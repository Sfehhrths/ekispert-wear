package dev.sfehhrths.ekispertwear

import android.content.Context
import android.util.Log
import dev.sfehhrths.ekispertwear.parser.EkispertXmlParser
import dev.sfehhrths.ekispertwear.parser.RealtimeTripParser
import dev.sfehhrths.ekispertwear.shared.Course
import dev.sfehhrths.ekispertwear.shared.CourseJson
import dev.sfehhrths.ekispertwear.shared.CoursePayload
import dev.sfehhrths.ekispertwear.shared.CourseSource
import dev.sfehhrths.ekispertwear.wear.WearSync
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.io.File

/** Where a pooled course came from. Decides the [CourseSource] shown on the watch. */
@Serializable
enum class PoolOrigin {
    /** `search/course/extreme` (one response per sort tab). */
    SEARCH,

    /** `course/edit` (前後のダイヤ, 区間のダイヤ選択 ...). */
    EDIT,

    /** Single-course XML of a MyClip (お気に入り) whose detail screen opened. */
    MYCLIP,

    /** Single-course XML saved when a transfer alarm was set. */
    TRANSFER_ALARM,
}

/**
 * Single source of truth on the phone.
 *
 * Every `ResultSet/Course` the app receives or loads is kept in a pool keyed by its
 * `SerializeData`. When the app shows a course in the detail screen the patch sends that
 * course's `SerializeData` and the pool tells us which course it is, no matter which sort tab,
 * re-search (前後のダイヤ), MyClip or alarm it came from. Positions/indexes are never used.
 *
 * Policy: "the course the user last touched" wins. Opening / swiping to a course in the
 * detail screen and setting a transfer alarm both replace the current payload; whichever
 * happened last is what the watch shows.
 */
class CourseRepository private constructor(private val context: Context) {

    @Serializable
    data class PooledCourse(
        /** `Course/SerializeData`. */
        val key: String,
        val origin: PoolOrigin,
        /** Epoch millis when the ResultSet carrying this course arrived. */
        val receivedAt: Long,
        val course: Course,
    )

    @Serializable
    data class State(
        /** Recently seen courses, newest ResultSet first, at most [POOL_MAX]. */
        val pool: List<PooledCourse> = emptyList(),
        /** Last payload pushed to the watch. */
        val current: CoursePayload? = null,
        /** Latest 運行情報 (rescuenow), applied to every course pushed. */
        val serviceInfo: List<EkispertXmlParser.ServiceInformation> = emptyList(),
        val serviceInfoAt: Long = 0,
        val lastEvent: String = "",
        val lastEventAt: Long = 0,
    )

    private val mutex = Mutex()
    private val file = File(context.filesDir, "state.json")
    private val _state = MutableStateFlow(load())
    val state: StateFlow<State> = _state

    /** A `ResultSet/Course[]` response (route search per sort tab, or course/edit). */
    suspend fun onCoursesReceived(xml: String, origin: PoolOrigin, ts: Long) = mutex.withLock {
        val courses = EkispertXmlParser.parseCourses(xml)
        Log.i(Logs.TAG, "$origin: ${courses.size} courses (${xml.length} chars)")
        val added = pool(courses, origin, ts)
        update { it.copy(lastEvent = "${origin.name.lowercase()}:$added", lastEventAt = ts) }
    }

    /** The detail screen that is opening shows this MyClip course (arrives before selected_course). */
    suspend fun onMyClipCourse(xml: String, ts: Long) = mutex.withLock {
        val courses = EkispertXmlParser.parseCourses(xml)
        if (courses.isEmpty()) {
            Log.w(Logs.TAG, "myclip xml had no Course")
            return
        }
        Log.i(Logs.TAG, "myclip course ${courses.first().departure} -> ${courses.first().arrival}")
        pool(courses, PoolOrigin.MYCLIP, ts)
        update { it.copy(lastEvent = "myclip", lastEventAt = ts) }
    }

    /**
     * The app is showing a course whose String fields are [keys] (one of them is its
     * SerializeData). Look it up in the pool and push it.
     */
    suspend fun onCourseSelected(keys: List<String>, presenter: String, ts: Long) = mutex.withLock {
        val hit = _state.value.pool.firstOrNull { it.key in keys }
        if (hit == null) {
            Log.w(
                Logs.TAG,
                "selected course in $presenter not in pool (${_state.value.pool.size} courses); " +
                    "keys=${keys.map { it.take(24) }}",
            )
            update { it.copy(lastEvent = "selected (unknown course)", lastEventAt = ts) }
            return
        }
        val source = when (hit.origin) {
            PoolOrigin.SEARCH, PoolOrigin.EDIT -> CourseSource.SELECTED
            PoolOrigin.MYCLIP -> CourseSource.MYCLIP
            PoolOrigin.TRANSFER_ALARM -> CourseSource.TRANSFER_ALARM
        }
        val course = hit.course
        Log.i(Logs.TAG, "selected ${hit.origin} course #${course.index} ${course.departure} -> ${course.arrival}")
        push(CoursePayload(source, ts, hit.receivedAt, course), "selected:${hit.origin.name.lowercase()}#${course.index}", ts)
    }

    suspend fun onTransferAlarmCourse(xml: String, ts: Long) = mutex.withLock {
        val courses = EkispertXmlParser.parseCourses(xml)
        val course = courses.firstOrNull()
        if (course == null) {
            Log.w(Logs.TAG, "transfer alarm xml had no Course")
            return
        }
        Log.i(Logs.TAG, "transfer alarm course #${course.index} ${course.departure} -> ${course.arrival}")
        pool(courses, PoolOrigin.TRANSFER_ALARM, ts)
        push(CoursePayload(CourseSource.TRANSFER_ALARM, ts, ts, course), "alarm:${course.index}", ts)
    }

    /**
     * Adds [courses] to the front of the pool (a course already present is replaced so it
     * carries the newest origin / timestamp) and trims to [POOL_MAX]. Returns how many had a
     * SerializeData; courses without one cannot be matched and are dropped.
     */
    private fun pool(courses: List<Course>, origin: PoolOrigin, ts: Long): Int {
        val fresh = courses.mapNotNull { c ->
            val key = c.serializeData
            if (key == null) {
                Log.w(Logs.TAG, "course #${c.index} from $origin has no SerializeData; not pooled")
                null
            } else {
                PooledCourse(key, origin, ts, c)
            }
        }
        if (fresh.isEmpty()) return 0
        val freshKeys = fresh.mapTo(HashSet()) { it.key }
        update { s ->
            s.copy(pool = (fresh + s.pool.filterNot { it.key in freshKeys }).take(POOL_MAX))
        }
        return fresh.size
    }

    /** 運行情報 XML: remember it and re-apply to the current course if any status changed. */
    suspend fun onServiceInformation(xml: String, ts: Long) = mutex.withLock {
        val info = EkispertXmlParser.parseServiceInformation(xml)
        Log.i(Logs.TAG, "service info: ${info.size} entries")
        update { it.copy(serviceInfo = info, serviceInfoAt = ts) }
        val cur = _state.value.current ?: return
        val refreshed = cur.copy(course = applyServiceInfo(cur.course, info))
        if (refreshed.course != cur.course) {
            push(refreshed, "service-info", ts)
        }
    }

    /** mixway realtime/trip JSON: overlay delays onto the current course's lines by tripCode. */
    suspend fun onRealtimeTrip(body: String, ts: Long) = mutex.withLock {
        val result = runCatching { RealtimeTripParser.parse(body) }
            .onFailure { Log.w(Logs.TAG, "realtime/trip parse failed", it) }
            .getOrNull() ?: return
        val cur = _state.value.current ?: return
        val byCode = result.trips.associateBy { it.tripCode }
        var touched = false
        val lines = cur.course.lines.map { ln ->
            val trip = ln.tripCode?.let(byCode::get) ?: return@map ln
            if (trip.operationDate != null && trip.operationDate != departureDate(ln.departure)) return@map ln
            touched = true
            ln.copy(delayMinutes = trip.delayMinutes ?: 0)
        }
        Log.i(Logs.TAG, "realtime/trip: ${result.trips.size} trips, matched=$touched @ ${result.timestamp}")
        // The app polls every 60 s while a detail page is visible; only bother the watch when a
        // line of the current course actually got realtime data.
        if (!touched) return
        push(cur.copy(course = cur.course.copy(lines = lines), realtimeAt = result.timestamp), "realtime", ts)
    }

    /** Re-sends the current payload (e.g. after the watch was reconnected). */
    suspend fun resend() = mutex.withLock {
        val cur = _state.value.current ?: return
        WearSync.putCourse(context, cur)
    }

    private suspend fun push(payload: CoursePayload, event: String, ts: Long) {
        val withStatus = payload.copy(course = applyServiceInfo(payload.course, _state.value.serviceInfo))
        update { it.copy(current = withStatus, lastEvent = event, lastEventAt = ts) }
        WearSync.putCourse(context, withStatus)
    }

    /** Marks lines whose operation-line codes appear in a 運行情報 entry (same join as the app). */
    private fun applyServiceInfo(course: Course, info: List<EkispertXmlParser.ServiceInformation>): Course {
        if (info.isEmpty()) return course
        return course.copy(
            lines = course.lines.map { ln ->
                val hit = ln.operationLineCodes.takeIf { it.isNotEmpty() }?.let { codes ->
                    info.firstOrNull { i -> i.lineCodes.any(codes::contains) }
                }
                if (hit == null) {
                    if (ln.serviceStatus == null) ln else ln.copy(serviceStatus = null, serviceComment = null)
                } else {
                    ln.copy(serviceStatus = hit.status, serviceComment = hit.shortComment ?: hit.title)
                }
            },
        )
    }

    /** "2026-09-11T05:42:00+09:00" -> "20260911" */
    private fun departureDate(iso: String): String? =
        iso.takeIf { it.length >= 10 }?.substring(0, 10)?.replace("-", "")

    private fun update(f: (State) -> State) {
        val next = f(_state.value)
        _state.value = next
        runCatching { file.writeText(CourseJson.encodeToString(State.serializer(), next)) }
            .onFailure { Log.w(Logs.TAG, "persist failed", it) }
    }

    private fun load(): State = runCatching {
        if (file.exists()) CourseJson.decodeFromString(State.serializer(), file.readText()) else State()
    }.getOrElse {
        Log.w(Logs.TAG, "state load failed; starting empty", it)
        State()
    }

    companion object {
        /**
         * Pool size. A search returns up to 20 courses per sort tab (4 tabs), and 前後のダイヤ
         * adds a course per step; 200 comfortably covers a session of browsing.
         */
        private const val POOL_MAX = 200

        @Volatile
        private var instance: CourseRepository? = null

        fun get(context: Context): CourseRepository =
            instance ?: synchronized(this) {
                instance ?: CourseRepository(context.applicationContext).also { instance = it }
            }
    }
}

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

/**
 * Single source of truth on the phone.
 *
 * Policy: "the course the user last touched" wins. Opening / swiping to a course in the
 * detail screen and setting a transfer alarm both replace the current payload; whichever
 * happened last is what the watch shows.
 */
class CourseRepository private constructor(private val context: Context) {

    @Serializable
    data class State(
        /** Courses of the latest `search/course/extreme` response, in ResultSet order. */
        val searchCourses: List<Course> = emptyList(),
        val searchedAt: Long = 0,
        /** Last payload pushed to the watch. */
        val current: CoursePayload? = null,
        /**
         * MyClip course loaded for the detail screen that is currently opening. Set by
         * `myclip_course`, cleared by `detail_opened`; consumed by the next `selected_course`.
         */
        val pendingMyClip: Course? = null,
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

    suspend fun onRouteSearchResult(xml: String, ts: Long) = mutex.withLock {
        val courses = EkispertXmlParser.parseCourses(xml)
        Log.i(Logs.TAG, "route search: ${courses.size} courses (${xml.length} chars)")
        update { it.copy(searchCourses = courses, searchedAt = ts, lastEvent = "search:${courses.size}", lastEventAt = ts) }
    }

    /** A detail screen is opening; forget any MyClip course from the previous one. */
    suspend fun onDetailOpened(ts: Long) = mutex.withLock {
        if (_state.value.pendingMyClip != null) {
            update { it.copy(pendingMyClip = null) }
        }
    }

    /** The detail screen that is opening shows this MyClip course (arrives before selected_course). */
    suspend fun onMyClipCourse(xml: String, ts: Long) = mutex.withLock {
        val course = EkispertXmlParser.parseCourses(xml).firstOrNull()
        if (course == null) {
            Log.w(Logs.TAG, "myclip xml had no Course")
            return
        }
        Log.i(Logs.TAG, "myclip course #${course.index} ${course.departure} -> ${course.arrival}")
        update { it.copy(pendingMyClip = course, lastEvent = "myclip:${course.index}", lastEventAt = ts) }
    }

    suspend fun onCourseSelected(index0: Int, ts: Long) = mutex.withLock {
        val s = _state.value
        s.pendingMyClip?.let { myClip ->
            // MyClip detail screens hold exactly one course; the page index is always 0.
            Log.i(Logs.TAG, "selected MyClip course #${myClip.index}")
            push(CoursePayload(CourseSource.MYCLIP, ts, 0, myClip), "selected-myclip:${myClip.index}", ts)
            return
        }
        val course = s.searchCourses.getOrNull(index0)
        if (course == null) {
            Log.w(Logs.TAG, "selected index $index0 but only ${s.searchCourses.size} courses known")
            update { it.copy(lastEvent = "selected:$index0 (no result)", lastEventAt = ts) }
            return
        }
        Log.i(Logs.TAG, "selected course #${course.index} ${course.departure} -> ${course.arrival}")
        push(CoursePayload(CourseSource.SELECTED, ts, s.searchedAt, course), "selected:${course.index}", ts)
    }

    suspend fun onTransferAlarmCourse(xml: String, ts: Long) = mutex.withLock {
        val course = EkispertXmlParser.parseCourses(xml).firstOrNull()
        if (course == null) {
            Log.w(Logs.TAG, "transfer alarm xml had no Course")
            return
        }
        Log.i(Logs.TAG, "transfer alarm course #${course.index} ${course.departure} -> ${course.arrival}")
        push(CoursePayload(CourseSource.TRANSFER_ALARM, ts, _state.value.searchedAt, course), "alarm:${course.index}", ts)
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
        @Volatile
        private var instance: CourseRepository? = null

        fun get(context: Context): CourseRepository =
            instance ?: synchronized(this) {
                instance ?: CourseRepository(context.applicationContext).also { instance = it }
            }
    }
}

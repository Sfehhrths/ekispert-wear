package dev.sfehhrths.ekispertwear

import dev.sfehhrths.ekispertwear.shared.Course
import dev.sfehhrths.ekispertwear.shared.Line
import java.time.OffsetDateTime

/**
 * Time-based derivations shared by the app pages and the tiles.
 * All "now" values are epoch millis so callers can tick.
 */
object CourseLogic {

    // --- time helpers --------------------------------------------------------------------------

    fun epoch(iso: String?): Long? =
        iso?.takeIf { it.length >= 19 }?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

    /** "2026-09-11T05:42:00+09:00" -> "05:42" */
    fun hhmm(iso: String?): String = iso?.takeIf { it.length >= 16 }?.substring(11, 16) ?: "--:--"

    /** "2026-09-11T05:42:00+09:00" -> "2026/09/11" */
    fun dateSlash(iso: String?): String = iso?.takeIf { it.length >= 10 }?.substring(0, 10)?.replace('-', '/') ?: ""

    /** Countdown text like Apple Watch: `m:ss` under an hour, `h:mm:ss` above. Negative -> 0:00. */
    fun countdown(millis: Long): String {
        val total = (millis / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    val walkColor = 0xFFD8D8DC.toInt()

    /**
     * "4番線" / "1・2番線" for trains, "3のりば" for buses (same suffix rule as the official app),
     * bare value for anything else; null when the API gave no platform.
     */
    fun platformLabel(line: Line, platform: String?): String? {
        val p = platform?.trim()?.ifBlank { null } ?: return null
        return when (line.type) {
            "train" -> "${p}番線"
            "bus" -> "${p}のりば"
            else -> p
        }
    }

    fun lineColor(line: Line, fallback: Int = 0xFF4C8DFF.toInt()): Int =
        if (line.type == "walk") walkColor
        else line.colorRgb?.let { runCatching { android.graphics.Color.parseColor(it) }.getOrNull() } ?: fallback

    // --- タイマー ------------------------------------------------------------------------------

    data class TimerTarget(
        /** Station whose departure (or the final arrival) we count down to. */
        val stationName: String,
        /** Ready-to-show platform text ("1番線"), or null. */
        val platformLabel: String?,
        val targetMillis: Long,
        /** true = "到着まであと" (no more departures), false = "出発まであと". */
        val isArrival: Boolean,
    )

    /**
     * Next boarding: the earliest non-walk leg whose departure is still ahead. Once every
     * departure has passed, count down to the final arrival; null when the course is over.
     */
    fun timerTarget(course: Course, now: Long): TimerTarget? {
        course.lines.forEachIndexed { i, ln ->
            if (ln.type == "walk") return@forEachIndexed
            val dep = epoch(ln.departure) ?: return@forEachIndexed
            if (dep > now) {
                val from = course.points.getOrNull(i)?.name ?: ""
                return TimerTarget(from, platformLabel(ln, ln.departurePlatform), dep, isArrival = false)
            }
        }
        val last = course.lines.lastOrNull() ?: return null
        val arr = epoch(last.arrival) ?: return null
        if (arr > now) {
            val to = course.points.lastOrNull()?.name ?: ""
            return TimerTarget(to, platformLabel(last, last.arrivalPlatform), arr, isArrival = true)
        }
        return null
    }

    // --- イマココ -----------------------------------------------------------------------------

    /** A station row in the flattened stop sequence. */
    data class Node(
        val name: String,
        /** Boarding / alighting / transfer station -> square marker + bold; else small circle. */
        val isMajor: Boolean,
        val arrival: Long?,
        val departure: Long?,
    )

    /** The line segment drawn below node [fromIndex] (to the next node). */
    data class Segment(val fromIndex: Int, val color: Int, val isWalk: Boolean)

    data class Sequence(val nodes: List<Node>, val segments: List<Segment>)

    sealed class Position {
        data class Stopped(val nodeIndex: Int) : Position()
        data class Between(val fromIndex: Int) : Position()
        object None : Position()
    }

    /**
     * Flattens the course into stations + segments. Each leg contributes its stops (major at
     * both ends); consecutive legs share their boundary station. Legs without a stop list fall
     * back to their two end points.
     */
    fun sequence(course: Course): Sequence {
        val nodes = mutableListOf<Node>()
        val segments = mutableListOf<Segment>()
        course.lines.forEachIndexed { i, ln ->
            val legNodes: List<Node> = if (ln.stops.size >= 2) {
                ln.stops.mapIndexed { si, st ->
                    Node(
                        name = st.name,
                        isMajor = si == 0 || si == ln.stops.lastIndex,
                        arrival = epoch(st.arrival),
                        departure = epoch(st.departure),
                    )
                }
            } else {
                val from = course.points.getOrNull(i)?.name ?: ""
                val to = course.points.getOrNull(i + 1)?.name ?: ""
                listOf(
                    Node(from, true, epoch(ln.departure), epoch(ln.departure)),
                    Node(to, true, epoch(ln.arrival), epoch(ln.arrival)),
                )
            }
            val color = lineColor(ln)
            legNodes.forEachIndexed { si, n ->
                if (si == 0 && nodes.isNotEmpty()) {
                    // Boundary station: merge with the previous leg's last node.
                    val prev = nodes.removeAt(nodes.lastIndex)
                    nodes += Node(
                        name = prev.name.ifBlank { n.name },
                        isMajor = true,
                        arrival = prev.arrival ?: n.arrival,
                        departure = n.departure ?: prev.departure,
                    )
                } else {
                    nodes += n
                }
                if (si < legNodes.lastIndex) {
                    segments += Segment(nodes.lastIndex, color, ln.type == "walk")
                }
            }
        }
        return Sequence(nodes, segments)
    }

    /** "時刻表通りに運行した場合の現在位置の目安" — purely from the timetable. */
    fun position(seq: Sequence, now: Long): Position {
        val nodes = seq.nodes
        for (i in nodes.indices) {
            val n = nodes[i]
            val arr = n.arrival ?: n.departure
            val dep = n.departure ?: n.arrival
            if (arr != null && dep != null && now >= arr && now < dep) return Position.Stopped(i)
            // Treat the minute of departure at a station as "stopped" so the arrow does not jump
            // to "between" before the train has actually left.
            if (dep != null && now >= dep && now < dep + 60_000 && (i == nodes.lastIndex || (nodes[i + 1].arrival ?: Long.MAX_VALUE) > now)) {
                if (i < nodes.lastIndex && (nodes[i + 1].arrival ?: Long.MAX_VALUE) - dep <= 60_000) {
                    // very short hop: prefer "between"
                } else {
                    return Position.Stopped(i)
                }
            }
            if (i < nodes.lastIndex) {
                val nextArr = nodes[i + 1].arrival ?: nodes[i + 1].departure
                if (dep != null && nextArr != null && now >= dep && now < nextArr) return Position.Between(i)
            }
        }
        return Position.None
    }
}

package dev.sfehhrths.ekispertwear.parser

import android.util.Xml
import dev.sfehhrths.ekispertwear.shared.Course
import dev.sfehhrths.ekispertwear.shared.Line
import dev.sfehhrths.ekispertwear.shared.Point
import dev.sfehhrths.ekispertwear.shared.Stop
import kotlinx.serialization.Serializable
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/**
 * Parses an Ekispert `ResultSet/Course[]` response (`search/course/extreme`, `course/edit`)
 * into the shared wire model. Also accepts the single-course XML the app stores for MyClip
 * and transfer alarms.
 *
 * Only the subset needed on the watch is extracted; everything else is skipped.
 */
object EkispertXmlParser {

    /** Clock jumps larger than this between consecutive stop times are treated as a date change. */
    private const val HALF_DAY_SECONDS = 12 * 3600

    fun parseCourses(xml: String): List<Course> {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(StringReader(xml))
        }
        val courses = mutableListOf<Course>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "Course") {
                courses += readCourse(parser)
            }
        }
        return courses
    }

    private fun readCourse(p: XmlPullParser): Course {
        val searchType = p.getAttributeValue(null, "searchType")
        var index = 0
        var serializeData: String? = null
        var transferCount = 0
        var timeOnBoard = 0
        var timeWalk = 0
        var timeOther = 0
        var fareOneway: Int? = null
        var chargeOneway: Int? = null
        val points = mutableListOf<Point>()
        val lines = mutableListOf<Line>()
        val operationLineCodes = mutableMapOf<Int, MutableList<String>>() // routeLineIndex -> codes

        p.forEachChild("Course") { name ->
            when (name) {
                "Route" -> {
                    index = p.intAttr("index")
                    transferCount = p.intAttr("transferCount")
                    timeOnBoard = p.intAttr("timeOnBoard")
                    timeWalk = p.intAttr("timeWalk")
                    timeOther = p.intAttr("timeOther")
                    p.forEachChild("Route") { child ->
                        when (child) {
                            "Point" -> points += readPoint(p)
                            "Line" -> lines += readLine(p)
                            else -> p.skip()
                        }
                    }
                }

                "SerializeData" -> serializeData = p.text().trim().ifEmpty { null }

                "Price" -> {
                    when (p.getAttributeValue(null, "kind")) {
                        "FareSummary" -> fareOneway = readOneway(p)
                        "ChargeSummary" -> chargeOneway = readOneway(p)
                        else -> p.skip()
                    }
                }

                "OperationLinePattern" -> {
                    val routeLineIndex = p.intAttr("routeLineIndex")
                    val codes = operationLineCodes.getOrPut(routeLineIndex) { mutableListOf() }
                    p.forEachChild("OperationLinePattern") { child ->
                        if (child == "Line") {
                            p.getAttributeValue(null, "code")?.let { codes += it }
                        }
                        p.skip()
                    }
                }

                else -> p.skip()
            }
        }
        val linesWithCodes = lines.map { ln ->
            operationLineCodes[ln.index]?.let { ln.copy(operationLineCodes = it.toList()) } ?: ln
        }
        return Course(
            index = index,
            serializeData = serializeData,
            searchType = searchType,
            transferCount = transferCount,
            timeOnBoard = timeOnBoard,
            timeWalk = timeWalk,
            timeOther = timeOther,
            fareOneway = fareOneway,
            chargeOneway = chargeOneway,
            points = points,
            lines = linesWithCodes,
        )
    }

    private fun readPoint(p: XmlPullParser): Point {
        val index = p.intAttr("index")
        var name = ""
        var type: String? = null
        var code: String? = null
        p.forEachChild("Point") { child ->
            when (child) {
                "Station" -> {
                    code = p.getAttributeValue(null, "code")
                    p.forEachChild("Station") { s ->
                        when (s) {
                            "Name" -> name = p.text()
                            "Type" -> type = p.text()
                            else -> p.skip()
                        }
                    }
                }

                else -> p.skip()
            }
        }
        return Point(index = index, name = name, type = type, stationCode = code)
    }

    private fun readLine(p: XmlPullParser): Line {
        val index = p.intAttr("index")
        val timeOnBoard = p.intAttr("timeOnBoard")
        val stopStationCount = p.getAttributeValue(null, "stopStationCount")?.toIntOrNull()
        val tripCode = p.getAttributeValue(null, "tripCode")?.ifBlank { null }
        var name = ""
        var type = ""
        var typeDetail: String? = null
        var trainType: String? = null
        var destination: String? = null
        var corporation: String? = null
        var color: String? = null
        var departure = ""
        var arrival = ""
        var depPlatform: String? = null
        var arrPlatform: String? = null
        val rawStops = mutableListOf<RawStop>()

        p.forEachChild("Line") { child ->
            when (child) {
                "Name" -> name = p.text()
                "Type" -> {
                    typeDetail = p.getAttributeValue(null, "detail")
                    trainType = p.getAttributeValue(null, "trainType")
                    type = p.text()
                }

                "Destination" -> destination = p.text().ifBlank { null }
                "Color" -> color = p.text()
                "Corporation" -> p.forEachChild("Corporation") { c ->
                    if (c == "Name") corporation = p.text().ifBlank { null } else p.skip()
                }

                "DepartureState" -> {
                    val (dt, no) = readState(p, "DepartureState")
                    departure = dt
                    depPlatform = no
                }

                "ArrivalState" -> {
                    val (dt, no) = readState(p, "ArrivalState")
                    arrival = dt
                    arrPlatform = no
                }

                "InsideInformation" -> p.forEachChild("InsideInformation") { ii ->
                    if (ii == "Stop") rawStops += readStop(p) else p.skip()
                }

                else -> p.skip()
            }
        }
        return Line(
            index = index,
            name = name,
            type = type,
            typeDetail = typeDetail,
            trainType = trainType,
            destination = destination,
            corporation = corporation,
            colorRgb = toRgb(color),
            departure = departure,
            arrival = arrival,
            timeOnBoard = timeOnBoard,
            stopStationCount = stopStationCount,
            departurePlatform = depPlatform,
            arrivalPlatform = arrPlatform,
            tripCode = tripCode,
            stops = resolveStops(rawStops, departure),
        )
    }

    private class RawStop(val name: String, val code: String?, val arrival: String, val departure: String)

    /**
     * `<Stop index="1"><ArrivalState><Datetime>05:42:00+09:00</Datetime></ArrivalState>
     *   <Point getOff getOn><Station code><Name>…</Name></Station>…</Point>
     *   <DepartureState><Datetime>05:42:00+09:00</Datetime></DepartureState></Stop>`
     */
    private fun readStop(p: XmlPullParser): RawStop {
        var name = ""
        var code: String? = null
        var arr = ""
        var dep = ""
        p.forEachChild("Stop") { child ->
            when (child) {
                "ArrivalState" -> arr = readState(p, "ArrivalState").first
                "DepartureState" -> dep = readState(p, "DepartureState").first
                "Point" -> p.forEachChild("Point") { pt ->
                    if (pt == "Station") {
                        code = p.getAttributeValue(null, "code")
                        p.forEachChild("Station") { s -> if (s == "Name") name = p.text() else p.skip() }
                    } else {
                        p.skip()
                    }
                }

                else -> p.skip()
            }
        }
        return RawStop(name, code, arr, dep)
    }

    /**
     * Stop times come as `HH:mm:ss+09:00` (no date). Attach the leg's departure date, then walk
     * the sequence and pick, for each clock time, the day closest to the previous time: a jump
     * backwards of more than 12 hours means the next day, a jump forwards of more than 12 hours
     * means the previous day. Small reversals are normal and must NOT roll the date: the boarding
     * stop's ArrivalState (when the train pulled in) precedes the leg's departure time, and a
     * stop's arrival can be listed one minute after its departure in the raw data.
     */
    private fun resolveStops(raw: List<RawStop>, legDeparture: String): List<Stop> {
        if (raw.isEmpty()) return emptyList()
        val datePart = legDeparture.takeIf { it.length >= 10 }?.substring(0, 10)
            ?: return raw.map { Stop(it.name, it.code, it.arrival, it.departure) }
        var date = java.time.LocalDate.parse(datePart)
        var lastSeconds = secondsOfDay(legDeparture)
        fun full(t: String): String {
            val seconds = secondsOfDay(t) ?: return ""
            if (lastSeconds != null) {
                val delta = seconds - lastSeconds!!
                if (delta < -HALF_DAY_SECONDS) date = date.plusDays(1)
                else if (delta > HALF_DAY_SECONDS) date = date.minusDays(1)
            }
            lastSeconds = seconds
            return "${date}T$t"
        }
        return raw.map { Stop(it.name, it.code, full(it.arrival), full(it.departure)) }
    }

    /** `HH:mm:ss…` (Stop) or `yyyy-MM-ddTHH:mm:ss…` (leg) to seconds since midnight, or null. */
    private fun secondsOfDay(t: String): Int? {
        val clock = when {
            t.length >= 19 && t[10] == 'T' -> t.substring(11, 19)
            t.length >= 8 -> t.substring(0, 8)
            else -> return null
        }
        val parts = clock.split(':')
        if (parts.size != 3) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        val s = parts[2].toIntOrNull() ?: return null
        return h * 3600 + m * 60 + s
    }

    // --- 運行情報 (operationLine/service/rescuenow/information) --------------------------------

    @Serializable
    data class ServiceInformation(
        val status: String,
        val lineCodes: List<String>,
        val title: String?,
        val shortComment: String?,
    )

    /** Parses `ResultSet/Information[]` of the rescuenow 運行情報 response. */
    fun parseServiceInformation(xml: String): List<ServiceInformation> {
        val p = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(StringReader(xml))
        }
        val out = mutableListOf<ServiceInformation>()
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType != XmlPullParser.START_TAG || p.name != "Information") continue
            val status = p.getAttributeValue(null, "status") ?: ""
            val codes = mutableListOf<String>()
            var title: String? = null
            var short: String? = null
            var long: String? = null
            p.forEachChild("Information") { child ->
                when (child) {
                    "Line" -> {
                        p.getAttributeValue(null, "code")?.let { codes += it }
                        p.skip()
                    }

                    "Title" -> title = p.text().ifBlank { null }
                    "Comment" -> {
                        val kind = p.getAttributeValue(null, "status")
                        val t = p.text().ifBlank { null }
                        if (kind == "short") short = t else if (long == null) long = t
                    }

                    else -> p.skip()
                }
            }
            out += ServiceInformation(status, codes, title, short ?: long)
        }
        return out
    }

    /**
     * Returns (Datetime text, platform). The platform (番線 / のりば) is the `no` attribute of
     * `<DepartureState no="4">` / `<ArrivalState no="1・2">` — the same attribute the official
     * app reads; it can be non-numeric ("京葉1") or a list ("1・2").
     */
    private fun readState(p: XmlPullParser, tag: String): Pair<String, String?> {
        var dt = ""
        val no = p.getAttributeValue(null, "no")?.trim()?.ifBlank { null }
        p.forEachChild(tag) { child ->
            when (child) {
                "Datetime" -> dt = p.text()
                else -> p.skip()
            }
        }
        return dt to no
    }

    private fun readOneway(p: XmlPullParser): Int? {
        var v: Int? = null
        p.forEachChild("Price") { child ->
            if (child == "Oneway") v = p.text().toIntOrNull() else p.skip()
        }
        return v
    }

    /** Ekispert colours are 9-digit decimal "RRRGGGBBB" (e.g. 254094051). */
    private fun toRgb(raw: String?): String? {
        val s = raw?.trim() ?: return null
        if (s.length != 9 || !s.all { it.isDigit() }) return null
        val r = s.substring(0, 3).toInt()
        val g = s.substring(3, 6).toInt()
        val b = s.substring(6, 9).toInt()
        return "#%02X%02X%02X".format(r, g, b)
    }

    // --- XmlPullParser helpers ---------------------------------------------------------------

    private fun XmlPullParser.intAttr(name: String): Int =
        getAttributeValue(null, name)?.toIntOrNull() ?: 0

    /** Reads the text of the current element and consumes its END_TAG. */
    private fun XmlPullParser.text(): String {
        var result = ""
        if (next() == XmlPullParser.TEXT) {
            result = text ?: ""
            nextTag()
        }
        return result
    }

    /**
     * Iterates the direct children of the current START_TAG [parent]; [block] is called
     * positioned on each child's START_TAG and must consume that child entirely.
     */
    private inline fun XmlPullParser.forEachChild(parent: String, block: (String) -> Unit) {
        require(eventType == XmlPullParser.START_TAG && name == parent) { "expected <$parent>, at $name" }
        while (true) {
            when (next()) {
                XmlPullParser.START_TAG -> block(name)
                XmlPullParser.END_TAG -> if (name == parent) return
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    /** Skips the current element including all nested content. */
    private fun XmlPullParser.skip() {
        require(eventType == XmlPullParser.START_TAG)
        var depth = 1
        while (depth != 0) {
            when (next()) {
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }
}

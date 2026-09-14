package dev.sfehhrths.ekispertwear.shared

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wire model shared by the phone companion and the Wear OS app.
 * Built by the companion from the Ekispert `ResultSet/Course` XML; the watch only ever sees this.
 * Times are the ISO-8601 strings from the XML (e.g. `2026-09-11T05:42:00+09:00`).
 */
@Serializable
data class CoursePayload(
    val source: CourseSource,
    /** Epoch millis when the companion built this payload. */
    val updatedAt: Long,
    /** Epoch millis of the search response this course came from (0 if unknown). */
    val searchedAt: Long,
    val course: Course,
    /** `realtimeDataTimestamp` of the last mixway realtime/trip response applied, ISO-8601, or null. */
    val realtimeAt: String? = null,
)

@Serializable
enum class CourseSource {
    /** The course the user last opened / swiped to in the detail screen. */
    SELECTED,

    /** The course the user set a transfer alarm for. */
    TRANSFER_ALARM,

    /** A saved MyClip (お気に入り) course the user opened. */
    MYCLIP,
}

@Serializable
data class Course(
    /** `Route@index` (1-based, as in the XML). Only meaningful within its own ResultSet. */
    val index: Int,
    /**
     * `Course/SerializeData`: the API's opaque identity of this course. The companion uses it
     * to find the course the user opened in the app among all ResultSets it has received.
     */
    val serializeData: String? = null,
    /** `Course@searchType`: departure / arrival / firstTrain / lastTrain / plain. */
    val searchType: String? = null,
    val transferCount: Int,
    val timeOnBoard: Int,
    val timeWalk: Int,
    val timeOther: Int,
    /** `Price[@kind=FareSummary]/Oneway` in yen, if present. */
    val fareOneway: Int? = null,
    /** `Price[@kind=ChargeSummary]/Oneway` (limited express etc.) in yen, if present. */
    val chargeOneway: Int? = null,
    /** Stations in order. `lines[i]` runs from `points[i]` to `points[i+1]`. */
    val points: List<Point>,
    val lines: List<Line>,
) {
    val departure: String? get() = lines.firstOrNull()?.departure
    val arrival: String? get() = lines.lastOrNull()?.arrival

    /** Fare + charges, i.e. the total the app shows. Null if no fare is known. */
    val totalOneway: Int? get() = fareOneway?.let { it + (chargeOneway ?: 0) }

    val totalMinutes: Int get() = timeOnBoard + timeWalk + timeOther
}

@Serializable
data class Point(
    /** `Point@index` (1-based). */
    val index: Int,
    val name: String,
    /** `Station/Type`: train / bus / plane / ship / walk ... */
    val type: String? = null,
    val stationCode: String? = null,
)

@Serializable
data class Line(
    /** `Line@index` (1-based). */
    val index: Int,
    val name: String,
    /** `Line/Type`: train / bus / walk / plane / ship / strange ... */
    val type: String,
    /** `Line/Type@detail` (e.g. limitedExpress, local) if present. */
    val typeDetail: String? = null,
    /** `Line/Type@trainType` (none / ltdExpress ...) if present. */
    val trainType: String? = null,
    val destination: String? = null,
    val corporation: String? = null,
    /** `#RRGGBB` derived from `Line/Color` (9-digit decimal RRRGGGBBB), or null. */
    val colorRgb: String? = null,
    /** ISO-8601, from `DepartureState/Datetime`. Empty for walks without times. */
    val departure: String = "",
    /** ISO-8601, from `ArrivalState/Datetime`. */
    val arrival: String = "",
    val timeOnBoard: Int = 0,
    val stopStationCount: Int? = null,
    /** `Line/DepartureState/No`? Track/platform text when the API provides it. */
    val departurePlatform: String? = null,
    val arrivalPlatform: String? = null,
    /** `Line@tripCode`; key into mixway realtime/trip. Null for walks. */
    val tripCode: String? = null,
    /**
     * Every stop of this leg including both ends (`InsideInformation/Stop[]`), in order.
     * Empty for walks and when the API gives no stop list. Times are full ISO-8601 (the
     * companion combines the API's time-only values with the leg's departure date).
     */
    val stops: List<Stop> = emptyList(),
    /** `Course/OperationLinePattern[@routeLineIndex=index]/Line@code`; key into 運行情報. */
    val operationLineCodes: List<String> = emptyList(),

    // --- realtime overlay (filled by the companion from later responses) ---------------------
    /** Delay in minutes from realtime/trip (0 = on time), or null if unknown. */
    val delayMinutes: Int? = null,
    /** 運行情報 `Information@status` for one of [operationLineCodes] (e.g. 運転見合わせ), or null. */
    val serviceStatus: String? = null,
    /** Short comment that goes with [serviceStatus]. */
    val serviceComment: String? = null,
)

@Serializable
data class Stop(
    val name: String,
    val stationCode: String? = null,
    /** ISO-8601 or empty. */
    val arrival: String = "",
    val departure: String = "",
)

/** Data Layer constants shared by both apps. */
object DataLayer {
    const val COURSE_PATH = "/ekispert/course"
    const val KEY_JSON = "json"
    const val KEY_UPDATED_AT = "updatedAt"
}

val CourseJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

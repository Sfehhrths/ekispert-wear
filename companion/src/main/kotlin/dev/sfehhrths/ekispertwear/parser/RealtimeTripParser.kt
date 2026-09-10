package dev.sfehhrths.ekispertwear.parser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.math.floor

/**
 * Parses mixway `realtime/trip` JSON:
 * ```
 * {"ResultSet":{"realtimeDataTimestamp":"…","Trip":[{"tripCode":"…","operationDate":"20260911",
 *   "From":{"Departure":{"Delay":{"unit":"minutes","text":"3"}}, "Arrival":{…}}, "To":{…},
 *   "StopPattern":{…}}]}}
 * ```
 * Ekispert JSON APIs may emit a single-element array as a plain object; handled here.
 */
object RealtimeTripParser {

    data class Trip(
        val tripCode: String,
        /** yyyyMMdd */
        val operationDate: String?,
        /** Minutes, first of From.Departure / From.Arrival / To.Arrival / To.Departure; null if none. */
        val delayMinutes: Int?,
    )

    data class Result(val timestamp: String?, val trips: List<Trip>)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(body: String): Result {
        val root = json.parseToJsonElement(body).jsonObject
        val rs = root["ResultSet"]?.asObject() ?: return Result(null, emptyList())
        val ts = rs["realtimeDataTimestamp"]?.asString()
        val trips = rs["Trip"].asList().mapNotNull { t ->
            val code = t["tripCode"]?.asString() ?: return@mapNotNull null
            Trip(
                tripCode = code,
                operationDate = t["operationDate"]?.asString(),
                delayMinutes = firstDelay(t),
            )
        }
        return Result(ts, trips)
    }

    private fun firstDelay(trip: JsonObject): Int? {
        val from = trip["From"]?.asObject()
        val to = trip["To"]?.asObject()
        val candidates = listOf(
            from?.get("Departure"), from?.get("Arrival"), to?.get("Arrival"), to?.get("Departure"),
        )
        for (c in candidates) {
            val delay = c?.asObject()?.get("Delay")?.asObject() ?: continue
            val text = delay["text"]?.asString()?.toDoubleOrNull() ?: continue
            val unit = delay["unit"]?.asString()
            return if (unit == "seconds") floor(text / 60.0).toInt() else text.toInt()
        }
        return null
    }

    // --- helpers ---------------------------------------------------------------------------

    private fun JsonElement?.asList(): List<JsonObject> = when (this) {
        is JsonArray -> mapNotNull { it.asObject() }
        is JsonObject -> listOf(this)
        else -> emptyList()
    }

    private fun JsonElement.asObject(): JsonObject? = this as? JsonObject
    private fun JsonElement.asString(): String? = (this as? JsonPrimitive)?.contentOrNull
}

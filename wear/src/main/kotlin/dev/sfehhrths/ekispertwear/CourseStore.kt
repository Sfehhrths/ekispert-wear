package dev.sfehhrths.ekispertwear

import android.content.Context
import android.util.Log
import dev.sfehhrths.ekispertwear.tiles.CourseTileBase
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import dev.sfehhrths.ekispertwear.shared.CourseJson
import dev.sfehhrths.ekispertwear.shared.CoursePayload
import dev.sfehhrths.ekispertwear.shared.DataLayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await
import java.io.File

/**
 * Holds the latest [CoursePayload] on the watch. Updated by [CourseListenerService] and, on
 * app start, by re-reading the Data Layer item so a missed change is recovered.
 */
object CourseStore {
    const val TAG = "EkispertWear"

    private val _payload = MutableStateFlow<CoursePayload?>(null)
    val payload: StateFlow<CoursePayload?> = _payload
    private var loaded = false

    fun init(context: Context) {
        if (loaded) return
        loaded = true
        val f = file(context)
        _payload.value = runCatching {
            if (f.exists()) CourseJson.decodeFromString(CoursePayload.serializer(), f.readText()) else null
        }.getOrNull()
    }

    fun update(context: Context, json: String) {
        val p = runCatching { CourseJson.decodeFromString(CoursePayload.serializer(), json) }
            .onFailure { Log.w(TAG, "bad payload json", it) }
            .getOrNull() ?: return
        _payload.value = p
        runCatching { file(context).writeText(json) }
        Log.i(TAG, "course updated: #${p.course.index} ${p.course.departure} -> ${p.course.arrival} (${p.source})")
        notifySurfaces(context)
    }

    /** Ask the three tiles to re-render from the new course. */
    private fun notifySurfaces(context: Context) {
        runCatching { CourseTileBase.requestUpdateAll(context) }
            .onFailure { Log.w(TAG, "tile update request failed: $it") }
    }

    /** Pull the current item from the Data Layer (covers changes missed while not installed/running). */
    suspend fun refreshFromDataLayer(context: Context) {
        try {
            val client = Wearable.getDataClient(context)
            val buffer = client.getDataItems(
                android.net.Uri.Builder().scheme("wear").path(DataLayer.COURSE_PATH).build(),
            ).await()
            try {
                val item = buffer.maxByOrNull {
                    DataMapItem.fromDataItem(it).dataMap.getLong(DataLayer.KEY_UPDATED_AT)
                } ?: return
                val map = DataMapItem.fromDataItem(item).dataMap
                val json = map.getString(DataLayer.KEY_JSON) ?: return
                val cur = _payload.value
                if (cur == null || map.getLong(DataLayer.KEY_UPDATED_AT) > cur.updatedAt) {
                    update(context, json)
                }
            } finally {
                buffer.release()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "refreshFromDataLayer failed: $t")
        }
    }

    private fun file(context: Context) = File(context.filesDir, "course.json")
}

package dev.sfehhrths.ekispertwear.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import dev.sfehhrths.ekispertwear.Logs
import dev.sfehhrths.ekispertwear.shared.CourseJson
import dev.sfehhrths.ekispertwear.shared.CoursePayload
import dev.sfehhrths.ekispertwear.shared.DataLayer
import kotlinx.coroutines.tasks.await

/** Pushes the current course to the watch through the Wearable Data Layer. */
object WearSync {

    suspend fun putCourse(context: Context, payload: CoursePayload) {
        val json = CourseJson.encodeToString(CoursePayload.serializer(), payload)
        val request = PutDataMapRequest.create(DataLayer.COURSE_PATH).apply {
            dataMap.putString(DataLayer.KEY_JSON, json)
            // Always changes, so identical courses still trigger onDataChanged on the watch.
            dataMap.putLong(DataLayer.KEY_UPDATED_AT, payload.updatedAt)
        }.asPutDataRequest().setUrgent()
        try {
            val item = Wearable.getDataClient(context).putDataItem(request).await()
            Log.i(Logs.TAG, "data item put ${item.uri} (${json.length} chars)")
        } catch (t: Throwable) {
            // Typically ApiException 17 (API unavailable) when no Wear OS device is paired.
            Log.w(Logs.TAG, "putDataItem failed: $t")
        }
    }
}

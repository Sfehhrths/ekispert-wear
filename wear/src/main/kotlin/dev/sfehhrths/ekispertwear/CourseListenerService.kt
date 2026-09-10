package dev.sfehhrths.ekispertwear

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import dev.sfehhrths.ekispertwear.shared.DataLayer

/** Woken by Play services whenever the phone writes `/ekispert/course`. */
class CourseListenerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        CourseStore.init(this)
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val item = event.dataItem
            if (item.uri.path != DataLayer.COURSE_PATH) continue
            val json = DataMapItem.fromDataItem(item).dataMap.getString(DataLayer.KEY_JSON) ?: continue
            CourseStore.update(this, json)
        }
    }
}

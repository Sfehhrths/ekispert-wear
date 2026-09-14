package dev.sfehhrths.ekispertwear.tap

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import dev.sfehhrths.ekispertwear.CourseRepository
import dev.sfehhrths.ekispertwear.Logs
import dev.sfehhrths.ekispertwear.PoolOrigin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

/**
 * Receives the explicit broadcasts sent by the patched Ekispert app (see CompanionBridge in
 * ekispert-patches) and hands them to [CourseRepository]. Work is done off the main thread
 * under [goAsync] so the ~10 s receiver budget is not an issue for the 185 KB XML.
 */
class TapReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TapContract.ACTION) return
        // Anti-spoofing: only the (patched) Ekispert app may feed us. The receiver is exported,
        // so verify the sender's uid (Android 14+). Unknown uid (-1, e.g. `am broadcast` from
        // the shell reports no package) is rejected too.
        val uid = sentFromUid
        val senderPkgs = if (uid >= 0) context.packageManager.getPackagesForUid(uid)?.toList() else null
        if (senderPkgs == null || TapContract.SENDER_PACKAGE !in senderPkgs) {
            Log.w(Logs.TAG, "dropping broadcast from uid=$uid pkgs=$senderPkgs")
            return
        }
        val kind = intent.getStringExtra(TapContract.EXTRA_KIND) ?: return
        val pending = goAsync()
        val app = context.applicationContext
        scope.launch {
            try {
                handle(app, kind, intent)
            } catch (t: Throwable) {
                Log.e(Logs.TAG, "failed to handle $kind", t)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handle(context: Context, kind: String, intent: Intent) {
        val repo = CourseRepository.get(context)
        val ts = intent.getLongExtra(TapContract.EXTRA_TIMESTAMP, System.currentTimeMillis())
        when (kind) {
            TapContract.KIND_HTTP_RESPONSE -> {
                val url = intent.getStringExtra(TapContract.EXTRA_URL) ?: ""
                val code = intent.getIntExtra(TapContract.EXTRA_HTTP_CODE, -1)
                val path = runCatching { Uri.parse(url).path }.getOrNull() ?: ""
                if (code != 200) {
                    Log.w(Logs.TAG, "$path returned $code; ignored")
                    return
                }
                when (path) {
                    TapContract.ROUTE_SEARCH_PATH ->
                        repo.onCoursesReceived(gunzip(intent) ?: return, PoolOrigin.SEARCH, ts)
                    TapContract.COURSE_EDIT_PATH ->
                        repo.onCoursesReceived(gunzip(intent) ?: return, PoolOrigin.EDIT, ts)
                    TapContract.SERVICE_INFO_PATH -> repo.onServiceInformation(gunzip(intent) ?: return, ts)
                    TapContract.REALTIME_TRIP_PATH -> repo.onRealtimeTrip(gunzip(intent) ?: return, ts)
                    else -> Log.d(Logs.TAG, "ignore http_response $path")
                }
            }

            TapContract.KIND_SELECTED_COURSE -> {
                val presenter = intent.getStringExtra(TapContract.EXTRA_PRESENTER) ?: ""
                val keys = intent.getStringArrayExtra(TapContract.EXTRA_COURSE_KEYS)?.toList().orEmpty()
                if (keys.isEmpty()) {
                    Log.w(Logs.TAG, "selected_course from $presenter carries no keys; patch/app mismatch?")
                    return
                }
                repo.onCourseSelected(keys, presenter, ts)
            }

            TapContract.KIND_TRANSFER_ALARM_COURSE -> {
                val body = gunzip(intent) ?: return
                repo.onTransferAlarmCourse(body, ts)
            }

            TapContract.KIND_MYCLIP_COURSE -> {
                val body = gunzip(intent) ?: return
                repo.onMyClipCourse(body, ts)
            }

            else -> Log.w(Logs.TAG, "unknown kind $kind")
        }
    }

    private fun gunzip(intent: Intent): String? {
        val gz = intent.getByteArrayExtra(TapContract.EXTRA_BODY_GZIP)
        if (gz == null) {
            Log.w(Logs.TAG, "no body_gzip extra")
            return null
        }
        return GZIPInputStream(ByteArrayInputStream(gz)).use { it.readBytes() }.toString(Charsets.UTF_8)
    }
}

package dev.sfehhrths.ekispertwear

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service that publishes an Ongoing Activity while the course has a countdown
 * ([CourseLogic.timerTarget] != null). On Wear OS 5+ an always-on app is only kept on screen
 * (instead of falling back to the watch face) while it owns an ongoing activity, so this is what
 * lets [MainActivity] stay visible. The chip on the watch face also brings the app back.
 *
 * Started by [MainActivity] when it sees an active countdown; stops itself once the course is
 * over (or the course goes away), and is stopped by the activity when the user closes the app.
 */
class CountdownService : Service() {

    companion object {
        private const val TAG = CourseStore.TAG
        private const val CHANNEL_ID = "countdown"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, CountdownService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CountdownService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var foregrounded = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        CourseStore.init(this)
        ensureChannel()
        scope.launch {
            // StateFlow replays the current course synchronously, so the first startForeground()
            // happens inside onCreate, well within the startForegroundService() deadline.
            CourseStore.payload.collectLatest { payload ->
                while (true) {
                    val now = System.currentTimeMillis()
                    val target = payload?.course?.let { CourseLogic.timerTarget(it, now) }
                    if (target == null) {
                        Log.i(TAG, "countdown over, stopping ongoing activity")
                        finish()
                        return@collectLatest
                    }
                    show(target, now)
                    // Re-evaluate once this target passes: next leg, final arrival, or done.
                    delay(target.targetMillis - now + 1_000)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        scope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun show(target: CourseLogic.TimerTarget, now: Long) {
        val touch = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val label = if (target.isArrival) "到着" else "出発"
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_countdown)
            .setContentTitle(target.stationName)
            .setContentText("${label}まであと ${CourseLogic.countdown(target.targetMillis - now)}")
            .setContentIntent(touch)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOngoing(true)
            .setSilent(true)
        // TimerPart counts down on its own; its zero point is on the elapsedRealtime clock.
        val status = Status.Builder()
            .addTemplate("#station# #label#まで #timer#")
            .addPart("station", Status.TextPart(target.stationName))
            .addPart("label", Status.TextPart(label))
            .addPart("timer", Status.TimerPart(SystemClock.elapsedRealtime() + (target.targetMillis - now)))
            .build()
        OngoingActivity.Builder(applicationContext, NOTIFICATION_ID, builder)
            .setStaticIcon(R.drawable.ic_countdown)
            .setTouchIntent(touch)
            .setStatus(status)
            .build()
            .apply(applicationContext)
        ServiceCompat.startForeground(this, NOTIFICATION_ID, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        foregrounded = true
        Log.i(TAG, "ongoing activity: ${target.stationName} $label in ${CourseLogic.countdown(target.targetMillis - now)}")
    }

    private fun finish() {
        if (!foregrounded) {
            // Honour the startForegroundService() contract even when there is nothing to show.
            val placeholder = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_countdown)
                .setContentTitle("駅すぱあと")
                .setOngoing(true)
                .setSilent(true)
                .build()
            ServiceCompat.startForeground(this, NOTIFICATION_ID, placeholder, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "カウントダウン", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "出発・到着までのカウントダウンをウォッチに表示し続けます"
                    setShowBadge(false)
                },
            )
        }
    }
}

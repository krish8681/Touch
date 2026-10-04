package com.niftyengine.app

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.niftyengine.app.ui.UiState
import com.niftyengine.app.ui.inActiveWindow
import com.niftyengine.engine.core.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the process (and therefore [com.niftyengine.app.ui.EngineController]'s loop)
 * alive while the app is minimised or the screen is off. Holds a partial wake lock so polling continues,
 * and shows an ongoing notification with the latest reading plus a Stop action.
 */
class EngineService : Service() {
    companion object {
        const val CHANNEL = "engine_status"
        private const val NOTIFICATION_ID = 1002
        private const val ACTION_STOP = "com.niftyengine.app.STOP_ENGINE"

        fun start(ctx: Context) {
            // Starting a foreground service from the background can be refused (Android 12+); the loop still
            // runs while the process lives, so failure here is not fatal.
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, EngineService::class.java)) }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, EngineService::class.java)) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val controller = (application as NiftyApp).controller
        ServiceCompat.startForeground(this, NOTIFICATION_ID, build(controller.ui.value),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NiftyEngine:loop").apply { setReferenceCounted(false); acquire() }
        scope.launch {
            controller.ui.map { statusLine(it) to it.output?.decision?.headline }.distinctUntilChanged().collect {
                runCatching { NotificationManagerCompat.from(this@EngineService).notify(NOTIFICATION_ID, build(controller.ui.value)) }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val controller = (application as NiftyApp).controller
        if (intent?.action == ACTION_STOP) {
            controller.stop() // stops this service too
            return START_NOT_STICKY
        }
        // Restarted by the system after the process was killed (null intent): resume the loop.
        controller.start()
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { (application as NiftyApp).controller.persist() }
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun statusLine(ui: UiState): String {
        val o = ui.output
        val window = if (inActiveWindow(System.currentTimeMillis())) "" else " · market closed, checking every 5 min"
        if (o == null) return (ui.error?.let { "⚠ $it" } ?: "Starting…") + window
        val d = o.direction
        return "%.0f · Bull %.0f%% / Bear %.0f%% · %s%s".format(o.spot, d.pBull * 100, d.pBear * 100,
            if (ui.lastUpdate > 0) "updated ${Session.hhmm(ui.lastUpdate)}" else "", window)
    }

    private fun build(ui: UiState): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, EngineService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val text = statusLine(ui)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(ui.output?.decision?.headline ?: "NIFTY engine running")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, "Stop engine", stop)
            .build()
    }
}

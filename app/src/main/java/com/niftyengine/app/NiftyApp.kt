package com.niftyengine.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.niftyengine.app.ui.EngineController

class NiftyApp : Application() {
    /** The engine loop lives here — not in an Activity — so minimising or closing the screen doesn't stop it. */
    val controller: EngineController by lazy { EngineController(this) }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(Notifier.CHANNEL, "Trade signals", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Alerts when the engine's trade filter passes"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(EngineService.CHANNEL, "Engine running", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Ongoing status while the engine runs in the background"
                setShowBadge(false)
            },
        )
    }
}

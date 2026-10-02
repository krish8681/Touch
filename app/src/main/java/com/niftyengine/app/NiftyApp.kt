package com.niftyengine.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class NiftyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(Notifier.CHANNEL, "Trade signals", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Alerts when the engine's trade filter passes"
            },
        )
    }
}

package com.niftyengine.app

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.niftyengine.engine.model.EngineOutput

class Notifier(private val ctx: Context) {
    companion object { const val CHANNEL = "trade_signals" }

    fun trade(o: EngineOutput) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val m = o.master
        val text = "%s · master %s %.0f%% · %s · conf %s · event risk %s".format(
            o.regime.regime.label, m.direction.label, m.probability * 100, m.alignmentLabel, m.confidence.name, m.eventRisk.label) +
            (o.decision.candidate?.let { "\n" + it.rationale } ?: "")
        val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(o.decision.headline)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(1001, n) }
    }
}

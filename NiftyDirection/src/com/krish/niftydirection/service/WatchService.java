package com.krish.niftydirection.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;

import com.krish.niftydirection.data.Brain;
import com.krish.niftydirection.data.Collector;
import com.krish.niftydirection.data.Prefs;
import com.krish.niftydirection.engine.Result;
import com.krish.niftydirection.ui.MainActivity;

import java.util.Calendar;

/**
 * Live watch: keeps the score updating in the background on market days (8:30–15:35 IST)
 * and alerts when the regime changes. Read-only — it never trades.
 */
public class WatchService extends Service {
    static final String CH_WATCH = "watch", CH_ALERT = "alerts";
    static final int ID_WATCH = 1, ID_ALERT = 2;

    private HandlerThread thread;
    private Handler h;
    private String lastRegime = "";

    public static void start(Context c) {
        Intent i = new Intent(c, WatchService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Exception ignored) { }
    }

    public static void stop(Context c) { c.stopService(new Intent(c, WatchService.class)); }

    @Override public void onCreate() {
        super.onCreate();
        channels(this);
        Notification n = watchNote("Starting…");
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID_WATCH, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(ID_WATCH, n);
        thread = new HandlerThread("watch");
        thread.start();
        h = new Handler(thread.getLooper());
        lastRegime = new Prefs(this).str("last_regime", "");
        h.post(loop);
    }

    @Override public int onStartCommand(Intent i, int flags, int id) { return START_STICKY; }

    @Override public void onDestroy() {
        if (h != null) h.removeCallbacksAndMessages(null);
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }

    private final Runnable loop = new Runnable() {
        @Override public void run() {
            Prefs p = new Prefs(WatchService.this);
            if (!p.bool("watch_on", false)) { stopSelf(); return; }
            long next;
            if (window()) {
                if (!p.hasValidSession()) {
                    update("Log in to Kite to keep watching", true);
                    next = 10 * 60_000L;
                } else {
                    PowerManager.WakeLock wl = null;
                    try {
                        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "niftydirection:refresh");
                        wl.acquire(90_000);
                        Brain.Output o = Brain.refresh(getApplicationContext(), null);
                        onResult(p, o.result);
                    } catch (Throwable t) {
                        update("Update failed: " + t.getMessage(), false);
                    } finally {
                        if (wl != null && wl.isHeld()) wl.release();
                    }
                    next = p.refreshMin() * 60_000L;
                }
            } else {
                update("Waiting for the market (8:30–15:35 on weekdays)", false);
                next = 10 * 60_000L;
            }
            h.postDelayed(this, next);
        }
    };

    private void onResult(Prefs p, Result r) {
        String line = r.label() + " · " + r.directionScore + "/100 · conf " + r.confidence + " · " + r.state;
        update(line, false);
        if (r.coverage < 0.4) return;   // too little data to alert on
        if (!r.regime.equals(lastRegime)) {
            boolean quietSwap = isQuiet(lastRegime) && isQuiet(r.regime);   // NO EDGE ↔ RANGE is not worth a ping
            if (!lastRegime.isEmpty() && !quietSwap && p.bool("notify_flip", true)) {
                alert("Nifty regime: " + lastRegime + " → " + r.label(), r.action + " (confidence " + r.confidence + "/100)");
            }
            lastRegime = r.regime;
            p.put("last_regime", lastRegime);
        }
    }

    static boolean isQuiet(String regime) { return Result.RANGE.equals(regime) || Result.NO_EDGE.equals(regime); }

    static boolean window() {
        Calendar c = Calendar.getInstance(Collector.IST);
        int d = c.get(Calendar.DAY_OF_WEEK), m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        return d != Calendar.SATURDAY && d != Calendar.SUNDAY && m >= 8 * 60 + 30 && m <= 15 * 60 + 35;
    }

    private void update(String text, boolean important) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(ID_WATCH, watchNote(text));
    }

    private PendingIntent openApp() {
        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification watchNote(String text) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH_WATCH) : new Notification.Builder(this);
        return b.setSmallIcon(com.krish.niftydirection.R.drawable.ic_stat)
                .setContentTitle("Nifty Direction · live watch")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openApp())
                .build();
    }

    private void alert(String title, String text) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH_ALERT) : new Notification.Builder(this);
        Notification n = b.setSmallIcon(com.krish.niftydirection.R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(openApp())
                .build();
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(ID_ALERT, n);
    }

    static void channels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CH_WATCH, "Live watch", NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(CH_ALERT, "Regime changes", NotificationManager.IMPORTANCE_HIGH));
    }
}

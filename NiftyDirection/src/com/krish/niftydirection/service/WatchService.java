package com.krish.niftydirection.service;

import android.app.AlarmManager;
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
import com.krish.niftydirection.data.IntelRunner;
import com.krish.niftydirection.data.Kite;
import com.krish.niftydirection.intel.IntelEngine;
import com.krish.niftydirection.data.Prefs;
import com.krish.niftydirection.engine.Result;
import com.krish.niftydirection.ui.MainActivity;

import java.util.Calendar;

/**
 * Live watch: keeps the score and the AI forecast updating in the background on market days (8:30–15:35 IST),
 * alerts when the regime changes or a validated horizon becomes actionable, and keeps the paper-trade record growing.
 * Read-only — it never trades.
 */
public class WatchService extends Service {
    static final String CH_WATCH = "watch", CH_ALERT = "alerts";
    static final int ID_WATCH = 1, ID_ALERT = 2;

    private HandlerThread thread;
    private Handler h;
    private String lastRegime = "";
    /** Held only while one pass runs; passes are booked with alarms, which wake the phone even in Doze. */
    private PowerManager.WakeLock windowLock;

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

    @Override public int onStartCommand(Intent i, int flags, int id) {
        if (i != null && ACTION_WAKE.equals(i.getAction()) && h != null) { h.removeCallbacks(loop); h.post(loop); }   // alarm: next pass or window opening
        return START_STICKY;
    }

    static final String ACTION_WAKE = "com.krish.niftydirection.WATCH_WAKE";

    @Override public void onDestroy() {
        if (h != null) h.removeCallbacksAndMessages(null);
        releaseWindowLock();
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }

    /**
     * One pass: refresh the evidence score and (if trained) the AI forecast, then book the next pass with an alarm.
     * The CPU is kept awake only while a pass runs (not the whole market window), so the phone can sleep in between.
     */
    private final Runnable loop = new Runnable() {
        @Override public void run() {
            Prefs p = new Prefs(WatchService.this);
            if (!p.bool("watch_on", false)) { releaseWindowLock(); stopSelf(); return; }
            if (window()) {
                holdWindowLock();
                try {
                    if (!p.hasValidSession()) update("Log in to Kite to keep watching", true);
                    else {
                        try {
                            Brain.Output o = Brain.refresh(getApplicationContext(), null);
                            onResult(p, o.result);
                            intel(p, o);
                        } catch (Throwable t) {
                            update("Update failed: " + t.getMessage(), false);
                        }
                    }
                } finally {
                    releaseWindowLock();
                }
                scheduleNext(p.hasValidSession() ? p.refreshMin() * 60_000L : 10 * 60_000L);
            } else {
                releaseWindowLock();
                scheduleWindowStart();
                update("Waiting for the market (8:30–15:35 on weekdays)", false);
            }
        }
    };

    private long lastIntel;

    /** AI forecast in the background (every 5 minutes at most), with an alert when a validated horizon becomes actionable. */
    private void intel(Prefs p, Brain.Output o) {
        java.io.File dir = getFilesDir();
        if (!IntelRunner.hasModels(dir) || System.currentTimeMillis() - lastIntel < 5 * 60_000L) return;
        lastIntel = System.currentTimeMillis();
        try {
            Calendar c = Calendar.getInstance(Collector.IST);
            String today = Collector.day(c.getTime());
            int minute = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
            IntelEngine.Forecast fc = IntelRunner.live(new Kite(p.apiKey(), p.token()), dir, today, minute, IntelRunner.expectedOpen(o.snap),
                    IntelRunner.context(o.snap, o.result, p.str("user_events", "")), p.simConfig(), null, (w, a, b) -> {});
            StringBuilder now = new StringBuilder();
            String before = p.str("act_set", "");
            for (IntelEngine.HPred h : fc.preds) {
                if (!h.has() || !h.tradeable) continue;
                now.append(h.hz.id).append(h.direction.charAt(0)).append(',');
                String key = h.hz.id + h.direction.charAt(0) + ",";
                if (!before.contains(key) && p.bool("notify_flip", true))
                    alert("Nifty " + h.hz.label + ": " + h.direction + String.format(java.util.Locale.US, " %.0f%%", h.sideProb() * 100),
                            "Strong enough to act on (validated " + h.validation + "). Signal only — the app never trades.");
            }
            p.put("act_set", now.toString());
            // option positions: one alert per position when an exit rule is hit
            String done = p.str("opt_exit_alerted", "");
            StringBuilder still = new StringBuilder();
            for (com.krish.niftydirection.intel.OptionStrategy.Position pos : IntelRunner.optPositions(dir)) {
                if (pos.closed) continue;
                com.krish.niftydirection.intel.OptionStrategy.Status st = IntelRunner.optStatus(o.snap, fc, pos, p.simConfig());
                if (st == null || !"EXIT".equals(st.signal)) continue;
                still.append(pos.id).append(',');
                if (done.contains(pos.id + ",")) continue;
                alert("EXIT " + pos.name + (pos.paper ? " (paper)" : ""),
                        st.reasons.get(0) + String.format(java.util.Locale.US, ". P&L now %s₹%,.0f after costs. Signal only — close it yourself in Kite.", st.pnl < 0 ? "−" : "+", Math.abs(st.pnl)));
            }
            p.put("opt_exit_alerted", still.toString());
        } catch (Throwable ignored) { }
    }

    /** Next pass: an exact alarm when the phone allows it, otherwise an inexact one (Android may stretch it a few minutes in deep sleep). */
    private void scheduleNext(long delayMs) {
        Intent i = new Intent(this, WatchService.class).setAction(ACTION_WAKE);
        PendingIntent pi = PendingIntent.getService(this, 2, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        long at = System.currentTimeMillis() + delayMs;
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (Exception e) {
            h.postDelayed(loop, delayMs);   // last resort
        }
    }

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

    /** Keeps the CPU awake for one pass only (the timeout is a safety net). */
    private void holdWindowLock() {
        if (windowLock != null && windowLock.isHeld()) return;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        windowLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "niftydirection:pass");
        windowLock.setReferenceCounted(false);
        windowLock.acquire(4 * 60_000L);
    }

    /** Handler delays stall in Doze, so an idle-allowed alarm makes sure the loop runs when the next window opens. */
    private void scheduleWindowStart() {
        Calendar c = Calendar.getInstance(Collector.IST);
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        int m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        c.set(Calendar.HOUR_OF_DAY, 8); c.set(Calendar.MINUTE, 30);
        if (m >= 8 * 60 + 30) c.add(Calendar.DAY_OF_MONTH, 1);
        while (c.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY || c.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) c.add(Calendar.DAY_OF_MONTH, 1);
        Intent i = new Intent(this, WatchService.class).setAction(ACTION_WAKE);
        PendingIntent pi = PendingIntent.getService(this, 1, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, c.getTimeInMillis(), pi); } catch (Exception ignored) { }
    }

    private void releaseWindowLock() {
        if (windowLock != null && windowLock.isHeld()) windowLock.release();
        windowLock = null;
    }

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

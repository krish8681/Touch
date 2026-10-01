package com.krish.niftydirection.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.krish.niftydirection.BuildInfo;
import com.krish.niftydirection.data.Brain;
import com.krish.niftydirection.data.Collector;
import com.krish.niftydirection.data.ForecastRunner;
import com.krish.niftydirection.data.Kite;
import com.krish.niftydirection.data.HistoryLoader;
import com.krish.niftydirection.data.Prefs;
import com.krish.niftydirection.data.Store;
import com.krish.niftydirection.service.WatchService;

import java.util.Calendar;

public class MainActivity extends Activity implements Brain.Listener {
    static final String[] TABS = {"Forecast", "Today", "Evidence", "Options", "Market", "News", "Record"};
    static final String[] ICONS = {"↗", "◉", "≡", "⊞", "◍", "✎", "✓"};

    private Prefs prefs;
    private Store store;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private int tab = 0;
    private ScrollView scroll;
    private LinearLayout page;
    private TextView status, loginPill, refreshBtn;
    private final TextView[] tabViews = new TextView[TABS.length];
    private volatile boolean busy;
    private long lastRun;
    private volatile String fcWorking;
    private volatile boolean fcTriedTrain;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            long every = prefs.refreshMin() * 60_000L;
            if (!inMarketWindow()) every = Math.max(every, 15 * 60_000L);
            if (System.currentTimeMillis() - lastRun >= every) refresh();
            updateStatus(null);
            ui.postDelayed(this, 20_000);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        store = new Store(getFilesDir());
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.PANEL);

        LinearLayout shell = Ui.col(this);
        shell.setBackgroundColor(Ui.BG);

        // top bar
        LinearLayout bar = Ui.row(this);
        bar.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 6));
        LinearLayout title = Ui.col(this);
        title.addView(Ui.text(this, "Nifty Direction", 19, Ui.TEXT, true));
        title.addView(Ui.text(this, BuildInfo.VERSION + " · 1H · 3H · 6H · next day", 10, Ui.DIM, false));
        bar.addView(title, Ui.weight(1));
        loginPill = Ui.pill(this, "Log in", Ui.AMBER);
        loginPill.setOnClickListener(v -> onLoginPill());
        bar.addView(loginPill, Ui.wrap());
        refreshBtn = Ui.text(this, "⟳", 24, Ui.CYAN, true);
        refreshBtn.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 6), 0);
        refreshBtn.setOnClickListener(v -> refresh());
        bar.addView(refreshBtn, Ui.wrap());
        TextView gear = Ui.text(this, "⚙", 22, Ui.DIM, false);
        gear.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 4), 0);
        gear.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        bar.addView(gear, Ui.wrap());
        shell.addView(bar, Ui.matchW());

        status = Ui.text(this, "", 11, Ui.DIM, false);
        status.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 6));
        shell.addView(status, Ui.matchW());

        scroll = new ScrollView(this);
        page = Ui.col(this);
        page.setPadding(Ui.dp(this, 12), Ui.dp(this, 4), Ui.dp(this, 12), Ui.dp(this, 12));
        scroll.addView(page);
        FrameLayout body = new FrameLayout(this);
        body.addView(scroll);
        shell.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));

        // bottom tabs
        LinearLayout nav = Ui.row(this);
        nav.setBackgroundColor(Ui.PANEL);
        nav.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 8));
        for (int i = 0; i < TABS.length; i++) {
            final int k = i;
            TextView t = Ui.text(this, ICONS[i] + "\n" + TABS[i], 10, Ui.DIM, true);
            t.setGravity(Gravity.CENTER);
            t.setOnClickListener(v -> { tab = k; scroll.scrollTo(0, 0); render(); });
            tabViews[i] = t;
            nav.addView(t, Ui.weight(1));
        }
        shell.addView(nav, Ui.matchW());
        setContentView(shell);

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        if (prefs.bool("watch_on", false)) WatchService.start(this);
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        Brain.listen(this);
        updateLogin();
        if (Brain.last == null || System.currentTimeMillis() - lastRun > 60_000) refresh(); else render();
        ui.removeCallbacks(ticker);
        ui.postDelayed(ticker, 20_000);
    }

    @Override protected void onPause() {
        super.onPause();
        Brain.unlisten(this);
        ui.removeCallbacks(ticker);
    }

    @Override public void onBackPressed() {
        if (tab != 0) { tab = 0; render(); } else super.onBackPressed();
    }

    @Override public void onOutput(Brain.Output o) {
        lastRun = System.currentTimeMillis();
        ui.post(() -> { updateLogin(); render(); autoForecast(); });
    }

    // ================================================================== forecast (1H / 3H / 6H / next day)

    private final ForecastPage.Actions fcActions = new ForecastPage.Actions() {
        @Override public void update() { fcUpdate(); }
        @Override public void train() { fcTrain(); }
    };

    /** Keeps the forecast fresh: every 5 minutes in market hours, hourly otherwise; weekly retrain outside market hours. */
    private void autoForecast() {
        if (fcWorking != null || !prefs.hasValidSession()) return;
        java.io.File dir = getFilesDir();
        if (!ForecastRunner.hasModelFiles(dir)) return;   // first training is the user's choice (big download)
        boolean market = inSession();
        if (!market && !fcTriedTrain && ForecastRunner.needsTraining(dir)) { fcTriedTrain = true; fcTrain(); return; }
        if (ForecastRunner.models(dir).isEmpty()) return;
        ForecastRunner.Live l = ForecastRunner.last;
        long age = l == null ? Long.MAX_VALUE : System.currentTimeMillis() - l.at;
        boolean wantPreOpen = l != null && !l.preOpen && !Double.isNaN(expectedOpen());   // GIFT just became available
        if (wantPreOpen || age > (market ? 5 : preOpenWindow() ? 15 : 60) * 60_000L) fcUpdate();
    }

    private void fcUpdate() { fcRun(false); }
    private void fcTrain() { fcRun(true); }

    private void fcRun(boolean train) {
        if (fcWorking != null) return;
        if (!prefs.hasValidSession()) { onLoginPill(); return; }
        fcWorking = train ? "Training…" : "Updating forecast…";
        render();
        final Kite kite = new Kite(prefs.apiKey(), prefs.token());
        final java.io.File dir = getFilesDir();
        new Thread(() -> {
            String err = null;
            try {
                Calendar c = Calendar.getInstance(Collector.IST);
                String today = Collector.day(c.getTime());
                int minute = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
                HistoryLoader.Progress pr = (w, d, t) -> { fcWorking = (train ? "Training: " : "Forecast: ") + w + (t > 1 ? " (" + d + "/" + t + ")" : ""); ui.post(this::renderIfForecast); };
                if (train) ForecastRunner.train(kite, dir, today, null, pr);
                ForecastRunner.live(kite, dir, today, minute, expectedOpen(), null, pr);
            } catch (Kite.TokenExpired e) {
                prefs.clearSession();
                err = "Kite login expired — log in again.";
            } catch (Throwable t) {
                err = (train ? "Training failed: " : "Forecast failed: ") + t.getMessage();
            }
            final String e2 = err;
            fcWorking = null;
            ui.post(() -> { updateLogin(); if (e2 != null) updateStatus(e2); renderIfForecast(); });
        }, "forecast").start();
    }

    /** Before 9:15 on a weekday: the open GIFT Nifty points to (Nifty × GIFT ÷ near futures). NaN otherwise. */
    static double expectedOpen() {
        Brain.Output o = Brain.last;
        if (o == null || o.snap == null || !preOpenWindow()) return Double.NaN;
        com.krish.niftydirection.model.Snapshot s = o.snap;
        if (s.live || Double.isNaN(s.giftNifty) || s.nifty == null || !s.nifty.ok() || s.fut == null || !s.fut.ok()) return Double.NaN;
        return s.nifty.last * s.giftNifty / s.fut.last;
    }

    static boolean preOpenWindow() {
        Calendar c = Calendar.getInstance(Collector.IST);
        int d = c.get(Calendar.DAY_OF_WEEK), m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        return d != Calendar.SATURDAY && d != Calendar.SUNDAY && m >= 6 * 60 && m < 9 * 60 + 15;
    }

    private void renderIfForecast() { if (tab == 0) render(); }

    static boolean inSession() {
        Calendar c = Calendar.getInstance(Collector.IST);
        int d = c.get(Calendar.DAY_OF_WEEK), m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        return d != Calendar.SATURDAY && d != Calendar.SUNDAY && m >= 9 * 60 + 10 && m <= 15 * 60 + 35;
    }

    private void onLoginPill() {
        if (prefs.apiKey().isEmpty() || prefs.apiSecret().isEmpty()) { startActivity(new Intent(this, SettingsActivity.class)); return; }
        if (!prefs.hasValidSession()) startActivity(new Intent(this, LoginActivity.class));
    }

    private void updateLogin() {
        if (prefs.apiKey().isEmpty()) { loginPill.setText("Set up Kite"); restyle(Ui.AMBER); }
        else if (prefs.hasValidSession()) { loginPill.setText("● Live"); restyle(Ui.GREEN); }
        else { loginPill.setText("Log in"); restyle(Ui.AMBER); }
    }

    private void restyle(int color) {
        loginPill.setTextColor(color);
        loginPill.setBackground(Ui.round((color & 0x00FFFFFF) | 0x26000000, Ui.dp(this, 20), color, Ui.dp(this, 1)));
    }

    private void refresh() {
        if (busy) return;
        busy = true;
        lastRun = System.currentTimeMillis();
        refreshBtn.setTextColor(Ui.GREY);
        updateStatus("Updating…");
        new Thread(() -> {
            try {
                Brain.refresh(getApplicationContext(), what -> ui.post(() -> updateStatus(what)));
            } catch (Throwable t) {
                ui.post(() -> updateStatus("Update failed: " + t.getMessage()));
            } finally {
                busy = false;
                ui.post(() -> { refreshBtn.setTextColor(Ui.CYAN); updateStatus(null); });
            }
        }, "refresh").start();
    }

    private void updateStatus(String working) {
        if (working != null) { status.setText(working); status.setTextColor(Ui.CYAN); return; }
        if (busy) return;
        Brain.Output o = Brain.last;
        if (o == null) { status.setText("Tap ⟳ to load."); status.setTextColor(Ui.DIM); return; }
        long every = prefs.refreshMin() * 60_000L;
        if (!inMarketWindow()) every = Math.max(every, 15 * 60_000L);
        long left = Math.max(0, every - (System.currentTimeMillis() - lastRun));
        String live = o.snap.live ? "Market open" : "Market closed";
        status.setText("Updated " + Pages.time(o.snap.time) + " · " + live + " · next update in " + (left / 60000 + 1) + " min");
        status.setTextColor(Ui.DIM);
    }

    static boolean inMarketWindow() {
        Calendar c = Calendar.getInstance(Collector.IST);
        int d = c.get(Calendar.DAY_OF_WEEK), m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        return d != Calendar.SATURDAY && d != Calendar.SUNDAY && m >= 8 * 60 && m <= 15 * 60 + 35;
    }

    private void render() {
        for (int i = 0; i < tabViews.length; i++) tabViews[i].setTextColor(i == tab ? Ui.CYAN : Ui.DIM);
        final int keepY = scroll.getScrollY();
        scroll.post(() -> scroll.scrollTo(0, keepY));
        page.removeAllViews();
        Brain.Output o = Brain.last;
        if (tab == 0) {
            try { page.addView(ForecastPage.build(this, getFilesDir(), fcWorking, prefs.hasValidSession(), fcActions)); }
            catch (Throwable t) { page.addView(Pages.empty(this, "Could not draw this page: " + t)); }
            return;
        }
        if (tab == 6) { page.addView(Pages.record(this, store, prefs.bool("autotune", true))); return; }
        if (o == null) {
            page.addView(welcome());
            return;
        }
        View v;
        try {
            switch (tab) {
                case 2: v = Pages.evidence(this, o); break;
                case 3: v = Pages.options(this, o); break;
                case 4: v = Pages.market(this, o); break;
                case 5: v = Pages.news(this, o); break;
                default: v = Pages.today(this, o, store);
            }
        } catch (Throwable t) {
            v = Pages.empty(this, "Could not draw this page: " + t);
        }
        page.addView(v);
    }

    private View welcome() {
        LinearLayout col = Ui.col(this);
        LinearLayout c = Ui.card(this);
        c.addView(Ui.text(this, "Welcome", 18, Ui.TEXT, true));
        c.addView(Ui.text(this, "This app reads Nifty the way desks do: global cues, GIFT Nifty, FII positioning, futures and option OI, VIX, "
                + "sectors, breadth, Bank Nifty, VWAP and price action. It turns them into one Direction Score (0–100), "
                + "a regime (bullish / bearish / range), a confidence, support and resistance, and what to do next.", 13, Ui.DIM, false), Ui.top(this, 6));
        if (prefs.apiKey().isEmpty()) {
            c.addView(Ui.text(this, "Step 1: open Settings (⚙) and paste your Kite Connect API key and secret — the same app you use for Nifty50 Signals works. "
                    + "Its Redirect URL can be https://127.0.0.1 or https://127.0.0.1/kite", 13, Ui.AMBER, false), Ui.top(this, 10));
        } else if (!prefs.hasValidSession()) {
            c.addView(Ui.text(this, "Tap Log in at the top. Kite logins end every morning at about 6 AM.", 13, Ui.AMBER, false), Ui.top(this, 10));
        } else {
            c.addView(Ui.text(this, "Loading…", 13, Ui.CYAN, false), Ui.top(this, 10));
        }
        col.addView(c, Ui.cardLp(this));
        return col;
    }
}

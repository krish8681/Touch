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
import android.widget.Toast;

import com.krish.niftydirection.BuildInfo;
import com.krish.niftydirection.data.Brain;
import com.krish.niftydirection.data.Collector;
import com.krish.niftydirection.data.ForecastRunner;
import com.krish.niftydirection.data.Kite;
import com.krish.niftydirection.data.HistoryLoader;
import com.krish.niftydirection.data.IntelRunner;
import com.krish.niftydirection.intel.IntelEngine;
import com.krish.niftydirection.intel.Validator;
import com.krish.niftydirection.data.Prefs;
import com.krish.niftydirection.data.Store;
import com.krish.niftydirection.service.WatchService;

import java.util.Calendar;

public class MainActivity extends Activity implements Brain.Listener {
    static final String[] TABS = {"AI", "Today", "Evidence", "Options", "Market", "News", "Record"};
    static final String[] ICONS = {"✦", "◉", "≡", "⊞", "◍", "✎", "✓"};

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
    private volatile boolean fcTriedTrain, fcTriedValidate;

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
        bar.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 6));
        TextView logo = Ui.text(this, "N", 16, 0xFFFFFFFF, true);
        logo.setGravity(Gravity.CENTER);
        logo.setBackground(Ui.gradient(Ui.dp(this, 12), 0, Ui.CYAN, Ui.ACCENT2));
        bar.addView(logo, new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36)));
        LinearLayout title = Ui.col(this);
        title.setPadding(Ui.dp(this, 10), 0, 0, 0);
        title.addView(Ui.text(this, "Nifty Direction", 17, Ui.TEXT, true));
        title.addView(Ui.text(this, BuildInfo.VERSION + " · 15 min → 1 week", 10.5f, Ui.DIM, false));
        bar.addView(title, Ui.weight(1));
        loginPill = Ui.pill(this, "Log in", Ui.AMBER);
        loginPill.setOnClickListener(v -> onLoginPill());
        bar.addView(loginPill, Ui.wrap());
        refreshBtn = Ui.text(this, "⟳", 20, Ui.CYAN, true);
        refreshBtn.setGravity(Gravity.CENTER);
        refreshBtn.setBackground(Ui.round(Ui.alpha(Ui.CYAN, 0x1A), Ui.dp(this, 18), 0, 0));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36));
        rlp.leftMargin = Ui.dp(this, 8);
        refreshBtn.setLayoutParams(rlp);
        refreshBtn.setOnClickListener(v -> refresh());
        bar.addView(refreshBtn);
        TextView gear = Ui.text(this, "⚙", 18, Ui.DIM, false);
        gear.setGravity(Gravity.CENTER);
        gear.setBackground(Ui.round(0x14FFFFFF, Ui.dp(this, 18), 0, 0));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36));
        glp.leftMargin = Ui.dp(this, 8);
        gear.setLayoutParams(glp);
        gear.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        bar.addView(gear);
        shell.addView(bar, Ui.matchW());

        status = Ui.text(this, "", 11, Ui.DIM, false);
        status.setPadding(Ui.dp(this, 16), Ui.dp(this, 2), Ui.dp(this, 16), Ui.dp(this, 8));
        shell.addView(status, Ui.matchW());

        scroll = new ScrollView(this);
        page = Ui.col(this);
        page.setPadding(Ui.dp(this, 14), Ui.dp(this, 4), Ui.dp(this, 14), Ui.dp(this, 16));
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(page);
        FrameLayout body = new FrameLayout(this);
        body.addView(scroll);
        shell.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));

        // bottom tabs
        LinearLayout nav = Ui.row(this);
        android.graphics.drawable.GradientDrawable navBg = new android.graphics.drawable.GradientDrawable();
        navBg.setColor(Ui.PANEL);
        navBg.setCornerRadii(new float[]{Ui.dp(this, 22), Ui.dp(this, 22), Ui.dp(this, 22), Ui.dp(this, 22), 0, 0, 0, 0});
        navBg.setStroke(1, 0x14FFFFFF);
        nav.setBackground(navBg);
        nav.setElevation(Ui.dp(this, 8));
        nav.setPadding(Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 10));
        for (int i = 0; i < TABS.length; i++) {
            final int k = i;
            TextView t = Ui.text(this, ICONS[i] + "\n" + TABS[i], 10, Ui.DIM, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
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

    private final IntelPage.Actions fcActions = new IntelPage.Actions() {
        @Override public void update() { fcUpdate(); }
        @Override public void train() { fcTrain(); }
        @Override public void validate() { fcRunMode(2); }
        @Override public void export() { startExport(); }
        @Override public void redraw() { scroll.scrollTo(0, 0); render(); }
    };

    /** Keeps the forecast fresh: every 5 minutes in market hours, hourly otherwise; weekly retrain outside market hours. */
    private void autoForecast() {
        if (fcWorking != null || !prefs.hasValidSession()) return;
        java.io.File dir = getFilesDir();
        boolean has = IntelRunner.hasModels(dir);
        boolean market = inSession();
        // automatic: the first training (and validation) starts as soon as you are logged in; weekly retrains and
        // model-version upgrades run outside market hours; every retrain is followed by the pre-live replay
        if (!fcTriedTrain && !has) { fcTriedTrain = true; fcTrain(); return; }
        if (!market && !fcTriedTrain && IntelRunner.needsTraining(dir)) { fcTriedTrain = true; fcTrain(); return; }
        if (!has) return;
        if (!fcTriedValidate && IntelRunner.needsValidation(dir)) { fcTriedValidate = true; fcRunMode(2); return; }
        IntelEngine.Forecast l = IntelRunner.last;
        long age = l == null ? Long.MAX_VALUE : System.currentTimeMillis() - l.at;
        boolean wantPreOpen = l != null && !l.preOpen && !Double.isNaN(expectedOpen());   // GIFT just became available
        if (wantPreOpen || age > (market ? 5 : preOpenWindow() ? 15 : 60) * 60_000L) fcUpdate();
    }

    static final int REQ_EXPORT = 7;

    /** Asks where to save the export ZIP (Downloads, Drive …) — no storage permission needed. */
    private void startExport() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/zip");
        String ts = new java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US).format(new java.util.Date());
        i.putExtra(Intent.EXTRA_TITLE, "NiftyDirection_validation_" + ts + ".zip");
        try { startActivityForResult(i, REQ_EXPORT); }
        catch (Exception e) { Toast.makeText(this, "No file picker available: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_EXPORT || res != RESULT_OK || data == null || data.getData() == null) return;
        android.net.Uri uri = data.getData();
        updateStatus("Exporting…");
        new Thread(() -> {
            String msg;
            try (java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
                int n = IntelRunner.export(getFilesDir(), out, prefs.simConfig());
                msg = "Exported " + n + " files (ZIP).";
            } catch (Throwable t) {
                msg = "Export failed: " + t.getMessage();
            }
            final String m2 = msg;
            ui.post(() -> { Toast.makeText(this, m2, Toast.LENGTH_LONG).show(); updateStatus(null); });
        }, "export").start();
    }

    private void fcUpdate() { fcRunMode(0); }
    private void fcTrain() { fcRunMode(1); }

    Validator.Config simConfig() { return prefs.simConfig(); }

    /** mode 0 = update forecast, 1 = train → validate → update, 2 = validate → update. */
    private void fcRunMode(int mode) {
        boolean train = mode == 1;
        if (fcWorking != null) return;
        if (!prefs.hasValidSession()) { onLoginPill(); return; }
        fcWorking = train ? "Training…" : mode == 2 ? "Validating…" : "Updating forecast…";
        render();
        final Kite kite = new Kite(prefs.apiKey(), prefs.token());
        final java.io.File dir = getFilesDir();
        new Thread(() -> {
            String err = null;
            try {
                Calendar c = Calendar.getInstance(Collector.IST);
                String today = Collector.day(c.getTime());
                int minute = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
                final String[] stage = {train ? "Training: " : mode == 2 ? "Validating: " : "Forecast: "};
                HistoryLoader.Progress pr = (w, d, t) -> { fcWorking = stage[0] + w + (t > 1 ? " (" + d + "/" + t + ")" : ""); ui.post(this::renderIfForecast); };
                Validator.Config cfg = simConfig();
                if (train) IntelRunner.train(kite, dir, today, null, pr);
                if (train || mode == 2 || IntelRunner.needsValidation(dir)) { stage[0] = "Validating (replaying 12 months): "; IntelRunner.validate(kite, dir, today, simConfig(), null, pr); }
                stage[0] = "Forecast: ";
                IntelRunner.live(kite, dir, today, minute, expectedOpen(), liveContext(), cfg, null, pr);
            } catch (Kite.TokenExpired e) {
                prefs.clearSession();
                err = "Kite login expired — log in again.";
            } catch (Throwable t) {
                err = (train ? "Training failed: " : mode == 2 ? "Validation failed: " : "Forecast failed: ") + t.getMessage();
            }
            final String e2 = err;
            fcWorking = null;
            ui.post(() -> { updateLogin(); if (e2 != null) updateStatus(e2); renderIfForecast(); });
        }, "forecast").start();
    }

    private IntelEngine.LiveContext liveContext() {
        Brain.Output o = Brain.last;
        return IntelRunner.context(o == null ? null : o.snap, o == null ? null : o.result, prefs.str("user_events", ""));
    }

    /** Before 9:15 on a weekday: the open GIFT Nifty points to. NaN otherwise. */
    static double expectedOpen() {
        Brain.Output o = Brain.last;
        return IntelRunner.expectedOpen(o == null ? null : o.snap);
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
        for (int i = 0; i < tabViews.length; i++) {
            tabViews[i].setTextColor(i == tab ? Ui.TEXT : Ui.DIM);
            tabViews[i].setBackground(i == tab ? Ui.gradient(Ui.dp(this, 14), 0, Ui.alpha(Ui.CYAN, 0x38), Ui.alpha(Ui.ACCENT2, 0x38)) : null);
        }
        final int keepY = scroll.getScrollY();
        scroll.post(() -> scroll.scrollTo(0, keepY));
        page.removeAllViews();
        Brain.Output o = Brain.last;
        if (tab == 0) {
            try { page.addView(IntelPage.build(this, getFilesDir(), fcWorking, prefs.hasValidSession(), fcActions)); }
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

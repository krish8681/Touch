package com.krish.niftydirection.ui;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.krish.niftydirection.data.Collector;
import com.krish.niftydirection.data.Prefs;
import com.krish.niftydirection.service.WatchService;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Settings. Every row saves itself when you press Save or Back. */
public class SettingsActivity extends Activity {
    private static final int NUM = InputType.TYPE_CLASS_NUMBER, DEC = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL,
            SIGNED = DEC | InputType.TYPE_NUMBER_FLAG_SIGNED,
            TEXT = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, SECRET = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;

    private Prefs prefs;
    private LinearLayout root;
    private final List<Runnable> savers = new ArrayList<>();
    private String giftBefore;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        giftBefore = prefs.str("gift", "");
        getWindow().setStatusBarColor(Ui.BG);
        LinearLayout shell = Ui.col(this);
        shell.setBackgroundColor(Ui.BG);

        LinearLayout bar = Ui.row(this);
        bar.setPadding(Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8));
        TextView back = Ui.text(this, "‹", 30, Ui.TEXT, false);
        back.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 14), Ui.dp(this, 4));
        back.setOnClickListener(v -> save());
        bar.addView(back, Ui.wrap());
        bar.addView(Ui.text(this, "Settings", 20, Ui.TEXT, true), Ui.weight(1));
        shell.addView(bar, Ui.matchW());

        ScrollView sv = new ScrollView(this);
        root = Ui.col(this);
        root.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), Ui.dp(this, 20));
        sv.addView(root);
        shell.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout bottom = Ui.col(this);
        bottom.setBackgroundColor(Ui.PANEL);
        bottom.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        Button save = Ui.primary(this, "Save");
        save.setOnClickListener(v -> save());
        bottom.addView(save, Ui.matchW());
        shell.addView(bottom, Ui.matchW());

        build();
        setContentView(shell);
    }

    @Override public void onBackPressed() { save(); }

    private void build() {
        section("🔑  Zerodha Kite Connect", "Use the same Kite Connect app as your other Nifty apps.\n"
                + "Its Redirect URL can be https://127.0.0.1 or https://127.0.0.1/kite (both work)\nLog in each morning from the top bar (Kite logins end at about 6 AM).");
        text("api_key", "API key", "", TEXT);
        text("api_secret", "API secret (kept only on this phone)", "", SECRET);
        if (prefs.hasValidSession()) {
            Button out = Ui.button(this, "Log out of Kite" + (prefs.userName().isEmpty() ? "" : " (" + prefs.userName() + ")"), Ui.RED);
            out.setOnClickListener(v -> { prefs.clearSession(); out.setEnabled(false); out.setText("Logged out"); });
            add(out);
        }

        section("🌏  Before the open", "GIFT Nifty is read from Kite automatically (NSEIX:GIFT NIFTY). "
                + "Type a value here only if Kite can't give it. After 9:15 the real opening gap is used instead.");
        text("gift", "GIFT Nifty backup value (leave empty normally)", "", DEC);
        text("fii_manual", "FII cash net ₹ crore, e.g. -1250 (only used if NSE can't be reached)", "", SIGNED);
        check("global_on", "Load global markets (US, US futures, Asia, crude, rupee) from Yahoo Finance", true);
        check("nse_on", "Load FII / DII and FII index-futures positions from NSE", true);

        section("📰  News reader", "Headlines come from Google News, ET Markets, Moneycontrol, Mint, official feeds (RBI, SEBI, Fed, ECB, BoE, BoJ, PIB, US BEA), NSE filings of Nifty 50 companies and searches for the 15 biggest Nifty companies. A free Gemini API key (aistudio.google.com) lets Gemini rate "
                + "each headline's impact; without it a simple word list is used. Gemini never says buy or sell — the app decides how much news counts (±15 at most).");
        check("news_on", "Load market news", true);
        text("gemini_key", "Gemini API key (kept only on this phone)", "", SECRET);
        text("gemini_model", "Gemini model (auto = pick the newest Flash once, then keep using it)", "auto", TEXT);
        java.io.File pin = new java.io.File(getFilesDir(), "gemini_pin.txt");
        String pinned = "";
        try { if (pin.exists()) pinned = new String(java.nio.file.Files.readAllBytes(pin.toPath()), "UTF-8").trim(); } catch (Exception ignored) {}
        if (!pinned.isEmpty()) {
            root.addView(Ui.text(this, "Auto is pinned to: " + pinned + ". Pinning keeps news ratings comparable from day to day.", 12, Ui.DIM, false), Ui.top(this, 4));
            Button re = Ui.button(this, "Re-pick the newest model", Ui.CYAN);
            re.setOnClickListener(v -> { pin.delete(); re.setEnabled(false); re.setText("Will re-pick on the next update"); });
            add(re);
        }
        section("📅  My events", "One per line: yyyy-mm-dd [HH:MM] name. They raise event risk on that day (with a time: 30 minutes before is PRE-EVENT). "
                + "RBI, Fed, CPI, Budget and expiries are built in.\n"
                + "Surprise engine: add | exp=… and, once released, | act=… — e.g. 2026-10-12 16:00 India CPI | exp=4.5 | act=4.3 | good=down "
                + "(good=down: a lower number is good for shares). Actual − expected becomes a primary-source event.");
        multi("user_events", "e.g. 2026-10-14 Big bank results");

        section("⏱  Updates", null);
        text("refresh_min", "Update every N minutes while the market is open (1–60)", "5", NUM);
        check("watch_on", "Live watch: keep updating in the background from 8:30 to 15:35 on weekdays (shows a small notification)", false);
        check("notify_flip", "Alert me when the regime changes (bullish / bearish / range)", true);

        section("📊  Option chain", null);
        text("strikes", "Strikes each side of the money to load (6–20; ±5 give direction, the rest show walls building)", "15", NUM);
        text("oi_strikes", "Strikes each side that get yesterday's OI (2–10; more = slower first update)", "6", NUM);

        section("🧠  Learning", "Weights can move a little toward the evidence that has been right. Protected: refitted weekly from past days only, "
                + "last 20 days held back as a test, 100 readings needed per factor, and new weights are used only if they pass the test.");
        check("autotune", "Let the record adjust weights", true);

        section("🎯  Trade gate", "The forecast and the decision to act are separate. A horizon shows NO TRADE unless its probability reaches this level, "
                + "it has a proven edge, confidence is at least medium, data quality is fine and no event is minutes away. Signals only — the app never trades.");
        text("trade_threshold", "Probability needed before a forecast counts as strong enough (55–90, %)", "62", NUM);

        section("🧪  Pre-live validation (simulated trading)", "After every training the app replays the last ~12 months through the same engine and trades every "
                + "\"strong enough\" signal in Nifty futures: entry at the next 5-minute bar, exit at the horizon's end or the stop, with slippage and "
                + "approximate Zerodha charges (brokerage, STT, exchange, SEBI, stamp, GST). Only horizons that PASS may show \"strong enough to act on\" live.");
        text("sim_lot", "Nifty futures lot size (check the current lot with your broker)", "65", NUM);
        text("sim_slip", "Slippage per side, Nifty points", "1", DEC);
        text("sim_stop", "Stop = this × the 68% expected range (0 = no stop)", "1", DEC);

        section("ℹ️  How to read the app", "Direction Score 0–100: 50 = no side. A side starts when the signed score passes ±25 and ends when it falls back through ±15.\n"
                + "Labels: BULLISH, BEARISH, RANGE (real range signs), NO EDGE (weak or mixed evidence), CONFLICT (positioning and price disagree).\n"
                + "Confidence = strength × agreement × data coverage, lowered by high VIX and event risk.\n"
                + "Signals only — this app never places orders.");
    }

    private void section(String title, String help) {
        TextView t = Ui.text(this, title, 16, Ui.TEXT, true);
        t.setPadding(0, Ui.dp(this, 18), 0, Ui.dp(this, 4));
        root.addView(t);
        if (help != null) root.addView(Ui.text(this, help, 12, Ui.DIM, false));
    }

    private void add(View v) { root.addView(v, Ui.top(this, 8)); }

    private void text(String key, String label, String def, int type) {
        boolean secret = type == SECRET;
        LinearLayout lr = Ui.row(this);
        lr.addView(Ui.text(this, label, 12, Ui.DIM, false), Ui.weight(1));
        TextView eye = secret ? Ui.text(this, "Show", 12, Ui.CYAN, true) : null;
        if (eye != null) lr.addView(eye, Ui.wrap());
        root.addView(lr, Ui.top(this, 10));
        EditText e = new EditText(this);
        e.setInputType(type);
        e.setText(prefs.str(key, def));
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.GREY);
        e.setSingleLine(true);
        if (secret) {
            // setSingleLine() replaces the password mask, so it must be set again afterwards
            e.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
            eye.setOnClickListener(v -> {
                boolean hidden = e.getTransformationMethod() instanceof android.text.method.PasswordTransformationMethod;
                e.setTransformationMethod(hidden ? null : android.text.method.PasswordTransformationMethod.getInstance());
                e.setSelection(e.getText().length());
                eye.setText(hidden ? "Hide" : "Show");
            });
        }
        e.setBackground(Ui.round(Ui.CARD, Ui.dp(this, 8), Ui.LINE, 1));
        e.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        root.addView(e, Ui.top(this, 4));
        savers.add(() -> prefs.put(key, e.getText().toString().trim()));
    }

    private void multi(String key, String hint) {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setText(prefs.str(key, ""));
        e.setHint(hint);
        e.setMinLines(3);
        e.setGravity(android.view.Gravity.TOP);
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.GREY);
        e.setBackground(Ui.round(Ui.CARD, Ui.dp(this, 8), Ui.LINE, 1));
        e.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        root.addView(e, Ui.top(this, 6));
        savers.add(() -> prefs.put(key, e.getText().toString().trim()));
    }

    private void check(String key, String label, boolean def) {
        CheckBox c = new CheckBox(this);
        c.setText(label);
        c.setTextColor(Ui.TEXT);
        c.setChecked(prefs.bool(key, def));
        root.addView(c, Ui.top(this, 6));
        savers.add(() -> prefs.put(key, c.isChecked()));
    }

    private void save() {
        for (Runnable r : savers) r.run();
        String gift = prefs.str("gift", "");
        if (!gift.equals(giftBefore)) prefs.put("gift_date", gift.isEmpty() ? "" : Collector.day(new Date()));
        if (prefs.bool("watch_on", false)) WatchService.start(this); else WatchService.stop(this);
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
        finish();
    }
}

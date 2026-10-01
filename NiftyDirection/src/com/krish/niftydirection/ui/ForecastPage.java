package com.krish.niftydirection.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.krish.niftydirection.data.ForecastRunner;
import com.krish.niftydirection.data.Recorder;
import com.krish.niftydirection.forecast.Forecaster;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * The main question, answered plainly: which way is Nifty moving now, and which way is more likely
 * over the next 1 hour, 3 hours, 6 hours and at the next session's close — with proof of how often that was right.
 */
final class ForecastPage {
    private ForecastPage() {}

    interface Actions { void update(); void train(); }

    static LinearLayout build(Context c, File dir, String working, boolean loggedIn, Actions act) {
        LinearLayout col = Ui.col(c);
        ForecastRunner.Live live = ForecastRunner.last;
        List<Forecaster.Model> models = ForecastRunner.models(dir);

        // ---- status / buttons
        LinearLayout top = Ui.card(c);
        top.addView(Ui.text(c, "Where is Nifty going?", 18, Ui.TEXT, true));
        if (live != null) {
            String now = String.format(Locale.US, "Nifty %,.2f  ·  %s today", live.price, signed(live.dayChange));
            if (!Double.isNaN(live.hourChange)) now += "  ·  " + signed(live.hourChange) + " last hour";
            top.addView(Ui.text(c, now, 14, Ui.signColor(live.dayChange), true), Ui.top(c, 6));
            top.addView(Ui.text(c, movingNow(live), 13, Ui.TEXT, false), Ui.top(c, 4));
            top.addView(Ui.text(c, "Forecast made at " + live.asOf + " (only finished 5-minute bars are used).", 11, Ui.DIM, false), Ui.top(c, 4));
            if (!live.note.isEmpty()) top.addView(Ui.text(c, live.note, 12, Ui.AMBER, false), Ui.top(c, 4));
        } else if (models.isEmpty()) {
            top.addView(Ui.text(c, "No models yet. Tap “Train models”. The first time it downloads about 3 years of 5-minute history from Kite "
                    + "(a few minutes, then it is saved on the phone).", 13, Ui.DIM, false), Ui.top(c, 6));
        } else {
            top.addView(Ui.text(c, "Tap “Update forecast”.", 13, Ui.DIM, false), Ui.top(c, 6));
        }
        if (working != null) top.addView(Ui.text(c, working, 12, Ui.CYAN, false), Ui.top(c, 6));
        if (!loggedIn) top.addView(Ui.text(c, "Log in to Kite first (top right).", 12, Ui.AMBER, false), Ui.top(c, 6));
        LinearLayout btns = Ui.row(c);
        Button up = Ui.primary(c, "Update forecast");
        up.setEnabled(working == null && loggedIn && !models.isEmpty());
        up.setOnClickListener(v -> act.update());
        Button tr = Ui.button(c, models.isEmpty() ? "Train models" : "Retrain", Ui.CYAN);
        tr.setEnabled(working == null && loggedIn);
        tr.setOnClickListener(v -> act.train());
        btns.addView(up, Ui.weight(1));
        View gap = new View(c);
        btns.addView(gap, new LinearLayout.LayoutParams(Ui.dp(c, 10), 1));
        btns.addView(tr, Ui.weight(1));
        top.addView(btns, Ui.top(c, 10));
        col.addView(top, Ui.cardLp(c));

        // ---- the answer in one line
        if (live != null && !live.predictions.isEmpty()) {
            LinearLayout sum = Ui.card(c);
            sum.addView(Ui.header(c, "Best answer"));
            String[] fin = finalDirection(live, com.krish.niftydirection.data.Brain.last);
            int fc = fin[0].contains("UP") ? Ui.GREEN : fin[0].contains("DOWN") ? Ui.RED : Ui.AMBER;
            sum.addView(Ui.text(c, fin[0], 20, fc, true));
            sum.addView(Ui.text(c, fin[1], 12, Ui.DIM, false), Ui.top(c, 4));
            sum.addView(Ui.divider(c));
            sum.addView(Ui.text(c, bestAnswer(live), 13, Ui.TEXT, false));
            col.addView(sum, Ui.cardLp(c));
            for (Forecaster.Prediction p : live.predictions) col.addView(card(c, p), Ui.cardLp(c));
        } else if (!models.isEmpty()) {
        }

        // ---- details
        String rep = ForecastRunner.report(dir);
        if (!rep.isEmpty()) {
            LinearLayout d = Ui.card(c);
            d.addView(Ui.header(c, "What the models learnt from"));
            TextView body = Ui.mono(c, rep, 10.5f, Ui.DIM, false);
            body.setVisibility(View.GONE);
            TextView toggle = Ui.text(c, "Show details ▾", 13, Ui.CYAN, true);
            toggle.setOnClickListener(v -> {
                boolean show = body.getVisibility() != View.VISIBLE;
                body.setVisibility(show ? View.VISIBLE : View.GONE);
                toggle.setText(show ? "Hide details ▴" : "Show details ▾");
            });
            d.addView(toggle);
            d.addView(body, Ui.top(c, 6));
            col.addView(d, Ui.cardLp(c));
        }
        LinearLayout info = Ui.card(c);
        info.addView(Ui.header(c, "How to read this"));
        info.addView(Ui.text(c, "• UP 60% means: in the last 3 years, when the inputs looked like this, Nifty was higher at that time about 60 times out of 100.\n"
                + "• Strong = 65%+ · Clear = 58–65% · Mild = 53–58% · Toss-up = below 53%.\n"
                + "• Typical move = the usual size of the move over that time, up or down.\n"
                + "• Models retrain by themselves once a week (outside market hours).\n"
                + "• Live-only inputs (option chain, FII, news, order book) are being saved every refresh: " + Recorder.days(dir)
                + " days so far. After a few months the models can learn from them too.", 12, Ui.DIM, false));
        col.addView(info, Ui.cardLp(c));
        Pages.disclaimer(c, col);
        return col;
    }

    static String movingNow(ForecastRunner.Live l) {
        if (Double.isNaN(l.dayChange)) return "";
        String day = Math.abs(l.dayChange) < 0.15 ? "flat today" : l.dayChange > 0 ? "UP today" : "DOWN today";
        if (Double.isNaN(l.hourChange)) return "Moving now: " + day + ".";
        String hr = Math.abs(l.hourChange) < 0.08 ? "flat in the last hour" : l.hourChange > 0 ? "rising in the last hour" : "falling in the last hour";
        return "Moving now: " + day + ", " + hr + ".";
    }

    /**
     * One final line from the app's two independent parts: the forecast models (learnt from 3 years of prices)
     * and the evidence score on the Today tab (options, futures, FII, breadth, news …).
     * Same side → that side. One side and the other undecided → a lean. Opposite → no clear side.
     */
    static String[] finalDirection(ForecastRunner.Live l, com.krish.niftydirection.data.Brain.Output o) {
        double sum = 0; int n = 0;
        for (Forecaster.Prediction p : l.predictions) if (!Double.isNaN(p.pUp)) { sum += p.pUp; n++; }
        double avg = n > 0 ? sum / n : 0.5;
        int fSide = Math.abs(avg - 0.5) < 0.03 ? 0 : avg > 0.5 ? 1 : -1;
        String fText = fSide == 0 ? "forecast models: toss-up" : String.format(Locale.US, "forecast models: %s (avg %.0f%%)", fSide > 0 ? "UP" : "DOWN", Math.max(avg, 1 - avg) * 100);
        int eSide = 0;
        String eText = "evidence score: not loaded yet";
        if (o != null && o.result != null) {
            String rg = o.result.regime;
            boolean weak = o.result.coverage < 0.8 || o.result.confidence < 25;   // still loading, or too little evidence
            eSide = weak ? 0 : "BULLISH".equals(rg) ? 1 : "BEARISH".equals(rg) ? -1 : 0;
            eText = String.format(Locale.US, "evidence score: %s (%+.0f, confidence %d) at %s%s", rg, o.result.score, o.result.confidence,
                    Pages.time(o.snap.time), weak ? " — too little evidence yet" : "");
        }
        String head;
        if (fSide != 0 && fSide == eSide) head = "FINAL: " + (fSide > 0 ? "UP" : "DOWN") + " — both parts agree";
        else if (fSide != 0 && eSide != 0) head = "NO CLEAR SIDE — the two parts disagree";
        else if (fSide != 0 || eSide != 0) head = "LEANS " + ((fSide != 0 ? fSide : eSide) > 0 ? "UP" : "DOWN") + " — only one part sees it";
        else head = "NO CLEAR SIDE — both parts are undecided";
        return new String[]{head, "Based on " + fText + " · " + eText + "."};
    }

    static String bestAnswer(ForecastRunner.Live l) {
        Forecaster.Prediction best = null;
        for (Forecaster.Prediction p : l.predictions)
            if (!Double.isNaN(p.pUp) && (best == null || Math.abs(p.pUp - 0.5) > Math.abs(best.pUp - 0.5))) best = p;
        if (best == null) return "No forecast yet.";
        boolean up = best.pUp >= 0.5;
        StringBuilder b = new StringBuilder(String.format(Locale.US, "Most likely: %s — %s, %.0f%% (%s).",
                best.label, up ? "UP" : "DOWN", Math.max(best.pUp, 1 - best.pUp) * 100, best.when));
        int ups = 0, n = 0;
        for (Forecaster.Prediction p : l.predictions) if (!Double.isNaN(p.pUp)) { n++; if (p.pUp >= 0.5) ups++; }
        if (n > 1) b.append(ups == n ? " All horizons point UP." : ups == 0 ? " All horizons point DOWN." : " Horizons disagree: " + ups + " up, " + (n - ups) + " down.");
        if (Math.max(best.pUp, 1 - best.pUp) < 0.53) b.append(" Even the strongest side is close to a toss-up.");
        return b.toString();
    }

    static LinearLayout card(Context c, Forecaster.Prediction p) {
        LinearLayout k = Ui.card(c);
        boolean has = !Double.isNaN(p.pUp);
        double up = has ? p.pUp : 0.5;
        String st = has ? Forecaster.strength(up) : "";
        LinearLayout head = Ui.row(c);
        head.addView(Ui.text(c, p.label, 15, Ui.TEXT, true), Ui.weight(1));
        if (has) head.addView(Ui.pill(c, st, st.equals("Toss-up") ? Ui.GREY : up >= 0.5 ? Ui.GREEN : Ui.RED), Ui.wrap());
        k.addView(head);
        k.addView(Ui.text(c, p.when, 12, Ui.DIM, false), Ui.top(c, 2));
        String big = !has ? "No model" : up >= 0.5 ? String.format(Locale.US, "UP %.0f%%", up * 100) : String.format(Locale.US, "DOWN %.0f%%", (1 - up) * 100);
        int color = !has || st.equals("Toss-up") ? Ui.GREY : up >= 0.5 ? Ui.GREEN : Ui.RED;
        k.addView(Ui.text(c, big, 26, color, true), Ui.top(c, 6));
        if (has) k.addView(bar(c, up), Ui.top(c, 6));
        if (!Double.isNaN(p.moveMedPts)) k.addView(Ui.text(c, String.format(Locale.US, "Typical move over this time: ±%.0f points", p.moveMedPts), 12, Ui.DIM, false), Ui.top(c, 6));
        if (!p.reasons.isEmpty()) {
            k.addView(Ui.text(c, "Biggest reasons:", 12, Ui.DIM, true), Ui.top(c, 8));
            for (int i = 0; i < p.reasons.size(); i++)
                k.addView(Ui.text(c, "• " + p.reasons.get(i), 12, p.pushes.get(i) > 0 ? Ui.GREEN : Ui.RED, false), Ui.top(c, 2));
        }
        return k;
    }

    static View bar(Context c, double up) {
        LinearLayout r = Ui.row(c);
        r.setBackground(Ui.round(Ui.LINE, Ui.dp(c, 6), Ui.LINE, 0));
        TextView a = Ui.text(c, String.format(Locale.US, "UP %.0f%%", up * 100), 11, 0xFF000000, true);
        a.setGravity(Gravity.CENTER);
        a.setBackground(Ui.round(Ui.GREEN, Ui.dp(c, 6), Ui.GREEN, 0));
        TextView b = Ui.text(c, String.format(Locale.US, "DOWN %.0f%%", (1 - up) * 100), 11, 0xFF000000, true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(Ui.round(Ui.RED, Ui.dp(c, 6), Ui.RED, 0));
        r.addView(a, new LinearLayout.LayoutParams(0, Ui.dp(c, 22), (float) Math.max(0.12, up)));
        r.addView(b, new LinearLayout.LayoutParams(0, Ui.dp(c, 22), (float) Math.max(0.12, 1 - up)));
        return r;
    }

    static String signed(double v) { return Double.isNaN(v) ? "—" : String.format(Locale.US, "%+.2f%%", v); }
}

package com.krish.niftydirection.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.krish.niftydirection.data.IntelRunner;
import com.krish.niftydirection.intel.EventCalendarRisk;
import com.krish.niftydirection.intel.EventImpact;
import com.krish.niftydirection.intel.FeatureEngine;
import com.krish.niftydirection.intel.Horizon;
import com.krish.niftydirection.intel.HorizonModel;
import com.krish.niftydirection.intel.ImpactGraph;
import com.krish.niftydirection.intel.IntelEngine;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The main screen of the prediction engine: regime, current signal, all seven horizons with probability, confidence and
 * expected range (tap a row for Why? and What changed?), event risk, data quality, the learnt weights and the track record.
 */
final class IntelPage {
    private IntelPage() {}

    interface Actions { void update(); void train(); }

    static LinearLayout build(Context c, File dir, String working, boolean loggedIn, Actions act) {
        LinearLayout col = Ui.col(c);
        IntelEngine.Forecast fc = IntelRunner.last;
        List<HorizonModel> models = IntelRunner.models(dir);

        // ---- header
        LinearLayout top = Ui.card(c);
        top.addView(Ui.text(c, "NIFTY AI", 20, Ui.TEXT, true));
        if (fc != null) {
            top.addView(Ui.text(c, String.format(Locale.US, "Nifty %,.2f  ·  %s", fc.price, signed(fc.dayChange)), 15, Ui.signColor(fc.dayChange), true), Ui.top(c, 4));
            LinearLayout rr = Ui.row(c);
            rr.addView(Ui.pill(c, fc.regime.label(), regimeColor(fc.regime.label())), Ui.wrap());
            TextView det = Ui.text(c, "  " + fc.regime.detail(), 11, Ui.DIM, false);
            rr.addView(det, Ui.weight(1));
            top.addView(rr, Ui.top(c, 8));
            top.addView(Ui.text(c, "Forecast made at " + fc.asOf + " (finished 5-minute bars only).", 11, Ui.DIM, false), Ui.top(c, 6));
            if (!fc.note.isEmpty()) top.addView(Ui.text(c, fc.note, 12, Ui.AMBER, false), Ui.top(c, 4));
        } else if (models.isEmpty()) {
            top.addView(Ui.text(c, "No models yet. Tap “Train models”: it downloads about 3 years of 5-minute Nifty, Bank Nifty, VIX and sector history "
                    + "from Kite plus 29 world markets, bonds, currencies and commodities, then learns 9 group models + a meta model for each of 7 horizons, "
                    + "each tested on sessions it never saw.", 13, Ui.DIM, false), Ui.top(c, 6));
        } else top.addView(Ui.text(c, "Tap “Update forecast”.", 13, Ui.DIM, false), Ui.top(c, 6));
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
        btns.addView(new View(c), new LinearLayout.LayoutParams(Ui.dp(c, 10), 1));
        btns.addView(tr, Ui.weight(1));
        top.addView(btns, Ui.top(c, 10));
        col.addView(top, Ui.cardLp(c));

        if (fc != null) {
            col.addView(signal(c, fc), Ui.cardLp(c));
            col.addView(horizons(c, fc), Ui.cardLp(c));
            col.addView(factors(c, fc), Ui.cardLp(c));
            col.addView(events(c, fc), Ui.cardLp(c));
            if (!fc.changed.isEmpty() || anyChanged(fc)) col.addView(changed(c, fc), Ui.cardLp(c));
            if (!fc.memory.isEmpty()) col.addView(list(c, "Event memory — today's sequence", fc.memory, Ui.TEXT), Ui.cardLp(c));
            if (!fc.movers.isEmpty()) col.addView(movers(c, fc), Ui.cardLp(c));
            col.addView(quality(c, fc), Ui.cardLp(c));
            col.addView(record(c, fc, dir), Ui.cardLp(c));
        }
        if (!models.isEmpty()) col.addView(matrix(c, models), Ui.cardLp(c));
        List<ImpactGraph.Edge> g = fc != null && !fc.graph.isEmpty() ? fc.graph : IntelRunner.graph(dir);
        if (!g.isEmpty()) col.addView(graph(c, g), Ui.cardLp(c));
        String rep = IntelRunner.report(dir);
        if (!rep.isEmpty()) col.addView(collapsible(c, "What the models learnt from", Ui.mono(c, rep, 10.5f, Ui.DIM, false)), Ui.cardLp(c));
        col.addView(howTo(c), Ui.cardLp(c));
        Pages.disclaimer(c, col);
        return col;
    }

    // ================================================================== sections

    static View signal(Context c, IntelEngine.Forecast fc) {
        LinearLayout k = Ui.card(c);
        k.addView(Ui.header(c, "Current signal"));
        if (fc.signal < 0) {
            k.addView(Ui.text(c, "NO PROVEN EDGE", 22, Ui.GREY, true));
            k.addView(Ui.text(c, "No horizon has a proven edge with at least medium confidence right now. The table below still shows every forecast; "
                    + "treat them as close to the usual drift.", 12, Ui.DIM, false), Ui.top(c, 4));
            return k;
        }
        IntelEngine.HPred p = fc.preds.get(fc.signal);
        int col = dirColor(p.direction);
        k.addView(Ui.text(c, p.direction + "  ·  " + p.hz.label, 22, col, true));
        k.addView(Ui.kv(c, "Probability", String.format(Locale.US, "%.0f%% %s", p.sideProb() * 100, "BEARISH".equals(p.direction) ? "down" : "up"), col));
        k.addView(Ui.kv(c, "Confidence", p.confLabel + " (" + p.confidence + "/100)", confColor(p.confLabel)));
        if (!Double.isNaN(p.range68)) k.addView(Ui.kv(c, "Expected range (68%)", String.format(Locale.US, "±%.2f%%  (±%.0f pts)", p.range68 * 100, p.range68 * fc.price), Ui.TEXT));
        k.addView(Ui.divider(c));
        if (p.tradeable) k.addView(Ui.text(c, "Forecast is strong enough to act on (signal only — the app never trades).", 12, Ui.GREEN, true));
        else {
            k.addView(Ui.text(c, "NO TRADE — the forecast is not strong enough to act on:", 12, Ui.AMBER, true));
            for (String g : p.gate) k.addView(Ui.text(c, "• " + g, 12, Ui.DIM, false), Ui.top(c, 2));
        }
        return k;
    }

    static View horizons(Context c, IntelEngine.Forecast fc) {
        LinearLayout k = Ui.card(c);
        k.addView(Ui.header(c, "Forecast by horizon — tap a row for Why?"));
        LinearLayout hd = Ui.row(c);
        hd.addView(cell(c, "Horizon", Ui.DIM, false, 1.1f), Ui.weight(1.1f));
        hd.addView(cell(c, "Direction", Ui.DIM, false, 1.3f), Ui.weight(1.3f));
        hd.addView(cell(c, "Conf.", Ui.DIM, false, 0.9f), Ui.weight(0.9f));
        hd.addView(cell(c, "Range 68%", Ui.DIM, false, 1f), Ui.weight(1f));
        k.addView(hd);
        for (IntelEngine.HPred p : fc.preds) {
            LinearLayout row = Ui.row(c);
            row.setPadding(0, Ui.dp(c, 7), 0, Ui.dp(c, 7));
            int col = p.has() && p.proven() ? dirColor(p.direction) : Ui.GREY;
            row.addView(cell(c, p.hz.label, Ui.TEXT, true, 1.1f), Ui.weight(1.1f));
            String d = !p.has() ? "no model" : arrow(p.direction) + String.format(Locale.US, " %.0f%%", p.sideProb() * 100) + (p.proven() ? "" : " ·no edge");
            row.addView(cell(c, d, col, true, 1.3f), Ui.weight(1.3f));
            row.addView(cell(c, p.has() ? p.confLabel : "—", p.has() ? confColor(p.confLabel) : Ui.GREY, false, 0.9f), Ui.weight(0.9f));
            row.addView(cell(c, Double.isNaN(p.range68) ? "—" : String.format(Locale.US, "±%.2f%%", p.range68 * 100), Ui.DIM, false, 1f), Ui.weight(1f));
            k.addView(row);
            if (!p.has()) continue;
            LinearLayout det = detail(c, p, fc);
            det.setVisibility(View.GONE);
            row.setOnClickListener(v -> det.setVisibility(det.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
            k.addView(det);
            k.addView(thin(c));
        }
        return k;
    }

    static LinearLayout detail(Context c, IntelEngine.HPred p, IntelEngine.Forecast fc) {
        LinearLayout d = Ui.col(c);
        d.setPadding(Ui.dp(c, 8), 0, 0, Ui.dp(c, 8));
        d.addView(Ui.text(c, String.format(Locale.US, "WHY %s? P(up) %.1f%% · model %.1f%% · confidence %d", p.direction, p.pFinal * 100, p.pModel * 100, p.confidence), 12, Ui.TEXT, true));
        d.addView(Ui.text(c, p.why0, 11, Ui.DIM, false), Ui.top(c, 2));
        for (IntelEngine.Line l : p.why) d.addView(Ui.text(c, String.format(Locale.US, "%+5.1f  %s", l.pts, l.text), 12, Ui.signColor(l.pts), false), Ui.top(c, 2));
        if (!p.changed.isEmpty()) {
            d.addView(Ui.text(c, "WHAT CHANGED", 11, Ui.DIM, true), Ui.top(c, 8));
            for (String s : p.changed) d.addView(Ui.text(c, s, 12, Ui.TEXT, false), Ui.top(c, 2));
        }
        if (!Double.isNaN(p.range68))
            d.addView(Ui.text(c, String.format(Locale.US, "Expected move: half the time within ±%.0f pts, 68%% within ±%.0f, 90%% within ±%.0f.",
                    p.range50 * fc.price, p.range68 * fc.price, p.range90 * fc.price), 11, Ui.DIM, false), Ui.top(c, 8));
        d.addView(Ui.text(c, (p.tradeable ? "Trade gate: passed." : "Trade gate: NO TRADE — " + String.join("; ", p.gate) + "."), 11,
                p.tradeable ? Ui.GREEN : Ui.AMBER, false), Ui.top(c, 4));
        if (p.info != null) d.addView(Ui.text(c, "Tested: " + IntelRunner.testLine(p.info), 11, p.info.proven ? Ui.DIM : Ui.AMBER, false), Ui.top(c, 4));
        return d;
    }

    static View factors(Context c, IntelEngine.Forecast fc) {
        LinearLayout k = Ui.card(c);
        String h = fc.signal >= 0 ? fc.preds.get(fc.signal).hz.label : "1 hour";
        k.addView(Ui.header(c, "Top factors (" + h + ")"));
        k.addView(Ui.text(c, "POSITIVE", 11, Ui.GREEN, true));
        if (fc.positives.isEmpty()) k.addView(Ui.text(c, "none of note", 12, Ui.DIM, false));
        for (int i = 0; i < Math.min(5, fc.positives.size()); i++) k.addView(Ui.text(c, String.format(Locale.US, "+ %s  (%+.1f pts)", fc.positives.get(i).text, fc.positives.get(i).pts), 12, Ui.TEXT, false), Ui.top(c, 2));
        k.addView(Ui.text(c, "NEGATIVE", 11, Ui.RED, true), Ui.top(c, 8));
        if (fc.negatives.isEmpty()) k.addView(Ui.text(c, "none of note", 12, Ui.DIM, false));
        for (int i = 0; i < Math.min(5, fc.negatives.size()); i++) k.addView(Ui.text(c, String.format(Locale.US, "− %s  (%+.1f pts)", fc.negatives.get(i).text, fc.negatives.get(i).pts), 12, Ui.TEXT, false), Ui.top(c, 2));
        return k;
    }

    static View events(Context c, IntelEngine.Forecast fc) {
        LinearLayout k = Ui.card(c);
        k.addView(Ui.header(c, "Event risk"));
        EventCalendarRisk.Risk r = fc.risk;
        int col = EventCalendarRisk.PRE_EVENT.equals(r.mode) ? Ui.RED : EventCalendarRisk.NORMAL.equals(r.mode) ? Ui.GREEN : Ui.AMBER;
        k.addView(Ui.kv(c, "Mode", r.mode, col));
        k.addView(Ui.text(c, r.text, 12, Ui.DIM, false), Ui.top(c, 2));
        if (!r.next.isEmpty()) k.addView(Ui.kv(c, "Next important event", r.next, Ui.TEXT));
        if (!fc.events.isEmpty()) {
            k.addView(Ui.text(c, "EVENTS NOW (impact on the 1-hour forecast · source tier · confirmations)", 11, Ui.DIM, true), Ui.top(c, 8));
            for (int i = 0; i < Math.min(6, fc.events.size()); i++) {
                EventImpact.Event e = fc.events.get(i);
                String t = String.format(Locale.US, "%+.2f  T%d · %d src · %s", e.impact[2], e.tier, e.sources, e.title);
                if (!e.constituents.isEmpty()) t += " [" + String.join(", ", e.constituents) + "]";
                k.addView(Ui.text(c, t, 12, Ui.signColor(e.impact[2]), false), Ui.top(c, 2));
            }
        }
        return k;
    }

    static View changed(Context c, IntelEngine.Forecast fc) {
        LinearLayout k = Ui.card(c);
        k.addView(Ui.header(c, "What changed since the last update"));
        for (String s : fc.changed) k.addView(Ui.text(c, s, 12, Ui.TEXT, true), Ui.top(c, 2));
        for (IntelEngine.HPred p : fc.preds) {
            if (p.changed.isEmpty()) continue;
            k.addView(Ui.text(c, p.hz.label + ": " + p.changed.get(0), 12, Ui.TEXT, true), Ui.top(c, 6));
            for (int i = 1; i < p.changed.size(); i++) k.addView(Ui.text(c, p.changed.get(i), 12, Ui.signColor(sign(p.changed.get(i))), false), Ui.top(c, 1));
        }
        return k;
    }

    static View movers(Context c, IntelEngine.Forecast fc) {
        LinearLayout k = Ui.card(c);
        k.addView(Ui.header(c, "Nifty 50 constituents — who moved the index"));
        if (!Double.isNaN(fc.weightUp)) k.addView(Ui.kv(c, "Index weight trading up", String.format(Locale.US, "%.0f%%", fc.weightUp * 100), fc.weightUp >= 0.5 ? Ui.GREEN : Ui.RED));
        for (int i = 0; i < Math.min(8, fc.movers.size()); i++) {
            Object[] m = fc.movers.get(i);
            k.addView(Ui.kv(c, (String) m[0] + String.format(Locale.US, " (%+.2f%%)", (Double) m[2]), String.format(Locale.US, "%+.3f%% of Nifty", (Double) m[1]),
                    Ui.signColor((Double) m[1])));
        }
        return k;
    }

    static View quality(Context c, IntelEngine.Forecast fc) {
        LinearLayout k = Ui.card(c);
        k.addView(Ui.header(c, "Data quality"));
        double q = fc.quality.score;
        k.addView(Ui.kv(c, "Score", String.format(Locale.US, "%.0f%%", q * 100), q >= 0.8 ? Ui.GREEN : q >= 0.6 ? Ui.AMBER : Ui.RED));
        for (Map.Entry<String, Double> e : fc.quality.groups.entrySet())
            k.addView(Ui.kv(c, e.getKey(), String.format(Locale.US, "%.0f%% of inputs", e.getValue() * 100), e.getValue() >= 0.8 ? Ui.DIM : Ui.AMBER));
        for (String s : fc.quality.issues) k.addView(Ui.text(c, "• " + s, 12, Ui.AMBER, false), Ui.top(c, 2));
        return k;
    }

    static View record(Context c, IntelEngine.Forecast fc, File dir) {
        LinearLayout k = Ui.card(c);
        k.addView(Ui.header(c, "Track record"));
        k.addView(Ui.text(c, "WALK-FORWARD TEST (sessions the models never saw)", 11, Ui.DIM, true));
        for (IntelEngine.HPred p : fc.preds) {
            if (p.info == null) continue;
            k.addView(Ui.text(c, p.hz.label + ": " + IntelRunner.testLine(p.info), 11, p.info.proven ? Ui.TEXT : Ui.DIM, false), Ui.top(c, 3));
        }
        IntelEngine.HPred ref = fc.signal >= 0 ? fc.preds.get(fc.signal) : null;
        if (ref == null) for (IntelEngine.HPred p : fc.preds) if (p.info != null && p.hz.id.equals("1h")) ref = p;
        if (ref != null && ref.info != null && ref.info.reliability.length > 0) {
            k.addView(Ui.text(c, "CALIBRATION (" + ref.hz.label + "): predicted P(up) → how often it went up", 11, Ui.DIM, true), Ui.top(c, 10));
            for (double[] b : ref.info.reliability)
                k.addView(Ui.mono(c, String.format(Locale.US, "%3.0f%% → %3.0f%%   (%.0f samples)", b[1] * 100, b[2] * 100, b[0]), 11, Ui.TEXT, false));
        }
        if (ref != null && ref.info != null && !ref.info.byRegime.isEmpty()) {
            k.addView(Ui.text(c, "BY REGIME (" + ref.hz.label + " test): model right vs usual side", 11, Ui.DIM, true), Ui.top(c, 10));
            for (Map.Entry<String, double[]> e : ref.info.byRegime.entrySet())
                k.addView(Ui.mono(c, String.format(Locale.US, "%-16s %4.0f%% vs %3.0f%%  (%.0f)", e.getKey(), e.getValue()[1] * 100, e.getValue()[2] * 100, e.getValue()[0]), 11,
                        e.getValue()[1] > e.getValue()[2] ? Ui.GREEN : Ui.DIM, false));
        }
        k.addView(Ui.text(c, "LIVE RECORD (forecasts logged by this phone, scored once the time passed)", 11, Ui.DIM, true), Ui.top(c, 10));
        if (fc.scorecard.isEmpty()) k.addView(Ui.text(c, "Nothing resolved yet — forecasts are logged every 30 minutes while the market is open.", 12, Ui.DIM, false));
        for (Map.Entry<String, double[]> e : fc.scorecard.entrySet()) {
            double[] v = e.getValue();
            k.addView(Ui.mono(c, String.format(Locale.US, "%-4s %4.0f forecasts · right %.0f%% · Brier %.3f vs %.3f", e.getKey(), v[0], v[1] * 100, v[2], v[3]), 11,
                    v[2] < v[3] ? Ui.GREEN : Ui.DIM, false));
        }
        k.addView(Ui.text(c, "LIVE OVERLAYS (option/futures evidence + news)", 11, Ui.DIM, true), Ui.top(c, 10));
        for (Map.Entry<String, String> e : IntelRunner.overlayStatus(dir).entrySet())
            k.addView(Ui.text(c, e.getKey().toLowerCase(Locale.US) + ": " + e.getValue(), 11, Ui.DIM, false), Ui.top(c, 2));
        return k;
    }

    static View matrix(Context c, List<HorizonModel> models) {
        LinearLayout k = Ui.card(c);
        k.addView(Ui.header(c, "Prediction matrix — learnt weight of each group (%)"));
        StringBuilder b = new StringBuilder(String.format(Locale.US, "%-15s", ""));
        for (HorizonModel m : models) b.append(String.format(Locale.US, "%5s", m.id));
        b.append('\n');
        for (int g = 0; g < HorizonModel.G; g++) {
            String n = FeatureEngine.GROUP_NAMES[g];
            b.append(String.format(Locale.US, "%-15s", n.length() > 15 ? n.substring(0, 15) : n));
            for (HorizonModel m : models) b.append(String.format(Locale.US, "%5.0f", m.info.importance[g]));
            b.append('\n');
        }
        k.addView(Ui.mono(c, b.toString(), 10.5f, Ui.TEXT, false));
        k.addView(Ui.text(c, "Learnt from history by each horizon's meta model, not set by hand. Weights also shift in range-bound and high-volatility regimes.",
                11, Ui.DIM, false), Ui.top(c, 6));
        return k;
    }

    static View graph(Context c, List<ImpactGraph.Edge> g) {
        LinearLayout k = Ui.card(c);
        k.addView(Ui.header(c, "Impact graph — links learnt from the last year"));
        for (int i = 0; i < Math.min(10, g.size()); i++) {
            ImpactGraph.Edge e = g.get(i);
            k.addView(Ui.text(c, ImpactGraph.describe(e), 12, e.significant() ? Ui.signColor(e.corr) : Ui.DIM, false), Ui.top(c, 2));
        }
        return k;
    }

    static View howTo(Context c) {
        LinearLayout info = Ui.card(c);
        info.addView(Ui.header(c, "How to read this"));
        info.addView(Ui.text(c, "• Probabilities, not certainties: UP 62% means that when inputs looked like this, Nifty was higher at the target time about 62 times in 100.\n"
                + "• Each horizon has its own 9 group models (technical, trend, sector, volatility, global, currencies, bonds, commodities, calendar) and a meta model "
                + "that learnt how much to trust each, separately for range-bound and high-volatility markets. Probabilities are calibrated on out-of-sample forecasts.\n"
                + "• Proven edge = on the newest sessions it never learnt from, the model beat “always guess the usual side” and the 90% bootstrap band of its skill stays above zero. "
                + "Without it a forecast is shown grey and confidence is capped.\n"
                + "• Confidence is separate from direction: strength, proven edge, agreement of the groups, data quality and event risk.\n"
                + "• Live option/futures evidence (Today tab) and news events can move a forecast by at most ±10 points; their weights are re-learnt from this phone's own record.\n"
                + "• Trade gate: a forecast below your threshold (Settings), without proven edge, with low confidence or right before an event shows NO TRADE. "
                + "Signals only — the app never places orders.", 12, Ui.DIM, false));
        return info;
    }

    // ================================================================== helpers

    static View collapsible(Context c, String title, TextView body) {
        LinearLayout d = Ui.card(c);
        d.addView(Ui.header(c, title));
        body.setVisibility(View.GONE);
        TextView toggle = Ui.text(c, "Show ▾", 13, Ui.CYAN, true);
        toggle.setOnClickListener(v -> {
            boolean show = body.getVisibility() != View.VISIBLE;
            body.setVisibility(show ? View.VISIBLE : View.GONE);
            toggle.setText(show ? "Hide ▴" : "Show ▾");
        });
        d.addView(toggle);
        d.addView(body, Ui.top(c, 6));
        return d;
    }

    static View list(Context c, String title, List<String> lines, int color) {
        LinearLayout k = Ui.card(c);
        k.addView(Ui.header(c, title));
        for (int i = Math.max(0, lines.size() - 20); i < lines.size(); i++) k.addView(Ui.text(c, lines.get(i), 12, color, false), Ui.top(c, 2));
        return k;
    }

    static TextView cell(Context c, String s, int color, boolean bold, float w) {
        TextView t = Ui.text(c, s, 13, color, bold);
        t.setGravity(Gravity.START);
        return t;
    }

    static View thin(Context c) {
        View v = new View(c);
        v.setBackgroundColor(Ui.LINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, 1));
        return v;
    }

    static boolean anyChanged(IntelEngine.Forecast fc) { for (IntelEngine.HPred p : fc.preds) if (!p.changed.isEmpty()) return true; return false; }
    static double sign(String s) { return s.contains(" +") ? 1 : s.contains(" -") || s.contains(" −") ? -1 : 0; }
    static String arrow(String dir) { return "BULLISH".equals(dir) ? "↑" : "BEARISH".equals(dir) ? "↓" : "→"; }
    static int dirColor(String dir) { return "BULLISH".equals(dir) ? Ui.GREEN : "BEARISH".equals(dir) ? Ui.RED : Ui.GREY; }
    static int confColor(String l) { return "High".equals(l) ? Ui.GREEN : "Medium".equals(l) ? Ui.AMBER : Ui.GREY; }
    static int regimeColor(String l) {
        if (l.contains("BULL")) return Ui.GREEN;
        if (l.contains("BEAR") || l.contains("SHOCK") || l.contains("PANIC")) return Ui.RED;
        if (l.contains("EVENT") || l.contains("NEWS")) return Ui.PURPLE;
        return Ui.AMBER;
    }
    static String signed(double v) { return Double.isNaN(v) ? "—" : String.format(Locale.US, "%+.2f%%", v); }
}

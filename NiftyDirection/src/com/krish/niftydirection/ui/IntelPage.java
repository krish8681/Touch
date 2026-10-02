package com.krish.niftydirection.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.krish.niftydirection.data.IntelRunner;
import com.krish.niftydirection.intel.CrossMarket;
import com.krish.niftydirection.intel.EventCalendarRisk;
import com.krish.niftydirection.intel.EventImpact;
import com.krish.niftydirection.intel.FeatureEngine;
import com.krish.niftydirection.intel.HorizonModel;
import com.krish.niftydirection.intel.ImpactGraph;
import com.krish.niftydirection.intel.IntelEngine;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The AI tab. Overview answers "where is Nifty likely going and can I act on it?" at a glance (hero card, seven horizons,
 * top factors, event risk). Insights holds the stories behind it, Health holds data quality and the track record.
 */
final class IntelPage {
    private IntelPage() {}

    interface Actions { void update(); void train(); void redraw(); void validate(); void export(); }

    static final String[] SECTIONS = {"Overview", "Insights", "Health", "Validate"};
    static int section = 0;

    static LinearLayout build(Context c, File dir, String working, boolean loggedIn, Actions act) {
        LinearLayout col = Ui.col(c);
        IntelEngine.Forecast fc = IntelRunner.last;
        List<HorizonModel> models = IntelRunner.models(dir);

        col.addView(actions(c, fc, models, working, loggedIn, act), Ui.cardLp(c));
        if (fc == null) {
            col.addView(welcome(c, models.isEmpty(), loggedIn), Ui.cardLp(c));
            if (!models.isEmpty()) col.addView(matrix(c, models), Ui.cardLp(c));
            col.addView(howTo(c), Ui.cardLp(c));
            Pages.disclaimer(c, col);
            return col;
        }
        col.addView(segments(c, act), Ui.cardLp(c));
        if (section == 0) {
            col.addView(hero(c, fc), Ui.cardLp(c));
            col.addView(horizons(c, fc), Ui.cardLp(c));
            col.addView(factors(c, fc), Ui.cardLp(c));
            col.addView(risk(c, fc), Ui.cardLp(c));
        } else if (section == 1) {
            if (!fc.changed.isEmpty() || anyChanged(fc)) col.addView(changed(c, fc), Ui.cardLp(c));
            if (!fc.events.isEmpty()) col.addView(events(c, fc), Ui.cardLp(c));
            if (!fc.chains.isEmpty()) col.addView(chains(c, fc), Ui.cardLp(c));
            if (!fc.memory.isEmpty()) col.addView(memory(c, fc), Ui.cardLp(c));
            if (!fc.movers.isEmpty()) col.addView(movers(c, fc), Ui.cardLp(c));
            List<ImpactGraph.Edge> g = !fc.graph.isEmpty() ? fc.graph : IntelRunner.graph(dir);
            if (!g.isEmpty()) col.addView(graph(c, g), Ui.cardLp(c));
        } else if (section == 3) {
            validateSection(c, col, dir, working, loggedIn, act);
        } else {
            col.addView(quality(c, fc), Ui.cardLp(c));
            col.addView(record(c, fc, dir), Ui.cardLp(c));
            if (!models.isEmpty()) col.addView(matrix(c, models), Ui.cardLp(c));
            String rep = IntelRunner.report(dir);
            if (!rep.isEmpty()) col.addView(collapsible(c, "What the models learnt from", Ui.mono(c, rep, 10.5f, Ui.DIM, false)), Ui.cardLp(c));
            col.addView(howTo(c), Ui.cardLp(c));
        }
        Pages.disclaimer(c, col);
        return col;
    }

    // ================================================================== top: actions + sections

    static View actions(Context c, IntelEngine.Forecast fc, List<HorizonModel> models, String working, boolean loggedIn, Actions act) {
        LinearLayout k = Ui.col(c);
        LinearLayout r = Ui.row(c);
        LinearLayout info = Ui.col(c);
        info.addView(Ui.text(c, "NIFTY AI", 20, Ui.TEXT, true));
        String sub = working != null ? working : fc != null ? fc.asOf : models.isEmpty() ? "Not trained yet" : "Ready";
        info.addView(Ui.text(c, sub, 11.5f, working != null ? Ui.CYAN : Ui.DIM, false));
        r.addView(info, Ui.weight(1));
        Button up = Ui.primary(c, "⟳  Update");
        up.setTextSize(13);
        up.setEnabled(working == null && loggedIn && !models.isEmpty());
        up.setAlpha(up.isEnabled() ? 1f : 0.45f);
        up.setOnClickListener(v -> act.update());
        r.addView(up, Ui.wrap());
        Button tr = Ui.button(c, models.isEmpty() ? "Train" : "Retrain", Ui.CYAN);
        tr.setTextSize(13);
        tr.setEnabled(working == null && loggedIn);
        tr.setAlpha(tr.isEnabled() ? 1f : 0.45f);
        tr.setOnClickListener(v -> act.train());
        r.addView(tr, Ui.gapLeft(c, 8));
        k.addView(r);
        if (!loggedIn) k.addView(banner(c, "🔑  Log in to Kite (top right) to load data and forecasts.", Ui.AMBER), Ui.top(c, 10));
        if (fc != null && !fc.note.isEmpty()) k.addView(banner(c, "ℹ  " + fc.note, Ui.CYAN), Ui.top(c, 10));
        return k;
    }

    static View segments(Context c, Actions act) {
        LinearLayout r = Ui.row(c);
        r.setBackground(Ui.round(0xFF111727, Ui.dp(c, 16), 0x1AFFFFFF, 1));
        int p = Ui.dp(c, 4);
        r.setPadding(p, p, p, p);
        for (int i = 0; i < SECTIONS.length; i++) {
            final int k = i;
            TextView t = Ui.text(c, SECTIONS[i], 13, i == section ? Ui.TEXT : Ui.DIM, i == section);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, Ui.dp(c, 9), 0, Ui.dp(c, 9));
            if (i == section) t.setBackground(Ui.gradient(Ui.dp(c, 12), 0, Ui.alpha(Ui.CYAN, 0x40), Ui.alpha(Ui.ACCENT2, 0x40)));
            t.setOnClickListener(v -> { section = k; act.redraw(); });
            r.addView(t, Ui.weight(1));
        }
        return r;
    }

    static View welcome(Context c, boolean noModels, boolean loggedIn) {
        LinearLayout k = Ui.hero(c, Ui.CYAN);
        k.addView(Ui.text(c, "Your Nifty forecast, in three steps", 18, Ui.TEXT, true));
        String[][] steps = {
                {"1", "Log in", "Tap Log in at the top right (once each morning).", loggedIn ? "✓" : ""},
                {"2", "Train", "Downloads ~3 years of history and learns 7 horizon models (a few minutes, once a week).", noModels ? "" : "✓"},
                {"3", "Update", "Get calibrated forecasts for 15 min → 1 week, with reasons.", ""}};
        for (String[] s : steps) {
            LinearLayout r = Ui.row(c);
            r.setPadding(0, Ui.dp(c, 12), 0, 0);
            TextView n = Ui.text(c, s[3].isEmpty() ? s[0] : s[3], 14, s[3].isEmpty() ? Ui.TEXT : Ui.GREEN, true);
            n.setGravity(Gravity.CENTER);
            n.setBackground(Ui.round(Ui.alpha(s[3].isEmpty() ? Ui.CYAN : Ui.GREEN, 0x30), Ui.dp(c, 16), 0, 0));
            r.addView(n, new LinearLayout.LayoutParams(Ui.dp(c, 32), Ui.dp(c, 32)));
            LinearLayout t = Ui.col(c);
            t.setPadding(Ui.dp(c, 12), 0, 0, 0);
            t.addView(Ui.text(c, s[1], 14, Ui.TEXT, true));
            t.addView(Ui.text(c, s[2], 12, Ui.DIM, false));
            r.addView(t, Ui.weight(1));
            k.addView(r);
        }
        return k;
    }

    // ================================================================== overview

    static View hero(Context c, IntelEngine.Forecast fc) {
        IntelEngine.HPred sig = fc.signal >= 0 ? fc.preds.get(fc.signal) : null;
        IntelEngine.HPred show = sig != null ? sig : find(fc, "1h");
        int col = sig != null ? dirColor(sig.direction) : Ui.GREY;
        LinearLayout k = Ui.hero(c, col);

        LinearLayout r1 = Ui.row(c);
        r1.addView(Ui.text(c, "NIFTY 50", 12, Ui.DIM, true), Ui.weight(1));
        r1.addView(Ui.chip(c, fc.regime.label(), regimeColor(fc.regime.label())), Ui.wrap());
        k.addView(r1);
        LinearLayout r2 = Ui.row(c);
        r2.setPadding(0, Ui.dp(c, 2), 0, 0);
        r2.addView(Ui.text(c, String.format(Locale.US, "%,.2f", fc.price), 30, Ui.TEXT, true), Ui.wrap());
        if (!Double.isNaN(fc.dayChange)) r2.addView(Ui.chip(c, (fc.dayChange >= 0 ? "▲ " : "▼ ") + signed(fc.dayChange), Ui.signColor(fc.dayChange)), Ui.gapLeft(c, 10));
        k.addView(r2);
        String det = fc.regime.detail();
        if (!det.isEmpty()) k.addView(Ui.text(c, det, 11, Ui.DIM, false), Ui.top(c, 2));

        LinearLayout r3 = Ui.row(c);
        r3.setPadding(0, Ui.dp(c, 16), 0, 0);
        Fx.Ring ring = new Fx.Ring(c);
        if (show != null && show.has()) {
            double sp = show.sideProb();
            ring.set(sp, String.format(Locale.US, "%.0f%%", sp * 100), "BEARISH".equals(show.direction) ? "chance down" : "chance up", sig != null ? col : Ui.GREY);
        } else ring.set(0, "—", "no model", Ui.GREY);
        r3.addView(ring, Ui.wrap());
        LinearLayout side = Ui.col(c);
        side.setPadding(Ui.dp(c, 16), 0, 0, 0);
        side.addView(Ui.text(c, "CURRENT SIGNAL", 10.5f, Ui.DIM, true));
        if (sig != null) {
            side.addView(Ui.text(c, arrow(sig.direction) + " " + sig.direction, 24, col, true), Ui.top(c, 2));
            side.addView(Ui.text(c, "next " + sig.hz.label, 13, Ui.TEXT, false));
            LinearLayout cr = Ui.row(c);
            cr.setPadding(0, Ui.dp(c, 8), 0, 0);
            Fx.Dots dots = new Fx.Dots(c);
            dots.set(sig.confidence, confColor(sig.confLabel));
            cr.addView(dots, Ui.wrap());
            cr.addView(Ui.text(c, "  " + sig.confLabel + " confidence", 12, Ui.DIM, false), Ui.wrap());
            side.addView(cr);
            side.addView(Ui.chip(c, "Signal quality: " + sig.signalQuality, qualityColor(sig.signalQuality)), Ui.top(c, 8));
        } else {
            side.addView(Ui.text(c, "No clear edge", 22, Ui.TEXT, true), Ui.top(c, 2));
            side.addView(Ui.text(c, "No horizon has a proven edge with enough confidence right now.", 12, Ui.DIM, false), Ui.top(c, 2));
        }
        r3.addView(side, Ui.weight(1));
        k.addView(r3);

        k.addView(Ui.text(c, summary(fc, sig, show), 13.5f, Ui.TEXT, false), Ui.top(c, 14));
        if (sig != null) {
            if (sig.tradeable) {
                k.addView(banner(c, "✓  Strong enough to act on — signal only, the app never trades.", Ui.GREEN), Ui.top(c, 12));
                Button took = Ui.button(c, "✍  I took this trade", Ui.GREEN);
                took.setOnClickListener(v -> tookDialog(c, sig, fc));
                k.addView(took, Ui.top(c, 8));
            } else k.addView(banner(c, "⏸  No trade: " + (sig.gate.isEmpty() ? "not strong enough" : sig.gate.get(0)), Ui.AMBER), Ui.top(c, 12));
        }
        if (!fc.guard.isEmpty()) k.addView(banner(c, "🛡  Risk guard: " + String.join(" · ", fc.guard), Ui.RED), Ui.top(c, 8));
        return k;
    }

    /** One plain sentence: what the most useful forecast says, in words. */
    static String summary(IntelEngine.Forecast fc, IntelEngine.HPred sig, IntelEngine.HPred show) {
        if (sig != null) {
            String move = "BULLISH".equals(sig.direction) ? "higher" : "lower";
            String s = String.format(Locale.US, "Nifty is more likely to be %s in %s (%.0f%%).", move, sig.hz.label, sig.sideProb() * 100);
            if (!Double.isNaN(sig.range68) && fc.price > 0) s += String.format(Locale.US, " Usual range: ±%.0f points.", sig.range68 * fc.price);
            if (!fc.positives.isEmpty() || !fc.negatives.isEmpty()) {
                List<IntelEngine.Line> drv = "BULLISH".equals(sig.direction) ? fc.positives : fc.negatives;
                if (!drv.isEmpty()) s += " Main reason: " + shortReason(drv.get(0).text) + ".";
            }
            return s;
        }
        if (show != null && show.has()) return String.format(Locale.US, "Forecasts are close to the usual drift (1 hour: %.0f%% up). Wait for a clearer setup.", show.pFinal * 100);
        return "Tap Update to get a forecast.";
    }

    static View horizons(Context c, IntelEngine.Forecast fc) {
        LinearLayout k = Ui.card(c);
        k.addView(Ui.title(c, "Forecasts", "tap a row for why"));
        boolean first = true;
        for (IntelEngine.HPred p : fc.preds) {
            if (!first) k.addView(thin(c));
            first = false;
            LinearLayout rowBox = Ui.col(c);
            rowBox.setPadding(0, Ui.dp(c, 10), 0, Ui.dp(c, 10));
            LinearLayout r = Ui.row(c);
            LinearLayout left = Ui.col(c);
            left.addView(Ui.text(c, p.hz.label, 14, Ui.TEXT, true));
            if (p.has()) {
                Fx.Dots d = new Fx.Dots(c);
                d.set(p.confidence, confColor(p.confLabel));
                LinearLayout dr = Ui.row(c);
                dr.setPadding(0, Ui.dp(c, 4), 0, 0);
                dr.addView(d, Ui.wrap());
                dr.addView(Ui.text(c, " " + p.confLabel, 10.5f, Ui.DIM, false), Ui.wrap());
                left.addView(dr);
            }
            r.addView(left, Ui.weight(1));
            int col = p.has() && p.proven() ? dirColor(p.direction) : Ui.GREY;
            String big = !p.has() ? "—" : arrow(p.direction) + String.format(Locale.US, " %.0f%%", p.sideProb() * 100);
            TextView bt = Ui.text(c, big, 19, col, true);
            r.addView(bt, Ui.wrap());
            String chip = !p.has() ? (p.hz != null && p.hz.eod() && p.info != null ? "after close" : "no model") : !p.validation.isEmpty() ? p.validation : !p.proven() ? "no edge" : Double.isNaN(p.range68) ? p.signalQuality : String.format(Locale.US, "±%.2f%%", p.range68 * 100);
            TextView ch = Ui.chip(c, chip, !p.has() ? Ui.GREY : !p.validation.isEmpty() ? verdictColor(p.validation) : !p.proven() ? Ui.GREY : Ui.CYAN);
            ch.setMinWidth(Ui.dp(c, 72));
            r.addView(ch, Ui.gapLeft(c, 10));
            rowBox.addView(r);
            if (p.has()) {
                Fx.Split sp = new Fx.Split(c);
                sp.set(p.pUp3, p.pFlat, p.pDown3, p.pFinal);
                if (!p.proven()) sp.setAlpha(0.45f);
                rowBox.addView(sp, Ui.top(c, 8));
                LinearLayout det = detail(c, p, fc);
                det.setVisibility(View.GONE);
                rowBox.setOnClickListener(v -> det.setVisibility(det.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
                rowBox.addView(det);
            }
            k.addView(rowBox);
        }
        LinearLayout legend = Ui.row(c);
        legend.setPadding(0, Ui.dp(c, 8), 0, 0);
        legend.addView(Ui.text(c, "● up   ", 10.5f, Ui.GREEN, false));
        legend.addView(Ui.text(c, "● flat   ", 10.5f, 0xFF9CA3AF, false));
        legend.addView(Ui.text(c, "● down", 10.5f, Ui.RED, false));
        k.addView(legend);
        return k;
    }

    static LinearLayout detail(Context c, IntelEngine.HPred p, IntelEngine.Forecast fc) {
        LinearLayout d = Ui.col(c);
        d.setBackground(Ui.round(0x0DFFFFFF, Ui.dp(c, 14), 0, 0));
        int pad = Ui.dp(c, 12);
        d.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = Ui.top(c, 10);
        d.setLayoutParams(lp);

        LinearLayout stats = Ui.row(c);
        stats.addView(stat(c, "P(up)", String.format(Locale.US, "%.0f%%", p.pFinal * 100), Ui.TEXT), Ui.weight(1));
        stats.addView(stat(c, "Confidence", p.confidence + "/100", confColor(p.confLabel)), Ui.weight(1));
        stats.addView(stat(c, "Signal", p.signalQuality, qualityColor(p.signalQuality)), Ui.weight(1));
        d.addView(stats);
        if (!Double.isNaN(p.pFlat)) {
            LinearLayout s2 = Ui.row(c);
            s2.setPadding(0, Ui.dp(c, 8), 0, 0);
            s2.addView(stat(c, "Up · flat · down", String.format(Locale.US, "%.0f · %.0f · %.0f", p.pUp3 * 100, p.pFlat * 100, p.pDown3 * 100), Ui.TEXT), Ui.weight(1.4f));
            if (!Double.isNaN(p.expReturn)) s2.addView(stat(c, "Exp. return", String.format(Locale.US, "%+.2f%%", p.expReturn * 100), Ui.signColor(p.expReturn)), Ui.weight(1));
            if (!Double.isNaN(p.expHigh)) s2.addView(stat(c, "High / low", String.format(Locale.US, "%,.0f / %,.0f", fc.price * (1 + p.expHigh), fc.price * (1 + p.expLow)), Ui.TEXT), Ui.weight(1.3f));
            d.addView(s2);
        }

        d.addView(Ui.text(c, "WHY", 10.5f, Ui.DIM, true), Ui.top(c, 12));
        d.addView(Ui.text(c, p.why0, 11, Ui.DIM, false), Ui.top(c, 2));
        double max = 1;
        for (IntelEngine.Line l : p.why) max = Math.max(max, Math.abs(l.pts));
        for (IntelEngine.Line l : p.why) d.addView(pushRow(c, l.text, l.pts, max), Ui.top(c, 6));
        if (!p.changed.isEmpty()) {
            d.addView(Ui.text(c, "WHAT CHANGED", 10.5f, Ui.DIM, true), Ui.top(c, 12));
            for (String s : p.changed) d.addView(Ui.text(c, s, 12, s.startsWith("Before") ? Ui.TEXT : Ui.signColor(sign(s)), s.startsWith("Before")), Ui.top(c, 2));
        }
        if (!Double.isNaN(p.range68))
            d.addView(Ui.text(c, String.format(Locale.US, "Range: half the time within ±%.0f pts · 68%% within ±%.0f · 90%% within ±%.0f",
                    p.range50 * fc.price, p.range68 * fc.price, p.range90 * fc.price), 11, Ui.DIM, false), Ui.top(c, 12));
        d.addView(Ui.text(c, p.tradeable ? "✓ Trade gate passed" : "⏸ No trade — " + String.join("; ", p.gate), 11.5f, p.tradeable ? Ui.GREEN : Ui.AMBER, false), Ui.top(c, 6));
        if (p.tradeable) {
            Button took = Ui.button(c, "✍  I took this trade", Ui.GREEN);
            took.setOnClickListener(v -> tookDialog(c, p, fc));
            d.addView(took, Ui.top(c, 6));
        }
        if (p.info != null) d.addView(Ui.text(c, "Tested: " + IntelRunner.testLine(p.info), 10.5f, p.info.proven ? Ui.DIM : Ui.AMBER, false), Ui.top(c, 6));
        return d;
    }

    static View factors(Context c, IntelEngine.Forecast fc) {
        LinearLayout k = Ui.card(c);
        String h = fc.signal >= 0 ? fc.preds.get(fc.signal).hz.label : "1 hour";
        k.addView(Ui.title(c, "What is driving it", h + " forecast"));
        double max = 1;
        for (IntelEngine.Line l : fc.positives) max = Math.max(max, Math.abs(l.pts));
        for (IntelEngine.Line l : fc.negatives) max = Math.max(max, Math.abs(l.pts));
        if (fc.positives.isEmpty() && fc.negatives.isEmpty()) k.addView(Ui.text(c, "Nothing stands out right now.", 12, Ui.DIM, false));
        if (!fc.positives.isEmpty()) k.addView(Ui.chip(c, "PUSHING UP", Ui.GREEN), Ui.wrap());
        for (int i = 0; i < Math.min(4, fc.positives.size()); i++) k.addView(pushRow(c, fc.positives.get(i).text, fc.positives.get(i).pts, max), Ui.top(c, 8));
        if (!fc.negatives.isEmpty()) {
            LinearLayout.LayoutParams lp = Ui.wrap();
            lp.topMargin = Ui.dp(c, 14);
            k.addView(Ui.chip(c, "PUSHING DOWN", Ui.RED), lp);
        }
        for (int i = 0; i < Math.min(4, fc.negatives.size()); i++) k.addView(pushRow(c, fc.negatives.get(i).text, fc.negatives.get(i).pts, max), Ui.top(c, 8));
        return k;
    }

    static View risk(Context c, IntelEngine.Forecast fc) {
        LinearLayout k = Ui.card(c);
        EventCalendarRisk.Risk r = fc.risk;
        int col = EventCalendarRisk.PRE_EVENT.equals(r.mode) ? Ui.RED : EventCalendarRisk.NORMAL.equals(r.mode) ? Ui.GREEN : Ui.AMBER;
        LinearLayout t = Ui.row(c);
        t.setPadding(0, 0, 0, Ui.dp(c, 8));
        t.addView(Ui.text(c, "Event risk", 15, Ui.TEXT, true), Ui.weight(1));
        t.addView(Ui.chip(c, r.mode, col), Ui.wrap());
        k.addView(t);
        k.addView(Ui.text(c, r.text, 12.5f, Ui.DIM, false));
        if (!r.next.isEmpty()) {
            LinearLayout n = Ui.row(c);
            n.setPadding(0, Ui.dp(c, 10), 0, 0);
            n.addView(Ui.text(c, "📅  ", 14, Ui.TEXT, false), Ui.wrap());
            LinearLayout nc = Ui.col(c);
            nc.addView(Ui.text(c, "Next important event", 10.5f, Ui.DIM, true));
            nc.addView(Ui.text(c, r.next, 13, Ui.TEXT, true));
            n.addView(nc, Ui.weight(1));
            k.addView(n);
        }
        if (!fc.events.isEmpty()) k.addView(Ui.text(c, fc.events.size() + " news event" + (fc.events.size() == 1 ? "" : "s") + " in play — see Insights", 11.5f, Ui.CYAN, false), Ui.top(c, 10));
        return k;
    }

    // ================================================================== insights

    static View changed(Context c, IntelEngine.Forecast fc) {
        LinearLayout k = Ui.card(c);
        k.addView(Ui.title(c, "What changed", "since the last update"));
        for (String s : fc.changed) k.addView(Ui.text(c, s, 12.5f, Ui.TEXT, true), Ui.top(c, 2));
        for (IntelEngine.HPred p : fc.preds) {
            if (p.changed.isEmpty()) continue;
            k.addView(Ui.text(c, p.hz.label + " · " + p.changed.get(0), 12.5f, Ui.TEXT, true), Ui.top(c, 8));
            for (int i = 1; i < p.changed.size(); i++) k.addView(Ui.text(c, p.changed.get(i), 12, Ui.signColor(sign(p.changed.get(i))), false), Ui.top(c, 1));
        }
        return k;
    }

    static View events(Context c, IntelEngine.Forecast fc) {
        LinearLayout k = Ui.card(c);
        k.addView(Ui.title(c, "News events", "impact on the 1-hour forecast"));
        for (int i = 0; i < Math.min(8, fc.events.size()); i++) {
            EventImpact.Event e = fc.events.get(i);
            if (i > 0) k.addView(thin(c));
            LinearLayout box = Ui.col(c);
            box.setPadding(0, Ui.dp(c, 8), 0, Ui.dp(c, 8));
            LinearLayout r = Ui.row(c);
            r.addView(Ui.chip(c, "T" + e.tier, e.tier == 1 ? Ui.PURPLE : e.tier == 2 ? Ui.CYAN : Ui.GREY), Ui.wrap());
            r.addView(Ui.chip(c, e.persistence.toLowerCase(Locale.US), Ui.GREY), Ui.gapLeft(c, 6));
            if (e.sources > 1) r.addView(Ui.chip(c, e.sources + " sources", Ui.GREY), Ui.gapLeft(c, 6));
            View sp = new View(c);
            r.addView(sp, Ui.weight(1));
            r.addView(Ui.text(c, String.format(Locale.US, "%+.2f", e.impact[2]), 13, Ui.signColor(e.impact[2]), true), Ui.wrap());
            box.addView(r);
            box.addView(Ui.text(c, e.title, 13, Ui.TEXT, false), Ui.top(c, 6));
            if (!e.chain.isEmpty()) box.addView(Ui.text(c, "↳ " + e.chain, 11, Ui.DIM, false), Ui.top(c, 3));
            k.addView(box);
        }
        return k;
    }

    static View chains(Context c, IntelEngine.Forecast fc) {
        LinearLayout k = Ui.card(c);
        k.addView(Ui.title(c, "Cross-market chains", "how today's shocks travel"));
        for (CrossMarket.Path p : fc.chains) {
            LinearLayout box = Ui.col(c);
            box.setBackground(Ui.round(0x0DFFFFFF, Ui.dp(c, 12), 0, 0));
            int pad = Ui.dp(c, 10);
            box.setPadding(pad, pad, pad, pad);
            LinearLayout r = Ui.row(c);
            r.addView(Ui.text(c, p.name, 13, Ui.TEXT, true), Ui.weight(1));
            r.addView(Ui.text(c, Double.isNaN(p.implied) ? "—" : String.format(Locale.US, "Nifty %+.2f%%", p.implied), 13,
                    Double.isNaN(p.implied) ? Ui.DIM : Ui.signColor(p.implied), true), Ui.wrap());
            box.addView(r);
            StringBuilder nodes = new StringBuilder();
            for (int i = 0; i < p.nodes.length; i++) {
                if (i > 0) nodes.append(Double.isNaN(p.beta[i - 1]) ? "  →  " : String.format(Locale.US, "  →(%+.2f%s)→  ", p.beta[i - 1], Math.abs(p.t[i - 1]) >= 2 ? "" : "?"));
                nodes.append(p.nodes[i].equals("NIFTY") ? "Nifty" : p.nodes[i]);
            }
            box.addView(Ui.text(c, nodes.toString(), 11, Ui.DIM, false), Ui.top(c, 4));
            if (!Double.isNaN(p.rootMove)) box.addView(Ui.text(c, String.format(Locale.US, "%s moved %+.2f%s last session", p.nodes[0], p.rootMove,
                    com.krish.niftydirection.intel.Markets.isYield(p.nodes[0]) ? " (×10 bp)" : "%"), 11, Ui.DIM, false), Ui.top(c, 2));
            k.addView(box, Ui.top(c, 6));
        }
        k.addView(Ui.text(c, "Each link's strength is learnt from the last 250 sessions. ? = not statistically significant.", 10.5f, Ui.DIM, false), Ui.top(c, 8));
        return k;
    }

    static View memory(Context c, IntelEngine.Forecast fc) {
        LinearLayout k = Ui.card(c);
        k.addView(Ui.title(c, "Today's timeline", "event memory"));
        List<String> lines = fc.memory;
        for (int i = Math.max(0, lines.size() - 15); i < lines.size(); i++) {
            String s = lines.get(i);
            int sp = s.indexOf("  ");
            String tm = sp > 0 ? s.substring(0, sp) : "", tx = sp > 0 ? s.substring(sp + 2) : s;
            LinearLayout r = Ui.row(c);
            r.setGravity(Gravity.TOP);
            r.setPadding(0, Ui.dp(c, 6), 0, 0);
            TextView t = Ui.text(c, tm, 11.5f, Ui.CYAN, true);
            t.setMinWidth(Ui.dp(c, 44));
            r.addView(t, Ui.wrap());
            TextView dot = Ui.text(c, "●", 9, i == lines.size() - 1 ? Ui.CYAN : Ui.LINE, false);
            dot.setPadding(Ui.dp(c, 4), Ui.dp(c, 2), Ui.dp(c, 8), 0);
            r.addView(dot, Ui.wrap());
            r.addView(Ui.text(c, tx, 12.5f, Ui.TEXT, false), Ui.weight(1));
            k.addView(r);
        }
        return k;
    }

    static View movers(Context c, IntelEngine.Forecast fc) {
        LinearLayout k = Ui.card(c);
        k.addView(Ui.title(c, "Who moved Nifty", Double.isNaN(fc.weightUp) ? "" : String.format(Locale.US, "%.0f%% of weight up", fc.weightUp * 100)));
        double max = 0.01;
        for (int i = 0; i < Math.min(8, fc.movers.size()); i++) max = Math.max(max, Math.abs((Double) fc.movers.get(i)[1]));
        for (int i = 0; i < Math.min(8, fc.movers.size()); i++) {
            Object[] m = fc.movers.get(i);
            k.addView(pushRow(c, String.format(Locale.US, "%s  %+.2f%%", m[0], (Double) m[2]), (Double) m[1] * 100, max * 100, "%+.1f bp"), Ui.top(c, 6));
        }
        return k;
    }

    static View graph(Context c, List<ImpactGraph.Edge> g) {
        LinearLayout k = Ui.card(c);
        k.addView(Ui.title(c, "Learnt links", "last year, by correlation"));
        for (int i = 0; i < Math.min(10, g.size()); i++) {
            ImpactGraph.Edge e = g.get(i);
            String to = e.to.equals("NIFTY") ? "Nifty" : e.to;
            LinearLayout r = Ui.col(c);
            LinearLayout h = Ui.row(c);
            h.addView(Ui.text(c, e.from + " → " + to, 12.5f, e.significant() ? Ui.TEXT : Ui.DIM, false), Ui.weight(1));
            h.addView(Ui.text(c, String.format(Locale.US, "%+.2f", e.corr), 12.5f, e.significant() ? Ui.signColor(e.corr) : Ui.DIM, true), Ui.wrap());
            r.addView(h);
            Fx.Push pb = new Fx.Push(c);
            pb.set(e.corr, 1);
            if (!e.significant()) pb.setAlpha(0.4f);
            r.addView(pb, Ui.top(c, 4));
            k.addView(r, Ui.top(c, 8));
        }
        return k;
    }

    // ================================================================== health

    static View quality(Context c, IntelEngine.Forecast fc) {
        LinearLayout k = Ui.card(c);
        double q = fc.quality.score;
        int qc = q >= 0.8 ? Ui.GREEN : q >= 0.6 ? Ui.AMBER : Ui.RED;
        LinearLayout t = Ui.row(c);
        t.addView(Ui.text(c, "Data quality", 15, Ui.TEXT, true), Ui.weight(1));
        t.addView(Ui.text(c, String.format(Locale.US, "%.0f%%", q * 100), 22, qc, true), Ui.wrap());
        k.addView(t);
        Fx.Progress pr = new Fx.Progress(c);
        pr.set(q, qc);
        k.addView(pr, Ui.top(c, 8));
        if (!fc.quality.conflicts.isEmpty()) {
            LinearLayout cb = Ui.col(c);
            cb.setBackground(Ui.round(Ui.alpha(Ui.RED, 0x1A), Ui.dp(c, 12), Ui.alpha(Ui.RED, 0x55), 1));
            int pad = Ui.dp(c, 10);
            cb.setPadding(pad, pad, pad, pad);
            cb.addView(Ui.text(c, "⚠  Sources disagree", 12.5f, Ui.RED, true));
            for (String s : fc.quality.conflicts) cb.addView(Ui.text(c, "• " + s, 12, Ui.TEXT, false), Ui.top(c, 3));
            k.addView(cb, Ui.top(c, 12));
        }
        k.addView(Ui.text(c, "MODEL INPUTS AVAILABLE", 10.5f, Ui.DIM, true), Ui.top(c, 14));
        for (Map.Entry<String, Double> e : fc.quality.groups.entrySet()) k.addView(bar(c, e.getKey(), e.getValue()), Ui.top(c, 6));
        if (!fc.quality.sources.isEmpty()) {
            k.addView(Ui.text(c, "LIVE SOURCES (FRESHNESS)", 10.5f, Ui.DIM, true), Ui.top(c, 14));
            for (Map.Entry<String, Double> e : fc.quality.sources.entrySet()) k.addView(bar(c, e.getKey(), e.getValue()), Ui.top(c, 6));
        }
        for (String s : fc.quality.issues) k.addView(Ui.text(c, "• " + s, 11.5f, Ui.AMBER, false), Ui.top(c, 4));
        return k;
    }

    static View record(Context c, IntelEngine.Forecast fc, File dir) {
        LinearLayout k = Ui.card(c);
        k.addView(Ui.title(c, "Track record", "tested on unseen sessions"));
        for (IntelEngine.HPred p : fc.preds) {
            if (p.info == null || !p.info.tested()) continue;
            LinearLayout r = Ui.row(c);
            r.setPadding(0, Ui.dp(c, 5), 0, Ui.dp(c, 5));
            TextView l = Ui.text(c, p.hz.label, 12.5f, Ui.TEXT, true);
            l.setMinWidth(Ui.dp(c, 64));
            r.addView(l, Ui.wrap());
            r.addView(Ui.text(c, String.format(Locale.US, "right %.0f%% vs %.0f%%", p.info.hit * 100, p.info.baseHit * 100), 12, Ui.DIM, false), Ui.weight(1));
            r.addView(Ui.chip(c, p.info.proven ? "EDGE" : "no edge", p.info.proven ? Ui.GREEN : Ui.GREY), Ui.wrap());
            k.addView(r);
        }
        IntelEngine.HPred ref = fc.signal >= 0 ? fc.preds.get(fc.signal) : find(fc, "1h");
        if (ref != null && ref.info != null && ref.info.reliability.length > 0) {
            k.addView(Ui.text(c, "CALIBRATION (" + ref.hz.label + "): SAID → HAPPENED", 10.5f, Ui.DIM, true), Ui.top(c, 14));
            for (double[] b : ref.info.reliability) {
                LinearLayout r = Ui.row(c);
                r.setPadding(0, Ui.dp(c, 3), 0, Ui.dp(c, 3));
                TextView l = Ui.text(c, String.format(Locale.US, "%.0f%% → %.0f%%", b[1] * 100, b[2] * 100), 11.5f, Ui.TEXT, false);
                l.setMinWidth(Ui.dp(c, 96));
                r.addView(l, Ui.wrap());
                Fx.Progress pr = new Fx.Progress(c);
                pr.set(b[2], Math.abs(b[2] - b[1]) < 0.05 ? Ui.GREEN : Ui.AMBER);
                r.addView(pr, Ui.weight(1));
                r.addView(Ui.text(c, String.format(Locale.US, "  %.0f", b[0]), 10.5f, Ui.DIM, false), Ui.wrap());
                k.addView(r);
            }
        }
        if (ref != null && ref.info != null && !ref.info.byRegime.isEmpty()) {
            k.addView(Ui.text(c, "BY REGIME (" + ref.hz.label + ")", 10.5f, Ui.DIM, true), Ui.top(c, 14));
            for (Map.Entry<String, double[]> e : ref.info.byRegime.entrySet()) {
                double[] v = e.getValue();
                k.addView(Ui.kv(c, e.getKey() + String.format(Locale.US, "  (%.0f)", v[0]), String.format(Locale.US, "%.0f%% vs %.0f%%", v[1] * 100, v[2] * 100),
                        v[1] > v[2] ? Ui.GREEN : Ui.DIM));
            }
        }
        k.addView(Ui.text(c, "LIVE RECORD ON THIS PHONE", 10.5f, Ui.DIM, true), Ui.top(c, 14));
        if (fc.scorecard.isEmpty()) k.addView(Ui.text(c, "Nothing scored yet — forecasts are logged every 30 minutes while the market is open.", 12, Ui.DIM, false), Ui.top(c, 4));
        for (Map.Entry<String, double[]> e : fc.scorecard.entrySet()) {
            double[] v = e.getValue();
            k.addView(Ui.kv(c, e.getKey() + String.format(Locale.US, "  (%.0f)", v[0]), String.format(Locale.US, "right %.0f%% · Brier %.3f vs %.3f", v[1] * 100, v[2], v[3]),
                    v[2] < v[3] ? Ui.GREEN : Ui.DIM));
        }
        k.addView(Ui.text(c, "LIVE OVERLAYS", 10.5f, Ui.DIM, true), Ui.top(c, 14));
        for (Map.Entry<String, String> e : IntelRunner.overlayStatus(dir).entrySet())
            k.addView(Ui.text(c, e.getKey().toLowerCase(Locale.US) + ": " + e.getValue(), 11.5f, Ui.DIM, false), Ui.top(c, 3));
        return k;
    }

    /** Learnt weights as a heat map: groups × horizons. */
    static View matrix(Context c, List<HorizonModel> models) {
        LinearLayout k = Ui.card(c);
        k.addView(Ui.title(c, "What each horizon listens to", "learnt weight %"));
        LinearLayout hd = Ui.row(c);
        hd.addView(Ui.text(c, "", 10, Ui.DIM, false), Ui.weight(2.2f));
        for (HorizonModel m : models) { TextView t = Ui.text(c, m.id, 10.5f, Ui.DIM, true); t.setGravity(Gravity.CENTER); hd.addView(t, Ui.weight(1)); }
        k.addView(hd);
        for (int g = 0; g < HorizonModel.G; g++) {
            LinearLayout r = Ui.row(c);
            r.setPadding(0, Ui.dp(c, 2), 0, Ui.dp(c, 2));
            r.addView(Ui.text(c, FeatureEngine.GROUP_NAMES[g], 11, Ui.TEXT, false), Ui.weight(2.2f));
            for (HorizonModel m : models) {
                double v = m.info.importance[g];
                TextView t = Ui.text(c, String.format(Locale.US, "%.0f", v), 10.5f, v >= 15 ? Ui.TEXT : Ui.DIM, v >= 15);
                t.setGravity(Gravity.CENTER);
                t.setPadding(0, Ui.dp(c, 4), 0, Ui.dp(c, 4));
                t.setBackground(Ui.round(Ui.alpha(Ui.CYAN, (int) Math.min(200, 12 + v * 5)), Ui.dp(c, 6), 0, 0));
                LinearLayout.LayoutParams lp = Ui.weight(1);
                lp.leftMargin = Ui.dp(c, 2);
                r.addView(t, lp);
            }
            k.addView(r);
        }
        k.addView(Ui.text(c, "Learnt from history by each horizon's meta model, not set by hand. Brighter = listened to more.", 10.5f, Ui.DIM, false), Ui.top(c, 8));
        return k;
    }

    static View howTo(Context c) {
        LinearLayout info = Ui.card(c);
        info.addView(Ui.title(c, "How to read this", null));
        String[][] tips = {
                {"🎯", "Probabilities, not promises", "UP 62% means: when things looked like this before, Nifty was higher about 62 times in 100."},
                {"✅", "Proven edge", "Each horizon is tested on recent sessions it never saw. Grey = no proven edge, treat as noise."},
                {"🔵", "Confidence ≠ direction", "Dots show confidence: strength, proven edge, agreement, data quality and event risk."},
                {"📊", "Up · flat · down", "The bar under each horizon. Flat = a move too small to matter."},
                {"⏸", "Trade gate", "\"No trade\" unless the forecast clears your threshold (Settings) and every check. Signals only — the app never trades."}};
        for (String[] t : tips) {
            LinearLayout r = Ui.row(c);
            r.setGravity(Gravity.TOP);
            r.setPadding(0, Ui.dp(c, 8), 0, 0);
            r.addView(Ui.text(c, t[0], 16, Ui.TEXT, false), Ui.wrap());
            LinearLayout tc = Ui.col(c);
            tc.setPadding(Ui.dp(c, 10), 0, 0, 0);
            tc.addView(Ui.text(c, t[1], 13, Ui.TEXT, true));
            tc.addView(Ui.text(c, t[2], 12, Ui.DIM, false));
            r.addView(tc, Ui.weight(1));
            info.addView(r);
        }
        return info;
    }

    // ================================================================== validate

    static void validateSection(Context c, LinearLayout col, File dir, String working, boolean loggedIn, Actions act) {
        com.krish.niftydirection.intel.Validator.Report r = IntelRunner.validation(dir);
        int vc = r == null ? Ui.GREY : verdictColor(r.verdict);
        LinearLayout k = Ui.hero(c, vc);
        k.addView(Ui.text(c, "PRE-LIVE VALIDATION", 11, Ui.DIM, true));
        if (r == null) {
            k.addView(Ui.text(c, "Not validated yet", 24, Ui.TEXT, true), Ui.top(c, 4));
            k.addView(Ui.text(c, "The app replays the last ~12 months through the same engine, scores every forecast and simulates the trades. "
                    + "It runs by itself after training. Until then no forecast is marked \"strong enough to act on\".", 12.5f, Ui.DIM, false), Ui.top(c, 6));
        } else {
            LinearLayout r1 = Ui.row(c);
            r1.addView(Ui.text(c, r.verdict, 30, vc, true), Ui.wrap());
            r1.addView(Ui.text(c, "   " + r.sessions + " sessions · " + r.from + " → " + r.to, 11.5f, Ui.DIM, false), Ui.weight(1));
            k.addView(r1, Ui.top(c, 2));
            k.addView(Ui.text(c, r.summary, 13.5f, Ui.TEXT, false), Ui.top(c, 6));
            k.addView(Ui.text(c, "Run " + Pages.time(r.at).substring(0, 5) + " · " + r.blocks + " walk-forward blocks, each predicted by models frozen before it",
                    11, Ui.DIM, false), Ui.top(c, 6));
        }
        Button run = Ui.button(c, working != null && working.startsWith("Validating") ? working : r == null ? "Run validation now" : "Run again", Ui.CYAN);
        run.setEnabled(working == null && loggedIn);
        run.setAlpha(run.isEnabled() ? 1f : 0.45f);
        run.setOnClickListener(v -> act.validate());
        Button ex = Ui.primary(c, "⬇  Export all data");
        ex.setEnabled(working == null);
        ex.setAlpha(ex.isEnabled() ? 1f : 0.45f);
        ex.setOnClickListener(v -> act.export());
        LinearLayout br = Ui.row(c);
        br.addView(run, Ui.weight(1));
        br.addView(new View(c), new LinearLayout.LayoutParams(Ui.dp(c, 10), 1));
        br.addView(ex, Ui.weight(1));
        k.addView(br, Ui.top(c, 12));
        k.addView(Ui.text(c, "Export = one ZIP of CSV files for Excel / Python: summary, confidence buckets, market conditions, stress days, every replay "
                + "forecast and simulated trade (all 9 horizons), the 74 inputs at every moment, the leakage audit and the live forecast + paper-trade log.",
                11, Ui.DIM, false), Ui.top(c, 8));
        col.addView(k, Ui.cardLp(c));
        col.addView(journal(c, dir, act), Ui.cardLp(c));
        if (r == null) return;

        // per-horizon verdicts
        LinearLayout hz = Ui.card(c);
        hz.addView(Ui.title(c, "Verdict by horizon", "tap for details"));
        boolean first = true;
        for (com.krish.niftydirection.intel.Validator.HReport x : r.horizons) {
            if (!first) hz.addView(thin(c));
            first = false;
            LinearLayout box = Ui.col(c);
            box.setPadding(0, Ui.dp(c, 10), 0, Ui.dp(c, 10));
            LinearLayout row = Ui.row(c);
            LinearLayout left = Ui.col(c);
            left.addView(Ui.text(c, x.label, 14, Ui.TEXT, true));
            left.addView(Ui.text(c, Double.isNaN(x.hit) ? "no forecasts" : String.format(Locale.US, "right %.1f%% vs %.1f%% usual side", x.hit * 100, x.base * 100), 11.5f, Ui.DIM, false));
            row.addView(left, Ui.weight(1));
            LinearLayout right = Ui.col(c);
            right.setGravity(Gravity.END);
            TextView pnl = Ui.text(c, x.trades == 0 ? "no trades" : String.format(Locale.US, "₹%,.0f", x.netRupees), 14, x.trades == 0 ? Ui.DIM : Ui.signColor(x.netRupees), true);
            pnl.setGravity(Gravity.END);
            right.addView(pnl);
            TextView tr = Ui.text(c, x.trades == 0 ? "per lot" : x.trades + " trades · per lot", 10.5f, Ui.DIM, false);
            tr.setGravity(Gravity.END);
            right.addView(tr);
            row.addView(right, Ui.wrap());
            TextView chip = Ui.chip(c, x.verdict, verdictColor(x.verdict));
            chip.setMinWidth(Ui.dp(c, 56));
            row.addView(chip, Ui.gapLeft(c, 10));
            box.addView(row);
            LinearLayout det = valDetail(c, x);
            det.setVisibility(View.GONE);
            box.setOnClickListener(v -> det.setVisibility(det.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
            box.addView(det);
            hz.addView(box);
        }
        col.addView(hz, Ui.cardLp(c));

        // leakage audit
        LinearLayout au = Ui.card(c);
        boolean leak = r.auditFailed > 0 || !r.purgeOk;
        LinearLayout at = Ui.row(c);
        at.addView(Ui.text(c, "Leakage audit", 15, Ui.TEXT, true), Ui.weight(1));
        at.addView(Ui.chip(c, leak ? "FAILED" : "CLEAN", leak ? Ui.RED : Ui.GREEN), Ui.wrap());
        au.addView(at);
        au.addView(Ui.text(c, String.format(Locale.US, "• %d random moments recomputed on a history cut at that moment: %d differed.", r.auditChecked, r.auditFailed), 12, Ui.DIM, false), Ui.top(c, 8));
        au.addView(Ui.text(c, "• Every block's models learnt only from before the block: " + (r.purgeOk ? "yes" : "NO"), 12, Ui.DIM, false), Ui.top(c, 3));
        au.addView(Ui.text(c, "• Only finished 5-minute bars and daily data dated before each day are used (no future prices).", 12, Ui.DIM, false), Ui.top(c, 3));
        for (String s : r.audit) au.addView(Ui.text(c, "⚠ " + s, 12, Ui.RED, false), Ui.top(c, 3));
        col.addView(au, Ui.cardLp(c));

        // live paper record
        Map<String, double[]> paper = IntelRunner.paper(dir);
        LinearLayout pp = Ui.card(c);
        pp.addView(Ui.title(c, "Live paper trading", "same rules, real time"));
        if (paper.isEmpty()) pp.addView(Ui.text(c, "No paper trades closed yet. Every live \"strong enough\" signal is paper-traded automatically and scored when its time is up.", 12, Ui.DIM, false));
        for (Map.Entry<String, double[]> e : paper.entrySet()) {
            double[] v = e.getValue();
            pp.addView(Ui.kv(c, com.krish.niftydirection.intel.Horizon.of(e.getKey()).label + String.format(Locale.US, "  (%.0f trades, %.0f won)", v[0], v[1]),
                    String.format(Locale.US, "₹%,.0f per lot", v[2]), Ui.signColor(v[2])));
        }
        col.addView(pp, Ui.cardLp(c));

        // assumptions
        LinearLayout as = Ui.card(c);
        as.addView(Ui.title(c, "Simulation rules", "change in Settings"));
        as.addView(Ui.kv(c, "Instrument", "Nifty futures, " + r.lot + " per lot", Ui.TEXT));
        as.addView(Ui.kv(c, "Signal", String.format(Locale.US, "trade gate passed (≥ %.0f%%)", r.threshold * 100), Ui.TEXT));
        as.addView(Ui.kv(c, "Entry", "next 5-min bar close (delay)", Ui.TEXT));
        as.addView(Ui.kv(c, "Exit", "horizon end" + (r.stopMult > 0 ? String.format(Locale.US, " or stop at %.1f× range", r.stopMult) : ""), Ui.TEXT));
        as.addView(Ui.kv(c, "Slippage", String.format(Locale.US, "%.1f pts per side", r.slippagePts), Ui.TEXT));
        as.addView(Ui.kv(c, "Charges", "approx. Zerodha futures: brokerage, STT, exchange, SEBI, stamp, GST", Ui.TEXT));
        as.addView(Ui.text(c, "One position per horizon at a time. Past results do not guarantee future ones — start small and keep the live paper record growing.",
                11, Ui.DIM, false), Ui.top(c, 8));
        col.addView(as, Ui.cardLp(c));
    }

    static LinearLayout valDetail(Context c, com.krish.niftydirection.intel.Validator.HReport x) {
        LinearLayout d = Ui.col(c);
        d.setBackground(Ui.round(0x0DFFFFFF, Ui.dp(c, 14), 0, 0));
        int pad = Ui.dp(c, 12);
        d.setPadding(pad, pad, pad, pad);
        d.setLayoutParams(Ui.top(c, 10));
        for (String s : x.reasons) d.addView(Ui.text(c, "• " + s, 12, Ui.TEXT, false), Ui.top(c, 2));
        LinearLayout st = Ui.row(c);
        st.setPadding(0, Ui.dp(c, 10), 0, 0);
        st.addView(stat(c, "Brier skill", Double.isNaN(x.skill) ? "—" : String.format(Locale.US, "%+.1f%%", x.skill * 100), Ui.signColor(x.skill)), Ui.weight(1));
        st.addView(stat(c, "90% band", Double.isNaN(x.skillLo) ? "—" : String.format(Locale.US, "%+.1f…%+.1f", x.skillLo * 100, x.skillHi * 100), Ui.TEXT), Ui.weight(1.3f));
        st.addView(stat(c, "Up/flat/down", Double.isNaN(x.acc3) ? "—" : String.format(Locale.US, "%.0f%% vs %.0f%%", x.acc3 * 100, x.base3 * 100), Ui.TEXT), Ui.weight(1.2f));
        d.addView(st);
        if (x.trades > 0) {
            LinearLayout s2 = Ui.row(c);
            s2.setPadding(0, Ui.dp(c, 8), 0, 0);
            s2.addView(stat(c, "Win rate", String.format(Locale.US, "%.0f%%", 100.0 * x.wins / x.trades), Ui.TEXT), Ui.weight(1));
            s2.addView(stat(c, "Profit factor", Double.isNaN(x.profitFactor) ? "—" : String.format(Locale.US, "%.2f", x.profitFactor), x.profitFactor >= 1.1 ? Ui.GREEN : Ui.AMBER), Ui.weight(1));
            s2.addView(stat(c, "Max drawdown", String.format(Locale.US, "₹%,.0f", x.maxDD), Ui.RED), Ui.weight(1.2f));
            d.addView(s2);
            d.addView(Ui.text(c, String.format(Locale.US, "Avg ₹%,.0f per trade · %d stopped out · worst losing streak %d · charges ₹%,.0f · %+.0f Nifty points",
                    x.avgRupees, x.stops, x.worstStreak, x.chargesRupees, x.netPts), 11, Ui.DIM, false), Ui.top(c, 6));
        }
        if (!x.buckets.isEmpty()) {
            d.addView(Ui.text(c, "CONFIDENCE CHECK: SAID → WAS RIGHT", 10.5f, Ui.DIM, true), Ui.top(c, 12));
            for (double[] b : x.buckets) {
                LinearLayout r = Ui.row(c);
                r.setPadding(0, Ui.dp(c, 3), 0, Ui.dp(c, 3));
                TextView l = Ui.text(c, String.format(Locale.US, "%.0f–%.0f%%: %.0f → %.0f%%", b[0] * 100, b[0] * 100 + 10, b[2] * 100, b[3] * 100), 11.5f, Ui.TEXT, false);
                l.setMinWidth(Ui.dp(c, 150));
                r.addView(l, Ui.wrap());
                Fx.Progress pr = new Fx.Progress(c);
                pr.set(b[3], Math.abs(b[3] - b[2]) <= 0.08 ? Ui.GREEN : Ui.AMBER);
                r.addView(pr, Ui.weight(1));
                r.addView(Ui.text(c, String.format(Locale.US, "  %.0f", b[1]), 10.5f, Ui.DIM, false), Ui.wrap());
                d.addView(r);
            }
        }
        table(c, d, "BY MARKET CONDITION", x.regimes);
        table(c, d, "STRESS DAYS", x.stress);
        return d;
    }

    static void table(Context c, LinearLayout d, String title, Map<String, double[]> m) {
        if (m.isEmpty()) return;
        d.addView(Ui.text(c, title + " — right vs usual · trades ₹", 10.5f, Ui.DIM, true), Ui.top(c, 12));
        for (Map.Entry<String, double[]> e : m.entrySet()) {
            double[] v = e.getValue();
            String val = String.format(Locale.US, "%.0f%% vs %.0f%%", v[1] * 100, v[2] * 100) + (v[3] > 0 ? String.format(Locale.US, " · %.0f · ₹%,.0f", v[3], v[4]) : "");
            d.addView(Ui.kv(c, e.getKey() + String.format(Locale.US, " (%.0f)", v[0]), val, v[1] > v[2] ? (v[3] > 0 && v[4] < 0 ? Ui.AMBER : Ui.GREEN) : Ui.DIM));
        }
    }

    // ================================================================== journal

    static Actions lastActions;

    /** "I took this": your real fill (prefilled with the current price) and lots. */
    static void tookDialog(Context c, IntelEngine.HPred p, IntelEngine.Forecast fc) {
        int dir = "BEARISH".equals(p.direction) ? -1 : 1;
        LinearLayout box = Ui.col(c);
        int pad = Ui.dp(c, 18);
        box.setPadding(pad, Ui.dp(c, 8), pad, 0);
        box.addView(Ui.text(c, (dir > 0 ? "LONG" : "SHORT") + " Nifty futures · " + p.hz.label + String.format(Locale.US, " · %.0f%%", p.sideProb() * 100), 13, Ui.DIM, false));
        android.widget.EditText price = field(c, "Your fill price", String.format(Locale.US, "%.2f", fc.price), true);
        android.widget.EditText lots = field(c, "Lots", "1", false);
        box.addView(price, Ui.top(c, 10));
        box.addView(lots, Ui.top(c, 8));
        new android.app.AlertDialog.Builder(c).setTitle("I took this trade").setView(box)
                .setPositiveButton("Save", (dg, w) -> {
                    try {
                        com.krish.niftydirection.intel.Validator.Config cfg = new com.krish.niftydirection.data.Prefs(c).simConfig();
                        IntelRunner.takeTrade(((android.app.Activity) c).getFilesDir(), p.hz.id, dir, Double.parseDouble(price.getText().toString().trim()),
                                Integer.parseInt(lots.getText().toString().trim()), cfg.lot, p.sideProb(), fc.price);
                        android.widget.Toast.makeText(c, "Saved in My trades (AI → Validate)", android.widget.Toast.LENGTH_SHORT).show();
                        if (lastActions != null) lastActions.redraw();
                    } catch (Exception e) { android.widget.Toast.makeText(c, "Not saved: " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show(); }
                })
                .setNegativeButton("Cancel", null).show();
    }

    static android.widget.EditText field(Context c, String hint, String value, boolean decimal) {
        android.widget.EditText e = new android.widget.EditText(c);
        e.setHint(hint);
        e.setText(value);
        e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | (decimal ? android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL : 0));
        e.setSelectAllOnFocus(true);
        return e;
    }

    static View journal(Context c, File dir, Actions act) {
        lastActions = act;
        List<com.krish.niftydirection.intel.Journal.Entry> l = IntelRunner.journal(dir);
        String today = com.krish.niftydirection.data.Collector.day(new java.util.Date());
        double[] t = com.krish.niftydirection.intel.Journal.summary(l, today), all = com.krish.niftydirection.intel.Journal.summary(l, null);
        LinearLayout k = Ui.card(c);
        k.addView(Ui.title(c, "My trades (journal)", "your real trades"));
        LinearLayout st = Ui.row(c);
        st.addView(stat(c, "Today", String.format(Locale.US, "₹%,.0f", t[2]), Ui.signColor(t[2])), Ui.weight(1));
        st.addView(stat(c, "Trades today", String.format(Locale.US, "%.0f", t[0]), Ui.TEXT), Ui.weight(1));
        st.addView(stat(c, "All time", String.format(Locale.US, "₹%,.0f (%.0f)", all[2], all[0]), Ui.signColor(all[2])), Ui.weight(1.3f));
        k.addView(st);
        IntelEngine.Forecast fc = IntelRunner.last;
        if (fc != null && !fc.guard.isEmpty()) k.addView(banner(c, "🛡  Risk guard: " + String.join(" · ", fc.guard), Ui.RED), Ui.top(c, 10));
        if (l.isEmpty()) k.addView(Ui.text(c, "Tap \"I took this trade\" on a signal to record your real fill. Close it here with your exit price. "
                + "The risk guard (Settings) counts these trades.", 12, Ui.DIM, false), Ui.top(c, 10));
        java.text.SimpleDateFormat tf = new java.text.SimpleDateFormat("dd MMM HH:mm", Locale.US);
        tf.setTimeZone(com.krish.niftydirection.data.Collector.IST);
        for (int i = l.size() - 1; i >= Math.max(0, l.size() - 15); i--) {
            com.krish.niftydirection.intel.Journal.Entry e = l.get(i);
            k.addView(thin(c), Ui.top(c, 8));
            LinearLayout r = Ui.row(c);
            r.setPadding(0, Ui.dp(c, 8), 0, 0);
            LinearLayout left = Ui.col(c);
            left.addView(Ui.text(c, (e.dir > 0 ? "LONG " : "SHORT ") + e.lots + " lot · " + com.krish.niftydirection.intel.Horizon.of(e.horizon).label, 13, Ui.TEXT, true));
            left.addView(Ui.text(c, tf.format(new java.util.Date(e.entryAt)) + String.format(Locale.US, " @ %,.1f", e.entry)
                    + (e.open() ? "  · open" : String.format(Locale.US, " → %,.1f (%+.1f pts)", e.exit, e.points())), 11.5f, Ui.DIM, false));
            r.addView(left, Ui.weight(1));
            if (e.open()) {
                Button cl = Ui.button(c, "Close", Ui.CYAN);
                cl.setTextSize(12);
                cl.setOnClickListener(v -> {
                    android.widget.EditText px = field(c, "Exit price", fc != null ? String.format(Locale.US, "%.2f", fc.price) : "", true);
                    LinearLayout box = Ui.col(c);
                    box.setPadding(Ui.dp(c, 18), Ui.dp(c, 8), Ui.dp(c, 18), 0);
                    box.addView(px);
                    new android.app.AlertDialog.Builder(c).setTitle("Close trade").setView(box).setPositiveButton("Save", (dg, w) -> {
                        try { IntelRunner.closeTrade(dir, e.id, Double.parseDouble(px.getText().toString().trim())); act.redraw(); }
                        catch (Exception ex) { android.widget.Toast.makeText(c, "Not saved: " + ex.getMessage(), android.widget.Toast.LENGTH_LONG).show(); }
                    }).setNeutralButton("Delete", (dg, w) -> { try { IntelRunner.deleteTrade(dir, e.id); act.redraw(); } catch (Exception ignored) { } })
                            .setNegativeButton("Cancel", null).show();
                });
                r.addView(cl, Ui.wrap());
            } else r.addView(Ui.text(c, String.format(Locale.US, "₹%,.0f", e.pnl()), 14, Ui.signColor(e.pnl()), true), Ui.wrap());
            k.addView(r);
        }
        return k;
    }

    // ================================================================== helpers

    static View banner(Context c, String s, int color) {
        TextView t = Ui.text(c, s, 12.5f, color, true);
        t.setBackground(Ui.round(Ui.alpha(color, 0x1F), Ui.dp(c, 12), Ui.alpha(color, 0x55), 1));
        int p = Ui.dp(c, 10);
        t.setPadding(p + Ui.dp(c, 2), p, p, p);
        return t;
    }

    static View stat(Context c, String label, String value, int color) {
        LinearLayout k = Ui.col(c);
        k.addView(Ui.text(c, label, 10.5f, Ui.DIM, false));
        k.addView(Ui.text(c, value, 14, color, true));
        return k;
    }

    static View pushRow(Context c, String text, double pts, double max) { return pushRow(c, text, pts, max, "%+.1f pts"); }

    static View pushRow(Context c, String text, double pts, double max, String fmt) {
        LinearLayout k = Ui.col(c);
        LinearLayout r = Ui.row(c);
        r.addView(Ui.text(c, text, 12.5f, Ui.TEXT, false), Ui.weight(1));
        r.addView(Ui.text(c, String.format(Locale.US, fmt, pts), 12, Ui.signColor(pts), true), Ui.gapLeft(c, 8));
        k.addView(r);
        Fx.Push b = new Fx.Push(c);
        b.set(pts, max);
        k.addView(b, Ui.top(c, 4));
        return k;
    }

    static View bar(Context c, String label, double v) {
        LinearLayout k = Ui.col(c);
        LinearLayout r = Ui.row(c);
        r.addView(Ui.text(c, label, 12, Ui.TEXT, false), Ui.weight(1));
        int col = v >= 0.8 ? Ui.GREEN : v >= 0.5 ? Ui.AMBER : Ui.RED;
        r.addView(Ui.text(c, String.format(Locale.US, "%.0f%%", v * 100), 11.5f, col, true), Ui.wrap());
        k.addView(r);
        Fx.Progress p = new Fx.Progress(c);
        p.set(v, col);
        k.addView(p, Ui.top(c, 3));
        return k;
    }

    static View collapsible(Context c, String title, TextView body) {
        LinearLayout d = Ui.card(c);
        LinearLayout h = Ui.row(c);
        h.addView(Ui.text(c, title, 15, Ui.TEXT, true), Ui.weight(1));
        TextView toggle = Ui.text(c, "Show ▾", 13, Ui.CYAN, true);
        h.addView(toggle, Ui.wrap());
        d.addView(h);
        body.setVisibility(View.GONE);
        h.setOnClickListener(v -> {
            boolean show = body.getVisibility() != View.VISIBLE;
            body.setVisibility(show ? View.VISIBLE : View.GONE);
            toggle.setText(show ? "Hide ▴" : "Show ▾");
        });
        d.addView(body, Ui.top(c, 8));
        return d;
    }

    static View thin(Context c) {
        View v = new View(c);
        v.setBackgroundColor(0x12FFFFFF);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, 1));
        return v;
    }

    static IntelEngine.HPred find(IntelEngine.Forecast fc, String id) { for (IntelEngine.HPred p : fc.preds) if (p.hz.id.equals(id) && p.has()) return p; return null; }
    static String shortReason(String s) { int i = s.indexOf(" — "); return (i > 0 ? s.substring(0, i) : s).toLowerCase(Locale.US); }
    static boolean anyChanged(IntelEngine.Forecast fc) { for (IntelEngine.HPred p : fc.preds) if (!p.changed.isEmpty()) return true; return false; }
    static double sign(String s) { return s.contains(" +") ? 1 : s.contains(" -") || s.contains(" −") ? -1 : 0; }
    static String arrow(String dir) { return "BULLISH".equals(dir) ? "↑" : "BEARISH".equals(dir) ? "↓" : "→"; }
    static int dirColor(String dir) { return "BULLISH".equals(dir) ? Ui.GREEN : "BEARISH".equals(dir) ? Ui.RED : Ui.GREY; }
    static int confColor(String l) { return "High".equals(l) ? Ui.GREEN : "Medium".equals(l) ? Ui.AMBER : Ui.GREY; }
    static int verdictColor(String v) { return "PASS".equals(v) ? Ui.GREEN : "WARN".equals(v) ? Ui.AMBER : Ui.RED; }
    static int qualityColor(String q) { return "HIGH".equals(q) ? Ui.GREEN : "MEDIUM".equals(q) ? Ui.AMBER : Ui.GREY; }
    static int regimeColor(String l) {
        if (l.contains("BULL")) return Ui.GREEN;
        if (l.contains("BEAR") || l.contains("SHOCK") || l.contains("PANIC")) return Ui.RED;
        if (l.contains("EVENT") || l.contains("NEWS")) return Ui.PURPLE;
        return Ui.AMBER;
    }
    static String signed(double v) { return Double.isNaN(v) ? "—" : String.format(Locale.US, "%+.2f%%", v); }
}

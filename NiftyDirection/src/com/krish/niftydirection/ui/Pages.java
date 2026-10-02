package com.krish.niftydirection.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.krish.niftydirection.data.Brain;
import com.krish.niftydirection.data.Collector;
import com.krish.niftydirection.data.Store;
import com.krish.niftydirection.engine.Chain;
import com.krish.niftydirection.engine.Engine;
import com.krish.niftydirection.engine.Factor;
import com.krish.niftydirection.engine.Result;
import com.krish.niftydirection.model.EventItem;
import com.krish.niftydirection.model.NewsItem;
import com.krish.niftydirection.model.OptionRow;
import com.krish.niftydirection.model.Quote;
import com.krish.niftydirection.model.Snapshot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The six pages. Each builder returns a fresh column of cards from the latest output. */
final class Pages {
    private Pages() {}

    static String pct(double p) { return Engine.pctS(p); }
    static String n0(double v) { return Engine.n0(v); }
    static String n2(double v) { return Double.isNaN(v) ? "—" : String.format(Locale.US, "%,.2f", v); }
    static String sg(double v) { return Engine.sg0(v); }
    static double nz(double v) { return Double.isNaN(v) ? 0 : v; }

    static LinearLayout empty(Context c, String msg) {
        LinearLayout col = Ui.col(c);
        LinearLayout card = Ui.card(c);
        card.addView(Ui.text(c, msg, 14, Ui.DIM, false));
        col.addView(card, Ui.cardLp(c));
        return col;
    }

    // ================================================================== TODAY

    static LinearLayout today(Context c, Brain.Output o, Store store) {
        LinearLayout col = Ui.col(c);
        Result r = o.result; Snapshot s = o.snap;
        int rc = Ui.regimeColor(r.regime);

        LinearLayout hero = Ui.card(c);
        Views.Gauge g = new Views.Gauge(c);
        g.set(r.directionScore, r.label(), "Nifty Direction Score", rc);
        hero.addView(g, Ui.matchW());
        TextView reg = Ui.text(c, r.label(), 25, rc, true);
        reg.setGravity(Gravity.CENTER);
        hero.addView(reg, Ui.top(c, 2));
        if (!r.transition.isEmpty()) {
            TextView tr = Ui.text(c, "↪ " + r.transition, 13, Ui.CYAN, true);
            tr.setGravity(Gravity.CENTER);
            hero.addView(tr, Ui.top(c, 2));
        }
        LinearLayout cr = Ui.row(c);
        cr.addView(Ui.text(c, "Confidence", 13, Ui.DIM, false), Ui.wrap());
        cr.addView(Ui.text(c, "  " + r.confidence + "/100", 13, Ui.TEXT, true), Ui.wrap());
        cr.addView(new View(c), Ui.weight(1));
        cr.addView(Ui.text(c, "data " + Math.round(r.coverage * 100) + "% · agree " + Math.round(r.agreement * 100) + "%", 11, Ui.DIM, false), Ui.wrap());
        hero.addView(cr, Ui.top(c, 10));
        Views.Meter m = new Views.Meter(c);
        m.set(r.confidence, r.confidence >= 65 ? rc : r.confidence >= 35 ? Ui.AMBER : Ui.GREY);
        hero.addView(m, Ui.top(c, 6));
        LinearLayout pills = Ui.row(c);
        if (r.degraded) addPill(c, pills, "DATA DEGRADED", Ui.RED);
        addPill(c, pills, "Event risk " + r.eventRisk, r.eventRisk.equals("HIGH") ? Ui.RED : r.eventRisk.equals("MEDIUM") ? Ui.AMBER : Ui.GREY);
        if (!r.dayType.equals("NORMAL DAY")) addPill(c, pills, r.dayType, Ui.PURPLE);
        if (r.disagreement) addPill(c, pills, "High disagreement", Ui.AMBER);
        if (!r.volRegime.isEmpty()) addPill(c, pills, r.volRegime, Ui.CYAN);
        if (!r.gapClass.isEmpty()) addPill(c, pills, r.gapClass + (r.gapBehavior.isEmpty() ? "" : " · " + r.gapBehavior), Ui.DIM);
        HorizontalScrollView ps = new HorizontalScrollView(c);
        ps.setHorizontalScrollBarEnabled(false);
        ps.addView(pills);
        hero.addView(ps, Ui.top(c, 10));
        hero.addView(Ui.divider(c));
        hero.addView(Ui.text(c, r.stageName, 13, Ui.CYAN, true));
        hero.addView(Ui.text(c, r.stageTip, 12, Ui.DIM, false), Ui.top(c, 2));
        col.addView(hero, Ui.cardLp(c));
        TextView warn = Ui.text(c, "⚠  Not a tested trading signal. This score is a summary of today's evidence. In a 6-month replay "
                + "(Apr–Sep 2026) its calls were right about half the time and lost money after costs. For tested signals use the AI tab — "
                + "only horizons that PASS validation are marked \"strong enough to act on\".", 12, Ui.AMBER, false);
        warn.setBackground(Ui.round(Ui.alpha(Ui.AMBER, 0x1A), Ui.dp(c, 14), Ui.alpha(Ui.AMBER, 0x55), 1));
        int wp = Ui.dp(c, 12);
        warn.setPadding(wp, wp, wp, wp);
        col.addView(warn, Ui.cardLp(c));

        // right now
        LinearLayout now = Ui.card(c);
        now.addView(Ui.header(c, "Right now"));
        now.addView(Ui.kv(c, "Current state", r.state, Ui.TEXT));
        TextView act = Ui.text(c, r.action, 15, Ui.TEXT, true);
        act.setBackground(Ui.round((rc & 0x00FFFFFF) | 0x1F000000, Ui.dp(c, 10), rc, 1));
        act.setPadding(Ui.dp(c, 12), Ui.dp(c, 10), Ui.dp(c, 12), Ui.dp(c, 10));
        now.addView(act, Ui.top(c, 8));
        now.addView(Ui.divider(c));
        now.addView(Ui.kv(c, "Nifty", s.nifty != null && s.nifty.ok() ? n2(s.nifty.last) + "  " + pct(s.nifty.pct()) : "—", s.nifty != null ? Ui.signColor(s.nifty.pct()) : Ui.DIM));
        now.addView(Ui.kv(c, "Support", Double.isNaN(r.support) ? "—" : n0(r.support) + "  (" + r.supportWhy + ")", Ui.GREEN));
        now.addView(Ui.kv(c, "Resistance", Double.isNaN(r.resistance) ? "—" : n0(r.resistance) + "  (" + r.resistanceWhy + ")", Ui.RED));
        if (!r.priceLine.isEmpty()) now.addView(Ui.kv(c, "Price", r.priceLine, Ui.TEXT));
        if (!r.flowLine.isEmpty()) now.addView(Ui.kv(c, "Option flow (15 min)", r.flowLine, Ui.TEXT));
        if (!r.futFlowLine.isEmpty()) now.addView(Ui.kv(c, "Futures flow (30 min)", r.futFlowLine, Ui.TEXT));
        if (!r.momentumState.isEmpty()) now.addView(Ui.kv(c, "Momentum", r.momentumState, Ui.TEXT));
        if (!r.optionsLine.isEmpty()) now.addView(Ui.kv(c, "Options (since yesterday)", r.optionsLine.replaceFirst("^ · ", ""), Ui.TEXT));
        if (!r.breadthLine.isEmpty()) now.addView(Ui.kv(c, "Breadth (up : down)", r.breadthLine, Ui.TEXT));
        if (!r.bankLine.isEmpty()) now.addView(Ui.kv(c, "Bank Nifty", r.bankLine, Ui.TEXT));
        if (!Double.isNaN(r.expectedMove)) now.addView(Ui.kv(c, "VIX expects today", "±" + n0(r.expectedMove) + " pts", Ui.TEXT));
        if (!Double.isNaN(r.atmIv)) now.addView(Ui.kv(c, "ATM IV", Engine.f1(r.atmIv) + "%" + (Double.isNaN(r.atmIvChange) ? "" : String.format(Locale.US, " (%+.2f today)", r.atmIvChange)), Ui.TEXT));
        if (!Double.isNaN(r.maxPain)) now.addView(Ui.kv(c, "Max pain (" + s.expiry + ")", n0(r.maxPain) + (r.maxPainMagnet ? " · expiry magnet active" : " · location only"), r.maxPainMagnet ? Ui.PURPLE : Ui.DIM));
        if (!Double.isNaN(r.globalRisk) || !Double.isNaN(r.indiaMacro))
            now.addView(Ui.kv(c, "World / India macro", sg(100 * nz(r.globalRisk)) + " / " + sg(100 * nz(r.indiaMacro)), Ui.TEXT));
        if (!Double.isNaN(r.attrCoverage)) now.addView(Ui.kv(c, "Sector attribution", Math.round(r.attrCoverage) + "% of Nifty's move explained",
                r.attrCoverage < 70 || r.attrCoverage > 130 ? Ui.AMBER : Ui.TEXT));
        col.addView(now, Ui.cardLp(c));

        // conflict
        if (r.regime.equals(Result.CONFLICT)) {
            LinearLayout w = Ui.card(c);
            w.setBackground(Ui.round(0x1FBC8CFF, Ui.dp(c, 14), Ui.PURPLE, 1));
            w.addView(Ui.header(c, "Conflicting market"));
            for (String x : r.conflictLines) w.addView(Ui.text(c, "• " + x, 13, Ui.TEXT, false), Ui.top(c, 2));
            col.addView(w, Ui.cardLp(c));
        }
        if (r.degraded) {
            LinearLayout w = Ui.card(c);
            w.setBackground(Ui.round(0x1FF85149, Ui.dp(c, 14), Ui.RED, 1));
            w.addView(Ui.header(c, "Data degraded"));
            for (String x : r.degradedList) w.addView(Ui.text(c, "• " + x, 13, Ui.TEXT, false), Ui.top(c, 2));
            w.addView(Ui.text(c, "The score is still shown, but confidence is cut until these are back.", 11, Ui.DIM, false), Ui.top(c, 4));
            col.addView(w, Ui.cardLp(c));
        }
        if (r.disagreement) {
            LinearLayout w = Ui.card(c);
            w.setBackground(Ui.round(0x1FD29922, Ui.dp(c, 14), Ui.AMBER, 1));
            w.addView(Ui.header(c, String.format(Locale.US, "High internal disagreement (%.0f%% against)", r.opposingShare * 100)));
            for (String x : r.opposers) w.addView(Ui.text(c, "• " + x, 13, Ui.TEXT, false), Ui.top(c, 2));
            col.addView(w, Ui.cardLp(c));
        }
        if (!r.warnings.isEmpty()) {
            LinearLayout w = Ui.card(c);
            w.setBackground(Ui.round(0x1FD29922, Ui.dp(c, 14), Ui.AMBER, 1));
            w.addView(Ui.header(c, "Be careful"));
            for (String x : r.warnings) w.addView(Ui.text(c, "• " + x, 13, Ui.AMBER, false), Ui.top(c, 2));
            col.addView(w, Ui.cardLp(c));
        }

        // three scores
        LinearLayout split = Ui.card(c);
        split.addView(Ui.header(c, "Three scores"));
        split.addView(splitRow(c, "Structure (positioning)", r.structScore, "counts " + Math.round(r.structWeight * 100) + "% now"));
        split.addView(splitRow(c, "Live (price + flows)", r.liveScore, "counts " + Math.round(r.liveWeight * 100) + "% now"));
        split.addView(splitRow(c, "Events (news tone)", r.eventScore, r.eventAdj == 0 ? "no change to score" : String.format(Locale.US, "adds %+.0f", r.eventAdj)));
        split.addView(Ui.text(c, "Before the open, structure makes the idea. Once the market is open, live evidence counts most. News only nudges (±15 at most).", 11, Ui.DIM, false), Ui.top(c, 6));
        col.addView(split, Ui.cardLp(c));

        // timeline
        LinearLayout tl = Ui.card(c);
        tl.addView(Ui.header(c, "Score through the day"));
        Views.Timeline t = new Views.Timeline(c);
        List<double[]> inHours = new ArrayList<>();
        for (double[] p : store.timeline(s.today)) if (p[0] >= 9 * 60 + 15 && p[0] <= 15 * 60 + 30) inHours.add(p);
        t.set(inHours);
        tl.addView(t, Ui.matchW());
        tl.addView(Ui.text(c, "Green band = bullish zone (above +25), red = bearish (below −25). A side is kept until the score falls back through ±15, so it does not flip on small wiggles.", 11, Ui.DIM, false), Ui.top(c, 4));
        col.addView(tl, Ui.cardLp(c));

        // next events
        if (!s.events.isEmpty()) {
            LinearLayout ev = Ui.card(c);
            ev.addView(Ui.header(c, "Coming up"));
            int k = 0;
            for (EventItem e : s.events) {
                if (k++ >= 4) break;
                ev.addView(Ui.kv(c, e.date.substring(5) + (e.date.equals(s.today) ? " (today)" : ""), e.name, e.importance >= 3 ? Ui.RED : e.importance == 2 ? Ui.AMBER : Ui.DIM));
            }
            col.addView(ev, Ui.cardLp(c));
        }

        LinearLayout tiles = Ui.card(c);
        tiles.addView(Ui.header(c, "Indices"));
        tiles.addView(tileRow(c, "NIFTY 50", s.nifty, "BANK NIFTY", s.bank));
        tiles.addView(tileRow(c, "INDIA VIX", s.vix, s.gift != null ? "GIFT NIFTY" : "NIFTY FUT", s.gift != null ? s.gift : s.fut), Ui.top(c, 8));
        col.addView(tiles, Ui.cardLp(c));

        notes(c, col, r);
        disclaimer(c, col);
        return col;
    }

    static void addPill(Context c, LinearLayout row, String text, int color) {
        if (row.getChildCount() > 0) row.addView(new View(c), new LinearLayout.LayoutParams(Ui.dp(c, 6), 1));
        row.addView(Ui.pill(c, text, color), Ui.wrap());
    }

    static LinearLayout splitRow(Context c, String name, double score, String sub) {
        LinearLayout box = Ui.col(c);
        LinearLayout r = Ui.row(c);
        r.addView(Ui.text(c, name, 13, Ui.DIM, false), Ui.weight(1));
        String v = Double.isNaN(score) ? "no data" : String.format(Locale.US, "%+.0f", score) + (score >= 25 ? " bullish" : score <= -25 ? " bearish" : " neutral");
        r.addView(Ui.text(c, v, 13, Double.isNaN(score) ? Ui.DIM : Ui.signColor(Math.abs(score) < 25 ? 0 : score), true), Ui.wrap());
        box.addView(r);
        Views.Bar b = new Views.Bar(c);
        b.set(Double.isNaN(score) ? 0 : score / 100, !Double.isNaN(score));
        box.addView(b, Ui.top(c, 4));
        if (sub != null) box.addView(Ui.text(c, sub, 10, Ui.GREY, false), Ui.top(c, 2));
        box.setPadding(0, Ui.dp(c, 4), 0, Ui.dp(c, 6));
        return box;
    }

    static LinearLayout tileRow(Context c, String n1, Quote q1, String n2, Quote q2) {
        LinearLayout r = Ui.row(c);
        r.addView(tile(c, n1, q1), Ui.weight(1));
        View gap = new View(c); r.addView(gap, new LinearLayout.LayoutParams(Ui.dp(c, 8), 1));
        r.addView(tile(c, n2, q2), Ui.weight(1));
        return r;
    }

    static LinearLayout tile(Context c, String name, Quote q) {
        LinearLayout t = Ui.col(c);
        t.setBackground(Ui.round(Ui.PANEL, Ui.dp(c, 10), Ui.LINE, 1));
        t.setPadding(Ui.dp(c, 10), Ui.dp(c, 8), Ui.dp(c, 10), Ui.dp(c, 8));
        t.addView(Ui.text(c, name, 11, Ui.DIM, true));
        boolean ok = q != null && q.ok();
        t.addView(Ui.text(c, ok ? n2(q.last) : "—", 17, Ui.TEXT, true));
        t.addView(Ui.text(c, ok ? String.format(Locale.US, "%+.2f  (%s)", q.change(), pct(q.pct())) : "", 12, ok ? Ui.signColor(q.pct()) : Ui.DIM, false));
        return t;
    }

    static void notes(Context c, LinearLayout col, Result r) {
        List<String> ns = new ArrayList<>();
        for (String n : r.notes) ns.add(n.startsWith("KITE_LOGIN: ") ? n.substring(12) : n);
        if (ns.isEmpty()) return;
        LinearLayout card = Ui.card(c);
        card.addView(Ui.header(c, "Notes"));
        for (String n : ns) card.addView(Ui.text(c, "• " + n, 12, Ui.DIM, false), Ui.top(c, 2));
        col.addView(card, Ui.cardLp(c));
    }

    static void disclaimer(Context c, LinearLayout col) {
        TextView d = Ui.text(c, "This is a view of where the evidence points, not a promise. Nifty can do anything. The app never places orders.", 11, Ui.GREY, false);
        d.setGravity(Gravity.CENTER);
        d.setPadding(Ui.dp(c, 10), Ui.dp(c, 4), Ui.dp(c, 10), Ui.dp(c, 16));
        col.addView(d, Ui.matchW());
    }

    // ================================================================== EVIDENCE + DATA QUALITY

    static LinearLayout evidence(Context c, Brain.Output o) {
        LinearLayout col = Ui.col(c);
        Result r = o.result; Snapshot s = o.snap;

        LinearLayout sum = Ui.card(c);
        sum.addView(Ui.header(c, "How the score is built"));
        sum.addView(Ui.text(c, "Each piece of evidence scores from fully bearish (−) to fully bullish (+). Its weight = base weight × source trust × freshness × what the record taught. "
                + "Structure and live evidence make two scores; the time of day decides how much each counts. VIX and news don't vote — they change confidence and event risk.", 12, Ui.DIM, false));
        sum.addView(Ui.kv(c, "Final signed score", String.format(Locale.US, "%+.0f of ±100", r.score), Ui.signColor(r.score)), Ui.top(c, 6));
        sum.addView(Ui.kv(c, "Structure / Live / Events", sg(r.structScore) + " / " + sg(r.liveScore) + " / " + sg(r.eventScore), Ui.TEXT));
        sum.addView(Ui.kv(c, "Evidence with data", Math.round(r.coverage * 100) + "%", Ui.TEXT));
        sum.addView(Ui.kv(c, "Evidence agreeing", Math.round(r.agreement * 100) + "%", Ui.TEXT));
        col.addView(sum, Ui.cardLp(c));

        LinearLayout dq = Ui.card(c);
        dq.addView(Ui.header(c, "Data quality"));
        dq.addView(qualityRow(c, "Source", "Age", "Quality", "Trust", true));
        for (String[] q : r.quality) dq.addView(qualityRow(c, q[0], q[1], q[2], q[3], false));
        dq.addView(Ui.text(c, String.format(Locale.US, "Last update took %.1f s. Old data counts less automatically (e.g. prices older than 10 min, global data older than 30 min).", s.collectMs / 1000.0), 11, Ui.DIM, false), Ui.top(c, 6));
        col.addView(dq, Ui.cardLp(c));

        String[][] groups = {{Factor.STRUCT, "Structure — positioning (" + Math.round(r.structWeight * 100) + "% of the score now)"},
                {Factor.LIVE, "Live — price and flows (" + Math.round(r.liveWeight * 100) + "% of the score now)"},
                {Factor.MODIFIER, "Modifier — changes confidence only"}, {Factor.EVENT, "Events — news tone and scheduled events"}};
        for (String[] gp : groups) {
            TextView h = Ui.header(c, gp[1]);
            h.setPadding(Ui.dp(c, 4), Ui.dp(c, 6), 0, Ui.dp(c, 6));
            col.addView(h);
            for (Factor f : r.factors) {
                if (!f.group.equals(gp[0])) continue;
                LinearLayout card = Ui.card(c);
                LinearLayout top = Ui.row(c);
                top.addView(Ui.text(c, f.name, 15, Ui.TEXT, true), Ui.weight(1));
                top.addView(Ui.pill(c, f.available ? f.reading : "No data", f.available ? Ui.signColor(Math.abs(f.value) < 0.15 ? 0 : f.value) : Ui.GREY), Ui.wrap());
                card.addView(top);
                Views.Bar b = new Views.Bar(c);
                b.set(f.value, f.available);
                card.addView(b, Ui.top(c, 10));
                LinearLayout meta = Ui.row(c);
                meta.addView(Ui.text(c, f.available ? String.format(Locale.US, "%+.2f", f.value) : "—", 12, f.available ? Ui.signColor(f.value) : Ui.DIM, true), Ui.weight(1));
                String w = f.maxWeight > 0 ? String.format(Locale.US, "weight %.1f of %.0f", f.weight, f.maxWeight) : "no vote";
                meta.addView(Ui.text(c, w, 11, Ui.DIM, false), Ui.wrap());
                card.addView(meta, Ui.top(c, 4));
                String src = f.source + " · " + f.quality + String.format(Locale.US, " · trust %.2f", f.reliability)
                        + (f.freshness < 0.99 ? String.format(Locale.US, " · freshness %.2f", f.freshness) : "")
                        + (Math.abs(f.learnt - 1) > 0.005 ? String.format(Locale.US, " · learnt ×%.2f", f.learnt) : "")
                        + (f.maturity < 1 ? String.format(Locale.US, " · short window ×%.2f", f.maturity) : "")
                        + (f.dayMod < 1 ? String.format(Locale.US, " · expiry ×%.2f", f.dayMod) : "")
                        + (Double.isNaN(f.ageMin) ? "" : " · age " + Engine.ageText(f.ageMin) + " (fresh ≤ " + Engine.ageText(f.expectedMin) + ")");
                card.addView(Ui.text(c, src, 10, Ui.GREY, false), Ui.top(c, 2));
                card.addView(Ui.text(c, f.detail, 13, Ui.DIM, false), Ui.top(c, 6));
                col.addView(card, Ui.cardLp(c));
            }
        }
        disclaimer(c, col);
        return col;
    }

    static LinearLayout qualityRow(Context c, String a, String b, String q, String t, boolean head) {
        LinearLayout r = Ui.row(c);
        r.setPadding(0, Ui.dp(c, 3), 0, Ui.dp(c, 3));
        int col = head ? Ui.DIM : Ui.TEXT;
        r.addView(Ui.text(c, a, 12, col, head), Ui.weight(2.2f));
        r.addView(Ui.text(c, b, 12, col, head), Ui.weight(0.8f));
        int qc = head ? Ui.DIM : q.equals("LIVE") ? Ui.GREEN : q.equals("STALE") ? Ui.RED : q.equals("CLOSED") ? Ui.DIM : Ui.AMBER;
        r.addView(Ui.text(c, q, 12, qc, true), Ui.weight(0.9f));
        r.addView(Ui.text(c, t, 12, col, head), Ui.weight(0.9f));
        return r;
    }

    // ================================================================== OPTIONS

    static LinearLayout options(Context c, Brain.Output o) {
        Snapshot s = o.snap; Result r = o.result;
        if (s.chain.isEmpty()) return empty(c, "Option chain not loaded yet. Log in to Kite and refresh.");
        LinearLayout col = Ui.col(c);
        try { OptionsPage.build(c, col, s); }
        catch (Throwable t) { col.addView(empty(c, "Strategy builder could not draw: " + t)); }
        LinearLayout head = Ui.card(c);
        head.addView(Ui.header(c, "Nifty options · " + s.expiry + " expiry (" + s.optDaysToExpiry + " days)"));
        head.addView(Ui.kv(c, "Spot", n2(s.spot()), Ui.TEXT));
        head.addView(Ui.kv(c, "Support wall (most put OI below)", n0(r.peWall), Ui.GREEN));
        head.addView(Ui.kv(c, "Resistance wall (most call OI above)", n0(r.ceWall), Ui.RED));
        head.addView(Ui.kv(c, "PCR", Engine.f2(r.pcr) + (r.pcr > 1.2 ? " (put-heavy, supportive)" : r.pcr < 0.8 ? " (call-heavy, capping)" : " (balanced)"), Ui.TEXT));
        head.addView(Ui.kv(c, "ATM IV", Engine.f1(r.atmIv) + "%" + (Double.isNaN(r.atmIvChange) ? "" : String.format(Locale.US, "  (%+.2f since first snapshot)", r.atmIvChange)), Ui.TEXT));
        head.addView(Ui.kv(c, "Max pain (location only)", n0(r.maxPain), Ui.DIM));
        head.addView(Ui.kv(c, "Live flow (15 min)", r.flowLine.isEmpty() ? "needs 2 snapshots" : r.flowLine, Ui.TEXT));
        if (r.optFlow != null) {
            head.addView(Ui.kv(c, "Flow intensity (per contract traded)", String.format(Locale.US, "%+.3f · avg confidence %.0f%%", r.optFlow.intensity, r.optFlow.avgConfidence * 100), Ui.TEXT));
            if (r.optFlow.newCallWall != null) head.addView(Ui.kv(c, "Call wall building (outer)", n0(r.optFlow.newCallWall.strike) + "  +" + Engine.lots(r.optFlow.newCallWall.dOi), Ui.RED));
            if (r.optFlow.newPutWall != null) head.addView(Ui.kv(c, "Put wall building (outer)", n0(r.optFlow.newPutWall.strike) + "  +" + Engine.lots(r.optFlow.newPutWall.dOi), Ui.GREEN));
        }
        if (!r.dayType.equals("NORMAL DAY")) head.addView(Ui.kv(c, "Day type", r.dayType + " — small OI moves ignored, IV trusted less", Ui.PURPLE));
        head.addView(Ui.text(c, "Two columns per side: 'Today' = change since yesterday's close. 'Live' = what happened in the last ~15 minutes. "
                + "Live labels are PROBABLE, with a confidence: OI ↑ + sellers pressing = writing, OI ↑ + buyers paying up = buying, OI ↓ + buyers paying up = short covering, OI ↓ + sellers = unwinding. "
                + "'Pressing / paying up' is read from each strike's IV change after taking out the whole curve's move, checked against the premium move beyond what spot and time explain. "
                + "Much volume with little OI change (churn) lowers confidence. ATM ±5 strikes give direction; the outer strikes show walls being built. IV is worked out from prices (Kite does not send it).", 11, Ui.DIM, false), Ui.top(c, 6));
        if (!Chain.anyPrev(s.chain)) head.addView(Ui.text(c, "Yesterday's OI is not loaded yet, so 'Today' columns are empty.", 11, Ui.AMBER, false), Ui.top(c, 4));
        col.addView(head, Ui.cardLp(c));

        Map<String, Chain.Activity> live = new HashMap<>();
        if (r.optFlow != null) for (Chain.Activity a : r.optFlow.acts) live.put((a.call ? "C" : "P") + a.strike, a);

        LinearLayout tbl = Ui.card(c);
        tbl.setPadding(Ui.dp(c, 6), Ui.dp(c, 10), Ui.dp(c, 6), Ui.dp(c, 10));
        HorizontalScrollView hs = new HorizontalScrollView(c);
        LinearLayout t = Ui.col(c);
        String[] hd = {"Live", "Today", "ΔOI", "CALL OI", "IV", "STRIKE", "IV", "PUT OI", "ΔOI", "Today", "Live"};
        int[] hc = new int[hd.length]; for (int i = 0; i < hc.length; i++) hc[i] = Ui.DIM;
        t.addView(chainRow(c, true, hd, hc, 0));
        double atm = Math.round(s.spot() / s.strikeStep) * s.strikeStep;
        for (int i = s.chain.size() - 1; i >= 0; i--) {
            OptionRow x = s.chain.get(i);
            Chain.Action ca = x.cePrevOi >= 0 ? Chain.read(x.ceDoi(), x.ceDp(), x.ceOi) : Chain.Action.NONE;
            Chain.Action pa = x.pePrevOi >= 0 ? Chain.read(x.peDoi(), x.peDp(), x.peOi) : Chain.Action.NONE;
            Chain.Activity lc = live.get("C" + x.strike), lp = live.get("P" + x.strike);
            int bg = x.strike == atm ? 0x2658A6FF : x.strike == r.ceWall ? 0x22F85149 : x.strike == r.peWall ? 0x223FB950 : 0;
            String[] v = {lc == null ? "·" : lc.shortLabel(), ca.label, x.cePrevOi >= 0 ? sgnL(x.ceDoi()) : "·", Engine.lots(x.ceOi), Engine.f1(x.ceIv),
                    n0(x.strike) + (x.strike == atm ? " ◆" : ""),
                    Engine.f1(x.peIv), Engine.lots(x.peOi), x.pePrevOi >= 0 ? sgnL(x.peDoi()) : "·", pa.label, lp == null ? "·" : lp.shortLabel()};
            int[] cc = {lc == null ? Ui.GREY : actionColor(lc.action, true), actionColor(ca, true), Ui.TEXT, Ui.TEXT, Ui.DIM, Ui.TEXT,
                    Ui.DIM, Ui.TEXT, Ui.TEXT, actionColor(pa, false), lp == null ? Ui.GREY : actionColor(lp.action, false)};
            t.addView(chainRow(c, false, v, cc, bg));
        }
        hs.addView(t);
        tbl.addView(hs);
        tbl.addView(Ui.text(c, "◆ = at the money. OI in units (L = 1,00,000). Red row = resistance wall, green row = support wall. Colours show what it means for Nifty: green = supportive, red = pressure.", 11, Ui.DIM, false), Ui.top(c, 8));
        col.addView(tbl, Ui.cardLp(c));
        disclaimer(c, col);
        return col;
    }

    static String sgnL(double v) { return (v >= 0 ? "+" : "−") + Engine.lots(Math.abs(v)); }

    static int actionColor(Chain.Action a, boolean call) {
        switch (a) {
            case WRITING: return call ? Ui.RED : Ui.GREEN;
            case SHORT_COVERING: return call ? Ui.GREEN : Ui.RED;
            case BUYING: return call ? 0xFF8FD19E : 0xFFF0A09A;
            case UNWINDING: return call ? 0xFFF0A09A : 0xFF8FD19E;
            default: return Ui.GREY;
        }
    }

    static LinearLayout chainRow(Context c, boolean head, String[] v, int[] colors, int bg) {
        LinearLayout r = Ui.row(c);
        r.setPadding(0, Ui.dp(c, 5), 0, Ui.dp(c, 5));
        if (bg != 0) r.setBackgroundColor(bg);
        int[] w = {92, 92, 58, 58, 40, 76, 40, 58, 58, 92, 92};
        for (int i = 0; i < v.length; i++) {
            TextView t = Ui.mono(c, v[i], head ? 10 : 11, colors[i], head || i == 5);
            t.setGravity(Gravity.CENTER);
            r.addView(t, new LinearLayout.LayoutParams(Ui.dp(c, w[i]), -2));
        }
        return r;
    }

    // ================================================================== MARKET

    static LinearLayout market(Context c, Brain.Output o) {
        Snapshot s = o.snap; Result r = o.result;
        LinearLayout col = Ui.col(c);

        // what is moving Nifty
        LinearLayout mv = Ui.card(c);
        mv.addView(Ui.header(c, "What is moving Nifty" + (s.weightsApprox ? " (approx weights)" : "")));
        if (r.sectorContrib.isEmpty()) mv.addView(Ui.text(c, "Not loaded.", 12, Ui.DIM, false));
        else {
            double tot = 0, maxAbs = 0.05;
            for (Object[] x : r.sectorContrib) { tot += (Double) x[1]; maxAbs = Math.max(maxAbs, Math.abs((Double) x[1])); }
            mv.addView(Ui.kv(c, "Weighted move of the 50 stocks", String.format(Locale.US, "%+.2f%%", tot), Ui.signColor(tot)));
            if (!Double.isNaN(r.attrActual)) mv.addView(Ui.kv(c, "Actual Nifty move", pct(r.attrActual), Ui.signColor(r.attrActual)));
            mv.addView(Ui.kv(c, "Attribution coverage", Double.isNaN(r.attrCoverage) ? "n/a (tiny move)" : Math.round(r.attrCoverage) + "%"
                    + (r.attrCoverage < 70 || r.attrCoverage > 130 ? " — don't trust this breakdown much" : " — good"), r.attrCoverage < 70 || r.attrCoverage > 130 ? Ui.AMBER : Ui.GREEN));
            for (Object[] x : r.sectorContrib) {
                double v = (Double) x[1];
                mv.addView(barRow(c, (String) x[0], v / maxAbs, String.format(Locale.US, "%+.2f", v)));
            }
            mv.addView(Ui.text(c, "Numbers are % points of Nifty's move (weight × stock move).", 11, Ui.DIM, false), Ui.top(c, 4));
            mv.addView(Ui.header(c, "Biggest single-stock contributions"));
            for (Object[] x : r.heavy) mv.addView(Ui.kv(c, x[0] + String.format(Locale.US, "  (%.1f%% wt)", (Double) x[1]),
                    String.format(Locale.US, "%+.2f%%  →  %+.3f", (Double) x[2], (Double) x[3]), Ui.signColor((Double) x[3])));
        }
        col.addView(mv, Ui.cardLp(c));

        LinearLayout gl = Ui.card(c);
        gl.addView(Ui.header(c, "Global markets"));
        if (!Double.isNaN(r.globalRisk) || !Double.isNaN(r.indiaMacro)) {
            gl.addView(Ui.kv(c, "World risk appetite", sg(100 * nz(r.globalRisk)), Ui.signColor(nz(r.globalRisk))));
            gl.addView(Ui.kv(c, "India macro (rupee, crude, yields, dollar)", sg(100 * nz(r.indiaMacro)), Ui.signColor(nz(r.indiaMacro))));
        }
        if (s.global.isEmpty()) gl.addView(Ui.text(c, "Not loaded (turn on in Settings, or no internet).", 12, Ui.DIM, false));
        for (Map.Entry<String, Double> e : s.global.entrySet()) {
            Double price = s.globalPrice.get(e.getKey());
            gl.addView(Ui.kv(c, e.getKey(), (price == null ? "" : n2(price) + "   ") + pct(e.getValue()), Ui.signColor(e.getValue())));
        }
        String giftV;
        if (s.gift != null) giftV = n2(s.gift.last) + "   " + pct(s.gift.pct()) + (s.gift.time.length() >= 16 ? "  @" + s.gift.time.substring(11, 16) : "");
        else giftV = Double.isNaN(s.giftNifty) ? "not available" : n0(s.giftNifty) + " (typed)";
        gl.addView(Ui.kv(c, "GIFT Nifty" + (s.gift != null ? " (Kite)" : ""), giftV, s.gift != null ? Ui.signColor(s.gift.pct()) : Ui.TEXT), Ui.top(c, 4));
        gl.addView(Ui.text(c, "For India, rising crude and a weaker rupee are negatives.", 11, Ui.DIM, false), Ui.top(c, 4));
        col.addView(gl, Ui.cardLp(c));

        LinearLayout fi = Ui.card(c);
        fi.addView(Ui.header(c, "Institutions (background, daily)"));
        fi.addView(Ui.kv(c, "FII cash net" + (s.fiiDate.isEmpty() ? "" : " (" + s.fiiDate + ")"), Double.isNaN(s.fiiCash) ? "—" : String.format(Locale.US, "₹%,+.0f cr", s.fiiCash), Ui.signColor(Double.isNaN(s.fiiCash) ? 0 : s.fiiCash)));
        fi.addView(Ui.kv(c, "DII cash net", Double.isNaN(s.diiCash) ? "—" : String.format(Locale.US, "₹%,+.0f cr", s.diiCash), Ui.signColor(Double.isNaN(s.diiCash) ? 0 : s.diiCash)));
        fi.addView(Ui.kv(c, "FII long in index futures" + (s.fiiOiDate.isEmpty() ? "" : " (" + s.fiiOiDate + ")"),
                Double.isNaN(s.fiiIdxLong) ? "—" : Engine.f1(s.fiiIdxLong) + "%" + (Double.isNaN(s.fiiIdxLongPrev) ? "" : String.format(Locale.US, "  (%+.1f)", s.fiiIdxLong - s.fiiIdxLongPrev)),
                Double.isNaN(s.fiiIdxLong) ? Ui.DIM : s.fiiIdxLong >= 50 ? Ui.GREEN : Ui.RED));
        fi.addView(Ui.text(c, "NSE publishes these in the evening. They show the positioning behind the market, not what FIIs are doing right now. DII buying softens FII cash selling but does not cancel FII futures positions.", 11, Ui.DIM, false), Ui.top(c, 4));
        col.addView(fi, Ui.cardLp(c));

        LinearLayout se = Ui.card(c);
        se.addView(Ui.header(c, "Sector indices"));
        List<Map.Entry<String, Quote>> sec = new ArrayList<>(s.sectors.entrySet());
        sec.sort((a, b) -> Double.compare(b.getValue().pct(), a.getValue().pct()));
        double maxAbs = 0.5;
        for (Map.Entry<String, Quote> e : sec) maxAbs = Math.max(maxAbs, Math.abs(e.getValue().pct()));
        for (Map.Entry<String, Quote> e : sec) se.addView(barRow(c, e.getKey(), e.getValue().pct() / maxAbs, pct(e.getValue().pct())));
        if (sec.isEmpty()) se.addView(Ui.text(c, "Not loaded.", 12, Ui.DIM, false));
        col.addView(se, Ui.cardLp(c));

        LinearLayout br = Ui.card(c);
        br.addView(Ui.header(c, "Nifty 50 breadth"));
        br.addView(Ui.kv(c, "Up : Down", r.breadthLine.isEmpty() ? r.advances + " : " + r.declines : r.breadthLine, r.advances > r.declines ? Ui.GREEN : r.advances < r.declines ? Ui.RED : Ui.TEXT));
        Factor bf = r.factor("breadth");
        if (bf != null && bf.available) br.addView(Ui.text(c, bf.detail, 12, Ui.DIM, false), Ui.top(c, 4));
        List<Quote> st = new ArrayList<>();
        for (Quote q : s.stocks) if (q.ok() && q.prevClose > 0) st.add(q);
        st.sort((a, b) -> Double.compare(b.pct(), a.pct()));
        if (!st.isEmpty()) {
            LinearLayout two = Ui.row(c);
            two.setGravity(Gravity.TOP);
            LinearLayout up = Ui.col(c), dn = Ui.col(c);
            up.addView(Ui.text(c, "Top gainers", 12, Ui.DIM, true));
            dn.addView(Ui.text(c, "Top losers", 12, Ui.DIM, true));
            for (int i = 0; i < Math.min(6, st.size()); i++) {
                Quote a = st.get(i), z = st.get(st.size() - 1 - i);
                up.addView(Ui.text(c, a.symbol + "  " + pct(a.pct()), 12, Ui.signColor(a.pct()), false), Ui.top(c, 3));
                dn.addView(Ui.text(c, z.symbol + "  " + pct(z.pct()), 12, Ui.signColor(z.pct()), false), Ui.top(c, 3));
            }
            two.addView(up, Ui.weight(1));
            two.addView(dn, Ui.weight(1));
            br.addView(two, Ui.top(c, 8));
        }
        col.addView(br, Ui.cardLp(c));
        disclaimer(c, col);
        return col;
    }

    static LinearLayout barRow(Context c, String name, double v, String val) {
        LinearLayout rw = Ui.row(c);
        rw.setPadding(0, Ui.dp(c, 4), 0, Ui.dp(c, 4));
        rw.addView(Ui.text(c, name, 12, Ui.TEXT, false), new LinearLayout.LayoutParams(Ui.dp(c, 110), -2));
        Views.Bar b = new Views.Bar(c);
        b.set(v, true);
        rw.addView(b, Ui.weight(1));
        TextView pv = Ui.text(c, val, 12, Ui.signColor(v), true);
        pv.setGravity(Gravity.END);
        rw.addView(pv, new LinearLayout.LayoutParams(Ui.dp(c, 62), -2));
        return rw;
    }

    // ================================================================== NEWS + EVENTS

    static LinearLayout news(Context c, Brain.Output o) {
        Snapshot s = o.snap; Result r = o.result;
        LinearLayout col = Ui.col(c);
        LinearLayout top = Ui.card(c);
        top.addView(Ui.header(c, "Event risk"));
        top.addView(Ui.kv(c, "Level", r.eventRisk + (r.eventWhy.isEmpty() ? "" : " — " + r.eventWhy), r.eventRisk.equals("HIGH") ? Ui.RED : r.eventRisk.equals("MEDIUM") ? Ui.AMBER : Ui.GREEN));
        top.addView(Ui.kv(c, "News tone (last 24 h)", sg(r.eventScore) + (r.eventAdj == 0 ? "" : String.format(Locale.US, " → adds %+.0f to the score", r.eventAdj)), Ui.signColor(Double.isNaN(r.eventScore) ? 0 : r.eventScore)));
        top.addView(Ui.kv(c, "Read by", s.newsReader.isEmpty() ? "—" : s.newsReader, Ui.TEXT));
        top.addView(Ui.text(c, "The reader only rates impact (−1 to +1), severity and timing. It never says buy or sell, and it does not check facts. "
                + "So the app checks: official feed (RBI, SEBI) = VERIFIED; 2+ independent reports (different wording, not the same agency) = CONFIRMED; "
                + "several sites carrying one agency / copied story = CORROBORATED (counts 0.7, can't raise event risk); one source = PROVISIONAL (counts half, can't raise event risk). "
                + "Rumours count half again. News the market has already reacted to counts less. News moves the score by at most ±15.", 11, Ui.DIM, false), Ui.top(c, 6));
        if (!s.newsReader.startsWith("Gemini")) top.addView(Ui.text(c, "Tip: add a free Gemini API key in Settings for much better reading than the word list.", 11, Ui.AMBER, false), Ui.top(c, 4));
        col.addView(top, Ui.cardLp(c));

        LinearLayout ev = Ui.card(c);
        ev.addView(Ui.header(c, "Scheduled events (next 10 days)"));
        if (s.events.isEmpty()) ev.addView(Ui.text(c, "None known.", 12, Ui.DIM, false));
        for (EventItem e : s.events) {
            LinearLayout rw = Ui.col(c);
            rw.addView(Ui.kv(c, e.date + (e.date.equals(s.today) ? " (today)" : ""), e.name, e.importance >= 3 ? Ui.RED : e.importance == 2 ? Ui.AMBER : Ui.DIM));
            rw.addView(Ui.text(c, e.source, 10, Ui.GREY, false));
            ev.addView(rw);
        }
        ev.addView(Ui.text(c, "Built in: RBI policy days, US Fed decisions, India CPI (12th), Budget and Nifty expiries. Add your own in Settings.", 11, Ui.DIM, false), Ui.top(c, 6));
        col.addView(ev, Ui.cardLp(c));

        if (s.news.isEmpty()) col.addView(empty(c, "No headlines loaded yet."));
        // one card per story: copies of the same story are folded under it (they are not counted again anyway)
        java.util.Map<Integer, java.util.List<NewsItem>> dups = new java.util.HashMap<>();
        for (NewsItem n : s.news) if (!n.lead && n.cluster >= 0) dups.computeIfAbsent(n.cluster, k -> new java.util.ArrayList<>()).add(n);
        int folded = 0;
        for (NewsItem n : s.news) if (!n.lead && n.cluster >= 0) folded++;
        if (folded > 0) col.addView(Ui.text(c, s.news.size() - folded + " stories · " + folded + " copies folded under them", 11.5f, Ui.DIM, false), Ui.cardLp(c));
        for (NewsItem n : s.news) {
            if (!n.lead && n.cluster >= 0) continue;
            LinearLayout card = Ui.card(c);
            LinearLayout top2 = Ui.row(c);
            int ic = Ui.signColor(Math.abs(n.niftyImpact) < 0.1 ? 0 : n.niftyImpact);
            top2.addView(Ui.pill(c, String.format(Locale.US, "%+.1f", n.niftyImpact), ic), Ui.wrap());
            View g = new View(c); top2.addView(g, new LinearLayout.LayoutParams(Ui.dp(c, 8), 1));
            top2.addView(Ui.text(c, n.severity + " · " + n.horizon + (n.sector.isEmpty() ? "" : " · " + n.sector), 11, n.severity.equals("HIGH") ? Ui.RED : Ui.DIM, true), Ui.weight(1));
            int vc = n.verification.equals("VERIFIED") ? Ui.GREEN : n.verification.equals("CONFIRMED") ? Ui.CYAN : n.verification.equals("CORROBORATED") ? Ui.PURPLE : Ui.AMBER;
            String vt = n.verification + (n.verification.equals("CONFIRMED") ? " ×" + n.independent : n.verification.equals("CORROBORATED") ? " · " + n.publishers + " sites, 1 story" : "");
            top2.addView(Ui.pill(c, vt, vc), Ui.wrap());
            card.addView(top2);
            card.addView(Ui.text(c, n.title, 14, Ui.TEXT, false), Ui.top(c, 6));
            String meta = n.source + (n.agency.isEmpty() ? "" : " (via " + n.agency + ")") + " · " + ago(s.time, n.time) + (n.reason.isEmpty() ? "" : " · " + n.reason);
            card.addView(Ui.text(c, meta, 11, Ui.DIM, false), Ui.top(c, 4));
            String flags = (n.speculative ? "Rumour / possibility — counts half. " : "") + (n.lead ? "" : "Duplicate of an earlier headline — not counted again. ")
                    + (n.reaction.isEmpty() ? "" : "Market reaction: " + n.reaction + ".");
            if (!flags.isEmpty()) card.addView(Ui.text(c, flags.trim(), 11, n.lead ? Ui.TEXT : Ui.GREY, false), Ui.top(c, 2));
            card.addView(Ui.text(c, "Rated by " + (n.model.isEmpty() ? n.by : n.model) + (n.promptVersion.isEmpty() ? "" : " · prompt " + n.promptVersion)
                    + (n.ratedAt > 0 ? " · at " + time(n.ratedAt).substring(0, 5) : ""), 10, Ui.GREY, false), Ui.top(c, 2));
            java.util.List<NewsItem> same = n.cluster >= 0 ? dups.get(n.cluster) : null;
            if (same != null && !same.isEmpty()) {
                LinearLayout more = Ui.col(c);
                more.setVisibility(View.GONE);
                for (NewsItem d : same) more.addView(Ui.text(c, "• " + d.source + " · " + ago(s.time, d.time) + " — " + d.title, 11.5f, Ui.DIM, false), Ui.top(c, 4));
                TextView tg = Ui.text(c, "+" + same.size() + " similar headline" + (same.size() == 1 ? "" : "s") + " (counted once) ▾", 12, Ui.CYAN, true);
                tg.setOnClickListener(v -> { boolean sh = more.getVisibility() != View.VISIBLE; more.setVisibility(sh ? View.VISIBLE : View.GONE);
                    tg.setText("+" + same.size() + " similar headline" + (same.size() == 1 ? "" : "s") + " (counted once) " + (sh ? "▴" : "▾")); });
                card.addView(tg, Ui.top(c, 8));
                card.addView(more);
            }
            if (!n.link.isEmpty()) card.setOnClickListener(v -> {
                try { c.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(n.link))); } catch (Exception ignored) {}
            });
            col.addView(card, Ui.cardLp(c));
        }
        return col;
    }

    static String ago(long now, long t) {
        if (t <= 0) return "time unknown";
        long m = Math.max(0, (now - t) / 60000);
        return m < 60 ? m + " min ago" : (m / 60) + " h ago";
    }

    // ================================================================== RECORD + LEARNING

    static LinearLayout record(Context c, Store store, boolean autotune) {
        LinearLayout col = Ui.col(c);
        List<Store.Day> days = store.days();
        LinearLayout sum = Ui.card(c);
        sum.addView(Ui.header(c, "Track record (honest check)"));
        sum.addView(Ui.text(c, "Two calls are saved each day and checked at the close:\n"
                + "• Morning idea — the view between 8:00 and 9:15 (only if the app was open then), checked against the day's move.\n"
                + "• 9:45 call — checked from the 9:45 price to the close.\n"
                + "Bullish is right if Nifty gains more than 0.1%, bearish if it falls more than 0.1%, range if it moves less than 0.4%. NO EDGE and CONFLICT make no claim, so they are not counted.", 12, Ui.DIM, false));
        int[] idea = count(days, true), call = count(days, false);
        sum.addView(Ui.kv(c, "Morning idea right", idea[1] == 0 ? "no days yet" : idea[0] + " of " + idea[1] + " (" + Math.round(100.0 * idea[0] / idea[1]) + "%)", Ui.TEXT), Ui.top(c, 8));
        sum.addView(Ui.kv(c, "9:45 call right", call[1] == 0 ? "no days yet" : call[0] + " of " + call[1] + " (" + Math.round(100.0 * call[0] / call[1]) + "%)", Ui.TEXT));
        sum.addView(Ui.text(c, "Judge it after 30+ days. A handful of days means nothing.", 11, Ui.AMBER, false), Ui.top(c, 6));
        col.addView(sum, Ui.cardLp(c));

        // factor usefulness (walk-forward)
        Map<String, double[]> cal = new java.util.LinkedHashMap<>(store.calibration(Collector.day(new java.util.Date())));
        double[] meta = cal.remove("_meta");
        LinearLayout lc = Ui.card(c);
        lc.addView(Ui.header(c, "What the record teaches (per evidence)"));
        lc.addView(Ui.text(c, "At 9:45 and 11:30 the app saves each factor's reading, then checks at the close whether it pointed the right way. Safety rules: "
                + "weights are refitted at most once a week and never from today; the last 20 days are held back to test the new weights; "
                + "a factor needs 100 readings before it can move at all (±15% until 500, then ±40%); new weights are used only if they call the held-back days at least as well as fixed weights."
                + (autotune ? "" : " Learning is OFF in Settings, so weights are not changed."), 11, Ui.DIM, false));
        if (meta != null) {
            String st = meta[2] == 1 ? "passed — new weights in use" : meta[2] == 0 ? "failed — fixed weights kept" : "not enough data yet — fixed weights";
            lc.addView(Ui.kv(c, "Training / test days", (int) meta[0] + " / " + (int) meta[1], Ui.TEXT), Ui.top(c, 6));
            lc.addView(Ui.kv(c, "Validation", st + (meta[5] > 0 ? String.format(Locale.US, " (%d vs %d of %d right)", (int) meta[3], (int) meta[4], (int) meta[5]) : ""),
                    meta[2] == 1 ? Ui.GREEN : meta[2] == 0 ? Ui.AMBER : Ui.DIM));
        }
        if (!cal.isEmpty()) lc.addView(qualityRow(c, "Evidence", "Right", "Samples", "Weight ×", true));
        List<Map.Entry<String, double[]>> rows = new ArrayList<>(cal.entrySet());
        rows.sort((a, b) -> Double.compare(rate(b.getValue()), rate(a.getValue())));
        for (Map.Entry<String, double[]> e : rows) {
            double[] v = e.getValue();
            lc.addView(qualityRow(c, e.getKey(), Math.round(rate(v) * 100) + "%", String.valueOf((int) v[1]), String.format(Locale.US, "%.2f", autotune ? v[2] : 1.0), false));
        }
        col.addView(lc, Ui.cardLp(c));

        for (Store.Day d : days) {
            LinearLayout card = Ui.card(c);
            LinearLayout top = Ui.row(c);
            top.addView(Ui.text(c, d.date, 14, Ui.TEXT, true), Ui.weight(1));
            double mv = d.dayMove();
            top.addView(Ui.text(c, Double.isNaN(mv) ? "" : "Day " + pct(mv) + (d.final_ ? "" : " (so far)"), 12, Ui.signColor(Double.isNaN(mv) ? 0 : mv), true), Ui.wrap());
            card.addView(top);
            if (!d.ideaRegime.isEmpty()) card.addView(Ui.kv(c, "Morning idea", d.ideaRegime + String.format(Locale.US, " (%+.0f)", d.ideaScore) + mark(d.ideaHit(), d.final_), Ui.regimeColor(d.ideaRegime)));
            if (!d.callRegime.isEmpty()) card.addView(Ui.kv(c, "9:45 call", d.callRegime + " · conf " + d.callConf + mark(d.callHit(), d.final_), Ui.regimeColor(d.callRegime)));
            if (!Double.isNaN(d.callMove())) card.addView(Ui.kv(c, "9:45 → close", pct(d.callMove()), Ui.signColor(d.callMove())));
            col.addView(card, Ui.cardLp(c));
        }
        if (days.isEmpty()) col.addView(empty(c, "Nothing yet. Keep the app open (or the live watch on) around 8:30–9:45 on market days."));
        return col;
    }

    static double rate(double[] v) { return v[1] > 0 ? v[0] / v[1] : 0; }

    static String mark(Boolean hit, boolean fin) {
        if (hit == null) return "";
        return (hit ? "  ✓" : "  ✗") + (fin ? "" : "?");
    }

    static int[] count(List<Store.Day> days, boolean idea) {
        int ok = 0, n = 0;
        for (Store.Day d : days) {
            if (!d.final_) continue;
            Boolean h = idea ? d.ideaHit() : d.callHit();
            if (h == null) continue;
            n++; if (h) ok++;
        }
        return new int[]{ok, n};
    }

    static String time(long ms) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("HH:mm:ss", Locale.US);
        f.setTimeZone(Collector.IST);
        return f.format(new java.util.Date(ms));
    }
}

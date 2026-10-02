package com.krish.niftydirection.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.krish.niftydirection.data.IntelRunner;
import com.krish.niftydirection.data.Prefs;
import com.krish.niftydirection.intel.IntelEngine;
import com.krish.niftydirection.intel.OptionStrategy;
import com.krish.niftydirection.intel.Validator;
import com.krish.niftydirection.model.Snapshot;

import java.io.File;
import java.util.List;
import java.util.Locale;

/** Options tab, top part: strategy builder (AI view → strikes, premium, risk, BUY / exit signals) and your option positions. */
final class OptionsPage {
    private OptionsPage() {}

    /** Horizon picked on the page (null = the current signal's horizon). */
    static String horizon;
    /** Re-draws the current page (set by MainActivity). */
    static Runnable redraw = () -> {};

    static void build(Context c, LinearLayout col, Snapshot s) {
        IntelEngine.Forecast fc = IntelRunner.last;
        Validator.Config cfg = new Prefs(c).simConfig();
        File base = c.getFilesDir();
        IntelRunner.OptionsPlan plan = IntelRunner.optionsPlan(s, fc, horizon, cfg, System.currentTimeMillis());
        if (plan.chain == null) return;

        // ---------------- AI view + horizon picker
        LinearLayout hero = Ui.card(c);
        hero.addView(Ui.title(c, "Strategy builder", "AI forecast → strikes, premium, risk and signals"));
        if (fc != null) {
            LinearLayout chips = Ui.row(c);
            for (IntelEngine.HPred p : fc.preds) {
                if (!p.has()) continue;
                boolean on = plan.pred != null && plan.pred.hz.id.equals(p.hz.id);
                TextView t = Ui.chip(c, p.hz.label, on ? Ui.CYAN : Ui.DIM);
                t.setOnClickListener(v -> { horizon = p.hz.id; redraw.run(); });
                chips.addView(t, Ui.gapLeft(c, chips.getChildCount() == 0 ? 0 : 6));
            }
            HorizontalScrollView hs = new HorizontalScrollView(c);
            hs.setHorizontalScrollBarEnabled(false);
            hs.addView(chips);
            hero.addView(hs, Ui.top(c, 8));
        }
        OptionStrategy.View v = plan.view;
        String dirTxt = v.pUp >= 0.5 ? String.format(Locale.US, "↑ up %.0f%%", v.pUp * 100) : String.format(Locale.US, "↓ down %.0f%%", (1 - v.pUp) * 100);
        hero.addView(Ui.text(c, v.horizonLabel + ": " + dirTxt + (Double.isNaN(v.sigma) ? "" : String.format(Locale.US, " · typical move ±%.2f%%", v.sigma * 100)), 16, v.pUp >= 0.5 ? Ui.GREEN : Ui.RED, true), Ui.top(c, 8));
        String gate = v.act ? "Trade gate: OPEN" : "Trade gate: closed" + (v.validation.isEmpty() ? " (not validated)" : " (validation " + v.validation + ")");
        hero.addView(Ui.text(c, gate + " · exit by " + OptionStrategy.clock(v.exitBy), 11.5f, v.act ? Ui.GREEN : Ui.AMBER, false), Ui.top(c, 2));
        for (String n : plan.notes) hero.addView(Ui.text(c, n, 11, Ui.DIM, false), Ui.top(c, 4));
        col.addView(hero, Ui.cardLp(c));

        // ---------------- market readout
        OptionStrategy.Market m = plan.market;
        OptionStrategy.Chain ch = plan.chain;
        LinearLayout mk = Ui.card(c);
        mk.addView(Ui.header(c, "What options are pricing · " + ch.expiry + " (" + ch.days + "d) · lot " + ch.lot));
        int vc = "expensive".equals(m.vol) ? Ui.RED : "cheap".equals(m.vol) ? Ui.GREEN : Ui.CYAN;
        LinearLayout vr = Ui.row(c);
        vr.addView(Ui.text(c, String.format(Locale.US, "ATM %,.0f · IV %.1f%% · real moves %s", m.atm, m.atmIv * 100, Double.isNaN(m.rv) ? "—" : String.format(Locale.US, "%.1f%%", m.rv * 100)), 13, Ui.TEXT, true), Ui.weight(1));
        vr.addView(Ui.pill(c, m.vol.toUpperCase(Locale.US), vc), Ui.wrap());
        mk.addView(vr, Ui.top(c, 6));
        mk.addView(Ui.kv(c, "Move priced in to expiry (±1σ)", String.format(Locale.US, "±%,.0f pts  (%,.0f – %,.0f)", m.moveExpiry, ch.spot - m.moveExpiry, ch.spot + m.moveExpiry), Ui.TEXT));
        mk.addView(Ui.kv(c, "Move priced in for one day", String.format(Locale.US, "±%,.0f pts", m.moveDay), Ui.TEXT));
        mk.addView(Ui.kv(c, "PCR · max pain", String.format(Locale.US, "%.2f · %,.0f", m.pcr, m.maxPain), Ui.DIM));
        mk.addView(Ui.kv(c, "Put wall · call wall", String.format(Locale.US, "%,.0f · %,.0f", m.putWall, m.callWall), Ui.DIM));
        if (!Double.isNaN(m.skew)) mk.addView(Ui.kv(c, "Skew (3% OTM put − call IV)", String.format(Locale.US, "%+.1f pts", m.skew), Ui.DIM));
        for (String n : m.notes) mk.addView(Ui.text(c, "• " + n, 11, Ui.DIM, false), Ui.top(c, 4));
        col.addView(mk, Ui.cardLp(c));

        // ---------------- ideas
        int shown = 0;
        StringBuilder avoided = new StringBuilder();
        for (OptionStrategy.Idea i : plan.ideas) {
            if ("AVOID".equals(i.signal)) { avoided.append("• ").append(i.name).append(": ").append(i.reasons.isEmpty() ? "" : i.reasons.get(0)).append('\n'); continue; }
            if (shown++ >= 3) continue;
            col.addView(ideaCard(c, base, plan, i), Ui.cardLp(c));
        }
        if (shown == 0) {
            LinearLayout e = Ui.card(c);
            e.addView(Ui.text(c, "No strategy fits right now.", 14, Ui.TEXT, true));
            col.addView(e, Ui.cardLp(c));
        }
        if (avoided.length() > 0) {
            LinearLayout a = Ui.card(c);
            a.addView(Ui.header(c, "Avoid now"));
            a.addView(Ui.text(c, avoided.toString().trim(), 11.5f, Ui.DIM, false), Ui.top(c, 4));
            a.addView(Ui.text(c, "Never offered: naked option selling, short straddles / strangles (unlimited loss).", 11, Ui.DIM, false), Ui.top(c, 4));
            col.addView(a, Ui.cardLp(c));
        }

        // ---------------- positions
        List<OptionStrategy.Position> pos = IntelRunner.optPositions(base);
        if (!pos.isEmpty()) col.addView(positions(c, base, s, fc, cfg, pos), Ui.cardLp(c));

        // ---------------- fair premium table
        LinearLayout fr = Ui.card(c);
        fr.addView(Ui.header(c, "Is the premium reasonable? (market vs fair)"));
        fr.addView(Ui.text(c, "Fair = the market's own smile re-scaled to Nifty's real recent moves blended with the AI's expected range. Wide spread = hard to get a good fill.", 10.5f, Ui.DIM, false), Ui.top(c, 2));
        fr.addView(row(c, true, "CE ₹ (fair)", "Strike", "PE ₹ (fair)", Ui.DIM, Ui.DIM), Ui.top(c, 6));
        for (OptionStrategy.Fair f : plan.fair)
            fr.addView(row(c, false, prem(f.ce, f.ceFair, f.ceVerdict), String.format(Locale.US, "%,.0f", f.strike), prem(f.pe, f.peFair, f.peVerdict),
                    verdictColor(f.ceVerdict), verdictColor(f.peVerdict)));
        col.addView(fr, Ui.cardLp(c));
    }

    static LinearLayout ideaCard(Context c, File base, IntelRunner.OptionsPlan plan, OptionStrategy.Idea i) {
        LinearLayout k = Ui.card(c);
        LinearLayout h = Ui.row(c);
        h.addView(Ui.text(c, i.name, 15, Ui.TEXT, true), Ui.weight(1));
        h.addView(Ui.pill(c, i.signal, signalColor(i.signal)), Ui.wrap());
        k.addView(h);
        k.addView(Ui.text(c, i.kind + " · " + i.expiry + " expiry", 11, Ui.DIM, false), Ui.top(c, 2));
        for (OptionStrategy.Leg g : i.legs) k.addView(Ui.mono(c, g.label() + String.format(Locale.US, "  IV %.1f%%", g.iv * 100), 12, g.qty > 0 ? Ui.GREEN : Ui.RED, false), Ui.top(c, 3));
        int lot = plan.chain.lot;
        k.addView(Ui.kv(c, i.net >= 0 ? "Premium paid (1 lot)" : "Premium received (1 lot)", String.format(Locale.US, "₹%,.0f", Math.abs(i.net) * lot), Ui.TEXT), Ui.top(c, 4));
        k.addView(Ui.kv(c, "Max profit · max loss (1 lot, at expiry)", String.format(Locale.US, "₹%,.0f · ₹%,.0f", i.maxProfit, i.maxLoss), Ui.TEXT));
        String be = Double.isNaN(i.breakLo) ? "—" : Double.isNaN(i.breakHi) ? String.format(Locale.US, "%,.0f", i.breakLo) : String.format(Locale.US, "%,.0f / %,.0f", i.breakLo, i.breakHi);
        k.addView(Ui.kv(c, "Breakeven at expiry", be, Ui.TEXT));
        k.addView(Ui.kv(c, "Chance of profit · expected (AI view)", String.format(Locale.US, "%.0f%% · %s₹%,.0f", i.pop * 100, i.ev < 0 ? "−" : "+", Math.abs(i.ev)), i.ev > 0 ? Ui.GREEN : Ui.RED));
        k.addView(Ui.kv(c, "Charges + slippage (1 lot)", String.format(Locale.US, "₹%,.0f", i.costs), Ui.DIM));
        k.addView(Ui.kv(c, "Lots within your risk limit", String.valueOf(i.lots), Ui.DIM));
        k.addView(Ui.text(c, i.why, 11.5f, Ui.TEXT, false), Ui.top(c, 6));
        k.addView(Ui.text(c, i.history, 10.5f, Ui.DIM, false), Ui.top(c, 2));
        for (String r : i.reasons) k.addView(Ui.text(c, "• " + r, 11, Ui.AMBER, false), Ui.top(c, 2));
        k.addView(Ui.text(c, "Exit plan: " + i.exitText, 11.5f, Ui.CYAN, false), Ui.top(c, 6));
        if (i.actionable()) {
            LinearLayout b = Ui.row(c);
            Button paper = Ui.button(c, "Paper trade", Ui.AMBER);
            paper.setOnClickListener(v -> take(c, base, plan, i, true));
            b.addView(paper, Ui.weight(1));
            if ("BUY".equals(i.signal)) {
                Button real = Ui.primary(c, "I placed it");
                real.setOnClickListener(v -> take(c, base, plan, i, false));
                b.addView(real, Ui.gapLeft(c, 8));
            }
            k.addView(b, Ui.top(c, 8));
        }
        return k;
    }

    static void take(Context c, File base, IntelRunner.OptionsPlan plan, OptionStrategy.Idea i, boolean paper) {
        int lots = Math.max(1, i.lots);
        new AlertDialog.Builder(c)
                .setTitle(paper ? "Paper trade" : "Record your trade")
                .setMessage(i.name + "\n" + lots + " lot(s) at the prices shown.\n\n" + i.exitText + "\n\nThe app watches it and shows EXIT when a rule is hit. It never places orders.")
                .setPositiveButton("Save", (d, w) -> {
                    try { IntelRunner.takeOption(base, plan, i, lots, paper); } catch (Exception e) { /* shown as missing */ }
                    redraw.run();
                })
                .setNegativeButton("Cancel", null).show();
    }

    static LinearLayout positions(Context c, File base, Snapshot s, IntelEngine.Forecast fc, Validator.Config cfg, List<OptionStrategy.Position> pos) {
        LinearLayout k = Ui.card(c);
        k.addView(Ui.header(c, "Your option positions"));
        double net = 0; int closed = 0;
        for (int idx = pos.size() - 1; idx >= 0; idx--) {
            OptionStrategy.Position p = pos.get(idx);
            if (p.closed) { closed++; if (!Double.isNaN(p.exitPnl)) net += p.exitPnl; continue; }
            OptionStrategy.Status st = IntelRunner.optStatus(s, fc, p, cfg);
            LinearLayout r = Ui.row(c);
            r.addView(Ui.text(c, (p.paper ? "📝 " : "") + p.name + " ×" + p.lots, 13, Ui.TEXT, true), Ui.weight(1));
            String sig = st == null ? "NO PRICE" : st.signal;
            r.addView(Ui.pill(c, sig, "EXIT".equals(sig) ? Ui.RED : "HOLD".equals(sig) ? Ui.GREEN : Ui.GREY), Ui.wrap());
            k.addView(r, Ui.top(c, 8));
            if (st != null && !Double.isNaN(st.pnl))
                k.addView(Ui.text(c, String.format(Locale.US, "P&L now %s₹%,.0f after costs · %s view · exit by %s", st.pnl < 0 ? "−" : "+", Math.abs(st.pnl), p.horizon, OptionStrategy.clock(p.exitBy)), 11.5f, Ui.signColor(st.pnl), false));
            if (st != null) for (String why : st.reasons) k.addView(Ui.text(c, "• " + why, 11, "EXIT".equals(st.signal) ? Ui.RED : Ui.DIM, false));
            LinearLayout b = Ui.row(c);
            Button cl = Ui.button(c, "Close at current price", Ui.CYAN);
            cl.setOnClickListener(v -> { try { IntelRunner.closeOption(base, p.id, st); } catch (Exception e) { /* ignore */ } redraw.run(); });
            Button del = Ui.button(c, "Delete", Ui.GREY);
            del.setOnClickListener(v -> { try { IntelRunner.deleteOption(base, p.id); } catch (Exception e) { /* ignore */ } redraw.run(); });
            b.addView(cl, Ui.weight(1));
            b.addView(del, Ui.gapLeft(c, 8));
            k.addView(b, Ui.top(c, 4));
        }
        if (closed > 0) k.addView(Ui.text(c, String.format(Locale.US, "Closed: %d · net %s₹%,.0f after costs", closed, net < 0 ? "−" : "+", Math.abs(net)), 12, Ui.signColor(net), true), Ui.top(c, 8));
        return k;
    }

    static LinearLayout row(Context c, boolean head, String a, String b, String d, int ca, int cd) {
        LinearLayout r = Ui.row(c);
        r.addView(Ui.text(c, a, 11.5f, head ? Ui.DIM : ca, head), Ui.weight(1.2f));
        TextView k = Ui.text(c, b, 12, head ? Ui.DIM : Ui.TEXT, true);
        k.setGravity(android.view.Gravity.CENTER);
        r.addView(k, Ui.weight(0.8f));
        TextView p = Ui.text(c, d, 11.5f, head ? Ui.DIM : cd, head);
        p.setGravity(android.view.Gravity.END);
        r.addView(p, Ui.weight(1.2f));
        r.setPadding(0, Ui.dp(c, 3), 0, Ui.dp(c, 3));
        return r;
    }

    static String prem(double px, double fair, String verdict) {
        if (Double.isNaN(px)) return "—";
        String s = String.format(Locale.US, "%.1f", px);
        if (!Double.isNaN(fair)) s += String.format(Locale.US, " (%.1f)", fair);
        return verdict.isEmpty() ? s : s + " " + verdict;
    }

    static int verdictColor(String v) {
        return "expensive".equals(v) || "wide spread".equals(v) ? Ui.RED : "cheap".equals(v) ? Ui.GREEN : "fair".equals(v) ? Ui.TEXT : Ui.DIM;
    }

    static int signalColor(String s) {
        return "BUY".equals(s) ? Ui.GREEN : "PAPER".equals(s) ? Ui.AMBER : "AVOID".equals(s) ? Ui.RED : Ui.GREY;
    }
}

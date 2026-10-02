package com.krish.niftydirection.intel;

import com.krish.niftydirection.model.OptionRow;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Option strategy builder.
 *
 * 1. Reads the live chain: ATM strike and IV, the move options are pricing in, IV against how much Nifty really moved
 *    lately (options "expensive" / "fair" / "cheap"), PCR, max pain, OI walls.
 * 2. Fair premium for each strike: the market's own smile re-scaled to a fair volatility (realised move blended with the
 *    AI's expected range), and the bid/ask spread — so you can see if a premium is reasonable.
 * 3. Builds defined-risk strategies for the AI forecast of one horizon (buy ATM option, debit spread, credit spread,
 *    iron condor, long straddle), with strikes, premium, max profit / loss, breakevens, charges, lots for your risk limit,
 *    chance of profit and expected ₹ — both worked out from the AI's own probability and range at the horizon's end.
 * 4. Signals: BUY only when the horizon passed pre-live validation, the trade gate and risk guard allow it and the
 *    expected value is positive after costs; PAPER when only the validation is missing; otherwise WAIT / AVOID with the
 *    reasons. Every idea carries its exit plan (Nifty levels, ₹ target / stop, time), and taken positions get EXIT
 *    signals from {@link #check}.
 *
 * Unlimited-risk trades (naked selling, short straddle / strangle) are never suggested.
 *
 * Rules marked "history" come from a replay with real NSE end-of-day option prices, 26 Sep 2025 – 1 Oct 2026.
 */
public final class OptionStrategy {
    private OptionStrategy() {}

    public static final double RATE = 0.065;
    /** IV / realised above this = options expensive (buying premium lost money there); below CHEAP = cheap. */
    public static final double EXPENSIVE = 1.15, CHEAP = 0.95;
    /** IV is quoted on calendar time (365 days); realised volatility on trading days (252). Multiply trading-day vol by this to compare. */
    public static final double CAL = Math.sqrt(252.0 / 365.0);

    // ------------------------------------------------------------------ inputs

    /** One expiry of the chain, as the builder sees it. */
    public static final class Chain {
        public double spot, years, step = 50;
        public int lot = 65, days;
        public String expiry = "";
        public List<OptionRow> rows = new ArrayList<>();

        public OptionRow row(double k) {
            for (OptionRow r : rows) if (Math.abs(r.strike - k) < 0.01) return r;
            return null;
        }
        /** Tradable price: mid of bid / offer when both are there and sane, else last traded. NaN = none. */
        public double price(boolean call, double k) {
            OptionRow r = row(k);
            if (r == null) return Double.NaN;
            double bid = call ? r.ceBid : r.peBid, ask = call ? r.ceAsk : r.peAsk, ltp = call ? r.ceLtp : r.peLtp;
            if (bid > 0 && ask >= bid && (ask - bid) <= 0.2 * ask) return (bid + ask) / 2;
            return ltp > 0 ? ltp : Double.NaN;
        }
        /** Bid/offer spread as a fraction of the price (NaN = no quotes). */
        public double spread(boolean call, double k) {
            OptionRow r = row(k);
            if (r == null) return Double.NaN;
            double bid = call ? r.ceBid : r.peBid, ask = call ? r.ceAsk : r.peAsk;
            return bid > 0 && ask >= bid ? (ask - bid) / ((ask + bid) / 2) : Double.NaN;
        }
        /** IV as a fraction (0.13), falling back to the other side of the strike, then NaN. */
        public double iv(boolean call, double k) {
            OptionRow r = row(k);
            if (r == null) return Double.NaN;
            double a = call ? r.ceIv : r.peIv, b = call ? r.peIv : r.ceIv;
            return !Double.isNaN(a) ? a / 100 : !Double.isNaN(b) ? b / 100 : Double.NaN;
        }
        public double atm() { return Math.round(spot / step) * step; }
    }

    /** What the AI says for the chosen horizon. */
    public static final class View {
        public String horizon = "", horizonLabel = "";
        public double pUp = 0.5, sigma = Double.NaN;     // sigma = 1σ move over the horizon, fraction of price
        public double years;                             // horizon length in calendar years
        public long exitBy;                              // clock time the trade should be out (epoch ms)
        public boolean act;                              // the trade gate allows acting on this horizon now
        public String validation = "";                   // PASS / WARN / FAIL / ""
        public final List<String> blocks = new ArrayList<>();   // risk guard / gate reasons (empty = allowed)
        public int dir() { return pUp >= 0.5 ? 1 : -1; }
    }

    public static final class Config {
        public double slip = 1.0;            // points lost per fill (each leg, each side)
        public double riskBudget = 3000;     // ₹ you accept losing on one trade (Settings → Risk guard)
        public int maxLots = 10;
    }

    // ------------------------------------------------------------------ chain readout

    public static final class Market {
        public double atm, atmIv = Double.NaN, rv = Double.NaN, ivRv = Double.NaN;
        public double moveExpiry = Double.NaN, moveDay = Double.NaN;     // points, ±1σ priced in
        public double pcr = Double.NaN, maxPain = Double.NaN, callWall = Double.NaN, putWall = Double.NaN, skew = Double.NaN;
        public String vol = "unknown";
        public final List<String> notes = new ArrayList<>();
    }

    /** rv = realised volatility of Nifty (annualised over 252 trading days, e.g. 0.12), NaN if unknown. */
    public static Market read(Chain c, double rvTrading) {
        double rv = rvTrading * CAL;     // on the same calendar basis as IV
        Market m = new Market();
        m.atm = c.atm();
        double ce = c.iv(true, m.atm), pe = c.iv(false, m.atm);
        m.atmIv = !Double.isNaN(ce) && !Double.isNaN(pe) ? (ce + pe) / 2 : !Double.isNaN(ce) ? ce : pe;
        m.rv = rv;
        if (!Double.isNaN(m.atmIv)) {
            m.moveExpiry = c.spot * m.atmIv * Math.sqrt(Math.max(c.years, 1e-6));
            m.moveDay = c.spot * m.atmIv / Math.sqrt(252);
        }
        if (!Double.isNaN(m.atmIv) && rv > 0) {
            m.ivRv = m.atmIv / rv;
            m.vol = m.ivRv > EXPENSIVE ? "expensive" : m.ivRv < CHEAP ? "cheap" : "fair";
        }
        double coi = 0, poi = 0, best = Double.MAX_VALUE, cw = -1, pw = -1, cwOi = -1, pwOi = -1;
        for (OptionRow r : c.rows) {
            coi += r.ceOi; poi += r.peOi;
            if (r.strike > c.spot && r.ceOi > cwOi) { cwOi = r.ceOi; cw = r.strike; }
            if (r.strike < c.spot && r.peOi > pwOi) { pwOi = r.peOi; pw = r.strike; }
        }
        if (coi > 0) m.pcr = poi / coi;
        if (cw > 0) m.callWall = cw;
        if (pw > 0) m.putWall = pw;
        for (OptionRow x : c.rows) {
            double pain = 0;
            for (OptionRow r : c.rows) pain += Math.max(0, x.strike - r.strike) * r.ceOi + Math.max(0, r.strike - x.strike) * r.peOi;
            if (pain < best) { best = pain; m.maxPain = x.strike; }
        }
        double kp = Math.round(c.spot * 0.97 / c.step) * c.step, kc = Math.round(c.spot * 1.03 / c.step) * c.step;
        double ivp = c.iv(false, kp), ivc = c.iv(true, kc);
        if (!Double.isNaN(ivp) && !Double.isNaN(ivc)) m.skew = (ivp - ivc) * 100;
        if ("expensive".equals(m.vol)) m.notes.add(String.format(Locale.US,
                "Options are expensive: IV %.1f%% vs Nifty's recent real moves %.1f%% (%.2f×). History (3 of 4 days were like this): buying the AI's direction as an option lost ~₹261 a trade, a straddle ~₹1,074 a night; overnight sellers earned ~₹243 but risked ₹19k+ on bad nights.",
                m.atmIv * 100, rv * 100, m.ivRv));
        else if ("cheap".equals(m.vol)) m.notes.add(String.format(Locale.US,
                "Options are cheap: IV %.1f%% vs real moves %.1f%% (%.2f×). History: buying direction did better at fair/cheap prices (~+₹1,000 a trade, only 61 days — not proven).",
                m.atmIv * 100, rv * 100, m.ivRv));
        m.notes.add("Options predict the SIZE of the move well (priced-in move vs real move correlated), not its direction: PCR, max pain and OI walls did not call the next day better than a coin flip, and walls held no better than any level the same distance away.");
        if (c.days == 0) m.notes.add("Expiry day: near-expiry options lose value very fast — the builder uses the next expiry.");
        return m;
    }

    // ------------------------------------------------------------------ fair premium

    public static final class Fair {
        public double strike, ce = Double.NaN, ceFair = Double.NaN, pe = Double.NaN, peFair = Double.NaN, ceSpread = Double.NaN, peSpread = Double.NaN;
        public String ceVerdict = "", peVerdict = "";
    }

    /**
     * Fair volatility (calendar basis, comparable with IV): realised volatility blended with the AI's expected range; falls back to either.
     * sigmaDay = AI 1-day 1σ move (fraction), NaN if none.
     */
    public static double fairVol(double rv, double sigmaDay) {
        double a = rv > 0 ? rv : Double.NaN, b = sigmaDay > 0 ? sigmaDay * Math.sqrt(252) : Double.NaN;
        double v = !Double.isNaN(a) && !Double.isNaN(b) ? (a + b) / 2 : !Double.isNaN(a) ? a : b;
        return v * CAL;
    }

    /** Market vs fair premium for `each` strikes either side of the money (the market smile scaled to the fair level). */
    public static List<Fair> fair(Chain c, Market m, double fairVol, int each) {
        List<Fair> out = new ArrayList<>();
        double scale = !Double.isNaN(fairVol) && m.atmIv > 0 ? fairVol / m.atmIv : Double.NaN;
        for (int i = each; i >= -each; i--) {
            double k = m.atm + i * c.step;
            if (c.row(k) == null) continue;
            Fair f = new Fair();
            f.strike = k;
            for (int side = 0; side < 2; side++) {
                boolean call = side == 0;
                double px = c.price(call, k), iv = c.iv(call, k), sp = c.spread(call, k);
                double fair = !Double.isNaN(iv) && !Double.isNaN(scale) ? bs(call, c.spot, k, c.years, iv * scale) : Double.NaN;
                String v = verdict(px, fair, sp);
                if (call) { f.ce = px; f.ceFair = fair; f.ceSpread = sp; f.ceVerdict = v; }
                else { f.pe = px; f.peFair = fair; f.peSpread = sp; f.peVerdict = v; }
            }
            out.add(f);
        }
        return out;
    }

    static String verdict(double px, double fair, double spread) {
        if (Double.isNaN(px)) return "no price";
        if (!Double.isNaN(spread) && spread > 0.05) return "wide spread";
        if (Double.isNaN(fair) || fair <= 0) return "";
        double r = px / fair;
        return r > 1.15 ? "expensive" : r < 0.87 ? "cheap" : "fair";
    }

    // ------------------------------------------------------------------ ideas

    public static final class Leg {
        public boolean call;
        public double strike, price, iv;
        public int qty;                       // +1 buy, -1 sell (per lot)
        public String label() { return String.format(Locale.US, "%s %.0f %s @ ₹%.1f", qty > 0 ? "BUY" : "SELL", strike, call ? "CE" : "PE", price); }
    }

    public static final class Idea {
        public String name = "", kind = "", why = "", history = "", expiry = "";
        public int dir;                                     // +1 bullish, -1 bearish, 0 neutral
        public final List<Leg> legs = new ArrayList<>();
        public double net;                                  // points per unit: + = debit paid, - = credit received
        public double maxProfit, maxLoss, costs, pop = Double.NaN, ev = Double.NaN;   // ₹ per lot (costs = charges + slippage)
        public double breakLo = Double.NaN, breakHi = Double.NaN;
        public boolean unlimited;
        public int lots;
        public String signal = "WAIT";                      // BUY / PAPER / WAIT / AVOID
        public final List<String> reasons = new ArrayList<>();
        // exit plan
        public double exitBelow = Double.NaN, exitAbove = Double.NaN;   // Nifty levels
        public double takeProfit, stopLoss;                             // ₹ per lot (P&L after costs)
        public long exitBy;
        public String exitText = "";
        public boolean actionable() { return "BUY".equals(signal) || "PAPER".equals(signal); }
    }

    /** All ideas for the view, best first (AVOID last). */
    public static List<Idea> build(Chain c, Market m, View v, Config cfg) {
        List<Idea> out = new ArrayList<>();
        if (c.rows.isEmpty() || Double.isNaN(m.atmIv)) return out;
        double sig = !Double.isNaN(v.sigma) && v.sigma > 0 ? v.sigma : m.atmIv * Math.sqrt(Math.max(v.years, 1e-6));
        double w = Math.max(2 * c.step, Math.round(sig * c.spot / c.step) * c.step);
        double mv = Math.max(c.step, Math.round((Double.isNaN(m.moveExpiry) ? sig * c.spot : m.moveExpiry) / c.step) * c.step);
        int d = v.dir();
        boolean up = d > 0;
        double k0 = m.atm;
        String side = up ? "CE" : "PE";

        Idea a = idea(c, "Buy " + (long) k0 + " " + side, "Directional", d, new Object[][]{{up, k0, 1}});
        if (a != null) { a.why = "Simplest bet on the AI's direction. Loss limited to the premium."; a.history = "History (AI 1-day direction, ATM, overnight, real NSE prices): about break-even overall; −₹261/trade when options were expensive, ~+₹1,000 at fair/cheap prices."; out.add(a); }
        Idea b = idea(c, (up ? "Bull call spread " : "Bear put spread ") + (long) k0 + "/" + (long) (k0 + d * w), "Directional", d, new Object[][]{{up, k0, 1}, {up, k0 + d * w, -1}});
        if (b != null) { b.why = "Cheaper than buying alone; profit capped at the second strike (about one expected move away)."; b.history = "History: −₹100 to −₹223/trade overnight (two legs of costs); −₹219 when expensive, +₹619 at fair prices."; out.add(b); }
        Idea cr = idea(c, (up ? "Bull put spread " : "Bear call spread ") + (long) k0 + "/" + (long) (k0 - d * w), "Directional (credit)", d,
                new Object[][]{{!up, k0, -1}, {!up, k0 - d * w, 1}});
        if (cr != null) { cr.why = "Collects premium if Nifty does not move against the view; time decay works for you; loss capped by the bought strike."; cr.history = "History: −₹108/trade overall; −₹182 when expensive, +₹476 at fair prices."; out.add(cr); }
        Idea ic = idea(c, "Iron condor " + (long) (k0 - mv - w) + "/" + (long) (k0 - mv) + " – " + (long) (k0 + mv) + "/" + (long) (k0 + mv + w), "Range-bound", 0,
                new Object[][]{{false, k0 - mv, -1}, {false, k0 - mv - w, 1}, {true, k0 + mv, -1}, {true, k0 + mv + w, 1}});
        if (ic != null) { ic.why = "Profits if Nifty stays inside the move options price in. Four legs = four sets of costs."; ic.history = "History: −₹606/trade held overnight, −₹427 held to weekly expiry (costs ate the edge)."; out.add(ic); }
        Idea st = idea(c, "Long straddle " + (long) k0, "Big move either way", 0, new Object[][]{{true, k0, 1}, {false, k0, 1}});
        if (st != null) { st.why = "Profits from a large move in either direction (events)."; st.history = "History: −₹924/night overall and lost in every IV state (−₹1,074 expensive, −₹1,510 cheap). Event days only, never as a habit."; out.add(st); }

        double tRem = Math.max(0, c.years - v.years);
        for (Idea i : out) {
            i.expiry = c.expiry;
            evaluate(i, c, v, sig, tRem, cfg);
            rules(i, c, m, v, cfg);
            plan(i, c, v, sig, w, mv);
        }
        out.sort((x, y) -> {
            int rx = rank(x.signal), ry = rank(y.signal);
            if (rx != ry) return rx - ry;
            return Double.compare(nz(y.ev), nz(x.ev));
        });
        return out;
    }

    static int rank(String s) { return "BUY".equals(s) ? 0 : "PAPER".equals(s) ? 1 : "WAIT".equals(s) ? 2 : 3; }
    static double nz(double x) { return Double.isNaN(x) ? -1e18 : x; }

    /** legs: {Boolean call, Double strike, Integer qty}. Null when a strike has no price. */
    static Idea idea(Chain c, String name, String kind, int dir, Object[][] legs) {
        Idea i = new Idea();
        i.name = name; i.kind = kind; i.dir = dir;
        for (Object[] l : legs) {
            Leg g = new Leg();
            g.call = (Boolean) l[0]; g.strike = ((Number) l[1]).doubleValue(); g.qty = ((Number) l[2]).intValue();
            g.price = c.price(g.call, g.strike);
            g.iv = c.iv(g.call, g.strike);
            if (Double.isNaN(g.price) || Double.isNaN(g.iv) || g.price <= 0) return null;
            i.legs.add(g);
            i.net += g.qty * g.price;
        }
        return i;
    }

    /** Max profit / loss at expiry, breakevens, costs, then chance of profit and expected ₹ at the horizon's end. */
    static void evaluate(Idea i, Chain c, View v, double sig, double tRem, Config cfg) {
        int lot = c.lot;
        double cost = 0;
        for (Leg g : i.legs) cost += 2 * cfg.slip * lot + charges(g.price, g.price, lot);
        i.costs = cost;
        // expiry payoff on a grid of kinks and far ends (piecewise linear)
        List<Double> xs = new ArrayList<>();
        xs.add(c.spot * 0.5); xs.add(c.spot * 1.5);
        for (Leg g : i.legs) xs.add(g.strike);
        xs.sort(Double::compare);
        double mx = -1e18, mn = 1e18;
        double prevX = Double.NaN, prevY = Double.NaN;
        for (double x : xs) {
            double y = payoff(i, x);
            mx = Math.max(mx, y); mn = Math.min(mn, y);
            if (!Double.isNaN(prevY) && (prevY < 0) != (y < 0) && y != prevY) {
                double be = prevX + (x - prevX) * (0 - prevY) / (y - prevY);
                if (Double.isNaN(i.breakLo)) i.breakLo = be; else i.breakHi = be;
            }
            prevX = x; prevY = y;
        }
        double lo1 = payoff(i, c.spot * 0.4), hi1 = payoff(i, c.spot * 1.6);
        i.unlimited = lo1 < payoff(i, c.spot * 0.5) - 1 || hi1 < payoff(i, c.spot * 1.5) - 1;
        i.maxProfit = mx * lot - cost;
        i.maxLoss = -mn * lot + cost;
        // scenarios at the horizon's end: lognormal with P(up) = the AI's probability
        double mu = sig * invNorm(Math.min(0.99, Math.max(0.01, v.pUp)));
        double wsum = 0, ev = 0, pop = 0;
        for (int s = -40; s <= 40; s++) {
            double z = s / 10.0, wt = Math.exp(-z * z / 2);
            double S = c.spot * Math.exp(mu + sig * z);
            double pnl = (value(i, S, tRem) - i.net) * lot - cost;
            wsum += wt; ev += wt * pnl; if (pnl > 0) pop += wt;
        }
        i.ev = ev / wsum;
        i.pop = pop / wsum;
    }

    static double payoff(Idea i, double s) {
        double y = -i.net;
        for (Leg g : i.legs) y += g.qty * Math.max(0, g.call ? s - g.strike : g.strike - s);
        return y;
    }

    /** Position value in points per unit at spot s with t years left (each leg at its own IV). */
    public static double value(Idea i, double s, double t) {
        double y = 0;
        for (Leg g : i.legs) y += g.qty * bs(g.call, s, g.strike, t, g.iv);
        return y;
    }

    static void rules(Idea i, Chain c, Market m, View v, Config cfg) {
        boolean buysPremium = i.net > 0;
        if (i.unlimited) i.reasons.add("unlimited risk");
        for (Leg g : i.legs) {
            double sp = c.spread(g.call, g.strike);
            if (!Double.isNaN(sp) && sp > 0.05) i.reasons.add(String.format(Locale.US, "%.0f %s: bid/offer spread %.0f%% — too illiquid", g.strike, g.call ? "CE" : "PE", sp * 100));
            if (g.price < 2) i.reasons.add(String.format(Locale.US, "%.0f %s premium ₹%.1f is too small to trade sensibly", g.strike, g.call ? "CE" : "PE", g.price));
        }
        if (i.name.startsWith("Long straddle") && !"cheap".equals(m.vol)) i.reasons.add("long straddle only when options are cheap (history: lost ~₹1,074 a night when expensive)");
        if (buysPremium && "expensive".equals(m.vol) && i.dir != 0) i.reasons.add(String.format(Locale.US, "options expensive (IV %.2f× real moves): buying premium lost money in history", m.ivRv));
        boolean avoid = !i.reasons.isEmpty();
        int lot1 = (int) Math.floor(cfg.riskBudget / Math.max(1, i.maxLoss));
        i.lots = Math.min(cfg.maxLots, Math.max(0, lot1));
        if (avoid) { i.signal = "AVOID"; return; }
        if (i.lots < 1) { i.signal = "WAIT"; i.reasons.add(String.format(Locale.US, "1 lot can lose ₹%,.0f — above your ₹%,.0f limit", i.maxLoss, cfg.riskBudget)); return; }
        if (!(i.ev > 0)) { i.signal = "WAIT"; i.reasons.add(String.format(Locale.US, "no edge: expected %s₹%,.0f a lot after ₹%,.0f costs", i.ev < 0 ? "−" : "", Math.abs(i.ev), i.costs)); return; }
        if (i.dir != 0 && i.dir != v.dir()) { i.signal = "WAIT"; i.reasons.add("against the AI's direction"); return; }
        if (!v.blocks.isEmpty()) { i.signal = "WAIT"; i.reasons.addAll(v.blocks); return; }
        if (v.act) { i.signal = "BUY"; return; }
        i.signal = "PAPER";
        i.reasons.add("PASS".equals(v.validation) ? "the AI's probability is below your trade threshold — paper only"
                : "the " + v.horizonLabel + " forecast has not passed pre-live validation" + (v.validation.isEmpty() ? "" : " (" + v.validation + ")") + " — paper only");
    }

    static void plan(Idea i, Chain c, View v, double sig, double w, double mv) {
        int lot = c.lot;
        i.exitBy = v.exitBy;
        boolean debit = i.net > 0;
        if (i.dir != 0) {
            double stop = c.spot * (1 - i.dir * 0.6 * sig), target = c.spot * (1 + i.dir * sig);
            if (i.kind.contains("credit")) stop = Double.isNaN(i.breakLo) ? stop : (i.dir > 0 ? Math.max(stop, nzb(i.breakLo, stop)) : Math.min(stop, nzb(i.breakLo, stop)));
            if (i.dir > 0) { i.exitBelow = stop; i.exitAbove = target; } else { i.exitAbove = stop; i.exitBelow = target; }
        } else if (i.name.startsWith("Iron condor")) {
            i.exitBelow = c.atm() - mv; i.exitAbove = c.atm() + mv;
        }
        if (debit) { i.takeProfit = Math.min(i.maxProfit, 0.6 * i.net * lot); i.stopLoss = -Math.min(i.maxLoss, 0.4 * i.net * lot + i.costs); }
        else { i.takeProfit = 0.5 * Math.max(0, i.maxProfit); i.stopLoss = -Math.min(i.maxLoss, -i.net * lot); }
        StringBuilder t = new StringBuilder();
        if (!Double.isNaN(i.exitBelow)) t.append(String.format(Locale.US, "Exit if Nifty goes below %,.0f", i.exitBelow));
        if (!Double.isNaN(i.exitAbove)) t.append(t.length() > 0 ? " or above " : "Exit if Nifty goes above ").append(String.format(Locale.US, "%,.0f", i.exitAbove));
        if (t.length() > 0) t.append(". ");
        t.append(String.format(Locale.US, "Take profit at +₹%,.0f a lot, stop at −₹%,.0f a lot", i.takeProfit, -i.stopLoss));
        if (i.exitBy > 0) t.append(", and be out by ").append(clock(i.exitBy));
        t.append('.');
        i.exitText = t.toString();
    }

    static double nzb(double x, double alt) { return Double.isNaN(x) ? alt : x; }

    // ------------------------------------------------------------------ positions (taken ideas) and exit signals

    public static final class Position {
        public String id = "", name = "", horizon = "", expiry = "";
        public int dir, lots = 1, lot = 65;
        public boolean paper = true, closed;
        public long openedAt, exitBy, closedAt;
        public double spotAtEntry, exitBelow = Double.NaN, exitAbove = Double.NaN, takeProfit, stopLoss, exitPnl = Double.NaN;
        public final List<Leg> legs = new ArrayList<>();
        public double net() { double n = 0; for (Leg g : legs) n += g.qty * g.price; return n; }
    }

    public static Position open(Idea i, Chain c, String horizon, int lots, boolean paper, long now) {
        Position p = new Position();
        p.id = Long.toString(now, 36);
        p.name = i.name; p.horizon = horizon; p.expiry = c.expiry; p.dir = i.dir;
        p.lots = Math.max(1, lots); p.lot = c.lot; p.paper = paper; p.openedAt = now; p.exitBy = i.exitBy; p.spotAtEntry = c.spot;
        p.exitBelow = i.exitBelow; p.exitAbove = i.exitAbove; p.takeProfit = i.takeProfit; p.stopLoss = i.stopLoss;
        for (Leg g : i.legs) { Leg x = new Leg(); x.call = g.call; x.strike = g.strike; x.qty = g.qty; x.iv = g.iv; x.price = g.price; p.legs.add(x); }
        return p;
    }

    public static final class Status {
        public double value = Double.NaN, pnl = Double.NaN;   // pnl: ₹ for all lots after costs
        public String signal = "HOLD";                        // HOLD / EXIT / NO PRICE
        public final List<String> reasons = new ArrayList<>();
    }

    /** Live check of a position: current P&L (after round-trip costs) and EXIT signals. pUpNow = AI's current P(up) for the horizon (NaN = unknown). */
    public static Status check(Position p, Chain c, double pUpNow, long now, Config cfg) {
        Status s = new Status();
        double v = 0, cost = 0;
        for (Leg g : p.legs) {
            double px = c.price(g.call, g.strike);
            if (Double.isNaN(px)) { s.signal = "NO PRICE"; s.reasons.add(String.format(Locale.US, "no price for %.0f %s", g.strike, g.call ? "CE" : "PE")); return s; }
            v += g.qty * px;
            cost += 2 * cfg.slip * p.lot + charges(g.price, px, p.lot);
        }
        s.value = v;
        s.pnl = ((v - p.net()) * p.lot - cost) * p.lots;
        double perLot = s.pnl / p.lots;
        if (!Double.isNaN(p.exitBelow) && c.spot < p.exitBelow) s.reasons.add(String.format(Locale.US, "Nifty %,.0f is below the exit level %,.0f", c.spot, p.exitBelow));
        if (!Double.isNaN(p.exitAbove) && c.spot > p.exitAbove) s.reasons.add(String.format(Locale.US, "Nifty %,.0f is above the exit level %,.0f", c.spot, p.exitAbove));
        if (perLot >= p.takeProfit && p.takeProfit > 0) s.reasons.add(String.format(Locale.US, "profit target reached (₹%,.0f a lot)", perLot));
        if (perLot <= p.stopLoss) s.reasons.add(String.format(Locale.US, "stop reached (₹%,.0f a lot)", perLot));
        if (p.exitBy > 0 && now >= p.exitBy) s.reasons.add("time is up for the " + p.horizon + " view");
        if (p.dir != 0 && !Double.isNaN(pUpNow) && (p.dir > 0 ? pUpNow <= 0.4 : pUpNow >= 0.6))
            s.reasons.add(String.format(Locale.US, "the AI now leans the other way (%.0f%% %s)", (p.dir > 0 ? 1 - pUpNow : pUpNow) * 100, p.dir > 0 ? "down" : "up"));
        if (c.days == 0 && toMinute(now) >= 15 * 60) s.reasons.add("expiry day after 15:00 — close before settlement");
        if (!s.reasons.isEmpty()) s.signal = "EXIT";
        return s;
    }

    public static void close(Position p, Status s, long now) { p.closed = true; p.closedAt = now; p.exitPnl = s == null ? Double.NaN : s.pnl; }

    public static JSONArray toJson(List<Position> l) throws Exception {
        JSONArray a = new JSONArray();
        for (Position p : l) {
            JSONArray legs = new JSONArray();
            for (Leg g : p.legs) legs.put(new JSONObject().put("c", g.call).put("k", g.strike).put("q", g.qty).put("p", g.price).put("iv", g.iv));
            JSONObject o = new JSONObject().put("id", p.id).put("name", p.name).put("h", p.horizon).put("exp", p.expiry).put("dir", p.dir)
                    .put("lots", p.lots).put("lot", p.lot).put("paper", p.paper).put("closed", p.closed).put("openedAt", p.openedAt)
                    .put("exitBy", p.exitBy).put("closedAt", p.closedAt).put("spot", p.spotAtEntry).put("tp", p.takeProfit).put("sl", p.stopLoss).put("legs", legs);
            if (!Double.isNaN(p.exitBelow)) o.put("below", p.exitBelow);
            if (!Double.isNaN(p.exitAbove)) o.put("above", p.exitAbove);
            if (!Double.isNaN(p.exitPnl)) o.put("pnl", p.exitPnl);
            a.put(o);
        }
        return a;
    }

    public static List<Position> fromJson(JSONArray a) {
        List<Position> l = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Position p = new Position();
            p.id = o.optString("id"); p.name = o.optString("name"); p.horizon = o.optString("h"); p.expiry = o.optString("exp"); p.dir = o.optInt("dir");
            p.lots = o.optInt("lots", 1); p.lot = o.optInt("lot", 65); p.paper = o.optBoolean("paper", true); p.closed = o.optBoolean("closed");
            p.openedAt = o.optLong("openedAt"); p.exitBy = o.optLong("exitBy"); p.closedAt = o.optLong("closedAt"); p.spotAtEntry = o.optDouble("spot", Double.NaN);
            p.takeProfit = o.optDouble("tp", 0); p.stopLoss = o.optDouble("sl", 0);
            p.exitBelow = o.optDouble("below", Double.NaN); p.exitAbove = o.optDouble("above", Double.NaN); p.exitPnl = o.optDouble("pnl", Double.NaN);
            JSONArray legs = o.optJSONArray("legs");
            for (int j = 0; legs != null && j < legs.length(); j++) {
                JSONObject g = legs.optJSONObject(j);
                if (g == null) continue;
                Leg x = new Leg(); x.call = g.optBoolean("c"); x.strike = g.optDouble("k"); x.qty = g.optInt("q"); x.price = g.optDouble("p"); x.iv = g.optDouble("iv", Double.NaN);
                p.legs.add(x);
            }
            l.add(p);
        }
        return l;
    }

    // ------------------------------------------------------------------ helpers

    /** NSE option charges for one round trip of one leg (₹): brokerage ₹20 an order, STT 0.15% of the sell premium, exchange, SEBI, stamp, GST. */
    public static double charges(double buyPrem, double sellPrem, int lot) {
        double b = buyPrem * lot, s = sellPrem * lot, t = b + s, brk = 40, ex = 0.0003503 * t, sebi = 1e-6 * t;
        return brk + 0.0015 * s + ex + sebi + 0.00003 * b + 0.18 * (brk + ex + sebi);
    }

    /** Annualised realised volatility from daily closes (last n returns), NaN if too short. */
    public static double realised(List<Double> closes, int n) {
        if (closes == null || closes.size() < n + 1) return Double.NaN;
        double s = 0, s2 = 0;
        for (int i = closes.size() - n; i < closes.size(); i++) {
            double r = Math.log(closes.get(i) / closes.get(i - 1));
            s += r; s2 += r * r;
        }
        double var = (s2 - s * s / n) / (n - 1);
        return Math.sqrt(Math.max(0, var) * 252);
    }

    /** When a trade on this horizon should be out: intraday = now + minutes (capped at 15:20), swing = 15:20 after n weekday sessions. */
    public static long exitBy(long now, int minutesAhead, int sessions) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"));
        c.setTimeInMillis(now);
        if (sessions <= 0) {
            c.add(Calendar.MINUTE, minutesAhead);
            int m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
            if (m > 15 * 60 + 20) { c.set(Calendar.HOUR_OF_DAY, 15); c.set(Calendar.MINUTE, 20); }
            return c.getTimeInMillis();
        }
        for (int n = 0; n < sessions; ) {
            c.add(Calendar.DAY_OF_MONTH, 1);
            int d = c.get(Calendar.DAY_OF_WEEK);
            if (d != Calendar.SATURDAY && d != Calendar.SUNDAY) n++;
        }
        c.set(Calendar.HOUR_OF_DAY, 15); c.set(Calendar.MINUTE, 20); c.set(Calendar.SECOND, 0);
        return c.getTimeInMillis();
    }

    static int toMinute(long ms) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"));
        c.setTimeInMillis(ms);
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }

    public static String clock(long ms) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"));
        Calendar n = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"));
        c.setTimeInMillis(ms);
        boolean today = c.get(Calendar.YEAR) == n.get(Calendar.YEAR) && c.get(Calendar.DAY_OF_YEAR) == n.get(Calendar.DAY_OF_YEAR);
        String hm = String.format(Locale.US, "%d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
        return today ? hm : String.format(Locale.US, "%s %d %s", hm, c.get(Calendar.DAY_OF_MONTH),
                new String[]{"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"}[c.get(Calendar.MONTH)]);
    }

    static double ncdf(double x) {
        double t = 1 / (1 + 0.2316419 * Math.abs(x));
        double d = 0.3989422804014327 * Math.exp(-x * x / 2);
        double p = d * t * (0.319381530 + t * (-0.356563782 + t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))));
        return x >= 0 ? 1 - p : p;
    }

    static double invNorm(double p) {
        double lo = -8, hi = 8;
        for (int i = 0; i < 80; i++) { double m = (lo + hi) / 2; if (ncdf(m) < p) lo = m; else hi = m; }
        return (lo + hi) / 2;
    }

    /** Black-Scholes price; v as a fraction. */
    public static double bs(boolean call, double s, double k, double t, double v) {
        if (t <= 0 || v <= 0) return Math.max(0, call ? s - k : k - s);
        double sq = v * Math.sqrt(t);
        double d1 = (Math.log(s / k) + (RATE + v * v / 2) * t) / sq, d2 = d1 - sq;
        double disc = Math.exp(-RATE * t);
        return call ? s * ncdf(d1) - k * disc * ncdf(d2) : k * disc * ncdf(-d2) - s * ncdf(-d1);
    }
}

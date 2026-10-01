package com.krish.niftydirection.engine;

import com.krish.niftydirection.model.OptionRow;

import com.krish.niftydirection.model.FlowPoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Option-chain maths: max pain, PCR, OI walls, and what writers / buyers did at each strike. */
public final class Chain {
    private Chain() {}

    /** What happened at one side of one strike, read from OI change + premium change. */
    public enum Action {
        WRITING("Writing"), SHORT_COVERING("Short covering"), BUYING("Buying"), UNWINDING("Long unwinding"), NONE("—");
        public final String label;
        Action(String l) { label = l; }
    }

    /** OI up + premium down = writing; OI down + premium up = short covering; both up = buying; both down = unwinding. */
    public static Action read(double dOi, double dPrem, double oi) {
        if (oi <= 0) return Action.NONE;
        if (Math.abs(dOi) < oi * 0.01 && Math.abs(dOi) < 5000) return Action.NONE;   // too small to mean anything
        if (dOi > 0 && dPrem <= 0) return Action.WRITING;
        if (dOi < 0 && dPrem > 0) return Action.SHORT_COVERING;
        if (dOi > 0) return Action.BUYING;
        return Action.UNWINDING;
    }

    /** The expiry price at which option buyers (all strikes together) get the least money. */
    public static double maxPain(List<OptionRow> rows) {
        double best = Double.NaN, bestPain = Double.MAX_VALUE;
        for (OptionRow k : rows) {
            double pain = 0;
            for (OptionRow s : rows) {
                if (k.strike > s.strike) pain += s.ceOi * (k.strike - s.strike);
                if (k.strike < s.strike) pain += s.peOi * (s.strike - k.strike);
            }
            if (pain < bestPain) { bestPain = pain; best = k.strike; }
        }
        return best;
    }

    public static double pcr(List<OptionRow> rows) {
        double ce = 0, pe = 0;
        for (OptionRow r : rows) { ce += r.ceOi; pe += r.peOi; }
        return ce > 0 ? pe / ce : Double.NaN;
    }

    /** Strike at or above spot with the most call OI (resistance). */
    public static OptionRow ceWall(List<OptionRow> rows, double spot) {
        OptionRow best = null;
        for (OptionRow r : rows) if (r.strike >= spot && (best == null || r.ceOi > best.ceOi)) best = r;
        return best;
    }

    /** Strike at or below spot with the most put OI (support). */
    public static OptionRow peWall(List<OptionRow> rows, double spot) {
        OptionRow best = null;
        for (OptionRow r : rows) if (r.strike <= spot && (best == null || r.peOi > best.peOi)) best = r;
        return best;
    }

    /**
     * Net "bullish flow" in contracts near the money (within `band` strikes of ATM).
     * Put writing, call short covering and call buying push it up;
     * call writing, put short covering and put buying push it down. Unwinding counts half.
     * Returns {netFlow, totalOiNearby, ceWritingOi, peWritingOi}.
     */
    public static double[] flow(List<OptionRow> rows, double spot, double step, int band) {
        double net = 0, total = 0, ceW = 0, peW = 0;
        for (OptionRow r : rows) {
            if (Math.abs(r.strike - spot) > band * step + step / 2) continue;
            total += r.ceOi + r.peOi;
            if (!r.hasPrev()) continue;
            double dc = r.ceDoi(), dp = r.peDoi();
            switch (read(dc, r.ceDp(), r.ceOi)) {
                case WRITING: net -= dc; ceW += dc; break;
                case SHORT_COVERING: net += -dc; break;
                case BUYING: net += 0.5 * dc; break;
                case UNWINDING: net -= 0.5 * -dc; break;
                default: break;
            }
            switch (read(dp, r.peDp(), r.peOi)) {
                case WRITING: net += dp; peW += dp; break;
                case SHORT_COVERING: net -= -dp; break;
                case BUYING: net -= 0.5 * dp; break;
                case UNWINDING: net += 0.5 * -dp; break;
                default: break;
            }
        }
        return new double[]{net, total, ceW, peW};
    }

    public static boolean anyPrev(List<OptionRow> rows) {
        for (OptionRow r : rows) if (r.hasPrev()) return true;
        return false;
    }

    // ================================================================== intraday flow (snapshot vs snapshot)

    /** What happened at one side of one strike between two snapshots, with how sure we are. */
    public static class Activity {
        public double strike; public boolean call;
        public double dOi, dPrem, dIv = Double.NaN, relIv = Double.NaN, residual = Double.NaN, dVol, liquidity;
        public Action action = Action.NONE;
        public boolean byIv;          // true = read mainly from (surface-adjusted) IV change
        public double confidence;     // 0..1: how sure the "probable" label is
        public double bull;           // signed, confidence-weighted contracts pushing Nifty up (+) or down (-)
        public boolean outer;         // true = outer layer (away from the money)

        public String shortLabel() {
            if (action == Action.NONE) return "·";
            String a = action == Action.WRITING ? "Write" : action == Action.SHORT_COVERING ? "Cover" : action == Action.BUYING ? "Buy" : "Unwind";
            return a + " " + Math.round(confidence * 100) + "%";
        }

        public String label() { return action == Action.NONE ? "—" : "Probable " + action.label.toLowerCase(java.util.Locale.US) + " (" + Math.round(confidence * 100) + "%)"; }
    }

    public static class Flow {
        public double net, minutes, volume, intensity = Double.NaN, surfaceIv = Double.NaN;
        public double ceWriting, peWriting, ceCovering, peCovering, ceBuying, peBuying;
        public double avgConfidence;
        public boolean byIv;
        public List<Activity> acts = new ArrayList<>();
        // outer layer (structure): where new walls are being built
        public double outerNet, outerVolume;
        public Activity newCallWall, newPutWall;
    }

    /** Settings that change on expiry day. */
    public static class FlowRules {
        public int nearBand = 5, outerBand = 15;
        public double minDoi = 1500, ivTrust = 1.0;
    }

    /**
     * Compare two option snapshots. For each strike side the "pressure" is judged three ways:
     *  - IV change minus the ATM IV change (so a market-wide IV fall does not look like writing everywhere),
     *  - premium change left over after the model price move from spot and time (delta / theta residual),
     *  - how much of the traded volume turned into new OI (much volume but little OI change = churn, less sure).
     * The label is "probable …" with a confidence. Thin strikes are skipped.
     * Near layer (ATM ±nearBand) gives direction; outer layer (up to ±outerBand) shows walls being built.
     * `t` = years to expiry now.
     */
    public static Flow intraday(FlowPoint ref, FlowPoint cur, List<OptionRow> rows, double spot, double step, double t, FlowRules rules) {
        Flow f = new Flow();
        f.minutes = cur.minute - ref.minute;
        double dSpot = cur.spot - ref.spot;
        if (!Double.isNaN(cur.atmIv) && !Double.isNaN(ref.atmIv)) f.surfaceIv = cur.atmIv - ref.atmIv;
        List<Double> vols = new ArrayList<>();
        List<Activity> raw = new ArrayList<>();
        for (OptionRow r : rows) {
            double dist = Math.abs(r.strike - spot);
            if (dist > rules.outerBand * step + step / 2) continue;
            boolean outer = dist > rules.nearBand * step + step / 2;
            for (int side = 0; side < 2; side++) {
                String sym = side == 0 ? r.ceSymbol : r.peSymbol;
                double[] a = ref.opt.get(sym), b = cur.opt.get(sym);
                if (a == null || b == null) continue;
                Activity x = new Activity();
                x.strike = r.strike; x.call = side == 0; x.outer = outer;
                x.dOi = b[0] - a[0]; x.dPrem = b[1] - a[1]; x.dVol = Math.max(0, b[2] - a[2]);
                if (!Double.isNaN(a[3]) && !Double.isNaN(b[3])) {
                    x.dIv = b[3] - a[3];
                    x.relIv = Double.isNaN(f.surfaceIv) ? x.dIv : x.dIv - f.surfaceIv;
                    double expected = Greeks.price(x.call, cur.spot, r.strike, t, a[3] / 100);   // same IV as before, today's spot and time
                    x.residual = b[1] - expected;
                } else {
                    // no IV: take out the part of the premium move that spot explains (delta from ATM IV)
                    double iv = !Double.isNaN(cur.atmIv) ? cur.atmIv / 100 : 0.14;
                    double d = Greeks.delta(x.call, cur.spot, r.strike, t, iv);
                    x.residual = x.dPrem - d * dSpot;
                }
                if (!outer) { vols.add(x.dVol); f.volume += x.dVol; } else f.outerVolume += x.dVol;
                raw.add(x);
            }
        }
        if (raw.isEmpty()) return f;
        java.util.Collections.sort(vols);
        double med = vols.isEmpty() ? 1 : Math.max(1, vols.get(vols.size() / 2));
        int ivCount = 0; double confSum = 0; int n = 0;
        for (Activity x : raw) {
            x.liquidity = Math.min(1.5, x.dVol / med);
            if (x.liquidity < 0.2 || Math.abs(x.dOi) < rules.minDoi) continue;   // thin or tiny: noise
            // pressure: + = buyers paying up, - = sellers pressing
            double ivP = Double.isNaN(x.relIv) ? Double.NaN : x.relIv;
            double resP = x.residual;
            double pressure;
            double conf;
            double build = Math.min(1, Math.abs(x.dOi) / Math.max(1, x.dVol) * 3);   // OI change vs volume: low = churn
            if (!Double.isNaN(ivP) && Math.abs(ivP) >= 0.1) {
                pressure = ivP;
                x.byIv = true; ivCount++;
                boolean agree = !Double.isNaN(resP) && Math.signum(resP) == Math.signum(ivP);
                conf = 0.30 + 0.30 * Math.tanh(Math.abs(ivP) / 0.4) * rules.ivTrust + (agree ? 0.15 : 0) + 0.10 * Math.min(1, x.liquidity) + 0.15 * build;
            } else if (!Double.isNaN(resP) && Math.abs(resP) > 0.05) {
                pressure = resP;
                conf = 0.25 + 0.15 * Math.tanh(Math.abs(resP) / 3) + 0.05 * Math.min(1, x.liquidity) + 0.10 * build;
            } else continue;   // no readable pressure
            x.confidence = Math.max(0.2, Math.min(0.95, conf));
            if (x.dOi > 0) x.action = pressure < 0 ? Action.WRITING : Action.BUYING;
            else x.action = pressure > 0 ? Action.SHORT_COVERING : Action.UNWINDING;
            double size = Math.abs(x.dOi) * Math.min(1.5, x.liquidity) * x.confidence;
            switch (x.action) {
                case WRITING: x.bull = x.call ? -size : size; break;
                case SHORT_COVERING: x.bull = x.call ? size : -size; break;
                case BUYING: x.bull = (x.call ? size : -size) * 0.5; break;
                case UNWINDING: x.bull = (x.call ? -size : size) * 0.5; break;
                default: break;
            }
            if (x.outer) {
                f.outerNet += x.bull;
                if (x.action == Action.WRITING && x.call && x.strike > spot && (f.newCallWall == null || x.dOi > f.newCallWall.dOi)) f.newCallWall = x;
                if (x.action == Action.WRITING && !x.call && x.strike < spot && (f.newPutWall == null || x.dOi > f.newPutWall.dOi)) f.newPutWall = x;
            } else {
                f.net += x.bull;
                confSum += x.confidence; n++;
                switch (x.action) {
                    case WRITING: if (x.call) f.ceWriting += x.dOi; else f.peWriting += x.dOi; break;
                    case SHORT_COVERING: if (x.call) f.ceCovering += -x.dOi; else f.peCovering += -x.dOi; break;
                    case BUYING: if (x.call) f.ceBuying += x.dOi; else f.peBuying += x.dOi; break;
                    default: break;
                }
            }
            f.acts.add(x);
        }
        f.avgConfidence = n > 0 ? confSum / n : 0;
        f.byIv = ivCount * 2 >= Math.max(1, f.acts.size());
        // intensity: net confidence-weighted OI change per contract traded (volume, not OI, as the yardstick)
        double minLiq = 50000;
        f.intensity = f.net / Math.max(f.volume, minLiq);
        f.acts.sort((p, q) -> Double.compare(Math.abs(q.bull), Math.abs(p.bull)));
        return f;
    }

    /** ATM implied volatility (average of the call and put nearest the money). */
    public static double atmIv(List<OptionRow> rows, double spot) {
        OptionRow best = null;
        for (OptionRow r : rows) if (best == null || Math.abs(r.strike - spot) < Math.abs(best.strike - spot)) best = r;
        if (best == null) return Double.NaN;
        double a = best.ceIv, b = best.peIv;
        if (Double.isNaN(a)) return b;
        if (Double.isNaN(b)) return a;
        return (a + b) / 2;
    }
}

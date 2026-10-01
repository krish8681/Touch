package com.krish.niftydirection.intel;

import com.krish.niftydirection.forecast.History;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;

/**
 * Cross-market event graph: shocks travel along chains of markets, so a driver is read through the path it takes to Nifty
 * instead of as an independent vote.
 *
 *   Oil shock        Brent → USD/INR → Nifty
 *   Rates & dollar   US 10Y → Dollar index → USD/INR → Nifty
 *   US rates → tech  US 10Y → Nasdaq → Nifty
 *
 * Every link's beta is learnt by regression over the 250 sessions BEFORE the date in question (no look-ahead), so the
 * chains are re-estimated as relationships change. A chain's implied Nifty move = root's last move × product of link betas.
 * The "consensus implied move" averages the direct one-step estimates of eight drivers.
 * The four values become the model group "Cross-market chains"; the live page shows each path with its numbers.
 */
public final class CrossMarket {
    private CrossMarket() {}

    public static final int WINDOW = 250, MIN_PAIRS = 60;
    static final String NIFTY = "NIFTY";

    public static final String[] CHAIN_NAMES = {"Oil shock", "Rates & dollar", "US rates → tech"};
    static final String[][] CHAINS = {
            {Markets.BRENT, Markets.USDINR, NIFTY},
            {Markets.US10Y, Markets.DXY, Markets.USDINR, NIFTY},
            {Markets.US10Y, Markets.NASDAQ, NIFTY}};
    static final String[] DIRECT = {Markets.SP, Markets.USFUT, Markets.US10Y, Markets.DXY, Markets.USDINR, Markets.BRENT, Markets.GOLD, Markets.USVIX};

    /** One chain as seen on a date: root move, link betas, implied Nifty move in %. */
    public static final class Path {
        public String name;
        public String[] nodes;
        public double rootMove = Double.NaN, implied = Double.NaN;
        public double[] beta, t;
        public String describe() {
            if (Double.isNaN(rootMove)) return name + ": no data";
            StringBuilder b = new StringBuilder(String.format(Locale.US, "%s: %s %+.2f%s", name, nodes[0], rootMove, Markets.isYield(nodes[0]) ? " (×10 bp)" : "%"));
            for (int i = 1; i < nodes.length; i++) {
                b.append(String.format(Locale.US, " → %s (β %+.2f%s)", nodes[i].equals(NIFTY) ? "Nifty" : nodes[i], beta[i - 1], Math.abs(t[i - 1]) >= 2 ? "" : ", weak"));
            }
            if (!Double.isNaN(implied)) b.append(String.format(Locale.US, " ⇒ Nifty %+.2f%%", implied));
            return b.toString();
        }
    }

    /** The four model inputs for `date`, in Nifty-% units: three chains + consensus. Cached per date in the History. */
    public static double[] implied(History h, String date) {
        String key = "xm:" + date;
        Object c = h.cache.get(key);
        if (c instanceof double[]) return (double[]) c;
        double[] out = new double[4];
        List<Path> ps = paths(h, date);
        for (int i = 0; i < 3; i++) out[i] = ps.get(i).implied;
        double s = 0; int n = 0;
        for (String d : DIRECT) {
            double[] bt = beta(h, d, NIFTY, date);
            double x = FeatureEngine.chg(h, d, date, 1);
            if (bt == null || Double.isNaN(x)) continue;
            s += bt[0] * x; n++;
        }
        out[3] = n >= 3 ? s / n : Double.NaN;
        h.cache.put(key, out);
        return out;
    }

    public static List<Path> paths(History h, String date) {
        List<Path> out = new ArrayList<>();
        for (int c = 0; c < CHAINS.length; c++) {
            Path p = new Path();
            p.name = CHAIN_NAMES[c]; p.nodes = CHAINS[c];
            p.beta = new double[p.nodes.length - 1]; p.t = new double[p.nodes.length - 1];
            p.rootMove = FeatureEngine.chg(h, p.nodes[0], date, 1);
            double prod = 1;
            boolean ok = true;
            for (int i = 0; i + 1 < p.nodes.length; i++) {
                double[] bt = beta(h, p.nodes[i], p.nodes[i + 1], date);
                if (bt == null) { ok = false; p.beta[i] = Double.NaN; p.t[i] = 0; continue; }
                p.beta[i] = bt[0]; p.t[i] = bt[1];
                prod *= bt[0];
            }
            if (ok && !Double.isNaN(p.rootMove)) p.implied = Math.max(-5, Math.min(5, p.rootMove * prod));
            out.add(p);
        }
        return out;
    }

    /** {beta, t} of `to` on `from` over the WINDOW sessions before `date`; null with too few pairs. Cached per date. */
    static double[] beta(History h, String from, String to, String date) {
        String key = "b:" + from + ">" + to + ":" + date;
        Object c = h.cache.get(key);
        if (c != null) return c instanceof double[] ? (double[]) c : null;
        NavigableMap<String, double[]> past = h.niftyDaily.headMap(date, false);
        double sx = 0, sy = 0, sxx = 0, sxy = 0, syy = 0;
        int n = 0;
        String prev = null;
        int seen = 0;
        for (Map.Entry<String, double[]> e : past.descendingMap().entrySet()) {
            if (seen++ > WINDOW) break;
            String d = e.getKey();
            Map.Entry<String, double[]> pe = past.lowerEntry(d);
            double x = FeatureEngine.chg(h, from, d, 1), y;
            if (to.equals(NIFTY)) y = pe != null && pe.getValue()[3] > 0 ? (e.getValue()[3] / pe.getValue()[3] - 1) * 100 : Double.NaN;
            else y = FeatureEngine.chg(h, to, d, 1);
            if (Double.isNaN(x) || Double.isNaN(y)) continue;
            sx += x; sy += y; sxx += x * x; sxy += x * y; syy += y * y; n++;
        }
        double[] out = null;
        if (n >= MIN_PAIRS) {
            double vx = sxx - sx * sx / n, vy = syy - sy * sy / n, cv = sxy - sx * sy / n;
            if (vx > 1e-12 && vy > 1e-12) {
                double r = cv / Math.sqrt(vx * vy);
                out = new double[]{cv / vx, r * Math.sqrt((n - 2) / Math.max(1e-9, 1 - r * r))};
            }
        }
        h.cache.put(key, out == null ? Boolean.FALSE : out);
        return out;
    }
}

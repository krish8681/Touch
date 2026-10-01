package com.krish.niftydirection.intel;

import com.krish.niftydirection.forecast.History;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Impact Graph: the relationships between drivers, LEARNT from the last year of sessions instead of assumed
 * (e.g. "a weaker rupee hurts Nifty" is measured, and may turn out weak or reversed in the current year).
 *
 * Edges: driver → Nifty (driver's last finished session before the Indian session vs Nifty's close-to-close move that day)
 * and driver → driver (both as known on the same Indian morning: crude → rupee, US yields → rupee, dollar → gold …).
 * Each edge carries the correlation, the beta (Nifty % per 1 unit of the driver) and a t-statistic.
 */
public final class ImpactGraph {
    private ImpactGraph() {}

    public static final int WINDOW = 250;

    public static final class Edge {
        public String from, to;
        public double corr, beta, t;
        public int n;
        public boolean significant() { return Math.abs(t) >= 2; }
    }

    static final String NIFTY = "NIFTY";
    static final String[][] PAIRS = {
            {Markets.SP, NIFTY}, {Markets.USFUT, NIFTY}, {Markets.NASDAQ, NIFTY}, {Markets.DAX, NIFTY}, {Markets.NIKKEI, NIFTY}, {Markets.HSI, NIFTY},
            {Markets.USVIX, NIFTY}, {Markets.US10Y, NIFTY}, {Markets.DXY, NIFTY}, {Markets.USDINR, NIFTY}, {Markets.BRENT, NIFTY},
            {Markets.GOLD, NIFTY}, {Markets.COPPER, NIFTY},
            {Markets.BRENT, Markets.USDINR}, {Markets.US10Y, Markets.USDINR}, {Markets.DXY, Markets.USDINR},
            {Markets.US10Y, Markets.GOLD}, {Markets.DXY, Markets.GOLD}, {Markets.BRENT, Markets.US10Y}, {Markets.SP, Markets.USDINR}};

    public static List<Edge> build(History h) {
        List<String> dates = new ArrayList<>(h.niftyDaily.keySet());
        List<Edge> out = new ArrayList<>();
        int start = Math.max(1, dates.size() - WINDOW);
        for (String[] p : PAIRS) {
            List<double[]> xy = new ArrayList<>();
            for (int i = start; i < dates.size(); i++) {
                String d = dates.get(i);
                double x = FeatureEngine.chg(h, p[0], d, 1);
                double y;
                if (p[1].equals(NIFTY)) {
                    double c0 = h.niftyDaily.get(dates.get(i - 1))[3], c1 = h.niftyDaily.get(d)[3];
                    y = c0 > 0 ? (c1 / c0 - 1) * 100 : Double.NaN;
                } else y = FeatureEngine.chg(h, p[1], d, 1);
                if (!Double.isNaN(x) && !Double.isNaN(y)) xy.add(new double[]{x, y});
            }
            Edge e = fit(p[0], p[1], xy);
            if (e != null) out.add(e);
        }
        out.sort((a, b) -> Double.compare(Math.abs(b.t), Math.abs(a.t)));
        return out;
    }

    static Edge fit(String from, String to, List<double[]> xy) {
        int n = xy.size();
        if (n < 40) return null;
        double mx = 0, my = 0;
        for (double[] v : xy) { mx += v[0]; my += v[1]; }
        mx /= n; my /= n;
        double sxx = 0, syy = 0, sxy = 0;
        for (double[] v : xy) { sxx += (v[0] - mx) * (v[0] - mx); syy += (v[1] - my) * (v[1] - my); sxy += (v[0] - mx) * (v[1] - my); }
        if (sxx <= 0 || syy <= 0) return null;
        Edge e = new Edge();
        e.from = from; e.to = to; e.n = n;
        e.corr = sxy / Math.sqrt(sxx * syy);
        e.beta = sxy / sxx;
        e.t = e.corr * Math.sqrt((n - 2) / Math.max(1e-9, 1 - e.corr * e.corr));
        return e;
    }

    public static JSONArray toJson(List<Edge> edges) throws Exception {
        JSONArray a = new JSONArray();
        for (Edge e : edges) a.put(new JSONObject().put("from", e.from).put("to", e.to).put("corr", e.corr).put("beta", e.beta).put("t", e.t).put("n", e.n));
        return a;
    }

    public static List<Edge> fromJson(JSONArray a) throws Exception {
        List<Edge> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            Edge e = new Edge();
            e.from = o.optString("from"); e.to = o.optString("to"); e.corr = o.optDouble("corr", 0); e.beta = o.optDouble("beta", 0);
            e.t = o.optDouble("t", 0); e.n = o.optInt("n");
            out.add(e);
        }
        return out;
    }

    /** "Brent crude → USD/INR: +0.31 (strong)" */
    public static String describe(Edge e) {
        String to = e.to.equals(NIFTY) ? "Nifty" : e.to;
        String s = Math.abs(e.corr) >= 0.4 ? "strong" : Math.abs(e.corr) >= 0.2 ? "moderate" : "weak";
        return String.format(java.util.Locale.US, "%s → %s: corr %+.2f, %s%s", e.from, to, e.corr, s, e.significant() ? "" : " (not significant)");
    }
}

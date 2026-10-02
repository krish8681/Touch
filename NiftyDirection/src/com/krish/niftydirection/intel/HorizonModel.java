package com.krish.niftydirection.intel;

import com.krish.niftydirection.forecast.LogReg;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Everything one horizon has learnt: a model per feature group, the meta model that blends them (with regime interactions),
 * the calibration map, the expected-range table and the walk-forward test results.
 */
public final class HorizonModel {
    public static final int VERSION = 4;   // 4: weighted fits, group gating, shrinkage, leaders + positioning (3: 2-hour, Day close)
    public static final int G = FeatureEngine.GROUPS.length;

    public String id;
    public int version = VERSION;
    public long at;
    public final LogReg[] groups = new LogReg[G];
    public LogReg meta;
    public Calibrator calib = new Calibrator();
    /** Groups the meta model may use (false = its out-of-sample predictions did not beat the base rate). */
    public boolean[] use = filled(G);
    /** Final shrink toward the base rate: p = base + shrink × (calibrated − base), shrink chosen on unseen sessions. */
    public double shrink = 1, base = Double.NaN;

    static boolean[] filled(int n) { boolean[] b = new boolean[n]; java.util.Arrays.fill(b, true); return b; }
    public final Info info = new Info();

    public static final class Info {
        public int samples, days;
        public String from = "", to = "";
        public double upShare = Double.NaN;
        // walk-forward test on sessions the meta model never saw (group models only ever saw earlier data)
        public String testFrom = "";
        public int testDays, testSamples;
        public double hit = Double.NaN, baseHit = Double.NaN, brier = Double.NaN, baseBrier = Double.NaN, logLoss = Double.NaN, baseLogLoss = Double.NaN;
        public double skill = Double.NaN, skillLo = Double.NaN, skillHi = Double.NaN;   // Brier skill and its 90% block-bootstrap band
        public boolean proven;
        /** Reliability curve on the test: per bin {count, mean predicted P(up), share that actually went up}. */
        public double[][] reliability = new double[0][];
        /** Test hit rate by regime: label → {count, model hit, base hit}. */
        public Map<String, double[]> byRegime = new LinkedHashMap<>();
        /** |move| / horizon volatility quantiles {50%, 68%, 90%} per volatility bucket {low, normal, high}. */
        public double[][] range = new double[3][];
        /** Per volatility bucket: share of moves smaller than FLAT_Z × horizon volatility ("flat"), mean |move| / horizon vol,
         *  and the median highest-high / lowest-low excursion before the target, in horizon-vol units. */
        public double[] flat = new double[3], meanAbs = new double[3], excUp = new double[3], excDn = new double[3];
        /** Learnt share of each feature group in the meta model (adds to 100). */
        public double[] importance = new double[G];
        /** Shrink toward the base rate chosen on unseen sessions (1 = trusted fully, 0 = always the base rate); groups the meta model uses. */
        public double shrink = 1;
        public String used = "";
        public boolean tested() { return testDays > 0 && !Double.isNaN(brier); }
    }

    // ================================================================== prediction

    public static final class Output {
        public double pRaw = Double.NaN, p = Double.NaN;   // meta probability, calibrated probability of UP
        public final double[] groupP = new double[G];
        /** Each group's push on the meta logit (regime interactions included). + = towards UP. */
        public final double[] contrib = new double[G];
        public double intercept;
        /** Probability points per logit unit around the current forecast (for showing contributions in points). */
        public double ptsPerLogit;
    }

    /** Meta inputs: each group's logit, and the same × range flag and × high-volatility flag. */
    static double[] metaRow(double[] groupP, Regime r) { return metaRow(groupP, r, null); }

    static double[] metaRow(double[] groupP, Regime r, boolean[] use) {
        double[] m = new double[3 * G];
        double rg = r.rangeFlag(), hv = r.highVolFlag();
        for (int g = 0; g < G; g++) {
            double l = Double.isNaN(groupP[g]) || (use != null && !use[g]) ? 0 : logit(groupP[g]);
            m[g] = l; m[G + g] = l * rg; m[2 * G + g] = l * hv;
        }
        return m;
    }

    public static double[] groupRow(double[] f, int g) {
        int[] idx = FeatureEngine.GROUPS[g];
        double[] x = new double[idx.length];
        for (int i = 0; i < idx.length; i++) x[i] = f[idx[i]];
        return x;
    }

    public Output predict(double[] f, Regime r) {
        Output o = new Output();
        for (int g = 0; g < G; g++) o.groupP[g] = groups[g] == null ? Double.NaN : groups[g].predict(groupRow(f, g));
        if (meta == null) return o;
        double[] m = metaRow(o.groupP, r, use);
        double[] z = new double[m.length + 1];
        meta.row(m, z);
        o.intercept = meta.w[0];
        double s = meta.w[0];
        for (int j = 0; j < m.length; j++) { double c = meta.w[j + 1] * z[j + 1]; o.contrib[j % G] += c; s += c; }
        o.pRaw = sig(s);
        o.p = shrunk(calib.apply(o.pRaw));
        double slope = (shrunk(calib.apply(Math.min(0.999, o.pRaw + 0.01))) - shrunk(calib.apply(Math.max(0.001, o.pRaw - 0.01)))) / 0.02;
        o.ptsPerLogit = Math.max(0, slope) * o.pRaw * (1 - o.pRaw) * 100;
        return o;
    }

    double shrunk(double p) { return Double.isNaN(base) || Double.isNaN(p) ? p : base + shrink * (p - base); }

    /** The inputs inside group g that push hardest in direction `sign`: {feature index, push} pairs, strongest first. */
    public double[][] topInputs(double[] f, int g, double sign, int max) {
        LogReg lr = groups[g];
        if (lr == null) return new double[0][];
        double[] x = groupRow(f, g), z = new double[x.length + 1];
        lr.row(x, z);
        int[] idx = FeatureEngine.GROUPS[g];
        java.util.List<double[]> l = new java.util.ArrayList<>();
        for (int j = 0; j < x.length; j++) {
            double c = lr.w[j + 1] * z[j + 1];
            if (Math.signum(c) == Math.signum(sign) && Math.abs(c) > 0.01) l.add(new double[]{idx[j], c, z[j + 1]});
        }
        l.sort((a, b) -> Double.compare(Math.abs(b[1]), Math.abs(a[1])));
        return l.subList(0, Math.min(max, l.size())).toArray(new double[0][]);
    }

    /** Expected 68% / 90% move as a fraction of price, given the daily volatility and the volatility bucket. */
    public double[] expectedRange(double sigmaDaily, int volBucket, Horizon hz) { return expectedRange(sigmaDaily, volBucket, hz, 0); }

    public double[] expectedRange(double sigmaDaily, int volBucket, Horizon hz, int k) {
        double[] q = info.range[volBucket] != null ? info.range[volBucket] : info.range[1];
        if (q == null || Double.isNaN(sigmaDaily)) return new double[]{Double.NaN, Double.NaN, Double.NaN};
        double sh = sigmaDaily * Math.sqrt(hz.minutesAt(k) / 375.0);
        return new double[]{q[0] * sh, q[1] * sh, q[2] * sh};
    }

    static double sig(double v) { return v > 30 ? 1 : v < -30 ? 0 : 1 / (1 + Math.exp(-v)); }
    static double logit(double p) { p = Math.max(0.01, Math.min(0.99, p)); return Math.log(p / (1 - p)); }

    // ================================================================== storage

    public JSONObject toJson() throws Exception {
        JSONObject j = new JSONObject().put("id", id).put("version", version).put("at", at);
        JSONArray gs = new JSONArray();
        for (LogReg g : groups) gs.put(g == null ? new JSONObject() : lr(g));
        j.put("groups", gs);
        if (meta != null) j.put("meta", lr(meta));
        j.put("calib", calib.toJson());
        StringBuilder us = new StringBuilder();
        for (boolean b : use) us.append(b ? '1' : '0');
        j.put("use", us.toString()).put("shrink", shrink);
        if (!Double.isNaN(base)) j.put("base", base);
        Info i = info;
        JSONObject ij = new JSONObject().put("samples", i.samples).put("days", i.days).put("from", i.from).put("to", i.to).put("upShare", nz(i.upShare))
                .put("testFrom", i.testFrom).put("testDays", i.testDays).put("testSamples", i.testSamples)
                .put("hit", nz(i.hit)).put("baseHit", nz(i.baseHit)).put("brier", nz(i.brier)).put("baseBrier", nz(i.baseBrier))
                .put("logLoss", nz(i.logLoss)).put("baseLogLoss", nz(i.baseLogLoss))
                .put("skill", nz(i.skill)).put("skillLo", nz(i.skillLo)).put("skillHi", nz(i.skillHi)).put("proven", i.proven)
                .put("shrink", i.shrink).put("used", i.used);
        JSONArray rel = new JSONArray();
        for (double[] b : i.reliability) rel.put(arr(b));
        ij.put("reliability", rel);
        JSONObject br = new JSONObject();
        for (Map.Entry<String, double[]> e : i.byRegime.entrySet()) br.put(e.getKey(), arr(e.getValue()));
        ij.put("byRegime", br);
        JSONArray rg = new JSONArray();
        for (double[] q : i.range) rg.put(q == null ? new JSONArray() : arr(q));
        ij.put("range", rg);
        ij.put("importance", arr(i.importance)).put("flat", arr(i.flat)).put("meanAbs", arr(i.meanAbs)).put("excUp", arr(i.excUp)).put("excDn", arr(i.excDn));
        return j.put("info", ij);
    }

    public static HorizonModel fromJson(JSONObject j) throws Exception {
        HorizonModel m = new HorizonModel();
        m.id = j.getString("id"); m.version = j.optInt("version", 0); m.at = j.optLong("at", 0);
        JSONArray gs = j.optJSONArray("groups");
        for (int g = 0; gs != null && g < Math.min(G, gs.length()); g++) {
            LogReg lr = lr(gs.getJSONObject(g));
            if (lr != null && lr.mean.length == FeatureEngine.GROUPS[g].length) m.groups[g] = lr;
        }
        if (j.has("meta")) { m.meta = lr(j.getJSONObject("meta")); if (m.meta != null && m.meta.mean.length != 3 * G) m.meta = null; }
        m.calib = Calibrator.fromJson(j.optJSONObject("calib"));
        String us = j.optString("use", "");
        for (int g = 0; g < Math.min(G, us.length()); g++) m.use[g] = us.charAt(g) == '1';
        m.shrink = j.optDouble("shrink", 1);
        m.base = j.optDouble("base", Double.NaN);
        JSONObject ij = j.getJSONObject("info");
        Info i = m.info;
        i.samples = ij.optInt("samples"); i.days = ij.optInt("days"); i.from = ij.optString("from"); i.to = ij.optString("to"); i.upShare = nan(ij, "upShare");
        i.testFrom = ij.optString("testFrom"); i.testDays = ij.optInt("testDays"); i.testSamples = ij.optInt("testSamples");
        i.hit = nan(ij, "hit"); i.baseHit = nan(ij, "baseHit"); i.brier = nan(ij, "brier"); i.baseBrier = nan(ij, "baseBrier");
        i.logLoss = nan(ij, "logLoss"); i.baseLogLoss = nan(ij, "baseLogLoss");
        i.skill = nan(ij, "skill"); i.skillLo = nan(ij, "skillLo"); i.skillHi = nan(ij, "skillHi"); i.proven = ij.optBoolean("proven", false);
        i.shrink = ij.optDouble("shrink", 1); i.used = ij.optString("used", "");
        JSONArray rel = ij.optJSONArray("reliability");
        if (rel != null) { i.reliability = new double[rel.length()][]; for (int b = 0; b < rel.length(); b++) i.reliability[b] = darr(rel.getJSONArray(b)); }
        JSONObject br = ij.optJSONObject("byRegime");
        if (br != null) { java.util.Iterator<String> it = br.keys(); while (it.hasNext()) { String k = it.next(); i.byRegime.put(k, darr(br.getJSONArray(k))); } }
        JSONArray rg = ij.optJSONArray("range");
        for (int b = 0; rg != null && b < Math.min(3, rg.length()); b++) { double[] q = darr(rg.getJSONArray(b)); i.range[b] = q.length == 3 ? q : null; }
        JSONArray im = ij.optJSONArray("importance");
        if (im != null && im.length() == G) i.importance = darr(im);
        i.flat = arr3(ij, "flat"); i.meanAbs = arr3(ij, "meanAbs"); i.excUp = arr3(ij, "excUp"); i.excDn = arr3(ij, "excDn");
        return m;
    }

    static double[] arr3(JSONObject j, String k) throws Exception {
        JSONArray a = j.optJSONArray(k);
        return a != null && a.length() == 3 ? darr(a) : new double[]{Double.NaN, Double.NaN, Double.NaN};
    }

    /** Moves smaller than this many horizon-volatilities count as "flat" (about one in five). */
    public static final double FLAT_Z = 0.25;

    /**
     * Three-way outlook from P(up) and the flat share of this volatility bucket (direction and size treated as independent):
     * {P(up), P(flat), P(down), expected return (fraction), typical high, typical low (fractions vs price)}.
     * The high/low excursions are tilted toward the forecast side: at P(up) 0.7 the up-excursion grows ×1.2 and the down one shrinks ×0.8.
     */
    public double[] outlook(double pUp, double sigmaDaily, int bucket, Horizon hz) { return outlook(pUp, sigmaDaily, bucket, hz, 0); }

    public double[] outlook(double pUp, double sigmaDaily, int bucket, Horizon hz, int k) {
        double sh = sigmaDaily * Math.sqrt(hz.minutesAt(k) / 375.0);
        double fl = pick(info.flat, bucket), ma = pick(info.meanAbs, bucket), eu = pick(info.excUp, bucket), ed = pick(info.excDn, bucket);
        double[] o = new double[6];
        java.util.Arrays.fill(o, Double.NaN);
        if (Double.isNaN(pUp)) return o;
        if (!Double.isNaN(fl)) { o[1] = fl; o[0] = pUp * (1 - fl); o[2] = (1 - pUp) * (1 - fl); }
        if (!Double.isNaN(ma) && !Double.isNaN(sh)) o[3] = (2 * pUp - 1) * ma * sh;
        if (!Double.isNaN(eu) && !Double.isNaN(sh)) o[4] = Math.exp(eu * sh * (0.5 + pUp)) - 1;
        if (!Double.isNaN(ed) && !Double.isNaN(sh)) o[5] = Math.exp(-ed * sh * (1.5 - pUp)) - 1;
        return o;
    }

    static double pick(double[] v, int b) { return v == null || v.length < 3 ? Double.NaN : !Double.isNaN(v[b]) ? v[b] : v[1]; }

    static JSONObject lr(LogReg l) throws Exception { return new JSONObject().put("w", arr(l.w)).put("mean", arr(l.mean)).put("sd", arr(l.sd)); }

    static LogReg lr(JSONObject j) throws Exception {
        if (j == null || !j.has("w")) return null;
        LogReg l = new LogReg();
        l.w = darr(j.getJSONArray("w")); l.mean = darr(j.getJSONArray("mean")); l.sd = darr(j.getJSONArray("sd"));
        return l.w.length == l.mean.length + 1 ? l : null;
    }

    static double nz(double v) { return Double.isNaN(v) || Double.isInfinite(v) ? -999 : v; }
    static double nan(JSONObject j, String k) { double v = j.optDouble(k, -999); return v == -999 ? Double.NaN : v; }
    static JSONArray arr(double[] v) throws Exception { JSONArray a = new JSONArray(); for (double x : v) a.put(Double.isNaN(x) || Double.isInfinite(x) ? 0 : x); return a; }
    static double[] darr(JSONArray a) throws Exception { double[] v = new double[a.length()]; for (int i = 0; i < v.length; i++) v[i] = a.getDouble(i); return v; }
}

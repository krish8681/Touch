package com.krish.niftydirection.forecast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * One question per horizon: "from now, will Nifty be HIGHER or LOWER after 1 hour / 3 hours / 6 hours of trading,
 * and at the next session's close?" (trading time only — nights and weekends are skipped, so a 1-hour forecast
 * at 15:30 is about 10:15 next session).
 * Each horizon has one model, learnt from every 15 minutes of every past session. The live forecast is that model's probability.
 */
public final class Forecaster {
    private Forecaster() {}

    public static final int STEP = 3, ITERS = 12;
    public static final double LAMBDA = 10;
    public static final int VERSION = 4;
    /** Walk-forward test: the most recent sessions are held back, the model learns from the older ones only. */
    public static final int TEST_DAYS = 120, MIN_TEST_DAYS = 60;

    public static final class Horizon {
        public final String id, label;
        public final int bars;       // trading bars ahead; -1 = next session's close
        public final int minutes;    // for counting overlapping samples
        Horizon(String id, String label, int bars, int minutes) { this.id = id; this.label = label; this.bars = bars; this.minutes = minutes; }
    }

    public static final Horizon[] HORIZONS = {
            new Horizon("1H", "Next 1 hour", 12, 60),
            new Horizon("3H", "Next 3 hours", 36, 180),
            new Horizon("6H", "Next 6 hours", 72, 360),
            new Horizon("1D", "Next session's close", -1, 375)};

    public static Horizon horizon(String id) { for (Horizon h : HORIZONS) if (h.id.equals(id)) return h; return null; }

    // ================================================================== samples

    public static final class Samples {
        public final List<double[]> X = new ArrayList<>();
        public final List<Integer> y = new ArrayList<>(), day = new ArrayList<>(), tday = new ArrayList<>(), k = new ArrayList<>();
        public final List<Double> move = new ArrayList<>();
        public int size() { return y.size(); }
    }

    /** Target of a forecast made after k bars of session d: {target session index, price then}, or null if unknown. */
    public static double[] target(History h, int d, int k, Horizon hz, int[] sidx) {
        int dd, slot;
        if (hz.bars < 0) { dd = k == 0 ? d : d + 1; slot = History.BARS - 1; }   // from the open: today's close
        else { int idx = k - 1 + hz.bars; dd = d + idx / History.BARS; slot = idx % History.BARS; }
        if (dd >= h.days.size() || sidx[dd] - sidx[d] != dd - d) return null;
        History.Day t = h.days.get(dd);
        if (t.bars < slot + 1) return null;
        double v = t.closeAt(slot + 1);
        return Double.isNaN(v) ? null : new double[]{dd, v};
    }

    public static Samples samples(History h, Horizon hz) { return samples(h, hz, false); }

    /** open = true: one sample per session AT THE OPEN (only the opening price known) — for the pre-open forecast. */
    public static Samples samples(History h, Horizon hz, boolean open) {
        int[] sidx = h.sessionIndex();
        Samples s = new Samples();
        for (int d = 0; d < h.days.size(); d++) {
            History.Day day = h.days.get(d);
            if (day.bars < History.BARS) continue;
            for (int k = open ? 0 : STEP; k <= (open ? 0 : History.BARS); k += STEP) {
                double p = k == 0 ? day.open() : day.closeAt(k);
                double[] t = target(h, d, k, hz, sidx);
                if (t == null || Double.isNaN(p) || t[1] == p) continue;
                s.X.add(Features.compute(h, d, k, sidx));
                s.y.add(t[1] > p ? 1 : 0);
                s.day.add(d); s.tday.add((int) t[0]); s.k.add(k);
                s.move.add(Math.log(t[1] / p));
            }
        }
        return s;
    }

    // ================================================================== model

    /** What the model learnt from (no scores — just the size of the history and the usual move). */
    public static final class Info {
        public String id, label, from = "", to = "";
        public int samples, days;
        public double upShare = Double.NaN, medMove = Double.NaN;   // medMove = median |log move| over the horizon
        // walk-forward test on sessions the model never saw (NaN / 0 = not tested)
        public String testFrom = "";
        public int testDays, testSamples;
        public double hit = Double.NaN, baseHit = Double.NaN;       // share called right: model vs always guessing the usual side
        public double brier = Double.NaN, baseBrier = Double.NaN;   // probability error: model vs the usual up-share

        public boolean tested() { return testDays >= MIN_TEST_DAYS && !Double.isNaN(brier); }
        /** Beat the "always the usual side" guess on unseen sessions, both in calls and in probability error. */
        public boolean proven() { return tested() && brier < baseBrier && hit > baseHit; }
        /** 1 - brier / baseBrier: above 0 = better than the base rate, below 0 = worse. */
        public double skill() { return tested() && baseBrier > 0 ? 1 - brier / baseBrier : Double.NaN; }
    }

    public static final class Model {
        public String id;
        public boolean open;   // true = pre-open model (forecast made at the open, from the expected opening price)
        public int version = VERSION;
        public long at;
        public LogReg lr;
        public Info info;
    }

    public static Model train(History h, Horizon hz) { return train(h, hz, false); }

    public static Model train(History h, Horizon hz, boolean open) {
        Samples s = samples(h, hz, open);
        Model m = new Model();
        m.id = hz.id;
        m.open = open;
        m.at = System.currentTimeMillis();
        Info in = new Info();
        in.id = hz.id; in.label = hz.label;
        int n = s.size();
        in.samples = n;
        m.info = in;
        if (n < 200) return m;
        int ups = 0;
        for (int v : s.y) ups += v;
        in.upShare = ups / (double) n;
        double[] mv = new double[n];
        for (int i = 0; i < n; i++) mv[i] = Math.abs(s.move.get(i));
        Arrays.sort(mv);
        in.medMove = mv[n / 2];
        java.util.TreeSet<Integer> dd = new java.util.TreeSet<>(s.day);
        in.days = dd.size();
        in.from = h.days.get(dd.first()).date;
        in.to = h.days.get(dd.last()).date;
        double[][] X = s.X.toArray(new double[0][]);
        int[] y = toInt(s.y), rows = new int[n];
        for (int i = 0; i < n; i++) rows[i] = i;
        evaluate(h, s, X, y, in);
        m.lr = LogReg.fit(X, y, rows, n, LAMBDA, ITERS);
        return m;
    }

    /**
     * Walk-forward test: learn from the older sessions, score the last TEST_DAYS sessions.
     * Training samples whose target falls inside the test period are dropped (no overlap between the two).
     */
    static void evaluate(History h, Samples s, double[][] X, int[] y, Info in) {
        java.util.TreeSet<Integer> dd = new java.util.TreeSet<>(s.day);
        int nTest = Math.min(TEST_DAYS, dd.size() / 5);
        if (nTest < MIN_TEST_DAYS) return;
        Integer[] days = dd.toArray(new Integer[0]);
        int first = days[days.length - nTest];
        int n = s.size(), nTr = 0, nTe = 0;
        int[] tr = new int[n], te = new int[n];
        for (int i = 0; i < n; i++) {
            if (s.day.get(i) >= first) te[nTe++] = i;
            else if (s.tday.get(i) < first) tr[nTr++] = i;
        }
        if (nTr < 200 || nTe < 50) return;
        LogReg lr = LogReg.fit(X, y, tr, nTr, LAMBDA, ITERS);
        int ups = 0;
        for (int r = 0; r < nTr; r++) ups += y[tr[r]];
        double base = ups / (double) nTr;
        double hit = 0, baseHit = 0, br = 0, bb = 0;
        for (int r = 0; r < nTe; r++) {
            int i = te[r];
            double p = lr.predict(X[i]);
            if ((p >= 0.5 ? 1 : 0) == y[i]) hit++;
            if ((base >= 0.5 ? 1 : 0) == y[i]) baseHit++;
            br += (p - y[i]) * (p - y[i]);
            bb += (base - y[i]) * (base - y[i]);
        }
        in.testFrom = h.days.get(first).date;
        in.testDays = nTest;
        in.testSamples = nTe;
        in.hit = hit / nTe; in.baseHit = baseHit / nTe;
        in.brier = br / nTe; in.baseBrier = bb / nTe;
    }

    // ================================================================== live

    public static final class Prediction {
        public String id, label, when = "";
        public double pUp = Double.NaN, moveMedPts = Double.NaN, price = Double.NaN;
        public Info info;
        public final List<String> reasons = new ArrayList<>();
        public final List<Double> pushes = new ArrayList<>();   // + = towards UP
    }

    /** Forecast after k completed bars of session d (the last session in h for live use). */
    public static Prediction predict(Model m, History h, int d, int k) {
        Horizon hz = horizon(m.id);
        Prediction p = new Prediction();
        p.id = m.id; p.label = hz.label; p.info = m.info;
        if (m.open) p.label = hz.bars < 0 ? "Today's close vs the open" : hz.label.replace("Next", "First") + " after the open";
        int[] sidx = h.sessionIndex();
        History.Day day = h.days.get(d);
        p.price = k == 0 ? day.open() : day.closeAt(k);
        p.when = when(hz, k);
        if (m.info != null && !Double.isNaN(m.info.medMove) && !Double.isNaN(p.price)) p.moveMedPts = m.info.medMove * p.price;
        if (m.lr == null) return p;
        double[] x = Features.compute(h, d, k, sidx);
        p.pUp = m.lr.predict(x);
        double[] z = new double[x.length + 1];
        m.lr.row(x, z);
        final double[] c = new double[x.length];
        for (int j = 0; j < x.length; j++) c[j] = m.lr.w[j + 1] * z[j + 1];
        // Inputs that move together (e.g. 20- and 50-day averages) can get opposite weights that cancel,
        // so reasons are shown per GROUP: the group's net push, and the input inside it that pushes hardest that way.
        Integer[] ord = new Integer[Features.GROUPS.length];
        final double[] net = new double[Features.GROUPS.length];
        for (int g = 0; g < net.length; g++) { ord[g] = g; for (int j : Features.GROUPS[g]) net[g] += c[j]; }
        Arrays.sort(ord, (a, b) -> Double.compare(Math.abs(net[b]), Math.abs(net[a])));
        for (int i = 0; i < Math.min(4, ord.length); i++) {
            int g = ord[i];
            if (Math.abs(net[g]) < 0.02) break;
            int main = -1;
            for (int j : Features.GROUPS[g]) if (Math.signum(c[j]) == Math.signum(net[g]) && (main < 0 || Math.abs(c[j]) > Math.abs(c[main]))) main = j;
            String size = Math.abs(net[g]) >= 0.15 ? "strongly" : Math.abs(net[g]) >= 0.05 ? "" : "slightly";
            String line = Features.GROUP_NAMES[g] + " → points " + (size.isEmpty() ? "" : size + " ") + (net[g] > 0 ? "UP" : "DOWN");
            if (main >= 0 && Features.GROUPS[g].length > 1)
                line += " (mainly: " + Features.NAMES[main].toLowerCase(Locale.US) + (main == 26 ? ")" : (z[main + 1] > 0 ? " high)" : " low)"));
            p.reasons.add(line);
            p.pushes.add(net[g]);
        }
        return p;
    }

    /** "by 11:45" / "by 10:15 next session" / "at next session's close". */
    public static String when(Horizon hz, int k) {
        if (hz.bars < 0) return k == 0 ? "at today's close" : k >= History.BARS ? "at next session's close" : "at next session's close (not today's)";
        int idx = k - 1 + hz.bars, dd = idx / History.BARS, slot = idx % History.BARS;
        int m = History.OPEN_MIN + 5 * (slot + 1);
        String t = String.format(Locale.US, "%d:%02d", m / 60, m % 60);
        return "by " + t + (dd == 0 ? " today" : dd == 1 ? " next session" : " in " + dd + " sessions");
    }

    // ================================================================== storage

    public static JSONObject toJson(Model m) throws Exception {
        JSONObject j = new JSONObject();
        j.put("id", m.id).put("open", m.open).put("version", m.version).put("at", m.at);
        if (m.lr != null) j.put("w", arr(m.lr.w)).put("mean", arr(m.lr.mean)).put("sd", arr(m.lr.sd));
        Info i = m.info;
        j.put("info", new JSONObject().put("id", i.id).put("label", i.label).put("from", i.from).put("to", i.to)
                .put("samples", i.samples).put("days", i.days).put("upShare", nz(i.upShare)).put("medMove", nz(i.medMove))
                .put("testFrom", i.testFrom).put("testDays", i.testDays).put("testSamples", i.testSamples)
                .put("hit", nz(i.hit)).put("baseHit", nz(i.baseHit)).put("brier", nz(i.brier)).put("baseBrier", nz(i.baseBrier)));
        return j;
    }

    public static Model fromJson(JSONObject j) throws Exception {
        Model m = new Model();
        m.id = j.getString("id"); m.open = j.optBoolean("open", false); m.version = j.optInt("version", 0); m.at = j.optLong("at", 0);
        if (j.has("w")) {
            m.lr = new LogReg();
            m.lr.w = darr(j.getJSONArray("w")); m.lr.mean = darr(j.getJSONArray("mean")); m.lr.sd = darr(j.getJSONArray("sd"));
            if (m.lr.mean.length != Features.N) m.lr = null;   // model from an older input list
        }
        JSONObject ij = j.getJSONObject("info");
        Info i = new Info();
        i.id = ij.optString("id"); i.label = ij.optString("label"); i.from = ij.optString("from"); i.to = ij.optString("to");
        i.samples = ij.optInt("samples"); i.days = ij.optInt("days");
        i.upShare = nan(ij, "upShare"); i.medMove = nan(ij, "medMove");
        i.testFrom = ij.optString("testFrom"); i.testDays = ij.optInt("testDays"); i.testSamples = ij.optInt("testSamples");
        i.hit = nan(ij, "hit"); i.baseHit = nan(ij, "baseHit"); i.brier = nan(ij, "brier"); i.baseBrier = nan(ij, "baseBrier");
        m.info = i;
        return m;
    }

    /** Like strength(pUp), but a model that did not beat the base rate on unseen sessions never gets a strength label. */
    public static String strength(double pUp, Info info) {
        if (info != null && info.tested() && !info.proven()) return "No proven edge";
        return strength(pUp);
    }

    /** Plain words for a probability of UP. */
    public static String strength(double pUp) {
        double c = Math.max(pUp, 1 - pUp);
        return c >= 0.65 ? "Strong" : c >= 0.58 ? "Clear" : c >= 0.53 ? "Mild" : "Toss-up";
    }

    // ================================================================== helpers

    static String pct(double v) { return Double.isNaN(v) ? "—" : String.format(Locale.US, "%.1f%%", v * 100); }
    static int[] toInt(List<Integer> l) { int[] a = new int[l.size()]; for (int i = 0; i < a.length; i++) a[i] = l.get(i); return a; }
    static double nz(double v) { return Double.isNaN(v) || Double.isInfinite(v) ? -999 : v; }
    static double nan(JSONObject j, String k) { double v = j.optDouble(k, -999); return v == -999 ? Double.NaN : v; }
    static JSONArray arr(double[] v) throws Exception { JSONArray a = new JSONArray(); for (double x : v) a.put(Double.isNaN(x) ? 0 : x); return a; }
    static double[] darr(JSONArray a) throws Exception { double[] v = new double[a.length()]; for (int i = 0; i < v.length; i++) v[i] = a.getDouble(i); return v; }
}

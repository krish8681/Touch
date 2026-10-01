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
    public static final int VERSION = 3;

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
        m.lr = LogReg.fit(X, y, rows, n, LAMBDA, ITERS);
        return m;
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
        if (m.open) p.label = hz.bars < 0 ? "Today's close" : hz.label.replace("Next", "First") + " after the open";
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
                .put("samples", i.samples).put("days", i.days).put("upShare", nz(i.upShare)).put("medMove", nz(i.medMove)));
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
        m.info = i;
        return m;
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

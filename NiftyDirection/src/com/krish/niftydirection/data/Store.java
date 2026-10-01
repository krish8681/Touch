package com.krish.niftydirection.data;

import com.krish.niftydirection.engine.Factor;
import com.krish.niftydirection.engine.Result;
import com.krish.niftydirection.model.FlowPoint;
import com.krish.niftydirection.model.NewsItem;
import com.krish.niftydirection.model.Snapshot;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Saves the day's score line (for the chart) and an honest track record:
 *  - "idea": the pre-market view (if the app ran between 8:00 and 9:15), judged on the day's close vs the previous close
 *  - "call": the 9:45 view, judged on the close vs the 9:45 price
 */
public class Store {
    private final File dir;
    public Store(File dir) { this.dir = dir; }

    // ------------------------------------------------------------------ score line

    public synchronized void addPoint(Snapshot s, Result r) {
        if (s.nifty == null || !s.nifty.ok()) return;
        File f = new File(dir, "timeline_" + s.today + ".json");
        JSONArray a = readArr(f);
        try {
            JSONArray p = new JSONArray();
            p.put(s.minute).put(Math.round(r.score * 10) / 10.0).put(r.confidence).put(Result.code(r.regime)).put(s.spot())
                    .put(Double.isNaN(r.structScore) ? 0 : Math.round(r.structScore)).put(Double.isNaN(r.liveScore) ? 0 : Math.round(r.liveScore))
                    .put(Result.code(r.rawRegime));
            // one point per minute at most
            if (a.length() > 0 && a.getJSONArray(a.length() - 1).getInt(0) == s.minute) a.put(a.length() - 1, p); else a.put(p);
            write(f, a.toString());
        } catch (Exception ignored) {}
        File[] old = dir.listFiles((d, n) -> n.startsWith("timeline_") && n.compareTo("timeline_" + Collector.daysAgo(s.today, 7)) < 0);
        if (old != null) for (File o : old) o.delete();
    }

    /** Each point: {minute, score, confidence, regimeCode (see Result.fromCode), spot, structural, live}. */
    public synchronized List<double[]> timeline(String day) {
        List<double[]> out = new ArrayList<>();
        JSONArray a = readArr(new File(dir, "timeline_" + day + ".json"));
        for (int i = 0; i < a.length(); i++) {
            JSONArray p = a.optJSONArray(i);
            if (p == null) continue;
            out.add(new double[]{p.optDouble(0), p.optDouble(1), p.optDouble(2), Result.codeNum(p.optString(3, "R")), p.optDouble(4),
                    p.optDouble(5, 0), p.optDouble(6, 0), Result.codeNum(p.optString(7, p.optString(3, "N")))});
        }
        return out;
    }

    // ------------------------------------------------------------------ track record

    public static class Day {
        public String date;
        public String ideaRegime = "", callRegime = "";
        public double ideaScore = Double.NaN, callScore = Double.NaN, callPrice = Double.NaN, prevClose = Double.NaN, close = Double.NaN;
        public int callConf;
        public boolean final_;

        public double dayMove() { return prevClose > 0 && close > 0 ? (close - prevClose) / prevClose * 100 : Double.NaN; }
        public double callMove() { return callPrice > 0 && close > 0 ? (close - callPrice) / callPrice * 100 : Double.NaN; }
        public Boolean ideaHit() { return judge(ideaRegime, dayMove()); }
        public Boolean callHit() { return judge(callRegime, callMove()); }

        static Boolean judge(String regime, double move) {
            if (regime.isEmpty() || Double.isNaN(move)) return null;
            if (regime.equals(Result.BULLISH)) return move > 0.1;
            if (regime.equals(Result.BEARISH)) return move < -0.1;
            if (regime.equals(Result.RANGE)) return Math.abs(move) < 0.4;
            return null;   // NO EDGE and CONFLICT make no claim, so they are not scored
        }
    }

    public synchronized void record(Snapshot s, Result r) {
        if (s.nifty == null || !s.nifty.ok() || !s.weekday) return;
        File f = new File(dir, "record.json");
        JSONObject all = Collector.readJson(f);
        try {
            // pre-market idea for today
            if (!s.live && s.minute >= 8 * 60 && s.minute < 9 * 60 + 15 && !s.today.equals(s.sessionDate)) {
                JSONObject d = all.optJSONObject(s.today);
                if (d == null) d = new JSONObject();
                d.put("ideaRegime", r.regime).put("ideaScore", r.score).put("prevClose", s.nifty.last);
                all.put(s.today, d);
            }
            // 9:45 call
            if (s.live && s.minute >= 9 * 60 + 45) {
                JSONObject d = all.optJSONObject(s.today);
                if (d == null) d = new JSONObject();
                if (!d.has("callRegime")) {
                    d.put("callRegime", r.regime).put("callScore", r.score).put("callConf", r.confidence).put("callPrice", s.spot());
                }
                if (!d.has("prevClose")) d.put("prevClose", s.nifty.prevClose);
                d.put("close", s.spot());   // latest price; becomes the close
                // factor readings at two checkpoints, for the walk-forward calibration
                checkpoint(d, "c945", s, r);
                if (s.minute >= 11 * 60 + 30) checkpoint(d, "c1130", s, r);
                all.put(s.today, d);
            }
            // after the session: freeze the close
            if (!s.live && !s.sessionDate.isEmpty()) {
                JSONObject d = all.optJSONObject(s.sessionDate);
                if (d != null && !d.optBoolean("final")) {
                    if (s.minute >= 15 * 60 + 30 || !s.sessionDate.equals(s.today)) {
                        d.put("close", s.nifty.last).put("final", true);
                        if (!d.has("prevClose")) d.put("prevClose", s.nifty.prevClose);
                    }
                }
            }
            // keep ~120 days
            if (all.length() > 130) {
                List<String> keys = new ArrayList<>();
                Iterator<String> it = all.keys();
                while (it.hasNext()) keys.add(it.next());
                java.util.Collections.sort(keys);
                for (int i = 0; i < keys.size() - 120; i++) all.remove(keys.get(i));
            }
            write(f, all.toString());
        } catch (Exception ignored) {}
    }

    private static void checkpoint(JSONObject d, String key, Snapshot s, Result r) throws Exception {
        if (d.has(key)) return;
        JSONObject f = new JSONObject();
        for (Factor x : r.factors) if (x.available) f.put(x.key, Math.round(x.value * 100) / 100.0);
        d.put(key, new JSONObject().put("price", s.spot()).put("f", f));
    }

    /** One saved factor reading at a checkpoint and what Nifty did afterwards. */
    static class Obs { String date, key; double v, move; }

    List<Obs> observations(String before) {
        JSONObject all = Collector.readJson(new File(dir, "record.json"));
        List<Obs> out = new ArrayList<>();
        Iterator<String> it = all.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (k.compareTo(before) >= 0) continue;
            JSONObject d = all.optJSONObject(k);
            if (d == null || !d.optBoolean("final")) continue;
            double close = d.optDouble("close", Double.NaN);
            for (String cp : new String[]{"c945", "c1130"}) {
                JSONObject c = d.optJSONObject(cp);
                if (c == null || Double.isNaN(close)) continue;
                double price = c.optDouble("price", Double.NaN);
                if (!(price > 0)) continue;
                double move = (close - price) / price * 100;
                if (Math.abs(move) < 0.05) continue;
                JSONObject f = c.optJSONObject("f");
                if (f == null) continue;
                Iterator<String> fk = f.keys();
                while (fk.hasNext()) {
                    Obs o = new Obs();
                    o.date = k; o.key = fk.next(); o.v = f.optDouble(o.key, 0); o.move = move;
                    out.add(o);
                }
            }
        }
        return out;
    }

    public static final int MIN_OBS = 100, FULL_OBS = 500, VALIDATION_DAYS = 20;

    /**
     * Protected walk-forward calibration. Returns key → {hits, samples, multiplier}, plus "_meta" →
     * {training days, validation days, status (1 = passed, 0 = rejected by validation, -1 = not enough data), validation hits new, validation hits old, validation samples}.
     *  - Only finished days before this week are used; weights change at most once a week, never from today.
     *  - The last 20 days are held back for validation. Weights are fitted on the older days only.
     *  - A factor needs 100 training readings before it can move at all, ±15% below 500, ±40% above.
     *  - The new weights are kept only if they call the validation days at least as well as the fixed weights.
     */
    public synchronized Map<String, double[]> calibration(String today) {
        String week = weekStart(today);
        File cache = new File(dir, "calib_" + week + ".json");
        JSONObject cj = Collector.readJson(cache);
        Map<String, double[]> t = new LinkedHashMap<>();
        if (cj.length() > 0) {
            Iterator<String> it = cj.keys();
            while (it.hasNext()) {
                String k = it.next();
                JSONArray a = cj.optJSONArray(k);
                if (a == null) continue;
                double[] v = new double[a.length()];
                for (int i = 0; i < v.length; i++) v[i] = a.optDouble(i, 0);
                t.put(k, v);
            }
            return t;
        }
        List<Obs> obs = observations(week);
        java.util.TreeSet<String> dates = new java.util.TreeSet<>();
        for (Obs o : obs) dates.add(o.date);
        List<String> dl = new ArrayList<>(dates);
        String valFrom = dl.size() > VALIDATION_DAYS ? dl.get(dl.size() - VALIDATION_DAYS) : "9999";
        Map<String, double[]> train = new LinkedHashMap<>();
        for (Obs o : obs) {
            if (o.date.compareTo(valFrom) >= 0 || Math.abs(o.v) < 0.2) continue;
            double[] a = train.computeIfAbsent(o.key, k -> new double[3]);
            a[1]++;
            if (Math.signum(o.v) == Math.signum(o.move)) a[0]++;
        }
        boolean any = false;
        for (double[] a : train.values()) {
            double rate = a[1] > 0 ? a[0] / a[1] : 0.5;
            double cap = a[1] < MIN_OBS ? 0 : a[1] < FULL_OBS ? 0.15 : 0.40;
            double shrink = a[1] / (a[1] + 200.0);
            a[2] = 1 + Math.max(-cap, Math.min(cap, (rate - 0.5) * 2 * shrink));
            if (Math.abs(a[2] - 1) > 1e-9) any = true;
        }
        // validation: does the adjusted mix call the held-back days better than the fixed mix?
        int status = -1; double newHits = 0, oldHits = 0, samples = 0;
        if (any) {
            Map<String, double[]> byCheckpoint = new LinkedHashMap<>();   // date|move → {sumNew, sumOld, move}
            for (Obs o : obs) {
                if (o.date.compareTo(valFrom) < 0) continue;
                String k = o.date + "|" + o.move;
                double[] a = byCheckpoint.computeIfAbsent(k, x -> new double[]{0, 0, o.move});
                double m = train.containsKey(o.key) ? train.get(o.key)[2] : 1;
                a[0] += o.v * m; a[1] += o.v;
            }
            for (double[] a : byCheckpoint.values()) {
                if (Math.abs(a[1]) < 1e-9 && Math.abs(a[0]) < 1e-9) continue;
                samples++;
                if (Math.signum(a[0]) == Math.signum(a[2])) newHits++;
                if (Math.signum(a[1]) == Math.signum(a[2])) oldHits++;
            }
            status = samples >= 10 && newHits >= oldHits ? 1 : 0;
            if (status != 1) for (double[] a : train.values()) a[2] = 1;
        }
        t.putAll(train);
        int trainDays = 0;
        for (String d : dl) if (d.compareTo(valFrom) < 0) trainDays++;
        t.put("_meta", new double[]{trainDays, Math.min(VALIDATION_DAYS, dl.size() - trainDays), status, newHits, oldHits, samples});
        try {
            JSONObject out = new JSONObject();
            for (Map.Entry<String, double[]> e : t.entrySet()) {
                JSONArray a = new JSONArray();
                for (double v : e.getValue()) a.put(v);
                out.put(e.getKey(), a);
            }
            write(cache, out.toString());
            File[] old = dir.listFiles((d, n) -> n.startsWith("calib_") && !n.equals(cache.getName()));
            if (old != null) for (File x : old) x.delete();
        } catch (Exception ignored) {}
        return t;
    }

    /** Monday of the week of `day` (yyyy-MM-dd). */
    static String weekStart(String day) {
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
            f.setTimeZone(Collector.IST);
            java.util.Calendar c = java.util.Calendar.getInstance(Collector.IST);
            c.setTime(f.parse(day));
            while (c.get(java.util.Calendar.DAY_OF_WEEK) != java.util.Calendar.MONDAY) c.add(java.util.Calendar.DAY_OF_MONTH, -1);
            return f.format(c.getTime());
        } catch (Exception e) { return day; }
    }

    // ------------------------------------------------------------------ option-flow history (to size today's flow against past days)

    public synchronized void addFlowStat(String today, int minute, double intensity) {
        if (Double.isNaN(intensity)) return;
        File f = new File(dir, "flowstats.json");
        JSONArray a = readArr(f);
        try {
            if (a.length() > 0) {
                JSONArray last = a.optJSONArray(a.length() - 1);
                if (last != null && today.equals(last.optString(0)) && minute - last.optInt(1) < 4) return;   // one per ~5 min
            }
            a.put(new JSONArray().put(today).put(minute).put(Math.round(intensity * 10000) / 10000.0));
            JSONArray keep = new JSONArray();
            for (int i = Math.max(0, a.length() - 1500); i < a.length(); i++) keep.put(a.get(i));
            write(f, keep.toString());
        } catch (Exception ignored) {}
    }

    /** Median |intensity| from days before today; NaN until 30 readings exist. */
    public synchronized double flowScale(String today) {
        JSONArray a = readArr(new File(dir, "flowstats.json"));
        List<Double> v = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONArray x = a.optJSONArray(i);
            if (x == null || today.equals(x.optString(0))) continue;
            v.add(Math.abs(x.optDouble(2, 0)));
        }
        if (v.size() < 30) return Double.NaN;
        java.util.Collections.sort(v);
        return Math.max(0.005, v.get(v.size() / 2));
    }

    // ------------------------------------------------------------------ intraday option / futures snapshots

    public synchronized List<FlowPoint> addFlow(String today, FlowPoint p) {
        File f = new File(dir, "flow_" + today + ".json");
        JSONArray a = readArr(f);
        try {
            JSONObject o = new JSONObject();
            o.put("m", p.minute).put("spot", p.spot).put("fut", p.fut).put("futOi", p.futOi).put("basis", p.basis);
            if (!Double.isNaN(p.atmIv)) o.put("iv", p.atmIv);
            JSONObject opt = new JSONObject();
            for (Map.Entry<String, double[]> e : p.opt.entrySet()) {
                double[] v = e.getValue();
                opt.put(e.getKey(), new JSONArray().put(v[0]).put(v[1]).put(v[2]).put(Double.isNaN(v[3]) ? -1 : Math.round(v[3] * 100) / 100.0));
            }
            o.put("o", opt);
            // at most one snapshot per 2 minutes: replace the last one if it is too recent
            if (a.length() > 0 && p.minute - a.getJSONObject(a.length() - 1).optInt("m") < 2) a.put(a.length() - 1, o); else a.put(o);
            write(f, a.toString());
        } catch (Exception ignored) {}
        File[] old = dir.listFiles((d, n) -> n.startsWith("flow_") && !n.equals(f.getName()));
        if (old != null) for (File x : old) x.delete();
        return loadFlow(a);
    }

    public synchronized List<FlowPoint> flow(String today) { return loadFlow(readArr(new File(dir, "flow_" + today + ".json"))); }

    static List<FlowPoint> loadFlow(JSONArray a) {
        List<FlowPoint> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            FlowPoint p = new FlowPoint();
            p.minute = o.optInt("m"); p.spot = o.optDouble("spot", 0); p.fut = o.optDouble("fut", 0);
            p.futOi = o.optDouble("futOi", 0); p.basis = o.optDouble("basis", 0); p.atmIv = o.optDouble("iv", Double.NaN);
            JSONObject opt = o.optJSONObject("o");
            if (opt != null) {
                Iterator<String> it = opt.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    JSONArray v = opt.optJSONArray(k);
                    if (v == null || v.length() < 4) continue;
                    double iv = v.optDouble(3, -1);
                    p.opt.put(k, new double[]{v.optDouble(0), v.optDouble(1), v.optDouble(2), iv < 0 ? Double.NaN : iv});
                }
            }
            out.add(p);
        }
        return out;
    }

    // ------------------------------------------------------------------ news ratings (so each headline is rated once)

    public synchronized Map<String, NewsItem> newsCache() {
        JSONObject all = Collector.readJson(new File(dir, "news.json"));
        Map<String, NewsItem> out = new LinkedHashMap<>();
        Iterator<String> it = all.keys();
        while (it.hasNext()) {
            String k = it.next();
            JSONObject o = all.optJSONObject(k);
            if (o == null) continue;
            NewsItem n = new NewsItem();
            n.id = k; n.title = o.optString("t", ""); n.source = o.optString("s", ""); n.link = o.optString("l", "");
            n.time = o.optLong("time"); n.read = o.optBoolean("read"); n.by = o.optString("by", "");
            n.marketImpact = o.optDouble("mi", 0); n.niftyImpact = o.optDouble("ni", 0);
            n.sector = o.optString("sec", ""); n.severity = o.optString("sev", "LOW"); n.horizon = o.optString("hz", "intraday");
            n.reason = o.optString("why", ""); n.scheduled = o.optBoolean("sch"); n.eventDate = o.optString("ed", ""); n.eventName = o.optString("en", "");
            n.topic = o.optString("topic", ""); n.speculative = o.optBoolean("spec"); n.official = o.optBoolean("off"); n.summary = o.optString("sum", "");
            n.model = o.optString("model", ""); n.promptVersion = o.optString("pv", ""); n.ratedAt = o.optLong("at", 0);
            out.put(k, n);
        }
        return out;
    }

    public synchronized void saveNews(Map<String, NewsItem> items) {
        JSONObject all = new JSONObject();
        long cutoff = System.currentTimeMillis() - 3 * 86400_000L;
        try {
            for (NewsItem n : items.values()) {
                if (n.time > 0 && n.time < cutoff) continue;
                all.put(n.id, new JSONObject().put("t", n.title).put("s", n.source).put("l", n.link).put("time", n.time).put("read", n.read)
                        .put("by", n.by).put("mi", n.marketImpact).put("ni", n.niftyImpact).put("sec", n.sector).put("sev", n.severity)
                        .put("hz", n.horizon).put("why", n.reason).put("sch", n.scheduled).put("ed", n.eventDate).put("en", n.eventName)
                        .put("topic", n.topic).put("spec", n.speculative).put("off", n.official).put("sum", n.summary)
                        .put("model", n.model).put("pv", n.promptVersion).put("at", n.ratedAt));
            }
        } catch (Exception ignored) {}
        write(new File(dir, "news.json"), all.toString());
    }

    public synchronized List<Day> days() {
        JSONObject all = Collector.readJson(new File(dir, "record.json"));
        List<Day> out = new ArrayList<>();
        Iterator<String> it = all.keys();
        while (it.hasNext()) {
            String k = it.next();
            JSONObject d = all.optJSONObject(k);
            if (d == null) continue;
            Day x = new Day();
            x.date = k;
            x.ideaRegime = d.optString("ideaRegime", "");
            x.ideaScore = d.optDouble("ideaScore", Double.NaN);
            x.callRegime = d.optString("callRegime", "");
            x.callScore = d.optDouble("callScore", Double.NaN);
            x.callConf = d.optInt("callConf", 0);
            x.callPrice = d.optDouble("callPrice", Double.NaN);
            x.prevClose = d.optDouble("prevClose", Double.NaN);
            x.close = d.optDouble("close", Double.NaN);
            x.final_ = d.optBoolean("final");
            out.add(x);
        }
        out.sort((a, b) -> b.date.compareTo(a.date));
        return out;
    }

    // ------------------------------------------------------------------ files

    static JSONArray readArr(File f) {
        try { return f.exists() ? new JSONArray(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)) : new JSONArray(); }
        catch (Exception e) { return new JSONArray(); }
    }

    static void write(File f, String text) {
        File tmp = new File(f.getPath() + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) { w.write(text); } catch (Exception e) { return; }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f); }
    }
}

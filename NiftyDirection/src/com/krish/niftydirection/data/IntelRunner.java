package com.krish.niftydirection.data;

import com.krish.niftydirection.forecast.History;
import com.krish.niftydirection.intel.Feedback;
import com.krish.niftydirection.intel.FeatureEngine;
import com.krish.niftydirection.intel.Horizon;
import com.krish.niftydirection.intel.HorizonModel;
import com.krish.niftydirection.intel.ImpactGraph;
import com.krish.niftydirection.intel.IntelEngine;
import com.krish.niftydirection.intel.Trainer;
import com.krish.niftydirection.intel.Validator;
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
import java.util.Arrays;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Disk and network side of the prediction engine (files/intel/):
 *   model_<h>.json   one per horizon (versioned, with its walk-forward test)
 *   graph.json       learnt impact graph            report.txt   what the models learnt and how they tested
 *   state.json       last forecast (for "What changed?")   memory.json   today's event memory
 *   log_<yyyy-MM>.jsonl  every logged forecast and, once known, its outcome   feedback.json  scorecard + learnt overlays (daily)
 */
public final class IntelRunner {
    private IntelRunner() {}

    static final long RETRAIN_MS = 7L * 24 * 3600 * 1000;
    static final int LOG_EVERY_MIN = 30, LOG_MONTHS = 12;

    public static volatile IntelEngine.Forecast last;

    static File dir(File base) { File d = new File(base, "intel"); d.mkdirs(); return d; }

    // ================================================================== training

    public static String train(Kite kite, File base, String today, AtomicBoolean cancel, HistoryLoader.Progress pr) throws Exception {
        History h = ForecastRunner.history(kite, base, today, cancel, pr);
        return train(h, base, cancel, pr);
    }

    public static String train(History h, File base, AtomicBoolean cancel, HistoryLoader.Progress pr) throws Exception {
        File d = dir(base);
        List<HorizonModel> models = Trainer.trainAll(h, (w, a, b) -> {
            if (cancel != null && cancel.get()) throw new RuntimeException("Cancelled");
            pr.step(w, a, b);
        });
        for (HorizonModel m : models) write(new File(d, "model_" + m.id + ".json"), m.toJson().toString());
        write(new File(d, "graph.json"), ImpactGraph.toJson(ImpactGraph.build(h)).toString());
        String rep = report(models, h);
        write(new File(d, "report.txt"), rep);
        refreshFeedback(base, true);
        pr.step("Done", 1, 1);
        return rep;
    }

    static String report(List<HorizonModel> models, History h) {
        StringBuilder t = new StringBuilder();
        t.append(String.format(Locale.US, "Learnt %s from %d sessions (%s → %s), %d inputs in %d groups, %d world-market series.%n",
                new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new java.util.Date()), h.days.size(),
                h.days.isEmpty() ? "" : h.days.get(0).date, h.days.isEmpty() ? "" : h.days.get(h.days.size() - 1).date,
                FeatureEngine.N, FeatureEngine.GROUPS.length, h.global.size()));
        for (HorizonModel m : models) {
            HorizonModel.Info i = m.info;
            Horizon hz = Horizon.of(m.id);
            t.append(String.format(Locale.US, "%n%s: %,d samples · %d sessions · went up %.0f%% · calibration %s%n", hz.label, i.samples, i.days,
                    i.upShare * 100, m.calib.kind));
            t.append("  ").append(testLine(i)).append('\n');
            Integer[] o = new Integer[HorizonModel.G];
            for (int g = 0; g < o.length; g++) o[g] = g;
            Arrays.sort(o, (a, b) -> Double.compare(i.importance[b], i.importance[a]));
            t.append("  weights: ");
            for (int g = 0; g < 4; g++) t.append(g > 0 ? ", " : "").append(FeatureEngine.GROUP_NAMES[o[g]]).append(String.format(Locale.US, " %.0f%%", i.importance[o[g]]));
            t.append('\n');
        }
        return t.toString();
    }

    public static String testLine(HorizonModel.Info i) {
        if (!i.tested()) return "not tested (too little history for a held-back test)";
        return String.format(Locale.US, "test on %d unseen sessions from %s: right %.1f%% vs %.1f%% usual side · Brier skill %+.1f%% (90%% band %+.1f…%+.1f) · %s",
                i.testDays, i.testFrom, i.hit * 100, i.baseHit * 100, i.skill * 100, i.skillLo * 100, i.skillHi * 100, i.proven ? "PROVEN EDGE" : "NO PROVEN EDGE");
    }

    public static List<HorizonModel> models(File base) {
        List<HorizonModel> out = new ArrayList<>();
        for (Horizon hz : Horizon.ALL) {
            File f = new File(dir(base), "model_" + hz.id + ".json");
            if (!f.exists()) continue;
            try {
                HorizonModel m = HorizonModel.fromJson(new JSONObject(read(f)));
                if (m.version == HorizonModel.VERSION && m.meta != null) out.add(m);
            } catch (Exception ignored) { }
        }
        return out;
    }

    public static boolean hasModels(File base) { return !models(base).isEmpty(); }

    /** Any model file, even from an older version that must be retrained. */
    public static boolean hasModelFiles(File base) {
        File[] fs = dir(base).listFiles((x, n) -> n.startsWith("model_"));
        return fs != null && fs.length > 0;
    }

    public static boolean needsTraining(File base) {
        List<HorizonModel> m = models(base);
        if (m.size() < Horizon.ALL.length) return true;
        long oldest = Long.MAX_VALUE;
        for (HorizonModel x : m) oldest = Math.min(oldest, x.at);
        return System.currentTimeMillis() - oldest > RETRAIN_MS;
    }

    public static String report(File base) { try { File f = new File(dir(base), "report.txt"); return f.exists() ? read(f) : ""; } catch (Exception e) { return ""; } }

    public static List<ImpactGraph.Edge> graph(File base) {
        try { File f = new File(dir(base), "graph.json"); return f.exists() ? ImpactGraph.fromJson(new JSONArray(read(f))) : new ArrayList<>(); }
        catch (Exception e) { return new ArrayList<>(); }
    }

    // ================================================================== live

    /** Builds the live-only inputs from the last refresh of the Today tab (evidence score, news, events, constituents). */
    public static IntelEngine.LiveContext context(Snapshot s, com.krish.niftydirection.engine.Result r, String userEvents) {
        IntelEngine.LiveContext c = new IntelEngine.LiveContext();
        Calendar now = Calendar.getInstance(Collector.IST);
        c.now = now.getTimeInMillis();
        c.today = Collector.day(now.getTime());
        c.minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        c.userEvents = userEvents == null ? "" : userEvents;
        if (s == null) return c;
        c.news = s.news; c.events = s.events; c.weights = s.weights; c.sectorOf = s.sectorOf; c.stocks = s.stocks;
        c.sourceTime = s.sourceTime; c.marketOpen = s.live; c.newsReader = s.newsReader == null ? "" : s.newsReader; c.fiiDate = s.fiiDate == null ? "" : s.fiiDate;
        c.gift = s.giftNifty;
        if (s.nifty != null && s.nifty.ok()) { c.niftyLast = s.nifty.last; c.niftyPrevClose = s.nifty.prevClose; }
        if (s.fut != null && s.fut.ok()) c.futLast = s.fut.last;
        if (r != null && s.nifty != null && s.nifty.ok() && System.currentTimeMillis() - s.time < 30 * 60_000L) {
            c.evidenceAvailable = true;
            c.evidenceScore = r.score; c.evidenceConf = r.confidence; c.evidenceCoverage = r.coverage;
            c.evidenceRegime = r.regime;
            c.dataIssues.addAll(r.degradedList);
        }
        for (String n : s.notes) if (n.startsWith("Kite error") || n.contains("could not be loaded")) c.dataIssues.add(n);
        return c;
    }

    public static IntelEngine.Forecast live(Kite kite, File base, String today, int minute, double expectedOpen, IntelEngine.LiveContext ctx,
                                            Validator.Config cfg, AtomicBoolean cancel, HistoryLoader.Progress pr) throws Exception {
        double tradeThreshold = cfg.threshold;
        List<HorizonModel> models = models(base);
        if (models.isEmpty()) throw new Exception("No models yet — tap “Train models”.");
        File d = dir(base);
        History h = ForecastRunner.history(kite, base, today, cancel, pr).copy();
        History.Day td = ForecastRunner.todayBars(kite, today, minute, cancel, pr);
        boolean preOpen = false;
        if (td != null) h.days.add(td);
        else if (!Double.isNaN(expectedOpen) && expectedOpen > 0 && minute < History.OPEN_MIN + 15
                && !h.days.isEmpty() && h.days.get(h.days.size() - 1).date.compareTo(today) < 0) {
            History.Day x = new History.Day(today);
            x.o[0] = (float) expectedOpen;
            h.days.add(x);
            preOpen = true;
        }
        if (h.days.isEmpty()) throw new Exception("No history.");
        int di = h.days.size() - 1;
        History.Day day = h.days.get(di);
        int k = td != null ? day.bars : preOpen ? 0 : History.BARS;

        JSONObject prev = readJson(new File(d, "state.json"));
        JSONObject fb = readJson(new File(d, "feedback.json"));
        Map<String, Feedback.Overlay> overlays = overlays(fb);
        IntelEngine.Forecast fc = IntelEngine.forecast(models, h, di, k, preOpen, ctx, prev, overlays, tradeThreshold, governor(base));
        int m = History.minuteAfter(k);
        fc.asOf = preOpen ? "Before the open — expected open " + String.format(Locale.US, "%,.0f", fc.price) + " (from GIFT Nifty)"
                : (td != null ? "Today " : day.date + " close, ") + String.format(Locale.US, "%d:%02d", m / 60, m % 60);
        if (preOpen) fc.note = "Pre-open: the open is estimated from GIFT Nifty, so every forecast is pulled toward the usual drift.";
        else if (k == History.BARS) fc.note = "Market closed — forecasts are from the last close; intraday horizons start at the next open.";

        // ---- feedback: log this forecast, resolve old ones, scorecard
        JSONObject lastLog = prev != null && prev.optJSONObject("lastLog") != null ? prev.getJSONObject("lastLog") : new JSONObject();
        // log intraday and pre-open forecasts, and one forecast from each session's close (not the same one every 30 min all evening)
        boolean closeOnce = td != null && k == History.BARS && !fc.date.equals(lastLog.optString("closeDay"));
        if (fc.intraday || preOpen || closeOnce) {
            lastLog = logForecast(d, fc, lastLog, cfg);
            if (closeOnce) lastLog.put("closeDay", fc.date);
        }
        resolve(d, h, today);
        fc.validation = validation(base);
        refreshFeedback(base, false);
        fc.scorecard = scorecard(readJson(new File(d, "feedback.json")));

        // ---- event memory, impact graph, state for the next "What changed?"
        JSONObject mem = IntelEngine.remember(readJson(new File(d, "memory.json")), fc, prev, ctx != null ? ctx.minute : m);
        write(new File(d, "memory.json"), mem.toString());
        fc.memory = IntelEngine.memoryLines(mem);
        fc.graph = graph(base);
        JSONObject st = IntelEngine.state(fc);
        st.put("lastLog", lastLog);
        write(new File(d, "state.json"), st.toString());
        last = fc;
        return fc;
    }

    /** One log line per horizon at most every 30 minutes (the record should not be dominated by overlapping copies). */
    static JSONObject logForecast(File d, IntelEngine.Forecast fc, JSONObject ll, Validator.Config cfg) throws Exception {
        StringBuilder add = new StringBuilder();
        for (IntelEngine.HPred p : fc.preds) {
            if (!p.has()) continue;
            if (fc.at - ll.optLong(p.hz.id, 0) < LOG_EVERY_MIN * 60_000L) continue;
            JSONObject en = Feedback.entry(fc.at, fc.date, fc.k, p.hz, fc.price, p.pModel, p.pFinal, p.evPush, p.newsPush, fc.regime.label());
            if (en == null) continue;
            if (p.tradeable && fc.k > 0 && fc.k < History.BARS) {   // live paper trade, simulated with the replay's rules once the time has passed
                double stop = cfg.stopMult > 0 && !Double.isNaN(p.range68) ? cfg.stopMult * p.range68 * fc.price : 0;
                en.put("trade", "BULLISH".equals(p.direction) ? 1 : -1).put("stop", stop).put("lot", cfg.lot).put("slip", cfg.slippagePts);
            }
            add.append(en.toString()).append('\n');
            ll.put(p.hz.id, fc.at);
        }
        if (add.length() == 0) return ll;
        try (Writer w = new OutputStreamWriter(new FileOutputStream(new File(d, "log_" + fc.date.substring(0, 7) + ".jsonl"), true), StandardCharsets.UTF_8)) {
            w.write(add.toString());
        }
        return ll;
    }

    /** Fills outcomes in this month's and last month's log (a 1-week forecast resolves within about 8 days). */
    static void resolve(File d, History h, String today) throws Exception {
        for (String mon : new String[]{Collector.daysAgo(today, 31).substring(0, 7), today.substring(0, 7)}) {
            File f = new File(d, "log_" + mon + ".jsonl");
            if (!f.exists()) continue;
            List<JSONObject> log = readLog(f);
            int n = Feedback.resolve(log, h) + resolvePaper(log, h);
            if (n > 0) writeLog(f, log);
        }
    }

    /** Recomputes the scorecard and re-learns the overlays at most once a day (or when forced after training). */
    static void refreshFeedback(File base, boolean force) throws Exception {
        File d = dir(base), fbf = new File(d, "feedback.json");
        String today = Collector.day(new java.util.Date());
        JSONObject old = readJson(fbf);
        if (!force && old != null && today.equals(old.optString("date"))) return;
        File[] fs = d.listFiles((x, n) -> n.startsWith("log_") && n.endsWith(".jsonl"));
        List<JSONObject> all = new ArrayList<>();
        if (fs != null) {
            Arrays.sort(fs);
            String keep = "log_" + Collector.daysAgo(today, LOG_MONTHS * 31).substring(0, 7);
            for (File f : fs) { if (f.getName().compareTo(keep) < 0) { f.delete(); continue; } all.addAll(readLog(f)); }
        }
        JSONObject fb = new JSONObject().put("date", today);
        JSONObject sc = new JSONObject();
        for (Map.Entry<String, double[]> e : Feedback.scorecard(all, null).entrySet()) sc.put(e.getKey(), arr(e.getValue()));
        fb.put("scorecard", sc);
        JSONObject paper = new JSONObject();
        for (Horizon hz : Horizon.ALL) {
            int n = 0, w = 0; double net = 0;
            for (JSONObject e : all) if (hz.id.equals(e.optString("h")) && e.has("pnl")) { n++; double v = e.optDouble("pnl", 0); net += v; if (v > 0) w++; }
            if (n > 0) paper.put(hz.id, new JSONArray().put(n).put(w).put(net));
        }
        fb.put("paper", paper);
        JSONObject ov = new JSONObject();
        for (String band : new String[]{Horizon.SHORT, Horizon.INTRADAY, Horizon.SWING}) {
            Feedback.Overlay o = Feedback.learnOverlay(all, band);
            if (o == null) continue;
            JSONObject j = new JSONObject().put("rows", o.rows).put("logLoss", nz(o.logLoss)).put("priorLogLoss", nz(o.priorLogLoss));
            if (o.lr != null) j.put("w", arr(o.lr.w)).put("mean", arr(o.lr.mean)).put("sd", arr(o.lr.sd));
            ov.put(band, j);
        }
        fb.put("overlays", ov);
        write(fbf, fb.toString());
    }

    static Map<String, Feedback.Overlay> overlays(JSONObject fb) {
        Map<String, Feedback.Overlay> out = new LinkedHashMap<>();
        JSONObject ov = fb == null ? null : fb.optJSONObject("overlays");
        if (ov == null) return out;
        for (String band : new String[]{Horizon.SHORT, Horizon.INTRADAY, Horizon.SWING}) {
            JSONObject j = ov.optJSONObject(band);
            if (j == null) continue;
            Feedback.Overlay o = new Feedback.Overlay();
            o.rows = j.optInt("rows");
            o.logLoss = j.optDouble("logLoss", Double.NaN); o.priorLogLoss = j.optDouble("priorLogLoss", Double.NaN);
            try {
                if (j.has("w")) {
                    com.krish.niftydirection.forecast.LogReg lr = new com.krish.niftydirection.forecast.LogReg();
                    lr.w = darr(j.getJSONArray("w")); lr.mean = darr(j.getJSONArray("mean")); lr.sd = darr(j.getJSONArray("sd"));
                    if (lr.w.length == 4 && lr.mean.length == 3) o.lr = lr;
                }
            } catch (Exception ignored) { }
            out.put(band, o);
        }
        return out;
    }

    /** Overlay status per band for the page: "learnt from N forecasts" / "prior (N of 200 needed)". */
    public static Map<String, String> overlayStatus(File base) {
        Map<String, String> out = new LinkedHashMap<>();
        Map<String, Feedback.Overlay> ov = overlays(readJson(new File(dir(base), "feedback.json")));
        for (String band : new String[]{Horizon.SHORT, Horizon.INTRADAY, Horizon.SWING}) {
            Feedback.Overlay o = ov.get(band);
            if (o == null) out.put(band, "starting weights (needs " + Feedback.MIN_OVERLAY_ROWS + " resolved forecasts with live inputs)");
            else if (o.lr == null) out.put(band, String.format(Locale.US, "starting weights kept — the re-learnt ones did not beat them (%d forecasts)", o.rows));
            else out.put(band, String.format(Locale.US, "re-learnt from %d resolved forecasts (log loss %.3f vs %.3f)", o.rows, o.logLoss, o.priorLogLoss));
        }
        return out;
    }

    static Map<String, double[]> scorecard(JSONObject fb) {
        Map<String, double[]> out = new LinkedHashMap<>();
        JSONObject sc = fb == null ? null : fb.optJSONObject("scorecard");
        if (sc == null) return out;
        for (Horizon hz : Horizon.ALL) {
            JSONArray a = sc.optJSONArray(hz.id);
            if (a == null) continue;
            try { out.put(hz.id, darr(a)); } catch (Exception ignored) { }
        }
        return out;
    }

    // ================================================================== pre-live validation + governor

    private static volatile Validator.Report cachedValidation;
    private static volatile long cachedValidationAt;

    /** Replays the last ~12 months through the same engine, simulates trades and writes intel/validation.json. */
    public static Validator.Report validate(Kite kite, File base, String today, Validator.Config cfg, AtomicBoolean cancel, HistoryLoader.Progress pr) throws Exception {
        History h = ForecastRunner.history(kite, base, today, cancel, pr);
        cfg.eventDates.addAll(eventDates());
        Validator.Report r = Validator.run(h, cfg, (w, a, b) -> {
            if (cancel != null && cancel.get()) throw new RuntimeException("Cancelled");
            pr.step(w, a, b);
        });
        write(new File(dir(base), "validation.json"), Validator.toJson(r).toString());
        cachedValidation = r;
        cachedValidationAt = System.currentTimeMillis();
        return r;
    }

    /** Last validation report, or null. */
    public static Validator.Report validation(File base) {
        File f = new File(dir(base), "validation.json");
        if (!f.exists()) return null;
        if (cachedValidation != null && cachedValidationAt >= f.lastModified()) return cachedValidation;
        try {
            Validator.Report r = Validator.fromJson(new JSONObject(read(f)));
            cachedValidation = r; cachedValidationAt = f.lastModified();
            return r;
        } catch (Exception e) { return null; }
    }

    /** True when there is no report yet, or the models were retrained after it. */
    public static boolean needsValidation(File base) {
        Validator.Report r = validation(base);
        if (r == null) return true;
        for (HorizonModel m : models(base)) if (m.at > r.at) return true;
        return false;
    }

    /** Verdict per horizon for the live trade gate: {verdict, first reason}. Empty map = not validated (nothing may act). */
    public static Map<String, String[]> governor(File base) {
        Map<String, String[]> g = new LinkedHashMap<>();
        Validator.Report r = validation(base);
        if (r == null) return g;
        for (Validator.HReport x : r.horizons) g.put(x.id, new String[]{x.verdict, x.reasons.isEmpty() ? "" : x.reasons.get(0)});
        return g;
    }

    /** RBI, Fed and Budget days known to the calendar (for the replay's regime and stress tables). */
    static java.util.Set<String> eventDates() {
        java.util.Set<String> s = new java.util.HashSet<>(Arrays.asList(EventCalendar.RBI));
        s.addAll(Arrays.asList(EventCalendar.FED));
        for (int y = 2018; y <= 2027; y++) s.add(y + "-02-01");
        return s;
    }

    /** Paper trades logged live: once their path is in the history, simulate them with the replay's rules. */
    static int resolvePaper(List<JSONObject> log, History h) throws Exception {
        int[] sidx = h.sessionIndex();
        Map<String, Integer> idx = new LinkedHashMap<>();
        for (int i = 0; i < h.days.size(); i++) idx.put(h.days.get(i).date, i);
        int n = 0;
        for (JSONObject e : log) {
            if (!e.has("trade") || e.has("pnl")) continue;
            Integer d = idx.get(e.optString("date"));
            Horizon hz = Horizon.of(e.optString("h"));
            if (d == null || hz == null) continue;
            Validator.Config c = new Validator.Config();
            c.lot = e.optInt("lot", 65); c.slippagePts = e.optDouble("slip", 1);
            Validator.Trade t = Validator.simulate(h, sidx, d, e.optInt("k"), hz, e.optInt("trade"), e.optDouble("stop", 0), c);
            if (t == null) continue;
            e.put("pnl", t.rupees).put("pts", t.pts).put("stopped", t.stopped);
            n++;
        }
        return n;
    }

    /** Live paper-trade results per horizon: {trades, wins, net ₹}. */
    public static Map<String, double[]> paper(File base) {
        Map<String, double[]> out = new LinkedHashMap<>();
        JSONObject fb = readJson(new File(dir(base), "feedback.json"));
        JSONObject p = fb == null ? null : fb.optJSONObject("paper");
        if (p == null) return out;
        for (Horizon hz : Horizon.ALL) { JSONArray a = p.optJSONArray(hz.id); if (a != null) try { out.put(hz.id, darr(a)); } catch (Exception ignored) { } }
        return out;
    }

    /** Before 9:15 on a weekday: the open GIFT Nifty points to (Nifty × GIFT ÷ near futures). NaN otherwise. */
    public static double expectedOpen(Snapshot s) {
        Calendar c = Calendar.getInstance(Collector.IST);
        int d = c.get(Calendar.DAY_OF_WEEK), m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        boolean pre = d != Calendar.SATURDAY && d != Calendar.SUNDAY && m >= 6 * 60 && m < 9 * 60 + 15;
        if (s == null || !pre || s.live || Double.isNaN(s.giftNifty) || s.nifty == null || !s.nifty.ok() || s.fut == null || !s.fut.ok()) return Double.NaN;
        return s.nifty.last * s.giftNifty / s.fut.last;
    }

    // ================================================================== io

    static List<JSONObject> readLog(File f) throws Exception {
        List<JSONObject> out = new ArrayList<>();
        for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
            line = line.trim();
            if (line.isEmpty()) continue;
            try { out.add(new JSONObject(line)); } catch (Exception ignored) { }
        }
        return out;
    }

    static void writeLog(File f, List<JSONObject> log) throws Exception {
        StringBuilder b = new StringBuilder();
        for (JSONObject j : log) b.append(j.toString()).append('\n');
        write(f, b.toString());
    }

    static JSONObject readJson(File f) {
        try { return f.exists() ? new JSONObject(read(f)) : null; } catch (Exception e) { return null; }
    }

    static String read(File f) throws Exception { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); }

    static void write(File f, String s) throws Exception {
        File tmp = new File(f.getPath() + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) { w.write(s); }
        if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) throw new java.io.IOException("could not save " + f.getName()); }
    }

    static double nz(double v) { return Double.isNaN(v) || Double.isInfinite(v) ? -999 : v; }
    static JSONArray arr(double[] v) throws Exception { JSONArray a = new JSONArray(); for (double x : v) a.put(Double.isNaN(x) || Double.isInfinite(x) ? 0 : x); return a; }
    static double[] darr(JSONArray a) throws Exception { double[] v = new double[a.length()]; for (int i = 0; i < v.length; i++) v[i] = a.getDouble(i); return v; }
}

package com.krish.niftydirection.data;

import com.krish.niftydirection.forecast.History;
import com.krish.niftydirection.intel.Feedback;
import com.krish.niftydirection.intel.FeatureEngine;
import com.krish.niftydirection.intel.Horizon;
import com.krish.niftydirection.intel.HorizonModel;
import com.krish.niftydirection.intel.ImpactGraph;
import com.krish.niftydirection.intel.Journal;
import com.krish.niftydirection.intel.OptionStrategy;
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
            t.append("  groups that beat the base rate on unseen sessions (used): ").append(i.used.isEmpty() ? "—" : i.used).append('\n');
            t.append(String.format(Locale.US, "  trust in the model's lean (shrink toward base rate, chosen on unseen sessions): %.0f%%%s%n", i.shrink * 100,
                    hz.watchOnly() ? " · WATCH ONLY" : ""));
            if (i.importance[o[0]] > 0) {
                t.append("  weights: ");
                for (int g = 0; g < 4 && i.importance[o[g]] > 0; g++) t.append(g > 0 ? ", " : "").append(FeatureEngine.GROUP_NAMES[o[g]]).append(String.format(Locale.US, " %.0f%%", i.importance[o[g]]));
                t.append('\n');
            }
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
        applyGuard(fc, base, today, ctx != null ? ctx.minute : History.minuteAfter(k), cfg);
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
        r.saveDetail(dir(base));   // row-level replay data, for the export
        cachedValidation = r;
        cachedValidationAt = System.currentTimeMillis();
        return r;
    }

    /**
     * Everything behind the validation, for analysis elsewhere (Excel, Python …), as one ZIP of CSV / text files:
     * the per-horizon summary, confidence buckets, market conditions, stress days, every replay forecast and simulated trade,
     * the 88 inputs at every replay moment, the leakage audit, the live forecast log with outcomes and paper trades,
     * the training report and the settings used. Returns the number of files written.
     */
    public static int export(File base, java.io.OutputStream out, Validator.Config settings) throws Exception {
        File d = dir(base);
        Validator.Report r = validation(base);
        java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(new java.io.BufferedOutputStream(out));
        int n = 0;
        String stamp = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new java.util.Date());
        StringBuilder readme = new StringBuilder("Nifty Direction — validation export, " + stamp + "\n\n");
        if (r != null) readme.append("Replay ").append(r.from).append(" → ").append(r.to).append(", ").append(r.sessions).append(" sessions, ").append(r.blocks)
                .append(" walk-forward blocks. Verdict: ").append(r.verdict).append(" — ").append(r.summary).append("\n\n");
        else readme.append("No validation report yet (only the live log is included).\n\n");
        readme.append(README);
        n += entry(z, "README.txt", readme.toString());
        if (r != null) {
            StringBuilder b = new StringBuilder("horizon,label,verdict,reasons,forecasts,sessions,hit_rate,usual_side_rate,brier,base_brier,brier_skill,skill_band_low,skill_band_high,skill_first_half,skill_second_half,"
                    + "up_flat_down_accuracy,up_flat_down_base,calibration_error,trades,wins,win_rate,stopped,net_points,net_rupees_per_lot,charges_rupees,avg_rupees_per_trade,"
                    + "profit_factor,max_drawdown_rupees,worst_losing_streak\n");
            for (Validator.HReport x : r.horizons)
                b.append(x.id).append(',').append(x.label).append(',').append(x.verdict).append(',').append(csv(String.join("; ", x.reasons))).append(',')
                        .append(x.n).append(',').append(x.sessions).append(',').append(num(x.hit, 4)).append(',').append(num(x.base, 4)).append(',')
                        .append(num(x.brier, 5)).append(',').append(num(x.baseBrier, 5)).append(',').append(num(x.skill, 5)).append(',').append(num(x.skillLo, 5)).append(',')
                        .append(num(x.skillHi, 5)).append(',').append(num(x.skill1, 5)).append(',').append(num(x.skill2, 5)).append(',').append(num(x.acc3, 4)).append(',').append(num(x.base3, 4)).append(',').append(num(x.calErr, 4)).append(',')
                        .append(x.trades).append(',').append(x.wins).append(',').append(x.trades > 0 ? num(x.wins / (double) x.trades, 4) : "").append(',').append(x.stops).append(',')
                        .append(num(x.netPts, 2)).append(',').append(num(x.netRupees, 2)).append(',').append(num(x.chargesRupees, 2)).append(',').append(num(x.avgRupees, 2)).append(',')
                        .append(num(x.profitFactor, 3)).append(',').append(num(x.maxDD, 2)).append(',').append(x.worstStreak).append('\n');
            n += entry(z, "summary_by_horizon.csv", b.toString());
            StringBuilder bk = new StringBuilder("horizon,bucket_from,bucket_to,forecasts,mean_predicted,actual_right\n");
            for (Validator.HReport x : r.horizons) for (double[] q : x.buckets)
                bk.append(x.id).append(',').append(num(q[0], 2)).append(',').append(num(q[0] + 0.1, 2)).append(',').append((long) q[1]).append(',').append(num(q[2], 4)).append(',').append(num(q[3], 4)).append('\n');
            n += entry(z, "confidence_buckets.csv", bk.toString());
            n += entry(z, "market_conditions.csv", tableCsv(r, false));
            n += entry(z, "stress_days.csv", tableCsv(r, true));
            StringBuilder au = new StringBuilder(String.format(Locale.US, "Moments recomputed on a cut history: %d, differences: %d%nPurge (models learnt only before their block): %s%n",
                    r.auditChecked, r.auditFailed, r.purgeOk ? "OK" : "FAILED"));
            for (String s : r.audit) au.append(s).append('\n');
            n += entry(z, "leakage_audit.txt", au.toString());
            File vj = new File(d, "validation.json");
            if (vj.exists()) n += entry(z, "validation.json", read(vj));
            for (String name : new String[]{"replay_forecasts", "replay_trades", "replay_features"}) {
                File g = new File(d, name + ".csv.gz");
                if (!g.exists()) continue;
                z.putNextEntry(new java.util.zip.ZipEntry(name + ".csv"));
                try (java.io.InputStream in = new java.util.zip.GZIPInputStream(new java.io.FileInputStream(g))) {
                    byte[] buf = new byte[65536]; int k;
                    while ((k = in.read(buf)) > 0) z.write(buf, 0, k);
                }
                z.closeEntry();
                n++;
            }
        }
        // live record: every logged forecast, its outcome and paper trade
        File[] fs = d.listFiles((x, nm) -> nm.startsWith("log_") && nm.endsWith(".jsonl"));
        StringBuilder lv = new StringBuilder("time,date,bar,horizon,price,p_model,p_final,evidence_push,news_push,regime,resolved,target_date,went_up,move_pct,"
                + "paper_trade_side,stop_points,paper_net_rupees,paper_points,stopped\n");
        if (fs != null) {
            Arrays.sort(fs);
            java.text.SimpleDateFormat tf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
            tf.setTimeZone(Collector.IST);
            for (File f : fs) for (JSONObject e : readLog(f)) {
                lv.append(tf.format(new java.util.Date(e.optLong("t")))).append(',').append(e.optString("date")).append(',').append(e.optInt("k")).append(',')
                        .append(e.optString("h")).append(',').append(num(e.optDouble("price", Double.NaN), 2)).append(',').append(num(e.optDouble("pm", Double.NaN), 4)).append(',')
                        .append(num(e.optDouble("pf", Double.NaN), 4)).append(',').append(num(e.optDouble("ev", Double.NaN), 4)).append(',').append(num(e.optDouble("nw", Double.NaN), 4)).append(',')
                        .append(csv(e.optString("regime"))).append(',').append(e.optBoolean("done", false) ? 1 : 0).append(',').append(e.optString("target")).append(',')
                        .append(e.has("up") ? String.valueOf(e.optInt("up")) : "").append(',').append(e.has("move") ? num(e.optDouble("move", 0) * 100, 3) : "").append(',')
                        .append(e.has("trade") ? (e.optInt("trade") > 0 ? "LONG" : "SHORT") : "").append(',').append(e.has("stop") ? num(e.optDouble("stop", 0), 1) : "").append(',')
                        .append(e.has("pnl") ? num(e.optDouble("pnl", 0), 2) : "").append(',').append(e.has("pts") ? num(e.optDouble("pts", 0), 2) : "").append(',')
                        .append(e.has("stopped") ? (e.optBoolean("stopped", false) ? 1 : 0) : "").append('\n');
            }
        }
        n += entry(z, "live_forecast_log.csv", lv.toString());
        StringBuilder jr = new StringBuilder("date,horizon,side,lots,lot_size,entry_time,entry,exit_time,exit,points,net_rupees_after_charges,signal_prob,paper_entry,slippage_vs_paper_pts\n");
        java.text.SimpleDateFormat jf = new java.text.SimpleDateFormat("HH:mm", Locale.US);
        jf.setTimeZone(Collector.IST);
        for (Journal.Entry e : journal(base))
            jr.append(e.date).append(',').append(e.horizon).append(',').append(e.dir > 0 ? "LONG" : "SHORT").append(',').append(e.lots).append(',').append(e.lot).append(',')
                    .append(e.entryAt > 0 ? jf.format(new java.util.Date(e.entryAt)) : "").append(',').append(num(e.entry, 2)).append(',')
                    .append(e.exitAt > 0 ? jf.format(new java.util.Date(e.exitAt)) : "").append(',').append(num(e.exit, 2)).append(',').append(num(e.points(), 2)).append(',')
                    .append(num(e.pnl(), 2)).append(',').append(num(e.signalProb, 4)).append(',').append(num(e.paperEntry, 2)).append(',')
                    .append(Double.isNaN(e.paperEntry) ? "" : num(e.dir * (e.entry - e.paperEntry), 2)).append('\n');
        n += entry(z, "my_trades_journal.csv", jr.toString());
        StringBuilder op = new StringBuilder("id,strategy,horizon,expiry,paper,lots,lot,opened,closed,legs,net_premium_points,spot_at_entry,exit_below,exit_above,take_profit_rupees_per_lot,stop_rupees_per_lot,net_rupees_after_costs\n");
        java.text.SimpleDateFormat tf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        tf.setTimeZone(Collector.IST);
        for (OptionStrategy.Position p : optPositions(base)) {
            StringBuilder legs = new StringBuilder();
            for (OptionStrategy.Leg g : p.legs) legs.append(legs.length() > 0 ? " | " : "").append(g.label());
            op.append(csv(p.id)).append(',').append(csv(p.name)).append(',').append(p.horizon).append(',').append(p.expiry).append(',').append(p.paper ? 1 : 0).append(',')
                    .append(p.lots).append(',').append(p.lot).append(',').append(tf.format(new java.util.Date(p.openedAt))).append(',')
                    .append(p.closed ? tf.format(new java.util.Date(p.closedAt)) : "").append(',').append(csv(legs.toString())).append(',').append(num(p.net(), 2)).append(',')
                    .append(num(p.spotAtEntry, 2)).append(',').append(num(p.exitBelow, 2)).append(',').append(num(p.exitAbove, 2)).append(',')
                    .append(num(p.takeProfit, 0)).append(',').append(num(p.stopLoss, 0)).append(',').append(num(p.exitPnl, 2)).append('\n');
        }
        n += entry(z, "my_option_positions.csv", op.toString());
        n += entry(z, "live_market_recorder.csv", Recorder2.all(base));
        String rep = report(base);
        if (!rep.isEmpty()) n += entry(z, "training_report.txt", rep);
        if (settings != null) n += entry(z, "settings.txt", String.format(Locale.US,
                "trade_threshold=%.2f%nlot_size=%d%nslippage_points_per_side=%.2f%nstop_multiple_of_68pct_range=%.2f%n", settings.threshold, settings.lot, settings.slippagePts, settings.stopMult));
        z.finish();
        z.flush();
        return n;
    }

    static final String README = "FILES\n"
            + "summary_by_horizon.csv   one row per horizon: verdict and reasons, accuracy vs always guessing the usual side, Brier skill with its 90% bootstrap band,\n"
            + "                         up/flat/down accuracy, calibration error, simulated trades (net ₹ per lot after charges), profit factor, max drawdown.\n"
            + "confidence_buckets.csv   for each horizon: forecasts whose side probability was 50–60%, 60–70% … and how often they were right.\n"
            + "market_conditions.csv    accuracy and trade P&L by condition (trend up/down, sideways, high/low volatility, gaps, expiry, major event, normal day).\n"
            + "stress_days.csv          the same for hard days (large gaps, sharp reversals, VIX spikes, global shocks, RBI/Fed/Budget, expiry).\n"
            + "replay_forecasts.csv     EVERY replay forecast (every 15 minutes × every horizon): probabilities, direction, confidence, signal quality, whether it\n"
            + "                         was an act signal and why not, expected range/return, regime, target time and price, actual move, right or wrong, trade P&L.\n"
            + "replay_trades.csv        EVERY simulated trade: entry/exit date and time, side, prices, stop, points, charges, net ₹ per lot, cumulative ₹.\n"
            + "replay_features.csv      the 88 model inputs at every replay moment (scaled values as the models see them), for your own analysis.\n"
            + "leakage_audit.txt        the look-ahead and purge checks.\n"
            + "my_trades_journal.csv    the trades you marked \"I took this\": your fills, points, net ₹ after charges, and slippage vs the paper entry.\n"
            + "live_market_recorder.csv every few minutes of each live session: option chain (PCR, ATM IV, straddle, OI build-up, walls, max pain), futures\n"
            + "                         (basis, OI, order-book imbalance), breadth, VIX, GIFT Nifty, FII data — the app's own history of live-only data.\n"
            + "my_option_positions.csv  option ideas you saved from the strategy builder (paper or real): legs, exit plan and net ₹ after costs once closed.\n"
            + "live_forecast_log.csv    every live forecast this phone logged (every 30 min per horizon), its outcome once known, and paper trades.\n"
            + "validation.json          the raw report. training_report.txt: what the models learnt and how they tested. settings.txt: assumptions used.\n\n"
            + "NOTES\n"
            + "- Times are IST, the forecast time is after the 5-minute bar that just closed. Probabilities are 0–1; moves are in %.\n"
            + "- Replay forecasts come from models frozen before each block (walk-forward), through the same engine as live.\n"
            + "- Trades: Nifty futures, entry at the next 5-minute bar close ± slippage, exit at the horizon's end or the stop, approximate Zerodha charges.\n"
            + "- Past results do not guarantee future ones. Signals only — the app never places orders.\n";

    static String tableCsv(Validator.Report r, boolean stress) {
        StringBuilder b = new StringBuilder("horizon," + (stress ? "stress_day" : "condition") + ",forecasts,hit_rate,usual_side_rate,trades,net_rupees_per_lot\n");
        for (Validator.HReport x : r.horizons) for (Map.Entry<String, double[]> e : (stress ? x.stress : x.regimes).entrySet()) {
            double[] v = e.getValue();
            b.append(x.id).append(',').append(csv(e.getKey())).append(',').append((long) v[0]).append(',').append(num(v[1], 4)).append(',').append(num(v[2], 4)).append(',')
                    .append((long) v[3]).append(',').append(num(v[4], 2)).append('\n');
        }
        return b.toString();
    }

    static int entry(java.util.zip.ZipOutputStream z, String name, String text) throws Exception {
        z.putNextEntry(new java.util.zip.ZipEntry(name));
        z.write(text.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
        return 1;
    }

    static String num(double v, int dp) { return Double.isNaN(v) || Double.isInfinite(v) ? "" : String.format(Locale.US, "%." + dp + "f", v); }
    static String csv(String s) { return s == null ? "" : s.indexOf(',') >= 0 || s.indexOf('"') >= 0 ? "\"" + s.replace("\"", "\"\"") + "\"" : s; }

    // ================================================================== journal + risk guard

    static File journalFile(File base) { return new File(dir(base), "journal.json"); }

    public static synchronized List<Journal.Entry> journal(File base) {
        try { File f = journalFile(base); return f.exists() ? Journal.fromJson(new JSONArray(read(f))) : new ArrayList<>(); }
        catch (Exception e) { return new ArrayList<>(); }
    }

    static synchronized void saveJournal(File base, List<Journal.Entry> l) throws Exception { write(journalFile(base), Journal.toJson(l).toString()); }

    /** "I took this": records your real entry for a live signal. */
    public static synchronized Journal.Entry takeTrade(File base, String horizon, int dir, double fill, int lots, int lot, double prob, double paperEntry) throws Exception {
        List<Journal.Entry> l = journal(base);
        Journal.Entry e = new Journal.Entry();
        e.id = Long.toString(System.currentTimeMillis(), 36); e.date = Collector.day(new java.util.Date()); e.horizon = horizon; e.dir = dir;
        e.entry = fill; e.lots = Math.max(1, lots); e.lot = lot; e.entryAt = System.currentTimeMillis(); e.signalProb = prob; e.paperEntry = paperEntry;
        l.add(e);
        saveJournal(base, l);
        return e;
    }

    public static synchronized void closeTrade(File base, String id, double fill) throws Exception {
        List<Journal.Entry> l = journal(base);
        for (Journal.Entry e : l) if (e.id.equals(id) && e.open()) { e.exit = fill; e.exitAt = System.currentTimeMillis(); }
        saveJournal(base, l);
    }

    public static synchronized void deleteTrade(File base, String id) throws Exception {
        List<Journal.Entry> l = journal(base);
        l.removeIf(e -> e.id.equals(id));
        saveJournal(base, l);
    }

    /** Risk guard: blocks new act signals after a bad day, too many trades, right after a loss, or near the open / close. */
    static void applyGuard(IntelEngine.Forecast fc, File base, String today, int minute, Validator.Config cfg) {
        fc.guard = Journal.blocks(journal(base), today, minute, System.currentTimeMillis(), cfg.guard);
        if (fc.guard.isEmpty()) return;
        for (IntelEngine.HPred p : fc.preds) {
            if (!p.tradeable) continue;
            p.tradeable = false;
            p.gate.add(0, "risk guard: " + fc.guard.get(0));
            p.signalQuality = "LOW";
            p.signalReason = "risk guard: " + fc.guard.get(0);
        }
    }

    // ================================================================== option strategy builder

    /** Everything the Options page shows for one horizon. */
    public static final class OptionsPlan {
        public OptionStrategy.Chain chain;
        public OptionStrategy.Market market;
        public OptionStrategy.View view;
        public List<OptionStrategy.Fair> fair = new ArrayList<>();
        public List<OptionStrategy.Idea> ideas = new ArrayList<>();
        public IntelEngine.HPred pred;
        public final List<String> notes = new ArrayList<>();
    }

    /** One expiry of the snapshot's chain for the builder (next = the following weekly expiry). Null if not loaded. */
    public static OptionStrategy.Chain optChain(Snapshot s, boolean next) {
        List<com.krish.niftydirection.model.OptionRow> rows = next ? s.chain2 : s.chain;
        if (rows == null || rows.isEmpty() || s.nifty == null || !s.nifty.ok()) return null;
        OptionStrategy.Chain c = new OptionStrategy.Chain();
        c.spot = s.spot(); c.rows = rows; c.step = s.strikeStep > 0 ? s.strikeStep : 50; c.lot = s.lotSize > 0 ? s.lotSize : 65;
        c.expiry = next ? s.expiry2 : s.expiry; c.days = next ? s.opt2DaysToExpiry : s.optDaysToExpiry;
        c.years = com.krish.niftydirection.engine.Greeks.yearsToExpiry(s.today, s.minute, c.expiry, c.days);
        return c;
    }

    /** Annualised 10-day realised volatility of Nifty from the snapshot's daily candles. */
    public static double realisedVol(Snapshot s) {
        List<Double> closes = new ArrayList<>();
        for (com.krish.niftydirection.model.Candle c : s.niftyDaily) if (c.c > 0) closes.add(c.c);
        return OptionStrategy.realised(closes, 10);
    }

    /** horizonId null = the current signal's horizon, else 1 day, else the first horizon with a forecast. */
    public static OptionsPlan optionsPlan(Snapshot s, IntelEngine.Forecast fc, String horizonId, Validator.Config cfg, long now) {
        OptionsPlan plan = new OptionsPlan();
        OptionStrategy.Chain near = optChain(s, false);
        if (near == null) { plan.notes.add("Option chain not loaded yet. Log in to Kite and refresh."); return plan; }
        IntelEngine.HPred p = null;
        if (fc != null) {
            for (IntelEngine.HPred x : fc.preds) if (x.has() && horizonId != null && x.hz.id.equals(horizonId)) p = x;
            if (p == null && fc.signal >= 0 && fc.signal < fc.preds.size() && fc.preds.get(fc.signal).has()) p = fc.preds.get(fc.signal);
            if (p == null) for (IntelEngine.HPred x : fc.preds) if (x.has() && "1D".equals(x.hz.id)) p = x;
            if (p == null) for (IntelEngine.HPred x : fc.preds) if (x.has()) { p = x; break; }
        }
        plan.pred = p;
        OptionStrategy.View v = new OptionStrategy.View();
        if (p != null) {
            v.horizon = p.hz.id; v.horizonLabel = p.hz.label; v.pUp = p.pFinal; v.sigma = p.range68; v.act = p.tradeable; v.validation = p.validation;
            v.exitBy = p.hz.swing() ? OptionStrategy.exitBy(now, 0, p.hz.sessions) : OptionStrategy.exitBy(now, p.hz.minutesAt(fc.k), 0);
            v.blocks.addAll(fc.guard);
        } else {
            v.horizon = "1D"; v.horizonLabel = "1 day (no AI forecast yet)"; v.pUp = 0.5;
            v.exitBy = OptionStrategy.exitBy(now, 0, 1);
            plan.notes.add("No AI forecast yet — ideas assume 50/50, so none can show an edge.");
        }
        v.years = Math.max(5, (v.exitBy - now) / 60000.0) / (365.0 * 1440);
        OptionStrategy.Chain c = near;
        OptionStrategy.Chain next = optChain(s, true);
        if (next != null && (near.days == 0 || near.years - v.years < 0.5 / 365)) {
            c = next;
            plan.notes.add("Uses the " + next.expiry + " expiry: the " + near.expiry + (near.days == 0 ? " expiry is today" : " expiry ends before this trade would") + ".");
        }
        if (!s.live) plan.notes.add("Market closed: premiums are the last traded prices — re-check at the open before acting.");
        double rv = realisedVol(s);
        plan.chain = c;
        plan.market = OptionStrategy.read(c, rv);
        double sigDay = Double.NaN;
        if (fc != null) for (IntelEngine.HPred x : fc.preds) if (x.has() && "1D".equals(x.hz.id)) sigDay = x.range68;
        plan.fair = OptionStrategy.fair(c, plan.market, OptionStrategy.fairVol(rv, sigDay), 6);
        OptionStrategy.Config oc = new OptionStrategy.Config();
        oc.slip = cfg.slippagePts;
        if (cfg.guard != null) oc.riskBudget = cfg.guard.maxLossRupees;
        plan.view = v;
        plan.ideas = OptionStrategy.build(c, plan.market, v, oc);
        return plan;
    }

    static File optFile(File base) { return new File(dir(base), "option_positions.json"); }

    public static synchronized List<OptionStrategy.Position> optPositions(File base) {
        try { File f = optFile(base); return f.exists() ? OptionStrategy.fromJson(new JSONArray(read(f))) : new ArrayList<>(); }
        catch (Exception e) { return new ArrayList<>(); }
    }

    static synchronized void saveOptPositions(File base, List<OptionStrategy.Position> l) throws Exception { write(optFile(base), OptionStrategy.toJson(l).toString()); }

    public static synchronized OptionStrategy.Position takeOption(File base, OptionsPlan plan, OptionStrategy.Idea idea, int lots, boolean paper) throws Exception {
        List<OptionStrategy.Position> l = optPositions(base);
        OptionStrategy.Position p = OptionStrategy.open(idea, plan.chain, plan.view.horizon, lots, paper, System.currentTimeMillis());
        l.add(p);
        saveOptPositions(base, l);
        return p;
    }

    /** Live status of a position against the snapshot (null when its expiry is not in the loaded chains). */
    public static OptionStrategy.Status optStatus(Snapshot s, IntelEngine.Forecast fc, OptionStrategy.Position p, Validator.Config cfg) {
        OptionStrategy.Chain c = optChain(s, false);
        if (c == null || !c.expiry.equals(p.expiry)) c = optChain(s, true);
        if (c == null || !c.expiry.equals(p.expiry)) return null;
        double pUp = Double.NaN;
        if (fc != null) for (IntelEngine.HPred x : fc.preds) if (x.has() && x.hz.id.equals(p.horizon)) pUp = x.pFinal;
        OptionStrategy.Config oc = new OptionStrategy.Config();
        oc.slip = cfg.slippagePts;
        return OptionStrategy.check(p, c, pUp, System.currentTimeMillis(), oc);
    }

    public static synchronized void closeOption(File base, String id, OptionStrategy.Status st) throws Exception {
        List<OptionStrategy.Position> l = optPositions(base);
        for (OptionStrategy.Position p : l) if (p.id.equals(id) && !p.closed) OptionStrategy.close(p, st, System.currentTimeMillis());
        saveOptPositions(base, l);
    }

    public static synchronized void deleteOption(File base, String id) throws Exception {
        List<OptionStrategy.Position> l = optPositions(base);
        l.removeIf(p -> p.id.equals(id));
        saveOptPositions(base, l);
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

package com.krish.niftydirection.data;

import com.krish.niftydirection.forecast.Forecaster;
import com.krish.niftydirection.forecast.History;
import com.krish.niftydirection.model.Candle;
import com.krish.niftydirection.model.Quote;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Feeds the forecaster from Kite (+ Yahoo daily closes):
 *  - history(): ~3 years of 5-minute bars of Nifty, Bank Nifty, India VIX and the sector indices, plus daily Nifty / VIX
 *    and global closes. 5-minute data is fetched in fixed 95-day blocks, so finished blocks are downloaded only once.
 *  - train(): tests and fits one model per horizon and saves them (files/forecast/model_*.json).
 *  - live(): the same History plus today's bars so far → one forecast per horizon. Same code path as training.
 */
public class ForecastRunner {
    public static final int YEARS_DAYS = 3 * 365 + 10;
    static final String ANCHOR = "2018-01-01";
    static final int CHUNK = 95;
    static final String[][] GLOBAL = HistoryLoader.GLOBAL;
    static final long RETRAIN_MS = 7L * 24 * 3600 * 1000;

    // ================================================================== history

    /** Tokens: key → instrument token. Keys: NIFTY, BANK, VIX, SEC:<sector>. */
    static LinkedHashMap<String, Long> tokens(Kite kite) throws Exception {
        List<String> want = new ArrayList<>(Arrays.asList(HistoryLoader.NIFTY, HistoryLoader.BANK, HistoryLoader.VIX));
        for (String[] x : Collector.SECTORS) want.add(x[1]);
        Map<String, Quote> q = kite.quote(want);
        if (!q.containsKey(HistoryLoader.NIFTY)) throw new Exception("Kite gave no Nifty token.");
        LinkedHashMap<String, Long> t = new LinkedHashMap<>();
        t.put("NIFTY", q.get(HistoryLoader.NIFTY).token);
        if (q.containsKey(HistoryLoader.BANK)) t.put(History.BANK, q.get(HistoryLoader.BANK).token);
        if (q.containsKey(HistoryLoader.VIX)) t.put(History.VIX, q.get(HistoryLoader.VIX).token);
        for (String[] x : Collector.SECTORS) if (q.containsKey(x[1])) t.put("SEC:" + x[0], q.get(x[1]).token);
        return t;
    }

    /** Fixed calendar blocks [a, b] that cover [from, to]. */
    static List<String[]> grid(String from, String to) {
        List<String[]> out = new ArrayList<>();
        String a = ANCHOR;
        while (a.compareTo(to) <= 0) {
            String b = HistoryLoader.plusDays(a, CHUNK - 1);
            if (b.compareTo(from) >= 0) out.add(new String[]{a, b});
            a = HistoryLoader.plusDays(b, 1);
        }
        return out;
    }

    /** Past sessions only (dates before `today`). Cached for the day in files/forecast/history.bin.gz. */
    public static History history(Kite kite, File dir, String today, AtomicBoolean cancel, HistoryLoader.Progress pr) throws Exception {
        File fd = new File(dir, "forecast");
        fd.mkdirs();
        File f = new File(fd, "history.bin.gz"), stamp = new File(fd, "history_date.txt");
        if (f.exists() && stamp.exists() && today.equals(read(stamp).trim())) {
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(new FileInputStream(f))))) { return History.read(in); }
            catch (Exception ignored) { }
        }
        HistoryLoader rl = new HistoryLoader(kite, dir, cancel);
        pr.step("Finding instruments…", 0, 1);
        LinkedHashMap<String, Long> tok = tokens(kite);
        String from = Collector.daysAgo(today, YEARS_DAYS), yday = Collector.daysAgo(today, 1);
        List<String[]> blocks = grid(from, yday);
        int total = tok.size() * blocks.size() + 2 + GLOBAL.length, done = 0;
        History h = new History();
        TreeMap<String, History.Day> byDate = new TreeMap<>();
        for (Map.Entry<String, Long> e : tok.entrySet()) {
            String key = e.getKey();
            if (key.startsWith("SEC:")) h.sectors.add(key);
            for (String[] b : blocks) {
                rl.checkCancel();
                pr.step("5-min history: " + key.replace("SEC:", "") + " " + b[0].substring(0, 7), ++done, total);
                List<Candle> cs;
                if (b[1].compareTo(today) < 0) cs = rl.fiveMin(e.getValue(), b[0], b[1], today);   // finished block: cached
                else cs = kite.historical(e.getValue(), "5minute", b[0] + " 09:00:00", yday + " 15:35:00", false);
                for (Candle c : cs) {
                    if (c.date.compareTo(from) < 0 || c.date.compareTo(today) >= 0) continue;
                    int s = History.slot(c.minute);
                    if (s < 0 || s >= History.BARS) continue;
                    if (key.equals("NIFTY")) {
                        History.Day d = byDate.computeIfAbsent(c.date, History.Day::new);
                        d.o[s] = (float) c.o; d.h[s] = (float) c.h; d.l[s] = (float) c.l; d.c[s] = (float) c.c;
                    } else {
                        History.Day d = byDate.get(c.date);
                        if (d != null) d.aux(key)[s] = (float) c.c;
                    }
                }
            }
        }
        for (History.Day d : byDate.values()) { finishDay(d); h.days.add(d); }
        rl.checkCancel();
        pr.step("Daily history…", ++done, total);
        for (Candle c : rl.daily(tok.get("NIFTY"), today, YEARS_DAYS + 120))
            if (c.date.compareTo(today) < 0) h.niftyDaily.put(c.date, new double[]{c.o, c.h, c.l, c.c});
        if (tok.containsKey(History.VIX))
            for (Candle c : rl.daily(tok.get(History.VIX), today, YEARS_DAYS + 420)) if (c.date.compareTo(today) < 0) h.vixDaily.put(c.date, c.c);
        pr.step("Expiry days…", ++done, total);
        Set<String> weekly = new HashSet<>();
        List<String> sessions = new ArrayList<>(h.niftyDaily.keySet());
        HistoryLoader.expiries(sessions, weekly);
        h.expiries.addAll(weekly);
        for (String[] g : GLOBAL) {
            rl.checkCancel();
            pr.step("Global history: " + g[0], ++done, total);
            TreeMap<String, Double> m = HistoryLoader.yahoo(dir, g[1], today);
            if (m != null && m.size() > 100) h.global.put(g[0], m);
        }
        try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(new FileOutputStream(f))))) { h.write(o); }
        if (HistoryLoader.settled()) HistoryLoader.write(stamp, today);   // before 6:00 IST: rebuild later with settled global closes
        return h;
    }

    /** Sets `bars` = completed slots; a session with too few bars (special / broken) gets 0 and is never used. */
    static void finishDay(History.Day d) {
        int n = 0, last = -1;
        for (int i = 0; i < History.BARS; i++) if (!Float.isNaN(d.c[i])) { n++; last = i; }
        d.bars = n >= 60 ? last + 1 : 0;
        if (d.bars >= 72) d.bars = History.BARS;   // a finished normal session (a missing last bar or two is fine)
    }

    // ================================================================== training

    public static class TrainOutput {
        public final List<Forecaster.Model> models = new ArrayList<>();
        public String text = "";
    }

    public static TrainOutput train(Kite kite, File dir, String today, AtomicBoolean cancel, HistoryLoader.Progress pr) throws Exception {
        History h = history(kite, dir, today, cancel, pr);
        return train(h, dir, cancel, pr);
    }

    public static TrainOutput train(History h, File dir, AtomicBoolean cancel, HistoryLoader.Progress pr) throws Exception {
        TrainOutput out = new TrainOutput();
        File fd = new File(dir, "forecast");
        fd.mkdirs();
        StringBuilder t = new StringBuilder();
        int usable = 0;
        for (History.Day d : h.days) if (d.bars == History.BARS) usable++;
        t.append(String.format(Locale.US, "Models learnt %s from %d sessions (%s → %s).%n",
                new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new java.util.Date()), usable,
                h.days.isEmpty() ? "" : h.days.get(0).date, h.days.isEmpty() ? "" : h.days.get(h.days.size() - 1).date));
        t.append("Inputs: Nifty price action, daily context, Bank Nifty / Financials, India VIX, sector indices, global closes (").append(h.global.keySet()).append(").\n");
        int i = 0;
        for (Forecaster.Horizon hz : Forecaster.HORIZONS) {
            if (cancel != null && cancel.get()) throw new HistoryLoader.CancelledException();
            pr.step("Learning " + hz.label + "…", i++, Forecaster.HORIZONS.length);
            Forecaster.Model m = Forecaster.train(h, hz);
            out.models.add(m);
            HistoryLoader.write(new File(fd, "model_" + hz.id + ".json"), Forecaster.toJson(m).toString());
            Forecaster.Info in = m.info;
            t.append(String.format(Locale.US, "%s: %,d examples from %d sessions · went up %.0f%% of the time · usual move %.2f%%%n",
                    hz.label, in.samples, in.days, in.upShare * 100, in.medMove * 100));
            t.append("  ").append(testLine(in)).append('\n');
            Forecaster.Model mo = Forecaster.train(h, hz, true);   // pre-open model: from the opening price
            HistoryLoader.write(new File(fd, "model_P_" + hz.id + ".json"), Forecaster.toJson(mo).toString());
            in = mo.info;
            t.append(String.format(Locale.US, "  from the open (%s): %,d sessions · went up %.0f%% · usual move %.2f%%%n",
                    hz.bars < 0 ? "to today's close" : hz.label.toLowerCase(Locale.US), in.samples, in.upShare * 100, in.medMove * 100));
            t.append("    ").append(testLine(in)).append('\n');
        }
        out.text = t.toString();
        HistoryLoader.write(new File(fd, "report.txt"), out.text);
        pr.step("Done", Forecaster.HORIZONS.length, Forecaster.HORIZONS.length);
        return out;
    }

    /** One line on how the model did on the sessions it never saw. */
    public static String testLine(Forecaster.Info in) {
        if (!in.tested()) return "not tested (too little history for a held-back test)";
        return String.format(Locale.US, "test on last %d unseen sessions (from %s): right %.1f%% vs %.1f%% always guessing %s · skill %+.1f%% · %s",
                in.testDays, in.testFrom, in.hit * 100, in.baseHit * 100, in.upShare >= 0.5 ? "UP" : "DOWN", in.skill() * 100,
                in.proven() ? "EDGE" : "NO PROVEN EDGE");
    }

    public static List<Forecaster.Model> models(File dir) { return models(dir, false); }

    /** True if any model file exists (even from an older version that must be retrained). */
    public static boolean hasModelFiles(File dir) {
        File[] fs = new File(dir, "forecast").listFiles((x, n) -> n.startsWith("model_"));
        return fs != null && fs.length > 0;
    }

    public static List<Forecaster.Model> models(File dir, boolean open) {
        List<Forecaster.Model> out = new ArrayList<>();
        for (Forecaster.Horizon hz : Forecaster.HORIZONS) {
            File f = new File(new File(dir, "forecast"), (open ? "model_P_" : "model_") + hz.id + ".json");
            if (!f.exists()) continue;
            try {
                Forecaster.Model m = Forecaster.fromJson(new JSONObject(read(f)));
                if (m.version == Forecaster.VERSION) out.add(m);
            } catch (Exception ignored) { }
        }
        return out;
    }

    public static String report(File dir) {
        File f = new File(new File(dir, "forecast"), "report.txt");
        try { return f.exists() ? read(f) : ""; } catch (Exception e) { return ""; }
    }

    /** True when there are no models, or they are older than a week. */
    public static boolean needsTraining(File dir) {
        List<Forecaster.Model> m = models(dir);
        if (m.size() < Forecaster.HORIZONS.length || models(dir, true).size() < Forecaster.HORIZONS.length) return true;
        long oldest = Long.MAX_VALUE;
        for (Forecaster.Model x : m) oldest = Math.min(oldest, x.at);
        return System.currentTimeMillis() - oldest > RETRAIN_MS;
    }

    // ================================================================== live

    public static class Live {
        public final List<Forecaster.Prediction> predictions = new ArrayList<>();
        public String asOf = "", date = "", note = "";
        public double price = Double.NaN, dayChange = Double.NaN, hourChange = Double.NaN;
        public boolean intraday, preOpen;
        public long at = System.currentTimeMillis();
    }

    public static volatile Live last;

    /**
     * @param minute IST minutes since midnight now
     */
    public static Live live(Kite kite, File dir, String today, int minute, AtomicBoolean cancel, HistoryLoader.Progress pr) throws Exception {
        return live(kite, dir, today, minute, Double.NaN, cancel, pr);
    }

    /**
     * @param expectedOpen before the open on a trading day: the opening price GIFT Nifty points to (else NaN).
     *                     Then the pre-open models forecast from that open: first 1 / 3 / 6 hours and today's close.
     */
    public static Live live(Kite kite, File dir, String today, int minute, double expectedOpen, AtomicBoolean cancel, HistoryLoader.Progress pr) throws Exception {
        List<Forecaster.Model> models = models(dir);
        if (models.isEmpty()) throw new Exception("No forecast models yet — tap “Train models”.");
        History base = history(kite, dir, today, cancel, pr);
        History h = base.copy();
        Live out = new Live();
        if (minute >= History.OPEN_MIN + 5) {
            pr.step("Today's bars…", 0, 1);
            LinkedHashMap<String, Long> tok = tokens(kite);
            History.Day d = new History.Day(today);
            int cut = Math.min(minute, 15 * 60 + 30);
            for (Map.Entry<String, Long> e : tok.entrySet()) {
                if (cancel != null && cancel.get()) throw new HistoryLoader.CancelledException();
                List<Candle> cs = kite.historical(e.getValue(), "5minute", today + " 09:00:00", today + " 15:35:00", false);
                for (Candle c : cs) {
                    if (!today.equals(c.date) || c.minute + 5 > cut) continue;   // only bars that have closed
                    int s = History.slot(c.minute);
                    if (s < 0 || s >= History.BARS) continue;
                    if (e.getKey().equals("NIFTY")) { d.o[s] = (float) c.o; d.h[s] = (float) c.h; d.l[s] = (float) c.l; d.c[s] = (float) c.c; }
                    else d.aux(e.getKey())[s] = (float) c.c;
                }
                if (e.getKey().equals("NIFTY") && Double.isNaN(History.last(d.c, History.BARS))) break;   // no session today
            }
            int last = -1;
            for (int i = 0; i < History.BARS; i++) if (!Float.isNaN(d.c[i])) last = i;
            if (last >= 0) { d.bars = last + 1; h.days.add(d); out.intraday = true; }
        }
        if (!out.intraday && !Double.isNaN(expectedOpen) && expectedOpen > 0 && minute < History.OPEN_MIN + 15
                && !h.days.isEmpty() && h.days.get(h.days.size() - 1).date.compareTo(today) < 0) {
            List<Forecaster.Model> om = models(dir, true);
            if (!om.isEmpty()) {
                History.Day d = new History.Day(today);
                d.o[0] = (float) expectedOpen;
                d.bars = 0;
                h.days.add(d);
                out.preOpen = true;
                models = om;
            }
        }
        int di = h.days.size() - 1;
        if (di < 0) throw new Exception("No history.");
        History.Day day = h.days.get(di);
        int k = out.intraday ? day.bars : out.preOpen ? 0 : History.BARS;
        out.date = day.date;
        int m = History.minuteAfter(k);
        out.asOf = (out.intraday ? "Today " : day.date + " close, ") + String.format(Locale.US, "%d:%02d", m / 60, m % 60);
        out.price = out.preOpen ? day.open() : day.closeAt(k);
        if (out.preOpen) out.asOf = "Before the open — expected open " + String.format(Locale.US, "%,.0f", out.price) + " (from GIFT Nifty)";
        Map.Entry<String, double[]> pe = h.niftyDaily.lowerEntry(day.date);
        if (pe != null) out.dayChange = (out.price / pe.getValue()[3] - 1) * 100;
        double hr = day.closeAt(Math.max(1, k - 12));
        if (k > 12) out.hourChange = (out.price / hr - 1) * 100;
        if (out.preOpen) out.note = "Pre-open forecast: learnt from 3 years of openings. 'Today' = change the expected open makes vs yesterday's close.";
        else if (!out.intraday) out.note = "Market closed — forecasts are from the last close. The 1-hour forecast is about the first hour of the next session.";
        for (Forecaster.Model mo : models) out.predictions.add(Forecaster.predict(mo, h, di, k));
        last = out;
        return out;
    }

    static String read(File f) throws Exception { return new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); }
}

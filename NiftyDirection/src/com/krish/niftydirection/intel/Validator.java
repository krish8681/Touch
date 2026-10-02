package com.krish.niftydirection.intel;

import com.krish.niftydirection.forecast.History;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

/**
 * Pre-Live Validation Engine.
 *
 * Replays the newest ~12 months of sessions as if the app had been running live:
 *  - walk-forward: the replay window is split into blocks; each block is predicted by models learnt ONLY from samples made
 *    before the block's first day whose targets also ended before it (Trainer cutoff), then frozen for the whole block;
 *  - every 15 minutes of every replayed session goes through the SAME live engine (IntelEngine.forecast), seeing only
 *    finished bars and daily data dated before that day;
 *  - each forecast is scored against what happened: direction, up/flat/down, confidence buckets, market regimes, stress days;
 *  - "act" signals (the live trade gate) are traded in a futures simulation with a 5-minute execution delay, slippage,
 *    a stop at a multiple of the expected range, exit at the horizon's end and Zerodha-style charges;
 *  - leakage audits: features recomputed on a history cut at the forecast moment must match exactly, every block model must
 *    have learnt only from before its block, and an implausibly good intraday hit rate is flagged;
 *  - each horizon gets PASS / WARN / FAIL. Only PASS horizons are allowed to show "strong enough to act on" live.
 */
public final class Validator {
    private Validator() {}

    public static final int REPLAY_SESSIONS = 250, BLOCKS = 4, MIN_TRAIN_SESSIONS = 300, AUDIT = 40, MIN_BUCKET = 30;
    public static final int VERSION = 1;

    public interface Progress { void step(String what, int done, int total); }

    /** Strategy and cost assumptions (Settings). */
    public static final class Config {
        public int lot = 65;                    // Nifty futures lot size (check the current one with your broker)
        public double slippagePts = 1.0;        // per side
        public double stopMult = 1.0;           // stop = this × the 68% expected range (0 = no stop)
        public double threshold = 0.62;         // trade gate probability
        public Set<String> eventDates = new HashSet<>();   // RBI / Fed / Budget days, for the regime and stress tables
    }

    /** Approximate Zerodha charges for one futures round trip, in ₹ (brokerage, STT, exchange, SEBI, stamp, GST). */
    public static double charges(double entry, double exit, int dir, int lot) {
        double buy = (dir > 0 ? entry : exit) * lot, sell = (dir > 0 ? exit : entry) * lot, turnover = buy + sell;
        double brokerage = Math.min(20, 0.0003 * buy) + Math.min(20, 0.0003 * sell);
        double stt = 0.0002 * sell, exch = 0.0000173 * turnover, sebi = 0.000001 * turnover, stamp = 0.00002 * buy;
        return brokerage + stt + exch + sebi + stamp + 0.18 * (brokerage + exch + sebi);
    }

    /** One simulated trade. */
    public static final class Trade {
        public String date = "", exitDate = "", horizon = "";
        public int k, exitSlot, dir;
        public double entry, exit, pts, rupees, charges;
        public boolean stopped;
    }

    /**
     * Simulates a trade on a forecast made after k bars of session d: enter at the close of the next 5-minute bar (execution
     * delay) ± slippage, stop at stopDist points (0 = none) checked on every bar's high/low, otherwise exit at the horizon's
     * target bar close ± slippage. Null when the path is not in the history (missing session, end of data).
     */
    public static Trade simulate(History h, int[] sidx, int d, int k, Horizon hz, int dir, double stopDist, Config cfg) {
        int[] spec = Trainer.targetSpec(k, hz);
        if (spec == null || k >= History.BARS || dir == 0) return null;
        int dd = d + spec[0], endSlot = spec[1] < 0 ? History.BARS - 1 : spec[1];
        if (dd >= h.days.size() || sidx[dd] - sidx[d] != dd - d) return null;
        if (h.days.get(dd).bars < endSlot + 1) return null;   // the target bar has not closed yet (live paper trade still open)
        History.Day day = h.days.get(d);
        if (Float.isNaN(day.c[k])) return null;
        Trade t = new Trade();
        t.horizon = hz.id; t.date = day.date; t.k = k; t.dir = dir;
        t.entry = day.c[k] + dir * cfg.slippagePts;
        double stop = stopDist > 0 ? t.entry - dir * stopDist : Double.NaN;
        double last = Double.NaN;
        for (int x = d; x <= dd; x++) {
            History.Day b = h.days.get(x);
            int from = x == d ? k + 1 : 0, to = x == dd ? endSlot : History.BARS - 1;
            for (int s = from; s <= to; s++) {
                if (Float.isNaN(b.c[s])) continue;
                if (!Double.isNaN(stop) && (dir > 0 ? b.l[s] <= stop : b.h[s] >= stop)) {
                    t.exit = stop - dir * cfg.slippagePts; t.stopped = true; t.exitDate = b.date; t.exitSlot = s;
                    return finish(t, cfg);
                }
                last = b.c[s]; t.exitDate = b.date; t.exitSlot = s;
            }
        }
        if (Double.isNaN(last)) return null;
        t.exit = last - dir * cfg.slippagePts;
        return finish(t, cfg);
    }

    static Trade finish(Trade t, Config cfg) {
        t.pts = t.dir * (t.exit - t.entry);
        t.charges = charges(t.entry, t.exit, t.dir, cfg.lot);
        t.rupees = t.pts * cfg.lot - t.charges;
        return t;
    }

    // ================================================================== report

    public static final class HReport {
        public String id = "", label = "", verdict = "WARN";
        public final List<String> reasons = new ArrayList<>();
        public int n, sessions;
        public double hit = Double.NaN, base = Double.NaN, brier = Double.NaN, baseBrier = Double.NaN, skill = Double.NaN, skillLo = Double.NaN, skillHi = Double.NaN;
        public double acc3 = Double.NaN, base3 = Double.NaN, calErr = Double.NaN;
        /** Confidence buckets of the forecast side's probability: {from, n, mean predicted, share right}. */
        public final List<double[]> buckets = new ArrayList<>();
        /** Regime / stress name → {n, hit, base, trades, net ₹}. */
        public final Map<String, double[]> regimes = new LinkedHashMap<>(), stress = new LinkedHashMap<>();
        public int trades, wins, stops, worstStreak;
        public double netPts, netRupees, chargesRupees, maxDD, profitFactor = Double.NaN, avgRupees = Double.NaN;
    }

    public static final class Report {
        public int version = VERSION;
        public long at = System.currentTimeMillis();
        public String from = "", to = "", verdict = "FAIL", summary = "";
        public int sessions, blocks, auditChecked, auditFailed;
        public boolean purgeOk = true;
        public final List<String> audit = new ArrayList<>(), ready = new ArrayList<>();
        public final List<HReport> horizons = new ArrayList<>();
        public int lot; public double slippagePts, stopMult, threshold;
        public HReport get(String id) { for (HReport r : horizons) if (r.id.equals(id)) return r; return null; }
        /** Row-level replay data for export; only present right after run(). */
        transient Detail detail;
        /** Writes the row-level replay data as gzipped CSVs into `dir` (replay_forecasts, replay_trades, replay_features). */
        public void saveDetail(java.io.File dir) throws java.io.IOException { if (detail != null) { Validator.saveDetail(this, dir); detail = null; } }
    }

    static final class Rec {
        String date, hz = "", tdate = "", quality = "", gate = "", regime = "", direction = ""; int d, k, block, conf; double p, side, move, range68, price, target;
        double pUp3 = Double.NaN, pFlat = Double.NaN, pDown3 = Double.NaN, expRet = Double.NaN; int dir, y; boolean flat, tradeable; int pred3 = -1, act3;
        List<String> regimes, stress;
        Trade trade;
    }

    /** Detail kept for export (not saved in validation.json): every replay forecast, every simulated trade, the inputs per moment. */
    static final class Detail {
        final List<Rec> recs = new ArrayList<>();
        final List<Object[]> features = new ArrayList<>();   // {date, k, block, double[] features, regime label}
    }

    public static Report run(History h, Config cfg, Progress pr) {
        Report rep = new Report();
        rep.lot = cfg.lot; rep.slippagePts = cfg.slippagePts; rep.stopMult = cfg.stopMult; rep.threshold = cfg.threshold;
        pr.step("Preparing the replay…", 0, BLOCKS + 2);
        Trainer.Dataset ds = Trainer.dataset(h);
        int[] sidx = h.sessionIndex();
        List<String> sessions = new ArrayList<>(h.niftyDaily.keySet());
        Map<String, Integer> pos = new HashMap<>();
        for (int i = 0; i < sessions.size(); i++) pos.put(sessions.get(i), i);
        java.util.TreeSet<String> dset = new java.util.TreeSet<>(ds.date);
        String[] D = dset.toArray(new String[0]);
        int nRep = Math.min(REPLAY_SESSIONS, D.length - MIN_TRAIN_SESSIONS);
        if (nRep < 60) {
            rep.summary = "Not enough history for a replay (" + D.length + " sessions; needs " + (MIN_TRAIN_SESSIONS + 60) + ").";
            return rep;
        }
        String[] win = Arrays.copyOfRange(D, D.length - nRep, D.length);
        rep.from = win[0]; rep.to = win[win.length - 1]; rep.sessions = nRep; rep.blocks = BLOCKS;

        Detail detail = new Detail();
        rep.detail = detail;
        Map<String, List<Rec>> recs = new LinkedHashMap<>();
        for (Horizon hz : Horizon.ALL) recs.put(hz.id, new ArrayList<>());
        Map<String, List<String>> stressOf = new HashMap<>();
        int per = (nRep + BLOCKS - 1) / BLOCKS;
        for (int b = 0; b < BLOCKS; b++) {
            int a = b * per;
            if (a >= nRep) break;
            String cut = win[a], end = a + per < nRep ? win[a + per] : "9999";
            pr.step(String.format(Locale.US, "Replay block %d of %d: learning from data before %s…", b + 1, BLOCKS, cut), b + 1, BLOCKS + 2);
            List<HorizonModel> models = new ArrayList<>();
            for (Horizon hz : Horizon.ALL) {
                HorizonModel m = Trainer.train(h, ds, hz, cut);
                if (m.meta == null) continue;
                if (m.info.to.compareTo(cut) >= 0) { rep.purgeOk = false; rep.audit.add(hz.label + " model of block " + (b + 1) + " saw data from " + m.info.to); }
                models.add(m);
            }
            // forecasts every 15 minutes (not at the open: live, the open is only an estimate; not after the close)
            for (int i = 0; i < ds.size(); i++) {
                String date = ds.date.get(i);
                if (date.compareTo(cut) < 0 || date.compareTo(end) >= 0) continue;
                int d = ds.d.get(i), k = ds.k.get(i);
                if (k == 0 || k >= History.BARS) continue;
                IntelEngine.Forecast fc = IntelEngine.forecast(models, h, d, k, false, null, null, null, cfg.threshold);
                List<String> rg = regimes(h, fc, cfg);
                List<String> st = stressOf.computeIfAbsent(date, x -> stress(h, d, fc.features, cfg));
                detail.features.add(new Object[]{date, k, b + 1, fc.features, fc.regime.label() + " · " + fc.regime.detail()});
                for (IntelEngine.HPred p : fc.preds) {
                    if (!p.has()) continue;
                    Object[] t = Trainer.target(h, sidx, sessions, pos, ds, i, p.hz);
                    if (t == null) continue;
                    double tp = (Double) t[0], price = ds.price.get(i);
                    if (tp == price) continue;
                    Rec r = new Rec();
                    r.date = date; r.d = d; r.k = k; r.p = p.pFinal; r.side = Math.max(p.pFinal, 1 - p.pFinal); r.dir = p.pFinal >= 0.5 ? 1 : -1;
                    r.move = Math.log(tp / price); r.y = tp > price ? 1 : 0; r.range68 = p.range68;
                    double sh = ds.sigma.get(i) * Math.sqrt(p.hz.minutesAt(k) / 375.0);
                    r.flat = Math.abs(r.move) < HorizonModel.FLAT_Z * sh;
                    r.act3 = r.flat ? 1 : r.y == 1 ? 0 : 2;
                    if (!Double.isNaN(p.pFlat)) r.pred3 = p.pUp3 >= p.pFlat && p.pUp3 >= p.pDown3 ? 0 : p.pFlat >= p.pDown3 ? 1 : 2;
                    r.tradeable = p.tradeable;
                    r.regimes = rg; r.stress = st;
                    r.hz = p.hz.id; r.block = b + 1; r.price = price; r.target = tp; r.tdate = (String) t[1];
                    r.conf = p.confidence; r.quality = p.signalQuality; r.gate = p.gate.isEmpty() ? "" : p.gate.get(0); r.regime = fc.regime.label();
                    r.direction = p.direction; r.pUp3 = p.pUp3; r.pFlat = p.pFlat; r.pDown3 = p.pDown3; r.expRet = p.expReturn;
                    recs.get(p.hz.id).add(r);
                    detail.recs.add(r);
                }
            }
        }
        pr.step("Scoring, simulating trades, checking for leakage…", BLOCKS + 1, BLOCKS + 2);
        audit(h, win, rep);
        for (Horizon hz : Horizon.ALL) rep.horizons.add(score(h, sidx, hz, recs.get(hz.id), cfg, rep));
        verdicts(rep);
        pr.step("Done", BLOCKS + 2, BLOCKS + 2);
        return rep;
    }

    // ================================================================== regimes, stress days

    static List<String> regimes(History h, IntelEngine.Forecast fc, Config cfg) {
        List<String> out = new ArrayList<>();
        Regime r = fc.regime;
        double[] f = fc.features;
        out.add(r.trend.equals("TREND_BULL") ? "Trend up" : r.trend.equals("TREND_BEAR") ? "Trend down" : "Sideways");
        if (r.vol.equals("HIGH_VOL")) out.add("High volatility");
        if (r.vol.equals("LOW_VOL")) out.add("Low volatility");
        boolean gapUp = f[3] > 0.5, gapDn = f[3] < -0.5, expiry = h.expiries.contains(fc.date), event = cfg.eventDates.contains(fc.date);
        if (gapUp) out.add("Gap up");
        if (gapDn) out.add("Gap down");
        if (expiry) out.add("Expiry day");
        if (event) out.add("Major event day");
        if (!gapUp && !gapDn && !expiry && !event && !r.vol.equals("HIGH_VOL")) out.add("Normal day");
        return out;
    }

    /** Hard days, judged on the whole session (for reporting only — never a model input). */
    static List<String> stress(History h, int d, double[] f, Config cfg) {
        List<String> out = new ArrayList<>();
        History.Day day = h.days.get(d);
        double sig = com.krish.niftydirection.forecast.Features.sigma(h, day.date);
        if (!Double.isNaN(f[3]) && Math.abs(f[3]) > 1.0) out.add("Large gap (>1 daily σ)");
        double o = day.open(), mid = day.closeAt(37), cl = day.lastClose();
        if (o > 0 && mid > 0 && cl > 0) {
            double a = Math.log(mid / o), b = Math.log(cl / mid);
            if (Math.signum(a) != Math.signum(b) && Math.abs(a) > 0.5 * sig && Math.abs(b) > 0.5 * sig) out.add("Sharp reversal");
        }
        Map.Entry<String, Double> v0 = h.vixDaily.lowerEntry(day.date);
        Double v1 = h.vixDaily.get(day.date);
        if (v0 != null && v1 != null && v0.getValue() > 0 && v1 / v0.getValue() > 1.10) out.add("VIX spike (+10%)");
        if (!Double.isNaN(f[52]) && f[52] <= -2.0) out.add("Global shock");
        if (cfg.eventDates.contains(day.date)) out.add("RBI / Fed / Budget day");
        if (h.expiries.contains(day.date)) out.add("Expiry session");
        return out;
    }

    // ================================================================== scoring

    static HReport score(History h, int[] sidx, Horizon hz, List<Rec> rs, Config cfg, Report rep) {
        HReport r = new HReport();
        r.id = hz.id; r.label = hz.label;
        r.n = rs.size();
        if (rs.isEmpty()) { r.verdict = "FAIL"; r.reasons.add("no forecasts in the replay"); return r; }
        int ups = 0;
        java.util.Set<String> days = new java.util.HashSet<>();
        for (Rec x : rs) { ups += x.y; days.add(x.date); }
        r.sessions = days.size();
        double upShare = ups / (double) rs.size(), baseP = upShare;
        double hit = 0, br = 0, bb = 0;
        int[] cls = new int[3]; int right3 = 0, n3 = 0;
        Map<String, double[]> perDay = new LinkedHashMap<>();
        double[][] bk = new double[5][3];
        for (Rec x : rs) {
            if ((x.p >= 0.5 ? 1 : 0) == x.y) hit++;
            double e = (x.p - x.y) * (x.p - x.y), eb = (baseP - x.y) * (baseP - x.y);
            br += e; bb += eb;
            double[] pd = perDay.computeIfAbsent(x.date, k -> new double[3]);
            pd[0] += e; pd[1] += eb; pd[2]++;
            cls[x.act3]++;
            if (x.pred3 >= 0) { n3++; if (x.pred3 == x.act3) right3++; }
            int b = Math.min(4, (int) ((x.side - 0.5) * 10));
            bk[b][0]++; bk[b][1] += x.side; if ((x.dir > 0) == (x.y == 1)) bk[b][2]++;
        }
        r.hit = hit / rs.size(); r.base = Math.max(upShare, 1 - upShare);
        r.brier = br / rs.size(); r.baseBrier = bb / rs.size(); r.skill = bb > 0 ? 1 - br / bb : Double.NaN;
        double[] band = Trainer.bootstrap(new ArrayList<>(perDay.values()), hz.swing() ? Math.max(1, hz.sessions) : 1);
        r.skillLo = band[0]; r.skillHi = band[1];
        if (n3 > 0) { r.acc3 = right3 / (double) n3; r.base3 = Math.max(cls[0], Math.max(cls[1], cls[2])) / (double) rs.size(); }
        double ew = 0, en = 0;
        for (int b = 0; b < 5; b++) {
            if (bk[b][0] == 0) continue;
            double mp = bk[b][1] / bk[b][0], act = bk[b][2] / bk[b][0];
            r.buckets.add(new double[]{0.5 + b * 0.1, bk[b][0], mp, act});
            if (bk[b][0] >= MIN_BUCKET) { ew += bk[b][0] * Math.abs(mp - act); en += bk[b][0]; }
        }
        r.calErr = en > 0 ? ew / en : Double.NaN;

        // ---- trades: act signals only, one position at a time
        List<Trade> trades = new ArrayList<>();
        Map<Rec, Trade> byRec = new HashMap<>();
        String busyDate = ""; int busySlot = -1;
        for (Rec x : rs) {
            if (!x.tradeable) continue;
            int cmp = x.date.compareTo(busyDate);
            if (cmp < 0 || (cmp == 0 && x.k <= busySlot)) continue;
            double stopDist = cfg.stopMult > 0 && !Double.isNaN(x.range68) ? cfg.stopMult * x.range68 * h.days.get(x.d).c[Math.min(x.k, History.BARS - 1)] : 0;
            Trade t = simulate(h, sidx, x.d, x.k, hz, x.dir, stopDist, cfg);
            if (t == null) continue;
            trades.add(t); byRec.put(x, t); x.trade = t;
            busyDate = t.exitDate; busySlot = t.exitSlot;
        }
        double eq = 0, peak = 0, gw = 0, gl = 0; int streak = 0;
        for (Trade t : trades) {
            r.trades++; r.netPts += t.pts; r.netRupees += t.rupees; r.chargesRupees += t.charges;
            if (t.stopped) r.stops++;
            if (t.rupees > 0) { r.wins++; gw += t.rupees; streak = 0; } else { gl -= t.rupees; streak++; r.worstStreak = Math.max(r.worstStreak, streak); }
            eq += t.rupees; peak = Math.max(peak, eq); r.maxDD = Math.max(r.maxDD, peak - eq);
        }
        if (r.trades > 0) { r.avgRupees = r.netRupees / r.trades; r.profitFactor = gl > 0 ? gw / gl : gw > 0 ? 99 : Double.NaN; }

        // ---- regime and stress tables
        Map<String, double[]> rg = new TreeMap<>(), stt = new TreeMap<>();
        for (Rec x : rs) {
            boolean right = (x.p >= 0.5 ? 1 : 0) == x.y;
            Trade t = byRec.get(x);
            for (String g : x.regimes) add(rg, g, right, x.y, t);
            for (String g : x.stress) add(stt, g, right, x.y, t);
        }
        finishTable(rg, r.regimes);
        finishTable(stt, r.stress);
        return r;
    }

    static void add(Map<String, double[]> m, String key, boolean right, int y, Trade t) {
        double[] v = m.computeIfAbsent(key, k -> new double[5]);   // n, right, ups, trades, ₹
        v[0]++; if (right) v[1]++; v[2] += y;
        if (t != null) { v[3]++; v[4] += t.rupees; }
    }

    static void finishTable(Map<String, double[]> in, Map<String, double[]> out) {
        for (Map.Entry<String, double[]> e : in.entrySet()) {
            double[] v = e.getValue();
            double up = v[2] / v[0];
            out.put(e.getKey(), new double[]{v[0], v[1] / v[0], Math.max(up, 1 - up), v[3], v[4]});
        }
    }

    // ================================================================== leakage audit

    /** Features recomputed on a history cut at the forecast moment must equal the replay's. */
    static void audit(History h, String[] win, Report rep) {
        int[] sidx = h.sessionIndex();
        Map<String, Integer> idx = new HashMap<>();
        for (int d = 0; d < h.days.size(); d++) idx.put(h.days.get(d).date, d);
        Random rnd = new Random(7);
        for (int t = 0; t < AUDIT; t++) {
            Integer d = idx.get(win[rnd.nextInt(win.length)]);
            if (d == null) continue;
            int k = 1 + rnd.nextInt(History.BARS - 1);
            double[] full = FeatureEngine.compute(h, d, k, sidx);
            History cut = truncated(h, d, k);
            double[] part = FeatureEngine.compute(cut, d, k, cut.sessionIndex());
            rep.auditChecked++;
            for (int j = 0; j < full.length; j++) {
                if (Double.isNaN(full[j]) && Double.isNaN(part[j])) continue;
                if (full[j] != part[j]) {
                    rep.auditFailed++;
                    rep.audit.add("Look-ahead in '" + FeatureEngine.NAMES[j] + "' on " + h.days.get(d).date + " after bar " + k);
                    break;
                }
            }
        }
    }

    /** What was known after k bars of session d: later sessions removed, later bars blank, daily data before d only. */
    static History truncated(History h, int d, int k) {
        History c = new History();
        for (int i = 0; i < d; i++) c.days.add(h.days.get(i));
        History.Day src = h.days.get(d), day = new History.Day(src.date);
        for (int i = 0; i < k; i++) {
            day.o[i] = src.o[i]; day.h[i] = src.h[i]; day.l[i] = src.l[i]; day.c[i] = src.c[i];
            for (Map.Entry<String, float[]> e : src.aux.entrySet()) day.aux(e.getKey())[i] = e.getValue()[i];
        }
        day.bars = k;
        c.days.add(day);
        c.niftyDaily.putAll(h.niftyDaily.headMap(src.date, false));
        c.vixDaily.putAll(h.vixDaily.headMap(src.date, false));
        for (Map.Entry<String, TreeMap<String, Double>> e : h.global.entrySet()) c.global.put(e.getKey(), new TreeMap<>(e.getValue().headMap(src.date, false)));
        c.expiries.addAll(h.expiries);
        c.sectors.addAll(h.sectors);
        return c;
    }

    // ================================================================== verdicts

    /**
     * PASS: real edge on the replay (Brier-skill bootstrap band above 0 and hit rate above the usual side), confidence honest
     *       (calibration error ≤ 8 points), and the simulated trades make money after costs (≥ 20 trades, profit factor ≥ 1.1).
     * WARN: an edge that is not yet proven, confidence off by 8–15 points, too few trades, or an edge that loses after costs.
     * FAIL: no edge, confidence off by more than 15 points, or any leakage.
     */
    static void verdicts(Report rep) {
        boolean leak = rep.auditFailed > 0 || !rep.purgeOk;
        for (HReport r : rep.horizons) {
            if (r.n == 0) continue;
            List<String> why = r.reasons;
            String v;
            if (leak) { v = "FAIL"; why.add("leakage found in the audit"); }
            else if (r.sessions < 60 || r.n < 300) { v = "WARN"; why.add("too little replay data (" + r.sessions + " sessions)"); }
            else if (!(r.skill > 0) || r.hit <= r.base) { v = "FAIL"; why.add(String.format(Locale.US, "no edge: right %.1f%% vs %.1f%% always guessing the usual side", r.hit * 100, r.base * 100)); }
            else if (r.calErr > 0.15) { v = "FAIL"; why.add(String.format(Locale.US, "confidence is misleading: off by %.0f points on average", r.calErr * 100)); }
            else {
                boolean proven = r.skillLo > 0;
                boolean honest = Double.isNaN(r.calErr) || r.calErr <= 0.08;
                boolean money = r.trades >= 20 && r.netRupees > 0 && r.profitFactor >= 1.1;
                if (!proven) why.add("edge not proven (skill band includes zero)");
                if (!honest) why.add(String.format(Locale.US, "confidence off by %.0f points", r.calErr * 100));
                if (r.trades < 20) why.add("only " + r.trades + " simulated trades — too few to judge P&L");
                else if (!money) why.add(String.format(Locale.US, "simulated trades %s after costs (₹%,.0f per lot, PF %.2f)", r.netRupees > 0 ? "barely profitable" : "lose money", r.netRupees, r.profitFactor));
                v = proven && honest && money ? "PASS" : "WARN";
            }
            boolean intraday = !Horizon.of(r.id).swing();
            if (intraday && r.n >= 300 && r.hit > 0.75 && !"FAIL".equals(v)) { v = "WARN"; why.add("suspiciously good for intraday — check the data"); }
            if ("PASS".equals(v)) why.add(String.format(Locale.US, "right %.1f%% vs %.1f%%, %d trades, ₹%,.0f per lot after costs", r.hit * 100, r.base * 100, r.trades, r.netRupees));
            r.verdict = v;
        }
        int pass = 0, warn = 0;
        for (HReport r : rep.horizons) { if ("PASS".equals(r.verdict)) { pass++; rep.ready.add(r.label); } else if ("WARN".equals(r.verdict)) warn++; }
        if (leak) { rep.verdict = "FAIL"; rep.summary = "Leakage found — do not trade on these forecasts until it is fixed."; }
        else if (pass > 0) { rep.verdict = "PASS"; rep.summary = "Ready for a controlled small-money test on: " + String.join(", ", rep.ready) + ". Other horizons stay paper-only."; }
        else if (warn > 0) { rep.verdict = "WARN"; rep.summary = "No horizon passed every check. Use paper trading only and let the live record grow."; }
        else { rep.verdict = "FAIL"; rep.summary = "No horizon showed an edge in the replay. Do not trade on these forecasts."; }
    }

    // ================================================================== row-level export

    static String hhmm(int k) { int m = History.minuteAfter(k); return String.format(Locale.US, "%d:%02d", m / 60, m % 60); }
    static String slotEnd(int slot) { int m = History.OPEN_MIN + 5 * (slot + 1); return String.format(Locale.US, "%d:%02d", m / 60, m % 60); }
    static String f(double v, int dp) { return Double.isNaN(v) || Double.isInfinite(v) ? "" : String.format(Locale.US, "%." + dp + "f", v); }
    static String q(String s) { return s == null ? "" : s.indexOf(',') >= 0 || s.indexOf('"') >= 0 ? "\"" + s.replace("\"", "\"\"") + "\"" : s; }

    static java.io.Writer gz(java.io.File f) throws java.io.IOException {
        return new java.io.BufferedWriter(new java.io.OutputStreamWriter(new java.util.zip.GZIPOutputStream(new java.io.FileOutputStream(f)), "UTF-8"));
    }

    static void saveDetail(Report rep, java.io.File dir) throws java.io.IOException {
        dir.mkdirs();
        Detail dt = rep.detail;
        try (java.io.Writer w = gz(new java.io.File(dir, "replay_forecasts.csv.gz"))) {
            w.write("date,time,block,horizon,price,p_up,p_flat_up,p_flat,p_flat_down,direction,side_prob,confidence,signal_quality,act_signal,first_gate_reason,"
                    + "expected_range68_pct,expected_return_pct,regime,conditions,stress_day,target_date,target_price,actual_move_pct,went_up,flat,direction_right,trade_net_rupees\n");
            for (Rec r : dt.recs) {
                boolean right = (r.p >= 0.5 ? 1 : 0) == r.y;
                w.write(r.date + "," + hhmm(r.k) + "," + r.block + "," + r.hz + "," + f(r.price, 2) + "," + f(r.p, 4) + "," + f(r.pUp3, 4) + "," + f(r.pFlat, 4) + ","
                        + f(r.pDown3, 4) + "," + r.direction + "," + f(r.side, 4) + "," + r.conf + "," + r.quality + "," + (r.tradeable ? 1 : 0) + "," + q(r.gate) + ","
                        + f(r.range68 * 100, 3) + "," + f(r.expRet * 100, 3) + "," + q(r.regime) + "," + q(String.join("; ", r.regimes)) + ","
                        + q(String.join("; ", r.stress)) + "," + r.tdate + "," + f(r.target, 2) + "," + f(r.move * 100, 3) + "," + r.y + "," + (r.flat ? 1 : 0) + ","
                        + (right ? 1 : 0) + "," + (r.trade == null ? "" : f(r.trade.rupees, 0)) + "\n");
            }
        }
        try (java.io.Writer w = gz(new java.io.File(dir, "replay_trades.csv.gz"))) {
            w.write("horizon,entry_date,signal_time,entry_time,side,entry_price,exit_date,exit_time,exit_price,stopped,points,charges_rupees,net_rupees_per_lot,cumulative_rupees,confidence,signal_prob\n");
            Map<String, Double> cum = new HashMap<>();
            for (Rec r : dt.recs) {
                Trade t = r.trade;
                if (t == null) continue;
                double c = cum.merge(r.hz, t.rupees, Double::sum);
                w.write(r.hz + "," + t.date + "," + hhmm(t.k) + "," + slotEnd(t.k) + "," + (t.dir > 0 ? "LONG" : "SHORT") + "," + f(t.entry, 2) + "," + t.exitDate + ","
                        + slotEnd(t.exitSlot) + "," + f(t.exit, 2) + "," + (t.stopped ? 1 : 0) + "," + f(t.pts, 2) + "," + f(t.charges, 2) + "," + f(t.rupees, 2) + ","
                        + f(c, 2) + "," + r.conf + "," + f(r.side, 4) + "\n");
            }
        }
        try (java.io.Writer w = gz(new java.io.File(dir, "replay_features.csv.gz"))) {
            StringBuilder hd = new StringBuilder("date,time,block,regime");
            for (String n : FeatureEngine.NAMES) hd.append(',').append(q(n));
            w.write(hd.append('\n').toString());
            for (Object[] o : dt.features) {
                StringBuilder b = new StringBuilder();
                b.append(o[0]).append(',').append(hhmm((Integer) o[1])).append(',').append(o[2]).append(',').append(q((String) o[4]));
                for (double v : (double[]) o[3]) b.append(',').append(f(v, 5));
                w.write(b.append('\n').toString());
            }
        }
    }

    // ================================================================== storage

    public static JSONObject toJson(Report r) throws Exception {
        JSONObject j = new JSONObject().put("version", r.version).put("at", r.at).put("from", r.from).put("to", r.to).put("verdict", r.verdict)
                .put("summary", r.summary).put("sessions", r.sessions).put("blocks", r.blocks).put("auditChecked", r.auditChecked)
                .put("auditFailed", r.auditFailed).put("purgeOk", r.purgeOk).put("lot", r.lot).put("slippagePts", r.slippagePts)
                .put("stopMult", r.stopMult).put("threshold", r.threshold);
        j.put("audit", strs(r.audit)).put("ready", strs(r.ready));
        JSONArray hs = new JSONArray();
        for (HReport x : r.horizons) {
            JSONObject o = new JSONObject().put("id", x.id).put("label", x.label).put("verdict", x.verdict).put("reasons", strs(x.reasons))
                    .put("n", x.n).put("sessions", x.sessions).put("trades", x.trades).put("wins", x.wins).put("stops", x.stops).put("worstStreak", x.worstStreak);
            String[] k = {"hit", "base", "brier", "baseBrier", "skill", "skillLo", "skillHi", "acc3", "base3", "calErr", "netPts", "netRupees", "chargesRupees", "maxDD", "profitFactor", "avgRupees"};
            double[] v = {x.hit, x.base, x.brier, x.baseBrier, x.skill, x.skillLo, x.skillHi, x.acc3, x.base3, x.calErr, x.netPts, x.netRupees, x.chargesRupees, x.maxDD, x.profitFactor, x.avgRupees};
            for (int i = 0; i < k.length; i++) o.put(k[i], nz(v[i]));
            JSONArray b = new JSONArray();
            for (double[] q : x.buckets) b.put(arr(q));
            o.put("buckets", b).put("regimes", table(x.regimes)).put("stress", table(x.stress));
            hs.put(o);
        }
        return j.put("horizons", hs);
    }

    public static Report fromJson(JSONObject j) throws Exception {
        Report r = new Report();
        r.version = j.optInt("version"); r.at = j.optLong("at"); r.from = j.optString("from"); r.to = j.optString("to"); r.verdict = j.optString("verdict", "FAIL");
        r.summary = j.optString("summary"); r.sessions = j.optInt("sessions"); r.blocks = j.optInt("blocks"); r.auditChecked = j.optInt("auditChecked");
        r.auditFailed = j.optInt("auditFailed"); r.purgeOk = j.optBoolean("purgeOk", true); r.lot = j.optInt("lot"); r.slippagePts = j.optDouble("slippagePts", 0);
        r.stopMult = j.optDouble("stopMult", 0); r.threshold = j.optDouble("threshold", 0);
        r.audit.addAll(list(j.optJSONArray("audit"))); r.ready.addAll(list(j.optJSONArray("ready")));
        JSONArray hs = j.optJSONArray("horizons");
        for (int i = 0; hs != null && i < hs.length(); i++) {
            JSONObject o = hs.getJSONObject(i);
            HReport x = new HReport();
            x.id = o.optString("id"); x.label = o.optString("label"); x.verdict = o.optString("verdict"); x.reasons.addAll(list(o.optJSONArray("reasons")));
            x.n = o.optInt("n"); x.sessions = o.optInt("sessions"); x.trades = o.optInt("trades"); x.wins = o.optInt("wins"); x.stops = o.optInt("stops");
            x.worstStreak = o.optInt("worstStreak");
            x.hit = nan(o, "hit"); x.base = nan(o, "base"); x.brier = nan(o, "brier"); x.baseBrier = nan(o, "baseBrier"); x.skill = nan(o, "skill");
            x.skillLo = nan(o, "skillLo"); x.skillHi = nan(o, "skillHi"); x.acc3 = nan(o, "acc3"); x.base3 = nan(o, "base3"); x.calErr = nan(o, "calErr");
            x.netPts = nan(o, "netPts"); x.netRupees = nan(o, "netRupees"); x.chargesRupees = nan(o, "chargesRupees"); x.maxDD = nan(o, "maxDD");
            x.profitFactor = nan(o, "profitFactor"); x.avgRupees = nan(o, "avgRupees");
            JSONArray b = o.optJSONArray("buckets");
            for (int q = 0; b != null && q < b.length(); q++) x.buckets.add(darr(b.getJSONArray(q)));
            untable(o.optJSONObject("regimes"), x.regimes);
            untable(o.optJSONObject("stress"), x.stress);
            r.horizons.add(x);
        }
        return r;
    }

    static JSONObject table(Map<String, double[]> m) throws Exception { JSONObject o = new JSONObject(); for (Map.Entry<String, double[]> e : m.entrySet()) o.put(e.getKey(), arr(e.getValue())); return o; }
    static void untable(JSONObject o, Map<String, double[]> m) throws Exception {
        if (o == null) return;
        java.util.Iterator<String> it = o.keys();
        while (it.hasNext()) { String k = it.next(); m.put(k, darr(o.getJSONArray(k))); }
    }
    static JSONArray strs(List<String> l) { JSONArray a = new JSONArray(); for (String s : l) a.put(s); return a; }
    static List<String> list(JSONArray a) { List<String> l = new ArrayList<>(); for (int i = 0; a != null && i < a.length(); i++) l.add(a.optString(i)); return l; }
    static double nz(double v) { return Double.isNaN(v) || Double.isInfinite(v) ? -999 : v; }
    static double nan(JSONObject j, String k) { double v = j.optDouble(k, -999); return v == -999 ? Double.NaN : v; }
    static JSONArray arr(double[] v) throws Exception { JSONArray a = new JSONArray(); for (double x : v) a.put(Double.isNaN(x) || Double.isInfinite(x) ? 0 : x); return a; }
    static double[] darr(JSONArray a) throws Exception { double[] v = new double[a.length()]; for (int i = 0; i < v.length; i++) v[i] = a.getDouble(i); return v; }
}

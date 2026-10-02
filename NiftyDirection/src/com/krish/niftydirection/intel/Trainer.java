package com.krish.niftydirection.intel;

import com.krish.niftydirection.forecast.Features;
import com.krish.niftydirection.forecast.History;
import com.krish.niftydirection.forecast.LogReg;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;

/**
 * Learns one HorizonModel per horizon, with controlled walk-forward validation:
 *
 *  1. Samples: every 15 minutes of every finished session (and the open), features computed once and shared by all horizons.
 *  2. Group models (one logistic regression per feature group) are re-learnt in 5 expanding blocks over the newest 60% of
 *     sessions; each block is predicted only by models learnt from EARLIER sessions whose targets also ended earlier (purged).
 *     These out-of-sample group probabilities are what the meta model learns from (stacking without leakage).
 *  3. Meta model: blends the group logits, with range-market and high-volatility interactions (regime-specific weights).
 *  4. Calibration (isotonic / Platt) on the meta model's output.
 *  5. Test: the newest TEST_DAYS sessions are scored with a meta model + calibration learnt only from earlier sessions.
 *     "Proven edge" = the Brier skill's 90% block-bootstrap band (resampling whole sessions) stays above zero AND the hit rate
 *     beats always guessing the usual side. A lucky test window no longer earns the label.
 *  6. The models used live are then learnt on everything (versioned, saved with their test results).
 */
public final class Trainer {
    private Trainer() {}

    public static final int STEP = 3, ITERS = 12, FOLDS = 5, TEST_DAYS = 120, MIN_TEST_DAYS = 60, BOOT = 400;
    public static final double LAMBDA = 20, META_LAMBDA = 20, OOS_SHARE = 0.6;
    /** Recency weighting: a session this many sessions old counts half as much (markets change; 2023's bull run is not 2026). */
    public static final double HALF_LIFE = 250;

    public interface Progress { void step(String what, int done, int total); }

    /** All samples, features computed once. */
    public static final class Dataset {
        public final List<double[]> X = new ArrayList<>();
        public final List<Integer> d = new ArrayList<>(), k = new ArrayList<>();
        public final List<String> date = new ArrayList<>();
        public final List<Regime> regime = new ArrayList<>();
        public final List<Double> price = new ArrayList<>(), sigma = new ArrayList<>();
        public int size() { return X.size(); }
    }

    /** Where a forecast made after k bars of session d resolves: sessions ahead and the slot (-1 = the session's close). */
    public static int[] targetSpec(int k, Horizon hz) {
        if (hz.eod()) return k >= History.BARS ? null : new int[]{0, History.BARS - 1};
        if (hz.swing()) return new int[]{k == 0 ? hz.sessions - 1 : hz.sessions, -1};
        int idx = k - 1 + hz.bars;
        return new int[]{idx / History.BARS, idx % History.BARS};
    }

    public static Dataset dataset(History h) {
        int[] sidx = h.sessionIndex();
        Dataset s = new Dataset();
        for (int d = 0; d < h.days.size(); d++) {
            History.Day day = h.days.get(d);
            if (day.bars < History.BARS) continue;
            double sig = Features.sigma(h, day.date);
            for (int k = 0; k <= History.BARS; k += STEP) {
                double p = k == 0 ? day.open() : day.closeAt(k);
                if (Double.isNaN(p) || p <= 0) continue;
                double[] f = FeatureEngine.compute(h, d, k, sidx);
                s.X.add(f); s.d.add(d); s.k.add(k); s.date.add(day.date); s.regime.add(Regime.of(f));
                s.price.add(p); s.sigma.add(sig);
            }
        }
        return s;
    }

    /** Target {price, target date} of sample i for a horizon, or null when it is not in the history. */
    static Object[] target(History h, int[] sidx, List<String> sessions, Map<String, Integer> pos, Dataset s, int i, Horizon hz) {
        int d = s.d.get(i), k = s.k.get(i);
        int[] spec = targetSpec(k, hz);
        if (spec == null) return null;
        if (hz.swing()) {
            Integer p = pos.get(s.date.get(i));
            if (p == null || p + spec[0] >= sessions.size()) return null;
            String td = sessions.get(p + spec[0]);
            return new Object[]{h.niftyDaily.get(td)[3], td};
        }
        int dd = d + spec[0], slot = spec[1];
        if (dd >= h.days.size() || sidx[dd] - sidx[d] != dd - d) return null;
        History.Day t = h.days.get(dd);
        if (t.bars < slot + 1) return null;
        double v = t.closeAt(slot + 1);
        return Double.isNaN(v) ? null : new Object[]{v, t.date};
    }

    public static List<HorizonModel> trainAll(History h, Progress pr) {
        pr.step("Computing features…", 0, Horizon.ALL.length + 1);
        Dataset s = dataset(h);
        List<HorizonModel> out = new ArrayList<>();
        int i = 0;
        for (Horizon hz : Horizon.ALL) {
            pr.step("Learning " + hz.label + " (" + HorizonModel.G + " group models + meta)…", ++i, Horizon.ALL.length + 1);
            out.add(train(h, s, hz));
        }
        return out;
    }

    public static HorizonModel train(History h, Dataset s, Horizon hz) { return train(h, s, hz, null); }

    /**
     * cutoff (yyyy-MM-dd, or null): learn only from samples made before that date whose targets also ended before it —
     * what a model frozen on that morning could have known. Used by the pre-live replay.
     */
    public static HorizonModel train(History h, Dataset s, Horizon hz, String cutoff) {
        int[] sidx = h.sessionIndex();
        List<String> sessions = new ArrayList<>(h.niftyDaily.keySet());
        Map<String, Integer> pos = new HashMap<>();
        for (int i = 0; i < sessions.size(); i++) pos.put(sessions.get(i), i);

        // ---- rows with a known target
        List<Integer> rowsL = new ArrayList<>();
        List<Integer> yL = new ArrayList<>();
        List<String> tdL = new ArrayList<>();
        List<Double> mvL = new ArrayList<>();
        List<double[]> exL = new ArrayList<>();
        for (int i = 0; i < s.size(); i++) {
            if (cutoff != null && s.date.get(i).compareTo(cutoff) >= 0) continue;
            Object[] t = target(h, sidx, sessions, pos, s, i, hz);
            if (t == null) continue;
            if (cutoff != null && ((String) t[1]).compareTo(cutoff) >= 0) continue;
            double tp = (Double) t[0], p = s.price.get(i);
            if (tp == p) continue;
            rowsL.add(i); yL.add(tp > p ? 1 : 0); tdL.add((String) t[1]); mvL.add(Math.log(tp / p));
            exL.add(excursion(h, sidx, sessions, pos, s, i, hz));
        }
        int n = rowsL.size();
        HorizonModel m = new HorizonModel();
        m.id = hz.id;
        m.at = System.currentTimeMillis();
        HorizonModel.Info in = m.info;
        in.samples = n;
        if (n < 300) return m;
        int[] y = new int[n], src = new int[n];
        String[] day = new String[n], tday = new String[n];
        Regime[] reg = new Regime[n];
        int ups = 0;
        for (int r = 0; r < n; r++) {
            src[r] = rowsL.get(r); y[r] = yL.get(r); tday[r] = tdL.get(r); day[r] = s.date.get(src[r]); reg[r] = s.regime.get(src[r]);
            ups += y[r];
        }
        in.upShare = ups / (double) n;
        TreeSet<String> days = new TreeSet<>(Arrays.asList(day));
        in.days = days.size(); in.from = days.first(); in.to = days.last();
        String[] D = days.toArray(new String[0]);

        // ---- sample weights: recency × 1 / (rows that share one outcome or one piece of information)
        Map<String, Integer> dIdx = new HashMap<>();
        for (int i = 0; i < D.length; i++) dIdx.put(D[i], i);
        double overlap = targetOverlap(hz, n / (double) D.length);
        double[] rec = new double[n], wMeta = new double[n];
        double ws = 0, wu = 0;
        for (int r = 0; r < n; r++) {
            rec[r] = Math.pow(0.5, (D.length - 1 - dIdx.get(day[r])) / HALF_LIFE);
            wMeta[r] = rec[r] / overlap;
            ws += rec[r]; wu += rec[r] * y[r];
        }
        double recentBase = ws > 0 ? wu / ws : in.upShare;

        // ---- expected-range tables: |move| / horizon volatility, by volatility bucket
        List<List<Double>> zb = new ArrayList<>();
        for (int b = 0; b < 3; b++) zb.add(new ArrayList<>());
        for (int r = 0; r < n; r++) {
            double sh = s.sigma.get(src[r]) * Math.sqrt(hz.minutesAt(s.k.get(src[r])) / 375.0);
            if (sh > 0) zb.get(reg[r].volBucket()).add(Math.abs(mvL.get(r)) / sh);
        }
        List<Double> all = new ArrayList<>();
        for (List<Double> l : zb) all.addAll(l);
        for (int b = 0; b < 3; b++) in.range[b] = quantiles(zb.get(b).size() >= 200 ? zb.get(b) : all);
        // three-way outlook and excursions, per volatility bucket (all buckets pooled when one is thin)
        double[][] acc = new double[4][3];   // bucket 0..2, 3 = all: {count, flat count, sum of |z|}
        List<List<Double>> eu = new ArrayList<>(), ed = new ArrayList<>();
        for (int b = 0; b < 4; b++) { eu.add(new ArrayList<>()); ed.add(new ArrayList<>()); }
        for (int r = 0; r < n; r++) {
            double sh = s.sigma.get(src[r]) * Math.sqrt(hz.minutesAt(s.k.get(src[r])) / 375.0);
            if (sh <= 0) continue;
            double z = Math.abs(mvL.get(r)) / sh;
            for (int b : new int[]{reg[r].volBucket(), 3}) {
                acc[b][0]++; if (z < HorizonModel.FLAT_Z) acc[b][1]++; acc[b][2] += z;
                double[] ex = exL.get(r);
                if (ex != null) { eu.get(b).add(ex[0] / sh); ed.get(b).add(ex[1] / sh); }
            }
        }
        for (int b = 0; b < 3; b++) {
            int u = acc[b][0] >= 200 ? b : 3;
            in.flat[b] = acc[u][1] / acc[u][0];
            in.meanAbs[b] = acc[u][2] / acc[u][0];
            List<Double> a1 = eu.get(eu.get(b).size() >= 200 ? b : 3), a2 = ed.get(ed.get(b).size() >= 200 ? b : 3);
            in.excUp[b] = median(a1); in.excDn[b] = median(a2);
        }

        // ---- group design matrices
        double[][][] Xg = new double[HorizonModel.G][n][];
        for (int r = 0; r < n; r++) {
            double[] f = s.X.get(src[r]);
            for (int g = 0; g < HorizonModel.G; g++) Xg[g][r] = HorizonModel.groupRow(f, g);
        }

        double[][] wG = new double[HorizonModel.G][n];
        for (int g = 0; g < HorizonModel.G; g++) {
            double div = Math.max(overlap, dupFactor(Xg[g], day, n));
            for (int r = 0; r < n; r++) wG[g][r] = rec[r] / div;
        }

        // ---- 2. out-of-sample group probabilities (purged, expanding blocks over the newest 60%)
        double[][] oos = new double[n][];
        int startIdx = (int) Math.floor(D.length * (1 - OOS_SHARE));
        int per = Math.max(1, (D.length - startIdx + FOLDS - 1) / FOLDS);
        for (int b = startIdx; b < D.length; b += per) {
            String from = D[b], to = b + per < D.length ? D[b + per] : "9999";
            int[] tr = new int[n], te = new int[n];
            int nTr = 0, nTe = 0;
            for (int r = 0; r < n; r++) {
                if (day[r].compareTo(from) >= 0 && day[r].compareTo(to) < 0) te[nTe++] = r;
                else if (day[r].compareTo(from) < 0 && tday[r].compareTo(from) < 0) tr[nTr++] = r;
            }
            if (nTr < 500 || nTe == 0) continue;
            LogReg[] gm = fitGroups(Xg, y, tr, nTr, wG);
            for (int t = 0; t < nTe; t++) oos[te[t]] = groupProbs(gm, Xg, te[t]);
        }
        List<Integer> oosRows = new ArrayList<>();
        for (int r = 0; r < n; r++) if (oos[r] != null) oosRows.add(r);

        // ---- 5. walk-forward test of meta + calibration on the newest sessions
        int nTest = Math.min(TEST_DAYS, D.length / 5);
        if (nTest >= MIN_TEST_DAYS && oosRows.size() >= 1000) {
            String testFrom = D[D.length - nTest];
            List<Integer> trL = new ArrayList<>(), teL = new ArrayList<>();
            for (int r : oosRows) {
                if (day[r].compareTo(testFrom) >= 0) teL.add(r);
                else if (tday[r].compareTo(testFrom) < 0) trL.add(r);
            }
            if (trL.size() >= 500 && teL.size() >= 100) {
                boolean[] use = gate(oos, y, trL, wMeta);
                LogReg meta = fitMeta(oos, reg, y, trL, use, wMeta);
                Calibrator cal = calib(meta, oos, reg, y, trL, use, wMeta);
                double alpha = chooseShrink(oos, reg, y, day, tday, trL, use, wMeta);
                int baseUps = 0;
                for (int r : trL) baseUps += y[r];
                double base = baseUps / (double) trL.size();
                evaluate(in, meta, cal, base, oos, reg, y, day, teL, hz, use, alpha, wmean(y, trL, rec));
                in.testFrom = testFrom;
                in.testDays = nTest;
            }
        }

        // ---- 6. production models: groups on everything, meta + calibration on all out-of-sample rows
        int[] allRows = new int[n];
        for (int r = 0; r < n; r++) allRows[r] = r;
        LogReg[] gm = fitGroups(Xg, y, allRows, n, wG);
        System.arraycopy(gm, 0, m.groups, 0, gm.length);
        List<Integer> metaRows = oosRows;
        boolean stacked = metaRows.size() >= 500;
        if (!stacked) {   // too little history for stacking: meta learns from in-sample group probabilities
            metaRows = new ArrayList<>();
            for (int r = 0; r < n; r++) { oos[r] = groupProbs(gm, Xg, r); metaRows.add(r); }
        }
        m.use = stacked ? gate(oos, y, metaRows, wMeta) : HorizonModel.filled(HorizonModel.G);
        m.meta = fitMeta(oos, reg, y, metaRows, m.use, wMeta);
        m.calib = calib(m.meta, oos, reg, y, metaRows, m.use, wMeta);
        m.shrink = stacked ? chooseShrink(oos, reg, y, day, tday, metaRows, m.use, wMeta) : 0.5;
        m.base = recentBase;
        in.shrink = m.shrink;
        StringBuilder used = new StringBuilder();
        for (int g = 0; g < HorizonModel.G; g++) if (m.use[g] && m.groups[g] != null) used.append(used.length() > 0 ? ", " : "").append(FeatureEngine.GROUP_NAMES[g]);
        in.used = used.length() == 0 ? "none (no group beat the base rate on unseen sessions)" : used.toString();
        importance(m);
        return m;
    }

    static LogReg[] fitGroups(double[][][] Xg, int[] y, int[] rows, int nRows, double[][] wG) {
        LogReg[] out = new LogReg[HorizonModel.G];
        for (int g = 0; g < HorizonModel.G; g++) {
            LogReg lr = LogReg.fit(Xg[g], y, rows, nRows, LAMBDA, ITERS, wG == null ? null : wG[g]);
            boolean any = false;
            for (double sd : lr.sd) if (sd > 0) any = true;
            out[g] = any ? lr : null;
        }
        return out;
    }

    static double[] groupProbs(LogReg[] gm, double[][][] Xg, int r) {
        double[] p = new double[HorizonModel.G];
        for (int g = 0; g < p.length; g++) p[g] = gm[g] == null ? Double.NaN : gm[g].predict(Xg[g][r]);
        return p;
    }

    static LogReg fitMeta(double[][] oos, Regime[] reg, int[] y, List<Integer> rows, boolean[] use, double[] w) {
        double[][] M = new double[y.length][];
        int[] idx = new int[rows.size()];
        for (int i = 0; i < idx.length; i++) { int r = rows.get(i); idx[i] = r; M[r] = HorizonModel.metaRow(oos[r], reg[r], use); }
        for (int r = 0; r < M.length; r++) if (M[r] == null) M[r] = new double[3 * HorizonModel.G];
        return LogReg.fit(M, y, idx, idx.length, META_LAMBDA, ITERS, w);
    }

    static Calibrator calib(LogReg meta, double[][] oos, Regime[] reg, int[] y, List<Integer> rows, boolean[] use, double[] w) {
        double[] p = new double[rows.size()];
        int[] yy = new int[rows.size()];
        double eff = 0;
        for (int i = 0; i < p.length; i++) { int r = rows.get(i); p[i] = meta.predict(HorizonModel.metaRow(oos[r], reg[r], use)); yy[i] = y[r]; eff += w == null ? 1 : w[r]; }
        return Calibrator.fit(p, yy, p.length, eff);
    }

    /** Rows that share one outcome: overlapping intraday targets, the whole day for Day close, days × sessions for swing horizons. */
    static double targetOverlap(Horizon hz, double rowsPerDay) {
        if (hz.swing()) return Math.max(1, rowsPerDay * hz.sessions);
        if (hz.eod()) return Math.max(1, rowsPerDay / 2);
        return Math.max(1, hz.bars / (double) STEP);
    }

    /** Average number of rows per distinct input row within a day: about 26 for inputs that change once a day, ~1 for intraday ones. */
    static double dupFactor(double[][] X, String[] day, int n) {
        int distinct = 0;
        java.util.HashSet<Integer> seen = new java.util.HashSet<>();
        String cur = null;
        for (int r = 0; r < n; r++) {
            if (!day[r].equals(cur)) { cur = day[r]; seen.clear(); }
            if (seen.add(Arrays.hashCode(X[r]))) distinct++;
        }
        return distinct > 0 ? n / (double) distinct : 1;
    }

    static double wmean(int[] y, List<Integer> rows, double[] w) {
        double a = 0, b = 0;
        for (int r : rows) { a += w[r] * y[r]; b += w[r]; }
        return b > 0 ? a / b : 0.5;
    }

    /** Group gating: a group feeds the meta model only if its out-of-sample predictions beat the base rate (weighted Brier). */
    static boolean[] gate(double[][] oos, int[] y, List<Integer> rows, double[] w) {
        boolean[] use = new boolean[HorizonModel.G];
        double base = wmean(y, rows, w);
        for (int g = 0; g < HorizonModel.G; g++) {
            double num = 0, den = 0;
            for (int r : rows) {
                double p = oos[r][g];
                if (Double.isNaN(p)) continue;
                num += w[r] * (p - y[r]) * (p - y[r]); den += w[r] * (base - y[r]) * (base - y[r]);
            }
            use[g] = den > 0 && 1 - num / den > 0;
        }
        return use;
    }

    /**
     * How far to trust the calibrated probability: meta + calibration are learnt on the earlier 70% of the out-of-sample
     * sessions, then the shrink toward the base rate that scores best on the later 30% is kept (0 = always the base rate).
     */
    static double chooseShrink(double[][] oos, Regime[] reg, int[] y, String[] day, String[] tday, List<Integer> rows, boolean[] use, double[] w) {
        TreeSet<String> ds = new TreeSet<>();
        for (int r : rows) ds.add(day[r]);
        if (ds.size() < 40) return 0.5;
        String split = ds.toArray(new String[0])[(int) (ds.size() * 0.7)];
        List<Integer> early = new ArrayList<>(), late = new ArrayList<>();
        for (int r : rows) { if (day[r].compareTo(split) >= 0) late.add(r); else if (tday[r].compareTo(split) < 0) early.add(r); }
        if (early.size() < 300 || late.size() < 100) return 0.5;
        LogReg me = fitMeta(oos, reg, y, early, use, w);
        Calibrator ce = calib(me, oos, reg, y, early, use, w);
        double b = wmean(y, early, w);
        double[] p = new double[late.size()];
        for (int i = 0; i < p.length; i++) { int r = late.get(i); p[i] = ce.apply(me.predict(HorizonModel.metaRow(oos[r], reg[r], use))); }
        double best = 0, bestErr = Double.MAX_VALUE;
        for (int a = 0; a <= 20; a++) {
            double al = a / 20.0, err = 0;
            for (int i = 0; i < p.length; i++) { int r = late.get(i); double q = b + al * (p[i] - b); err += w[r] * (q - y[r]) * (q - y[r]); }
            if (err < bestErr - 1e-12) { bestErr = err; best = al; }
        }
        return best;
    }

    static void evaluate(HorizonModel.Info in, LogReg meta, Calibrator cal, double base, double[][] oos, Regime[] reg, int[] y, String[] day,
                         List<Integer> te, Horizon hz, boolean[] use, double alpha, double sbase) {
        int n = te.size();
        double hit = 0, bhit = 0, br = 0, bb = 0, ll = 0, bll = 0;
        Map<String, double[]> perDay = new LinkedHashMap<>();   // day → {model brier sum, base brier sum, count}
        double[][] bins = new double[10][3];
        Map<String, double[]> byReg = new LinkedHashMap<>();
        double bc = Math.max(1e-4, Math.min(1 - 1e-4, base));
        for (int r : te) {
            double p = cal.apply(meta.predict(HorizonModel.metaRow(oos[r], reg[r], use)));
            p = sbase + alpha * (p - sbase);
            int yy = y[r];
            boolean right = (p >= 0.5 ? 1 : 0) == yy, bright = (base >= 0.5 ? 1 : 0) == yy;
            if (right) hit++;
            if (bright) bhit++;
            double e = (p - yy) * (p - yy), eb = (base - yy) * (base - yy);
            br += e; bb += eb;
            ll -= yy == 1 ? Math.log(p) : Math.log(1 - p);
            bll -= yy == 1 ? Math.log(bc) : Math.log(1 - bc);
            double[] dd = perDay.computeIfAbsent(day[r], k -> new double[3]);
            dd[0] += e; dd[1] += eb; dd[2]++;
            double[] bin = bins[Math.min(9, (int) (p * 10))];
            bin[0]++; bin[1] += p; bin[2] += yy;
            double[] rg = byReg.computeIfAbsent(reg[r].label(), k -> new double[3]);
            rg[0]++; if (right) rg[1]++; if (bright) rg[2]++;
        }
        in.testSamples = n;
        in.hit = hit / n; in.baseHit = bhit / n; in.brier = br / n; in.baseBrier = bb / n; in.logLoss = ll / n; in.baseLogLoss = bll / n;
        in.skill = bb > 0 ? 1 - br / bb : Double.NaN;
        double[] band = bootstrap(new ArrayList<>(perDay.values()), hz.swing() ? Math.max(1, hz.sessions) : 1);
        in.skillLo = band[0]; in.skillHi = band[1];
        in.proven = !Double.isNaN(in.skillLo) && in.skillLo > 0 && in.hit > in.baseHit;
        List<double[]> rel = new ArrayList<>();
        for (double[] b : bins) if (b[0] > 0) rel.add(new double[]{b[0], b[1] / b[0], b[2] / b[0]});
        in.reliability = rel.toArray(new double[0][]);
        for (Map.Entry<String, double[]> e : byReg.entrySet()) {
            double[] v = e.getValue();
            in.byRegime.put(e.getKey(), new double[]{v[0], v[1] / v[0], v[2] / v[0]});
        }
    }

    /**
     * Brier skill 5% and 95% points by a circular block bootstrap over test SESSIONS (block = horizon length in sessions),
     * so the many overlapping samples inside a day, or a week, count as the few independent outcomes they really are.
     */
    static double[] bootstrap(List<double[]> perDay, int block) {
        int nd = perDay.size();
        if (nd < 20) return new double[]{Double.NaN, Double.NaN};
        Random rnd = new Random(42);
        double[] sk = new double[BOOT];
        for (int b = 0; b < BOOT; b++) {
            double sm = 0, sb = 0;
            int got = 0;
            while (got < nd) {
                int st = rnd.nextInt(nd);
                for (int j = 0; j < block && got < nd; j++, got++) { double[] v = perDay.get((st + j) % nd); sm += v[0]; sb += v[1]; }
            }
            sk[b] = sb > 0 ? 1 - sm / sb : 0;
        }
        Arrays.sort(sk);
        return new double[]{sk[(int) (0.05 * BOOT)], sk[(int) (0.95 * BOOT) - 1]};
    }

    /** {log(highest high / price), log(price / lowest low)} between the forecast and its target, or null. */
    static double[] excursion(History h, int[] sidx, List<String> sessions, Map<String, Integer> pos, Dataset s, int i, Horizon hz) {
        int d = s.d.get(i), k = s.k.get(i);
        double p = s.price.get(i), hi = p, lo = p;
        History.Day day = h.days.get(d);
        if (hz.swing()) {
            for (int b = k; b < History.BARS; b++) if (!Float.isNaN(day.c[b])) { hi = Math.max(hi, day.h[b]); lo = Math.min(lo, day.l[b]); }
            Integer ps = pos.get(day.date);
            int[] spec = targetSpec(k, hz);
            if (ps == null || ps + spec[0] >= sessions.size()) return null;
            for (int j = ps + 1; j <= ps + spec[0]; j++) { double[] v = h.niftyDaily.get(sessions.get(j)); hi = Math.max(hi, v[1]); lo = Math.min(lo, v[2]); }
        } else {
            int[] spec = targetSpec(k, hz);
            if (spec == null) return null;
            int dd = d + spec[0];
            if (dd >= h.days.size() || sidx[dd] - sidx[d] != dd - d) return null;
            for (int x = d; x <= dd; x++) {
                History.Day t = h.days.get(x);
                int from = x == d ? k : 0, to = x == dd ? spec[1] : History.BARS - 1;
                for (int b = from; b <= to && b < History.BARS; b++) if (!Float.isNaN(t.c[b])) { hi = Math.max(hi, t.h[b]); lo = Math.min(lo, t.l[b]); }
            }
        }
        return p > 0 && lo > 0 ? new double[]{Math.log(hi / p), Math.log(p / lo)} : null;
    }

    static double median(List<Double> v) {
        if (v.isEmpty()) return Double.NaN;
        double[] a = new double[v.size()];
        for (int i = 0; i < a.length; i++) a[i] = v.get(i);
        Arrays.sort(a);
        return a[a.length / 2];
    }

    static double[] quantiles(List<Double> v) {
        if (v.isEmpty()) return null;
        double[] a = new double[v.size()];
        for (int i = 0; i < a.length; i++) a[i] = v.get(i);
        Arrays.sort(a);
        return new double[]{a[(int) (0.5 * (a.length - 1))], a[(int) (0.68 * (a.length - 1))], a[(int) (0.9 * (a.length - 1))]};
    }

    /** Learnt share of each group in the meta model (standardised weights; interactions count half). */
    static void importance(HorizonModel m) {
        int G = HorizonModel.G;
        double[] imp = new double[G];
        double tot = 0;
        for (int g = 0; g < G; g++) {
            imp[g] = Math.abs(m.meta.w[1 + g]) + 0.5 * (Math.abs(m.meta.w[1 + G + g]) + Math.abs(m.meta.w[1 + 2 * G + g]));
            tot += imp[g];
        }
        for (int g = 0; g < G; g++) m.info.importance[g] = tot > 0 ? imp[g] / tot * 100 : 0;
    }
}

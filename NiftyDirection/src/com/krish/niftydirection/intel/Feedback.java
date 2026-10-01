package com.krish.niftydirection.intel;

import com.krish.niftydirection.forecast.History;
import com.krish.niftydirection.forecast.LogReg;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Historical Learning + Feedback Engine.
 *
 * Every live forecast is stored (time, price, regime, model and final probability, live overlays, where it resolves).
 * Once the target time has passed, the actual outcome is filled in from the price history. From that record:
 *  - live scorecard per horizon and per regime (hit rate, Brier score vs the usual up-share);
 *  - the live overlay weights (how much the option/futures evidence score and the news score move the probability)
 *    start from conservative priors and are re-learnt from resolved forecasts — used only once they beat the priors on
 *    the newest 30% of the record (controlled, versioned; never retrained on every prediction).
 */
public final class Feedback {
    private Feedback() {}

    public static final int MIN_OVERLAY_ROWS = 200;

    /** Prior overlay weights (logit per unit of score) by band: {evidence, news}. */
    public static double[] prior(String band) {
        if (Horizon.SHORT.equals(band)) return new double[]{0.6, 0.3};
        if (Horizon.INTRADAY.equals(band)) return new double[]{0.45, 0.35};
        return new double[]{0.2, 0.4};
    }

    public static final double MAX_SHIFT = 0.4;   // logit; about ±10 probability points

    /** A learnt overlay for one band (null fields = use the prior). */
    public static final class Overlay {
        public LogReg lr;           // inputs: model logit, evidence, news
        public int rows;
        public double logLoss = Double.NaN, priorLogLoss = Double.NaN;
        public String from = "";
    }

    /** Final probability and the logit pushes of the two live overlays. */
    public static double[] applyOverlay(double pModel, double evidence, double news, String band, Overlay learnt) {
        double lm = logit(pModel);
        if (learnt != null && learnt.lr != null) {
            double[] x = {lm, evidence, news}, z = new double[4];
            learnt.lr.row(x, z);
            double total = learnt.lr.w[0];
            for (int j = 0; j < 3; j++) total += learnt.lr.w[j + 1] * z[j + 1];
            double ev = learnt.lr.w[2] * z[2], nw = learnt.lr.w[3] * z[3];
            double base = total - ev - nw;   // what the record says the model alone is worth
            double shift = clip(ev + nw, MAX_SHIFT);
            double scale = ev + nw == 0 ? 0 : shift / (ev + nw);
            return new double[]{sig(base + shift), ev * scale, nw * scale};
        }
        double[] w = prior(band);
        double ev = w[0] * evidence, nw = w[1] * news;
        double shift = clip(ev + nw, MAX_SHIFT), scale = ev + nw == 0 ? 0 : shift / (ev + nw);
        return new double[]{sig(lm + shift), ev * scale, nw * scale};
    }

    // ================================================================== log entries

    public static JSONObject entry(long t, String date, int k, Horizon hz, double price, double pModel, double pFinal, double ev, double nw, String regime)
            throws Exception {
        int[] spec = Trainer.targetSpec(k, hz);
        return new JSONObject().put("t", t).put("date", date).put("k", k).put("h", hz.id).put("price", price)
                .put("pm", pModel).put("pf", pFinal).put("ev", ev).put("nw", nw).put("regime", regime)
                .put("ahead", spec[0]).put("slot", spec[1]).put("done", false);
    }

    /** Fills outcomes of entries whose target is now in the history (past sessions + today's bars). Returns how many were resolved. */
    public static int resolve(List<JSONObject> log, History h) throws Exception {
        TreeSet<String> sess = new TreeSet<>(h.niftyDaily.keySet());
        Map<String, History.Day> byDate = new LinkedHashMap<>();
        for (History.Day d : h.days) { sess.add(d.date); byDate.put(d.date, d); }
        List<String> sl = new ArrayList<>(sess);
        int n = 0;
        for (JSONObject e : log) {
            if (e.optBoolean("done", false)) continue;
            int i = sl.indexOf(e.optString("date"));
            if (i < 0) continue;
            int j = i + e.optInt("ahead");
            if (j >= sl.size()) continue;
            String td = sl.get(j);
            int slot = e.optInt("slot", -1);
            double v = Double.NaN;
            History.Day day = byDate.get(td);
            if (slot < 0) {
                if (day != null && day.bars >= History.BARS) v = day.lastClose();
                else if (h.niftyDaily.containsKey(td)) v = h.niftyDaily.get(td)[3];   // daily history holds finished sessions only
            } else if (day != null && day.bars >= slot + 1) v = day.closeAt(slot + 1);
            if (Double.isNaN(v)) continue;
            double p = e.optDouble("price", 0);
            if (p <= 0 || v == p) { e.put("done", true).put("skip", true); continue; }
            e.put("done", true).put("up", v > p ? 1 : 0).put("move", Math.log(v / p)).put("target", td);
            n++;
        }
        return n;
    }

    /** Live scorecard: per horizon {n, hit, Brier, base Brier, mean final P(up)}; base = share of UP outcomes so far. */
    public static Map<String, double[]> scorecard(List<JSONObject> log, String regime) {
        Map<String, double[]> out = new LinkedHashMap<>();
        for (Horizon hz : Horizon.ALL) {
            int n = 0, ups = 0; double hit = 0, br = 0, sp = 0;
            for (JSONObject e : log) {
                if (!e.optBoolean("done", false) || e.has("skip") || !hz.id.equals(e.optString("h"))) continue;
                if (regime != null && !regime.equals(e.optString("regime"))) continue;
                int y = e.optInt("up"); double p = e.optDouble("pf", 0.5);
                n++; ups += y; sp += p;
                if ((p >= 0.5 ? 1 : 0) == y) hit++;
                br += (p - y) * (p - y);
            }
            if (n == 0) continue;
            double base = ups / (double) n, bb = 0;
            for (JSONObject e : log) {
                if (!e.optBoolean("done", false) || e.has("skip") || !hz.id.equals(e.optString("h"))) continue;
                if (regime != null && !regime.equals(e.optString("regime"))) continue;
                int y = e.optInt("up"); bb += (base - y) * (base - y);
            }
            out.put(hz.id, new double[]{n, hit / n, br / n, bb / n, sp / n});
        }
        return out;
    }

    /**
     * Re-learns the overlay for one band from resolved forecasts that had live inputs. Fitted on the oldest 70%,
     * kept only if it beats the prior on the newest 30% (log loss); otherwise null (= keep the prior).
     */
    public static Overlay learnOverlay(List<JSONObject> log, String band) {
        List<double[]> rows = new ArrayList<>();
        List<Integer> ys = new ArrayList<>();
        for (JSONObject e : log) {
            if (!e.optBoolean("done", false) || e.has("skip")) continue;
            Horizon hz = Horizon.of(e.optString("h"));
            if (hz == null || !band.equals(hz.band)) continue;
            double ev = e.optDouble("ev", 0), nw = e.optDouble("nw", 0);
            if (ev == 0 && nw == 0) continue;
            rows.add(new double[]{logit(e.optDouble("pm", 0.5)), ev, nw});
            ys.add(e.optInt("up"));
        }
        int n = rows.size();
        if (n < MIN_OVERLAY_ROWS) return null;
        int cut = (int) (n * 0.7);
        double[][] X = rows.toArray(new double[0][]);
        int[] y = new int[n], idx = new int[cut];
        for (int i = 0; i < n; i++) y[i] = ys.get(i);
        for (int i = 0; i < cut; i++) idx[i] = i;
        LogReg lr = LogReg.fit(X, y, idx, cut, 2, 15);
        double ll = 0, llp = 0;
        double[] pw = prior(band);
        for (int i = cut; i < n; i++) {
            double p = clampP(lr.predict(X[i]));
            double pp = clampP(sig(X[i][0] + clip(pw[0] * X[i][1] + pw[1] * X[i][2], MAX_SHIFT)));
            ll -= y[i] == 1 ? Math.log(p) : Math.log(1 - p);
            llp -= y[i] == 1 ? Math.log(pp) : Math.log(1 - pp);
        }
        Overlay o = new Overlay();
        o.rows = n; o.logLoss = ll / (n - cut); o.priorLogLoss = llp / (n - cut);
        if (o.logLoss >= o.priorLogLoss) return o;   // tested, not better: lr stays null → prior is used
        int[] all = new int[n];
        for (int i = 0; i < n; i++) all[i] = i;
        o.lr = LogReg.fit(X, y, all, n, 2, 15);
        return o;
    }

    static double clampP(double p) { return Math.max(1e-4, Math.min(1 - 1e-4, p)); }
    static double sig(double v) { return v > 30 ? 1 : v < -30 ? 0 : 1 / (1 + Math.exp(-v)); }
    static double logit(double p) { p = Math.max(0.01, Math.min(0.99, p)); return Math.log(p / (1 - p)); }
    static double clip(double v, double lim) { return Math.max(-lim, Math.min(lim, v)); }
}

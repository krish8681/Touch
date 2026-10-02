package com.krish.niftydirection.intel;

import com.krish.niftydirection.forecast.Features;
import com.krish.niftydirection.forecast.History;
import com.krish.niftydirection.forecast.LogReg;
import com.krish.niftydirection.model.EventItem;
import com.krish.niftydirection.model.NewsItem;
import com.krish.niftydirection.model.Quote;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The live pipeline, in the order of the architecture:
 *   features → regime → horizon models (group models → meta → calibration) → live overlays (option/futures evidence, news events)
 *   → confidence (separate from direction) → expected range → trade gate (forecast ≠ trading signal) → Why? / What changed?
 * Pure Java: the data layer hands in the history, the models and a LiveContext; nothing here touches the network or disk.
 */
public final class IntelEngine {
    private IntelEngine() {}

    public static final double PRE_OPEN_SHRINK = 0.7;   // the open is estimated from GIFT Nifty, not known: forecasts are pulled toward the base rate

    /** Live-only inputs gathered by the collector (all optional). */
    public static final class LiveContext {
        public boolean evidenceAvailable;
        public double evidenceScore, evidenceConf, evidenceCoverage;   // Today-tab score −100..100, confidence 0..100, coverage 0..1
        public String evidenceRegime = "";
        public List<NewsItem> news = new ArrayList<>();
        public List<EventItem> events = new ArrayList<>();
        public String userEvents = "";
        public Map<String, Double> weights = new LinkedHashMap<>();
        public Map<String, String> sectorOf = new LinkedHashMap<>();
        public List<Quote> stocks = new ArrayList<>();
        public List<String> dataIssues = new ArrayList<>();
        /** When each live source was last refreshed (epoch ms), from the collector. */
        public Map<String, Long> sourceTime = new LinkedHashMap<>();
        public boolean marketOpen;
        public double gift = Double.NaN, niftyLast = Double.NaN, niftyPrevClose = Double.NaN, futLast = Double.NaN;
        public String newsReader = "", fiiDate = "";
        public long now = System.currentTimeMillis();
        public String today = "";
        public int minute;
    }

    public static final class Line {
        public final String text;
        public final double pts;   // probability points toward UP (+) or DOWN (−)
        Line(String text, double pts) { this.text = text; this.pts = pts; }
    }

    public static final class Quality {
        public double score = 1;
        public final Map<String, Double> groups = new LinkedHashMap<>();   // group → share of inputs available
        public final Map<String, Double> sources = new LinkedHashMap<>();  // live source → freshness score 0..1
        public final List<String> issues = new ArrayList<>();
        /** Sources or signals that disagree with each other (each one lowers intraday confidence by 10%). */
        public final List<String> conflicts = new ArrayList<>();
    }

    public static final class HPred {
        public Horizon hz;
        public HorizonModel.Info info;
        public double pModel = Double.NaN, pFinal = Double.NaN, evPush, newsPush;
        public double[] groupP = new double[HorizonModel.G], groupPts = new double[HorizonModel.G];
        public int confidence;
        public String confLabel = "Low", direction = "NEUTRAL", why0 = "";
        public double range50 = Double.NaN, range68 = Double.NaN, range90 = Double.NaN;   // fraction of price
        public boolean tradeable;
        /** Three-way outlook and size: P(up), P(flat), P(down), expected return, typical high and low (fractions of price). */
        public double pUp3 = Double.NaN, pFlat = Double.NaN, pDown3 = Double.NaN, expReturn = Double.NaN, expHigh = Double.NaN, expLow = Double.NaN;
        /** HIGH / MEDIUM / LOW: how usable this forecast is as a trading signal, and the main reason. */
        public String signalQuality = "LOW", signalReason = "";
        /** Pre-live validation verdict of this horizon (PASS / WARN / FAIL), "" when not governed. */
        public String validation = "";
        public final List<String> gate = new ArrayList<>();
        public final List<Line> why = new ArrayList<>();
        public final List<String> changed = new ArrayList<>();
        public boolean has() { return !Double.isNaN(pFinal); }
        public boolean proven() { return info != null && info.proven; }
        /** Probability of the side shown (UP for bullish/neutral, DOWN for bearish). */
        public double sideProb() { return "BEARISH".equals(direction) ? 1 - pFinal : pFinal; }
    }

    public static final class Forecast {
        public String date = "", asOf = "", note = "";
        public int k;
        public long at = System.currentTimeMillis();
        public double price = Double.NaN, dayChange = Double.NaN;
        public boolean preOpen, intraday;
        public Regime regime;
        public final List<HPred> preds = new ArrayList<>();
        public int signal = -1;                          // index of the horizon shown as the current signal, -1 = none proven
        public final List<Line> positives = new ArrayList<>(), negatives = new ArrayList<>();
        public EventCalendarRisk.Risk risk;
        public List<EventImpact.Event> events = new ArrayList<>();
        public double[] news = new double[Horizon.ALL.length];
        public Quality quality = new Quality();
        public final List<String> changed = new ArrayList<>();
        public List<Object[]> movers = new ArrayList<>();
        public double weightUp = Double.NaN;
        public double[] features;
        public List<String> memory = new ArrayList<>();
        public List<ImpactGraph.Edge> graph = new ArrayList<>();
        public Map<String, double[]> scorecard = new LinkedHashMap<>();
        /** Cross-market chains as seen today (paths with learnt betas and implied Nifty moves). */
        public List<CrossMarket.Path> chains = new ArrayList<>();
        /** Risk-guard reasons active now (empty = new trades allowed). */
        public List<String> guard = new ArrayList<>();
        /** The pre-live validation report the gate was governed by (null = not validated yet). */
        public Validator.Report validation;
    }

    public static Forecast forecast(List<HorizonModel> models, History h, int d, int k, boolean preOpen, LiveContext ctx, JSONObject prev,
                                    Map<String, Feedback.Overlay> overlays, double tradeThreshold) {
        return forecast(models, h, d, k, preOpen, ctx, prev, overlays, tradeThreshold, null);
    }

    /**
     * governor: the pre-live validation verdict per horizon ({verdict, reason}); null = not governed (used by the replay itself).
     * Only PASS horizons may show "strong enough to act on"; WARN is paper-only; FAIL or not yet validated never acts.
     */
    public static Forecast forecast(List<HorizonModel> models, History h, int d, int k, boolean preOpen, LiveContext ctx, JSONObject prev,
                                    Map<String, Feedback.Overlay> overlays, double tradeThreshold, Map<String, String[]> governor) {
        Forecast fc = new Forecast();
        int[] sidx = h.sessionIndex();
        History.Day day = h.days.get(d);
        double[] f = FeatureEngine.compute(h, d, k, sidx);
        fc.features = f;
        fc.k = k; fc.date = day.date; fc.preOpen = preOpen; fc.intraday = k > 0 && k < History.BARS;
        fc.price = k == 0 ? day.open() : day.closeAt(k);
        Map.Entry<String, double[]> pe = h.niftyDaily.lowerEntry(day.date);
        if (pe != null && !Double.isNaN(fc.price)) fc.dayChange = (fc.price / pe.getValue()[3] - 1) * 100;
        double sigma = Features.sigma(h, day.date);

        // ---- regime (+ live flags)
        Regime reg = Regime.of(f);
        String today = ctx != null && !ctx.today.isEmpty() ? ctx.today : day.date;
        int minute = ctx != null ? ctx.minute : History.minuteAfter(k);
        fc.risk = EventCalendarRisk.assess(ctx != null ? ctx.events : null, today, minute);
        if (ctx != null) {
            fc.events = EventImpact.build(ctx.news, EventCalendarRisk.surprises(ctx.userEvents), ctx.weights, ctx.sectorOf, ctx.now, today, minute);
            fc.news = EventImpact.score(fc.events);
            fc.movers = Constituents.contributions(ctx.stocks, ctx.weights);
            fc.weightUp = Constituents.weightUp(ctx.stocks, ctx.weights);
        }
        if (fc.risk.eventDriven()) reg.flags.add("EVENT_DRIVEN");
        if (EventImpact.dominated(fc.events)) reg.flags.add("NEWS_DOMINATED");
        if (!Double.isNaN(f[2]) && Math.abs(f[2]) > 1.0 && (Double.isNaN(f[52]) || Math.abs(f[52]) < 0.4)) reg.flags.add("INDIA_SPECIFIC");
        fc.regime = reg;

        fc.chains = CrossMarket.paths(h, day.date);
        fc.quality = quality(f, models, ctx);
        double ev = 0;
        if (ctx != null && ctx.evidenceAvailable && ctx.evidenceCoverage >= 0.5)
            ev = Math.max(-1, Math.min(1, ctx.evidenceScore / 100.0)) * Math.max(0, Math.min(1, ctx.evidenceConf / 100.0));

        JSONObject prevPreds = prev != null && day.date.equals(prev.optString("date")) ? prev.optJSONObject("preds") : null;
        double[] prevF = prevPreds != null ? darr(prev.optJSONArray("f")) : null;

        for (int i = 0; i < Horizon.ALL.length; i++) {
            Horizon hz = Horizon.ALL[i];
            HorizonModel m = null;
            for (HorizonModel x : models) if (hz.id.equals(x.id)) m = x;
            HPred p = new HPred();
            p.hz = hz;
            fc.preds.add(p);
            if (m == null || m.meta == null) continue;
            p.info = m.info;
            if (Trainer.targetSpec(k, hz) == null) continue;   // e.g. Day close after the close: model exists, nothing to forecast
            HorizonModel.Output o = m.predict(f, reg);
            System.arraycopy(o.groupP, 0, p.groupP, 0, HorizonModel.G);
            double pm = o.p;
            double shrink = preOpen ? PRE_OPEN_SHRINK : 1;
            if (preOpen) pm = sig(logit(pm) * shrink);
            p.pModel = pm;
            double[] ov = Feedback.applyOverlay(pm, ev, fc.news[i], hz.band, overlays == null ? null : overlays.get(hz.band));
            // live pushes (option evidence, news) are untested priors: on a horizon without a proven edge they count half
            double trust = m.info.proven ? 1 : 0.5;
            p.pFinal = pm + trust * (ov[0] - pm);
            p.evPush = ov[1] * trust; p.newsPush = ov[2] * trust;
            p.direction = p.pFinal >= 0.53 ? "BULLISH" : p.pFinal <= 0.47 ? "BEARISH" : "NEUTRAL";

            // ---- Why? (probability points per group, then the live overlays)
            double slope = p.pFinal * (1 - p.pFinal) * 100;
            for (int g = 0; g < HorizonModel.G; g++) {
                p.groupPts[g] = o.contrib[g] * o.ptsPerLogit * shrink;
                if (Math.abs(p.groupPts[g]) < 0.3) continue;
                StringBuilder t = new StringBuilder(FeatureEngine.GROUP_NAMES[g]);
                double[][] top = m.topInputs(f, g, p.groupPts[g], 2);
                if (top.length > 0) {
                    t.append(" — ");
                    for (int j = 0; j < top.length; j++) {
                        if (j > 0) t.append(", ");
                        t.append(FeatureEngine.NAMES[(int) top[j][0]].toLowerCase(Locale.US)).append(top[j][2] > 0 ? " high" : " low");
                    }
                }
                p.why.add(new Line(t.toString(), p.groupPts[g]));
            }
            if (Math.abs(p.evPush) * slope >= 0.3) p.why.add(new Line("Live option & futures evidence (Today tab: " + (ctx == null ? "" : ctx.evidenceRegime + " ")
                    + String.format(Locale.US, "%+.0f", ctx == null ? 0 : ctx.evidenceScore) + ")", p.evPush * slope));
            if (Math.abs(p.newsPush) * slope >= 0.3) p.why.add(new Line("News & events (" + fc.events.size() + " events, impact-weighted)", p.newsPush * slope));
            p.why.sort((a, b) -> Double.compare(Math.abs(b.pts), Math.abs(a.pts)));
            p.why0 = String.format(Locale.US, "Starting point: Nifty went up %.0f%% of the time over %s in the learning history.", m.info.upShare * 100, hz.label);

            // ---- confidence, separate from direction
            double side = Math.signum(p.pFinal - 0.5), agreeW = 0, totW = 0;
            for (int g = 0; g < HorizonModel.G; g++) { totW += Math.abs(p.groupPts[g]); if (Math.signum(p.groupPts[g]) == side) agreeW += Math.abs(p.groupPts[g]); }
            for (double x : new double[]{p.evPush * slope, p.newsPush * slope}) { totW += Math.abs(x); if (Math.signum(x) == side) agreeW += Math.abs(x); }
            double agreement = totW > 0 ? agreeW / totW : 0.5;
            double strength = Math.min(1, Math.abs(p.pFinal - 0.5) / 0.15);
            double edge = !m.info.tested() ? 0.4 : m.info.proven ? 0.6 + 0.4 * Math.min(1, Math.max(0, m.info.skillLo) / 0.02) : 0.15;
            double conflictMult = hz.swing() ? 1 : Math.max(0.7, 1 - 0.1 * fc.quality.conflicts.size());
            double c = 100 * (0.35 * strength + 0.25 * edge + 0.2 * agreement + 0.2 * fc.quality.score) * fc.risk.mult[i] * (reg.panic ? 0.85 : 1) * conflictMult;
            if (m.info.tested() && !m.info.proven) c = Math.min(c, 30);
            if (!m.info.tested()) c = Math.min(c, 45);
            p.confidence = (int) Math.round(Math.max(0, Math.min(100, c)));
            p.confLabel = p.confidence >= 65 ? "High" : p.confidence >= 40 ? "Medium" : "Low";

            // ---- expected range
            double[] rg = m.expectedRange(sigma, reg.volBucket(), hz, k);
            p.range50 = rg[0]; p.range68 = rg[1]; p.range90 = rg[2];
            double[] ol = m.outlook(p.pFinal, sigma, reg.volBucket(), hz, k);
            p.pUp3 = ol[0]; p.pFlat = ol[1]; p.pDown3 = ol[2]; p.expReturn = ol[3]; p.expHigh = ol[4]; p.expLow = ol[5];

            // ---- trade gate: is the forecast strong enough to act on? (signals only — the app never trades)
            if (p.sideProb() < tradeThreshold) p.gate.add(String.format(Locale.US, "probability %.0f%% is below the %.0f%% threshold", p.sideProb() * 100, tradeThreshold * 100));
            if (hz.watchOnly()) p.gate.add(hz.swing() ? "watch only: too few independent weeks to prove an edge" : "watch only: moves this short are smaller than costs");
            if (!m.info.proven) p.gate.add(m.info.tested() ? "no proven edge on unseen sessions" : "not tested yet");
            if (p.confidence < 40) p.gate.add("confidence is low (" + p.confidence + ")");
            if (EventCalendarRisk.PRE_EVENT.equals(fc.risk.mode) && !hz.swing()) p.gate.add("a scheduled event is less than 30 minutes away");
            if (fc.quality.score < 0.6) p.gate.add("data quality is low");
            if (!Double.isNaN(p.range68) && p.range68 * 100 < 0.1) p.gate.add("expected move is too small to cover costs");
            if ("NEUTRAL".equals(p.direction)) p.gate.add("no side");
            if (governor != null) {
                String[] v = governor.get(hz.id);
                if (v == null) p.gate.add("not validated yet — the pre-live replay has not run");
                else if ("FAIL".equals(v[0])) p.gate.add("failed pre-live validation (" + v[1] + ")");
                else if ("WARN".equals(v[0])) p.gate.add("paper only — validation WARN (" + v[1] + ")");
                p.validation = v == null ? "" : v[0];
            }
            p.tradeable = p.gate.isEmpty();
            // signal quality: separate from the forecast itself — "67% bullish" is not "BUY"
            if (p.tradeable && p.confidence >= 65 && (hz.swing() || fc.quality.conflicts.isEmpty())) { p.signalQuality = "HIGH"; p.signalReason = "passes every check"; }
            else if (p.tradeable) { p.signalQuality = "MEDIUM"; p.signalReason = !fc.quality.conflicts.isEmpty() && !hz.swing() ? "sources disagree: " + fc.quality.conflicts.get(0) : "confidence is medium"; }
            else { p.signalQuality = "LOW"; p.signalReason = p.gate.isEmpty() ? "" : p.gate.get(0); }
            if (!fc.risk.why[i].isEmpty()) p.gate.add(0, "event risk: " + fc.risk.why[i]);

            // ---- What changed? (vs the previous forecast today)
            JSONObject pp = prevPreds == null ? null : prevPreds.optJSONObject(hz.id);
            if (pp != null) changed(p, pp, m, f, prevF, fc);
        }

        // ---- current signal: the most confident horizon with a proven edge
        int best = -1;
        for (int i = 0; i < fc.preds.size(); i++) {
            HPred p = fc.preds.get(i);
            if (p.has() && p.proven() && p.confidence >= 40 && !"NEUTRAL".equals(p.direction) && (best < 0 || p.confidence > fc.preds.get(best).confidence)) best = i;
        }
        fc.signal = best;
        HPred ref = best >= 0 ? fc.preds.get(best) : null;
        if (ref == null) for (HPred p : fc.preds) if (p.has() && p.hz.id.equals("1h")) ref = p;
        if (ref != null) for (Line l : ref.why) (l.pts > 0 ? fc.positives : fc.negatives).add(l);
        if (prev != null && day.date.equals(prev.optString("date")) && !reg.label().equals(prev.optString("regime")))
            fc.changed.add("Regime: " + prev.optString("regime") + " → " + reg.label());
        return fc;
    }

    static void changed(HPred p, JSONObject pp, HorizonModel m, double[] f, double[] prevF, Forecast fc) {
        double pf = pp.optDouble("pf", Double.NaN);
        if (Double.isNaN(pf)) return;
        String before = side(pf), now = side(p.pFinal);
        if (Math.abs(p.pFinal - pf) < 0.02 && before.equals(now)) return;
        p.changed.add(String.format(Locale.US, "Before: %s %.0f%% → now: %s %.0f%%", before, (before.equals("DOWN") ? 1 - pf : pf) * 100,
                now, (now.equals("DOWN") ? 1 - p.pFinal : p.pFinal) * 100));
        JSONArray pg = pp.optJSONArray("g");
        List<Line> moves = new ArrayList<>();
        for (int g = 0; pg != null && g < Math.min(HorizonModel.G, pg.length()); g++) {
            double dlt = p.groupPts[g] - pg.optDouble(g, 0);
            if (Math.abs(dlt) < 1) continue;
            String t = FeatureEngine.GROUP_NAMES[g];
            String in = prevF == null ? "" : movedInput(m, g, f, prevF);
            moves.add(new Line(t + (in.isEmpty() ? "" : " (" + in + ")"), dlt));
        }
        double slope = p.pFinal * (1 - p.pFinal) * 100;
        double de = (p.evPush - pp.optDouble("ev", 0)) * slope, dn = (p.newsPush - pp.optDouble("nw", 0)) * slope;
        if (Math.abs(de) >= 1) moves.add(new Line("Live option & futures evidence", de));
        if (Math.abs(dn) >= 1) moves.add(new Line("News & events" + (fc.events.isEmpty() ? "" : " — " + EventCalendarRisk.clean(fc.events.get(0).title)), dn));
        moves.sort((a, b) -> Double.compare(Math.abs(b.pts), Math.abs(a.pts)));
        for (int i = 0; i < Math.min(5, moves.size()); i++)
            p.changed.add(String.format(Locale.US, "• %s %+.1f pts", moves.get(i).text, moves.get(i).pts));
    }

    /** The input inside a group whose change moved the group's vote most: "rupee 5-day change ↑". */
    static String movedInput(HorizonModel m, int g, double[] f, double[] prevF) {
        LogReg lr = m.groups[g];
        if (lr == null || prevF.length != f.length) return "";
        int[] idx = FeatureEngine.GROUPS[g];
        int best = -1; double bv = 0;
        for (int j = 0; j < idx.length; j++) {
            double a = f[idx[j]], b = prevF[idx[j]];
            if (Double.isNaN(a) || Double.isNaN(b) || lr.sd[j] <= 0) continue;
            double v = Math.abs(lr.w[j + 1] * (a - b) / lr.sd[j]);
            if (v > bv) { bv = v; best = j; }
        }
        if (best < 0 || bv < 0.02) return "";
        int fi = idx[best];
        return FeatureEngine.NAMES[fi].toLowerCase(Locale.US) + (f[fi] > prevF[fi] ? " ↑" : " ↓");
    }

    /**
     * Data Quality + conflict engine. Inputs: share of each model group's inputs present (weighted by how much the models use it),
     * freshness of every live source against how fresh it should be (nothing is "stale" while the market is closed), the news reader,
     * and checks where sources disagree. Score = 0.6 × model inputs + 0.25 × live sources + 0.15 × evidence coverage.
     */
    static Quality quality(double[] f, List<HorizonModel> models, LiveContext ctx) {
        Quality q = new Quality();
        double[] imp = new double[HorizonModel.G];
        int nm = 0;
        for (HorizonModel m : models) { for (int g = 0; g < imp.length; g++) imp[g] += m.info.importance[g]; nm++; }
        double wsum = 0, avail = 0;
        for (int g = 0; g < HorizonModel.G; g++) {
            int[] idx = FeatureEngine.GROUPS[g];
            int have = 0;
            for (int j : idx) if (!Double.isNaN(f[j])) have++;
            double share = have / (double) idx.length;
            q.groups.put(FeatureEngine.GROUP_NAMES[g], share);
            double w = nm > 0 ? Math.max(1, imp[g] / nm) : 1;
            wsum += w; avail += w * share;
            if (share < 0.5) q.issues.add(FeatureEngine.GROUP_NAMES[g] + ": only " + have + " of " + idx.length + " inputs available");
        }
        double a = wsum > 0 ? avail / wsum : 0;
        double src = 0.6, live = 0.5;
        if (ctx != null) {
            double ss = 0; int sn = 0;
            for (Map.Entry<String, Long> e : ctx.sourceTime.entrySet()) {
                double exp = expectedMin(e.getKey());
                boolean liveSource = exp <= 30;
                double age = (ctx.now - e.getValue()) / 60000.0;
                double sc = !ctx.marketOpen && liveSource ? 1 : age <= exp ? 1 : Math.max(0, 1 - (age - exp) / (3 * exp));
                q.sources.put(e.getKey(), sc);
                ss += sc; sn++;
                if (sc < 0.5) q.issues.add(e.getKey() + " is old (" + Math.round(age) + " min)");
            }
            double nr = ctx.newsReader.startsWith("Gemini") ? 1 : ctx.newsReader.isEmpty() ? 0.5 : 0.6;
            q.sources.put("News reader (" + (ctx.newsReader.isEmpty() ? "none" : ctx.newsReader) + ")", nr);
            ss += nr; sn++;
            src = sn > 0 ? ss / sn : 0.6;
            live = ctx.evidenceAvailable ? Math.max(0, Math.min(1, ctx.evidenceCoverage)) : 0.5;
            if (!ctx.evidenceAvailable) q.issues.add("Live option / futures evidence not loaded (log in to Kite)");
            for (int i = 0; i < Math.min(4, ctx.dataIssues.size()); i++) q.issues.add(ctx.dataIssues.get(i));
            conflicts(q, f, ctx);
        }
        q.score = 0.6 * a + 0.25 * src + 0.15 * live;
        return q;
    }

    /** Freshness each live source should have, in minutes (daily sources: three days, to cover weekends). */
    static double expectedMin(String source) {
        String s = source.toLowerCase(Locale.US);
        if (s.contains("fii") || s.contains("weights") || s.contains("daily")) return 3 * 1440;
        if (s.contains("global")) return 30;
        if (s.contains("news")) return 20;
        if (s.contains("candles")) return 10;
        if (s.contains("option")) return 4;
        return 3;   // spot, futures quote, Bank Nifty, breadth, Kite prices
    }

    /** Checks where two sources tell different stories. */
    static void conflicts(Quality q, double[] f, LiveContext ctx) {
        // GIFT Nifty vs the overnight world: a gap one way while world markets went the other way
        if (!ctx.marketOpen && !Double.isNaN(ctx.gift) && !Double.isNaN(ctx.futLast) && ctx.futLast > 0 && !Double.isNaN(f[52])) {
            double gap = (ctx.gift / ctx.futLast - 1) * 100;
            if (gap > 0.4 && f[52] < -0.8 || gap < -0.4 && f[52] > 0.8)
                q.conflicts.add(String.format(Locale.US, "GIFT Nifty points %+.1f%% but world markets were %s overnight", gap, f[52] > 0 ? "up" : "down"));
        }
        // the Today-tab evidence vs the price models' own read of today (move since open, scaled)
        if (ctx.evidenceAvailable && ctx.evidenceConf >= 50 && !Double.isNaN(f[2])) {
            if (ctx.evidenceScore >= 30 && f[2] < -1.0 || ctx.evidenceScore <= -30 && f[2] > 1.0)
                q.conflicts.add(String.format(Locale.US, "option/futures evidence is %s (%+.0f) while Nifty is %s today", ctx.evidenceScore > 0 ? "bullish" : "bearish",
                        ctx.evidenceScore, f[2] > 0 ? "rising" : "falling"));
        }
        // bank vs index: Bank Nifty and Nifty far apart today
        if (!Double.isNaN(f[13]) && Math.abs(f[13]) > 1.5) q.conflicts.add("Bank Nifty and Nifty are moving very differently today");
        // stale daily flow data
        if (!ctx.fiiDate.isEmpty() && !ctx.fiiDate.equals("typed")) {
            try {
                java.text.SimpleDateFormat fm = new java.text.SimpleDateFormat("dd-MMM-yyyy", Locale.US);
                long age = (ctx.now - fm.parse(ctx.fiiDate).getTime()) / 86400000L;
                if (age > 4) q.issues.add("FII / DII figures are " + age + " days old");
            } catch (Exception ignored) { }
        }
    }

    // ================================================================== state for "What changed?" and the event memory

    public static JSONObject state(Forecast fc) throws Exception {
        JSONObject preds = new JSONObject();
        for (HPred p : fc.preds) {
            if (!p.has()) continue;
            JSONArray g = new JSONArray();
            for (double v : p.groupPts) g.put(v);
            preds.put(p.hz.id, new JSONObject().put("pf", p.pFinal).put("ev", p.evPush).put("nw", p.newsPush).put("g", g).put("dir", p.direction)
                    .put("conf", p.confidence));
        }
        JSONArray f = new JSONArray();
        for (double v : fc.features) f.put(Double.isNaN(v) ? -999 : v);
        return new JSONObject().put("t", fc.at).put("date", fc.date).put("k", fc.k).put("regime", fc.regime.label()).put("mode", fc.risk.mode)
                .put("preds", preds).put("f", f);
    }

    /**
     * Event memory: the day's sequence of things that mattered (regime changes, forecast flips, new high-impact events,
     * event-risk windows, VIX jumps), so a forecast can be read against what led up to it. `mem` is today's memory file.
     */
    public static JSONObject remember(JSONObject mem, Forecast fc, JSONObject prev, int minute) throws Exception {
        if (mem == null || !fc.date.equals(mem.optString("date"))) mem = new JSONObject().put("date", fc.date).put("items", new JSONArray()).put("seen", new JSONArray());
        JSONArray items = mem.getJSONArray("items"), seen = mem.getJSONArray("seen");
        String tm = String.format(Locale.US, "%d:%02d", minute / 60, minute % 60);
        boolean samePrev = prev != null && fc.date.equals(prev.optString("date"));
        if (items.length() == 0 || !samePrev || !fc.regime.label().equals(prev.optString("regime"))) add(items, tm, "Regime: " + fc.regime.label() + " (" + fc.regime.detail() + ")");
        if (samePrev && !fc.risk.mode.equals(prev.optString("mode")) && !EventCalendarRisk.NORMAL.equals(fc.risk.mode)) add(items, tm, "Event risk: " + fc.risk.mode + " — " + fc.risk.text);
        JSONObject pp = samePrev ? prev.optJSONObject("preds") : null;
        for (HPred p : fc.preds) {
            if (!p.has() || p.hz.swing() || pp == null) continue;
            JSONObject o = pp.optJSONObject(p.hz.id);
            if (o == null) continue;
            String was = o.optString("dir");
            if (!was.equals(p.direction) && !"NEUTRAL".equals(p.direction) && p.confidence >= 40)
                add(items, tm, p.hz.label + " forecast turned " + p.direction + String.format(Locale.US, " (%.0f%%)", p.sideProb() * 100));
        }
        java.util.Set<String> s = new java.util.HashSet<>();
        for (int i = 0; i < seen.length(); i++) s.add(seen.optString(i));
        for (EventImpact.Event e : fc.events) {
            if (e.maxAbs() < 0.25 || s.contains(e.title)) continue;
            seen.put(e.title);
            s.add(e.title);
            add(items, tm, "New " + (e.direction > 0 ? "positive" : "negative") + " event (tier " + e.tier + "): " + EventCalendarRisk.clean(e.title));
        }
        double vixJump = fc.features.length > 15 ? fc.features[15] : Double.NaN;
        if (!Double.isNaN(vixJump) && vixJump > 0.08 && !mem.optBoolean("vix", false)) { mem.put("vix", true); add(items, tm, String.format(Locale.US, "India VIX up %.0f%% today", (Math.exp(vixJump) - 1) * 100)); }
        while (items.length() > 60) { JSONArray t = new JSONArray(); for (int i = items.length() - 60; i < items.length(); i++) t.put(items.get(i)); mem.put("items", t); items = t; }
        return mem;
    }

    static void add(JSONArray items, String tm, String text) { items.put(tm + "  " + text); }

    public static List<String> memoryLines(JSONObject mem) {
        List<String> out = new ArrayList<>();
        JSONArray a = mem == null ? null : mem.optJSONArray("items");
        for (int i = 0; a != null && i < a.length(); i++) out.add(a.optString(i));
        return out;
    }

    static String side(double p) { return p >= 0.53 ? "UP" : p <= 0.47 ? "DOWN" : "FLAT"; }
    static double sig(double v) { return v > 30 ? 1 : v < -30 ? 0 : 1 / (1 + Math.exp(-v)); }
    static double logit(double p) { p = Math.max(0.01, Math.min(0.99, p)); return Math.log(p / (1 - p)); }

    static double[] darr(JSONArray a) {
        if (a == null) return null;
        double[] v = new double[a.length()];
        for (int i = 0; i < v.length; i++) { double x = a.optDouble(i, -999); v[i] = x == -999 ? Double.NaN : x; }
        return v;
    }
}

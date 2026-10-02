import com.krish.niftydirection.forecast.*;
import com.krish.niftydirection.intel.*;
import com.krish.niftydirection.model.EventItem;
import com.krish.niftydirection.model.NewsItem;

import org.json.JSONObject;

import java.util.*;

/** Prediction engine: walk-forward ensemble, edge proof, calibration, regime, events, feedback, explanations. */
public class IntelTest {
    static int pass = 0, fail = 0;
    static void check(String name, boolean ok) { if (ok) pass++; else { fail++; System.out.println("FAIL " + name); } }

    /** ForecastTest's synthetic market plus the extra world series; USD/INR follows Brent (a planted link for the impact graph). */
    static History synth(int sessions, double signal, long seed) {
        History h = ForecastTest.synth(sessions, signal, seed);
        Random r = new Random(seed * 31 + 7);
        String[] extra = {Markets.DOW, Markets.RUSSELL, Markets.USVIX, Markets.USFUT, Markets.DAX, Markets.FTSE, Markets.CAC, Markets.KOSPI, Markets.SHANGHAI,
                Markets.ASX, Markets.TAIWAN, Markets.EURUSD, Markets.USDJPY, Markets.USDCNY, Markets.US13W, Markets.US5Y, Markets.US30Y, Markets.WTI,
                Markets.GOLD, Markets.SILVER, Markets.COPPER};
        TreeMap<String, Double> brent = h.global.get(Markets.BRENT);
        TreeMap<String, Double> inr = new TreeMap<>();
        double x = 82, prevB = Double.NaN;
        for (Map.Entry<String, Double> e : brent.entrySet()) {
            if (!Double.isNaN(prevB)) x *= Math.exp(0.3 * Math.log(e.getValue() / prevB) + r.nextGaussian() * 0.002);
            prevB = e.getValue();
            inr.put(e.getKey(), x);
        }
        h.global.put(Markets.USDINR, inr);
        for (String k : extra) {
            TreeMap<String, Double> m = new TreeMap<>();
            double v = Markets.isYield(k) ? 4 : 100;
            for (String d : brent.keySet()) { v *= Math.exp(r.nextGaussian() * 0.01); m.put(d, v); }
            h.global.put(k, m);
        }
        // NSE positioning (daily) and the ten heavyweights (5-minute: Nifty's own path plus noise)
        for (String k : com.krish.niftydirection.data.HistoryLoader.POI_KEYS) {
            TreeMap<String, Double> m = new TreeMap<>();
            double v = 0.4;
            for (String d : h.niftyDaily.keySet()) { v = Math.max(0.05, Math.min(0.95, v + r.nextGaussian() * 0.02)); m.put(d, v); }
            h.global.put(k, m);
        }
        for (String[] st : FeatureEngine.LEADER_STOCKS) {
            double lv = 1000;
            for (History.Day day : h.days) {
                float[] a = day.aux("STK:" + st[0]);
                for (int i = 0; i < History.BARS; i++) if (!Float.isNaN(day.c[i])) { lv *= Math.exp(r.nextGaussian() * 0.001); a[i] = (float) (lv * day.c[i] / 25000); }
            }
        }
        return h;
    }

    public static void main(String[] a) throws Exception {
        long t0 = System.currentTimeMillis();
        // ---- 1. a planted pattern is learnt and PROVEN on unseen sessions
        History sig = synth(560, 1.0, 11);
        Trainer.Dataset ds = Trainer.dataset(sig);
        check("dataset: every 15 min + the open", ds.size() > 560 * 25);
        Horizon h1 = Horizon.of("1h");
        HorizonModel m1 = Trainer.train(sig, ds, h1);
        System.out.println("planted 1h: " + IntelRunnerLine(m1.info) + "  calib " + m1.calib.kind + "  (" + (System.currentTimeMillis() - t0) / 1000 + "s)");
        check("walk-forward test ran", m1.info.tested() && m1.info.testDays == 112);
        check("planted pattern: proven edge with a positive bootstrap band", m1.info.proven && m1.info.skillLo > 0);
        System.out.println("learnt group shares: " + Arrays.toString(Arrays.stream(m1.info.importance).map(v -> Math.round(v)).toArray()));
        check("the groups that carry the planted drift (technical, sector) lead the meta model",
                m1.info.importance[0] + m1.info.importance[2] > 50);
        History fresh = synth(160, 1.0, 99);
        int[] fs = fresh.sessionIndex();
        int right = 0, n = 0;
        for (int d = 60; d < fresh.days.size() - 1; d++) for (int k = 12; k <= 60; k += 12) {
            double[] f = FeatureEngine.compute(fresh, d, k, fs);
            double p = m1.predict(f, Regime.of(f)).p;
            double now = fresh.days.get(d).closeAt(k), then = fresh.days.get(d).closeAt(k + 12);
            n++; if ((p >= 0.5) == (then > now)) right++;
        }
        System.out.println("fresh sessions: right " + right + " of " + n);
        check("planted pattern holds on fresh sessions", right > 0.65 * n);

        // ---- 2. pure noise earns no edge
        History noise = synth(560, 0.0, 5);
        HorizonModel mn = Trainer.train(noise, Trainer.dataset(noise), h1);
        System.out.println("noise 1h: " + IntelRunnerLine(mn.info));
        check("pure noise: no proven edge", mn.info.tested() && !mn.info.proven);

        // ---- 3. no look-ahead in any of the " + FeatureEngine.N + " inputs
        int[] sidx = sig.sessionIndex();
        boolean same = true;
        Random r = new Random(3);
        for (int t = 0; t < 60; t++) {
            int d = 230 + r.nextInt(sig.days.size() - 231), k = r.nextInt(76);
            double[] full = FeatureEngine.compute(sig, d, k, sidx);
            History cut = ForecastTest.truncated(sig, d, Math.max(k, 1));
            if (k == 0) { History.Day td = cut.days.get(d); for (int i = 0; i < 75; i++) { td.c[i] = Float.NaN; td.h[i] = Float.NaN; td.l[i] = Float.NaN; if (i > 0) td.o[i] = Float.NaN; } td.aux.clear(); td.bars = 0; }
            double[] part = FeatureEngine.compute(cut, d, k, cut.sessionIndex());
            for (int j = 0; j < full.length; j++) if (!(Double.isNaN(full[j]) && Double.isNaN(part[j])) && full[j] != part[j]) { same = false; System.out.println("look-ahead in " + FeatureEngine.NAMES[j] + " d=" + d + " k=" + k); }
        }
        check("no look-ahead (60 random moments, incl. the open)", same);
        double[] f200 = FeatureEngine.compute(sig, 300, 30, sidx);
        int filled = 0;
        for (double v : f200) if (!Double.isNaN(v)) filled++;
        check("all " + FeatureEngine.N + " inputs computable on full data (" + filled + ")", filled >= FeatureEngine.N - 1);

        // ---- honesty: on a market with no pattern the model must say "base rate", not a confident guess
        History noise = synth(700, 0.0, 5);
        HorizonModel nm = Trainer.train(noise, Trainer.dataset(noise), Horizon.of("1h"));
        int[] nsi = noise.sessionIndex();
        double dev = 0; int nd = 0;
        for (int d = noise.days.size() - 60; d < noise.days.size(); d++) for (int k = 6; k < 75; k += 12) {
            double[] f = FeatureEngine.compute(noise, d, k, nsi); dev += Math.abs(nm.predict(f, Regime.of(f)).p - nm.base); nd++; }
        System.out.printf("noise 1h: shrink %.2f, avg |p − base| %.3f, groups used: %s%n", nm.shrink, dev / nd, nm.info.used);
        check("pure noise: forecasts stay at the base rate (no confident guesses)", dev / nd < 0.02 && nm.shrink <= 0.3);
        check("pure noise: no input group survives gating", nm.info.used.startsWith("none"));
        check("Day close / 15m / 30m / 1W flags: 15m, 30m, 1W watch only", Horizon.of("15m").watchOnly() && Horizon.of("1W").watchOnly() && !Horizon.of("1D").watchOnly() && !Horizon.of("1h").watchOnly());

        // ---- 4. live path = history path
        int dl = sig.days.size() - 1, kl = 40;
        History live = ForecastTest.truncated(sig, dl, kl);
        IntelEngine.Forecast fc = IntelEngine.forecast(Collections.singletonList(m1), live, dl, kl, false, null, null, null, 0.62);
        double[] fh = FeatureEngine.compute(sig, dl, kl, sidx);
        IntelEngine.HPred p1 = fc.preds.get(2);
        check("live forecast = history forecast", Math.abs(m1.predict(fh, Regime.of(fh)).p - p1.pModel) < 1e-12 && Math.abs(p1.pFinal - p1.pModel) < 1e-12);
        check("nine horizon slots, only trained ones filled", fc.preds.size() == 9 && p1.has() && !fc.preds.get(0).has());
        check("why: group lines + starting point", !p1.why.isEmpty() && p1.why0.contains("went up"));
        check("expected range ordered", p1.range50 < p1.range68 && p1.range68 < p1.range90 && p1.range68 > 0);
        check("confidence in 0..100 with a label", p1.confidence >= 0 && p1.confidence <= 100 && !p1.confLabel.isEmpty());
        IntelEngine.Forecast strict = IntelEngine.forecast(Collections.singletonList(m1), live, dl, kl, false, null, null, null, 0.99);
        check("trade gate: 99% threshold → NO TRADE with a reason", !strict.preds.get(2).tradeable && strict.preds.get(2).gate.get(0).contains("threshold"));
        HorizonModel.Output out = m1.predict(fh, Regime.of(fh));
        double s = out.intercept;
        for (double c : out.contrib) s += c;
        check("contributions add up to the meta logit", Math.abs(1 / (1 + Math.exp(-s)) - out.pRaw) < 1e-9);

        // ---- 5. what changed + event memory
        JSONObject st = IntelEngine.state(IntelEngine.forecast(Collections.singletonList(m1), ForecastTest.truncated(sig, dl, 10), dl, 10, false, null, null, null, 0.62));
        IntelEngine.Forecast fc2 = IntelEngine.forecast(Collections.singletonList(m1), live, dl, kl, false, null, st, null, 0.62);
        check("what changed: before → now", fc2.preds.get(2).changed.isEmpty() || fc2.preds.get(2).changed.get(0).startsWith("Before:"));
        JSONObject mem = IntelEngine.remember(null, fc2, st, 600);
        check("event memory records the regime", IntelEngine.memoryLines(mem).size() >= 1);

        // ---- 6. targets
        int[] sp = Trainer.targetSpec(75, Horizon.of("15m"));
        check("15m from the close = 9:30 next session", sp[0] == 1 && sp[1] == 2);
        sp = Trainer.targetSpec(0, Horizon.of("1D"));
        check("1D from the open = today's close", sp[0] == 0 && sp[1] == -1);
        sp = Trainer.targetSpec(30, Horizon.of("1W"));
        check("1W = close 5 sessions later", sp[0] == 5 && sp[1] == -1);

        // ---- 7. calibration
        Random cr = new Random(8);
        int N = 6000;
        double[] raw = new double[N]; int[] yy = new int[N];
        for (int i = 0; i < N; i++) { double pt = 0.3 + 0.4 * cr.nextDouble(); raw[i] = Math.max(0.01, Math.min(0.99, 0.5 + 2.2 * (pt - 0.5))); yy[i] = cr.nextDouble() < pt ? 1 : 0; }
        Calibrator iso = Calibrator.fit(raw, yy, N);
        double bRaw = 0, bCal = 0;
        for (int i = 0; i < N; i++) { bRaw += sq(raw[i] - yy[i]); bCal += sq(iso.apply(raw[i]) - yy[i]); }
        check("isotonic chosen with enough data", iso.kind.equals("isotonic"));
        check("calibration lowers the Brier score of an over-confident model", bCal < bRaw);
        boolean mono = true;
        for (double v = 0.01; v < 0.99; v += 0.01) if (iso.apply(v + 0.01) < iso.apply(v) - 1e-12) mono = false;
        check("isotonic map is monotone", mono);
        Calibrator pl = Calibrator.fit(Arrays.copyOf(raw, 400), Arrays.copyOf(yy, 400), 400);
        check("Platt with little data, shrinks over-confidence", pl.kind.equals("platt") && pl.a < 1);

        // ---- 8. regime
        double[] f = new double[FeatureEngine.N];
        Arrays.fill(f, Double.NaN);
        f[11] = 1.2; f[12] = 1.5; f[10] = 0.8; f[37] = 0.5; f[16] = 0.0; f[52] = 0.9;
        Regime rg = Regime.of(f);
        check("trending bull + risk-on", rg.label().equals("TRENDING BULL") && rg.risk.equals("RISK_ON"));
        f[11] = 0.1; f[12] = -0.1; f[10] = 0.05; f[37] = 0.05; f[16] = 0.4; f[52] = -2.5;
        rg = Regime.of(f);
        check("global shock headline, range + high vol axes", rg.label().equals("GLOBAL SHOCK") && rg.trend.equals("RANGE") && rg.vol.equals("HIGH_VOL") && rg.panic);
        check("regime flags feed the meta model", rg.rangeFlag() == 1 && rg.highVolFlag() == 1);

        // ---- 9. news → events → impact
        long now = 1_800_000_000_000L;
        Map<String, Double> w = new LinkedHashMap<>();
        w.put("HDFCBANK", 0.13); w.put("TRENT", 0.005);
        Map<String, String> sec = new LinkedHashMap<>();
        sec.put("HDFCBANK", "Financials"); sec.put("TRENT", "Consumer");
        List<NewsItem> news = new ArrayList<>();
        for (int i = 0; i < 50; i++) news.add(item("Crude oil jumps on supply fears", "Site " + i, -0.6, "HIGH", now - 30 * 60000, i == 0, false));
        List<EventImpact.Event> ev = EventImpact.build(news, null, w, sec, now, "2026-10-01", 600);
        check("50 copies of one story = 1 event", ev.size() == 1);
        List<NewsItem> tiers = new ArrayList<>();
        tiers.add(item("RBI raises repo rate", "RBI (official)", -0.6, "HIGH", now - 30 * 60000, true, true));
        tiers.add(item("Fed signals pause on rates", "Reuters", -0.6, "HIGH", now - 30 * 60000, true, false));
        tiers.add(item("Rate panic coming says influencer", "YouTube", -0.6, "HIGH", now - 30 * 60000, true, false));
        ev = EventImpact.build(tiers, null, w, sec, now, "2026-10-01", 600);
        check("tier order: primary > major press > social", ev.size() == 3 && ev.get(0).tier == 1 && ev.get(1).tier == 2 && ev.get(2).tier == 4
                && Math.abs(ev.get(0).impact[2]) > Math.abs(ev.get(1).impact[2]) && Math.abs(ev.get(1).impact[2]) > Math.abs(ev.get(2).impact[2]));
        check("'Sensex Daily' is not NSE", EventImpact.tier("Sensex Daily", false) == 3);
        NewsItem old = item("Inflation fears grip markets", "Mint", -0.5, "HIGH", now - 6 * 3600000L, true, false);
        EventImpact.Event oe = EventImpact.build(Collections.singletonList(old), null, w, sec, now, "2026-10-01", 600).get(0);
        check("older news decays faster at short horizons", Math.abs(oe.impact[0]) < Math.abs(oe.impact[8]));
        NewsItem rum = item("Inflation fears grip markets", "Mint", -0.5, "HIGH", now - 6 * 3600000L, true, false);
        rum.speculative = true;
        EventImpact.Event re = EventImpact.build(Collections.singletonList(rum), null, w, sec, now, "2026-10-01", 600).get(0);
        check("rumours count half and fade fast", re.persistence.equals("FLOW") && Math.abs(re.impact[3]) < 0.5 * Math.abs(oe.impact[3]));
        EventImpact.Event big = EventImpact.build(Collections.singletonList(item("HDFC Bank results beat estimates", "Mint", 0.6, "HIGH", now, true, false)), null, w, sec, now, "2026-10-01", 600).get(0);
        EventImpact.Event small = EventImpact.build(Collections.singletonList(item("Trent results beat estimates", "Mint", 0.6, "HIGH", now, true, false)), null, w, sec, now, "2026-10-01", 600).get(0);
        check("company news weighted by Nifty weight", big.exposure == 1 && small.exposure < 0.2 && big.constituents.contains("HDFCBANK"));
        check("news score bounded", Math.abs(EventImpact.score(ev)[2]) < 1);

        // ---- 9b. persistence classes + company → sector → Nifty chain
        w.put("ICICIBANK", 0.08); sec.put("ICICIBANK", "Financials");
        EventImpact.Event rs = EventImpact.build(Collections.singletonList(item("HDFC Bank Q2 results: profit beats estimates", "Mint", 0.6, "HIGH", now - 4 * 3600000L, true, false)), null, w, sec, now, "2026-10-01", 600).get(0);
        EventImpact.Event opi = EventImpact.build(Collections.singletonList(item("Broker says HDFC Bank target price raised", "Mint", 0.6, "HIGH", now - 4 * 3600000L, true, false)), null, w, sec, now, "2026-10-01", 600).get(0);
        EventImpact.Event blk = EventImpact.build(Collections.singletonList(item("Block deal in HDFC Bank shares", "Mint", 0.6, "HIGH", now - 4 * 3600000L, true, false)), null, w, sec, now, "2026-10-01", 600).get(0);
        check("persistence classes: results / opinion / block deal", rs.persistence.equals("FUNDAMENTAL") && opi.persistence.equals("OPINION") && blk.persistence.equals("FLOW"));
        check("lasting news keeps more of its impact after 4 hours", Math.abs(rs.impact[2]) > Math.abs(opi.impact[2]) && Math.abs(opi.impact[2]) > Math.abs(blk.impact[2]));
        check("company → sector → Nifty chain shown", rs.chain.contains("HDFCBANK") && rs.chain.contains("Financials") && rs.chain.contains("peers 8%"));

        // ---- 9c. more sources: official central banks + company searches
        boolean fed = false;
        for (String[] fd : com.krish.niftydirection.data.News.FEEDS) if (fd[0].startsWith("Fed (official)")) fed = true;
        Map<String, Double> wt = new LinkedHashMap<>();
        String[] syms = {"HDFCBANK", "RELIANCE", "ICICIBANK", "BHARTIARTL", "INFY", "TCS", "SBIN", "LT", "AXISBANK", "ITC", "KOTAKBANK", "BAJFINANCE", "HINDUNILVR", "MARUTI", "M&M", "SUNPHARMA"};
        for (int i = 0; i < syms.length; i++) wt.put(syms[i], 0.12 - i * 0.005);
        List<String[]> cf = com.krish.niftydirection.data.News.companyFeeds(wt);
        check("official central-bank feeds + 3 company searches for the top 15", fed && cf.size() == 3 && cf.get(0)[1].toLowerCase().contains("%22hdfc+bank%22") && !cf.get(2)[1].contains("SUNPHARMA"));
        check("Fed is a tier-1 source", EventImpact.tier("Fed (official)", false) == 1 && EventImpact.tier("Bank of Japan (official)", false) == 1);

        // ---- 10. surprise + event calendar
        List<EventCalendarRisk.Surprise> su = EventCalendarRisk.surprises("2026-10-01 16:00 India CPI | exp=4.5 | act=4.3 | good=down\n2026-10-02 random line");
        check("surprise parsed: lower CPI than expected is good for shares", su.size() == 1 && su.get(0).impact() > 0 && su.get(0).minute == 960);
        List<EventImpact.Event> se = EventImpact.build(null, su, w, sec, now, "2026-10-01", 17 * 60);
        check("released surprise becomes a primary event", se.size() == 1 && se.get(0).primary && se.get(0).impact[2] > 0);
        check("unreleased surprise is not an event", EventImpact.build(null, su, w, sec, now, "2026-10-01", 15 * 60).isEmpty());
        List<EventItem> cal = Collections.singletonList(new EventItem("2026-10-07", "RBI policy decision", 3, "RBI calendar"));
        EventCalendarRisk.Risk rk = EventCalendarRisk.assess(cal, "2026-10-07", 9 * 60 + 45);
        check("15 min before RBI → PRE-EVENT, short horizons halved", rk.mode.equals(EventCalendarRisk.PRE_EVENT) && rk.mult[0] == 0.5 && rk.eventDriven());
        check("after the release → POST-EVENT", EventCalendarRisk.assess(cal, "2026-10-07", 10 * 60 + 30).mode.equals(EventCalendarRisk.POST_EVENT));
        EventCalendarRisk.Risk wk = EventCalendarRisk.assess(cal, "2026-10-02", 11 * 60);
        check("RBI next week lowers only the 1-week confidence", wk.mult[8] < 1 && wk.mult[2] == 1 && wk.mult[7] == 1);
        check("expiry is not an event", EventCalendarRisk.minuteOf(new EventItem("2026-10-06", "Nifty weekly expiry", 1, "x")) == -2);

        // ---- 11. feedback: log, resolve, scorecard, overlays
        List<JSONObject> log = new ArrayList<>();
        for (int d = 400; d < 420; d++) for (int k = 6; k <= 66; k += 30)
            log.add(Feedback.entry(0, sig.days.get(d).date, k, h1, sig.days.get(d).closeAt(k), 0.55, 0.56, 0.1, 0, "RANGE"));
        int res = Feedback.resolve(log, sig);
        check("forecasts resolved from the price history", res == log.size());
        JSONObject e0 = log.get(0);
        check("outcome = price after 12 bars", e0.optInt("up") == (sig.days.get(400).closeAt(18) > sig.days.get(400).closeAt(6) ? 1 : 0));
        Map<String, double[]> sc = Feedback.scorecard(log, null);
        check("scorecard per horizon", sc.containsKey("1h") && sc.get("1h")[0] == log.size());
        double[] ov = Feedback.applyOverlay(0.5, 1, 1, Horizon.SHORT, null);
        check("overlay shift capped at 0.4 logit", Math.abs(Math.log(ov[0] / (1 - ov[0])) - 0.4) < 1e-9);
        check("too few rows → keep priors", Feedback.learnOverlay(log, Horizon.INTRADAY) == null);
        List<JSONObject> rich = new ArrayList<>();
        Random fr = new Random(4);
        for (int i = 0; i < 600; i++) {
            double evd = fr.nextGaussian() * 0.5;
            int up = fr.nextDouble() < 1 / (1 + Math.exp(-2.5 * evd)) ? 1 : 0;
            rich.add(new JSONObject().put("done", true).put("h", "1h").put("pm", 0.5).put("ev", evd).put("nw", 0.0).put("up", up));
        }
        Feedback.Overlay lo = Feedback.learnOverlay(rich, Horizon.INTRADAY);
        check("overlay re-learnt when the record shows evidence works better than the prior", lo != null && lo.lr != null && lo.logLoss < lo.priorLogLoss);

        // ---- 12. impact graph learns the planted crude → rupee link
        List<ImpactGraph.Edge> g = ImpactGraph.build(sig);
        ImpactGraph.Edge br = null;
        for (ImpactGraph.Edge e : g) if (e.from.equals(Markets.BRENT) && e.to.equals(Markets.USDINR)) br = e;
        check("impact graph: Brent → rupee learnt and significant", br != null && br.corr > 0.5 && br.significant());

        // ---- 12b. cross-market chains: learnt betas, implied moves, live paths
        List<CrossMarket.Path> paths = CrossMarket.paths(sig, sig.days.get(400).date);
        CrossMarket.Path oil = paths.get(0);
        System.out.println(oil.describe());
        check("oil chain learns Brent → rupee beta ≈ 0.3", Math.abs(oil.beta[0] - 0.3) < 0.08 && oil.t[0] > 2);
        check("chain inputs present in the feature vector", !Double.isNaN(f200[70]) && !Double.isNaN(f200[73]) && FeatureEngine.groupOf(70) == 9);

        // ---- 12c. three-way outlook, expected return, typical high/low
        double[] ol = m1.outlook(0.62, 0.01, 1, h1);
        System.out.println("outlook 1h @62%: " + Arrays.toString(ol));
        check("three-way probabilities add up", Math.abs(ol[0] + ol[1] + ol[2] - 1) < 1e-12 && ol[1] > 0.05 && ol[1] < 0.5);
        check("expected return follows the side; high above, low below", ol[3] > 0 && ol[4] > 0 && ol[5] < 0 && m1.outlook(0.38, 0.01, 1, h1)[3] < 0);
        check("forecast carries the outlook", !Double.isNaN(p1.pFlat) && !Double.isNaN(p1.expHigh) && !p1.signalQuality.isEmpty());

        // ---- 12d. data quality + conflicts + signal quality
        IntelEngine.LiveContext lc = new IntelEngine.LiveContext();
        lc.evidenceAvailable = true; lc.evidenceScore = 60; lc.evidenceConf = 80; lc.evidenceCoverage = 1; lc.marketOpen = true; lc.newsReader = "keywords";
        lc.today = sig.days.get(dl).date; lc.minute = 11 * 60;
        lc.sourceTime.put("Nifty spot", lc.now - 60000L); lc.sourceTime.put("Option chain", lc.now - 30 * 60000L);
        History down = ForecastTest.truncated(sig, dl, kl);
        History.Day dd0 = down.days.get(dl);
        for (int i = 0; i < kl; i++) { float fac = (float) (1 - 0.0012 * (i + 1)); dd0.c[i] = dd0.o[0] * fac; dd0.h[i] = dd0.c[i] * 1.0002f; dd0.l[i] = dd0.c[i] * 0.9998f; }
        IntelEngine.Forecast fq = IntelEngine.forecast(Collections.singletonList(m1), down, dl, kl, false, lc, null, null, 0.62);
        System.out.println("quality " + fq.quality.score + " sources " + fq.quality.sources + " conflicts " + fq.quality.conflicts);
        check("stale option chain flagged, live spot fresh", fq.quality.sources.get("Option chain") < 0.5 && fq.quality.sources.get("Nifty spot") == 1);
        check("bullish evidence vs a falling Nifty = conflict", !fq.quality.conflicts.isEmpty() && fq.quality.conflicts.get(0).contains("bullish"));
        IntelEngine.HPred pq = fq.preds.get(2);
        check("signal quality with a reason", ("LOW".equals(pq.signalQuality) && !pq.signalReason.isEmpty()) || !"LOW".equals(pq.signalQuality));

        // ---- 13. expected range covers about 68% of fresh moves
        int in = 0, tot = 0;
        for (int d = 60; d < fresh.days.size() - 1; d++) for (int k = 6; k <= 60; k += 6) {
            double[] ff = FeatureEngine.compute(fresh, d, k, fs);
            double[] rr = m1.expectedRange(Features.sigma(fresh, fresh.days.get(d).date), Regime.of(ff).volBucket(), h1);
            double mv = Math.abs(Math.log(fresh.days.get(d).closeAt(k + 12) / fresh.days.get(d).closeAt(k)));
            tot++; if (mv <= rr[1]) in++;
        }
        System.out.println("68% range covered " + in + " of " + tot);
        check("68% range covers 58–78% of fresh moves", in > 0.58 * tot && in < 0.78 * tot);

        // ---- 14. model JSON round trip
        HorizonModel back = HorizonModel.fromJson(new JSONObject(m1.toJson().toString()));
        check("model JSON round trip", Math.abs(back.predict(fh, Regime.of(fh)).p - m1.predict(fh, Regime.of(fh)).p) < 1e-9
                && back.info.proven == m1.info.proven && back.info.reliability.length == m1.info.reliability.length && back.calib.kind.equals(m1.calib.kind));

        System.out.println(pass + " passed, " + fail + " failed (" + (System.currentTimeMillis() - t0) / 1000 + "s)");
        if (fail > 0) System.exit(1);
    }

    static String IntelRunnerLine(HorizonModel.Info i) {
        return String.format(Locale.US, "hit %.3f vs %.3f, skill %+.4f [%+.4f, %+.4f] %s", i.hit, i.baseHit, i.skill, i.skillLo, i.skillHi, i.proven ? "EDGE" : "no edge");
    }

    static NewsItem item(String title, String source, double imp, String sev, long t, boolean lead, boolean official) {
        NewsItem n = new NewsItem();
        n.title = title; n.source = source; n.niftyImpact = imp; n.severity = sev; n.time = t; n.lead = lead; n.official = official; n.read = true;
        n.verification = official ? "VERIFIED" : "CONFIRMED";
        return n;
    }

    static int argmax(double[] v) { int b = 0; for (int i = 1; i < v.length; i++) if (v[i] > v[b]) b = i; return b; }
    static double sq(double v) { return v * v; }
}

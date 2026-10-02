import com.krish.niftydirection.engine.Chain;
import com.krish.niftydirection.engine.Engine;
import com.krish.niftydirection.engine.Factor;
import com.krish.niftydirection.engine.Greeks;
import com.krish.niftydirection.engine.Result;
import com.krish.niftydirection.model.*;

import java.util.*;

/** Scenario tests for the v1.2 direction engine. */
public class EngineTest {
    static int pass = 0, fail = 0;
    static void check(boolean ok, String what) { if (ok) pass++; else { fail++; System.out.println("FAIL: " + what); } }

    public static void main(String[] a) {
        chainMaths();
        greeks();
        intradayFlow();

        Result bull = Engine.run(scenario(+1, true, 11 * 60));
        print("BULL", bull);
        check(bull.regime.equals(Result.BULLISH), "bull regime: " + bull.regime);
        check(bull.directionScore >= 70, "bull score >= 70: " + bull.directionScore);
        check(bull.confidence >= 60, "bull confidence >= 60: " + bull.confidence);
        check(bull.structScore > 30 && bull.liveScore > 30, "both scores bullish");
        check(!Double.isNaN(bull.support) && bull.support < bull.spot, "bull support below spot");
        check(!Double.isNaN(bull.resistance) && bull.resistance > bull.spot, "bull resistance above spot");
        check(bull.factor("optflow").available && bull.factor("optflow").value > 0.3, "live option flow bullish: " + bull.factor("optflow").reading);
        check(bull.factor("futflow").available && bull.factor("futflow").value > 0.3, "live futures flow bullish: " + bull.factor("futflow").reading);
        check(bull.factor("sectors").available && !bull.sectorContrib.isEmpty() && !bull.heavy.isEmpty(), "sector contribution computed");
        check(bull.volRegime.contains("orderly bullish"), "VIX regime: " + bull.volRegime);
        check(!bull.degraded, "fresh data is not degraded");
        check(bull.factor("vix").weight == 0, "VIX does not vote");

        Result bear = Engine.run(scenario(-1, true, 11 * 60));
        print("BEAR", bear);
        check(bear.regime.equals(Result.BEARISH), "bear regime: " + bear.regime);
        check(bear.directionScore <= 30, "bear score <= 30: " + bear.directionScore);
        check(Math.abs(bull.score + bear.score) < 20, "bull and bear roughly mirror: " + bull.score + " / " + bear.score);

        Result range = Engine.run(scenario(0, true, 12 * 60));
        print("RANGE", range);
        check(range.regime.equals(Result.RANGE), "range regime: " + range.regime);

        Result pre = Engine.run(scenario(+1, false, 8 * 60 + 40));
        print("PRE", pre);
        check(pre.stage == 2, "pre-market stage 2");
        check(pre.state.startsWith("Before the open"), "pre-market state");
        check(pre.structWeight > 0.8, "structure dominates pre-market: " + pre.structWeight);
        for (Factor f : pre.factors) if (f.key.equals("vwap") || f.key.equals("opening") || f.key.equals("optflow") || f.key.equals("futflow")) check(!f.available, "live factor off pre-market: " + f.key);

        // CONFLICT: bullish structure, bearish live price
        Snapshot flip = scenario(+1, true, 11 * 60), bearLive = scenario(-1, true, 11 * 60);
        // keep the bullish positioning (quotes, chain, FII, global), replace only the live tape with a falling one
        flip.niftyBars = bearLive.niftyBars; flip.futBars = bearLive.futBars; flip.futNextBars = bearLive.futNextBars; flip.stocks = bearLive.stocks;
        flip.bank = bearLive.bank; flip.fin = bearLive.fin; flip.flow = bearLive.flow; flip.sectors = bearLive.sectors;
        Result fr = Engine.run(flip);
        print("CONFLICT", fr);
        check(fr.regime.equals(Result.CONFLICT), "conflict detected: " + fr.regime + " S=" + fr.structScore + " L=" + fr.liveScore);
        check(!fr.conflictLines.isEmpty() && fr.state.startsWith("Conflict"), "conflict explains each side");
        check(fr.confidence < 50, "conflict halves confidence: " + fr.confidence);

        // Hysteresis rule: enter at ±25, leave at ±15
        check(Engine.pickDirection(20, Result.BULLISH).equals(Result.BULLISH), "+20 stays bullish when already bullish");
        check(Engine.pickDirection(20, "").isEmpty(), "+20 does not enter bullish");
        check(Engine.pickDirection(14, Result.BULLISH).isEmpty(), "+14 leaves bullish");
        check(Engine.pickDirection(-20, Result.BEARISH).equals(Result.BEARISH), "-20 stays bearish");
        check(Engine.pickDirection(26, Result.BEARISH).equals(Result.BULLISH), "+26 flips a bearish view to bullish");

        // Transition: bullish but falling from a peak
        Snapshot tr = scenario(+1, true, 11 * 60);
        tr.history.add(new double[]{10 * 60 + 10, 95, 90, 1, 25200, 60, 90});
        tr.history.add(new double[]{10 * 60 + 40, 90, 85, 1, 25200, 60, 90});
        Result trr = Engine.run(tr);
        tr.prevRegime = Result.BULLISH;
        check(trr.transition.isEmpty() || trr.transition.contains("weakening") || trr.transition.contains("→"), "transition text: " + trr.transition);
        Snapshot tr2 = weak(+1); tr2.prevRegime = Result.BULLISH;
        tr2.history.add(new double[]{10 * 60 + 30, 70, 80, 1, 25200, 60, 70});
        tr2.history.add(new double[]{10 * 60 + 55, 60, 70, 1, 25200, 60, 60});
        Result tr2r = Engine.run(tr2);
        System.out.println("TRANSITION: " + tr2r.regime + " | " + tr2r.transition + " | slope30 " + tr2r.slope30);
        check(tr2r.transition.length() > 0, "a fall from +70 to ~+20 shows a transition");

        // Event risk: RBI today
        Snapshot ev = scenario(+1, true, 11 * 60);
        ev.events.add(new EventItem(ev.today, "RBI policy decision", 3, "test"));
        Result evr = Engine.run(ev);
        check(evr.eventRisk.equals("HIGH"), "event risk HIGH on RBI day: " + evr.eventRisk);
        check(evr.confidence < bull.confidence, "event risk lowers confidence: " + evr.confidence + " < " + bull.confidence);
        check(evr.action.contains("Event risk HIGH"), "action mentions event risk");

        // News tone moves the score a little, never more than 15
        Snapshot nw = scenario(0, true, 12 * 60);
        for (int i = 0; i < 6; i++) { NewsItem n = new NewsItem(); n.id = "n" + i; n.title = "Big negative " + i; n.read = true; n.by = "Gemini";
            n.niftyImpact = -0.9; n.severity = "HIGH"; n.time = nw.time - 30 * 60_000L; n.verification = "CONFIRMED"; n.topic = "t" + i; nw.news.add(n); }
        Result nwr = Engine.run(nw);
        check(nwr.eventAdj < 0 && nwr.eventAdj >= -15, "negative news pulls the score down, capped: " + nwr.eventAdj);
        check(!nwr.eventRisk.equals("LOW"), "fresh HIGH news raises event risk: " + nwr.eventRisk);

        v13Tests(bull);
        staleFlowTests();

        // NO EDGE: weak mixed evidence without range signs
        Result ne = Engine.run(weak(0));
        System.out.println("NO EDGE case: " + ne.label() + " score " + ne.score);
        check(ne.regime.equals(Result.NO_EDGE) || ne.regime.equals(Result.RANGE), "weak evidence → NO EDGE/RANGE: " + ne.regime);

        // Freshness: old global data counts less
        Snapshot st = scenario(+1, true, 11 * 60);
        st.sourceTime.put("Global markets (Yahoo)", st.time - 180 * 60_000L);
        Result str = Engine.run(st);
        check(str.factor("global").freshness < 0.5, "3-hour-old global data fades: " + str.factor("global").freshness);
        // Reliability: typed GIFT counts half
        Snapshot tg = scenario(+1, false, 8 * 60 + 40); tg.giftSource = "typed";
        check(Engine.run(tg).factor("gap").reliability == 0.5, "typed GIFT reliability 0.5");
        // Calibration multiplier applied
        Snapshot cal = scenario(+1, true, 11 * 60); cal.calibration.put("vwap", 1.3);
        Result calr = Engine.run(cal);
        check(Math.abs(calr.factor("vwap").learnt - 1.3) < 1e-9, "learnt weight applied");

        // Empty snapshot: must not crash
        Snapshot empty = new Snapshot(); empty.minute = 10 * 60; empty.live = true; empty.today = "2026-09-29";
        Result e = Engine.run(empty);
        check(e.confidence == 0, "empty snapshot gives zero confidence");
        check(e.regime.equals(Result.NO_EDGE), "empty snapshot is NO EDGE: " + e.regime);

        double sS = 0, sL = 0;
        for (Factor f : bull.factors) { if (f.group.equals(Factor.STRUCT)) sS += f.maxWeight; if (f.group.equals(Factor.LIVE)) sL += f.maxWeight; }
        check(Math.abs(sS - 100) < 1e-9 && Math.abs(sL - 100) < 1e-9, "each group's base weights sum to 100: " + sS + " / " + sL);

        System.out.println("\n" + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    static Factor fac(Result r, String k) { return r.factor(k); }

    static void v13Tests(Result bull) {
        // DATA DEGRADED
        Snapshot dg = scenario(+1, true, 11 * 60);
        dg.sourceTime.remove("Option chain");
        dg.sourceTime.put("Stocks (breadth)", dg.time - 20 * 60_000L);
        Result dgr = Engine.run(dg);
        check(dgr.degraded && dgr.degradedList.size() == 2, "degraded lists missing options and old breadth: " + dgr.degradedList);
        check(dgr.confidence < bull.confidence && dgr.action.startsWith("DATA DEGRADED"), "degraded lowers confidence and says so");
        check(fac(dgr, "breadth").freshness <= 0.3, "20-min-old breadth (expected 2 min) fades: " + fac(dgr, "breadth").freshness);

        // source-specific freshness: a 5-min-old option chain is stale (expected 2), 5-min-old global data is fine (expected 30)
        Snapshot fr = scenario(+1, true, 11 * 60);
        fr.sourceTime.put("Option chain", fr.time - 5 * 60_000L);
        Result frr = Engine.run(fr);
        check(fac(frr, "options").freshness < 0.5 && fac(frr, "global").freshness == 1.0, "per-source freshness: options " + fac(frr, "options").freshness + ", global " + fac(frr, "global").freshness);

        // smooth blend
        double prev = -1; boolean smooth = true;
        for (int m = 9 * 60 + 15; m <= 14 * 60; m++) { double l = Engine.liveShare(true, m); if (l < prev || l - prev > 0.02 && prev >= 0) smooth = false; prev = l; }
        check(smooth, "live share rises smoothly minute by minute");
        check(Math.abs(Engine.liveShare(true, 9 * 60 + 30) - 0.30) < 1e-9 && Math.abs(Engine.liveShare(true, 13 * 60) - 0.75) < 1e-9, "blend anchor points");

        // expiry day
        Snapshot ex = scenario(0, true, 13 * 60);
        ex.expiry = ex.today; ex.optExpiries.add(ex.today);
        Result exr = Engine.run(ex);
        check(exr.dayType.equals("WEEKLY EXPIRY"), "weekly expiry detected: " + exr.dayType);
        check(fac(exr, "options").dayMod == 0.6, "options-since-yesterday counts less on expiry");
        ex.futExpiries.add(ex.today);
        check(Engine.run(ex).dayType.equals("MONTHLY EXPIRY"), "monthly expiry detected");
        Snapshot mp = scenario(0, true, 13 * 60); mp.expiry = mp.today;
        Result mpr = Engine.run(mp);
        check(mpr.maxPainMagnet == (Math.abs(mpr.spot - mpr.maxPain) / mpr.spot * 100 < 0.4), "max pain magnet late on expiry when price is near it: " + mpr.maxPain + " vs " + mpr.spot);

        // gap regimes
        Snapshot gg = scenario(+1, true, 11 * 60);
        gg.nifty.prevClose = 25000; gg.nifty.open = 25200; gg.nifty.last = 25300;   // +0.8% gap, extended further
        Result ggr = Engine.run(gg);
        check(ggr.gapClass.equals("Large gap-up") && ggr.gapBehavior.equals("Gap-and-go"), "gap-and-go: " + ggr.gapClass + " / " + ggr.gapBehavior);
        gg.nifty.last = 24900;   // went through yesterday's close
        Result gr2 = Engine.run(gg);
        check(gr2.gapBehavior.equals("Gap reversal") && fac(gr2, "gap").value < -0.5, "gap reversal is bearish: " + fac(gr2, "gap").value);
        gg.nifty.last = 25005;
        check(Engine.run(gg).gapBehavior.equals("Gap fill"), "gap fill");
        gg.nifty.open = 25050; gg.nifty.last = 25045;
        check(Engine.run(gg).gapClass.equals("Normal gap-up"), "normal gap class");

        // attribution coverage
        Snapshot at = scenario(+1, true, 11 * 60);
        at.nifty.prevClose = at.nifty.last / 1.02;   // Nifty +2% but stocks only ~+0.44%
        Result atr = Engine.run(at);
        check(atr.attrCoverage < 70 && fac(atr, "sectors").reliability < 1, "low attribution coverage lowers sector trust: " + atr.attrCoverage);
        check(!Double.isNaN(bull.attrCoverage), "attribution computed in the normal case: " + bull.attrCoverage);

        // global vs India split
        Snapshot gs = scenario(+1, true, 11 * 60);
        gs.global.put("USD/INR", 0.8); gs.global.put("Brent crude", 3.0); gs.global.put("US 10Y yield", 2.0); gs.global.put("Dollar index", 0.6);
        Result gsr = Engine.run(gs);
        check(gsr.globalRisk > 0.3 && gsr.indiaMacro < -0.3 && fac(gsr, "global").detail.contains("Split"), "risk-on world vs India headwind split: " + gsr.globalRisk + " / " + gsr.indiaMacro);

        // 2-of-3 persistence
        Snapshot ps = scenario(-1, true, 11 * 60);
        ps.prevRegime = Result.BULLISH;
        ps.history.add(new double[]{10 * 60 + 50, 40, 70, 1, 25200, 50, 40, 1});
        ps.history.add(new double[]{10 * 60 + 55, 38, 70, 1, 25200, 50, 40, 1});
        Result psr = Engine.run(ps);
        check(psr.rawRegime.equals(Result.BEARISH), "raw reading is bearish");
        System.out.println("PERSISTENCE strong bear: score " + psr.score + " shown " + psr.regime + " pending " + psr.pending + " | " + psr.transition);
        if (Math.abs(psr.score) < 60) {
            check(psr.regime.equals(Result.BULLISH) && psr.pending.equals(Result.BEARISH), "one bearish reading does not flip a bullish view: " + psr.regime + " pending " + psr.pending);
        } else check(psr.regime.equals(Result.BEARISH), "a very strong score flips at once");
        Snapshot ps2 = scenario(-1, true, 11 * 60);
        ps2.prevRegime = Result.BULLISH;
        ps2.history.add(new double[]{10 * 60 + 50, 40, 70, 1, 25200, 50, 40, 1});
        ps2.history.add(new double[]{10 * 60 + 55, -30, 70, 1, 25200, 50, -40, 2});   // last update already read bearish
        check(Engine.run(ps2).regime.equals(Result.BEARISH), "second bearish reading confirms the flip");
        // bearish live tape with neutral structure: score between -25 and -60, so the 2-of-3 gate applies
        Snapshot ps3 = scenario(-1, true, 11 * 60), z = scenario(0, true, 11 * 60);
        ps3.global = z.global; ps3.fiiCash = z.fiiCash; ps3.diiCash = z.diiCash; ps3.fiiIdxLong = z.fiiIdxLong; ps3.fiiIdxLongPrev = z.fiiIdxLongPrev;
        ps3.chain = z.chain; ps3.fut = z.fut; ps3.futNext = z.futNext; ps3.futPrevOi = z.futPrevOi; ps3.prevDay = z.prevDay;
        ps3.nifty.open = ps3.nifty.prevClose;   // no gap
        ps3.prevRegime = Result.BULLISH;
        ps3.history.add(new double[]{10 * 60 + 50, 30, 60, 1, 25200, 30, 30, 1});
        ps3.history.add(new double[]{10 * 60 + 55, 28, 60, 1, 25200, 30, 30, 1});
        Result p3 = Engine.run(ps3);
        System.out.println("PERSISTENCE mid bear: raw " + p3.rawRegime + " shown " + p3.regime + " pending " + p3.pending + " score " + p3.score + " | " + p3.transition);
        check(Math.abs(p3.score) < 60 && p3.rawRegime.equals(Result.BEARISH), "test setup: mid-strength bearish raw reading: " + p3.score + " " + p3.rawRegime);
        check(p3.regime.equals(Result.BULLISH) && p3.pending.equals(Result.BEARISH) && p3.transition.contains("needs 1 more"), "first bearish reading is held as pending");
        check(p3.action.contains("not confirmed"), "action warns the flip is unconfirmed");

        // progressive futures windows
        Snapshot fw = scenario(+1, true, 9 * 60 + 40);   // 5 bars
        Result fwr = Engine.run(fw);
        check(fac(fwr, "futflow").available && fac(fwr, "futflow").maturity == 0.6 && fac(fwr, "futflow").reading.contains("15 min"), "15-min futures flow from 9:35: " + fac(fwr, "futflow").reading);
        Snapshot fw2 = scenario(+1, true, 9 * 60 + 55);   // 8 bars
        check(Engine.run(fw2).factor("futflow").maturity == 0.85, "30-min window counts 85%");
        check(fac(bull, "futflow").maturity == 1.0, "60-min acceleration window counts fully");

        // VIX percentile regime
        Snapshot vx = scenario(+1, true, 11 * 60);
        for (int i = 0; i < 250; i++) { Candle c = new Candle(); c.date = String.format("2025-%02d-%02d", 1 + i / 28, 1 + i % 28); c.c = 10 + i * 0.05; vx.vixDaily.add(c); }
        vx.vix.last = 22.0;   // above ~95% of history
        Result vxr = Engine.run(vx);
        check(vxr.vixPct > 90 && vxr.volClass.startsWith("Extreme") || vxr.volClass.startsWith("Elevated"), "percentile regime: " + vxr.vixPct + " " + vxr.volClass);
        check(vxr.volMod < 1, "high VIX percentile trims confidence");
        vx.vix.last = 10.2; vx.vix.prevClose = 10.3;
        Result vlo = Engine.run(vx);
        check(vlo.volClass.startsWith("Low"), "low percentile = low volatility: " + vlo.vixPct);

        // news: unconfirmed big headline does not raise event risk; already-priced news counts less
        Snapshot nu = scenario(0, true, 12 * 60);
        NewsItem n = new NewsItem(); n.id = "x"; n.title = "RBI may hike rates"; n.read = true; n.by = "Gemini"; n.niftyImpact = -0.9; n.severity = "HIGH";
        n.time = nu.time - 20 * 60_000L; n.verification = "PROVISIONAL";
        nu.news.add(n);
        Result nur = Engine.run(nu);
        check(nur.eventRisk.equals("LOW") && fac(nur, "events").detail.contains("not independently confirmed"), "single-source HIGH headline ignored for event risk");
        n.verification = "CONFIRMED";
        check(!Engine.run(nu).eventRisk.equals("LOW"), "confirmed HIGH headline raises event risk");
        // already priced: bearish news 2 hours ago, Nifty fell 1% since
        Snapshot ap = scenario(-1, true, 12 * 60);
        NewsItem m = new NewsItem(); m.id = "y"; m.title = "Bad news"; m.read = true; m.by = "Gemini"; m.niftyImpact = -0.8; m.severity = "MEDIUM";
        m.verification = "CONFIRMED"; m.time = ap.time - 12 * 3600_000L;   // before the open
        ap.news.add(m);
        Engine.run(ap);
        check(m.reaction.startsWith("Already priced") || m.reaction.startsWith("Re-accelerating"), "news before a big same-way move is 'already priced': " + m.reaction);
        check(m.reactionWeight < 1, "already-priced news counts less");
    }

    static void staleFlowTests() {
        // only an old snapshot (40 min back) → option flow unavailable, not a fake "15-minute" reading
        Snapshot st = scenario(+1, true, 11 * 60);
        st.flow.get(0).minute = 11 * 60 - 40;
        Result r = Engine.run(st);
        check(!r.factor("optflow").available && r.factor("optflow").detail.contains("40 min old"), "stale reference → option flow unavailable: " + r.factor("optflow").detail);
        // reference picked closest to 15 min within 6–25
        List<FlowPoint> fl = new ArrayList<>();
        for (int m : new int[]{600, 628, 631, 640, 645}) { FlowPoint p = new FlowPoint(); p.minute = m; fl.add(p); }
        check(Engine.pickFlowRef(fl).minute == 631, "picks the snapshot nearest 15 min back (631 for 645): " + Engine.pickFlowRef(fl).minute);
        List<FlowPoint> f2 = new ArrayList<>();
        for (int m : new int[]{600, 640, 643}) { FlowPoint p = new FlowPoint(); p.minute = m; f2.add(p); }
        check(Engine.pickFlowRef(f2) == null, "nothing between 6 and 25 min back → no reference");
        List<FlowPoint> f3 = new ArrayList<>();
        for (int m : new int[]{620, 645}) { FlowPoint p = new FlowPoint(); p.minute = m; f3.add(p); }
        check(Engine.pickFlowRef(f3) != null && Engine.pickFlowRef(f3).minute == 620, "25 min back is still allowed");
        f3.get(0).minute = 619;
        check(Engine.pickFlowRef(f3) == null, "26 min back is too old");
    }

    static void chainMaths() {
        List<OptionRow> rows = new ArrayList<>();
        double[][] d = {{25000, 100, 900}, {25100, 300, 500}, {25200, 600, 200}, {25300, 900, 50}};
        for (double[] x : d) { OptionRow r = new OptionRow(); r.strike = x[0]; r.ceOi = x[1]; r.peOi = x[2]; rows.add(r); }
        double best = 0, bp = 1e18;
        for (double[] k : d) { double p = 0; for (double[] s : d) { p += s[1] * Math.max(0, k[0] - s[0]) + s[2] * Math.max(0, s[0] - k[0]); } if (p < bp) { bp = p; best = k[0]; } }
        check(Chain.maxPain(rows) == best, "max pain");
        check(Math.abs(Chain.pcr(rows) - 1650.0 / 1900.0) < 1e-9, "pcr");
        check(Chain.ceWall(rows, 25150).strike == 25300, "ce wall");
        check(Chain.peWall(rows, 25150).strike == 25000, "pe wall");
        check(Chain.read(10000, -5, 100000) == Chain.Action.WRITING, "writing");
        check(Chain.read(-10000, 5, 100000) == Chain.Action.SHORT_COVERING, "short covering");
        check(Chain.read(10000, 5, 100000) == Chain.Action.BUYING, "buying");
        check(Chain.read(-10000, -5, 100000) == Chain.Action.UNWINDING, "unwinding");
        check(Chain.read(100, -5, 100000) == Chain.Action.NONE, "tiny change ignored");
    }

    static void greeks() {
        double t = 5 / 365.0;
        for (double vol : new double[]{0.10, 0.15, 0.25}) {
            for (double k : new double[]{24800, 25000, 25200}) {
                double pc = Greeks.price(true, 25000, k, t, vol), pp = Greeks.price(false, 25000, k, t, vol);
                double ivc = Greeks.iv(true, 25000, k, t, pc), ivp = Greeks.iv(false, 25000, k, t, pp);
                check(Math.abs(ivc - vol * 100) < 0.05, "IV round trip call " + k + " " + vol + ": " + ivc);
                check(Math.abs(ivp - vol * 100) < 0.05, "IV round trip put " + k + " " + vol + ": " + ivp);
            }
        }
        check(Double.isNaN(Greeks.iv(true, 25000, 24000, t, 900)), "below intrinsic gives NaN");
        double pcp = Greeks.price(true, 25000, 25000, t, 0.15) - Greeks.price(false, 25000, 25000, t, 0.15);
        check(Math.abs(pcp - (25000 - 25000 * Math.exp(-Greeks.RATE * t))) < 1e-6, "put-call parity");
    }

    /** Probabilistic flow: IV change relative to the whole curve, premium residual, volume vs OI. */
    static void intradayFlow() {
        double t = 5 / 365.0;
        List<OptionRow> rows = new ArrayList<>();
        for (int k = -2; k <= 2; k++) { OptionRow r = new OptionRow(); r.strike = 25000 + 50 * k; r.ceSymbol = "C" + k; r.peSymbol = "P" + k; rows.add(r); }
        FlowPoint a = new FlowPoint(), b = new FlowPoint();
        a.minute = 600; b.minute = 615; a.spot = 25000; b.spot = 25040; a.atmIv = 14; b.atmIv = 14;
        for (OptionRow r : rows) for (int side = 0; side < 2; side++) {
            boolean call = side == 0;
            String sym = call ? r.ceSymbol : r.peSymbol;
            double iv0 = 14, iv1 = 14;
            double oi0 = 1e6, oi1 = 1e6 + 2e3;
            if (r.strike == 25000 && call) { oi1 = 1.25e6; iv1 = 13.2; }          // CE: OI up, IV down vs curve → writing
            if (r.strike == 25000 && !call) { oi1 = 0.85e6; iv1 = 14.6; }         // PE: OI down, IV up → short covering
            double p0 = Greeks.price(call, a.spot, r.strike, t + 15.0 / 525600, iv0 / 100), p1 = Greeks.price(call, b.spot, r.strike, t, iv1 / 100);
            a.opt.put(sym, new double[]{oi0, p0, 1e6, iv0});
            b.opt.put(sym, new double[]{oi1, p1, 1.5e6, iv1});
        }
        Chain.FlowRules rules = new Chain.FlowRules();
        Chain.Flow f = Chain.intraday(a, b, rows, 25040, 50, t, rules);
        Chain.Activity ce = null, pe = null;
        for (Chain.Activity x : f.acts) if (x.strike == 25000) { if (x.call) ce = x; else pe = x; }
        check(ce != null && ce.action == Chain.Action.WRITING && ce.byIv, "CE OI up + IV down (premium rose with spot) = probable writing");
        check(ce != null && ce.confidence > 0.6 && ce.label().startsWith("Probable writing ("), "writing has a confidence label: " + (ce == null ? null : ce.label()));
        check(pe != null && pe.action == Chain.Action.SHORT_COVERING, "PE OI down + IV up = probable short covering");
        check(f.net < 0, "call writing + put covering is bearish");
        check(!Double.isNaN(f.intensity) && f.intensity < 0, "intensity is volume-based and negative: " + f.intensity);
        // whole curve falls by 1 point: a strike whose IV fell by 1 is NOT writing any more (surface adjusted)
        FlowPoint c = new FlowPoint(); c.minute = 615; c.spot = 25040; c.atmIv = 13;
        for (Map.Entry<String, double[]> e : b.opt.entrySet()) { double[] v = e.getValue().clone(); c.opt.put(e.getKey(), v); }
        for (OptionRow r : rows) for (String sym : new String[]{r.ceSymbol, r.peSymbol}) { double[] v = c.opt.get(sym); v[3] = 13; v[0] = 1e6 + 2e3; }
        c.opt.get("C0")[0] = 1.25e6;   // OI up with IV falling exactly like the curve
        Chain.Flow g = Chain.intraday(a, c, rows, 25040, 50, t, rules);
        Chain.Activity cx = null; for (Chain.Activity x : g.acts) if (x.strike == 25000 && x.call) cx = x;
        check(cx == null || !cx.byIv || cx.confidence < ce.confidence, "market-wide IV drop is taken out before judging writing");
        // churn: huge volume, small OI change → lower confidence than clean building
        FlowPoint h = new FlowPoint(); h.minute = 615; h.spot = 25040; h.atmIv = 14;
        for (Map.Entry<String, double[]> e : b.opt.entrySet()) h.opt.put(e.getKey(), e.getValue().clone());
        h.opt.get("C0")[2] = 1e6 + 5e6;   // 5M traded for +250k OI
        Chain.Flow hf = Chain.intraday(a, h, rows, 25040, 50, t, rules);
        Chain.Activity hc = null; for (Chain.Activity x : hf.acts) if (x.strike == 25000 && x.call) hc = x;
        check(hc != null && hc.confidence < ce.confidence, "churn (volume >> OI change) lowers confidence: " + (hc == null ? null : hc.confidence) + " < " + ce.confidence);
        // expiry rules drop small OI changes
        Chain.FlowRules ex = new Chain.FlowRules(); ex.minDoi = 3000;
        check(Chain.intraday(a, b, rows, 25040, 50, t, ex).acts.size() <= f.acts.size(), "expiry rules ignore small OI changes");
    }

    /** A snapshot where everything leans the given way but weakly. */
    static Snapshot weak(int dir) {
        Snapshot s = scenario(dir, true, 11 * 60);
        Snapshot z = scenario(0, true, 11 * 60);
        // mostly neutral live data, mildly directional structure
        s.niftyBars = z.niftyBars; s.futBars = z.futBars; s.futNextBars = z.futNextBars; s.stocks = z.stocks; s.flow = z.flow;
        s.bank = z.bank; s.fin = z.fin; s.vix = new Quote("VIX", 14, 14, 0, 0, 14);
        s.nifty = z.nifty; s.chain = z.chain; s.sectors = z.sectors;
        s.fut = z.fut; s.futNext = z.futNext; s.futPrevOi = z.futPrevOi;
        return s;
    }

    /** dir +1 bullish, -1 bearish, 0 range. */
    static Snapshot scenario(int dir, boolean live, int minute) {
        Snapshot s = new Snapshot();
        s.today = "2026-09-29"; s.minute = minute; s.live = live; s.weekday = true; s.sessionDate = live ? s.today : "2026-09-28";
        s.time = 1790000000000L;
        s.sourceTime.put("Kite prices", s.time);
        for (String k : new String[]{"Nifty spot", "Futures quote", "Option chain", "Stocks (breadth)", "Bank Nifty"}) s.sourceTime.put(k, s.time - 20_000L);
        s.sourceTime.put("Futures 5-min candles + OI", s.time - 3 * 60_000L);
        s.sourceTime.put("Global markets (Yahoo)", s.time - 5 * 60_000L);
        double base = 25000, mv = dir * 0.8;
        double spot = base * (1 + mv / 100);
        s.nifty = new Quote("NIFTY 50", spot, base * (1 + dir * 0.3 / 100), Math.max(spot, base) + 20, Math.min(spot, base) - 20, base);
        if (dir == 0) { s.nifty.last = base + 10; s.nifty.high = base + 60; s.nifty.low = base - 60; s.nifty.open = base; }
        spot = s.nifty.last;
        s.bank = new Quote("BANK", 55000 * (1 + dir * 1.0 / 100), 55000, 0, 0, 55000);
        s.fin = new Quote("FIN", 26000 * (1 + dir * 0.9 / 100), 26000, 0, 0, 26000);
        s.vix = new Quote("VIX", dir == 0 ? 11.5 : 13 * (1 - dir * 0.06), 13, 0, 0, dir == 0 ? 11.6 : 13);
        double basis = spot * 0.065 * 20 / 365 + dir * 15;
        s.fut = new Quote("NIFTY FUT", spot + basis, 0, 0, 0, base + base * 0.065 * 20 / 365); s.fut.oi = 1.10e7 + dir * 6e5;
        s.futNext = new Quote("NIFTY FUT2", spot + basis + 100, 0, 0, 0, base + 120); s.futNext.oi = 2e6;
        s.futPrevOi = 1.30e7 + (dir == 0 ? 0 : 0); s.futDaysToExpiry = 20;
        if (dir > 0) s.futPrevOi = 1.20e7;
        if (dir == 0) { s.fut.oi = 1.10e7; s.futPrevOi = 1.30e7; s.fut.prevClose = s.fut.last; }
        String[] sec = {"Financials", "Energy", "IT", "Auto", "FMCG", "Pharma", "Metal", "Infra", "Realty", "PSU Bank"};
        for (int i = 0; i < sec.length; i++) {
            double p = dir == 0 ? (i % 2 == 0 ? 0.2 : -0.2) : dir * (0.4 + 0.1 * i) * (i == 5 ? -0.5 : 1);
            s.sectors.put(sec[i], new Quote(sec[i], 1000 * (1 + p / 100), 0, 0, 0, 1000));
        }
        String[] stockSec = {"Financials", "IT", "Energy", "Auto", "FMCG"};
        for (int i = 0; i < 50; i++) {
            double p = dir == 0 ? (i % 2 == 0 ? 0.3 : -0.3) : dir * (i < 38 ? 0.7 : -0.4);
            Quote q = new Quote("S" + i, 100 * (1 + p / 100), 100, 0, 0, 100);
            q.volume = 1e6; q.avgPrice = 100 * (1 + p / 200);
            s.stocks.add(q);
            s.weights.put("S" + i, 1 / 50.0);
            s.sectorOf.put("S" + i, stockSec[i % 5]);
            s.dma20.put("S" + i, dir >= 0 ? 99.0 : 101.0);
        }
        s.weightsApprox = false;
        int bars = Math.max(0, (Math.min(minute, 15 * 60 + 30) - (9 * 60 + 15)) / 5);
        if (live) for (int i = 0; i < bars; i++) {
            double frac = (i + 1.0) / bars;
            double c = dir == 0 ? base + 40 * Math.sin(i * 1.3) : base * (1 + (dir * 0.3 + (mv - dir * 0.3) * frac) / 100);
            Candle k = new Candle(0, c - dir * 5, c + 8, c - 8, c, 1000);
            k.minute = 9 * 60 + 15 + 5 * i;
            if (dir != 0 && i > 0) { Candle p = s.niftyBars.get(i - 1); k.h = Math.max(k.h, dir > 0 ? p.h + 3 : p.h - 3); k.l = dir > 0 ? p.l + 3 : Math.min(k.l, p.l - 3); if (dir < 0) k.h = p.h - 3; }
            s.niftyBars.add(k);
            double bb = basis + dir * i * 0.4;   // basis widens in the trend's direction
            Candle fk = new Candle(0, k.o + bb, k.h + bb, k.l + bb, k.c + bb, 5000 + 100 * i);
            fk.minute = k.minute;
            fk.oi = dir == 0 ? 1.1e7 + (i % 2) * 2e4 : 1.0e7 * (1 + 0.004 * i * Math.abs(dir));   // OI rises with the trend
            s.futBars.add(fk);
            Candle nx = new Candle(0, 0, 0, 0, 0, 0); nx.minute = k.minute; nx.oi = 2e6;
            s.futNextBars.add(nx);
        }
        s.prevDay = new Candle(0, base - 50 * dir, base + 80, base - 80, base + 60 * dir, 0);
        if (dir == 0) s.prevDay.c = base;
        s.strikeStep = 50; s.expiry = "2026-10-06";
        double atm = Math.round(spot / 50) * 50;
        FlowPoint f0 = new FlowPoint(), f1 = new FlowPoint();
        f0.minute = minute - 15; f1.minute = minute; f0.spot = f1.spot = spot;
        for (int k = -10; k <= 10; k++) {
            OptionRow r = new OptionRow(); r.strike = atm + 50 * k;
            r.ceSymbol = "C" + (int) r.strike; r.peSymbol = "P" + (int) r.strike;
            double dist = Math.abs(k);
            r.ceOi = 3e6 / (1 + dist * 0.3) * (k > 0 ? 1.4 : 0.7);
            r.peOi = 3e6 / (1 + dist * 0.3) * (k < 0 ? 1.4 : 0.7);
            if (dir != 0) { if (dir > 0) r.peOi *= 1.3; else r.ceOi *= 1.3; }
            if (dir == 0) { if (k == 1) r.ceOi *= 3; if (k == -1) r.peOi *= 3; }
            r.cePrevOi = r.ceOi * (dir > 0 ? 1.1 : dir < 0 ? 0.8 : 1.0);
            r.pePrevOi = r.peOi * (dir > 0 ? 0.8 : dir < 0 ? 1.1 : 1.0);
            r.ceLtp = 100; r.cePrevLtp = dir > 0 ? 80 : 120;
            r.peLtp = 100; r.pePrevLtp = dir > 0 ? 120 : 80;
            if (dir == 0) { r.cePrevLtp = 100; r.pePrevLtp = 100; }
            r.ceIv = 13; r.peIv = 13;
            s.chain.add(r);
            // intraday: bull = put writing (PE OI up, IV down) + call covering (CE OI down, IV up); bear mirrored; range = flat
            double ceD = dir > 0 ? -0.06 : dir < 0 ? 0.08 : 0, peD = dir > 0 ? 0.08 : dir < 0 ? -0.06 : 0;
            double ceIv = dir > 0 ? 0.4 : dir < 0 ? -0.4 : 0, peIv = -ceIv;
            f0.opt.put(r.ceSymbol, new double[]{r.ceOi / (1 + ceD), 100, 1e6, 13 - ceIv});
            f1.opt.put(r.ceSymbol, new double[]{r.ceOi, 100, 1.4e6, 13});
            f0.opt.put(r.peSymbol, new double[]{r.peOi / (1 + peD), 100, 1e6, 13 - peIv});
            f1.opt.put(r.peSymbol, new double[]{r.peOi, 100, 1.4e6, 13});
        }
        if (live) { s.flow.add(f0); s.flow.add(f1); }
        s.global.put("S&P 500", dir * 0.8); s.global.put("Nasdaq", dir * 1.0); s.global.put("US futures", dir * 0.3);
        s.global.put("Nikkei", dir * 0.7); s.global.put("Hang Seng", dir * 0.5); s.global.put("Kospi", dir * 0.6);
        s.fiiCash = dir * 2500; s.diiCash = -dir * 1000; s.fiiIdxLong = 50 + dir * 15; s.fiiIdxLongPrev = 50 + dir * 12;
        if (dir == 0) { s.fiiCash = 0; s.diiCash = 0; }
        if (!live) { s.giftNifty = s.fut.last * (1 + dir * 0.4 / 100); s.giftSource = "Kite"; }
        return s;
    }

    static void print(String tag, Result r) {
        System.out.printf("%n== %s: %s  dir %d  conf %d  S %.0f  L %.0f  cov %.2f  agree %.2f | %s | %s | vol: %s%n", tag, r.label(), r.directionScore, r.confidence,
                r.structScore, r.liveScore, r.coverage, r.agreement, r.state, r.transition, r.volRegime);
        for (Factor f : r.factors) System.out.printf("  %s %-38s %6s w%5.1f  %-24s %s%n", f.group, f.name, f.available ? String.format("%+.2f", f.value) : "  --", f.weight, f.reading,
                f.detail.length() > 150 ? f.detail.substring(0, 150) + "…" : f.detail);
        System.out.println("  ACTION: " + r.action);
        for (String w : r.warnings) System.out.println("  WARN: " + w);
    }
}

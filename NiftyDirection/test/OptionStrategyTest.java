import com.krish.niftydirection.intel.*;
import com.krish.niftydirection.model.OptionRow;
import java.util.*;

/** Option strategy builder: chain readout, fair premium, ideas, risk numbers, signals, exit checks, storage. */
public class OptionStrategyTest {
    static int pass = 0, fail = 0;
    static void check(String name, boolean ok) { if (ok) pass++; else { fail++; System.out.println("FAIL " + name); } }

    /** Flat-IV chain priced by Black-Scholes, tight quotes. */
    static OptionStrategy.Chain chain(double spot, double iv, int days) {
        OptionStrategy.Chain c = new OptionStrategy.Chain();
        c.spot = spot; c.days = days; c.years = (days + 0.25) / 365.0; c.lot = 65; c.expiry = "2026-10-06";
        double atm = Math.round(spot / 50) * 50;
        for (int i = -15; i <= 15; i++) {
            OptionRow r = new OptionRow();
            r.strike = atm + i * 50;
            r.ceLtp = OptionStrategy.bs(true, spot, r.strike, c.years, iv);
            r.peLtp = OptionStrategy.bs(false, spot, r.strike, c.years, iv);
            r.ceIv = iv * 100; r.peIv = iv * 100;
            r.ceBid = r.ceLtp - 0.25; r.ceAsk = r.ceLtp + 0.25; r.peBid = r.peLtp - 0.25; r.peAsk = r.peLtp + 0.25;
            r.ceOi = 1000 + (i > 3 ? 5000 : 0) * (i == 6 ? 2 : 1); r.peOi = 1000 + (i < -3 ? 5000 : 0) * (i == -5 ? 2 : 1);
            c.rows.add(r);
        }
        return c;
    }

    static OptionStrategy.View view(double pUp, double sigma, boolean act, String validation) {
        OptionStrategy.View v = new OptionStrategy.View();
        v.horizon = "1D"; v.horizonLabel = "1 day"; v.pUp = pUp; v.sigma = sigma; v.act = act; v.validation = validation;
        v.years = 1.0 / 365; v.exitBy = System.currentTimeMillis() + 86_400_000L;
        return v;
    }

    static OptionStrategy.Idea find(List<OptionStrategy.Idea> l, String prefix) {
        for (OptionStrategy.Idea i : l) if (i.name.startsWith(prefix)) return i;
        return null;
    }

    public static void main(String[] a) throws Exception {
        OptionStrategy.Chain c = chain(25010, 0.13, 4);
        // ---- readout
        double FAIRRV = 0.13 / OptionStrategy.CAL;   // trading-day vol equal to 13% calendar IV
        OptionStrategy.Market m = OptionStrategy.read(c, FAIRRV);
        check("ATM strike rounds to the step", m.atm == 25000);
        check("ATM IV read back from the chain", Math.abs(m.atmIv - 0.13) < 1e-9);
        double mv = 25010 * 0.13 * Math.sqrt(c.years);
        check("move priced in to expiry = spot × IV × √T", Math.abs(m.moveExpiry - mv) < 1e-6);
        check("IV equal to real moves = fair", "fair".equals(m.vol));
        check("IV well above real moves = expensive", "expensive".equals(OptionStrategy.read(c, 0.10).vol));
        check("IV below real moves = cheap", "cheap".equals(OptionStrategy.read(c, FAIRRV / 0.9).vol));
        check("walls are the biggest OI above / below spot", m.callWall == 25300 && m.putWall == 24750);
        check("PCR from OI", Math.abs(m.pcr - 1.0) < 0.2);

        // ---- fair premium
        List<OptionStrategy.Fair> fair = OptionStrategy.fair(c, m, 0.13, 3);
        boolean allFair = fair.size() == 7;
        for (OptionStrategy.Fair f : fair) allFair &= "fair".equals(f.ceVerdict) && "fair".equals(f.peVerdict);
        check("same vol → every strike fair", allFair);
        boolean allExp = true;
        for (OptionStrategy.Fair f : OptionStrategy.fair(c, m, 0.09, 3)) allExp &= "expensive".equals(f.ceVerdict);
        check("much lower fair vol → premiums expensive", allExp);
        check("fair vol blends real moves and AI range", Math.abs(OptionStrategy.fairVol(0.12, 0.14 / Math.sqrt(252)) - 0.13 * OptionStrategy.CAL) < 1e-9);

        OptionStrategy.Config cfg = new OptionStrategy.Config();
        cfg.riskBudget = 25000;
        // ---- coin-flip view: costs → no BUY anywhere
        List<OptionStrategy.Idea> flat = OptionStrategy.build(c, m, view(0.5, 0.13 * Math.sqrt(1 / 365.0), true, "PASS"), cfg);   // view agrees with the option price
        boolean noBuy = !flat.isEmpty();
        for (OptionStrategy.Idea i : flat) noBuy &= !"BUY".equals(i.signal);
        check("50/50 view: nothing is a BUY (costs)", noBuy);
        for (OptionStrategy.Idea i : flat) check("never unlimited risk: " + i.name, !i.unlimited);

        // ---- strong bullish view
        List<OptionStrategy.Idea> bull = OptionStrategy.build(c, m, view(0.78, 0.009, true, "PASS"), cfg);
        OptionStrategy.Idea ce = find(bull, "Buy 25000 CE"), sp = find(bull, "Bull call spread"), cr = find(bull, "Bull put spread"), ic = find(bull, "Iron condor");
        check("bullish ideas are calls / bull spreads", ce != null && sp != null && cr != null && find(bull, "Buy 25000 PE") == null);
        check("long call: positive expected value → BUY", ce != null && ce.ev > 0 && "BUY".equals(ce.signal));
        check("BUY ideas listed first", "BUY".equals(bull.get(0).signal));
        double lot = 65;
        check("long call max loss = premium + costs", Math.abs(ce.maxLoss - (ce.net * lot + ce.costs)) < 1e-6);
        check("long call breakeven = strike + premium", Math.abs(ce.breakLo - (25000 + ce.net)) < 0.5);
        double w = sp.legs.get(1).strike - sp.legs.get(0).strike;
        check("debit spread: width ≥ 2 strikes, max profit = (width − debit)×lot − costs", w >= 100 && Math.abs(sp.maxProfit - ((w - sp.net) * lot - sp.costs)) < 1e-6);
        check("credit spread: receives premium, loss capped", cr.net < 0 && Math.abs(cr.maxLoss - ((w + cr.net) * lot + cr.costs)) < 1.0);
        check("iron condor: 4 legs, short strikes outside the priced-in move", ic != null && ic.legs.size() == 4 && ic.legs.get(2).strike - 25000 >= 300);
        check("chance of profit in 0..1", ce.pop > 0 && ce.pop < 1);
        check("lots fit the risk budget", ce.lots == Math.min(cfg.maxLots, (int) Math.floor(cfg.riskBudget / ce.maxLoss)));
        check("exit plan: stop below, target above spot", ce.exitBelow < c.spot && ce.exitAbove > c.spot && ce.stopLoss < 0 && ce.takeProfit > 0);
        check("costs ≈ ₹60–300 a leg", ce.costs > 60 && ce.costs < 300);

        // ---- gates
        OptionStrategy.Idea paper = find(OptionStrategy.build(c, m, view(0.78, 0.009, false, "FAIL"), cfg), "Buy 25000 CE");
        check("not validated → PAPER, not BUY", paper != null && "PAPER".equals(paper.signal));
        OptionStrategy.View guarded = view(0.78, 0.009, true, "PASS");
        guarded.blocks.add("daily loss limit reached");
        check("risk guard → WAIT", "WAIT".equals(find(OptionStrategy.build(c, m, guarded, cfg), "Buy 25000 CE").signal));
        OptionStrategy.Market exp = OptionStrategy.read(c, 0.08);
        check("expensive options → buying a call is AVOID", "AVOID".equals(find(OptionStrategy.build(c, exp, view(0.78, 0.009, true, "PASS"), cfg), "Buy 25000 CE").signal));
        check("long straddle only when cheap", "AVOID".equals(find(bull, "Long straddle").signal));
        OptionStrategy.Config tight = new OptionStrategy.Config();
        tight.riskBudget = 3000;   // the default daily limit: one ATM lot (~₹10k premium) does not fit
        check("risk limit below one lot → WAIT", "WAIT".equals(find(OptionStrategy.build(c, m, view(0.78, 0.009, true, "PASS"), tight), "Buy 25000 CE").signal));
        OptionStrategy.Chain wide = chain(25010, 0.13, 4);
        for (OptionRow r : wide.rows) { r.ceBid = r.ceLtp * 0.8; r.ceAsk = r.ceLtp * 1.2; }
        check("wide bid/offer → AVOID", "AVOID".equals(find(OptionStrategy.build(wide, OptionStrategy.read(wide, FAIRRV), view(0.78, 0.009, true, "PASS"), cfg), "Buy 25000 CE").signal));
        List<OptionStrategy.Idea> bear = OptionStrategy.build(c, m, view(0.2, 0.009, true, "PASS"), cfg);
        check("bearish view builds puts", find(bear, "Buy 25000 PE") != null && find(bear, "Bear put spread") != null && find(bear, "Bear call spread") != null);

        // ---- positions and exit signals
        long now = System.currentTimeMillis();
        OptionStrategy.Position p = OptionStrategy.open(ce, c, "1D", 2, true, now);
        OptionStrategy.Status hold = OptionStrategy.check(p, c, 0.7, now, cfg);
        check("just opened: HOLD, small loss = costs", "HOLD".equals(hold.signal) && hold.pnl < 0 && hold.pnl > -2 * 400);
        OptionStrategy.Chain up = chain(25010 * 1.012, 0.13, 4);
        OptionStrategy.Status st = OptionStrategy.check(p, up, 0.7, now, cfg);
        check("Nifty through the target → EXIT with profit", "EXIT".equals(st.signal) && st.pnl > 0);
        OptionStrategy.Chain dn = chain(25010 * 0.99, 0.13, 4);
        OptionStrategy.Status sd = OptionStrategy.check(p, dn, 0.7, now, cfg);
        check("Nifty through the stop → EXIT with loss", "EXIT".equals(sd.signal) && sd.pnl < 0);
        check("time up → EXIT", "EXIT".equals(OptionStrategy.check(p, c, 0.7, p.exitBy + 1, cfg).signal));
        check("AI flips → EXIT", "EXIT".equals(OptionStrategy.check(p, c, 0.3, now, cfg).signal));
        OptionStrategy.close(p, st, now);
        List<OptionStrategy.Position> back = OptionStrategy.fromJson(new org.json.JSONArray(OptionStrategy.toJson(Collections.singletonList(p)).toString()));
        check("positions survive storage", back.size() == 1 && back.get(0).closed && back.get(0).legs.size() == 1 && Math.abs(back.get(0).exitPnl - st.pnl) < 1e-6
                && back.get(0).lots == 2 && Math.abs(back.get(0).exitBelow - p.exitBelow) < 1e-9);

        // ---- helpers
        List<Double> closes = new ArrayList<>();
        double x = 25000;
        for (int i = 0; i < 12; i++) { closes.add(x); x *= i % 2 == 0 ? 1.01 : 1 / 1.01; }
        check("realised vol of ±1% days ≈ 16%", Math.abs(OptionStrategy.realised(closes, 10) - 0.01 * Math.sqrt(252) * Math.sqrt(10.0 / 9)) < 0.005);
        Calendar fri = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"));
        fri.set(2026, Calendar.OCTOBER, 2, 11, 0, 0);
        Calendar e = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"));
        e.setTimeInMillis(OptionStrategy.exitBy(fri.getTimeInMillis(), 0, 1));
        check("1 session after Friday = Monday 15:20", e.get(Calendar.DAY_OF_WEEK) == Calendar.MONDAY && e.get(Calendar.HOUR_OF_DAY) == 15 && e.get(Calendar.MINUTE) == 20);
        e.setTimeInMillis(OptionStrategy.exitBy(fri.getTimeInMillis(), 600, 0));
        check("intraday exit capped at 15:20", e.get(Calendar.HOUR_OF_DAY) == 15 && e.get(Calendar.MINUTE) == 20);
        double ch = OptionStrategy.charges(100, 100, 65);
        check("option charges for a ₹100 premium lot ≈ ₹60–70", ch > 55 && ch < 75);

        System.out.printf("long call: net %.1f  EV ₹%.0f  PoP %.0f%%  costs ₹%.0f  lots %d  %s%n", ce.net, ce.ev, ce.pop * 100, ce.costs, ce.lots, ce.exitText);
        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}

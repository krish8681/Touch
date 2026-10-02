import com.krish.niftydirection.forecast.*;
import com.krish.niftydirection.intel.*;
import java.util.*;

/** Pre-live validation: replay, verdicts, trade simulation, costs, leakage audit. */
public class ValidatorTest {
    static int pass = 0, fail = 0;
    static void check(String name, boolean ok) { if (ok) pass++; else { fail++; System.out.println("FAIL " + name); } }

    public static void main(String[] a) throws Exception {
        long t0 = System.currentTimeMillis();
        // ---- costs: one lot round trip at 25,000 → brokerage 40 + STT 325 + … ≈ ₹450
        double ch = Validator.charges(25000, 25000, 1, 65);
        System.out.printf("charges per round trip: ₹%.0f%n", ch);
        check("charges in a sane range", ch > 350 && ch < 550);

        // ---- trade simulation: stop is hit before the exit
        History h = IntelTest.synth(700, 1.0, 11);
        int[] sidx = h.sessionIndex();
        Validator.Config cfg = new Validator.Config();
        cfg.sessions = 250; cfg.blocks = 4;   // short replay keeps the test quick
        History.Day day = h.days.get(400);
        Validator.Trade tNo = Validator.simulate(h, sidx, 400, 10, Horizon.of("1h"), 1, 0, cfg);
        check("trade enters at the next bar close + slippage and exits at the horizon close − slippage",
                tNo != null && Math.abs(tNo.entry - (day.c[10] + 1)) < 1e-6 && Math.abs(tNo.exit - (day.c[10 + 12 - 1] - 1)) < 1e-3 && !tNo.stopped);
        Validator.Trade tStop = Validator.simulate(h, sidx, 400, 10, Horizon.of("1h"), 1, 0.01, cfg);
        check("a tiny stop is hit", tStop != null && tStop.stopped);
        check("P&L = points × lot − charges", tNo != null && Math.abs(tNo.rupees - (tNo.pts * 65 - tNo.charges)) < 1e-6);

        // ---- replay on a market with a real (planted) pattern
        Validator.Report r = Validator.run(h, cfg, (w, d, n) -> {});
        System.out.println("planted: " + r.verdict + " — " + r.summary + " (" + (System.currentTimeMillis() - t0) / 1000 + "s)");
        for (Validator.HReport x : r.horizons)
            System.out.printf("  %-4s %-4s right %.3f vs %.3f  cal %.3f  trades %d  net ₹%,.0f  PF %.2f  %s%n", x.id, x.verdict, x.hit, x.base, x.calErr, x.trades, x.netRupees, x.profitFactor, x.reasons);
        check("replay covers 250 sessions in 4 frozen blocks", r.sessions == 250 && r.blocks == 4);
        check("leakage audit clean", r.auditChecked >= 30 && r.auditFailed == 0 && r.purgeOk);
        check("planted pattern: at least one horizon PASSES", "PASS".equals(r.verdict) && !r.ready.isEmpty());
        Validator.HReport h1 = r.get("1h");
        check("1h replay has buckets, regimes and stress tables", h1 != null && !h1.buckets.isEmpty() && h1.regimes.containsKey("Sideways") || h1.regimes.containsKey("Trend up"));
        check("2h and Day close are replayed", r.get("2h").n > 0 && r.get("EOD").n > 0);
        Validator.Report back = Validator.fromJson(new org.json.JSONObject(Validator.toJson(r).toString()));
        check("report survives JSON", back.verdict.equals(r.verdict) && back.horizons.size() == r.horizons.size() && back.get("1h").trades == h1.trades);

        // ---- pure noise: nothing may pass
        Validator.Report rn = Validator.run(IntelTest.synth(700, 0.0, 5), cfg, (w, d, n) -> {});
        System.out.println("noise: " + rn.verdict + " — " + rn.summary);
        for (Validator.HReport x : rn.horizons) System.out.printf("  %-4s %-4s right %.3f vs %.3f trades %d net ₹%,.0f%n", x.id, x.verdict, x.hit, x.base, x.trades, x.netRupees);
        check("pure noise: no horizon passes", !"PASS".equals(rn.verdict));

        // ---- governor: live gate respects the verdicts
        History live = ForecastTest.truncated(h, h.days.size() - 1, 40);
        Trainer.Dataset ds = Trainer.dataset(h);
        List<HorizonModel> ms = Collections.singletonList(Trainer.train(h, ds, Horizon.of("1h")));
        Map<String, String[]> gov = new HashMap<>();
        gov.put("1h", new String[]{"FAIL", "no edge"});
        IntelEngine.Forecast f = IntelEngine.forecast(ms, live, h.days.size() - 1, 40, false, null, null, null, 0.5, gov);
        IntelEngine.HPred p = f.preds.get(2);
        check("governor: FAIL horizon never acts", !p.tradeable && String.join(";", p.gate).contains("failed pre-live validation"));
        IntelEngine.Forecast f2 = IntelEngine.forecast(ms, live, h.days.size() - 1, 40, false, null, null, null, 0.5, new HashMap<>());
        check("governor: not validated yet never acts", !f2.preds.get(2).tradeable && String.join(";", f2.preds.get(2).gate).contains("not validated"));

        // ---- stricter PASS: every PASS horizon shows its edge in both halves of the replay
        boolean halves = true;
        for (Validator.HReport x : r.horizons) if ("PASS".equals(x.verdict) && !(x.skill1 > 0 && x.skill2 > 0)) halves = false;
        check("PASS needs the edge in both halves", halves && !Double.isNaN(h1.skill1));

        // ---- journal + risk guard
        List<Journal.Entry> j = new ArrayList<>();
        Journal.Guard g = new Journal.Guard();
        long now = 1_800_000_000_000L;
        check("guard: first 15 minutes blocked", !Journal.blocks(j, "2026-10-05", 9 * 60 + 20, now, g).isEmpty());
        check("guard: last 30 minutes blocked", !Journal.blocks(j, "2026-10-05", 15 * 60 + 5, now, g).isEmpty());
        check("guard: mid-morning allowed", Journal.blocks(j, "2026-10-05", 11 * 60, now, g).isEmpty());
        Journal.Entry loss = new Journal.Entry();
        loss.date = "2026-10-05"; loss.dir = 1; loss.entry = 25000; loss.exit = 24950; loss.lots = 1; loss.lot = 65; loss.exitAt = now - 10 * 60_000L;
        j.add(loss);
        check("journal P&L = points × lot − charges", Math.abs(loss.pnl() - (-50 * 65 - Validator.charges(25000, 24950, 1, 65))) < 1e-6);
        List<String> b = Journal.blocks(j, "2026-10-05", 11 * 60, now, g);
        check("guard: daily loss limit + cool-down after a loss", b.size() == 2 && b.get(0).contains("loss limit") && b.get(1).contains("cool-down"));
        check("guard: a new day starts clean", Journal.blocks(j, "2026-10-06", 11 * 60, now, g).isEmpty());
        g.on = false;
        check("guard can be switched off", Journal.blocks(j, "2026-10-05", 9 * 60 + 16, now, g).isEmpty());
        List<Journal.Entry> back2 = Journal.fromJson(new org.json.JSONArray(Journal.toJson(j).toString()));
        check("journal survives JSON", back2.size() == 1 && Math.abs(back2.get(0).pnl() - loss.pnl()) < 1e-9);

        System.out.println(pass + " passed, " + fail + " failed (" + (System.currentTimeMillis() - t0) / 1000 + "s)");
        if (fail > 0) System.exit(1);
    }
}

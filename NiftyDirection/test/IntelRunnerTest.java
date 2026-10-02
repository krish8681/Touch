import com.krish.niftydirection.data.*;
import com.krish.niftydirection.intel.*;
import com.krish.niftydirection.model.*;
import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
/** End-to-end against the mock Kite + Yahoo server: download history, train 7 horizons, live forecast, log, what changed, pre-open. */
public class IntelRunnerTest {
    public static void main(String[] a) throws Exception {
        Kite.ROOT = "http://127.0.0.1:" + a[0];
        HistoryLoader.YAHOO = new String[]{"http://127.0.0.1:" + a[0], "http://127.0.0.1:" + a[0]};
        com.krish.niftydirection.data.Nse.ARCHIVES = "http://127.0.0.1:" + a[0];   // NSE positioning from the mock
        ForecastRunner.YEARS_DAYS = 3 * 365 + 10;   // shorter history keeps the test quick
        ForecastRunner.POI_SESSIONS = 120;
        File dir = new File(a[2]); dir.mkdirs();
        Kite k = new Kite("KEY", "TOKEN");
        long t0 = System.currentTimeMillis();
        String rep = IntelRunner.train(k, dir, a[1], new AtomicBoolean(), (w, d, n) -> {});
        System.out.println(rep);
        com.krish.niftydirection.forecast.History hh = ForecastRunner.history(k, dir, a[1], new AtomicBoolean(), (w, d, n) -> {});
        boolean data = hh.global.containsKey(FeatureEngine.POI_FII_FUT) && hh.global.get(FeatureEngine.POI_FII_FUT).size() > 50
                && hh.days.get(hh.days.size() - 1).aux.containsKey("STK:HDFCBANK");
        double[] fv = FeatureEngine.compute(hh, hh.days.size() - 1, 30, hh.sessionIndex());
        data &= !Double.isNaN(fv[82]) && !Double.isNaN(fv[78]) && !Double.isNaN(fv[74]) && FeatureEngine.NAMES.length == 88 && FeatureEngine.HIGH.length == 88;
        System.out.println("new data: positioning " + (hh.global.containsKey(FeatureEngine.POI_FII_FUT) ? hh.global.get(FeatureEngine.POI_FII_FUT).size() : 0)
                + " sessions, leaders " + hh.days.get(hh.days.size() - 1).aux.containsKey("STK:HDFCBANK") + ", inputs " + FeatureEngine.N + " → " + (data ? "ok" : "MISSING"));
        boolean ok = data && IntelRunner.models(dir).size() == 9 && !IntelRunner.needsTraining(dir) && !IntelRunner.graph(dir).isEmpty();
        Validator.Report vr = IntelRunner.validate(k, dir, a[1], quick(), new AtomicBoolean(), (w, d, n) -> {});
        System.out.println("validation: " + vr.verdict + " — " + vr.summary + " · audit " + vr.auditChecked + "/" + vr.auditFailed);
        ok &= vr.sessions > 0 && vr.auditFailed == 0 && !IntelRunner.needsValidation(dir) && IntelRunner.governor(dir).size() == 9;
        Snapshot s = new Snapshot();
        s.time = System.currentTimeMillis();
        NewsItem n = new NewsItem();
        n.title = "RBI unexpectedly tightens liquidity"; n.source = "RBI (official)"; n.official = true; n.read = true; n.lead = true;
        n.niftyImpact = -0.7; n.severity = "HIGH"; n.time = System.currentTimeMillis() - 20 * 60000; n.verification = "VERIFIED";
        s.news.add(n);
        IntelEngine.LiveContext ctx = IntelRunner.context(s, null, "");
        Validator.Config cfg = quick();
        IntelEngine.Forecast fc = IntelRunner.live(k, dir, a[1], 11 * 60 + 2, Double.NaN, ctx, cfg, new AtomicBoolean(), (w, d, x) -> {});
        int filled = 0;
        for (IntelEngine.HPred p : fc.preds) { if (p.has()) filled++; System.out.printf("%-4s %-8s P(up) %.3f conf %3d %-6s range68 ±%.2f%% %s%n", p.hz.id, p.direction, p.pFinal, p.confidence, p.confLabel, p.range68 * 100, p.tradeable ? "TRADEABLE" : "no trade: " + p.gate); }
        System.out.println("regime " + fc.regime.label() + " · " + fc.regime.detail() + " · quality " + fc.quality.score + " · events " + fc.events.size() + " · memory " + fc.memory);
        ok &= fc.intraday && filled == 9 && fc.events.size() == 1 && fc.events.get(0).tier == 1 && fc.news[2] < 0 && !fc.memory.isEmpty();
        File logF = new File(dir, "intel/log_" + a[1].substring(0, 7) + ".jsonl");
        ok &= logF.exists() && java.nio.file.Files.readAllLines(logF.toPath()).size() == 9;
        // a second update minutes later: no extra log lines (30-min spacing), state used for What changed
        IntelEngine.Forecast fc2 = IntelRunner.live(k, dir, a[1], 11 * 60 + 7, Double.NaN, ctx, cfg, new AtomicBoolean(), (w, d, x) -> {});
        ok &= java.nio.file.Files.readAllLines(logF.toPath()).size() == 9 && fc2.preds.size() == 9;
        // pre-open, from a GIFT-implied open
        IntelEngine.Forecast po = IntelRunner.live(k, dir, a[1], 8 * 60, 25000, ctx, cfg, new AtomicBoolean(), (w, d, x) -> {});
        System.out.println("pre-open: " + po.asOf + " · 1D P(up) " + po.preds.get(7).pFinal);
        ok &= po.preOpen && po.k == 0 && po.preds.get(7).has();
        // export: one ZIP with every file, real rows, the documented columns
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        int files = IntelRunner.export(dir, bo, cfg);
        java.util.Map<String, String> zip = new java.util.LinkedHashMap<>();
        try (java.util.zip.ZipInputStream zi = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bo.toByteArray()))) {
            java.util.zip.ZipEntry ze;
            while ((ze = zi.getNextEntry()) != null) zip.put(ze.getName(), new String(zi.readAllBytes(), "UTF-8"));
        }
        System.out.println("export: " + files + " files, " + bo.size() / 1024 + " KB: " + zip.keySet());
        String[] need = {"README.txt", "summary_by_horizon.csv", "confidence_buckets.csv", "market_conditions.csv", "stress_days.csv", "leakage_audit.txt",
                "validation.json", "replay_forecasts.csv", "replay_trades.csv", "replay_features.csv", "live_forecast_log.csv", "my_trades_journal.csv", "my_option_positions.csv", "training_report.txt", "settings.txt"};
        for (String nm : need) ok &= zip.containsKey(nm);
        String[] rf = zip.getOrDefault("replay_forecasts.csv", "").split("\n");
        String[] feats = zip.getOrDefault("replay_features.csv", "").split("\n");
        ok &= files == need.length && rf.length > 1000 && rf[0].startsWith("date,time,block,horizon")
                && zip.get("summary_by_horizon.csv").split("\n").length == 10
                && feats.length > 100 && feats[0].split(",").length >= 4 + FeatureEngine.N
                && zip.get("live_forecast_log.csv").split("\n").length >= 10;
        System.out.println("replay_forecasts rows " + (rf.length - 1) + ", first: " + (rf.length > 1 ? rf[1] : ""));
        System.out.println("took " + (System.currentTimeMillis() - t0) / 1000 + "s");
        System.out.println(ok ? "1 passed, 0 failed" : "0 passed, 1 failed");
        if (!ok) System.exit(1);
    }
    static Validator.Config quick() { Validator.Config c = new Validator.Config(); c.sessions = 250; c.blocks = 4; return c; }
}

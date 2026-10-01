import com.krish.niftydirection.data.Collector;
import com.krish.niftydirection.data.Gemini;
import com.krish.niftydirection.data.Kite;
import com.krish.niftydirection.data.EventCalendar;
import com.krish.niftydirection.model.NewsItem;
import com.krish.niftydirection.data.Store;
import com.krish.niftydirection.engine.Engine;
import com.krish.niftydirection.engine.Factor;
import com.krish.niftydirection.engine.Result;
import com.krish.niftydirection.model.OptionRow;
import com.krish.niftydirection.model.Snapshot;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;

/** End-to-end: Collector + Kite parsing + Engine + Store against the local mock server. Args: port today dir */
public class CollectorTest {
    static int pass, fail;
    static void check(boolean ok, String w) { if (ok) pass++; else { fail++; System.out.println("FAIL: " + w); } }

    public static void main(String[] a) throws Exception {
        Kite.ROOT = "http://127.0.0.1:" + a[0];
        // ---- only a TokenException means the login is gone (403 PermissionException must not log the user out)
        check(Kite.isTokenError(403, "{\"status\":\"error\",\"error_type\":\"TokenException\",\"message\":\"x\"}"), "403 TokenException = expired");
        check(!Kite.isTokenError(403, "{\"status\":\"error\",\"error_type\":\"PermissionException\",\"message\":\"Insufficient permission\"}"), "403 PermissionException is not expiry");
        check(!Kite.isTokenError(429, "{\"status\":\"error\",\"error_type\":\"NetworkException\",\"message\":\"Too many requests\"}"), "429 is not expiry");
        check(Kite.isTokenError(403, "<html>Forbidden</html>"), "bare 403 still treated as expiry");
        String today = a[1];
        File dir = new File(a[2]); dir.mkdirs();
        Collector.Config cfg = new Collector.Config();
        cfg.apiKey = "KEY"; cfg.token = "TOKEN"; cfg.globalOn = false; cfg.nseOn = false; cfg.newsOn = false;
        cfg.fiiManual = 1800; cfg.gift = 25300; cfg.giftDate = today;

        // ---- 11:00 IST, market live
        Collector.nowOverride = at(today, 11, 0);
        Snapshot s = new Collector(dir).gather(cfg, w -> {});
        for (String n : s.notes) System.out.println("note: " + n);
        check(s.live, "live at 11:00");
        check(today.equals(s.sessionDate), "session is today");
        check(s.nifty != null && s.nifty.last == 25120, "nifty parsed");
        check(s.vix != null && Math.abs(s.vix.last - 12.4) < 1e-9, "vix parsed");
        check(s.sectors.size() == 10, "10 sectors: " + s.sectors.size());
        check(s.stocks.size() >= 10, "stocks from fallback list that the mock knows: " + s.stocks.size());
        check(s.fut != null && s.fut.symbol.endsWith("NIFTY26OCTFUT"), "near future rolls to Oct on Sep expiry day: " + (s.fut == null ? null : s.fut.symbol));
        check(s.futPrevOi > 0, "futures prev OI: " + s.futPrevOi);
        check(s.niftyBars.size() == 21 && s.futBars.size() == 21, "5m bars: " + s.niftyBars.size() + "/" + s.futBars.size());
        check(s.prevDay != null && s.prevDay.date.compareTo(today) < 0, "prevDay before today: " + (s.prevDay == null ? null : s.prevDay.date));
        check(today.equals(s.expiry), "options: nearest expiry is today before close: " + s.expiry);
        check(s.chain.size() == 31, "ATM ±15 = 31 strikes: " + s.chain.size());
        int withPrev = 0; for (OptionRow r : s.chain) if (r.hasPrev()) withPrev++;
        check(withPrev == 13, "prev OI on ATM±6 = 13 strikes: " + withPrev);
        check(s.giftNifty == 25310 && "Kite".equals(s.giftSource), "GIFT Nifty read from Kite (beats the typed 25300): " + s.giftNifty + " " + s.giftSource);
        check(s.futBars.size() == 21 && s.futBars.get(20).oi > 0 && s.futNextBars.size() == 21, "futures candles carry OI");
        int ivs = 0; for (OptionRow x : s.chain) if (!Double.isNaN(x.ceIv) || !Double.isNaN(x.peIv)) ivs++;
        check(ivs >= 15, "IV worked out for most strikes: " + ivs);
        check(s.flow.size() == 1, "first flow snapshot saved: " + s.flow.size());
        check(s.dma20.size() >= 10, "20-day averages loaded: " + s.dma20.size());
        check(!s.weights.isEmpty() && s.weightsApprox, "weights from built-in table when NSE is off");
        check(s.stocks.get(0).avgPrice > 0, "stock VWAP (average_price) parsed");
        // a second update 15 minutes later gives live option flow
        Collector.nowOverride = at(today, 11, 15);
        s = new Collector(dir).gather(cfg, w -> {});
        check(s.flow.size() == 2, "second flow snapshot: " + s.flow.size());
        Result r = Engine.run(s);
        print(r);
        check(r.factor("futflow").available, "live futures flow computed from candle OI: " + r.factor("futflow").detail);
        check(r.factor("optflow").available, "live option flow computed from snapshots: " + r.factor("optflow").detail);
        check(r.regime.equals(Result.BULLISH), "mock market is bullish -> " + r.regime);
        check(!Double.isNaN(r.vwapSpot) && !Double.isNaN(r.support) && !Double.isNaN(r.resistance), "levels computed");
        Store st = new Store(dir);
        st.addPoint(s, r); st.record(s, r);
        check(st.timeline(today).size() == 1, "timeline point saved");
        check(!st.days().isEmpty() && st.days().get(0).callRegime.equals(r.regime), "9:45 call saved");

        // ---- 16:00 IST same day: after close, expiry day -> next option expiry, Sep future dropped
        Collector.nowOverride = at(today, 16, 0);
        Snapshot e = new Collector(dir).gather(cfg, w -> {});
        check(!e.live, "not live after close");
        check(!today.equals(e.expiry), "after close on expiry day uses next expiry: " + e.expiry);
        check(e.prevDay != null && today.equals(e.prevDay.date), "evening prevDay = today's session");
        Result er = Engine.run(e);
        Factor gf = null; for (Factor f : er.factors) if (f.key.equals("gap")) gf = f;
        check(gf != null && gf.available && gf.detail.contains("(Kite)"), "evening gap factor uses Kite GIFT: " + (gf == null ? null : gf.detail));
        check(er.stage == 1, "stage 1 in the evening");
        st.record(e, er);
        Store.Day d0 = st.days().get(0);
        check(d0.final_ && !Double.isNaN(d0.callMove()), "close frozen for the record");

        // ---- expired token
        cfg.token = "BAD";
        Snapshot x = new Collector(dir).gather(cfg, w -> {});
        boolean flagged = false; for (String n : x.notes) if (n.startsWith("KITE_LOGIN")) flagged = true;
        check(flagged, "expired token flagged for re-login");

        // ---- Gemini reader against the mock
        Gemini.ROOT = "http://127.0.0.1:" + a[0] + "/v1beta/";
        String model = Gemini.model("GKEY", "auto");
        check(model.equals("gemini-3.8-flash"), "auto picks the newest general Flash model: " + model);
        List<NewsItem> items = new ArrayList<>();
        for (int i = 0; i < 3; i++) { NewsItem n = new NewsItem(); n.id = "id" + i; n.title = "RBI \"surprise\" hike " + i; n.source = "Test"; items.add(n); }
        Gemini.rate(items, "GKEY", "auto");
        check(items.get(2).read && "Gemini".equals(items.get(2).by) && items.get(2).niftyImpact == -0.6 && "HIGH".equals(items.get(2).severity), "Gemini ratings parsed (fenced JSON)");
        check(items.get(0).model.equals("gemini-3.8-flash") && items.get(0).promptVersion.equals(Gemini.PROMPT_VERSION) && items.get(0).ratedAt > 0, "each AI rating logs model + prompt version + time");
        check(items.get(0).topic.equals("rbi policy surprise") && items.get(0).speculative, "Gemini topic and speculative flag parsed");

        // pinned model: a new file pins "auto"; next call reuses it even if a newer model appears
        java.io.File pin = new java.io.File(a[2], "gemini_pin_test.txt");
        pin.delete();
        Gemini.pinFile = pin;
        java.lang.reflect.Field ch = Gemini.class.getDeclaredField("chosen"); ch.setAccessible(true); ch.set(null, "");
        String m1 = Gemini.model("GKEY", "auto");
        check(pin.exists() && m1.equals("gemini-3.8-flash"), "auto choice pinned to a file");
        java.nio.file.Files.write(pin.toPath(), "gemini-3.5-flash".getBytes());
        ch.set(null, "");
        check(Gemini.model("GKEY", "auto").equals("gemini-3.5-flash"), "pinned model is reused (results stay comparable)");
        check(Gemini.model("GKEY", "my-model").equals("my-model"), "a model typed in Settings wins");
        ch.set(null, "");

        // clustering / verification
        List<NewsItem> cl = new ArrayList<>();
        cl.add(news("a", "RBI hikes repo rate by 25 bps in surprise move", "ET Markets", 1000));
        cl.add(news("b", "RBI surprise: repo rate hiked by 25 bps", "Mint", 2000));
        cl.add(news("c", "Infosys wins large deal from European bank", "Moneycontrol", 1500));
        NewsItem off = news("d", "Monetary Policy Statement: repo rate raised", "RBI (official)", 3000); off.official = true; off.topic = "rbi rate hike";
        cl.add(off);
        cl.get(0).topic = "rbi rate hike";
        com.krish.niftydirection.data.News.cluster(cl);
        check(cl.get(0).cluster == cl.get(1).cluster && cl.get(0).cluster != cl.get(2).cluster, "same story grouped by words; different story separate");
        check(cl.get(0).verification.equals("VERIFIED") && cl.get(3).cluster == cl.get(0).cluster, "official feed in the group makes it VERIFIED");
        check(cl.get(2).verification.equals("PROVISIONAL") && cl.get(2).publishers == 1, "one publisher = PROVISIONAL");
        check(cl.get(0).lead && !cl.get(1).lead && !cl.get(3).lead, "earliest headline leads its group; others are duplicates");
        NewsItem sp = news("e", "Sources say government may cut fuel tax", "Mint", 100);
        cl.clear(); cl.add(sp); com.krish.niftydirection.data.News.cluster(cl);
        check(sp.speculative, "rumour words mark a headline speculative");

        // syndication: 3 sites carrying one PTI story = CORROBORATED, not CONFIRMED
        List<NewsItem> sy = new ArrayList<>();
        NewsItem s1 = news("s1", "Sensex falls 600 points as FIIs sell banking stocks", "ET Markets", 100); s1.summary = "Mumbai (PTI) Benchmark indices fell sharply...";
        NewsItem s2 = news("s2", "Sensex falls 600 pts as FIIs sell banking shares", "Business Standard", 200); s2.summary = "Mumbai, (PTI): Benchmark indices fell sharply...";
        NewsItem s3 = news("s3", "Sensex falls 600 points as FII selling hits banks", "Deccan Herald", 300); s3.summary = "Press Trust of India reports benchmark indices fell";
        sy.add(s1); sy.add(s2); sy.add(s3);
        com.krish.niftydirection.data.News.cluster(sy);
        check(s1.cluster == s2.cluster && s2.cluster == s3.cluster, "syndicated copies grouped as one story");
        check(s1.verification.equals("CORROBORATED") && s1.independent == 1 && s1.publishers == 3 && s1.agency.equals("PTI"),
                "same agency story on 3 sites = CORROBORATED (1 independent report): " + s1.verification + " " + s1.independent);
        // two different write-ups from different outlets = CONFIRMED
        List<NewsItem> in = new ArrayList<>();
        NewsItem i1 = news("i1", "Sensex falls 600 points as FIIs sell banking stocks", "ET Markets", 100); i1.summary = "Our reporter: foreign funds dumped HDFC Bank and ICICI";
        NewsItem i2 = news("i2", "FIIs dump banking stocks; Sensex falls 600 points", "Mint", 200); i2.summary = "Analysts at Mint say heavy selling by overseas investors in lenders";
        in.add(i1); in.add(i2);
        com.krish.niftydirection.data.News.cluster(in);
        check(i1.cluster == i2.cluster && i1.verification.equals("CONFIRMED") && i1.independent == 2, "two independent write-ups = CONFIRMED: " + i1.verification);
        // near-identical wording without an agency credit still counts as one report
        List<NewsItem> cp = new ArrayList<>();
        cp.add(news("c1", "RBI keeps repo rate unchanged at 5.5 per cent", "Site A", 1));
        cp.add(news("c2", "RBI keeps repo rate unchanged at 5.5 per cent", "Site B", 2));
        com.krish.niftydirection.data.News.cluster(cp);
        check(cp.get(0).verification.equals("CORROBORATED"), "copied text on two sites = CORROBORATED: " + cp.get(0).verification);

        List<com.krish.niftydirection.model.EventItem> ev = EventCalendar.upcoming(today, 10, Arrays.asList("2026-10-27"), Arrays.asList("2026-10-06"), "2026-10-01 My meeting", items);
        boolean rbi = false, mine = false, fromNews = false;
        for (com.krish.niftydirection.model.EventItem ei : ev) { if (ei.name.startsWith("RBI policy decision")) rbi = true; if (ei.name.equals("My meeting")) mine = true; if (ei.source.startsWith("news")) fromNews = true; }
        check(rbi && mine && fromNews, "calendar has RBI (7 Oct), my event and a news event: " + ev.size());

        // ---- protected calibration
        java.io.File cdir = new java.io.File(a[2], "calib"); cdir.mkdirs();
        Store cs = new Store(cdir);
        check(calib(cs, cdir, 30).get("vwap")[2] == 1.0, "under 100 readings: weight stays fixed");
        Map<String, double[]> big = calib(cs, cdir, 90);
        double[] meta = big.get("_meta");
        System.out.println("CALIB 90 days: vwap " + Arrays.toString(big.get("vwap")) + " noise " + Arrays.toString(big.get("noise")) + " meta " + Arrays.toString(meta));
        check(meta[1] == 20 && meta[0] == 70, "last 20 days held back for validation, 70 for training");
        check(big.get("vwap")[1] >= 100 && big.get("vwap")[2] > 1 && big.get("vwap")[2] <= 1.15, "useful factor gains weight, capped at +15% below 500 readings: " + big.get("vwap")[2]);
        check(meta[2] == 1, "adjusted weights passed validation");
        check(calib(cs, cdir, 90) != null && new java.io.File(cdir, "calib_" + weekStartOf("2026-09-29") + ".json").exists(), "weights cached for the week (no same-day refits)");

        System.out.println("\n" + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    /** Writes `days` finished days of fake checkpoints: "vwap" right 72% of the time, "noise" 50%. Returns the calibration for 2026-09-29. */
    static Map<String, double[]> calib(Store st, java.io.File dir, int days) throws Exception {
        for (java.io.File f : dir.listFiles()) f.delete();
        org.json.JSONObject all = new org.json.JSONObject();
        java.util.Random rnd = new java.util.Random(7);
        java.util.Calendar c = java.util.Calendar.getInstance(); c.set(2026, 8, 25);
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US);
        for (int d = 0; d < days; d++) {
            c.add(java.util.Calendar.DAY_OF_MONTH, -1);
            while (c.get(java.util.Calendar.DAY_OF_WEEK) == java.util.Calendar.SATURDAY || c.get(java.util.Calendar.DAY_OF_WEEK) == java.util.Calendar.SUNDAY) c.add(java.util.Calendar.DAY_OF_MONTH, -1);
            org.json.JSONObject day = new org.json.JSONObject();
            double close = 25000;
            for (String cp : new String[]{"c945", "c1130"}) {
                boolean up = rnd.nextBoolean();
                double price = up ? 24900 : 25100;
                double vw = (rnd.nextDouble() < 0.72 ? 1 : -1) * (up ? 0.6 : -0.6);
                double nz = (rnd.nextBoolean() ? 0.6 : -0.6);
                day.put(cp, new org.json.JSONObject().put("price", price).put("f", new org.json.JSONObject().put("vwap", vw).put("noise", nz)));
            }
            day.put("close", close).put("final", true);
            all.put(f.format(c.getTime()), day);
        }
        java.nio.file.Files.write(new java.io.File(dir, "record.json").toPath(), all.toString().getBytes());
        return st.calibration("2026-09-29");
    }

    static String weekStartOf(String d) throws Exception {
        java.lang.reflect.Method m = Store.class.getDeclaredMethod("weekStart", String.class); m.setAccessible(true); return (String) m.invoke(null, d);
    }

    static NewsItem news(String id, String title, String src, long t) {
        NewsItem n = new NewsItem(); n.id = id; n.title = title; n.source = src; n.time = t; n.read = true; return n;
    }

    static long at(String day, int h, int m) throws Exception {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("Asia/Kolkata"));
        return f.parse(day + String.format(" %02d:%02d", h, m)).getTime();
    }

    static void print(Result r) {
        System.out.printf("%s dir %d conf %d | %s | %s%n", r.label(), r.directionScore, r.confidence, r.state, r.action);
        for (Factor f : r.factors) System.out.printf("  %-24s %6s %-24s %s%n", f.name, f.available ? String.format("%+.2f", f.value) : "--", f.reading, f.detail);
    }
}

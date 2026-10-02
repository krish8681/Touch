package com.krish.niftydirection.data;

import com.krish.niftydirection.engine.Chain;
import com.krish.niftydirection.engine.Greeks;
import com.krish.niftydirection.model.Candle;
import com.krish.niftydirection.model.FlowPoint;
import com.krish.niftydirection.model.NewsItem;
import com.krish.niftydirection.model.OptionRow;
import com.krish.niftydirection.model.Quote;
import com.krish.niftydirection.model.Snapshot;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Gathers one Snapshot: Kite (indices, VIX, sectors, 50 stocks, futures, option chain, candles),
 * NSE (FII / DII, participant OI) and Yahoo (global markets).
 * Slow things are cached: the instrument list and yesterday's OI once a day, FII every 30 min, global every 5 min.
 */
public class Collector {
    public static final TimeZone IST = TimeZone.getTimeZone("Asia/Kolkata");

    /** Settings the collector needs (filled from Prefs on Android). */
    public static class Config {
        public String apiKey = "", token = "";
        public boolean globalOn = true, nseOn = true;
        public double gift = Double.NaN;          // typed GIFT Nifty
        public String giftDate = "";              // the day it was typed (old values are ignored)
        public double fiiManual = Double.NaN;     // typed FII cash (₹ cr), used when NSE fails
        public int strikesEachSide = 15;          // chain width (outer flow layer uses all of it)
        public int oiStrikes = 6;                 // strikes each side that get yesterday's OI
        public int strikesNext = 10;              // next-expiry chain width (strategy builder)
        public boolean newsOn = true;
        public String geminiKey = "", geminiModel = "auto";
        public String userEvents = "";            // "yyyy-mm-dd name" per line
    }

    public interface Progress { void step(String what); }

    static final String NIFTY = "NSE:NIFTY 50", BANK = "NSE:NIFTY BANK", FIN = "NSE:NIFTY FIN SERVICE", VIX = "NSE:INDIA VIX", GIFT = "NSEIX:GIFT NIFTY";
    public static final String[][] SECTORS = {
            {"Financials", "NSE:NIFTY FIN SERVICE"}, {"Energy", "NSE:NIFTY ENERGY"}, {"IT", "NSE:NIFTY IT"},
            {"Auto", "NSE:NIFTY AUTO"}, {"FMCG", "NSE:NIFTY FMCG"}, {"Pharma", "NSE:NIFTY PHARMA"},
            {"Metal", "NSE:NIFTY METAL"}, {"Infra", "NSE:NIFTY INFRA"}, {"Realty", "NSE:NIFTY REALTY"}, {"PSU Bank", "NSE:NIFTY PSU BANK"}};

    /** Used only if niftyindices.com cannot be reached. Any symbol Kite does not know is simply skipped. */
    static final String[] NIFTY50_FALLBACK = {
            "ADANIENT", "ADANIPORTS", "APOLLOHOSP", "ASIANPAINT", "AXISBANK", "BAJAJ-AUTO", "BAJFINANCE", "BAJAJFINSV", "BEL", "BHARTIARTL",
            "CIPLA", "COALINDIA", "DRREDDY", "EICHERMOT", "ETERNAL", "GRASIM", "HCLTECH", "HDFCBANK", "HDFCLIFE", "HINDALCO",
            "HINDUNILVR", "ICICIBANK", "INDIGO", "INFY", "ITC", "JIOFIN", "JSWSTEEL", "KOTAKBANK", "LT", "M&M",
            "MARUTI", "MAXHEALTH", "NESTLEIND", "NTPC", "ONGC", "POWERGRID", "RELIANCE", "SBILIFE", "SBIN", "SHRIRAMFIN",
            "SUNPHARMA", "TATACONSUM", "TMPV", "TATAMOTORS", "TATASTEEL", "TCS", "TECHM", "TITAN", "TRENT", "ULTRACEMCO", "WIPRO"};

    private final File dir;

    // in-memory caches (live as long as the process)
    private static List<Candle> dailyCache, vixCache = new ArrayList<>(); private static String dailyKey = "";
    private static Map<String, Double> globalPct = new HashMap<>(), globalPrice = new HashMap<>(); private static long globalAt;
    private static Nse.FiiDii fiiCache; private static long fiiAt;
    private static Nse.FiiOi fiiOiCache; private static String fiiOiKey = "";
    private static List<String> members; private static String membersKey = "";
    private static final Map<String, String> memberIndustry = new HashMap<>();
    private static long newsAt; private static List<NewsItem> newsCache = new ArrayList<>(); private static String newsReader = "";

    /** Tests only: pretend it is this time (epoch millis). 0 = real clock. */
    public static long nowOverride = 0;

    public Collector(File dir) { this.dir = dir; }

    public Snapshot gather(Config cfg, Progress pr) {
        Snapshot s = new Snapshot();
        Calendar now = Calendar.getInstance(IST);
        if (nowOverride > 0) now.setTimeInMillis(nowOverride);
        s.time = now.getTimeInMillis();
        s.today = day(now.getTime());
        s.minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        int dow = now.get(Calendar.DAY_OF_WEEK);
        s.weekday = dow != Calendar.SATURDAY && dow != Calendar.SUNDAY;
        boolean inHours = s.weekday && s.minute >= 9 * 60 + 15 && s.minute < 15 * 60 + 30;
        long t0 = System.currentTimeMillis();

        // ---------------- global + NSE (independent of Kite)
        if (cfg.globalOn) {
            step(pr, "Global markets…");
            if (System.currentTimeMillis() - globalAt > 5 * 60_000L || globalPct.isEmpty()) {
                Map<String, Global.Row> g = Global.fetch();
                if (!g.isEmpty()) {
                    globalPct = new HashMap<>(); globalPrice = new HashMap<>();
                    for (Map.Entry<String, Global.Row> e : g.entrySet()) {
                        if (!Double.isNaN(e.getValue().pct)) globalPct.put(e.getKey(), e.getValue().pct);
                        if (!Double.isNaN(e.getValue().price)) globalPrice.put(e.getKey(), e.getValue().price);
                    }
                    globalAt = System.currentTimeMillis();
                }
            }
            for (String[] sym : Global.SYMBOLS) if (globalPct.containsKey(sym[0])) s.global.put(sym[0], globalPct.get(sym[0]));
            s.globalPrice.putAll(globalPrice);
            if (globalAt > 0) s.sourceTime.put("Global markets (Yahoo)", globalAt);
            if (s.global.isEmpty()) s.notes.add("Global markets could not be loaded.");
        }
        if (cfg.nseOn) {
            step(pr, "NSE FII / DII…");
            if (fiiCache == null || System.currentTimeMillis() - fiiAt > 30 * 60_000L) {
                try { fiiCache = Nse.fiiDii(); fiiAt = System.currentTimeMillis(); } catch (Exception e) { fiiAt = System.currentTimeMillis() - 25 * 60_000L; }
            }
            if (fiiCache != null) {
                s.fiiCash = fiiCache.fii; s.diiCash = fiiCache.dii; s.fiiDate = fiiCache.date;
                long t = dayTime(fiiCache.date, "dd-MMM-yyyy", 18);
                if (t > 0) s.sourceTime.put("FII cash (NSE, daily)", t);
            }
            String key = s.today + (s.minute >= 19 * 60 ? "E" : "D");
            if (!key.equals(fiiOiKey)) { fiiOiCache = Nse.participantOi(); fiiOiKey = key; }
            if (fiiOiCache != null) {
                s.fiiIdxLong = fiiOiCache.longPct; s.fiiIdxLongPrev = fiiOiCache.prevLongPct; s.fiiOiDate = fiiOiCache.date;
                long t = dayTime(fiiOiCache.date + "-" + s.today.substring(0, 4), "dd-MMM-yyyy", 19);
                if (t > 0) s.sourceTime.put("FII futures positions (NSE, daily)", t);
            }
        }
        if (Double.isNaN(s.fiiCash) && !Double.isNaN(cfg.fiiManual)) { s.fiiCash = cfg.fiiManual; s.fiiDate = "typed"; }
        // Typed GIFT Nifty is only a fallback; the Kite price (below) replaces it when available.
        if (!Double.isNaN(cfg.gift) && cfg.gift > 0 && isRecent(cfg.giftDate, s)) { s.giftNifty = cfg.gift; s.giftSource = "typed"; }

        List<String> futExp = new ArrayList<>(), optExp = new ArrayList<>();
        if (cfg.apiKey.isEmpty() || cfg.token.isEmpty()) {
            s.notes.add("Not logged in to Kite — only global, NSE and news data are shown.");
        } else {
            Kite kite = new Kite(cfg.apiKey, cfg.token);
            try {
                kiteData(kite, cfg, s, inHours, pr, futExp, optExp);
            } catch (Kite.TokenExpired e) {
                s.notes.add("KITE_LOGIN: Kite login expired (" + e.getMessage() + "). Log in again.");
            } catch (Exception e) {
                s.notes.add("Kite error: " + e.getMessage());
            }
        }
        if (cfg.newsOn) news(cfg, s, pr);
        java.util.Collections.sort(futExp); java.util.Collections.sort(optExp);
        s.futExpiries = futExp; s.optExpiries = optExp;
        s.events = EventCalendar.upcoming(s.today, 10, futExp, optExp, cfg.userEvents, s.news);
        s.collectMs = System.currentTimeMillis() - t0;
        return s;
    }

    // ================================================================== news

    static Map<String, Double> approxWeights() {
        Map<String, Double> m = new HashMap<>();
        for (Map.Entry<String, double[]> e : Weights.approxWeights().entrySet()) m.put(e.getKey(), e.getValue()[0]);
        return m;
    }

    private void news(Config cfg, Snapshot s, Progress pr) {
        Store st = new Store(dir);
        Gemini.pinFile = new File(dir, "gemini_pin.txt");
        long now = System.currentTimeMillis();
        if (now - newsAt > 10 * 60_000L || newsCache.isEmpty()) {
            step(pr, "News…");
            List<NewsItem> fresh = News.fetch(24, s.weights.isEmpty() ? approxWeights() : s.weights);
            if (!fresh.isEmpty()) {
                Map<String, NewsItem> known = st.newsCache();
                List<NewsItem> merged = new ArrayList<>(), toRate = new ArrayList<>();
                for (NewsItem n : fresh) {
                    NewsItem k = known.get(n.id);
                    if (k != null && k.read && (cfg.geminiKey.isEmpty() || "Gemini".equals(k.by))) { merged.add(k); continue; }
                    merged.add(n);
                    toRate.add(n);
                }
                String reader = "keywords";
                if (!cfg.geminiKey.isEmpty() && !toRate.isEmpty()) {
                    step(pr, "Gemini is reading " + Math.min(40, toRate.size()) + " new headlines…");
                    try {
                        String model = Gemini.rate(toRate.size() > 40 ? toRate.subList(0, 40) : toRate, cfg.geminiKey, cfg.geminiModel);
                        reader = "Gemini (" + model + ")";
                    } catch (Exception e) {
                        s.notes.add("Gemini news reader: " + e.getMessage() + " — word list used instead.");
                    }
                } else if (!cfg.geminiKey.isEmpty()) reader = "Gemini";
                for (NewsItem n : toRate) if (!n.read) News.readByWords(n);
                if (!Gemini.lastSwitchNote.isEmpty()) { s.notes.add(Gemini.lastSwitchNote); Gemini.lastSwitchNote = ""; }
                News.cluster(merged);
                for (NewsItem n : merged) known.put(n.id, n);
                st.saveNews(known);
                newsCache = merged;
                newsReader = reader;
                newsAt = now;
            } else if (newsCache.isEmpty()) {
                s.notes.add("News feeds could not be loaded.");
                newsAt = now - 7 * 60_000L;
            }
        }
        s.news = new ArrayList<>(newsCache);
        s.newsReader = newsReader;
        s.newsAt = newsAt;
        if (newsAt > 0 && !s.news.isEmpty()) s.sourceTime.put("News headlines", newsAt);
    }

    private void kiteData(Kite kite, Config cfg, Snapshot s, boolean inHours, Progress pr, List<String> futExp, List<String> optExp) throws Exception {
        step(pr, "Kite: contracts…");
        List<Kite.Inst> nfo = kite.niftyContracts(dir, s.today);
        for (Kite.Inst i : nfo) {
            if (i.expiry.compareTo(s.today) < 0) continue;
            List<String> l = "FUT".equals(i.type) ? futExp : optExp;
            if (!l.contains(i.expiry)) l.add(i.expiry);
        }
        Kite.Inst fut = null, fut2 = null;
        List<Kite.Inst> futs = new ArrayList<>();
        // Futures roll on expiry day: the expiring contract is dying (volume and OI move to the next one), so skip it.
        for (Kite.Inst i : nfo) if ("FUT".equals(i.type) && i.expiry.compareTo(s.today) > 0) futs.add(i);
        futs.sort((a, b) -> a.expiry.compareTo(b.expiry));
        if (futs.size() > 0) fut = futs.get(0);
        if (futs.size() > 1) fut2 = futs.get(1);

        // ---------------- quote 1: indices, VIX, sectors, stocks, futures
        step(pr, "Kite: prices…");
        List<String> want = new ArrayList<>(Arrays.asList(NIFTY, BANK, FIN, VIX, GIFT));
        for (String[] x : SECTORS) if (!want.contains(x[1])) want.add(x[1]);
        List<String> mem = members(s.today);
        for (String m : mem) want.add("NSE:" + m);
        if (fut != null) want.add("NFO:" + fut.symbol);
        if (fut2 != null) want.add("NFO:" + fut2.symbol);
        Map<String, Quote> q = kite.quote(want);
        s.nifty = q.get(NIFTY); s.bank = q.get(BANK); s.fin = q.get(FIN); s.vix = q.get(VIX);
        useKiteGift(s, q.get(GIFT));
        for (String[] x : SECTORS) if (q.containsKey(x[1])) s.sectors.put(x[0], q.get(x[1]));
        for (String m : mem) { Quote x = q.get("NSE:" + m); if (x != null) { x.symbol = m; s.stocks.add(x); } }
        if (fut != null) { s.fut = q.get("NFO:" + fut.symbol); s.futDaysToExpiry = days(s.today, fut.expiry); }
        if (fut2 != null) s.futNext = q.get("NFO:" + fut2.symbol);
        if (s.nifty == null || !s.nifty.ok()) { s.notes.add("Kite returned no Nifty price."); return; }
        long fetched = System.currentTimeMillis();
        s.sourceTime.put("Kite prices", fetched);
        s.sourceTime.put("Nifty spot", quoteTime(s.nifty, fetched));
        if (s.fut != null && s.fut.ok()) s.sourceTime.put("Futures quote", quoteTime(s.fut, fetched));
        if (s.bank != null && s.bank.ok()) s.sourceTime.put("Bank Nifty", quoteTime(s.bank, fetched));
        List<Long> st = new ArrayList<>();
        for (Quote x : s.stocks) if (x.ok()) st.add(quoteTime(x, fetched));
        if (st.size() >= 10) { java.util.Collections.sort(st); s.sourceTime.put("Stocks (breadth)", st.get(st.size() / 2)); }
        if (s.gift != null) { long gt = parseTime(s.gift.time); if (gt > 0) s.sourceTime.put("GIFT Nifty (Kite)", gt); }
        weights(s, mem);

        // ---------------- session: is today's market running?
        step(pr, "Kite: daily candles…");
        String dk = s.today + (inHours ? "L" : s.minute >= 15 * 60 + 30 ? "C" : "P");
        if (!dk.equals(dailyKey) || dailyCache == null) {
            dailyCache = kite.historical(s.nifty.token, "day", daysAgo(s.today, 400) + " 00:00:00", s.today + " 23:59:59", false);
            dailyKey = dk;
            if (s.vix != null && s.vix.token > 0) {
                try { vixCache = kite.historical(s.vix.token, "day", daysAgo(s.today, 400) + " 00:00:00", s.today + " 23:59:59", false); }
                catch (Kite.TokenExpired e) { throw e; } catch (Exception e) { vixCache = new ArrayList<>(); }
            }
        }
        s.niftyDaily = dailyCache;
        s.vixDaily = vixCache;
        List<Candle> daily = dailyCache;
        String lastDay = daily.isEmpty() ? "" : daily.get(daily.size() - 1).date;
        String quoteDay = s.nifty.time.length() >= 10 ? s.nifty.time.substring(0, 10) : "";
        // Before 9:15 the exchange may already stamp quotes with today's date (pre-open), so only trust it from 9:15.
        boolean tradedToday = s.weekday && s.minute >= 9 * 60 + 15 && (s.today.equals(lastDay) || s.today.equals(quoteDay));
        s.live = inHours && tradedToday;
        s.sessionDate = tradedToday ? s.today : lastDay;
        for (int i = daily.size() - 1; i >= 0; i--) {
            Candle c = daily.get(i);
            if (s.live ? c.date.compareTo(s.today) < 0 : c.date.compareTo(s.sessionDate) <= 0) { s.prevDay = c; break; }
        }
        if (inHours && !tradedToday) s.notes.add("No trading today so far (holiday?). Showing the last session.");
        sessionRef(kite, s, q, pr);

        // ---------------- futures OI at the close before the session
        if (fut != null && !s.sessionDate.isEmpty()) {
            step(pr, "Kite: futures OI…");
            s.futPrevOi = futPrevOi(kite, s, fut, fut2);
        }

        // ---------------- today's 5-minute candles
        if (s.live) {
            step(pr, "Kite: today's candles…");
            String from = s.today + " 09:15:00", to = s.today + " 15:30:00";
            try { s.niftyBars = kite.historical(s.nifty.token, "5minute", from, to, false); } catch (Kite.TokenExpired e) { throw e; } catch (Exception e) { s.notes.add("Nifty candles: " + e.getMessage()); }
            if (fut != null) {
                try { s.futBars = kite.historical(fut.token, "5minute", from, to, true); } catch (Kite.TokenExpired e) { throw e; } catch (Exception e) { s.notes.add("Futures candles: " + e.getMessage()); }
            }
            if (fut2 != null) {
                try { s.futNextBars = kite.historical(fut2.token, "5minute", from, to, true); } catch (Kite.TokenExpired e) { throw e; } catch (Exception e) { s.notes.add("Next-month candles: " + e.getMessage()); }
            }
            if (!s.futBars.isEmpty()) {
                Candle last = s.futBars.get(s.futBars.size() - 1);
                long t = parseTime(s.today + String.format(Locale.US, " %02d:%02d:00", last.minute / 60, last.minute % 60));
                s.sourceTime.put("Futures 5-min candles + OI", t > 0 ? Math.min(fetched, t + 5 * 60_000L) : fetched);
            }
        }

        // ---------------- 20-day average of each stock (a few per update until all are known)
        dma20(kite, s, pr);

        // ---------------- option chain
        step(pr, "Kite: option chain…");
        chain(kite, cfg, s, nfo, pr);
        if (!s.chain.isEmpty()) s.sourceTime.put("Option chain", System.currentTimeMillis());

        // ---------------- intraday snapshot (for live option + futures flow)
        if (s.live && !s.chain.isEmpty()) {
            FlowPoint p = new FlowPoint();
            p.minute = s.minute;
            p.spot = s.nifty.last;
            if (s.fut != null && s.fut.ok()) { p.fut = s.fut.last; p.basis = s.fut.last - s.nifty.last; }
            p.futOi = (s.fut != null ? s.fut.oi : 0) + (s.futNext != null ? s.futNext.oi : 0);
            p.atmIv = Chain.atmIv(s.chain, s.nifty.last);
            for (OptionRow r : s.chain) {
                if (!r.ceSymbol.isEmpty()) p.opt.put(r.ceSymbol, new double[]{r.ceOi, r.ceLtp, r.ceVol, r.ceIv});
                if (!r.peSymbol.isEmpty()) p.opt.put(r.peSymbol, new double[]{r.peOi, r.peLtp, r.peVol, r.peIv});
            }
            s.flow = new Store(dir).addFlow(s.today, p);
        } else {
            s.flow = new Store(dir).flow(s.today);
        }
    }

    // ================================================================== weights, 20-day averages

    private static Map<String, Double> ffmcCache; private static String ffmcKey = "";

    private void weights(Snapshot s, List<String> mem) {
        File f = new File(dir, "weights.json");
        if (!s.today.equals(ffmcKey)) {
            JSONObject j = readJson(f);
            if (s.today.equals(j.optString("date"))) {
                ffmcCache = new java.util.LinkedHashMap<>();
                JSONObject w = j.optJSONObject("w");
                if (w != null) { java.util.Iterator<String> it = w.keys(); while (it.hasNext()) { String k = it.next(); ffmcCache.put(k, w.optDouble(k)); } }
            } else {
                Map<String, Double> m = Nse.ffmc();
                if (m != null) {
                    ffmcCache = m;
                    try {
                        JSONObject w = new JSONObject();
                        for (Map.Entry<String, Double> e : m.entrySet()) w.put(e.getKey(), e.getValue());
                        writeJson(f, new JSONObject().put("date", s.today).put("w", w), "weights_none");
                    } catch (Exception ignored) {}
                } else if (ffmcCache == null) {
                    // keep an older NSE file if we have one, else the built-in table
                    JSONObject w = j.optJSONObject("w");
                    if (w != null) { ffmcCache = new java.util.LinkedHashMap<>(); java.util.Iterator<String> it = w.keys(); while (it.hasNext()) { String k = it.next(); ffmcCache.put(k, w.optDouble(k)); } s.weightsDate = j.optString("date"); }
                }
            }
            ffmcKey = s.today;
        }
        double sum = 0;
        Map<String, Double> raw = new java.util.LinkedHashMap<>();
        boolean approx = ffmcCache == null || ffmcCache.isEmpty();
        Map<String, double[]> ap = Weights.approxWeights();
        for (String m : mem) {
            Double v = approx ? (ap.containsKey(m) ? ap.get(m)[0] : null) : ffmcCache.get(m);
            if (v == null || v <= 0) continue;
            raw.put(m, v); sum += v;
        }
        for (Map.Entry<String, Double> e : raw.entrySet()) s.weights.put(e.getKey(), e.getValue() / sum);
        for (String m : mem) {
            String ind = Weights.shortSector(memberIndustry.get(m));
            s.sectorOf.put(m, ind.isEmpty() ? Weights.approxSector(m) : ind);
        }
        s.weightsApprox = approx;
        if (!approx) s.sourceTime.put("Nifty weights (NSE)", System.currentTimeMillis());
        else s.notes.add("Nifty stock weights: NSE not reachable, using the built-in approximate table.");
    }

    /**
     * Overnight, Kite resets every quote's "previous close" to the last close, so before the open every change reads 0.00%
     * and yesterday's evidence (futures build-up, Bank Nifty, breadth, sectors) disappears from the morning idea.
     * Fix: keep each instrument's last-session numbers {previous close, open, high, low, close, volume}.
     * They are saved for free in the evening (quotes still correct), or fetched once from day candles in the morning.
     */
    private void sessionRef(Kite kite, Snapshot s, Map<String, Quote> q, Progress pr) throws Kite.TokenExpired {
        if (s.live || s.sessionDate.isEmpty() || s.nifty == null || !s.nifty.ok()) return;
        File f = new File(dir, "sessref_" + s.sessionDate + ".json");
        JSONObject j = readJson(f);
        boolean rolled = Math.abs(s.nifty.last - s.nifty.prevClose) < 0.01;
        try {
            if (!rolled) {
                // evening of the session: quotes still hold the session's change, save them (no extra requests)
                if (s.today.equals(s.sessionDate) && s.minute >= 15 * 60 + 30 && !j.has("_done")) {
                    for (Map.Entry<String, Quote> e : q.entrySet()) {
                        Quote x = e.getValue();
                        if (x == null || !x.ok() || x.prevClose <= 0 || e.getKey().equals(GIFT)) continue;
                        j.put(e.getKey(), new JSONArray().put(x.prevClose).put(x.open).put(x.high).put(x.low).put(x.last).put(x.volume));
                    }
                    j.put("_done", true);
                    writeJson(f, j, "sessref_");
                }
                return;
            }
            int fetched = 0, applied = 0;
            for (Map.Entry<String, Quote> e : q.entrySet()) {
                Quote x = e.getValue();
                if (x == null || !x.ok() || x.token <= 0 || e.getKey().equals(GIFT)) continue;
                JSONArray a = j.optJSONArray(e.getKey());
                if (a == null) {
                    if (fetched == 0) step(pr, "Kite: last session's moves…");
                    try {
                        List<Candle> c = kite.historical(x.token, "day", daysAgo(s.sessionDate, 12) + " 00:00:00", s.sessionDate + " 23:59:59", false);
                        Candle ses = null, ref = null;
                        for (Candle k : c) { if (k.date.equals(s.sessionDate)) ses = k; else if (k.date.compareTo(s.sessionDate) < 0) ref = k; }
                        if (ses != null && ref != null) {
                            a = new JSONArray().put(ref.c).put(ses.o).put(ses.h).put(ses.l).put(ses.c).put(ses.v);
                            j.put(e.getKey(), a);
                        }
                    } catch (Kite.TokenExpired ex) { throw ex; } catch (Exception ignored) { }
                    fetched++;
                }
                if (a == null || a.length() < 6 || a.optDouble(0, 0) <= 0) continue;
                x.prevClose = a.optDouble(0, x.prevClose);
                if (a.optDouble(1, 0) > 0) { x.open = a.optDouble(1, 0); x.high = a.optDouble(2, 0); x.low = a.optDouble(3, 0); }
                if (x.volume <= 0) x.volume = a.optDouble(5, 0);
                applied++;
            }
            if (fetched > 0) writeJson(f, j, "sessref_");
            if (applied > 0) s.notes.add("Before the open: price changes are the last session's (" + s.sessionDate + "), because Kite resets them overnight.");
        } catch (Kite.TokenExpired e) { throw e; } catch (Exception ignored) { }
    }

    private void dma20(Kite kite, Snapshot s, Progress pr) throws Kite.TokenExpired {
        File f = new File(dir, "dma20_" + s.sessionDate + ".json");
        JSONObject j = readJson(f);
        int fetched = 0;
        for (Quote q : s.stocks) {
            if (j.has(q.symbol)) continue;
            if (fetched >= 15) break;
            if (fetched == 0) step(pr, "Kite: 20-day averages…");
            try {
                List<Candle> c = kite.historical(q.token, "day", daysAgo(s.today, 45) + " 00:00:00", s.today + " 23:59:59", false);
                double sum = 0; int n = 0;
                for (int i = c.size() - 1; i >= 0 && n < 20; i--) {
                    if (c.get(i).date.compareTo(s.today) >= 0 && s.live) continue;   // completed days only while trading
                    sum += c.get(i).c; n++;
                }
                if (n >= 15) j.put(q.symbol, sum / n);
            } catch (Kite.TokenExpired e) { throw e; } catch (Exception ignored) { }
            fetched++;
        }
        if (fetched > 0) writeJson(f, j, "dma20_");
        java.util.Iterator<String> it = j.keys();
        while (it.hasNext()) { String k = it.next(); s.dma20.put(k, j.optDouble(k)); }
    }

    /**
     * GIFT Nifty from Kite (NSEIX:GIFT NIFTY, token 291849). Kite has shipped a bad instrument mapping for it before
     * (wrong symbol, 1970 time), so the price must be fresh and within 3% of Nifty to be trusted.
     */
    static void useKiteGift(Snapshot s, Quote g) {
        if (g == null || !g.ok() || s.nifty == null || !s.nifty.ok()) return;
        if (Math.abs(g.last - s.nifty.last) / s.nifty.last > 0.03) { s.notes.add("Kite's GIFT Nifty price looks wrong (" + Math.round(g.last) + "), not used."); return; }
        String day = g.time.length() >= 10 ? g.time.substring(0, 10) : "";
        int age = day.isEmpty() ? 99 : days(day, s.today);
        if (age < 0 || age > 3) { s.notes.add("Kite's GIFT Nifty price is old (" + (day.isEmpty() ? "no time" : day) + "), not used."); return; }
        s.gift = g;
        s.giftNifty = g.last;
        s.giftSource = "Kite";
    }

    // ================================================================== option chain

    private void chain(Kite kite, Config cfg, Snapshot s, List<Kite.Inst> nfo, Progress pr) throws Exception {
        String exp = null;
        for (Kite.Inst i : nfo) {
            if (!("CE".equals(i.type) || "PE".equals(i.type))) continue;
            if (i.expiry.compareTo(s.today) < 0) continue;
            if (i.expiry.equals(s.today) && s.minute >= 15 * 60 + 30) continue;
            if (exp == null || i.expiry.compareTo(exp) < 0) exp = i.expiry;
        }
        if (exp == null) { s.notes.add("No Nifty option expiry found."); return; }
        s.expiry = exp;
        s.optDaysToExpiry = days(s.today, exp);
        Map<Double, OptionRow> rows = new java.util.TreeMap<>();
        List<Double> strikes = new ArrayList<>();
        for (Kite.Inst i : nfo) if (exp.equals(i.expiry) && "CE".equals(i.type)) strikes.add(i.strike);
        java.util.Collections.sort(strikes);
        if (strikes.size() < 3) return;
        double spot = s.nifty.last;
        // step = most common gap near the money
        double step = 50;
        int atmIdx = 0;
        for (int i = 0; i < strikes.size(); i++) if (Math.abs(strikes.get(i) - spot) < Math.abs(strikes.get(atmIdx) - spot)) atmIdx = i;
        if (atmIdx + 1 < strikes.size()) step = strikes.get(atmIdx + 1) - strikes.get(atmIdx);
        if (step <= 0) step = 50;
        s.strikeStep = step;
        double atm = Math.round(spot / step) * step;
        double lo = atm - cfg.strikesEachSide * step, hi = atm + cfg.strikesEachSide * step;
        List<String> want = new ArrayList<>();
        Map<String, Kite.Inst> bySym = new HashMap<>();
        for (Kite.Inst i : nfo) {
            if (!exp.equals(i.expiry) || i.strike < lo - 1 || i.strike > hi + 1) continue;
            if (!("CE".equals(i.type) || "PE".equals(i.type))) continue;
            OptionRow r = rows.get(i.strike);
            if (r == null) { r = new OptionRow(); r.strike = i.strike; rows.put(i.strike, r); }
            if ("CE".equals(i.type)) { r.ceSymbol = i.symbol; r.ceToken = i.token; } else { r.peSymbol = i.symbol; r.peToken = i.token; }
            want.add("NFO:" + i.symbol);
            bySym.put(i.symbol, i);
        }
        for (Kite.Inst i : nfo) if (i.lot > 0 && ("CE".equals(i.type) || "PE".equals(i.type)) && exp.equals(i.expiry)) { s.lotSize = i.lot; break; }
        // the following expiry, narrower (strategy builder uses it when the near one expires before the trade's horizon)
        String exp2 = null;
        for (Kite.Inst i : nfo) {
            if (!("CE".equals(i.type) || "PE".equals(i.type)) || i.expiry.compareTo(exp) <= 0) continue;
            if (exp2 == null || i.expiry.compareTo(exp2) < 0) exp2 = i.expiry;
        }
        Map<Double, OptionRow> rows2 = new java.util.TreeMap<>();
        if (exp2 != null) {
            double lo2 = atm - cfg.strikesNext * step, hi2 = atm + cfg.strikesNext * step;
            for (Kite.Inst i : nfo) {
                if (!exp2.equals(i.expiry) || i.strike < lo2 - 1 || i.strike > hi2 + 1 || !("CE".equals(i.type) || "PE".equals(i.type))) continue;
                OptionRow r = rows2.get(i.strike);
                if (r == null) { r = new OptionRow(); r.strike = i.strike; rows2.put(i.strike, r); }
                if ("CE".equals(i.type)) { r.ceSymbol = i.symbol; r.ceToken = i.token; } else { r.peSymbol = i.symbol; r.peToken = i.token; }
                want.add("NFO:" + i.symbol);
            }
        }
        Map<String, Quote> q = kite.quote(want);
        fill(rows.values(), q, spot, Greeks.yearsToExpiry(s.today, s.minute, exp, s.optDaysToExpiry));
        if (exp2 != null && !rows2.isEmpty()) {
            s.expiry2 = exp2;
            s.opt2DaysToExpiry = days(s.today, exp2);
            fill(rows2.values(), q, spot, Greeks.yearsToExpiry(s.today, s.minute, exp2, s.opt2DaysToExpiry));
            s.chain2 = new ArrayList<>(rows2.values());
        }

        // yesterday's OI for the strikes nearest the money (cached per session + expiry)
        File f = new File(dir, "optoi_" + s.sessionDate + "_" + exp + ".json");
        JSONObject cache = readJson(f);
        int fetched = 0;
        for (OptionRow r : rows.values()) {
            boolean near = Math.abs(r.strike - atm) <= cfg.oiStrikes * step + 1;
            for (int side = 0; side < 2; side++) {
                String sym = side == 0 ? r.ceSymbol : r.peSymbol;
                long tok = side == 0 ? r.ceToken : r.peToken;
                if (sym.isEmpty()) continue;
                double prev = cache.has(sym) ? cache.optDouble(sym, -1) : -2;
                if (prev == -2 && near && fetched < 40) {
                    if (fetched == 0) step(pr, "Kite: yesterday's option OI (first run of the day is slower)…");
                    prev = prevOi(kite, tok, s.sessionDate);
                    fetched++;
                    if (prev >= 0) cache.put(sym, prev);
                }
                if (prev < 0) continue;
                if (side == 0) r.cePrevOi = prev; else r.pePrevOi = prev;
            }
        }
        if (fetched > 0) writeJson(f, cache, "optoi_");
        s.chain = new ArrayList<>(rows.values());
    }

    private static void fill(java.util.Collection<OptionRow> rows, Map<String, Quote> q, double spot, double t) {
        for (OptionRow r : rows) {
            Quote c = q.get("NFO:" + r.ceSymbol), p = q.get("NFO:" + r.peSymbol);
            if (c != null) { r.ceOi = c.oi; r.ceLtp = c.last; r.cePrevLtp = c.prevClose; r.ceVol = c.volume; r.ceBid = c.bid; r.ceAsk = c.ask; }
            if (p != null) { r.peOi = p.oi; r.peLtp = p.last; r.pePrevLtp = p.prevClose; r.peVol = p.volume; r.peBid = p.bid; r.peAsk = p.ask; }
            r.ceIv = Greeks.iv(true, spot, r.strike, t, r.ceLtp);
            r.peIv = Greeks.iv(false, spot, r.strike, t, r.peLtp);
        }
    }

    /** OI at the close of the last day before `session`. 0 if the contract did not exist yet, -1 if unknown. */
    private double prevOi(Kite kite, long token, String session) throws Kite.TokenExpired {
        try {
            List<Candle> c = kite.historical(token, "day", daysAgo(session, 10) + " 00:00:00", session + " 23:59:59", true);
            for (int i = c.size() - 1; i >= 0; i--) if (c.get(i).date.compareTo(session) < 0) return c.get(i).oi;
            return 0;
        } catch (Kite.TokenExpired e) {
            throw e;
        } catch (Exception e) {
            return -1;
        }
    }

    private double futPrevOi(Kite kite, Snapshot s, Kite.Inst fut, Kite.Inst fut2) throws Kite.TokenExpired {
        File f = new File(dir, "futoi_" + s.sessionDate + "_" + fut.symbol + ".json");
        JSONObject j = readJson(f);
        if (j.has("oi")) return j.optDouble("oi", -1);
        double a = prevOi(kite, fut.token, s.sessionDate);
        double b = fut2 != null ? prevOi(kite, fut2.token, s.sessionDate) : 0;
        if (a < 0 || b < 0) return -1;
        try { j.put("oi", a + b); } catch (Exception ignored) {}
        writeJson(f, j, "futoi_");
        return a + b;
    }

    // ================================================================== Nifty 50 members

    public List<String> members(String today) {
        String week = today.substring(0, 8);   // refresh at most once a month
        if (members != null && week.equals(membersKey)) return members;
        File f = new File(dir, "nifty50.txt");
        try {
            if (f.exists() && System.currentTimeMillis() - f.lastModified() < 7L * 86400_000L) {
                List<String> l = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
                if (l.size() >= 45) { members = splitMembers(l); membersKey = week; return members; }
            }
        } catch (Exception ignored) {}
        List<String> l = Nse.nifty50();
        if (l != null) {
            try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) { w.write(String.join("\n", l)); } catch (Exception ignored) {}
            l = splitMembers(l);
        } else {
            l = Arrays.asList(NIFTY50_FALLBACK);
        }
        members = l; membersKey = week;
        return l;
    }

    /** Lines are "SYMBOL" or "SYMBOL,Industry". */
    static List<String> splitMembers(List<String> lines) {
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            String[] p = line.split(",", 2);
            if (p[0].trim().isEmpty()) continue;
            out.add(p[0].trim());
            if (p.length > 1) memberIndustry.put(p[0].trim(), p[1].trim());
        }
        return out;
    }

    // ================================================================== helpers

    private static void step(Progress p, String what) { if (p != null) p.step(what); }

    static boolean isRecent(String typedDay, Snapshot s) {
        if (typedDay == null || typedDay.isEmpty()) return false;
        // A typed GIFT value is used for up to 3 days (covers Friday evening to Monday morning).
        int d = days(typedDay, s.today);
        return d >= 0 && d <= 3;
    }

    static JSONObject readJson(File f) {
        try { return f.exists() ? new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)) : new JSONObject(); }
        catch (Exception e) { return new JSONObject(); }
    }

    /** Write a cache file and delete older files with the same prefix (keeps the folder small). */
    static void writeJson(File f, JSONObject j, String prefix) {
        File[] old = f.getParentFile().listFiles((d, n) -> n.startsWith(prefix) && !n.equals(f.getName()));
        if (old != null) for (File o : old) if (System.currentTimeMillis() - o.lastModified() > 3 * 86400_000L) o.delete();
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) { w.write(j.toString()); } catch (Exception ignored) {}
    }

    /** Exchange time of a quote, or the fetch time when Kite gave none. Never later than the fetch. */
    static long quoteTime(Quote q, long fetched) {
        long t = parseTime(q.time);
        return t > 0 ? Math.min(t, fetched) : fetched;
    }

    /** "dd-MMM-yyyy" style day + hour → epoch ms in IST. 0 if unreadable. */
    static long dayTime(String d, String fmt, int hour) {
        try {
            SimpleDateFormat f = new SimpleDateFormat(fmt, Locale.US);
            f.setTimeZone(IST);
            return f.parse(d).getTime() + hour * 3600_000L;
        } catch (Exception e) { return 0; }
    }

    /** "yyyy-MM-dd HH:mm:ss" (IST) → epoch ms, 0 if unreadable. */
    static long parseTime(String t) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            f.setTimeZone(IST);
            return f.parse(t).getTime();
        } catch (Exception e) { return 0; }
    }

    public static String day(Date d) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(IST);
        return f.format(d);
    }

    public static String daysAgo(String day, int n) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            f.setTimeZone(IST);
            Calendar c = Calendar.getInstance(IST);
            c.setTime(f.parse(day));
            c.add(Calendar.DAY_OF_MONTH, -n);
            return f.format(c.getTime());
        } catch (Exception e) { return day; }
    }

    /** Calendar days from a to b (both yyyy-MM-dd). */
    public static int days(String a, String b) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            f.setTimeZone(IST);
            return (int) Math.round((f.parse(b).getTime() - f.parse(a).getTime()) / 86400_000.0);
        } catch (Exception e) { return 0; }
    }
}

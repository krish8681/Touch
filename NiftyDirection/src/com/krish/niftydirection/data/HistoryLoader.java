package com.krish.niftydirection.data;

import com.krish.niftydirection.model.Candle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Downloads and caches the price history the forecaster learns from (Kite candles + Yahoo daily closes). */
public class HistoryLoader {
    public interface Progress { void step(String what, int done, int total); }

    public static class CancelledException extends Exception { CancelledException() { super("Cancelled"); } }

    static final String NIFTY = "NSE:NIFTY 50", BANK = "NSE:NIFTY BANK", VIX = "NSE:INDIA VIX";
    static final String[][] GLOBAL = {{"S&P 500", "^GSPC"}, {"Nasdaq", "^IXIC"}, {"Nikkei", "^N225"}, {"Hang Seng", "^HSI"},
            {"Brent crude", "BZ=F"}, {"USD/INR", "INR=X"}, {"US 10Y yield", "^TNX"}, {"Dollar index", "DX-Y.NYB"}};

    private final Kite kite;
    private final File cache;
    private final AtomicBoolean cancel;

    public HistoryLoader(Kite kite, File dir, AtomicBoolean cancel) {
        this.kite = kite;
        this.cache = new File(dir, "history_cache");
        this.cache.mkdirs();
        this.cancel = cancel;
        File old = new File(dir, "replay_cache");   // left over from older versions
        File[] fs = old.listFiles();
        if (fs != null) { for (File f : fs) f.delete(); old.delete(); }
    }

    void checkCancel() throws CancelledException { if (cancel != null && cancel.get()) throw new CancelledException(); }

    /** 5-minute bars for [from, to]; cached when the block is fully in the past. */
    List<Candle> fiveMin(long token, String from, String to, String today) throws Exception {
        File f = new File(cache, "m5_" + token + "_" + from + "_" + to + ".bin.gz");
        if (f.exists()) try { return readBin(f); } catch (Exception ignored) { f.delete(); }
        List<Candle> c = kite.historical(token, "5minute", from + " 09:00:00", to + " 15:35:00", false);
        for (Candle x : c) x.date = x.date.intern();
        if (to.compareTo(today) < 0) writeBin(f, c);
        return c;
    }

    List<Candle> daily(long token, String today, int days) throws Exception {
        File f = new File(cache, "d_" + token + "_" + days + "_" + today + ".bin.gz");
        if (f.exists()) try { return readBin(f); } catch (Exception ignored) { f.delete(); }
        List<Candle> c = kite.historical(token, "day", Collector.daysAgo(today, days) + " 00:00:00", today + " 23:59:59", false);
        for (Candle x : c) x.date = x.date.intern();
        File[] old = cache.listFiles((d, n) -> n.startsWith("d_" + token + "_") && !n.endsWith("_" + today + ".bin.gz"));
        if (old != null) for (File o : old) o.delete();
        writeBin(f, c);
        return c;
    }

    static void writeBin(File f, List<Candle> c) {
        try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(new FileOutputStream(f))))) {
            o.writeInt(c.size());
            for (Candle x : c) {
                o.writeUTF(x.date); o.writeShort(x.minute);
                o.writeDouble(x.o); o.writeDouble(x.h); o.writeDouble(x.l); o.writeDouble(x.c); o.writeDouble(x.v); o.writeDouble(x.oi);
            }
        } catch (Exception e) { f.delete(); }
    }

    static List<Candle> readBin(File f) throws Exception {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(new FileInputStream(f))))) {
            int n = in.readInt();
            List<Candle> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                Candle x = new Candle();
                x.date = in.readUTF().intern(); x.minute = in.readShort();
                x.o = in.readDouble(); x.h = in.readDouble(); x.l = in.readDouble(); x.c = in.readDouble(); x.v = in.readDouble(); x.oi = in.readDouble();
                out.add(x);
            }
            return out;
        }
    }

    public static long cacheSize(File dir) {
        long b = 0;
        for (String sub : new String[]{"history_cache", "forecast"}) {
            File[] fs = new File(dir, sub).listFiles();
            if (fs != null) for (File f : fs) b += f.length();
        }
        return b;
    }

    static String plusDays(String day, int n) { return Collector.daysAgo(day, -n); }

    /** Weekly expiry sessions: Thursday until Aug 2025, Tuesday from Sep 2025; moved to the previous session on holidays. */
    static void expiries(List<String> sessions, Set<String> weekly) {
        Set<String> have = new java.util.HashSet<>(sessions);
        if (sessions.isEmpty()) return;
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(Collector.IST);
        try {
            Calendar c = Calendar.getInstance(Collector.IST);
            c.setTime(f.parse(sessions.get(0)));
            Calendar end = Calendar.getInstance(Collector.IST);
            end.setTime(f.parse(sessions.get(sessions.size() - 1)));
            end.add(Calendar.DAY_OF_MONTH, 7);
            while (!c.after(end)) {
                String d = f.format(c.getTime());
                int want = d.compareTo("2025-09-01") < 0 ? Calendar.THURSDAY : Calendar.TUESDAY;
                if (c.get(Calendar.DAY_OF_WEEK) == want) {
                    String e = d;
                    int guard = 0;
                    while (!have.contains(e) && guard++ < 5) e = Collector.daysAgo(e, 1);
                    if (have.contains(e)) weekly.add(e);
                }
                c.add(Calendar.DAY_OF_MONTH, 1);
            }
        } catch (Exception ignored) { }
    }

    /** Yahoo daily closes keyed by the IST date of each session (cached for the day). Only closes before today. */
    static TreeMap<String, Double> yahoo(File dir, String symbol, String today) {
        String safe = symbol.replaceAll("[^A-Za-z0-9]", "_");
        File f = new File(new File(dir, "history_cache"), "y_" + safe + "_" + today + ".json");
        try {
            String body;
            if (f.exists()) body = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            else {
                Map<String, String> h = new HashMap<>();
                h.put("User-Agent", Http.BROWSER_UA);
                try { body = Http.get("https://query1.finance.yahoo.com/v8/finance/chart/" + Http.enc(symbol) + "?range=10y&interval=1d", h, 20000); }
                catch (Exception e) { body = Http.get("https://query2.finance.yahoo.com/v8/finance/chart/" + Http.enc(symbol) + "?range=10y&interval=1d", h, 20000); }
                f.getParentFile().mkdirs();
                File[] old = f.getParentFile().listFiles((dd, n) -> n.startsWith("y_" + safe + "_"));
                if (old != null) for (File x : old) x.delete();
                write(f, body);
            }
            JSONObject res = new JSONObject(body).getJSONObject("chart").getJSONArray("result").getJSONObject(0);
            JSONArray ts = res.getJSONArray("timestamp");
            JSONArray cl = res.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0).getJSONArray("close");
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            fmt.setTimeZone(Collector.IST);
            TreeMap<String, Double> m = new TreeMap<>();
            for (int i = 0; i < ts.length(); i++) {
                double c = cl.optDouble(i, Double.NaN);
                if (Double.isNaN(c)) continue;
                String day = fmt.format(new java.util.Date(ts.getLong(i) * 1000L));
                if (day.compareTo(today) >= 0) continue;
                m.put(day, c);
            }
            return m;
        } catch (Exception e) {
            f.delete();
            return null;
        }
    }

    static void write(File f, String s) throws Exception {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) { w.write(s); }
    }
}

package com.krish.niftydirection.data;

import com.krish.niftydirection.model.Candle;
import com.krish.niftydirection.model.Quote;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Zerodha Kite Connect v3 (REST). Read-only: quotes, instruments, historical candles.
 * This app never places orders.
 */
public class Kite {
    /** Not final so tests can point it at a local mock server. */
    public static String ROOT = "https://api.kite.trade";
    public static final String REDIRECT = "https://127.0.0.1/kite";

    /** Thrown when Kite says the token is bad or expired — the user must log in again. */
    public static class TokenExpired extends IOException { TokenExpired(String m) { super(m); } }

    private final String apiKey, token;
    private long lastQuote, lastHist;

    public Kite(String apiKey, String accessToken) { this.apiKey = apiKey; this.token = accessToken; }

    public static String loginUrl(String apiKey) { return "https://kite.zerodha.com/connect/login?v=3&api_key=" + Http.enc(apiKey); }

    /** Swap the request_token (from the login redirect) for an access token. Returns {access_token, user_name}. */
    public static String[] createSession(String apiKey, String secret, String requestToken) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] h = md.digest((apiKey + requestToken + secret).getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : h) hex.append(String.format("%02x", b));
        Map<String, String> form = new LinkedHashMap<>();
        form.put("api_key", apiKey);
        form.put("request_token", requestToken);
        form.put("checksum", hex.toString());
        Map<String, String> hd = new HashMap<>();
        hd.put("X-Kite-Version", "3");
        String body;
        try {
            body = Http.postForm(ROOT + "/session/token", hd, form, 20000);
        } catch (Http.HttpError e) {
            throw new IOException(kiteMessage(e.body, e.getMessage()));
        }
        JSONObject d = new JSONObject(body).getJSONObject("data");
        return new String[]{d.getString("access_token"), d.optString("user_name", d.optString("user_id", ""))};
    }

    private Map<String, String> headers() {
        Map<String, String> h = new HashMap<>();
        h.put("X-Kite-Version", "3");
        h.put("Authorization", "token " + apiKey + ":" + token);
        return h;
    }

    private String call(String url) throws IOException {
        try {
            return Http.get(url, headers(), 25000);
        } catch (Http.HttpError e) {
            String msg = kiteMessage(e.body, e.getMessage());
            if (e.code == 403 || (e.body != null && e.body.contains("TokenException"))) throw new TokenExpired(msg);
            throw new IOException(msg);
        }
    }

    static JSONObject json(String body) throws IOException {
        try { return new JSONObject(body); } catch (org.json.JSONException e) { throw new IOException("Kite sent an unreadable reply"); }
    }

    static String kiteMessage(String body, String fallback) {
        try { return new JSONObject(body).optString("message", fallback); } catch (Exception x) { return fallback; }
    }

    private synchronized void pace(boolean hist) {
        long gap = hist ? 350 : 1050;   // Kite: quote 1/s, historical 3/s
        long now = System.currentTimeMillis(), last = hist ? lastHist : lastQuote;
        if (now - last < gap) try { Thread.sleep(gap - (now - last)); } catch (InterruptedException ignored) {}
        if (hist) lastHist = System.currentTimeMillis(); else lastQuote = System.currentTimeMillis();
    }

    /** Full quotes. Keys like "NSE:NIFTY 50", "NFO:NIFTY26OCTFUT". Unknown symbols are simply missing from the result. */
    public Map<String, Quote> quote(List<String> instruments) throws IOException {
        Map<String, Quote> out = new HashMap<>();
        for (int i = 0; i < instruments.size(); i += 450) {
            StringBuilder u = new StringBuilder(ROOT + "/quote?");
            for (int j = i; j < Math.min(instruments.size(), i + 450); j++) {
                if (j > i) u.append('&');
                u.append("i=").append(Http.enc(instruments.get(j)));
            }
            pace(false);
            JSONObject data = json(call(u.toString())).optJSONObject("data");
            if (data == null) continue;
            java.util.Iterator<String> it = data.keys();
            while (it.hasNext()) {
                String k = it.next();
                JSONObject o = data.optJSONObject(k);
                if (o == null) continue;
                Quote q = new Quote();
                q.symbol = k;
                q.token = o.optLong("instrument_token");
                q.last = o.optDouble("last_price", 0);
                q.volume = o.optDouble("volume", 0);
                q.oi = o.optDouble("oi", 0);
                q.avgPrice = o.optDouble("average_price", 0);
                q.buyQty = o.optDouble("buy_quantity", 0);
                q.sellQty = o.optDouble("sell_quantity", 0);
                JSONObject ohlc = o.optJSONObject("ohlc");
                if (ohlc != null) {
                    q.open = ohlc.optDouble("open", 0); q.high = ohlc.optDouble("high", 0);
                    q.low = ohlc.optDouble("low", 0); q.prevClose = ohlc.optDouble("close", 0);
                }
                String ts = o.isNull("timestamp") ? "" : o.optString("timestamp", "");
                String lt = o.isNull("last_trade_time") ? "" : o.optString("last_trade_time", "");
                q.time = lt.compareTo(ts) > 0 ? lt : ts;
                out.put(k, q);
            }
        }
        return out;
    }

    /** Candles. interval: minute, 5minute, 15minute, day. from/to: "yyyy-MM-dd HH:mm:ss". */
    public List<Candle> historical(long token, String interval, String from, String to, boolean oi) throws IOException {
        return historical(token, interval, from, to, oi, false);
    }

    /** continuous = true: day candles stitched across expired futures contracts (Kite supports this for day candles only). */
    public List<Candle> historical(long token, String interval, String from, String to, boolean oi, boolean continuous) throws IOException {
        String u = ROOT + "/instruments/historical/" + token + "/" + interval + "?from=" + Http.enc(from) + "&to=" + Http.enc(to) + (oi ? "&oi=1" : "") + (continuous ? "&continuous=1" : "");
        pace(true);
        JSONObject d = json(call(u)).optJSONObject("data");
        List<Candle> out = new ArrayList<>();
        if (d == null) return out;
        JSONArray a = d.optJSONArray("candles");
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            JSONArray c = a.optJSONArray(i);
            if (c == null || c.length() < 5) continue;
            Candle k = new Candle();
            String ts = c.optString(0, "");   // 2026-09-29T09:15:00+0530
            if (ts.length() >= 16) {
                k.date = ts.substring(0, 10);
                try { k.minute = Integer.parseInt(ts.substring(11, 13)) * 60 + Integer.parseInt(ts.substring(14, 16)); } catch (Exception ignored) {}
            }
            k.o = c.optDouble(1); k.h = c.optDouble(2); k.l = c.optDouble(3); k.c = c.optDouble(4);
            k.v = c.length() > 5 ? c.optDouble(5, 0) : 0;
            k.oi = c.length() > 6 ? c.optDouble(6, 0) : 0;
            out.add(k);
        }
        return out;
    }

    // ------------------------------------------------------------------ instruments

    /** One NFO contract on NIFTY. */
    public static class Inst {
        public long token;
        public String symbol, type, expiry;   // type FUT / CE / PE, expiry yyyy-MM-dd
        public double strike;
        public int lot;
    }

    /**
     * NIFTY futures and options from the NFO instrument list. The full list is large (several MB),
     * so it is downloaded once a day and only the NIFTY rows are kept on disk.
     */
    public List<Inst> niftyContracts(File dir, String today) throws IOException {
        File f = new File(dir, "nfo_nifty_" + today + ".csv");
        String text;
        if (f.exists() && f.length() > 1000) {
            text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } else {
            String all = call(ROOT + "/instruments/NFO");
            StringBuilder keep = new StringBuilder();
            BufferedReader br = new BufferedReader(new StringReader(all));
            String line;
            while ((line = br.readLine()) != null) {
                // columns: instrument_token,exchange_token,tradingsymbol,name,last_price,expiry,strike,tick_size,lot_size,instrument_type,segment,exchange
                String[] p = line.split(",", -1);
                if (p.length < 12) continue;
                if (!"NIFTY".equals(unq(p[3]))) continue;
                keep.append(line).append('\n');
            }
            text = keep.toString();
            if (text.length() > 1000) {
                File[] old = dir.listFiles((d, n) -> n.startsWith("nfo_nifty_"));
                if (old != null) for (File o : old) o.delete();
                try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) { w.write(text); }
            }
        }
        List<Inst> out = new ArrayList<>();
        BufferedReader br = new BufferedReader(new StringReader(text));
        String line;
        while ((line = br.readLine()) != null) {
            String[] p = line.split(",", -1);
            if (p.length < 12) continue;
            Inst i = new Inst();
            try {
                i.token = Long.parseLong(unq(p[0]));
                i.symbol = unq(p[2]);
                i.expiry = unq(p[5]);
                i.strike = p[6].isEmpty() ? 0 : Double.parseDouble(unq(p[6]));
                i.lot = p[8].isEmpty() ? 0 : (int) Double.parseDouble(unq(p[8]));
                i.type = unq(p[9]);
            } catch (Exception e) { continue; }
            out.add(i);
        }
        return out;
    }

    static String unq(String s) {
        s = s.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1);
        return s;
    }
}

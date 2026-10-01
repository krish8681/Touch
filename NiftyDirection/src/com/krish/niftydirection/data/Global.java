package com.krish.niftydirection.data;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Global markets from Yahoo Finance's public chart endpoint (no key). % change vs the previous close. */
public final class Global {
    private Global() {}

    /** Display name → Yahoo symbol. The engine reads these names. */
    public static final String[][] SYMBOLS = {
            {"S&P 500", "^GSPC"}, {"Nasdaq", "^IXIC"}, {"Dow", "^DJI"}, {"US futures", "ES=F"},
            {"Nikkei", "^N225"}, {"Hang Seng", "^HSI"}, {"Kospi", "^KS11"},
            {"Brent crude", "BZ=F"}, {"USD/INR", "INR=X"}, {"US 10Y yield", "^TNX"}, {"Dollar index", "DX-Y.NYB"}};

    public static class Row { public double price = Double.NaN, pct = Double.NaN; public long time; }

    public static Map<String, Row> fetch() {
        Map<String, Row> out = new LinkedHashMap<>();
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", Http.BROWSER_UA);
        h.put("Accept", "application/json");
        for (String[] s : SYMBOLS) {
            try {
                String body;
                try {
                    body = Http.get("https://query1.finance.yahoo.com/v8/finance/chart/" + Http.enc(s[1]) + "?range=1d&interval=15m", h, 10000);
                } catch (Exception first) {
                    body = Http.get("https://query2.finance.yahoo.com/v8/finance/chart/" + Http.enc(s[1]) + "?range=1d&interval=15m", h, 10000);
                }
                JSONObject meta = new JSONObject(body).getJSONObject("chart").getJSONArray("result").getJSONObject(0).getJSONObject("meta");
                double p = meta.optDouble("regularMarketPrice", Double.NaN);
                double prev = meta.optDouble("chartPreviousClose", meta.optDouble("previousClose", Double.NaN));
                Row r = new Row();
                r.price = p;
                r.time = meta.optLong("regularMarketTime", 0) * 1000L;
                if (!Double.isNaN(p) && prev > 0) r.pct = (p - prev) / prev * 100;
                out.put(s[0], r);
            } catch (Exception ignored) { }
        }
        return out;
    }
}

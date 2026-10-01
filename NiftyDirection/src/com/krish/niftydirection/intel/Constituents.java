package com.krish.niftydirection.intel;

import com.krish.niftydirection.model.Quote;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * NIFTY 50 Constituents Engine. Nifty is free-float weighted, so each stock's move — and each piece of company news —
 * counts in proportion to its weight in the index (weights come from NSE via the collector).
 */
public final class Constituents {
    private Constituents() {}

    /** Names the news uses for the heavier constituents (symbol → lower-case aliases). The symbol itself always matches too. */
    static final String[][] ALIASES = {
            {"HDFCBANK", "hdfc bank"}, {"RELIANCE", "reliance industries", "reliance", "ril"}, {"ICICIBANK", "icici bank"},
            {"BHARTIARTL", "bharti airtel", "airtel"}, {"INFY", "infosys"}, {"TCS", "tata consultancy", "tcs"}, {"SBIN", "state bank of india", "sbi"},
            {"LT", "larsen", "l&t"}, {"AXISBANK", "axis bank"}, {"ITC", "itc"}, {"KOTAKBANK", "kotak mahindra bank", "kotak bank", "kotak"},
            {"BAJFINANCE", "bajaj finance"}, {"BAJAJFINSV", "bajaj finserv"}, {"HINDUNILVR", "hindustan unilever", "hul"},
            {"MARUTI", "maruti suzuki", "maruti"}, {"M&M", "mahindra & mahindra", "mahindra and mahindra", "m&m"}, {"SUNPHARMA", "sun pharma"},
            {"HCLTECH", "hcl tech", "hcltech"}, {"NTPC", "ntpc"}, {"TATAMOTORS", "tata motors"}, {"TMPV", "tata motors"}, {"TATASTEEL", "tata steel"},
            {"POWERGRID", "power grid"}, {"ONGC", "ongc"}, {"TITAN", "titan"}, {"ULTRACEMCO", "ultratech"}, {"ASIANPAINT", "asian paints"},
            {"WIPRO", "wipro"}, {"TECHM", "tech mahindra"}, {"ADANIENT", "adani enterprises"}, {"ADANIPORTS", "adani ports"}, {"COALINDIA", "coal india"},
            {"NESTLEIND", "nestle india", "nestle"}, {"JSWSTEEL", "jsw steel"}, {"HINDALCO", "hindalco"}, {"GRASIM", "grasim"}, {"CIPLA", "cipla"},
            {"DRREDDY", "dr reddy", "dr. reddy"}, {"ETERNAL", "eternal", "zomato"}, {"TRENT", "trent"}, {"BEL", "bharat electronics"},
            {"SHRIRAMFIN", "shriram finance"}, {"APOLLOHOSP", "apollo hospitals"}, {"EICHERMOT", "eicher motors", "royal enfield"},
            {"HDFCLIFE", "hdfc life"}, {"SBILIFE", "sbi life"}, {"TATACONSUM", "tata consumer"}, {"INDIGO", "interglobe", "indigo"},
            {"JIOFIN", "jio financial"}, {"MAXHEALTH", "max healthcare"}, {"BAJAJ-AUTO", "bajaj auto"}};

    /** Constituents named in a headline (by alias or symbol), with their index weights. */
    public static Map<String, Double> mentioned(String text, Map<String, Double> weights) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (text == null || weights == null || weights.isEmpty()) return out;
        String t = " " + text.toLowerCase(Locale.US).replaceAll("[^a-z0-9&. ]", " ") + " ";
        for (String[] a : ALIASES) {
            Double w = weights.get(a[0]);
            if (w == null) continue;
            for (int i = 1; i < a.length; i++) if (t.contains(" " + a[i] + " ")) { out.put(a[0], w); break; }
        }
        for (Map.Entry<String, Double> e : weights.entrySet()) {
            String sym = e.getKey().toLowerCase(Locale.US);
            if (sym.length() >= 4 && t.contains(" " + sym + " ")) out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    /** Sector weight in Nifty (fraction), matching the news sector name loosely to the collector's sector names. */
    public static double sectorWeight(String sector, Map<String, Double> weights, Map<String, String> sectorOf) {
        if (sector == null || sector.isEmpty() || weights == null) return Double.NaN;
        String s = sector.toLowerCase(Locale.US);
        if (s.startsWith("broad") || s.equals("none")) return Double.NaN;
        String key = s.startsWith("health") || s.startsWith("pharma") ? "health" : s.startsWith("metal") ? "metal" : s.startsWith("infra") ? "infra"
                : s.startsWith("consumer") ? "consumer" : s.length() > 4 ? s.substring(0, 4) : s;
        double w = 0;
        for (Map.Entry<String, Double> e : weights.entrySet()) {
            String sec = sectorOf == null ? null : sectorOf.get(e.getKey());
            if (sec != null && sec.toLowerCase(Locale.US).contains(key)) w += e.getValue();
        }
        return w > 0 ? w : Double.NaN;
    }

    /** Points each stock added to Nifty today: weight × % change. Sorted by size. {symbol, contribution %, stock %}. */
    public static List<Object[]> contributions(List<Quote> stocks, Map<String, Double> weights) {
        List<Object[]> l = new ArrayList<>();
        if (stocks == null || weights == null) return l;
        for (Quote q : stocks) {
            Double w = weights.get(q.symbol);
            if (w == null || !q.ok() || q.prevClose <= 0) continue;
            l.add(new Object[]{q.symbol, w * q.pct(), q.pct()});
        }
        l.sort((a, b) -> Double.compare(Math.abs((Double) b[1]), Math.abs((Double) a[1])));
        return l;
    }

    /** Share of index weight trading up today (0..1), NaN without data. */
    public static double weightUp(List<Quote> stocks, Map<String, Double> weights) {
        double up = 0, tot = 0;
        if (stocks == null || weights == null) return Double.NaN;
        for (Quote q : stocks) {
            Double w = weights.get(q.symbol);
            if (w == null || !q.ok() || q.prevClose <= 0) continue;
            tot += w;
            if (q.last > q.prevClose) up += w;
        }
        return tot > 0.5 ? up / tot : Double.NaN;
    }
}

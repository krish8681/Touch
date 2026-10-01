package com.krish.niftydirection.data;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Nifty 50 weights. Live weights come from NSE (free-float market cap of each member).
 * This table is only a fallback when NSE can't be reached — rough 2026 weights, marked "approx" in the app.
 */
public final class Weights {
    private Weights() {}

    /** symbol, approx weight %, sector. */
    static final Object[][] APPROX = {
            {"HDFCBANK", 12.8, "Financials"}, {"ICICIBANK", 8.9, "Financials"}, {"RELIANCE", 8.4, "Energy"}, {"INFY", 4.6, "IT"},
            {"BHARTIARTL", 4.7, "Telecom"}, {"LT", 3.9, "Infra & capital goods"}, {"ITC", 3.2, "FMCG"}, {"SBIN", 3.2, "Financials"},
            {"AXISBANK", 3.0, "Financials"}, {"TCS", 2.7, "IT"}, {"KOTAKBANK", 2.7, "Financials"}, {"M&M", 2.6, "Auto"},
            {"BAJFINANCE", 2.3, "Financials"}, {"HINDUNILVR", 1.9, "FMCG"}, {"SUNPHARMA", 1.6, "Healthcare"}, {"ETERNAL", 1.5, "Consumer"},
            {"HCLTECH", 1.5, "IT"}, {"MARUTI", 1.5, "Auto"}, {"NTPC", 1.4, "Power"}, {"TITAN", 1.3, "Consumer"},
            {"BEL", 1.3, "Infra & capital goods"}, {"ULTRACEMCO", 1.2, "Cement"}, {"TATASTEEL", 1.2, "Metals"}, {"POWERGRID", 1.1, "Power"},
            {"INDIGO", 1.0, "Services"}, {"TMPV", 1.0, "Auto"}, {"TATAMOTORS", 1.0, "Auto"}, {"BAJAJFINSV", 0.9, "Financials"},
            {"ADANIPORTS", 0.9, "Services"}, {"ASIANPAINT", 0.9, "Consumer"}, {"TRENT", 0.9, "Consumer"}, {"HINDALCO", 0.9, "Metals"},
            {"JSWSTEEL", 0.8, "Metals"}, {"ONGC", 0.8, "Energy"}, {"GRASIM", 0.8, "Cement"}, {"SHRIRAMFIN", 0.8, "Financials"},
            {"TECHM", 0.8, "IT"}, {"JIOFIN", 0.8, "Financials"}, {"BAJAJ-AUTO", 0.8, "Auto"}, {"CIPLA", 0.7, "Healthcare"},
            {"SBILIFE", 0.7, "Financials"}, {"HDFCLIFE", 0.7, "Financials"}, {"COALINDIA", 0.7, "Energy"}, {"NESTLEIND", 0.7, "FMCG"},
            {"EICHERMOT", 0.7, "Auto"}, {"DRREDDY", 0.6, "Healthcare"}, {"TATACONSUM", 0.6, "FMCG"}, {"APOLLOHOSP", 0.6, "Healthcare"},
            {"MAXHEALTH", 0.6, "Healthcare"}, {"WIPRO", 0.6, "IT"}, {"ADANIENT", 0.5, "Metals"}};

    public static Map<String, double[]> approxWeights() {
        Map<String, double[]> m = new LinkedHashMap<>();
        for (Object[] r : APPROX) m.put((String) r[0], new double[]{(Double) r[1]});
        return m;
    }

    public static String approxSector(String sym) {
        for (Object[] r : APPROX) if (r[0].equals(sym)) return (String) r[2];
        return "Other";
    }

    /** NSE industry names → short sector names. */
    public static String shortSector(String industry) {
        if (industry == null || industry.trim().isEmpty()) return "";
        String s = industry.toLowerCase(Locale.US);
        if (s.contains("financial")) return "Financials";
        if (s.contains("information technology") || s.equals("it")) return "IT";
        if (s.contains("oil") || s.contains("gas") || s.contains("consumable fuels") || s.contains("energy")) return "Energy";
        if (s.contains("automobile") || s.contains("auto")) return "Auto";
        if (s.contains("fast moving") || s.contains("fmcg")) return "FMCG";
        if (s.contains("healthcare") || s.contains("pharma")) return "Healthcare";
        if (s.contains("metal") || s.contains("mining")) return "Metals";
        if (s.contains("construction materials") || s.contains("cement")) return "Cement";
        if (s.contains("construction") || s.contains("capital goods")) return "Infra & capital goods";
        if (s.contains("power")) return "Power";
        if (s.contains("telecom")) return "Telecom";
        if (s.contains("consumer")) return "Consumer";
        if (s.contains("services")) return "Services";
        return industry.trim();
    }
}

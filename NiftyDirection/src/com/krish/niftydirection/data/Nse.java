package com.krish.niftydirection.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.StringReader;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * NSE public data: FII / DII cash flows and the participant-wise open interest file
 * (how long or short FIIs are in index futures). Both come out in the evening.
 */
public final class Nse {
    private Nse() {}

    static {
        if (CookieHandler.getDefault() == null) CookieHandler.setDefault(new CookieManager(null, CookiePolicy.ACCEPT_ALL));
    }

    private static Map<String, String> headers(String referer) {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", Http.BROWSER_UA);
        h.put("Accept", "application/json,text/plain,*/*");
        h.put("Accept-Language", "en-IN,en;q=0.9");
        if (referer != null) h.put("Referer", referer);
        return h;
    }

    /** {fiiNet, diiNet} in ₹ crore plus the date, from NSE's FII/DII report. */
    public static class FiiDii { public double fii = Double.NaN, dii = Double.NaN; public String date = ""; }

    public static FiiDii fiiDii() throws Exception {
        // NSE needs its cookies first.
        try { Http.get("https://www.nseindia.com/reports/fii-dii", headers(null), 15000); } catch (Exception ignored) {}
        String body = Http.get("https://www.nseindia.com/api/fiidiiTradeReact", headers("https://www.nseindia.com/reports/fii-dii"), 15000);
        JSONArray a = new JSONArray(body);
        FiiDii r = new FiiDii();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            String cat = o.optString("category", "").toUpperCase(Locale.US);
            double net = num(o.optString("netValue", "NaN"));
            if (cat.startsWith("FII") || cat.startsWith("FPI")) { r.fii = net; r.date = o.optString("date", ""); }
            else if (cat.startsWith("DII")) r.dii = net;
        }
        return r;
    }

    /** FII long share (%) in index futures for the latest file and the one before it. */
    public static class FiiOi { public double longPct = Double.NaN, prevLongPct = Double.NaN; public String date = ""; }

    public static FiiOi participantOi() {
        FiiOi r = new FiiOi();
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"));
        SimpleDateFormat f = new SimpleDateFormat("ddMMyyyy", Locale.US), show = new SimpleDateFormat("dd-MMM", Locale.US);
        f.setTimeZone(c.getTimeZone()); show.setTimeZone(c.getTimeZone());
        int found = 0;
        for (int back = 0; back < 10 && found < 2; back++) {
            int dow = c.get(Calendar.DAY_OF_WEEK);
            if (dow != Calendar.SATURDAY && dow != Calendar.SUNDAY) {
                String url = ARCHIVES + "/content/nsccl/fao_participant_oi_" + f.format(c.getTime()) + ".csv";
                try {
                    double p = fiiLongPct(Http.get(url, headers("https://www.nseindia.com/"), 12000));
                    if (!Double.isNaN(p)) {
                        if (found == 0) { r.longPct = p; r.date = show.format(c.getTime()); } else r.prevLongPct = p;
                        found++;
                    }
                } catch (Exception ignored) { }
            }
            c.add(Calendar.DAY_OF_MONTH, -1);
        }
        return r;
    }

    /** NSE archive host (tests point it at a local mock). */
    public static String ARCHIVES = "https://nsearchives.nseindia.com";

    /** Participant OI file of one session (yyyy-MM-dd): {FII fut long share, FII options net, Client fut long share, Pro fut long share, DII fut long share}. Null if absent. */
    public static double[] participantDay(String date) {
        String ddmmyyyy = date.substring(8, 10) + date.substring(5, 7) + date.substring(0, 4);
        try { return participantRow(Http.get(ARCHIVES + "/content/nsccl/fao_participant_oi_" + ddmmyyyy + ".csv", headers("https://www.nseindia.com/"), 12000)); }
        catch (Exception e) { return null; }
    }

    static double[] participantRow(String csv) throws Exception {
        BufferedReader br = new BufferedReader(new StringReader(csv));
        String line;
        Map<String, Integer> col = null;
        Map<String, double[]> who = new java.util.HashMap<>();
        while ((line = br.readLine()) != null) {
            String[] p = line.split(",");
            for (int i = 0; i < p.length; i++) p[i] = p[i].replace("\"", "").trim();
            if (col == null) {
                if (p.length > 8 && p[0].equalsIgnoreCase("Client Type")) { col = new java.util.HashMap<>(); for (int i = 0; i < p.length; i++) col.put(p[i].toLowerCase(Locale.US), i); }
                continue;
            }
            Integer fl = col.get("future index long"), fs = col.get("future index short"), cl = col.get("option index call long"), pl = col.get("option index put long"),
                    cs = col.get("option index call short"), ps = col.get("option index put short");
            if (fl == null || fs == null || cl == null || pl == null || cs == null || ps == null || p.length <= Math.max(Math.max(fl, fs), Math.max(Math.max(cl, pl), Math.max(cs, ps)))) continue;
            double a = num(p[fl]), b = num(p[fs]), c = num(p[cl]), d = num(p[pl]), e = num(p[cs]), f = num(p[ps]);
            double opt = c + d + e + f;
            who.put(p[0].toUpperCase(Locale.US), new double[]{a + b > 0 ? a / (a + b) : Double.NaN, opt > 0 ? ((c - e) - (d - f)) / opt : Double.NaN});
        }
        double[] fii = who.get("FII"), cli = who.get("CLIENT"), pro = who.get("PRO"), dii = who.get("DII");
        if (fii == null || cli == null) return null;
        return new double[]{fii[0], fii[1], cli[0], pro == null ? Double.NaN : pro[0], dii == null ? Double.NaN : dii[0]};
    }

    /** Parses the participant OI csv: "Client Type,Future Index Long,Future Index Short,..." and returns FII long %. */
    static double fiiLongPct(String csv) throws Exception {
        BufferedReader br = new BufferedReader(new StringReader(csv));
        String line; int iLong = -1, iShort = -1;
        while ((line = br.readLine()) != null) {
            String[] p = line.split(",");
            for (int i = 0; i < p.length; i++) p[i] = p[i].replace("\"", "").trim();
            if (iLong < 0) {
                for (int i = 0; i < p.length; i++) {
                    if (p[i].equalsIgnoreCase("Future Index Long")) iLong = i;
                    if (p[i].equalsIgnoreCase("Future Index Short")) iShort = i;
                }
                continue;
            }
            if (p.length > Math.max(iLong, iShort) && p[0].toUpperCase(Locale.US).startsWith("FII")) {
                double l = num(p[iLong]), s = num(p[iShort]);
                return l + s > 0 ? l / (l + s) * 100 : Double.NaN;
            }
        }
        return Double.NaN;
    }

    static double num(String s) {
        try { return Double.parseDouble(s.replace(",", "").trim()); } catch (Exception e) { return Double.NaN; }
    }

    /** Nifty 50 free-float market cap of each member, from NSE's index page. symbol → ffmc. Null on failure. */
    public static Map<String, Double> ffmc() {
        try {
            try { Http.get("https://www.nseindia.com/market-data/live-equity-market", headers(null), 15000); } catch (Exception ignored) {}
            String body = Http.get("https://www.nseindia.com/api/equity-stockIndices?index=NIFTY%2050", headers("https://www.nseindia.com/market-data/live-equity-market"), 15000);
            JSONArray a = new JSONObject(body).getJSONArray("data");
            Map<String, Double> out = new java.util.LinkedHashMap<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                String sym = o.optString("symbol", "");
                if (o.optInt("priority", 0) == 1 || sym.equals("NIFTY 50")) continue;
                double f = o.optDouble("ffmc", Double.NaN);
                if (!sym.isEmpty() && f > 0) out.put(sym, f);
            }
            return out.size() >= 45 ? out : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Current Nifty 50 members from niftyindices.com as "SYMBOL,Industry" lines. Returns null on failure. */
    public static java.util.List<String> nifty50() {
        try {
            String csv = Http.get("https://www.niftyindices.com/IndexConstituent/ind_nifty50list.csv", headers(null), 15000);
            BufferedReader br = new BufferedReader(new StringReader(csv));
            String line = br.readLine();
            if (line == null) return null;
            String[] h = line.split(",");
            int col = -1, ind = -1;
            for (int i = 0; i < h.length; i++) {
                if (h[i].trim().equalsIgnoreCase("Symbol")) col = i;
                if (h[i].trim().equalsIgnoreCase("Industry")) ind = i;
            }
            if (col < 0) return null;
            java.util.List<String> out = new java.util.ArrayList<>();
            while ((line = br.readLine()) != null) {
                String[] p = line.split(",");
                if (p.length > col && !p[col].trim().isEmpty()) out.add(p[col].trim() + (ind >= 0 && p.length > ind ? "," + p[ind].trim() : ""));
            }
            return out.size() >= 45 ? out : null;
        } catch (Exception e) {
            return null;
        }
    }
}

package com.krish.niftydirection.forecast;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * The inputs of every forecast. Each one is computed ONLY from what was known after k completed 5-minute bars of session d:
 * bars 0..k-1 of that day, earlier sessions, and daily closes / global closes dated before that day.
 * Missing values are NaN (the model fills them with the training average).
 * Price moves are divided by Nifty's recent daily volatility so a 100-point move means the same in calm and wild markets.
 */
public final class Features {
    private Features() {}

    public static final String[] NAMES = {
            "Last 15 min move", "Last 60 min move", "Move since today's open", "Opening gap", "Place in today's range",
            "Distance from today's average price", "Today's swings vs VIX", "Time of day",
            "Yesterday's move", "Yesterday's close in its range", "Last 5 days' move", "Distance from 20-day average", "Distance from 50-day average",
            "Bank Nifty vs Nifty today", "Financials vs Nifty today", "India VIX change today", "India VIX level (1-year rank)",
            "Share of sectors up today", "Sectors pulling apart",
            "S&P 500 last session", "Nasdaq last session", "Asia (Nikkei + Hang Seng) last session", "Brent crude last session",
            "Rupee (USD/INR) last session", "US 10-year yield change", "Dollar index last session", "Weekly expiry day",
            "Bank Nifty vs Nifty yesterday", "Share of sectors up yesterday"};
    public static final int N = NAMES.length;

    /** Short description of which way a HIGH value of each input points, for the "why" lines. */
    public static final String[] HIGH = {
            "Nifty rose in the last 15 min", "Nifty rose in the last hour", "Nifty is above today's open", "Nifty opened with a gap up", "Nifty is near today's high",
            "Nifty is above today's average price", "today's swings are big for the VIX", "later in the day",
            "yesterday was an up day", "yesterday closed near its high", "the last 5 days were up", "Nifty is above its 20-day average", "Nifty is above its 50-day average",
            "Bank Nifty is stronger than Nifty", "Financials are stronger than Nifty", "India VIX is rising", "India VIX is high for the year",
            "most sectors are up", "sectors are moving very differently",
            "S&P 500 rose", "Nasdaq rose", "Asian markets rose", "crude oil rose",
            "the rupee weakened", "US yields rose", "the dollar rose", "it is weekly expiry day",
            "Bank Nifty beat Nifty yesterday", "most sectors rose yesterday"};

    /** Inputs grouped by what they describe (for plain-English reasons). */
    public static final int[][] GROUPS = {{0, 1, 2, 3, 4, 5, 7}, {8, 9, 10, 11, 12}, {13, 14, 27}, {6, 15, 16}, {17, 18, 28}, {19, 20, 21, 22, 23, 24, 25}, {26}};
    public static final String[] GROUP_NAMES = {"Today's price action", "Recent days and trend", "Banks and financials",
            "Volatility (VIX)", "Sector breadth", "Global markets overnight", "Weekly expiry day"};

    static final String[] GLOBAL_KEYS = {"S&P 500", "Nasdaq", "ASIA", "Brent crude", "USD/INR", "US 10Y yield", "Dollar index"};

    /** Recent daily volatility of Nifty (std of daily log returns over the last 20 sessions before `date`). */
    public static double sigma(History h, String date) {
        NavigableMap<String, double[]> past = h.niftyDaily.headMap(date, false);
        List<Double> r = new ArrayList<>();
        double prev = Double.NaN;
        for (double[] v : past.descendingMap().values()) {
            if (!Double.isNaN(prev) && v[3] > 0) r.add(Math.log(prev / v[3]));
            prev = v[3];
            if (r.size() >= 20) break;
        }
        if (r.size() < 8) return 0.01;
        double m = 0, s = 0;
        for (double x : r) m += x;
        m /= r.size();
        for (double x : r) s += (x - m) * (x - m);
        return Math.max(0.003, Math.sqrt(s / (r.size() - 1)));
    }

    /**
     * @param d session index in h.days
     * @param k completed bars (1..75); 0 = at the open (only the opening price is known)
     */
    public static double[] compute(History h, int d, int k, int[] sidx) {
        double[] f = new double[N];
        java.util.Arrays.fill(f, Double.NaN);
        History.Day day = h.days.get(d);
        String date = day.date;
        double sig = sigma(h, date);
        double O = day.open(), P = k == 0 ? O : day.closeAt(k);
        Map.Entry<String, double[]> pe = h.niftyDaily.lowerEntry(date);
        double PC = pe != null ? pe.getValue()[3] : Double.NaN;
        if (Double.isNaN(P) || P <= 0) return f;

        // ---- today's price action
        f[0] = ret(P, k >= 4 ? day.closeAt(k - 3) : O) / sig;
        f[1] = ret(P, k >= 13 ? day.closeAt(k - 12) : O) / sig;
        f[2] = ret(P, O) / sig;
        f[3] = ret(O, PC) / sig;
        double hi = Double.NEGATIVE_INFINITY, lo = Double.POSITIVE_INFINITY, tw = 0;
        int n = 0;
        for (int i = 0; i < k; i++) {
            if (Float.isNaN(day.c[i])) continue;
            hi = Math.max(hi, day.h[i]); lo = Math.min(lo, day.l[i]);
            tw += (day.h[i] + day.l[i] + day.c[i]) / 3.0; n++;
        }
        if (n > 0 && hi > lo) f[4] = (P - lo) / (hi - lo) - 0.5;
        if (n > 0) f[5] = ret(P, tw / n) / sig;
        double vixNow = Double.NaN;
        float[] vx = day.aux.get(History.VIX);
        if (vx != null) vixNow = History.last(vx, k);
        if (k >= 6 && !Double.isNaN(vixNow) && vixNow > 0) {
            double ss = 0; int m = 0;
            for (int i = 1; i < k; i++) if (!Float.isNaN(day.c[i]) && !Float.isNaN(day.c[i - 1])) { double r = Math.log(day.c[i] / day.c[i - 1]); ss += r * r; m++; }
            if (m >= 5) {
                double rvDay = Math.sqrt(ss / m * History.BARS);
                double ivDay = vixNow / 100.0 / Math.sqrt(252);
                f[6] = clip(Math.log(rvDay / ivDay), 2);
            }
        }
        f[7] = k / (double) History.BARS - 0.5;

        // ---- daily context (closes before today)
        NavigableMap<String, double[]> past = h.niftyDaily.headMap(date, false);
        if (pe != null) {
            double[] y = pe.getValue();
            Map.Entry<String, double[]> pp = h.niftyDaily.lowerEntry(pe.getKey());
            if (pp != null) f[8] = ret(y[3], pp.getValue()[3]) / sig;
            if (y[1] > y[2]) f[9] = (y[3] - y[2]) / (y[1] - y[2]) - 0.5;
            double[] closes = new double[Math.min(50, past.size())];
            int i = 0;
            for (double[] v : past.descendingMap().values()) { if (i >= closes.length) break; closes[i++] = v[3]; }
            if (closes.length > 5) f[10] = ret(closes[0], closes[5]) / (sig * Math.sqrt(5));
            if (closes.length >= 20) f[11] = ret(P, avg(closes, 20)) / (sig * Math.sqrt(5));
            if (closes.length >= 50) f[12] = ret(P, avg(closes, 50)) / (sig * Math.sqrt(10));
        }

        // ---- other Indian indices today
        double niftyDay = ret(P, PC);
        f[13] = relative(h, d, History.BANK, k, niftyDay, sidx) / sig;
        f[14] = relative(h, d, "SEC:Financials", k, niftyDay, sidx) / sig;
        Map.Entry<String, Double> vPrev = h.vixDaily.lowerEntry(date);
        if (vPrev != null && !Double.isNaN(vixNow) && vixNow > 0) f[15] = clip(Math.log(vixNow / vPrev.getValue()), 0.5);
        if (vPrev != null) {
            NavigableMap<String, Double> vp = h.vixDaily.headMap(date, false);
            if (vp.size() >= 60) {
                int below = 0, m = 0;
                for (double v : vp.descendingMap().values()) { if (m >= 250) break; m++; if (v < vPrev.getValue()) below++; }
                f[16] = below / (double) m - 0.5;
            }
        }
        List<Double> sec = new ArrayList<>();
        for (String s : h.sectors) {
            float[] a = day.aux.get(s);
            double pc = h.prevAuxClose(d, s, sidx);
            if (a == null || Double.isNaN(pc)) continue;
            double v = History.last(a, k);
            if (!Double.isNaN(v)) sec.add(ret(v, pc));
        }
        if (sec.size() >= 4) {
            int up = 0; double m = 0, s2 = 0;
            for (double v : sec) { if (v > 0) up++; m += v; }
            m /= sec.size();
            for (double v : sec) s2 += (v - m) * (v - m);
            f[17] = up / (double) sec.size() - 0.5;
            f[18] = Math.sqrt(s2 / sec.size()) / sig;
        }

        // ---- global: last finished session before today's Indian open
        for (int g = 0; g < GLOBAL_KEYS.length; g++) {
            String key = GLOBAL_KEYS[g];
            double v;
            if (key.equals("ASIA")) {
                double a = gchg(h, "Nikkei", date, false), b = gchg(h, "Hang Seng", date, false);
                v = Double.isNaN(a) ? b : Double.isNaN(b) ? a : (a + b) / 2;
            } else v = gchg(h, key, date, key.equals("US 10Y yield"));
            f[19 + g] = Double.isNaN(v) ? Double.NaN : clip(v, 8);
        }
        f[26] = h.expiries.contains(date) ? 1 : 0;

        // ---- yesterday's internals (known before today's open)
        if (pe != null) {
            Map.Entry<String, double[]> pp = h.niftyDaily.lowerEntry(pe.getKey());
            double nY = pp != null ? ret(pe.getValue()[3], pp.getValue()[3]) : Double.NaN;
            double b1 = h.prevAuxClose(d, History.BANK, sidx), b2 = d > 0 ? h.prevAuxClose(d - 1, History.BANK, sidx) : Double.NaN;
            if (!Double.isNaN(nY)) { double bY = ret(b1, b2); if (!Double.isNaN(bY)) f[27] = clip((bY - nY) / sig, 5); }
            if (d > 0) {
                int up = 0, m = 0;
                for (String sct : h.sectors) {
                    double a1 = h.prevAuxClose(d, sct, sidx), a2 = h.prevAuxClose(d - 1, sct, sidx);
                    if (Double.isNaN(a1) || Double.isNaN(a2)) continue;
                    m++; if (a1 > a2) up++;
                }
                if (m >= 4) f[28] = up / (double) m - 0.5;
            }
        }
        return f;
    }

    static double relative(History h, int d, String key, int k, double niftyDay, int[] sidx) {
        float[] a = h.days.get(d).aux.get(key);
        double pc = h.prevAuxClose(d, key, sidx);
        if (a == null || Double.isNaN(pc) || Double.isNaN(niftyDay)) return Double.NaN;
        double v = History.last(a, k);
        return Double.isNaN(v) ? Double.NaN : ret(v, pc) - niftyDay;
    }

    /** % change (or, for yields, the change in points × 10) between the last two closes dated before `date`. */
    static double gchg(History h, String key, String date, boolean diff) {
        TreeMap<String, Double> m = h.global.get(key);
        if (m == null) return Double.NaN;
        Map.Entry<String, Double> a = m.lowerEntry(date);
        if (a == null || Collectors.days(a.getKey(), date) > 5) return Double.NaN;   // stale series
        Map.Entry<String, Double> b = m.lowerEntry(a.getKey());
        if (b == null || b.getValue() == 0) return Double.NaN;
        return diff ? (a.getValue() - b.getValue()) * 10 : (a.getValue() - b.getValue()) / b.getValue() * 100;
    }

    static double ret(double a, double b) { return a > 0 && b > 0 ? Math.log(a / b) : Double.NaN; }
    static double avg(double[] v, int n) { double s = 0; for (int i = 0; i < n; i++) s += v[i]; return s / n; }
    static double clip(double v, double lim) { return Math.max(-lim, Math.min(lim, v)); }

    /** Tiny date helper (kept here so the forecast package stays free of Android and data classes). */
    static final class Collectors {
        static int days(String a, String b) {
            try {
                java.time.LocalDate x = java.time.LocalDate.parse(a), y = java.time.LocalDate.parse(b);
                return (int) java.time.temporal.ChronoUnit.DAYS.between(x, y);
            } catch (Exception e) { return 0; }
        }
    }
}

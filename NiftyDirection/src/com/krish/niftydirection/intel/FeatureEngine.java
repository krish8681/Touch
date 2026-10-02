package com.krish.niftydirection.intel;

import com.krish.niftydirection.forecast.Features;
import com.krish.niftydirection.forecast.History;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Feature Engine: every input of every horizon model, grouped by the kind of information it carries.
 *
 * Each value is computed ONLY from what was known after k completed 5-minute bars of session d (k = 0: at the open):
 * that day's bars 0..k-1, earlier sessions, and daily series dated before that day. Missing values are NaN.
 * Moves are scaled by Nifty's recent daily volatility, so a 1% move means the same in calm and wild markets.
 *
 * Inputs 0..28 are the original forecaster's (forecast.Features) — one code path for old and new.
 */
public final class FeatureEngine {
    private FeatureEngine() {}

    public static final String TECH = "Technical", TREND = "Trend", SECTOR = "Sector & banks", VOL = "Volatility", GLOBAL = "Global markets",
            FX = "Currencies", BONDS = "Bonds & rates", COMMOD = "Commodities", CAL = "Calendar", CHAINS = "Cross-market chains", LEADERS = "Leaders & breadth", POSITION = "Positioning (NSE)";

    /** Ten heaviest Nifty members (5-minute history key "STK:<symbol>") and their approximate index weights. */
    public static final String[][] LEADER_STOCKS = {{"HDFCBANK", "0.13"}, {"RELIANCE", "0.09"}, {"ICICIBANK", "0.09"}, {"INFY", "0.05"}, {"BHARTIARTL", "0.05"},
            {"LT", "0.04"}, {"ITC", "0.035"}, {"TCS", "0.03"}, {"SBIN", "0.03"}, {"AXISBANK", "0.03"}};
    /** NSE participant-wise open interest, one value per session (key in History.global, dated by the session it describes). */
    public static final String POI_FII_FUT = "POI:FII index futures long share", POI_FII_OPT = "POI:FII index options net",
            POI_CLIENT_FUT = "POI:Client index futures long share", POI_PRO_FUT = "POI:Pro index futures long share", POI_DII_FUT = "POI:DII index futures long share";

    static final String[] EXTRA_NAMES = {
            // 29.. technical (5-minute bars, yesterday's bars used as warm-up)
            "RSI (14 × 5 min)", "MACD histogram (5 min)", "Bollinger %b (20 × 5 min)", "Stochastic %K (14 × 5 min)", "Rate of change (30 min)",
            "Efficiency of the last hour's move", "Recent bar range vs normal",
            // 36.. trend
            "Daily RSI (14)", "Efficiency of the last 20 days' move", "Distance from 200-day average",
            // 39.. volatility
            "India VIX 5-day change", "US VIX last session", "Nifty realised vol vs VIX",
            // 42.. global
            "Dow last session", "Russell 2000 last session", "DAX last session", "FTSE 100 last session", "CAC 40 last session",
            "Kospi last session", "Shanghai last session", "ASX 200 last session", "Taiwan last session", "US futures last session", "Global risk score",
            // 53.. currencies
            "EUR/USD last session", "USD/JPY last session", "USD/CNY last session", "Rupee 5-day change",
            // 57.. bonds
            "US 13-week yield change", "US 5-year yield change", "US 30-year yield change", "US curve slope (10Y − 13W)", "US curve slope 5-day change",
            "US 10-year yield 5-day change",
            // 63.. commodities
            "WTI crude last session", "Gold last session", "Silver last session", "Copper last session", "Brent 5-day change", "Crude shock score",
            // 69.. calendar
            "Day of the week",
            // 70.. cross-market chains (learnt betas, see CrossMarket)
            "Oil shock chain (Brent → rupee → Nifty)", "Rates & dollar chain (US 10Y → DXY → rupee → Nifty)",
            "US rates → tech chain (US 10Y → Nasdaq → Nifty)", "Consensus implied move from 8 drivers",
            // 74.. intraday extras
            "Place vs the opening 30-min range", "Share of the opening gap filled", "India VIX change (last 30 min)", "Bank Nifty vs Nifty (last 30 min)",
            // 78.. leaders & breadth (10 heaviest stocks)
            "Heavyweights up today (weighted share)", "Heavyweights vs Nifty today", "Heavyweights vs Nifty (last 30 min)", "Heavyweights pulling apart",
            // 82.. positioning (NSE participant-wise open interest, previous session)
            "FII index futures long share", "FII futures long share 1-day change", "FII index options net (calls − puts)",
            "Client index futures long share", "Pro futures long share 1-day change", "DII index futures long share"};

    static final String[] EXTRA_HIGH = {
            "RSI is high (overbought side)", "MACD is turning up", "price is near the upper Bollinger band", "price is near the top of its recent range",
            "price rose over the last 30 min", "the last hour moved steadily up", "bars are wider than normal",
            "daily RSI is high", "the last 20 days trended up", "Nifty is above its 200-day average",
            "India VIX rose over 5 days", "US VIX rose", "Nifty is moving more than VIX implies",
            "Dow rose", "US small caps rose", "DAX rose", "FTSE rose", "CAC rose", "Kospi rose", "Shanghai rose", "ASX rose", "Taiwan rose", "US futures rose",
            "world markets are risk-on",
            "the euro rose", "the yen weakened", "the yuan weakened", "the rupee weakened over 5 days",
            "US short rates rose", "US 5-year yield rose", "US 30-year yield rose", "the US curve is steep", "the US curve steepened", "US 10-year yield rose over 5 days",
            "WTI rose", "gold rose", "silver rose", "copper rose", "Brent rose over 5 days", "a crude shock is building",
            "later in the week",
            "the oil chain points up", "the rates & dollar chain points up", "the US rates → tech chain points up", "world drivers imply a rise",
            "price is above the opening range", "the opening gap is being filled", "India VIX rose in the last 30 min", "Bank Nifty led in the last 30 min",
            "most heavyweights are up", "heavyweights are stronger than Nifty", "heavyweights led in the last 30 min", "heavyweights are moving apart",
            "FIIs hold more index longs", "FIIs added index longs", "FIIs are net call-side in options", "clients hold more index longs",
            "pros added index longs", "DIIs hold more index longs"};

    public static final String[] NAMES, HIGH;
    public static final int N;
    static {
        NAMES = concat(Features.NAMES, EXTRA_NAMES);
        HIGH = concat(Features.HIGH, EXTRA_HIGH);
        N = NAMES.length;
    }

    /** Feature groups (indices into the full vector). One model per group per horizon. */
    public static final String[] GROUP_NAMES = {TECH, TREND, SECTOR, VOL, GLOBAL, FX, BONDS, COMMOD, CAL, CHAINS, LEADERS, POSITION};
    public static final int[][] GROUPS = {
            {0, 1, 2, 3, 4, 5, 29, 30, 31, 32, 33, 34, 35, 74, 75},
            {8, 9, 10, 11, 12, 36, 37, 38},
            {13, 14, 17, 18, 27, 28, 77},
            {6, 15, 16, 39, 40, 41, 76},
            {19, 20, 21, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, 52},
            {23, 25, 53, 54, 55, 56},
            {24, 57, 58, 59, 60, 61, 62},
            {22, 63, 64, 65, 66, 67, 68},
            {7, 26, 69},
            {70, 71, 72, 73},
            {78, 79, 80, 81},
            {82, 83, 84, 85, 86, 87}};

    public static int groupOf(int feature) {
        for (int g = 0; g < GROUPS.length; g++) for (int j : GROUPS[g]) if (j == feature) return g;
        return -1;
    }

    // ------------------------------------------------------------------ compute

    public static double[] compute(History h, int d, int k, int[] sidx) {
        double[] f = Arrays.copyOf(Features.compute(h, d, k, sidx), N);
        for (int i = Features.N; i < N; i++) f[i] = Double.NaN;
        History.Day day = h.days.get(d);
        String date = day.date;
        double P = k == 0 ? day.open() : day.closeAt(k);
        if (Double.isNaN(P) || P <= 0) return f;
        double sig = Features.sigma(h, date), sigBar = sig / Math.sqrt(History.BARS);

        // ---- technical: yesterday's bars (if it is the previous session) + today's finished bars
        List<double[]> bars = new ArrayList<>();   // {h, l, c}
        if (d > 0 && sidx[d] - sidx[d - 1] == 1) addBars(bars, h.days.get(d - 1), History.BARS);
        addBars(bars, day, k);
        int n = bars.size();
        if (n >= 15) {
            double last = bars.get(n - 1)[2];
            f[29] = (rsi(bars, 14) - 50) / 50;
            if (n >= 35) f[30] = clip(macdHist(bars) / (last * sigBar), 5);
            double[] mb = n >= 20 ? meanSd(bars, 20) : new double[]{0, 0};
            if (mb[1] > 0) f[31] = clip((last - (mb[0] - 2 * mb[1])) / (4 * mb[1]) - 0.5, 1.5);
            double hh = Double.NEGATIVE_INFINITY, ll = Double.POSITIVE_INFINITY;
            for (int i = n - 14; i < n; i++) { hh = Math.max(hh, bars.get(i)[0]); ll = Math.min(ll, bars.get(i)[1]); }
            if (hh > ll) f[32] = (last - ll) / (hh - ll) - 0.5;
            f[33] = clip(ret(last, bars.get(n - 7)[2]) / (sigBar * Math.sqrt(6)), 5);
            double path = 0;
            for (int i = n - 12; i < n; i++) path += Math.abs(bars.get(i)[2] - bars.get(i - 1)[2]);
            if (path > 0) f[34] = (last - bars.get(n - 13)[2]) / path;
            double rg = 0;
            for (int i = n - 6; i < n; i++) rg += bars.get(i)[0] - bars.get(i)[1];
            f[35] = clip(Math.log(Math.max(1e-9, rg / 6 / (last * sigBar))), 3);
        }

        // ---- trend (daily closes before today)
        NavigableMap<String, double[]> past = h.niftyDaily.headMap(date, false);
        double[] dc = new double[Math.min(220, past.size())];
        int i0 = dc.length - 1;
        for (double[] v : past.descendingMap().values()) { if (i0 < 0) break; dc[i0--] = v[3]; }   // oldest first
        if (dc.length >= 15) f[36] = (rsiSeries(dc, 14) - 50) / 50;
        if (dc.length >= 21) {
            double path = 0;
            for (int i = dc.length - 20; i < dc.length; i++) path += Math.abs(dc[i] - dc[i - 1]);
            if (path > 0) f[37] = (dc[dc.length - 1] - dc[dc.length - 21]) / path;
        }
        if (dc.length >= 200) {
            double s = 0;
            for (int i = dc.length - 200; i < dc.length; i++) s += dc[i];
            f[38] = clip(ret(P, s / 200) / (sig * Math.sqrt(20)), 5);
        }

        // ---- volatility
        NavigableMap<String, Double> vp = h.vixDaily.headMap(date, false);
        if (vp.size() >= 6) {
            Double[] vv = vp.descendingMap().values().toArray(new Double[0]);
            if (vv[0] > 0 && vv[5] > 0) f[39] = clip(Math.log(vv[0] / vv[5]), 1);
            f[41] = clip(Math.log(sig * Math.sqrt(252) * 100 / vv[0]), 2);
        }
        f[40] = scaled(chg(h, Markets.USVIX, date, 1), 10, 5);

        // ---- global
        String[] gl = {Markets.DOW, Markets.RUSSELL, Markets.DAX, Markets.FTSE, Markets.CAC, Markets.KOSPI, Markets.SHANGHAI, Markets.ASX, Markets.TAIWAN, Markets.USFUT};
        for (int g = 0; g < gl.length; g++) f[42 + g] = scaled(chg(h, gl[g], date, 1), 1, 8);
        f[52] = globalRisk(h, date);

        // ---- currencies
        f[53] = scaled(chg(h, Markets.EURUSD, date, 1), 1, 5);
        f[54] = scaled(chg(h, Markets.USDJPY, date, 1), 1, 5);
        f[55] = scaled(chg(h, Markets.USDCNY, date, 1), 1, 5);
        f[56] = scaled(chg(h, Markets.USDINR, date, 5), 1, 5);

        // ---- bonds
        f[57] = scaled(chg(h, Markets.US13W, date, 1), 1, 8);
        f[58] = scaled(chg(h, Markets.US5Y, date, 1), 1, 8);
        f[59] = scaled(chg(h, Markets.US30Y, date, 1), 1, 8);
        double y10 = level(h, Markets.US10Y, date), y13 = level(h, Markets.US13W, date);
        if (!Double.isNaN(y10) && !Double.isNaN(y13)) f[60] = clip(y10 - y13, 5);
        double y10b = levelBack(h, Markets.US10Y, date, 5), y13b = levelBack(h, Markets.US13W, date, 5);
        if (!Double.isNaN(f[60]) && !Double.isNaN(y10b) && !Double.isNaN(y13b)) f[61] = clip(((y10 - y13) - (y10b - y13b)) * 10, 8);
        f[62] = scaled(chg(h, Markets.US10Y, date, 5), 1, 10);

        // ---- commodities
        f[63] = scaled(chg(h, Markets.WTI, date, 1), 1, 10);
        f[64] = scaled(chg(h, Markets.GOLD, date, 1), 1, 8);
        f[65] = scaled(chg(h, Markets.SILVER, date, 1), 1, 10);
        f[66] = scaled(chg(h, Markets.COPPER, date, 1), 1, 8);
        f[67] = scaled(chg(h, Markets.BRENT, date, 5), 1, 20);
        f[68] = crudeShock(h, date);

        // ---- calendar
        try {
            int dow = java.time.LocalDate.parse(date).getDayOfWeek().getValue();   // 1 = Monday
            f[69] = (Math.min(5, dow) - 1) / 4.0 - 0.5;
        } catch (Exception ignored) { }

        // ---- cross-market chains: implied Nifty % from learnt betas, in units of Nifty's daily volatility
        double[] xm = CrossMarket.implied(h, date);
        for (int i = 0; i < 4; i++) f[70 + i] = Double.isNaN(xm[i]) ? Double.NaN : clip(xm[i] / (sig * 100), 4);

        // ---- intraday extras
        double O = day.open();
        Map.Entry<String, double[]> pcE = h.niftyDaily.lowerEntry(date);
        double PC = pcE == null ? Double.NaN : pcE.getValue()[3];
        if (k >= 7) {
            double hi = Double.NEGATIVE_INFINITY, lo = Double.POSITIVE_INFINITY;
            for (int i = 0; i < 6; i++) if (!Float.isNaN(day.c[i])) { hi = Math.max(hi, day.h[i]); lo = Math.min(lo, day.l[i]); }
            if (hi > lo) f[74] = clip((P - (hi + lo) / 2) / ((hi - lo) / 2), 4);
        }
        if (k >= 1 && O > 0 && PC > 0 && Math.abs(O - PC) > 0.1 * sig * PC) f[75] = clip((O - P) / (O - PC), 2);
        if (k >= 7) {
            float[] vx = day.aux.get(History.VIX);
            if (vx != null) { double a = History.last(vx, k), b = History.last(vx, k - 6); if (a > 0 && b > 0) f[76] = clip(Math.log(a / b) * 10, 3); }
            float[] bk = day.aux.get(History.BANK);
            double n6 = day.closeAt(k - 6);
            if (bk != null && n6 > 0) {
                double a = History.last(bk, k), b = History.last(bk, k - 6);
                if (a > 0 && b > 0) f[77] = clip((Math.log(a / b) - Math.log(P / n6)) / (sigBar * Math.sqrt(6)), 5);
            }
        }

        // ---- leaders & breadth: the ten heaviest stocks
        if (k >= 1) {
            double wUp = 0, wSum = 0, wRet = 0, wRet6 = 0, w6 = 0, s1 = 0, s2 = 0;
            int cnt = 0;
            double nRet = PC > 0 ? Math.log(P / PC) : Double.NaN, n6 = k >= 7 ? day.closeAt(k - 6) : Double.NaN;
            for (String[] st : LEADER_STOCKS) {
                String key = "STK:" + st[0];
                float[] a = day.aux.get(key);
                if (a == null) continue;
                double w = Double.parseDouble(st[1]), now = History.last(a, k), prev = h.prevAuxClose(d, key, sidx);
                if (!(now > 0) || !(prev > 0)) continue;
                double rr = Math.log(now / prev);
                wSum += w; wRet += w * rr; if (rr > 0) wUp += w;
                s1 += rr; s2 += rr * rr; cnt++;
                double b6 = k >= 7 ? History.last(a, k - 6) : Double.NaN;
                if (b6 > 0) { wRet6 += w * Math.log(now / b6); w6 += w; }
            }
            if (cnt >= 5 && wSum > 0) {
                f[78] = wUp / wSum - 0.5;
                if (!Double.isNaN(nRet)) f[79] = clip((wRet / wSum - nRet) / sig, 5);
                if (w6 > 0 && n6 > 0) f[80] = clip((wRet6 / w6 - Math.log(P / n6)) / (sigBar * Math.sqrt(6)), 5);
                double m = s1 / cnt;
                f[81] = clip(Math.sqrt(Math.max(0, s2 / cnt - m * m)) / sig, 5);
            }
        }

        // ---- positioning: NSE participant-wise OI of the previous session
        f[82] = prevLevel(h, POI_FII_FUT, date, 0);
        double fiiB = prevLevel(h, POI_FII_FUT, date, 1);
        if (!Double.isNaN(f[82]) && !Double.isNaN(fiiB)) f[83] = clip((f[82] - fiiB) * 20, 5);
        f[84] = prevLevel(h, POI_FII_OPT, date, 0);
        f[85] = prevLevel(h, POI_CLIENT_FUT, date, 0);
        double pa = prevLevel(h, POI_PRO_FUT, date, 0), pb = prevLevel(h, POI_PRO_FUT, date, 1);
        if (!Double.isNaN(pa) && !Double.isNaN(pb)) f[86] = clip((pa - pb) * 20, 5);
        f[87] = prevLevel(h, POI_DII_FUT, date, 0);
        return f;
    }

    /** Value of a once-a-day series dated before `date` (back = entries earlier), NaN when missing or stale (> 7 days). */
    static double prevLevel(History h, String key, String date, int back) {
        TreeMap<String, Double> m = h.global.get(key);
        if (m == null) return Double.NaN;
        Map.Entry<String, Double> a = m.lowerEntry(date);
        if (a == null || days(a.getKey(), date) > 7) return Double.NaN;
        for (int i = 0; i < back && a != null; i++) a = m.lowerEntry(a.getKey());
        return a == null ? Double.NaN : a.getValue();
    }

    /** Composite of overnight equity moves (%), less a US VIX jump. Positive = risk-on. Also used by the regime engine. */
    public static double globalRisk(History h, String date) {
        String[] eq = {Markets.SP, Markets.NASDAQ, Markets.DAX, Markets.NIKKEI, Markets.HSI, Markets.USFUT};
        double s = 0; int n = 0;
        for (String k : eq) { double v = chg(h, k, date, 1); if (!Double.isNaN(v)) { s += clip(v, 5); n++; } }
        if (n < 2) return Double.NaN;
        double r = s / n;
        double vx = chg(h, Markets.USVIX, date, 1);
        if (!Double.isNaN(vx)) r -= 0.05 * clip(vx, 40);
        return clip(r, 6);
    }

    /** Brent's 1-day and 5-day move, made worse by a weaker rupee (India imports most of its oil). Positive = shock building. */
    public static double crudeShock(History h, String date) {
        double b1 = chg(h, Markets.BRENT, date, 1), b5 = chg(h, Markets.BRENT, date, 5), inr = chg(h, Markets.USDINR, date, 1);
        if (Double.isNaN(b1) && Double.isNaN(b5)) return Double.NaN;
        double s = (Double.isNaN(b1) ? 0 : clip(b1, 10) / 2) + (Double.isNaN(b5) ? 0 : clip(b5, 20) / 4) + (Double.isNaN(inr) ? 0 : clip(inr, 3) * 2);
        return clip(s, 8);
    }

    // ------------------------------------------------------------------ helpers

    static void addBars(List<double[]> out, History.Day d, int k) {
        for (int i = 0; i < Math.min(k, History.BARS); i++) if (!Float.isNaN(d.c[i])) out.add(new double[]{d.h[i], d.l[i], d.c[i]});
    }

    static double rsi(List<double[]> b, int n) {
        double up = 0, dn = 0;
        for (int i = b.size() - n; i < b.size(); i++) { double x = b.get(i)[2] - b.get(i - 1)[2]; if (x > 0) up += x; else dn -= x; }
        return up + dn == 0 ? 50 : 100 * up / (up + dn);
    }

    static double rsiSeries(double[] c, int n) {
        double up = 0, dn = 0;
        for (int i = c.length - n; i < c.length; i++) { double x = c[i] - c[i - 1]; if (x > 0) up += x; else dn -= x; }
        return up + dn == 0 ? 50 : 100 * up / (up + dn);
    }

    static double macdHist(List<double[]> b) {
        double e12 = b.get(0)[2], e26 = e12, sig = 0;
        double a12 = 2 / 13.0, a26 = 2 / 27.0, a9 = 2 / 10.0;
        for (int i = 1; i < b.size(); i++) {
            double c = b.get(i)[2];
            e12 += a12 * (c - e12); e26 += a26 * (c - e26);
            double m = e12 - e26;
            sig = i == 1 ? m : sig + a9 * (m - sig);
        }
        return (e12 - e26) - sig;
    }

    static double[] meanSd(List<double[]> b, int n) {
        double s = 0, s2 = 0;
        for (int i = b.size() - n; i < b.size(); i++) { double c = b.get(i)[2]; s += c; s2 += c * c; }
        double m = s / n;
        return new double[]{m, Math.sqrt(Math.max(0, s2 / n - m * m))};
    }

    /** Change between the last close dated before `date` and the close `back` entries earlier (% or, for yields, points × 10). */
    public static double chg(History h, String key, String date, int back) {
        TreeMap<String, Double> m = h.global.get(key);
        if (m == null) return Double.NaN;
        Map.Entry<String, Double> a = m.lowerEntry(date);
        if (a == null || days(a.getKey(), date) > 5) return Double.NaN;   // stale series
        Map.Entry<String, Double> b = a;
        for (int i = 0; i < back && b != null; i++) b = m.lowerEntry(b.getKey());
        if (b == null || b.getValue() == 0) return Double.NaN;
        return Markets.isYield(key) ? (a.getValue() - b.getValue()) * 10 : (a.getValue() - b.getValue()) / b.getValue() * 100;
    }

    static double level(History h, String key, String date) { return levelBack(h, key, date, 0); }

    static double levelBack(History h, String key, String date, int back) {
        TreeMap<String, Double> m = h.global.get(key);
        if (m == null) return Double.NaN;
        Map.Entry<String, Double> a = m.lowerEntry(date);
        if (a == null || days(a.getKey(), date) > 5) return Double.NaN;
        for (int i = 0; i < back && a != null; i++) a = m.lowerEntry(a.getKey());
        return a == null ? Double.NaN : a.getValue();
    }

    static double scaled(double v, double div, double lim) { return Double.isNaN(v) ? Double.NaN : clip(v / div, lim); }
    static double ret(double a, double b) { return a > 0 && b > 0 ? Math.log(a / b) : Double.NaN; }
    static double clip(double v, double lim) { return Double.isNaN(v) ? v : Math.max(-lim, Math.min(lim, v)); }

    static int days(String a, String b) {
        try { return (int) java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.parse(a), java.time.LocalDate.parse(b)); }
        catch (Exception e) { return 0; }
    }

    static String[] concat(String[] a, String[] b) {
        String[] o = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, o, a.length, b.length);
        return o;
    }
}

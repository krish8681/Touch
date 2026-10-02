package com.krish.niftydirection.data;

import com.krish.niftydirection.engine.Chain;
import com.krish.niftydirection.model.OptionRow;
import com.krish.niftydirection.model.Quote;
import com.krish.niftydirection.model.Snapshot;

import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Live market recorder: data Kite only gives live (no history), written every few minutes during the session so the app
 * builds its own history for future models — option chain (PCR, ATM IV, straddle, OI build-up near the money, walls,
 * max pain), futures (basis, OI, order-book imbalance), breadth of the 50 stocks and sectors, VIX, GIFT Nifty, FII data.
 * One CSV a month in files/recorder/. Included in Export.
 */
public final class Recorder2 {
    private Recorder2() {}

    public static final String[] COLUMNS = {"date", "time", "spot", "fut", "basis_pts", "fut_oi", "fut_oi_change_pct", "fut_book_imbalance",
            "vix", "pcr", "atm_strike", "atm_iv", "atm_straddle", "ce_oi_change_near", "pe_oi_change_near", "call_wall", "put_wall", "max_pain",
            "stocks_up_share", "stocks_weighted_move_pct", "sectors_up_share", "gift_nifty", "fii_cash_cr", "dii_cash_cr", "fii_index_long_pct",
            "next_expiry_atm_iv"};
    static final long MIN_GAP_MS = 4 * 60_000L;
    static long lastAt;

    /** Appends one row when the session is live (at most every 4 minutes). Returns true if written. */
    public static synchronized boolean record(File base, Snapshot s) {
        if (!s.live || s.nifty == null || !s.nifty.ok() || s.time - lastAt < MIN_GAP_MS) return false;
        try {
            File d = new File(base, "recorder");
            d.mkdirs();
            File f = new File(d, "rec_" + s.today.substring(0, 7) + ".csv");
            boolean head = !f.exists();
            try (FileWriter w = new FileWriter(f, true)) {
                if (head) w.write(String.join(",", COLUMNS) + "\n");
                w.write(row(s) + "\n");
            }
            lastAt = s.time;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static String row(Snapshot s) {
        double spot = s.spot();
        List<String> v = new ArrayList<>();
        v.add(s.today);
        v.add(String.format(Locale.US, "%d:%02d", s.minute / 60, s.minute % 60));
        v.add(n(spot, 2));
        Quote f = s.fut;
        v.add(f != null && f.ok() ? n(f.last, 2) : "");
        v.add(f != null && f.ok() ? n(f.last - spot, 2) : "");
        double oi = (f != null ? f.oi : 0) + (s.futNext != null ? s.futNext.oi : 0);
        v.add(oi > 0 ? n(oi, 0) : "");
        v.add(oi > 0 && s.futPrevOi > 0 ? n((oi / s.futPrevOi - 1) * 100, 3) : "");
        v.add(f != null && f.buyQty + f.sellQty > 0 ? n((f.buyQty - f.sellQty) / (f.buyQty + f.sellQty), 4) : "");
        v.add(s.vix != null && s.vix.ok() ? n(s.vix.last, 2) : "");
        if (!s.chain.isEmpty()) {
            double atm = Math.round(spot / s.strikeStep) * s.strikeStep;
            OptionRow a = null;
            double ceD = 0, peD = 0;
            for (OptionRow r : s.chain) {
                if (Math.abs(r.strike - atm) < 0.01) a = r;
                if (Math.abs(r.strike - atm) <= 2 * s.strikeStep + 0.01) { ceD += r.ceDoi(); peD += r.peDoi(); }
            }
            v.add(n(Chain.pcr(s.chain), 4));
            v.add(n(atm, 0));
            v.add(n(Chain.atmIv(s.chain, spot), 2));
            v.add(a != null ? n(a.ceLtp + a.peLtp, 2) : "");
            v.add(Chain.anyPrev(s.chain) ? n(ceD, 0) : "");
            v.add(Chain.anyPrev(s.chain) ? n(peD, 0) : "");
            OptionRow cw = Chain.ceWall(s.chain, spot), pw = Chain.peWall(s.chain, spot);
            v.add(cw != null ? n(cw.strike, 0) : "");
            v.add(pw != null ? n(pw.strike, 0) : "");
            v.add(n(Chain.maxPain(s.chain), 0));
        } else for (int i = 0; i < 9; i++) v.add("");
        int up = 0, cnt = 0;
        double wm = 0, ws = 0;
        for (Quote q : s.stocks) {
            if (!q.ok() || q.prevClose <= 0) continue;
            cnt++; if (q.last > q.prevClose) up++;
            String sym = q.symbol.contains(":") ? q.symbol.substring(q.symbol.indexOf(':') + 1) : q.symbol;
            double w = s.weights.getOrDefault(sym, 0.0);
            wm += w * q.pct(); ws += w;
        }
        v.add(cnt > 0 ? n(up / (double) cnt, 3) : "");
        v.add(ws > 0 ? n(wm / ws, 3) : "");
        int su = 0, sc = 0;
        for (Quote q : s.sectors.values()) if (q != null && q.ok()) { sc++; if (q.pct() > 0) su++; }
        v.add(sc > 0 ? n(su / (double) sc, 3) : "");
        v.add(n(s.giftNifty, 2));
        v.add(n(s.fiiCash, 0));
        v.add(n(s.diiCash, 0));
        v.add(n(s.fiiIdxLong, 2));
        v.add(s.chain2.isEmpty() ? "" : n(Chain.atmIv(s.chain2, spot), 2));
        return String.join(",", v);
    }

    static String n(double x, int dp) { return Double.isNaN(x) || Double.isInfinite(x) ? "" : String.format(Locale.US, "%." + dp + "f", x); }

    /** All recorded months as one CSV (header once), for Export. */
    public static String all(File base) {
        File d = new File(base, "recorder");
        File[] fs = d.listFiles((x, n) -> n.startsWith("rec_") && n.endsWith(".csv"));
        StringBuilder b = new StringBuilder(String.join(",", COLUMNS)).append('\n');
        if (fs == null) return b.toString();
        Arrays.sort(fs);
        for (File f : fs) {
            try {
                String[] lines = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).split("\n");
                for (int i = 1; i < lines.length; i++) if (!lines[i].trim().isEmpty()) b.append(lines[i]).append('\n');
            } catch (Exception ignored) { }
        }
        return b.toString();
    }

    /** Sessions recorded so far (distinct dates). */
    public static int sessions(File base) {
        String[] lines = all(base).split("\n");
        java.util.Set<String> d = new java.util.HashSet<>();
        for (int i = 1; i < lines.length; i++) { int c = lines[i].indexOf(','); if (c > 0) d.add(lines[i].substring(0, c)); }
        return d.size();
    }
}

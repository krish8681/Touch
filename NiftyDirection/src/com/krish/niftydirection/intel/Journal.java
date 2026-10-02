package com.krish.niftydirection.intel;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Trade journal + risk guard.
 *
 * Journal: the trades YOU actually took ("I took this"), with your real fills, so real results can be compared with the
 * paper trade of the same signal. P&L uses the same futures charges as the replay.
 *
 * Risk guard: on top of the trade gate, blocks new "act" signals when the day has gone badly or the moment is risky —
 * daily loss limit, maximum trades per day, a cool-down after a losing trade, and no entries in the first / last minutes.
 */
public final class Journal {
    private Journal() {}

    public static final class Entry {
        public String id = "", date = "", horizon = "";
        public int dir, lots = 1, lot = 65;
        public double entry = Double.NaN, exit = Double.NaN, signalProb = Double.NaN, paperEntry = Double.NaN;
        public long entryAt, exitAt;
        public boolean open() { return Double.isNaN(exit); }
        /** Net ₹ after charges (NaN while open). */
        public double pnl() {
            if (open() || Double.isNaN(entry)) return Double.NaN;
            return (dir * (exit - entry) * lot - Validator.charges(entry, exit, dir, lot)) * lots;
        }
        public double points() { return open() ? Double.NaN : dir * (exit - entry); }
    }

    /** Risk-guard settings (Settings → Risk guard). */
    public static final class Guard {
        public boolean on = true;
        public double maxLossRupees = 3000;
        public int maxTrades = 3, coolMin = 30, noFirstMin = 15, noLastMin = 30;
    }

    /** Reasons why no new trade may be opened now (empty = allowed). */
    public static List<String> blocks(List<Entry> journal, String today, int minute, long now, Guard g) {
        List<String> out = new ArrayList<>();
        if (g == null || !g.on) return out;
        int open = 9 * 60 + 15, close = 15 * 60 + 30;
        if (minute < open + g.noFirstMin) out.add(String.format(Locale.US, "no new entries in the first %d minutes (until %s)", g.noFirstMin, hm(open + g.noFirstMin)));
        if (minute > close - g.noLastMin) out.add(String.format(Locale.US, "no new entries in the last %d minutes (after %s)", g.noLastMin, hm(close - g.noLastMin)));
        double realized = 0; int taken = 0; long lastLossAt = 0;
        for (Entry e : journal) {
            if (!today.equals(e.date)) continue;
            taken++;
            double p = e.pnl();
            if (!Double.isNaN(p)) { realized += p; if (p < 0) lastLossAt = Math.max(lastLossAt, e.exitAt); }
        }
        if (realized <= -g.maxLossRupees) out.add(String.format(Locale.US, "daily loss limit reached (₹%,.0f today, limit ₹%,.0f)", realized, g.maxLossRupees));
        if (taken >= g.maxTrades) out.add(String.format(Locale.US, "%d trades taken today (limit %d)", taken, g.maxTrades));
        if (lastLossAt > 0 && now - lastLossAt < g.coolMin * 60_000L)
            out.add(String.format(Locale.US, "cool-down after a loss: %d min left", (g.coolMin * 60_000L - (now - lastLossAt)) / 60_000L + 1));
        return out;
    }

    static String hm(int m) { return String.format(Locale.US, "%d:%02d", m / 60, m % 60); }

    /** {trades, wins, net ₹} for a day (null = all days), closed trades only. */
    public static double[] summary(List<Entry> journal, String day) {
        double n = 0, w = 0, net = 0;
        for (Entry e : journal) {
            if (day != null && !day.equals(e.date)) continue;
            double p = e.pnl();
            if (Double.isNaN(p)) continue;
            n++; net += p; if (p > 0) w++;
        }
        return new double[]{n, w, net};
    }

    // ------------------------------------------------------------------ storage

    public static JSONArray toJson(List<Entry> l) throws Exception {
        JSONArray a = new JSONArray();
        for (Entry e : l) {
            JSONObject o = new JSONObject().put("id", e.id).put("date", e.date).put("h", e.horizon).put("dir", e.dir).put("lots", e.lots).put("lot", e.lot)
                    .put("entryAt", e.entryAt).put("exitAt", e.exitAt);
            if (!Double.isNaN(e.entry)) o.put("entry", e.entry);
            if (!Double.isNaN(e.exit)) o.put("exit", e.exit);
            if (!Double.isNaN(e.signalProb)) o.put("prob", e.signalProb);
            if (!Double.isNaN(e.paperEntry)) o.put("paperEntry", e.paperEntry);
            a.put(o);
        }
        return a;
    }

    public static List<Entry> fromJson(JSONArray a) {
        List<Entry> l = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Entry e = new Entry();
            e.id = o.optString("id"); e.date = o.optString("date"); e.horizon = o.optString("h"); e.dir = o.optInt("dir"); e.lots = o.optInt("lots", 1);
            e.lot = o.optInt("lot", 65); e.entryAt = o.optLong("entryAt"); e.exitAt = o.optLong("exitAt");
            e.entry = o.optDouble("entry", Double.NaN); e.exit = o.optDouble("exit", Double.NaN); e.signalProb = o.optDouble("prob", Double.NaN);
            e.paperEntry = o.optDouble("paperEntry", Double.NaN);
            l.add(e);
        }
        return l;
    }
}

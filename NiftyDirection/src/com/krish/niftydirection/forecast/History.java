package com.krish.niftydirection.forecast;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Everything the forecaster may look at, in one compact shape. The SAME object is used for training (past sessions)
 * and for the live forecast (past sessions + today's bars so far), so live and history always go through one code path.
 *
 * A session has 75 five-minute slots (9:15 … 15:25). Slot k holds the bar that starts at 9:15 + 5k and closes 5 minutes later.
 * Missing bars are NaN.
 */
public class History {
    public static final int BARS = 75;
    public static final int OPEN_MIN = 9 * 60 + 15;

    public static class Day {
        public String date;
        public final float[] o = nan(), h = nan(), l = nan(), c = nan();   // Nifty 50
        public final Map<String, float[]> aux = new LinkedHashMap<>();       // other instruments: close per slot
        public int bars;                                                     // completed Nifty bars (75 for a finished session)

        public Day(String date) { this.date = date; }

        public float[] aux(String key) { return aux.computeIfAbsent(key, k -> nan()); }

        /** Last known Nifty close at or before slot k-1 (the price after k completed bars). */
        public double closeAt(int k) { return last(c, k); }
        public double open() { for (int i = 0; i < BARS; i++) if (!Float.isNaN(o[i])) return o[i]; return Double.NaN; }
        public double lastClose() { return last(c, BARS); }
    }

    public static final String BANK = "BANK", VIX = "VIX";

    public final List<Day> days = new ArrayList<>();                          // sessions in date order
    public final TreeMap<String, double[]> niftyDaily = new TreeMap<>();      // date → {o, h, l, c}
    public final TreeMap<String, Double> vixDaily = new TreeMap<>();          // date → close
    public final Map<String, TreeMap<String, Double>> global = new LinkedHashMap<>();   // name → IST date → close
    public final Set<String> expiries = new HashSet<>();                      // weekly expiry sessions
    public final List<String> sectors = new ArrayList<>();                    // aux keys "SEC:<name>"

    public static float[] nan() { float[] f = new float[BARS]; java.util.Arrays.fill(f, Float.NaN); return f; }

    public static double last(float[] a, int k) {
        for (int i = Math.min(k, BARS) - 1; i >= 0; i--) if (!Float.isNaN(a[i])) return a[i];
        return Double.NaN;
    }

    public static int slot(int minute) { return (minute - OPEN_MIN) / 5; }

    /** Clock time (minutes since midnight) after k completed bars. */
    public static int minuteAfter(int k) { return OPEN_MIN + 5 * k; }

    /** Position of each session in the full daily calendar, so gaps (missing sessions) can be detected. */
    public int[] sessionIndex() {
        Map<String, Integer> pos = new HashMap<>();
        int i = 0;
        for (String d : niftyDaily.keySet()) pos.put(d, i++);
        int[] out = new int[days.size()];
        int extra = niftyDaily.size();
        for (int j = 0; j < days.size(); j++) {
            Integer p = pos.get(days.get(j).date);
            out[j] = p != null ? p : extra++;   // a day not in the daily list (e.g. today) goes after the known sessions
        }
        return out;
    }

    /** Previous session's close of an aux series, or NaN if the previous session is missing. */
    public double prevAuxClose(int d, String key, int[] sidx) {
        if (d == 0 || sidx[d] - sidx[d - 1] != 1) return Double.NaN;
        float[] a = days.get(d - 1).aux.get(key);
        return a == null ? Double.NaN : last(a, BARS);
    }

    // ------------------------------------------------------------------ compact storage (past sessions only)

    public void write(DataOutputStream o) throws IOException {
        o.writeInt(3);   // format
        o.writeInt(days.size());
        for (Day d : days) {
            o.writeUTF(d.date); o.writeByte(d.bars);
            for (float[] a : new float[][]{d.o, d.h, d.l, d.c}) for (float v : a) o.writeFloat(v);
            o.writeByte(d.aux.size());
            for (Map.Entry<String, float[]> e : d.aux.entrySet()) { o.writeUTF(e.getKey()); for (float v : e.getValue()) o.writeFloat(v); }
        }
        o.writeInt(niftyDaily.size());
        for (Map.Entry<String, double[]> e : niftyDaily.entrySet()) { o.writeUTF(e.getKey()); for (double v : e.getValue()) o.writeDouble(v); }
        writeMap(o, vixDaily);
        o.writeByte(global.size());
        for (Map.Entry<String, TreeMap<String, Double>> e : global.entrySet()) { o.writeUTF(e.getKey()); writeMap(o, e.getValue()); }
        o.writeInt(expiries.size());
        for (String s : expiries) o.writeUTF(s);
        o.writeByte(sectors.size());
        for (String s : sectors) o.writeUTF(s);
    }

    public static History read(DataInputStream in) throws IOException {
        if (in.readInt() != 3) throw new IOException("old format");
        History h = new History();
        int n = in.readInt();
        for (int i = 0; i < n; i++) {
            Day d = new Day(in.readUTF());
            d.bars = in.readByte();
            for (float[] a : new float[][]{d.o, d.h, d.l, d.c}) for (int k = 0; k < BARS; k++) a[k] = in.readFloat();
            int m = in.readByte();
            for (int j = 0; j < m; j++) { float[] a = d.aux(in.readUTF()); for (int k = 0; k < BARS; k++) a[k] = in.readFloat(); }
            h.days.add(d);
        }
        n = in.readInt();
        for (int i = 0; i < n; i++) { String k = in.readUTF(); h.niftyDaily.put(k, new double[]{in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble()}); }
        readMap(in, h.vixDaily);
        n = in.readByte();
        for (int i = 0; i < n; i++) { String k = in.readUTF(); TreeMap<String, Double> m = new TreeMap<>(); readMap(in, m); h.global.put(k, m); }
        n = in.readInt();
        for (int i = 0; i < n; i++) h.expiries.add(in.readUTF());
        n = in.readByte();
        for (int i = 0; i < n; i++) h.sectors.add(in.readUTF());
        return h;
    }

    static void writeMap(DataOutputStream o, TreeMap<String, Double> m) throws IOException {
        o.writeInt(m.size());
        for (Map.Entry<String, Double> e : m.entrySet()) { o.writeUTF(e.getKey()); o.writeDouble(e.getValue()); }
    }

    static void readMap(DataInputStream in, TreeMap<String, Double> m) throws IOException {
        int n = in.readInt();
        for (int i = 0; i < n; i++) { String k = in.readUTF(); m.put(k, in.readDouble()); }
    }

    /** A shallow copy that shares past days (they are never changed) so today's bars can be added for a live forecast. */
    public History copy() {
        History h = new History();
        h.days.addAll(days);
        h.niftyDaily.putAll(niftyDaily);
        h.vixDaily.putAll(vixDaily);
        h.global.putAll(global);
        h.expiries.addAll(expiries);
        h.sectors.addAll(sectors);
        return h;
    }
}

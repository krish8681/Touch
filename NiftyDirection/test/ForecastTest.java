import com.krish.niftydirection.forecast.*;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.*;

/** Forecaster: learns a planted pattern, no look-ahead, cross-day targets, live = history features, storage. */
public class ForecastTest {
    static int pass = 0, fail = 0;
    static void check(String name, boolean ok) { if (ok) pass++; else { fail++; System.out.println("FAIL " + name); } }

    /** signal = 0: pure noise. signal > 0: Bank Nifty's lead over Nifty today predicts Nifty's next hours. */
    static History synth(int sessions, double signal, long seed) {
        Random r = new Random(seed);
        History h = new History();
        h.sectors.add("SEC:Financials"); h.sectors.add("SEC:IT"); h.sectors.add("SEC:Metal"); h.sectors.add("SEC:Auto");
        LocalDate d = LocalDate.of(2022, 1, 3);
        double p = 17000, bank = 36000, vix = 15;
        double lead = 0;   // Bank Nifty's hidden lead (log) — persists within the day
        String[] glob = {"S&P 500", "Nasdaq", "Nikkei", "Hang Seng", "Brent crude", "USD/INR", "US 10Y yield", "Dollar index"};
        for (String g : glob) h.global.put(g, new TreeMap<>());
        double[] gv = {4000, 12000, 27000, 20000, 80, 82, 4, 100};
        for (int s = 0; s < sessions; ) {
            d = d.plusDays(1);
            if (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) continue;
            String date = d.toString();
            // global closes dated the previous calendar day (like US closes seen in India the next morning)
            for (int g = 0; g < glob.length; g++) { gv[g] *= Math.exp(r.nextGaussian() * 0.01); h.global.get(glob[g]).put(d.minusDays(1).toString(), gv[g]); }
            History.Day day = new History.Day(date);
            double open = p * Math.exp(r.nextGaussian() * 0.004);
            double bprev = bank;
            lead = r.nextGaussian() * 0.004;
            double hi = open, lo = open, x = open;
            double bk = bprev * Math.exp(Math.log(open / p));
            for (int k = 0; k < History.BARS; k++) {
                double o = x;
                double drift = signal * Math.signum(lead) * 0.0004;
                x = x * Math.exp(drift + r.nextGaussian() * 0.0012);
                double hh = Math.max(o, x) * (1 + Math.abs(r.nextGaussian()) * 0.0003), ll = Math.min(o, x) * (1 - Math.abs(r.nextGaussian()) * 0.0003);
                day.o[k] = (float) o; day.h[k] = (float) hh; day.l[k] = (float) ll; day.c[k] = (float) x;
                double bankNow = bprev * (x / p) * Math.exp(lead * Math.min(1, (k + 1) / 6.0) + r.nextGaussian() * 0.0005);
                day.aux("BANK")[k] = (float) bankNow;
                day.aux("VIX")[k] = (float) (vix * Math.exp(r.nextGaussian() * 0.01));
                for (String sec : h.sectors) day.aux(sec)[k] = (float) (1000 * (x / p) * Math.exp(r.nextGaussian() * 0.003));
                hi = Math.max(hi, hh); lo = Math.min(lo, ll);
            }
            day.bars = History.BARS;
            // previous-day sector closes must be 1000·1 → use relative-to-own-prev: store last as 1000 baseline next day
            h.days.add(day);
            h.niftyDaily.put(date, new double[]{open, hi, lo, x});
            vix = Math.max(9, Math.min(35, vix * Math.exp(r.nextGaussian() * 0.05)));
            h.vixDaily.put(date, vix);
            bank = day.aux("BANK")[74];
            p = x;
            if (d.getDayOfWeek() == DayOfWeek.TUESDAY) h.expiries.add(date);
            s++;
        }
        return h;
    }

    public static void main(String[] a) throws Exception {
        // ---- 1. learning works: with a planted pattern, the model picks the right side on fresh days it never saw
        History sig = synth(560, 1.0, 11);
        Forecaster.Model m1 = Forecaster.train(sig, Forecaster.HORIZONS[0]);
        History fresh = synth(120, 1.0, 99);
        int right = 0, n = 0;
        int[] fs = fresh.sessionIndex();
        for (int d = 30; d < fresh.days.size() - 1; d++) for (int k = 12; k <= 60; k += 12) {
            double[] t = Forecaster.target(fresh, d, k, Forecaster.HORIZONS[0], fs);
            if (t == null) continue;
            double p = m1.lr.predict(Features.compute(fresh, d, k, fs));
            n++; if ((p >= 0.5) == (t[1] > fresh.days.get(d).closeAt(k))) right++;
        }
        System.out.println("planted pattern: right side " + right + " of " + n);
        check("model learns a planted pattern", right > 0.65 * n);
        check("model info filled", m1.info.days > 500 && m1.info.samples > 10000 && !Double.isNaN(m1.info.medMove));
        for (Forecaster.Horizon hz : Forecaster.HORIZONS) check("trains " + hz.id, Forecaster.train(sig, hz).lr != null);

        // ---- 3. no look-ahead: features at (d,k) are the same when everything after that moment is removed
        int[] sidx = sig.sessionIndex();
        boolean same = true;
        Random r = new Random(3);
        for (int t = 0; t < 60; t++) {
            int d = 60 + r.nextInt(sig.days.size() - 61), k = 1 + r.nextInt(75);
            double[] full = Features.compute(sig, d, k, sidx);
            History cut = truncated(sig, d, k);
            double[] part = Features.compute(cut, d, k, cut.sessionIndex());
            for (int j = 0; j < full.length; j++) if (!(Double.isNaN(full[j]) && Double.isNaN(part[j])) && full[j] != part[j]) { same = false; System.out.println("look-ahead in " + Features.NAMES[j] + " d=" + d + " k=" + k); }
        }
        check("no look-ahead (60 random moments)", same);

        // ---- 4. live path = history path (a live day built bar by bar gives the same features and forecast)
        int d = sig.days.size() - 1, k = 40;
        History live = truncated(sig, d, k);
        double pHist = m1.lr.predict(Features.compute(sig, d, k, sig.sessionIndex()));
        Forecaster.Prediction pl = Forecaster.predict(m1, live, d, k);
        check("live forecast = history forecast", Math.abs(pHist - pl.pUp) < 1e-12);
        check("reasons given", !pl.reasons.isEmpty());

        // ---- 5. cross-day targets
        Forecaster.Horizon h1 = Forecaster.HORIZONS[0], hD = Forecaster.HORIZONS[3], h6 = Forecaster.HORIZONS[2];
        int[] si = sig.sessionIndex();
        double[] t = Forecaster.target(sig, 100, 75, h1, si);
        check("1H from the close = 10:15 next session", t != null && (int) t[0] == 101 && t[1] == sig.days.get(101).c[11]);
        t = Forecaster.target(sig, 100, 3, hD, si);
        check("next-day target = next session's close", t != null && (int) t[0] == 101 && t[1] == sig.days.get(101).c[74]);
        t = Forecaster.target(sig, 100, 30, h6, si);
        check("6H from 11:45 crosses the night", t != null && (int) t[0] == 101 && t[1] == sig.days.get(101).c[(29 + 72) % 75]);
        check("when text", Forecaster.when(h1, 75).equals("by 10:15 next session") && Forecaster.when(h1, 9).equals("by 11:00 today"));
        History gap = sig.copy();
        gap.days.remove(101);   // a missing session must not be jumped over
        check("missing session → no target", Forecaster.target(gap, 100, 75, h1, gap.sessionIndex()) == null);
        check("last day has no future target", Forecaster.target(sig, sig.days.size() - 1, 75, h1, si) == null);

        // ---- 5b. pre-open models (forecast from the opening price)
        check("from the open, 1D = today's close", Forecaster.target(sig, 100, 0, hD, si) != null
                && (int) Forecaster.target(sig, 100, 0, hD, si)[0] == 100 && Forecaster.target(sig, 100, 0, hD, si)[1] == sig.days.get(100).c[74]);
        check("from the open, 1H = 10:15 today", Forecaster.target(sig, 100, 0, h1, si)[1] == sig.days.get(100).c[11] && Forecaster.when(h1, 0).equals("by 10:15 today"));
        Forecaster.Model op = Forecaster.train(sig, hD, true);
        check("pre-open model trains on one sample per session", op.lr != null && op.open && op.info.samples > 500 && op.info.samples < 600);
        double[] f0 = Features.compute(sig, 200, 0, si);
        check("open features: gap known, today's bars unknown", !Double.isNaN(f0[3]) && Double.isNaN(f0[4]) && !Double.isNaN(f0[11]));
        History pre = truncated(sig, 300, 1);
        History.Day today = pre.days.get(300);
        for (int i = 0; i < 75; i++) { today.c[i] = Float.NaN; today.h[i] = Float.NaN; today.l[i] = Float.NaN; if (i > 0) today.o[i] = Float.NaN; }
        today.aux.clear(); today.bars = 0;
        Forecaster.Prediction pp = Forecaster.predict(op, pre, 300, 0);
        check("pre-open forecast from an expected open", !Double.isNaN(pp.pUp) && pp.label.equals("Today's close") && pp.when.equals("at today's close"));
        check("yesterday's internals filled", !Double.isNaN(f0[27]) && !Double.isNaN(f0[28]));

        // ---- 6. model survives saving
        Forecaster.Model back = Forecaster.fromJson(new org.json.JSONObject(Forecaster.toJson(m1).toString()));
        double[] x = Features.compute(sig, 300, 30, si);
        check("model JSON round trip", Math.abs(back.lr.predict(x) - m1.lr.predict(x)) < 1e-9 && back.info.days == m1.info.days);

        // ---- 7. history storage round trip
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        sig.write(new java.io.DataOutputStream(bo));
        History rd = History.read(new java.io.DataInputStream(new java.io.ByteArrayInputStream(bo.toByteArray())));
        double[] x2 = Features.compute(rd, 300, 30, rd.sessionIndex());
        check("history file round trip", Arrays.equals(x, x2));

        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    /** What was known after k bars of day d: later days removed, today's later bars blank, daily closes before d only. */
    static History truncated(History h, int d, int k) {
        History c = new History();
        for (int i = 0; i < d; i++) c.days.add(h.days.get(i));
        History.Day src = h.days.get(d), day = new History.Day(src.date);
        for (int i = 0; i < k; i++) {
            day.o[i] = src.o[i]; day.h[i] = src.h[i]; day.l[i] = src.l[i]; day.c[i] = src.c[i];
            for (Map.Entry<String, float[]> e : src.aux.entrySet()) day.aux(e.getKey())[i] = e.getValue()[i];
        }
        day.bars = k;
        c.days.add(day);
        c.niftyDaily.putAll(h.niftyDaily.headMap(src.date, false));
        c.vixDaily.putAll(h.vixDaily.headMap(src.date, false));
        for (Map.Entry<String, TreeMap<String, Double>> e : h.global.entrySet()) c.global.put(e.getKey(), new TreeMap<>(e.getValue().headMap(src.date, false)));
        c.expiries.addAll(h.expiries);
        c.sectors.addAll(h.sectors);
        return c;
    }
}

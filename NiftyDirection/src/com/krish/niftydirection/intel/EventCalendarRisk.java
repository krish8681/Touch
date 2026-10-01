package com.krish.niftydirection.intel;

import com.krish.niftydirection.model.EventItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Economic Calendar Engine + Surprise Engine.
 *
 * Event risk mode: 30 minutes before a scheduled medium/high-importance release the market enters PRE_EVENT
 * (short-horizon confidence halved), for an hour after it is POST_EVENT, and any horizon whose window contains
 * a scheduled event gets lower confidence. Expiries are not events here (they are a regime).
 *
 * Surprise: markets react to actual − expected. A line in "My events" like
 *   2026-10-12 16:00 India CPI | exp=4.5 | act=4.3 | good=down
 * becomes a primary-source event once `act` is filled: surprise z = (act − exp) / sd (sd defaults to 10% of |exp|, at least 0.1),
 * direction = sign(z), flipped when lower is good for shares (good=down).
 */
public final class EventCalendarRisk {
    private EventCalendarRisk() {}

    public static final String NORMAL = "NORMAL", EVENT_DAY = "EVENT DAY", PRE_EVENT = "PRE-EVENT", POST_EVENT = "POST-EVENT";

    public static final class Risk {
        public String mode = NORMAL, text = "No scheduled event in the forecast windows.", next = "";
        public final double[] mult = new double[Horizon.ALL.length];
        public final String[] why = new String[Horizon.ALL.length];
        public boolean eventDriven() { return PRE_EVENT.equals(mode) || POST_EVENT.equals(mode); }
        Risk() { java.util.Arrays.fill(mult, 1); java.util.Arrays.fill(why, ""); }
    }

    static final Pattern TIME = Pattern.compile("^(\\d{1,2}):(\\d{2})\\b\\s*");

    /** Minute of the day (IST) an event lands; -1 = unknown time (all day); -2 = not an event (expiry). Overnight releases = 0. */
    public static int minuteOf(EventItem e) {
        Matcher m = TIME.matcher(e.name);
        if (m.find()) return Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2));
        String n = e.name.toLowerCase(Locale.US);
        if (n.contains("expiry")) return -2;
        if (n.contains("rbi")) return 10 * 60;
        if (n.contains("fed")) return 0;                       // decided overnight, hits India at the open
        if (n.contains("budget")) return 11 * 60;
        if (n.contains("india cpi") || n.contains("cpi inflation")) return 16 * 60;
        if (n.contains("us cpi") || n.contains("payroll") || n.contains("jobs")) return 18 * 60;
        if (n.contains("gdp")) return 17 * 60 + 30;
        return -1;
    }

    public static Risk assess(List<EventItem> events, String today, int minute) {
        Risk r = new Risk();
        if (events == null) return r;
        List<String> lines = new ArrayList<>();
        for (EventItem e : events) {
            if (e.importance < 2) continue;
            int t = minuteOf(e);
            if (t == -2) continue;
            int dAhead = FeatureEngine.days(today, e.date);
            if (dAhead < 0) continue;
            String when = e.date + (t >= 0 ? String.format(Locale.US, " %d:%02d", t / 60, t % 60) : "");
            if (r.next.isEmpty() && (dAhead > 0 || t < 0 || t >= minute)) r.next = clean(e.name) + " · " + when;
            if (dAhead == 0 && t >= 0) {
                if (t - minute >= 0 && t - minute <= 30) { r.mode = PRE_EVENT; lines.add(clean(e.name) + " in " + (t - minute) + " min"); }
                else if (minute - t >= 0 && minute - t <= 60 && !PRE_EVENT.equals(r.mode)) { r.mode = POST_EVENT; lines.add(clean(e.name) + " came out " + (minute - t) + " min ago"); }
                else if (t > minute && NORMAL.equals(r.mode)) { r.mode = EVENT_DAY; lines.add(clean(e.name) + " later today"); }
            } else if (dAhead == 0 && NORMAL.equals(r.mode)) { r.mode = EVENT_DAY; lines.add(clean(e.name) + " today"); }
            for (int i = 0; i < Horizon.ALL.length; i++) {
                Horizon hz = Horizon.ALL[i];
                if (!inWindow(hz, dAhead, t, minute)) continue;
                double m = e.importance >= 3 ? 0.6 : 0.8;
                if (dAhead == 0 && t >= 0 && t - minute >= 0 && t - minute <= 30 && !hz.swing()) m = 0.5;
                if (m < r.mult[i]) { r.mult[i] = m; r.why[i] = clean(e.name) + " (" + when + ") falls inside this window"; }
            }
        }
        if (!lines.isEmpty()) r.text = String.join(" · ", lines);
        return r;
    }

    static boolean inWindow(Horizon hz, int dAhead, int t, int minute) {
        if (hz.swing()) return dAhead <= (hz.sessions == 1 ? 1 : 7);
        int close = 15 * 60 + 30, end = Math.max(minute, 9 * 60 + 15) + hz.minutes, spill = end - close;
        if (dAhead == 0) return t < 0 || (t >= minute - 60 && (spill > 0 || t <= end));
        return spill > 0 && dAhead <= 3 && (t < 0 || t <= 9 * 60 + 15 + spill);   // the window runs into the next session
    }

    static String clean(String name) {
        String s = TIME.matcher(name).replaceFirst("");
        int bar = s.indexOf('|');
        return (bar >= 0 ? s.substring(0, bar) : s).trim();
    }

    // ================================================================== surprise

    public static final class Surprise {
        public String date, name;
        public int minute = -1;
        public double expected = Double.NaN, actual = Double.NaN, sd = Double.NaN, good = 1, z = Double.NaN;
        /** Direction for Nifty × size (−1..1), NaN until the actual is known. */
        public double impact() { return Double.isNaN(z) ? Double.NaN : Math.signum(z) * good * Math.min(1, Math.abs(z) / 2); }
    }

    /** Lines "yyyy-mm-dd [HH:MM] name | exp=.. | act=.. [| sd=..] [| good=up/down]" from the user's events. */
    public static List<Surprise> surprises(String userText) {
        List<Surprise> out = new ArrayList<>();
        if (userText == null) return out;
        for (String line : userText.split("\n")) {
            line = line.trim();
            if (line.length() < 11 || !line.substring(0, 10).matches("\\d{4}-\\d{2}-\\d{2}") || !line.contains("|")) continue;
            Surprise s = new Surprise();
            s.date = line.substring(0, 10);
            String[] parts = line.substring(10).split("\\|");
            String head = parts[0].trim();
            Matcher m = TIME.matcher(head);
            if (m.find()) { s.minute = Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2)); head = head.substring(m.end()).trim(); }
            s.name = head.isEmpty() ? "My event" : head;
            for (int i = 1; i < parts.length; i++) {
                String[] kv = parts[i].trim().split("=", 2);
                if (kv.length < 2) continue;
                String k = kv[0].trim().toLowerCase(Locale.US), v = kv[1].trim();
                try {
                    if (k.equals("exp")) s.expected = Double.parseDouble(v);
                    else if (k.equals("act")) s.actual = Double.parseDouble(v);
                    else if (k.equals("sd")) s.sd = Double.parseDouble(v);
                    else if (k.equals("good")) s.good = v.toLowerCase(Locale.US).startsWith("d") ? -1 : 1;
                } catch (NumberFormatException ignored) { }
            }
            if (Double.isNaN(s.expected)) continue;
            double sd = !Double.isNaN(s.sd) && s.sd > 0 ? s.sd : Math.max(0.1, Math.abs(s.expected) * 0.1);
            if (!Double.isNaN(s.actual)) s.z = (s.actual - s.expected) / sd;
            out.add(s);
        }
        return out;
    }
}

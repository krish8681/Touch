package com.krish.niftydirection.data;

import com.krish.niftydirection.model.EventItem;
import com.krish.niftydirection.model.NewsItem;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Scheduled events that can move Nifty. Built-in dates (RBI policy, Fed decisions, India CPI, Budget),
 * Nifty expiries from the instrument list, events the Gemini reader found in the news, and the user's own list.
 */
public final class EventCalendar {
    private EventCalendar() {}

    /** RBI policy announcement days, FY 2026-27 (RBI calendar). */
    public static final String[] RBI = {"2025-02-07", "2025-04-09", "2025-06-06", "2025-08-06", "2025-10-01", "2025-12-05", "2026-02-06", "2026-04-08", "2026-06-05", "2026-08-05", "2026-10-07", "2026-12-04", "2027-02-05"};
    /** Fed decisions land in India the morning after the 2nd FOMC day (2026 calendar). */
    public static final String[] FED = {"2025-01-30", "2025-03-20", "2025-05-08", "2025-06-19", "2025-07-31", "2025-09-18", "2025-10-30", "2025-12-11", "2026-01-29", "2026-03-19", "2026-04-30", "2026-06-18", "2026-07-30", "2026-09-17", "2026-10-29", "2026-12-10"};

    /** Events from today up to `days` days ahead, sorted by date. */
    public static List<EventItem> upcoming(String today, int days, List<String> futExpiries, List<String> optExpiries, String userText, List<NewsItem> news) {
        List<EventItem> all = new ArrayList<>();
        for (String d : RBI) all.add(new EventItem(d, "RBI policy decision", 3, "RBI calendar"));
        for (String d : FED) all.add(new EventItem(d, "US Fed decision (hits India this morning)", 3, "Fed calendar"));
        all.add(new EventItem("2027-02-01", "Union Budget", 3, "fixed date"));
        // India CPI: around the 12th of each month, 4 PM (moves the next session)
        for (int m = -1; m <= 2; m++) {
            String cpi = cpiDay(today, m);
            if (cpi != null) all.add(new EventItem(cpi, "India CPI inflation (4 PM)", 2, "usual date (12th)"));
        }
        for (String e : futExpiries) all.add(new EventItem(e, "Nifty monthly expiry", 2, "Kite contracts"));
        for (String e : optExpiries) if (!futExpiries.contains(e)) all.add(new EventItem(e, "Nifty weekly expiry", 1, "Kite contracts"));
        // user list: "yyyy-mm-dd name" per line
        if (userText != null) for (String line : userText.split("\n")) {
            line = line.trim();
            if (line.length() < 11 || !line.substring(0, 10).matches("\\d{4}-\\d{2}-\\d{2}")) continue;
            all.add(new EventItem(line.substring(0, 10), line.substring(10).trim().isEmpty() ? "My event" : line.substring(10).trim(), 3, "my list"));
        }
        // events the news reader spotted
        if (news != null) for (NewsItem n : news) {
            if (!n.scheduled || n.eventDate == null || !n.eventDate.matches("\\d{4}-\\d{2}-\\d{2}")) continue;
            int imp = "HIGH".equals(n.severity) ? 3 : "MEDIUM".equals(n.severity) ? 2 : 1;
            all.add(new EventItem(n.eventDate, n.eventName.isEmpty() ? n.title : n.eventName, imp, "news (" + n.by + ")"));
        }
        List<EventItem> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (EventItem e : all) {
            int d = Collector.days(today, e.date);
            if (d < 0 || d > days) continue;
            String key = e.date + "|" + e.name.toLowerCase(Locale.US);
            if (!seen.add(key)) continue;
            out.add(e);
        }
        out.sort((a, b) -> a.date.equals(b.date) ? Integer.compare(b.importance, a.importance) : a.date.compareTo(b.date));
        return out;
    }

    /** The 12th of (this month + offset), moved to Monday if it is a weekend. */
    static String cpiDay(String today, int monthOffset) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            f.setTimeZone(Collector.IST);
            Calendar c = Calendar.getInstance(Collector.IST);
            c.setTime(f.parse(today));
            c.set(Calendar.DAY_OF_MONTH, 12);
            c.add(Calendar.MONTH, monthOffset);
            int dow = c.get(Calendar.DAY_OF_WEEK);
            if (dow == Calendar.SATURDAY) c.add(Calendar.DAY_OF_MONTH, 2);
            if (dow == Calendar.SUNDAY) c.add(Calendar.DAY_OF_MONTH, 1);
            return f.format(c.getTime());
        } catch (Exception e) { return null; }
    }
}

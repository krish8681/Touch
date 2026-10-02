package com.krish.niftydirection.intel;

/**
 * The nine forecast horizons. Each one gets its OWN group models, meta model, calibration and range table —
 * no single model is shared across horizons.
 *
 * Day close (EOD) targets today's 15:25 bar close from any moment of the session.
 * Intraday horizons count trading time only (5-minute bars; nights and weekends are skipped, so 1H at 15:00 means 9:50 next session).
 * Swing horizons are measured to a session close: 1D = next session's close (today's close when made at the open),
 * 1W = the close five sessions later.
 */
public final class Horizon {
    public static final String SHORT = "SHORT", INTRADAY = "INTRADAY", SWING = "SWING";

    public final String id, label, band;
    /** Bars ahead for intraday horizons; 0 for swing horizons. */
    public final int bars;
    /** Sessions ahead (to a close) for swing horizons; 0 for intraday. */
    public final int sessions;
    /** Trading minutes covered (for the volatility scale of the expected range). */
    public final int minutes;
    /** Half-life of a news event's effect at this horizon, in minutes. */
    public final int newsHalfLifeMin;

    Horizon(String id, String label, String band, int bars, int sessions, int minutes, int newsHalfLifeMin) {
        this.id = id; this.label = label; this.band = band; this.bars = bars; this.sessions = sessions; this.minutes = minutes; this.newsHalfLifeMin = newsHalfLifeMin;
    }

    public boolean swing() { return sessions > 0; }

    /** "Day close": to today's close from wherever the forecast is made (only defined while the session is running). */
    public boolean eod() { return "EOD".equals(id); }

    /** Trading minutes covered when made after k bars (fixed, except Day close, which shrinks through the day). */
    public int minutesAt(int k) { return eod() ? Math.max(5, (75 - k) * 5) : minutes; }

    public static final Horizon[] ALL = {
            new Horizon("15m", "15 min", SHORT, 3, 0, 15, 60),
            new Horizon("30m", "30 min", SHORT, 6, 0, 30, 120),
            new Horizon("1h", "1 hour", INTRADAY, 12, 0, 60, 180),
            new Horizon("2h", "2 hours", INTRADAY, 24, 0, 120, 240),
            new Horizon("3h", "3 hours", INTRADAY, 36, 0, 180, 360),
            new Horizon("6h", "6 hours", INTRADAY, 72, 0, 360, 720),
            new Horizon("EOD", "Day close", INTRADAY, 0, 0, 190, 480),
            new Horizon("1D", "1 day", SWING, 0, 1, 375, 1440),
            new Horizon("1W", "1 week", SWING, 0, 5, 5 * 375, 4320)};

    public static Horizon of(String id) { for (Horizon h : ALL) if (h.id.equals(id)) return h; return null; }
}

package com.krish.niftydirection.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the engine needs, gathered in one refresh.
 * Any field can be missing (null / empty / NaN). The engine skips a factor when its data is missing
 * and lowers the confidence instead of guessing.
 */
public class Snapshot {
    public long time;               // epoch millis when gathered
    public String today = "";       // yyyy-MM-dd IST
    public int minute;              // minutes since midnight IST
    public boolean weekday = true;
    /** true = today's session is running now (9:15-15:30 and the exchange is open today). */
    public boolean live;
    /** Date of the session the prices describe (today when live or after close; last trading day before open). */
    public String sessionDate = "";

    public Quote nifty, bank, fin, vix;
    public Quote fut, futNext;          // near and next month NIFTY futures
    public double futPrevOi = -1;       // near + next month OI at the close before the session
    public int futDaysToExpiry = 30;

    public Map<String, Quote> sectors = new LinkedHashMap<>();
    public List<Quote> stocks = new ArrayList<>();

    public List<Candle> niftyBars = new ArrayList<>();  // today's 5-minute index bars
    public List<Candle> futBars = new ArrayList<>();    // today's 5-minute near-month futures bars (volume for VWAP, oi)
    public List<Candle> futNextBars = new ArrayList<>(); // today's 5-minute next-month futures bars (oi only)
    public Candle prevDay;                              // last completed session (for levels + structure)

    public List<OptionRow> chain = new ArrayList<>();
    public String expiry = "";
    public int optDaysToExpiry = 7;
    public double strikeStep = 50;
    public int lotSize;                 // Nifty F&O lot size from Kite (0 = unknown)
    /** The following weekly expiry (fewer strikes, no OI history): used for trades that must outlive the near expiry. */
    public List<OptionRow> chain2 = new ArrayList<>();
    public String expiry2 = "";
    public int opt2DaysToExpiry = 14;

    /** % change of global markets. Keys: see Global.SYMBOLS names. */
    public Map<String, Double> global = new LinkedHashMap<>();
    public Map<String, Double> globalPrice = new LinkedHashMap<>();
    public double giftNifty = Double.NaN;       // from Kite (NSEIX:GIFT NIFTY), or typed by the user as a fallback
    public String giftSource = "";              // "Kite" or "typed"
    public Quote gift;                          // raw Kite quote (for the Market page)
    public double fiiCash = Double.NaN, diiCash = Double.NaN;   // ₹ crore, net
    public String fiiDate = "";
    public double fiiIdxLong = Double.NaN, fiiIdxLongPrev = Double.NaN;  // FII index-futures long share, %
    public String fiiOiDate = "";

    /** Option-chain snapshots taken today (oldest first; the last one is now). */
    public List<FlowPoint> flow = new ArrayList<>();
    /** Nifty weight (fraction, adds to 1) and sector of each member. */
    public Map<String, Double> weights = new LinkedHashMap<>();
    public Map<String, String> sectorOf = new LinkedHashMap<>();
    public boolean weightsApprox = true;
    public String weightsDate = "";
    /** About one year of daily candles for India VIX and Nifty (VIX percentile, VIX/Nifty correlation). */
    public List<Candle> vixDaily = new ArrayList<>(), niftyDaily = new ArrayList<>();
    /** Typical size of the live option-flow intensity on past days (median of |intensity|). NaN = not enough history. */
    public double flowScale = Double.NaN;
    /** Future and option expiry dates known from Kite. */
    public List<String> futExpiries = new ArrayList<>(), optExpiries = new ArrayList<>();
    /** 20-day average close of each stock (from completed days). */
    public Map<String, Double> dma20 = new LinkedHashMap<>();

    /** News and scheduled events. */
    public List<NewsItem> news = new ArrayList<>();
    public List<EventItem> events = new ArrayList<>();
    public String newsReader = "";          // "Gemini (model)" / "keywords" / ""
    public long newsAt;                     // when headlines were last fetched

    /** Today's earlier results: {minute, score, confidence, regimeCode, spot}. Used for hysteresis and transitions. */
    public List<double[]> history = new ArrayList<>();
    public String prevRegime = "";

    /** Per-factor weight multipliers learnt from the record (walk-forward). Empty = none yet. */
    public Map<String, Double> calibration = new LinkedHashMap<>();

    /** When each source was last refreshed (epoch ms), for the data-quality view. */
    public Map<String, Long> sourceTime = new LinkedHashMap<>();
    public long collectMs;                  // how long gathering took


    public List<String> notes = new ArrayList<>();   // data problems, shown to the user

    public double spot() { return nifty != null ? nifty.last : 0; }
}

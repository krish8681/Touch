package com.krish.niftydirection.engine;

import java.util.ArrayList;
import java.util.List;

/** What the engine concluded. */
public class Result {
    public static final String BULLISH = "BULLISH", BEARISH = "BEARISH", RANGE = "RANGE", NO_EDGE = "NO EDGE", CONFLICT = "CONFLICT";

    public static String code(String regime) {
        switch (regime) { case BULLISH: return "U"; case BEARISH: return "D"; case RANGE: return "R"; case CONFLICT: return "C"; default: return "N"; }
    }
    public static String fromCode(String c) {
        switch (c) { case "U": return BULLISH; case "D": return BEARISH; case "R": return RANGE; case "C": return CONFLICT; default: return NO_EDGE; }
    }
    /** Numeric code for charts: 1 bull, 2 bear, 0 range, 3 no edge, 4 conflict. */
    public static double codeNum(String c) {
        switch (c) { case "U": return 1; case "D": return 2; case "R": return 0; case "C": return 4; default: return 3; }
    }
    public static String fromNum(double n) {
        return n == 1 ? BULLISH : n == 2 ? BEARISH : n == 0 ? RANGE : n == 4 ? CONFLICT : NO_EDGE;
    }

    public long time;
    public String regime = NO_EDGE;
    public String rawRegime = NO_EDGE;   // before the 2-of-3 persistence check
    public String pending = "";          // a flip waiting for confirmation
    public String leans = "";     // for NO EDGE: "leans up" / "leans down" / ""
    public boolean strong;
    public double score;          // -100 .. +100 final (signed)
    public int directionScore;    // 0 .. 100 (50 = no side)
    public int confidence;        // 0 .. 100
    public double coverage, agreement;
    public double structScore = Double.NaN, liveScore = Double.NaN, eventScore = Double.NaN;
    public double structWeight, liveWeight, eventAdj;
    public double preScore = Double.NaN;   // kept for older code = structural

    public int stage;
    public String stageName = "", stageTip = "";
    public String state = "", action = "";
    public String transition = "";      // "Bullish → weakening", "Range → Bullish" …
    public double slope30 = Double.NaN; // score change over the last 30 minutes
    public List<String> warnings = new ArrayList<>();
    public List<String> conflictLines = new ArrayList<>();   // which evidence says what, when in CONFLICT

    // data health
    public boolean degraded;
    public List<String> degradedList = new ArrayList<>();

    // internal disagreement (even when structure and live do not formally conflict)
    public boolean disagreement;
    public double opposingShare, dispersion;
    public List<String> opposers = new ArrayList<>();

    // day type + gap
    public String dayType = "NORMAL DAY";   // NORMAL DAY / WEEKLY EXPIRY / MONTHLY EXPIRY / EVENT + EXPIRY
    public boolean maxPainMagnet;
    public String gapClass = "", gapBehavior = "";

    // sector attribution check
    public double attrActual = Double.NaN, attrCalc = Double.NaN, attrCoverage = Double.NaN;

    // global split
    public double globalRisk = Double.NaN, indiaMacro = Double.NaN;

    // VIX regime
    public double vixPct = Double.NaN, vixCorr = Double.NaN;
    public String volClass = "";

    // volatility regime (VIX as a modifier, not a vote)
    public String volRegime = "";
    public double volMod = 1;

    // events
    public String eventRisk = "LOW";    // LOW / MEDIUM / HIGH
    public String eventWhy = "";
    public double eventMod = 1;

    public double spot, support = Double.NaN, resistance = Double.NaN;
    public String supportWhy = "", resistanceWhy = "";
    public double vwapSpot = Double.NaN;
    public double maxPain = Double.NaN, pcr = Double.NaN, ceWall = Double.NaN, peWall = Double.NaN;
    public double expectedMove = Double.NaN, atmIv = Double.NaN, atmIvChange = Double.NaN;
    public int advances, declines;
    public String optionsLine = "", priceLine = "", breadthLine = "", bankLine = "", flowLine = "", futFlowLine = "", momentumState = "";

    /** Sector → contribution to Nifty's move in % points, biggest first. */
    public List<Object[]> sectorContrib = new ArrayList<>();
    /** Stock → {weight %, move %, contribution % pts}, biggest movers of the index first. */
    public List<Object[]> heavy = new ArrayList<>();
    /** Intraday option activity rows (for the Options page). */
    public Chain.Flow optFlow;

    /** Data-quality rows: {name, age text, quality, reliability}. */
    public List<String[]> quality = new ArrayList<>();

    public List<Factor> factors = new ArrayList<>();
    public List<String> notes = new ArrayList<>();

    public String label() {
        if (NO_EDGE.equals(regime)) return leans.isEmpty() ? NO_EDGE : NO_EDGE + " (" + leans + ")";
        return (strong && (BULLISH.equals(regime) || BEARISH.equals(regime)) ? "STRONG " : "") + regime;
    }

    public Factor factor(String key) { for (Factor f : factors) if (f.key.equals(key)) return f; return null; }
}

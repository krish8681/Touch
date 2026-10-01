package com.krish.niftydirection.engine;

import com.krish.niftydirection.model.Candle;
import com.krish.niftydirection.model.EventItem;
import com.krish.niftydirection.model.FlowPoint;
import com.krish.niftydirection.model.NewsItem;
import com.krish.niftydirection.model.OptionRow;
import com.krish.niftydirection.model.Quote;
import com.krish.niftydirection.model.Snapshot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Nifty direction engine (v1.2).
 *
 * It classifies, it does not predict. Three separate scores:
 *   STRUCTURAL (positioning before / behind the move): global 15, GIFT/gap 15, FII 15, futures since yesterday 20,
 *                options since yesterday 20, previous-day structure 15
 *   LIVE (what price and flows are doing now):        opening range 10, VWAP 14, breadth 14, Bank Nifty 10, momentum 12,
 *                sector contribution 10, live option flow 16, live futures flow 14
 *   EVENT (news + scheduled events):                  a small adjustment plus an event-risk level
 * India VIX does not vote: it sets the volatility regime and scales confidence.
 *
 * The final score blends structure and live by time of day (live dominates once the market is open),
 * adds the event adjustment, then:
 *   - hysteresis: enter bullish at +25, stay until +15 (mirror for bearish)
 *   - CONFLICT when structure and live disagree strongly
 *   - NO EDGE when the evidence is weak or mixed; RANGE only when there are real range signs
 * Every factor's weight is scaled by source reliability, data freshness and (after 20 recorded days) what the record taught.
 */
public final class Engine {
    private Engine() {}

    public static final double ENTER = 25, EXIT = 15, STRONG_AT = 50, CONFLICT_AT = 30;

    public static Result run(Snapshot s) {
        Result r = new Result();
        r.time = s.time;
        r.spot = s.spot();
        r.notes.addAll(s.notes);
        stage(s, r);
        dayType(s, r);

        List<Factor> f = r.factors;
        // structural
        f.add(global(s, r));
        f.add(gap(s, r));
        f.add(fii(s));
        f.add(futures(s, r));
        f.add(options(s, r));
        f.add(prevDay(s));
        // live
        double[] or = openingRange(s);
        f.add(opening(s, or));
        f.add(vwap(s, r));
        f.add(breadth(s, r));
        f.add(bank(s, r));
        f.add(momentum(s, r));
        f.add(sectors(s, r));
        f.add(optionFlow(s, r));
        f.add(futuresFlow(s, r));
        // modifiers + events
        f.add(vix(s, r));
        f.add(events(s, r));

        applyQuality(s, r);
        score(s, r);
        disagreement(r);
        regime(s, r, or);
        transition(s, r);
        levels(s, r, or);
        stateAndAction(s, r);
        return r;
    }

    // ================================================================== stage of the day

    static void stage(Snapshot s, Result r) {
        int m = s.minute;
        if (!s.weekday) { r.stage = 1; r.stageName = "Market closed"; r.stageTip = "Weekend. The view is built for the next session from Friday's data."; return; }
        if (m < 8 * 60 || m >= 15 * 60 + 30 || (!s.live && m >= 9 * 60 + 15)) {
            r.stage = 1; r.stageName = "Stage 1 · Evening review";
            r.stageTip = "Read FII, futures OI, option OI, VIX, news and global markets. This builds tomorrow's first idea.";
        } else if (m < 9 * 60 + 15) {
            r.stage = 2; r.stageName = "Stage 2 · Pre-market (8:00–9:15)";
            r.stageTip = "US close, Asia, GIFT Nifty, crude, rupee, overnight news. The result is a hypothesis, not a trade.";
        } else if (m < 9 * 60 + 30) {
            r.stage = 3; r.stageName = "Stage 3 · Opening (9:15–9:30)";
            r.stageTip = "Do not assume direction yet. Watch the gap, the first 15 minutes, breadth, Bank Nifty and VWAP.";
        } else if (m < 11 * 60 + 30) {
            r.stage = 4; r.stageName = "Stage 4 · Trend check (9:30–11:30)";
            r.stageTip = "Now price action decides: trend, range or reversal. It can overrule the morning idea.";
        } else {
            r.stage = 5; r.stageName = "Stage 5 · Rest of day (11:30–15:30)";
            r.stageTip = "Look for continuation, tiredness, short covering, long unwinding or a range break.";
        }
    }

    /** Live share by minute of the day: a smooth line, so a stage change never makes the score jump. */
    static final int[] BLEND_MIN = {9 * 60 + 15, 9 * 60 + 30, 9 * 60 + 45, 10 * 60 + 15, 11 * 60, 13 * 60};
    static final double[] BLEND_LIVE = {0.15, 0.30, 0.40, 0.55, 0.65, 0.75};

    public static double liveShare(boolean live, int minute) {
        if (!live) return 0.15;
        if (minute <= BLEND_MIN[0]) return BLEND_LIVE[0];
        for (int i = 1; i < BLEND_MIN.length; i++) {
            if (minute <= BLEND_MIN[i]) {
                double f = (minute - BLEND_MIN[i - 1]) / (double) (BLEND_MIN[i] - BLEND_MIN[i - 1]);
                return BLEND_LIVE[i - 1] + f * (BLEND_LIVE[i] - BLEND_LIVE[i - 1]);
            }
        }
        return BLEND_LIVE[BLEND_LIVE.length - 1];
    }

    /** How much structure vs live counts at this time of day. */
    static double[] blend(Snapshot s, Result r) {
        double l = liveShare(s.live, s.minute);
        return new double[]{1 - l, l};
    }

    // ================================================================== expiry-day mode

    static void dayType(Snapshot s, Result r) {
        boolean weekly = s.today.equals(s.expiry) || s.optExpiries.contains(s.today);
        boolean monthly = s.futExpiries.contains(s.today);
        boolean bigEvent = false;
        for (com.krish.niftydirection.model.EventItem e : s.events)
            if (e.importance >= 3 && e.date.equals(s.today) && !e.name.toLowerCase(Locale.US).contains("expiry")) bigEvent = true;
        if ((weekly || monthly) && bigEvent) r.dayType = "EVENT + EXPIRY";
        else if (monthly) r.dayType = "MONTHLY EXPIRY";
        else if (weekly) r.dayType = "WEEKLY EXPIRY";
        else r.dayType = "NORMAL DAY";
    }

    static boolean expiryDay(Result r) { return !r.dayType.equals("NORMAL DAY"); }

    // ================================================================== weights: freshness, reliability, learning

    /** Expected refresh time in minutes for each kind of data; older than that and the weight fades. */
    static double freshness(double ageMin, double expectedMin) {
        if (Double.isNaN(ageMin) || ageMin <= expectedMin) return 1;
        return Math.max(0.3, expectedMin / ageMin);
    }

    static double ageMin(Snapshot s, String source) {
        Long t = s.sourceTime.get(source);
        return t == null ? Double.NaN : Math.max(0, (s.time - t) / 60000.0);
    }

    /** Which data source each factor lives on, and how fresh that kind of data should be (minutes). */
    static final Object[][] SOURCES = {
            {"Nifty spot", 1.0, true}, {"Futures quote", 1.0, true}, {"Option chain", 2.0, true}, {"Futures 5-min candles + OI", 7.0, true},
            {"Stocks (breadth)", 2.0, true}, {"Bank Nifty", 2.0, true}, {"Global markets (Yahoo)", 30.0, false}, {"GIFT Nifty (Kite)", 30.0, false},
            {"FII cash (NSE, daily)", 4 * 1440.0, false}, {"FII futures positions (NSE, daily)", 4 * 1440.0, false}, {"News headlines", 10.0, false},
            {"Nifty weights (NSE)", 7 * 1440.0, false}};

    static double expected(String source) {
        for (Object[] o : SOURCES) if (o[0].equals(source)) return (Double) o[1];
        return 10;
    }

    static String sourceOf(Factor x, Snapshot s) {
        switch (x.key) {
            case "global": return "Global markets (Yahoo)";
            case "fii": return "FII futures positions (NSE, daily)";
            case "gap": return s.live ? "Nifty spot" : "Kite".equals(s.giftSource) ? "GIFT Nifty (Kite)" : null;
            case "futures": return "Futures quote";
            case "options": case "optflow": return "Option chain";
            case "opening": case "prevday": return "Nifty spot";
            case "vwap": case "momentum": case "futflow": return "Futures 5-min candles + OI";
            case "breadth": case "sectors": return "Stocks (breadth)";
            case "bank": return "Bank Nifty";
            case "events": return "News headlines";
            default: return null;
        }
    }

    static void applyQuality(Snapshot s, Result r) {
        for (Factor x : r.factors) {
            String src = sourceOf(x, s);
            if (src != null) {
                boolean liveSrc = false;
                for (Object[] o : SOURCES) if (o[0].equals(src)) liveSrc = (Boolean) o[2];
                x.expectedMin = expected(src);
                x.ageMin = ageMin(s, src);
                if (x.key.equals("fii")) x.ageMin = Math.min(nz(ageMin(s, "FII cash (NSE, daily)")), nz(x.ageMin));
                // market-hours data can only be fresh while the market is open
                if (!liveSrc || s.live) x.freshness = freshness(x.ageMin, x.expectedMin);
            }
            Double learnt = s.calibration.get(x.key);
            x.learnt = learnt == null ? 1 : learnt;
            x.weight = x.maxWeight * x.reliability * x.freshness * x.learnt * x.maturity * x.dayMod;
            if (x.freshness < 0.99 && x.available && !"DAILY".equals(x.quality)) x.quality = "STALE";
        }
        // data-quality rows
        for (Map.Entry<String, Long> e : s.sourceTime.entrySet()) {
            double a = (s.time - e.getValue()) / 60000.0;
            double exp = expected(e.getKey());
            String rel = e.getKey().contains("Yahoo") ? "0.8" : e.getKey().contains("News") ? (s.newsReader.startsWith("Gemini") ? "0.6 (AI)" : "0.35 (words)") : "1.0";
            String q = e.getKey().contains("daily") ? "DAILY" : a <= exp ? "LIVE" : a <= 3 * exp ? "DELAYED" : "STALE";
            if (!s.live && !"LIVE".equals(q) && !"DAILY".equals(q)) q = "CLOSED";   // last price of the session — normal after hours
            r.quality.add(new String[]{e.getKey(), ageText(a) + " (≤" + ageText(exp) + ")", q, rel});
        }
        // DATA DEGRADED: while the market is open, key live inputs must be there and fresh
        if (s.live) {
            String[][] critical = new String[][]{{"Nifty spot", "Nifty price"}, {"Futures quote", "Futures"}, {"Option chain", "Options"},
                    {"Stocks (breadth)", "Breadth"}, {"Bank Nifty", "Bank Nifty"}, {"Futures 5-min candles + OI", "Candles / VWAP"}};
            for (String[] c : critical) {
                Long t = s.sourceTime.get(c[0]);
                double exp = expected(c[0]);
                if (t == null) r.degradedList.add(c[1] + " unavailable");
                else {
                    double a = (s.time - t) / 60000.0;
                    if (a > Math.max(3, 3 * exp)) r.degradedList.add(c[1] + " " + ageText(a) + " old");
                }
            }
            for (String n : s.notes) if (n.startsWith("Kite error") || n.startsWith("KITE_LOGIN")) r.degradedList.add("Kite: " + n.replace("KITE_LOGIN: ", ""));
            r.degraded = !r.degradedList.isEmpty();
        }
    }

    static double nz(double v) { return Double.isNaN(v) ? 1e9 : v; }

    public static String ageText(double min) {
        if (Double.isNaN(min)) return "—";
        if (min < 1) return "now";
        if (min < 60) return Math.round(min) + " min";
        if (min < 48 * 60) return Math.round(min / 60) + " h";
        return Math.round(min / 1440) + " days";
    }

    // ================================================================== three scores → final

    static double group(Result r, String g, boolean countAll, Snapshot s, double[] cov) {
        double sw = 0, sum = 0, base = 0, baseAll = 0;
        for (Factor x : r.factors) {
            if (!x.group.equals(g)) continue;
            boolean possible = s.live || !x.live || x.key.equals("breadth") || x.key.equals("bank") || x.key.equals("sectors");
            if (possible || countAll) baseAll += x.maxWeight;
            if (!x.available || x.weight <= 0) continue;
            base += x.maxWeight;
            sw += x.weight;
            sum += x.value * x.weight;
        }
        cov[0] = baseAll > 0 ? Math.min(1, base / baseAll) : 0;
        return sw > 0 ? sum / sw * 100 : Double.NaN;
    }

    static void score(Snapshot s, Result r) {
        double[] cs = new double[1], cl = new double[1];
        r.structScore = group(r, Factor.STRUCT, false, s, cs);
        r.liveScore = group(r, Factor.LIVE, false, s, cl);
        r.preScore = r.structScore;
        double[] b = blend(s, r);
        double aS = Double.isNaN(r.structScore) ? 0 : b[0], aL = Double.isNaN(r.liveScore) ? 0 : b[1];
        double tot = aS + aL;
        double base = tot > 0 ? ((aS > 0 ? aS * r.structScore : 0) + (aL > 0 ? aL * r.liveScore : 0)) / tot : 0;
        r.structWeight = tot > 0 ? aS / tot : 0;
        r.liveWeight = tot > 0 ? aL / tot : 0;
        // event adjustment: small, and only when the news is clear
        Factor ev = r.factor("events");
        r.eventAdj = 0;
        if (ev != null && ev.available && !Double.isNaN(r.eventScore) && Math.abs(r.eventScore) >= 10)
            r.eventAdj = Math.max(-15, Math.min(15, 0.15 * r.eventScore * ev.reliability));
        r.score = Math.max(-100, Math.min(100, base + r.eventAdj));
        r.coverage = b[0] * cs[0] + b[1] * cl[0];

        // agreement across all voting factors, weighted as they count in the final score
        double sum = 0, abs = 0;
        for (Factor x : r.factors) {
            if (!x.available || x.weight <= 0) continue;
            double g = x.group.equals(Factor.STRUCT) ? r.structWeight : x.group.equals(Factor.LIVE) ? r.liveWeight : 0;
            sum += x.value * x.weight * g;
            abs += Math.abs(x.value) * x.weight * g;
        }
        r.agreement = abs > 0 ? Math.abs(sum) / abs : 0;
        r.directionScore = (int) Math.round(50 + r.score / 2);
        double degr = !r.degraded ? 1 : r.degradedList.size() >= 3 ? 0.5 : 0.7;
        double c = Math.min(1, Math.abs(r.score) / 60.0) * (0.5 + 0.5 * r.agreement) * (0.55 + 0.45 * r.coverage) * r.volMod * r.eventMod * degr;
        r.confidence = (int) Math.round(100 * c);
        if (r.degraded) r.warnings.add("DATA DEGRADED — " + String.join(", ", r.degradedList) + ". Confidence cut; don't rely on the score until data is back.");
        if (r.coverage < 0.5) r.warnings.add("Less than half of the evidence has data. Treat the score as rough.");
    }

    // ================================================================== internal disagreement

    /**
     * Even when structure and live don't formally conflict, strong pieces of evidence may point the other way.
     * Opposing share = weight of strong factors (|v| ≥ 0.3) against the score's side ÷ weight of all factors.
     * 35%+ from at least two factors = HIGH INTERNAL DISAGREEMENT (confidence × 0.75).
     */
    static void disagreement(Result r) {
        double side = Math.signum(r.score);
        if (side == 0) return;
        double opp = 0, all = 0, mean = 0, wsum = 0;
        List<Factor> strong = new ArrayList<>();
        for (Factor x : r.factors) {
            if (!x.available || x.weight <= 0) continue;
            double g = x.group.equals(Factor.STRUCT) ? r.structWeight : x.group.equals(Factor.LIVE) ? r.liveWeight : 0;
            double w = x.weight * g;
            if (w <= 0) continue;
            all += w * Math.abs(x.value);
            mean += w * x.value; wsum += w;
            if (Math.signum(x.value) == -side && Math.abs(x.value) >= 0.3) { opp += w * Math.abs(x.value); strong.add(x); }
        }
        if (all <= 0) return;
        mean /= wsum;
        double var = 0;
        for (Factor x : r.factors) {
            if (!x.available || x.weight <= 0) continue;
            double g = x.group.equals(Factor.STRUCT) ? r.structWeight : x.group.equals(Factor.LIVE) ? r.liveWeight : 0;
            var += x.weight * g * (x.value - mean) * (x.value - mean);
        }
        r.dispersion = Math.sqrt(var / wsum);
        r.opposingShare = opp / all;
        strong.sort((a, b) -> Double.compare(Math.abs(b.value) * b.weight, Math.abs(a.value) * a.weight));
        for (Factor x : strong) r.opposers.add(x.name + " " + String.format(Locale.US, "%+.2f", x.value));
        if (r.opposingShare >= 0.35 && strong.size() >= 2) {
            r.disagreement = true;
            r.confidence = (int) Math.round(r.confidence * 0.75);
            r.warnings.add(String.format(Locale.US, "HIGH INTERNAL DISAGREEMENT: %.0f%% of the evidence points the other way (", r.opposingShare * 100)
                    + String.join(", ", r.opposers.subList(0, Math.min(4, r.opposers.size()))) + ").");
        }
    }

    // ================================================================== regime: hysteresis, conflict, no edge

    static void regime(Snapshot s, Result r, double[] or) {
        // range signs are weighed (0..1 each), not counted as hard yes / no
        double rangeVotes = 0;
        List<String> why = new ArrayList<>();
        double vixRange = vixRangeVote(s, r);
        if (vixRange > 0.05) { rangeVotes += vixRange; why.add(String.format(Locale.US, "calm volatility (%s, weight %.1f)", r.volClass.isEmpty() ? "VIX" : r.volClass.toLowerCase(Locale.US), vixRange)); }
        if (s.live) {
            int x = vwapCrosses(s);
            if (x >= 3) { double v = Math.min(1, (x - 2) / 3.0); rangeVotes += v; why.add("price keeps crossing VWAP (" + x + "×)"); }
        }
        if (!Double.isNaN(r.ceWall) && !Double.isNaN(r.peWall) && r.spot > 0) {
            double width = (r.ceWall - r.peWall) / r.spot * 100;
            double v = Math.max(0, Math.min(1, (1.8 - width) / 1.0));
            if (v > 0.05) { rangeVotes += v; why.add(String.format(Locale.US, "call and put OI walls only %.1f%% apart", width)); }
        }
        if (r.advances + r.declines >= 20) {
            double ratio = r.advances / (double) (r.advances + r.declines);
            double v = Math.max(0, 1 - Math.abs(ratio - 0.5) / 0.15);
            if (v > 0.05) { rangeVotes += v; why.add("stocks split between up and down"); }
        }
        if ("Compression".equals(r.momentumState)) { rangeVotes += 0.8; why.add("candles getting smaller (compression)"); }
        if (expiryDay(r)) {
            rangeVotes += 0.4; why.add("expiry day (option sellers pin the price)");
            if (s.live && s.minute >= 12 * 60 + 30 && !Double.isNaN(r.maxPain) && r.spot > 0 && Math.abs(r.spot - r.maxPain) / r.spot * 100 < 0.4) {
                r.maxPainMagnet = true;
                rangeVotes += 0.6; why.add("price is near max pain " + n0(r.maxPain) + " late on expiry day");
            }
        }

        double sc = r.score;
        String dir = pickDirection(sc, s.prevRegime);
        if (!dir.isEmpty() && rangeVotes >= 2.5) {
            r.warnings.add("The score leans " + (sc > 0 ? "up" : "down") + ", but range signs win: " + String.join(", ", why) + ".");
            dir = "";
        }

        // CONFLICT: structure and live price strongly disagree
        boolean conflict = s.live && r.stage >= 3 && !Double.isNaN(r.structScore) && !Double.isNaN(r.liveScore)
                && Math.abs(r.structScore) >= CONFLICT_AT && Math.abs(r.liveScore) >= CONFLICT_AT
                && Math.signum(r.structScore) != Math.signum(r.liveScore);
        if (conflict) {
            r.rawRegime = Result.CONFLICT;
            r.confidence = r.confidence / 2;
            r.conflictLines.add("Structure (positioning): " + side(r.structScore) + " " + sg0(r.structScore));
            r.conflictLines.add("Live price action: " + side(r.liveScore) + " " + sg0(r.liveScore));
            for (Factor x : r.factors) if (x.available && Math.abs(x.value) >= 0.3 && (x.group.equals(Factor.STRUCT) || x.group.equals(Factor.LIVE)))
                r.conflictLines.add(x.name + ": " + (x.value > 0 ? "bullish" : "bearish") + " (" + x.reading + ")");
            r.warnings.add("CONFLICT: the morning structure is " + side(r.structScore) + " but price is " + side(r.liveScore)
                    + ". The " + (r.structScore > 0 ? "bullish" : "bearish") + " thesis is losing confirmation.");
        } else if (!dir.isEmpty() && (r.confidence < 25 || (r.disagreement && r.confidence < 35))) {
            r.rawRegime = Result.NO_EDGE;
            r.leans = sc > 0 ? "leans up" : "leans down";
        } else if (!dir.isEmpty()) {
            r.rawRegime = dir;
            r.strong = Math.abs(sc) >= STRONG_AT && r.agreement >= 0.6 && r.confidence >= 60 && !r.disagreement;
        } else if (rangeVotes >= 1.6) {
            r.rawRegime = Result.RANGE;
            r.notes.add(String.format(Locale.US, "Range signs (%.1f of 1.6 needed): ", rangeVotes) + String.join(", ", why) + ".");
        } else {
            r.rawRegime = Result.NO_EDGE;
            r.leans = Math.abs(sc) >= 10 ? (sc > 0 ? "leans up" : "leans down") : "";
        }
        r.regime = r.rawRegime;
        persistence(s, r);
    }

    /**
     * 2-of-3 confirmation: a straight flip (bullish ↔ bearish) needs the same new side on this update and on at least
     * one of the two before it. Until then the old side is kept, marked "pending", with lower confidence.
     * Hard overrides: a very strong score (±60) or HIGH event risk from fresh news.
     */
    static void persistence(Snapshot s, Result r) {
        String prev = s.prevRegime, now = r.rawRegime;
        boolean flip = (Result.BULLISH.equals(prev) && Result.BEARISH.equals(now)) || (Result.BEARISH.equals(prev) && Result.BULLISH.equals(now));
        if (!flip) return;
        boolean override = Math.abs(r.score) >= 60 || (r.eventRisk.equals("HIGH") && r.eventWhy.startsWith("big news"));
        if (override) { r.notes.add("Regime flip allowed at once (" + (Math.abs(r.score) >= 60 ? "very strong score" : "high-risk news") + ")."); return; }
        int seen = 0;
        List<double[]> h = s.history;
        for (int i = Math.max(0, h.size() - 2); i < h.size(); i++) {
            double[] p = h.get(i);
            if (p.length > 7 && Result.fromNum(p[7]).equals(now)) seen++;
        }
        if (seen >= 1) return;   // 2 of the last 3 readings agree → confirmed
        r.pending = now;
        r.regime = prev;
        r.strong = false;
        r.confidence = (int) Math.round(r.confidence * 0.6);
        r.transition = cap(prev) + " → " + cap(now).toLowerCase(Locale.US) + "? (1 signal, needs 1 more update to confirm)";
    }

    /** Hysteresis: enter a side at ±25, keep it until the score falls back through ±15. "" = no side. */
    public static String pickDirection(double sc, String prev) {
        if (Result.BULLISH.equals(prev) && sc >= EXIT) return Result.BULLISH;
        if (Result.BEARISH.equals(prev) && sc <= -EXIT) return Result.BEARISH;
        if (sc >= ENTER) return Result.BULLISH;
        if (sc <= -ENTER) return Result.BEARISH;
        return "";
    }

    static String side(double v) { return v > 0 ? "bullish" : "bearish"; }

    // ================================================================== transitions (from today's score line)

    static void transition(Snapshot s, Result r) {
        List<double[]> h = s.history;
        if (h.isEmpty() || !r.pending.isEmpty()) return;
        double[] last = h.get(h.size() - 1);
        String prevReg = Result.fromNum(last[3]);
        double peak = r.score, trough = r.score, ago30 = Double.NaN;
        for (double[] p : h) {
            if (p[0] >= s.minute - 60) { peak = Math.max(peak, p[1]); trough = Math.min(trough, p[1]); }
            if (p[0] <= s.minute - 25 && p[0] >= s.minute - 40) ago30 = p[1];
        }
        if (!Double.isNaN(ago30)) r.slope30 = r.score - ago30;
        if (!prevReg.equals(r.regime) && last[0] >= s.minute - 30) {
            r.transition = cap(prevReg) + " → " + cap(r.regime);
            return;
        }
        if (Result.BULLISH.equals(r.regime) && r.score <= peak - 15) r.transition = "Bullish → weakening (score " + sg0(peak) + " → " + sg0(r.score) + " in the last hour)";
        else if (Result.BEARISH.equals(r.regime) && r.score >= trough + 15) r.transition = "Bearish → recovering (score " + sg0(trough) + " → " + sg0(r.score) + " in the last hour)";
        else if ((Result.RANGE.equals(r.regime) || Result.NO_EDGE.equals(r.regime)) && !Double.isNaN(r.slope30) && Math.abs(r.slope30) >= 15)
            r.transition = cap(r.regime) + " → building " + (r.slope30 > 0 ? "bullish" : "bearish") + " (" + sg0(r.slope30) + " in 30 min)";
    }

    static String cap(String regime) { return regime.charAt(0) + regime.substring(1).toLowerCase(Locale.US); }

    // ================================================================== levels

    static void levels(Snapshot s, Result r, double[] or) {
        double spot = r.spot;
        if (spot <= 0) return;
        List<double[]> sup = new ArrayList<>(), res = new ArrayList<>();
        String[] kinds = {"Put OI wall", "Call OI wall", "Previous day low", "Previous day high", "VWAP", "Opening-range low", "Opening-range high", "Day low", "Day high",
                "Call wall building (live)", "Put wall building (live)"};
        if (r.optFlow != null && r.optFlow.newCallWall != null) res.add(new double[]{r.optFlow.newCallWall.strike, 9});
        if (r.optFlow != null && r.optFlow.newPutWall != null) sup.add(new double[]{r.optFlow.newPutWall.strike, 10});
        if (!Double.isNaN(r.peWall)) sup.add(new double[]{r.peWall, 0});
        if (!Double.isNaN(r.ceWall)) res.add(new double[]{r.ceWall, 1});
        if (s.prevDay != null && s.live) {
            (s.prevDay.l < spot ? sup : res).add(new double[]{s.prevDay.l, 2});
            (s.prevDay.h > spot ? res : sup).add(new double[]{s.prevDay.h, 3});
        }
        if (!Double.isNaN(r.vwapSpot)) (r.vwapSpot < spot ? sup : res).add(new double[]{r.vwapSpot, 4});
        if (or != null) {
            (or[1] < spot ? sup : res).add(new double[]{or[1], 5});
            (or[0] > spot ? res : sup).add(new double[]{or[0], 6});
        }
        if (s.live && s.nifty != null && s.nifty.high > 0) {
            if (s.nifty.low < spot) sup.add(new double[]{s.nifty.low, 7});
            if (s.nifty.high > spot) res.add(new double[]{s.nifty.high, 8});
        }
        double[] bs = null, br = null;
        for (double[] x : sup) if (x[0] < spot && (bs == null || x[0] > bs[0])) bs = x;
        for (double[] x : res) if (x[0] > spot && (br == null || x[0] < br[0])) br = x;
        if (bs != null) { r.support = bs[0]; r.supportWhy = kinds[(int) bs[1]]; }
        if (br != null) { r.resistance = br[0]; r.resistanceWhy = kinds[(int) br[1]]; }
    }

    // ================================================================== state + action

    static void stateAndAction(Snapshot s, Result r) {
        String sup = Double.isNaN(r.support) ? "support" : n0(r.support);
        String res = Double.isNaN(r.resistance) ? "resistance" : n0(r.resistance);
        String reg = r.regime;
        boolean bull = reg.equals(Result.BULLISH), bear = reg.equals(Result.BEARISH);
        double vw = r.vwapSpot, spot = r.spot;

        if (!s.live) {
            r.state = "Before the open — idea only";
            String side = bull ? "bullish" : bear ? "bearish" : reg.equals(Result.RANGE) ? "range" : "no clear side";
            r.action = "Morning idea: " + side + " (" + r.confidence + "/100). Let the first 15 minutes after 9:15 confirm it before acting.";
        } else if (r.stage == 3) {
            r.state = "Opening — still forming";
            r.action = "Too early. Watch whether the gap holds, where the first 15-minute range breaks, and whether Bank Nifty and breadth agree.";
        } else if (reg.equals(Result.CONFLICT)) {
            r.state = "Conflict — structure vs price";
            r.action = "Positioning says " + side(r.structScore) + ", price says " + side(r.liveScore) + ". Stand aside or trade small until one side gives way. "
                    + (Double.isNaN(vw) ? "" : "VWAP (" + n0(vw) + ") is the line to watch.");
        } else if (reg.equals(Result.NO_EDGE)) {
            r.state = "No edge — evidence weak or mixed";
            r.action = "No trade is the right default. Wait for a clear move away from " + (Double.isNaN(vw) ? "VWAP" : "VWAP (" + n0(vw) + ")")
                    + " with breadth and Bank Nifty agreeing.";
        } else {
            boolean aboveVwap = !Double.isNaN(vw) && spot > vw;
            boolean nearVwap = !Double.isNaN(vw) && Math.abs(spot - vw) / spot * 100 < 0.15;
            int lastDir = lastBarsDirection(s.niftyBars, 3);
            double dayHi = s.nifty != null ? s.nifty.high : 0, dayLo = s.nifty != null ? s.nifty.low : 0;
            if (bull) {
                if (!Double.isNaN(vw) && !aboveVwap) { r.state = "Weakening — below VWAP"; r.action = "Up-bias is under test. Stand aside until price is back above VWAP (" + n0(vw) + ")."; }
                else if (nearVwap || lastDir < 0) { r.state = "Pullback in an uptrend"; r.action = "Wait for the pullback to hold " + sup + " and turn up, then look for long setups. Stop idea: below " + sup + "."; }
                else if (dayHi > 0 && (dayHi - spot) / spot * 100 < 0.1) { r.state = "Trending up — at the day high"; r.action = "Trend is up but price is stretched. Don't chase; buy dips toward VWAP" + (Double.isNaN(vw) ? "" : " (" + n0(vw) + ")") + ". Next hurdle " + res + "."; }
                else { r.state = "Trending up"; r.action = "Bias is up. Prefer long setups on dips; watch " + res + " as the next hurdle."; }
            } else if (bear) {
                if (!Double.isNaN(vw) && aboveVwap) { r.state = "Recovering — above VWAP"; r.action = "Down-bias is under test. Stand aside until price is back below VWAP (" + n0(vw) + ")."; }
                else if (nearVwap || lastDir > 0) { r.state = "Bounce in a downtrend"; r.action = "Wait for the bounce to fail near " + res + " and turn down, then look for short setups. Stop idea: above " + res + "."; }
                else if (dayLo > 0 && (spot - dayLo) / spot * 100 < 0.1) { r.state = "Trending down — at the day low"; r.action = "Trend is down but price is stretched. Don't chase; sell bounces toward VWAP" + (Double.isNaN(vw) ? "" : " (" + n0(vw) + ")") + ". Next floor " + sup + "."; }
                else { r.state = "Trending down"; r.action = "Bias is down. Prefer short setups on bounces; watch " + sup + " as the next floor."; }
            } else {
                r.state = nearVwap ? "Range — circling VWAP" : "Range";
                r.action = "Range day more likely between " + sup + " and " + res + ". Avoid chasing breakouts. Wait for a clean break with breadth and Bank Nifty agreeing.";
            }
            if (r.transition.startsWith("Bullish → weakening") || r.transition.startsWith("Bearish → recovering"))
                r.action += " The trend is losing strength — tighten stops, don't add.";
        }
        if (!r.pending.isEmpty()) r.action += " A flip to " + r.pending.toLowerCase(Locale.US) + " is showing but not confirmed yet — don't act on it until the next update agrees.";
        if (r.maxPainMagnet) r.action += " Expiry day: price is near max pain " + n0(r.maxPain) + ", which often acts as a magnet into the close.";
        if (!r.eventRisk.equals("LOW")) r.action += " Event risk " + r.eventRisk + " (" + r.eventWhy + "): expect bigger swings, size down.";
        if (r.degraded) r.action = "DATA DEGRADED — wait for fresh data. " + r.action;
        if (r.confidence < 35 && (bull || bear)) r.action += " Confidence is low — no trade is a fine choice.";
    }

    // ================================================================== STRUCTURAL factors

    /** Two parts kept apart: global risk appetite (US, US futures, Asia) and India-specific macro (rupee, crude, US yields, dollar). */
    static Factor global(Snapshot s, Result r) {
        Factor f = new Factor("global", "Global risk + India macro", Factor.STRUCT, false, 15).src("Yahoo", 0.8, "DELAYED");
        Map<String, Double> g = s.global;
        double us = avg(g.get("S&P 500"), g.get("Nasdaq")), usf = val(g.get("US futures")), asia = avg(g.get("Nikkei"), g.get("Hang Seng"), g.get("Kospi"));
        double w = 0, sum = 0;
        if (!Double.isNaN(us)) { sum += 0.35 * us; w += 0.35; }
        if (!Double.isNaN(usf)) { sum += 0.30 * usf; w += 0.30; }
        if (!Double.isNaN(asia)) { sum += 0.35 * asia; w += 0.35; }
        double risk = w > 0 ? Math.tanh(sum / w / 0.8) : Double.NaN;
        double crude = val(g.get("Brent crude")), inr = val(g.get("USD/INR")), yld = val(g.get("US 10Y yield")), dxy = val(g.get("Dollar index"));
        double mw = 0, ms = 0;   // rising crude, weaker rupee, rising US yields, stronger dollar = bad for India
        if (!Double.isNaN(inr)) { ms -= 0.35 * Math.tanh(inr / 0.3); mw += 0.35; }
        if (!Double.isNaN(crude)) { ms -= 0.30 * Math.tanh(crude / 1.5); mw += 0.30; }
        if (!Double.isNaN(yld)) { ms -= 0.20 * Math.tanh(yld / 1.5); mw += 0.20; }
        if (!Double.isNaN(dxy)) { ms -= 0.15 * Math.tanh(dxy / 0.4); mw += 0.15; }
        double macro = mw > 0 ? ms / mw : Double.NaN;
        r.globalRisk = risk; r.indiaMacro = macro;
        if (Double.isNaN(risk) && Double.isNaN(macro)) return f.missing("Global prices could not be loaded (Yahoo Finance). Turn it on in Settings or check the internet.");
        double v = Double.isNaN(risk) ? macro : Double.isNaN(macro) ? risk : 0.65 * risk + 0.35 * macro;
        StringBuilder d = new StringBuilder("Risk appetite: US " + pctS(us) + ", US futures " + pctS(usf) + ", Asia " + pctS(asia) + " → " + sg0(100 * nzv(risk)) + ". ");
        d.append("India macro: USD/INR ").append(pctS(inr)).append(", crude ").append(pctS(crude)).append(", US 10Y ").append(pctS(yld))
                .append(", dollar ").append(pctS(dxy)).append(" → ").append(sg0(100 * nzv(macro))).append(".");
        String rd = tone(nzv(risk), "Risk-on", "Risk-off", "Neutral") + " · India macro " + tone(nzv(macro), "tailwind", "headwind", "neutral");
        if (!Double.isNaN(risk) && !Double.isNaN(macro) && Math.abs(risk) >= 0.3 && Math.abs(macro) >= 0.3 && Math.signum(risk) != Math.signum(macro))
            d.append(" Split: world is ").append(risk > 0 ? "risk-on" : "risk-off").append(" but India-specific factors are ").append(macro > 0 ? "helping" : "hurting").append(" — the two partly cancel.");
        return f.set(v, rd, d.toString());
    }

    static double nzv(double v) { return Double.isNaN(v) ? 0 : v; }

    static String gapClass(double g) {
        double a = Math.abs(g);
        String size = a < 0.25 ? "Normal" : a < 0.75 ? "Moderate" : a < 1.25 ? "Large" : "Extreme";
        return a < 0.1 ? "Flat open" : size + " gap-" + (g > 0 ? "up" : "down");
    }

    static Factor gap(Snapshot s, Result r) {
        Factor f = new Factor("gap", "GIFT Nifty / gap", Factor.STRUCT, false, 15);
        if (s.live && s.nifty != null && s.nifty.open > 0 && s.nifty.prevClose > 0) {
            f.src("Kite (opening gap)", 1.0, "LIVE");
            double g = (s.nifty.open - s.nifty.prevClose) / s.nifty.prevClose * 100;
            double spot = s.nifty.last, dir = Math.signum(g);
            r.gapClass = gapClass(g);
            String d = "Nifty opened " + pctS(g) + " (" + sgn(s.nifty.open - s.nifty.prevClose) + " pts): " + r.gapClass.toLowerCase(Locale.US) + ". ";
            double v;
            if (Math.abs(g) < 0.1) {
                r.gapBehavior = "No gap";
                v = 0.3 * Math.tanh((spot - s.nifty.prevClose) / s.nifty.prevClose * 100 / 0.4);
                d += "No real gap, so this only reads where price is vs yesterday's close.";
            } else {
                double ext = (spot - s.nifty.open) / s.nifty.open * 100 * dir;          // + = moving further in the gap's direction
                double back = (spot - s.nifty.prevClose) / s.nifty.prevClose * 100 * dir; // + = still on the gap's side of yesterday's close
                double sizeW = Math.tanh(Math.abs(g) / 0.75);
                if (back < -0.2) { r.gapBehavior = "Gap reversal"; v = -dir * (0.6 + 0.3 * sizeW); d += "Price has gone through yesterday's close to the other side — a gap reversal (strong sign against the gap)."; }
                else if (back <= 0.05) { r.gapBehavior = "Gap fill"; v = -dir * 0.2; d += "The gap has been filled — price is back at yesterday's close."; }
                else if (ext >= 0.25) { r.gapBehavior = "Gap-and-go"; v = dir * (0.7 + 0.3 * sizeW); d += "Price kept going in the gap's direction — gap-and-go."; }
                else if (ext >= -0.5 * Math.abs(g)) { r.gapBehavior = "Gap hold"; v = dir * (0.4 + 0.2 * sizeW); d += "Most of the gap is holding."; }
                else { r.gapBehavior = "Gap fading"; v = dir * 0.1; d += "More than half the gap has faded."; }
                if (Math.abs(g) >= 1.25) d += " Extreme gaps often see some give-back early; judge after 9:45.";
            }
            return f.set(v, r.gapClass + " · " + r.gapBehavior, d);
        }
        if (!Double.isNaN(s.giftNifty) && s.giftNifty > 0) {
            boolean kite = "Kite".equals(s.giftSource);
            f.src(kite ? "Kite" : "typed by you", kite ? 1.0 : 0.5, kite ? "LIVE" : "TYPED");
            Quote ref = s.fut != null && s.fut.ok() ? s.fut : s.nifty;
            if (ref == null || !ref.ok()) return f.missing("GIFT Nifty known, but no Nifty price to compare with.");
            double g = (s.giftNifty - ref.last) / ref.last * 100;
            String what = ref == s.fut ? "Nifty futures close" : "Nifty close";
            r.gapClass = gapClass(g) + " expected";
            return f.set(Math.tanh(g / 0.6), "GIFT " + pctS(g) + " · " + gapClass(g), "GIFT Nifty " + n0(s.giftNifty) + " (" + s.giftSource + ") is " + pctS(g)
                    + " (" + sgn(s.giftNifty - ref.last) + " pts) vs the " + what + " " + n0(ref.last) + ": " + r.gapClass.toLowerCase(Locale.US) + "."
                    + (Math.abs(g) >= 0.75 ? " After a large gap, watch whether it holds, fills or reverses in the first 30 minutes." : ""));
        }
        return f.missing("GIFT Nifty not available from Kite right now. You can type it in Settings. After 9:15 the real opening gap is used.");
    }

    /** FII: cash, futures positioning and DII kept apart. DII buying only softens FII cash selling; it never cancels FII futures positions. */
    static Factor fii(Snapshot s) {
        Factor f = new Factor("fii", "FII positioning (background)", Factor.STRUCT, false, 15).src("NSE", 1.0, "DAILY");
        double w = 0, sum = 0;
        StringBuilder d = new StringBuilder("Daily data — this is background positioning, not what FIIs are doing right now. ");
        if (!Double.isNaN(s.fiiCash)) {
            double cash = Math.tanh(s.fiiCash / 2500);
            String dii = "";
            if (!Double.isNaN(s.diiCash) && s.fiiCash < 0 && s.diiCash > 0) {
                double soften = Math.min(0.5, s.diiCash / Math.abs(s.fiiCash) * 0.5);
                cash *= 1 - soften;
                dii = " DII bought " + crS(s.diiCash) + ", which softens the selling.";
            } else if (!Double.isNaN(s.diiCash)) dii = " DII " + (s.diiCash >= 0 ? "bought " : "sold ") + crS(Math.abs(s.diiCash)) + ".";
            sum += 0.35 * cash; w += 0.35;
            d.append("FII cash ").append(s.fiiCash >= 0 ? "bought " : "sold ").append(crS(Math.abs(s.fiiCash)))
                    .append(s.fiiDate.isEmpty() ? "" : " (" + s.fiiDate + ")").append(".").append(dii).append(" ");
        }
        if (!Double.isNaN(s.fiiIdxLong)) {
            sum += 0.35 * clamp((s.fiiIdxLong - 50) / 25); w += 0.35;
            d.append("FIIs are ").append(f0(s.fiiIdxLong)).append("% long in index futures");
            if (!Double.isNaN(s.fiiIdxLongPrev)) {
                double ch = s.fiiIdxLong - s.fiiIdxLongPrev;
                sum += 0.30 * Math.tanh(ch / 3); w += 0.30;
                d.append(" (").append(ch >= 0 ? "+" : "").append(f1(ch)).append(" pts vs the day before)");
            }
            d.append(s.fiiOiDate.isEmpty() ? "." : " as of " + s.fiiOiDate + ".");
        }
        if (w == 0) return f.missing("NSE FII data not loaded yet (published in the evening). You can type FII cash in Settings.");
        if ("typed".equals(s.fiiDate)) f.src("typed by you", 0.5, "TYPED");
        double v = sum / w;
        return f.set(v, tone(v, "Supportive", "Selling pressure", "Neutral"), d.toString().trim());
    }

    static Factor futures(Snapshot s, Result r) {
        Factor f = new Factor("futures", "Futures positioning (since yesterday)", Factor.STRUCT, false, 20).src("Kite", 1.0, "LIVE");
        Quote q = s.fut;
        if (q == null || !q.ok() || q.prevClose <= 0) return f.missing("Nifty futures quote not available.");
        double p = q.pct();
        double oiNow = q.oi + (s.futNext != null ? s.futNext.oi : 0);
        double buildV; String kind = "Price only";
        StringBuilder d = new StringBuilder("Futures " + pctS(p));
        if (s.futPrevOi > 0 && oiNow > 0) {
            double oi = (oiNow - s.futPrevOi) / s.futPrevOi * 100;
            double[] b = buildup(p, oi, 0.1, 0.5, 5);
            buildV = b[0]; kind = BUILD[(int) b[1]];
            d.append(", open interest ").append(pctS(oi)).append(" (near + next month). ");
        } else {
            buildV = 0.5 * Math.tanh(p / 0.5);
            d.append(". Yesterday's OI is not known, so only the price move counts. ");
        }
        double basisV = 0;
        if (s.nifty != null && s.nifty.ok()) {
            double basis = q.last - s.nifty.last;
            double fair = s.nifty.last * Greeks.RATE * Math.max(0, s.futDaysToExpiry) / 365.0;
            double excess = (basis - fair) / s.nifty.last * 100;
            basisV = Math.tanh(excess / 0.15);
            d.append("Premium ").append(sgn(basis)).append(" pts vs about ").append(n0(fair)).append(" fair → ")
                    .append(excess > 0.05 ? "rich (buyers paying up)." : excess < -0.05 ? "thin (weak demand)." : "normal.");
        }
        if (r.dayType.equals("MONTHLY EXPIRY") || r.dayType.equals("EVENT + EXPIRY")) { f.dayMod = 0.8; d.append(" Monthly expiry: rollover distorts OI, counts a bit less."); }
        return f.set(0.8 * buildV + 0.2 * basisV, kind, d.toString().trim());
    }

    static final String[] BUILD = {"No clear build-up", "Long buildup", "Short buildup", "Short covering", "Long unwinding"};

    /** {value, kind}: price up + OI up = long buildup … thresholds in %. */
    static double[] buildup(double p, double oi, double pMin, double oiMin, double oiFull) {
        double str = 0.5 + 0.5 * Math.min(1, Math.abs(oi) / oiFull);
        if (Math.abs(p) < pMin || Math.abs(oi) < oiMin) return new double[]{0.3 * Math.tanh(p / (pMin * 5)), 0};
        if (p > 0 && oi > 0) return new double[]{str, 1};
        if (p < 0 && oi > 0) return new double[]{-str, 2};
        if (p > 0) return new double[]{0.6 * str, 3};
        return new double[]{-0.6 * str, 4};
    }

    static Factor options(Snapshot s, Result r) {
        Factor f = new Factor("options", "Option positioning (since yesterday)", Factor.STRUCT, false, 20).src("Kite", 1.0, "LIVE");
        List<OptionRow> rows = s.chain;
        double spot = s.spot();
        if (rows.isEmpty() || spot <= 0) return f.missing("Option chain not loaded.");
        r.maxPain = Chain.maxPain(rows);
        r.pcr = Chain.pcr(rows);
        r.atmIv = Chain.atmIv(rows, spot);
        OptionRow cw = Chain.ceWall(rows, spot), pw = Chain.peWall(rows, spot);
        if (cw != null) r.ceWall = cw.strike;
        if (pw != null) r.peWall = pw.strike;
        double pcrV = Double.isNaN(r.pcr) ? 0 : Math.tanh((r.pcr - 1.0) / 0.35);
        if (r.pcr > 1.6) pcrV *= 0.5;
        double wallV = 0;
        if (cw != null && pw != null && cw.ceOi + pw.peOi > 0) wallV = (pw.peOi - cw.ceOi) / (pw.peOi + cw.ceOi);
        StringBuilder d = new StringBuilder();
        double v; String reading;
        if (Chain.anyPrev(rows)) {
            double[] fl = Chain.flow(rows, spot, s.strikeStep, 5);
            double flowV = fl[1] > 0 ? Math.tanh(fl[0] / (0.08 * fl[1])) : 0;
            v = 0.5 * flowV + 0.25 * pcrV + 0.25 * wallV;
            d.append("Since yesterday, near the money: ").append(fl[3] > 0 ? "put writing " + lots(fl[3]) : "little put writing")
                    .append(", ").append(fl[2] > 0 ? "call writing " + lots(fl[2]) : "little call writing").append(". ");
            reading = flowV > 0.25 ? "Put writers in control" : flowV < -0.25 ? "Call writers in control" : "Balanced writing";
        } else {
            v = 0.5 * pcrV + 0.5 * wallV;
            reading = "Walls + PCR only";
            d.append("Yesterday's OI not loaded, so only OI size is read, not OI change. ");
        }
        d.append("PCR ").append(f2(r.pcr)).append(". ");
        if (pw != null) d.append("Support wall ").append(n0(pw.strike)).append(" PE (").append(lots(pw.peOi)).append("). ");
        if (cw != null) d.append("Resistance wall ").append(n0(cw.strike)).append(" CE (").append(lots(cw.ceOi)).append("). ");
        if (!Double.isNaN(r.atmIv)) d.append("ATM IV ").append(f1(r.atmIv)).append("%. ");
        d.append("Max pain ").append(n0(r.maxPain)).append(" (").append(s.expiry).append(" expiry) is shown for location only — it is not scored.");
        if (expiryDay(r)) { f.dayMod = 0.6; d.append(" Expiry day: yesterday-vs-today OI is mostly positions closing, so this counts less."); }
        r.optionsLine = (pw != null ? "Support " + n0(pw.strike) + " PE" : "") + (cw != null ? " · Resistance " + n0(cw.strike) + " CE" : "") + " · PCR " + f2(r.pcr);
        return f.set(v, reading, d.toString());
    }

    static Factor prevDay(Snapshot s) {
        Factor f = new Factor("prevday", "Previous-day structure", Factor.STRUCT, false, 15).src("Kite", 1.0, "LIVE");
        Candle p = s.prevDay;
        if (p == null || p.h <= p.l) return f.missing("Previous day's candle not loaded.");
        double pos = (p.c - p.l) / (p.h - p.l);
        double v = (2 * pos - 1) * (s.live ? 0.5 : 1.0);
        String d = "Last session closed in the " + (pos > 0.66 ? "top" : pos < 0.33 ? "bottom" : "middle") + " third of its range (" + n0(p.l) + "–" + n0(p.h) + ").";
        String rd = pos > 0.66 ? "Strong close" : pos < 0.33 ? "Weak close" : "Middle close";
        double spot = s.spot();
        if (s.live && spot > 0) {
            if (spot > p.h) { v += 0.5; d += " Now above the previous high — buyers in control."; rd = "Above prev. high"; }
            else if (spot < p.l) { v -= 0.5; d += " Now below the previous low — sellers in control."; rd = "Below prev. low"; }
            else d += " Now inside the previous range.";
        }
        return f.set(v, rd, d);
    }

    // ================================================================== LIVE factors

    static double[] openingRange(Snapshot s) {
        if (!s.live || s.niftyBars.size() < 3 || s.minute < 9 * 60 + 30) return null;
        double h = -1e18, l = 1e18;
        for (int i = 0; i < 3; i++) { h = Math.max(h, s.niftyBars.get(i).h); l = Math.min(l, s.niftyBars.get(i).l); }
        return new double[]{h, l};
    }

    static Factor opening(Snapshot s, double[] or) {
        Factor f = new Factor("opening", "Opening structure", Factor.LIVE, true, 10);
        Quote n = s.nifty;
        if (!s.live || n == null || n.open <= 0) return f.missing("Starts at 9:15.");
        double spot = n.last;
        if (or == null) {
            double m = (spot - n.open) / n.open * 100;
            return f.set(Math.tanh(m / 0.25), m > 0 ? "Above the open" : "Below the open", "First 15 minutes still forming. Price is " + pctS(m) + " from the open (" + n0(n.open) + ").");
        }
        double h = or[0], l = or[1];
        if (spot > h) return f.set(0.6 + 0.4 * Math.tanh((spot - h) / spot * 100 / 0.3), "Broke up", "Price broke above the first-15-minute high " + n0(h) + ". Buyers won the opening.");
        if (spot < l) return f.set(-0.6 - 0.4 * Math.tanh((l - spot) / spot * 100 / 0.3), "Broke down", "Price broke below the first-15-minute low " + n0(l) + ". Sellers won the opening.");
        double pos = (spot - l) / Math.max(1e-9, h - l);
        return f.set((pos - 0.5) * 0.6, "Inside range", "Price is still inside the first-15-minute range " + n0(l) + "–" + n0(h) + ". No winner yet.");
    }

    static Factor vwap(Snapshot s, Result r) {
        Factor f = new Factor("vwap", "VWAP", Factor.LIVE, true, 14);
        if (!s.live) return f.missing("Starts at 9:15.");
        double vw, px, basis;
        vw = vwapOf(s.futBars);
        if (Double.isNaN(vw) || s.fut == null || !s.fut.ok()) return f.missing("Futures candles (needed for VWAP) not loaded.");
        px = s.fut.last;
        basis = s.nifty != null && s.nifty.ok() ? s.fut.last - s.nifty.last : 0;
        r.vwapSpot = vw - basis;
        double dist = (px - vw) / vw * 100;
        double v = Math.tanh(dist / 0.2);
        int x = vwapCrosses(s);
        String d = ("Futures are " + pctS(dist) + " from VWAP " + n0(vw) + " (≈ " + n0(r.vwapSpot) + " in spot terms). ")
                + (dist > 0 ? "Buyers have the upper hand today." : "Sellers have the upper hand today.");
        if (x >= 4) { v *= 0.5; d += " Price crossed VWAP " + x + " times in the last hour — choppy."; }
        r.priceLine = (dist > 0 ? "Above" : "Below") + " VWAP (" + n0(r.vwapSpot) + ")";
        return f.set(v, dist > 0 ? "Above VWAP" : "Below VWAP", d);
    }

    /** Advance/decline, up-volume vs down-volume, % of stocks above their own VWAP and above their 20-day average. */
    static Factor breadth(Snapshot s, Result r) {
        Factor f = new Factor("breadth", "Market breadth", Factor.LIVE, true, 14);
        int adv = 0, dec = 0, aboveV = 0, vwN = 0, aboveD = 0, dN = 0; double eq = 0, upVal = 0, downVal = 0;
        for (Quote q : s.stocks) {
            if (!q.ok() || q.prevClose <= 0) continue;
            double p = q.pct();
            eq += p;
            double val = q.volume * q.last;
            if (p > 0.02) { adv++; upVal += val; } else if (p < -0.02) { dec++; downVal += val; }
            if (s.live && q.avgPrice > 0) { vwN++; if (q.last > q.avgPrice) aboveV++; }
            Double d20 = s.dma20.get(q.symbol);
            if (d20 != null && d20 > 0) { dN++; if (q.last > d20) aboveD++; }
        }
        r.advances = adv; r.declines = dec;
        if (adv + dec < 10) return f.missing("Nifty 50 stock prices not loaded.");
        double ratio = adv / (double) (adv + dec);
        double w = 0.35, sum = 0.35 * Math.tanh((ratio - 0.5) / 0.2);
        StringBuilder d = new StringBuilder(adv + " up, " + dec + " down. ");
        double upShare = Double.NaN;
        if (upVal + downVal > 0) {
            upShare = upVal / (upVal + downVal);
            sum += 0.30 * Math.tanh((upShare - 0.5) / 0.2); w += 0.30;
            d.append("Up-volume ").append(Math.round(upShare * 100)).append("% of traded value. ");
        }
        if (vwN >= 20) {
            double sh = aboveV / (double) vwN;
            sum += 0.20 * Math.tanh((sh - 0.5) / 0.2); w += 0.20;
            d.append(Math.round(sh * 100)).append("% of stocks above their own VWAP. ");
        }
        if (dN >= 30) {
            double sh = aboveD / (double) dN;
            sum += 0.15 * Math.tanh((sh - 0.5) / 0.25); w += 0.15;
            d.append(Math.round(sh * 100)).append("% above their 20-day average. ");
        }
        double v = sum / w;
        eq /= Math.max(1, s.stocks.size());
        d.append("Average stock ").append(pctS(eq)).append(".");
        double np = s.nifty != null ? s.nifty.pct() : 0;
        if (np > 0.25 && ratio < 0.45) { v -= 0.3; d.append(" Nifty is up but most stocks are not — a narrow rally."); }
        if (np < -0.25 && ratio > 0.55) { v += 0.3; d.append(" Nifty is down but most stocks are up — heavyweights are dragging it."); }
        if (!Double.isNaN(upShare) && ratio > 0.6 && upShare < 0.55) d.append(" Many stocks up, but on thin volume — weaker participation.");
        r.breadthLine = adv + " : " + dec + (Double.isNaN(upShare) ? "" : " · up-vol " + Math.round(upShare * 100) + "%");
        return f.set(v, adv + ":" + dec, d.toString());
    }

    static Factor bank(Snapshot s, Result r) {
        Factor f = new Factor("bank", "Bank Nifty confirmation", Factor.LIVE, true, 10);
        double b = s.bank != null && s.bank.ok() ? s.bank.pct() : Double.NaN;
        double fn = s.fin != null && s.fin.ok() ? s.fin.pct() : Double.NaN;
        double a = avg(b, fn);
        if (Double.isNaN(a)) return f.missing("Bank Nifty not available.");
        double v = Math.tanh(a / 0.6);
        double np = s.nifty != null ? s.nifty.pct() : 0;
        String d = "Bank Nifty " + pctS(b) + ", Fin Services " + pctS(fn) + ". Financials are about a third of Nifty.";
        String rd;
        if (np > 0.1 && a < -0.1) { rd = "Not confirming"; d += " Nifty is up without the banks — be careful."; v -= 0.2; }
        else if (np < -0.1 && a > 0.1) { rd = "Not confirming"; d += " Nifty is down while banks are up — selling is not broad."; v += 0.2; }
        else rd = Math.abs(a) < 0.1 ? "Flat" : (a > 0 ? "Confirming up" : "Confirming down");
        r.bankLine = rd + " (" + pctS(b) + ")";
        return f.set(v, rd, d);
    }

    /** Returns over 5 / 15 / 30 minutes scaled by ATR, range and volume expansion → trend, acceleration, exhaustion, compression, reversal. */
    static Factor momentum(Snapshot s, Result r) {
        Factor f = new Factor("momentum", "Momentum", Factor.LIVE, true, 12);
        List<Candle> b = s.niftyBars;
        if (!s.live || b.size() < 7) return f.missing("Needs at least 35 minutes of today's candles.");
        int n = b.size();
        double atr = 0; int an = 0;
        for (int i = Math.max(1, n - 14); i < n; i++) {
            Candle c = b.get(i), p = b.get(i - 1);
            atr += Math.max(c.h - c.l, Math.max(Math.abs(c.h - p.c), Math.abs(c.l - p.c))); an++;
        }
        atr = Math.max(1e-6, atr / Math.max(1, an));
        double r5 = (b.get(n - 1).c - b.get(n - 2).c) / atr, r15 = (b.get(n - 1).c - b.get(n - 4).c) / atr, r30 = (b.get(n - 1).c - b.get(n - 7).c) / atr;
        double prev30 = n >= 10 ? (b.get(n - 4).c - b.get(n - 10).c) / atr : 0;
        double avgRange = 0; int rn = 0;
        for (int i = Math.max(0, n - 13); i < n - 1; i++) { avgRange += b.get(i).h - b.get(i).l; rn++; }
        avgRange = rn > 0 ? avgRange / rn : 0;
        double dayRange = 0; for (Candle c : b) dayRange += c.h - c.l; dayRange /= n;
        double last6 = 0; for (int i = n - 6; i < n; i++) last6 += b.get(i).h - b.get(i).l; last6 /= 6;
        double rangeExp = avgRange > 0 ? (b.get(n - 1).h - b.get(n - 1).l) / avgRange : 1;
        double volExp = Double.NaN;
        List<Candle> fb = s.futBars;
        if (fb.size() >= 8) {
            double lastV = (fb.get(fb.size() - 1).v + fb.get(fb.size() - 2).v) / 2, pv = 0; int pn = 0;
            for (int i = Math.max(0, fb.size() - 14); i < fb.size() - 2; i++) { pv += fb.get(i).v; pn++; }
            if (pn > 0 && pv > 0) volExp = lastV / (pv / pn);
        }
        int hh = 0, ll = 0, k = Math.min(6, n - 1);
        for (int i = n - k; i < n; i++) {
            Candle c = b.get(i), p = b.get(i - 1);
            if (c.h > p.h && c.l >= p.l) hh++;
            if (c.l < p.l && c.h <= p.h) ll++;
        }
        double hl = (hh - ll) / (double) k;
        double v = 0.35 * Math.tanh(r15 / 1.5) + 0.35 * Math.tanh(r30 / 2.5) + 0.3 * hl;
        String st;
        if (last6 < 0.6 * dayRange) { st = "Compression"; v *= 0.5; }
        else if (Math.signum(r15) != Math.signum(prev30) && Math.abs(r15) > 1.2 && Math.abs(prev30) > 1.2) { st = "Reversal " + (r15 > 0 ? "up" : "down"); v = 0.7 * Math.tanh(r15 / 1.5); }
        else if (Math.abs(r30) > 2.5 && Math.signum(r5) != Math.signum(r30) && rangeExp > 1.3) { st = "Exhaustion"; v *= 0.4; }
        else if (Math.abs(r15) > 1.5 && Math.signum(r5) == Math.signum(r15) && Math.signum(r15) == Math.signum(r30) && rangeExp > 1.3) st = "Acceleration " + (r15 > 0 ? "up" : "down");
        else if (Math.signum(r15) == Math.signum(r30) && Math.abs(r30) > 1) st = "Trend " + (r30 > 0 ? "up" : "down");
        else st = "Drift";
        r.momentumState = st.startsWith("Compression") ? "Compression" : st;
        String d = String.format(Locale.US, "Moves in ATR units: 5 min %+.1f, 15 min %+.1f, 30 min %+.1f. %d higher highs, %d lower lows in the last %d bars. Last candle range %.1f× normal",
                r5, r15, r30, hh, ll, k, rangeExp) + (Double.isNaN(volExp) ? "." : String.format(Locale.US, ", futures volume %.1f× normal.", volExp));
        return f.set(v, st, d);
    }

    /** Each stock's weight × its move = its contribution to Nifty. Summed by sector. Detects narrow, heavyweight-only moves. */
    static Factor sectors(Snapshot s, Result r) {
        Factor f = new Factor("sectors", "Sector contribution", Factor.LIVE, true, 10);
        if (s.weights.isEmpty() || s.stocks.isEmpty()) return f.missing("Stock weights or prices not loaded.");
        if (s.weightsApprox) f.src("Kite + built-in weights (approx)", 0.8, "LIVE"); else f.src("Kite + NSE weights", 1.0, "LIVE");
        Map<String, Double> bySector = new LinkedHashMap<>();
        double total = 0, posW = 0, allW = 0;
        List<Object[]> stocks = new ArrayList<>();
        for (Quote q : s.stocks) {
            Double w = s.weights.get(q.symbol);
            if (w == null || !q.ok() || q.prevClose <= 0) continue;
            double c = w * q.pct();
            total += c; allW += w;
            if (q.pct() > 0) posW += w;
            String sec = s.sectorOf.containsKey(q.symbol) ? s.sectorOf.get(q.symbol) : "Other";
            bySector.merge(sec, c, Double::sum);
            stocks.add(new Object[]{q.symbol, w * 100, q.pct(), c});
        }
        if (allW < 0.5) return f.missing("Too few weighted stocks.");
        for (Map.Entry<String, Double> e : bySector.entrySet()) r.sectorContrib.add(new Object[]{e.getKey(), e.getValue()});
        r.sectorContrib.sort((a, b) -> Double.compare(Math.abs((Double) b[1]), Math.abs((Double) a[1])));
        stocks.sort((a, b) -> Double.compare(Math.abs((Double) b[3]), Math.abs((Double) a[3])));
        r.heavy.addAll(stocks.subList(0, Math.min(8, stocks.size())));
        double posShare = posW / allW;
        double v = 0.6 * Math.tanh(total / 0.6) + 0.4 * (2 * posShare - 1);
        // concentration: how much of the move comes from the 3 biggest contributors in the move's direction
        double top3 = 0; int k = 0;
        for (Object[] x : stocks) { double c = (Double) x[3]; if (Math.signum(c) == Math.signum(total)) { top3 += c; if (++k == 3) break; } }
        double conc = Math.abs(total) > 1e-6 ? top3 / total : 0;
        StringBuilder d = new StringBuilder(String.format(Locale.US, "Weighted move %+.2f%%. %.0f%% of Nifty's weight is up. ", total, posShare * 100));
        if (!r.sectorContrib.isEmpty()) {
            d.append("Biggest drivers: ");
            for (int i = 0; i < Math.min(3, r.sectorContrib.size()); i++) {
                Object[] x = r.sectorContrib.get(i);
                d.append(i > 0 ? ", " : "").append(x[0]).append(String.format(Locale.US, " %+.2f", (Double) x[1]));
            }
            d.append(" (% points). ");
        }
        // attribution check: do the weighted stock moves add up to Nifty's actual move?
        r.attrCalc = total;
        if (s.nifty != null && s.nifty.ok() && s.nifty.prevClose > 0) {
            r.attrActual = s.nifty.pct();
            if (Math.abs(r.attrActual) >= 0.1) {
                r.attrCoverage = total / r.attrActual * 100;
                d.append(String.format(Locale.US, "Attribution: stocks add up to %+.2f%% vs Nifty %+.2f%% (%.0f%%). ", total, r.attrActual, r.attrCoverage));
                if (r.attrCoverage < 70 || r.attrCoverage > 130) {
                    f.reliability *= 0.6;
                    d.append("That gap is large (old quotes, missing stocks or wrong weights), so this counts less. ");
                    r.notes.add(String.format(Locale.US, "Sector attribution only explains %.0f%% of Nifty's move — sector factor trusted less.", r.attrCoverage));
                }
            } else d.append("Attribution: Nifty barely moved, so no check. ");
        }
        String rd = posShare > 0.65 ? "Broad strength" : posShare < 0.35 ? "Broad weakness" : "Mixed";
        if (Math.abs(total) > 0.15 && conc > 0.7) {
            v *= 0.6; rd = "Narrow move";
            d.append(String.format(Locale.US, "Narrow: the top 3 stocks make %.0f%% of the move.", conc * 100));
        } else if (Math.abs(total) > 0.15) d.append("Healthy: the move is spread across many stocks.");
        if (s.weightsApprox) d.append(" (Weights are approximate.)");
        return f.set(v, rd, d.toString().trim());
    }

    public static final double DEFAULT_FLOW_SCALE = 0.06;
    /** The reference snapshot must be 6–25 minutes older than the current one (aim: about 15). */
    public static final int FLOW_REF_MIN = 6, FLOW_REF_TARGET = 15, FLOW_REF_MAX = 25;

    /** Pick the snapshot closest to 15 minutes before the newest one, within 6–25 minutes. Null if none qualifies. */
    public static FlowPoint pickFlowRef(List<FlowPoint> flow) {
        if (flow.size() < 2) return null;
        FlowPoint cur = flow.get(flow.size() - 1), best = null;
        for (int i = 0; i < flow.size() - 1; i++) {
            FlowPoint p = flow.get(i);
            int gap = cur.minute - p.minute;
            if (gap < FLOW_REF_MIN || gap > FLOW_REF_MAX) continue;
            if (best == null || Math.abs(gap - FLOW_REF_TARGET) < Math.abs(cur.minute - best.minute - FLOW_REF_TARGET)) best = p;
        }
        return best;
    }

    /** Live option flow: probable writing / covering / buying in the last ~15 minutes, sized by traded volume and compared with past days. */
    static Factor optionFlow(Snapshot s, Result r) {
        Factor f = new Factor("optflow", "Live option flow (last 15 min)", Factor.LIVE, true, 16);
        if (!s.live) return f.missing("Starts at 9:15.");
        if (s.flow.size() < 2 || s.chain.isEmpty()) return f.missing("Needs two option snapshots at least 6 minutes apart. Keep the app or the Live watch running.");
        FlowPoint cur = s.flow.get(s.flow.size() - 1);
        FlowPoint ref = pickFlowRef(s.flow);
        if (ref == null) {
            FlowPoint newestOld = null;
            for (int i = 0; i < s.flow.size() - 1; i++) newestOld = s.flow.get(i);
            int gap = newestOld == null ? 0 : cur.minute - newestOld.minute;
            if (gap > FLOW_REF_MAX) return f.missing("The last usable snapshot is " + gap + " min old (limit " + FLOW_REF_MAX
                    + " min), so a 15-minute flow can't be measured honestly. It comes back once the app has updated twice within 25 minutes.");
            return f.missing("Snapshots are too close together yet (need 6+ minutes between them).");
        }
        FlowPoint first = s.flow.get(0);
        if (!Double.isNaN(cur.atmIv) && !Double.isNaN(first.atmIv)) r.atmIvChange = cur.atmIv - first.atmIv;
        Chain.FlowRules rules = new Chain.FlowRules();
        double maxDist = 0;
        for (OptionRow x : s.chain) maxDist = Math.max(maxDist, Math.abs(x.strike - s.spot()));
        rules.outerBand = (int) Math.max(rules.nearBand + 1, Math.min(15, Math.floor(maxDist / s.strikeStep)));
        if (expiryDay(r)) { rules.minDoi = 3000; rules.ivTrust = 0.7; }
        double t = Greeks.yearsToExpiry(s.today, s.minute, s.expiry, s.optDaysToExpiry);
        Chain.Flow fl = Chain.intraday(ref, cur, s.chain, s.spot(), s.strikeStep, t, rules);
        r.optFlow = fl;
        if (fl.volume <= 0) return f.missing("No trading volume between snapshots.");
        double scale = Double.isNaN(s.flowScale) ? DEFAULT_FLOW_SCALE : s.flowScale;
        double near = Math.tanh(fl.intensity / (2.5 * scale));
        double outerI = fl.outerNet / Math.max(fl.outerVolume, 50000);
        double outer = Math.tanh(outerI / (2.5 * scale));
        double v = 0.8 * near + 0.2 * outer;
        if (expiryDay(r)) f.dayMod = 0.8;
        String rd;
        double pw = fl.peWriting, cw = fl.ceWriting, cc = fl.ceCovering, pc = fl.peCovering;
        double big = Math.max(Math.max(pw, cw), Math.max(cc, pc));
        if (big <= 0 || Math.abs(v) < 0.15) rd = "Quiet / balanced";
        else if (big == pw) rd = "Probable put writing";
        else if (big == cw) rd = "Probable call writing";
        else if (big == cc) rd = "Probable call covering";
        else rd = "Probable put covering";
        StringBuilder d = new StringBuilder("Last " + Math.round(fl.minutes) + " min, ATM ±" + rules.nearBand + ": put writing " + lots(pw) + ", call writing " + lots(cw)
                + ", call covering " + lots(cc) + ", put covering " + lots(pc) + String.format(Locale.US, " (average confidence %.0f%%). ", fl.avgConfidence * 100));
        int shown = 0;
        for (Chain.Activity a : fl.acts) {
            if (a.outer || shown >= 2) continue;
            shown++;
            d.append(n0(a.strike)).append(a.call ? " CE: " : " PE: ").append(a.dOi >= 0 ? "+" : "−").append(lots(Math.abs(a.dOi))).append(" OI, ")
                    .append(Double.isNaN(a.relIv) ? "premium beyond spot move " + sgn2(a.residual) : "IV vs market " + sgn2(a.relIv)).append(" → ").append(a.label().toLowerCase(Locale.US)).append(". ");
        }
        d.append(String.format(Locale.US, "Flow intensity %+.3f per contract traded vs a typical %.3f%s. ", fl.intensity, scale, Double.isNaN(s.flowScale) ? " (starting guess until 30 readings exist)" : " (past days)"));
        if (!Double.isNaN(fl.surfaceIv)) d.append("Whole IV curve moved ").append(sgn2(fl.surfaceIv)).append(", which is taken out first. ");
        if (fl.newCallWall != null) d.append("Outer strikes: call wall building at ").append(n0(fl.newCallWall.strike)).append(" (+").append(lots(fl.newCallWall.dOi)).append("). ");
        if (fl.newPutWall != null) d.append("Outer strikes: put wall building at ").append(n0(fl.newPutWall.strike)).append(" (+").append(lots(fl.newPutWall.dOi)).append("). ");
        if (expiryDay(r)) d.append("Expiry day: small OI moves ignored and IV trusted less.");
        r.flowLine = rd;
        return f.set(v, rd, d.toString().trim());
    }

    /**
     * Live futures flow with growing windows: 15 min from 9:30, 30 min from 9:45, 60-min acceleration from 10:15.
     * A shorter window counts less (maturity 0.6 / 0.85 / 1.0).
     */
    static Factor futuresFlow(Snapshot s, Result r) {
        Factor f = new Factor("futflow", "Live futures flow (OI speed)", Factor.LIVE, true, 14);
        if (!s.live) return f.missing("Starts at 9:15.");
        List<Candle> a = s.futBars;
        int n = a.size();
        if (n < 4) return f.missing("Needs at least 15 minutes of futures candles (from about 9:35).");
        Map<Integer, Double> nextOi = new HashMap<>();
        for (Candle c : s.futNextBars) nextOi.put(c.minute, c.oi);
        Map<Integer, Double> idx = new HashMap<>();
        for (Candle c : s.niftyBars) idx.put(c.minute, c.c);
        double[] oi = new double[n];
        boolean haveOi = false;
        for (int i = 0; i < n; i++) {
            Double nx = nextOi.get(a.get(i).minute);
            oi[i] = a.get(i).oi + (nx == null ? 0 : nx);
            if (a.get(i).oi > 0) haveOi = true;
        }
        if (!haveOi) return f.missing("Kite did not send OI with the futures candles.");
        int back = n >= 7 ? 6 : 3;
        String win = back == 6 ? "30 min" : "15 min";
        f.maturity = n >= 13 ? 1.0 : n >= 7 ? 0.85 : 0.6;
        int i0 = n - 1, iw = n - 1 - back;
        double pw = (a.get(i0).c - a.get(iw).c) / a.get(iw).c * 100;
        double oiw = oi[iw] > 0 ? (oi[i0] - oi[iw]) / oi[iw] * 100 : 0;
        double scale = back == 6 ? 1 : 0.6;   // thresholds scale with the window
        double[] b = buildup(pw, oiw, 0.05 * scale, 0.3 * scale, 2.5 * scale);
        String kind = BUILD[(int) b[1]];
        double basisV = 0, dBasis = Double.NaN;
        Double ix0 = idx.get(a.get(i0).minute), ixw = idx.get(a.get(iw).minute);
        if (ix0 != null && ixw != null) {
            dBasis = (a.get(i0).c - ix0) - (a.get(iw).c - ixw);
            basisV = Math.tanh(dBasis / (ix0 * 0.0003));
        }
        double v = 0.8 * b[0] + 0.2 * basisV;
        StringBuilder d = new StringBuilder(String.format(Locale.US, "Last %s: futures %+.2f%%, OI (near + next) %+.2f%% → %s. ", win, pw, oiw, kind.toLowerCase(Locale.US)));
        if (n >= 13) {
            int ip = n - 13;
            double oiPrev = oi[ip] > 0 ? (oi[iw] - oi[ip]) / oi[ip] * 100 : Double.NaN;
            if (!Double.isNaN(oiPrev)) {
                double acc = oiw - oiPrev;
                d.append(String.format(Locale.US, "The 30 min before: OI %+.2f%%, so OI is %s. ", oiPrev, Math.abs(acc) < 0.2 ? "steady" : acc > 0 ? "speeding up" : "slowing"));
                if (b[1] == 1 || b[1] == 2) v *= acc > 0.2 ? 1.1 : acc < -0.4 ? 0.8 : 1.0;
            }
        } else d.append("Window is still short (").append(win).append("), so this counts ").append(Math.round(f.maturity * 100)).append("%. ");
        if (!Double.isNaN(dBasis)) d.append("Basis ").append(dBasis >= 0 ? "widened " : "narrowed ").append(String.format(Locale.US, "%.1f pts", Math.abs(dBasis)))
                .append(pw > 0 && dBasis < 0 ? " while price rose — buyers not paying up (weaker)." : pw < 0 && dBasis > 0 ? " while price fell — sellers not pressing (weaker)." : ".");
        if (b[1] == 3 && oiw < -1) d.append(" Mostly short covering — rallies built on covering can fade.");
        if (r.dayType.equals("MONTHLY EXPIRY") || r.dayType.equals("EVENT + EXPIRY")) { f.dayMod = 0.8; d.append(" Monthly expiry: rollover distorts OI, counts a bit less."); }
        r.futFlowLine = kind + " (" + win + ")";
        return f.set(v, kind + " · " + win, d.toString().trim());
    }

    // ================================================================== VIX as a volatility modifier (no vote)

    /** Where today's VIX sits among the last year's closes (0–100), NaN without history. */
    static double vixPercentile(Snapshot s) {
        if (s.vix == null || !s.vix.ok() || s.vixDaily.size() < 60) return Double.NaN;
        int below = 0, n = 0;
        for (int i = Math.max(0, s.vixDaily.size() - 250); i < s.vixDaily.size(); i++) {
            Candle c = s.vixDaily.get(i);
            if (c.date.equals(s.today)) continue;
            n++;
            if (c.c < s.vix.last) below++;
        }
        return n > 0 ? 100.0 * below / n : Double.NaN;
    }

    /** Correlation of daily % changes of Nifty and VIX over the last 20 sessions (normally strongly negative). */
    static double vixCorrelation(Snapshot s) {
        Map<String, Double> nc = new HashMap<>();
        for (Candle c : s.niftyDaily) nc.put(c.date, c.c);
        List<double[]> pairs = new ArrayList<>();
        for (int i = 1; i < s.vixDaily.size(); i++) {
            Candle a = s.vixDaily.get(i - 1), b = s.vixDaily.get(i);
            Double na = nc.get(a.date), nb = nc.get(b.date);
            if (na == null || nb == null || a.c <= 0 || na <= 0) continue;
            pairs.add(new double[]{(nb - na) / na, (b.c - a.c) / a.c});
        }
        if (pairs.size() < 15) return Double.NaN;
        List<double[]> p = pairs.subList(Math.max(0, pairs.size() - 20), pairs.size());
        double mx = 0, my = 0;
        for (double[] x : p) { mx += x[0]; my += x[1]; }
        mx /= p.size(); my /= p.size();
        double sxy = 0, sxx = 0, syy = 0;
        for (double[] x : p) { sxy += (x[0] - mx) * (x[1] - my); sxx += (x[0] - mx) * (x[0] - mx); syy += (x[1] - my) * (x[1] - my); }
        return sxx > 0 && syy > 0 ? sxy / Math.sqrt(sxx * syy) : Double.NaN;
    }

    /** How much VIX says "range" (0..1): smooth, from the percentile (or the level when there is no history). */
    static double vixRangeVote(Snapshot s, Result r) {
        if (s.vix == null || !s.vix.ok()) return 0;
        if (!Double.isNaN(r.vixPct)) return Math.max(0, Math.min(1, (35 - r.vixPct) / 35));
        return Math.max(0, Math.min(1, (13.5 - s.vix.last) / 2.5));
    }

    static Factor vix(Snapshot s, Result r) {
        Factor f = new Factor("vix", "India VIX (volatility regime — not a vote)", Factor.MODIFIER, false, 0);
        Quote q = s.vix;
        if (q == null || !q.ok()) return f.missing("India VIX not available.");
        double ch = q.pct(), np = s.nifty != null ? s.nifty.pct() : 0;
        if (s.nifty != null && s.nifty.ok()) r.expectedMove = s.nifty.last * q.last / 100 / Math.sqrt(252);
        r.vixPct = vixPercentile(s);
        r.vixCorr = vixCorrelation(s);
        double pct = r.vixPct;
        if (!Double.isNaN(pct)) r.volClass = pct < 25 ? "Low volatility" : pct < 75 ? "Normal volatility" : pct < 95 ? "Elevated volatility" : "Extreme volatility";
        else r.volClass = q.last < 12.5 ? "Low volatility" : q.last < 16 ? "Normal volatility" : q.last < 22 ? "Elevated volatility" : "Extreme volatility";
        boolean up = np > 0.15, down = np < -0.15, vUp = ch > 2, vDown = ch < -2;
        String mood;
        double mod = 1;
        if (up && vDown) mood = "orderly bullish";
        else if (up && vUp) { mood = "bullish but unstable"; mod = 0.9; }
        else if (down && vUp) { mood = "bearish risk-off"; mod = 0.95; }
        else if (down && vDown) mood = "orderly weakness";
        else mood = "steady";
        if (r.volClass.startsWith("Elevated")) mod *= 0.9;
        if (r.volClass.startsWith("Extreme")) mod *= 0.8;
        if (ch > 5) mod *= 0.95;
        boolean oddCorr = !Double.isNaN(r.vixCorr) && r.vixCorr > 0;
        if (oddCorr) mod *= 0.9;
        r.volRegime = r.volClass + " · " + mood;
        r.volMod = mod;
        String d = "VIX " + f2(q.last) + ", " + pctS(ch) + " today" + (Double.isNaN(pct) ? " (no 1-year history yet, level used)" : String.format(Locale.US, ", higher than %.0f%% of the last year", pct))
                + ". VIX measures expected swings, not direction, so it does not vote. "
                + (Double.isNaN(r.vixCorr) ? "" : String.format(Locale.US, "20-day Nifty/VIX correlation %.2f%s. ", r.vixCorr, oddCorr ? " — unusual (they normally move opposite), so confidence is trimmed" : ""))
                + (mod < 1 ? "It lowers confidence by " + Math.round((1 - mod) * 100) + "%. " : "")
                + (Double.isNaN(r.expectedMove) ? "" : "Market is pricing about ±" + n0(r.expectedMove) + " pts for one day.");
        return f.set(-Math.tanh(ch / 6), r.volRegime, d);
    }

    // ================================================================== EVENTS: news + calendar

    static double sevW(String sev) { return "HIGH".equals(sev) ? 1.0 : "MEDIUM".equals(sev) ? 0.6 : 0.3; }

    static double verifyW(NewsItem n) {
        double w = "VERIFIED".equals(n.verification) ? 1.2 : "CONFIRMED".equals(n.verification) ? 1.0 : "CORROBORATED".equals(n.verification) ? 0.7 : 0.5;
        return n.speculative ? w * 0.5 : w;
    }

    /** IST epoch for a day + minute. */
    static long istTime(String day, int minute) {
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US);
            f.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Kolkata"));
            return f.parse(day).getTime() + minute * 60_000L;
        } catch (Exception e) { return 0; }
    }

    static double priceAt(Snapshot s, int minute) {
        double p = s.nifty != null ? s.nifty.open : Double.NaN;
        for (Candle c : s.niftyBars) if (c.minute <= minute) p = c.c;
        return p;
    }

    /**
     * Has the market already reacted to this news? Compares Nifty's move since the headline with the move its rating implies.
     * Fresh shock (full weight) · Absorbing · Re-accelerating · Already priced · Faded (market ignored it).
     */
    static void reaction(Snapshot s, NewsItem n) {
        double dir = Math.signum(n.niftyImpact);
        if (n.time <= 0) { n.reaction = "Time unknown"; n.reactionWeight = 0.6; return; }
        if (dir == 0 || s.nifty == null || !s.nifty.ok()) { n.reaction = "No clear direction"; n.reactionWeight = 1; return; }
        long openT = istTime(s.today, 9 * 60 + 15);
        if (!s.live) {
            long closeT = istTime(s.sessionDate.isEmpty() ? s.today : s.sessionDate, 15 * 60 + 30);
            if (n.time >= closeT) { n.reaction = "Fresh (market closed since)"; n.reactionWeight = 1.0; }
            else { n.reaction = "Priced in last session"; n.reactionWeight = 0.4; }
            return;
        }
        double ref; double ageMin;
        if (n.time < openT) { ref = s.nifty.prevClose; ageMin = s.minute - (9 * 60 + 15); }
        else {
            int m = (int) ((n.time - istTime(s.today, 0)) / 60000);
            ref = priceAt(s, m);
            ageMin = s.minute - m;
        }
        if (!(ref > 0)) { n.reaction = "No price reference"; n.reactionWeight = 0.8; return; }
        double spot = s.nifty.last;
        double move = (spot - ref) / ref * 100 * dir;
        double p15 = priceAt(s, s.minute - 15);
        double recent = p15 > 0 ? (spot - p15) / p15 * 100 * dir : 0;
        double e = "HIGH".equals(n.severity) ? 0.8 : "MEDIUM".equals(n.severity) ? 0.5 : 0.25;
        if (ageMin < 30 && move < 0.5 * e) { n.reaction = "Fresh shock"; n.reactionWeight = 1.0; }
        else if (move >= 0.7 * e) {
            if (recent >= 0.15) { n.reaction = "Re-accelerating"; n.reactionWeight = 0.7; }
            else { n.reaction = "Already priced"; n.reactionWeight = 0.25; }
        }
        else if (move <= -0.3 * e) { n.reaction = "Faded (market ignored it)"; n.reactionWeight = 0.3; }
        else { n.reaction = "Absorbing"; n.reactionWeight = 0.6; }
        n.reaction += String.format(Locale.US, " (Nifty %+.2f%% its way since)", move);
    }

    static Factor events(Snapshot s, Result r) {
        Factor f = new Factor("events", "News + events", Factor.EVENT, false, 0);
        // event risk from the calendar
        String risk = "LOW", why = "";
        for (EventItem e : s.events) {
            int d = daysBetween(s.today, e.date);
            boolean nextSession = d == 0 || d == 1 || (d <= 3 && isWeekendBetween(s.today, e.date));
            if (e.importance >= 3 && nextSession) { risk = "HIGH"; why = e.name + (d == 0 ? " today" : " next session"); break; }
            if ((e.importance >= 3 && d <= 4) || (e.importance == 2 && nextSession)) {
                if (!risk.equals("MEDIUM")) { risk = "MEDIUM"; why = e.name + (d == 0 ? " today" : " on " + e.date); }
            }
        }
        // news tone: one vote per story (duplicates grouped), weighted by verification and by how much the market has already reacted
        double sum = 0; int stories = 0, dup = 0, unconfirmed = 0; boolean ai = false; NewsItem loud = null;
        for (NewsItem n : s.news) {
            if (!n.read) continue;
            if (!n.lead) { dup++; continue; }
            double ageH = n.time > 0 ? Math.max(0, (s.time - n.time) / 3600_000.0) : 12;
            if (ageH > 24) continue;
            reaction(s, n);
            double half = "weeks".equals(n.horizon) ? 48 : "days".equals(n.horizon) ? 18 : 6;
            double decay = Math.pow(0.5, ageH / half);
            double rel = "Gemini".equals(n.by) ? 0.6 : 0.35;
            if ("Gemini".equals(n.by)) ai = true;
            sum += n.niftyImpact * sevW(n.severity) * decay * rel * verifyW(n) * n.reactionWeight;
            stories++;
            if ("HIGH".equals(n.severity) && ageH <= 3) {
                boolean trusted = ("CONFIRMED".equals(n.verification) || "VERIFIED".equals(n.verification)) && !n.speculative;
                if (!trusted) unconfirmed++;
                else if (loud == null || Math.abs(n.niftyImpact) > Math.abs(loud.niftyImpact)) loud = n;
            }
        }
        if (loud != null) {
            if (risk.equals("LOW") || risk.equals("MEDIUM")) { risk = risk.equals("LOW") ? "MEDIUM" : "HIGH"; why = "big news: " + shorten(loud.title, 60); }
        }
        if (r.dayType.equals("EVENT + EXPIRY") && risk.equals("MEDIUM")) { risk = "HIGH"; why = "event on expiry day"; }
        r.eventRisk = risk;
        r.eventWhy = why;
        r.eventMod = risk.equals("HIGH") ? 0.8 : risk.equals("MEDIUM") ? 0.92 : 1.0;
        f.src(ai ? "Gemini (" + s.newsReader.replace("Gemini (", "").replace(")", "") + ")" : "word list", ai ? 0.6 : 0.35, ai ? "AI" : "DELAYED");
        if (stories == 0 && s.events.isEmpty()) return f.missing("No news or events loaded.");
        r.eventScore = 100 * Math.tanh(sum / 1.2);
        String d = stories + " stories from the last 24 h (" + dup + " duplicate headlines grouped), read by "
                + (ai ? "Gemini" : "a word list (add a Gemini key in Settings for better reading)")
                + ". Official = 1.2, two independent reports = 1.0, the same agency story on several sites = 0.7, one source = 0.5; rumours half again; news the market has already reacted to counts less. "
                + "News tone " + sg0(r.eventScore) + ". Event risk " + risk + (why.isEmpty() ? "." : ": " + why + ".")
                + (unconfirmed > 0 ? " " + unconfirmed + " big headline(s) not independently confirmed yet (one source, or only copies of one agency story) — ignored for event risk until an independent or official source carries them." : "")
                + " News moves the score by at most ±15.";
        return f.set(r.eventScore / 100, "Risk " + risk + " · tone " + sg0(r.eventScore), d);
    }

    static boolean isWeekendBetween(String a, String b) {
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US);
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.setTime(f.parse(a));
            int d = daysBetween(a, b);
            for (int i = 1; i < d; i++) {
                c.add(java.util.Calendar.DAY_OF_MONTH, 1);
                int w = c.get(java.util.Calendar.DAY_OF_WEEK);
                if (w != java.util.Calendar.SATURDAY && w != java.util.Calendar.SUNDAY) return false;
            }
            return true;
        } catch (Exception e) { return false; }
    }

    static int daysBetween(String a, String b) {
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US);
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            return (int) Math.round((f.parse(b).getTime() - f.parse(a).getTime()) / 86400_000.0);
        } catch (Exception e) { return 99; }
    }

    static String shorten(String s, int n) { return s.length() <= n ? s : s.substring(0, n - 1) + "…"; }

    // ================================================================== helpers

    public static double vwapOf(List<Candle> bars) {
        double pv = 0, v = 0;
        for (Candle c : bars) { double tp = (c.h + c.l + c.c) / 3; pv += tp * c.v; v += c.v; }
        return v > 0 ? pv / v : Double.NaN;
    }

    static int vwapCrosses(Snapshot s) {
        List<Candle> b = s.futBars;
        if (b.size() < 4) return 0;
        double pv = 0, v = 0; int prev = 0, x = 0, from = Math.max(0, b.size() - 12);
        for (int i = 0; i < b.size(); i++) {
            Candle c = b.get(i);
            pv += (c.h + c.l + c.c) / 3 * c.v; v += c.v;
            if (v <= 0) continue;
            int side = c.c >= pv / v ? 1 : -1;
            if (i >= from && prev != 0 && side != prev) x++;
            prev = side;
        }
        return x;
    }

    static int lastBarsDirection(List<Candle> b, int n) {
        if (b.size() < n + 1) return 0;
        double ch = b.get(b.size() - 1).c - b.get(b.size() - 1 - n).c;
        return ch > 0 ? 1 : ch < 0 ? -1 : 0;
    }

    static double val(Double d) { return d == null ? Double.NaN : d; }
    static double avg(Double... xs) {
        double s = 0; int n = 0;
        for (Double x : xs) if (x != null && !Double.isNaN(x)) { s += x; n++; }
        return n > 0 ? s / n : Double.NaN;
    }
    static double clamp(double v) { return Math.max(-1, Math.min(1, v)); }
    static String tone(double v, String pos, String neg, String mid) { return v > 0.2 ? pos : v < -0.2 ? neg : mid; }

    public static String pctS(double p) { return Double.isNaN(p) ? "n/a" : String.format(Locale.US, "%+.2f%%", p); }
    public static String sgn(double p) { return String.format(Locale.US, "%+.0f", p); }
    public static String sg0(double p) { return Double.isNaN(p) ? "—" : String.format(Locale.US, "%+.0f", p); }
    static String sgn2(double p) { return String.format(Locale.US, "%+.2f", p); }
    public static String n0(double p) { return Double.isNaN(p) ? "—" : String.format(Locale.US, "%,.0f", p); }
    public static String f0(double p) { return String.format(Locale.US, "%.0f", p); }
    public static String f1(double p) { return Double.isNaN(p) ? "—" : String.format(Locale.US, "%.1f", p); }
    public static String f2(double p) { return Double.isNaN(p) ? "—" : String.format(Locale.US, "%.2f", p); }
    static String crS(double cr) { return String.format(Locale.US, "₹%,.0f cr", cr); }
    /** Kite reports option OI in units. Show lakh units for readability. */
    public static String lots(double oi) { return Math.abs(oi) >= 1e5 ? String.format(Locale.US, "%.1fL", oi / 1e5) : String.format(Locale.US, "%,.0f", oi); }
}

package com.krish.niftydirection.intel;

import com.krish.niftydirection.model.NewsItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * News Intelligence + Event Impact Engine.
 *
 * Article → event: headlines about the same story are already grouped by the news reader (News.cluster); only the
 * lead item of each group is an event here, so 50 copies of one story are ONE event confirmed by many sources.
 * Each event gets
 *   Impact(h) = direction × magnitude × probability × credibility × novelty × NIFTY exposure × time decay(h)
 *   direction, magnitude  from the reader's Nifty impact (−1..1) and severity
 *   probability           0.5 for rumours / "may" stories, else 1
 *   credibility           source tier (1 primary 1.0 · 2 major financial press 0.9 · 3 other sites 0.7 · 4 social 0.4) × confirmation
 *   novelty               fresh news counts fully, older news half; "already priced" reactions lower it further
 *   exposure              constituents named → their Nifty weight (6% weight = full); a sector → its weight; macro = full
 *   time decay            half-life grows with the horizon (1 hour for 15 min … 3 days for 1 week)
 * The news score per horizon is tanh(Σ impacts): it feeds the forecast as a bounded live overlay.
 */
public final class EventImpact {
    private EventImpact() {}

    public static final class Event {
        public String title = "", source = "", sector = "", verification = "";
        public int tier = 3, sources = 1;
        public double direction, magnitude, probability = 1, credibility, novelty = 1, exposure = 1, ageMin;
        public final List<String> constituents = new ArrayList<>();
        /** How long the effect lasts: FUNDAMENTAL (results, policy …) ×4 half-life, ORDERS ×2, OPINION ×1, FLOW (block deals, rumours) ×0.5. */
        public String persistence = "NORMAL";
        public double halfLifeMult = 1;
        /** Company → sector → Nifty path, e.g. "HDFCBANK 13.0% of Nifty → Financials peers 22% (spillover) → exposure 1.00". */
        public String chain = "";
        public final double[] impact = new double[Horizon.ALL.length];
        public boolean primary, surprise;
        public double maxAbs() { double m = 0; for (double v : impact) m = Math.max(m, Math.abs(v)); return m; }
    }

    static final String[] TIER1 = {"rbi", "reserve bank", "sebi", "nse", "bse", "pib", "ministry", "government of india", "federal reserve", "us treasury",
            "bureau of labor", "mospi", "fed", "ecb", "european central bank", "bank of england", "bank of japan", "bea", "nse filings"};
    static final String[] TIER2 = {"reuters", "bloomberg", "financial times", "ft.com", "cnbc", "wall street journal", "wsj", "economic times", "et markets",
            "business standard", "mint", "livemint", "moneycontrol", "financial express", "businessline", "business line", "ndtv profit", "the hindu"};
    static final String[] TIER4 = {"twitter", "x.com", "telegram", "youtube", "reddit", "forum", "blog", "medium.com", "substack", "whatsapp", "facebook", "instagram"};

    public static int tier(String source, boolean official) {
        if (official) return 1;
        String s = source == null ? "" : source.toLowerCase(Locale.US);
        for (String t : TIER1) if (has(s, t)) return 1;
        for (String t : TIER4) if (has(s, t)) return 4;
        for (String t : TIER2) if (has(s, t)) return 2;
        return 3;
    }

    /** Short names (nse, bse, rbi, mint, wsj …) must match as whole words, so "Sensex Daily" is not NSE. */
    static boolean has(String s, String t) {
        if (t.length() > 4) return s.contains(t);
        return java.util.regex.Pattern.compile("(^|[^a-z])" + java.util.regex.Pattern.quote(t) + "([^a-z]|$)").matcher(s).find();
    }

    static double tierCred(int t) { return t == 1 ? 1.0 : t == 2 ? 0.9 : t == 4 ? 0.4 : 0.7; }

    static double verifyMult(String v) {
        if ("VERIFIED".equals(v)) return 1.1;
        if ("CONFIRMED".equals(v)) return 1.05;
        if ("CORROBORATED".equals(v)) return 0.95;
        return 0.85;   // PROVISIONAL: one source so far
    }

    static double sevW(String s) { return "HIGH".equals(s) ? 1.0 : "MEDIUM".equals(s) ? 0.6 : 0.3; }

    static final String[] MACRO = {"rbi", "fed", "federal reserve", "inflation", "cpi", "gdp", "budget", "rupee", "crude", "oil", "tariff", "war",
            "election", "fii", "repo", "yield", "rate", "recession", "sanction", "opec"};

    public static List<Event> build(List<NewsItem> news, List<EventCalendarRisk.Surprise> surprises, Map<String, Double> weights,
                                    Map<String, String> sectorOf, long now, String today, int minute) {
        List<Event> out = new ArrayList<>();
        if (news != null) for (NewsItem n : news) {
            if (!n.read || !n.lead || n.time <= 0) continue;
            Event e = new Event();
            e.title = n.title; e.source = n.source; e.sector = n.sector; e.verification = n.verification;
            e.sources = Math.max(1, n.independent);
            e.tier = tier(n.source, n.official);
            e.primary = e.tier == 1;
            e.direction = Math.signum(n.niftyImpact);
            e.magnitude = Math.min(1, Math.abs(n.niftyImpact)) * sevW(n.severity);
            e.probability = n.speculative ? 0.5 : 1;
            e.credibility = Math.min(1, tierCred(e.tier) * verifyMult(n.verification));
            e.ageMin = Math.max(0, (now - n.time) / 60000.0);
            e.novelty = (0.5 + 0.5 * Math.exp(-e.ageMin / 180.0)) * Math.max(0.2, Math.min(1, n.reactionWeight));
            persistence(e, n.title + " " + n.summary, n.speculative);
            e.exposure = exposure(n.title + " " + n.summary, n.sector, weights, sectorOf, e.constituents, e);
            fill(e);
            if (e.maxAbs() > 0) out.add(e);
        }
        if (surprises != null) for (EventCalendarRisk.Surprise s : surprises) {
            double imp = s.impact();
            if (Double.isNaN(imp)) continue;
            int d = FeatureEngine.days(s.date, today);
            int at = s.minute < 0 ? 0 : s.minute;
            double age = d * 1440.0 + (minute - at);
            if (age < 0 || age > 3 * 1440) continue;
            Event e = new Event();
            e.surprise = true; e.primary = true; e.tier = 1;
            e.title = String.format(Locale.US, "%s: actual %s vs expected %s (surprise %+.1f sd)", s.name, trim(s.actual), trim(s.expected), s.z);
            e.source = "My events (official release)";
            e.direction = Math.signum(imp); e.magnitude = Math.abs(imp); e.credibility = 1; e.ageMin = age;
            e.novelty = 0.5 + 0.5 * Math.exp(-age / 180.0);
            e.persistence = "FUNDAMENTAL"; e.halfLifeMult = 4;
            fill(e);
            out.add(e);
        }
        out.sort((a, b) -> Double.compare(b.maxAbs(), a.maxAbs()));
        return out;
    }

    static void fill(Event e) {
        for (int i = 0; i < Horizon.ALL.length; i++) {
            double decay = Math.exp(-Math.log(2) * e.ageMin / (Horizon.ALL[i].newsHalfLifeMin * e.halfLifeMult));
            e.impact[i] = e.direction * e.magnitude * e.probability * e.credibility * e.novelty * e.exposure * decay;
        }
    }

    /**
     * Company → sector → Nifty. A named constituent reaches the index through its own weight (6% weight = full exposure)
     * and, for news that lasts (results, policy, orders), partly through its sector peers (30% spillover of their weight).
     */
    static double exposure(String text, String sector, Map<String, Double> weights, Map<String, String> sectorOf, List<String> named, Event ev) {
        Map<String, Double> m = Constituents.mentioned(text, weights);
        if (!m.isEmpty()) {
            double w = 0, peers = 0;
            java.util.Set<String> secs = new java.util.LinkedHashSet<>();
            for (Map.Entry<String, Double> e : m.entrySet()) {
                w += e.getValue(); named.add(e.getKey());
                String sec = sectorOf == null ? null : sectorOf.get(e.getKey());
                if (sec != null && !sec.isEmpty()) secs.add(sec);
            }
            for (String sec : secs) for (Map.Entry<String, Double> e : weights.entrySet())
                if (sec.equals(sectorOf.get(e.getKey())) && !m.containsKey(e.getKey())) peers += e.getValue();
            double spill = ev.halfLifeMult >= 2 ? 0.3 : 0.1;
            double x = Math.min(1, Math.max(0.1, w / 0.06 + spill * peers / 0.06));
            ev.chain = String.format(Locale.US, "%s %.1f%% of Nifty → %s peers %.0f%% (×%.1f spillover) → index exposure %.2f",
                    String.join("+", m.keySet()), w * 100, secs.isEmpty() ? "sector" : String.join("/", secs), peers * 100, spill, x);
            return x;
        }
        double sw = Constituents.sectorWeight(sector, weights, sectorOf);
        if (!Double.isNaN(sw)) {
            double x = Math.min(1, Math.max(0.15, sw / 0.25));
            ev.chain = String.format(Locale.US, "%s sector %.0f%% of Nifty → index exposure %.2f", sector, sw * 100, x);
            return x;
        }
        String t = text == null ? "" : text.toLowerCase(Locale.US);
        for (String k : MACRO) if (t.contains(k)) return 1;
        return 0.6;
    }

    static final String[] FUNDAMENTAL = {"result", "earnings", "profit", "revenue", "guidance", "quarter", "q1 ", "q2 ", "q3 ", "q4 ", "merger",
            "acquisition", "acquire", "demerger", "buyback", "dividend", "policy", "repo", "rate cut", "rate hike", "budget", "gdp", "inflation", "cpi",
            "downgrade of india", "sovereign rating", "tariff", "sanction", "war"};
    static final String[] ORDERS = {"order", "contract", "deal", "approval", "licence", "license", "launch", "capex", "expansion", "stake"};
    static final String[] OPINION = {"upgrade", "downgrade", "target price", "rating", "brokerage", "outlook", "view", "says", "expects", "sees "};
    static final String[] FLOW = {"block deal", "bulk deal", "ofs", "offer for sale", "intraday", "short covering", "profit booking"};

    /** Persistence class from the story type: lasting news decays slowly, flow and rumours fast. */
    static void persistence(Event e, String text, boolean speculative) {
        String t = " " + (text == null ? "" : text.toLowerCase(Locale.US)) + " ";
        if (speculative || anyOf(t, FLOW)) { e.persistence = "FLOW"; e.halfLifeMult = 0.5; }
        else if (anyOf(t, FUNDAMENTAL)) { e.persistence = "FUNDAMENTAL"; e.halfLifeMult = 4; }
        else if (anyOf(t, ORDERS)) { e.persistence = "ORDERS"; e.halfLifeMult = 2; }
        else if (anyOf(t, OPINION)) { e.persistence = "OPINION"; e.halfLifeMult = 1; }
    }

    static boolean anyOf(String t, String[] keys) { for (String k : keys) if (t.contains(k)) return true; return false; }

    /** News score per horizon, −1..1: tanh of the summed impacts (many weak stories cannot add up without limit). */
    public static double[] score(List<Event> events) {
        double[] s = new double[Horizon.ALL.length];
        for (Event e : events) for (int i = 0; i < s.length; i++) s[i] += e.impact[i];
        for (int i = 0; i < s.length; i++) s[i] = Math.tanh(s[i]);
        return s;
    }

    /** NEWS DOMINATED: a confirmed or primary high-impact event in the last 3 hours. */
    public static boolean dominated(List<Event> events) {
        for (Event e : events) if (e.ageMin <= 180 && e.magnitude >= 0.6 && e.credibility >= 0.9 && e.exposure >= 0.6) return true;
        return false;
    }

    static String trim(double v) { return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v); }
}

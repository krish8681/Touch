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
        public final double[] impact = new double[Horizon.ALL.length];
        public boolean primary, surprise;
        public double maxAbs() { double m = 0; for (double v : impact) m = Math.max(m, Math.abs(v)); return m; }
    }

    static final String[] TIER1 = {"rbi", "reserve bank", "sebi", "nse", "bse", "pib", "ministry", "government of india", "federal reserve", "us treasury",
            "bureau of labor", "mospi"};
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
            e.exposure = exposure(n.title + " " + n.summary, n.sector, weights, sectorOf, e.constituents);
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
            fill(e);
            out.add(e);
        }
        out.sort((a, b) -> Double.compare(b.maxAbs(), a.maxAbs()));
        return out;
    }

    static void fill(Event e) {
        for (int i = 0; i < Horizon.ALL.length; i++) {
            double decay = Math.exp(-Math.log(2) * e.ageMin / Horizon.ALL[i].newsHalfLifeMin);
            e.impact[i] = e.direction * e.magnitude * e.probability * e.credibility * e.novelty * e.exposure * decay;
        }
    }

    static double exposure(String text, String sector, Map<String, Double> weights, Map<String, String> sectorOf, List<String> named) {
        Map<String, Double> m = Constituents.mentioned(text, weights);
        if (!m.isEmpty()) {
            double w = 0;
            for (Map.Entry<String, Double> e : m.entrySet()) { w += e.getValue(); named.add(e.getKey()); }
            return Math.min(1, Math.max(0.1, w / 0.06));
        }
        double sw = Constituents.sectorWeight(sector, weights, sectorOf);
        if (!Double.isNaN(sw)) return Math.min(1, Math.max(0.15, sw / 0.25));
        String t = text == null ? "" : text.toLowerCase(Locale.US);
        for (String k : MACRO) if (t.contains(k)) return 1;
        return 0.6;
    }

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

package com.krish.niftydirection.data;

import com.krish.niftydirection.model.NewsItem;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Market headlines from public RSS feeds, plus a simple word-list reader used when there is no Gemini key. */
public final class News {
    private News() {}

    public static final String[][] FEEDS = {
            {"Google News", "https://news.google.com/rss/search?q=Nifty+OR+Sensex+OR+RBI+OR+%22stock+market%22+OR+FII+when:1d&hl=en-IN&gl=IN&ceid=IN:en"},
            {"Google News", "https://news.google.com/rss/search?q=Fed+OR+crude+OR+rupee+OR+inflation+OR+tariff+markets+when:1d&hl=en-IN&gl=IN&ceid=IN:en"},
            {"ET Markets", "https://economictimes.indiatimes.com/markets/rssfeeds/1977021501.cms"},
            {"Moneycontrol", "https://www.moneycontrol.com/rss/marketreports.xml"},
            {"Mint", "https://www.livemint.com/rss/markets"},
            {"RBI (official)", "https://www.rbi.org.in/pressreleases_rss.xml"},
            {"SEBI (official)", "https://www.sebi.gov.in/sebirss.xml"}};

    static final Pattern ITEM = Pattern.compile("<item>(.*?)</item>", Pattern.DOTALL);

    /** Headlines from the last `hours` hours, newest first, duplicates removed. */
    public static List<NewsItem> fetch(int hours) {
        List<NewsItem> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", Http.BROWSER_UA);
        long cutoff = System.currentTimeMillis() - hours * 3600_000L;
        for (String[] feed : FEEDS) {
            try {
                String xml = Http.get(feed[1], h, 12000);
                for (NewsItem n : parse(xml, feed[0])) {
                    if (feed[0].contains("(official)")) { n.official = true; n.source = feed[0]; }
                    if (n.time > 0 && n.time < cutoff) continue;
                    String key = norm(n.title);
                    if (key.length() < 12 || !seen.add(key)) continue;
                    out.add(n);
                }
            } catch (Exception ignored) { }
        }
        out.sort((a, b) -> Long.compare(b.time, a.time));
        return out.size() > 80 ? new ArrayList<>(out.subList(0, 80)) : out;
    }

    static List<NewsItem> parse(String xml, String feedName) {
        List<NewsItem> out = new ArrayList<>();
        Matcher m = ITEM.matcher(xml);
        while (m.find()) {
            String it = m.group(1);
            NewsItem n = new NewsItem();
            n.title = clean(tag(it, "title"));
            n.summary = clean(tag(it, "description"));
            if (n.summary.length() > 400) n.summary = n.summary.substring(0, 400);
            n.link = clean(tag(it, "link"));
            String src = clean(tag(it, "source"));
            n.source = src.isEmpty() ? feedName : src;
            // Google News puts " - Publisher" at the end of the title
            if (feedName.startsWith("Google") && n.title.contains(" - ")) n.title = n.title.substring(0, n.title.lastIndexOf(" - ")).trim();
            n.time = date(tag(it, "pubDate"));
            n.id = Integer.toHexString(norm(n.title).hashCode());
            if (!n.title.isEmpty()) out.add(n);
        }
        return out;
    }

    static String tag(String s, String t) {
        Matcher m = Pattern.compile("<" + t + "(?:\\s[^>]*)?>(.*?)</" + t + ">", Pattern.DOTALL).matcher(s);
        return m.find() ? m.group(1) : "";
    }

    static String clean(String s) {
        s = s.replace("<![CDATA[", "").replace("]]>", "");
        s = s.replaceAll("<[^>]+>", " ");
        s = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ");
        return s.replaceAll("\\s+", " ").trim();
    }

    static String norm(String t) { return t.toLowerCase(Locale.US).replaceAll("[^a-z0-9 ]", "").replaceAll("\\s+", " ").trim(); }

    static long date(String s) {
        s = s.trim();
        String[] fmts = {"EEE, dd MMM yyyy HH:mm:ss zzz", "EEE, dd MMM yyyy HH:mm:ss Z", "EEE, d MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm zzz"};
        for (String f : fmts) {
            try { return new SimpleDateFormat(f, Locale.US).parse(s).getTime(); } catch (Exception ignored) {}
        }
        return 0;
    }

    // ================================================================== verification: group the same story, count publishers

    static final java.util.Set<String> STOP = new HashSet<>(java.util.Arrays.asList(("a an the to of in on for and or at by with from as is are was were be "
            + "will may could says said after amid over its it this that into up down new today live updates update market markets stock stocks share shares india indian").split(" ")));

    static Set<String> words(String t) {
        Set<String> w = new HashSet<>();
        for (String x : norm(t).split(" ")) if (x.length() > 2 && !STOP.contains(x)) w.add(x);
        return w;
    }

    static final java.util.regex.Pattern SPEC = java.util.regex.Pattern.compile("\\b(may|might|could|likely|expected to|sources|reportedly|mulls|considering|plans to|set to|rumou?r|speculat)", java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * Group headlines about the same story (same Gemini topic, or ≥45% shared words), count distinct publishers, and mark:
     * VERIFIED (an official feed carries it), CONFIRMED (2+ publishers), PROVISIONAL (1 publisher).
     * The earliest item leads its group; the rest are duplicates and are not counted again.
     */
    public static void cluster(List<NewsItem> items) {
        int next = 0;
        List<Set<String>> ws = new ArrayList<>();
        for (NewsItem n : items) ws.add(words(n.title));
        for (int i = 0; i < items.size(); i++) items.get(i).cluster = -1;
        for (int i = 0; i < items.size(); i++) {
            NewsItem a = items.get(i);
            if (a.cluster >= 0) continue;
            a.cluster = next;
            for (int j = i + 1; j < items.size(); j++) {
                NewsItem b = items.get(j);
                if (b.cluster >= 0) continue;
                boolean sameTopic = !a.topic.isEmpty() && a.topic.equalsIgnoreCase(b.topic);
                String ag = agency(a);
                boolean sameAgencyStory = !ag.isEmpty() && ag.equals(agency(b)) && jaccard(ws.get(i), ws.get(j)) >= 0.3;
                if (sameTopic || sameAgencyStory || jaccard(ws.get(i), ws.get(j)) >= 0.45) b.cluster = next;
            }
            next++;
        }
        Map<Integer, List<NewsItem>> groups = new HashMap<>();
        for (NewsItem n : items) groups.computeIfAbsent(n.cluster, k -> new ArrayList<>()).add(n);
        for (List<NewsItem> g : groups.values()) {
            Set<String> pubs = new HashSet<>();
            boolean official = false;
            NewsItem lead = null;
            for (NewsItem n : g) {
                pubs.add(n.source.toLowerCase(Locale.US).trim());
                if (n.official) official = true;
                if (lead == null || (n.time > 0 && (lead.time <= 0 || n.time < lead.time))) lead = n;
                n.agency = agency(n);
            }
            int indep = independentReports(g);
            // VERIFIED = an official / primary source; CONFIRMED = 2+ independent reports;
            // CORROBORATED = several outlets but the same syndicated (agency / copied) story; PROVISIONAL = one source
            String v = official ? "VERIFIED" : indep >= 2 ? "CONFIRMED" : pubs.size() >= 2 ? "CORROBORATED" : "PROVISIONAL";
            for (NewsItem n : g) {
                n.independent = indep;
                n.publishers = pubs.size();
                n.verification = v;
                n.lead = n == lead;
                if (!n.speculative && SPEC.matcher(n.title).find()) n.speculative = true;
            }
            // the lead takes the strongest rating in the group (duplicates may have been rated slightly differently)
            for (NewsItem n : g) if (Math.abs(n.niftyImpact) > Math.abs(lead.niftyImpact) && n.read) {
                lead.niftyImpact = n.niftyImpact; lead.marketImpact = n.marketImpact; lead.severity = n.severity;
            }
        }
    }

    static final String[][] AGENCIES = {{"PTI", "\\bpti\\b|press trust of india"}, {"Reuters", "\\breuters\\b"}, {"IANS", "\\bians\\b"}, {"ANI", "\\bani\\b"},
            {"Bloomberg", "\\bbloomberg\\b"}, {"AFP", "\\bafp\\b"}, {"AP", "\\bassociated press\\b"}, {"UNI", "\\buni\\b"}};

    /** The news agency the item credits (from the title or description), or "". */
    static String agency(NewsItem n) {
        String t = (n.title + " " + n.summary).toLowerCase(Locale.US);
        for (String[] a : AGENCIES) if (java.util.regex.Pattern.compile(a[1]).matcher(t).find()) return a[0];
        return "";
    }

    /**
     * How many independent reports a story group has. Items are the same report (syndicated) when they come from
     * the same publisher, credit the same agency, or use near-identical wording (titles ≥90% or descriptions ≥75% of words shared).
     * Otherwise each distinct publisher's own write-up counts as independent.
     */
    static int independentReports(List<NewsItem> g) {
        int n = g.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        List<Set<String>> ws = new ArrayList<>();
        for (NewsItem x : g) ws.add(words(x.title + " " + x.summary));
        for (int i = 0; i < n; i++) for (int j = i + 1; j < n; j++) {
            NewsItem a = g.get(i), b = g.get(j);
            boolean samePub = a.source.equalsIgnoreCase(b.source);
            boolean sameAgency = !a.agency.isEmpty() && a.agency.equals(b.agency);
            boolean sameText = jaccard(words(a.title), words(b.title)) >= 0.9 || (!a.summary.isEmpty() && !b.summary.isEmpty() && jaccard(words(a.summary), words(b.summary)) >= 0.75);
            if (samePub || sameAgency || sameText) union(parent, i, j);
        }
        Set<Integer> roots = new HashSet<>();
        for (int i = 0; i < n; i++) if (!g.get(i).official) roots.add(find(parent, i));
        return Math.max(1, roots.size());
    }

    static int find(int[] p, int i) { while (p[i] != i) { p[i] = p[p[i]]; i = p[i]; } return i; }
    static void union(int[] p, int a, int b) { p[find(p, a)] = find(p, b); }

    static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        int in = 0;
        for (String x : a) if (b.contains(x)) in++;
        return in / (double) (a.size() + b.size() - in);
    }

    // ================================================================== word-list reader (no key)

    static final String[] UP = {"surge", "rally", "jump", "soar", "gain", "rise", "record high", "upgrade", "beat", "strong", "rate cut", "cuts rate",
            "eases", "inflow", "buying", "boost", "recover", "optimism", "deal", "ceasefire", "cools"};
    static final String[] DOWN = {"crash", "plunge", "slump", "tumble", "fall", "drop", "decline", "selloff", "sell-off", "downgrade", "miss", "weak",
            "rate hike", "hikes rate", "outflow", "selling", "war", "tariff", "sanction", "fear", "recession", "default", "probe", "slows", "spike in", "tension"};
    static final String[] BIG = {"rbi", "fed ", "federal reserve", "budget", "gdp", "inflation", "cpi", "war", "tariff", "election", "crude", "rupee", "fii", "nifty", "sensex"};

    /** Rough reading from words. Honest about being rough: impact is kept small. */
    public static void readByWords(NewsItem n) {
        String t = " " + n.title.toLowerCase(Locale.US) + " ";
        int up = 0, down = 0, big = 0;
        for (String w : UP) if (t.contains(w)) up++;
        for (String w : DOWN) if (t.contains(w)) down++;
        for (String w : BIG) if (t.contains(w)) big++;
        double v = Math.max(-1, Math.min(1, (up - down) * 0.3));
        n.marketImpact = v;
        n.niftyImpact = big > 0 ? v : v * 0.5;
        n.severity = big >= 2 && Math.abs(v) >= 0.3 ? "MEDIUM" : "LOW";
        n.horizon = "intraday";
        n.sector = big > 0 ? "Broad market" : "";
        n.reason = up == down ? "no clear tone" : (v > 0 ? "positive words" : "negative words");
        n.by = "keywords";
        n.model = "word list";
        n.promptVersion = "words-1";
        n.ratedAt = System.currentTimeMillis();
        n.read = true;
    }
}

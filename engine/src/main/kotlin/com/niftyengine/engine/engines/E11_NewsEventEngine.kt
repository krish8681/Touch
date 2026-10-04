package com.niftyengine.engine.engines

import com.niftyengine.engine.core.M
import com.niftyengine.engine.model.Candle
import com.niftyengine.engine.model.Detail
import com.niftyengine.engine.model.EngineSignal
import com.niftyengine.engine.model.EventDuration
import com.niftyengine.engine.model.EventType
import com.niftyengine.engine.model.NewsEvent
import com.niftyengine.engine.model.NewsItem
import com.niftyengine.engine.model.Sector
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min

/**
 * 11 — News / Event Engine (rule layer).
 *
 * In v4 the per-cycle scoring here is superseded by [EventIntelligenceEngine] (lifecycle, expectations,
 * pricing-in, reaction, multi-horizon). This class remains the deterministic rule analyst: keyword typing,
 * direction semantics, number extraction and source quality — used when Gemini is unavailable and as a
 * cross-check of Gemini's reading.
 *
 *  1. Only items published at or before `now` are visible (no look-ahead; required for replay).
 *  2. Duplicate stories from many outlets are clustered into ONE event; extra sources raise confidence,
 *     they never add extra directional votes.
 *  3. Each event is structured: type, expected, actual, surprise, direction, magnitude, sectors,
 *     duration, confidence, source quality.
 *  4. Impact decays: Impact(t) = Initial × e^(−λt), λ from the event duration's half-life.
 *  5. Market reaction score: what NIFTY actually did since the event, and whether it persisted.
 *     A bullish story the market sells is a DIVERGENCE and its influence is cut.
 */
class NewsEventEngine {
    data class Result(val signal: EngineSignal, val events: List<NewsEvent>, val shock: NewsEvent?)

    fun analyze(items: List<NewsItem>, now: Long, niftySeries: List<Candle>, prevClose: Double, last: Double, lookbackHours: Int = 18): Result {
        val visible = items.filter { it.publishedAt <= now && now - it.publishedAt <= lookbackHours * 3_600_000L }
            .sortedBy { it.publishedAt }
        if (visible.isEmpty()) return Result(EngineSignal.unavailable("News", "no recent news"), emptyList(), null)

        val clusters = cluster(visible)
        val events = clusters.map { buildEvent(it, now, niftySeries, prevClose, last) }
            .sortedByDescending { abs(it.effectiveImpact) + it.magnitude * it.decay * 0.1 }

        val total = events.sumOf { it.effectiveImpact }
        val score = M.squash(total, 0.8)
        val shock = events.firstOrNull { it.magnitude >= 0.7 && now - it.firstSeen <= 60 * 60_000L && it.confidence >= 0.45 }
        val divergences = events.count { it.divergence }
        val tags = buildList {
            if (shock != null) add("EVENT_SHOCK")
            if (divergences > 0) add("NEWS_MARKET_DIVERGENCE")
            if (events.size < visible.size) add("DEDUPED_${visible.size}_TO_${events.size}")
        }
        val conf = M.clamp(0.3 + 0.1 * events.count { it.decay > 0.2 && it.type != EventType.MARKET }, 0.0, 0.8)
        return Result(
            EngineSignal("News", score, conf, tags, listOf(
                Detail("Articles → events", "${visible.size} → ${events.size}"),
                Detail("Net impact", "%+.2f".format(total)),
                Detail("Top event", events.firstOrNull()?.let { "${it.type.label}: ${it.headline.take(70)}" } ?: "–"),
                Detail("Divergences", "$divergences"),
            )),
            events.take(40), shock,
        )
    }

    // ---------------------------------------------------------------- clustering
    private val stop = setOf("the", "and", "for", "with", "from", "that", "this", "are", "was", "will", "has", "have", "its",
        "after", "over", "into", "amid", "says", "said", "today", "live", "updates", "news", "stock", "stocks", "market",
        "markets", "share", "shares", "india", "indian", "what", "how", "why", "than", "more", "may", "now", "you", "your")

    fun tokens(s: String): Set<String> = s.lowercase().replace(Regex("[^a-z0-9.% ]"), " ").split(" ")
        .map { it.trim('.') }.filter { it.length >= 3 && it !in stop }.toSet()

    private fun cluster(items: List<NewsItem>): List<List<NewsItem>> {
        data class Cl(val items: MutableList<NewsItem>, var toks: MutableSet<String>)
        val out = mutableListOf<Cl>()
        for (it in items) {
            val tk = tokens(it.title)
            val match = out.firstOrNull { c ->
                val inter = c.toks.intersect(tk).size.toDouble()
                val jac = inter / (c.toks.union(tk).size.coerceAtLeast(1))
                val overlap = inter / min(c.toks.size, tk.size).coerceAtLeast(1)
                (jac >= 0.35 || (overlap >= 0.6 && inter >= 3)) && it.publishedAt - c.items.first().publishedAt < 8 * 3_600_000L
            }
            if (match != null) { match.items += it; match.toks.addAll(tk) } else out += Cl(mutableListOf(it), tk.toMutableSet())
        }
        return out.map { it.items }
    }

    // ---------------------------------------------------------------- classification
    private val typeRules: List<Pair<EventType, Regex>> = listOf(
        // A bare "RBI" mention is not monetary policy ("…new CEO after RBI approval", "RBI penalises bank"): policy wording required.
        EventType.RBI_POLICY to Regex("repo rate|reverse repo|monetary policy|\\bmpc\\b|policy rate|\\bcrr\\b|\\bslr\\b|malhotra|" +
            "\\b(rbi|reserve bank)('s)? (policy|governor|rate|keeps|cuts|hikes|raises|holds|leaves|stance|liquidity)|" +
            "rate (cut|hike)s?\\b.{0,40}\\b(rbi|reserve bank)\\b|\\b(rbi|reserve bank)\\b.{0,40}rate (cut|hike)"),
        EventType.FED to Regex("\\bfed\\b|fomc|federal reserve|powell|rate cut.*us|us rate"),
        EventType.INFLATION to Regex("inflation|\\bcpi\\b|\\bwpi\\b|consumer price"),
        EventType.GROWTH to Regex("\\bgdp\\b|\\biip\\b|\\bpmi\\b|industrial output|growth rate|economy grew|core sector"),
        EventType.US_DATA to Regex("payroll|jobless|nonfarm|us jobs|us cpi|treasury yield|us economy"),
        EventType.CRUDE to Regex("crude|brent|oil price|opec"),
        EventType.GEOPOLITICS to Regex("\\bwar\\b|missile|attack|strike on|military|border|sanction|ceasefire|conflict|tension|terror|tariff"),
        EventType.EARNINGS to Regex("q[1-4] (results|profit|earnings)|net profit|quarterly|earnings|results|beats estimates|misses estimates"),
        EventType.FLOWS to Regex("\\bfii|\\bfpi|\\bdii|foreign investors|outflow|inflow|foreign fund"),
        EventType.GOVERNMENT to Regex("budget|finance minister|sitharaman|government|cabinet|gst|tax|sebi|policy reform|election"),
        EventType.CURRENCY to Regex("rupee|usd/inr|dollar index|forex reserve"),
        EventType.MARKET to Regex("sensex|nifty|closing bell|opening bell|market wrap|stock market today|trade setup"),
    )
    private val baseMagnitude = mapOf(
        EventType.RBI_POLICY to 0.9, EventType.FED to 0.7, EventType.INFLATION to 0.55, EventType.GROWTH to 0.45,
        EventType.US_DATA to 0.45, EventType.CRUDE to 0.5, EventType.GEOPOLITICS to 0.75, EventType.EARNINGS to 0.35,
        EventType.FLOWS to 0.3, EventType.GOVERNMENT to 0.55, EventType.CURRENCY to 0.4, EventType.CORPORATE to 0.3,
        EventType.MARKET to 0.12, EventType.OTHER to 0.15,
    )
    private val durationOf = mapOf(
        EventType.RBI_POLICY to EventDuration.MEDIUM, EventType.FED to EventDuration.MEDIUM, EventType.INFLATION to EventDuration.MEDIUM,
        EventType.GROWTH to EventDuration.MEDIUM, EventType.GEOPOLITICS to EventDuration.MEDIUM, EventType.GOVERNMENT to EventDuration.LONG,
    )
    private val bull = Regex("\\b(?:surge|soar|rally|jump|gain|rise|rises|rose|climb|record high|beat|beats|upgrade|boost|strong|eases|cools|cut rates|rate cut|dovish|inflow|buying|ceasefire|deal|stimulus|recover|rebound|optimism|upbeat)")
    private val bear = Regex("\\b(?:plunge|crash|slump|tumble|fall|falls|fell|drop|decline|slide|sink|miss|misses|downgrade|weak|hike|hawkish|war|attack|sanction|tariff|outflow|selling|sell-off|selloff|recession|fear|concern|worry|record low|pressure|slowdown|crisis|default|escalat)")
    private val sectorRules: List<Pair<Sector, Regex>> = listOf(
        Sector.BANK to Regex("bank|lender|nbfc|credit|loan|deposit|hdfc|icici|sbi|kotak|axis"),
        Sector.IT to Regex("\\bit\\b|software|tech|infosys|tcs|wipro|hcl|accenture|nasdaq"),
        Sector.ENERGY to Regex("oil|crude|gas|power|energy|reliance|ongc|ntpc|coal"),
        Sector.AUTO to Regex("auto|car|vehicle|maruti|tata motors|mahindra|ev\\b|two-wheeler"),
        Sector.FMCG to Regex("fmcg|consumer goods|itc|hindustan unilever|nestle|rural demand"),
        Sector.PHARMA to Regex("pharma|drug|usfda|hospital|health"),
        Sector.METALS to Regex("metal|steel|aluminium|copper|iron ore|china demand"),
        Sector.TELECOM to Regex("telecom|airtel|tariff hike|spectrum"),
    )

    fun sourceQuality(src: String): Double {
        val s = src.lowercase()
        return when {
            listOf("nse", "bse", "rbi", "sebi", "pib", "government").any { s.contains(it) } -> 1.0
            listOf("reuters", "bloomberg").any { s.contains(it) } -> 0.95
            listOf("economic times", "economictimes", "livemint", "mint", "moneycontrol", "business standard", "cnbc", "financial express", "hindu businessline")
                .any { s.contains(it) } -> 0.8
            listOf("twitter", "x.com", "telegram", "reddit").any { s.contains(it) } -> 0.4
            else -> 0.6
        }
    }

    data class Numbers(val expected: Double, val actual: Double)

    /** Pulls "actual vs expected" pairs such as "cut 50 bps vs 25 bps expected" or "6.2% against estimate of 5.9%". */
    fun extractNumbers(text: String): Numbers {
        val t = text.lowercase()
        val num = "(-?\\d+(?:\\.\\d+)?)\\s*(?:%|bps|basis points|per cent|percent)?"
        val exp1 = Regex("(?:expected|estimates?|forecast|poll|consensus|projected)\\s*(?:of|at|was|is)?\\s*$num").find(t)
        val exp2 = Regex("$num\\s*(?:expected|estimated|forecast)").find(t)
        val expected = (exp1 ?: exp2)?.groupValues?.get(1)?.toDoubleOrNull() ?: Double.NaN
        val all = Regex("$num").findAll(t).map { it.groupValues[1] }.mapNotNull { it.toDoubleOrNull() }
            .filter { it < 1000 && it !in listOf(2024.0, 2025.0, 2026.0, 2027.0) }.toList()
        val actual = all.firstOrNull { expected.isNaN() || it != expected } ?: Double.NaN
        return Numbers(expected, if (expected.isNaN()) Double.NaN else actual)
    }

    /** Rule-based reading of one article cluster (also the fallback analyst for the v4 event-intelligence engine). */
    fun buildEvent(items: List<NewsItem>, now: Long, series: List<Candle>, prevClose: Double, last: Double): NewsEvent {
        val text = items.joinToString(" ") { it.title + " " + it.summary }.lowercase()
        val headline = items.maxBy { sourceQuality(it.source) }.title
        val type = typeRules.firstOrNull { it.second.containsMatchIn(text) }?.first ?: EventType.OTHER

        val bullHits = bull.findAll(text).count().toDouble()
        val bearHits = bear.findAll(text).count().toDouble()
        var direction = if (bullHits + bearHits == 0.0) 0.0 else (bullHits - bearHits) / (bullHits + bearHits)
        // Event-specific semantics where generic words mislead.
        when (type) {
            // Oil up is bad for India (importer), oil down is good.
            EventType.CRUDE -> direction = when {
                Regex("(crude|oil|brent)[a-z ]{0,20}(surge|jump|rise|rises|soar|spike|climb|rall|gain|higher)").containsMatchIn(text) -> -0.6
                Regex("(crude|oil|brent)[a-z ]{0,20}(fall|drop|slump|plunge|ease|slide|decline|lower|tumble|sink)").containsMatchIn(text) -> 0.6
                else -> -direction
            }
            EventType.INFLATION -> direction = when {
                Regex("eases|cools|lower|slows|falls|declin").containsMatchIn(text) -> 0.6
                Regex("rises|higher|accelerat|jumps|surges").containsMatchIn(text) -> -0.6
                else -> direction
            }
            EventType.RBI_POLICY, EventType.FED -> direction = when {
                Regex("cut|dovish|easing|liquidity infusion").containsMatchIn(text) -> 0.7
                Regex("hike|hawkish|tighten").containsMatchIn(text) -> -0.7
                Regex("unchanged|hold|holds|status quo|pause").containsMatchIn(text) -> 0.0
                else -> direction
            }
            EventType.CURRENCY -> if (Regex("rupee").containsMatchIn(text)) direction = when {
                Regex("rupee (falls|weakens|slips|hits record low|depreciat)").containsMatchIn(text) -> -0.5
                Regex("rupee (gains|rises|strengthens|appreciat)").containsMatchIn(text) -> 0.5
                else -> direction
            }
            else -> {}
        }

        val nums = extractNumbers(text)
        var surprise = if (nums.expected.isNaN() || nums.actual.isNaN()) 0.0 else nums.actual - nums.expected
        // Translate numeric surprise to market direction by event semantics.
        val surpriseDir = when (type) {
            EventType.INFLATION -> -surprise
            EventType.GROWTH -> surprise
            EventType.RBI_POLICY, EventType.FED -> if (text.contains("cut")) surprise else -surprise
            else -> 0.0
        }
        if (surpriseDir != 0.0) direction = M.clamp(0.4 * direction + 0.6 * M.squash(surpriseDir, 0.25))
        val qualitativeSurprise = Regex("unexpected|surprise|than expected|beats estimates|misses estimates|shock").containsMatchIn(text)
        if (surprise == 0.0 && qualitativeSurprise) surprise = direction

        val sectors = sectorRules.filter { it.second.containsMatchIn(text) }.map { it.first }
        val heavyMentioned = Constituents.DEFAULT.filter { text.contains(it.symbol.lowercase()) }.sumOf { it.weight }
        var magnitude = baseMagnitude.getValue(type)
        if (type == EventType.EARNINGS || type == EventType.CORPORATE) magnitude += min(0.4, heavyMentioned / 20)
        if (type == EventType.GOVERNMENT && text.contains("budget")) magnitude = 0.9
        if (qualitativeSurprise || abs(surprise) > 0) magnitude = min(1.0, magnitude * 1.25)

        val quality = items.map { sourceQuality(it.source) }.average()
        val distinctSources = items.map { it.source.lowercase() }.distinct().size
        val clarity = if (bullHits + bearHits == 0.0 && surpriseDir == 0.0) 0.5 else 0.6 + 0.4 * abs(direction)
        val confidence = M.clamp((0.45 + 0.15 * (distinctSources - 1)).coerceAtMost(1.0) * quality * clarity, 0.0, 1.0)

        val firstSeen = items.first().publishedAt
        val lastSeen = items.last().publishedAt
        val duration = durationOf[type] ?: EventDuration.SHORT
        // Confirmation by later sources partially refreshes the clock.
        val effAgeMin = ((now - (0.7 * firstSeen + 0.3 * lastSeen)) / 60_000.0).coerceAtLeast(0.0)
        val decay = exp(-ln(2.0) * effAgeMin / duration.halfLifeMinutes)

        // Market reaction since the event.
        val sessionOpen = series.firstOrNull()?.t ?: Long.MAX_VALUE
        val p0 = if (firstSeen < sessionOpen) prevClose else series.lastOrNull { it.t <= firstSeen }?.c ?: prevClose
        val p15 = if (firstSeen < sessionOpen) (series.firstOrNull { it.t >= sessionOpen + 15 * 60_000L }?.c ?: last)
        else series.lastOrNull { it.t <= firstSeen + 15 * 60_000L }?.c ?: last
        val initialPct = M.pctChange(p0, p15)
        val nowPct = M.pctChange(p0, last)
        val reaction = M.squash(DataNormalizer.nz(nowPct), 0.35)
        val persistence = if (abs(DataNormalizer.nz(initialPct)) < 0.05) 0.5 else M.clamp(nowPct / initialPct, 0.0, 1.0)
        val minutesSince = (now - firstSeen) / 60_000.0
        val divergence = minutesSince >= 15 && abs(direction) > 0.2 && direction * reaction < -0.15
        val reactionAdj = when {
            minutesSince < 15 -> 1.0
            divergence -> 0.25
            direction * reaction > 0 -> 0.6 + 0.4 * persistence
            else -> 0.6
        }
        val impact = direction * magnitude * confidence * decay * reactionAdj

        return NewsEvent(
            clusterId = Integer.toHexString(headline.hashCode()), headline = headline, type = type,
            sources = items.map { it.source }.distinct(), firstSeen = firstSeen,
            expected = if (nums.expected.isNaN()) "–" else "%.2f".format(nums.expected),
            actual = if (nums.actual.isNaN()) "–" else "%.2f".format(nums.actual),
            surprise = surprise, direction = direction, magnitude = magnitude, sectors = sectors,
            duration = duration, confidence = confidence, sourceQuality = quality, decay = decay,
            marketReaction = reaction, reactionPersistence = persistence, divergence = divergence,
            effectiveImpact = impact,
        )
    }
}

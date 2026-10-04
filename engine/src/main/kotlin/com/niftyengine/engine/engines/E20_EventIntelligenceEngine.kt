package com.niftyengine.engine.engines

import com.niftyengine.engine.core.M
import com.niftyengine.engine.core.Session
import com.niftyengine.engine.model.AnalysisRequest
import com.niftyengine.engine.model.Candle
import com.niftyengine.engine.model.ChannelReaction
import com.niftyengine.engine.model.Detail
import com.niftyengine.engine.model.EngineSignal
import com.niftyengine.engine.model.EventAnalysis
import com.niftyengine.engine.model.EventDuration
import com.niftyengine.engine.model.EventStage
import com.niftyengine.engine.model.EventType
import com.niftyengine.engine.model.ExpectationPoint
import com.niftyengine.engine.model.MarketBaseline
import com.niftyengine.engine.model.MarketChannel
import com.niftyengine.engine.model.NewsHorizon
import com.niftyengine.engine.model.NewsItem
import com.niftyengine.engine.model.ReactionReport
import com.niftyengine.engine.model.Sector
import com.niftyengine.engine.model.StageChange
import com.niftyengine.engine.model.TrackedEvent
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sign

/**
 * Rule-based event analyst: the deterministic fallback when Gemini is unavailable (no key, quota, offline)
 * and the cross-check for Gemini. Produces the same [EventAnalysis] schema at lower confidence.
 */
object RuleEventAnalyzer {
    /** Checked in order: hedged language (reportedly / likely / may) outranks escalation and confirmation verbs. */
    private val stageRules: List<Pair<EventStage, Regex>> = listOf(
        EventStage.RESOLVED to Regex("\\b(resolved|called off|agreement signed|deal signed|sanctions lifted|ends? (the )?(war|conflict|strike))"),
        EventStage.RESOLVING to Regex("ceasefire|truce|de-?escalat|peace talks|talks resume|tension eases|negotiat"),
        EventStage.EXPECTED to Regex("expected to|economists expect|analysts expect|poll shows|consensus|seen (cutting|hiking|raising)|forecast to|likely to (cut|hike|raise|hold)"),
        EventStage.RUMOUR to Regex("reportedly|sources (said|say)|rumou?r|unconfirmed|speculat"),
        EventStage.LIKELY to Regex("\\b(likely|set to|poised to|plans to|to announce|on track to)\\b"),
        EventStage.POSSIBLE to Regex("\\b(may|might|could|possible|possibility|considers?|weighs|mulls|explor(es|ing)|eyes)\\b"),
        EventStage.ESCALATING to Regex("escalat|retaliat|widens?|intensif|fresh (attack|strike)s?|more strikes|spreads to"),
        EventStage.CONFIRMED to Regex("\\b(confirm(s|ed)?|announce[sd]?|cuts?|cut by|raises|raised|hikes|hiked|approve[sd]|signs|signed|imposes|imposed|keeps .* unchanged|holds (repo|rates?)|posts|reports|declared?|launch(es|ed)|attack(s|ed)? |struck)\\b"),
    )
    /**
     * Previews, outlooks, opinion and question headlines ("Week ahead: RBI policy…", "MPC meeting begins Monday; hike
     * possible", "Rate hike? …", "Quote on RBI MPC expectation…") discuss a scheduled event — they confirm nothing, and the
     * market already knows the event is coming. Judged on the TITLE only, and only when the title has no outcome verb
     * ("RBI cuts repo rate, in line with expectations" stays CONFIRMED).
     */
    private val preview = Regex("week ahead|weekly (outlook|wrap)|\\boutlook\\b|what to expect|things to watch|\\bto watch\\b|key triggers|" +
        "triggers? (for|to watch|next week)|\\bpreview\\b|curtain.?raiser|\\bbegins\\b|kicks off|to decide|decision (due|tomorrow|today)|" +
        "meeting (begins|starts|today|tomorrow)|\\bexpectations? (from|ahead)|\\bquote on\\b|\\bview:|\\bopinion\\b|explainer|explained|\\?")
    private val outcomeVerb = Regex("\\b(cuts?|cut by|raises|raised|hikes|hiked|keeps|kept|holds|held|leaves|left|announce[sd]|confirm(s|ed)|" +
        "unchanged|approve[sd]|signs|signed|imposes|imposed|posts|reports|declared?)\\b")
    private val defaultProb = mapOf(EventStage.RUMOUR to 0.25, EventStage.POSSIBLE to 0.4, EventStage.LIKELY to 0.65, EventStage.EXPECTED to 0.8)

    fun channelsFor(t: EventType): List<MarketChannel> = when (t) {
        EventType.RBI_POLICY -> listOf(MarketChannel.RATES, MarketChannel.FLOWS)
        EventType.FED -> listOf(MarketChannel.RATES, MarketChannel.FX, MarketChannel.GLOBAL_EQUITY)
        EventType.INFLATION -> listOf(MarketChannel.INFLATION, MarketChannel.RATES)
        EventType.GROWTH -> listOf(MarketChannel.GROWTH)
        EventType.US_DATA -> listOf(MarketChannel.RATES, MarketChannel.GLOBAL_EQUITY)
        EventType.CRUDE -> listOf(MarketChannel.CRUDE, MarketChannel.FX, MarketChannel.INFLATION)
        EventType.GEOPOLITICS -> listOf(MarketChannel.RISK_SENTIMENT, MarketChannel.CRUDE)
        EventType.EARNINGS, EventType.CORPORATE -> listOf(MarketChannel.EARNINGS, MarketChannel.SECTOR_SPECIFIC)
        EventType.FLOWS -> listOf(MarketChannel.FLOWS, MarketChannel.FX)
        EventType.GOVERNMENT -> listOf(MarketChannel.GROWTH, MarketChannel.SECTOR_SPECIFIC)
        EventType.CURRENCY -> listOf(MarketChannel.FX, MarketChannel.FLOWS)
        EventType.MARKET, EventType.OTHER -> listOf(MarketChannel.RISK_SENTIMENT)
    }

    fun stageOf(title: String, summary: String = ""): EventStage {
        val head = title.lowercase()
        if (preview.containsMatchIn(head) && !outcomeVerb.containsMatchIn(head)) return EventStage.EXPECTED
        val t = (title + " " + summary).lowercase()
        return stageRules.firstOrNull { it.second.containsMatchIn(t) }?.first ?: EventStage.CONFIRMED
    }

    fun analyze(rules: NewsEventEngine, eventId: String, items: List<NewsItem>, now: Long, series: List<Candle>, prevClose: Double, last: Double): EventAnalysis {
        val ne = rules.buildEvent(items.sortedBy { it.publishedAt }, now, series, prevClose, last)
        val latest = items.maxBy { it.publishedAt }
        val stage = stageOf(latest.title, latest.summary)
        val text = items.joinToString(" ") { it.title + " " + it.summary }.lowercase()
        val matches: Boolean? = when {
            ne.expected != "–" && ne.actual != "–" -> ne.expected == ne.actual
            Regex("as expected|in line with|on expected lines|as anticipated").containsMatchIn(text) -> true
            Regex("unexpected|surprise|than expected|beats estimates|misses estimates|shock").containsMatchIn(text) -> false
            else -> null
        }
        val dirSign = sign(ne.direction)
        return EventAnalysis(
            eventId = eventId, analyzedAt = now, source = "rules", title = latest.title, eventType = ne.type, stage = stage,
            severity = ne.magnitude, direction = ne.direction, affectedSectors = ne.sectors,
            affectedStocks = Constituents.DEFAULT.filter { text.contains(it.symbol.lowercase()) }.map { it.symbol },
            channels = channelsFor(ne.type),
            expectedOutcome = if (ne.expected != "–") "expected ${ne.expected}" else "",
            expectedProbability = defaultProb[stage] ?: Double.NaN,
            actualOutcome = if (ne.actual != "–" && stage.outcomeKnown) "actual ${ne.actual}" else "",
            actualMatchesExpectation = if (stage.outcomeKnown) matches else null,
            surprise = when (matches) { true -> 0.15 * dirSign; false -> 0.6 * dirSign; null -> 0.0 },
            duration = ne.duration,
            persistence = when (ne.duration) { EventDuration.SHORT -> 0.3; EventDuration.MEDIUM -> 0.5; EventDuration.LONG -> 0.8 },
            escalationRisk = if (ne.type == EventType.GEOPOLITICS) 0.4 else 0.1,
            confidence = ne.confidence * 0.8,
            rationale = "keyword rules",
        )
    }
}

/** Serializable memory of the event tracker (persisted by the app across restarts and days). */
@Serializable
data class EventTrackerState(
    val events: List<TrackedEvent> = emptyList(),
    val articleToEvent: Map<String, String> = emptyMap(),
    val articles: List<NewsItem> = emptyList(),
    val geminiAnalyzedAt: Map<String, Long> = emptyMap(),
    val lastIngestAt: Map<String, Long> = emptyMap(),
)

/**
 * 20 — Event Intelligence Engine (v4).
 *
 *  • Event lifecycle + clustering: articles are attached to persistent EVENT_IDs (token similarity, plus
 *    Gemini's `mergeWith`), so the same story from ten outlets — or developing over days — is ONE event.
 *  • Expectation state: expected outcome + probability over time, actual outcome, expectation change.
 *    Expected news is not new news: surprise = information not already expected.
 *  • Pricing-in: how much of the new information the market has already absorbed, measured on NIFTY,
 *    Bank Nifty, affected sectors/stocks, breadth, futures, options, VIX and USDINR.
 *  • Reaction confirmation: if the market contradicts the reading, news confidence is cut — the market is
 *    never forced to agree with the analyst.
 *  • Effective impact = event impact × unpriced × surprise × confidence, decayed separately per horizon
 *    (5–15 min, 30–120 min, EOD, 1–3 days, 1–2 weeks).
 */
class EventIntelligenceEngine(private val rules: NewsEventEngine = NewsEventEngine()) {
    private val events = LinkedHashMap<String, TrackedEvent>()
    private val articleToEvent = HashMap<String, String>()
    private val articles = LinkedHashMap<String, NewsItem>()
    private val geminiAnalyzedAt = HashMap<String, Long>()
    private val lastIngestAt = HashMap<String, Long>()

    data class Result(
        val signal: EngineSignal,
        val events: List<TrackedEvent>,
        val horizons: Map<NewsHorizon, Double>,
        val shock: TrackedEvent?,
        val contradicted: List<TrackedEvent>,
        val pending: List<AnalysisRequest>,
    )

    fun exportState() = EventTrackerState(events.values.toList(), articleToEvent.toMap(), articles.values.toList(),
        geminiAnalyzedAt.toMap(), lastIngestAt.toMap())

    fun importState(s: EventTrackerState) {
        events.clear(); articleToEvent.clear(); articles.clear(); geminiAnalyzedAt.clear(); lastIngestAt.clear()
        s.events.forEach { events[it.id] = it }
        articleToEvent.putAll(s.articleToEvent)
        s.articles.forEach { articles[it.id] = it }
        geminiAnalyzedAt.putAll(s.geminiAnalyzedAt)
        lastIngestAt.putAll(s.lastIngestAt)
    }

    // ------------------------------------------------------------------------------------------ main

    fun process(
        news: List<NewsItem>, analyses: List<EventAnalysis>, now: Long,
        market: MarketBaseline, prevCloseBaseline: MarketBaseline, niftySeries: List<Candle>,
        decisionHorizon: NewsHorizon,
    ): Result {
        val sessionStart = Session.sessionStart(now)
        fun baselineAt(t: Long): MarketBaseline = when {
            t < sessionStart && now - t < 4 * 86_400_000L -> prevCloseBaseline.copy(t = t)
            else -> {
                val px = niftySeries.lastOrNull { it.t <= t }?.c
                market.copy(t = t, nifty = px ?: market.nifty)
            }
        }

        // 1) ingest new articles (point-in-time: only those published by now)
        val fresh = news.filter { it.publishedAt <= now && now - it.publishedAt < 3 * 86_400_000L && it.id !in articleToEvent }
            .sortedBy { it.publishedAt }
        for (a in fresh) {
            articles[a.id] = a
            val toks = eventTokens(a.title)
            val target = events.values.filter { now - it.lastInfoAt < 3 * 86_400_000L && it.stage != EventStage.RESOLVED }
                .map { it to similarity(it.tokens.toSet(), toks) }.filter { it.second >= 0.35 }.maxByOrNull { it.second }?.first
            if (target != null) {
                events[target.id] = target.copy(
                    articleIds = target.articleIds + a.id, sources = (target.sources + a.source).distinct(),
                    tokens = (target.tokens + toks).distinct().take(80),
                )
                articleToEvent[a.id] = target.id
                lastIngestAt[target.id] = now
            } else {
                val id = "E" + Integer.toHexString(a.id.hashCode()).uppercase().padStart(8, '0')
                events[id] = TrackedEvent(
                    id = id, title = a.title, type = EventType.OTHER, firstSeen = a.publishedAt, lastInfoAt = a.publishedAt,
                    articleIds = listOf(a.id), sources = listOf(a.source), tokens = toks.toList(), stage = EventStage.CONFIRMED,
                    stageHistory = emptyList(), expectations = emptyList(), analysis = null,
                    baseline = baselineAt(a.publishedAt),
                )
                articleToEvent[a.id] = id
                lastIngestAt[id] = now
            }
        }

        // 2) apply analyst results (merges first), point-in-time
        for (an in analyses.filter { it.analyzedAt <= now }.sortedBy { it.analyzedAt }) {
            var id = an.eventId
            for (other in an.mergeWith) if (other != id && events.containsKey(other) && events.containsKey(id)) id = merge(id, other)
            val ev = events[resolveId(id)] ?: continue
            if (an.source.startsWith("gemini")) geminiAnalyzedAt[ev.id] = maxOf(geminiAnalyzedAt[ev.id] ?: 0L, an.analyzedAt)
            // Information time = newest article the analyst could have read, not when its answer arrived.
            val infoT = ev.articleIds.mapNotNull { articles[it]?.publishedAt }.filter { it <= an.analyzedAt }.maxOrNull() ?: an.analyzedAt
            events[ev.id] = applyAnalysis(ev, an.copy(eventId = ev.id), baselineAt(infoT))
        }

        // 3) rule analysis for events with new articles (and no newer AI reading)
        for (ev in events.values.toList()) {
            val ing = lastIngestAt[ev.id] ?: continue
            val cur = ev.analysis
            if (cur != null && cur.analyzedAt >= ing) continue
            val items = ev.articleIds.mapNotNull { articles[it] }
            if (items.isEmpty()) continue
            val ra = RuleEventAnalyzer.analyze(rules, ev.id, items, now, niftySeries, prevCloseBaseline.nifty, market.nifty)
            // An AI reading stays authoritative unless new articles have arrived since it was made.
            if (cur == null || cur.source == "rules" || ing > cur.analyzedAt) {
                val infoT = items.maxOf { it.publishedAt }
                events[ev.id] = applyAnalysis(ev, ra.copy(analyzedAt = maxOf(ing, cur?.analyzedAt ?: 0L)), baselineAt(infoT))
            }
        }

        // 4) expire
        events.values.filter { now - it.lastInfoAt > 14 * 86_400_000L || (it.stage == EventStage.RESOLVED && now - it.lastInfoAt > 2 * 86_400_000L) }
            .forEach { e -> events.remove(e.id); e.articleIds.forEach { articles.remove(it) } }
        while (events.size > 200) events.remove(events.keys.first())

        // 5) derive expectations / pricing / reaction / horizons
        val derived = events.values.filter { it.analysis != null }.map { derive(it, now, market) }
        derived.forEach { events[it.id] = it }

        val horizons = NewsHorizon.values().associateWith { h -> M.squash(derived.sumOf { it.horizonImpacts[h] ?: 0.0 }, 0.6) }
        val score = horizons.getValue(decisionHorizon)
        val active = derived.filter { abs(it.effectiveImpact) > 0.01 }
        val ai = derived.count { it.analysis?.source?.startsWith("gemini") == true }
        val conf = if (derived.isEmpty()) 0.0 else M.clamp(0.3 + 0.08 * active.size + if (ai > 0) 0.1 else 0.0, 0.0, 0.85)
        val shock = derived.filter { isShock(it, now) }.maxByOrNull { abs(it.effectiveImpact) }
        val contradicted = derived.filter { it.reaction.contradicted && (it.analysis?.severity ?: 0.0) >= 0.5 }
        val tags = buildList {
            if (shock != null) add("EVENT_SHOCK")
            if (contradicted.isNotEmpty()) add("MARKET_DISAGREES_WITH_NEWS")
            if (derived.any { "MOSTLY_PRICED" in it.flags && (it.analysis?.severity ?: 0.0) >= 0.5 }) add("MAJOR_NEWS_PRICED_IN")
            if (ai > 0) add("AI_READ_$ai")
        }
        val top = derived.sortedByDescending { abs(it.effectiveImpact) }
        val signal = if (derived.isEmpty()) EngineSignal.unavailable("News", "no tracked events")
        else EngineSignal("News", score, conf, tags, listOf(
            Detail("Events (articles)", "${derived.size} (${derived.sumOf { it.articleIds.size }})"),
            Detail("Impact by horizon", horizons.entries.joinToString { "${it.key.label} %+.2f".format(it.value) }),
            Detail("Top event", top.firstOrNull()?.let { "${it.stage.label}: ${it.title.take(60)}" } ?: "–"),
            Detail("Analyst", "$ai AI-read · ${derived.size - ai} rules"),
        ))
        return Result(signal, top.take(40), horizons, shock, contradicted, pending(now))
    }

    // ------------------------------------------------------------------------------------------ pieces

    /** Title words plus adjacent-word phrases ("border_clash", "repo_rate") — phrases identify the same story across rewrites. */
    fun eventTokens(title: String): Set<String> {
        val words = rules.tokens(title).toList()
        val bigrams = words.zipWithNext { x, y -> "${x}_$y" }.filter { b -> listOf("nifty", "sensex", "stock", "market").none { b.contains(it) } }
        return (words + bigrams).toSet()
    }

    private fun similarity(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val wa = a.filter { '_' !in it }.toSet(); val wb = b.filter { '_' !in it }.toSet()
        val inter = wa.intersect(wb).size.toDouble()
        val jac = if (wa.isEmpty() || wb.isEmpty()) 0.0 else inter / wa.union(wb).size
        val overlap = if (wa.isEmpty() || wb.isEmpty()) 0.0 else inter / min(wa.size, wb.size)
        val sharedPhrase = a.any { '_' in it && it in b }
        return when {
            overlap >= 0.6 && inter >= 3 -> maxOf(jac, 0.35)
            sharedPhrase && inter >= 2 -> maxOf(jac, 0.4)
            else -> jac
        }
    }

    private fun resolveId(id: String): String = id

    /** Merge [b] into [a] (keeps the older id). Returns surviving id. */
    private fun merge(a: String, b: String): String {
        val ea = events[a] ?: return b
        val eb = events[b] ?: return a
        val (keep, drop) = if (ea.firstSeen <= eb.firstSeen) ea to eb else eb to ea
        events[keep.id] = keep.copy(
            articleIds = (keep.articleIds + drop.articleIds).distinct(), sources = (keep.sources + drop.sources).distinct(),
            tokens = (keep.tokens + drop.tokens).distinct().take(80),
            stageHistory = (keep.stageHistory + drop.stageHistory).sortedBy { it.t },
            expectations = (keep.expectations + drop.expectations).sortedBy { it.t },
            lastInfoAt = maxOf(keep.lastInfoAt, drop.lastInfoAt),
        )
        events.remove(drop.id)
        drop.articleIds.forEach { articleToEvent[it] = keep.id }
        lastIngestAt[keep.id] = maxOf(lastIngestAt[keep.id] ?: 0L, lastIngestAt.remove(drop.id) ?: 0L)
        geminiAnalyzedAt.remove(drop.id)?.let { geminiAnalyzedAt[keep.id] = maxOf(geminiAnalyzedAt[keep.id] ?: 0L, it) }
        return keep.id
    }

    private fun stageProb(a: EventAnalysis): Double = when {
        !a.expectedProbability.isNaN() -> M.clamp(a.expectedProbability, 0.0, 1.0)
        else -> when (a.stage) {
            EventStage.RUMOUR -> 0.25; EventStage.POSSIBLE -> 0.4; EventStage.LIKELY -> 0.65; EventStage.EXPECTED -> 0.8
            else -> Double.NaN
        }
    }

    /** Record stage changes and expectation changes; new information resets the information clock/baseline. */
    private fun applyAnalysis(ev: TrackedEvent, a: EventAnalysis, base: MarketBaseline): TrackedEvent {
        val prevStage = if (ev.stageHistory.isEmpty()) null else ev.stage
        val prevExp = ev.expectations.lastOrNull()
        val p = stageProb(a)
        val stageChanged = prevStage != a.stage
        val probChanged = prevExp != null && !p.isNaN() && !prevExp.probability.isNaN() && abs(p - prevExp.probability) >= 0.05
        val outcomeNew = a.stage.outcomeKnown && a.actualOutcome.isNotBlank() && prevExp?.actualOutcome != a.actualOutcome
        val newInfo = prevStage == null || stageChanged || probChanged || outcomeNew
        val infoT = if (newInfo) maxOf(base.t, ev.firstSeen) else ev.lastInfoAt
        // A re-reading of the SAME articles (e.g. AI after rules) replaces that reading instead of counting as new information.
        val sameInfo = prevExp != null && prevExp.t == infoT
        val point = ExpectationPoint(infoT, a.stage, a.expectedOutcome, p, a.actualOutcome, a.source)
        val change = StageChange(infoT, a.stage, a.source)
        return ev.copy(
            title = a.title.ifBlank { ev.title }, type = a.eventType, stage = a.stage, analysis = a,
            stageHistory = when {
                sameInfo && ev.stageHistory.lastOrNull()?.t == infoT -> ev.stageHistory.dropLast(1) + change
                stageChanged || prevStage == null -> ev.stageHistory + change
                else -> ev.stageHistory
            },
            expectations = when {
                sameInfo -> ev.expectations.dropLast(1) + point
                newInfo -> ev.expectations + point
                else -> ev.expectations
            },
            lastInfoAt = infoT,
            infoBaseline = if (newInfo) base else ev.infoBaseline ?: base,
            baseline = ev.baseline ?: base,
        )
    }

    data class Surprise(val magnitude: Double, val sign: Double, val note: String)

    /** Expectation-state logic: how much of the latest information was NOT already expected. */
    fun surpriseOf(ev: TrackedEvent): Surprise {
        val a = ev.analysis!!
        val dir = sign(a.direction).takeIf { it != 0.0 } ?: 1.0
        val exps = ev.expectations
        val priorPoint = exps.lastOrNull { !it.stage.outcomeKnown && !it.probability.isNaN() }
        return when {
            a.stage == EventStage.CONFIRMED -> {
                val prior = priorPoint?.probability ?: a.expectedProbability.takeIf { !it.isNaN() }
                val engineS = when (a.actualMatchesExpectation) {
                    true -> 1 - (prior ?: 0.6)
                    false -> maxOf(0.5, prior ?: 0.5)
                    null -> if (prior != null) 1 - prior else if (a.surprise != 0.0) abs(a.surprise) else 0.6
                }
                val s = if (a.source.startsWith("gemini") && a.surprise != 0.0) 0.5 * engineS + 0.5 * abs(a.surprise) else engineS
                val sg = if (a.surprise != 0.0) sign(a.surprise) else dir
                Surprise(M.clamp(s, 0.0, 1.0), sg, "confirmed; prior %s, %s".format(prior?.let { "%.0f%%".format(it * 100) } ?: "unknown",
                    when (a.actualMatchesExpectation) { true -> "as expected"; false -> "differs from expected"; null -> "no stated expectation" }))
            }
            a.stage == EventStage.DEVELOPING || a.stage == EventStage.ESCALATING -> {
                val s = if (a.surprise != 0.0) abs(a.surprise) else 0.3 + 0.4 * a.escalationRisk
                Surprise(M.clamp(s, 0.0, 1.0), if (a.surprise != 0.0) sign(a.surprise) else dir, a.stage.label.lowercase())
            }
            a.stage == EventStage.RESOLVING || a.stage == EventStage.RESOLVED -> {
                val s = if (a.surprise != 0.0) abs(a.surprise) else 0.3
                Surprise(s, if (a.surprise != 0.0) sign(a.surprise) else dir, "resolution")
            }
            else -> { // outcome not yet known: information = change in expected probability
                val pts = exps.filter { !it.probability.isNaN() }
                val now = pts.lastOrNull()?.probability ?: stageProb(a).takeIf { !it.isNaN() } ?: 0.3
                val prev = when {
                    pts.size >= 2 -> pts[pts.size - 2].probability
                    // first sight of a scheduled/consensus event: nothing new, the market already knew
                    pts.firstOrNull()?.stage == EventStage.EXPECTED -> now
                    else -> 0.0
                }
                val d = now - prev
                Surprise(M.clamp(abs(d), 0.05, 1.0), dir * (if (d < 0) -1.0 else 1.0),
                    "expectation %.0f%% → %.0f%%".format(prev * 100, now * 100))
            }
        }
    }

    /** Per-channel moves between two baselines, normalised by the move this information should cause. */
    fun channelReactions(a: EventAnalysis, from: MarketBaseline, to: MarketBaseline, expSign: Double, scale: Double): List<ChannelReaction> {
        val out = ArrayList<ChannelReaction>()
        fun pct(x: Double, y: Double) = if (x.isNaN() || y.isNaN() || x == 0.0) Double.NaN else (y - x) / x * 100
        fun add(name: String, move: Double, expected: Double, sgn: Double, w: Double) {
            if (move.isNaN() || w <= 0) return
            val e = expected.coerceAtLeast(1e-9)
            out += ChannelReaction(name, sgn.toInt(), move, M.clamp(move * sgn / e, -1.5, 1.5), w)
        }
        val s = scale.coerceAtLeast(0.12)
        val rates = MarketChannel.RATES in a.channels || MarketChannel.FLOWS in a.channels
        add("NIFTY", pct(from.nifty, to.nifty), 1.2 * s, expSign, 0.30)
        add("Bank Nifty", pct(from.bank, to.bank), (if (rates) 1.8 else 1.3) * s, expSign, if (rates) 0.22 else 0.12)
        val secMoves = a.affectedSectors.mapNotNull { sec -> pct(from.sectors[sec] ?: Double.NaN, to.sectors[sec] ?: Double.NaN).takeIf { !it.isNaN() } }
        if (secMoves.isNotEmpty()) add("Sectors", secMoves.average(), 1.8 * s, expSign, 0.15)
        val stockMoves = a.affectedStocks.mapNotNull { st -> pct(from.stocks[st] ?: Double.NaN, to.stocks[st] ?: Double.NaN).takeIf { !it.isNaN() } }
        if (stockMoves.isNotEmpty()) add("Heavyweights", stockMoves.average(), 2.5 * s, expSign, 0.10)
        if (!from.adRatio.isNaN() && !to.adRatio.isNaN()) add("Breadth", (to.adRatio - from.adRatio) * 100, 60 * s, expSign, 0.10)
        add("Futures", pct(from.futures, to.futures), 1.2 * s, expSign, 0.05)
        if (!from.pcr.isNaN() && !to.pcr.isNaN()) add("Options PCR", (to.pcr - from.pcr) * 100, 15 * s, expSign, 0.05)
        add("India VIX", pct(from.vix, to.vix), 12 * s, -expSign, 0.10)
        if (a.channels.any { it == MarketChannel.FX || it == MarketChannel.CRUDE || it == MarketChannel.FLOWS || it == MarketChannel.RISK_SENTIMENT })
            add("USDINR", pct(from.usdinr, to.usdinr), 0.4 * s, -expSign, 0.07)
        return out
    }

    private fun derive(ev: TrackedEvent, now: Long, market: MarketBaseline): TrackedEvent {
        val a = ev.analysis!!
        val sev = M.clamp(a.severity, 0.0, 1.0)
        val eventImpact = sev * M.clamp(a.direction)
        val sur = surpriseOf(ev)
        val flags = ArrayList<String>()
        val notes = ArrayList<String>()

        // Repricing since the latest information (what share of the NEW information is already in prices).
        val info = ev.infoBaseline ?: ev.baseline
        val minutes = (now - ev.lastInfoAt) / 60_000.0
        // Directionless events (commentary, neutral facts) have no expected reaction to confirm or contradict.
        val directional = abs(a.direction) >= 0.1
        val chans = if (info != null && directional) channelReactions(a, info, market, sur.sign, sev * abs(a.direction).coerceAtLeast(0.3) * sur.magnitude) else emptyList()
        val w = chans.sumOf { it.weight }
        val agreement = if (w <= 0) 0.0 else chans.sumOf { it.weight * M.clamp(it.alignedRatio) } / w
        val absorbed = if (w <= 0) 0.0 else M.clamp(chans.sumOf { it.weight * it.alignedRatio.coerceAtLeast(0.0) } / w, 0.0, 1.0)
        val niftyMove = chans.firstOrNull { it.channel == "NIFTY" }?.movePct ?: 0.0
        val evaluable = minutes >= 5 && (abs(niftyMove) >= 0.08 || chans.count { abs(it.alignedRatio) >= 0.5 } >= 3)
        val contradicted = evaluable && minutes >= 10 && agreement <= -0.25
        val confirmed = evaluable && agreement >= 0.3

        // Anticipation before the information: drift since the event first appeared (pricing of expectations).
        val anticip = if (directional && ev.baseline != null && info != null && info.t > ev.baseline.t + 60_000L)
            channelReactions(a, ev.baseline, info, sign(eventImpact).takeIf { it != 0.0 } ?: 1.0, sev)
                .let { c -> val cw = c.sumOf { it.weight }; if (cw <= 0) 0.0 else M.clamp(c.sumOf { it.weight * it.alignedRatio } / cw, 0.0, 1.0) }
        else 0.0

        val unpriced = 1 - absorbed
        val pricedTotal = M.clamp(1 - sur.magnitude * unpriced, 0.0, 1.0)
        notes += "Expected beforehand: %.0f%% · new information: %.0f%% (%s)".format((1 - sur.magnitude) * 100, sur.magnitude * 100, sur.note)
        notes += "Absorbed since new info: %.0f%% → unpriced %.0f%%".format(absorbed * 100, unpriced * 100)
        if (anticip > 0.05) notes += "Pre-information drift priced ≈%.0f%% of the full move".format(anticip * 100)

        val srcQ = ev.sources.map { rules.sourceQuality(it) }.average().takeIf { !it.isNaN() } ?: 0.6
        var conf = M.clamp(a.confidence, 0.0, 1.0) * (0.7 + 0.3 * srcQ) * (if (a.source.startsWith("gemini")) 1.0 else 0.8)
        if (contradicted) { conf *= (1 - 0.7 * abs(agreement)).coerceAtLeast(0.1); flags += "MARKET_DISAGREES" }
        else if (confirmed) { conf = min(1.0, conf * 1.1); flags += "MARKET_CONFIRMS" }
        if (directional && pricedTotal >= 0.8) flags += "MOSTLY_PRICED"
        if (directional && sur.magnitude >= 0.5 && minutes < 30) flags += "NEW_INFORMATION"
        if (!directional) flags += "NO_DIRECTION"
        if (!a.source.startsWith("gemini")) flags += "RULES_ONLY"

        val effective = sur.sign * sev * abs(a.direction) * unpriced * sur.magnitude * conf
        val weights = horizonWeights(a)
        val impacts = NewsHorizon.values().associateWith { h ->
            effective * (weights[h] ?: 0.0) * exp(-minutes.coerceAtLeast(0.0) / h.tauMinutes)
        }
        return ev.copy(
            surprise = sur.sign * sur.magnitude, surpriseMagnitude = sur.magnitude,
            pricedIn = pricedTotal, unpriced = unpriced, pricingNotes = notes,
            reaction = ReactionReport(minutes, agreement, contradicted, confirmed, chans),
            newsConfidence = conf, eventImpact = eventImpact, effectiveImpact = effective,
            horizonImpacts = impacts, flags = flags,
        )
    }

    /** Separate impact profile per horizon from duration, persistence, stage and the analyst's hint. */
    fun horizonWeights(a: EventAnalysis): Map<NewsHorizon, Double> {
        val base = when (a.duration) {
            EventDuration.SHORT -> doubleArrayOf(1.0, 0.7, 0.4, 0.1, 0.0)
            EventDuration.MEDIUM -> doubleArrayOf(0.8, 1.0, 0.8, 0.5, 0.2)
            EventDuration.LONG -> doubleArrayOf(0.5, 0.7, 0.8, 1.0, 0.8)
        }
        val p = M.clamp(a.persistence, 0.0, 1.0)
        base[3] *= 0.4 + 0.6 * p; base[4] *= 0.4 + 0.6 * p
        when (a.stage) {
            EventStage.ESCALATING -> { base[3] *= 1 + 0.5 * a.escalationRisk; base[4] *= 1 + 0.5 * a.escalationRisk }
            EventStage.RESOLVED -> { base[3] *= 0.5; base[4] *= 0.3 }
            EventStage.RUMOUR, EventStage.POSSIBLE -> { base[3] *= 0.6; base[4] *= 0.4 }
            else -> {}
        }
        val hs = NewsHorizon.values()
        return hs.mapIndexed { i, h ->
            val hint = a.horizonWeights[h]?.let { M.clamp(it, 0.0, 1.0) }
            h to M.clamp(if (hint == null) base[i] else 0.5 * base[i] + 0.5 * hint, 0.0, 1.5)
        }.toMap()
    }

    private fun isShock(e: TrackedEvent, now: Long): Boolean {
        val a = e.analysis ?: return false
        val fresh = now - e.lastInfoAt <= 60 * 60_000L
        val newUnpriced = e.surpriseMagnitude * e.unpriced
        return fresh && a.severity >= 0.7 && e.newsConfidence >= 0.35 && (
            (a.stage.outcomeKnown && newUnpriced >= 0.3) || (a.severity >= 0.85 && newUnpriced >= 0.3))
    }

    /** Events that need (re-)analysis by the AI analyst: new or with articles newer than the last AI reading. */
    private fun pending(now: Long): List<AnalysisRequest> = events.values.asSequence()
        .filter { ev ->
            val ing = lastIngestAt[ev.id] ?: 0L
            val ai = geminiAnalyzedAt[ev.id] ?: 0L
            val a = ev.analysis
            ing > ai && now - ev.lastInfoAt < 2 * 86_400_000L && a != null &&
                (a.severity >= 0.3 || ev.sources.size >= 2) && !(a.eventType == EventType.MARKET && ev.sources.size < 3)
        }
        .sortedByDescending { (it.analysis?.severity ?: 0.0) * 10 + it.sources.size }
        .take(8)
        .map { ev ->
            AnalysisRequest(ev.id, ev.articleIds.mapNotNull { articles[it] }.sortedByDescending { it.publishedAt }.take(6),
                ev.stage, ev.expectations.lastOrNull(), ev.analysis!!)
        }.toList()

    /** Brief list of active events (id + title + stage) so the analyst can merge duplicates. */
    fun activeBriefs(now: Long): List<Triple<String, String, EventStage>> = events.values
        .filter { now - it.lastInfoAt < 3 * 86_400_000L }.sortedByDescending { it.lastInfoAt }.take(30)
        .map { Triple(it.id, it.title, it.stage) }

    companion object {
        /** Snapshot → market levels used for pricing-in and reaction checks. */
        /**
         * @param usePrevClose build the "previous close" baseline. Which field holds the latest close depends on when the
         * quote was taken (before 09:15 `last` is yesterday's close), so it is resolved per instrument from its timestamp.
         */
        fun baselineFrom(s: com.niftyengine.engine.model.MarketSnapshot, t: Long, usePrevClose: Boolean, wall: Long = s.timestamp): MarketBaseline {
            fun ref(last: Double, prev: Double, asOf: Long) = GiftNiftyEngine.closeReference(last, prev, asOf, wall)
            fun v(d: com.niftyengine.engine.model.InstrumentData?) = d?.let { if (usePrevClose) ref(it.last, it.prevClose, it.asOf) else it.last } ?: Double.NaN
            val chain = s.optionChain
            val pcr = if (usePrevClose || chain == null) Double.NaN else {
                val ce = chain.rows.sumOf { it.call.oi }; val pe = chain.rows.sumOf { it.put.oi }
                if (ce > 0) pe / ce else Double.NaN
            }
            val stocks = s.constituents.filterValues { it.prevClose > 0 }
            val ad = if (usePrevClose || stocks.size < 10) (if (usePrevClose) 0.0 else Double.NaN)
            else (stocks.values.count { it.changePct > 0 } - stocks.values.count { it.changePct < 0 }).toDouble() / stocks.size
            return MarketBaseline(
                t = t, nifty = v(s.nifty), bank = v(s.bankNifty), vix = v(s.vix),
                usdinr = v(s.global[com.niftyengine.engine.model.GlobalAsset.USDINR]),
                futures = s.futures?.let { if (usePrevClose) ref(it.last, it.prevClose, it.asOf) else it.last } ?: Double.NaN,
                pcr = pcr, adRatio = ad,
                sectors = s.sectors.mapValues { (_, d) -> if (usePrevClose) ref(d.last, d.prevClose, d.asOf) else d.last },
                stocks = stocks.mapValues { (_, d) -> if (usePrevClose) ref(d.last, d.prevClose, d.asOf) else d.last },
            )
        }
    }
}

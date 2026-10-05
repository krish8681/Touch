package com.niftyengine.engine.model

import kotlinx.serialization.Serializable

/** Event lifecycle. One EVENT_ID moves through these stages as information develops. */
@Serializable
enum class EventStage(val label: String) {
    RUMOUR("Rumour"), POSSIBLE("Possible"), LIKELY("Likely"), EXPECTED("Expected"), CONFIRMED("Confirmed"),
    DEVELOPING("Developing"), ESCALATING("Escalating"), RESOLVING("Resolving"), RESOLVED("Resolved");

    /** Outcome is a known fact rather than a possibility. */
    val outcomeKnown get() = ordinal >= CONFIRMED.ordinal
}

/** News-impact horizons. Each event gets a separate impact per horizon. */
@Serializable
enum class NewsHorizon(val label: String, val tauMinutes: Double) {
    M5_15("5–15 min", 20.0),
    M30_120("30–120 min", 120.0),
    EOD("EOD", 375.0),
    D1_3("1–3 days", 3 * 1440.0),
    W1_2("1–2 weeks", 14 * 1440.0);

    companion object {
        /** Horizon bucket that drives a direction prediction for [minutes]. */
        fun forMinutes(minutes: Int): NewsHorizon = when {
            minutes <= 20 -> M5_15
            minutes <= 150 -> M30_120
            else -> EOD
        }
    }
}

/** How an event transmits into Indian equities. */
@Serializable
enum class MarketChannel { RATES, FX, CRUDE, FLOWS, EARNINGS, GROWTH, INFLATION, RISK_SENTIMENT, GLOBAL_EQUITY, SECTOR_SPECIFIC }

/**
 * Structured understanding of ONE event (from Gemini, or from the rule engine as a fallback).
 * It describes the news; it never contains trade recommendations.
 */
@Serializable
data class EventAnalysis(
    val eventId: String,
    /** When this analysis became available (point-in-time rule: never used before this instant). */
    val analyzedAt: Long,
    /** "gemini:<model>" or "rules". */
    val source: String,
    val title: String,
    val eventType: EventType,
    val stage: EventStage,
    /** 0..1 — how big the market impact would be if the information were entirely new. */
    val severity: Double,
    /** −1..+1 — sign/strength of the impact on Indian equities of the information as currently known. */
    val direction: Double,
    val affectedSectors: List<Sector> = emptyList(),
    val affectedStocks: List<String> = emptyList(),
    val channels: List<MarketChannel> = emptyList(),
    /** What the market expected before the outcome was known (e.g. "25 bps repo cut"). */
    val expectedOutcome: String = "",
    /** Market-implied/consensus probability of [expectedOutcome] (NaN if unknown). */
    val expectedProbability: Double = Double.NaN,
    val actualOutcome: String = "",
    /** true/false once the outcome is known; null while unknown. */
    val actualMatchesExpectation: Boolean? = null,
    /** −1..+1: actual vs expected, from Indian equities' point of view (+ = better than expected). */
    val surprise: Double = 0.0,
    val duration: EventDuration = EventDuration.MEDIUM,
    /** 0..1 — will the effect persist beyond the session? */
    val persistence: Double = 0.5,
    /** 0..1 — chance the situation gets materially worse/bigger. */
    val escalationRisk: Double = 0.0,
    /** 0..1 — analyst confidence in this reading. */
    val confidence: Double = 0.5,
    /** Optional relative weights per horizon (0..1) suggested by the analyst. */
    val horizonWeights: Map<NewsHorizon, Double> = emptyMap(),
    /** Other active EVENT_IDs that describe the same real-world event. */
    val mergeWith: List<String> = emptyList(),
    val rationale: String = "",
    /**
     * v5: −1..+1 — how much this information moved the market's EXPECTED future for Indian equities versus what was
     * expected before it (0 = nothing new). NaN when the analyst did not say (rules analyst).
     */
    val expectationShift: Double = Double.NaN,
)

@Serializable
data class StageChange(val t: Long, val stage: EventStage, val source: String)

@Serializable
data class ExpectationPoint(
    val t: Long,
    val stage: EventStage,
    val expectedOutcome: String,
    val probability: Double,
    val actualOutcome: String = "",
    val source: String = "",
)

/** Market levels captured when an event (or new information about it) arrived. */
@Serializable
data class MarketBaseline(
    val t: Long,
    val nifty: Double,
    val bank: Double = Double.NaN,
    val vix: Double = Double.NaN,
    val usdinr: Double = Double.NaN,
    val futures: Double = Double.NaN,
    val pcr: Double = Double.NaN,
    /** (advancers − decliners) / n among NIFTY stocks. */
    val adRatio: Double = Double.NaN,
    val sectors: Map<Sector, Double> = emptyMap(),
    val stocks: Map<String, Double> = emptyMap(),
)

@Serializable
data class ChannelReaction(val channel: String, val expectedSign: Int, val movePct: Double, val alignedRatio: Double, val weight: Double)

@Serializable
data class ReactionReport(
    val minutesSinceInfo: Double = 0.0,
    /** −1 (market clearly contradicts) … +1 (clearly confirms). */
    val agreement: Double = 0.0,
    val contradicted: Boolean = false,
    val confirmed: Boolean = false,
    val channels: List<ChannelReaction> = emptyList(),
)

/** One real-world event, tracked across articles, days and lifecycle stages. */
@Serializable
data class TrackedEvent(
    val id: String,
    val title: String,
    val type: EventType,
    val firstSeen: Long,
    val lastInfoAt: Long,
    val articleIds: List<String>,
    val sources: List<String>,
    val tokens: List<String>,
    val stage: EventStage,
    val stageHistory: List<StageChange>,
    val expectations: List<ExpectationPoint>,
    val analysis: EventAnalysis?,
    val baseline: MarketBaseline? = null,
    val infoBaseline: MarketBaseline? = null,
    // ---- derived each cycle
    /** Signed surprise used for impact (+ = better than expected for equities). */
    val surprise: Double = 0.0,
    /** 0..1 share of the information that is new (expectation change / surprise magnitude). */
    val surpriseMagnitude: Double = 0.0,
    val pricedIn: Double = 0.0,
    val unpriced: Double = 1.0,
    val pricingNotes: List<String> = emptyList(),
    val reaction: ReactionReport = ReactionReport(),
    val newsConfidence: Double = 0.0,
    /** severity × direction before surprise/pricing adjustments. */
    val eventImpact: Double = 0.0,
    /** eventImpact × unpriced × surprise × confidence. */
    val effectiveImpact: Double = 0.0,
    val horizonImpacts: Map<NewsHorizon, Double> = emptyMap(),
    val flags: List<String> = emptyList(),
    /** v5.1.3: the founding headline's tokens — the event's identity for matching new articles (never grows). */
    val seedTokens: List<String> = emptyList(),
)

/** Work item for the event analyst (Gemini): an event whose articles changed since it was last analysed. */
@Serializable
data class AnalysisRequest(
    val eventId: String,
    val articles: List<NewsItem>,
    val currentStage: EventStage,
    val previousExpectation: ExpectationPoint?,
    val ruleView: EventAnalysis,
)

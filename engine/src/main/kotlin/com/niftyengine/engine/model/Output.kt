package com.niftyengine.engine.model

import kotlinx.serialization.Serializable

/** Driver categories used by the direction engine (the rows of the weight table). */
@Serializable
enum class Driver(val label: String) {
    PRICE("Market structure"),
    DERIVATIVES("Futures + options"),
    SECTOR("Sector + heavyweights"),
    GLOBAL("Global risk"),
    BREADTH("Breadth"),
    VIX("India VIX"),
    MACRO("INR / crude / rates"),
    FLOWS("FPI / DII"),
    NEWS("News / events"),
}

/**
 * Normalised output of one analysis engine.
 * [score] is in -1..+1 (bearish..bullish), [confidence] 0..1 reflects data quality/coverage.
 */
@Serializable
data class EngineSignal(
    val name: String,
    val score: Double,
    val confidence: Double,
    val tags: List<String> = emptyList(),
    val details: List<Detail> = emptyList(),
) {
    companion object {
        fun unavailable(name: String, why: String) =
            EngineSignal(name, 0.0, 0.0, listOf("NO_DATA"), listOf(Detail("status", why)))
    }
}

@Serializable
data class Detail(val key: String, val value: String)

@Serializable
enum class Regime(val label: String, val bias: Int) {
    STRONG_BULL("Strong Bull Trend", +2),
    BULL_TREND("Bull Trend", +1),
    BULL_SHORT_COVERING("Bull Short Covering", +1),
    RANGE("Neutral / Range", 0),
    BEAR_LONG_UNWINDING("Bear Long Unwinding", -1),
    BEAR_TREND("Bear Trend", -1),
    STRONG_BEAR("Strong Bear Trend", -2),
    EVENT_SHOCK("Event Shock", 0),
    DIVERGENCE("Divergence", 0),
    TRANSITION("Transition", 0);
}

/** Weight table selected by regime (section 21 of the spec). */
@Serializable
enum class RegimeClass { NORMAL, TREND, EVENT, RANGE }

@Serializable
data class RegimeResult(
    val regime: Regime,
    val regimeClass: RegimeClass,
    val reasons: List<String>,
    val tags: List<String>,
)

@Serializable
enum class ConfidenceLevel { LOW, MEDIUM, HIGH }

@Serializable
data class DriverContribution(
    val driver: Driver,
    val score: Double,
    val weight: Double,
    val confidence: Double,
    val persistence: Double,
    val contribution: Double,
)

@Serializable
data class DirectionResult(
    val pBull: Double,
    val pBear: Double,
    val pRange: Double,
    val directionalScore: Double,
    val rangeScore: Double,
    val confidence: ConfidenceLevel,
    val confidenceValue: Double,
    val driverAgreement: Double,
    val conflict: Double,
    val conflictLevel: ConfidenceLevel,
    val priceConfirmation: Boolean,
    val derivativeConfirmation: Boolean,
    val drivers: List<DriverContribution>,
    val conflicts: List<String>,
) {
    val bias: Int get() = when {
        pBull > pBear && pBull > pRange -> 1
        pBear > pBull && pBear > pRange -> -1
        else -> 0
    }
    val topProbability: Double get() = maxOf(pBull, pBear, pRange)
}

@Serializable
data class ExpectedMove(
    val horizonMinutes: Int,
    /** 1-sigma move over the horizon, index points. */
    val sigmaPoints: Double,
    /** Signed central expected move in the favoured direction (points). */
    val expectedMovePoints: Double,
    val moveLow: Double,
    val moveHigh: Double,
    val rangeLow: Double,
    val rangeHigh: Double,
    val annualVolUsed: Double,
    val eventMultiplier: Double,
    val components: List<Detail>,
)

@Serializable
enum class OptionType { CE, PE }

@Serializable
data class OptionCandidate(
    val strike: Double,
    val type: OptionType,
    val premium: Double,
    val bid: Double,
    val ask: Double,
    val iv: Double,
    val delta: Double,
    val gamma: Double,
    val thetaPerDay: Double,
    val vega: Double,
    val oi: Double,
    val volume: Double,
    val spreadPct: Double,
    /** Probability the underlying touches the strike within the horizon (1.0 if already ITM). */
    val probReach: Double,
    /** Probability the option is worth more than its entry premium at the horizon. */
    val probProfit: Double,
    val probItmAtExpiry: Double,
    val breakevenSpot: Double,
    val expectedValue: Double,
    val expectedReturnPct: Double,
    val valueAtTarget: Double,
    val liquidityFactor: Double,
    val ivFactor: Double,
    val thetaFactor: Double,
    val executionFactor: Double,
    val score: Double,
    val moneyness: String,
    val passedFilters: Boolean,
    val filterFailures: List<String>,
)

@Serializable
data class OptionAnalysis(
    val preferred: OptionType?,
    val best: OptionCandidate?,
    val candidates: List<OptionCandidate>,
    val atmIv: Double,
    val daysToExpiry: Double,
    val notes: List<String>,
)

@Serializable
enum class Decision { TRADE, WAIT, NO_TRADE }

@Serializable
data class TradeDecision(
    val decision: Decision,
    val headline: String,
    val reasons: List<String>,
    val checks: List<Check>,
)

@Serializable
data class Check(val name: String, val passed: Boolean, val detail: String)

/** Complete output of one engine cycle. */
@Serializable
data class EngineOutput(
    val timestamp: Long,
    val spot: Double,
    val spotChangePct: Double,
    val signals: Map<String, EngineSignal>,
    val regime: RegimeResult,
    val direction: DirectionResult,
    val expectedMove: ExpectedMove,
    val options: OptionAnalysis,
    val decision: TradeDecision,
    val heavyweights: HeavyweightReport,
    val sectors: List<SectorRow>,
    val events: List<NewsEvent>,
    val dataSource: String,
    val feedStatus: Map<String, String>,
)

@Serializable
data class HeavyweightRow(
    val symbol: String,
    val sector: Sector,
    val weightPct: Double,
    val changePct: Double,
    val contributionPts: Double,
)

@Serializable
data class HeavyweightReport(
    val rows: List<HeavyweightRow>,
    val totalContributionPts: Double,
    val top5Pts: Double,
    val top10Pts: Double,
    val advancers: Int,
    val decliners: Int,
    val heavyweightDependence: Double,
    val fakeBreadth: Boolean,
    val weightsSource: String,
)

@Serializable
data class SectorRow(
    val sector: Sector,
    val changePct: Double,
    val momentum: Double,
    val breadth: Double,
    val relativeStrength: Double,
    val contributionPts: Double,
)

@Serializable
enum class EventType(val label: String) {
    RBI_POLICY("RBI policy"), INFLATION("Inflation"), GROWTH("Growth data"), FED("US Fed"),
    US_DATA("US macro data"), CRUDE("Crude oil"), GEOPOLITICS("Geopolitics"), EARNINGS("Earnings"),
    FLOWS("FPI/DII flows"), GOVERNMENT("Government / policy"), CURRENCY("Currency"),
    CORPORATE("Corporate"), MARKET("Market commentary"), OTHER("Other");
}

@Serializable
enum class EventDuration(val halfLifeMinutes: Double) { SHORT(45.0), MEDIUM(90.0), LONG(240.0) }

@Serializable
data class NewsEvent(
    val clusterId: String,
    val headline: String,
    val type: EventType,
    val sources: List<String>,
    val firstSeen: Long,
    val expected: String,
    val actual: String,
    val surprise: Double,
    val direction: Double,
    val magnitude: Double,
    val sectors: List<Sector>,
    val duration: EventDuration,
    val confidence: Double,
    val sourceQuality: Double,
    val decay: Double,
    /** -1..1 actual market reaction measured in NIFTY since the event (sign relative to market). */
    val marketReaction: Double,
    /** 0..1: does the reaction persist? */
    val reactionPersistence: Double,
    val divergence: Boolean,
    val effectiveImpact: Double,
)

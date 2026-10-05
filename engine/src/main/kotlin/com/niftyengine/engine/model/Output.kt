package com.niftyengine.engine.model

import kotlinx.serialization.Serializable

// =====================================================================================================================
//  Shared building blocks
// =====================================================================================================================

/**
 * Diagnostic output of one analysis engine (shown in the Market / Expiry tabs).
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
enum class ConfidenceLevel { LOW, MEDIUM, HIGH }

@Serializable
enum class Direction(val label: String, val arrow: String, val sign: Int) {
    BULLISH("Bullish", "↑", 1), NEUTRAL("Neutral", "→", 0), BEARISH("Bearish", "↓", -1);
}

// =====================================================================================================================
//  §2 — three horizons
// =====================================================================================================================

@Serializable
enum class HorizonGroup(val label: String, val short: String) {
    H1("H1 — Intraday", "H1"), H2("H2 — Weekly expiry", "H2"), H3("H3 — Monthly expiry", "H3");
}

/** Prediction targets. Intraday ones carry their length in trading minutes; CLOSE/expiry targets are dated. */
@Serializable
enum class HorizonId(val label: String, val short: String, val group: HorizonGroup, val minutes: Int) {
    M30("30 minutes", "30 MIN", HorizonGroup.H1, 30),
    M60("1 hour", "1 HOUR", HorizonGroup.H1, 60),
    M180("3 hours", "3 HOURS", HorizonGroup.H1, 180),
    CLOSE("Today's close", "DAY CLOSE", HorizonGroup.H1, 0),
    WEEKLY("Next weekly expiry", "WEEKLY", HorizonGroup.H2, 0),
    MONTHLY("Next monthly expiry", "MONTHLY", HorizonGroup.H3, 0);

    val intraday: Boolean get() = group == HorizonGroup.H1
}

/**
 * Core-model factors (spec §3–§11). Technical oscillators (RSI, MACD, Bollinger, stochastics, CCI, candlestick
 * patterns) are deliberately NOT factors (§29).
 */
@Serializable
enum class Factor(val label: String) {
    PRICE_STRUCTURE("Price / trend structure"),
    HEAVYWEIGHTS("Heavyweight contribution"),
    OPTIONS("Options OI / positioning"),
    FUTURES("Futures positioning"),
    FII_FUTURES("FII / futures positioning"),
    VIX_IV("India VIX / IV"),
    GLOBAL("Global market"),
    FII("FII / FPI"),
    NEWS("News / events"),
    USDINR("USD/INR"),
    CRUDE("Crude"),
    USDINR_CRUDE("USD/INR + crude"),
    BREADTH("Intraday breadth"),
    RBI_RATES("RBI / liquidity / rates"),
    EARNINGS("Earnings / EPS"),
    SECTOR_LEADERSHIP("Sector leadership"),
    INDIA_MACRO("Indian macro"),
    INDIA_GROWTH("Indian growth"),
    VALUATION("Valuation"),
    INFLATION("Inflation"),
    FISCAL("Fiscal / government policy"),
}

/**
 * One factor's view for one horizon (spec §27): direction −100..+100, strength/freshness/reliability 0..1.
 * A factor that is [available] = false is removed from the horizon and the remaining weights are renormalised (§31).
 */
@Serializable
data class FactorReading(
    val factor: Factor,
    val available: Boolean,
    val direction: Double = 0.0,
    val strength: Double = 0.0,
    val freshness: Double = 0.0,
    val reliability: Double = 0.0,
    /** Timestamp of the newest input behind the reading (epoch ms, 0 = unknown). */
    val asOf: Long = 0L,
    val ageSec: Double = Double.NaN,
    val source: String = "",
    val summary: String = "",
    val details: List<Detail> = emptyList(),
) {
    companion object {
        fun missing(f: Factor, why: String) = FactorReading(f, false, summary = why)
    }
}

@Serializable
data class FactorContribution(
    val factor: Factor,
    /** Spec base weight (%). */
    val baseWeight: Double,
    /** After bounded regime adaptation (§28), before missing-data renormalisation. */
    val adjustedWeight: Double,
    /** Weight actually used (%): renormalised over available factors; 0 when missing. */
    val usedWeight: Double,
    val reading: FactorReading,
    /** direction × strength × freshness × reliability × usedWeight / 100 (−100..+100 scale contribution). */
    val effective: Double,
)

/** One log-normal component of the expiry/horizon price distribution (log-return mean and s.d.). */
@Serializable
data class DistComponent(val weight: Double, val mu: Double, val sigma: Double)

@Serializable
data class DistributionBucket(val low: Double, val high: Double, val label: String, val p: Double)

/** Intermediate checkpoint on the way to an expiry (H2: +1…+5 days, H3: every 5 trading days). */
@Serializable
data class PathPoint(
    val label: String, val t: Long, val median: Double, val low: Double, val high: Double,
    val pBull: Double, val pBear: Double,
)

@Serializable
data class HorizonPrediction(
    val id: HorizonId,
    /** Target instant (epoch ms). */
    val targetTime: Long,
    /** Trading minutes until the target (for σ). */
    val tradingMinutes: Double,
    val spot: Double,
    /** Σ effective factor scores, −100..+100 (§27). */
    val score: Double,
    /** Share (0..1) of the regime-adjusted weight whose data was available. */
    val coverage: Double,
    val factors: List<FactorContribution>,
    val missing: List<String>,
    /** Bounded extra term (H3 monthly options structure) — never allowed to dominate the fundamental model. */
    val overlay: Double = 0.0,
    val overlayNote: String = "",
    // ---- expected move / distribution (§18, §19)
    val sigmaPts: Double,
    val driftPts: Double,
    /** Neutral band half-width (points): |move| ≤ band ⇒ Neutral. */
    val neutralBand: Double,
    val annualVolUsed: Double,
    val volSources: List<Detail>,
    val components: List<DistComponent>,
    // ---- direction probability (§20): raw model score and calibrated (when enough outcomes)
    val pBull: Double,
    val pNeutral: Double,
    val pBear: Double,
    val calibrated: Boolean,
    val calPBull: Double,
    val calPNeutral: Double,
    val calPBear: Double,
    val direction: Direction,
    /** Probability of [direction] (calibrated when available, otherwise model score). */
    val probability: Double,
    val expectedPrice: Double,
    /** ~68 % range (16th–84th percentile). */
    val rangeLow: Double,
    val rangeHigh: Double,
    /** ~90 % range (5th–95th percentile). */
    val range90Low: Double,
    val range90High: Double,
    val buckets: List<DistributionBucket>,
    val path: List<PathPoint> = emptyList(),
    // ---- §21 confidence (separate from probability)
    val confidence: ConfidenceLevel = ConfidenceLevel.LOW,
    val confidenceValue: Double = 0.0,
    val confidenceNotes: List<String> = emptyList(),
    val eventRisk: EventRiskLevel = EventRiskLevel.LOW,
    // ---- expiry horizons
    val expiry: String = "",
    val daysToExpiry: Double = Double.NaN,
    val support: Double = Double.NaN,
    val resistance: Double = Double.NaN,
    val pinStrike: Double = Double.NaN,
    val pinWeight: Double = 0.0,
    /** Note when the target had to be adapted (e.g. "3 h capped at today's close", "next session"). */
    val note: String = "",
) {
    /** Probabilities used for decisions: calibrated when available. */
    val bull: Double get() = if (calibrated) calPBull else pBull
    val bear: Double get() = if (calibrated) calPBear else pBear
    val neutral: Double get() = if (calibrated) calPNeutral else pNeutral
}

// =====================================================================================================================
//  §13 market regime, §14 expiry regime
// =====================================================================================================================

@Serializable
enum class MarketRegime(val label: String, val bias: Int) {
    RISK_ON("Risk-on", 1), RISK_OFF("Risk-off", -1), DOMESTIC_BULLISH("Domestic bullish", 1),
    EARNINGS_EXPANSION("Earnings expansion", 1), EVENT_SHOCK("Event shock", 0), RANGE_COMPRESSION("Range / compression", 0),
    MIXED("Mixed — no dominant regime", 0);
}

@Serializable
data class RegimeEvidence(val regime: MarketRegime, val score: Double, val met: List<String>, val missed: List<String>)

@Serializable
data class RegimeReport(
    val regime: MarketRegime,
    val score: Double,
    val secondary: MarketRegime? = null,
    val evidence: List<RegimeEvidence>,
    /** Factor weight changes applied for this regime (within min/max bounds, §28). */
    val weightAdjustments: List<Detail> = emptyList(),
)

@Serializable
enum class ExpiryRegime(val label: String) {
    RANGE_PIN("Range / pin"), BULLISH_EXPANSION("Bullish expansion"), BEARISH_EXPANSION("Bearish expansion"),
    VOLATILITY_EXPANSION("Volatility expansion"), UNDEFINED("No clear expiry regime");
}

// =====================================================================================================================
//  §9, §11, §17 expiry / options intelligence
// =====================================================================================================================

@Serializable
enum class ExpiryKind(val label: String) { WEEKLY("Weekly"), MONTHLY("Monthly") }

@Serializable
data class OiLevel(val strike: Double, val strength: Double, val oi: Double, val oiChange: Double, val note: String = "")

@Serializable
data class PinZone(val strike: Double, val low: Double, val high: Double, val strength: Double, val reasons: List<String>)

@Serializable
data class Breakout(
    val upLevel: Double, val downLevel: Double,
    /** Probability (model distribution, adjusted for wall strength) of finishing above [upLevel] / below [downLevel]. */
    val pUp: Double, val pDown: Double, val pInside: Double,
    val note: String = "",
)

@Serializable
data class StrikeOi(
    val strike: Double, val callOi: Double, val callChange: Double, val putOi: Double, val putChange: Double,
    val callIv: Double, val putIv: Double, val callLtp: Double, val putLtp: Double,
)

@Serializable
data class ExpiryIntel(
    val kind: ExpiryKind,
    val expiry: String,
    val expiryMillis: Long,
    val daysToExpiry: Double,
    val tradingDaysToExpiry: Int,
    val spot: Double,
    val atmStrike: Double,
    val atmIv: Double,
    /** ~2 % OTM call / put IV and skew (put − call, vol points). */
    val callIv: Double,
    val putIv: Double,
    val skew: Double,
    /** ATM IV change since the first chain seen today (vol points). */
    val ivChange: Double,
    val straddle: Double,
    /** 1σ move to expiry from ATM IV, and from the ATM straddle (straddle ≈ 0.8σ). */
    val expectedMoveIv: Double,
    val expectedMoveStraddle: Double,
    val pcr: Double,
    val pcrChange: Double,
    val totalCallOi: Double,
    val totalPutOi: Double,
    val callWriting: Double,
    val callUnwinding: Double,
    val putWriting: Double,
    val putUnwinding: Double,
    val support: OiLevel?,
    val majorSupport: OiLevel?,
    val resistance: OiLevel?,
    val majorResistance: OiLevel?,
    val pin: PinZone?,
    val maxPain: Double,
    /** Share of near-ATM OI sitting in the top 3 strikes (0..1). */
    val oiConcentration: Double,
    val breakout: Breakout? = null,
    val regime: ExpiryRegime = ExpiryRegime.UNDEFINED,
    val regimeReasons: List<String> = emptyList(),
    val strikes: List<StrikeOi> = emptyList(),
    val signal: EngineSignal,
    val notes: List<String> = emptyList(),
)

// =====================================================================================================================
//  §12 expectation engine, §16 FII engine, §15 heavyweights, §23 event risk
// =====================================================================================================================

@Serializable
enum class ExpectationChannel(val label: String) {
    RBI_RATES("RBI / rates"), EARNINGS("Earnings"), GROWTH("Growth"), INFLATION("Inflation"), FISCAL("Fiscal / government"),
    FLOWS("FII / flows"), CRUDE("Crude"), CURRENCY("Currency"), GLOBAL("Global"), GEOPOLITICS("Geopolitics"), OTHER("Other");
}

@Serializable
data class ExpectationRecord(
    val id: String,
    val title: String,
    val channel: ExpectationChannel,
    val source: String,
    val expected: String,
    val actual: String,
    /** −1..+1 from Indian equities' point of view (+ = better than expected). */
    val surprise: Double,
    val surpriseLabel: String,
    val interpretation: Direction,
    val persistence: String,
    val persistenceValue: Double,
    val pricedIn: Double,
    /** Signed impact per horizon group after decay (−1..1). */
    val impact: Map<HorizonGroup, Double>,
    val asOf: Long,
)

@Serializable
data class ExpectationReport(
    val records: List<ExpectationRecord> = emptyList(),
    /** Aggregate impact per channel and horizon group (squashed, −1..1). */
    val channels: Map<ExpectationChannel, Map<HorizonGroup, Double>> = emptyMap(),
)

@Serializable
enum class FiiRegime(val label: String, val sign: Int) {
    STRONG_BULLISH("Strong bullish", 2), BULLISH("Bullish", 1), NEUTRAL("Neutral", 0), BEARISH("Bearish", -1), STRONG_BEARISH("Strong bearish", -2);
}

@Serializable
data class FiiReport(
    val available: Boolean,
    val regime: FiiRegime,
    /** Positioning score −100..+100 (weekly view). */
    val score: Double,
    val cashLatestCr: Double = Double.NaN,
    val cash5dCr: Double = Double.NaN,
    val cash20dCr: Double = Double.NaN,
    val diiLatestCr: Double = Double.NaN,
    val dii5dCr: Double = Double.NaN,
    val futLong: Double = Double.NaN,
    val futShort: Double = Double.NaN,
    val futLongPct: Double = Double.NaN,
    val futLongPctChange1d: Double = Double.NaN,
    val futLongPctChange5d: Double = Double.NaN,
    val optionsNet: Double = Double.NaN,
    val optionsNetChange1d: Double = Double.NaN,
    val date: String = "",
    val tags: List<String> = emptyList(),
    val details: List<Detail> = emptyList(),
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
data class LeadershipRow(val group: String, val contributionPts: Double, val changePct: Double, val weightPct: Double)

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
    /** Contribution by leadership group (Banking, IT, Energy, Auto, Pharma, FMCG, Telecom, Capital goods …). */
    val leadership: List<LeadershipRow> = emptyList(),
    /** Points contributed over the last 30 / 60 minutes. */
    val contribution30mPts: Double = Double.NaN,
    val contribution60mPts: Double = Double.NaN,
    /** True when a few stocks carry the index (top-3 share of |move| ≥ 70 %). */
    val narrow: Boolean = false,
)

@Serializable
data class SectorRow(
    val sector: Sector,
    val changePct: Double,
    val momentum: Double,
    val breadth: Double,
    val relativeStrength: Double,
    val contributionPts: Double,
    val change5dPct: Double = Double.NaN,
    val change20dPct: Double = Double.NaN,
)

@Serializable
enum class EventRiskLevel(val label: String, val penalty: Double) {
    LOW("Low", 0.0), MEDIUM("Medium", 0.05), HIGH("High", 0.12), EXTREME("Extreme", 0.25);
}

@Serializable
data class UpcomingEvent(
    val date: String, val title: String, val category: CalendarCategory, val importance: Int, val source: String,
    val daysAway: Double, val windows: List<HorizonGroup>,
)

@Serializable
data class EventRiskReport(
    val levels: Map<HorizonGroup, EventRiskLevel> = emptyMap(),
    val points: Map<HorizonGroup, Double> = emptyMap(),
    val upcoming: List<UpcomingEvent> = emptyList(),
    val notes: List<String> = emptyList(),
) {
    fun level(g: HorizonGroup) = levels[g] ?: EventRiskLevel.LOW
}

// =====================================================================================================================
//  §21/§22 master prediction, alignment, confidence
// =====================================================================================================================

@Serializable
data class MasterPrediction(
    val direction: Direction,
    val probability: Double,
    val rangeLow: Double,
    val rangeHigh: Double,
    /** Number of horizons (of 3) agreeing with [direction]. */
    val alignment: Int,
    val alignmentLabel: String,
    val horizonDirections: Map<HorizonGroup, Direction>,
    val horizonProbabilities: Map<HorizonGroup, Double>,
    val confidence: ConfidenceLevel,
    val confidenceValue: Double,
    val notes: List<String>,
    val eventRisk: EventRiskLevel,
)

// =====================================================================================================================
//  §25/§26 options valuation → strategy → risk
// =====================================================================================================================

@Serializable
enum class OptionType { CE, PE }

@Serializable
enum class OptionValueClass(val label: String) {
    ATTRACTIVE("Potentially attractive"), FAIR("Reasonably priced"), TOO_EXPENSIVE("Too expensive"),
    TOO_FAR_OTM("Too far OTM"), EXCESSIVE_THETA("Excessive theta");
}

@Serializable
data class OptionValuation(
    val strike: Double,
    val type: OptionType,
    val ask: Double,
    val mid: Double,
    val iv: Double,
    /** Model value: expected payoff at expiry under the horizon distribution (discounted). */
    val fairValue: Double,
    /** (fair − ask) / ask. */
    val edgePct: Double,
    val pItm: Double,
    /** P(payoff at expiry > ask + costs). */
    val pProfit: Double,
    val delta: Double,
    val thetaPerDay: Double,
    /** |θ|/premium per day. */
    val thetaShare: Double,
    val oi: Double,
    val volume: Double,
    val spreadPct: Double,
    val cls: OptionValueClass,
)

@Serializable
data class ValuationSummary(
    val kind: ExpiryKind,
    val expiry: String,
    val atmIv: Double,
    /** Model forecast of realised vol to expiry (annualised %). */
    val forecastVol: Double,
    /** ATM IV ÷ forecast vol. */
    val ivRatio: Double,
    val ivView: String,
    val rows: List<OptionValuation>,
)

@Serializable
enum class StrategyKind(val label: String, val directional: Int) {
    LONG_CE("Long call (CE)", 1), LONG_PE("Long put (PE)", -1),
    BULL_CALL_SPREAD("Bull call spread", 1), BEAR_PUT_SPREAD("Bear put spread", -1),
    BULL_PUT_SPREAD("Bull put spread (credit)", 1), BEAR_CALL_SPREAD("Bear call spread (credit)", -1),
    IRON_CONDOR("Iron condor (defined risk)", 0), IRON_BUTTERFLY("Iron butterfly (defined risk)", 0),
    LONG_STRADDLE("Long straddle", 0);
}

@Serializable
enum class TradeCategory(val label: String) {
    STRONG_BULLISH("Strong bullish"), MILD_BULLISH("Mild bullish / range"), NEUTRAL_HIGH_IV("Neutral + high IV"),
    NEUTRAL_VOL_EXPANSION("Neutral + volatility expansion"), NEUTRAL("Neutral"), MILD_BEARISH("Mild bearish / range"),
    STRONG_BEARISH("Strong bearish");
}

@Serializable
enum class LegAction { BUY, SELL }

@Serializable
data class StrategyLeg(
    val action: LegAction,
    val type: OptionType,
    val strike: Double,
    /** Fill price used: ask for buys, bid for sells (conservative). */
    val price: Double,
    val iv: Double,
    val delta: Double,
    val oi: Double,
    val volume: Double,
    val spreadPct: Double,
    val lastTradeAgeSec: Double = Double.NaN,
)

@Serializable
data class StrategyCandidate(
    val kind: StrategyKind,
    val expiryKind: ExpiryKind,
    val expiry: String,
    /** Horizon whose distribution prices the strategy (intraday trades exit at the H1 horizon, others hold to expiry). */
    val horizon: HorizonId,
    val legs: List<StrategyLeg>,
    /** Per unit: + debit paid, − credit received. */
    val netPremium: Double,
    /** Per unit; NaN = unlimited (long single options / straddles). */
    val maxProfit: Double,
    /** Per unit, positive number (worst case incl. costs). */
    val maxLoss: Double,
    val breakevens: List<Double>,
    val pProfit: Double,
    /** Calibrated P(profit) from realised strategy outcomes (NaN until enough samples). */
    val pProfitCalibrated: Double = Double.NaN,
    val expectedPnl: Double,
    val expectedPnlGross: Double,
    val costPerUnit: Double,
    /** maxProfit / maxLoss. */
    val riskReward: Double,
    /** expectedPnl / maxLoss. */
    val returnOnRisk: Double,
    val netDelta: Double,
    val netThetaPerDay: Double,
    val netVega: Double,
    val liquidity: Double,
    /** How well the structure fits the predicted category (0..1). */
    val fit: Double,
    val score: Double,
    val passedFilters: Boolean,
    val failures: List<String>,
    val rationale: String,
) {
    val title: String get() = "${kind.label} · " + legs.joinToString(" / ") { "${if (it.action == LegAction.BUY) "+" else "−"}${it.strike.toInt()}${it.type}" }
}

@Serializable
data class StrategyReport(
    val intradayCategory: TradeCategory? = null,
    val weeklyCategory: TradeCategory? = null,
    val monthlyCategory: TradeCategory? = null,
    val candidates: List<StrategyCandidate> = emptyList(),
    val best: StrategyCandidate? = null,
    val notes: List<String> = emptyList(),
)

@Serializable
enum class Decision {
    TRADE,
    /** Every check passes except probability calibration: log it, paper-trade it, don't risk money. */
    PAPER_TRADE,
    WAIT,
    NO_TRADE,
    /** Circuit breaker: required data missing, stale or invalid. */
    DATA_ERROR,
}

@Serializable
data class Check(val name: String, val passed: Boolean, val detail: String)

@Serializable
data class TradeDecision(
    val decision: Decision,
    val headline: String,
    val reasons: List<String>,
    val checks: List<Check>,
    val candidate: StrategyCandidate? = null,
    val lots: Int = 0,
    val maxLossRupees: Double = Double.NaN,
    val expectedPnlRupees: Double = Double.NaN,
)

// =====================================================================================================================
//  Calibration, data quality, GIFT, news (kept from v3.2/v4)
// =====================================================================================================================

@Serializable
data class CalibrationInfo(
    val calibrated: Boolean = false,
    /** Evaluated samples per horizon behind the fit (key = HorizonId.name). */
    val samples: Map<String, Int> = emptyMap(),
    val calibratedHorizons: List<String> = emptyList(),
    val minSamples: Int = 150,
    /** Walk-forward Brier score on the hold-out slice: raw vs calibrated, per horizon. */
    val holdoutBrierRaw: Map<String, Double> = emptyMap(),
    val holdoutBrierCalibrated: Map<String, Double> = emptyMap(),
    val strategySamples: Int = 0,
    val fittedAt: Long = 0L,
    val note: String = "Uncalibrated: probabilities are model scores until enough outcomes are logged",
) {
    fun isCalibrated(h: HorizonId) = h.name in calibratedHorizons
}

@Serializable
enum class GapState(val label: String) {
    PRE_OPEN("Pre-open"), EXTENDING("Gap extending"), HOLDING("Gap holding"), FADING("Gap fading"),
    FILLED("Gap filled"), NO_GAP("No meaningful gap"), SPENT("Opening factor spent");
}

@Serializable
data class GiftNiftyReport(
    val last: Double,
    val changePct: Double,
    val asOf: Long,
    val ageMinutes: Double,
    /** Expected NIFTY opening gap implied by GIFT vs the NSE futures close (same contract). */
    val impliedGapPct: Double,
    val impliedOpen: Double,
    val method: String,
    val state: GapState,
    val actualGapPct: Double = Double.NaN,
    /** actual gap ÷ implied gap (1 = opened exactly where GIFT implied). */
    val gapRealization: Double = Double.NaN,
    /** Share of the actual gap given back since the open (≥1 = filled). */
    val retracement: Double = Double.NaN,
    val notes: List<String> = emptyList(),
)

@Serializable
enum class FeedStatus { LIVE, DEGRADED, STALE, INVALID, MISSING, MANUAL }

@Serializable
data class FeedQuality(
    val name: String,
    val status: FeedStatus,
    val source: String,
    /** Source timestamp (epoch ms), 0 if unknown. */
    val asOf: Long,
    val ageSeconds: Double,
    /** Required for any trade recommendation. */
    val critical: Boolean,
    val detail: String = "",
)

@Serializable
data class DataQualityReport(
    val feeds: List<FeedQuality> = emptyList(),
    /** 0..1 weighted share of usable inputs. */
    val score: Double = 1.0,
    /** Non-empty ⇒ circuit breaker tripped (DATA ERROR — NO TRADE). */
    val circuitBreaker: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
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

// =====================================================================================================================
//  Complete output of one engine cycle
// =====================================================================================================================

@Serializable
data class EngineOutput(
    val timestamp: Long,
    val spot: Double,
    val spotChangePct: Double,
    val marketOpen: Boolean,
    val regime: RegimeReport,
    val weekly: ExpiryIntel?,
    val monthly: ExpiryIntel?,
    /** M30, M60, M180, CLOSE, WEEKLY, MONTHLY (in that order). */
    val horizons: List<HorizonPrediction>,
    val master: MasterPrediction,
    val fii: FiiReport,
    val heavyweights: HeavyweightReport,
    val sectors: List<SectorRow>,
    val expectations: ExpectationReport,
    val eventRisk: EventRiskReport,
    val valuation: List<ValuationSummary>,
    val strategies: StrategyReport,
    val decision: TradeDecision,
    /** Diagnostic engine panels (price structure, futures, VIX, global, macro, breadth, GIFT …). */
    val signals: Map<String, EngineSignal>,
    val events: List<TrackedEvent>,
    val dataSource: String,
    val feedStatus: Map<String, String>,
    val dataQuality: DataQualityReport = DataQualityReport(),
    val calibration: CalibrationInfo = CalibrationInfo(),
    val engineVersion: String = "",
    /** Aggregate news impact per horizon (−1..1). */
    val newsHorizons: Map<NewsHorizon, Double> = emptyMap(),
    /** Events waiting for (re-)analysis by the AI analyst. */
    val pendingEventAnalysis: List<AnalysisRequest> = emptyList(),
    val gift: GiftNiftyReport? = null,
) {
    fun horizon(id: HorizonId): HorizonPrediction? = horizons.firstOrNull { it.id == id }
    fun expiry(kind: ExpiryKind): ExpiryIntel? = if (kind == ExpiryKind.WEEKLY) weekly else monthly
}

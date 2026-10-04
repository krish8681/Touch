package com.niftyengine.engine.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * v5 decision pipeline:
 *   data → NORMALIZED STATE → REGIME → WHAT THE MARKET EXPECTS → WHAT CHANGED (information shock)
 *        → FUTURE SCENARIOS → DIRECTION PROBABILITY → CALIBRATED PROBABILITY → TRADE QUALITY
 *        → OPTION → STRATEGY → RISK → EXECUTION (shadow) → OUTCOME → CALIBRATION
 */

// ------------------------------------------------------------------ data normalization (relative state)

/** One input expressed relative to its own normal ("PCR 1.12 vs 20-day normal 0.94 → +19 %"). */
@Serializable
data class RelativeReading(
    val name: String,
    val value: Double,
    val normal: Double,
    /** value ÷ normal − 1 for level readings; for move readings the signed move in units of the normal move. */
    val relative: Double,
    /** |move| ÷ normal |move| ("4× normal"); NaN for level readings. */
    val multiple: Double = Double.NaN,
    /** Where the normal comes from: "20-day normal (n days)", "VIX proxy", "fair carry", "static prior". */
    val basis: String = "",
    val display: String = "",
)

@Serializable
data class NormalizedState(
    val readings: List<RelativeReading> = emptyList(),
    /** Completed sessions behind the rolling baselines (0 = priors only). */
    val daysOfHistory: Int = 0,
) {
    fun reading(name: String): RelativeReading? = readings.firstOrNull { it.name == name }
}

// ------------------------------------------------------------------ future expectation

@Serializable
enum class ExpectationState(val label: String) {
    CONFIRMING("Expectation confirms the move"),
    IMPROVING("Expectation improving"),
    DETERIORATING("Expectation deteriorating"),
    BULL_REVERSAL_WARNING("Early reversal warning — price rising, future expectation deteriorating"),
    BEAR_REVERSAL_WARNING("Early reversal warning — price falling, future expectation improving"),
    NEUTRAL("No clear expectation"),
}

@Serializable
data class ExpectationComponent(val name: String, val score: Double, val weight: Double, val confidence: Double, val note: String = "")

/** An event whose outcome is not yet known — what the market expects and how that expectation is moving. */
@Serializable
data class PendingExpectation(
    val eventId: String,
    val title: String,
    val stage: EventStage,
    val expectedOutcome: String,
    val probability: Double,
    /** Change in the expected probability since the previous reading (NaN if only one reading). */
    val probabilityChange: Double,
    val severity: Double,
    val direction: Double,
)

@Serializable
data class FutureExpectation(
    /** −1..1 what the market is doing now (price structure, breadth, sectors). */
    val current: Double = 0.0,
    /** −1..1 what the market is pricing for the near future (events, options, basis, global futures, AI reading). */
    val expected: Double = 0.0,
    /** Change in [expected] over the last ~30 min. */
    val change: Double = 0.0,
    /** Change over the last ~10 min. */
    val changeShort: Double = 0.0,
    /** expected − current. */
    val divergence: Double = 0.0,
    val state: ExpectationState = ExpectationState.NEUTRAL,
    val confidence: Double = 0.0,
    /** 0..1 — size of high-severity scheduled events still ahead (two-sided risk, not a direction). */
    val eventRiskAhead: Double = 0.0,
    val components: List<ExpectationComponent> = emptyList(),
    val pending: List<PendingExpectation> = emptyList(),
    val notes: List<String> = emptyList(),
)

// ------------------------------------------------------------------ information shock

@Serializable
enum class ShockLevel { NONE, MINOR, SIGNIFICANT, MAJOR }

@Serializable
enum class ShockKind(val label: String) {
    EVENT_SURPRISE("Event surprise"), GLOBAL("Global markets"), VOLATILITY("Volatility"), PRICE("NIFTY move"),
    GAP("Opening gap"), EXPECTATION("Expectation repricing"),
}

@Serializable
data class ShockSource(
    val kind: ShockKind,
    val name: String,
    /** 0..1 contribution to the shock score. */
    val magnitude: Double,
    /** Observed ÷ normal ("4.0× normal"); NaN when not a move ratio. */
    val multiple: Double,
    val direction: Double,
    val detail: String,
)

/** What the market expected vs what actually happened — measured on events and on market data. */
@Serializable
data class InformationShock(
    /** 0..1 — probabilistic OR of every source. */
    val score: Double = 0.0,
    /** −1..1 — weighted direction of the shock for Indian equities. */
    val direction: Double = 0.0,
    val level: ShockLevel = ShockLevel.NONE,
    val sources: List<ShockSource> = emptyList(),
    val headline: String = "No information shock",
) {
    val eventDriven: Boolean get() = sources.any { it.kind == ShockKind.EVENT_SURPRISE && it.magnitude >= 0.25 }
    val marketDriven: Boolean get() = sources.any { it.kind != ShockKind.EVENT_SURPRISE && it.kind != ShockKind.EXPECTATION && it.magnitude >= 0.25 }
}

// ------------------------------------------------------------------ regime (v5 primary classification)

@Serializable
enum class PrimaryRegime(val label: String, val bias: Int) {
    TREND_UP("Trend up", +1),
    TREND_DOWN("Trend down", -1),
    RANGE("Range", 0),
    VOLATILITY_EXPANSION("Volatility expansion", 0),
    EVENT_DRIVEN("Event-driven", 0),
    REVERSAL_RISK("Reversal risk", 0),
    CONFLICT("Conflict — wait", 0),
}

/** One information block's opinion (NIFTY, global, options, news, breadth, expectation). */
@Serializable
data class BlockView(val block: String, val score: Double, val confidence: Double, val sign: Int)

@Serializable
data class RegimeAssessment(
    val primary: PrimaryRegime = PrimaryRegime.RANGE,
    /** 0..1 how clean / tradeable the regime is. */
    val quality: Double = 0.0,
    /** Share of recent cycles with the same primary regime. */
    val stability: Double = 0.0,
    /** Weight table the direction engine used. */
    val weightTable: RegimeClass = RegimeClass.NORMAL,
    val blocks: List<BlockView> = emptyList(),
    /** Share of opinionated weight on the losing side (0 = unanimous, 0.5 = split). */
    val blockConflict: Double = 0.0,
    /** For REVERSAL_RISK: the trend that is at risk (+1 uptrend, −1 downtrend, 0 unknown). */
    val reversalSide: Int = 0,
    val reasons: List<String> = emptyList(),
)

// ------------------------------------------------------------------ scenarios

@Serializable
enum class Scenario { STRONG_UP, MILD_UP, RANGE, MILD_DOWN, STRONG_DOWN }

@Serializable
data class ScenarioProb(
    val scenario: Scenario,
    /** Context label: "Bull breakout", "Bull continuation"/"Bull reversal", "Range", "Bear reversal"/"Bear continuation", "Sharp decline". */
    val label: String,
    /** snake_case label used in the decision object ("bull_continuation"). */
    val key: String,
    val probability: Double,
    /** Before scenario calibration. */
    val rawProbability: Double,
    /** Move bucket in points (−∞/+∞ for the tails). */
    val moveFrom: Double,
    val moveTo: Double,
    /** Conditional mean move inside the bucket (points). */
    val meanMove: Double,
)

@Serializable
data class ScenarioSet(
    val horizonMinutes: Int = 0,
    val spot: Double = 0.0,
    val scenarios: List<ScenarioProb> = emptyList(),
    /** |move| below this = Range (same threshold the outcome logger uses). */
    val rangeBand: Double = 0.0,
    /** |move| beyond this = breakout / sharp decline. */
    val breakoutBand: Double = 0.0,
    /** Centre and width of the in-bucket move distribution (points). */
    val center: Double = 0.0,
    val sigma: Double = 0.0,
    /** P(breakout | up move), P(sharp decline | down move). */
    val breakoutShareUp: Double = 0.0,
    val breakoutShareDown: Double = 0.0,
    /** NONE / VIA_DIRECTION / PARTIAL / FULL. */
    val calibration: String = "NONE",
    val notes: List<String> = emptyList(),
) {
    fun p(s: Scenario): Double = scenarios.firstOrNull { it.scenario == s }?.probability ?: 0.0
    val pUp: Double get() = p(Scenario.STRONG_UP) + p(Scenario.MILD_UP)
    val pDown: Double get() = p(Scenario.STRONG_DOWN) + p(Scenario.MILD_DOWN)
    val dominant: ScenarioProb? get() = scenarios.maxByOrNull { it.probability }
}

// ------------------------------------------------------------------ trade quality

@Serializable
enum class QualityTier { HIGH, MEDIUM, LOW, NONE }

@Serializable
data class QualityComponent(val name: String, val value: Double, val floor: Double, val detail: String)

/** Probability × confidence × regime quality × liquidity × risk/reward — a trade needs all of them, not just a high probability. */
@Serializable
data class TradeQuality(
    val score: Double = 0.0,
    val tier: QualityTier = QualityTier.NONE,
    val components: List<QualityComponent> = emptyList(),
    /** Multiplier from the strategy's own shadow track record (1.0 until enough trades). */
    val trackRecord: Double = 1.0,
    val passed: Boolean = false,
    val weakest: String = "",
    val notes: List<String> = emptyList(),
)

// ------------------------------------------------------------------ strategy

@Serializable
enum class StrategyType(val label: String, val directional: Int, val credit: Boolean) {
    BUY_CALL("Buy call", +1, false),
    BULL_CALL_SPREAD("Bull call spread", +1, false),
    BUY_PUT("Buy put", -1, false),
    BEAR_PUT_SPREAD("Bear put spread", -1, false),
    IRON_CONDOR("Iron condor (defined-risk option selling)", 0, true),
    NO_TRADE("No trade", 0, false),
}

@Serializable
enum class LegAction { BUY, SELL }

@Serializable
data class StrategyLeg(
    val action: LegAction,
    val type: OptionType,
    val strike: Double,
    val expiry: String,
    /** Fill price: ask for a buy, bid for a sell. */
    val price: Double,
    val bid: Double,
    val ask: Double,
    val iv: Double,
    val delta: Double,
    val spreadPct: Double,
    val oi: Double,
    val volume: Double,
)

@Serializable
data class StrategyCandidate(
    val type: StrategyType,
    val legs: List<StrategyLeg>,
    /** Net entry value per unit: + debit paid, − credit received. */
    val netPremium: Double,
    /** At expiry, per unit (NaN = unlimited). */
    val maxProfit: Double,
    val maxLoss: Double,
    val breakevens: List<Double>,
    /** P(net P&L > 0 at the horizon) from the scenario distribution. */
    val probProfit: Double,
    /** Expected net P&L per unit at the horizon (after costs). */
    val expectedValue: Double,
    val grossExpectedValue: Double,
    val costPerUnit: Double,
    /** expectedValue ÷ maxLoss. */
    val returnOnRisk: Double,
    /** E[gain | gain] ÷ E[loss | loss] at the horizon. */
    val riskReward: Double,
    /** 0..1 worst leg's liquidity/execution quality. */
    val liquidity: Double,
    val feasible: Boolean,
    val issues: List<String> = emptyList(),
    /** Minutes the structure is valued/held for: the decision horizon, or until the time exit for credit structures. */
    val holdMinutes: Int = 0,
) {
    val instrument: String get() = when (type) {
        StrategyType.NO_TRADE -> "–"
        StrategyType.IRON_CONDOR -> legs.filter { it.action == LegAction.SELL }.sortedBy { it.strike }
            .joinToString("/") { "${it.strike.toInt()}${it.type}" }.let { "NIFTY $it condor" }
        StrategyType.BULL_CALL_SPREAD, StrategyType.BEAR_PUT_SPREAD ->
            "NIFTY " + legs.joinToString("/") { it.strike.toInt().toString() } + " " + legs.first().type + " spread"
        else -> legs.firstOrNull()?.let { "NIFTY ${it.strike.toInt()} ${it.type}" } ?: "–"
    }
}

@Serializable
data class StrategyPlan(
    val type: StrategyType = StrategyType.NO_TRADE,
    val chosen: StrategyCandidate? = null,
    /** What the regime/scenario shape asked for (the chosen one may be a fallback with better economics). */
    val preferredByRegime: StrategyType = StrategyType.NO_TRADE,
    val candidates: List<StrategyCandidate> = emptyList(),
    val rationale: List<String> = emptyList(),
)

// ------------------------------------------------------------------ risk (deterministic — AI can recommend, code decides)

@Serializable
data class RiskConfig(
    val capital: Double = 200_000.0,
    val maxRiskPerTradePct: Double = 2.0,
    /** Max premium outlay / margin per trade, % of capital. */
    val maxCapitalPerTradePct: Double = 25.0,
    val maxDailyLossPct: Double = 5.0,
    val maxOpenPositions: Int = 1,
    val maxTradesPerDay: Int = 4,
    val maxLots: Int = 10,
    val maxSpreadPct: Double = 3.0,
    /** Slippage (ticks × legs × 2) as % of the per-unit risk. */
    val maxSlippagePct: Double = 4.0,
    /** No long-premium trades above this ATM IV (%). */
    val maxIvPct: Double = 35.0,
    /** No new trades while the information-shock score is above this. */
    val maxShockScore: Double = 0.6,
    val longStopPct: Double = 35.0,
    val longTargetPct: Double = 60.0,
    val spreadStopPct: Double = 50.0,
    /** Target as % of the spread's max profit. */
    val spreadTargetPct: Double = 60.0,
    /** Close a condor when the cost to close reaches this multiple of the credit. */
    val condorStopMultiple: Double = 2.0,
    /** Take profit when this % of the credit has been earned. */
    val condorTargetPct: Double = 50.0,
    /** No new entries after this IST time (minutes after midnight). */
    val noNewEntriesAfterMin: Int = 14 * 60 + 45,
    /** Shadow positions are flat by this IST time (minutes after midnight). */
    val exitAllAtMin: Int = 15 * 60 + 15,
)

@Serializable
data class ExitPlan(
    /** Exit when the position's per-unit value falls to/below this. */
    val stopValue: Double,
    /** Exit when the per-unit value rises to/above this. */
    val targetValue: Double,
    val stopText: String,
    val targetText: String,
    val timeExitAt: Long,
    val emergencyRules: List<String>,
)

@Serializable
data class RiskAssessment(
    val approved: Boolean = false,
    val lots: Int = 0,
    val quantity: Int = 0,
    val riskBudget: Double = 0.0,
    /** Rupees lost if the stop is hit (incl. costs). */
    val riskAtStop: Double = 0.0,
    /** Rupees lost in the worst case at expiry (NaN = unlimited). */
    val worstCaseLoss: Double = 0.0,
    val capitalRequired: Double = 0.0,
    val dailyPnl: Double = 0.0,
    val openPositions: Int = 0,
    val tradesToday: Int = 0,
    val exit: ExitPlan? = null,
    val checks: List<Check> = emptyList(),
    val notes: List<String> = emptyList(),
)

// ------------------------------------------------------------------ execution (shadow mode) + learning

@Serializable
data class ShadowLeg(val action: LegAction, val type: OptionType, val strike: Double, val entry: Double, val mark: Double)

@Serializable
data class ShadowPosition(
    val id: String,
    val openedAt: Long,
    val strategy: StrategyType,
    val instrument: String,
    val legs: List<ShadowLeg>,
    val lots: Int,
    val lotSize: Int,
    /** Per-unit entry value (+ debit, − credit). */
    val entryValue: Double,
    val markValue: Double,
    val stopValue: Double,
    val targetValue: Double,
    val timeExitAt: Long,
    val riskAtStop: Double,
    val direction: Int,
    val entrySpot: Double,
    val regime: String,
    val quality: Double,
    val qualityTier: String,
    val probability: Double,
    val expectationState: String,
    val shockLevel: String,
    val decision: String,
    val lastMarkAt: Long = 0L,
    /** Best / worst per-unit P&L seen while open. */
    val mfe: Double = 0.0,
    val mae: Double = 0.0,
)

@Serializable
data class ShadowTrade(
    val position: ShadowPosition,
    val closedAt: Long,
    val exitValue: Double,
    val exitSpot: Double,
    val reason: String,
    val grossPnlPerUnit: Double,
    val costs: Double,
    /** Net rupees after all charges. */
    val pnl: Double,
    /** pnl ÷ rupees at risk at entry. */
    val rMultiple: Double,
) {
    val win: Boolean get() = pnl > 0
    val holdMinutes: Double get() = (closedAt - position.openedAt) / 60_000.0
}

@Serializable
data class ShadowBook(
    val open: List<ShadowPosition> = emptyList(),
    val closed: List<ShadowTrade> = emptyList(),
)

@Serializable
data class GroupStat(val key: String, val n: Int, val winRate: Double, val avgPnl: Double, val totalPnl: Double, val avgR: Double)

@Serializable
data class ShadowSummary(
    val enabled: Boolean = true,
    val open: List<ShadowPosition> = emptyList(),
    val recent: List<ShadowTrade> = emptyList(),
    val trades: Int = 0,
    val winRate: Double = Double.NaN,
    val totalPnl: Double = 0.0,
    val todayPnl: Double = 0.0,
    val expectancy: Double = Double.NaN,
    val avgR: Double = Double.NaN,
    val maxDrawdown: Double = 0.0,
    /** What the shadow trader did this cycle ("opened …", "closed … TARGET"). */
    val events: List<String> = emptyList(),
    val byStrategy: List<GroupStat> = emptyList(),
    val byRegime: List<GroupStat> = emptyList(),
    val byQuality: List<GroupStat> = emptyList(),
    val byProbability: List<GroupStat> = emptyList(),
    val byExpectation: List<GroupStat> = emptyList(),
    val byExitReason: List<GroupStat> = emptyList(),
)

// ------------------------------------------------------------------ final decision object

/** The complete decision state of one cycle — not just "NIFTY UP". Keys follow the v5 spec. */
@Serializable
data class DecisionState(
    @SerialName("market") val market: String = "NIFTY",
    @SerialName("timestamp") val timestamp: Long = 0L,
    @SerialName("spot") val spot: Double = 0.0,
    @SerialName("regime") val regime: String = PrimaryRegime.RANGE.name,
    @SerialName("regime_quality") val regimeQuality: Double = 0.0,
    @SerialName("direction") val direction: String = "RANGE",
    @SerialName("raw_probability") val rawProbability: Double = 0.0,
    /** null until the decision horizon has (partial or full) calibration. */
    @SerialName("calibrated_probability") val calibratedProbability: Double? = null,
    /** NONE / PARTIAL / FULL. */
    @SerialName("calibration") val calibration: String = "NONE",
    @SerialName("confidence") val confidence: Double = 0.0,
    @SerialName("scenarios") val scenarios: Map<String, Double> = emptyMap(),
    @SerialName("future_expectation") val futureExpectation: Double = 0.0,
    @SerialName("expectation_change") val expectationChange: Double = 0.0,
    @SerialName("expectation_state") val expectationState: String = ExpectationState.NEUTRAL.name,
    @SerialName("information_shock") val informationShock: Double = 0.0,
    @SerialName("shock_direction") val shockDirection: Double = 0.0,
    @SerialName("trade_quality") val tradeQuality: Double = 0.0,
    @SerialName("quality_tier") val qualityTier: String = QualityTier.NONE.name,
    @SerialName("instrument") val instrument: String = "–",
    @SerialName("strategy") val strategy: String = StrategyType.NO_TRADE.name,
    @SerialName("lots") val lots: Int = 0,
    @SerialName("risk_at_stop") val riskAtStop: Double = 0.0,
    @SerialName("stop") val stop: String = "",
    @SerialName("target") val target: String = "",
    @SerialName("action") val action: String = Decision.WAIT.name,
    @SerialName("reasons") val reasons: List<String> = emptyList(),
)

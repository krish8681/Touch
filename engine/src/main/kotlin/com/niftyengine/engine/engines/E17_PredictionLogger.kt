package com.niftyengine.engine.engines

import com.niftyengine.engine.core.Session
import com.niftyengine.engine.model.ConfidenceLevel
import com.niftyengine.engine.model.Direction
import com.niftyengine.engine.model.EngineOutput
import com.niftyengine.engine.model.ExpiryKind
import com.niftyengine.engine.model.HorizonId
import com.niftyengine.engine.model.LegAction
import com.niftyengine.engine.model.OptionType
import com.niftyengine.engine.model.StrategyKind
import com.niftyengine.engine.model.StrategyLeg
import kotlinx.serialization.Serializable
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.pow

/**
 * 17 — Prediction Logger / audit trail.
 *
 * Each logged prediction stores all six horizons (raw and calibrated probabilities, σ, drift, neutral band, range,
 * every factor's effective score), the regimes, alignment/confidence/event risk, the chosen strategy and the decision
 * checks. When a horizon's target time passes the realised NIFTY price is attached (intraday from the observed path,
 * the close and expiries from daily closes) — the labelled, out-of-sample dataset the calibrator is fitted on (§27).
 * The recommended strategy's realised P&L at expiry is attached as well, so its P(profit) can be calibrated too.
 */
@Serializable
data class HorizonOutcome(
    val price: Double,
    val move: Double,
    /** +1 bullish, −1 bearish, 0 neutral — using the record's own neutral band. */
    val realized: Int,
    val insideRange: Boolean,
    val evaluatedAt: Long,
)

@Serializable
data class HorizonRecord(
    val id: HorizonId,
    val targetTime: Long,
    val score: Double,
    val sigma: Double,
    val drift: Double,
    val band: Double,
    /** RAW model scores (the calibrator is fitted on these, never on calibrated values). */
    val pBull: Double,
    val pNeutral: Double,
    val pBear: Double,
    val calibrated: Boolean,
    val calPBull: Double = Double.NaN,
    val calPNeutral: Double = Double.NaN,
    val calPBear: Double = Double.NaN,
    val direction: Direction,
    val probability: Double,
    val confidence: ConfidenceLevel,
    val confidenceValue: Double,
    val coverage: Double,
    val expectedPrice: Double,
    val rangeLow: Double,
    val rangeHigh: Double,
    /** Factor → effective contribution (score points). */
    val factors: Map<String, Double> = emptyMap(),
    /** Factor → direction (−100..100) of available factors (for per-factor hit rates). */
    val factorDirections: Map<String, Double> = emptyMap(),
    val outcome: HorizonOutcome? = null,
) {
    val predictedClass: Int get() = direction.sign
}

@Serializable
data class StrategyRecord(
    val kind: StrategyKind,
    val expiryKind: ExpiryKind,
    val horizon: HorizonId,
    val expiry: String,
    val targetTime: Long,
    val legs: List<StrategyLeg>,
    val netPremium: Double,
    val costPerUnit: Double,
    val pProfit: Double,
    val expectedPnl: Double,
    val maxLoss: Double,
    val realizedPnl: Double = Double.NaN,
    val win: Boolean? = null,
)

/** Snapshot of one tracked event as it stood when the prediction was made. */
@Serializable
data class EventAudit(
    val id: String, val title: String, val stage: String, val source: String, val severity: Double,
    val expectedProbability: Double, val surprise: Double, val pricedIn: Double, val unpriced: Double,
    val reactionAgreement: Double, val confidence: Double, val effectiveImpact: Double,
    val horizonImpacts: Map<String, Double>, val flags: List<String>,
)

@Serializable
data class PredictionRecord(
    val id: String,
    val timestamp: Long,
    val spot: Double,
    val engineVersion: String = "",
    val source: String,
    val regime: String,
    val secondaryRegime: String = "",
    val weeklyExpiryRegime: String = "",
    val monthlyExpiryRegime: String = "",
    val masterDirection: Direction,
    val masterProbability: Double,
    val alignment: Int,
    val masterConfidence: ConfidenceLevel,
    val eventRisk: Map<String, String> = emptyMap(),
    val horizons: List<HorizonRecord>,
    val strategy: StrategyRecord? = null,
    val decision: String,
    val headline: String = "",
    val failedChecks: List<String> = emptyList(),
    val dataQuality: Double = Double.NaN,
    val circuitBreaker: List<String> = emptyList(),
    val feedStatus: Map<String, String> = emptyMap(),
    val config: Map<String, String> = emptyMap(),
    val events: List<EventAudit> = emptyList(),
) {
    fun horizon(id: HorizonId) = horizons.firstOrNull { it.id == id }
}

interface PredictionStore {
    fun append(r: PredictionRecord)
    fun update(r: PredictionRecord)
    fun all(): List<PredictionRecord>
}

class InMemoryPredictionStore : PredictionStore {
    private val list = mutableListOf<PredictionRecord>()
    override fun append(r: PredictionRecord) { list += r }
    override fun update(r: PredictionRecord) { val i = list.indexOfFirst { it.id == r.id }; if (i >= 0) list[i] = r }
    override fun all(): List<PredictionRecord> = list.toList()
}

class PredictionLogger(private val store: PredictionStore) {
    /** Observed NIFTY price (and option LTPs keyed "strike|CE") at time [t]. */
    data class PricePoint(val t: Long, val price: Double, val optionPrices: Map<String, Double> = emptyMap())

    fun record(o: EngineOutput, config: Map<String, String> = emptyMap(), slim: Boolean = false): PredictionRecord {
        val best = o.decision.candidate ?: o.strategies.best
        fun r1(x: Double) = if (x.isNaN()) x else kotlin.math.round(x * 10) / 10
        val r = PredictionRecord(
            id = "${o.timestamp}", timestamp = o.timestamp, spot = o.spot, engineVersion = o.engineVersion, source = o.dataSource,
            regime = o.regime.regime.name, secondaryRegime = o.regime.secondary?.name ?: "",
            weeklyExpiryRegime = o.weekly?.regime?.name ?: "", monthlyExpiryRegime = o.monthly?.regime?.name ?: "",
            masterDirection = o.master.direction, masterProbability = o.master.probability, alignment = o.master.alignment,
            masterConfidence = o.master.confidence, eventRisk = o.eventRisk.levels.entries.associate { it.key.name to it.value.name },
            horizons = o.horizons.map { h ->
                HorizonRecord(
                    id = h.id, targetTime = h.targetTime, score = h.score, sigma = h.sigmaPts, drift = h.driftPts, band = h.neutralBand,
                    pBull = h.pBull, pNeutral = h.pNeutral, pBear = h.pBear, calibrated = h.calibrated,
                    calPBull = h.calPBull, calPNeutral = h.calPNeutral, calPBear = h.calPBear,
                    direction = h.direction, probability = h.probability, confidence = h.confidence, confidenceValue = h.confidenceValue,
                    coverage = h.coverage, expectedPrice = h.expectedPrice, rangeLow = h.rangeLow, rangeHigh = h.rangeHigh,
                    factors = if (slim) emptyMap() else h.factors.filter { it.reading.available }.associate { it.factor.name to r1(it.effective) },
                    factorDirections = h.factors.filter { it.reading.available }.associate { it.factor.name to r1(it.reading.direction) },
                )
            },
            strategy = best?.let { b ->
                val target = if (b.horizon.intraday) o.horizon(b.horizon)?.targetTime ?: 0L else (o.expiry(b.expiryKind)?.expiryMillis
                    ?: o.horizon(b.horizon)?.targetTime ?: 0L)
                StrategyRecord(b.kind, b.expiryKind, b.horizon, b.expiry, target, b.legs, b.netPremium, b.costPerUnit, b.pProfit, b.expectedPnl, b.maxLoss)
            },
            decision = o.decision.decision.name, headline = o.decision.headline,
            failedChecks = o.decision.checks.filter { !it.passed }.map { "${it.name}: ${it.detail}" },
            dataQuality = o.dataQuality.score, circuitBreaker = o.dataQuality.circuitBreaker,
            // Only feeds that were not LIVE (keeps the log small; LIVE is the default assumption).
            feedStatus = if (slim) emptyMap() else o.dataQuality.feeds.filter { it.status != com.niftyengine.engine.model.FeedStatus.LIVE }
                .associate { it.name to "${it.status}${if (it.detail.isNotBlank()) " · ${it.detail.take(60)}" else ""}" },
            config = config,
            events = if (slim) emptyList() else o.events.filter { abs(it.effectiveImpact) > 0.005 || (it.analysis?.severity ?: 0.0) >= 0.5 }.take(5).map { e ->
                EventAudit(e.id, e.title.take(120), e.stage.name, e.analysis?.source ?: "", e.analysis?.severity ?: 0.0,
                    e.expectations.lastOrNull()?.probability ?: Double.NaN, e.surprise, e.pricedIn, e.unpriced,
                    e.reaction.agreement, e.newsConfidence, e.effectiveImpact, e.horizonImpacts.mapKeys { it.key.name }, e.flags)
            },
        )
        store.append(r)
        return r
    }

    /**
     * Attach outcomes whose target time has passed. [path] = observed prices (ascending, for intraday targets);
     * [dailyCloses] = NIFTY closes by date (for the close and expiry targets when the app wasn't watching).
     * Returns the number of records changed.
     */
    fun evaluate(now: Long, path: List<PricePoint>, dailyCloses: Map<LocalDate, Double>): Int {
        var changed = 0
        for (r in store.all()) {
            val pending = r.horizons.any { it.outcome == null && now >= it.targetTime } ||
                (r.strategy != null && r.strategy.win == null && now >= r.strategy.targetTime && r.strategy.targetTime > 0)
            if (!pending) continue
            if (now - r.timestamp > 45L * 86_400_000L) continue
            val hs = r.horizons.map { h ->
                if (h.outcome != null || now < h.targetTime) h else priceAt(h.targetTime, path, dailyCloses)?.let { px -> h.copy(outcome = outcomeOf(r, h, px, now)) } ?: h
            }
            val st = r.strategy?.let { s -> if (s.win != null || now < s.targetTime || s.targetTime <= 0) s else strategyOutcome(s, path, dailyCloses) ?: s }
            if (hs != r.horizons || st != r.strategy) { store.update(r.copy(horizons = hs, strategy = st)); changed++ }
        }
        return changed
    }

    companion object {
        /** Price at [t]: the last observed tick within 3 min before it (10 min for a session close), else the daily close. */
        fun priceAt(t: Long, path: List<PricePoint>, dailyCloses: Map<LocalDate, Double>): Double? {
            val z = Session.zdt(t)
            val isClose = z.toLocalTime() == Session.CLOSE
            val before = path.lastOrNull { it.t <= t }
            if (before != null && t - before.t <= (if (isClose) 10 else 3) * 60_000L) return before.price
            if (isClose) dailyCloses[z.toLocalDate()]?.let { return it }
            return null
        }

        fun outcomeOf(r: PredictionRecord, h: HorizonRecord, px: Double, now: Long): HorizonOutcome {
            val move = px - r.spot
            return HorizonOutcome(px, move, when { move > h.band -> 1; move < -h.band -> -1; else -> 0 },
                px >= h.rangeLow && px <= h.rangeHigh, now)
        }

        fun payoffAtExpiry(legs: List<StrategyLeg>, s: Double) = legs.sumOf { l ->
            (if (l.action == LegAction.BUY) 1.0 else -1.0) * OptionsValuationEngine.intrinsic(l.type == OptionType.CE, s, l.strike)
        }

        fun strategyOutcome(s: StrategyRecord, path: List<PricePoint>, dailyCloses: Map<LocalDate, Double>): StrategyRecord? {
            val pnl = if (s.horizon.intraday) {
                val p = path.lastOrNull { it.t <= s.targetTime }?.takeIf { s.targetTime - it.t <= 5 * 60_000L } ?: return null
                val exit = s.legs.sumOf { l ->
                    val px = p.optionPrices["${l.strike}|${l.type}"] ?: return null
                    (if (l.action == LegAction.BUY) 1.0 else -1.0) * px
                }
                exit - s.netPremium - s.costPerUnit
            } else {
                val px = priceAt(s.targetTime, path, dailyCloses) ?: return null
                payoffAtExpiry(s.legs, px) - s.netPremium - s.costPerUnit
            }
            return s.copy(realizedPnl = pnl, win = pnl > 0)
        }
    }
}

/** Performance summary per horizon over logged predictions with outcomes. */
object PerformanceStats {
    data class Bucket(val label: String, val n: Int, val avgPredicted: Double, val hitRate: Double)
    data class Summary(
        val horizon: HorizonId,
        val n: Int,
        val accuracy: Double,
        val brier: Double,
        val directionalHitRate: Double,
        /** Share of outcomes inside the predicted ~68 % range (well calibrated ≈ 68 %). */
        val rangeHitRate: Double,
        /** Reliability of the RAW top-class score. */
        val buckets: List<Bucket>,
        /** Reliability after calibration (records that were calibrated when logged). */
        val calibratedBuckets: List<Bucket>,
        val factorHitRates: Map<String, Double>,
    )

    data class StrategySummary(val n: Int, val winRate: Double, val avgPnl: Double, val buckets: List<Bucket>, val byKind: Map<String, Pair<Int, Double>>)

    val EDGES = listOf(0.0 to 0.40, 0.40 to 0.50, 0.50 to 0.55, 0.55 to 0.60, 0.60 to 0.65, 0.65 to 0.70, 0.70 to 0.80, 0.80 to 1.01)

    private fun label(lo: Double, hi: Double) = when {
        lo == 0.0 -> "<%.0f%%".format(hi * 100); hi > 1.0 -> "%.0f%%+".format(lo * 100); else -> "%.0f–%.0f%%".format(lo * 100, hi * 100)
    }

    fun bucketize(points: List<Pair<Double, Boolean>>): List<Bucket> = EDGES.map { (lo, hi) ->
        val sel = points.filter { it.first >= lo && it.first < hi }
        Bucket(label(lo, hi), sel.size, if (sel.isEmpty()) Double.NaN else sel.map { it.first }.average(),
            if (sel.isEmpty()) Double.NaN else sel.count { it.second }.toDouble() / sel.size)
    }

    fun brier(pb: Double, pn: Double, pd: Double, y: Int): Double =
        (pb - if (y == 1) 1.0 else 0.0).pow(2) + (pn - if (y == 0) 1.0 else 0.0).pow(2) + (pd - if (y == -1) 1.0 else 0.0).pow(2)

    fun summarize(records: List<PredictionRecord>, h: HorizonId): Summary {
        val done = records.mapNotNull { r -> r.horizon(h)?.takeIf { it.outcome != null } }
        if (done.isEmpty()) return Summary(h, 0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, emptyList(), emptyList(), emptyMap())
        val acc = done.count { it.predictedClass == it.outcome!!.realized }.toDouble() / done.size
        val br = done.map { brier(it.pBull, it.pNeutral, it.pBear, it.outcome!!.realized) }.average()
        val dir = done.filter { it.predictedClass != 0 }
        val dirHit = if (dir.isEmpty()) Double.NaN else dir.count { it.predictedClass * it.outcome!!.move > 0 }.toDouble() / dir.size
        val rangeHit = done.count { it.outcome!!.insideRange }.toDouble() / done.size
        fun top(b: Double, n: Double, d: Double): Pair<Double, Int> = listOf(b to 1, n to 0, d to -1).maxBy { it.first }
        val raw = done.map { x -> top(x.pBull, x.pNeutral, x.pBear).let { (p, c) -> p to (c == x.outcome!!.realized) } }
        val cal = done.filter { it.calibrated && !it.calPBull.isNaN() }.map { x -> top(x.calPBull, x.calPNeutral, x.calPBear).let { (p, c) -> p to (c == x.outcome!!.realized) } }
        val factors = done.flatMap { it.factorDirections.keys }.toSet()
        val hits = factors.associateWith { f ->
            val sel = done.filter { abs(it.factorDirections[f] ?: 0.0) > 20 }
            if (sel.size < 5) Double.NaN else sel.count { (it.factorDirections.getValue(f)) * it.outcome!!.move > 0 }.toDouble() / sel.size
        }
        return Summary(h, done.size, acc, br, dirHit, rangeHit, bucketize(raw), bucketize(cal), hits)
    }

    fun strategies(records: List<PredictionRecord>): StrategySummary {
        val done = records.mapNotNull { r -> r.strategy?.takeIf { it.win != null } }
        if (done.isEmpty()) return StrategySummary(0, Double.NaN, Double.NaN, emptyList(), emptyMap())
        return StrategySummary(done.size, done.count { it.win == true }.toDouble() / done.size, done.map { it.realizedPnl }.average(),
            bucketize(done.map { it.pProfit to (it.win == true) }),
            done.groupBy { it.kind.label }.mapValues { (_, l) -> l.size to l.count { it.win == true }.toDouble() / l.size })
    }
}

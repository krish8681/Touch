package com.niftyengine.engine.engines

import com.niftyengine.engine.core.M
import com.niftyengine.engine.model.DriverContribution
import com.niftyengine.engine.model.EngineOutput
import com.niftyengine.engine.model.Driver
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 17 — Prediction Logger / Audit Trail.
 *
 * Every logged prediction stores everything needed to reproduce *why* the app said what it said:
 * all driver scores/weights/confidences, every engine signal, regime + reasons, raw and calibrated
 * probabilities, data freshness, the option chosen with its gross/net economics, and every decision check.
 * 5/15/30/60 minutes later the realised NIFTY move (and the option's price) is attached — the labelled
 * dataset for calibration and weight tuning.
 */
@Serializable
data class Outcome(
    val minutes: Int,
    val price: Double,
    val move: Double,
    val high: Double,
    val low: Double,
    val optionPrice: Double = Double.NaN,
    /** +1 bull, −1 bear, 0 range — realised class using the record's horizon-scaled threshold. */
    val realized: Int,
    // ---- v5.1 provenance: which prices produced this outcome, and when it was attached
    /** Prices strictly after this (= the record's decision time) … */
    val windowStart: Long = 0L,
    /** … and at or before this (= decision time + [minutes]). */
    val windowEnd: Long = 0L,
    /** Timestamp of the price used as the outcome. */
    val lastPriceAt: Long = 0L,
    /** Price points observed inside the window. */
    val points: Int = 0,
    /** When the outcome was attached — never before [windowEnd]. */
    val attachedAt: Long = 0L,
    /** The range/direction threshold (points) [realized] was classified with. */
    val threshold: Double = Double.NaN,
)

@Serializable
data class SignalAudit(val score: Double, val confidence: Double, val tags: List<String> = emptyList())

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
    /** RAW model scores (the calibrator is fitted on these, never on calibrated values). */
    val pBull: Double,
    val pBear: Double,
    val pRange: Double,
    val confidence: String,
    val confidenceValue: Double,
    val regime: String,
    val expectedMove: Double,
    /** 1σ move for [horizonMinutes]. */
    val sigma: Double,
    val strike: Double = Double.NaN,
    val optionType: String = "",
    val premium: Double = Double.NaN,
    val iv: Double = Double.NaN,
    val delta: Double = Double.NaN,
    val vix: Double = Double.NaN,
    val futuresOi: Double = Double.NaN,
    val breadth: Double = 0.0,
    val globalScore: Double = 0.0,
    val newsScore: Double = 0.0,
    val driverScores: Map<String, Double> = emptyMap(),
    val decision: String,
    val source: String,
    val outcomes: List<Outcome> = emptyList(),
    // ---- v3.2 audit fields
    val engineVersion: String = "",
    val horizonMinutes: Int = 60,
    val calPBull: Double = Double.NaN,
    val calPBear: Double = Double.NaN,
    val calPRange: Double = Double.NaN,
    val calibrated: Boolean = false,
    val regimeReasons: List<String> = emptyList(),
    val drivers: List<DriverContribution> = emptyList(),
    val signals: Map<String, SignalAudit> = emptyMap(),
    val directionalScore: Double = Double.NaN,
    val conflict: Double = Double.NaN,
    val dataQuality: Double = Double.NaN,
    val feedStatus: Map<String, String> = emptyMap(),
    val circuitBreaker: List<String> = emptyList(),
    val failedChecks: List<String> = emptyList(),
    val probProfit: Double = Double.NaN,
    val probProfitCalibrated: Double = Double.NaN,
    val optionGrossEv: Double = Double.NaN,
    val optionNetEv: Double = Double.NaN,
    val optionCost: Double = Double.NaN,
    val optionSpreadPct: Double = Double.NaN,
    val config: Map<String, String> = emptyMap(),
    // ---- v4 event intelligence
    val newsHorizons: Map<String, Double> = emptyMap(),
    val events: List<EventAudit> = emptyList(),
    // ---- v5 decision state
    val primaryRegime: String = "",
    val regimeQuality: Double = Double.NaN,
    /** RAW scenario probabilities keyed by [com.niftyengine.engine.model.Scenario] name (scenario calibration is fitted on these). */
    val scenarioProbs: Map<String, Double> = emptyMap(),
    val calScenarioProbs: Map<String, Double> = emptyMap(),
    val rangeBand: Double = 0.0,
    val breakoutBand: Double = 0.0,
    val expectation: Double = Double.NaN,
    val expectationChange: Double = Double.NaN,
    val expectationState: String = "",
    val shockScore: Double = Double.NaN,
    val shockDirection: Double = Double.NaN,
    val tradeQuality: Double = Double.NaN,
    val qualityTier: String = "",
    val strategy: String = "",
    val instrument: String = "",
    val strategyProbProfit: Double = Double.NaN,
    val strategyEv: Double = Double.NaN,
    val riskApproved: Boolean = false,
    val lots: Int = 0,
    // ---- v5.1 point-in-time audit: the frozen snapshot behind the decision and everything the decision used
    /** [timestamp] is the decision time (the snapshot's own time); this is the moment analysed (last bar after hours). */
    val analysedTimestamp: Long = 0L,
    val snapshotId: String = "",
    /** Timestamp of every input in the snapshot (0 = input absent). None may be after [timestamp] + [pitToleranceSec]. */
    val inputTimestamps: Map<String, Long> = emptyMap(),
    val criticalSkewSec: Double = Double.NaN,
    val pitToleranceSec: Double = Double.NaN,
    /** Inputs the point-in-time validator rejected or trimmed (stamped after the decision time). */
    val pitViolations: List<String> = emptyList(),
    /** "OK", or the critical-data failure that made this cycle DATA ERROR (such records are never calibrated on). */
    val criticalData: String = "",
    val direction: String = "",
    /** The probabilities the decision actually used, at [calibrationLevel] (FULL / PARTIAL / NONE = raw). */
    val usedPBull: Double = Double.NaN,
    val usedPBear: Double = Double.NaN,
    val usedPRange: Double = Double.NaN,
    val calibrationLevel: String = "",
    val calibrationFittedAt: Long = 0L,
    val modelHealth: Double = Double.NaN,
    val healthTier: String = "",
    /** Integrity hash of the prediction-time content (see [PredictionAudit]); blank on records logged before v5.1.1. */
    val auditHash: String = "",
) {
    val predictedClass: Int get() = when {
        pBull >= pBear && pBull >= pRange -> 1
        pBear >= pBull && pBear >= pRange -> -1
        else -> 0
    }
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

class PredictionLogger(private val store: PredictionStore, val horizons: List<Int> = ProbabilityCalibrator.HORIZONS) {
    data class PricePoint(val t: Long, val price: Double, val optionPrice: Double = Double.NaN)

    fun record(o: EngineOutput, config: Map<String, String> = emptyMap()): PredictionRecord {
        val best = o.options.best
        val d = o.direction
        val cal = d.decisionProbs(o.expectedMove.horizonMinutes)
        val r = PredictionRecord(
            id = "${o.timestamp}", timestamp = o.timestamp, spot = o.spot,
            pBull = d.pBull, pBear = d.pBear, pRange = d.pRange,
            confidence = d.confidence.name, confidenceValue = d.confidenceValue,
            regime = o.regime.regime.name, expectedMove = o.expectedMove.expectedMovePoints, sigma = o.expectedMove.sigmaPoints,
            strike = best?.strike ?: Double.NaN, optionType = best?.type?.name ?: "", premium = best?.premium ?: Double.NaN,
            iv = best?.iv ?: Double.NaN, delta = best?.delta ?: Double.NaN,
            vix = o.signals["India VIX"]?.details?.firstOrNull()?.value?.toDoubleOrNull() ?: Double.NaN,
            futuresOi = o.signals["Futures"]?.details?.firstOrNull { it.key == "OI" }?.value?.replace(",", "")?.toDoubleOrNull() ?: Double.NaN,
            breadth = o.signals["Breadth"]?.score ?: 0.0,
            globalScore = o.signals["Global risk"]?.score ?: 0.0,
            newsScore = o.signals["News"]?.score ?: 0.0,
            driverScores = d.drivers.associate { it.driver.name to it.score },
            decision = o.decision.decision.name, source = o.dataSource,
            engineVersion = o.engineVersion, horizonMinutes = o.expectedMove.horizonMinutes,
            calPBull = if (cal.calibrated) cal.pBull else Double.NaN,
            calPBear = if (cal.calibrated) cal.pBear else Double.NaN,
            calPRange = if (cal.calibrated) cal.pRange else Double.NaN,
            calibrated = cal.calibrated,
            regimeReasons = o.regime.reasons,
            drivers = d.drivers,
            signals = o.signals.mapValues { SignalAudit(it.value.score, it.value.confidence, it.value.tags) },
            directionalScore = d.directionalScore, conflict = d.conflict,
            dataQuality = o.dataQuality.score,
            feedStatus = o.dataQuality.feeds.associate { it.name to "${it.status}${if (it.detail.isNotBlank()) " · ${it.detail}" else ""}" },
            circuitBreaker = o.dataQuality.circuitBreaker,
            failedChecks = o.decision.checks.filter { !it.passed }.map { "${it.name}: ${it.detail}" },
            probProfit = best?.probProfit ?: Double.NaN,
            probProfitCalibrated = best?.probProfitCalibrated ?: Double.NaN,
            optionGrossEv = best?.grossExpectedValue ?: Double.NaN,
            optionNetEv = best?.expectedValue ?: Double.NaN,
            optionCost = best?.costPerUnit ?: Double.NaN,
            optionSpreadPct = best?.spreadPct ?: Double.NaN,
            config = config,
            newsHorizons = o.newsHorizons.mapKeys { it.key.name },
            events = o.events.filter { kotlin.math.abs(it.effectiveImpact) > 0.005 || (it.analysis?.severity ?: 0.0) >= 0.5 }.take(10).map { e ->
                EventAudit(e.id, e.title.take(120), e.stage.name, e.analysis?.source ?: "", e.analysis?.severity ?: 0.0,
                    e.expectations.lastOrNull()?.probability ?: Double.NaN, e.surprise, e.pricedIn, e.unpriced,
                    e.reaction.agreement, e.newsConfidence, e.effectiveImpact, e.horizonImpacts.mapKeys { it.key.name }, e.flags)
            },
            primaryRegime = o.regimeV5.primary.name, regimeQuality = o.regimeV5.quality,
            scenarioProbs = o.scenarios.scenarios.associate { it.scenario.name to it.rawProbability },
            calScenarioProbs = if (o.scenarios.calibration == "NONE") emptyMap() else o.scenarios.scenarios.associate { it.scenario.name to it.probability },
            rangeBand = o.scenarios.rangeBand, breakoutBand = o.scenarios.breakoutBand,
            expectation = o.expectation.expected, expectationChange = o.expectation.change, expectationState = o.expectation.state.name,
            shockScore = o.shock.score, shockDirection = o.shock.direction,
            tradeQuality = o.quality.score, qualityTier = o.quality.tier.name,
            strategy = o.strategy.type.name, instrument = o.strategy.chosen?.instrument ?: "",
            strategyProbProfit = o.strategy.chosen?.probProfit ?: Double.NaN, strategyEv = o.strategy.chosen?.expectedValue ?: Double.NaN,
            riskApproved = o.risk.approved, lots = o.risk.lots,
            analysedTimestamp = o.snapshot.analysedTime, snapshotId = o.snapshot.snapshotId,
            inputTimestamps = o.snapshot.inputTimes, criticalSkewSec = o.snapshot.criticalSkewSec,
            pitToleranceSec = o.pointInTime.toleranceSec,
            pitViolations = o.pointInTime.violations.map { PointInTimeValidator.describe(it) },
            criticalData = o.snapshot.criticalData.ifBlank { "OK" }, direction = o.decisionState.direction,
            usedPBull = cal.pBull, usedPBear = cal.pBear, usedPRange = cal.pRange, calibrationLevel = cal.level,
            calibrationFittedAt = o.snapshot.calibrationFittedAt,
            modelHealth = o.health.score, healthTier = o.health.tier.name,
        ).let(PredictionAudit::seal)
        store.append(r)
        return r
    }

    /** Attach outcomes to every record whose horizon has elapsed. [path] = observed prices (ascending). */
    fun evaluate(path: List<PricePoint>, now: Long) = evaluate(now) { path }

    /** Variant where the path can be record-specific (e.g. carrying the selected option's price). */
    fun evaluate(now: Long, pathFor: (PredictionRecord) -> List<PricePoint>): Int {
        var changed = 0
        for (r in store.all()) {
            if (now - r.timestamp > 6 * 3_600_000L) continue
            val missing = horizons.filter { h -> r.outcomes.none { it.minutes == h } && now >= r.timestamp + h * 60_000L }
            if (missing.isEmpty()) continue
            val path = pathFor(r)
            if (path.isEmpty()) continue
            val added = missing.mapNotNull { h -> outcomeFor(r, h, path, now) }
            if (added.isNotEmpty()) { store.update(r.copy(outcomes = (r.outcomes + added).sortedBy { it.minutes })); changed++ }
        }
        return changed
    }

    /**
     * The outcome of [r] at horizon [h], or null. [now] is the clock at attachment: an outcome is attached ONLY once the
     * horizon has fully expired (now ≥ decision time + h), from prices stamped in (decision time, decision time + h].
     */
    fun outcomeFor(r: PredictionRecord, h: Int, path: List<PricePoint>, now: Long): Outcome? {
        val end = r.timestamp + h * 60_000L
        if (now < end) return null
        val tolerance = minOf(5 * 60_000L, (h * 60_000L * 0.4).toLong())
        val window = path.filter { it.t > r.timestamp && it.t <= end }
        if (window.isEmpty() || window.last().t < end - tolerance) return null
        val px = window.last().price
        val move = px - r.spot
        val thr = classThreshold(r, h)
        return Outcome(h, px, move, window.maxOf { it.price }, window.minOf { it.price }, window.last().optionPrice,
            classify(move, thr), windowStart = r.timestamp, windowEnd = end, lastPriceAt = window.last().t,
            points = window.size, attachedAt = now, threshold = thr)
    }

    companion object {
        /** "Range" = |move| below 0.35σ of that horizon (σ scaled by √time from the record's horizon), floor 0.04%·√(h/15). */
        fun classThreshold(r: PredictionRecord, h: Int = r.horizonMinutes): Double {
            val sigmaH = r.sigma * sqrt(h.toDouble() / r.horizonMinutes.coerceAtLeast(1))
            return maxOf(0.35 * sigmaH, r.spot * 0.0004 * sqrt(h / 15.0))
        }

        fun classify(move: Double, threshold: Double): Int = when { move > threshold -> 1; move < -threshold -> -1; else -> 0 }
    }
}

/**
 * v5.1 — prediction / outcome audit.
 *
 * At logging time every record is SEALED with a hash of its prediction-time content (decision time, snapshot id and input
 * timestamps, raw / used / calibrated probabilities, regime, scenarios, selected option and strategy, model health,
 * decision). [verify] then proves, before a record is used for calibration or statistics:
 *  • the prediction-time content is unchanged since it was logged (hash);
 *  • no input of its snapshot was stamped after its decision time (beyond the clock-skew tolerance);
 *  • every outcome came from prices strictly after the decision time and at or before the horizon end, was attached only
 *    after the horizon expired, and its class matches its own move and threshold.
 * The hash detects edits, corruption and pipeline bugs; it is not a cryptographic signature.
 * Records logged before v5.1.1 carry no hash: they are LEGACY — their outcomes are checked where provenance exists.
 * Cycles whose critical data failed (DATA ERROR) are kept for the audit trail but never used for calibration.
 */
object PredictionAudit {
    enum class Status { VERIFIED, LEGACY, INVALID }
    data class Verdict(val status: Status, val problems: List<String> = emptyList()) {
        val usable: Boolean get() = status != Status.INVALID
    }
    data class Counts(val verified: Int, val legacy: Int, val invalid: Int, val dataError: Int) {
        val excluded: Int get() = invalid + dataError
    }

    private const val VERSION = "a1"

    /** Canonical text of everything known at prediction time (outcomes, slimmable diagnostics excluded). */
    private fun canonical(r: PredictionRecord): String {
        fun d(x: Double) = java.lang.Double.toString(x)
        fun <V> m(map: Map<String, V>) = map.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value.let { v -> if (v is Double) d(v) else v.toString() }}" }
        return listOf(
            VERSION, r.id, r.timestamp, r.analysedTimestamp, r.snapshotId, m(r.inputTimestamps), d(r.criticalSkewSec),
            d(r.pitToleranceSec), r.pitViolations.joinToString("|"), r.criticalData, r.engineVersion, d(r.spot),
            d(r.pBull), d(r.pBear), d(r.pRange), d(r.usedPBull), d(r.usedPBear), d(r.usedPRange),
            d(r.calPBull), d(r.calPBear), d(r.calPRange), r.calibrated, r.calibrationLevel, r.calibrationFittedAt,
            r.horizonMinutes, d(r.sigma), d(r.expectedMove), r.regime, r.primaryRegime, d(r.regimeQuality),
            m(r.scenarioProbs), m(r.calScenarioProbs), d(r.rangeBand), d(r.breakoutBand),
            r.strategy, r.instrument, d(r.strike), r.optionType, d(r.premium), d(r.probProfit), d(r.strategyProbProfit),
            d(r.strategyEv), r.riskApproved, r.lots, r.direction, r.decision, d(r.modelHealth), r.healthTier, d(r.dataQuality),
        ).joinToString("\u001F")
    }

    fun hash(r: PredictionRecord): String {
        val dig = java.security.MessageDigest.getInstance("SHA-256").digest(canonical(r).toByteArray(Charsets.UTF_8))
        return dig.take(12).joinToString("") { "%02x".format(it) }
    }

    fun seal(r: PredictionRecord): PredictionRecord = r.copy(auditHash = hash(r))

    fun dataError(r: PredictionRecord): Boolean = r.criticalData.isNotBlank() && r.criticalData != "OK"

    fun verify(r: PredictionRecord): Verdict {
        val sealed = r.auditHash.isNotBlank()
        val problems = ArrayList<String>()
        if (sealed) {
            if (hash(r) != r.auditHash) problems += "prediction content changed after it was logged (hash mismatch)"
            if (r.snapshotId.isBlank()) problems += "no snapshot id"
            val tolMs = ((if (r.pitToleranceSec.isNaN()) 0.0 else r.pitToleranceSec) * 1000).toLong()
            r.inputTimestamps.filter { it.value > r.timestamp + tolMs }.forEach { (k, t) ->
                problems += "input %s stamped %.0fs after the decision time".format(k, (t - r.timestamp) / 1000.0)
            }
        }
        val seen = HashSet<Int>()
        for (o in r.outcomes) {
            val tag = "${o.minutes}m outcome"
            if (!seen.add(o.minutes)) problems += "$tag attached twice"
            val hasProvenance = o.windowEnd > 0L
            if (!hasProvenance) { if (sealed) problems += "$tag has no provenance"; continue }
            val end = r.timestamp + o.minutes * 60_000L
            if (o.windowStart != r.timestamp || o.windowEnd != end) problems += "$tag window does not match the decision time + horizon"
            if (o.attachedAt < o.windowEnd) problems += "$tag attached before its horizon expired"
            if (o.lastPriceAt <= r.timestamp || o.lastPriceAt > end) problems += "$tag uses a price outside (decision time, horizon end]"
            if (o.points < 1) problems += "$tag has no observed prices"
            if (abs(o.move - (o.price - r.spot)) > 1e-6 * maxOf(1.0, abs(o.price))) problems += "$tag move ≠ price − spot"
            if (!o.threshold.isNaN() && o.realized != PredictionLogger.classify(o.move, o.threshold)) problems += "$tag class does not match its move"
        }
        return Verdict(when { problems.isNotEmpty() -> Status.INVALID; sealed -> Status.VERIFIED; else -> Status.LEGACY }, problems)
    }

    /** Records fit for calibration and statistics: verified or legacy, and not a DATA ERROR cycle. */
    fun usable(records: List<PredictionRecord>): List<PredictionRecord> = records.filter { !dataError(it) && verify(it).usable }

    fun counts(records: List<PredictionRecord>): Counts {
        var v = 0; var l = 0; var inv = 0; var de = 0
        for (r in records) {
            when (verify(r).status) { Status.VERIFIED -> v++; Status.LEGACY -> l++; Status.INVALID -> inv++ }
            if (dataError(r)) de++
        }
        return Counts(v, l, inv, de)
    }
}

/** Calibration / performance summary over logged predictions. */
object PerformanceStats {
    data class Bucket(val label: String, val n: Int, val avgPredicted: Double, val hitRate: Double)
    data class Summary(
        val horizon: Int,
        val n: Int,
        val accuracy: Double,
        val brier: Double,
        val directionalHitRate: Double,
        val tradeCount: Int,
        val tradeWinRate: Double,
        /** Reliability of the RAW top-class score. */
        val buckets: List<Bucket>,
        /** Reliability after calibration (only records that were calibrated when logged). */
        val calibratedBuckets: List<Bucket>,
        val driverHitRates: Map<String, Double>,
        /** Option-outcome model: predicted P(profit) vs realised net option profit. */
        val optionBuckets: List<Bucket>,
        /** v5: every scenario probability vs whether that scenario happened (pooled reliability), at the record's horizon. */
        val scenarioBuckets: List<Bucket> = emptyList(),
        /** v5: realised frequency per scenario vs average predicted probability. */
        val scenarioRates: List<Bucket> = emptyList(),
        /** v5.1: multi-class log loss of the raw scores (uninformed 3-way guess ≈ 1.099). */
        val logLoss: Double = Double.NaN,
        /** v5.1: directional calls by regime — predicted vs realised, Brier, log loss, threshold bump. */
        val regimes: List<RegimeRecord> = emptyList(),
        /** v5.1: logged records left out (DATA ERROR cycles, records failing the audit). */
        val excluded: Int = 0,
    )

    /** The bucket edges requested for calibration review. */
    val EDGES = listOf(0.0 to 0.50, 0.50 to 0.55, 0.55 to 0.60, 0.60 to 0.65, 0.65 to 0.70, 0.70 to 0.75, 0.75 to 0.80, 0.80 to 1.01)

    private fun label(lo: Double, hi: Double) = when {
        lo == 0.0 -> "<50%"; hi > 1.0 -> "80%+"; else -> "%.0f–%.0f%%".format(lo * 100, hi * 100)
    }

    private fun bucketize(points: List<Pair<Double, Boolean>>): List<Bucket> = EDGES.map { (lo, hi) ->
        val sel = points.filter { it.first >= lo && it.first < hi }
        Bucket(label(lo, hi), sel.size, if (sel.isEmpty()) Double.NaN else sel.map { it.first }.average(),
            if (sel.isEmpty()) Double.NaN else sel.count { it.second }.toDouble() / sel.size)
    }

    private val SCEN_EDGES = listOf(0.0 to 0.05, 0.05 to 0.10, 0.10 to 0.20, 0.20 to 0.30, 0.30 to 0.45, 0.45 to 1.01)

    private fun scenarioBucketize(points: List<Pair<Double, Boolean>>): List<Bucket> = SCEN_EDGES.map { (lo, hi) ->
        val sel = points.filter { it.first >= lo && it.first < hi }
        Bucket(if (hi > 1.0) "%.0f%%+".format(lo * 100) else "%.0f–%.0f%%".format(lo * 100, hi * 100), sel.size,
            if (sel.isEmpty()) Double.NaN else sel.map { it.first }.average(),
            if (sel.isEmpty()) Double.NaN else sel.count { it.second }.toDouble() / sel.size)
    }

    fun summarize(logged: List<PredictionRecord>, horizon: Int = 30): Summary {
        val records = PredictionAudit.usable(logged)
        val excluded = logged.size - records.size
        val done = records.mapNotNull { r -> r.outcomes.firstOrNull { it.minutes == horizon }?.let { r to it } }
        val opt = records.mapNotNull { r ->
            if (r.probProfit.isNaN() || r.premium.isNaN()) return@mapNotNull null
            val o = r.outcomes.firstOrNull { it.minutes == horizon } ?: return@mapNotNull null
            if (o.optionPrice.isNaN()) return@mapNotNull null
            r.probProfit to (o.optionPrice - r.premium - (if (r.optionCost.isNaN()) 0.0 else r.optionCost) > 0)
        }
        if (done.isEmpty()) return Summary(horizon, 0, Double.NaN, Double.NaN, Double.NaN, 0, Double.NaN, emptyList(), emptyList(), emptyMap(), bucketize(opt),
            excluded = excluded)
        // scenario reliability: records whose own horizon equals this horizon
        val scen = done.filter { (r, _) -> r.horizonMinutes == horizon && r.scenarioProbs.isNotEmpty() && r.rangeBand > 0 }.map { (r, o) ->
            r to ScenarioEngine.realized(o.move, r.rangeBand, r.breakoutBand)
        }
        val scenPoints = scen.flatMap { (r, real) ->
            val probs = r.calScenarioProbs.ifEmpty { r.scenarioProbs }
            probs.map { (k, p) -> p to (k == real.name) }
        }
        val scenRates = com.niftyengine.engine.model.Scenario.values().map { sc ->
            val ps = scen.mapNotNull { (r, _) -> (r.calScenarioProbs.ifEmpty { r.scenarioProbs })[sc.name] }
            Bucket(sc.name, scen.size, if (ps.isEmpty()) Double.NaN else ps.average(),
                if (scen.isEmpty()) Double.NaN else scen.count { it.second == sc }.toDouble() / scen.size)
        }
        val acc = done.count { (r, o) -> r.predictedClass == o.realized }.toDouble() / done.size
        val brier = done.map { (r, o) ->
            val y = doubleArrayOf(if (o.realized == 1) 1.0 else 0.0, if (o.realized == -1) 1.0 else 0.0, if (o.realized == 0) 1.0 else 0.0)
            (r.pBull - y[0]).pow(2) + (r.pBear - y[1]).pow(2) + (r.pRange - y[2]).pow(2)
        }.average()
        val directional = done.filter { it.first.predictedClass != 0 }
        val dirHit = if (directional.isEmpty()) Double.NaN else directional.count { (r, o) -> r.predictedClass * o.move > 0 }.toDouble() / directional.size
        val trades = done.filter { it.first.decision == "TRADE" || it.first.decision == "PAPER_TRADE" }
        val tradeWin = if (trades.isEmpty()) Double.NaN else trades.count { (r, o) ->
            if (!o.optionPrice.isNaN() && !r.premium.isNaN()) o.optionPrice - r.premium - (if (r.optionCost.isNaN()) 0.0 else r.optionCost) > 0
            else r.predictedClass * o.move > 0
        }.toDouble() / trades.size
        val raw = done.map { (r, o) -> maxOf(r.pBull, r.pBear, r.pRange) to (r.predictedClass == o.realized) }
        val cal = done.filter { it.first.calibrated && !it.first.calPBull.isNaN() }.map { (r, o) ->
            val top = maxOf(r.calPBull, r.calPBear, r.calPRange)
            val cls = when (top) { r.calPBull -> 1; r.calPBear -> -1; else -> 0 }
            top to (cls == o.realized)
        }
        val driverHits = Driver.values().associate { d ->
            val sel = done.filter { abs(it.first.driverScores[d.name] ?: 0.0) > 0.2 }
            d.label to if (sel.size < 5) Double.NaN else sel.count { (r, o) -> M.clamp(r.driverScores.getValue(d.name)) * o.move > 0 }.toDouble() / sel.size
        }
        val logLoss = done.map { (r, o) -> ProbabilityCalibrator.logLoss(r.pBull, r.pBear, r.pRange, o.realized) }.average()
        return Summary(horizon, done.size, acc, brier, dirHit, trades.size, tradeWin, bucketize(raw), bucketize(cal), driverHits, bucketize(opt),
            if (scenPoints.isEmpty()) emptyList() else scenarioBucketize(scenPoints), if (scen.isEmpty()) emptyList() else scenRates,
            logLoss, ProbabilityCalibrator.regimeRecords(records, horizon).values.sortedByDescending { it.n }, excluded)
    }
}

package com.niftyengine.engine.engines

import com.niftyengine.engine.core.M
import com.niftyengine.engine.model.EngineOutput
import com.niftyengine.engine.model.Driver
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.pow

/**
 * 17 — Prediction Logger.
 * Every prediction is saved with its inputs; 15/30/60 minutes later the actual outcome is attached.
 * This builds the labelled dataset for stage-3 weight/threshold/calibration tuning automatically.
 */
@Serializable
data class Outcome(
    val minutes: Int,
    val price: Double,
    val move: Double,
    val high: Double,
    val low: Double,
    val optionPrice: Double = Double.NaN,
    /** +1 bull, −1 bear, 0 range — realised class using the record's threshold. */
    val realized: Int,
)

@Serializable
data class PredictionRecord(
    val id: String,
    val timestamp: Long,
    val spot: Double,
    val pBull: Double,
    val pBear: Double,
    val pRange: Double,
    val confidence: String,
    val confidenceValue: Double,
    val regime: String,
    val expectedMove: Double,
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

class PredictionLogger(private val store: PredictionStore, val horizons: List<Int> = listOf(15, 30, 60)) {
    data class PricePoint(val t: Long, val price: Double, val optionPrice: Double = Double.NaN)

    fun record(o: EngineOutput): PredictionRecord {
        val best = o.options.best
        val r = PredictionRecord(
            id = "${o.timestamp}", timestamp = o.timestamp, spot = o.spot,
            pBull = o.direction.pBull, pBear = o.direction.pBear, pRange = o.direction.pRange,
            confidence = o.direction.confidence.name, confidenceValue = o.direction.confidenceValue,
            regime = o.regime.regime.name, expectedMove = o.expectedMove.expectedMovePoints, sigma = o.expectedMove.sigmaPoints,
            strike = best?.strike ?: Double.NaN, optionType = best?.type?.name ?: "", premium = best?.premium ?: Double.NaN,
            iv = best?.iv ?: Double.NaN, delta = best?.delta ?: Double.NaN,
            vix = o.signals["India VIX"]?.details?.firstOrNull()?.value?.toDoubleOrNull() ?: Double.NaN,
            futuresOi = o.signals["Futures"]?.details?.firstOrNull { it.key == "OI" }?.value?.replace(",", "")?.toDoubleOrNull() ?: Double.NaN,
            breadth = o.signals["Breadth"]?.score ?: 0.0,
            globalScore = o.signals["Global risk"]?.score ?: 0.0,
            newsScore = o.signals["News"]?.score ?: 0.0,
            driverScores = o.direction.drivers.associate { it.driver.name to it.score },
            decision = o.decision.decision.name, source = o.dataSource,
        )
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
            val added = missing.mapNotNull { h -> outcomeFor(r, h, path) }
            if (added.isNotEmpty()) { store.update(r.copy(outcomes = (r.outcomes + added).sortedBy { it.minutes })); changed++ }
        }
        return changed
    }

    fun outcomeFor(r: PredictionRecord, h: Int, path: List<PricePoint>): Outcome? {
        val end = r.timestamp + h * 60_000L
        val window = path.filter { it.t > r.timestamp && it.t <= end }
        if (window.isEmpty() || window.last().t < end - 5 * 60_000L) return null
        val px = window.last().price
        val move = px - r.spot
        val thr = classThreshold(r)
        return Outcome(h, px, move, window.maxOf { it.price }, window.minOf { it.price }, window.last().optionPrice,
            when { move > thr -> 1; move < -thr -> -1; else -> 0 })
    }

    companion object {
        /** A move smaller than 0.35σ (or 0.1%) of the predicted horizon σ counts as "range". */
        fun classThreshold(r: PredictionRecord): Double = maxOf(0.35 * r.sigma, r.spot * 0.001)
    }
}

/** Calibration / performance summary over logged predictions (stage 2 → stage 3 tuning input). */
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
        val buckets: List<Bucket>,
        val driverHitRates: Map<String, Double>,
    )

    fun summarize(records: List<PredictionRecord>, horizon: Int = 30): Summary {
        val done = records.mapNotNull { r -> r.outcomes.firstOrNull { it.minutes == horizon }?.let { r to it } }
        if (done.isEmpty()) return Summary(horizon, 0, Double.NaN, Double.NaN, Double.NaN, 0, Double.NaN, emptyList(), emptyMap())
        val acc = done.count { (r, o) -> r.predictedClass == o.realized }.toDouble() / done.size
        val brier = done.map { (r, o) ->
            val y = doubleArrayOf(if (o.realized == 1) 1.0 else 0.0, if (o.realized == -1) 1.0 else 0.0, if (o.realized == 0) 1.0 else 0.0)
            (r.pBull - y[0]).pow(2) + (r.pBear - y[1]).pow(2) + (r.pRange - y[2]).pow(2)
        }.average()
        val directional = done.filter { it.first.predictedClass != 0 }
        val dirHit = if (directional.isEmpty()) Double.NaN else directional.count { (r, o) -> r.predictedClass * o.move > 0 }.toDouble() / directional.size
        val trades = done.filter { it.first.decision == "TRADE" }
        val tradeWin = if (trades.isEmpty()) Double.NaN else trades.count { (r, o) ->
            if (!o.optionPrice.isNaN() && !r.premium.isNaN()) o.optionPrice > r.premium else r.predictedClass * o.move > 0
        }.toDouble() / trades.size
        // Reliability: bucket the top directional probability and compare with realised hit rate.
        val buckets = listOf(0.33 to 0.45, 0.45 to 0.55, 0.55 to 0.65, 0.65 to 0.75, 0.75 to 1.01).map { (lo, hi) ->
            val sel = done.filter { (r, _) -> maxOf(r.pBull, r.pBear, r.pRange) in lo..<hi }
            Bucket("%.0f–%.0f%%".format(lo * 100, minOf(hi, 1.0) * 100), sel.size,
                if (sel.isEmpty()) Double.NaN else sel.map { maxOf(it.first.pBull, it.first.pBear, it.first.pRange) }.average(),
                if (sel.isEmpty()) Double.NaN else sel.count { (r, o) -> r.predictedClass == o.realized }.toDouble() / sel.size)
        }
        val driverHits = Driver.values().associate { d ->
            val sel = done.filter { abs(it.first.driverScores[d.name] ?: 0.0) > 0.2 }
            d.label to if (sel.size < 5) Double.NaN else sel.count { (r, o) -> M.clamp(r.driverScores.getValue(d.name)) * o.move > 0 }.toDouble() / sel.size
        }
        return Summary(horizon, done.size, acc, brier, dirHit, trades.size, tradeWin, buckets, driverHits)
    }
}

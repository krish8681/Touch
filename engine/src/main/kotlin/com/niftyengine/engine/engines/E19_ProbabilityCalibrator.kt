package com.niftyengine.engine.engines

import com.niftyengine.engine.model.CalibrationInfo
import com.niftyengine.engine.model.HorizonProb
import kotlinx.serialization.Serializable
import kotlin.math.pow

/**
 * 19 — Probability Calibrator.
 *
 * The direction engine's softmax output is a *model score*. This module maps it onto observed frequencies:
 * for each horizon (5/15/30/60 min) and each class (bull/bear/range) an isotonic regression is fitted
 * on logged predictions vs realised outcomes, then the three calibrated values are renormalised.
 * A walk-forward check (fit on the first 70 % by time, score on the last 30 %) reports whether calibration
 * actually improves the Brier score. Until a horizon has [minSamples] outcomes it stays "uncalibrated".
 *
 * The same machinery calibrates the option-outcome model: model P(profit) → realised net option P&L > 0.
 */
@Serializable
data class IsoModel(val xs: List<Double>, val ys: List<Double>) {
    /** Piecewise-linear interpolation between block centres, flat beyond the ends. */
    fun predict(x: Double): Double {
        if (xs.isEmpty()) return x
        if (x <= xs.first()) return ys.first()
        if (x >= xs.last()) return ys.last()
        val i = xs.indexOfLast { it <= x }
        val x0 = xs[i]; val x1 = xs[i + 1]
        return if (x1 == x0) ys[i] else ys[i] + (ys[i + 1] - ys[i]) * (x - x0) / (x1 - x0)
    }

    companion object {
        /** Pool-adjacent-violators with a light prior ([prior] weighted as 2 pseudo-observations per block). */
        fun fit(points: List<Pair<Double, Double>>, prior: Double): IsoModel {
            if (points.isEmpty()) return IsoModel(emptyList(), emptyList())
            val sorted = points.sortedBy { it.first }
            // blocks: (sumX, sumY, n)
            val sx = ArrayList<Double>(); val sy = ArrayList<Double>(); val n = ArrayList<Double>()
            for ((x, y) in sorted) {
                sx += x; sy += y; n += 1.0
                while (sy.size >= 2 && sy[sy.size - 2] / n[n.size - 2] > sy.last() / n.last()) {
                    val k = sy.size - 1
                    sx[k - 1] += sx[k]; sy[k - 1] += sy[k]; n[k - 1] += n[k]
                    sx.removeAt(k); sy.removeAt(k); n.removeAt(k)
                }
            }
            val xs = sx.indices.map { sx[it] / n[it] }
            val ys = sy.indices.map { (sy[it] + 2 * prior) / (n[it] + 2) }
            // smoothing can re-introduce tiny violations; enforce monotone
            val mono = ys.toMutableList()
            for (i in 1 until mono.size) if (mono[i] < mono[i - 1]) mono[i] = mono[i - 1]
            return IsoModel(xs, mono)
        }
    }
}

@Serializable
data class ClassModels(val bull: IsoModel, val bear: IsoModel, val range: IsoModel)

@Serializable
data class CalibrationModel(
    val horizons: Map<Int, ClassModels> = emptyMap(),
    val option: IsoModel? = null,
    val optionSamples: Int = 0,
    val info: CalibrationInfo = CalibrationInfo(),
) {
    fun apply(pBull: Double, pBear: Double, pRange: Double, h: Int): HorizonProb {
        val m = horizons[h] ?: return HorizonProb(h, pBull, pBear, pRange, false)
        val b = m.bull.predict(pBull).coerceAtLeast(1e-4)
        val d = m.bear.predict(pBear).coerceAtLeast(1e-4)
        val r = m.range.predict(pRange).coerceAtLeast(1e-4)
        val s = b + d + r
        return HorizonProb(h, b / s, d / s, r / s, true)
    }

    fun allHorizons(pBull: Double, pBear: Double, pRange: Double, hs: List<Int>): List<HorizonProb> =
        hs.map { apply(pBull, pBear, pRange, it) }

    fun optionFn(): ((Double) -> Double)? = option?.let { m -> { p: Double -> m.predict(p) } }
}

object ProbabilityCalibrator {
    val HORIZONS = listOf(5, 15, 30, 60)

    private fun brier(pb: Double, pd: Double, pr: Double, realized: Int): Double =
        (pb - if (realized == 1) 1.0 else 0.0).pow(2) + (pd - if (realized == -1) 1.0 else 0.0).pow(2) +
            (pr - if (realized == 0) 1.0 else 0.0).pow(2)

    private fun fitClasses(rows: List<Pair<PredictionRecord, Outcome>>): ClassModels = ClassModels(
        IsoModel.fit(rows.map { (r, o) -> r.pBull to if (o.realized == 1) 1.0 else 0.0 }, 1.0 / 3),
        IsoModel.fit(rows.map { (r, o) -> r.pBear to if (o.realized == -1) 1.0 else 0.0 }, 1.0 / 3),
        IsoModel.fit(rows.map { (r, o) -> r.pRange to if (o.realized == 0) 1.0 else 0.0 }, 1.0 / 3),
    )

    fun fit(records: List<PredictionRecord>, minSamples: Int = 150, minOptionSamples: Int = 100, now: Long = System.currentTimeMillis()): CalibrationModel {
        val models = HashMap<Int, ClassModels>()
        val samples = HashMap<Int, Int>()
        val brRaw = HashMap<Int, Double>(); val brCal = HashMap<Int, Double>()
        for (h in HORIZONS) {
            val rows = records.mapNotNull { r -> r.outcomes.firstOrNull { it.minutes == h }?.let { r to it } }.sortedBy { it.first.timestamp }
            samples[h] = rows.size
            if (rows.size < 20) continue
            // walk-forward: fit on the earlier 70 %, evaluate on the later 30 %
            val cut = (rows.size * 0.7).toInt()
            val train = rows.subList(0, cut); val test = rows.subList(cut, rows.size)
            if (test.size >= 10) {
                val m = CalibrationModel(mapOf(h to fitClasses(train)))
                brRaw[h] = test.map { (r, o) -> brier(r.pBull, r.pBear, r.pRange, o.realized) }.average()
                brCal[h] = test.map { (r, o) -> m.apply(r.pBull, r.pBear, r.pRange, h).let { p -> brier(p.pBull, p.pBear, p.pRange, o.realized) } }.average()
            }
            if (rows.size >= minSamples) models[h] = fitClasses(rows)
        }
        // option-outcome calibration: model P(profit) vs realised net P&L > 0 at the record's own horizon
        val optRows = records.mapNotNull { r ->
            if (r.probProfit.isNaN() || r.premium.isNaN()) return@mapNotNull null
            val o = r.outcomes.firstOrNull { it.minutes == r.horizonMinutes } ?: r.outcomes.maxByOrNull { it.minutes } ?: return@mapNotNull null
            if (o.optionPrice.isNaN()) return@mapNotNull null
            r.probProfit to if (o.optionPrice - r.premium - (if (r.optionCost.isNaN()) 0.0 else r.optionCost) > 0) 1.0 else 0.0
        }
        val optModel = if (optRows.size >= minOptionSamples) IsoModel.fit(optRows, 0.5) else null
        val calibrated = models.isNotEmpty()
        return CalibrationModel(
            models, optModel, optRows.size,
            CalibrationInfo(
                calibrated = calibrated, samples = samples, minSamples = minSamples,
                holdoutBrierRaw = brRaw, holdoutBrierCalibrated = brCal, fittedAt = now,
                note = if (calibrated) "Calibrated on ${models.keys.sorted().joinToString { "${it}m n=${samples[it]}" }}"
                else "Uncalibrated: ${samples.entries.sortedBy { it.key }.joinToString { "${it.key}m ${it.value}/$minSamples" }} outcomes — probabilities are model scores",
            ),
        )
    }
}

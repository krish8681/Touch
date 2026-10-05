package com.niftyengine.engine.engines

import com.niftyengine.engine.model.CalibrationInfo
import com.niftyengine.engine.model.HorizonId
import kotlinx.serialization.Serializable

/**
 * 19 — Probability Calibrator (§27: "do not map +60 to 80 %").
 *
 * The probability engine's output is a MODEL SCORE. For each horizon (30 min, 1 h, 3 h, close, weekly expiry, monthly
 * expiry) and class (bullish / neutral / bearish) an isotonic regression maps it onto the frequencies actually observed in
 * logged, out-of-sample outcomes; the three values are renormalised. A walk-forward check (fit on the first 70 % by time,
 * score on the last 30 %) reports whether calibration improves the Brier score. Expiry horizons use at most one record per
 * hour, since predictions minutes apart share the same outcome. Until a horizon has [minSamples] outcomes it stays
 * uncalibrated and the UI labels its numbers "model score".
 *
 * The recommended strategy's P(profit) is calibrated the same way against realised strategy P&L.
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
            val mono = ys.toMutableList()
            for (i in 1 until mono.size) if (mono[i] < mono[i - 1]) mono[i] = mono[i - 1]
            return IsoModel(xs, mono)
        }
    }
}

@Serializable
data class ClassModels(val bull: IsoModel, val neutral: IsoModel, val bear: IsoModel)

@Serializable
data class CalibrationModel(
    /** Key = HorizonId.name. */
    val horizons: Map<String, ClassModels> = emptyMap(),
    val strategy: IsoModel? = null,
    val strategySamples: Int = 0,
    val info: CalibrationInfo = CalibrationInfo(),
) {
    /** Calibrated (bull, neutral, bear) for [h], or null when the horizon is not calibrated yet. */
    fun apply(h: HorizonId, pBull: Double, pNeutral: Double, pBear: Double): Triple<Double, Double, Double>? {
        val m = horizons[h.name] ?: return null
        val b = m.bull.predict(pBull).coerceAtLeast(1e-4)
        val n = m.neutral.predict(pNeutral).coerceAtLeast(1e-4)
        val d = m.bear.predict(pBear).coerceAtLeast(1e-4)
        val s = b + n + d
        return Triple(b / s, n / s, d / s)
    }

    fun strategyFn(): ((Double) -> Double)? = strategy?.let { m -> { p: Double -> m.predict(p) } }
}

object ProbabilityCalibrator {
    private fun rows(records: List<PredictionRecord>, h: HorizonId): List<HorizonRecord> {
        val all = records.sortedBy { it.timestamp }.mapNotNull { r -> r.horizon(h)?.takeIf { it.outcome != null }?.let { r.timestamp to it } }
        if (h.intraday) return all.map { it.second }
        // Expiry horizons: one record per hour (records minutes apart share one outcome).
        val out = ArrayList<HorizonRecord>(); var last = Long.MIN_VALUE
        for ((t, x) in all) if (t - last >= 3_600_000L) { out += x; last = t }
        return out
    }

    private fun fitClasses(rows: List<HorizonRecord>) = ClassModels(
        IsoModel.fit(rows.map { it.pBull to if (it.outcome!!.realized == 1) 1.0 else 0.0 }, 1.0 / 3),
        IsoModel.fit(rows.map { it.pNeutral to if (it.outcome!!.realized == 0) 1.0 else 0.0 }, 1.0 / 3),
        IsoModel.fit(rows.map { it.pBear to if (it.outcome!!.realized == -1) 1.0 else 0.0 }, 1.0 / 3),
    )

    fun fit(records: List<PredictionRecord>, minSamples: Int = 150, minStrategySamples: Int = 60, now: Long = System.currentTimeMillis()): CalibrationModel {
        val models = HashMap<String, ClassModels>()
        val samples = HashMap<String, Int>()
        val brRaw = HashMap<String, Double>(); val brCal = HashMap<String, Double>()
        for (h in HorizonId.values()) {
            val rs = rows(records, h)
            samples[h.name] = rs.size
            if (rs.size < 20) continue
            val cut = (rs.size * 0.7).toInt()
            val train = rs.subList(0, cut); val test = rs.subList(cut, rs.size)
            if (test.size >= 10) {
                val m = CalibrationModel(mapOf(h.name to fitClasses(train)))
                brRaw[h.name] = test.map { PerformanceStats.brier(it.pBull, it.pNeutral, it.pBear, it.outcome!!.realized) }.average()
                brCal[h.name] = test.map { x -> m.apply(h, x.pBull, x.pNeutral, x.pBear)!!.let { (b, n, d) -> PerformanceStats.brier(b, n, d, x.outcome!!.realized) } }.average()
            }
            if (rs.size >= minSamples) models[h.name] = fitClasses(rs)
        }
        val strat = records.mapNotNull { r -> r.strategy?.takeIf { it.win != null && !it.pProfit.isNaN() } }
        val stratModel = if (strat.size >= minStrategySamples) IsoModel.fit(strat.map { it.pProfit to if (it.win == true) 1.0 else 0.0 }, 0.5) else null
        val calibrated = models.isNotEmpty()
        return CalibrationModel(
            models, stratModel, strat.size,
            CalibrationInfo(
                calibrated = calibrated, samples = samples, calibratedHorizons = models.keys.toList(), minSamples = minSamples,
                holdoutBrierRaw = brRaw, holdoutBrierCalibrated = brCal, strategySamples = strat.size, fittedAt = now,
                note = if (calibrated) "Calibrated: " + HorizonId.values().filter { it.name in models }.joinToString { "${it.short} n=${samples[it.name]}" } +
                    HorizonId.values().filter { it.name !in models }.takeIf { it.isNotEmpty() }?.joinToString(prefix = " · not yet: ") { "${it.short} ${samples[it.name]}/$minSamples" }.orEmpty()
                else "Uncalibrated: " + HorizonId.values().joinToString { "${it.short} ${samples[it.name] ?: 0}/$minSamples" } + " outcomes — probabilities are model scores",
            ),
        )
    }
}

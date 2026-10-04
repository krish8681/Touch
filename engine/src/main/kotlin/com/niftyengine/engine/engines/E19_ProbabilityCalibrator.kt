package com.niftyengine.engine.engines

import com.niftyengine.engine.model.CalibrationInfo
import com.niftyengine.engine.model.HorizonProb
import com.niftyengine.engine.model.Scenario
import kotlinx.serialization.Serializable
import kotlin.math.ln
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
            // Tied scores are one point (weighted by their count) — otherwise duplicate x blocks make predict() ambiguous.
            val grouped = points.groupBy { it.first }.toSortedMap().map { (x, l) -> Triple(x * l.size, l.sumOf { it.second }, l.size.toDouble()) }
            // blocks: (sumX, sumY, n)
            val sx = ArrayList<Double>(); val sy = ArrayList<Double>(); val n = ArrayList<Double>()
            for ((gx, gy, gn) in grouped) {
                sx += gx; sy += gy; n += gn
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
    /** v5 progressive calibration: horizons with some (but not enough) outcomes, blended by sample share. */
    val partial: Map<Int, ClassModels> = emptyMap(),
    val partialWeight: Map<Int, Double> = emptyMap(),
    /** v5 scenario calibration: isotonic per scenario (key = [Scenario] name), blended by sample share. */
    val scenario: Map<String, IsoModel> = emptyMap(),
    val scenarioWeight: Double = 0.0,
    val scenarioSamples: Int = 0,
    /** v5.1 realised performance per primary regime (drives regime selectivity). */
    val regimes: Map<String, RegimeRecord> = emptyMap(),
) {
    /** Extra probability required in a regime where the model has been more over-confident than overall. */
    fun regimeBump(regime: String): Double = regimes[regime]?.thresholdBump ?: 0.0

    fun apply(pBull: Double, pBear: Double, pRange: Double, h: Int): HorizonProb {
        val m = horizons[h] ?: return HorizonProb(h, pBull, pBear, pRange, false)
        val b = m.bull.predict(pBull).coerceAtLeast(1e-4)
        val d = m.bear.predict(pBear).coerceAtLeast(1e-4)
        val r = m.range.predict(pRange).coerceAtLeast(1e-4)
        val s = b + d + r
        return HorizonProb(h, b / s, d / s, r / s, true)
    }

    /** Full calibration when available, otherwise a partial blend (raw score shrunk toward observed frequencies). */
    fun applyProgressive(pBull: Double, pBear: Double, pRange: Double, h: Int): HorizonProb {
        if (h in horizons) return apply(pBull, pBear, pRange, h)
        val m = partial[h] ?: return HorizonProb(h, pBull, pBear, pRange, false)
        val w = (partialWeight[h] ?: 0.0).coerceIn(0.0, 1.0)
        val b = (w * m.bull.predict(pBull) + (1 - w) * pBull).coerceAtLeast(1e-4)
        val d = (w * m.bear.predict(pBear) + (1 - w) * pBear).coerceAtLeast(1e-4)
        val r = (w * m.range.predict(pRange) + (1 - w) * pRange).coerceAtLeast(1e-4)
        val s = b + d + r
        return HorizonProb(h, b / s, d / s, r / s, calibrated = false, partial = true)
    }

    fun allHorizons(pBull: Double, pBear: Double, pRange: Double, hs: List<Int>): List<HorizonProb> =
        hs.map { applyProgressive(pBull, pBear, pRange, it) }

    /** Calibrated scenario probabilities and the level (FULL / PARTIAL), or null without a scenario fit. */
    fun applyScenarios(raw: Map<Scenario, Double>): Pair<Map<Scenario, Double>, String>? {
        if (scenario.isEmpty() || scenarioWeight <= 0) return null
        val w = scenarioWeight.coerceIn(0.0, 1.0)
        val cal = raw.mapValues { (k, p) -> (scenario[k.name]?.let { w * it.predict(p) + (1 - w) * p } ?: p).coerceAtLeast(1e-4) }
        val s = cal.values.sum()
        return cal.mapValues { it.value / s } to if (w >= 1.0) "FULL" else "PARTIAL"
    }

    fun optionFn(): ((Double) -> Double)? = option?.let { m -> { p: Double -> m.predict(p) } }
}

/** Realised performance of directional calls made in one regime (at each record's own horizon). */
@Serializable
data class RegimeRecord(
    val regime: String,
    val n: Int,
    /** Mean top (bull/bear) probability of the directional calls. */
    val avgPredicted: Double,
    /** Share of those calls whose class was realised. */
    val hitRate: Double,
    val brier: Double,
    val logLoss: Double,
    /** Over-confidence in this regime beyond the model's overall over-confidence, shrunk by sample size. */
    val excessGap: Double,
    /** Probability-threshold bump applied to new calls in this regime (0 = none). */
    val thresholdBump: Double,
)

object ProbabilityCalibrator {
    val HORIZONS = listOf(5, 15, 30, 60)

    private fun brier(pb: Double, pd: Double, pr: Double, realized: Int): Double =
        (pb - if (realized == 1) 1.0 else 0.0).pow(2) + (pd - if (realized == -1) 1.0 else 0.0).pow(2) +
            (pr - if (realized == 0) 1.0 else 0.0).pow(2)

    /** Multi-class log loss of the realised class (probabilities floored at 1e-4). */
    fun logLoss(pb: Double, pd: Double, pr: Double, realized: Int): Double =
        -ln(maxOf(1e-4, when (realized) { 1 -> pb; -1 -> pd; else -> pr }))

    private fun fitClasses(rows: List<Pair<PredictionRecord, Outcome>>): ClassModels = ClassModels(
        IsoModel.fit(rows.map { (r, o) -> r.pBull to if (o.realized == 1) 1.0 else 0.0 }, 1.0 / 3),
        IsoModel.fit(rows.map { (r, o) -> r.pBear to if (o.realized == -1) 1.0 else 0.0 }, 1.0 / 3),
        IsoModel.fit(rows.map { (r, o) -> r.pRange to if (o.realized == 0) 1.0 else 0.0 }, 1.0 / 3),
    )

    /** Fewest outcomes before a partial (progressive) calibration can be judged and applied. */
    const val MIN_PARTIAL = 34
    /** Hold-out slice must have at least this many outcomes to accept a calibration. */
    const val MIN_HOLDOUT = 10
    const val MIN_REGIME = 30

    /** Chronological split: earlier 70 % to fit, later 30 % (never seen by the fit) to judge. */
    private fun <T> split(rows: List<T>): Pair<List<T>, List<T>> {
        val cut = (rows.size * 0.7).toInt()
        return rows.subList(0, cut) to rows.subList(cut, rows.size)
    }

    private fun scenarioScores(probs: Map<Scenario, Double>, real: Scenario): Pair<Double, Double> =
        Scenario.values().sumOf { (probs.getValue(it) - if (it == real) 1.0 else 0.0).pow(2) } to -ln(maxOf(1e-4, probs.getValue(real)))

    private fun rawScenario(r: PredictionRecord): Map<Scenario, Double>? {
        val m = Scenario.values().associateWith { r.scenarioProbs[it.name] ?: return null }
        val s = m.values.sum()
        return if (s <= 0) null else m.mapValues { it.value / s }
    }

    /**
     * Fits every calibration and ACCEPTS it only if, fitted on the earlier 70 % of outcomes, it beats the raw model on the
     * later 30 % (lower Brier and no worse log loss). Rejected calibrations are not applied — the raw score is kept and
     * labelled as such. Outcomes are all in the past (attached only after their horizon elapsed), so nothing leaks.
     * v5.1: only records that pass [PredictionAudit.verify] and were not DATA ERROR cycles are fitted on.
     */
    fun fit(logged: List<PredictionRecord>, minSamples: Int = 150, minOptionSamples: Int = 100, now: Long = System.currentTimeMillis()): CalibrationModel {
        val records = PredictionAudit.usable(logged)
        val auditExcluded = logged.size - records.size
        val models = HashMap<Int, ClassModels>()
        val partial = HashMap<Int, ClassModels>()
        val partialW = HashMap<Int, Double>()
        val samples = HashMap<Int, Int>()
        val brRaw = HashMap<Int, Double>(); val brCal = HashMap<Int, Double>()
        val llRaw = HashMap<Int, Double>(); val llCal = HashMap<Int, Double>()
        val accepted = HashMap<Int, Boolean>()
        val rejectedNotes = ArrayList<String>()
        for (h in HORIZONS) {
            val rows = records.mapNotNull { r -> r.outcomes.firstOrNull { it.minutes == h }?.let { r to it } }.sortedBy { it.first.timestamp }
            samples[h] = rows.size
            if (rows.size < 20) continue
            val (train, test) = split(rows)
            if (test.size < MIN_HOLDOUT) continue
            // what will be deployed: the full fit at ≥ minSamples, a partial blend (weight = share of minSamples) below
            val w = (rows.size.toDouble() / minSamples).coerceAtMost(1.0)
            val m = if (rows.size >= minSamples) CalibrationModel(mapOf(h to fitClasses(train)))
                else CalibrationModel(partial = mapOf(h to fitClasses(train)), partialWeight = mapOf(h to w))
            brRaw[h] = test.map { (r, o) -> brier(r.pBull, r.pBear, r.pRange, o.realized) }.average()
            llRaw[h] = test.map { (r, o) -> logLoss(r.pBull, r.pBear, r.pRange, o.realized) }.average()
            val calP = test.map { (r, o) -> m.applyProgressive(r.pBull, r.pBear, r.pRange, h) to o.realized }
            brCal[h] = calP.map { (p, y) -> brier(p.pBull, p.pBear, p.pRange, y) }.average()
            llCal[h] = calP.map { (p, y) -> logLoss(p.pBull, p.pBear, p.pRange, y) }.average()
            val ok = brCal.getValue(h) < brRaw.getValue(h) && llCal.getValue(h) <= llRaw.getValue(h) + 1e-9
            if (rows.size < MIN_PARTIAL) continue
            accepted[h] = ok
            if (!ok) { rejectedNotes += "${h}m rejected (hold-out Brier %.3f vs raw %.3f)".format(brCal.getValue(h), brRaw.getValue(h)); continue }
            if (rows.size >= minSamples) models[h] = fitClasses(rows)
            else { partial[h] = fitClasses(rows); partialW[h] = w }
        }

        // v5 scenario calibration: raw scenario probabilities vs the realised bucket at the record's own horizon
        val scenRows = records.mapNotNull { r ->
            if (r.rangeBand <= 0 || r.breakoutBand <= 0) return@mapNotNull null
            val raw = rawScenario(r) ?: return@mapNotNull null
            val o = r.outcomes.firstOrNull { it.minutes == r.horizonMinutes } ?: return@mapNotNull null
            Triple(r.timestamp, raw, ScenarioEngine.realized(o.move, r.rangeBand, r.breakoutBand))
        }.sortedBy { it.first }
        fun fitScenarios(rows: List<Triple<Long, Map<Scenario, Double>, Scenario>>) = Scenario.values().associate { sc ->
            sc.name to IsoModel.fit(rows.map { (_, p, real) -> p.getValue(sc) to if (real == sc) 1.0 else 0.0 }, 0.2)
        }
        var scenModels: Map<String, IsoModel> = emptyMap()
        var sBrRaw = Double.NaN; var sBrCal = Double.NaN; var sLlRaw = Double.NaN; var sLlCal = Double.NaN; var sOk = false
        val scenW = (scenRows.size.toDouble() / minSamples).coerceAtMost(1.0)
        if (scenRows.size >= MIN_PARTIAL) {
            val (train, test) = split(scenRows)
            if (test.size >= MIN_HOLDOUT) {
                val m = CalibrationModel(scenario = fitScenarios(train), scenarioWeight = scenW)
                val rawS = test.map { (_, p, real) -> scenarioScores(p, real) }
                val calS = test.map { (_, p, real) -> scenarioScores(m.applyScenarios(p)!!.first, real) }
                sBrRaw = rawS.map { it.first }.average(); sLlRaw = rawS.map { it.second }.average()
                sBrCal = calS.map { it.first }.average(); sLlCal = calS.map { it.second }.average()
                sOk = sBrCal < sBrRaw && sLlCal <= sLlRaw + 1e-9
                if (sOk) scenModels = fitScenarios(scenRows)
                else rejectedNotes += "scenarios rejected (hold-out Brier %.3f vs raw %.3f)".format(sBrCal, sBrRaw)
            }
        }

        // option-outcome calibration: model P(profit) vs realised net P&L > 0 at the record's own horizon
        val optRows = records.mapNotNull { r ->
            if (r.probProfit.isNaN() || r.premium.isNaN()) return@mapNotNull null
            val o = r.outcomes.firstOrNull { it.minutes == r.horizonMinutes } ?: r.outcomes.maxByOrNull { it.minutes } ?: return@mapNotNull null
            if (o.optionPrice.isNaN()) return@mapNotNull null
            Triple(r.timestamp, r.probProfit, if (o.optionPrice - r.premium - (if (r.optionCost.isNaN()) 0.0 else r.optionCost) > 0) 1.0 else 0.0)
        }.sortedBy { it.first }
        var optModel: IsoModel? = null
        var oBrRaw = Double.NaN; var oBrCal = Double.NaN; var oOk = false
        if (optRows.size >= minOptionSamples) {
            val (train, test) = split(optRows)
            val m = IsoModel.fit(train.map { it.second to it.third }, 0.5)
            oBrRaw = test.map { (it.second - it.third).pow(2) }.average()
            oBrCal = test.map { (m.predict(it.second) - it.third).pow(2) }.average()
            oOk = oBrCal < oBrRaw
            if (oOk) optModel = IsoModel.fit(optRows.map { it.second to it.third }, 0.5)
            else rejectedNotes += "option P(profit) rejected (hold-out Brier %.3f vs raw %.3f)".format(oBrCal, oBrRaw)
        }

        val calibrated = models.isNotEmpty()
        return CalibrationModel(
            models, optModel, optRows.size, partial = partial, partialWeight = partialW,
            scenario = scenModels, scenarioWeight = if (scenModels.isEmpty()) 0.0 else scenW,
            scenarioSamples = scenRows.size, regimes = regimeRecords(records),
            info = CalibrationInfo(
                calibrated = calibrated, samples = samples, minSamples = minSamples,
                holdoutBrierRaw = brRaw, holdoutBrierCalibrated = brCal, fittedAt = now,
                note = (if (calibrated) "Calibrated on ${models.keys.sorted().joinToString { "${it}m n=${samples[it]}" }}"
                else "Uncalibrated: ${samples.entries.sortedBy { it.key }.joinToString { "${it.key}m ${it.value}/$minSamples" }} outcomes — probabilities are model scores" +
                    if (partial.isNotEmpty()) " (partial calibration on ${partial.keys.sorted().joinToString { "${it}m" }})" else "") +
                    (if (rejectedNotes.isEmpty()) "" else " · " + rejectedNotes.joinToString("; ")) +
                    if (auditExcluded == 0) "" else " · $auditExcluded record(s) excluded by the audit (data error / failed verification)",
                holdoutLogLossRaw = llRaw, holdoutLogLossCalibrated = llCal, accepted = accepted,
                scenarioSamples = scenRows.size, scenarioBrierRaw = sBrRaw, scenarioBrierCalibrated = sBrCal,
                scenarioLogLossRaw = sLlRaw, scenarioLogLossCalibrated = sLlCal, scenarioAccepted = sOk,
                optionBrierRaw = oBrRaw, optionBrierCalibrated = oBrCal, optionAccepted = oOk,
                auditExcluded = auditExcluded,
            ),
        )
    }

    /**
     * v5.1 regime selectivity: for each primary regime, how over-confident the directional calls made in it were,
     * relative to the model overall. A regime that keeps over-stating its probability gets a higher bar for new calls.
     */
    fun regimeRecords(records: List<PredictionRecord>, horizon: Int? = null): Map<String, RegimeRecord> {
        val rows = records.mapNotNull { r ->
            val o = r.outcomes.firstOrNull { it.minutes == (horizon ?: r.horizonMinutes) } ?: return@mapNotNull null
            if (r.predictedClass == 0) return@mapNotNull null
            Triple(r.primaryRegime.ifBlank { r.regime }, r, o)
        }
        if (rows.isEmpty()) return emptyMap()
        fun top(r: PredictionRecord) = maxOf(r.pBull, r.pBear)
        val globalGap = rows.map { top(it.second) }.average() - rows.count { it.third.realized == it.second.predictedClass }.toDouble() / rows.size
        return rows.groupBy { it.first }.mapValues { (reg, l) ->
            val n = l.size
            val avgP = l.map { top(it.second) }.average()
            val hit = l.count { it.third.realized == it.second.predictedClass }.toDouble() / n
            val excess = ((avgP - hit) - globalGap) * n / (n + 30.0)
            RegimeRecord(reg, n, avgP, hit,
                l.map { brier(it.second.pBull, it.second.pBear, it.second.pRange, it.third.realized) }.average(),
                l.map { logLoss(it.second.pBull, it.second.pBear, it.second.pRange, it.third.realized) }.average(),
                excess, if (n >= MIN_REGIME) excess.coerceIn(0.0, 0.10) else 0.0)
        }
    }
}

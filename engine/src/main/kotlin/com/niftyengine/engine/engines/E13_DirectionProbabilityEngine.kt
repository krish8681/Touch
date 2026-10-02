package com.niftyengine.engine.engines

import com.niftyengine.engine.core.EngineState
import com.niftyengine.engine.core.M
import com.niftyengine.engine.model.ConfidenceLevel
import com.niftyengine.engine.model.DirectionResult
import com.niftyengine.engine.model.Driver
import com.niftyengine.engine.model.DriverContribution
import com.niftyengine.engine.model.Regime
import com.niftyengine.engine.model.RegimeClass
import kotlin.math.abs
import kotlin.math.sign

/**
 * 13 — Direction Probability Engine.
 *
 * Transparent weighted model (stage 1 of the build plan, no ML):
 *   D = Σ wᵢ(regime) · confᵢ · persistenceᵢ · sᵢ / Σ wᵢ · confᵢ · persistenceᵢ,  sᵢ ∈ [−1, 1]
 * then softmax over (bull, bear, range) logits. Conflict between drivers flattens the logits, so a
 * single strong driver (e.g. news) cannot overpower what the market itself is doing.
 * Probability and confidence are reported separately.
 */
class DirectionProbabilityEngine(private val state: EngineState, private val params: Params = Params()) {

    data class Params(
        val directionalGain: Double = 3.2,
        val rangeGain: Double = 2.4,
        val rangeBaseline: Double = 0.35,
        val conflictDamping: Double = 0.5,
    )

    data class DriverInput(val driver: Driver, val score: Double, val confidence: Double)

    data class RangeInputs(val adx: Double, val wallRangeEvidence: Double, val vixState: VixState, val insideOpeningRange: Boolean)

    companion object {
        /** Starting engineering weights (spec §20/§21) — not empirically optimal; tune from logged predictions. */
        val WEIGHTS: Map<RegimeClass, Map<Driver, Double>> = mapOf(
            RegimeClass.NORMAL to mapOf(
                Driver.PRICE to 20.0, Driver.DERIVATIVES to 18.0, Driver.SECTOR to 15.0, Driver.GLOBAL to 12.0,
                Driver.BREADTH to 10.0, Driver.VIX to 8.0, Driver.MACRO to 7.0, Driver.FLOWS to 5.0, Driver.NEWS to 5.0,
            ),
            RegimeClass.TREND to mapOf(
                Driver.PRICE to 25.0, Driver.DERIVATIVES to 20.0, Driver.SECTOR to 15.0, Driver.GLOBAL to 12.0,
                Driver.BREADTH to 10.0, Driver.VIX to 6.0, Driver.MACRO to 7.0, Driver.FLOWS to 0.0, Driver.NEWS to 5.0,
            ),
            RegimeClass.EVENT to mapOf(
                Driver.NEWS to 25.0, Driver.GLOBAL to 15.0, Driver.VIX to 15.0, Driver.DERIVATIVES to 15.0,
                Driver.PRICE to 15.0, Driver.SECTOR to 8.0, Driver.BREADTH to 4.0, Driver.MACRO to 3.0, Driver.FLOWS to 0.0,
            ),
            RegimeClass.RANGE to mapOf(
                Driver.PRICE to 25.0, Driver.DERIVATIVES to 20.0, Driver.VIX to 15.0, Driver.BREADTH to 10.0,
                Driver.SECTOR to 8.0, Driver.GLOBAL to 7.0, Driver.MACRO to 5.0, Driver.NEWS to 5.0, Driver.FLOWS to 5.0,
            ),
        )
    }

    /** Weighted composite without regime adaptation/persistence — used by the regime engine as a first pass. */
    fun preliminary(inputs: List<DriverInput>): Double {
        val w = WEIGHTS.getValue(RegimeClass.NORMAL)
        val den = inputs.sumOf { w.getValue(it.driver) * it.confidence }
        return if (den <= 0) 0.0 else M.clamp(inputs.sumOf { w.getValue(it.driver) * it.confidence * it.score } / den)
    }

    /**
     * @param qualityScore data-quality score 0..1 — confidence can never exceed it.
     * @param concentration extra notes/penalty when the index move is carried by a few heavyweights.
     */
    fun compute(
        inputs: List<DriverInput>, regime: Regime, regimeClass: RegimeClass, range: RangeInputs, commit: Boolean = true,
        qualityScore: Double = 1.0, concentration: Pair<Double, String>? = null,
    ): DirectionResult {
        val w = WEIGHTS.getValue(regimeClass)
        val contributions = inputs.map { inp ->
            val hist = state.driverHistory[inp.driver]?.toList().orEmpty()
            // Driver persistence: a driver whose sign keeps flipping loses influence.
            val persistence = if (hist.size < 3 || abs(inp.score) < 0.1) 1.0
            else hist.takeLast(6).count { sign(it) == sign(inp.score) || abs(it) < 0.05 }.toDouble() / hist.takeLast(6).size
            val eff = w.getValue(inp.driver) * inp.confidence * (0.6 + 0.4 * persistence)
            DriverContribution(inp.driver, inp.score, w.getValue(inp.driver), inp.confidence, persistence, eff * inp.score)
        }
        if (commit) inputs.forEach { state.pushDriver(it.driver, it.score) }

        val effWeights = contributions.map { c -> c.weight * c.confidence * (0.6 + 0.4 * c.persistence) }
        val den = effWeights.sum()
        val d = if (den <= 0) 0.0 else M.clamp(contributions.sumOf { it.contribution } / den)
        val coverage = contributions.filter { it.confidence > 0 }.sumOf { it.weight } / w.values.sum().coerceAtLeast(1.0)

        // Conflict / agreement over drivers with a meaningful opinion.
        val opinions = contributions.zip(effWeights).filter { abs(it.first.score) > 0.1 && it.second > 0 }
        val pos = opinions.filter { it.first.score > 0 }.sumOf { it.second * abs(it.first.score) }
        val neg = opinions.filter { it.first.score < 0 }.sumOf { it.second * abs(it.first.score) }
        val conflict = if (pos + neg <= 0) 0.0 else 2 * minOf(pos, neg) / (pos + neg)
        val agreement = if (pos + neg <= 0) 0.0 else (if (d >= 0) pos else neg) / (pos + neg)
        val conflicts = opinions.filter { sign(it.first.score) != sign(d) && abs(it.first.score) > 0.3 }
            .map { "${it.first.driver.label} %+.2f vs composite %+.2f".format(it.first.score, d) }

        // Range evidence.
        val adxLow = if (range.adx.isNaN()) 0.5 else M.clamp((25 - range.adx) / 15, 0.0, 1.0)
        val vixCalm = when (range.vixState) { VixState.FALLING, VixState.STABLE -> 1.0; VixState.RISING -> 0.3; VixState.SPIKING -> 0.0 }
        val r = M.clamp(0.35 * (1 - abs(d)) + 0.25 * adxLow + 0.20 * range.wallRangeEvidence + 0.10 * vixCalm +
            0.10 * (if (range.insideOpeningRange) 1.0 else 0.0), 0.0, 1.0)

        val k = params.directionalGain * (1 - params.conflictDamping * conflict)
        val p = M.softmax(doubleArrayOf(k * d, -k * d, params.rangeGain * (r - params.rangeBaseline)))

        val price = contributions.firstOrNull { it.driver == Driver.PRICE }
        val deriv = contributions.firstOrNull { it.driver == Driver.DERIVATIVES }
        val priceConf = price != null && price.confidence > 0 && abs(price.score) > 0.15 && sign(price.score) == sign(d)
        val derivConf = deriv != null && deriv.confidence > 0 && abs(deriv.score) > 0.1 && sign(deriv.score) == sign(d)

        var cv = 0.35 * agreement + 0.20 * (1 - conflict) + 0.15 * coverage +
            0.15 * (if (priceConf) 1.0 else 0.0) + 0.15 * (if (derivConf) 1.0 else 0.0)
        if (regime == Regime.DIVERGENCE || regime == Regime.TRANSITION) cv -= 0.12
        if (regime == Regime.EVENT_SHOCK) cv -= 0.08
        val extraConflicts = ArrayList<String>()
        // Heavyweight concentration: a narrow, top-5-driven move deserves less confidence in broad direction.
        if (concentration != null && concentration.first > 0) { cv -= concentration.first; extraConflicts += concentration.second }
        cv = M.clamp(cv, 0.0, 1.0)
        val cap = M.clamp(qualityScore, 0.0, 1.0)
        if (cv > cap) { cv = cap; extraConflicts += "Confidence capped at %.0f%% by data quality".format(cap * 100) }
        val level = when { cv >= 0.72 -> ConfidenceLevel.HIGH; cv >= 0.5 -> ConfidenceLevel.MEDIUM; else -> ConfidenceLevel.LOW }
        val conflictLevel = when { conflict >= 0.55 -> ConfidenceLevel.HIGH; conflict >= 0.3 -> ConfidenceLevel.MEDIUM; else -> ConfidenceLevel.LOW }

        return DirectionResult(
            pBull = p[0], pBear = p[1], pRange = p[2],
            directionalScore = d, rangeScore = r,
            confidence = level, confidenceValue = cv,
            driverAgreement = agreement, conflict = conflict, conflictLevel = conflictLevel,
            priceConfirmation = priceConf, derivativeConfirmation = derivConf,
            drivers = contributions.sortedByDescending { abs(it.contribution) },
            conflicts = conflicts + extraConflicts,
            qualityCap = cap,
        )
    }
}

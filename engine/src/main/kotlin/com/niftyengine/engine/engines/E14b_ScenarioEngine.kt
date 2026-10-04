package com.niftyengine.engine.engines

import com.niftyengine.engine.core.M
import com.niftyengine.engine.model.FutureExpectation
import com.niftyengine.engine.model.HorizonProb
import com.niftyengine.engine.model.InformationShock
import com.niftyengine.engine.model.PrimaryRegime
import com.niftyengine.engine.model.RegimeAssessment
import com.niftyengine.engine.model.Scenario
import com.niftyengine.engine.model.ScenarioProb
import com.niftyengine.engine.model.ScenarioSet
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 14b — Scenario Engine (v5).
 *
 * "UP 65 %" can be a slow grind or a breakout — different trades. The engine splits the horizon's outcome into
 *   Bull breakout · Bull continuation (or reversal) · Range · Bear reversal (or continuation) · Sharp decline
 * by move size: |move| < range band (the same threshold the outcome logger uses), up to the breakout band
 * (1.25 normal σ), and beyond it.
 *
 *   P(up) and P(down) come from the direction engine; the share of each that is a breakout starts from a normal
 *   distribution around the probability-weighted drift and is tilted by evidence: momentum + ADX, opening-range /
 *   previous-day breaks, acceleration, futures short covering / buildup, the information shock, expectation change,
 *   volatility regime and scheduled-event risk (two-sided); range evidence pulls it back.
 *
 * The result is also a full move distribution ([ScenarioDistribution]) used to reprice options and strategies.
 * Scenario probabilities are calibrated against realised buckets once outcomes accumulate.
 */
class ScenarioEngine {
    data class Inputs(
        val spot: Double,
        val horizonMinutes: Int,
        /** Full 1σ move over the horizon (incl. event/vol multipliers), points. */
        val sigma: Double,
        val eventMultiplier: Double,
        /** Probabilities used for the split (calibrated / partially calibrated when available). */
        val probs: HorizonProb,
        /** Raw model scores (bull, bear, range) — what scenario calibration is fitted on. */
        val rawBull: Double,
        val rawBear: Double,
        val rawRange: Double,
        val pressure: Double,
        val adx: Double,
        val structureTags: List<String>,
        val trend: Double,
        val futuresDay: FuturesState,
        val futuresIntraday: FuturesState,
        val regime: RegimeAssessment,
        val shock: InformationShock,
        val expectation: FutureExpectation,
        val rangeEvidence: Double,
    )

    companion object {
        /** Range threshold — identical to [PredictionLogger.classThreshold] at the record's own horizon. */
        fun rangeBand(spot: Double, sigma: Double, h: Int) = maxOf(0.35 * sigma, spot * 0.0004 * sqrt(h / 15.0))

        /** Breakout = a clearly above-normal move: 1.25 × the normal (pre-event-multiplier) σ, at least 2 range bands. */
        fun breakoutBand(spot: Double, sigma: Double, eventMultiplier: Double, h: Int) =
            maxOf(2 * rangeBand(spot, sigma, h), 1.25 * sigma / eventMultiplier.coerceAtLeast(1.0))

        fun label(s: Scenario, trend: Int): String = when (s) {
            Scenario.STRONG_UP -> "Bull breakout"
            Scenario.MILD_UP -> when { trend > 0 -> "Bull continuation"; trend < 0 -> "Bull reversal"; else -> "Bull drift" }
            Scenario.RANGE -> "Range"
            Scenario.MILD_DOWN -> when { trend > 0 -> "Bear reversal"; trend < 0 -> "Bear continuation"; else -> "Bear drift" }
            Scenario.STRONG_DOWN -> "Sharp decline"
        }

        fun key(label: String) = label.lowercase().replace(' ', '_')

        /** Realised scenario for a move, given the bands. */
        fun realized(move: Double, rangeBand: Double, breakoutBand: Double): Scenario = when {
            move >= breakoutBand -> Scenario.STRONG_UP
            move > rangeBand -> Scenario.MILD_UP
            move <= -breakoutBand -> Scenario.STRONG_DOWN
            move < -rangeBand -> Scenario.MILD_DOWN
            else -> Scenario.RANGE
        }
    }

    data class Split(val p: Map<Scenario, Double>, val shareUp: Double, val shareDown: Double)

    private fun split(pB: Double, pD: Double, pR: Double, r: Double, b: Double, sigma: Double, evUp: Double, evDn: Double): Split {
        val mu = (pB - pD) * 0.8 * sigma
        val sd = sigma.coerceAtLeast(1e-6)
        val sUp = (1 - M.cdf(b, mu, sd)) / (1 - M.cdf(r, mu, sd)).coerceAtLeast(1e-9)
        val sDn = M.cdf(-b, mu, sd) / M.cdf(-r, mu, sd).coerceAtLeast(1e-9)
        val up = M.clamp(sUp * (1 + 0.8 * evUp), 0.03, 0.90)
        val dn = M.clamp(sDn * (1 + 0.8 * evDn), 0.03, 0.90)
        return Split(mapOf(
            Scenario.STRONG_UP to pB * up, Scenario.MILD_UP to pB * (1 - up), Scenario.RANGE to pR,
            Scenario.MILD_DOWN to pD * (1 - dn), Scenario.STRONG_DOWN to pD * dn,
        ), up, dn)
    }

    fun compute(i: Inputs, calibration: CalibrationModel = CalibrationModel()): ScenarioSet {
        val h = i.horizonMinutes
        val sigma = i.sigma.coerceAtLeast(1e-6)
        val r = rangeBand(i.spot, sigma, h)
        val b = breakoutBand(i.spot, sigma, i.eventMultiplier, h)
        val notes = ArrayList<String>()

        // ---- breakout evidence per side
        val adxF = when { i.adx.isNaN() -> 0.6; i.adx >= 25 -> 1.0; i.adx < 18 -> 0.4; else -> 0.7 }
        val tags = i.structureTags.toSet()
        val momUp = M.clamp(i.pressure, 0.0, 1.0) * adxF
        val momDn = M.clamp(-i.pressure, 0.0, 1.0) * adxF
        val tagUp = (if ("OR_BREAKOUT" in tags || "ABOVE_PDH" in tags) 0.6 else 0.0) + (if ("ACCELERATING_UP" in tags) 0.4 else 0.0)
        val tagDn = (if ("OR_BREAKDOWN" in tags || "BELOW_PDL" in tags) 0.6 else 0.0) + (if ("ACCELERATING_DOWN" in tags) 0.4 else 0.0)
        val vol = if (i.regime.primary == PrimaryRegime.VOLATILITY_EXPANSION || i.regime.primary == PrimaryRegime.EVENT_DRIVEN) 1.0 else 0.0
        val shkUp = i.shock.score * M.clamp(i.shock.direction, 0.0, 1.0)
        val shkDn = i.shock.score * M.clamp(-i.shock.direction, 0.0, 1.0)
        fun fut(st: FuturesState, up: Boolean) = when (st) {
            FuturesState.SHORT_COVERING -> if (up) 1.0 else 0.0
            FuturesState.LONG_BUILDUP -> if (up) 0.6 else 0.0
            FuturesState.SHORT_BUILDUP -> if (up) 0.0 else 0.6
            FuturesState.LONG_UNWINDING -> if (up) 0.0 else 0.5
            FuturesState.NEUTRAL -> 0.0
        }
        val futUp = maxOf(fut(i.futuresDay, true), fut(i.futuresIntraday, true))
        val futDn = maxOf(fut(i.futuresDay, false), fut(i.futuresIntraday, false))
        val expUp = M.clamp(i.expectation.change / 0.4, 0.0, 1.0)
        val expDn = M.clamp(-i.expectation.change / 0.4, 0.0, 1.0)
        val twoSided = i.expectation.eventRiskAhead
        val evUp = M.clamp(0.35 * momUp + 0.25 * tagUp + 0.15 * vol + 0.25 * shkUp + 0.15 * futUp + 0.10 * expUp + 0.15 * twoSided - 0.30 * i.rangeEvidence)
        val evDn = M.clamp(0.35 * momDn + 0.25 * tagDn + 0.15 * vol + 0.25 * shkDn + 0.15 * futDn + 0.10 * expDn + 0.15 * twoSided - 0.30 * i.rangeEvidence)
        if (i.shock.score >= 0.15) notes += "Tails re-planned for the information shock (%.0f%%, %s)".format(i.shock.score * 100,
            if (i.shock.direction > 0.2) "bullish" else if (i.shock.direction < -0.2) "bearish" else "two-sided")
        if (twoSided >= 0.6) notes += "Scheduled event ahead fattens both tails"

        // ---- raw split (from raw model scores) and the split actually used
        val rawSplit = split(i.rawBull, i.rawBear, i.rawRange, r, b, sigma, evUp, evDn)
        val usedProbs = i.probs
        val baseSplit = if (usedProbs.calibrated || usedProbs.partial) split(usedProbs.pBull, usedProbs.pBear, usedProbs.pRange, r, b, sigma, evUp, evDn) else rawSplit
        val cal = calibration.applyScenarios(rawSplit.p)
        val final = cal?.first ?: baseSplit.p
        val calLabel = when {
            cal != null -> cal.second
            usedProbs.calibrated || usedProbs.partial -> "VIA_DIRECTION"
            else -> "NONE"
        }
        val mu = (usedProbs.pBull - usedProbs.pBear) * 0.8 * sigma
        val trend = when {
            i.regime.primary.bias != 0 -> i.regime.primary.bias
            abs(i.trend) >= 0.3 -> if (i.trend > 0) 1 else -1
            else -> 0
        }
        val bounds = mapOf(
            Scenario.STRONG_UP to (b to Double.POSITIVE_INFINITY), Scenario.MILD_UP to (r to b), Scenario.RANGE to (-r to r),
            Scenario.MILD_DOWN to (-b to -r), Scenario.STRONG_DOWN to (Double.NEGATIVE_INFINITY to -b),
        )
        val rows = Scenario.values().map { sc ->
            val (lo, hi) = bounds.getValue(sc)
            val lbl = label(sc, trend)
            ScenarioProb(sc, lbl, key(lbl), final.getValue(sc), rawSplit.p.getValue(sc), lo, hi, M.truncMean(lo, hi, mu, sigma))
        }
        return ScenarioSet(h, i.spot, rows, r, b, mu, sigma, baseSplit.shareUp, baseSplit.shareDown, calLabel, notes)
    }
}

/**
 * Move distribution over the horizon implied by a [ScenarioSet]: each scenario bucket carries its probability, and
 * within a bucket the move follows N(center, sigma) truncated to the bucket.
 */
class ScenarioDistribution(set: ScenarioSet) {
    private data class Bucket(val lo: Double, val hi: Double, val p: Double, val fallback: Double)

    private val mu = set.center
    private val sd = set.sigma.coerceAtLeast(1e-6)
    private val buckets = set.scenarios.filter { it.probability > 0 }.map { Bucket(it.moveFrom, it.moveTo, it.probability, it.meanMove) }
    private val total = buckets.sumOf { it.p }.coerceAtLeast(1e-12)

    val isEmpty: Boolean get() = buckets.isEmpty()

    /** P(move > x). */
    fun probAbove(x: Double): Double = buckets.sumOf { bk ->
        val w = bk.p / total
        when {
            x >= bk.hi -> 0.0
            x <= bk.lo -> w
            else -> {
                val fLo = M.cdf(bk.lo, mu, sd); val fHi = M.cdf(bk.hi, mu, sd)
                if (fHi - fLo < 1e-12) (if (bk.fallback > x) w else 0.0)
                else w * ((fHi - M.cdf(x, mu, sd)) / (fHi - fLo)).coerceIn(0.0, 1.0)
            }
        }
    }

    fun probBelow(x: Double): Double = 1 - probAbove(x)

    /** Quadrature points (move, weight) — [n] quantile points per bucket. */
    fun points(n: Int = 7): List<Pair<Double, Double>> = buckets.flatMap { bk ->
        val w = bk.p / total
        val fLo = M.cdf(bk.lo, mu, sd); val fHi = M.cdf(bk.hi, mu, sd)
        if (fHi - fLo < 1e-9) listOf(bk.fallback to w)
        else (0 until n).map { k ->
            val u = fLo + (fHi - fLo) * (k + 0.5) / n
            (mu + sd * M.normInv(u)).coerceIn(if (bk.lo.isInfinite()) -1e9 else bk.lo, if (bk.hi.isInfinite()) 1e9 else bk.hi) to w / n
        }
    }

    private val grid by lazy { points() }

    fun expect(f: (Double) -> Double): Double = grid.sumOf { (x, w) -> w * f(x) }

    /** P(f(move) > 0) over the quadrature grid. */
    fun probPositive(f: (Double) -> Double): Double = grid.sumOf { (x, w) -> if (f(x) > 0) w else 0.0 }

    /** E[f | f > 0] ÷ |E[f | f < 0]| (NaN if one side is empty). */
    fun gainLossRatio(f: (Double) -> Double): Double {
        var gw = 0.0; var g = 0.0; var lw = 0.0; var l = 0.0
        for ((x, w) in grid) { val v = f(x); if (v > 0) { g += w * v; gw += w } else if (v < 0) { l += w * v; lw += w } }
        if (gw <= 0 || lw <= 0) return if (gw > 0) 9.99 else Double.NaN
        return (g / gw) / abs(l / lw)
    }
}

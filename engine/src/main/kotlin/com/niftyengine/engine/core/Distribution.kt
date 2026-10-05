package com.niftyengine.engine.core

import com.niftyengine.engine.model.DistComponent
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * §19 — price distribution at a horizon: a mixture of log-normals in S_T / S_0.
 * The main component carries the model drift; on expiry horizons an optional narrow "pin" component sits on the
 * OI concentration strike. Everything downstream (direction probability, ranges, buckets, option values, strategy
 * P&L) is computed from this one distribution so the numbers are mutually consistent.
 */
class PriceDistribution(val spot: Double, val comps: List<DistComponent>) {
    init { require(spot > 0 && comps.isNotEmpty()) }

    private val wSum = comps.sumOf { it.weight }.takeIf { it > 0 } ?: 1.0

    fun cdf(x: Double): Double {
        if (x <= 0) return 0.0
        val lx = ln(x / spot)
        return comps.sumOf { c -> c.weight * M.normCdf((lx - c.mu) / c.sigma.coerceAtLeast(1e-9)) } / wSum
    }

    fun pAbove(x: Double) = 1 - cdf(x)
    fun pBelow(x: Double) = cdf(x)
    fun pBetween(a: Double, b: Double) = (cdf(b) - cdf(a)).coerceAtLeast(0.0)

    fun quantile(p: Double): Double {
        val sMax = comps.maxOf { it.sigma }
        val muLo = comps.minOf { it.mu }; val muHi = comps.maxOf { it.mu }
        var lo = spot * exp(muLo - 8 * sMax); var hi = spot * exp(muHi + 8 * sMax)
        repeat(80) {
            val mid = (lo + hi) / 2
            if (cdf(mid) < p) lo = mid else hi = mid
        }
        return (lo + hi) / 2
    }

    /** Discretised grid (price, probability mass) covering 0.05 %–99.95 %, built once. */
    val grid: List<Pair<Double, Double>> by lazy {
        val lo = quantile(0.0005); val hi = quantile(0.9995)
        val n = 600
        val step = (hi - lo) / n
        val out = ArrayList<Pair<Double, Double>>(n + 2)
        // tails lumped at the edges so masses sum to 1
        out += lo to cdf(lo + step / 2)
        for (i in 1 until n) {
            val x = lo + i * step
            out += x to (cdf(x + step / 2) - cdf(x - step / 2)).coerceAtLeast(0.0)
        }
        out += hi to (1 - cdf(hi - step / 2)).coerceAtLeast(0.0)
        out
    }

    fun expectation(f: (Double) -> Double): Double = grid.sumOf { (x, p) -> p * f(x) }

    fun probability(pred: (Double) -> Boolean): Double = grid.sumOf { (x, p) -> if (pred(x)) p else 0.0 }

    val mean: Double get() = expectation { it }

    /** Effective 1σ in points (from the 16th/84th percentiles). */
    val sigmaPts: Double get() = (quantile(0.8413) - quantile(0.1587)) / 2

    companion object {
        /**
         * Main log-normal with drift [driftPts] and 1σ [sigmaPts] (points), optionally mixed with a pin component
         * at [pinStrike] holding weight [pinWeight] and 0.35σ spread.
         */
        fun build(spot: Double, sigmaPts: Double, driftPts: Double, pinStrike: Double = Double.NaN, pinWeight: Double = 0.0): PriceDistribution {
            val sig = if (sigmaPts.isNaN() || sigmaPts <= 0) spot * 0.01 else sigmaPts
            val drift = if (driftPts.isNaN()) 0.0 else driftPts
            val s = max(sig, spot * 1e-5) / spot
            val mu = ln(max(spot + drift, spot * 0.2) / spot)
            val comps = mutableListOf(DistComponent(1.0 - pinWeight.coerceIn(0.0, 0.6), mu, s))
            if (!pinStrike.isNaN() && pinStrike > 0 && pinWeight > 0) comps += DistComponent(pinWeight.coerceIn(0.0, 0.6), ln(pinStrike / spot), min(s * 0.35, s))
            return PriceDistribution(spot, comps)
        }
    }
}

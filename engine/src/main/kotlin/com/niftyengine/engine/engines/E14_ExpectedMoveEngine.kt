package com.niftyengine.engine.engines

import com.niftyengine.engine.core.M
import com.niftyengine.engine.core.Session
import com.niftyengine.engine.model.DirectionResult
import com.niftyengine.engine.model.Detail
import com.niftyengine.engine.model.ExpectedMove
import com.niftyengine.engine.model.MoveProb
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 14 — Expected Move Engine (magnitude, not just direction).
 *
 * σ_annual = blend(India VIX, ATM IV, realised 5m vol, 20-day historical vol) × event multiplier
 * σ_h      = spot × σ_annual × √(h / (375 × 252))      (h in trading minutes)
 * Conditional move in the favoured direction ≈ E|X| = 0.8 σ_h, scaled by directional strength/momentum.
 */
class ExpectedMoveEngine {
    data class Inputs(
        val spot: Double,
        val horizonMinutes: Int,
        val vix: Double,
        val atmIv: Double,
        val realizedVol: Double,
        val histVol: Double,
        val eventShock: Boolean,
        val vixState: VixState,
        val freshMajorEvent: Boolean,
        val globalStress: Double,
        val momentum: Double,
        /** v5 information-shock score 0..1: a fresh shock widens the distribution immediately. */
        val shockScore: Double = 0.0,
    )

    fun compute(i: Inputs, dir: DirectionResult): ExpectedMove {
        val comps = mutableListOf<Detail>()
        val parts = mutableListOf<Pair<Double, Double>>()
        fun add(name: String, v: Double, w: Double) {
            if (!v.isNaN() && v > 0.01 && v < 2.0) { parts += w to v; comps += Detail(name, "%.1f%%".format(v * 100)) }
        }
        add("India VIX", i.vix / 100, 0.30)
        add("ATM IV", i.atmIv / 100, 0.30)
        add("Realised (5m)", i.realizedVol, 0.25)
        add("Historical (20d)", i.histVol, 0.15)
        val annual = if (parts.isEmpty()) 0.14 else parts.sumOf { it.first * it.second } / parts.sumOf { it.first }

        var mult = 1.0
        if (i.eventShock) mult *= 1.4
        if (i.vixState == VixState.SPIKING) mult *= 1.25 else if (i.vixState == VixState.RISING) mult *= 1.08
        if (i.freshMajorEvent) mult *= 1.15
        mult *= 1 + 0.2 * i.globalStress
        if (i.shockScore > 0.05) mult *= 1 + 0.5 * i.shockScore
        mult = mult.coerceAtMost(2.5)
        comps += Detail("Event/vol multiplier", "×%.2f".format(mult))

        val h = i.horizonMinutes.coerceAtLeast(5)
        val sigma = i.spot * annual * mult * sqrt(h / (Session.SESSION_MINUTES * Session.TRADING_DAYS))
        val fav = if (dir.pBull >= dir.pBear) 1.0 else -1.0
        val strength = 1 + 0.3 * abs(dir.directionalScore) + 0.15 * M.clamp(abs(i.momentum), 0.0, 1.0)
        val center = 0.8 * sigma * strength
        val drift = (dir.pBull - dir.pBear) * 0.8 * sigma
        return ExpectedMove(
            horizonMinutes = h,
            sigmaPoints = sigma,
            expectedMovePoints = fav * center,
            moveLow = fav * 0.5 * sigma,
            moveHigh = fav * 1.3 * sigma * strength,
            rangeLow = i.spot + drift - sigma,
            rangeHigh = i.spot + drift + sigma,
            annualVolUsed = annual * mult,
            eventMultiplier = mult,
            components = comps,
            // Move distribution: normal around the probability-weighted drift (model-based, not yet calibrated).
            thresholds = listOf(50, 100, 150, 200).map { k ->
                MoveProb(k, 1 - M.normCdf((k - drift) / sigma), M.normCdf((-k - drift) / sigma))
            },
        )
    }
}

package com.niftyengine.engine.engines

import com.niftyengine.engine.core.M
import com.niftyengine.engine.model.DirectionResult
import com.niftyengine.engine.model.HorizonProb
import com.niftyengine.engine.model.QualityComponent
import com.niftyengine.engine.model.QualityTier
import com.niftyengine.engine.model.RegimeAssessment
import com.niftyengine.engine.model.ShadowBook
import com.niftyengine.engine.model.StrategyCandidate
import com.niftyengine.engine.model.StrategyType
import com.niftyengine.engine.model.TradeQuality
import kotlin.math.exp
import kotlin.math.ln

/**
 * 25 — Trade Quality Gate (v5). A 74 % probability is not a trade on its own:
 *
 *   quality = geometric mean of  probability edge · model confidence · regime quality · liquidity · risk/reward
 *
 * and every component must clear its floor (weakest link). Example: 74 % · 86 % confidence · 81 % regime ·
 * 94 % liquidity · R:R 1.8 ⇒ HIGH; the same 74 % with 52 % confidence and a 43 % regime ⇒ NO TRADE.
 * Once a strategy has enough shadow trades, its realised R-multiple nudges the score (track record).
 */
class TradeQualityEngine(private val p: Params = Params()) {
    data class Params(
        val minScore: Double = 0.65,
        val floorEdge: Double = 0.30,
        val floorConfidence: Double = 0.50,
        val floorRegime: Double = 0.50,
        val floorLiquidity: Double = 0.50,
        val floorRiskReward: Double = 0.40,
        val minTradesForTrackRecord: Int = 8,
    )

    fun assess(dir: DirectionResult, probs: HorizonProb, regime: RegimeAssessment, strategy: StrategyCandidate?, book: ShadowBook): TradeQuality {
        if (strategy == null || strategy.type == StrategyType.NO_TRADE)
            return TradeQuality(notes = listOf("No strategy selected — nothing to grade"))
        val directional = strategy.type.directional
        val (prob, probLabel) = when {
            directional > 0 -> probs.pBull to "bull %.0f%%".format(probs.pBull * 100)
            directional < 0 -> probs.pBear to "bear %.0f%%".format(probs.pBear * 100)
            else -> strategy.probProfit to "P(stay inside) %.0f%%".format(strategy.probProfit * 100)
        }
        val edge = M.clamp((prob - 0.5) / 0.3, 0.0, 1.0)
        val rrMetric = if (strategy.riskReward.isNaN()) 0.0 else strategy.riskReward
        val comps = listOf(
            QualityComponent("Probability edge", edge, p.floorEdge, "$probLabel ${if (probs.calibrated) "calibrated" else if (probs.partial) "partly calibrated" else "model score"}"),
            QualityComponent("Model confidence", M.clamp(dir.confidenceValue, 0.0, 1.0), p.floorConfidence, "${dir.confidence} (%.0f%%)".format(dir.confidenceValue * 100)),
            QualityComponent("Regime quality", regime.quality, p.floorRegime, "${regime.primary.label} (%.0f%%, stability %.0f%%)".format(regime.quality * 100, regime.stability * 100)),
            QualityComponent("Liquidity", strategy.liquidity, p.floorLiquidity, "worst leg %.0f%%".format(strategy.liquidity * 100)),
            QualityComponent("Risk / reward", M.clamp(rrMetric / 2.0, 0.0, 1.0), p.floorRiskReward, "expected R:R %.2f".format(rrMetric)),
        )
        val gm = exp(comps.sumOf { ln(it.value.coerceAtLeast(1e-3)) } / comps.size)

        // track record in shadow mode (R multiples shrunk toward zero with few trades): this strategy, and — v5.1 —
        // this regime, so the engine becomes more selective where it has been losing
        fun factor(l: List<com.niftyengine.engine.model.ShadowTrade>) =
            if (l.size < p.minTradesForTrackRecord) 1.0 else M.clamp(1 + 0.5 * l.sumOf { it.rMultiple } / (l.size + 10), 0.75, 1.10)
        val mine = book.closed.filter { it.position.strategy == strategy.type }
        val inRegime = book.closed.filter { it.position.regime == regime.primary.name }
        val stratF = factor(mine)
        val regimeF = factor(inRegime)
        val track = M.clamp(stratF * regimeF, 0.6, 1.15)
        val score = M.clamp(gm * track, 0.0, 1.0)
        val below = comps.filter { it.value < it.floor }
        val tier = when { score >= 0.75 -> QualityTier.HIGH; score >= 0.62 -> QualityTier.MEDIUM; else -> QualityTier.LOW }
        val notes = ArrayList<String>()
        below.forEach { notes += "${it.name} " + "%.0f%% below floor %.0f%%".format(it.value * 100, it.floor * 100) + " (${it.detail})" }
        if (stratF != 1.0) notes += "Strategy track record ×%.2f from ${mine.size} shadow ${strategy.type.label} trades".format(stratF)
        if (regimeF != 1.0) notes += "Regime track record ×%.2f from ${inRegime.size} shadow trades in ${regime.primary.label}".format(regimeF)
        val passed = below.isEmpty() && score >= p.minScore
        if (!passed && below.isEmpty()) notes += "Score %.0f%% below minimum %.0f%%".format(score * 100, p.minScore * 100)
        return TradeQuality(score, tier, comps, track, passed, comps.minBy { it.value / it.floor.coerceAtLeast(1e-6) }.name, notes)
    }
}

package com.niftyengine.engine.engines

import com.niftyengine.engine.model.Check
import com.niftyengine.engine.model.ConfidenceLevel
import com.niftyengine.engine.model.Decision
import com.niftyengine.engine.model.DirectionResult
import com.niftyengine.engine.model.ExpectedMove
import com.niftyengine.engine.model.OptionAnalysis
import com.niftyengine.engine.model.Regime
import com.niftyengine.engine.model.RegimeResult
import com.niftyengine.engine.model.TradeDecision
import kotlin.math.abs

/**
 * 16 — Trade Decision Engine.
 *
 * TRADE only if: direction probability ≥ threshold AND confidence ≥ required AND expected move large
 * enough AND option liquidity/spread acceptable AND positive expected value AND no immediate event
 * risk AND driver conflict not high. Otherwise WAIT (edge exists but conditions fail) or
 * NO_TRADE (no directional edge). Saying "no edge" is a valid, expected output.
 */
class TradeDecisionEngine(private val p: Params = Params()) {
    data class Params(
        val minProbability: Double = 0.65,
        val minConfidence: ConfidenceLevel = ConfidenceLevel.HIGH,
        val minExpectedMovePts: Double = 40.0,
        val requireMarketOpen: Boolean = true,
        val minExpectedReturnPct: Double = 5.0,
    )

    fun decide(
        dir: DirectionResult, regime: RegimeResult, move: ExpectedMove, opt: OptionAnalysis,
        marketOpen: Boolean, recentShockMinutes: Double?,
    ): TradeDecision {
        val dirProb = maxOf(dir.pBull, dir.pBear)
        val side = if (dir.pBull >= dir.pBear) "BULL" else "BEAR"
        val best = opt.best
        val checks = listOf(
            Check("Direction probability", dirProb >= p.minProbability && dirProb > dir.pRange,
                "$side %.0f%% (need ≥ %.0f%%, range %.0f%%)".format(dirProb * 100, p.minProbability * 100, dir.pRange * 100)),
            Check("Confidence", dir.confidence.ordinal >= p.minConfidence.ordinal,
                "${dir.confidence} (%.2f), need ${p.minConfidence}".format(dir.confidenceValue)),
            Check("Driver conflict", dir.conflictLevel != ConfidenceLevel.HIGH, "${dir.conflictLevel} (%.0f%%)".format(dir.conflict * 100)),
            Check("Price confirmation", dir.priceConfirmation, if (dir.priceConfirmation) "price agrees" else "price not confirming"),
            Check("Expected move", abs(move.expectedMovePoints) >= p.minExpectedMovePts,
                "%+.0f pts (need ≥ %.0f)".format(move.expectedMovePoints, p.minExpectedMovePts)),
            Check("Option liquidity / spread", best != null,
                best?.let { "${it.strike.toInt()} ${it.type} spread %.1f%%, OI %,.0f".format(it.spreadPct, it.oi) } ?: "no strike passed filters"),
            Check("Option expected value", best != null && best.expectedReturnPct >= p.minExpectedReturnPct,
                best?.let { "EV %+.1f%% of premium".format(it.expectedReturnPct) } ?: "–"),
            Check("Immediate event risk", regime.regime != Regime.EVENT_SHOCK && (recentShockMinutes == null || recentShockMinutes > 15),
                if (regime.regime == Regime.EVENT_SHOCK) "event shock regime" else recentShockMinutes?.let { "major event %.0f min ago".format(it) } ?: "none"),
            Check("Market session", !p.requireMarketOpen || marketOpen, if (marketOpen) "open" else "closed"),
        )
        val failed = checks.filter { !it.passed }
        val noEdge = checks[0].passed.not() && (dirProb < 0.5 || dir.pRange >= dirProb)
        val decision = when {
            failed.isEmpty() -> Decision.TRADE
            noEdge -> Decision.NO_TRADE
            else -> Decision.WAIT
        }
        val headline = when (decision) {
            Decision.TRADE -> "TRADE · BUY ${best!!.strike.toInt()} ${best.type} @ ₹%.1f".format(best.premium)
            Decision.NO_TRADE -> "NO TRADE · no directional edge"
            Decision.WAIT -> "WAIT · $side bias, ${failed.size} check(s) failing"
        }
        return TradeDecision(decision, headline, failed.map { "${it.name}: ${it.detail}" }, checks)
    }
}

package com.niftyengine.engine.engines

import com.niftyengine.engine.model.Check
import com.niftyengine.engine.model.ConfidenceLevel
import com.niftyengine.engine.model.DataQualityReport
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
 *  • Circuit breaker first: missing/stale/invalid critical data ⇒ DATA_ERROR (never "recover with assumptions").
 *  • Direction check uses CALIBRATED probabilities for the decision horizon when available.
 *  • Option check is separate: the option's own P(profit) and expected value, both net of costs.
 *  • Event regime raises the probability threshold; data quality must clear a minimum.
 *  • Hysteresis: the setup must hold for N consecutive cycles before TRADE.
 *  • If every check passes except calibration ⇒ PAPER_TRADE (log/paper-trade only, no real money).
 */
class TradeDecisionEngine(private val p: Params = Params()) {
    data class Params(
        val minProbability: Double = 0.65,
        val minConfidence: ConfidenceLevel = ConfidenceLevel.HIGH,
        val minExpectedMovePts: Double = 40.0,
        val requireMarketOpen: Boolean = true,
        val minExpectedReturnPct: Double = 5.0,
        val minOptionProfitProb: Double = 0.50,
        val eventThresholdBump: Double = 0.08,
        val minDataQuality: Double = 0.70,
        val confirmCycles: Int = 2,
        val requireCalibration: Boolean = true,
    )

    private var streakSide = 0
    private var streak = 0

    fun decide(
        dir: DirectionResult, regime: RegimeResult, move: ExpectedMove, opt: OptionAnalysis,
        marketOpen: Boolean, recentShockMinutes: Double?, quality: DataQualityReport = DataQualityReport(),
        majorEventRisk: Boolean = false,
    ): TradeDecision {
        if (quality.circuitBreaker.isNotEmpty()) {
            streak = 0; streakSide = 0
            return TradeDecision(Decision.DATA_ERROR, "DATA ERROR — NO TRADE", quality.circuitBreaker,
                quality.circuitBreaker.map { Check("Data integrity", false, it) })
        }
        val probs = dir.decisionProbs(move.horizonMinutes)
        val dirProb = maxOf(probs.pBull, probs.pBear)
        val sideInt = if (probs.pBull >= probs.pBear) 1 else -1
        val side = if (sideInt > 0) "BULL" else "BEAR"
        val eventRisk = regime.regime == Regime.EVENT_SHOCK || majorEventRisk
        val minProb = p.minProbability + if (eventRisk) p.eventThresholdBump else 0.0
        val best = opt.best
        val optProb = best?.let { if (!it.probProfitCalibrated.isNaN()) it.probProfitCalibrated else it.probProfit }
        val probLabel = if (probs.calibrated) "calibrated" else "model score"

        val core = listOf(
            Check("Direction probability", dirProb >= minProb && dirProb > probs.pRange,
                "$side %.0f%% $probLabel (need ≥ %.0f%%%s, range %.0f%%)".format(dirProb * 100, minProb * 100,
                    if (eventRisk) " — event regime" else "", probs.pRange * 100)),
            Check("Confidence", dir.confidence.ordinal >= p.minConfidence.ordinal,
                "${dir.confidence} (%.2f), need ${p.minConfidence}".format(dir.confidenceValue)),
            Check("Driver conflict", dir.conflictLevel != ConfidenceLevel.HIGH, "${dir.conflictLevel} (%.0f%%)".format(dir.conflict * 100)),
            Check("Price confirmation", dir.priceConfirmation, if (dir.priceConfirmation) "price agrees" else "price not confirming"),
            Check("Expected move", abs(move.expectedMovePoints) >= p.minExpectedMovePts,
                "%+.0f pts (need ≥ %.0f)".format(move.expectedMovePoints, p.minExpectedMovePts)),
            Check("Option liquidity / spread", best != null,
                best?.let { "${it.strike.toInt()} ${it.type} spread %.1f%%, OI %,.0f".format(it.spreadPct, it.oi) } ?: "no strike passed filters"),
            Check("Option P(profit), net", optProb != null && optProb >= p.minOptionProfitProb,
                optProb?.let { "%.0f%% %s (need ≥ %.0f%%)".format(it * 100, if (best!!.probProfitCalibrated.isNaN()) "model" else "calibrated", p.minOptionProfitProb * 100) } ?: "–"),
            Check("Option expected value, net", best != null && best.expectedReturnPct >= p.minExpectedReturnPct,
                best?.let { "EV %+.1f%% after ₹%.2f/unit costs".format(it.expectedReturnPct, it.costPerUnit) } ?: "–"),
            Check("Immediate event risk", regime.regime != Regime.EVENT_SHOCK && (recentShockMinutes == null || recentShockMinutes > 15),
                if (regime.regime == Regime.EVENT_SHOCK) "event shock regime" else recentShockMinutes?.let { "major event %.0f min ago".format(it) } ?: "none"),
            Check("Data quality", quality.score >= p.minDataQuality, "%.0f%% (need ≥ %.0f%%)".format(quality.score * 100, p.minDataQuality * 100)),
            Check("Market session", !p.requireMarketOpen || marketOpen, if (marketOpen) "open" else "closed"),
        )
        // Hysteresis: count consecutive cycles where the core setup holds on the same side.
        val coreOk = core.all { it.passed }
        if (coreOk && sideInt == streakSide) streak++ else if (coreOk) { streakSide = sideInt; streak = 1 } else { streak = 0; streakSide = 0 }
        val persist = Check("Signal persistence", streak >= p.confirmCycles, "$streak / ${p.confirmCycles} consecutive cycles")
        val calib = Check("Probability calibrated", !p.requireCalibration || probs.calibrated,
            if (probs.calibrated) "yes (${move.horizonMinutes}m)" else dir.calibration.note)
        val checks = core + persist + calib
        val failed = checks.filter { !it.passed }

        val noEdge = !core[0].passed && (dirProb < 0.5 || probs.pRange >= dirProb)
        val decision = when {
            failed.isEmpty() -> Decision.TRADE
            failed.size == 1 && failed[0] === calib -> Decision.PAPER_TRADE
            noEdge -> Decision.NO_TRADE
            else -> Decision.WAIT
        }
        val headline = when (decision) {
            Decision.TRADE -> "TRADE · BUY ${best!!.strike.toInt()} ${best.type} @ ₹%.1f".format(best.premium)
            Decision.PAPER_TRADE -> "PAPER TRADE · ${best!!.strike.toInt()} ${best.type} @ ₹%.1f (uncalibrated — no real money)".format(best.premium)
            Decision.NO_TRADE -> "NO TRADE · no directional edge"
            Decision.WAIT -> "WAIT · $side bias, ${failed.size} check(s) failing"
            Decision.DATA_ERROR -> "DATA ERROR — NO TRADE"
        }
        return TradeDecision(decision, headline, failed.map { "${it.name}: ${it.detail}" }, checks)
    }
}

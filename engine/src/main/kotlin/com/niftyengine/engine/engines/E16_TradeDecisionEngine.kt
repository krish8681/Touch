package com.niftyengine.engine.engines

import com.niftyengine.engine.model.Check
import com.niftyengine.engine.model.ConfidenceLevel
import com.niftyengine.engine.model.DataQualityReport
import com.niftyengine.engine.model.Decision
import com.niftyengine.engine.model.DirectionResult
import com.niftyengine.engine.model.ExpectedMove
import com.niftyengine.engine.model.HorizonProb
import com.niftyengine.engine.model.LegAction
import com.niftyengine.engine.model.OptionAnalysis
import com.niftyengine.engine.model.PrimaryRegime
import com.niftyengine.engine.model.Regime
import com.niftyengine.engine.model.RegimeAssessment
import com.niftyengine.engine.model.RegimeResult
import com.niftyengine.engine.model.RiskAssessment
import com.niftyengine.engine.model.ScenarioSet
import com.niftyengine.engine.model.StrategyPlan
import com.niftyengine.engine.model.StrategyType
import com.niftyengine.engine.model.TradeDecision
import com.niftyengine.engine.model.TradeQuality
import kotlin.math.abs

/**
 * 16 — Trade Decision Engine (v5).
 *
 *   DATA → REGIME → PROBABILITY (calibrated when available) → TRADE QUALITY → STRATEGY → RISK → TRADE / WAIT
 *
 *  • Circuit breaker first: missing/stale/invalid critical data ⇒ DATA_ERROR.
 *  • CONFLICT regime ⇒ WAIT (the engine never forces UP/DOWN); reversal risk against the trade fails the regime check.
 *  • Directional structures need the direction probability (event regimes raise the bar), price confirmation and an
 *    expected move; the iron condor needs range to dominate and the move to stay inside its short strikes.
 *  • The chosen structure's own P(profit) and EV (net of costs) are checked separately from direction.
 *  • Trade quality gate and the deterministic risk engine must both pass.
 *  • Hysteresis: the same strategy must hold for N consecutive cycles before TRADE.
 *  • Everything passes except calibration ⇒ PAPER_TRADE (shadow-trade only, no real money).
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

    private var streakKey = -1
    private var streak = 0

    data class V5(
        val regime: RegimeAssessment,
        val scenarios: ScenarioSet,
        val strategy: StrategyPlan,
        val quality: TradeQuality,
        val risk: RiskAssessment,
    )

    fun decide(
        dir: DirectionResult, regime: RegimeResult, move: ExpectedMove, opt: OptionAnalysis,
        marketOpen: Boolean, recentShockMinutes: Double?, quality: DataQualityReport = DataQualityReport(),
        majorEventRisk: Boolean = false, v5: V5? = null,
    ): TradeDecision {
        if (quality.circuitBreaker.isNotEmpty()) {
            streak = 0; streakKey = -1
            return TradeDecision(Decision.DATA_ERROR, "DATA ERROR — NO TRADE", quality.circuitBreaker,
                quality.circuitBreaker.map { Check("Data integrity", false, it) })
        }
        val probs: HorizonProb = dir.decisionProbs(move.horizonMinutes)
        val plan = v5?.strategy ?: StrategyPlan()
        val chosen = plan.chosen
        val type = chosen?.type ?: plan.preferredByRegime
        val condor = type == StrategyType.IRON_CONDOR
        val sideInt = when {
            type.directional != 0 -> type.directional
            condor -> 0
            probs.pBull >= probs.pBear -> 1
            else -> -1
        }
        val side = when (sideInt) { 1 -> "BULL"; -1 -> "BEAR"; else -> "RANGE" }
        val dirProb = when (sideInt) { 1 -> probs.pBull; -1 -> probs.pBear; else -> probs.pRange }
        val eventRisk = regime.regime == Regime.EVENT_SHOCK || majorEventRisk || v5?.regime?.primary == PrimaryRegime.EVENT_DRIVEN
        val minProb = p.minProbability + if (eventRisk) p.eventThresholdBump else 0.0
        val probLabel = when { probs.calibrated -> "calibrated"; probs.partial -> "partly calibrated"; else -> "model score" }
        val rg = v5?.regime

        val checks = ArrayList<Check>()
        if (rg != null) checks += Check("Regime", rg.primary != PrimaryRegime.CONFLICT &&
            !(rg.primary == PrimaryRegime.REVERSAL_RISK && sideInt != 0 && rg.reversalSide == sideInt),
            "${rg.primary.label} · quality %.0f%%".format(rg.quality * 100) + when {
                rg.primary == PrimaryRegime.CONFLICT -> " — blocks disagree, wait"
                rg.primary == PrimaryRegime.REVERSAL_RISK && rg.reversalSide == sideInt && sideInt != 0 -> " — trend at risk of reversing"
                else -> ""
            })
        if (condor) {
            checks += Check("Range probability", probs.pRange >= maxOf(probs.pBull, probs.pBear),
                "range %.0f%% $probLabel vs bull %.0f%% / bear %.0f%%".format(probs.pRange * 100, probs.pBull * 100, probs.pBear * 100))
        } else {
            checks += Check("Direction probability", dirProb >= minProb && dirProb > probs.pRange,
                "$side %.0f%% $probLabel (need ≥ %.0f%%%s, range %.0f%%)".format(dirProb * 100, minProb * 100,
                    if (eventRisk) " — event regime" else "", probs.pRange * 100))
        }
        checks += Check("Confidence", dir.confidence.ordinal >= p.minConfidence.ordinal,
            "${dir.confidence} (%.2f), need ${p.minConfidence}".format(dir.confidenceValue))
        checks += Check("Driver conflict", dir.conflictLevel != ConfidenceLevel.HIGH, "${dir.conflictLevel} (%.0f%%)".format(dir.conflict * 100))
        if (condor) {
            val spot = v5?.scenarios?.spot ?: 0.0
            val shortDist = chosen?.legs?.filter { it.action == LegAction.SELL }?.minOfOrNull { abs(it.strike - spot) } ?: 0.0
            val holdSigma = move.sigmaPoints * kotlin.math.sqrt((chosen?.holdMinutes ?: move.horizonMinutes).coerceAtLeast(1).toDouble() / move.horizonMinutes.coerceAtLeast(1))
            checks += Check("Move inside short strikes", chosen == null || holdSigma < shortDist,
                "σ %.0f pts over the %d-min hold vs nearest short strike %.0f pts away".format(holdSigma, chosen?.holdMinutes ?: move.horizonMinutes, shortDist))
        } else {
            checks += Check("Price confirmation", dir.priceConfirmation, if (dir.priceConfirmation) "price agrees" else "price not confirming")
            checks += Check("Expected move", abs(move.expectedMovePoints) >= p.minExpectedMovePts,
                "%+.0f pts (need ≥ %.0f)".format(move.expectedMovePoints, p.minExpectedMovePts))
        }
        if (v5 != null) {
            checks += Check("Strategy", chosen != null,
                chosen?.let { "${it.type.label} · ${it.instrument}" } ?: (plan.rationale.lastOrNull() ?: "no structure"))
            checks += Check("Liquidity / spread", chosen != null && chosen.feasible,
                chosen?.let { c -> if (c.feasible) "worst leg %.0f%% · max spread %.1f%%".format(c.liquidity * 100, c.legs.maxOf { it.spreadPct }) else c.issues.joinToString() } ?: "–")
            val pop = chosen?.let { c ->
                // single long option: prefer the option-outcome calibrated P(profit) when it exists
                val single = opt.best?.takeIf { b -> c.legs.size == 1 && b.strike == c.legs[0].strike && b.type == c.legs[0].type }
                single?.probProfitCalibrated?.takeIf { !it.isNaN() } ?: c.probProfit
            }
            checks += Check("P(profit), net", pop != null && pop >= p.minOptionProfitProb,
                pop?.let { "%.0f%% at the horizon (need ≥ %.0f%%)".format(it * 100, p.minOptionProfitProb * 100) } ?: "–")
            checks += Check("Expected value, net", chosen != null && chosen.returnOnRisk * 100 >= p.minExpectedReturnPct,
                chosen?.let { "EV %+.1f/unit = %+.1f%% of max loss after ₹%.2f/unit costs".format(it.expectedValue, it.returnOnRisk * 100, it.costPerUnit) } ?: "–")
            checks += Check("Trade quality", v5.quality.passed,
                if (chosen == null) "–" else "%.0f%% ${v5.quality.tier}".format(v5.quality.score * 100) +
                    (v5.quality.notes.firstOrNull()?.let { " · $it" } ?: ""))
            checks += Check("Risk engine", v5.risk.approved,
                if (v5.risk.approved) "${v5.risk.lots} lot(s) · ₹%,.0f at stop".format(v5.risk.riskAtStop)
                else v5.risk.checks.firstOrNull { !it.passed }?.let { "${it.name}: ${it.detail}" } ?: (v5.risk.notes.firstOrNull() ?: "–"))
        } else {
            val best = opt.best
            val optProb = best?.let { if (!it.probProfitCalibrated.isNaN()) it.probProfitCalibrated else it.probProfit }
            checks += Check("Option liquidity / spread", best != null,
                best?.let { "${it.strike.toInt()} ${it.type} spread %.1f%%, OI %,.0f".format(it.spreadPct, it.oi) } ?: "no strike passed filters")
            checks += Check("Option P(profit), net", optProb != null && optProb >= p.minOptionProfitProb,
                optProb?.let { "%.0f%% (need ≥ %.0f%%)".format(it * 100, p.minOptionProfitProb * 100) } ?: "–")
            checks += Check("Option expected value, net", best != null && best.expectedReturnPct >= p.minExpectedReturnPct,
                best?.let { "EV %+.1f%% after ₹%.2f/unit costs".format(it.expectedReturnPct, it.costPerUnit) } ?: "–")
        }
        checks += Check("Immediate event risk", regime.regime != Regime.EVENT_SHOCK && (recentShockMinutes == null || recentShockMinutes > 15),
            if (regime.regime == Regime.EVENT_SHOCK) "event shock regime" else recentShockMinutes?.let { "major event %.0f min ago".format(it) } ?: "none")
        checks += Check("Data quality", quality.score >= p.minDataQuality, "%.0f%% (need ≥ %.0f%%)".format(quality.score * 100, p.minDataQuality * 100))
        checks += Check("Market session", !p.requireMarketOpen || marketOpen, if (marketOpen) "open" else "closed")

        // Hysteresis: count consecutive cycles where the core setup holds with the same strategy.
        val coreOk = checks.all { it.passed }
        val key = type.ordinal * 3 + (sideInt + 1)
        if (coreOk && key == streakKey) streak++ else if (coreOk) { streakKey = key; streak = 1 } else { streak = 0; streakKey = -1 }
        val persist = Check("Signal persistence", streak >= p.confirmCycles, "$streak / ${p.confirmCycles} consecutive cycles")
        val calib = Check("Probability calibrated", !p.requireCalibration || probs.calibrated,
            if (probs.calibrated) "yes (${move.horizonMinutes}m)" else dir.calibration.note)
        val all = checks + persist + calib
        val failed = all.filter { !it.passed }

        val conflictWait = rg?.primary == PrimaryRegime.CONFLICT
        // NO TRADE = no edge at all (the strategy layer found nothing the regime supports); otherwise WAIT for checks.
        val noEdge = !conflictWait && if (v5 != null) plan.preferredByRegime == StrategyType.NO_TRADE
            else !checks.first { it.name == "Direction probability" }.passed && (dirProb < 0.5 || probs.pRange >= dirProb)
        val decision = when {
            failed.isEmpty() -> Decision.TRADE
            failed.size == 1 && failed[0] === calib -> Decision.PAPER_TRADE
            noEdge -> Decision.NO_TRADE
            else -> Decision.WAIT
        }
        val what = chosen?.let { c ->
            if (c.legs.size == 1) "BUY ${c.legs[0].strike.toInt()} ${c.legs[0].type} @ ₹%.1f".format(c.netPremium)
            else "${c.type.label.substringBefore(" (")} ${c.instrument.removePrefix("NIFTY ")} @ ₹%.1f %s".format(abs(c.netPremium), if (c.netPremium >= 0) "debit" else "credit")
        } ?: opt.best?.let { "BUY ${it.strike.toInt()} ${it.type} @ ₹%.1f".format(it.premium) } ?: "–"
        val lots = v5?.risk?.lots?.takeIf { it > 0 }?.let { " × $it lot(s)" } ?: ""
        val headline = when (decision) {
            Decision.TRADE -> "TRADE · $what$lots"
            Decision.PAPER_TRADE -> "PAPER TRADE · $what$lots (uncalibrated — shadow only)"
            Decision.NO_TRADE -> "NO TRADE · " + (plan.rationale.firstOrNull() ?: "no directional edge")
            Decision.WAIT -> if (conflictWait) "WAIT · conflicting information blocks" else "WAIT · $side bias, ${failed.size} check(s) failing"
            Decision.DATA_ERROR -> "DATA ERROR — NO TRADE"
        }
        return TradeDecision(decision, headline, failed.map { "${it.name}: ${it.detail}" }, all)
    }
}

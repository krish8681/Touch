package com.niftyengine.engine.engines

import com.niftyengine.engine.model.CalibrationInfo
import com.niftyengine.engine.model.Check
import com.niftyengine.engine.model.ConfidenceLevel
import com.niftyengine.engine.model.DataQualityReport
import com.niftyengine.engine.model.Decision
import com.niftyengine.engine.model.Direction
import com.niftyengine.engine.model.EventRiskLevel
import com.niftyengine.engine.model.EventRiskReport
import com.niftyengine.engine.model.HorizonPrediction
import com.niftyengine.engine.model.LegAction
import com.niftyengine.engine.model.MasterPrediction
import com.niftyengine.engine.model.StrategyCandidate
import com.niftyengine.engine.model.TradeDecision
import kotlin.math.floor

/**
 * 16 — Risk Engine (§26: "should we actually trade?"). Prediction ≠ trade: this layer sees the chosen structure and asks
 *
 *  • data integrity (circuit breaker ⇒ DATA ERROR, never "recover with assumptions")
 *  • horizon direction probability (raised under HIGH event risk), horizon confidence, H1/H2/H3 alignment
 *  • the structure's own net P(profit), net expected value / return on risk, risk:reward (credit structures)
 *  • max loss per lot vs the risk budget (capital × risk %), defined risk (no uncovered short leg), liquidity
 *  • event risk not EXTREME, data quality, market session, persistence over N cycles
 *  • calibration: everything passing except calibration ⇒ PAPER TRADE (log it, don't risk money)
 */
class RiskEngine(private val p: Params = Params()) {
    data class Params(
        val minProbability: Double = 0.55,
        val minConfidence: ConfidenceLevel = ConfidenceLevel.MEDIUM,
        val minAlignment: Int = 2,
        val minProfitProb: Double = 0.45,
        val minReturnOnRisk: Double = 0.05,
        val minRiskReward: Double = 0.25,
        val eventThresholdBump: Double = 0.05,
        val minDataQuality: Double = 0.70,
        val confirmCycles: Int = 2,
        val requireCalibration: Boolean = true,
        val requireMarketOpen: Boolean = true,
        val capital: Double = 500_000.0,
        val riskPerTradePct: Double = 2.0,
        val maxLots: Int = 10,
        val lotSize: Int = 65,
    )

    private var streakKey = ""
    private var streak = 0

    fun pick(candidates: List<StrategyCandidate>): StrategyCandidate? {
        val ranked = candidates.sortedByDescending { it.score }
        return ranked.firstOrNull { it.passedFilters && it.expectedPnl > 0 && it.score > 0 } ?: ranked.firstOrNull { it.expectedPnl > 0 } ?: ranked.firstOrNull()
    }

    fun decide(
        best: StrategyCandidate?, horizons: List<HorizonPrediction>, master: MasterPrediction, risk: EventRiskReport,
        quality: DataQualityReport, marketOpen: Boolean, calibration: CalibrationInfo,
    ): TradeDecision {
        if (quality.circuitBreaker.isNotEmpty()) {
            streak = 0; streakKey = ""
            return TradeDecision(Decision.DATA_ERROR, "DATA ERROR — NO TRADE", quality.circuitBreaker,
                quality.circuitBreaker.map { Check("Data integrity", false, it) })
        }
        if (best == null) {
            streak = 0; streakKey = ""
            return TradeDecision(Decision.NO_TRADE, "NO TRADE · no liquid defined-risk structure available", listOf("No option chain / candidates"),
                listOf(Check("Strategy available", false, "no candidate could be priced")))
        }
        val pred = horizons.firstOrNull { it.id == best.horizon }
        val ev = risk.level(best.horizon.group)
        val sideSign = best.kind.directional
        val pProfitUsed = if (!best.pProfitCalibrated.isNaN()) best.pProfitCalibrated else best.pProfit
        // Directional structures need the horizon's direction; non-directional ones need NIFTY inside their profit zone.
        val dirProb = when {
            pred == null -> Double.NaN
            sideSign > 0 -> pred.bull
            sideSign < 0 -> pred.bear
            else -> pProfitUsed
        }
        val minProb = p.minProbability + if (ev.ordinal >= EventRiskLevel.HIGH.ordinal) p.eventThresholdBump else 0.0
        val probLabel = if (pred?.calibrated == true) "calibrated" else "model score"
        val pProfit = pProfitUsed
        val budget = p.capital * p.riskPerTradePct / 100
        val lossPerLot = best.maxLoss * p.lotSize
        val lots = if (lossPerLot <= 0) 0 else minOf(p.maxLots, floor(budget / lossPerLot).toInt())
        val masterAgrees = sideSign == 0 || master.direction.sign == sideSign
        val credit = best.netPremium < 0
        val naked = best.failures.any { it.startsWith("Uncovered") }

        val core = listOf(
            Check("Direction probability (${best.horizon.short})",
                !dirProb.isNaN() && dirProb >= minProb,
                if (dirProb.isNaN()) "–" else "%s %.0f%% %s (need ≥ %.0f%%%s)".format(
                    when { sideSign > 0 -> "bull"; sideSign < 0 -> "bear"; else -> "in profit zone" }, dirProb * 100, probLabel, minProb * 100,
                    if (ev.ordinal >= EventRiskLevel.HIGH.ordinal) ", event risk ${ev.label}" else "")),
            Check("Horizon confidence", pred != null && pred.confidence.ordinal >= p.minConfidence.ordinal,
                pred?.let { "${it.confidence} (%.0f%%), need ${p.minConfidence}".format(it.confidenceValue * 100) } ?: "–"),
            Check("Horizon alignment", master.alignment >= p.minAlignment && masterAgrees,
                "${master.alignmentLabel}, master ${master.direction.label}${if (!masterAgrees) " — opposes the structure" else ""}"),
            Check("Event risk", ev != EventRiskLevel.EXTREME, "${ev.label} to ${best.horizon.label.lowercase()}"),
            Check("P(profit), net", pProfit >= p.minProfitProb,
                "%.0f%% %s (need ≥ %.0f%%)".format(pProfit * 100, if (best.pProfitCalibrated.isNaN()) "model" else "calibrated", p.minProfitProb * 100)),
            Check("Expected value, net", best.expectedPnl > 0 && best.returnOnRisk >= p.minReturnOnRisk,
                "EV %+.1f/unit · %+.0f%% of max loss (need ≥ %.0f%%) after ₹%.2f costs".format(best.expectedPnl, best.returnOnRisk * 100, p.minReturnOnRisk * 100, best.costPerUnit)),
            Check("Risk / reward", !credit || best.riskReward >= p.minRiskReward,
                if (best.riskReward.isNaN()) "upside open, max loss ₹%.1f".format(best.maxLoss) else "%.2f : 1 (credit structures need ≥ %.2f)".format(best.riskReward, p.minRiskReward)),
            Check("Max loss within risk budget", lots >= 1,
                "₹%,.0f per lot vs budget ₹%,.0f (%.1f%% of ₹%,.0f) → %d lot(s)".format(lossPerLot, budget, p.riskPerTradePct, p.capital, lots)),
            Check("Defined risk (no naked selling)", !naked, if (naked) "uncovered short leg" else
                best.legs.count { it.action == LegAction.SELL }.let { if (it == 0) "long premium only" else "$it short leg(s), all covered" }),
            Check("Liquidity / execution", best.failures.none { !it.startsWith("Uncovered") && !it.startsWith("Max loss") && !it.startsWith("Theta") },
                best.failures.filter { !it.startsWith("Uncovered") }.joinToString().ifBlank { "all legs pass OI / volume / spread / freshness" }),
            Check("Theta exposure", best.failures.none { it.startsWith("Theta") }, best.failures.firstOrNull { it.startsWith("Theta") } ?: "acceptable"),
            Check("Data quality", quality.score >= p.minDataQuality, "%.0f%% (need ≥ %.0f%%)".format(quality.score * 100, p.minDataQuality * 100)),
            Check("Market session", !p.requireMarketOpen || marketOpen, if (marketOpen) "open" else "closed"),
        )
        val key = "${best.kind}|${best.expiryKind}|${best.horizon}"
        val coreOk = core.all { it.passed }
        if (coreOk && key == streakKey) streak++ else if (coreOk) { streakKey = key; streak = 1 } else { streak = 0; streakKey = "" }
        val persist = Check("Signal persistence", streak >= p.confirmCycles, "$streak / ${p.confirmCycles} consecutive cycles")
        val calibrated = !p.requireCalibration || calibration.isCalibrated(best.horizon)
        val calib = Check("Probability calibrated", calibrated,
            if (calibration.isCalibrated(best.horizon)) "yes (${best.horizon.short})" else "${best.horizon.short}: ${calibration.samples[best.horizon.name] ?: 0}/${calibration.minSamples} outcomes — model scores only")
        val checks = core + persist + calib
        val failed = checks.filter { !it.passed }
        val noEdge = best.expectedPnl <= 0 || (!dirProb.isNaN() && dirProb < 0.45 && sideSign != 0) || (master.direction == Direction.NEUTRAL && sideSign != 0)
        val decision = when {
            failed.isEmpty() -> Decision.TRADE
            failed.size == 1 && failed[0] === calib -> Decision.PAPER_TRADE
            noEdge -> Decision.NO_TRADE
            else -> Decision.WAIT
        }
        val structure = "${best.title} · ${best.expiryKind.label.lowercase()} ${best.expiry}"
        val money = (if (best.netPremium >= 0) "debit ₹%.1f" else "credit ₹%.1f").format(kotlin.math.abs(best.netPremium))
        val headline = when (decision) {
            Decision.TRADE -> "TRADE · $structure · $money · $lots lot(s) · max loss ₹%,.0f".format(lossPerLot * lots)
            Decision.PAPER_TRADE -> "PAPER TRADE · $structure · $money (uncalibrated — no real money)"
            Decision.NO_TRADE -> "NO TRADE · no edge after costs and risk checks"
            Decision.WAIT -> "WAIT · ${best.kind.label}, ${failed.size} check(s) failing"
            Decision.DATA_ERROR -> "DATA ERROR — NO TRADE"
        }
        return TradeDecision(decision, headline, failed.map { "${it.name}: ${it.detail}" }, checks, best, lots,
            lossPerLot * lots, best.expectedPnl * p.lotSize * lots)
    }
}

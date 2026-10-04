package com.niftyengine.engine.engines

import com.niftyengine.engine.core.Session
import com.niftyengine.engine.core.TransactionCosts
import com.niftyengine.engine.model.Check
import com.niftyengine.engine.model.ExitPlan
import com.niftyengine.engine.model.InformationShock
import com.niftyengine.engine.model.RiskAssessment
import com.niftyengine.engine.model.RiskConfig
import com.niftyengine.engine.model.ShadowBook
import com.niftyengine.engine.model.StrategyCandidate
import com.niftyengine.engine.model.StrategyType
import kotlin.math.abs
import kotlin.math.floor

/**
 * 27 — Risk Engine (v5). Deterministic and hard-coded: AI can recommend, code decides.
 *
 * Sizes the trade from the risk budget (capital × max risk per trade ÷ loss at the stop), and blocks it on: daily loss
 * limit, open positions, trades per day, capital per trade, leg spread, slippage share, IV cap (long premium), event /
 * shock risk, and the entry cut-off time. It also fixes the exit plan: stop, target, time exit and emergency exits
 * that the shadow executor enforces.
 */
class RiskEngine(private val cfg: RiskConfig = RiskConfig(), private val costs: TransactionCosts = TransactionCosts()) {
    /** Minutes from [now] to the shadow time exit (credit structures are valued over this hold). */
    fun minutesToTimeExit(now: Long): Int = ((Session.atMinuteOfDay(now, cfg.exitAllAtMin) - now) / 60_000L).toInt()

    fun exitPlan(c: StrategyCandidate, now: Long, horizonMinutes: Int): ExitPlan {
        val e = c.netPremium
        val (stop, target, stopT, targetT) = when (c.type) {
            StrategyType.BUY_CALL, StrategyType.BUY_PUT -> Quad(e * (1 - cfg.longStopPct / 100), e * (1 + cfg.longTargetPct / 100),
                "premium −%.0f%% (₹%.1f)".format(cfg.longStopPct, e * (1 - cfg.longStopPct / 100)),
                "premium +%.0f%% (₹%.1f)".format(cfg.longTargetPct, e * (1 + cfg.longTargetPct / 100)))
            StrategyType.BULL_CALL_SPREAD, StrategyType.BEAR_PUT_SPREAD -> {
                val maxProfit = (c.maxProfit + c.costPerUnit).coerceAtLeast(0.0)
                Quad(e * (1 - cfg.spreadStopPct / 100), e + maxProfit * cfg.spreadTargetPct / 100,
                    "spread value −%.0f%% (₹%.1f)".format(cfg.spreadStopPct, e * (1 - cfg.spreadStopPct / 100)),
                    "%.0f%% of max profit (value ₹%.1f)".format(cfg.spreadTargetPct, e + maxProfit * cfg.spreadTargetPct / 100))
            }
            StrategyType.IRON_CONDOR -> {
                val credit = -e
                Quad(-cfg.condorStopMultiple * credit, -credit * (1 - cfg.condorTargetPct / 100),
                    "cost to close ≥ %.1f× credit (₹%.1f)".format(cfg.condorStopMultiple, cfg.condorStopMultiple * credit),
                    "%.0f%% of credit kept (close ≤ ₹%.1f)".format(cfg.condorTargetPct, credit * (1 - cfg.condorTargetPct / 100)))
            }
            StrategyType.NO_TRADE -> Quad(0.0, 0.0, "", "")
        }
        // Debit structures live for the decision horizon; credit structures are held for their time decay until the time exit.
        val exitAll = Session.atMinuteOfDay(now, cfg.exitAllAtMin)
        val timeExit = if (c.type.credit) exitAll else minOf(now + horizonMinutes * 60_000L, exitAll)
        val emergency = buildList {
            add("Data circuit breaker trips")
            add(if (c.type.credit) "Information shock ≥ SIGNIFICANT or volatility expansion" else "MAJOR information shock against the position")
            if (c.type.directional != 0) add("Regime flips to the opposite trend, or early-reversal warning against the position")
            add("Exit everything at ${"%02d:%02d".format(cfg.exitAllAtMin / 60, cfg.exitAllAtMin % 60)} IST — no overnight shadow positions")
        }
        return ExitPlan(stop, target, stopT, targetT, timeExit, emergency)
    }

    private data class Quad(val a: Double, val b: Double, val c: String, val d: String)

    fun assess(
        c: StrategyCandidate?, now: Long, horizonMinutes: Int, book: ShadowBook, atmIvPct: Double,
        shock: InformationShock, eventRiskAhead: Double, marketOpen: Boolean,
    ): RiskAssessment {
        val budget = cfg.capital * cfg.maxRiskPerTradePct / 100
        val today = Session.zdt(now).toLocalDate()
        val realizedToday = book.closed.filter { Session.zdt(it.closedAt).toLocalDate() == today }.sumOf { it.pnl }
        val unrealized = book.open.sumOf { (it.markValue - it.entryValue) * it.lots * it.lotSize }
        val dayPnl = realizedToday + unrealized
        val tradesToday = book.closed.count { Session.zdt(it.position.openedAt).toLocalDate() == today } +
            book.open.count { Session.zdt(it.openedAt).toLocalDate() == today }
        if (c == null || c.type == StrategyType.NO_TRADE)
            return RiskAssessment(false, riskBudget = budget, dailyPnl = dayPnl, openPositions = book.open.size, tradesToday = tradesToday,
                notes = listOf("No strategy to size"))

        val exit = exitPlan(c, now, horizonMinutes)
        val lotSize = costs.lotSize.coerceAtLeast(1)
        // per-unit loss if the stop is hit (stop distance + round-trip charges), capped by the structure's max loss
        val stopLoss = (c.netPremium - exit.stopValue).let { if (c.type.credit) it.coerceAtMost(c.maxLoss) else it } + c.costPerUnit
        val perLotRisk = stopLoss * lotSize
        val lotsByRisk = if (perLotRisk > 0) floor(budget / perLotRisk).toInt() else 0
        val outlayPerLot = (if (c.type.credit) c.maxLoss else c.netPremium) * lotSize
        val lotsByCapital = if (outlayPerLot > 0) floor(cfg.capital * cfg.maxCapitalPerTradePct / 100 / outlayPerLot).toInt() else 0
        val lots = minOf(lotsByRisk, lotsByCapital, cfg.maxLots).coerceAtLeast(0)
        val qty = lots * lotSize
        val worst = if (c.maxLoss.isNaN()) Double.NaN else c.maxLoss * qty
        // legs judged like the strategy layer: % spread, or ≤ 4 ticks absolute for cheap hedge wings
        val maxLegSpread = c.legs.filter { !StrategySelector.spreadOk(it, cfg.maxSpreadPct) }
            .maxOfOrNull { if (it.spreadPct < 0) 99.0 else it.spreadPct } ?: (c.legs.maxOfOrNull { it.spreadPct.coerceAtMost(cfg.maxSpreadPct) } ?: 99.0)
        val slip = 2 * costs.slippageTicks * costs.tickSize * c.legs.size
        val slipShare = if (stopLoss > 0) slip / stopLoss * 100 else 100.0
        val minute = Session.minuteOfDay(now)
        val dailyLimit = cfg.capital * cfg.maxDailyLossPct / 100

        val checks = listOf(
            Check("Position size", lots >= 1,
                if (lots >= 1) "$lots lot(s) × $lotSize · ₹%,.0f at stop (budget ₹%,.0f)".format(perLotRisk * lots, budget)
                else if (lotsByRisk < 1) "1 lot risks ₹%,.0f at the stop > budget ₹%,.0f".format(perLotRisk, budget)
                else "1 lot needs ₹%,.0f > %.0f%% of capital".format(outlayPerLot, cfg.maxCapitalPerTradePct)),
            Check("Daily loss limit", dayPnl > -dailyLimit, "today ₹%,.0f (limit −₹%,.0f)".format(dayPnl, dailyLimit)),
            Check("Open positions", book.open.size < cfg.maxOpenPositions, "${book.open.size} open (max ${cfg.maxOpenPositions})"),
            Check("Trades today", tradesToday < cfg.maxTradesPerDay, "$tradesToday (max ${cfg.maxTradesPerDay})"),
            Check("Max option spread", maxLegSpread <= cfg.maxSpreadPct, "worst leg %.1f%% (max %.1f%%)".format(maxLegSpread, cfg.maxSpreadPct)),
            Check("Max slippage", slipShare <= cfg.maxSlippagePct, "%.1f%% of risk per unit (max %.1f%%)".format(slipShare, cfg.maxSlippagePct)),
            Check("Max IV", c.type.credit || atmIvPct.isNaN() || atmIvPct <= cfg.maxIvPct,
                if (atmIvPct.isNaN()) "ATM IV unknown" else "ATM IV %.1f%% (max %.0f%% for long premium)".format(atmIvPct, cfg.maxIvPct)),
            Check("Max event risk", shock.score <= cfg.maxShockScore && !(c.type.credit && (eventRiskAhead >= 0.6 || shock.score >= 0.35)),
                "shock %.0f%% (max %.0f%%)%s".format(shock.score * 100, cfg.maxShockScore * 100,
                    if (c.type.credit && eventRiskAhead >= 0.6) " · event ahead — no short gamma" else "")),
            Check("Entry window", marketOpen && minute < cfg.noNewEntriesAfterMin,
                if (!marketOpen) "market closed" else "no new entries after %02d:%02d".format(cfg.noNewEntriesAfterMin / 60, cfg.noNewEntriesAfterMin % 60)),
        )
        val approved = checks.all { it.passed }
        val notes = ArrayList<String>()
        if (c.maxLoss.isNaN() || abs(worst) > 3 * budget) notes += "Worst case (gap through the stop) ₹%s".format(if (worst.isNaN()) "unlimited" else "%,.0f".format(worst))
        return RiskAssessment(
            approved, lots, qty, budget, perLotRisk * lots, worst, outlayPerLot * lots, dayPnl, book.open.size, tradesToday,
            exit, checks, notes,
        )
    }
}

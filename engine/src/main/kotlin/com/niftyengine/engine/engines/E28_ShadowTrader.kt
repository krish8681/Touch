package com.niftyengine.engine.engines

import com.niftyengine.engine.core.Session
import com.niftyengine.engine.core.TransactionCosts
import com.niftyengine.engine.model.Decision
import com.niftyengine.engine.model.ExpectationState
import com.niftyengine.engine.model.FutureExpectation
import com.niftyengine.engine.model.GroupStat
import com.niftyengine.engine.model.InformationShock
import com.niftyengine.engine.model.LegAction
import com.niftyengine.engine.model.OptionChain
import com.niftyengine.engine.model.OptionType
import com.niftyengine.engine.model.PrimaryRegime
import com.niftyengine.engine.model.RegimeAssessment
import com.niftyengine.engine.model.RiskAssessment
import com.niftyengine.engine.model.ShadowBook
import com.niftyengine.engine.model.ShadowLeg
import com.niftyengine.engine.model.ShadowPosition
import com.niftyengine.engine.model.ShadowSummary
import com.niftyengine.engine.model.ShadowTrade
import com.niftyengine.engine.model.ShockLevel
import com.niftyengine.engine.model.StrategyPlan
import com.niftyengine.engine.model.TradeQuality

/**
 * 28 — Execution in SHADOW MODE (v5): the bot makes every decision but places no orders.
 *
 * When the pipeline says TRADE (or PAPER TRADE) and the risk engine approves, a virtual position is opened at the
 * quoted ask/bid of each leg with the risk engine's size, stop, target and time exit. Every cycle it is marked to the
 * option chain (long legs at bid, short legs at ask) and closed on STOP, TARGET, TIME, an EMERGENCY condition
 * (data breaker, shock/regime/expectation against the position) or the session end. P&L is net of brokerage,
 * statutory charges and slippage. Closed trades are the learning log: win rate and R by strategy, regime, quality
 * tier, probability bucket, expectation state and exit reason — evaluated before any money is risked.
 */
class ShadowTrader(
    private val costs: TransactionCosts = TransactionCosts(),
    var enabled: Boolean = true,
    private val keepClosed: Int = 1000,
) {
    private var book = ShadowBook()
    val current: ShadowBook get() = book

    fun exportState(): ShadowBook = book
    fun importState(b: ShadowBook) { book = b }

    data class Context(
        val now: Long,
        val spot: Double,
        val chain: OptionChain?,
        val decision: Decision,
        val plan: StrategyPlan,
        val risk: RiskAssessment,
        val regime: RegimeAssessment,
        val quality: TradeQuality,
        val probability: Double,
        val expectation: FutureExpectation,
        val shock: InformationShock,
        val dataError: Boolean,
        val marketOpen: Boolean,
    )

    private fun legMark(chain: OptionChain?, l: ShadowLeg): Double {
        val row = chain?.rows?.firstOrNull { it.strike == l.strike } ?: return l.mark
        val q = if (l.type == OptionType.CE) row.call else row.put
        val px = when (l.action) {
            LegAction.BUY -> if (q.bid > 0) q.bid else q.ltp
            LegAction.SELL -> if (q.ask > 0) q.ask else q.ltp
        }
        return if (px > 0) px else l.mark
    }

    private fun value(legs: List<ShadowLeg>) = legs.sumOf { if (it.action == LegAction.BUY) it.mark else -it.mark }

    private fun close(p: ShadowPosition, now: Long, spot: Double, reason: String): ShadowTrade {
        val c = costs.copy(lots = p.lots)
        val charges = p.legs.sumOf { l ->
            if (l.action == LegAction.BUY) c.roundTrip(l.entry, l.mark).total else c.roundTrip(l.mark, l.entry).total
        }
        val gross = p.markValue - p.entryValue
        val pnl = gross * p.lots * p.lotSize - charges
        return ShadowTrade(p, now, p.markValue, spot, reason, gross, charges, pnl, if (p.riskAtStop > 0) pnl / p.riskAtStop else 0.0)
    }

    fun update(c: Context): ShadowSummary {
        val events = ArrayList<String>()
        val day = Session.zdt(c.now).toLocalDate()
        val stillOpen = ArrayList<ShadowPosition>()
        val closedNow = ArrayList<ShadowTrade>()

        for (pos0 in book.open) {
            val legs = if (c.dataError) pos0.legs else pos0.legs.map { it.copy(mark = legMark(c.chain, it)) }
            val mv = value(legs)
            val pnlUnit = mv - pos0.entryValue
            val pos = pos0.copy(legs = legs, markValue = mv, lastMarkAt = c.now,
                mfe = maxOf(pos0.mfe, pnlUnit), mae = minOf(pos0.mae, pnlUnit))
            val against = pos.direction != 0 && (
                (c.shock.level == ShockLevel.MAJOR && c.shock.direction * pos.direction < -0.2) ||
                    (c.regime.primary.bias != 0 && c.regime.primary.bias == -pos.direction) ||
                    (pos.direction > 0 && c.expectation.state == ExpectationState.BULL_REVERSAL_WARNING) ||
                    (pos.direction < 0 && c.expectation.state == ExpectationState.BEAR_REVERSAL_WARNING))
            val creditDanger = pos.strategy.credit && (c.shock.level >= ShockLevel.SIGNIFICANT || c.regime.primary == PrimaryRegime.VOLATILITY_EXPANSION)
            val reason = when {
                Session.zdt(pos.openedAt).toLocalDate() != day -> "SESSION END"
                c.dataError -> "EMERGENCY: data error"
                mv <= pos.stopValue -> "STOP"
                mv >= pos.targetValue -> "TARGET"
                c.now >= pos.timeExitAt -> "TIME"
                against -> "EMERGENCY: " + when {
                    c.shock.level == ShockLevel.MAJOR && c.shock.direction * pos.direction < -0.2 -> "shock against position"
                    c.regime.primary.bias == -pos.direction -> "regime flipped"
                    else -> "reversal warning"
                }
                creditDanger -> "EMERGENCY: volatility"
                else -> null
            }
            if (reason == null) stillOpen += pos
            else {
                val t = close(pos, c.now, c.spot, reason)
                closedNow += t
                events += "Closed ${pos.instrument} · $reason · ₹%,.0f".format(t.pnl)
            }
        }

        // open a new virtual position when the full pipeline (decision + risk) says so
        val chosen = c.plan.chosen
        val exit = c.risk.exit
        if (enabled && c.marketOpen && !c.dataError && chosen != null && exit != null && c.risk.approved && c.risk.lots >= 1 &&
            (c.decision == Decision.TRADE || c.decision == Decision.PAPER_TRADE) && stillOpen.none { it.instrument == chosen.instrument }
        ) {
            val legs = chosen.legs.map { l -> ShadowLeg(l.action, l.type, l.strike, l.price, l.price) }
                .map { it.copy(mark = legMark(c.chain, it)) }
            val entry = chosen.netPremium
            val mv = value(legs)
            stillOpen += ShadowPosition(
                id = "S${c.now}", openedAt = c.now, strategy = chosen.type, instrument = chosen.instrument, legs = legs,
                lots = c.risk.lots, lotSize = costs.lotSize, entryValue = entry, markValue = mv,
                stopValue = exit.stopValue, targetValue = exit.targetValue, timeExitAt = exit.timeExitAt, riskAtStop = c.risk.riskAtStop,
                direction = chosen.type.directional, entrySpot = c.spot, regime = c.regime.primary.name, quality = c.quality.score,
                qualityTier = c.quality.tier.name, probability = c.probability, expectationState = c.expectation.state.name,
                shockLevel = c.shock.level.name, decision = c.decision.name, lastMarkAt = c.now,
                mfe = minOf(0.0, mv - entry), mae = minOf(0.0, mv - entry),
            )
            events += "Opened ${chosen.instrument} × ${c.risk.lots} lot(s) @ ₹%.1f (${c.decision.name.replace('_', ' ')})".format(entry)
        }

        book = ShadowBook(stillOpen, (book.closed + closedNow).takeLast(keepClosed))
        return summary(c.now, events)
    }

    fun summary(now: Long, events: List<String> = emptyList()): ShadowSummary {
        val closed = book.closed
        val day = Session.zdt(now).toLocalDate()
        val today = closed.filter { Session.zdt(it.closedAt).toLocalDate() == day }.sumOf { it.pnl } +
            book.open.sumOf { (it.markValue - it.entryValue) * it.lots * it.lotSize }
        var peak = 0.0; var eq = 0.0; var dd = 0.0
        for (t in closed.sortedBy { it.closedAt }) { eq += t.pnl; peak = maxOf(peak, eq); dd = maxOf(dd, peak - eq) }
        fun groups(key: (ShadowTrade) -> String) = closed.groupBy(key).map { (k, l) ->
            GroupStat(k, l.size, l.count { it.win }.toDouble() / l.size, l.map { it.pnl }.average(), l.sumOf { it.pnl }, l.map { it.rMultiple }.average())
        }.sortedByDescending { it.n }
        return ShadowSummary(
            enabled = enabled, open = book.open, recent = closed.takeLast(30).reversed(), trades = closed.size,
            winRate = if (closed.isEmpty()) Double.NaN else closed.count { it.win }.toDouble() / closed.size,
            totalPnl = closed.sumOf { it.pnl }, todayPnl = today,
            expectancy = if (closed.isEmpty()) Double.NaN else closed.map { it.pnl }.average(),
            avgR = if (closed.isEmpty()) Double.NaN else closed.map { it.rMultiple }.average(),
            maxDrawdown = dd, events = events,
            byStrategy = groups { it.position.strategy.label },
            byRegime = groups { it.position.regime },
            byQuality = groups { it.position.qualityTier },
            byProbability = groups { probBucket(it.position.probability) },
            byExpectation = groups { it.position.expectationState },
            byExitReason = groups { it.reason.substringBefore(':') },
        )
    }

    companion object {
        fun probBucket(p: Double): String = when {
            p.isNaN() -> "?"
            p < 0.55 -> "<55%"
            p < 0.60 -> "55–60%"
            p < 0.65 -> "60–65%"
            p < 0.70 -> "65–70%"
            p < 0.75 -> "70–75%"
            else -> "75%+"
        }
    }
}

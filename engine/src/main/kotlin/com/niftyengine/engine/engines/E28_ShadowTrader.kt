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
import com.niftyengine.engine.model.StrategyCandidate
import com.niftyengine.engine.model.StrategyPlan
import com.niftyengine.engine.model.TradeQuality
import com.niftyengine.engine.model.TradeStats

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
    /** The benchmark positions viewed as a book of their own (for sizing them independently). */
    val benchmarkBook: ShadowBook get() = ShadowBook(book.benchOpen, book.benchClosed)

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
        /** v5.1 benchmark: naive candidate + its risk sizing, opened only when the main book opens a position. */
        val benchmark: Pair<StrategyCandidate, RiskAssessment>? = null,
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

    private data class Step(val open: List<ShadowPosition>, val closed: List<ShadowTrade>)

    /** Marks every position to the chain and closes those hitting stop / target / time / emergency / session end. */
    private fun markAndExit(positions: List<ShadowPosition>, c: Context, tag: String, events: MutableList<String>): Step {
        val day = Session.zdt(c.now).toLocalDate()
        val stillOpen = ArrayList<ShadowPosition>()
        val closedNow = ArrayList<ShadowTrade>()
        for (pos0 in positions) {
            // new day — or the clock went backwards (simulator restart / replay): close at the last mark, never carry it over
            val stale = Session.zdt(pos0.openedAt).toLocalDate() != day || pos0.openedAt > c.now
            val legs = if (c.dataError || stale) pos0.legs else pos0.legs.map { it.copy(mark = legMark(c.chain, it)) }
            val mv = value(legs)
            val pnlUnit = mv - pos0.entryValue
            val fresh = !c.dataError && !stale
            val pos = pos0.copy(legs = legs, markValue = mv, lastMarkAt = if (fresh) c.now else pos0.lastMarkAt,
                lastSpot = if (fresh) c.spot else pos0.lastSpot, mfe = maxOf(pos0.mfe, pnlUnit), mae = minOf(pos0.mae, pnlUnit))
            val against = pos.direction != 0 && (
                (c.shock.level == ShockLevel.MAJOR && c.shock.direction * pos.direction < -0.2) ||
                    (c.regime.primary.bias != 0 && c.regime.primary.bias == -pos.direction) ||
                    (pos.direction > 0 && c.expectation.state == ExpectationState.BULL_REVERSAL_WARNING) ||
                    (pos.direction < 0 && c.expectation.state == ExpectationState.BEAR_REVERSAL_WARNING))
            val creditDanger = pos.strategy.credit && (c.shock.level >= ShockLevel.SIGNIFICANT || c.regime.primary == PrimaryRegime.VOLATILITY_EXPANSION)
            val reason = when {
                stale -> "SESSION END"
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
                // a stale position is booked when it was last marked (its own session), not "now"
                val t = if (stale) close(pos0, maxOf(pos0.lastMarkAt, pos0.openedAt), pos0.lastSpot.takeIf { !it.isNaN() } ?: pos0.entrySpot, reason)
                else close(pos, c.now, c.spot.takeIf { !it.isNaN() && it > 0 } ?: pos0.lastSpot, reason)
                closedNow += t
                events += "${tag}Closed ${pos.instrument} · $reason · ₹%,.0f".format(t.pnl)
            }
        }
        return Step(stillOpen, closedNow)
    }

    private fun open(c: Context, cand: StrategyCandidate, risk: RiskAssessment): ShadowPosition {
        val exit = risk.exit!!
        val legs = cand.legs.map { l -> ShadowLeg(l.action, l.type, l.strike, l.price, l.price) }.map { it.copy(mark = legMark(c.chain, it)) }
        val entry = cand.netPremium
        val mv = value(legs)
        return ShadowPosition(
            id = "S${c.now}", openedAt = c.now, strategy = cand.type, instrument = cand.instrument, legs = legs,
            lots = risk.lots, lotSize = costs.lotSize, entryValue = entry, markValue = mv,
            stopValue = exit.stopValue, targetValue = exit.targetValue, timeExitAt = exit.timeExitAt, riskAtStop = risk.riskAtStop,
            direction = cand.type.directional, entrySpot = c.spot, regime = c.regime.primary.name, quality = c.quality.score,
            qualityTier = c.quality.tier.name, probability = c.probability, expectationState = c.expectation.state.name,
            shockLevel = c.shock.level.name, decision = c.decision.name, lastMarkAt = c.now, lastSpot = c.spot,
            mfe = minOf(0.0, mv - entry), mae = minOf(0.0, mv - entry),
        )
    }

    fun update(c: Context): ShadowSummary {
        val events = ArrayList<String>()
        val main = markAndExit(book.open, c, "", events)
        val bench = markAndExit(book.benchOpen, c, "Benchmark: ", events)
        val stillOpen = main.open.toMutableList()
        val benchOpen = bench.open.toMutableList()

        // open a new virtual position when the full pipeline (decision + risk) says so
        val chosen = c.plan.chosen
        if (enabled && c.marketOpen && !c.dataError && chosen != null && c.risk.exit != null && c.risk.approved && c.risk.lots >= 1 &&
            (c.decision == Decision.TRADE || c.decision == Decision.PAPER_TRADE) && stillOpen.none { it.instrument == chosen.instrument }
        ) {
            stillOpen += open(c, chosen, c.risk)
            events += "Opened ${chosen.instrument} × ${c.risk.lots} lot(s) @ ₹%.1f (${c.decision.name.replace('_', ' ')})".format(chosen.netPremium)
            // benchmark: same moment, naive instrument, same risk budget and exit rules — isolates what v5's choices add
            val b = c.benchmark
            if (b != null && b.second.exit != null && b.second.lots >= 1) {
                benchOpen += open(c, b.first, b.second)
                events += "Benchmark: opened ${b.first.instrument} × ${b.second.lots} lot(s)"
            }
        }

        book = ShadowBook(stillOpen, (book.closed + main.closed).takeLast(keepClosed), benchOpen, (book.benchClosed + bench.closed).takeLast(keepClosed))
        return summary(c.now, events)
    }

    fun summary(now: Long, events: List<String> = emptyList()): ShadowSummary {
        val closed = book.closed
        val day = Session.zdt(now).toLocalDate()
        val today = closed.filter { Session.zdt(it.closedAt).toLocalDate() == day }.sumOf { it.pnl } +
            book.open.sumOf { (it.markValue - it.entryValue) * it.lots * it.lotSize }
        val st = stats(closed)
        fun groups(key: (ShadowTrade) -> String) = closed.groupBy(key).map { (k, l) ->
            GroupStat(k, l.size, l.count { it.win }.toDouble() / l.size, l.map { it.pnl }.average(), l.sumOf { it.pnl }, l.map { it.rMultiple }.average(),
                profitFactor(l))
        }.sortedByDescending { it.n }
        return ShadowSummary(
            enabled = enabled, open = book.open, recent = closed.takeLast(30).reversed(), trades = closed.size,
            winRate = st.winRate, totalPnl = st.totalPnl, todayPnl = today, expectancy = st.expectancy, avgR = st.avgR,
            maxDrawdown = st.maxDrawdown, events = events,
            byStrategy = groups { it.position.strategy.label },
            byRegime = groups { it.position.regime },
            byQuality = groups { it.position.qualityTier },
            byProbability = groups { probBucket(it.position.probability) },
            byExpectation = groups { it.position.expectationState },
            byExitReason = groups { it.reason.substringBefore(':') },
            stats = st, benchmark = stats(book.benchClosed),
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

        fun profitFactor(l: List<ShadowTrade>): Double {
            val wins = l.filter { it.pnl > 0 }.sumOf { it.pnl }
            val losses = -l.filter { it.pnl < 0 }.sumOf { it.pnl }
            return if (losses <= 0) Double.NaN else wins / losses
        }

        /** Expectancy, profit factor, drawdown and payoff first; win rate is secondary. */
        fun stats(closed: List<ShadowTrade>): TradeStats {
            if (closed.isEmpty()) return TradeStats()
            val ordered = closed.sortedBy { it.closedAt }
            var peak = 0.0; var eq = 0.0; var dd = 0.0
            for (t in ordered) { eq += t.pnl; peak = maxOf(peak, eq); dd = maxOf(dd, peak - eq) }
            val wins = closed.filter { it.pnl > 0 }; val losses = closed.filter { it.pnl < 0 }
            val avgWin = if (wins.isEmpty()) Double.NaN else wins.map { it.pnl }.average()
            val avgLoss = if (losses.isEmpty()) Double.NaN else losses.map { it.pnl }.average()
            val daily = closed.groupBy { Session.zdt(it.closedAt).toLocalDate() }.values.map { d -> d.sumOf { it.pnl } }
            val sharpe = if (daily.size < 5) Double.NaN else {
                val m = daily.average(); val sd = kotlin.math.sqrt(daily.sumOf { (it - m) * (it - m) } / (daily.size - 1))
                if (sd <= 0) Double.NaN else m / sd * kotlin.math.sqrt(252.0)
            }
            return TradeStats(
                trades = closed.size, totalPnl = closed.sumOf { it.pnl }, expectancy = closed.map { it.pnl }.average(),
                profitFactor = profitFactor(closed), maxDrawdown = dd, avgWin = avgWin, avgLoss = avgLoss,
                payoff = if (avgWin.isNaN() || avgLoss.isNaN() || avgLoss == 0.0) Double.NaN else avgWin / kotlin.math.abs(avgLoss),
                avgR = closed.map { it.rMultiple }.average(), winRate = wins.size.toDouble() / closed.size,
                sharpe = sharpe, days = daily.size,
            )
        }
    }
}

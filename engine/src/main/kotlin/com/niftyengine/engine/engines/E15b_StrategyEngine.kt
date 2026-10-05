package com.niftyengine.engine.engines

import com.niftyengine.engine.core.BlackScholes
import com.niftyengine.engine.core.M
import com.niftyengine.engine.core.PriceDistribution
import com.niftyengine.engine.core.Session
import com.niftyengine.engine.core.TransactionCosts
import com.niftyengine.engine.engines.OptionsValuationEngine.ask
import com.niftyengine.engine.engines.OptionsValuationEngine.bid
import com.niftyengine.engine.engines.OptionsValuationEngine.intrinsic
import com.niftyengine.engine.engines.OptionsValuationEngine.ivOf
import com.niftyengine.engine.engines.OptionsValuationEngine.spreadPct
import com.niftyengine.engine.model.ConfidenceLevel
import com.niftyengine.engine.model.Direction
import com.niftyengine.engine.model.ExpiryIntel
import com.niftyengine.engine.model.ExpiryKind
import com.niftyengine.engine.model.ExpiryRegime
import com.niftyengine.engine.model.HorizonPrediction
import com.niftyengine.engine.model.LegAction
import com.niftyengine.engine.model.OptionChain
import com.niftyengine.engine.model.OptionStrikeRow
import com.niftyengine.engine.model.OptionType
import com.niftyengine.engine.model.StrategyCandidate
import com.niftyengine.engine.model.StrategyKind
import com.niftyengine.engine.model.StrategyLeg
import com.niftyengine.engine.model.TradeCategory
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.round
import kotlin.math.sqrt

/**
 * 15b — Option Strategy Engine (§25). Runs only after the NIFTY prediction is complete:
 *
 *   prediction → expiry distribution → expected range → probability → IV → OI → premium → liquidity → theta → selection
 *
 * Candidates (all defined-risk; naked option selling is never generated, and any structure with an uncovered short leg
 * is rejected):
 *   strong bullish → ITM / ATM call, bull call spread        strong bearish → ITM / ATM put, bear put spread
 *   mild bullish / range → bull put (credit) spread          mild bearish / range → bear call (credit) spread
 *   neutral + high IV → iron condor / iron butterfly (pin)   neutral + volatility expansion + cheap IV → long straddle
 *
 * Every candidate is priced against the horizon's own distribution: expected P&L, P(profit), max profit/loss and
 * breakevens, all NET of brokerage, statutory charges and slippage. A high probability alone never selects a
 * structure — the score is return-on-risk × √P(profit) × liquidity × fit with the predicted category.
 */
class StrategyEngine(private val f: Filters = Filters(), private val costs: TransactionCosts = TransactionCosts()) {
    data class Filters(
        val minOi: Double = 2_000.0,
        val minVolume: Double = 500.0,
        val maxSpreadPct: Double = 3.0,
        val maxLastTradeAgeSec: Double = 300.0,
        val minPremium: Double = 2.0,
    )

    /** Maps model P(profit) → realised strategy win rate (null until enough logged strategy outcomes). */
    var calibrator: ((Double) -> Double)? = null

    companion object {
        /** §25 category from the horizon prediction, IV vs forecast and the expiry regime. */
        fun category(p: HorizonPrediction, ivRatio: Double, regime: ExpiryRegime): TradeCategory {
            val bull = p.bull; val bear = p.bear
            val edge = bull - bear
            val strongOk = p.confidence != ConfidenceLevel.LOW
            return when {
                p.direction == Direction.BULLISH && bull >= 0.60 && strongOk && regime != ExpiryRegime.RANGE_PIN -> TradeCategory.STRONG_BULLISH
                p.direction == Direction.BEARISH && bear >= 0.60 && strongOk && regime != ExpiryRegime.RANGE_PIN -> TradeCategory.STRONG_BEARISH
                abs(edge) < 0.08 || p.direction == Direction.NEUTRAL -> when {
                    regime == ExpiryRegime.VOLATILITY_EXPANSION && (ivRatio.isNaN() || ivRatio < 1.0) -> TradeCategory.NEUTRAL_VOL_EXPANSION
                    !ivRatio.isNaN() && ivRatio >= 1.05 -> TradeCategory.NEUTRAL_HIGH_IV
                    regime == ExpiryRegime.RANGE_PIN -> TradeCategory.NEUTRAL_HIGH_IV
                    else -> TradeCategory.NEUTRAL
                }
                edge > 0 -> TradeCategory.MILD_BULLISH
                else -> TradeCategory.MILD_BEARISH
            }
        }

        fun fit(kind: StrategyKind, cat: TradeCategory, intraday: Boolean = false): Double = when (cat) {
            TradeCategory.STRONG_BULLISH -> when (kind) {
                StrategyKind.LONG_CE, StrategyKind.BULL_CALL_SPREAD -> 1.0; StrategyKind.BULL_PUT_SPREAD -> 0.6; else -> 0.1 }
            TradeCategory.MILD_BULLISH -> when (kind) {
                StrategyKind.BULL_PUT_SPREAD -> 1.0; StrategyKind.BULL_CALL_SPREAD -> 0.7; StrategyKind.LONG_CE -> if (intraday) 0.6 else 0.35; else -> 0.1 }
            TradeCategory.STRONG_BEARISH -> when (kind) {
                StrategyKind.LONG_PE, StrategyKind.BEAR_PUT_SPREAD -> 1.0; StrategyKind.BEAR_CALL_SPREAD -> 0.6; else -> 0.1 }
            TradeCategory.MILD_BEARISH -> when (kind) {
                StrategyKind.BEAR_CALL_SPREAD -> 1.0; StrategyKind.BEAR_PUT_SPREAD -> 0.7; StrategyKind.LONG_PE -> if (intraday) 0.6 else 0.35; else -> 0.1 }
            TradeCategory.NEUTRAL_HIGH_IV -> when (kind) {
                StrategyKind.IRON_CONDOR, StrategyKind.IRON_BUTTERFLY -> 1.0; StrategyKind.BULL_PUT_SPREAD, StrategyKind.BEAR_CALL_SPREAD -> 0.4; else -> 0.05 }
            TradeCategory.NEUTRAL_VOL_EXPANSION -> when (kind) { StrategyKind.LONG_STRADDLE -> 1.0; else -> 0.1 }
            TradeCategory.NEUTRAL -> when (kind) { StrategyKind.IRON_CONDOR -> 0.5; else -> 0.1 }
        }
    }

    private class Ctx(val chain: OptionChain, val spot: Double, val now: Long, val tYears: Double, val step: Double, val rows: List<OptionStrikeRow>) {
        val atm: Double = rows.minBy { abs(it.strike - spot) }.strike
        fun row(k: Double) = rows.firstOrNull { abs(it.strike - k) < 1e-6 }
        fun nearest(x: Double) = rows.minBy { abs(it.strike - x) }.strike
    }

    private fun legOf(c: Ctx, action: LegAction, type: OptionType, k: Double): StrategyLeg? {
        val r = c.row(k) ?: return null
        val isCall = type == OptionType.CE
        val l = if (isCall) r.call else r.put
        val px = if (action == LegAction.BUY) ask(l) else bid(l)
        if (px <= 0) return null
        val iv = ivOf(l, isCall, c.spot, k, c.tYears)
        val delta = if (iv.isNaN()) Double.NaN else BlackScholes.price(isCall, c.spot, k, c.tYears, iv).delta
        return StrategyLeg(action, type, k, px, if (iv.isNaN()) Double.NaN else iv * 100, delta, l.oi, l.volume, spreadPct(l), l.lastTradeAgeSec)
    }

    /** Candidates held to expiry, priced on [pred]'s distribution. */
    fun expiryCandidates(kind: ExpiryKind, chain: OptionChain?, intel: ExpiryIntel?, pred: HorizonPrediction, spot: Double, now: Long, cat: TradeCategory): List<StrategyCandidate> {
        if (chain == null || chain.rows.size < 8) return emptyList()
        val rows = chain.rows.filter { it.strike > 0 }.sortedBy { it.strike }
        val step = rows.zipWithNext { a, b -> b.strike - a.strike }.filter { it > 0 }.minOrNull() ?: chain.strikeStep
        val c = Ctx(chain, spot, now, Session.yearsToExpiry(now, chain.expiryMillis), step, rows)
        val dist = PriceDistribution(spot, pred.components)
        val sigma = pred.sigmaPts
        val atm = c.atm
        val sup = intel?.support?.strike ?: Double.NaN
        val res = intel?.resistance?.strike ?: Double.NaN
        val width = (round(0.35 * sigma / step).coerceIn(2.0, 6.0)) * step
        fun itm(type: OptionType): Double {
            val cands = (1..4).map { if (type == OptionType.CE) atm - it * step else atm + it * step }.filter { c.row(it) != null }
            return cands.minByOrNull { k -> legOf(c, LegAction.BUY, type, k)?.delta?.let { abs(abs(it) - 0.65) } ?: 9.0 } ?: atm
        }
        val shortPut = c.nearest(listOf(sup, spot - 0.4 * sigma).filter { !it.isNaN() }.min()).coerceAtMost(atm - step)
        val shortCall = c.nearest(listOf(res, spot + 0.4 * sigma).filter { !it.isNaN() }.max()).coerceAtLeast(atm + step)
        val bullCallShort = c.nearest(maxOf(if (res.isNaN()) 0.0 else res, spot + 0.75 * sigma)).coerceIn(atm + 2 * step, atm + 8 * step)
        val bearPutShort = c.nearest(minOf(if (sup.isNaN()) Double.MAX_VALUE else sup, spot - 0.75 * sigma)).coerceIn(atm - 8 * step, atm - 2 * step)
        val flyCenter = intel?.pin?.takeIf { it.strength > 0.3 }?.strike?.let(c::nearest) ?: atm
        val flyWing = (round(maxOf(sigma, 3 * step) / step)) * step

        val specs: List<Pair<StrategyKind, List<Triple<LegAction, OptionType, Double>>>> = listOf(
            StrategyKind.LONG_CE to listOf(Triple(LegAction.BUY, OptionType.CE, atm)),
            StrategyKind.LONG_CE to listOf(Triple(LegAction.BUY, OptionType.CE, itm(OptionType.CE))),
            StrategyKind.LONG_PE to listOf(Triple(LegAction.BUY, OptionType.PE, atm)),
            StrategyKind.LONG_PE to listOf(Triple(LegAction.BUY, OptionType.PE, itm(OptionType.PE))),
            StrategyKind.BULL_CALL_SPREAD to listOf(Triple(LegAction.BUY, OptionType.CE, atm), Triple(LegAction.SELL, OptionType.CE, bullCallShort)),
            StrategyKind.BEAR_PUT_SPREAD to listOf(Triple(LegAction.BUY, OptionType.PE, atm), Triple(LegAction.SELL, OptionType.PE, bearPutShort)),
            StrategyKind.BULL_PUT_SPREAD to listOf(Triple(LegAction.SELL, OptionType.PE, shortPut), Triple(LegAction.BUY, OptionType.PE, shortPut - width)),
            StrategyKind.BEAR_CALL_SPREAD to listOf(Triple(LegAction.SELL, OptionType.CE, shortCall), Triple(LegAction.BUY, OptionType.CE, shortCall + width)),
            StrategyKind.IRON_CONDOR to listOf(
                Triple(LegAction.BUY, OptionType.PE, shortPut - width), Triple(LegAction.SELL, OptionType.PE, shortPut),
                Triple(LegAction.SELL, OptionType.CE, shortCall), Triple(LegAction.BUY, OptionType.CE, shortCall + width)),
            StrategyKind.IRON_BUTTERFLY to listOf(
                Triple(LegAction.BUY, OptionType.PE, flyCenter - flyWing), Triple(LegAction.SELL, OptionType.PE, flyCenter),
                Triple(LegAction.SELL, OptionType.CE, flyCenter), Triple(LegAction.BUY, OptionType.CE, flyCenter + flyWing)),
            StrategyKind.LONG_STRADDLE to listOf(Triple(LegAction.BUY, OptionType.CE, atm), Triple(LegAction.BUY, OptionType.PE, atm)),
        )
        return specs.distinctBy { (k, l) -> k.name + l.joinToString { "${it.first}${it.second}${it.third}" } }.mapNotNull { (k, spec) ->
            val legs = spec.map { (a, t, s) -> legOf(c, a, t, s) ?: return@mapNotNull null }
            evaluateAtExpiry(k, kind, chain.expiry, pred, legs, dist, c, cat)
        }
    }

    private fun legLiquidity(l: StrategyLeg): Double {
        val liq = M.clamp(0.6 * log10(1 + l.oi) / 6 + 0.4 * log10(1 + l.volume) / 6, 0.05, 1.0)
        val exec = if (l.spreadPct.isNaN()) 0.7 else M.clamp(1 - l.spreadPct / 5, 0.1, 1.0)
        return liq * exec
    }

    private fun legFailures(l: StrategyLeg): List<String> = buildList {
        val tag = "${l.strike.toInt()}${l.type}"
        if (l.oi < f.minOi) add("$tag OI < %,.0f".format(f.minOi))
        if (l.volume < f.minVolume) add("$tag volume < %,.0f".format(f.minVolume))
        if (l.spreadPct.isNaN()) add("$tag no bid/ask") else if (l.spreadPct > f.maxSpreadPct) add("$tag spread %.1f%%".format(l.spreadPct))
        if (!l.lastTradeAgeSec.isNaN() && l.lastTradeAgeSec > f.maxLastTradeAgeSec) add("$tag stale (%.0fs)".format(l.lastTradeAgeSec))
        if (l.action == LegAction.BUY && l.price < f.minPremium) add("$tag premium < ₹%.0f".format(f.minPremium))
        if (!l.iv.isNaN() && (l.iv > 150 || l.iv < 2)) add("$tag abnormal IV")
    }

    /**
     * Every short leg must be paired with a long leg of the same type (any strike): with as many long calls as short calls
     * the upside loss is capped, with as many long puts as short puts the downside loss is capped by the strike gap.
     */
    private fun nakedShort(legs: List<StrategyLeg>): Boolean = OptionType.values().any { t ->
        legs.count { it.type == t && it.action == LegAction.SELL } > legs.count { it.type == t && it.action == LegAction.BUY }
    }

    private fun evaluateAtExpiry(
        kind: StrategyKind, ek: ExpiryKind, expiry: String, pred: HorizonPrediction, legs: List<StrategyLeg>,
        dist: PriceDistribution, c: Ctx, cat: TradeCategory,
    ): StrategyCandidate {
        fun sgn(l: StrategyLeg) = if (l.action == LegAction.BUY) 1.0 else -1.0
        val net = legs.sumOf { sgn(it) * it.price }
        fun payoff(s: Double) = legs.sumOf { sgn(it) * intrinsic(it.type == OptionType.CE, s, it.strike) }
        // Costs per leg at its expected expiry value (round trip; conservative for legs that expire worthless).
        val cost = legs.sumOf { l ->
            val exit = dist.expectation { intrinsic(l.type == OptionType.CE, it, l.strike) }
            if (l.action == LegAction.BUY) costs.perUnit(l.price, exit) else costs.perUnit(exit, l.price)
        }
        fun pnl(s: Double) = payoff(s) - net - cost
        val grossEv = dist.expectation { payoff(it) - net }
        val ev = grossEv - cost
        val pProfit = dist.probability { pnl(it) > 0 }
        val callSlope = legs.filter { it.type == OptionType.CE }.sumOf { sgn(it) }
        val pts = (listOf(0.0, c.spot * 3) + legs.map { it.strike }).sorted()
        val maxLoss = -pts.minOf { pnl(it) }
        // Max profit within the distribution's plausible range (a long put "pays K at zero" is not a useful number).
        val lo = dist.grid.first().first; val hi = dist.grid.last().first
        val maxProfit = if (callSlope > 0) Double.NaN else (listOf(lo, hi) + legs.map { it.strike }.filter { it in lo..hi }).maxOf { pnl(it) }
        val grid = (listOf(0.0) + dist.grid.map { it.first } + legs.map { it.strike } + listOf(c.spot * 3)).sorted()
        val bes = grid.zipWithNext().filter { (a, b) -> pnl(a) * pnl(b) < 0 }.map { (a, b) ->
            var lo = a; var hi = b
            repeat(50) { val m = (lo + hi) / 2; if (pnl(lo) * pnl(m) <= 0) hi = m else lo = m }
            (lo + hi) / 2
        }
        return finish(kind, ek, expiry, pred, legs, net, maxProfit, maxLoss, bes, pProfit, ev, grossEv, cost, c, cat, intraday = false)
    }

    private fun finish(
        kind: StrategyKind, ek: ExpiryKind, expiry: String, pred: HorizonPrediction, legs: List<StrategyLeg>, net: Double,
        maxProfit: Double, maxLoss: Double, bes: List<Double>, pProfit: Double, ev: Double, grossEv: Double, cost: Double,
        c: Ctx, cat: TradeCategory, intraday: Boolean,
    ): StrategyCandidate {
        fun sgn(l: StrategyLeg) = if (l.action == LegAction.BUY) 1.0 else -1.0
        val greeks = legs.map { l ->
            val iv = if (l.iv.isNaN()) Double.NaN else l.iv / 100
            if (iv.isNaN()) null else sgn(l) to BlackScholes.price(l.type == OptionType.CE, c.spot, l.strike, c.tYears, iv)
        }
        val netDelta = greeks.filterNotNull().sumOf { (s, g) -> s * g.delta }
        val netTheta = greeks.filterNotNull().sumOf { (s, g) -> s * g.thetaPerDay }
        val netVega = greeks.filterNotNull().sumOf { (s, g) -> s * g.vega }
        val liquidity = legs.minOf { legLiquidity(it) }
        val fails = ArrayList<String>()
        legs.forEach { fails += legFailures(it) }
        if (nakedShort(legs)) fails += "Uncovered short leg (naked selling is never allowed)"
        if (maxLoss <= 0 || maxLoss.isNaN()) fails += "Max loss not finite / defined"
        val pCal = calibrator?.invoke(pProfit) ?: Double.NaN
        val rr = if (maxProfit.isNaN()) Double.NaN else maxProfit / maxLoss.coerceAtLeast(1e-6)
        val ror = ev / maxLoss.coerceAtLeast(1e-6)
        val fit = fit(kind, cat, intraday)
        val score = fit * ror.coerceAtLeast(0.0) * sqrt((if (pCal.isNaN()) pProfit else pCal).coerceIn(0.0, 1.0)) * liquidity
        val rationale = buildString {
            append("${cat.label} → ${kind.label}. ")
            append(if (net >= 0) "Debit ₹%.1f".format(net) else "Credit ₹%.1f".format(-net))
            append(", max loss ₹%.1f".format(maxLoss))
            append(if (maxProfit.isNaN()) ", upside open" else ", max profit ₹%.1f".format(maxProfit))
            append(", P(profit) %.0f%%, EV %+.1f per unit net of ₹%.2f costs".format(pProfit * 100, ev, cost))
            if (bes.isNotEmpty()) append(", breakeven ${bes.joinToString(" / ") { "%,.0f".format(it) }}")
            append(if (intraday) " (exit at the ${pred.id.label.lowercase()} horizon)." else " (held to ${ek.label.lowercase()} expiry ${expiry}).")
        }
        return StrategyCandidate(kind, ek, expiry, pred.id, legs, net, maxProfit, maxLoss, bes, pProfit, pCal, ev, grossEv, cost, rr, ror,
            netDelta, netTheta, netVega, liquidity, fit, score, fails.isEmpty(), fails, rationale)
    }

    /**
     * Intraday trades (H1): ATM / ITM calls or puts on the weekly chain, exited at the [pred] horizon. Each candidate is
     * re-priced at the horizon (Black-Scholes at the leg's IV, time to expiry reduced, exit at bid) over the horizon's
     * distribution.
     */
    fun intradayCandidates(chain: OptionChain?, pred: HorizonPrediction, spot: Double, now: Long, cat: TradeCategory): List<StrategyCandidate> {
        if (chain == null || chain.rows.size < 8) return emptyList()
        val rows = chain.rows.filter { it.strike > 0 }.sortedBy { it.strike }
        val step = rows.zipWithNext { a, b -> b.strike - a.strike }.filter { it > 0 }.minOrNull() ?: chain.strikeStep
        val c = Ctx(chain, spot, now, Session.yearsToExpiry(now, chain.expiryMillis), step, rows)
        val dist = PriceDistribution(spot, pred.components)
        val hYears = (pred.targetTime - now).coerceAtLeast(60_000L) / (365.0 * 86_400_000)
        val t2 = (c.tYears - hYears).coerceAtLeast(1.0 / (365 * 24 * 60))
        val out = ArrayList<StrategyCandidate>()
        for (type in OptionType.values()) {
            val kind = if (type == OptionType.CE) StrategyKind.LONG_CE else StrategyKind.LONG_PE
            val strikes = listOf(c.atm, if (type == OptionType.CE) c.atm - step else c.atm + step)
            for (k in strikes.distinct()) {
                val leg = legOf(c, LegAction.BUY, type, k) ?: continue
                val r = c.row(k) ?: continue
                val l = if (type == OptionType.CE) r.call else r.put
                val iv = if (leg.iv.isNaN()) continue else leg.iv / 100
                val halfSpread = if (l.bid > 0 && l.ask > 0) (l.ask - l.bid) / 2 else leg.price * 0.005
                fun value(s: Double) = (BlackScholes.price(type == OptionType.CE, s, k, t2, iv).price - halfSpread).coerceAtLeast(0.0)
                val grossEv = dist.expectation { value(it) - leg.price }
                val cost = costs.perUnit(leg.price, (leg.price + grossEv).coerceAtLeast(0.0))
                val ev = grossEv - cost
                val pProfit = dist.probability { value(it) - leg.price - cost > 0 }
                val maxLoss = leg.price + cost
                var a = if (type == OptionType.CE) spot else spot * 0.8; var b = if (type == OptionType.CE) spot * 1.2 else spot
                repeat(50) {
                    val m = (a + b) / 2
                    val v = value(m) - leg.price - cost
                    if (type == OptionType.CE) { if (v > 0) b = m else a = m } else { if (v > 0) a = m else b = m }
                }
                out += finish(kind, ExpiryKind.WEEKLY, chain.expiry, pred, listOf(leg), leg.price, Double.NaN, maxLoss, listOf((a + b) / 2),
                    pProfit, ev, grossEv, cost, c, cat, intraday = true).let { cand ->
                    val thetaShare = abs(cand.netThetaPerDay) * (pred.tradingMinutes / Session.SESSION_MINUTES) / leg.price
                    if (thetaShare > 0.25) cand.copy(passedFilters = false, failures = cand.failures + "Theta %.0f%% of premium over the horizon".format(thetaShare * 100)) else cand
                }
            }
        }
        return out
    }
}

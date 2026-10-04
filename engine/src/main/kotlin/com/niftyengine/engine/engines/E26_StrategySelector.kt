package com.niftyengine.engine.engines

import com.niftyengine.engine.core.BlackScholes
import com.niftyengine.engine.core.M
import com.niftyengine.engine.core.OptionClock
import com.niftyengine.engine.core.Session
import com.niftyengine.engine.core.TransactionCosts
import com.niftyengine.engine.model.FutureExpectation
import com.niftyengine.engine.model.InformationShock
import com.niftyengine.engine.model.LegAction
import com.niftyengine.engine.model.OptionAnalysis
import com.niftyengine.engine.model.OptionChain
import com.niftyengine.engine.model.OptionLeg
import com.niftyengine.engine.model.OptionStrikeRow
import com.niftyengine.engine.model.OptionType
import com.niftyengine.engine.model.PrimaryRegime
import com.niftyengine.engine.model.RegimeAssessment
import com.niftyengine.engine.model.ScenarioSet
import com.niftyengine.engine.model.ShockLevel
import com.niftyengine.engine.model.StrategyCandidate
import com.niftyengine.engine.model.StrategyLeg
import com.niftyengine.engine.model.StrategyPlan
import com.niftyengine.engine.model.StrategyType
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 26 — Strategy Selector (v5). The strategy is a consequence of the regime and the scenario shape, not a fixed choice:
 *
 *   strong directional (breakout share high, regime aligned, IV not rich) → BUY CALL / BUY PUT
 *   moderate directional, or IV rich                                     → BULL CALL / BEAR PUT debit spread
 *   range + IV above normal + no event ahead + no shock                   → IRON CONDOR (defined-risk option selling)
 *   conflict, reversal risk against the trade, or no positive-EV structure → NO TRADE
 *
 * Every structure is priced the same way: legs filled at ask (buy) / bid (sell), repriced with Black-Scholes at the
 * horizon over the scenario distribution, exits at mid ∓ half-spread, net of round-trip charges per leg. Strikes and
 * widths are searched (ATM ±1 for the long leg, spread width ≈ 1–2 breakout bands, condor shorts at ~1 band or the
 * OI walls), and the best return on risk wins.
 */
class StrategySelector(private val costs: TransactionCosts = TransactionCosts(), private val p: Params = Params()) {
    data class Params(
        val enableLongOptions: Boolean = true,
        val enableSpreads: Boolean = true,
        val enableCondor: Boolean = true,
        val maxSpreadPct: Double = 3.0,
        val minOi: Double = 2_000.0,
        val minVolume: Double = 500.0,
        val minPremium: Double = 5.0,
        /** Condors need ATM IV at least this much above its normal (0.05 = +5 %). */
        val condorMinIvRel: Double = 0.05,
        /** Debit spreads replace naked long options when ATM IV is this much above normal. */
        val richIvRel: Double = 0.15,
        val strongBreakoutShare: Double = 0.40,
    )

    data class Inputs(
        val chain: OptionChain?,
        val spot: Double,
        val now: Long,
        val horizonMinutes: Int,
        val scenarios: ScenarioSet,
        val dist: ScenarioDistribution,
        val regime: RegimeAssessment,
        val options: OptionAnalysis,
        /** ATM IV relative to its normal (NaN if unknown). */
        val ivRel: Double,
        val atmIvPct: Double,
        val callWall: Double,
        val putWall: Double,
        val expectation: FutureExpectation,
        val shock: InformationShock,
        /**
         * Credit structures earn time decay, which an hour can't show: they are valued and held until the session's
         * time exit (moves scaled by √(hold ÷ horizon)). 0 = use the decision horizon.
         */
        val creditHoldMinutes: Int = 0,
    )

    private class Ctx(
        val i: Inputs, val rows: Map<Double, OptionStrikeRow>, val step: Double, val atm: Double, val tYears: Double,
        val t2: Double, val t2Credit: Double, val creditHold: Int, val atmIv: Double, val volScale: Double,
    )

    fun select(i: Inputs): StrategyPlan {
        val chain = i.chain
        if (chain == null || chain.rows.size < 5 || i.dist.isEmpty) return StrategyPlan(rationale = listOf("Option chain unavailable — no strategy"))
        val sorted = chain.rows.sortedBy { it.strike }
        val step = sorted.zipWithNext { a, b -> b.strike - a.strike }.filter { it > 0 }.minOrNull() ?: chain.strikeStep
        val atm = sorted.minBy { abs(it.strike - i.spot) }.strike
        val tYears = Session.yearsToExpiry(i.now, chain.expiryMillis)
        // horizon values in trading time with variance-consistent IV (see OptionClock)
        val clock = OptionClock.of(i.now, chain.expiryMillis)
        val hold = if (i.creditHoldMinutes > 0) i.creditHoldMinutes else i.horizonMinutes
        val atmIv = if (!i.atmIvPct.isNaN() && i.atmIvPct > 0) i.atmIvPct / 100 else 0.13
        val ctx = Ctx(i, sorted.associateBy { it.strike }, step, atm, tYears, clock.after(i.horizonMinutes), clock.after(hold), hold, atmIv, clock.volScale)
        val sc = i.scenarios
        val rationale = ArrayList<String>()

        // ---- what the regime and scenario shape ask for
        val pUp = sc.pUp; val pDn = sc.pDown; val pR = sc.p(com.niftyengine.engine.model.Scenario.RANGE)
        val side = if (maxOf(pUp, pDn) > pR && abs(pUp - pDn) >= 0.05) (if (pUp > pDn) 1 else -1) else 0
        val share = if (side > 0) sc.breakoutShareUp else if (side < 0) sc.breakoutShareDown else 0.0
        val prim = i.regime.primary
        val aligned = (side > 0 && prim == PrimaryRegime.TREND_UP) || (side < 0 && prim == PrimaryRegime.TREND_DOWN) ||
            ((prim == PrimaryRegime.VOLATILITY_EXPANSION || prim == PrimaryRegime.EVENT_DRIVEN) && i.shock.score >= 0.2 && i.shock.direction * side > 0.2)
        val ivRich = !i.ivRel.isNaN() && i.ivRel >= p.richIvRel
        val strong = side != 0 && share >= p.strongBreakoutShare && aligned && !ivRich
        var preferred = when {
            prim == PrimaryRegime.CONFLICT -> { rationale += "Conflicting information blocks → no trade (wait for agreement)"; StrategyType.NO_TRADE }
            prim == PrimaryRegime.REVERSAL_RISK && side != 0 && i.regime.reversalSide == side -> {
                rationale += "Reversal risk against a ${if (side > 0) "bullish" else "bearish"} trade → no trade"; StrategyType.NO_TRADE
            }
            side > 0 -> if (strong) StrategyType.BUY_CALL else StrategyType.BULL_CALL_SPREAD
            side < 0 -> if (strong) StrategyType.BUY_PUT else StrategyType.BEAR_PUT_SPREAD
            pR >= 0.45 && prim == PrimaryRegime.RANGE && !i.ivRel.isNaN() && i.ivRel >= p.condorMinIvRel &&
                i.expectation.eventRiskAhead < 0.6 && i.shock.level <= ShockLevel.MINOR -> StrategyType.IRON_CONDOR
            else -> {
                rationale += if (pR >= maxOf(pUp, pDn)) "Range expected but " + when {
                    prim != PrimaryRegime.RANGE -> "regime is ${prim.label}"
                    i.ivRel.isNaN() || i.ivRel < p.condorMinIvRel -> "options are not rich (IV %s normal) — nothing worth selling".format(if (i.ivRel.isNaN()) "vs ?" else "%+.0f%% vs".format(i.ivRel * 100))
                    i.expectation.eventRiskAhead >= 0.6 -> "a scheduled event is ahead — no short gamma"
                    else -> "information shock in progress"
                } else "No directional edge (up %.0f%% / down %.0f%% / range %.0f%%)".format(pUp * 100, pDn * 100, pR * 100)
                StrategyType.NO_TRADE
            }
        }
        if (side != 0 && preferred != StrategyType.NO_TRADE) rationale += "%s %.0f%% (breakout share %.0f%%), regime %s%s → %s".format(
            if (side > 0) "Up" else "Down", maxOf(pUp, pDn) * 100, share * 100, prim.label,
            if (ivRich) ", IV %+.0f%% vs normal (rich)".format(i.ivRel * 100) else "", preferred.label)
        if (preferred == StrategyType.IRON_CONDOR) rationale += "Range %.0f%%, IV %+.0f%% vs normal, no event/shock → sell premium with defined risk".format(pR * 100, i.ivRel * 100)
        // respect disabled strategy families
        preferred = when {
            preferred == StrategyType.BUY_CALL && !p.enableLongOptions -> StrategyType.BULL_CALL_SPREAD
            preferred == StrategyType.BUY_PUT && !p.enableLongOptions -> StrategyType.BEAR_PUT_SPREAD
            preferred == StrategyType.BULL_CALL_SPREAD && !p.enableSpreads -> if (p.enableLongOptions) StrategyType.BUY_CALL else StrategyType.NO_TRADE
            preferred == StrategyType.BEAR_PUT_SPREAD && !p.enableSpreads -> if (p.enableLongOptions) StrategyType.BUY_PUT else StrategyType.NO_TRADE
            preferred == StrategyType.IRON_CONDOR && !p.enableCondor -> StrategyType.NO_TRADE
            else -> preferred
        }

        // ---- price the candidate structures
        val cands = ArrayList<StrategyCandidate>()
        val dirSide = if (side != 0) side else if (pUp >= pDn) 1 else -1
        val longType = if (dirSide > 0) StrategyType.BUY_CALL else StrategyType.BUY_PUT
        val spreadType = if (dirSide > 0) StrategyType.BULL_CALL_SPREAD else StrategyType.BEAR_PUT_SPREAD
        longOption(ctx, dirSide)?.let { cands += it }
        bestSpread(ctx, dirSide)?.let { cands += it }
        bestCondor(ctx)?.let { cands += it }

        fun ok(c: StrategyCandidate?) = c != null && c.feasible && c.expectedValue > 0
        val pick = cands.firstOrNull { it.type == preferred }
        val alt = when (preferred) {
            StrategyType.BUY_CALL, StrategyType.BUY_PUT -> cands.firstOrNull { it.type == spreadType }?.takeIf { p.enableSpreads }
            StrategyType.BULL_CALL_SPREAD, StrategyType.BEAR_PUT_SPREAD -> cands.firstOrNull { it.type == longType }?.takeIf { p.enableLongOptions }
            else -> null
        }
        val chosen = when {
            preferred == StrategyType.NO_TRADE -> null
            ok(pick) -> pick
            ok(alt) -> { rationale += "${preferred.label} has no positive net EV — ${alt!!.type.label} does (fallback)"; alt }
            else -> {
                rationale += "${preferred.label}: " + (pick?.let { c -> if (!c.feasible) c.issues.joinToString() else "net EV %+.1f per unit ≤ 0".format(c.expectedValue) } ?: "no strikes")
                null
            }
        }
        return StrategyPlan(chosen?.type ?: StrategyType.NO_TRADE, chosen, preferred, cands.sortedByDescending { it.returnOnRisk }, rationale)
    }

    // ------------------------------------------------------------------ structures

    private fun longOption(c: Ctx, side: Int): StrategyCandidate? {
        val type = if (side > 0) OptionType.CE else OptionType.PE
        val k = c.i.options.candidates.filter { it.type == type && it.passedFilters }.maxByOrNull { it.score }?.strike
            ?: c.i.options.candidates.filter { it.type == type }.maxByOrNull { it.score }?.strike ?: c.atm
        val leg = leg(c, k, type, LegAction.BUY) ?: return null
        return evaluate(c, if (side > 0) StrategyType.BUY_CALL else StrategyType.BUY_PUT, listOf(leg))
    }

    private fun bestSpread(c: Ctx, side: Int): StrategyCandidate? {
        val type = if (side > 0) OptionType.CE else OptionType.PE
        val b = c.i.scenarios.breakoutBand
        val longs = listOf(c.atm, c.atm - side * c.step, c.atm + side * c.step)
        val widths = listOf(1.0, 1.5, 2.0).map { m -> (ceil(m * b / c.step) * c.step).coerceAtLeast(c.step) }.distinct()
        val out = ArrayList<StrategyCandidate>()
        for (k1 in longs) for (w in widths) {
            val k2 = k1 + side * w
            val l1 = leg(c, k1, type, LegAction.BUY) ?: continue
            val l2 = leg(c, k2, type, LegAction.SELL) ?: continue
            out += evaluate(c, if (side > 0) StrategyType.BULL_CALL_SPREAD else StrategyType.BEAR_PUT_SPREAD, listOf(l1, l2))
        }
        return best(out)
    }

    private fun bestCondor(c: Ctx): StrategyCandidate? {
        val spot = c.i.spot
        // short strikes sit beyond the breakout band of the HOLD period (the condor is held to the time exit)
        val b = c.i.scenarios.breakoutBand * sqrt(c.creditHold.toDouble() / c.i.horizonMinutes.coerceAtLeast(1))
        fun up(x: Double) = ceil(x / c.step) * c.step
        fun dn(x: Double) = floor(x / c.step) * c.step
        val mults = listOf(1.0, 1.3, 1.6)
        val callShorts = (mults.map { up(spot + it * b) } +
            listOfNotNull(c.i.callWall.takeIf { !it.isNaN() && it >= spot + 0.8 * b && it <= spot + 2.5 * b })).distinct()
        val putShorts = (mults.map { dn(spot - it * b) } +
            listOfNotNull(c.i.putWall.takeIf { !it.isNaN() && it <= spot - 0.8 * b && it >= spot - 2.5 * b })).distinct()
        val out = ArrayList<StrategyCandidate>()
        for (kc in callShorts) for (kp in putShorts) for (wing in listOf(2, 3)) {
            val w = wing * c.step
            val legs = listOf(
                leg(c, kp - w, OptionType.PE, LegAction.BUY), leg(c, kp, OptionType.PE, LegAction.SELL),
                leg(c, kc, OptionType.CE, LegAction.SELL), leg(c, kc + w, OptionType.CE, LegAction.BUY),
            )
            if (legs.any { it == null }) continue
            out += evaluate(c, StrategyType.IRON_CONDOR, legs.filterNotNull())
        }
        return best(out)
    }

    private fun best(xs: List<StrategyCandidate>): StrategyCandidate? =
        xs.filter { it.feasible }.maxWithOrNull(compareBy<StrategyCandidate> { it.expectedValue > 0 }.thenBy { it.returnOnRisk }.thenBy { it.probProfit })
            ?: xs.maxByOrNull { it.returnOnRisk }

    // ------------------------------------------------------------------ pricing

    private fun quote(l: OptionLeg) = l.ltp > 0 || l.ask > 0 || l.bid > 0

    private fun leg(c: Ctx, k: Double, type: OptionType, action: LegAction): StrategyLeg? {
        val row = c.rows[k] ?: return null
        val l = if (type == OptionType.CE) row.call else row.put
        if (!quote(l)) return null
        val isCall = type == OptionType.CE
        val mid = if (l.bid > 0 && l.ask > 0) (l.bid + l.ask) / 2 else l.ltp
        val price = when (action) {
            LegAction.BUY -> if (l.ask > 0) l.ask else l.ltp
            LegAction.SELL -> if (l.bid > 0) l.bid else l.ltp
        }
        val spreadPct = if (l.bid > 0 && l.ask > 0 && mid > 0) (l.ask - l.bid) / mid * 100 else -1.0
        val iv = when {
            !l.iv.isNaN() && l.iv > 0 -> l.iv / 100
            else -> BlackScholes.impliedVol(isCall, c.i.spot, k, c.tYears, mid).takeIf { !it.isNaN() } ?: c.atmIv
        }
        val g = BlackScholes.price(isCall, c.i.spot, k, c.tYears, iv)
        return StrategyLeg(action, type, k, c.i.chain?.expiry ?: "", price, l.bid, l.ask, iv * 100, g.delta, spreadPct, l.oi, l.volume)
    }

    private fun evaluate(c: Ctx, type: StrategyType, legs: List<StrategyLeg>): StrategyCandidate {
        val spot = c.i.spot
        val issues = ArrayList<String>()
        fun sgn(l: StrategyLeg) = if (l.action == LegAction.BUY) 1.0 else -1.0
        fun half(l: StrategyLeg) = if (l.bid > 0 && l.ask > 0) (l.ask - l.bid) / 2 else l.price * 0.005
        val entry = legs.sumOf { sgn(it) * it.price }
        // valuation point: the decision horizon, or the time exit for credit structures (moves scaled by √time)
        val t2 = if (type.credit) c.t2Credit else c.t2
        val hold = if (type.credit) c.creditHold else c.i.horizonMinutes
        val scale = sqrt(hold.toDouble() / c.i.horizonMinutes.coerceAtLeast(1))
        // per-unit value of the position when the underlying has moved by m (exit: long at bid, short at ask)
        fun value(m: Double) = legs.sumOf { l ->
            val v = BlackScholes.price(l.type == OptionType.CE, spot + m * scale, l.strike, t2, l.iv / 100 * c.volScale).price
            if (l.action == LegAction.BUY) (v - half(l)).coerceAtLeast(0.0) else -(v + half(l))
        }
        val center = c.i.scenarios.center
        val cost = legs.sumOf { l ->
            val exit = BlackScholes.price(l.type == OptionType.CE, spot + center * scale, l.strike, t2, l.iv / 100 * c.volScale).price.coerceAtLeast(0.05)
            if (l.action == LegAction.BUY) costs.perUnit(l.price, exit) else costs.perUnit(exit, l.price)
        }
        val pnl = { m: Double -> value(m) - entry - cost }
        val ev = c.i.dist.expect(pnl)
        val pop = c.i.dist.probPositive(pnl)
        val rr = c.i.dist.gainLossRatio(pnl)

        val longs = legs.filter { it.action == LegAction.BUY }; val shorts = legs.filter { it.action == LegAction.SELL }
        val (maxProfit, maxLoss, breakevens) = when (type) {
            StrategyType.BUY_CALL -> Triple(Double.NaN, entry + cost, listOf(legs[0].strike + entry))
            StrategyType.BUY_PUT -> Triple(legs[0].strike - entry - cost, entry + cost, listOf(legs[0].strike - entry))
            StrategyType.BULL_CALL_SPREAD, StrategyType.BEAR_PUT_SPREAD -> {
                val width = abs(legs[1].strike - legs[0].strike)
                Triple(width - entry - cost, entry + cost,
                    listOf(if (type == StrategyType.BULL_CALL_SPREAD) legs[0].strike + entry else legs[0].strike - entry))
            }
            StrategyType.IRON_CONDOR -> {
                val credit = -entry
                val callWing = abs(longs.first { it.type == OptionType.CE }.strike - shorts.first { it.type == OptionType.CE }.strike)
                val putWing = abs(longs.first { it.type == OptionType.PE }.strike - shorts.first { it.type == OptionType.PE }.strike)
                Triple(credit - cost, maxOf(callWing, putWing) - credit + cost,
                    listOf(shorts.first { it.type == OptionType.PE }.strike - credit, shorts.first { it.type == OptionType.CE }.strike + credit))
            }
            StrategyType.NO_TRADE -> Triple(0.0, 0.0, emptyList())
        }
        for (l in legs) {
            if (l.spreadPct < 0) issues += "${l.strike.toInt()}${l.type}: no bid/ask"
            else if (!spreadOk(l, p.maxSpreadPct)) issues += "${l.strike.toInt()}${l.type} spread %.1f%%".format(l.spreadPct)
            if (l.oi < p.minOi) issues += "${l.strike.toInt()}${l.type} OI < %,.0f".format(p.minOi)
            if (l.volume < p.minVolume) issues += "${l.strike.toInt()}${l.type} volume < %,.0f".format(p.minVolume)
        }
        when (type) {
            StrategyType.BUY_CALL, StrategyType.BUY_PUT -> if (entry < p.minPremium) issues += "premium < ₹%.0f".format(p.minPremium)
            StrategyType.BULL_CALL_SPREAD, StrategyType.BEAR_PUT_SPREAD -> if (entry <= 0.5) issues += "debit ≤ ₹0.5 (quotes look stale)"
            StrategyType.IRON_CONDOR -> if (-entry <= 1.0) issues += "credit ≤ ₹1"
            else -> {}
        }
        if (maxLoss <= 0) issues += "non-positive max loss (bad quotes)"
        val liq = legs.minOf { l ->
            val lf = M.clamp(0.6 * log10(1 + l.oi) / 6 + 0.4 * log10(1 + l.volume) / 6, 0.05, 1.0)
            val ex = if (l.spreadPct < 0) 0.3 else M.clamp(1 - l.spreadPct / 5, 0.1, 1.0)
            0.5 * lf + 0.5 * ex
        }
        return StrategyCandidate(
            type, legs, entry, maxProfit, maxLoss, breakevens, pop, ev, ev + cost, cost,
            if (maxLoss > 0) ev / maxLoss else Double.NaN, rr, liq, issues.isEmpty(), issues, hold,
        )
    }

    companion object {
        /**
         * v5.1 benchmark instrument: the ATM option in the direction of recent momentum, bought at the ask — what a naive
         * trader would do at the same moment. Used only by the shadow benchmark book.
         */
        fun atmLong(chain: OptionChain?, spot: Double, side: Int, costs: TransactionCosts): StrategyCandidate? {
            val row = chain?.rows?.minByOrNull { abs(it.strike - spot) } ?: return null
            val type = if (side >= 0) OptionType.CE else OptionType.PE
            val l = if (type == OptionType.CE) row.call else row.put
            val ask = if (l.ask > 0) l.ask else l.ltp
            if (ask <= 0) return null
            val mid = if (l.bid > 0 && l.ask > 0) (l.bid + l.ask) / 2 else l.ltp
            val spreadPct = if (l.bid > 0 && l.ask > 0 && mid > 0) (l.ask - l.bid) / mid * 100 else -1.0
            val leg = StrategyLeg(LegAction.BUY, type, row.strike, chain.expiry, ask, l.bid, l.ask, l.iv, Double.NaN, spreadPct, l.oi, l.volume)
            val cost = costs.perUnit(ask, ask)
            return StrategyCandidate(if (type == OptionType.CE) StrategyType.BUY_CALL else StrategyType.BUY_PUT, listOf(leg), ask,
                if (type == OptionType.CE) Double.NaN else row.strike - ask - cost, ask + cost,
                listOf(if (type == OptionType.CE) row.strike + ask else row.strike - ask),
                Double.NaN, Double.NaN, Double.NaN, cost, Double.NaN, Double.NaN, 1.0, true)
        }

        /** Cheap hedge wings quote 0.05/0.10: judge them by the absolute spread (≤ 4 ticks), everything else by %. */
        const val MAX_ABS_SPREAD = 0.20

        fun spreadOk(l: StrategyLeg, maxPct: Double): Boolean =
            l.spreadPct in 0.0..maxPct || (l.bid > 0 && l.ask > 0 && l.ask - l.bid <= MAX_ABS_SPREAD + 1e-9)

        fun describe(c: StrategyCandidate): String {
            val legs = c.legs.joinToString(" + ") { "${if (it.action == LegAction.BUY) "B" else "S"} ${it.strike.roundToInt()}${it.type} @%.1f".format(it.price) }
            return "${c.type.label}: $legs"
        }
    }
}

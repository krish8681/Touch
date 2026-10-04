package com.niftyengine.engine.engines

import com.niftyengine.engine.core.BlackScholes
import com.niftyengine.engine.core.M
import com.niftyengine.engine.core.OptionClock
import com.niftyengine.engine.core.Session
import com.niftyengine.engine.core.TransactionCosts
import com.niftyengine.engine.model.HorizonProb
import com.niftyengine.engine.model.DirectionResult
import com.niftyengine.engine.model.ExpectedMove
import com.niftyengine.engine.model.OptionAnalysis
import com.niftyengine.engine.model.OptionCandidate
import com.niftyengine.engine.model.OptionChain
import com.niftyengine.engine.model.OptionLeg
import com.niftyengine.engine.model.OptionType
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * 15 — Option Selection Engine.
 *
 * Does NOT default to ATM. For ITM → OTM candidates on the favoured side it reprices each option
 * (Black-Scholes, chain IV) under bull / bear / range scenarios built from the expected-move model,
 * then ranks by
 *   OPTION_SCORE = P(direction) × P(profit) × payoff × liquidity × IV × theta × execution
 * after hard filters (liquidity, spread, volume, IV distortion, theta risk, stale price).
 *
 * This is the OPTION OUTCOME model, deliberately separate from the direction model: "NIFTY bull 72%"
 * is not "this call is profitable 72%" — strike distance, IV, theta, spread, costs and expiry decide that.
 * Expected value and P(profit) are NET of brokerage, statutory charges and slippage ([TransactionCosts]).
 */
class OptionSelectionEngine(
    private val f: Filters = Filters(),
    private val costs: TransactionCosts = TransactionCosts(),
    /** Maps model P(profit) → historically observed option win rate; null until enough option outcomes exist. */
    var optionCalibrator: ((Double) -> Double)? = null,
) {
    data class Filters(
        val minOi: Double = 2_000.0,
        val minVolume: Double = 500.0,
        val maxSpreadPct: Double = 3.0,
        val maxIvDistortion: Double = 1.35,
        val maxThetaShare: Double = 0.25,
        val minPremium: Double = 5.0,
        /** Reject quotes whose last trade is older than this (when the feed reports it). */
        val maxLastTradeAgeSec: Double = 300.0,
        val itmSteps: Int = 2,
        val otmSteps: Int = 6,
    )

    /**
     * @param dist v5 scenario distribution: when given, EV and P(profit) integrate over the five scenarios
     * (breakout / continuation / range / reversal / sharp decline) instead of three point scenarios.
     */
    fun analyze(
        chain: OptionChain?, spot: Double, now: Long, dir: DirectionResult, move: ExpectedMove, atmIvPct: Double,
        probs: HorizonProb = HorizonProb(move.horizonMinutes, dir.pBull, dir.pBear, dir.pRange, false),
        dist: ScenarioDistribution? = null,
    ): OptionAnalysis {
        if (chain == null || chain.rows.isEmpty()) return OptionAnalysis(null, null, emptyList(), atmIvPct, 0.0, listOf("Option chain unavailable"))
        val notes = mutableListOf<String>()
        val rows = chain.rows.sortedBy { it.strike }
        val step = rows.zipWithNext { a, b -> b.strike - a.strike }.filter { it > 0 }.minOrNull() ?: chain.strikeStep
        val atm = rows.minBy { abs(it.strike - spot) }.strike
        val tYears = Session.yearsToExpiry(now, chain.expiryMillis)
        // horizon value in trading time with variance-consistent IV (see OptionClock)
        val clock = OptionClock.of(now, chain.expiryMillis)
        val t2 = clock.after(move.horizonMinutes)
        val dte = tYears * 365
        val atmIv = if (!atmIvPct.isNaN() && atmIvPct > 0) atmIvPct / 100 else move.annualVolUsed
        val preferred = if (probs.pBull >= probs.pBear) OptionType.CE else OptionType.PE
        if (dte < 0.3) notes += "Expiry day: theta and gamma risk are extreme"

        val sigmaH = move.sigmaPoints
        val upMove = if (move.expectedMovePoints > 0) move.expectedMovePoints else 0.8 * sigmaH
        val dnMove = if (move.expectedMovePoints < 0) -move.expectedMovePoints else 0.8 * sigmaH
        val scenarios = listOf(spot + upMove to probs.pBull, spot - dnMove to probs.pBear, spot to probs.pRange)
        val drift = (probs.pBull - probs.pBear) * 0.8 * sigmaH

        val out = mutableListOf<OptionCandidate>()
        for (type in OptionType.values()) {
            val isCall = type == OptionType.CE
            val lo = if (isCall) atm - f.itmSteps * step else atm - f.otmSteps * step
            val hi = if (isCall) atm + f.otmSteps * step else atm + f.itmSteps * step
            for (r in rows.filter { it.strike in lo..hi }) {
                val leg = if (isCall) r.call else r.put
                candidate(type, r.strike, leg, spot, tYears, t2, atmIv, sigmaH, drift, scenarios, probs, move, dist?.takeIf { !it.isEmpty }, clock.volScale)?.let { out += it }
            }
        }
        val ranked = out.sortedByDescending { it.score }
        val best = ranked.firstOrNull { it.type == preferred && it.passedFilters }
        if (best == null) notes += "No ${preferred.name} strike passed liquidity/spread/IV/theta filters"
        if (best != null && best.moneyness != "ATM") notes += "Best risk/reward is ${best.moneyness}, not ATM"
        return OptionAnalysis(preferred, best, ranked, atmIv * 100, dte, notes)
    }

    private fun candidate(
        type: OptionType, k: Double, leg: OptionLeg, spot: Double, tYears: Double, t2: Double, atmIv: Double,
        sigmaH: Double, drift: Double, scenarios: List<Pair<Double, Double>>, dir: HorizonProb, move: ExpectedMove,
        dist: ScenarioDistribution? = null,
        /** Calendar-IV → trading-time-IV factor for horizon repricing (1.0 = legacy calendar repricing). */
        volScale: Double = 1.0,
    ): OptionCandidate? {
        val isCall = type == OptionType.CE
        val ltp = leg.ltp
        if (ltp <= 0 && leg.ask <= 0) return null
        val premium = if (leg.ask > 0) leg.ask else ltp
        val mid = if (leg.bid > 0 && leg.ask > 0) (leg.bid + leg.ask) / 2 else ltp
        val spreadPct = if (leg.bid > 0 && leg.ask > 0 && mid > 0) (leg.ask - leg.bid) / mid * 100 else Double.NaN
        val iv = when {
            !leg.iv.isNaN() && leg.iv > 0 -> leg.iv / 100
            else -> BlackScholes.impliedVol(isCall, spot, k, tYears, mid).takeIf { !it.isNaN() } ?: atmIv
        }
        if (iv.isNaN() || iv <= 0) return null
        val g = BlackScholes.price(isCall, spot, k, tYears, iv)
        val ivH = iv * volScale
        val halfSpread = if (spreadPct.isNaN()) premium * 0.005 else (leg.ask - leg.bid) / 2

        // Scenario repricing at the horizon (exit at bid ≈ model − half-spread).
        val grossEv = dist?.expect { m -> BlackScholes.price(isCall, spot + m, k, t2, ivH).price - halfSpread - premium }
            ?: scenarios.sumOf { (s, p) -> p * (BlackScholes.price(isCall, s, k, t2, ivH).price - halfSpread - premium) }
        // Charges + slippage per unit, evaluated at the expected exit price.
        val cost = costs.perUnit(premium, (premium + grossEv).coerceAtLeast(0.0))
        val ev = grossEv - cost
        val target = scenarios[if (isCall) 0 else 1].first
        val valueAtTarget = BlackScholes.price(isCall, target, k, t2, ivH).price

        // Net breakeven spot at horizon (value − half-spread = premium + costs), by bisection.
        val hurdle = premium + cost
        var a = if (isCall) spot else spot - 6 * sigmaH - premium
        var b = if (isCall) spot + 6 * sigmaH + premium else spot
        repeat(50) {
            val m = (a + b) / 2
            val v = BlackScholes.price(isCall, m, k, t2, ivH).price - halfSpread
            if (isCall) { if (v > hurdle) b = m else a = m } else { if (v > hurdle) a = m else b = m }
        }
        val breakeven = (a + b) / 2
        val sd = sigmaH.coerceAtLeast(1e-6)
        val probProfit = when {
            dist != null -> if (isCall) dist.probAbove(breakeven - spot) else dist.probBelow(breakeven - spot)
            isCall -> 1 - M.normCdf((breakeven - spot - drift) / sd)
            else -> M.normCdf((breakeven - spot - drift) / sd)
        }
        val itm = if (isCall) spot >= k else spot <= k
        val probReach = if (itm) 1.0 else {
            val distSd = abs(ln(k / spot)) / (sd / spot)
            val adj = (if (isCall) drift else -drift) / sd
            M.clamp(2 * (1 - M.normCdf(distSd - adj)), 0.0, 1.0)
        }
        val sq = sqrt(tYears)
        val d2 = (ln(spot / k) + (0.065 - iv * iv / 2) * tYears) / (iv * sq)
        val probItm = if (isCall) M.normCdf(d2) else M.normCdf(-d2)

        val liquidity = M.clamp(0.6 * log10(1 + leg.oi) / 6 + 0.4 * log10(1 + leg.volume) / 6, 0.05, 1.0)
        val ivDist = iv / atmIv
        val ivFactor = M.clamp(1 - (ivDist - 1).coerceAtLeast(0.0) * 2, 0.2, 1.0)
        val horizonDays = move.horizonMinutes / 375.0
        val thetaShare = abs(g.thetaPerDay) * horizonDays / premium
        val thetaFactor = 1 - M.clamp(thetaShare, 0.0, 0.9)
        val execution = if (spreadPct.isNaN()) 0.7 else M.clamp(1 - spreadPct / 5, 0.1, 1.0)
        val dirProb = if (isCall) dir.pBull else dir.pBear
        val retPct = ev / premium * 100
        val payoff = M.clamp(0.5 + retPct / 100, 0.05, 2.0)
        val probProfitCal = optionCalibrator?.invoke(probProfit) ?: Double.NaN
        val pProfitUsed = if (probProfitCal.isNaN()) probProfit else probProfitCal
        val score = dirProb * pProfitUsed * payoff * liquidity * ivFactor * thetaFactor * execution

        val fails = buildList {
            if (leg.oi < f.minOi) add("OI < %,.0f".format(f.minOi))
            if (leg.volume < f.minVolume) add("Volume < %,.0f".format(f.minVolume))
            if (!spreadPct.isNaN() && spreadPct > f.maxSpreadPct) add("Spread %.1f%% > %.1f%%".format(spreadPct, f.maxSpreadPct))
            if (spreadPct.isNaN()) add("No bid/ask")
            if (ivDist > f.maxIvDistortion) add("IV %.0f%% of ATM".format(ivDist * 100))
            if (thetaShare > f.maxThetaShare) add("Theta %.0f%% of premium over horizon".format(thetaShare * 100))
            if (premium < f.minPremium) add("Premium < ₹%.0f".format(f.minPremium))
            if (!leg.lastTradeAgeSec.isNaN() && leg.lastTradeAgeSec > f.maxLastTradeAgeSec)
                add("Stale price: last trade %.0fs ago".format(leg.lastTradeAgeSec))
            if (iv > 1.5 || iv < 0.02) add("Abnormal IV %.0f%%".format(iv * 100))
        }
        val steps = (k - spot) / (sd.coerceAtLeast(1.0))
        val moneyness = when {
            abs(k - spot) <= 0.5 * (k * 0.0025).coerceAtLeast(25.0) -> "ATM"
            itm -> "ITM"
            else -> if (abs(steps) > 1.5) "Far OTM" else "OTM"
        }
        return OptionCandidate(
            strike = k, type = type, premium = premium, bid = leg.bid, ask = leg.ask, iv = iv * 100,
            delta = g.delta, gamma = g.gamma, thetaPerDay = g.thetaPerDay, vega = g.vega,
            oi = leg.oi, volume = leg.volume, spreadPct = if (spreadPct.isNaN()) -1.0 else spreadPct,
            probReach = probReach, probProfit = probProfit, probItmAtExpiry = probItm, breakevenSpot = breakeven,
            expectedValue = ev, expectedReturnPct = retPct, valueAtTarget = valueAtTarget,
            liquidityFactor = liquidity, ivFactor = ivFactor, thetaFactor = thetaFactor, executionFactor = execution,
            score = score, moneyness = moneyness, passedFilters = fails.isEmpty(), filterFailures = fails,
            grossExpectedValue = grossEv, costPerUnit = cost, lastTradeAgeSec = leg.lastTradeAgeSec,
            probProfitCalibrated = probProfitCal,
        )
    }
}

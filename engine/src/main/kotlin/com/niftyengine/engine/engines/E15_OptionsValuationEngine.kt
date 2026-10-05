package com.niftyengine.engine.engines

import com.niftyengine.engine.core.BlackScholes
import com.niftyengine.engine.core.PriceDistribution
import com.niftyengine.engine.core.Session
import com.niftyengine.engine.core.TransactionCosts
import com.niftyengine.engine.model.ExpiryKind
import com.niftyengine.engine.model.OptionChain
import com.niftyengine.engine.model.OptionLeg
import com.niftyengine.engine.model.OptionType
import com.niftyengine.engine.model.OptionValueClass
import com.niftyengine.engine.model.OptionValuation
import com.niftyengine.engine.model.ValuationSummary
import kotlin.math.abs
import kotlin.math.exp

/**
 * 15 — Options Valuation Engine (§18, §26: "which option is priced attractively?").
 *
 * Each strike is valued against the model's own distribution for that expiry (which carries the predicted direction and
 * the expected range): fair value = discounted expected payoff at expiry. Comparing that with the ask, the probability of
 * finishing in the money and the theta burn classifies the option as attractive, reasonably priced, too expensive,
 * too far OTM or exposed to excessive theta. ATM IV is also compared with the model's forecast of realised volatility
 * (IV rich → favour defined-risk credit structures; IV cheap → favour debit structures).
 */
object OptionsValuationEngine {
    private const val R = 0.065

    fun mid(l: OptionLeg) = if (l.bid > 0 && l.ask > 0) (l.bid + l.ask) / 2 else l.ltp
    fun ask(l: OptionLeg) = if (l.ask > 0) l.ask else l.ltp
    fun bid(l: OptionLeg) = if (l.bid > 0) l.bid else l.ltp * 0.99
    fun spreadPct(l: OptionLeg): Double = if (l.bid > 0 && l.ask > 0) (l.ask - l.bid) / ((l.ask + l.bid) / 2) * 100 else Double.NaN

    fun ivOf(l: OptionLeg, isCall: Boolean, spot: Double, k: Double, tYears: Double): Double {
        if (!l.iv.isNaN() && l.iv > 0) return l.iv / 100
        val m = mid(l)
        return if (m > 0) BlackScholes.impliedVol(isCall, spot, k, tYears, m) else Double.NaN
    }

    fun intrinsic(isCall: Boolean, s: Double, k: Double) = if (isCall) (s - k).coerceAtLeast(0.0) else (k - s).coerceAtLeast(0.0)

    fun analyze(
        kind: ExpiryKind, chain: OptionChain?, dist: PriceDistribution, spot: Double, now: Long, atmIvPct: Double,
        forecastVol: Double, costs: TransactionCosts,
    ): ValuationSummary? {
        if (chain == null || chain.rows.size < 5) return null
        val tY = Session.yearsToExpiry(now, chain.expiryMillis)
        val dte = tY * 365
        val disc = exp(-R * tY)
        val sigma = dist.sigmaPts
        val rows = chain.rows.filter { abs(it.strike - spot) <= 2.5 * sigma.coerceAtLeast(spot * 0.005) }.sortedBy { it.strike }
        val out = ArrayList<OptionValuation>()
        for (r in rows) for (type in OptionType.values()) {
            val isCall = type == OptionType.CE
            val leg = if (isCall) r.call else r.put
            val a = ask(leg)
            if (a <= 0) continue
            val iv = ivOf(leg, isCall, spot, r.strike, tY)
            val fair = disc * dist.expectation { intrinsic(isCall, it, r.strike) }
            val cost = costs.perUnit(a, fair)
            val pItm = if (isCall) dist.pAbove(r.strike) else dist.pBelow(r.strike)
            val pProfit = dist.probability { intrinsic(isCall, it, r.strike) - a - cost > 0 }
            val g = if (iv.isNaN()) null else BlackScholes.price(isCall, spot, r.strike, tY, iv)
            val theta = g?.thetaPerDay ?: Double.NaN
            val thetaShare = if (theta.isNaN()) Double.NaN else abs(theta) / a
            val edge = (fair - a) / a
            val otm = if (isCall) r.strike > spot else r.strike < spot
            val cls = when {
                otm && pItm < 0.10 -> OptionValueClass.TOO_FAR_OTM
                !thetaShare.isNaN() && ((dte <= 2 && thetaShare >= 0.15) || thetaShare >= 0.25) -> OptionValueClass.EXCESSIVE_THETA
                edge < -0.15 -> OptionValueClass.TOO_EXPENSIVE
                edge > 0.10 -> OptionValueClass.ATTRACTIVE
                else -> OptionValueClass.FAIR
            }
            out += OptionValuation(r.strike, type, a, mid(leg), if (iv.isNaN()) Double.NaN else iv * 100, fair, edge, pItm, pProfit,
                g?.delta ?: Double.NaN, theta, thetaShare, leg.oi, leg.volume, spreadPct(leg), cls)
        }
        val ratio = if (atmIvPct.isNaN() || forecastVol.isNaN() || forecastVol <= 0) Double.NaN else atmIvPct / 100 / forecastVol
        val view = when {
            ratio.isNaN() -> "IV vs forecast unavailable"
            ratio > 1.15 -> "IV rich vs forecast (%.2f×) — favour defined-risk credit structures".format(ratio)
            ratio < 0.90 -> "IV cheap vs forecast (%.2f×) — favour debit structures".format(ratio)
            else -> "IV fair vs forecast (%.2f×)".format(ratio)
        }
        return ValuationSummary(kind, chain.expiry, atmIvPct, forecastVol * 100, ratio, view, out)
    }
}

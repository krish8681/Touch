package com.niftyengine.engine.engines

import com.niftyengine.engine.core.EngineState
import com.niftyengine.engine.core.M
import com.niftyengine.engine.core.PriceDistribution
import com.niftyengine.engine.model.Breakout
import com.niftyengine.engine.model.EventRiskLevel
import com.niftyengine.engine.model.ExpiryIntel
import com.niftyengine.engine.model.ExpiryRegime
import com.niftyengine.engine.model.FactorReading
import com.niftyengine.engine.model.FiiReport
import com.niftyengine.engine.model.MarketRegime
import com.niftyengine.engine.model.RegimeEvidence
import com.niftyengine.engine.model.RegimeReport
import kotlin.math.abs

/**
 * 12 — Regime Engine (§13) and Expiry Regime Engine (§14).
 *
 * The market regime is identified BEFORE the horizon scores are computed, because it adapts the factor weights (§28).
 * Each regime is scored as the weighted share of its conditions that are met; conditions whose data is missing are
 * skipped (and a regime with too few evaluable conditions is discounted). EVENT SHOCK wins whenever its evidence is
 * strong; otherwise the best regime above 0.55, else MIXED. A small hysteresis avoids flip-flopping between cycles.
 */
class RegimeEngine(private val state: EngineState) {
    data class Inputs(
        val globalIntraday: Double, val globalWeekly: Double,
        val fii: FiiReport,
        val vixState: VixState, val vixLevel: Double, val vixPercentile: Double,
        val us10y5d: Double, val dxy5d: Double, val crude1d: Double, val crude5d: Double, val usdinr5d: Double, val sp1d: Double,
        val rbi: FactorReading, val liquidityCr: Double, val creditGrowth: Double,
        val earnings: FactorReading, val epsRevisionPct: Double, val epsSurprise: Double, val beatRatio: Double, val earningsNews: Double,
        val sectorLeadership: FactorReading, val domesticSectors5d: Double, val indiaMacro: FactorReading,
        val newsShock: String?, val c15m: Double,
        val dayRangePct: Double, val typicalRangePct: Double, val range5dPct: Double, val typicalRange5dPct: Double,
        val pinStrength: Double, val oiConcentration: Double, val eventRiskH2: EventRiskLevel,
    )

    private class Cond(val label: String, val met: Boolean?, val w: Double)

    private fun evidence(r: MarketRegime, conds: List<Cond>, additive: Boolean = false): RegimeEvidence {
        val evaluable = conds.filter { it.met != null }
        val total = conds.sumOf { it.w }
        val evalW = evaluable.sumOf { it.w }
        val metW = evaluable.filter { it.met == true }.sumOf { it.w }
        val score = when {
            evaluable.isEmpty() -> 0.0
            additive -> M.clamp(metW / 2.0, 0.0, 1.0)
            else -> metW / evalW * minOf(1.0, evalW / (0.6 * total))
        }
        return RegimeEvidence(r, score,
            evaluable.filter { it.met == true }.map { it.label },
            conds.filter { it.met != true }.map { it.label + if (it.met == null) " (no data)" else "" })
    }

    private fun n(x: Double, f: (Double) -> Boolean): Boolean? = if (x.isNaN()) null else f(x)
    private fun r(x: FactorReading, f: (Double) -> Boolean): Boolean? = if (!x.available) null else f(x.direction)

    fun classify(i: Inputs, now: Long): RegimeReport {
        val g = listOf(i.globalIntraday, i.globalWeekly).filter { !it.isNaN() }
        val fiiOk = i.fii.available
        val ev = listOf(
            evidence(MarketRegime.RISK_ON, listOf(
                Cond("Global markets ↑", if (g.isEmpty()) null else g.any { it > 0.15 } && g.none { it < -0.15 }, 1.5),
                Cond("FII improving", if (!fiiOk) null else i.fii.score > 10 || (i.fii.futLongPctChange5d > 0 && i.fii.score > -10), 1.2),
                Cond("VIX stable / down", i.vixState == VixState.FALLING || i.vixState == VixState.STABLE, 1.0),
                Cond("Yields stable / down", n(i.us10y5d) { it <= 1.5 }, 0.8),
                Cond("Crude stable", n(i.crude5d) { abs(it) < 4 }, 0.6),
                Cond("INR stable", n(i.usdinr5d) { abs(it) < 0.6 }, 0.6),
            )),
            evidence(MarketRegime.RISK_OFF, listOf(
                Cond("Global markets ↓", if (g.isEmpty()) null else g.any { it < -0.15 } && g.none { it > 0.15 }, 1.5),
                Cond("VIX ↑", i.vixState == VixState.RISING || i.vixState == VixState.SPIKING, 1.0),
                Cond("US yields ↑", n(i.us10y5d) { it > 1.5 }, 0.8),
                Cond("USD ↑", n(i.dxy5d) { it > 0.6 }, 0.8),
                Cond("FII selling", if (!fiiOk) null else i.fii.score < -10 || i.fii.cashLatestCr < -1500, 1.2),
                Cond("Crude ↑", n(i.crude5d) { it > 4 }, 0.6),
            )),
            evidence(MarketRegime.DOMESTIC_BULLISH, listOf(
                Cond("RBI supportive", r(i.rbi) { it > 15 }, 1.2),
                Cond("Liquidity ↑ (surplus)", n(i.liquidityCr) { it > 0 }, 0.8),
                Cond("Credit growth ↑", n(i.creditGrowth) { it > 12 }, 0.6),
                Cond("Earnings ↑", r(i.earnings) { it > 15 }, 0.8),
                Cond("Domestic sectors strong", if (i.domesticSectors5d.isNaN() && !i.sectorLeadership.available) null
                    else (i.domesticSectors5d > 0.5 || (i.sectorLeadership.available && i.sectorLeadership.direction > 20)), 1.0),
                Cond("Indian macro supportive", r(i.indiaMacro) { it > 15 }, 0.6),
            )),
            evidence(MarketRegime.EARNINGS_EXPANSION, listOf(
                Cond("EPS revisions ↑", n(i.epsRevisionPct) { it > 0.3 }, 1.2),
                Cond("Earnings surprises positive", if (i.epsSurprise.isNaN() && i.earningsNews.isNaN()) null
                    else (!i.epsSurprise.isNaN() && i.epsSurprise > 0) || (!i.earningsNews.isNaN() && i.earningsNews > 0.1), 1.2),
                Cond("Beat ratio high", n(i.beatRatio) { it > 0.6 }, 0.8),
                Cond("Earnings factor strong", r(i.earnings) { it > 30 }, 0.8),
            )),
            evidence(MarketRegime.EVENT_SHOCK, listOf(
                Cond(i.newsShock ?: "Unexpected major news", i.newsShock != null, 2.0),
                Cond("VIX spiking", i.vixState == VixState.SPIKING, 1.2),
                Cond("Abrupt NIFTY move (>0.6% in 15m)", abs(i.c15m) > 0.6, 1.0),
                Cond("Large crude move (>4%)", n(i.crude1d) { abs(it) > 4 }, 0.8),
                Cond("Global shock (S&P > 2%)", n(i.sp1d) { abs(it) > 2 }, 0.8),
            ), additive = true),
            evidence(MarketRegime.RANGE_COMPRESSION, listOf(
                Cond("VIX low", if (i.vixLevel.isNaN()) null else i.vixLevel < 13 || (!i.vixPercentile.isNaN() && i.vixPercentile < 35), 1.0),
                Cond("Price range narrow", if (i.typicalRangePct.isNaN() && i.typicalRange5dPct.isNaN()) null else
                    (!i.typicalRangePct.isNaN() && i.dayRangePct < 0.7 * i.typicalRangePct) || (!i.typicalRange5dPct.isNaN() && i.range5dPct < 0.75 * i.typicalRange5dPct), 1.2),
                Cond("OI concentrated", if (i.pinStrength.isNaN() && i.oiConcentration.isNaN()) null else
                    (!i.pinStrength.isNaN() && i.pinStrength > 0.35) || (!i.oiConcentration.isNaN() && i.oiConcentration > 0.35), 1.0),
                Cond("No major catalyst", i.eventRiskH2.ordinal <= EventRiskLevel.MEDIUM.ordinal, 0.8),
            )),
        )
        val shock = ev.first { it.regime == MarketRegime.EVENT_SHOCK }
        val ranked = ev.filter { it.regime != MarketRegime.EVENT_SHOCK }.sortedByDescending { it.score }
        var pick = when {
            shock.score >= 0.6 -> shock
            ranked.first().score >= 0.55 -> ranked.first()
            else -> null
        }
        // Hysteresis: keep the previous regime while its evidence is still almost as strong.
        val prev = state.regimeHistory.lastOrNull()?.second
        if (pick != null && prev != null && prev != pick.regime && pick.regime != MarketRegime.EVENT_SHOCK) {
            ev.firstOrNull { it.regime == prev }?.takeIf { it.score >= 0.5 && it.score >= pick!!.score - 0.08 }?.let { pick = it }
        }
        val regime = pick?.regime ?: MarketRegime.MIXED
        state.pushRegime(now, regime)
        val secondary = ev.filter { it.regime != regime && it.score >= 0.45 }.maxByOrNull { it.score }?.regime
        return RegimeReport(regime, pick?.score ?: ranked.first().score, secondary, ev.sortedByDescending { it.score })
    }

    companion object {
        /**
         * §14 — expiry regime + breakout probability for one chain, using that expiry's price distribution.
         * Walls that are being unwound make a breakout through them more likely.
         */
        fun expiryRegime(
            intel: ExpiryIntel, dist: PriceDistribution, fiiScore: Double, globalScore: Double, eventRisk: EventRiskLevel,
            vixState: VixState, c15m: Double, migration: Double,
        ): ExpiryIntel {
            val spot = intel.spot
            val sigma = dist.sigmaPts.coerceAtLeast(1.0)
            val up = intel.resistance?.strike ?: (spot + sigma)
            val dn = intel.support?.strike ?: (spot - sigma)
            fun weak(unwind: Double, write: Double) = if (unwind + write <= 0) 0.0 else M.clamp((unwind - write) / (unwind + write))
            val weakUp = weak(intel.callUnwinding, intel.callWriting)
            val weakDn = weak(intel.putUnwinding, intel.putWriting)
            var pUp = dist.pAbove(up) * (1 + 0.3 * weakUp)
            var pDn = dist.pBelow(dn) * (1 + 0.3 * weakDn)
            if (pUp + pDn > 0.98) { val k = 0.98 / (pUp + pDn); pUp *= k; pDn *= k }
            val breakout = Breakout(up, dn, pUp, pDn, 1 - pUp - pDn, when {
                pUp > 1.3 * pDn && pUp > 0.2 -> "Upside breakout more likely (call walls ${if (weakUp > 0.2) "being unwound" else "holding"})"
                pDn > 1.3 * pUp && pDn > 0.2 -> "Downside breakdown more likely (put walls ${if (weakDn > 0.2) "being unwound" else "holding"})"
                else -> "Likely to stay inside the OI range"
            })
            data class C(val label: String, val met: Boolean, val w: Double)
            fun score(cs: List<C>) = cs.filter { it.met }.sumOf { it.w } / cs.sumOf { it.w } to cs.filter { it.met }.map { it.label }
            val writingBull = intel.putWriting > intel.callWriting && intel.putWriting > 0
            val writingBear = intel.callWriting > intel.putWriting && intel.callWriting > 0
            val pin = intel.pin
            val candidates = mapOf(
                ExpiryRegime.VOLATILITY_EXPANSION to score(listOf(
                    C("IV rising (%+.1f pts)".format(intel.ivChange), intel.ivChange > 0.8 || vixState == VixState.RISING || vixState == VixState.SPIKING, 1.2),
                    C("Major event before expiry (${eventRisk.label})", eventRisk.ordinal >= EventRiskLevel.HIGH.ordinal, 1.0),
                    C("OI repositioning", abs(migration) > 0.4, 0.8),
                    C("Large price movement", abs(c15m) > 0.5, 0.8),
                )),
                ExpiryRegime.BULLISH_EXPANSION to score(listOf(
                    C("Put support strengthening", writingBull, 1.0),
                    C("Call resistance weakening", intel.callUnwinding > intel.callWriting, 1.0),
                    C("Upside breakout favoured", pUp > 1.3 * pDn, 1.0),
                    C("FII supportive", fiiScore > 10, 0.8),
                    C("Global regime supportive", globalScore > 0.1, 0.8),
                )),
                ExpiryRegime.BEARISH_EXPANSION to score(listOf(
                    C("Put support weakening", intel.putUnwinding > intel.putWriting, 1.0),
                    C("Call resistance strengthening", writingBear, 1.0),
                    C("Downside breakdown favoured", pDn > 1.3 * pUp, 1.0),
                    C("FII negative", fiiScore < -10, 0.8),
                    C("Global regime negative", globalScore < -0.1, 0.8),
                )),
                ExpiryRegime.RANGE_PIN to score(listOf(
                    C("High OI concentration", (pin?.strength ?: 0.0) > 0.35 || intel.oiConcentration > 0.35, 1.2),
                    C("IV flat / declining", intel.ivChange <= 0.2, 1.0),
                    C("No major catalyst", eventRisk.ordinal <= EventRiskLevel.MEDIUM.ordinal, 0.8),
                    C("Price near OI concentration", pin != null && abs(pin.strike - spot) < 0.5 * sigma, 1.0),
                    C("Range more likely than breakout", 1 - pUp - pDn > 0.55, 0.8),
                )),
            )
            val best = candidates.maxBy { it.value.first }
            val regime = if (best.value.first >= 0.5) best.key else ExpiryRegime.UNDEFINED
            return intel.copy(breakout = breakout, regime = regime,
                regimeReasons = if (regime == ExpiryRegime.UNDEFINED) listOf("No expiry regime has majority evidence (best: ${best.key.label} %.0f%%)".format(best.value.first * 100))
                else best.value.second.map { "✓ $it" } + "evidence %.0f%%".format(best.value.first * 100))
        }
    }
}

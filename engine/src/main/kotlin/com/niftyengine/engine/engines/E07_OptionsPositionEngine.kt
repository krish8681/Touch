package com.niftyengine.engine.engines

import com.niftyengine.engine.core.EngineState
import com.niftyengine.engine.core.M
import com.niftyengine.engine.model.Detail
import com.niftyengine.engine.model.EngineSignal
import com.niftyengine.engine.model.MarketSnapshot
import com.niftyengine.engine.model.OptionChain
import com.niftyengine.engine.model.OptionStrikeRow
import kotlin.math.abs
import kotlin.math.min

/**
 * 07 — Options Positioning Engine.
 *
 * PCR is only one feature. The engine looks at OI clusters (call/put walls), ΔOI writing near ATM,
 * OI migration (where the centre of open interest is moving), IV skew, volume/OI and max pain.
 */
class OptionsPositionEngine(private val state: EngineState) {
    data class Result(
        val signal: EngineSignal,
        val callWall: Double,
        val putWall: Double,
        val atmIv: Double,
        val straddle: Double,
        val maxPain: Double,
        /** 0..1 evidence that walls pin the market in a range. */
        val rangeEvidence: Double,
    )

    fun analyze(s: MarketSnapshot, now: Long): Result {
        val chain = s.optionChain
        if (chain == null || chain.rows.size < 5) {
            return Result(EngineSignal.unavailable("Options", "option chain unavailable"),
                Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0.0)
        }
        val spot = if (chain.underlying > 0) chain.underlying else s.nifty.last
        val rows = chain.rows.filter { abs(it.strike - spot) / spot < 0.06 }.sortedBy { it.strike }
        if (rows.size < 5) return Result(EngineSignal.unavailable("Options", "too few strikes near spot"),
            Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0.0)
        if (state.openingChain == null) state.openingChain = chain

        val ceOi = rows.sumOf { it.call.oi }
        val peOi = rows.sumOf { it.put.oi }
        val pcr = if (ceOi > 0) peOi / ceOi else Double.NaN
        val ceAdd = rows.sumOf { it.call.changeOi }
        val peAdd = rows.sumOf { it.put.changeOi }

        val callWall = rows.filter { it.strike >= spot }.maxByOrNull { it.call.oi }?.strike ?: Double.NaN
        val putWall = rows.filter { it.strike <= spot }.maxByOrNull { it.put.oi }?.strike ?: Double.NaN
        val ceConc = rows.map { it.call.oi }.sortedDescending().take(3).sum() / ceOi.coerceAtLeast(1.0)
        val peConc = rows.map { it.put.oi }.sortedDescending().take(3).sum() / peOi.coerceAtLeast(1.0)

        // Writing pressure near ATM (±3 strikes): put writing = support (bullish), call writing = resistance.
        val atm = rows.minBy { abs(it.strike - spot) }
        val atmIdx = rows.indexOf(atm)
        val near = rows.subList((atmIdx - 3).coerceAtLeast(0), (atmIdx + 4).coerceAtMost(rows.size))
        val nearCe = near.sumOf { it.call.changeOi }
        val nearPe = near.sumOf { it.put.changeOi }
        val writing = (nearPe - nearCe) / (abs(nearPe) + abs(nearCe)).coerceAtLeast(1.0)

        // OI migration: centre of mass of OI now vs base (prior-day OI = OI − ΔOI, or opening chain).
        val base = state.openingChain?.takeIf { it !== chain && it.rows.size >= 5 }
        fun com(rs: List<OptionStrikeRow>, call: Boolean, useBase: Boolean): Double {
            var num = 0.0; var den = 0.0
            for (r in rs) {
                val leg = if (call) r.call else r.put
                val w = if (useBase) (leg.oi - leg.changeOi).coerceAtLeast(0.0) else leg.oi
                num += r.strike * w; den += w
            }
            return if (den > 0) num / den else Double.NaN
        }
        val ceShift: Double; val peShift: Double
        if (base != null) {
            val baseRows = base.rows.filter { abs(it.strike - spot) / spot < 0.06 }
            ceShift = com(rows, true, false) - com(baseRows, true, false)
            peShift = com(rows, false, false) - com(baseRows, false, false)
        } else {
            ceShift = com(rows, true, false) - com(rows, true, true)
            peShift = com(rows, false, false) - com(rows, false, true)
        }
        val step = chain.strikeStep.coerceAtLeast(1.0)
        val migration = M.squash((DataNormalizer.nz(ceShift) + DataNormalizer.nz(peShift)) / 2 / step, 1.5)

        // IV: ATM average, and skew between ~2% OTM put and call.
        fun ivOf(x: Double) = if (x.isNaN() || x <= 0) Double.NaN else x
        val atmIv = listOf(ivOf(atm.call.iv), ivOf(atm.put.iv)).filter { !it.isNaN() }.let { if (it.isEmpty()) Double.NaN else it.average() }
        val otmPut = rows.minByOrNull { abs(it.strike - spot * 0.98) }
        val otmCall = rows.minByOrNull { abs(it.strike - spot * 1.02) }
        val skew = (otmPut?.put?.iv?.let(::ivOf) ?: Double.NaN) - (otmCall?.call?.iv?.let(::ivOf) ?: Double.NaN)
        // Typical NIFTY put skew ≈ +2 vol points; extra put demand above that = hedging = bearish tilt.
        val skewScore = if (skew.isNaN()) 0.0 else -M.squash(skew - 2.0, 3.0)

        val straddle = atm.call.ltp + atm.put.ltp
        val volOi = rows.sumOf { it.call.volume + it.put.volume } / (ceOi + peOi).coerceAtLeast(1.0)

        // Max pain: strike minimising total option-holder payoff.
        val maxPain = rows.minBy { k ->
            rows.sumOf { r -> r.call.oi * (k.strike - r.strike).coerceAtLeast(0.0) + r.put.oi * (r.strike - k.strike).coerceAtLeast(0.0) }
        }.strike

        val pcrScore = if (pcr.isNaN()) 0.0 else M.squash(pcr - 1.0, 0.35)
        val distCall = if (callWall.isNaN()) 1.0 else (callWall - spot) / spot * 100
        val distPut = if (putWall.isNaN()) 1.0 else (spot - putWall) / spot * 100
        val wallScore = M.clamp((distCall - distPut) / 0.6) * 0.5 // closer to put wall (support) → mildly bullish
        val rangeEvidence = if (callWall.isNaN() || putWall.isNaN()) 0.0 else
            M.clamp((1.5 - (distCall + distPut)) / 1.5, 0.0, 1.0) * min(1.0, (ceConc + peConc))

        val score = M.clamp(0.30 * writing + 0.25 * migration + 0.20 * pcrScore + 0.15 * skewScore + 0.10 * wallScore)
        if (now - state.previousChainTime > 60_000) { state.previousChain = chain; state.previousChainTime = now }

        val tags = buildList {
            if (writing > 0.3) add("PUT_WRITING") else if (writing < -0.3) add("CALL_WRITING")
            if (migration > 0.3) add("OI_MIGRATING_UP") else if (migration < -0.3) add("OI_MIGRATING_DOWN")
            if (rangeEvidence > 0.5) add("WALLS_PINNING")
            if (skew > 4) add("PUT_SKEW_ELEVATED")
        }
        return Result(
            EngineSignal("Options", score, 0.85, tags, listOf(
                Detail("PCR (OI / ΔOI)", "%.2f / %s".format(pcr, if (ceAdd > 0 && peAdd > 0) "%.2f".format(peAdd / ceAdd) else "–")),
                Detail("Call wall / Put wall", "%.0f / %.0f".format(callWall, putWall)),
                Detail("ATM writing (PE−CE ΔOI)", "%+.0f%%".format(writing * 100)),
                Detail("OI migration CE / PE", "%+.0f / %+.0f pts".format(DataNormalizer.nz(ceShift), DataNormalizer.nz(peShift))),
                Detail("ATM IV / skew", "%.1f%% / %+.1f".format(atmIv, DataNormalizer.nz(skew))),
                Detail("Straddle / Max pain", "%.0f / %.0f".format(straddle, maxPain)),
                Detail("Concentration CE / PE", "%.0f%% / %.0f%%".format(ceConc * 100, peConc * 100)),
                Detail("Volume/OI", "%.2f".format(volOi)),
            )),
            callWall, putWall, atmIv, straddle, maxPain, rangeEvidence,
        )
    }

    companion object {
        fun atmStrike(chain: OptionChain, spot: Double) = chain.rows.minByOrNull { abs(it.strike - spot) }?.strike ?: spot
    }
}

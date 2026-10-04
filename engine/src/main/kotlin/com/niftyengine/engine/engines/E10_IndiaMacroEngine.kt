package com.niftyengine.engine.engines

import com.niftyengine.engine.core.M
import com.niftyengine.engine.model.Detail
import com.niftyengine.engine.model.EngineSignal
import com.niftyengine.engine.model.GlobalAsset
import com.niftyengine.engine.model.MarketSnapshot

/**
 * 10 — India Macro Engine (INR / crude / rates) + FPI/DII flow engine.
 *
 * FAST variables (USDINR, India 10Y, crude, liquidity) drive the intraday score.
 * SLOW variables (GDP, CPI trend, PMI, credit growth, policy) only add a capped regime bias —
 * GDP must never produce an intraday signal by itself.
 */
class IndiaMacroEngine(private val norm: DataNormalizer) {
    data class Result(val signal: EngineSignal, val fastScore: Double, val slowScore: Double)

    fun analyze(s: MarketSnapshot, now: Long, sessionStart: Long): Result {
        val fast = mutableListOf<Pair<Double, Double>>() // (weight, score)
        val details = mutableListOf<Detail>()
        fun asset(a: GlobalAsset, w: Double) {
            val d = s.global[a] ?: return
            if (d.prevClose <= 0) return
            val f = norm.features(d, now, sessionStart, a.typicalDailyMovePct)
            val z = (f.c1d + 0.5 * DataNormalizer.nz(f.c1h)) / a.typicalDailyMovePct
            val sc = a.riskSign * M.squash(z, 1.2)
            fast += w to sc
            details += Detail(a.label, "%.2f (%+.2f%%) → %+.2f".format(d.last, f.c1d, sc))
        }
        asset(GlobalAsset.USDINR, 0.35)
        asset(GlobalAsset.BRENT, 0.30)
        if (s.global[GlobalAsset.BRENT] == null) asset(GlobalAsset.WTI, 0.30)
        asset(GlobalAsset.INDIA10Y, 0.20)
        val m = s.macro
        if (!m.liquidityCr.isNaN()) {
            val sc = M.squash(m.liquidityCr / 100_000.0, 1.0)
            fast += 0.15 to sc
            details += Detail("Liquidity", "₹%,.0f cr → %+.2f".format(m.liquidityCr, sc))
        }
        val fastScore = if (fast.isEmpty()) 0.0 else fast.sumOf { it.first * it.second } / fast.sumOf { it.first }

        val slow = mutableListOf<Double>()
        if (!m.cpiYoY.isNaN() && !m.cpiPrevYoY.isNaN()) slow += -M.squash(m.cpiYoY - m.cpiPrevYoY, 0.5)
        if (!m.cpiYoY.isNaN()) slow += -M.squash(m.cpiYoY - 4.0, 2.0) * 0.5 // vs RBI 4% target
        if (!m.gdpGrowth.isNaN() && !m.gdpPrevGrowth.isNaN()) slow += M.squash(m.gdpGrowth - m.gdpPrevGrowth, 0.8)
        if (!m.pmiManufacturing.isNaN()) slow += M.squash(m.pmiManufacturing - 55, 3.0)
        if (!m.creditGrowth.isNaN()) slow += M.squash(m.creditGrowth - 12, 4.0) * 0.5
        if (m.lastPolicyChangeBps != 0.0) slow += -M.squash(m.lastPolicyChangeBps, 25.0)
        val slowScore = if (slow.isEmpty()) 0.0 else M.clamp(slow.average(), -0.3, 0.3)
        if (slow.isNotEmpty()) details += Detail("Slow macro bias", "%+.2f (capped ±0.30)".format(slowScore))

        if (fast.isEmpty() && slow.isEmpty()) return Result(EngineSignal.unavailable("INR/crude/rates", "no macro inputs"), 0.0, 0.0)
        val score = M.clamp(0.8 * fastScore + 0.2 * (slowScore / 0.3))
        val tags = buildList {
            if (fastScore < -0.3) add("MACRO_HEADWIND") else if (fastScore > 0.3) add("MACRO_TAILWIND")
        }
        return Result(EngineSignal("INR/crude/rates", score, if (fast.isEmpty()) 0.3 else 0.85, tags, details), fastScore, slowScore)
    }
}

/** FPI/DII flows: DIIs are modelled explicitly as a counterweight to FPI selling. */
class FlowEngine {
    fun analyze(s: MarketSnapshot): EngineSignal {
        val f = s.flows ?: return EngineSignal.unavailable("FPI/DII", "flow data unavailable")
        val fpi = if (!f.fpi5dCr.isNaN()) 0.5 * f.fpiNetCr + 0.5 * f.fpi5dCr / 5 else f.fpiNetCr
        val dii = if (!f.dii5dCr.isNaN()) 0.5 * f.diiNetCr + 0.5 * f.dii5dCr / 5 else f.diiNetCr
        val net = fpi + 0.85 * dii
        val score = M.squash(net, 4000.0)
        val tags = buildList {
            if (fpi < -1000 && dii > -0.7 * fpi) add("DII_ABSORBING_FPI_SELLING")
            if (fpi > 1000 && dii > 0) add("JOINT_BUYING")
            if (fpi < -1000 && dii < 0) add("JOINT_SELLING")
        }
        return EngineSignal("FPI/DII", score, 0.6, tags, listOfNotNull(
            Detail("FPI net", "₹%,.0f cr".format(f.fpiNetCr)),
            Detail("DII net", "₹%,.0f cr".format(f.diiNetCr)),
            if (!f.fpi5dCr.isNaN()) Detail("5D FPI / DII", "₹%,.0f / ₹%,.0f cr".format(f.fpi5dCr, f.dii5dCr)) else null,
            Detail("As of", f.date.ifBlank { "latest" }),
        ))
    }
}

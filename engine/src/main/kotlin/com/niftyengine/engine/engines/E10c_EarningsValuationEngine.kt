package com.niftyengine.engine.engines

import com.niftyengine.engine.core.Composite
import com.niftyengine.engine.core.Composite.Part
import com.niftyengine.engine.core.Fresh
import com.niftyengine.engine.core.M
import com.niftyengine.engine.model.ExpectationChannel
import com.niftyengine.engine.model.ExpectationReport
import com.niftyengine.engine.model.Factor
import com.niftyengine.engine.model.FactorReading
import com.niftyengine.engine.model.HorizonGroup
import com.niftyengine.engine.model.HorizonId
import com.niftyengine.engine.model.MarketSnapshot

/**
 * 10c — Earnings / EPS expectations (H2 12 %, H3 20 %) and valuation (H3 10 %).
 *
 * Earnings: forward-EPS revisions, the latest quarter's aggregate surprise (reported vs expected growth), the share of
 * companies beating estimates, and constituent earnings news from the Expectation Engine (results vs estimates).
 * Valuation: NIFTY P/E against a configurable fair band and the earnings-yield gap to the India 10-year yield.
 * Valuation is a weak one-month predictor, so its reliability is set low.
 */
object EarningsValuationEngine {
    data class Config(val fairPeLow: Double = 20.0, val fairPeHigh: Double = 24.0, val india10y: Double = Double.NaN)

    fun earnings(s: MarketSnapshot, h: HorizonId, ref: Long, exp: ExpectationReport, reliability: Double): FactorReading {
        val e = s.earnings
        val g = h.group
        val revision = if (!e.forwardEps.isNaN() && !e.forwardEpsPrev.isNaN() && e.forwardEpsPrev > 0)
            M.squash((e.forwardEps / e.forwardEpsPrev - 1) * 100 / 1.5, 1.0) else Double.NaN
        val surprise = if (!e.epsGrowthActual.isNaN() && !e.epsGrowthExpected.isNaN()) M.squash((e.epsGrowthActual - e.epsGrowthExpected) / 5.0, 1.0) else Double.NaN
        val beat = if (!e.beatRatio.isNaN()) M.squash((e.beatRatio - 0.55) / 0.15, 1.0) else Double.NaN
        val news = exp.channels[ExpectationChannel.EARNINGS]?.get(g)?.takeIf { kotlin.math.abs(it) > 1e-6 } ?: Double.NaN
        val w = if (g == HorizonGroup.H2) listOf(0.2, 0.3, 0.15, 0.35) else listOf(0.4, 0.25, 0.15, 0.2)
        val parts = listOf(
            Part("Forward EPS revision", revision, w[0], if (revision.isNaN()) "" else "%.1f → %.1f".format(e.forwardEpsPrev, e.forwardEps)),
            Part("Quarter surprise (actual − expected)", surprise, w[1], if (surprise.isNaN()) "" else "%+.1f%% vs %+.1f%%".format(e.epsGrowthActual, e.epsGrowthExpected)),
            Part("Beat ratio", beat, w[2], if (beat.isNaN()) "" else "%.0f%%".format(e.beatRatio * 100)),
            Part("Earnings news (expectation engine)", news, w[3]),
        )
        val manual = listOf(revision, surprise, beat).any { !it.isNaN() }
        val age = if (e.asOf > 0) ((ref - e.asOf) / 1000.0).coerceAtLeast(0.0) else Double.NaN
        val fresh = if (manual) Fresh.of(age, Fresh.Cadence.MONTHLY, h) else 1.0
        return Composite.reading(Factor.EARNINGS, parts, fresh, reliability * if (manual) 0.7 else 0.55, e.asOf, age,
            if (manual) e.source else "news",
            summary = { r -> when { r.direction > 15 -> "improving"; r.direction < -15 -> "deteriorating"; else -> "steady" } +
                if (!revision.isNaN()) " · EPS rev %+.1f%%".format((e.forwardEps / e.forwardEpsPrev - 1) * 100) else "" },
            missingWhy = "no earnings inputs (Setup) or earnings news")
    }

    fun valuation(s: MarketSnapshot, ref: Long, cfg: Config, reliability: Double): FactorReading {
        val v = s.valuation ?: return FactorReading.missing(Factor.VALUATION, "NIFTY P/E unavailable")
        if (v.pe.isNaN() || v.pe <= 0) return FactorReading.missing(Factor.VALUATION, "NIFTY P/E unavailable")
        val mid = (cfg.fairPeLow + cfg.fairPeHigh) / 2
        val half = ((cfg.fairPeHigh - cfg.fairPeLow) / 2).coerceAtLeast(0.5)
        val band = M.squash((mid - v.pe) / half, 1.0)
        val ey = 100 / v.pe
        val gap = if (cfg.india10y.isNaN()) Double.NaN else M.squash((ey - cfg.india10y + 2.0) / 1.0, 1.0)
        val age = Fresh.dailyAgeSec(v.asOf, ref)
        return Composite.reading(Factor.VALUATION, listOf(
            Part("P/E vs fair band", band, 0.7, "P/E %.1f vs %.0f–%.0f".format(v.pe, cfg.fairPeLow, cfg.fairPeHigh)),
            Part("Earnings yield − 10Y", gap, 0.3, if (gap.isNaN()) "" else "%.2f%% − %.2f%%".format(ey, cfg.india10y)),
        ), Fresh.of(age, Fresh.Cadence.DAILY, HorizonId.MONTHLY), reliability * 0.6, v.asOf, age, v.source,
            summary = { "P/E %.1f%s · %s".format(v.pe, if (v.pb.isNaN()) "" else ", P/B %.2f".format(v.pb),
                when { v.pe < cfg.fairPeLow -> "cheap"; v.pe > cfg.fairPeHigh -> "rich"; else -> "fair" }) })
    }
}

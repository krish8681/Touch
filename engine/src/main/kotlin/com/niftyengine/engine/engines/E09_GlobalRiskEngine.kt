package com.niftyengine.engine.engines

import com.niftyengine.engine.core.Composite
import com.niftyengine.engine.core.Composite.Part
import com.niftyengine.engine.core.Fresh
import com.niftyengine.engine.core.M
import com.niftyengine.engine.model.Detail
import com.niftyengine.engine.model.EngineSignal
import com.niftyengine.engine.model.Factor
import com.niftyengine.engine.model.FactorReading
import com.niftyengine.engine.model.GlobalAsset
import com.niftyengine.engine.model.HorizonGroup
import com.niftyengine.engine.model.HorizonId
import com.niftyengine.engine.model.MarketSnapshot
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 09 — Global market / global risk regime (H1 "Global market", H2/H3 "Global risk regime").
 *
 * One composite per view: each asset's move is normalised by its typical daily move (× √days for multi-day views) and
 * signed by risk polarity (equities +, US VIX / yields / DXY −, gold mildly −). Crude and USD/INR are separate factors.
 *  • intraday: overnight/1-day change + live drift, plus GIFT Nifty around the open
 *  • weekly: 5-session change (needs daily history), blended with the 1-day view
 *  • monthly: 20-session trend + US VIX level
 */
class GlobalRiskEngine(private val norm: DataNormalizer) {
    private val weights = mapOf(
        GlobalAsset.SP500 to 0.15, GlobalAsset.NASDAQ to 0.12, GlobalAsset.DOW to 0.04, GlobalAsset.DAX to 0.06,
        GlobalAsset.NIKKEI to 0.08, GlobalAsset.HANGSENG to 0.08, GlobalAsset.SHANGHAI to 0.04,
        GlobalAsset.US_VIX to 0.12, GlobalAsset.US10Y to 0.10, GlobalAsset.US2Y to 0.04,
        GlobalAsset.DXY to 0.11, GlobalAsset.GOLD to 0.06,
    )

    data class Result(
        val signal: EngineSignal,
        /** Composite scores −1..1 (NaN = not enough data). */
        val intraday: Double,
        val weekly: Double,
        val monthly: Double,
        val perAsset: Map<GlobalAsset, Double>,
        val globalVolStress: Double,
        val usVix: Double,
        /** 5-session % changes used by the regime engine. */
        val change5d: Map<GlobalAsset, Double>,
        val change1d: Map<GlobalAsset, Double>,
        val asOf: Long,
    )

    private fun composite(values: Map<GlobalAsset, Double>): Double {
        var num = 0.0; var den = 0.0
        for ((a, w) in weights) { val v = values[a] ?: continue; if (v.isNaN()) continue; num += w * v; den += w }
        return if (den < 0.2) Double.NaN else M.clamp(num / den)
    }

    fun analyze(s: MarketSnapshot, now: Long, sessionStart: Long): Result {
        val intra = LinkedHashMap<GlobalAsset, Double>(); val wk = HashMap<GlobalAsset, Double>(); val mo = HashMap<GlobalAsset, Double>()
        val c5 = HashMap<GlobalAsset, Double>(); val c1 = HashMap<GlobalAsset, Double>()
        for (a in GlobalAsset.values()) {
            val d = s.global[a] ?: continue
            if (d.prevClose <= 0 && d.daily.isEmpty()) continue
            val f = norm.features(d, now, sessionStart, a.typicalDailyMovePct)
            c1[a] = f.c1d
            val z = (f.c1d + 0.5 * DataNormalizer.nz(f.c1h)) / a.typicalDailyMovePct
            intra[a] = a.riskSign * M.squash(z, 1.2)
            val r5 = DataNormalizer.sessionsChange(d, 5); c5[a] = r5
            if (!r5.isNaN()) wk[a] = a.riskSign * M.squash(r5 / (a.typicalDailyMovePct * sqrt(5.0)), 1.2)
            val r20 = DataNormalizer.sessionsChange(d, 20)
            if (!r20.isNaN()) mo[a] = a.riskSign * M.squash(r20 / (a.typicalDailyMovePct * sqrt(20.0)), 1.2)
        }
        val ci = composite(intra); val cw = composite(wk); val cm = composite(mo)
        val usVix = s.global[GlobalAsset.US_VIX]?.last ?: Double.NaN
        val stress = if (usVix.isNaN()) 0.0 else M.clamp((usVix - 18) / 15, 0.0, 1.0)
        val asOf = s.global.values.maxOfOrNull { it.asOf } ?: 0L
        if (ci.isNaN()) return Result(EngineSignal.unavailable("Global risk", "global quotes unavailable"), Double.NaN, cw, cm, intra, stress, usVix, c5, c1, asOf)
        fun label(x: Double) = when {
            x.isNaN() -> "–"; x > 0.5 -> "STRONG_RISK_ON"; x > 0.15 -> "RISK_ON"; x < -0.5 -> "STRONG_RISK_OFF"; x < -0.15 -> "RISK_OFF"; else -> "MIXED"
        }
        val top = intra.entries.sortedByDescending { abs(it.value) }.take(4)
        return Result(
            EngineSignal("Global risk", ci, (intra.keys.sumOf { weights[it] ?: 0.0 } / weights.values.sum()).coerceAtMost(1.0), listOf(label(ci)), listOf(
                Detail("Intraday / 5-day / 20-day", "%s / %s / %s".format(fmt(ci), fmt(cw), fmt(cm))),
                Detail("Regime (1d · 5d)", "${label(ci)} · ${label(cw)}"),
                Detail("Drivers", top.joinToString { "${it.key.label} %+.2f".format(it.value) }),
                Detail("US VIX", if (usVix.isNaN()) "–" else "%.1f".format(usVix)),
                Detail("5-day: S&P / DXY / US10Y / Brent", listOf(GlobalAsset.SP500, GlobalAsset.DXY, GlobalAsset.US10Y, GlobalAsset.BRENT)
                    .joinToString(" / ") { c5[it]?.takeIf { v -> !v.isNaN() }?.let { v -> "%+.1f%%".format(v) } ?: "–" }),
            )),
            ci, cw, cm, intra, stress, usVix, c5, c1, asOf,
        )
    }

    /**
     * Global factor for [h]. Before/around the open GIFT Nifty is the best read of overnight global sentiment for India,
     * so its signal is blended in with its own (decaying) confidence.
     */
    fun reading(r: Result, h: HorizonId, gift: EngineSignal?, ref: Long, reliability: Double, source: String): FactorReading {
        val age = if (r.asOf > 0) ((ref - r.asOf) / 1000.0).coerceAtLeast(0.0) else Double.NaN
        val parts = when (h.group) {
            HorizonGroup.H1 -> buildList {
                add(Part("Global (1d + live)", r.intraday, 0.7, ""))
                if (gift != null && gift.confidence > 0) add(Part("GIFT Nifty", gift.score, 0.6 * gift.confidence, gift.tags.firstOrNull() ?: ""))
            }
            HorizonGroup.H2 -> listOf(Part("5-day global trend", r.weekly, 0.6), Part("Latest session", r.intraday, 0.4))
            HorizonGroup.H3 -> listOf(
                Part("20-day global trend", r.monthly, 0.6), Part("5-day global trend", r.weekly, 0.25),
                Part("US VIX level", if (r.usVix.isNaN()) Double.NaN else -M.squash((r.usVix - 18) / 6, 1.0), 0.15, "%.1f".format(r.usVix)),
            )
        }
        val fresh = Fresh.of(age, Fresh.Cadence.GLOBAL, h).let { if (h.intraday) it else maxOf(it, 0.5) }
        return Composite.reading(Factor.GLOBAL, parts, fresh, reliability, r.asOf, age, source,
            summary = { res -> when { res.direction > 15 -> "risk-on"; res.direction < -15 -> "risk-off"; else -> "mixed" } +
                " (1d %s · 5d %s)".format(fmt(r.intraday), fmt(r.weekly)) },
            missingWhy = "global quotes unavailable")
    }

    private fun fmt(x: Double) = if (x.isNaN()) "–" else "%+.2f".format(x)
}

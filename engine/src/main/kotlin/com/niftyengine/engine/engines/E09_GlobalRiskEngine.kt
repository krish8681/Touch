package com.niftyengine.engine.engines

import com.niftyengine.engine.core.M
import com.niftyengine.engine.model.Detail
import com.niftyengine.engine.model.EngineSignal
import com.niftyengine.engine.model.GlobalAsset
import com.niftyengine.engine.model.MarketSnapshot
import kotlin.math.abs

/**
 * 09 — Global Risk Engine → one composite GLOBAL_RISK_SCORE (−1 strong risk-off … +1 strong risk-on).
 * Each asset's change is normalised by its typical daily move and signed by its risk polarity
 * (equities +, DXY/yields/US VIX −, gold mildly −). Crude/USDINR/India 10Y live in the macro engine
 * to avoid double counting.
 */
class GlobalRiskEngine(private val norm: DataNormalizer) {
    private val weights = mapOf(
        GlobalAsset.SP500 to 0.15, GlobalAsset.NASDAQ to 0.12, GlobalAsset.DOW to 0.04, GlobalAsset.DAX to 0.06,
        GlobalAsset.NIKKEI to 0.08, GlobalAsset.HANGSENG to 0.08, GlobalAsset.SHANGHAI to 0.04,
        GlobalAsset.US_VIX to 0.12, GlobalAsset.US10Y to 0.10, GlobalAsset.US2Y to 0.04,
        GlobalAsset.DXY to 0.11, GlobalAsset.GOLD to 0.06,
    )

    data class Result(val signal: EngineSignal, val perAsset: Map<GlobalAsset, Double>, val globalVolStress: Double)

    /**
     * @param typical per-asset typical daily move (%) from the relative-baseline layer (v5); assets without
     * history fall back to the static [GlobalAsset.typicalDailyMovePct].
     */
    fun analyze(s: MarketSnapshot, now: Long, sessionStart: Long, typical: Map<GlobalAsset, Double> = emptyMap()): Result {
        val per = LinkedHashMap<GlobalAsset, Double>()
        var num = 0.0; var den = 0.0
        for ((asset, w) in weights) {
            val d = s.global[asset] ?: continue
            if (d.prevClose <= 0) continue
            val move = typical[asset]?.takeIf { it > 0.01 } ?: asset.typicalDailyMovePct
            val f = norm.features(d, now, sessionStart, move)
            // Live intraday drift counts extra for markets trading now; otherwise the 1D (overnight) change.
            val intra = DataNormalizer.nz(f.c1h)
            val z = (f.c1d + 0.5 * intra) / move
            val c = asset.riskSign * M.squash(z, 1.2)
            per[asset] = c
            num += w * c; den += w
        }
        if (den < 0.2) return Result(EngineSignal.unavailable("Global risk", "global quotes unavailable"), per, 0.0)
        val score = M.clamp(num / den)
        val usVix = s.global[GlobalAsset.US_VIX]
        val stress = usVix?.let { M.clamp((it.last - 18) / 15, 0.0, 1.0) } ?: 0.0
        val label = when {
            score > 0.5 -> "STRONG_RISK_ON"; score > 0.15 -> "RISK_ON"
            score < -0.5 -> "STRONG_RISK_OFF"; score < -0.15 -> "RISK_OFF"; else -> "MIXED"
        }
        val top = per.entries.sortedByDescending { abs(it.value) }.take(4)
        return Result(
            EngineSignal("Global risk", score, (den / weights.values.sum()).coerceAtMost(1.0), listOf(label), listOf(
                Detail("Composite", "%+.2f (%s)".format(score, label)),
                Detail("Drivers", top.joinToString { "${it.key.label} %+.2f".format(it.value) }),
                Detail("US VIX", usVix?.let { "%.1f".format(it.last) } ?: "–"),
            )),
            per, stress,
        )
    }
}

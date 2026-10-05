package com.niftyengine.engine.engines

import com.niftyengine.engine.core.M
import com.niftyengine.engine.model.Detail
import com.niftyengine.engine.model.EngineSignal
import com.niftyengine.engine.model.MarketSnapshot

/**
 * 08 — India VIX Engine.
 * VIX mainly sets the *volatility regime* (feeds expected move, event detection, option IV factor).
 * Its directional score is deliberately small: falling VIX = mild support, spiking VIX = risk-off.
 */
enum class VixState { FALLING, STABLE, RISING, SPIKING }

class VIXEngine(private val norm: DataNormalizer) {
    data class Result(val signal: EngineSignal, val state: VixState, val level: Double, val percentile: Double)

    fun analyze(s: MarketSnapshot, now: Long, sessionStart: Long): Result {
        val v = s.vix ?: return Result(EngineSignal.unavailable("India VIX", "VIX unavailable"), VixState.STABLE, Double.NaN, Double.NaN)
        val f = norm.features(v, now, sessionStart, typicalDailyMovePct = 5.0)
        val c15 = DataNormalizer.nz(f.c15m); val c1h = DataNormalizer.nz(f.c1h); val c1d = f.c1d
        val st = when {
            c15 > 4 || c1d > 12 -> VixState.SPIKING
            c1h > 2 || c1d > 4 -> VixState.RISING
            c1h < -2 || c1d < -4 -> VixState.FALLING
            else -> VixState.STABLE
        }
        val pct = f.percentile
        val score = M.clamp(-(0.6 * M.squash(c1d, 6.0) + 0.4 * M.squash(c1h, 3.0)) * if (st == VixState.SPIKING) 1.3 else 1.0)
        val tags = buildList {
            add("VIX_${st.name}")
            if (!pct.isNaN() && pct > 80) add("VIX_HIGH_PERCENTILE")
            if (!pct.isNaN() && pct < 20) add("VIX_LOW_PERCENTILE")
        }
        return Result(
            EngineSignal("India VIX", score, 0.9, tags, listOf(
                Detail("Level", "%.2f".format(v.last)),
                Detail("Δ 5m / 15m / 1h", "%+.1f%% / %+.1f%% / %+.1f%%".format(DataNormalizer.nz(f.c5m), c15, c1h)),
                Detail("Δ 1D", "%+.1f%%".format(c1d)),
                Detail("1y percentile", if (pct.isNaN()) "–" else "%.0f".format(pct)),
                Detail("State", st.name),
            )),
            st, v.last, pct,
        )
    }
}

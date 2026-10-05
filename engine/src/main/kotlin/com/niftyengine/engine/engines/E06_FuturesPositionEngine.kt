package com.niftyengine.engine.engines

import com.niftyengine.engine.core.Composite
import com.niftyengine.engine.core.EngineState
import com.niftyengine.engine.core.Fresh
import com.niftyengine.engine.core.M
import com.niftyengine.engine.model.Detail
import com.niftyengine.engine.model.EngineSignal
import com.niftyengine.engine.model.Factor
import com.niftyengine.engine.model.FactorReading
import com.niftyengine.engine.model.HorizonId
import com.niftyengine.engine.model.MarketSnapshot
import kotlin.math.abs

/**
 * 06 — Futures Positioning Engine.
 *
 * | Price | OI | State          |
 * |  ↑    | ↑  | Long buildup   |
 * |  ↓    | ↑  | Short buildup  |
 * |  ↑    | ↓  | Short covering |
 * |  ↓    | ↓  | Long unwinding |
 *
 * Evaluated on two windows (day vs previous close, and the last ~30 minutes of engine history).
 * These are positioning facts, not guaranteed signals, so the score is moderate and magnitude-scaled.
 */
enum class FuturesState(val label: String, val bias: Double) {
    LONG_BUILDUP("Long buildup", 0.8), SHORT_COVERING("Short covering", 0.5),
    SHORT_BUILDUP("Short buildup", -0.8), LONG_UNWINDING("Long unwinding", -0.5), NEUTRAL("Neutral", 0.0);

    companion object {
        fun classify(priceChgPct: Double, oiChgPct: Double, priceEps: Double = 0.05, oiEps: Double = 0.3): FuturesState = when {
            abs(priceChgPct) < priceEps || abs(oiChgPct) < oiEps -> NEUTRAL
            priceChgPct > 0 && oiChgPct > 0 -> LONG_BUILDUP
            priceChgPct < 0 && oiChgPct > 0 -> SHORT_BUILDUP
            priceChgPct > 0 && oiChgPct < 0 -> SHORT_COVERING
            else -> LONG_UNWINDING
        }
    }
}

class FuturesPositionEngine(private val state: EngineState) {
    data class Result(
        val signal: EngineSignal, val dayState: FuturesState, val intradayState: FuturesState, val basis: Double,
        /** Components (−1..1): day positioning, last-30-minute positioning (NaN until history exists), basis change. */
        val dayScore: Double = Double.NaN, val intraScore: Double = Double.NaN, val basisScore: Double = Double.NaN,
        val asOf: Long = 0L,
    ) {
        /** Futures positioning reading for an intraday horizon (short horizons lean on the last 30 minutes). */
        fun reading(h: HorizonId, ref: Long, reliability: Double, source: String): FactorReading {
            if (dayScore.isNaN()) return FactorReading.missing(Factor.FUTURES, "futures quote unavailable")
            val (wi, wd) = when (h) { HorizonId.M30 -> 0.6 to 0.25; HorizonId.M60 -> 0.45 to 0.4; else -> 0.3 to 0.55 }
            val age = if (asOf > 0) ((ref - asOf) / 1000.0).coerceAtLeast(0.0) else Double.NaN
            return Composite.reading(Factor.FUTURES, listOf(
                Composite.Part("Last 30m", intraScore, wi, intradayState.label), Composite.Part("Day", dayScore, wd, dayState.label),
                Composite.Part("Basis change", basisScore, 0.15, "%+.1f pts basis".format(basis)),
            ), Fresh.of(age, Fresh.Cadence.LIVE, h), reliability, asOf, age, source,
                summary = { "day ${dayState.label.lowercase()} · 30m ${intradayState.label.lowercase()}" })
        }
    }

    fun analyze(s: MarketSnapshot, now: Long): Result {
        val f = s.futures ?: return Result(EngineSignal.unavailable("Futures", "futures quote unavailable"),
            FuturesState.NEUTRAL, FuturesState.NEUTRAL, Double.NaN)
        // Seed OI history from feed bars (e.g. Kite historical with OI) so 30-minute positioning is
        // available immediately instead of after 30 minutes of polling.
        val seedFrom = state.futures.firstOrNull()?.t ?: Long.MAX_VALUE
        val seed = f.intraday.filter { it.t < seedFrom && it.t < now && it.oi > 0 }
        if (seed.isNotEmpty()) {
            val basisNow = f.last - s.nifty.last // historical spot approximated assuming constant basis
            seed.asReversed().forEach { b -> state.futures.addFirst(EngineState.FutTick(b.t, b.price, b.oi, b.volume, b.price - basisNow)) }
        }
        state.futures.addLast(EngineState.FutTick(now, f.last, f.openInterest, f.volume, s.nifty.last))
        while (state.futures.size > 600) state.futures.removeFirst()

        val dayPx = M.pctChange(f.prevClose, f.last)
        val first = state.futures.first()
        val dayOi = if (!f.prevOpenInterest.isNaN() && f.prevOpenInterest > 0) M.pctChange(f.prevOpenInterest, f.openInterest)
        else M.pctChange(first.oi, f.openInterest)
        val dayState = FuturesState.classify(dayPx, dayOi)

        val ref = state.futures.lastOrNull { it.t <= now - 30 * 60_000L } ?: first
        val intraPx = M.pctChange(ref.price, f.last)
        val intraOi = M.pctChange(ref.oi, f.openInterest)
        val intraState = FuturesState.classify(intraPx, intraOi, 0.03, 0.15)

        val basis = f.last - s.nifty.last
        val basisChg = basis - (first.price - first.spot)
        val premiumPct = basis / s.nifty.last * 100

        fun stateScore(st: FuturesState, oi: Double) = st.bias * (0.5 + 0.5 * M.squash(abs(DataNormalizer.nz(oi)), 2.0))
        val dayScore = stateScore(dayState, dayOi)
        val intraScore = stateScore(intraState, intraOi * 3)
        val basisScore = M.squash(basisChg / s.nifty.last * 100, 0.05) // widening premium = longs paying up
        val hasIntra = state.futures.size >= 3 && ref !== state.futures.last()
        val score = M.clamp(if (hasIntra) 0.45 * dayScore + 0.40 * intraScore + 0.15 * basisScore else 0.85 * dayScore + 0.15 * basisScore)

        val tags = buildList {
            add("DAY_${dayState.name}")
            if (hasIntra) add("30M_${intraState.name}")
            if (dayState.bias * intraState.bias < 0) add("POSITIONING_SHIFT")
        }
        return Result(
            EngineSignal("Futures", score, if (f.openInterest > 0) 0.9 else 0.4, tags, listOf(
                Detail("Day", "${dayState.label} (px %+.2f%%, OI %+.2f%%)".format(dayPx, DataNormalizer.nz(dayOi))),
                Detail("Last 30m", if (hasIntra) "${intraState.label} (px %+.2f%%, OI %+.2f%%)".format(intraPx, DataNormalizer.nz(intraOi)) else "building history"),
                Detail("Basis", "%+.1f pts (%.2f%%), Δ %+.1f".format(basis, premiumPct, basisChg)),
                Detail("OI", "%,.0f".format(f.openInterest)),
            )),
            dayState, intraState, basis, dayScore, if (hasIntra) intraScore else Double.NaN, basisScore, f.asOf,
        )
    }
}

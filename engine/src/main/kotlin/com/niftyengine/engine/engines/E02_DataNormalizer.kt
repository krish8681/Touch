package com.niftyengine.engine.engines

import com.niftyengine.engine.core.EngineState
import com.niftyengine.engine.core.M
import com.niftyengine.engine.model.Candle
import com.niftyengine.engine.model.InstrumentData
import kotlin.math.abs

/**
 * 02 — Data Normalizer / Feature engine.
 *
 * Raw levels are never fed into prediction. Every instrument is converted to *changes*:
 * multi-horizon returns, acceleration, percentile vs. its 1y history and a volatility-normalised z-score.
 */
data class ChangeFeatures(
    val last: Double,
    val c1m: Double,
    val c5m: Double,
    val c15m: Double,
    val c30m: Double,
    val c1h: Double,
    val c1d: Double,
    /** (last 5m change) − (previous 5m change), in % points. */
    val acceleration: Double,
    /** Percentile (0..100) of the current level within the daily history. */
    val percentile: Double,
    /** 1D change divided by typical daily move. */
    val zDay: Double,
    val bars: Int,
)

class DataNormalizer(private val state: EngineState) {

    /**
     * Intraday price path for an instrument: provided candles merged with the engine's own ticks
     * (ticks fill the gap after the last candle and replace candles entirely when none are supplied).
     */
    fun intradaySeries(inst: InstrumentData, sessionStart: Long): List<Candle> {
        val base = inst.intraday.filter { it.t >= sessionStart }
        val lastT = base.lastOrNull()?.t ?: Long.MIN_VALUE
        val extra = state.history(inst.symbol)
            .filter { it.t > lastT && it.t >= sessionStart }
            .map { Candle(it.t, it.price, it.price, it.price, it.price, it.volume) }
        return base + extra
    }

    /** 5-minute bars resampled from any intraday series (needed for EMA/RSI/ADX on uniform bars). */
    fun resample(series: List<Candle>, minutes: Int = 5): List<Candle> {
        if (series.isEmpty()) return emptyList()
        val ms = minutes * 60_000L
        return series.groupBy { it.t / ms }.toSortedMap().map { (k, g) ->
            Candle(k * ms, g.first().o, g.maxOf { it.h }, g.minOf { it.l }, g.last().c, g.sumOf { it.v })
        }
    }

    fun priceAt(series: List<Candle>, t: Long): Double =
        series.lastOrNull { it.t <= t }?.c ?: series.firstOrNull()?.o ?: Double.NaN

    fun features(inst: InstrumentData, now: Long, sessionStart: Long, typicalDailyMovePct: Double = 1.0): ChangeFeatures {
        val s = intradaySeries(inst, sessionStart)
        val last = inst.last
        fun chg(minutes: Int): Double {
            if (s.isEmpty()) return Double.NaN
            val p = priceAt(s, now - minutes * 60_000L)
            return M.pctChange(p, last)
        }
        val c5 = chg(5)
        val prev5 = if (s.isEmpty()) Double.NaN else M.pctChange(priceAt(s, now - 10 * 60_000L), priceAt(s, now - 5 * 60_000L))
        val c1d = inst.changePct
        val closes = inst.daily.map { it.c }.takeLast(250)
        return ChangeFeatures(
            last = last,
            c1m = chg(1), c5m = c5, c15m = chg(15), c30m = chg(30), c1h = chg(60),
            c1d = c1d,
            acceleration = if (c5.isNaN() || prev5.isNaN()) Double.NaN else c5 - prev5,
            percentile = M.percentileRank(closes, last),
            zDay = if (typicalDailyMovePct > 0) c1d / typicalDailyMovePct else 0.0,
            bars = s.size,
        )
    }

    /** Best available "recent move" in %: prefers 30m, then 15m, then 1D. */
    fun recentMove(f: ChangeFeatures): Double = listOf(f.c30m, f.c15m, f.c1d).firstOrNull { !it.isNaN() } ?: 0.0

    companion object {
        /** % change of the live price vs the close [sessions] completed sessions ago (NaN without enough daily history). */
        fun sessionsChange(d: InstrumentData?, sessions: Int): Double {
            if (d == null || d.last <= 0) return Double.NaN
            val dl = d.daily
            if (dl.size < sessions) return Double.NaN
            return M.pctChange(dl[dl.size - sessions].c, d.last)
        }

        fun nz(x: Double, d: Double = 0.0) = if (x.isNaN()) d else x
        fun sign(x: Double, eps: Double = 1e-9) = if (abs(x) < eps) 0 else if (x > 0) 1 else -1
    }
}

package com.niftyengine.engine.engines

import com.niftyengine.engine.core.M
import com.niftyengine.engine.model.Candle
import com.niftyengine.engine.model.Detail
import com.niftyengine.engine.model.EngineSignal
import com.niftyengine.engine.model.MarketSnapshot
import kotlin.math.abs

/**
 * NIFTY Market Pressure / Structure Engine (spec §4).
 * Price pressure (1m…1h returns), trend (VWAP, EMA20/50, opening range, previous-day levels) and
 * momentum (ROC, ATR, ADX, RSI). Technical indicators are *confirmation*, not primary drivers.
 */
class MarketStructureEngine(private val norm: DataNormalizer) {
    data class Result(
        val signal: EngineSignal,
        val series: List<Candle>,
        val bars5m: List<Candle>,
        val pressure: Double,
        val trend: Double,
        val vwap: Double,
        val adx: Double,
        val rsi: Double,
        val atr5m: Double,
        val orHigh: Double,
        val orLow: Double,
        val c15m: Double,
        val c1h: Double,
        val realizedVolAnnual: Double,
        val histVolAnnual: Double,
    )

    fun analyze(s: MarketSnapshot, now: Long, sessionStart: Long): Result {
        val n = s.nifty
        val series = norm.intradaySeries(n, sessionStart)
        val bars = norm.resample(series, 5)
        val f = norm.features(n, now, sessionStart, 1.0)
        val px = n.last

        val pressureParts = listOf(f.c5m to 0.15, f.c15m to 0.25, f.c30m to 0.35, f.c1h to 0.50)
            .zip(listOf(0.15, 0.25, 0.30, 0.30))
            .filter { !it.first.first.isNaN() }
        val pressure = if (pressureParts.isEmpty()) M.squash(f.c1d, 0.8)
        else pressureParts.sumOf { (p, w) -> w * M.squash(p.first, p.second) } / pressureParts.sumOf { it.second }

        // VWAP (TWAP proxy if the index feed carries no volume).
        val hasVol = series.sumOf { it.v } > 0
        val vwap = if (series.isEmpty()) Double.NaN else if (hasVol)
            series.sumOf { (it.h + it.l + it.c) / 3 * it.v } / series.sumOf { it.v }
        else series.map { (it.h + it.l + it.c) / 3 }.average()
        val closes = bars.map { it.c }
        val ema20 = M.ema(closes, 20).lastOrNull() ?: Double.NaN
        val ema50 = M.ema(closes, 50).lastOrNull() ?: Double.NaN
        val orBars = series.filter { it.t < sessionStart + 15 * 60_000L }
        val orHigh = orBars.maxOfOrNull { it.h } ?: Double.NaN
        val orLow = orBars.minOfOrNull { it.l } ?: Double.NaN
        val orComplete = now >= sessionStart + 15 * 60_000L && orBars.isNotEmpty()
        val prevDay = n.daily.lastOrNull()

        val trendParts = mutableListOf<Pair<Double, Double>>()
        if (!vwap.isNaN()) trendParts += 0.35 to M.squash((px - vwap) / px * 100, 0.12)
        if (bars.size >= 20) trendParts += 0.20 to M.squash((px - ema20) / px * 100, 0.12)
        if (bars.size >= 30) trendParts += 0.20 to (if (ema20 > ema50) 1.0 else -1.0)
        if (orComplete) trendParts += 0.25 to when { px > orHigh -> 1.0; px < orLow -> -1.0; else -> 0.0 }
        val trend = if (trendParts.isEmpty()) 0.0 else trendParts.sumOf { it.first * it.second } / trendParts.sumOf { it.first }

        var structure = M.squash(f.c1d, 0.5)
        if (prevDay != null) {
            if (px > prevDay.h) structure = M.clamp(structure + 0.4)
            if (px < prevDay.l) structure = M.clamp(structure - 0.4)
        }

        val rsi = M.rsi(closes, 14)
        val (adx, pdi, mdi) = M.adx(bars, 14)
        val atr = M.atr(bars, 14)
        val roc = if (closes.size > 12) M.pctChange(closes[closes.size - 13], closes.last()) else Double.NaN

        var score = 0.40 * pressure + 0.40 * trend + 0.20 * structure
        // Technical confirmation: disagreeing RSI/DI trims conviction, strong ADX extends it.
        if (!rsi.isNaN() && (rsi - 50) * score < 0) score *= 0.85
        if (!pdi.isNaN() && (pdi - mdi) * score < 0) score *= 0.9
        if (!adx.isNaN()) score *= M.clamp(adx / 25.0, 0.6, 1.2)
        score = M.clamp(score)

        val rets = M.logReturns(closes)
        val realized = if (rets.size >= 6) M.std(rets) * Math.sqrt(75.0 * 252) else Double.NaN // 75 five-minute bars/day
        val dailyRets = M.logReturns(n.daily.map { it.c }.takeLast(21))
        val hist = if (dailyRets.size >= 10) M.std(dailyRets) * Math.sqrt(252.0) else Double.NaN

        val tags = buildList {
            if (!vwap.isNaN()) add(if (px > vwap) "ABOVE_VWAP" else "BELOW_VWAP")
            if (orComplete && px > orHigh) add("OR_BREAKOUT") else if (orComplete && px < orLow) add("OR_BREAKDOWN")
            if (!adx.isNaN() && adx > 25) add("TRENDING") else if (!adx.isNaN() && adx < 18) add("NON_TRENDING")
            if (prevDay != null && px > prevDay.h) add("ABOVE_PDH")
            if (prevDay != null && px < prevDay.l) add("BELOW_PDL")
            if (!f.acceleration.isNaN() && abs(f.acceleration) > 0.1) add(if (f.acceleration > 0) "ACCELERATING_UP" else "ACCELERATING_DOWN")
        }
        fun p(x: Double) = if (x.isNaN()) "–" else "%+.2f%%".format(x)
        return Result(
            EngineSignal("Market structure", score, if (series.size >= 6) 0.95 else 0.6, tags, listOf(
                Detail("Return 5m/15m/30m/1h", "${p(f.c5m)} ${p(f.c15m)} ${p(f.c30m)} ${p(f.c1h)}"),
                Detail(if (hasVol) "VWAP" else "VWAP (TWAP proxy)", if (vwap.isNaN()) "–" else "%.1f".format(vwap)),
                Detail("EMA20 / EMA50 (5m)", "%.1f / %.1f".format(ema20, ema50)),
                Detail("Opening range", if (orComplete) "%.1f – %.1f".format(orLow, orHigh) else "forming"),
                Detail("Prev day H/L/C", prevDay?.let { "%.1f / %.1f / %.1f".format(it.h, it.l, n.prevClose) } ?: "–"),
                Detail("RSI / ADX / ATR(5m)", "%.1f / %.1f / %.1f".format(rsi, adx, atr)),
                Detail("ROC(1h)", p(roc)),
            )),
            series, bars, pressure, trend, vwap, adx, rsi, atr, orHigh, orLow,
            DataNormalizer.nz(f.c15m), DataNormalizer.nz(f.c1h), realized, hist,
        )
    }
}

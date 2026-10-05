package com.niftyengine.engine.engines

import com.niftyengine.engine.core.Composite
import com.niftyengine.engine.core.Composite.Part
import com.niftyengine.engine.core.Fresh
import com.niftyengine.engine.core.M
import com.niftyengine.engine.model.Candle
import com.niftyengine.engine.model.Detail
import com.niftyengine.engine.model.EngineSignal
import com.niftyengine.engine.model.Factor
import com.niftyengine.engine.model.FactorReading
import com.niftyengine.engine.model.HorizonId
import com.niftyengine.engine.model.MarketSnapshot
import kotlin.math.sign

/**
 * NIFTY price / trend structure (H1 factor "Price structure", §3–§6).
 *
 * Structure only — returns over several windows, position vs VWAP, the opening range and the previous day's range,
 * swing structure (higher highs / lower lows on 15-minute bars) and trend persistence. Oscillators such as RSI, MACD,
 * Bollinger bands or stochastics are deliberately not used (§29).
 *
 * Each intraday horizon weighs the same evidence differently: the 30-minute view leans on the last 5–15 minutes of
 * pressure, the day-close view on the day's trend and where price sits in the session's structure.
 */
class PriceStructureEngine(private val norm: DataNormalizer) {
    data class Result(
        val signal: EngineSignal,
        val readings: Map<HorizonId, FactorReading>,
        val series: List<Candle>,
        val bars5m: List<Candle>,
        val vwap: Double,
        val orHigh: Double,
        val orLow: Double,
        val dayHigh: Double,
        val dayLow: Double,
        val c15m: Double,
        val c1h: Double,
        val realizedVolAnnual: Double,
        val histVolAnnual: Double,
        /** Today's high–low range and the 20-day average daily range, % of price. */
        val dayRangePct: Double,
        val typicalRangePct: Double,
        /** Last 5 sessions' high–low range vs its 60-day typical value, % of price. */
        val range5dPct: Double,
        val typicalRange5dPct: Double,
    )

    fun analyze(s: MarketSnapshot, now: Long, sessionStart: Long, ref: Long, reliability: Double): Result {
        val n = s.nifty
        val series = norm.intradaySeries(n, sessionStart)
        val bars = norm.resample(series, 5)
        val bars15 = norm.resample(series, 15)
        val f = norm.features(n, now, sessionStart, 1.0)
        val px = n.last

        val hasVol = series.sumOf { it.v } > 0
        val vwap = if (series.isEmpty()) Double.NaN else if (hasVol)
            series.sumOf { (it.h + it.l + it.c) / 3 * it.v } / series.sumOf { it.v }
        else series.map { (it.h + it.l + it.c) / 3 }.average()
        val orBars = series.filter { it.t < sessionStart + 15 * 60_000L }
        val orHigh = orBars.maxOfOrNull { it.h } ?: Double.NaN
        val orLow = orBars.minOfOrNull { it.l } ?: Double.NaN
        val orComplete = now >= sessionStart + 15 * 60_000L && orBars.isNotEmpty()
        val prevDay = n.daily.lastOrNull()
        val dayHigh = series.maxOfOrNull { it.h } ?: n.high
        val dayLow = series.minOfOrNull { it.l } ?: n.low

        fun pr(x: Double, scale: Double) = if (x.isNaN()) Double.NaN else M.squash(x, scale)
        val p5 = pr(f.c5m, 0.15); val p15 = pr(f.c15m, 0.25); val p30 = pr(f.c30m, 0.35); val p60 = pr(f.c1h, 0.5)
        val dayMove = pr(f.c1d, 0.8)
        val vwapPos = if (vwap.isNaN()) Double.NaN else M.squash((px - vwap) / px * 100, 0.15)
        val orPos = if (!orComplete) Double.NaN else when { px > orHigh -> 1.0; px < orLow -> -1.0; else -> 0.0 }
        val pdPos = if (prevDay == null || prevDay.h <= prevDay.l) Double.NaN else when {
            px > prevDay.h -> 1.0
            px < prevDay.l -> -1.0
            else -> M.clamp((px - (prevDay.h + prevDay.l) / 2) / ((prevDay.h - prevDay.l) / 2)) * 0.5
        }
        // Swing structure on completed 15-minute bars: higher highs + higher lows = up-structure.
        val swing = if (bars15.size < 4) Double.NaN else {
            val b = bars15.dropLast(1).takeLast(3)
            if (b.size < 3) Double.NaN else (sign(b[2].h - b[1].h) + sign(b[2].l - b[1].l) + sign(b[1].h - b[0].h) + sign(b[1].l - b[0].l)) / 4.0
        }
        // Trend persistence: share of the last hour's 5-minute closes above VWAP.
        val persistence = if (vwap.isNaN() || bars.size < 6) Double.NaN else
            bars.takeLast(12).count { it.c > vwap }.toDouble() / bars.takeLast(12).size * 2 - 1

        fun part(name: String, v: Double, w: Double, txt: String = "") = Part(name, v, w, txt)
        fun pct(x: Double) = if (x.isNaN()) "–" else "%+.2f%%".format(x)
        val ageSec = if (n.asOf > 0) ((ref - n.asOf) / 1000.0).coerceAtLeast(0.0) else Double.NaN
        val rel = reliability * if (series.size >= 6) 1.0 else 0.75
        val readings = mapOf(
            HorizonId.M30 to listOf(part("5m pressure", p5, 0.20, pct(f.c5m)), part("15m pressure", p15, 0.30, pct(f.c15m)),
                part("30m pressure", p30, 0.20, pct(f.c30m)), part("vs VWAP", vwapPos, 0.15), part("Opening range", orPos, 0.05), part("Swing structure", swing, 0.10)),
            HorizonId.M60 to listOf(part("15m pressure", p15, 0.20, pct(f.c15m)), part("30m pressure", p30, 0.25, pct(f.c30m)),
                part("1h pressure", p60, 0.15, pct(f.c1h)), part("vs VWAP", vwapPos, 0.15), part("Opening range", orPos, 0.10), part("Swing structure", swing, 0.15)),
            HorizonId.M180 to listOf(part("30m pressure", p30, 0.15, pct(f.c30m)), part("1h pressure", p60, 0.20, pct(f.c1h)),
                part("vs VWAP", vwapPos, 0.20), part("Opening range", orPos, 0.15), part("Prev-day range", pdPos, 0.15), part("Swing structure", swing, 0.15)),
            HorizonId.CLOSE to listOf(part("Day trend", dayMove, 0.20, pct(f.c1d)), part("vs VWAP", vwapPos, 0.20),
                part("Opening range", orPos, 0.15), part("Prev-day range", pdPos, 0.15), part("Swing structure", swing, 0.15), part("Trend persistence", persistence, 0.15)),
        ).mapValues { (h, parts) ->
            Composite.reading(Factor.PRICE_STRUCTURE, parts, Fresh.of(ageSec, Fresh.Cadence.LIVE, h), rel, n.asOf, ageSec, s.source,
                summary = { r -> describe(r.direction) + " · VWAP ${if (vwap.isNaN()) "–" else if (px > vwap) "above" else "below"}" },
                missingWhy = "no NIFTY price path yet")
        }

        val closes = bars.map { it.c }
        val rets = M.logReturns(closes)
        val realized = if (rets.size >= 6) M.std(rets) * Math.sqrt(75.0 * 252) else Double.NaN
        val dailyRets = M.logReturns(n.daily.map { it.c }.takeLast(21))
        val hist = if (dailyRets.size >= 10) M.std(dailyRets) * Math.sqrt(252.0) else Double.NaN
        val ranges = n.daily.takeLast(20).filter { it.c > 0 }.map { (it.h - it.l) / it.c * 100 }
        val typicalRange = ranges.takeIf { it.size >= 5 }?.average()?.takeIf { it > 0.05 } ?: Double.NaN
        val dayRange = if (dayHigh.isNaN() || dayLow.isNaN() || px <= 0) Double.NaN else (dayHigh - dayLow) / px * 100
        val d5 = n.daily.takeLast(4)
        val range5 = if (d5.size < 4 || dayHigh.isNaN()) Double.NaN else
            ((maxOf(d5.maxOf { it.h }, dayHigh) - minOf(d5.minOf { it.l }, dayLow)) / px * 100).takeIf { it > 0.05 } ?: Double.NaN
        val typical5 = n.daily.takeLast(60).windowed(5).map { w -> (w.maxOf { it.h } - w.minOf { it.l }) / w.last().c * 100 }
            .takeIf { it.size >= 10 }?.average()?.takeIf { it > 0.1 } ?: Double.NaN

        val r60 = readings.getValue(HorizonId.M60)
        val tags = buildList {
            if (!vwap.isNaN()) add(if (px > vwap) "ABOVE_VWAP" else "BELOW_VWAP")
            if (orComplete && px > orHigh) add("OR_BREAKOUT") else if (orComplete && px < orLow) add("OR_BREAKDOWN")
            if (prevDay != null && px > prevDay.h) add("ABOVE_PDH")
            if (prevDay != null && px < prevDay.l) add("BELOW_PDL")
            if (!swing.isNaN() && swing >= 0.5) add("HIGHER_HIGHS") else if (!swing.isNaN() && swing <= -0.5) add("LOWER_LOWS")
            if (!dayRange.isNaN() && !typicalRange.isNaN() && dayRange < 0.6 * typicalRange) add("NARROW_DAY_RANGE")
        }
        val sig = EngineSignal("Price structure", r60.direction / 100, if (series.size >= 6) 0.95 else 0.6, tags, listOf(
            Detail("Return 5m/15m/30m/1h/day", "${pct(f.c5m)} ${pct(f.c15m)} ${pct(f.c30m)} ${pct(f.c1h)} ${pct(f.c1d)}"),
            Detail(if (hasVol) "VWAP" else "VWAP (TWAP proxy)", if (vwap.isNaN()) "–" else "%.1f".format(vwap)),
            Detail("Opening range", if (orComplete) "%.1f – %.1f".format(orLow, orHigh) else "forming"),
            Detail("Prev day H/L/C", prevDay?.let { "%.1f / %.1f / %.1f".format(it.h, it.l, n.prevClose) } ?: "–"),
            Detail("Swing structure (15m)", if (swing.isNaN()) "–" else "%+.2f".format(swing)),
            Detail("Day range / typical", "%.2f%% / %.2f%%".format(dayRange, typicalRange)),
            Detail("5-day range / typical", "%.2f%% / %.2f%%".format(range5, typical5)),
            Detail("Realised vol (5m) / 20d", "%.1f%% / %.1f%%".format(realized * 100, hist * 100)),
        ) + HorizonId.values().filter { it.intraday }.map { h -> readings.getValue(h).let { Detail("Reading ${h.short}", "%+.0f (strength %.2f)".format(it.direction, it.strength)) } })
        return Result(sig, readings, series, bars, vwap, orHigh, orLow, dayHigh, dayLow,
            DataNormalizer.nz(f.c15m), DataNormalizer.nz(f.c1h), realized, hist, dayRange, typicalRange, range5, typical5)
    }

    companion object {
        fun describe(d: Double) = when {
            d > 40 -> "strong up"; d > 12 -> "up"; d < -40 -> "strong down"; d < -12 -> "down"; else -> "flat"
        }
    }
}

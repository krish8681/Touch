package com.niftyengine.engine.engines

import com.niftyengine.engine.core.Composite
import com.niftyengine.engine.core.Composite.Part
import com.niftyengine.engine.core.Fresh
import com.niftyengine.engine.core.M
import com.niftyengine.engine.model.Detail
import com.niftyengine.engine.model.ExpectationChannel
import com.niftyengine.engine.model.ExpectationReport
import com.niftyengine.engine.model.Factor
import com.niftyengine.engine.model.FactorReading
import com.niftyengine.engine.model.FiiRegime
import com.niftyengine.engine.model.FiiReport
import com.niftyengine.engine.model.HorizonGroup
import com.niftyengine.engine.model.HorizonId
import com.niftyengine.engine.model.MarketSnapshot

/**
 * 10b — FII Engine (§16). Not just daily cash:
 *  • Cash: latest session, 5-session and 20-session net FPI flow (DII shown as the counterweight)
 *  • Futures: FII index-futures long vs short contracts, long share and its 1- and 5-day change
 *  • Options: FII index calls/puts long vs short → net bullish exposure and its change
 * → FII regime Strong bullish … Strong bearish.
 *
 * The same data is read three ways: H1 "FII activity" (latest session + day-over-day positioning change),
 * H2 "FII/FPI positioning" (5-day flow + futures positioning), H3 "FII/FPI allocation" (20-day flow trend + positioning level).
 */
object FiiEngine {
    data class Result(val report: FiiReport, val readings: Map<HorizonId, FactorReading>)

    fun analyze(s: MarketSnapshot, ref: Long, exp: ExpectationReport, reliability: Double): Result {
        val fl = s.flows
        val hist = fl?.history.orEmpty().sortedBy { it.date }
        val latest = fl?.fpiNetCr ?: Double.NaN
        val dii = fl?.diiNetCr ?: Double.NaN
        fun sumLast(n: Int, sel: (com.niftyengine.engine.model.FlowDay) -> Double): Double =
            if (hist.size >= (n * 0.6).toInt().coerceAtLeast(2)) hist.takeLast(n).sumOf(sel) * n / hist.takeLast(n).size.coerceAtLeast(1) else Double.NaN
        val cash5 = sumLast(5) { it.fpiNetCr }.takeIf { !it.isNaN() } ?: fl?.fpi5dCr ?: Double.NaN
        val dii5 = sumLast(5) { it.diiNetCr }.takeIf { !it.isNaN() } ?: fl?.dii5dCr ?: Double.NaN
        val cash20 = if (hist.size >= 12) sumLast(20) { it.fpiNetCr } else Double.NaN

        val days = s.fiiDerivatives?.days.orEmpty().sortedBy { it.date }
        val d0 = days.lastOrNull(); val d1 = days.getOrNull(days.size - 2); val d5 = days.getOrNull(days.size - 6) ?: days.firstOrNull()?.takeIf { days.size >= 3 }
        val longPct = d0?.futLongPct ?: Double.NaN
        val chg1 = if (d0 != null && d1 != null) d0.futLongPct - d1.futLongPct else Double.NaN
        val chg5 = if (d0 != null && d5 != null && d5 !== d0) d0.futLongPct - d5.futLongPct else Double.NaN
        val optNet = d0?.netOptionExposure ?: Double.NaN
        val optChg = if (d0 != null && d1 != null) d0.netOptionExposure - d1.netOptionExposure else Double.NaN
        val optScale = (d0?.totalOptionOi ?: 0.0).coerceAtLeast(1.0)

        fun sq(x: Double, scale: Double) = if (x.isNaN()) Double.NaN else M.squash(x / scale, 1.0)
        val pCash = sq(latest, 3000.0); val pCash5 = sq(cash5, 12_000.0); val pCash20 = sq(cash20, 30_000.0)
        // Positioning LEVELS are judged against FIIs' own recent window, not a fixed anchor: FII index-futures long share has
        // spent long stretches far below 50 %, so "8 % long" can be normal while a move from 8 % to 15 % is the signal.
        fun relLevel(sel: (com.niftyengine.engine.model.FiiDerivDay) -> Double, minSd: Double): Double {
            if (days.size < 5 || d0 == null) return Double.NaN
            val xs = days.dropLast(1).map(sel).filter { !it.isNaN() }
            if (xs.size < 4) return Double.NaN
            val sd = (M.std(xs).takeIf { !it.isNaN() } ?: 0.0).coerceAtLeast(minSd)
            return M.squash((sel(d0) - xs.average()) / sd / 1.5, 1.0)
        }
        val pFutLevel = relLevel({ it.futLongPct }, 0.015)
        val pFut1 = sq(chg1, 0.03); val pFut5 = sq(chg5, 0.06)
        val pOpt1 = sq(optChg, optScale * 0.03); val pOptLevel = relLevel({ it.netOptionExposure / it.totalOptionOi.coerceAtLeast(1.0) }, 0.02)
        // DIIs absorb FPI selling: the combined flow tempers a pure-FPI reading.
        val pDii = if (latest.isNaN() || dii.isNaN()) Double.NaN else M.squash((latest + 0.85 * dii) / 4000.0, 1.0)
        val news = exp.channels[ExpectationChannel.FLOWS]

        val cashAsOf = fl?.asOf ?: 0L
        val derivAsOf = s.fiiDerivatives?.asOf ?: 0L
        val asOf = maxOf(cashAsOf, derivAsOf)
        val ageSec = listOf(cashAsOf, derivAsOf).filter { it > 0 }.maxOrNull()?.let { Fresh.dailyAgeSec(it, ref) } ?: Double.NaN
        val rel = reliability * if (days.isEmpty()) 0.8 else 1.0
        fun txt(x: Double, f: String = "₹%,.0f cr") = if (x.isNaN()) "" else f.format(x)
        fun pct(x: Double) = if (x.isNaN()) "" else "%+.1f pp".format(x * 100)
        val parts = mapOf(
            HorizonGroup.H1 to listOf(Part("Cash (latest)", pCash, 0.35, txt(latest)), Part("Futures long% Δ1d", pFut1, 0.25, pct(chg1)),
                Part("Index options net Δ1d", pOpt1, 0.2, txt(optChg, "%+,.0f")), Part("Futures long% vs recent", pFutLevel, 0.1, txt(longPct * 100, "%.0f%%")),
                Part("FPI + DII", pDii, 0.1), Part("News (flows)", news?.get(HorizonGroup.H1) ?: Double.NaN, 0.1)),
            HorizonGroup.H2 to listOf(Part("Cash 5-day", pCash5, 0.3, txt(cash5)), Part("Futures long% vs recent", pFutLevel, 0.2, txt(longPct * 100, "%.0f%%")),
                Part("Futures long% Δ5d", pFut5, 0.2, pct(chg5)), Part("Cash (latest)", pCash, 0.1, txt(latest)),
                Part("Index options net vs recent", pOptLevel, 0.1, txt(optNet, "%+,.0f")), Part("FPI + DII", pDii, 0.1), Part("News (flows)", news?.get(HorizonGroup.H2) ?: Double.NaN, 0.1)),
            HorizonGroup.H3 to listOf(Part("Cash 20-day", pCash20, 0.45, txt(cash20)), Part("Futures long% vs recent", pFutLevel, 0.25, txt(longPct * 100, "%.0f%%")),
                Part("Cash 5-day", pCash5, 0.2, txt(cash5)), Part("Futures long% Δ5d", pFut5, 0.1, pct(chg5))),
        )
        val readings = HorizonId.values().associateWith { h ->
            Composite.reading(Factor.FII, parts.getValue(h.group), Fresh.of(ageSec, Fresh.Cadence.DAILY, h), rel, asOf, ageSec,
                if (days.isEmpty()) "NSE FII/DII cash" else "NSE cash + participant OI",
                summary = { r -> regimeOf(r.direction).label + if (fl?.date.isNullOrBlank()) "" else " · as of ${fl!!.date}" },
                missingWhy = "FII data unavailable")
        }
        val weekly = readings.getValue(HorizonId.WEEKLY)
        val regime = if (weekly.available) regimeOf(weekly.direction) else FiiRegime.NEUTRAL
        val tags = buildList {
            if (!latest.isNaN() && !dii.isNaN() && latest < -1000 && dii > -0.7 * latest) add("DII_ABSORBING_FPI_SELLING")
            if (!latest.isNaN() && latest > 1000 && !dii.isNaN() && dii > 0) add("JOINT_BUYING")
            if (!latest.isNaN() && latest < -1000 && !dii.isNaN() && dii < 0) add("JOINT_SELLING")
            if (!chg1.isNaN() && chg1 > 0.02) add("FII_ADDING_LONGS") else if (!chg1.isNaN() && chg1 < -0.02) add("FII_ADDING_SHORTS")
            if (!longPct.isNaN() && longPct < 0.25) add("FII_HEAVILY_SHORT_FUTURES") else if (!longPct.isNaN() && longPct > 0.6) add("FII_NET_LONG_FUTURES")
        }
        val report = FiiReport(
            available = weekly.available, regime = regime, score = if (weekly.available) weekly.direction else 0.0,
            cashLatestCr = latest, cash5dCr = cash5, cash20dCr = cash20, diiLatestCr = dii, dii5dCr = dii5,
            futLong = d0?.futIndexLong ?: Double.NaN, futShort = d0?.futIndexShort ?: Double.NaN, futLongPct = longPct,
            futLongPctChange1d = chg1, futLongPctChange5d = chg5, optionsNet = optNet, optionsNetChange1d = optChg,
            date = d0?.date ?: fl?.date ?: "", tags = tags,
            details = listOf(
                Detail("FPI cash latest / 5d / 20d", "%s / %s / %s".format(txt(latest).ifBlank { "–" }, txt(cash5).ifBlank { "–" }, txt(cash20).ifBlank { "–" })),
                Detail("DII cash latest / 5d", "%s / %s".format(txt(dii).ifBlank { "–" }, txt(dii5).ifBlank { "–" })),
                Detail("Index futures long / short", if (d0 == null) "–" else "%,.0f / %,.0f (long %.0f%%)".format(d0.futIndexLong, d0.futIndexShort, longPct * 100)),
                Detail("Long% change 1d / 5d", "%s / %s".format(pct(chg1).ifBlank { "–" }, pct(chg5).ifBlank { "–" })),
                Detail("Index calls long / short", d0?.let { "%,.0f / %,.0f".format(it.callLong, it.callShort) } ?: "–"),
                Detail("Index puts long / short", d0?.let { "%,.0f / %,.0f".format(it.putLong, it.putShort) } ?: "–"),
                Detail("Net option exposure (Δ1d)", if (optNet.isNaN()) "–" else "%+,.0f (%s)".format(optNet, txt(optChg, "%+,.0f").ifBlank { "–" })),
                Detail("History", "${hist.size} cash days · ${days.size} participant-OI days"),
            ),
        )
        return Result(report, readings)
    }

    fun regimeOf(score: Double) = when {
        score >= 50 -> FiiRegime.STRONG_BULLISH; score >= 15 -> FiiRegime.BULLISH; score > -15 -> FiiRegime.NEUTRAL
        score > -50 -> FiiRegime.BEARISH; else -> FiiRegime.STRONG_BEARISH
    }
}

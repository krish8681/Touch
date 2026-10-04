package com.niftyengine.engine.engines

import com.niftyengine.engine.core.Session
import com.niftyengine.engine.model.Candle
import com.niftyengine.engine.model.GlobalAsset
import com.niftyengine.engine.model.MarketSnapshot
import com.niftyengine.engine.model.NormalizedState
import com.niftyengine.engine.model.RelativeReading
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/** One metric's aggregate for one session day. */
@Serializable
data class DayAgg(val day: Long, val sum: Double, val n: Int, val last: Double) {
    val mean: Double get() = if (n > 0) sum / n else Double.NaN
}

/** Persisted rolling history behind the relative baselines (the app saves it across restarts and days). */
@Serializable
data class BaselineState(val metrics: Map<String, List<DayAgg>> = emptyMap())

/**
 * 02c — Relative baselines (v5 data-normalization layer).
 *
 * Raw values are not information on their own: "PCR = 1.12" means little, "PCR 1.12 vs a 20-day normal of 0.94 (+19 %)"
 * does. Every positioning/volatility/global input is expressed relative to its own normal:
 *
 *  • rolling 20-session normals for PCR, IV skew, ATM IV, futures excess premium and global daily moves
 *    (one aggregate per day, persisted by the app);
 *  • principled priors until enough history exists: India VIX for ATM IV, fair carry for the futures premium,
 *    the instrument's own daily candles for move sizes, the typical daily move as the last resort;
 *  • moves are reported as multiples of a normal move ("Nasdaq +1.0 % = 4.0× normal").
 */
class RelativeBaselines(private val window: Int = 20, private val keepDays: Int = 40) {
    private val metrics = HashMap<String, MutableList<DayAgg>>()
    private var sessionDay = -1L
    private var ivOpen = Double.NaN
    /** Today's ATM IV readings (for 15/30-minute changes — a shock is sudden, not a slow drift). */
    private val ivSeries = ArrayDeque<Pair<Long, Double>>()

    fun exportState() = BaselineState(metrics.mapValues { it.value.toList() })

    fun importState(s: BaselineState) {
        metrics.clear()
        s.metrics.forEach { (k, v) -> metrics[k] = v.sortedBy { it.day }.takeLast(keepDays).toMutableList() }
    }

    fun observe(metric: String, day: Long, value: Double) {
        if (value.isNaN() || value.isInfinite()) return
        val l = metrics.getOrPut(metric) { mutableListOf() }
        val last = l.lastOrNull()
        when {
            last != null && last.day == day -> l[l.size - 1] = DayAgg(day, last.sum + value, last.n + 1, value)
            last != null && last.day > day -> return // out-of-order (replay of an older day) — ignore
            else -> { l += DayAgg(day, value, 1, value); while (l.size > keepDays) l.removeAt(0) }
        }
    }

    /** Mean of the daily means over the last [window] completed days before [day]; (NaN, 0) without history. */
    fun normalOf(metric: String, day: Long, useLast: Boolean = false, absolute: Boolean = false): Pair<Double, Int> {
        val prior = metrics[metric].orEmpty().filter { it.day < day }.takeLast(window)
        if (prior.isEmpty()) return Double.NaN to 0
        val xs = prior.map { if (useLast) it.last else it.mean }.map { if (absolute) abs(it) else it }.filter { !it.isNaN() }
        return (if (xs.isEmpty()) Double.NaN else xs.average()) to xs.size
    }

    fun daysOfHistory(day: Long): Int = metrics.values.maxOfOrNull { l -> l.count { it.day < day } } ?: 0

    data class Result(
        val state: NormalizedState,
        /** "Typical" daily move per global asset (std-equivalent %), replacing the static table when history exists. */
        val globalTypical: Map<GlobalAsset, Double>,
        /** Mean absolute daily move per global asset (%), for "× normal" multiples. */
        val globalNormalAbs: Map<GlobalAsset, Double>,
        val pcr: Double, val pcrNormal: Double, val pcrRel: Double,
        val skew: Double, val skewNormal: Double,
        val atmIv: Double, val ivNormal: Double, val ivRel: Double,
        /** ATM IV change since the session's first reading, %. */
        val ivChangePct: Double,
        /** ATM IV change over the last 15 / 30 minutes, % (NaN until enough history). */
        val ivChange15Pct: Double,
        val ivChange30Pct: Double,
        /** (futures basis − fair carry) as % of spot, and its normal. */
        val basisExcessPct: Double, val basisExcessNormal: Double,
        /** Sessions behind the excess-premium normal (0 = fair-value prior only). */
        val basisNormalDays: Int,
        val fairCarryPts: Double,
        /** realised 5-minute vol ÷ normal vol (20-day historical or VIX). */
        val realizedVolRel: Double,
        /** Annual vol used as "normal" for intraday move sizes. */
        val normalAnnualVol: Double,
        /** |NIFTY 15m move| ÷ normal 15m move. */
        val niftyMove15Multiple: Double,
        val vixMove15Multiple: Double,
        /** Normal |opening gap| %, from daily candles. */
        val normalGapPct: Double,
    )

    fun analyze(
        s: MarketSnapshot, now: Long, sessionStart: Long, norm: DataNormalizer,
        st: MarketStructureEngine.Result, op: OptionsPositionEngine.Result, fu: FuturesPositionEngine.Result, vx: VIXEngine.Result,
    ): Result {
        val day = Session.zdt(now).toLocalDate().toEpochDay()
        if (day != sessionDay) { sessionDay = day; ivOpen = Double.NaN; ivSeries.clear() }
        val open = Session.isOpen(now)
        val readings = ArrayList<RelativeReading>()
        val spot = s.nifty.last
        fun basisLabel(n: Int, prior: String) = if (n >= 3) "$n-day normal" else prior

        // ---- options: PCR and skew vs their own normals
        val (pcrN, pcrDays) = normalOf("PCR", day)
        val pcrNormal = if (pcrDays >= 3) pcrN else 1.0
        val pcrRel = if (op.pcr.isNaN()) Double.NaN else op.pcr / pcrNormal - 1
        if (!op.pcr.isNaN()) readings += RelativeReading("PCR", op.pcr, pcrNormal, pcrRel, basis = basisLabel(pcrDays, "prior 1.00"),
            display = "%.2f vs %.2f normal → %+.0f%%".format(op.pcr, pcrNormal, pcrRel * 100))
        val (skN, skDays) = normalOf("SKEW", day)
        val skewNormal = if (skDays >= 3) skN else 2.0
        if (!op.skew.isNaN()) readings += RelativeReading("IV skew", op.skew, skewNormal, (op.skew - skewNormal) / 3.0,
            basis = basisLabel(skDays, "prior +2 vol pts"), display = "%+.1f vs %+.1f normal vol pts".format(op.skew, skewNormal))

        // ---- ATM IV vs normal (20-day mean of ATM IV, else India VIX as a 30-day IV proxy)
        val (ivN, ivDays) = normalOf("ATM_IV", day)
        val vixLvl = vx.level
        val ivNormal = when { ivDays >= 3 -> ivN; !vixLvl.isNaN() && vixLvl > 0 -> vixLvl; else -> Double.NaN }
        val ivRel = if (op.atmIv.isNaN() || ivNormal.isNaN() || ivNormal <= 0) Double.NaN else op.atmIv / ivNormal - 1
        if (open && !op.atmIv.isNaN() && ivOpen.isNaN() && now >= sessionStart + 5 * 60_000L) ivOpen = op.atmIv
        val ivChange = if (ivOpen.isNaN() || op.atmIv.isNaN() || ivOpen <= 0) Double.NaN else (op.atmIv / ivOpen - 1) * 100
        if (open && !op.atmIv.isNaN() && op.atmIv > 0 && (ivSeries.isEmpty() || ivSeries.last().first < now)) {
            ivSeries.addLast(now to op.atmIv)
            while (ivSeries.size > 400) ivSeries.removeFirst()
        }
        fun ivChangeOver(min: Int): Double {
            if (op.atmIv.isNaN() || op.atmIv <= 0) return Double.NaN
            val ref = ivSeries.lastOrNull { it.first <= now - min * 60_000L }
                ?: ivSeries.firstOrNull()?.takeIf { now - it.first >= min * 60_000L * 2 / 3 } ?: return Double.NaN
            return (op.atmIv / ref.second - 1) * 100
        }
        val iv15 = ivChangeOver(15)
        val iv30 = ivChangeOver(30)
        if (!ivRel.isNaN()) readings += RelativeReading("ATM IV", op.atmIv, ivNormal, ivRel, basis = basisLabel(ivDays, "India VIX proxy"),
            display = "%.1f%% vs %.1f%% normal → %+.0f%%%s".format(op.atmIv, ivNormal, ivRel * 100,
                if (ivChange.isNaN()) "" else " · %+.1f%% since open".format(ivChange)))

        // ---- India VIX vs its 20-day mean (from daily candles)
        val vixHist = s.vix?.daily?.takeLast(window)?.map { it.c }?.filter { it > 0 }.orEmpty()
        if (!vixLvl.isNaN() && vixHist.size >= 5) {
            val n = vixHist.average()
            readings += RelativeReading("India VIX", vixLvl, n, vixLvl / n - 1, basis = "${vixHist.size}-day mean",
                display = "%.2f vs %.2f normal → %+.0f%%".format(vixLvl, n, (vixLvl / n - 1) * 100))
        }

        // ---- futures premium vs fair carry (and vs its own normal excess)
        val f = s.futures
        val expiry = f?.expiry?.let(Session::parseDate)
        val tYears = expiry?.let { ((Session.closeOf(it) - now).coerceAtLeast(0) / 86_400_000.0) / 365.0 } ?: Double.NaN
        val fair = if (tYears.isNaN() || spot <= 0) Double.NaN else spot * (exp(CARRY_RATE * tYears) - 1)
        val basis = fu.basis
        val excessPct = if (basis.isNaN() || fair.isNaN() || spot <= 0) Double.NaN else (basis - fair) / spot * 100
        val (exN, exDays) = normalOf("BASIS_EXCESS_PCT", day)
        val excessNormal = if (exDays >= 3) exN else 0.0
        if (!excessPct.isNaN()) readings += RelativeReading("Futures premium", basis, fair, excessPct - excessNormal,
            basis = if (exDays >= 3) "fair carry + $exDays-day normal" else "fair carry",
            display = "%+.1f pts vs fair %+.1f → excess %+.2f%% (normal %+.2f%%)".format(basis, fair, excessPct, excessNormal))

        // ---- volatility and move sizes in units of normal
        val normalVol = when {
            !st.histVolAnnual.isNaN() && st.histVolAnnual > 0.03 -> st.histVolAnnual
            !vixLvl.isNaN() && vixLvl > 0 -> vixLvl / 100
            else -> 0.13
        }
        val rvRel = if (st.realizedVolAnnual.isNaN()) Double.NaN else st.realizedVolAnnual / normalVol
        if (!rvRel.isNaN()) readings += RelativeReading("Realised vol (5m)", st.realizedVolAnnual * 100, normalVol * 100, rvRel - 1,
            multiple = rvRel, basis = if (!st.histVolAnnual.isNaN()) "20-day historical" else "India VIX",
            display = "%.1f%% vs %.1f%% normal = %.1f×".format(st.realizedVolAnnual * 100, normalVol * 100, rvRel))
        val sigma15Pct = normalVol * sqrt(15.0 / (Session.SESSION_MINUTES * Session.TRADING_DAYS)) * 100
        val c15 = st.c15m
        val move15 = if (st.series.size >= 10 && sigma15Pct > 0) abs(c15) / sigma15Pct else Double.NaN
        if (!move15.isNaN()) readings += RelativeReading("NIFTY 15m move", c15, sigma15Pct, c15 / sigma15Pct, multiple = move15,
            basis = "normal 15-min σ", display = "%+.2f%% = %.1f× normal (σ %.2f%%)".format(c15, move15, sigma15Pct))
        val vixF = s.vix?.let { norm.features(it, now, sessionStart, 5.0) }
        val vix15 = vixF?.c15m ?: Double.NaN
        val vixMove15 = if (vix15.isNaN()) Double.NaN else abs(vix15) / VIX_15M_NORMAL_PCT

        val normalGap = normalGapPct(s.nifty.daily)

        // ---- global markets: daily move vs the asset's normal daily move
        val typical = HashMap<GlobalAsset, Double>()
        val normalAbs = HashMap<GlobalAsset, Double>()
        val globalReadings = ArrayList<RelativeReading>()
        for ((asset, d) in s.global) {
            if (d.prevClose <= 0) continue
            val fromCandles = meanAbsDailyMove(d.daily)
            val (stored, sDays) = normalOf("G_${asset.name}_ABS", day, useLast = true, absolute = true)
            val (nAbs, label) = when {
                !fromCandles.isNaN() -> fromCandles to "${d.daily.size.coerceAtMost(window)}-day mean |move|"
                sDays >= 5 -> stored to "$sDays-day mean |move|"
                else -> asset.typicalDailyMovePct * 0.8 to "static prior"
            }
            normalAbs[asset] = nAbs
            if (label != "static prior") typical[asset] = nAbs * 1.25
            val chg = d.changePct
            val mult = abs(chg) / nAbs.coerceAtLeast(1e-6)
            globalReadings += RelativeReading(asset.label, chg, nAbs, chg / nAbs.coerceAtLeast(1e-6), mult, label,
                "%+.2f%% = %.1f× normal (±%.2f%%)".format(chg, mult, nAbs))
            if (open) observe("G_${asset.name}_ABS", day, chg)
        }
        readings += globalReadings.sortedByDescending { it.multiple }

        // ---- record today's values (only while the market is open, so the closed-market cycles don't skew means)
        if (open) {
            observe("PCR", day, op.pcr); observe("SKEW", day, op.skew); observe("ATM_IV", day, op.atmIv)
            observe("BASIS_EXCESS_PCT", day, excessPct)
        }

        return Result(
            NormalizedState(readings, daysOfHistory(day)), typical, normalAbs,
            op.pcr, pcrNormal, pcrRel, op.skew, skewNormal, op.atmIv, ivNormal, ivRel, ivChange, iv15, iv30,
            excessPct, excessNormal, exDays, fair, rvRel, normalVol, move15, vixMove15, normalGap,
        )
    }

    companion object {
        /** Cost of carry for NIFTY futures (repo-like rate minus index dividend yield), annual. */
        const val CARRY_RATE = 0.055
        /** Typical |15-minute| India VIX change, %. */
        const val VIX_15M_NORMAL_PCT = 1.0

        fun meanAbsDailyMove(daily: List<Candle>): Double {
            val closes = daily.takeLast(21).map { it.c }.filter { it > 0 }
            if (closes.size < 11) return Double.NaN
            return closes.zipWithNext { a, b -> abs(b / a - 1) * 100 }.average()
        }

        /** Mean |open ÷ previous close − 1| over the last 20 sessions, % (0.35 % prior; floor 0.15 %). */
        fun normalGapPct(daily: List<Candle>): Double {
            val d = daily.takeLast(21)
            if (d.size < 11) return 0.35
            return d.zipWithNext { a, b -> abs(b.o / a.c - 1) * 100 }.average().coerceIn(0.15, 2.0)
        }
    }
}

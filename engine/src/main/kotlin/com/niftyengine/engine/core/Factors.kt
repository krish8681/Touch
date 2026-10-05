package com.niftyengine.engine.core

import com.niftyengine.engine.model.Detail
import com.niftyengine.engine.model.Factor
import com.niftyengine.engine.model.FactorReading
import com.niftyengine.engine.model.HorizonId
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.pow

/**
 * §32 — data freshness. Every input carries a timestamp; how quickly it goes stale depends on what it is
 * (a tick, a daily FII print, a monthly CPI release) AND on the horizon it is used for: a one-day-old FII figure
 * is still fresh for the monthly forecast but fading for the next 30 minutes.
 *
 * freshness = 1 while age ≤ full, then halves every half-life. Below [MIN_USABLE] the input counts as missing.
 */
object Fresh {
    enum class Cadence { LIVE, GLOBAL, DAILY, MONTHLY, QUARTERLY, NEWS }

    const val MIN_USABLE = 0.05
    private const val M = 60.0
    private const val H = 3600.0
    private const val D = 86_400.0

    /** (full, half-life) in seconds. DAILY ages are measured in trading days × 86 400 s. */
    fun params(c: Cadence, h: HorizonId): Pair<Double, Double> = when (c) {
        Cadence.LIVE -> when (h) {
            HorizonId.M30 -> 1.5 * M to 5 * M
            HorizonId.M60 -> 2 * M to 8 * M
            HorizonId.M180, HorizonId.CLOSE -> 3 * M to 15 * M
            HorizonId.WEEKLY -> 15 * M to 6 * H
            HorizonId.MONTHLY -> H to D
        }
        Cadence.GLOBAL -> if (h.intraday) 20 * M to 2 * H else if (h == HorizonId.WEEKLY) D to 2 * D else 2 * D to 5 * D
        Cadence.DAILY -> if (h.intraday) 1.2 * D to D else if (h == HorizonId.WEEKLY) 2 * D to 3 * D else 3 * D to 7 * D
        Cadence.MONTHLY -> if (h == HorizonId.MONTHLY) 45 * D to 45 * D else 40 * D to 30 * D
        Cadence.QUARTERLY -> 100 * D to 60 * D
        Cadence.NEWS -> Double.MAX_VALUE to D
    }

    fun of(ageSec: Double, c: Cadence, h: HorizonId): Double {
        if (ageSec.isNaN()) return 0.7 // no source timestamp: usable but degraded
        val (full, hl) = params(c, h)
        return if (ageSec <= full) 1.0 else 0.5.pow((ageSec - full) / hl)
    }

    /** Weekdays strictly after [from] up to and including [to] (holidays are not known; they read as one extra day). */
    fun tradingDaysBetween(from: LocalDate, to: LocalDate): Int {
        if (!to.isAfter(from)) return 0
        var n = 0
        var d = from.plusDays(1)
        while (!d.isAfter(to)) {
            if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) n++
            d = d.plusDays(1)
        }
        return n
    }

    /** Age of a daily data point expressed as trading days × 86 400 s (Friday's print on Monday = 1 day). */
    fun dailyAgeSec(asOf: Long, ref: Long): Double {
        if (asOf <= 0) return Double.NaN
        return tradingDaysBetween(Session.zdt(asOf).toLocalDate(), Session.zdt(ref).toLocalDate()) * D
    }
}

/**
 * Builds a factor's direction/strength from weighted sub-signals (each −1..+1).
 *
 *  direction = 100 × Σ wᵢsᵢ / Σ wᵢ
 *  strength  = 0.35 + 0.65 × coherence,  coherence = |Σ wᵢsᵢ| / Σ wᵢ|sᵢ|  (1 = all sub-signals agree)
 *
 * So a factor whose own evidence is split counts for less than one whose evidence lines up.
 */
object Composite {
    data class Part(val name: String, val score: Double, val weight: Double, val text: String = "")
    data class Result(val direction: Double, val strength: Double, val parts: List<Part>) {
        fun details(): List<Detail> = parts.map { Detail(it.name, (if (it.text.isBlank()) "" else it.text + " → ") + "%+.2f".format(it.score)) }
    }

    fun of(parts: List<Part>): Result? {
        val ok = parts.filter { !it.score.isNaN() && it.weight > 0 }
        if (ok.isEmpty()) return null
        val w = ok.sumOf { it.weight }
        val num = ok.sumOf { it.weight * M.clamp(it.score) }
        val absSum = ok.sumOf { it.weight * abs(M.clamp(it.score)) }
        val coherence = if (absSum <= 1e-12) 0.0 else abs(num) / absSum
        return Result(100 * num / w, 0.35 + 0.65 * coherence, ok)
    }

    /** Convenience: a complete [FactorReading] from parts (or a "missing" reading when no part has data). */
    fun reading(
        f: Factor, parts: List<Part>, freshness: Double, reliability: Double, asOf: Long, ageSec: Double, source: String,
        summary: (Result) -> String = { "" }, extra: List<Detail> = emptyList(), missingWhy: String = "no data",
    ): FactorReading {
        val r = of(parts) ?: return FactorReading.missing(f, missingWhy)
        if (freshness < Fresh.MIN_USABLE) return FactorReading(f, false, r.direction, r.strength, freshness, reliability, asOf, ageSec, source,
            "too stale (freshness %.0f%%)".format(freshness * 100), r.details() + extra)
        return FactorReading(f, true, M.clamp(r.direction, -100.0, 100.0), r.strength, freshness, reliability, asOf, ageSec, source,
            summary(r), r.details() + extra)
    }

    /** Blend two readings into one factor (e.g. FII + futures at 1 h). Missing parts are dropped. */
    fun blend(f: Factor, a: FactorReading, wa: Double, b: FactorReading, wb: Double, summary: String): FactorReading {
        val items = listOf(a to wa, b to wb).filter { it.first.available }
        if (items.isEmpty()) return FactorReading.missing(f, "no FII or futures data")
        val w = items.sumOf { it.second }
        fun avg(sel: (FactorReading) -> Double) = items.sumOf { sel(it.first) * it.second } / w
        return FactorReading(
            f, true, avg { it.direction }, avg { it.strength }, avg { it.freshness }, avg { it.reliability },
            items.maxOf { it.first.asOf }, items.minOf { if (it.first.ageSec.isNaN()) Double.MAX_VALUE else it.first.ageSec }.let { if (it == Double.MAX_VALUE) Double.NaN else it },
            items.joinToString(" + ") { it.first.source }, summary,
            items.flatMap { (r, _) -> listOf(Detail("— ${r.factor.label}", "%+.0f · %s".format(r.direction, r.summary))) + r.details },
        )
    }
}

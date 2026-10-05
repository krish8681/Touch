package com.niftyengine.engine.engines

import com.niftyengine.engine.core.ExpiryCalendar
import com.niftyengine.engine.core.M
import com.niftyengine.engine.core.PriceDistribution
import com.niftyengine.engine.core.Session
import com.niftyengine.engine.model.Detail
import com.niftyengine.engine.model.Direction
import com.niftyengine.engine.model.DistributionBucket
import com.niftyengine.engine.model.EventRiskLevel
import com.niftyengine.engine.model.ExpiryIntel
import com.niftyengine.engine.model.HorizonId
import com.niftyengine.engine.model.HorizonPrediction
import com.niftyengine.engine.model.PathPoint
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.sqrt

/**
 * 14 — Expected move (§18) + Probability engine (§19, §20).
 *
 *  σ (1-sigma move to the target, points)
 *    intraday: blend(India VIX, weekly ATM IV, realised 5-min vol, 20-day vol) × event multiplier, scaled by √(trading minutes)
 *    expiry  : blend(ATM IV of that expiry × √T, ATM straddle ÷ 0.8, 20-day realised vol × √days), VIX as fallback
 *  drift μ = κ × (score / 100) × σ          — κ is an uncalibrated engineering constant per horizon
 *  distribution = log-normal(μ, σ) [+ pin component at the OI concentration strike on expiry horizons]
 *  Bullish = P(S_T > S₀ + band), Bearish = P(S_T < S₀ − band), Neutral = the rest; band = 0.25 σ.
 *
 * These are MODEL SCORES. The score is never mapped directly to a probability: calibration against logged out-of-sample
 * outcomes (isotonic, per horizon) replaces them once enough outcomes exist (§27).
 */
object ProbabilityEngine {
    val KAPPA = mapOf(HorizonId.M30 to 1.2, HorizonId.M60 to 1.2, HorizonId.M180 to 1.1, HorizonId.CLOSE to 1.1,
        HorizonId.WEEKLY to 1.0, HorizonId.MONTHLY to 0.9)
    const val BAND = 0.25

    data class VolInputs(
        /** India VIX level (e.g. 13.5). */
        val vix: Double,
        /** ATM IV of the weekly / monthly chains (%). */
        val weeklyIv: Double,
        val monthlyIv: Double,
        /** Annualised fractions. */
        val realized: Double,
        val hist: Double,
        /** Intraday event multiplier (shock, VIX spike, fresh major event, global stress). */
        val eventMult: Double,
        val notes: List<Detail>,
    )

    fun volInputs(vix: Double, weeklyIv: Double, monthlyIv: Double, realized: Double, hist: Double, eventShock: Boolean,
                  vixState: VixState, freshMajorEvent: Boolean, globalStress: Double): VolInputs {
        var mult = 1.0
        if (eventShock) mult *= 1.4
        if (vixState == VixState.SPIKING) mult *= 1.25 else if (vixState == VixState.RISING) mult *= 1.08
        if (freshMajorEvent) mult *= 1.15
        mult *= 1 + 0.2 * globalStress
        return VolInputs(vix, weeklyIv, monthlyIv, realized, hist, mult, listOf(Detail("Event / vol multiplier (intraday)", "×%.2f".format(mult))))
    }

    /** Annualised intraday vol blend (fraction) + its sources. */
    fun intradayAnnualVol(v: VolInputs): Pair<Double, List<Detail>> {
        val parts = ArrayList<Pair<Double, Double>>(); val comps = ArrayList<Detail>()
        fun add(name: String, x: Double, w: Double) { if (!x.isNaN() && x > 0.01 && x < 2.0) { parts += w to x; comps += Detail(name, "%.1f%%".format(x * 100)) } }
        add("India VIX", v.vix / 100, 0.30); add("Weekly ATM IV (trading-time)", v.weeklyIv / 100, 0.30)
        add("Realised (5m)", v.realized, 0.25); add("Historical (20d)", v.hist, 0.15)
        val annual = if (parts.isEmpty()) 0.14 else parts.sumOf { it.first * it.second } / parts.sumOf { it.first }
        return annual * v.eventMult to comps + v.notes
    }

    data class Target(val time: Long, val tradingMinutes: Double, val overnight: Boolean, val note: String)

    fun target(h: HorizonId, now: Long, open: Boolean, expiryMillis: Long = 0L): Target = when (h) {
        HorizonId.M30, HorizonId.M60, HorizonId.M180 -> if (open) {
            val toClose = Session.minutesToClose(now)
            val m = minOf(h.minutes.toDouble(), toClose).coerceAtLeast(5.0)
            Target(now + (m * 60_000).toLong(), m, false, if (toClose < h.minutes) "capped at today's close (%.0f min left)".format(toClose) else "")
        } else {
            val t = ExpiryCalendar.addTradingMinutes(now, h.minutes.toDouble())
            Target(t, h.minutes.toDouble(), true, "next session (indicative — market closed)")
        }
        HorizonId.CLOSE -> if (open) Target(Session.closeOf(Session.zdt(now).toLocalDate()), Session.minutesToClose(now).coerceAtLeast(1.0), false, "")
        else ExpiryCalendar.nextSessionClose(now).let { Target(it, Session.SESSION_MINUTES.toDouble(), true, "next session's close (market closed)") }
        HorizonId.WEEKLY, HorizonId.MONTHLY -> Target(expiryMillis, ExpiryCalendar.tradingMinutesBetween(now, expiryMillis), false, "")
    }

    /** σ (points) to an expiry, from the chain's ATM IV, its straddle and realised vol (VIX as fallback). */
    fun expirySigma(spot: Double, now: Long, expiryMillis: Long, intel: ExpiryIntel?, v: VolInputs, eventRisk: EventRiskLevel): Pair<Double, List<Detail>> {
        val tY = Session.yearsToExpiry(now, expiryMillis)
        val tradingDays = ExpiryCalendar.tradingMinutesBetween(now, expiryMillis) / Session.SESSION_MINUTES
        val parts = ArrayList<Pair<Double, Double>>(); val comps = ArrayList<Detail>()
        fun add(name: String, sigma: Double, w: Double) {
            if (!sigma.isNaN() && sigma > 0) { parts += w to sigma; comps += Detail(name, "±%.0f pts".format(sigma)) }
        }
        val iv = intel?.atmIv ?: Double.NaN
        add("ATM IV %.1f%% × √T".format(iv), if (iv.isNaN() || iv <= 0) Double.NaN else spot * iv / 100 * sqrt(tY), 0.5)
        add("ATM straddle ÷ 0.8", intel?.expectedMoveStraddle?.takeIf { (intel.daysToExpiry) > 0.05 } ?: Double.NaN, 0.3)
        add("Realised 20d vol", if (v.hist.isNaN()) Double.NaN else spot * v.hist * sqrt(tradingDays.coerceAtLeast(0.05) / Session.TRADING_DAYS), 0.2)
        if (parts.none { it.first >= 0.3 }) add("India VIX %.1f × √T (fallback)".format(v.vix), if (v.vix.isNaN()) Double.NaN else spot * v.vix / 100 * sqrt(tY), 0.6)
        var sigma = if (parts.isEmpty()) spot * 0.14 * sqrt(tY) else parts.sumOf { it.first * it.second } / parts.sumOf { it.first }
        // Implied vol already prices scheduled events; only an extreme, unpriced situation widens the range further.
        if (eventRisk == EventRiskLevel.EXTREME) { sigma *= 1.1; comps += Detail("Extreme event risk", "×1.10") }
        return sigma.coerceAtLeast(spot * 0.0005) to comps
    }

    private val dayFmt = DateTimeFormatter.ofPattern("EEE dd MMM", Locale.ENGLISH)

    /** Build the prediction for one horizon (confidence and event risk are filled in later). */
    fun predict(
        h: HorizonId, spot: Double, now: Long, scored: HorizonEngine.Scored, sigma: Double, volUsed: Double, volSources: List<Detail>,
        target: Target, intel: ExpiryIntel?, pinWeightScale: Double,
        calibrate: (HorizonId, Double, Double, Double) -> Triple<Double, Double, Double>?,
    ): HorizonPrediction {
        val kappa = KAPPA.getValue(h)
        val drift = kappa * scored.score / 100 * sigma
        val pin = intel?.pin
        val pinW = if (pin == null) 0.0 else M.clamp(pin.strength * pinWeightScale, 0.0, 0.5)
        val dist = PriceDistribution.build(spot, sigma, drift, pin?.strike ?: Double.NaN, pinW)
        val band = BAND * sigma
        val pBull = dist.pAbove(spot + band); val pBear = dist.pBelow(spot - band)
        val pNeutral = (1 - pBull - pBear).coerceAtLeast(0.0)
        val cal = calibrate(h, pBull, pNeutral, pBear)
        val b = cal?.first ?: pBull; val n = cal?.second ?: pNeutral; val d = cal?.third ?: pBear
        val direction = when {
            abs(b - d) < 0.03 -> Direction.NEUTRAL
            n > b && n > d -> Direction.NEUTRAL
            b > d -> Direction.BULLISH
            else -> Direction.BEARISH
        }
        val prob = when (direction) { Direction.BULLISH -> b; Direction.BEARISH -> d; Direction.NEUTRAL -> n }
        val buckets = buckets(dist, spot, sigma)
        val path = if (h == HorizonId.WEEKLY || h == HorizonId.MONTHLY) path(h, spot, now, target.time, sigma, drift) else emptyList()
        return HorizonPrediction(
            id = h, targetTime = target.time, tradingMinutes = target.tradingMinutes, spot = spot, score = scored.score,
            coverage = scored.coverage, factors = scored.contributions, missing = scored.missing, overlay = scored.overlay, overlayNote = scored.overlayNote,
            sigmaPts = sigma, driftPts = drift, neutralBand = band, annualVolUsed = volUsed, volSources = volSources, components = dist.comps,
            pBull = pBull, pNeutral = pNeutral, pBear = pBear, calibrated = cal != null,
            calPBull = cal?.first ?: Double.NaN, calPNeutral = cal?.second ?: Double.NaN, calPBear = cal?.third ?: Double.NaN,
            direction = direction, probability = prob, expectedPrice = dist.quantile(0.5),
            rangeLow = dist.quantile(0.1587), rangeHigh = dist.quantile(0.8413),
            range90Low = dist.quantile(0.05), range90High = dist.quantile(0.95), buckets = buckets, path = path,
            expiry = intel?.expiry ?: if (h == HorizonId.WEEKLY || h == HorizonId.MONTHLY) Session.zdt(target.time).toLocalDate().toString() else "",
            daysToExpiry = if (h == HorizonId.WEEKLY || h == HorizonId.MONTHLY) Session.yearsToExpiry(now, target.time) * 365 else Double.NaN,
            support = intel?.support?.strike ?: Double.NaN, resistance = intel?.resistance?.strike ?: Double.NaN,
            pinStrike = pin?.strike ?: Double.NaN, pinWeight = pinW, note = target.note,
        )
    }

    private fun niceStep(x: Double): Double {
        val steps = listOf(10.0, 25.0, 50.0, 100.0, 200.0, 250.0, 500.0, 1000.0, 2000.0)
        return steps.minBy { abs(it - x) }
    }

    /** 5 inner buckets of ≈0.5σ centred on spot (rounded to 50) plus two tails (§19). */
    fun buckets(dist: PriceDistribution, spot: Double, sigma: Double): List<DistributionBucket> {
        val w = niceStep(0.5 * sigma)
        val unit = if (w >= 50) 50.0 else 5.0
        val c = round(spot / unit) * unit
        val edges = (-2..3).map { c + (it - 0.5) * w }
        val out = ArrayList<DistributionBucket>()
        out += DistributionBucket(Double.NEGATIVE_INFINITY, edges.first(), "< %,.0f".format(edges.first()), dist.pBelow(edges.first()))
        for (i in 0 until edges.size - 1) out += DistributionBucket(edges[i], edges[i + 1], "%,.0f–%,.0f".format(edges[i], edges[i + 1]), dist.pBetween(edges[i], edges[i + 1]))
        out += DistributionBucket(edges.last(), Double.POSITIVE_INFINITY, "> %,.0f".format(edges.last()), dist.pAbove(edges.last()))
        return out
    }

    /** Checkpoints on the way to an expiry: +1…+5 sessions (weekly) or every 5 sessions (monthly). Drift accrues linearly, σ with √t. */
    fun path(h: HorizonId, spot: Double, now: Long, expiry: Long, sigmaT: Double, driftT: Double): List<PathPoint> {
        val tT = Session.yearsToExpiry(now, expiry).coerceAtLeast(1e-6)
        val ks = if (h == HorizonId.WEEKLY) (1..5).toList() else listOf(5, 10, 15, 20)
        val out = ArrayList<PathPoint>()
        for (k in ks) {
            val t = ExpiryCalendar.closeAfterTradingDays(now, k)
            if (t >= expiry - 3_600_000L) break
            out += point("+${k}d · ${Session.zdt(t).format(dayFmt)}", spot, now, t, tT, sigmaT, driftT)
        }
        out += point("Expiry · ${Session.zdt(expiry).format(dayFmt)}", spot, now, expiry, tT, sigmaT, driftT)
        return out
    }

    private fun point(label: String, spot: Double, now: Long, t: Long, tT: Double, sigmaT: Double, driftT: Double): PathPoint {
        val f = (Session.yearsToExpiry(now, t) / tT).coerceIn(1e-4, 1.0)
        val s = sigmaT * sqrt(f); val mu = driftT * f
        val d = PriceDistribution.build(spot, s, mu)
        val band = BAND * s
        return PathPoint(label, t, d.quantile(0.5), d.quantile(0.1587), d.quantile(0.8413), d.pAbove(spot + band), d.pBelow(spot - band))
    }
}

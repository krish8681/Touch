package com.niftyengine.engine.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tanh

object M {
    fun clamp(x: Double, lo: Double = -1.0, hi: Double = 1.0): Double =
        if (x.isNaN()) 0.0 else min(hi, max(lo, x))

    /** Squash an unbounded value into -1..1; [scale] is the input that maps to ~0.76. */
    fun squash(x: Double, scale: Double): Double =
        if (x.isNaN() || scale <= 0) 0.0 else tanh(x / scale)

    fun mean(xs: List<Double>): Double = if (xs.isEmpty()) Double.NaN else xs.sum() / xs.size

    fun std(xs: List<Double>): Double {
        if (xs.size < 2) return Double.NaN
        val m = mean(xs)
        return sqrt(xs.sumOf { (it - m) * (it - m) } / (xs.size - 1))
    }

    fun pctChange(from: Double, to: Double): Double =
        if (from.isNaN() || to.isNaN() || from == 0.0) Double.NaN else (to - from) / from * 100.0

    fun logReturns(xs: List<Double>): List<Double> =
        xs.zipWithNext().filter { it.first > 0 && it.second > 0 }.map { ln(it.second / it.first) }

    /** Percentile rank (0..100) of [x] within [xs]. */
    fun percentileRank(xs: List<Double>, x: Double): Double {
        val clean = xs.filter { !it.isNaN() }
        if (clean.isEmpty() || x.isNaN()) return Double.NaN
        return clean.count { it <= x }.toDouble() / clean.size * 100.0
    }

    fun ema(xs: List<Double>, period: Int): List<Double> {
        if (xs.isEmpty()) return emptyList()
        val k = 2.0 / (period + 1)
        val out = ArrayList<Double>(xs.size)
        var e = xs[0]
        for (x in xs) { e = x * k + e * (1 - k); out.add(e) }
        return out
    }

    fun rsi(closes: List<Double>, period: Int = 14): Double {
        if (closes.size <= period) return Double.NaN
        var gain = 0.0; var loss = 0.0
        for (i in 1..period) {
            val d = closes[i] - closes[i - 1]
            if (d > 0) gain += d else loss -= d
        }
        gain /= period; loss /= period
        for (i in period + 1 until closes.size) {
            val d = closes[i] - closes[i - 1]
            gain = (gain * (period - 1) + max(d, 0.0)) / period
            loss = (loss * (period - 1) + max(-d, 0.0)) / period
        }
        if (loss == 0.0) return 100.0
        return 100.0 - 100.0 / (1 + gain / loss)
    }

    fun atr(c: List<com.niftyengine.engine.model.Candle>, period: Int = 14): Double {
        if (c.size < 2) return Double.NaN
        val trs = (1 until c.size).map { i ->
            maxOf(c[i].h - c[i].l, abs(c[i].h - c[i - 1].c), abs(c[i].l - c[i - 1].c))
        }
        val n = min(period, trs.size)
        var a = trs.take(n).average()
        for (i in n until trs.size) a = (a * (period - 1) + trs[i]) / period
        return a
    }

    /** Wilder ADX. Returns Triple(adx, +DI, -DI). */
    fun adx(c: List<com.niftyengine.engine.model.Candle>, period: Int = 14): Triple<Double, Double, Double> {
        if (c.size < period + 2) return Triple(Double.NaN, Double.NaN, Double.NaN)
        var trS = 0.0; var pS = 0.0; var mS = 0.0
        val dxs = ArrayList<Double>()
        var pdi = 0.0; var mdi = 0.0
        for (i in 1 until c.size) {
            val up = c[i].h - c[i - 1].h
            val dn = c[i - 1].l - c[i].l
            val pdm = if (up > dn && up > 0) up else 0.0
            val mdm = if (dn > up && dn > 0) dn else 0.0
            val tr = maxOf(c[i].h - c[i].l, abs(c[i].h - c[i - 1].c), abs(c[i].l - c[i - 1].c))
            if (i <= period) { trS += tr; pS += pdm; mS += mdm } else {
                trS = trS - trS / period + tr; pS = pS - pS / period + pdm; mS = mS - mS / period + mdm
            }
            if (i >= period && trS > 0) {
                pdi = 100 * pS / trS; mdi = 100 * mS / trS
                val s = pdi + mdi
                dxs.add(if (s == 0.0) 0.0 else 100 * abs(pdi - mdi) / s)
            }
        }
        if (dxs.isEmpty()) return Triple(Double.NaN, pdi, mdi)
        var a = dxs.take(min(period, dxs.size)).average()
        for (i in min(period, dxs.size) until dxs.size) a = (a * (period - 1) + dxs[i]) / period
        return Triple(a, pdi, mdi)
    }

    fun softmax(z: DoubleArray): DoubleArray {
        val mx = z.max()
        val e = z.map { exp(it - mx) }
        val s = e.sum()
        return e.map { it / s }.toDoubleArray()
    }

    /** Standard normal CDF (Abramowitz-Stegun 7.1.26 via erf). */
    fun normCdf(x: Double): Double {
        val t = 1.0 / (1.0 + 0.3275911 * abs(x) / sqrt(2.0))
        val y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) *
            t * exp(-x * x / 2.0)
        return if (x >= 0) 0.5 * (1 + y) else 0.5 * (1 - y)
    }

    fun normPdf(x: Double): Double = exp(-x * x / 2.0) / sqrt(2 * Math.PI)

    /** Inverse standard normal CDF (Acklam's rational approximation, |error| < 1.2e-9). */
    fun normInv(p: Double): Double {
        if (p <= 0.0) return Double.NEGATIVE_INFINITY
        if (p >= 1.0) return Double.POSITIVE_INFINITY
        val a = doubleArrayOf(-3.969683028665376e1, 2.209460984245205e2, -2.759285104469687e2, 1.383577518672690e2, -3.066479806614716e1, 2.506628277459239)
        val b = doubleArrayOf(-5.447609879822406e1, 1.615858368580409e2, -1.556989798598866e2, 6.680131188771972e1, -1.328068155288572e1)
        val c = doubleArrayOf(-7.784894002430293e-3, -3.223964580411365e-1, -2.400758277161838, -2.549732539343734, 4.374664141464968, 2.938163982698783)
        val d = doubleArrayOf(7.784695709041462e-3, 3.224671290700398e-1, 2.445134137142996, 3.754408661907416)
        val lo = 0.02425
        return when {
            p < lo -> { val q = sqrt(-2 * ln(p)); (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1) }
            p > 1 - lo -> { val q = sqrt(-2 * ln(1 - p)); -(((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1) }
            else -> { val q = p - 0.5; val r = q * q; (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q / (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1) }
        }
    }

    /** Normal CDF of N([mu], [sd]²) at [x], with ±∞ handled. */
    fun cdf(x: Double, mu: Double, sd: Double): Double = when {
        x == Double.NEGATIVE_INFINITY -> 0.0
        x == Double.POSITIVE_INFINITY -> 1.0
        else -> normCdf((x - mu) / sd.coerceAtLeast(1e-9))
    }

    /** E[X | lo < X < hi] for X ~ N([mu], [sd]²). Falls back to the bucket midpoint/edge when the mass is negligible. */
    fun truncMean(lo: Double, hi: Double, mu: Double, sd: Double): Double {
        val s = sd.coerceAtLeast(1e-9)
        val a = if (lo.isInfinite()) Double.NEGATIVE_INFINITY else (lo - mu) / s
        val b = if (hi.isInfinite()) Double.POSITIVE_INFINITY else (hi - mu) / s
        val mass = (if (b.isInfinite()) 1.0 else normCdf(b)) - (if (a.isInfinite()) 0.0 else normCdf(a))
        if (mass < 1e-9) return when {
            lo.isInfinite() -> hi - 0.5 * s
            hi.isInfinite() -> lo + 0.5 * s
            else -> (lo + hi) / 2
        }
        val pa = if (a.isInfinite()) 0.0 else normPdf(a)
        val pb = if (b.isInfinite()) 0.0 else normPdf(b)
        return mu + s * (pa - pb) / mass
    }
}

/**
 * Variance-consistent option clock. Market IVs (and India VIX) are quoted in calendar time, but NIFTY's variance is
 * realised during trading hours — the engine's expected move scales with trading minutes. Repricing an option at a
 * horizon with calendar time would charge only 1/24 of a day's decay per trading hour (≈4–5× too little theta
 * intraday), flattering option buyers and hiding the edge of option sellers. So the horizon value is computed in
 * trading time with the IV rescaled to keep today's total variance (and therefore today's price) unchanged.
 */
data class OptionClock(val tCal: Double, val tTrd: Double) {
    /** Multiply a calendar-time IV by this to get the equivalent trading-time IV. */
    val volScale: Double get() = kotlin.math.sqrt(tCal / tTrd)

    /** Trading-time years left after [minutes] more trading minutes. */
    fun after(minutes: Int): Double = (tTrd - minutes / (Session.SESSION_MINUTES * Session.TRADING_DAYS)).coerceAtLeast(MIN_T)

    companion object {
        private const val MIN_T = 30.0 / (Session.SESSION_MINUTES * Session.TRADING_DAYS)

        fun of(now: Long, expiryMillis: Long): OptionClock {
            val cal = Session.yearsToExpiry(now, expiryMillis)
            val trd = (Session.tradingMinutesUntil(now, expiryMillis) / (Session.SESSION_MINUTES * Session.TRADING_DAYS)).coerceAtLeast(MIN_T)
            return OptionClock(cal, trd)
        }
    }
}

/** Black-Scholes (no dividends) for European index options. */
object BlackScholes {
    data class Greeks(val price: Double, val delta: Double, val gamma: Double, val thetaPerDay: Double, val vega: Double)

    fun price(isCall: Boolean, s: Double, k: Double, tYears: Double, vol: Double, r: Double = 0.065): Greeks {
        if (tYears <= 1e-6 || vol <= 1e-6) {
            val intrinsic = if (isCall) max(0.0, s - k) else max(0.0, k - s)
            val d = if (isCall) (if (s > k) 1.0 else 0.0) else (if (s < k) -1.0 else 0.0)
            return Greeks(intrinsic, d, 0.0, 0.0, 0.0)
        }
        val sq = sqrt(tYears)
        val d1 = (ln(s / k) + (r + vol * vol / 2) * tYears) / (vol * sq)
        val d2 = d1 - vol * sq
        val disc = exp(-r * tYears)
        val pdf = M.normPdf(d1)
        val gamma = pdf / (s * vol * sq)
        val vega = s * pdf * sq / 100.0
        return if (isCall) {
            val p = s * M.normCdf(d1) - k * disc * M.normCdf(d2)
            val theta = (-s * pdf * vol / (2 * sq) - r * k * disc * M.normCdf(d2)) / 365.0
            Greeks(p, M.normCdf(d1), gamma, theta, vega)
        } else {
            val p = k * disc * M.normCdf(-d2) - s * M.normCdf(-d1)
            val theta = (-s * pdf * vol / (2 * sq) + r * k * disc * M.normCdf(-d2)) / 365.0
            Greeks(p, M.normCdf(d1) - 1, gamma, theta, vega)
        }
    }

    /** Implied vol by bisection; NaN when price is outside no-arbitrage bounds. */
    fun impliedVol(isCall: Boolean, s: Double, k: Double, tYears: Double, premium: Double): Double {
        if (premium <= 0 || tYears <= 0) return Double.NaN
        var lo = 0.01; var hi = 3.0
        if (price(isCall, s, k, tYears, hi).price < premium) return Double.NaN
        repeat(60) {
            val mid = (lo + hi) / 2
            if (price(isCall, s, k, tYears, mid).price > premium) hi = mid else lo = mid
        }
        return (lo + hi) / 2
    }
}

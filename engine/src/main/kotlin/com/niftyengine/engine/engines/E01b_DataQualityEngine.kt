package com.niftyengine.engine.engines

import com.niftyengine.engine.core.BlackScholes
import com.niftyengine.engine.core.Session
import com.niftyengine.engine.model.DataQualityReport
import com.niftyengine.engine.model.EngineSignal
import com.niftyengine.engine.model.FeedQuality
import com.niftyengine.engine.model.FeedStatus
import com.niftyengine.engine.model.MarketSnapshot
import java.time.DayOfWeek
import kotlin.math.abs

/**
 * 01b — Data Quality / Freshness Engine + Circuit Breaker.
 *
 * Every input is classified LIVE / DEGRADED / STALE / INVALID / MISSING / MANUAL with its source,
 * timestamp and age. Stale/invalid inputs are removed as live drivers, degraded ones are down-weighted,
 * the overall quality score caps confidence, and the circuit breaker forces DATA ERROR — NO TRADE when a
 * critical input (NIFTY, futures, option chain) is missing, stale or implausible.
 */
class DataQualityEngine(
    /** Feeds whose absence trips the circuit breaker. A market-only historical backtest needs only NIFTY. */
    private val criticalFeeds: Set<String> = setOf("NIFTY", "Futures", "Options"),
) {
    data class Limits(val liveSec: Double, val staleSec: Double)

    companion object {
        val LIMITS = mapOf(
            "NIFTY" to Limits(120.0, 600.0), "Futures" to Limits(180.0, 900.0), "Options" to Limits(240.0, 900.0),
            "India VIX" to Limits(300.0, 1200.0), "Constituents" to Limits(240.0, 900.0), "Sectors" to Limits(300.0, 1200.0),
            "NIFTY bars" to Limits(300.0, 1800.0), "Monthly options" to Limits(300.0, 1800.0),
        )
        private val WEIGHTS = mapOf(
            "NIFTY" to 20.0, "Futures" to 15.0, "Options" to 15.0, "India VIX" to 10.0, "Constituents" to 10.0,
            "Sectors" to 5.0, "NIFTY bars" to 5.0, "Global" to 8.0, "FPI/DII" to 4.0, "News" to 5.0, "Macro" to 3.0, "GIFT Nifty" to 3.0,
            "Monthly options" to 5.0, "FII derivatives" to 4.0, "Valuation" to 2.0,
        )
        /** Max useful age (days) of slow macro values, by release frequency. */
        val MACRO_MAX_AGE_DAYS = mapOf(
            "repoRate" to 70.0, "lastPolicyChangeBps" to 70.0, "cpiYoY" to 45.0, "cpiPrevYoY" to 75.0, "wpiYoY" to 45.0,
            "gdpGrowth" to 100.0, "gdpPrevGrowth" to 190.0, "pmiManufacturing" to 45.0, "iipYoY" to 60.0,
            "creditGrowth" to 30.0, "liquidityCr" to 7.0, "tradeBalanceBn" to 45.0,
        )

        /** Which engine signals each feed powers (used to drop/down-weight stale drivers). */
        val SIGNALS_BY_FEED = mapOf(
            "Futures" to listOf("Futures"), "India VIX" to listOf("India VIX"),
            "Constituents" to listOf("Heavyweights", "Breadth"), "Sectors" to listOf("Sectors"),
            "Global" to listOf("Global risk"), "News" to listOf("News"),
            "NIFTY bars" to listOf("Price structure"), "GIFT Nifty" to listOf("GIFT Nifty"),
        )

        fun factor(st: FeedStatus) = when (st) {
            FeedStatus.LIVE -> 1.0; FeedStatus.MANUAL -> 0.8; FeedStatus.DEGRADED -> 0.6
            FeedStatus.STALE -> 0.15; FeedStatus.INVALID, FeedStatus.MISSING -> 0.0
        }

        /** Latest session close at or before [now] (weekends skipped; exchange holidays look like an older close). */
        fun referenceTime(now: Long): Long {
            if (Session.isOpen(now)) return now
            var d = Session.zdt(now).toLocalDate()
            val close = Session.closeOf(d)
            if (now < close || d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) {
                do { d = d.minusDays(1) } while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY)
            }
            return minOf(now, Session.closeOf(d))
        }
    }

    /**
     * @param prevSpot spot from the previous cycle (NaN if none), [prevT] its time — used for jump detection.
     */
    /**
     * Instant against which data ages are measured. Market closed (evening, weekend, exchange holiday): the newest
     * critical timestamp of the last session, so a feed lagging the others is still caught. (No holiday calendar:
     * on a weekday holiday during market hours old data correctly reads as STALE — nothing trades anyway.)
     */
    fun refTime(s: MarketSnapshot, now: Long): Long = if (Session.isOpen(now)) now else listOfNotNull(s.nifty.asOf, s.optionChain?.asOf, s.futures?.asOf)
        .filter { it in 1..now && now - it < 5 * 86_400_000L }.maxOrNull() ?: referenceTime(now)

    fun assess(s: MarketSnapshot, now: Long, prevSpot: Double, prevT: Long): DataQualityReport {
        val open = Session.isOpen(now)
        val ref = refTime(s, now)
        val feeds = ArrayList<FeedQuality>()
        val breaker = ArrayList<String>()
        val warnings = ArrayList<String>()

        fun timed(name: String, asOf: Long, source: String, critical: Boolean, invalid: String? = null, extra: String = ""): FeedQuality {
            val lim = LIMITS.getValue(name)
            val age = if (asOf <= 0) Double.NaN else ((ref - asOf) / 1000.0).coerceAtLeast(0.0)
            val (st, why) = when {
                invalid != null -> FeedStatus.INVALID to invalid
                asOf <= 0 -> FeedStatus.DEGRADED to "no source timestamp"
                age <= lim.liveSec -> FeedStatus.LIVE to extra
                age <= lim.staleSec -> FeedStatus.DEGRADED to "lagging ${fmtAge(age)}"
                else -> FeedStatus.STALE to "last update ${fmtAge(age)} ago"
            }
            return FeedQuality(name, st, source, asOf, age, critical, why).also { feeds += it }
        }

        // ---- NIFTY spot
        val n = s.nifty
        val niftyInvalid = when {
            n.last.isNaN() || n.last <= 0 -> "invalid price ${n.last}"
            n.prevClose.isNaN() || n.prevClose <= 0 -> "missing previous close"
            abs(n.changePct) > 12 -> "implausible change %.1f%%".format(n.changePct)
            else -> null
        }
        timed("NIFTY", n.asOf, s.source, "NIFTY" in criticalFeeds, niftyInvalid)
        if (!prevSpot.isNaN() && prevSpot > 0 && n.last > 0 && now - prevT in 1..180_000) {
            val jump = abs(n.last - prevSpot) / prevSpot * 100
            if (jump > 2.5) breaker += "NIFTY jumped %.2f%% between cycles — verify feed".format(jump)
        }

        // ---- Futures
        val f = s.futures
        if (f == null) feeds += FeedQuality("Futures", FeedStatus.MISSING, "-", 0, Double.NaN, "Futures" in criticalFeeds, "no futures quote")
        else timed("Futures", f.asOf, s.source, "Futures" in criticalFeeds, when {
            f.last.isNaN() || f.last <= 0 -> "invalid futures price"
            n.last > 0 && abs(f.last - n.last) / n.last > 0.02 -> "basis %.2f%% implausible".format((f.last - n.last) / n.last * 100)
            else -> null
        })

        // ---- Option chain
        val c = s.optionChain
        if (c == null || c.rows.isEmpty()) feeds += FeedQuality("Options", FeedStatus.MISSING, "-", 0, Double.NaN, "Options" in criticalFeeds, "no option chain")
        else {
            val spot = n.last
            val atm = c.rows.minBy { abs(it.strike - spot) }
            val tY = Session.yearsToExpiry(now, c.expiryMillis)
            fun ivOf(isCall: Boolean): Double {
                val leg = if (isCall) atm.call else atm.put
                if (!leg.iv.isNaN() && leg.iv > 0) return leg.iv
                val mid = if (leg.bid > 0 && leg.ask > 0) (leg.bid + leg.ask) / 2 else leg.ltp
                return if (mid > 0) BlackScholes.impliedVol(isCall, spot, atm.strike, tY, mid) * 100 else Double.NaN
            }
            val ivs = listOf(ivOf(true), ivOf(false)).filter { !it.isNaN() }
            val atmIv = if (ivs.isEmpty()) Double.NaN else ivs.average()
            val spreadPct = listOf(atm.call, atm.put).filter { it.bid > 0 && it.ask > 0 }
                .map { (it.ask - it.bid) / ((it.ask + it.bid) / 2) * 100 }.maxOrNull()
            val invalid = when {
                c.rows.size < 10 -> "only ${c.rows.size} strikes"
                c.underlying > 0 && spot > 0 && abs(c.underlying - spot) / spot > 0.006 ->
                    "chain underlying %.0f vs spot %.0f".format(c.underlying, spot)
                atmIv.isNaN() || atmIv <= 1 || atmIv >= 150 -> "ATM IV invalid (%.1f)".format(atmIv)
                spreadPct != null && spreadPct > 10 -> "ATM spread %.1f%% extreme".format(spreadPct)
                c.expiryMillis < now -> "expired chain"
                else -> null
            }
            timed("Options", c.asOf, s.source, "Options" in criticalFeeds, invalid, "ATM IV %.1f%%".format(atmIv))
        }

        // ---- other timed feeds
        s.vix?.let { timed("India VIX", it.asOf, s.source, false, if (it.last <= 0) "invalid VIX" else null) }
            ?: run { feeds += FeedQuality("India VIX", FeedStatus.MISSING, "-", 0, Double.NaN, false) }
        if (s.constituents.size >= 20) {
            val ts = s.constituents.values.map { it.asOf }.filter { it > 0 }.sorted()
            timed("Constituents", if (ts.isEmpty()) 0 else ts[ts.size / 2], s.source, false,
                if (s.constituents.values.count { it.last <= 0 } > 5) "invalid prices" else null, "${s.constituents.size} stocks")
        } else feeds += FeedQuality("Constituents", FeedStatus.MISSING, "-", 0, Double.NaN, false, "${s.constituents.size} stocks")
        if (s.sectors.isNotEmpty()) timed("Sectors", s.sectors.values.maxOf { it.asOf }, s.source, false)
        else feeds += FeedQuality("Sectors", FeedStatus.MISSING, "-", 0, Double.NaN, false)
        n.intraday.lastOrNull()?.let { timed("NIFTY bars", it.t, s.source, false, extra = "${n.intraday.size} bars") }
            ?: run { feeds += FeedQuality("NIFTY bars", FeedStatus.DEGRADED, "-", 0, Double.NaN, false, "no bars; using own ticks") }

        // ---- global: markets close overnight, so judge coverage rather than age
        val g = s.global.size
        feeds += FeedQuality("Global", when { g >= 8 -> FeedStatus.LIVE; g >= 4 -> FeedStatus.DEGRADED; else -> FeedStatus.MISSING },
            "Yahoo", s.global.values.maxOfOrNull { it.asOf } ?: 0, Double.NaN, false, "$g assets")

        // ---- flows (daily data)
        s.flows.let { fl ->
            if (fl == null) feeds += FeedQuality("FPI/DII", FeedStatus.MISSING, "-", 0, Double.NaN, false)
            else {
                val ageD = if (fl.asOf > 0) (now - fl.asOf) / 86_400_000.0 else Double.NaN
                val st = when { ageD.isNaN() -> FeedStatus.DEGRADED; ageD <= 4 -> FeedStatus.LIVE; ageD <= 7 -> FeedStatus.DEGRADED; else -> FeedStatus.STALE }
                feeds += FeedQuality("FPI/DII", st, "NSE", fl.asOf, ageD * 86400, false, "daily, as of ${fl.date.ifBlank { "?" }}")
            }
        }
        // ---- GIFT Nifty (trades ~06:30–02:45 IST; quiet 02:45–06:30 is normal, so only age while it should trade)
        s.giftNifty.let { gn ->
            if (gn == null) feeds += FeedQuality("GIFT Nifty", FeedStatus.MISSING, "NSE", 0, Double.NaN, false)
            else {
                val ageM = if (gn.asOf > 0) ((now - gn.asOf) / 60_000.0).coerceAtLeast(0.0) else Double.NaN
                val h = Session.zdt(now).hour
                val quietHours = h in 3..6
                val st = when {
                    gn.last <= 0 -> FeedStatus.INVALID
                    ageM.isNaN() -> FeedStatus.DEGRADED
                    ageM <= 15 || quietHours -> FeedStatus.LIVE
                    ageM <= 360 -> FeedStatus.DEGRADED
                    else -> FeedStatus.STALE
                }
                feeds += FeedQuality("GIFT Nifty", st, "NSE IX", gn.asOf, ageM * 60, false, "%.1f (%+.2f%%)".format(gn.last, gn.changePct))
            }
        }
        // ---- monthly chain (H3), FII participant OI and valuation (daily)
        s.monthlyChain.let { mc ->
            if (mc == null || mc.rows.isEmpty()) feeds += FeedQuality("Monthly options", FeedStatus.MISSING, "-", 0, Double.NaN, false, "no monthly chain")
            else timed("Monthly options", mc.asOf, s.source, false, if (mc.expiryMillis < now) "expired chain" else null, "exp ${mc.expiry}")
        }
        fun daily(name: String, asOf: Long, src: String, detail: String) {
            val ageD = if (asOf > 0) (now - asOf) / 86_400_000.0 else Double.NaN
            val st = when { ageD.isNaN() -> FeedStatus.DEGRADED; ageD <= 4 -> FeedStatus.LIVE; ageD <= 7 -> FeedStatus.DEGRADED; else -> FeedStatus.STALE }
            feeds += FeedQuality(name, st, src, asOf, ageD * 86400, false, detail)
        }
        s.fiiDerivatives?.let { daily("FII derivatives", it.asOf, it.source, "${it.days.size} days · latest ${it.days.lastOrNull()?.date ?: "?"}") }
            ?: run { feeds += FeedQuality("FII derivatives", FeedStatus.MISSING, "NSE", 0, Double.NaN, false) }
        s.valuation?.let { daily("Valuation", it.asOf, it.source, "P/E %.1f".format(it.pe)) }
            ?: run { feeds += FeedQuality("Valuation", FeedStatus.MISSING, "NSE", 0, Double.NaN, false) }

        // ---- news
        val newest = s.news.filter { it.publishedAt <= now }.maxOfOrNull { it.publishedAt }
        if (newest == null) feeds += FeedQuality("News", FeedStatus.MISSING, "RSS", 0, Double.NaN, false)
        else {
            val age = (now - newest) / 1000.0
            feeds += FeedQuality("News", when { age <= 6 * 3600 -> FeedStatus.LIVE; age <= 24 * 3600 -> FeedStatus.DEGRADED; else -> FeedStatus.STALE },
                "RSS", newest, age, false, "${s.news.size} items")
        }
        // ---- macro: one row per supplied value, MANUAL with release age
        val m = s.macro
        val values = mapOf("repoRate" to m.repoRate, "cpiYoY" to m.cpiYoY, "gdpGrowth" to m.gdpGrowth, "pmiManufacturing" to m.pmiManufacturing,
            "iipYoY" to m.iipYoY, "creditGrowth" to m.creditGrowth, "liquidityCr" to m.liquidityCr)
        val macroRows = values.filterValues { !it.isNaN() }.map { (k, v) ->
            val at = m.releasedAt[k] ?: 0L
            val ageD = if (at > 0) (now - at) / 86_400_000.0 else Double.NaN
            val max = MACRO_MAX_AGE_DAYS[k] ?: 60.0
            val st = when { at <= 0 -> FeedStatus.DEGRADED; ageD > max -> FeedStatus.STALE; else -> FeedStatus.MANUAL }
            FeedQuality("Macro: $k", st, m.source, at, ageD * 86400, false,
                "%s = %s%s".format(k, v, if (at <= 0) " (undated)" else " · ${"%.0f".format(ageD)}d old, max ${max.toInt()}d"))
        }
        feeds += macroRows

        // ---- score & breaker
        val macroFactor = if (macroRows.isEmpty()) 0.5 else macroRows.map { factor(it.status) }.average()
        var num = 0.0; var den = 0.0
        for ((name, w) in WEIGHTS) {
            val fct = if (name == "Macro") macroFactor else feeds.firstOrNull { it.name == name }?.let { factor(it.status) } ?: 0.0
            num += w * fct; den += w
        }
        for (fq in feeds.filter { it.critical }) {
            if (fq.status == FeedStatus.MISSING || fq.status == FeedStatus.STALE || fq.status == FeedStatus.INVALID)
                breaker += "${fq.name} ${fq.status.name.lowercase()}${if (fq.detail.isNotBlank()) ": ${fq.detail}" else ""}"
            else if (fq.status == FeedStatus.DEGRADED) warnings += "${fq.name} degraded: ${fq.detail}"
        }
        if (!open) warnings += "Market closed — ages measured against the last session's data (${Session.zdt(ref).toLocalDate()} ${Session.hhmm(ref)})"
        return DataQualityReport(feeds, num / den, breaker, warnings)
    }

    /** Remove stale/invalid inputs from the live drivers and down-weight degraded ones. */
    fun gate(signal: EngineSignal, report: DataQualityReport): EngineSignal {
        val feed = SIGNALS_BY_FEED.entries.firstOrNull { signal.name in it.value }?.key ?: return signal
        val st = report.feeds.firstOrNull { it.name == feed }?.status ?: return signal
        val f = when (st) {
            FeedStatus.LIVE, FeedStatus.MANUAL -> 1.0
            FeedStatus.DEGRADED -> 0.6
            else -> 0.0
        }
        if (f == 1.0) return signal
        return signal.copy(confidence = signal.confidence * f, tags = signal.tags + "DATA_${st.name}")
    }

    private fun fmtAge(sec: Double) = when {
        sec < 120 -> "%.0fs".format(sec); sec < 7200 -> "%.0fm".format(sec / 60); sec < 172800 -> "%.1fh".format(sec / 3600)
        else -> "%.1fd".format(sec / 86400)
    }
}

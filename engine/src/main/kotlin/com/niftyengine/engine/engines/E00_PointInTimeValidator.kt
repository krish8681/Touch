package com.niftyengine.engine.engines

import com.niftyengine.engine.core.Session
import com.niftyengine.engine.model.InstrumentData
import com.niftyengine.engine.model.MarketSnapshot
import com.niftyengine.engine.model.PitViolation
import com.niftyengine.engine.model.PointInTimeReport

/**
 * 00 — Point-in-time validator (v5.1). Runs before the entire prediction pipeline.
 *
 * Every input must satisfy  dataTimestamp ≤ decisionTimestamp  (the snapshot's own timestamp, i.e. the moment it was
 * completed). A small clock-skew tolerance is accepted and reported; beyond it the input is REJECTED, never treated as
 * fresh:
 *
 *  • point quotes — NIFTY, Bank Nifty, India VIX, constituents, sectors, global assets (`InstrumentData.asOf`), futures
 *    (`FuturesData.asOf`), option chain (`OptionChain.asOf`), GIFT Nifty, FII/DII flows, macro releases: a future-dated
 *    non-critical input is removed; a future-dated CRITICAL input (NIFTY, futures, option chain) is reported as critical
 *    and the data-quality gate turns the cycle into DATA ERROR — NO TRADE;
 *  • time series — intraday and daily candles of every instrument, futures OI bars, news, event analyses: items stamped
 *    after the decision time are dropped (and reported).
 */
class PointInTimeValidator(
    private val toleranceMs: Long = 10_000L,
    private val critical: Set<String> = setOf("NIFTY", "Futures", "Options"),
) {
    data class Result(val snapshot: MarketSnapshot, val report: PointInTimeReport)

    fun validate(s: MarketSnapshot): Result {
        val t = s.timestamp
        val limit = t + toleranceMs
        val v = ArrayList<PitViolation>()
        var maxSkew = 0.0
        fun ahead(asOf: Long) = (asOf - t) / 1000.0
        /** true ⇒ the quote is acceptable (unknown timestamps are judged by the data-quality engine, not here). */
        fun ok(name: String, asOf: Long): Boolean {
            if (asOf <= 0 || asOf <= t) return true
            if (asOf <= limit) { maxSkew = maxOf(maxSkew, ahead(asOf)); return true }
            val crit = name in critical
            v += PitViolation(name, asOf, ahead(asOf), crit, if (crit) "critical — NO TRADE" else "rejected")
            return false
        }
        fun series(d: InstrumentData, name: String): InstrumentData {
            val futIntra = d.intraday.count { it.t > t }
            val futDaily = d.daily.count { it.t > t }
            if (futIntra + futDaily == 0) return d
            val first = (d.intraday.filter { it.t > t } + d.daily.filter { it.t > t }).minOf { it.t }
            v += PitViolation("$name bars", first, ahead(first), false, "dropped ${futIntra + futDaily} future bar(s)")
            return d.copy(intraday = d.intraday.filter { it.t <= t }, daily = d.daily.filter { it.t <= t })
        }

        // ---- critical quotes: kept (so the gate can name them) but flagged; their bars are cleaned
        ok("NIFTY", s.nifty.asOf)
        val nifty = series(s.nifty, "NIFTY")
        val futures = s.futures?.let { f ->
            if (!ok("Futures", f.asOf)) null
            else {
                val fut = f.intraday.count { it.t > t }
                if (fut > 0) v += PitViolation("Futures OI bars", f.intraday.filter { it.t > t }.minOf { it.t },
                    ahead(f.intraday.filter { it.t > t }.minOf { it.t }), false, "dropped $fut future bar(s)")
                if (fut > 0) f.copy(intraday = f.intraday.filter { it.t <= t }) else f
            }
        }
        val chain = s.optionChain?.takeIf { ok("Options", it.asOf) }

        // ---- non-critical quotes: a future-dated one is removed
        fun inst(d: InstrumentData?, name: String): InstrumentData? = d?.takeIf { ok(name, it.asOf) }?.let { series(it, name) }
        val bank = inst(s.bankNifty, "Bank Nifty")
        val vix = inst(s.vix, "India VIX")
        val constituents = s.constituents.mapNotNull { (k, d) -> inst(d, "Stock $k")?.let { k to it } }.toMap()
        val sectors = s.sectors.mapNotNull { (k, d) -> inst(d, "Sector ${k.label}")?.let { k to it } }.toMap()
        val global = s.global.mapNotNull { (k, d) -> inst(d, k.label)?.let { k to it } }.toMap()
        val gift = s.giftNifty?.takeIf { ok("GIFT Nifty", it.asOf) }
        val flows = s.flows?.takeIf { ok("FPI/DII", it.asOf) }

        // ---- macro: a value "released" after the decision time did not exist yet
        val m = s.macro
        val futureMacro = m.releasedAt.filter { (_, at) -> at > limit }
        futureMacro.forEach { (k, at) -> v += PitViolation("Macro: $k", at, ahead(at), false, "rejected") }
        val macro = if (futureMacro.isEmpty()) m else m.copy(
            repoRate = if ("repoRate" in futureMacro) Double.NaN else m.repoRate,
            cpiYoY = if ("cpiYoY" in futureMacro) Double.NaN else m.cpiYoY,
            gdpGrowth = if ("gdpGrowth" in futureMacro) Double.NaN else m.gdpGrowth,
            pmiManufacturing = if ("pmiManufacturing" in futureMacro) Double.NaN else m.pmiManufacturing,
            iipYoY = if ("iipYoY" in futureMacro) Double.NaN else m.iipYoY,
            creditGrowth = if ("creditGrowth" in futureMacro) Double.NaN else m.creditGrowth,
            liquidityCr = if ("liquidityCr" in futureMacro) Double.NaN else m.liquidityCr,
            releasedAt = m.releasedAt - futureMacro.keys,
        )

        // ---- news and analyses
        val futNews = s.news.filter { it.publishedAt > t }
        if (futNews.isNotEmpty()) v += PitViolation("News", futNews.minOf { it.publishedAt }, ahead(futNews.minOf { it.publishedAt }), false,
            "dropped ${futNews.size} future article(s)")
        val futAn = s.eventAnalyses.filter { it.analyzedAt > t }
        if (futAn.isNotEmpty()) v += PitViolation("Event analyses", futAn.minOf { it.analyzedAt }, ahead(futAn.minOf { it.analyzedAt }), false,
            "dropped ${futAn.size} future analysis(es)")

        val clean = if (v.isEmpty()) s else s.copy(
            nifty = nifty, bankNifty = bank, vix = vix, futures = futures, optionChain = chain,
            constituents = constituents, sectors = sectors, global = global, giftNifty = gift, flows = flows, macro = macro,
            news = if (futNews.isEmpty()) s.news else s.news - futNews.toSet(),
            eventAnalyses = if (futAn.isEmpty()) s.eventAnalyses else s.eventAnalyses - futAn.toSet(),
        )
        return Result(clean, PointInTimeReport(t, toleranceMs / 1000.0, v, maxSkew))
    }

    companion object {
        fun describe(v: PitViolation): String =
            "%s stamped %s — %.0fs after the decision time (%s)".format(v.input, Session.hhmm(v.asOf), v.aheadSec, v.action)
    }
}

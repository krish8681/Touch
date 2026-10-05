package com.niftyengine.engine.engines

import com.niftyengine.engine.core.Composite
import com.niftyengine.engine.core.Composite.Part
import com.niftyengine.engine.core.Fresh
import com.niftyengine.engine.core.M
import com.niftyengine.engine.core.Session
import com.niftyengine.engine.model.Detail
import com.niftyengine.engine.model.EngineSignal
import com.niftyengine.engine.model.Factor
import com.niftyengine.engine.model.FactorReading
import com.niftyengine.engine.model.HeavyweightReport
import com.niftyengine.engine.model.HeavyweightRow
import com.niftyengine.engine.model.HorizonId
import com.niftyengine.engine.model.LeadershipRow
import com.niftyengine.engine.model.MarketSnapshot
import com.niftyengine.engine.model.Sector
import kotlin.math.abs

/**
 * Approximate NIFTY 50 free-float weights. If the feed publishes free-float market caps (NSE `ffmc`) live weights are
 * derived from them instead (refresh these from the NSE monthly factsheet).
 */
object Constituents {
    data class C(val symbol: String, val sector: Sector, val weight: Double)

    /** Approximate free-float weights (%). Symbols are NSE trading symbols. */
    val DEFAULT: List<C> = listOf(
        C("HDFCBANK", Sector.BANK, 13.0), C("ICICIBANK", Sector.BANK, 8.9), C("RELIANCE", Sector.ENERGY, 8.6),
        C("INFY", Sector.IT, 4.8), C("BHARTIARTL", Sector.TELECOM, 4.7), C("LT", Sector.INFRA, 3.9),
        C("ITC", Sector.FMCG, 3.3), C("TCS", Sector.IT, 2.9), C("AXISBANK", Sector.BANK, 3.0),
        C("KOTAKBANK", Sector.BANK, 2.9), C("SBIN", Sector.BANK, 3.0), C("M&M", Sector.AUTO, 2.5),
        C("BAJFINANCE", Sector.FIN_SERVICES, 2.3), C("HINDUNILVR", Sector.FMCG, 1.9), C("SUNPHARMA", Sector.PHARMA, 1.6),
        C("HCLTECH", Sector.IT, 1.5), C("NTPC", Sector.ENERGY, 1.4), C("MARUTI", Sector.AUTO, 1.5),
        C("ULTRACEMCO", Sector.INFRA, 1.3), C("TITAN", Sector.CONSUMER, 1.3), C("ETERNAL", Sector.CONSUMER, 1.3),
        C("POWERGRID", Sector.ENERGY, 1.1), C("TATASTEEL", Sector.METALS, 1.1), C("BAJAJFINSV", Sector.FIN_SERVICES, 1.0),
        C("ADANIPORTS", Sector.INFRA, 1.0), C("BEL", Sector.INFRA, 1.1), C("TMPV", Sector.AUTO, 0.9),
        C("ONGC", Sector.ENERGY, 0.9), C("ASIANPAINT", Sector.CONSUMER, 0.9), C("JSWSTEEL", Sector.METALS, 0.9),
        C("HINDALCO", Sector.METALS, 0.9), C("COALINDIA", Sector.ENERGY, 0.8), C("TRENT", Sector.CONSUMER, 0.9),
        C("GRASIM", Sector.INFRA, 0.8), C("SHRIRAMFIN", Sector.FIN_SERVICES, 0.8), C("TECHM", Sector.IT, 0.8),
        C("JIOFIN", Sector.FIN_SERVICES, 0.8), C("INDIGO", Sector.CONSUMER, 0.9), C("HDFCLIFE", Sector.FIN_SERVICES, 0.7),
        C("NESTLEIND", Sector.FMCG, 0.7), C("BAJAJ-AUTO", Sector.AUTO, 0.8), C("SBILIFE", Sector.FIN_SERVICES, 0.7),
        C("EICHERMOT", Sector.AUTO, 0.7), C("WIPRO", Sector.IT, 0.6), C("CIPLA", Sector.PHARMA, 0.6),
        C("DRREDDY", Sector.PHARMA, 0.6), C("APOLLOHOSP", Sector.PHARMA, 0.6), C("MAXHEALTH", Sector.PHARMA, 0.6),
        C("TATACONSUM", Sector.FMCG, 0.6), C("ADANIENT", Sector.METALS, 0.6),
    )

    val bySymbol: Map<String, C> = DEFAULT.associateBy { it.symbol }

    fun sectorOf(symbol: String): Sector = bySymbol[symbol]?.sector ?: Sector.OTHER
}

/** Leadership groups reported by the heavyweight engine (§15). */
object Leadership {
    fun groupOf(s: Sector): String = when (s) {
        Sector.BANK, Sector.FIN_SERVICES -> "Banking & financials"
        Sector.IT -> "IT"; Sector.ENERGY -> "Energy"; Sector.AUTO -> "Auto"; Sector.PHARMA -> "Pharma"
        Sector.FMCG -> "FMCG"; Sector.TELECOM -> "Telecom"; Sector.INFRA -> "Capital goods / infra"
        Sector.METALS -> "Metals"; Sector.CONSUMER -> "Consumer"; Sector.OTHER -> "Other"
    }
}

/**
 * 03 — NIFTY Heavyweight Engine (§15).
 *
 * NIFTY is free-float weighted, so a stock's contribution = its return × its index weight, and
 * Heavyweight contribution = Σ(weight × stock return). Live weights come from NSE free-float caps when available.
 * Besides the total, the engine reports which groups lead (banking, IT, energy, auto, pharma, FMCG, telecom, capital
 * goods …), the last 30/60 minutes' contribution, and whether only a few stocks carry the index — so the model does not
 * call the market "bullish" when three stocks are doing all the work.
 */
class NiftyWeightEngine(private val norm: DataNormalizer? = null) {
    data class Result(
        val signal: EngineSignal,
        val report: HeavyweightReport,
        val contributionBySector: Map<Sector, Double>,
        val readings: Map<HorizonId, FactorReading>,
    )

    fun analyze(s: MarketSnapshot, now: Long = s.timestamp, sessionStart: Long = Session.sessionStart(now), ref: Long = now, reliability: Double = 0.9): Result {
        val prevClose = s.nifty.prevClose.takeIf { it > 0 } ?: s.nifty.last
        val stocks = s.constituents.filterValues { it.prevClose > 0 && it.last > 0 }
        if (stocks.size < 10) {
            val empty = HeavyweightReport(emptyList(), 0.0, 0.0, 0.0, 0, 0, 0.0, false, "none")
            val miss = HorizonId.values().filter { it.intraday }.associateWith { FactorReading.missing(Factor.HEAVYWEIGHTS, "constituent quotes unavailable") }
            return Result(EngineSignal.unavailable("Heavyweights", "constituent quotes unavailable"), empty, emptyMap(), miss)
        }
        val ffmcAvailable = stocks.values.count { !it.freeFloatMcap.isNaN() && it.freeFloatMcap > 0 } >= stocks.size * 0.8
        val rawWeights: Map<String, Double> = if (ffmcAvailable) {
            stocks.mapValues { (_, d) -> if (d.freeFloatMcap.isNaN()) 0.0 else d.freeFloatMcap }
        } else {
            stocks.mapValues { (sym, _) -> Constituents.bySymbol[sym]?.weight ?: 0.3 }
        }
        val total = rawWeights.values.sum().takeIf { it > 0 } ?: 1.0
        val weights = rawWeights.mapValues { it.value / total }
        val rows = stocks.map { (sym, d) ->
            val w = weights.getValue(sym)
            HeavyweightRow(sym, Constituents.sectorOf(sym), w * 100, d.changePct, w * d.changePct / 100.0 * prevClose)
        }.sortedByDescending { it.weightPct }

        val totalPts = rows.sumOf { it.contributionPts }
        val top5 = rows.take(5).sumOf { it.contributionPts }
        val top10 = rows.take(10).sumOf { it.contributionPts }
        val adv = rows.count { it.changePct > 0 }
        val dec = rows.count { it.changePct < 0 }
        val dependence = if (abs(totalPts) < 1e-6) 0.0 else M.clamp(top5 / totalPts, -2.0, 2.0)
        val idxSign = DataNormalizer.sign(totalPts, prevClose * 0.0015) // ignore index moves < 0.15%
        val breadthSign = DataNormalizer.sign((adv - dec).toDouble())
        // Index moving one way while most stocks go the other way, propped by a few heavyweights.
        val fakeBreadth = idxSign != 0 && breadthSign != 0 && idxSign != breadthSign && abs(dependence) > 0.6
        val top3Share = rows.map { abs(it.contributionPts) }.sortedDescending().let { l ->
            val sum = l.sum(); if (sum < 1e-6) 0.0 else l.take(3).sum() / sum
        }
        val narrow = abs(totalPts) > prevClose * 0.002 && top3Share >= 0.7
        val bySector = rows.groupBy { it.sector }.mapValues { (_, r) -> r.sumOf { it.contributionPts } }
        val leadership = rows.groupBy { Leadership.groupOf(it.sector) }.map { (g, r) ->
            val wPct = r.sumOf { it.weightPct }
            LeadershipRow(g, r.sumOf { it.contributionPts }, if (wPct > 0) r.sumOf { it.weightPct * it.changePct } / wPct else 0.0, wPct)
        }.sortedByDescending { abs(it.contributionPts) }

        // Contribution over the last 30 / 60 minutes (needs intraday bars or the engine's own tick history).
        fun windowPts(minutes: Int): Double {
            val nz = norm ?: return Double.NaN
            var num = 0.0; var cover = 0.0
            for ((sym, d) in stocks) {
                val f = nz.features(d, now, sessionStart)
                val c = if (minutes == 30) f.c30m else f.c1h
                if (c.isNaN()) continue
                val w = weights.getValue(sym)
                num += w * c / 100 * prevClose; cover += w
            }
            return if (cover < 0.6) Double.NaN else num / cover
        }
        val pts30 = windowPts(30); val pts60 = windowPts(60)

        val pctDay = totalPts / prevClose * 100
        fun sq(pts: Double, scale: Double) = if (pts.isNaN()) Double.NaN else M.squash(pts / prevClose * 100, scale)
        val day = M.squash(pctDay, 0.6)
        val s30 = sq(pts30, 0.15); val s60 = sq(pts60, 0.25)
        val top10Adv = rows.take(10).let { l -> (l.count { it.changePct > 0 } - l.count { it.changePct < 0 }).toDouble() / l.size }
        val asOfs = stocks.values.map { it.asOf }.filter { it > 0 }.sorted()
        val asOf = if (asOfs.isEmpty()) 0L else asOfs[asOfs.size / 2]
        val ageSec = if (asOf > 0) ((ref - asOf) / 1000.0).coerceAtLeast(0.0) else Double.NaN
        val rel = reliability * (if (ffmcAvailable) 1.0 else 0.85) * (if (fakeBreadth || narrow) 0.8 else 1.0)
        fun p(n: String, v: Double, w: Double, t: String = "") = Part(n, v, w, t)
        val readings = mapOf(
            HorizonId.M30 to listOf(p("Last 30m", s30, 0.5, "%+.1f pts".format(pts30)), p("Day", day, 0.3, "%+.1f pts".format(totalPts)), p("Top-10 breadth", top10Adv, 0.2)),
            HorizonId.M60 to listOf(p("Last 30m", s30, 0.3, "%+.1f pts".format(pts30)), p("Last 60m", s60, 0.3, "%+.1f pts".format(pts60)), p("Day", day, 0.25, "%+.1f pts".format(totalPts)), p("Top-10 breadth", top10Adv, 0.15)),
            HorizonId.M180 to listOf(p("Last 60m", s60, 0.35, "%+.1f pts".format(pts60)), p("Day", day, 0.5, "%+.1f pts".format(totalPts)), p("Top-10 breadth", top10Adv, 0.15)),
            HorizonId.CLOSE to listOf(p("Last 60m", s60, 0.25, "%+.1f pts".format(pts60)), p("Day", day, 0.6, "%+.1f pts".format(totalPts)), p("Top-10 breadth", top10Adv, 0.15)),
        ).mapValues { (h, parts) ->
            val lead = leadership.take(2).joinToString { "${it.group} %+.0f".format(it.contributionPts) }
            Composite.reading(Factor.HEAVYWEIGHTS, parts, Fresh.of(ageSec, Fresh.Cadence.LIVE, h), rel, asOf, ageSec, s.source,
                summary = { "%+.0f pts · %s%s".format(totalPts, lead, if (narrow) " · NARROW" else if (fakeBreadth) " · fake breadth" else "") })
        }

        var score = M.squash(pctDay, 0.6)
        val tags = mutableListOf<String>()
        if (idxSign > 0) tags += "INDEX_UP" else if (idxSign < 0) tags += "INDEX_DOWN"
        if (fakeBreadth) {
            tags += "BREADTH_WEAK"; tags += "HEAVYWEIGHT_DEPENDENCE_HIGH"
            score *= 0.5
        } else if (abs(dependence) > 0.8 && abs(totalPts) > 10) tags += "HEAVYWEIGHT_LED"
        if (narrow) tags += "NARROW_LEADERSHIP"
        leadership.firstOrNull()?.takeIf { abs(it.contributionPts) > 3 }?.let { tags += "LEADER_" + it.group.uppercase().replace(Regex("[^A-Z]+"), "_").trim('_') }
        val leaders = rows.sortedByDescending { abs(it.contributionPts) }.take(3)
        return Result(
            EngineSignal(
                "Heavyweights", score, if (ffmcAvailable) 0.95 else 0.8, tags,
                listOf(
                    Detail("Contribution", "%+.1f pts (30m %+.1f · 60m %+.1f)".format(totalPts, pts30, pts60)),
                    Detail("Top 5 / Top 10", "%+.1f / %+.1f pts".format(top5, top10)),
                    Detail("Adv / Dec", "$adv / $dec"),
                    Detail("Top-5 dependence", "%.0f%%".format(dependence * 100)),
                    Detail("Top-3 share of move", "%.0f%%".format(top3Share * 100)),
                    Detail("Leaders", leaders.joinToString { "${it.symbol} %+.1f".format(it.contributionPts) }),
                    Detail("Leadership", leadership.take(4).joinToString { "${it.group} %+.0f".format(it.contributionPts) }),
                ),
            ),
            HeavyweightReport(rows, totalPts, top5, top10, adv, dec, dependence, fakeBreadth,
                if (ffmcAvailable) "live free-float mcap" else "approximate static weights", leadership, pts30, pts60, narrow),
            bySector, readings,
        )
    }
}

package com.niftyengine.engine.engines

import com.niftyengine.engine.core.M
import com.niftyengine.engine.model.Detail
import com.niftyengine.engine.model.EngineSignal
import com.niftyengine.engine.model.HeavyweightReport
import com.niftyengine.engine.model.HeavyweightRow
import com.niftyengine.engine.model.MarketSnapshot
import com.niftyengine.engine.model.Sector
import kotlin.math.abs

/**
 * 03 — NIFTY Weight / Heavyweight Contribution Engine.
 *
 * NIFTY is free-float weighted, so contribution = stock return × index weight.
 * If the feed publishes free-float market caps (NSE `ffmc`) the live weights are derived from them;
 * otherwise [Constituents.DEFAULT] (approximate weights, refresh from the NSE monthly factsheet) is used.
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

class NiftyWeightEngine {
    data class Result(val signal: EngineSignal, val report: HeavyweightReport, val contributionBySector: Map<Sector, Double>)

    fun analyze(s: MarketSnapshot): Result {
        val prevClose = s.nifty.prevClose.takeIf { it > 0 } ?: s.nifty.last
        val stocks = s.constituents.filterValues { it.prevClose > 0 && it.last > 0 }
        if (stocks.size < 10) {
            val empty = HeavyweightReport(emptyList(), 0.0, 0.0, 0.0, 0, 0, 0.0, false, "none")
            return Result(EngineSignal.unavailable("Heavyweights", "constituent quotes unavailable"), empty, emptyMap())
        }
        val ffmcAvailable = stocks.values.count { !it.freeFloatMcap.isNaN() && it.freeFloatMcap > 0 } >= stocks.size * 0.8
        val rawWeights: Map<String, Double> = if (ffmcAvailable) {
            stocks.mapValues { (_, d) -> if (d.freeFloatMcap.isNaN()) 0.0 else d.freeFloatMcap }
        } else {
            stocks.mapValues { (sym, _) -> Constituents.bySymbol[sym]?.weight ?: 0.3 }
        }
        val total = rawWeights.values.sum().takeIf { it > 0 } ?: 1.0
        val rows = stocks.map { (sym, d) ->
            val w = rawWeights.getValue(sym) / total
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
        val bySector = rows.groupBy { it.sector }.mapValues { (_, r) -> r.sumOf { it.contributionPts } }

        val pctMove = totalPts / prevClose * 100
        var score = M.squash(pctMove, 0.6)
        val tags = mutableListOf<String>()
        if (idxSign > 0) tags += "INDEX_UP" else if (idxSign < 0) tags += "INDEX_DOWN"
        if (fakeBreadth) {
            tags += "BREADTH_WEAK"; tags += "HEAVYWEIGHT_DEPENDENCE_HIGH"
            score *= 0.5
        } else if (abs(dependence) > 0.8 && abs(totalPts) > 10) tags += "HEAVYWEIGHT_LED"
        val leaders = rows.sortedByDescending { abs(it.contributionPts) }.take(3)
        return Result(
            EngineSignal(
                "Heavyweights", score, if (ffmcAvailable) 0.95 else 0.8, tags,
                listOf(
                    Detail("Contribution", "%+.1f pts".format(totalPts)),
                    Detail("Top 5 / Top 10", "%+.1f / %+.1f pts".format(top5, top10)),
                    Detail("Adv / Dec", "$adv / $dec"),
                    Detail("Top-5 dependence", "%.0f%%".format(dependence * 100)),
                    Detail("Leaders", leaders.joinToString { "${it.symbol} %+.1f".format(it.contributionPts) }),
                ),
            ),
            HeavyweightReport(rows, totalPts, top5, top10, adv, dec, dependence, fakeBreadth,
                if (ffmcAvailable) "live free-float mcap" else "approximate static weights"),
            bySector,
        )
    }
}

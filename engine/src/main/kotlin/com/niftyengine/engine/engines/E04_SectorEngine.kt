package com.niftyengine.engine.engines

import com.niftyengine.engine.core.Composite
import com.niftyengine.engine.core.Fresh
import com.niftyengine.engine.core.M
import com.niftyengine.engine.model.Detail
import com.niftyengine.engine.model.EngineSignal
import com.niftyengine.engine.model.Factor
import com.niftyengine.engine.model.FactorReading
import com.niftyengine.engine.model.HeavyweightReport
import com.niftyengine.engine.model.HorizonId
import com.niftyengine.engine.model.InstrumentData
import com.niftyengine.engine.model.MarketSnapshot
import com.niftyengine.engine.model.Sector
import com.niftyengine.engine.model.SectorRow
import kotlin.math.abs
import kotlin.math.sign

/**
 * 04 — Sector Engine.
 * Per sector: return, momentum, internal breadth, relative strength vs NIFTY and NIFTY contribution;
 * then SECTOR_ALIGNMENT = how broadly sectors participate in the index move.
 */
class SectorEngine(private val norm: DataNormalizer) {
    data class Result(val signal: EngineSignal, val rows: List<SectorRow>, val alignment: Double)

    fun analyze(s: MarketSnapshot, contribution: Map<Sector, Double>, now: Long, sessionStart: Long): Result {
        val niftyChg = s.nifty.changePct
        val stocksBySector = s.constituents.entries.groupBy { Constituents.sectorOf(it.key) }
        val sectors = (s.sectors.keys + stocksBySector.keys).filter { it != Sector.OTHER }.toSet()
        if (sectors.isEmpty()) return Result(EngineSignal.unavailable("Sectors", "no sector data"), emptyList(), 0.0)

        val rows = sectors.map { sec ->
            val idx = s.sectors[sec]
            val members = stocksBySector[sec].orEmpty().map { it.value }
            val chg = idx?.changePct ?: members.map { it.changePct }.let { if (it.isEmpty()) 0.0 else it.average() }
            val momentum = idx?.let { norm.recentMove(norm.features(it, now, sessionStart)) }
                ?: DataNormalizer.nz(members.map { norm.recentMove(norm.features(it, now, sessionStart)) }.let { if (it.isEmpty()) 0.0 else it.average() })
            val breadth = if (members.isEmpty()) sign(chg) else
                (members.count { it.changePct > 0 } - members.count { it.changePct < 0 }).toDouble() / members.size
            SectorRow(sec, chg, momentum, breadth, chg - niftyChg, contribution[sec] ?: 0.0,
                change5dPct = idx?.let { multiDay(it, 5) } ?: Double.NaN, change20dPct = idx?.let { multiDay(it, 20) } ?: Double.NaN)
        }.sortedByDescending { abs(it.contributionPts) + abs(it.changePct) }

        // Alignment: share of sectors (weighted by |contribution|+small floor) moving with the index.
        val dir = sign(niftyChg)
        val wts = rows.map { abs(it.contributionPts) + 1.0 }
        val aligned = rows.zip(wts).sumOf { (r, w) -> if (sign(r.changePct) == dir && dir != 0.0) w else 0.0 }
        val alignment = if (wts.sum() > 0) aligned / wts.sum() else 0.0

        val totalContribPct = rows.sumOf { it.contributionPts } / (s.nifty.prevClose.takeIf { it > 0 } ?: 1.0) * 100
        val avgBreadth = rows.map { it.breadth }.average()
        val avgMomentum = rows.map { it.momentum }.average()
        val raw = 0.45 * M.squash(totalContribPct, 0.6) + 0.30 * avgBreadth + 0.25 * M.squash(avgMomentum, 0.25)
        val score = M.clamp(raw * (0.6 + 0.4 * alignment))
        val tags = buildList {
            if (alignment > 0.75) add("BROAD_PARTICIPATION")
            if (alignment < 0.45 && dir != 0.0) add("NARROW_PARTICIPATION")
            rows.firstOrNull()?.let { add("LEADER_${it.sector.name}") }
        }
        return Result(
            EngineSignal("Sectors", score, if (s.sectors.isNotEmpty()) 0.9 else 0.7, tags,
                listOf(
                    Detail("Alignment", "%.0f%%".format(alignment * 100)),
                    Detail("Top", rows.take(3).joinToString { "${it.sector.label} %+.2f%%".format(it.changePct) }),
                    Detail("Weakest", rows.sortedBy { it.changePct }.take(2).joinToString { "${it.sector.label} %+.2f%%".format(it.changePct) }),
                )),
            rows, alignment,
        )
    }

    /** Change of [d]'s live price vs the close [sessions] sessions ago (needs daily history). */
    private fun multiDay(d: InstrumentData, sessions: Int): Double {
        val dl = d.daily
        if (dl.size < sessions || d.last <= 0) return Double.NaN
        return M.pctChange(dl[dl.size - sessions].c, d.last)
    }

    /**
     * H2 "Sector leadership" (§8): weight-share-weighted 5-day sector returns, how many sectors participate, and whether
     * the heavyweight financials lead. Falls back to today's sector moves (lower freshness) without daily history.
     */
    fun weeklyLeadership(s: MarketSnapshot, rows: List<SectorRow>, hw: HeavyweightReport, ref: Long, reliability: Double): FactorReading {
        if (rows.isEmpty()) return FactorReading.missing(Factor.SECTOR_LEADERSHIP, "no sector data")
        val share = hw.rows.groupBy { it.sector }.mapValues { (_, r) -> r.sumOf { it.weightPct } }
            .ifEmpty { Constituents.DEFAULT.groupBy { it.sector }.mapValues { (_, r) -> r.sumOf { it.weight } } }
        val withHist = rows.filter { !it.change5dPct.isNaN() }
        val daily = withHist.size >= 4
        val used = if (daily) withHist else rows
        fun ret(r: SectorRow) = if (daily) r.change5dPct else r.changePct
        val wsum = used.sumOf { share[it.sector] ?: 0.5 }
        val weighted = used.sumOf { (share[it.sector] ?: 0.5) * ret(it) } / wsum.coerceAtLeast(1e-9)
        val breadth = (used.count { ret(it) > 0 } - used.count { ret(it) < 0 }).toDouble() / used.size
        val fin = used.filter { it.sector == Sector.BANK || it.sector == Sector.FIN_SERVICES }.map { ret(it) }
        val scale = if (daily) 1.5 else 0.6
        val parts = listOf(
            Composite.Part("Weighted sector return", M.squash(weighted, scale), 0.5, "%+.2f%%".format(weighted)),
            Composite.Part("Sector participation", breadth, 0.3, "${used.count { ret(it) > 0 }}/${used.size} up"),
            Composite.Part("Financials leadership", if (fin.isEmpty()) Double.NaN else M.squash(fin.average(), scale), 0.2,
                if (fin.isEmpty()) "" else "%+.2f%%".format(fin.average())),
        )
        val asOf = s.sectors.values.maxOfOrNull { it.asOf } ?: 0L
        val age = if (asOf > 0) ((ref - asOf) / 1000.0).coerceAtLeast(0.0) else Double.NaN
        val fresh = Fresh.of(age, Fresh.Cadence.LIVE, HorizonId.WEEKLY) * if (daily) 1.0 else 0.6
        val top = used.sortedByDescending { ret(it) }
        return Composite.reading(Factor.SECTOR_LEADERSHIP, parts, fresh, reliability * if (daily) 1.0 else 0.7, asOf, age, s.source,
            summary = { "${if (daily) "5-day" else "today (no daily history)"}: lead ${top.firstOrNull()?.sector?.label ?: "–"} %+.1f%%, lag ${top.lastOrNull()?.sector?.label ?: "–"} %+.1f%%"
                .format(top.firstOrNull()?.let(::ret) ?: 0.0, top.lastOrNull()?.let(::ret) ?: 0.0) })
    }
}

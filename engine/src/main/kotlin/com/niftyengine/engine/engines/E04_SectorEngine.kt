package com.niftyengine.engine.engines

import com.niftyengine.engine.core.M
import com.niftyengine.engine.model.Detail
import com.niftyengine.engine.model.EngineSignal
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
            SectorRow(sec, chg, momentum, breadth, chg - niftyChg, contribution[sec] ?: 0.0)
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
}

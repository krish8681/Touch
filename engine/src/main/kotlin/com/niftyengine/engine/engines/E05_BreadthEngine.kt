package com.niftyengine.engine.engines

import com.niftyengine.engine.core.M
import com.niftyengine.engine.model.Detail
import com.niftyengine.engine.model.EngineSignal
import com.niftyengine.engine.model.HeavyweightReport
import com.niftyengine.engine.model.MarketSnapshot

/**
 * 05 — Breadth Engine.
 * Advance/decline, equal-weight vs cap-weight return (participation), % of stocks above today's open,
 * % of stocks with positive short-term momentum. Detects breadth that disagrees with the index.
 */
class BreadthEngine(private val norm: DataNormalizer) {
    fun analyze(s: MarketSnapshot, hw: HeavyweightReport, now: Long, sessionStart: Long): EngineSignal {
        val stocks = s.constituents.values.filter { it.prevClose > 0 }
        if (stocks.size < 10) return EngineSignal.unavailable("Breadth", "constituent quotes unavailable")
        val n = stocks.size.toDouble()
        val adv = stocks.count { it.changePct > 0 }
        val dec = stocks.count { it.changePct < 0 }
        val adScore = (adv - dec) / n
        val equalWeight = stocks.map { it.changePct }.average()
        val capWeight = s.nifty.changePct
        val aboveOpen = stocks.filter { !it.open.isNaN() && it.open > 0 }
            .let { l -> if (l.isEmpty()) Double.NaN else l.count { it.last > it.open }.toDouble() / l.size }
        val momentumUp = stocks.map { norm.features(it, now, sessionStart).c15m }.filter { !it.isNaN() }
            .let { l -> if (l.size < 10) Double.NaN else l.count { it > 0 }.toDouble() / l.size }

        val parts = mutableListOf(0.45 * adScore, 0.25 * M.squash(equalWeight, 0.6))
        var wsum = 0.70
        if (!aboveOpen.isNaN()) { parts += 0.15 * (aboveOpen * 2 - 1); wsum += 0.15 }
        if (!momentumUp.isNaN()) { parts += 0.15 * (momentumUp * 2 - 1); wsum += 0.15 }
        val score = M.clamp(parts.sum() / wsum)

        val tags = buildList {
            if (adScore > 0.4) add("BREADTH_STRONG") else if (adScore < -0.4) add("BREADTH_WEAK")
            if (capWeight - equalWeight > 0.35) add("CAP_WEIGHT_LEADING") // heavyweights doing the lifting
            if (equalWeight - capWeight > 0.35) add("BROAD_MARKET_LEADING")
            if (hw.fakeBreadth) add("FAKE_BREADTH")
        }
        return EngineSignal("Breadth", score, 0.85, tags, listOfNotNull(
            Detail("Adv / Dec", "$adv / $dec"),
            Detail("Equal-wt vs index", "%+.2f%% vs %+.2f%%".format(equalWeight, capWeight)),
            if (!aboveOpen.isNaN()) Detail("Above open", "%.0f%%".format(aboveOpen * 100)) else null,
            if (!momentumUp.isNaN()) Detail("15m momentum up", "%.0f%%".format(momentumUp * 100)) else null,
        ))
    }
}

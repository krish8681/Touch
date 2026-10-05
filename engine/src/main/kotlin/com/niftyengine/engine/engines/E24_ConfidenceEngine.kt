package com.niftyengine.engine.engines

import com.niftyengine.engine.core.M
import com.niftyengine.engine.model.ConfidenceLevel
import com.niftyengine.engine.model.Direction
import com.niftyengine.engine.model.EventRiskLevel
import com.niftyengine.engine.model.EventRiskReport
import com.niftyengine.engine.model.Factor
import com.niftyengine.engine.model.HorizonGroup
import com.niftyengine.engine.model.HorizonId
import com.niftyengine.engine.model.HorizonPrediction
import com.niftyengine.engine.model.MasterPrediction
import kotlin.math.abs
import kotlin.math.sign

/**
 * 24 — Confidence Engine (§21) and horizon alignment (§22).
 *
 * Probability says how likely a direction is; confidence says how much the evidence behind it can be trusted.
 *  • per horizon: factor agreement, data coverage (missing factors), the key cross-checks (FII, global, heavyweights /
 *    sector leadership, options structure) agreeing, score magnitude and bull-vs-bear separation, minus event risk;
 *    capped by the data-quality score.
 *  • master: H1/H2/H3 alignment (3/3 strong, 2/3 moderate, 1/3 conflicting) + the same cross-checks + horizon confidences.
 *    Any horizon that OPPOSES the master direction with ≥ 60 % forces LOW — high individual probabilities with strong
 *    disagreement are not a high-confidence situation.
 */
object ConfidenceEngine {
    private val CROSS: Map<HorizonGroup, List<Factor>> = mapOf(
        HorizonGroup.H1 to listOf(Factor.FII, Factor.FII_FUTURES, Factor.GLOBAL, Factor.HEAVYWEIGHTS, Factor.OPTIONS),
        HorizonGroup.H2 to listOf(Factor.FII, Factor.GLOBAL, Factor.SECTOR_LEADERSHIP, Factor.OPTIONS),
        HorizonGroup.H3 to listOf(Factor.FII, Factor.GLOBAL, Factor.EARNINGS, Factor.RBI_RATES),
    )

    fun level(v: Double) = when { v >= 0.65 -> ConfidenceLevel.HIGH; v >= 0.45 -> ConfidenceLevel.MEDIUM; else -> ConfidenceLevel.LOW }

    fun horizon(p: HorizonPrediction, risk: EventRiskLevel, qualityCap: Double): HorizonPrediction {
        val notes = ArrayList<String>()
        val s = sign(p.score)
        val effs = p.factors.filter { it.reading.available && abs(it.effective) > 1e-9 }
        val tot = effs.sumOf { abs(it.effective) }
        val agree = if (tot <= 0 || s == 0.0) 0.5 else effs.filter { sign(it.effective) == s }.sumOf { abs(it.effective) } / tot
        val agreeScore = M.clamp((agree - 0.5) * 2, 0.0, 1.0)
        val crossF = CROSS.getValue(p.id.group)
        val cross = p.factors.filter { it.factor in crossF && it.reading.available && abs(it.reading.direction) > 5 }
        val crossShare = if (cross.isEmpty() || s == 0.0) 0.5 else cross.count { sign(it.reading.direction) == s }.toDouble() / cross.size
        val magnitude = M.clamp(abs(p.score) / 35, 0.0, 1.0)
        val clarity = M.clamp(abs(p.bull - p.bear) / 0.3, 0.0, 1.0)
        var v = 0.25 * agreeScore + 0.20 * p.coverage + 0.20 * crossShare + 0.15 * magnitude + 0.20 * clarity - risk.penalty
        notes += "Factor agreement %.0f%%, coverage %.0f%%, cross-checks %s".format(agree * 100, p.coverage * 100,
            if (cross.isEmpty()) "n/a" else "${cross.count { sign(it.reading.direction) == s }}/${cross.size} agree")
        if (risk != EventRiskLevel.LOW) notes += "Event risk ${risk.label}: −%.0f pts".format(risk.penalty * 100)
        if (p.coverage < 0.7) notes += "Only %.0f%% of the factor weight had data".format(p.coverage * 100)
        if (v > qualityCap) { v = qualityCap; notes += "Capped at %.0f%% by data quality".format(qualityCap * 100) }
        v = M.clamp(v, 0.0, 1.0)
        return p.copy(confidence = level(v), confidenceValue = v, confidenceNotes = notes, eventRisk = risk)
    }

    private val H1_WEIGHTS = mapOf(HorizonId.M30 to 0.2, HorizonId.M60 to 0.25, HorizonId.M180 to 0.25, HorizonId.CLOSE to 0.3)

    fun groupDirection(hs: List<HorizonPrediction>, g: HorizonGroup): Pair<Direction, HorizonPrediction?> {
        if (g != HorizonGroup.H1) return hs.firstOrNull { it.id.group == g }.let { (it?.direction ?: Direction.NEUTRAL) to it }
        val h1 = hs.filter { it.id.intraday }
        if (h1.isEmpty()) return Direction.NEUTRAL to null
        val edge = h1.sumOf { (H1_WEIGHTS[it.id] ?: 0.25) * (it.bull - it.bear) } / h1.sumOf { H1_WEIGHTS[it.id] ?: 0.25 }
        val dir = when { edge > 0.05 -> Direction.BULLISH; edge < -0.05 -> Direction.BEARISH; else -> Direction.NEUTRAL }
        return dir to h1.firstOrNull { it.id == HorizonId.CLOSE }
    }

    private fun probOf(p: HorizonPrediction, d: Direction) = when (d) { Direction.BULLISH -> p.bull; Direction.BEARISH -> p.bear; Direction.NEUTRAL -> p.neutral }

    fun master(hs: List<HorizonPrediction>, risk: EventRiskReport, qualityCap: Double): MasterPrediction {
        val dirs = HorizonGroup.values().associateWith { groupDirection(hs, it).first }
        val bulls = dirs.values.count { it == Direction.BULLISH }; val bears = dirs.values.count { it == Direction.BEARISH }
        val weekly = hs.firstOrNull { it.id == HorizonId.WEEKLY }
        val master = when {
            bulls > bears -> Direction.BULLISH
            bears > bulls -> Direction.BEARISH
            else -> if (bulls == 0) Direction.NEUTRAL else weekly?.direction ?: Direction.NEUTRAL
        }
        val alignment = dirs.values.count { it == master }
        val label = when (alignment) { 3 -> "3/3 · strong alignment"; 2 -> "2/3 · moderate alignment"; else -> "$alignment/3 · conflicting" }
        // Group probabilities of the master direction (H1 = weighted average of its sub-horizons).
        val gp = HorizonGroup.values().associateWith { g ->
            val sel = hs.filter { it.id.group == g }
            if (sel.isEmpty()) Double.NaN else if (g == HorizonGroup.H1)
                sel.sumOf { (H1_WEIGHTS[it.id] ?: 0.25) * probOf(it, master) } / sel.sumOf { H1_WEIGHTS[it.id] ?: 0.25 }
            else probOf(sel.first(), master)
        }
        val gw = mapOf(HorizonGroup.H1 to 0.25, HorizonGroup.H2 to 0.45, HorizonGroup.H3 to 0.30)
        val okG = gp.filterValues { !it.isNaN() }
        val prob = if (okG.isEmpty()) Double.NaN else okG.entries.sumOf { (g, p) -> gw.getValue(g) * p } / okG.keys.sumOf { gw.getValue(it) }

        val notes = ArrayList<String>()
        val alignScore = when (alignment) { 3 -> 1.0; 2 -> 0.55; else -> 0.15 }
        val ms = master.sign.toDouble()
        val crossReadings = listOfNotNull(
            hs.firstOrNull { it.id == HorizonId.WEEKLY }?.factors?.firstOrNull { it.factor == Factor.FII },
            hs.firstOrNull { it.id == HorizonId.WEEKLY }?.factors?.firstOrNull { it.factor == Factor.GLOBAL },
            hs.firstOrNull { it.id == HorizonId.CLOSE }?.factors?.firstOrNull { it.factor == Factor.HEAVYWEIGHTS },
            hs.firstOrNull { it.id == HorizonId.WEEKLY }?.factors?.firstOrNull { it.factor == Factor.OPTIONS },
        ).filter { it.reading.available && abs(it.reading.direction) > 5 }
        val crossAgree = if (crossReadings.isEmpty() || ms == 0.0) 0.5 else crossReadings.count { sign(it.reading.direction) == ms }.toDouble() / crossReadings.size
        crossReadings.forEach { c -> notes += "${c.factor.label}: ${if (sign(c.reading.direction) == ms) "agrees" else "disagrees"} (%+.0f)".format(c.reading.direction) }
        val avgConf = hs.map { it.confidenceValue }.average()
        val avgCov = hs.map { it.coverage }.average()
        val r2 = risk.level(HorizonGroup.H2)
        var v = 0.35 * alignScore + 0.30 * crossAgree + 0.20 * avgConf + 0.15 * avgCov - r2.penalty
        if (r2 != EventRiskLevel.LOW) notes += "Event risk to weekly expiry: ${r2.label}"
        v = M.clamp(minOf(v, qualityCap), 0.0, 1.0)
        var lvl = level(v)
        val opposing = HorizonGroup.values().filter { g -> dirs.getValue(g).sign == -master.sign && master != Direction.NEUTRAL }
            .filter { g ->
                val sel = hs.filter { it.id.group == g }
                val p = if (g == HorizonGroup.H1) sel.sumOf { (H1_WEIGHTS[it.id] ?: 0.25) * probOf(it, dirs.getValue(g)) } / sel.sumOf { H1_WEIGHTS[it.id] ?: 0.25 }.coerceAtLeast(1e-9)
                else sel.firstOrNull()?.let { probOf(it, dirs.getValue(g)) } ?: 0.0
                p >= 0.6
            }
        if (opposing.isNotEmpty()) { lvl = ConfidenceLevel.LOW; notes += "LOW: ${opposing.joinToString { it.short }} strongly disagrees with the master direction" }
        else if (alignment <= 1) { lvl = ConfidenceLevel.LOW; notes += "LOW: horizons conflict" }
        else if (alignment < 3 && lvl == ConfidenceLevel.HIGH) { lvl = ConfidenceLevel.MEDIUM; notes += "HIGH needs 3/3 alignment" }
        return MasterPrediction(master, prob, weekly?.rangeLow ?: Double.NaN, weekly?.rangeHigh ?: Double.NaN, alignment, label,
            dirs, gp, lvl, v, notes, r2)
    }
}

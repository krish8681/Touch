package com.niftyengine.engine.engines

import com.niftyengine.engine.core.M
import com.niftyengine.engine.model.CalibrationInfo
import com.niftyengine.engine.model.DataQualityReport
import com.niftyengine.engine.model.FeedStatus
import com.niftyengine.engine.model.HealthComponent
import com.niftyengine.engine.model.HealthTier
import com.niftyengine.engine.model.HorizonProb
import com.niftyengine.engine.model.ModelHealth
import com.niftyengine.engine.model.OptionChain
import com.niftyengine.engine.model.RegimeAssessment
import com.niftyengine.engine.model.TrackedEvent
import kotlin.math.abs

/**
 * 29 — Model Health Gate (v5.1). One 0–100 score over what makes the output trustworthy right now:
 *
 *   data freshness 25 · data completeness 15 · regime stability 15 · calibration quality 20 · news reliability 10 · options quality 15
 *
 *   ≥ eligibleAt (75)  → eligible to trade
 *   ≥ shadowAt   (60)  → shadow only (PAPER TRADE at best)
 *   below               → no signal (NO TRADE)
 */
class ModelHealthEngine(private val eligibleAt: Double = 75.0, private val shadowAt: Double = 60.0) {
    data class Inputs(
        val quality: DataQualityReport,
        val coverage: Double,
        val regime: RegimeAssessment,
        val probs: HorizonProb,
        val calibration: CalibrationInfo,
        val horizonMinutes: Int,
        val events: List<TrackedEvent>,
        val chain: OptionChain?,
        val spot: Double,
        val atmIvPct: Double,
    )

    private fun factor(st: FeedStatus) = when (st) {
        FeedStatus.LIVE, FeedStatus.MANUAL -> 1.0
        FeedStatus.DEGRADED -> 0.6
        else -> 0.0
    }

    fun assess(i: Inputs): ModelHealth {
        val notes = ArrayList<String>()
        // 1. freshness: critical feeds (NIFTY, futures, options) dominate; the weighted quality score covers the rest
        val crit = i.quality.feeds.filter { it.critical }
        val critF = if (crit.isEmpty()) i.quality.score else crit.map { factor(it.status) }.average()
        val fresh = M.clamp(0.7 * critF + 0.3 * i.quality.score, 0.0, 1.0)
        val freshTxt = crit.joinToString(" · ") { f -> "${f.name} ${f.status}" + if (f.ageSeconds.isNaN()) "" else " %.0fs".format(f.ageSeconds) }
            .ifBlank { "quality %.0f%%".format(i.quality.score * 100) }

        // 2. completeness
        val complete = M.clamp(i.coverage, 0.0, 1.0)

        // 3. regime stability
        val reg = M.clamp(0.6 * i.regime.stability + 0.4 * i.regime.quality, 0.0, 1.0)

        // 4. calibration quality: has the calibration proved itself on unseen outcomes?
        val acc = i.calibration.accepted[i.horizonMinutes]
        val raw = i.calibration.holdoutBrierRaw[i.horizonMinutes]; val cal = i.calibration.holdoutBrierCalibrated[i.horizonMinutes]
        val (calQ, calTxt) = when {
            i.probs.calibrated -> {
                val skill = if (raw != null && cal != null && raw > 0) M.clamp((raw - cal) / raw * 10, 0.0, 1.0) else 0.0
                (0.6 + 0.4 * skill) to "calibrated · hold-out Brier %.3f vs raw %.3f".format(cal ?: Double.NaN, raw ?: Double.NaN)
            }
            i.probs.partial -> 0.55 to "partially calibrated (accepted on hold-out)"
            acc == false -> 0.25 to "calibration REJECTED on hold-out — raw scores only".also { notes += "Calibration did not beat the raw model on unseen outcomes" }
            else -> 0.35 to "not enough outcomes to calibrate yet"
        }

        // 5. news reliability: confidence of the events that matter, cut when the market contradicts the reading
        val material = i.events.filter { abs(it.effectiveImpact) > 0.01 }
        val news = if (material.isEmpty()) 0.8 else {
            val w = material.sumOf { abs(it.effectiveImpact) }
            val conf = material.sumOf { abs(it.effectiveImpact) * M.clamp(it.newsConfidence / 0.85, 0.0, 1.0) } / w
            val contradicted = material.count { it.reaction.contradicted }.toDouble() / material.size
            M.clamp(conf * (1 - 0.5 * contradicted), 0.0, 1.0)
        }
        val newsTxt = if (material.isEmpty()) "no material news" else "${material.size} material event(s)" +
            material.count { it.reaction.contradicted }.let { if (it > 0) " · $it contradicted by the market" else "" }

        // 6. options quality: feed status × ATM spread × chain depth × IV sanity
        val optFeed = i.quality.feeds.firstOrNull { it.name == "Options" }?.status
        val chain = i.chain
        val (optQ, optTxt) = if (chain == null || chain.rows.isEmpty()) 0.0 to "no option chain" else {
            val atm = chain.rows.minBy { abs(it.strike - i.spot) }
            fun sp(b: Double, a: Double) = if (b > 0 && a > 0) (a - b) / ((a + b) / 2) * 100 else Double.NaN
            val spreads = listOf(sp(atm.call.bid, atm.call.ask), sp(atm.put.bid, atm.put.ask)).filter { !it.isNaN() }
            val atmSpread = if (spreads.isEmpty()) Double.NaN else spreads.average()
            val spreadQ = if (atmSpread.isNaN()) 0.3 else M.clamp(1 - atmSpread / 5, 0.0, 1.0)
            val depth = M.clamp(chain.rows.size / 20.0, 0.0, 1.0)
            val ivOk = i.atmIvPct.isNaN() || i.atmIvPct in 3.0..80.0
            val st = optFeed?.let(::factor) ?: 1.0
            (st * (0.5 + 0.5 * spreadQ) * depth * if (ivOk) 1.0 else 0.3) to
                "${optFeed ?: "?"} · ATM spread %s · %d strikes".format(if (atmSpread.isNaN()) "–" else "%.1f%%".format(atmSpread), chain.rows.size)
        }

        val comps = listOf(
            HealthComponent("Data freshness", fresh, 25.0, freshTxt),
            HealthComponent("Data completeness", complete, 15.0, "coverage %.0f%%".format(complete * 100)),
            HealthComponent("Regime stability", reg, 15.0, "${i.regime.primary.label} · stability %.0f%% · quality %.0f%%".format(i.regime.stability * 100, i.regime.quality * 100)),
            HealthComponent("Calibration quality", calQ, 20.0, calTxt),
            HealthComponent("News reliability", news, 10.0, newsTxt),
            HealthComponent("Options quality", optQ, 15.0, optTxt),
        )
        val score = comps.sumOf { it.value * it.weight }
        val tier = when { score >= eligibleAt -> HealthTier.ELIGIBLE; score >= shadowAt -> HealthTier.SHADOW_ONLY; else -> HealthTier.NO_SIGNAL }
        comps.filter { it.value < 0.5 }.forEach { notes += "${it.name} weak: ${it.detail}" }
        return ModelHealth(score, tier, comps, eligibleAt, shadowAt, notes.distinct())
    }
}

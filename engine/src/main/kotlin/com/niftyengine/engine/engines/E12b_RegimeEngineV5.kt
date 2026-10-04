package com.niftyengine.engine.engines

import com.niftyengine.engine.core.EngineState
import com.niftyengine.engine.core.M
import com.niftyengine.engine.model.BlockView
import com.niftyengine.engine.model.Driver
import com.niftyengine.engine.model.ExpectationState
import com.niftyengine.engine.model.FutureExpectation
import com.niftyengine.engine.model.InformationShock
import com.niftyengine.engine.model.PrimaryRegime
import com.niftyengine.engine.model.Regime
import com.niftyengine.engine.model.RegimeAssessment
import com.niftyengine.engine.model.RegimeClass
import com.niftyengine.engine.model.RegimeResult
import com.niftyengine.engine.model.ShockKind
import kotlin.math.abs
import kotlin.math.sign

/**
 * 12b — Regime Engine v5: one primary regime per cycle plus a regime-quality score.
 *
 *   EVENT_DRIVEN          fresh unpriced event surprise, or a high-severity scheduled event still ahead
 *   VOLATILITY_EXPANSION  VIX spiking, realised vol / IV well above normal, abnormal NIFTY or global moves
 *   CONFLICT              information blocks (NIFTY, global, options, news, breadth, expectation) split ⇒ WAIT
 *   REVERSAL_RISK         established trend losing momentum / diverging, or the expected future turning against price
 *   TREND_UP / TREND_DOWN directional regime from the R1–R10 classifier
 *   RANGE                 no directional dominance
 *
 * The regime selects the direction engine's weight table and the strategy family; a CONFLICT regime is never
 * forced into UP or DOWN.
 */
class RegimeEngineV5(private val state: EngineState) {
    data class Inputs(
        val legacy: RegimeResult,
        val drivers: List<DirectionProbabilityEngine.DriverInput>,
        val adx: Double,
        val vix: VIXEngine.Result,
        val rangeEvidence: Double,
        val shock: InformationShock,
        val expectation: FutureExpectation,
        val rel: RelativeBaselines.Result,
        /** The event engine flagged a fresh, unpriced, high-severity event. */
        val newsShock: Boolean,
        val dataQuality: Double,
    )

    companion object {
        val BLOCKS = listOf(Driver.PRICE to "NIFTY", Driver.GLOBAL to "Global", Driver.DERIVATIVES to "Options/futures",
            Driver.NEWS to "News", Driver.BREADTH to "Breadth", Driver.EXPECTATION to "Expectation")

        fun table(p: PrimaryRegime): RegimeClass = when (p) {
            PrimaryRegime.TREND_UP, PrimaryRegime.TREND_DOWN -> RegimeClass.TREND
            PrimaryRegime.RANGE -> RegimeClass.RANGE
            PrimaryRegime.EVENT_DRIVEN -> RegimeClass.EVENT
            PrimaryRegime.VOLATILITY_EXPANSION -> RegimeClass.VOLATILE
            PrimaryRegime.REVERSAL_RISK -> RegimeClass.REVERSAL
            PrimaryRegime.CONFLICT -> RegimeClass.NORMAL
        }

        private fun baseQuality(p: PrimaryRegime) = when (p) {
            PrimaryRegime.TREND_UP, PrimaryRegime.TREND_DOWN -> 0.70
            PrimaryRegime.RANGE -> 0.62
            PrimaryRegime.EVENT_DRIVEN -> 0.50
            PrimaryRegime.VOLATILITY_EXPANSION -> 0.45
            PrimaryRegime.REVERSAL_RISK -> 0.35
            PrimaryRegime.CONFLICT -> 0.20
        }
    }

    data class Split(val blocks: List<BlockView>, val minorityShare: Double, val nBull: Int, val nBear: Int)

    fun blockSplit(drivers: List<DirectionProbabilityEngine.DriverInput>): Split {
        val w = DirectionProbabilityEngine.WEIGHTS.getValue(RegimeClass.NORMAL)
        val views = BLOCKS.mapNotNull { (d, name) ->
            drivers.firstOrNull { it.driver == d }?.let { inp ->
                val sg = if (abs(inp.score) >= 0.2 && inp.confidence >= 0.3) sign(inp.score).toInt() else 0
                BlockView(name, inp.score, inp.confidence, sg)
            }
        }
        fun weight(v: BlockView) = (w[BLOCKS.first { it.second == v.block }.first] ?: 0.0) * v.confidence * abs(v.score)
        val bull = views.filter { it.sign > 0 }.sumOf(::weight)
        val bear = views.filter { it.sign < 0 }.sumOf(::weight)
        val minority = if (bull + bear <= 0) 0.0 else minOf(bull, bear) / (bull + bear)
        return Split(views, minority, views.count { it.sign > 0 }, views.count { it.sign < 0 })
    }

    fun classify(i: Inputs, now: Long): RegimeAssessment {
        val reasons = ArrayList<String>()
        val split = blockSplit(i.drivers)
        val s = i.shock
        val r = i.rel

        val eventDriven = i.newsShock || (s.eventDriven && s.score >= 0.35) || i.expectation.eventRiskAhead >= 0.7
        if (i.newsShock) reasons += "Fresh unpriced high-severity event"
        else if (s.eventDriven && s.score >= 0.35) reasons += "Event surprise: " + s.sources.first { it.kind == ShockKind.EVENT_SURPRISE }.detail.take(90)
        else if (i.expectation.eventRiskAhead >= 0.7) reasons += "High-severity scheduled event ahead — outcome not yet known"

        val volReasons = ArrayList<String>()
        if (i.vix.state == VixState.SPIKING) volReasons += "India VIX spiking"
        if (i.vix.state == VixState.RISING && ((!r.realizedVolRel.isNaN() && r.realizedVolRel >= 1.4) || (!r.ivRel.isNaN() && r.ivRel >= 0.25)))
            volReasons += "VIX rising with vol above normal"
        if (!r.realizedVolRel.isNaN() && r.realizedVolRel >= 1.8) volReasons += "Realised vol %.1f× normal".format(r.realizedVolRel)
        s.sources.filter { (it.kind == ShockKind.VOLATILITY || it.kind == ShockKind.PRICE || it.kind == ShockKind.GLOBAL || it.kind == ShockKind.GAP) && it.magnitude >= 0.4 }
            .forEach { volReasons += it.detail }
        val volExpansion = volReasons.isNotEmpty()

        val conflict = (minOf(split.nBull, split.nBear) >= 2 && split.minorityShare >= 0.30) ||
            (minOf(split.nBull, split.nBear) >= 1 && split.minorityShare >= 0.42)

        val legacy = i.legacy.regime
        val warn = i.expectation.state == ExpectationState.BULL_REVERSAL_WARNING || i.expectation.state == ExpectationState.BEAR_REVERSAL_WARNING
        val reversal = legacy == Regime.TRANSITION || legacy == Regime.DIVERGENCE || warn

        val primary = when {
            eventDriven -> PrimaryRegime.EVENT_DRIVEN
            volExpansion -> { reasons += volReasons; PrimaryRegime.VOLATILITY_EXPANSION }
            conflict -> {
                reasons += "Information blocks disagree: " + split.blocks.filter { it.sign != 0 }
                    .joinToString { "${it.block} ${if (it.sign > 0) "bull" else "bear"}" }
                PrimaryRegime.CONFLICT
            }
            reversal -> {
                if (warn) reasons += i.expectation.state.label
                if (legacy == Regime.TRANSITION || legacy == Regime.DIVERGENCE) reasons += i.legacy.reasons.take(2)
                PrimaryRegime.REVERSAL_RISK
            }
            legacy.bias > 0 -> PrimaryRegime.TREND_UP
            legacy.bias < 0 -> PrimaryRegime.TREND_DOWN
            else -> PrimaryRegime.RANGE
        }
        if (primary == PrimaryRegime.TREND_UP || primary == PrimaryRegime.TREND_DOWN || primary == PrimaryRegime.RANGE)
            reasons += "${i.legacy.regime.label}: " + i.legacy.reasons.take(2).joinToString("; ")
        val reversalSide = if (primary == PrimaryRegime.REVERSAL_RISK) {
            when (i.expectation.state) {
                ExpectationState.BULL_REVERSAL_WARNING -> 1
                ExpectationState.BEAR_REVERSAL_WARNING -> -1
                else -> if (abs(i.expectation.current) >= 0.15) sign(i.expectation.current).toInt() else 0
            }
        } else 0
        return assess(primary, split, i, now, reasons, reversalSide)
    }

    /** Re-label as CONFLICT when the direction engine itself reports high driver conflict. */
    fun toConflict(a: RegimeAssessment, i: Inputs, now: Long, why: String): RegimeAssessment =
        assess(PrimaryRegime.CONFLICT, blockSplit(i.drivers), i, now, a.reasons + why, 0)

    private fun assess(p: PrimaryRegime, split: Split, i: Inputs, now: Long, reasons: List<String>, reversalSide: Int): RegimeAssessment {
        val recent = state.primaryHistory.toList().takeLast(5).map { it.second } + p
        val stability = recent.count { it == p }.toDouble() / recent.size
        var q = baseQuality(p) + 0.20 * (stability - 0.5) - 0.25 * split.minorityShare + 0.10 * (i.dataQuality - 0.8)
        when (p) {
            PrimaryRegime.TREND_UP, PrimaryRegime.TREND_DOWN -> if (!i.adx.isNaN()) q += 0.15 * M.clamp((i.adx - 20) / 20, -0.5, 1.0)
            PrimaryRegime.RANGE -> q += 0.15 * (i.rangeEvidence - 0.3)
            PrimaryRegime.EVENT_DRIVEN, PrimaryRegime.VOLATILITY_EXPANSION -> q -= 0.10 * i.shock.score
            else -> {}
        }
        return RegimeAssessment(p, M.clamp(q, 0.0, 1.0), stability, table(p), split.blocks, split.minorityShare, reversalSide, reasons.distinct())
    }

    fun commit(a: RegimeAssessment, now: Long) = state.pushPrimary(now, a.primary)
}

package com.niftyengine.engine.engines

import com.niftyengine.engine.core.EngineState
import com.niftyengine.engine.core.M
import com.niftyengine.engine.model.EngineSignal
import com.niftyengine.engine.model.EventStage
import com.niftyengine.engine.model.ExpectationComponent
import com.niftyengine.engine.model.ExpectationState
import com.niftyengine.engine.model.FutureExpectation
import com.niftyengine.engine.model.GapState
import com.niftyengine.engine.model.GiftNiftyReport
import com.niftyengine.engine.model.GlobalAsset
import com.niftyengine.engine.model.MarketSnapshot
import com.niftyengine.engine.model.NewsHorizon
import com.niftyengine.engine.model.PendingExpectation
import com.niftyengine.engine.model.TrackedEvent
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sign

/**
 * 23 — Future Expectation Engine (v5).
 *
 * Markets trade the future, not the present. The engine keeps three numbers:
 *   CURRENT   what the market is doing now (price structure, breadth, sectors);
 *   EXPECTED  what the market is pricing for the near future — expectation changes of unresolved events,
 *             lasting (beyond-horizon) news impact, options positioning vs its normal, futures premium vs fair
 *             carry, live global futures / GIFT pre-open, and the AI analyst's expectation shift;
 *   CHANGE    how EXPECTED moved over the last ~30 min.
 *
 * Price can still be rising while the expected future deteriorates — that is an early reversal warning that
 * arrives before RSI/MACD turn. The EXPECTATION driver = 0.6 × expected + 0.4 × change.
 */
class FutureExpectationEngine(private val state: EngineState) {
    data class Inputs(
        val snapshot: MarketSnapshot,
        val sessionStart: Long,
        val norm: DataNormalizer,
        val structure: EngineSignal,
        val breadth: EngineSignal,
        val sector: EngineSignal,
        val events: List<TrackedEvent>,
        val newsHorizons: Map<NewsHorizon, Double>,
        val decisionHorizon: NewsHorizon,
        val rel: RelativeBaselines.Result,
        val gift: GiftNiftyReport?,
    )

    data class Result(val expectation: FutureExpectation, val driverScore: Double, val driverConfidence: Double)

    fun analyze(i: Inputs, now: Long, commit: Boolean = true): Result {
        val comps = ArrayList<ExpectationComponent>()
        val notes = ArrayList<String>()

        // ---------------------------------------------------------------- current state
        val cur = listOf(i.structure to 0.60, i.breadth to 0.25, i.sector to 0.15).filter { it.first.confidence > 0 }
        val curDen = cur.sumOf { it.second * it.first.confidence }
        val current = if (curDen <= 0) 0.0 else M.clamp(cur.sumOf { it.second * it.first.confidence * it.first.score } / curDen)

        // ---------------------------------------------------------------- 1. unresolved events: how their expectation moves
        val pending = ArrayList<PendingExpectation>()
        var pendScore = 0.0
        var eventRisk = 0.0
        for (e in i.events) {
            val a = e.analysis ?: continue
            if (e.stage.outcomeKnown || now - e.lastInfoAt > 3 * 86_400_000L) continue
            val pts = e.expectations.filter { !it.probability.isNaN() }
            val p = pts.lastOrNull()?.probability ?: Double.NaN
            val dp = if (pts.size >= 2) pts.last().probability - pts[pts.size - 2].probability else Double.NaN
            pending += PendingExpectation(e.id, e.title.take(90), e.stage, a.expectedOutcome, p, dp, a.severity, a.direction)
            if (!dp.isNaN() && now - pts.last().t <= 4 * 3_600_000L) {
                val fresh = exp(-(now - pts.last().t) / (120 * 60_000.0))
                pendScore += sign(a.direction) * a.severity * dp * 2.0 * (0.5 + 0.5 * e.unpriced) * fresh
            }
            if (a.severity >= 0.6 && now - e.lastInfoAt <= 86_400_000L &&
                (e.stage == EventStage.EXPECTED || e.stage == EventStage.LIKELY))
                eventRisk = maxOf(eventRisk, a.severity * if (e.stage == EventStage.EXPECTED) 1.0 else 0.6)
        }
        if (pending.isNotEmpty()) comps += ExpectationComponent("Unresolved events", M.clamp(pendScore), 0.20,
            if (pending.any { !it.probabilityChange.isNaN() }) 0.8 else 0.3,
            pending.firstOrNull()?.let { p -> "${p.title.take(50)}: " + (if (p.probability.isNaN()) "p ?" else "p %.0f%%".format(p.probability * 100)) +
                (if (p.probabilityChange.isNaN()) "" else " (%+.0f pts)".format(p.probabilityChange * 100)) } ?: "")

        // ---------------------------------------------------------------- 2. lasting news impact beyond the decision horizon
        val h = i.newsHorizons
        if (h.isNotEmpty() && i.events.isNotEmpty()) {
            val fwd = when (i.decisionHorizon) {
                NewsHorizon.M5_15 -> 0.5 * (h[NewsHorizon.M30_120] ?: 0.0) + 0.5 * (h[NewsHorizon.EOD] ?: 0.0)
                NewsHorizon.M30_120 -> 0.5 * (h[NewsHorizon.EOD] ?: 0.0) + 0.5 * (h[NewsHorizon.D1_3] ?: 0.0)
                else -> (h[NewsHorizon.D1_3] ?: 0.0)
            }
            comps += ExpectationComponent("News beyond horizon", M.clamp(fwd), 0.15, 0.6, "lasting impact of known information")
        }

        // ---------------------------------------------------------------- 3. options positioning vs its normal
        val r = i.rel
        run {
            val parts = ArrayList<Pair<Double, Double>>()
            if (!r.pcrRel.isNaN()) parts += 0.35 to M.squash(r.pcrRel, 0.35)
            if (!r.skew.isNaN()) parts += 0.35 to -M.squash(r.skew - r.skewNormal, 3.0)
            if (!r.ivChange30Pct.isNaN()) parts += 0.30 to -M.squash(r.ivChange30Pct, 6.0)
            if (parts.isNotEmpty()) {
                val sc = parts.sumOf { it.first * it.second } / parts.sumOf { it.first }
                comps += ExpectationComponent("Options vs normal", M.clamp(sc), 0.20, if (!r.pcrRel.isNaN()) 0.8 else 0.5,
                    listOfNotNull(
                        if (!r.pcrRel.isNaN()) "PCR %+.0f%% vs normal".format(r.pcrRel * 100) else null,
                        if (!r.skew.isNaN()) "skew %+.1f vs %+.1f".format(r.skew, r.skewNormal) else null,
                        if (!r.ivChange30Pct.isNaN()) "IV %+.1f%% in 30 min".format(r.ivChange30Pct) else null,
                    ).joinToString(" · "))
            }
        }

        // ---------------------------------------------------------------- 4. futures premium vs fair carry
        if (!r.basisExcessPct.isNaN()) {
            val x = r.basisExcessPct - r.basisExcessNormal
            // Without a few days of its own history the "normal" excess is only a prior (fair value) — low confidence.
            comps += ExpectationComponent("Futures premium vs fair", M.squash(x, 0.15), 0.10, if (r.basisNormalDays >= 3) 0.7 else 0.3,
                "excess %+.2f%% of spot vs normal".format(x))
        }

        // ---------------------------------------------------------------- 5. live global futures / GIFT pre-open
        run {
            val s = i.snapshot
            val parts = ArrayList<Pair<Double, Double>>()
            val live = listOf(GlobalAsset.SP500 to 0.35, GlobalAsset.NASDAQ to 0.35, GlobalAsset.NIKKEI to 0.15, GlobalAsset.HANGSENG to 0.15)
            val txt = ArrayList<String>()
            for ((a, w) in live) {
                val d = s.global[a] ?: continue
                val c1h = i.norm.features(d, now, i.sessionStart).c1h
                if (c1h.isNaN()) continue
                val hourlyNormal = (r.globalNormalAbs[a] ?: a.typicalDailyMovePct * 0.8) * 0.4
                parts += w to a.riskSign * M.squash(c1h / hourlyNormal.coerceAtLeast(0.02), 1.5)
                txt += "${a.label} 1h %+.2f%%".format(c1h)
            }
            val g = i.gift
            if (g != null && g.state == GapState.PRE_OPEN && !g.impliedGapPct.isNaN() && g.ageMinutes <= 90) {
                parts += 0.6 to M.squash(g.impliedGapPct / r.normalGapPct.coerceAtLeast(0.05), 1.5)
                txt += "GIFT implied gap %+.2f%%".format(g.impliedGapPct)
            }
            if (parts.isNotEmpty()) comps += ExpectationComponent("Global futures / pre-open", M.clamp(parts.sumOf { it.first * it.second } / parts.sumOf { it.first }),
                0.15, 0.7, txt.take(3).joinToString(" · "))
        }

        // ---------------------------------------------------------------- 6. AI analyst: expectation shift per event
        run {
            var num = 0.0; var n = 0
            for (e in i.events) {
                val a = e.analysis ?: continue
                if (a.expectationShift.isNaN()) continue
                val age = now - a.analyzedAt
                if (age < 0 || age > 6 * 3_600_000L) continue
                num += a.expectationShift * a.confidence * exp(-age / (120 * 60_000.0)); n++
            }
            if (n > 0) comps += ExpectationComponent("AI expectation shift", M.squash(num, 0.6), 0.20, 0.75, "$n event reading(s)")
        }

        // ---------------------------------------------------------------- combine
        val den = comps.sumOf { it.weight * it.confidence }
        val expected = if (den <= 0) 0.0 else M.clamp(comps.sumOf { it.weight * it.confidence * it.score } / den)
        val coverage = comps.sumOf { it.weight * it.confidence } / 1.0
        val conf = M.clamp(coverage * 0.9, 0.0, 0.85)

        val past30 = state.expectationAt(now - 30 * 60_000L, now, 10 * 60_000L)
        val past10 = state.expectationAt(now - 10 * 60_000L, now, 5 * 60_000L)
        val change = past30?.let { expected - it } ?: 0.0
        val changeShort = past10?.let { expected - it } ?: 0.0
        if (commit && comps.isNotEmpty()) state.pushExpectation(now, expected)

        // Early reversal warning: price moving one way while the expected future deteriorates (Δ) — or already sits
        // clearly on the other side with decent confidence.
        val strongOpposite = conf >= 0.5 && abs(expected - current) >= 0.6
        val st = when {
            current >= 0.25 && (change <= -0.20 || (expected <= -0.25 && strongOpposite)) -> ExpectationState.BULL_REVERSAL_WARNING
            current <= -0.25 && (change >= 0.20 || (expected >= 0.25 && strongOpposite)) -> ExpectationState.BEAR_REVERSAL_WARNING
            change >= 0.15 -> ExpectationState.IMPROVING
            change <= -0.15 -> ExpectationState.DETERIORATING
            abs(current) >= 0.2 && abs(expected) >= 0.15 && sign(current) == sign(expected) -> ExpectationState.CONFIRMING
            else -> ExpectationState.NEUTRAL
        }
        when (st) {
            ExpectationState.BULL_REVERSAL_WARNING -> notes += "Price is rising but what the market expects next is deteriorating (%+.2f over 30 min)".format(change)
            ExpectationState.BEAR_REVERSAL_WARNING -> notes += "Price is falling but what the market expects next is improving (%+.2f over 30 min)".format(change)
            else -> {}
        }
        if (eventRisk >= 0.6) notes += "High-severity scheduled event still ahead — two-sided risk until the outcome is known"

        val driver = M.clamp(0.6 * expected + 0.4 * M.squash(change, 0.3))
        return Result(
            FutureExpectation(current, expected, change, changeShort, expected - current, st, conf, eventRisk,
                comps.sortedByDescending { abs(it.score * it.weight * it.confidence) }, pending.take(6), notes),
            driver, if (comps.isEmpty()) 0.0 else conf,
        )
    }
}

package com.niftyengine.engine.engines

import com.niftyengine.engine.core.M
import com.niftyengine.engine.model.Direction
import com.niftyengine.engine.model.EarningsInputs
import com.niftyengine.engine.model.EventStage
import com.niftyengine.engine.model.EventType
import com.niftyengine.engine.model.ExpectationChannel
import com.niftyengine.engine.model.ExpectationRecord
import com.niftyengine.engine.model.ExpectationReport
import com.niftyengine.engine.model.HorizonGroup
import com.niftyengine.engine.model.MacroInputs
import com.niftyengine.engine.model.NewsHorizon
import com.niftyengine.engine.model.TrackedEvent
import kotlin.math.abs
import kotlin.math.pow

/**
 * 20b — Expectation Engine (§12), common to H1, H2 and H3.
 *
 * Every major event becomes Expected → Actual → Surprise → Market interpretation → Persistence. Market reaction depends
 * on the surprise relative to expectations, not on whether the news is "good" or "bad": a 25 bps cut that was fully
 * expected carries little new information; an unexpected cut carries a lot.
 *
 * Sources:
 *  • news events tracked by [EventIntelligenceEngine] (expected outcome/probability, actual, surprise, priced-in share,
 *    per-horizon decayed impact);
 *  • manual consensus-vs-actual inputs (RBI policy, CPI, GDP, aggregate NIFTY earnings growth) entered in Setup.
 *
 * The aggregate impact per channel (RBI/rates, earnings, growth, inflation, fiscal, flows, crude, currency, global,
 * geopolitics) and horizon group is fed into the matching factor, so each surprise moves the factor it belongs to.
 */
object ExpectationEngine {
    private const val DAY = 86_400_000.0

    fun channelOf(t: EventType): ExpectationChannel = when (t) {
        EventType.RBI_POLICY -> ExpectationChannel.RBI_RATES
        EventType.INFLATION -> ExpectationChannel.INFLATION
        EventType.GROWTH -> ExpectationChannel.GROWTH
        EventType.FED, EventType.US_DATA -> ExpectationChannel.GLOBAL
        EventType.CRUDE -> ExpectationChannel.CRUDE
        EventType.GEOPOLITICS -> ExpectationChannel.GEOPOLITICS
        EventType.EARNINGS, EventType.CORPORATE -> ExpectationChannel.EARNINGS
        EventType.FLOWS -> ExpectationChannel.FLOWS
        EventType.GOVERNMENT -> ExpectationChannel.FISCAL
        EventType.CURRENCY -> ExpectationChannel.CURRENCY
        EventType.MARKET, EventType.OTHER -> ExpectationChannel.OTHER
    }

    private fun persistenceLabel(p: Double) = when { p >= 0.7 -> "High"; p >= 0.4 -> "Medium"; else -> "Low" }
    private fun interp(x: Double) = when { x > 0.02 -> Direction.BULLISH; x < -0.02 -> Direction.BEARISH; else -> Direction.NEUTRAL }

    /** Impact of one tracked news event per horizon group (from its per-horizon decayed effective impact). */
    fun groupImpact(e: TrackedEvent): Map<HorizonGroup, Double> {
        val h = e.horizonImpacts
        val pers = e.analysis?.persistence ?: 0.5
        return mapOf(
            HorizonGroup.H1 to (h[NewsHorizon.EOD] ?: 0.0),
            HorizonGroup.H2 to 0.6 * (h[NewsHorizon.D1_3] ?: 0.0) + 0.4 * (h[NewsHorizon.W1_2] ?: 0.0),
            HorizonGroup.H3 to (h[NewsHorizon.W1_2] ?: 0.0) * (0.5 + 0.5 * pers),
        )
    }

    fun analyze(events: List<TrackedEvent>, macro: MacroInputs, earnings: EarningsInputs, now: Long): ExpectationReport {
        val records = ArrayList<ExpectationRecord>()
        for (e in events) {
            val a = e.analysis ?: continue
            if (a.severity < 0.3 && abs(e.effectiveImpact) < 0.01) continue
            val exp = e.expectations.lastOrNull { it.expectedOutcome.isNotBlank() }
            val expected = (a.expectedOutcome.ifBlank { exp?.expectedOutcome ?: "" }).ifBlank { "not stated" } +
                (exp?.probability?.takeIf { !it.isNaN() }?.let { " (%.0f%% expected)".format(it * 100) } ?: "")
            val actual = a.actualOutcome.ifBlank { if (e.stage.outcomeKnown) a.title.take(60) else "pending" }
            val surpriseLabel = when {
                !e.stage.outcomeKnown -> "Pending (${e.stage.label})"
                e.surprise > 0.1 -> "Positive"
                e.surprise < -0.1 -> "Negative"
                else -> "In line"
            }
            records += ExpectationRecord(
                id = e.id, title = e.title, channel = channelOf(e.type), source = a.source, expected = expected, actual = actual,
                surprise = e.surprise, surpriseLabel = surpriseLabel, interpretation = interp(e.effectiveImpact.takeIf { abs(it) > 0.005 } ?: a.direction * 0.05),
                persistence = persistenceLabel(a.persistence), persistenceValue = a.persistence, pricedIn = e.pricedIn,
                impact = groupImpact(e), asOf = e.lastInfoAt,
            )
        }
        records += manual(macro, earnings, now)
        val channels = ExpectationChannel.values().associateWith { ch ->
            val sel = records.filter { it.channel == ch }
            HorizonGroup.values().associateWith { g -> M.squash(sel.sumOf { it.impact[g] ?: 0.0 }, 0.6) }
        }.filterValues { m -> m.values.any { abs(it) > 1e-6 } }
        return ExpectationReport(records.sortedByDescending { r -> r.impact.values.maxOf { abs(it) } }.take(25), channels)
    }

    /**
     * Manual consensus-vs-actual releases. Impact = surprise × persistence, decaying with the age of the release
     * (half-lives per horizon group).
     */
    private fun manual(m: MacroInputs, e: EarningsInputs, now: Long): List<ExpectationRecord> {
        val out = ArrayList<ExpectationRecord>()
        fun rec(id: String, title: String, ch: ExpectationChannel, expected: String, actual: String, surprise: Double, pers: Double,
                releasedAt: Long, hl: Triple<Double, Double, Double>) {
            if (surprise.isNaN()) return
            val ageD = if (releasedAt > 0) ((now - releasedAt) / DAY).coerceAtLeast(0.0) else 7.0
            val s = M.clamp(surprise)
            fun dec(h: Double) = s * pers * 0.5.pow(ageD / h)
            out += ExpectationRecord(
                id = id, title = title, channel = ch, source = "manual", expected = expected, actual = actual, surprise = s,
                surpriseLabel = when { s > 0.1 -> "Positive"; s < -0.1 -> "Negative"; else -> "In line" },
                interpretation = interp(s * 0.1), persistence = persistenceLabel(pers), persistenceValue = pers, pricedIn = 0.0,
                impact = mapOf(HorizonGroup.H1 to dec(hl.first), HorizonGroup.H2 to dec(hl.second), HorizonGroup.H3 to dec(hl.third)),
                asOf = releasedAt,
            )
        }
        fun bps(x: Double) = when { x.isNaN() -> "?"; x == 0.0 -> "no change"; x < 0 -> "%.0f bps cut".format(-x); else -> "%.0f bps hike".format(x) }
        val policyAt = m.releasedAt["lastPolicyChangeBps"] ?: m.releasedAt["repoRate"] ?: 0L
        if (!m.policyExpectedChangeBps.isNaN())
            rec("manual-rbi", "RBI policy decision", ExpectationChannel.RBI_RATES, bps(m.policyExpectedChangeBps), bps(m.lastPolicyChangeBps),
                -(m.lastPolicyChangeBps - m.policyExpectedChangeBps) / 25.0, 0.8, policyAt, Triple(2.0, 30.0, 60.0))
        if (!m.cpiConsensus.isNaN() && !m.cpiYoY.isNaN())
            rec("manual-cpi", "India CPI inflation", ExpectationChannel.INFLATION, "%.2f%%".format(m.cpiConsensus), "%.2f%%".format(m.cpiYoY),
                -(m.cpiYoY - m.cpiConsensus) / 0.3, 0.55, m.releasedAt["cpiYoY"] ?: 0L, Triple(1.0, 15.0, 30.0))
        if (!m.gdpConsensus.isNaN() && !m.gdpGrowth.isNaN())
            rec("manual-gdp", "India GDP growth", ExpectationChannel.GROWTH, "%.1f%%".format(m.gdpConsensus), "%.1f%%".format(m.gdpGrowth),
                (m.gdpGrowth - m.gdpConsensus) / 0.5, 0.55, m.releasedAt["gdpGrowth"] ?: 0L, Triple(1.0, 20.0, 45.0))
        if (!e.epsGrowthExpected.isNaN() && !e.epsGrowthActual.isNaN())
            rec("manual-eps", "NIFTY quarterly earnings growth", ExpectationChannel.EARNINGS, "%+.1f%%".format(e.epsGrowthExpected),
                "%+.1f%%".format(e.epsGrowthActual), (e.epsGrowthActual - e.epsGrowthExpected) / 5.0, 0.75, e.asOf, Triple(2.0, 20.0, 45.0))
        return out
    }

    /** Events whose outcome is still ahead (for the event-risk calendar). */
    fun pendingMajor(events: List<TrackedEvent>, now: Long): List<TrackedEvent> = events.filter { e ->
        val sev = e.analysis?.severity ?: 0.0
        sev >= 0.5 && !e.stage.outcomeKnown && e.stage != EventStage.RUMOUR && now - e.lastInfoAt < DAY.toLong()
    }
}

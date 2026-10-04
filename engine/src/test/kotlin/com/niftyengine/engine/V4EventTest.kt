package com.niftyengine.engine

import com.niftyengine.engine.core.Session
import com.niftyengine.engine.engines.EventIntelligenceEngine
import com.niftyengine.engine.engines.HistoricalReplayEngine
import com.niftyengine.engine.engines.ReplayMode
import com.niftyengine.engine.model.EventAnalysis
import com.niftyengine.engine.model.EventDuration
import com.niftyengine.engine.model.EventStage
import com.niftyengine.engine.model.EventType
import com.niftyengine.engine.model.MarketBaseline
import com.niftyengine.engine.model.MarketChannel
import com.niftyengine.engine.model.NewsHorizon
import com.niftyengine.engine.model.NewsItem
import com.niftyengine.engine.model.Sector
import com.niftyengine.engine.model.TrackedEvent
import com.niftyengine.engine.sim.SimulatedMarket
import java.time.LocalDate
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class V4EventTest {
    private val day = LocalDate.of(2026, 9, 29)
    private val open = day.atTime(9, 15).atZone(Session.IST).toInstant().toEpochMilli()
    private fun at(min: Int) = open + min * 60_000L

    private fun mkt(t: Long, nifty: Double, bank: Double = nifty * 2.25, vix: Double = 14.0, usdinr: Double = 88.0) =
        MarketBaseline(t = t, nifty = nifty, bank = bank, vix = vix, usdinr = usdinr, futures = nifty + 60, pcr = 1.0, adRatio = 0.0,
            sectors = mapOf(Sector.BANK to bank), stocks = mapOf("HDFCBANK" to 1000.0 * nifty / 25000))

    private val prev = mkt(open - 1, 25000.0)

    private fun gemini(id: String, t: Long, stage: EventStage, dir: Double, sev: Double, p: Double = Double.NaN, matches: Boolean? = null,
                       surprise: Double = 0.0, type: EventType = EventType.RBI_POLICY, duration: EventDuration = EventDuration.MEDIUM,
                       merge: List<String> = emptyList()) =
        EventAnalysis(id, t, "gemini:test", "event", type, stage, sev, dir, listOf(Sector.BANK), listOf("HDFCBANK"),
            listOf(MarketChannel.RATES), "25 bps cut", p, if (stage.outcomeKnown) "25 bps cut" else "", matches, surprise,
            duration, 0.6, 0.1, 0.9, mergeWith = merge)

    /** Runs one engine through a scripted sequence of (minute → articles, analyses, NIFTY level). */
    private class Script(val eng: EventIntelligenceEngine = EventIntelligenceEngine()) {
        val news = ArrayList<NewsItem>()
        var last: EventIntelligenceEngine.Result? = null
    }

    private fun Script.step(t: Long, nifty: Double, add: List<NewsItem> = emptyList(), analyses: List<EventAnalysis> = emptyList(),
                            vix: Double = 14.0, usdinr: Double = 88.0): EventIntelligenceEngine.Result {
        news += add
        val r = eng.process(news.toList(), analyses, t, mkt(t, nifty, vix = vix, usdinr = usdinr), prev, emptyList(), NewsHorizon.M30_120)
        last = r
        return r
    }

    private fun only(r: EventIntelligenceEngine.Result): TrackedEvent { assertEquals(1, r.events.size, r.events.map { it.title }.toString()); return r.events[0] }

    // 2 — Expectation-state: expected news is not new news.
    @Test fun expectedRbiCutHasLowSurpriseAndSurpriseCutHasHigh() {
        fun run(matches: Boolean, surprise: Double): TrackedEvent {
            val s = Script()
            s.step(at(1), 25000.0, listOf(NewsItem("a1", "RBI expected to cut repo rate by 25 bps, economists poll shows", "Reuters", at(0))))
            val id = s.last!!.events[0].id
            s.step(at(2), 25000.0, analyses = listOf(gemini(id, at(2), EventStage.EXPECTED, 0.6, 0.9, p = 0.8)))
            s.step(at(45), 25000.0, listOf(NewsItem("a2", "RBI cuts repo rate, decision announced", "Economic Times", at(45))))
            return only(s.step(at(46), 25000.0, analyses = listOf(gemini(id, at(46), EventStage.CONFIRMED, 0.6, 0.9, p = 0.8, matches = matches, surprise = surprise))))
        }
        val asExpected = run(true, 0.1)
        val surprise = run(false, 0.8)
        println("as expected: S=%.2f eff=%+.3f | surprise: S=%.2f eff=%+.3f".format(asExpected.surpriseMagnitude, asExpected.effectiveImpact,
            surprise.surpriseMagnitude, surprise.effectiveImpact))
        assertEquals(0.15, asExpected.surpriseMagnitude, 0.06) // ≈ 0.5·(1−0.8) + 0.5·0.1
        assertTrue(surprise.surpriseMagnitude >= 0.75)
        assertTrue(abs(surprise.effectiveImpact) > 4 * abs(asExpected.effectiveImpact))
        assertEquals(listOf(EventStage.EXPECTED, EventStage.CONFIRMED), asExpected.stageHistory.map { it.stage })
        // first sight of a scheduled, consensus event carries (almost) no new information
        val s = Script()
        s.step(at(1), 25000.0, listOf(NewsItem("b1", "RBI expected to cut repo rate by 25 bps, economists poll shows", "Reuters", at(0))))
        val e = only(s.step(at(2), 25000.0, analyses = listOf(gemini(s.last!!.events[0].id, at(2), EventStage.EXPECTED, 0.6, 0.9, p = 0.8))))
        assertTrue(e.surpriseMagnitude <= 0.06, "consensus expectation is not news: S=${e.surpriseMagnitude}")
    }

    // 3 — Pricing-in: if the market already moved, the same news has little unpriced impact left.
    @Test fun rumourAlreadyPricedLeavesLittleUnpricedImpact() {
        fun run(niftyAfter: Double): TrackedEvent {
            val s = Script()
            s.step(at(60), 25000.0, listOf(NewsItem("w1", "Border clash reportedly under way, sources said", "Reuters", at(60))))
            val id = s.last!!.events[0].id
            s.step(at(61), 25000.0, analyses = listOf(gemini(id, at(61), EventStage.RUMOUR, -0.8, 0.9, p = 0.5, type = EventType.GEOPOLITICS)))
            return only(s.step(at(90), niftyAfter, vix = if (niftyAfter < 25000) 15.5 else 14.0))
        }
        val priced = run(24700.0)   // −1.2 % after the rumour
        val unpriced = run(25000.0) // market ignored it
        println("priced: unpriced=%.2f eff=%+.3f | flat: unpriced=%.2f eff=%+.3f".format(priced.unpriced, priced.effectiveImpact, unpriced.unpriced, unpriced.effectiveImpact))
        assertTrue(priced.unpriced <= 0.2, "move already absorbed: ${priced.pricingNotes}")
        assertTrue(unpriced.unpriced >= 0.9)
        assertTrue(abs(priced.effectiveImpact) < 0.25 * abs(unpriced.effectiveImpact))
        assertTrue("MOSTLY_PRICED" in priced.flags || priced.pricedIn > 0.6)
    }

    // 5 — Market reaction: contradiction cuts confidence; the market is not forced to agree.
    @Test fun marketContradictionReducesNewsConfidence() {
        fun run(niftyAfter: Double, bankAfter: Double): TrackedEvent {
            val s = Script()
            s.step(at(30), 25000.0, listOf(NewsItem("c1", "Government announces big infrastructure stimulus package", "Reuters", at(30))))
            val id = s.last!!.events[0].id
            s.step(at(31), 25000.0, analyses = listOf(gemini(id, at(31), EventStage.CONFIRMED, 0.8, 0.8, matches = false, surprise = 0.7, type = EventType.GOVERNMENT)))
            val r = s.eng.process(s.news, emptyList(), at(55), mkt(at(55), niftyAfter, bank = bankAfter, vix = 14.9), prev, emptyList(), NewsHorizon.M30_120)
            return r.events.single()
        }
        val rejected = run(24850.0, 56000.0) // −0.6 % against a bullish reading
        val accepted = run(25000.0, 56250.0)
        println("rejected agree=%.2f conf=%.2f | flat conf=%.2f".format(rejected.reaction.agreement, rejected.newsConfidence, accepted.newsConfidence))
        assertTrue(rejected.reaction.contradicted && "MARKET_DISAGREES" in rejected.flags)
        assertTrue(rejected.newsConfidence < 0.6 * accepted.newsConfidence)
    }

    // 4 — Lifecycle + clustering: one EVENT_ID across outlets, rewrites and stages; AI merges what rules can't.
    @Test fun oneEventIdAcrossArticlesAndStages() {
        val s = Script()
        s.step(at(10), 25000.0, listOf(
            NewsItem("r1", "Border clash reportedly under way, sources said", "Reuters", at(9)),
            NewsItem("r2", "Border clash reportedly under way: report", "Moneycontrol", at(10)),
            NewsItem("r3", "Update: border clash reportedly under way, sources said", "Livemint", at(10)),
        ))
        val e1 = only(s.last!!)
        assertEquals(3, e1.articleIds.size)
        assertEquals(EventStage.RUMOUR, e1.stage)
        s.step(at(30), 24950.0, listOf(NewsItem("r4", "Military escalation likely as border clash widens", "Economic Times", at(30))))
        assertEquals(1, s.last!!.events.size, "shared phrase 'border clash' keeps one event")
        // A differently-worded confirmation forms a new cluster; the AI analyst says it's the same event.
        s.step(at(60), 24900.0, listOf(NewsItem("r5", "Government confirms troops attacked at frontier post", "PIB", at(60))))
        val other = s.last!!.events.first { it.id != e1.id }
        val r = s.step(at(61), 24900.0, analyses = listOf(gemini(other.id, at(61), EventStage.CONFIRMED, -0.7, 0.9,
            type = EventType.GEOPOLITICS, merge = listOf(e1.id))))
        val e = only(r)
        assertEquals(e1.id, e.id, "older EVENT_ID survives the merge")
        assertEquals(5, e.articleIds.size)
        val stages = e.stageHistory.map { it.stage }
        assertEquals(EventStage.RUMOUR, stages.first()); assertEquals(EventStage.CONFIRMED, stages.last())
        assertTrue(EventStage.LIKELY in stages)
        assertTrue(e.expectations.size >= 3)
    }

    // 6 — Multi-horizon: separate impacts; short events fade fast, long events persist.
    @Test fun horizonsDifferByDurationAndAge() {
        fun ev(d: EventDuration, ageMin: Int): TrackedEvent {
            val s = Script()
            s.step(at(0), 25000.0, listOf(NewsItem("h-$d-$ageMin", "Crude oil prices surge on supply shock", "Reuters", at(0))))
            val id = s.last!!.events[0].id
            s.step(at(1), 25000.0, analyses = listOf(gemini(id, at(1), EventStage.CONFIRMED, -0.7, 0.8, matches = false, surprise = -0.7,
                type = EventType.CRUDE, duration = d)))
            return only(s.step(at(ageMin), 25000.0))
        }
        val shortFresh = ev(EventDuration.SHORT, 3).horizonImpacts
        val shortOld = ev(EventDuration.SHORT, 120).horizonImpacts
        val longFresh = ev(EventDuration.LONG, 3).horizonImpacts
        fun a(m: Map<NewsHorizon, Double>, h: NewsHorizon) = abs(m.getValue(h))
        assertTrue(a(shortFresh, NewsHorizon.M5_15) > a(shortFresh, NewsHorizon.EOD) && a(shortFresh, NewsHorizon.EOD) > a(shortFresh, NewsHorizon.W1_2))
        assertTrue(a(longFresh, NewsHorizon.D1_3) > a(longFresh, NewsHorizon.M5_15))
        assertTrue(a(shortOld, NewsHorizon.M5_15) < 0.01 * a(shortFresh, NewsHorizon.M5_15), "immediate impact gone after 2h")
        assertTrue(a(shortOld, NewsHorizon.EOD) > 0.3 * a(shortFresh, NewsHorizon.EOD), "EOD impact persists")
        assertTrue(shortFresh.values.all { it <= 0 }, "crude shock is negative on every horizon")
    }

    // 7 — point-in-time: an analysis from the future is ignored.
    @Test fun futureAnalysisIsIgnored() {
        val s = Script()
        s.step(at(5), 25000.0, listOf(NewsItem("p1", "Fed holds rates steady", "Reuters", at(5))))
        val id = s.last!!.events[0].id
        val e = only(s.step(at(6), 25000.0, analyses = listOf(gemini(id, at(30), EventStage.CONFIRMED, 0.9, 0.9, matches = false, surprise = 0.9))))
        assertEquals("rules", e.analysis!!.source)
    }

    // End-to-end on the simulator storylines (rules only — no AI key in tests).
    @Test fun simulatedStorylinesFlowThroughTheEngine() {
        var rbiSeen = false; var warSeen = false
        for (seed in 1L..20L) {
            if (rbiSeen && warSeen) break
            val sim = SimulatedMarket(seed = seed, startDate = day)
            val engine = NiftyDirectionEngine()
            var out = engine.process(sim.collect(0))
            repeat(250) { out = engine.process(sim.collect(0)) }
            assertEquals(5, out.newsHorizons.size)
            out.events.firstOrNull { it.type == EventType.RBI_POLICY && it.stageHistory.any { s -> s.stage == EventStage.EXPECTED } }?.let { rbi ->
                rbiSeen = true
                println("seed $seed RBI ${rbi.stageHistory.map { it.stage }} S=%.2f eff=%+.3f".format(rbi.surpriseMagnitude, rbi.effectiveImpact))
                assertEquals(EventStage.CONFIRMED, rbi.stage)
                assertTrue(rbi.surpriseMagnitude <= 0.3, "RBI cut as expected → low surprise")
            }
            val war = out.events.filter { it.type == EventType.GEOPOLITICS && it.stageHistory.any { s -> s.stage == EventStage.RUMOUR } }
            if (war.isNotEmpty()) {
                warSeen = true
                val w = war.first()
                println("seed $seed border ${w.stageHistory.map { it.stage }} articles=${w.articleIds.size} unpriced=%.2f eff=%+.3f flags=${w.flags}"
                    .format(w.unpriced, w.effectiveImpact))
                assertEquals(1, war.size, "rumour → likely → confirmed stays one EVENT_ID")
                assertEquals(listOf(EventStage.RUMOUR, EventStage.LIKELY, EventStage.CONFIRMED), w.stageHistory.map { it.stage })
            }
        }
        assertTrue(rbiSeen && warSeen, "storylines not produced (rbi=$rbiSeen war=$warSeen)")
    }

    @Test fun replayModeAStripsAnalyses() {
        val sim = SimulatedMarket(seed = 4, startDate = day)
        val snaps = List(60) { sim.collect(0) }.mapIndexed { i, s ->
            if (i == 10) s.copy(eventAnalyses = listOf(gemini("X", s.timestamp, EventStage.CONFIRMED, 0.5, 0.5))) else s
        }
        val r = HistoricalReplayEngine()
        assertTrue(r.pointInTime(snaps[10], ReplayMode.MARKET_ONLY, emptyList()).eventAnalyses.isEmpty())
        assertEquals(1, r.pointInTime(snaps[10], ReplayMode.FULL_INFORMATION, emptyList()).eventAnalyses.size)
        assertNull(null)
    }
}

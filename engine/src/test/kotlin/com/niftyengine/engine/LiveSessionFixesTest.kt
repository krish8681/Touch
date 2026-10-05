package com.niftyengine.engine

import com.niftyengine.engine.core.Session
import com.niftyengine.engine.engines.DataQualityEngine
import com.niftyengine.engine.engines.EventIntelligenceEngine
import com.niftyengine.engine.engines.InMemoryPredictionStore
import com.niftyengine.engine.engines.PredictionAudit
import com.niftyengine.engine.engines.PredictionLogger
import com.niftyengine.engine.engines.PredictionRecord
import com.niftyengine.engine.engines.NewsEventEngine
import com.niftyengine.engine.engines.RuleEventAnalyzer
import com.niftyengine.engine.model.Candle
import com.niftyengine.engine.model.EventStage
import com.niftyengine.engine.model.EventType
import com.niftyengine.engine.model.FeedStatus
import com.niftyengine.engine.model.MarketBaseline
import com.niftyengine.engine.model.NewsHorizon
import com.niftyengine.engine.model.MarketSnapshot
import com.niftyengine.engine.model.NewsItem
import com.niftyengine.engine.sim.SimulatedMarket
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** v5.1.2 — issues seen in the first live (Kite) run, reproduced with the values from the device. */
class LiveSessionFixesTest {
    private fun ist(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(Session.IST).toInstant().toEpochMilli()

    private val thu = LocalDate.of(2026, 10, 1)            // last session (Fri 2 Oct = exchange holiday)
    private val sundayEvening = ist(2026, 10, 4, 19, 36)
    private val dq = DataQualityEngine(setOf("NIFTY", "Futures", "Options"))

    /** A valid snapshot whose critical feeds carry the given stamps, NIFTY bars ending at [lastBar]. */
    private fun snap(now: Long, nifty: Long, futures: Long, chain: Long, lastBar: Long): MarketSnapshot {
        val sim = SimulatedMarket(seed = 3, startDate = thu)
        repeat(20) { sim.collect(0) }
        val s = sim.collect(0)
        val bar = s.nifty.intraday.last()
        return s.copy(
            timestamp = now,
            nifty = s.nifty.copy(asOf = nifty, intraday = s.nifty.intraday.dropLast(1) + Candle(lastBar, bar.o, bar.h, bar.l, bar.c)),
            futures = s.futures!!.copy(asOf = futures), optionChain = s.optionChain!!.copy(asOf = chain),
            vix = s.vix?.copy(asOf = nifty), bankNifty = s.bankNifty?.copy(asOf = nifty),
        )
    }

    // ------------------------------------------------------------------ after-hours freshness

    @Test fun postCloseRestampsAreTheSessionsFinalValuesNotStale() {
        // exactly the device: NIFTY 17:35, futures 17:01, chain 17:18, last minute bar 15:29 — on Sunday evening
        val s = snap(sundayEvening, ist(2026, 10, 1, 17, 35), ist(2026, 10, 1, 17, 1), ist(2026, 10, 1, 17, 18), ist(2026, 10, 1, 15, 29))
        val r = dq.assess(s, sundayEvening, Double.NaN, 0L)
        for (f in listOf("NIFTY", "Futures", "Options", "NIFTY bars")) {
            val q = r.feeds.first { it.name == f }
            assertEquals(FeedStatus.LIVE, q.status, "$f: $q")
            assertTrue(q.ageSeconds >= 0, "$f age must not read as ahead: ${q.ageSeconds}")
        }
        assertTrue(r.circuitBreaker.isEmpty(), r.circuitBreaker.toString())
        assertTrue(r.warnings.any { "15:30" in it && "2026-10-01" in it }, "ages are measured against the last close: ${r.warnings}")
    }

    @Test fun aFeedThatStalledDuringTheSessionIsStillStaleAfterHours() {
        // futures froze at 13:00 on Thursday: 2.5 h before the close is genuinely stale
        val s = snap(sundayEvening, ist(2026, 10, 1, 17, 35), ist(2026, 10, 1, 13, 0), ist(2026, 10, 1, 17, 18), ist(2026, 10, 1, 15, 29))
        val r = dq.assess(s, sundayEvening, Double.NaN, 0L)
        assertEquals(FeedStatus.STALE, r.feeds.first { it.name == "Futures" }.status)
        assertTrue(r.circuitBreaker.any { it.startsWith("Futures") })
    }

    @Test fun duringTheSessionA34MinuteOldFeedIsStillStale() {
        val now = ist(2026, 10, 1, 11, 35)
        val s = snap(now, now, now - 34 * 60_000L, now - 60_000L, now - 60_000L)
        val r = dq.assess(s, now, Double.NaN, 0L)
        assertEquals(FeedStatus.STALE, r.feeds.first { it.name == "Futures" }.status)
        assertTrue(r.circuitBreaker.any { it.startsWith("Futures") })
    }

    @Test fun futureStampsAreStillRejectedAfterHours() {
        val s = snap(sundayEvening, sundayEvening + 120_000L, ist(2026, 10, 1, 17, 1), ist(2026, 10, 1, 17, 18), ist(2026, 10, 1, 15, 29))
        val r = dq.assess(s, sundayEvening, Double.NaN, 0L)
        assertEquals(FeedStatus.FUTURE, r.feeds.first { it.name == "NIFTY" }.status)
        assertTrue(r.circuitBreaker.any { "FUTURE" in it })
    }

    // ------------------------------------------------------------------ rule fallback (Gemini unavailable)

    @Test fun previewsOutlooksAndOpinionAreNotConfirmedEvents() {
        listOf(
            "Week Ahead On D-Street: RBI Policy, Q2 Results, FPI Flows Among Key Triggers To Dictate Sensex, Nifty",
            "Market outlook: RBI policy stance, Q2 earnings, West Asia tensions key triggers next week",
            "RBI's October MPC Meeting Begins Monday; 25-Basis-Point Repo Rate Hike Possible",
            "Quote on RBI MPC Expectation by Shishir Baijal, International Partner, Chairman and Managing Director, Knight Frank India",
            "Rate hike? Yes, but how long is the cycle?",
        ).forEach { assertEquals(EventStage.EXPECTED, RuleEventAnalyzer.stageOf(it), it) }
        // real outcomes stay confirmed — also with "expectations" or a question in the title
        listOf(
            "RBI cuts repo rate by 25 bps, in line with expectations",
            "RBI keeps repo rate unchanged at 5.5%",
            "RBI hikes repo rate by 25 bps: what it means for your EMI?",
        ).forEach { assertEquals(EventStage.CONFIRMED, RuleEventAnalyzer.stageOf(it), it) }
        assertEquals(EventStage.POSSIBLE, RuleEventAnalyzer.stageOf("Repo rate hike possible in December, economists say"))
    }

    @Test fun aBareRbiMentionIsNotMonetaryPolicy() {
        val rules = NewsEventEngine()
        fun type(title: String) = rules.buildEvent(listOf(NewsItem("n", title, "Economic Times", sundayEvening - 3_600_000L)),
            sundayEvening, emptyList(), 0.0, 0.0).type
        assertNotEquals(EventType.RBI_POLICY, type("Kotak Mahindra Bank stock gets a new CEO after RBI approval"))
        assertNotEquals(EventType.RBI_POLICY, type("RBI imposes penalty on two co-operative banks"))
        assertEquals(EventType.RBI_POLICY, type("RBI keeps repo rate unchanged at 5.5%"))
        assertEquals(EventType.RBI_POLICY, type("RBI's October MPC Meeting Begins Monday; 25-Basis-Point Repo Rate Hike Possible"))
        assertEquals(EventType.RBI_POLICY, type("Governor Malhotra signals pause"))
        assertEquals(EventType.RBI_POLICY, type("Rate cut by RBI likely in December: poll"))
    }

    // ------------------------------------------------------------------ v5.1.3 — first full live session (5 Oct)

    private val mon = LocalDate.of(2026, 10, 5)
    private fun at(h: Int, m: Int) = ist(2026, 10, 5, h, m)
    private fun mkt(t: Long) = MarketBaseline(t = t, nifty = 22_450.0)
    private fun feed(eng: EventIntelligenceEngine, news: List<NewsItem>, t: Long) =
        eng.process(news, emptyList(), t, mkt(t), mkt(at(9, 15)), emptyList(), NewsHorizon.M30_120)

    @Test fun eventsDoNotSnowballIntoOneMegaCluster() {
        val eng = EventIntelligenceEngine()
        val news = ArrayList<NewsItem>()
        var t = at(9, 30)
        fun add(title: String, src: String = "Economic Times") { news += NewsItem("n${news.size}", title, src, t); t += 120_000L; feed(eng, news, t) }
        add("RBI MPC meeting begins Monday; repo rate hike possible")
        add("RBI MPC meeting begins today, repo rate hike possible amid inflation", "Reuters")      // same story → joins
        add("RBI MPC meeting: repo rate decision on Wednesday, economists see hike", "Mint")        // same story → joins
        // shares 5 of its 7 words with the event — but 3 of those came from the rewrites, only "rbi"/"rate" from the founding headline
        add("Economists see inflation easing, RBI rate steady")                                    // different stories ↓
        add("RBI cautions banks on global financial tightening risks")
        add("UPI MDR should not attract any GST: Former RBI Governor C Rangarajan")
        add("Kotak Mahindra Bank stock gets a new CEO after RBI approval")
        add("RBI chief says the next financial crisis may start with a cyber attack")
        val r = feed(eng, news, t)
        val mpc = r.events.first { "MPC" in it.title || it.articleIds.contains("n0") }
        assertEquals(3, mpc.articleIds.size, "only the MPC rewrites belong to the MPC event: ${r.events.map { it.title to it.articleIds.size }}")
        assertTrue(r.events.all { it.articleIds.size <= 3 }, r.events.map { it.title to it.articleIds.size }.toString())
    }

    @Test fun anOutcomeOnceKnownIsNotUndoneByLaterPreviews() {
        val eng = EventIntelligenceEngine()
        val news = ArrayList<NewsItem>()
        var t = at(9, 30)
        fun add(title: String) { news += NewsItem("n${news.size}", title, "Reuters", t); t += 300_000L; feed(eng, news, t) }
        add("RBI MPC meeting begins; repo rate decision due Wednesday")
        add("RBI MPC keeps repo rate unchanged at 5.5%")
        add("RBI MPC repo rate decision: what to expect next?")
        add("RBI MPC repo rate: will the pause last?")
        val ev = feed(eng, news, t).events.single { it.articleIds.size == 4 }
        assertEquals(EventStage.CONFIRMED, ev.stage)
        val stages = ev.stageHistory.map { it.stage }
        assertEquals(listOf(EventStage.EXPECTED, EventStage.CONFIRMED), stages, "no Confirmed→Expected→Confirmed flip-flop: $stages")
    }

    @Test fun commentaryOnAScheduledEventIsNotTheDecision() {
        val rules = NewsEventEngine()
        val items = listOf(NewsItem("f", "US Market: Fed's Hammack says jobs data gives time to assess rate path", "Reuters", at(13, 49)))
        val a = RuleEventAnalyzer.analyze(rules, "E1", items, at(14, 0), emptyList(), 0.0, 0.0)
        assertEquals(EventType.FED, a.eventType)
        assertEquals(EventStage.EXPECTED, a.stage, "a speech is not a confirmed Fed decision")
        assertTrue(a.severity <= 0.36, "commentary carries half the decision's severity: ${a.severity}")
        assertTrue("commentary" in a.rationale)
        // a stated outcome is still a confirmed decision at full severity
        val d = RuleEventAnalyzer.analyze(rules, "E2", listOf(NewsItem("g", "Fed cuts rates by 25 bps", "Reuters", at(13, 49))), at(14, 0), emptyList(), 0.0, 0.0)
        assertEquals(EventStage.CONFIRMED, d.stage)
        assertTrue(d.severity >= 0.7)
    }

    @Test fun executiveIsNotARateCut() {
        val rules = NewsEventEngine()
        fun ev(title: String) = rules.buildEvent(listOf(NewsItem("n", title, "Reuters", at(10, 0))), at(10, 5), emptyList(), 0.0, 0.0)
        val e = ev("RBI monetary policy: executive director speaks on prosecuted fraud cases")
        assertEquals(EventType.RBI_POLICY, e.type)
        assertTrue(e.direction < 0.7, "no 'cut' in the text — direction ${e.direction}")
        assertEquals(0.7, ev("RBI monetary policy: repo rate cut by 25 bps").direction, 1e-9)
        assertNotEquals(EventType.RBI_POLICY, ev("UPI MDR should not attract any GST: Former RBI Governor C Rangarajan").type) // a tax remark
    }

    @Test fun barsFillOutcomesWhenTheLivePathHasAGap() {
        // the app was down 12:57–13:26: the in-memory path has nothing for a 12:30 prediction's 30-min window
        val store = InMemoryPredictionStore()
        val logger = PredictionLogger(store, listOf(30))
        val t0 = at(12, 30)
        store.append(PredictionAudit.seal(PredictionRecord(id = "$t0", timestamp = t0, spot = 22_454.0, pBull = 0.3, pBear = 0.33,
            pRange = 0.37, confidence = "LOW", confidenceValue = 0.3, regime = "RANGE", expectedMove = 30.0, sigma = 60.0,
            decision = "WAIT", source = "live", snapshotId = "X", inputTimestamps = mapOf("NIFTY" to t0), pitToleranceSec = 10.0)))
        val now = at(13, 27)
        val path = listOf(PredictionLogger.PricePoint(at(12, 31), 22_450.0), PredictionLogger.PricePoint(at(13, 26), 22_460.0))
        assertEquals(0, logger.evaluate(path, now), "path alone cannot price the 13:00 outcome")
        val bars = (0 until 60).map { i -> PredictionLogger.PricePoint(at(12, 30) + i * 60_000L + 60_000L, 22_454.0 + i) }.filter { it.t <= now }
        assertEquals(1, logger.evaluate(bars, now))
        val r = store.all().single()
        val o = r.outcomes.single()
        assertEquals(at(13, 0), o.lastPriceAt)
        assertEquals(22_483.0, o.price, 1e-9)
        assertEquals(PredictionAudit.Status.VERIFIED, PredictionAudit.verify(r).status)
    }
}

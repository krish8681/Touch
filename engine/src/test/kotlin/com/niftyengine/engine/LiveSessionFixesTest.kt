package com.niftyengine.engine

import com.niftyengine.engine.core.Session
import com.niftyengine.engine.engines.DataQualityEngine
import com.niftyengine.engine.engines.NewsEventEngine
import com.niftyengine.engine.engines.RuleEventAnalyzer
import com.niftyengine.engine.model.Candle
import com.niftyengine.engine.model.EventStage
import com.niftyengine.engine.model.EventType
import com.niftyengine.engine.model.FeedStatus
import com.niftyengine.engine.model.MarketSnapshot
import com.niftyengine.engine.model.NewsItem
import com.niftyengine.engine.sim.SimulatedMarket
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
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
}

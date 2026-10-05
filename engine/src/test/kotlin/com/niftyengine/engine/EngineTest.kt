package com.niftyengine.engine

import com.niftyengine.engine.core.BlackScholes
import com.niftyengine.engine.core.Session
import com.niftyengine.engine.engines.FuturesState
import com.niftyengine.engine.engines.HistoricalReplayEngine
import com.niftyengine.engine.engines.NewsEventEngine
import com.niftyengine.engine.engines.ReplayMode
import com.niftyengine.engine.model.Candle
import com.niftyengine.engine.model.Decision
import com.niftyengine.engine.model.EventType
import com.niftyengine.engine.model.MarketSnapshot
import com.niftyengine.engine.model.NewsItem
import com.niftyengine.engine.sim.SimulatedMarket
import java.time.LocalDate
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EngineTest {
    private val day = LocalDate.of(2026, 9, 29)

    @Test
    fun fullSimulatedSessionProducesValidOutputs() {
        val sim = SimulatedMarket(seed = 11, startDate = day)
        val engine = NiftyDirectionEngine()
        val decisions = mutableMapOf<Decision, Int>()
        repeat(370) {
            val out = engine.process(sim.collect(0))
            val sum = out.direction.pBull + out.direction.pBear + out.direction.pRange
            assertEquals(1.0, sum, 1e-9)
            assertTrue(!out.direction.pBull.isNaN() && !out.expectedMove.sigmaPoints.isNaN())
            assertTrue(out.expectedMove.sigmaPoints > 0)
            assertTrue(out.heavyweights.rows.size >= 45)
            decisions.merge(out.decision.decision, 1, Int::plus)
        }
        println("decisions over one simulated session: $decisions")
        assertTrue(decisions.isNotEmpty())
    }

    @Test
    fun optionEngineRanksCandidatesOnFavouredSide() {
        val sim = SimulatedMarket(seed = 3, startDate = day)
        val engine = NiftyDirectionEngine()
        var out = engine.process(sim.collect(0))
        repeat(120) { out = engine.process(sim.collect(0)) }
        assertTrue(out.options.candidates.isNotEmpty())
        assertTrue(out.options.candidates.all { it.premium > 0 && it.delta.isFinite() })
        out.options.best?.let { assertEquals(out.options.preferred, it.type) }
    }

    @Test
    fun replayHasNoLookAhead() {
        val sim = SimulatedMarket(seed = 5, startDate = day)
        val snaps = mutableListOf<MarketSnapshot>()
        repeat(200) { snaps += sim.collect(0) }
        val future = NewsItem("f1", "RBI cuts repo rate by 100 bps in surprise move", "Reuters", snaps.last().timestamp + 3_600_000L)
        val replay = HistoricalReplayEngine()
        val pit = replay.pointInTime(snaps[50], ReplayMode.FULL_INFORMATION, listOf(future))
        assertTrue(pit.news.none { it.id == "f1" })
        assertTrue(pit.nifty.intraday.all { it.t <= snaps[50].timestamp })
        val a = replay.run(snaps, ReplayMode.MARKET_ONLY)
        val b = replay.run(snaps, ReplayMode.FULL_INFORMATION)
        assertTrue(a.records.isNotEmpty())
        assertTrue(a.records.any { it.outcomes.isNotEmpty() })
        println("replay A acc30=${a.summaries[1].accuracy} B acc30=${b.summaries[1].accuracy} n=${a.summaries[1].n}")
    }

    @Test
    fun duplicateNewsIsClusteredIntoOneEvent() {
        val t0 = Session.sessionStart(System.currentTimeMillis())
        val items = listOf(
            NewsItem("1", "RBI cuts repo rate by 50 bps vs 25 bps expected", "Reuters", t0),
            NewsItem("2", "RBI cuts repo rate by 50 bps, more than 25 bps expected", "Economic Times", t0 + 60_000),
            NewsItem("3", "Update: RBI cuts repo rate by 50 bps vs 25 bps expected", "Moneycontrol", t0 + 120_000),
            NewsItem("4", "Crude oil prices surge on supply fears", "Livemint", t0 + 180_000),
        )
        val series = (0..30).map { Candle(t0 + it * 60_000L, 25_000.0 + it, 25_000.0 + it, 25_000.0 + it, 25_000.0 + it) }
        val r = NewsEventEngine().analyze(items, t0 + 30 * 60_000L, series, 24_990.0, 25_030.0)
        assertEquals(2, r.events.size)
        val rbi = r.events.first { it.type == EventType.RBI_POLICY }
        assertEquals(3, rbi.sources.size)
        assertTrue(rbi.direction > 0, "rate cut should be bullish")
        assertEquals(25.0, rbi.surprise, 1e-6)
        val crude = r.events.first { it.type == EventType.CRUDE }
        assertTrue(crude.direction < 0, "crude surge should be bearish for India")
        assertTrue(rbi.decay < 1.0 && rbi.decay > 0.5)
    }

    @Test
    fun futuresClassificationTable() {
        assertEquals(FuturesState.LONG_BUILDUP, FuturesState.classify(0.5, 2.0))
        assertEquals(FuturesState.SHORT_BUILDUP, FuturesState.classify(-0.5, 2.0))
        assertEquals(FuturesState.SHORT_COVERING, FuturesState.classify(0.5, -2.0))
        assertEquals(FuturesState.LONG_UNWINDING, FuturesState.classify(-0.5, -2.0))
    }

    @Test
    fun blackScholesPutCallParity() {
        val s = 25_000.0; val k = 25_100.0; val t = 5 / 365.0; val v = 0.13; val r = 0.065
        val c = BlackScholes.price(true, s, k, t, v, r).price
        val p = BlackScholes.price(false, s, k, t, v, r).price
        assertTrue(abs((c - p) - (s - k * Math.exp(-r * t))) < 0.05)
        val iv = BlackScholes.impliedVol(true, s, k, t, c)
        assertEquals(v, iv, 1e-4)
    }
}

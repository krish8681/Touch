package com.niftyengine.engine

import com.niftyengine.engine.core.Session
import com.niftyengine.engine.engines.GiftNiftyEngine
import com.niftyengine.engine.model.Candle
import com.niftyengine.engine.model.Driver
import com.niftyengine.engine.model.FuturesData
import com.niftyengine.engine.model.GapState
import com.niftyengine.engine.model.GiftNiftyData
import com.niftyengine.engine.model.InstrumentData
import com.niftyengine.engine.model.MarketSnapshot
import com.niftyengine.engine.model.NewsItem
import com.niftyengine.engine.sim.SimulatedMarket
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GiftNiftyTest {
    private val day = LocalDate.of(2026, 10, 5) // Monday
    private fun ist(h: Int, m: Int, d: LocalDate = day) = d.atTime(h, m).atZone(Session.IST).toInstant().toEpochMilli()
    private val prevDayClose = ist(15, 30, day.minusDays(3)) // Friday close
    private val engine = GiftNiftyEngine()

    /** Pre-open snapshot built like NSE serves it at 08:45: last = Friday close, previousClose = Thursday close. */
    private fun preOpen(wall: Long, gift: Double, giftAsOf: Long, giftExpiry: String = "27-Oct-2026") = MarketSnapshot(
        timestamp = wall,
        nifty = InstrumentData("NIFTY 50", last = 22421.95, prevClose = 22620.45, asOf = prevDayClose),
        futures = FuturesData("NIFTY FUT", "27-Oct-2026", last = 22520.0, prevClose = 22707.3, openInterest = 294707.0, asOf = prevDayClose),
        giftNifty = GiftNiftyData(gift, 0.0, 0.59, giftExpiry, 80_000.0, giftAsOf),
    )

    @Test fun preOpenImpliedGapUsesSameContractAndLatestClose() {
        val wall = ist(8, 45)
        val r = engine.analyze(preOpen(wall, 22624.5, ist(8, 44)), wall)
        val g = r.report!!
        assertEquals((22624.5 / 22520.0 - 1) * 100, g.impliedGapPct, 1e-9)       // vs Friday futures close, not Thursday's
        assertEquals(22421.95 * (1 + g.impliedGapPct / 100), g.impliedOpen, 1e-6) // applied to Friday spot close
        assertEquals(GapState.PRE_OPEN, g.state)
        assertTrue(r.signal.score > 0 && r.signal.confidence == 0.8)
        assertTrue(!r.impliedOpenForPricing.isNaN())
    }

    @Test fun staleOrMismatchedGiftIsHandledHonestly() {
        val wall = ist(8, 45)
        val stale = engine.analyze(preOpen(wall, 22624.5, ist(6, 30)), wall)
        assertEquals(0.0, stale.signal.confidence)
        assertTrue(stale.report!!.notes.any { it.contains("old") })
        val mismatch = engine.analyze(preOpen(wall, 22624.5, ist(8, 44), giftExpiry = "24-Nov-2026"), wall)
        assertEquals(0.59, mismatch.report!!.impliedGapPct, 1e-9)
        assertTrue(mismatch.report!!.method.contains("mismatch"))
    }

    private fun inSession(minute: Int, open: Double, last: Double, impliedGift: Double): MarketSnapshot {
        val t = ist(9, 15) + minute * 60_000L
        return MarketSnapshot(
            timestamp = t,
            nifty = InstrumentData("NIFTY 50", last = last, prevClose = 22421.95, open = open, asOf = t,
                intraday = listOf(Candle(ist(9, 15), open, open, open, open), Candle(t, last, last, last, last))),
            futures = FuturesData("NIFTY FUT", "27-Oct-2026", last = last + 90, prevClose = 22520.0, openInterest = 3e5, asOf = t),
            giftNifty = GiftNiftyData(impliedGift, 0.0, 0.0, "27-Oct-2026", 0.0, ist(9, 10)), // last pre-open quote
        )
    }

    @Test fun openingGapStatesAndDecay() {
        val gift = 22520.0 * 1.005 // implied +0.5 %
        val open = 22421.95 * 1.005
        val holding = engine.analyze(inSession(10, open, open * 1.0005, gift), ist(9, 25))
        assertEquals(GapState.HOLDING, holding.report!!.state)
        assertTrue(holding.signal.score > 0)
        assertEquals(1.0, holding.report!!.gapRealization, 0.02)
        val filled = engine.analyze(inSession(20, open, 22421.95 * 0.999, gift), ist(9, 35))
        assertEquals(GapState.FILLED, filled.report!!.state)
        assertTrue(filled.signal.score < 0, "a filled gap-up turns the opening factor bearish")
        val extending = engine.analyze(inSession(15, open, open * 1.003, gift), ist(9, 30))
        assertEquals(GapState.EXTENDING, extending.report!!.state)
        assertTrue(extending.signal.confidence < holding.signal.confidence, "confidence decays through the first hour")
        val spent = engine.analyze(inSession(80, open, open, gift), ist(10, 35))
        assertEquals(GapState.SPENT, spent.report!!.state)
        assertEquals(0.0, spent.signal.confidence)
    }

    @Test fun openFarFromGiftIsFlagged() {
        val r = engine.analyze(inSession(2, 22421.95 * 0.99, 22421.95 * 0.99, 22520.0 * 1.004), ist(9, 17))
        assertTrue(r.warnings.any { it.contains("GIFT-implied") })
    }

    @Test fun preOpenGiftMovePricesOvernightNews() {
        val wall = ist(8, 50)
        val news = listOf(NewsItem("ov1", "Missile attack escalates Middle East conflict, crude surges", "Reuters", ist(1, 30)))
        fun run(gift: Double): Double {
            val e = NiftyDirectionEngine()
            val out = e.process(preOpen(wall, gift, ist(8, 49)).copy(news = news))
            assertTrue(out.direction.drivers.any { it.driver == Driver.GIFT_NIFTY && it.confidence > 0 })
            return out.events.first().unpriced
        }
        val alreadyPriced = run(22520.0 * 0.985) // GIFT −1.5 % overnight
        val notPriced = run(22520.0)             // GIFT flat
        println("overnight war news: unpriced with GIFT −1.5%% = %.2f, with GIFT flat = %.2f".format(alreadyPriced, notPriced))
        assertTrue(alreadyPriced < notPriced - 0.3)
    }

    @Test fun simulatorProducesGiftReportsThroughTheSession() {
        val sim = SimulatedMarket(seed = 11, startDate = LocalDate.of(2026, 9, 29))
        val e = NiftyDirectionEngine()
        val states = (0 until 120).map { e.process(sim.collect(0)).gift!!.state }
        assertTrue(states.take(30).none { it == GapState.SPENT })
        assertTrue(states.drop(70).all { it == GapState.SPENT })
        println("sim gap states: " + states.groupingBy { it }.eachCount())
    }
}

class GiftFreezeTest {
    @Test fun impliedGapIsFrozenAtTheOpenNotRecomputedLive() {
        val day = LocalDate.of(2026, 10, 5)
        fun ist(h: Int, m: Int) = day.atTime(h, m).atZone(Session.IST).toInstant().toEpochMilli()
        val e = GiftNiftyEngine()
        val prev = ist(15, 30) - 3 * 86_400_000L
        val pre = MarketSnapshot(ist(9, 5), InstrumentData("NIFTY 50", 22421.95, 22620.45, asOf = prev),
            futures = FuturesData("F", "27-Oct-2026", 22520.0, 22707.3, 3e5, asOf = prev),
            giftNifty = GiftNiftyData(22520.0 * 1.004, 0.0, 0.0, "27-Oct-2026", 0.0, ist(9, 4)))
        assertEquals(0.4, e.analyze(pre, ist(9, 5)).report!!.impliedGapPct, 1e-6)
        // 11:00 — GIFT has run up with the market; the opening expectation must stay +0.40 %.
        val t = ist(11, 0)
        val later = MarketSnapshot(t, InstrumentData("NIFTY 50", 22421.95 * 1.015, 22421.95, open = 22421.95 * 1.004, asOf = t),
            futures = FuturesData("F", "27-Oct-2026", 22520.0 * 1.015, 22520.0, 3e5, asOf = t),
            giftNifty = GiftNiftyData(22520.0 * 1.015, 0.0, 1.5, "27-Oct-2026", 0.0, t))
        val r = e.analyze(later, t).report!!
        assertEquals(0.4, r.impliedGapPct, 1e-6)
        assertTrue(r.method.contains("frozen"))
        // a fresh engine that never saw the pre-open must not invent an implied gap
        assertTrue(GiftNiftyEngine().analyze(later, t).report!!.impliedGapPct.isNaN())
    }
}

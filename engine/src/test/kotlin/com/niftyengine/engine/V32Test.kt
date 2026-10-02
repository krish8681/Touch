package com.niftyengine.engine

import com.niftyengine.engine.core.TransactionCosts
import com.niftyengine.engine.engines.HistoricalReplayEngine
import com.niftyengine.engine.engines.IsoModel
import com.niftyengine.engine.engines.Outcome
import com.niftyengine.engine.engines.PerformanceStats
import com.niftyengine.engine.engines.PredictionRecord
import com.niftyengine.engine.engines.ProbabilityCalibrator
import com.niftyengine.engine.engines.ReplayMode
import com.niftyengine.engine.model.Decision
import com.niftyengine.engine.model.FeedStatus
import com.niftyengine.engine.model.MarketSnapshot
import com.niftyengine.engine.sim.SimulatedMarket
import java.time.LocalDate
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class V32Test {
    private val day = LocalDate.of(2026, 9, 29)

    private fun warmed(seed: Long = 11, minutes: Int = 90): Pair<NiftyDirectionEngine, SimulatedMarket> {
        val sim = SimulatedMarket(seed = seed, startDate = day)
        val e = NiftyDirectionEngine()
        repeat(minutes) { e.process(sim.collect(0)) }
        return e to sim
    }

    @Test fun circuitBreakerOnMissingOptionChain() {
        val (e, sim) = warmed()
        val out = e.process(sim.collect(0).copy(optionChain = null))
        assertEquals(Decision.DATA_ERROR, out.decision.decision)
        assertTrue(out.dataQuality.circuitBreaker.any { it.startsWith("Options missing") })
    }

    @Test fun circuitBreakerOnStaleSpotAndExtremeSpread() {
        val (e, sim) = warmed()
        val s = sim.collect(0)
        val stale = e.process(s.copy(nifty = s.nifty.copy(asOf = s.timestamp - 20 * 60_000L)))
        assertEquals(Decision.DATA_ERROR, stale.decision.decision)
        assertTrue(stale.dataQuality.feeds.first { it.name == "NIFTY" }.status == FeedStatus.STALE)

        val s2 = sim.collect(0)
        val wide = s2.optionChain!!.copy(rows = s2.optionChain!!.rows.map { r ->
            r.copy(call = r.call.copy(bid = r.call.ltp * 0.5, ask = r.call.ltp * 1.5), put = r.put.copy(bid = r.put.ltp * 0.5, ask = r.put.ltp * 1.5))
        })
        val bad = e.process(s2.copy(optionChain = wide))
        assertEquals(Decision.DATA_ERROR, bad.decision.decision)
        assertTrue(bad.dataQuality.circuitBreaker.any { it.contains("spread") })
    }

    @Test fun staleFeedIsRemovedAsDriverAndQualityCapsConfidence() {
        val (e, sim) = warmed()
        val s = sim.collect(0)
        val out = e.process(s.copy(futures = s.futures!!.copy(asOf = s.timestamp - 3_600_000L),
            flows = s.flows!!.copy(asOf = s.timestamp - 20 * 86_400_000L)))
        assertEquals(0.0, out.signals.getValue("Futures").confidence, 1e-12)
        assertTrue("DATA_STALE" in out.signals.getValue("FPI/DII").tags)
        assertTrue(out.direction.confidenceValue <= out.dataQuality.score + 1e-9)
        assertEquals(Decision.DATA_ERROR, out.decision.decision) // futures are critical
    }

    @Test fun macroValuesCarryManualStaleStatus() {
        val (e, sim) = warmed(minutes = 5)
        val s = sim.collect(0)
        val day = 86_400_000L
        val out = e.process(s.copy(macro = s.macro.copy(releasedAt = mapOf("cpiYoY" to s.timestamp - 80 * day, "repoRate" to s.timestamp - 10 * day))))
        val f = out.dataQuality.feeds.associateBy { it.name }
        assertEquals(FeedStatus.STALE, f.getValue("Macro: cpiYoY").status)
        assertEquals(FeedStatus.MANUAL, f.getValue("Macro: repoRate").status)
        assertEquals(FeedStatus.DEGRADED, f.getValue("Macro: gdpGrowth").status) // no release date ⇒ undated
    }

    @Test fun uncalibratedSetupBecomesPaperTradeAndNeedsPersistence() {
        val sim = SimulatedMarket(seed = 11, startDate = day)
        val e = NiftyDirectionEngine()
        val decisions = (0 until 370).map { e.process(sim.collect(0)).decision }
        assertTrue(decisions.none { it.decision == Decision.TRADE }, "uncalibrated engine must not emit TRADE")
        val firstPaper = decisions.indexOfFirst { it.decision == Decision.PAPER_TRADE }
        assertTrue(firstPaper > 0)
        // hysteresis: the cycle before the first paper trade had the same setup for only 1 cycle
        assertTrue(decisions[firstPaper].checks.first { it.name == "Signal persistence" }.passed)
        println("decisions: " + decisions.groupingBy { it.decision }.eachCount())
    }

    @Test fun isotonicCalibrationRecoversTrueFrequencies() {
        // Model is over-confident: true P(bull) = 0.25 + 0.5 * (score - 0.33)
        val rnd = Random(3)
        val recs = (0 until 3000).map { i ->
            val pb = 0.2 + rnd.nextDouble() * 0.7
            val pd = (1 - pb) * 0.5; val pr = 1 - pb - pd
            val trueBull = 0.25 + 0.5 * (pb - 0.33)
            val u = rnd.nextDouble()
            val realized = if (u < trueBull) 1 else if (u < trueBull + (1 - trueBull) / 2) -1 else 0
            PredictionRecord(id = "$i", timestamp = i * 60_000L, spot = 25000.0, pBull = pb, pBear = pd, pRange = pr,
                confidence = "HIGH", confidenceValue = 0.8, regime = "BULL_TREND", expectedMove = 50.0, sigma = 80.0,
                decision = "WAIT", source = "t", outcomes = listOf(Outcome(30, 25000.0, 0.0, 0.0, 0.0, realized = realized)))
        }
        val cal = ProbabilityCalibrator.fit(recs, minSamples = 150)
        assertTrue(30 in cal.horizons && cal.info.calibrated)
        val p = cal.apply(0.83, 0.085, 0.085, 30)
        assertEquals(0.50, p.pBull, 0.07) // raw said 83 %, reality ≈ 50 %
        assertTrue(cal.info.holdoutBrierCalibrated.getValue(30) < cal.info.holdoutBrierRaw.getValue(30))
        val s = PerformanceStats.summarize(recs, 30)
        assertEquals(8, s.buckets.size)
        assertEquals("80%+", s.buckets.last().label)
        // monotone
        val m = IsoModel.fit(listOf(0.1 to 0.0, 0.2 to 1.0, 0.3 to 0.0, 0.4 to 1.0, 0.5 to 1.0), 0.5)
        assertTrue(m.ys.zipWithNext().all { it.first <= it.second })
    }

    @Test fun transactionCostsAreChargedAndNetEvIsLower() {
        val c = TransactionCosts(lotSize = 65, lots = 1)
        val b = c.roundTrip(100.0, 120.0)
        assertEquals(40.0, b.brokerage, 1e-9)
        assertEquals(120.0 * 65 * 0.1 / 100, b.stt, 1e-9)
        assertTrue(c.perUnit(100.0, 120.0) in 0.8..1.5) // ≈ ₹1/unit on a ₹100 option
        val (e, sim) = warmed(minutes = 120)
        val out = e.process(sim.collect(0))
        val cand = out.options.candidates.first()
        assertTrue(cand.costPerUnit > 0 && cand.expectedValue < cand.grossExpectedValue)
        assertEquals(4, out.expectedMove.thresholds.size)
        assertTrue(out.direction.horizons.map { it.minutes } == listOf(5, 15, 30, 60))
    }

    @Test fun walkForwardReplayUsesOnlyPastCalibration() {
        val sessions = (0 until 3).map { k ->
            val sim = SimulatedMarket(seed = 100L + k, startDate = day.minusDays((3 - k).toLong() * 7))
            List<MarketSnapshot>(370) { sim.collect(0) }
        }
        val wf = HistoricalReplayEngine().runWalkForward(sessions, ReplayMode.MARKET_ONLY, minSamples = 60)
        assertEquals(3, wf.sessions)
        // first session's records were made before any calibration existed
        val firstDay = wf.result.records.minOf { it.timestamp }
        assertTrue(wf.result.records.filter { it.timestamp < firstDay + 6 * 3_600_000L }.none { it.calibrated })
        assertTrue(wf.result.records.any { it.calibrated }, "later sessions should use the earlier fit")
        assertTrue(wf.result.records.all { it.engineVersion == ENGINE_VERSION && it.drivers.isNotEmpty() })
        println("walk-forward: ${wf.finalCalibration.info.note} brier raw=${wf.finalCalibration.info.holdoutBrierRaw} cal=${wf.finalCalibration.info.holdoutBrierCalibrated}")
    }
}

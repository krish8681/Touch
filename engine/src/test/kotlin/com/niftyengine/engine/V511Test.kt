package com.niftyengine.engine

import com.niftyengine.engine.core.Session
import com.niftyengine.engine.engines.InMemoryPredictionStore
import com.niftyengine.engine.engines.PointInTimeValidator
import com.niftyengine.engine.engines.PredictionAudit
import com.niftyengine.engine.engines.PredictionLogger
import com.niftyengine.engine.engines.PredictionRecord
import com.niftyengine.engine.engines.ProbabilityCalibrator
import com.niftyengine.engine.model.Decision
import com.niftyengine.engine.model.EngineOutput
import com.niftyengine.engine.model.FeedStatus
import com.niftyengine.engine.sim.SimulatedMarket
import kotlinx.serialization.json.Json
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * v5.1.1 — the five essential integrity fixes: point-in-time validation, future-timestamp rejection, one frozen decision
 * snapshot, the hard critical-data gate, and the prediction/outcome audit trail.
 */
class V511Test {
    private val day = LocalDate.of(2026, 9, 29)

    /** An engine and a simulator warmed up to late morning, so every engine has history. */
    private fun warm(seed: Long = 21, cycles: Int = 120): Pair<NiftyDirectionEngine, SimulatedMarket> {
        val eng = NiftyDirectionEngine(EngineConfig(requireMarketOpen = false))
        val sim = SimulatedMarket(seed = seed, startDate = day)
        repeat(cycles) { eng.process(sim.collect(0)) }
        return eng to sim
    }

    private fun EngineOutput.feed(name: String) = dataQuality.feeds.firstOrNull { it.name == name }

    private fun assertNoTrade(o: EngineOutput, why: String) {
        assertEquals(Decision.DATA_ERROR, o.decision.decision, "$why: ${o.dataQuality.circuitBreaker}")
        assertNull(o.strategy.chosen, "$why: a strategy was selected")
        assertEquals(0, o.risk.lots, "$why: something was sized")
        assertEquals("–", o.decisionState.instrument, "$why: an instrument was named")
        assertFalse(o.decisionState.criticalData == "OK", "$why: decision state says critical data OK")
        assertTrue(o.shadow.events.none { it.startsWith("opened") }, "$why: the shadow trader opened a position: ${o.shadow.events}")
    }

    // ------------------------------------------------------------------ 1/2 — point-in-time, future timestamps

    @Test fun futureDatedNiftyQuoteIsDataErrorNotFresh() {
        val (eng, sim) = warm()
        val s = sim.collect(0)
        val o = eng.process(s.copy(nifty = s.nifty.copy(asOf = s.timestamp + 5 * 60_000L)))
        val f = assertNotNull(o.feed("NIFTY"))
        assertEquals(FeedStatus.FUTURE, f.status, f.toString())
        assertTrue(f.ageSeconds < 0, "age of a future quote must be negative, never clamped to 0: ${f.ageSeconds}")
        assertTrue(o.dataQuality.circuitBreaker.any { "NIFTY FUTURE DATA" in it }, o.dataQuality.circuitBreaker.toString())
        assertTrue(o.pointInTime.criticalViolations.any { it.input == "NIFTY" })
        assertNoTrade(o, "future NIFTY")
    }

    @Test fun futureDatedFuturesOrOptionChainIsRejectedAndBlocksTrading() {
        val (eng, sim) = warm(seed = 22)
        val s1 = sim.collect(0)
        val o1 = eng.process(s1.copy(futures = s1.futures!!.copy(asOf = s1.timestamp + 120_000L)))
        assertEquals(FeedStatus.FUTURE, o1.feed("Futures")?.status, o1.dataQuality.feeds.toString())
        assertNoTrade(o1, "future futures")

        val s2 = sim.collect(0)
        val o2 = eng.process(s2.copy(optionChain = s2.optionChain!!.copy(asOf = s2.timestamp + 120_000L)))
        assertEquals(FeedStatus.FUTURE, o2.feed("Options")?.status, o2.dataQuality.feeds.toString())
        assertTrue(o2.options.candidates.isEmpty(), "a rejected chain must not be priced")
        assertNoTrade(o2, "future option chain")
        // the validator removed it before any engine saw it
        assertNull(eng.pointInTime(s2.copy(optionChain = s2.optionChain!!.copy(asOf = s2.timestamp + 120_000L))).optionChain)
    }

    @Test fun clockSkewWithinToleranceIsAcceptedAndReported() {
        val (eng, sim) = warm(seed = 23)
        val s = sim.collect(0)
        val o = eng.process(s.copy(nifty = s.nifty.copy(asOf = s.timestamp + 4_000L)))
        val f = assertNotNull(o.feed("NIFTY"))
        assertEquals(FeedStatus.LIVE, f.status)
        assertTrue("clock skew" in f.detail, f.detail)
        assertTrue(o.pointInTime.violations.isEmpty())
        assertEquals(4.0, o.pointInTime.maxAcceptedSkewSec, 1e-9)
        assertTrue(o.dataQuality.circuitBreaker.none { "NIFTY" in it }, o.dataQuality.circuitBreaker.toString())
    }

    @Test fun futureDatedNonCriticalInputsAreRemovedNotUsed() {
        val (eng, sim) = warm(seed = 24)
        val s = sim.collect(0)
        val vixAhead = s.vix!!.copy(asOf = s.timestamp + 10 * 60_000L)
        val macroAhead = s.macro.copy(cpiYoY = 99.0, releasedAt = s.macro.releasedAt + ("cpiYoY" to s.timestamp + 86_400_000L))
        val clean = eng.pointInTime(s.copy(vix = vixAhead, macro = macroAhead))
        assertNull(clean.vix, "future-dated VIX must be removed")
        assertTrue(clean.macro.cpiYoY.isNaN(), "a macro value released after the decision time did not exist yet")
        val o = eng.process(s.copy(vix = vixAhead, macro = macroAhead))
        assertEquals(FeedStatus.FUTURE, o.feed("India VIX")?.status, o.dataQuality.feeds.toString())
        assertTrue(o.dataQuality.feeds.any { it.name == "Macro: cpiYoY" && it.status == FeedStatus.FUTURE })
        assertTrue(o.dataQuality.circuitBreaker.none { "VIX" in it || "Macro" in it }, "non-critical inputs do not trip the breaker")
        assertTrue(o.pointInTime.violations.all { !it.critical })
    }

    @Test fun validatorDropsEveryFutureStampedItem() {
        val (_, sim) = warm(seed = 25, cycles = 30)
        val s = sim.collect(0)
        val t = s.timestamp
        val f = s.futures!!
        val bad = s.copy(
            nifty = s.nifty.copy(intraday = s.nifty.intraday + com.niftyengine.engine.model.Candle(t + 60_000L, 1.0, 1.0, 1.0, 1.0)),
            futures = f.copy(intraday = f.intraday + com.niftyengine.engine.model.FuturesBar(t + 60_000L, f.last, f.openInterest)),
            giftNifty = s.giftNifty?.copy(asOf = t + 3_600_000L),
            flows = s.flows?.copy(asOf = t + 3_600_000L),
        )
        val r = PointInTimeValidator().validate(bad)
        assertTrue(r.snapshot.nifty.intraday.all { it.t <= t })
        assertTrue(r.snapshot.futures!!.intraday.all { it.t <= t })
        if (s.giftNifty != null) assertNull(r.snapshot.giftNifty)
        if (s.flows != null) assertNull(r.snapshot.flows)
        assertTrue(r.report.violations.isNotEmpty() && r.report.criticalViolations.isEmpty())
        assertTrue(r.report.violations.all { it.aheadSec > 0 })
    }

    // ------------------------------------------------------------------ 3 — one frozen snapshot

    @Test fun criticalInputsFromDifferentMomentsAreRejected() {
        val (eng, sim) = warm(seed = 26)
        val s = sim.collect(0)
        assertTrue(Session.isOpen(s.timestamp))
        // chain 200 s older than the underlying: each is "fresh" on its own (limit 240 s) but they are not one moment
        val o = eng.process(s.copy(optionChain = s.optionChain!!.copy(asOf = s.timestamp - 200_000L)))
        assertTrue(o.dataQuality.circuitBreaker.any { it.startsWith("Inconsistent snapshot") }, o.dataQuality.circuitBreaker.toString())
        assertNoTrade(o, "mismatched chain")
        assertEquals(200.0, o.snapshot.criticalSkewSec, 1e-9)
        // 60 s apart is one moment
        val s2 = sim.collect(0)
        val o2 = eng.process(s2.copy(optionChain = s2.optionChain!!.copy(asOf = s2.timestamp - 60_000L)))
        assertTrue(o2.dataQuality.circuitBreaker.none { it.startsWith("Inconsistent snapshot") })
    }

    @Test fun decisionCarriesItsFrozenSnapshot() {
        val (eng, sim) = warm(seed = 27)
        val s = sim.collect(0)
        val o = eng.process(s)
        val snap = o.snapshot
        assertEquals(s.timestamp, snap.decisionTime)
        assertEquals(o.timestamp, snap.decisionTime)
        assertTrue(snap.snapshotId.length == 8 && snap.snapshotId == o.decisionState.snapshotId)
        assertTrue(snap.inputTimes.values.all { it <= snap.decisionTime }, snap.inputTimes.toString())
        assertEquals(s.optionChain!!.asOf, snap.inputTimes["Options"])
        assertEquals("OK", snap.criticalData)
        assertEquals(eng.calibration.info.fittedAt, snap.calibrationFittedAt)
        // same inputs ⇒ same id; a different input timestamp ⇒ a different snapshot
        val (eng2, sim2) = warm(seed = 27)
        val o2 = eng2.process(sim2.collect(0))
        assertEquals(snap.snapshotId, o2.snapshot.snapshotId)
        val (eng3, sim3) = warm(seed = 27)
        val s3 = sim3.collect(0)
        val o3 = eng3.process(s3.copy(optionChain = s3.optionChain!!.copy(asOf = s3.timestamp - 30_000L)))
        assertFalse(snap.snapshotId == o3.snapshot.snapshotId)
    }

    // ------------------------------------------------------------------ 4 — hard critical-data gate

    @Test fun missingCriticalInputIsNeverNeutral() {
        val (eng, sim) = warm(seed = 28)
        val s = sim.collect(0)
        val o = eng.process(s.copy(optionChain = null))
        assertEquals(FeedStatus.MISSING, o.feed("Options")?.status)
        assertNoTrade(o, "missing chain")
        val s2 = sim.collect(0)
        val o2 = eng.process(s2.copy(futures = null))
        assertNoTrade(o2, "missing futures")
        val s3 = sim.collect(0)
        val o3 = eng.process(s3.copy(nifty = s3.nifty.copy(last = Double.NaN)))
        assertEquals(FeedStatus.INVALID, o3.feed("NIFTY")?.status)
        assertNoTrade(o3, "invalid NIFTY")
    }

    @Test fun noVolatilityInputMeansNoEstimateAndNoTrade() {
        // a fresh engine: no tick memory, so no realised volatility either
        val eng = NiftyDirectionEngine(EngineConfig(requireMarketOpen = false))
        val sim = SimulatedMarket(seed = 29, startDate = day)
        repeat(120) { sim.collect(0) }
        val s = sim.collect(0)
        val noIv = s.optionChain!!.copy(rows = s.optionChain!!.rows.map { r ->
            r.copy(call = r.call.copy(iv = Double.NaN), put = r.put.copy(iv = Double.NaN)) })
        // NIFTY with no history (no realised / historical vol), no VIX, chain without IV — and no IV can be implied either
        val o = eng.process(s.copy(vix = null, nifty = s.nifty.copy(intraday = emptyList(), daily = emptyList()),
            optionChain = noIv.copy(rows = noIv.rows.map { r -> r.copy(call = r.call.copy(ltp = 0.0, bid = 0.0, ask = 0.0), put = r.put.copy(ltp = 0.0, bid = 0.0, ask = 0.0)) })))
        assertEquals(0, o.expectedMove.volInputs)
        assertTrue(o.dataQuality.circuitBreaker.any { "No volatility input" in it }, o.dataQuality.circuitBreaker.toString())
        assertNoTrade(o, "no volatility")
    }

    // ------------------------------------------------------------------ 5 — prediction / outcome audit trail

    private val json = Json { allowSpecialFloatingPointValues = true }

    private fun session(seed: Long = 31): List<PredictionRecord> {
        val eng = NiftyDirectionEngine(EngineConfig(requireMarketOpen = false))
        val sim = SimulatedMarket(seed = seed, startDate = day)
        val store = InMemoryPredictionStore()
        val logger = PredictionLogger(store)
        val path = ArrayList<PredictionLogger.PricePoint>()
        repeat(300) { i ->
            val o = eng.process(sim.collect(0))
            path += PredictionLogger.PricePoint(o.timestamp, o.spot)
            if (i >= 60 && i % 5 == 0 && Session.isOpen(o.timestamp)) logger.record(o)
            logger.evaluate(path, o.timestamp)
        }
        return store.all()
    }

    @Test fun everyLoggedPredictionIsCompleteAndVerifies() {
        val recs = session()
        assertTrue(recs.size > 30)
        assertTrue(recs.any { it.outcomes.isNotEmpty() })
        for (r in recs) {
            assertTrue(r.auditHash.isNotBlank() && r.snapshotId.isNotBlank(), "record ${r.id} not sealed")
            assertEquals(r.timestamp, r.id.toLong())
            assertTrue(r.inputTimestamps.isNotEmpty() && r.inputTimestamps.values.all { it <= r.timestamp })
            assertTrue(!r.pBull.isNaN() && !r.usedPBull.isNaN() && r.calibrationLevel.isNotBlank())
            assertTrue(r.primaryRegime.isNotBlank() && r.scenarioProbs.size == 5 && r.strategy.isNotBlank())
            assertTrue(!r.modelHealth.isNaN() && r.healthTier.isNotBlank() && r.decision.isNotBlank() && r.criticalData.isNotBlank())
            for (o in r.outcomes) {
                assertEquals(r.timestamp + o.minutes * 60_000L, o.windowEnd)
                assertTrue(o.attachedAt >= o.windowEnd, "outcome attached before its horizon expired")
                assertTrue(o.lastPriceAt > r.timestamp && o.lastPriceAt <= o.windowEnd)
            }
            val v = PredictionAudit.verify(r)
            assertEquals(PredictionAudit.Status.VERIFIED, v.status, "${r.id}: ${v.problems}")
            // survives the store's JSON round trip unchanged
            val back = json.decodeFromString(PredictionRecord.serializer(), json.encodeToString(PredictionRecord.serializer(), r))
            assertEquals(PredictionAudit.Status.VERIFIED, PredictionAudit.verify(back).status)
        }
    }

    @Test fun tamperedOrPrematureRecordsAreExcludedFromCalibration() {
        val recs = session(seed = 32).filter { it.outcomes.isNotEmpty() }
        assertTrue(recs.size >= 4)
        val edited = recs[0].copy(pBull = recs[0].pBull + 0.1)                            // probability edited afterwards
        val early = recs[1].copy(outcomes = recs[1].outcomes.map { it.copy(attachedAt = it.windowEnd - 60_000L) })
        val wrongWindow = recs[2].copy(outcomes = recs[2].outcomes.map { it.copy(lastPriceAt = it.windowEnd + 60_000L) })
        val relabelled = recs[3].copy(outcomes = recs[3].outcomes.map { it.copy(realized = if (it.realized == 1) -1 else 1) })
        for ((r, why) in listOf(edited to "hash", early to "attached before", wrongWindow to "outside", relabelled to "class")) {
            val v = PredictionAudit.verify(r)
            assertEquals(PredictionAudit.Status.INVALID, v.status, "$why: ${v.problems}")
            assertTrue(v.problems.any { why in it }, "$why: ${v.problems}")
        }
        val all = listOf(edited, early, wrongWindow, relabelled) + recs.drop(4)
        assertEquals(recs.size - 4, PredictionAudit.usable(all).size)
        val fit = ProbabilityCalibrator.fit(all, minSamples = 1_000)
        assertEquals(4, fit.info.auditExcluded)
        assertTrue("excluded by the audit" in fit.info.note)
    }

    @Test fun dataErrorCyclesAreAuditedButNeverCalibratedOn() {
        val (eng, sim) = warm(seed = 33)
        val logger = PredictionLogger(InMemoryPredictionStore())
        val s = sim.collect(0)
        val r = logger.record(eng.process(s.copy(optionChain = null)))
        assertEquals("DATA_ERROR", r.decision)
        assertFalse(r.criticalData == "OK")
        assertEquals(PredictionAudit.Status.VERIFIED, PredictionAudit.verify(r).status, "kept, sealed, verifiable")
        assertTrue(PredictionAudit.usable(listOf(r)).isEmpty(), "but never used for calibration")
        assertEquals(1, PredictionAudit.counts(listOf(r)).dataError)
    }

    @Test fun outcomeIsNeverAttachedBeforeTheHorizonExpires() {
        val (eng, sim) = warm(seed = 34)
        val logger = PredictionLogger(InMemoryPredictionStore(), listOf(15))
        val o = eng.process(sim.collect(0))
        val r = logger.record(o)
        // a complete price path exists, but the clock says the horizon has not expired yet
        val path = (1..15).map { PredictionLogger.PricePoint(r.timestamp + it * 60_000L, r.spot + it) }
        assertNull(logger.outcomeFor(r, 15, path, now = r.timestamp + 14 * 60_000L))
        val out = assertNotNull(logger.outcomeFor(r, 15, path, now = r.timestamp + 15 * 60_000L))
        assertEquals(r.timestamp + 15 * 60_000L, out.attachedAt)
        assertEquals(15, out.points)
        assertEquals(PredictionAudit.Status.VERIFIED, PredictionAudit.verify(r.copy(outcomes = listOf(out))).status)
    }

    @Test fun legacyRecordsWithoutAuditFieldsStayUsable() {
        val legacy = PredictionRecord(id = "1", timestamp = 1_000L, spot = 25_000.0, pBull = 0.5, pBear = 0.3, pRange = 0.2,
            confidence = "LOW", confidenceValue = 0.3, regime = "RANGE", expectedMove = 10.0, sigma = 30.0, decision = "WAIT", source = "old",
            outcomes = listOf(com.niftyengine.engine.engines.Outcome(30, 25_010.0, 10.0, 25_020.0, 24_990.0, realized = 0)))
        assertEquals(PredictionAudit.Status.LEGACY, PredictionAudit.verify(legacy).status)
        assertEquals(1, PredictionAudit.usable(listOf(legacy)).size)
    }
}

package com.niftyengine.engine

import com.niftyengine.engine.core.BlackScholes
import com.niftyengine.engine.core.ExpiryCalendar
import com.niftyengine.engine.core.Fresh
import com.niftyengine.engine.core.PriceDistribution
import com.niftyengine.engine.core.Session
import com.niftyengine.engine.core.TransactionCosts
import com.niftyengine.engine.engines.ConfidenceEngine
import com.niftyengine.engine.engines.EventRiskEngine
import com.niftyengine.engine.engines.ExpectationEngine
import com.niftyengine.engine.engines.FuturesState
import com.niftyengine.engine.engines.HistoricalReplayEngine
import com.niftyengine.engine.engines.HorizonEngine
import com.niftyengine.engine.engines.HorizonOutcome
import com.niftyengine.engine.engines.HorizonRecord
import com.niftyengine.engine.engines.IsoModel
import com.niftyengine.engine.engines.NewsEventEngine
import com.niftyengine.engine.engines.PerformanceStats
import com.niftyengine.engine.engines.PredictionRecord
import com.niftyengine.engine.engines.ProbabilityCalibrator
import com.niftyengine.engine.engines.ProbabilityEngine
import com.niftyengine.engine.engines.ReplayMode
import com.niftyengine.engine.model.CalendarCategory
import com.niftyengine.engine.model.Candle
import com.niftyengine.engine.model.ConfidenceLevel
import com.niftyengine.engine.model.Decision
import com.niftyengine.engine.model.Direction
import com.niftyengine.engine.model.EarningsInputs
import com.niftyengine.engine.model.EngineOutput
import com.niftyengine.engine.model.EventRiskLevel
import com.niftyengine.engine.model.EventRiskReport
import com.niftyengine.engine.model.EventType
import com.niftyengine.engine.model.ExpectationChannel
import com.niftyengine.engine.model.Factor
import com.niftyengine.engine.model.FeedStatus
import com.niftyengine.engine.model.HorizonGroup
import com.niftyengine.engine.model.HorizonId
import com.niftyengine.engine.model.HorizonPrediction
import com.niftyengine.engine.model.LegAction
import com.niftyengine.engine.model.MacroInputs
import com.niftyengine.engine.model.MarketRegime
import com.niftyengine.engine.model.MarketSnapshot
import com.niftyengine.engine.model.NewsItem
import com.niftyengine.engine.model.OptionType
import com.niftyengine.engine.model.ScheduledEvent
import com.niftyengine.engine.model.StrategyKind
import com.niftyengine.engine.sim.SimulatedMarket
import java.time.LocalDate
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ThreeHorizonTest {
    private val day = LocalDate.of(2026, 9, 29)

    private fun warmed(seed: Long = 11, minutes: Int = 90): Triple<NiftyDirectionEngine, SimulatedMarket, EngineOutput> {
        val sim = SimulatedMarket(seed = seed, startDate = day)
        val e = NiftyDirectionEngine()
        var out = e.process(sim.collect(0))
        repeat(minutes - 1) { out = e.process(sim.collect(0)) }
        return Triple(e, sim, out)
    }

    private fun summary(o: EngineOutput) = buildString {
        appendLine("NIFTY %,.1f (%+.2f%%) · regime ${o.regime.regime.label} · weekly ${o.weekly?.regime?.label} · monthly ${o.monthly?.regime?.label}".format(o.spot, o.spotChangePct))
        o.horizons.forEach { h ->
            appendLine("  %-9s %s %-8s %3.0f%%  score %+6.1f  σ %5.0f  range %,.0f–%,.0f  cov %.0f%%  conf %s".format(
                h.id.short, h.direction.arrow, h.direction.label, h.probability * 100, h.score, h.sigmaPts, h.rangeLow, h.rangeHigh, h.coverage * 100, h.confidence))
        }
        appendLine("  master ${o.master.direction} %.0f%% · ${o.master.alignmentLabel} · conf ${o.master.confidence} · event risk ${o.master.eventRisk}".format(o.master.probability * 100))
        appendLine("  best: ${o.strategies.best?.title} score %.3f · ${o.decision.headline}".format(o.strategies.best?.score ?: 0.0))
    }

    @Test fun fullSimulatedSessionProducesConsistentSixHorizonOutput() {
        val sim = SimulatedMarket(seed = 11, startDate = day)
        val engine = NiftyDirectionEngine()
        val decisions = mutableMapOf<Decision, Int>()
        val regimes = mutableMapOf<MarketRegime, Int>()
        repeat(370) { i ->
            val out = engine.process(sim.collect(0))
            assertEquals(HorizonId.values().toList(), out.horizons.map { it.id })
            for (h in out.horizons) {
                assertEquals(1.0, h.pBull + h.pNeutral + h.pBear, 1e-6, "${h.id} probabilities")
                assertTrue(h.sigmaPts > 0 && !h.score.isNaN(), "${h.id} σ/score")
                assertTrue(h.rangeLow < h.expectedPrice && h.expectedPrice < h.rangeHigh, "${h.id} range")
                assertTrue(h.range90Low <= h.rangeLow && h.range90High >= h.rangeHigh)
                assertEquals(1.0, h.buckets.sumOf { it.p }, 1e-6, "${h.id} buckets")
                assertEquals(7, h.buckets.size)
                val used = h.factors.filter { it.reading.available }.sumOf { it.usedWeight }
                if (h.factors.any { it.reading.available }) assertEquals(100.0, used, 1e-6, "${h.id} used weights renormalise to 100")
                assertTrue(h.factors.filter { !it.reading.available }.all { it.usedWeight == 0.0 && it.effective == 0.0 })
                assertEquals(h.score, (h.factors.sumOf { it.effective } + h.overlay).coerceIn(-100.0, 100.0), 1e-6)
            }
            assertTrue(abs(out.horizon(HorizonId.MONTHLY)!!.overlay) <= HorizonEngine.MONTHLY_OPTIONS_CAP + 1e-9)
            assertTrue(out.master.alignment in 0..3)
            val wk = out.horizon(HorizonId.WEEKLY)!!; val mo = out.horizon(HorizonId.MONTHLY)!!
            if (wk.expiry == mo.expiry) assertEquals(wk.sigmaPts, mo.sigmaPts, 1e-6) else assertTrue(wk.sigmaPts < mo.sigmaPts, "monthly range wider than weekly")
            assertTrue(out.horizon(HorizonId.M30)!!.sigmaPts < out.horizon(HorizonId.M180)!!.sigmaPts || Session.minutesToClose(out.timestamp) < 180)
            decisions.merge(out.decision.decision, 1, Int::plus)
            regimes.merge(out.regime.regime, 1, Int::plus)
            if (i == 120 || i == 300) print(summary(out))
        }
        println("decisions: $decisions\nregimes: $regimes")
        assertTrue(decisions.keys.none { it == Decision.TRADE }, "uncalibrated engine must never emit TRADE")
    }

    @Test fun weightTablesMatchSpecAndRegimeAdaptationIsBounded() {
        for ((h, t) in HorizonEngine.BASE) assertEquals(100.0, t.values.sum(), 1e-9, "$h base weights")
        assertEquals(18.0, HorizonEngine.BASE.getValue(HorizonId.WEEKLY).getValue(Factor.FII))
        assertEquals(18.0, HorizonEngine.BASE.getValue(HorizonId.WEEKLY).getValue(Factor.OPTIONS))
        assertEquals(20.0, HorizonEngine.BASE.getValue(HorizonId.MONTHLY).getValue(Factor.EARNINGS))
        assertEquals(20.0, HorizonEngine.BASE.getValue(HorizonId.M30).getValue(Factor.PRICE_STRUCTURE))
        assertFalse(HorizonEngine.BASE.getValue(HorizonId.MONTHLY).containsKey(Factor.OPTIONS), "monthly OI must not be a core H3 factor")
        for (r in MarketRegime.values()) for (h in HorizonId.values()) {
            val base = HorizonEngine.BASE.getValue(h); val adj = HorizonEngine.adjustedWeights(h, r)
            assertEquals(100.0, adj.values.sum(), 1e-6)
            for ((f, b) in base) assertTrue(adj.getValue(f) in b * 0.55..b * 1.6, "$r $h $f ${adj[f]} vs base $b")
        }
        // §28 example: FII 18 % → ~22 % in risk-off, ~15 % in range, ~12 % in earnings regimes.
        assertEquals(22.0, HorizonEngine.adjustedWeights(HorizonId.WEEKLY, MarketRegime.RISK_OFF).getValue(Factor.FII), 2.0)
        assertEquals(15.0, HorizonEngine.adjustedWeights(HorizonId.WEEKLY, MarketRegime.RANGE_COMPRESSION).getValue(Factor.FII), 1.5)
        assertEquals(12.0, HorizonEngine.adjustedWeights(HorizonId.WEEKLY, MarketRegime.EARNINGS_EXPANSION).getValue(Factor.FII), 1.5)
    }

    @Test fun missingFactorIsRemovedNotTreatedAsNeutral() {
        val (e, sim, _) = warmed()
        val s = sim.collect(0)
        val out = e.process(s.copy(flows = null, fiiDerivatives = null))
        for (h in out.horizons) {
            val fii = h.factors.firstOrNull { it.factor == Factor.FII } ?: continue
            assertFalse(fii.reading.available, "${h.id} FII should be unavailable")
            assertEquals(0.0, fii.usedWeight)
            assertTrue(h.coverage <= 1.0 - fii.adjustedWeight / 100 + 1e-9, "${h.id} coverage reduced")
            assertTrue(h.missing.any { it.startsWith(Factor.FII.label) })
        }
        val w = out.horizon(HorizonId.WEEKLY)!!
        // remaining weights renormalised: options (18 of the remaining 82) → ~22 %
        val opt = w.factors.first { it.factor == Factor.OPTIONS }
        assertTrue(opt.usedWeight > opt.adjustedWeight)
        assertTrue(out.fii.available.not())
    }

    @Test fun freshnessDependsOnHorizonAndCadence() {
        val d = 86_400.0
        assertEquals(1.0, Fresh.of(30.0, Fresh.Cadence.LIVE, HorizonId.M30))
        assertTrue(Fresh.of(20 * 60.0, Fresh.Cadence.LIVE, HorizonId.M30) < 0.1)
        assertTrue(Fresh.of(20 * 60.0, Fresh.Cadence.LIVE, HorizonId.WEEKLY) > 0.95)
        // a 3-trading-day-old FII print: fading for 30 minutes, still fine for the monthly view
        assertTrue(Fresh.of(3 * d, Fresh.Cadence.DAILY, HorizonId.M30) < 0.3)
        assertEquals(1.0, Fresh.of(3 * d, Fresh.Cadence.DAILY, HorizonId.MONTHLY))
        val fri = LocalDate.of(2026, 10, 2); val mon = LocalDate.of(2026, 10, 5)
        assertEquals(1, Fresh.tradingDaysBetween(fri, mon))
        // unknown timestamp ⇒ degraded, not fresh
        assertEquals(0.7, Fresh.of(Double.NaN, Fresh.Cadence.LIVE, HorizonId.M60))
    }

    @Test fun staleFiiDataReducesItsFreshnessOnShortHorizons() {
        val (e, sim, _) = warmed()
        val s = sim.collect(0)
        val old = s.timestamp - 6 * 86_400_000L
        val out = e.process(s.copy(flows = s.flows!!.copy(asOf = old), fiiDerivatives = s.fiiDerivatives!!.copy(asOf = old)))
        val m30 = out.horizon(HorizonId.M30)!!.factors.first { it.factor == Factor.FII }.reading
        val mo = out.horizon(HorizonId.MONTHLY)!!.factors.first { it.factor == Factor.FII }.reading
        println("FII freshness 30m=%.2f monthly=%.2f".format(m30.freshness, mo.freshness))
        assertTrue(!m30.available || m30.freshness < 0.2)
        assertTrue(mo.available && mo.freshness > 0.5)
    }

    @Test fun expectationEngineScoresSurpriseNotGoodOrBadNews() {
        val now = Session.closeOf(day)
        val d = 86_400_000L
        fun run(expected: Double, actual: Double) = ExpectationEngine.analyze(emptyList(),
            MacroInputs(lastPolicyChangeBps = actual, policyExpectedChangeBps = expected, releasedAt = mapOf("lastPolicyChangeBps" to now - 2 * d)),
            EarningsInputs(), now)
        val surpriseCut = run(0.0, -25.0)
        val rec = surpriseCut.records.first { it.channel == ExpectationChannel.RBI_RATES }
        assertEquals("Positive", rec.surpriseLabel)
        assertEquals(Direction.BULLISH, rec.interpretation)
        assertEquals("High", rec.persistence)
        assertTrue(surpriseCut.channels.getValue(ExpectationChannel.RBI_RATES).getValue(HorizonGroup.H3) > 0.3)
        val expectedCut = run(-25.0, -25.0)
        assertEquals("In line", expectedCut.records.first().surpriseLabel)
        assertTrue(expectedCut.channels[ExpectationChannel.RBI_RATES] == null, "an expected cut carries no new information")
        // earnings: expected +20 %, actual +12 % ⇒ negative surprise / bearish
        val eps = ExpectationEngine.analyze(emptyList(), MacroInputs(), EarningsInputs(epsGrowthExpected = 20.0, epsGrowthActual = 12.0, asOf = now - d), now)
        assertEquals("Negative", eps.records.first().surpriseLabel)
        assertEquals(Direction.BEARISH, eps.records.first().interpretation)
    }

    private fun fakeHorizon(id: HorizonId, bull: Double, bear: Double): HorizonPrediction {
        val dir = when { bull - bear > 0.05 -> Direction.BULLISH; bear - bull > 0.05 -> Direction.BEARISH; else -> Direction.NEUTRAL }
        return HorizonPrediction(id, 0L, 60.0, 25000.0, 0.0, 1.0, emptyList(), emptyList(), sigmaPts = 100.0, driftPts = 0.0, neutralBand = 25.0,
            annualVolUsed = 0.13, volSources = emptyList(), components = emptyList(), pBull = bull, pNeutral = 1 - bull - bear, pBear = bear,
            calibrated = false, calPBull = Double.NaN, calPNeutral = Double.NaN, calPBear = Double.NaN, direction = dir,
            probability = maxOf(bull, bear), expectedPrice = 25000.0, rangeLow = 24900.0, rangeHigh = 25100.0, range90Low = 24800.0,
            range90High = 25200.0, buckets = emptyList(), confidenceValue = 0.7, confidence = ConfidenceLevel.HIGH)
    }

    @Test fun conflictingHorizonsGiveLowConfidenceEvenWithHighProbabilities() {
        // §21 example: H1 bullish 70 %, H2 bearish 60 %, H3 bullish 65 % ⇒ LOW
        val hs = listOf(HorizonId.M30, HorizonId.M60, HorizonId.M180, HorizonId.CLOSE).map { fakeHorizon(it, 0.70, 0.15) } +
            fakeHorizon(HorizonId.WEEKLY, 0.25, 0.60) + fakeHorizon(HorizonId.MONTHLY, 0.65, 0.20)
        val m = ConfidenceEngine.master(hs, EventRiskReport(), 1.0)
        assertEquals(Direction.BULLISH, m.direction)
        assertEquals(2, m.alignment)
        assertEquals(ConfidenceLevel.LOW, m.confidence)
        // all aligned ⇒ 3/3 and not LOW
        val aligned = hs.map { if (it.id == HorizonId.WEEKLY) fakeHorizon(HorizonId.WEEKLY, 0.66, 0.14) else it }
        val m2 = ConfidenceEngine.master(aligned, EventRiskReport(), 1.0)
        assertEquals(3, m2.alignment)
        assertTrue(m2.confidence != ConfidenceLevel.LOW)
        assertTrue(m2.alignmentLabel.startsWith("3/3"))
    }

    @Test fun distributionGivesBucketsRangesAndPinMass() {
        val d = PriceDistribution.build(25_000.0, 350.0, 80.0)
        assertEquals(1.0, d.grid.sumOf { it.second }, 1e-6)
        assertEquals(25_080.0, d.quantile(0.5), 5.0)
        assertEquals(350.0, d.sigmaPts, 15.0)
        val b = ProbabilityEngine.buckets(d, 25_000.0, 350.0)
        assertEquals(7, b.size)
        assertEquals("24,900–25,100", b[3].label)
        assertEquals(1.0, b.sumOf { it.p }, 1e-9)
        // pin component concentrates probability near the pin strike
        val pinned = PriceDistribution.build(25_000.0, 350.0, 0.0, 25_050.0, 0.4)
        assertTrue(pinned.pBetween(24_950.0, 25_150.0) > d.pBetween(24_950.0, 25_150.0) + 0.1)
        // direction probabilities with the 0.25σ neutral band ≈ 20 % neutral when drift is 0
        val flat = PriceDistribution.build(25_000.0, 400.0, 0.0)
        val neutral = flat.pBetween(25_000.0 - 100.0, 25_000.0 + 100.0)
        assertEquals(0.20, neutral, 0.02)
    }

    @Test fun strategiesAreDefinedRiskAndNeverNakedShort() {
        val (_, _, out) = warmed(seed = 3, minutes = 150)
        val c = out.strategies.candidates
        assertTrue(c.isNotEmpty())
        print(summary(out))
        c.sortedByDescending { it.score }.take(8).forEach {
            println("  %-60s EV %+6.1f maxL %6.1f P %.2f RR %s score %.3f %s".format(it.title, it.expectedPnl, it.maxLoss, it.pProfit,
                if (it.riskReward.isNaN()) "open" else "%.2f".format(it.riskReward), it.score, if (it.passedFilters) "" else it.failures.take(2)))
        }
        for (s in c) {
            assertTrue(s.maxLoss > 0 && s.maxLoss.isFinite(), "${s.title} max loss must be finite")
            for (t in OptionType.values()) assertTrue(s.legs.count { it.type == t && it.action == LegAction.SELL } <=
                s.legs.count { it.type == t && it.action == LegAction.BUY }, "${s.title}: uncovered short $t")
            assertTrue(s.failures.none { it.startsWith("Uncovered") }, "${s.title}: ${s.failures}")
            assertTrue(s.costPerUnit > 0 && s.expectedPnl < s.expectedPnlGross)
        }
        val credit = c.first { it.kind == StrategyKind.BULL_PUT_SPREAD }
        assertTrue(credit.netPremium < 0, "credit spread receives premium")
        val width = credit.legs.maxOf { it.strike } - credit.legs.minOf { it.strike }
        assertEquals(width + credit.netPremium + credit.costPerUnit, credit.maxLoss, 1e-6)
        val condor = c.first { it.kind == StrategyKind.IRON_CONDOR }
        assertEquals(4, condor.legs.size)
        assertTrue(c.any { it.horizon.intraday }, "intraday candidates during the session")
    }

    @Test fun circuitBreakerOnMissingOptionChainAndStaleSpot() {
        val (e, sim, _) = warmed()
        val out = e.process(sim.collect(0).copy(optionChain = null))
        assertEquals(Decision.DATA_ERROR, out.decision.decision)
        assertTrue(out.dataQuality.circuitBreaker.any { it.startsWith("Options missing") })
        val s = sim.collect(0)
        val stale = e.process(s.copy(nifty = s.nifty.copy(asOf = s.timestamp - 20 * 60_000L)))
        assertEquals(Decision.DATA_ERROR, stale.decision.decision)
        assertEquals(FeedStatus.STALE, stale.dataQuality.feeds.first { it.name == "NIFTY" }.status)
        // a stale NIFTY also stops being a fresh input for the short horizons
        val ps = stale.horizon(HorizonId.M30)!!.factors.first { it.factor == Factor.PRICE_STRUCTURE }.reading
        assertTrue(!ps.available || ps.freshness < 0.2)
    }

    @Test fun uncalibratedEngineOnlyPaperTradesAndNeedsPersistence() {
        var paperSeen = false
        for (seed in listOf(11L, 3L, 7L, 21L)) {
            val sim = SimulatedMarket(seed = seed, startDate = day)
            val e = NiftyDirectionEngine()
            val decisions = (0 until 370).map { e.process(sim.collect(0)).decision }
            assertTrue(decisions.none { it.decision == Decision.TRADE }, "uncalibrated engine must not emit TRADE")
            val firstPaper = decisions.indexOfFirst { it.decision == Decision.PAPER_TRADE }
            println("seed $seed decisions: " + decisions.groupingBy { it.decision }.eachCount())
            if (firstPaper >= 0) {
                paperSeen = true
                val d = decisions[firstPaper]
                assertTrue(d.checks.first { it.name == "Signal persistence" }.passed)
                assertFalse(d.checks.first { it.name == "Probability calibrated" }.passed)
                assertTrue(d.lots >= 1 && d.maxLossRupees <= 500_000 * 0.02 + 1e-6, "position sized within the risk budget")
                break
            }
        }
        println("paper trade seen: $paperSeen")
    }

    @Test fun eventRiskCalendarRaisesRiskWithoutReversingDirection() {
        val now = day.atTime(10, 0).atZone(Session.IST).toInstant().toEpochMilli()
        val weekly = ExpiryCalendar.nextWeekly(now); val monthly = ExpiryCalendar.nextMonthly(now)
        val quiet = EventRiskEngine.analyze(now, emptyList(), emptyList(), weekly, monthly, activeShock = false)
        assertTrue(quiet.upcoming.any { it.category == CalendarCategory.EXPIRY })
        val rbi = ScheduledEvent(day.toString(), "RBI MPC decision", CalendarCategory.RBI, 3, "user")
        val busy = EventRiskEngine.analyze(now, listOf(rbi), emptyList(), weekly, monthly, activeShock = false)
        assertTrue(busy.level(HorizonGroup.H1).ordinal >= EventRiskLevel.HIGH.ordinal, "RBI today ⇒ high intraday risk")
        assertTrue(busy.level(HorizonGroup.H2).ordinal > quiet.level(HorizonGroup.H2).ordinal)
        val shocked = EventRiskEngine.analyze(now, listOf(rbi), emptyList(), weekly, monthly, activeShock = true)
        assertEquals(EventRiskLevel.EXTREME, shocked.level(HorizonGroup.H1))
        assertTrue(EventRiskLevel.HIGH.penalty > EventRiskLevel.MEDIUM.penalty)
    }

    @Test fun expiryCalendarRules() {
        val tue = LocalDate.of(2026, 10, 6)
        assertEquals(LocalDate.of(2026, 10, 27), ExpiryCalendar.monthlyOf(2026, 10))
        val monAfternoon = LocalDate.of(2026, 10, 5).atTime(14, 0).atZone(Session.IST).toInstant().toEpochMilli()
        assertEquals(tue, ExpiryCalendar.nextWeekly(monAfternoon))
        val tueEvening = tue.atTime(16, 0).atZone(Session.IST).toInstant().toEpochMilli()
        assertEquals(tue.plusWeeks(1), ExpiryCalendar.nextWeekly(tueEvening))
        assertEquals(375.0, ExpiryCalendar.tradingMinutesBetween(Session.sessionStart(monAfternoon), Session.closeOf(LocalDate.of(2026, 10, 5))), 1e-9)
        val fri = LocalDate.of(2026, 10, 2).atTime(15, 0).atZone(Session.IST).toInstant().toEpochMilli()
        // 30 minutes after Friday 15:00 = Friday close; 60 minutes = Monday 09:45
        assertEquals(LocalDate.of(2026, 10, 5).atTime(9, 45).atZone(Session.IST).toInstant().toEpochMilli(), ExpiryCalendar.addTradingMinutes(fri, 60.0))
    }

    @Test fun expiryIntelligenceFindsSupportResistanceAndPin() {
        val (_, _, out) = warmed(seed = 5, minutes = 200)
        val w = assertNotNull(out.weekly)
        assertTrue(w.support == null || w.support!!.strike <= w.spot)
        assertTrue(w.resistance == null || w.resistance!!.strike >= w.spot)
        assertTrue(w.expectedMoveIv > 0 && w.atmIv > 0)
        assertNotNull(w.breakout)
        assertEquals(1.0, w.breakout!!.pUp + w.breakout!!.pDown + w.breakout!!.pInside, 1e-9)
        val wk = out.horizon(HorizonId.WEEKLY)!!
        assertTrue(wk.path.isNotEmpty() && wk.path.last().label.startsWith("Expiry"))
        println("weekly ${w.expiry}: S ${w.support?.strike} R ${w.resistance?.strike} pin ${w.pin?.strike}/%.2f regime ${w.regime} · ${w.breakout?.note}".format(w.pin?.strength ?: 0.0))
        val m = out.monthly
        if (m != null) assertTrue(m.daysToExpiry >= w.daysToExpiry)
    }

    @Test fun isotonicCalibrationRecoversTrueFrequencies() {
        val rnd = Random(3)
        val recs = (0 until 3000).map { i ->
            val pb = 0.2 + rnd.nextDouble() * 0.7
            val pd = (1 - pb) * 0.5; val pn = 1 - pb - pd
            val trueBull = 0.25 + 0.5 * (pb - 0.33)
            val u = rnd.nextDouble()
            val realized = if (u < trueBull) 1 else if (u < trueBull + (1 - trueBull) / 2) -1 else 0
            val hr = HorizonRecord(HorizonId.M30, i * 60_000L + 1_800_000L, 0.0, 80.0, 0.0, 20.0, pb, pn, pd, false,
                direction = Direction.BULLISH, probability = pb, confidence = ConfidenceLevel.MEDIUM, confidenceValue = 0.5, coverage = 1.0,
                expectedPrice = 25000.0, rangeLow = 24920.0, rangeHigh = 25080.0, outcome = HorizonOutcome(25000.0, realized * 50.0, realized, true, 0L))
            PredictionRecord("$i", i * 60_000L, 25000.0, source = "t", regime = "MIXED", masterDirection = Direction.BULLISH, masterProbability = pb,
                alignment = 2, masterConfidence = ConfidenceLevel.MEDIUM, horizons = listOf(hr), decision = "WAIT")
        }
        val cal = ProbabilityCalibrator.fit(recs, minSamples = 150)
        assertTrue(cal.info.isCalibrated(HorizonId.M30) && !cal.info.isCalibrated(HorizonId.WEEKLY))
        val p = cal.apply(HorizonId.M30, 0.83, 0.085, 0.085)!!
        assertEquals(0.50, p.first, 0.07) // raw said 83 %, reality ≈ 50 %
        assertTrue(cal.info.holdoutBrierCalibrated.getValue("M30") < cal.info.holdoutBrierRaw.getValue("M30"))
        val s = PerformanceStats.summarize(recs, HorizonId.M30)
        assertEquals(8, s.buckets.size)
        val m = IsoModel.fit(listOf(0.1 to 0.0, 0.2 to 1.0, 0.3 to 0.0, 0.4 to 1.0, 0.5 to 1.0), 0.5)
        assertTrue(m.ys.zipWithNext().all { it.first <= it.second })
    }

    @Test fun transactionCostsAreCharged() {
        val c = TransactionCosts(lotSize = 65, lots = 1)
        val b = c.roundTrip(100.0, 120.0)
        assertEquals(40.0, b.brokerage, 1e-9)
        assertEquals(120.0 * 65 * 0.1 / 100, b.stt, 1e-9)
        assertTrue(c.perUnit(100.0, 120.0) in 0.8..1.5)
    }

    @Test fun replayHasNoLookAheadAndAttachesOutcomes() {
        val sim = SimulatedMarket(seed = 5, startDate = day)
        val snaps = mutableListOf<MarketSnapshot>()
        repeat(370) { snaps += sim.collect(0) }
        val future = NewsItem("f1", "RBI cuts repo rate by 100 bps in surprise move", "Reuters", snaps.last().timestamp + 3_600_000L)
        val replay = HistoricalReplayEngine()
        val pit = replay.pointInTime(snaps[50], ReplayMode.FULL_INFORMATION, listOf(future))
        assertTrue(pit.news.none { it.id == "f1" })
        assertTrue(pit.nifty.intraday.all { it.t <= snaps[50].timestamp })
        val today = Session.zdt(snaps[50].timestamp).toLocalDate()
        assertTrue(pit.fiiDerivatives!!.days.all { LocalDate.parse(it.date) < today }, "no same-day FII print before it exists")
        val a = replay.run(snaps, ReplayMode.MARKET_ONLY)
        assertTrue(a.records.isNotEmpty())
        for (h in listOf(HorizonId.M30, HorizonId.M60, HorizonId.M180, HorizonId.CLOSE)) {
            assertTrue(a.records.any { r -> r.horizon(h)?.outcome != null }, "$h outcomes attached")
        }
        val s30 = a.summaries.first { it.horizon == HorizonId.M30 }
        println("replay 30m n=${s30.n} acc=%.2f dir-hit=%.2f range-hit=%.2f".format(s30.accuracy, s30.directionalHitRate, s30.rangeHitRate))
    }

    @Test fun walkForwardReplayUsesOnlyPastCalibration() {
        val sessions = (0 until 3).map { k ->
            val sim = SimulatedMarket(seed = 100L + k, startDate = day.minusDays((3 - k).toLong() * 7))
            List<MarketSnapshot>(370) { sim.collect(0) }
        }
        val wf = HistoricalReplayEngine().runWalkForward(sessions, ReplayMode.MARKET_ONLY, minSamples = 60)
        assertEquals(3, wf.sessions)
        val firstDay = wf.result.records.minOf { it.timestamp }
        assertTrue(wf.result.records.filter { it.timestamp < firstDay + 6 * 3_600_000L }.none { r -> r.horizons.any { it.calibrated } })
        assertTrue(wf.result.records.any { r -> r.horizons.any { it.calibrated } }, "later sessions should use the earlier fit")
        assertTrue(wf.result.records.all { it.engineVersion == ENGINE_VERSION && it.horizons.size == 6 })
        println("walk-forward: ${wf.finalCalibration.info.note}")
    }

    @Test fun duplicateNewsIsClusteredIntoOneEvent() {
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
        assertTrue(rbi.direction > 0)
        assertTrue(r.events.first { it.type == EventType.CRUDE }.direction < 0)
    }

    @Test fun futuresClassificationAndBlackScholesParity() {
        assertEquals(FuturesState.LONG_BUILDUP, FuturesState.classify(0.5, 2.0))
        assertEquals(FuturesState.SHORT_BUILDUP, FuturesState.classify(-0.5, 2.0))
        assertEquals(FuturesState.SHORT_COVERING, FuturesState.classify(0.5, -2.0))
        assertEquals(FuturesState.LONG_UNWINDING, FuturesState.classify(-0.5, -2.0))
        val s = 25_000.0; val k = 25_100.0; val t = 5 / 365.0; val v = 0.13; val r = 0.065
        val c = BlackScholes.price(true, s, k, t, v, r).price
        val p = BlackScholes.price(false, s, k, t, v, r).price
        assertTrue(abs((c - p) - (s - k * Math.exp(-r * t))) < 0.05)
        assertEquals(v, BlackScholes.impliedVol(true, s, k, t, c), 1e-4)
    }
}

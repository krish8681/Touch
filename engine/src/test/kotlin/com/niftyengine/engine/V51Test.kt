package com.niftyengine.engine

import com.niftyengine.engine.core.BlackScholes
import com.niftyengine.engine.core.Session
import com.niftyengine.engine.core.TransactionCosts
import com.niftyengine.engine.engines.ModelHealthEngine
import com.niftyengine.engine.engines.Outcome
import com.niftyengine.engine.engines.PredictionLogger
import com.niftyengine.engine.engines.PredictionRecord
import com.niftyengine.engine.engines.InMemoryPredictionStore
import com.niftyengine.engine.engines.ProbabilityCalibrator
import com.niftyengine.engine.engines.RiskEngine
import com.niftyengine.engine.engines.ShadowTrader
import com.niftyengine.engine.engines.StrategySelector
import com.niftyengine.engine.engines.TradeDecisionEngine
import com.niftyengine.engine.model.CalibrationInfo
import com.niftyengine.engine.model.Candle
import com.niftyengine.engine.model.ConfidenceLevel
import com.niftyengine.engine.model.DataQualityReport
import com.niftyengine.engine.model.Decision
import com.niftyengine.engine.model.DirectionResult
import com.niftyengine.engine.model.EngineOutput
import com.niftyengine.engine.model.EventAnalysis
import com.niftyengine.engine.model.EventStage
import com.niftyengine.engine.model.EventType
import com.niftyengine.engine.model.ExpectedMove
import com.niftyengine.engine.model.FeedQuality
import com.niftyengine.engine.model.FeedStatus
import com.niftyengine.engine.model.FuturesBar
import com.niftyengine.engine.model.FutureExpectation
import com.niftyengine.engine.model.HealthTier
import com.niftyengine.engine.model.HorizonProb
import com.niftyengine.engine.model.InformationShock
import com.niftyengine.engine.model.LegAction
import com.niftyengine.engine.model.ModelHealth
import com.niftyengine.engine.model.NewsItem
import com.niftyengine.engine.model.OptionAnalysis
import com.niftyengine.engine.model.OptionChain
import com.niftyengine.engine.model.OptionLeg
import com.niftyengine.engine.model.OptionStrikeRow
import com.niftyengine.engine.model.OptionType
import com.niftyengine.engine.model.PrimaryRegime
import com.niftyengine.engine.model.Regime
import com.niftyengine.engine.model.RegimeAssessment
import com.niftyengine.engine.model.RegimeClass
import com.niftyengine.engine.model.RegimeResult
import com.niftyengine.engine.model.RiskConfig
import com.niftyengine.engine.model.Scenario
import com.niftyengine.engine.model.ScenarioSet
import com.niftyengine.engine.model.ShadowBook
import com.niftyengine.engine.model.StrategyCandidate
import com.niftyengine.engine.model.StrategyLeg
import com.niftyengine.engine.model.StrategyPlan
import com.niftyengine.engine.model.StrategyType
import com.niftyengine.engine.model.TradeQuality
import com.niftyengine.engine.model.MarketSnapshot
import com.niftyengine.engine.sim.SimulatedMarket
import kotlinx.serialization.json.Json
import java.time.LocalDate
import kotlin.math.round
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** v5.1 — correctness, validation and data-integrity fixes. */
class V51Test {
    private val day = LocalDate.of(2026, 9, 29)
    private val t11 = day.atTime(11, 0).atZone(Session.IST).toInstant().toEpochMilli()
    private val spot = 25_000.0

    private fun rec(i: Int, pBull: Double, realized: Int, h: Int = 30, regime: String = "TREND_UP") = PredictionRecord(
        id = "$i", timestamp = i * 60_000L, spot = spot, pBull = pBull, pBear = (1 - pBull) / 2, pRange = (1 - pBull) / 2,
        confidence = "HIGH", confidenceValue = 0.8, regime = "BULL_TREND", expectedMove = 50.0, sigma = 80.0, decision = "WAIT",
        source = "t", horizonMinutes = h, primaryRegime = regime,
        outcomes = listOf(Outcome(h, spot, if (realized == 1) 50.0 else if (realized == -1) -50.0 else 0.0, 0.0, 0.0, realized = realized)))

    // ------------------------------------------------------------------ 1 — calibration must prove itself out of sample

    @Test fun calibrationIsRejectedWhenItDoesNotHoldOnUnseenOutcomes() {
        val rnd = Random(4)
        // the relationship flips after the first 70 %: a calibration fitted on the past hurts the future
        val recs = (0 until 300).map { i ->
            val pb = 0.3 + rnd.nextDouble() * 0.6
            val trueBull = if (i < 210) 1.2 - pb else pb
            rec(i, pb, if (rnd.nextDouble() < trueBull.coerceIn(0.0, 1.0)) 1 else if (rnd.nextBoolean()) -1 else 0)
        }
        val cal = ProbabilityCalibrator.fit(recs, minSamples = 150)
        assertEquals(false, cal.info.accepted[30])
        assertTrue(30 !in cal.horizons && !cal.info.calibrated, "a calibration that fails the hold-out must not be applied")
        assertTrue(cal.info.holdoutBrierCalibrated.getValue(30) >= cal.info.holdoutBrierRaw.getValue(30))
        assertTrue(cal.info.note.contains("rejected"), cal.info.note)
        assertTrue(!cal.applyProgressive(0.8, 0.1, 0.1, 30).calibrated && !cal.applyProgressive(0.8, 0.1, 0.1, 30).partial)
        assertTrue(cal.info.holdoutLogLossRaw.getValue(30).isFinite())

        // stable relationship ⇒ accepted (and log loss reported)
        val stable = (0 until 300).map { i ->
            val pb = 0.3 + rnd.nextDouble() * 0.6
            rec(i, pb, if (rnd.nextDouble() < 0.25 + 0.5 * (pb - 0.3)) 1 else if (rnd.nextBoolean()) -1 else 0)
        }
        val ok = ProbabilityCalibrator.fit(stable, minSamples = 150)
        assertEquals(true, ok.info.accepted[30])
        assertTrue(30 in ok.horizons)
        assertTrue(ok.info.holdoutLogLossCalibrated.getValue(30) <= ok.info.holdoutLogLossRaw.getValue(30))
    }

    @Test fun scenarioCalibrationIsWalkForwardToo() {
        val raw = mapOf(Scenario.STRONG_UP.name to 0.5, Scenario.MILD_UP.name to 0.2, Scenario.RANGE.name to 0.2,
            Scenario.MILD_DOWN.name to 0.05, Scenario.STRONG_DOWN.name to 0.05)
        fun recs(move: (Int) -> Double) = (0 until 150).map { i ->
            rec(i, 0.7, 1, h = 60).copy(scenarioProbs = raw, rangeBand = 20.0, breakoutBand = 60.0,
                outcomes = listOf(Outcome(60, spot, move(i), 0.0, 0.0, realized = 1)))
        }
        // breakouts stop happening in the later 30 %: the early fit (breakouts common) must be rejected
        val shift = ProbabilityCalibrator.fit(recs { i -> if (i < 105) 80.0 else 30.0 }, minSamples = 60)
        assertFalse(shift.info.scenarioAccepted, shift.info.toString())
        assertTrue(shift.scenario.isEmpty())
        assertTrue(shift.info.scenarioBrierCalibrated >= shift.info.scenarioBrierRaw)
        // a stable pattern (breakouts 10 %, mild up 90 %) is accepted
        val stable = ProbabilityCalibrator.fit(recs { i -> if (i % 10 == 0) 80.0 else 30.0 }, minSamples = 60)
        assertTrue(stable.info.scenarioAccepted)
        assertTrue(stable.info.scenarioLogLossCalibrated < stable.info.scenarioLogLossRaw)
    }

    // ------------------------------------------------------------------ 2 — no future data can reach a decision

    private val json = Json { allowSpecialFloatingPointValues = true }
    private fun enc(o: EngineOutput) = json.encodeToString(EngineOutput.serializer(), o)

    /** Same snapshot with data stamped AFTER its own time injected everywhere it could hide. */
    private fun poison(s: MarketSnapshot): MarketSnapshot {
        val t = s.timestamp
        fun fut(c: Double, k: Int) = Candle(t + k * 60_000L, c, c, c, c, 1e6)
        fun bad(d: com.niftyengine.engine.model.InstrumentData, mult: Double) = d.copy(
            intraday = d.intraday + (1..20).map { fut(d.last * mult, it) },
            daily = d.daily + Candle(t + 86_400_000L, d.last * mult, d.last * mult, d.last * mult, d.last * mult))
        return s.copy(
            nifty = bad(s.nifty, 1.08), vix = s.vix?.let { bad(it, 2.0) }, bankNifty = s.bankNifty?.let { bad(it, 1.1) },
            constituents = s.constituents.mapValues { bad(it.value, 1.2) }, sectors = s.sectors.mapValues { bad(it.value, 1.2) },
            global = s.global.mapValues { bad(it.value, 1.05) },
            futures = s.futures?.let { f -> f.copy(intraday = f.intraday + (1..10).map { FuturesBar(t + it * 60_000L, f.last * 1.1, f.openInterest * 3) }) },
            news = s.news + NewsItem("future-1", "RBI cuts repo rate by 100 bps in surprise emergency move", "Reuters", t + 30 * 60_000L),
            eventAnalyses = s.eventAnalyses + EventAnalysis("Efuture", t + 10 * 60_000L, "gemini:test", "future", EventType.RBI_POLICY,
                EventStage.CONFIRMED, 1.0, 1.0, expectationShift = 1.0),
        )
    }

    @Test fun injectingFutureDataChangesNothing() {
        val a = NiftyDirectionEngine(EngineConfig(requireMarketOpen = false))
        val b = NiftyDirectionEngine(EngineConfig(requireMarketOpen = false))
        val simA = SimulatedMarket(seed = 17, startDate = day)
        val simB = SimulatedMarket(seed = 17, startDate = day)
        repeat(150) { a.process(simA.collect(0)); b.process(simB.collect(0)) }
        // every snapshot from here on carries future data for engine B — outputs must stay byte-identical
        repeat(60) { k ->
            val clean = simA.collect(0); simB.collect(0)
            val oa = a.process(clean)
            val ob = b.process(poison(clean))
            assertEquals(enc(oa), enc(ob), "future data leaked into the decision at cycle $k (${Session.hhmm(clean.timestamp)})")
        }
        // and the replay layer strips the same things
        val s = simA.collect(0)
        val pit = a.pointInTime(poison(s))
        assertTrue(pit.nifty.intraday.all { it.t <= s.timestamp } && pit.news.all { it.publishedAt <= s.timestamp })
        assertTrue(pit.global.values.all { g -> g.daily.all { it.t <= s.timestamp } })
        assertTrue(pit.eventAnalyses.all { it.analyzedAt <= s.timestamp })
    }

    // ------------------------------------------------------------------ 3 — prediction time vs outcome time

    @Test fun outcomesUseOnlyTheirOwnWindowAndArriveOnlyAfterTheHorizon() {
        val store = InMemoryPredictionStore()
        val logger = PredictionLogger(store, listOf(30))
        val r = rec(0, 0.7, 0).copy(id = "p", timestamp = t11, outcomes = emptyList(), horizonMinutes = 30)
        store.append(r)
        val path = listOf(
            PredictionLogger.PricePoint(t11 - 60_000L, 30_000.0),           // before the prediction — never used
            PredictionLogger.PricePoint(t11 + 10 * 60_000L, 25_020.0),
            PredictionLogger.PricePoint(t11 + 29 * 60_000L, 25_060.0),
            PredictionLogger.PricePoint(t11 + 31 * 60_000L, 26_000.0),     // after outcomeEnd — never used
        )
        assertEquals(0, logger.evaluate(path, t11 + 29 * 60_000L), "no outcome before T + horizon")
        assertTrue(store.all().single().outcomes.isEmpty())
        logger.evaluate(path, t11 + 31 * 60_000L)
        val o = store.all().single().outcomes.single()
        assertEquals(25_060.0, o.price, 1e-9)
        assertEquals(60.0, o.move, 1e-9)
        assertEquals(25_060.0, o.high, 1e-9)
        assertEquals(25_020.0, o.low, 1e-9)
    }

    // ------------------------------------------------------------------ 5 — model health gate

    private fun chain(ivPct: Double = 13.0, spreadMult: Double = 1.0): OptionChain {
        val expMs = t11 + 4 * 86_400_000L
        val tY = Session.yearsToExpiry(t11, expMs)
        val atm = round(spot / 50) * 50
        return OptionChain(spot, "2026-10-03", expMs, (-20..20).map { i ->
            val k = atm + i * 50
            fun leg(call: Boolean): OptionLeg {
                val px = BlackScholes.price(call, spot, k, tY, ivPct / 100).price
                return OptionLeg(oi = 300_000.0, volume = 800_000.0, iv = ivPct, ltp = px, bid = px * (1 - 0.002 * spreadMult), ask = px * (1 + 0.002 * spreadMult))
            }
            OptionStrikeRow(k, leg(true), leg(false))
        }, 50.0)
    }

    private fun feeds(st: FeedStatus) = listOf("NIFTY", "Futures", "Options").map { FeedQuality(it, st, "t", t11, 20.0, true) }

    @Test fun modelHealthTiers() {
        val eng = ModelHealthEngine()
        val good = eng.assess(ModelHealthEngine.Inputs(
            DataQualityReport(feeds(FeedStatus.LIVE), 1.0), 1.0, RegimeAssessment(PrimaryRegime.TREND_UP, quality = 0.85, stability = 1.0),
            HorizonProb(60, 0.7, 0.1, 0.2, true),
            CalibrationInfo(calibrated = true, holdoutBrierRaw = mapOf(60 to 0.60), holdoutBrierCalibrated = mapOf(60 to 0.55), accepted = mapOf(60 to true)),
            60, emptyList(), chain(), spot, 13.0))
        assertEquals(HealthTier.ELIGIBLE, good.tier, good.toString())
        assertTrue(good.score >= 75 && good.components.size == 6)
        // healthy data, but an unstable regime, no calibration yet and wide spreads ⇒ shadow only
        val shaky = eng.assess(ModelHealthEngine.Inputs(
            DataQualityReport(feeds(FeedStatus.LIVE), 0.85), 0.9, RegimeAssessment(PrimaryRegime.REVERSAL_RISK, quality = 0.35, stability = 0.5),
            HorizonProb(60, 0.6, 0.2, 0.2, false), CalibrationInfo(), 60, emptyList(), chain(spreadMult = 5.0), spot, 13.0))
        assertEquals(HealthTier.SHADOW_ONLY, shaky.tier, "%.1f".format(shaky.score))
        val broken = eng.assess(ModelHealthEngine.Inputs(
            DataQualityReport(feeds(FeedStatus.STALE), 0.3), 0.5, RegimeAssessment(PrimaryRegime.CONFLICT, quality = 0.2, stability = 0.3),
            HorizonProb(60, 0.6, 0.2, 0.2, false), CalibrationInfo(accepted = mapOf(60 to false)), 60, emptyList(), null, spot, Double.NaN))
        assertEquals(HealthTier.NO_SIGNAL, broken.tier)
        assertTrue(broken.notes.any { it.contains("did not beat") })
    }

    private fun candidate() = StrategyCandidate(
        StrategyType.BUY_CALL, listOf(StrategyLeg(LegAction.BUY, OptionType.CE, 25_000.0, "2026-10-03", 100.0, 99.8, 100.2, 13.0, 0.5, 0.4, 3e5, 8e5)),
        100.0, Double.NaN, 101.0, listOf(25_100.0), 0.56, 10.0, 11.0, 1.0, 0.1, 1.8, 0.94, true)

    private fun decide(health: ModelHealth?, regimeBump: Double = 0.0): Decision {
        val d = DirectionResult(0.78, 0.08, 0.14, 0.6, 0.2, ConfidenceLevel.HIGH, 0.9, 0.9, 0.05, ConfidenceLevel.LOW, true, true, emptyList(), emptyList())
        val move = ExpectedMove(60, 80.0, 70.0, 40.0, 110.0, 24_900.0, 25_100.0, 0.13, 1.0, emptyList())
        val risk = RiskEngine(RiskConfig(), TransactionCosts()).assess(candidate(), t11, 60, ShadowBook(), 13.0, InformationShock(), 0.0, true)
        assertTrue(risk.approved)
        return TradeDecisionEngine(TradeDecisionEngine.Params(confirmCycles = 1, requireCalibration = false, requireMarketOpen = false)).decide(
            d, RegimeResult(Regime.BULL_TREND, RegimeClass.TREND, emptyList(), emptyList()), move,
            OptionAnalysis(OptionType.CE, null, emptyList(), 13.0, 4.0, emptyList()), true, null, DataQualityReport(), false,
            TradeDecisionEngine.V5(RegimeAssessment(PrimaryRegime.TREND_UP, quality = 0.8), ScenarioSet(spot = spot),
                StrategyPlan(StrategyType.BUY_CALL, candidate(), StrategyType.BUY_CALL), TradeQuality(0.8, passed = true), risk, health, regimeBump),
        ).decision
    }

    @Test fun healthGateDecidesTradeShadowOrNoSignal() {
        assertEquals(Decision.TRADE, decide(ModelHealth(86.0, HealthTier.ELIGIBLE)))
        assertEquals(Decision.PAPER_TRADE, decide(ModelHealth(68.0, HealthTier.SHADOW_ONLY)), "60–74 ⇒ shadow only")
        assertEquals(Decision.NO_TRADE, decide(ModelHealth(52.0, HealthTier.NO_SIGNAL)), "< 60 ⇒ no signal")
        assertEquals(Decision.TRADE, decide(null))
        // a weak realised record in this regime raises the bar: 78 % no longer clears 65 % + 15 pts
        assertEquals(Decision.WAIT, decide(ModelHealth(86.0, HealthTier.ELIGIBLE), regimeBump = 0.15))
    }

    // ------------------------------------------------------------------ 8 — regime-specific performance → selectivity

    @Test fun overConfidentRegimesGetAHigherBar() {
        val rnd = Random(2)
        val recs = (0 until 120).map { i -> rec(i, 0.75, if (rnd.nextDouble() < 0.30) 1 else 0, regime = "TREND_UP") } +
            (0 until 120).map { i -> rec(1000 + i, 0.62, if (rnd.nextDouble() < 0.62) 1 else 0, regime = "RANGE") }
        val cal = ProbabilityCalibrator.fit(recs, minSamples = 150)
        val tu = cal.regimes.getValue("TREND_UP"); val rg = cal.regimes.getValue("RANGE")
        assertTrue(tu.hitRate < 0.4 && rg.hitRate > 0.5)
        assertTrue(cal.regimeBump("TREND_UP") > 0.05, tu.toString())
        assertEquals(0.0, cal.regimeBump("RANGE"), 1e-9)
        assertEquals(0.0, cal.regimeBump("UNKNOWN"), 1e-9)
        val few = ProbabilityCalibrator.fit(recs.filter { it.primaryRegime == "TREND_UP" }.take(20) + recs.filter { it.primaryRegime == "RANGE" })
        assertEquals(0.0, few.regimeBump("TREND_UP"), 1e-9, "fewer than 30 calls ⇒ no bump")
        val stats = com.niftyengine.engine.engines.PerformanceStats.summarize(recs, 30)
        assertTrue(stats.logLoss.isFinite() && stats.regimes.map { it.regime }.toSet() == setOf("TREND_UP", "RANGE"))
    }

    // ------------------------------------------------------------------ 15–17 — shadow metrics + benchmark

    @Test fun shadowStatsPutExpectancyFirstAndBenchmarkRunsAtTheSameMoments() {
        val ch = chain()
        val main = candidate().let { c -> c.copy(legs = listOf(c.legs[0].copy(price = ch.rows.first { it.strike == 25_000.0 }.call.ask)),
            netPremium = ch.rows.first { it.strike == 25_000.0 }.call.ask) }
        val riskE = RiskEngine(RiskConfig(), TransactionCosts())
        val risk = riskE.assess(main, t11, 60, ShadowBook(), 13.0, InformationShock(), 0.0, true)
        val bench = StrategySelector.atmLong(ch, spot, -1, TransactionCosts())!!
        assertEquals(StrategyType.BUY_PUT, bench.type)
        val st = ShadowTrader()
        fun ctx(t: Long, c: OptionChain, d: Decision) = ShadowTrader.Context(t, spot, c, d, StrategyPlan(StrategyType.BUY_CALL, main), risk,
            RegimeAssessment(PrimaryRegime.TREND_UP), TradeQuality(), 0.7, FutureExpectation(), InformationShock(), false, true,
            bench to riskE.assess(bench, t, 60, st.benchmarkBook, 13.0, InformationShock(), 0.0, true))
        st.update(ctx(t11 - 60_000L, ch, Decision.WAIT))
        assertTrue(st.current.benchOpen.isEmpty(), "the benchmark only trades when the main book does")
        st.update(ctx(t11, ch, Decision.PAPER_TRADE))
        assertEquals(1, st.current.open.size); assertEquals(1, st.current.benchOpen.size)
        val s = st.update(ctx(t11 + 61 * 60_000L, ch, Decision.WAIT)) // time exit for both
        assertEquals(1, s.stats.trades); assertEquals(1, s.benchmark.trades)

        val t0 = st.current.closed.single()
        fun tr(pnl: Double, dayOff: Int) = t0.copy(pnl = pnl, closedAt = t11 + dayOff * 86_400_000L, rMultiple = pnl / 100)
        val stats = ShadowTrader.stats(listOf(tr(300.0, 0), tr(-100.0, 1), tr(200.0, 2), tr(-100.0, 3)))
        assertEquals(2.5, stats.profitFactor, 1e-9)
        assertEquals(75.0, stats.expectancy, 1e-9)
        assertEquals(250.0, stats.avgWin, 1e-9); assertEquals(-100.0, stats.avgLoss, 1e-9)
        assertEquals(2.5, stats.payoff, 1e-9)
        assertEquals(100.0, stats.maxDrawdown, 1e-9)
        assertTrue(stats.sharpe.isNaN(), "Sharpe needs ≥ 5 trading days")
        // 80 % winners with one large loser is worse than it looks — expectancy and profit factor say so
        val fragile = ShadowTrader.stats((1..8).map { tr(100.0, it) } + listOf(tr(-1500.0, 9), tr(-200.0, 10)))
        assertEquals(0.8, fragile.winRate, 1e-9)
        assertTrue(fragile.expectancy < 0 && fragile.profitFactor < 1)
        assertNotNull(fragile.sharpe)
    }
}

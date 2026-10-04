package com.niftyengine.engine

import com.niftyengine.engine.core.BlackScholes
import com.niftyengine.engine.core.EngineState
import com.niftyengine.engine.core.Session
import com.niftyengine.engine.core.TransactionCosts
import com.niftyengine.engine.engines.CalibrationModel
import com.niftyengine.engine.engines.DataNormalizer
import com.niftyengine.engine.engines.DirectionProbabilityEngine.DriverInput
import com.niftyengine.engine.engines.FutureExpectationEngine
import com.niftyengine.engine.engines.FuturesState
import com.niftyengine.engine.engines.InformationShockEngine
import com.niftyengine.engine.engines.Outcome
import com.niftyengine.engine.engines.PredictionRecord
import com.niftyengine.engine.engines.ProbabilityCalibrator
import com.niftyengine.engine.engines.RegimeEngineV5
import com.niftyengine.engine.engines.RelativeBaselines
import com.niftyengine.engine.engines.RiskEngine
import com.niftyengine.engine.engines.ScenarioDistribution
import com.niftyengine.engine.engines.ScenarioEngine
import com.niftyengine.engine.engines.ShadowTrader
import com.niftyengine.engine.engines.StrategySelector
import com.niftyengine.engine.engines.TradeQualityEngine
import com.niftyengine.engine.engines.VIXEngine
import com.niftyengine.engine.engines.VixState
import com.niftyengine.engine.model.ConfidenceLevel
import com.niftyengine.engine.model.Decision
import com.niftyengine.engine.model.DecisionState
import com.niftyengine.engine.model.DirectionResult
import com.niftyengine.engine.model.Driver
import com.niftyengine.engine.model.EngineSignal
import com.niftyengine.engine.model.ExpectationState
import com.niftyengine.engine.model.FutureExpectation
import com.niftyengine.engine.model.GlobalAsset
import com.niftyengine.engine.model.HorizonProb
import com.niftyengine.engine.model.InformationShock
import com.niftyengine.engine.model.InstrumentData
import com.niftyengine.engine.model.LegAction
import com.niftyengine.engine.model.MarketSnapshot
import com.niftyengine.engine.model.NewsHorizon
import com.niftyengine.engine.model.NormalizedState
import com.niftyengine.engine.model.OptionAnalysis
import com.niftyengine.engine.model.OptionChain
import com.niftyengine.engine.model.OptionLeg
import com.niftyengine.engine.model.OptionStrikeRow
import com.niftyengine.engine.model.PrimaryRegime
import com.niftyengine.engine.model.QualityTier
import com.niftyengine.engine.model.Regime
import com.niftyengine.engine.model.RegimeAssessment
import com.niftyengine.engine.model.RegimeClass
import com.niftyengine.engine.model.RegimeResult
import com.niftyengine.engine.model.RiskConfig
import com.niftyengine.engine.model.Scenario
import com.niftyengine.engine.model.ScenarioSet
import com.niftyengine.engine.model.ShadowBook
import com.niftyengine.engine.model.ShadowTrade
import com.niftyengine.engine.model.ShockKind
import com.niftyengine.engine.model.ShockLevel
import com.niftyengine.engine.model.StrategyCandidate
import com.niftyengine.engine.model.StrategyLeg
import com.niftyengine.engine.model.StrategyPlan
import com.niftyengine.engine.model.StrategyType
import com.niftyengine.engine.model.TradeQuality
import com.niftyengine.engine.model.OptionType
import com.niftyengine.engine.sim.SimulatedMarket
import kotlinx.serialization.json.Json
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class V5Test {
    private val day = LocalDate.of(2026, 9, 29) // a Tuesday
    private val t11 = day.atTime(11, 0).atZone(Session.IST).toInstant().toEpochMilli()
    private val preOpen = day.atTime(8, 50).atZone(Session.IST).toInstant().toEpochMilli()
    private val spot = 25_000.0
    private val sigma60 = spot * 0.13 * sqrt(60.0 / (Session.SESSION_MINUTES * Session.TRADING_DAYS))

    // ------------------------------------------------------------------ helpers

    private fun dir(pB: Double, pD: Double, conf: Double, level: ConfidenceLevel = ConfidenceLevel.HIGH) = DirectionResult(
        pBull = pB, pBear = pD, pRange = 1 - pB - pD, directionalScore = pB - pD, rangeScore = 0.2, confidence = level,
        confidenceValue = conf, driverAgreement = 0.9, conflict = 0.1, conflictLevel = ConfidenceLevel.LOW,
        priceConfirmation = true, derivativeConfirmation = true, drivers = emptyList(), conflicts = emptyList(),
    )

    private fun rel(
        globalNormalAbs: Map<GlobalAsset, Double> = emptyMap(), pcrRel: Double = Double.NaN, skew: Double = Double.NaN,
        skewNormal: Double = 2.0, iv30: Double = Double.NaN, basisExcess: Double = Double.NaN, ivRel: Double = 0.0,
    ) = RelativeBaselines.Result(
        state = NormalizedState(), globalTypical = emptyMap(), globalNormalAbs = globalNormalAbs,
        pcr = 1.0, pcrNormal = 1.0, pcrRel = pcrRel, skew = skew, skewNormal = skewNormal,
        atmIv = 13.0, ivNormal = 13.0, ivRel = ivRel, ivChangePct = Double.NaN, ivChange15Pct = Double.NaN, ivChange30Pct = iv30,
        basisExcessPct = basisExcess, basisExcessNormal = 0.0, basisNormalDays = 5, fairCarryPts = 20.0,
        realizedVolRel = 1.0, normalAnnualVol = 0.13, niftyMove15Multiple = Double.NaN, vixMove15Multiple = Double.NaN, normalGapPct = 0.35,
    )

    private fun snap(t: Long, global: Map<GlobalAsset, InstrumentData> = emptyMap()) =
        MarketSnapshot(timestamp = t, nifty = InstrumentData("NIFTY 50", spot, spot), global = global)

    /** Black-Scholes option chain around [s] with tight quotes and deep liquidity. */
    private fun chain(s: Double, ivPct: Double, now: Long, days: Double = 4.0, priceMult: Double = 1.0): OptionChain {
        val expMs = now + (days * 86_400_000).toLong()
        val tY = Session.yearsToExpiry(now, expMs)
        val atm = round(s / 50) * 50
        val rows = (-20..20).map { i ->
            val k = atm + i * 50
            fun leg(call: Boolean): OptionLeg {
                val px = BlackScholes.price(call, s, k, tY, ivPct / 100).price * priceMult
                return OptionLeg(oi = 300_000.0, volume = 800_000.0, iv = ivPct, ltp = px,
                    bid = maxOf(0.05, px * 0.998 - 0.05), ask = px * 1.002 + 0.05)
            }
            OptionStrikeRow(k, leg(true), leg(false))
        }
        return OptionChain(s, "2026-10-03", expMs, rows, 50.0)
    }

    private fun scenarios(
        pB: Double, pD: Double, pressure: Double, adx: Double, tags: List<String>, fut: FuturesState, regime: RegimeAssessment,
        rangeEvidence: Double = 0.0,
    ): ScenarioSet = ScenarioEngine().compute(ScenarioEngine.Inputs(
        spot = spot, horizonMinutes = 60, sigma = sigma60, eventMultiplier = 1.0,
        probs = HorizonProb(60, pB, pD, 1 - pB - pD, false), rawBull = pB, rawBear = pD, rawRange = 1 - pB - pD,
        pressure = pressure, adx = adx, structureTags = tags, trend = pressure, futuresDay = fut, futuresIntraday = FuturesState.NEUTRAL,
        regime = regime, shock = InformationShock(), expectation = FutureExpectation(), rangeEvidence = rangeEvidence,
    ))

    private fun select(set: ScenarioSet, regime: RegimeAssessment, ivRel: Double, ivPct: Double = 13.0, days: Double = 4.0, hold: Int = 0): StrategyPlan {
        val ch = chain(spot, ivPct, t11, days)
        return StrategySelector(TransactionCosts()).select(StrategySelector.Inputs(
            ch, spot, t11, 60, set, ScenarioDistribution(set), regime,
            OptionAnalysis(null, null, emptyList(), ivPct, days, emptyList()), ivRel, ivPct, Double.NaN, Double.NaN,
            FutureExpectation(), InformationShock(), hold,
        ))
    }

    private fun longCall(premium: Double = 100.0, spreadPct: Double = 0.4) = StrategyCandidate(
        StrategyType.BUY_CALL, listOf(StrategyLeg(LegAction.BUY, OptionType.CE, 25_000.0, "2026-10-03", premium, premium * 0.998, premium * 1.002,
            13.0, 0.5, spreadPct, 300_000.0, 800_000.0)),
        netPremium = premium, maxProfit = Double.NaN, maxLoss = premium + 1, breakevens = listOf(25_000.0 + premium), probProfit = 0.55,
        expectedValue = 10.0, grossExpectedValue = 11.0, costPerUnit = 1.0, returnOnRisk = 0.1, riskReward = 1.8, liquidity = 0.94, feasible = true,
    )

    // ------------------------------------------------------------------ 9 — signal / trade quality

    @Test fun qualityGateMatchesTheSpecExamples() {
        val gate = TradeQualityEngine()
        val good = gate.assess(dir(0.74, 0.10, 0.86), HorizonProb(60, 0.74, 0.10, 0.16, true),
            RegimeAssessment(PrimaryRegime.TREND_UP, quality = 0.81), longCall(), ShadowBook())
        assertEquals(QualityTier.HIGH, good.tier, good.toString())
        assertTrue(good.passed)
        val weak = gate.assess(dir(0.74, 0.10, 0.52, ConfidenceLevel.MEDIUM), HorizonProb(60, 0.74, 0.10, 0.16, true),
            RegimeAssessment(PrimaryRegime.TREND_UP, quality = 0.43), longCall().copy(liquidity = 0.61), ShadowBook())
        assertFalse(weak.passed, "74% with weak confidence and regime must be NO TRADE")
        assertTrue(weak.notes.any { it.startsWith("Regime quality") })
        assertEquals(TradeQuality().tier, gate.assess(dir(0.7, 0.1, 0.9), HorizonProb(60, 0.7, 0.1, 0.2, false),
            RegimeAssessment(), null, ShadowBook()).tier)
    }

    // ------------------------------------------------------------------ 6 — scenarios

    @Test fun scenariosSplitDirectionIntoMoveSizes() {
        val trend = RegimeAssessment(PrimaryRegime.TREND_UP, quality = 0.8)
        val strong = scenarios(0.62, 0.13, 0.85, 34.0, listOf("OR_BREAKOUT", "ACCELERATING_UP"), FuturesState.SHORT_COVERING, trend)
        val calm = scenarios(0.62, 0.13, 0.05, 15.0, emptyList(), FuturesState.NEUTRAL, trend, rangeEvidence = 0.7)
        for (s in listOf(strong, calm)) {
            assertEquals(1.0, s.scenarios.sumOf { it.probability }, 1e-9)
            assertEquals(0.62, s.pUp, 1e-9)
            assertEquals(0.13, s.pDown, 1e-9)
            assertEquals(0.25, s.p(Scenario.RANGE), 1e-9)
            assertTrue(s.breakoutBand > s.rangeBand && s.rangeBand > 0)
        }
        assertTrue(strong.breakoutShareUp > calm.breakoutShareUp + 0.1, "momentum/breakout evidence must fatten the bull tail")
        assertEquals(listOf("bull_breakout", "bull_continuation", "range", "bear_reversal", "sharp_decline"), strong.scenarios.map { it.key })
        assertEquals(Scenario.MILD_UP, ScenarioEngine.realized(strong.rangeBand + 1, strong.rangeBand, strong.breakoutBand))
        assertEquals(Scenario.STRONG_DOWN, ScenarioEngine.realized(-strong.breakoutBand - 1, strong.rangeBand, strong.breakoutBand))

        val d = ScenarioDistribution(strong)
        assertEquals(1.0, d.probAbove(-1e9), 1e-9)
        assertEquals(strong.pUp, d.probAbove(strong.rangeBand), 1e-6)
        assertEquals(strong.p(Scenario.STRONG_UP), d.probAbove(strong.breakoutBand), 1e-6)
        val mean = strong.scenarios.sumOf { it.probability * it.meanMove }
        assertEquals(mean, d.expect { it }, 3.0)
        assertTrue(d.probAbove(10.0) >= d.probAbove(50.0))
    }

    // ------------------------------------------------------------------ 8 — information shock

    @Test fun informationShockMeasuresMovesInUnitsOfNormal() {
        val nasdaq = InstrumentData("G_NASDAQ", 20_200.0, 20_000.0) // +1.0 %
        val s = snap(preOpen, mapOf(GlobalAsset.NASDAQ to nasdaq))
        val shock = InformationShockEngine().analyze(InformationShockEngine.Inputs(
            s, Session.sessionStart(preOpen), DataNormalizer(EngineState()), emptyList(),
            rel(globalNormalAbs = mapOf(GlobalAsset.NASDAQ to 0.25)), null, FutureExpectation(), Double.NaN, Double.NaN), preOpen)
        val g = shock.sources.first { it.kind == ShockKind.GLOBAL }
        assertEquals(4.0, g.multiple, 1e-6)
        assertTrue(g.detail.contains("4.0× normal"), g.detail)
        assertTrue(shock.direction > 0 && shock.level >= ShockLevel.MINOR, shock.toString())
        // the same +1 % when ±1 % is normal is no shock at all
        val calm = InformationShockEngine().analyze(InformationShockEngine.Inputs(
            s, Session.sessionStart(preOpen), DataNormalizer(EngineState()), emptyList(),
            rel(globalNormalAbs = mapOf(GlobalAsset.NASDAQ to 1.0)), null, FutureExpectation(), Double.NaN, Double.NaN), preOpen)
        assertEquals(ShockLevel.NONE, calm.level)
    }

    // ------------------------------------------------------------------ 4 — future expectation

    @Test fun futureExpectationWarnsWhenPriceRisesButTheExpectedFutureDeteriorates() {
        val state = EngineState()
        val fe = FutureExpectationEngine(state)
        val bull = EngineSignal("Market structure", 0.6, 0.95)
        fun run(t: Long, r: RelativeBaselines.Result) = fe.analyze(FutureExpectationEngine.Inputs(
            snap(t), Session.sessionStart(t), DataNormalizer(state), bull, EngineSignal("Breadth", 0.5, 0.85),
            EngineSignal("Sector", 0.4, 0.8), emptyList(), emptyMap(), NewsHorizon.M30_120, r, null), t)
        val before = run(t11, rel(pcrRel = 0.3, skew = 2.0, basisExcess = 0.10))
        assertTrue(before.expectation.expected > 0.2, before.expectation.toString())
        val after = run(t11 + 35 * 60_000L, rel(pcrRel = -0.4, skew = 4.0, iv30 = 8.0, basisExcess = -0.10))
        val e = after.expectation
        assertTrue(e.current > 0.4, "price is still rising")
        assertTrue(e.change <= -0.2, e.toString())
        assertEquals(ExpectationState.BULL_REVERSAL_WARNING, e.state)
        assertTrue(after.driverScore < 0, "the EXPECTATION driver turns bearish before price does")
    }

    // ------------------------------------------------------------------ 5 — regime (conflict ⇒ wait)

    @Test fun conflictingBlocksAreNotForcedIntoUpOrDown() {
        fun classify(drivers: List<DriverInput>) = RegimeEngineV5(EngineState()).classify(RegimeEngineV5.Inputs(
            RegimeResult(Regime.BULL_TREND, RegimeClass.TREND, listOf("Composite score +0.30"), emptyList()), drivers, 30.0,
            VIXEngine.Result(EngineSignal("India VIX", 0.0, 0.9), VixState.STABLE, 13.0, 50.0), 0.0, InformationShock(),
            FutureExpectation(), rel(), false, 1.0), t11)
        val split = classify(listOf(
            DriverInput(Driver.PRICE, 0.5, 0.9), DriverInput(Driver.GLOBAL, 0.5, 0.9), DriverInput(Driver.DERIVATIVES, -0.5, 0.9),
            DriverInput(Driver.NEWS, -0.5, 0.8), DriverInput(Driver.BREADTH, 0.0, 0.9), DriverInput(Driver.EXPECTATION, 0.0, 0.5)))
        assertEquals(PrimaryRegime.CONFLICT, split.primary, split.reasons.toString())
        assertEquals(RegimeClass.NORMAL, split.weightTable)
        assertTrue(split.quality < 0.4)
        val agree = classify(listOf(
            DriverInput(Driver.PRICE, 0.5, 0.9), DriverInput(Driver.GLOBAL, 0.4, 0.9), DriverInput(Driver.DERIVATIVES, 0.3, 0.9),
            DriverInput(Driver.NEWS, 0.0, 0.8), DriverInput(Driver.BREADTH, 0.3, 0.9), DriverInput(Driver.EXPECTATION, 0.2, 0.5)))
        assertEquals(PrimaryRegime.TREND_UP, agree.primary)
        assertEquals(RegimeClass.TREND, agree.weightTable)
        assertTrue(agree.quality > 0.6, agree.toString())
        // the strategy layer refuses to trade a conflict
        val trend = RegimeAssessment(PrimaryRegime.TREND_UP, quality = 0.8)
        val plan = select(scenarios(0.66, 0.1, 0.8, 32.0, listOf("OR_BREAKOUT"), FuturesState.LONG_BUILDUP, trend), split, 0.0)
        assertEquals(StrategyType.NO_TRADE, plan.type)
    }

    // ------------------------------------------------------------------ 10/11 — option + strategy selection

    @Test fun strategyFollowsRegimeScenarioShapeAndIv() {
        val trend = RegimeAssessment(PrimaryRegime.TREND_UP, quality = 0.8)
        val strongSet = scenarios(0.70, 0.08, 0.85, 34.0, listOf("OR_BREAKOUT", "ACCELERATING_UP"), FuturesState.SHORT_COVERING, trend)
        val strong = select(strongSet, trend, ivRel = 0.0)
        assertEquals(StrategyType.BUY_CALL, strong.preferredByRegime, strong.rationale.toString())
        strong.chosen?.let { assertTrue(it.type.directional > 0) }
        // same strong view but rich options ⇒ finance the call with a short call (debit spread)
        assertEquals(StrategyType.BULL_CALL_SPREAD, select(strongSet, trend, ivRel = 0.25).preferredByRegime)
        // moderate, range-ish bull ⇒ debit spread
        val moderate = select(scenarios(0.58, 0.14, 0.1, 16.0, emptyList(), FuturesState.NEUTRAL, trend, rangeEvidence = 0.6), trend, 0.0)
        assertEquals(StrategyType.BULL_CALL_SPREAD, moderate.preferredByRegime, moderate.rationale.toString())
        val spread = moderate.candidates.first { it.type == StrategyType.BULL_CALL_SPREAD }
        assertEquals(2, spread.legs.size)
        assertTrue(spread.netPremium > 0 && spread.maxLoss > 0 && spread.maxProfit > 0)
        assertTrue(spread.legs[1].strike > spread.legs[0].strike)
        // range + IV above normal ⇒ defined-risk option selling
        val range = RegimeAssessment(PrimaryRegime.RANGE, quality = 0.7)
        val condor = select(scenarios(0.14, 0.12, 0.0, 14.0, emptyList(), FuturesState.NEUTRAL, range, rangeEvidence = 0.8), range, 0.2, 16.0)
        assertEquals(StrategyType.IRON_CONDOR, condor.preferredByRegime, condor.rationale.toString())
        val ic = condor.candidates.first { it.type == StrategyType.IRON_CONDOR }
        assertEquals(4, ic.legs.size)
        assertTrue(ic.netPremium < 0, "condor collects a credit")
        assertTrue(ic.maxLoss > 0 && ic.maxProfit > 0)
        // options priced well above the realised range (IV 22 % vs ~13 % realised), held to the time exit:
        // time decay outweighs gamma and costs, so the condor is the chosen structure
        val rangeSet = scenarios(0.14, 0.12, 0.0, 14.0, emptyList(), FuturesState.NEUTRAL, range, rangeEvidence = 0.8)
        val rich = select(rangeSet, range, 0.35, 22.0, days = 2.0, hold = 255)
        val icx = rich.candidates.first { it.type == StrategyType.IRON_CONDOR }
        assertEquals(255, icx.holdMinutes)
        assertTrue(icx.probProfit > 0.5 && icx.expectedValue > 0, "rich-IV condor in a range: $icx")
        assertEquals(StrategyType.IRON_CONDOR, rich.type)
        println("rich condor: ${icx.instrument} credit ${-icx.netPremium} pop ${icx.probProfit} EV ${icx.expectedValue} maxLoss ${icx.maxLoss}")
        // range with cheap options ⇒ nothing worth selling
        assertEquals(StrategyType.NO_TRADE, select(scenarios(0.14, 0.12, 0.0, 14.0, emptyList(), FuturesState.NEUTRAL, range, 0.8), range, -0.1).preferredByRegime)
        println("strong: ${strong.rationale} → ${strong.chosen?.instrument} EV ${strong.chosen?.expectedValue}")
        println("condor: ${ic.instrument} credit ${-ic.netPremium} pop ${ic.probProfit} EV ${ic.expectedValue}")
    }

    // ------------------------------------------------------------------ 12 — risk engine

    @Test fun riskEngineSizesFromTheStopAndEnforcesHardLimits() {
        val risk = RiskEngine(RiskConfig(capital = 200_000.0, maxRiskPerTradePct = 2.0), TransactionCosts(lotSize = 65))
        val ok = risk.assess(longCall(), t11, 60, ShadowBook(), 13.0, InformationShock(), 0.0, true)
        assertTrue(ok.approved, ok.checks.toString())
        assertEquals(1, ok.lots) // stop 35 % of ₹100 + ₹1 costs = ₹36/unit = ₹2,340/lot; budget ₹4,000
        assertEquals(65.0, ok.exit!!.stopValue, 1e-9)
        assertEquals(160.0, ok.exit.targetValue, 1e-9)
        assertTrue(ok.exit.timeExitAt <= t11 + 60 * 60_000L)

        val pos = ShadowTrader().let { st ->
            st.update(ShadowTrader.Context(t11, spot, chain(spot, 13.0, t11), Decision.PAPER_TRADE,
                StrategyPlan(StrategyType.BUY_CALL, longCall()), ok, RegimeAssessment(PrimaryRegime.TREND_UP), TradeQuality(),
                0.7, FutureExpectation(), InformationShock(), false, true))
            st.current
        }
        assertEquals(1, pos.open.size)
        assertFalse(risk.assess(longCall(), t11, 60, pos, 13.0, InformationShock(), 0.0, true).approved, "max open positions")

        val lossDay = ShadowBook(closed = listOf(ShadowTrade(pos.open[0], t11 - 60_000, 0.0, spot, "STOP", -170.0, 60.0, -11_100.0, -2.0)))
        val stopped = risk.assess(longCall(), t11, 60, lossDay, 13.0, InformationShock(), 0.0, true)
        assertFalse(stopped.checks.first { it.name == "Daily loss limit" }.passed)
        assertFalse(risk.assess(longCall(), t11, 60, ShadowBook(), 40.0, InformationShock(), 0.0, true).checks.first { it.name == "Max IV" }.passed)
        assertFalse(risk.assess(longCall(), t11, 60, ShadowBook(), 13.0, InformationShock(score = 0.8, level = ShockLevel.MAJOR), 0.0, true).approved)
        val late = day.atTime(15, 0).atZone(Session.IST).toInstant().toEpochMilli()
        assertFalse(risk.assess(longCall(), late, 60, ShadowBook(), 13.0, InformationShock(), 0.0, true).checks.first { it.name == "Entry window" }.passed)
        // a lot that risks more than the budget is refused, not shrunk below one lot
        assertEquals(0, risk.assess(longCall(premium = 400.0), t11, 60, ShadowBook(), 13.0, InformationShock(), 0.0, true).lots)
    }

    // ------------------------------------------------------------------ 14 — shadow execution

    @Test fun shadowTraderFillsMarksAndExitsNetOfCosts() {
        val ch0 = chain(spot, 13.0, t11)
        val leg = ch0.rows.first { it.strike == 25_000.0 }.call
        val cand = longCall(premium = leg.ask)
        val risk = RiskEngine(RiskConfig(), TransactionCosts()).assess(cand, t11, 60, ShadowBook(), 13.0, InformationShock(), 0.0, true)
        assertTrue(risk.approved)
        fun ctx(t: Long, ch: OptionChain, decision: Decision = Decision.WAIT, dataError: Boolean = false) = ShadowTrader.Context(
            t, spot, ch, decision, StrategyPlan(StrategyType.BUY_CALL, cand), risk, RegimeAssessment(PrimaryRegime.TREND_UP), TradeQuality(),
            0.7, FutureExpectation(), InformationShock(), dataError, true)

        val win = ShadowTrader()
        win.update(ctx(t11, ch0, Decision.PAPER_TRADE))
        assertEquals(1, win.current.open.size)
        val s1 = win.update(ctx(t11 + 10 * 60_000L, chain(spot, 13.0, t11, priceMult = 1.7)))
        assertEquals(0, s1.open.size)
        val t = win.current.closed.single()
        assertEquals("TARGET", t.reason)
        assertTrue(t.costs > 40 && t.pnl > 0 && t.pnl < t.grossPnlPerUnit * 65, t.toString()) // brokerage alone is ₹40
        assertTrue(s1.events.any { it.startsWith("Closed") })

        val loss = ShadowTrader()
        loss.update(ctx(t11, ch0, Decision.TRADE))
        loss.update(ctx(t11 + 10 * 60_000L, chain(spot, 13.0, t11, priceMult = 0.5)))
        assertEquals("STOP", loss.current.closed.single().reason)
        assertTrue(loss.current.closed.single().rMultiple < -0.9)

        val emergency = ShadowTrader()
        emergency.update(ctx(t11, ch0, Decision.PAPER_TRADE))
        emergency.update(ctx(t11 + 60_000L, ch0, dataError = true))
        assertTrue(emergency.current.closed.single().reason.startsWith("EMERGENCY"))

        val off = ShadowTrader(enabled = false)
        off.update(ctx(t11, ch0, Decision.TRADE))
        assertTrue(off.current.open.isEmpty())
        // WAIT never opens a position
        val idle = ShadowTrader(); idle.update(ctx(t11, ch0, Decision.WAIT)); assertTrue(idle.current.open.isEmpty())
    }

    // ------------------------------------------------------------------ 13 — calibration

    @Test fun progressiveCalibrationShrinksTowardObservedFrequencies() {
        val rnd = Random(9)
        val recs = (0 until 60).map { i ->
            val realized = if (rnd.nextDouble() < 0.4) 1 else if (rnd.nextBoolean()) -1 else 0
            PredictionRecord(id = "$i", timestamp = i * 60_000L, spot = spot, pBull = 0.8, pBear = 0.1, pRange = 0.1, confidence = "HIGH",
                confidenceValue = 0.8, regime = "BULL_TREND", expectedMove = 50.0, sigma = 80.0, decision = "WAIT", source = "t",
                outcomes = listOf(Outcome(30, spot, 0.0, 0.0, 0.0, realized = realized)))
        }
        val cal = ProbabilityCalibrator.fit(recs, minSamples = 150)
        assertTrue(cal.horizons.isEmpty() && !cal.info.calibrated)
        assertEquals(0.4, cal.partialWeight.getValue(30), 1e-9)
        val hp = cal.applyProgressive(0.8, 0.1, 0.1, 30)
        assertTrue(hp.partial && !hp.calibrated)
        assertTrue(hp.pBull < 0.8 && hp.pBull > 0.45, "80% model score that is right ~40% of the time is shrunk: $hp")
        assertEquals("PARTIAL", hp.level)
        val d = dir(0.8, 0.1, 0.8).copy(horizons = cal.allHorizons(0.8, 0.1, 0.1, ProbabilityCalibrator.HORIZONS))
        assertTrue(d.decisionProbs(30).partial)
        assertFalse(d.decisionProbs(60).partial) // no outcomes at 60 m
    }

    @Test fun scenarioCalibrationLearnsRealisedBuckets() {
        val raw = mapOf(Scenario.STRONG_UP.name to 0.5, Scenario.MILD_UP.name to 0.2, Scenario.RANGE.name to 0.2,
            Scenario.MILD_DOWN.name to 0.05, Scenario.STRONG_DOWN.name to 0.05)
        val recs = (0 until 120).map { i ->
            PredictionRecord(id = "$i", timestamp = i * 60_000L, spot = spot, pBull = 0.7, pBear = 0.1, pRange = 0.2, confidence = "HIGH",
                confidenceValue = 0.8, regime = "BULL_TREND", expectedMove = 50.0, sigma = 80.0, decision = "WAIT", source = "t",
                horizonMinutes = 60, scenarioProbs = raw, rangeBand = 20.0, breakoutBand = 60.0,
                outcomes = listOf(Outcome(60, spot + 30, if (i % 10 == 0) 80.0 else 30.0, 0.0, 0.0, realized = 1)))
        }
        val cal = ProbabilityCalibrator.fit(recs, minSamples = 60)
        val (p, level) = cal.applyScenarios(Scenario.values().associateWith { raw.getValue(it.name) })!!
        assertEquals("FULL", level)
        assertTrue(p.getValue(Scenario.STRONG_UP) < 0.3, "breakouts were predicted at 50% but happened 10%: $p")
        assertTrue(p.getValue(Scenario.MILD_UP) > 0.4)
        assertEquals(1.0, p.values.sum(), 1e-9)
        assertNull(CalibrationModel().applyScenarios(p))
    }

    // ------------------------------------------------------------------ 2 — relative normalization

    @Test fun relativeBaselinesCompareWithTheirOwnNormal() {
        val b = RelativeBaselines()
        val d0 = day.toEpochDay()
        listOf(0.90, 0.94, 0.98).forEachIndexed { k, v -> b.observe("PCR", d0 - 3 + k, v); b.observe("PCR", d0 - 3 + k, v) }
        val (normal, days) = b.normalOf("PCR", d0)
        assertEquals(0.94, normal, 1e-9)
        assertEquals(3, days)
        assertEquals(0.19, 1.12 / normal - 1, 0.01) // "PCR 1.12 vs normal 0.94 → +19 %"
        b.observe("PCR", d0, 1.5) // today's value never counts toward today's normal
        assertEquals(0.94, b.normalOf("PCR", d0).first, 1e-9)
        val restored = RelativeBaselines().apply { importState(b.exportState()) }
        assertEquals(0.94, restored.normalOf("PCR", d0).first, 1e-9)
    }

    // ------------------------------------------------------------------ 16 — final decision object

    @Test fun decisionObjectUsesSpecKeys() {
        val json = Json { encodeDefaults = true; allowSpecialFloatingPointValues = true }
        val txt = json.encodeToString(DecisionState.serializer(), DecisionState(
            regime = "TREND_UP", direction = "UP", rawProbability = 0.78, calibratedProbability = 0.72,
            scenarios = mapOf("bull_continuation" to 0.51), strategy = "BUY_CALL", instrument = "NIFTY 24500 CE", action = "TRADE"))
        for (k in listOf("\"market\"", "\"regime\"", "\"direction\"", "\"raw_probability\"", "\"calibrated_probability\"", "\"confidence\"",
            "\"scenarios\"", "\"future_expectation\"", "\"information_shock\"", "\"trade_quality\"", "\"instrument\"", "\"strategy\"", "\"action\""))
            assertTrue(txt.contains(k), "missing $k in $txt")
    }

    // ------------------------------------------------------------------ whole pipeline

    @Test fun simulatedSessionsProduceCompleteDecisionStates() {
        val sim = SimulatedMarket(seed = 31, startDate = day.minusDays(1))
        val e = NiftyDirectionEngine(EngineConfig(requireMarketOpen = false))
        val counts = HashMap<String, Int>()
        var last = e.process(sim.collect(0))
        repeat(375 * 3) {
            val o = e.process(sim.collect(0))
            last = o
            assertEquals(1.0, o.scenarios.scenarios.sumOf { it.probability }, 1e-9)
            assertTrue(o.scenarios.scenarios.all { it.probability in 0.0..1.0 })
            assertEquals(o.decision.decision.name, o.decisionState.action)
            assertEquals(RegimeEngineV5.table(o.regimeV5.primary), o.regimeV5.weightTable)
            assertEquals(o.regimeV5.primary.name, o.decisionState.regime)
            assertTrue(o.direction.drivers.any { it.driver == Driver.EXPECTATION })
            if (o.decision.decision == Decision.TRADE || o.decision.decision == Decision.PAPER_TRADE) {
                assertNotNull(o.strategy.chosen); assertTrue(o.risk.approved && o.quality.passed)
            }
            if (o.regimeV5.primary == PrimaryRegime.CONFLICT) assertTrue(o.decision.decision != Decision.TRADE && o.decision.decision != Decision.PAPER_TRADE)
            counts.merge(o.regimeV5.primary.name + "/" + o.decision.decision.name, 1, Int::plus)
        }
        val sh = last.shadow
        assertTrue(sh.trades > 0, "shadow mode should have executed paper trades over three simulated sessions")
        assertTrue(sh.recent.all { !it.pnl.isNaN() && it.costs > 0 })
        assertTrue(last.normalized.readings.any { it.name == "PCR" && it.basis.contains("day normal") }, last.normalized.readings.toString())
        println("v5 regime/decision mix: $counts")
        println("shadow: ${sh.trades} trades, win ${"%.0f".format(sh.winRate * 100)}%, net ₹${"%,.0f".format(sh.totalPnl)}, avg R ${"%.2f".format(sh.avgR)}")
    }
}

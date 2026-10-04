package com.niftyengine.engine

import com.niftyengine.engine.core.EngineState
import com.niftyengine.engine.core.Session
import com.niftyengine.engine.engines.BreadthEngine
import com.niftyengine.engine.engines.CalibrationModel
import com.niftyengine.engine.engines.DataCollector
import com.niftyengine.engine.engines.DataNormalizer
import com.niftyengine.engine.engines.DataQualityEngine
import com.niftyengine.engine.engines.DirectionProbabilityEngine
import com.niftyengine.engine.engines.DirectionProbabilityEngine.DriverInput
import com.niftyengine.engine.engines.EventIntelligenceEngine
import com.niftyengine.engine.engines.ExpectedMoveEngine
import com.niftyengine.engine.engines.FlowEngine
import com.niftyengine.engine.engines.FuturesPositionEngine
import com.niftyengine.engine.engines.FutureExpectationEngine
import com.niftyengine.engine.engines.GiftNiftyEngine
import com.niftyengine.engine.engines.GlobalRiskEngine
import com.niftyengine.engine.engines.IndiaMacroEngine
import com.niftyengine.engine.engines.InformationShockEngine
import com.niftyengine.engine.engines.MarketRegimeEngine
import com.niftyengine.engine.engines.MarketStructureEngine
import com.niftyengine.engine.engines.ModelHealthEngine
import com.niftyengine.engine.engines.NiftyWeightEngine
import com.niftyengine.engine.engines.OptionSelectionEngine
import com.niftyengine.engine.engines.OptionsPositionEngine
import com.niftyengine.engine.engines.PointInTimeValidator
import com.niftyengine.engine.engines.ProbabilityCalibrator
import com.niftyengine.engine.engines.RegimeEngineV5
import com.niftyengine.engine.engines.RelativeBaselines
import com.niftyengine.engine.engines.RiskEngine
import com.niftyengine.engine.engines.ScenarioDistribution
import com.niftyengine.engine.engines.ScenarioEngine
import com.niftyengine.engine.engines.SectorEngine
import com.niftyengine.engine.engines.ShadowTrader
import com.niftyengine.engine.engines.StrategySelector
import com.niftyengine.engine.engines.TradeDecisionEngine
import com.niftyengine.engine.engines.TradeQualityEngine
import com.niftyengine.engine.engines.VIXEngine
import com.niftyengine.engine.model.ConfidenceLevel
import com.niftyengine.engine.model.DecisionSnapshot
import com.niftyengine.engine.model.DecisionState
import com.niftyengine.engine.model.RiskAssessment
import com.niftyengine.engine.model.StrategyPlan
import com.niftyengine.engine.model.TradeQuality
import com.niftyengine.engine.model.Driver
import com.niftyengine.engine.model.EngineOutput
import com.niftyengine.engine.model.EngineSignal
import com.niftyengine.engine.model.EventStage
import com.niftyengine.engine.model.InformationShock
import com.niftyengine.engine.model.MarketSnapshot
import com.niftyengine.engine.model.NewsHorizon
import com.niftyengine.engine.model.PrimaryRegime
import com.niftyengine.engine.model.Regime
import com.niftyengine.engine.model.RiskConfig
import com.niftyengine.engine.model.StrategyType
import kotlinx.serialization.Serializable
import kotlin.math.abs

@Serializable
data class EngineConfig(
    val horizonMinutes: Int = 60,
    val minProbability: Double = 0.65,
    val minConfidence: ConfidenceLevel = ConfidenceLevel.HIGH,
    val minExpectedMovePts: Double = 40.0,
    val requireMarketOpen: Boolean = true,
    val maxSpreadPct: Double = 3.0,
    val minOi: Double = 2_000.0,
    val minVolume: Double = 500.0,
    val costs: com.niftyengine.engine.core.TransactionCosts = com.niftyengine.engine.core.TransactionCosts(),
    val minOptionProfitProb: Double = 0.50,
    val eventThresholdBump: Double = 0.08,
    val minDataQuality: Double = 0.70,
    val confirmCycles: Int = 2,
    val requireCalibration: Boolean = true,
    /** Historical market-only backtest: no option chain/news/global exist for past days, so only NIFTY is critical. */
    val marketOnlyBacktest: Boolean = false,
    // ---- v5
    /** Deterministic risk limits (sizing, stops, daily loss, IV/event caps). */
    val risk: RiskConfig = RiskConfig(),
    /** Execute approved decisions as virtual (shadow) trades. The app never places real orders. */
    val shadowMode: Boolean = true,
    val minTradeQuality: Double = 0.65,
    val enableLongOptions: Boolean = true,
    val enableSpreads: Boolean = true,
    val enableCondor: Boolean = true,
    // ---- v5.1
    /** Model health ≥ this may TRADE; between [healthShadow] and this → shadow only; below [healthShadow] → no signal. */
    val healthEligible: Double = 75.0,
    val healthShadow: Double = 60.0,
    /** Clock-skew allowance: inputs stamped up to this far after the decision time are accepted; beyond it rejected. */
    val futureToleranceSec: Double = 10.0,
    /** During the session the critical inputs (NIFTY, futures, option chain) must be stamped within this of each other. */
    val maxCriticalSkewSec: Double = 180.0,
)

const val ENGINE_VERSION = "5.1.2"

/**
 * NIFTY Direction Engine v5 — one cycle of the decision pipeline:
 *
 *   data → normalized/relative state → market state engines (structure, heavyweights, sectors, breadth, futures,
 *        options, VIX, global, macro, flows, GIFT, news/event intelligence)
 *        → future expectation (what the market prices) → information shock (what changed)
 *        → regime (v5 primary + quality) → direction probabilities (regime-weighted, EXPECTATION driver)
 *        → calibration → expected move → scenarios → option selector → strategy selector
 *        → trade quality → risk engine → TRADE / WAIT → shadow execution → outcome log → calibration
 *
 * The engine is stateful (tick history, driver persistence, regime/expectation history, baselines, shadow book), so
 * feed snapshots in chronological order. Use one instance per live session or per replay run.
 * Gemini (in the app) only reads news into structured events; probabilities, option and strategy selection, risk and
 * execution are deterministic code.
 */
class NiftyDirectionEngine(val config: EngineConfig = EngineConfig()) {
    val state = EngineState()
    private val norm = DataNormalizer(state)
    private val structure = MarketStructureEngine(norm)
    private val weights = NiftyWeightEngine()
    private val sectors = SectorEngine(norm)
    private val breadth = BreadthEngine(norm)
    private val futures = FuturesPositionEngine(state)
    private val options = OptionsPositionEngine(state)
    private val vix = VIXEngine(norm)
    private val global = GlobalRiskEngine(norm)
    private val macro = IndiaMacroEngine(norm)
    private val flows = FlowEngine()
    /** 20 — event intelligence (lifecycle, expectations, pricing-in, reaction, horizons). State persisted by the app. */
    val eventIntel = EventIntelligenceEngine()
    private val giftEngine = GiftNiftyEngine()
    private val regime = MarketRegimeEngine(state)
    private val direction = DirectionProbabilityEngine(state)
    private val move = ExpectedMoveEngine()
    private val optionSelect = OptionSelectionEngine(
        OptionSelectionEngine.Filters(minOi = config.minOi, minVolume = config.minVolume, maxSpreadPct = config.maxSpreadPct), config.costs)
    private val decision = TradeDecisionEngine(TradeDecisionEngine.Params(
        minProbability = config.minProbability, minConfidence = config.minConfidence, minExpectedMovePts = config.minExpectedMovePts,
        requireMarketOpen = config.requireMarketOpen, minOptionProfitProb = config.minOptionProfitProb,
        eventThresholdBump = config.eventThresholdBump, minDataQuality = config.minDataQuality,
        confirmCycles = config.confirmCycles, requireCalibration = config.requireCalibration))
    private val criticalFeeds = if (config.marketOnlyBacktest) setOf("NIFTY") else setOf("NIFTY", "Futures", "Options")
    /** 00 — every input must be stamped at or before the decision time. */
    private val validator = PointInTimeValidator((config.futureToleranceSec * 1000).toLong(), criticalFeeds)
    private val quality = DataQualityEngine(criticalFeeds, (config.futureToleranceSec * 1000).toLong(), (config.maxCriticalSkewSec * 1000).toLong())
    // ---- v5 layers
    /** 02c — rolling relative baselines. State persisted by the app. */
    val baselines = RelativeBaselines()
    private val expectationEngine = FutureExpectationEngine(state)
    private val shockEngine = InformationShockEngine()
    private val regimeV5 = RegimeEngineV5(state)
    private val scenarioEngine = ScenarioEngine()
    private val strategySelector = StrategySelector(config.costs, StrategySelector.Params(
        enableLongOptions = config.enableLongOptions, enableSpreads = config.enableSpreads, enableCondor = config.enableCondor,
        maxSpreadPct = config.maxSpreadPct, minOi = config.minOi, minVolume = config.minVolume))
    private val qualityGate = TradeQualityEngine(TradeQualityEngine.Params(minScore = config.minTradeQuality))
    private val riskEngine = RiskEngine(config.risk.copy(maxSpreadPct = config.maxSpreadPct), config.costs)
    /** 28 — shadow execution book. State persisted by the app. */
    val shadow = ShadowTrader(config.costs, config.shadowMode)
    private val healthEngine = ModelHealthEngine(config.healthEligible, config.healthShadow)
    private var prevSpot = Double.NaN
    private var prevSpotT = 0L

    /**
     * Fitted calibration (from the prediction log). Replace whenever a new fit is available; each cycle captures it ONCE
     * at the start, so one decision never mixes two calibrations even if a refit lands mid-cycle.
     */
    @Volatile var calibration: CalibrationModel = CalibrationModel()

    fun process(input: MarketSnapshot): EngineOutput {
        // One decision = one frozen snapshot: the decision time is the snapshot's timestamp, the calibration is captured once.
        val wall = input.timestamp
        val cal = calibration
        optionSelect.optionCalibrator = cal.optionFn()
        // 00 — point-in-time validation before anything else: nothing stamped after the decision time enters the cycle.
        val pit = validator.validate(input)
        val raw = pit.snapshot
        // Outside market hours analyse the most recent session (last bar), not an empty "today".
        val lastBar = raw.nifty.intraday.lastOrNull()?.t
        val now = if (!Session.isOpen(wall) && lastBar != null && lastBar < wall && wall - lastBar < 5 * 86_400_000L) lastBar else wall
        state.rollDay(now)
        val sessionStart = Session.sessionStart(now)
        val sessionDay = Session.zdt(now).toLocalDate()
        // Daily history must end before the analysed session (previous-day levels, percentiles, normals).
        // By DATE: some feeds stamp daily candles at 00:00, which is earlier than 09:15 of the same day.
        fun priorDays(d: com.niftyengine.engine.model.InstrumentData) = d.copy(daily = d.daily.filter { Session.zdt(it.t).toLocalDate() < sessionDay })
        val s = raw.copy(
            nifty = priorDays(raw.nifty), vix = raw.vix?.let(::priorDays), bankNifty = raw.bankNifty?.let(::priorDays),
            constituents = raw.constituents.mapValues { priorDays(it.value) }, sectors = raw.sectors.mapValues { priorDays(it.value) },
            global = raw.global.mapValues { priorDays(it.value) },
        )
        recordTicks(s, now)
        val health = DataCollector.health(s)
        // 01b — freshness/validity of every input; ages are measured against wall-clock time.
        val dq0 = quality.assess(s, wall, prevSpot, prevSpotT, pit.report)
        if (s.nifty.last > 0) { prevSpot = s.nifty.last; prevSpotT = wall }

        val st = structure.analyze(s, now, sessionStart)
        val hw = weights.analyze(s)
        val sec = sectors.analyze(s, hw.contributionBySector, now, sessionStart)
        val br = quality.gate(breadth.analyze(s, hw.report, now, sessionStart), dq0)
        val fu = futures.analyze(s, now)
        val op = options.analyze(s, now)
        val vx = vix.analyze(s, now, sessionStart)
        // 02c — every positioning / volatility / global input relative to its own normal
        val rel = baselines.analyze(s, now, sessionStart, norm, st, op, fu, vx)
        val gl = global.analyze(s, now, sessionStart, rel.globalTypical)
        val mc = macro.analyze(s, now, sessionStart)
        val fl = quality.gate(flows.analyze(s), dq0)
        // 21 — GIFT Nifty: opening factor (pre-open implied gap → gap behaviour in the first hour).
        val gift = giftEngine.analyze(s, wall)
        val giftSig = quality.gate(gift.signal, dq0)
        // Before the open NIFTY isn't trading: GIFT's implied open is the market's reaction to overnight news.
        val liveBaseline = EventIntelligenceEngine.baselineFrom(s, wall, usePrevClose = false).let { b ->
            if (gift.impliedOpenForPricing.isNaN()) b else b.copy(nifty = gift.impliedOpenForPricing, futures = s.giftNifty?.last ?: b.futures)
        }
        // News is point-in-time against the wall clock (overnight/pre-open news must be visible before 09:15).
        val newsHorizon = NewsHorizon.forMinutes(config.horizonMinutes)
        val nw = eventIntel.process(
            s.news, s.eventAnalyses, wall,
            market = liveBaseline,
            prevCloseBaseline = EventIntelligenceEngine.baselineFrom(s, sessionStart, usePrevClose = true),
            niftySeries = st.series, decisionHorizon = newsHorizon,
        )
        // Stale/invalid inputs stop acting as live drivers; degraded ones are down-weighted.
        val stSig = quality.gate(st.signal, dq0); val hwSig = quality.gate(hw.signal, dq0); val secSig = quality.gate(sec.signal, dq0)
        val fuSig = quality.gate(fu.signal, dq0); val opSig = quality.gate(op.signal, dq0); val vxSig = quality.gate(vx.signal, dq0)
        val glSig = quality.gate(gl.signal, dq0); val nwSig = quality.gate(nw.signal, dq0)

        val derivatives = blend("Derivatives", fuSig, opSig, 0.5)
        val sectorDriver = blend("Sector+heavyweight", hwSig, secSig, 0.5)

        // 23 — what the market expects for the near future, and how that expectation is changing
        val fe = expectationEngine.analyze(FutureExpectationEngine.Inputs(
            s, sessionStart, norm, stSig, br, sectorDriver, nw.events, nw.horizons, newsHorizon, rel, gift.report), now)
        val expSig = EngineSignal("Future expectation", fe.driverScore, fe.driverConfidence,
            listOf("EXP_${fe.expectation.state.name}"), fe.expectation.components.map {
                com.niftyengine.engine.model.Detail(it.name, "%+.2f · %s".format(it.score, it.note))
            } + com.niftyengine.engine.model.Detail("Current / expected / Δ30m",
                "%+.2f / %+.2f / %+.2f".format(fe.expectation.current, fe.expectation.expected, fe.expectation.change)))
        // 24 — what changed: event surprises and market moves in units of normal
        val vixC15 = s.vix?.let { norm.features(it, now, sessionStart, 5.0).c15m } ?: Double.NaN
        val shock = shockEngine.analyze(InformationShockEngine.Inputs(
            s, sessionStart, norm, nw.events, rel, gift.report, fe.expectation, st.c15m, vixC15), now)

        val inputs = listOf(
            DriverInput(Driver.PRICE, stSig.score, stSig.confidence),
            DriverInput(Driver.DERIVATIVES, derivatives.score, derivatives.confidence),
            DriverInput(Driver.SECTOR, sectorDriver.score, sectorDriver.confidence),
            DriverInput(Driver.GLOBAL, glSig.score, glSig.confidence),
            DriverInput(Driver.BREADTH, br.score, br.confidence),
            DriverInput(Driver.VIX, vxSig.score, vxSig.confidence),
            DriverInput(Driver.MACRO, mc.signal.score, mc.signal.confidence),
            DriverInput(Driver.FLOWS, fl.score, fl.confidence),
            DriverInput(Driver.NEWS, nwSig.score, nwSig.confidence),
            DriverInput(Driver.GIFT_NIFTY, giftSig.score, giftSig.confidence),
            DriverInput(Driver.EXPECTATION, fe.driverScore, fe.driverConfidence),
        )
        val prelim = direction.preliminary(inputs)
        val rg = regime.classify(MarketRegimeEngine.Inputs(prelim, st, fu, op, vx, br.score, derivatives.score,
            hw.report.fakeBreadth,
            newsShock = nw.shock?.let { "Fresh unpriced ${it.type.label} event (${it.stage.label}): ${it.title.take(50)}" },
            newsContradicted = nw.contradicted.isNotEmpty()), now)
        // 12b — primary regime (selects the weight table and the strategy family)
        val rv5In = RegimeEngineV5.Inputs(rg, inputs, st.adx, vx, op.rangeEvidence, shock, fe.expectation, rel, nw.shock != null, dq0.score)
        var rv5 = regimeV5.classify(rv5In, now)
        val insideOr = !st.orHigh.isNaN() && s.nifty.last <= st.orHigh && s.nifty.last >= st.orLow
        val hwr = hw.report
        val prevClose = s.nifty.prevClose.takeIf { it > 0 } ?: s.nifty.last
        val concentration = if (abs(hwr.totalContributionPts) > prevClose * 0.003 && (hwr.fakeBreadth || abs(hwr.heavyweightDependence) > 0.8))
            0.08 to "Heavyweight concentration: top 5 carry %.0f%% of a %+.0f pt move".format(hwr.heavyweightDependence * 100, hwr.totalContributionPts)
        else null
        val rangeIn = DirectionProbabilityEngine.RangeInputs(st.adx, op.rangeEvidence, vx.state, insideOr)
        val trial = direction.compute(inputs, rg.regime, rv5.weightTable, rangeIn, commit = false, qualityScore = dq0.score, concentration = concentration)
        if (trial.conflictLevel == ConfidenceLevel.HIGH && rv5.primary in CONFLICT_PRONE)
            rv5 = regimeV5.toConflict(rv5, rv5In, now, "Driver conflict %.0f%% in the direction engine".format(trial.conflict * 100))
        val rawDir = direction.compute(inputs, rg.regime, rv5.weightTable, rangeIn, qualityScore = dq0.score, concentration = concentration)
        regimeV5.commit(rv5, now)
        // 19 — calibrated probabilities per horizon (full when enough outcomes, partial while they accumulate).
        val dir = rawDir.copy(
            horizons = cal.allHorizons(rawDir.pBull, rawDir.pBear, rawDir.pRange, ProbabilityCalibrator.HORIZONS),
            calibration = cal.info,
        )
        state.pushDirection(now, dir.directionalScore)

        val horizon = if (Session.isOpen(wall)) config.horizonMinutes.coerceAtMost(Session.minutesToClose(now).toInt().coerceAtLeast(15))
        else config.horizonMinutes
        val em = move.compute(ExpectedMoveEngine.Inputs(
            spot = s.nifty.last, horizonMinutes = horizon, vix = vx.level, atmIv = op.atmIv,
            realizedVol = st.realizedVolAnnual, histVol = st.histVolAnnual,
            eventShock = rg.regime == Regime.EVENT_SHOCK, vixState = vx.state,
            freshMajorEvent = nw.events.any { (it.analysis?.severity ?: 0.0) >= 0.6 && now - it.lastInfoAt <= 90 * 60_000L && it.surpriseMagnitude * it.unpriced >= 0.2 },
            globalStress = gl.globalVolStress, momentum = st.pressure, shockScore = shock.score,
        ), dir)
        // v5.1 hard critical-data gate: missing / stale / invalid / future-dated critical inputs, inputs from different
        // moments, or no volatility input at all ⇒ DATA ERROR — NO TRADE. Nothing is substituted with a neutral value.
        val dq = if (em.volInputs == 0) dq0.copy(circuitBreaker = dq0.circuitBreaker +
            "No volatility input (India VIX, ATM IV, realised and historical all missing) — the move cannot be estimated") else dq0
        val criticalFail = dq.circuitBreaker.isNotEmpty()
        val probs = dir.decisionProbs(em.horizonMinutes)
        // 14b — five scenarios and the move distribution they imply
        val scen = scenarioEngine.compute(ScenarioEngine.Inputs(
            spot = s.nifty.last, horizonMinutes = em.horizonMinutes, sigma = em.sigmaPoints, eventMultiplier = em.eventMultiplier,
            probs = probs, rawBull = rawDir.pBull, rawBear = rawDir.pBear, rawRange = rawDir.pRange,
            pressure = st.pressure, adx = st.adx, structureTags = st.signal.tags, trend = st.trend,
            futuresDay = fu.dayState, futuresIntraday = fu.intradayState, regime = rv5, shock = shock, expectation = fe.expectation,
            rangeEvidence = op.rangeEvidence,
        ), cal)
        val dist = ScenarioDistribution(scen)
        val optSpot = s.optionChain?.underlying?.takeIf { it > 0 } ?: s.nifty.last
        // 15 — option selector (strike), priced over the scenario distribution
        val oa = optionSelect.analyze(s.optionChain, optSpot, wall, dir, em, op.atmIv, probs, dist)
        // 26 — strategy selector
        val creditHold = if (Session.isOpen(wall)) riskEngine.minutesToTimeExit(wall).coerceAtLeast(em.horizonMinutes) else em.horizonMinutes
        val plan = if (criticalFail) StrategyPlan(rationale = listOf("Critical data unavailable — no strategy: " + dq.circuitBreaker.first()))
        else strategySelector.select(StrategySelector.Inputs(
            s.optionChain, optSpot, wall, em.horizonMinutes, scen, dist, rv5, oa, rel.ivRel, oa.atmIv, op.callWall, op.putWall,
            fe.expectation, shock, creditHold))
        // 25 — trade quality gate · 27 — risk engine (skipped when critical data failed: nothing to grade or size)
        val tq = if (criticalFail) TradeQuality(notes = listOf("Critical data unavailable")) else qualityGate.assess(dir, probs, rv5, plan.chosen, shadow.current)
        val risk = if (criticalFail) RiskAssessment(notes = listOf("Critical data unavailable — nothing sized"))
        else riskEngine.assess(plan.chosen, wall, em.horizonMinutes, shadow.current, oa.atmIv, shock, fe.expectation.eventRiskAhead, Session.isOpen(wall))
        // 29 — model health (freshness, completeness, regime stability, calibration quality, news, options)
        val mh = healthEngine.assess(ModelHealthEngine.Inputs(dq, health.coverage, rv5, probs, cal.info, em.horizonMinutes,
            nw.events, s.optionChain, optSpot, oa.atmIv))
        val shockAge = nw.shock?.let { (now - it.lastInfoAt) / 60_000.0 }
        // Event-risk regime: a fresh major event, or a scheduled high-severity event still ahead (outcome unknown).
        val majorEvent = nw.events.any { e ->
            val sev = e.analysis?.severity ?: 0.0
            (sev >= 0.7 && now - e.lastInfoAt <= 120 * 60_000L && e.newsConfidence >= 0.3) ||
                (sev >= 0.7 && e.stage == EventStage.EXPECTED && now - e.lastInfoAt <= 86_400_000L)
        }
        val td = decision.decide(dir, rg, em, oa, Session.isOpen(wall), shockAge, dq, majorEvent,
            TradeDecisionEngine.V5(rv5, scen, plan, tq, risk, mh, cal.regimeBump(rv5.primary.name)))

        // 28 — execution in shadow mode (virtual fills, stops, targets, emergency exits)
        val side = plan.chosen?.type?.directional ?: 0
        val sideProb = when {
            side > 0 -> probs.pBull; side < 0 -> probs.pBear
            plan.chosen?.type == StrategyType.IRON_CONDOR -> plan.chosen.probProfit
            else -> probs.top
        }
        // benchmark for the shadow book: naive momentum ATM option, sized by the same risk budget and exit rules
        val benchmark = StrategySelector.atmLong(s.optionChain, optSpot, if (st.pressure >= 0) 1 else -1, config.costs)?.let { b ->
            val r = riskEngine.assess(b, wall, em.horizonMinutes, shadow.benchmarkBook, oa.atmIv, InformationShock(), 0.0, true)
            b to r
        }
        // the frozen decision snapshot: decision time, analysed time, every input's timestamp, id, calibration used
        val snap = decisionSnapshot(s, wall, now, cal, dq)
        val shadowSummary = shadow.update(ShadowTrader.Context(wall, optSpot, s.optionChain, td.decision, plan, risk, rv5, tq, sideProb,
            fe.expectation, shock, criticalFail, Session.isOpen(wall), benchmark, snap.snapshotId))

        val signals = linkedMapOf(
            stSig.name to stSig, hwSig.name to hwSig, secSig.name to secSig,
            br.name to br, fuSig.name to fuSig, opSig.name to opSig, vxSig.name to vxSig,
            glSig.name to glSig, mc.signal.name to mc.signal, fl.name to fl, nwSig.name to nwSig, giftSig.name to giftSig,
            expSig.name to expSig,
        )
        val feed = LinkedHashMap(s.feedStatus)
        feed["Coverage"] = "%.0f%%".format(health.coverage * 100) + if (health.missing.isEmpty()) "" else " (missing: ${health.missing.joinToString()})"

        // 16 — the complete decision object
        val dirName = when { probs.pBull >= probs.pBear && probs.pBull >= probs.pRange -> "UP"; probs.pBear >= probs.pRange -> "DOWN"; else -> "RANGE" }
        val rawP = when (dirName) { "UP" -> rawDir.pBull; "DOWN" -> rawDir.pBear; else -> rawDir.pRange }
        val calP = if (probs.calibrated || probs.partial) when (dirName) { "UP" -> probs.pBull; "DOWN" -> probs.pBear; else -> probs.pRange } else null
        val ds = DecisionState(
            timestamp = wall, snapshotId = snap.snapshotId, criticalData = snap.criticalData, spot = s.nifty.last, regime = rv5.primary.name, regimeQuality = rv5.quality,
            direction = dirName, rawProbability = rawP, calibratedProbability = calP, calibration = probs.level,
            confidence = dir.confidenceValue, scenarios = scen.scenarios.associate { it.key to it.probability },
            futureExpectation = fe.expectation.expected, expectationChange = fe.expectation.change, expectationState = fe.expectation.state.name,
            informationShock = shock.score, shockDirection = shock.direction,
            tradeQuality = tq.score, qualityTier = tq.tier.name,
            instrument = plan.chosen?.instrument ?: "–", strategy = plan.type.name, lots = risk.lots, riskAtStop = risk.riskAtStop,
            stop = risk.exit?.stopText ?: "", target = risk.exit?.targetText ?: "",
            modelHealth = mh.score, healthTier = mh.tier.name,
            action = td.decision.name,
            reasons = (if (td.reasons.isEmpty()) plan.rationale else td.reasons).take(6),
        )
        return EngineOutput(
            timestamp = wall, spot = s.nifty.last, spotChangePct = s.nifty.changePct,
            signals = signals, regime = rg, direction = dir, expectedMove = em, options = oa, decision = td,
            heavyweights = hw.report, sectors = sec.rows, events = nw.events,
            dataSource = s.source, feedStatus = feed, dataQuality = dq.copy(warnings = dq.warnings + gift.warnings), engineVersion = ENGINE_VERSION,
            newsHorizons = nw.horizons, pendingEventAnalysis = nw.pending, gift = gift.report,
            normalized = rel.state, regimeV5 = rv5, expectation = fe.expectation, shock = shock, scenarios = scen,
            quality = tq, strategy = plan, risk = risk, shadow = shadowSummary, decisionState = ds, health = mh,
            pointInTime = pit.report, snapshot = snap,
        )
    }

    /** The snapshot as the pipeline sees it after point-in-time validation (nothing stamped after its own time). */
    fun pointInTime(s: MarketSnapshot): MarketSnapshot = validator.validate(s).snapshot

    private fun decisionSnapshot(s: MarketSnapshot, wall: Long, now: Long, cal: CalibrationModel,
                                 dq: com.niftyengine.engine.model.DataQualityReport): DecisionSnapshot {
        val times = linkedMapOf(
            "NIFTY" to s.nifty.asOf, "NIFTY bars" to (s.nifty.intraday.lastOrNull()?.t ?: 0L),
            "Futures" to (s.futures?.asOf ?: 0L), "Options" to (s.optionChain?.asOf ?: 0L),
            "India VIX" to (s.vix?.asOf ?: 0L), "Bank Nifty" to (s.bankNifty?.asOf ?: 0L),
            "Constituents" to (s.constituents.values.map { it.asOf }.filter { it > 0 }.sorted().let { if (it.isEmpty()) 0L else it[it.size / 2] }),
            "Global" to (s.global.values.maxOfOrNull { it.asOf } ?: 0L), "GIFT Nifty" to (s.giftNifty?.asOf ?: 0L),
            "FPI/DII" to (s.flows?.asOf ?: 0L), "News" to (s.news.maxOfOrNull { it.publishedAt } ?: 0L),
        )
        val crit = listOf(times["NIFTY"], times["Futures"], times["Options"]).mapNotNull { it?.takeIf { t -> t > 0 } }
        val skew = if (crit.size >= 2) (crit.max() - crit.min()) / 1000.0 else Double.NaN
        val id = "%08X".format((wall.toString() + times.entries.joinToString { "${it.key}=${it.value}" } + "%.2f".format(s.nifty.last)).hashCode())
        return DecisionSnapshot(wall, now, id, times, skew, cal.info.fittedAt,
            if (dq.circuitBreaker.isEmpty()) "OK" else dq.circuitBreaker.joinToString("; "))
    }

    private fun recordTicks(s: MarketSnapshot, now: Long) {
        fun rec(d: com.niftyengine.engine.model.InstrumentData?) { if (d != null) state.record(d.symbol, now, d.last, d.volume) }
        rec(s.nifty); rec(s.bankNifty); rec(s.vix)
        s.constituents.values.forEach(::rec)
        s.sectors.values.forEach(::rec)
        s.global.values.forEach(::rec)
    }

    private fun blend(name: String, a: EngineSignal, b: EngineSignal, wa: Double): EngineSignal {
        val wA = wa * a.confidence; val wB = (1 - wa) * b.confidence
        val den = wA + wB
        if (den <= 0) return EngineSignal.unavailable(name, "no data")
        return EngineSignal(name, (wA * a.score + wB * b.score) / den, maxOf(a.confidence, b.confidence) * (0.7 + 0.3 * minOf(a.confidence, b.confidence)))
    }

    companion object {
        /** Regimes that the direction engine's own high driver conflict turns into CONFLICT. */
        private val CONFLICT_PRONE = setOf(PrimaryRegime.TREND_UP, PrimaryRegime.TREND_DOWN, PrimaryRegime.RANGE, PrimaryRegime.REVERSAL_RISK)
    }
}

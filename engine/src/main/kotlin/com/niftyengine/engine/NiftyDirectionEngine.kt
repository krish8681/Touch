package com.niftyengine.engine

import com.niftyengine.engine.core.EngineState
import com.niftyengine.engine.core.Session
import com.niftyengine.engine.engines.BreadthEngine
import com.niftyengine.engine.engines.CalibrationModel
import com.niftyengine.engine.engines.DataQualityEngine
import com.niftyengine.engine.engines.ProbabilityCalibrator
import kotlin.math.abs
import com.niftyengine.engine.engines.DataCollector
import com.niftyengine.engine.engines.DataNormalizer
import com.niftyengine.engine.engines.DirectionProbabilityEngine
import com.niftyengine.engine.engines.DirectionProbabilityEngine.DriverInput
import com.niftyengine.engine.engines.ExpectedMoveEngine
import com.niftyengine.engine.engines.FlowEngine
import com.niftyengine.engine.engines.FuturesPositionEngine
import com.niftyengine.engine.engines.GlobalRiskEngine
import com.niftyengine.engine.engines.IndiaMacroEngine
import com.niftyengine.engine.engines.MarketRegimeEngine
import com.niftyengine.engine.engines.MarketStructureEngine
import com.niftyengine.engine.engines.EventIntelligenceEngine
import com.niftyengine.engine.engines.GiftNiftyEngine
import com.niftyengine.engine.model.NewsHorizon
import com.niftyengine.engine.engines.NiftyWeightEngine
import com.niftyengine.engine.engines.OptionSelectionEngine
import com.niftyengine.engine.engines.OptionsPositionEngine
import com.niftyengine.engine.engines.SectorEngine
import com.niftyengine.engine.engines.TradeDecisionEngine
import com.niftyengine.engine.engines.VIXEngine
import com.niftyengine.engine.model.Driver
import com.niftyengine.engine.model.EngineOutput
import com.niftyengine.engine.model.EngineSignal
import com.niftyengine.engine.model.MarketSnapshot
import kotlinx.serialization.Serializable

@Serializable
data class EngineConfig(
    val horizonMinutes: Int = 60,
    val minProbability: Double = 0.65,
    val minConfidence: com.niftyengine.engine.model.ConfidenceLevel = com.niftyengine.engine.model.ConfidenceLevel.HIGH,
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
)

const val ENGINE_VERSION = "4.2.0"

/**
 * NIFTY Direction Engine v3 — orchestrates modules 02–16 for one snapshot.
 *
 *   data → features → (structure, heavyweights, sectors, breadth, futures, options, VIX, global, macro, flows, news)
 *        → regime → direction probabilities → expected move → option ranking → trade filter
 *
 * The engine is stateful (tick history, driver persistence, regime history), so feed snapshots in
 * chronological order. Use one instance per live session or per replay run.
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
    private val quality = DataQualityEngine(if (config.marketOnlyBacktest) setOf("NIFTY") else setOf("NIFTY", "Futures", "Options"))
    private var prevSpot = Double.NaN
    private var prevSpotT = 0L

    /** Fitted calibration (from the prediction log). Replace whenever a new fit is available. */
    var calibration: CalibrationModel = CalibrationModel()
        set(value) { field = value; optionSelect.optionCalibrator = value.optionFn() }

    fun process(raw: MarketSnapshot): EngineOutput {
        val wall = raw.timestamp
        // Outside market hours analyse the most recent session (last bar), not an empty "today".
        val lastBar = raw.nifty.intraday.lastOrNull()?.t
        val now = if (!Session.isOpen(wall) && lastBar != null && lastBar < wall && wall - lastBar < 5 * 86_400_000L) lastBar else wall
        state.rollDay(now)
        val sessionStart = Session.sessionStart(now)
        val sessionDay = Session.zdt(now).toLocalDate()
        // Daily history must end before the analysed session (previous-day levels, percentiles).
        val s = raw.copy(
            // By DATE: some feeds stamp daily candles at 00:00, which is earlier than 09:15 of the same day.
            nifty = raw.nifty.copy(daily = raw.nifty.daily.filter { Session.zdt(it.t).toLocalDate() < sessionDay }),
            vix = raw.vix?.let { v -> v.copy(daily = v.daily.filter { Session.zdt(it.t).toLocalDate() < sessionDay }) },
        )
        recordTicks(s, now)
        val health = DataCollector.health(s)
        // 01b — freshness/validity of every input; ages are measured against wall-clock time.
        val dq = quality.assess(s, wall, prevSpot, prevSpotT)
        if (s.nifty.last > 0) { prevSpot = s.nifty.last; prevSpotT = wall }

        val st = structure.analyze(s, now, sessionStart)
        val hw = weights.analyze(s)
        val sec = sectors.analyze(s, hw.contributionBySector, now, sessionStart)
        val br = quality.gate(breadth.analyze(s, hw.report, now, sessionStart), dq)
        val fu = futures.analyze(s, now)
        val op = options.analyze(s, now)
        val vx = vix.analyze(s, now, sessionStart)
        val gl = global.analyze(s, now, sessionStart)
        val mc = macro.analyze(s, now, sessionStart)
        val fl = quality.gate(flows.analyze(s), dq)
        // 21 — GIFT Nifty: opening factor (pre-open implied gap → gap behaviour in the first hour).
        val gift = giftEngine.analyze(s, wall)
        val giftSig = quality.gate(gift.signal, dq)
        // Before the open NIFTY isn't trading: GIFT's implied open is the market's reaction to overnight news.
        val liveBaseline = EventIntelligenceEngine.baselineFrom(s, wall, usePrevClose = false).let { b ->
            if (gift.impliedOpenForPricing.isNaN()) b else b.copy(nifty = gift.impliedOpenForPricing, futures = s.giftNifty?.last ?: b.futures)
        }
        // News is point-in-time against the wall clock (overnight/pre-open news must be visible before 09:15).
        val nw = eventIntel.process(
            s.news, s.eventAnalyses, wall,
            market = liveBaseline,
            prevCloseBaseline = EventIntelligenceEngine.baselineFrom(s, sessionStart, usePrevClose = true),
            niftySeries = st.series, decisionHorizon = NewsHorizon.forMinutes(config.horizonMinutes),
        )
        // Stale/invalid inputs stop acting as live drivers; degraded ones are down-weighted.
        val stSig = quality.gate(st.signal, dq); val hwSig = quality.gate(hw.signal, dq); val secSig = quality.gate(sec.signal, dq)
        val fuSig = quality.gate(fu.signal, dq); val opSig = quality.gate(op.signal, dq); val vxSig = quality.gate(vx.signal, dq)
        val glSig = quality.gate(gl.signal, dq); val nwSig = quality.gate(nw.signal, dq)

        val derivatives = blend("Derivatives", fuSig, opSig, 0.5)
        val sectorDriver = blend("Sector+heavyweight", hwSig, secSig, 0.5)
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
        )
        val prelim = direction.preliminary(inputs)
        val rg = regime.classify(MarketRegimeEngine.Inputs(prelim, st, fu, op, vx, br.score, derivatives.score,
            hw.report.fakeBreadth,
            newsShock = nw.shock?.let { "Fresh unpriced ${it.type.label} event (${it.stage.label}): ${it.title.take(50)}" },
            newsContradicted = nw.contradicted.isNotEmpty()), now)
        val insideOr = !st.orHigh.isNaN() && s.nifty.last <= st.orHigh && s.nifty.last >= st.orLow
        val hwr = hw.report
        val prevClose = s.nifty.prevClose.takeIf { it > 0 } ?: s.nifty.last
        val concentration = if (abs(hwr.totalContributionPts) > prevClose * 0.003 && (hwr.fakeBreadth || abs(hwr.heavyweightDependence) > 0.8))
            0.08 to "Heavyweight concentration: top 5 carry %.0f%% of a %+.0f pt move".format(hwr.heavyweightDependence * 100, hwr.totalContributionPts)
        else null
        val rawDir = direction.compute(inputs, rg.regime, rg.regimeClass,
            DirectionProbabilityEngine.RangeInputs(st.adx, op.rangeEvidence, vx.state, insideOr),
            qualityScore = dq.score, concentration = concentration)
        // 19 — calibrated probabilities per horizon (raw scores flagged uncalibrated until enough outcomes).
        val dir = rawDir.copy(
            horizons = calibration.allHorizons(rawDir.pBull, rawDir.pBear, rawDir.pRange, ProbabilityCalibrator.HORIZONS),
            calibration = calibration.info,
        )
        state.pushDirection(now, dir.directionalScore)

        val horizon = if (Session.isOpen(wall)) config.horizonMinutes.coerceAtMost(Session.minutesToClose(now).toInt().coerceAtLeast(15))
        else config.horizonMinutes
        val em = move.compute(ExpectedMoveEngine.Inputs(
            spot = s.nifty.last, horizonMinutes = horizon, vix = vx.level, atmIv = op.atmIv,
            realizedVol = st.realizedVolAnnual, histVol = st.histVolAnnual,
            eventShock = rg.regime == com.niftyengine.engine.model.Regime.EVENT_SHOCK, vixState = vx.state,
            freshMajorEvent = nw.events.any { (it.analysis?.severity ?: 0.0) >= 0.6 && now - it.lastInfoAt <= 90 * 60_000L && it.surpriseMagnitude * it.unpriced >= 0.2 },
            globalStress = gl.globalVolStress, momentum = st.pressure,
        ), dir)
        val oa = optionSelect.analyze(s.optionChain, s.optionChain?.underlying?.takeIf { it > 0 } ?: s.nifty.last, wall, dir, em, op.atmIv,
            dir.decisionProbs(em.horizonMinutes))
        val shockAge = nw.shock?.let { (now - it.lastInfoAt) / 60_000.0 }
        // Event-risk regime: a fresh major event, or a scheduled high-severity event still ahead (outcome unknown).
        val majorEvent = nw.events.any { e ->
            val sev = e.analysis?.severity ?: 0.0
            (sev >= 0.7 && now - e.lastInfoAt <= 120 * 60_000L && e.newsConfidence >= 0.3) ||
                (sev >= 0.7 && e.stage == com.niftyengine.engine.model.EventStage.EXPECTED && now - e.lastInfoAt <= 86_400_000L)
        }
        val td = decision.decide(dir, rg, em, oa, Session.isOpen(wall), shockAge, dq, majorEvent)

        val signals = linkedMapOf(
            stSig.name to stSig, hwSig.name to hwSig, secSig.name to secSig,
            br.name to br, fuSig.name to fuSig, opSig.name to opSig, vxSig.name to vxSig,
            glSig.name to glSig, mc.signal.name to mc.signal, fl.name to fl, nwSig.name to nwSig, giftSig.name to giftSig,
        )
        val feed = LinkedHashMap(s.feedStatus)
        feed["Coverage"] = "%.0f%%".format(health.coverage * 100) + if (health.missing.isEmpty()) "" else " (missing: ${health.missing.joinToString()})"
        return EngineOutput(
            timestamp = wall, spot = s.nifty.last, spotChangePct = s.nifty.changePct,
            signals = signals, regime = rg, direction = dir, expectedMove = em, options = oa, decision = td,
            heavyweights = hw.report, sectors = sec.rows, events = nw.events,
            dataSource = s.source, feedStatus = feed, dataQuality = dq.copy(warnings = dq.warnings + gift.warnings), engineVersion = ENGINE_VERSION,
            newsHorizons = nw.horizons, pendingEventAnalysis = nw.pending, gift = gift.report,
        )
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
}

package com.niftyengine.engine

import com.niftyengine.engine.core.EngineState
import com.niftyengine.engine.core.Session
import com.niftyengine.engine.engines.BreadthEngine
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
import com.niftyengine.engine.engines.NewsEventEngine
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
)

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
    private val news = NewsEventEngine()
    private val regime = MarketRegimeEngine(state)
    private val direction = DirectionProbabilityEngine(state)
    private val move = ExpectedMoveEngine()
    private val optionSelect = OptionSelectionEngine(
        OptionSelectionEngine.Filters(minOi = config.minOi, minVolume = config.minVolume, maxSpreadPct = config.maxSpreadPct))
    private val decision = TradeDecisionEngine(TradeDecisionEngine.Params(
        config.minProbability, config.minConfidence, config.minExpectedMovePts, config.requireMarketOpen))

    fun process(raw: MarketSnapshot): EngineOutput {
        val wall = raw.timestamp
        // Outside market hours analyse the most recent session (last bar), not an empty "today".
        val lastBar = raw.nifty.intraday.lastOrNull()?.t
        val now = if (!Session.isOpen(wall) && lastBar != null && lastBar < wall && wall - lastBar < 5 * 86_400_000L) lastBar else wall
        state.rollDay(now)
        val sessionStart = Session.sessionStart(now)
        // Daily history must end before the analysed session (previous-day levels, percentiles).
        val s = raw.copy(
            nifty = raw.nifty.copy(daily = raw.nifty.daily.filter { it.t < sessionStart }),
            vix = raw.vix?.let { v -> v.copy(daily = v.daily.filter { it.t < sessionStart }) },
        )
        recordTicks(s, now)
        val health = DataCollector.health(s)

        val st = structure.analyze(s, now, sessionStart)
        val hw = weights.analyze(s)
        val sec = sectors.analyze(s, hw.contributionBySector, now, sessionStart)
        val br = breadth.analyze(s, hw.report, now, sessionStart)
        val fu = futures.analyze(s, now)
        val op = options.analyze(s, now)
        val vx = vix.analyze(s, now, sessionStart)
        val gl = global.analyze(s, now, sessionStart)
        val mc = macro.analyze(s, now, sessionStart)
        val fl = flows.analyze(s)
        val nw = news.analyze(s.news, now, st.series, s.nifty.prevClose, s.nifty.last)

        val derivatives = blend("Derivatives", fu.signal, op.signal, 0.5)
        val sectorDriver = blend("Sector+heavyweight", hw.signal, sec.signal, 0.5)
        val inputs = listOf(
            DriverInput(Driver.PRICE, st.signal.score, st.signal.confidence),
            DriverInput(Driver.DERIVATIVES, derivatives.score, derivatives.confidence),
            DriverInput(Driver.SECTOR, sectorDriver.score, sectorDriver.confidence),
            DriverInput(Driver.GLOBAL, gl.signal.score, gl.signal.confidence),
            DriverInput(Driver.BREADTH, br.score, br.confidence),
            DriverInput(Driver.VIX, vx.signal.score, vx.signal.confidence),
            DriverInput(Driver.MACRO, mc.signal.score, mc.signal.confidence),
            DriverInput(Driver.FLOWS, fl.score, fl.confidence),
            DriverInput(Driver.NEWS, nw.signal.score, nw.signal.confidence),
        )
        val prelim = direction.preliminary(inputs)
        val rg = regime.classify(MarketRegimeEngine.Inputs(prelim, st, fu, op, vx, br.score, derivatives.score,
            hw.report.fakeBreadth, nw), now)
        val insideOr = !st.orHigh.isNaN() && s.nifty.last <= st.orHigh && s.nifty.last >= st.orLow
        val dir = direction.compute(inputs, rg.regime, rg.regimeClass,
            DirectionProbabilityEngine.RangeInputs(st.adx, op.rangeEvidence, vx.state, insideOr))
        state.pushDirection(now, dir.directionalScore)

        val horizon = if (Session.isOpen(wall)) config.horizonMinutes.coerceAtMost(Session.minutesToClose(now).toInt().coerceAtLeast(15))
        else config.horizonMinutes
        val em = move.compute(ExpectedMoveEngine.Inputs(
            spot = s.nifty.last, horizonMinutes = horizon, vix = vx.level, atmIv = op.atmIv,
            realizedVol = st.realizedVolAnnual, histVol = st.histVolAnnual,
            eventShock = rg.regime == com.niftyengine.engine.model.Regime.EVENT_SHOCK, vixState = vx.state,
            freshMajorEvent = nw.events.any { it.magnitude >= 0.6 && it.decay > 0.5 },
            globalStress = gl.globalVolStress, momentum = st.pressure,
        ), dir)
        val oa = optionSelect.analyze(s.optionChain, s.optionChain?.underlying?.takeIf { it > 0 } ?: s.nifty.last, wall, dir, em, op.atmIv)
        val shockAge = nw.shock?.let { (now - it.firstSeen) / 60_000.0 }
        val td = decision.decide(dir, rg, em, oa, Session.isOpen(wall), shockAge)

        val signals = linkedMapOf(
            st.signal.name to st.signal, hw.signal.name to hw.signal, sec.signal.name to sec.signal,
            br.name to br, fu.signal.name to fu.signal, op.signal.name to op.signal, vx.signal.name to vx.signal,
            gl.signal.name to gl.signal, mc.signal.name to mc.signal, fl.name to fl, nw.signal.name to nw.signal,
        )
        val feed = LinkedHashMap(s.feedStatus)
        feed["Coverage"] = "%.0f%%".format(health.coverage * 100) + if (health.missing.isEmpty()) "" else " (missing: ${health.missing.joinToString()})"
        return EngineOutput(
            timestamp = wall, spot = s.nifty.last, spotChangePct = s.nifty.changePct,
            signals = signals, regime = rg, direction = dir, expectedMove = em, options = oa, decision = td,
            heavyweights = hw.report, sectors = sec.rows, events = nw.events,
            dataSource = s.source, feedStatus = feed,
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

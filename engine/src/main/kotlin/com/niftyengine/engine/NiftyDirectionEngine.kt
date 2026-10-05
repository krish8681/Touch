package com.niftyengine.engine

import com.niftyengine.engine.core.Composite
import com.niftyengine.engine.core.Composite.Part
import com.niftyengine.engine.core.EngineState
import com.niftyengine.engine.core.ExpiryCalendar
import com.niftyengine.engine.core.Fresh
import com.niftyengine.engine.core.M
import com.niftyengine.engine.core.PriceDistribution
import com.niftyengine.engine.core.Session
import com.niftyengine.engine.core.TransactionCosts
import com.niftyengine.engine.engines.BreadthEngine
import com.niftyengine.engine.engines.CalibrationModel
import com.niftyengine.engine.engines.ConfidenceEngine
import com.niftyengine.engine.engines.DataCollector
import com.niftyengine.engine.engines.DataNormalizer
import com.niftyengine.engine.engines.DataQualityEngine
import com.niftyengine.engine.engines.EarningsValuationEngine
import com.niftyengine.engine.engines.EventIntelligenceEngine
import com.niftyengine.engine.engines.EventRiskEngine
import com.niftyengine.engine.engines.ExpectationEngine
import com.niftyengine.engine.engines.ExpiryOptionsEngine
import com.niftyengine.engine.engines.FiiEngine
import com.niftyengine.engine.engines.FuturesPositionEngine
import com.niftyengine.engine.engines.GiftNiftyEngine
import com.niftyengine.engine.engines.GlobalRiskEngine
import com.niftyengine.engine.engines.HorizonEngine
import com.niftyengine.engine.engines.MacroEngine
import com.niftyengine.engine.engines.NiftyWeightEngine
import com.niftyengine.engine.engines.OptionsValuationEngine
import com.niftyengine.engine.engines.PriceStructureEngine
import com.niftyengine.engine.engines.ProbabilityEngine
import com.niftyengine.engine.engines.RegimeEngine
import com.niftyengine.engine.engines.RiskEngine
import com.niftyengine.engine.engines.SectorEngine
import com.niftyengine.engine.engines.StrategyEngine
import com.niftyengine.engine.engines.VIXEngine
import com.niftyengine.engine.engines.VixState
import com.niftyengine.engine.model.ConfidenceLevel
import com.niftyengine.engine.model.EngineOutput
import com.niftyengine.engine.model.EngineSignal
import com.niftyengine.engine.model.ExpectationChannel
import com.niftyengine.engine.model.ExpiryKind
import com.niftyengine.engine.model.Factor
import com.niftyengine.engine.model.FactorReading
import com.niftyengine.engine.model.GlobalAsset
import com.niftyengine.engine.model.HorizonGroup
import com.niftyengine.engine.model.HorizonId
import com.niftyengine.engine.model.MarketRegime
import com.niftyengine.engine.model.MarketSnapshot
import com.niftyengine.engine.model.NewsHorizon
import com.niftyengine.engine.model.Sector
import com.niftyengine.engine.model.StrategyCandidate
import com.niftyengine.engine.model.StrategyReport
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.sqrt

@Serializable
data class EngineConfig(
    /** H1 horizon at which intraday option trades are exited / evaluated. */
    val intradayHorizon: HorizonId = HorizonId.M60,
    /** Minimum probability of the structure's direction (3-class: bullish / neutral / bearish), raised under HIGH event risk. */
    val minProbability: Double = 0.55,
    val minConfidence: ConfidenceLevel = ConfidenceLevel.MEDIUM,
    val minAlignment: Int = 2,
    val requireMarketOpen: Boolean = true,
    val maxSpreadPct: Double = 3.0,
    val minOi: Double = 2_000.0,
    val minVolume: Double = 500.0,
    val costs: TransactionCosts = TransactionCosts(),
    val minOptionProfitProb: Double = 0.45,
    val minReturnOnRisk: Double = 0.05,
    val minRiskReward: Double = 0.25,
    val eventThresholdBump: Double = 0.05,
    val minDataQuality: Double = 0.70,
    val confirmCycles: Int = 2,
    val requireCalibration: Boolean = true,
    /** Risk budget: max loss per trade = capital × riskPerTradePct %. */
    val capital: Double = 500_000.0,
    val riskPerTradePct: Double = 2.0,
    val maxLots: Int = 10,
    /** NIFTY P/E band considered fair (valuation factor) and the India 10-year yield (NaN = not used). */
    val fairPeLow: Double = 20.0,
    val fairPeHigh: Double = 24.0,
    val india10y: Double = Double.NaN,
    /** Historical market-only backtest: no option chain/news/global exist for past days, so only NIFTY is critical. */
    val marketOnlyBacktest: Boolean = false,
)

const val ENGINE_VERSION = "5.0.0"

/**
 * NIFTY Three-Horizon Engine (v5) — one cycle:
 *
 *   live data → data validation (freshness, circuit breaker) → expectation engine → regime engine
 *     → factor readings (direction · strength · freshness · reliability) per horizon
 *     → H1 (30 min / 1 h / 3 h / close) · H2 (next weekly expiry) · H3 (next monthly expiry)
 *     → expiry intelligence (OI / IV / PCR / VIX, support / resistance, pin / breakout, expected move)
 *     → probability engine (distribution → direction · range · buckets) → confidence engine (alignment, event risk)
 *     → options valuation → strategy engine → risk engine → final signal
 *
 * Prediction and trade are separate layers (§26). The engine is stateful (tick history, opening chains, regime history,
 * signal persistence), so feed snapshots in chronological order; use one instance per live session or replay run.
 */
class NiftyDirectionEngine(val config: EngineConfig = EngineConfig()) {
    val state = EngineState()
    private val norm = DataNormalizer(state)
    private val structure = PriceStructureEngine(norm)
    private val weights = NiftyWeightEngine(norm)
    private val sectors = SectorEngine(norm)
    private val breadth = BreadthEngine(norm)
    private val futures = FuturesPositionEngine(state)
    private val expiryOptions = ExpiryOptionsEngine(state)
    private val vix = VIXEngine(norm)
    private val global = GlobalRiskEngine(norm)
    private val macro = MacroEngine(norm)
    /** 20 — event intelligence (lifecycle, expectations, pricing-in, reaction, horizons). State persisted by the app. */
    val eventIntel = EventIntelligenceEngine()
    private val giftEngine = GiftNiftyEngine()
    private val regimeEngine = RegimeEngine(state)
    private val strategyEngine = StrategyEngine(
        StrategyEngine.Filters(minOi = config.minOi, minVolume = config.minVolume, maxSpreadPct = config.maxSpreadPct), config.costs)
    private val risk = RiskEngine(RiskEngine.Params(
        minProbability = config.minProbability, minConfidence = config.minConfidence, minAlignment = config.minAlignment,
        minProfitProb = config.minOptionProfitProb, minReturnOnRisk = config.minReturnOnRisk, minRiskReward = config.minRiskReward,
        eventThresholdBump = config.eventThresholdBump, minDataQuality = config.minDataQuality, confirmCycles = config.confirmCycles,
        requireCalibration = config.requireCalibration, requireMarketOpen = config.requireMarketOpen, capital = config.capital,
        riskPerTradePct = config.riskPerTradePct, maxLots = config.maxLots, lotSize = config.costs.lotSize))
    private val quality = DataQualityEngine(if (config.marketOnlyBacktest) setOf("NIFTY") else setOf("NIFTY", "Futures", "Options"))
    private var prevSpot = Double.NaN
    private var prevSpotT = 0L

    /** Fitted calibration (from the prediction log). Replace whenever a new fit is available. */
    var calibration: CalibrationModel = CalibrationModel()
        set(value) { field = value; strategyEngine.calibrator = value.strategyFn() }

    private fun sourceReliability(src: String): Double = when {
        src.contains("Kite", true) -> 0.95
        src.startsWith("SIMULATED") || src.startsWith("backtest") || src.startsWith("replay") -> 0.9
        src.contains("NSE", true) -> 0.85
        else -> 0.8
    }

    fun process(raw: MarketSnapshot): EngineOutput {
        val wall = raw.timestamp
        // Outside market hours analyse the most recent session (last bar), not an empty "today".
        val lastBar = raw.nifty.intraday.lastOrNull()?.t
        val now = if (!Session.isOpen(wall) && lastBar != null && lastBar < wall && wall - lastBar < 5 * 86_400_000L) lastBar else wall
        state.rollDay(now)
        val sessionStart = Session.sessionStart(now)
        val sessionDay = Session.zdt(now).toLocalDate()
        // Daily history must end before the analysed session (by DATE: some feeds stamp daily candles at 00:00).
        fun cut(d: com.niftyengine.engine.model.InstrumentData) = d.copy(daily = d.daily.filter { Session.zdt(it.t).toLocalDate() < sessionDay })
        val s = raw.copy(nifty = cut(raw.nifty), vix = raw.vix?.let(::cut), sectors = raw.sectors.mapValues { cut(it.value) })
        recordTicks(s, now)
        val health = DataCollector.health(s)
        // ---- data validation: freshness / validity of every input, circuit breaker
        val dq = quality.assess(s, wall, prevSpot, prevSpotT)
        if (s.nifty.last > 0) { prevSpot = s.nifty.last; prevSpotT = wall }
        val open = Session.isOpen(wall)
        val ref = quality.refTime(s, wall)
        val rel = sourceReliability(s.source)
        // Reference price for distributions/strategies. If NIFTY's own quote is invalid the circuit breaker already forces
        // DATA ERROR; a fallback reference keeps every downstream engine well-defined instead of crashing.
        fun ok(x: Double?) = x != null && !x.isNaN() && x > 0
        val spot = listOf(s.nifty.last, s.optionChain?.underlying, s.futures?.last, s.nifty.prevClose, s.nifty.daily.lastOrNull()?.c)
            .firstOrNull { ok(it) } ?: 25_000.0

        // ---- analysis engines
        val st = structure.analyze(s, now, sessionStart, ref, rel)
        val hw = weights.analyze(s, now, sessionStart, ref, rel)
        val sec = sectors.analyze(s, hw.contributionBySector, now, sessionStart)
        val br = quality.gate(breadth.analyze(s, hw.report, now, sessionStart), dq)
        val fu = futures.analyze(s, now)
        val vx = vix.analyze(s, now, sessionStart)
        val gl = global.analyze(s, now, sessionStart)
        val gift = giftEngine.analyze(s, wall)
        val giftSig = quality.gate(gift.signal, dq)
        // News is point-in-time against the wall clock (overnight/pre-open news must be visible before 09:15).
        val liveBaseline = EventIntelligenceEngine.baselineFrom(s, wall, usePrevClose = false).let { b ->
            if (gift.impliedOpenForPricing.isNaN()) b else b.copy(nifty = gift.impliedOpenForPricing, futures = s.giftNifty?.last ?: b.futures)
        }
        val nw = eventIntel.process(
            s.news, s.eventAnalyses, wall, market = liveBaseline,
            prevCloseBaseline = EventIntelligenceEngine.baselineFrom(s, sessionStart, usePrevClose = true),
            niftySeries = st.series, decisionHorizon = NewsHorizon.forMinutes(config.intradayHorizon.minutes.coerceAtLeast(30)),
        )
        // §12 expectation engine (common to all horizons)
        val exp = ExpectationEngine.analyze(nw.events, s.macro, s.earnings, wall)
        val mc = macro.analyze(s, now, sessionStart, ref, exp, rel)
        val fii = FiiEngine.analyze(s, ref, exp, 0.9)

        // §9/§11/§17 expiry intelligence (weekly + monthly chains)
        val ctx = ExpiryOptionsEngine.Context(spot, wall, st.dayLow, st.dayHigh, DataNormalizer.nz(fu.dayScore))
        val weeklyRes = expiryOptions.analyze(s.optionChain, ExpiryKind.WEEKLY, ctx)
        val monthlyChain = s.monthlyChain ?: s.optionChain?.takeIf { ExpiryCalendar.isMonthlyExpiry(Session.zdt(it.expiryMillis).toLocalDate()) }
        val monthlyRes = expiryOptions.analyze(monthlyChain, ExpiryKind.MONTHLY, ctx)
        val weeklyExp = weeklyRes.intel?.expiryMillis ?: Session.closeOf(ExpiryCalendar.nextWeekly(wall))
        val monthlyExp = monthlyRes.intel?.expiryMillis ?: Session.closeOf(ExpiryCalendar.nextMonthly(wall)).coerceAtLeast(weeklyExp)

        // §23 event risk (30-day look-ahead)
        val eventRisk = EventRiskEngine.analyze(wall, s.calendar, ExpectationEngine.pendingMajor(nw.events, wall),
            Session.zdt(weeklyExp).toLocalDate(), Session.zdt(monthlyExp).toLocalDate(), activeShock = nw.shock != null)

        // ---- factor readings per horizon
        val readings = HorizonId.values().associateWith { h -> readingsFor(h, s, ref, rel, st, hw, br, fu, vx, gl, giftSig, mc, fii, nw, exp, weeklyRes, sec) }

        // §13 market regime BEFORE the horizon scores (it adapts the weights, §28)
        val epsRev = s.earnings.let { if (!it.forwardEps.isNaN() && !it.forwardEpsPrev.isNaN() && it.forwardEpsPrev > 0) (it.forwardEps / it.forwardEpsPrev - 1) * 100 else Double.NaN }
        val domestic = sec.rows.filter { it.sector in setOf(Sector.BANK, Sector.FIN_SERVICES, Sector.AUTO, Sector.FMCG, Sector.INFRA) && !it.change5dPct.isNaN() }
            .map { it.change5dPct }.let { if (it.isEmpty()) Double.NaN else it.average() }
        val regime = regimeEngine.classify(RegimeEngine.Inputs(
            globalIntraday = gl.intraday, globalWeekly = gl.weekly, fii = fii.report,
            vixState = vx.state, vixLevel = vx.level, vixPercentile = vx.percentile,
            us10y5d = gl.change5d[GlobalAsset.US10Y] ?: Double.NaN, dxy5d = gl.change5d[GlobalAsset.DXY] ?: Double.NaN,
            crude1d = mc.crude1d, crude5d = mc.crude5d, usdinr5d = mc.usdinr5d, sp1d = gl.change1d[GlobalAsset.SP500] ?: Double.NaN,
            rbi = mc.get(Factor.RBI_RATES, HorizonId.WEEKLY), liquidityCr = mc.liquidityCr, creditGrowth = s.macro.creditGrowth,
            earnings = readings.getValue(HorizonId.MONTHLY).getValue(Factor.EARNINGS), epsRevisionPct = epsRev,
            epsSurprise = s.earnings.let { it.epsGrowthActual - it.epsGrowthExpected }, beatRatio = s.earnings.beatRatio,
            earningsNews = exp.channels[ExpectationChannel.EARNINGS]?.get(HorizonGroup.H3) ?: Double.NaN,
            sectorLeadership = readings.getValue(HorizonId.WEEKLY).getValue(Factor.SECTOR_LEADERSHIP), domesticSectors5d = domestic,
            indiaMacro = mc.get(Factor.INDIA_MACRO, HorizonId.WEEKLY),
            newsShock = nw.shock?.let { "Fresh unpriced ${it.type.label} event (${it.stage.label}): ${it.title.take(50)}" }, c15m = st.c15m,
            dayRangePct = st.dayRangePct, typicalRangePct = st.typicalRangePct, range5dPct = st.range5dPct, typicalRange5dPct = st.typicalRange5dPct,
            pinStrength = weeklyRes.intel?.pin?.strength ?: Double.NaN, oiConcentration = weeklyRes.intel?.oiConcentration ?: Double.NaN,
            eventRiskH2 = eventRisk.level(HorizonGroup.H2),
        ), now).let { it.copy(weightAdjustments = HorizonEngine.weightChanges(it.regime)) }

        // §3–§11, §27, §28, §31 horizon scores
        val monthlyOverlay = if (monthlyRes.intel != null) expiryOptions.reading(monthlyRes, HorizonId.MONTHLY, ref, rel, s.source) else null
        val scored = HorizonId.values().associateWith { h ->
            HorizonEngine.score(h, readings.getValue(h), regime.regime, if (h == HorizonId.MONTHLY) monthlyOverlay else null)
        }

        // §18–§20 expected move + probability distribution per horizon
        val freshMajor = nw.events.any { (it.analysis?.severity ?: 0.0) >= 0.6 && wall - it.lastInfoAt <= 90 * 60_000L && it.surpriseMagnitude * it.unpriced >= 0.2 }
        // Chain IVs are quoted on calendar time; the intraday blend scales by trading time, so convert first
        // (an expiry-day IV of 30 % "calendar" is ~13 % in trading-time terms).
        fun tradingIv(i: com.niftyengine.engine.model.ExpiryIntel?): Double {
            if (i == null || i.atmIv.isNaN()) return Double.NaN
            val tc = Session.yearsToExpiry(wall, i.expiryMillis)
            val tt = ExpiryCalendar.tradingMinutesBetween(wall, i.expiryMillis) / (Session.SESSION_MINUTES * Session.TRADING_DAYS)
            return if (tt <= 1e-6) Double.NaN else i.atmIv * sqrt(tc / tt)
        }
        val vol = ProbabilityEngine.volInputs(vx.level, tradingIv(weeklyRes.intel), monthlyRes.intel?.atmIv ?: Double.NaN,
            st.realizedVolAnnual, st.histVolAnnual, regime.regime == MarketRegime.EVENT_SHOCK, vx.state, freshMajor, gl.globalVolStress)
        val (annual, intraSources) = ProbabilityEngine.intradayAnnualVol(vol)
        val calibrate: (HorizonId, Double, Double, Double) -> Triple<Double, Double, Double>? = { h, b, n, d -> calibration.apply(h, b, n, d) }
        val rawUnordered = HorizonId.values().map { h ->
            val sc = scored.getValue(h)
            when (h.group) {
                HorizonGroup.H1 -> {
                    val tg = ProbabilityEngine.target(h, wall, open)
                    var sigma = spot * annual * sqrt(tg.tradingMinutes / (Session.SESSION_MINUTES * Session.TRADING_DAYS))
                    if (tg.overnight) sigma = sqrt(sigma * sigma + (0.55 * spot * annual * sqrt(1 / Session.TRADING_DAYS)).let { it * it })
                    ProbabilityEngine.predict(h, spot, wall, sc, sigma, annual, intraSources, tg, null, 0.0, calibrate)
                }
                else -> {
                    val weekly = h == HorizonId.WEEKLY
                    val res = if (weekly) weeklyRes else monthlyRes
                    val expMs = if (weekly) weeklyExp else monthlyExp
                    val (sigma, src) = ProbabilityEngine.expirySigma(spot, wall, expMs, res.intel, vol, eventRisk.level(h.group))
                    val tg = ProbabilityEngine.target(h, wall, open, expMs)
                    ProbabilityEngine.predict(h, spot, wall, sc, sigma, sigma / spot / sqrt(Session.yearsToExpiry(wall, expMs)), src, tg,
                        res.intel, if (weekly) 0.5 else 0.3, calibrate)
                }
            }
        }
        // Variance cannot shrink with a longer horizon: floor each σ at the σ of any horizon that ends earlier
        // (e.g. on expiry day the IV-implied move to 15:30 must not be smaller than today's-close σ).
        val raw = rawUnordered.sortedBy { it.targetTime }.fold(emptyList<com.niftyengine.engine.model.HorizonPrediction>()) { acc, p ->
            val floor = acc.filter { it.targetTime <= p.targetTime }.maxOfOrNull { it.sigmaPts } ?: 0.0
            acc + if (p.sigmaPts >= floor - 1e-9) p else {
                val sc = scored.getValue(p.id)
                val res = when (p.id) { HorizonId.WEEKLY -> weeklyRes.intel; HorizonId.MONTHLY -> monthlyRes.intel; else -> null }
                ProbabilityEngine.predict(p.id, spot, wall, sc, floor, p.annualVolUsed * floor / p.sigmaPts,
                    p.volSources + com.niftyengine.engine.model.Detail("Floor", "raised to ±%.0f pts (σ of an earlier-ending horizon)".format(floor)),
                    ProbabilityEngine.Target(p.targetTime, p.tradingMinutes, false, p.note), res,
                    if (p.id == HorizonId.WEEKLY) 0.5 else 0.3, calibrate)
            }
        }.sortedBy { it.id.ordinal }
        // §21 confidence per horizon, §22 alignment + master
        val horizons = raw.map { ConfidenceEngine.horizon(it, eventRisk.level(it.id.group), dq.score) }
        val master = ConfidenceEngine.master(horizons, eventRisk, dq.score)
        val hW = horizons.first { it.id == HorizonId.WEEKLY }; val hM = horizons.first { it.id == HorizonId.MONTHLY }
        val distW = PriceDistribution(spot, hW.components); val distM = PriceDistribution(spot, hM.components)

        // §14 expiry regimes + breakout probability
        val weekly = weeklyRes.intel?.let { RegimeEngine.expiryRegime(it, distW, fii.report.score, if (gl.weekly.isNaN()) DataNormalizer.nz(gl.intraday) else gl.weekly,
            eventRisk.level(HorizonGroup.H2), vx.state, st.c15m, weeklyRes.parts["migration"] ?: 0.0) }
        val monthly = monthlyRes.intel?.let { RegimeEngine.expiryRegime(it, distM, fii.report.score, if (gl.monthly.isNaN()) DataNormalizer.nz(gl.weekly) else gl.monthly,
            eventRisk.level(HorizonGroup.H3), vx.state, st.c15m, monthlyRes.parts["migration"] ?: 0.0) }

        // §26 options valuation → strategy → risk
        val forecastVol = listOf(st.histVolAnnual to 0.5, st.realizedVolAnnual to 0.3, vx.level / 100 to 0.2)
            .filter { !it.first.isNaN() && it.first > 0.01 && it.first < 2 }.let { l -> if (l.isEmpty()) Double.NaN else l.sumOf { it.first * it.second } / l.sumOf { it.second } }
        val valW = OptionsValuationEngine.analyze(ExpiryKind.WEEKLY, s.optionChain, distW, spot, wall, weekly?.atmIv ?: Double.NaN, forecastVol, config.costs)
        val sameExpiry = monthlyChain != null && s.optionChain != null && monthlyChain.expiry == s.optionChain.expiry
        val valM = if (sameExpiry) null else OptionsValuationEngine.analyze(ExpiryKind.MONTHLY, monthlyChain, distM, spot, wall, monthly?.atmIv ?: Double.NaN, forecastVol, config.costs)
        val hIntra = horizons.first { it.id == config.intradayHorizon }
        val catIntra = StrategyEngine.category(hIntra, valW?.ivRatio ?: Double.NaN, weekly?.regime ?: com.niftyengine.engine.model.ExpiryRegime.UNDEFINED)
        val catW = StrategyEngine.category(hW, valW?.ivRatio ?: Double.NaN, weekly?.regime ?: com.niftyengine.engine.model.ExpiryRegime.UNDEFINED)
        val catM = StrategyEngine.category(hM, (valM ?: valW)?.ivRatio ?: Double.NaN, monthly?.regime ?: com.niftyengine.engine.model.ExpiryRegime.UNDEFINED)
        val candidates: List<StrategyCandidate> = buildList {
            if (open || !config.requireMarketOpen) addAll(strategyEngine.intradayCandidates(s.optionChain, hIntra, spot, wall, catIntra))
            addAll(strategyEngine.expiryCandidates(ExpiryKind.WEEKLY, s.optionChain, weekly, hW, spot, wall, catW))
            if (!sameExpiry) addAll(strategyEngine.expiryCandidates(ExpiryKind.MONTHLY, monthlyChain, monthly, hM, spot, wall, catM))
        }.sortedByDescending { it.score }
        val best = risk.pick(candidates)
        val decision = risk.decide(best, horizons, master, eventRisk, dq, open, calibration.info)
        val strategies = StrategyReport(catIntra, catW, catM, candidates, best, buildList {
            if (sameExpiry) add("Next weekly expiry is also the monthly expiry — monthly strategies are the weekly ones")
            if (candidates.isEmpty()) add("No option chain — no structures could be priced")
            if (candidates.isNotEmpty() && candidates.none { it.passedFilters }) add("No structure passed the liquidity / spread / freshness filters")
        })

        val signals = linkedMapOf<String, EngineSignal>()
        listOf(st.signal, hw.signal, sec.signal, br, fu.signal, vx.signal, gl.signal, mc.signal, nw.signal, giftSig).forEach { signals[it.name] = it }
        weekly?.signal?.let { signals[it.name] = it }; monthly?.signal?.let { signals[it.name] = it }
        val feed = LinkedHashMap(s.feedStatus)
        feed["Coverage"] = "%.0f%%".format(health.coverage * 100) + if (health.missing.isEmpty()) "" else " (missing: ${health.missing.joinToString()})"
        return EngineOutput(
            timestamp = wall, spot = s.nifty.last, spotChangePct = s.nifty.changePct, marketOpen = open, regime = regime,
            weekly = weekly, monthly = monthly, horizons = horizons, master = master, fii = fii.report, heavyweights = hw.report,
            sectors = sec.rows, expectations = exp, eventRisk = eventRisk, valuation = listOfNotNull(valW, valM), strategies = strategies,
            decision = decision, signals = signals, events = nw.events, dataSource = s.source, feedStatus = feed,
            dataQuality = dq.copy(warnings = dq.warnings + gift.warnings), calibration = calibration.info, engineVersion = ENGINE_VERSION,
            newsHorizons = nw.horizons, pendingEventAnalysis = nw.pending, gift = gift.report,
        )
    }

    /** Which reading feeds which factor of horizon [h] (the factor tables live in [HorizonEngine]). */
    private fun readingsFor(
        h: HorizonId, s: MarketSnapshot, ref: Long, rel: Double,
        st: PriceStructureEngine.Result, hw: NiftyWeightEngine.Result, br: EngineSignal, fu: FuturesPositionEngine.Result,
        vx: VIXEngine.Result, gl: GlobalRiskEngine.Result, gift: EngineSignal, mc: MacroEngine.Result, fii: FiiEngine.Result,
        nw: EventIntelligenceEngine.Result, exp: com.niftyengine.engine.model.ExpectationReport, weeklyRes: ExpiryOptionsEngine.Result,
        sec: SectorEngine.Result,
    ): Map<Factor, FactorReading> {
        val out = HashMap<Factor, FactorReading>()
        fun ch(c: ExpectationChannel) = exp.channels[c]?.get(h.group) ?: Double.NaN
        val futR = fu.reading(h, ref, rel, s.source)
        when (h.group) {
            HorizonGroup.H1 -> {
                out[Factor.PRICE_STRUCTURE] = st.readings[h] ?: FactorReading.missing(Factor.PRICE_STRUCTURE, "no price path")
                out[Factor.HEAVYWEIGHTS] = hw.readings[h] ?: FactorReading.missing(Factor.HEAVYWEIGHTS, "no constituents")
                out[Factor.OPTIONS] = expiryOptions.reading(weeklyRes, h, ref, rel, s.source)
                out[Factor.FUTURES] = futR
                out[Factor.FII] = fii.readings.getValue(h)
                out[Factor.FII_FUTURES] = Composite.blend(Factor.FII_FUTURES, fii.readings.getValue(h), 0.5, futR, 0.5,
                    "FII %+.0f · futures %+.0f".format(fii.readings.getValue(h).direction, futR.direction))
                out[Factor.VIX_IV] = vixReading(h, vx, weeklyRes, ref, rel, s.source)
                out[Factor.GLOBAL] = global.reading(gl, h, gift, ref, rel * 0.85, "Yahoo + GIFT")
                out[Factor.NEWS] = newsReading(h, s, nw)
                out[Factor.BREADTH] = if (br.confidence <= 0) FactorReading.missing(Factor.BREADTH, "constituents unavailable") else {
                    val asOf = s.constituents.values.maxOfOrNull { it.asOf } ?: 0L
                    val age = if (asOf > 0) ((ref - asOf) / 1000.0).coerceAtLeast(0.0) else Double.NaN
                    FactorReading(Factor.BREADTH, true, br.score * 100, 0.35 + 0.65 * br.confidence, Fresh.of(age, Fresh.Cadence.LIVE, h), rel * br.confidence,
                        asOf, age, s.source, br.details.firstOrNull()?.let { "${it.key} ${it.value}" } ?: "", br.details)
                }
            }
            HorizonGroup.H2 -> {
                out[Factor.FII] = fii.readings.getValue(h)
                out[Factor.OPTIONS] = expiryOptions.reading(weeklyRes, h, ref, rel, s.source)
                out[Factor.GLOBAL] = global.reading(gl, h, null, ref, rel * 0.85, "Yahoo").withExtra(ch(ExpectationChannel.GLOBAL), "Fed / US data surprises")
                out[Factor.EARNINGS] = EarningsValuationEngine.earnings(s, h, ref, exp, rel)
                out[Factor.SECTOR_LEADERSHIP] = sectors.weeklyLeadership(s, sec.rows, hw.report, ref, rel * 0.9)
                out[Factor.INDIA_MACRO] = mc.get(Factor.INDIA_MACRO, h)
                // H2 news = events without a dedicated factor (geopolitics, government, other) — the rest flow through their factors.
                val other = listOf(ExpectationChannel.GEOPOLITICS, ExpectationChannel.FISCAL, ExpectationChannel.OTHER).map { ch(it) }.filter { !it.isNaN() }
                out[Factor.NEWS] = if (s.news.isEmpty() && exp.records.isEmpty()) FactorReading.missing(Factor.NEWS, "news feed unavailable")
                else Composite.reading(Factor.NEWS, listOf(Part("Geopolitics / government / other events", if (other.isEmpty()) 0.0 else M.squash(other.sum(), 0.6), 1.0)),
                    1.0, nw.signal.confidence.coerceAtLeast(0.4), 0L, Double.NaN, "news + expectation engine",
                    summary = { "${exp.records.size} tracked expectations" })
            }
            HorizonGroup.H3 -> {
                out[Factor.FII] = fii.readings.getValue(h)
                out[Factor.EARNINGS] = EarningsValuationEngine.earnings(s, h, ref, exp, rel)
                out[Factor.GLOBAL] = global.reading(gl, h, null, ref, rel * 0.85, "Yahoo")
                    .withExtra(listOf(ch(ExpectationChannel.GLOBAL), ch(ExpectationChannel.GEOPOLITICS)).filter { !it.isNaN() }.let { if (it.isEmpty()) Double.NaN else M.clamp(it.sum()) },
                        "Fed / US data / geopolitics")
                out[Factor.VALUATION] = EarningsValuationEngine.valuation(s, ref, EarningsValuationEngine.Config(config.fairPeLow, config.fairPeHigh, config.india10y), rel)
                out[Factor.INDIA_GROWTH] = mc.get(Factor.INDIA_GROWTH, h)
                out[Factor.INFLATION] = mc.get(Factor.INFLATION, h)
                out[Factor.FISCAL] = mc.get(Factor.FISCAL, h)
            }
        }
        out[Factor.USDINR] = mc.get(Factor.USDINR, h)
        out[Factor.CRUDE] = mc.get(Factor.CRUDE, h)
        out[Factor.USDINR_CRUDE] = mc.get(Factor.USDINR_CRUDE, h)
        if (h.group != HorizonGroup.H1) out[Factor.RBI_RATES] = mc.get(Factor.RBI_RATES, h)
        return out
    }

    /** Adds one extra sub-signal (e.g. an expectation-channel surprise) to a reading. */
    private fun FactorReading.withExtra(v: Double, label: String, w: Double = 0.2): FactorReading {
        if (v.isNaN() || abs(v) < 1e-6) return this
        if (!available) return copy(available = true, direction = v * 100, strength = 0.5, freshness = 1.0, reliability = 0.5, summary = "$label only")
        val dir = (direction * (1 - w) + v * 100 * w)
        return copy(direction = M.clamp(dir, -100.0, 100.0), details = details + com.niftyengine.engine.model.Detail(label, "%+.2f (weight %.0f%%)".format(v, w * 100)))
    }

    private fun vixReading(h: HorizonId, vx: VIXEngine.Result, weeklyRes: ExpiryOptionsEngine.Result, ref: Long, rel: Double, src: String): FactorReading {
        if (vx.level.isNaN()) return FactorReading.missing(Factor.VIX_IV, "India VIX unavailable")
        val ivChg = weeklyRes.intel?.ivChange ?: Double.NaN
        val age = if (vx.asOf > 0) ((ref - vx.asOf) / 1000.0).coerceAtLeast(0.0) else Double.NaN
        val spike = if (vx.state == VixState.SPIKING) -0.8 else Double.NaN
        return Composite.reading(Factor.VIX_IV, listOf(
            Part("VIX last hour", if (vx.c1h.isNaN()) Double.NaN else -M.squash(vx.c1h / 3, 1.0), if (h == HorizonId.M30) 0.4 else 0.3, "%+.1f%%".format(vx.c1h)),
            Part("VIX on the day", if (vx.c1d.isNaN()) Double.NaN else -M.squash(vx.c1d / 6, 1.0), if (h == HorizonId.CLOSE) 0.4 else 0.3, "%+.1f%%".format(vx.c1d)),
            Part("Weekly ATM IV change", if (ivChg.isNaN()) Double.NaN else -M.squash(ivChg / 1.5, 1.0), 0.3, "%+.1f vol pts".format(ivChg)),
            Part("VIX spike", spike, 0.3),
        ), Fresh.of(age, Fresh.Cadence.LIVE, h), rel, vx.asOf, age, src,
            summary = { "VIX %.2f (${vx.state.name.lowercase()})".format(vx.level) })
    }

    private fun newsReading(h: HorizonId, s: MarketSnapshot, nw: EventIntelligenceEngine.Result): FactorReading {
        if (s.news.isEmpty() && nw.events.isEmpty()) return FactorReading.missing(Factor.NEWS, "news feed unavailable")
        val nh = when (h) { HorizonId.M30, HorizonId.M60 -> NewsHorizon.M30_120; else -> NewsHorizon.EOD }
        val v = nw.horizons[nh] ?: 0.0
        val ai = nw.events.count { it.analysis?.source?.startsWith("gemini") == true }
        return FactorReading(Factor.NEWS, true, M.clamp(v) * 100, 0.35 + 0.65 * nw.signal.confidence, 1.0,
            if (ai > 0) 0.7 else 0.5, s.news.maxOfOrNull { it.publishedAt } ?: 0L, Double.NaN, if (ai > 0) "news · Gemini" else "news · rules",
            "${nw.events.size} events · ${nh.label} impact %+.2f".format(v), nw.signal.details)
    }

    private fun recordTicks(s: MarketSnapshot, now: Long) {
        fun rec(d: com.niftyengine.engine.model.InstrumentData?) { if (d != null) state.record(d.symbol, now, d.last, d.volume) }
        rec(s.nifty); rec(s.bankNifty); rec(s.vix)
        s.constituents.values.forEach(::rec)
        s.sectors.values.forEach(::rec)
        s.global.values.forEach(::rec)
    }
}

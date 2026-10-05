package com.niftyengine.engine.engines

import com.niftyengine.engine.EngineConfig
import com.niftyengine.engine.NiftyDirectionEngine
import com.niftyengine.engine.core.Session
import com.niftyengine.engine.model.CalibrationInfo
import com.niftyengine.engine.model.Candle
import com.niftyengine.engine.model.FuturesBar
import com.niftyengine.engine.model.FuturesData
import com.niftyengine.engine.model.HorizonId
import com.niftyengine.engine.model.InstrumentData
import com.niftyengine.engine.model.MarketSnapshot
import com.niftyengine.engine.model.Sector
import kotlinx.serialization.Serializable
import java.time.LocalDate

/** Bars for a contiguous block of trading days (plus at least one earlier day for previous closes). */
data class HistoricalChunk(
    /** NIFTY 50 one-minute bars. */
    val nifty: List<Candle>,
    val vix: List<Candle> = emptyList(),
    val bank: List<Candle> = emptyList(),
    /** Continuous near-month futures bars with OI (empty if unavailable). */
    val futures: List<FuturesBar> = emptyList(),
    /** Sector indices / constituents — 1- or 5-minute bars. */
    val sectors: Map<Sector, List<Candle>> = emptyMap(),
    val stocks: Map<String, List<Candle>> = emptyMap(),
    val stockBarMinutes: Int = 5,
)

interface HistoricalDataSource {
    val description: String
    /** Trading days in [from, to]. */
    fun tradingDays(from: LocalDate, to: LocalDate): List<LocalDate>
    /** Bars covering [from, to] and the trading day before [from]. */
    fun chunk(from: LocalDate, to: LocalDate): HistoricalChunk
    /** ≈1 year of NIFTY and India VIX daily candles strictly before [day]. */
    fun dailyBefore(day: LocalDate): Pair<List<Candle>, List<Candle>>
}

@Serializable
data class BaselineRow(
    val horizon: String,
    val n: Int,
    val modelAccuracy: Double,
    val momentumAccuracy: Double,
    val modelBrier: Double,
    val climatologyBrier: Double,
    /** 1 − model/climatology: > 0 means the model beats "always predict the past base rates". */
    val brierSkill: Double,
    val classRates: Map<String, Double>,
    /** Share of outcomes inside the predicted ~68 % range. */
    val rangeHitRate: Double = Double.NaN,
)

@Serializable
data class ThresholdRow(
    val horizon: String,
    val threshold: Double,
    val calibrated: Boolean,
    val signals: Int,
    val hitRate: Double,
    /** Average NIFTY move in the predicted direction (points). */
    val avgMovePts: Double,
)

@Serializable
data class RegimeRow(val regime: String, val n: Int, val accuracy: Double, val directionalHitRate: Double)

@Serializable
data class BacktestReport(
    val source: String,
    val from: String,
    val to: String,
    val days: Int,
    val cycles: Int,
    val predictions: Int,
    val baselines: List<BaselineRow>,
    val thresholds: List<ThresholdRow>,
    val regimes: List<RegimeRow>,
    val calibration: CalibrationInfo,
    /** Calibrated-at-the-time Brier for the decision horizon (walk-forward), NaN until calibration kicked in. */
    val calibratedBrier: Double,
    val calibratedN: Int,
    val notes: List<String>,
    val elapsedMs: Long,
)

/**
 * 22 — Historical (market-only) backtest.
 *
 * Replays real minute data every [Config.stepMinutes] through the full engine. At each step the snapshot holds
 * only COMPLETED bars up to that instant (no look-ahead). Calibration is refitted walk-forward from days already
 * replayed. Results are compared with two baselines that any real edge must beat:
 *   • climatology — always predict the base rates of bull/bear/range observed so far;
 *   • momentum — predict that the last 30 minutes' move continues.
 * Options, news, GIFT Nifty, global markets and flows do not exist historically, so only the market-only core
 * (structure, heavyweights, sectors, breadth, futures, VIX → regime → probabilities) is tested.
 */
class HistoricalBacktest(private val engineConfig: EngineConfig = EngineConfig(), private val cfg: Config = Config()) {
    data class Config(
        val stepMinutes: Int = 5,
        val chunkDays: Int = 20,
        val recalibrateEveryDays: Int = 5,
        val minCalibrationSamples: Int = 150,
    )

    data class Run(val report: BacktestReport, val records: List<PredictionRecord>)

    /** O(1) update by id (the generic in-memory store is O(n), too slow for year-long replays). */
    private class IndexedStore : PredictionStore {
        private val map = LinkedHashMap<String, PredictionRecord>()
        override fun append(r: PredictionRecord) { map[r.id] = r }
        override fun update(r: PredictionRecord) { if (map.containsKey(r.id)) map[r.id] = r }
        override fun all(): List<PredictionRecord> = map.values.toList()
        fun since(t: Long): List<PredictionRecord> = map.values.filter { it.timestamp >= t }
        val size get() = map.size
    }

    private class DayIndex(bars: List<Candle>) {
        val byDay: Map<LocalDate, List<Candle>> = bars.sortedBy { it.t }.groupBy { Session.zdt(it.t).toLocalDate() }
        val days = byDay.keys.sorted()
        fun prevClose(day: LocalDate): Double = days.lastOrNull { it < day }?.let { byDay.getValue(it).last().c } ?: Double.NaN
        /** Completed bars of [day] at instant [t]. */
        fun upTo(day: LocalDate, t: Long, barMs: Long): List<Candle> {
            val l = byDay[day] ?: return emptyList()
            var n = 0
            while (n < l.size && l[n].t + barMs <= t) n++
            return l.subList(0, n)
        }
    }

    fun run(
        src: HistoricalDataSource, from: LocalDate, to: LocalDate,
        onProgress: (String, Float) -> Unit = { _, _ -> }, cancelled: () -> Boolean = { false },
    ): Run {
        val started = System.currentTimeMillis()
        val engine = NiftyDirectionEngine(engineConfig.copy(requireMarketOpen = false, marketOnlyBacktest = true))
        val store = IndexedStore()
        val logger = PredictionLogger(store)
        val momentum = HashMap<String, Double>() // record id → NIFTY move over the 30 min before the prediction
        val closes = HashMap<LocalDate, Double>()
        val days = src.tradingDays(from, to)
        if (days.isEmpty()) throw IllegalStateException("No trading days between $from and $to")
        var cycles = 0
        var daysDone = 0
        var calibration = CalibrationModel()

        for (block in days.chunked(cfg.chunkDays)) {
            if (cancelled()) break
            onProgress("Fetching ${block.first()} → ${block.last()}", daysDone.toFloat() / days.size)
            val ch = src.chunk(block.first(), block.last())
            val nifty = DayIndex(ch.nifty); val vix = DayIndex(ch.vix); val bank = DayIndex(ch.bank)
            val sectors = ch.sectors.mapValues { DayIndex(it.value) }
            val stocks = ch.stocks.mapValues { DayIndex(it.value) }
            val futByDay = ch.futures.sortedBy { it.t }.groupBy { Session.zdt(it.t).toLocalDate() }
            val futDays = futByDay.keys.sorted()
            var (niftyDaily, vixDaily) = src.dailyBefore(block.first())
            val stockMs = ch.stockBarMinutes * 60_000L

            for (day in block) {
                if (cancelled()) break
                val niftyDay = nifty.byDay[day]
                if (niftyDay.isNullOrEmpty()) { daysDone++; continue }
                val open = Session.sessionStart(niftyDay.first().t)
                val pcNifty = nifty.prevClose(day).takeIf { !it.isNaN() } ?: niftyDaily.lastOrNull()?.c ?: niftyDay.first().o
                val pcVix = vix.prevClose(day).takeIf { !it.isNaN() } ?: vixDaily.lastOrNull()?.c ?: Double.NaN
                val pcBank = bank.prevClose(day)
                val pcSectors = sectors.mapValues { it.value.prevClose(day) }
                val pcStocks = stocks.mapValues { it.value.prevClose(day) }
                val prevFut = futDays.lastOrNull { it < day }?.let { futByDay.getValue(it).last() }
                val futToday = futByDay[day].orEmpty()

                fun inst(sym: String, bars: List<Candle>, prev: Double, t: Long, keepBars: Boolean = true): InstrumentData? {
                    if (bars.isEmpty() || prev.isNaN()) return null
                    return InstrumentData(sym, bars.last().c, prev, bars.first().o, bars.maxOf { it.h }, bars.minOf { it.l },
                        bars.sumOf { it.v }, intraday = if (keepBars) bars else emptyList(), asOf = t)
                }

                var t = open + cfg.stepMinutes * 60_000L
                val end = Session.closeOf(day) - 5 * 60_000L
                while (t <= end) {
                    val nBars = nifty.upTo(day, t, 60_000L)
                    if (nBars.isNotEmpty()) {
                        val fBars = futToday.filter { it.t + 60_000L <= t }
                        val snap = MarketSnapshot(
                            timestamp = t,
                            nifty = InstrumentData("NIFTY 50", nBars.last().c, pcNifty, nBars.first().o, nBars.maxOf { it.h },
                                nBars.minOf { it.l }, 0.0, nBars, niftyDaily, asOf = t),
                            bankNifty = inst("NIFTY BANK", bank.upTo(day, t, 60_000L), pcBank, t),
                            vix = inst("INDIA VIX", vix.upTo(day, t, 60_000L), pcVix, t)?.copy(daily = vixDaily),
                            futures = if (fBars.isEmpty() || prevFut == null) null else FuturesData(
                                "NIFTY FUT (continuous)", "", fBars.last().price, prevFut.price, fBars.last().oi, prevFut.oi,
                                fBars.sumOf { it.volume }, fBars, asOf = t),
                            sectors = sectors.mapNotNull { (sec, idx) -> inst("SECTOR_${sec.name}", idx.upTo(day, t, stockMs), pcSectors.getValue(sec), t)?.let { sec to it } }.toMap(),
                            constituents = stocks.mapNotNull { (sym, idx) -> inst(sym, idx.upTo(day, t, stockMs), pcStocks.getValue(sym), t)?.let { sym to it } }.toMap(),
                            source = "backtest:${src.description}",
                        )
                        val out = engine.process(snap)
                        cycles++
                        // Slim records keep year-long backtests in memory.
                        val r = logger.record(out, emptyMap(), slim = true)
                        val p30 = nBars.lastOrNull { it.t + 60_000L <= t - 30 * 60_000L }?.c ?: nBars.first().o
                        momentum[r.id] = nBars.last().c - p30
                    }
                    t += cfg.stepMinutes * 60_000L
                }
                // Outcomes: this day's realised minute path (completed bar closes) + every daily close so far (close / expiry targets).
                val path = niftyDay.map { PredictionLogger.PricePoint(it.t + 60_000L, it.c) }
                closes[day] = niftyDay.last().c
                logger.evaluate(Session.closeOf(day) + 1, path, closes)
                // Daily history grows as the replay moves forward.
                niftyDaily = niftyDaily + Candle(open, niftyDay.first().o, niftyDay.maxOf { it.h }, niftyDay.minOf { it.l }, niftyDay.last().c)
                vix.byDay[day]?.let { v -> vixDaily = vixDaily + Candle(open, v.first().o, v.maxOf { it.h }, v.minOf { it.l }, v.last().c) }
                daysDone++
                if (daysDone % cfg.recalibrateEveryDays == 0) {
                    calibration = ProbabilityCalibrator.fit(store.all(), cfg.minCalibrationSamples)
                    engine.calibration = calibration
                }
                onProgress("Replayed $day (${daysDone}/${days.size}) · ${store.size} predictions", daysDone.toFloat() / days.size)
            }
        }
        val recs = store.all()
        calibration = ProbabilityCalibrator.fit(recs, cfg.minCalibrationSamples)
        val report = report(src, days, cycles, recs, momentum, calibration.info, System.currentTimeMillis() - started)
        return Run(report, recs)
    }

    fun report(src: HistoricalDataSource, days: List<LocalDate>, cycles: Int, recs: List<PredictionRecord>,
               momentum: Map<String, Double>, cal: CalibrationInfo, elapsed: Long): BacktestReport {
        val baselines = HorizonId.values().map { h ->
            val done = recs.mapNotNull { r -> r.horizon(h)?.takeIf { it.outcome != null }?.let { r to it } }.sortedBy { it.first.timestamp }
            // Climatology, walk-forward: base rates from outcomes already known at prediction time.
            val counts = IntArray(3); var known = 0; var j = 0
            var climB = 0.0; var modelB = 0.0; var modelHit = 0; var momHit = 0
            for ((r, x) in done) {
                while (j < done.size && done[j].second.targetTime <= r.timestamp) {
                    counts[done[j].second.outcome!!.realized + 1]++; known++; j++
                }
                val pd = (counts[0] + 1.0) / (known + 3); val pn = (counts[1] + 1.0) / (known + 3); val pb = (counts[2] + 1.0) / (known + 3)
                val y = x.outcome!!.realized
                climB += PerformanceStats.brier(pb, pn, pd, y)
                modelB += PerformanceStats.brier(x.pBull, x.pNeutral, x.pBear, y)
                if (x.predictedClass == y) modelHit++
                val m = momentum[r.id] ?: 0.0
                val momCls = when { m > x.band -> 1; m < -x.band -> -1; else -> 0 }
                if (momCls == y) momHit++
            }
            val n = done.size.coerceAtLeast(1)
            BaselineRow(h.name, done.size, modelHit.toDouble() / n, momHit.toDouble() / n, modelB / n, climB / n,
                if (climB > 0) 1 - modelB / climB else Double.NaN,
                mapOf("bull" to done.count { it.second.outcome!!.realized == 1 }.toDouble() / n,
                    "bear" to done.count { it.second.outcome!!.realized == -1 }.toDouble() / n,
                    "neutral" to done.count { it.second.outcome!!.realized == 0 }.toDouble() / n),
                done.count { it.second.outcome!!.insideRange }.toDouble() / n)
        }
        val thresholds = listOf(HorizonId.M30, HorizonId.M60, HorizonId.CLOSE).flatMap { h ->
            listOf(false, true).flatMap { calibrated ->
                listOf(0.50, 0.55, 0.60, 0.65, 0.70).map { th ->
                    val sel = recs.mapNotNull { r ->
                        val x = r.horizon(h)?.takeIf { it.outcome != null } ?: return@mapNotNull null
                        val (pb, pd) = if (calibrated) { if (!x.calibrated) return@mapNotNull null; x.calPBull to x.calPBear } else x.pBull to x.pBear
                        val side = when { pb >= th && pb > pd -> 1; pd >= th && pd > pb -> -1; else -> 0 }
                        if (side == 0) null else side * x.outcome!!.move
                    }
                    ThresholdRow(h.name, th, calibrated, sel.size, if (sel.isEmpty()) Double.NaN else sel.count { it > 0 }.toDouble() / sel.size,
                        if (sel.isEmpty()) Double.NaN else sel.average())
                }
            }
        }
        val regimes = recs.mapNotNull { r -> r.horizon(HorizonId.M30)?.takeIf { it.outcome != null }?.let { r to it } }.groupBy { it.first.regime }
            .map { (k, l) ->
                val dir = l.filter { it.second.predictedClass != 0 }
                RegimeRow(k, l.size, l.count { (_, x) -> x.predictedClass == x.outcome!!.realized }.toDouble() / l.size,
                    if (dir.isEmpty()) Double.NaN else dir.count { (_, x) -> x.predictedClass * x.outcome!!.move > 0 }.toDouble() / dir.size)
            }.sortedByDescending { it.n }
        val h = engineConfig.intradayHorizon
        val calRows = recs.mapNotNull { r -> r.horizon(h)?.takeIf { it.outcome != null && it.calibrated && !it.calPBull.isNaN() } }
        val calBrier = if (calRows.isEmpty()) Double.NaN else calRows.map { PerformanceStats.brier(it.calPBull, it.calPNeutral, it.calPBear, it.outcome!!.realized) }.average()
        val notes = listOf(
            "Market-only: no historical option chain, news, GIFT Nifty, global markets, FII data or macro inputs — those factors were " +
                "absent, so their weights were redistributed to the market factors (the live model has many more inputs).",
            "Constituents and weights are today's NIFTY 50 (survivorship bias for older periods).",
            "Snapshots every ${cfg.stepMinutes} min use completed bars only; calibration refitted every ${cfg.recalibrateEveryDays} days from earlier days only.",
            "Outcome classes: bullish/bearish if the move beats the prediction's neutral band (0.25σ of that horizon), else neutral.",
            "Weekly/monthly targets use rule-based expiry dates (Tuesday / last Tuesday) and σ from India VIX.",
            "Continuous futures OI jumps on rollover days, which can distort the futures signal on those days.",
        )
        return BacktestReport(src.description, days.first().toString(), days.last().toString(), days.size, cycles, recs.size,
            baselines, thresholds, regimes, cal, calBrier, calRows.size, notes, elapsed)
    }
}

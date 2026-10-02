package com.niftyengine.engine.engines

import com.niftyengine.engine.EngineConfig
import com.niftyengine.engine.NiftyDirectionEngine
import com.niftyengine.engine.core.Session
import com.niftyengine.engine.model.EngineOutput
import com.niftyengine.engine.model.InstrumentData
import com.niftyengine.engine.model.MarketSnapshot
import com.niftyengine.engine.model.NewsItem

/**
 * 18 — Historical Replay Engine.
 *
 * Mode A (MARKET_ONLY): replays market data only — news is removed.
 * Mode B (FULL_INFORMATION): market + news/events that were *published* by each timestamp.
 *
 * Critical rule: at time t the engine only sees information available at t. Intraday bars after t,
 * daily bars from t's own session, and news published after t are stripped from every snapshot
 * (no future news, no revised data, no hindsight). A fresh engine instance is used per run.
 */
enum class ReplayMode { MARKET_ONLY, FULL_INFORMATION }

class HistoricalReplayEngine(private val config: EngineConfig = EngineConfig()) {
    data class Result(
        val mode: ReplayMode,
        val records: List<PredictionRecord>,
        val summaries: List<PerformanceStats.Summary>,
        val outputs: Int,
        val lastOutput: EngineOutput?,
    )

    fun run(
        snapshots: List<MarketSnapshot>,
        mode: ReplayMode,
        newsArchive: List<NewsItem> = emptyList(),
        predictEvery: Int = 1,
        calibration: CalibrationModel = CalibrationModel(),
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Result {
        val sorted = snapshots.sortedBy { it.timestamp }
        val engine = NiftyDirectionEngine(config.copy(requireMarketOpen = false))
        engine.calibration = calibration
        val store = InMemoryPredictionStore()
        val logger = PredictionLogger(store)
        val allNews = (newsArchive + sorted.flatMap { it.news }).distinctBy { it.id }
        var last: EngineOutput? = null
        sorted.forEachIndexed { i, raw ->
            val snap = pointInTime(raw, mode, allNews)
            // Replayed snapshots are historical by construction: judge freshness relative to their own time.
            val out = engine.process(snap)
            last = out
            if (i % predictEvery == 0 && Session.isOpen(out.timestamp)) logger.record(out)
            onProgress(i + 1, sorted.size)
        }
        // Outcomes, including the selected option's later price.
        for (r in store.all()) {
            val path = sorted.filter { it.timestamp > r.timestamp && it.timestamp <= r.timestamp + 61 * 60_000L }.map { s ->
                val leg = s.optionChain?.rows?.firstOrNull { it.strike == r.strike }?.let { if (r.optionType == "CE") it.call else it.put }
                PredictionLogger.PricePoint(s.timestamp, s.nifty.last, leg?.ltp ?: Double.NaN)
            }
            val outs = logger.horizons.mapNotNull { h -> logger.outcomeFor(r, h, path) }
            if (outs.isNotEmpty()) store.update(r.copy(outcomes = outs))
        }
        val recs = store.all()
        return Result(mode, recs, logger.horizons.map { PerformanceStats.summarize(recs, it) }, sorted.size, last)
    }

    data class WalkForward(val result: Result, val sessions: Int, val finalCalibration: CalibrationModel)

    /**
     * Walk-forward validation over several sessions (oldest first). Session k is predicted with a calibration
     * fitted only on sessions 0..k-1 — the probabilities each prediction carries were knowable at that time.
     */
    fun runWalkForward(
        sessions: List<List<MarketSnapshot>>, mode: ReplayMode, newsArchive: List<NewsItem> = emptyList(),
        minSamples: Int = 150, onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): WalkForward {
        val ordered = sessions.filter { it.isNotEmpty() }.sortedBy { s -> s.minOf { it.timestamp } }
        val all = ArrayList<PredictionRecord>()
        var cal = CalibrationModel()
        var outputs = 0
        var last: EngineOutput? = null
        ordered.forEachIndexed { i, snaps ->
            val r = run(snaps, mode, newsArchive, 1, cal)
            all += r.records; outputs += r.outputs; last = r.lastOutput ?: last
            cal = ProbabilityCalibrator.fit(all, minSamples)
            onProgress(i + 1, ordered.size)
        }
        val horizons = ProbabilityCalibrator.HORIZONS
        return WalkForward(Result(mode, all, horizons.map { PerformanceStats.summarize(all, it) }, outputs, last), ordered.size, cal)
    }

    /** Strip everything that was not knowable at the snapshot's timestamp. */
    fun pointInTime(s: MarketSnapshot, mode: ReplayMode, archive: List<NewsItem>): MarketSnapshot {
        val t = s.timestamp
        val open = Session.sessionStart(t)
        fun clip(d: InstrumentData) = d.copy(
            intraday = d.intraday.filter { it.t <= t },
            daily = d.daily.filter { it.t < open },
        )
        val news = when (mode) {
            ReplayMode.MARKET_ONLY -> emptyList()
            ReplayMode.FULL_INFORMATION -> archive.filter { it.publishedAt <= t }
        }
        return s.copy(
            nifty = clip(s.nifty), bankNifty = s.bankNifty?.let(::clip), vix = s.vix?.let(::clip),
            constituents = s.constituents.mapValues { clip(it.value) },
            sectors = s.sectors.mapValues { clip(it.value) },
            global = s.global.mapValues { clip(it.value) },
            news = news,
            source = "replay:${mode.name.lowercase()}",
        )
    }
}

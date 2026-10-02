package com.niftyengine.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.niftyengine.app.Notifier
import com.niftyengine.app.data.KiteClient
import com.niftyengine.app.data.LiveSnapshotProvider
import com.niftyengine.app.store.AppSettings
import com.niftyengine.app.store.DataMode
import com.niftyengine.app.store.JsonlPredictionStore
import com.niftyengine.app.store.SessionRecorder
import com.niftyengine.app.store.SettingsStore
import com.niftyengine.engine.NiftyDirectionEngine
import com.niftyengine.engine.core.Session
import com.niftyengine.engine.engines.HistoricalReplayEngine
import com.niftyengine.engine.engines.PerformanceStats
import com.niftyengine.engine.engines.PredictionLogger
import com.niftyengine.engine.engines.PredictionRecord
import com.niftyengine.engine.engines.ReplayMode
import com.niftyengine.engine.engines.SnapshotProvider
import com.niftyengine.engine.model.Decision
import com.niftyengine.engine.model.EngineOutput
import com.niftyengine.engine.model.MarketSnapshot
import com.niftyengine.engine.model.OptionChain
import com.niftyengine.engine.sim.SimulatedMarket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ChartPoint(val t: Long, val spot: Double, val pBull: Double, val pBear: Double)

data class ReplayState(
    val running: Boolean = false,
    val progress: Float = 0f,
    val label: String = "",
    val result: HistoricalReplayEngine.Result? = null,
    val error: String? = null,
)

data class UiState(
    val output: EngineOutput? = null,
    val running: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val lastUpdate: Long = 0L,
    val cycles: Int = 0,
    val chart: List<ChartPoint> = emptyList(),
    val stats: List<PerformanceStats.Summary> = emptyList(),
    val records: List<PredictionRecord> = emptyList(),
    val sessions: List<File> = emptyList(),
    val replay: ReplayState = ReplayState(),
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val settingsStore = SettingsStore(app)
    private val _settings = MutableStateFlow(settingsStore.load())
    val settings: StateFlow<AppSettings> = _settings

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    private val predictionStore = JsonlPredictionStore(File(app.filesDir, "predictions.jsonl"))
    private val logger = PredictionLogger(predictionStore)
    private val recorder = SessionRecorder(File(app.filesDir, "sessions"))
    private val notifier = Notifier(app)

    private var engine = NiftyDirectionEngine(_settings.value.engineConfig())
    private var provider: SnapshotProvider = makeProvider()
    private var loop: Job? = null
    private var lastLoggedBucket = -1L
    private var lastDecision: Decision? = null
    /** Recent spot + chain history used to attach outcomes to logged predictions. */
    private val path = ArrayDeque<Triple<Long, Double, OptionChain?>>()

    init {
        refreshStats()
        start()
    }

    private fun makeProvider(): SnapshotProvider = when (_settings.value.mode) {
        DataMode.SIMULATED -> SimulatedMarket(seed = System.currentTimeMillis() / 86_400_000L)
        else -> LiveSnapshotProvider(File(getApplication<Application>().cacheDir, "kite")) { _settings.value }
    }

    fun start() {
        if (loop?.isActive == true) return
        _ui.update { it.copy(running = true) }
        loop = viewModelScope.launch {
            while (isActive) {
                cycle()
                val s = _settings.value
                delay(if (s.mode == DataMode.SIMULATED) s.simSecondsPerMinute * 1000L else s.refreshSeconds * 1000L)
            }
        }
    }

    fun stop() {
        loop?.cancel(); loop = null
        _ui.update { it.copy(running = false) }
    }

    fun refreshNow() = viewModelScope.launch { cycle() }

    private suspend fun cycle() {
        if (_ui.value.busy) return
        _ui.update { it.copy(busy = true) }
        try {
            val out = withContext(Dispatchers.Default) {
                val snap = withContext(Dispatchers.IO) { provider.collect(System.currentTimeMillis()) }
                val o = engine.process(snap)
                afterCycle(snap, o)
                o
            }
            _ui.update { s ->
                val chart = (s.chart + ChartPoint(out.timestamp, out.spot, out.direction.pBull, out.direction.pBear))
                    .filter { Session.zdt(it.t).toLocalDate() == Session.zdt(out.timestamp).toLocalDate() }.takeLast(400)
                s.copy(output = out, error = null, lastUpdate = System.currentTimeMillis(), cycles = s.cycles + 1, chart = chart)
            }
        } catch (e: Exception) {
            _ui.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
        } finally {
            _ui.update { it.copy(busy = false) }
        }
    }

    private fun afterCycle(snap: MarketSnapshot, o: EngineOutput) {
        val s = _settings.value
        path.addLast(Triple(o.timestamp, o.spot, snap.optionChain))
        while (path.isNotEmpty() && path.first().first < o.timestamp - 75 * 60_000L) path.removeFirst()

        // Log one prediction per 5-minute bucket while the session is open (and on every decision change).
        val bucket = o.timestamp / (5 * 60_000L)
        val sessionOpen = Session.isOpen(o.timestamp)
        if (sessionOpen && (bucket != lastLoggedBucket || o.decision.decision != lastDecision)) {
            logger.record(o); lastLoggedBucket = bucket
        }
        val changed = logger.evaluate(o.timestamp) { r ->
            path.filter { it.first > r.timestamp }.map { (t, spot, chain) ->
                val leg = chain?.rows?.firstOrNull { it.strike == r.strike }?.let { if (r.optionType == "CE") it.call else it.put }
                PredictionLogger.PricePoint(t, spot, leg?.ltp ?: Double.NaN)
            }
        }
        if (changed > 0) predictionStore.flush()
        if (s.recordSessions && s.mode != DataMode.SIMULATED && sessionOpen) runCatching { recorder.record(snap) }
        if (s.notifyOnTrade && o.decision.decision == Decision.TRADE && lastDecision != Decision.TRADE) notifier.trade(o)
        lastDecision = o.decision.decision
        refreshStats()
    }

    fun refreshStats() {
        val recs = predictionStore.all()
        _ui.update {
            it.copy(
                stats = logger.horizons.map { h -> PerformanceStats.summarize(recs, h) },
                records = recs.takeLast(200).reversed(),
                sessions = recorder.sessions(),
            )
        }
    }

    fun clearLog() { predictionStore.clear(); refreshStats() }

    fun exportLogFile(): File = predictionStore.exportFile()

    fun deleteSession(f: File) { recorder.delete(f); refreshStats() }

    fun updateSettings(new: AppSettings) {
        val old = _settings.value
        settingsStore.save(new)
        _settings.value = new
        if (old.kiteAccessToken != new.kiteAccessToken && new.mode == DataMode.LIVE_KITE) refreshNow()
        if (old.mode != new.mode || old.engineConfig() != new.engineConfig()) {
            engine = NiftyDirectionEngine(new.engineConfig())
            if (old.mode != new.mode) { provider = makeProvider(); path.clear(); _ui.update { it.copy(chart = emptyList(), output = null) } }
            refreshNow()
        }
    }

    fun completeKiteLogin(requestToken: String) = viewModelScope.launch {
        val s = _settings.value
        try {
            val token = withContext(Dispatchers.IO) { KiteClient.createSession(s.kiteApiKey, s.kiteApiSecret, requestToken) }
            updateSettings(s.copy(kiteAccessToken = token, kiteTokenDate = Session.zdt(System.currentTimeMillis()).toLocalDate().toString(),
                mode = DataMode.LIVE_KITE))
            _ui.update { it.copy(error = null) }
        } catch (e: Exception) {
            _ui.update { it.copy(error = "Kite login failed: ${e.message}") }
        }
    }

    /** 18 — replay a recorded live session (or a freshly simulated day when [file] is null). */
    fun runReplay(file: File?, mode: ReplayMode) {
        if (_ui.value.replay.running) return
        viewModelScope.launch {
            _ui.update { it.copy(replay = ReplayState(running = true, label = file?.name ?: "Simulated session")) }
            try {
                val result = withContext(Dispatchers.Default) {
                    val snaps: List<MarketSnapshot>
                    val archive = if (file == null) emptyList() else recorder.newsArchive()
                    if (file == null) {
                        val sim = SimulatedMarket(seed = System.nanoTime())
                        snaps = List(Session.SESSION_MINUTES - 2) { sim.collect(0) }
                    } else snaps = recorder.load(file)
                    if (snaps.size < 20) throw IllegalStateException("Session has only ${snaps.size} snapshots")
                    HistoricalReplayEngine(_settings.value.engineConfig()).run(snaps, mode, archive) { done, total ->
                        if (done % 10 == 0) _ui.update { it.copy(replay = it.replay.copy(progress = done.toFloat() / total)) }
                    }
                }
                _ui.update { it.copy(replay = it.replay.copy(running = false, progress = 1f, result = result)) }
            } catch (e: Exception) {
                _ui.update { it.copy(replay = it.replay.copy(running = false, error = e.message)) }
            }
        }
    }
}

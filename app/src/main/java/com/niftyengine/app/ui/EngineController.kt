package com.niftyengine.app.ui

import android.app.Application
import com.niftyengine.app.CrashLog
import com.niftyengine.app.EngineService
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
import com.niftyengine.app.data.GeminiEventAnalyst
import com.niftyengine.app.data.KiteApi
import com.niftyengine.app.data.KiteHistoricalSource
import com.niftyengine.engine.engines.HistoricalBacktest
import com.niftyengine.app.data.GeminiException
import com.niftyengine.app.store.AppJson
import com.niftyengine.engine.engines.CalibrationModel
import com.niftyengine.engine.engines.EventTrackerState
import com.niftyengine.engine.engines.BaselineState
import com.niftyengine.engine.model.ShadowBook
import com.niftyengine.app.store.PrettyJson
import com.niftyengine.engine.model.EventAnalysis
import com.niftyengine.engine.engines.HistoricalReplayEngine
import com.niftyengine.engine.engines.ProbabilityCalibrator
import com.niftyengine.engine.model.CalibrationInfo
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
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    /** Set for walk-forward runs: calibration fitted from the replayed sessions. */
    val walkForwardCalibration: CalibrationInfo? = null,
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
    val calibration: CalibrationInfo = CalibrationInfo(),
    val optionCalibrationSamples: Int = 0,
    /** Event-analyst status line (Gemini or rules fallback). */
    val analystStatus: String = "",
    val backtest: BacktestState = BacktestState(),
)

data class BacktestState(
    val running: Boolean = false,
    val progress: Float = 0f,
    val label: String = "",
    val report: com.niftyengine.engine.engines.BacktestReport? = null,
    val sourceNotes: List<String> = emptyList(),
    val csv: File? = null,
    val error: String? = null,
)

/**
 * Owns the engine loop. Application-scoped (one per process, held by [com.niftyengine.app.NiftyApp]) so it keeps
 * running when the Activity is minimised or destroyed; [com.niftyengine.app.EngineService] keeps the process alive.
 */
class EngineController(private val app: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, e ->
        // Never let a background failure take the whole app down: report it in the top bar instead.
        CrashLog.write(app, Thread.currentThread(), e, fatal = false)
        _ui.update { it.copy(error = "Internal error: ${e.javaClass.simpleName}: ${e.message}", busy = false) }
    })
    /**
     * The engine, its event memory, the provider and the outcome path are not thread-safe: every access to them
     * runs on this one thread (cycles, settings changes, Kite login) so a settings change can't race a cycle.
     */
    private val engineThread = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "nifty-engine") }
        .asCoroutineDispatcher()
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
    private var calibration = CalibrationModel()
    private var lastFit = 0L

    // ---- v4 event intelligence: Gemini runs off the cycle; results enter the NEXT snapshot (recorded ⇒ replayable)
    private val analysesQueue = java.util.concurrent.ConcurrentLinkedQueue<EventAnalysis>()
    @Volatile private var analystBusy = false
    private var analystNextAllowed = 0L
    private var analystLastOk = 0L
    private var analystLastError: String? = null
    private val budgetPrefs = app.getSharedPreferences("gemini_budget", android.content.Context.MODE_PRIVATE)
    private val eventStateFile = File(app.filesDir, "events-live.json")
    /** v5: rolling relative baselines (live data only) and the shadow book (kept apart for simulator vs live). */
    private val baselinesFile = File(app.filesDir, "baselines-live.json")
    private fun shadowFile(mode: DataMode = _settings.value.mode) =
        File(app.filesDir, if (mode == DataMode.SIMULATED) "shadow-sim.json" else "shadow-live.json")
    private var cyclesSinceSave = 0
    private var provider: SnapshotProvider = makeProvider()
    private var loop: Job? = null
    private var lastLoggedBucket = -1L
    private var lastDecision: Decision? = null
    /** Recent spot + chain history used to attach outcomes to logged predictions. */
    private val path = ArrayDeque<Triple<Long, Double, OptionChain?>>()

    init {
        start() // first cycle runs on the engine thread after the initial load below
        scope.launch(engineThread) {
            loadEventState()
            loadV5State()
            refitNow()
            refreshStats()
        }
    }

    private fun makeProvider(): SnapshotProvider = when (_settings.value.mode) {
        DataMode.SIMULATED -> SimulatedMarket(seed = System.currentTimeMillis() / 86_400_000L)
        else -> LiveSnapshotProvider(File(app.cacheDir, "kite")) { _settings.value }
    }

    fun start() {
        if (loop?.isActive == true) return
        _ui.update { it.copy(running = true) }
        if (_settings.value.runInBackground) EngineService.start(app)
        loop = scope.launch {
            while (isActive) {
                cycle()
                delay(nextDelayMs(_settings.value, System.currentTimeMillis()))
            }
        }
    }

    fun stop() {
        loop?.cancel(); loop = null
        _ui.update { it.copy(running = false) }
        persist()
        EngineService.stop(app)
    }

    fun refreshNow() = scope.launch { cycle() }

    private suspend fun cycle() {
        if (_ui.value.busy) return
        _ui.update { it.copy(busy = true) }
        try {
            val out = withContext(engineThread) {
                val raw = withContext(Dispatchers.IO) { provider.collect(System.currentTimeMillis()) }
                // Only analyses that existed when the snapshot was completed belong to it; later ones wait for the next.
                val (delivered, later) = generateSequence { analysesQueue.poll() }.toList().partition { it.analyzedAt <= raw.timestamp }
                analysesQueue.addAll(later)
                val snap = if (delivered.isEmpty()) raw else raw.copy(eventAnalyses = delivered)
                val o = engine.process(snap)
                afterCycle(snap, o)
                maybeAnalyzeEvents(o)
                o
            }
            _ui.update { s ->
                val chart = (s.chart + ChartPoint(out.timestamp, out.spot, out.direction.pBull, out.direction.pBear))
                    .filter { Session.zdt(it.t).toLocalDate() == Session.zdt(out.timestamp).toLocalDate() }.takeLast(400)
                s.copy(output = out, error = null, lastUpdate = System.currentTimeMillis(), cycles = s.cycles + 1, chart = chart)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) { // incl. OutOfMemoryError: report and keep going rather than crash
            if (e !is Exception) CrashLog.write(app, Thread.currentThread(), e, fatal = false)
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
            logger.record(o, s.auditConfig()); lastLoggedBucket = bucket
        }
        val changed = logger.evaluate(o.timestamp) { r ->
            path.filter { it.first > r.timestamp }.map { (t, spot, chain) ->
                val leg = chain?.rows?.firstOrNull { it.strike == r.strike }?.let { if (r.optionType == "CE") it.call else it.put }
                PredictionLogger.PricePoint(t, spot, leg?.ltp ?: Double.NaN)
            }
        }
        if (changed > 0) predictionStore.flush()
        if (changed > 0 && System.currentTimeMillis() - lastFit > 10 * 60_000L) refitNow()
        if (s.recordSessions && s.mode != DataMode.SIMULATED && sessionOpen) runCatching { recorder.record(snap) }
        val alert = o.decision.decision == Decision.TRADE || o.decision.decision == Decision.PAPER_TRADE
        if (s.notifyOnTrade && alert && lastDecision != o.decision.decision) notifier.trade(o)
        lastDecision = o.decision.decision
        saveEventState()
        refreshStats()
    }

    /**
     * Records from the same data family as the current mode: simulated predictions must never calibrate
     * (or be mixed into the statistics of) live predictions.
     */
    private fun recordsForMode(): List<PredictionRecord> {
        val sim = _settings.value.mode == DataMode.SIMULATED
        return predictionStore.all().filter { it.source.startsWith("SIMULATED") == sim }
    }

    private fun todayKey() = Session.zdt(System.currentTimeMillis()).toLocalDate().toString()
    private fun callsToday() = if (budgetPrefs.getString("day", "") == todayKey()) budgetPrefs.getInt("n", 0) else 0
    private fun countCall() { budgetPrefs.edit().putString("day", todayKey()).putInt("n", callsToday() + 1).apply() }

    private fun updateAnalystStatus() {
        val s = _settings.value
        val txt = when {
            s.mode == DataMode.SIMULATED -> "Rules (simulator)"
            !s.geminiEnabled -> "Rules only (Gemini off)"
            s.geminiApiKey.isBlank() -> "Rules only — add a Gemini API key in Setup"
            else -> "Gemini ${s.geminiModel} · ${callsToday()}/${s.geminiDailyBudget} calls today" +
                (if (analystLastOk > 0) " · last ok ${Session.hhmm(analystLastOk)}" else "") +
                (analystLastError?.let { " · ✗ $it" } ?: "") + if (analystBusy) " · reading…" else ""
        }
        _ui.update { it.copy(analystStatus = txt) }
    }

    /** 20 — send events whose articles changed to Gemini (budgeted, rate-limited); rules cover everything meanwhile. */
    private fun maybeAnalyzeEvents(o: EngineOutput) {
        val s = _settings.value
        val now = System.currentTimeMillis()
        val reqs = o.pendingEventAnalysis
        if (!s.geminiActive || reqs.isEmpty() || analystBusy || now < analystNextAllowed || callsToday() >= s.geminiDailyBudget) {
            updateAnalystStatus(); return
        }
        val briefs = engine.eventIntel.activeBriefs(o.timestamp) // read on the engine's thread
        analystBusy = true
        analystNextAllowed = now + s.geminiMinIntervalSec * 1000L
        updateAnalystStatus()
        scope.launch(Dispatchers.IO) {
            try {
                countCall()
                val res = GeminiEventAnalyst(s.geminiApiKey, s.geminiModel).analyze(reqs, briefs, System.currentTimeMillis())
                // stamped when the analysis became AVAILABLE (point-in-time), not when it was requested
                val arrived = System.currentTimeMillis()
                analysesQueue.addAll(res.map { it.copy(analyzedAt = maxOf(it.analyzedAt, arrived)) })
                analystLastOk = System.currentTimeMillis(); analystLastError = null
            } catch (e: GeminiException) {
                analystLastError = e.message
                if (e.retryAfterSec > 0) analystNextAllowed = System.currentTimeMillis() + e.retryAfterSec * 1000L
            } catch (e: Exception) {
                analystLastError = e.message ?: e.javaClass.simpleName
            } finally {
                analystBusy = false
                updateAnalystStatus()
            }
        }
    }

    /** Event memory (EVENT_IDs, lifecycle, expectations, baselines) survives restarts — live modes only. */
    private fun loadEventState() {
        if (_settings.value.mode == DataMode.SIMULATED || !eventStateFile.exists()) return
        runCatching { engine.eventIntel.importState(AppJson.decodeFromString<EventTrackerState>(eventStateFile.readText())) }
    }

    /** [mode] = the data family the CURRENT engine belongs to (on a mode switch, settings already hold the new one). */
    private fun saveEventState(force: Boolean = false, mode: DataMode = _settings.value.mode) {
        if (!force && ++cyclesSinceSave < 5) return
        cyclesSinceSave = 0
        saveV5State(mode)
        if (mode == DataMode.SIMULATED) return
        runCatching { writeAtomic(eventStateFile, AppJson.encodeToString(EventTrackerState.serializer(), engine.eventIntel.exportState())) }
    }

    private fun writeAtomic(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        tmp.renameTo(f)
    }

    /** v5 memory: baselines (live only — the simulator rebuilds its own) and the shadow book of the current data family. */
    private fun loadV5State(mode: DataMode = _settings.value.mode) {
        if (mode != DataMode.SIMULATED && baselinesFile.exists())
            runCatching { engine.baselines.importState(AppJson.decodeFromString<BaselineState>(baselinesFile.readText())) }
        val sf = shadowFile(mode)
        if (sf.exists()) runCatching { engine.shadow.importState(AppJson.decodeFromString<ShadowBook>(sf.readText())) }
    }

    private fun saveV5State(mode: DataMode = _settings.value.mode) {
        runCatching {
            if (mode != DataMode.SIMULATED) writeAtomic(baselinesFile, AppJson.encodeToString(BaselineState.serializer(), engine.baselines.exportState()))
            writeAtomic(shadowFile(mode), AppJson.encodeToString(ShadowBook.serializer(), engine.shadow.exportState()))
        }
    }

    /** Persist event memory, baselines and the shadow book now (service teardown / engine stop). */
    fun persist() { scope.launch(engineThread) { saveEventState(force = true) } }

    /** Clear the shadow book of the current data family (open positions and history). */
    fun resetShadow() = scope.launch(engineThread) {
        engine.shadow.importState(ShadowBook())
        saveV5State()
    }

    /** The latest decision object as pretty JSON (spec keys), for sharing. */
    fun exportDecisionFile(): File? {
        val ds = _ui.value.output?.decisionState ?: return null
        val f = File(app.filesDir, "decision-state.json")
        f.writeText(PrettyJson.encodeToString(com.niftyengine.engine.model.DecisionState.serializer(), ds))
        return f
    }

    /** 19 — refit the probability calibrator from logged outcomes and hand it to the engine. */
    /** Refit requested from the UI: runs on the engine thread, so it never races a cycle or the log writer. */
    fun refitCalibration() { scope.launch(engineThread) { refitNow() } }

    private fun refitNow() {
        val fit = ProbabilityCalibrator.fit(recordsForMode(), _settings.value.minCalibrationSamples)
        calibration = fit
        engine.calibration = fit
        lastFit = System.currentTimeMillis()
        _ui.update { it.copy(calibration = fit.info, optionCalibrationSamples = fit.optionSamples) }
    }

    fun refreshStats() {
        val recs = recordsForMode()
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
        if (old.runInBackground != new.runInBackground && loop?.isActive == true) {
            if (new.runInBackground) EngineService.start(app) else EngineService.stop(app)
        }
        val rebuild = old.mode != new.mode || old.engineConfig() != new.engineConfig() || old.minCalibrationSamples != new.minCalibrationSamples
        if (!rebuild) {
            if (old.kiteAccessToken != new.kiteAccessToken && new.mode == DataMode.LIVE_KITE) refreshNow()
            return
        }
        scope.launch {
            withContext(engineThread) { rebuildEngine(old, new) }
            refreshNow()
        }
    }

    private fun rebuildEngine(old: AppSettings, new: AppSettings) {
        run {
            // Keep the event memory, baselines and shadow book when only thresholds change; switch files when the data family changes.
            val sameFamily = old.mode == new.mode
            val keep = if (!sameFamily) null else engine.eventIntel.exportState()
            val keepBase = if (!sameFamily) null else engine.baselines.exportState()
            val keepShadow = if (!sameFamily) null else engine.shadow.exportState()
            if (!sameFamily) saveEventState(force = true, mode = old.mode)
            engine = NiftyDirectionEngine(new.engineConfig())
            if (keep != null) engine.eventIntel.importState(keep) else if (new.mode != DataMode.SIMULATED) loadEventState()
            if (keepBase != null && keepShadow != null) { engine.baselines.importState(keepBase); engine.shadow.importState(keepShadow) }
            else loadV5State(new.mode)
            if (old.mode != new.mode) { provider = makeProvider(); path.clear(); _ui.update { it.copy(chart = emptyList(), output = null) } }
            refitNow()
            refreshStats()
        }
    }

    fun completeKiteLogin(requestToken: String) = scope.launch {
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
        scope.launch {
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

    @Volatile private var backtestCancel = false
    fun cancelBacktest() { backtestCancel = true }

    /**
     * Kite historical (market-only) backtest over the last [months] months, run on the phone with the Kite session.
     * Results stay separate from the live prediction log; every prediction is exported to CSV for review.
     */
    fun runBacktest(months: Int) {
        if (_ui.value.backtest.running) return
        val s = _settings.value
        if (s.kiteApiKey.isBlank() || s.kiteAccessToken.isBlank() || s.kiteLoginNeeded()) {
            _ui.update { it.copy(backtest = BacktestState(error = "Log in to Kite first (Setup → Login to Kite) — the backtest downloads Kite historical data.")) }
            return
        }
        backtestCancel = false
        _ui.update { it.copy(backtest = BacktestState(running = true, label = "Starting…")) }
        scope.launch(Dispatchers.IO) {
            try {
                val today = Session.zdt(System.currentTimeMillis()).toLocalDate()
                val to = today.minusDays(1)
                val from = to.minusMonths(months.toLong())
                val src = KiteHistoricalSource(KiteApi(s.kiteApiKey, s.kiteAccessToken), File(app.cacheDir, "kite-hist")) { msg ->
                    _ui.update { st -> st.copy(backtest = st.backtest.copy(label = msg)) }
                }
                val run = HistoricalBacktest(s.engineConfig()).run(src, from, to,
                    onProgress = { msg, p -> _ui.update { st -> st.copy(backtest = st.backtest.copy(label = msg, progress = p)) } },
                    cancelled = { backtestCancel })
                val csv = File(app.filesDir, "backtest-$from-$to.csv")
                writeBacktestCsv(csv, run.records)
                File(app.filesDir, "backtest-$from-$to-report.json")
                    .writeText(AppJson.encodeToString(com.niftyengine.engine.engines.BacktestReport.serializer(), run.report))
                _ui.update { it.copy(backtest = BacktestState(running = false, progress = 1f,
                    label = if (backtestCancel) "Cancelled — partial results" else "Done", report = run.report, sourceNotes = src.notes, csv = csv)) }
            } catch (e: Exception) {
                _ui.update { it.copy(backtest = it.backtest.copy(running = false, error = e.message ?: e.javaClass.simpleName)) }
            }
        }
    }

    private fun writeBacktestCsv(f: File, recs: List<PredictionRecord>) {
        f.bufferedWriter().use { w ->
            val drivers = com.niftyengine.engine.model.Driver.values()
            w.write("time,spot,pBull,pBear,pRange,calibrated,calBull,calBear,calRange,regime,confidence,decision,composite," +
                ProbabilityCalibrator.HORIZONS.joinToString(",") { "move${it}m,class${it}m" } + "," + drivers.joinToString(",") { it.name } + "\n")
            for (r in recs) {
                val z = Session.zdt(r.timestamp)
                val cells = mutableListOf("%s %02d:%02d".format(z.toLocalDate(), z.hour, z.minute), "%.2f".format(r.spot),
                    "%.4f".format(r.pBull), "%.4f".format(r.pBear), "%.4f".format(r.pRange), "${r.calibrated}",
                    "%.4f".format(r.calPBull), "%.4f".format(r.calPBear), "%.4f".format(r.calPRange), r.regime, r.confidence, r.decision,
                    "%.4f".format(r.directionalScore))
                ProbabilityCalibrator.HORIZONS.forEach { h ->
                    val o = r.outcomes.firstOrNull { it.minutes == h }
                    cells += o?.let { "%.2f".format(it.move) } ?: ""; cells += o?.realized?.toString() ?: ""
                }
                drivers.forEach { d -> cells += r.driverScores[d.name]?.let { "%.4f".format(it) } ?: "" }
                w.write(cells.joinToString(",") + "\n")
            }
        }
    }

    /**
     * Point-in-time validation across ALL recorded sessions (oldest first): each session is predicted with a
     * calibration fitted only on the sessions before it.
     */
    fun runWalkForward(mode: ReplayMode) {
        if (_ui.value.replay.running) return
        scope.launch {
            _ui.update { it.copy(replay = ReplayState(running = true, label = "Walk-forward · all recorded sessions")) }
            try {
                val wf = withContext(Dispatchers.Default) {
                    val files = recorder.sessions().sortedBy { it.name }
                    if (files.size < 2) throw IllegalStateException("Need at least 2 recorded sessions (have ${files.size})")
                    val sessions = files.map { recorder.load(it) }.filter { it.size >= 20 }
                    HistoricalReplayEngine(_settings.value.engineConfig()).runWalkForward(sessions, mode, recorder.newsArchive(),
                        _settings.value.minCalibrationSamples) { done, total ->
                        _ui.update { it.copy(replay = it.replay.copy(progress = done.toFloat() / total)) }
                    }
                }
                _ui.update { it.copy(replay = it.replay.copy(running = false, progress = 1f, result = wf.result,
                    label = "Walk-forward · ${wf.sessions} sessions", walkForwardCalibration = wf.finalCalibration.info)) }
            } catch (e: Exception) {
                _ui.update { it.copy(replay = it.replay.copy(running = false, error = e.message)) }
            }
        }
    }
}

/**
 * Live modes poll at the configured rate from 08:45 (GIFT/pre-open) to 15:45 IST on weekdays and every
 * 5 minutes otherwise, so a background run overnight or at weekends costs almost no battery or data.
 */
internal fun nextDelayMs(s: AppSettings, now: Long): Long {
    if (s.mode == DataMode.SIMULATED) return s.simSecondsPerMinute * 1000L
    return if (inActiveWindow(now)) s.refreshSeconds * 1000L else IDLE_POLL_MS
}

internal const val IDLE_POLL_MS = 5 * 60_000L

internal fun inActiveWindow(now: Long): Boolean {
    val z = Session.zdt(now)
    if (z.dayOfWeek == java.time.DayOfWeek.SATURDAY || z.dayOfWeek == java.time.DayOfWeek.SUNDAY) return false
    val m = z.hour * 60 + z.minute
    return m in (8 * 60 + 45)..(15 * 60 + 45)
}

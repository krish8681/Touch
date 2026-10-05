package com.niftyengine.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.niftyengine.app.store.AppSettings
import com.niftyengine.app.store.DataMode
import com.niftyengine.engine.core.Session
import com.niftyengine.engine.engines.PerformanceStats
import com.niftyengine.engine.engines.PredictionRecord
import com.niftyengine.engine.engines.ReplayMode
import com.niftyengine.engine.model.ConfidenceLevel
import com.niftyengine.engine.model.HorizonId
import java.io.File

private fun pct(x: Double) = if (x.isNaN()) "–" else "%.0f%%".format(x * 100)

@Composable
private fun BucketTable(buckets: List<PerformanceStats.Bucket>, predLabel: String = "pred", realLabel: String = "real") {
    TableHeader("Bucket" to 1f, "N" to 0.6f, predLabel to 0.8f, realLabel to 0.8f, "gap" to 0.7f)
    buckets.forEach { b ->
        val gap = b.hitRate - b.avgPredicted
        TableRow(Triple(b.label, 1f, C.white), Triple("${b.n}", 0.6f, C.text), Triple(pct(b.avgPredicted), 0.8f, C.text),
            Triple(pct(b.hitRate), 0.8f, C.text),
            Triple(if (gap.isNaN()) "–" else "%+.0f".format(gap * 100), 0.7f,
                if (gap.isNaN() || b.n < 20) C.dim else if (kotlin.math.abs(gap) <= 0.05) C.green else if (kotlin.math.abs(gap) <= 0.10) C.amber else C.red))
    }
}

@Composable
fun StatsCard(title: String, summaries: List<PerformanceStats.Summary>, strategies: PerformanceStats.StrategySummary? = null) {
    var h by remember { mutableStateOf(HorizonId.M30) }
    Card(title) {
        if (summaries.all { it.n == 0 }) {
            Label("No evaluated predictions yet. Outcomes are attached when each horizon's target passes (30 min, 1 h, 3 h, the close, the weekly and monthly expiry).",
                color = C.dim, size = 11.sp, mono = false)
            return@Card
        }
        TableHeader("Horizon" to 1.1f, "N" to 0.6f, "Acc" to 0.6f, "Dir hit" to 0.7f, "In range" to 0.8f, "Brier" to 0.7f)
        summaries.forEach { s ->
            TableRow(
                Triple(s.horizon.short, 1.1f, C.white), Triple("${s.n}", 0.6f, C.text), Triple(pct(s.accuracy), 0.6f, C.text),
                Triple(pct(s.directionalHitRate), 0.7f, C.text),
                Triple(pct(s.rangeHitRate), 0.8f, if (s.rangeHitRate.isNaN()) C.dim else if (kotlin.math.abs(s.rangeHitRate - 0.68) < 0.08) C.green else C.amber),
                Triple(if (s.brier.isNaN()) "–" else "%.3f".format(s.brier), 0.7f, C.text),
            )
        }
        Spacer(Modifier.height(8.dp))
        HorizonPicker(h, summaries.map { it.horizon }) { h = it }
        val sel = summaries.firstOrNull { it.horizon == h } ?: summaries.first()
        Label("RELIABILITY · ${sel.horizon.label} · raw model score (top class) vs realised hit rate", color = C.dim, size = 10.sp)
        BucketTable(sel.buckets)
        if (sel.calibratedBuckets.any { it.n > 0 }) {
            Spacer(Modifier.height(6.dp))
            Label("AFTER CALIBRATION · ${sel.horizon.label} (predictions calibrated when made)", color = C.dim, size = 10.sp)
            BucketTable(sel.calibratedBuckets)
        }
        val fh = sel.factorHitRates.filterValues { !it.isNaN() }
        if (fh.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Label("FACTOR SIGN HIT RATE (${sel.horizon.short}, |direction| > 20)", color = C.dim, size = 10.sp)
            fh.forEach { (k, v) -> KV(runCatching { com.niftyengine.engine.model.Factor.valueOf(k).label }.getOrDefault(k), pct(v),
                if (v > 0.55) C.green else if (v < 0.45) C.red else C.amber) }
        }
        if (strategies != null && strategies.n > 0) {
            Spacer(Modifier.height(6.dp))
            Label("RECOMMENDED STRUCTURES · realised P&L (net, per unit)", color = C.dim, size = 10.sp)
            KV("Evaluated / win rate / avg P&L", "${strategies.n} / ${pct(strategies.winRate)} / %+.1f".format(strategies.avgPnl))
            strategies.byKind.forEach { (k, v) -> KV(k, "n=${v.first} · win ${pct(v.second)}") }
            BucketTable(strategies.buckets, "P(prof)", "won")
        }
        Label("Goal: 'real' ≈ 'pred' in every bucket (gap within ±5) and ≈68 % of outcomes inside the predicted range. Brier: 0 = perfect, ≈0.667 = uninformed 3-way guess. Gaps with n < 20 are greyed.",
            color = C.dim, size = 9.sp, mono = false)
    }
}

@Composable
fun CalibrationCard(info: com.niftyengine.engine.model.CalibrationInfo, strategySamples: Int, onRefit: (() -> Unit)?) {
    Card("Probability calibration", trailing = {
        Chip(if (info.calibrated) "CALIBRATED" else "UNCALIBRATED", if (info.calibrated) C.green else C.amber)
    }) {
        Label(info.note, color = C.text, size = 11.sp, mono = false)
        Spacer(Modifier.height(4.dp))
        TableHeader("Horizon" to 1f, "Outcomes" to 1.1f, "Brier raw" to 0.9f, "Brier cal" to 0.9f)
        HorizonId.values().forEach { hz ->
            val raw = info.holdoutBrierRaw[hz.name]; val cal = info.holdoutBrierCalibrated[hz.name]
            val n = info.samples[hz.name] ?: 0
            TableRow(Triple(hz.short, 1f, C.white), Triple("$n / ${info.minSamples}", 1.1f, if (n >= info.minSamples) C.green else C.amber),
                Triple(raw?.let { "%.3f".format(it) } ?: "–", 0.9f, C.text),
                Triple(cal?.let { "%.3f".format(it) } ?: "–", 0.9f, if (raw != null && cal != null && cal < raw) C.green else C.text))
        }
        KV("Strategy-outcome samples", "$strategySamples / 60")
        Label("Isotonic regression per horizon and class, refitted from the log every 10 min. Expiry horizons use one record per hour. " +
            "Brier columns are walk-forward: fitted on the oldest 70 %, scored on the newest 30 %.", color = C.dim, size = 9.sp, mono = false)
        if (onRefit != null) TextButtonLike("Refit now", onRefit)
    }
}

@Composable
private fun TextButtonLike(label: String, onClick: () -> Unit) =
    Text(label, color = C.blue, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp).clickable { onClick() })

@Composable
private fun AuditDetail(r: PredictionRecord) {
    Column(Modifier.fillMaxWidth().padding(start = 8.dp, bottom = 8.dp)) {
        KV("Prediction ID", r.id)
        KV("Engine / source", "${r.engineVersion} · ${r.source}")
        KV("Regime", r.regime + (if (r.secondaryRegime.isNotBlank()) " (+${r.secondaryRegime})" else "") +
            " · expiry ${r.weeklyExpiryRegime.ifBlank { "–" }} / ${r.monthlyExpiryRegime.ifBlank { "–" }}")
        KV("Master", "${r.masterDirection} %.0f%% · alignment ${r.alignment}/3 · ${r.masterConfidence}".format(r.masterProbability * 100))
        KV("Event risk", r.eventRisk.entries.joinToString { "${it.key} ${it.value}" })
        r.horizons.forEach { h ->
            Label("  %-9s score %+5.1f · B/N/b %.0f/%.0f/%.0f%s · σ %.0f · range %,.0f–%,.0f · %s%s".format(h.id.short, h.score,
                h.pBull * 100, h.pNeutral * 100, h.pBear * 100, if (h.calibrated) "ᶜ" else "", h.sigma, h.rangeLow, h.rangeHigh, h.confidence,
                h.outcome?.let { o -> " → %+.0f (%s%s)".format(o.move, when (o.realized) { 1 -> "bull"; -1 -> "bear"; else -> "neutral" }, if (o.insideRange) ", in range" else "") } ?: ""),
                color = C.text, size = 10.sp)
        }
        r.strategy?.let { s ->
            KV("Structure", "${s.kind.label} · ${s.legs.joinToString(" ") { "${if (it.action.name == "BUY") "+" else "−"}${it.strike.toInt()}${it.type}@%.1f".format(it.price) }}")
            KV("Structure economics", "net %+.1f · cost %.2f · P(profit) %.0f%% · EV %+.1f · max loss %.1f".format(s.netPremium, s.costPerUnit, s.pProfit * 100, s.expectedPnl, s.maxLoss) +
                (s.win?.let { " → realised %+.1f (${if (it) "win" else "loss"})".format(s.realizedPnl) } ?: ""))
        }
        KV("Data quality", if (r.dataQuality.isNaN()) "–" else "%.0f%%".format(r.dataQuality * 100))
        r.circuitBreaker.forEach { Label("  breaker: $it", color = C.red, size = 10.sp) }
        if (r.failedChecks.isNotEmpty()) Label("Failed: " + r.failedChecks.joinToString("; "), color = C.amber, size = 10.sp)
        if (r.feedStatus.isNotEmpty()) Label("Feeds: " + r.feedStatus.entries.joinToString("; ") { "${it.key} ${it.value.substringBefore(" ·")}" }, color = C.dim, size = 9.sp)
        r.events.forEach { e ->
            Label("  event ${e.id} [${e.stage}/${e.source}] sev %.2f surprise %+.2f unpriced %.0f%% → %+.3f · ${e.title.take(60)}"
                .format(e.severity, e.surprise, e.unpriced * 100, e.effectiveImpact), color = C.text, size = 9.sp)
        }
        if (r.config.isNotEmpty()) Label("Config: " + r.config.entries.joinToString(" ") { "${it.key}=${it.value}" }, color = C.dim, size = 9.sp)
    }
}

@Composable
fun LogScreen(ui: UiState, vm: EngineController, onExport: () -> Unit, onShare: (File) -> Unit = {}) {
    BacktestCard(ui.backtest, vm, onShare)
    CalibrationCard(ui.calibration, ui.calibration.strategySamples) { vm.refitCalibration() }
    StatsCard("Prediction performance (this data mode)", ui.stats, ui.strategyStats)
    ReplayCard(ui, vm)
    var open by remember { mutableStateOf<String?>(null) }
    Card("Prediction log (${ui.records.size} recent) · tap a row for the audit trail", trailing = {
        Text("Export", color = C.blue, fontSize = 12.sp, modifier = Modifier.padding(end = 12.dp).clickable { onExport() })
        Text("Clear", color = C.red, fontSize = 12.sp, modifier = Modifier.clickable { vm.clearLog() })
    }) {
        TableHeader("Time" to 0.7f, "Spot" to 0.9f, "1H" to 0.7f, "WK" to 0.7f, "MO" to 0.7f, "Dec" to 0.8f, "1H out" to 0.8f)
        ui.records.take(60).forEach { r ->
            val h1 = r.horizon(HorizonId.M60); val wk = r.horizon(HorizonId.WEEKLY); val mo = r.horizon(HorizonId.MONTHLY)
            fun cell(h: com.niftyengine.engine.engines.HorizonRecord?) = h?.let { "${it.direction.arrow}%.0f".format(it.probability * 100) + if (it.calibrated) "ᶜ" else "" } ?: "–"
            val hit = h1?.outcome?.let { it.realized == h1.predictedClass }
            Column(Modifier.fillMaxWidth().clickable { open = if (open == r.id) null else r.id }) {
                TableRow(
                    Triple(Session.hhmm(r.timestamp), 0.7f, C.text),
                    Triple("%.0f".format(r.spot), 0.9f, C.white),
                    Triple(cell(h1), 0.7f, C.text), Triple(cell(wk), 0.7f, C.text), Triple(cell(mo), 0.7f, C.text),
                    Triple(r.decision.replace("PAPER_TRADE", "PAPER").replace("DATA_ERROR", "DATA✗").take(6), 0.8f,
                        when (r.decision) { "TRADE" -> C.green; "PAPER_TRADE" -> C.blue; "DATA_ERROR" -> C.red; else -> C.dim }),
                    Triple(h1?.outcome?.let { "%+.0f".format(it.move) } ?: "…", 0.8f, when (hit) { true -> C.green; false -> C.red; null -> C.dim }),
                )
                if (open == r.id) AuditDetail(r)
            }
        }
    }
}

@Composable
fun ReplayCard(ui: UiState, vm: EngineController) {
    var mode by remember { mutableStateOf(ReplayMode.FULL_INFORMATION) }
    val rp = ui.replay
    Card("Historical replay") {
        Label("Mode A replays market data only. Mode B adds news published by each timestamp. The engine never sees bars, news or FII prints from after the replayed instant.",
            color = C.dim, size = 10.sp, mono = false)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReplayMode.values().forEach { m ->
                val sel = m == mode
                OutlinedButton(onClick = { mode = m }, colors = ButtonDefaults.outlinedButtonColors(containerColor = if (sel) C.s2 else C.s1)) {
                    Text(if (m == ReplayMode.MARKET_ONLY) "A · Market" else "B · Full info", color = if (sel) C.green else C.dim, fontSize = 11.sp)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Button(onClick = { vm.runReplay(null, mode) }, enabled = !rp.running, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = C.blue)) { Text("Replay a simulated session", fontSize = 12.sp) }
        Button(onClick = { vm.runWalkForward(mode) }, enabled = !rp.running && ui.sessions.size >= 2, modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            colors = ButtonDefaults.buttonColors(containerColor = C.violet)) {
            Text("Walk-forward validate all ${ui.sessions.size} recorded sessions", fontSize = 12.sp)
        }
        Label("Walk-forward: each session is predicted with a calibration fitted only on earlier sessions — the honest test of whether 70 % means 70 %.",
            color = C.dim, size = 10.sp, mono = false)
        if (ui.sessions.isEmpty()) Label("Recorded live sessions appear here (Setup → record live sessions).", color = C.dim, size = 10.sp)
        ui.sessions.take(10).forEach { f: File ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Label(f.name.removePrefix("session-").removeSuffix(".jsonl") + " · %.1f MB".format(f.length() / 1e6), color = C.text, size = 11.sp, modifier = Modifier.weight(1f))
                Text("Replay", color = C.green, fontSize = 12.sp, modifier = Modifier.padding(end = 12.dp).clickable(enabled = !rp.running) { vm.runReplay(f, mode) })
                Text("Delete", color = C.red, fontSize = 12.sp, modifier = Modifier.clickable(enabled = !rp.running) { vm.deleteSession(f) })
            }
        }
        if (rp.running || rp.progress > 0f) {
            Spacer(Modifier.height(8.dp))
            Label("${rp.label} ${if (rp.running) "running…" else "done"}", color = C.text, size = 11.sp)
            LinearProgressIndicator(progress = { rp.progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), color = C.green, trackColor = C.s2)
        }
        rp.error?.let { Label("Replay error: $it", color = C.red, size = 11.sp) }
    }
    rp.walkForwardCalibration?.let { CalibrationCard(it, it.strategySamples, null) }
    rp.result?.let { r ->
        StatsCard("Replay result · ${r.mode.name.lowercase().replace('_', ' ')} · ${r.outputs} snapshots, ${r.records.size} predictions", r.summaries)
    }
}

@Composable
private fun Field(label: String, value: String, keyboard: KeyboardType = KeyboardType.Decimal, secret: Boolean = false, singleLine: Boolean = true, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label, fontSize = 11.sp) }, singleLine = singleLine,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = C.white, unfocusedTextColor = C.text, focusedBorderColor = C.green, unfocusedBorderColor = C.b1),
    )
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Label(label, color = C.text, size = 12.sp, modifier = Modifier.weight(1f), mono = false)
        Switch(checked = value, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = C.green))
    }
}

@Composable
private fun Pair2(a: @Composable () -> Unit, b: @Composable () -> Unit) {
    Row { Column(Modifier.weight(1f).padding(end = 4.dp)) { a() }; Column(Modifier.weight(1f)) { b() } }
}

private fun num(s: String, def: Double) = s.trim().toDoubleOrNull() ?: def
private fun numOrNaN(s: String) = s.trim().toDoubleOrNull() ?: Double.NaN
private fun show(x: Double) = if (x.isNaN()) "" else x.toString()

@Composable
fun SettingsScreen(current: AppSettings, onSave: (AppSettings) -> Unit, onKiteLogin: (AppSettings) -> Unit) {
    var mode by remember(current) { mutableStateOf(current.mode) }
    var refresh by remember(current) { mutableStateOf(current.refreshSeconds.toString()) }
    var simSpeed by remember(current) { mutableStateOf(current.simSecondsPerMinute.toString()) }
    var intraH by remember(current) { mutableStateOf(current.intradayHorizon) }
    var minProb by remember(current) { mutableStateOf((current.minProbability * 100).toInt().toString()) }
    var minConf by remember(current) { mutableStateOf(current.minConfidence) }
    var minAlign by remember(current) { mutableStateOf(current.minAlignment.toString()) }
    var maxSpread by remember(current) { mutableStateOf(current.maxSpreadPct.toString()) }
    var minOi by remember(current) { mutableStateOf(current.minOi.toLong().toString()) }
    var minVol by remember(current) { mutableStateOf(current.minVolume.toLong().toString()) }
    var notify by remember(current) { mutableStateOf(current.notifyOnTrade) }
    var record by remember(current) { mutableStateOf(current.recordSessions) }
    var screenOn by remember(current) { mutableStateOf(current.keepScreenOn) }
    var background by remember(current) { mutableStateOf(current.runInBackground) }
    var kKey by remember(current) { mutableStateOf(current.kiteApiKey) }
    var kSecret by remember(current) { mutableStateOf(current.kiteApiSecret) }
    var kToken by remember(current) { mutableStateOf(current.kiteAccessToken) }
    val m = current.macro
    var repo by remember(current) { mutableStateOf(show(m.repoRate)) }
    var policy by remember(current) { mutableStateOf(show(m.lastPolicyChangeBps)) }
    var policyExp by remember(current) { mutableStateOf(show(m.policyExpectedChangeBps)) }
    var cpi by remember(current) { mutableStateOf(show(m.cpiYoY)) }
    var cpiPrev by remember(current) { mutableStateOf(show(m.cpiPrevYoY)) }
    var cpiCons by remember(current) { mutableStateOf(show(m.cpiConsensus)) }
    var gdp by remember(current) { mutableStateOf(show(m.gdpGrowth)) }
    var gdpPrev by remember(current) { mutableStateOf(show(m.gdpPrevGrowth)) }
    var gdpCons by remember(current) { mutableStateOf(show(m.gdpConsensus)) }
    var pmi by remember(current) { mutableStateOf(show(m.pmiManufacturing)) }
    var iip by remember(current) { mutableStateOf(show(m.iipYoY)) }
    var wpi by remember(current) { mutableStateOf(show(m.wpiYoY)) }
    var credit by remember(current) { mutableStateOf(show(m.creditGrowth)) }
    var liq by remember(current) { mutableStateOf(show(m.liquidityCr)) }
    var fiscal by remember(current) { mutableStateOf(show(m.fiscalStance)) }
    val e = current.earnings
    var fwd by remember(current) { mutableStateOf(show(e.forwardEps)) }
    var fwdPrev by remember(current) { mutableStateOf(show(e.forwardEpsPrev)) }
    var epsExp by remember(current) { mutableStateOf(show(e.epsGrowthExpected)) }
    var epsAct by remember(current) { mutableStateOf(show(e.epsGrowthActual)) }
    var beat by remember(current) { mutableStateOf(if (e.beatRatio.isNaN()) "" else "%.0f".format(e.beatRatio * 100)) }
    var eDate by remember(current) { mutableStateOf(current.earningsDate) }
    var calendar by remember(current) { mutableStateOf(current.calendarText) }
    val dates = remember(current) { androidx.compose.runtime.mutableStateMapOf<String, String>().apply { putAll(current.macroDates) } }
    var reqCal by remember(current) { mutableStateOf(current.requireCalibration) }
    var minCalN by remember(current) { mutableStateOf(current.minCalibrationSamples.toString()) }
    var confirm by remember(current) { mutableStateOf(current.confirmCycles.toString()) }
    var minOptP by remember(current) { mutableStateOf((current.minOptionProfitProb * 100).toInt().toString()) }
    var minRoR by remember(current) { mutableStateOf((current.minReturnOnRisk * 100).toInt().toString()) }
    var minRR by remember(current) { mutableStateOf(current.minRiskReward.toString()) }
    var eventBump by remember(current) { mutableStateOf((current.eventThresholdBump * 100).toInt().toString()) }
    var minDq by remember(current) { mutableStateOf((current.minDataQuality * 100).toInt().toString()) }
    var capital by remember(current) { mutableStateOf(current.capital.toLong().toString()) }
    var riskPct by remember(current) { mutableStateOf(current.riskPerTradePct.toString()) }
    var maxLots by remember(current) { mutableStateOf(current.maxLots.toString()) }
    var brok by remember(current) { mutableStateOf(current.brokeragePerOrder.toString()) }
    var stt by remember(current) { mutableStateOf(current.sttSellPct.toString()) }
    var slip by remember(current) { mutableStateOf(current.slippageTicks.toString()) }
    var lotSize by remember(current) { mutableStateOf(current.lotSize.toString()) }
    var lots by remember(current) { mutableStateOf(current.lots.toString()) }
    var peLow by remember(current) { mutableStateOf(current.fairPeLow.toString()) }
    var peHigh by remember(current) { mutableStateOf(current.fairPeHigh.toString()) }
    var in10y by remember(current) { mutableStateOf(show(current.india10y)) }
    var gemOn by remember(current) { mutableStateOf(current.geminiEnabled) }
    var gemKey by remember(current) { mutableStateOf(current.geminiApiKey) }
    var gemModel by remember(current) { mutableStateOf(current.geminiModel) }
    var gemBudget by remember(current) { mutableStateOf(current.geminiDailyBudget.toString()) }
    var gemInterval by remember(current) { mutableStateOf(current.geminiMinIntervalSec.toString()) }

    fun build() = current.copy(
        mode = mode, refreshSeconds = num(refresh, 30.0).toInt().coerceIn(5, 600),
        simSecondsPerMinute = num(simSpeed, 2.0).toInt().coerceIn(1, 60),
        intradayHorizon = intraH,
        minProbability = (num(minProb, 55.0) / 100).coerceIn(0.34, 0.95), minConfidence = minConf,
        minAlignment = num(minAlign, 2.0).toInt().coerceIn(1, 3),
        maxSpreadPct = num(maxSpread, 3.0), minOi = num(minOi, 2000.0),
        minVolume = num(minVol, 500.0), notifyOnTrade = notify, recordSessions = record, keepScreenOn = screenOn, runInBackground = background,
        kiteApiKey = kKey.trim(), kiteApiSecret = kSecret.trim(), kiteAccessToken = kToken.trim(),
        macro = m.copy(repoRate = numOrNaN(repo), lastPolicyChangeBps = num(policy, 0.0), policyExpectedChangeBps = numOrNaN(policyExp),
            cpiYoY = numOrNaN(cpi), cpiPrevYoY = numOrNaN(cpiPrev), cpiConsensus = numOrNaN(cpiCons),
            gdpGrowth = numOrNaN(gdp), gdpPrevGrowth = numOrNaN(gdpPrev), gdpConsensus = numOrNaN(gdpCons),
            pmiManufacturing = numOrNaN(pmi), iipYoY = numOrNaN(iip), wpiYoY = numOrNaN(wpi),
            creditGrowth = numOrNaN(credit), liquidityCr = numOrNaN(liq), fiscalStance = numOrNaN(fiscal).let { if (it.isNaN()) it else it.coerceIn(-1.0, 1.0) }),
        earnings = e.copy(forwardEps = numOrNaN(fwd), forwardEpsPrev = numOrNaN(fwdPrev), epsGrowthExpected = numOrNaN(epsExp),
            epsGrowthActual = numOrNaN(epsAct), beatRatio = numOrNaN(beat).let { if (it.isNaN()) it else (it / 100).coerceIn(0.0, 1.0) }),
        earningsDate = eDate.trim(), calendarText = calendar,
        macroDates = dates.filterValues { it.isNotBlank() },
        requireCalibration = reqCal, minCalibrationSamples = num(minCalN, 150.0).toInt().coerceIn(30, 5000),
        confirmCycles = num(confirm, 2.0).toInt().coerceIn(1, 10),
        minOptionProfitProb = (num(minOptP, 45.0) / 100).coerceIn(0.0, 0.95),
        minReturnOnRisk = (num(minRoR, 5.0) / 100).coerceIn(-1.0, 2.0),
        minRiskReward = num(minRR, 0.25).coerceIn(0.0, 5.0),
        eventThresholdBump = (num(eventBump, 5.0) / 100).coerceIn(0.0, 0.3),
        minDataQuality = (num(minDq, 70.0) / 100).coerceIn(0.0, 1.0),
        capital = num(capital, 200_000.0).coerceAtLeast(1000.0), riskPerTradePct = num(riskPct, 1.0).coerceIn(0.05, 20.0),
        maxLots = num(maxLots, 10.0).toInt().coerceIn(1, 500),
        brokeragePerOrder = num(brok, 20.0), sttSellPct = num(stt, 0.1), slippageTicks = num(slip, 1.0),
        lotSize = num(lotSize, 65.0).toInt().coerceAtLeast(1), lots = num(lots, 1.0).toInt().coerceAtLeast(1),
        fairPeLow = num(peLow, 19.0), fairPeHigh = num(peHigh, 23.0).coerceAtLeast(num(peLow, 19.0) + 0.5), india10y = numOrNaN(in10y),
        geminiEnabled = gemOn, geminiApiKey = gemKey.trim(), geminiModel = gemModel.trim().ifBlank { "gemini-2.5-flash" },
        geminiDailyBudget = num(gemBudget, 200.0).toInt().coerceIn(0, 5000),
        geminiMinIntervalSec = num(gemInterval, 60.0).toInt().coerceIn(10, 3600),
    )

    Card("Data source") {
        DataMode.values().forEach { dm ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.RadioButton(selected = mode == dm, onClick = { mode = dm })
                Label(dm.label, color = if (mode == dm) C.white else C.text, size = 12.sp, mono = false)
            }
        }
        Label("Simulator data is synthetic and for testing the app only. Live NSE/Yahoo endpoints are public website feeds that may be delayed, rate-limited or change without notice.",
            color = C.dim, size = 10.sp, mono = false)
        Spacer(Modifier.height(6.dp))
        Field("Live refresh (seconds)", refresh, KeyboardType.Number) { refresh = it }
        Field("Simulator: seconds per simulated minute", simSpeed, KeyboardType.Number) { simSpeed = it }
    }
    Card("Kite Connect") {
        Label("1. In developers.kite.trade → your app, set any Redirect URL (e.g. https://127.0.0.1/kite) — the app intercepts it.\n" +
            "2. Enter API key + secret here, tap Login to Kite, sign in. Tokens expire daily (~6 AM), so log in once each trading day.\n" +
            "3. With Kite active: quotes, the weekly and monthly option chains, futures OI history and 1-min/daily candles come from Kite; NSE adds free-float weights, ΔOI, FII/DII cash, FII participant OI and P/E; Yahoo/RSS add global markets and news.\n" +
            "The API secret is stored only in this app's private storage on this phone.",
            color = C.dim, size = 10.sp, mono = false)
        Spacer(Modifier.height(6.dp))
        Field("API key", kKey, KeyboardType.Text) { kKey = it }
        Field("API secret", kSecret, KeyboardType.Password, secret = true) { kSecret = it }
        Field("Access token (filled by login)", kToken, KeyboardType.Text, secret = true) { kToken = it }
        if (current.kiteTokenDate.isNotBlank()) Label("Token issued: ${current.kiteTokenDate} (expires daily)", color = C.dim, size = 10.sp)
        Button(onClick = { onKiteLogin(build()) }, enabled = kKey.isNotBlank() && kSecret.isNotBlank(), modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = C.blue)) { Text("Login to Kite (switches to Kite mode)", fontSize = 12.sp) }
    }
    Card("Risk engine (prediction ≠ trade)") {
        Label("The risk engine sizes and filters the structure the strategy engine picked. Only defined-risk structures are ever generated — never naked option selling.",
            color = C.dim, size = 10.sp, mono = false)
        Pair2({ Field("Capital ₹", capital, KeyboardType.Number) { capital = it } }, { Field("Max loss per trade %", riskPct) { riskPct = it } })
        Pair2({ Field("Max lots", maxLots, KeyboardType.Number) { maxLots = it } }, { Field("Min alignment (of 3)", minAlign, KeyboardType.Number) { minAlign = it } })
        Pair2({ Field("Min direction prob %", minProb, KeyboardType.Number) { minProb = it } }, { Field("Min P(profit) net %", minOptP, KeyboardType.Number) { minOptP = it } })
        Pair2({ Field("Min EV / max loss %", minRoR, KeyboardType.Number) { minRoR = it } }, { Field("Min reward:risk (credit)", minRR) { minRR = it } })
        Label("Min horizon confidence", color = C.dim, size = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ConfidenceLevel.values().forEach { c ->
                OutlinedButton(onClick = { minConf = c }, colors = ButtonDefaults.outlinedButtonColors(containerColor = if (c == minConf) C.s2 else C.s1)) {
                    Text(c.name, color = if (c == minConf) C.green else C.dim, fontSize = 11.sp)
                }
            }
        }
        Label("Intraday trades exit at", color = C.dim, size = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HorizonId.values().filter { it.intraday }.forEach { h ->
                OutlinedButton(onClick = { intraH = h }, colors = ButtonDefaults.outlinedButtonColors(containerColor = if (h == intraH) C.s2 else C.s1)) {
                    Text(h.short.replace(" ", " "), color = if (h == intraH) C.green else C.dim, fontSize = 10.sp)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Field("Max option bid/ask spread (%)", maxSpread) { maxSpread = it }
        Pair2({ Field("Min option OI", minOi, KeyboardType.Number) { minOi = it } }, { Field("Min option volume", minVol, KeyboardType.Number) { minVol = it } })
        Field("HIGH event risk: raise probability threshold by (pts)", eventBump, KeyboardType.Number) { eventBump = it }
        Pair2({ Field("Min data quality (%)", minDq, KeyboardType.Number) { minDq = it } }, { Field("Cycles before TRADE", confirm, KeyboardType.Number) { confirm = it } })
    }
    Card("Calibration") {
        Toggle("Require calibrated probabilities for TRADE (else PAPER TRADE)", reqCal) { reqCal = it }
        Field("Min outcomes per horizon before calibrating", minCalN, KeyboardType.Number) { minCalN = it }
        Label("Predictions are logged every 5 min during market hours: ≈2 sessions fill the intraday horizons; weekly/monthly expiry horizons need weeks of outcomes (one per hour is used).",
            color = C.dim, size = 10.sp, mono = false)
    }
    Card("Transaction costs (round trip, per leg)") {
        Pair2({ Field("Brokerage ₹/order", brok) { brok = it } }, { Field("STT % (sell)", stt) { stt = it } })
        Pair2({ Field("Lot size", lotSize, KeyboardType.Number) { lotSize = it } }, { Field("Lots (cost basis)", lots, KeyboardType.Number) { lots = it } })
        Field("Slippage (ticks per side)", slip) { slip = it }
        Label("Also applied: NSE txn 0.03503%, SEBI ₹10/cr, GST 18%, stamp 0.003% (buy). Check your broker's current charge sheet — statutory rates change.",
            color = C.dim, size = 10.sp, mono = false)
    }
    Card("Event calendar (event risk, next 30 days)") {
        Label("Event risk lowers confidence; it never reverses a prediction. Expiries, 2026 FOMC dates and estimated India CPI / GDP dates are built in — add RBI MPC dates, Budget, major NIFTY results, elections here.",
            color = C.dim, size = 10.sp, mono = false)
        Field("Calendar (one event per line)", calendar, KeyboardType.Text, singleLine = false) { calendar = it }
        Label("Parsed: ${com.niftyengine.app.store.parseCalendar(calendar).size} event(s)", color = C.dim, size = 10.sp)
    }
    Card("Expectations — RBI / inflation / growth (H2, H3)") {
        Label("The expectation engine scores the SURPRISE (actual vs what was expected), not whether the news sounds good. Leave blank if unknown — a missing factor is removed, never counted as neutral.",
            color = C.dim, size = 10.sp, mono = false)
        Pair2({ Field("Repo rate %", repo) { repo = it } }, { Field("Last policy Δ bps (−25 = cut)", policy) { policy = it } })
        Field("Expected policy Δ bps before the decision", policyExp) { policyExp = it }
        Pair2({ Field("CPI YoY %", cpi) { cpi = it } }, { Field("CPI prev %", cpiPrev) { cpiPrev = it } })
        Pair2({ Field("CPI consensus %", cpiCons) { cpiCons = it } }, { Field("WPI YoY %", wpi) { wpi = it } })
        Pair2({ Field("GDP growth %", gdp) { gdp = it } }, { Field("GDP prev %", gdpPrev) { gdpPrev = it } })
        Pair2({ Field("GDP consensus %", gdpCons) { gdpCons = it } }, { Field("Mfg PMI", pmi) { pmi = it } })
        Pair2({ Field("IIP YoY %", iip) { iip = it } }, { Field("Credit growth %", credit) { credit = it } })
        Pair2({ Field("System liquidity ₹ cr (+surplus)", liq) { liq = it } }, { Field("Fiscal stance −1…+1", fiscal) { fiscal = it } })
        Label("Release dates (yyyy-mm-dd) set freshness: older values fade, undated ones count as degraded.", color = C.dim, size = 10.sp, mono = false)
        com.niftyengine.app.store.MACRO_DATE_KEYS.chunked(2).forEach { pair ->
            Row {
                pair.forEachIndexed { i, k ->
                    Column(Modifier.weight(1f).padding(end = if (i == 0) 4.dp else 0.dp)) {
                        Field("$k date", dates[k] ?: "", KeyboardType.Text) { dates[k] = it }
                    }
                }
            }
        }
    }
    Card("Earnings / EPS (H2 12 %, H3 20 %) and valuation (H3 10 %)") {
        Pair2({ Field("NIFTY fwd EPS now", fwd) { fwd = it } }, { Field("Fwd EPS 1 month ago", fwdPrev) { fwdPrev = it } })
        Pair2({ Field("Qtr EPS growth expected %", epsExp) { epsExp = it } }, { Field("Qtr EPS growth actual %", epsAct) { epsAct = it } })
        Pair2({ Field("Beat ratio %", beat, KeyboardType.Number) { beat = it } }, { Field("As of (yyyy-mm-dd)", eDate, KeyboardType.Text) { eDate = it } })
        Pair2({ Field("Fair P/E low", peLow) { peLow = it } }, { Field("Fair P/E high", peHigh) { peHigh = it } })
        Field("India 10Y yield % (optional, for earnings-yield gap)", in10y) { in10y = it }
        Label("NIFTY P/E comes live from NSE. Constituent results news also feeds the earnings factor through the expectation engine.",
            color = C.dim, size = 10.sp, mono = false)
    }
    Card("Event intelligence (Gemini)") {
        Label("Gemini reads important news and describes each event: stage, expectation vs actual, surprise, severity, sectors, channels, duration. " +
            "It never produces buy/sell or call/put signals; the engine checks its reading against the market's reaction. Without a key the rule engine is used.",
            color = C.dim, size = 10.sp, mono = false)
        Toggle("Use Gemini for event understanding", gemOn) { gemOn = it }
        Field("Gemini API key (aistudio.google.com)", gemKey, KeyboardType.Password, secret = true) { gemKey = it }
        Field("Model", gemModel, KeyboardType.Text) { gemModel = it }
        Pair2({ Field("Max calls / day", gemBudget, KeyboardType.Number) { gemBudget = it } }, { Field("Min seconds between calls", gemInterval, KeyboardType.Number) { gemInterval = it } })
    }
    Card("App") {
        Toggle("Notify when a trade / paper trade passes", notify) { notify = it }
        Toggle("Record live sessions for replay", record) { record = it }
        Toggle("Keep screen on", screenOn) { screenOn = it }
        Toggle("Run in background (keeps working when minimised)", background) { background = it }
    }
    Button(onClick = { onSave(build()) }, modifier = Modifier.fillMaxWidth().height(48.dp),
        colors = ButtonDefaults.buttonColors(containerColor = C.green)) { Text("Save settings", color = androidx.compose.ui.graphics.Color.Black, fontWeight = FontWeight.Bold) }
    Spacer(Modifier.height(24.dp).width(1.dp))
}

@Composable
fun BacktestCard(bt: BacktestState, vm: EngineController, onShare: (File) -> Unit) {
    Card("Kite historical backtest (market-only)") {
        Label("Replays real NIFTY minute data every 5 min through the engine (no look-ahead), with walk-forward calibration, " +
            "and compares every horizon with two baselines: base rates (climatology) and 30-min momentum. Needs today's Kite login. " +
            "Options, news, FII data and macro inputs don't exist historically, so this tests the market-only core of the model.",
            color = C.dim, size = 10.sp, mono = false)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(1, 3, 6, 12).forEach { m ->
                OutlinedButton(onClick = { vm.runBacktest(m) }, enabled = !bt.running) { Text("${m}M", color = C.green, fontSize = 11.sp) }
            }
            if (bt.running) OutlinedButton(onClick = { vm.cancelBacktest() }) { Text("Stop", color = C.red, fontSize = 11.sp) }
        }
        if (bt.running || bt.label.isNotBlank()) {
            Label(bt.label, color = C.text, size = 10.sp, maxLines = 2)
            LinearProgressIndicator(progress = { bt.progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), color = C.green, trackColor = C.s2)
        }
        bt.error?.let { Label("✗ $it", color = C.red, size = 11.sp) }
        val r = bt.report ?: return@Card
        Spacer(Modifier.height(8.dp))
        Label("${r.from} → ${r.to} · ${r.days} days · ${r.predictions} predictions · %.0f s".format(r.elapsedMs / 1000.0), color = C.white, size = 11.sp)
        Spacer(Modifier.height(4.dp))
        Label("DOES THE MODEL BEAT THE BASELINES?", color = C.dim, size = 10.sp)
        TableHeader("H" to 0.9f, "N" to 0.6f, "Model" to 0.7f, "Momtm" to 0.7f, "Brier" to 0.7f, "Clim" to 0.7f, "Skill" to 0.7f)
        r.baselines.filter { it.n > 0 }.forEach { b ->
            TableRow(Triple(runCatching { HorizonId.valueOf(b.horizon).short }.getOrDefault(b.horizon), 0.9f, C.white), Triple("${b.n}", 0.6f, C.text),
                Triple(pct(b.modelAccuracy), 0.7f, if (b.modelAccuracy > b.momentumAccuracy) C.green else C.red),
                Triple(pct(b.momentumAccuracy), 0.7f, C.text),
                Triple("%.3f".format(b.modelBrier), 0.7f, C.text), Triple("%.3f".format(b.climatologyBrier), 0.7f, C.text),
                Triple("%+.3f".format(b.brierSkill), 0.7f, if (b.brierSkill > 0.01) C.green else if (b.brierSkill < -0.01) C.red else C.amber))
        }
        Label("Skill > 0 = probabilities beat 'always predict past base rates'. Model accuracy should also beat simple momentum.",
            color = C.dim, size = 9.sp, mono = false)
        if (!r.calibratedBrier.isNaN()) KV("Calibrated Brier (${r.calibratedN}, walk-forward)", "%.3f".format(r.calibratedBrier))
        Spacer(Modifier.height(6.dp))
        Label("SIGNAL QUALITY · bull/bear probability ≥ threshold", color = C.dim, size = 10.sp)
        TableHeader("H" to 0.8f, "Prob" to 0.7f, "Cal" to 0.5f, "Signals" to 0.8f, "Hit" to 0.7f, "Avg pts" to 0.8f)
        r.thresholds.filter { it.signals > 0 }.forEach { t ->
            TableRow(Triple(runCatching { HorizonId.valueOf(t.horizon).short }.getOrDefault(t.horizon), 0.8f, C.white), Triple("≥%.0f%%".format(t.threshold * 100), 0.7f, C.text),
                Triple(if (t.calibrated) "yes" else "raw", 0.5f, C.dim), Triple("${t.signals}", 0.8f, C.text),
                Triple(pct(t.hitRate), 0.7f, if (t.hitRate > 0.55) C.green else if (t.hitRate < 0.5) C.red else C.amber),
                Triple("%+.1f".format(t.avgMovePts), 0.8f, C.signed(t.avgMovePts, 1.0)))
        }
        Spacer(Modifier.height(6.dp))
        Label("BY REGIME (30 min)", color = C.dim, size = 10.sp)
        r.regimes.take(8).forEach { g -> KV(g.regime, "n=${g.n} · acc ${pct(g.accuracy)} · dir hit ${pct(g.directionalHitRate)}") }
        Spacer(Modifier.height(6.dp))
        (r.notes + bt.sourceNotes).forEach { Label("• $it", color = C.dim, size = 9.sp, mono = false) }
        bt.csv?.let { f ->
            Text("Share predictions CSV", color = C.blue, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp).clickable { onShare(f) })
            Text("Share report JSON", color = C.blue, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp).clickable {
                onShare(File(f.parentFile, f.name.removeSuffix(".csv") + "-report.json"))
            })
        }
    }
}

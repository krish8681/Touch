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
import com.niftyengine.engine.engines.ReplayMode
import com.niftyengine.engine.model.ConfidenceLevel
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
fun StatsCard(title: String, summaries: List<PerformanceStats.Summary>) {
    var h by remember { mutableStateOf(30) }
    Card(title) {
        if (summaries.all { it.n == 0 }) {
            Label("No evaluated predictions yet. Outcomes are attached 5/15/30/60 min after each logged prediction.", color = C.dim, size = 11.sp)
            return@Card
        }
        TableHeader("H" to 0.6f, "N" to 0.6f, "Acc" to 0.6f, "Dir hit" to 0.7f, "Brier" to 0.7f, "LogL" to 0.6f, "Trades" to 0.9f)
        summaries.forEach { s ->
            TableRow(
                Triple("${s.horizon}m", 0.6f, C.white), Triple("${s.n}", 0.6f, C.text), Triple(pct(s.accuracy), 0.6f, C.text),
                Triple(pct(s.directionalHitRate), 0.7f, C.text), Triple(if (s.brier.isNaN()) "–" else "%.3f".format(s.brier), 0.7f, C.text),
                Triple(if (s.logLoss.isNaN()) "–" else "%.2f".format(s.logLoss), 0.6f, C.text),
                Triple("${s.tradeCount}·${pct(s.tradeWinRate)}", 0.9f, C.text),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            summaries.forEach { s ->
                OutlinedButton(onClick = { h = s.horizon }, colors = ButtonDefaults.outlinedButtonColors(containerColor = if (s.horizon == h) C.s2 else C.s1)) {
                    Text("${s.horizon}m", color = if (s.horizon == h) C.green else C.dim, fontSize = 11.sp)
                }
            }
        }
        val sel = summaries.firstOrNull { it.horizon == h } ?: summaries.first()
        Label("RELIABILITY · ${sel.horizon}m · raw model score (top class) vs realised hit rate", color = C.dim, size = 10.sp)
        BucketTable(sel.buckets)
        if (sel.calibratedBuckets.any { it.n > 0 }) {
            Spacer(Modifier.height(6.dp))
            Label("AFTER CALIBRATION · ${sel.horizon}m (predictions that were calibrated when made)", color = C.dim, size = 10.sp)
            BucketTable(sel.calibratedBuckets)
        }
        if (sel.scenarioRates.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Label("SCENARIOS · ${sel.horizon}m · predicted vs realised frequency", color = C.dim, size = 10.sp)
            BucketTable(sel.scenarioRates, "pred", "freq")
            Label("SCENARIO RELIABILITY (every scenario probability vs whether it happened)", color = C.dim, size = 10.sp)
            BucketTable(sel.scenarioBuckets)
        }
        if (sel.optionBuckets.any { it.n > 0 }) {
            Spacer(Modifier.height(6.dp))
            Label("OPTION OUTCOME MODEL · ${sel.horizon}m · P(profit) vs realised net option profit", color = C.dim, size = 10.sp)
            BucketTable(sel.optionBuckets, "P(prof)", "won")
        }
        if (sel.regimes.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Label("BY REGIME · ${sel.horizon}m · directional calls (predicted vs realised)", color = C.dim, size = 10.sp)
            TableHeader("Regime" to 1.3f, "N" to 0.5f, "pred" to 0.6f, "real" to 0.6f, "Brier" to 0.7f, "bar +" to 0.6f)
            sel.regimes.forEach { r ->
                TableRow(Triple(r.regime.replace('_', ' '), 1.3f, C.white), Triple("${r.n}", 0.5f, C.text), Triple(pct(r.avgPredicted), 0.6f, C.text),
                    Triple(pct(r.hitRate), 0.6f, if (r.hitRate >= r.avgPredicted - 0.05) C.green else C.red),
                    Triple("%.3f".format(r.brier), 0.7f, C.text),
                    Triple(if (r.thresholdBump > 0) "+%.0f".format(r.thresholdBump * 100) else "–", 0.6f, if (r.thresholdBump > 0) C.amber else C.dim))
            }
            Label("Regimes that keep over-stating their probability (vs the model overall, n ≥ 30) need a higher probability before a trade ('bar +' pts).",
                color = C.dim, size = 9.sp, mono = false)
        }
        val dh = sel.driverHitRates.filterValues { !it.isNaN() }
        if (dh.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Label("DRIVER SIGN HIT RATE (${sel.horizon}m)", color = C.dim, size = 10.sp)
            dh.forEach { (k, v) -> KV(k, pct(v), if (v > 0.55) C.green else if (v < 0.45) C.red else C.amber) }
        }
        Label("Goal: 'real' ≈ 'pred' in every bucket (gap within ±5). Brier: 0 = perfect, ≈0.667 = uninformed 3-way guess; log loss ≈1.10 = uninformed. Gaps with n < 20 are greyed — too few samples.",
            color = C.dim, size = 9.sp, mono = false)
    }
}

@Composable
fun CalibrationCard(info: com.niftyengine.engine.model.CalibrationInfo, optionSamples: Int, onRefit: (() -> Unit)?) {
    Card("Probability calibration", trailing = {
        Chip(if (info.calibrated) "CALIBRATED" else "UNCALIBRATED", if (info.calibrated) C.green else C.amber)
    }) {
        Label(info.note, color = C.text, size = 11.sp, mono = false)
        Spacer(Modifier.height(4.dp))
        TableHeader("H" to 0.5f, "Outcomes" to 1f, "Brier raw→cal" to 1.3f, "LogL raw→cal" to 1.3f, "" to 0.8f)
        com.niftyengine.engine.engines.ProbabilityCalibrator.HORIZONS.forEach { hz ->
            val raw = info.holdoutBrierRaw[hz]; val cal = info.holdoutBrierCalibrated[hz]
            val lr = info.holdoutLogLossRaw[hz]; val lc = info.holdoutLogLossCalibrated[hz]
            val acc = info.accepted[hz]
            TableRow(Triple("${hz}m", 0.5f, C.white), Triple("${info.samples[hz] ?: 0}/${info.minSamples}", 1f,
                if ((info.samples[hz] ?: 0) >= info.minSamples) C.green else C.amber),
                Triple(if (raw == null || cal == null) "–" else "%.3f→%.3f".format(raw, cal), 1.3f, if (raw != null && cal != null && cal < raw) C.green else C.text),
                Triple(if (lr == null || lc == null) "–" else "%.2f→%.2f".format(lr, lc), 1.3f, if (lr != null && lc != null && lc <= lr) C.green else C.text),
                Triple(when (acc) { true -> "accepted"; false -> "REJECTED"; null -> "–" }, 0.8f, when (acc) { true -> C.green; false -> C.red; null -> C.dim }))
        }
        if (!info.scenarioBrierRaw.isNaN()) KV("Scenarios (n=${info.scenarioSamples})", "Brier %.3f→%.3f · LogL %.2f→%.2f · %s".format(
            info.scenarioBrierRaw, info.scenarioBrierCalibrated, info.scenarioLogLossRaw, info.scenarioLogLossCalibrated,
            if (info.scenarioAccepted) "accepted" else "REJECTED"), if (info.scenarioAccepted) C.green else C.red)
        KV("Option-outcome samples", "$optionSamples / 100" + if (info.optionBrierRaw.isNaN()) "" else
            " · Brier %.3f→%.3f %s".format(info.optionBrierRaw, info.optionBrierCalibrated, if (info.optionAccepted) "accepted" else "REJECTED"))
        Label("Walk-forward acceptance: each calibration is fitted on the oldest 70% of outcomes and scored on the newest 30% it never saw. " +
            "It is applied only if it lowers the Brier score without worsening log loss — otherwise the raw score is kept and labelled as such.",
            color = C.dim, size = 9.sp, mono = false)
        if (onRefit != null) TextButtonLike("Refit now", onRefit)
    }
}

@Composable
private fun TextButtonLike(label: String, onClick: () -> Unit) =
    Text(label, color = C.blue, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp).clickable { onClick() })

@Composable
private fun AuditDetail(r: com.niftyengine.engine.engines.PredictionRecord) {
    Column(Modifier.fillMaxWidth().padding(start = 8.dp, bottom = 8.dp)) {
        KV("Prediction ID", r.id)
        KV("Engine / source", "${r.engineVersion.ifBlank { "≤3.1" }} · ${r.source}")
        KV("Raw score B/b/R", "%.1f / %.1f / %.1f".format(r.pBull * 100, r.pBear * 100, r.pRange * 100))
        if (r.calibrated) KV("Calibrated B/b/R", "%.1f / %.1f / %.1f".format(r.calPBull * 100, r.calPBear * 100, r.calPRange * 100))
        KV("Composite / conflict", "%+.3f / %.0f%%".format(r.directionalScore, r.conflict * 100))
        KV("Regime", r.regime + if (r.regimeReasons.isNotEmpty()) " — " + r.regimeReasons.joinToString("; ") else "")
        KV("Expected move / σ", "%+.0f / %.0f pts (${r.horizonMinutes}m)".format(r.expectedMove, r.sigma))
        KV("Data quality", if (r.dataQuality.isNaN()) "–" else "%.0f%%".format(r.dataQuality * 100))
        r.circuitBreaker.forEach { Label("  breaker: $it", color = C.red, size = 10.sp) }
        if (r.drivers.isNotEmpty()) {
            Label("DRIVERS (score · weight · data conf · persistence · contribution)", color = C.dim, size = 9.sp)
            r.drivers.forEach { d ->
                Label("  %-20s %+.2f · %.0f · %.2f · %.2f · %+.2f".format(d.driver.label, d.score, d.weight, d.confidence, d.persistence, d.contribution),
                    color = C.text, size = 10.sp)
            }
        }
        if (r.signals.isNotEmpty()) {
            Label("ENGINE SIGNALS", color = C.dim, size = 9.sp)
            r.signals.forEach { (k, v) -> Label("  $k %+.2f (conf %.2f) %s".format(v.score, v.confidence, v.tags.take(3).joinToString(" ")), color = C.text, size = 10.sp) }
        }
        if (!r.strike.isNaN()) KV("Option", "%.0f %s @ %.2f · P(profit) %.0f%% · EV net %+.2f (gross %+.2f, cost %.2f)".format(
            r.strike, r.optionType, r.premium, r.probProfit * 100, r.optionNetEv, r.optionGrossEv, r.optionCost))
        if (r.failedChecks.isNotEmpty()) Label("Failed: " + r.failedChecks.joinToString("; "), color = C.amber, size = 10.sp)
        if (r.feedStatus.isNotEmpty()) Label("Feeds: " + r.feedStatus.entries.joinToString("; ") { "${it.key} ${it.value.substringBefore(" ·")}" }, color = C.dim, size = 9.sp)
        if (r.outcomes.isNotEmpty()) Label("Outcomes: " + r.outcomes.joinToString { o ->
            "${o.minutes}m %+.0f (%s)%s".format(o.move, when (o.realized) { 1 -> "bull"; -1 -> "bear"; else -> "range" },
                if (o.optionPrice.isNaN()) "" else " opt %.1f".format(o.optionPrice))
        }, color = C.text, size = 10.sp)
        if (r.newsHorizons.isNotEmpty()) Label("News by horizon: " + r.newsHorizons.entries.joinToString { "${it.key} %+.2f".format(it.value) }, color = C.text, size = 10.sp)
        r.events.forEach { e ->
            Label("  event ${e.id} [${e.stage}/${e.source}] sev %.2f surprise %+.2f unpriced %.0f%% reaction %+.2f conf %.2f → %+.3f %s · ${e.title.take(60)}"
                .format(e.severity, e.surprise, e.unpriced * 100, e.reactionAgreement, e.confidence, e.effectiveImpact, e.flags.joinToString(" ")),
                color = C.text, size = 9.sp)
        }
        if (r.primaryRegime.isNotBlank()) {
            KV("v5 regime / quality", "${r.primaryRegime} · %.0f%%".format(r.regimeQuality * 100))
            KV("Expectation / Δ / state", "%+.2f / %+.2f / ${r.expectationState}".format(r.expectation, r.expectationChange))
            KV("Information shock", "%.0f%% (dir %+.2f)".format(r.shockScore * 100, r.shockDirection))
            if (r.scenarioProbs.isNotEmpty()) KV("Scenarios (raw)", r.scenarioProbs.entries.joinToString(" ") { "${it.key.lowercase()} %.0f".format(it.value * 100) })
            KV("Strategy / quality", "${r.strategy} ${r.instrument} · %.0f%% ${r.qualityTier}".format(r.tradeQuality * 100))
            if (!r.strategyEv.isNaN()) KV("Strategy P(profit) / EV", "%.0f%% / %+.2f · risk ${if (r.riskApproved) "approved ${r.lots} lot(s)" else "blocked"}"
                .format(r.strategyProbProfit * 100, r.strategyEv))
        }
        if (r.config.isNotEmpty()) Label("Config: " + r.config.entries.joinToString(" ") { "${it.key}=${it.value}" }, color = C.dim, size = 9.sp)
    }
}

@Composable
fun LogScreen(ui: UiState, vm: EngineController, onExport: () -> Unit, onShare: (File) -> Unit = {}) {
    BacktestCard(ui.backtest, vm, onShare)
    CalibrationCard(ui.calibration, ui.optionCalibrationSamples) { vm.refitCalibration() }
    StatsCard("Prediction performance (this data mode)", ui.stats)
    ReplayCard(ui, vm)
    var open by remember { mutableStateOf<String?>(null) }
    Card("Prediction log (${ui.records.size} recent) · tap a row for the audit trail", trailing = {
        Text("Export", color = C.blue, fontSize = 12.sp, modifier = Modifier.padding(end = 12.dp).clickable { onExport() })
        Text("Clear", color = C.red, fontSize = 12.sp, modifier = Modifier.clickable { vm.clearLog() })
    }) {
        TableHeader("Time" to 0.8f, "Spot" to 1f, "B/b/R" to 1.4f, "Dec" to 0.9f, "30m" to 0.9f)
        ui.records.take(60).forEach { r ->
            val o30 = r.outcomes.firstOrNull { it.minutes == 30 }
            val hit = o30?.let { it.realized == r.predictedClass }
            Column(Modifier.fillMaxWidth().clickable { open = if (open == r.id) null else r.id }) {
                TableRow(
                    Triple(Session.hhmm(r.timestamp), 0.8f, C.text),
                    Triple("%.0f".format(r.spot), 1f, C.white),
                    Triple("%.0f/%.0f/%.0f".format(r.pBull * 100, r.pBear * 100, r.pRange * 100) + if (r.calibrated) "ᶜ" else "", 1.4f, C.text),
                    Triple(r.decision.replace("PAPER_TRADE", "PAPER").replace("DATA_ERROR", "DATA✗").take(6), 0.9f,
                        when (r.decision) { "TRADE" -> C.green; "PAPER_TRADE" -> C.blue; "DATA_ERROR" -> C.red; else -> C.dim }),
                    Triple(o30?.let { "%+.0f".format(it.move) } ?: "…", 0.9f, when (hit) { true -> C.green; false -> C.red; null -> C.dim }),
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
        Label("Mode A replays market data only. Mode B adds news published by each timestamp. The engine never sees bars or news from after the replayed instant.",
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
        Label("Walk-forward: each session is predicted with a calibration fitted only on earlier sessions — the honest test of whether 70% means 70%.",
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
    rp.walkForwardCalibration?.let { CalibrationCard(it, 0, null) }
    rp.result?.let { r ->
        StatsCard("Replay result · ${r.mode.name.lowercase().replace('_', ' ')} · ${r.outputs} snapshots, ${r.records.size} predictions", r.summaries)
    }
}

@Composable
private fun Field(label: String, value: String, keyboard: KeyboardType = KeyboardType.Decimal, secret: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label, fontSize = 11.sp) }, singleLine = true,
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

private fun num(s: String, def: Double) = s.trim().toDoubleOrNull() ?: def
private fun numOrNaN(s: String) = s.trim().toDoubleOrNull() ?: Double.NaN
private fun show(x: Double) = if (x.isNaN()) "" else x.toString()

@Composable
fun SettingsScreen(current: AppSettings, onSave: (AppSettings) -> Unit, onKiteLogin: (AppSettings) -> Unit) {
    var mode by remember(current) { mutableStateOf(current.mode) }
    var refresh by remember(current) { mutableStateOf(current.refreshSeconds.toString()) }
    var simSpeed by remember(current) { mutableStateOf(current.simSecondsPerMinute.toString()) }
    var horizon by remember(current) { mutableStateOf(current.horizonMinutes.toString()) }
    var minProb by remember(current) { mutableStateOf((current.minProbability * 100).toInt().toString()) }
    var minConf by remember(current) { mutableStateOf(current.minConfidence) }
    var minMove by remember(current) { mutableStateOf(current.minExpectedMovePts.toString()) }
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
    var cpi by remember(current) { mutableStateOf(show(m.cpiYoY)) }
    var cpiPrev by remember(current) { mutableStateOf(show(m.cpiPrevYoY)) }
    var gdp by remember(current) { mutableStateOf(show(m.gdpGrowth)) }
    var gdpPrev by remember(current) { mutableStateOf(show(m.gdpPrevGrowth)) }
    var pmi by remember(current) { mutableStateOf(show(m.pmiManufacturing)) }
    var credit by remember(current) { mutableStateOf(show(m.creditGrowth)) }
    var liq by remember(current) { mutableStateOf(show(m.liquidityCr)) }
    val dates = remember(current) { androidx.compose.runtime.mutableStateMapOf<String, String>().apply { putAll(current.macroDates) } }
    var reqCal by remember(current) { mutableStateOf(current.requireCalibration) }
    var minCalN by remember(current) { mutableStateOf(current.minCalibrationSamples.toString()) }
    var confirm by remember(current) { mutableStateOf(current.confirmCycles.toString()) }
    var minOptP by remember(current) { mutableStateOf((current.minOptionProfitProb * 100).toInt().toString()) }
    var eventBump by remember(current) { mutableStateOf((current.eventThresholdBump * 100).toInt().toString()) }
    var minDq by remember(current) { mutableStateOf((current.minDataQuality * 100).toInt().toString()) }
    var brok by remember(current) { mutableStateOf(current.brokeragePerOrder.toString()) }
    var stt by remember(current) { mutableStateOf(current.sttSellPct.toString()) }
    var slip by remember(current) { mutableStateOf(current.slippageTicks.toString()) }
    var lotSize by remember(current) { mutableStateOf(current.lotSize.toString()) }
    var lots by remember(current) { mutableStateOf(current.lots.toString()) }
    var gemOn by remember(current) { mutableStateOf(current.geminiEnabled) }
    var gemKey by remember(current) { mutableStateOf(current.geminiApiKey) }
    var gemModel by remember(current) { mutableStateOf(current.geminiModel) }
    var gemBudget by remember(current) { mutableStateOf(current.geminiDailyBudget.toString()) }
    var gemInterval by remember(current) { mutableStateOf(current.geminiMinIntervalSec.toString()) }
    var shadow by remember(current) { mutableStateOf(current.shadowMode) }
    var capital by remember(current) { mutableStateOf(current.capital.toLong().toString()) }
    var riskPct by remember(current) { mutableStateOf(current.maxRiskPerTradePct.toString()) }
    var dailyLossPct by remember(current) { mutableStateOf(current.maxDailyLossPct.toString()) }
    var maxPos by remember(current) { mutableStateOf(current.maxOpenPositions.toString()) }
    var maxTrades by remember(current) { mutableStateOf(current.maxTradesPerDay.toString()) }
    var maxIv by remember(current) { mutableStateOf(current.maxIvPct.toString()) }
    var stopPct by remember(current) { mutableStateOf(current.longStopPct.toString()) }
    var targetPct by remember(current) { mutableStateOf(current.longTargetPct.toString()) }
    var minQuality by remember(current) { mutableStateOf((current.minTradeQuality * 100).toInt().toString()) }
    var stratLong by remember(current) { mutableStateOf(current.enableLongOptions) }
    var stratSpread by remember(current) { mutableStateOf(current.enableSpreads) }
    var stratCondor by remember(current) { mutableStateOf(current.enableCondor) }
    var healthEligible by remember(current) { mutableStateOf(current.healthEligible.toInt().toString()) }
    var healthShadow by remember(current) { mutableStateOf(current.healthShadow.toInt().toString()) }

    fun build() = current.copy(
        mode = mode, refreshSeconds = num(refresh, 30.0).toInt().coerceIn(5, 600),
        simSecondsPerMinute = num(simSpeed, 2.0).toInt().coerceIn(1, 60),
        horizonMinutes = num(horizon, 60.0).toInt().coerceIn(5, 375),
        minProbability = (num(minProb, 65.0) / 100).coerceIn(0.34, 0.99), minConfidence = minConf,
        minExpectedMovePts = num(minMove, 40.0), maxSpreadPct = num(maxSpread, 3.0), minOi = num(minOi, 2000.0),
        minVolume = num(minVol, 500.0), notifyOnTrade = notify, recordSessions = record, keepScreenOn = screenOn, runInBackground = background,
        kiteApiKey = kKey.trim(), kiteApiSecret = kSecret.trim(), kiteAccessToken = kToken.trim(),
        macro = m.copy(repoRate = numOrNaN(repo), lastPolicyChangeBps = num(policy, 0.0), cpiYoY = numOrNaN(cpi), cpiPrevYoY = numOrNaN(cpiPrev),
            gdpGrowth = numOrNaN(gdp), gdpPrevGrowth = numOrNaN(gdpPrev), pmiManufacturing = numOrNaN(pmi),
            creditGrowth = numOrNaN(credit), liquidityCr = numOrNaN(liq)),
        macroDates = dates.filterValues { it.isNotBlank() },
        requireCalibration = reqCal, minCalibrationSamples = num(minCalN, 150.0).toInt().coerceIn(30, 5000),
        confirmCycles = num(confirm, 2.0).toInt().coerceIn(1, 10),
        minOptionProfitProb = (num(minOptP, 50.0) / 100).coerceIn(0.0, 0.95),
        eventThresholdBump = (num(eventBump, 8.0) / 100).coerceIn(0.0, 0.3),
        minDataQuality = (num(minDq, 70.0) / 100).coerceIn(0.0, 1.0),
        brokeragePerOrder = num(brok, 20.0), sttSellPct = num(stt, 0.1), slippageTicks = num(slip, 1.0),
        lotSize = num(lotSize, 65.0).toInt().coerceAtLeast(1), lots = num(lots, 1.0).toInt().coerceAtLeast(1),
        geminiEnabled = gemOn, geminiApiKey = gemKey.trim(), geminiModel = gemModel.trim().ifBlank { "gemini-2.5-flash" },
        geminiDailyBudget = num(gemBudget, 200.0).toInt().coerceIn(0, 5000),
        geminiMinIntervalSec = num(gemInterval, 60.0).toInt().coerceIn(10, 3600),
        shadowMode = shadow, capital = num(capital, 200_000.0).coerceAtLeast(10_000.0),
        maxRiskPerTradePct = num(riskPct, 2.0).coerceIn(0.1, 20.0), maxDailyLossPct = num(dailyLossPct, 5.0).coerceIn(0.5, 50.0),
        maxOpenPositions = num(maxPos, 1.0).toInt().coerceIn(1, 10), maxTradesPerDay = num(maxTrades, 4.0).toInt().coerceIn(1, 50),
        maxIvPct = num(maxIv, 35.0).coerceIn(5.0, 150.0), longStopPct = num(stopPct, 35.0).coerceIn(5.0, 90.0),
        longTargetPct = num(targetPct, 60.0).coerceIn(5.0, 500.0), minTradeQuality = (num(minQuality, 65.0) / 100).coerceIn(0.0, 0.99),
        enableLongOptions = stratLong, enableSpreads = stratSpread, enableCondor = stratCondor,
        healthEligible = num(healthEligible, 75.0).coerceIn(10.0, 100.0),
        healthShadow = num(healthShadow, 60.0).coerceIn(0.0, 100.0).coerceAtMost(num(healthEligible, 75.0).coerceIn(10.0, 100.0)),
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
            "3. With Kite active: quotes, option chain, futures OI history and 1-min/daily candles come from Kite; NSE adds free-float weights, ΔOI and FII/DII; Yahoo/RSS add global markets and news.\n" +
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
    Card("Event intelligence (Gemini)") {
        Label("Gemini reads important news and describes each event: stage, expectation vs actual, surprise, severity, sectors, channels, duration. " +
            "It never produces buy/sell or call/put signals; the engine checks its reading against the market's reaction. Without a key the rule engine is used.",
            color = C.dim, size = 10.sp, mono = false)
        Toggle("Use Gemini for event understanding", gemOn) { gemOn = it }
        Field("Gemini API key (aistudio.google.com)", gemKey, KeyboardType.Password, secret = true) { gemKey = it }
        Field("Model", gemModel, KeyboardType.Text) { gemModel = it }
        Row { Column(Modifier.weight(1f).padding(end = 4.dp)) { Field("Max calls / day", gemBudget, KeyboardType.Number) { gemBudget = it } }
            Column(Modifier.weight(1f)) { Field("Min seconds between calls", gemInterval, KeyboardType.Number) { gemInterval = it } } }
        Label("Free-tier limits change; keep calls/day below your quota. Each call batches up to 8 events. The key stays in this app's private storage.",
            color = C.dim, size = 10.sp, mono = false)
    }
    Card("Prediction & trade filter") {
        Field("Prediction horizon (minutes)", horizon, KeyboardType.Number) { horizon = it }
        Field("Min direction probability (%)", minProb, KeyboardType.Number) { minProb = it }
        Label("Min confidence", color = C.dim, size = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ConfidenceLevel.values().forEach { c ->
                OutlinedButton(onClick = { minConf = c }, colors = ButtonDefaults.outlinedButtonColors(containerColor = if (c == minConf) C.s2 else C.s1)) {
                    Text(c.name, color = if (c == minConf) C.green else C.dim, fontSize = 11.sp)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Field("Min expected move (points)", minMove) { minMove = it }
        Field("Max option bid/ask spread (%)", maxSpread) { maxSpread = it }
        Field("Min option OI", minOi, KeyboardType.Number) { minOi = it }
        Field("Min option volume", minVol, KeyboardType.Number) { minVol = it }
        Field("Min option P(profit, net) (%)", minOptP, KeyboardType.Number) { minOptP = it }
        Field("Event regime: raise probability threshold by (pts)", eventBump, KeyboardType.Number) { eventBump = it }
        Field("Min data quality (%)", minDq, KeyboardType.Number) { minDq = it }
        Field("Consecutive cycles before TRADE (hysteresis)", confirm, KeyboardType.Number) { confirm = it }
    }
    Card("Strategy, risk & shadow mode (v5)") {
        Label("The risk engine is deterministic code: it sizes from the stop, enforces the daily loss limit, open positions, trades/day, " +
            "spread, IV and event caps, and sets stop/target/time exits. Shadow mode executes approved decisions virtually — the app never places real orders.",
            color = C.dim, size = 10.sp, mono = false)
        Toggle("Shadow mode (virtual execution of TRADE / PAPER TRADE)", shadow) { shadow = it }
        Row { Column(Modifier.weight(1f).padding(end = 4.dp)) { Field("Capital ₹", capital, KeyboardType.Number) { capital = it } }
            Column(Modifier.weight(1f)) { Field("Max risk / trade %", riskPct) { riskPct = it } } }
        Row { Column(Modifier.weight(1f).padding(end = 4.dp)) { Field("Max daily loss %", dailyLossPct) { dailyLossPct = it } }
            Column(Modifier.weight(1f)) { Field("Max IV % (long premium)", maxIv) { maxIv = it } } }
        Row { Column(Modifier.weight(1f).padding(end = 4.dp)) { Field("Max open positions", maxPos, KeyboardType.Number) { maxPos = it } }
            Column(Modifier.weight(1f)) { Field("Max trades / day", maxTrades, KeyboardType.Number) { maxTrades = it } } }
        Row { Column(Modifier.weight(1f).padding(end = 4.dp)) { Field("Long option stop %", stopPct) { stopPct = it } }
            Column(Modifier.weight(1f)) { Field("Long option target %", targetPct) { targetPct = it } } }
        Field("Min trade quality (%)", minQuality, KeyboardType.Number) { minQuality = it }
        Row { Column(Modifier.weight(1f).padding(end = 4.dp)) { Field("Model health: eligible ≥", healthEligible, KeyboardType.Number) { healthEligible = it } }
            Column(Modifier.weight(1f)) { Field("Model health: shadow ≥", healthShadow, KeyboardType.Number) { healthShadow = it } } }
        Label("Strategies the selector may use", color = C.dim, size = 11.sp)
        Toggle("Buy call / buy put (strong directional)", stratLong) { stratLong = it }
        Toggle("Bull call / bear put debit spreads (moderate, or rich IV)", stratSpread) { stratSpread = it }
        Toggle("Iron condor — defined-risk option selling (range + rich IV)", stratCondor) { stratCondor = it }
    }
    Card("Calibration") {
        Toggle("Require calibrated probabilities for TRADE (else PAPER TRADE)", reqCal) { reqCal = it }
        Field("Min outcomes per horizon before calibrating", minCalN, KeyboardType.Number) { minCalN = it }
        Label("Predictions are logged every 5 min during market hours, so 150 outcomes ≈ 2 sessions per horizon. More is better; isotonic fits need data.",
            color = C.dim, size = 10.sp, mono = false)
    }
    Card("Transaction costs (round trip)") {
        Row { Column(Modifier.weight(1f).padding(end = 4.dp)) { Field("Brokerage ₹/order", brok) { brok = it } }
            Column(Modifier.weight(1f)) { Field("STT % (sell)", stt) { stt = it } } }
        Row { Column(Modifier.weight(1f).padding(end = 4.dp)) { Field("Lot size", lotSize, KeyboardType.Number) { lotSize = it } }
            Column(Modifier.weight(1f)) { Field("Lots", lots, KeyboardType.Number) { lots = it } } }
        Field("Slippage (ticks per side)", slip) { slip = it }
        Label("Also applied: NSE txn 0.03503%, SEBI ₹10/cr, GST 18%, stamp 0.003% (buy). Check your broker's current charge sheet — statutory rates change.",
            color = C.dim, size = 10.sp, mono = false)
    }
    Card("India macro (slow inputs)") {
        Label("Slow variables only add a capped bias (±0.30) and never create an intraday signal by themselves. Leave blank if unknown.",
            color = C.dim, size = 10.sp, mono = false)
        Spacer(Modifier.height(4.dp))
        Row { Column(Modifier.weight(1f).padding(end = 4.dp)) { Field("Repo rate %", repo) { repo = it } }
            Column(Modifier.weight(1f)) { Field("Last policy Δ bps", policy) { policy = it } } }
        Row { Column(Modifier.weight(1f).padding(end = 4.dp)) { Field("CPI YoY %", cpi) { cpi = it } }
            Column(Modifier.weight(1f)) { Field("CPI prev %", cpiPrev) { cpiPrev = it } } }
        Row { Column(Modifier.weight(1f).padding(end = 4.dp)) { Field("GDP growth %", gdp) { gdp = it } }
            Column(Modifier.weight(1f)) { Field("GDP prev %", gdpPrev) { gdpPrev = it } } }
        Row { Column(Modifier.weight(1f).padding(end = 4.dp)) { Field("Mfg PMI", pmi) { pmi = it } }
            Column(Modifier.weight(1f)) { Field("Credit growth %", credit) { credit = it } } }
        Field("System liquidity ₹ cr (+surplus)", liq) { liq = it }
        Label("Release dates (yyyy-mm-dd). Values older than their release cycle are marked STALE; undated ones count as degraded.",
            color = C.dim, size = 10.sp, mono = false)
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
    Card("App") {
        Toggle("Notify when trade filter passes", notify) { notify = it }
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
            "and compares it with two baselines: base rates (climatology) and 30-min momentum. Needs today's Kite login. " +
            "Options, news, GIFT and global data don't exist historically, so this tests the market-only core.",
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
        TableHeader("H" to 0.5f, "N" to 0.7f, "Model" to 0.8f, "Momtm" to 0.8f, "Brier" to 0.8f, "Clim" to 0.8f, "Skill" to 0.8f)
        r.baselines.forEach { b ->
            TableRow(Triple("${b.horizon}m", 0.5f, C.white), Triple("${b.n}", 0.7f, C.text),
                Triple(pct(b.modelAccuracy), 0.8f, if (b.modelAccuracy > b.momentumAccuracy) C.green else C.red),
                Triple(pct(b.momentumAccuracy), 0.8f, C.text),
                Triple("%.3f".format(b.modelBrier), 0.8f, C.text), Triple("%.3f".format(b.climatologyBrier), 0.8f, C.text),
                Triple("%+.3f".format(b.brierSkill), 0.8f, if (b.brierSkill > 0.01) C.green else if (b.brierSkill < -0.01) C.red else C.amber))
        }
        Label("Skill > 0 = probabilities beat 'always predict past base rates'. Model accuracy should also beat simple momentum.",
            color = C.dim, size = 9.sp, mono = false)
        if (!r.calibratedBrier.isNaN()) KV("Calibrated Brier (${r.calibratedN}, walk-forward)", "%.3f".format(r.calibratedBrier))
        Spacer(Modifier.height(6.dp))
        Label("SIGNAL QUALITY · bull/bear probability ≥ threshold", color = C.dim, size = 10.sp)
        TableHeader("H" to 0.5f, "Prob" to 0.7f, "Cal" to 0.5f, "Signals" to 0.8f, "Hit" to 0.7f, "Avg pts" to 0.8f)
        r.thresholds.filter { it.signals > 0 }.forEach { t ->
            TableRow(Triple("${t.horizon}m", 0.5f, C.white), Triple("≥%.0f%%".format(t.threshold * 100), 0.7f, C.text),
                Triple(if (t.calibrated) "yes" else "raw", 0.5f, C.dim), Triple("${t.signals}", 0.8f, C.text),
                Triple(pct(t.hitRate), 0.7f, if (t.hitRate > 0.55) C.green else if (t.hitRate < 0.5) C.red else C.amber),
                Triple("%+.1f".format(t.avgMovePts), 0.8f, C.signed(t.avgMovePts, 1.0)))
        }
        Spacer(Modifier.height(6.dp))
        Label("BY REGIME (30m)", color = C.dim, size = 10.sp)
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

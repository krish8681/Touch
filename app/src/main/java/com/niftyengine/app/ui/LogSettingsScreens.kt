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
fun StatsCard(title: String, summaries: List<PerformanceStats.Summary>) {
    Card(title) {
        if (summaries.all { it.n == 0 }) {
            Label("No evaluated predictions yet. Outcomes are attached 15/30/60 min after each logged prediction.", color = C.dim, size = 11.sp)
            return@Card
        }
        TableHeader("Horizon" to 1f, "N" to 0.6f, "Acc" to 0.7f, "Dir hit" to 0.8f, "Brier" to 0.8f, "Trades" to 0.9f)
        summaries.forEach { s ->
            TableRow(
                Triple("${s.horizon}m", 1f, C.white), Triple("${s.n}", 0.6f, C.text), Triple(pct(s.accuracy), 0.7f, C.text),
                Triple(pct(s.directionalHitRate), 0.8f, C.text), Triple(if (s.brier.isNaN()) "–" else "%.3f".format(s.brier), 0.8f, C.text),
                Triple("${s.tradeCount}·${pct(s.tradeWinRate)}", 0.9f, C.text),
            )
        }
        val s30 = summaries.firstOrNull { it.horizon == 30 } ?: summaries.first()
        Spacer(Modifier.height(8.dp))
        Label("CALIBRATION (${s30.horizon}m): predicted top-probability vs realised hit rate", color = C.dim, size = 10.sp)
        s30.buckets.forEach { b -> KV(b.label, "n=${b.n}  pred ${pct(b.avgPredicted)}  real ${pct(b.hitRate)}") }
        val dh = s30.driverHitRates.filterValues { !it.isNaN() }
        if (dh.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Label("DRIVER SIGN HIT RATE (${s30.horizon}m)", color = C.dim, size = 10.sp)
            dh.forEach { (k, v) -> KV(k, pct(v), if (v > 0.55) C.green else if (v < 0.45) C.red else C.amber) }
        }
        Label("Brier: 0 = perfect, ≈0.667 = uninformed 3-way guess.", color = C.dim, size = 9.sp)
    }
}

@Composable
fun LogScreen(ui: UiState, vm: MainViewModel, onExport: () -> Unit) {
    StatsCard("Live prediction performance", ui.stats)
    ReplayCard(ui, vm)
    Card("Prediction log (${ui.records.size} recent)", trailing = {
        Text("Export", color = C.blue, fontSize = 12.sp, modifier = Modifier.padding(end = 12.dp).clickable { onExport() })
        Text("Clear", color = C.red, fontSize = 12.sp, modifier = Modifier.clickable { vm.clearLog() })
    }) {
        TableHeader("Time" to 0.8f, "Spot" to 1f, "B/b/R" to 1.4f, "Dec" to 0.9f, "30m" to 0.9f)
        ui.records.take(60).forEach { r ->
            val o30 = r.outcomes.firstOrNull { it.minutes == 30 }
            val hit = o30?.let { it.realized == r.predictedClass }
            TableRow(
                Triple(Session.hhmm(r.timestamp), 0.8f, C.text),
                Triple("%.0f".format(r.spot), 1f, C.white),
                Triple("%.0f/%.0f/%.0f".format(r.pBull * 100, r.pBear * 100, r.pRange * 100), 1.4f, C.text),
                Triple(r.decision.take(5), 0.9f, if (r.decision == "TRADE") C.green else C.dim),
                Triple(o30?.let { "%+.0f".format(it.move) } ?: "…", 0.9f, when (hit) { true -> C.green; false -> C.red; null -> C.dim }),
            )
        }
    }
}

@Composable
fun ReplayCard(ui: UiState, vm: MainViewModel) {
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
        if (ui.sessions.isEmpty()) Label("Recorded live sessions appear here (Settings → record live sessions).", color = C.dim, size = 10.sp)
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

    fun build() = current.copy(
        mode = mode, refreshSeconds = num(refresh, 30.0).toInt().coerceIn(5, 600),
        simSecondsPerMinute = num(simSpeed, 2.0).toInt().coerceIn(1, 60),
        horizonMinutes = num(horizon, 60.0).toInt().coerceIn(5, 375),
        minProbability = (num(minProb, 65.0) / 100).coerceIn(0.34, 0.99), minConfidence = minConf,
        minExpectedMovePts = num(minMove, 40.0), maxSpreadPct = num(maxSpread, 3.0), minOi = num(minOi, 2000.0),
        minVolume = num(minVol, 500.0), notifyOnTrade = notify, recordSessions = record, keepScreenOn = screenOn,
        kiteApiKey = kKey.trim(), kiteApiSecret = kSecret.trim(), kiteAccessToken = kToken.trim(),
        macro = m.copy(repoRate = numOrNaN(repo), lastPolicyChangeBps = num(policy, 0.0), cpiYoY = numOrNaN(cpi), cpiPrevYoY = numOrNaN(cpiPrev),
            gdpGrowth = numOrNaN(gdp), gdpPrevGrowth = numOrNaN(gdpPrev), pmiManufacturing = numOrNaN(pmi),
            creditGrowth = numOrNaN(credit), liquidityCr = numOrNaN(liq)),
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
    Card("Kite Connect (optional)") {
        Field("API key", kKey, KeyboardType.Text) { kKey = it }
        Field("API secret", kSecret, KeyboardType.Password, secret = true) { kSecret = it }
        Field("Access token (filled by login)", kToken, KeyboardType.Text, secret = true) { kToken = it }
        if (current.kiteTokenDate.isNotBlank()) Label("Token issued: ${current.kiteTokenDate} (expires daily)", color = C.dim, size = 10.sp)
        Button(onClick = { onKiteLogin(build()) }, enabled = kKey.isNotBlank() && kSecret.isNotBlank(), modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = C.blue)) { Text("Login to Kite", fontSize = 12.sp) }
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
    }
    Card("App") {
        Toggle("Notify when trade filter passes", notify) { notify = it }
        Toggle("Record live sessions for replay", record) { record = it }
        Toggle("Keep screen on", screenOn) { screenOn = it }
    }
    Button(onClick = { onSave(build()) }, modifier = Modifier.fillMaxWidth().height(48.dp),
        colors = ButtonDefaults.buttonColors(containerColor = C.green)) { Text("Save settings", color = androidx.compose.ui.graphics.Color.Black, fontWeight = FontWeight.Bold) }
    Spacer(Modifier.height(24.dp).width(1.dp))
}

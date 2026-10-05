package com.niftyengine.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.niftyengine.engine.core.PriceDistribution
import com.niftyengine.engine.core.Session
import com.niftyengine.engine.model.ConfidenceLevel
import com.niftyengine.engine.model.Decision
import com.niftyengine.engine.model.EngineOutput
import com.niftyengine.engine.model.EventRiskLevel
import com.niftyengine.engine.model.EventStage
import com.niftyengine.engine.model.ExpiryIntel
import com.niftyengine.engine.model.ExpiryRegime
import com.niftyengine.engine.model.FeedStatus
import com.niftyengine.engine.model.HorizonGroup
import com.niftyengine.engine.model.HorizonId
import com.niftyengine.engine.model.HorizonPrediction
import com.niftyengine.engine.model.LegAction
import com.niftyengine.engine.model.MarketRegime
import com.niftyengine.engine.model.NewsHorizon
import com.niftyengine.engine.model.OptionValueClass
import com.niftyengine.engine.model.StrategyCandidate
import kotlin.math.abs

fun confColor(c: ConfidenceLevel) = when (c) { ConfidenceLevel.HIGH -> C.green; ConfidenceLevel.MEDIUM -> C.amber; ConfidenceLevel.LOW -> C.red }
fun riskColor(r: EventRiskLevel) = when (r) { EventRiskLevel.LOW -> C.green; EventRiskLevel.MEDIUM -> C.amber; else -> C.red }
fun regimeColor(r: MarketRegime) = when { r.bias > 0 -> C.green; r.bias < 0 -> C.red; r == MarketRegime.EVENT_SHOCK -> C.red; else -> C.amber }
fun expiryRegimeColor(r: ExpiryRegime?) = when (r) {
    ExpiryRegime.BULLISH_EXPANSION -> C.green; ExpiryRegime.BEARISH_EXPANSION -> C.red; ExpiryRegime.VOLATILITY_EXPANSION -> C.violet
    ExpiryRegime.RANGE_PIN -> C.amber; else -> C.dim
}
private fun pts(x: Double) = if (x.isNaN()) "–" else "%,.0f".format(x)
private fun pct0(x: Double) = if (x.isNaN()) "–" else "%.0f%%".format(x * 100)

// =====================================================================================================================
//  Home — the §24 "NIFTY ENGINE" summary
// =====================================================================================================================

@Composable
fun DashboardScreen(ui: UiState) {
    val o = ui.output ?: return EmptyState(ui)
    Card {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Label("NIFTY 50", color = C.dim, size = 11.sp, weight = FontWeight.Bold)
                Label("%,.2f".format(o.spot), color = C.white, size = 30.sp, weight = FontWeight.Bold)
            }
            Column(horizontalAlignment = Alignment.End) {
                Label("%+.2f%%".format(o.spotChangePct), color = C.signed(o.spotChangePct, 0.0), size = 18.sp, weight = FontWeight.Bold)
                Label(Session.hhmm(o.timestamp) + " IST" + if (o.marketOpen) "" else " · closed", color = C.dim, size = 10.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        SpotChart(ui.chart)
        Label("strip: 1-hour bull (green) vs bear (red) probability", color = C.dim, size = 9.sp, mono = false)
    }
    if (o.dataQuality.circuitBreaker.isNotEmpty()) DataErrorBanner(o)
    EngineSummaryCard(o)
    DecisionCard(o)
    o.gift?.takeIf { it.state != com.niftyengine.engine.model.GapState.SPENT }?.let { GiftCard(it, o.signals["GIFT Nifty"]) }
    MasterNotesCard(o)
    DataQualityCard(o)
    FeedStatusCard(o)
}

@Composable
fun EmptyState(ui: UiState) {
    Card("Starting") {
        Label(if (ui.busy) "Collecting data and running the engine…" else "Waiting for first cycle.", color = C.text)
        ui.error?.let { Label("Error: $it", color = C.red, size = 11.sp) }
    }
}

@Composable
private fun HorizonLine(h: HorizonPrediction) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Label(h.id.short, color = C.text, size = 12.sp, modifier = Modifier.weight(1f))
        Label("${h.direction.arrow} ${h.direction.label}", color = dirColor(h.direction), size = 12.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
        // A neutral call shows the bull/bear split rather than the narrow neutral-band probability.
        val p = if (h.direction == com.niftyengine.engine.model.Direction.NEUTRAL) "↑%.0f ↓%.0f".format(h.bull * 100, h.bear * 100)
        else "%.0f%%".format(h.probability * 100)
        Label(p + if (h.calibrated) "ᶜ" else "", color = dirColor(h.direction), size = 13.sp, weight = FontWeight.Bold)
    }
}

@Composable
private fun ExpiryBlock(h: HorizonPrediction?, intel: ExpiryIntel?) {
    if (h == null) return
    HorizonLine(h)
    KV("Range (≈68 %)", "${pts(h.rangeLow)} – ${pts(h.rangeHigh)}")
    KV("Support", intel?.support?.let { "${pts(it.strike)} (%.2f)".format(it.strength) } ?: pts(h.support))
    KV("Resistance", intel?.resistance?.let { "${pts(it.strike)} (%.2f)".format(it.strength) } ?: pts(h.resistance))
    KV("Expiry", "${h.expiry} · %.1f days".format(h.daysToExpiry) + (intel?.let { " · ${it.tradingDaysToExpiry} sessions" } ?: ""))
    intel?.let { KV("Expiry regime", it.regime.label, expiryRegimeColor(it.regime)) }
}

@Composable
fun EngineSummaryCard(o: EngineOutput) {
    val m = o.master
    Card("NIFTY engine", trailing = { Chip(if (o.calibration.calibrated) "CALIBRATED" else "MODEL SCORES", if (o.calibration.calibrated) C.green else C.amber) }) {
        KV("Current NIFTY", "%,.2f".format(o.spot))
        KV("Market regime", o.regime.regime.label + (o.regime.secondary?.let { " (+ ${it.label.lowercase()})" } ?: ""), regimeColor(o.regime.regime))
        KV("Expiry regime", o.weekly?.regime?.label ?: "–", expiryRegimeColor(o.weekly?.regime))
        Spacer(Modifier.height(6.dp))
        Label("H1 — INTRADAY", color = C.dim, size = 10.sp, weight = FontWeight.Bold)
        o.horizons.filter { it.id.intraday }.forEach { HorizonLine(it) }
        o.horizon(HorizonId.CLOSE)?.let { c ->
            val prevClose = o.spot / (1 + o.spotChangePct / 100)
            val pPos = runCatching { PriceDistribution(o.spot, c.components).pAbove(prevClose) }.getOrDefault(Double.NaN)
            KV("Expected close", "${pts(c.rangeLow)} – ${pts(c.rangeHigh)}")
            KV("P(positive close)", pct0(pPos) + if (c.calibrated) "" else " (model)")
            if (c.note.isNotBlank()) Label(c.note, color = C.dim, size = 9.sp, mono = false)
        }
        Spacer(Modifier.height(6.dp))
        Label("H2 — WEEKLY EXPIRY", color = C.dim, size = 10.sp, weight = FontWeight.Bold)
        ExpiryBlock(o.horizon(HorizonId.WEEKLY), o.weekly)
        Spacer(Modifier.height(6.dp))
        Label("H3 — MONTHLY EXPIRY", color = C.dim, size = 10.sp, weight = FontWeight.Bold)
        ExpiryBlock(o.horizon(HorizonId.MONTHLY), o.monthly ?: o.weekly?.takeIf { o.horizon(HorizonId.MONTHLY)?.expiry == it.expiry })
        Spacer(Modifier.height(8.dp))
        Row {
            Column(Modifier.weight(1f)) {
                Label("ALIGNMENT", color = C.dim, size = 10.sp)
                Label("${m.alignment}/3", color = if (m.alignment == 3) C.green else if (m.alignment == 2) C.amber else C.red, size = 18.sp, weight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f)) {
                Label("CONFIDENCE", color = C.dim, size = 10.sp)
                Label(m.confidence.name, color = confColor(m.confidence), size = 18.sp, weight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f)) {
                Label("EVENT RISK", color = C.dim, size = 10.sp)
                Label(m.eventRisk.label.uppercase(), color = riskColor(m.eventRisk), size = 18.sp, weight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(4.dp))
        KV("Master", "${m.direction.arrow} ${m.direction.label}" + (if (m.direction == com.niftyengine.engine.model.Direction.NEUTRAL) "" else " %.0f%%".format(m.probability * 100)) +
            " · ${m.alignmentLabel}", dirColor(m.direction))
        Label("Probability and confidence are separate: probability = how likely; confidence = how well the evidence (alignment, FII, global, heavyweights, options, data, event risk) supports it.",
            color = C.dim, size = 9.sp, mono = false)
    }
}

@Composable
private fun MasterNotesCard(o: EngineOutput) {
    val m = o.master
    if (m.notes.isEmpty()) return
    Card("Why this confidence") {
        HorizonGroup.values().forEach { g ->
            val d = m.horizonDirections[g] ?: return@forEach
            KV(g.label, "${d.arrow} ${d.label} · P(${m.direction.label.lowercase()}) ${pct0(m.horizonProbabilities[g] ?: Double.NaN)}", dirColor(d))
        }
        m.notes.forEach { Label("• $it", color = C.text, size = 10.sp, mono = false) }
    }
}

@Composable
fun DecisionCard(o: EngineOutput) {
    val dec = o.decision
    val col = when (dec.decision) {
        Decision.TRADE -> C.green; Decision.PAPER_TRADE -> C.blue; Decision.WAIT -> C.amber
        Decision.NO_TRADE -> C.red; Decision.DATA_ERROR -> C.red
    }
    Card("Final signal (risk engine)") {
        Label(dec.decision.name.replace('_', ' '), color = col, size = 24.sp, weight = FontWeight.Bold)
        Label(dec.headline, color = C.white, size = 12.sp)
        dec.candidate?.let { c ->
            Spacer(Modifier.height(4.dp))
            Label(c.rationale, color = C.text, size = 10.sp, mono = false)
            if (dec.lots > 0) KV("Size", "${dec.lots} lot(s) · max loss ₹%,.0f · EV ₹%,.0f".format(dec.maxLossRupees, dec.expectedPnlRupees))
        }
        Spacer(Modifier.height(6.dp))
        dec.checks.forEach { c ->
            Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                Label(if (c.passed) "✓" else "✗", color = if (c.passed) C.green else C.red, size = 12.sp, weight = FontWeight.Bold, modifier = Modifier.padding(end = 6.dp))
                Label(c.name, color = C.text, size = 11.sp, modifier = Modifier.weight(0.45f))
                Label(c.detail, color = C.dim, size = 11.sp, modifier = Modifier.weight(0.55f))
            }
        }
        Spacer(Modifier.height(6.dp))
        if (dec.decision == Decision.PAPER_TRADE) Label("Every check passed except probability calibration. Paper-trade it and let the log build outcomes; don't use real money yet.",
            color = C.blue, size = 10.sp, mono = false)
        Label("Decision support only — not investment advice. Prediction ≠ trade: direction, valuation, structure and risk are separate layers.",
            color = C.dim, size = 9.sp, mono = false)
    }
}

@Composable
fun FeedStatusCard(o: EngineOutput) {
    Card("Data feeds · ${o.dataSource}") {
        o.feedStatus.forEach { (k, v) -> KV(k, v, if (v.startsWith("✗")) C.red else C.text) }
    }
}

// =====================================================================================================================
//  Horizons — factor-level breakdown (§27 effective scores, §28 weights, §31 missing data)
// =====================================================================================================================

@Composable
fun HorizonsScreen(ui: UiState) {
    val o = ui.output ?: return EmptyState(ui)
    var sel by rememberSaveable { mutableStateOf(HorizonId.M60) }
    HorizonPicker(sel) { sel = it }
    val h = o.horizon(sel) ?: return
    Card("${h.id.group.label} · ${h.id.label}", trailing = { Chip(if (h.calibrated) "CALIBRATED" else "MODEL SCORE", if (h.calibrated) C.green else C.amber) }) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Label("DIRECTION", color = C.dim, size = 10.sp)
                Label("${h.direction.arrow} ${h.direction.label} %.0f%%".format(h.probability * 100), color = dirColor(h.direction), size = 20.sp, weight = FontWeight.Bold)
            }
            Column(horizontalAlignment = Alignment.End) {
                Label("SCORE", color = C.dim, size = 10.sp)
                Label("%+.1f".format(h.score), color = C.signed(h.score, 5.0), size = 20.sp, weight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(6.dp))
        TriBar(h.bull, h.neutral, h.bear)
        if (h.calibrated) Label("Raw model score: ↑%.0f / →%.0f / ↓%.0f".format(h.pBull * 100, h.pNeutral * 100, h.pBear * 100), color = C.dim, size = 10.sp)
        else Label("Uncalibrated: model scores until enough outcomes are logged (${o.calibration.samples[h.id.name] ?: 0}/${o.calibration.minSamples}).", color = C.dim, size = 10.sp, mono = false)
        Spacer(Modifier.height(6.dp))
        KV("Target", Session.zdt(h.targetTime).let { "%s %02d:%02d".format(it.toLocalDate(), it.hour, it.minute) } + (if (h.note.isNotBlank()) " · ${h.note}" else ""))
        KV("Confidence", "${h.confidence} (%.0f%%) · event risk ${h.eventRisk.label}".format(h.confidenceValue * 100), confColor(h.confidence))
        h.confidenceNotes.forEach { Label("• $it", color = C.dim, size = 10.sp, mono = false) }
        KV("Data coverage", "%.0f%% of factor weight".format(h.coverage * 100), if (h.coverage >= 0.8) C.green else C.amber)
    }
    Card("Expected move & distribution (§18–§20)") {
        KV("1σ move", "±%.0f pts (%.2f%%) · vol %.1f%%".format(h.sigmaPts, h.sigmaPts / h.spot * 100, h.annualVolUsed * 100))
        KV("Model drift", "%+.0f pts (κ × score × σ)".format(h.driftPts))
        KV("Median / ≈68 % range", "${pts(h.expectedPrice)} · ${pts(h.rangeLow)} – ${pts(h.rangeHigh)}")
        KV("≈90 % range", "${pts(h.range90Low)} – ${pts(h.range90High)}")
        KV("Neutral band", "±%.0f pts (0.25σ)".format(h.neutralBand))
        if (h.pinWeight > 0) KV("Pin component", "${pts(h.pinStrike)} · weight %.0f%%".format(h.pinWeight * 100), C.amber)
        h.volSources.forEach { KV("· ${it.key}", it.value) }
        Spacer(Modifier.height(6.dp))
        Label("DISTRIBUTION AT TARGET", color = C.dim, size = 10.sp)
        h.buckets.forEach { b -> ProbBar(b.label, b.p, if (b.high <= h.spot) C.red else if (b.low >= h.spot) C.green else C.amber, labelWidth = 120) }
        if (h.path.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Label("PATH TO EXPIRY (median · ≈68 % range · ↑ / ↓)", color = C.dim, size = 10.sp)
            h.path.forEach { pp ->
                TableRow(Triple(pp.label, 1.4f, C.white), Triple(pts(pp.median), 0.8f, C.text),
                    Triple("${pts(pp.low)}–${pts(pp.high)}", 1.4f, C.text),
                    Triple("%.0f/%.0f".format(pp.pBull * 100, pp.pBear * 100), 0.7f, C.signed(pp.pBull - pp.pBear, 0.03)))
            }
        }
    }
    FactorTable(h)
    o.regime.weightAdjustments.filter { it.key.startsWith(h.id.short + " ·") }.takeIf { it.isNotEmpty() }?.let { adj ->
        Card("Regime weight adaptation · ${o.regime.regime.label}") {
            adj.forEach { KV(it.key.substringAfter("· "), it.value) }
            Label("Bounded to 0.6×–1.5× of the spec weight, then renormalised (§28).", color = C.dim, size = 9.sp, mono = false)
        }
    }
}

@Composable
private fun FactorTable(h: HorizonPrediction) {
    var open by remember { mutableStateOf<String?>(null) }
    Card("Factors · effective = direction × strength × freshness × reliability × weight") {
        fun f2(x: Double) = if (x >= 0.995) "1" else ".%02d".format((x * 100).toInt().coerceIn(0, 99))
        TableHeader("Factor" to 1.6f, "Wt" to 0.5f, "Dir" to 0.55f, "S·F·R" to 0.85f, "Eff" to 0.6f)
        h.factors.sortedByDescending { abs(it.effective) + if (it.reading.available) 0.0 else -1.0 }.forEach { c ->
            val r = c.reading
            Column(Modifier.fillMaxWidth().clickable { open = if (open == c.factor.name) null else c.factor.name }) {
                if (r.available) TableRow(
                    Triple(c.factor.label, 1.6f, C.white),
                    Triple("%.0f".format(c.usedWeight), 0.5f, if (abs(c.adjustedWeight - c.baseWeight) > 0.4) C.violet else C.text),
                    Triple("%+.0f".format(r.direction), 0.55f, C.signed(r.direction, 5.0)),
                    Triple("${f2(r.strength)}·${f2(r.freshness)}·${f2(r.reliability)}", 0.85f, if (r.freshness < 0.5) C.amber else C.dim),
                    Triple("%+.1f".format(c.effective), 0.6f, C.signed(c.effective, 0.5)),
                ) else TableRow(Triple(c.factor.label, 1.6f, C.dim), Triple("0", 0.5f, C.dim), Triple("MISSING", 1.4f, C.red), Triple("", 0.6f, C.dim))
                if (open == c.factor.name) Column(Modifier.padding(start = 8.dp, bottom = 6.dp)) {
                    KV("Weight base → regime → used", "%.0f → %.1f → %.1f %%".format(c.baseWeight, c.adjustedWeight, c.usedWeight))
                    if (r.summary.isNotBlank()) KV("Summary", r.summary)
                    KV("Source / age", "${r.source.ifBlank { "–" }} · ${ageText(r.ageSec)}")
                    r.details.forEach { KV(it.key, it.value) }
                }
            }
        }
        if (h.overlayNote.isNotBlank()) Label("+ ${h.overlayNote}", color = C.blue, size = 10.sp, mono = false)
        KV("Horizon score", "%+.1f".format(h.score), C.signed(h.score, 5.0))
        if (h.missing.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Label("Missing factors are removed and the remaining weights renormalised — never treated as neutral (§31):", color = C.dim, size = 9.sp, mono = false)
            h.missing.forEach { Label("• $it", color = C.amber, size = 10.sp, mono = false) }
        }
        Label("Tap a factor for its inputs. Violet weight = adapted to the current regime.", color = C.dim, size = 9.sp, mono = false)
    }
}

private fun ageText(sec: Double) = when {
    sec.isNaN() -> "age unknown"; sec < 120 -> "%.0fs".format(sec); sec < 7200 -> "%.0f min".format(sec / 60)
    sec < 172800 -> "%.1f h".format(sec / 3600); else -> "%.1f d".format(sec / 86400)
}

// =====================================================================================================================
//  Expiry — weekly and monthly options intelligence (§9, §11, §14, §17)
// =====================================================================================================================

@Composable
fun ExpiryScreen(ui: UiState) {
    val o = ui.output ?: return EmptyState(ui)
    if (o.weekly == null && o.monthly == null) Card("Expiry intelligence") { Label("Option chain unavailable.", color = C.dim) }
    o.weekly?.let { ExpiryCard(it, o.horizon(HorizonId.WEEKLY)) }
    val monthly = o.monthly
    if (monthly != null) ExpiryCard(monthly, o.horizon(HorizonId.MONTHLY))
    else if (o.weekly != null) Card("Monthly expiry") { Label("The next weekly expiry is also the monthly expiry — see above.", color = C.dim, size = 11.sp, mono = false) }
}

@Composable
private fun ExpiryCard(x: ExpiryIntel, h: HorizonPrediction?) {
    Card("${x.kind.label} expiry · ${x.expiry}", trailing = { Chip(x.regime.label.uppercase(), expiryRegimeColor(x.regime)) }) {
        KV("Days / sessions to expiry", "%.2f / %d".format(x.daysToExpiry, x.tradingDaysToExpiry))
        KV("Expected move (IV / straddle)", "±%.0f / ±%.0f pts".format(x.expectedMoveIv, x.expectedMoveStraddle))
        h?.let { KV("Model range (≈68 %)", "${pts(it.rangeLow)} – ${pts(it.rangeHigh)} · ↑%.0f ↓%.0f".format(it.bull * 100, it.bear * 100), dirColor(it.direction)) }
        KV("ATM ${pts(x.atmStrike)} IV / ΔIV", "%.1f%% / %+.1f pts".format(x.atmIv, x.ivChange), if (x.ivChange > 1) C.red else if (x.ivChange < -0.7) C.green else C.white)
        KV("OTM put / call IV · skew", "%.1f%% / %.1f%% · %+.1f".format(x.putIv, x.callIv, x.skew))
        KV("PCR (OI / ΔOI)", "%.2f / %s".format(x.pcr, if (x.pcrChange.isNaN()) "–" else "%.2f".format(x.pcrChange)))
        KV("Call writing / unwinding", "%,.0f / %,.0f".format(x.callWriting, x.callUnwinding))
        KV("Put writing / unwinding", "%,.0f / %,.0f".format(x.putWriting, x.putUnwinding))
        Spacer(Modifier.height(4.dp))
        KV("Support", x.support?.let { "${pts(it.strike)} · strength %.2f${if (it.note.isNotBlank()) " · ${it.note}" else ""}".format(it.strength) } ?: "–", C.green)
        x.majorSupport?.takeIf { it.strike != x.support?.strike }?.let { KV("Major put OI", "${pts(it.strike)} · %,.0f".format(it.oi)) }
        KV("Resistance", x.resistance?.let { "${pts(it.strike)} · strength %.2f${if (it.note.isNotBlank()) " · ${it.note}" else ""}".format(it.strength) } ?: "–", C.red)
        x.majorResistance?.takeIf { it.strike != x.resistance?.strike }?.let { KV("Major call OI", "${pts(it.strike)} · %,.0f".format(it.oi)) }
        x.pin?.let { p ->
            KV("Pin zone", "${pts(p.low)}–${pts(p.high)} (${pts(p.strike)}) · strength %.2f".format(p.strength), if (p.strength > 0.4) C.amber else C.text)
            p.reasons.forEach { Label("   · $it", color = C.dim, size = 10.sp) }
        }
        KV("Max pain · OI concentration", "${pts(x.maxPain)} · %.0f%%".format(x.oiConcentration * 100))
        x.breakout?.let { b ->
            KV("↑ above ${pts(b.upLevel)}", pct0(b.pUp), C.green)
            KV("↓ below ${pts(b.downLevel)}", pct0(b.pDown), C.red)
            KV("Stays inside", pct0(b.pInside), C.amber)
            Label(b.note, color = C.text, size = 10.sp, mono = false)
        }
        Spacer(Modifier.height(4.dp))
        Label("EXPIRY REGIME · ${x.regime.label}", color = C.dim, size = 10.sp, weight = FontWeight.Bold)
        x.regimeReasons.forEach { Label(it, color = C.text, size = 10.sp, mono = false) }
        x.notes.forEach { Label("⚠ $it", color = C.amber, size = 10.sp, mono = false) }
        Spacer(Modifier.height(6.dp))
        Label("OI BY STRIKE (calls | puts, ΔOI)", color = C.dim, size = 10.sp)
        TableHeader("CE ΔOI" to 0.9f, "CE OI" to 1f, "Strike" to 0.9f, "PE OI" to 1f, "PE ΔOI" to 0.9f)
        x.strikes.forEach { r ->
            val tag = when (r.strike) { x.atmStrike -> C.white; x.support?.strike -> C.green; x.resistance?.strike -> C.red; x.pin?.strike -> C.amber; else -> C.text }
            TableRow(Triple("%+,.0f".format(r.callChange), 0.9f, C.signed(-r.callChange, 1.0)), Triple("%,.0f".format(r.callOi), 1f, C.text),
                Triple(pts(r.strike), 0.9f, tag), Triple("%,.0f".format(r.putOi), 1f, C.text), Triple("%+,.0f".format(r.putChange), 0.9f, C.signed(r.putChange, 1.0)))
        }
        Label("Green ΔOI = put writing / call unwinding (supportive); red = call writing / put unwinding.", color = C.dim, size = 9.sp, mono = false)
    }
    SignalCard(x.signal)
}

// =====================================================================================================================
//  Strategy — valuation → structure → risk (§25, §26)
// =====================================================================================================================

@Composable
fun StrategyScreen(ui: UiState) {
    val o = ui.output ?: return EmptyState(ui)
    DecisionCard(o)
    val st = o.strategies
    Card("Prediction → structure") {
        KV("Intraday (${o.horizon(HorizonId.M60)?.id?.short ?: "1H"}) category", st.intradayCategory?.label ?: "–")
        KV("Weekly expiry category", st.weeklyCategory?.label ?: "–")
        KV("Monthly expiry category", st.monthlyCategory?.label ?: "–")
        st.notes.forEach { Label("• $it", color = C.amber, size = 10.sp, mono = false) }
        Label("Strong bullish → ITM/ATM call, bull call spread · mild bullish/range → bull put spread · strong bearish → ITM/ATM put, bear put spread · " +
            "neutral + high IV → iron condor / butterfly · volatility expansion + cheap IV → long straddle. Never naked selling.",
            color = C.dim, size = 9.sp, mono = false)
    }
    o.valuation.forEach { v ->
        var showAll by remember(v.kind) { mutableStateOf(false) }
        Card("Valuation · ${v.kind.label} ${v.expiry}") {
            KV("ATM IV vs model forecast vol", "%.1f%% vs %.1f%%".format(v.atmIv, v.forecastVol))
            Label(v.ivView, color = if (v.ivRatio > 1.15) C.amber else if (v.ivRatio < 0.9) C.green else C.text, size = 11.sp, mono = false)
            Spacer(Modifier.height(4.dp))
            TableHeader("Option" to 1.1f, "Ask" to 0.7f, "Fair" to 0.7f, "Edge" to 0.6f, "P(ITM)" to 0.6f, "Class" to 1.2f)
            val rows = v.rows.sortedBy { abs(it.strike - o.spot) }.let { if (showAll) it else it.take(12) }.sortedWith(compareBy({ it.type }, { it.strike }))
            rows.forEach { r ->
                val col = when (r.cls) {
                    OptionValueClass.ATTRACTIVE -> C.green; OptionValueClass.FAIR -> C.text; OptionValueClass.TOO_EXPENSIVE -> C.red
                    OptionValueClass.TOO_FAR_OTM, OptionValueClass.EXCESSIVE_THETA -> C.amber
                }
                TableRow(Triple("${r.strike.toInt()}${r.type}", 1.1f, if (r.type.name == "CE") C.green else C.red), Triple("%.1f".format(r.ask), 0.7f, C.text),
                    Triple("%.1f".format(r.fairValue), 0.7f, C.text), Triple("%+.0f%%".format(r.edgePct * 100), 0.6f, C.signed(r.edgePct, 0.05)),
                    Triple(pct0(r.pItm), 0.6f, C.text), Triple(r.cls.label, 1.2f, col))
            }
            Text(if (showAll) "Show fewer" else "Show all ${v.rows.size}", color = C.blue, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp).clickable { showAll = !showAll })
            Label("Fair = expected payoff at expiry under this expiry's model distribution (it carries the predicted direction and range).",
                color = C.dim, size = 9.sp, mono = false)
        }
    }
    var open by remember { mutableStateOf<Int?>(null) }
    Card("Candidate structures (ranked)") {
        if (st.candidates.isEmpty()) Label("No structures — option chain unavailable.", color = C.dim)
        st.candidates.take(24).forEachIndexed { i, c ->
            Column(Modifier.fillMaxWidth().clickable { open = if (open == i) null else i }.padding(vertical = 3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label(c.title, color = if (c.passedFilters) C.white else C.dim, size = 11.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 2)
                    Label("%.3f".format(c.score), color = if (c === st.best) C.green else C.text, size = 11.sp)
                }
                Label("${c.expiryKind.label} ${c.expiry} · ${if (c.horizon.intraday) "exit ${c.horizon.short}" else "hold to expiry"} · " +
                    (if (c.netPremium >= 0) "debit %.1f" else "credit %.1f").format(abs(c.netPremium)) +
                    " · max loss %.1f · P %.0f%% · EV %+.1f · fit %.1f".format(c.maxLoss, c.pProfit * 100, c.expectedPnl, c.fit), color = C.dim, size = 10.sp)
                if (open == i) CandidateDetail(c)
            }
        }
        Label("Grey = failed liquidity / spread / freshness / theta filters. Score = fit × (EV ÷ max loss) × √P(profit) × liquidity; per unit, net of costs.",
            color = C.dim, size = 9.sp, mono = false)
    }
}

@Composable
private fun CandidateDetail(c: StrategyCandidate) {
    Column(Modifier.padding(start = 8.dp, top = 4.dp)) {
        Label(c.rationale, color = C.text, size = 10.sp, mono = false)
        c.legs.forEach { l ->
            KV("${if (l.action == LegAction.BUY) "BUY" else "SELL"} ${l.strike.toInt()} ${l.type}",
                "₹%.2f · IV %.1f%% · Δ %.2f · OI %,.0f · spread %s".format(l.price, l.iv, l.delta, l.oi, if (l.spreadPct.isNaN()) "–" else "%.1f%%".format(l.spreadPct)))
        }
        KV("Max profit / loss", (if (c.maxProfit.isNaN()) "open" else "%.1f".format(c.maxProfit)) + " / %.1f".format(c.maxLoss))
        KV("Reward:risk · EV/risk", (if (c.riskReward.isNaN()) "open" else "%.2f".format(c.riskReward)) + " · %+.0f%%".format(c.returnOnRisk * 100))
        KV("Breakeven(s)", c.breakevens.joinToString(" / ") { pts(it) }.ifBlank { "–" })
        KV("P(profit) model / calibrated", "%.0f%% / %s".format(c.pProfit * 100, if (c.pProfitCalibrated.isNaN()) "–" else "%.0f%%".format(c.pProfitCalibrated * 100)))
        KV("EV gross / costs / net", "%+.1f / %.2f / %+.1f".format(c.expectedPnlGross, c.costPerUnit, c.expectedPnl))
        KV("Δ / θ per day / vega", "%+.2f / %+.1f / %+.1f".format(c.netDelta, c.netThetaPerDay, c.netVega))
        KV("Liquidity", "%.2f".format(c.liquidity))
        if (c.failures.isNotEmpty()) Label("Filters: " + c.failures.joinToString(), color = C.red, size = 10.sp, mono = false)
    }
}

// =====================================================================================================================
//  Market — regime evidence, FII, heavyweights, sectors, engine panels
// =====================================================================================================================

@Composable
fun MarketScreen(ui: UiState) {
    val o = ui.output ?: return EmptyState(ui)
    Card("Market regime (§13)", trailing = { Chip(o.regime.regime.label.uppercase(), regimeColor(o.regime.regime)) }) {
        o.regime.evidence.forEach { e ->
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Label(e.regime.label, color = if (e.regime == o.regime.regime) C.white else C.text, size = 11.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Label("%.0f%%".format(e.score * 100), color = if (e.score >= 0.55) C.green else C.dim, size = 11.sp)
            }
            if (e.met.isNotEmpty()) Label("✓ " + e.met.joinToString(" · "), color = C.green, size = 9.sp, mono = false)
            if (e.missed.isNotEmpty()) Label("✗ " + e.missed.joinToString(" · "), color = C.dim, size = 9.sp, mono = false)
        }
        Label("Event shock wins when its evidence is strong; otherwise the best regime above 55 %, else Mixed. The regime adapts factor weights within bounds.",
            color = C.dim, size = 9.sp, mono = false)
    }
    val f = o.fii
    Card("FII engine (§16)", trailing = { Chip(f.regime.label.uppercase(), when { f.regime.sign > 0 -> C.green; f.regime.sign < 0 -> C.red; else -> C.amber }) }) {
        if (!f.available) Label("FII data unavailable — the FII factor is removed from every horizon and the weights renormalised.", color = C.amber, size = 11.sp, mono = false)
        KV("Positioning score (weekly view)", "%+.0f".format(f.score), C.signed(f.score, 5.0))
        f.details.forEach { KV(it.key, it.value) }
        if (f.tags.isNotEmpty()) FlowChips(f.tags.map { it.replace('_', ' ') to tagColor(it) })
    }
    val hw = o.heavyweights
    Card("Heavyweight engine (§15)") {
        if (hw.fakeBreadth) Label("⚠ INDEX MOVE CARRIED BY HEAVYWEIGHTS — BREADTH WEAK", color = C.red, size = 11.sp, weight = FontWeight.Bold)
        if (hw.narrow) Label("⚠ NARROW: three stocks carry ≥ 70 % of the move", color = C.amber, size = 11.sp, weight = FontWeight.Bold)
        KV("Σ weight × return", "%+.1f pts".format(hw.totalContributionPts), C.signed(hw.totalContributionPts, 0.5))
        KV("Last 30 / 60 min", "%s / %s pts".format(if (hw.contribution30mPts.isNaN()) "–" else "%+.1f".format(hw.contribution30mPts),
            if (hw.contribution60mPts.isNaN()) "–" else "%+.1f".format(hw.contribution60mPts)))
        KV("Top 5 / Top 10", "%+.1f / %+.1f pts".format(hw.top5Pts, hw.top10Pts))
        KV("Advancers / Decliners", "${hw.advancers} / ${hw.decliners}")
        KV("Weights", hw.weightsSource)
        Spacer(Modifier.height(6.dp))
        Label("LEADERSHIP", color = C.dim, size = 10.sp)
        hw.leadership.take(8).forEach { l -> ScoreBar(l.group, (l.contributionPts / 40).coerceIn(-1.0, 1.0), "%+.1f pts · %+.2f%% · weight %.1f%%".format(l.contributionPts, l.changePct, l.weightPct)) }
        Spacer(Modifier.height(6.dp))
        TableHeader("Stock" to 1.4f, "Wt%" to 0.7f, "Chg%" to 0.8f, "Pts" to 0.8f)
        hw.rows.sortedByDescending { abs(it.contributionPts) }.take(12).forEach { r ->
            TableRow(Triple(r.symbol, 1.4f, C.white), Triple("%.1f".format(r.weightPct), 0.7f, C.text),
                Triple("%+.2f".format(r.changePct), 0.8f, C.signed(r.changePct, 0.0)), Triple("%+.1f".format(r.contributionPts), 0.8f, C.signed(r.contributionPts, 0.0)))
        }
    }
    Card("Sectors") {
        TableHeader("Sector" to 1.5f, "Day%" to 0.7f, "5d%" to 0.7f, "20d%" to 0.7f, "Pts" to 0.6f)
        o.sectors.forEach { s ->
            fun p(x: Double) = if (x.isNaN()) "–" else "%+.1f".format(x)
            TableRow(Triple(s.sector.label, 1.5f, C.white), Triple("%+.2f".format(s.changePct), 0.7f, C.signed(s.changePct, 0.0)),
                Triple(p(s.change5dPct), 0.7f, C.signed(s.change5dPct, 0.0)), Triple(p(s.change20dPct), 0.7f, C.signed(s.change20dPct, 0.0)),
                Triple("%+.1f".format(s.contributionPts), 0.6f, C.signed(s.contributionPts, 0.0)))
        }
    }
    Label("ENGINE PANELS (tap to expand)", color = C.dim, size = 10.sp, weight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
    o.signals.values.filter { !it.name.startsWith("Options ·") && it.name != "News" }.forEach { SignalCard(it) }
}

// =====================================================================================================================
//  Events — expectation engine (§12), event risk (§23), news intelligence
// =====================================================================================================================

private fun stageColor(st: EventStage) = when (st) {
    EventStage.RUMOUR, EventStage.POSSIBLE -> C.dim
    EventStage.LIKELY, EventStage.EXPECTED -> C.amber
    EventStage.CONFIRMED, EventStage.DEVELOPING -> C.blue
    EventStage.ESCALATING -> C.red
    EventStage.RESOLVING, EventStage.RESOLVED -> C.green
}

@Composable
fun EventsScreen(ui: UiState) {
    val o = ui.output ?: return EmptyState(ui)
    val er = o.eventRisk
    Card("Event risk · next 30 days (§23)") {
        Row {
            HorizonGroup.values().forEach { g ->
                Column(Modifier.weight(1f)) {
                    Label(g.short + if (g == HorizonGroup.H1) " today" else if (g == HorizonGroup.H2) " → weekly" else " → monthly", color = C.dim, size = 9.sp)
                    Label(er.level(g).label.uppercase(), color = riskColor(er.level(g)), size = 15.sp, weight = FontWeight.Bold)
                }
            }
        }
        er.notes.forEach { Label("• $it", color = C.amber, size = 10.sp, mono = false) }
        Spacer(Modifier.height(6.dp))
        er.upcoming.take(25).forEach { u ->
            Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                Label(u.date.substring(5), color = C.text, size = 10.sp, modifier = Modifier.weight(0.5f))
                Label(u.title, color = if (u.importance >= 3) C.white else C.text, size = 10.sp, modifier = Modifier.weight(2f), maxLines = 2, mono = false)
                Label("●".repeat(u.importance.coerceIn(1, 3)), color = if (u.importance >= 3) C.red else if (u.importance == 2) C.amber else C.dim, size = 10.sp, modifier = Modifier.weight(0.4f))
            }
        }
        Label("Event risk reduces confidence; it never reverses a prediction. Dates marked 'estimate'/'verify' are approximate — keep RBI MPC and results dates in Setup.",
            color = C.dim, size = 9.sp, mono = false)
    }
    Card("Expectation engine (§12) · expected → actual → surprise") {
        if (o.expectations.records.isEmpty()) Label("No major events or consensus inputs yet. Add consensus values (RBI, CPI, GDP, EPS) in Setup.", color = C.dim, size = 11.sp, mono = false)
        o.expectations.records.take(12).forEach { r ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Chip(r.channel.label, C.blue)
                    Chip(r.surpriseLabel, when { r.surprise > 0.1 -> C.green; r.surprise < -0.1 -> C.red; else -> C.dim })
                    Label("${r.interpretation.arrow} ${r.interpretation.label}", color = dirColor(r.interpretation), size = 11.sp, weight = FontWeight.Bold)
                }
                Label(r.title, color = C.white, size = 11.sp, mono = false, maxLines = 2)
                KV("Expected", r.expected)
                KV("Actual", r.actual)
                KV("Surprise / persistence", "%+.2f · ${r.persistence}".format(r.surprise))
                if (r.pricedIn > 0.01) KV("Already priced in", pct0(r.pricedIn))
                KV("Impact H1 / H2 / H3", HorizonGroup.values().joinToString(" / ") { "%+.2f".format(r.impact[it] ?: 0.0) })
            }
        }
        if (o.expectations.channels.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Label("CHANNEL IMPACT (feeds the matching factor)", color = C.dim, size = 10.sp)
            o.expectations.channels.forEach { (ch, m) -> KV(ch.label, HorizonGroup.values().joinToString(" / ") { "%+.2f".format(m[it] ?: 0.0) }) }
        }
    }
    Card("News impact by horizon") {
        NewsHorizon.values().forEach { h -> ScoreBar(h.label, o.newsHorizons[h] ?: 0.0) }
        KV("Analyst", ui.analystStatus.ifBlank { "–" })
        Label("Effective impact = event impact × unpriced × surprise × confidence. The AI describes events; it never issues buy/sell or call/put signals, and the market's reaction can overrule it.",
            color = C.dim, size = 10.sp, mono = false)
    }
    o.events.take(20).forEach { e ->
        val a = e.analysis
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Chip(e.stage.label.uppercase(), stageColor(e.stage))
                Chip(e.type.label, C.blue)
                if (a?.source?.startsWith("gemini") == true) Chip("AI", C.violet) else Chip("RULES", C.dim)
                Spacer(Modifier.weight(1f))
                Label("%+.2f".format(e.effectiveImpact), color = C.signed(e.effectiveImpact, 0.01), weight = FontWeight.Bold)
            }
            Label(e.title, color = C.white, size = 12.sp, mono = false)
            Label("${e.id} · ${e.articleIds.size} articles · ${e.sources.size} sources · first ${Session.hhmm(e.firstSeen)} · last info ${Session.hhmm(e.lastInfoAt)}",
                color = C.dim, size = 10.sp)
            KV("Surprise (new info)", "%+.2f".format(e.surprise), C.signed(e.surprise))
            KV("Priced in / unpriced", "%.0f%% / %.0f%%".format(e.pricedIn * 100, e.unpriced * 100))
            KV("Market reaction", "%+.2f%s".format(e.reaction.agreement,
                when { e.reaction.contradicted -> " · DISAGREES"; e.reaction.confirmed -> " · confirms"; else -> "" }),
                when { e.reaction.contradicted -> C.red; e.reaction.confirmed -> C.green; else -> C.text })
            if (e.flags.isNotEmpty()) FlowChips(e.flags.map { it.replace('_', ' ') to tagColor(it) })
        }
    }
}

// =====================================================================================================================
//  Shared cards
// =====================================================================================================================

fun statusColor(s: FeedStatus) = when (s) {
    FeedStatus.LIVE -> C.green; FeedStatus.MANUAL -> C.blue; FeedStatus.DEGRADED -> C.amber
    FeedStatus.STALE, FeedStatus.INVALID, FeedStatus.MISSING -> C.red
}

@Composable
fun DataErrorBanner(o: EngineOutput) {
    Card("Data error — no trade") {
        o.dataQuality.circuitBreaker.forEach { Label("✗ $it", color = C.red, size = 12.sp, weight = FontWeight.Bold) }
        Label("The circuit breaker blocks recommendations instead of filling gaps with assumptions.", color = C.dim, size = 10.sp, mono = false)
    }
}

@Composable
fun DataQualityCard(o: EngineOutput) {
    val q = o.dataQuality
    Card("Data quality %.0f%%".format(q.score * 100), trailing = {
        Chip(if (q.circuitBreaker.isEmpty()) "OK" else "BREAKER", if (q.circuitBreaker.isEmpty()) C.green else C.red)
    }) {
        TableHeader("Input" to 1.3f, "Status" to 1f, "Age" to 0.6f)
        q.feeds.forEach { f ->
            TableRow(Triple(f.name.removePrefix("Macro: "), 1.3f, if (f.critical) C.white else C.text),
                Triple(f.status.name + if (f.critical) " *" else "", 1f, statusColor(f.status)), Triple(ageText(f.ageSeconds).removePrefix("age "), 0.6f, C.dim))
            if (f.detail.isNotBlank() && f.status != FeedStatus.LIVE) Label("   ${f.detail}", color = C.dim, size = 10.sp, maxLines = 2)
        }
        q.warnings.forEach { Label("• $it", color = C.amber, size = 10.sp) }
        Label("* critical: missing/stale/invalid ⇒ DATA ERROR — NO TRADE. Non-critical feeds only lower freshness / remove their factor.",
            color = C.dim, size = 9.sp, mono = false)
    }
}

@Composable
fun GiftCard(g: com.niftyengine.engine.model.GiftNiftyReport, sig: com.niftyengine.engine.model.EngineSignal?) {
    val st = g.state
    val stateColor = when (st) {
        com.niftyengine.engine.model.GapState.EXTENDING, com.niftyengine.engine.model.GapState.HOLDING -> C.signed(g.actualGapPct, 0.0)
        com.niftyengine.engine.model.GapState.FADING, com.niftyengine.engine.model.GapState.FILLED -> C.amber
        com.niftyengine.engine.model.GapState.PRE_OPEN -> C.signed(g.impliedGapPct, 0.1)
        else -> C.dim
    }
    Card("GIFT Nifty · opening", trailing = { Chip(st.label.uppercase(), stateColor) }) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Label("IMPLIED GAP", color = C.dim, size = 10.sp)
                Label("%+.2f%%".format(g.impliedGapPct), color = C.signed(g.impliedGapPct, 0.1), size = 20.sp, weight = FontWeight.Bold)
                Label("open ≈ %,.0f".format(g.impliedOpen), color = C.text, size = 11.sp)
            }
            Column(Modifier.weight(1f)) {
                Label("GIFT NIFTY", color = C.dim, size = 10.sp)
                Label("%,.1f".format(g.last), color = C.white, size = 16.sp, weight = FontWeight.Bold)
                Label("%+.2f%% · %s".format(g.changePct, if (g.ageMinutes.isNaN()) "age ?" else "%.0f min ago".format(g.ageMinutes)),
                    color = if (g.ageMinutes > 60) C.amber else C.text, size = 11.sp)
            }
        }
        if (!g.actualGapPct.isNaN()) {
            Spacer(Modifier.height(6.dp))
            KV("Actual gap", "%+.2f%%".format(g.actualGapPct) + if (g.gapRealization.isNaN()) "" else " (%.0f%% of implied)".format(g.gapRealization * 100))
            if (!g.retracement.isNaN()) KV("Gap given back", "%.0f%%".format(g.retracement * 100), if (g.retracement >= 0.4) C.amber else C.text)
        }
        sig?.let { KV("Signal / confidence", "%+.2f · %.0f%%".format(it.score, it.confidence * 100), C.signed(it.score)) }
        g.notes.forEach { Label("• $it", color = C.amber, size = 10.sp, mono = false) }
        Label("GIFT feeds the H1 global-market factor before the open and fades out over the first hour.", color = C.dim, size = 9.sp, mono = false)
    }
}

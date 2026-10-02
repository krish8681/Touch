package com.niftyengine.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.niftyengine.engine.core.Session
import com.niftyengine.engine.model.ConfidenceLevel
import com.niftyengine.engine.model.Decision
import com.niftyengine.engine.model.EngineOutput
import com.niftyengine.engine.model.OptionCandidate
import kotlin.math.abs

fun confColor(c: ConfidenceLevel) = when (c) { ConfidenceLevel.HIGH -> C.green; ConfidenceLevel.MEDIUM -> C.amber; ConfidenceLevel.LOW -> C.red }
fun regimeColor(bias: Int) = when { bias > 0 -> C.green; bias < 0 -> C.red; else -> C.amber }

@Composable
fun DashboardScreen(ui: UiState) {
    val o = ui.output ?: return EmptyState(ui)
    val d = o.direction
    // Hero
    Card {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Label("NIFTY 50", color = C.dim, size = 11.sp, weight = FontWeight.Bold)
                Label("%,.2f".format(o.spot), color = C.white, size = 30.sp, weight = FontWeight.Bold)
            }
            Column(horizontalAlignment = Alignment.End) {
                Label("%+.2f%%".format(o.spotChangePct), color = C.signed(o.spotChangePct, 0.0), size = 18.sp, weight = FontWeight.Bold)
                Label(Session.hhmm(o.timestamp) + " IST", color = C.dim, size = 10.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        SpotChart(ui.chart)
    }
    // Direction
    Card("Direction · next ${o.expectedMove.horizonMinutes} min") {
        ProbBar("BULL", d.pBull, C.green)
        ProbBar("BEAR", d.pBear, C.red)
        ProbBar("RANGE", d.pRange, C.amber)
        Spacer(Modifier.height(8.dp))
        Row {
            Column(Modifier.weight(1f)) {
                Label("CONFIDENCE", color = C.dim, size = 10.sp)
                Label("${d.confidence} (%.0f%%)".format(d.confidenceValue * 100), color = confColor(d.confidence), size = 16.sp, weight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f)) {
                Label("REGIME", color = C.dim, size = 10.sp)
                Label(o.regime.regime.label, color = regimeColor(o.regime.regime.bias), size = 16.sp, weight = FontWeight.Bold)
            }
        }
        if (o.regime.reasons.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            o.regime.reasons.take(4).forEach { Label("• $it", color = C.dim, size = 11.sp) }
        }
    }
    // Expected move
    Card("Expected move") {
        val m = o.expectedMove
        Row {
            Column(Modifier.weight(1f)) {
                Label("CENTRAL", color = C.dim, size = 10.sp)
                Label("%+.0f pts".format(m.expectedMovePoints), color = C.signed(m.expectedMovePoints), size = 20.sp, weight = FontWeight.Bold)
                Label("%+.0f to %+.0f".format(m.moveLow, m.moveHigh), color = C.text, size = 11.sp)
            }
            Column(Modifier.weight(1f)) {
                Label("EXPECTED RANGE (±1σ)", color = C.dim, size = 10.sp)
                Label("%,.0f – %,.0f".format(m.rangeLow, m.rangeHigh), color = C.white, size = 16.sp, weight = FontWeight.Bold)
                Label("σ %.0f pts · vol %.1f%%".format(m.sigmaPoints, m.annualVolUsed * 100), color = C.text, size = 11.sp)
            }
        }
    }
    DecisionCard(o)
    o.options.best?.let { Card("Option candidate") { CandidateSummary(it) } }
    Card("Driver agreement") {
        KV("Agreement", "%.0f%%".format(d.driverAgreement * 100), if (d.driverAgreement > 0.7) C.green else C.amber)
        KV("Conflict", "${d.conflictLevel} (%.0f%%)".format(d.conflict * 100), confColor(when (d.conflictLevel) {
            ConfidenceLevel.HIGH -> ConfidenceLevel.LOW; ConfidenceLevel.LOW -> ConfidenceLevel.HIGH; else -> ConfidenceLevel.MEDIUM }))
        KV("Price confirmation", if (d.priceConfirmation) "YES" else "NO", if (d.priceConfirmation) C.green else C.red)
        KV("Derivative confirmation", if (d.derivativeConfirmation) "YES" else "NO", if (d.derivativeConfirmation) C.green else C.red)
    }
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
fun DecisionCard(o: EngineOutput) {
    val dec = o.decision
    val col = when (dec.decision) { Decision.TRADE -> C.green; Decision.WAIT -> C.amber; Decision.NO_TRADE -> C.red }
    Card("Decision") {
        Label(dec.decision.name.replace('_', ' '), color = col, size = 24.sp, weight = FontWeight.Bold)
        Label(dec.headline, color = C.white, size = 12.sp)
        Spacer(Modifier.height(6.dp))
        dec.checks.forEach { c ->
            Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                Label(if (c.passed) "✓" else "✗", color = if (c.passed) C.green else C.red, size = 12.sp, weight = FontWeight.Bold, modifier = Modifier.padding(end = 6.dp))
                Label(c.name, color = C.text, size = 11.sp, modifier = Modifier.weight(0.45f))
                Label(c.detail, color = C.dim, size = 11.sp, modifier = Modifier.weight(0.55f))
            }
        }
        Spacer(Modifier.height(6.dp))
        Label("Decision support only — not investment advice. Probabilities are model estimates and are not validated until the prediction log has enough outcomes.",
            color = C.dim, size = 9.sp, mono = false)
    }
}

@Composable
fun CandidateSummary(c: OptionCandidate) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Label("${c.strike.toInt()} ${c.type}", color = if (c.type.name == "CE") C.green else C.red, size = 20.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Label("₹%.1f".format(c.premium), color = C.white, size = 18.sp, weight = FontWeight.Bold)
    }
    Label("${c.moneyness} · score %.3f".format(c.score), color = C.dim, size = 11.sp)
    Spacer(Modifier.height(4.dp))
    KV("P(profit @ horizon)", "%.0f%%".format(c.probProfit * 100))
    KV("P(touch strike)", "%.0f%%".format(c.probReach * 100))
    KV("Expected value", "%+.1f (%+.1f%%)".format(c.expectedValue, c.expectedReturnPct), C.signed(c.expectedValue))
    KV("Breakeven spot", "%,.0f".format(c.breakevenSpot))
    KV("IV / Delta", "%.1f%% / %.2f".format(c.iv, c.delta))
    KV("Theta/day / Vega", "%.1f / %.1f".format(c.thetaPerDay, c.vega))
    KV("Liquidity", "OI %,.0f · Vol %,.0f · spread %.1f%%".format(c.oi, c.volume, c.spreadPct),
        if (c.liquidityFactor > 0.6 && c.spreadPct in 0.0..2.0) C.green else C.amber)
    val risk = when { c.thetaFactor < 0.8 || c.moneyness == "Far OTM" -> "HIGH"; c.moneyness == "OTM" -> "MEDIUM"; else -> "MODERATE" }
    KV("Risk", risk, if (risk == "HIGH") C.red else C.amber)
    if (c.filterFailures.isNotEmpty()) Label("Filters: " + c.filterFailures.joinToString(), color = C.red, size = 10.sp)
}

@Composable
fun FeedStatusCard(o: EngineOutput) {
    Card("Data feeds · ${o.dataSource}") {
        o.feedStatus.forEach { (k, v) -> KV(k, v, if (v.startsWith("✗")) C.red else C.text) }
    }
}

@Composable
fun DriversScreen(ui: UiState) {
    val o = ui.output ?: return EmptyState(ui)
    val d = o.direction
    Card("Driver analysis · ${o.regime.regimeClass} weights") {
        d.drivers.forEach { c ->
            ScoreBar(c.driver.label, c.score,
                "weight %.0f%% · data %.0f%% · persistence %.0f%%".format(c.weight, c.confidence * 100, c.persistence * 100))
        }
        Spacer(Modifier.height(6.dp))
        KV("Composite score", "%+.3f".format(d.directionalScore), C.signed(d.directionalScore))
        KV("Range evidence", "%.2f".format(d.rangeScore))
        KV("Driver agreement", "%.0f%%".format(d.driverAgreement * 100))
        KV("Conflict", "${d.conflictLevel} (%.0f%%)".format(d.conflict * 100))
    }
    if (d.conflicts.isNotEmpty()) Card("Conflicts") { d.conflicts.forEach { Label("⚠ $it", color = C.amber, size = 11.sp) } }
    Label("ENGINE SIGNALS (tap to expand)", color = C.dim, size = 10.sp, weight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
    o.signals.values.forEach { SignalCard(it) }
}

@Composable
fun OptionsScreen(ui: UiState) {
    val o = ui.output ?: return EmptyState(ui)
    val oa = o.options
    Card("Option engine") {
        KV("Preferred", oa.preferred?.let { if (it.name == "CE") "CALL (CE)" else "PUT (PE)" } ?: "–",
            if (oa.preferred?.name == "CE") C.green else C.red)
        KV("ATM IV", "%.1f%%".format(oa.atmIv))
        KV("Days to expiry", "%.2f".format(oa.daysToExpiry))
        oa.notes.forEach { Label("• $it", color = C.amber, size = 11.sp) }
    }
    oa.best?.let { Card("Best candidate") { CandidateSummary(it) } }
    o.signals["Options"]?.let { SignalCard(it) }
    Card("Ranked candidates") {
        TableHeader("Strike" to 1.3f, "Prem" to 0.9f, "Δ" to 0.7f, "P(pr)" to 0.8f, "EV%" to 0.9f, "Score" to 0.9f)
        oa.candidates.take(16).forEach { c ->
            val col = if (!c.passedFilters) C.dim else if (c.type.name == "CE") C.green else C.red
            TableRow(
                Triple("${c.strike.toInt()}${c.type}", 1.3f, col),
                Triple("%.1f".format(c.premium), 0.9f, C.text),
                Triple("%.2f".format(c.delta), 0.7f, C.text),
                Triple("%.0f%%".format(c.probProfit * 100), 0.8f, C.text),
                Triple("%+.0f".format(c.expectedReturnPct), 0.9f, C.signed(c.expectedReturnPct, 1.0)),
                Triple("%.3f".format(c.score), 0.9f, if (c.passedFilters) C.white else C.dim),
            )
        }
        Label("Grey rows failed hard filters (liquidity, spread, IV distortion, theta).", color = C.dim, size = 10.sp)
    }
}

@Composable
fun MarketScreen(ui: UiState) {
    val o = ui.output ?: return EmptyState(ui)
    val hw = o.heavyweights
    Card("Heavyweight contribution") {
        if (hw.fakeBreadth) Label("⚠ INDEX MOVE CARRIED BY HEAVYWEIGHTS — BREADTH WEAK", color = C.red, size = 11.sp, weight = FontWeight.Bold)
        KV("Total", "%+.1f pts".format(hw.totalContributionPts), C.signed(hw.totalContributionPts, 0.5))
        KV("Top 5 / Top 10", "%+.1f / %+.1f pts".format(hw.top5Pts, hw.top10Pts))
        KV("Advancers / Decliners", "${hw.advancers} / ${hw.decliners}")
        KV("Top-5 dependence", "%.0f%%".format(hw.heavyweightDependence * 100))
        KV("Weights", hw.weightsSource)
        Spacer(Modifier.height(6.dp))
        TableHeader("Stock" to 1.4f, "Wt%" to 0.7f, "Chg%" to 0.8f, "Pts" to 0.8f)
        hw.rows.sortedByDescending { abs(it.contributionPts) }.take(15).forEach { r ->
            TableRow(
                Triple(r.symbol, 1.4f, C.white),
                Triple("%.1f".format(r.weightPct), 0.7f, C.text),
                Triple("%+.2f".format(r.changePct), 0.8f, C.signed(r.changePct, 0.0)),
                Triple("%+.1f".format(r.contributionPts), 0.8f, C.signed(r.contributionPts, 0.0)),
            )
        }
    }
    Card("Sectors") {
        TableHeader("Sector" to 1.5f, "Chg%" to 0.8f, "RS" to 0.7f, "Brdth" to 0.7f, "Pts" to 0.7f)
        o.sectors.forEach { s ->
            TableRow(
                Triple(s.sector.label, 1.5f, C.white),
                Triple("%+.2f".format(s.changePct), 0.8f, C.signed(s.changePct, 0.0)),
                Triple("%+.2f".format(s.relativeStrength), 0.7f, C.signed(s.relativeStrength, 0.0)),
                Triple("%+.1f".format(s.breadth), 0.7f, C.signed(s.breadth, 0.0)),
                Triple("%+.1f".format(s.contributionPts), 0.7f, C.signed(s.contributionPts, 0.0)),
            )
        }
    }
    listOf("Market structure", "Breadth", "Futures", "India VIX", "Global risk", "INR/crude/rates", "FPI/DII")
        .mapNotNull { o.signals[it] }.forEach { SignalCard(it) }
}

@Composable
fun NewsScreen(ui: UiState) {
    val o = ui.output ?: return EmptyState(ui)
    o.signals["News"]?.let { SignalCard(it) }
    if (o.events.isEmpty()) Card { Label("No recent news events.", color = C.dim) }
    o.events.forEach { e ->
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Chip(e.type.label, C.blue)
                if (e.divergence) Chip("DIVERGENCE", C.red)
                Spacer(Modifier.weight(1f))
                Label("%+.2f".format(e.effectiveImpact), color = C.signed(e.effectiveImpact, 0.01), weight = FontWeight.Bold)
            }
            Label(e.headline, color = C.white, size = 12.sp, mono = false)
            Spacer(Modifier.height(4.dp))
            Label("${Session.hhmm(e.firstSeen)} · ${e.sources.size} source(s): ${e.sources.take(4).joinToString()}", color = C.dim, size = 10.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Label("dir %+.2f".format(e.direction), color = C.signed(e.direction), size = 10.sp)
                Label("mag %.2f".format(e.magnitude), color = C.text, size = 10.sp)
                Label("conf %.2f".format(e.confidence), color = C.text, size = 10.sp)
                Label("decay %.2f".format(e.decay), color = C.text, size = 10.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Label("reaction %+.2f".format(e.marketReaction), color = C.signed(e.marketReaction), size = 10.sp)
                Label("persist %.2f".format(e.reactionPersistence), color = C.text, size = 10.sp)
                Label(e.duration.name.lowercase(), color = C.text, size = 10.sp)
                if (e.expected != "–") Label("exp ${e.expected} act ${e.actual}", color = C.amber, size = 10.sp)
            }
            if (e.sectors.isNotEmpty()) Label("Sectors: " + e.sectors.joinToString { it.label }, color = C.dim, size = 10.sp)
        }
    }
}

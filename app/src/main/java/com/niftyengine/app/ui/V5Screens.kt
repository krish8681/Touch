package com.niftyengine.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.niftyengine.engine.core.Session
import com.niftyengine.engine.model.Decision
import com.niftyengine.engine.model.EngineOutput
import com.niftyengine.engine.model.ExpectationState
import com.niftyengine.engine.model.GroupStat
import com.niftyengine.engine.model.LegAction
import com.niftyengine.engine.model.PrimaryRegime
import com.niftyengine.engine.model.QualityTier
import com.niftyengine.engine.model.Scenario
import com.niftyengine.engine.model.ShockLevel
import com.niftyengine.engine.model.StrategyCandidate
import com.niftyengine.engine.model.StrategyType
import kotlin.math.abs

/* v5 screens — the pipeline as the user sees it:
 * decision object → regime → scenarios → expectation → shock → strategy + risk → quality → shadow execution. */

private fun pct0(x: Double) = if (x.isNaN()) "–" else "%.0f%%".format(x * 100)
private fun rupees(x: Double) = if (x.isNaN()) "unlimited" else "₹%,.0f".format(x)

fun actionColor(d: Decision) = when (d) {
    Decision.TRADE -> C.green; Decision.PAPER_TRADE -> C.blue; Decision.WAIT -> C.amber
    Decision.NO_TRADE -> C.red; Decision.DATA_ERROR -> C.red
}

fun primaryColor(p: PrimaryRegime) = when (p) {
    PrimaryRegime.TREND_UP -> C.green; PrimaryRegime.TREND_DOWN -> C.red; PrimaryRegime.RANGE -> C.blue
    PrimaryRegime.VOLATILITY_EXPANSION, PrimaryRegime.EVENT_DRIVEN -> C.violet
    PrimaryRegime.REVERSAL_RISK -> C.amber; PrimaryRegime.CONFLICT -> C.red
}

fun tierColor(t: QualityTier) = when (t) { QualityTier.HIGH -> C.green; QualityTier.MEDIUM -> C.amber; QualityTier.LOW -> C.red; QualityTier.NONE -> C.dim }

fun shockColor(l: ShockLevel) = when (l) { ShockLevel.NONE -> C.dim; ShockLevel.MINOR -> C.blue; ShockLevel.SIGNIFICANT -> C.amber; ShockLevel.MAJOR -> C.red }

private fun scenarioColor(s: Scenario) = when (s) {
    Scenario.STRONG_UP -> C.green; Scenario.MILD_UP -> C.green.copy(alpha = 0.6f); Scenario.RANGE -> C.amber
    Scenario.MILD_DOWN -> C.red.copy(alpha = 0.6f); Scenario.STRONG_DOWN -> C.red
}

/** Labelled 0..1 bar with a wide label column (scenario names, quality components). */
@Composable
fun WideBar(label: String, p: Double, color: Color, trailing: String = "%3.0f%%".format(p * 100), labelWidth: Int = 128) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Label(label, color = C.text, size = 11.sp, modifier = Modifier.width(labelWidth.dp), maxLines = 1)
        Box(Modifier.weight(1f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(C.s2)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(p.toFloat().coerceIn(0f, 1f)).clip(RoundedCornerShape(6.dp)).background(color))
        }
        Label(trailing, color = color, size = 12.sp, weight = FontWeight.Bold, modifier = Modifier.width(56.dp).padding(start = 8.dp))
    }
}

// ------------------------------------------------------------------ decision object

@Composable
fun DecisionStateCard(o: EngineOutput) {
    val ds = o.decisionState
    val plan = o.strategy
    val c = plan.chosen
    Card("Decision state · v5", trailing = {
        Chip(o.regimeV5.primary.label.uppercase(), primaryColor(o.regimeV5.primary))
    }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Label(o.decision.decision.name.replace('_', ' '), color = actionColor(o.decision.decision), size = 24.sp, weight = FontWeight.Bold)
                Label(if (c != null) "${c.type.label} · ${c.instrument}" else plan.preferredByRegime.takeIf { it != StrategyType.NO_TRADE }
                    ?.let { "Wants ${it.label} — not yet" } ?: "No strategy", color = C.white, size = 12.sp, mono = false)
            }
            Column(horizontalAlignment = Alignment.End) {
                Label("QUALITY", color = C.dim, size = 9.sp)
                Label(if (o.quality.tier == QualityTier.NONE) "–" else "%.0f%% %s".format(o.quality.score * 100, o.quality.tier.name),
                    color = tierColor(o.quality.tier), size = 14.sp, weight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(6.dp))
        Row {
            Column(Modifier.weight(1f)) {
                Label("DIRECTION", color = C.dim, size = 9.sp)
                Label(ds.direction, color = when (ds.direction) { "UP" -> C.green; "DOWN" -> C.red; else -> C.amber }, size = 16.sp, weight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f)) {
                Label("RAW / CALIBRATED", color = C.dim, size = 9.sp)
                Label("%.0f%% / %s".format(ds.rawProbability * 100, ds.calibratedProbability?.let { "%.0f%%".format(it * 100) } ?: "–"),
                    color = C.white, size = 14.sp, weight = FontWeight.Bold)
                Label(ds.calibration.lowercase(), color = if (ds.calibration == "FULL") C.green else C.amber, size = 9.sp)
            }
            Column(Modifier.weight(1f)) {
                Label("CONFIDENCE", color = C.dim, size = 9.sp)
                Label("%.0f%%".format(ds.confidence * 100), color = confColor(o.direction.confidence), size = 14.sp, weight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(6.dp))
        Row {
            Column(Modifier.weight(1f)) {
                Label("EXPECTATION", color = C.dim, size = 9.sp)
                Label("%+.2f (Δ %+.2f)".format(ds.futureExpectation, ds.expectationChange), color = C.signed(ds.futureExpectation), size = 12.sp)
            }
            Column(Modifier.weight(1f)) {
                Label("INFO SHOCK", color = C.dim, size = 9.sp)
                Label("%.0f%% %s".format(ds.informationShock * 100, o.shock.level.name.lowercase()), color = shockColor(o.shock.level), size = 12.sp)
            }
            Column(Modifier.weight(1f)) {
                Label("LOTS / RISK", color = C.dim, size = 9.sp)
                Label(if (ds.lots > 0) "${ds.lots} · ₹%,.0f".format(ds.riskAtStop) else "–", color = C.white, size = 12.sp)
            }
        }
        Label("MODEL HEALTH %.0f/100 · %s".format(o.health.score, o.health.tier.label), color = healthColor(o.health.tier), size = 11.sp,
            weight = FontWeight.Bold)
        if (ds.stop.isNotBlank()) Label("Stop ${ds.stop} · target ${ds.target}", color = C.text, size = 10.sp, mono = false)
        o.decision.reasons.take(3).forEach { Label("• $it", color = C.dim, size = 10.sp, mono = false) }
        Label("AI reads the news; probabilities, strategy, risk and execution are deterministic code. Shadow mode places no real orders.",
            color = C.dim, size = 9.sp, mono = false)
    }
}

// ------------------------------------------------------------------ model health (v5.1)

fun healthColor(t: com.niftyengine.engine.model.HealthTier) = when (t) {
    com.niftyengine.engine.model.HealthTier.ELIGIBLE -> C.green
    com.niftyengine.engine.model.HealthTier.SHADOW_ONLY -> C.amber
    com.niftyengine.engine.model.HealthTier.NO_SIGNAL -> C.red
}

@Composable
fun ModelHealthCard(o: EngineOutput) {
    val h = o.health
    if (h.components.isEmpty()) return
    Card("Model health %.0f/100".format(h.score), trailing = { Chip(h.tier.label.uppercase(), healthColor(h.tier)) }) {
        h.components.forEach { c ->
            WideBar(c.name, c.value, if (c.value >= 0.75) C.green else if (c.value >= 0.5) C.amber else C.red,
                trailing = "%.0f%%".format(c.value * 100), labelWidth = 150)
            Label("   weight %.0f · ".format(c.weight) + c.detail, color = C.dim, size = 9.sp, mono = false)
        }
        h.notes.forEach { Label("• $it", color = C.amber, size = 10.sp, mono = false) }
        Label("≥ %.0f eligible to trade · %.0f–%.0f shadow only (PAPER TRADE at best) · below %.0f no signal.".format(h.eligibleAt, h.shadowAt, h.eligibleAt - 1, h.shadowAt),
            color = C.dim, size = 9.sp, mono = false)
    }
}

// ------------------------------------------------------------------ regime

@Composable
fun RegimeV5Card(o: EngineOutput) {
    val r = o.regimeV5
    Card("Regime · ${r.primary.label}", trailing = { Chip("${r.weightTable} WEIGHTS", C.blue) }) {
        WideBar("Regime quality", r.quality, if (r.quality >= 0.6) C.green else if (r.quality >= 0.45) C.amber else C.red)
        WideBar("Stability", r.stability, C.blue)
        if (r.blocks.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Label("INFORMATION BLOCKS", color = C.dim, size = 10.sp)
            FlowChips(r.blocks.map { b ->
                "${b.block} ${when (b.sign) { 1 -> "▲"; -1 -> "▼"; else -> "·" }} %+.2f".format(b.score) to when (b.sign) { 1 -> C.green; -1 -> C.red; else -> C.dim }
            })
            if (r.blockConflict > 0.05) Label("Split: %.0f%% of opinionated weight on the losing side".format(r.blockConflict * 100),
                color = if (r.blockConflict >= 0.3) C.red else C.text, size = 10.sp)
        }
        r.reasons.take(4).forEach { Label("• $it", color = C.text, size = 10.sp, mono = false) }
        if (r.primary == PrimaryRegime.CONFLICT) Label("Conflict ⇒ WAIT. The engine does not force UP or DOWN when the blocks disagree.",
            color = C.amber, size = 10.sp, mono = false)
    }
}

// ------------------------------------------------------------------ scenarios

@Composable
fun ScenarioCard(o: EngineOutput) {
    val s = o.scenarios
    if (s.scenarios.isEmpty()) return
    Card("Next ${s.horizonMinutes} min · scenarios", trailing = {
        Chip(when (s.calibration) { "FULL" -> "CALIBRATED"; "PARTIAL" -> "PARTLY CALIBRATED"; "VIA_DIRECTION" -> "VIA DIRECTION CAL"; else -> "MODEL" },
            if (s.calibration == "NONE") C.amber else C.green)
    }) {
        s.scenarios.forEach { sc ->
            WideBar(sc.label, sc.probability, scenarioColor(sc.scenario))
        }
        Spacer(Modifier.height(4.dp))
        KV("Range band / breakout band", "±%.0f / ±%.0f pts".format(s.rangeBand, s.breakoutBand))
        KV("Breakout share up / down", "%s / %s".format(pct0(s.breakoutShareUp), pct0(s.breakoutShareDown)))
        KV("Mean move by scenario", s.scenarios.joinToString(" · ") { "%+.0f".format(it.meanMove) })
        s.notes.forEach { Label("• $it", color = C.amber, size = 10.sp, mono = false) }
        Label("Same 'UP' can be a slow grind (spread) or a breakout (long option) — the scenario shape picks the strategy.",
            color = C.dim, size = 9.sp, mono = false)
    }
}

// ------------------------------------------------------------------ future expectation

@Composable
fun ExpectationCard(o: EngineOutput) {
    val e = o.expectation
    val warn = e.state == ExpectationState.BULL_REVERSAL_WARNING || e.state == ExpectationState.BEAR_REVERSAL_WARNING
    Card("Future expectation", trailing = { Chip(e.state.name.replace('_', ' '), if (warn) C.red else when (e.state) {
        ExpectationState.IMPROVING -> C.green; ExpectationState.DETERIORATING -> C.red; ExpectationState.CONFIRMING -> C.blue; else -> C.dim }) }) {
        ScoreBar("Current state (price / breadth / sectors)", e.current)
        ScoreBar("Expected state (what is priced next)", e.expected)
        ScoreBar("Change in expectation (30 min)", e.change)
        Label(e.state.label, color = if (warn) C.red else C.text, size = 11.sp, weight = if (warn) FontWeight.Bold else FontWeight.Normal, mono = false)
        e.notes.forEach { Label("• $it", color = C.amber, size = 10.sp, mono = false) }
        if (e.components.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Label("WHAT THE MARKET EXPECTS · confidence %.0f%%".format(e.confidence * 100), color = C.dim, size = 10.sp)
            e.components.forEach { c -> ScoreBar(c.name, c.score, c.note.takeIf { it.isNotBlank() }) }
        }
        if (e.pending.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Label("UNRESOLVED EVENTS", color = C.dim, size = 10.sp)
            e.pending.take(4).forEach { p ->
                Label("• ${p.stage.label} · ${p.title.take(60)} — " + (if (p.probability.isNaN()) "p ?" else "p %.0f%%".format(p.probability * 100)) +
                    (if (p.probabilityChange.isNaN()) "" else " (%+.0f pts)".format(p.probabilityChange * 100)),
                    color = C.text, size = 10.sp, mono = false)
            }
        }
    }
}

// ------------------------------------------------------------------ information shock

@Composable
fun ShockCard(o: EngineOutput) {
    val s = o.shock
    Card("Information shock", trailing = { Chip(s.level.name, shockColor(s.level)) }) {
        WideBar("Shock score", s.score, shockColor(s.level))
        ScoreBar("Shock direction", s.direction)
        Label(s.headline, color = C.text, size = 11.sp, mono = false)
        s.sources.take(5).forEach { src ->
            Label("• ${src.kind.label}: ${src.detail}", color = if (src.magnitude >= 0.35) C.amber else C.dim, size = 10.sp, mono = false)
        }
        Label("Expected vs actual, in units of normal. A shock widens the move distribution and re-plans the scenario tails immediately.",
            color = C.dim, size = 9.sp, mono = false)
    }
}

// ------------------------------------------------------------------ strategy + risk

@Composable
fun StrategyLegs(c: StrategyCandidate) {
    TableHeader("Leg" to 1.4f, "Fill" to 0.8f, "IV" to 0.6f, "Δ" to 0.6f, "Sprd" to 0.6f)
    c.legs.forEach { l ->
        TableRow(
            Triple("${if (l.action == LegAction.BUY) "BUY" else "SELL"} ${l.strike.toInt()}${l.type}", 1.4f, if (l.action == LegAction.BUY) C.green else C.red),
            Triple("%.1f".format(l.price), 0.8f, C.white), Triple("%.1f".format(l.iv), 0.6f, C.text),
            Triple("%.2f".format(l.delta), 0.6f, C.text), Triple(if (l.spreadPct < 0) "–" else "%.1f%%".format(l.spreadPct), 0.6f, C.text),
        )
    }
}

@Composable
fun StrategyMetrics(c: StrategyCandidate) {
    KV(if (c.netPremium >= 0) "Net debit / unit" else "Net credit / unit", "₹%.2f".format(abs(c.netPremium)))
    KV("Max profit / max loss (expiry)", "${if (c.maxProfit.isNaN()) "unlimited" else "₹%.1f".format(c.maxProfit)} / ₹%.1f".format(c.maxLoss))
    KV("Breakeven (expiry)", c.breakevens.joinToString(" · ") { "%,.0f".format(it) })
    KV("P(profit) @ ${c.holdMinutes} min, net", pct0(c.probProfit), if (c.probProfit >= 0.5) C.green else C.amber)
    KV("EV net / unit", "%+.2f (%+.1f%% of max loss)".format(c.expectedValue, c.returnOnRisk * 100), C.signed(c.expectedValue))
    KV("Expected R:R", if (c.riskReward.isNaN()) "–" else "%.2f".format(c.riskReward))
    KV("Costs / unit (round trip, all legs)", "₹%.2f".format(c.costPerUnit))
    KV("Liquidity (worst leg)", pct0(c.liquidity), if (c.liquidity >= 0.5) C.green else C.red)
    if (c.issues.isNotEmpty()) Label("Issues: " + c.issues.joinToString(), color = C.red, size = 10.sp)
}

@Composable
fun StrategyRiskCard(o: EngineOutput) {
    val plan = o.strategy
    val c = plan.chosen
    val r = o.risk
    Card("Strategy & risk plan", trailing = { Chip(plan.type.name.replace('_', ' '), if (c != null) C.green else C.dim) }) {
        plan.rationale.forEach { Label("• $it", color = C.text, size = 10.sp, mono = false) }
        if (c == null) {
            Label("No structure selected this cycle.", color = C.dim, size = 11.sp)
            return@Card
        }
        Spacer(Modifier.height(4.dp))
        Label(c.instrument, color = C.white, size = 16.sp, weight = FontWeight.Bold)
        StrategyLegs(c)
        Spacer(Modifier.height(4.dp))
        StrategyMetrics(c)
        Spacer(Modifier.height(6.dp))
        Label("RISK ENGINE (deterministic)", color = C.dim, size = 10.sp)
        KV("Size", if (r.lots > 0) "${r.lots} lot(s) · ${r.quantity} qty" else "–", if (r.lots > 0) C.white else C.red)
        KV("Risk at stop / budget", "₹%,.0f / ₹%,.0f".format(r.riskAtStop, r.riskBudget))
        KV("Worst case (expiry)", rupees(r.worstCaseLoss))
        KV("Capital required", "₹%,.0f".format(r.capitalRequired))
        KV("Today P&L (shadow)", "₹%,.0f".format(r.dailyPnl), C.signed(r.dailyPnl, 1.0))
        r.exit?.let { x ->
            KV("Stop", x.stopText, C.red)
            KV("Target", x.targetText, C.green)
            KV("Time exit", Session.hhmm(x.timeExitAt) + " IST")
            x.emergencyRules.forEach { Label("  emergency: $it", color = C.dim, size = 9.sp, mono = false) }
        }
        r.checks.forEach { ck ->
            Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                Label(if (ck.passed) "✓" else "✗", color = if (ck.passed) C.green else C.red, size = 11.sp, weight = FontWeight.Bold, modifier = Modifier.padding(end = 6.dp))
                Label(ck.name, color = C.text, size = 10.sp, modifier = Modifier.weight(0.4f))
                Label(ck.detail, color = C.dim, size = 10.sp, modifier = Modifier.weight(0.6f))
            }
        }
        r.notes.forEach { Label("• $it", color = C.amber, size = 10.sp, mono = false) }
    }
}

@Composable
fun QualityCard(o: EngineOutput) {
    val q = o.quality
    if (q.components.isEmpty()) return
    Card("Trade quality %.0f%%".format(q.score * 100), trailing = { Chip(q.tier.name + if (q.passed) " · PASS" else " · FAIL", tierColor(q.tier)) }) {
        q.components.forEach { c ->
            WideBar(c.name, c.value, if (c.value < c.floor) C.red else if (c.value >= 0.75) C.green else C.amber,
                trailing = "%.0f%%".format(c.value * 100))
            Label("   ${c.detail} · floor " + "%.0f%%".format(c.floor * 100), color = C.dim, size = 9.sp)
        }
        if (q.trackRecord != 1.0) KV("Track record (shadow)", "×%.2f".format(q.trackRecord))
        q.notes.forEach { Label("• $it", color = C.amber, size = 10.sp, mono = false) }
        Label("Geometric mean of probability edge, confidence, regime quality, liquidity and R:R — every component must clear its floor.",
            color = C.dim, size = 9.sp, mono = false)
    }
}

// ------------------------------------------------------------------ market tab: relative state

@Composable
fun RelativeStateCard(o: EngineOutput) {
    val n = o.normalized
    if (n.readings.isEmpty()) return
    Card("Relative state · vs normal", trailing = { Chip(if (n.daysOfHistory > 0) "${n.daysOfHistory} DAYS" else "PRIORS", C.blue) }) {
        n.readings.take(18).forEach { r ->
            val hot = !r.multiple.isNaN() && r.multiple >= 2.0 || abs(r.relative) >= 0.25 && r.multiple.isNaN()
            KV(r.name, r.display, if (hot) C.amber else C.white)
        }
        Label("Raw values are compared with their own normal (20-session baselines, India VIX / fair carry / daily candles as priors).",
            color = C.dim, size = 9.sp, mono = false)
    }
}

// ------------------------------------------------------------------ options tab: strategy candidates

@Composable
fun StrategyCandidatesCard(o: EngineOutput) {
    val cands = o.strategy.candidates
    if (cands.isEmpty()) return
    Card("Strategy candidates") {
        Label("Each structure priced over the scenario distribution, net of all-leg costs. Chosen: ${o.strategy.type.label}.",
            color = C.dim, size = 10.sp, mono = false)
        TableHeader("Structure" to 1.6f, "P(pr)" to 0.7f, "EV" to 0.7f, "EV/risk" to 0.8f, "R:R" to 0.6f)
        cands.forEach { c ->
            TableRow(
                Triple(c.instrument.removePrefix("NIFTY "), 1.6f, if (c.type == o.strategy.type) C.green else if (c.feasible) C.white else C.dim),
                Triple(pct0(c.probProfit), 0.7f, C.text),
                Triple("%+.1f".format(c.expectedValue), 0.7f, C.signed(c.expectedValue, 0.1)),
                Triple("%+.0f%%".format(c.returnOnRisk * 100), 0.8f, C.signed(c.returnOnRisk, 0.01)),
                Triple(if (c.riskReward.isNaN()) "–" else "%.1f".format(c.riskReward), 0.6f, C.text),
            )
        }
    }
}

// ------------------------------------------------------------------ shadow tab

/** Expectancy, profit factor and drawdown first; win rate is secondary. */
@Composable
fun StatsBlock(st: com.niftyengine.engine.model.TradeStats) {
    fun money(x: Double) = if (x.isNaN()) "–" else "₹%,.0f".format(x)
    KV("Expectancy / trade", money(st.expectancy), C.signed(if (st.expectancy.isNaN()) 0.0 else st.expectancy, 1.0))
    KV("Profit factor", if (st.profitFactor.isNaN()) (if (st.trades > 0) "no losses yet" else "–") else "%.2f".format(st.profitFactor),
        if (st.profitFactor.isNaN()) C.text else C.signed(st.profitFactor - 1, 0.05))
    KV("Max drawdown", money(st.maxDrawdown))
    KV("Avg win / avg loss", "${money(st.avgWin)} / ${money(st.avgLoss)}" + if (st.payoff.isNaN()) "" else " (payoff %.2f)".format(st.payoff))
    KV("Average R multiple", if (st.avgR.isNaN()) "–" else "%+.2f".format(st.avgR))
    KV("Sharpe (daily, annualised)", if (st.sharpe.isNaN()) "needs ≥ 5 trading days (${st.days})" else "%.2f".format(st.sharpe))
    KV("Win rate (secondary)", pct0(st.winRate))
}

@Composable
private fun GroupTable(title: String, rows: List<GroupStat>) {
    if (rows.isEmpty()) return
    Spacer(Modifier.height(6.dp))
    Label(title, color = C.dim, size = 10.sp)
    TableHeader("Group" to 1.4f, "N" to 0.4f, "Exp ₹" to 0.8f, "PF" to 0.5f, "R" to 0.5f, "Win" to 0.5f)
    rows.take(8).forEach { g ->
        TableRow(Triple(g.key.replace('_', ' '), 1.4f, C.white), Triple("${g.n}", 0.4f, C.text),
            Triple("%,.0f".format(g.avgPnl), 0.8f, C.signed(g.avgPnl, 1.0)),
            Triple(if (g.profitFactor.isNaN()) "–" else "%.2f".format(g.profitFactor), 0.5f, if (g.profitFactor.isNaN()) C.dim else C.signed(g.profitFactor - 1, 0.05)),
            Triple("%+.2f".format(g.avgR), 0.5f, C.signed(g.avgR, 0.01)), Triple(pct0(g.winRate), 0.5f, C.text))
    }
}

@Composable
fun ShadowScreen(ui: UiState, onReset: () -> Unit, onShareDecision: () -> Unit) {
    val o = ui.output ?: return EmptyState(ui)
    val sh = o.shadow
    var confirmReset by remember { mutableStateOf(false) }
    Card("Shadow mode · execution", trailing = { Chip(if (sh.enabled) "ON · NO REAL ORDERS" else "OFF", if (sh.enabled) C.blue else C.dim) }) {
        Label("Every TRADE / PAPER TRADE the full pipeline approves is executed virtually: filled at the quoted ask/bid, marked to the chain each cycle, " +
            "closed on stop, target, time or an emergency rule, net of all charges. Use it to measure the whole pipeline before live trading.",
            color = C.dim, size = 10.sp, mono = false)
        Spacer(Modifier.height(6.dp))
        Row {
            Column(Modifier.weight(1f)) {
                Label("NET P&L", color = C.dim, size = 9.sp)
                Label("₹%,.0f".format(sh.totalPnl), color = C.signed(sh.totalPnl, 1.0), size = 18.sp, weight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f)) {
                Label("TODAY", color = C.dim, size = 9.sp)
                Label("₹%,.0f".format(sh.todayPnl), color = C.signed(sh.todayPnl, 1.0), size = 18.sp, weight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f)) {
                Label("TRADES · WIN", color = C.dim, size = 9.sp)
                Label("${sh.trades} · ${pct0(sh.winRate)}", color = C.white, size = 18.sp, weight = FontWeight.Bold)
            }
        }
        StatsBlock(sh.stats)
        sh.events.forEach { Label("• $it", color = C.blue, size = 10.sp, mono = false) }
        Text(if (confirmReset) "Tap again to clear the shadow book" else "Reset shadow book", color = C.red, fontSize = 12.sp,
            modifier = Modifier.padding(top = 6.dp).clickable { if (confirmReset) { onReset(); confirmReset = false } else confirmReset = true })
    }
    Card("Benchmark · does v5 beat a naive trade?") {
        Label(sh.benchmarkLabel + " — what v5's strategy and strike choice add, after costs.", color = C.dim, size = 10.sp, mono = false)
        val m = sh.stats; val b = sh.benchmark
        TableHeader("" to 1.2f, "v5" to 1f, "Benchmark" to 1f)
        @Composable fun row(name: String, a: String, bb: String, better: Boolean?) = TableRow(Triple(name, 1.2f, C.text),
            Triple(a, 1f, when (better) { true -> C.green; false -> C.red; null -> C.white }), Triple(bb, 1f, C.white))
        fun money(x: Double) = if (x.isNaN()) "–" else "₹%,.0f".format(x)
        fun num(x: Double) = if (x.isNaN()) "–" else "%.2f".format(x)
        row("Trades", "${m.trades}", "${b.trades}", null)
        row("Net P&L", money(m.totalPnl), money(b.totalPnl), if (b.trades == 0) null else m.totalPnl > b.totalPnl)
        row("Expectancy", money(m.expectancy), money(b.expectancy), if (m.expectancy.isNaN() || b.expectancy.isNaN()) null else m.expectancy > b.expectancy)
        row("Profit factor", num(m.profitFactor), num(b.profitFactor), if (m.profitFactor.isNaN() || b.profitFactor.isNaN()) null else m.profitFactor > b.profitFactor)
        row("Max drawdown", money(m.maxDrawdown), money(b.maxDrawdown), if (b.trades == 0) null else m.maxDrawdown < b.maxDrawdown)
        row("Win rate", pct0(m.winRate), pct0(b.winRate), null)
        Label("If v5 does not beat this after costs over enough trades, the extra complexity is not earning its keep.", color = C.dim, size = 9.sp, mono = false)
    }
    Card("Open positions (${sh.open.size})") {
        if (sh.open.isEmpty()) Label("Flat.", color = C.dim, size = 11.sp)
        sh.open.forEach { p ->
            val pnl = (p.markValue - p.entryValue) * p.lots * p.lotSize
            Label(p.instrument, color = C.white, size = 13.sp, weight = FontWeight.Bold)
            KV("Opened", "${Session.hhmm(p.openedAt)} · ${p.strategy.label} × ${p.lots} · ${p.decision.replace('_', ' ')}")
            KV("Entry → mark (per unit)", "%.2f → %.2f".format(p.entryValue, p.markValue))
            KV("Unrealised (gross)", "₹%,.0f".format(pnl), C.signed(pnl, 1.0))
            KV("Stop / target value", "%.2f / %.2f".format(p.stopValue, p.targetValue))
            KV("Time exit", Session.hhmm(p.timeExitAt))
            KV("Entry context", "${p.regime} · quality ${p.qualityTier} · p %.0f%%".format(p.probability * 100))
        }
    }
    Card("Recent shadow trades") {
        if (sh.recent.isEmpty()) Label("No closed shadow trades yet.", color = C.dim, size = 11.sp)
        else {
            TableHeader("Time" to 0.7f, "Instrument" to 1.6f, "Exit" to 0.9f, "₹ net" to 0.9f)
            sh.recent.take(20).forEach { t ->
                TableRow(Triple(Session.hhmm(t.closedAt), 0.7f, C.text), Triple(t.position.instrument.removePrefix("NIFTY "), 1.6f, C.white),
                    Triple(t.reason.substringBefore(':').take(9), 0.9f, if (t.reason == "TARGET") C.green else if (t.reason == "STOP") C.red else C.amber),
                    Triple("%,.0f".format(t.pnl), 0.9f, C.signed(t.pnl, 1.0)))
            }
        }
    }
    Card("Learning · what works") {
        Label("Rolling statistics from shadow outcomes — which strategies, regimes, quality tiers and probability ranges actually pay. " +
            "Strategies with enough trades feed their track record back into the quality gate.", color = C.dim, size = 10.sp, mono = false)
        GroupTable("BY STRATEGY", sh.byStrategy)
        GroupTable("BY REGIME AT ENTRY", sh.byRegime)
        GroupTable("BY QUALITY TIER", sh.byQuality)
        GroupTable("BY PROBABILITY", sh.byProbability)
        GroupTable("BY EXPECTATION STATE", sh.byExpectation)
        GroupTable("BY EXIT REASON", sh.byExitReason)
        if (sh.trades == 0) Label("Appears after the first closed shadow trades.", color = C.dim, size = 10.sp)
    }
    Card("Decision object (JSON)") {
        Label(com.niftyengine.app.store.PrettyJson.encodeToString(com.niftyengine.engine.model.DecisionState.serializer(), o.decisionState),
            color = C.text, size = 9.sp)
        Text("Share decision JSON", color = C.blue, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp).clickable { onShareDecision() })
    }
}

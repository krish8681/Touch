package com.niftyengine.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.niftyengine.app.ui.C
import com.niftyengine.app.ui.ChartPoint
import com.niftyengine.app.ui.DashboardScreen
import com.niftyengine.app.ui.DriversScreen
import com.niftyengine.app.ui.MarketScreen
import com.niftyengine.app.ui.NewsScreen
import com.niftyengine.app.ui.ShadowScreen
import com.niftyengine.app.ui.NiftyTheme
import com.niftyengine.app.ui.OptionsScreen
import com.niftyengine.app.ui.UiState
import com.niftyengine.engine.EngineConfig
import com.niftyengine.engine.NiftyDirectionEngine
import com.niftyengine.engine.core.Session
import com.niftyengine.engine.model.InstrumentData
import com.niftyengine.engine.model.MarketSnapshot
import com.niftyengine.engine.sim.SimulatedMarket
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * Every screen must render whatever the feeds deliver: first cycle after login, weekend/holiday, missing chain,
 * futures or stocks, NaN prices. A composition exception kills the whole app, so this is a crash test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UiRobustnessTest {
    @get:Rule val compose = createComposeRule()

    private fun nanify(d: InstrumentData) = d.copy(last = Double.NaN, prevClose = Double.NaN, intraday = emptyList())

    private fun cases(): Map<String, MarketSnapshot> {
        val sim = SimulatedMarket(seed = 5, startDate = LocalDate.of(2026, 9, 29))
        repeat(40) { sim.collect(0) }
        val s = sim.collect(0)
        val sunday = LocalDate.of(2026, 10, 4).atTime(11, 0).atZone(Session.IST).toInstant().toEpochMilli()
        return mapOf(
            "normal" to s,
            "no intraday" to s.copy(nifty = s.nifty.copy(intraday = emptyList())),
            "no chain/futures/stocks" to s.copy(optionChain = null, futures = null, constituents = emptyMap(), sectors = emptyMap(), vix = null, bankNifty = null),
            "empty chain" to s.copy(optionChain = s.optionChain?.copy(rows = emptyList())),
            "weekend" to s.copy(timestamp = sunday),
            // headlines often contain '%': they must never end up inside a format pattern
            "headline with %" to s.copy(news = s.news + listOf(
                com.niftyengine.engine.model.NewsItem("pct1", "Sensex crashes 2%, Nifty falls 1.5% as FPIs pull out ₹8,000 crore", "Reuters", s.timestamp - 120_000),
                com.niftyengine.engine.model.NewsItem("pct2", "India CPI inflation rises to 6.2% against estimate of 5.6%", "Economic Times", s.timestamp - 60_000))),
            // v5.1 point-in-time: future-dated critical inputs, inputs from different moments, future series/news
            "future NIFTY" to s.copy(nifty = s.nifty.copy(asOf = s.timestamp + 300_000)),
            "future chain" to s.copy(optionChain = s.optionChain?.copy(asOf = s.timestamp + 300_000)),
            "mismatched chain" to s.copy(optionChain = s.optionChain?.copy(asOf = s.timestamp - 200_000)),
            "future bars + news" to s.copy(
                nifty = s.nifty.copy(intraday = s.nifty.intraday + com.niftyengine.engine.model.Candle(s.timestamp + 60_000, 1.0, 1.0, 1.0, 1.0)),
                news = s.news + com.niftyengine.engine.model.NewsItem("fut", "Nifty jumps 3% tomorrow", "Reuters", s.timestamp + 600_000)),
            "NaN everywhere" to s.copy(nifty = nanify(s.nifty), vix = s.vix?.let(::nanify), bankNifty = null,
                constituents = s.constituents.mapValues { nanify(it.value) }, sectors = s.sectors.mapValues { nanify(it.value) },
                futures = s.futures?.copy(last = Double.NaN, openInterest = Double.NaN), optionChain = null, giftNifty = null),
        )
    }

    @Test fun allScreensSurviveDegradedData() {
        val screens = listOf<Pair<String, @androidx.compose.runtime.Composable (UiState) -> Unit>>(
            "home" to { DashboardScreen(it) }, "drivers" to { DriversScreen(it) }, "options" to { OptionsScreen(it) },
            "market" to { MarketScreen(it) }, "news" to { NewsScreen(it) }, "shadow" to { ShadowScreen(it, {}, {}) },
        )
        var current by androidx.compose.runtime.mutableStateOf<Pair<Int, UiState>?>(null)
        compose.setContent {
            current?.let { (i, ui) -> NiftyTheme { Column(Modifier.fillMaxWidth().background(C.bg).padding(12.dp)) { screens[i].second(ui) } } }
        }
        for ((name, snap) in cases()) for (marketOpenRequired in listOf(true, false)) {
            val out = runCatching { NiftyDirectionEngine(EngineConfig(requireMarketOpen = marketOpenRequired)).process(snap) }
                .getOrElse { throw AssertionError("engine crashed on '$name'", it) }
            val ui = UiState(output = out, running = true, chart = listOf(ChartPoint(out.timestamp, out.spot, out.direction.pBull, out.direction.pBear)))
            screens.indices.forEach { i ->
                try {
                    compose.runOnIdle { current = i to ui }
                    compose.waitForIdle()
                } catch (e: Throwable) {
                    throw AssertionError("${screens[i].first} screen crashed on '$name' (requireMarketOpen=$marketOpenRequired): $e", e)
                }
            }
        }
    }

    /**
     * v5.1.2 crashed on the device when a Log row was opened: a headline with '%' ("% f") went into a format pattern in
     * the audit trail. Render the audit trail of a REAL logged record, then the same record with hostile text everywhere.
     */
    @Test fun auditTrailSurvivesPercentInEveryTextField() {
        val snap = cases().getValue("headline with %")
        val out = NiftyDirectionEngine(EngineConfig(requireMarketOpen = false)).process(snap)
        val real = com.niftyengine.engine.engines.PredictionLogger(com.niftyengine.engine.engines.InMemoryPredictionStore()).record(out)
        val pct = "Nifty rises 0.5% from lows, % f %s %d 100% %"
        val hostile = real.copy(
            regime = "RANGE %s", regimeReasons = listOf(pct), failedChecks = listOf(pct), circuitBreaker = listOf(pct),
            feedStatus = mapOf(pct to pct), pitViolations = listOf(pct), instrument = pct, strategy = "BUY_CALL %d",
            expectationState = pct, healthTier = pct, calibrationLevel = pct, primaryRegime = pct, qualityTier = pct,
            criticalData = pct, snapshotId = pct, inputTimestamps = mapOf(pct to 1L), config = mapOf(pct to pct),
            signals = mapOf(pct to com.niftyengine.engine.engines.SignalAudit(0.1, 0.5, listOf(pct))),
            newsHorizons = mapOf(pct to 0.1), scenarioProbs = mapOf(pct to 0.2),
            events = listOf(com.niftyengine.engine.engines.EventAudit("E1 %s", pct, "CONFIRMED %", "gemini:% f", 0.9, 0.4, 0.6, 0.4, 0.6,
                0.1, 0.5, 0.05, mapOf(pct to 0.1), listOf(pct))),
            outcomes = listOf(com.niftyengine.engine.engines.Outcome(30, 22_500.0, 20.0, 22_510.0, 22_480.0, realized = 1)),
            strategyEv = 1.0, strategyProbProfit = 0.6, strike = 22_500.0, optionType = "CE %", premium = 50.0,
        )
        var current by androidx.compose.runtime.mutableStateOf<com.niftyengine.engine.engines.PredictionRecord?>(null)
        compose.setContent { current?.let { r -> NiftyTheme { Column(Modifier.fillMaxWidth().background(C.bg)) { com.niftyengine.app.ui.AuditDetail(r) } } } }
        for ((name, r) in listOf("real record (% headlines)" to real, "hostile text" to hostile)) {
            try { compose.runOnIdle { current = r }; compose.waitForIdle() }
            catch (e: Throwable) { throw AssertionError("audit trail crashed on $name: $e", e) }
        }
    }
}

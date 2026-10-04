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
            "NaN everywhere" to s.copy(nifty = nanify(s.nifty), vix = s.vix?.let(::nanify), bankNifty = null,
                constituents = s.constituents.mapValues { nanify(it.value) }, sectors = s.sectors.mapValues { nanify(it.value) },
                futures = s.futures?.copy(last = Double.NaN, openInterest = Double.NaN), optionChain = null, giftNifty = null),
        )
    }

    @Test fun allScreensSurviveDegradedData() {
        val screens = listOf<Pair<String, @androidx.compose.runtime.Composable (UiState) -> Unit>>(
            "home" to { DashboardScreen(it) }, "drivers" to { DriversScreen(it) }, "options" to { OptionsScreen(it) },
            "market" to { MarketScreen(it) }, "news" to { NewsScreen(it) },
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
}

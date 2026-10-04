package com.niftyengine.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.niftyengine.app.store.AppSettings
import com.niftyengine.app.ui.C
import com.niftyengine.app.ui.ChartPoint
import com.niftyengine.app.ui.DashboardScreen
import com.niftyengine.app.ui.DriversScreen
import com.niftyengine.app.ui.MarketScreen
import com.niftyengine.app.ui.NewsScreen
import com.niftyengine.app.ui.NiftyTheme
import com.niftyengine.app.ui.OptionsScreen
import com.niftyengine.app.ui.SettingsScreen
import com.niftyengine.app.ui.ShadowScreen
import com.niftyengine.app.ui.StatsCard
import com.niftyengine.app.ui.UiState
import com.niftyengine.engine.NiftyDirectionEngine
import com.niftyengine.engine.engines.HistoricalReplayEngine
import com.niftyengine.engine.engines.ReplayMode
import com.niftyengine.engine.sim.SimulatedMarket
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/** Renders each screen with simulator data to build/outputs/roborazzi as PNG files (visual check, not an assertion). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h2400dp-xxhdpi")
class ScreenshotTest {
    private fun state(): UiState {
        val sim = SimulatedMarket(seed = 11, startDate = LocalDate.of(2026, 9, 29))
        val engine = NiftyDirectionEngine()
        val chart = ArrayList<ChartPoint>()
        var out = engine.process(sim.collect(0))
        repeat(260) {
            out = engine.process(sim.collect(0))
            chart += ChartPoint(out.timestamp, out.spot, out.direction.pBull, out.direction.pBear)
        }
        return UiState(output = out, running = true, chart = chart)
    }

    private fun shot(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        captureRoboImage("build/outputs/roborazzi/$name.png") {
            NiftyTheme { Column(Modifier.fillMaxWidth().background(C.bg).padding(12.dp)) { content() } }
        }
    }

    @Test fun screens() {
        val ui = state()
        shot("1_home") { DashboardScreen(ui) }
        shot("2_drivers") { DriversScreen(ui) }
        shot("3_options") { OptionsScreen(ui) }
        shot("4_market") { MarketScreen(ui) }
        shot("5_news") { NewsScreen(ui) }
        shot("5b_shadow") { ShadowScreen(ui, {}, {}) }
        shot("7_settings") { SettingsScreen(AppSettings(), {}, {}) }
        val sim = SimulatedMarket(seed = 21, startDate = LocalDate.of(2026, 9, 29))
        val snaps = List(370) { sim.collect(0) }
        val r = HistoricalReplayEngine().run(snaps, ReplayMode.FULL_INFORMATION)
        shot("6_replay_stats") { StatsCard("Replay result", r.summaries) }
    }
}

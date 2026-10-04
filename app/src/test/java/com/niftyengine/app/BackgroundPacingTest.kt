package com.niftyengine.app

import com.niftyengine.app.store.AppSettings
import com.niftyengine.app.store.DataMode
import com.niftyengine.app.ui.IDLE_POLL_MS
import com.niftyengine.app.ui.inActiveWindow
import com.niftyengine.app.ui.nextDelayMs
import com.niftyengine.engine.core.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class BackgroundPacingTest {
    private fun ist(s: String) = LocalDateTime.parse(s).atZone(Session.IST).toInstant().toEpochMilli()

    @Test fun pollsFastOnlyAroundMarketHours() {
        assertTrue(inActiveWindow(ist("2026-10-05T08:45:00"))) // Monday, GIFT pre-open reading
        assertTrue(inActiveWindow(ist("2026-10-05T12:00:00")))
        assertTrue(inActiveWindow(ist("2026-10-05T15:45:00")))
        assertFalse(inActiveWindow(ist("2026-10-05T08:44:00")))
        assertFalse(inActiveWindow(ist("2026-10-05T15:46:00")))
        assertFalse(inActiveWindow(ist("2026-10-04T11:00:00"))) // Sunday

        val live = AppSettings(mode = DataMode.LIVE_PUBLIC, refreshSeconds = 30)
        assertEquals(30_000L, nextDelayMs(live, ist("2026-10-05T10:00:00")))
        assertEquals(IDLE_POLL_MS, nextDelayMs(live, ist("2026-10-05T22:00:00")))
        val sim = AppSettings(mode = DataMode.SIMULATED, simSecondsPerMinute = 2)
        assertEquals(2_000L, nextDelayMs(sim, ist("2026-10-05T22:00:00")))
    }
}

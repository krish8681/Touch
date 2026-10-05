package com.niftyengine.app

import android.app.NotificationManager
import android.content.Intent
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EngineServiceTest {
    @Test fun runsInForegroundWithWakeLockUntilStopped() {
        val app = RuntimeEnvironment.getApplication() as NiftyApp
        val controller = app.controller // starts the loop (simulator) and asks for the service
        assertTrue(controller.ui.value.running)
        assertEquals(EngineService::class.java.name, shadowOf(app).nextStartedService?.component?.className)

        val svc = Robolectric.buildService(EngineService::class.java, Intent(app, EngineService::class.java)).create().startCommand(0, 1)
        val shadow = shadowOf(svc.get())
        assertFalse("must be a foreground service", shadow.isStoppedBySelf)
        assertNotNull("ongoing notification", shadow.lastForegroundNotification)
        assertTrue(app.getSystemService(NotificationManager::class.java).getNotificationChannel(EngineService.CHANNEL) != null)
        val lock = ShadowPowerManager.getLatestWakeLock()
        assertTrue("partial wake lock held", lock != null && lock.isHeld)

        // Activity gone ≠ engine gone: the controller is application-scoped.
        assertTrue(app.controller === controller && controller.ui.value.running)

        svc.startCommand(0, 2).get().onStartCommand(Intent(app, EngineService::class.java).setAction("com.niftyengine.app.STOP_ENGINE"), 0, 3)
        assertFalse(controller.ui.value.running)
        svc.destroy()
        assertFalse("wake lock released", lock!!.isHeld)
    }
}

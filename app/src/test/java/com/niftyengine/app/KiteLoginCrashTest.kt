package com.niftyengine.app

import android.content.Intent
import android.webkit.WebView
import com.niftyengine.app.store.DataMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KiteLoginCrashTest {
    @Test fun tokenParsingNeverThrows() {
        assertEquals("abc123XYZabc123XYZ", KiteLoginActivity.tokenFrom("https://127.0.0.1/?action=login&type=login&status=success&request_token=abc123XYZabc123XYZ"))
        assertEquals("abc123XYZabc123XYZ", KiteLoginActivity.tokenFrom("  abc123XYZabc123XYZ \n"))
        // URLs that used to crash Uri.getQueryParameter ("not a hierarchical URI")
        listOf("about:blank", "intent:#Intent;scheme=kite;end", "data:text/html,hi", "mailto:x@y.z", "", "https://kite.zerodha.com/connect/login?v=3&api_key=k")
            .forEach { assertNull(it, KiteLoginActivity.tokenFrom(it)) }
        assertTrue(KiteLoginActivity.cancelled("https://127.0.0.1/?status=cancelled"))
    }

    @Test fun loginActivityHandlesOddUrlsAndReturnsToken() {
        val ctl = Robolectric.buildActivity(KiteLoginActivity::class.java,
            Intent(RuntimeEnvironment.getApplication(), KiteLoginActivity::class.java).putExtra(KiteLoginActivity.EXTRA_API_KEY, "k")).setup()
        val a = ctl.get()
        val web = shadowOf(a).contentView as WebView
        val client = shadowOf(web).webViewClient
        client.onPageStarted(web, "about:blank", null)
        client.onPageStarted(web, null, null)
        assertFalse(a.isFinishing)
        client.onPageStarted(web, "https://127.0.0.1/?status=success&request_token=tok123tok123tok123", null)
        assertTrue(a.isFinishing)
        assertEquals("tok123tok123tok123", shadowOf(a).resultIntent.getStringExtra(KiteLoginActivity.EXTRA_REQUEST_TOKEN))
        ctl.pause().stop().destroy()
    }

    @Test fun settingsChangesDuringCyclesDoNotCrash() {
        val app = RuntimeEnvironment.getApplication() as NiftyApp
        val c = app.controller
        val modes = listOf(DataMode.SIMULATED, DataMode.LIVE_KITE, DataMode.SIMULATED, DataMode.LIVE_PUBLIC, DataMode.SIMULATED)
        repeat(3) {
            modes.forEach { m ->
                c.updateSettings(c.settings.value.copy(mode = m, kiteApiKey = "", kiteAccessToken = "", minProbability = 0.6 + it * 0.01))
                c.refreshNow()
                ShadowLooper.idleMainLooper()
            }
        }
        Thread.sleep(1500); ShadowLooper.idleMainLooper()
        assertEquals(DataMode.SIMULATED, c.settings.value.mode)
        assertNull("no crash/internal error recorded", CrashLog.pending(app)?.readText())
        c.stop()
    }

    @Test fun crashLogIsWrittenAndDismissable() {
        val app = RuntimeEnvironment.getApplication()
        CrashLog.write(app, Thread.currentThread(), IllegalStateException("boom"), fatal = true)
        val txt = CrashLog.pending(app)!!.readText()
        assertTrue(txt.contains("CRASH") && txt.contains("boom") && txt.contains("Android"))
        CrashLog.write(app, Thread.currentThread(), RuntimeException("minor"), fatal = false)
        assertTrue("fatal report kept", CrashLog.pending(app)!!.readText().contains("boom"))
        CrashLog.dismiss(app)
        assertNull(CrashLog.pending(app))
    }
}

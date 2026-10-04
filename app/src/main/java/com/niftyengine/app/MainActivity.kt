package com.niftyengine.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.niftyengine.app.store.AppSettings
import com.niftyengine.app.ui.C
import com.niftyengine.app.ui.DashboardScreen
import com.niftyengine.app.ui.DriversScreen
import com.niftyengine.app.ui.Label
import com.niftyengine.app.ui.LogScreen
import com.niftyengine.app.ui.EngineController
import com.niftyengine.app.ui.MarketScreen
import com.niftyengine.app.ui.NewsScreen
import com.niftyengine.app.ui.NiftyTheme
import com.niftyengine.app.ui.OptionsScreen
import com.niftyengine.app.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private val vm: EngineController get() = (application as NiftyApp).controller

    private val kiteLogin = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringExtra(KiteLoginActivity.EXTRA_REQUEST_TOKEN)?.let { vm.completeKiteLogin(it) }
    }
    /** Re-checked on every resume: Android may kill a battery-optimised app's background work despite the service. */
    private var batteryRestricted by mutableStateOf(false)

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private enum class Tab(val icon: String, val label: String) {
        HOME("◉", "Home"), DRIVERS("≡", "Drivers"), OPTIONS("◈", "Options"), MARKET("▤", "Market"),
        NEWS("✉", "News"), LOG("◷", "Log"), SETTINGS("⚙", "Setup")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            NiftyTheme {
                val ui by vm.ui.collectAsStateWithLifecycle()
                val settings by vm.settings.collectAsStateWithLifecycle()
                var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
                LaunchedEffect(settings.keepScreenOn) {
                    if (settings.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                Column(Modifier.fillMaxSize().background(C.bg).systemBarsPadding()) {
                    TopBar(ui.output?.dataSource ?: settings.mode.label, ui.running, ui.busy, ui.error,
                        onToggle = { if (ui.running) vm.stop() else vm.start() }, onRefresh = { vm.refreshNow() })
                    if (settings.runInBackground && batteryRestricted) {
                        Row(Modifier.fillMaxWidth().background(C.amber.copy(alpha = 0.15f)).clickable { requestBatteryExemption() }
                            .padding(horizontal = 14.dp, vertical = 8.dp)) {
                            Label("Battery optimisation may stop the engine in the background — tap to allow unrestricted battery",
                                color = C.amber, size = 11.sp, weight = FontWeight.Bold, mono = false)
                        }
                    }
                    if (settings.kiteLoginNeeded()) {
                        Row(Modifier.fillMaxWidth().background(C.amber.copy(alpha = 0.15f)).clickable {
                            if (settings.kiteApiKey.isNotBlank() && settings.kiteApiSecret.isNotBlank()) startKiteLogin(settings) else tab = Tab.SETTINGS
                        }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                            Label("Kite login needed for today's session — tap to log in (using NSE/Yahoo until then)",
                                color = C.amber, size = 11.sp, weight = FontWeight.Bold, mono = false)
                        }
                    }
                    val scroll = rememberScrollState()
                    LaunchedEffect(tab) { scroll.scrollTo(0) }
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(horizontal = 12.dp, vertical = 10.dp)) {
                        when (tab) {
                            Tab.HOME -> DashboardScreen(ui)
                            Tab.DRIVERS -> DriversScreen(ui)
                            Tab.OPTIONS -> OptionsScreen(ui)
                            Tab.MARKET -> MarketScreen(ui)
                            Tab.NEWS -> NewsScreen(ui)
                            Tab.LOG -> LogScreen(ui, vm, onExport = ::exportLog, onShare = ::shareFile)
                            Tab.SETTINGS -> SettingsScreen(settings, onSave = { vm.updateSettings(it); tab = Tab.HOME }, onKiteLogin = ::startKiteLogin)
                        }
                    }
                    Row(Modifier.fillMaxWidth().background(C.s1).padding(vertical = 6.dp)) {
                        Tab.values().forEach { t ->
                            val sel = t == tab
                            Column(Modifier.weight(1f).clickable { tab = t }, horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(t.icon, color = if (sel) C.green else C.dim, fontSize = 18.sp)
                                Text(t.label, color = if (sel) C.green else C.dim, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        batteryRestricted = !getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
    }

    @android.annotation.SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        runCatching { startActivity(direct) }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    private fun startKiteLogin(s: AppSettings) {
        vm.updateSettings(s)
        kiteLogin.launch(Intent(this, KiteLoginActivity::class.java).putExtra(KiteLoginActivity.EXTRA_API_KEY, s.kiteApiKey))
    }

    private fun shareFile(f: java.io.File) {
        if (!f.exists()) return
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = if (f.name.endsWith(".csv")) "text/csv" else "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Share ${f.name}"))
    }

    private fun exportLog() {
        val f = vm.exportLogFile()
        if (!f.exists()) return
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Export prediction log"))
    }
}

@Composable
private fun TopBar(source: String, running: Boolean, busy: Boolean, error: String?, onToggle: () -> Unit, onRefresh: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(C.s1).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).clip(CircleShape).background(C.green), contentAlignment = Alignment.Center) {
                Text("N", color = C.bg, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Label("NIFTY Direction Engine", color = C.white, size = 14.sp, weight = FontWeight.Bold, mono = false)
                Label("v4.3 · $source", color = C.dim, size = 9.sp, maxLines = 1)
            }
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (busy) C.amber else if (running) C.green else C.dim))
            Spacer(Modifier.width(12.dp))
            Text("↻", color = C.blue, fontSize = 20.sp, modifier = Modifier.clickable { onRefresh() }.padding(horizontal = 6.dp))
            Text(if (running) "❚❚" else "▶", color = if (running) C.amber else C.green, fontSize = 16.sp,
                modifier = Modifier.clickable { onToggle() }.padding(horizontal = 6.dp))
        }
        if (error != null) {
            Spacer(Modifier.height(4.dp))
            Label("⚠ $error", color = C.red, size = 10.sp, maxLines = 2)
        }
    }
}

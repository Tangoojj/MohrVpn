package com.example

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.AppLog
import com.example.core.ChildProcesses
import com.example.engine.ConnectStore
import com.example.engine.EngineId
import com.example.engine.Phase
import com.example.ui.AboutSheet
import com.example.ui.LicensesSheet
import com.example.ui.LogSheet
import com.example.ui.MenuSheet
import com.example.ui.MohrScreen
import com.example.ui.MohrSheet
import com.example.ui.Sheet
import com.example.ui.StagesSheet
import com.example.ui.theme.MyApplicationTheme
import com.example.vpn.MehVpnService
import com.example.xray.XraySettings

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val prefs = getSharedPreferences("ui", Context.MODE_PRIVATE)
        if (ConnectStore.state.value.phase == Phase.IDLE) {
            ConnectStore.selectEngine(EngineId.from(prefs.getString("engine", null)))
            // No session in this process: any engine core still running is an orphan; kill it.
            val libDir = applicationInfo.nativeLibraryDir
            val pidFile = java.io.File(filesDir, MehVpnService.PID_FILE)
            Thread({
                ChildProcesses.init(pidFile)
                if (ConnectStore.state.value.phase == Phase.IDLE) {
                    // Only cores not started by this process (orphans of an earlier, dead process).
                    runCatching { ChildProcesses.killLeftovers(libDir, android.os.Process.myUid(), android.os.Process.myPid(), includeTracked = false) }
                }
            }, "mohr-sweep").start()
        }

        setContent {
            MyApplicationTheme {
                val state by ConnectStore.state.collectAsStateWithLifecycle()
                val logCount by AppLog.count.collectAsStateWithLifecycle()
                var selected by remember { mutableStateOf(state.engine) }
                var sheet by remember { mutableStateOf(Sheet.NONE) }
                val xraySettings = remember { XraySettings(this) }
                var fragment by remember { mutableStateOf(xraySettings.fragment) }

                LaunchedEffect(state.engine, state.phase) {
                    if (state.phase != Phase.IDLE && state.phase != Phase.ERROR) selected = state.engine
                }

                val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
                val vpnPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                    if (result.resultCode == Activity.RESULT_OK) MehVpnService.connect(this, selected)
                    else Toast.makeText(this, "برای اتصال، اجازه VPN لازم است", Toast.LENGTH_SHORT).show()
                }

                fun startConnect() {
                    if (Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    val prepare = VpnService.prepare(this)
                    if (prepare != null) vpnPermission.launch(prepare) else MehVpnService.connect(this, selected)
                }

                MohrScreen(
                    state = state,
                    selected = selected,
                    logCount = logCount,
                    onPower = {
                        when (state.phase) {
                            Phase.IDLE, Phase.ERROR -> startConnect()
                            Phase.CONNECTING, Phase.CONNECTED -> MehVpnService.disconnect(this)
                            Phase.DISCONNECTING -> Unit
                        }
                    },
                    onSelectEngine = { id ->
                        val idle = state.phase == Phase.IDLE || state.phase == Phase.ERROR
                        if (!idle) {
                            // One engine at a time, and no switching mid-session: disconnect first.
                            if (id != selected) Toast.makeText(this, "برای تغییر موتور، اول اتصال را قطع کنید", Toast.LENGTH_SHORT).show()
                        } else if (id != selected) {
                            selected = id
                            prefs.edit().putString("engine", id.name).apply()
                            ConnectStore.update { if (it.phase == Phase.IDLE || it.phase == Phase.ERROR) it.copy(engine = id, error = "", selection = "", phase = Phase.IDLE) else it }
                        }
                    },
                    onOpenMenu = { sheet = Sheet.MENU },
                    onOpenStages = { sheet = Sheet.STAGES },
                    onOpenLog = { sheet = Sheet.LOG },
                )

                if (sheet != Sheet.NONE) {
                    MohrSheet(onDismiss = { sheet = Sheet.NONE }) {
                        when (sheet) {
                            Sheet.MENU -> MenuSheet(
                                fragment = fragment,
                                // Like the engine switcher: no change mid-session; it applies on the next connect.
                                fragmentLocked = state.phase != Phase.IDLE && state.phase != Phase.ERROR,
                                onFragment = { on ->
                                    if (state.phase == Phase.IDLE || state.phase == Phase.ERROR) {
                                        fragment = on
                                        xraySettings.fragment = on
                                    }
                                },
                            ) { sheet = it }
                            Sheet.STAGES -> StagesSheet(state.events)
                            Sheet.LOG -> LogSheet(AppLog.snapshot()) {
                                val cm = getSystemService(ClipboardManager::class.java)
                                cm?.setPrimaryClip(ClipData.newPlainText("MohrVpn log", AppLog.snapshot().joinToString("\n")))
                                Toast.makeText(this, "لاگ کپی شد", Toast.LENGTH_SHORT).show()
                            }
                            Sheet.LICENSES -> LicensesSheet(remember { licenses() })
                            Sheet.ABOUT -> AboutSheet()
                            Sheet.NONE -> Unit
                        }
                    }
                }
            }
        }
    }

    private fun licenses(): String = runCatching {
        val dir = "licenses"
        val names = assets.list(dir).orEmpty().sorted()
        val notice = runCatching { assets.open("$dir/NOTICE.txt").bufferedReader().readText() }.getOrDefault("")
        notice + "\n\n" + names.filter { it != "NOTICE.txt" }.joinToString("\n\n") { n ->
            "===== $n =====\n" + assets.open("$dir/$n").bufferedReader().readText()
        }
    }.getOrDefault("")
}

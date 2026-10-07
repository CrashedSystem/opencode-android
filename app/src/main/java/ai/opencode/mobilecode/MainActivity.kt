package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.ProcessInfo
import ai.opencode.mobilecode.system.SystemTheme
import android.app.UiModeManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // The iOS lab's launch environment and arguments arrive as extras with the same names.
        if (BuildConfig.DEBUG) {
            ProcessInfo.processInfo = ProcessInfo.launch(intent.extras)
            OpenCodeApp.installLabPasteboardImageIfRequested(this)
            getSystemService(UiModeManager::class.java)
                .setApplicationNightMode(OpenCodeApp.labColorScheme ?: UiModeManager.MODE_NIGHT_AUTO)
        }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SystemTheme {
                CompositionLocalProvider(LocalReduceMotion provides (LocalReduceMotion.current || OpenCodeApp.labReducesMotion)) {
                    AppView(store = OpenCodeApp.store)
                }
            }
        }
    }
}

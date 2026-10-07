@file:PortedFrom("OpenCode/OpenCodeApp.swift", "553a407")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.ProcessInfo
import ai.opencode.mobilecode.tca.SharedMemory
import ai.opencode.mobilecode.tca.Store
import android.app.Application
import android.app.UiModeManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.PersistableBundle
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class OpenCodeApp : Application() {
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        instance = this
        SharedMemory.installLive(this)
        KeychainStorage.install(this)
        DictationClient.install(this)
        installDiagnosticsStorage(this)
        if (BuildConfig.DEBUG) verifyKeychainRoundTrip(this)
        processScope.launch { VoiceLiveActivityClient.liveValue.endAll() }
    }

    companion object {
        var instance: OpenCodeApp? = null
            private set

        val store: Store<AppFeature.State, AppFeature.Action> by lazy {
            LabMarks.mark("app_launch")
            val store = Store(initialState = { AppFeature.State() }) { AppFeature() }
            LabMarks.mark("app_store_ready")
            store
        }

        /// UI-test-only bridge: the XCTest runner and app do not share a pasteboard
        /// process, so seed one only for an explicitly identified lab launch.
        fun installLabPasteboardImageIfRequested(context: Context) {
            val process = ProcessInfo.processInfo
            if (!process.arguments.contains("-lab-pasteboard-image") ||
                process.environment["OPENCODE_LAB_SERVER_URL"] == null
            ) {
                return
            }
            val image = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            image.eraseColor(Color.rgb(88, 86, 214))
            for (x in 4 until 12) for (y in 4 until 12) image.setPixel(x, y, Color.WHITE)
            val file = File(context.cacheDir, "lab-pasteboard-image.png")
            file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            val clip = ClipData.newUri(context.contentResolver, "image", uri)
            // An emulator's copy confirmation would cover the composer; iOS shows none.
            clip.description.extras = PersistableBundle().apply {
                putBoolean("com.android.systemui.SUPPRESS_CLIPBOARD_OVERLAY", true)
            }
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        }

        /// `-lab-interface-style light|dark`, as a night mode for every window of the app.
        val labColorScheme: Int?
            get() {
                if (ProcessInfo.processInfo.environment["OPENCODE_LAB_SERVER_URL"] == null) return null
                return when (UserDefaults.standard.string(forKey = "lab-interface-style")) {
                    "light" -> UiModeManager.MODE_NIGHT_NO
                    "dark" -> UiModeManager.MODE_NIGHT_YES
                    else -> null
                }
            }

        /// `-lab-reduce-motion YES`: UI tests can't switch the system setting.
        val labReducesMotion: Boolean
            get() = ProcessInfo.processInfo.environment["OPENCODE_LAB_SERVER_URL"] != null &&
                UserDefaults.standard.bool(forKey = "lab-reduce-motion")
    }
}

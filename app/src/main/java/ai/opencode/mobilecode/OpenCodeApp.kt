@file:PortedFrom("OpenCode/OpenCodeApp.swift", "553a407", partial = "process-lifetime store, storage install, leftover Live Activity cleanup; lab pasteboard and lab color scheme are iOS UI-test bridges")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.tca.SharedMemory
import ai.opencode.mobilecode.tca.Store
import android.app.Application
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
    }
}

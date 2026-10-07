package ai.opencode.mobilecode

import ai.opencode.mobilecode.tca.SharedMemory
import android.app.Application

class OpenCodeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SharedMemory.installLive(this)
        installDiagnosticsStorage(this)
    }
}

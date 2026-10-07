package ai.opencode.mobilecode

import ai.opencode.mobilecode.tca.SharedMemory
import android.app.Application

class OpenCodeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        SharedMemory.installLive(this)
        KeychainStorage.install(this)
        DictationClient.install(this)
        installDiagnosticsStorage(this)
        if (BuildConfig.DEBUG) verifyKeychainRoundTrip(this)
    }

    companion object {
        var instance: OpenCodeApp? = null
            private set
    }
}

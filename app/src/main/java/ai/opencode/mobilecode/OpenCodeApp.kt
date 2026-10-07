package ai.opencode.mobilecode

import ai.opencode.mobilecode.tca.SharedMemory
import android.app.Application

class OpenCodeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        SharedMemory.installLive(this)
    }

    companion object {
        var instance: OpenCodeApp? = null
            private set
    }
}

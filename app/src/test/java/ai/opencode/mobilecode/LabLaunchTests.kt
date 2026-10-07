package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.ProcessInfo
import ai.opencode.mobilecode.tca.Shared
import ai.opencode.mobilecode.tca.SharedKey
import ai.opencode.mobilecode.tca.TestDependencies
import ai.opencode.mobilecode.tca.expect
import android.app.UiModeManager
import android.os.Bundle
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/// The iOS lab's launch environment and arguments, given to a debug
/// `MainActivity` as intent extras with the same names.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LabLaunchTests {
    @get:Rule
    val dependencies = TestDependencies()

    @After
    fun resetProcessInfo() {
        ProcessInfo.processInfo = ProcessInfo()
    }

    @Test
    fun extrasBecomeTheLaunchEnvironmentAndArguments() {
        val process = ProcessInfo.launch(
            Bundle().apply {
                putString("OPENCODE_LAB_SERVER_URL", "http://10.0.2.2:4120")
                putString("OPENCODE_LAB_LAST_SESSION_ID", "-")
                putBoolean("-lab-pasteboard-image", true)
                putBoolean("-lab-crash-report", false)
                putString("-lab-interface-style", "dark")
                putString("profile", "0")
            },
        )

        expect(process.environment == mapOf("OPENCODE_LAB_LAST_SESSION_ID" to "-", "OPENCODE_LAB_SERVER_URL" to "http://10.0.2.2:4120"))
        expect(process.arguments == listOf("-lab-interface-style", "dark", "-lab-pasteboard-image"))
        expect(process.argumentValue("lab-interface-style") == "dark")
        expect(ProcessInfo.launch(null).environment.isEmpty())
    }

    @Test
    fun labServerLaunchOpensThatServerWithoutTheLastSession() {
        val server = OpenCodeServer(url = "http://10.0.2.2:4120", username = "opencode", password = "")
        var lastViewed by Shared(SharedKey.lastViewedSessionIDs)
        lastViewed = mapOf(server.persistenceID to "ses_gauntlet_01")
        ProcessInfo.processInfo = ProcessInfo(
            environment = mapOf("OPENCODE_LAB_SERVER_URL" to server.url, "OPENCODE_LAB_LAST_SESSION_ID" to "-"),
            arguments = listOf("-lab-interface-style", "dark"),
        )

        val state = AppFeature.State()

        expect(state.savedServer == server)
        expect(state.savedServers == listOf(server))
        expect(state.home?.server == server)
        expect(state.lastViewedSessionIDs.isEmpty())
        expect(OpenCodeApp.labColorScheme == UiModeManager.MODE_NIGHT_YES)
    }

    @Test
    fun launchWithoutLabExtrasKeepsTheSavedState() {
        val state = AppFeature.State()

        expect(state.savedServer == null)
        expect(state.home == null)
        expect(OpenCodeApp.labColorScheme == null)
    }
}

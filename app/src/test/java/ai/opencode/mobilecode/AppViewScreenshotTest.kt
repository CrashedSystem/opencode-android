package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.LocalGlassBlurEnabled
import ai.opencode.mobilecode.system.SystemTheme
import ai.opencode.mobilecode.tca.Store
import ai.opencode.mobilecode.tca.TestDependencies
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w440dp-h956dp-xxhdpi")
class AppViewScreenshotTest {
    @get:Rule
    val dependencies = TestDependencies()

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun firstLaunchLight() = capture("first-launch.light") { AppFeature.State() }

    @Test
    @Config(qualifiers = "+night")
    fun firstLaunchDark() = capture("first-launch.dark") { AppFeature.State() }

    @Test
    fun serverManualLight() = capture("server-manual.light") {
        AppFeature.State().apply { isEditingServer = true }
    }

    @Test
    @Config(qualifiers = "+night")
    fun serverManualDark() = capture("server-manual.dark") {
        AppFeature.State().apply { isEditingServer = true }
    }

    @Test
    fun connectErrorLight() = capture("connect-error.light") { failed() }

    @Test
    @Config(qualifiers = "+night")
    fun connectErrorDark() = capture("connect-error.dark") { failed() }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(name: String, initial: () -> AppFeature.State) {
        val store = Store(initialState = initial) { AppFeature() }
        compose.setContent {
            SystemTheme {
                CompositionLocalProvider(LocalGlassBlurEnabled provides false) {
                    AppView(store)
                }
            }
        }
        compose.waitForIdle()
        captureScreenRoboImage("src/test/snapshots/app/AppView.$name.png")
    }

    private fun failed(): AppFeature.State = AppFeature.State().apply {
        connection = AppFeature.State.Connection.failed(
            message = "Couldn’t reach the OpenCode server",
            context = AppFeature.FailureContext(
                server = OpenCodeServer(url = "http://127.0.0.1:9", username = "opencode", password = ""),
                phase = AppFeature.FailureContext.Phase.connect,
                attempt = 1,
            ),
        )
    }
}

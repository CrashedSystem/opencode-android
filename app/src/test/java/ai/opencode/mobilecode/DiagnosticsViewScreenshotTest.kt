package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.ForcedAppearance
import ai.opencode.mobilecode.system.LocalGlassBlurEnabled
import ai.opencode.mobilecode.system.SystemTheme
import ai.opencode.mobilecode.tca.TestDependencies
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import java.net.URI
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w440dp-h956dp-xxhdpi")
class DiagnosticsViewScreenshotTest {
    @get:Rule
    val dependencies = TestDependencies()

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun light() = capture(false, "light")

    @Test
    @Config(qualifiers = "+night")
    fun dark() = capture(true, "dark")

    private fun capture(dark: Boolean, appearance: String) {
        val recorder = DiagnosticsRecorder.shared
        recorder.setEnabled(true)
        recorder.setIncludesTranscripts(true)
        recorder.clear()
        val url = URI("https://example.test/api/session/ses/message")
        repeat(59) { index ->
            val id = "r$index"
            recorder.requestStarted(id, "GET", url, null)
            recorder.requestEnded(
                id,
                "GET",
                url,
                200,
                null,
                if (index == 0) 18_000 else 0,
                "identity",
            )
            if (index == 0) {
                recorder.networkMetrics(
                    id,
                    url.path,
                    URLSessionTaskMetrics(
                        listOf(
                            URLSessionTaskTransactionMetrics(
                                countOfResponseBodyBytesReceived = 18_000,
                                networkProtocolName = "h2",
                                isReusedConnection = true,
                            ),
                        ),
                    ),
                )
            }
        }
        compose.setContent {
            ForcedAppearance(dark) {
                CompositionLocalProvider(LocalGlassBlurEnabled provides false) {
                    SystemTheme { DiagnosticsView() }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/snapshots/diagnostics/DiagnosticsView.$appearance.png")
    }
}

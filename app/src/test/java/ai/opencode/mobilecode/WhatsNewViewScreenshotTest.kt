package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.LocalGlassBlurEnabled
import ai.opencode.mobilecode.system.SystemColors
import ai.opencode.mobilecode.system.SystemTheme
import ai.opencode.mobilecode.tca.Store
import ai.opencode.mobilecode.tca.TestDependencies
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The sheet at its medium detent on the iOS reference device's 440 × 956 point screen, at 3x. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w440dp-h956dp-xxhdpi")
class WhatsNewViewScreenshotTest {
    @get:Rule
    val dependencies = TestDependencies()

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun light() = capture("light")

    /** `+night` reaches the sheet's own window, which `ForcedAppearance` does not. */
    @Test
    @Config(qualifiers = "+night")
    fun dark() = capture("dark")

    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(appearance: String) {
        val store = Store(initialState = WhatsNewFeature.State().apply { isPresented = true }) { WhatsNewFeature() }
        compose.setContent {
            SystemTheme {
                CompositionLocalProvider(LocalGlassBlurEnabled provides false) {
                    WhatsNewSheet(store) {
                        Box(Modifier.fillMaxSize().background(SystemColors.systemBackground))
                    }
                }
            }
        }
        compose.waitForIdle()
        captureScreenRoboImage("src/test/snapshots/whatsnew/WhatsNewView.$appearance.png")
    }
}

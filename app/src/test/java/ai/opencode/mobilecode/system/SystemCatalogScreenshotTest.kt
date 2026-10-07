package ai.opencode.mobilecode.system

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziRule
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w400dp-h2400dp-mdpi")
class SystemCatalogScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val roborazziRule = RoborazziRule(
        options = RoborazziRule.Options(
            outputDirectoryPath = "src/test/snapshots/system",
        ),
    )

    @Test
    fun light() = capture(dark = false)

    @Test
    fun dark() = capture(dark = true)

    private fun capture(dark: Boolean) {
        composeRule.setContent {
            ForcedAppearance(dark) {
                SystemTheme {
                    CompositionLocalProvider(LocalGlassBlurEnabled provides false) {
                        SystemCatalog(Modifier.width(400.dp))
                    }
                }
            }
        }
        composeRule.onRoot().captureRoboImage()
    }
}

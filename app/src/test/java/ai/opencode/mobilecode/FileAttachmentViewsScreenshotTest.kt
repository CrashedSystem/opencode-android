package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.ForcedAppearance
import ai.opencode.mobilecode.system.LocalGlassBlurEnabled
import ai.opencode.mobilecode.system.SystemColors
import ai.opencode.mobilecode.system.SystemTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w440dp-h240dp-xxhdpi")
class FileAttachmentViewsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun light() = capture("light", dark = false)

    @Test
    @Config(qualifiers = "+night")
    fun dark() = capture("dark", dark = true)

    private fun capture(name: String, dark: Boolean) {
        compose.setContent {
            ForcedAppearance(dark) {
                SystemTheme {
                    CompositionLocalProvider(LocalGlassBlurEnabled provides false) {
                        Box(Modifier.width(440.dp).background(SystemColors.systemBackground).padding(16.dp)) {
                            AgentFileCards(
                                files = listOf(
                                    AgentFileReference("recordings/drawer-demo.mp4"),
                                    AgentFileReference("docs/drawer-spec.pdf"),
                                ),
                                onOpen = {},
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/snapshots/file-attachments/FileAttachmentViews.$name.png")
    }
}

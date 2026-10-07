package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.ForcedAppearance
import ai.opencode.mobilecode.system.LocalGlassBlurEnabled
import ai.opencode.mobilecode.system.SystemTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w440dp-h956dp-xxhdpi")
class ShareViewScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun light() = capture(false, "light")

    @Test
    @Config(qualifiers = "+night")
    fun dark() = capture(true, "dark")

    private fun capture(dark: Boolean, appearance: String) {
        val model = ShareModel(
            targets = ShareTargets(
                serverID = "server",
                serverName = "localhost:4096",
                lastViewedSessionID = "ses_1",
                newSessionProject = ShareTargets.Project(id = "proj", name = "Work"),
                pinned = listOf(ShareTargets.Session(id = "ses_1", title = "Current", projectID = "proj", projectName = "Work")),
                recents = listOf(ShareTargets.Session(id = "ses_2", title = "Target", projectID = "proj_other", projectName = "Other")),
            ),
            store = ShareHandoffStore(File("share-preview")),
        )
        model.isLoading = false
        model.sharedText = listOf("Hello from the share sheet")
        compose.setContent {
            ForcedAppearance(dark) {
                CompositionLocalProvider(LocalGlassBlurEnabled provides false) {
                    SystemTheme {
                        ShareView(model, onCancel = {}, onAdded = {})
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/snapshots/share/ShareView.$appearance.png")
    }
}

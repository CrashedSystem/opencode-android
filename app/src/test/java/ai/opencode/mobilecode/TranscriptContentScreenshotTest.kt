package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.ForcedAppearance
import ai.opencode.mobilecode.system.LocalGlassBlurEnabled
import ai.opencode.mobilecode.system.SystemColors
import ai.opencode.mobilecode.system.SystemTheme
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziRule
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w440dp-h6400dp-mdpi")
class TranscriptContentScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val roborazziRule = RoborazziRule(
        options = RoborazziRule.Options(
            outputDirectoryPath = "src/test/snapshots/transcript",
        ),
    )

    @Test
    fun light() = capture(dark = false)

    @Test
    fun dark() = capture(dark = true)

    @Test
    @Config(qualifiers = "w320dp-h6400dp-mdpi")
    fun lightNarrow() = capture(dark = false, width = 320)

    @Test
    @Config(qualifiers = "w320dp-h6400dp-mdpi")
    fun darkNarrow() = capture(dark = true, width = 320)

    @Test
    @Config(qualifiers = "w320dp-h6400dp-mdpi")
    fun quote() = capture(dark = false, width = 320, blocks = TranscriptFixture.withIds(TranscriptFixture.blocks(TranscriptFixture.quote)))

    /// A small wide image scales up to the prose width; a tall one stops at
    /// 360 and centres on the card fill, as `scaledToFit` does on iOS.
    @Test
    fun images() {
        val markdown = "Before the images.\n\n![wide](${png(120, 90, 0xFF3762BE.toInt())})\n\n" +
            "![tall](${png(90, 240, 0xFFD38432.toInt())})\n\nAfter the images."
        capture(dark = false, blocks = TranscriptFixture.withIds(TranscriptFixture.blocks(markdown))) {
            composeRule.waitUntil(5_000) {
                listOf("Open wide", "Open tall").all { composeRule.onAllNodesWithContentDescription(it).fetchSemanticsNodes().isNotEmpty() }
            }
        }
    }

    private fun png(width: Int, height: Int, color: Int): String {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray())
    }

    private fun capture(
        dark: Boolean,
        width: Int = 440,
        blocks: List<OpenCodeMarkdownBlock> = TranscriptFixture.withIds(TranscriptFixture.referenceBlocks()),
        ready: () -> Unit = {},
    ) {
        val server = OpenCodeServer(url = "http://127.0.0.1:4096", username = "opencode", password = "pw")
        composeRule.setContent {
            ForcedAppearance(dark) {
                SystemTheme {
                    CompositionLocalProvider(LocalGlassBlurEnabled provides false) {
                        Column(
                            Modifier
                                .width(width.dp)
                                .background(SystemColors.systemBackground)
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            MarkdownBlocksView(
                                blocks = blocks,
                                server = server,
                                directory = "/tmp/opencode-lab",
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
        ready()
        composeRule.onRoot().captureRoboImage()
    }
}

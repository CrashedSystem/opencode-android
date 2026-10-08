package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.SystemTheme
import ai.opencode.mobilecode.tca.TestDependencies
import ai.opencode.mobilecode.tca.expect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/// The viewer through its real gesture: `MediaPresenterHost` presents a
/// transcript image out of its thumbnail, and a drag down decides.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w440dp-h956dp-xxhdpi")
class MediaPresentationTests {
    @get:Rule
    val dependencies = TestDependencies()

    @get:Rule
    val compose = createComposeRule()

    private val image = ImageBitmap(40, 30)
    private var thumbnail: MediaZoomSourceView? = null

    @After
    fun reset() {
        MediaPresenter.presented = null
    }

    private fun present() {
        val preview = TranscriptImagePreview(id = "img", image = image, label = "Image")
        compose.setContent {
            SystemTheme {
                Box(Modifier.fillMaxSize()) {
                    MediaThumbnail(image = image, ids = listOf("img"), modifier = Modifier.size(80.dp))
                    MediaPresenterHost()
                }
            }
        }
        compose.runOnIdle {
            MediaPresenter.present(sourceID = "img", isMedia = true, reducesMotion = false, darkColorScheme = true) { viewer ->
                TranscriptImageViewer(preview = preview, viewer = viewer)
            }
        }
        compose.waitForIdle()
        thumbnail = MediaZoomSources.shared.view(forID = "img")
    }

    @Test
    fun theThumbnailLeavesTheTranscriptWhileItsImageIsUp() {
        present()
        expect(MediaPresenter.presented != null)
        expect(thumbnail?.alpha == 0f)
        compose.onNodeWithContentDescription("Close image").performClick()
        compose.waitForIdle()
        expect(MediaPresenter.presented == null)
        expect(thumbnail?.alpha == 1f)
    }

    @Test
    fun aLongDragDownPutsTheImageBack() {
        present()
        // 600 px at 3x is 200 pt, past the 120 pt dismissal.
        compose.onNodeWithTag("image-gallery").performTouchInput { swipeDown(startY = centerY, endY = centerY + 600f, durationMillis = 400) }
        compose.waitForIdle()
        expect(MediaPresenter.presented == null)
    }

    @Test
    fun aShortDragSpringsBack() {
        present()
        compose.onNodeWithTag("image-gallery").performTouchInput { swipeDown(startY = centerY, endY = centerY + 60f, durationMillis = 600) }
        compose.waitForIdle()
        expect(MediaPresenter.presented != null)
        expect(MediaPresenter.presented?.state?.chromeHidden == false)
    }
}

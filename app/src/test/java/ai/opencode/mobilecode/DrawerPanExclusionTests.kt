package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.LocalGlassBlurEnabled
import ai.opencode.mobilecode.system.SystemTheme
import ai.opencode.mobilecode.tca.ImmediateClock
import ai.opencode.mobilecode.tca.TestDependencies
import ai.opencode.mobilecode.tca.expect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import kotlin.math.abs
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/// A sideways drag that starts on + belongs to the attachment menu and a
/// drag elsewhere on the session still opens the drawer, as in the iOS lab
/// tests. Each drag's first move already passes the pan's 8 pt slop, so
/// the pan decides before the control sees the move. 1x pixels are points.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w440dp-h956dp-mdpi")
class DrawerPanExclusionTests {
    @get:Rule
    val dependencies = TestDependencies()

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun showHome() {
        val store = HomeLabFixture.store(clock = ImmediateClock())
        compose.mainClock.autoAdvance = false
        compose.setContent {
            SystemTheme {
                CompositionLocalProvider(LocalGlassBlurEnabled provides false) { HomeView(store) }
            }
        }
        settle()
        expect(abs(cardLeft()) < 1, "the drawer starts closed")
    }

    @Test
    fun sidewaysDragFromTheAttachmentButtonLeavesTheDrawerClosed() {
        val plus = compose.onNodeWithTag("attachment-menu-button").fetchSemanticsNode().boundsInRoot.center
        drag(from = plus, by = Offset(120f, 0f))
        expect(abs(cardLeft()) < 1, "The drawer stays closed")
        expect(compose.onAllNodesWithTag("attachment-menu", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(), "The drag opened the menu instead")
    }

    @Test
    fun sidewaysDragElsewhereStillOpensTheDrawer() {
        val composer = compose.onNodeWithTag("session-composer").fetchSemanticsNode().boundsInRoot
        drag(from = Offset(composer.center.x, composer.top - 200), by = Offset(120f, 0f)) {
            expect(cardLeft() > 60, "The drawer follows a drag that starts on the transcript")
        }
    }

    private fun drag(from: Offset, by: Offset, during: () -> Unit = {}) {
        compose.onRoot().performTouchInput { down(from) }
        compose.onRoot().performTouchInput { moveBy(by) }
        compose.waitForIdle()
        compose.onRoot().performTouchInput { moveBy(Offset(by.x / 4, 0f)) }
        compose.waitForIdle()
        during()
        compose.onRoot().performTouchInput { up() }
        settle()
    }

    private fun cardLeft(): Float = compose.onNodeWithTag("session-drawer-card").fetchSemanticsNode().boundsInRoot.left

    private fun settle() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }
}

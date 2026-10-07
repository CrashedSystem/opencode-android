package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.LocalGlassBlurEnabled
import ai.opencode.mobilecode.system.SystemTheme
import ai.opencode.mobilecode.tca.ImmediateClock
import ai.opencode.mobilecode.tca.TestDependencies
import ai.opencode.mobilecode.tca.expect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import kotlin.math.abs
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/// Real touches on Home with the drawer open: a vertical scroll and a row's
/// swipe must never move the card, while a drag on the exposed card closes
/// it. A short screen at 1x makes the list scroll and pixels points.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w440dp-h640dp-mdpi")
class SessionDrawerGestureTests {
    @get:Rule
    val dependencies = TestDependencies()

    @get:Rule
    val compose = createComposeRule()

    /// The card's leading edge with the drawer open: 75% of the screen.
    private val open = 330f

    @Before
    fun openDrawer() {
        val store = HomeLabFixture.store(clock = ImmediateClock())
        compose.mainClock.autoAdvance = false
        compose.setContent {
            SystemTheme {
                CompositionLocalProvider(LocalGlassBlurEnabled provides false) { HomeView(store) }
            }
        }
        settle()
        compose.onNodeWithContentDescription("Open sessions").performClick()
        settle()
        expect(abs(cardLeft() - open) < 1, "the drawer opened")
    }

    @Test
    fun verticalScrollInTheListNeverMovesTheCard() {
        val row = bounds("Audit SSE reconnect timing")
        // Mostly up the screen, drifting sideways as a thumb does.
        drag(from = row.center, steps = List(30) { Offset(1.5f, -8f) })
        expect(bounds("Audit SSE reconnect timing").top < row.top - 100, "the list scrolled")
        settle()
        expect(abs(cardLeft() - open) < 1)
    }

    @Test
    fun leftwardRowSwipeNeverMovesTheCard() {
        val row = bounds("Polish the native feature tour")
        drag(from = row.center, steps = List(10) { Offset(-4f, 0.3f) }) {
            expect(bounds("Polish the native feature tour").left < row.left - 20, "the row's swipe follows the finger")
        }
        settle()
        expect(abs(cardLeft() - open) < 1)
    }

    @Test
    fun rightwardRowSwipeNeverMovesTheCard() {
        val row = bounds("Profile transcript rendering")
        // The card can't open further, so the row moving is what shows the
        // row, not the drawer, took the drag.
        drag(from = row.center, steps = List(10) { Offset(4f, -0.3f) }) {
            expect(bounds("Profile transcript rendering").left > row.left + 20, "the row's swipe follows the finger")
        }
        settle()
        expect(abs(cardLeft() - open) < 1)
    }

    /// The control: the same harness sees the drawer pan when it should win.
    @Test
    fun leftwardDragOnTheExposedCardClosesTheDrawer() {
        drag(from = Offset(open + 60, 320f), steps = List(20) { Offset(-10f, 0.5f) }, checkCard = false) {
            expect(cardLeft() < open - 100, "the card follows the finger")
        }
        settle()
        expect(abs(cardLeft()) < 1, "the drawer closed")
    }

    /// Touches down at [from], moves by each step, checks the card has not
    /// moved after every step unless told not to, runs [during] before the
    /// finger lifts, then lifts.
    private fun drag(from: Offset, steps: List<Offset>, checkCard: Boolean = true, during: () -> Unit = {}) {
        compose.onRoot().performTouchInput { down(from) }
        for (step in steps) {
            compose.onRoot().performTouchInput { moveBy(step) }
            compose.waitForIdle()
            if (checkCard) expect(abs(cardLeft() - open) < 1, "the drawer pan began")
        }
        during()
        compose.onRoot().performTouchInput { up() }
        compose.waitForIdle()
    }

    private fun cardLeft(): Float = compose.onNodeWithTag("session-drawer-card").fetchSemanticsNode().boundsInRoot.left

    private fun bounds(text: String): Rect = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun settle() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }
}

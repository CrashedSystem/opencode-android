package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.LocalGlassBlurEnabled
import ai.opencode.mobilecode.system.SystemTheme
import ai.opencode.mobilecode.tca.Effect
import ai.opencode.mobilecode.tca.Reduce
import ai.opencode.mobilecode.tca.Store
import ai.opencode.mobilecode.tca.TestDependencies
import ai.opencode.mobilecode.tca.expect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/// The + button on the real session screen, like the iOS lab tests:
/// a tap opens the menu and the same button closes it, a hold opens it
/// under the finger, and sliding onto a row and releasing chooses it.
/// Robolectric has no camera and an empty clipboard, so the rows are
/// Photos and Files. 1x pixels are points.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w440dp-h956dp-mdpi")
class AttachmentMenuInteractionTests {
    @get:Rule
    val dependencies = TestDependencies()

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun showSession() {
        val store = Store(initialState = { SessionViewFixture.state(SessionViewFixture.intro) }) {
            Reduce<SessionFeature.State, SessionFeature.Action> { _ -> Effect.none }
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            SystemTheme {
                CompositionLocalProvider(LocalGlassBlurEnabled provides false) {
                    Box(Modifier.fillMaxSize()) { SessionView(store, listContext = SessionViewFixture.context) }
                }
            }
        }
        settle()
    }

    @Test
    fun aTapOpensTheMenuAndTheSameButtonClosesIt() {
        expect(label() == "Add attachment")
        compose.onNodeWithTag("attachment-menu-button").performClick()
        settle()
        expect(isMenuOpen(), "A tap opens the menu")
        expect(label() == "Close", "The + turns into a ×")
        val menu = compose.onNodeWithTag("attachment-menu").fetchSemanticsNode().boundsInRoot
        val composer = compose.onNodeWithTag("session-composer").fetchSemanticsNode().boundsInRoot
        expect(menu.bottom <= composer.top, "The menu stands above the composer, leaving the × in place")

        compose.onNodeWithTag("attachment-menu-button").performClick()
        settle()
        expect(!isMenuOpen(), "The × closes it")
        expect(label() == "Add attachment")
    }

    @Test
    fun holdingOpensTheMenuAndReleasingInPlaceLeavesItOpen() {
        compose.onNodeWithTag("attachment-menu-button").performTouchInput { down(center) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        expect(isMenuOpen(), "A hold opens the menu under the finger")
        compose.onNodeWithTag("attachment-menu-button").performTouchInput { up() }
        settle()
        expect(isMenuOpen(), "Releasing in place leaves it open")
        expect(label() == "Close")
    }

    @Test
    fun pressHoldSlideAndReleaseOnFilesChoosesIt() {
        val button = compose.onNodeWithTag("attachment-menu-button")
        val origin = button.fetchSemanticsNode().boundsInRoot.topLeft
        button.performTouchInput { down(center) }
        compose.waitForIdle()
        // Held past the menu's spring, so the rows are where they rest.
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        expect(isMenuOpen())
        val files = compose.onNodeWithContentDescription("Files", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center
        button.performTouchInput {
            val target = files - origin
            moveTo(Offset((center.x + target.x) / 2, (center.y + target.y) / 2))
            moveTo(target)
        }
        compose.mainClock.advanceTimeBy(100)
        button.performTouchInput { up() }
        settle()
        expect(!isMenuOpen(), "Releasing on Files chooses it, which closes the menu")
        expect(label() == "Add attachment")
    }

    private fun isMenuOpen(): Boolean =
        compose.onAllNodesWithTag("attachment-menu", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun label(): String? =
        compose.onNodeWithTag("attachment-menu-button").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()

    private fun settle() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_200)
        compose.waitForIdle()
    }
}

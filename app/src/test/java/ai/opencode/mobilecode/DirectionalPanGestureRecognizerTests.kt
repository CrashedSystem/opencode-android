@file:PortedFrom("OpenCodeTests/DirectionalPanGestureRecognizerTests.swift", "553a407")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.tca.Binding
import ai.opencode.mobilecode.tca.expect
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

class DirectionalPanGestureRecognizerTests {
    @Test
    fun decisionTable() {
        val rows = listOf(
            Row(DirectionalPanGestureRecognizer.TouchOrigin.drawerRow, true, -20f, 1f, DirectionalPanGestureRecognizer.Decision.fail),
            Row(DirectionalPanGestureRecognizer.TouchOrigin.drawerList, true, -20f, 1f, DirectionalPanGestureRecognizer.Decision.begin),
            Row(DirectionalPanGestureRecognizer.TouchOrigin.drawerChrome, true, -20f, 1f, DirectionalPanGestureRecognizer.Decision.begin),
            Row(DirectionalPanGestureRecognizer.TouchOrigin.exposedCard, true, -20f, 1f, DirectionalPanGestureRecognizer.Decision.begin),
            Row(DirectionalPanGestureRecognizer.TouchOrigin.drawerRow, true, 20f, 1f, DirectionalPanGestureRecognizer.Decision.fail),
            Row(DirectionalPanGestureRecognizer.TouchOrigin.drawerList, true, 20f, 1f, DirectionalPanGestureRecognizer.Decision.fail),
            Row(DirectionalPanGestureRecognizer.TouchOrigin.drawerChrome, true, 20f, 1f, DirectionalPanGestureRecognizer.Decision.begin),
            Row(DirectionalPanGestureRecognizer.TouchOrigin.content(horizontalScrollerOwnsRightwardDrag = false), false, 20f, 1f, DirectionalPanGestureRecognizer.Decision.begin),
            Row(DirectionalPanGestureRecognizer.TouchOrigin.content(horizontalScrollerOwnsRightwardDrag = true), false, 20f, 1f, DirectionalPanGestureRecognizer.Decision.fail),
            Row(DirectionalPanGestureRecognizer.TouchOrigin.content(horizontalScrollerOwnsRightwardDrag = false), false, -20f, 1f, DirectionalPanGestureRecognizer.Decision.fail),
            Row(DirectionalPanGestureRecognizer.TouchOrigin.content(horizontalScrollerOwnsRightwardDrag = false), false, 9f, 10f, DirectionalPanGestureRecognizer.Decision.fail),
            Row(DirectionalPanGestureRecognizer.TouchOrigin.content(horizontalScrollerOwnsRightwardDrag = false), false, 3f, 2f, DirectionalPanGestureRecognizer.Decision.wait),
        )
        for (row in rows) {
            expect(DirectionalPanGestureRecognizer.decision(origin = row.origin, presented = row.presented, dx = row.dx, dy = row.dy) == row.expected)
        }
    }

    private data class Row(
        val origin: DirectionalPanGestureRecognizer.TouchOrigin,
        val presented: Boolean,
        val dx: Float,
        val dy: Float,
        val expected: DirectionalPanGestureRecognizer.Decision,
    )
}

/// The container on a 440 × 956 point screen at 1x, so the pixels the
/// controller takes are the points the Swift tests use.
private class DrawerHarness(
    val compose: ComposeContentTestRule,
    reducedMotion: Boolean = false,
    isPresented: Boolean = false,
    content: @androidx.compose.runtime.Composable () -> Unit = { Box(Modifier.fillMaxSize().background(Color.White)) },
) {
    val controller = SessionDrawerViewController(isPresented = isPresented, capturesCards = false)
    val settled = mutableListOf<Boolean>()

    init {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            SessionDrawerContainer(
                // The tests drive the controller as UIKit's do; the bindings follow it.
                isPresented = Binding({ controller.isPresented }, {}),
                width = 330.dp,
                reducedMotion = reducedMotion,
                content = content,
                drawer = { Box(Modifier.fillMaxSize().background(Color.Black)) },
                chrome = { Box(Modifier.fillMaxSize().background(Color.Gray)) },
                preview = { Box(Modifier.fillMaxSize().background(Color.White)) },
                catchUp = controller.catchUp,
                catchUpProxy = CatchUpStageProxy(),
                onSettled = { settled += it },
                controller = controller,
            )
        }
        compose.waitForIdle()
    }

    fun run(block: SessionDrawerViewController.() -> Unit) {
        compose.runOnIdle { controller.block() }
    }

    /// Longer than the 0.3 s settle.
    fun waitForSettle() = sleep(600)

    /// Dispatches what the test just changed, then moves the clock.
    fun sleep(milliseconds: Long) {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(milliseconds)
        compose.waitForIdle()
    }

    /// Recomposes what the controller just changed.
    fun frame() = sleep(32)
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w440dp-h956dp-mdpi")
class CatchUpTransitionTests {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun cardRotationDoesNotChangeComposerLayout() {
        var card: LayoutCoordinates? = null
        var composer: LayoutCoordinates? = null
        val harness = DrawerHarness(compose) {
            Box(Modifier.fillMaxSize().background(Color.White).onGloballyPositioned { card = it }) {
                Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp).fillMaxWidth().height(80.dp).background(Color.Blue).onGloballyPositioned { composer = it })
            }
        }
        harness.run { updateCatchUp(CatchUpStage(queue = listOf("a"), undoDirection = null, onDismiss = {}, onUndo = {}, onExit = {}), animated = false) }
        compose.waitForIdle()
        fun inCard(): Rect = card!!.localBoundingBoxOf(composer!!)
        val resting = inCard()
        expect(resting.height == 80f)
        for (direction in listOf(-1f, 1f)) {
            harness.run {
                stack.cardTranslationX = direction * 180
                stack.cardTranslationY = 20f
                stack.cardRotation = (direction * 0.16f * 180 / PI).toFloat()
            }
            compose.waitForIdle()
            val dragged = inCard()
            expect(abs(dragged.top - resting.top) < 1)
            expect(abs(dragged.width - resting.width) < 1)
            expect(abs(dragged.height - resting.height) < 1)
        }
    }

    @Test
    fun immediateDismissUndoAndExitLeaveNoTransitionSnapshots() {
        val harness = DrawerHarness(compose, isPresented = true)
        var dismissed = 0
        var undone = 0
        val stage = CatchUpStage(
            queue = listOf("first", "second"),
            undoDirection = HomeFeature.CatchUpDisposition.markRead,
            onDismiss = { dismissed += 1 },
            onUndo = { undone += 1 },
            onExit = {},
        )
        val controller = harness.controller

        harness.run { updateCatchUp(stage, animated = true) }
        expect(controller.contentFade == 0f, "The entrance owns the handoff")
        harness.run { dismissCard(HomeFeature.CatchUpDisposition.markRead) }
        expect(dismissed == 1)
        expect(controller.contentFade == 1f, "The entrance must finish before a flight takes ownership")
        harness.run { undoCard() }
        expect(undone == 1)
        harness.run { updateCatchUp(null, animated = true) }
        // Re-enter and leave before the exit morph completes, too.
        harness.run { updateCatchUp(stage, animated = false) }
        harness.run { updateCatchUp(null, animated = false) }
        expect(controller.contentFade == 1f)
        expect(controller.stack.flight == null, "Flights and morphs must release every snapshot")
        expect(controller.offset == 0f && controller.stack.cardTranslationX == 0f && controller.stack.cardTranslationY == 0f)
        expect(controller.stack.cardRotation == 0f && controller.stack.cardScale == 1f)
        expect(controller.stack.cardAlpha == 1f)
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w440dp-h956dp-mdpi")
class SessionDrawerSettleTests {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun buttonOpenAndCloseEachSettleOnce() {
        val harness = DrawerHarness(compose)
        harness.run { setPresented(true, animated = true) }
        expect(harness.settled.isEmpty(), "Feedback waits for the drawer to arrive")
        harness.waitForSettle()
        expect(harness.settled == listOf(true))
        harness.run { setPresented(false, animated = true) }
        harness.waitForSettle()
        expect(harness.settled == listOf(true, false))
    }

    @Test
    fun dragsThatFallBackNeverSettle() {
        val harness = DrawerHarness(compose)
        harness.run {
            beginDrag()
            drag(translation = 60f)
            endDrag(velocity = 0f)
        }
        harness.waitForSettle()
        expect(harness.settled.isEmpty())

        harness.run { setPresented(true, animated = false) }
        expect(harness.settled == listOf(true))
        harness.run {
            beginDrag()
            drag(translation = -60f)
            cancelDrag()
        }
        harness.waitForSettle()
        expect(harness.settled == listOf(true))
    }

    @Test
    fun dragsAndFlicksSettleOnceEachWay() {
        val harness = DrawerHarness(compose)
        harness.run {
            beginDrag()
            drag(translation = 30f)
            endDrag(velocity = 900f)
        }
        harness.waitForSettle()
        expect(harness.settled == listOf(true))
        harness.run {
            beginDrag()
            drag(translation = -250f)
            endDrag(velocity = 0f)
        }
        harness.waitForSettle()
        expect(harness.settled == listOf(true, false))
    }

    @Test
    fun rapidReversalsNeverDoubleFire() {
        val harness = DrawerHarness(compose)
        // Opened, then caught and flung shut before it arrived.
        harness.run { setPresented(true, animated = true) }
        harness.sleep(80)
        harness.run {
            beginDrag()
            endDrag(velocity = -1200f)
        }
        harness.waitForSettle()
        expect(harness.settled.isEmpty())

        // Opened and closed again by taps faster than the settle.
        harness.run {
            setPresented(true, animated = true)
            setPresented(false, animated = true)
        }
        harness.waitForSettle()
        expect(harness.settled.isEmpty())

        // Reversed twice, ending open: one arrival.
        harness.run {
            setPresented(true, animated = true)
            setPresented(false, animated = true)
            setPresented(true, animated = true)
        }
        harness.waitForSettle()
        expect(harness.settled == listOf(true))
    }

    @Test
    fun reducedMotionSettlesImmediately() {
        val harness = DrawerHarness(compose, reducedMotion = true)
        harness.run { setPresented(true, animated = true) }
        expect(harness.settled == listOf(true))
        harness.run {
            beginDrag()
            drag(translation = -40f)
            endDrag(velocity = 0f)
        }
        expect(harness.settled == listOf(true))
        harness.run {
            beginDrag()
            drag(translation = -300f)
            endDrag(velocity = 0f)
        }
        expect(harness.settled == listOf(true, false))
    }

    @Test
    fun catchUpTakingOverAnOpenDrawerIsNotAClose() {
        val harness = DrawerHarness(compose, isPresented = true)
        val stage = CatchUpStage(queue = listOf("a"), undoDirection = null, onDismiss = {}, onUndo = {}, onExit = {})
        harness.run {
            updateCatchUp(stage, animated = false)
            updateCatchUp(null, animated = false)
        }
        expect(harness.settled.isEmpty())
        harness.run { setPresented(true, animated = false) }
        expect(harness.settled == listOf(true))
    }

    @Test
    fun scrimFollowsTheDragAndHidesTheCardFromAccessibility() {
        val harness = DrawerHarness(compose)
        val controller = harness.controller
        fun scrims() = compose.onAllNodesWithContentDescription("Close session list", useUnmergedTree = true).fetchSemanticsNodes().size
        expect(controller.scrimAlpha == 0f)
        expect(!controller.isScrimInteractive)
        expect(controller.isContentAccessible)

        harness.run {
            beginDrag()
            drag(translation = 165f)
        }
        expect(abs(controller.scrimAlpha - 0.5f) < 0.001f)
        harness.run { drag(translation = 330f) }
        harness.frame()
        expect(controller.scrimAlpha == 1f)
        expect(controller.isScrimInteractive, "A tap on the open card closes the drawer")
        expect(scrims() == 1)
        expect(!controller.isContentAccessible)
        harness.run { drag(translation = 0f) }
        harness.frame()
        expect(controller.scrimAlpha == 0f)
        expect(controller.isContentAccessible)
        expect(scrims() == 0)
    }

    @Test
    fun scrimMutesTheCardInBothAppearances() {
        fun resolved(dark: Boolean, highContrast: Boolean = false): Pair<Float, Float> {
            val color = DrawerScrim.color(dark = dark, highContrast = highContrast)
            return color.red to color.alpha
        }
        // Over a white card the background darkens; over a black card it lifts,
        // so it always separates from the drawer, and white text always recedes.
        val (lightWhite, lightAlpha) = resolved(dark = false)
        val (darkWhite, darkAlpha) = resolved(dark = true)
        expect(1 - lightAlpha + lightWhite * lightAlpha < 0.75f)
        expect(darkWhite * darkAlpha > 0.1f)
        expect(1 - darkAlpha + darkWhite * darkAlpha < 0.6f)
        expect(resolved(dark = false, highContrast = true).second > lightAlpha)
        expect(resolved(dark = true, highContrast = true).second > darkAlpha)
    }
}

class ComposerSendTransitionTests {
    @Ignore("ComposerTextInput.kt: ComposerSendTransition belongs to the composer's port")
    @Test
    fun interruptedSendRestoresTheBubbleAndRemovesBothSnapshots() {}
}

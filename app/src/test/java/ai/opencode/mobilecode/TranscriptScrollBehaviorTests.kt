@file:PortedFrom("OpenCodeTests/TranscriptScrollBehaviorTests.swift", "3804368")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.SystemColors
import ai.opencode.mobilecode.system.SystemTheme
import ai.opencode.mobilecode.tca.Binding
import ai.opencode.mobilecode.tca.Effect
import ai.opencode.mobilecode.tca.Reduce
import ai.opencode.mobilecode.tca.Store
import ai.opencode.mobilecode.tca.TestDependencies
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/// The list from `SessionTranscriptView`, with a reducer that ignores every
/// action, as the iOS tests host `TranscriptListViewController` in a window.
internal class ListHarness(private val compose: ComposeContentTestRule, count: Int = 60) {
    val bridge = SessionScrollEdgeBridge()
    val shown = mutableStateListOf<Boolean>()
    var jumpRequest by mutableIntStateOf(0)
    var composerInset by mutableFloatStateOf(240f)
    val items = (0 until count).map { OpenCodeTranscriptItem.userText(id = "m$it", text = "Message $it") }
    val store = Store(initialState = { SessionViewFixture.state(items).apply { visibleLimit = count } }) {
        Reduce<SessionFeature.State, SessionFeature.Action> { _ -> Effect.none }
    }
    val list: LazyListState get() = bridge.scrollView!!

    fun show() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            SystemTheme {
                CompositionLocalProvider(LocalTranscriptClock provides { compose.mainClock.currentTime * 1_000_000 }) {
                    Box(Modifier.fillMaxSize().background(SystemColors.systemBackground)) {
                        SessionTranscriptView(
                            store = store,
                            scrollEdgeBridge = bridge,
                            dismissKeyboard = {},
                            onPreviewImage = {},
                            onOpenFile = {},
                            showsJumpButton = Binding({ shown.lastOrNull() ?: false }, { shown += it }),
                            jumpRequest = jumpRequest,
                            composerInset = { composerInset },
                            keyboardIsMoving = { false },
                        )
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_500)
    }

    fun remaining(): Float {
        val info = list.layoutInfo
        val last = info.visibleItemsInfo.last()
        if (last.index != info.totalItemsCount - 1) return Float.MAX_VALUE
        return maxOf(0, last.offset + last.size + info.afterContentPadding - info.viewportEndOffset).toFloat()
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w440dp-h956dp-xxhdpi")
class TranscriptJumpTests {
    @get:Rule
    val dependencies = TestDependencies()

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun jumpImmediatelyReachesTheMeasuredTailAndSurvivesScrollCancellation() {
        val harness = ListHarness(compose, 100)
        harness.show()
        compose.onNodeWithTag("transcript-list").performScrollToIndex(0)
        compose.mainClock.advanceTimeBy(500)
        assertTrue(harness.remaining() > 1_000)
        compose.runOnUiThread {
            harness.jumpRequest = 1
            Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeBy(100)
        assertTrue(harness.list.layoutInfo.visibleItemsInfo.any { it.index == harness.list.layoutInfo.totalItemsCount - 1 })
        // A scroll that interrupts the landing does not leave it short.
        compose.runOnUiThread { harness.list.dispatchRawDelta(0f) }
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(0f, harness.remaining(), 1f)
        assertTrue(harness.list.layoutInfo.visibleItemsInfo.any { it.index == harness.list.layoutInfo.totalItemsCount - 1 })
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w440dp-h956dp-xxhdpi")
class TranscriptAssistiveScrollTests {
    @get:Rule
    val dependencies = TestDependencies()

    @get:Rule
    val compose = createComposeRule()

    /// TalkBack's scroll moves the list without a drag; the reader is
    /// browsing from then on, so arrivals no longer pull them back.
    @Test
    fun assistiveScrollUpLeavesTheBottom() {
        val harness = ListHarness(compose, 60)
        harness.show()
        compose.onNodeWithTag("transcript-list").performScrollToIndex(40)
        compose.mainClock.advanceTimeBy(500)
        assertEquals(true, harness.shown.lastOrNull())
        val first = harness.list.firstVisibleItemIndex
        // Not pulled back by a later layout pass.
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals("Pulled back from item $first", first, harness.list.firstVisibleItemIndex)
    }

    @Test
    fun listOwnMotionIsNotAnAssistiveScroll() {
        val harness = ListHarness(compose, 60)
        harness.show()
        compose.runOnUiThread { harness.list.dispatchRawDelta(-400f) }
        compose.mainClock.advanceTimeBy(500)
        assertFalse(harness.shown.contains(true))
    }

    @Ignore("Android has no status-bar scroll to top")
    @Test
    fun statusBarScrollToTopLeavesTheBottom() {
    }
}


class TranscriptScrollBehaviorTests {
    @get:Rule
    val dependencies = TestDependencies()

    @Test
    fun followingAppendsAndKeyboardLayoutStayAtBottom() {
        val behavior = TranscriptScrollBehavior()
        val viewport = viewport(700f, 1_200f, 500f)
        assertEquals(
            listOf(TranscriptScrollBehavior.Command.scrollToBottom(false)),
            behavior.handle(TranscriptScrollBehavior.Event.contentChanged(0f, true, viewport, true)),
        )
        assertEquals(
            listOf(TranscriptScrollBehavior.Command.scrollToBottom(false)),
            behavior.handle(TranscriptScrollBehavior.Event.layoutChanged(viewport)),
        )
        assertEquals(TranscriptScrollBehavior.Mode.following, behavior.mode)
    }

    @Test
    fun bottomHysteresisTransitionsBetweenFollowingAndBrowsing() {
        val behavior = TranscriptScrollBehavior()
        assertTrue(behavior.handle(TranscriptScrollBehavior.Event.userScrolled(viewport(900f, 1_500f, 500f))).isEmpty())
        assertEquals(TranscriptScrollBehavior.Mode.following, behavior.mode)
        assertEquals(
            listOf(TranscriptScrollBehavior.Command.showJumpButton(true)),
            behavior.handle(TranscriptScrollBehavior.Event.userScrolled(viewport(870f, 1_500f, 500f))),
        )
        assertEquals(TranscriptScrollBehavior.Mode.browsing, behavior.mode)
        assertTrue(behavior.handle(TranscriptScrollBehavior.Event.userScrolled(viewport(1_000f, 1_500f, 500f))).isEmpty())
        assertEquals(TranscriptScrollBehavior.Mode.browsing, behavior.mode)
        assertEquals(
            listOf(
                TranscriptScrollBehavior.Command.showJumpButton(false),
                TranscriptScrollBehavior.Command.scrollToBottom(false),
            ),
            behavior.handle(TranscriptScrollBehavior.Event.userSettled(viewport(1_000f, 1_500f, 500f), false)),
        )
        assertEquals(TranscriptScrollBehavior.Mode.following, behavior.mode)
    }

    @Test
    fun readerMayRestJustAboveTheBottom() {
        val behavior = TranscriptScrollBehavior()
        val resting = viewport(1_400f, 2_000f, 500f, canRequestEarlierHistory = false)
        behavior.handle(TranscriptScrollBehavior.Event.dragBegan(viewport(1_500f, 2_000f, 500f)))
        assertTrue(behavior.handle(TranscriptScrollBehavior.Event.userScrolled(resting)).isEmpty())
        assertEquals(
            listOf(TranscriptScrollBehavior.Command.showJumpButton(true)),
            behavior.handle(TranscriptScrollBehavior.Event.userSettled(resting, false)),
        )
        assertEquals(TranscriptScrollBehavior.Mode.browsing, behavior.mode)
        assertTrue(behavior.handle(TranscriptScrollBehavior.Event.contentChanged(
            0f, true, viewport(1_400f, 2_200f, 500f), true,
        )).isEmpty())
    }

    @Test
    fun contentArrivingDuringAShortDragKeepsFollowing() {
        val behavior = TranscriptScrollBehavior()
        behavior.handle(TranscriptScrollBehavior.Event.dragBegan(viewport(1_500f, 2_000f, 500f)))
        val resting = viewport(1_440f, 2_100f, 500f, canRequestEarlierHistory = false)
        assertEquals(
            listOf(TranscriptScrollBehavior.Command.scrollToBottom(false)),
            behavior.handle(TranscriptScrollBehavior.Event.userSettled(resting, true)),
        )
        assertEquals(TranscriptScrollBehavior.Mode.following, behavior.mode)
    }

    @Test
    fun keyboardLeavingUnderAFollowingDragHoldsAndTheDragDrainsIt() {
        val behavior = TranscriptScrollBehavior()
        behavior.handle(TranscriptScrollBehavior.Event.dragBegan(viewport(1_180f, 1_500f, 320f)))
        val grown = viewport(1_180f, 1_500f, 600f, canRequestEarlierHistory = false).copy(isUserScrolling = true)
        assertEquals(
            listOf(TranscriptScrollBehavior.Command.setTrailingSpace(280f)),
            behavior.handle(TranscriptScrollBehavior.Event.layoutChanged(grown)),
        )
        assertEquals(TranscriptScrollBehavior.Mode.following, behavior.mode)
        assertEquals(
            listOf(TranscriptScrollBehavior.Command.setTrailingSpace(200f)),
            behavior.handle(TranscriptScrollBehavior.Event.userScrolled(viewport(1_100f, 1_500f, 600f, 280f, false))),
        )
        assertEquals(TranscriptScrollBehavior.Mode.following, behavior.mode)
        assertEquals(
            listOf(
                TranscriptScrollBehavior.Command.setTrailingSpace(140f),
                TranscriptScrollBehavior.Command.showJumpButton(true),
            ),
            behavior.handle(TranscriptScrollBehavior.Event.userScrolled(viewport(1_040f, 1_500f, 600f, 200f, false))),
        )
        assertEquals(TranscriptScrollBehavior.Mode.browsing, behavior.mode)
    }

    @Test
    fun aHeldDragThatCarriedTheReaderUpRestsBrowsing() {
        val behavior = TranscriptScrollBehavior()
        behavior.handle(TranscriptScrollBehavior.Event.dragBegan(viewport(1_180f, 1_500f, 320f)))
        val grown = viewport(1_180f, 1_500f, 600f, canRequestEarlierHistory = false).copy(isUserScrolling = true)
        behavior.handle(TranscriptScrollBehavior.Event.layoutChanged(grown))
        behavior.handle(TranscriptScrollBehavior.Event.userScrolled(viewport(1_160f, 1_500f, 600f, 280f, false)))
        val resting = viewport(1_160f, 1_500f, 600f, 260f, false)
        assertEquals(
            listOf(TranscriptScrollBehavior.Command.showJumpButton(true)),
            behavior.handle(TranscriptScrollBehavior.Event.userSettled(resting, false)),
        )
        assertEquals(TranscriptScrollBehavior.Mode.browsing, behavior.mode)
    }

    @Test
    fun aDragTowardTheEndKeepsFollowing() {
        val behavior = TranscriptScrollBehavior()
        behavior.handle(TranscriptScrollBehavior.Event.dragBegan(viewport(1_180f, 1_500f, 320f)))
        val resting = viewport(1_180f, 1_500f, 600f, 280f, false)
        assertEquals(
            listOf(TranscriptScrollBehavior.Command.scrollToBottom(false)),
            behavior.handle(TranscriptScrollBehavior.Event.userSettled(resting, false)),
        )
        assertEquals(TranscriptScrollBehavior.Mode.following, behavior.mode)
    }

    @Test
    fun aDragThatLeftInsideHeldSpaceStaysBrowsingAsContentArrives() {
        val behavior = TranscriptScrollBehavior()
        behavior.handle(TranscriptScrollBehavior.Event.dragBegan(viewport(1_180f, 1_500f, 320f)))
        val grown = viewport(1_180f, 1_500f, 600f, canRequestEarlierHistory = false).copy(isUserScrolling = true)
        behavior.handle(TranscriptScrollBehavior.Event.layoutChanged(grown))
        behavior.handle(TranscriptScrollBehavior.Event.userScrolled(viewport(1_040f, 1_500f, 600f, 280f, false)))
        assertEquals(TranscriptScrollBehavior.Mode.browsing, behavior.mode)
        // Still inside the held space, so the distance to its end is zero, and
        // a reply grew meanwhile: the reader who left is not pulled back.
        val resting = viewport(1_040f, 1_560f, 600f, 80f, false)
        assertTrue(behavior.handle(TranscriptScrollBehavior.Event.userSettled(resting, true)).isEmpty())
        assertEquals(TranscriptScrollBehavior.Mode.browsing, behavior.mode)
    }

    @Test
    fun rowsArrivingUnderAFollowingFingerDoNotLeaveTheBottom() {
        val behavior = TranscriptScrollBehavior()
        behavior.handle(TranscriptScrollBehavior.Event.dragBegan(viewport(1_180f, 1_500f, 320f)))
        // More than the leave distance arrives below while the finger barely
        // moves: the reader stays following, and no jump button flashes.
        assertTrue(behavior.handle(TranscriptScrollBehavior.Event.userScrolled(viewport(1_170f, 1_640f, 320f))).isEmpty())
        assertEquals(TranscriptScrollBehavior.Mode.following, behavior.mode)
        val resting = viewport(1_150f, 1_640f, 600f, 110f, false)
        assertEquals(
            listOf(TranscriptScrollBehavior.Command.scrollToBottom(false)),
            behavior.handle(TranscriptScrollBehavior.Event.userSettled(resting, true)),
        )
        assertEquals(TranscriptScrollBehavior.Mode.following, behavior.mode)
    }

    @Test
    fun aFollowerFarBehindAStreamIsNotLeftByALandingFinger() {
        val behavior = TranscriptScrollBehavior()
        // The finger lands while the end is 300 pt ahead of the follower.
        behavior.handle(TranscriptScrollBehavior.Event.dragBegan(viewport(1_200f, 2_000f, 500f)))
        assertTrue(behavior.handle(TranscriptScrollBehavior.Event.userScrolled(viewport(1_195f, 2_000f, 500f))).isEmpty())
        assertEquals(TranscriptScrollBehavior.Mode.following, behavior.mode)
        // Carried 150 pt up by the finger itself, it leaves.
        assertEquals(
            listOf(TranscriptScrollBehavior.Command.showJumpButton(true)),
            behavior.handle(TranscriptScrollBehavior.Event.userScrolled(viewport(1_050f, 2_000f, 500f))),
        )
    }

    @Test
    fun aDragDuringTheFollowersGlideKeepsFollowing() {
        val behavior = TranscriptScrollBehavior()
        // The follower trails a streaming end by 30 pt when the finger lands.
        behavior.handle(TranscriptScrollBehavior.Event.dragBegan(viewport(1_470f, 2_000f, 500f)))
        val resting = viewport(1_472f, 2_000f, 500f, canRequestEarlierHistory = false)
        assertEquals(
            listOf(TranscriptScrollBehavior.Command.scrollToBottom(false)),
            behavior.handle(TranscriptScrollBehavior.Event.userSettled(resting, false)),
        )
        assertEquals(TranscriptScrollBehavior.Mode.following, behavior.mode)
    }

    @Test
    fun aPillRowGrowingUnderAFingerAtTheEndKeepsFollowing() {
        val behavior = TranscriptScrollBehavior()
        behavior.handle(TranscriptScrollBehavior.Event.dragBegan(viewport(1_500f, 2_000f, 500f)))
        // The capsule's row joins the composer while the finger holds the list:
        // the end now lies 54 pt below, but the reader went nowhere.
        val shrunk = viewport(1_500f, 2_000f, 446f, canRequestEarlierHistory = false).copy(isUserScrolling = true)
        assertTrue(behavior.handle(TranscriptScrollBehavior.Event.layoutChanged(shrunk)).isEmpty())
        assertEquals(
            listOf(TranscriptScrollBehavior.Command.scrollToBottom(false)),
            behavior.handle(TranscriptScrollBehavior.Event.userSettled(shrunk.copy(isUserScrolling = false), false)),
        )
        assertEquals(TranscriptScrollBehavior.Mode.following, behavior.mode)
    }

    @Test
    fun shortTranscriptsHoldNothing() {
        val behavior = TranscriptScrollBehavior()
        assertTrue(behavior.handle(TranscriptScrollBehavior.Event.endShrank(viewport(0f, 260f, 600f))).isEmpty())
        assertTrue(behavior.handle(TranscriptScrollBehavior.Event.endShrank(viewport(0f, 300f, 700f))).isEmpty())
    }

    @Test
    fun netForwardChangesHoldNothing() {
        val behavior = TranscriptScrollBehavior()
        assertTrue(behavior.handle(TranscriptScrollBehavior.Event.endShrank(viewport(900f, 1_590f, 660f))).isEmpty())
        assertEquals(
            listOf(TranscriptScrollBehavior.Command.setTrailingSpace(20f)),
            behavior.handle(TranscriptScrollBehavior.Event.endShrank(viewport(900f, 1_540f, 660f))),
        )
    }

    @Test
    fun scrollToTopLeavesTheBottom() {
        val behavior = TranscriptScrollBehavior()
        assertEquals(
            listOf(TranscriptScrollBehavior.Command.showJumpButton(true)),
            behavior.handle(TranscriptScrollBehavior.Event.userLeftBottom),
        )
        assertEquals(TranscriptScrollBehavior.Mode.browsing, behavior.mode)
    }

    @Test
    fun earlierHistoryRequestsOncePerTopApproach() {
        val behavior = TranscriptScrollBehavior()
        val top = viewport(300f, 2_000f, 500f)
        assertTrue(behavior.handle(TranscriptScrollBehavior.Event.userScrolled(top)).contains(TranscriptScrollBehavior.Command.requestEarlierHistory))
        assertFalse(behavior.handle(TranscriptScrollBehavior.Event.userScrolled(top)).contains(TranscriptScrollBehavior.Command.requestEarlierHistory))
        assertEquals(
            listOf(TranscriptScrollBehavior.Command.adjustOffset(800f)),
            behavior.handle(TranscriptScrollBehavior.Event.contentChanged(800f, false, top, true)),
        )
        assertTrue(behavior.handle(TranscriptScrollBehavior.Event.userScrolled(top)).contains(TranscriptScrollBehavior.Command.requestEarlierHistory))
    }

    @Test
    fun rowsArrivingBelowABrowsingReaderNeitherMoveThemNorRearmPaging() {
        val behavior = TranscriptScrollBehavior()
        behavior.handle(TranscriptScrollBehavior.Event.userLeftBottom)
        val top = viewport(300f, 2_000f, 500f)
        assertTrue(behavior.handle(TranscriptScrollBehavior.Event.userScrolled(top)).contains(TranscriptScrollBehavior.Command.requestEarlierHistory))
        // A catch-up lands below; the reader's row did not move (the list
        // passes only the anchor's own delta, not UIKit's offset shift).
        val grown = viewport(300f, 4_000f, 500f)
        assertTrue(behavior.handle(TranscriptScrollBehavior.Event.contentChanged(0f, true, grown, true)).isEmpty())
        assertFalse(
            "One request per approach",
            behavior.handle(TranscriptScrollBehavior.Event.userScrolled(grown)).contains(TranscriptScrollBehavior.Command.requestEarlierHistory),
        )
    }

    @Test
    fun anAssistiveScrollJustAboveTheBottomLeavesIt() {
        val behavior = TranscriptScrollBehavior()
        // VoiceOver scrolls 80 pt up: no drag, so no drag start.
        val up = viewport(1_420f, 2_000f, 500f, canRequestEarlierHistory = false)
        assertTrue(behavior.handle(TranscriptScrollBehavior.Event.userScrolled(up)).isEmpty())
        assertEquals(
            listOf(TranscriptScrollBehavior.Command.showJumpButton(true)),
            behavior.handle(TranscriptScrollBehavior.Event.userSettled(up, false)),
        )
        assertEquals(TranscriptScrollBehavior.Mode.browsing, behavior.mode)
    }

    @Test
    fun aFollowingReaderResolvesHeldKeyboardSpaceMidRun() {
        val behavior = TranscriptScrollBehavior()
        // The drag that dismisses the keyboard holds 280 pt under the reader; it
        // rests at the end of that space, still following, so the list lets
        // the space go (running or not) and the end comes back up.
        behavior.handle(TranscriptScrollBehavior.Event.dragBegan(viewport(1_180f, 1_500f, 320f)))
        val grown = viewport(1_182f, 1_500f, 600f, canRequestEarlierHistory = false).copy(isUserScrolling = true)
        assertEquals(
            listOf(TranscriptScrollBehavior.Command.setTrailingSpace(282f)),
            behavior.handle(TranscriptScrollBehavior.Event.layoutChanged(grown)),
        )
        val resting = viewport(1_182f, 1_500f, 600f, 282f, false)
        assertEquals(
            listOf(TranscriptScrollBehavior.Command.scrollToBottom(false)),
            behavior.handle(TranscriptScrollBehavior.Event.userSettled(resting, false)),
        )
        assertEquals(TranscriptScrollBehavior.Mode.following, behavior.mode)
    }

    @Test
    fun completedOverlappingHistoryPageRearmsWithoutChainingFromStaleGeometry() {
        val behavior = TranscriptScrollBehavior()
        val top = viewport(300f, 2_000f, 500f)
        assertTrue(behavior.handle(TranscriptScrollBehavior.Event.userScrolled(top)).contains(TranscriptScrollBehavior.Command.requestEarlierHistory))
        assertTrue(behavior.handle(TranscriptScrollBehavior.Event.contentChanged(0f, false, top, true)).isEmpty())
        assertTrue(behavior.handle(TranscriptScrollBehavior.Event.earlierHistoryRequestCompleted).isEmpty())
        assertEquals(
            listOf(TranscriptScrollBehavior.Command.requestEarlierHistory),
            behavior.handle(TranscriptScrollBehavior.Event.userScrolled(top)),
        )
    }

    @Test
    fun ineligibleViewportDoesNotConsumeRequestLatch() {
        val behavior = TranscriptScrollBehavior()
        val ineligible = viewport(300f, 2_000f, 500f, canRequestEarlierHistory = false)
        val eligible = viewport(300f, 2_000f, 500f)
        assertFalse(behavior.handle(TranscriptScrollBehavior.Event.userScrolled(ineligible)).contains(TranscriptScrollBehavior.Command.requestEarlierHistory))
        assertEquals(
            listOf(TranscriptScrollBehavior.Command.requestEarlierHistory),
            behavior.handle(TranscriptScrollBehavior.Event.userScrolled(eligible)),
        )
    }

    @Test
    fun completionDuringPrependDoesNotStartNextPageBeforeCompensation() {
        val behavior = TranscriptScrollBehavior()
        val top = viewport(300f, 2_000f, 500f)
        assertEquals(
            listOf(TranscriptScrollBehavior.Command.showJumpButton(true), TranscriptScrollBehavior.Command.requestEarlierHistory),
            behavior.handle(TranscriptScrollBehavior.Event.userScrolled(top)),
        )
        assertTrue(behavior.handle(TranscriptScrollBehavior.Event.earlierHistoryRequestCompleted).isEmpty())
        assertEquals(
            listOf(TranscriptScrollBehavior.Command.adjustOffset(800f)),
            behavior.handle(TranscriptScrollBehavior.Event.contentChanged(800f, false, top, true)),
        )
        val compensated = viewport(1_100f, 2_800f, 500f)
        assertTrue(behavior.handle(TranscriptScrollBehavior.Event.layoutChanged(compensated)).isEmpty())
        assertEquals(
            listOf(TranscriptScrollBehavior.Command.requestEarlierHistory),
            behavior.handle(TranscriptScrollBehavior.Event.userScrolled(top)),
        )
    }

    @Test
    fun prependAlwaysPreservesReadingPositionWithoutFollowingBottom() {
        val behavior = TranscriptScrollBehavior()
        val commands = behavior.handle(TranscriptScrollBehavior.Event.contentChanged(
            640f, true, viewport(900f, 2_000f, 500f), true,
        ))
        assertEquals(listOf(TranscriptScrollBehavior.Command.adjustOffset(640f)), commands)
    }

    @Test
    fun browsingAnchorSurvivesGrowthAndShrinkAboveIt() {
        val behavior = TranscriptScrollBehavior()
        behavior.handle(TranscriptScrollBehavior.Event.userScrolled(viewport(500f, 1_600f, 500f)))
        assertEquals(
            listOf(TranscriptScrollBehavior.Command.adjustOffset(37f)),
            behavior.handle(TranscriptScrollBehavior.Event.contentChanged(37f, true, viewport(500f, 1_637f, 500f), true)),
        )
        assertEquals(
            listOf(TranscriptScrollBehavior.Command.adjustOffset(-19f)),
            behavior.handle(TranscriptScrollBehavior.Event.contentChanged(-19f, false, viewport(537f, 1_618f, 500f), true)),
        )
        assertEquals(TranscriptScrollBehavior.Mode.browsing, behavior.mode)
    }

    @Test
    fun browsingWindowKeepsItsLeadingBoundaryAcrossTailAppends() {
        val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")
        val session = OpenCodeSession(
            id = "ses_window",
            title = "Window",
            projectID = "project",
            directory = "/project",
            updatedAt = 0,
        )
        val state = SessionFeature.State(
            server = server,
            session = session,
            cachedItems = (0 until 30).map { OpenCodeTranscriptItem.userText(id = "message-$it", text = "Message $it") },
        )
        state.browsingWindowStartID = state.visibleItems.firstOrNull()?.id
        val leadingID = state.visibleItems.firstOrNull()?.id

        state.items += (30 until 35).map { OpenCodeTranscriptItem.assistantMarkdown(id = "message-$it", blocks = emptyList()) }
        assertEquals(leadingID, state.visibleItems.firstOrNull()?.id)
        assertEquals(25, state.visibleItems.size)

        state.browsingWindowStartID = null
        assertEquals("message-15", state.visibleItems.firstOrNull()?.id)
        assertEquals(20, state.visibleItems.size)
    }

    @Test
    fun browsingReaderIsNeverMovedByArrivals() {
        val behavior = TranscriptScrollBehavior()
        behavior.handle(TranscriptScrollBehavior.Event.userScrolled(viewport(400f, 1_500f, 600f, canRequestEarlierHistory = false)))
        assertEquals(TranscriptScrollBehavior.Mode.browsing, behavior.mode)
        var height = 1_600
        while (height <= 2_400) {
            val commands = behavior.handle(TranscriptScrollBehavior.Event.contentChanged(
                0f, true, viewport(400f, height.toFloat(), 600f), true,
            ))
            assertTrue(commands.isEmpty())
            height += 200
        }
        assertEquals(TranscriptScrollBehavior.Mode.browsing, behavior.mode)
    }

    @Test
    fun followingReaderIsHeldWhenTheEndShrinksThenFillsForward() {
        val behavior = TranscriptScrollBehavior()
        assertEquals(
            listOf(TranscriptScrollBehavior.Command.setTrailingSpace(40f)),
            behavior.handle(TranscriptScrollBehavior.Event.endShrank(viewport(900f, 1_460f, 600f))),
        )
        assertEquals(
            listOf(
                TranscriptScrollBehavior.Command.setTrailingSpace(15f),
                TranscriptScrollBehavior.Command.scrollToBottom(false),
            ),
            behavior.handle(TranscriptScrollBehavior.Event.contentChanged(
                0f, true, viewport(900f, 1_485f, 600f, 40f), true,
            )),
        )
        assertEquals(
            listOf(
                TranscriptScrollBehavior.Command.setTrailingSpace(0f),
                TranscriptScrollBehavior.Command.scrollToBottom(false),
            ),
            behavior.handle(TranscriptScrollBehavior.Event.layoutChanged(viewport(900f, 1_600f, 600f, 15f))),
        )
        assertEquals(TranscriptScrollBehavior.Mode.following, behavior.mode)
    }

    @Test
    fun heldSpaceSurvivesTheKeyboardLeavingWhileFollowing() {
        val behavior = TranscriptScrollBehavior()
        behavior.handle(TranscriptScrollBehavior.Event.endShrank(viewport(900f, 1_460f, 600f)))
        assertEquals(
            listOf(TranscriptScrollBehavior.Command.scrollToBottom(false)),
            behavior.handle(TranscriptScrollBehavior.Event.layoutChanged(viewport(900f, 1_460f, 900f, 40f))),
        )
    }

    @Test
    fun endShrinkingUnderABrowsingReaderIsLeftToTheLayoutFloor() {
        val behavior = TranscriptScrollBehavior()
        behavior.handle(TranscriptScrollBehavior.Event.userScrolled(viewport(200f, 2_000f, 500f)))
        assertTrue(behavior.handle(TranscriptScrollBehavior.Event.endShrank(viewport(200f, 1_900f, 500f))).isEmpty())
    }

    @Test
    fun pagingThreeTimesThenJumpLandsAtBottom() {
        val behavior = TranscriptScrollBehavior()
        for (page in 0 until 3) {
            val top = viewport(100f, (1_000 + page * 700).toFloat(), 500f)
            assertTrue(behavior.handle(TranscriptScrollBehavior.Event.userScrolled(top)).contains(TranscriptScrollBehavior.Command.requestEarlierHistory))
            assertEquals(
                listOf(TranscriptScrollBehavior.Command.adjustOffset(700f)),
                behavior.handle(TranscriptScrollBehavior.Event.contentChanged(700f, false, top, true)),
            )
        }
        assertEquals(
            listOf(
                TranscriptScrollBehavior.Command.setTrailingSpace(0f),
                TranscriptScrollBehavior.Command.showJumpButton(false),
                TranscriptScrollBehavior.Command.scrollToBottom(true),
            ),
            behavior.handle(TranscriptScrollBehavior.Event.jumpToLatestTapped),
        )
        assertEquals(TranscriptScrollBehavior.Mode.following, behavior.mode)
    }

    @Test
    fun twoWindowsBeforeFirstLayoutDeferScrollingUntilLayoutIsReady() {
        val behavior = TranscriptScrollBehavior()
        val unlaidOut = viewport(2_000f, 0f, 0f)
        assertTrue(behavior.handle(TranscriptScrollBehavior.Event.contentChanged(0f, true, unlaidOut, false)).isEmpty())
        assertTrue(behavior.handle(TranscriptScrollBehavior.Event.contentChanged(0f, true, unlaidOut, false)).isEmpty())
        val laidOut = viewport(0f, 1_200f, 500f)
        assertEquals(
            listOf(
                TranscriptScrollBehavior.Command.scrollToBottom(false),
                TranscriptScrollBehavior.Command.requestEarlierHistory,
            ),
            behavior.handle(TranscriptScrollBehavior.Event.layoutChanged(laidOut)),
        )
    }

    @Test
    fun jumpRequestsMotionAndSessionChangeResetsState() {
        val behavior = TranscriptScrollBehavior()
        assertEquals(
            TranscriptScrollBehavior.Command.scrollToBottom(true),
            behavior.handle(TranscriptScrollBehavior.Event.jumpToLatestTapped).last(),
        )
        behavior.handle(TranscriptScrollBehavior.Event.userScrolled(viewport(0f, 2_000f, 500f)))
        assertEquals(
            listOf(TranscriptScrollBehavior.Command.setTrailingSpace(0f), TranscriptScrollBehavior.Command.showJumpButton(false)),
            behavior.handle(TranscriptScrollBehavior.Event.sessionChanged),
        )
        assertEquals(TranscriptScrollBehavior.Mode.following, behavior.mode)
        assertTrue(behavior.handle(TranscriptScrollBehavior.Event.userScrolled(viewport(0f, 2_000f, 500f))).contains(TranscriptScrollBehavior.Command.requestEarlierHistory))
    }

    @Test
    fun keyboardDismissingUnderABrowsingReaderReservesSpace() {
        val behavior = TranscriptScrollBehavior()
        behavior.handle(TranscriptScrollBehavior.Event.userScrolled(viewport(1_000f, 1_500f, 320f, canRequestEarlierHistory = false)))
        assertEquals(TranscriptScrollBehavior.Mode.browsing, behavior.mode)
        assertEquals(
            listOf(TranscriptScrollBehavior.Command.setTrailingSpace(100f)),
            behavior.handle(TranscriptScrollBehavior.Event.layoutChanged(viewport(1_000f, 1_500f, 600f, canRequestEarlierHistory = false))),
        )
        assertTrue(behavior.handle(TranscriptScrollBehavior.Event.userScrolled(viewport(1_000f, 1_500f, 600f, 100f, false))).isEmpty())
        assertEquals(TranscriptScrollBehavior.Mode.browsing, behavior.mode)
    }

    @Test
    fun positionSpaceDrainsAsTheReaderScrollsUp() {
        val behavior = TranscriptScrollBehavior()
        behavior.handle(TranscriptScrollBehavior.Event.userScrolled(viewport(500f, 1_500f, 320f, canRequestEarlierHistory = false)))
        assertTrue(behavior.handle(TranscriptScrollBehavior.Event.userScrolled(viewport(1_080f, 1_500f, 320f, canRequestEarlierHistory = false))).isEmpty())
        assertEquals(TranscriptScrollBehavior.Mode.browsing, behavior.mode)
        assertEquals(
            listOf(TranscriptScrollBehavior.Command.setTrailingSpace(180f)),
            behavior.handle(TranscriptScrollBehavior.Event.layoutChanged(viewport(1_080f, 1_500f, 600f, canRequestEarlierHistory = false))),
        )
        assertEquals(
            listOf(TranscriptScrollBehavior.Command.setTrailingSpace(100f)),
            behavior.handle(TranscriptScrollBehavior.Event.userScrolled(viewport(1_000f, 1_500f, 600f, 180f, false))),
        )
        assertEquals(
            listOf(TranscriptScrollBehavior.Command.setTrailingSpace(0f)),
            behavior.handle(TranscriptScrollBehavior.Event.userScrolled(viewport(500f, 1_500f, 600f, 100f, false))),
        )
        assertEquals(
            listOf(
                TranscriptScrollBehavior.Command.showJumpButton(false),
                TranscriptScrollBehavior.Command.scrollToBottom(false),
            ),
            behavior.handle(TranscriptScrollBehavior.Event.userSettled(viewport(900f, 1_500f, 600f, canRequestEarlierHistory = false), false)),
        )
        assertEquals(TranscriptScrollBehavior.Mode.following, behavior.mode)
    }

    @Test
    fun openingADisclosureAtTheBottomLeavesItAndClosingItReturns() {
        val behavior = TranscriptScrollBehavior()
        assertEquals(
            listOf(TranscriptScrollBehavior.Command.showJumpButton(true)),
            behavior.handle(TranscriptScrollBehavior.Event.disclosureToggled(viewport(1_000f, 1_860f, 500f))),
        )
        assertEquals(TranscriptScrollBehavior.Mode.browsing, behavior.mode)
        assertTrue(behavior.handle(TranscriptScrollBehavior.Event.contentChanged(
            0f, true, viewport(1_000f, 1_900f, 500f), true,
        )).isEmpty())
        assertEquals(
            listOf(TranscriptScrollBehavior.Command.showJumpButton(false)),
            behavior.handle(TranscriptScrollBehavior.Event.disclosureToggled(viewport(1_000f, 1_500f, 500f))),
        )
        assertEquals(TranscriptScrollBehavior.Mode.following, behavior.mode)
    }

    @Test
    fun aDisclosureDrainsHeldSpaceBeforeJudgingTheEnd() {
        val behavior = TranscriptScrollBehavior()
        assertEquals(
            listOf(TranscriptScrollBehavior.Command.setTrailingSpace(50f)),
            behavior.handle(TranscriptScrollBehavior.Event.disclosureToggled(viewport(600f, 1_050f, 500f, 100f))),
        )
        assertEquals(TranscriptScrollBehavior.Mode.following, behavior.mode)
        assertEquals(
            listOf(
                TranscriptScrollBehavior.Command.setTrailingSpace(0f),
                TranscriptScrollBehavior.Command.showJumpButton(true),
            ),
            behavior.handle(TranscriptScrollBehavior.Event.disclosureToggled(viewport(600f, 1_300f, 500f, 50f))),
        )
        assertEquals(TranscriptScrollBehavior.Mode.browsing, behavior.mode)
    }

    @Test
    fun aDisclosureThatLeavesTheEndInViewKeepsFollowing() {
        val behavior = TranscriptScrollBehavior()
        assertTrue(behavior.handle(TranscriptScrollBehavior.Event.disclosureToggled(viewport(1_000f, 1_500f, 500f))).isEmpty())
        assertTrue(behavior.handle(TranscriptScrollBehavior.Event.disclosureToggled(viewport(0f, 300f, 500f))).isEmpty())
        assertEquals(TranscriptScrollBehavior.Mode.following, behavior.mode)
        behavior.handle(TranscriptScrollBehavior.Event.userScrolled(viewport(200f, 2_000f, 500f)))
        assertTrue(behavior.handle(TranscriptScrollBehavior.Event.disclosureToggled(viewport(200f, 2_400f, 500f))).isEmpty())
        assertEquals(TranscriptScrollBehavior.Mode.browsing, behavior.mode)
    }

    @Test
    fun browsingAboveTheBottomNeedsNoPositionSpace() {
        val behavior = TranscriptScrollBehavior()
        behavior.handle(TranscriptScrollBehavior.Event.userScrolled(viewport(200f, 2_000f, 500f)))
        assertEquals(TranscriptScrollBehavior.Mode.browsing, behavior.mode)
        assertTrue(behavior.handle(TranscriptScrollBehavior.Event.layoutChanged(viewport(200f, 2_000f, 800f))).isEmpty())
    }

    private fun viewport(
        offset: Float,
        height: Float,
        visible: Float,
        trailing: Float = 0f,
        canRequestEarlierHistory: Boolean = true,
    ) = TranscriptScrollBehavior.Viewport(offset, height, visible, trailing, canRequestEarlierHistory)
}

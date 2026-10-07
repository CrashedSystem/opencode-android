@file:PortedFrom("OpenCodeTests/TranscriptScrollBehaviorTests.swift", "553a407", partial = "pure follow and browse policy; UIKit list and SessionFeature window tests stay with those files")

package ai.opencode.mobilecode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptScrollBehaviorTests {
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

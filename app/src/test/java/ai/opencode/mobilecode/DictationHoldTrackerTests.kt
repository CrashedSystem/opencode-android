@file:PortedFrom("OpenCodeTests/DictationHoldTrackerTests.swift", "27556a1")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.tca.expect
import androidx.compose.ui.geometry.Offset
import org.junit.Test

/// Holding the mic: sliding arms lock or cancel along one axis, with a dead
/// zone on the diagonal and hysteresis on the way back.
class DictationHoldTrackerTests {
    private fun track(points: List<Pair<Float, Float>>): DictationHoldTracker {
        val tracker = DictationHoldTracker()
        for ((x, y) in points) tracker.update(Offset(x, y))
        return tracker
    }

    @Test
    fun slidingUpArmsLockAt56Points() {
        expect(track(listOf(0f to -20f, 0f to -55f)).armed == null)
        expect(track(listOf(0f to -20f, 0f to -56f)).armed == SessionFeature.State.DictationHold.Arm.lock)
        expect(track(listOf(0f to -20f, 0f to -56f)).axis == DictationHoldTracker.Axis.up)
    }

    @Test
    fun slidingLeftArmsCancelAt96Points() {
        expect(track(listOf(-20f to 0f, -95f to 0f)).armed == null)
        expect(track(listOf(-20f to 0f, -96f to 0f)).armed == SessionFeature.State.DictationHold.Arm.cancel)
    }

    @Test
    fun eachDisarms14PointsShortOfItsLine() {
        expect(track(listOf(0f to -20f, 0f to -60f, 0f to -43f)).armed == SessionFeature.State.DictationHold.Arm.lock)
        expect(track(listOf(0f to -20f, 0f to -60f, 0f to -41f)).armed == null)
        expect(track(listOf(-20f to 0f, -100f to 0f, -83f to 0f)).armed == SessionFeature.State.DictationHold.Arm.cancel)
        expect(track(listOf(-20f to 0f, -100f to 0f, -81f to 0f)).armed == null)
    }

    @Test
    fun aDiagonalArmsNothing() {
        val diagonal = track(listOf(-10f to -10f, -40f to -40f, -120f to -120f))
        expect(diagonal.axis == null)
        expect(diagonal.armed == null)
        // Within about 35° of an axis still counts as that axis.
        expect(track(listOf(-14f to -20f, -50f to -72f)).armed == SessionFeature.State.DictationHold.Arm.lock)
        expect(track(listOf(-20f to -14f, -100f to -70f)).armed == SessionFeature.State.DictationHold.Arm.cancel)
    }

    @Test
    fun theAxisSticksUntilTheFingerComesBack() {
        // Chosen up, then swung far left: still only up counts.
        expect(track(listOf(0f to -20f, -120f to -20f)).armed == null)
        // Back inside the slop, left can be chosen.
        val reset = track(listOf(0f to -20f, 0f to -5f, -20f to 0f, -100f to 0f))
        expect(reset.axis == DictationHoldTracker.Axis.left)
        expect(reset.armed == SessionFeature.State.DictationHold.Arm.cancel)
    }

    @Test
    fun onlyShortStillPressesAreTaps() {
        expect(track(listOf(2f to 3f)).isTap(after = 0.2))
        expect(!track(listOf(2f to 3f)).isTap(after = 0.31))
        expect(!track(listOf(12f to 0f, 2f to 0f)).isTap(after = 0.2))
        expect(!track(listOf(0f to -20f, 0f to -60f)).isTap(after = 0.2))
    }

    /// The system taking the touch (Control Center, a call banner, another
    /// finger, a scroll view, the app leaving) is not a lift, whenever it comes.
    @Test
    fun aCancelledTouchIsNeverATapOrARelease() {
        val still = track(listOf(2f to 3f))
        expect(still.ending(after = 0.1, cancelled = true) == DictationHoldTracker.Ending.interrupted)
        expect(still.ending(after = 1.2, cancelled = true) == DictationHoldTracker.Ending.interrupted)
        expect(track(listOf(0f to -20f, 0f to -60f)).ending(after = 1.2, cancelled = true) == DictationHoldTracker.Ending.interrupted)
        expect(still.ending(after = 0.1, cancelled = false) == DictationHoldTracker.Ending.tap)
        expect(still.ending(after = 1.2, cancelled = false) == DictationHoldTracker.Ending.release)
    }

    /// What one recording's hold showed never carries into the next: the
    /// second starts at the mic's size, with no lock target or chevrons.
    @Test
    fun aSecondDictationStartsWithNoCircleOrGuides() {
        // The first: held long enough to grow, slid up to lock, and the lock
        // target landed in Stop before the bar went away.
        val visuals = DictationHoldVisuals().begin().copy(showsGuides = true, lockDropDone = true)
        for (start in listOf(false, true)) {
            // Ended (the bar left), or a new press began without an end between.
            val next = if (start) visuals.begin() else visuals.end()
            val pose = DictationBarPose(
                hold = SessionFeature.State.DictationHold(text = ""),
                phase = SessionFeature.State.DictationPhase.preparing,
                drag = DictationDrag(),
                reducedMotion = false,
                visuals = next,
            )
            expect(!pose.guidesShown, "start: $start")
            expect(!pose.showsGrownCircle, "start: $start")
            expect(!next.lockDropDone, "start: $start")
        }
    }
}

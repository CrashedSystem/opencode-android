@file:PortedFrom("OpenCodeTests/MediaDismissDragTests.swift", "74bd186")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.tca.expect
import kotlin.math.abs
import org.junit.Test

class MediaDismissDragTests {
    val drag = MediaDismissDrag(screenHeight = 900.0)

    @Test
    fun shrinksAndRevealsTheSessionAsItIsPulledDown() {
        expect(drag.scale(at = 0.0) == 1.0)
        expect(drag.backdropAlpha(at = 0.0) == 1.0)
        expect(drag.scale(at = 225.0) == 0.8)
        expect(drag.backdropAlpha(at = 225.0) == 0.5)
        expect(drag.scale(at = 450.0) == MediaDismissDrag.minimumScale)
        expect(drag.backdropAlpha(at = 450.0) == 0.0)
        // Past the range it holds; above the start it doesn't grow.
        expect(drag.scale(at = 2000.0) == MediaDismissDrag.minimumScale)
        expect(drag.scale(at = -100.0) == 1.0)
        expect(drag.backdropAlpha(at = -100.0) == 1.0)
    }

    @Test
    fun roundsTowardTheThumbnailWithTheScale() {
        expect(drag.cornerRadius(at = 0.0, toward = 14.0) == 0.0)
        expect(drag.cornerRadius(at = 225.0, toward = 14.0) == 7.0)
        expect(drag.cornerRadius(at = 450.0, toward = 14.0) == 14.0)
        expect(drag.cornerRadius(at = 2000.0, toward = 14.0) == 14.0)
        expect(drag.cornerRadius(at = -50.0, toward = 14.0) == 0.0)
        // The radius follows the scale, so where the image is, its shape is too.
        for (distance in listOf(0.0, 60.0, 180.0, 300.0, 450.0)) {
            val shrunk = (1 - drag.scale(at = distance)) / (1 - MediaDismissDrag.minimumScale)
            expect(abs(drag.cornerRadius(at = distance, toward = 20.0) - 20 * shrunk) < 0.0001)
        }
    }

    @Test
    fun aCaughtSpringBackContinuesFromItsScale() {
        for (distance in listOf(0.0, 90.0, 300.0, 450.0)) {
            expect(abs(drag.distance(atScale = drag.scale(at = distance)) - distance) < 0.001)
        }
    }

    @Test
    fun letsGoPastTheDistanceOrWithAFlickDismisses() {
        expect(!MediaDismissDrag.dismisses(distance = 70.0, velocity = 0.0))
        expect(MediaDismissDrag.dismisses(distance = 200.0, velocity = 0.0))
        expect(MediaDismissDrag.dismisses(distance = 40.0, velocity = 1200.0))
        expect(!MediaDismissDrag.dismisses(distance = 200.0, velocity = -600.0), "Flicked back up, it stays")
    }
}

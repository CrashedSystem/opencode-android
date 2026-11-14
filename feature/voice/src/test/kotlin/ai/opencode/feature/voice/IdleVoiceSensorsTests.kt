package ai.opencode.feature.voice

import ai.opencode.core.dependencies.DependencyValues
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/** Between calls, the GPT-Live warm-up stops a minute after anyone could be heading for a call. */
class IdleVoiceSensorsTests {
    @Test fun aWarmWindowWarmsSixTimesAndThenNothing() = runTest {
        var warms = 0
        val window = launch { LiveSocketWarmer.warmWindow { warms += 1 } }
        advanceTimeBy(120.seconds)
        runCurrent()
        assertTrue(window.isCompleted)
        assertEquals(6, warms)
        advanceTimeBy(3_600.seconds)
        assertEquals(6, warms)
    }

    @Test fun aCancelledWindowStopsWarming() = runTest {
        var warms = 0
        val window = launch { LiveSocketWarmer.warmWindow { warms += 1 } }
        advanceTimeBy(25.seconds)
        window.cancel()
        advanceTimeBy(120.seconds)
        assertEquals(3, warms)
    }

    @Test fun webRTCWarmUpsCloseTogetherSendOneRequest() {
        val requests = mutableListOf<String>()
        val start = Instant.ofEpochSecond(1_000)
        val values = DependencyValues.testing().update { this[LiveTransportChoice] = LiveTransportChoice() }
        DependencyValues.withValues(values) {
            val warmer = LiveSocketWarmer(warmRTC = { key -> requests += key; Job() })
            warmer.warm("k", now = start)
            // Another screen, a nudge, the call itself: the connection is still open.
            warmer.warm("k", now = start.plusSeconds(1))
            warmer.warm("k", now = start.plusSeconds(4))
            assertEquals(listOf("k"), requests)
            warmer.warm("k", now = start.plusSeconds((LiveSocketWarmer.maxAge / 2).toLong()))
            warmer.warm("other", now = start.plusSeconds((LiveSocketWarmer.maxAge / 2).toLong() + 1))
        }
        assertEquals(listOf("k", "k", "other"), requests)
    }
}

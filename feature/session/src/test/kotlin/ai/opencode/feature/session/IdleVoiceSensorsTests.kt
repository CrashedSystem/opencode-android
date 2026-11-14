package ai.opencode.feature.session

import ai.opencode.core.sharing.SharedKeys
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.feature.voice.LiveSocketWarmerClient
import ai.opencode.feature.voice.VoiceServerChoice
import ai.opencode.feature.voice.VoiceSharedKeys
import ai.opencode.server.OpenCodeServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/** Between calls, the GPT-Live warm-up does no work on an idle session; a call warms as it starts. */
class IdleVoiceSensorsTests {
    private val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")

    @Test fun startingAPhoneKeyCallWarmsItsConnectionOnce() = sessionTest { h ->
        h.sharedStore.reference(VoiceSharedKeys.voiceServerChoice).set(VoiceServerChoice.Phone)
        h.sharedStore.reference(SharedKeys.voiceModeEnabled).set(true)
        h.sharedStore.reference(SharedKeys.openAIAPIKey).set(" sk-test ")
        // A draft with no project: the tap opens the project picker, so no call runs, but the
        // connection still warms for the call that follows.
        val state = h.state { SessionFeature.State.draft(server, null, emptyList()) }
        assertTrue(h.state { state.voiceRoute.warmsPhoneConnection })
        val warms = Collections.synchronizedList(mutableListOf<String>())
        val store = h.store(state) {
            this[LiveSocketWarmerClient] = LiveSocketWarmerClient(warm = { warms += it }, cool = {})
        }
        store.exhaustive = false
        store.send(Action.CallButtonTapped)
        store.receive(Action.VoiceTapped)
        store.finish()
        assertEquals(listOf("sk-test"), warms.toList())
    }
}

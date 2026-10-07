@file:PortedFrom("OpenCodeTests/HapticsAuditTests.swift", "553a407")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.tca.DateGenerator
import ai.opencode.mobilecode.tca.DependencyValues
import ai.opencode.mobilecode.tca.Exhaustivity
import ai.opencode.mobilecode.tca.TestDependencies
import ai.opencode.mobilecode.tca.TestStore
import ai.opencode.mobilecode.tca.UUIDGenerator
import ai.opencode.mobilecode.tca.date
import ai.opencode.mobilecode.tca.expect
import ai.opencode.mobilecode.tca.uuid
import java.time.Instant
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/// Records every haptic by name.
private fun recording(log: MutableList<String>) = HapticsClient(
    selection = { log += "selection" },
    impact = { log += "impact" },
    success = { log += "success" },
    error = { log += "error" },
    attention = { log += "attention" },
    voice = { haptic -> log += "voice.${haptic.name}" },
    drawer = { log += "drawer" },
)

/// Which interactions play which haptics (`.lab-output/haptics/README.md`).
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HapticsAuditTests {
    @get:Rule
    val dependencies = TestDependencies()

    private val server = OpenCodeServer(url = "http://127.0.0.1:9", username = "opencode", password = "pw")
    private val session = OpenCodeSession(id = "ses_1", title = "Chat", projectID = "proj_1", directory = "/tmp/demo", updatedAt = System.currentTimeMillis())

    private fun sessionStore(
        state: SessionFeature.State? = null,
        log: MutableList<String>,
        dependencies: (DependencyValues) -> Unit = {},
    ): TestStore<SessionFeature.State, SessionFeature.Action> {
        val store = TestStore(
            initialState = { state ?: SessionFeature.State(server = server, session = session, cachedItems = emptyList()) },
            withDependencies = {
                it.uuid = UUIDGenerator.incrementing()
                it.haptics = recording(log)
                it.openCodeClient.sendPrompt = { _, _, _, _, _, _, _ -> }
                it.openCodeClient.waitForIdle = { _, _ -> }
                it.openCodeClient.transcript = { _, _, _ -> emptyList() }
                it.openCodeClient.familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) }
                dependencies(it)
            },
        ) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
        return store
    }

    @Test
    fun sendingSteeredOrQueuedIsOneImpact() {
        for (action in listOf(SessionFeature.Action.sendTapped, SessionFeature.Action.queueTapped)) {
            val log = mutableListOf<String>()
            val state = SessionFeature.State(server = server, session = session, cachedItems = emptyList())
            state.draft = "Ship it"
            val store = sessionStore(state, log)
            store.send(action)
            expect(log == listOf("impact"))
            store.skipInFlightEffects()
        }
    }

    @Test
    fun aFailedSendIsAnError() {
        val log = mutableListOf<String>()
        val store = sessionStore(null, log)
        store.send(
            SessionFeature.Action.promptFailed(
                sessionID = session.id,
                error = OpenCodeClientError(message = "Offline"),
                pending = SessionFeature.PendingPrompt(
                    messageID = "msg_1",
                    text = "Ship it",
                    attachments = emptyList(),
                    optimisticIDs = emptySet(),
                    requestEpoch = 1,
                ),
            ),
        )
        expect(log == listOf("error"))
    }

    @Test
    fun answeringARequestIsOneSuccessNotATapAndASuccess() {
        val permission = protocolJson.decodeFromString<OpenCodePermissionRequest>(
            """
            {"id":"per_1","sessionID":"ses_1","action":"shell","resources":["git status"],
             "source":{"type":"tool","messageID":"msg_1","id":"call_1"}}
            """.trimIndent(),
        )
        val log = mutableListOf<String>()
        val store = sessionStore(null, log) {
            it.openCodeClient.replyPermission = { _, _, _, _ -> }
        }
        // A request that arrives while the session is open opens its review: a single attention tap.
        store.send(SessionFeature.Action.pendingRequestsResponse(sessionID = session.id, result = Result.success(OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()))))
        store.send(
            SessionFeature.Action.pendingRequestsResponse(
                sessionID = session.id,
                result = Result.success(OpenCodePendingRequests(permissions = listOf(permission), forms = emptyList())),
            ),
        )
        expect(store.state.activePicker == SessionFeature.State.ActivePicker.requests)
        expect(log == listOf("attention"))
        store.send(SessionFeature.Action.permissionReplyTapped(permission.id, OpenCodePermissionReply.once))
        store.receive<SessionFeature.Action.requestResponseFinished>()
        expect(log == listOf("attention", "success"))
    }

    @Test
    fun anIncompleteFormIsAnError() {
        val form = protocolJson.decodeFromString<OpenCodeFormRequest>(
            """
            {"id":"frm_1","sessionID":"ses_1","title":"Deploy","fields":[{"key":"note","type":"string","title":"Note","required":true}]}
            """.trimIndent(),
        )
        val log = mutableListOf<String>()
        val store = sessionStore(null, log)
        store.send(SessionFeature.Action.pendingRequestsResponse(sessionID = session.id, result = Result.success(OpenCodePendingRequests(permissions = emptyList(), forms = listOf(form)))))
        log.clear()
        store.send(SessionFeature.Action.formSubmitTapped(form.id))
        expect(store.state.requestErrors[form.id] != null)
        expect(log == listOf("error"))
    }

    @Test
    fun dictationStartsWithAnImpactAndLocksWithASelection() {
        val log = mutableListOf<String>()
        val feed = Channel<String>(Channel.UNLIMITED)
        val store = sessionStore(null, log) {
            it.date = DateGenerator.constant(Instant.ofEpochSecond(1_000))
            it.dictation.start = { feed.consumeAsFlow() }
            it.dictation.stop = { feed.close() }
        }
        store.send(SessionFeature.Action.micPressBegan)
        store.receive<SessionFeature.Action.dictationReady>()
        store.send(SessionFeature.Action.micPressLocked)
        expect(log == listOf("impact", "selection"))
        feed.close()
        store.skipInFlightEffects()
    }

    @Test
    fun homeHaptics() {
        val log = mutableListOf<String>()
        val state = HomeFeature.State(server = server, snapshot = OpenCodeHomeSnapshot(projects = emptyList(), sessions = listOf(session), activeSessionIDs = emptySet()))
        val store = TestStore(
            initialState = { state },
            withDependencies = {
                it.haptics = recording(log)
                it.openCodeClient.deleteSession = { _, _ -> }
            },
        ) { HomeFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
        store.send(HomeFeature.Action.togglePin(session.id))
        store.send(HomeFeature.Action.togglePin(session.id))
        expect(log == listOf("selection", "selection"))
        store.send(HomeFeature.Action.deleteSessionTapped(session.id))
        expect(log.size == 2, "Asking to delete plays nothing; the confirmation dialog is the feedback")
        store.send(HomeFeature.Action.deleteSessionConfirmed)
        expect(log == listOf("selection", "selection", "impact"))
        store.skipInFlightEffects()
    }

    @Test
    fun compactingCompletesWithSuccessAndForkingNavigatesQuietly() {
        val log = mutableListOf<String>()
        val store = TestStore(
            initialState = { SessionSheetFeature.State(server = server, sessionID = session.id) },
            withDependencies = {
                it.haptics = recording(log)
                it.openCodeClient.compactSession = { _, _ -> }
            },
        ) { SessionSheetFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
        store.send(SessionSheetFeature.Action.compactTapped)
        store.receive<SessionSheetFeature.Action.compactFinished>()
        expect(log == listOf("success"))
        store.send(SessionSheetFeature.Action.forkFinished(Result.success(session)))
        expect(log == listOf("success"))
        store.skipReceivedActions()
    }
}

class LiveHapticsTests {
    @Ignore("OpenCode/HapticsClient.kt LiveHaptics scheduler not ported")
    @Test
    fun aRequestArrivingInTheBackgroundPlaysNothing() {}
}

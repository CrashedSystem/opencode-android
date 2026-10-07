@file:PortedFrom("OpenCodeTests/RequestReviewTests.swift", "553a407")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.tca.Exhaustivity
import ai.opencode.mobilecode.tca.TestDependencies
import ai.opencode.mobilecode.tca.TestStore
import ai.opencode.mobilecode.tca.expect
import java.text.NumberFormat
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RequestReviewTests {
    @get:Rule
    val dependencies = TestDependencies()

    private val session = OpenCodeSession(
        id = "ses_review",
        title = "Review",
        projectID = "proj",
        directory = "/tmp/review",
        updatedAt = System.currentTimeMillis(),
    )

    /// A question the agent asked, as current V2 sends it: a form.
    private fun question(id: String = "frm_review") = protocolJson.decodeFromString<OpenCodeFormRequest>(
        """{"id":"$id","sessionID":"ses_review","title":"Questions","metadata":{"kind":"question"},"fields":[{"key":"q0","type":"string","title":"Approach","description":"Which approach?","options":[{"value":"Native","label":"Native"}],"custom":true}]}""",
    )

    private val request: OpenCodeFormRequest get() = question()

    private val state: SessionFeature.State
        get() {
            val state = SessionFeature.State(
                server = OpenCodeServer(url = "http://127.0.0.1:4105", username = "", password = ""),
                session = session,
                cachedItems = emptyList(),
            )
            val request = request
            state.formRequests = listOf(request)
            state.formAnswers += request.id to mapOf("q0" to OpenCodeFormValue.string("Native"))
            state.selectedRequestID = request.id
            state.activePicker = SessionFeature.State.ActivePicker.requests
            return state
        }

    @Test
    fun successfulReplyCannotBeResubmittedThroughStalePoll() {
        val request = request
        val pending = OpenCodePendingRequests(permissions = emptyList(), forms = listOf(request))
        val store = TestStore(
            initialState = { state },
            withDependencies = { it.openCodeClient.familyPendingRequests = { _, _, _ -> pending } },
        ) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
        store.send(SessionFeature.Action.requestResponseFinished(sessionID = session.id, requestID = request.id, error = null))
        expect(store.state.activePicker == null)
        expect(store.state.selectedRequestID == null)
        expect(store.state.requestNotice == null)
        store.receive<SessionFeature.Action.pendingRequestsResponse>()
        expect(store.state.formRequests.isEmpty())
        expect(store.state.activePicker == null)
        expect(store.state.selectedRequestID == null)
        expect(store.state.requestNotice == null)
        // An unimplemented network dependency fails the test if a second reply is attempted.
        store.send(SessionFeature.Action.formSubmitTapped(request.id))
    }

    @Test
    fun successfulReplyDismissesReviewWhileOtherRequestsRemainPending() {
        val request = request
        val permission = OpenCodePermissionRequest(id = "per_pending", sessionID = session.id, action = "shell", resources = listOf("git status"), save = null)
        val initial = state
        initial.permissionRequests = listOf(permission)
        initial.requestNotice = "Previous notice"
        val pending = OpenCodePendingRequests(permissions = listOf(permission), forms = listOf(request))
        val store = TestStore(
            initialState = { initial },
            withDependencies = { it.openCodeClient.familyPendingRequests = { _, _, _ -> pending } },
        ) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(SessionFeature.Action.requestResponseFinished(sessionID = session.id, requestID = request.id, error = null))
        expect(store.state.activePicker == null)
        expect(store.state.selectedRequestID == null)
        expect(store.state.requestNotice == null)
        expect(store.state.reviewRequestIDs == listOf(permission.id))
        store.receive<SessionFeature.Action.pendingRequestsResponse>()
        expect(store.state.activePicker == null)
        expect(store.state.selectedRequestID == null)
        expect(store.state.requestNotice == null)
        expect(store.state.reviewRequestIDs == listOf(permission.id))

        store.send(SessionFeature.Action.reviewRequestsTapped)
        expect(store.state.activePicker == SessionFeature.State.ActivePicker.requests)
        expect(store.state.selectedRequestID == permission.id)
        store.receive<SessionFeature.Action.pendingRequestsResponse>()
        expect(store.state.reviewRequestIDs == listOf(permission.id))
    }

    @Test
    fun successfulReplyDoesNotDismissUnrelatedPicker() {
        val request = request
        val initial = state
        initial.activePicker = SessionFeature.State.ActivePicker.model
        initial.requestNotice = "Previous notice"
        val store = TestStore(
            initialState = { initial },
            withDependencies = { it.openCodeClient.familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList()) } },
        ) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(SessionFeature.Action.requestResponseFinished(sessionID = session.id, requestID = request.id, error = null))
        expect(store.state.activePicker == SessionFeature.State.ActivePicker.model)
        expect(store.state.selectedRequestID == null)
        expect(store.state.requestNotice == null)
        store.receive<SessionFeature.Action.pendingRequestsResponse>()
        expect(store.state.activePicker == SessionFeature.State.ActivePicker.model)
    }

    @Test
    fun expiredRequestLosesItsActionsWithoutClaimingSuccess() {
        val request = request
        val store = TestStore(initialState = { state }) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
        store.send(SessionFeature.Action.pendingRequestsResponse(sessionID = session.id, result = Result.success(OpenCodePendingRequests(permissions = emptyList()))))
        expect(store.state.selectedRequestID == null)
        expect(store.state.requestNotice?.contains("no longer pending") == true)
        expect(store.state.completedRequestIDs.isEmpty())
        store.send(SessionFeature.Action.formSubmitTapped(request.id))
    }

    @Test
    fun newRequestOpensReviewButPollingDoesNotReopenDismissedSheet() {
        val request = request
        val initial = state
        initial.formRequests = emptyList()
        initial.activePicker = null
        initial.selectedRequestID = null
        val store = TestStore(initialState = { initial }) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
        val snapshot = OpenCodePendingRequests(permissions = emptyList(), forms = listOf(request))
        store.send(SessionFeature.Action.pendingRequestsResponse(sessionID = session.id, result = Result.success(snapshot)))
        expect(store.state.activePicker == SessionFeature.State.ActivePicker.requests)
        expect(store.state.selectedRequestID == request.id)
        val opened = store.state
        val dismissed = SessionFeature.State(server = opened.server, session = checkNotNull(opened.session), cachedItems = opened.items)
        dismissed.formRequests = opened.formRequests
        dismissed.permissionRequests = opened.permissionRequests
        dismissed.formAnswers = opened.formAnswers
        dismissed.selectedRequestID = opened.selectedRequestID
        val reopened = TestStore(initialState = { dismissed }) { SessionFeature() }
        reopened.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
        reopened.send(SessionFeature.Action.pendingRequestsResponse(sessionID = session.id, result = Result.success(snapshot)))
        expect(reopened.state.activePicker == null)
    }

    @Test
    fun settledRetryFailureIsVisibleEvenWithoutPendingRequests() {
        val initial = state
        initial.formRequests = emptyList()
        initial.selectedRequestID = null
        initial.activePicker = null
        initial.requestLoadAttempt = SessionFeature.State.RequestLoadAttempt.reconnect
        val store = TestStore(initialState = { initial }) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
        store.send(SessionFeature.Action.pendingRequestsResponse(sessionID = session.id, result = Result.failure(OpenCodeClientError(message = "Could not load requests"))))
        expect(store.state.requestLoadError == "Could not load requests")
    }

    @Test
    fun modernQuestionFormOpensReviewAndKeepsItsToolIdentity() {
        val form = protocolJson.decodeFromString<OpenCodeFormRequest>(
            """{"id":"frm_question","sessionID":"ses_review","title":"Questions","metadata":{"kind":"question","tool":{"messageID":"msg_1","id":"call_1"}},"fields":[{"key":"q0","type":"string","title":"Approach","description":"Which approach?","options":[{"value":"Native","label":"Native"}],"custom":true}]}""",
        )
        val initial = state
        initial.formRequests = emptyList()
        initial.activePicker = null
        initial.selectedRequestID = null
        val store = TestStore(initialState = { initial }) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
        store.send(SessionFeature.Action.pendingRequestsResponse(sessionID = session.id, result = Result.success(OpenCodePendingRequests(permissions = emptyList(), forms = listOf(form)))))
        expect(form.isQuestion)
        expect(form.toolCallID == "call_1")
        expect(form.toolMessageID == "msg_1")
        expect(store.state.activePicker == SessionFeature.State.ActivePicker.requests)
        expect(store.state.selectedRequestID == form.id)
        expect(store.state.reviewRequestIDs == listOf(form.id))
    }

    @Test
    fun failureRetainsAnswerAndIsNotClearedByImmediateRefresh() {
        val request = request
        val pending = OpenCodePendingRequests(permissions = emptyList(), forms = listOf(request))
        val store = TestStore(
            initialState = { state },
            withDependencies = { it.openCodeClient.familyPendingRequests = { _, _, _ -> pending } },
        ) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
        store.send(SessionFeature.Action.requestResponseFinished(sessionID = session.id, requestID = request.id, error = OpenCodeClientError(message = "Offline")))
        expect(store.state.activePicker == SessionFeature.State.ActivePicker.requests)
        expect(store.state.selectedRequestID == request.id)
        store.receive<SessionFeature.Action.pendingRequestsResponse>()
        expect(store.state.activePicker == SessionFeature.State.ActivePicker.requests)
        expect(store.state.selectedRequestID == request.id)
        expect(store.state.formAnswers[request.id] == mapOf("q0" to OpenCodeFormValue.string("Native")))
        expect(store.state.requestErrors[request.id] == "Offline")
        expect(store.state.requestNotice == null)
        expect(store.state.completedRequestIDs.isEmpty())
    }

    @Test
    fun settledReplyAndStalePollPruneOnlyRequestsNoLongerPending() {
        val request = request
        val other = question("frm_other")
        val initial = state
        initial.formRequests = listOf(request, other)
        initial.formAnswers += other.id to mapOf("q0" to OpenCodeFormValue.string("Keep me"))
        initial.questionPageIndices += request.id to 0
        initial.questionPageIndices += other.id to 0
        initial.requestErrors += request.id to "Offline"
        val store = TestStore(
            initialState = { initial },
            withDependencies = { it.openCodeClient.familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList()) } },
        ) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)

        store.send(SessionFeature.Action.requestResponseFinished(sessionID = session.id, requestID = request.id, error = null)) {
            it.formAnswers = mapOf(other.id to mapOf("q0" to OpenCodeFormValue.string("Keep me")))
            it.questionPageIndices = mapOf(other.id to 0)
            it.requestErrors = emptyMap()
        }

        store.receive<SessionFeature.Action.pendingRequestsResponse>()
        expect(store.state.formRequests.isEmpty())
        expect(store.state.formAnswers.isEmpty())
        expect(store.state.questionPageIndices.isEmpty())
    }

    @Test
    fun typedNumbersReadMidEntryTextAndLocaleSeparators() {
        val us = Locale.US
        val de = Locale.GERMANY
        val fr = Locale.FRANCE
        expect(OpenCodeFormValue.typedNumber("1.", us) == OpenCodeFormValue.number(1.0))
        expect(OpenCodeFormValue.typedNumber("1.5", us) == OpenCodeFormValue.number(1.5))
        expect(OpenCodeFormValue.typedNumber("1,234.5", us) == OpenCodeFormValue.number(1234.5))
        expect(OpenCodeFormValue.typedNumber("1,", de) == OpenCodeFormValue.number(1.0))
        expect(OpenCodeFormValue.typedNumber("1.234,5", de) == OpenCodeFormValue.number(1234.5))
        expect(OpenCodeFormValue.typedNumber(NumberFormat.getNumberInstance(fr).format(1234.5), fr) == OpenCodeFormValue.number(1234.5))
        expect(OpenCodeFormValue.typedNumber(NumberFormat.getNumberInstance().format(1234.5)) == OpenCodeFormValue.number(1234.5))
        expect(OpenCodeFormValue.typedNumber("", us) == null)
        expect(OpenCodeFormValue.typedNumber("abc", us) == null)
    }

    @Test
    fun inFlightAnswersCannotBeEditedOrSubmittedAgain() {
        val request = request
        val initial = state
        initial.respondingRequestIDs = setOf(request.id)
        val store = TestStore(initialState = { initial }) { SessionFeature() }
        store.send(SessionFeature.Action.formValueChanged(formID = request.id, key = "q0", value = OpenCodeFormValue.string("Changed")))
        store.send(SessionFeature.Action.formSubmitTapped(request.id))
        store.send(SessionFeature.Action.formCancelTapped(request.id))
    }

    @Test
    fun alwaysAllowRequiresServerSuppliedSavePatterns() {
        val request = state
        val permission = OpenCodePermissionRequest(id = "per_1", sessionID = session.id, action = "shell", resources = listOf("git status"), save = null)
        request.permissionRequests = listOf(permission)
        val store = TestStore(initialState = { request }) { SessionFeature() }
        store.send(SessionFeature.Action.permissionReplyTapped(permission.id, OpenCodePermissionReply.always))
    }

    @Test
    fun keyboardSendCannotTurnAPendingAnswerIntoChatInput() {
        val initial = state
        initial.draft = "Yes, you may"
        val store = TestStore(initialState = { initial }) { SessionFeature() }
        store.send(SessionFeature.Action.sendTapped)
    }

    @Test
    fun globalFormCompletionIsRetiredLikeSessionForms() {
        val form = protocolJson.decodeFromString<OpenCodeFormRequest>(
            """{"id":"frm_global","sessionID":"global","title":"Confirm","fields":[{"key":"confirm","type":"boolean"}]}""",
        )
        val initial = state
        initial.formRequests = listOf(form)
        initial.selectedRequestID = form.id
        val store = TestStore(
            initialState = { initial },
            withDependencies = { it.openCodeClient.familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) } },
        ) { SessionFeature() }
        store.exhaustivity = Exhaustivity.off(showSkippedAssertions = false)
        store.send(SessionFeature.Action.requestResponseFinished(sessionID = "global", requestID = form.id, error = null))
        expect(store.state.formRequests.isEmpty())
        expect(store.state.completedRequestIDs.contains(form.id))
        expect(store.state.requestNotice == null)
        expect(store.state.selectedRequestID == null)
        expect(store.state.activePicker == null)
        store.receive<SessionFeature.Action.pendingRequestsResponse>()
    }
}

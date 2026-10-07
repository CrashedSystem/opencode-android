@file:PortedFrom("OpenCode/ProblemReports/ProblemReportsFeature.swift", "553a407")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.tca.BindableAction
import ai.opencode.mobilecode.tca.BindingAction
import ai.opencode.mobilecode.tca.BindingReducer
import ai.opencode.mobilecode.tca.Dependency
import ai.opencode.mobilecode.tca.DependencyKey
import ai.opencode.mobilecode.tca.DependencyValues
import ai.opencode.mobilecode.tca.Effect
import ai.opencode.mobilecode.tca.ObservableState
import ai.opencode.mobilecode.tca.Reduce
import ai.opencode.mobilecode.tca.Reducer
import ai.opencode.mobilecode.tca.Shared
import ai.opencode.mobilecode.tca.SharedKey
import ai.opencode.mobilecode.tca.StoreVar
import ai.opencode.mobilecode.tca.cancellable
import ai.opencode.mobilecode.tca.combine
import ai.opencode.mobilecode.tca.continuousClock
import ai.opencode.mobilecode.tca.date
import ai.opencode.mobilecode.tca.uuid
import ai.opencode.mobilecode.tca.withLock
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/// Problem reports in debug and TestFlight builds. A shake or Settings opens
/// the Report Problem sheet; trouble the app notices (a dropped call, a
/// stalled event stream, a crash last run) saves a draft and offers Send
/// Report?. Sent reports wait on disk and go to the server's Phone Reports
/// session as queued prompts, one at a time, oldest first. A notice in the
/// composer pill row says how each one went.
class ProblemReportsFeature : Reducer<ProblemReportsFeature.State, ProblemReportsFeature.Action> {
    class State : ObservableState() {
        /// Home keeps these current.
        var server by StoreVar<OpenCodeServer?>(null)
        var projects by StoreVar<List<OpenCodeProject>>(emptyList())
        var context by StoreVar(ProblemReportContext())

        var composer by StoreVar<Composer?>(null)
        var offer by StoreVar<Offer?>(null)
        var notice by StoreVar<Notice?>(null)
        /// The report whose next result someone is waiting on, after Send or Retry.
        var awaitedID by StoreVar<UUID?>(null)
        var lastOfferAt by StoreVar<Instant?>(null)
        var delivering by StoreVar<Delivering?>(null)
        /// The server came back while a delivery was on its way: try the rest
        /// once it settles, even if it fails.
        var retryAfterCurrent by StoreVar(false)
        /// Failed automatic deliveries this launch; at the limit a report waits
        /// for Send or a later launch.
        var automaticAttempts by StoreVar<Map<UUID, Int>>(emptyMap())
        /// Reports that failed this round; retried on the next reconnect.
        var deferredIDs by StoreVar<Set<UUID>>(emptySet())
        var projectFilter by StoreVar("")
        var destinations by Shared(SharedKey.phoneReportsDestinations)
        var uploadUnsupportedServerIDs by Shared(SharedKey.uploadUnsupportedServerIDs)

        data class Composer(
            val reportID: UUID,
            val trigger: ProblemReportTrigger,
            val note: String = "",
            val includesScreenshot: Boolean = true,
            val hasScreenshot: Boolean = false,
            /// The bundle is on disk; Send waits for it.
            val isReady: Boolean = false,
            val phase: Phase = Phase.editing,
            val isPickingProject: Boolean = false,
        ) {
            enum class Phase { editing, sending }
        }

        /// How a delivery went, in the composer pill row. It waits off screen
        /// (on a call, while dictating or typing) and its timer and haptic start
        /// once it shows.
        data class Notice(
            val id: UUID,
            val kind: Kind,
            val isShown: Boolean = false,
        ) {
            sealed interface Kind {
                /// The report just sent went through.
                data class sent(val sessionID: String) : Kind

                /// The report just sent waits for the server.
                data object waiting : Kind

                /// A report that waited went through.
                data class delivered(val sessionID: String) : Kind

                /// A report ran out of automatic attempts this launch.
                data class failed(val reportID: UUID) : Kind
            }

            val sessionID: String?
                get() = when (val kind = kind) {
                    is Kind.sent -> kind.sessionID
                    is Kind.delivered -> kind.sessionID
                    Kind.waiting, is Kind.failed -> null
                }
        }

        data class Offer(
            val reportID: UUID,
            val trigger: ProblemReportTrigger,
        )

        /// Where a delivery started, so its result lands there even if the
        /// server or project changed meanwhile.
        data class Delivering(
            val reportID: UUID,
            val serverKey: String,
            val projectID: String,
        )

        val destination: PhoneReportsDestination?
            get() = server?.let { destinations[it.persistenceID] }

        val filteredProjects: List<OpenCodeProject>
            get() {
                val query = projectFilter.trim()
                if (query.isEmpty()) return projects
                return projects.filter { it.name.contains(query, ignoreCase = true) || it.directory.contains(query, ignoreCase = true) }
            }
    }

    sealed interface Delegate {
        /// Opens Phone Reports, fetching it when Home doesn't list it.
        data class openSession(val sessionID: String) : Delegate

        data class openFetchedSession(val session: OpenCodeSession) : Delegate
    }

    sealed interface Action : BindableAction<State> {
        data class binding(override val binding: BindingAction<State>) : Action

        data object task : Action

        data class reportRequested(val trigger: ProblemReportTrigger, val screenshot: ByteArray?) : Action

        data class troubleDetected(val trigger: ProblemReportTrigger) : Action

        data class composerPrepared(val id: UUID, val hasScreenshot: Boolean) : Action

        data class offerPrepared(val report: ProblemReport) : Action

        data object offerTapped : Action

        data object offerDismissed : Action

        data class noteChanged(val note: String) : Action

        data class screenshotToggled(val isOn: Boolean) : Action

        data object sendTapped : Action

        data object cancelTapped : Action

        data class projectPicked(val project: OpenCodeProject) : Action

        data object projectPickerDismissed : Action

        data object serverReachable : Action

        data class deliveryFinished(val id: UUID, val result: Result<PhoneReportsDelivery.Receipt>) : Action

        data class offerExpired(val id: UUID) : Action

        data class noticeAppeared(val id: UUID) : Action

        data class noticeExpired(val id: UUID) : Action

        data object noticeTapped : Action

        data object noticeDismissed : Action

        data object retryTapped : Action

        data class retryDue(val id: UUID) : Action

        data object openPhoneReportsTapped : Action

        data class delegate(val action: Delegate) : Action
    }

    private val store by Dependency(DependencyValues::problemReportStore)
    private val diagnostics by Dependency(DependencyValues::diagnostics)
    private val capture by Dependency(DependencyValues::problemReportCapture)
    private val openCodeClient by Dependency(DependencyValues::openCodeClient)
    private val now by Dependency { it.date.now }
    private val uuid by Dependency(DependencyValues::uuid)
    private val clock by Dependency(DependencyValues::continuousClock)
    private val haptics by Dependency(DependencyValues::haptics)

    private sealed interface CancelID {
        data object prepare : CancelID
        data object offer : CancelID
        data object notice : CancelID
        data class retry(val id: UUID) : CancelID
    }

    override val body: Reducer<State, Action> =
        combine(
            BindingReducer(),
            Reduce { action ->
                when (action) {
                    is Action.binding -> Effect.none
                    Action.task -> {
                        var offered: ProblemReport? = null
                        for (report in store.all()) {
                            when {
                                report.status == ProblemReport.Status.composing && report.id != state.composer?.reportID ->
                                    store.delete(report.id)
                                report.status == ProblemReport.Status.offered -> {
                                    offered?.let { store.delete(it.id) }
                                    offered = report
                                }
                            }
                        }
                        var expiry: Effect<Action> = Effect.none
                        val restored = offered
                        if (state.offer == null && restored != null) {
                            // An offer left by the last launch keeps the time it had left.
                            val remaining = offerLifetime - (now.toEpochMilli() - restored.createdAt.toEpochMilli()).milliseconds
                            if (remaining <= Duration.ZERO) {
                                store.delete(restored.id)
                            } else {
                                state.offer = State.Offer(restored.id, restored.trigger)
                                expiry = expireOffer(restored.id, remaining)
                            }
                        }
                        val crashed = offersCrashReports && diagnostics.takeUnexpectedEnd()
                        Effect.merge(
                            expiry,
                            if (crashed) Effect.send(Action.troubleDetected(ProblemReportTrigger.crash)) else Effect.none,
                            deliverNext(state),
                        )
                    }
                    is Action.reportRequested -> {
                        if (state.composer != null) return@Reduce Effect.none
                        val id = uuid()
                        state.composer = State.Composer(reportID = id, trigger = action.trigger, hasScreenshot = action.screenshot != null)
                        prepare(id, action.trigger, ProblemReport.Status.composing, state.context, action.screenshot)
                    }
                    is Action.troubleDetected -> {
                        if (state.composer != null || state.offer != null) return@Reduce Effect.none
                        val last = state.lastOfferAt
                        if (action.trigger != ProblemReportTrigger.crash && last != null && now.toEpochMilli() - last.toEpochMilli() < offerIntervalMillis) {
                            return@Reduce Effect.none
                        }
                        if (action.trigger == ProblemReportTrigger.streamStalled && (diagnostics.foregroundSeconds() ?: 0.0) < stallForegroundSeconds) {
                            return@Reduce Effect.none
                        }
                        state.lastOfferAt = now
                        val id = uuid()
                        val context = state.context
                        val at = now
                        val trigger = action.trigger
                        Effect.run { send ->
                            val screenshot = if (trigger == ProblemReportTrigger.crash) null else capture.screenshot()
                            val bundle = build(id, trigger, ProblemReport.Status.offered, context, at, screenshot, diagnostics)
                            if (runCatching { store.save(bundle) }.isFailure) return@run
                            send(Action.offerPrepared(bundle.report))
                        }
                    }
                    is Action.composerPrepared -> {
                        if (state.composer?.reportID != action.id) {
                            store.delete(action.id)
                            return@Reduce Effect.none
                        }
                        state.composer = state.composer?.copy(isReady = true, hasScreenshot = action.hasScreenshot)
                        Effect.none
                    }
                    is Action.offerPrepared -> {
                        // The sheet or another offer came first while this one was saving.
                        if (state.composer != null || state.offer != null) {
                            store.delete(action.report.id)
                            return@Reduce Effect.none
                        }
                        state.offer = State.Offer(action.report.id, action.report.trigger)
                        expireOffer(action.report.id, offerLifetime)
                    }
                    Action.offerTapped -> {
                        val offer = state.offer
                        if (offer == null || state.composer != null) return@Reduce Effect.none
                        state.offer = null
                        val loaded = store.load(offer.reportID) ?: return@Reduce Effect.cancel(CancelID.offer)
                        val report = loaded.copy(status = ProblemReport.Status.composing)
                        runCatching { store.update(report) }
                        state.composer = State.Composer(
                            reportID = report.id,
                            trigger = report.trigger,
                            hasScreenshot = store.url(report.id, ProblemReportBundle.File.screenshot) != null,
                            isReady = true,
                        )
                        Effect.cancel(CancelID.offer)
                    }
                    Action.offerDismissed -> {
                        val offer = state.offer ?: return@Reduce Effect.none
                        state.offer = null
                        store.delete(offer.reportID)
                        Effect.cancel(CancelID.offer)
                    }
                    is Action.offerExpired -> {
                        if (state.offer?.reportID != action.id) return@Reduce Effect.none
                        state.offer = null
                        store.delete(action.id)
                        Effect.none
                    }
                    is Action.noteChanged -> {
                        state.composer = state.composer?.copy(note = action.note)
                        Effect.none
                    }
                    is Action.screenshotToggled -> {
                        state.composer = state.composer?.copy(includesScreenshot = action.isOn)
                        Effect.none
                    }
                    Action.sendTapped -> {
                        val current = state.composer
                        if (current == null || !current.isReady || current.phase != State.Composer.Phase.editing) return@Reduce Effect.none
                        if (state.destination == null) {
                            state.composer = current.copy(isPickingProject = true)
                            return@Reduce Effect.none
                        }
                        val loaded = store.load(current.reportID)
                        if (loaded == null) {
                            state.composer = null
                            return@Reduce Effect.none
                        }
                        val report = loaded.copy(
                            note = current.note.trim(),
                            includesScreenshot = current.includesScreenshot && current.hasScreenshot,
                            status = ProblemReport.Status.pending,
                        )
                        if (!report.includesScreenshot) store.removeScreenshot(report.id)
                        runCatching { store.update(report) }
                        state.composer = current.copy(phase = State.Composer.Phase.sending)
                        state.deferredIDs -= report.id
                        state.automaticAttempts -= report.id
                        state.awaitedID = report.id
                        deliverNext(state)
                    }
                    Action.cancelTapped -> {
                        val composer = state.composer ?: return@Reduce Effect.none
                        if (composer.phase == State.Composer.Phase.editing) store.delete(composer.reportID)
                        state.composer = null
                        Effect.none
                    }
                    is Action.projectPicked -> {
                        val server = state.server ?: return@Reduce Effect.none
                        val key = server.persistenceID
                        state::destinations.withLock {
                            // Another project needs its own Phone Reports session.
                            val kept = value[key]
                            val sessionID = if (kept?.projectID == action.project.id) kept.sessionID else null
                            value += (key to PhoneReportsDestination(action.project.id, action.project.name, action.project.directory, sessionID))
                        }
                        state.projectFilter = ""
                        if (state.composer?.isPickingProject == true) {
                            state.composer = state.composer?.copy(isPickingProject = false)
                            return@Reduce Effect.send(Action.sendTapped)
                        }
                        // Reports waiting for a destination can go now.
                        deliverNext(state)
                    }
                    Action.projectPickerDismissed -> {
                        state.composer = state.composer?.copy(isPickingProject = false)
                        state.projectFilter = ""
                        Effect.none
                    }
                    Action.serverReachable -> {
                        state.deferredIDs = emptySet()
                        if (state.delivering != null) state.retryAfterCurrent = true
                        deliverNext(state)
                    }
                    is Action.deliveryFinished -> {
                        val delivering = state.delivering
                        if (delivering == null || delivering.reportID != action.id) return@Reduce Effect.none
                        state.delivering = null
                        val isComposerReport = state.composer?.reportID == action.id
                        val wasAwaited = state.awaitedID == action.id
                        if (wasAwaited) state.awaitedID = null
                        val receipt = action.result.getOrNull()
                        if (receipt != null) {
                            store.delete(action.id)
                            state.automaticAttempts -= action.id
                            remember(receipt.sessionID, delivering, state)
                            if (receipt.uploadsUnsupported) {
                                state::uploadUnsupportedServerIDs.withLock { value += delivering.serverKey }
                            }
                            runCatching { LabMarks.mark("problem_report_delivered") }
                            if (isComposerReport) state.composer = null
                            val notice = post(if (wasAwaited) State.Notice.Kind.sent(receipt.sessionID) else State.Notice.Kind.delivered(receipt.sessionID), state)
                            return@Reduce Effect.merge(notice, Effect.cancel(CancelID.retry(action.id)), deliverNext(state))
                        }
                        val failure = action.result.exceptionOrNull() as? PhoneReportsDelivery.Failure
                        if (failure?.sessionID != null) remember(failure.sessionID, delivering, state)
                        val saved = store.load(action.id)
                        if (saved != null) {
                            runCatching {
                                store.update(saved.copy(attempts = saved.attempts + 1, lastError = failure?.error?.message))
                            }
                        }
                        state.automaticAttempts += (action.id to ((state.automaticAttempts[action.id] ?: 0) + 1))
                        // The server is likely unreachable: stop this round, keep the rest.
                        state.deferredIDs += action.id
                        var notice: Effect<Action> = Effect.none
                        // The sheet closes on its report's result, or when the server
                        // failed the report ahead of it.
                        if (isComposerReport || state.composer?.phase == State.Composer.Phase.sending) {
                            state.composer = null
                            state.awaitedID = null
                            notice = post(State.Notice.Kind.waiting, state)
                        } else if (wasAwaited) {
                            notice = post(State.Notice.Kind.waiting, state)
                        } else if ((state.automaticAttempts[action.id] ?: 0) >= maximumAutomaticAttempts) {
                            notice = post(State.Notice.Kind.failed(action.id), state)
                        }
                        val failures = state.automaticAttempts[action.id] ?: 0
                        val retry = if (failures < maximumAutomaticAttempts) {
                            Effect.run { send ->
                                clock.sleep(retryDelay(failures))
                                send(Action.retryDue(action.id))
                            }.cancellable(CancelID.retry(action.id), cancelInFlight = true)
                        } else {
                            Effect.cancel(CancelID.retry(action.id))
                        }
                        if (!state.retryAfterCurrent) return@Reduce Effect.merge(notice, retry)
                        state.retryAfterCurrent = false
                        state.deferredIDs = emptySet()
                        Effect.merge(notice, retry, deliverNext(state))
                    }
                    is Action.retryDue -> {
                        state.deferredIDs -= action.id
                        deliverNext(state)
                    }
                    is Action.noticeAppeared -> {
                        val current = state.notice
                        if (current == null || current.id != action.id || current.isShown) return@Reduce Effect.none
                        val notice = current.copy(isShown = true)
                        state.notice = notice
                        when (notice.kind) {
                            is State.Notice.Kind.sent, is State.Notice.Kind.delivered -> haptics.success()
                            State.Notice.Kind.waiting -> haptics.warning()
                            is State.Notice.Kind.failed -> {
                                haptics.error()
                                return@Reduce Effect.none
                            }
                        }
                        Effect.run { send ->
                            clock.sleep(noticeDuration)
                            send(Action.noticeExpired(action.id))
                        }.cancellable(CancelID.notice, cancelInFlight = true)
                    }
                    is Action.noticeExpired -> {
                        if (state.notice?.id == action.id) state.notice = null
                        Effect.none
                    }
                    Action.noticeTapped -> {
                        val sessionID = state.notice?.sessionID ?: return@Reduce Effect.none
                        state.notice = null
                        Effect.merge(Effect.cancel(CancelID.notice), Effect.send(Action.delegate(Delegate.openSession(sessionID))))
                    }
                    Action.noticeDismissed -> {
                        state.notice = null
                        Effect.cancel(CancelID.notice)
                    }
                    Action.retryTapped -> {
                        val failed = state.notice?.kind as? State.Notice.Kind.failed ?: return@Reduce Effect.none
                        state.notice = null
                        if (store.load(failed.reportID)?.status != ProblemReport.Status.pending) return@Reduce Effect.none
                        state.automaticAttempts -= failed.reportID
                        state.deferredIDs -= failed.reportID
                        state.awaitedID = failed.reportID
                        Effect.merge(Effect.cancel(CancelID.retry(failed.reportID)), deliverNext(state))
                    }
                    Action.openPhoneReportsTapped -> {
                        val sessionID = state.destination?.sessionID ?: return@Reduce Effect.none
                        Effect.send(Action.delegate(Delegate.openSession(sessionID)))
                    }
                    is Action.delegate -> Effect.none
                }
            },
        )

    /// Shows a notice in place of any other, except that a failure gives way
    /// only to the next report someone sends.
    private fun post(kind: State.Notice.Kind, state: State): Effect<Action> {
        if (state.notice?.kind is State.Notice.Kind.failed && kind is State.Notice.Kind.delivered) return Effect.none
        state.notice = State.Notice(id = uuid(), kind = kind)
        return Effect.cancel(CancelID.notice)
    }

    private fun expireOffer(id: UUID, after: Duration): Effect<Action> =
        Effect.run { send ->
            clock.sleep(after)
            send(Action.offerExpired(id))
        }.cancellable(CancelID.offer, cancelInFlight = true)

    /// A session the delivery found or made, kept only if its server still
    /// sends reports to the same project.
    private fun remember(sessionID: String, delivering: State.Delivering, state: State) {
        state::destinations.withLock {
            val current = value[delivering.serverKey] ?: return@withLock
            if (current.projectID != delivering.projectID) return@withLock
            value += (delivering.serverKey to current.copy(sessionID = sessionID))
        }
    }

    /// One per report, for every attempt in every launch: a send whose response
    /// was lost is accepted again as the same message, not a second report.
    /// Delivers the oldest pending report, unless one is already on its way.
    private fun deliverNext(state: State): Effect<Action> {
        if (state.delivering != null) return Effect.none
        val server = state.server ?: return Effect.none
        val destination = state.destination ?: return Effect.none
        val attempts = state.automaticAttempts
        val report = store.all().firstOrNull {
            it.status == ProblemReport.Status.pending &&
                it.id !in state.deferredIDs &&
                (attempts[it.id] ?: 0) < maximumAutomaticAttempts
        } ?: return Effect.none
        state.delivering = State.Delivering(report.id, server.persistenceID, destination.projectID)
        val supportsUploads = server.persistenceID !in state.uploadUnsupportedServerIDs
        val messageID = messageID(report.id)
        return Effect.run { send ->
            try {
                val receipt = PhoneReportsDelivery.deliver(
                    report,
                    store,
                    server,
                    destination,
                    supportsUploads,
                    messageID,
                    openCodeClient,
                )
                send(Action.deliveryFinished(report.id, Result.success(receipt)))
            } catch (error: PhoneReportsDelivery.Failure) {
                send(Action.deliveryFinished(report.id, Result.failure(error)))
            }
        }
    }

    private fun prepare(
        id: UUID,
        trigger: ProblemReportTrigger,
        status: ProblemReport.Status,
        context: ProblemReportContext,
        screenshot: ByteArray?,
    ): Effect<Action> {
        val at = now
        return Effect.run { send ->
            val bundle = build(id, trigger, status, context, at, screenshot, diagnostics)
            runCatching { store.save(bundle) }
            send(Action.composerPrepared(id, bundle.files[ProblemReportBundle.File.screenshot] != null))
        }.cancellable(CancelID.prepare, cancelInFlight = true)
    }

    companion object {
        val isAvailable: Boolean get() = InternalBuild.isEnabled

        /// A debugger stop or a UI test's terminate looks like a crash, so debug
        /// builds offer crash reports only when a lab asks for them.
        val offersCrashReports: Boolean
            get() = if (BuildConfig.DEBUG) {
                (System.getProperty("opencode.arguments") ?: "").split(" ").contains("-lab-crash-report")
            } else {
                true
            }

        /// At most one automatic offer this often, except after a crash.
        const val offerInterval = 600.0
        private const val offerIntervalMillis = 600_000L

        /// A stalled stream counts only after this long in the foreground: a
        /// resumed phone's timed-out socket isn't a stall.
        const val stallForegroundSeconds = 120.0
        const val maximumAutomaticAttempts = 5

        /// Waits after each failed attempt while the connection stays up but the
        /// server keeps failing; a reconnect tries at once. The attempt limit ends it.
        fun retryDelay(afterFailures: Int): Duration = when {
            afterFailures <= 1 -> 5.seconds
            afterFailures == 2 -> 15.seconds
            afterFailures == 3 -> 60.seconds
            else -> 300.seconds
        }

        /// Trouble is worth reporting while it's fresh. Five minutes is time to
        /// finish what you were doing when it happened (a dropped call's offer
        /// waits out the call's end); after that the offer is clutter, and its
        /// bundle goes with it.
        val offerLifetime = 300.seconds

        /// How long a delivery notice stays once it's on screen: a glance, and
        /// time to tap it. A failure stays until it's dealt with.
        val noticeDuration = 5.seconds

        fun messageID(reportID: UUID): String = "msg_" + reportID.toString().replace("-", "")

        /// Off the main actor: the timeline and the previous run can be a few hundred KB.
        private suspend fun build(
            id: UUID,
            trigger: ProblemReportTrigger,
            status: ProblemReport.Status,
            context: ProblemReportContext,
            now: Instant,
            screenshot: ByteArray?,
            diagnostics: DiagnosticsClient,
        ): ProblemReportBundle = withContext(Dispatchers.Default) {
            val device = ProblemReportDevice.current.copy(liveActivities = diagnostics.liveActivities())
            ProblemReportBuilder.make(
                id = id,
                trigger = trigger,
                status = status,
                context = context,
                device = device,
                now = now,
                events = diagnostics.timeline(),
                previousRun = diagnostics.previousRunEvents(),
                screenshot = screenshot,
            )
        }
    }
}

/// The app window at the moment of a report, as a JPEG.
class ProblemReportCapture(
    var screenshot: suspend () -> ByteArray?,
) {
    companion object : DependencyKey<ProblemReportCapture> {
        override val liveValue get() = ProblemReportCapture(screenshot = { WindowSnapshot.jpeg() })
        override val testValue get() = ProblemReportCapture(screenshot = { null })
    }
}

var DependencyValues.problemReportCapture: ProblemReportCapture
    get() = this[ProblemReportCapture]
    set(value) {
        this[ProblemReportCapture] = value
    }

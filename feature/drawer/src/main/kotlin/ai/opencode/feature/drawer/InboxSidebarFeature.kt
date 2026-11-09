package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.log.AppLog
import ai.opencode.core.log.logSummary
import ai.opencode.core.sharing.ExperimentalFeature
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.cancellable
import ai.opencode.core.ui.appFormattingLocale
import ai.opencode.feature.drawer.icons.resolved
import ai.opencode.feature.transcript.markdown.OpenCodeMarkdownBlock
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeTranscriptItem
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

/**
 * Inbox Sidebar (experimental): the drawer as an inbox, one item per session, sorted Needs your
 * input, then unread, then newest. Rows come from Home's state ([HomeFeature.State.inboxSidebarRows]);
 * this reducer owns Archive, the one action Home doesn't already have.
 */
class InboxSidebarFeature : Reducer<InboxSidebarFeature.State, InboxSidebarFeature.Action> {
    data class Status(
        /**
         * Archives in flight: hidden at once. Once the server confirms, Home stamps the session's
         * `time.archived` and the ID goes, so snapshots decide from then on (a session restored on
         * desktop comes back).
         */
        val archivedIDs: Set<String> = emptySet(),
        val archiveError: String? = null,
    )

    data class State(val server: OpenCodeServer, val status: Status = Status())

    sealed interface Action {
        data class ArchiveSwiped(val sessionID: String, val title: String) : Action
        data class ArchiveResponse(val sessionID: String, val title: String, val error: OpenCodeClientError?) : Action
        data object ArchiveErrorCleared : Action
    }

    sealed interface CancelID {
        data class Archive(val sessionID: String) : CancelID
        data object ArchiveError : CancelID
    }

    override fun reduce(state: State, action: Action): Reduced<State, Action> = when (action) {
        is Action.ArchiveSwiped -> {
            val sessionID = action.sessionID
            if (sessionID in state.status.archivedIDs) {
                Reduced(state)
            } else {
                dependency(HapticsClient).play(Haptic.tap)
                val client = dependency(OpenCodeClient)
                val server = state.server
                val title = action.title
                Reduced(
                    state.copy(status = state.status.copy(archivedIDs = state.status.archivedIDs + sessionID, archiveError = null)),
                    Effect.merge(
                        Effect.cancel(CancelID.ArchiveError),
                        Effect.run<Action> { send ->
                            try {
                                client.archiveSession(server, sessionID)
                                send(Action.ArchiveResponse(sessionID, title, null))
                            } catch (error: OpenCodeClientError) {
                                send(Action.ArchiveResponse(sessionID, title, error))
                            }
                        }.cancellable(CancelID.Archive(sessionID), cancelInFlight = true),
                    ),
                )
            }
        }

        is Action.ArchiveResponse -> {
            val next = state.copy(status = state.status.copy(archivedIDs = state.status.archivedIDs - action.sessionID))
            val error = action.error
            if (error == null) {
                Reduced(next)
            } else {
                AppLog.network.notice { "Archive failed: ${error.logSummary}" }
                dependency(HapticsClient).play(Haptic.error)
                val clock = dependency(Clock)
                Reduced(
                    next.copy(status = next.status.copy(archiveError = "Couldn’t archive “${action.title}”. ${error.message}")),
                    Effect.run<Action> { send ->
                        clock.sleep(3.seconds)
                        send(Action.ArchiveErrorCleared)
                    }.cancellable(CancelID.ArchiveError, cancelInFlight = true),
                )
            }
        }

        Action.ArchiveErrorCleared -> Reduced(state.copy(status = state.status.copy(archiveError = null)))
    }

    companion object {
        /** The Inbox Sidebar switch (iOS `ExperimentalFeature.inboxSidebar`, off by default). */
        val experiment: ExperimentalFeature get() = ExperimentalFeature.inboxSidebar
    }
}

/** One session as the Inbox Sidebar shows it. */
data class InboxSidebarRow(
    val id: String,
    val title: String,
    val project: String?,
    val line: Line,
    val unread: Boolean,
    val updatedAt: Instant,
) {
    sealed interface Line {
        data object NeedsInput : Line
        data object Working : Line
        /** The latest reply's opening prose, on one line. */
        data class Reply(val text: String) : Line
        data object None : Line
    }

    companion object {
        /** The newest reply's first prose block, flattened to one line. */
        fun replySnippet(items: List<OpenCodeTranscriptItem>): String? {
            for (item in items.asReversed()) {
                if (item !is OpenCodeTranscriptItem.AssistantMarkdown) continue
                for (block in OpenCodeMarkdownBlock.blocks(item.text, inlineCache = null)) {
                    val text = when (val kind = block.kind) {
                        is OpenCodeMarkdownBlock.Kind.Paragraph -> kind.text.text
                        is OpenCodeMarkdownBlock.Kind.ListItem -> kind.text.text
                        is OpenCodeMarkdownBlock.Kind.NumberedItem -> kind.text.text
                        is OpenCodeMarkdownBlock.Kind.Blockquote -> kind.text.text
                        is OpenCodeMarkdownBlock.Kind.Heading -> kind.text.text
                        else -> continue
                    }
                    val line = text.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
                    if (line.isNotEmpty()) return line.take(200)
                }
            }
            return null
        }

        /** A short age: “now”, “5m”, “3h”, “2d”, then the date. */
        fun age(date: Instant, now: Instant, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = appFormattingLocale()): String {
            val seconds = maxOf(Duration.between(date, now).toMillis() / 1_000.0, 0.0)
            return when {
                seconds < 60 -> "now"
                seconds < 3_600 -> "${(seconds / 60).toInt()}m"
                seconds < 86_400 -> "${(seconds / 3_600).toInt()}h"
                seconds < 604_800 -> "${(seconds / 86_400).toInt()}d"
                else -> DateTimeFormatter.ofPattern("MMM d", locale).withZone(zone).format(date)
            }
        }
    }
}

/**
 * The server archived it: mirror `time.archived` until the next snapshot replaces it, and read it,
 * so it leaves Catch Up and the unread count.
 */
internal fun HomeFeature.archiveConfirmed(state: HomeFeature.State, sessionID: String): Reduced<HomeFeature.State, HomeFeature.Action> {
    val index = state.sessions.indexOfFirst { it.id == sessionID }
    if (index < 0) return Reduced(state)
    val session = state.sessions[index].copy(archivedMilliseconds = dependency(DateClient).now().toEpochMilli())
    val sessions = state.sessions.toMutableList().also { it[index] = session }
    val next = state.copy(sessions = sessions).markingReadLocally(session).clearingUnseenBaseline(sessionID)
    return Reduced(next, acknowledgeView(session, next.server))
}

val HomeFeature.State.inboxSidebar: InboxSidebarFeature.State
    get() = InboxSidebarFeature.State(server = server, status = inboxSidebarStatus)

/** Every listed session except archived ones: Needs your input first, then unread, then most recent. */
fun HomeFeature.State.inboxSidebarRows(): List<InboxSidebarRow> {
    val status = statusSnapshot()
    val archived = inboxSidebarStatus.archivedIDs
    val rows = visibleSessions.mapNotNull { session ->
        if (session.archivedMilliseconds != null || session.id in archived) return@mapNotNull null
        val line = when (status.indicator(session)) {
            HomeFeature.State.Indicator.attention -> InboxSidebarRow.Line.NeedsInput
            HomeFeature.State.Indicator.running -> InboxSidebarRow.Line.Working
            HomeFeature.State.Indicator.unread, HomeFeature.State.Indicator.none ->
                cachedTranscriptItems(session)?.let(InboxSidebarRow::replySnippet)?.let(InboxSidebarRow.Line::Reply)
                    ?: InboxSidebarRow.Line.None
        }
        val project = OpenCodeProject.resolved(session, projects).name
        InboxSidebarRow(
            id = session.id,
            title = session.title,
            project = project.ifEmpty { null },
            line = line,
            unread = status.isUnread(session) && !status.isMuted(session.id),
            updatedAt = session.updatedAt,
        )
    }
    return rows.sortedWith { a, b ->
        val aInput = a.line == InboxSidebarRow.Line.NeedsInput
        val bInput = b.line == InboxSidebarRow.Line.NeedsInput
        when {
            aInput != bInput -> if (aInput) -1 else 1
            a.unread != b.unread -> if (a.unread) -1 else 1
            else -> b.updatedAt.compareTo(a.updatedAt)
        }
    }
}

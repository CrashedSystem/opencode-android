package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.ForcedAppearance
import ai.opencode.mobilecode.system.SystemTheme
import ai.opencode.mobilecode.tca.Clock
import ai.opencode.mobilecode.tca.Store
import ai.opencode.mobilecode.tca.continuousClock
import ai.opencode.mobilecode.tca.withLock
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow

/// Home on the gauntlet fixture's sessions, as the iOS reference drawer
/// (`04-drawer`) shows them: two pinned, one running, one asking, the rest
/// unread. Nothing reaches a server; the client answers from these values.
object HomeLabFixture {
    val project = OpenCodeProject(id = "proj_gauntlet", name = "Gauntlet api", directory = "/fixtures/gauntlet")

    /// Titles in the fixture's order, `ses_gauntlet_01` to `_09`, then the heavy session.
    private val titles = listOf(
        "Investigate slow startup",
        "Fix composer keyboard overlap",
        "Review push notification plan",
        "Add cursor-paged history",
        "Profile transcript rendering",
        "Tidy attachment picker",
        "Design the new session card",
        "Audit SSE reconnect timing",
        "Polish the native feature tour",
        "Heavy session",
    )

    /// Newest first as the drawer's Recents list them: asked, started, then the rest.
    private val recency = listOf(5, 4, 9, 8, 7, 6, 1, 10, 3, 2)

    val sessions: List<OpenCodeSession> = titles.mapIndexed { index, title ->
        val number = index + 1
        OpenCodeSession(
            id = sessionID(number),
            title = title,
            projectID = project.id,
            directory = project.directory,
            updatedAt = 1_800_000_000_000L - recency.indexOf(number) * 60_000L,
            idleMilliseconds = 2_000,
            viewedMilliseconds = 1_000,
        )
    }

    val running = sessionID(4)
    val asking = sessionID(5)
    val pinned = listOf(sessionID(2), sessionID(3))

    fun sessionID(number: Int): String = if (number == 10) "ses_heavy" else "ses_gauntlet_%02d".format(number)

    /// What `ses_gauntlet_05`'s card shows in Catch Up (`29-catch-up`).
    val transcript: List<OpenCodeTranscriptItem> = listOf(
        OpenCodeTranscriptItem.userText(id = "m1", text = "Profile transcript rendering. Show me what you find."),
        OpenCodeTranscriptItem.assistantMarkdown(
            id = "m2",
            blocks = OpenCodeMarkdownBlock.blocks(
                from = "I found the layout issue in `SessionView.swift`. The transcript and composer share the available height, so the keyboard is a useful stress test.",
            ),
        ),
        OpenCodeTranscriptItem.userText(id = "m3", text = "Keep the earlier messages readable."),
        OpenCodeTranscriptItem.userText(id = "m4", text = "And leave the composer accessible."),
        OpenCodeTranscriptItem.assistantMarkdown(
            id = "m5",
            blocks = OpenCodeMarkdownBlock.blocks(from = "**Profile transcript rendering**\n\nDone. The composer stays reachable and history keeps its position."),
        ),
    )

    fun server(port: Int = 4120) = OpenCodeServer(url = "http://127.0.0.1:$port", username = "opencode", password = "")

    fun state(server: OpenCodeServer = server()): HomeFeature.State {
        val state = HomeFeature.State(
            server = server,
            snapshot = OpenCodeHomeSnapshot(projects = listOf(project), sessions = sessions, activeSessionIDs = setOf(running)),
        )
        state.attentionSessionIDs = setOf(asking)
        state.transcriptCache = sessions.associate { it.id to (if (it.id == asking) transcript else emptyList()) }
        state::pinnedSessionKeys.withLock { value = pinned.map(state::sessionKey) }
        return state
    }

    /// The home store with a client that answers from the fixture and an
    /// event stream that stays open and quiet.
    fun store(server: OpenCodeServer = server(), clock: Clock? = null): Store<HomeFeature.State, HomeFeature.Action> =
        Store(
            initialState = { state(server) },
            withDependencies = {
                if (clock != null) it.continuousClock = clock
                it.openCodeClient.events = { flow { awaitCancellation() } }
                it.openCodeClient.homeSnapshot = {
                    OpenCodeHomeSnapshot(projects = listOf(project), sessions = sessions, activeSessionIDs = setOf(running))
                }
                it.openCodeClient.homeAttention = { _, _, _ -> setOf(asking) }
                it.openCodeClient.transcript = { _, id, _ -> if (id == asking) transcript else emptyList() }
                it.openCodeClient.viewSession = { _, _, _ -> }
                it.openCodeClient.deleteSession = { _, _ -> }
                it.openCodeClient.getSession = { _, id -> sessions.first { session -> session.id == id } }
            },
        ) {
            HomeFeature()
        }
}

/// `adb shell am start -n ai.opencode.mobilecode/.HomeLabActivity [--ez dark true]`
class HomeLabActivity : ComponentActivity() {
    private val store by lazy { HomeLabFixture.store() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val forcedDark = if (intent.hasExtra(DARK)) intent.getBooleanExtra(DARK, false) else null
        setContent {
            ForcedAppearance(forcedDark ?: isSystemInDarkTheme()) {
                SystemTheme { HomeView(store) }
            }
        }
    }

    companion object {
        const val DARK = "dark"
    }
}

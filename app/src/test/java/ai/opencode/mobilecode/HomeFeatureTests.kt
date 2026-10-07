@file:PortedFrom("OpenCodeTests/HomeFeatureTests.swift", "553a407", partial = "the State-only tests; the reducer tests land with the reducer")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.tca.TestDependencies
import ai.opencode.mobilecode.tca.expect
import ai.opencode.mobilecode.tca.withLock
import java.time.Instant
import org.junit.Rule
import org.junit.Test

class HomeFeatureTests {
    @get:Rule
    val dependencies = TestDependencies()

    private val testServer = OpenCodeServer(url = "http://127.0.0.1:9", username = "opencode", password = "pw")

    private fun testSession(
        id: String = "ses_1",
        title: String = "Fix login bug",
        projectID: String = "proj_1",
        directory: String = "/tmp/demo",
        updatedAt: Long = 1_000_000,
    ): OpenCodeSession = OpenCodeSession(id = id, title = title, projectID = projectID, directory = directory, updatedAt = updatedAt)

    @Test
    fun prioritySortUsesCurrentActivityAndAttentionBeforeRecency() {
        val sessions = (0 until 5).map {
            testSession(id = "root-$it", updatedAt = it * 1_000L)
        }
        val state = HomeFeature.State(
            server = testServer,
            snapshot = OpenCodeHomeSnapshot(
                projects = emptyList(),
                sessions = sessions,
                activeSessionIDs = setOf("nested"),
                activeSessionParents = mapOf("nested" to "child", "child" to "root-0"),
            ),
        )
        state.attentionSessionIDs = setOf("root-1")
        expect(state.sessionsByPriority().map { it.id } == listOf("root-1", "root-0", "root-4", "root-3", "root-2"))
        state.activeSessionIDs = emptySet()
        expect(state.sessionsByPriority().map { it.id } == listOf("root-1", "root-4", "root-3", "root-2", "root-0"))
        state.attentionSessionIDs = emptySet()
        expect(state.sessionsByPriority().map { it.id } == listOf("root-4", "root-3", "root-2", "root-1", "root-0"))
    }

    @Test
    fun restoresRememberedSessionFromPersistedTranscriptOrStartsDraft() {
        val server = OpenCodeServer(url = "https://saved.example", username = "", password = "")
        val session = OpenCodeSession(id = "remembered", title = "Remembered", projectID = "p", directory = "/p", updatedAt = Instant.now().toEpochMilli())
        val items = listOf<OpenCodeTranscriptItem>(OpenCodeTranscriptItem.userText(id = "m1", text = "ready offline"))
        val state = HomeFeature.State(
            server = server,
            snapshot = OpenCodeHomeSnapshot(projects = emptyList(), sessions = listOf(session), activeSessionIDs = emptySet()),
        )
        state::lastViewedSessionIDs.withLock { value += (server.persistenceID to session.id) }
        state.transcriptCache += (session.id to items)
        state.restoreInitialSession()
        expect(state.sessionDetail?.session == session)
        expect(state.sessionDetail?.items == items)
        expect(state.sessionDetail?.isLoading == false)

        state.sessions = emptyList()
        state.restoreInitialSession()
        expect(state.sessionDetail?.isDraft == true)
        expect(state.lastViewedSessionIDs[server.persistenceID] == null)
    }

    @Test
    fun inMemoryTranscriptCacheRetainsTheOpenSessionAndPrioritySessions() {
        val server = OpenCodeServer(url = "https://saved.example", username = "", password = "")
        val sessions = (0 until 24).map { index ->
            OpenCodeSession(
                id = "ses_$index",
                title = "Session $index",
                projectID = "p",
                directory = "/p",
                updatedAt = index * 1_000L,
            )
        }
        val state = HomeFeature.State(
            server = server,
            snapshot = OpenCodeHomeSnapshot(projects = emptyList(), sessions = sessions, activeSessionIDs = setOf("ses_0")),
        )
        state.sessionDetail = SessionFeature.State(server = server, session = sessions[1])
        for (session in sessions) {
            state.transcriptCache += (session.id to listOf(OpenCodeTranscriptItem.userText(id = session.id, text = session.title)))
        }
        state.trimTranscriptCache()

        expect(state.transcriptCache.size == HomeFeature.transcriptMemoryLimit)
        expect(state.transcriptCache["ses_1"] != null)
        expect(state.transcriptCache["ses_0"] != null)
        expect(state.transcriptCache["ses_23"] != null)
    }
}

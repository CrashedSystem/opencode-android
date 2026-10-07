@file:PortedFrom("OpenCodeTests/TranscriptCachePersistenceTests.swift", "553a407")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.tca.Shared
import ai.opencode.mobilecode.tca.SharedKey
import ai.opencode.mobilecode.tca.TestDependencies
import ai.opencode.mobilecode.tca.expect
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class TranscriptCachePersistenceTests {
    @get:Rule
    val dependencies = TestDependencies()

    @Test
    fun splicedEnvelopeMatchesASingleFullEncode() {
        val entries = (0 until 3).map { index ->
            CachedTranscriptSnapshots.Entry(
                serverID = "server",
                sessionID = "ses_$index",
                updatedAt = index * 1000L,
                items = listOf(
                    OpenCodeTranscriptItem.userText(id = "u_$index", text = "Hello \"$index\""),
                    OpenCodeTranscriptItem.assistantMarkdown(
                        id = "a_$index",
                        blocks = OpenCodeMarkdownBlock.blocks(from = "**Hi**\n\n```swift\nlet n = $index\n```"),
                    ),
                    OpenCodeTranscriptItem.compaction(
                        id = "c_$index",
                        info = OpenCodeCompaction(
                            status = OpenCodeCompaction.Status.completed,
                            summary = OpenCodeMarkdownBlock.blocks(from = "## Kept"),
                        ),
                    ),
                ),
            )
        }
        for (snapshot in listOf(CachedTranscriptSnapshots(), CachedTranscriptSnapshots(entries = entries))) {
            val data = snapshot.encodedForPersistence() ?: error("encodedForPersistence returned nil")
            val full = transcriptCacheJson.encodeToString(CachedTranscriptSnapshots.serializer(), snapshot)
            expect(data.toByteArray(Charsets.UTF_8).size == full.toByteArray(Charsets.UTF_8).size)
            expect(
                transcriptCacheJson.decodeFromString(CachedTranscriptSnapshots.serializer(), data) ==
                    transcriptCacheJson.decodeFromString(CachedTranscriptSnapshots.serializer(), full),
            )
        }
    }

    @Test
    fun transcriptPersistenceCoalescesAndKeepsProtectedFormat() {
        val storage = ProtectedFileStorage.inMemory()
        val persistence = TranscriptCachePersistence.make(storage)
        val first = CachedTranscriptSnapshots(
            entries = listOf(CachedTranscriptSnapshots.Entry(serverID = "server", sessionID = "first", updatedAt = 0, items = emptyList())),
        )
        val second = CachedTranscriptSnapshots(
            entries = listOf(CachedTranscriptSnapshots.Entry(serverID = "server", sessionID = "second", updatedAt = 1_700_000_000_000, items = emptyList())),
        )
        persistence.schedule(first)
        persistence.schedule(second)
        runBlocking { persistence.flush() }
        expect(persistence.load() == second)
        val stored = storage.data() ?: error("storage was empty")
        expect(transcriptCacheJson.decodeFromString(CachedTranscriptSnapshots.serializer(), stored.toString(Charsets.UTF_8)) == second)
    }

    @Test
    fun transcriptPersistenceBoundsLargeSnapshotsBeforeItsSingleFinalEncode() {
        val payload = "x".repeat(600_000)
        val entries = (0 until CachedTranscriptSnapshots.maximumEntries).map { index ->
            CachedTranscriptSnapshots.Entry(
                serverID = "server",
                sessionID = "ses_$index",
                updatedAt = index.toLong(),
                items = listOf(OpenCodeTranscriptItem.userText(id = "m_$index", text = payload)),
            )
        }
        val snapshot = CachedTranscriptSnapshots(entries = entries)
        val data = snapshot.encodedForPersistence() ?: error("encodedForPersistence returned nil")
        val decoded = transcriptCacheJson.decodeFromString(CachedTranscriptSnapshots.serializer(), data)
        expect(data.toByteArray(Charsets.UTF_8).size <= CachedTranscriptSnapshots.maximumBytes)
        expect(decoded.entries.size < entries.size)
        expect(decoded.entries.first().sessionID == entries.first().sessionID)
    }

    @Test
    fun rememberPutsTheNewestServerFirstAndReplacesTheEditedAddress() {
        val older = OpenCodeServer(url = "https://two.example", username = "opencode", password = "other")
        val server = OpenCodeServer(url = "https://one.example", username = "opencode", password = "secret")
        older.remember()
        server.remember()
        var saved by Shared(SharedKey.savedServer)
        var servers by Shared(SharedKey.savedServers)
        expect(saved == server)
        expect(servers.map { it.url } == listOf("https://one.example", "https://two.example"))

        val edited = server.copy(url = "https://one-edited.example")
        edited.remember(replacing = server)
        expect(saved == edited)
        expect(servers.map { it.url } == listOf("https://one-edited.example", "https://two.example"))
    }

    @Test
    fun undeliveredPromptIsTakenOnceAndDiscardedByMessage() {
        val prompts = UndeliveredPrompts()
        val pending = SessionFeature.PendingPrompt(
            messageID = "msg_1",
            text = "hello",
            attachments = emptyList(),
            optimisticIDs = emptySet(),
            requestEpoch = 1,
        )
        val error = OpenCodeClientError("Couldn’t reach the OpenCode server")
        prompts.keep(pending, error, "server", null)
        expect(prompts.take("server", null)?.pending?.text == "hello")
        expect(prompts.take("server", null) == null)

        prompts.keep(pending, error, "server", "ses_1")
        prompts.discard("msg_1")
        expect(prompts.take("server", "ses_1") == null)
    }
}

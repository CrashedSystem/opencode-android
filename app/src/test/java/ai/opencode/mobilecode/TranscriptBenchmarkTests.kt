@file:PortedFrom("OpenCodeTests/TranscriptBenchmarkTests.swift", "553a407")

package ai.opencode.mobilecode

import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/// Perf benchmark for the transcript load pipeline. Skipped unless BENCH=1.
/// Run:
///   BENCH=1 OPENCODE_PASSWORD=... BENCH_SESSION=ses_... ./gradlew :app:testDebugUnitTest --tests '*TranscriptBenchmarkTests*'
class TranscriptBenchmarkTests {
    @Test(timeout = 300_000)
    fun transcriptLoadStages() {
        assumeTrue(System.getenv("BENCH") == "1")
        val password = System.getenv("OPENCODE_PASSWORD") ?: ""
        val sessionID = System.getenv("BENCH_SESSION") ?: ""
        val server = OpenCodeServer(url = "http://127.0.0.1:4096", username = "opencode", password = password)
        val credentials = Base64.getEncoder().encodeToString("${server.username}:${server.password}".toByteArray())
        val request = Request.Builder()
            .url("${server.url}/api/session/$sessionID/message?order=desc&limit=60")
            .header("Authorization", "Basic $credentials")
            .build()
        val http = OkHttpClient()

        fun median(values: List<Double>): Double = values.sorted()[values.size / 2]

        val fetchMs = mutableListOf<Double>()
        val decodeMs = mutableListOf<Double>()
        val mapMs = mutableListOf<Double>()
        val totalMs = mutableListOf<Double>()
        var payloadBytes = 0

        for (run in 0 until 8) {
            val fetchStart = System.nanoTime()
            val data = http.newCall(request).execute().use { it.body.bytes() }
            val fetchEnd = System.nanoTime()
            payloadBytes = data.size

            val decodeStart = System.nanoTime()
            val payload = Json.parseToJsonElement(data.decodeToString()).jsonObject
            val decodeEnd = System.nanoTime()

            val mapStart = System.nanoTime()
            var blockCount = 0
            for (message in payload["data"]?.jsonArray ?: JsonArray(emptyList())) {
                val content = (message as JsonObject)["content"] as? JsonArray ?: continue
                for (part in content) {
                    val fields = part.jsonObject
                    if (fields["type"]?.jsonPrimitive?.content != "text") continue
                    blockCount += OpenCodeMarkdownBlock.blocks(from = fields["text"]?.jsonPrimitive?.content ?: "").size
                }
            }
            val mapEnd = System.nanoTime()

            val totalStart = System.nanoTime()
            runBlocking { OpenCodeClient.liveValue.transcript(server, sessionID, 30) }
            val totalEnd = System.nanoTime()

            if (run == 0) continue // warmup
            fetchMs += (fetchEnd - fetchStart) / 1e6
            decodeMs += (decodeEnd - decodeStart) / 1e6
            mapMs += (mapEnd - mapStart) / 1e6
            totalMs += (totalEnd - totalStart) / 1e6
            check(blockCount >= 0)
        }

        println("METRIC transcript_fetch_ms=${median(fetchMs).toLong()}")
        println("METRIC transcript_decode_ms=${median(decodeMs).toLong()}")
        println("METRIC transcript_map_ms=${median(mapMs).toLong()}")
        println("METRIC transcript_total_ms=${median(totalMs).toLong()}")
        println("METRIC transcript_payload_bytes=$payloadBytes")
        println("METRIC transcript_total_best_ms=${totalMs.min().toLong()} worst=${totalMs.max().toLong()}")
    }
}

/// Deterministic CPU benchmarks: no server, sleeps, or UI polling. One warmup
/// followed by seven samples; checksums keep all of the output observable.
class TranscriptCPUBenchmarkTests {
    @Test
    fun testTranscriptCPU() {
        assumeTrue(System.getenv("TRANSCRIPT_CPU_BENCH") == "1")
        runBenchmarks()
    }

    private fun runBenchmarks() {
        val section = """
## Streaming response

Keep **stable identities** while the reply grows. Read `Sources/Session.swift` and [the spec](docs/spec.pdf).

- Preserve the reader's position
  - Reconcile the authoritative snapshot

```swift
struct Response: Sendable {
  let value: String
  func render() -> String { return "Hello" + value } // streamed output
}
```

| Stage | State |
|---|---|
| Decode | Complete |
| Render | Working |

""".trimStart('\n')
        val document = (section + "\n").repeat(24)
        val code = "let answer: String = render(value: 42) // next value\n".repeat(200)
        val blocks = OpenCodeMarkdownBlock.blocks(from = section)
        val items: List<OpenCodeTranscriptItem> = (0 until 120).flatMap { index ->
            listOf(
                OpenCodeTranscriptItem.userText(id = "user-$index", text = "Review the next section"),
                OpenCodeTranscriptItem.assistantMarkdown(id = "reply-$index", blocks = blocks),
            )
        }
        val entries = TranscriptEntry.build(from = items)
        val identities = entries.associate { it.id to it.id }
        val streamingEntries = TranscriptEntry.build(from = items, isRunning = true)
        val updatedItems = items.toMutableList()
        updatedItems[updatedItems.size - 1] = OpenCodeTranscriptItem.assistantMarkdown(
            id = "reply-119",
            blocks = blocks + OpenCodeMarkdownBlock.blocks(from = "The latest streamed paragraph"),
        )
        benchmark("markdown_document_ms") { OpenCodeMarkdownBlock.blocks(from = document, inlineCache = OpenCodeMarkdownBlock.InlineCache()).size }
        benchmark("highlight_200_lines_ms") { SyntaxHighlighter.highlight(code, language = "swift").runs.size }
        benchmark("entry_build_240_items_ms") { TranscriptEntry.build(from = items, isRunning = true).size }
        benchmark("entry_build_20_items_ms") { TranscriptEntry.build(from = items.takeLast(20), isRunning = true).size }
        benchmark("entry_update_240_items_ms") {
            val updated = TranscriptEntry.build(from = updatedItems, isRunning = true)
            updated.size + (if (updated == streamingEntries) 1 else 0)
        }
        benchmark("row_identity_240_items_ms") {
            TranscriptRowIdentityPlan.make(
                previousEntries = entries,
                previousQueued = emptyList(),
                previousEntryStableIDs = identities,
                previousQueuedStableIDs = emptyMap(),
                entries = entries,
                queued = emptyList(),
            ).entryStableIDs.size
        }
        // 64 successive flushes of the same growing reply, including closed code
        // fences, links and tables. This is the parser called by upsertLiveText.
        val prefixes = (1..64).map { document.take(document.length * it / 64) }
        benchmark("stream_64_flushes_ms") {
            val cache = OpenCodeMarkdownBlock.InlineCache()
            var previous = emptyList<OpenCodeMarkdownBlock>()
            prefixes.fold(0) { count, text ->
                previous = OpenCodeMarkdownBlock.blocks(from = text, inlineCache = cache, reusing = previous)
                count + previous.size
            }
        }

        // Unique sections keep a reuse optimization honest: each reply starts
        // cold, and only earlier flushes of this reply can provide useful work.
        val unique = (0 until 48).joinToString("\n") { index ->
            """
## Finding $index

The **result $index** references `Sources/Feature$index.swift` and [report $index](reports/result-$index.pdf). Keep the reader’s position while more text arrives 🧑🏽‍💻.

- Check the boundary for case $index
  - Preserve its identity $index

```swift
struct Response$index: Sendable {
  let value = $index
  func render() -> String { return "Result $index" }
}
```

| Stage $index | Result $index |
|---|---|
| Decode $index | Complete $index |

""".trimStart('\n')
        }
        for ((name, source) in listOf("ascii" to document, "unicode" to unique)) {
            val expected = source.split("```")
            assertEquals(expected, OpenCodeMarkdownBlock.codeFenceSegments(source))
            benchmark("fences_${name}_foundation_50x_ms") { (0 until 50).fold(0) { count, _ -> count + source.split("```").size } }
            benchmark("fences_${name}_candidate_50x_ms") {
                (0 until 50).fold(0) { count, _ -> count + OpenCodeMarkdownBlock.codeFenceSegments(source).size }
            }
        }
        val table = "| File | Status |\n|---|---|\n" + (0 until 128).joinToString("") { "| `Sources/Feature$it.swift` | **Result $it** |\n" }
        val prose = (0 until 96).joinToString("") {
            "Paragraph $it: preserve **formatting $it**, [reference $it](https://example.com/$it), and `value_$it` as the response grows.\n\n"
        }
        benchmark("unique_document_ms") { OpenCodeMarkdownBlock.blocks(from = unique, inlineCache = OpenCodeMarkdownBlock.InlineCache()).size }
        for ((name, text) in listOf("unique" to unique, "table" to table, "prose" to prose)) {
            val flushes = (1..128).map { text.take(text.length * it / 128) }
            for (reuse in listOf(false, true)) {
                benchmark("stream_${name}_${if (reuse) "" else "uncached_"}128_flushes_ms") {
                    val cache = if (reuse) OpenCodeMarkdownBlock.InlineCache() else null
                    var previous = emptyList<OpenCodeMarkdownBlock>()
                    flushes.fold(0) { count, flush ->
                        previous = OpenCodeMarkdownBlock.blocks(from = flush, inlineCache = cache, reusing = if (reuse) previous else emptyList())
                        count + previous.size
                    }
                }
            }
        }
    }

    private fun benchmark(name: String, operation: () -> Int) {
        val samples = mutableListOf<Double>()
        var checksum = 0
        for (run in 0 until 8) {
            val start = System.nanoTime()
            checksum += operation()
            val elapsed = (System.nanoTime() - start) / 1e6
            if (run > 0) samples += elapsed
        }
        val sorted = samples.sorted()
        val median = sorted[sorted.size / 2]
        val mad = samples.map { kotlin.math.abs(it - median) }.sorted()[samples.size / 2]
        println("METRIC $name=$median mad=$mad min=${sorted.first()} max=${sorted.last()} checksum=$checksum")
        assertTrue(checksum > 0)
    }
}

package ai.opencode.mobilecode

object TranscriptFixture {
    const val typography = """## The Effect upgrade, change by change

Each item below is one of the problems the move from rc.112 to rc.118 created, and how it was solved, starting with the ones users could notice.

### 1. Permission rules could be applied in the wrong order

**Why:** V1 permission rules are applied last-match-wins, so the order you write them in is what makes them work. Effect doesn't guarantee that it keeps object key order when decoding, so relying on it was a hidden bug waiting for an upgrade.

```ts
"bash": { "*": "ask", "git status": "allow" }

// fix: decode to an ordered list
ConfigPermissionV1.Entries  →  [["*", "ask"], ["git status", "allow"]]
```

**Proof:** a canary test fails if Effect ever stops preserving order. The second reviewer diffed config output for every map type and found it identical.

### 2. No-input tools could send providers a wrong schema

**Why:** rc.118 builds schemas differently. Core and `ai` each had their own "does this tool take input?" check, and one of them went wrong for transformed schemas. Providers would have been sent `{anyOf: [object, array]}` instead of an empty object.

What changed:

- [x] `emptyInputJsonSchema` lives in one place now
- [x] the core tool runtime calls it instead of its own copy
- [ ] `ai` keeps no separate check

**Proof:** the tool schemas for all 17 built-in tools and 30+ browser tools are byte-identical to V2. The one difference is intended.

### 3. Streams ended early when a provider sent an empty chunk

**Why:** rc.118's `Stream.takeUntil` treats an empty chunk as done. Two providers send one between tool calls, so the reply stopped mid-sentence.

```swift
let events = provider.stream(request).filter { !${'$'}0.isEmpty }  // skip keep-alive chunks before they reach takeUntil
```

To check it yourself:

```sh
bun run test packages/core/test/stream.test.ts
```

**Proof:** the replay of 400 recorded sessions finishes every reply, and the keep-alive test fails without the filter.

### Summary

| Change | Users saw | Fix | Proof |
| --- | --- | --- | --- |
| Permission order | Rules applied in the wrong order after an upgrade | Decode permissions to an ordered list | Canary test and a config diff across every map type |
| Tool schemas | Providers rejected tools that take no input | One shared `emptyInputJsonSchema` | Byte-identical schemas for 47 tools |
| Empty chunks | Replies stopped mid-sentence | Filter keep-alive chunks before `takeUntil` | 400 replayed sessions |

All three ship together in rc.118."""

    const val files = """I recorded the drawer animation and wrote up the change.

- The recording is `recordings/drawer-demo.mp4`.
- A voice note to go with it: [memo.m4a](/tmp/opencode-lab/files/memo.m4a).
- The write-up is in [the design spec](docs/drawer-spec.pdf), with a screenshot at `docs/drawer.png`.

The fix is in `OpenCode/SessionView.swift:12`, and the full output is in `logs/build.log`. The routing notes are in `docs/voice-routing.md`."""

    const val lists = """1. First item with `inline` code
2. Second item
   - nested bullet
   - [x] nested done task

---

A [link](https://example.com) after the divider."""

    fun referenceBlocks(): List<OpenCodeMarkdownBlock> =
        blocks(typography) + blocks(files) + blocks(lists)

    fun blocks(markdown: String): List<OpenCodeMarkdownBlock> {
        val result = mutableListOf<OpenCodeMarkdownBlock>()
        val segments = markdown.split("```")
        segments.forEachIndexed { index, segment ->
            if (index % 2 == 0) appendProse(segment, result)
            else appendCode(segment, result)
        }
        return result
    }

    private fun appendCode(segment: String, blocks: MutableList<OpenCodeMarkdownBlock>) {
        val lines = segment.split("\n").toMutableList()
        var language: String? = null
        if (lines.size > 1) {
            language = lines.removeAt(0).trim().ifEmpty { null }
        }
        val source = lines.joinToString("\n").trim()
        if (source.isEmpty()) return
        blocks += block(OpenCodeMarkdownBlock.Kind.code(OpenCodeMarkdownBlock.HighlightedCode(source, language)))
    }

    private fun appendProse(text: String, blocks: MutableList<OpenCodeMarkdownBlock>) {
        val lines = text.split("\n")
        var index = 0
        val paragraph = StringBuilder()
        fun flush() {
            val value = paragraph.toString().trim()
            paragraph.clear()
            if (value.isEmpty()) return
            val attributed = inline(value)
            blocks += block(OpenCodeMarkdownBlock.Kind.paragraph(attributed))
            cards(value)?.let { blocks += block(OpenCodeMarkdownBlock.Kind.files(it)) }
        }
        while (index < lines.size) {
            val line = lines[index]
            val trimmed = line.trim()
            if (isDivider(trimmed)) {
                flush()
                blocks += block(OpenCodeMarkdownBlock.Kind.divider)
                index += 1
                continue
            }
            val heading = Regex("""^(#{1,6}) (.+)$""").matchEntire(line.trimEnd())
            if (heading != null) {
                flush()
                blocks += block(
                    OpenCodeMarkdownBlock.Kind.heading(
                        heading.groupValues[1].length,
                        inline(heading.groupValues[2]),
                    ),
                )
                index += 1
                continue
            }
            if (trimmed.startsWith("|") && index + 1 < lines.size && isSeparator(lines[index + 1].trim())) {
                flush()
                val tableLines = mutableListOf(line)
                index += 2
                while (index < lines.size && lines[index].trim().startsWith("|")) {
                    tableLines += lines[index]
                    index += 1
                }
                val header = cells(tableLines.first())
                val rows = tableLines.drop(1).map { cells(it) }
                blocks += block(OpenCodeMarkdownBlock.Kind.table(header, rows))
                continue
            }
            val item = listMarker(line)
            if (item != null) {
                flush()
                val body = inline(item.text)
                val kind = if (item.numbered && item.level == 0) {
                    OpenCodeMarkdownBlock.Kind.numberedItem(item.marker, body)
                } else {
                    OpenCodeMarkdownBlock.Kind.listItem(item.marker, item.level, body)
                }
                blocks += block(kind)
                cards(item.text)?.let { blocks += block(OpenCodeMarkdownBlock.Kind.files(it)) }
                index += 1
                continue
            }
            if (line.isBlank()) {
                flush()
            } else {
                if (paragraph.isNotEmpty()) paragraph.append('\n')
                paragraph.append(line.trim())
            }
            index += 1
        }
        flush()
    }

    private fun cards(source: String): List<AgentFileReference>? {
        val found = mutableListOf<AgentFileReference>()
        Regex("""\[[^\]]+]\(([^)]+)\)|`([^`]+)`""").findAll(source).forEach { match ->
            val destination = match.groupValues[1].ifEmpty { match.groupValues[2] }
            val file = AgentFileReference.fromLink(destination) ?: AgentFileReference.withLocation(destination) ?: return@forEach
            if (file.kind.showsCard && found.none { it.path == file.path }) found += file
        }
        return found.ifEmpty { null }
    }

    private fun cells(line: String): List<AttributedString> {
        val trimmed = line.trim().removePrefix("|").removeSuffix("|")
        return trimmed.split("|").map { inline(it.trim()) }
    }

    private fun isSeparator(line: String) = line.isNotEmpty() && line.all { it == '|' || it == '-' || it == ':' || it == ' ' }

    private fun isDivider(line: String): Boolean {
        val stripped = line.filter { !it.isWhitespace() }
        return stripped.length >= 3 && stripped.all { it == stripped[0] } && stripped[0] in "-*_"
    }

    private data class Item(val marker: String, val level: Int, val text: String, val numbered: Boolean)

    private fun listMarker(line: String): Item? {
        val indent = line.takeWhile { it == ' ' }.length
        val body = line.drop(indent)
        val task = Regex("""^[-*]\s+\[( |x|X)]\s+(.*)$""").matchEntire(body)
        if (task != null) {
            val marker = if (task.groupValues[1].equals("x", ignoreCase = true)) OpenCodeMarkdownBlock.doneTask else OpenCodeMarkdownBlock.openTask
            return Item(marker, (indent / 2).coerceAtMost(3), task.groupValues[2], false)
        }
        val bullet = Regex("""^[-*•]\s+(.*)$""").matchEntire(body)
        if (bullet != null) return Item("•", (indent / 2).coerceAtMost(3), bullet.groupValues[1], false)
        val numbered = Regex("""^(\d+)\.\s+(.*)$""").matchEntire(body)
        if (numbered != null) {
            return Item("${numbered.groupValues[1]}.", (indent / 2).coerceAtMost(3), numbered.groupValues[2], true)
        }
        return null
    }

    private fun inline(source: String): AttributedString {
        val runs = mutableListOf<AttributedString.Run>()
        val out = StringBuilder()
        var index = 0
        while (index < source.length) {
            if (source.startsWith("**", index)) {
                val end = source.indexOf("**", index + 2)
                if (end > index) {
                    val start = out.length
                    out.append(source.substring(index + 2, end))
                    runs += AttributedString.Run(
                        start,
                        out.length,
                        inlinePresentationIntent = setOf(AttributedString.Intent.stronglyEmphasized),
                    )
                    index = end + 2
                    continue
                }
            }
            if (source[index] == '`') {
                val end = source.indexOf('`', index + 1)
                if (end > index) {
                    val code = source.substring(index + 1, end)
                    val start = out.length
                    out.append(code)
                    val file = AgentFileReference.fromLink(code) ?: AgentFileReference.withLocation(code)
                    val link = if (file != null && file.hasViewableName) "opencode-file://open?path=${file.path}" else null
                    runs += AttributedString.Run(
                        start,
                        out.length,
                        link = link,
                        inlinePresentationIntent = setOf(AttributedString.Intent.code),
                    )
                    index = end + 1
                    continue
                }
            }
            if (source[index] == '[') {
                val labelEnd = source.indexOf("](", index)
                val urlEnd = if (labelEnd > index) source.indexOf(')', labelEnd + 2) else -1
                if (labelEnd > index && urlEnd > labelEnd) {
                    val start = out.length
                    out.append(source.substring(index + 1, labelEnd))
                    runs += AttributedString.Run(start, out.length, link = source.substring(labelEnd + 2, urlEnd))
                    index = urlEnd + 1
                    continue
                }
            }
            out.append(source[index])
            index += 1
        }
        return AttributedString(out.toString(), runs)
    }

    private fun block(kind: OpenCodeMarkdownBlock.Kind) = OpenCodeMarkdownBlock(0, kind)

    fun withIds(blocks: List<OpenCodeMarkdownBlock>) = blocks.mapIndexed { index, block ->
        OpenCodeMarkdownBlock(index, block.kind)
    }
}

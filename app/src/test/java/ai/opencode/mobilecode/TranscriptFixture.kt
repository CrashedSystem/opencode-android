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

    const val quote = """> The paragraph is fitted as a whole: every break is chosen together to minimize one cost, rather than greedily line by line.
> A second quoted line, set as its own hard-broken segment.

- A list item long enough to wrap onto a second line, so its first line is justified and its last stays ragged."""

    fun referenceBlocks(): List<OpenCodeMarkdownBlock> =
        blocks(typography) + blocks(files) + blocks(lists)

    fun blocks(markdown: String): List<OpenCodeMarkdownBlock> = OpenCodeMarkdownBlock.blocks(from = markdown)

    fun withIds(blocks: List<OpenCodeMarkdownBlock>) = blocks.mapIndexed { index, block ->
        OpenCodeMarkdownBlock(index, block.kind)
    }
}

@file:PortedFrom("OpenCode/Transcript/TranscriptEntry.swift", "553a407")

package ai.opencode.mobilecode

class TranscriptSnapshotTrace {
    var sessionID: String? = null
    var previous: Set<String> = emptySet()
    var missing: MutableMap<String, Double> = mutableMapOf()

    fun record(sessionID: String?, ids: List<String>, now: Double): Change {
        if (this.sessionID != sessionID) {
            this.sessionID = sessionID
            previous = emptySet()
            missing = mutableMapOf()
        }
        missing = missing.filter { now - it.value <= 5 }.toMutableMap()
        val current = ids.toSet()
        val vanished = previous.subtract(current).sorted()
        for (id in vanished) missing[id] = now
        val reappeared = current.filter { missing[it] != null }.sorted()
        for (id in reappeared) missing.remove(id)
        previous = current
        return Change(vanished, reappeared)
    }

    data class Change(val vanished: List<String>, val reappeared: List<String>)
}

sealed class TranscriptEntry {
    abstract val id: String

    val itemIDs: List<String>
        get() = when (this) {
            is single -> listOf(item.id)
            is userGroup -> items.map { it.id }
            is toolGroup -> tools.map { it.id }
            is replyEnd -> emptyList()
        }

    val toolImageAttachments: List<OpenCodeImageAttachment>
        get() {
            val tools = when (this) {
                is single -> listOf(item)
                is toolGroup -> this.tools
                is userGroup, is replyEnd -> return emptyList()
            }
            return tools.flatMap { item ->
                val call = (item as? OpenCodeTranscriptItem.tool)?.call ?: return@flatMap emptyList()
                call.imageAttachments
            }
        }

    val userMessageID: String?
        get() {
            val group = this as? userGroup ?: return null
            return group.items.firstOrNull()?.let { promptID(forItem = it) }
        }

    val layoutKind: String
        get() = when (this) {
            is userGroup -> "user"
            is toolGroup -> "tools"
            is replyEnd -> "reply-end"
            is single -> when (val item = item) {
                is OpenCodeTranscriptItem.assistantMarkdown -> when {
                    item.blocks.any { it.kind is OpenCodeMarkdownBlock.Kind.table } -> "table"
                    item.blocks.any { it.kind is OpenCodeMarkdownBlock.Kind.code } -> "code"
                    else -> "markdown"
                }
                is OpenCodeTranscriptItem.compaction -> "compaction"
                else -> "other"
            }
        }

    data class single(val item: OpenCodeTranscriptItem) : TranscriptEntry() {
        override val id: String get() = item.id
    }

    data class userGroup(
        val items: List<OpenCodeTranscriptItem>,
        val startsTurn: Boolean,
        val rawID: String,
    ) : TranscriptEntry() {
        override val id: String get() = "user-$rawID"
    }

    data class toolGroup(
        val tools: List<OpenCodeTranscriptItem>,
        val rawID: String,
    ) : TranscriptEntry() {
        override val id: String get() = "group-$rawID"
    }

    data class replyEnd(
        override val id: String,
        val blocks: List<OpenCodeMarkdownBlock>,
    ) : TranscriptEntry()

    companion object {
        fun userGroup(id: String, items: List<OpenCodeTranscriptItem>, startsTurn: Boolean) =
            userGroup(items = items, startsTurn = startsTurn, rawID = id)

        fun toolGroup(id: String, tools: List<OpenCodeTranscriptItem>) =
            toolGroup(tools = tools, rawID = id)

        fun promptID(forItem: OpenCodeTranscriptItem): String? = when (forItem) {
            is OpenCodeTranscriptItem.userText -> forItem.id
            is OpenCodeTranscriptItem.userImages ->
                if (forItem.id.endsWith("-images")) forItem.id.dropLast("-images".length) else forItem.id
            is OpenCodeTranscriptItem.userFiles ->
                if (forItem.id.endsWith("-files")) forItem.id.dropLast("-files".length) else forItem.id
            else -> null
        }

        fun build(from: List<OpenCodeTranscriptItem>, isRunning: Boolean = false): List<TranscriptEntry> {
            val entries = mutableListOf<TranscriptEntry>()
            val run = mutableListOf<OpenCodeTranscriptItem>()
            var kind: Run? = null
            var reply = mutableListOf<OpenCodeMarkdownBlock>()
            var replyID: String? = null

            fun flush() {
                when (val current = kind) {
                    Run.tools -> entries += toolGroup(id = run[0].id, tools = run.toList())
                    is Run.user -> {
                        val startsTurn = entries.lastOrNull() !is userGroup
                        entries += userGroup(id = run[0].id, items = run.toList(), startsTurn = startsTurn)
                    }
                    null -> Unit
                }
                if (kind is Run.user) Unit
                run.clear()
                kind = null
            }

            fun append(item: OpenCodeTranscriptItem, to: Run) {
                if (kind != to) flush()
                kind = to
                run += item
            }

            fun endReply() {
                val id = replyID
                if (id != null && reply.isNotEmpty()) {
                    flush()
                    entries += replyEnd(id = "reply-end-$id", blocks = reply.toList())
                }
                reply = mutableListOf()
                replyID = null
            }

            for (item in from) {
                when {
                    item is OpenCodeTranscriptItem.tool && item.call.questions != null -> {
                        flush()
                        entries += single(item)
                    }
                    item is OpenCodeTranscriptItem.tool || item is OpenCodeTranscriptItem.notice ->
                        append(item, Run.tools)
                    item is OpenCodeTranscriptItem.userText ||
                        item is OpenCodeTranscriptItem.userImages ||
                        item is OpenCodeTranscriptItem.userFiles -> {
                        endReply()
                        append(item, Run.user(promptID(forItem = item) ?: item.id))
                    }
                    item is OpenCodeTranscriptItem.assistantMarkdown -> {
                        flush()
                        entries += single(item)
                        reply += item.blocks
                        replyID = item.id
                    }
                    item is OpenCodeTranscriptItem.compaction ||
                        item is OpenCodeTranscriptItem.info ||
                        item is OpenCodeTranscriptItem.sendFailure -> {
                        flush()
                        entries += single(item)
                    }
                }
            }
            flush()
            if (!isRunning) endReply()
            return entries
        }

        fun tail(of: List<TranscriptEntry>): List<TranscriptEntry> {
            val last = of.lastOrNull() ?: return emptyList()
            if (last !is replyEnd) return listOf(last)
            val content = of.lastOrNull { it !is replyEnd } ?: return listOf(last)
            return listOf(last, content)
        }

        fun entryID(containing: String, entries: List<TranscriptEntry>): String? =
            entries.firstOrNull { containing in it.itemIDs }?.id
    }

    private sealed class Run {
        data object tools : Run()
        data class user(val promptID: String) : Run()
    }
}

data class TranscriptRowIdentityPlan(
    val entryStableIDs: Map<String, String>,
    val queuedStableIDs: Map<String, String>,
    val promotedEntryIDs: Set<String>,
    val supersededQueuedIDs: Set<String>,
) {
    companion object {
        fun make(
            previousEntries: List<TranscriptEntry>,
            previousQueued: List<OpenCodeQueuedInput>,
            previousEntryStableIDs: Map<String, String>,
            previousQueuedStableIDs: Map<String, String>,
            entries: List<TranscriptEntry>,
            queued: List<OpenCodeQueuedInput>,
        ): TranscriptRowIdentityPlan {
            val previousEntryIDs = previousEntries.map { it.id }.toSet()
            val availableQueued = previousQueued.map { input ->
                Queued(input, previousQueuedStableIDs[input.id] ?: input.id)
            }.toMutableList()
            val entryStableIDs = mutableMapOf<String, String>()
            val promotedEntryIDs = mutableSetOf<String>()
            val matchedPreviousEntryIDs = mutableSetOf<String>()

            for (entry in entries) {
                val stable = previousEntryStableIDs[entry.id] ?: continue
                entryStableIDs[entry.id] = stable
                matchedPreviousEntryIDs += entry.id
            }
            if (matchedPreviousEntryIDs.size < previousEntries.size && entries.any { entryStableIDs[it.id] == null }) {
                val previousIndexByItemID = mutableMapOf<String, MutableList<Int>>()
                previousEntries.forEachIndexed { offset, element ->
                    if (element.id in matchedPreviousEntryIDs) return@forEachIndexed
                    for (itemID in element.itemIDs) previousIndexByItemID.getOrPut(itemID) { mutableListOf() }.add(offset)
                }
                val overlapCandidates = entries.flatMapIndexed { newIndex, entry ->
                    if (entryStableIDs[entry.id] != null) return@flatMapIndexed emptyList()
                    val overlapByOldIndex = mutableMapOf<Int, Int>()
                    for (itemID in entry.itemIDs) {
                        for (oldIndex in previousIndexByItemID[itemID].orEmpty()) {
                            overlapByOldIndex[oldIndex] = (overlapByOldIndex[oldIndex] ?: 0) + 1
                        }
                    }
                    overlapByOldIndex.map { (oldIndex, overlap) ->
                        Candidate(overlap, oldIndex, newIndex, previousEntries[oldIndex].id, entry.id)
                    }
                }.sortedWith(compareByDescending<Candidate> { it.overlap }.thenBy { it.oldIndex }.thenBy { it.newIndex })
                for (candidate in overlapCandidates) {
                    if (entryStableIDs[candidate.newID] != null) continue
                    if (candidate.oldID in matchedPreviousEntryIDs) continue
                    val stableID = previousEntryStableIDs[candidate.oldID] ?: continue
                    entryStableIDs[candidate.newID] = stableID
                    matchedPreviousEntryIDs += candidate.oldID
                }
            }

            for (entry in entries) {
                if (entryStableIDs[entry.id] != null) continue
                val messageID = entry.userMessageID
                val index = if (entry.id !in previousEntryIDs && messageID != null) {
                    availableQueued.indexOfFirst { !it.input.isCompaction && it.input.id == messageID }
                } else {
                    -1
                }
                if (index >= 0) {
                    entryStableIDs[entry.id] = availableQueued.removeAt(index).stableID
                    promotedEntryIDs += entry.id
                } else {
                    entryStableIDs[entry.id] = entry.id
                }
            }

            val queuedStableIDs = mutableMapOf<String, String>()
            val supersededQueuedIDs = mutableSetOf<String>()
            val previousQueuedStableIDSet = previousQueuedStableIDs.values.toSet()
            for (input in queued) {
                val echoStableID = echoStableID(input, entries, entryStableIDs, promotedEntryIDs, previousQueuedStableIDSet)
                val index = availableQueued.indexOfFirst { it.input.id == input.id }
                val stableID = if (index >= 0) availableQueued.removeAt(index).stableID else echoStableID ?: input.id
                queuedStableIDs[input.id] = stableID
                if (stableID == echoStableID) supersededQueuedIDs += input.id
            }

            return TranscriptRowIdentityPlan(entryStableIDs, queuedStableIDs, promotedEntryIDs, supersededQueuedIDs)
        }

        private fun echoStableID(
            input: OpenCodeQueuedInput,
            entries: List<TranscriptEntry>,
            entryStableIDs: Map<String, String>,
            promotedEntryIDs: Set<String>,
            previousQueuedStableIDSet: Set<String>,
        ): String? {
            if (input.isCompaction) return null
            val entry = entries.firstOrNull { it.userMessageID == input.id } ?: return null
            val entryStableID = entryStableIDs[entry.id] ?: return null
            if (entry.id in promotedEntryIDs || entryStableID in previousQueuedStableIDSet) return entryStableID
            return null
        }
    }

    private data class Queued(val input: OpenCodeQueuedInput, val stableID: String)
    private data class Candidate(val overlap: Int, val oldIndex: Int, val newIndex: Int, val oldID: String, val newID: String)
}

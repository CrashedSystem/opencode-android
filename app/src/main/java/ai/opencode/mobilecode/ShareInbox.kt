@file:PortedFrom("OpenCode/ShareInbox.swift", "553a407", partial = "client, drafts, and draft target; SessionFeature.absorb and HomeFeature share resolution wait for those features")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.tca.DependencyKey
import ai.opencode.mobilecode.tca.DependencyValues
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/// The app's side of the share extension handoff (see ShareHandoff.swift).
class ShareInboxClient(
    /// Removes and returns every pending share, oldest first.
    var consume: suspend () -> List<ReceivedShare>,
    /// Publishes the sessions the share sheet offers.
    var publishTargets: (ShareTargets) -> Unit,
    /// Forgets published sessions for a server that is no longer saved.
    var removeTargets: (String) -> Unit,
) {
    companion object : DependencyKey<ShareInboxClient> {
        override val liveValue
            get() = ShareInboxClient(
                consume = {
                    withContext(Dispatchers.IO) {
                        ShareHandoffStore.live?.consumeAll(Instant.now(), ComposerFileStaging.directory) ?: emptyList()
                    }
                },
                publishTargets = { targets ->
                    Thread { targets.write() }.apply { isDaemon = true }.start()
                },
                removeTargets = { serverID ->
                    if (ShareTargets.load()?.serverID == serverID) ShareTargets.remove()
                },
            )

        override val testValue
            get() = ShareInboxClient(
                consume = { emptyList() },
                publishTargets = {},
                removeTargets = {},
            )
    }
}

var DependencyValues.shareInbox: ShareInboxClient
    get() = this[ShareInboxClient]
    set(value) {
        this[ShareInboxClient] = value
    }

/// What a share adds to one composer.
class SharedComposerDraft(
    var text: String = "",
    var attachments: List<OpenCodeOutgoingAttachment> = emptyList(),
    /// Staged non-image files; they upload once the composer absorbs them.
    var files: List<ComposerStagedFile> = emptyList(),
    /// Where a New Session draft should start.
    var projectID: String? = null,
) {
    constructor(share: ReceivedShare, projectID: String?) : this(
        text = share.handoff.text.trim(),
        attachments = share.images.map { OpenCodeOutgoingAttachment(id = it.id.toString(), data = it.data, mime = it.mime) },
        files = share.files,
        projectID = projectID,
    )

    fun append(other: SharedComposerDraft) {
        text = joined(text, other.text)
        attachments = attachments + other.attachments
        files = files + other.files
        projectID = other.projectID ?: projectID
    }

    override fun equals(other: Any?): Boolean {
        val draft = other as? SharedComposerDraft ?: return false
        return text == draft.text && attachments == draft.attachments && files == draft.files && projectID == draft.projectID
    }

    override fun hashCode(): Int = text.hashCode()

    companion object {
        fun joined(first: String, second: String): String {
            if (first.isEmpty()) return second
            if (second.isEmpty()) return first
            return first + "\n\n" + second
        }
    }
}

sealed interface SharedDraftTarget {
    data class session(val id: String) : SharedDraftTarget
    data object newSession : SharedDraftTarget
}

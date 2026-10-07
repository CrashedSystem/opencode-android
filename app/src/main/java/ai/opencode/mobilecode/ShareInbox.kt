@file:PortedFrom("OpenCode/ShareInbox.swift", "553a407")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.tca.DependencyKey
import ai.opencode.mobilecode.tca.DependencyValues
import java.io.File
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
data class SharedComposerDraft(
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

/// Shared text follows whatever is already typed; images join the tray.
/// Files join as uploads that `queuedUploadsStarted` sets going.
fun SessionFeature.State.absorb(shared: SharedComposerDraft) {
    draft = SharedComposerDraft.joined(draft, shared.text)
    pendingAttachments += shared.attachments
    pendingFiles += shared.files.map { ComposerFileAttachment(it) }
    unstartedUploadIDs += shared.files.map { it.id }
}

data class ResolvedShareTarget(val target: SharedDraftTarget, val projectID: String?)

/// A deleted session's items start a new session in the same project.
fun HomeFeature.State.resolvedShareTarget(target: ShareHandoff.Target): ResolvedShareTarget =
    when (target) {
        is ShareHandoff.Target.session ->
            if (sessions.any { it.id == target.id }) {
                ResolvedShareTarget(SharedDraftTarget.session(target.id), null)
            } else {
                ResolvedShareTarget(SharedDraftTarget.newSession, target.projectID)
            }
        is ShareHandoff.Target.newSession -> ResolvedShareTarget(SharedDraftTarget.newSession, target.projectID)
    }

/// Pinned and recent sessions in drawer order, without the drawer's search.
val HomeFeature.State.shareTargets: ShareTargets
    get() {
        val projectsByID = projects.distinctBy { it.id }.associateBy { it.id }
        fun target(session: OpenCodeSession) = ShareTargets.Session(
            id = session.id,
            title = session.title,
            projectID = session.projectID,
            projectName = projectsByID[session.projectID]?.name ?: File(session.directory).name,
        )
        val indexed = sessions.distinctBy { sessionKey(it.id) }.associateBy { sessionKey(it.id) }
        val pinned = pinnedSessionKeys.mapNotNull { indexed[it] }
        val recents = sessions.filter { !isPinned(it.id) }
            .sortedByDescending { it.updatedAt }
            .take(ShareTargets.maximumRecents)
        val draftProject = if (sessionDetail?.isDraft == true) sessionDetail?.draftProject else null
        val project = draftProject ?: projectsByRecency.firstOrNull()
        return ShareTargets(
            serverID = server.persistenceID,
            serverName = server.host,
            lastViewedSessionID = sessionDetail?.session?.id,
            newSessionProject = project?.let { ShareTargets.Project(id = it.id, name = it.name) },
            pinned = pinned.map(::target),
            recents = recents.map(::target),
        )
    }

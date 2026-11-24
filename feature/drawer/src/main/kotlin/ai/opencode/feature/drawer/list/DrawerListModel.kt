package ai.opencode.feature.drawer.list

import ai.opencode.core.strings.localized
import ai.opencode.feature.drawer.DrawerListFooter
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.drawer.HomeFeature.State.Indicator
import ai.opencode.feature.drawer.R
import ai.opencode.feature.drawer.ServerSwapTarget
import ai.opencode.feature.drawer.ServerSwitchNote
import ai.opencode.feature.drawer.drawerListFooter
import ai.opencode.feature.drawer.drawerSessions
import ai.opencode.feature.drawer.hiddenSessionIDs
import ai.opencode.feature.drawer.placeholderSession
import ai.opencode.feature.drawer.statusSnapshot
import ai.opencode.feature.drawer.style.DrawerSectionHeaderKind
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import java.time.Instant

/*
 * What the drawer's list shows, built from Home's state in one pass. Rows get these values, never a
 * store. Pure: the list view keys a `LazyColumn` by [DrawerListItem.key] and compares
 * [DrawerListRow]s to decide what recomposes.
 *
 * `DrawerTunables` are constants here, so rows read them directly.
 */

enum class DrawerListSection { top, pinned, recents, projects }

sealed interface DrawerListItem {
    data object CatchUp : DrawerListItem
    data object WhatsNew : DrawerListItem
    /** Under Catch Up while the server has archived sessions. */
    data object Archived : DrawerListItem
    data class Header(
        val kind: DrawerSectionHeaderKind,
        /** Set for a project's group header in Recents; null for Pinned and the flat Recents. */
        val projectID: String? = null,
        val title: String? = null,
        val count: Int = 0,
        val collapsed: Boolean = false,
        /** Whether the list scrolls to it ([headerFollows]). */
        val follows: Boolean = true,
    ) : DrawerListItem
    data class Session(val id: String) : DrawerListItem
    data object Empty : DrawerListItem
    /** Under the last row while more sessions can be read ([DrawerListFooter]). */
    data object Footer : DrawerListItem
    /** Server Flow: a row's place while the server picked answers. */
    data class Ghost(val index: Int) : DrawerListItem
    /** Server Flow: a switch went back on its own. */
    data object ServerNote : DrawerListItem

    /** A stable `LazyColumn` key. */
    val key: String
        get() = when (this) {
            CatchUp -> "catch-up"
            WhatsNew -> "whats-new"
            Archived -> "archived"
            is Header -> if (projectID == null) "header-${kind.name}" else "header-project-$projectID"
            is Session -> "session-$id"
            Empty -> "empty"
            Footer -> "footer"
            is Ghost -> "ghost-$index"
            ServerNote -> "server-note"
        }
}

/** One session row, compared to decide which rows to recompose. */
data class DrawerListRow(
    val session: OpenCodeSession,
    val isPinnedSection: Boolean,
    val selected: Boolean,
    val indicator: Indicator,
    val muted: Boolean,
    /** Hidden from the list; it shows only while searching. */
    val hidden: Boolean = false,
    val unread: Boolean,
    val isLifted: Boolean,
    val time: String,
    val project: OpenCodeProject?,
    val server: OpenCodeServer,
    val reducedMotion: Boolean,
    val increasedContrast: Boolean,
    /** Whether the row is chosen while the drawer selects rows ([DrawerSelection]); null when it isn't. */
    val checked: Boolean? = null,
    /** A new session whose first message only this phone has; null for a session the server has. */
    val unsent: Unsent? = null,
) {
    enum class Unsent { Sending, Failed }

    /** TalkBack's state for the row. */
    val accessibilityValue: String
        get() {
            val parts = mutableListOf<String>()
            if (isPinnedSection) parts += localized(R.string.drawer_row_pinned_state)
            if (muted) parts += localized(R.string.drawer_row_muted_state)
            if (hidden) parts += localized(R.string.drawer_row_hidden_state)
            if (unsent == Unsent.Failed) parts += localized(R.string.drawer_row_unsent_failed_state)
            if (unsent == Unsent.Sending) parts += localized(R.string.drawer_row_unsent_sending_state)
            val status = when (indicator) {
                Indicator.attention -> localized(R.string.drawer_row_needs_input_state)
                Indicator.running -> if (unsent == null) localized(R.string.drawer_row_working_state) else ""
                Indicator.unread -> localized(R.string.drawer_row_unread_state)
                Indicator.none -> ""
            }
            if (status.isNotEmpty()) parts += status
            if (time.isNotEmpty()) parts += time
            return parts.joinToString(", ")
        }
}

/** What the list shows besides its rows. */
data class DrawerListShortcuts(
    val catchUpCount: Int = 0,
    /** Catch Up just finished with nothing left: the finish's identity (`CatchUpFeature.State.finishes`), played once. */
    val catchUpFinish: Int? = null,
    val canMarkAllRead: Boolean = false,
    val showsWhatsNew: Boolean = false,
) {
    val catchUpFinished: Boolean get() = catchUpFinish != null
    val showsCatchUp: Boolean get() = catchUpCount > 0 || catchUpFinished
}

/** The list's view-local inputs beyond Home's state. */
data class DrawerListInputs(
    val selectedSessionID: String?,
    val reducedMotion: Boolean,
    val increasedContrast: Boolean,
    /** Section headers always show. */
    val alwaysShowsHeaders: Boolean = true,
    /** Server Flow: the server the drawer shows, which may still be connecting. */
    val serverSwap: ServerSwapTarget? = null,
    /** The rows chosen in selection mode ([DrawerSelection]). */
    val checkedIDs: Set<String> = emptySet(),
    /** Project groups the reader folded in Recents, by project ID. */
    val collapsedProjectIDs: Set<String> = emptySet(),
)

/** One project's sessions in Recents, newest first, in the server's order. */
internal data class DrawerProjectGroup(
    /** The project's ID; "" for the home/root project that owns sessions outside a project. */
    val id: String,
    val name: String,
    val sessions: List<OpenCodeSession>,
    /** A new session whose first message only this phone has. */
    val unsent: List<DrawerListRow> = emptyList(),
) {
    val count: Int get() = sessions.size + unsent.size
}

/**
 * [recents] split by project, keeping the server's recency order inside each group and ordering
 * the groups by their newest session. A session in no known project (the home folder or the
 * server's root) gets a group named after its folder, so it is never silently mixed in.
 *
 * [projectOf] is the caller's resolver — the same one the rows use for their tiles — so a group
 * header and the rows under it can never disagree about which project a session is in.
 */
internal fun projectGroups(
    recents: List<OpenCodeSession>,
    unsent: List<DrawerListRow>,
    projectOf: (OpenCodeSession, List<OpenCodeProject>) -> OpenCodeProject?,
    projects: List<OpenCodeProject>,
): List<DrawerProjectGroup> {
    val byID = LinkedHashMap<String, MutableList<OpenCodeSession>>()
    for (session in recents) {
        val id = projectOf(session, projects)?.id ?: ""
        byID.getOrPut(id) { mutableListOf() } += session
    }
    // Unsent drafts belong to their draft's project; they lead their group's rows.
    val unsentByID = unsent.groupBy { projectOf(it.session, projects)?.id ?: "" }
    val ids = LinkedHashSet<String>().apply {
        addAll(byID.keys)
        addAll(unsentByID.keys)
    }
    return ids.map { id ->
        val first = byID[id]?.firstOrNull() ?: unsentByID[id]!!.first().session
        val project = projectOf(first, projects)
        DrawerProjectGroup(
            id = id,
            name = project?.name?.ifEmpty { localized(R.string.drawer_section_recents) }
                ?: localized(R.string.drawer_section_recents),
            sessions = byID[id].orEmpty(),
            unsent = unsentByID[id].orEmpty(),
        )
    }
}

data class DrawerListModel(
    val sections: Map<DrawerListSection, List<DrawerListItem>> = emptyMap(),
    val rows: Map<String, DrawerListRow> = emptyMap(),
    val headerFollows: Map<DrawerSectionHeaderKind, Boolean> = emptyMap(),
    val shortcuts: DrawerListShortcuts = DrawerListShortcuts(),
    val archivedCount: Int = 0,
    val serverNote: ServerSwitchNote? = null,
    val search: String = "",
    val footer: DrawerListFooter? = null,
    /** Page answers read: each one checks the footer again. */
    val pagesRead: Int = 0,
    val serverID: String = "",
    val reducedMotion: Boolean = false,
) {
    /** Every item in display order. */
    val items: List<DrawerListItem> get() = order.flatMap { sections[it].orEmpty() }

    companion object {
        val order = listOf(DrawerListSection.top, DrawerListSection.pinned, DrawerListSection.recents)
        const val ghostCount = 5

        /**
         * Home's state as the drawer shows it: shortcuts, then Pinned and Recents with their
         * headers. [project] resolves a row's project. Every row has a project: a session in the
         * home folder or at the server's root gets one derived from its directory, so its tile is
         * stable from the first frame.
         */
        fun build(
            state: HomeFeature.State,
            inputs: DrawerListInputs,
            liftedID: String?,
            now: Instant,
            timestamp: (date: Instant, now: Instant) -> String,
            project: (OpenCodeSession, List<OpenCodeProject>) -> OpenCodeProject?,
        ): DrawerListModel {
            var serverNote: ServerSwitchNote? = null
            val swap = inputs.serverSwap
            if (swap != null) {
                if (swap.isConnecting) {
                    // Ghost rows hold the other server's place until it answers.
                    return DrawerListModel(
                        sections = mapOf(
                            DrawerListSection.top to emptyList(),
                            DrawerListSection.pinned to emptyList(),
                            DrawerListSection.recents to listOf(DrawerListItem.Header(DrawerSectionHeaderKind.Recents)) +
                                (0 until ghostCount).map { DrawerListItem.Ghost(it) },
                        ),
                        headerFollows = mapOf(DrawerSectionHeaderKind.Recents to false),
                        search = state.search,
                        serverID = swap.id,
                        reducedMotion = inputs.reducedMotion,
                    )
                }
                serverNote = state.serverSwitchNote
            }
            val status = state.statusSnapshot()
            val catchUpSessions = state.sessions.filter { status.needsCatchUp(it) }
            val catchUpCount = catchUpSessions.size
            val shortcuts = DrawerListShortcuts(
                catchUpCount = catchUpCount,
                catchUpFinish = if (state.catchUpState.justFinished && catchUpCount == 0) state.catchUpState.finishes else null,
                canMarkAllRead = catchUpSessions.any { status.indicator(it) == Indicator.unread },
                showsWhatsNew = state.showsWhatsNewButton,
            )
            val archivedCount = if (state.search.isEmpty()) state.inboxSidebarStatus.archived.size else 0
            val showsShortcuts = state.search.isEmpty() && (shortcuts.showsCatchUp || shortcuts.showsWhatsNew) || archivedCount > 0

            val top = buildList {
                if (serverNote != null && state.search.isEmpty()) add(DrawerListItem.ServerNote)
                if (state.search.isEmpty()) {
                    add(DrawerListItem.CatchUp)
                    add(DrawerListItem.WhatsNew)
                }
                if (archivedCount > 0) add(DrawerListItem.Archived)
            }

            // One pass for both lists.
            val sessions = state.drawerSessions
            val pinned = sessions.pinned
            val recents = sessions.recents
            val rows = LinkedHashMap<String, DrawerListRow>()
            val headerFollows = HashMap<DrawerSectionHeaderKind, Boolean>()
            val hiddenIDs = state.hiddenSessionIDs

            fun row(session: OpenCodeSession, pinned: Boolean) = DrawerListRow(
                session = session,
                isPinnedSection = pinned,
                selected = inputs.selectedSessionID == session.id,
                indicator = status.indicator(session),
                muted = status.isMuted(session.id),
                hidden = session.id in hiddenIDs,
                unread = status.isUnread(session),
                isLifted = liftedID == session.id,
                time = timestamp(session.updatedAt, now),
                project = project(session, state.projects),
                server = state.server,
                reducedMotion = inputs.reducedMotion,
                increasedContrast = inputs.increasedContrast,
                checked = if (inputs.checkedIDs.isEmpty()) null else session.id in inputs.checkedIDs,
            )

            fun header(kind: DrawerSectionHeaderKind, follows: Boolean): List<DrawerListItem> {
                headerFollows[kind] = follows
                return if (inputs.alwaysShowsHeaders || follows) listOf(DrawerListItem.Header(kind)) else emptyList()
            }

            val pinnedItems = mutableListOf<DrawerListItem>()
            if (pinned.isNotEmpty()) {
                pinnedItems += header(DrawerSectionHeaderKind.Pinned, follows = showsShortcuts)
                for (session in pinned) {
                    rows[session.id] = row(session, pinned = true)
                    pinnedItems += DrawerListItem.Session(session.id)
                }
            }
            // Unsent sessions lead Recents: they wait on you, and the server can't order them.
            val open = if (state.catchUp == null) state.sessionDetail else null
            val unsentRows = mutableListOf<DrawerListRow>()
            for (draft in state.unsentDrafts) {
                val session = draft.placeholderSession
                if (session.id in rows || state.sessions.any { it.id == session.id }) continue
                if (state.search.isNotEmpty() && !session.title.contains(state.search.trim(), ignoreCase = true)) continue
                unsentRows += DrawerListRow(
                    session = session,
                    isPinnedSection = false,
                    selected = open != null && open.session == null && open.outboxDraftID == draft.draftID,
                    indicator = if (draft.isFailed) Indicator.none else Indicator.running,
                    muted = false,
                    unread = false,
                    isLifted = false,
                    time = timestamp(session.updatedAt, now),
                    project = project(session, state.projects),
                    server = state.server,
                    reducedMotion = inputs.reducedMotion,
                    increasedContrast = inputs.increasedContrast,
                    unsent = if (draft.isFailed) DrawerListRow.Unsent.Failed else DrawerListRow.Unsent.Sending,
                )
            }
            val footer = state.drawerListFooter
            val recentItems = mutableListOf<DrawerListItem>()
            // Recents by project: the flat list keeps the server's order, and a project's sessions
            // sit together under a header that folds. A search stays flat — folding would hide the
            // hit being looked for — and so does a single project, where a header per group is
            // noise rather than structure. [project] is the caller's resolver, so the grouping and
            // the rows' tiles agree on which project a session belongs to.
            val groups = if (state.search.isEmpty()) {
                projectGroups(recents, unsentRows, project, state.projects)
            } else {
                emptyList()
            }
            val grouped = groups.size > 1
            if (recents.isNotEmpty() || unsentRows.isNotEmpty()) {
                if (!grouped) {
                    recentItems += header(DrawerSectionHeaderKind.Recents, follows = pinned.isNotEmpty() || showsShortcuts)
                    for (unsent in unsentRows) {
                        rows[unsent.session.id] = unsent
                        recentItems += DrawerListItem.Session(unsent.session.id)
                    }
                    for (session in recents) {
                        if (session.id in rows) continue
                        rows[session.id] = row(session, pinned = false)
                        recentItems += DrawerListItem.Session(session.id)
                    }
                } else {
                    for (group in groups) {
                        val collapsed = group.id in inputs.collapsedProjectIDs
                        recentItems += DrawerListItem.Header(
                            DrawerSectionHeaderKind.Recents,
                            projectID = group.id,
                            title = group.name,
                            count = group.count,
                            collapsed = collapsed,
                        )
                        if (collapsed) continue
                        for (unsent in group.unsent) {
                            rows[unsent.session.id] = unsent
                            recentItems += DrawerListItem.Session(unsent.session.id)
                        }
                        for (session in group.sessions) {
                            if (session.id in rows) continue
                            rows[session.id] = row(session, pinned = false)
                            recentItems += DrawerListItem.Session(session.id)
                        }
                    }
                }
            } else if (pinned.isEmpty() && (state.search.isNotEmpty() || state.hasConnected) && footer == null) {
                recentItems += DrawerListItem.Empty
            }
            if (footer != null) recentItems += DrawerListItem.Footer
            return DrawerListModel(
                sections = mapOf(
                    DrawerListSection.top to top,
                    DrawerListSection.pinned to pinnedItems,
                    DrawerListSection.recents to recentItems,
                ),
                rows = rows,
                headerFollows = headerFollows,
                shortcuts = shortcuts,
                archivedCount = archivedCount,
                serverNote = serverNote,
                search = state.search,
                footer = footer,
                pagesRead = if (footer != null) state.sessionPages.pagesRead else 0,
                serverID = swap?.id ?: state.server.persistenceID,
                reducedMotion = inputs.reducedMotion,
            )
        }
    }
}

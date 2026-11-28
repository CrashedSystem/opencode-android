package ai.opencode.app

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.system.notifications.LocalSessionNotifications

/**
 * Posts the alerts the relay cannot carry: a background shell or a subagent finishing while the
 * app is away.
 *
 * The notify plugin sends for the session a prompt came from. A background shell is not a session,
 * and a subagent is a session with a `parentID` the plugin skips, so neither reached the phone
 * before. These are read from the transcript the app already holds, so they need no server change.
 */
class LocalSessionAlertsClient(
    private val subagentsFinished: suspend (titles: List<String>, count: Int) -> Unit,
    private val shellFinished: suspend (title: String, command: String, exitCode: Int?) -> Unit,
) {
    /** Subagents of [detail]'s session that were running and are not any more. */
    suspend fun subagentsFinished(detail: SessionFeature.State, finishedIDs: Set<String>) {
        if (finishedIDs.isEmpty()) return
        val names = detail.familySessions.filter { it.id in finishedIDs }.map { it.title }
        subagentsFinished(names, names.size)
    }

    /** Background shells that ended, with the command each was running. */
    suspend fun shellsFinished(detail: SessionFeature.State, finishedIDs: Set<String>) {
        if (finishedIDs.isEmpty()) return
        val shells = detail.shells.filter { it.id in finishedIDs }
        if (shells.isEmpty()) return
        val last = shells.last()
        val title = detail.session?.serverTitle.orEmpty()
        shellFinished(title, last.command, last.exitCode)
    }

    companion object Key : DependencyKey<LocalSessionAlertsClient> {
        override val liveValue: LocalSessionAlertsClient by lazy {
            val context = dependency(AppContext).applicationContext
            LocalSessionAlertsClient(
                subagentsFinished = { titles, count ->
                    LocalSessionNotifications.subagent(
                        context = context,
                        sessionID = "",
                        title = titles.firstOrNull().orEmpty(),
                        serverKey = null,
                        count = count,
                    )
                },
                shellFinished = { title, command, exitCode ->
                    LocalSessionNotifications.shell(
                        context = context,
                        sessionID = "",
                        title = title,
                        command = command,
                        exitCode = exitCode,
                        serverKey = null,
                    )
                },
            )
        }
    }
}

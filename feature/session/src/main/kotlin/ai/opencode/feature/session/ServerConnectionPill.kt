package ai.opencode.feature.session

import ai.opencode.core.R as CoreR
import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.MotionTransition
import ai.opencode.core.strings.localized
import ai.opencode.server.OpenCodeClientError
import ai.opencode.core.ui.OpenCodeShape
import ai.opencode.core.ui.OpenCodeSurfaces
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.openCodeTapTarget
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * Server Flow's tunables (iOS `ServerFlowTunables`, `OpenCode/Drawer/ServerFlow.swift`), moved
 * down from the drawer because the session's [ServerConnectionPill] reads the pill's palette;
 * the drawer reads the springs and travels, the app the switch-back timeout. Constants here:
 * Android has no Tuning Panel.
 */
object ServerFlowTunables {
    /** Switch back after (s). */
    const val timeout = 8.0
    /** Offline after this long with two failed attempts (s). */
    const val offlineGrace = 12.0
    /** Offline sooner when the phone has no network at all (s). */
    const val unsatisfiedOfflineGrace = 4.0
    /** How far each ghost row trails the one above (s); the springs themselves are `MotionRole`s. */
    const val chainLag = 0.08
    const val titleTravel = 22f
    const val listTravel = 28f
    const val blur = 2f
    /**
     * How long a reconnect stays quiet before “Reconnecting…” shows (s; the pill and the drawer
     * title), so the first connect at launch, a return to the app and a quick reconnect never
     * flash it (`HomeConnectionFeature.State.displayedStatus`).
     */
    const val grace = 2.0
}

/**
 * What a session's pill says while its server isn't connected (iOS `ServerConnectionNotice`,
 * `OpenCode/Drawer/ServerFlow.swift`; here in `:feature:session` because [SessionListContext]
 * carries it). The drawer builds it from Home's connection (`ServerConnectionNotice(status:…)`
 * in `:feature:drawer`'s `ServerFlow.kt`).
 */
data class ServerConnectionNotice(
    val kind: Kind,
    val serverName: String,
    /** Why it failed, when the error says more than the title. */
    val detail: String? = null,
) {
    enum class Kind { reconnecting, failed, signInFailed }

    enum class Action { retry, pairAgain, editServer }

    val title: String
        get() = when (kind) {
            Kind.reconnecting -> localized(R.string.session_server_reconnecting, serverName)
            Kind.failed -> localized(R.string.session_server_connect_failed, serverName)
            Kind.signInFailed -> localized(R.string.session_server_sign_in_failed, serverName)
        }

    /** The pill's tap: retry, or pair again for a sign-in the server refused. */
    val primary: Action get() = if (kind == Kind.signInFailed) Action.pairAgain else Action.retry

    /** The word at the pill's end, naming its tap. */
    val action: String?
        get() = when (kind) {
            Kind.reconnecting -> null
            Kind.failed -> localized(CoreR.string.common_try_again)
            Kind.signInFailed -> localized(R.string.session_server_pair_again)
        }

    /** What TalkBack says the pill's tap does (“Double-tap to try again”). */
    val actionClickLabel: String?
        get() = when (kind) {
            Kind.reconnecting -> null
            Kind.failed -> localized(R.string.session_server_try_again_click_label)
            Kind.signInFailed -> localized(R.string.session_server_pair_again_click_label)
        }

    val systemImage: SFSymbol
        get() = when (kind) {
            Kind.reconnecting -> SFSymbol.ArrowTriangle2Circlepath
            Kind.failed -> SFSymbol.ExclamationmarkCircleFill
            Kind.signInFailed -> SFSymbol.KeyFill
        }

    companion object {
        /**
         * iOS `init?(status:credentialsRejected:errorMessage:server:isSwitching:)` without Home's
         * status type: null while connected and while the drawer switches servers (the switch is
         * the drawer's to show). Pass Home's displayed status, which reads connected while a
         * reconnect is still in its grace.
         */
        fun of(
            isConnected: Boolean,
            isReconnecting: Boolean,
            credentialsRejected: Boolean,
            errorMessage: String?,
            serverName: String,
            isSwitching: Boolean,
        ): ServerConnectionNotice? {
            if (isSwitching || isConnected) return null
            if (isReconnecting) return ServerConnectionNotice(Kind.reconnecting, serverName)
            if (credentialsRejected) return ServerConnectionNotice(Kind.signInFailed, serverName)
            // The pill already says the server couldn't be reached, so that message adds nothing as a detail.
            // The English check covers the other “Couldn’t …” messages; in other languages those show as the detail.
            val detail = errorMessage?.takeIf {
                it.isNotEmpty() && it != OpenCodeClientError.UNREACHABLE_MESSAGE && !it.startsWith("Couldn’t")
            }
            return ServerConnectionNotice(Kind.failed, serverName, detail)
        }
    }
}

/**
 * Server Flow: a pill right above the composer while the session's server isn't connected
 * (iOS `ServerConnectionPill`). It says what's happening, and its tap retries (or pairs again,
 * for a sign-in the server refused); a long press offers Edit Server… then. iOS's glass capsule
 * is an M3 tonal surface here.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ServerConnectionPill(
    notice: ServerConnectionNotice,
    onAction: (ServerConnectionNotice.Action) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = when (notice.kind) {
        ServerConnectionNotice.Kind.reconnecting -> OpenCodeTheme.colors.secondaryText
        ServerConnectionNotice.Kind.failed -> OpenCodeTheme.colors.destructive
        ServerConnectionNotice.Kind.signInFailed -> Color(0xFFFF9500)
    }
    val offersEdit = notice.kind == ServerConnectionNotice.Kind.signInFailed
    var menuShown by remember { mutableStateOf(false) }
    val fontScale = LocalDensity.current.fontScale
    val editServerLabel = stringResource(R.string.session_server_edit)
    Box(modifier) {
        // The desktop's floating chip: flat fill, hairline ring, small radius.
        Surface(
            shape = OpenCodeShape.control,
            color = OpenCodeSurfaces.color,
            border = OpenCodeSurfaces.border,
            shadowElevation = OpenCodeSurfaces.raisedShadow,
            modifier = Modifier
                .clip(OpenCodeShape.control)
                .combinedClickable(
                    onClick = { onAction(notice.primary) },
                    onLongClick = if (offersEdit) ({ menuShown = true }) else null,
                )
                .openCodeTapTarget()
                .clearAndSetSemantics {
                    contentDescription = notice.title
                    notice.detail?.let { stateDescription = it }
                    onClick(label = notice.actionClickLabel) { onAction(notice.primary); true }
                    if (offersEdit) {
                        customActions = listOf(CustomAccessibilityAction(editServerLabel) { onAction(ServerConnectionNotice.Action.editServer); true })
                    }
                }
                .testTag("server-connection-pill"),
        ) {
            Row(
                Modifier.heightIn(min = 44.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (notice.kind == ServerConnectionNotice.Kind.reconnecting) {
                    CircularProgressIndicator(Modifier.size((16 * fontScale).dp), color = tint, strokeWidth = 2.dp)
                } else {
                    OpenCodeIcon(notice.systemImage, contentDescription = null, size = (18 * fontScale).dp, tint = tint, modifier = Modifier.width((18 * fontScale).dp))
                }
                Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        notice.title,
                        style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.Medium),
                        color = OpenCodeTheme.colors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    notice.detail?.let {
                        Text(it, style = OpenCodeTextStyles.caption1, color = OpenCodeTheme.colors.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                notice.action?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(it, style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.SemiBold), color = OpenCodeTheme.colors.accent)
                }
            }
        }
        DropdownMenu(expanded = menuShown && offersEdit, onDismissRequest = { menuShown = false }) {
            DropdownMenuItem(
                text = { Text(editServerLabel) },
                leadingIcon = { OpenCodeIcon(SFSymbol.Pencil, contentDescription = null) },
                onClick = {
                    menuShown = false
                    onAction(ServerConnectionNotice.Action.editServer)
                },
            )
        }
    }
}

/**
 * iOS `.motionMicroTransition()` on the pill, with its room in the composer's column
 * (`composerColumnAnimation`): the room opens on `layout` and closes on `collapse`, so the
 * composer's top, and the transcript riding it, move with the pill instead of jumping its height
 * in one frame. Under Reduce Motion it cross-fades.
 */
object ServerConnectionPillTransition {
    fun enter(reducedMotion: Boolean): EnterTransition {
        val room = expandVertically(
            Motion.layout.animation(MotionPhase.Enter, reducedMotion, visibilityThreshold = IntSize.VisibilityThreshold),
            Alignment.Bottom,
            clip = false,
        )
        return room + MotionTransition.micro(reducedMotion).enter
    }

    fun exit(reducedMotion: Boolean): ExitTransition {
        val room = shrinkVertically(
            Motion.layout.animation(MotionPhase.Exit, reducedMotion, visibilityThreshold = IntSize.VisibilityThreshold),
            Alignment.Bottom,
            clip = false,
        )
        return room + MotionTransition.micro(reducedMotion).exit
    }
}

package ai.opencode.feature.session

import ai.opencode.core.motion.transitionBlur

import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.MotionSpring
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.observe
import ai.opencode.core.ui.OpenCodeColors
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeIconButton
import ai.opencode.core.ui.OpenCodeIconButtonStyle
import ai.opencode.core.ui.OpenCodeIconSize
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.openCodeTapTarget
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.session.header.BreathingTitleHold
import ai.opencode.feature.session.header.HeaderBlurTunables
import ai.opencode.feature.session.header.SessionContextGauge
import ai.opencode.feature.session.header.SessionContextText
import ai.opencode.server.model.OpenCodeExportFormat
import ai.opencode.server.model.isUntitled
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max

// Port of iOS `Session/SessionView+Header.swift`: the header row (drawer or Back, the title
// capsule, Back to main session and the … session menu), and the session menu's items. The
// header's Liquid Glass controls are M3 tonal surfaces; the … menu is a Material dropdown that
// runs the Session sheet's actions without opening it (`Action.SessionMenu`).

/** The header's controls: the 44 pt glass circle, as a 48 dp tonal circle (the minimum target). */
internal val headerButtonSize: Dp = max(HeaderBlurTunables.buttonSize.value, 48f).dp

/** iOS `dynamicTypeSize.isAccessibilitySize`: the AX sizes start at about 1.65× body. */
internal fun isAccessibilityFontScale(fontScale: Float): Boolean = fontScale >= 1.65f

/** What the header reads from the session, observed as one value so the header recomposes only when it changes. */
@Immutable
internal data class SessionHeaderModel(
    val titleText: String?,
    val draftProjectName: String?,
    val isDraft: Boolean,
    val isSyncing: Boolean,
    val isLastKnown: Boolean,
    val autoRenameFailed: Boolean,
    val isBusy: Boolean,
    val isResyncing: Boolean,
    val refreshFailed: Boolean,
    val canChooseProject: Boolean,
    val identity: TranscriptIdentity,
    val sessionID: String?,
    val sessionTitle: String?,
    val familyRootID: String?,
    val contextFraction: Double?,
    val contextTokens: Int?,
    val contextLimit: Int?,
    /** What Rename starts from: the server's title, or nothing while the session reads as its first prompt. */
    val renameTitle: String = "",
) {
    /** A subagent family's root is elsewhere: Back to main session. */
    val backToRootID: String? get() = familyRootID?.takeIf { it != sessionID }

    companion object {
        fun from(state: State): SessionHeaderModel {
            val titleText = headerTitleText(state)
            val isDraft = state.isDraft
            // A session just made from this draft reads New Session as the draft did, without a resync's breathing.
            val isSyncing = !isDraft &&
                !(state.session?.id == state.draftBecameSessionID && titleText == "New Session") &&
                (state.isResyncing || state.sessionSheet?.operation == SessionSheetFeature.Operation.AutoRenaming)
            return SessionHeaderModel(
                titleText = titleText,
                draftProjectName = if (isDraft || state.session?.id == state.draftBecameSessionID) state.draftProject?.name else null,
                isDraft = isDraft,
                isSyncing = isSyncing,
                isLastKnown = !isDraft && state.refreshFailed,
                autoRenameFailed = state.sessionSheet?.autoRenameFailed == true,
                isBusy = state.isBusy,
                isResyncing = state.isResyncing,
                refreshFailed = state.refreshFailed,
                canChooseProject = state.canChooseProject,
                identity = state.transcriptIdentity,
                sessionID = state.session?.id,
                sessionTitle = state.session?.title,
                familyRootID = state.familyRootID,
                contextFraction = state.contextFraction,
                contextTokens = state.contextUsage?.tokens,
                contextLimit = state.contextLimit,
                renameTitle = state.session?.let { if (it.isUntitled) "" else it.title }.orEmpty(),
            )
        }

        /**
         * What the header capsule reads: a draft's New Session, and once its session exists the
         * session's title. The server's placeholder title reads the same as the draft's, so the
         * first send changes nothing here; a real title replaces it in the same capsule.
         */
        fun headerTitleText(state: State): String? {
            val session = state.session ?: return if (state.isDraft) "New Session" else null
            val title = session.title.trim()
            if (title.isEmpty() || title.equals("New session", ignoreCase = true)) return "New Session"
            return session.title
        }
    }
}

/**
 * The header row (iOS `headerControls(hostsSessionMenu:)`): the drawer button (or Back on a
 * pushed subagent, nothing on a Catch Up card), the title capsule centred on the header, and the
 * trailing controls. Reports its laid-out height for the backdrop.
 *
 * @param navigationIcon the drawer button Home supplies (iOS `drawerControl`).
 * @param onHeaderHeight the row's height in dp, whenever it changes.
 */
@Composable
internal fun SessionHeaderControls(
    store: StoreOf<State, Action>,
    listContext: SessionListContext,
    navigationIcon: @Composable () -> Unit,
    onBack: (() -> Unit)?,
    onCatchUpOpen: (() -> Unit)?,
    onHeaderHeight: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val model by store.observe(SessionHeaderModel::from)
    val reducedMotion = rememberReduceMotion()
    val density = LocalDensity.current
    val showsSessionMenu = onCatchUpOpen == null && model.sessionID != null
    // A draft keeps the session menu's place, so the menu fading in as its session arrives moves nothing.
    val holdsSessionMenuSlot = onCatchUpOpen == null && model.isDraft
    // While the drawer button's label shows, the title steps aside under it.
    val noticeCoversTitle = onBack == null && listContext.notice != null

    // Android divergence: a Material top app bar (start-aligned title, plain icon buttons, 64 dp
    // tall) in place of iOS's centred title capsule between glass circles.
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            // Measured with the card's top inset, as iOS does, so the scrolled bar and the
            // transcript's top clear the whole header.
            .onSizeChanged { onHeaderHeight(with(density) { it.height.toDp().value }) }
            .padding(top = if (onCatchUpOpen == null) 0.dp else CATCH_UP_COMPOSER_INSET.dp)
            .heightIn(min = TOP_APP_BAR_HEIGHT),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            onBack != null -> BackControl(onBack)
            onCatchUpOpen == null -> navigationIcon()
        }
        Box(
            Modifier
                .weight(1f)
                .steppingAside(noticeCoversTitle, reducedMotion),
            contentAlignment = Alignment.CenterStart,
        ) {
            HeaderTitle(store, model, listContext, onCatchUpOpen, reducedMotion)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
            model.backToRootID?.let { rootID ->
                FloatingControl(SFSymbol.ArrowUturnBackward, "Back to main session") { store.send(Action.FamilyPicked(rootID)) }
            }
            when {
                // It fades into the slot a draft kept for it.
                showsSessionMenu -> AnimatedVisibility(
                    visibleState = remember { MutableTransitionState(false).apply { targetState = true } },
                    enter = fadeIn(Motion.microIn.animation(MotionPhase.Enter, reducedMotion)),
                    exit = fadeOut(Motion.microIn.animation(MotionPhase.Exit, reducedMotion)),
                ) { SessionMenuButton(store, model, listContext) }
                holdsSessionMenuSlot -> Spacer(Modifier.size(headerButtonSize).clearAndSetSemantics {})
            }
        }
    }
}

/** iOS `CatchUpMetrics.composerInset`. */
private const val CATCH_UP_COMPOSER_INSET = 12f

/** Material 3's small top app bar height. */
private val TOP_APP_BAR_HEIGHT = 64.dp

/** The drawer button's label grows over the title: it fades and blurs out of the way, and back once it has folded past. */
private fun Modifier.steppingAside(aside: Boolean, reducedMotion: Boolean): Modifier = composed {
    val spec = when {
        reducedMotion -> tween<Float>(250, easing = Motion.EaseInOut)
        aside -> tween(180, easing = Motion.EaseOut)
        else -> asideSmooth.animation<Float>(reduceMotion = false, delaySeconds = 0.12)
    }
    val progress by animateFloatAsState(if (aside) 1f else 0f, spec, label = "steppingAside")
    this
        .alpha(1 - progress)
        .transitionBlur(if (reducedMotion) 0.dp else (6 * progress).dp)
        .then(if (aside) Modifier.clearAndSetSemantics {} else Modifier)
}

/** SwiftUI `.smooth(duration: 0.35)`. */
private val asideSmooth = MotionSpring(0.35, 0.0)

/** A pushed subagent's way back to its parent, where the drawer button is on the root session: the same circle. */
@Composable
private fun BackControl(onBack: () -> Unit) {
    OpenCodeIconButton(
        symbol = SFSymbol.ChevronBackward,
        contentDescription = "Back",
        onClick = onBack,
        size = headerButtonSize,
        style = OpenCodeIconButtonStyle.Standard,
        modifier = Modifier.testTag("subagent-back"),
    )
}

@Composable
internal fun FloatingControl(symbol: SFSymbol, label: String, onClick: () -> Unit) {
    OpenCodeIconButton(symbol = symbol, contentDescription = label, onClick = onClick, size = headerButtonSize, style = OpenCodeIconButtonStyle.Standard)
}

/** iOS `headerProjectColor(for:)`: secondary under high contrast, else primary at 75 %. */
internal fun headerProjectColor(colors: OpenCodeColors): Color =
    if (colors.highContrast) colors.secondaryText else colors.text.copy(alpha = 0.75f)

/**
 * The header's one title capsule. A draft and the session it becomes share it, so creating the
 * session never replaces or moves it. A draft's capsule chooses its project; a session's shows
 * its details; a Catch Up card's opens the session.
 */
@Composable
private fun HeaderTitle(
    store: StoreOf<State, Action>,
    model: SessionHeaderModel,
    listContext: SessionListContext,
    onCatchUpOpen: (() -> Unit)?,
    reducedMotion: Boolean,
) {
    val latestTitle = model.titleText ?: return
    // The capsule's second line: the project, which a draft knows before the session list does.
    val project = listContext.project?.name ?: model.draftProjectName
    val projectColor = headerProjectColor(OpenCodeTheme.colors)
    val fontScale = LocalDensity.current.fontScale
    // Auto Rename's new title waits for the breath to ease back to full; another session switched
    // in place shows its own title at once.
    BreathingTitleHold(identity = model.identity, title = latestTitle, isBreathing = model.isSyncing) { title, animates ->
        val subtitle = if (model.autoRenameFailed) "Couldn’t rename" else project
        val onClick: () -> Unit = onCatchUpOpen
            ?: { store.send(if (model.isDraft) Action.ProjectButtonTapped else Action.SessionInfoTapped) }
        val hint = when {
            onCatchUpOpen != null -> "Opens the session and leaves Catch Up"
            model.isDraft -> if (model.canChooseProject) "Chooses where the session starts" else null
            else -> "Shows session details"
        }
        val value = if (model.isDraft) {
            ""
        } else {
            listOfNotNull(
                if (model.isBusy) "Running" else "Idle",
                if (model.isResyncing) "Syncing" else if (model.refreshFailed) "Last known" else null,
            ).joinToString(", ")
        }
        // Android divergence: the title sits on the bar itself (no capsule); a press shows the
        // ripple in a rounded rectangle around it.
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = Color.Transparent,
            tonalElevation = 0.dp,
            modifier = Modifier
                .clip(MaterialTheme.shapes.medium)
                .clickable(role = Role.Button, onClickLabel = hint, onClick = onClick)
                .openCodeTapTarget()
                .testTag(if (model.isDraft) "draft-title" else "session-title")
                .clearAndSetSemantics {
                    contentDescription = listOfNotNull(title, subtitle).joinToString(", ")
                    if (value.isNotEmpty()) stateDescription = value
                },
        ) {
            Column(
                Modifier
                    // The header doesn't carry the screen's transactions: the capsule animates its own width.
                    .animateContentSize(if (!animates) snap() else Motion.layout.animation(reduceMotion = reducedMotion))
                    .heightIn(min = headerButtonSize)
                    .padding(horizontal = 8.dp, vertical = if (isAccessibilityFontScale(fontScale)) 8.dp else 3.dp),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SyncingSessionTitle(
                        title = title,
                        isSyncing = model.isSyncing,
                        isLastKnown = model.isLastKnown,
                        animates = animates,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (onCatchUpOpen != null) {
                        OpenCodeIcon(SFSymbol.ChevronRight, contentDescription = null, size = 12.dp, tint = OpenCodeTheme.colors.tertiaryText)
                    }
                }
                AnimatedVisibility(
                    visible = subtitle != null,
                    enter = fadeIn(Motion.layout.animation(reduceMotion = reducedMotion)),
                    exit = fadeOut(Motion.layout.animation(reduceMotion = reducedMotion)),
                ) {
                    val shown = subtitle ?: ""
                    if (model.autoRenameFailed) {
                        Text(
                            shown,
                            style = MaterialTheme.typography.labelMedium,
                            color = projectColor,
                            maxLines = 1,
                        )
                    } else {
                        ProjectHeaderSubtitle(shown) {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelMedium,
                                color = projectColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Session actions; the rim doubles as the context gauge. */
@Composable
private fun SessionMenuButton(store: StoreOf<State, Action>, model: SessionHeaderModel, listContext: SessionListContext) {
    var expanded by remember { mutableStateOf(false) }
    val value = model.contextTokens?.let { SessionContextText.accessibilityValue(it, model.contextLimit) } ?: ""
    Box {
        OpenCodeIconButton(
            symbol = SFSymbol.Ellipsis,
            contentDescription = "More",
            onClick = { expanded = true },
            size = headerButtonSize,
            style = OpenCodeIconButtonStyle.Standard,
            modifier = Modifier
                .testTag("session-menu")
                .semantics { if (value.isNotEmpty()) stateDescription = value },
        )
        val fraction = model.contextFraction
        AnimatedVisibility(visible = fraction != null, modifier = Modifier.matchParentSize(), enter = fadeIn(), exit = fadeOut()) {
            SessionContextGauge(
                fraction = fraction ?: 0.0,
                modifier = Modifier.fillMaxSize().padding(6.dp),
            )
        }
        SessionMenu(
            expanded = expanded,
            onDismiss = { expanded = false },
            title = model.sessionTitle ?: "Session",
            renameTitle = model.renameTitle,
            listContext = listContext,
            send = {
                expanded = false
                store.send(it)
            },
        )
    }
}

/** iOS `sessionMenuItems`: the session's own actions, its work, and deletion. */
@Composable
private fun SessionMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    title: String,
    renameTitle: String,
    listContext: SessionListContext,
    send: (Action) -> Unit,
) {
    val developerMode by remember { shared(SharedKeys.developerMode) }.flow.collectAsState()
    var exportOpen by remember(expanded) { mutableStateOf(false) }
    fun menu(action: SessionSheetFeature.Action) = send(Action.SessionMenu(action))
    // No title heading: the header already shows it (6bbf321d); exports still name the file by it.
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        // Android divergence: Export's formats take the menu's place behind a back row, the way
        // Android's own overflow submenus and Google's apps open them, instead of iOS's inline
        // section with a chevron.
        if (exportOpen) {
            DropdownMenuItem(
                text = { Text("Export", style = MaterialTheme.typography.titleSmall) },
                leadingIcon = { OpenCodeIcon(SFSymbol.ChevronBackward, contentDescription = "Back", size = OpenCodeIconSize.small) },
                onClick = { exportOpen = false },
                contentPadding = MenuDefaults.DropdownMenuItemContentPadding,
            )
            HorizontalDivider()
            MenuItem("Markdown", SFSymbol.DocPlaintext) { menu(SessionSheetFeature.Action.ExportTapped(OpenCodeExportFormat.Markdown, title)) }
            MenuItem("JSON", SFSymbol.DocText) { menu(SessionSheetFeature.Action.ExportTapped(OpenCodeExportFormat.Json, title)) }
            return@DropdownMenu
        }
        listContext.isPinned?.let { isPinned ->
            MenuItem(if (isPinned) "Unpin" else "Pin", if (isPinned) SFSymbol.PinSlash else SFSymbol.Pin) {
                menu(SessionSheetFeature.Action.PinTapped)
            }
        }
        MenuItem("Rename", SFSymbol.Pencil) { menu(SessionSheetFeature.Action.RenameTapped(currentTitle = renameTitle)) }
        MenuItem("Copy Session ID", SFSymbol.SquareOnSquare) { menu(SessionSheetFeature.Action.CopySessionIDTapped) }
        if (listContext.isPinned != null) {
            MenuItem(
                if (listContext.isUnread) "Mark as Read" else "Mark as Unread",
                if (listContext.isUnread) SFSymbol.EnvelopeOpen else SFSymbol.EnvelopeBadge,
            ) { menu(SessionSheetFeature.Action.ReadTapped) }
            MenuItem(if (listContext.isMuted) "Unmute" else "Mute", if (listContext.isMuted) SFSymbol.Bell else SFSymbol.BellSlash) {
                menu(SessionSheetFeature.Action.MuteTapped)
            }
        }
        HorizontalDivider()
        MenuItem("Compact", SFSymbol.RectangleCompressVertical) { menu(SessionSheetFeature.Action.CompactTapped) }
        MenuItem("Fork", SFSymbol.ArrowTriangleBranch) { menu(SessionSheetFeature.Action.ForkTapped) }
        DropdownMenuItem(
            text = { Text("Export") },
            leadingIcon = { OpenCodeIcon(SFSymbol.SquareAndArrowUp, contentDescription = null, size = OpenCodeIconSize.small) },
            trailingIcon = { OpenCodeIcon(SFSymbol.ArrowtriangleRightFill, contentDescription = null, size = OpenCodeIconSize.medium) },
            onClick = { exportOpen = true },
            contentPadding = MenuDefaults.DropdownMenuItemContentPadding,
        )
        MenuItem("Session Info", SFSymbol.InfoCircle) { send(Action.SessionInfoTapped) }
        if (developerMode) {
            MenuItem("Report a Problem", SFSymbol.ExclamationmarkBubble, Modifier.testTag("session-menu-report-problem")) {
                send(Action.ReportProblemTapped)
            }
        }
        if (listContext.isPinned != null) {
            HorizontalDivider()
            val red = OpenCodeTheme.colors.destructive
            DropdownMenuItem(
                text = { Text("Delete Session", color = red) },
                leadingIcon = { OpenCodeIcon(SFSymbol.Trash, contentDescription = null, size = OpenCodeIconSize.small, tint = red) },
                onClick = { menu(SessionSheetFeature.Action.DeleteTapped) },
            )
        }
    }
}

@Composable
private fun MenuItem(label: String, symbol: SFSymbol, modifier: Modifier = Modifier, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { OpenCodeIcon(symbol, contentDescription = null, size = OpenCodeIconSize.small) },
        onClick = onClick,
        modifier = modifier,
        contentPadding = MenuDefaults.DropdownMenuItemContentPadding,
    )
}

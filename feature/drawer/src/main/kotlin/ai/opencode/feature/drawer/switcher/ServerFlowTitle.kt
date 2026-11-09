package ai.opencode.feature.drawer.switcher

import ai.opencode.core.motion.transitionBlur

import ai.opencode.core.motion.AmbientTimeline
import ai.opencode.core.motion.MotionRole
import ai.opencode.core.experiments.key
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.observe
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.feature.drawer.HomeConnectionFeature
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.drawer.ServerMenuStatus
import ai.opencode.feature.drawer.ServerReachability
import ai.opencode.feature.drawer.ServerSwapTarget
import ai.opencode.feature.drawer.motion.ServerSwapMotion
import ai.opencode.feature.drawer.style.DrawerTunables
import ai.opencode.feature.session.ServerFlowTunables
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.ServerSharedKeys
import ai.opencode.server.friendlyName
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Port of iOS `ServerFlowTitle` (Server Flow, d8a311bd): the drawer's title with Server Flow on.
 * The server picked shows at once. Its name rolls in the way the menu goes ([ServerSwapMotion]),
 * the title's width springs to it, and an underline shimmers under it until the server answers,
 * then fills and fades. The menu checks the server shown and gives every server it knows about a
 * status glyph and word ([ServerMenuStatus]).
 *
 * @param servers in the menu's order ([drawerTitleServers]).
 */
@Composable
fun ServerFlowTitle(
    current: OpenCodeServer,
    servers: List<OpenCodeServer>,
    status: HomeConnectionFeature.State.ConnectionStatus,
    motion: ServerSwapMotion,
    onSwitch: (OpenCodeServer) -> Unit,
    onRetry: () -> Unit,
    onEdit: () -> Unit,
    onManage: () -> Unit,
    modifier: Modifier = Modifier,
    credentialsRejected: Boolean = false,
    switchingTo: OpenCodeServer? = null,
    reachability: Map<String, ServerReachability> = emptyMap(),
) {
    val shown = switchingTo ?: current
    val target = ServerSwapTarget(current, switchingTo, servers)
    // A switch speaks for itself: the server it left has nothing to offer.
    val presentation = DrawerTitle.Presentation(
        serverCount = servers.size,
        status = if (switchingTo == null) status else HomeConnectionFeature.State.ConnectionStatus.connected,
        credentialsRejected = credentialsRejected,
    )
    val previous = remember { mutableStateOf<ServerSwapTarget?>(null) }
    val reducedMotion = rememberReduceMotion()
    SideEffect {
        // iOS `.onChange(of: target, initial: true) { old, new in motion.sync(new, animated: old != new, reduceMotion:) }`.
        motion.sync(target, animated = previous.value != null && previous.value != target, reduceMotion = reducedMotion)
        previous.value = target
    }
    val accessibilityValue = if (switchingTo == null) presentation.accessibilityValue else "Connecting"
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val available = maxWidth
        if (presentation.hasMenu) {
            var expanded by rememberSaveable { mutableStateOf(false) }
            Box {
                Label(
                    shown, presentation, motion, chevron = true, available = available,
                    modifier = Modifier
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.DropdownList,
                            onClickLabel = presentation.accessibilityHint,
                        ) { expanded = true }
                        .clearAndSetSemantics {
                            contentDescription = shown.friendlyName
                            stateDescription = accessibilityValue
                            heading()
                            testTag = DrawerTitle.TAG
                        },
                )
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    if (presentation.listsServers) {
                        for (server in servers) {
                            val isShown = server.persistenceID == shown.persistenceID
                            val serverStatus = ServerMenuStatus.of(server, current, status, credentialsRejected, switchingTo, reachability)
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(server.friendlyName, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                                        if (serverStatus != null) {
                                            Text(serverStatus.title, style = OpenCodeTextStyles.footnote, color = OpenCodeTheme.colors.secondaryText)
                                        }
                                    }
                                },
                                leadingIcon = {
                                    // A menu toggle draws the checkmark (iOS `Toggle` in a `Menu`).
                                    if (isShown) OpenCodeIcon(SFSymbol.Checkmark, contentDescription = null) else Spacer(Modifier.size(24.dp))
                                },
                                trailingIcon = serverStatus?.let { { OpenCodeIcon(it.systemImage, contentDescription = null) } },
                                onClick = {
                                    expanded = false
                                    if (!isShown) onSwitch(server)
                                },
                                // Picking a server switches to it, so it reads as chosen, not as a toggle.
                                modifier = Modifier.semantics { if (isShown) stateDescription = "Selected" },
                            )
                        }
                        HorizontalDivider()
                    }
                    if (presentation.offersTryAgain) {
                        DropdownMenuItem(
                            text = { Text(DrawerTitle.TRY_AGAIN) },
                            leadingIcon = { OpenCodeIcon(SFSymbol.ArrowClockwise, contentDescription = null) },
                            onClick = {
                                expanded = false
                                onRetry()
                            },
                        )
                    }
                    if (presentation.offersEdit) {
                        DropdownMenuItem(
                            text = { Text(DrawerTitle.EDIT_SERVER) },
                            leadingIcon = { OpenCodeIcon(SFSymbol.Key, contentDescription = null) },
                            onClick = {
                                expanded = false
                                onEdit()
                            },
                        )
                    }
                    if (presentation.offersTryAgain || presentation.offersEdit) HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(DrawerTitle.MANAGE_SERVERS) },
                        leadingIcon = { OpenCodeIcon(SFSymbol.Gearshape, contentDescription = null) },
                        onClick = {
                            expanded = false
                            onManage()
                        },
                    )
                }
            }
        } else {
            Label(
                shown, presentation, motion, chevron = false, available = available,
                modifier = Modifier
                    .clearAndSetSemantics {
                        contentDescription = shown.friendlyName
                        stateDescription = accessibilityValue
                        heading()
                    }
                    .testTag(DrawerTitle.TAG),
            )
        }
    }
}

/**
 * The Server Flow title wired to Home as iOS `HomeView.drawerHeader` wires it, sharing [motion]
 * with the list.
 */
@Composable
fun ServerFlowTitle(
    store: StoreOf<HomeFeature.State, HomeFeature.Action>,
    motion: ServerSwapMotion,
    onManage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current by store.observe { it.server }
    val status by store.observe { it.connection.displayedStatus }
    val switchingTo by store.observe { it.switchingTo }
    val credentialsRejected by store.observe { it.credentialsRejected }
    val reachability by store.observe { it.serverReachability }
    val saved by remember { shared(ServerSharedKeys.savedServers).flow }.collectAsState()
    ServerFlowTitle(
        current = current,
        servers = drawerTitleServers(current, saved),
        status = status,
        motion = motion,
        onSwitch = { store.send(HomeFeature.Action.Delegate(HomeFeature.DelegateAction.ServerSwitchTapped(it))) },
        onRetry = { store.send(HomeFeature.Action.RetryConnectionTapped) },
        onEdit = { store.send(HomeFeature.Action.Delegate(HomeFeature.DelegateAction.EditServerRequested(current))) },
        onManage = onManage,
        modifier = modifier,
        credentialsRejected = credentialsRejected,
        switchingTo = switchingTo,
        reachability = reachability,
    )
}

private val chevronSize = 18.dp
private val chevronGap = 6.dp

/** The chevron and its gap (iOS measures it, as it grows with Dynamic Type; Android's is a fixed 18 dp). */
private val chevronWidth = chevronSize + chevronGap

@Composable
private fun Label(
    shown: OpenCodeServer,
    presentation: DrawerTitle.Presentation,
    motion: ServerSwapMotion,
    chevron: Boolean,
    available: Dp,
    modifier: Modifier = Modifier,
) {
    val colors = OpenCodeTheme.colors
    val limit = maxOf(available - if (chevron) chevronWidth else 0.dp, 40.dp)
    Row(
        modifier.heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(chevronGap),
    ) {
        Names(shown, presentation, motion, limit)
        if (chevron) {
            val chevronColor by animateColorAsState(
                if (presentation.isDimmed && !colors.highContrast) colors.tertiaryText else colors.secondaryText,
                MotionRole.Swap.animation(reduceMotion = rememberReduceMotion()),
                label = "server-flow-chevron",
            )
            OpenCodeIcon(SFSymbol.ChevronDown, contentDescription = null, size = chevronSize, tint = chevronColor)
        }
    }
}

@Composable
private fun Names(shown: OpenCodeServer, presentation: DrawerTitle.Presentation, motion: ServerSwapMotion, limit: Dp) {
    val reducedMotion = rememberReduceMotion()
    val density = LocalDensity.current
    val travel = if (reducedMotion) 0f else ServerFlowTunables.titleTravel
    val blur = if (reducedMotion) 0f else ServerFlowTunables.blur
    val width = motion.width?.let { with(density) { it.toDp() } }?.let { minOf(it, limit) }
    Box(
        Modifier
            .then(if (width != null) Modifier.width(width) else Modifier.widthIn(max = limit))
            // Names lay out at full width and never truncate as the box springs; a longer name
            // leaving fades out past its edge.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val edge = size.width * 1.1f + 4.dp.toPx()
                drawRect(
                    Brush.horizontalGradient(0f to Color.Black, 0.9f to Color.Black, 1f to Color.Transparent, startX = 0f, endX = edge),
                    blendMode = BlendMode.DstIn,
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        // The shown name's width, truncated to the room, which the title springs to, so the
        // chevron sits right after a truncated name (iOS ffbc8f9f).
        val id = shown.persistenceID
        Name(
            shown.friendlyName, presentation, limit = limit,
            modifier = Modifier
                .wrapContentWidth(Alignment.Start, unbounded = true)
                .onSizeChanged { motion.measured(id, it.width.toFloat()) }
                .alpha(0f)
                .clearAndSetSemantics {},
        )
        val names = motion.names
        for (pose in names) {
            Name(
                pose.name, presentation, limit = limit,
                modifier = Modifier
                    .wrapContentWidth(Alignment.Start, unbounded = true)
                    .offset(y = (pose.position * travel).dp)
                    .alpha(pose.opacity)
                    .then(if (blur > 0f) Modifier.transitionBlur((abs(pose.position) * blur).dp) else Modifier),
            )
        }
        if (names.isEmpty()) Name(shown.friendlyName, presentation, limit = limit)
        ServerFlowUnderline(
            presence = motion.underline,
            fill = motion.underlineFill,
            reducedMotion = reducedMotion,
            modifier = Modifier.align(Alignment.BottomStart).offset(y = 2.dp),
        )
    }
}

@Composable
private fun Name(text: String, presentation: DrawerTitle.Presentation, limit: Dp, modifier: Modifier = Modifier) {
    val colors = OpenCodeTheme.colors
    val color by animateColorAsState(
        if (presentation.isDimmed) colors.secondaryText else colors.text,
        MotionRole.Swap.animation(reduceMotion = rememberReduceMotion()),
        label = "server-flow-name",
    )
    Text(
        text,
        modifier = modifier.widthIn(max = limit),
        style = DrawerTunables.titleFont.font.copy(fontWeight = FontWeight.Bold),
        color = color,
        maxLines = 1,
        overflow = TextOverflow.MiddleEllipsis,
    )
}

/**
 * iOS `ServerFlowUnderline`: the line under the name while a server connects. A sweep runs along it
 * once per `AmbientClock.sweepPeriod` (in step with every other sweep; eased by the iOS bezier
 * 0.4/0/0.2/1, which is `FastOutSlowInEasing`), and it fills when the server answers. Reduce Motion
 * holds the sweep. Hidden from TalkBack.
 */
@Composable
fun ServerFlowUnderline(presence: Float, fill: Float, reducedMotion: Boolean, modifier: Modifier = Modifier) {
    if (presence <= 0.001f) return
    val colors = OpenCodeTheme.colors
    val track = colors.tertiaryText.copy(alpha = colors.tertiaryText.alpha * 0.5f)
    val ink = colors.secondaryText
    AmbientTimeline(isActive = fill < 1f, reduceMotion = reducedMotion) { sample ->
        val phase = FastOutSlowInEasing.transform(sample.sweep.toFloat())
        Canvas(
            modifier
                .fillMaxWidth()
                .height(2.dp)
                .alpha(presence)
                .clipToBounds()
                .clearAndSetSemantics {},
        ) {
            val radius = CornerRadius(size.height / 2, size.height / 2)
            drawRoundRect(track, cornerRadius = radius)
            if (!reducedMotion && !sample.isSettled) {
                val segment = size.width * 0.32f
                drawRoundRect(ink, topLeft = Offset(segment * (-1 + 4.2f * phase), 0f), size = Size(segment, size.height), cornerRadius = radius)
            }
            drawRoundRect(ink, size = Size(size.width * fill.coerceAtLeast(0.0001f), size.height), cornerRadius = radius)
        }
    }
}

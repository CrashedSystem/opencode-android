@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package ai.opencode.app

import ai.opencode.core.diagnostics.HidesFromScreenshots
import ai.opencode.core.experiments.key
import ai.opencode.core.motion.MotionRole
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.sharing.ExperimentalFeature
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.Store
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.observe
import ai.opencode.core.store.rememberScope
import ai.opencode.core.ui.OpenCodeAppTheme
import ai.opencode.core.ui.OpenCodeButtonDefaults
import ai.opencode.core.ui.OpenCodeButtonSize
import ai.opencode.core.ui.OpenCodeFonts
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeIconButton
import ai.opencode.core.ui.OpenCodeIconButtonStyle
import ai.opencode.core.ui.OpenCodePrimaryButton
import ai.opencode.core.ui.OpenCodeSecondaryButton
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.ThisDevice
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.drawer.HomeView
import ai.opencode.feature.media.MediaFeature
import ai.opencode.feature.media.MediaView
import ai.opencode.feature.settings.onboarding.WelcomeFeature
import ai.opencode.feature.settings.onboarding.WelcomeView
import ai.opencode.feature.settings.servers.PairingScannerView
import ai.opencode.feature.system.clienttools.ClientToolsFeature
import ai.opencode.feature.transcript.TranscriptMediaProviders
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.clienttools.ClientTool
import ai.opencode.server.friendlyName
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

/**
 * The phone's client tools (iOS `AppView.clientTools`): links and audio always, the screen while
 * its experiment is on.
 */
private fun clientTools(shareScreen: Boolean): Set<ClientTool> {
    val tools = mutableSetOf(ClientTool.voiceOpenURL, ClientTool.voicePlayAudio, ClientTool.voiceAudioControl)
    if (ExperimentalFeature.shareScreenWithAgents.isActive(shareScreen)) tools.add(ClientTool.voiceRequestScreen)
    return tools
}

/**
 * Port of iOS `AppView` (`OpenCode/App/AppView.swift`): Home when a server is connected, else
 * the connection screen; over either the New Server / Edit Server sheet, the pairing scanner,
 * the pairing confirmation, the error alerts and the server switch toast.
 */
@Composable
fun AppView(store: StoreOf<AppFeature.State, AppFeature.Action>) {
    LaunchedEffect(store) {
        store.send(AppFeature.Action.Task)
        // The system action inbox is started by `AppWideWork`, in the window that owns it.
        store.send(AppFeature.Action.PendingSharesRequested)
    }
    val hasHome by store.observe { it.home != null }
    val isEditingServer by store.observe { it.isEditingServer }
    val isScanning by store.observe { it.isScanningPairingCode }
    val hasSavedServers by store.observe { it.savedServers.value.isNotEmpty() }
    val welcomePreview by remember { shared(ExperimentalFeature.welcomePreview.key).flow }.collectAsState()
    val showsWelcome = !hasSavedServers && ExperimentalFeature.welcomePreview.isActive(welcomePreview)
    val shareScreenWithAgents by remember { shared(ExperimentalFeature.shareScreenWithAgents.key).flow }.collectAsState()
    val clientTools = clientTools(shareScreenWithAgents)
    LaunchedEffect(store, clientTools) {
        store.send(AppFeature.Action.ClientTools(ClientToolsFeature.Action.ToolsChanged(clientTools)))
    }
    OpenCodeAppTheme {
        // Media's file viewer renders Markdown files with the transcript's renderer.
        TranscriptMediaProviders {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (hasHome) {
                val home = store.rememberScope<AppFeature.State, AppFeature.Action, HomeFeature.State, HomeFeature.Action>(
                    state = { it.home ?: HomeFeature.State.placeholder(OpenCodeServer(url = "", username = "", password = "")) },
                    action = AppFeature.Action::Home,
                )
                // The app's one media root (iOS `MediaPresenter` presents over the window): the
                // transcript image viewer and file/diagram/pasted-text viewers cover the whole Home.
                val media = remember { Store(MediaFeature.State(), MediaFeature()) }
                MediaView(media) { HomeView(home, welcomeMark = painterResource(R.drawable.opencode_app_mark)) }
            } else if (showsWelcome) {
                val welcome = store.rememberScope<AppFeature.State, AppFeature.Action, WelcomeFeature.State, WelcomeFeature.Action>(
                    state = { it.welcome },
                    action = AppFeature.Action::Welcome,
                )
                val progress by store.observe { it.connectionProgressLabel }
                val problem by store.observe { it.errorMessage }
                WelcomeView(welcome, progress, problem, mark = painterResource(R.drawable.opencode_app_mark))
            } else {
                ConnectionView(store)
            }
            // Over Home too: the drawer adds and edits servers without leaving it.
            if (isEditingServer) ServerFormSheet(store)
            if (isScanning) PairingScannerPresenter(store)
            AppAlerts(store)
        }
        }
    }
}

@Composable
private fun AppAlerts(store: StoreOf<AppFeature.State, AppFeature.Action>) {
    val shareError by store.observe { it.shareError }
    val notificationError by store.observe { it.notificationError }
    val systemActionError by store.observe { it.systemActionError }
    val pairingError by store.observe { it.pairingError }
    val pendingPairing by store.observe { it.pendingPairing }
    shareError?.let { ErrorAlert("Couldn’t Add Shared Items", it) { store.send(AppFeature.Action.ShareErrorDismissed) } }
    notificationError?.let { ErrorAlert("Couldn’t Open Session", it) { store.send(AppFeature.Action.NotificationErrorDismissed) } }
    systemActionError?.let { ErrorAlert("Couldn’t Open Session", it) { store.send(AppFeature.Action.SystemActionErrorDismissed) } }
    pairingError?.let { ErrorAlert("Couldn’t Pair with Server", it) { store.send(AppFeature.Action.PairingErrorDismissed) } }
    pendingPairing?.let { pending ->
        AlertDialog(
            onDismissRequest = { store.send(AppFeature.Action.PairingCancelled(pending.id)) },
            title = { Text(pending.title) },
            text = { Text(pending.message) },
            confirmButton = { TextButton(onClick = { store.send(AppFeature.Action.PairingConfirmed(pending.id)) }) { Text("Pair") } },
            dismissButton = { TextButton(onClick = { store.send(AppFeature.Action.PairingCancelled(pending.id)) }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ErrorAlert(title: String, message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

/** iOS `dynamicTypeSize.isAccessibilitySize`: the largest font scales (1.8× and 2×). */
@Composable
private fun isAccessibilitySize(): Boolean = LocalDensity.current.fontScale > 1.5f

/** iOS `.orange` (systemOrange) for the error card title and paste errors. */
@Composable
private fun systemOrange(): Color = if (OpenCodeTheme.colors.isDark) Color(0xFFFF9F0A) else Color(0xFFFF9500)

/** iOS `connectionView`: the saved servers, pairing and the manual form. */
@Composable
private fun ConnectionView(store: StoreOf<AppFeature.State, AppFeature.Action>) {
    val savedServers by store.observe { it.savedServers.value }
    val errorMessage by store.observe { it.errorMessage }
    val returnServer by store.observe { it.returnServer }
    val progress by store.observe { it.connectionProgressLabel }
    val accessibilitySize = isAccessibilitySize()
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        if (savedServers.isNotEmpty()) {
            // The "Servers" bar: shown once there are servers, with Back to the one just left.
            Box(Modifier.fillMaxWidth().heightIn(min = 56.dp), contentAlignment = Alignment.Center) {
                Text("Servers", style = OpenCodeTextStyles.headline, modifier = Modifier.semantics { heading() })
                if (returnServer != null && progress == null) {
                    OpenCodeIconButton(
                        symbol = SFSymbol.ChevronBackward,
                        contentDescription = "Back to ${returnServer?.displayName ?: "server"}",
                        onClick = { store.send(AppFeature.Action.ReturnToServerTapped) },
                        style = OpenCodeIconButtonStyle.Standard,
                        modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp),
                    )
                }
            }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = COLUMN_WIDTH).fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.padding(top = if (savedServers.isEmpty()) 24.dp else 0.dp),
                ) {
                    Image(
                        painter = painterResource(R.drawable.opencode_wordmark),
                        contentDescription = "OpenCode",
                        modifier = Modifier.size(width = 200.dp, height = 36.dp),
                        alignment = Alignment.CenterStart,
                    )
                    Text(
                        if (savedServers.isEmpty()) "Keep your coding sessions moving from your ${ThisDevice.casualName}."
                        else "Pick up where you left off, or pair another computer.",
                        style = OpenCodeTextStyles.body,
                        color = OpenCodeTheme.colors.secondaryText,
                    )
                }
                errorMessage?.let { ConnectionErrorCard(store, it) }
                if (savedServers.isEmpty()) SetupSteps(store) else SavedServerList(store, savedServers)
                if (accessibilitySize) PairingActionsButtons(store)
            }
        }
        if (!accessibilitySize) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(Modifier.widthIn(max = COLUMN_WIDTH).padding(horizontal = 24.dp).padding(top = 12.dp, bottom = 16.dp)) {
                    PairingActionsButtons(store)
                }
            }
        }
    }
}

private val COLUMN_WIDTH = 520.dp

/**
 * The pairing actions as M3 medium (56 dp) buttons: Scan Pairing Code is the filled primary
 * CTA, Enter a Server Manually its tonal companion (iOS `pairingActionLabel` carries
 * prominence by fill alone, so the two stay the same size).
 */
@Composable
private fun PairingActionsButtons(store: StoreOf<AppFeature.State, AppFeature.Action>) {
    val progress by store.observe { it.connectionProgressLabel }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OpenCodeSecondaryButton(
            text = "Enter a Server Manually",
            onClick = { store.send(AppFeature.Action.AddServerTapped) },
            size = OpenCodeButtonSize.Medium,
            modifier = Modifier.fillMaxWidth(),
        )
        val scheme = MaterialTheme.colorScheme
        val height = OpenCodeButtonDefaults.heroHeight
        Button(
            onClick = { store.send(AppFeature.Action.ScanPairingCodeTapped) },
            shapes = ButtonDefaults.shapesFor(height),
            enabled = progress == null,
            colors = ButtonDefaults.buttonColors(
                // The progress state keeps its fill: the label says what's happening.
                disabledContainerColor = scheme.primary,
                disabledContentColor = scheme.onPrimary,
            ),
            contentPadding = OpenCodeButtonDefaults.heroContentPadding,
            modifier = Modifier.fillMaxWidth().heightIn(min = height),
        ) {
            ProvideTextStyle(OpenCodeButtonDefaults.textStyle(OpenCodeButtonSize.Medium)) {
                if (progress != null) {
                    CircularProgressIndicator(Modifier.size(ButtonDefaults.MediumIconSize), strokeWidth = 2.dp, color = scheme.onPrimary)
                    Spacer(Modifier.width(ButtonDefaults.MediumIconSpacing))
                    Text(progress!!, maxLines = 1)
                } else {
                    OpenCodeIcon(SFSymbol.QrcodeViewfinder, contentDescription = null, size = ButtonDefaults.MediumIconSize)
                    Spacer(Modifier.width(ButtonDefaults.MediumIconSpacing))
                    Text("Scan Pairing Code", maxLines = 1)
                }
            }
        }
    }
}

/** What a new user needs on their computer before the scan can work. */
@Composable
private fun SetupSteps(store: StoreOf<AppFeature.State, AppFeature.Action>) {
    val linkColor = OpenCodeTheme.colors.secondaryText
    Column {
        Text(
            "On your computer, open OpenCode and go to Settings → Pairing, or type /pair in OpenCode in your terminal. Then scan the code with this ${ThisDevice.casualName}.",
            style = OpenCodeTextStyles.body,
            modifier = Modifier.padding(vertical = 16.dp),
        )
        HorizontalDivider()
        SetupSection("Don’t have OpenCode yet?") {
            CopyCommand("curl -fsSL https://opencode.ai/v2/install | bash") { store.send(AppFeature.Action.CopyTextTapped(it)) }
            Text(
                buildAnnotatedString {
                    withLink(LinkAnnotation.Url("https://opencode.ai/download", TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline)))) {
                        append("Other ways to install")
                    }
                },
                style = OpenCodeTextStyles.footnote,
                color = linkColor,
                modifier = Modifier.heightIn(min = 48.dp).padding(vertical = 14.dp),
            )
        }
        HorizontalDivider()
        SetupSection("Or run this in a terminal") {
            CopyCommand("opencode pair --remote") { store.send(AppFeature.Action.CopyTextTapped(it)) }
            Text("It works from any network.", style = OpenCodeTextStyles.footnote, color = OpenCodeTheme.colors.secondaryText)
            PasteRow(store)
        }
    }
}

@Composable
private fun SetupSection(title: String, detail: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = OpenCodeTextStyles.headline, modifier = Modifier.semantics { heading() })
        detail()
    }
}

/** Paste for a copied pairing link; a failed paste says why in place of the prompt. */
@Composable
private fun PasteRow(store: StoreOf<AppFeature.State, AppFeature.Action>) {
    val pasteError by store.observe { it.pasteError }
    val progress by store.observe { it.connectionProgressLabel }
    val prompt: @Composable (Modifier) -> Unit = { modifier ->
        Text(
            pasteError ?: "Or copy the link it prints, then",
            style = OpenCodeTextStyles.footnote,
            color = if (pasteError != null) systemOrange() else OpenCodeTheme.colors.secondaryText,
            modifier = modifier,
        )
    }
    val button: @Composable () -> Unit = {
        OpenCodeSecondaryButton(onClick = { store.send(AppFeature.Action.PastePairingCodeTapped) }, enabled = progress == null) {
            OpenCodeIcon(SFSymbol.DocOnClipboard, contentDescription = null, size = ButtonDefaults.IconSize)
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text("Paste", maxLines = 1)
        }
    }
    if (isAccessibilitySize()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            prompt(Modifier)
            button()
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            prompt(Modifier.weight(1f, fill = false))
            button()
        }
    }
}

/** A terminal command shown in the setup steps; tapping copies it (iOS `CopyCommandButton`). */
@Composable
private fun CopyCommand(command: String, onCopy: (String) -> Unit) {
    var copied by remember { mutableStateOf(false) }
    val reduceMotion = rememberReduceMotion()
    LaunchedEffect(copied) {
        if (!copied) return@LaunchedEffect
        // The Copied checkmark's dwell.
        delay(1_500)
        copied = false
    }
    Surface(
        onClick = {
            onCopy(command)
            copied = true
        },
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.heightIn(min = 48.dp).semantics {
            contentDescription = "Copy $command"
            stateDescription = if (copied) "Copied" else ""
        },
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(command, modifier = Modifier.weight(1f, fill = false), style = OpenCodeTextStyles.callout.copy(fontFamily = OpenCodeFonts.berkeleyMono, fontSize = OpenCodeTextStyles.subheadline.fontSize))
            // The copied checkmark swaps in and back on the swap role (a cross-fade under reduce motion).
            Crossfade(copied, animationSpec = MotionRole.Swap.animation(reduceMotion = reduceMotion), label = "copy-command") { isCopied ->
                OpenCodeIcon(
                    if (isCopied) SFSymbol.Checkmark else SFSymbol.SquareOnSquare,
                    contentDescription = null,
                    tint = OpenCodeTheme.colors.secondaryText,
                    size = 16.dp,
                )
            }
        }
    }
}

@Composable
private fun SavedServerList(store: StoreOf<AppFeature.State, AppFeature.Action>, servers: List<OpenCodeServer>) {
    val connectingID by store.observe { it.connectingServerID }
    val progress by store.observe { it.connectionProgressLabel }
    val lastUsedID by store.observe { it.savedServer.value?.persistenceID }
    var pendingRemoval by remember { mutableStateOf<OpenCodeServer?>(null) }
    val accessibilitySize = isAccessibilitySize()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        servers.forEach { server ->
            androidx.compose.runtime.key(server.persistenceID) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = OpenCodeTheme.colors.text,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            Modifier.weight(1f)
                                .clickable { store.send(AppFeature.Action.ServerTapped(server)) }
                                .heightIn(min = 72.dp)
                                .padding(vertical = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OpenCodeIcon(
                                if (server.isSecure) SFSymbol.LockFill else SFSymbol.ServerRack,
                                contentDescription = null,
                                tint = OpenCodeTheme.colors.secondaryText,
                                size = 20.dp,
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    server.displayName,
                                    style = OpenCodeTextStyles.headline,
                                    maxLines = if (accessibilitySize) 2 else 1,
                                    overflow = TextOverflow.MiddleEllipsis,
                                )
                                val detail = if (connectingID == server.id && progress != null) progress
                                else serverRowDetail(server, lastUsedID)
                                detail?.let {
                                    Text(
                                        it,
                                        style = OpenCodeTextStyles.footnote,
                                        color = OpenCodeTheme.colors.secondaryText,
                                        maxLines = if (accessibilitySize) 2 else 1,
                                        overflow = TextOverflow.MiddleEllipsis,
                                    )
                                }
                            }
                        }
                        if (connectingID == server.id) {
                            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(
                                    Modifier.size(20.dp).semantics { contentDescription = progress ?: "Connecting" },
                                    strokeWidth = 2.dp,
                                )
                            }
                        } else {
                            OpenCodeIconButton(
                                symbol = SFSymbol.Pencil,
                                contentDescription = "Edit ${server.displayName}",
                                onClick = { store.send(AppFeature.Action.EditServerTapped(server)) },
                                style = OpenCodeIconButtonStyle.Standard,
                            )
                            OpenCodeIconButton(
                                symbol = SFSymbol.Trash,
                                contentDescription = "Remove ${server.displayName}",
                                onClick = { pendingRemoval = server },
                                style = OpenCodeIconButtonStyle.Standard,
                            )
                        }
                    }
                }
            }
        }
    }
    pendingRemoval?.let { server ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text("Remove ${server.displayName}?") },
            text = { Text("This forgets the address and password on this ${ThisDevice.name}. Sessions on the server are not affected.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRemoval = null
                        store.send(AppFeature.Action.ServerDeleted(server))
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = OpenCodeTheme.colors.destructive),
                ) { Text("Remove Server") }
            },
            dismissButton = { TextButton(onClick = { pendingRemoval = null }) { Text("Cancel") } },
        )
    }
}

/** The address when the name doesn't say it, a username other than the default, and whether it's the last used. */
private fun serverRowDetail(server: OpenCodeServer, lastUsedID: String?): String? {
    val parts = mutableListOf<String>()
    if (server.displayName != server.host) parts += server.host
    if (server.username != "opencode") parts += server.username
    if (lastUsedID == server.persistenceID) parts += "last used"
    return if (parts.isEmpty()) null else parts.joinToString(" · ")
}

/** Local messages format commands as `code`; render that instead of backticks. */
private fun formatted(message: String): AnnotatedString = buildAnnotatedString {
    val parts = message.split('`')
    // An unmatched backtick stays literal.
    if (parts.size % 2 == 0) {
        append(message)
        return@buildAnnotatedString
    }
    parts.forEachIndexed { index, part ->
        if (index % 2 == 1) withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(part) } else append(part)
    }
}

@Composable
private fun ConnectionErrorCard(store: StoreOf<AppFeature.State, AppFeature.Action>, message: String) {
    val phase by store.observe { it.errorPhase }
    val context by store.observe { it.errorContext }
    var didCopy by remember(message) { mutableStateOf(false) }
    val title = when (phase) {
        AppFeature.FailureContext.Phase.Pair -> "Couldn’t Pair"
        AppFeature.FailureContext.Phase.LoadSessions -> "Couldn’t Load Sessions"
        else -> "Couldn’t connect to ${context?.friendlyName ?: "server"}"
    }
    val secondary = OpenCodeTheme.colors.secondaryText
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = OpenCodeTheme.colors.text,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.semantics(mergeDescendants = true) { heading() },
            ) {
                OpenCodeIcon(SFSymbol.ExclamationmarkTriangleFill, contentDescription = null, tint = systemOrange(), size = 20.dp)
                Text(title, style = OpenCodeTextStyles.headline, color = systemOrange())
            }
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(formatted(message), style = OpenCodeTextStyles.subheadline)
                    context?.takeIf { it.details.isNotEmpty() }?.let { Text(it.detailText, style = OpenCodeTextStyles.subheadline) }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        context?.let {
                            Text("Phase: ${it.phase.rawValue}", style = OpenCodeTextStyles.footnote, color = secondary)
                            Text("Server: ${it.serverLabel}", style = OpenCodeTextStyles.footnote, color = secondary)
                            Text("Attempt: ${it.attempt}", style = OpenCodeTextStyles.footnote, color = secondary)
                        }
                        Text("OpenCode ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = OpenCodeTextStyles.footnote, color = secondary)
                    }
                }
            }
            OpenCodeSecondaryButton(onClick = {
                store.state.errorReport(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toString(), java.time.Instant.now())?.let {
                    store.send(AppFeature.Action.CopyTextTapped(it))
                    didCopy = true
                }
            }) {
                OpenCodeIcon(if (didCopy) SFSymbol.Checkmark else SFSymbol.SquareOnSquare, contentDescription = null, size = ButtonDefaults.IconSize)
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(if (didCopy) "Copied" else "Copy Error Report")
            }
        }
    }
}

/** iOS `credentialForm` in its "New Server" / "Edit Server" sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerFormSheet(store: StoreOf<AppFeature.State, AppFeature.Action>) {
    val state by store.observe { it }
    var revealsPassword by rememberSaveable { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val isConnectDisabled = state.connectionProgressLabel != null || state.serverURL.isBlank() || state.headerProblem != null
    val attempt = state.errorContext?.attempt
    val reduceMotion = rememberReduceMotion()
    // Back to the top on the layout role, so a failed attempt's error is seen.
    LaunchedEffect(attempt) { if (attempt != null) scroll.animateScrollTo(0, MotionRole.Layout.animation(reduceMotion = reduceMotion)) }
    ModalBottomSheet(
        onDismissRequest = { store.send(AppFeature.Action.CancelEditingTapped) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        // Its password and headers, out of problem reports and voice screenshots.
        HidesFromScreenshots()
        // M3 sheet header: close at the start, the title beside it; the filled Connect button
        // below the fields is the action (iOS: a Cancel text button and a centred title).
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OpenCodeIconButton(
                symbol = SFSymbol.Xmark,
                contentDescription = "Cancel",
                onClick = { store.send(AppFeature.Action.CancelEditingTapped) },
                style = OpenCodeIconButtonStyle.Standard,
            )
            Text(
                if (state.editingSavedServer == null) "New Server" else "Edit Server",
                style = MaterialTheme.typography.titleLarge,
                color = OpenCodeTheme.colors.text,
                modifier = Modifier.semantics { heading() },
            )
        }
        Column(
            Modifier.fillMaxWidth().verticalScroll(scroll).imePadding().navigationBarsPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            state.errorMessage?.let { ConnectionErrorCard(store, it) }
            OutlinedTextField(
                value = state.serverName,
                onValueChange = { store.send(AppFeature.Action.ServerNameChanged(it)) },
                label = { Text("Name") },
                // Blank keeps the name the address gives, shown as the prompt.
                placeholder = { Text(state.credentialNamePrompt) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.serverURL,
                onValueChange = { store.send(AppFeature.Action.ServerURLChanged(it)) },
                label = { Text("Server address") },
                placeholder = { Text("http://192.168.1.20:4096") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.username,
                onValueChange = { store.send(AppFeature.Action.UsernameChanged(it)) },
                label = { Text("Username") },
                placeholder = { Text("opencode") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.password,
                onValueChange = { store.send(AppFeature.Action.PasswordChanged(it)) },
                label = { Text("Password") },
                placeholder = { Text("Password") },
                singleLine = true,
                visualTransformation = if (revealsPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (revealsPassword) KeyboardType.Text else KeyboardType.Password,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { if (!isConnectDisabled) store.send(AppFeature.Action.ConnectButtonTapped) }),
                trailingIcon = {
                    OpenCodeIconButton(
                        symbol = if (revealsPassword) SFSymbol.EyeSlash else SFSymbol.Eye,
                        contentDescription = if (revealsPassword) "Hide Password" else "Show Password",
                        onClick = { revealsPassword = !revealsPassword },
                        style = OpenCodeIconButtonStyle.Standard,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
            HeaderFields(store, state.headers, state.headerProblem)
            ConnectButton(
                label = state.connectionProgressLabel ?: if (state.editingSavedServer == null) "Connect" else "Save and connect",
                inProgress = state.connectionProgressLabel != null,
                enabled = !isConnectDisabled,
            ) { store.send(AppFeature.Action.ConnectButtonTapped) }
            Text(
                formatted(editHint(state.editingSavedServer)),
                style = OpenCodeTextStyles.footnote,
                color = OpenCodeTheme.colors.secondaryText,
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

private fun editHint(editing: OpenCodeServer?): String {
    if (editing == null) {
        return "Paste the link `opencode pair --remote` prints to pair, or use the address and password your OpenCode server runs with. The username is usually opencode."
    }
    return if (editing.usesPairingToken) {
        "Changes are saved once this server connects. This server was paired, so its password is a session token; scan a new pairing code instead of changing it."
    } else {
        "Changes are saved once this server connects."
    }
}

/**
 * The form's Connect action: an M3 filled medium button across the sheet, with a spinner while
 * connecting (iOS: a label-coloured capsule with a trailing arrow).
 */
@Composable
private fun ConnectButton(label: String, inProgress: Boolean, enabled: Boolean, onClick: () -> Unit) {
    OpenCodePrimaryButton(
        onClick = onClick,
        enabled = enabled,
        size = OpenCodeButtonSize.Medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (inProgress) {
            CircularProgressIndicator(Modifier.size(ButtonDefaults.MediumIconSize), strokeWidth = 2.dp, color = LocalContentColor.current)
            Spacer(Modifier.width(ButtonDefaults.MediumIconSpacing))
        }
        Text(label, maxLines = 1)
    }
}

/** Custom headers: rows of name and value, sent with every request. */
@Composable
private fun HeaderFields(
    store: StoreOf<AppFeature.State, AppFeature.Action>,
    headers: List<OpenCodeServer.Header>,
    headerProblem: String?,
) {
    val accessibilitySize = isAccessibilitySize()
    fun update(index: Int, transform: (OpenCodeServer.Header) -> OpenCodeServer.Header) {
        // A row edit that can't land on a row just removed.
        val current = store.state.headers
        if (index !in current.indices) return
        store.send(AppFeature.Action.HeadersChanged(current.toMutableList().also { it[index] = transform(it[index]) }))
    }
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("Headers", style = OpenCodeTextStyles.caption1.copy(fontWeight = FontWeight.SemiBold), color = OpenCodeTheme.colors.secondaryText)
        headers.forEachIndexed { index, header ->
            val nameField: @Composable (Modifier) -> Unit = { modifier ->
                OutlinedTextField(
                    value = header.name,
                    onValueChange = { value -> update(index) { it.copy(name = value) } },
                    placeholder = { Text("Name") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                    modifier = modifier.semantics { contentDescription = "Header ${index + 1} name" },
                )
            }
            val valueField: @Composable (Modifier) -> Unit = { modifier ->
                OutlinedTextField(
                    value = header.value,
                    onValueChange = { value -> update(index) { it.copy(value = value) } },
                    placeholder = { Text("Value") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                    modifier = modifier.semantics { contentDescription = "Header ${index + 1} value" },
                )
            }
            val removeButton: @Composable () -> Unit = {
                androidx.compose.material3.IconButton(
                    onClick = {
                        val current = store.state.headers
                        if (index in current.indices) store.send(AppFeature.Action.HeadersChanged(current.filterIndexed { i, _ -> i != index }))
                    },
                    modifier = Modifier.size(48.dp),
                ) {
                    OpenCodeIcon(SFSymbol.MinusCircleFill, contentDescription = "Remove Header ${index + 1}", tint = OpenCodeTheme.colors.destructive)
                }
            }
            if (accessibilitySize) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        nameField(Modifier.weight(1f))
                        removeButton()
                    }
                    valueField(Modifier.fillMaxWidth())
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    nameField(Modifier.weight(1f))
                    valueField(Modifier.weight(1f))
                    removeButton()
                }
            }
        }
        TextButton(
            onClick = { store.send(AppFeature.Action.HeadersChanged(store.state.headers + OpenCodeServer.Header("", ""))) },
            colors = ButtonDefaults.textButtonColors(contentColor = OpenCodeTheme.colors.secondaryText),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp),
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            OpenCodeIcon(SFSymbol.Plus, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Add Header", style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.Medium))
        }
        Text(
            headerProblem ?: "Sent with every request, for a proxy in front of the server such as Cloudflare Access.",
            style = OpenCodeTextStyles.footnote,
            color = if (headerProblem == null) OpenCodeTheme.colors.secondaryText else OpenCodeTheme.colors.destructive,
        )
    }
}

/**
 * `.sheet(isPresented: $store.isScanningPairingCode) { PairingScannerView(…) }`: settings'
 * CameraX + ZXing scanner, full screen, with its own no-camera / access-off / paste fallbacks.
 * Back dismisses it like Cancel.
 */
@Composable
private fun PairingScannerPresenter(store: StoreOf<AppFeature.State, AppFeature.Action>) {
    Dialog(
        onDismissRequest = { store.send(AppFeature.Action.ScannerDismissed) },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        HidesFromScreenshots()
        PairingScannerView(
            onScanned = { store.send(AppFeature.Action.PairingCodeScanned(it)) },
            onCancel = { store.send(AppFeature.Action.ScannerDismissed) },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

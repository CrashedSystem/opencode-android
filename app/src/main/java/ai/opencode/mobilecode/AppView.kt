@file:PortedFrom("OpenCode/AppView.swift", "553a407")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.AttributedString
import ai.opencode.mobilecode.system.FontFamilies
import ai.opencode.mobilecode.system.GlassSurface
import ai.opencode.mobilecode.system.NavigationBarHidden
import ai.opencode.mobilecode.system.NavigationStack
import ai.opencode.mobilecode.system.NavigationTitle
import ai.opencode.mobilecode.system.OpenCodeWordmark
import ai.opencode.mobilecode.system.SheetDetent
import ai.opencode.mobilecode.system.SystemButton
import ai.opencode.mobilecode.system.SystemColors
import ai.opencode.mobilecode.system.SystemConfirmation
import ai.opencode.mobilecode.system.SystemImage
import ai.opencode.mobilecode.system.TextStyles
import ai.opencode.mobilecode.system.TitleDisplayMode
import ai.opencode.mobilecode.system.Toolbar
import ai.opencode.mobilecode.system.ToolbarItem
import ai.opencode.mobilecode.system.ToolbarItemPlacement
import ai.opencode.mobilecode.system.medium
import ai.opencode.mobilecode.system.semibold
import ai.opencode.mobilecode.system.symbolSize
import ai.opencode.mobilecode.tca.Binding
import ai.opencode.mobilecode.tca.BindingAction
import ai.opencode.mobilecode.tca.Store
import ai.opencode.mobilecode.tca.binding
import ai.opencode.mobilecode.tca.alert
import ai.opencode.mobilecode.tca.sheet
import ai.opencode.mobilecode.tca.task
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun AppView(store: Store<AppFeature.State, AppFeature.Action>) {
    var didCopyErrorReport by remember { mutableStateOf(false) }
    val home = store.scope(AppFeature.State::home, AppFeature.Action.home)
    if (home != null) {
        HomeView(store = home)
    } else {
        ConnectionView(store, didCopyErrorReport) { didCopyErrorReport = it }
    }
    val shareError = store.state.shareError
    alert(
        "Couldn’t Add Shared Items",
        isPresented = Binding(
            getter = { store.state.shareError != null },
            setter = { presented ->
                if (!presented) {
                    store.send(AppFeature.Action.binding(BindingAction.set(AppFeature.State::shareError, null)))
                }
            },
        ),
        message = shareError,
    )
    store.task {
        store.send(AppFeature.Action.task)
        store.send(AppFeature.Action.pendingSharesRequested)
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val resumed = remember { mutableStateOf(false) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_START) return@LifecycleEventObserver
            if (resumed.value) store.send(AppFeature.Action.pendingSharesRequested)
            resumed.value = true
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val errorMessage = store.state.errorMessage
    DisposableEffect(errorMessage) {
        didCopyErrorReport = false
        onDispose {}
    }
}

@Composable
private fun ConnectionView(
    store: Store<AppFeature.State, AppFeature.Action>,
    didCopyErrorReport: Boolean,
    onCopyErrorReport: (Boolean) -> Unit,
) {
    var serverPendingRemoval by remember { mutableStateOf<OpenCodeServer?>(null) }
    val scanning = store.state.isScanningPairingCode
    val returnServer = store.state.returnServer
    val progress = store.state.connectionProgressLabel
    BackHandler(enabled = scanning) {
        store.send(AppFeature.Action.scannerDismissed)
    }
    BackHandler(enabled = !scanning && returnServer != null && progress == null) {
        store.send(AppFeature.Action.returnToServerTapped)
    }
    Box(Modifier.fillMaxSize().background(SystemColors.systemBackground)) {
        NavigationStack(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                ConnectionBody(store, didCopyErrorReport, onCopyErrorReport) { serverPendingRemoval = it }
            }
            PairingActions(store)
            if (store.state.savedServers.isEmpty()) {
                NavigationBarHidden()
            } else {
                NavigationTitle("Servers", displayMode = TitleDisplayMode.inline)
            }
            if (returnServer != null && progress == null) {
                Toolbar {
                    ToolbarItem(placement = ToolbarItemPlacement.topBarLeading) {
                        val host = returnServer.host
                        Row(
                            Modifier
                                .semantics { contentDescription = "Back to $host" }
                                .clickable { store.send(AppFeature.Action.returnToServerTapped) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SystemImage("chevron.backward", contentDescription = null, modifier = Modifier.symbolSize(TextStyles.body))
                            Text("Back")
                        }
                    }
                }
            }
        }
    }
    sheet(
        isPresented = store.binding(AppFeature.State::isEditingServer, AppFeature.Action::binding),
        detent = SheetDetent.Large,
    ) {
        CredentialSheet(store)
    }
    if (scanning) {
        Dialog(
            onDismissRequest = { store.send(AppFeature.Action.scannerDismissed) },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            PairingScannerView(
                onScanned = { store.send(AppFeature.Action.pairingCodeScanned(it)) },
                onCancel = { store.send(AppFeature.Action.scannerDismissed) },
            )
        }
    }
    val pending = serverPendingRemoval
    if (pending != null) {
        SystemConfirmation(
            onDismissRequest = { serverPendingRemoval = null },
            title = "Remove ${pending.host}?",
            confirm = "Remove Server",
            onConfirm = {
                serverPendingRemoval = null
                store.send(AppFeature.Action.serverDeleted(pending))
            },
            text = "This forgets the address and password on this phone. Sessions on the server are not affected.",
        )
    }
}

@Composable
private fun ColumnScope.ConnectionBody(
    store: Store<AppFeature.State, AppFeature.Action>,
    didCopyErrorReport: Boolean,
    onCopyErrorReport: (Boolean) -> Unit,
    onRemove: (OpenCodeServer) -> Unit,
) {
    val savedServers = store.state.savedServers
    val message = store.state.errorMessage
    Column(
        Modifier
            .fillMaxWidth()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = if (savedServers.isEmpty()) 24.dp else 0.dp)) {
            OpenCodeWordmark(
                Modifier.size(width = 200.dp, height = 36.dp),
                contentDescription = "OpenCode",
            )
            Text(
                if (savedServers.isEmpty()) {
                    "Keep your coding sessions moving from your phone."
                } else {
                    "Pick up where you left off, or pair another computer."
                },
                style = TextStyles.body,
                color = SystemColors.secondaryLabel,
            )
        }
        if (message != null) {
            ConnectionErrorCard(store, message, didCopyErrorReport, onCopyErrorReport)
        }
        if (savedServers.isEmpty()) {
            SetupSteps()
        } else {
            ServerList(store, onRemove)
        }
    }
}

@Composable
private fun SetupSteps() {
    val stepBadgeSize = with(LocalDensity.current) { 30.sp.toDp() }
    Column(Modifier.semantics(mergeDescendants = false) {}) {
        SetupStep(1, "Install OpenCode on your computer", stepBadgeSize) {
            val context = LocalContext.current
            Text(
                "opencode.ai/download",
                Modifier.clickable {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://opencode.ai/download")))
                },
                style = TextStyles.footnote.copy(fontFamily = FontFamilies.BerkeleyMono, fontSize = 14.sp, textDecoration = TextDecoration.Underline),
                color = SystemColors.secondaryLabel,
            )
        }
        HorizontalDivider(Modifier.padding(start = stepBadgeSize + 14.dp), color = SystemColors.separator)
        SetupStep(2, "Run this in a terminal", stepBadgeSize) {
            PairCommand()
        }
        HorizontalDivider(Modifier.padding(start = stepBadgeSize + 14.dp), color = SystemColors.separator)
        SetupStep(3, "Scan the QR code it prints", stepBadgeSize) {
            Text(
                "Your phone needs to reach your computer — use the same Wi-Fi, or Tailscale when you’re away.",
                style = TextStyles.footnote,
                color = SystemColors.secondaryLabel,
            )
        }
    }
}

@Composable
private fun PairCommand() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var didCopy by remember { mutableStateOf(false) }
    Row(
        Modifier
            .semantics { contentDescription = if (didCopy) "Copy opencode pair, Copied" else "Copy opencode pair" }
            .clickable {
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("opencode pair", "opencode pair"))
                didCopy = true
                scope.launch {
                    delay(1_500)
                    didCopy = false
                }
            }
            .heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(SystemColors.tertiarySystemFill)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "opencode pair",
                style = TextStyles.callout.copy(fontFamily = FontFamilies.BerkeleyMono, fontSize = 15.sp),
                color = SystemColors.label,
            )
            SystemImage(
                if (didCopy) "checkmark" else "square.on.square",
                contentDescription = null,
                modifier = Modifier.symbolSize(TextStyles.footnote.medium),
                tint = SystemColors.secondaryLabel,
            )
        }
    }
}

@Composable
private fun SetupStep(
    number: Int,
    title: String,
    badge: androidx.compose.ui.unit.Dp,
    detail: @Composable () -> Unit,
) {
    Row(
        Modifier
            .padding(vertical = 16.dp)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .size(badge)
                .clip(RoundedCornerShape(6.dp))
                .background(SystemColors.label)
                .semantics { hideFromAccessibility() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "$number",
                style = TextStyles.callout.copy(fontFamily = FontFamilies.BerkeleyMono, fontSize = 15.sp).semibold,
                color = SystemColors.systemBackground,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = TextStyles.headline, color = SystemColors.label)
            detail()
        }
    }
}

@Composable
private fun ConnectionErrorCard(
    store: Store<AppFeature.State, AppFeature.Action>,
    message: String,
    didCopy: Boolean,
    onCopy: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val version = BuildConfig.VERSION_NAME
    val build = BuildConfig.VERSION_CODE.toString()
    val contextLines = store.state.errorContext
    val card = RoundedCornerShape(20.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .shadow(12.dp, card, ambientColor = Color.Black.copy(alpha = 0.08f), spotColor = Color.Black.copy(alpha = 0.12f))
            .background(SystemColors.systemBackground, card)
            .openCodeGlass(card)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SystemImage(
                "exclamationmark.triangle.fill",
                contentDescription = null,
                modifier = Modifier.symbolSize(TextStyles.headline),
                tint = SystemColors.systemOrange,
            )
            Text(errorTitle(store.state.errorPhase), style = TextStyles.headline, color = SystemColors.systemOrange)
        }
        Text(inlineMessage(message), style = TextStyles.subheadline, color = SystemColors.label)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (contextLines != null) {
                Text("Phase: ${contextLines.phase.raw}", style = TextStyles.footnote, color = SystemColors.secondaryLabel)
                Text("Server: ${contextLines.target}", style = TextStyles.footnote, color = SystemColors.secondaryLabel)
                Text("Attempt: ${contextLines.attempt}", style = TextStyles.footnote, color = SystemColors.secondaryLabel)
            }
            Text("OpenCode $version ($build)", style = TextStyles.footnote, color = SystemColors.secondaryLabel)
        }
        Row(
            Modifier
                .shadow(1.dp, GlassSurface.rounded(14.dp))
                .background(SystemColors.secondarySystemGroupedBackground, GlassSurface.rounded(14.dp))
                .openCodeSecondaryButton()
                .clickable {
                    val report = store.state.errorReport(version, build, Instant.now()) ?: return@clickable
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("OpenCode connection error", report))
                    onCopy(true)
                }
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SystemImage(
                if (didCopy) "checkmark" else "square.on.square",
                contentDescription = null,
                modifier = Modifier.symbolSize(TextStyles.body),
                tint = SystemColors.label,
            )
            Text(if (didCopy) "Copied" else "Copy Error Report", style = TextStyles.body, color = SystemColors.label)
        }
    }
}

private fun errorTitle(phase: AppFeature.FailureContext.Phase?): String = when (phase) {
    AppFeature.FailureContext.Phase.pair -> "Couldn’t Pair"
    AppFeature.FailureContext.Phase.loadSessions -> "Couldn’t Load Sessions"
    AppFeature.FailureContext.Phase.connect, null -> "Couldn’t Connect"
}

@Composable
private fun PairingActions(store: Store<AppFeature.State, AppFeature.Action>) {
    val progress = store.state.connectionProgressLabel
    Column(
        Modifier
            .fillMaxWidth()
            .background(SystemColors.systemBackground)
            .padding(horizontal = 24.dp)
            .padding(top = 12.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Enter a Server Manually",
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clickable { store.send(AppFeature.Action.addServerTapped) },
            style = TextStyles.subheadline,
            color = SystemColors.secondaryLabel,
            textAlign = TextAlign.Center,
        )
        val enabled = progress == null
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (enabled) SystemColors.label else SystemColors.systemGray)
                .clickable(enabled = enabled, role = Role.Button) { store.send(AppFeature.Action.scanPairingCodeTapped) }
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (progress != null) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = SystemColors.systemBackground)
                Text(progress, style = TextStyles.headline, color = SystemColors.systemBackground)
            } else {
                SystemImage(
                    "qrcode.viewfinder",
                    contentDescription = null,
                    modifier = Modifier.symbolSize(TextStyles.headline),
                    tint = SystemColors.systemBackground,
                )
                Text("Scan Pairing Code", style = TextStyles.headline, color = SystemColors.systemBackground)
            }
        }
    }
}

@Composable
private fun ServerList(
    store: Store<AppFeature.State, AppFeature.Action>,
    onRemove: (OpenCodeServer) -> Unit,
) {
    val servers = store.state.savedServers
    val lastUsed = store.state.savedServer?.persistenceID
    val connectingID = store.state.connectingServerID
    val progress = store.state.connectionProgressLabel
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        servers.forEach { server ->
            val connecting = connectingID == server.id
            Row(
                Modifier
                    .fillMaxWidth()
                    .openCodeGlass(RoundedCornerShape(20.dp), interactive = true)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier
                        .weight(1f)
                        .clickable { store.send(AppFeature.Action.serverTapped(server)) },
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SystemImage(
                        if (server.isSecure) "lock.fill" else "server.rack",
                        contentDescription = null,
                        modifier = Modifier.width(22.dp).symbolSize(TextStyles.subheadline),
                        tint = SystemColors.secondaryLabel,
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(server.host, style = TextStyles.headline, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, color = SystemColors.label)
                        Text(
                            if (connecting && progress != null) {
                                progress
                            } else if (lastUsed == server.persistenceID) {
                                "${server.username} · last used"
                            } else {
                                server.username
                            },
                            style = TextStyles.footnote,
                            color = SystemColors.secondaryLabel,
                            maxLines = 1,
                        )
                    }
                }
                if (connecting) {
                    CircularProgressIndicator(
                        Modifier.size(18.dp).semantics { contentDescription = progress ?: "Connecting" },
                        strokeWidth = 2.dp,
                        color = SystemColors.secondaryLabel,
                    )
                } else {
                    Box(
                        Modifier
                            .size(44.dp)
                            .semantics { contentDescription = "Edit ${server.host}" }
                            .clickable { store.send(AppFeature.Action.editServerTapped(server)) },
                        contentAlignment = Alignment.Center,
                    ) {
                        SystemImage("pencil", contentDescription = null, modifier = Modifier.symbolSize(TextStyles.subheadline), tint = SystemColors.secondaryLabel)
                    }
                    Box(
                        Modifier
                            .size(44.dp)
                            .semantics { contentDescription = "Remove ${server.host}" }
                            .clickable { onRemove(server) },
                        contentAlignment = Alignment.Center,
                    ) {
                        SystemImage("trash", contentDescription = null, modifier = Modifier.symbolSize(TextStyles.subheadline), tint = SystemColors.secondaryLabel)
                    }
                }
            }
        }
    }
}

@Composable
private fun CredentialSheet(store: Store<AppFeature.State, AppFeature.Action>) {
    val editing = store.state.editingSavedServer
    NavigationStack(Modifier.fillMaxWidth().imePadding()) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            CredentialForm(store)
        }
        NavigationTitle(if (editing == null) "New Server" else "Edit Server", displayMode = TitleDisplayMode.inline)
        Toolbar {
            ToolbarItem(placement = ToolbarItemPlacement.cancellationAction) {
                SystemButton("Cancel") { store.send(AppFeature.Action.cancelEditingTapped) }
            }
        }
    }
}

@Composable
private fun CredentialForm(store: Store<AppFeature.State, AppFeature.Action>) {
    var revealsPassword by remember { mutableStateOf(false) }
    val progress = store.state.connectionProgressLabel
    val address = store.state.serverURL
    val disabled = progress != null || address.trim().isEmpty()
    val editing = store.state.editingSavedServer
    Column(
        Modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Field("Server address") {
            ServerField(
                value = address,
                onValueChange = { store.binding(AppFeature.State::serverURL, AppFeature.Action::binding).wrappedValue = it },
                placeholder = "http://192.168.1.20:4096",
                label = "Server address",
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next,
            )
        }
        Field("Username") {
            ServerField(
                value = store.state.username,
                onValueChange = { store.binding(AppFeature.State::username, AppFeature.Action::binding).wrappedValue = it },
                placeholder = "opencode",
                label = "Username",
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Next,
            )
        }
        Field("Password") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ServerField(
                    value = store.state.password,
                    onValueChange = { store.binding(AppFeature.State::password, AppFeature.Action::binding).wrappedValue = it },
                    placeholder = "Password",
                    label = "Password",
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Go,
                    hidden = !revealsPassword,
                    onGo = { if (!disabled) store.send(AppFeature.Action.connectButtonTapped) },
                    modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier
                        .size(44.dp)
                        .semantics { contentDescription = if (revealsPassword) "Hide Password" else "Show Password" }
                        .clickable { revealsPassword = !revealsPassword },
                    contentAlignment = Alignment.Center,
                ) {
                    SystemImage(
                        if (revealsPassword) "eye.slash" else "eye",
                        contentDescription = null,
                        modifier = Modifier.symbolSize(TextStyles.body),
                        tint = SystemColors.secondaryLabel,
                    )
                }
            }
        }
        val label = progress ?: if (editing == null) "Connect" else "Save and connect"
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (disabled) Modifier.heightIn(min = 50.dp).clip(GlassSurface.rounded(14.dp)).background(SystemColors.systemGray3) else Modifier.openCodePrimaryButton())
                .clickable(enabled = !disabled) { store.send(AppFeature.Action.connectButtonTapped) }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (progress != null) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = SystemColors.systemBackground)
            }
            Text(
                label,
                style = TextStyles.headline,
                color = if (disabled) SystemColors.secondaryLabel else SystemColors.systemBackground,
            )
            Spacer(Modifier.weight(1f))
            SystemImage(
                "arrow.right",
                contentDescription = null,
                modifier = Modifier.symbolSize(TextStyles.headline),
                tint = if (disabled) SystemColors.secondaryLabel else SystemColors.systemBackground,
            )
        }
        Text(editHint(editing), style = TextStyles.footnote, color = SystemColors.secondaryLabel)
    }
}

private fun editHint(editing: OpenCodeServer?): String {
    if (editing == null) {
        return "Use the address and password your OpenCode server runs with. The username is usually opencode. Pairing with a QR code fills these in for you."
    }
    return if (editing.usesPairingToken) {
        "Changes are saved once this server connects. This server was paired, so its password is a session token; scan a new pairing code instead of changing it."
    } else {
        "Changes are saved once this server connects."
    }
}

@Composable
private fun Field(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(label, style = TextStyles.caption.semibold, color = SystemColors.secondaryLabel)
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 50.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(SystemColors.tertiarySystemFill),
            contentAlignment = Alignment.CenterStart,
        ) {
            content()
        }
    }
}

@Composable
private fun ServerField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    label: String,
    keyboardType: KeyboardType,
    imeAction: ImeAction,
    hidden: Boolean = false,
    onGo: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .semantics { contentDescription = label },
        textStyle = TextStyles.body.copy(color = SystemColors.label),
        singleLine = true,
        visualTransformation = if (hidden) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = keyboardType,
            imeAction = imeAction,
        ),
        keyboardActions = KeyboardActions(onGo = { onGo?.invoke() }),
        cursorBrush = SolidColor(SystemColors.tint),
        decorationBox = { inner ->
            Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(placeholder, style = TextStyles.body, color = SystemColors.placeholderText)
                }
                inner()
            }
        },
    )
}

@Composable
private fun inlineMessage(message: String): AnnotatedString {
    val parsed = runCatching {
        AttributedString(
            message,
            AttributedString.MarkdownParsingOptions(
                AttributedString.MarkdownParsingOptions.InterpretedSyntax.inlineOnlyPreservingWhitespace,
            ),
        )
    }.getOrElse { AttributedString(message) }
    if (parsed.runs.isEmpty()) return AnnotatedString(parsed.characters)
    val points = (listOf(0, parsed.characters.length) + parsed.runs.flatMap { listOf(it.start, it.end) })
        .filter { it in 0..parsed.characters.length }
        .distinct()
        .sorted()
    return buildAnnotatedString {
        for (index in 0 until points.lastIndex) {
            val start = points[index]
            val end = points[index + 1]
            if (start == end) continue
            val code = parsed.runs.any { it.start <= start && it.end >= end && it.codeBackground }
            if (code) {
                pushStyle(SpanStyle(fontFamily = FontFamilies.BerkeleyMono, background = SystemColors.tertiarySystemFill))
            }
            append(parsed.characters.substring(start, end))
            if (code) pop()
        }
    }
}

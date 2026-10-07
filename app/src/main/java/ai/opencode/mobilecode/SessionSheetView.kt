@file:PortedFrom("OpenCode/SessionSheetView.swift", "4ab457c")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.GlassSurface
import ai.opencode.mobilecode.system.GroupedList
import ai.opencode.mobilecode.system.ListSectionSpacing
import ai.opencode.mobilecode.system.SectionScope
import ai.opencode.mobilecode.system.SystemColors
import ai.opencode.mobilecode.system.SystemConfirmation
import ai.opencode.mobilecode.system.SystemImage
import ai.opencode.mobilecode.system.SystemMenuItem
import ai.opencode.mobilecode.system.SystemMenuSection
import ai.opencode.mobilecode.system.SystemMenuSections
import ai.opencode.mobilecode.system.TextStyles
import ai.opencode.mobilecode.system.glassEffect
import ai.opencode.mobilecode.system.semibold
import ai.opencode.mobilecode.system.symbolSize
import ai.opencode.mobilecode.tca.AlertButton
import ai.opencode.mobilecode.tca.Binding
import ai.opencode.mobilecode.tca.Store
import ai.opencode.mobilecode.tca.alert
import ai.opencode.mobilecode.tca.binding
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import java.util.Currency
import java.util.Locale
import java.text.NumberFormat

/// What Home knows about the open session that its screen shows.
data class SessionListContext(
    /// The session's project, unless it has none (`global`).
    var project: OpenCodeProject? = null,
    /// nil when the session isn't a list row (a subagent).
    var isPinned: Boolean? = null,
    var isUnread: Boolean = false,
    /// Another session needs input or is unread: the drawer button shows it.
    var elsewhere: DrawerBadge? = null,
) {
    companion object {
        const val globalProjectID = "global"

        fun project(session: OpenCodeSession?, projects: List<OpenCodeProject>): OpenCodeProject? {
            if (session == null || session.projectID == globalProjectID) return null
            return projects.firstOrNull { it.id == session.projectID }
        }
    }
}

enum class DrawerBadge { attention, unread }

/// Session details and actions, opened from the header's title or the … menu.
@Composable
fun SessionSheetView(
    store: Store<SessionSheetFeature.State, SessionSheetFeature.Action>,
    session: OpenCodeSession?,
    context: SessionListContext,
    currentModelName: String,
    contextUsage: OpenCodeContextUsage?,
    contextFraction: Double?,
    contextLimit: Int?,
    sessionCost: Double?,
    onModelButtonTapped: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SessionSheetContent(
        store = store,
        session = session,
        context = context,
        currentModelName = currentModelName,
        contextUsage = contextUsage,
        contextFraction = contextFraction,
        contextLimit = contextLimit,
        sessionCost = sessionCost,
        onModelButtonTapped = onModelButtonTapped,
        modifier = modifier,
    )
}

@Composable
private fun SessionSheetContent(
    store: Store<SessionSheetFeature.State, SessionSheetFeature.Action>,
    session: OpenCodeSession?,
    context: SessionListContext,
    currentModelName: String,
    contextUsage: OpenCodeContextUsage?,
    contextFraction: Double?,
    contextLimit: Int?,
    sessionCost: Double?,
    onModelButtonTapped: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val operation = store.state.operation
    val isBusy = operation != null
    GroupedList(
        modifier.testTag("session-sheet"),
        sectionSpacing = ListSectionSpacing.compact,
        scrollContentBackground = false,
    ) {
        Section {
            row("title") {
                TitleRow(store, session, isBusy)
            }
            if (session != null) {
                row("location") { LocationRow(session, context) }
            }
        }
        Section {
            row("model") {
                ModelRow(currentModelName, onModelButtonTapped)
            }
            if (contextUsage != null) {
                row("context") { ContextRow(contextUsage, contextFraction, contextLimit) }
            }
            if (sessionCost != null) {
                row("cost") {
                    LabeledValue("Cost", usd(sessionCost), Modifier.testTag("session-sheet-cost"))
                }
            }
        }
        Section {
            row("compact") {
                ActionRow("Compact", "rectangle.compress.vertical", inFlight = operation == SessionSheetFeature.Operation.compacting, enabled = !isBusy) {
                    store.send(SessionSheetFeature.Action.compactTapped)
                }
            }
            row("fork") {
                ActionRow("Fork", "arrow.triangle.branch", inFlight = operation == SessionSheetFeature.Operation.forking, enabled = !isBusy) {
                    store.send(SessionSheetFeature.Action.forkTapped)
                }
            }
            row("export") {
                ExportRow(store, session?.title ?: "Session", isBusy, operation)
            }
        }
        val isPinned = context.isPinned
        if (isPinned != null) {
            Section {
                row("pin") {
                    ActionRow(if (isPinned) "Unpin" else "Pin", if (isPinned) "pin.slash" else "pin", enabled = true) {
                        store.send(SessionSheetFeature.Action.pinTapped)
                    }
                }
                row("read") {
                    ActionRow(
                        if (context.isUnread) "Mark as Read" else "Mark as Unread",
                        if (context.isUnread) "envelope.open" else "envelope.badge",
                        enabled = true,
                    ) {
                        store.send(SessionSheetFeature.Action.readTapped)
                    }
                }
            }
            Section {
                row("delete") {
                    ActionRow("Delete Session", "trash", enabled = !isBusy, destructive = true) {
                        store.send(SessionSheetFeature.Action.deleteTapped)
                    }
                }
            }
        }
    }
    SessionActionPresentations(store, session?.title)
}

@Composable
private fun TitleRow(
    store: Store<SessionSheetFeature.State, SessionSheetFeature.Action>,
    session: OpenCodeSession?,
    isBusy: Boolean,
) {
    val title = session?.title ?: ""
    val failed = store.state.autoRenameFailed
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (isBusy) 0.4f else 1f)
            .clickable(enabled = !isBusy, role = Role.Button) {
                store.send(SessionSheetFeature.Action.renameTapped(title))
            }
            .semantics {
                contentDescription = listOf("Rename Session", title, if (failed) "Couldn’t rename" else null).filterNotNull().joinToString(", ")
            }
            .testTag("session-sheet-title"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                Modifier.titleShimmer(store.state.operation == SessionSheetFeature.Operation.autoRenaming),
                style = TextStyles.title3.semibold,
                color = SystemColors.label,
                textAlign = TextAlign.Start,
            )
            if (failed) {
                Text("Couldn’t rename", style = TextStyles.footnote, color = SystemColors.secondaryLabel)
            }
        }
        if (store.state.operation == SessionSheetFeature.Operation.renaming) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = SystemColors.secondaryLabel)
        } else {
            SystemImage("pencil", contentDescription = null, modifier = Modifier.symbolSize(TextStyles.body), tint = SystemColors.secondaryLabel)
        }
    }
}

@Composable
private fun LocationRow(session: OpenCodeSession, context: SessionListContext) {
    val name = context.project?.name ?: session.directory.substringAfterLast('/')
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag("session-sheet-location"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SystemImage("folder", contentDescription = null, modifier = Modifier.symbolSize(TextStyles.body), tint = SystemColors.tint)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, style = TextStyles.body, color = SystemColors.label)
            Text(
                session.directory,
                style = TextStyles.footnote,
                color = SystemColors.secondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
            )
        }
    }
}

@Composable
private fun ModelRow(currentModelName: String, onModelButtonTapped: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onModelButtonTapped)
            .testTag("session-sheet-model"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Model", style = TextStyles.body, color = SystemColors.label)
        Spacer(Modifier.weight(1f))
        Text(currentModelName, style = TextStyles.body, color = SystemColors.label)
        SystemImage(
            "chevron.right",
            contentDescription = null,
            modifier = Modifier.symbolSize(TextStyles.footnote),
            tint = SystemColors.tertiaryLabel,
        )
    }
}

@Composable
private fun ContextRow(usage: OpenCodeContextUsage, fraction: Double?, limit: Int?) {
    Row(
        Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Context, ${SessionContextText.accessibilityValue(usage.tokens, limit)}" }
            .testTag("session-sheet-context"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Context", style = TextStyles.body, color = SystemColors.label)
        Spacer(Modifier.weight(1f))
        if (fraction != null) {
            SessionContextRing(fraction, Modifier.size(18.dp), lineWidth = 3.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            SessionContextText.summary(usage.tokens, limit),
            style = TextStyles.body.copy(fontFeatureSettings = "tnum"),
            color = SystemColors.secondaryLabel,
        )
    }
}

@Composable
private fun ExportRow(
    store: Store<SessionSheetFeature.State, SessionSheetFeature.Action>,
    title: String,
    isBusy: Boolean,
    operation: SessionSheetFeature.Operation?,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.testTag("session-sheet-export")) {
        ActionRow("Export", "square.and.arrow.up", inFlight = operation is SessionSheetFeature.Operation.exporting, enabled = !isBusy) {
            expanded = true
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Markdown", style = TextStyles.body) },
                onClick = {
                    expanded = false
                    store.send(SessionSheetFeature.Action.exportTapped(OpenCodeExportFormat.markdown, title))
                },
            )
            DropdownMenuItem(
                text = { Text("JSON", style = TextStyles.body) },
                onClick = {
                    expanded = false
                    store.send(SessionSheetFeature.Action.exportTapped(OpenCodeExportFormat.json, title))
                },
            )
        }
    }
}

@Composable
private fun ActionRow(
    title: String,
    systemImage: String,
    inFlight: Boolean = false,
    enabled: Boolean = true,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val color = if (destructive) SystemColors.systemRed else SystemColors.label
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SystemImage(systemImage, contentDescription = null, modifier = Modifier.symbolSize(TextStyles.body), tint = if (destructive) color else SystemColors.tint)
        Spacer(Modifier.width(12.dp))
        Text(title, style = TextStyles.body, color = color)
        Spacer(Modifier.weight(1f))
        if (inFlight) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = SystemColors.secondaryLabel)
    }
}

@Composable
private fun LabeledValue(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = TextStyles.body, color = SystemColors.label)
        Spacer(Modifier.weight(1f))
        Text(value, style = TextStyles.body, color = SystemColors.secondaryLabel)
    }
}

private fun SectionScope.row(id: String, content: @Composable () -> Unit) {
    ForEach(listOf(id), id = { it }) { content() }
}

/// Sheets are Liquid Glass at partial height; a grouped list's opaque
/// backdrop would hide it unless transparency is reduced.
@Composable
fun SheetGlassBackground(reduceTransparency: Boolean = false, content: @Composable () -> Unit) {
    content()
    if (!reduceTransparency) return
}

/// Rename, failure, delete confirmation, and export sharing for the Session
/// sheet's actions, wherever they were started: the sheet or the header menu.
@Composable
fun SessionActionPresentations(
    store: Store<SessionSheetFeature.State, SessionSheetFeature.Action>,
    title: String?,
) {
    val renaming = store.binding(SessionSheetFeature.State::isRenaming, SessionSheetFeature.Action::binding)
    val draft = store.binding(SessionSheetFeature.State::renameDraft, SessionSheetFeature.Action::binding)
    if (renaming.wrappedValue) {
        RenameAlert(store, renaming, draft)
    }
    val failure = store.state.failure
    alert(
        title = failure?.title ?: "",
        isPresented = Binding(
            getter = { store.state.failure != null },
            setter = { presented -> if (!presented) store.send(SessionSheetFeature.Action.failureDismissed) },
        ),
        message = failure?.message,
        buttons = listOf(AlertButton("OK") { store.send(SessionSheetFeature.Action.failureDismissed) }),
    )
    val confirming = store.binding(SessionSheetFeature.State::confirmsDeletion, SessionSheetFeature.Action::binding)
    if (confirming.wrappedValue) {
        SystemConfirmation(
            onDismissRequest = { confirming.wrappedValue = false },
            title = "Delete “${title ?: "Session"}”?",
            text = "This removes the session from the server for all clients.",
            confirm = "Delete",
            onConfirm = { store.send(SessionSheetFeature.Action.deleteConfirmed) },
        )
    }
    ExportShare(store)
}

@Composable
private fun RenameAlert(
    store: Store<SessionSheetFeature.State, SessionSheetFeature.Action>,
    renaming: Binding<Boolean>,
    draft: Binding<String>,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { renaming.wrappedValue = false },
        title = { Text("Rename Session", style = TextStyles.headline, color = SystemColors.label) },
        text = {
            BasicTextField(
                value = draft.wrappedValue,
                onValueChange = { draft.wrappedValue = it },
                textStyle = TextStyles.body.copy(color = SystemColors.label),
                modifier = Modifier.fillMaxWidth().testTag("session-sheet-rename-field"),
            )
        },
        confirmButton = {
            Row {
                Text(
                    "Auto Rename",
                    Modifier.clickable { store.send(SessionSheetFeature.Action.autoRenameTapped) }.padding(8.dp),
                    color = SystemColors.systemBlue,
                    style = TextStyles.body,
                )
                Text(
                    "Rename",
                    Modifier.clickable { store.send(SessionSheetFeature.Action.renameSubmitted) }.padding(8.dp),
                    color = SystemColors.systemBlue,
                    style = TextStyles.body.semibold,
                )
            }
        },
        dismissButton = {
            Text(
                "Cancel",
                Modifier.clickable { renaming.wrappedValue = false }.padding(8.dp),
                color = SystemColors.systemBlue,
                style = TextStyles.body,
            )
        },
        containerColor = SystemColors.secondarySystemGroupedBackground,
    )
}

@Composable
private fun ExportShare(store: Store<SessionSheetFeature.State, SessionSheetFeature.Action>) {
    val context = LocalContext.current
    var presented by remember { mutableStateOf<File?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        presented = null
        store.send(SessionSheetFeature.Action.exportDismissed)
    }
    val file = store.state.exportedFile?.url
    if (file != null && presented != file) {
        presented = file
        val uri = runCatching { FileProvider.getUriForFile(context, "${context.packageName}.files", file) }.getOrNull()
        if (uri != null) {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = if (file.extension == "json") "application/json" else "text/markdown"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newRawUri(file.name, uri)
            }
            runCatching { launcher.launch(Intent.createChooser(intent, null)) }
        }
    }
}

/// Session actions; the rim doubles as the context gauge.
@Composable
fun SessionMenuButton(
    title: String,
    sessionID: String?,
    context: SessionListContext,
    contextFraction: Double?,
    contextUsage: OpenCodeContextUsage?,
    contextLimit: Int?,
    onAction: (SessionSheetFeature.Action) -> Unit,
    onSessionInfo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val usage = contextUsage?.let { SessionContextText.accessibilityValue(it.tokens, contextLimit) } ?: ""
    Box(modifier) {
        Box(
            Modifier
                .size(44.dp)
                .glassEffect(GlassSurface.Circle, interactive = true)
                .clickable(role = Role.Button) { expanded = true }
                .semantics { contentDescription = listOf("More", usage).filter { it.isNotEmpty() }.joinToString(", ") }
                .testTag("session-menu"),
            contentAlignment = Alignment.Center,
        ) {
            SystemImage("ellipsis", contentDescription = null, modifier = Modifier.symbolSize(TextStyles.body), tint = SystemColors.label)
            if (contextFraction != null) {
                SessionContextRing(contextFraction, Modifier.fillMaxSize().padding(1.dp), lineWidth = 2.dp, trackOpacity = 0.0)
            }
        }
        SessionMenu(expanded, { expanded = false }, title, sessionID, context, onAction, onSessionInfo)
    }
}

@Composable
fun SessionMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    title: String,
    sessionID: String?,
    context: SessionListContext,
    onAction: (SessionSheetFeature.Action) -> Unit,
    onSessionInfo: () -> Unit,
) {
    val contextMenu = LocalContext.current
    var exportExpanded by remember { mutableStateOf(false) }
    val isPinned = context.isPinned
    val sections = buildList {
        val first = mutableListOf<SystemMenuItem>()
        if (isPinned != null) {
            first += SystemMenuItem(if (isPinned) "Unpin" else "Pin", if (isPinned) "pin.slash" else "pin") {
                onDismissRequest()
                onAction(SessionSheetFeature.Action.pinTapped)
            }
        }
        first += SystemMenuItem("Rename", "pencil") {
            onDismissRequest()
            onAction(SessionSheetFeature.Action.renameTapped(title))
        }
        first += SystemMenuItem("Copy Session ID", "square.on.square") {
            onDismissRequest()
            sessionID?.let { id ->
                contextMenu.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Session ID", id))
            }
        }
        if (isPinned != null) {
            first += SystemMenuItem(
                if (context.isUnread) "Mark as Read" else "Mark as Unread",
                if (context.isUnread) "envelope.open" else "envelope.badge",
            ) {
                onDismissRequest()
                onAction(SessionSheetFeature.Action.readTapped)
            }
        }
        add(SystemMenuSection(first))
        add(
            SystemMenuSection(
                listOf(
                    SystemMenuItem("Compact", "rectangle.compress.vertical") {
                        onDismissRequest()
                        onAction(SessionSheetFeature.Action.compactTapped)
                    },
                    SystemMenuItem("Fork", "arrow.triangle.branch") {
                        onDismissRequest()
                        onAction(SessionSheetFeature.Action.forkTapped)
                    },
                    SystemMenuItem("Session Info", "info.circle") {
                        onDismissRequest()
                        onSessionInfo()
                    },
                ),
            ),
        )
        if (isPinned != null) {
            add(
                SystemMenuSection(
                    listOf(
                        SystemMenuItem("Delete Session", "trash", destructive = true) {
                            onDismissRequest()
                            onAction(SessionSheetFeature.Action.deleteTapped)
                        },
                    ),
                ),
            )
        }
    }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest) {
        Text(
            title,
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = TextStyles.footnote,
            color = SystemColors.secondaryLabel,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        SystemMenuSections(listOf(sections[0]))
        HorizontalDivider(color = SystemColors.separator)
        sections[1].items.dropLast(1).forEach { item -> MenuRow(item) }
        Box {
            MenuRow(SystemMenuItem("Export", "square.and.arrow.up") { exportExpanded = true }, trailing = "chevron.right")
            DropdownMenu(expanded = exportExpanded, onDismissRequest = { exportExpanded = false }) {
                DropdownMenuItem(
                    text = { Text("Markdown") },
                    onClick = {
                        exportExpanded = false
                        onDismissRequest()
                        onAction(SessionSheetFeature.Action.exportTapped(OpenCodeExportFormat.markdown, title))
                    },
                )
                DropdownMenuItem(
                    text = { Text("JSON") },
                    onClick = {
                        exportExpanded = false
                        onDismissRequest()
                        onAction(SessionSheetFeature.Action.exportTapped(OpenCodeExportFormat.json, title))
                    },
                )
            }
        }
        MenuRow(sections[1].items.last())
        if (sections.size > 2) {
            HorizontalDivider(color = SystemColors.separator)
            sections[2].items.forEach { MenuRow(it) }
        }
    }
}

@Composable
private fun MenuRow(item: SystemMenuItem, trailing: String? = null) {
    val color = if (item.destructive) SystemColors.systemRed else SystemColors.label
    DropdownMenuItem(
        text = { Text(item.title, color = color, style = TextStyles.body) },
        leadingIcon = item.systemImage?.let { name -> { SystemImage(name, contentDescription = null, tint = color) } },
        trailingIcon = trailing?.let { name -> { SystemImage(name, contentDescription = null, tint = SystemColors.tertiaryLabel) } },
        onClick = item.onClick,
    )
}

private fun usd(cost: Double): String {
    val format = NumberFormat.getCurrencyInstance(Locale.getDefault())
    format.currency = Currency.getInstance("USD")
    return format.format(cost)
}

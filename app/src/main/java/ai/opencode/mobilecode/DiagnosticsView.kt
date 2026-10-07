@file:PortedFrom("OpenCode/DiagnosticsView.swift", "494e433", partial = "all but the Voice Calls row and the saved calls Developer Mode uploads, which arrive with the voice call log")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.NavigationStack
import ai.opencode.mobilecode.system.NavigationTitle
import ai.opencode.mobilecode.system.SystemColors
import ai.opencode.mobilecode.system.SystemImage
import ai.opencode.mobilecode.system.SystemMenu
import ai.opencode.mobilecode.system.SystemMenuItem
import ai.opencode.mobilecode.system.SystemMenuSection
import ai.opencode.mobilecode.system.TextStyles
import ai.opencode.mobilecode.system.TitleDisplayMode
import ai.opencode.mobilecode.system.Toolbar
import ai.opencode.mobilecode.system.ToolbarItem
import ai.opencode.mobilecode.system.ToolbarItemPlacement
import ai.opencode.mobilecode.system.symbolSize
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale
import ai.opencode.mobilecode.system.GroupedList
import ai.opencode.mobilecode.tca.Shared
import ai.opencode.mobilecode.tca.SharedKey

@Composable
fun DiagnosticsView() {
    val recorder = DiagnosticsRecorder.shared
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(recorder.isEnabled) }
    var includesTranscripts by remember { mutableStateOf(recorder.includesTranscripts) }
    var events by remember { mutableStateOf(emptyList<DiagnosticsEvent>()) }
    var metricsByRequest by remember { mutableStateOf(emptyMap<String, DiagnosticsEvent>()) }
    var shareMenu by remember { mutableStateOf(false) }
    var developerMode by remember<Shared<Boolean>> { Shared(SharedKey.developerMode) }

    fun reload() {
        enabled = recorder.isEnabled
        includesTranscripts = recorder.includesTranscripts
        events = recorder.snapshot()
        metricsByRequest = metricsByRequest(events)
    }

    LaunchedEffect(recorder) {
        reload()
        recorder.didChange.collect { reload() }
    }

    NavigationStack(Modifier.fillMaxSize().background(SystemColors.systemGroupedBackground)) {
        NavigationTitle("Diagnostics", displayMode = TitleDisplayMode.inline)
        Toolbar {
            ToolbarItem(placement = ToolbarItemPlacement.topBarTrailing) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        SystemImage(
                            "square.and.arrow.up",
                            contentDescription = "Share",
                            modifier = Modifier
                                .symbolSize(17)
                                .alpha(if (events.isEmpty()) 0.35f else 1f)
                                .clickable(enabled = events.isNotEmpty(), role = Role.Button) { shareMenu = true },
                        )
                        SystemMenu(
                            expanded = shareMenu,
                            onDismissRequest = { shareMenu = false },
                            sections = listOf(
                                SystemMenuSection(
                                    listOf(
                                        SystemMenuItem("Text") {
                                            shareMenu = false
                                            shareDiagnostics(context, DiagnosticsExport(DiagnosticsExport.Format.text, events))
                                        },
                                        SystemMenuItem("JSON") {
                                            shareMenu = false
                                            shareDiagnostics(context, DiagnosticsExport(DiagnosticsExport.Format.json, events))
                                        },
                                    ),
                                ),
                            ),
                        )
                    }
                    SystemImage(
                        "trash",
                        contentDescription = "Clear",
                        modifier = Modifier
                            .symbolSize(17)
                            .alpha(if (events.isEmpty()) 0.35f else 1f)
                            .clickable(enabled = events.isNotEmpty(), role = Role.Button) {
                                recorder.clear()
                                reload()
                            },
                    )
                }
            }
        }
        GroupedList(Modifier.weight(1f)) {
            Section(
                footer = {
                    Text(
                        if (developerMode) {
                            "Uploading voice calls and traces: a shake, a dropped call, a stalled connection, a crash, and leaving the app each send the timeline and where you were. Anyone with the link can view them; they’re kept 30 days."
                        } else {
                            "Turn on to send voice calls and traces to the OpenCode log server for debugging. Nothing leaves this phone while it’s off."
                        },
                    )
                },
            ) {
                ForEach(listOf("developer-mode"), { it }) {
                    DiagnosticsToggle(
                        title = "Developer Mode",
                        checked = developerMode,
                        hint = "Uploads voice calls and traces to the OpenCode log server",
                        onCheckedChange = { on ->
                            developerMode = on
                            // Traces need a timeline.
                            if (on && !enabled) {
                                recorder.setEnabled(true)
                                reload()
                            }
                        },
                    )
                }
                if (developerMode) {
                    ForEach(listOf("open-web"), { it }) {
                        DiagnosticsLink("Open on the Web", "safari") {
                            context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(DeveloperMode.webURL)))
                        }
                    }
                    ForEach(listOf("share-link"), { it }) {
                        DiagnosticsLink("Share Link", "square.and.arrow.up") {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, DeveloperMode.webURL)
                            }
                            context.startActivity(Intent.createChooser(intent, null))
                        }
                    }
                }
            }

            Section(
                footer = {
                    Text(
                        if (InternalBuild.isEnabled) {
                            "On by default in test builds. Diagnostics never include credentials, headers, prompts, tool input, or response bodies; voice turns keep a short excerpt only while Include Transcripts is on."
                        } else {
                            "Off by default. Diagnostics never include credentials, headers, message text, prompts, tool input, or response bodies."
                        },
                    )
                },
            ) {
                ForEach(listOf("record"), { it }) {
                    DiagnosticsToggle(
                        title = "Record diagnostics",
                        checked = enabled,
                        hint = "Records a bounded timeline of timing and network metadata on this phone",
                        enabled = !developerMode,
                        onCheckedChange = { value ->
                            recorder.setEnabled(value)
                            reload()
                        },
                    )
                }
                if (InternalBuild.isEnabled && enabled) {
                    ForEach(listOf("transcripts"), { it }) {
                        DiagnosticsToggle(
                            title = "Include Transcripts",
                            checked = includesTranscripts,
                            hint = "Keeps a short excerpt of each voice turn in the timeline",
                            onCheckedChange = { value ->
                                recorder.setIncludesTranscripts(value)
                                reload()
                            },
                        )
                    }
                }
            }
            if (enabled) {
                Section(header = { Text("Summary") }) {
                    ForEach(listOf("summary"), { it }) {
                        DiagnosticsSummary(events)
                    }
                }
                ForEach(groupedEpochs(events), { it.key }) { group ->
                    val visible = group.events.filter {
                        it.kind != DiagnosticsEvent.Kind.requestStart && it.kind != DiagnosticsEvent.Kind.networkMetrics
                    }
                    if (visible.isNotEmpty()) {
                        Section(header = { Text(group.title) }) {
                            ForEach(visible, { it.id }) { event ->
                                DiagnosticsEventRow(event, events, metricsByRequest)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsToggle(
    title: String,
    checked: Boolean,
    hint: String,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    val dark = isSystemInDarkTheme()
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.4f)
            .semantics { contentDescription = "$title. $hint" }
            .clickable(enabled = enabled, role = Role.Switch) { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), style = TextStyles.body, color = SystemColors.label)
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = SystemColors.systemGreen,
                checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = if (dark) SystemColors.systemGray4 else SystemColors.systemGray5,
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

@Composable
private fun DiagnosticsSummary(events: List<DiagnosticsEvent>) {
    val recent = events.filter { it.monotonicSeconds >= (events.lastOrNull()?.monotonicSeconds ?: 0.0) - 60 }
    val requestEnds = recent.filter { it.kind == DiagnosticsEvent.Kind.requestEnd }
    val wire = recent.mapNotNull { it.wireBytes }.sum()
    val decoded = requestEnds.mapNotNull { it.decodedBytes }.sum()
    val open = events.lastOrNull { it.name == "session_tap" || it.name == "session_navigation_started" }
    val frame = eventAfter(events, open, setOf("session_first_frame"))
    val fresh = eventAfter(events, open, setOf("fresh_content_laid_out", "snapshot_apply", "transcript_response"))
    val stream = events.lastOrNull { it.kind == DiagnosticsEvent.Kind.stream }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LabeledContent("Last session open", open?.sessionID ?: "—")
        LabeledContent("Tap → first frame", duration(open, frame))
        LabeledContent(
            "Tap → first fresh content",
            duration(open, fresh),
            Modifier.semantics { testTag = "diagnostics-first-fresh-content" },
        )
        LabeledContent("SSE", stream?.name ?: "No event recorded")
        LabeledContent(
            "Last 60 seconds",
            "${requestEnds.size} requests · ${formatBytes(wire)} wire → ${formatBytes(decoded)} decoded",
        )
    }
}

@Composable
private fun LabeledContent(label: String, value: String, modifier: Modifier = Modifier) {
    val style = TextStyles.subheadline
    if (value.length > 24) {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = style, color = SystemColors.label)
            Text(value, style = style, color = SystemColors.secondaryLabel)
        }
        return
    }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = style, color = SystemColors.label)
        Spacer(Modifier.weight(1f))
        Text(value, style = style, color = SystemColors.secondaryLabel, textAlign = TextAlign.End)
    }
}

@Composable
private fun DiagnosticsEventRow(
    event: DiagnosticsEvent,
    events: List<DiagnosticsEvent>,
    metricsByRequest: Map<String, DiagnosticsEvent>,
) {
    if (event.kind == DiagnosticsEvent.Kind.requestEnd) {
        val metrics = event.requestID?.let { metricsByRequest[it] }
        Column(
            Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) { testTag = "diagnostics-request-row" },
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${event.method ?: "GET"} ${event.path ?: "unknown"}",
                    Modifier.weight(1f),
                    style = TextStyles.body.copy(fontFamily = FontFamily.Monospace),
                    color = SystemColors.label,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                Text(formatMilliseconds(event.durationMilliseconds), color = SystemColors.secondaryLabel, style = TextStyles.body)
            }
            Text(
                "${formatBytes(metrics?.wireBytes)} wire → ${formatBytes(event.decodedBytes)} decoded · ${event.contentEncoding ?: "identity"} · ${metrics?.protocolName ?: "protocol —"} · ${if (metrics?.connectionReused == true) "reused" else "new"}",
                style = TextStyles.caption,
                color = SystemColors.secondaryLabel,
            )
            if (metrics != null) {
                Text(
                    "DNS ${formatMilliseconds(metrics.dnsMilliseconds)} · connect ${formatMilliseconds(metrics.connectMilliseconds)} · TLS ${formatMilliseconds(metrics.tlsMilliseconds)} · first byte ${formatMilliseconds(metrics.firstByteMilliseconds)} · download ${formatMilliseconds(metrics.downloadMilliseconds)}",
                    style = TextStyles.caption2,
                    color = SystemColors.secondaryLabel,
                )
            }
            event.errorCategory?.let { category ->
                Text(category.toString(), style = TextStyles.caption, color = SystemColors.systemRed)
            }
        }
        return
    }
    if (event.kind == DiagnosticsEvent.Kind.requestStart || event.kind == DiagnosticsEvent.Kind.networkMetrics) return
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                relativeTime(events, event),
                style = TextStyles.caption.copy(fontFamily = FontFamily.Monospace),
                color = SystemColors.secondaryLabel,
            )
            Spacer(Modifier.width(8.dp))
            Text(event.name.replace("_", " "), Modifier.weight(1f), style = TextStyles.body, color = SystemColors.label)
            event.durationMilliseconds?.let { duration ->
                Text(formatMilliseconds(duration), color = SystemColors.secondaryLabel, style = TextStyles.body)
            }
        }
        event.reason?.let { reason ->
            Text(reason, style = TextStyles.caption.copy(fontFamily = FontFamily.Monospace), color = SystemColors.secondaryLabel)
        }
    }
}

private data class EpochGroup(val key: Int, val title: String, val events: List<DiagnosticsEvent>)

private fun groupedEpochs(events: List<DiagnosticsEvent>): List<EpochGroup> {
    val groups = events.groupBy { it.navigationEpoch ?: 0 }
    return groups.keys.sortedDescending().map { epoch ->
        val values = groups[epoch] ?: emptyList()
        val session = values.firstNotNullOfOrNull { it.sessionID }
        EpochGroup(epoch, if (epoch == 0) "App" else "Navigation $epoch · ${session ?: "session"}", values)
    }
}

private fun eventAfter(events: List<DiagnosticsEvent>, start: DiagnosticsEvent?, names: Set<String>): DiagnosticsEvent? {
    start ?: return null
    return events.firstOrNull { it.monotonicSeconds >= start.monotonicSeconds && names.contains(it.name) }
}

private fun duration(start: DiagnosticsEvent?, end: DiagnosticsEvent?): String {
    if (start == null || end == null) return "Pending"
    return formatMilliseconds((end.monotonicSeconds - start.monotonicSeconds) * 1_000)
}

private fun relativeTime(events: List<DiagnosticsEvent>, event: DiagnosticsEvent): String {
    val origin = events.firstOrNull()?.monotonicSeconds ?: return "+0.0s"
    return String.format(Locale.US, "+%.1fs", event.monotonicSeconds - origin)
}

private fun formatMilliseconds(value: Double?): String =
    value?.let { String.format(Locale.US, "%.0f ms", it) } ?: "—"

private fun formatBytes(value: Long?): String {
    if (value == null) return "—"
    val units = listOf("bytes", "KB", "MB", "GB", "TB")
    var amount = value.toDouble()
    var index = 0
    while (amount >= 1_000 && index < units.lastIndex) {
        amount /= 1_000
        index += 1
    }
    val number = if (index == 0) value.toString() else String.format(Locale.US, "%.0f", amount)
    return "$number ${units[index]}"
}

fun metricsByRequest(events: List<DiagnosticsEvent>): Map<String, DiagnosticsEvent> {
    val index = mutableMapOf<String, DiagnosticsEvent>()
    for (event in events) {
        if (event.kind == DiagnosticsEvent.Kind.networkMetrics) {
            event.requestID?.let { index[it] = event }
        }
    }
    return index
}

/// The displayed timeline, serialized only when the share sheet asks for it.
class DiagnosticsExport(
    var format: Format,
    var events: List<DiagnosticsEvent>,
) {
    enum class Format { text, json }

    val suggestedFileName: String
        get() = if (format == Format.text) "opencode-diagnostics.txt" else "opencode-diagnostics.json"

    fun exported(): ByteArray = when (format) {
        Format.text -> DiagnosticsRecorder.shared.exportText(events = events).toByteArray()
        Format.json -> DiagnosticsRecorder.shared.exportJSON(events = events)
    }
}

/// `Link` and `ShareLink` rows: `Label(title, systemImage:)` in the tint.
@Composable
private fun DiagnosticsLink(title: String, systemImage: String, action: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = action),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SystemImage(systemImage, contentDescription = null, modifier = Modifier.symbolSize(TextStyles.body), tint = SystemColors.tint)
        Text(title, style = TextStyles.body, color = SystemColors.tint)
    }
}

private fun shareDiagnostics(context: android.content.Context, export: DiagnosticsExport) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = if (export.format == DiagnosticsExport.Format.json) "application/json" else "text/plain"
        putExtra(Intent.EXTRA_TEXT, export.exported().toString(Charsets.UTF_8))
        putExtra(Intent.EXTRA_SUBJECT, "OpenCode diagnostics")
        putExtra(Intent.EXTRA_TITLE, export.suggestedFileName)
    }
    context.startActivity(Intent.createChooser(intent, "OpenCode diagnostics"))
}

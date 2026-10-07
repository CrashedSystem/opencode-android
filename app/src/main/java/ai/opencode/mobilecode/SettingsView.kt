@file:PortedFrom("OpenCode/SettingsView.swift", "d809b1e", partial = "all but the Voice page's dot scope preview (494e433), Raise to Talk (2e313a1), VoiceKeyFingerprint (f3bda52), and the Notifications row")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.GlassSurface
import ai.opencode.mobilecode.system.GroupedList
import ai.opencode.mobilecode.system.ListSectionSpacing
import ai.opencode.mobilecode.system.LocalDismiss
import ai.opencode.mobilecode.system.NavigationStack
import ai.opencode.mobilecode.system.NavigationTitle
import ai.opencode.mobilecode.system.SystemButton
import ai.opencode.mobilecode.system.SystemColors
import ai.opencode.mobilecode.system.SystemImage
import ai.opencode.mobilecode.system.SystemMenu
import ai.opencode.mobilecode.system.SystemMenuItem
import ai.opencode.mobilecode.system.SystemMenuSection
import ai.opencode.mobilecode.system.TextStyles
import ai.opencode.mobilecode.system.bold
import ai.opencode.mobilecode.system.TitleDisplayMode
import ai.opencode.mobilecode.system.Toolbar
import ai.opencode.mobilecode.system.ToolbarItem
import ai.opencode.mobilecode.system.ToolbarItemPlacement
import ai.opencode.mobilecode.system.glassEffect
import ai.opencode.mobilecode.system.listRowSeparatorLeading
import ai.opencode.mobilecode.system.medium
import ai.opencode.mobilecode.system.semibold
import ai.opencode.mobilecode.system.symbolSize
import ai.opencode.mobilecode.tca.Dependency
import ai.opencode.mobilecode.tca.DependencyValues
import ai.opencode.mobilecode.tca.Shared
import ai.opencode.mobilecode.tca.SharedKey
import ai.opencode.mobilecode.tca.Store
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

@Composable
fun SessionSettingsView(
    serverHost: String,
    connectionStatus: HomeFeature.State.ConnectionStatus,
    connectionErrorMessage: String?,
    onChangeServer: () -> Unit,
    onRetryConnection: () -> Unit,
    whatsNew: Store<WhatsNewFeature.State, WhatsNewFeature.Action>,
) {
    val voiceModeEnabled by remember<Shared<Boolean>> { Shared(SharedKey.voiceModeEnabled) }
    val developerMode by remember<Shared<Boolean>> { Shared(SharedKey.developerMode) }
    val dismiss = LocalDismiss.current
    // `NavigationLink`'s pushes; the system layer has no navigation path yet.
    var pushed by rememberSaveable { mutableStateOf<SettingsDestination?>(null) }
    BackHandler(enabled = pushed != null) { pushed = null }
    Box(Modifier.fillMaxWidth().hidesFromProblemReports()) {
        when (pushed) {
            SettingsDestination.voice -> PushedScreen(onBack = { pushed = null }) { VoiceSettingsView() }
            SettingsDestination.diagnostics -> {
                // Diagnostics draws its own bar; the back button sits in its leading slot.
                Box(Modifier.fillMaxSize()) {
                    DiagnosticsView()
                    BackButton(Modifier.padding(start = 16.dp).height(56.dp)) { pushed = null }
                }
            }
            SettingsDestination.whatsNew -> PushedScreen(
                onBack = { pushed = null },
                trailing = { WhatsNewOptions(whatsNew) },
            ) { WhatsNewList(store = whatsNew) }
            null -> NavigationStack(Modifier.fillMaxWidth()) {
                NavigationTitle("Settings", displayMode = TitleDisplayMode.inline)
                Toolbar {
                    ToolbarItem(placement = ToolbarItemPlacement.confirmationAction) {
                        SystemButton("Done") { dismiss() }
                    }
                }
                GroupedList(sectionSpacing = ListSectionSpacing.compact, scrollContentBackground = false) {
                    Section {
                        ForEach(listOf("server"), { it }, plain = true) {
                            SettingsServerHeader(
                                host = serverHost,
                                status = connectionStatus,
                                error = connectionErrorMessage,
                                onRetry = onRetryConnection,
                                onChange = {
                                    dismiss()
                                    onChangeServer()
                                },
                            )
                        }
                    }

                    // Notifications wait for an Android delivery path (docs/port/deviations.md).
                    Section {
                        ForEach(listOf("voice"), { it }) {
                            NavigationLinkRow(push = { pushed = SettingsDestination.voice }) {
                                SettingsRow("Voice", symbol = "waveform", color = SystemColors.systemIndigo, value = if (voiceModeEnabled) "On" else "Off")
                            }
                        }
                        ForEach(listOf("diagnostics"), { it }) {
                            NavigationLinkRow(push = { pushed = SettingsDestination.diagnostics }) {
                                SettingsRow(
                                    "Diagnostics",
                                    symbol = "waveform.path.ecg",
                                    color = SystemColors.systemTeal,
                                    value = if (developerMode) "Developer Mode" else null,
                                )
                            }
                        }
                    }

                    Section {
                        ForEach(listOf("whats-new"), { it }) {
                            NavigationLinkRow(push = { pushed = SettingsDestination.whatsNew }) {
                                SettingsRow("What’s New", symbol = "sparkles", color = SystemColors.systemOrange)
                            }
                        }
                    }
                }
            }
        }
    }
}

private enum class SettingsDestination { voice, diagnostics, whatsNew }

/// What's New's page menu: the drawer toggle moved here from Settings.
@Composable
private fun WhatsNewOptions(whatsNew: Store<WhatsNewFeature.State, WhatsNewFeature.Action>) {
    var open by remember { mutableStateOf(false) }
    Box {
        SystemImage(
            "ellipsis",
            contentDescription = "What’s New options",
            modifier = Modifier.symbolSize(TextStyles.body).clickable(role = Role.Button) { open = true },
            tint = SystemColors.label,
        )
        SystemMenu(
            expanded = open,
            onDismissRequest = { open = false },
            sections = listOf(
                SystemMenuSection(
                    listOf(
                        SystemMenuItem("Show in Drawer", systemImage = if (whatsNew.state.isButtonEnabled) "checkmark" else null) {
                            open = false
                            whatsNew.send(WhatsNewFeature.Action.buttonEnabledChanged(!whatsNew.state.isButtonEnabled))
                        },
                    ),
                ),
            ),
        )
    }
}

/// A settings row: a tinted symbol tile, the title, and a quiet value.
@Composable
fun SettingsRow(title: String, symbol: String, color: Color, value: String? = null) {
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(29.dp)
                .background(Brush.verticalGradient(listOf(color.lighten(0.12f), color)), RoundedCornerShape(7.dp))
                .clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
        ) {
            SystemImage(symbol, contentDescription = null, modifier = Modifier.symbolSize(TextStyle(fontSize = 14.sp).semibold), tint = Color.White)
        }
        Text(title, Modifier.weight(1f).listRowSeparatorLeading(), style = TextStyles.body, color = SystemColors.label, maxLines = 1)
        if (value != null) Text(value, style = TextStyles.body, color = SystemColors.secondaryLabel, maxLines = 1)
    }
}

/// SwiftUI's `Color.gradient`: the color, a touch lighter at the top.
private fun Color.lighten(amount: Float): Color =
    Color(red + (1 - red) * amount, green + (1 - green) * amount, blue + (1 - blue) * amount, alpha)

/// The server, as a living card: a dot field in the connection's color that
/// drifts while connected, quickens while reconnecting, and rests when
/// offline (and under Reduce Motion).
@Composable
fun SettingsServerHeader(
    host: String,
    status: HomeFeature.State.ConnectionStatus,
    error: String?,
    onRetry: () -> Unit,
    onChange: () -> Unit,
) {
    val reduceMotion = LocalReduceMotion.current
    val dark = isSystemInDarkTheme()
    val tint = when (status) {
        HomeFeature.State.ConnectionStatus.connected -> SystemColors.systemGreen
        HomeFeature.State.ConnectionStatus.reconnecting -> SystemColors.systemOrange
        HomeFeature.State.ConnectionStatus.disconnected -> SystemColors.systemGray
    }
    val statusText = when (status) {
        HomeFeature.State.ConnectionStatus.connected -> "Connected"
        HomeFeature.State.ConnectionStatus.reconnecting -> "Reconnecting…"
        HomeFeature.State.ConnectionStatus.disconnected -> "Offline"
    }
    val phase = if (reduceMotion) {
        0f
    } else {
        when (status) {
            HomeFeature.State.ConnectionStatus.connected -> 1f
            HomeFeature.State.ConnectionStatus.reconnecting -> 2.4f
            HomeFeature.State.ConnectionStatus.disconnected -> 0f
        }
    }
    val time = settingsAuraTime(paused = phase == 0f)
    /// The machine's name: a host name's first label ("basebook" of
    /// basebook.tail1234.ts.net:4443), or the whole address for an IP.
    val name = machineName(host)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .settingsAura(time = time, tint = tint, phase = phase, dark = dark)
            .padding(horizontal = 18.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Status and actions share the top row, so the name below gets the
        // card's full width instead of being cut to fit beside a button.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).background(tint, CircleShape))
                Text(statusText, style = TextStyles.subheadline.semibold, color = tint)
            }
            Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
            if (status != HomeFeature.State.ConnectionStatus.connected) {
                GlassButton("Retry", enabled = error == null, action = onRetry)
                Spacer(Modifier.width(8.dp))
            }
            GlassButton("Change", accessibilityLabel = "Change Server", action = onChange)
        }
        Column(
            Modifier.clearAndSetSemantics { contentDescription = "Server $host" },
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ScaledLine(name, style = TextStyles.title2.bold, minimumScaleFactor = 0.7f)
            if (name != host) {
                Text(
                    host,
                    style = TextStyles.footnote.copy(fontFamily = FontFamily.Monospace),
                    color = SystemColors.secondaryLabel,
                    maxLines = 2,
                    overflow = TextOverflow.MiddleEllipsis,
                )
            }
        }
        if (error != null && status != HomeFeature.State.ConnectionStatus.connected) {
            SelectionContainer {
                Text(error, style = TextStyles.footnote, color = SystemColors.secondaryLabel, maxLines = 3)
            }
        }
    }
}

fun machineName(host: String): String {
    val address = host.split(":", limit = 2).first()
    val labels = address.split(".").filter { it.isNotEmpty() }
    if (labels.size <= 1 || address.all { it.isDigit() || it == '.' }) return host
    return labels.first()
}

/// One line that shrinks to `minimumScaleFactor` before it truncates in the middle.
@Composable
private fun ScaledLine(text: String, style: TextStyle, minimumScaleFactor: Float) {
    var scale by remember(text) { mutableStateOf(1f) }
    Text(
        text,
        style = style.copy(fontSize = style.fontSize * scale, lineHeight = style.lineHeight * scale),
        color = SystemColors.label,
        maxLines = 1,
        overflow = if (scale <= minimumScaleFactor) TextOverflow.MiddleEllipsis else TextOverflow.Clip,
        onTextLayout = { layout ->
            if (layout.hasVisualOverflow && scale > minimumScaleFactor) scale = maxOf(minimumScaleFactor, scale - 0.05f)
        },
    )
}

/// `.buttonStyle(.glass)`.
@Composable
private fun GlassButton(title: String, enabled: Boolean = true, accessibilityLabel: String? = null, action: () -> Unit) {
    Text(
        title,
        Modifier
            .alpha(if (enabled) 1f else 0.4f)
            .glassEffect(GlassSurface.Capsule, interactive = true)
            .clickable(enabled = enabled, role = Role.Button, onClick = action)
            .padding(horizontal = 14.dp, vertical = 7.dp)
            .semantics { if (accessibilityLabel != null) contentDescription = accessibilityLabel },
        style = TextStyles.body,
        color = SystemColors.label,
        maxLines = 1,
    )
}

/// Voice: its own page, with the call's dot scope at rest above the switch,
/// model, and key.
@Composable
fun VoiceSettingsView() {
    AdvancedSettingsView()
    NavigationTitle("Voice", displayMode = TitleDisplayMode.inline)
}

/// The Voice page's content: the switch, the model, and the key.
@Composable
fun AdvancedSettingsView() {
    val realtimePreflight by Dependency(DependencyValues::realtimePreflight)
    var voiceModeEnabled by remember<Shared<Boolean>> { Shared(SharedKey.voiceModeEnabled) }
    var voiceEngine by remember<Shared<VoiceEngine>> { Shared(SharedKey.voiceEngine) }
    var openAIAPIKey by remember<Shared<String?>> { Shared(SharedKey.openAIAPIKey) }
    var verifiedKeyFingerprint by remember<Shared<String?>> { Shared(SharedKey.verifiedOpenAIKeyFingerprint) }
    var revealsKey by remember { mutableStateOf(false) }
    var verificationStatus by remember { mutableStateOf<KeyVerificationStatus>(KeyVerificationStatus.idle) }
    var verifyRequest by remember { mutableIntStateOf(0) }
    var picksEngine by remember { mutableStateOf(false) }
    val apiKeyValue = (openAIAPIKey ?: "").trim()

    GroupedList(sectionSpacing = ListSectionSpacing.compact, scrollContentBackground = false) {
        Section(
            footer = {
                Text("Talk to your sessions hands-free. Voice calls stream your speech to OpenAI with your own project key. The key stays in this phone’s keychain and is never sent to your OpenCode server.")
            },
        ) {
            ForEach(listOf("voice-mode"), { it }) {
                SettingsToggle("Enable Voice", isOn = voiceModeEnabled, onChange = { voiceModeEnabled = it })
            }
            if (voiceModeEnabled) {
                ForEach(listOf("voice-model"), { it }) {
                    Box {
                        Row(
                            Modifier.fillMaxWidth().clickable(role = Role.Button) { picksEngine = true },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Voice Model", Modifier.weight(1f).listRowSeparatorLeading(), style = TextStyles.body, color = SystemColors.label)
                            Text(voiceEngine.title, style = TextStyles.body, color = SystemColors.secondaryLabel)
                            SystemImage(
                                "chevron.up.chevron.down",
                                contentDescription = null,
                                modifier = Modifier.padding(start = 4.dp).symbolSize(TextStyles.footnote),
                                tint = SystemColors.secondaryLabel,
                            )
                        }
                        SystemMenu(
                            expanded = picksEngine,
                            onDismissRequest = { picksEngine = false },
                            sections = listOf(
                                SystemMenuSection(
                                    VoiceEngine.entries.map { engine ->
                                        SystemMenuItem(engine.title, systemImage = if (engine == voiceEngine) "checkmark" else null) {
                                            picksEngine = false
                                            voiceEngine = engine
                                        }
                                    },
                                ),
                            ),
                        )
                    }
                }
                ForEach(listOf("api-key"), { it }) {
                    val apiKeyBinding = { value: String ->
                        val trimmed = value.trim()
                        verifyRequest = 0
                        verificationStatus = KeyVerificationStatus.idle
                        openAIAPIKey = trimmed.ifEmpty { null }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        BasicTextField(
                            value = openAIAPIKey ?: "",
                            onValueChange = apiKeyBinding,
                            modifier = Modifier.weight(1f).listRowSeparatorLeading().semantics { contentDescription = "OpenAI API key" },
                            singleLine = true,
                            textStyle = TextStyles.body.copy(color = SystemColors.label),
                            cursorBrush = SolidColor(SystemColors.tint),
                            visualTransformation = if (revealsKey) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.None,
                                autoCorrectEnabled = false,
                                keyboardType = if (revealsKey) KeyboardType.Ascii else KeyboardType.Password,
                            ),
                            decorationBox = { field ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if ((openAIAPIKey ?: "").isEmpty()) Text("OpenAI API key", style = TextStyles.body, color = SystemColors.placeholderText)
                                    field()
                                }
                            },
                        )
                        Box(
                            Modifier
                                .size(44.dp)
                                .clickable(role = Role.Button) { revealsKey = !revealsKey }
                                .semantics { contentDescription = if (revealsKey) "Hide key" else "Show key" },
                            contentAlignment = Alignment.Center,
                        ) {
                            SystemImage(if (revealsKey) "eye.slash" else "eye", contentDescription = null, modifier = Modifier.symbolSize(TextStyles.body), tint = SystemColors.secondaryLabel)
                        }
                        val canVerify = apiKeyValue.isNotEmpty() && verificationStatus != KeyVerificationStatus.checking
                        // `.buttonStyle(.bordered).controlSize(.small)`
                        Box(Modifier.heightIn(min = 44.dp), contentAlignment = Alignment.Center) {
                            Text(
                                "Verify",
                                Modifier
                                    .background(if (canVerify) SystemColors.tint.copy(alpha = 0.15f) else SystemColors.quaternarySystemFill, CircleShape)
                                    .clickable(enabled = canVerify, role = Role.Button) { verifyRequest += 1 }
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                                style = TextStyles.subheadline,
                                color = if (canVerify) SystemColors.tint else SystemColors.tertiaryLabel,
                            )
                        }
                    }
                }
                if (verificationStatus != KeyVerificationStatus.idle) {
                    ForEach(listOf("verification"), { it }) { KeyVerificationStatusRow(verificationStatus) }
                }
            }
        }
    }
    LaunchedEffect("$voiceModeEnabled|${voiceEngine.name}|$apiKeyValue|$verifyRequest") {
        if (!voiceModeEnabled) return@LaunchedEffect
        val debounce = verifyRequest == 0
        val key = apiKeyValue
        val model = voiceEngine.modelID
        if (key.isEmpty()) {
            verificationStatus = KeyVerificationStatus.idle
            return@LaunchedEffect
        }
        val fingerprint = keyFingerprint(key, model = model)
        if (debounce && verifiedKeyFingerprint == fingerprint) {
            verificationStatus = KeyVerificationStatus.verified
            return@LaunchedEffect
        }
        try {
            if (debounce) delay(800)
            verificationStatus = KeyVerificationStatus.checking
            realtimePreflight.verify(key, model)
            if ((openAIAPIKey ?: "").trim() != key || voiceEngine.modelID != model) return@LaunchedEffect
            verifiedKeyFingerprint = fingerprint
            verificationStatus = KeyVerificationStatus.verified
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if ((openAIAPIKey ?: "").trim() != key || voiceEngine.modelID != model) return@LaunchedEffect
            verificationStatus = KeyVerificationStatus.failed(error.message ?: "OpenAI couldn’t verify this key.")
        }
    }
}

private sealed interface KeyVerificationStatus {
    data object idle : KeyVerificationStatus

    data object checking : KeyVerificationStatus

    data object verified : KeyVerificationStatus

    data class failed(val message: String) : KeyVerificationStatus
}

@Composable
private fun KeyVerificationStatusRow(status: KeyVerificationStatus) {
    when (status) {
        KeyVerificationStatus.idle -> Unit
        KeyVerificationStatus.checking -> Row(
            Modifier.semantics(mergeDescendants = true) {},
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(18.dp), color = SystemColors.secondaryLabel, strokeWidth = 2.dp)
            Text("Checking key…", Modifier.listRowSeparatorLeading(), style = TextStyles.body, color = SystemColors.secondaryLabel)
        }
        KeyVerificationStatus.verified -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SystemImage("checkmark.circle.fill", contentDescription = null, modifier = Modifier.symbolSize(TextStyles.body), tint = SystemColors.systemGreen)
            Text("Key works", Modifier.listRowSeparatorLeading(), style = TextStyles.body, color = SystemColors.systemGreen)
        }
        is KeyVerificationStatus.failed -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SystemImage("xmark.circle.fill", contentDescription = null, modifier = Modifier.symbolSize(TextStyles.body), tint = SystemColors.systemRed)
            SelectionContainer {
                Text(status.message, Modifier.listRowSeparatorLeading(), style = TextStyles.body, color = SystemColors.systemRed)
            }
        }
    }
}

private fun keyFingerprint(key: String, model: String): String =
    MessageDigest.getInstance("SHA-256").digest("$model:$key".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/// `NavigationLink { … } label: { … }`: the label and a disclosure chevron.
@Composable
private fun NavigationLinkRow(push: () -> Unit, label: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = push),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) { label() }
        SystemImage("chevron.right", contentDescription = null, modifier = Modifier.padding(start = 8.dp).symbolSize(TextStyles.footnote), tint = SystemColors.tertiaryLabel)
    }
}

/// `Toggle(title, isOn:)`, the platform switch in iOS's colors.
@Composable
private fun SettingsToggle(title: String, isOn: Boolean, onChange: (Boolean) -> Unit) {
    val dark = isSystemInDarkTheme()
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Switch) { onChange(!isOn) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f).listRowSeparatorLeading(), style = TextStyles.body, color = SystemColors.label)
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = isOn,
            onCheckedChange = onChange,
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

/// A pushed screen's stack: its bar with the back button in the leading slot.
@Composable
private fun PushedScreen(onBack: () -> Unit, trailing: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    NavigationStack(Modifier.fillMaxWidth()) {
        Toolbar {
            ToolbarItem(placement = ToolbarItemPlacement.topBarLeading) {
                SystemImage(
                    "chevron.left",
                    contentDescription = "Back",
                    modifier = Modifier.symbolSize(TextStyles.body).clickable(role = Role.Button, onClick = onBack),
                    tint = SystemColors.label,
                )
            }
            if (trailing != null) {
                ToolbarItem(placement = ToolbarItemPlacement.topBarTrailing) { trailing() }
            }
        }
        content()
    }
}

/// The leading toolbar slot's back button, over a screen that draws its own bar.
@Composable
private fun BackButton(modifier: Modifier, onBack: () -> Unit) {
    val dark = isSystemInDarkTheme()
    Box(modifier, contentAlignment = Alignment.CenterStart) {
        Box(
            Modifier
                .heightIn(min = 44.dp)
                .then(if (dark) Modifier else Modifier.shadow(1.dp, GlassSurface.Capsule))
                .glassEffect(GlassSurface.Capsule, interactive = true, tint = if (dark) Color.Unspecified else SystemColors.systemBackground)
                .clickable(role = Role.Button, onClick = onBack)
                .padding(horizontal = 16.dp)
                .semantics { contentDescription = "Back" },
            contentAlignment = Alignment.Center,
        ) {
            SystemImage("chevron.left", contentDescription = null, modifier = Modifier.symbolSize(TextStyles.body), tint = SystemColors.label)
        }
    }
}

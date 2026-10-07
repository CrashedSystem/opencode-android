@file:PortedFrom("OpenCode/SettingsView.swift", "553a407")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.GlassSurface
import ai.opencode.mobilecode.system.GroupedList
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
import ai.opencode.mobilecode.system.TitleDisplayMode
import ai.opencode.mobilecode.system.Toolbar
import ai.opencode.mobilecode.system.ToolbarItem
import ai.opencode.mobilecode.system.ToolbarItemPlacement
import ai.opencode.mobilecode.system.glassEffect
import ai.opencode.mobilecode.system.listRowSeparatorLeading
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    reports: Store<ProblemReportsFeature.State, ProblemReportsFeature.Action>? = null,
    whatsNew: Store<WhatsNewFeature.State, WhatsNewFeature.Action>,
) {
    val dismiss = LocalDismiss.current
    // `NavigationLink`'s pushes; the system layer has no navigation path yet.
    var pushed by rememberSaveable { mutableStateOf<SettingsDestination?>(null) }
    BackHandler(enabled = pushed != null) { pushed = null }
    Box(Modifier.fillMaxWidth().hidesFromProblemReports()) {
        when (pushed) {
            SettingsDestination.advanced -> PushedScreen(onBack = { pushed = null }) { AdvancedSettingsView() }
            SettingsDestination.diagnostics -> {
                // Diagnostics draws its own bar; the back button sits in its leading slot.
                Box(Modifier.fillMaxSize()) {
                    DiagnosticsView()
                    BackButton(Modifier.padding(start = 16.dp).height(56.dp)) { pushed = null }
                }
            }
            SettingsDestination.whatsNew -> PushedScreen(onBack = { pushed = null }) { WhatsNewList(store = whatsNew) }
            null -> NavigationStack(Modifier.fillMaxWidth()) {
                NavigationTitle("Settings", displayMode = TitleDisplayMode.inline)
                Toolbar {
                    ToolbarItem(placement = ToolbarItemPlacement.confirmationAction) {
                        SystemButton("Done") { dismiss() }
                    }
                }
                GroupedList(scrollContentBackground = false) {
                    Section(header = { Text("Server") }) {
                        ForEach(listOf("server"), { it }) {
                            Row(
                                Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    serverHost,
                                    Modifier.weight(1f, fill = false).listRowSeparatorLeading(),
                                    style = TextStyles.body,
                                    color = SystemColors.label,
                                    maxLines = 1,
                                    overflow = TextOverflow.MiddleEllipsis,
                                )
                                Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
                                when (connectionStatus) {
                                    HomeFeature.State.ConnectionStatus.connected ->
                                        Text("Connected", style = TextStyles.body, color = SystemColors.secondaryLabel)
                                    HomeFeature.State.ConnectionStatus.reconnecting ->
                                        CircularProgressIndicator(Modifier.size(18.dp), color = SystemColors.secondaryLabel, strokeWidth = 2.dp)
                                    HomeFeature.State.ConnectionStatus.disconnected ->
                                        Text("Offline", style = TextStyles.body, color = SystemColors.secondaryLabel)
                                }
                            }
                        }
                        if (connectionStatus != HomeFeature.State.ConnectionStatus.connected) {
                            if (connectionErrorMessage != null) {
                                ForEach(listOf("error"), { it }) {
                                    SelectionContainer {
                                        Text(connectionErrorMessage, Modifier.listRowSeparatorLeading(), style = TextStyles.footnote, color = SystemColors.secondaryLabel)
                                    }
                                }
                            }
                            ForEach(listOf("retry"), { it }) {
                                SettingsLabel("Retry Connection", "arrow.clockwise", enabled = connectionErrorMessage == null, action = onRetryConnection)
                            }
                        }
                        ForEach(listOf("change-server"), { it }) {
                            SettingsLabel("Change Server", "server.rack") {
                                dismiss()
                                onChangeServer()
                            }
                        }
                    }

                    Section(
                        footer = {
                            Text(
                                if (InternalBuild.isEnabled) {
                                    "Test builds record diagnostics by default and keep the last 15 minutes on this phone."
                                } else {
                                    "Diagnostics recording is off by default and keeps at most the last 10 minutes on this phone."
                                },
                            )
                        },
                    ) {
                        ForEach(listOf("advanced"), { it }) {
                            NavigationLinkRow("Advanced", hint = "Experimental features including Voice Mode") { pushed = SettingsDestination.advanced }
                        }
                        ForEach(listOf("diagnostics"), { it }) {
                            NavigationLinkRow("Diagnostics", hint = "Inspect and share on-device timing and network metadata") {
                                pushed = SettingsDestination.diagnostics
                            }
                        }
                        if (reports != null) {
                            ForEach(listOf("reports"), { it }) { ProblemReportSettingsRows(store = reports) }
                        }
                    }

                    // Below Diagnostics, so Report Problem stays within the half-height sheet.
                    Section {
                        ForEach(listOf("whats-new"), { it }) {
                            NavigationLinkRow("What’s New") { pushed = SettingsDestination.whatsNew }
                        }
                        ForEach(listOf("show-whats-new"), { it }) {
                            SettingsToggle(
                                title = "Show What’s New",
                                isOn = whatsNew.state.isButtonEnabled,
                                onChange = { whatsNew.send(WhatsNewFeature.Action.buttonEnabledChanged(it)) },
                            )
                        }
                    }
                }
            }
        }
    }
}

private enum class SettingsDestination { advanced, diagnostics, whatsNew }

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

    GroupedList(scrollContentBackground = false) {
        Section(
            header = { Text("Voice") },
            footer = {
                Text("Voice calls stream your speech to OpenAI with your own project key. The key stays in this phone’s keychain and is never sent to your OpenCode server.")
            },
        ) {
            ForEach(listOf("voice-mode"), { it }) {
                SettingsToggle("Enable Voice Mode", isOn = voiceModeEnabled, onChange = { voiceModeEnabled = it })
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
    NavigationTitle("Advanced", displayMode = TitleDisplayMode.inline)
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

/// `Label { Text(title) } icon: { Image(systemName:).foregroundStyle(.secondary) }`
/// as a button, in the section's `.tint(.primary)`.
@Composable
private fun SettingsLabel(title: String, systemImage: String, enabled: Boolean = true, action: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled, role = Role.Button, onClick = action),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SystemImage(systemImage, contentDescription = null, modifier = Modifier.symbolSize(TextStyles.body), tint = SystemColors.secondaryLabel)
        Text(title, Modifier.listRowSeparatorLeading(), style = TextStyles.body, color = SystemColors.label)
    }
}

/// `NavigationLink(title) { … }`: the title and a disclosure chevron.
@Composable
private fun NavigationLinkRow(title: String, hint: String? = null, push: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = push)
            .semantics { if (hint != null) stateDescription = hint },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f).listRowSeparatorLeading(), style = TextStyles.body, color = SystemColors.label)
        SystemImage("chevron.right", contentDescription = null, modifier = Modifier.symbolSize(TextStyles.footnote), tint = SystemColors.tertiaryLabel)
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
private fun PushedScreen(onBack: () -> Unit, content: @Composable () -> Unit) {
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

package ai.opencode.feature.settings

import ai.opencode.core.experiments.ExperimentalFeature
import ai.opencode.core.experiments.key
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.observe
import ai.opencode.core.store.scope
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeIconSize
import ai.opencode.core.ui.OpenCodeRadius
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.openCodeTapTarget
import ai.opencode.feature.voice.CallVoice
import ai.opencode.feature.voice.ServerVoiceProvider
import ai.opencode.feature.voice.VoiceCallLog
import ai.opencode.feature.voice.VoiceCallLogList
import ai.opencode.feature.voice.VoiceCallReplayView
import ai.opencode.feature.voice.VoiceEngine
import ai.opencode.feature.voice.VoiceProvider
import ai.opencode.feature.voice.VoiceRoute
import ai.opencode.feature.voice.VoiceServerChoice
import ai.opencode.feature.voice.VoiceCallSoundPack
import ai.opencode.feature.voice.VoiceSharedKeys
import ai.opencode.server.ServerSharedKeys
import ai.opencode.core.motion.MotionRole
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

internal typealias VoiceStore = StoreOf<VoiceSettingsFeature.State, VoiceSettingsFeature.Action>

@Composable
internal fun rememberVoiceStore(store: SettingsStore): VoiceStore = remember(store) {
    store.scope<SettingsFeature.State, SettingsFeature.Action, VoiceSettingsFeature.State, VoiceSettingsFeature.Action>(
        state = { it.voice },
        action = { SettingsFeature.Action.Voice(it) },
    )
}

/**
 * Collects every shared value the Voice page reads so it recomposes when one changes (the
 * reducer's state reads them live; `State` equality doesn't include them).
 */
@Composable
internal fun rememberVoiceSharedRevision(): Any {
    val values = listOf(
        remember { shared(SharedKeys.voiceModeEnabled).flow }.collectAsState().value,
        remember { shared(VoiceSharedKeys.voiceEngine).flow }.collectAsState().value,
        remember { shared(VoiceSharedKeys.callVoice).flow }.collectAsState().value,
        remember { shared(SharedKeys.raiseToTalk).flow }.collectAsState().value,
        remember { shared(SharedKeys.openAIAPIKey).flow }.collectAsState().value,
        remember { shared(SharedKeys.xaiAPIKey).flow }.collectAsState().value,
        remember { shared(SharedKeys.verifiedVoiceKeyFingerprint).flow }.collectAsState().value,
        remember { shared(VoiceSharedKeys.serverVoiceProvider).flow }.collectAsState().value,
        remember { shared(VoiceSharedKeys.serverVoiceSupportByServer).flow }.collectAsState().value,
        remember { shared(VoiceSharedKeys.voiceServerChoice).flow }.collectAsState().value,
        remember { shared(ExperimentalFeature.voiceSessionDelegator.key).flow }.collectAsState().value,
        remember { shared(VoiceSharedKeys.voiceDelegatorChoice).flow }.collectAsState().value,
        remember { shared(VoiceSharedKeys.voiceModelsByServer).flow }.collectAsState().value,
        remember { shared(VoiceSharedKeys.callSoundPack).flow }.collectAsState().value,
        remember { shared(ServerSharedKeys.cachedHomeSnapshots).flow }.collectAsState().value,
    )
    return values
}

/**
 * Port of iOS `VoiceSettingsView` / `AdvancedSettingsView` (`Settings/SettingsView.swift`):
 * Settings › Voice. 1. Enable Voice; 2. the Voice Provider row and Model; for Grok, Model (provider
 * plus model) and its xAI Key row. Then Voice; 3. Raise to Talk and Call History.
 */
@Composable
fun VoiceSettingsView(store: SettingsStore) {
    val voice = rememberVoiceStore(store)
    val local by voice.observe { it }
    @Suppress("UNUSED_VARIABLE") val revision = rememberVoiceSharedRevision()
    val state = local
    val reduceMotion = rememberReduceMotion()
    val settings = { action: HomeSettingsFeature.Action -> store.send(SettingsFeature.Action.Settings(action)) }
    LaunchedEffect(voice) { voice.send(VoiceSettingsFeature.Action.Task) }
    // The server that brokers calls, asked again when it changes.
    val brokerID = state.broker.server?.persistenceID
    LaunchedEffect(brokerID) {
        if (brokerID != null) settings(HomeSettingsFeature.Action.CheckServerVoiceSupport)
    }
    val keySource = state.keySource
    LaunchedEffect(keySource) { voice.send(VoiceSettingsFeature.Action.RouteChanged) }
    val showsRaiseToTalk = remember { ai.opencode.core.dependencies.dependency(ai.opencode.feature.voice.RaiseToEarClient).isSupported() }

    SettingsPageScaffold(title = "Voice", store = store) {
        item(key = "scope") {
            ai.opencode.feature.voice.DotScopeView(
                mode = ai.opencode.feature.voice.DotScopeMode.Preview(active = state.voiceModeEnabled),
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .fillMaxWidth()
                    .height(96.dp)
                    .clip(RoundedCornerShape(OpenCodeRadius.tile))
                    .clearAndSetSemantics {},
            )
        }
        settingsSection(
            key = "enable",
            footer = if (!state.voiceModeEnabled) footerText(keySource.footer) else null,
        ) {
            SettingsToggleRow(
                "Enable Voice",
                checked = state.voiceModeEnabled,
                onCheckedChange = {
                    voice.send(VoiceSettingsFeature.Action.VoiceModeEnabledChanged(it))
                    settings(HomeSettingsFeature.Action.VoiceModeEnabledChanged(it))
                },
            )
        }
        if (state.voiceModeEnabled) {
            settingsSection(
                key = "configuration",
                footer = { SettingsFooterText(keySource.footer) },
            ) {
                var first = true
                fun divider() = if (first) { first = false; false } else true
                if (state.showsProviderRow) {
                    divider()
                    VoiceProviderSettingsRow(state) { store.send(SettingsFeature.Action.PagePushed(SettingsPage.VoiceProvider())) }
                }
                val route = state.route
                if (route != VoiceRoute.Server(VoiceRoute.ServerRoute.GeminiLive)) {
                    if (divider()) SettingsDivider()
                    SettingsMenuRow(
                        title = "Model",
                        value = state.modelRowValue,
                        selection = state.voiceEngine,
                        sections = VoiceProvider.allCases.map { provider ->
                            provider.displayName to provider.engines.map { SettingsMenuOption(it, it.title) }
                        },
                        onSelect = {
                            voice.send(VoiceSettingsFeature.Action.VoiceEngineChanged(it))
                            settings(HomeSettingsFeature.Action.VoiceEngineChanged(it))
                        },
                        accessibilityLabel = "Voice Model",
                    )
                }
                if (!state.showsProviderRow) {
                    if (divider()) SettingsDivider()
                    KeyRow(state, reduceMotion) { voice.send(VoiceSettingsFeature.Action.KeyRowTapped) }
                    // iOS inserts the editor on the layout role; under Reduce Motion it fades in place.
                    AnimatedVisibility(
                        visible = state.isEditingKey && keySource.isPhoneKey,
                        enter = keyEditorEnter(reduceMotion),
                        exit = keyEditorExit(reduceMotion),
                    ) {
                        Column {
                            SettingsDivider()
                            VoiceKeyEditor(state, voice, settings)
                        }
                    }
                }
                if (divider()) SettingsDivider()
                SettingsRow(
                    "Voice",
                    symbol = null,
                    value = CallVoicePicker.rowValue(state.callVoice, state.voiceEngine),
                ) { store.send(SettingsFeature.Action.PagePushed(SettingsPage.CallVoicePicker)) }
                if (state.showsDelegatorRow) {
                    SettingsDivider()
                    VoiceDelegatorSettingsRow(state) { store.send(SettingsFeature.Action.PagePushed(SettingsPage.VoiceDelegator)) }
                }
            }
        }
        settingsSection(key = "options") {
            if (state.voiceModeEnabled && showsRaiseToTalk) {
                SettingsToggleRow(
                    "Raise to Talk",
                    checked = state.raiseToTalk,
                    onCheckedChange = {
                        voice.send(VoiceSettingsFeature.Action.RaiseToTalkChanged(it))
                        settings(HomeSettingsFeature.Action.RaiseToTalkChanged(it))
                    },
                    // iOS `accessibilityHint`: TalkBack's click label (“Double tap to …”).
                    modifier = Modifier.semantics {
                        onClick(label = "Raise your phone to your ear on a session to start a call through the earpiece") {
                            val on = !state.raiseToTalk
                            voice.send(VoiceSettingsFeature.Action.RaiseToTalkChanged(on))
                            settings(HomeSettingsFeature.Action.RaiseToTalkChanged(on))
                            true
                        }
                    },
                )
                SettingsDivider()
            }
            if (state.voiceModeEnabled) {
                SettingsMenuRow(
                    title = "Call Sounds",
                    value = state.callSoundPack.title,
                    selection = state.callSoundPack,
                    sections = listOf(null to VoiceCallSoundPack.entries.map { SettingsMenuOption(it, it.title) }),
                    onSelect = { voice.send(VoiceSettingsFeature.Action.CallSoundPackSelected(it)) },
                )
                SettingsDivider()
            }
            SettingsRow("Call History", symbol = null) { store.send(SettingsFeature.Action.PagePushed(SettingsPage.CallHistory)) }
        }
    }
}

@Composable
private fun KeyRow(state: VoiceSettingsFeature.State, reduceMotion: Boolean, onTap: () -> Unit) {
    if (!state.keySource.isPhoneKey) {
        SettingsValueRow(state.keyRowTitle, state.keyBadgeTitle)
        return
    }
    val rotation by animateFloatAsState(
        if (state.isEditingKey) 180f else 0f,
        if (reduceMotion) snap() else MotionRole.Layout.animation(),
        label = "key-chevron",
    )
    val stacked = isAccessibilitySize()
    SettingsRowFrame(
        Modifier.semantics(mergeDescendants = true) {
            contentDescription = state.keyRowTitle
            stateDescription = state.keyBadgeTitle
            onClick(label = "Edit your ${state.activeProvider.displayName} API key") { onTap(); true }
        },
        onClick = onTap,
    ) {
        val title = @Composable { Text(state.keyRowTitle, style = SettingsText.title, color = OpenCodeTheme.colors.text) }
        if (stacked) {
            // iOS at accessibility sizes: the status goes under the title.
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                title()
                KeyStatusAccessory(state, rotation)
            }
        } else {
            Box(Modifier.weight(1f)) { title() }
            KeyStatusAccessory(state, rotation)
        }
    }
}

@Composable
private fun KeyStatusAccessory(state: VoiceSettingsFeature.State, rotation: Float) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        val secondary = OpenCodeTheme.colors.secondaryText
        when (val status = state.keyStatus) {
            VoiceKeyStatus.Verified -> {
                OpenCodeIcon(SFSymbol.CheckmarkCircleFill, contentDescription = null, size = 18.dp, tint = SettingsTint.Green.color())
                Text("Verified", style = SettingsText.title, color = secondary)
            }
            VoiceKeyStatus.Checking -> {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("Checking…", style = SettingsText.title, color = secondary)
            }
            VoiceKeyStatus.Saved -> Text("Saved", style = SettingsText.title, color = secondary)
            is VoiceKeyStatus.Invalid -> {
                OpenCodeIcon(SFSymbol.ExclamationmarkCircleFill, contentDescription = null, size = 18.dp, tint = SettingsTint.Orange.color())
                Text("Saved", style = SettingsText.title, color = secondary)
            }
            VoiceKeyStatus.Missing -> Text("Missing", style = SettingsText.title, color = secondary)
            is VoiceKeyStatus.OnServer -> Text(status.providerName, style = SettingsText.title, color = secondary)
        }
        // Android divergence: an expand arrow that flips open (iOS turns a trailing chevron.right).
        OpenCodeIcon(
            SFSymbol.ChevronDown,
            contentDescription = null,
            size = OpenCodeIconSize.medium,
            tint = OpenCodeTheme.colors.secondaryText,
            modifier = Modifier.rotate(rotation),
        )
    }
}

/** The key editor opens on the layout role (iOS `keyRow`); under Reduce Motion, the reduced fade. */
private fun keyEditorEnter(reduceMotion: Boolean): EnterTransition {
    if (reduceMotion) return fadeIn(MotionRole.Layout.animation(reduceMotion = true))
    return expandVertically(MotionRole.Layout.animation()) + fadeIn(MotionRole.Layout.animation())
}

private fun keyEditorExit(reduceMotion: Boolean): ExitTransition {
    if (reduceMotion) return fadeOut(MotionRole.Layout.animation(reduceMotion = true))
    return shrinkVertically(MotionRole.Layout.animation()) + fadeOut(MotionRole.Layout.animation())
}

/** Settings → Voice → Voice: the voices the selected model offers, each with a cached TTS preview. */
object CallVoicePicker {
    /** The row's value: the voice calls will use, and the saved one when the model doesn't offer it. */
    fun rowValue(saved: CallVoice, engine: VoiceEngine): String {
        val voice = saved.resolved(engine)
        return if (isMissing(saved, engine)) "${voice.title} · ${saved.title} unavailable" else voice.title
    }

    /** A saved voice from the engine's own provider that the engine lacks. */
    fun isMissing(saved: CallVoice, engine: VoiceEngine): Boolean =
        saved.provider == engine.provider && !saved.isSupported(engine)
}

@Composable
fun CallVoicePickerView(store: SettingsStore) {
    val voice = rememberVoiceStore(store)
    val local by voice.observe { it }
    @Suppress("UNUSED_VARIABLE") val revision = rememberVoiceSharedRevision()
    val state = local
    val engine = state.voiceEngine
    val selected = state.callVoice.resolved(engine)
    DisposableEffect(voice) { onDispose { voice.send(VoiceSettingsFeature.Action.VoicePickerDisappeared) } }
    val settings = { action: HomeSettingsFeature.Action -> store.send(SettingsFeature.Action.Settings(action)) }
    val footer = when {
        !state.canPreviewVoices -> "Add your ${state.activeProvider.displayName} key in Voice settings to preview voices."
        CallVoicePicker.isMissing(state.callVoice, engine) ->
            "${state.callVoice.title} isn’t available on ${engine.title}, so calls use ${selected.title}. Applies to your next call."
        else -> "Applies to your next call."
    }

    SettingsPageScaffold(title = "Voice", store = store) {
        state.previewError?.let { error ->
            settingsSection(key = "error") {
                SettingsRowFrame {
                    OpenCodeIcon(SFSymbol.ExclamationmarkCircleFill, contentDescription = null, size = 18.dp, tint = OpenCodeTheme.colors.destructive)
                    Text(error, style = OpenCodeTextStyles.subheadline, color = OpenCodeTheme.colors.destructive)
                }
            }
        }
        settingsSection(key = "voices", footer = footerText(footer)) {
            CallVoice.supported(engine).forEachIndexed { index, option ->
                if (index > 0) SettingsDivider()
                VoiceRow(
                    voice = option,
                    isSelected = option == selected,
                    isPlaying = state.previewingVoice == option,
                    canPreview = state.canPreviewVoices,
                    previewHint = state.previewDisabledHint,
                    onSelect = {
                        voice.send(VoiceSettingsFeature.Action.CallVoiceSelected(option))
                        settings(HomeSettingsFeature.Action.CallVoiceChanged(option))
                    },
                    onPreview = { voice.send(VoiceSettingsFeature.Action.PreviewVoiceTapped(option)) },
                )
            }
        }
    }
}

@Composable
private fun VoiceRow(
    voice: CallVoice,
    isSelected: Boolean,
    isPlaying: Boolean,
    canPreview: Boolean,
    previewHint: String?,
    onSelect: () -> Unit,
    onPreview: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SettingsRowFrame(
            Modifier.weight(1f).semantics(mergeDescendants = true) { selected = isSelected },
            onClick = onSelect,
            role = Role.RadioButton,
        ) {
            Column(Modifier.weight(1f)) {
                Text(voice.title, style = SettingsText.title, color = OpenCodeTheme.colors.text)
                voice.accent?.let { Text(it, style = SettingsText.summary, color = OpenCodeTheme.colors.secondaryText) }
            }
            OpenCodeIcon(
                SFSymbol.Checkmark,
                contentDescription = null,
                size = 20.dp,
                tint = if (isSelected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
            )
        }
        val accent = MaterialTheme.colorScheme.primary
        Box(
            Modifier
                .padding(end = 8.dp)
                .clickable(enabled = canPreview, role = Role.Button, onClick = onPreview)
                .openCodeTapTarget()
                .semantics {
                    contentDescription = if (isPlaying) "Stop ${voice.title} preview" else "Preview ${voice.title}"
                    stateDescription = if (isPlaying) "Playing" else (previewHint ?: "")
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(if (isPlaying) accent.copy(alpha = 0.15f) else OpenCodeTheme.colors.text.copy(alpha = 0.06f)),
                contentAlignment = Alignment.Center,
            ) {
                OpenCodeIcon(
                    if (isPlaying) SFSymbol.StopFill else SFSymbol.PlayFill,
                    contentDescription = null,
                    size = 14.dp,
                    tint = when {
                        isPlaying -> accent
                        canPreview -> OpenCodeTheme.colors.secondaryText
                        else -> OpenCodeTheme.colors.secondaryText.copy(alpha = 0.38f)
                    },
                )
            }
        }
    }
}

/**
 * Settings › Voice › Call History: `:feature:voice`'s [VoiceCallLogList] (its own top bar), a saved
 * call pushing its replay. Developer Mode's Open on the Web / Share Link are hidden: Diagnostics'
 * `DeveloperMode.webURL` has no Android counterpart yet.
 */
@Composable
fun CallHistoryView(store: SettingsStore) {
    VoiceCallLogList(
        onBack = { store.send(SettingsFeature.Action.BackTapped) },
        onOpenCall = { store.send(SettingsFeature.Action.PagePushed(SettingsPage.CallReplay(it))) },
        modifier = Modifier.fillMaxSize(),
    )
}

/** Settings › Voice › Call History › one call (`VoiceCallReplayView`, `:feature:voice`). */
@Composable
fun CallReplayView(store: SettingsStore, log: VoiceCallLog) {
    VoiceCallReplayView(log, onBack = { store.send(SettingsFeature.Action.BackTapped) }, modifier = Modifier.fillMaxSize())
}

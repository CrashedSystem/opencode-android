package ai.opencode.feature.composer

import ai.opencode.core.sharing.FileDirectory
import ai.opencode.core.sharing.SharedKey
import ai.opencode.core.sharing.shared
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeIconSize
import ai.opencode.core.ui.OpenCodeShape
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.server.model.AgentDisplayName
import ai.opencode.server.model.OpenCodeAutocompleteAgent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Port of iOS `Composer/AgentPicker.swift`. Agent Selection (experimental `agentSelection`,
// OC-511): choose the primary agent (Build, Plan, or a custom one) from the composer.
//
// The experiment's switch lives in Settings (`ExperimentalFeature`, above this module), so the
// composables take `isEnabled` from their host instead of reading `@Shared(.experimental(...))`.

/** iOS `SharedKey` extensions declared in `AgentPicker.swift`. */
object AgentPickerSharedKeys {
    /** The agent new sessions start with (an agent ID); empty is the server's default. Declared in `:core` (Settings writes it). */
    val defaultAgentID = ai.opencode.core.sharing.SharedKeys.defaultAgentID

    /** The agent picked in each session (session ID → agent ID). Declared in `:server`. */
    val sessionAgents = ai.opencode.server.ServerSharedKeys.sessionAgents

    /** The primary agents last listed, for Settings' Default Agent. Declared in `:server`. */
    val knownPrimaryAgents = ai.opencode.server.ServerSharedKeys.knownPrimaryAgents
}

/** The description's first sentence, for a menu's subtitle (iOS `shortDescription`). */
val OpenCodeAutocompleteAgent.shortDescription: String?
    get() {
        val text = description?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // Swift's `split(separator: ".", maxSplits: 1)` omits empty pieces, so leading dots
        // don't count: the first non-empty run before a dot, or the whole text when none.
        val rest = text.trimStart('.')
        val sentence = if (rest.isEmpty()) text else rest.substringBefore('.')
        return sentence.trim()
    }

/**
 * iOS `ComposerAgentChip`: the agent chip beside the model chip, only while Agent Selection is
 * on ([isEnabled]). [isFolded] hides it as the composer folds (iOS `composerHidden`).
 */
@Composable
fun ComposerAgentChip(
    isEnabled: Boolean,
    name: String,
    currentID: String?,
    agents: List<OpenCodeAutocompleteAgent>,
    onOpen: () -> Unit,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    isFolded: Boolean = false,
    targetDrop: Dp = 0.dp,
) {
    if (!isEnabled) return
    AgentChip(
        name = name,
        currentID = currentID,
        agents = agents,
        onOpen = onOpen,
        onPick = onPick,
        modifier = if (isFolded) modifier.alpha(0f).clearAndSetSemantics {} else modifier,
        enabled = !isFolded,
        targetDrop = targetDrop,
    )
}

/**
 * iOS `AgentChip`: the current agent's name, opening a menu of the server's primary agents.
 * Android: plain text with no outline or iOS chevron, like the model chip beside it.
 * [targetDrop]: how far its target sits below its ink (the composer's `pickerTargetDrop`).
 */
@Composable
fun AgentChip(
    name: String,
    currentID: String?,
    agents: List<OpenCodeAutocompleteAgent>,
    onOpen: () -> Unit,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    targetDrop: Dp = 0.dp,
) {
    var open by remember { mutableStateOf(false) }
    // Mounted as the composer expands: the list is fresh before it opens.
    LaunchedEffect(Unit) { onOpen() }
    val secondary = OpenCodeTheme.colors.secondaryText
    // One press source for the 48 dp target and the 32 dp chip, so the ripple stays on the chip.
    val interaction = remember { MutableInteractionSource() }
    // The whole menu moves its target down and its ink back up (iOS e16d3e8e), so the menu
    // drops from the lowered target too.
    Box(modifier.offset(y = targetDrop)) {
        Box(
            Modifier
                .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button) { open = true }
                .heightIn(min = 48.dp)
                .clearAndSetSemantics {
                    contentDescription = "Choose agent"
                    stateDescription = name
                    role = Role.DropdownList
                }
                .testTag("composer-agent")
                .offset(y = -targetDrop),
            contentAlignment = Alignment.CenterStart,
        ) {
            // Android: plain `labelLarge` text like a text button; a press ripples a 32 dp capsule.
            Box(
                Modifier
                    .height(AssistChipDefaults.Height)
                    .clip(OpenCodeShape.capsule)
                    .indication(interaction, ripple())
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    name,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (agents.isEmpty()) {
                DropdownMenuItem(text = { Text("Loading Agents…") }, onClick = {}, enabled = false)
            } else {
                // One “Agent” section (menu order fixed, as `.menuOrder(.fixed)`).
                Text(
                    "Agent",
                    style = OpenCodeTextStyles.footnote,
                    color = secondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).semantics { heading() },
                )
                for (agent in agents) {
                    val isOn = agent.id == currentID
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(agent.displayName, style = OpenCodeTextStyles.body)
                                agent.shortDescription?.let {
                                    Text(it, style = OpenCodeTextStyles.footnote, color = secondary)
                                }
                            }
                        },
                        // A menu's toggle draws the checkmark and a subtitle.
                        leadingIcon = {
                            Box(Modifier.width(24.dp)) {
                                if (isOn) OpenCodeIcon(SFSymbol.Checkmark, null, size = OpenCodeIconSize.small)
                            }
                        },
                        modifier = Modifier.semantics { selected = isOn },
                        onClick = {
                            open = false
                            onPick(agent.id)
                        },
                    )
                }
            }
        }
    }
}

/**
 * iOS `DefaultAgentSection`: Settings › Composer › Default Agent, the agent new sessions start
 * with, only while Agent Selection is on. Reads [AgentPickerSharedKeys.defaultAgentID] and
 * [AgentPickerSharedKeys.knownPrimaryAgents]; [onChange] hands the choice to the settings
 * reducer, which writes the key.
 *
 * Note: Settings sits beside (not above) this module, so the settings area needs these keys and
 * this section moved to a module it can see (`:server`); see the composer parity row.
 */
@Composable
fun DefaultAgentSection(
    isEnabled: Boolean,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!isEnabled) return
    val selectionShared = remember { shared(AgentPickerSharedKeys.defaultAgentID) }
    val agentsShared = remember { shared(AgentPickerSharedKeys.knownPrimaryAgents) }
    val selection by selectionShared.flow.collectAsState()
    val agents by agentsShared.flow.collectAsState()
    var open by remember { mutableStateOf(false) }

    data class Option(val id: String, val title: String)
    val options = buildList {
        add(Option("", "Server Default"))
        agents.forEach { add(Option(it.id, it.displayName)) }
        // A saved agent this server doesn't list stays choosable.
        if (selection.isNotEmpty() && agents.none { it.id == selection }) add(Option(selection, AgentDisplayName.titleCased(selection)))
    }
    val current = options.firstOrNull { it.id == selection }?.title ?: "Server Default"

    Column(modifier.fillMaxWidth()) {
        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.DropdownList) { open = true }
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 16.dp)
                    .clearAndSetSemantics {
                        contentDescription = "Default Agent"
                        stateDescription = current
                        role = Role.DropdownList
                    }
                    .testTag("settings-default-agent"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Default Agent", style = OpenCodeTextStyles.body, color = OpenCodeTheme.colors.text, modifier = Modifier.weight(1f))
                Text(current, style = OpenCodeTextStyles.body, color = OpenCodeTheme.colors.secondaryText)
                OpenCodeIcon(SFSymbol.ChevronUpChevronDown, null, size = 14.dp, tint = OpenCodeTheme.colors.secondaryText, modifier = Modifier.padding(start = 4.dp))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (option in options) {
                    val isOn = option.id == selection
                    DropdownMenuItem(
                        text = { Text(option.title) },
                        leadingIcon = { Box(Modifier.width(24.dp)) { if (isOn) OpenCodeIcon(SFSymbol.Checkmark, null, size = OpenCodeIconSize.small) } },
                        modifier = Modifier.semantics { selected = isOn },
                        onClick = {
                            open = false
                            onChange(option.id)
                        },
                    )
                }
            }
        }
        Text(
            "New sessions start with this agent. Each session remembers the one you choose.",
            style = OpenCodeTextStyles.footnote,
            color = OpenCodeTheme.colors.secondaryText,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}

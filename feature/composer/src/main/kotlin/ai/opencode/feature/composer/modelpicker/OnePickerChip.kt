package ai.opencode.feature.composer.modelpicker

import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionRollDirection
import ai.opencode.core.motion.MotionTransition
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.excludedFromDrawerPan
import ai.opencode.core.ui.openCodeTapTarget
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Port of iOS `OnePickerChip`: One Picker's composer chip, the provider's mark, the short model
 * name and the level word (`onSurfaceVariant`). A tap opens One Picker. The
 * name rolls (a few dp of travel, a slight scale and blur) only when [ModelKey] changes, so a
 * level change rolls the word alone, and the chip's width eases with them; neither animates
 * while the picker is shown. Its root-dp frame goes to [OnePickerController.chipFrame].
 *
 * TalkBack: one "Choose model" button whose value is the name and level; on an adjustable model
 * it is also a slider whose steps send [ModelPickerEvent.ModelRowLevelChanged] (iOS
 * `accessibilityAdjustableAction`). Android: drawn as an M3 assist chip (32 dp outlined
 * container, `labelLarge`, an 18 dp drop-down arrow) inside its 48 dp target.
 *
 * [targetDrop]: how far its target sits below its ink (the composer's `pickerTargetDrop`).
 */
@Composable
fun OnePickerChip(
    state: ModelPickerState,
    controller: OnePickerController,
    onEvent: (ModelPickerEvent) -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    isEnabled: Boolean = true,
    targetDrop: Dp = 0.dp,
) {
    val reduceMotion = rememberReduceMotion()
    val density = LocalDensity.current
    val key = OnePickerChip.ModelKey(state)
    val scale = state.currentLevelScale
    val levelLabel = state.currentChipLevelLabel
    val animates = !controller.isShown
    val model = state.currentModelInfo
    val level = state.currentLevel
    val value = listOfNotNull(key.shortName, levelLabel).joinToString(", ")

    // One press source for the 48 dp target and the 32 dp chip, so the ripple stays on the chip.
    val interaction = remember { MutableInteractionSource() }
    val chipShape = AssistChipDefaults.shape
    Box(
        modifier
            .excludedFromDrawerPan()
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInRoot()
                if (bounds.width > 0f) {
                    controller.chipFrame = Rect(
                        bounds.left / density.density,
                        bounds.top / density.density,
                        bounds.right / density.density,
                        bounds.bottom / density.density,
                    )
                }
            }
            // The target sits [targetDrop] below the ink (iOS `openCodeTapTarget(lowered:)`): the
            // chip's touch and TalkBack bounds move down and its ink moves back up.
            .offset(y = targetDrop)
            .clickable(interactionSource = interaction, indication = null, enabled = isEnabled, role = Role.Button) { onOpen() }
            .openCodeTapTarget()
            .heightIn(min = 48.dp)
            .clearAndSetSemantics {
                contentDescription = "Choose model"
                stateDescription = value
                role = Role.Button
                onClick { if (isEnabled) onOpen(); isEnabled }
                if (scale != null && model != null) {
                    val index = scale.index(level)
                    progressBarRangeInfo = ProgressBarRangeInfo(index.toFloat(), 0f..(scale.count - 1).toFloat(), steps = (scale.count - 2).coerceAtLeast(0))
                    setProgress { target ->
                        val delta = target.roundToInt() - index
                        if (delta == 0) return@setProgress false
                        onEvent(ModelPickerEvent.ModelRowLevelChanged(model, scale.stepped(level, if (delta > 0) 1 else -1)))
                        true
                    }
                }
            }
            .testTag("composer-model")
            .offset(y = -targetDrop),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            Modifier
                .height(AssistChipDefaults.Height)
                .clip(chipShape)
                .border(AssistChipDefaults.assistChipBorder(enabled = isEnabled), chipShape)
                .indication(interaction, ripple())
                .animateContentSize(if (animates) Motion.layout.animation(reduceMotion = reduceMotion) else snap())
                // The drop-down arrow's glyph carries its own side bearing, so the end inset is smaller.
                .padding(start = 8.dp, end = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Only the name gives way when the chip runs out of room (iOS keeps the level word, arc and
            // chevron `fixedSize`): weighted, it's measured after them and ellipsizes into what's left.
            OnePickerChipRoll(key, animates, reduceMotion, label = "onePickerName", modifier = Modifier.weight(1f, fill = false)) { rolled ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (OnePickerTunables.CHIP_SHOWS_LOGO && (state.currentModel != null || model != null)) {
                        OnePickerProviderMark(
                            providerID = state.currentModel?.providerID ?: "opencode",
                            canonical = model?.canonicalProviderID,
                            size = 16.dp,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        rolled.shortName,
                        style = ChipTextStyle,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
            if (scale != null) {
                if (OnePickerTunables.CHIP_SHOWS_ARC) {
                    AnimatedLevelArc(scale, level, size = 14.5.dp, color = OpenCodeTheme.colors.text.copy(alpha = 0.78f))
                }
                if (OnePickerTunables.CHIP_SHOWS_LEVEL_WORD && levelLabel != null) {
                    OnePickerChipRoll(levelLabel, animates, reduceMotion, label = "onePickerLevel") { word ->
                        Text(
                            word,
                            style = ChipTextStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
            // Android: always the M3 drop-down arrow (iOS hides its chevron, `CHIP_SHOWS_CHEVRON`).
            OpenCodeIcon(SFSymbol.ArrowtriangleDownFill, null, size = AssistChipDefaults.IconSize, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Names [OnePickerChip]'s identity: what changes it rolls the name. */
object OnePickerChip {
    /**
     * iOS `OnePickerChip.ModelKey`: the chip rolls its name only when the model, its label or its
     * mark changes, never for a level change or the catalogue arriving with the same label.
     */
    data class ModelKey(val id: String, val shortName: String, val icon: String) {
        constructor(state: ModelPickerState) : this(
            id = state.currentModel?.let { "${it.providerID}/${it.id}" } ?: "none",
            shortName = state.currentModelShortName,
            icon = (state.currentModel?.providerID ?: "opencode").let { providerID ->
                OnePickerProviderMark.resolvedAssetName(providerID, state.currentModelInfo?.canonicalProviderID)
                    ?: "fallback:${OnePickerProviderMark.fallbackInitial(providerID)}"
            },
        )
    }
}

/** M3 chip label (`labelLarge`); iOS uses `.subheadline.weight(.medium)`. */
private val ChipTextStyle: TextStyle
    @Composable get() = MaterialTheme.typography.labelLarge

/**
 * The incoming label rolls up from below on the frame the outgoing one rolls away
 * (`motionRoll`), its width following on `layout`; under Reduce Motion, a cross-fade. Not
 * animated ([animates] false: the picker is open) the value is swapped at once.
 */
@Composable
private fun <T> OnePickerChipRoll(
    target: T,
    animates: Boolean,
    reduceMotion: Boolean,
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    val density = LocalDensity.current
    AnimatedContent(
        targetState = target,
        modifier = modifier,
        transitionSpec = {
            if (!animates) {
                (fadeIn(snap()) togetherWith fadeOut(snap())).using(SizeTransform(clip = false) { _, _ -> snap() })
            } else {
                val size = SizeTransform(clip = false) { _, _ -> Motion.layout.animation(reduceMotion = reduceMotion) }
                MotionTransition.roll(MotionRollDirection.Up, reduceMotion, density).content.using(size)
            }
        },
        contentAlignment = Alignment.CenterStart,
        label = label,
    ) { value ->
        content(value)
    }
}

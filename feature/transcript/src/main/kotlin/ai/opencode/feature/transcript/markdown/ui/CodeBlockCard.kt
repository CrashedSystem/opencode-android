package ai.opencode.feature.transcript.markdown.ui

import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.SFSymbol
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Code and display math on Android: a rounded `surfaceContainer` card in the prose column
 * (M3 medium shape), not iOS's full-width band between hairlines. Code gets a header with its
 * language and a copy button, as code blocks have in Android chat apps.
 */
object CodeBlockCard {
    /** M3 medium shape (the drag lift rounds the same). */
    val cornerRadius: Dp = 12.dp

    /** From the card's edge to the content. */
    val contentPadding: Dp = 16.dp

    /** How long the copy button shows its checkmark (as the reply's copy button). */
    const val COPIED_MILLIS: Long = 1_500
}

/**
 * @param language shown in the header; with [onCopy] null and no language there's no header.
 * @param onCopy copies the block (through the row's actions, which play the haptic).
 * @param cardModifier applied to the whole card: a drag source there lifts the card.
 */
@Composable
fun CodeBlockCard(
    modifier: Modifier = Modifier,
    language: String? = null,
    onCopy: (() -> Unit)? = null,
    cardModifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(CodeBlockCard.cornerRadius)
    val scroll = rememberScrollState()
    val label = language?.trim().orEmpty()
    val hasHeader = onCopy != null || label.isNotEmpty()
    Column(
        modifier
            .fillMaxWidth()
            .then(cardModifier)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer, shape),
    ) {
        if (hasHeader) {
            Row(
                Modifier.fillMaxWidth().padding(start = CodeBlockCard.contentPadding, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (onCopy != null) {
                    CodeCopyButton(onCopy)
                } else {
                    Spacer(Modifier.size(48.dp))
                }
            }
        }
        val top = if (hasHeader) 0.dp else 12.dp
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = top, bottom = 12.dp)
                .horizontalOverflowFade(scroll, width = 24.dp)
                .horizontalScroll(scroll)
                .padding(horizontal = CodeBlockCard.contentPadding),
        ) { content() }
    }
}

/** `content_copy`, turning to a checkmark for a moment once copied. */
@Composable
private fun CodeCopyButton(onCopy: () -> Unit) {
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (!copied) return@LaunchedEffect
        delay(CodeBlockCard.COPIED_MILLIS)
        copied = false
    }
    val description = if (copied) "Copied" else "Copy code"
    IconButton(
        onClick = {
            onCopy()
            copied = true
        },
        modifier = Modifier.semantics { contentDescription = description },
    ) {
        Crossfade(copied, animationSpec = Motion.swap.animation(reduceMotion = rememberReduceMotion()), label = "CodeCopy") { isCopied ->
            OpenCodeIcon(
                if (isCopied) SFSymbol.Checkmark else SFSymbol.SquareOnSquare,
                contentDescription = null,
                size = 20.dp,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

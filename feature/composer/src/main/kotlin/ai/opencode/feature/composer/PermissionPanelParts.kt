package ai.opencode.feature.composer

import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.server.model.OpenCodePermissionRequest
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * A permission in a line or two (iOS `PermissionSummary`): its command flattened onto one
 * line, the file it edits (with `+N −M`), or what it reaches.
 */
data class PermissionSummary(val text: String, val isCode: Boolean) {
    companion object {
        operator fun invoke(permission: OpenCodePermissionRequest, command: String?): PermissionSummary {
            val preview = permission.filePreview
            if (preview != null) {
                val (file, patch) = preview
                val lines = patch.split("\n")
                val added = lines.count { it.startsWith("+") && !it.startsWith("+++") }
                val removed = lines.count { it.startsWith("-") && !it.startsWith("---") }
                return PermissionSummary("${lastPathComponent(file)}  +$added −$removed", isCode = false)
            }
            if (command != null) return PermissionSummary(flattened(command), isCode = true)
            return PermissionSummary(flattened(permission.resources.joinToString(" ")), isCode = permission.resources.isNotEmpty())
        }

        /** Swift `split(whereSeparator: \.isWhitespace)`: every Unicode whitespace, newlines included. */
        fun flattened(text: String): String = buildString {
            var pending = false
            for (char in text) {
                if (char.isWhitespace()) {
                    pending = isNotEmpty()
                } else {
                    if (pending) append(' ')
                    pending = false
                    append(char)
                }
            }
        }
    }
}

/** `NSString.lastPathComponent`. */
internal fun lastPathComponent(path: String): String {
    val trimmed = path.trimEnd('/')
    if (trimmed.isEmpty()) return if (path.isEmpty()) "" else "/"
    return trimmed.substringAfterLast('/')
}

/**
 * The permission actions' sizes (iOS `RequestTunables`; the Tuning Panel backing isn't
 * ported, these are its defaults).
 */
object RequestTunables {
    /** `requests.permission.scopeMaxHeight`: the scope list's maximum height (120…400). */
    val scopeMaxHeight = 220.dp
    /**
     * `requests.permission.labelHeight`: the action label's height (20…44). Android: 24 dp, so with
     * [ButtonDefaults.ContentPadding] the button is M3's 40 dp default; the touch target stays 48 dp.
     */
    val labelHeight = 24.dp
}

/** iOS `TranscriptMetrics` fonts and corner the request panel borrows (the transcript module sits beside this one). */
internal object RequestTranscriptMetrics {
    /** `TranscriptMetrics.toolFont`: footnote, monospaced. */
    val toolFont: TextStyle get() = OpenCodeTextStyles.footnote.copy(fontFamily = FontFamily.Monospace)
    /** `TranscriptMetrics.codeFont`: subheadline, monospaced. */
    val codeFont: TextStyle get() = OpenCodeTextStyles.subheadline.copy(fontFamily = FontFamily.Monospace)
    /** `TranscriptMetrics.controlCornerRadius`. */
    val controlCornerRadius = 10.dp
    /** `TranscriptBandStyle.verticalPadding`: from a hairline to the content's first line box. */
    val bandVerticalPadding = 12.dp
}

/**
 * A permission action (iOS `requestPermissionButtonShape()` inside the shared button styles).
 * Android: M3's default 40 dp button with its expressive round shape (it squares up when pressed)
 * and labelLarge text, not iOS's 14 dp card corners. Primary is the accent fill (Always Allow),
 * secondary the neutral tonal one (Allow Once).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun RequestPermissionButton(
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val shapes = ButtonDefaults.shapesFor(ButtonDefaults.MinHeight)
    val padding = ButtonDefaults.ContentPadding
    val sized = modifier.heightIn(min = ButtonDefaults.MinHeight)
    if (primary) {
        Button(onClick, shapes, sized, enabled, colors = ButtonDefaults.buttonColors(), contentPadding = padding, content = content)
    } else {
        FilledTonalButton(onClick, shapes, sized, enabled, colors = ButtonDefaults.filledTonalButtonColors(), contentPadding = padding, content = content)
    }
}

/**
 * Always Allow's scope, shown in full before it's given (R-177, D-130): every pattern
 * wraps, selectable, and a long list scrolls above the confirm, which stays in view.
 */
@Composable
fun PermissionScopeConfirm(save: List<String>, confirm: () -> Unit, modifier: Modifier = Modifier) {
    val colors = OpenCodeTheme.colors
    Column(modifier.width(280.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Won’t ask again for",
            style = OpenCodeTextStyles.footnote.copy(fontWeight = FontWeight.Medium),
            color = colors.secondaryText,
        )
        SelectionContainer(
            Modifier
                .heightIn(max = RequestTunables.scopeMaxHeight)
                .verticalScroll(rememberScrollState())
                .testTag("request-scope-list"),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (pattern in save) {
                    Text(pattern, style = RequestTranscriptMetrics.toolFont, color = colors.text, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        RequestPermissionButton(primary = true, onClick = confirm, modifier = Modifier.fillMaxWidth().testTag("request-always-confirm")) {
            Text("Always Allow", maxLines = 1)
        }
    }
}

/** What a permission would do: the command as a full-width band, or the file and its diff. */
@Composable
fun PermissionPreview(permission: OpenCodePermissionRequest, command: String?, modifier: Modifier = Modifier) {
    val colors = OpenCodeTheme.colors
    val preview = permission.filePreview
    Column(modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            preview != null -> {
                SelectionContainer {
                    Text(
                        preview.first,
                        style = RequestTranscriptMetrics.codeFont,
                        color = colors.secondaryText,
                        modifier = Modifier.padding(horizontal = RequestDockMetrics.edge),
                    )
                }
                DiffPreview(preview.second, Modifier.padding(horizontal = RequestDockMetrics.edge))
            }
            command != null -> PermissionBand(command)
            permission.resources.isNotEmpty() -> PermissionBand(permission.resources.joinToString("\n"))
        }
    }
}

@Composable
private fun PermissionBand(text: String) {
    val colors = OpenCodeTheme.colors
    val styled = remember(text, colors.tertiaryText) {
        buildAnnotatedString {
            withStyle(SpanStyle(color = colors.tertiaryText)) { append("$ ") }
            append(text)
        }
    }
    val rule = colors.border
    SelectionContainer {
        Text(
            styled,
            style = RequestTranscriptMetrics.codeFont,
            color = colors.text,
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.text.copy(alpha = if (colors.isDark) 0.035f else 0.025f))
                // iOS overlays the hairlines on the band's top and bottom edges (they add no height).
                .drawWithContent {
                    drawContent()
                    val px = 1f
                    drawRect(rule, topLeft = Offset.Zero, size = Size(size.width, px))
                    drawRect(rule, topLeft = Offset(0f, size.height - px), size = Size(size.width, px))
                }
                .padding(vertical = RequestTranscriptMetrics.bandVerticalPadding, horizontal = RequestDockMetrics.edge),
        )
    }
}

/** A unified diff's changed lines, tinted, without the file headers. */
@Composable
private fun DiffPreview(patch: String, modifier: Modifier = Modifier) {
    val colors = OpenCodeTheme.colors
    val lines = remember(patch) { DiffPreviewLines.lines(patch) }
    Column(modifier.clip(RoundedCornerShape(RequestTranscriptMetrics.controlCornerRadius))) {
        for (line in lines) {
            val (foreground, fill) = when (line.kind) {
                DiffPreviewLines.Kind.Added -> colors.diffAddedText to colors.diffAddedFill
                DiffPreviewLines.Kind.Removed -> colors.diffRemovedText to colors.diffRemovedFill
                DiffPreviewLines.Kind.Context -> colors.text to Color.Transparent
                DiffPreviewLines.Kind.Hunk -> colors.secondaryText to Color.Transparent
            }
            val spoken = line.kind.spoken + line.text.drop(1)
            Text(
                line.text.ifEmpty { " " },
                style = RequestTranscriptMetrics.toolFont,
                color = foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(fill)
                    .padding(horizontal = 6.dp)
                    .semantics { contentDescription = spoken },
            )
        }
    }
}

/** The pure part of iOS `DiffPreview`. */
internal object DiffPreviewLines {
    enum class Kind(val spoken: String) { Added("Added: "), Removed("Removed: "), Context(""), Hunk("") }

    data class Line(val kind: Kind, val text: String)

    fun lines(patch: String, limit: Int = 60): List<Line> {
        val result = mutableListOf<Line>()
        for (line in patch.split("\n")) {
            if (line.startsWith("Index:") || line.startsWith("===") || line.startsWith("---") || line.startsWith("+++") || line.startsWith("\\")) continue
            val kind = when {
                line.startsWith("@@") -> Kind.Hunk
                line.startsWith("+") -> Kind.Added
                line.startsWith("-") -> Kind.Removed
                else -> Kind.Context
            }
            if (kind == Kind.Context && line.isEmpty() && result.isEmpty()) continue
            result += Line(kind, line)
            if (result.size == limit) break
        }
        while (result.lastOrNull()?.let { it.kind == Kind.Context && it.text.isEmpty() } == true) result.removeAt(result.lastIndex)
        return result
    }
}

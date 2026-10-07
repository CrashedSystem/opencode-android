@file:PortedFrom("OpenCode/Transcript/MarkdownBlockView.swift", "553a407")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.SystemColors
import ai.opencode.mobilecode.system.SystemImage
import ai.opencode.mobilecode.system.TextStyles
import ai.opencode.mobilecode.system.bold
import ai.opencode.mobilecode.system.semibold
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

object TranscriptMetrics {
    const val compact = 4
    const val clustered = 8
    const val bubbleVertical = 10
    const val standard = 12
    const val contentGutter = 16
    const val turn = 20
    const val cardCornerRadius = 14
    const val flowHalf = clustered
    val codeFont @Composable get() = TextStyles.code
    val toolFont @Composable get() = TextStyles.tool
}

data class TranscriptImagePreview(val id: String, val label: String)

val LocalTranscriptTextReveal = staticCompositionLocalOf<TranscriptTextReveal?> { null }

@Composable
fun MarkdownBlockView(
    block: OpenCodeMarkdownBlock,
    server: OpenCodeServer,
    directory: String?,
    modifier: Modifier = Modifier,
    onPreviewImage: (TranscriptImagePreview) -> Unit = {},
    onOpenFile: (AgentFileReference) -> Unit = {},
    arrivals: List<TranscriptTextReveal.Arrival> = emptyList(),
) {
    val whole = arrivals.firstOrNull { it.offset == 0 }
    ArrivalFade(arriving = whole != null, at = whole?.time ?: 0.0, modifier = modifier) {
        BlockContent(block, server, directory, arrivals.filter { it.offset > 0 }, onPreviewImage, onOpenFile)
    }
}

@Composable
private fun BlockContent(
    block: OpenCodeMarkdownBlock,
    server: OpenCodeServer,
    directory: String?,
    grown: List<TranscriptTextReveal.Arrival>,
    onPreviewImage: (TranscriptImagePreview) -> Unit,
    onOpenFile: (AgentFileReference) -> Unit,
) {
    when (val kind = block.kind) {
        is OpenCodeMarkdownBlock.Kind.numberedItem -> ListItem(kind.marker, 0, kind.text, grown)
        is OpenCodeMarkdownBlock.Kind.listItem -> ListItem(kind.marker, kind.level, kind.text, grown)
        is OpenCodeMarkdownBlock.Kind.blockquote -> Row(
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .padding(vertical = TranscriptMetrics.compact.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(SystemColors.secondaryLabel.copy(alpha = 0.5f), RoundedCornerShape(2.dp)),
            )
            ArrivingText(
                MarkdownStyle.styled(kind.text),
                grown,
                SystemColors.secondaryLabel,
                TextStyles.body.copy(lineHeight = 26.sp),
                Modifier.weight(1f),
            )
        }
        is OpenCodeMarkdownBlock.Kind.paragraph ->
            ArrivingText(
                MarkdownStyle.styled(kind.text),
                grown,
                SystemColors.label,
                TextStyles.body.copy(lineHeight = 26.sp),
                Modifier.fillMaxWidth(),
            )
        OpenCodeMarkdownBlock.Kind.divider -> HairlineRule(Modifier.bleedHorizontal(TranscriptMetrics.contentGutter.dp))
        is OpenCodeMarkdownBlock.Kind.pullRequest -> PullRequestCard(kind.title, kind.url)
        is OpenCodeMarkdownBlock.Kind.files -> AgentFileCards(kind.files, onOpenFile)
        is OpenCodeMarkdownBlock.Kind.image -> ImageBlock(kind.alt, kind.source, server, directory, onPreviewImage, onOpenFile)
        is OpenCodeMarkdownBlock.Kind.heading -> ArrivingText(
            MarkdownStyle.styled(kind.text),
            grown,
            SystemColors.label,
            MarkdownStyle.headingFont(kind.level),
            Modifier.fillMaxWidth(),
        )
        is OpenCodeMarkdownBlock.Kind.code -> MarkdownBand {
            ArrivingText(kind.code.text, grown, SystemColors.label, TranscriptMetrics.codeFont)
        }
        is OpenCodeMarkdownBlock.Kind.table -> MarkdownTable(kind.header, kind.rows)
    }
}

@Composable
private fun ListItem(
    marker: String,
    level: Int,
    text: AttributedString,
    grown: List<TranscriptTextReveal.Arrival>,
) {
    val done = marker == OpenCodeMarkdownBlock.doneTask
    val isTask = done || marker == OpenCodeMarkdownBlock.openTask
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = (level * MarkdownStyle.listIndent).dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.width(20.dp), contentAlignment = Alignment.CenterEnd) {
            if (isTask) {
                SystemImage(
                    if (done) "checkmark.square.fill" else "square",
                    if (done) "Done" else "Not done",
                    Modifier.size(18.dp),
                    tint = SystemColors.secondaryLabel,
                )
            } else {
                Text(
                    marker,
                    style = if (marker == "•") TextStyles.body else TextStyles.footnote.copy(fontFamily = FontFamily.Monospace),
                    color = SystemColors.secondaryLabel,
                )
            }
        }
        ArrivingText(
            MarkdownStyle.styled(text, secondary = done),
            grown,
            if (done) SystemColors.secondaryLabel else SystemColors.label,
            TextStyles.body.copy(lineHeight = 26.sp),
            Modifier.weight(1f),
        )
    }
}

@Composable
private fun PullRequestCard(title: String, url: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TranscriptMetrics.cardCornerRadius.dp))
            .background(SystemColors.secondarySystemBackground)
            .clickable { }
            .padding(horizontal = 14.dp, vertical = TranscriptMetrics.standard.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SystemImage("arrow.triangle.pull", null, Modifier.size(16.dp), tint = SystemColors.systemPurple)
        Text(title, style = TextStyles.subheadline.semibold, color = SystemColors.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        SystemImage("arrow.up.right", null, Modifier.size(12.dp), tint = SystemColors.tertiaryLabel)
    }
}

@Composable
private fun ImageBlock(
    alt: String,
    source: String,
    server: OpenCodeServer,
    directory: String?,
    onPreviewImage: (TranscriptImagePreview) -> Unit,
    onOpenFile: (AgentFileReference) -> Unit,
) {
    val file = if (server.imageRequest(source, directory) == null) AgentFileReference.fromLink(source) else null
    if (file != null) {
        AgentFileCards(listOf(file), onOpenFile)
    } else {
        MarkdownImage(source, alt, server, directory, onPreviewImage)
    }
}

object MarkdownStyle {
    const val lineSpacing = 4
    val paragraphGap = TranscriptMetrics.flowHalf * 2
    val listGap = TranscriptMetrics.clustered
    const val headingAbove = 24
    val headingBelow = TranscriptMetrics.clustered
    val blockGap = TranscriptMetrics.turn
    const val listIndent = 20

    @Composable
    fun headingFont(level: Int) = when (level) {
        1 -> TextStyles.title2.bold
        2 -> TextStyles.title3.semibold
        else -> TextStyles.headline
    }

    fun gap(after: OpenCodeMarkdownBlock.Kind?, before: OpenCodeMarkdownBlock.Kind): Int {
        if (after == null) return 0
        if (before is OpenCodeMarkdownBlock.Kind.heading) return headingAbove
        if (after is OpenCodeMarkdownBlock.Kind.heading) return headingBelow
        if (isListItem(after) && isListItem(before)) return listGap
        if (standsApart(after) || standsApart(before)) return blockGap
        return paragraphGap
    }

    private fun isListItem(kind: OpenCodeMarkdownBlock.Kind) = when (kind) {
        is OpenCodeMarkdownBlock.Kind.numberedItem, is OpenCodeMarkdownBlock.Kind.listItem -> true
        is OpenCodeMarkdownBlock.Kind.paragraph -> kind.text.characters.firstOrNull() == '•'
        else -> false
    }

    private fun standsApart(kind: OpenCodeMarkdownBlock.Kind) = when (kind) {
        is OpenCodeMarkdownBlock.Kind.code,
        is OpenCodeMarkdownBlock.Kind.table,
        is OpenCodeMarkdownBlock.Kind.image,
        is OpenCodeMarkdownBlock.Kind.pullRequest,
        is OpenCodeMarkdownBlock.Kind.files,
        OpenCodeMarkdownBlock.Kind.divider,
        -> true
        else -> false
    }

    fun styled(text: AttributedString, secondary: Boolean = false): AttributedString {
        val runs = text.runs.map { run ->
            if (AttributedString.Intent.code in run.inlinePresentationIntent && run.link == null) {
                run.copy(
                    foregroundColor = if (secondary) AttributedString.Foreground.secondary else AttributedString.Foreground.primary,
                    codeBackground = true,
                )
            } else {
                run
            }
        }
        return text.copy(runs = runs)
    }
}

@Composable
fun MarkdownBlocksView(
    blocks: List<OpenCodeMarkdownBlock>,
    server: OpenCodeServer,
    directory: String?,
    modifier: Modifier = Modifier,
    onPreviewImage: (TranscriptImagePreview) -> Unit = {},
    onOpenFile: (AgentFileReference) -> Unit = {},
    revealItem: String? = null,
) {
    val reveal = LocalTranscriptTextReveal.current
    val arrivals = if (reveal != null && revealItem != null) {
        reveal.arrivals(revealItem, blocks, TranscriptTextReveal.clock())
    } else {
        emptyMap()
    }
    SelectionContainer(modifier) {
        Column {
            blocks.forEachIndexed { index, block ->
                MarkdownBlockView(
                    block = block,
                    server = server,
                    directory = directory,
                    onPreviewImage = onPreviewImage,
                    onOpenFile = onOpenFile,
                    arrivals = arrivals[block.id].orEmpty(),
                    modifier = Modifier.padding(
                        top = MarkdownStyle.gap(
                            after = if (index == 0) null else blocks[index - 1].kind,
                            before = block.kind,
                        ).dp,
                    ),
                )
            }
        }
    }
}

class TranscriptTextReveal {
    data class Arrival(val offset: Int, val time: Double)

    private class Record(val kind: Int, var length: Int, val arrivals: MutableList<Arrival>)

    private val records = mutableMapOf<String, Map<Int, Record>>()

    fun arrivals(item: String, blocks: List<OpenCodeMarkdownBlock>, now: Double): Map<Int, List<Arrival>> {
        val isNew = item !in records
        val previous = records[item].orEmpty()
        val next = mutableMapOf<Int, Record>()
        val result = mutableMapOf<Int, List<Arrival>>()
        for (block in blocks) {
            val measured = measure(block.kind)
            val prior = previous[block.id]
            if (prior == null || prior.kind != measured.first) {
                val arrives = !isNew && previous[block.id] == null && measured.second > 0
                val fresh = if (arrives) listOf(Arrival(0, now)) else emptyList()
                next[block.id] = Record(measured.first, measured.second, fresh.toMutableList())
                if (arrives) result[block.id] = fresh
                continue
            }
            prior.arrivals.removeAll { now - it.time >= duration || it.offset >= measured.second }
            if (measured.second > prior.length) prior.arrivals += Arrival(prior.length, now)
            prior.length = measured.second
            next[block.id] = prior
            if (prior.arrivals.isNotEmpty()) result[block.id] = prior.arrivals.toList()
        }
        records[item] = next
        return result
    }

    companion object {
        const val duration = 0.2

        fun clock(): Double = System.nanoTime() / 1_000_000_000.0

        suspend fun wait(
            until: Double,
            now: () -> Double = { clock() },
            sleep: suspend (Duration) -> Unit = { delay(it) },
        ): Boolean {
            while (now() < until) {
                try {
                    sleep((until - now()).seconds)
                } catch (_: CancellationException) {
                    return false
                }
                if (!coroutineContext.isActive) return false
            }
            return coroutineContext.isActive
        }

        private fun measure(kind: OpenCodeMarkdownBlock.Kind): Pair<Int, Int> = when (kind) {
            is OpenCodeMarkdownBlock.Kind.paragraph -> 0 to kind.text.characters.length
            is OpenCodeMarkdownBlock.Kind.numberedItem -> 1 to kind.text.characters.length
            is OpenCodeMarkdownBlock.Kind.listItem -> 1 to kind.text.characters.length
            is OpenCodeMarkdownBlock.Kind.blockquote -> 2 to kind.text.characters.length
            is OpenCodeMarkdownBlock.Kind.heading -> 3 to kind.text.characters.length
            is OpenCodeMarkdownBlock.Kind.code -> 4 to kind.code.text.characters.length
            is OpenCodeMarkdownBlock.Kind.table -> 5 to 1
            is OpenCodeMarkdownBlock.Kind.image -> 6 to 1
            is OpenCodeMarkdownBlock.Kind.pullRequest -> 7 to 1
            OpenCodeMarkdownBlock.Kind.divider -> 8 to 1
            is OpenCodeMarkdownBlock.Kind.files -> 9 to 1
        }
    }
}

@Composable
private fun ArrivalFade(
    arriving: Boolean,
    at: Double,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var shown by remember { mutableStateOf(!arriving) }
    LaunchedEffect(arriving, at) {
        if (!shown) shown = true
    }
    Box(modifier) { content() }
}

@Composable
private fun ArrivingText(
    text: AttributedString,
    arrivals: List<TranscriptTextReveal.Arrival>,
    color: Color,
    style: androidx.compose.ui.text.TextStyle,
    modifier: Modifier = Modifier,
) {
    Text(annotated(text, color), modifier = modifier, style = style, color = color)
}

@Composable
private fun annotated(text: AttributedString, base: Color): AnnotatedString {
    if (text.runs.isEmpty()) return AnnotatedString(text.characters)
    val points = (listOf(0, text.characters.length) + text.runs.flatMap { listOf(it.start, it.end) })
        .filter { it in 0..text.characters.length }
        .distinct()
        .sorted()
    val builder = AnnotatedString.Builder()
    for (index in 0 until points.lastIndex) {
        val start = points[index]
        val end = points[index + 1]
        if (start == end) continue
        val active = text.runs.filter { it.start <= start && it.end >= end }
        val link = active.firstOrNull { it.link != null }
        val foreground = active.lastOrNull { it.foregroundColor != null }?.foregroundColor
        val color = when {
            foreground == AttributedString.Foreground.clear -> Color.Transparent
            link != null -> SystemColors.link
            foreground != null -> foreground.resolve()
            else -> Color.Unspecified
        }
        val code = active.any { it.codeBackground }
        val span = SpanStyle(
            color = color,
            background = if (code) SystemColors.tertiarySystemFill else Color.Unspecified,
            fontWeight = if (active.any { AttributedString.Intent.stronglyEmphasized in it.inlinePresentationIntent }) FontWeight.Bold else null,
            fontStyle = if (active.any { AttributedString.Intent.emphasized in it.inlinePresentationIntent }) FontStyle.Italic else null,
            fontFamily = if (code) FontFamily.Monospace else null,
            fontSize = if (code) 15.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
        )
        builder.pushStyle(span)
        builder.append(text.characters.substring(start, end))
        builder.pop()
    }
    return builder.toAnnotatedString()
}

@Composable
private fun AttributedString.Foreground.resolve(): Color = when (this) {
    AttributedString.Foreground.primary -> SystemColors.label
    AttributedString.Foreground.secondary -> SystemColors.secondaryLabel
    AttributedString.Foreground.link -> SystemColors.link
    AttributedString.Foreground.clear -> Color.Transparent
    is AttributedString.Foreground.syntax -> syntaxColor(role)
}

@Composable
fun syntaxColor(role: AttributedString.SyntaxRole): Color {
    val dark = isSystemInDarkTheme()
    return when (role) {
        AttributedString.SyntaxRole.keyword, AttributedString.SyntaxRole.number ->
            if (dark) Color(247f / 255f, 153f / 255f, 198f / 255f) else Color(200f / 255f, 61f / 255f, 139f / 255f)
        AttributedString.SyntaxRole.string ->
            if (dark) Color(150f / 255f, 227f / 255f, 166f / 255f) else Color(25f / 255f, 139f / 255f, 67f / 255f)
        AttributedString.SyntaxRole.comment ->
            if (dark) Color(0.55f, 0.58f, 0.62f) else Color(0.38f, 0.40f, 0.43f)
        AttributedString.SyntaxRole.type ->
            if (dark) Color(158f / 255f, 153f / 255f, 247f / 255f) else Color(82f / 255f, 48f / 255f, 194f / 255f)
    }
}

private fun Modifier.bleedHorizontal(gutter: Dp): Modifier = layout { measurable, constraints ->
    val extra = gutter.roundToPx()
    val child = measurable.measure(
        constraints.copy(
            minWidth = constraints.maxWidth + extra * 2,
            maxWidth = constraints.maxWidth + extra * 2,
        ),
    )
    layout(constraints.maxWidth, child.height) {
        child.placeRelative(-extra, 0)
    }
}

private class TableRuleYs {
    val values = mutableStateListOf<Pair<Int, Float>>()
}

private val LocalTableRuleYs = staticCompositionLocalOf<TableRuleYs?> { null }

@Composable
private fun MarkdownBand(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val fill = SystemColors.label.copy(alpha = if (dark) 0.035f else 0.025f)
    val scroll = rememberScrollState()
    val rules = remember { TableRuleYs() }
    var bandTop by remember { mutableFloatStateOf(0f) }
    Column(Modifier.fillMaxWidth().bleedHorizontal(TranscriptMetrics.contentGutter.dp)) {
        HairlineRule()
        Box(
            Modifier
                .fillMaxWidth()
                .background(fill)
                .padding(vertical = 12.dp)
                .onGloballyPositioned { bandTop = it.positionInWindow().y },
        ) {
            CompositionLocalProvider(LocalTableRuleYs provides rules) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(scroll)
                        .horizontalOverflowFade(scroll, 32.dp)
                        .padding(horizontal = TranscriptMetrics.contentGutter.dp),
                ) { content() }
            }
            rules.values.sortedBy { it.first }.forEach { (index, y) ->
                HairlineRule(
                    Modifier
                        .offset { IntOffset(0, (y - bandTop).roundToInt()) }
                        .graphicsAlpha(if (index == 0) 1f else 0.6f),
                )
            }
            if (scroll.maxValue - scroll.value > 1) {
                Box(Modifier.matchParentSize()) {
                    Box(
                        Modifier
                            .align(Alignment.CenterEnd)
                            .width(32.dp)
                            .fillMaxHeight()
                            .background(Brush.horizontalGradient(listOf(Color.Transparent, SystemColors.systemBackground))),
                    )
                }
            }
        }
        HairlineRule()
    }
}

private fun Modifier.graphicsAlpha(alpha: Float): Modifier = if (alpha >= 1f) this else this.alpha(alpha)

@Composable
fun HairlineRule(modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val scale = density.density
    val height = with(density) { (1f / scale).toDp() }
    val color = SystemColors.separator
    var snap by remember { mutableFloatStateOf(0f) }
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .onGloballyPositioned { coordinates ->
                snap = pixelOffset(coordinates.positionInWindow().y, scale)
            }
            .drawBehind {
                drawRect(color, topLeft = Offset(0f, snap), size = Size(size.width, 1f))
            },
    )
}

fun pixelOffset(y: Float, scale: Float): Float = (y * scale).roundToInt() / scale - y

@Composable
fun MarkdownTable(header: List<AttributedString>, rows: List<List<AttributedString>>) {
    MarkdownBand { TableGrid(header, rows) }
}

@Composable
private fun TableGrid(header: List<AttributedString>, rows: List<List<AttributedString>>) {
    val rules = LocalTableRuleYs.current
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val maxPx = with(density) { 220.dp.roundToPx() }
    val headerStyle = TextStyles.subheadline.semibold
    val bodyStyle = TextStyles.subheadline
    val widths = remember(header, rows, headerStyle, bodyStyle, maxPx) {
        val count = maxOf(header.size, rows.maxOfOrNull { it.size } ?: 0)
        List(count) { column ->
            val headerWidth = header.getOrNull(column)?.let { cell ->
                measurer.measure(AnnotatedString(cell.characters), style = headerStyle, constraints = androidx.compose.ui.unit.Constraints(maxWidth = maxPx)).size.width
            } ?: 0
            val bodyWidth = rows.maxOfOrNull { row ->
                row.getOrNull(column)?.let { cell ->
                    measurer.measure(AnnotatedString(cell.characters), style = bodyStyle, constraints = androidx.compose.ui.unit.Constraints(maxWidth = maxPx)).size.width
                } ?: 0
            } ?: 0
            maxOf(headerWidth, bodyWidth).coerceAtMost(maxPx)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(TranscriptMetrics.bubbleVertical.dp)) {
        TableRow(header, header = true, widths)
        RuleSlot(0, rules)
        rows.forEachIndexed { index, row ->
            if (index > 0) RuleSlot(index, rules)
            TableRow(row, header = false, widths)
        }
    }
}

@Composable
private fun RuleSlot(index: Int, rules: TableRuleYs?) {
    Spacer(
        Modifier
            .height(0.5.dp)
            .onGloballyPositioned { coordinates ->
                if (rules == null) return@onGloballyPositioned
                val y = coordinates.positionInWindow().y
                val current = rules.values.indexOfFirst { it.first == index }
                val next = index to y
                if (current >= 0) rules.values[current] = next else rules.values += next
            },
    )
}

@Composable
private fun TableRow(cells: List<AttributedString>, header: Boolean, widths: List<Int>) {
    val density = LocalDensity.current
    Row(horizontalArrangement = Arrangement.spacedBy(TranscriptMetrics.turn.dp)) {
        cells.forEachIndexed { index, cell ->
            val width = with(density) { (widths.getOrElse(index) { 0 }).toDp() }
            Text(
                annotated(MarkdownStyle.styled(cell), if (header) SystemColors.secondaryLabel else SystemColors.label),
                modifier = Modifier.width(width),
                style = if (header) TextStyles.subheadline.semibold else TextStyles.subheadline,
                color = if (header) SystemColors.secondaryLabel else SystemColors.label,
            )
        }
    }
}

@Composable
fun MarkdownImage(
    source: String,
    alt: String,
    server: OpenCodeServer,
    directory: String?,
    onPreview: (TranscriptImagePreview) -> Unit,
) {
    val cacheKey = "${server.persistenceID}|${directory ?: ""}|$source"
    var image by remember(cacheKey) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(cacheKey) { mutableStateOf(false) }
    LaunchedEffect(cacheKey) {
        image = decodeDataImage(source)
        if (image == null && !source.startsWith("data:image/")) failed = server.imageRequest(source, directory) == null
    }
    val loaded = image
    val shape = RoundedCornerShape(TranscriptMetrics.cardCornerRadius.dp)
    when {
        loaded != null -> androidx.compose.foundation.Image(
            bitmap = loaded,
            contentDescription = "Open ${alt.ifEmpty { "image" }}",
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp)
                .clip(shape)
                .clickable { onPreview(TranscriptImagePreview(cacheKey, alt.ifEmpty { "Image" })) },
            contentScale = ContentScale.Fit,
        )
        failed -> Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .clip(shape)
                .background(SystemColors.secondarySystemBackground)
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SystemImage("photo.badge.exclamationmark", null, Modifier.size(16.dp), tint = SystemColors.secondaryLabel)
            Text(alt.ifEmpty { "Image unavailable" }, style = TextStyles.footnote, color = SystemColors.secondaryLabel)
        }
        else -> Box(
            Modifier
                .fillMaxWidth()
                .height(120.dp)
                .clip(shape)
                .background(SystemColors.secondarySystemBackground),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator(Modifier.size(20.dp), color = SystemColors.secondaryLabel) }
    }
}

private fun decodeDataImage(source: String): ImageBitmap? {
    if (!source.startsWith("data:image/")) return null
    val comma = source.indexOf(',')
    if (comma < 0 || !source.substring(0, comma).contains(";base64")) return null
    val bytes = runCatching { Base64.decode(source.substring(comma + 1), Base64.DEFAULT) }.getOrNull() ?: return null
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}

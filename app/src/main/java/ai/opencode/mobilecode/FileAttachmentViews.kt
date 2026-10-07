@file:PortedFrom("OpenCode/FileAttachmentViews.swift", "553a407")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.SystemColors
import ai.opencode.mobilecode.system.SystemImage
import ai.opencode.mobilecode.system.TextStyles
import ai.opencode.mobilecode.system.medium
import ai.opencode.mobilecode.system.semibold
import ai.opencode.mobilecode.system.symbolSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

@Composable
fun FileAttachmentCard(
    name: String,
    symbolName: String,
    modifier: Modifier = Modifier,
    width: Dp = 200.dp,
    height: Dp? = null,
    detailLines: Int = 1,
    trailingSymbolName: String? = null,
    detail: @Composable () -> Unit,
) {
    Row(
        modifier
            .width(width)
            .then(if (height != null) Modifier.heightIn(min = height) else Modifier)
            .padding(horizontal = TranscriptMetrics.standard.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
            SystemImage(symbolName, null, Modifier.symbolSize(TextStyles.title3), tint = SystemColors.secondaryLabel)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                name,
                style = TextStyles.subheadline.medium,
                color = SystemColors.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            androidx.compose.material3.ProvideTextStyle(TextStyles.caption.copy(color = SystemColors.secondaryLabel)) {
                detail()
            }
        }
        if (trailingSymbolName != null) {
            SystemImage(
                trailingSymbolName,
                null,
                Modifier.symbolSize(TextStyles.caption.semibold),
                tint = SystemColors.tertiaryLabel,
            )
        }
    }
}

@Composable
fun TranscriptFileChips(
    files: List<OpenCodeTranscriptFile>,
    onOpen: ((AgentFileReference) -> Unit)? = null,
) {
    val scroll = rememberScrollState()
    LaunchedEffect(files) { scroll.scrollTo(scroll.maxValue) }
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(TranscriptMetrics.clustered.dp, Alignment.End),
    ) {
        for (file in files) {
            val card = @Composable {
                FileAttachmentCard(name = file.name, symbolName = file.symbolName) {
                    Text(file.typeLabel, maxLines = 1)
                }
            }
            val shape = iosContinuousCorner(TranscriptMetrics.cardCornerRadius.dp)
            val path = file.path
            if (onOpen != null && path != null) {
                Box(
                    Modifier
                        .clip(shape)
                        .background(SystemColors.secondarySystemBackground)
                        .clickable { onOpen(AgentFileReference(path)) }
                        .semantics {
                            role = Role.Button
                            contentDescription = "Attached file ${file.name}"
                        },
                ) { card() }
            } else {
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .clip(shape)
                        .background(SystemColors.secondarySystemBackground)
                        .semantics { contentDescription = "Attached file ${file.name}" },
                ) { card() }
            }
        }
    }
}

@Composable
fun AgentFileCards(
    files: List<AgentFileReference>,
    onOpen: (AgentFileReference) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(TranscriptMetrics.clustered.dp),
    ) {
        for (file in files) {
            val shape = iosContinuousCorner(TranscriptMetrics.cardCornerRadius.dp)
            Box(
                Modifier
                    .clickable { onOpen(file) }
                    .semantics {
                        role = Role.Button
                        contentDescription = "Open ${file.name}"
                    },
            ) {
                FileAttachmentCard(
                    name = file.name,
                    symbolName = file.symbolName,
                    width = if (files.size == 1) 260.dp else 220.dp,
                    trailingSymbolName = if (file.kind.isTimeBased) "play.fill" else "chevron.right",
                    modifier = Modifier
                        .heightIn(min = 44.dp)
                        .clip(shape)
                        .background(SystemColors.secondarySystemBackground),
                ) {
                    Text(file.typeLabel, maxLines = 1)
                }
            }
        }
    }
}

object ComposerFileChip {
    val height = 76.dp
    val width = 220.dp

    @Composable
    operator fun invoke(
        file: ComposerFileAttachment,
        canRetry: Boolean,
        onRetry: () -> Unit,
    ) {
        val shape = iosContinuousCorner(22.dp)
        val card = @Composable {
            FileAttachmentCard(
                name = file.name,
                symbolName = AttachmentFileType.symbolName(file.name, file.mime),
                width = width,
                height = height,
                detailLines = if (file.isFailed) 2 else 1,
                modifier = Modifier.background(SystemColors.secondarySystemFill, shape),
            ) {
                when (val status = file.status) {
                    is ComposerFileAttachment.Status.uploading -> Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("Uploading ${percent(file.progress)}")
                        LinearProgressIndicator(progress = { file.progress.toFloat() }, modifier = Modifier.width(120.dp))
                    }
                    is ComposerFileAttachment.Status.uploaded -> Text(subtitle(file))
                    is ComposerFileAttachment.Status.failed -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        SystemImage(
                            if (canRetry) "arrow.clockwise" else "exclamationmark.triangle",
                            null,
                            Modifier.size(12.dp),
                            tint = SystemColors.systemRed,
                        )
                        Text(status.message, color = SystemColors.systemRed, maxLines = 2)
                    }
                }
            }
        }
        val label = if (file.isFailed && canRetry) "Retry uploading ${file.name}" else "File attachment ${file.name}"
        if (file.isFailed && canRetry) {
            Box(Modifier.clickable(onClick = onRetry).semantics { contentDescription = label }) {
                card()
            }
        } else {
            androidx.compose.foundation.layout.Box(Modifier.semantics { contentDescription = label }) { card() }
        }
    }

    fun subtitle(file: ComposerFileAttachment) = AttachmentFileType.subtitle(file.name, file.mime, file.size)

    fun percent(value: Double): String {
        val format = NumberFormat.getPercentInstance()
        format.maximumFractionDigits = 0
        return format.format(value)
    }
}

/// `.rect(cornerRadius:)` / `.continuous`. Smoothing 0.6 is the iOS curve.
internal fun iosContinuousCorner(radius: Dp): Shape = IosContinuousCorner(radius)

private class IosContinuousCorner(private val radius: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val px = with(density) { radius.toPx() }
        return Outline.Generic(continuousRoundRect(size.width, size.height, px))
    }
}

private fun continuousRoundRect(width: Float, height: Float, radius: Float): Path {
    val path = Path()
    if (width <= 0f || height <= 0f) return path
    val budget = min(width, height) / 2f
    val corner = continuousCorner(radius.coerceAtMost(budget), budget)
    if (corner.radius <= 0f) {
        path.addRect(Rect(0f, 0f, width, height))
        return path
    }
    val pen = Pen(path)
    pen.moveTo(width - corner.p, 0f)
    pen.cubic(corner.a, 0f, corner.a + corner.b, 0f, corner.a + corner.b + corner.c, corner.d)
    pen.arc(corner.radius, corner.arc, corner.arc)
    pen.cubic(corner.d, corner.c, corner.d, corner.b + corner.c, corner.d, corner.a + corner.b + corner.c)
    pen.lineTo(width, height - corner.p)
    pen.cubic(0f, corner.a, 0f, corner.a + corner.b, -corner.d, corner.a + corner.b + corner.c)
    pen.arc(corner.radius, -corner.arc, corner.arc)
    pen.cubic(-corner.c, corner.d, -(corner.b + corner.c), corner.d, -(corner.a + corner.b + corner.c), corner.d)
    pen.lineTo(corner.p, height)
    pen.cubic(-corner.a, 0f, -(corner.a + corner.b), 0f, -(corner.a + corner.b + corner.c), -corner.d)
    pen.arc(corner.radius, -corner.arc, -corner.arc)
    pen.cubic(-corner.d, -corner.c, -corner.d, -(corner.b + corner.c), -corner.d, -(corner.a + corner.b + corner.c))
    pen.lineTo(0f, corner.p)
    pen.cubic(0f, -corner.a, 0f, -(corner.a + corner.b), corner.d, -(corner.a + corner.b + corner.c))
    pen.arc(corner.radius, corner.arc, -corner.arc)
    pen.cubic(corner.c, -corner.d, corner.b + corner.c, -corner.d, corner.a + corner.b + corner.c, -corner.d)
    pen.close()
    return path
}

private data class ContinuousCorner(
    val a: Float,
    val b: Float,
    val c: Float,
    val d: Float,
    val p: Float,
    val arc: Float,
    val radius: Float,
)

private fun continuousCorner(radius: Float, budget: Float): ContinuousCorner {
    if (radius <= 0f) return ContinuousCorner(0f, 0f, 0f, 0f, 0f, 0f, 0f)
    val smooth = min(0.6f, budget / radius - 1f).coerceAtLeast(0f)
    val p = min((1f + smooth) * radius, budget)
    val arcMeasure = 90f * (1f - smooth)
    val arc = sin(arcMeasure / 2f * degrees) * radius * sqrt(2f)
    val alpha = (90f - arcMeasure) / 2f
    val p3ToP4 = radius * tan(alpha / 2f * degrees)
    val beta = 45f * smooth
    val c = p3ToP4 * cos(beta * degrees)
    val d = c * tan(beta * degrees)
    val b = (p - arc - c - d) / 3f
    return ContinuousCorner(a = 2f * b, b = b, c = c, d = d, p = p, arc = arc, radius = radius)
}

private class Pen(private val path: Path) {
    private var x = 0f
    private var y = 0f

    fun moveTo(x: Float, y: Float) {
        this.x = x
        this.y = y
        path.moveTo(x, y)
    }

    fun lineTo(x: Float, y: Float) {
        this.x = x
        this.y = y
        path.lineTo(x, y)
    }

    fun cubic(dx1: Float, dy1: Float, dx2: Float, dy2: Float, dx3: Float, dy3: Float) {
        path.cubicTo(x + dx1, y + dy1, x + dx2, y + dy2, x + dx3, y + dy3)
        x += dx3
        y += dy3
    }

    fun arc(radius: Float, dx: Float, dy: Float) {
        if (radius <= 0f || (dx == 0f && dy == 0f)) {
            lineTo(x + dx, y + dy)
            return
        }
        val x1 = x
        val y1 = y
        val x2 = x + dx
        val y2 = y + dy
        val center = svgArc(x1, y1, x2, y2, radius)
        path.arcTo(
            Rect(center.cx - center.radius, center.cy - center.radius, center.cx + center.radius, center.cy + center.radius),
            center.start,
            center.sweep,
            false,
        )
        x = x2
        y = y2
    }

    fun close() = path.close()
}

private class ArcCenter(val cx: Float, val cy: Float, val radius: Float, val start: Float, val sweep: Float)

private fun svgArc(x1: Float, y1: Float, x2: Float, y2: Float, radius: Float): ArcCenter {
    val dx = (x1 - x2) / 2f
    val dy = (y1 - y2) / 2f
    var rx = radius
    var ry = radius
    val lambda = dx * dx / (rx * rx) + dy * dy / (ry * ry)
    if (lambda > 1f) {
        val scale = sqrt(lambda)
        rx *= scale
        ry *= scale
    }
    val rx2 = rx * rx
    val ry2 = ry * ry
    val numerator = (rx2 * ry2 - rx2 * dy * dy - ry2 * dx * dx).coerceAtLeast(0f)
    val denominator = rx2 * dy * dy + ry2 * dx * dx
    val coef = sqrt(numerator / denominator)
    val cx = coef * rx * dy / ry + (x1 + x2) / 2f
    val cy = coef * -ry * dx / rx + (y1 + y2) / 2f
    val start = vectorAngle(1f, 0f, (x1 - cx) / rx, (y1 - cy) / ry)
    var sweep = vectorAngle((x1 - cx) / rx, (y1 - cy) / ry, (x2 - cx) / rx, (y2 - cy) / ry)
    if (sweep < 0f) sweep += (2f * PI).toFloat()
    return ArcCenter(cx, cy, rx, Math.toDegrees(start.toDouble()).toFloat(), Math.toDegrees(sweep.toDouble()).toFloat())
}

private fun vectorAngle(ux: Float, uy: Float, vx: Float, vy: Float): Float {
    val dot = ux * vx + uy * vy
    val length = sqrt(ux * ux + uy * uy) * sqrt(vx * vx + vy * vy)
    if (length == 0f) return 0f
    var angle = acos((dot / length).coerceIn(-1f, 1f))
    if (ux * vy - uy * vx < 0f) angle = -angle
    return angle
}

private const val degrees = (PI / 180.0).toFloat()

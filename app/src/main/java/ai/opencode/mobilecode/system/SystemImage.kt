package ai.opencode.mobilecode.system

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/**
 * Sizes a [SystemImage] so its glyph matches an SF Symbol at `.font(.system(size: points))`.
 * A Material symbol draws its glyph in about 83% of its box.
 */
fun Modifier.symbolSize(points: Number): Modifier = size((points.toFloat() * SymbolBoxScale).dp)

/** Sizes a [SystemImage] like an SF Symbol in a text style, `.font(.footnote)`. It scales with the font size. */
@Composable
fun Modifier.symbolSize(style: TextStyle): Modifier = size(with(LocalDensity.current) { (style.fontSize * SymbolBoxScale).toDp() })

private const val SymbolBoxScale = 1.2f

@Composable
fun SystemImage(
    name: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    val id = SymbolNames.drawable(name)
    if (id == null) {
        Box(modifier.size(24.dp), contentAlignment = Alignment.Center) {
            Text(name, color = SystemColors.systemRed, style = TextStyles.caption2, maxLines = 2)
        }
        return
    }
    Icon(
        painter = painterResource(id),
        contentDescription = contentDescription,
        modifier = modifier,
        tint = tint,
    )
}

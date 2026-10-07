package ai.opencode.mobilecode.system

import ai.opencode.mobilecode.R
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object TextStyles {
    val largeTitle = style(34, 41, FontWeight.Normal)
    val title = style(28, 34, FontWeight.Normal)
    val title2 = style(22, 28, FontWeight.Normal)
    val title3 = style(20, 25, FontWeight.Normal)
    val headline = style(17, 22, FontWeight.SemiBold)
    val body = style(17, 22, FontWeight.Normal)
    val callout = style(16, 21, FontWeight.Normal)
    val subheadline = style(15, 20, FontWeight.Normal)
    val footnote = style(13, 18, FontWeight.Normal)
    val caption = style(12, 16, FontWeight.Normal)
    val caption2 = style(11, 13, FontWeight.Normal)
    val code = subheadline.copy(fontFamily = FontFamily.Monospace)
    val tool = footnote.copy(fontFamily = FontFamily.Monospace)
}

val TextStyle.semibold: TextStyle
    get() = copy(fontWeight = FontWeight.SemiBold)

val TextStyle.medium: TextStyle
    get() = copy(fontWeight = FontWeight.Medium)

val TextStyle.bold: TextStyle
    get() = copy(fontWeight = FontWeight.Bold)

object FontFamilies {
    val Monospace = FontFamily.Monospace
    val BerkeleyMono = FontFamily(
        berkeley(FontWeight.Normal, 400),
        berkeley(FontWeight.Medium, 500),
        berkeley(FontWeight.SemiBold, 600),
        berkeley(FontWeight.Bold, 700),
    )
}

private fun style(size: Int, lineHeight: Int, weight: FontWeight) = TextStyle(
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    fontWeight = weight,
    fontFamily = FontFamily.Default,
)

private fun berkeley(weight: FontWeight, axis: Int) = Font(
    R.font.berkeley_mono_variable,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(axis)),
)

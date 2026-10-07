package ai.opencode.mobilecode.system

import ai.opencode.mobilecode.R
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource

@Composable
fun OpenCodeWordmark(
    modifier: Modifier = Modifier,
    simple: Boolean = false,
    contentDescription: String? = "OpenCode",
) {
    val dark = isSystemInDarkTheme()
    val id = when {
        simple && dark -> R.drawable.opencode_wordmark_simple_dark
        simple -> R.drawable.opencode_wordmark_simple_light
        dark -> R.drawable.opencode_wordmark_dark
        else -> R.drawable.opencode_wordmark_light
    }
    Image(
        painter = painterResource(id),
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
}

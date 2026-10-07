package ai.opencode.mobilecode.system

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration

@Composable
fun ForcedAppearance(dark: Boolean, content: @Composable () -> Unit) {
    val configuration = Configuration(LocalConfiguration.current)
    val night = if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
    configuration.uiMode = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv() or night
    CompositionLocalProvider(LocalConfiguration provides configuration, content = content)
}

@Composable
fun SystemTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) {
        darkColorScheme(
            primary = SystemColors.systemBlue,
            onPrimary = SystemColors.systemBackground,
            error = SystemColors.systemRed,
            background = SystemColors.systemBackground,
            surface = SystemColors.secondarySystemGroupedBackground,
            onBackground = SystemColors.label,
            onSurface = SystemColors.label,
            onSurfaceVariant = SystemColors.secondaryLabel,
            outline = SystemColors.separator,
        )
    } else {
        lightColorScheme(
            primary = SystemColors.systemBlue,
            onPrimary = SystemColors.systemBackground,
            error = SystemColors.systemRed,
            background = SystemColors.systemBackground,
            surface = SystemColors.secondarySystemGroupedBackground,
            onBackground = SystemColors.label,
            onSurface = SystemColors.label,
            onSurfaceVariant = SystemColors.secondaryLabel,
            outline = SystemColors.separator,
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

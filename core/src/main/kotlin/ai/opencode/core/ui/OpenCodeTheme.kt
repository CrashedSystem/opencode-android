package ai.opencode.core.ui

import ai.opencode.core.motion.OpenCodeMotionScheme
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.sharing.shared
import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat

/**
 * The app's theme: wraps [MaterialExpressiveTheme] with a [ColorScheme] built from the iOS
 * theme ([OpenCodeColors.toColorScheme]), the M3 type scale [OpenCodeTypography], [OpenCodeShapes]
 * and the Material 3 Expressive [OpenCodeMotionScheme], and provides [OpenCodeColors] for the colours
 * Material has no role for. The System theme uses the wallpaper's dynamic colour instead
 * ([DynamicSystemTheme]); the curated themes keep their fixed palettes.
 *
 * At the app root, use [OpenCodeAppTheme], which reads the persisted Settings → Appearance
 * choice. Previews and the design-system gallery pass a theme explicitly.
 *
 * @param theme the curated theme (iOS `AppTheme`).
 * @param appearance System / Light / Dark override.
 * @param highContrast iOS Increase Contrast; defaults to the system contrast setting.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OpenCodeTheme(
    theme: AppTheme = AppTheme.System,
    appearance: AppearanceColorScheme = AppearanceColorScheme.System,
    highContrast: Boolean = rememberHighContrast(),
    reduceMotion: Boolean = rememberReduceMotion(),
    content: @Composable () -> Unit,
) {
    val isDark = appearance.resolveIsDark(isSystemInDarkTheme())
    val context = LocalContext.current
    // A wallpaper change arrives as a configuration change.
    val configuration = LocalConfiguration.current
    val dynamicScheme: ColorScheme? = remember(theme, isDark, context, configuration) {
        if (theme == AppTheme.System) DynamicSystemTheme.colorScheme(context, isDark) else null
    }
    val colors = remember(theme, isDark, highContrast, dynamicScheme) {
        if (dynamicScheme != null) {
            DynamicSystemTheme.colors(dynamicScheme, isDark, highContrast)
        } else {
            OpenCodeColors.resolve(theme, isDark, highContrast)
        }
    }
    val colorScheme = remember(colors, dynamicScheme) { dynamicScheme ?: colors.toColorScheme() }
    val motionScheme = remember(reduceMotion) { OpenCodeMotionScheme(reduceMotion) }
    CompositionLocalProvider(LocalOpenCodeColors provides colors) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            motionScheme = motionScheme,
            shapes = OpenCodeShapes,
            typography = OpenCodeTypography,
            content = content,
        )
    }
}

/**
 * [OpenCodeTheme] driven by the persisted [ThemeSharedKeys] (iOS `applyAppAppearance()`).
 * Use once at the root of each window/activity. Like iOS's window
 * `overrideUserInterfaceStyle`, the window's status and navigation bars follow the app's
 * effective appearance rather than the system's night mode.
 */
@Composable
fun OpenCodeAppTheme(content: @Composable () -> Unit) {
    val appearance = rememberAppAppearance()
    SystemBarAppearance(isDark = appearance.colorScheme.resolveIsDark(isSystemInDarkTheme()))
    OpenCodeTheme(theme = appearance.theme, appearance = appearance.colorScheme, content = content)
}

/**
 * Light or dark status and navigation bar content for the hosting activity's window. Applied
 * when the appearance changes (not on every recomposition), so a screen that restyles the bars
 * for itself, such as the media viewer, keeps its style.
 */
@Composable
private fun SystemBarAppearance(isDark: Boolean) {
    val view = LocalView.current
    val window = remember(view) { view.context.findActivity()?.window }
    DisposableEffect(window, isDark) {
        if (window != null) {
            WindowCompat.getInsetsController(window, window.decorView).run {
                isAppearanceLightStatusBars = !isDark
                isAppearanceLightNavigationBars = !isDark
            }
        }
        onDispose {}
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** The persisted Settings → Appearance choice. */
@Immutable
data class AppAppearance(val theme: AppTheme, val colorScheme: AppearanceColorScheme)

/** Observes [ThemeSharedKeys] (read-only; mutate from the Settings reducer). */
@Composable
fun rememberAppAppearance(): AppAppearance {
    val themeShared = remember { shared(ThemeSharedKeys.appTheme) }
    val schemeShared = remember { shared(ThemeSharedKeys.appearanceColorScheme) }
    val theme: State<AppTheme> = themeShared.flow.collectAsState()
    val scheme: State<AppearanceColorScheme> = schemeShared.flow.collectAsState()
    return AppAppearance(theme.value, scheme.value)
}

/**
 * Android's counterpart of iOS Increase Contrast: the system contrast level
 * (`UiModeManager.getContrast()`, Android 14+) at medium or high. Live-updating.
 */
@Composable
fun rememberHighContrast(): Boolean {
    val context = LocalContext.current
    val uiModeManager = remember(context) { context.getSystemService(UiModeManager::class.java) }
    var contrast by remember(uiModeManager) { mutableFloatStateOf(uiModeManager?.contrast ?: 0f) }
    DisposableEffect(uiModeManager) {
        val manager = uiModeManager ?: return@DisposableEffect onDispose {}
        val listener = UiModeManager.ContrastChangeListener { contrast = it }
        manager.addContrastChangeListener(ContextCompat.getMainExecutor(context), listener)
        onDispose { manager.removeContrastChangeListener(listener) }
    }
    return contrast >= HIGH_CONTRAST_THRESHOLD
}

private const val HIGH_CONTRAST_THRESHOLD = 0.5f

/** Accessors for the design system inside [OpenCodeTheme]. */
object OpenCodeTheme {
    /** iOS semantic colours (`ThemePalette.*`). */
    val colors: OpenCodeColors
        @Composable @ReadOnlyComposable get() = LocalOpenCodeColors.current

    /** Material roles (mapped from the iOS colours). */
    val colorScheme: ColorScheme
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme

    /** iOS text styles by name (`OpenCodeTheme.textStyles.footnote`). */
    val textStyles: OpenCodeTextStyles get() = OpenCodeTextStyles
}

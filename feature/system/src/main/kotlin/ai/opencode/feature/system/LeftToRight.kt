package ai.opencode.feature.system

import android.content.res.Configuration
import java.util.Locale

/**
 * Keeps a window's configuration left-to-right while the app ships English only, as iOS does
 * (Y-01). `supportsRtl="false"` resolves views to LTR, but under Arabic or Hebrew the
 * configuration still says RTL, and every Compose window (popups, dialogs) starts its layout
 * direction from the configuration: its text is laid out RTL before the view resolves to LTR and
 * keeps that paragraph direction (“…Manage Servers”). Each activity applies this override in
 * `attachBaseContext`, so windows start LTR. Revisit when RTL translations ship.
 */
object LeftToRight {
    fun configuration(): Configuration = Configuration().apply { setLayoutDirection(Locale.ENGLISH) }
}

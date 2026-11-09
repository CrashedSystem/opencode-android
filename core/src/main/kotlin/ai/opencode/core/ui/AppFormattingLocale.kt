package ai.opencode.core.ui

import java.util.Locale

/**
 * The locale dates, times and numbers are written in: English, the app's only language, with the user's
 * region and preferences (hour cycle, numbering). iOS formats with `Locale.current`, which in an
 * English-only bundle is English in the user's region (“Gestern” never appears beside English
 * text, a German region still gets its 24-hour clock and day-month order).
 */
fun appFormattingLocale(system: Locale = Locale.getDefault(Locale.Category.FORMAT)): Locale =
    try {
        Locale.Builder().setLocale(system).setLanguage("en").setScript("").setVariant("").build()
    } catch (_: java.util.IllformedLocaleException) {
        Locale.Builder().setLanguage("en").build()
    }

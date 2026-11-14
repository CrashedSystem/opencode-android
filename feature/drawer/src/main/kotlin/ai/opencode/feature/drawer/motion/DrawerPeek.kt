package ai.opencode.feature.drawer.motion

import ai.opencode.core.strings.localized
import ai.opencode.feature.drawer.R
import ai.opencode.core.ui.appFormattingLocale
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

/**
 * A session's last activity as the drawer's time reveal shows it (iOS `DrawerTimestamp`): the
 * time today, then Yesterday, the weekday within the week, and a short numeric date. Formats
 * are java.time's localized short styles (iOS `Date.FormatStyle` `.shortened` / `.numeric`).
 * Pass [hour24] (`DateFormat.is24HourFormat`) so the time follows the device's 24-hour setting,
 * as iOS's `.shortened` follows the user's.
 */
object DrawerTimestamp {
    private val cache = ConcurrentHashMap<String, String>()

    fun text(
        date: Instant,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = appFormattingLocale(),
        hour24: Boolean? = null,
    ): String = Day(now, zone, locale, hour24).text(date)

    /** Today, worked out once for a list of times (`DrawerListModel.build`). */
    class Day(
        now: Instant,
        private val zone: ZoneId = ZoneId.systemDefault(),
        private val locale: Locale = appFormattingLocale(),
        private val hour24: Boolean? = null,
    ) {
        private val today = now.atZone(zone).truncatedTo(ChronoUnit.DAYS)
        private val todayDate = today.toLocalDate()
        private val tag = locale.toLanguageTag()

        fun text(date: Instant): String {
            val zoned = date.atZone(zone)
            val days = ChronoUnit.DAYS.between(zoned.toLocalDate(), todayDate).toInt()
            // iOS `MinuteTimestampCache`: one entry per minute, day bucket, locale and zone.
            val key = "${date.epochSecond / 60}|$days|$tag|${zone.id}|$hour24"
            cache[key]?.let { return it }
            val formatted = uncachedText(zoned, today, days, locale, hour24)
            if (cache.size > 2_000) cache.clear()
            cache[key] = formatted
            return formatted
        }
    }

    private fun uncachedText(date: ZonedDateTime, today: ZonedDateTime, days: Int, locale: Locale, hour24: Boolean?): String {
        val time = if (hour24 == null) {
            DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
        } else {
            DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, if (hour24) "Hm" else "hm"), locale)
        }
        // iOS `.numeric` writes the full year ("9/30/2026"); java.time's short style has two digits.
        val numericPattern = DateTimeFormatterBuilder.getLocalizedDateTimePattern(FormatStyle.SHORT, null, IsoChronology.INSTANCE, locale)
        val numeric = DateTimeFormatter.ofPattern(numericPattern.replace(Regex("y+"), "y"), locale)
        if (days == 0 && !date.isBefore(today)) return date.format(time)
        if (!date.isBefore(today)) return date.format(numeric)
        if (!date.isBefore(today.minusDays(1))) return localized(R.string.drawer_date_yesterday)
        if (!date.isBefore(today.minusDays(6))) return date.format(DateTimeFormatter.ofPattern("EEEE", locale))
        return date.format(numeric)
    }
}

/** A drag on the open drawer that slides the rows to show their times (iOS `DrawerRevealPhase`). */
sealed interface DrawerRevealPhase {
    data object Began : DrawerRevealPhase
    /** Translation in dp since the pan was recognized. */
    data class Changed(val translation: Float) : DrawerRevealPhase
    data object Ended : DrawerRevealPhase
}

/**
 * How far a sideways drag on the open drawer has slid the session rows, uncovering their times
 * (iOS `DrawerTimeReveal`). Only the rows read it, in their layer blocks, so a drag never
 * recomposes the drawer around them. The rows spring back from where they are on [DrawerRevealPhase.Ended]
 * (the list owns that spring).
 */
@Stable
class DrawerTimeReveal {
    /** The rows' slide in dp; zero under Reduce Motion, where the times fade in place. */
    var offset: Float by mutableFloatStateOf(0f)
        private set
    /** The times' opacity. */
    var progress: Float by mutableFloatStateOf(0f)
        private set
    /** The furthest this drag reached (lab trace). */
    var peak: Float by mutableFloatStateOf(0f)
        private set
    var peakProgress: Float by mutableFloatStateOf(0f)
        private set

    fun update(phase: DrawerRevealPhase, reach: Float, reducedMotion: Boolean) {
        when (phase) {
            is DrawerRevealPhase.Changed -> {
                val translation = phase.translation
                if (translation <= 0 && offset == 0f && progress == 0f) return
                if (reducedMotion) {
                    offset = 0f
                    progress = (translation / reach).coerceIn(0f, 1f)
                } else {
                    offset = offset(translation, reach)
                    progress = min(offset / (reach * 0.8f), 1f)
                }
                peak = max(peak, offset)
                peakProgress = max(peakProgress, progress)
            }
            DrawerRevealPhase.Began -> {
                peak = 0f
                peakProgress = 0f
            }
            DrawerRevealPhase.Ended -> {
                offset = 0f
                progress = 0f
            }
        }
    }

    companion object {
        /** iOS `-lab-reveal-trace`; no launch arguments on Android. */
        const val IS_TRACED = false
        /** Between a time and the row it belongs to (dp). */
        const val SPACING = 8f
        /** How far the card-edge fade's mask reaches before a row's leading edge, so the revealed time isn't masked off (dp). */
        const val FADE_GUTTER = 160f
        /** iOS `@ScaledMetric(relativeTo: .footnote) timeRevealReach = 76`: scale by the font scale. */
        const val REACH = 76f

        /** The rows follow the finger until the times are uncovered ([reach]), then rubber-band toward a little further. */
        fun offset(translation: Float, reach: Float): Float {
            if (translation <= 0 || reach <= 0) return 0f
            val followed = translation * 0.85f
            if (followed <= reach) return followed
            val limit = reach * 0.35f
            val excess = followed - reach
            return reach + limit * (1 - 1 / (excess * 0.55f / limit + 1))
        }
    }
}

/** `-lab-reveal-trace`: the furthest the last reveal reached and where the rows rest now (iOS `DrawerRevealTrace`). */
@Composable
fun DrawerRevealTrace(reveal: DrawerTimeReveal) {
    Box(
        Modifier
            .size(1.dp)
            .testTag("drawer-time-reveal")
            .semantics {
                stateDescription = "peak=%.0f progress=%.2f offset=%.0f".format(Locale.US, reveal.peak, reveal.peakProgress, reveal.offset)
            },
    )
}

/**
 * Whether a keyboard overlaps the screen (iOS `DrawerKeyboardMonitor`), so the drawer can put
 * its footer backdrop at its bottom while any keyboard is on screen. iOS keeps one shared
 * instance; here the drawer container owns one and [rememberDrawerKeyboardHold] feeds it.
 */
@Stable
class DrawerKeyboardMonitor {
    /** The IME overlaps the window (`WindowInsets.ime` bottom > 0). */
    var isImeVisible: Boolean by mutableStateOf(false)
    /** The drawer holds the composer's keyboard ([DrawerKeyboardHold]). */
    var isHoldingKeyboard: Boolean by mutableStateOf(false)
    val isKeyboardOnScreen: Boolean get() = isImeVisible || isHoldingKeyboard
}

/**
 * `-lab-drawer-controls-trace`: where the drawer's New Session and Settings have stood (iOS
 * `DrawerControlsTrace`). [top] is the controls' resting top edge, [highest] the highest it has
 * been laid out, so a test can tell they never rose with the keyboard.
 */
@Stable
class DrawerControlsTrace {
    var top: Float by mutableFloatStateOf(0f)
        private set
    var bottom: Float by mutableFloatStateOf(0f)
        private set
    var highest: Float by mutableFloatStateOf(Float.POSITIVE_INFINITY)
        private set

    fun record(minY: Float, maxY: Float) {
        top = minY
        bottom = maxY
        highest = min(highest, minY)
    }

    companion object {
        /** iOS reads a launch argument; no launch arguments on Android. */
        const val IS_TRACED = false
    }
}

/** Records the controls' frame in the root, in px (iOS `DrawerControlsTraceProbe`). */
fun Modifier.drawerControlsTraceProbe(trace: DrawerControlsTrace): Modifier = onGloballyPositioned {
    val y = it.positionInRoot().y
    trace.record(y, y + it.size.height)
}

/** Publishes [DrawerControlsTrace] as the `drawer-controls-trace` element (iOS `DrawerControlsTraceView`). */
@Composable
fun DrawerControlsTraceView(trace: DrawerControlsTrace, monitor: DrawerKeyboardMonitor) {
    Box(
        Modifier
            .size(1.dp)
            .testTag("drawer-controls-trace")
            .semantics {
                stateDescription = "top=%.0f highest=%.0f kbOn=%s".format(Locale.US, trace.top, trace.highest, monitor.isKeyboardOnScreen)
            },
    )
}

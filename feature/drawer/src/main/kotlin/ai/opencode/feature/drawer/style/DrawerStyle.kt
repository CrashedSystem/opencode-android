package ai.opencode.feature.drawer.style

import ai.opencode.core.tuning.Tunable
import ai.opencode.core.tuning.TuningStore
import ai.opencode.core.ui.OpenCodeColors
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeIconSize
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The drawer's grid (iOS `DrawerMetrics`). Row surfaces (highlights, search, the floating
 * controls) stand [rowEdge] in from the drawer's sides, and everything at a row's start
 * (titles, headers, shortcut symbols) begins [textInset] inside that, on one leading edge
 * 28 dp in (Android: M3 navigation drawer insets; iOS 20). Status sits at the trailing end,
 * [trailingInset] inside the row.
 */
object DrawerMetrics {
    val rowEdge: Dp get() = DrawerTunables.rowEdge
    val textInset: Dp get() = DrawerTunables.textInset
    val trailingInset: Dp get() = DrawerTunables.trailingInset

    /** The one leading edge. */
    val leadingEdge: Dp get() = rowEdge + textInset

    /** A row's highlight, and the one shape its hold lifts. */
    val rowCornerRadius: Dp get() = DrawerTunables.rowCornerRadius

    /** A session or shortcut row's height. */
    val rowHeight: Dp get() = DrawerTunables.rowHeight
}

/**
 * A Dynamic Type style for the drawer's text (iOS `DrawerTextStyle`). The styles are in sp,
 * so a tuned size keeps following the system font scale.
 */
enum class DrawerTextStyle {
    Title, Title2, Title3, Headline, Body, Callout, Subheadline, Footnote;

    /** iOS `font`: the style at its own weight. */
    val font: TextStyle
        get() = when (this) {
            Title -> OpenCodeTextStyles.title1
            Title2 -> OpenCodeTextStyles.title2
            Title3 -> OpenCodeTextStyles.title3
            Headline -> OpenCodeTextStyles.headline
            Body -> OpenCodeTextStyles.body
            Callout -> OpenCodeTextStyles.callout
            Subheadline -> OpenCodeTextStyles.subheadline
            Footnote -> OpenCodeTextStyles.footnote
        }

    /** A heading at this size: Headline is already semibold. */
    val heading: TextStyle
        get() = if (this == Headline) OpenCodeTextStyles.headline else font.copy(fontWeight = FontWeight.SemiBold)
}

/**
 * A drawer label's tone (iOS `DrawerTone`): the theme's label colours, so high contrast and
 * both appearances come with them.
 */
enum class DrawerTone {
    Primary, Secondary, Tertiary;

    fun color(colors: OpenCodeColors): Color = when (this) {
        Primary -> colors.text
        Secondary -> colors.secondaryText
        Tertiary -> colors.tertiaryText
    }

    /** The tone in the current [OpenCodeTheme]. */
    val color: Color
        @Composable @ReadOnlyComposable get() = color(OpenCodeTheme.colors)
}

/** A drawer row title's weight (iOS `DrawerFontWeight`). */
enum class DrawerFontWeight(val weight: FontWeight) {
    Regular(FontWeight.Normal),
    Medium(FontWeight.Medium),
    Semibold(FontWeight.SemiBold),
    Bold(FontWeight.Bold),
}

/**
 * How a session row's title reads (D-51, Quiet Hierarchy): the selected row semibold;
 * unread, waiting and running rows at `unreadWeight` in full primary; read rows regular,
 * dimmed to `readOpacity` (full primary under high contrast); muted rows secondary.
 */
enum class DrawerRowEmphasis {
    Selected, Active, Read, Muted;

    val weight: FontWeight get() = weight()

    fun weight(
        unread: DrawerFontWeight = DrawerTunables.unreadWeight,
        read: DrawerFontWeight = DrawerTunables.readWeight,
    ): FontWeight = when (this) {
        Selected -> FontWeight.SemiBold
        Active -> unread.weight
        Read -> read.weight
        Muted -> FontWeight.Normal
    }

    fun opacity(increasedContrast: Boolean, readOpacity: Float = DrawerTunables.readOpacity): Float =
        if (this == Read && !increasedContrast) readOpacity else 1f

    companion object {
        /**
         * iOS `init(selected:muted:indicator:)`. [hasIndicator] is
         * `indicator != HomeFeature.State.Indicator.none` (attention, running or unread).
         */
        fun of(selected: Boolean, muted: Boolean, hasIndicator: Boolean): DrawerRowEmphasis = when {
            selected -> Selected
            muted -> Muted
            !hasIndicator -> Read
            else -> Active
        }
    }
}

/** How the Pinned and Recents headers are cased. */
enum class DrawerHeaderCase { TitleCase, SmallCaps, Uppercase }

/**
 * How the selected row is marked: a quiet fill (shipped), the accent colour's fill with
 * white text (an iPad sidebar), an outline, or a bar at the leading edge.
 */
enum class DrawerSelectionStyle { Fill, Tint, Outline, Bar }

/**
 * How a held row looks while its menu is up: a lifted card, grown and on a raised surface;
 * the row its own size on that surface (shipped); or the row as it is, no surface or shadow.
 */
enum class DrawerHoldStyle { Card, InPlace, Flat }

/** What sits between the shortcuts, Pinned and Recents: a header, a gap alone, or a hairline. */
enum class DrawerSectionBreak { Header, Gap, Hairline }

/**
 * The drawer's tunables (iOS `DrawerTunables` in `DrawerStyle.swift` and
 * `DrawerBackdrops.swift`) as their shipped defaults. iOS exposes them in Developer Mode's
 * Tuning Panel with presets (Spacing, Sidebar Style, Footer, Top/Bottom Edge); Android has
 * no Tuning Panel (`:devtools` is Android-native), so only the defaults are ported. iOS
 * points are dp; text sizes come from [DrawerTextStyle] in sp.
 *
 * The motion tunables (`recedeScale`, `dim*`, `shortcut*`) live
 * with their owners (`DrawerMotion.swift`, `DrawerSearchPill.swift`).
 */
object DrawerTunables {
    private val registeredStores = java.util.Collections.newSetFromMap(java.util.WeakHashMap<TuningStore, Boolean>())

    /**
     * iOS `DrawerTuningRegistration.registerIfNeeded`: [register] once per store while tuning is
     * on, so the list's every pass doesn't list the knobs again.
     */
    fun registerIfNeeded(store: TuningStore = TuningStore.shared) {
        if (!store.isEnabled) return
        synchronized(registeredStores) {
            if (!registeredStores.add(store)) return
        }
        register(store)
    }

    /**
     * iOS `DrawerTunables.register(in:)`: lists the drawer's tunable knobs (the pin drag's shadow
     * and edge scroll; motion runs on the shared motion roles, the rest stay constants).
     */
    fun register(store: TuningStore = TuningStore.shared) {
        if (!store.isEnabled) return
        store.register(pinLiftShadow, pinLiftShadowY, pinLiftShadowRadius, pinEdgeBand, pinEdgeSpeed)
    }

    // Spacing (DrawerStyle.swift). The leading edge is derived (`rowEdge + textInset`).
    /** Row height. Android: M3's navigation drawer item height (iOS 44). */
    val rowHeight: Dp = 56.dp
    /** Gap between rows. */
    val rowGap: Dp = 0.dp
    /** Highlight from the sides. Android: M3's 12 dp drawer item inset (iOS 10). */
    val rowEdge: Dp = 12.dp
    /** Text inside the highlight. Android: M3's 16 dp label start padding (iOS 10). */
    val textInset: Dp = 16.dp
    /** Status inside the highlight. Android: M3's 24 dp trailing padding (iOS 12). */
    val trailingInset: Dp = 24.dp
    /** Highlight corner radius. Android: half the row, M3's full-round indicator (iOS 10). */
    val rowCornerRadius: Dp = 28.dp
    /** Row text. */
    val rowFont: DrawerTextStyle = DrawerTextStyle.Body
    /** Room above a header. */
    val sectionAbove: Dp = 18.dp
    /** Header height. */
    val sectionHeight: Dp = 34.dp
    /** Room below a header. */
    val sectionBelow: Dp = 4.dp
    /** Header text. */
    val sectionFont: DrawerTextStyle = DrawerTextStyle.Headline
    /** Header tone. */
    val sectionTone: DrawerTone = DrawerTone.Secondary
    /** Unread and waiting weight. */
    val unreadWeight: DrawerFontWeight = DrawerFontWeight.Medium
    /** Read row tone. */
    const val readOpacity: Float = 0.86f
    /** Catch Up count. */
    val countFont: DrawerTextStyle = DrawerTextStyle.Subheadline
    /** Room above Catch Up. */
    val shortcutsTop: Dp = 4.dp
    /** Catch Up to What’s New. */
    val shortcutsGap: Dp = 0.dp
    /** Room below the shortcuts. */
    val shortcutsBelow: Dp = 0.dp
    /** Title band below. */
    val headerBottom: Dp = 4.dp
    /** Title to its action buttons (Android: the call, Settings and search buttons). */
    val searchGap: Dp = 12.dp
    /** Title text. */
    val titleFont: DrawerTextStyle = DrawerTextStyle.Title
    /** New Session button height. Android: the M3 extended FAB's 56 dp (iOS 50). */
    val footerHeight: Dp = 56.dp
    /**
     * New Session's margin from the drawer's end and bottom. Android: the M3 FAB's 16 dp margin
     * (iOS 18, the pill's trailing padding; its glyph bearing and lift are n/a, the FAB lays out
     * its own icon).
     */
    val footerPadding: Dp = 16.dp

    // The list's style beyond spacing (Sidebar Style). Each default is what shipped.
    /** Header weight. */
    val headerWeight: DrawerFontWeight = DrawerFontWeight.Semibold
    /** Header case. */
    val headerCase: DrawerHeaderCase = DrawerHeaderCase.TitleCase
    /** Header tracking (iOS points of tracking, as sp of letter spacing). */
    const val headerTracking: Float = 0f
    /** Pinned and Recents. */
    val sectionBreak: DrawerSectionBreak = DrawerSectionBreak.Header
    /** Gap between sections. */
    val sectionGap: Dp = 16.dp
    /** Unread and waiting tone. */
    val unreadTone: DrawerTone = DrawerTone.Primary
    /** Read weight. */
    val readWeight: DrawerFontWeight = DrawerFontWeight.Regular
    /** Selected row. */
    val selection: DrawerSelectionStyle = DrawerSelectionStyle.Fill
    /** Selected fill. */
    const val selectionOpacity: Float = 0.14f
    /** Search button size. Android: the 48 dp icon button (iOS 44). */
    val searchSize: Dp = 48.dp
    /** Project icon size. */
    val projectIconSize: Dp = 20.dp
    /** Project icon corner radius (at a 20 dp tile; scales with the tile). */
    val projectIconCornerRadius: Dp = 5.5.dp
    /** Project icon to title. */
    val projectIconGap: Dp = 9.dp
    /**
     * The tile's lighting (iOS D-137 Lit Glass Edge, a0fc7375): a highlight from the top,
     * a 0.5 dp white edge fading toward the bottom, a contact shadow.
     */
    const val projectIconHighlight: Float = 0.3f
    /** Project icon edge. */
    const val projectIconEdgeOpacity: Float = 0.6f
    /** Project icon shadow. */
    const val projectIconShadow: Float = 1f
    /**
     * A long title fades out over its last [titleFade] dp instead of an ellipsis, ending at
     * the row's trailing inset, or [statusGap] before a status indicator.
     */
    val titleFade: Dp = 20.dp
    /** Title fade to status. */
    val statusGap: Dp = 4.dp
    /**
     * While the time reveal slides the rows, they fade out at the card's edge
     * (`DrawerListSessionCell`): the fade moves onto the edge over the first
     * [revealFadeRamp] dp of slide and reaches full strength on `MotionRole.Swap`.
     */
    val revealFadeRamp: Dp = 32.dp
    /**
     * The dragged row (iOS `DrawerLiftedRow`): it grows by [pinLiftScale] as it lifts
     * (`MotionRole.MicroIn`), then springs from the finger's release into its slot (`MotionRole.Release`).
     */
    /** Dragged row grow. */
    const val pinLiftScale: Float = 1.03f
    /** Dragged row shadow: its opacity at full lift (iOS `DrawerLiftedRowView.apply(lift:)`). */
    val pinLiftShadow = Tunable("drawer.pin.liftShadow", "Dragged row shadow", default = 0.3, range = 0.0..0.8, step = 0.01)
    /** Dragged row shadow drop, dp. */
    val pinLiftShadowY = Tunable("drawer.pin.liftShadowY", "Dragged row shadow drop", default = 6f, range = 0.0..24.0, step = 0.5)
    /** Dragged row shadow blur, dp. */
    val pinLiftShadowRadius = Tunable("drawer.pin.liftShadowRadius", "Dragged row shadow blur", default = 14f, range = 0.0..40.0, step = 0.5)
    /**
     * Holding the dragged row within [pinEdgeBand] dp of the list's top or bottom scrolls it, up
     * to [pinEdgeSpeed] dp a second at the edge.
     */
    val pinEdgeBand = Tunable("drawer.pin.edgeBand", "Drag scroll edge", default = 64f, range = 24.0..140.0, step = 1.0)
    /** Drag scroll speed, dp/s. */
    val pinEdgeSpeed = Tunable("drawer.pin.edgeSpeed", "Drag scroll speed", default = 900f, range = 100.0..2400.0, step = 10.0)
    /** Held row. */
    val holdStyle: DrawerHoldStyle = DrawerHoldStyle.InPlace
    /**
     * How much of the system's grow to undo: the held row's preview starts this much
     * smaller. iOS grows a drawer row about 9.2 %, so 1.09 leaves it about 1 %.
     */
    const val holdLift: Float = 1.09f

    // The drawer's top and bottom edges (DrawerBackdrops.swift).
    /** Fade below the title. */
    val fade: Dp = 19.dp
    /** Blur radius (iOS's progressive blur; Android draws the wash only, see `DrawerHeaderBackdrop`). */
    val blurRadius: Dp = 2.5.dp
    /** Blur falloff. */
    val blurCurve: DrawerEdgeCurve = DrawerEdgeCurve.Smoothstep
    /** Wash at the title. */
    const val washOpacity: Float = 0.7f
    /** Wash stop (× title). */
    const val washStop: Float = 0.84f
    /** Room under the title. */
    val topGap: Dp = 4.dp
    /** Room above the controls. */
    val bottomGap: Dp = 12.dp
    /** Bottom edge. */
    val bottomEdge: DrawerEdgeStyle = DrawerEdgeStyle.Soft
    /** Rows under the buttons (× their height). */
    const val controlsReach: Float = 0.5f
    /** Bottom edge mode. */
    val bottomMode: DrawerBottomEdgeMode = DrawerBottomEdgeMode.System
    /** Fade above the buttons. */
    val bottomFade: Dp = 14.dp
    /** Bottom blur radius. */
    val bottomBlurRadius: Dp = 2.5.dp
    /** Bottom blur falloff. */
    val bottomBlurCurve: DrawerEdgeCurve = DrawerEdgeCurve.Smoothstep
    /** Wash at the buttons. */
    const val bottomWashOpacity: Float = 0.75f
    /** Bottom wash stop (× bar). */
    const val bottomWashStop: Float = 0.65f
}

/**
 * Pinned or Recents, in the header's size, weight, tone, case and tracking (iOS
 * `DrawerSectionTitle`). At the defaults: a semibold heading in secondary.
 */
@Composable
fun DrawerSectionTitle(title: String, modifier: Modifier = Modifier) {
    val style = DrawerTunables.sectionFont
    val weight = DrawerTunables.headerWeight
    val tracking = DrawerTunables.headerTracking
    val case = DrawerTunables.headerCase
    var font = if (weight == DrawerFontWeight.Semibold) style.heading else style.font.copy(fontWeight = weight.weight)
    if (tracking > 0) font = font.copy(letterSpacing = tracking.sp)
    if (case == DrawerHeaderCase.SmallCaps) font = font.copy(fontFeatureSettings = "smcp")
    Text(
        text = if (case == DrawerHeaderCase.Uppercase) title.uppercase() else title,
        style = font,
        color = DrawerTunables.sectionTone.color,
        maxLines = 1,
        modifier = modifier.semantics { heading() },
    )
}

/**
 * A drawer shortcut row in the session rows’ style (iOS `DrawerShortcutLabel`): a tinted
 * symbol that starts on the drawer's leading edge, its title, and a trailing accessory in the
 * session rows' status place. The caller adds the click and the row's highlight.
 */
@Composable
fun DrawerShortcutLabel(
    systemImage: String,
    modifier: Modifier = Modifier,
    title: @Composable RowScope.() -> Unit,
    accessory: @Composable () -> Unit = {},
) {
    val fontScale = LocalDensity.current.fontScale
    // The symbol stops growing at XXXL (iOS `dynamicTypeSize(...xxxLarge)`), and its slot at 30.
    val symbolScale = fontScale.coerceAtMost(XXXL_FONT_SCALE)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = DrawerMetrics.rowHeight)
            .padding(start = DrawerMetrics.textInset, end = DrawerMetrics.trailingInset),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.width(minOf(22.dp * fontScale, 30.dp)), contentAlignment = Alignment.CenterStart) {
            OpenCodeIcon(
                sfName = systemImage,
                contentDescription = null,
                size = OpenCodeIconSize.medium * symbolScale,
                tint = OpenCodeTheme.colors.accent,
            )
        }
        ProvideTextStyle(DrawerTunables.rowFont.font) {
            title()
            Spacer(Modifier.weight(1f).widthIn(min = 4.dp))
            Box(Modifier.widthIn(min = 18.dp), contentAlignment = Alignment.Center) { accessory() }
        }
    }
}

/** [DrawerShortcutLabel] with a plain one-line title. */
@Composable
fun DrawerShortcutLabel(
    title: String,
    systemImage: String,
    modifier: Modifier = Modifier,
    accessory: @Composable () -> Unit = {},
) {
    DrawerShortcutLabel(
        systemImage = systemImage,
        modifier = modifier,
        title = { Text(title, maxLines = 1, style = LocalTextStyle.current, color = OpenCodeTheme.colors.text) },
        accessory = accessory,
    )
}

/**
 * The Catch Up row's hold menu (Mark All as Read), offered only while [enabled]: a long
 * press opens [items]; a tap runs [onClick]. Session rows use `DrawerRowInteraction` instead.
 * The menu's open state is view-local, as iOS's `contextMenu`.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecentRowMenu(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onClickLabel: String? = null,
    items: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(
        modifier.combinedClickable(
            onClickLabel = onClickLabel,
            onLongClick = if (enabled) ({ expanded = true }) else null,
            onClick = onClick,
        ),
    ) {
        content()
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            items { expanded = false }
        }
    }
}

/** A Pinned or Recents section header (iOS `DrawerSectionHeaderKind`). */
enum class DrawerSectionHeaderKind { Pinned, Recents }

/**
 * The empty-pins drop zone shown at the top of the session list while dragging an unpinned
 * row upward when nothing is pinned yet (iOS `DrawerEmptyPinDropZone`). Takes no touches.
 */
@Composable
fun DrawerEmptyPinDropZone(
    openProgress: Float,
    hoverProgress: Float,
    headerHeight: Dp,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val rowGap = DrawerTunables.rowGap
    val cornerRadius = DrawerMetrics.rowCornerRadius
    val hovered = hoverProgress >= 0.45f
    val colors = OpenCodeTheme.colors
    val accent = colors.accent
    val primary = colors.text
    val rowFont = DrawerTunables.rowFont.font
    Column(
        modifier
            .fillMaxSize()
            .testTag("drawer-empty-pin-drop-zone")
            .clearAndSetSemantics { contentDescription = "Drop to Pin" },
    ) {
        EmptyPinHeader(progress = openProgress, headerHeight = headerHeight)
        Row(
            modifier = Modifier
                .padding(horizontal = DrawerMetrics.rowEdge, vertical = rowGap / 2)
                .graphicsLayer { alpha = openProgress.coerceIn(0f, 1f) }
                .fillMaxWidth()
                .heightIn(min = DrawerMetrics.rowHeight)
                .drawBehind {
                    val radius = CornerRadius(cornerRadius.toPx())
                    val fill = if (hovered) accent.copy(alpha = 0.11f * hoverProgress) else primary.copy(alpha = 0.04f * openProgress)
                    drawRoundRect(color = fill, cornerRadius = radius)
                    val line = 1.dp.toPx()
                    val stroke = if (hovered) accent.copy(alpha = 0.32f * hoverProgress) else primary.copy(alpha = 0.11f * openProgress)
                    drawRoundRect(
                        color = stroke,
                        topLeft = Offset(line / 2, line / 2),
                        size = Size(size.width - line, size.height - line),
                        cornerRadius = CornerRadius((cornerRadius.toPx() - line / 2).coerceAtLeast(0f)),
                        style = Stroke(
                            width = line,
                            pathEffect = if (hovered) null else PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
                        ),
                    )
                }
                .padding(start = DrawerMetrics.textInset, end = DrawerMetrics.trailingInset),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.width(20.dp), contentAlignment = Alignment.CenterStart) {
                OpenCodeIcon(
                    symbol = if (hovered) SFSymbol.PinFill else SFSymbol.Pin,
                    contentDescription = null,
                    tint = if (hovered) accent else colors.secondaryText,
                    modifier = Modifier.graphicsLayer {
                        val scale = if (reducedMotion) 1f else 0.9f + 0.14f * hoverProgress
                        scaleX = scale
                        scaleY = scale
                        rotationZ = if (reducedMotion) 0f else (1f - hoverProgress) * 16f
                    },
                )
            }
            Text(
                "Drop to Pin",
                style = rowFont.copy(fontWeight = if (hovered) FontWeight.Medium else FontWeight.Normal),
                color = if (hovered) primary else colors.secondaryText,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f).widthIn(min = 4.dp))
        }
    }
}

@Composable
private fun EmptyPinHeader(progress: Float, headerHeight: Dp) {
    if (DrawerTunables.sectionBreak == DrawerSectionBreak.Header) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(headerHeight)
                .padding(
                    top = DrawerTunables.sectionAbove,
                    bottom = DrawerTunables.sectionBelow,
                    start = DrawerMetrics.leadingEdge,
                    end = DrawerMetrics.rowEdge,
                ),
            contentAlignment = Alignment.BottomStart,
        ) {
            Box(
                Modifier.heightIn(min = DrawerTunables.sectionHeight).graphicsLayer { alpha = progress.coerceIn(0f, 1f) },
                contentAlignment = Alignment.BottomStart,
            ) {
                DrawerSectionTitle("Pinned")
            }
        }
    } else {
        Spacer(Modifier.height(headerHeight))
    }
}

/** A row highlight's shape at [DrawerMetrics.rowCornerRadius]. */
val DrawerRowShape: RoundedCornerShape get() = RoundedCornerShape(DrawerMetrics.rowCornerRadius)

/**
 * The drawer's container colour, provided by `SessionDrawerContainer`. Android: the phone's
 * modal drawer is an M3 `ModalDrawerSheet` (`surfaceContainerLow`), so it stands off the session
 * under its scrim in dark mode too; the sidebar beside a session leaves it unspecified and keeps
 * the canvas (iOS draws both on the canvas).
 */
val LocalDrawerSurface = compositionLocalOf { Color.Unspecified }

/** [LocalDrawerSurface], or the canvas where nothing provides one. */
@Composable
@ReadOnlyComposable
fun drawerSurface(): Color = LocalDrawerSurface.current.takeOrElse { OpenCodeTheme.colors.canvas }

/** iOS XXXL body (23 pt) over Large (17 pt): where shortcut symbols stop growing. */
internal const val XXXL_FONT_SCALE = 23f / 17f

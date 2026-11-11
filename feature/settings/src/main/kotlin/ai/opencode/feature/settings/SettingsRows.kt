package ai.opencode.feature.settings

import ai.opencode.core.ui.OpenCodeSwitch
import ai.opencode.core.ui.OpenCodeTextStyles
import androidx.compose.ui.text.font.FontWeight
import ai.opencode.core.ui.OpenCodeTypography
import androidx.compose.foundation.BorderStroke
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeRadius
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import androidx.compose.foundation.clickable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Port of iOS `Settings/SettingsRows.swift` plus the inset-grouped `List`/`Section` chrome every
 * Settings page uses, as Material surfaces: a page is a `LazyColumn` of [settingsSection]s, each a
 * rounded card on the grouped canvas with an optional header and footer.
 *
 * Android divergence: the rows look like a Material 3 Expressive segmented list, as Android's own
 * Settings does. Rows are parted by small gaps instead of hairlines, icons are plain Material
 * icons instead of tinted tiles, a row's value sits under its title, navigation rows have no
 * chevron, section headers are in the accent colour, and text uses Material's list sizes.
 */

/** Settings' type: the app's desktop-derived scale (this branch), not Material's default Roboto list sizes. */
object SettingsText {
    /** Desktop list item: medium weight (`list.css` 530) at phone size. */
    val title: TextStyle = OpenCodeTextStyles.callout.copy(fontWeight = FontWeight.Medium)
    val summary: TextStyle = OpenCodeTextStyles.footnote
    val header: TextStyle = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.Medium)

    /** The app's (Inter) scale, with a larger headline for the Settings top app bar's large title. */
    val typography: Typography = OpenCodeTypography.copy(
        headlineMedium = OpenCodeTextStyles.title1,
        headlineLarge = OpenCodeTextStyles.largeTitle,
    )
}

/** iOS system tints for [SettingsIcon] tiles (`Color.purple`, `.blue`, …), light and dark. */
enum class SettingsTint(private val light: Long, private val dark: Long) {
    Red(0xFFFF3B30, 0xFFFF453A),
    Orange(0xFFFF9500, 0xFFFF9F0A),
    Yellow(0xFFFFCC00, 0xFFFFD60A),
    Green(0xFF34C759, 0xFF30D158),
    Mint(0xFF00C7BE, 0xFF63E6E2),
    Teal(0xFF30B0C7, 0xFF40CBE0),
    Cyan(0xFF32ADE6, 0xFF64D2FF),
    Blue(0xFF007AFF, 0xFF0A84FF),
    Indigo(0xFF5856D6, 0xFF5E5CE6),
    Purple(0xFFAF52DE, 0xFFBF5AF2),
    Pink(0xFFFF2D55, 0xFFFF375F),
    Brown(0xFFA2845E, 0xFFAC8E68),
    Gray(0xFF8E8E93, 0xFF8E8E93),
    ;

    fun color(isDark: Boolean): Color = Color(if (isDark) dark else light)

    @Composable
    fun color(): Color = color(OpenCodeTheme.colors.isDark)
}

/**
 * The icon in front of a settings row. iOS draws a white symbol on a tinted rounded tile; Android
 * draws a plain 24 dp Material icon in the secondary colour, as Material lists do. [tint] is
 * kept so the call sites stay as on iOS, but it isn't drawn.
 */
@Composable
fun SettingsIcon(symbol: String, tint: SettingsTint, modifier: Modifier = Modifier) {
    if (!LocalSettingsRowIcons.current) return
    OpenCodeIcon(symbol, contentDescription = null, modifier = modifier, size = SettingsIconSize, tint = OpenCodeTheme.colors.secondaryText)
}

/**
 * Whether settings rows here draw their leading [SettingsIcon]. Detail pages don't, as the
 * desktop's settings rows have none; the main Settings page provides `true`, since it works like
 * the desktop's settings sidebar, which has icons.
 */
val LocalSettingsRowIcons = staticCompositionLocalOf { false }

/** A settings row's icon size (Material list leading icon). */
val SettingsIconSize = 24.dp

@Composable
fun SettingsIcon(symbol: SFSymbol, tint: SettingsTint, modifier: Modifier = Modifier) =
    SettingsIcon(symbol.sfName, tint, modifier)

/**
 * iOS `dynamicTypeSize.isAccessibilitySize`: the accessibility text sizes start at AX1, whose body
 * is 28 pt against the default 17 pt.
 */
@Composable
internal fun isAccessibilitySize(): Boolean = LocalDensity.current.fontScale >= 28f / 17f

/** Minimum row height (iOS 44 pt list rows; Material's one-line list item is 56 dp). */
val SettingsRowMinHeight = 56.dp

/**
 * One section of an inset-grouped Settings list: a header (sentence case, in the accent colour as
 * Android's Settings shows one), the rows on a rounded card parted by [SettingsDivider] gaps, and
 * a footer.
 */
fun LazyListScope.settingsSection(
    key: Any,
    header: String? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    rows: @Composable ColumnScope.() -> Unit,
) {
    item(key = key, contentType = "settings-section") {
        SettingsSection(header = header, footer = footer, rows = rows)
    }
}

/** Plain-text footer convenience for [settingsSection]. */
fun footerText(text: String): @Composable ColumnScope.() -> Unit = { SettingsFooterText(text) }

@Composable
fun SettingsFooterText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = SettingsText.summary,
        color = OpenCodeTheme.colors.secondaryText,
        modifier = modifier,
    )
}

@Composable
fun SettingsSection(
    modifier: Modifier = Modifier,
    header: String? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    rows: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (header != null) {
            Text(
                header,
                style = SettingsText.header,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp).semantics { heading() },
            )
        }
        // The desktop's card: flat fill inside a hairline border, small radius.
        Surface(
            color = OpenCodeTheme.colors.groupedCard,
            shape = RoundedCornerShape(OpenCodeRadius.card),
            border = BorderStroke(1.dp, OpenCodeTheme.colors.border),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(content = rows)
        }
        if (footer != null) {
            Column(
                Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                content = footer,
            )
        }
    }
}

/**
 * What parts two rows on a section card: on this branch the desktop list's inset hairline (as
 * iOS draws), replacing Material 3 Expressive's 2 dp segmented gap. [inset] is kept so the call
 * sites stay as on iOS.
 */
@Composable
fun SettingsDivider(inset: Boolean = false) {
    // The desktop's `settings-row` border: a hairline inset to the card's padding on both sides,
    // or, under an iconed row, starting at the text like a list inset.
    var start = 16.dp
    if (inset && LocalSettingsRowIcons.current) {
        start = 16.dp + SettingsIconSize + 16.dp
    }
    HorizontalDivider(
        Modifier.padding(start = start, end = 16.dp),
        thickness = SettingsDividerThickness,
        color = OpenCodeTheme.colors.rowDivider,
    )
}

/** The row divider's weight: the desktop's 0.5 px line is one device pixel on a 2x screen. */
private val SettingsDividerThickness = 0.5.dp

/** A row's frame: full-width target, 16 dp sides, at least [SettingsRowMinHeight] tall, 16 dp between its parts. */
@Composable
fun SettingsRowFrame(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    role: Role? = Role.Button,
    content: @Composable RowScope.() -> Unit,
) {
    val clickable = if (onClick != null) Modifier.clickable(enabled = enabled, role = role, onClick = onClick) else Modifier
    Row(
        modifier
            .fillMaxWidth()
            .then(clickable)
            .heightIn(min = SettingsRowMinHeight)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

/**
 * A row's title with an optional summary under it (Android's preference summary: a row's value,
 * caption or few words), up to two lines.
 */
@Composable
fun SettingsRowText(title: String, summary: String?, modifier: Modifier = Modifier) {
    // Inter's tight line height sets the summary right against the title without a small gap.
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = SettingsText.title, color = OpenCodeTheme.colors.text)
        if (summary != null) {
            Text(
                summary,
                style = SettingsText.summary,
                color = OpenCodeTheme.colors.secondaryText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * iOS `SettingsRow`: a symbol, the title, and a quiet value; with [onClick] it is a navigation
 * row (iOS `NavigationLink`) and ends in [SettingsChevron] unless [showsChevron] is false.
 * Android divergence: the value is the summary under the title.
 */
@Composable
fun SettingsRow(
    title: String,
    symbol: String?,
    tint: SettingsTint = SettingsTint.Gray,
    value: String? = null,
    modifier: Modifier = Modifier,
    showsChevron: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    SettingsRowFrame(modifier, onClick = onClick) {
        if (symbol != null) SettingsIcon(symbol, tint)
        SettingsRowText(title, value, Modifier.weight(1f))
        if (onClick != null && showsChevron) SettingsChevron()
    }
}

/**
 * The muted chevron that ends a row opening another page, as the desktop's touch layout marks
 * rows that open a panel (`chevron-right`, muted icon colour). A value sits before it.
 */
@Composable
fun SettingsChevron() {
    OpenCodeIcon(SFSymbol.ChevronRight, contentDescription = null, size = 16.dp, tint = OpenCodeTheme.colors.tertiaryText)
}

/** iOS `LabeledContent(title, value:)`: a non-interactive title/value row; on Android the value is the summary. */
@Composable
fun SettingsValueRow(title: String, value: String, modifier: Modifier = Modifier) {
    SettingsRowFrame(modifier.semantics(mergeDescendants = true) {}) {
        SettingsRowText(title, value, Modifier.weight(1f))
    }
}

/**
 * iOS `Toggle` in a Settings list, optionally with a [SettingsIcon] and a [summary] (the
 * Experimental rows). The whole row toggles. The switch shows a check while on, as Android's
 * Settings switches do.
 */
@Composable
fun SettingsToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    symbol: String? = null,
    tint: SettingsTint = SettingsTint.Gray,
    summary: String? = null,
    enabled: Boolean = true,
) {
    SettingsRowFrame(
        modifier
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            // Spelled out so the change is sent as a state description, which TalkBack speaks on the focused row.
            .semantics { stateDescription = if (checked) "On" else "Off" },
        onClick = null,
    ) {
        if (symbol != null) SettingsIcon(symbol, tint)
        SettingsRowText(title, summary, Modifier.weight(1f))
        OpenCodeSwitch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** A plain button row (iOS `Button` in a List): accent text, or red for destructive. */
@Composable
fun SettingsButtonRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    symbol: SFSymbol? = null,
    destructive: Boolean = false,
    enabled: Boolean = true,
) {
    val color = when {
        !enabled -> OpenCodeTheme.colors.tertiaryText
        destructive -> OpenCodeTheme.colors.destructive
        else -> MaterialTheme.colorScheme.primary
    }
    SettingsRowFrame(modifier, onClick = onClick, enabled = enabled) {
        if (symbol != null) OpenCodeIcon(symbol, contentDescription = null, size = 22.dp, tint = color)
        Text(title, style = SettingsText.title, color = color, modifier = Modifier.weight(1f))
    }
}

/** One option of a [SettingsMenuRow]: its value, title and optional caption. */
data class SettingsMenuOption<T>(val value: T, val title: String, val caption: String? = null)

/**
 * iOS `SettingsMenuRow`: a choice in a Settings list: the title, then the value at the row's end
 * in the secondary colour. iOS ends it with an
 * up-and-down chevron; Android uses Material's dropdown arrow. The whole row opens the menu (a
 * Material dropdown with checkmarks).
 * [sections] groups options under headers (Voice › Model's provider groups); a single unnamed
 * section is a flat list.
 */
@Composable
fun <T> SettingsMenuRow(
    title: String,
    value: String,
    selection: T,
    sections: List<Pair<String?, List<SettingsMenuOption<T>>>>,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    accessibilityLabel: String = title,
) {
    var expanded by remember { mutableStateOf(false) }
    val stacked = isAccessibilitySize()
    Box(modifier) {
        SettingsRowFrame(
            Modifier.semantics(mergeDescendants = true) {
                contentDescription = accessibilityLabel
                stateDescription = value
            },
            onClick = { expanded = true },
        ) {
            if (stacked) {
                // iOS at accessibility sizes: the value goes under the title.
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SettingsRowText(title, summary = null)
                    SettingsMenuValue(value)
                }
            } else {
                // The title takes what's left; the value keeps to the end, ellipsized past its cap.
                SettingsRowText(title, summary = null, modifier = Modifier.weight(1f))
                SettingsMenuValue(value, Modifier.widthIn(max = SettingsMenuValueMaxWidth))
            }
        }
        TrailingDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            sections.forEachIndexed { index, (header, options) ->
                if (index > 0) HorizontalDivider()
                if (header != null) {
                    Text(
                        header,
                        style = MaterialTheme.typography.labelMedium,
                        color = OpenCodeTheme.colors.secondaryText,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
                options.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(option.title)
                                if (option.caption != null) {
                                    Text(option.caption, style = MaterialTheme.typography.bodySmall, color = OpenCodeTheme.colors.secondaryText)
                                }
                            }
                        },
                        leadingIcon = {
                            Box(Modifier.width(20.dp)) {
                                if (option.value == selection) {
                                    OpenCodeIcon(SFSymbol.Checkmark, contentDescription = null, size = 18.dp, tint = OpenCodeTheme.colors.selection)
                                }
                            }
                        },
                        onClick = {
                            expanded = false
                            onSelect(option.value)
                        },
                    )
                }
            }
        }
    }
}

/** The widest a [SettingsMenuRow]'s value grows before it's ellipsized. */
private val SettingsMenuValueMaxWidth = 168.dp

/** A [SettingsMenuRow]'s value and its dropdown arrow. */
@Composable
private fun SettingsMenuValue(value: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            value,
            style = SettingsText.summary,
            color = OpenCodeTheme.colors.secondaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        OpenCodeIcon(SFSymbol.ArrowtriangleDownFill, contentDescription = null, size = 24.dp, tint = OpenCodeTheme.colors.secondaryText)
    }
}

/**
 * The menu a [SettingsMenuRow] opens, hanging from the row's trailing edge where its value sits,
 * as iOS shows a Settings picker's menu. Material's dropdown prefers its anchor's leading edge, so
 * it's placed under the opposite layout direction (which prefers the trailing one) while its
 * options keep the row's own.
 */
@Composable
private fun TrailingDropdownMenu(expanded: Boolean, onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val direction = LocalLayoutDirection.current
    val trailing = if (direction == LayoutDirection.Ltr) LayoutDirection.Rtl else LayoutDirection.Ltr
    CompositionLocalProvider(LocalLayoutDirection provides trailing) {
        DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest) {
            val menu = this
            CompositionLocalProvider(LocalLayoutDirection provides direction) { menu.content() }
        }
    }
}

/** Flat-list overload of [SettingsMenuRow]. */
@Composable
fun <T> SettingsMenuRow(
    title: String,
    value: String,
    selection: T,
    options: List<SettingsMenuOption<T>>,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) = SettingsMenuRow(title, value, selection, listOf(null to options), onSelect, modifier)

/** Leading spacer matching a row with an icon, for rows that line up under iconed rows. */
@Composable
fun RowScope.SettingsIconSpacer() {
    if (!LocalSettingsRowIcons.current) return
    Spacer(Modifier.width(SettingsIconSize))
}

/**
 * A settings segmented choice, drawn as the desktop's segmented control
 * (`navigation/segmented-control`): a quiet track with a border-base edge, and the chosen
 * segment as a raised base-coloured pill with a border-strong edge and base text; the others
 * are muted text. Corners are 6 dp (`OpenCodeRadius.sm`). Selection stays neutral, as on the
 * desktop. Each segment is a radio button for TalkBack and fills a 48 dp tap height.
 */
@Composable
fun <T> SettingsSegmentedControl(
    options: List<T>,
    selected: T,
    title: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = OpenCodeTheme.colors
    val shape = RoundedCornerShape(OpenCodeRadius.sm)
    // Desktop: track bg-layer-01, chosen segment bg-base, edges border-base / border-strong.
    val trackFill = if (colors.isDark) Color.Transparent else colors.groupedCanvas
    val indicatorFill = colors.canvas
    val indicatorEdge = when {
        colors.strongBorders -> colors.border
        colors.isDark -> Color.White.copy(alpha = 0.2f)
        else -> Color.Black.copy(alpha = 0.2f)
    }
    Box(modifier.fillMaxWidth().height(SegmentTapHeight), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(SegmentHeight)
                .background(trackFill, shape)
                .border(SegmentEdge, colors.rowDivider, shape),
        )
        Row(Modifier.fillMaxWidth().height(SegmentTapHeight).selectableGroup()) {
            for (option in options) {
                val isSelected = option == selected
                val fill by animateColorAsState(
                    if (isSelected) indicatorFill else Color.Transparent,
                    tween(120),
                    label = "segmentFill",
                )
                val edge by animateColorAsState(
                    if (isSelected) indicatorEdge else Color.Transparent,
                    tween(120),
                    label = "segmentEdge",
                )
                val text by animateColorAsState(
                    if (isSelected) colors.text else colors.secondaryText,
                    tween(150),
                    label = "segmentText",
                )
                Box(
                    Modifier
                        .weight(1f)
                        .height(SegmentTapHeight)
                        .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(option) }),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(SegmentHeight)
                            .background(fill, shape)
                            .border(SegmentEdge, edge, shape),
                    )
                    Text(
                        title(option),
                        style = MaterialTheme.typography.labelLarge,
                        color = text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
        }
    }
}

private val SegmentHeight = 40.dp
private val SegmentTapHeight = 48.dp

/** The desktop control's 0.5 px edges. */
private val SegmentEdge = 0.5.dp

/** A settings slider: the filled part and thumb in the selection colour, as the desktop's timeline slider. */
@Composable
fun settingsSliderColors(): SliderColors {
    val colors = OpenCodeTheme.colors
    return SliderDefaults.colors(
        thumbColor = colors.selection,
        activeTrackColor = colors.selection,
        activeTickColor = colors.onSelection.copy(alpha = 0.6f),
        inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        inactiveTickColor = colors.secondaryText.copy(alpha = 0.6f),
    )
}

@file:PortedFrom("OpenCode/WhatsNew/WhatsNewView.swift", "a9bc920")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.LocalDismiss
import ai.opencode.mobilecode.system.NavigationStack
import ai.opencode.mobilecode.system.NavigationTitle
import ai.opencode.mobilecode.system.RoundedRectangle
import ai.opencode.mobilecode.system.SystemButton
import ai.opencode.mobilecode.system.SystemColors
import ai.opencode.mobilecode.system.SystemContextMenu
import ai.opencode.mobilecode.system.SystemImage
import ai.opencode.mobilecode.system.SystemMenuItem
import ai.opencode.mobilecode.system.SystemMenuSection
import ai.opencode.mobilecode.system.TextStyles
import ai.opencode.mobilecode.system.TitleDisplayMode
import ai.opencode.mobilecode.system.Toolbar
import ai.opencode.mobilecode.system.ToolbarItem
import ai.opencode.mobilecode.system.ToolbarItemPlacement
import ai.opencode.mobilecode.system.listRowSeparatorLeading
import ai.opencode.mobilecode.system.symbolSize
import ai.opencode.mobilecode.tca.Store
import ai.opencode.mobilecode.tca.binding
import ai.opencode.mobilecode.tca.onAppear
import ai.opencode.mobilecode.tca.sheet
import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/// Presents What’s New as a sheet when its button is tapped.
@Composable
fun WhatsNewSheet(
    store: Store<WhatsNewFeature.State, WhatsNewFeature.Action>,
    content: @Composable () -> Unit,
) {
    content()
    sheet(isPresented = store.binding(WhatsNewFeature.State::isPresented, WhatsNewFeature.Action::binding)) {
        WhatsNewSheetContent(store = store)
    }
}

@Composable
private fun WhatsNewSheetContent(store: Store<WhatsNewFeature.State, WhatsNewFeature.Action>) {
    val dismiss = LocalDismiss.current

    NavigationStack {
        WhatsNewList(store = store)
        Toolbar {
            ToolbarItem(placement = ToolbarItemPlacement.confirmationAction) {
                SystemButton("Done") { dismiss() }
            }
        }
    }
}

/// The entries, newest first, grouped by the day they shipped. Settings
/// pushes it; the sheet wraps it in its own navigation stack.
@Composable
fun WhatsNewList(store: Store<WhatsNewFeature.State, WhatsNewFeature.Action>) {
    // Each day’s card is one view rather than a List section: the cells
    // of a List could leave a card’s fill short over rows that painted only
    // their separators.
    Column(
        Modifier
            .testTag("whats-new-list")
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
    ) {
        for (day in WhatsNewDay.group(store.state.entries)) {
            key(day.id) { WhatsNewDayCard(day = day) }
        }
    }
    NavigationTitle("What’s New", displayMode = TitleDisplayMode.inline)
    store.onAppear { store.send(WhatsNewFeature.Action.viewed) }
}

/// A day’s header above a grouped card holding exactly its entries, with
/// dividers only between them.
@Composable
private fun WhatsNewDayCard(day: WhatsNewDay) {
    val monthDay = remember { DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(Locale.getDefault(), "MMMMd")) }
    val hairline = with(LocalDensity.current) { 1f.toDp() }
    Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            day.day.format(monthDay),
            Modifier.padding(horizontal = 20.dp).semantics { heading() },
            style = TextStyles.headline,
            color = SystemColors.secondaryLabel,
        )
        Column(
            Modifier
                .fillMaxWidth()
                // A translucent fill reads on the sheet and on the pushed page.
                .background(SystemColors.tertiarySystemFill, RoundedRectangle(26.dp))
                .testTag("whats-new-day-${day.id}"),
        ) {
            day.entries.forEachIndexed { index, entry ->
                if (index > 0) {
                    HorizontalDivider(Modifier.padding(start = (20 + 48 + 14).dp, end = 20.dp), thickness = hairline, color = SystemColors.separator)
                }
                Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
                    WhatsNewRow(entry = entry)
                }
            }
        }
    }
}

@Composable
private fun WhatsNewRow(entry: WhatsNewEntry) {
    Row(
        Modifier
            .padding(vertical = 6.dp)
            .semantics(mergeDescendants = true) {}
            .testTag("whats-new-entry-${entry.id}"),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        WhatsNewIllustration(kind = entry.illustration)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(entry.title, Modifier.listRowSeparatorLeading(), style = TextStyles.headline)
            Text(entry.summary, style = TextStyles.subheadline, color = SystemColors.secondaryLabel)
            Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SystemImage(
                    "arrow.turn.down.right",
                    contentDescription = null,
                    modifier = Modifier.symbolSize(TextStyles.footnote).alignBy { (it.measuredHeight * 0.8f).toInt() },
                    tint = SystemColors.tertiaryLabel,
                )
                Text(entry.example, Modifier.alignByBaseline(), style = TextStyles.footnote, color = SystemColors.secondaryLabel)
            }
        }
    }
}

/// The drawer’s What’s New row, while some entries are unseen.
@Composable
fun WhatsNewButton(
    action: () -> Unit,
    onHide: () -> Unit,
) {
    SystemContextMenu(
        sections = listOf(SystemMenuSection(listOf(SystemMenuItem("Hide", systemImage = "eye.slash", onClick = onHide)))),
        modifier =
            Modifier
                .semantics {
                    contentDescription = "What’s New"
                    customActions =
                        listOf(
                            CustomAccessibilityAction("Hide") {
                                onHide()
                                true
                            },
                        )
                }
                .testTag("drawer-whats-new"),
        onClick = action,
    ) {
        DrawerShortcutLabel(
            title = "What’s New",
            systemImage = "gift",
            // The drawer’s own background: invisible in the list, it gives the
            // context menu’s lifted preview a surface.
            modifier = Modifier.background(SystemColors.systemBackground, RoundedCornerShape(12.dp)),
        ) {
            SystemImage(
                "circle.fill",
                contentDescription = null,
                modifier = Modifier.height(18.dp).wrapContentHeight().symbolSize(TextStyles.caption2),
                tint = SystemColors.tint,
            )
        }
    }
}

/// Tiny drawings of each feature, from symbols and shapes.
@Composable
private fun WhatsNewIllustration(kind: WhatsNewEntry.Illustration) {
    val dark = isSystemInDarkTheme()
    val warm = if (dark) Color(red = 0.98f, green = 0.90f, blue = 0.78f) else Color(red = 0.62f, green = 0.45f, blue = 0.24f)
    val cool = if (dark) Color(red = 0.78f, green = 0.86f, blue = 1.0f) else Color(red = 0.26f, green = 0.38f, blue = 0.58f)

    Box(
        Modifier
            .size(48.dp)
            .background(SystemColors.label.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        when (kind) {
            WhatsNewEntry.Illustration.callButton ->
                Box(Modifier.size(28.dp).background(SystemColors.label, CircleShape), contentAlignment = Alignment.Center) {
                    SystemImage("waveform", contentDescription = null, modifier = Modifier.symbolSize(13), tint = SystemColors.systemBackground)
                }
            WhatsNewEntry.Illustration.liveActivity ->
                Row(
                    Modifier
                        .size(width = 38.dp, height = 15.dp)
                        .background(Color.Black, CircleShape)
                        .padding(horizontal = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DotBraid(warm = Color.White, cool = Color.White, columns = 5, rows = 5, modifier = Modifier.size(9.dp))
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.size(9.dp).background(SystemColors.systemRed, CircleShape), contentAlignment = Alignment.Center) {
                        SystemImage("phone.down.fill", contentDescription = null, modifier = Modifier.symbolSize(5), tint = Color.White)
                    }
                }
            WhatsNewEntry.Illustration.captions ->
                Box(
                    Modifier
                        .size(width = 32.dp, height = 34.dp)
                        .background(SystemColors.label.copy(alpha = 0.1f), RoundedCornerShape(9.dp)),
                ) {
                    val secondary = SystemColors.secondaryLabel
                    Column(
                        Modifier
                            .align(Alignment.TopStart)
                            .fillMaxWidth()
                            .padding(horizontal = 5.dp)
                            .padding(top = 5.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Row {
                            Box(Modifier.size(width = 16.dp, height = 3.dp).background(secondary, CircleShape))
                            Spacer(Modifier.weight(1f))
                            Box(Modifier.size(width = 6.dp, height = 2.dp).alpha(0.6f).background(secondary, CircleShape))
                        }
                        Box(Modifier.size(width = 11.dp, height = 3.dp).alpha(0.5f).background(secondary, CircleShape))
                    }
                    DotBraid(
                        warm = warm,
                        cool = cool,
                        columns = 9,
                        rows = 3,
                        modifier =
                            Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 4.dp)
                                .size(width = 22.dp, height = 6.dp),
                    )
                }
            WhatsNewEntry.Illustration.problemReport ->
                SystemImage("iphone.radiowaves.left.and.right", contentDescription = null, modifier = Modifier.symbolSize(19), tint = SystemColors.secondaryLabel)
            WhatsNewEntry.Illustration.catchUp ->
                SystemImage("rectangle.stack", contentDescription = null, modifier = Modifier.symbolSize(20), tint = SystemColors.secondaryLabel)
            WhatsNewEntry.Illustration.camera ->
                SystemImage("camera.aperture", contentDescription = null, modifier = Modifier.symbolSize(20), tint = SystemColors.secondaryLabel)
            WhatsNewEntry.Illustration.attachmentMenu ->
                SystemImage("plus.circle", contentDescription = null, modifier = Modifier.symbolSize(20), tint = SystemColors.secondaryLabel)
            WhatsNewEntry.Illustration.mediaDismiss ->
                SystemImage("photo.on.rectangle.angled", contentDescription = null, modifier = Modifier.symbolSize(19), tint = SystemColors.secondaryLabel)
            WhatsNewEntry.Illustration.voiceMessage ->
                Column(Modifier.width(36.dp), verticalArrangement = Arrangement.spacedBy(3.dp), horizontalAlignment = Alignment.End) {
                    SystemImage("waveform", contentDescription = null, modifier = Modifier.symbolSize(7), tint = SystemColors.secondaryLabel)
                    Box(Modifier.size(width = 30.dp, height = 14.dp).background(SystemColors.label.copy(alpha = 0.12f), RoundedCornerShape(6.dp)))
                }
            WhatsNewEntry.Illustration.dictationBar ->
                Row(
                    Modifier
                        .height(13.dp)
                        .background(SystemColors.label.copy(alpha = 0.08f), CircleShape)
                        .padding(horizontal = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).background(SystemColors.label.copy(alpha = 0.12f), CircleShape), contentAlignment = Alignment.Center) {
                        SystemImage("xmark", contentDescription = null, modifier = Modifier.symbolSize(4), tint = SystemColors.label)
                    }
                    DotBraid(warm = warm, cool = warm, columns = 7, rows = 3, modifier = Modifier.size(width = 12.dp, height = 5.dp))
                    Box(Modifier.size(8.dp).background(SystemColors.label.copy(alpha = 0.12f), CircleShape), contentAlignment = Alignment.Center) {
                        SystemImage("stop.fill", contentDescription = null, modifier = Modifier.symbolSize(3), tint = SystemColors.label)
                    }
                    Box(Modifier.size(8.dp).background(SystemColors.label, CircleShape), contentAlignment = Alignment.Center) {
                        SystemImage("arrow.up", contentDescription = null, modifier = Modifier.symbolSize(4), tint = SystemColors.systemBackground)
                    }
                }
            WhatsNewEntry.Illustration.talkOver ->
                Box(contentAlignment = Alignment.BottomEnd) {
                    SystemImage(
                        "waveform",
                        contentDescription = null,
                        modifier = Modifier.offset(x = (-4).dp, y = (-4).dp).symbolSize(20),
                        tint = cool.copy(alpha = 0.4f),
                    )
                    SystemImage("mic.fill", contentDescription = null, modifier = Modifier.offset(x = 5.dp, y = 5.dp).symbolSize(13), tint = warm)
                }
        }
    }
}

/// Two traces braided on a dot grid, like the call bar’s scope.
@Composable
private fun DotBraid(
    warm: Color,
    cool: Color,
    modifier: Modifier = Modifier,
    columns: Int = 9,
    rows: Int = 7,
) {
    val primary = SystemColors.label

    Canvas(modifier) {
        val pitch = size.width / columns
        val dot = pitch * 0.58f
        for (column in 0 until columns) {
            val phase = column.toDouble() / (columns - 1) * 2 * PI
            val middle = (rows - 1).toDouble() / 2
            val warmRow = middle + middle * 0.8 * sin(phase)
            val coolRow = middle - middle * 0.8 * sin(phase)
            for (row in 0 until rows) {
                val center = Offset((column + 0.5f) * pitch, (row + 0.5f) * size.height / rows)
                val warmLit = max(0.0, 1 - abs(row - warmRow))
                val coolLit = max(0.0, 1 - abs(row - coolRow))
                if (warmLit == 0.0 && coolLit == 0.0) {
                    drawCircle(primary.copy(alpha = 0.08f), radius = dot / 2, center = center)
                } else if (warmLit >= coolLit) {
                    drawCircle(warm.copy(alpha = (0.3 + 0.7 * warmLit).toFloat()), radius = dot / 2, center = center)
                } else {
                    drawCircle(cool.copy(alpha = (0.3 + 0.7 * coolLit).toFloat()), radius = dot / 2, center = center)
                }
            }
        }
    }
}

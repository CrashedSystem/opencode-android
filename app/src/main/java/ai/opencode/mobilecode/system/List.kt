package ai.opencode.mobilecode.system

import ai.opencode.mobilecode.tca.Identifiable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.VerticalAlignmentLine
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/** `.listSectionSpacing(_:)` */
object ListSectionSpacing {
    val default = 35.dp
    val compact = 20.dp
}

/** Where a row's separator starts. iOS starts it under a row's first text, so mark that text. */
val ListRowSeparatorLeading = VerticalAlignmentLine(::min)

fun Modifier.listRowSeparatorLeading(): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height, mapOf(ListRowSeparatorLeading to 0)) { placeable.place(0, 0) }
    }

/**
 * `List { Section { … } header: { … } }` in the inset grouped style of iOS sheets and Settings.
 * [scrollContentBackground] false is `.scrollContentBackground(.hidden)`: no list background, and rows
 * on a translucent fill over the sheet, the way they sit on iOS 26 glass.
 */
@Composable
fun GroupedList(
    modifier: Modifier = Modifier,
    sectionSpacing: Dp = ListSectionSpacing.default,
    scrollContentBackground: Boolean = true,
    content: GroupedListScope.() -> Unit,
) {
    val sections = GroupedListScope().apply(content).sections
    val rowBackground = if (scrollContentBackground) SystemColors.secondarySystemGroupedBackground else SystemColors.tertiarySystemFill
    val listBackground = if (scrollContentBackground) SystemColors.systemGroupedBackground else Color.Transparent
    LazyColumn(
        modifier.background(listBackground),
        contentPadding = PaddingValues(top = 4.dp, bottom = 20.dp),
    ) {
        sections.forEachIndexed { sectionIndex, section ->
            if (sectionIndex > 0) item(key = "spacing ${section.id}") { Spacer(Modifier.height(sectionSpacing)) }
            section.header?.let { header -> item(key = "header ${section.id}") { SectionHeader(header) } }
            section.rows.forEachIndexed { rowIndex, row ->
                item(key = row.id) {
                    GroupedRow(
                        first = rowIndex == 0,
                        last = rowIndex == section.rows.lastIndex,
                        background = rowBackground,
                        content = row.content,
                    )
                }
            }
        }
    }
}

class GroupedListScope internal constructor() {
    internal val sections = mutableListOf<ListSection>()
    private var sectionID: Any? = null

    /** `Section { rows } header: { header }` */
    fun Section(
        header: (@Composable () -> Unit)? = null,
        rows: SectionScope.() -> Unit,
    ) {
        val id = sectionID ?: sections.size
        sections += ListSection(id, header, SectionScope(id).apply(rows).rows)
    }

    /** `ForEach(items, id: \.key) { item in Section { … } }` */
    fun <T> ForEach(
        items: List<T>,
        id: (T) -> Any,
        content: GroupedListScope.(T) -> Unit,
    ) {
        items.forEach { item ->
            sectionID = id(item)
            content(item)
            sectionID = null
        }
    }
}

class SectionScope internal constructor(private val sectionID: Any) {
    internal val rows = mutableListOf<ListRow>()

    /** `ForEach(items) { item in Row(item: item) }` for `Identifiable` items. */
    fun <T : Identifiable<*>> ForEach(
        items: List<T>,
        content: @Composable (T) -> Unit,
    ) = ForEach(items, { it.id }, content)

    /** `ForEach(items, id: \.key) { item in Row(item: item) }` */
    fun <T> ForEach(
        items: List<T>,
        id: (T) -> Any?,
        content: @Composable (T) -> Unit,
    ) {
        items.forEach { item -> rows += ListRow("row $sectionID ${id(item)}") { content(item) } }
    }
}

internal class ListSection(val id: Any, val header: (@Composable () -> Unit)?, val rows: List<ListRow>)

/** [id] is a string: Android saves lazy list keys in a Bundle. */
internal class ListRow(val id: String, val content: @Composable () -> Unit)

@Composable
private fun SectionHeader(header: @Composable () -> Unit) {
    CompositionLocalProvider(LocalContentColor provides SystemColors.secondaryLabel, LocalTextStyle provides TextStyles.headline) {
        Box(Modifier.padding(start = ListMetrics.margin + ListMetrics.rowInset, end = ListMetrics.margin, top = 8.dp, bottom = 10.dp)) {
            header()
        }
    }
}

@Composable
private fun GroupedRow(
    first: Boolean,
    last: Boolean,
    background: Color,
    content: @Composable () -> Unit,
) {
    val radius = ListMetrics.cornerRadius
    val shape =
        RoundedCornerShape(
            topStart = if (first) radius else 0.dp,
            topEnd = if (first) radius else 0.dp,
            bottomStart = if (last) radius else 0.dp,
            bottomEnd = if (last) radius else 0.dp,
        )
    val separator = SystemColors.separator
    Layout(
        content = {
            Box(Modifier.padding(horizontal = ListMetrics.rowInset, vertical = ListMetrics.rowVerticalInset)) { content() }
            if (!last) Box(Modifier.background(separator))
        },
        modifier =
            Modifier
                .padding(horizontal = ListMetrics.margin)
                .fillMaxWidth()
                .background(background, shape),
    ) { measurables, constraints ->
        val row = measurables[0].measure(constraints.copy(minHeight = 0))
        val inset = ListMetrics.rowInset.roundToPx()
        val leading = row[ListRowSeparatorLeading].takeIf { it != AlignmentLine.Unspecified } ?: inset
        val hairline = 1
        val line =
            measurables.getOrNull(1)?.measure(
                Constraints.fixed(max(0, constraints.maxWidth - leading - inset), hairline),
            )
        val height = max(row.height, ListMetrics.minimumRowHeight.roundToPx())
        layout(constraints.maxWidth, height) {
            row.place(0, (height - row.height) / 2)
            line?.place(leading, height - hairline)
        }
    }
}

internal object ListMetrics {
    val margin = 20.dp
    val rowInset = 20.dp
    val rowVerticalInset = 14.dp
    val cornerRadius = 26.dp
    val minimumRowHeight = 44.dp
}

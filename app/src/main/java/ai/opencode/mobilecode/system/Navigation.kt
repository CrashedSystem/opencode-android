package ai.opencode.mobilecode.system

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** `.navigationBarTitleDisplayMode(_:)`. Bars draw the title inline. */
enum class TitleDisplayMode {
    automatic,
    inline,
}

/** `ToolbarItemPlacement` */
enum class ToolbarItemPlacement {
    automatic,
    cancellationAction,
    confirmationAction,
    primaryAction,
    topBarLeading,
    topBarTrailing,
    ;

    internal val isLeading: Boolean
        get() = this == cancellationAction || this == topBarLeading
}

/**
 * `NavigationStack { root }`. Draws the bar that the root declares with [NavigationTitle] and
 * [ToolbarItem], wherever in the root they appear, as SwiftUI's modifiers do.
 */
@Composable
fun NavigationStack(
    modifier: Modifier = Modifier,
    root: @Composable ColumnScope.() -> Unit,
) {
    val bar = remember { NavigationBarState() }
    CompositionLocalProvider(LocalNavigationBar provides bar) {
        Column(modifier) {
            NavigationBar(bar)
            root()
        }
    }
}

/** `.toolbar(.hidden, for: .navigationBar)` */
@Composable
fun NavigationBarHidden(hidden: Boolean = true) {
    val bar = LocalNavigationBar.current ?: return
    DisposableEffect(bar, hidden) {
        bar.hidden = hidden
        onDispose {
            if (bar.hidden == hidden) bar.hidden = false
        }
    }
}

/** `.navigationTitle("Title")` with `.navigationBarTitleDisplayMode(displayMode)` */
@Composable
fun NavigationTitle(
    title: String,
    displayMode: TitleDisplayMode = TitleDisplayMode.automatic,
    style: TextStyle = TextStyles.headline,
    overflow: TextOverflow = TextOverflow.Ellipsis,
) {
    val bar = LocalNavigationBar.current ?: return
    DisposableEffect(bar, title, displayMode, style, overflow) {
        bar.title = title
        bar.displayMode = displayMode
        bar.titleStyle = style
        bar.titleOverflow = overflow
        onDispose { if (bar.title == title) bar.title = null }
    }
}

/** `.toolbar { … }` */
@Composable
fun Toolbar(content: @Composable () -> Unit) = content()

/** `ToolbarItem(placement:) { Button("Done") { dismiss() } }`. Drawn in a glass capsule, as iOS 26 does. */
@Composable
fun ToolbarItem(
    placement: ToolbarItemPlacement = ToolbarItemPlacement.automatic,
    content: @Composable () -> Unit,
) {
    val bar = LocalNavigationBar.current ?: return
    val currentContent by rememberUpdatedState(content)
    val item = remember(placement) { ToolbarEntry(placement) { currentContent() } }
    DisposableEffect(bar, item) {
        bar.items.add(item)
        onDispose { bar.items.remove(item) }
    }
}

@Stable
private class NavigationBarState {
    var title by mutableStateOf<String?>(null)
    var displayMode by mutableStateOf(TitleDisplayMode.automatic)
    var titleStyle by mutableStateOf(TextStyles.headline)
    var titleOverflow by mutableStateOf(TextOverflow.Ellipsis)
    var hidden by mutableStateOf(false)
    val items = mutableStateListOf<ToolbarEntry>()
}

private class ToolbarEntry(val placement: ToolbarItemPlacement, val content: @Composable () -> Unit)

private val LocalNavigationBar = staticCompositionLocalOf<NavigationBarState?> { null }

@Composable
private fun NavigationBar(bar: NavigationBarState) {
    if (bar.hidden) return
    Box(
        Modifier
            .fillMaxWidth()
            .height(NavigationBarHeight)
            .padding(horizontal = 16.dp),
    ) {
        bar.title?.let { title ->
            Text(
                title,
                Modifier.align(Alignment.Center).padding(horizontal = 88.dp),
                style = bar.titleStyle,
                color = SystemColors.label,
                maxLines = 1,
                overflow = bar.titleOverflow,
            )
        }
        Row(Modifier.align(Alignment.CenterStart), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            bar.items.filter { it.placement.isLeading }.forEach { ToolbarSlot(it) }
        }
        Row(Modifier.align(Alignment.CenterEnd), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            bar.items.filterNot { it.placement.isLeading }.forEach { ToolbarSlot(it) }
        }
    }
}

@Composable
private fun ToolbarSlot(item: ToolbarEntry) {
    val style = if (item.placement == ToolbarItemPlacement.confirmationAction) TextStyles.headline else TextStyles.body
    CompositionLocalProvider(LocalContentColor provides SystemColors.label, LocalTextStyle provides style) {
        val dark = isSystemInDarkTheme()
        Box(
            Modifier
                .heightIn(min = 44.dp)
                .then(if (dark) Modifier else Modifier.shadow(1.dp, GlassSurface.Capsule))
                .glassEffect(GlassSurface.Capsule, interactive = true, tint = if (dark) Color.Unspecified else SystemColors.systemBackground)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            item.content()
        }
    }
}

private val NavigationBarHeight = 56.dp

package ai.opencode.mobilecode.system

import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.launch

enum class SheetDetent { Medium, Large }

data class SystemMenuItem(
    val title: String,
    val systemImage: String? = null,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

data class SystemMenuSection(val items: List<SystemMenuItem>)

/** `@Environment(\.dismiss) private var dismiss`, called as `dismiss()`. A sheet provides one that animates it away. */
fun interface DismissAction {
    operator fun invoke()
}

val LocalDismiss = staticCompositionLocalOf { DismissAction {} }

/// The part of a sheet below the screen at its current detent. An iOS sheet lays its
/// content out in the visible part, so a bottom bar or a followed tail stays in view.
val LocalSheetHiddenHeight = compositionLocalOf { 0.dp }

/** SwiftUI's `ButtonRole`. */
enum class ButtonRole {
    cancel,
    destructive,
}

/** `Button("Title") { action() }` with a text label, in the current content color and text style. */
@Composable
fun SystemButton(
    title: String,
    modifier: Modifier = Modifier,
    role: ButtonRole? = null,
    action: () -> Unit,
) {
    Text(
        title,
        modifier.clickable(role = Role.Button, onClick = action),
        color = if (role == ButtonRole.destructive) SystemColors.systemRed else LocalContentColor.current,
    )
}

/**
 * The platform sheet with the medium and large detents. Its surface stands in for iOS 26 glass,
 * the grouped background in light and the elevated background in dark, under an iOS-sized grabber.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class, ExperimentalLayoutApi::class)
@Composable
fun SystemSheet(
    onDismissRequest: () -> Unit,
    initialDetent: SheetDetent = SheetDetent.Medium,
    content: @Composable ColumnScope.() -> Unit,
) {
    // The sheet's own show() stops at the medium detent whenever one exists.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = initialDetent == SheetDetent.Large)
    val scope = rememberCoroutineScope()
    val currentOnDismissRequest by rememberUpdatedState(onDismissRequest)
    val dismiss = remember(sheetState) {
        DismissAction { scope.launch { sheetState.hide() }.invokeOnCompletion { currentOnDismissRequest() } }
    }
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = if (isSystemInDarkTheme()) SystemColors.secondarySystemBackground else SystemColors.systemGroupedBackground,
        contentColor = SystemColors.label,
        dragHandle = {
            Box(
                Modifier
                    .padding(vertical = 5.dp)
                    .size(width = 36.dp, height = 5.dp)
                    .background(SystemColors.tertiaryLabel, CircleShape),
            )
        },
    ) {
        // iOS lifts a sheet above the keyboard. At the medium detent Material keeps
        // the sheet's offset, so its bottom, and any actions there, sit under the keyboard.
        val keyboardShown = WindowInsets.isImeVisible
        LaunchedEffect(keyboardShown) {
            if (keyboardShown && sheetState.currentValue == SheetValue.PartiallyExpanded) sheetState.expand()
        }
        val density = LocalDensity.current
        var hidden by remember { mutableStateOf(0.dp) }
        CompositionLocalProvider(LocalDismiss provides dismiss, LocalSheetHiddenHeight provides hidden) {
            GlassWindow {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { coordinates ->
                            val root = coordinates.findRootCoordinates()
                            val bottom = root.localBoundingBoxOf(coordinates, clipBounds = false).bottom
                            hidden = with(density) { (bottom - root.size.height).coerceAtLeast(0f).toDp() }
                        }
                        .semantics { testTagsAsResourceId = true },
                    content = content,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SystemMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    sections: List<SystemMenuSection>,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val imeVisible = WindowInsets.isImeVisible
    // Last keyboard state while the menu was closed. The open frame must not
    // overwrite it, or a focusable popup that hides the IME looks like it never was up.
    var keyboardBeforeMenu by remember { mutableStateOf(false) }
    if (!expanded) keyboardBeforeMenu = imeVisible
    var openedWithKeyboard by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) {
        if (expanded) {
            openedWithKeyboard = keyboardBeforeMenu
            return@LaunchedEffect
        }
        if (!openedWithKeyboard) return@LaunchedEffect
        openedWithKeyboard = false
        restoreKeyboard(view)
    }
    // The default menu is focusable, so it takes window focus and the IME hides. The
    // composer then moves. Leaving it unfocusable keeps the field and the keyboard.
    BackHandler(enabled = expanded, onBack = onDismissRequest)
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        properties = menuKeepsKeyboard,
    ) {
        SystemMenuSections(sections)
    }
}

private val menuKeepsKeyboard = PopupProperties(focusable = false)

private fun restoreKeyboard(view: View) {
    val focused = view.rootView.findFocus()
    view.post {
        val target = focused?.takeIf { it.isAttachedToWindow } ?: view.rootView.findFocus() ?: return@post
        if (!target.hasFocus()) target.requestFocus()
        target.windowInsetsController?.show(android.view.WindowInsets.Type.ime())
    }
}

@Composable
fun SystemMenuSections(sections: List<SystemMenuSection>) {
    sections.forEachIndexed { index, section ->
        if (index > 0) HorizontalDivider(color = SystemColors.separator)
        section.items.forEach { item ->
            val color = if (item.destructive) SystemColors.systemRed else SystemColors.label
            DropdownMenuItem(
                text = { Text(item.title, color = color, style = TextStyles.body) },
                leadingIcon = item.systemImage?.let { name ->
                    { SystemImage(name, contentDescription = null, tint = color) }
                },
                onClick = item.onClick,
            )
        }
    }
}

@Composable
fun SystemContextMenu(
    sections: List<SystemMenuSection>,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(
        modifier.combinedClickable(
            onClick = { onClick?.invoke() },
            onLongClick = { expanded = true },
        ),
    ) {
        content()
        SystemMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            sections = sections,
        )
    }
}

@Composable
fun SystemAlert(
    onDismissRequest: () -> Unit,
    title: String,
    confirm: String,
    onConfirm: () -> Unit,
    text: String? = null,
    dismiss: String? = null,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(title, style = TextStyles.headline) },
        text = text?.let { message ->
            { Text(message, style = TextStyles.body, color = SystemColors.secondaryLabel) }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirm, color = if (destructive) SystemColors.systemRed else SystemColors.systemBlue)
            }
        },
        dismissButton = dismiss?.let { label ->
            {
                TextButton(onClick = onDismissRequest) {
                    Text(label, color = SystemColors.systemBlue)
                }
            }
        },
        containerColor = SystemColors.secondarySystemGroupedBackground,
        titleContentColor = SystemColors.label,
        textContentColor = SystemColors.secondaryLabel,
    )
}

@Composable
fun SystemConfirmation(
    onDismissRequest: () -> Unit,
    title: String,
    confirm: String,
    onConfirm: () -> Unit,
    text: String? = null,
    dismiss: String = "Cancel",
) {
    SystemAlert(
        onDismissRequest = onDismissRequest,
        title = title,
        text = text,
        confirm = confirm,
        onConfirm = onConfirm,
        dismiss = dismiss,
        destructive = true,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeToDeleteRow(
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.EndToStart) return@rememberSwipeToDismissBoxState false
            onDelete()
            true
        },
    )
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(SystemColors.systemRed)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                SystemImage("trash", contentDescription = "Delete", tint = Color.White)
            }
        },
        content = content,
    )
}

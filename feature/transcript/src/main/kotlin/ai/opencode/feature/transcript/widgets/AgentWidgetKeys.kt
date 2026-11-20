package ai.opencode.feature.transcript.widgets

import android.content.res.Configuration
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager

/**
 * An open widget's keyboard focus (`widgets-input.md`), only while a hardware keyboard is attached: a tap inside
 * takes it; Esc, or focus moving elsewhere, gives it back. While focused, keys go to the program as `key` events,
 * except Ctrl and Meta chords, which stay the app's. Esc is never delivered. Losing focus sends `up` for keys still
 * held, then `focus: false`. The pointer is never captured on a phone.
 */
internal class AgentWidgetKeyboard(private val focusManager: FocusManager) {
    var controller: AgentWidgetController? = null
    var active: Boolean = false
    var focused by mutableStateOf(false)
        private set
    private val requester = FocusRequester()
    private val held = LinkedHashMap<Int, AgentWidgetEvent>()

    val modifier: Modifier
        get() = if (!active) Modifier else Modifier
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (!focused) requester.requestFocus()
                }
            }
            .focusRequester(requester)
            .onFocusChanged { changed(it.isFocused) }
            .onKeyEvent(::key)
            .focusable()

    fun release() {
        if (focused) focusManager.clearFocus()
        changed(false)
    }

    private fun changed(now: Boolean) {
        if (now == focused) return
        focused = now
        val controller = controller ?: return
        if (!now) {
            held.values.forEach(controller::send)
            held.clear()
        }
        controller.send(AgentWidgetEvent.focus(now))
    }

    private fun key(event: KeyEvent): Boolean {
        if (!active || !focused) return false
        val native = event.nativeKeyEvent
        val kind = when (event.type) {
            KeyEventType.KeyDown -> "down"
            KeyEventType.KeyUp -> "up"
            else -> return false
        }
        if (native.keyCode == AndroidKeyEvent.KEYCODE_ESCAPE) {
            if (kind == "up") release()
            return true
        }
        if (native.isCtrlPressed || native.isMetaPressed) return false
        val sent = AgentWidgetKeys.event(native, kind) ?: return false
        if (kind == "down") held[native.keyCode] = AgentWidgetKeys.event(native, "up") ?: sent else held.remove(native.keyCode)
        controller?.send(sent)
        return true
    }
}

/** The widget frame's keyboard focus; inactive (no focus, no keys) without a hardware keyboard or when [enabled] is false. */
@Composable
internal fun rememberAgentWidgetKeyboard(controller: AgentWidgetController?, enabled: Boolean): AgentWidgetKeyboard {
    val configuration = LocalConfiguration.current
    val hardware = configuration.keyboard == Configuration.KEYBOARD_QWERTY &&
        configuration.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
    val focusManager = LocalFocusManager.current
    val keyboard = remember(focusManager) { AgentWidgetKeyboard(focusManager) }
    keyboard.controller = controller
    keyboard.active = enabled && hardware && controller != null
    LaunchedEffect(keyboard.active) { if (!keyboard.active) keyboard.release() }
    DisposableEffect(keyboard) { onDispose { keyboard.release() } }
    return keyboard
}

/** Android keys as DOM `KeyboardEvent` `key` and `code`. */
internal object AgentWidgetKeys {
    fun event(native: AndroidKeyEvent, kind: String): AgentWidgetEvent? {
        val code = code(native.keyCode) ?: return null
        val char = native.getUnicodeChar(native.metaState and (AndroidKeyEvent.META_SHIFT_MASK or AndroidKeyEvent.META_CAPS_LOCK_ON))
        val key = named(native.keyCode) ?: if (char > 0 && !Character.isISOControl(char)) String(Character.toChars(char)) else return null
        return AgentWidgetEvent.key(
            kind, key, code,
            repeat = kind == "down" && native.repeatCount > 0,
            shift = native.isShiftPressed, alt = native.isAltPressed, ctrl = native.isCtrlPressed, meta = native.isMetaPressed,
        )
    }

    fun code(keyCode: Int): String? = when (keyCode) {
        in AndroidKeyEvent.KEYCODE_A..AndroidKeyEvent.KEYCODE_Z -> "Key" + ('A' + (keyCode - AndroidKeyEvent.KEYCODE_A))
        in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 -> "Digit" + (keyCode - AndroidKeyEvent.KEYCODE_0)
        in AndroidKeyEvent.KEYCODE_NUMPAD_0..AndroidKeyEvent.KEYCODE_NUMPAD_9 -> "Numpad" + (keyCode - AndroidKeyEvent.KEYCODE_NUMPAD_0)
        in AndroidKeyEvent.KEYCODE_F1..AndroidKeyEvent.KEYCODE_F12 -> "F" + (keyCode - AndroidKeyEvent.KEYCODE_F1 + 1)
        AndroidKeyEvent.KEYCODE_SPACE -> "Space"
        AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> "NumpadEnter"
        AndroidKeyEvent.KEYCODE_SHIFT_LEFT -> "ShiftLeft"
        AndroidKeyEvent.KEYCODE_SHIFT_RIGHT -> "ShiftRight"
        AndroidKeyEvent.KEYCODE_ALT_LEFT -> "AltLeft"
        AndroidKeyEvent.KEYCODE_ALT_RIGHT -> "AltRight"
        AndroidKeyEvent.KEYCODE_MINUS -> "Minus"
        AndroidKeyEvent.KEYCODE_EQUALS -> "Equal"
        AndroidKeyEvent.KEYCODE_LEFT_BRACKET -> "BracketLeft"
        AndroidKeyEvent.KEYCODE_RIGHT_BRACKET -> "BracketRight"
        AndroidKeyEvent.KEYCODE_BACKSLASH -> "Backslash"
        AndroidKeyEvent.KEYCODE_SEMICOLON -> "Semicolon"
        AndroidKeyEvent.KEYCODE_APOSTROPHE -> "Quote"
        AndroidKeyEvent.KEYCODE_GRAVE -> "Backquote"
        AndroidKeyEvent.KEYCODE_COMMA -> "Comma"
        AndroidKeyEvent.KEYCODE_PERIOD -> "Period"
        AndroidKeyEvent.KEYCODE_SLASH -> "Slash"
        else -> named(keyCode)
    }

    /** Keys whose DOM `key` is a name, not the character they type; the `code` is the same name for most. */
    private fun named(keyCode: Int): String? = when (keyCode) {
        AndroidKeyEvent.KEYCODE_DPAD_LEFT -> "ArrowLeft"
        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> "ArrowRight"
        AndroidKeyEvent.KEYCODE_DPAD_UP -> "ArrowUp"
        AndroidKeyEvent.KEYCODE_DPAD_DOWN -> "ArrowDown"
        AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> "Enter"
        AndroidKeyEvent.KEYCODE_DEL -> "Backspace"
        AndroidKeyEvent.KEYCODE_FORWARD_DEL -> "Delete"
        AndroidKeyEvent.KEYCODE_TAB -> "Tab"
        AndroidKeyEvent.KEYCODE_MOVE_HOME -> "Home"
        AndroidKeyEvent.KEYCODE_MOVE_END -> "End"
        AndroidKeyEvent.KEYCODE_PAGE_UP -> "PageUp"
        AndroidKeyEvent.KEYCODE_PAGE_DOWN -> "PageDown"
        AndroidKeyEvent.KEYCODE_INSERT -> "Insert"
        AndroidKeyEvent.KEYCODE_CAPS_LOCK -> "CapsLock"
        AndroidKeyEvent.KEYCODE_SHIFT_LEFT, AndroidKeyEvent.KEYCODE_SHIFT_RIGHT -> "Shift"
        AndroidKeyEvent.KEYCODE_ALT_LEFT, AndroidKeyEvent.KEYCODE_ALT_RIGHT -> "Alt"
        in AndroidKeyEvent.KEYCODE_F1..AndroidKeyEvent.KEYCODE_F12 -> "F" + (keyCode - AndroidKeyEvent.KEYCODE_F1 + 1)
        else -> null
    }
}

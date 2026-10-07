@file:PortedFrom("OpenCode/ComposerTextInput.swift", "553a407")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.SystemColors
import ai.opencode.mobilecode.system.TextStyles
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

@Composable
fun ComposerTextInput(
    text: String,
    onText: (String) -> Unit,
    isFocused: Boolean,
    onFocus: (Boolean) -> Unit,
    expanded: Boolean,
    onImagePaste: (List<NSItemProvider>) -> Unit,
    sendTransition: ComposerSendTransition? = null,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    var value by remember(text) { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    if (value.text != text) value = TextFieldValue(text, TextRange(text.length))
    val lineHeight = 22.dp
    val lines = if (expanded) 6 else 1
    val inset = if (expanded) ComposerTextInput.expandedTopInset else 0.dp
    BasicTextField(
        value = value,
        onValueChange = { next ->
            value = next
            onText(next.text)
        },
        modifier = modifier
            .focusRequester(focusRequester)
            .onFocusChanged { onFocus(it.isFocused) }
            .heightIn(min = lineHeight + inset, max = lineHeight * lines + inset)
            .padding(PaddingValues(top = inset))
            .semantics { contentDescription = "Message" },
        textStyle = TextStyles.body.copy(color = SystemColors.label),
        cursorBrush = SolidColor(SystemColors.tint),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
        keyboardActions = KeyboardActions(),
        maxLines = lines,
    )
    sendTransition?.text = text
}

object ComposerTextInput {
    val expandedTopInset = 14.dp
}

class ImagePasteTextView {
    var text: String = ""
    var imagePasteHandler: ((List<NSItemProvider>) -> Unit)? = null
    var pinsToEnd: Boolean = false

    fun paste(itemProviders: List<NSItemProvider>) {
        val images = imageProviders(itemProviders)
        if (images.isEmpty()) return
        imagePasteHandler?.invoke(images)
    }

    fun canPaste(itemProviders: List<NSItemProvider>): Boolean = imageProviders(itemProviders).isNotEmpty()

    private fun imageProviders(providers: List<NSItemProvider>): List<NSItemProvider> =
        providers.filter { provider ->
            provider.canLoadImage || provider.registeredTypeIdentifiers.any { it.startsWith("image/") || it == "public.image" || it == "public.png" || it == "public.jpeg" }
        }
}

class ComposerSendTransition {
    var text: String = ""
    var isPrepared: Boolean = false
        private set

    fun prepare(reduceMotion: Boolean) {
        cancel()
        if (reduceMotion || text.isEmpty()) return
        isPrepared = true
    }

    fun stepLanding(progress: Float) = Unit

    fun finishLanding() {
        if (!isPrepared) return
        cancel()
        LabMarks.mark("send_flight_finished")
    }

    fun cancel() {
        isPrepared = false
    }
}

class SendLandingMarker {
    var itemID: String = ""
}

sealed class ComposerTrailingControl {
    data class send(val enabled: Boolean) : ComposerTrailingControl()
    data object stop : ComposerTrailingControl()
    data object mic : ComposerTrailingControl()
    data object call : ComposerTrailingControl()

    val symbolName: String
        get() = when (this) {
            is send -> "arrow.up"
            stop -> "stop.fill"
            mic -> "mic"
            call -> "waveform"
        }
}

data class ComposerControls(
    var trailing: ComposerTrailingControl,
    var secondaryMic: MicPlacement,
    var dictation: DictationControls?,
) {
    enum class MicPlacement { none, leading, trailing }
    data class DictationControls(var isFinishing: Boolean)

    companion object {
        fun make(
            isDictating: Boolean,
            isFinishing: Boolean,
            isRunning: Boolean,
            canSend: Boolean,
            expanded: Boolean,
            canCall: Boolean = false,
        ): ComposerControls {
            val dictation = if (isDictating || isFinishing) DictationControls(isFinishing) else null
            val trailing: ComposerTrailingControl = when {
                canSend -> ComposerTrailingControl.send(true)
                isRunning -> ComposerTrailingControl.stop
                canCall && dictation == null -> ComposerTrailingControl.call
                else -> ComposerTrailingControl.mic
            }
            val secondaryMic = when {
                dictation != null || trailing == ComposerTrailingControl.mic -> MicPlacement.none
                expanded -> MicPlacement.leading
                else -> MicPlacement.trailing
            }
            return ComposerControls(trailing, secondaryMic, dictation)
        }
    }
}

@file:PortedFrom("OpenCode/ShellOutputView.swift", "553a407")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.GlassSurface
import ai.opencode.mobilecode.system.LocalDismiss
import ai.opencode.mobilecode.system.NavigationStack
import ai.opencode.mobilecode.system.NavigationTitle
import ai.opencode.mobilecode.system.SystemColors
import ai.opencode.mobilecode.system.SystemConfirmation
import ai.opencode.mobilecode.system.SystemImage
import ai.opencode.mobilecode.system.TextStyles
import ai.opencode.mobilecode.system.TitleDisplayMode
import ai.opencode.mobilecode.system.Toolbar
import ai.opencode.mobilecode.system.ToolbarItem
import ai.opencode.mobilecode.system.ToolbarItemPlacement
import ai.opencode.mobilecode.system.glassEffect
import ai.opencode.mobilecode.system.medium
import ai.opencode.mobilecode.system.semibold
import ai.opencode.mobilecode.system.symbolSize
import ai.opencode.mobilecode.tca.Store
import ai.opencode.mobilecode.tca.binding
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.util.TypedValue
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlin.math.max

/// A shell's live output: tails while running, follows the bottom unless the
/// reader scrolls up, and ends with the command's exit status.
@Composable
fun ShellOutputView(
    store: Store<ShellOutputFeature.State, ShellOutputFeature.Action>,
    modifier: Modifier = Modifier,
) {
    val dismiss = LocalDismiss.current
    val owner = LocalLifecycleOwner.current
    var resumed by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    // Polls only while on screen and the app is active.
    LaunchedEffect(resumed) {
        if (!resumed) return@LaunchedEffect
        val task = store.send(ShellOutputFeature.Action.task)
        try {
            task.finish()
        } finally {
            task.cancel()
        }
    }
    var isFollowing by remember { mutableStateOf(true) }
    var jumpRequest by remember { mutableIntStateOf(0) }
    var didCopy by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val reducedMotion = LocalReduceMotion.current
    LaunchedEffect(didCopy) {
        if (!didCopy) return@LaunchedEffect
        kotlinx.coroutines.delay(1_500)
        didCopy = false
    }

    NavigationStack(modifier.fillMaxSize().testTag("shell-output")) {
        Column(Modifier.fillMaxSize()) {
            EarlierOutputBar(store)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                ShellContent(store, isFollowing, { isFollowing = it }, jumpRequest)
                if (!isFollowing && store.state.output.isNotEmpty()) {
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp)
                            .size(44.dp)
                            .glassEffect(GlassSurface.Circle, interactive = true)
                            .semantics { contentDescription = "Scroll to latest" }
                            .testTag("shell-output-scroll-to-latest")
                            .clickable(role = Role.Button) {
                                isFollowing = true
                                jumpRequest += 1
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        SystemImage("arrow.down", contentDescription = null, modifier = Modifier.symbolSize(TextStyles.subheadline), tint = SystemColors.label)
                    }
                }
            }
            StatusBar(store)
        }
        NavigationTitle(store.state.shell.title, displayMode = TitleDisplayMode.inline)
        Toolbar {
            ToolbarItem(placement = ToolbarItemPlacement.cancellationAction) {
                Box(
                    Modifier.testTag("shell-output-close").clickable(role = Role.Button) { dismiss() },
                    contentAlignment = Alignment.Center,
                ) {
                    SystemImage("xmark", contentDescription = "Close", modifier = Modifier.symbolSize(TextStyles.body), tint = SystemColors.label)
                }
            }
            ToolbarItem(placement = ToolbarItemPlacement.primaryAction) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    val copied = didCopy
                    Box(
                        Modifier.clickable(enabled = store.state.output.isNotEmpty(), role = Role.Button) {
                            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                                ClipData.newPlainText(store.state.shell.title, store.state.output),
                            )
                            didCopy = true
                        },
                    ) {
                        SystemImage(
                            if (copied && !reducedMotion) "checkmark" else "square.on.square",
                            contentDescription = if (copied) "Copied" else "Copy Output",
                            modifier = Modifier.symbolSize(TextStyles.body),
                            tint = SystemColors.label,
                        )
                    }
                    Box(
                        Modifier.clickable(enabled = store.state.output.isNotEmpty(), role = Role.Button) {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, store.state.output)
                                putExtra(Intent.EXTRA_SUBJECT, store.state.shell.title)
                            }
                            runCatching { context.startActivity(Intent.createChooser(intent, store.state.shell.title)) }
                        },
                    ) {
                        SystemImage("square.and.arrow.up", contentDescription = "Share Output", modifier = Modifier.symbolSize(TextStyles.body), tint = SystemColors.label)
                    }
                }
            }
        }
    }
}

@Composable
private fun ShellContent(
    store: Store<ShellOutputFeature.State, ShellOutputFeature.Action>,
    isFollowing: Boolean,
    onFollowingChanged: (Boolean) -> Unit,
    jumpRequest: Int,
) {
    val output = store.state.output
    when {
        output.isNotEmpty() -> ShellOutputTextView(output, store.state.startCursor, isFollowing, onFollowingChanged, jumpRequest, Modifier.fillMaxSize())
        store.state.isUnavailable -> Unavailable("Output Unavailable", "The server no longer has this shell’s output.")
        !store.state.isLoaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = SystemColors.secondaryLabel)
        }
        store.state.isRunning -> Unavailable("No Output Yet", "Output appears here as the command writes it.")
        else -> Unavailable("No Output", null)
    }
}

@Composable
private fun Unavailable(title: String, description: String?) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        SystemImage("terminal", contentDescription = null, modifier = Modifier.symbolSize(28), tint = SystemColors.secondaryLabel)
        Text(title, Modifier.padding(top = 8.dp), style = TextStyles.headline, color = SystemColors.label, textAlign = TextAlign.Center)
        if (description != null) {
            Text(description, Modifier.padding(top = 4.dp), style = TextStyles.body, color = SystemColors.secondaryLabel, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun EarlierOutputBar(store: Store<ShellOutputFeature.State, ShellOutputFeature.Action>) {
    if (!store.state.hasEarlierOutput || store.state.output.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (store.state.canLoadEarlier) "Earlier output hidden" else "Showing the last 4 MB",
            style = TextStyles.footnote,
            color = SystemColors.secondaryLabel,
        )
        Spacer(Modifier.weight(1f))
        if (store.state.canLoadEarlier && !store.state.isUnavailable) {
            Box(
                Modifier
                    .heightIn(min = 44.dp)
                    .testTag("shell-output-load-earlier")
                    .clickable(enabled = !store.state.isLoadingEarlier, role = Role.Button) {
                        store.send(ShellOutputFeature.Action.loadEarlierTapped)
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (store.state.isLoadingEarlier) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = SystemColors.tint)
                } else {
                    Text("Load Earlier", style = TextStyles.footnote.semibold, color = SystemColors.tint)
                }
            }
        }
    }
}

@Composable
private fun StatusBar(store: Store<ShellOutputFeature.State, ShellOutputFeature.Action>) {
    val confirming = store.binding(ShellOutputFeature.State::isConfirmingStop, ShellOutputFeature.Action::binding)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ShellStatusLabel(store.state, Modifier.weight(1f))
        if (store.state.isRunning || store.state.isStopping) {
            Box(
                Modifier
                    .testTag("shell-output-stop")
                    .clickable(enabled = !store.state.isStopping, role = Role.Button) {
                        store.send(ShellOutputFeature.Action.stopTapped)
                    }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (store.state.isStopping) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = SystemColors.systemRed)
                } else {
                    Text(
                        "Stop Shell",
                        Modifier
                            .background(SystemColors.systemRed.copy(alpha = 0.15f), GlassSurface.Capsule)
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        color = SystemColors.systemRed,
                        style = TextStyles.body,
                    )
                }
            }
        }
    }
    if (confirming.wrappedValue) {
        SystemConfirmation(
            onDismissRequest = { confirming.wrappedValue = false },
            title = "Stop This Shell?",
            text = "The command ends now. The output shown here stays until you close it.",
            confirm = "Stop Shell",
            onConfirm = { store.send(ShellOutputFeature.Action.stopConfirmed) },
        )
    }
}

/// “Running · 1:23”, “Exited with code 1”, “Stopped”: sentence-case status.
@Composable
private fun ShellStatusLabel(state: ShellOutputFeature.State, modifier: Modifier = Modifier) {
    Row(
        modifier.semantics(mergeDescendants = true) {}.testTag("shell-output-status"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusIcon(state)
        Column {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(statusTitle(state), style = TextStyles.subheadline.medium, color = SystemColors.label)
                val startedAt = state.shell.startedAt
                if (startedAt != null && (state.shell.status == OpenCodeShell.Status.running || state.shell.completedAt != null)) {
                    Text("·", style = TextStyles.subheadline.medium, color = SystemColors.label)
                    ShellElapsedTime(
                        since = startedAt,
                        until = if (state.shell.status == OpenCodeShell.Status.running) null else state.shell.completedAt,
                    )
                }
            }
            val error = state.errorMessage
            if (error != null) Text(error, style = TextStyles.caption, color = SystemColors.systemRed)
        }
    }
}

@Composable
private fun StatusIcon(state: ShellOutputFeature.State) {
    when (state.shell.status) {
        OpenCodeShell.Status.running if !state.isUnavailable -> AgentActivityGlyph(Modifier.width(18.dp))
        OpenCodeShell.Status.running, OpenCodeShell.Status.killed ->
            SystemImage("stop.circle", contentDescription = null, modifier = Modifier.symbolSize(TextStyles.subheadline), tint = SystemColors.secondaryLabel)
        OpenCodeShell.Status.timeout ->
            SystemImage("clock.badge.exclamationmark", contentDescription = null, modifier = Modifier.symbolSize(TextStyles.subheadline), tint = SystemColors.systemOrange)
        OpenCodeShell.Status.exited -> {
            val failed = (state.shell.exitCode ?: 0) != 0
            SystemImage(
                if (failed) "xmark.circle.fill" else "checkmark.circle.fill",
                contentDescription = null,
                modifier = Modifier.symbolSize(TextStyles.subheadline),
                tint = if (failed) SystemColors.systemRed else SystemColors.systemGreen,
            )
        }
    }
}

private fun statusTitle(state: ShellOutputFeature.State): String {
    if (state.isStopping) return "Stopping…"
    return when (state.shell.status) {
        OpenCodeShell.Status.running if state.isUnavailable -> "Unavailable"
        OpenCodeShell.Status.running -> "Running"
        OpenCodeShell.Status.killed -> "Stopped"
        OpenCodeShell.Status.timeout -> "Timed out"
        OpenCodeShell.Status.exited -> {
            val code = state.shell.exitCode
            if (code == null || code == 0) "Exited" else "Exited with code $code"
        }
    }
}

/// Monospaced, wrapped, selectable output that appends in place rather than
/// re-laying out the whole capture on every poll.
@Composable
fun ShellOutputTextView(
    text: String,
    startCursor: Long,
    isFollowing: Boolean,
    onFollowingChanged: (Boolean) -> Unit,
    jumpRequest: Int,
    modifier: Modifier = Modifier,
) {
    val color = SystemColors.label
    val threshold = with(LocalDensity.current) { 32.dp.toPx() }
    AndroidView(
        modifier = modifier.testTag("shell-output-text").semantics { contentDescription = "Shell output" },
        factory = { context ->
            OutputTextView(context).apply {
                setTextIsSelectable(true)
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(color.hashCode())
                setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 24))
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        },
        update = { view ->
            view.setTextColor(android.graphics.Color.argb((color.alpha * 255).toInt(), (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt()))
            view.onFollowingChanged = onFollowingChanged
            view.followThreshold = threshold
            if (view.jumpRequest != jumpRequest) {
                view.jumpRequest = jumpRequest
                view.isFollowing = true
                view.scrollToBottom(animated = false)
                onFollowingChanged(true)
            }
            view.isFollowing = isFollowing
            view.render(text, startCursor)
        },
    )
}

private fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

private class OutputTextView(context: Context) : TextView(context) {
    var isFollowing = true
    var jumpRequest = 0
    var onFollowingChanged: ((Boolean) -> Unit)? = null
    var followThreshold = 32f
    private var renderedUTF8 = 0
    private var renderedStart: Long? = null
    private var programmatic = false

    init {
        setOnScrollChangeListener { _, _, scrollY, _, _ ->
            if (programmatic) return@setOnScrollChangeListener
            val distance = layout?.let { it.height - height - scrollY } ?: return@setOnScrollChangeListener
            val following = distance < followThreshold
            if (following == isFollowing) return@setOnScrollChangeListener
            isFollowing = following
            post { onFollowingChanged?.invoke(following) }
        }
    }

    fun render(next: String, startCursor: Long) {
        val utf8Count = next.toByteArray(Charsets.UTF_8).size
        if (renderedStart == startCursor && renderedUTF8 == utf8Count) return
        if (renderedStart == startCursor && utf8Count > renderedUTF8) {
            val bytes = next.toByteArray(Charsets.UTF_8)
            append(String(bytes, renderedUTF8, bytes.size - renderedUTF8, Charsets.UTF_8))
        } else {
            val anchor = if (isFollowing || renderedStart == null) null else firstVisibleCharacter()
            val shift = (renderedStart ?: startCursor) - startCursor
            setText(next)
            if (anchor != null) scrollToCharacter((anchor + shift).toInt().coerceAtLeast(0))
        }
        renderedStart = startCursor
        renderedUTF8 = utf8Count
        if (isFollowing) scrollToBottom(animated = false)
    }

    fun scrollToBottom(animated: Boolean) {
        post {
            val layout = layout ?: return@post
            val y = max(0, layout.height - height + paddingBottom)
            programmatic = true
            scrollTo(0, y)
            programmatic = false
        }
    }

    private fun firstVisibleCharacter(): Int? {
        val layout = layout ?: return null
        return layout.getLineStart(layout.getLineForVertical(scrollY + paddingTop)).coerceAtLeast(0)
    }

    private fun scrollToCharacter(index: Int) {
        val layout = layout ?: return
        val line = layout.getLineForOffset(index.coerceAtMost(length()))
        programmatic = true
        scrollTo(0, max(0, layout.getLineTop(line) - paddingTop))
        programmatic = false
    }
}

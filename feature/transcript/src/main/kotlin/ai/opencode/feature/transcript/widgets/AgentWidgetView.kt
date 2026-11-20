package ai.opencode.feature.transcript.widgets

import ai.opencode.core.R as CoreR
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeRadius
import ai.opencode.core.ui.OpenCodeSecondaryButton
import ai.opencode.core.ui.OpenCodeShape
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.openCodeHairline
import ai.opencode.feature.transcript.LocalTranscriptRowContext
import ai.opencode.feature.transcript.R
import ai.opencode.feature.transcript.widgets.AgentWidgetMachine.Phase
import ai.opencode.server.model.OpenCodeToolCall
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * A `widget` tool call's row: the widget it posted once its state is known (a placeholder until
 * then), run by a controller the list's [AgentWidgetStore] keeps.
 */
@Composable
fun AgentWidgetToolRow(call: OpenCodeToolCall, modifier: Modifier = Modifier) {
    val widget = LocalAgentWidgetStates.current[call.callID]
    if (widget == null) {
        AgentWidgetFrame(Phase.Loading(AgentWidgetMachine.Status.Open), AgentWidgetAdapter.title(call) ?: AgentWidgetAdapter.TOOL, modifier)
    } else {
        AgentWidgetRow(widget, modifier)
    }
}

/** A widget's frame, driven by its controller. */
@Composable
fun AgentWidgetRow(widget: AgentWidgetState, modifier: Modifier = Modifier) {
    val context = LocalTranscriptRowContext.current
    val store = LocalAgentWidgetStore.current
    val server = context.server
    val sessionID = context.sessionID
    if (store == null || server == null || sessionID == null) {
        AgentWidgetFrame(Phase.Loading(AgentWidgetMachine.Status.Open), widget.label, modifier = modifier)
        return
    }
    val controller = remember(store, server, sessionID, widget.callID) { store.controller(widget, server, sessionID) }
    LaunchedEffect(controller) { controller.start() }
    LaunchedEffect(controller, widget) { controller.update(widget) }
    AgentWidgetFrame(controller.phase, widget.label, controller = controller, modifier = modifier)
}

/**
 * The widget in its frame: a placeholder while the template loads, the live view with a failed
 * step's error under it, Sending… while the answer goes, the final view read-only with how it
 * ended, or an error card with Try Again. Without a [controller] nothing in it responds.
 */
@Composable
fun AgentWidgetFrame(
    phase: Phase,
    title: String,
    modifier: Modifier = Modifier,
    controller: AgentWidgetController? = null,
) {
    val colors = OpenCodeTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .openCodeHairline(OpenCodeShape.panel)
            .padding(16.dp)
            .semantics { paneTitle = title },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (phase) {
            is Phase.Loading, is Phase.Starting -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp, color = colors.secondaryText)
                    Text(stringResource(R.string.transcript_widget_loading), style = OpenCodeTextStyles.footnote, color = colors.secondaryText)
                }
                Box(Modifier.fillMaxWidth().height(96.dp).background(colors.userBubble, OpenCodeShape.card))
            }
            is Phase.Failed -> {
                StatusLine(SFSymbol.XmarkCircleFill, colors.danger, stringResource(R.string.transcript_widget_failed))
                SelectionContainer {
                    Text(phase.message, style = OpenCodeTextStyles.footnote.copy(fontFamily = FontFamily.Monospace), color = colors.secondaryText)
                }
                if (controller != null) {
                    Row { OpenCodeSecondaryButton(stringResource(CoreR.string.common_try_again), onClick = controller::retry) }
                }
            }
            is Phase.Ready, is Phase.Stepping, is Phase.Responding -> {
                val live = controller != null && (phase is Phase.Ready || phase is Phase.Stepping)
                AgentWidgetNodeView(phase.view!!, AgentWidgetScope(controller, live, title))
                val error = (phase as? Phase.Ready)?.error
                if (error != null) StatusLine(SFSymbol.XmarkCircleFill, colors.danger, error, live = true)
                if (phase is Phase.Responding) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = colors.secondaryText)
                        Text(stringResource(R.string.transcript_widget_sending), style = OpenCodeTextStyles.footnote, color = colors.secondaryText)
                    }
                }
            }
            is Phase.Closed -> {
                phase.view?.let { AgentWidgetNodeView(it, AgentWidgetScope(controller, live = false, title)) }
                when (val how = phase.how) {
                    is AgentWidgetMachine.Closed.Submitted -> {
                        val summary = remember(how.json) { AgentWidgetStep.summary(how.json) }
                        StatusLine(SFSymbol.Checkmark, colors.diffAddedText, stringResource(R.string.transcript_widget_submitted), summary.ifEmpty { null })
                    }
                    AgentWidgetMachine.Closed.Dismissed ->
                        StatusLine(SFSymbol.Xmark, colors.tertiaryText, stringResource(R.string.transcript_widget_dismissed))
                }
            }
        }
    }
}

@Composable
private fun StatusLine(symbol: SFSymbol, tint: Color, words: String, detail: String? = null, live: Boolean = false) {
    val colors = OpenCodeTheme.colors
    Row(
        Modifier.fillMaxWidth().then(if (live) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OpenCodeIcon(symbol, contentDescription = null, size = 14.dp, tint = tint)
        Text(words, style = OpenCodeTextStyles.footnote, color = colors.text, maxLines = if (detail == null) 4 else 1)
        if (detail != null) {
            Text(detail, Modifier.weight(1f), style = OpenCodeTextStyles.footnote, color = colors.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The quiet line a widget's answer leaves in the transcript: `◈ Responded to "…"`. */
@Composable
fun AgentWidgetResponseLine(text: String, modifier: Modifier = Modifier) {
    val colors = OpenCodeTheme.colors
    Row(modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("◈", style = OpenCodeTextStyles.footnote, color = colors.tertiaryText)
        Text(text, style = OpenCodeTextStyles.footnote, color = colors.secondaryText, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

internal val WidgetCardRadius = OpenCodeRadius.card

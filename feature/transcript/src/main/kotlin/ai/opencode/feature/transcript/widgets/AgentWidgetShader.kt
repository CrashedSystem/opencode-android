package ai.opencode.feature.transcript.widgets

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.ui.excludedFromDrawerPan
import ai.opencode.core.ui.OpenCodeShape
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.feature.transcript.R
import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CancellationException

/**
 * A `shader` node: its WGSL translated to GLSL ES once per source ([AgentWidgetShaderStore]), drawn
 * on the shared GL thread into a `TextureView` every frame at `fps` while composed and the list
 * is on screen, or once (and on `values` changes) with `fps: 0`. A shader that fails shows its
 * `poster` or `alt` with the error; one that runs over budget keeps its last frame.
 */
@Composable
fun AgentWidgetShader(node: AgentWidgetNode.Shader, scope: AgentWidgetScope, modifier: Modifier = Modifier) {
    val shaders = remember { dependency(AgentWidgetShaderStore) }
    val density = LocalDensity.current.density
    val paused = LocalAgentWidgetPaused.current
    var failure by remember(node.wgsl) { mutableStateOf<String?>(null) }
    var fragment by remember(node.wgsl) { mutableStateOf<AgentWidgetShaderCode?>(null) }
    var vertex by remember { mutableStateOf<AgentWidgetShaderCode?>(null) }
    var ready by remember { mutableStateOf(false) }
    var stopped by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(node.wgsl) {
        try {
            val stages = shaders.stages(node.wgsl)
            vertex = stages.vertex
            fragment = stages.fragment
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            failure = error.message ?: error.javaClass.simpleName
            scope.controller?.reportError("shader", error)
        }
    }

    val width = node.width
    val fullscreen = LocalAgentWidgetFullscreen.current
    val size = modifier
        .then(if (width != null) Modifier.width(width.dp) else Modifier.fillMaxWidth())
        .then(if (fullscreen && node.fill) Modifier.heightIn(min = node.height.dp).fillMaxHeight() else Modifier.height(node.height.dp))
        .clip(OpenCodeShape.control)
    val error = failure
    if (error != null) {
        ShaderFallback(node, error, size)
        return
    }
    val vertexSource = vertex ?: run {
        Box(size.background(OpenCodeTheme.colors.userBubble))
        return
    }
    val surface = remember(vertexSource) {
        AgentWidgetGL.shared.Surface(
            vertexSource,
            object : AgentWidgetGL.SurfaceEvents {
                override fun ready(value: Boolean) { ready = value }
                override fun failed(message: String) {
                    failure = message
                    // GL setup, compile or link failing on WGSL that translated: the client's error.
                    scope.controller?.clientError("shader", message)
                }
                override fun stopped() { stopped = true }
            },
        )
    }
    DisposableEffect(surface) { onDispose { surface.close() } }
    LaunchedEffect(surface, fragment) { fragment?.let(surface::use) }

    val pointer = remember { FloatArray(3) }
    val values by rememberUpdatedState(node.values)
    val start = remember { longArrayOf(0L) }
    val valuesKey = if (node.fps == 0) node.values.contentHashCode() else 0
    LaunchedEffect(surface, ready, paused, stopped, visible, node.fps, valuesKey) {
        if (!ready || paused || stopped || !visible) return@LaunchedEffect
        val interval = if (node.fps > 0) 1_000_000_000L / node.fps else 0L
        var last = 0L
        while (true) {
            val now = withFrameNanos { it }
            if (start[0] == 0L) start[0] = scope.controller?.startNanos("shader:${node.id}", now) ?: now
            if (last != 0L && now - last < interval - 2_000_000L) continue
            val t = (now - start[0]) / 1e9f
            if (surface.render(t, density, pointer[0], pointer[1], pointer[2] > 0f, values)) last = now
            if (node.fps == 0 && last != 0L) break
        }
    }

    val currentScope by rememberUpdatedState(scope)
    Box(
        size
            .onVisibilityChanged(minFractionVisible = VISIBLE_FRACTION) { visible = it }
            .excludedFromDrawerPan(node.id != null && scope.live)
            .agentWidgetPointer(node.id, density, { currentScope.send(it) })
            .then(if (node.alt != null) Modifier.semantics { contentDescription = node.alt } else Modifier)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull() ?: continue
                        pointer[0] = change.position.x
                        pointer[1] = change.position.y
                        pointer[2] = if (change.pressed) 1f else 0f
                    }
                }
            },
    ) {
        AndroidView(
            factory = { context ->
                TextureView(context).apply {
                    isOpaque = false
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                            surface.attach(texture, width, height)
                        }

                        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                            surface.resize(width, height)
                        }

                        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                            surface.detach(texture)
                            return false
                        }

                        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun ShaderFallback(node: AgentWidgetNode.Shader, error: String, modifier: Modifier) {
    val colors = OpenCodeTheme.colors
    val poster = node.poster
    if (poster != null) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AgentWidgetImage(poster, node.alt, node.width?.let { it / node.height })
            Text(error, style = OpenCodeTextStyles.caption1, color = colors.tertiaryText, maxLines = 3)
        }
        return
    }
    Column(
        modifier.background(colors.userBubble).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(node.alt ?: stringResource(R.string.transcript_widget_shader_failed), style = OpenCodeTextStyles.footnote, color = colors.secondaryText, textAlign = TextAlign.Center)
        Text(error, style = OpenCodeTextStyles.caption1, color = colors.tertiaryText, textAlign = TextAlign.Center, maxLines = 4)
    }
}

@file:PortedFrom("OpenCode/Media/TranscriptImageViewer.swift", "8adf650")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.ForcedAppearance
import ai.opencode.mobilecode.system.SystemImage
import ai.opencode.mobilecode.system.TextStyles
import ai.opencode.mobilecode.system.semibold
import ai.opencode.mobilecode.system.symbolSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/// Transcript images, full screen: swipe between them, pinch or double-tap
/// to zoom, drag down to put them back.
@Composable
fun TranscriptImageViewer(preview: TranscriptImagePreview, viewer: MediaViewerState) {
    val pager = rememberPagerState(initialPage = preview.pages.indexOfFirst { it.id == preview.id }.coerceAtLeast(0)) { preview.pages.size }
    val selectedID = preview.pages.getOrNull(pager.currentPage)?.id ?: preview.id
    val selectedIndex = preview.pages.indexOfFirst { it.id == selectedID }.coerceAtLeast(0)
    val chrome by animateFloatAsState(
        if (viewer.chromeHidden) 0f else 1f,
        tween(if (viewer.chromeHidden) 150 else 200, easing = EaseOut),
        label = "chrome",
    )
    ForcedAppearance(dark = true) {
        // The overlays keep to the safe area; the pages fill the screen, so a
        // page's fitted image is where the flight lands.
        Box(Modifier.fillMaxSize()) {
            HorizontalPager(pager, Modifier.fillMaxSize().semantics { testTag = "image-gallery" }) { index ->
                val page = preview.pages[index]
                ZoomableImagePage(
                    image = page.image,
                    label = page.label,
                    isSelected = selectedID == page.id,
                    onZoomChange = { zoomed -> if (selectedID == page.id) viewer.isZoomed = zoomed },
                )
            }
            if (preview.pages.size > 1) {
                Text(
                    "${selectedIndex + 1} of ${preview.pages.size}",
                    Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 24.dp)
                        .graphicsLayer { alpha = chrome }
                        .background(Color.White.copy(alpha = 0.18f), CircleShape)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .semantics { testTag = "image-gallery-position" },
                    style = TextStyles.footnote.copy(fontFeatureSettings = "tnum"),
                    color = Color.White,
                )
            }
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(16.dp)
                    .graphicsLayer { alpha = chrome }
                    .size(44.dp)
                    .background(Color.White.copy(alpha = 0.18f), CircleShape)
                    .clickable(role = Role.Button) { viewer.dismiss() }
                    .semantics { contentDescription = "Close image" },
                contentAlignment = Alignment.Center,
            ) {
                SystemImage("xmark", null, Modifier.symbolSize(TextStyles.body.semibold), tint = Color.White)
            }
        }
    }
    // Synchronous, so the image is known before the viewer flies it in.
    DisposableEffect(selectedID) {
        viewer.show(sourceID = selectedID, image = preview.pages.firstOrNull { it.id == selectedID }?.image)
        onDispose {}
    }
}

/// One image filling the screen. Pinch or double-tap zooms; a zoomed image
/// pans with a drag.
@Composable
fun ZoomableImagePage(
    image: androidx.compose.ui.graphics.ImageBitmap,
    label: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onZoomChange: (Boolean) -> Unit = {},
) {
    val reducedMotion = LocalReduceMotion.current
    val scope = rememberCoroutineScope()
    val scale = remember { Animatable(1f) }
    val offset = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val zoomChange by rememberUpdatedState(onZoomChange)
    val zoomed by remember { derivedStateOf { scale.value > 1f } }
    LaunchedEffect(zoomed) { zoomChange(zoomed) }
    LaunchedEffect(isSelected) {
        if (!isSelected) {
            scale.snapTo(1f)
            offset.snapTo(Offset.Zero)
        }
    }
    Box(
        modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    val lastScale = scale.value
                    val lastOffset = offset.value
                    // A drag pans only when the gesture began zoomed in.
                    val pans = lastScale > 1f
                    var magnification = 1f
                    var translation = Offset.Zero
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.any { it.isConsumed }) break
                        val pressed = event.changes.count { it.pressed }
                        if (pressed >= 2) {
                            magnification *= event.calculateZoom()
                            val next = (lastScale * magnification).coerceIn(1f, 5f)
                            scope.launch { scale.snapTo(next) }
                            event.changes.forEach { it.consume() }
                        } else if (pans && pressed == 1) {
                            translation += event.calculatePan()
                            val next = lastOffset + translation
                            scope.launch { offset.snapTo(next) }
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    if (scale.value == 1f) scope.launch { offset.snapTo(Offset.Zero) }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    val target = if (scale.value > 1f) 1f else 2f
                    val spec = if (reducedMotion) snap() else MediaViewerController.swiftSpring(0.5, 0.15)
                    scope.launch { scale.animateTo(target, spec) }
                    if (target == 1f) scope.launch { offset.animateTo(Offset.Zero, if (reducedMotion) snap() else tween(300)) }
                })
            },
    ) {
        Image(
            bitmap = image,
            contentDescription = label,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    translationX = offset.value.x
                    translationY = offset.value.y
                },
            contentScale = ContentScale.Fit,
        )
    }
}

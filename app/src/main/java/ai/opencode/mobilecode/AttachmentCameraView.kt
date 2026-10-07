@file:PortedFrom("OpenCode/AttachmentCameraView.swift", "553a407")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.GlassSurface
import ai.opencode.mobilecode.system.SystemColors
import ai.opencode.mobilecode.system.SystemImage
import ai.opencode.mobilecode.system.TextStyles
import ai.opencode.mobilecode.system.glassEffect
import ai.opencode.mobilecode.system.SystemAlert
import ai.opencode.mobilecode.system.rememberHaptics
import ai.opencode.mobilecode.system.semibold
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.net.Uri
import android.os.UserManager
import android.provider.Settings
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal enum class Authorization { authorized, denied, restricted, notDetermined }

private fun authorization(context: Context, asked: Boolean): Authorization {
    val restricted = context.getSystemService(UserManager::class.java)
        ?.hasUserRestriction("no_camera") == true
    if (restricted) return Authorization.restricted
    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
    if (granted) return Authorization.authorized
    if (!asked) return Authorization.notDetermined
    return Authorization.denied
}

/**
 * A still camera presented full screen; the preview fills the display while
 * the controls stay inside the safe area.
 */
@Composable
fun AttachmentCameraView(
    onCapture: (ByteArray) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val camera = remember(context, lifecycleOwner, scope) {
        AttachmentCameraState(context, lifecycleOwner, scope)
    }
    var asked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        asked = true
        camera.onAuthorization(if (granted) Authorization.authorized else authorization(context, asked = true))
    }
    var showRationale by remember { mutableStateOf(false) }
    val request = {
        val activity = context as? Activity
        if (activity != null && androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)) {
            showRationale = true
        } else {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }
    val reduceMotion = reduceMotionEnabled()
    SideEffect {
        camera.onCapture = onCapture
        camera.reduceMotion = reduceMotion
        camera.isVisible = true
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> camera.activate(authorization(context, asked), request)
                Lifecycle.Event.ON_PAUSE -> camera.pause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            camera.activate(authorization(context, asked), request)
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            camera.close()
        }
    }
    if (showRationale) {
        SystemAlert(
            onDismissRequest = { showRationale = false },
            title = "Camera",
            text = context.getString(ai.opencode.mobilecode.R.string.camera_usage_description),
            confirm = "Continue",
            onConfirm = {
                showRationale = false
                launcher.launch(Manifest.permission.CAMERA)
            },
        )
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AttachmentCameraPreview(
            capture = camera.capture,
            isInteractive = camera.canCapture && camera.zoom != null,
            adjustable = camera.canCapture && camera.zoom?.canZoom == true,
            zoomLabel = camera.zoom?.let {
                CameraZoomModel.label(it.displayed(camera.zoomState.shownFactor), active = true)
            } ?: "",
            onAdjust = { up -> camera.stepZoom(up) },
            onPinch = { phase -> camera.pinch(phase) },
            onFocus = { point, devicePoint, lock -> camera.focus(point, devicePoint, lock) },
        )
        AttachmentFocusIndicator(camera.focusMark)
        camera.message?.let { message ->
            Column(
                Modifier
                    .align(Alignment.Center)
                    .safeDrawingPadding()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 72.dp)
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    message,
                    style = TextStyles.subheadline,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
                if (camera.needsSettings) {
                    TextButton(onClick = { openAppSettings(context) }) {
                        Text("Open Settings", color = Color.White)
                    }
                } else if (camera.canRetry) {
                    TextButton(onClick = { camera.activate(authorization(context, asked), request) }) {
                        Text("Try Again", color = Color.White)
                    }
                }
            }
        }
        camera.zoomRevision
        AttachmentCameraControls(
            canCapture = camera.canCapture,
            canFlip = camera.canFlip,
            flash = if (camera.hasFlash) camera.flash else null,
            zoom = camera.zoom,
            zoomFactor = camera.zoomState.shownFactor,
            isPinching = camera.zoomState.isPinching,
            isFocusLocked = camera.isFocusLocked,
            onClose = {
                camera.close()
                onBack()
            },
            onShutter = { camera.takePhoto() },
            onFlip = { camera.flip() },
            onFlash = { camera.flash = camera.flash.next },
            onLens = { camera.select(it) },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * The camera's controls, independent of the capture session so they can be
 * rendered over a placeholder in tests.
 */
@Composable
fun AttachmentCameraControls(
    canCapture: Boolean,
    canFlip: Boolean,
    flash: AttachmentCameraFlash?,
    zoom: CameraZoomModel?,
    zoomFactor: Double,
    isPinching: Boolean = false,
    isFocusLocked: Boolean = false,
    onClose: () -> Unit = {},
    onShutter: () -> Unit = {},
    onFlip: () -> Unit = {},
    onFlash: () -> Unit = {},
    onLens: (CameraZoomModel.Preset) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.safeDrawingPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (flash != null) {
                GlassIconButton(
                    symbol = flash.systemImage,
                    label = "Flash",
                    value = flash.accessibilityValue,
                    enabled = canCapture,
                    tint = if (flash == AttachmentCameraFlash.off) Color.White else SystemColors.systemYellow,
                    onClick = onFlash,
                )
            } else {
                Spacer(Modifier.size(44.dp))
            }
            Spacer(Modifier.weight(1f))
            AnimatedVisibility(visible = isFocusLocked, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
                Text(
                    "AE/AF Lock",
                    style = TextStyles.caption.semibold,
                    color = Color.Black,
                    modifier = Modifier
                        .background(SystemColors.systemYellow, CircleShape)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.size(44.dp))
        }
        Spacer(Modifier.weight(1f))
        if (zoom != null && zoom.canZoom) {
            AttachmentLensPicker(
                zoom = zoom,
                factor = zoomFactor,
                isPinching = isPinching,
                enabled = canCapture,
                onSelect = onLens,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 14.dp)
                    .alpha(if (canCapture) 1f else 0.35f),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIconButton(symbol = "xmark", label = "Close camera", enabled = true, onClick = onClose)
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .size(58.dp)
                    .alpha(if (canCapture) 1f else 0.35f)
                    .clickable(enabled = canCapture, role = Role.Button, onClick = onShutter)
                    .semantics { contentDescription = "Take photo" },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .border(2.dp, Color.White, CircleShape)
                        .padding(5.dp)
                        .background(Color.White, CircleShape),
                )
            }
            Spacer(Modifier.weight(1f))
            if (canFlip) {
                GlassIconButton(
                    symbol = "arrow.triangle.2.circlepath",
                    label = "Switch Camera",
                    enabled = canCapture,
                    dimWhenDisabled = false,
                    onClick = onFlip,
                )
            } else {
                Spacer(Modifier.size(44.dp))
            }
        }
    }
}

@Composable
private fun GlassIconButton(
    symbol: String,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    value: String? = null,
    tint: Color = Color.White,
    dimWhenDisabled: Boolean = true,
) {
    Box(
        Modifier
            .size(44.dp)
            .alpha(if (!enabled && dimWhenDisabled) 0.35f else 1f)
            .glassEffect(GlassSurface.Circle, interactive = true, tint = Color.Black.copy(alpha = 0.3f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = label
                if (value != null) stateDescription = value
            },
        contentAlignment = Alignment.Center,
    ) {
        SystemImage(symbol, contentDescription = null, modifier = Modifier.size(20.dp), tint = tint)
    }
}

/**
 * Round lens buttons like Camera's: the active one is larger and shows the
 * live zoom, the others their lens's factor.
 */
@Composable
private fun AttachmentLensPicker(
    zoom: CameraZoomModel,
    factor: Double,
    isPinching: Boolean,
    enabled: Boolean,
    onSelect: (CameraZoomModel.Preset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = zoom.activePreset(factor)
    val live = CameraZoomModel.label(zoom.displayed(factor), active = true)
    val reduceMotion = reduceMotionEnabled()
    val haptics = rememberHaptics()
    var seen by remember { mutableStateOf(false) }
    LaunchedEffect(active?.id) {
        if (!seen) {
            seen = true
            return@LaunchedEffect
        }
        if (isPinching) haptics.selection()
    }
    Row(
        modifier
            .glassEffect(GlassSurface.Capsule, tint = Color.Black.copy(alpha = 0.25f))
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        zoom.presets.forEach { preset ->
            val isActive = preset == active
            val diameter by animateDpAsState(
                targetValue = if (isActive) 38.dp else 30.dp,
                animationSpec = if (reduceMotion) snap() else tween(200),
                label = "lens",
            )
            val label = if (isActive) live else CameraZoomModel.label(preset.displayFactor, active = false)
            Box(
                Modifier
                    .size(44.dp)
                    .clickable(enabled = enabled, role = Role.Button, onClick = { onSelect(preset) })
                    .semantics {
                        contentDescription = preset.accessibilityLabel
                        if (isActive) {
                            stateDescription = live
                            selected = true
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(diameter)
                        .background(Color.Black.copy(alpha = 0.45f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        color = if (isActive) SystemColors.systemYellow else Color.White,
                        fontSize = if (isActive) 13.sp else 12.sp,
                        style = TextStyles.caption.semibold,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                        modifier = Modifier.padding(horizontal = 2.dp),
                    )
                }
            }
        }
    }
}

data class AttachmentFocusMark(
    val id: UUID = UUID.randomUUID(),
    val point: androidx.compose.ui.geometry.Offset,
    val locked: Boolean,
)

/** Camera's brief yellow square where a tap focused; it pulses once on a lock. */
@Composable
fun AttachmentFocusIndicator(mark: AttachmentFocusMark?) {
    val reduceMotion = reduceMotionEnabled()
    Box(Modifier.fillMaxSize().semantics { hideFromAccessibility() }) {
        if (mark != null) {
            val scale = remember(mark.id) { Animatable(if (reduceMotion) 1f else 1.4f) }
            LaunchedEffect(mark.id) {
                if (!reduceMotion) scale.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 400f))
            }
            val side = if (mark.locked) 92.dp else 76.dp
            Box(
                Modifier
                    .size(side)
                    .offset {
                        IntOffset(
                            mark.point.x.roundToInt() - side.roundToPx() / 2,
                            mark.point.y.roundToInt() - side.roundToPx() / 2,
                        )
                    }
                    .graphicsLayer {
                        scaleX = scale.value
                        scaleY = scale.value
                    }
                    .border(1.5.dp, SystemColors.systemYellow),
            )
        }
    }
}

private class AttachmentCameraState(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    private val scope: CoroutineScope,
) {
    var message by mutableStateOf<String?>("Opening camera…")
    var needsSettings by mutableStateOf(false)
    var canRetry by mutableStateOf(false)
    var canCapture by mutableStateOf(false)
    var canFlip by mutableStateOf(false)
    var isVisible by mutableStateOf(false)
    var zoom by mutableStateOf<CameraZoomModel?>(null)
    var zoomState by mutableStateOf(CameraZoomState())
    var zoomRevision by mutableIntStateOf(0)
    var hasFlash by mutableStateOf(false)
    var flash by mutableStateOf(AttachmentCameraFlash.off)
    var focusMark by mutableStateOf<AttachmentFocusMark?>(null)
    var isFocusLocked by mutableStateOf(false)
    var onCapture: ((ByteArray) -> Unit)? = null
    var reduceMotion = false
    private var generation: UUID? = null
    private var focusClear by mutableIntStateOf(0)

    val capture = AttachmentCaptureSession(context, lifecycleOwner) { eventGeneration, event ->
        if (!isVisible || generation != eventGeneration) return@AttachmentCaptureSession
        when (event) {
            is AttachmentCaptureSession.Event.ready -> {
                canCapture = true
                canFlip = event.readiness.canFlip
                message = null
                canRetry = false
                zoom = CameraZoomModel(event.readiness.device)
                zoomState.reset(event.readiness.zoom)
                publishZoom()
                hasFlash = event.readiness.hasFlash
                isFocusLocked = event.readiness.focusLocked
            }
            is AttachmentCaptureSession.Event.zoom -> {
                zoomState.rampProgressed(event.ramp, event.factor)
                publishZoom()
            }
            AttachmentCaptureSession.Event.focusReset -> isFocusLocked = false
            is AttachmentCaptureSession.Event.failure -> {
                message = event.message
                canCapture = false
                canRetry = event.retry
                resetInteraction()
            }
            is AttachmentCaptureSession.Event.captureFailed -> {
                message = event.message
                canCapture = true
            }
            is AttachmentCaptureSession.Event.photo -> {
                val callback = onCapture
                close()
                callback?.invoke(event.data)
            }
        }
    }

    fun activate(status: Authorization, request: () -> Unit) {
        if (!isVisible) return
        val next = UUID.randomUUID()
        generation = next
        canCapture = false
        canRetry = false
        needsSettings = false
        message = "Opening camera…"
        when (status) {
            Authorization.authorized -> capture.start(next)
            Authorization.notDetermined -> request()
            Authorization.restricted -> {
                needsSettings = true
                message = "Camera access is restricted."
            }
            Authorization.denied -> {
                needsSettings = true
                message = "Allow camera access in Settings to take a photo."
            }
        }
    }

    fun onAuthorization(status: Authorization) {
        if (!isVisible) return
        val next = generation ?: return
        if (status == Authorization.authorized) {
            capture.start(next)
            return
        }
        needsSettings = true
        message = if (status == Authorization.restricted) {
            "Camera access is restricted."
        } else {
            "Allow camera access in Settings to take a photo."
        }
    }

    fun pause() {
        generation = null
        canCapture = false
        resetInteraction()
        capture.stop()
    }

    /** Ends any pinch, ramp, focus square, and lock badge; the session resumes continuous focus when it next reports ready. */
    private fun resetInteraction() {
        zoomState.reset(zoomState.factor)
        publishZoom()
        focusMark = null
        isFocusLocked = false
    }

    fun close() {
        isVisible = false
        pause()
        onCapture = null
    }

    fun takePhoto() {
        val current = generation
        if (!canCapture || current == null) return
        canCapture = false
        message = null
        capture.takePhoto(current, if (hasFlash) flash else AttachmentCameraFlash.off)
    }

    fun flip() {
        val current = generation
        if (!canCapture || !canFlip || current == null) return
        canCapture = false
        resetInteraction()
        capture.flip(current)
    }

    fun pinch(phase: AttachmentCameraPreview.Pinch) {
        val model = zoom
        val current = generation
        if (!canCapture || model == null || current == null) {
            zoomState.endPinch()
            publishZoom()
            return
        }
        when (phase) {
            AttachmentCameraPreview.Pinch.began -> {
                zoomState.beginPinch()
                publishZoom()
            }
            is AttachmentCameraPreview.Pinch.changed -> {
                val factor = zoomState.pinch(phase.scale, model) ?: return
                publishZoom()
                capture.zoom(factor, ramp = null, generation = current)
            }
            AttachmentCameraPreview.Pinch.ended -> {
                zoomState.endPinch()
                publishZoom()
            }
        }
    }

    fun select(preset: CameraZoomModel.Preset) {
        val model = zoom
        val current = generation
        if (!canCapture || model == null || current == null) return
        val selected = zoomState.select(preset, model, animated = !reduceMotion)
        publishZoom()
        capture.zoom(selected.target, selected.ramp, current)
    }

    fun stepZoom(up: Boolean) {
        val model = zoom
        val current = generation
        if (!canCapture || model == null || current == null) return
        val factor = zoomState.step(up, model)
        publishZoom()
        capture.zoom(factor, ramp = null, generation = current)
    }

    private fun publishZoom() {
        zoomRevision++
    }

    fun focus(point: androidx.compose.ui.geometry.Offset, devicePoint: androidx.compose.ui.geometry.Offset, lock: Boolean) {
        val current = generation
        if (!canCapture || zoom == null || current == null) return
        val mark = AttachmentFocusMark(point = point, locked = lock)
        focusMark = mark
        isFocusLocked = lock
        capture.focus(devicePoint, lock, current)
        val token = ++focusClear
        scope.launch {
            delay(if (lock) 1600 else 1100)
            if (token == focusClear && focusMark?.id == mark.id) focusMark = null
        }
    }
}

private object AttachmentCameraPreview {
    sealed class Pinch {
        data object began : Pinch()
        data class changed(val scale: Double) : Pinch()
        data object ended : Pinch()
    }
}

@Composable
private fun AttachmentCameraPreview(
    capture: AttachmentCaptureSession,
    isInteractive: Boolean,
    adjustable: Boolean,
    zoomLabel: String,
    onAdjust: (Boolean) -> Unit,
    onPinch: (AttachmentCameraPreview.Pinch) -> Unit,
    onFocus: (androidx.compose.ui.geometry.Offset, androidx.compose.ui.geometry.Offset, Boolean) -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .then(
                if (adjustable) {
                    Modifier.semantics {
                        contentDescription = "Viewfinder"
                        stateDescription = zoomLabel
                        customActions = listOf(
                            CustomAccessibilityAction("Increment") {
                                onAdjust(true)
                                true
                            },
                            CustomAccessibilityAction("Decrement") {
                                onAdjust(false)
                                true
                            },
                        )
                    }
                } else {
                    Modifier.clearAndSetSemantics { hideFromAccessibility() }
                },
            )
            .pointerInput(isInteractive, capture) {
                if (!isInteractive) return@pointerInput
                detectCameraGestures(
                    onPinch = onPinch,
                    onTap = { point ->
                        val device = capture.devicePoint(point) ?: return@detectCameraGestures
                        onFocus(point, device, false)
                    },
                    onLongPress = { point ->
                        val device = capture.devicePoint(point) ?: return@detectCameraGestures
                        onFocus(point, device, true)
                    },
                )
            },
    ) {
        AndroidView(
            factory = { capture.previewView },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectCameraGestures(
    onPinch: (AttachmentCameraPreview.Pinch) -> Unit,
    onTap: (androidx.compose.ui.geometry.Offset) -> Unit,
    onLongPress: (androidx.compose.ui.geometry.Offset) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var pinching = false
        var initialSpan = 1f
        var locked = false
        var moved = false
        val origin = down.position
        while (true) {
            val event = if (locked || pinching || moved) {
                awaitPointerEvent()
            } else {
                withTimeoutOrNull(500) { awaitPointerEvent() }
            }
            if (event == null) {
                if (!pinching && !moved) {
                    locked = true
                    onLongPress(origin)
                }
                continue
            }
            val pressed = event.changes.filter { it.pressed }
            if (pressed.size >= 2) {
                val span = pressed.map { it.position }.let { points ->
                    val a = points[0]
                    val b = points[1]
                    kotlin.math.hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat().coerceAtLeast(1f)
                }
                if (!pinching) {
                    pinching = true
                    initialSpan = span
                    onPinch(AttachmentCameraPreview.Pinch.began)
                    onPinch(AttachmentCameraPreview.Pinch.changed(1.0))
                } else {
                    onPinch(AttachmentCameraPreview.Pinch.changed((span / initialSpan).toDouble()))
                }
                pressed.forEach { it.consume() }
                continue
            }
            if (pinching) {
                onPinch(AttachmentCameraPreview.Pinch.ended)
                break
            }
            if (pressed.isEmpty()) {
                if (!locked && !moved) onTap(origin)
                break
            }
            if ((pressed[0].position - origin).getDistance() > viewConfiguration.touchSlop) moved = true
        }
    }
}

private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

@Composable
private fun reduceMotionEnabled(): Boolean {
    val context = LocalContext.current
    val scale = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    return scale == 0f
}

private fun lensOf(intrinsic: Float): CameraDeviceDescription.Lens = when {
    intrinsic < 0.8f -> CameraDeviceDescription.Lens.ultraWide
    intrinsic > 1.5f -> CameraDeviceDescription.Lens.telephoto
    else -> CameraDeviceDescription.Lens.wide
}

private fun physicalCameras(info: CameraInfo): List<CameraInfo> {
    val physical = info.physicalCameraInfos.toList()
    return physical.ifEmpty { listOf(info) }
}

private fun widestIntrinsic(info: CameraInfo): Double {
    return physicalCameras(info).minOf { it.intrinsicZoomRatio.toDouble() }.coerceAtLeast(0.05)
}

/** Maps a CameraX camera into the device zoom space [CameraZoomModel] already understands. */
fun CameraDeviceDescription(info: CameraInfo): CameraDeviceDescription {
    val physical = physicalCameras(info).sortedBy { it.intrinsicZoomRatio }
    val widest = physical.first().intrinsicZoomRatio.toDouble().coerceAtLeast(0.05)
    val zoom = info.zoomState.value
    return CameraDeviceDescription(
        position = if (info.lensFacing == CameraSelector.LENS_FACING_FRONT) {
            CameraDeviceDescription.Position.front
        } else {
            CameraDeviceDescription.Position.back
        },
        lenses = physical.map { lensOf(it.intrinsicZoomRatio) },
        switchOverFactors = physical.drop(1).map { it.intrinsicZoomRatio.toDouble() / widest },
        minZoom = (zoom?.minZoomRatio?.toDouble() ?: widest) / widest,
        maxZoom = (zoom?.maxZoomRatio?.toDouble() ?: widest) / widest,
        nativeCropFactors = nativeCropFactors(physical, widest),
    )
}

private fun nativeCropFactors(physical: List<CameraInfo>, widest: Double): List<Double> {
    val wide = physical.minByOrNull { abs(it.intrinsicZoomRatio - 1f) } ?: return emptyList()
    val wideIntrinsic = wide.intrinsicZoomRatio.toDouble()
    val next = physical.firstOrNull { it.intrinsicZoomRatio > wide.intrinsicZoomRatio + 0.15f }
    val nextDevice = next?.let { it.intrinsicZoomRatio.toDouble() / widest } ?: Double.POSITIVE_INFINITY
    val multiples = focalMultiples(wide).toMutableList()
    if (multiples.none { abs(it - 2.0) < 0.15 } && sensorSupportsNativeDouble(wide)) multiples.add(2.0)
    return multiples.mapNotNull { multiple ->
        if (multiple <= 1.05) return@mapNotNull null
        val deviceFactor = wideIntrinsic * multiple / widest
        if (deviceFactor >= nextDevice) null else deviceFactor
    }
}

private fun focalMultiples(info: CameraInfo): List<Double> {
    val focals = camera2OrNull(info)?.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        ?: return emptyList()
    if (focals.isEmpty()) return emptyList()
    val shortest = focals.min()
    if (shortest <= 0f) return emptyList()
    return focals.map { (it / shortest).toDouble() }.filter { it > 1.2 }
}

private fun sensorSupportsNativeDouble(info: CameraInfo): Boolean {
    val camera2 = camera2OrNull(info) ?: return false
    val array = camera2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE) ?: return false
    val map = camera2.getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return false
    val jpeg = map.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width.toLong() * it.height } ?: return false
    if (jpeg.width == 0 || jpeg.height == 0) return false
    val linear = min(array.width.toFloat() / jpeg.width, array.height.toFloat() / jpeg.height)
    return linear >= 1.8f
}

private fun camera2OrNull(info: CameraInfo): Camera2CameraInfo? {
    return try {
        Camera2CameraInfo.from(info)
    } catch (_: IllegalArgumentException) {
        null
    }
}

private fun rank(info: CameraInfo): Int {
    val lenses = physicalCameras(info).map { lensOf(it.intrinsicZoomRatio) }.toSet()
    val ultra = CameraDeviceDescription.Lens.ultraWide in lenses
    val wide = CameraDeviceDescription.Lens.wide in lenses
    val tele = CameraDeviceDescription.Lens.telephoto in lenses
    return when {
        ultra && wide && tele -> 4
        ultra && wide -> 3
        wide && tele -> 2
        wide -> 1
        else -> 0
    }
}

/**
 * Session configuration, zoom, focus, and capture stay behind this seam.
 * CameraX requires those calls on the main thread, so the seam uses the main
 * executor instead of a private queue.
 */
@OptIn(ExperimentalCamera2Interop::class)
private class AttachmentCaptureSession(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val onEvent: (UUID, Event) -> Unit,
) {
    data class Readiness(
        val canFlip: Boolean,
        val device: CameraDeviceDescription,
        val zoom: Double,
        val hasFlash: Boolean,
        val focusLocked: Boolean,
    )

    sealed class Event {
        data class ready(val readiness: Readiness) : Event()
        data class zoom(val factor: Double, val ramp: UUID) : Event()
        data object focusReset : Event()
        data class failure(val message: String, val retry: Boolean) : Event()
        data class captureFailed(val message: String) : Event()
        data class photo(val data: ByteArray) : Event()
    }

    val previewView: PreviewView = PreviewView(context).apply {
        scaleType = PreviewView.ScaleType.FILL_CENTER
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    }

    private val mainExecutor: Executor = ContextCompat.getMainExecutor(context)
    private val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var generation: UUID? = null
    private var facing = CameraSelector.LENS_FACING_BACK
    private var widest = 1.0
    private var appliedDeviceZoom = 1.0
    private var preferApplied = false
    private var rampJob: Job? = null
    private var rampId: UUID? = null
    private var focusLocked = false
    private var tapFocused = false
    private var focusBaseline: Double? = null
    private var focusAtNanos = 0L
    private var pendingCapture = false
    private var wasReady = false
    private var interrupted = false
    private var observed: CameraInfo? = null

    fun start(generation: UUID) {
        this.generation = generation
        pendingCapture = false
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (this.generation != generation) return@addListener
            val opened = try {
                future.get()
            } catch (_: Exception) {
                emit(Event.failure("Couldn’t open the camera.", retry = true))
                return@addListener
            }
            provider = opened
            if (opened.availableCameraInfos.isEmpty()) {
                emit(Event.failure("No camera available on this device.", retry = false))
                return@addListener
            }
            if (!bind(generation)) return@addListener
            reportReadiness()
        }, mainExecutor)
    }

    fun stop() {
        generation = null
        pendingCapture = false
        rampJob?.cancel()
        rampId = null
        wasReady = false
        interrupted = false
        tapFocused = false
        try {
            provider?.unbindAll()
        } catch (_: Exception) {
            Unit
        }
        camera = null
        imageCapture = null
    }

    fun devicePoint(viewPoint: androidx.compose.ui.geometry.Offset): androidx.compose.ui.geometry.Offset? {
        val point = previewView.meteringPointFactory.createPoint(viewPoint.x, viewPoint.y)
        if (point.x !in 0f..1f || point.y !in 0f..1f) return null
        return androidx.compose.ui.geometry.Offset(point.x, point.y)
    }

    fun takePhoto(generation: UUID, flash: AttachmentCameraFlash) {
        if (this.generation != generation) return
        val capture = imageCapture
        val open = camera?.cameraInfo?.cameraState?.value?.type == CameraState.Type.OPEN
        if (capture == null || pendingCapture || !open) {
            emit(Event.failure("Camera is temporarily unavailable.", retry = true))
            return
        }
        capture.flashMode = when (flash) {
            AttachmentCameraFlash.off -> ImageCapture.FLASH_MODE_OFF
            AttachmentCameraFlash.auto -> ImageCapture.FLASH_MODE_AUTO
            AttachmentCameraFlash.on -> ImageCapture.FLASH_MODE_ON
        }
        capture.targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
        pendingCapture = true
        capture.takePicture(mainExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bytes = jpegBytes(image)
                image.close()
                pendingCapture = false
                if (this@AttachmentCaptureSession.generation != generation) return
                if (bytes != null) emit(Event.photo(bytes))
                else emit(Event.captureFailed("Couldn’t capture the photo. Try again."))
            }

            override fun onError(exception: ImageCaptureException) {
                pendingCapture = false
                if (this@AttachmentCaptureSession.generation != generation) return
                val stillOpen = camera?.cameraInfo?.cameraState?.value?.type == CameraState.Type.OPEN
                if (stillOpen) emit(Event.captureFailed("Couldn’t capture the photo. Try again."))
                else emit(Event.failure("Camera is temporarily unavailable.", retry = true))
            }
        })
    }

    fun flip(generation: UUID) {
        if (this.generation != generation || pendingCapture) return
        val next = if (facing == CameraSelector.LENS_FACING_FRONT) {
            CameraSelector.LENS_FACING_BACK
        } else {
            CameraSelector.LENS_FACING_FRONT
        }
        val opened = provider
        if (opened == null || opened.availableCameraInfos.none { it.lensFacing == next }) {
            emit(Event.captureFailed("Couldn’t switch cameras."))
            return
        }
        facing = next
        wasReady = false
        rampJob?.cancel()
        rampId = null
        if (!bind(generation)) emit(Event.captureFailed("Couldn’t switch cameras."))
        else reportReadiness()
    }

    /** Sets the zoom directly, or ramps to it when given a ramp ID. Rate matches AVFoundation's powers-of-two-per-second ramp. */
    fun zoom(to: Double, ramp: UUID?, generation: UUID) {
        if (this.generation != generation) return
        val camera = camera ?: return
        val state = camera.cameraInfo.zoomState.value ?: return
        val target = toCameraX(to).toFloat().coerceIn(state.minZoomRatio, state.maxZoomRatio)
        appliedDeviceZoom = toDevice(target.toDouble())
        rampJob?.cancel()
        rampId = null
        if (ramp == null) {
            camera.cameraControl.setZoomRatio(target)
            return
        }
        val start = state.zoomRatio
        if (start <= 0f || abs(target - start) < 0.001f) {
            camera.cameraControl.setZoomRatio(target)
            emit(Event.zoom(toDevice(target.toDouble()), ramp))
            return
        }
        rampId = ramp
        val durationMs = (abs(log2(target / start)) / ZOOM_RAMP_RATE * 1000.0).toLong().coerceAtLeast(1)
        rampJob = scope.launch {
            val started = System.nanoTime()
            while (isActive && rampId == ramp) {
                val t = ((System.nanoTime() - started) / 1_000_000.0 / durationMs).coerceIn(0.0, 1.0)
                val ratio = start * 2.0.pow(log2(target / start) * t)
                camera.cameraControl.setZoomRatio(ratio.toFloat())
                if (this@AttachmentCaptureSession.rampId == ramp) emit(Event.zoom(toDevice(ratio), ramp))
                if (t >= 1.0) {
                    if (rampId == ramp) rampId = null
                    break
                }
                delay(16)
            }
        }
    }

    /** Focuses and exposes once at a device point. A tap returns to continuous focus when the scene changes; a lock holds. */
    fun focus(at: androidx.compose.ui.geometry.Offset, lock: Boolean, generation: UUID) {
        if (this.generation != generation) return
        val camera = camera ?: return
        val point = SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(at.x, at.y)
        val builder = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
        if (lock) builder.disableAutoCancel() else builder.setAutoCancelDuration(5, TimeUnit.SECONDS)
        camera.cameraControl.startFocusAndMetering(builder.build())
        setAeLock(camera, lock)
        focusLocked = lock
        tapFocused = !lock
        focusAtNanos = System.nanoTime()
        focusBaseline = null
    }

    private fun imageCapture(): ImageCapture {
        val builder = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
        return try {
            builder.setMirrorMode(MirrorMode.MIRROR_MODE_OFF).build()
        } catch (_: UnsupportedOperationException) {
            ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
        }
    }

    private fun bind(generation: UUID): Boolean {
        val opened = provider ?: return false
        if (this.generation != generation) return false
        val selector = selector(opened, facing) ?: run {
            val fallback = if (facing == CameraSelector.LENS_FACING_BACK) {
                opened.availableCameraInfos.firstOrNull()
            } else {
                null
            }
            if (fallback == null) {
                emit(Event.failure("No camera available on this device.", retry = false))
                return false
            }
            cameraIdSelector(fallback)
        }
        val previewBuilder = Preview.Builder()
        Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(sceneCallback)
        val preview = previewBuilder.build()
        val capture = imageCapture()
        preview.surfaceProvider = previewView.surfaceProvider
        return try {
            opened.unbindAll()
            val bound = opened.bindToLifecycle(lifecycleOwner, selector, preview, capture)
            camera = bound
            imageCapture = capture
            prepare(bound)
            observe(bound)
            true
        } catch (_: Exception) {
            emit(Event.failure("Couldn’t open the camera.", retry = true))
            false
        }
    }

    private fun prepare(camera: Camera) {
        widest = widestIntrinsic(camera.cameraInfo)
        val model = CameraZoomModel(CameraDeviceDescription(camera.cameraInfo))
        val state = camera.cameraInfo.zoomState.value
        val ratio = if (state == null) {
            toCameraX(model.defaultFactor).toFloat()
        } else {
            toCameraX(model.defaultFactor).toFloat().coerceIn(state.minZoomRatio, state.maxZoomRatio)
        }
        appliedDeviceZoom = toDevice(ratio.toDouble())
        preferApplied = true
        camera.cameraControl.setZoomRatio(ratio)
        resumeContinuousFocus(camera)
        focusLocked = false
        tapFocused = false
        rampJob?.cancel()
        rampId = null
    }

    private fun observe(camera: Camera) {
        if (observed === camera.cameraInfo) return
        observed = camera.cameraInfo
        camera.cameraInfo.cameraState.observe(lifecycleOwner) { state ->
            if (generation == null) return@observe
            when (state.type) {
                CameraState.Type.PENDING_OPEN -> {
                    if (wasReady) {
                        interrupted = true
                        wasReady = false
                        emit(Event.failure("Camera interrupted. It will resume when available.", retry = false))
                    }
                }
                CameraState.Type.OPEN -> {
                    if (interrupted) {
                        interrupted = false
                        this.camera?.let { resumeContinuousFocus(it) }
                        reportReadiness()
                    }
                }
                CameraState.Type.CLOSED -> {
                    val error = state.error ?: return@observe
                    if (!wasReady && !interrupted) return@observe
                    val fatal = error.type == CameraState.ErrorType.CRITICAL
                    wasReady = false
                    emit(
                        Event.failure(
                            if (fatal) "Camera stopped. Try again." else "Camera interrupted. It will resume when available.",
                            retry = fatal,
                        ),
                    )
                }
                else -> Unit
            }
        }
    }

    private fun reportReadiness() {
        val camera = camera ?: return
        val info = camera.cameraInfo
        val device = CameraDeviceDescription(info)
        val zoomState = info.zoomState.value
        val zoom = if (preferApplied || zoomState == null) {
            appliedDeviceZoom
        } else {
            toDevice(zoomState.zoomRatio.toDouble())
        }
        preferApplied = false
        val opposite = if (info.lensFacing == CameraSelector.LENS_FACING_FRONT) {
            CameraSelector.LENS_FACING_BACK
        } else {
            CameraSelector.LENS_FACING_FRONT
        }
        wasReady = true
        emit(
            Event.ready(
                Readiness(
                    canFlip = provider?.availableCameraInfos?.any { it.lensFacing == opposite } == true,
                    device = device,
                    zoom = zoom,
                    hasFlash = info.lensFacing == CameraSelector.LENS_FACING_BACK && info.hasFlashUnit(),
                    focusLocked = focusLocked,
                ),
            ),
        )
    }

    private fun resumeContinuousFocus(camera: Camera) {
        focusLocked = false
        tapFocused = false
        setAeLock(camera, false)
        camera.cameraControl.cancelFocusAndMetering()
    }

    private fun setAeLock(camera: Camera, lock: Boolean) {
        val control = Camera2CameraControl.from(camera.cameraControl)
        if (!lock) {
            control.clearCaptureRequestOptions()
            return
        }
        control.setCaptureRequestOptions(
            CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                .build(),
        )
    }

    private val sceneCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            if (!tapFocused || focusLocked) return
            val exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: return
            val brightness = exposure.toDouble() * iso
            if (System.nanoTime() - focusAtNanos < 800_000_000L) {
                focusBaseline = brightness
                return
            }
            val baseline = focusBaseline ?: brightness.also { focusBaseline = it }
            if (baseline <= 0.0) return
            val ratio = brightness / baseline
            if (ratio > 1.8 || ratio < 0.55) {
                mainExecutor.execute {
                    val camera = camera ?: return@execute
                    resumeContinuousFocus(camera)
                    emit(Event.focusReset)
                }
            }
        }
    }

    private fun selector(provider: ProcessCameraProvider, facing: Int): CameraSelector? {
        val infos = provider.availableCameraInfos.filter { it.lensFacing == facing }
        val chosen = if (facing == CameraSelector.LENS_FACING_FRONT) {
            infos.minByOrNull { abs(it.intrinsicZoomRatio - 1f) }
        } else {
            infos.maxWithOrNull(compareBy<CameraInfo> { rank(it) }.thenBy { physicalCameras(it).size })
        } ?: return null
        return cameraIdSelector(chosen)
    }

    private fun cameraIdSelector(info: CameraInfo): CameraSelector {
        val id = camera2OrNull(info)?.cameraId
        return CameraSelector.Builder().addCameraFilter { cameras ->
            val matched = if (id == null) {
                cameras.filter { it == info }
            } else {
                cameras.filter { camera2OrNull(it)?.cameraId == id }
            }
            matched.toList()
        }.build()
    }

    private fun toCameraX(deviceFactor: Double) = deviceFactor * widest

    private fun toDevice(cameraX: Double) = cameraX / widest

    private fun emit(event: Event) {
        val generation = generation ?: return
        onEvent(generation, event)
    }

    private fun jpegBytes(image: ImageProxy): ByteArray? {
        val buffer = image.planes.firstOrNull()?.buffer ?: return null
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return bytes
    }

    companion object {
        private const val ZOOM_RAMP_RATE = 6.0
    }
}

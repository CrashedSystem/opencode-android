@file:PortedFrom("OpenCode/PairingScanner.swift", "553a407")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.FontFamilies
import ai.opencode.mobilecode.system.GlassSurface
import ai.opencode.mobilecode.system.SystemAlert
import ai.opencode.mobilecode.system.SystemImage
import ai.opencode.mobilecode.system.TextStyles
import ai.opencode.mobilecode.system.glassEffect
import ai.opencode.mobilecode.system.rememberHaptics
import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.UserManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/// Camera scanner for the QR code printed by `opencode pair`.
///
/// CameraX plus ML Kit rather than a bundled scanner UI: this only needs one
/// symbology, and camera availability is reported from the device feature set.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScannerView(
    onScanned: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    var asked by remember { mutableStateOf(false) }
    var access by remember { mutableStateOf(authorization(context, asked)) }
    var pasteFailed by remember { mutableStateOf(false) }
    var showRationale by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        asked = true
        access = if (granted) Authorization.authorized else authorization(context, asked = true)
    }
    val available = remember(context) { isCameraAvailable(context) }
    val denied = access == Authorization.denied || access == Authorization.restricted
    DisposableEffect(available) {
        if (available && access == Authorization.notDetermined) {
            val activity = context as? Activity
            if (activity != null && androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)) {
                showRationale = true
            } else {
                launcher.launch(Manifest.permission.CAMERA)
            }
        }
        onDispose {}
    }
    if (showRationale) {
        SystemAlert(
            onDismissRequest = { showRationale = false },
            title = "Camera",
            text = context.getString(R.string.camera_usage_description),
            confirm = "Continue",
            onConfirm = {
                showRationale = false
                launcher.launch(Manifest.permission.CAMERA)
            },
        )
    }
    if (pasteFailed) {
        SystemAlert(
            onDismissRequest = { pasteFailed = false },
            title = "Nothing to Paste",
            text = "Your clipboard doesn’t have a pairing link. Copy the link that `opencode pair` prints, then try again.",
            confirm = "OK",
            onConfirm = { pasteFailed = false },
        )
    }
    val paste = {
        val text = clipboardText(context)
        if (text.isNullOrBlank()) pasteFailed = true else onScanned(text)
    }
    Scaffold(
        containerColor = Color.Black,
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Scan Pairing Code", style = TextStyles.headline, color = Color.White) },
                navigationIcon = {
                    TextButton(onClick = onCancel) { Text("Cancel", color = Color.White) }
                },
                actions = {
                    if (available && !denied) {
                        TextButton(onClick = paste) { Text("Paste", color = Color.White) }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White,
                ),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (!available) {
                Fallback(
                    symbol = "camera.metering.unknown",
                    title = "No camera available",
                    message = "Copy the link that `opencode pair` prints on your computer, then paste it here.",
                    settings = false,
                    onSettings = {},
                    onPaste = paste,
                    modifier = Modifier.padding(padding).navigationBarsPadding(),
                )
            } else if (denied) {
                Fallback(
                    symbol = "camera.fill",
                    title = "Camera access is off",
                    message = "Allow camera access in Settings to scan the QR code, or paste the link that `opencode pair` prints.",
                    settings = access == Authorization.denied,
                    onSettings = { openSettings(context) },
                    onPaste = paste,
                    modifier = Modifier.padding(padding).navigationBarsPadding(),
                )
            } else if (access == Authorization.authorized) {
                CameraScanner(onScanned = onScanned, modifier = Modifier.fillMaxSize())
                Viewfinder(Modifier.padding(padding).navigationBarsPadding())
            } else {
                Viewfinder(Modifier.padding(padding).navigationBarsPadding())
            }
        }
    }
}

fun isCameraAvailable(context: Context): Boolean {
    if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) return true
    val manager = context.getSystemService(CameraManager::class.java) ?: return false
    return try {
        manager.cameraIdList.isNotEmpty()
    } catch (_: Exception) {
        false
    }
}

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

@Composable
private fun Viewfinder(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .size(250.dp)
                .semantics { hideFromAccessibility() }
                .drawBehind {
                    val stroke = 3.dp.toPx()
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.9f),
                        topLeft = Offset(stroke / 2f, stroke / 2f),
                        size = Size(size.width - stroke, size.height - stroke),
                        cornerRadius = CornerRadius(28.dp.toPx()),
                        style = Stroke(width = stroke),
                    )
                },
        )
        Spacer(Modifier.weight(1f))
        Column(
            Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(18.dp))
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Point your camera at the QR code", style = TextStyles.headline, color = Color.White, textAlign = TextAlign.Center)
            Text(
                buildAnnotatedString {
                    append("Run ")
                    withStyle(SpanStyle(fontFamily = FontFamilies.BerkeleyMono, fontSize = 15.sp)) {
                        append("opencode pair")
                    }
                    append(" on your computer to show it.")
                },
                style = TextStyles.subheadline,
                color = Color.White.copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun Fallback(
    symbol: String,
    title: String,
    message: String,
    settings: Boolean,
    onSettings: () -> Unit,
    onPaste: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        SystemImage(
            symbol,
            contentDescription = null,
            modifier = Modifier.size(34.dp).semantics { hideFromAccessibility() },
            tint = Color.White.copy(alpha = 0.75f),
        )
        Text(title, style = TextStyles.headline, color = Color.White, textAlign = TextAlign.Center)
        Text(
            message,
            style = TextStyles.subheadline,
            color = Color.White.copy(alpha = 0.75f),
            textAlign = TextAlign.Center,
        )
        Column(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (settings) {
                PasteButton(prominent = true, onClick = onSettings) { Text("Open Settings") }
            }
            PasteButton(prominent = !settings, onClick = onPaste) {
                SystemImage("doc.on.clipboard", contentDescription = null, modifier = Modifier.size(18.dp), tint = if (!settings) Color.Black else Color.White)
                Text("Paste pairing link", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun PasteButton(
    prominent: Boolean,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (prominent) {
                    Modifier.background(Color.White, shape)
                } else {
                    Modifier.glassEffect(shape, interactive = true)
                },
            )
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun CameraScanner(
    onScanned: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = rememberHaptics()
    val controller = remember(context, lifecycleOwner) { ScannerController(context, lifecycleOwner) }
    SideEffect {
        controller.onScanned = { text ->
            haptics.success()
            onScanned(text)
        }
    }
    DisposableEffect(controller) {
        controller.start()
        onDispose { controller.stop() }
    }
    AndroidView(factory = { controller.previewView }, modifier = modifier)
}

private class ScannerController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
) {
    var onScanned: ((String) -> Unit)? = null
    val previewView: PreviewView = PreviewView(context).apply {
        scaleType = PreviewView.ScaleType.FILL_CENTER
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    }
    private val executor = Executors.newSingleThreadExecutor()
    private val scanned = AtomicBoolean(false)
    private val barcodes = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
    )
    private var provider: ProcessCameraProvider? = null

    fun start() {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val opened = try {
                future.get()
            } catch (_: Exception) {
                return@addListener
            }
            provider = opened
            val preview = Preview.Builder().build()
            preview.surfaceProvider = previewView.surfaceProvider
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { image ->
                val media = image.image
                if (media == null || scanned.get()) {
                    image.close()
                    return@setAnalyzer
                }
                val input = InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees)
                barcodes.process(input)
                    .addOnSuccessListener { codes ->
                        val text = codes.firstOrNull()?.rawValue ?: return@addOnSuccessListener
                        if (!scanned.compareAndSet(false, true)) return@addOnSuccessListener
                        val callback = onScanned
                        previewView.post { callback?.invoke(text) }
                    }
                    .addOnCompleteListener { image.close() }
            }
            try {
                opened.unbindAll()
                opened.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (_: Exception) {
                Unit
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        try {
            provider?.unbindAll()
        } catch (_: Exception) {
            Unit
        }
        barcodes.close()
        executor.shutdown()
    }
}

private fun clipboardText(context: Context): String? {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return null
    val clip: ClipData = clipboard.primaryClip ?: return null
    return clip.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
}

private fun openSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

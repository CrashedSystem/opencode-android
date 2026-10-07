@file:PortedFrom("OpenCode/Traces/TraceTriggers.swift", "494e433")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.tca.Shared
import ai.opencode.mobilecode.tca.SharedKey
import ai.opencode.mobilecode.tca.Store
import ai.opencode.mobilecode.tca.task
import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.PixelCopy
import android.view.View
import android.widget.EditText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.math.min
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine

// MARK: Triggers

/// A shake asks Home to upload a trace (Developer Mode only).
object ProblemReportRequest {
    /// UI tests post this broadcast to stand in for a shake.
    const val labShakeName = "ai.opencode.lab.shake"

    /// UI tests arm this to shake on the first frame a presentation is moving.
    const val labShakeMidTransitionName = "ai.opencode.lab.shake-mid-transition"

    private val requests = MutableSharedFlow<ProblemReportTrigger>(extraBufferCapacity = 8)
    private val bridgeInstalled = AtomicBoolean(false)

    val notifications = requests.asSharedFlow()

    fun post(trigger: ProblemReportTrigger) {
        requests.tryEmit(trigger)
    }

    /// A shake is the system's Undo Typing while there's typing to undo.
    fun shakeReports(canUndo: Boolean): Boolean = !canUndo

    fun installLabShakeBridge(context: Context) {
        if (!BuildConfig.DEBUG || !bridgeInstalled.compareAndSet(false, true)) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                post(ProblemReportTrigger.shake)
            }
        }
        val filter = IntentFilter(labShakeName).apply { addAction(labShakeMidTransitionName) }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }
}

/// Counts what's on screen that shows credentials: Settings, with its API
/// key field. Screenshots skip while any is visible.
object CredentialSurface {
    var visible: Int = 0
        private set

    fun appeared() {
        visible += 1
    }

    fun disappeared() {
        visible = maxOf(0, visible - 1)
    }
}

/// Keeps this view out of problem report screenshots.
@Composable
fun Modifier.hidesFromProblemReports(): Modifier {
    DisposableEffect(Unit) {
        CredentialSurface.appeared()
        onDispose { CredentialSurface.disappeared() }
    }
    return this
}

/// The key window as it looks now, as a JPEG within the report's budget.
object WindowSnapshot {
    enum class ApplicationState { active, inactive, background }

    @Volatile
    var resumed: Activity? = null
        private set

    private val installed = AtomicBoolean(false)

    /// Never in the background, where drawing would touch the GPU and get the
    /// app killed (a call drops while the phone is locked), and never while a
    /// credential is on screen or being typed.
    fun isAllowed(applicationState: ApplicationState, credentialSurfaces: Int, secureFieldFocused: Boolean): Boolean {
        if (applicationState != ApplicationState.active || credentialSurfaces != 0) return false
        return !secureFieldFocused
    }

    suspend fun jpeg(maxBytes: Int = ProblemReportBuilder.screenshotLimit): ByteArray? {
        val application = OpenCodeApp.instance ?: return null
        install(application)
        val activity = resumed ?: return null
        val view = activity.window.decorView
        if (view.width <= 0 || view.height <= 0) return null
        if (!isAllowed(applicationState(), CredentialSurface.visible, secureFieldFocused(activity))) return null
        val density = activity.resources.displayMetrics.density
        val scale = min(2f, density)
        val width = (view.width * scale / density).toInt().coerceAtLeast(1)
        val height = (view.height * scale / density).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val copied = copyWindow(activity, view, bitmap)
        if (!copied) drawWindow(view, bitmap)
        val data = compress(bitmap, maxBytes)
        bitmap.recycle()
        return data
    }

    private fun install(application: Application) {
        if (!installed.compareAndSet(false, true)) return
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                resumed = activity
            }

            override fun onActivityPaused(activity: Activity) {
                if (resumed == activity) resumed = null
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) {
                if (resumed == activity) resumed = null
            }
        })
    }

    private fun applicationState(): ApplicationState {
        val state = runCatching { ProcessLifecycleOwner.get().lifecycle.currentState }.getOrNull() ?: return ApplicationState.background
        if (state.isAtLeast(Lifecycle.State.RESUMED)) return ApplicationState.active
        if (state.isAtLeast(Lifecycle.State.STARTED)) return ApplicationState.inactive
        return ApplicationState.background
    }

    private fun secureFieldFocused(activity: Activity): Boolean {
        val field = activity.currentFocus as? EditText ?: return false
        val variation = field.inputType and InputType.TYPE_MASK_VARIATION
        return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            field.transformationMethod is PasswordTransformationMethod
    }

    private suspend fun copyWindow(activity: Activity, view: View, bitmap: Bitmap): Boolean =
        suspendCancellableCoroutine { continuation ->
            val done = AtomicBoolean(false)
            fun finish(copied: Boolean) {
                if (done.compareAndSet(false, true)) continuation.resume(copied)
            }
            continuation.invokeOnCancellation { finish(false) }
            runCatching {
                PixelCopy.request(activity.window, Rect(0, 0, view.width, view.height), bitmap, { result ->
                    finish(result == PixelCopy.SUCCESS)
                }, Handler(Looper.getMainLooper()))
            }.onFailure { finish(false) }
        }

    private fun drawWindow(view: View, bitmap: Bitmap) {
        val canvas = Canvas(bitmap)
        canvas.scale(bitmap.width.toFloat() / view.width, bitmap.height.toFloat() / view.height)
        view.draw(canvas)
    }

    private fun compress(bitmap: Bitmap, maxBytes: Int): ByteArray? {
        for (quality in listOf(70, 50, 30)) {
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            if (stream.size() <= maxBytes) return stream.toByteArray()
        }
        return null
    }
}

/// Square Seismic's medium sensitivity. A sustained acceleration, not a bump,
/// which is what iOS's system shake feels like.
class ProblemReportShake(
    private val accelerationThreshold: Int = MEDIUM,
) {
    private class Sample(val timestamp: Long, val accelerating: Boolean)

    private val samples = ArrayDeque<Sample>()

    fun add(timestampNs: Long, ax: Float, ay: Float, az: Float): Boolean {
        val magnitudeSquared = ax * ax + ay * ay + az * az
        val accelerating = magnitudeSquared > accelerationThreshold * accelerationThreshold
        val cutoff = timestampNs - MAX_WINDOW
        while (samples.size >= MIN_QUEUE && samples.first().timestamp < cutoff) samples.removeFirst()
        samples.addLast(Sample(timestampNs, accelerating))
        val span = samples.last().timestamp - samples.first().timestamp
        val shaking = samples.size >= MIN_QUEUE && span >= MIN_WINDOW &&
            samples.count { it.accelerating } >= (samples.size shr 1) + (samples.size shr 2)
        if (shaking) samples.clear()
        return shaking
    }

    companion object {
        const val LIGHT = 11
        const val MEDIUM = 13
        const val HARD = 15
        private const val MAX_WINDOW = 500_000_000L
        private const val MIN_WINDOW = 250_000_000L
        private const val MIN_QUEUE = 4
    }
}

// MARK: Home

/// Shakes (and the lab's stand-in) become trace uploads.
@Composable
fun traceUploads(
    store: Store<TraceUploadsFeature.State, TraceUploadsFeature.Action>,
    content: @Composable () -> Unit,
) {
    content()
    val context = LocalContext.current
    LaunchedEffect(store) {
        ProblemReportRequest.notifications.collect { trigger ->
            store.send(TraceUploadsFeature.Action.traceRequested(trigger))
        }
    }
    DisposableEffect(context) {
        ProblemReportRequest.installLabShakeBridge(context)
        onDispose {}
    }
    val developerMode by remember<Shared<Boolean>> { Shared(SharedKey.developerMode) }
    DisposableEffect(context, developerMode) {
        val stop = if (developerMode) listenForShakes(context) else ({})
        onDispose { stop() }
    }
    store.task { store.send(TraceUploadsFeature.Action.task) }
}

/// UIKit's `motionEnded(.motionShake)` on the window: the accelerometer,
/// read only while Developer Mode is on.
private fun listenForShakes(context: Context): () -> Unit {
    val manager = context.getSystemService(SensorManager::class.java) ?: return {}
    val sensor = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return {}
    val detector = ProblemReportShake()
    val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!ProblemReportRequest.shakeReports(canUndo = false)) return
            val values = event.values
            if (values.size < 3) return
            if (detector.add(event.timestamp, values[0], values[1], values[2])) {
                ProblemReportRequest.post(ProblemReportTrigger.shake)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }
    manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
    return { manager.unregisterListener(listener) }
}

@file:PortedFrom("OpenCode/ProblemReports/ProblemReportViews.swift", "553a407")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.GlassSurface
import ai.opencode.mobilecode.system.GroupedList
import ai.opencode.mobilecode.system.LocalDismiss
import ai.opencode.mobilecode.system.NavigationStack
import ai.opencode.mobilecode.system.NavigationTitle
import ai.opencode.mobilecode.system.SystemButton
import ai.opencode.mobilecode.system.SystemColors
import ai.opencode.mobilecode.system.SystemImage
import ai.opencode.mobilecode.system.SystemMenu
import ai.opencode.mobilecode.system.SystemMenuItem
import ai.opencode.mobilecode.system.SystemMenuSection
import ai.opencode.mobilecode.system.SystemSheet
import ai.opencode.mobilecode.system.TextStyles
import ai.opencode.mobilecode.system.TitleDisplayMode
import ai.opencode.mobilecode.system.Toolbar
import ai.opencode.mobilecode.system.ToolbarItem
import ai.opencode.mobilecode.system.ToolbarItemPlacement
import ai.opencode.mobilecode.system.glassEffect
import ai.opencode.mobilecode.system.medium
import ai.opencode.mobilecode.system.symbolSize
import ai.opencode.mobilecode.tca.BindingAction
import ai.opencode.mobilecode.tca.Dependency
import ai.opencode.mobilecode.tca.DependencyValues
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/// Asks Home's problem reports to open the sheet: a shake, or Settings.
object ProblemReportRequest {
    const val labShakeName = "ai.opencode.lab.shake"
    const val labShakeMidTransitionName = "ai.opencode.lab.shake-mid-transition"

    /// Settings opened Phone Reports: Home closes the drawer it came from.
    const val openedPhoneReportsNotification = "OpenCodePhoneReportsOpened"

    private val requests = MutableSharedFlow<ProblemReportTrigger>(extraBufferCapacity = 8)
    private val openedPhoneReports = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val bridgeInstalled = AtomicBoolean(false)

    val notifications = requests.asSharedFlow()
    val phoneReportsOpened = openedPhoneReports.asSharedFlow()

    fun post(trigger: ProblemReportTrigger) {
        requests.tryEmit(trigger)
    }

    fun postOpenedPhoneReports() {
        openedPhoneReports.tryEmit(Unit)
    }

    /// A shake is the system's Undo Typing while there's typing to undo, and
    /// does nothing while the sheet is already up.
    fun shakeReports(canUndo: Boolean): Boolean {
        if (canUndo) return false
        return ProblemReportHub.shared.store?.state?.composer == null
    }

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

/// The store the composer pill reads: the pill lives in a session screen
/// hosted outside the reports modifier.
class ProblemReportHub {
    var store: Store<ProblemReportsFeature.State, ProblemReportsFeature.Action>? = null

    companion object {
        val shared = ProblemReportHub()
    }
}

/// Shake and Settings requests, the Report Problem sheet, and the pill's store.
@Composable
fun problemReports(
    store: Store<ProblemReportsFeature.State, ProblemReportsFeature.Action>,
    content: @Composable () -> Unit,
) {
    content()
    if (!ProblemReportsFeature.isAvailable) return
    val context = LocalContext.current
    LaunchedEffect(store) {
        ProblemReportRequest.notifications.collect { trigger ->
            if (store.state.composer != null) return@collect
            // Settings closes first, so the screenshot shows the app under it.
            if (trigger == ProblemReportTrigger.settings) delay(650)
            store.send(ProblemReportsFeature.Action.reportRequested(trigger, WindowSnapshot.jpeg()))
        }
    }
    DisposableEffect(context) {
        ProblemReportRequest.installLabShakeBridge(context)
        val stop = listenForShakes(context)
        onDispose { stop() }
    }
    store.task {
        ProblemReportHub.shared.store = store
        store.send(ProblemReportsFeature.Action.task)
    }
    val composer = store.state.composer
    if (composer != null) {
        SystemSheet(onDismissRequest = {
            if (store.state.composer?.phase == ProblemReportsFeature.State.Composer.Phase.sending) return@SystemSheet
            if (store.state.composer?.reportID == composer.reportID) store.send(ProblemReportsFeature.Action.cancelTapped)
        }) {
            ProblemReportSheet(store)
        }
    }
}

private fun listenForShakes(context: Context): () -> Unit {
    val manager = context.getSystemService(SensorManager::class.java) ?: return {}
    val sensor = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return {}
    val detector = ProblemReportShake()
    val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!ProblemReportsFeature.isAvailable) return
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

@Composable
fun ProblemReportSheet(store: Store<ProblemReportsFeature.State, ProblemReportsFeature.Action>) {
    val context = LocalContext.current
    val composer = store.state.composer
    val reportStore by Dependency(DependencyValues::problemReportStore)
    var thumbnail by remember(composer?.reportID) { mutableStateOf<Bitmap?>(null) }
    val savedID = composer?.takeIf { it.isReady && it.hasScreenshot }?.reportID
    LaunchedEffect(savedID) {
        val id = savedID ?: return@LaunchedEffect
        val bytes = reportStore.data(id, ProblemReportBundle.File.screenshot) ?: return@LaunchedEffect
        thumbnail = withContext(Dispatchers.Default) { android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
    }
    NavigationStack(Modifier.fillMaxWidth()) {
        if (composer?.isPickingProject == true) {
            ReportProjectPicker(store)
            Toolbar {
                ToolbarItem(placement = ToolbarItemPlacement.cancellationAction) {
                    SystemImage(
                        "chevron.backward",
                        contentDescription = "Back",
                        modifier = Modifier.symbolSize(TextStyles.body).clickable {
                            store.send(ProblemReportsFeature.Action.projectPickerDismissed)
                        },
                    )
                }
            }
        } else {
            NavigationTitle("Report Problem", displayMode = TitleDisplayMode.inline)
            val editing = composer?.phase == ProblemReportsFeature.State.Composer.Phase.editing
            GroupedList(scrollContentBackground = false, modifier = Modifier.fillMaxWidth()) {
                if (composer?.trigger?.isAutomatic == true) {
                    Section {
                        ForEach(listOf("trigger"), id = { it }) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SystemImage(composer.trigger.symbolName, contentDescription = null, tint = SystemColors.secondaryLabel, modifier = Modifier.symbolSize(TextStyles.body))
                                Text(composer.trigger.title, style = TextStyles.body, color = SystemColors.label)
                            }
                        }
                    }
                }
                Section {
                    ForEach(listOf("note"), id = { it }) {
                        BasicTextField(
                            value = composer?.note ?: "",
                            onValueChange = { if (editing) store.send(ProblemReportsFeature.Action.noteChanged(it)) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 66.dp, max = 176.dp).testTag("problem-report-note"),
                            textStyle = TextStyles.body.copy(color = SystemColors.label),
                            cursorBrush = SolidColor(SystemColors.tint),
                            minLines = 3,
                            maxLines = 8,
                            decorationBox = { inner ->
                                Box {
                                    if (composer?.note.isNullOrEmpty()) {
                                        Text("Note", style = TextStyles.body, color = SystemColors.tertiaryLabel)
                                    }
                                    inner()
                                }
                            },
                        )
                    }
                }
                if (composer?.hasScreenshot == true) {
                    Section {
                        ForEach(listOf("screenshot"), id = { it }) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = editing, role = Role.Switch) {
                                        store.send(ProblemReportsFeature.Action.screenshotToggled(composer.includesScreenshot.not()))
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ScreenshotThumbnail(thumbnail)
                                Spacer(Modifier.width(12.dp))
                                Text("Screenshot", Modifier.weight(1f), style = TextStyles.body, color = SystemColors.label)
                                ReportSwitch(composer.includesScreenshot)
                            }
                        }
                    }
                }
            }
            Toolbar {
                ToolbarItem(placement = ToolbarItemPlacement.cancellationAction) {
                    val cancelable = composer?.phase != ProblemReportsFeature.State.Composer.Phase.sending
                    SystemButton("Cancel") { if (cancelable) store.send(ProblemReportsFeature.Action.cancelTapped) }
                }
                ToolbarItem(placement = ToolbarItemPlacement.confirmationAction) {
                    if (composer?.phase == ProblemReportsFeature.State.Composer.Phase.sending) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = SystemColors.secondaryLabel)
                    } else {
                        val ready = composer?.isReady == true && editing
                        Box(Modifier.testTag("problem-report-send")) {
                            SystemButton("Send") {
                                if (ready) store.send(ProblemReportsFeature.Action.sendTapped)
                            }
                        }
                    }
                }
            }
            val reportID = composer?.reportID
            if (reportID != null && composer.isReady) {
                var more by remember { mutableStateOf(false) }
                Toolbar {
                    ToolbarItem(placement = ToolbarItemPlacement.topBarTrailing) {
                        Box {
                            SystemImage(
                                "ellipsis",
                                contentDescription = "More",
                                modifier = Modifier.symbolSize(TextStyles.body).clickable { more = true },
                            )
                            SystemMenu(
                                expanded = more,
                                onDismissRequest = { more = false },
                                sections = listOf(
                                    SystemMenuSection(
                                        listOf(
                                            SystemMenuItem("Share Report") {
                                                more = false
                                                shareReport(context, reportStore.shareableFiles(reportID))
                                            },
                                        ),
                                    ),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScreenshotThumbnail(bitmap: Bitmap?) {
    if (bitmap == null) return
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .height(64.dp)
            .aspectRatio(bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1))
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, SystemColors.separator, RoundedCornerShape(6.dp)),
    )
}

@Composable
private fun ReportSwitch(checked: Boolean) {
    val track = if (checked) {
        SystemColors.systemGreen
    } else if (isSystemInDarkTheme()) {
        SystemColors.systemGray4
    } else {
        SystemColors.systemGray5
    }
    Box(Modifier.size(width = 51.dp, height = 31.dp).clip(CircleShape).background(track)) {
        Box(
            Modifier
                .padding(2.dp)
                .size(27.dp)
                .offset(x = if (checked) 20.dp else 0.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

/// Phone Reports' project, picked once per server.
@Composable
fun ReportProjectPicker(store: Store<ProblemReportsFeature.State, ProblemReportsFeature.Action>) {
    val projects = store.state.projects
    val filtered = store.state.filteredProjects
    val selected = store.state.destination?.projectID
    val filter = store.state.projectFilter
    NavigationTitle("Send Reports To", displayMode = TitleDisplayMode.inline)
    Column(Modifier.fillMaxSize()) {
        BasicTextField(
            value = filter,
            onValueChange = { store.send(ProblemReportsFeature.Action.binding(BindingAction.set(ProblemReportsFeature.State::projectFilter, it))) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .background(SystemColors.tertiarySystemFill, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            textStyle = TextStyles.body.copy(color = SystemColors.label),
            cursorBrush = SolidColor(SystemColors.tint),
            singleLine = true,
            decorationBox = { inner ->
                Box {
                    if (filter.isEmpty()) Text("Search", style = TextStyles.body, color = SystemColors.tertiaryLabel)
                    inner()
                }
            },
        )
        Box(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize()) {
                items(filtered, key = { it.id }) { project ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { store.send(ProblemReportsFeature.Action.projectPicked(project)) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(project.name, style = TextStyles.body, color = SystemColors.label)
                            Text(
                                project.directory,
                                style = TextStyles.footnote,
                                color = SystemColors.tertiaryLabel,
                                maxLines = 1,
                                overflow = TextOverflow.StartEllipsis,
                            )
                        }
                        if (selected == project.id) {
                            SystemImage("checkmark", contentDescription = null, tint = SystemColors.tint, modifier = Modifier.symbolSize(TextStyles.subheadline))
                        }
                    }
                }
            }
            if (projects.isEmpty()) {
                Unavailable("No Projects Yet", "folder.badge.questionmark")
            } else if (filtered.isEmpty()) {
                Unavailable("No Results", null, "No results for “$filter”")
            }
        }
    }
}

@Composable
private fun Unavailable(title: String, symbol: String?, detail: String? = null) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        if (symbol != null) {
            SystemImage(symbol, contentDescription = null, tint = SystemColors.secondaryLabel, modifier = Modifier.symbolSize(28))
            Spacer(Modifier.height(8.dp))
        }
        Text(title, style = TextStyles.title3, color = SystemColors.secondaryLabel)
        if (detail != null) {
            Spacer(Modifier.height(4.dp))
            Text(detail, style = TextStyles.body, color = SystemColors.tertiaryLabel)
        }
    }
}

/// Settings → Diagnostics rows.
@Composable
fun ProblemReportSettingsRows(store: Store<ProblemReportsFeature.State, ProblemReportsFeature.Action>) {
    val dismiss = LocalDismiss.current
    Column {
        Text(
            "Report Problem",
            Modifier
                .fillMaxWidth()
                .clickable {
                    dismiss()
                    ProblemReportRequest.post(ProblemReportTrigger.settings)
                }
                .padding(vertical = 11.dp),
            style = TextStyles.body,
            color = SystemColors.label,
        )
        Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Send Reports To", Modifier.weight(1f), style = TextStyles.body, color = SystemColors.label)
            Text(store.state.destination?.projectName ?: "None", style = TextStyles.body, color = SystemColors.secondaryLabel)
        }
        if (store.state.destination?.sessionID != null) {
            Text(
                "Open ${PhoneReportsDelivery.sessionTitle}",
                Modifier
                    .fillMaxWidth()
                    .testTag("problem-report-open-session")
                    .clickable {
                        dismiss()
                        ProblemReportRequest.postOpenedPhoneReports()
                        store.send(ProblemReportsFeature.Action.openPhoneReportsTapped)
                    }
                    .padding(vertical = 11.dp),
                style = TextStyles.body,
                color = SystemColors.label,
            )
        }
    }
}

/// Send Report? in the composer pill row when the app noticed trouble, or
/// how a sent report went. The session screen hides it while typing,
/// dictating, or on a call, so it never interrupts them; a notice waits
/// until it shows.
object ProblemReportOfferPill {
    val isOffered: Boolean
        get() {
            val store = ProblemReportHub.shared.store ?: return false
            return (store.state.offer != null || store.state.notice != null) && store.state.composer == null
        }
}

@Composable
fun ProblemReportOfferPill() {
    val store = ProblemReportHub.shared.store
    if (store == null || store.state.composer != null) return
    val notice = store.state.notice
    val offer = store.state.offer
    if (notice != null) {
        ProblemReportNoticePill(store, notice)
    } else if (offer != null) {
        OfferPill(store, offer)
    }
}

@Composable
private fun OfferPill(
    store: Store<ProblemReportsFeature.State, ProblemReportsFeature.Action>,
    offer: ProblemReportsFeature.State.Offer,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        ProblemReportPillLabel(
            title = "Send Report?",
            modifier = Modifier
                .testTag("problem-report-offer")
                .semantics {
                    contentDescription = "Send report. ${offer.trigger.title}"
                }
                .clickable { store.send(ProblemReportsFeature.Action.offerTapped) },
        ) {
            SystemImage(offer.trigger.symbolName, contentDescription = null, modifier = Modifier.symbolSize(TextStyles.subheadline))
        }
        ProblemReportDismissButton(
            Modifier.testTag("problem-report-offer-dismiss"),
        ) { store.send(ProblemReportsFeature.Action.offerDismissed) }
    }
}

/// Sent to Phone Reports, Will send when connected, Report sent, or
/// Couldn’t send report with Retry and Share Report.
@Composable
private fun ProblemReportNoticePill(
    store: Store<ProblemReportsFeature.State, ProblemReportsFeature.Action>,
    notice: ProblemReportsFeature.State.Notice,
) {
    val reportStore by Dependency(DependencyValues::problemReportStore)
    val context = LocalContext.current
    LaunchedEffect(notice.id) {
        store.send(ProblemReportsFeature.Action.noticeAppeared(notice.id))
    }
    val title = when (notice.kind) {
        is ProblemReportsFeature.State.Notice.Kind.sent -> "Sent to ${PhoneReportsDelivery.sessionTitle}"
        ProblemReportsFeature.State.Notice.Kind.waiting -> "Will send when connected"
        is ProblemReportsFeature.State.Notice.Kind.delivered -> "Report sent"
        is ProblemReportsFeature.State.Notice.Kind.failed -> "Couldn’t send report"
    }
    Box(Modifier.testTag("problem-report-notice")) {
        when (val kind = notice.kind) {
            is ProblemReportsFeature.State.Notice.Kind.sent, is ProblemReportsFeature.State.Notice.Kind.delivered -> {
                ProblemReportPillLabel(
                    title = title,
                    modifier = Modifier
                        .semantics { contentDescription = "$title. Opens ${PhoneReportsDelivery.sessionTitle}" }
                        .clickable { store.send(ProblemReportsFeature.Action.noticeTapped) },
                ) {
                    SystemImage("checkmark.circle.fill", contentDescription = null, tint = SystemColors.systemGreen, modifier = Modifier.symbolSize(TextStyles.subheadline))
                }
            }
            ProblemReportsFeature.State.Notice.Kind.waiting -> {
                ProblemReportPillLabel(title = title, interactive = false, modifier = Modifier.semantics(mergeDescendants = true) {}) {
                    SystemImage("clock.arrow.circlepath", contentDescription = null, tint = SystemColors.secondaryLabel, modifier = Modifier.symbolSize(TextStyles.subheadline))
                }
            }
            is ProblemReportsFeature.State.Notice.Kind.failed -> {
                var menu by remember { mutableStateOf(false) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        ProblemReportPillLabel(
                            title = title,
                            modifier = Modifier.clickable { menu = true },
                        ) {
                            SystemImage(
                                "exclamationmark.triangle.fill",
                                contentDescription = null,
                                tint = SystemColors.systemOrange,
                                modifier = Modifier.symbolSize(TextStyles.subheadline),
                            )
                        }
                        SystemMenu(
                            expanded = menu,
                            onDismissRequest = { menu = false },
                            sections = listOf(
                                SystemMenuSection(
                                    listOf(
                                        SystemMenuItem("Retry", systemImage = "arrow.clockwise") {
                                            menu = false
                                            store.send(ProblemReportsFeature.Action.retryTapped)
                                        },
                                        SystemMenuItem("Share Report") {
                                            menu = false
                                            shareReport(context, reportStore.shareableFiles(kind.reportID))
                                        },
                                    ),
                                ),
                            ),
                        )
                    }
                    ProblemReportDismissButton(Modifier.testTag("problem-report-notice-dismiss")) {
                        store.send(ProblemReportsFeature.Action.noticeDismissed)
                    }
                }
            }
        }
    }
}

/// The composer pill family's slim glass capsule, with a full 44 pt target.
@Composable
private fun ProblemReportPillLabel(
    title: String,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    icon: @Composable () -> Unit,
) {
    Row(
        modifier
            .padding(vertical = 5.dp)
            .glassEffect(GlassSurface.Capsule, interactive = interactive)
            .padding(horizontal = 14.dp)
            .heightIn(min = 34.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.width(18.dp), contentAlignment = Alignment.Center) { icon() }
        Text(title, style = TextStyles.subheadline.medium, color = SystemColors.label, maxLines = 1)
    }
}

@Composable
private fun ProblemReportDismissButton(modifier: Modifier = Modifier, action: () -> Unit) {
    Box(
        modifier
            .semantics { contentDescription = "Dismiss" }
            .clickable(role = Role.Button, onClick = action)
            .padding(5.dp)
            .size(34.dp)
            .glassEffect(GlassSurface.Circle, interactive = true),
        contentAlignment = Alignment.Center,
    ) {
        SystemImage("xmark", contentDescription = null, tint = SystemColors.secondaryLabel, modifier = Modifier.symbolSize(TextStyles.caption))
    }
}

private fun shareReport(context: Context, files: List<File>) {
    if (files.isEmpty()) return
    val uris = files.mapNotNull { file ->
        runCatching { FileProvider.getUriForFile(context, "${context.packageName}.files", file) }.getOrNull()
    }
    if (uris.isEmpty()) return
    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "*/*"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "Share Report")) }
}

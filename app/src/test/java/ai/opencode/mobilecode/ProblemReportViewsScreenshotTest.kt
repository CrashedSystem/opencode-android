package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.GlassBackdrop
import ai.opencode.mobilecode.system.LocalGlassBlurEnabled
import ai.opencode.mobilecode.system.SystemColors
import ai.opencode.mobilecode.system.SystemTheme
import ai.opencode.mobilecode.tca.DependencyValues
import ai.opencode.mobilecode.tca.Store
import ai.opencode.mobilecode.tca.TestDependencies
import ai.opencode.mobilecode.tca.withLock
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/// No iOS capture of these sheets exists. The layout follows ProblemReportViews.swift.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w440dp-h956dp-xxhdpi")
class ProblemReportViewsScreenshotTest {
    @get:Rule
    val dependencies = TestDependencies()

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun composerLight() = sheet("composer-light", composerStore())

    @Test
    @Config(qualifiers = "+night")
    fun composerDark() = sheet("composer-dark", composerStore())

    @Test
    fun pickerLight() = sheet("picker-light", pickerStore())

    @Test
    @Config(qualifiers = "+night")
    fun pickerDark() = sheet("picker-dark", pickerStore())

    @Test
    fun offerLight() {
        val store = offerStore()
        capture("offer-light") { offer(store) }
    }

    @Test
    @Config(qualifiers = "+night")
    fun offerDark() {
        val store = offerStore()
        capture("offer-dark") { offer(store) }
    }

    private fun sheet(name: String, store: Store<ProblemReportsFeature.State, ProblemReportsFeature.Action>) =
        capture(name) { composerSheet(store) }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            SystemTheme {
                CompositionLocalProvider(LocalGlassBlurEnabled provides false) {
                    content()
                }
            }
        }
        compose.waitForIdle()
        captureScreenRoboImage("src/test/snapshots/problem-reports/ProblemReportViews.$name.png")
    }

    @androidx.compose.runtime.Composable
    private fun composerSheet(store: Store<ProblemReportsFeature.State, ProblemReportsFeature.Action>) {
        problemReports(store) {
            Box(Modifier.fillMaxSize().background(SystemColors.systemBackground))
        }
    }

    private fun composerStore(): Store<ProblemReportsFeature.State, ProblemReportsFeature.Action> {
        val reports = reports()
        val id = UUID(0, 1)
        reports.save(bundle(id, screenshot = jpeg()))
        return Store(
            initialState = {
                ProblemReportsFeature.State().apply {
                    composer = ProblemReportsFeature.State.Composer(
                        reportID = id,
                        trigger = ProblemReportTrigger.callDropped,
                        note = "It cut out mid-sentence",
                        hasScreenshot = true,
                        isReady = true,
                    )
                }
            },
        ) { ProblemReportsFeature() }
    }

    private fun pickerStore(): Store<ProblemReportsFeature.State, ProblemReportsFeature.Action> {
        val server = OpenCodeServer(url = "http://127.0.0.1:4096", username = "opencode", password = "")
        val store = Store(
            initialState = {
                ProblemReportsFeature.State().apply {
                    this.server = server
                    projects = listOf(
                        OpenCodeProject(id = "p", name = "opencode", directory = "/Users/kit/opencode"),
                        OpenCodeProject(id = "q", name = "mobile", directory = "/Users/kit/mobile"),
                    )
                    composer = ProblemReportsFeature.State.Composer(
                        reportID = UUID(0, 2),
                        trigger = ProblemReportTrigger.settings,
                        isReady = true,
                        isPickingProject = true,
                    )
                }
            },
        ) { ProblemReportsFeature() }
        store.state::destinations.withLock {
            value = mapOf(
                server.persistenceID to PhoneReportsDestination(
                    projectID = "p",
                    projectName = "opencode",
                    directory = "/Users/kit/opencode",
                ),
            )
        }
        return store
    }

    @androidx.compose.runtime.Composable
    private fun offer(store: Store<ProblemReportsFeature.State, ProblemReportsFeature.Action>) {
        ProblemReportHub.shared.store = store
        GlassBackdrop(
            modifier = Modifier.fillMaxSize().background(SystemColors.systemBackground),
            content = { Box(Modifier.fillMaxSize().background(SystemColors.secondarySystemBackground)) },
            overlay = {
                Box(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                    ProblemReportOfferPill()
                }
            },
        )
    }

    private fun offerStore() = Store(
        initialState = {
            ProblemReportsFeature.State().apply {
                offer = ProblemReportsFeature.State.Offer(UUID(0, 3), ProblemReportTrigger.callDropped)
            }
        },
    ) { ProblemReportsFeature() }

    private fun reports(): ProblemReportStore {
        val directory = File(System.getProperty("java.io.tmpdir"), "report-shots-${UUID.randomUUID()}")
        val store = ProblemReportStore(directory)
        DependencyValues.current.problemReportStore = store
        return store
    }

    private fun bundle(id: UUID, screenshot: ByteArray) = ProblemReportBuilder.make(
        id = id,
        trigger = ProblemReportTrigger.callDropped,
        status = ProblemReport.Status.composing,
        note = "It cut out mid-sentence",
        context = ProblemReportContext(screen = "Session", connection = "Connected"),
        device = ProblemReportDevice("0.1.0", "37", "Debug", "Android 16", "Pixel"),
        now = java.time.Instant.ofEpochSecond(2_000),
        events = emptyList(),
        screenshot = screenshot,
    )

    private fun jpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(40, 80, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.DKGRAY)
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
        bitmap.recycle()
        return stream.toByteArray()
    }
}

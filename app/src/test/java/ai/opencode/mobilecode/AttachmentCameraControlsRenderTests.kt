@file:PortedFrom("OpenCodeTests/AttachmentCameraControlsRenderTests.swift", "553a407")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.ForcedAppearance
import ai.opencode.mobilecode.system.LocalGlassBlurEnabled
import ai.opencode.mobilecode.system.SystemTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w400dp-h860dp-mdpi")
class AttachmentCameraControlsRenderTests {
    @get:Rule
    val composeRule = createComposeRule()

    private val triple = CameraZoomModel(
        CameraDeviceDescription(
            position = CameraDeviceDescription.Position.back,
            lenses = listOf(
                CameraDeviceDescription.Lens.ultraWide,
                CameraDeviceDescription.Lens.wide,
                CameraDeviceDescription.Lens.telephoto,
            ),
            switchOverFactors = listOf(2.0, 8.0),
            minZoom = 1.0,
            maxZoom = 123.0,
            nativeCropFactors = listOf(4.0),
        ),
    )
    private val front = CameraZoomModel(
        CameraDeviceDescription(
            position = CameraDeviceDescription.Position.front,
            lenses = listOf(CameraDeviceDescription.Lens.wide),
            minZoom = 1.0,
            maxZoom = 16.0,
        ),
    )

    private data class Scenario(
        val name: String,
        val zoom: CameraZoomModel,
        val factor: Double,
        val flash: AttachmentCameraFlash?,
        val focus: AttachmentFocusMark? = null,
        val locked: Boolean = false,
    )

    private val scenarios = listOf(
        Scenario("wide", triple, 2.0, AttachmentCameraFlash.off),
        Scenario(
            "pinch-1.6",
            triple,
            3.2,
            AttachmentCameraFlash.auto,
            AttachmentFocusMark(point = Offset(250f, 420f), locked = false),
        ),
        Scenario("ultra-wide", triple, 1.0, AttachmentCameraFlash.on),
        Scenario(
            "telephoto-locked",
            triple,
            8.0,
            AttachmentCameraFlash.off,
            AttachmentFocusMark(point = Offset(180f, 380f), locked = true),
            locked = true,
        ),
        Scenario("front-1.4", front, 1.4, null),
    )

    @Test fun wideLight() = capture(scenarios[0], dark = false)
    @Test fun wideDark() = capture(scenarios[0], dark = true)
    @Test fun pinchLight() = capture(scenarios[1], dark = false)
    @Test fun pinchDark() = capture(scenarios[1], dark = true)
    @Test fun ultraWideLight() = capture(scenarios[2], dark = false)
    @Test fun ultraWideDark() = capture(scenarios[2], dark = true)
    @Test fun telephotoLockedLight() = capture(scenarios[3], dark = false)
    @Test fun telephotoLockedDark() = capture(scenarios[3], dark = true)
    @Test fun frontLight() = capture(scenarios[4], dark = false)
    @Test fun frontDark() = capture(scenarios[4], dark = true)

    private fun capture(scenario: Scenario, dark: Boolean) {
        composeRule.setContent {
            ForcedAppearance(dark) {
                SystemTheme {
                    CompositionLocalProvider(LocalGlassBlurEnabled provides false) {
                        Box(Modifier.size(400.dp, 860.dp)) {
                            Placeholder(Modifier.fillMaxSize())
                            AttachmentFocusIndicator(scenario.focus)
                            AttachmentCameraControls(
                                canCapture = true,
                                canFlip = true,
                                flash = scenario.flash,
                                zoom = scenario.zoom,
                                zoomFactor = scenario.factor,
                                isFocusLocked = scenario.locked,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
        val suffix = if (dark) "dark" else "light"
        composeRule.onRoot().captureRoboImage("src/test/snapshots/camera/${scenario.name}-$suffix.png")
    }
}

@Composable
private fun Placeholder(modifier: Modifier = Modifier) {
    Box(modifier) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color(0.42f, 0.62f, 0.86f), Color(0.86f, 0.82f, 0.70f))),
            ),
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(420.dp)
                .background(Brush.verticalGradient(listOf(Color(0.36f, 0.50f, 0.30f), Color(0.20f, 0.30f, 0.18f)))),
        )
        Box(
            Modifier
                .align(Alignment.Center)
                .offset(x = (-40).dp, y = (-80).dp)
                .size(140.dp)
                .background(Color(0.85f, 0.45f, 0.30f), CircleShape),
        )
        Box(
            Modifier
                .align(Alignment.Center)
                .offset(x = 90.dp, y = 40.dp)
                .size(width = 120.dp, height = 180.dp)
                .background(Color(0.95f, 0.93f, 0.88f), RoundedCornerShape(12.dp)),
        )
    }
}

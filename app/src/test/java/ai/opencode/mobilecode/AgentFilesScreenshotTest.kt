package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.ForcedAppearance
import ai.opencode.mobilecode.system.LocalGlassBlurEnabled
import ai.opencode.mobilecode.system.SystemColors
import ai.opencode.mobilecode.system.SystemImage
import ai.opencode.mobilecode.system.SystemTheme
import ai.opencode.mobilecode.system.TextStyles
import ai.opencode.mobilecode.system.glassEffect
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w400dp-h860dp-mdpi")
class AgentFilesScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test fun menuLight() = capture("menu-light", false) { MenuShot() }
    @Test fun menuDark() = capture("menu-dark", true) { MenuShot() }
    @Test fun composerTextLight() = capture("composer-text-light", false) { ComposerShot(text = "Check the composer with a short note", attachment = false) }
    @Test fun composerTextDark() = capture("composer-text-dark", true) { ComposerShot(text = "Check the composer with a short note", attachment = false) }
    @Test fun composerAttachmentsLight() = capture("composer-attachments-light", false) { ComposerShot(text = "", attachment = true) }
    @Test fun composerAttachmentsDark() = capture("composer-attachments-dark", true) { ComposerShot(text = "", attachment = true) }
    @Test fun fileCodeLight() = capture("file-code-light", false) { FileCodeShot() }
    @Test fun fileCodeDark() = capture("file-code-dark", true) { FileCodeShot() }
    @Test fun fileImageLight() = capture("file-image-light", false) { FileImageShot() }
    @Test fun fileImageDark() = capture("file-image-dark", true) { FileImageShot() }

    private fun capture(name: String, dark: Boolean, content: @Composable () -> Unit) {
        composeRule.setContent {
            ForcedAppearance(dark) {
                SystemTheme {
                    CompositionLocalProvider(LocalGlassBlurEnabled provides false) {
                        Box(Modifier.size(400.dp, 860.dp).background(SystemColors.systemGroupedBackground), contentAlignment = Alignment.BottomCenter) {
                            content()
                        }
                    }
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/agent-files/$name.png")
    }
}

@Composable
private fun MenuShot() {
    val anchors = remember { AttachmentMenuAnchors() }
    val selection = remember { InlinePhotoSelection() }
    Box(Modifier.fillMaxSize()) {
        ComposerCapsule(text = "", attachment = false, modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp))
        AttachmentMenuOverlay(
            phase = AttachmentMenuPhase.menu,
            onPhase = {},
            anchors = anchors,
            selection = selection,
            includesVideos = true,
            canUseCamera = true,
            canAttachFiles = true,
            canPaste = true,
            onCamera = {},
            onCameraCapture = {},
            onFiles = {},
            onFilesPicked = {},
            onPaste = {},
            onPhotos = {},
            pickerReservation = 0f,
            onPickerReservation = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun ComposerShot(text: String, attachment: Boolean) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        ComposerCapsule(text, attachment, Modifier.padding(16.dp))
    }
}

@Composable
private fun ComposerCapsule(text: String, attachment: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(28.dp))
            .glassEffect(RoundedCornerShape(28.dp), tint = SystemColors.systemBackground.copy(alpha = 0.96f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        if (attachment) {
            Box(Modifier.padding(bottom = 8.dp).size(72.dp)) {
                Box(
                    Modifier
                        .size(64.dp)
                        .align(Alignment.BottomStart)
                        .background(Color(0xFF5B4BFF), RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(28.dp).background(Color.White, RoundedCornerShape(4.dp)))
                }
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(18.dp)
                        .background(SystemColors.label, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    SystemImage("xmark", contentDescription = "Remove", tint = SystemColors.systemBackground, modifier = Modifier.size(10.dp))
                }
            }
        }
        ComposerTextInput(
            text = text,
            onText = {},
            isFocused = text.isNotEmpty(),
            onFocus = {},
            expanded = attachment || text.isNotEmpty(),
            onImagePaste = {},
            modifier = Modifier.fillMaxWidth(),
        )
        if (text.isEmpty() && !attachment) {
            androidx.compose.material3.Text("Message", style = TextStyles.body, color = SystemColors.placeholderText)
        }
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            SystemImage("plus", contentDescription = "Add attachment", tint = SystemColors.label, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(16.dp))
            androidx.compose.material3.Text("Fixture model", style = TextStyles.subheadline, color = SystemColors.secondaryLabel)
            SystemImage("chevron.up.chevron.down", contentDescription = null, tint = SystemColors.secondaryLabel, modifier = Modifier.size(12.dp).padding(start = 4.dp))
            Spacer(Modifier.weight(1f))
            SystemImage("mic", contentDescription = "Dictate message", tint = SystemColors.secondaryLabel, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(34.dp).background(SystemColors.label, CircleShape), contentAlignment = Alignment.Center) {
                SystemImage("arrow.up", contentDescription = "Send message", tint = SystemColors.systemBackground, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun FileCodeShot() {
    val source = (1..18).joinToString("\n") { line ->
        when (line) {
            12 -> "    func layout() {"
            13 -> "        composer.frame(in: .named(space))"
            else -> "    let line$line = $line"
        }
    }
    AgentFilePage(
        reference = AgentFileReference("SessionView.swift", 12),
        server = OpenCodeServer("https://box.ts.net", "opencode", ""),
        directory = "/Users/kit/code/opencode-ios",
        isRoot = true,
        onOpen = {},
        onDismiss = {},
        modifier = Modifier.fillMaxSize(),
        initial = AgentFileViewerModel.Phase.ready(
            AgentFileViewerModel.Content.text(AgentTextDocument(source, "SessionView.swift", 12)),
            null,
        ),
    )
}

@Composable
private fun FileImageShot() {
    val file = remember {
        val image = Bitmap.createBitmap(240, 160, Bitmap.Config.ARGB_8888)
        image.eraseColor(Color(0xFF5B4BFF).toArgb())
        val destination = File(System.getProperty("java.io.tmpdir"), "agent-file-shot.png")
        destination.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        destination
    }
    AgentFilePage(
        reference = AgentFileReference("shot.png"),
        server = OpenCodeServer("https://box.ts.net", "opencode", ""),
        directory = "/Users/kit/code/opencode-ios",
        isRoot = true,
        onOpen = {},
        onDismiss = {},
        modifier = Modifier.fillMaxSize(),
        initial = AgentFileViewerModel.Phase.ready(AgentFileViewerModel.Content.quickLook(file), file),
    )
}

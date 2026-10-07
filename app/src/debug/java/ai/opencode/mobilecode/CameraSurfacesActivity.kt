package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.ForcedAppearance
import ai.opencode.mobilecode.system.SystemTheme
import ai.opencode.mobilecode.system.TextStyles
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

class CameraSurfacesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val surface = intent.getStringExtra(SURFACE)
        setContent {
            ForcedAppearance(true) {
                SystemTheme {
                    when (surface) {
                        PAIRING -> PairingScannerView(onScanned = {}, onCancel = { finish() })
                        ATTACHMENT -> AttachmentCameraView(onCapture = {}, onBack = { finish() })
                        else -> Column(
                            Modifier.fillMaxSize().background(Color.Black).padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Text("Camera", color = Color.White, style = TextStyles.largeTitle, modifier = Modifier.padding(bottom = 8.dp))
                            SurfaceRow("Pairing scanner") { open(PAIRING) }
                            SurfaceRow("Attachment camera") { open(ATTACHMENT) }
                        }
                    }
                }
            }
        }
    }

    private fun open(surface: String) {
        startActivity(Intent(this, CameraSurfacesActivity::class.java).putExtra(SURFACE, surface))
    }

    companion object {
        const val SURFACE = "surface"
        const val PAIRING = "pairing"
        const val ATTACHMENT = "attachment"
    }
}

@androidx.compose.runtime.Composable
private fun SurfaceRow(title: String, onClick: () -> Unit) {
    Text(
        title,
        color = Color.Black,
        style = TextStyles.headline,
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    )
}

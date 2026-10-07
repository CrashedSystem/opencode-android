package ai.opencode.mobilecode.system

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier

class SystemCatalogActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val dark = intent.getBooleanExtra("dark", false)
        setContent {
            ForcedAppearance(dark) {
                SystemTheme {
                    SystemCatalog(
                        Modifier
                            .fillMaxSize()
                            .safeDrawingPadding(),
                        scroll = true,
                    )
                }
            }
        }
    }
}

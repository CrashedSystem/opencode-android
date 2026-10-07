package ai.opencode.mobilecode

import ai.opencode.mobilecode.system.SystemTheme
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SystemTheme {
                AppView(store = OpenCodeApp.store)
            }
        }
    }
}

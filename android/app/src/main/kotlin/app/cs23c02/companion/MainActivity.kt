package app.cs23c02.companion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

object AppInfo {
    const val NAME = "CS23C02 Companion"
}

/** The server this build talks to. Fixed at build time from API_BASE_URL (never user-editable). */
object ApiConfig {
    val BASE_URL: String = BuildConfig.API_BASE_URL
    val HOST: String = BASE_URL.removePrefix("https://")
    val CERT_PINS: List<String> = BuildConfig.API_CERT_PINS.split(",").map { it.trim() }.filter { it.isNotEmpty() }
}

/** Build-loop proof only (ADR 0012). Real screens arrive after the pipeline is shown to work. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text("${AppInfo.NAME}\nBuild pipeline OK\nServer: ${ApiConfig.HOST}")
                }
            }
        }
    }
}

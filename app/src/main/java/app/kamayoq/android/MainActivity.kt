package app.kamayoq.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    private val app get() = application as KamayoqApp

    // Read by the tests of a recreated screen.
    internal val opening get() = app.opening
    internal val backups get() = app.backups

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val deviceId = DeviceId.of(applicationContext)
        setContent {
            val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colors) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val failure = app.failure
                    if (failure != null) {
                        LibraryFailureScreen(failure)
                    } else {
                        LibraryApp(app.store, app.folder, opening, backups, app.kavita, app.kavitaSync, deviceId)
                    }
                }
            }
        }
    }
}

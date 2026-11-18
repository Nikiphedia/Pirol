package ch.etasystems.pirol

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import ch.etasystems.pirol.ui.navigation.PirolNavHost
import ch.etasystems.pirol.ui.theme.PirolTheme

class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PirolTheme {
                val windowSizeClass = calculateWindowSizeClass(this)
                // Themed Hintergrund fuer alle Screens (Tablet-Layout hat kein Scaffold)
                Surface(color = MaterialTheme.colorScheme.background) {
                    PirolNavHost(windowSizeClass = windowSizeClass)
                }
            }
        }
    }
}

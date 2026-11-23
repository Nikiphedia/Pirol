package ch.etasystems.pirol

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get
import ch.etasystems.pirol.ui.components.AlarmOverlay
import ch.etasystems.pirol.ui.navigation.PirolNavHost
import ch.etasystems.pirol.ui.theme.PirolTheme

class MainActivity : AppCompatActivity() {

    // T136: eigene Artenlisten beim Zurueckkehren in den Vordergrund pruefen (nur bei Aenderung neu lesen)
    override fun onStart() {
        super.onStart()
        val filter: ch.etasystems.pirol.ml.RegionalSpeciesFilter = get()
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            filter.refreshCustomListsIfChanged()
        }
        // T206: Standort-Fix beim Start/Vordergrund (nur mit Permission, kein Dauertracking)
        if (ch.etasystems.pirol.location.areLocationPermissionsGranted(this)) {
            val locationProvider: ch.etasystems.pirol.location.LocationProvider = get()
            lifecycleScope.launch { locationProvider.ensureFix() }
        }
    }

    /** T195: Vollbild-Einstellung anwenden (Systemleisten aus; per Wischen kurz sichtbar). */
    private fun applyFullscreen(enabled: Boolean) {
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (enabled) controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        else controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
    }

    // Nach Dialogen/Fokuswechsel koennen die Leisten wieder erscheinen
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            val prefs: ch.etasystems.pirol.data.AppPreferences = get()
            if (prefs.fullscreen) applyFullscreen(true)
        }
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val appPrefs: ch.etasystems.pirol.data.AppPreferences = get()
        applyFullscreen(appPrefs.fullscreen)
        lifecycleScope.launch {
            appPrefs.fullscreenFlow.collect { applyFullscreen(it) }
        }
        setContent {
            PirolTheme {
                val windowSizeClass = calculateWindowSizeClass(this)
                // Themed Hintergrund fuer alle Screens (Tablet-Layout hat kein Scaffold)
                Surface(color = MaterialTheme.colorScheme.background) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        PirolNavHost(windowSizeClass = windowSizeClass)
                        AlarmOverlay()  // T124: Watchlist-Alarm ueber allen Tabs
                        WhatsNewHost()
                    }
                }
            }
        }
    }

    /** T183: Changelog genau einmal nach einem Update; bei Neuinstallation still als gesehen markieren. */
    @androidx.compose.runtime.Composable
    private fun WhatsNewHost() {
        val prefs: ch.etasystems.pirol.data.AppPreferences = get()
        val action = androidx.compose.runtime.remember {
            val code = ch.etasystems.pirol.ui.components.currentVersionCode(this)
            val a = ch.etasystems.pirol.ui.components.decideWhatsNew(
                prefs.lastSeenChangelogVersionCode, code, prefs.onboardingCompleted
            )
            if (a == ch.etasystems.pirol.ui.components.WhatsNewAction.MARK_SEEN) prefs.lastSeenChangelogVersionCode = code
            a
        }
        var show by androidx.compose.runtime.remember {
            androidx.compose.runtime.mutableStateOf(action == ch.etasystems.pirol.ui.components.WhatsNewAction.SHOW)
        }
        if (show) {
            ch.etasystems.pirol.ui.components.WhatsNewDialog(onDismiss = {
                prefs.lastSeenChangelogVersionCode = ch.etasystems.pirol.ui.components.currentVersionCode(this)
                show = false
            })
        }
    }
}

package ch.etasystems.pirol.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

// 5 Haupttabs: Live | Analyse | Referenzen | Karte | Settings
enum class Screen(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    Live("live", "Live", Icons.Filled.Mic),
    Analysis("analysis", "Analyse", Icons.Filled.Analytics),
    Reference("reference", "Referenzen", Icons.AutoMirrored.Filled.LibraryBooks),
    Map("map", "Karte", Icons.Filled.Map),
    Settings("settings", "Einstellungen", Icons.Filled.Settings)
}

/** Route fuer den Onboarding-Screen (nicht im Tab-Bar sichtbar) */
const val ONBOARDING_ROUTE = "onboarding"

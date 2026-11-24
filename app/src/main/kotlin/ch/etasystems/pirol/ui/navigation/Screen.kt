package ch.etasystems.pirol.ui.navigation

import androidx.annotation.StringRes
import ch.etasystems.pirol.R
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
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    @StringRes val helpRes: Int,      // T266: Erklaerung im Hilfemodus
    val manualChapter: String         // T266: passendes Kapitel in BDA_PIROL.md
) {
    Live("live", R.string.tab_live, Icons.Filled.Mic, R.string.help_tab_live, "2"),
    Analysis("analysis", R.string.tab_analysis, Icons.Filled.Analytics, R.string.help_tab_analysis, "6"),
    Reference("reference", R.string.tab_reference, Icons.AutoMirrored.Filled.LibraryBooks, R.string.help_tab_reference, "9"),
    Map("map", R.string.tab_map, Icons.Filled.Map, R.string.help_tab_map, "8"),
    Settings("settings", R.string.tab_settings, Icons.Filled.Settings, R.string.help_tab_settings, "17")
}

/** Route fuer den Onboarding-Screen (nicht im Tab-Bar sichtbar) */
const val ONBOARDING_ROUTE = "onboarding"

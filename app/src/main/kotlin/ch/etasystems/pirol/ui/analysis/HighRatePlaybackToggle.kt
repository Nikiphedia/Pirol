package ch.etasystems.pirol.ui.analysis

import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.etasystems.pirol.R
import ch.etasystems.pirol.audio.HighRatePlayback
import ch.etasystems.pirol.data.AppPreferences
import ch.etasystems.pirol.ui.help.helpTopic

/**
 * T294: Umschalter der Wiedergabeart bei Sessions ueber 96 kHz (nur dann sichtbar): Zeitdehnung 10x / 20x
 * oder normal (heruntergerechnet). Tippen wechselt reihum; Pref `pirol_playback_time_expansion`.
 * [onChanged] stoppt eine laufende Wiedergabe (neue Art gilt ab dem naechsten Start).
 */
@Composable
internal fun HighRatePlaybackToggle(sampleRate: Int, onChanged: () -> Unit) {
    if (!HighRatePlayback.applies(sampleRate)) return
    val prefs: AppPreferences = org.koin.compose.koinInject()
    var expansion by remember { mutableIntStateOf(HighRatePlayback.sanitize(prefs.playbackTimeExpansion)) }
    TextButton(
        onClick = {
            expansion = nextExpansion(expansion)
            prefs.playbackTimeExpansion = expansion
            onChanged()
        },
        modifier = Modifier.helpTopic(R.string.help_analysis_time_expansion, "6").defaultMinSize(minHeight = 48.dp)
    ) {
        Text(
            if (expansion <= 1) stringResource(R.string.playback_normal_downsampled)
            else stringResource(R.string.playback_time_expansion, expansion)
        )
    }
}

/** T294: naechste Wahl im Umschalter (reihum durch [HighRatePlayback.EXPANSION_OPTIONS]) */
internal fun nextExpansion(current: Int): Int {
    val opts = HighRatePlayback.EXPANSION_OPTIONS
    val i = opts.indexOf(current)
    return if (i < 0) HighRatePlayback.DEFAULT_EXPANSION else opts[(i + 1) % opts.size]
}

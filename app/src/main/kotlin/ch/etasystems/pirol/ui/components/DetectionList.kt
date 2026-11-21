package ch.etasystems.pirol.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.net.Uri
import ch.etasystems.pirol.audio.AudioPlayer
import ch.etasystems.pirol.audio.VoiceMemoRecorder
import ch.etasystems.pirol.ml.AtlasCode
import ch.etasystems.pirol.ml.DetectionCandidate
import ch.etasystems.pirol.ml.DetectionListState
import ch.etasystems.pirol.ml.PhenologyRepository
import java.io.File
import ch.etasystems.pirol.R
import androidx.compose.ui.res.stringResource

/**
 * LazyColumn mit SpeciesCards fuer alle erkannten Arten.
 *
 * Liest detectionState.version als Compose-Recomposition-Trigger,
 * dann getDetections() fuer die aktuelle Liste.
 *
 * Leerzustand:
 * - Kein Modell: "BirdNET-Modell nicht installiert" mit Warning-Icon
 * - Keine Detektionen: "Warte auf Erkennung..." mit Hearing-Icon
 *
 * @param detectionState Thread-safe State-Holder (Koin Singleton)
 * @param isModelAvailable true wenn BirdNET ONNX-Modell verfuegbar
 * @param onConfirm Callback fuer Bestaetigung (detectionId)
 * @param onReject Callback fuer Ablehnung (detectionId)
 * @param onCorrect Callback fuer Korrektur (detectionId, correctedSpecies)
 * @param modifier Modifier fuer die aeussere Box
 */
@Composable
fun DetectionList(
    detectionState: DetectionListState,
    isModelAvailable: Boolean = true,
    onConfirm: ((String) -> Unit)? = null,
    onMarkUncertain: ((String) -> Unit)? = null,
    onReject: ((String) -> Unit)? = null,
    onCorrect: ((String, String) -> Unit)? = null,
    onSaveAsReference: ((String) -> Unit)? = null,
    referencedIds: Set<String> = emptySet(),  // T127
    onShare: ((String) -> Unit)? = null,  // T116
    onSelectAlternative: ((String, DetectionCandidate) -> Unit)? = null,
    onEditDetection: ((id: String, count: Int, atlasCode: String?, note: String?, voiceMemoFile: String?) -> Unit)? = null,
    atlasCodes: List<AtlasCode> = emptyList(),
    watchlistSpecies: Set<String> = emptySet(),
    showTopNCandidates: Boolean = true,
    // T69: Voice-Memo
    voiceMemoRecorder: VoiceMemoRecorder? = null,
    memoAudioPlayer: AudioPlayer? = null,
    onGetMemoTargetFile: ((detectionId: String) -> File?)? = null,
    onGetMemoTargetUri: ((detectionId: String) -> Uri?)? = null,
    onSnackbar: (String) -> Unit = {},
    // T74: Phenology
    phenologyEnabled: Boolean = false,
    phenologyRepository: PhenologyRepository? = null,
    currentMonth: Int = java.time.LocalDate.now().monthValue,
    // T73: Lock-Species
    onToggleLock: ((String) -> Unit)? = null,
    // T164: Ausschnitt der Detektion anhoeren (Play/Stopp auf der Karte)
    onPlay: ((String) -> Unit)? = null,
    playingDetectionId: String? = null,
    modifier: Modifier = Modifier
) {
    // T106: version muss tatsaechlich gelesen werden, sonst beobachtet Compose den Zaehler nicht.
    // Ein unbenutztes "val version by ..." liest den State nie → Liste aktualisierte erst beim Tab-Wechsel.
    val version = detectionState.version.value
    val detections = remember(version) { detectionState.getDetections() }
    // T178: pro Karte merken, fuer welchen Treffer schon geblinkt wurde (kein State, loest keine Recomposition aus)
    val blinkHandled = remember { HashMap<String, Long>() }

    if (detections.isEmpty()) {
        // Leerzustand
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            if (!isModelAvailable) {
                // Modell fehlt
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.ui_birdnet_modell_nicht_installiert),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = stringResource(R.string.ui_birdnet_v3_onnx_in_assets),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                // Warte auf Erkennung
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Hearing,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.ui_warte_auf_erkennung),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    } else {
        // Detektionsliste
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp)
        ) {
            items(
                items = detections,
                key = { it.id }
            ) { detection ->
                AnimatedVisibility(
                    visible = true,
                    enter = slideInVertically(initialOffsetY = { -it / 2 }) + fadeIn()
                ) {
                    val speciesKey = detection.scientificName.replace(' ', '_')
                    val isInSeason = phenologyRepository?.isInSeason(speciesKey, currentMonth) ?: true
                    val rarityScore = phenologyRepository?.rarity(speciesKey) ?: 0.0f
                    SpeciesCard(
                        detection = detection,
                        onConfirm = onConfirm?.let { callback -> { callback(detection.id) } },
                        onMarkUncertain = onMarkUncertain?.let { callback -> { callback(detection.id) } },
                        onReject = onReject?.let { callback -> { callback(detection.id) } },
                        onCorrect = onCorrect?.let { callback -> { name -> callback(detection.id, name) } },
                        onSaveAsReference = onSaveAsReference?.let { callback -> { callback(detection.id) } },
                        isReference = detection.id in referencedIds,  // T127
                        // T128: manuelle Eintraege ohne Audio-Position koennen nicht geteilt werden
                        onShare = onShare?.takeIf { ch.etasystems.pirol.audio.export.DetectionExcerpt.hasAudioPosition(detection) }
                            ?.let { callback -> { callback(detection.id) } },
                        onPlay = onPlay?.takeIf { ch.etasystems.pirol.audio.export.DetectionExcerpt.hasAudioPosition(detection) }
                            ?.let { callback -> { callback(detection.id) } },
                        isPlaying = playingDetectionId == detection.id,
                        onSelectAlternative = onSelectAlternative?.let { cb ->
                            { candidate -> cb(detection.id, candidate) }
                        },
                        onEditDetection = onEditDetection?.let { cb ->
                            { count, code, note, memoFile -> cb(detection.id, count, code, note, memoFile) }
                        },
                        atlasCodes = atlasCodes,
                        isWatchlisted = watchlistSpecies.contains(
                            ch.etasystems.pirol.ml.SpeciesKey.of(detection.scientificName)
                        ),
                        showTopNCandidates = showTopNCandidates,
                        // T74: Phenology
                        phenologyEnabled = phenologyEnabled,
                        isInSeason = isInSeason,
                        rarityScore = rarityScore,
                        // T69: Voice-Memo
                        voiceMemoRecorder = voiceMemoRecorder,
                        memoAudioPlayer = memoAudioPlayer,
                        onGetMemoTargetFile = onGetMemoTargetFile?.let { cb -> { cb(detection.id) } },
                        onGetMemoTargetUri = onGetMemoTargetUri?.let { cb -> { cb(detection.id) } },
                        onSnackbar = onSnackbar,
                        // T73: Lock-Species
                        onToggleLock = onToggleLock?.let { cb -> { cb(detection.id) } },
                        blinkSignalMs = detectionState.lastHitMs(detection.id),
                        blinkHandledMs = blinkHandled[detection.id] ?: 0L,
                        onBlinkPlayed = { blinkHandled[detection.id] = it },
                        modifier = Modifier.padding(horizontal = 2.dp)
                    )
                }
            }
        }
    }
}

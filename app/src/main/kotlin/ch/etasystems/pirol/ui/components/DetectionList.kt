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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.etasystems.pirol.ml.DetectionListState

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
 * @param onUncertain Callback fuer Unsicher-Markierung (detectionId) (T44)
 * @param modifier Modifier fuer die aeussere Box
 */
@Composable
fun DetectionList(
    detectionState: DetectionListState,
    isModelAvailable: Boolean = true,
    onConfirm: ((String) -> Unit)? = null,
    onReject: ((String) -> Unit)? = null,
    onCorrect: ((String, String) -> Unit)? = null,
    onUncertain: ((String) -> Unit)? = null,
    onSaveAsReference: ((String) -> Unit)? = null,
    watchlistSpecies: Set<String> = emptySet(),
    speciesSuggestions: List<Pair<String, String>> = emptyList(),
    modifier: Modifier = Modifier
) {
    // version lesen => Recomposition bei Aenderung
    @Suppress("UNUSED_VARIABLE")
    val version by detectionState.version

    val detections = detectionState.getDetections()

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
                        text = "BirdNET-Modell nicht installiert",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = "birdnet_v3.onnx in assets/models/ ablegen",
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
                        text = "Warte auf Erkennung\u2026",
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
            verticalArrangement = Arrangement.spacedBy(6.dp),
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
                    SpeciesCard(
                        detection = detection,
                        onConfirm = onConfirm?.let { callback -> { callback(detection.id) } },
                        onReject = onReject?.let { callback -> { callback(detection.id) } },
                        onCorrect = onCorrect?.let { callback -> { name -> callback(detection.id, name) } },
                        onUncertain = onUncertain?.let { callback -> { callback(detection.id) } },
                        onSaveAsReference = onSaveAsReference?.let { callback -> { callback(detection.id) } },
                        isWatchlisted = watchlistSpecies.contains(
                            detection.scientificName.replace(' ', '_')
                        ),
                        speciesSuggestions = speciesSuggestions,
                        modifier = Modifier.padding(horizontal = 2.dp)
                    )
                }
            }
        }
    }
}

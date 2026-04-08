package ch.etasystems.pirol.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ch.etasystems.pirol.ml.EmbeddingDatabase

/**
 * Panel zur Anzeige von Aehnlichkeits-Ergebnissen aus der Embedding-Pipeline.
 *
 * Zeigt die Top-N aehnlichsten Referenz-Aufnahmen aus der lokalen Embedding-DB.
 * Farbcodierung: gruen (>=80%), blau (>=60%), rot (<60%).
 *
 * @param matches Liste der EmbeddingMatch-Ergebnisse (sortiert nach Aehnlichkeit)
 * @param isEmbeddingAvailable Ob die Embedding-Pipeline verfuegbar ist
 * @param modifier Standard Compose Modifier
 */
@Composable
fun SimilarityPanel(
    matches: List<EmbeddingDatabase.EmbeddingMatch>,
    isEmbeddingAvailable: Boolean,
    modifier: Modifier = Modifier
) {
    // Maximal 5 Eintraege anzeigen
    val topMatches = matches.take(5)

    Column(modifier = modifier) {
        // Header-Zeile: Icon + Titel + Badge
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Fingerprint,
                contentDescription = "Aehnlichkeitssuche",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Aehnliche Aufnahmen",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (topMatches.isNotEmpty()) {
                Badge(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Text(text = "${topMatches.size}")
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (!isEmbeddingAvailable) {
            // Embedding nicht verfuegbar
            Text(
                text = "Embedding-Pipeline nicht verfuegbar",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp)
            )
        } else if (topMatches.isEmpty()) {
            // Keine Referenzen vorhanden
            Text(
                text = "Noch keine Referenzen gespeichert",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp)
            )
        } else {
            // Match-Liste
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(
                    items = topMatches,
                    key = { "${it.recordingId}_${it.rank}" }
                ) { match ->
                    SimilarityCard(match = match)
                }
            }
        }
    }
}

/**
 * Einzelne Karte fuer ein Aehnlichkeits-Ergebnis.
 *
 * Zeigt Artname, Similarity-Balken mit Prozentwert, und Recording-ID.
 */
@Composable
private fun SimilarityCard(
    match: EmbeddingDatabase.EmbeddingMatch,
    modifier: Modifier = Modifier
) {
    val similarityPercent = (match.similarity * 100).toInt()

    // Farbcodierung basierend auf Aehnlichkeit
    val barColor = when {
        match.similarity >= 0.8f -> MaterialTheme.colorScheme.tertiary
        match.similarity >= 0.6f -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.error
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // Zeile 1: Artname + Prozentwert
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = match.species,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "$similarityPercent%",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = barColor
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Zeile 2: Horizontaler Aehnlichkeitsbalken
            LinearProgressIndicator(
                progress = { match.similarity.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = barColor,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Zeile 3: Recording-ID
            Text(
                text = match.recordingId,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

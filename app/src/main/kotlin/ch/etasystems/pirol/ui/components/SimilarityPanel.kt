package ch.etasystems.pirol.ui.components

import androidx.compose.foundation.basicMarquee
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
import ch.etasystems.pirol.ml.SpeciesNameResolver
import org.koin.compose.koinInject

/**
 * Panel zur Anzeige von Aehnlichkeits-Ergebnissen aus der Embedding-Pipeline.
 *
 * Modi (via similarLines):
 *   0.0  → nicht anzeigen (auch wenn similarEnabled=false)
 *   0.5  → Laufschrift der letzten 5 Detektionen der Session (Marquee)
 *   1–10 → vertikale Liste mit n Eintraegen, dedupliziert pro Art
 *
 * Namen werden per SpeciesNameResolver in DE aufgeloest (Fallback: wissenschaftlich).
 */
@Composable
fun SimilarityPanel(
    matches: List<EmbeddingDatabase.EmbeddingMatch>,
    isEmbeddingAvailable: Boolean,
    similarEnabled: Boolean,
    similarLines: Float,
    recentSpecies: List<String>,
    modifier: Modifier = Modifier
) {
    if (!similarEnabled || similarLines == 0f || !isEmbeddingAvailable) return

    val resolver: SpeciesNameResolver = koinInject()

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
                text = "Aehnliche Arten",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (similarLines >= 1f && matches.isNotEmpty()) {
                Badge(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Text(text = "${matches.size}")
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        when {
            similarLines == 0.5f -> {
                // Laufschrift-Mode: letzte 5 Detektionen der Session
                val marqueeText = recentSpecies.joinToString(" · ").ifEmpty { "—" }
                Text(
                    text = marqueeText,
                    modifier = Modifier
                        .fillMaxWidth()
                        .basicMarquee(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            matches.isEmpty() -> {
                Text(
                    text = "Noch keine Referenzen gespeichert",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
            else -> {
                // Listen-Mode: deduplizierte Arten
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(
                        items = matches,
                        key = { it.species }
                    ) { match ->
                        SimilarityCard(match = match, resolver = resolver)
                    }
                }
            }
        }
    }
}

@Composable
private fun SimilarityCard(
    match: EmbeddingDatabase.EmbeddingMatch,
    resolver: SpeciesNameResolver,
    modifier: Modifier = Modifier
) {
    val similarityPercent = (match.similarity * 100).toInt()
    val commonName = resolver.resolve(match.species)
    val scientificName = match.species.replace('_', ' ')

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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = commonName,
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

            LinearProgressIndicator(
                progress = { match.similarity.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = barColor,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )

            // Wissenschaftlicher Name als Subtitle (nur wenn verschieden vom commonName)
            if (commonName != scientificName) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = scientificName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

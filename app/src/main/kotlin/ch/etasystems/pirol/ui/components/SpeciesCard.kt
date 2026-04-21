package ch.etasystems.pirol.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.animation.animateContentSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.etasystems.pirol.ml.DetectionResult
import ch.etasystems.pirol.ml.VerificationStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Material 3 Card fuer eine einzelne Artendetektion.
 *
 * Layout:
 * ┌────────────────────────────────────┐
 * │ Amsel                  3×    94%   │  Zeile 1: Name + Zaehler-Badge + Konfidenz
 * │ Turdus merula          ████████░░  │  Zeile 2: Wissensch. Name + Balken
 * │ 08:47:12                           │  Zeile 3: Timestamp
 * │ [✓ Bestaetigt]  [✗]  [✎]          │  Zeile 4: Aktions-Buttons (bei Callbacks)
 * │ ▸ Alternativen (3)                 │  Zeile 5: Aufklappbare Kandidaten (T27)
 * │   Moenchsgrasmuecke         72%    │
 * │   Rotkehlchen               41%    │
 * └────────────────────────────────────┘
 *
 * Status-Feedback:
 * - CONFIRMED: Gruener Rand + Badge "Bestaetigt"
 * - REJECTED: Roter Rand + Badge "Abgelehnt", Karte leicht ausgegraut
 * - CORRECTED: Blauer Rand + Badge mit korrigiertem Artnamen
 * - UNVERIFIED: Kein Badge (Standard)
 *
 * @param detection Detektions-Ergebnis
 * @param onConfirm Callback fuer Bestaetigung (null = keine Buttons anzeigen)
 * @param onReject Callback fuer Ablehnung
 * @param onCorrect Callback mit korrigiertem Artnamen
 * @param modifier Modifier
 */
@Composable
fun SpeciesCard(
    detection: DetectionResult,
    onConfirm: (() -> Unit)? = null,
    onReject: (() -> Unit)? = null,
    onCorrect: ((String) -> Unit)? = null,
    onMarkUncertain: (() -> Unit)? = null,
    onSaveAsReference: (() -> Unit)? = null,
    onCompare: (() -> Unit)? = null,
    onJumpToChunk: (() -> Unit)? = null,
    onPlay: (() -> Unit)? = null,
    isPlayEnabled: Boolean = true,
    playTimeLabel: String? = null,
    onSelectAlternative: ((ch.etasystems.pirol.ml.DetectionCandidate) -> Unit)? = null,
    isWatchlisted: Boolean = false,
    showTopNCandidates: Boolean = true,
    modifier: Modifier = Modifier
) {
    val confidencePercent = (detection.confidence * 100).toInt()
    val confidenceColor = when {
        detection.confidence >= 0.8f -> MaterialTheme.colorScheme.primary
        detection.confidence >= 0.5f -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }
    val timestamp = remember(detection.timestampMs) {
        timeFormat.format(Date(detection.timestampMs))
    }

    // Verifikations-Status: Rand + Badge
    val verificationBorder = when (detection.verificationStatus) {
        VerificationStatus.CONFIRMED -> BorderStroke(2.dp, Color(0xFF4CAF50))
        VerificationStatus.REJECTED -> BorderStroke(2.dp, MaterialTheme.colorScheme.error)
        VerificationStatus.CORRECTED -> BorderStroke(2.dp, Color(0xFF2196F3))
        VerificationStatus.UNCERTAIN -> BorderStroke(2.dp, Color(0xFFFF9800))
        VerificationStatus.REPLACED -> BorderStroke(2.dp, MaterialTheme.colorScheme.error)
        VerificationStatus.UNVERIFIED -> null
    }

    // T33: 5s-Highlight bei kuerzlicher Erkennung
    val isRecent = detection.lastDetectedMs > 0 &&
                   (System.currentTimeMillis() - detection.lastDetectedMs) < 5000L
    val highlightColor by animateColorAsState(
        targetValue = if (isRecent) MaterialTheme.colorScheme.primary
                      else Color.Transparent,
        animationSpec = tween(durationMillis = 1000),
        label = "recentHighlight"
    )

    // REJECTED / REPLACED: leicht ausgegraut
    val cardAlpha = if (detection.verificationStatus == VerificationStatus.REJECTED ||
                        detection.verificationStatus == VerificationStatus.REPLACED) 0.6f else 1f

    // Korrektur-Dialog State
    var showCorrectionDialog by remember { mutableStateOf(false) }
    var correctedName by remember(detection.id) { mutableStateOf(detection.commonName) }

    // Kandidaten aufklappbar (T27) — rememberSaveable stabil bei Re-Sort (T52)
    var candidatesExpanded by rememberSaveable(key = detection.id) { mutableStateOf(false) }

    // Haptisches Feedback (T52: ✎ und Kandidaten-Tap)
    val haptic = LocalHapticFeedback.current

    // T33: Highlight-Border wenn keine Verifikations-Border aktiv
    val effectiveBorder = verificationBorder
        ?: if (isRecent) BorderStroke(2.dp, highlightColor) else null

    Card(
        modifier = modifier
            .fillMaxWidth()
            .alpha(cardAlpha),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = effectiveBorder
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // Zeile 1: Artname + Zaehler-Badge + Prozentwert
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = detection.commonName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    // Detektionszaehler-Badge (T27)
                    if (detection.detectionCount > 1) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Badge(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        ) {
                            Text("${detection.detectionCount}\u00D7")
                        }
                    }
                    // Watchlist-Badge (T20)
                    if (isWatchlisted) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Filled.NotificationsActive,
                            contentDescription = "Watchlist",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Text(
                    text = "$confidencePercent%",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = confidenceColor
                )
            }

            // Zeile 2: Wissenschaftlicher Name + Confidence-Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = detection.scientificName,
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                LinearProgressIndicator(
                    progress = { detection.confidence },
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .padding(start = 8.dp),
                    color = confidenceColor,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }

            // Zeile 3: Zeitstempel + Zeit-Offset-Label + Status-Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = timestamp,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (playTimeLabel != null) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = playTimeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // Status-Badge
                when (detection.verificationStatus) {
                    VerificationStatus.CONFIRMED -> {
                        Text(
                            text = "Bestaetigt",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF4CAF50)
                        )
                    }
                    VerificationStatus.REJECTED -> {
                        Text(
                            text = "Abgelehnt",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    VerificationStatus.CORRECTED -> {
                        Text(
                            text = "\u2192 ${detection.correctedSpecies ?: "?"}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF2196F3)
                        )
                    }
                    VerificationStatus.UNCERTAIN -> {
                        Text(
                            text = "Unsicher",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFF9800)
                        )
                    }
                    VerificationStatus.REPLACED -> {
                        Text(
                            text = "Ersetzt \u2192 ${detection.correctedSpecies ?: "?"}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    VerificationStatus.UNVERIFIED -> { /* kein Badge */ }
                }
            }

            // Zeile 4: Verifikations-Buttons + Play (nur wenn Callbacks vorhanden)
            if (onConfirm != null || onMarkUncertain != null || onReject != null || onCorrect != null || onPlay != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Bestaetigen (T52: FilledIconButton 56dp)
                    if (onConfirm != null) {
                        FilledIconButton(
                            onClick = onConfirm,
                            modifier = Modifier.size(56.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = if (detection.verificationStatus == VerificationStatus.CONFIRMED)
                                    Color(0xFF4CAF50) else MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = if (detection.verificationStatus == VerificationStatus.CONFIRMED)
                                    Color.White else MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = "Bestaetigen",
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    // Unsicher markieren (T44, T52: FilledTonalIconButton 56dp)
                    if (onMarkUncertain != null) {
                        FilledTonalIconButton(
                            onClick = onMarkUncertain,
                            modifier = Modifier.size(56.dp),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = if (detection.verificationStatus == VerificationStatus.UNCERTAIN)
                                    Color(0xFFFF9800) else MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = if (detection.verificationStatus == VerificationStatus.UNCERTAIN)
                                    Color.White else MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Help,
                                contentDescription = "Unsicher",
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    // Ablehnen (T52: FilledTonalIconButton 56dp)
                    if (onReject != null) {
                        FilledTonalIconButton(
                            onClick = onReject,
                            modifier = Modifier.size(56.dp),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = if (detection.verificationStatus == VerificationStatus.REJECTED)
                                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = if (detection.verificationStatus == VerificationStatus.REJECTED)
                                    MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Ablehnen",
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    // Korrigieren (T52: FilledTonalIconButton 56dp + Haptik)
                    if (onCorrect != null) {
                        FilledTonalIconButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                showCorrectionDialog = true
                            },
                            modifier = Modifier.size(56.dp),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = if (detection.verificationStatus == VerificationStatus.CORRECTED)
                                    Color(0xFF2196F3) else MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = if (detection.verificationStatus == VerificationStatus.CORRECTED)
                                    Color.White else MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Edit,
                                contentDescription = "Korrigieren",
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    // "Als Referenz speichern" fuer bestaetigte Detektionen
                    if (onSaveAsReference != null &&
                        (detection.verificationStatus == VerificationStatus.CONFIRMED ||
                         detection.verificationStatus == VerificationStatus.CORRECTED)) {
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(
                            onClick = onSaveAsReference,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.BookmarkAdd,
                                contentDescription = "Als Referenz speichern",
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    // Vergleichen (T24)
                    if (onCompare != null) {
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(
                            onClick = onCompare,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.CompareArrows,
                                contentDescription = "Vergleichen",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    // Zum Chunk springen (T39)
                    if (onJumpToChunk != null) {
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(
                            onClick = onJumpToChunk,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.MyLocation,
                                contentDescription = "Zum Chunk springen",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    // Play-Button (T48): ab chunkStartSec abspielen
                    if (onPlay != null) {
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(
                            onClick = onPlay,
                            enabled = isPlayEnabled,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = "Abspielen",
                                tint = if (isPlayEnabled) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    // Status-Text neben Buttons (kompakt)
                    if (detection.verificationStatus != VerificationStatus.UNVERIFIED) {
                        Spacer(modifier = Modifier.width(8.dp))
                        val statusText = when (detection.verificationStatus) {
                            VerificationStatus.CONFIRMED -> "\u2713"
                            VerificationStatus.REJECTED -> "\u2717"
                            VerificationStatus.CORRECTED -> "\u270E"
                            VerificationStatus.UNCERTAIN -> "?"
                            VerificationStatus.REPLACED -> "\u2717"
                            else -> ""
                        }
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Zeile 5: Aufklappbare Kandidaten-Liste (T27, T52: showTopNCandidates-Guard)
            if (showTopNCandidates && detection.candidates.isNotEmpty()) {
                TextButton(
                    onClick = { candidatesExpanded = !candidatesExpanded },
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    Text(
                        text = if (candidatesExpanded) "Alternativen ausblenden"
                               else if (onSelectAlternative != null) "\u25B8 Alternative waehlen (${detection.candidates.size})"
                               else "Alternativen (${detection.candidates.size})",
                        style = MaterialTheme.typography.labelSmall
                    )
                }

                AnimatedVisibility(visible = candidatesExpanded) {
                    Column(modifier = Modifier.animateContentSize()) {
                        detection.candidates.forEach { candidate ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .padding(start = 16.dp, end = 4.dp)
                                    .then(
                                        if (onSelectAlternative != null)
                                            Modifier.clickable {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                onSelectAlternative(candidate)
                                            }
                                        else Modifier
                                    ),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = candidate.commonName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = candidate.scientificName,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontStyle = FontStyle.Italic,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                                Text(
                                    text = "${(candidate.confidence * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Korrektur-Dialog
    if (showCorrectionDialog && onCorrect != null) {
        AlertDialog(
            onDismissRequest = { showCorrectionDialog = false },
            title = { Text("Art korrigieren") },
            text = {
                OutlinedTextField(
                    value = correctedName,
                    onValueChange = { correctedName = it },
                    label = { Text("Artname") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onCorrect(correctedName)
                    showCorrectionDialog = false
                }) { Text("Korrigieren") }
            },
            dismissButton = {
                TextButton(onClick = { showCorrectionDialog = false }) { Text("Abbrechen") }
            }
        )
    }
}

/** HH:mm:ss Formatter — Thread-safe da nur in Compose-Scope genutzt */
private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

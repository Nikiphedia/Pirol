package ch.etasystems.pirol.ui.components

import androidx.compose.runtime.collectAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Stop
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import android.net.Uri
import ch.etasystems.pirol.audio.AudioPlayer
import ch.etasystems.pirol.audio.VoiceMemoRecorder
import ch.etasystems.pirol.ml.AtlasCode
import ch.etasystems.pirol.ml.DetectionResult
import ch.etasystems.pirol.ml.VerificationStatus
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import ch.etasystems.pirol.R
import androidx.compose.ui.res.stringResource
import ch.etasystems.pirol.ui.AppStrings

/**
 * Material 3 Card fuer eine einzelne Artendetektion.
 *
 * Layout:
 * ┌────────────────────────────────────┐
 * │ Amsel                  3×    94%   │  Zeile 1: Name + Zaehler-Badge + Konfidenz
 * │ Turdus merula          ████████░░  │  Zeile 2: Wissensch. Name + Balken
 * │ 08:47:12                           │  Zeile 3: Timestamp
 * │ [✓ Bestaetigt]  [✗]  [✎]          │  Zeile 4: Aktions-Buttons (bei Callbacks)
 * │   Alternativen (3)                 │  Zeile 5: Kandidaten (T27); volle Karte: Kopf immer sichtbar, antippbar (T224/T230)
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
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun SpeciesCard(
    detection: DetectionResult,
    onConfirm: (() -> Unit)? = null,
    onReject: (() -> Unit)? = null,
    onCorrect: ((String) -> Unit)? = null,
    onMarkUncertain: (() -> Unit)? = null,
    onSaveAsReference: (() -> Unit)? = null,
    isReference: Boolean = false,  // T127: schon als Referenz gespeichert → markiert, Klick entfernt
    onCompare: (() -> Unit)? = null,
    onJumpToChunk: (() -> Unit)? = null,
    onPlay: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,  // T116: Detektion teilen
    isPlayEnabled: Boolean = true,
    isPlaying: Boolean = false,  // T164: Play-Knopf wird zum Stopp-Knopf
    playTimeLabel: String? = null,
    onSelectAlternative: ((ch.etasystems.pirol.ml.DetectionCandidate) -> Unit)? = null,
    onEditDetection: ((count: Int, atlasCode: String?, note: String?, voiceMemoFile: String?) -> Unit)? = null,
    atlasCodes: List<AtlasCode> = emptyList(),
    isWatchlisted: Boolean = false,
    showTopNCandidates: Boolean = true,
    // T74: Phenology
    phenologyEnabled: Boolean = false,
    isInSeason: Boolean = true,
    rarityScore: Float = 0.0f,
    // T69: Voice-Memo
    voiceMemoRecorder: VoiceMemoRecorder? = null,
    memoAudioPlayer: AudioPlayer? = null,
    onGetMemoTargetFile: (() -> File?)? = null,
    onGetMemoTargetUri: (() -> Uri?)? = null,
    onSnackbar: (String) -> Unit = {},
    onToggleLock: (() -> Unit)? = null,
    // T178: Blink-Signal (Zeitpunkt des letzten Treffers), Handled-Marke und Rueckmeldung
    blinkSignalMs: Long = 0L,
    blinkHandledMs: Long = 0L,
    onBlinkPlayed: (Long) -> Unit = {},
    // T224: Name des Hauptmodells fuer die Modellzeile der vollen Karte (null = ohne Namen)
    primaryModelLabel: String? = null,
    // T192: kompakte Einzeiler-Darstellung (Default folgt der Einstellung)
    compact: Boolean = rememberCompactCardSetting(),
    modifier: Modifier = Modifier
) {
    // T178: endliches Doppel-Aufblinken (~1.2 s); der Wert wird nur in der Draw-Phase gelesen
    val blink = remember { Animatable(0f) }
    LaunchedEffect(blinkSignalMs) {
        if (DetectionBlink.shouldBlink(blinkSignalMs, blinkHandledMs, System.currentTimeMillis())) {
            onBlinkPlayed(blinkSignalMs)
            repeat(2) {
                blink.animateTo(1f, tween(durationMillis = 200))
                blink.animateTo(0f, tween(durationMillis = 400))
            }
        }
    }
    val blinkColor = MaterialTheme.colorScheme.primary
    val blinkCorner = with(androidx.compose.ui.platform.LocalDensity.current) { 12.dp.toPx() }

    val confidencePercent = (detection.confidence * 100).toInt()
    val confidenceColor = when {
        detection.confidence >= 0.8f -> MaterialTheme.colorScheme.primary
        detection.confidence >= 0.5f -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }
    // T106: Zeit der letzten Erfassung zeigen (bei Cluster-Merge aktualisiert), nicht die erste
    val timestamp = remember(detection.lastSeenAtMs) {
        timeFormat.format(Date(detection.lastSeenAtMs))
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

    // T220: Namensanzeige (wissenschaftlich/Landessprache) aus dem Resolver
    val nameResolver: ch.etasystems.pirol.ml.SpeciesNameResolver = org.koin.compose.koinInject()
    val sciMode by nameResolver.scientificNamesFlow.collectAsState()

    // REJECTED / REPLACED: leicht ausgegraut
    val cardAlpha = if (detection.verificationStatus == VerificationStatus.REJECTED ||
                        detection.verificationStatus == VerificationStatus.REPLACED) 0.6f else 1f

    // Korrektur-Dialog State
    var showCorrectionDialog by remember { mutableStateOf(false) }
    var correctedName by remember(detection.id) { mutableStateOf(detection.commonName) }

    // Edit-Dialog State (T68)
    var showEditDialog by remember { mutableStateOf(false) }
    val hasCustomData = detection.count > 1 || detection.atlasCode != null || detection.note != null

    // Kandidaten aufklappbar (T27) — rememberSaveable stabil bei Re-Sort (T52)
    var candidatesExpanded by rememberSaveable(key = detection.id) { mutableStateOf(false) }

    // T73: Lock-Kontext-Menue (Long-Press)
    var showLockMenu by remember { mutableStateOf(false) }

    // Haptisches Feedback (T52: ✎ und Kandidaten-Tap)
    val haptic = LocalHapticFeedback.current

    // T182: altes 5-s-Highlight (T33) entfernt, Rueckmeldung ueber das Blinken (T178)
    val effectiveBorder = verificationBorder

    Card(
        modifier = modifier
            .fillMaxWidth()
            .drawWithContent {
                drawContent()
                // Halbtransparente Ueberlagerung: Statusrand und Inhalt bleiben erkennbar
                val a = blink.value
                if (a > 0f) {
                    drawRoundRect(
                        color = blinkColor.copy(alpha = 0.30f * a),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(blinkCorner)
                    )
                }
            }
            .alpha(cardAlpha)
            .combinedClickable(
                onClick = {},
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    showLockMenu = true
                }
            ),
        colors = CardDefaults.cardColors(
            // T73: surfaceVariant-Hintergrund fuer gesperrte Arten (AC2)
            containerColor = if (compact && isPlaying)
                MaterialTheme.colorScheme.primaryContainer
            else if (detection.lockSpecies)
                MaterialTheme.colorScheme.surfaceVariant
            else
                MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = effectiveBorder
    ) {
        // T164: breite Karten (Tablet) zeigen alle Aktionen inline, schmale (Telefon) nutzen das Ueberlauf-Menue
        BoxWithConstraints {
        val inline = maxWidth >= INLINE_ACTIONS_MIN_WIDTH
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            if (compact) {
                CompactRow(
                    detection = detection,
                    inline = inline,
                    confidencePercent = confidencePercent,
                    confidenceColor = confidenceColor,
                    timestamp = timestamp,
                    isWatchlisted = isWatchlisted,
                    isReference = isReference,
                    isPlaying = isPlaying,
                    isPlayEnabled = isPlayEnabled,
                    hasCandidates = showTopNCandidates && detection.candidates.isNotEmpty(),
                    candidatesExpanded = candidatesExpanded,
                    onConfirm = onConfirm,
                    onReject = onReject,
                    onMarkUncertain = onMarkUncertain,
                    onCorrect = onCorrect?.let { { haptic.performHapticFeedback(HapticFeedbackType.LongPress); showCorrectionDialog = true } },
                    onEditDetection = onEditDetection?.let { { showEditDialog = true } },
                    onPlay = onPlay,
                    onSaveAsReference = onSaveAsReference,
                    onCompare = onCompare,
                    onShare = onShare,
                    onToggleLock = onToggleLock,
                    onToggleCandidates = { candidatesExpanded = !candidatesExpanded },
                    hasCustomData = hasCustomData,
                    selectAlternativeEnabled = onSelectAlternative != null
                )
            } else {
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
                        text = if (sciMode) nameResolver.displayName(detection.scientificName) else detection.commonName,
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
                            contentDescription = stringResource(R.string.ui_watchlist),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    // Manuell-Badge (T72): Hand-Icon bei manuell hinzugefuegten Detektionen
                    if (detection.manualEntry) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Filled.PanTool,
                            contentDescription = stringResource(R.string.ui_manuell_hinzugefuegt),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    // Phenology-Badges (T74)
                    if (phenologyEnabled) {
                        if (!isInSeason) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Badge(
                                containerColor = androidx.compose.ui.graphics.Color(0xFFFF9800),
                                contentColor = androidx.compose.ui.graphics.Color.White
                            ) {
                                Text(stringResource(R.string.ui_saison_untypisch), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        if (rarityScore > 0.7f) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Filled.Star,
                                contentDescription = stringResource(R.string.ui_selten),
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                    // Lock-Badge (T73): letzte Position in der Badge-Row
                    if (detection.lockSpecies) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = stringResource(R.string.ui_gesperrt),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
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

            // Zeile 2 (T164): wiss. Name + Zeit-Offset + Zeitstempel + Status in EINER Zeile
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = nameResolver.subtitle(detection.scientificName),
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = if (sciMode) FontStyle.Normal else FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (playTimeLabel != null) {
                    Text(
                        text = playTimeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = timestamp,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Status-Badge
                when (detection.verificationStatus) {
                    VerificationStatus.CONFIRMED -> {
                        Text(
                            text = stringResource(R.string.ui_bestaetigt),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF4CAF50)
                        )
                    }
                    VerificationStatus.REJECTED -> {
                        Text(
                            text = stringResource(R.string.ui_abgelehnt),
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
                            text = stringResource(R.string.ui_unsicher),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFF9800)
                        )
                    }
                    VerificationStatus.REPLACED -> {
                        Text(
                            text = stringResource(R.string.ui_ersetzt, detection.correctedSpecies ?: "?"),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    VerificationStatus.UNVERIFIED -> { /* kein Badge */ }
                }
            }
            // Konfidenz-Balken (duenn)
            LinearProgressIndicator(
                progress = { detection.confidence },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                color = confidenceColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            // T224: Modellzeile "Hauptmodell % · Zweitmodell %" (nur Anzeige, neutrale Farben)
            ModelScoreRow(
                detection = detection,
                primaryModelLabel = primaryModelLabel,
                resolveName = { sci -> nameResolver.displayName(sci) }
            )

            // Zeile 3 (T164): Aktionsleiste in EINER Zeile, 48-dp-Tap-Targets.
            // Auf schmalen Breiten wandern Referenz/Vergleichen/Teilen ins Ueberlauf-Menue. Alternativen stehen
            // bei der vollen Karte nicht im Menue, sondern im Kopf der Kandidatenliste (T224); die kompakte
            // Karte bietet "Alternative waehlen" im "+"-Menue (T192).
            if (onConfirm != null || onMarkUncertain != null || onReject != null || onCorrect != null || onPlay != null || onShare != null) {
                val neutralContainer = Color.Transparent
                val neutralContent = MaterialTheme.colorScheme.onSurfaceVariant
                val status = detection.verificationStatus
                val showReference = onSaveAsReference != null &&
                    (status == VerificationStatus.CONFIRMED || status == VerificationStatus.CORRECTED)
                // T224: volle Karte zeigt die Kandidaten immer → kein Umschalter im Menue
                var overflowOpen by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (inline) Arrangement.spacedBy(4.dp) else Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Bestaetigen (T78: bei CONFIRMED zu Undo wechseln)
                    if (onConfirm != null) {
                        val isConfirmed = status == VerificationStatus.CONFIRMED
                        CardActionButton(
                            onClick = if (isConfirmed) (onMarkUncertain ?: onConfirm) else onConfirm,
                            icon = if (isConfirmed) Icons.AutoMirrored.Filled.Undo else Icons.Filled.Check,
                            description = if (isConfirmed) AppStrings.get(R.string.ui_bestaetigung_rueckgaengig) else AppStrings.get(R.string.ui_bestaetigen),
                            containerColor = if (isConfirmed) Color(0xFF4CAF50) else neutralContainer,
                            contentColor = if (isConfirmed) Color.White else neutralContent
                        )
                    }

                    // Unsicher markieren (T44)
                    if (onMarkUncertain != null) {
                        val active = status == VerificationStatus.UNCERTAIN
                        CardActionButton(
                            onClick = onMarkUncertain,
                            icon = Icons.AutoMirrored.Filled.Help,
                            description = AppStrings.get(R.string.ui_unsicher),
                            containerColor = if (active) Color(0xFFFF9800) else neutralContainer,
                            contentColor = if (active) Color.White else neutralContent
                        )
                    }

                    // Ablehnen
                    if (onReject != null) {
                        val active = status == VerificationStatus.REJECTED
                        CardActionButton(
                            onClick = onReject,
                            icon = Icons.Filled.Close,
                            description = AppStrings.get(R.string.ui_ablehnen),
                            containerColor = if (active) MaterialTheme.colorScheme.error else neutralContainer,
                            contentColor = if (active) MaterialTheme.colorScheme.onError else neutralContent
                        )
                    }

                    // Korrigieren (inkl. Haptik)
                    if (onCorrect != null) {
                        val active = status == VerificationStatus.CORRECTED
                        CardActionButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                showCorrectionDialog = true
                            },
                            icon = Icons.Filled.Edit,
                            description = AppStrings.get(R.string.ui_korrigieren),
                            containerColor = if (active) Color(0xFF2196F3) else neutralContainer,
                            contentColor = if (active) Color.White else neutralContent
                        )
                    }

                    // Detail-Eingaben (T68): Stueckzahl, Atlascode, Bemerkung
                    if (onEditDetection != null) {
                        CardActionButton(
                            onClick = { showEditDialog = true },
                            icon = Icons.AutoMirrored.Filled.List,
                            description = AppStrings.get(R.string.ui_detail_eingaben),
                            containerColor = if (hasCustomData) MaterialTheme.colorScheme.primaryContainer else neutralContainer,
                            contentColor = if (hasCustomData) MaterialTheme.colorScheme.onPrimaryContainer else neutralContent
                        )
                    }
                    // T244: Alarm-Knopf (dauerhaft oder 24 h)
                    AlarmButton(detection)
                    // Referenz / Vergleichen: inline nur bei breiter Karte, sonst im Menue
                    if (inline && showReference) {
                        CardActionButton(
                            onClick = onSaveAsReference!!,
                            icon = if (isReference) Icons.Filled.Bookmark else Icons.Filled.BookmarkAdd,
                            description = if (isReference) AppStrings.get(R.string.ui_referenz_entfernen) else AppStrings.get(R.string.ui_als_referenz_speichern),
                            containerColor = if (isReference) MaterialTheme.colorScheme.primary else neutralContainer,
                            contentColor = if (isReference) MaterialTheme.colorScheme.onPrimary else neutralContent
                        )
                    }
                    if (inline && onCompare != null) {
                        CardActionButton(
                            onClick = onCompare,
                            icon = Icons.AutoMirrored.Filled.CompareArrows,
                            description = AppStrings.get(R.string.ui_vergleichen),
                            containerColor = neutralContainer,
                            contentColor = neutralContent
                        )
                    }

                    // Play/Stopp (T48/T164): Detektions-Ausschnitt anhoeren
                    if (onPlay != null) {
                        CardActionButton(
                            onClick = onPlay,
                            icon = if (isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                            description = if (isPlaying) AppStrings.get(R.string.ui_wiedergabe_stoppen) else AppStrings.get(R.string.common_play),
                            containerColor = if (isPlaying) MaterialTheme.colorScheme.primaryContainer else neutralContainer,
                            contentColor = if (isPlaying) MaterialTheme.colorScheme.onPrimaryContainer else neutralContent,
                            enabled = isPlayEnabled
                        )
                    }
                    // T116: Teilen (inline nur bei breiter Karte)
                    if (inline && onShare != null) {
                        CardActionButton(
                            onClick = onShare,
                            icon = Icons.Filled.Share,
                            description = AppStrings.get(R.string.ui_teilen),
                            containerColor = neutralContainer,
                            contentColor = neutralContent
                        )
                    }
                    // Ueberlauf-Menue (nur schmale Karte, nur wenn es etwas zu zeigen gibt)
                    if (!inline && (showReference || onCompare != null || onShare != null)) {
                        Box {
                            CardActionButton(
                                onClick = { overflowOpen = true },
                                icon = Icons.Filled.MoreVert,
                                description = AppStrings.get(R.string.ui_mehr_aktionen),
                                containerColor = neutralContainer,
                                contentColor = neutralContent
                            )
                            DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                                if (showReference) {
                                    DropdownMenuItem(
                                        text = { Text(if (isReference) stringResource(R.string.ui_referenz_entfernen) else stringResource(R.string.ui_als_referenz_speichern)) },
                                        leadingIcon = { Icon(if (isReference) Icons.Filled.Bookmark else Icons.Filled.BookmarkAdd, null) },
                                        onClick = { overflowOpen = false; onSaveAsReference!!() }
                                    )
                                }
                                if (onCompare != null) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.ui_vergleichen)) },
                                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.CompareArrows, null) },
                                        onClick = { overflowOpen = false; onCompare() }
                                    )
                                }
                                if (onShare != null) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.ui_teilen)) },
                                        leadingIcon = { Icon(Icons.Filled.Share, null) },
                                        onClick = { overflowOpen = false; onShare() }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            } // Ende Vollkarte (T192)

            // Zeile 5: Aufklappbare Kandidaten-Liste (T27, T52: showTopNCandidates-Guard)
            if (showTopNCandidates && detection.candidates.isNotEmpty()) {
                // T230: volle Karte: antippbarer Kopf klappt die Kandidaten auf; kompakte Karte klappt ueber das Menue auf (T192)
                if (!compact) {
                    Text(
                        text = if (onSelectAlternative != null) stringResource(R.string.ui_alternative_waehlen, detection.candidates.size)
                               else stringResource(R.string.ui_alternativen, detection.candidates.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable { candidatesExpanded = !candidatesExpanded }
                            .padding(top = 2.dp, start = 4.dp)
                            .wrapContentHeight(Alignment.CenterVertically)
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
                                        text = if (sciMode) nameResolver.displayName(candidate.scientificName) else candidate.commonName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = nameResolver.subtitle(candidate.scientificName),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontStyle = if (sciMode) FontStyle.Normal else FontStyle.Italic,
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

            // T73: Long-Press Kontext-Menue — Sperren / Entsperren (AC1)
            if (onToggleLock != null) {
                DropdownMenu(
                    expanded = showLockMenu,
                    onDismissRequest = { showLockMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(if (detection.lockSpecies) stringResource(R.string.ui_entsperren) else stringResource(R.string.ui_sperren)) },
                        leadingIcon = {
                            Icon(
                                imageVector = if (detection.lockSpecies) Icons.Filled.LockOpen
                                              else Icons.Filled.Lock,
                                contentDescription = null
                            )
                        },
                        onClick = {
                            showLockMenu = false
                            onToggleLock()
                        }
                    )
                }
        }
        }
        }
    }

    // Edit-Dialog (T68/T69): Stückzahl, Atlascode, Bemerkung + Sprach-Memo
    if (showEditDialog && onEditDetection != null) {
        val memoTargetFile = remember { onGetMemoTargetFile?.invoke() }
        val memoTargetUri = remember { onGetMemoTargetUri?.invoke() }
        EditDetectionDialog(
            detection = detection,
            atlasCodes = atlasCodes,
            recorder = voiceMemoRecorder,
            memoAudioPlayer = memoAudioPlayer,
            memoTargetFile = memoTargetFile,
            memoTargetUri = memoTargetUri,
            onDismiss = { showEditDialog = false },
            onSave = { count, atlasCode, note, memoFile ->
                onEditDetection(count, atlasCode, note, memoFile)
                showEditDialog = false
            },
            onSnackbar = onSnackbar,
        )
    }

    // Korrektur-Dialog
    if (showCorrectionDialog && onCorrect != null) {
        AlertDialog(
            onDismissRequest = { showCorrectionDialog = false },
            title = { Text(stringResource(R.string.ui_art_korrigieren)) },
            text = {
                OutlinedTextField(
                    value = correctedName,
                    onValueChange = { correctedName = it },
                    label = { Text(stringResource(R.string.ui_artname)) },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onCorrect(correctedName)
                    showCorrectionDialog = false
                }) { Text(stringResource(R.string.ui_korrigieren)) }
            },
            dismissButton = {
                TextButton(onClick = { showCorrectionDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

/** T192/T229: beobachtet die Einstellung "kompakte Artkarte" (sofort wirksam). */
@Composable
private fun rememberCompactCardSetting(): Boolean {
    val prefs: ch.etasystems.pirol.data.AppPreferences = org.koin.compose.koinInject()
    return prefs.compactCardSetting.flow.collectAsState().value
}

/**
 * T192: Kompakt-Zeile: Name (+ Zaehler/Badges), Konfidenz, Zeit (nur breit), [Bestaetigen] [Ablehnen] [+].
 * Das "+" oeffnet ein DropdownMenu direkt am Knopf. Begruendung: das Menue erscheint neben dem
 * Daumen, bleibt im Kontext der Karte (Liste/Spektrogramm sichtbar) und braucht keinen
 * zusaetzlichen Wisch; ein BottomSheet waere fuer 5-9 kurze Eintraege zu schwer.
 */
@Composable
private fun CompactRow(
    detection: DetectionResult,
    inline: Boolean,
    confidencePercent: Int,
    confidenceColor: Color,
    timestamp: String,
    isWatchlisted: Boolean,
    isReference: Boolean,
    isPlaying: Boolean,
    isPlayEnabled: Boolean,
    hasCandidates: Boolean,
    candidatesExpanded: Boolean,
    onConfirm: (() -> Unit)?,
    onReject: (() -> Unit)?,
    onMarkUncertain: (() -> Unit)?,
    onCorrect: (() -> Unit)?,
    onEditDetection: (() -> Unit)?,
    onPlay: (() -> Unit)?,
    onSaveAsReference: (() -> Unit)?,
    onCompare: (() -> Unit)?,
    onShare: (() -> Unit)?,
    onToggleLock: (() -> Unit)?,
    onToggleCandidates: () -> Unit,
    hasCustomData: Boolean,
    selectAlternativeEnabled: Boolean
) {
    val status = detection.verificationStatus
    val neutralContent = MaterialTheme.colorScheme.onSurfaceVariant
    val showReference = onSaveAsReference != null &&
        (status == VerificationStatus.CONFIRMED || status == VerificationStatus.CORRECTED)
    val entries = CompactCardMenu.entries(
        hasMarkUncertain = onMarkUncertain != null,
        hasCorrect = onCorrect != null,
        hasEditDetection = onEditDetection != null,
        hasPlay = onPlay != null,
        hasCandidates = hasCandidates,
        showReference = showReference,
        hasCompare = onCompare != null,
        hasShare = onShare != null,
        hasToggleLock = onToggleLock != null
    )
    var menuOpen by remember { mutableStateOf(false) }
    val statusColor = when (status) {
        VerificationStatus.CONFIRMED -> Color(0xFF4CAF50)
        VerificationStatus.UNCERTAIN -> Color(0xFFFF9800)
        VerificationStatus.CORRECTED -> Color(0xFF2196F3)
        VerificationStatus.REJECTED, VerificationStatus.REPLACED -> MaterialTheme.colorScheme.error
        VerificationStatus.UNVERIFIED -> null
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (status == VerificationStatus.CORRECTED && detection.correctedSpecies != null)
                        detection.correctedSpecies!! else detection.commonName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = statusColor ?: MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (detection.detectionCount > 1) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Badge(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ) { Text("${detection.detectionCount}×") }
                }
                if (isWatchlisted) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(Icons.Filled.NotificationsActive, stringResource(R.string.ui_watchlist),
                        tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                }
                if (detection.lockSpecies) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(Icons.Filled.Lock, stringResource(R.string.ui_gesperrt),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                }
                if (isPlaying) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(Icons.Filled.PlayArrow, null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                }
            }
            // Kleine Zusatzzeile: Konfidenz, bei breiter Karte auch die Zeit
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("$confidencePercent%", style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold, color = confidenceColor)
                if (inline) {
                    Text(timestamp, style = MaterialTheme.typography.labelSmall, color = neutralContent)
                }
            }
        }
        if (onConfirm != null) {
            val isConfirmed = status == VerificationStatus.CONFIRMED
            CardActionButton(
                onClick = onConfirm,
                icon = Icons.Filled.Check,
                description = AppStrings.get(R.string.ui_bestaetigen),
                containerColor = if (isConfirmed) Color(0xFF4CAF50) else Color.Transparent,
                contentColor = if (isConfirmed) Color.White else neutralContent
            )
        }
        if (onReject != null) {
            val active = status == VerificationStatus.REJECTED
            CardActionButton(
                onClick = onReject,
                icon = Icons.Filled.Close,
                description = AppStrings.get(R.string.ui_ablehnen),
                containerColor = if (active) MaterialTheme.colorScheme.error else Color.Transparent,
                contentColor = if (active) MaterialTheme.colorScheme.onError else neutralContent
            )
        }
        if (entries.isNotEmpty()) {
            Box {
                CardActionButton(
                    onClick = { menuOpen = true },
                    icon = Icons.Filled.Add,
                    description = AppStrings.get(R.string.ui_mehr_aktionen),
                    containerColor = if (hasCustomData || candidatesExpanded) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                    contentColor = neutralContent
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    entries.forEach { entry ->
                        val (label, icon, action) = when (entry) {
                            CompactMenuEntry.PLAY_TOGGLE -> Triple(
                                if (isPlaying) stringResource(R.string.ui_wiedergabe_stoppen) else stringResource(R.string.common_play),
                                if (isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow, onPlay)
                            CompactMenuEntry.UNCERTAIN -> Triple(stringResource(R.string.ui_unsicher), Icons.AutoMirrored.Filled.Help, onMarkUncertain)
                            CompactMenuEntry.CORRECT -> Triple(stringResource(R.string.ui_korrigieren), Icons.Filled.Edit, onCorrect)
                            CompactMenuEntry.DETAILS -> Triple(stringResource(R.string.ui_detail_eingaben), Icons.AutoMirrored.Filled.List, onEditDetection)
                            CompactMenuEntry.ALTERNATIVES -> Triple(
                                if (candidatesExpanded) stringResource(R.string.ui_alternativen_ausblenden)
                                else if (selectAlternativeEnabled) stringResource(R.string.ui_alternative_waehlen, detection.candidates.size)
                                else stringResource(R.string.ui_alternativen, detection.candidates.size),
                                Icons.AutoMirrored.Filled.CompareArrows, onToggleCandidates)
                            CompactMenuEntry.REFERENCE -> Triple(
                                if (isReference) stringResource(R.string.ui_referenz_entfernen) else stringResource(R.string.ui_als_referenz_speichern),
                                if (isReference) Icons.Filled.Bookmark else Icons.Filled.BookmarkAdd, onSaveAsReference)
                            CompactMenuEntry.COMPARE -> Triple(stringResource(R.string.ui_vergleichen), Icons.AutoMirrored.Filled.CompareArrows, onCompare)
                            CompactMenuEntry.SHARE -> Triple(stringResource(R.string.ui_teilen), Icons.Filled.Share, onShare)
                            CompactMenuEntry.LOCK -> Triple(
                                if (detection.lockSpecies) stringResource(R.string.ui_entsperren) else stringResource(R.string.ui_sperren),
                                if (detection.lockSpecies) Icons.Filled.LockOpen else Icons.Filled.Lock, onToggleLock)
                        }
                        DropdownMenuItem(
                            text = { Text(label) },
                            leadingIcon = { Icon(icon, null) },
                            enabled = entry != CompactMenuEntry.PLAY_TOGGLE || isPlayEnabled,
                            modifier = Modifier.heightIn(min = 48.dp),
                            onClick = { menuOpen = false; action?.invoke() }
                        )
                    }
                }
            }
        }
    }
}

/** HH:mm:ss Formatter — Thread-safe da nur in Compose-Scope genutzt */
private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

/** Ab dieser Kartenbreite stehen alle Aktionen inline, darunter wandern seltene ins Menue (T164). */
private val INLINE_ACTIONS_MIN_WIDTH = 460.dp

/**
 * Aktions-Button der Karte (T164): 48 dp Tap-Target, 24-dp-Icon, ohne Hintergrund (nur aktive
 * Zustaende sind farbig hinterlegt).
 */
@Composable
private fun CardActionButton(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    containerColor: Color,
    contentColor: Color,
    enabled: Boolean = true
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(48.dp),
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = containerColor,
            contentColor = contentColor
        )
    ) {
        Icon(imageVector = icon, contentDescription = description, modifier = Modifier.size(24.dp))
    }
}

/**
 * T224: Modellzeile der vollen Artkarte: "Hauptmodell 82 % · Zweitmodell 64 %".
 * Zweitmodell unbekannte Art → "nicht pruefbar"; andere Top-Art → dezent umrandet (neutrale Farbe,
 * keine Verifikationsfarben). Ohne Zweitmodell-Ergebnis nur das Hauptmodell.
 */
@Composable
private fun ModelScoreRow(
    detection: DetectionResult,
    primaryModelLabel: String?,
    resolveName: (String) -> String
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val style = MaterialTheme.typography.labelSmall
    val primaryPercent = (detection.confidence * 100).toInt()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = if (primaryModelLabel != null) stringResource(R.string.card_model_score, primaryModelLabel, primaryPercent)
                   else "$primaryPercent %",
            style = style,
            color = muted,
            maxLines = 1
        )
        val status = detection.secondaryStatus ?: return@Row
        val secLabel = ch.etasystems.pirol.ml.SecondaryCheck.modelLabel(detection.secondaryModelId) ?: "?"
        Text(text = "·", style = style, color = muted)
        when (status) {
            ch.etasystems.pirol.ml.SecondaryCheck.STATUS_NOT_CHECKABLE -> Text(
                text = stringResource(R.string.card_secondary_not_checkable, secLabel),
                style = style,
                color = muted,
                fontStyle = FontStyle.Italic,
                maxLines = 1
            )
            else -> {
                val secPercent = ((detection.secondaryConfidence ?: 0f) * 100).toInt()
                Text(
                    text = stringResource(R.string.card_model_score, secLabel, secPercent),
                    style = style,
                    color = muted,
                    maxLines = 1
                )
                val top = detection.secondaryTopSpecies
                if (status == ch.etasystems.pirol.ml.SecondaryCheck.STATUS_DISAGREES && top != null) {
                    val topPercent = ((detection.secondaryTopConfidence ?: 0f) * 100).toInt()
                    val cd = stringResource(R.string.card_secondary_disagrees_cd)
                    Text(
                        text = stringResource(R.string.card_secondary_disagrees, resolveName(top), topPercent),
                        style = style,
                        color = muted,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp)
                            .semantics { contentDescription = cd }
                    )
                }
            }
        }
    }
}

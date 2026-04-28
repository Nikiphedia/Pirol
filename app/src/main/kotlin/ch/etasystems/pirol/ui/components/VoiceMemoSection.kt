package ch.etasystems.pirol.ui.components

import android.net.Uri
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ch.etasystems.pirol.audio.AudioPlayer
import ch.etasystems.pirol.audio.VoiceMemoRecorder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Sprach-Memo UI-Sektion fuer den EditDetectionDialog (T69).
 *
 * Drei Zustaende:
 * 1. Kein Memo — Button "Memo aufnehmen"
 * 2. Aufnahme laeuft — Wellenform + Timer + Stop-Button
 * 3. Memo vorhanden — Play/Pause + Dauer + Loeschen
 *
 * @param detectionId ID der Detektion (fuer Dateiname)
 * @param initialMemoRelPath Bestehender relativer Pfad (detection.voiceMemoFile) oder null
 * @param recorder VoiceMemoRecorder aus dem ViewModel
 * @param audioPlayer AudioPlayer fuer Memo-Wiedergabe
 * @param targetFile Ziel-File (File-Modus) — null im reinen SAF-Modus
 * @param targetUri Ziel-URI (SAF-Modus) — null im File-Modus
 * @param onMemoChanged Callback wenn Memo aufgenommen oder geloescht wurde (relativer Pfad oder null)
 * @param onSnackbar Callback fuer Fehlermeldungen / Cap-Warnung
 */
@Composable
fun VoiceMemoSection(
    detectionId: String,
    initialMemoRelPath: String?,
    recorder: VoiceMemoRecorder,
    audioPlayer: AudioPlayer,
    targetFile: File?,
    targetUri: Uri?,
    onMemoChanged: (relativePath: String?) -> Unit,
    onSnackbar: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var currentRelPath by remember { mutableStateOf(initialMemoRelPath) }
    val isRecording by recorder.isRecording.collectAsState()
    val amplitude by recorder.amplitude.collectAsState()
    val playerState by audioPlayer.state.collectAsState()

    // Timer fuer Aufnahme-Anzeige
    var elapsedSec by remember { mutableIntStateOf(0) }
    LaunchedEffect(isRecording) {
        if (isRecording) {
            elapsedSec = 0
            while (isRecording) {
                delay(1000)
                elapsedSec++
            }
        }
    }

    // 60s-Cap-Signal
    LaunchedEffect(recorder) {
        recorder.capReached.collect {
            onSnackbar("Memo auf 60s gekürzt")
            currentRelPath = "memos/$detectionId.wav"
            onMemoChanged(currentRelPath)
        }
    }

    // Cleanup Player bei Verlassen der Composition
    DisposableEffect(audioPlayer) {
        onDispose { audioPlayer.stop() }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        when {
            // ── Zustand 2: Aufnahme laeuft ───────────────────────────
            isRecording -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Wellenform-Anzeige (animierte Balken basierend auf Amplitude)
                    WaveformBars(amplitude = amplitude, modifier = Modifier.weight(1f))
                    // Timer
                    val min = elapsedSec / 60
                    val sec = elapsedSec % 60
                    Text(
                        text = "%02d:%02d".format(min, sec),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // Stop-Button
                    IconButton(
                        onClick = {
                            scope.launch {
                                recorder.stopRecording().onSuccess {
                                    currentRelPath = "memos/$detectionId.wav"
                                    onMemoChanged(currentRelPath)
                                }.onFailure {
                                    onSnackbar("Aufnahme konnte nicht gespeichert werden")
                                }
                            }
                        }
                    ) {
                        Icon(
                            Icons.Filled.Stop,
                            contentDescription = "Aufnahme stoppen",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // ── Zustand 3: Memo vorhanden ────────────────────────────
            currentRelPath != null -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Play/Pause
                    val isPlaying = playerState == AudioPlayer.PlaybackState.PLAYING
                    IconButton(
                        onClick = {
                            if (isPlaying) {
                                audioPlayer.togglePause()
                            } else {
                                if (targetFile != null) {
                                    audioPlayer.play(targetFile)
                                } else if (targetUri != null) {
                                    audioPlayer.play(targetUri)
                                }
                            }
                        }
                    ) {
                        Icon(
                            if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlaying) "Pausieren" else "Abspielen",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = "Sprach-Memo",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    // Loeschen
                    IconButton(
                        onClick = {
                            audioPlayer.stop()
                            // Datei loeschen
                            try { targetFile?.delete() } catch (_: Exception) {}
                            currentRelPath = null
                            onMemoChanged(null)
                        }
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Memo löschen",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ── Zustand 1: Kein Memo ─────────────────────────────────
            else -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = {
                            scope.launch {
                                val result = if (targetFile != null) {
                                    recorder.startRecording(targetFile)
                                } else if (targetUri != null) {
                                    recorder.startRecordingSaf(context, targetUri)
                                } else {
                                    Result.failure(IllegalStateException("Kein Ziel konfiguriert"))
                                }
                                result.onFailure { onSnackbar("Aufnahme fehlgeschlagen: ${it.message}") }
                            }
                        },
                        enabled = targetFile != null || targetUri != null
                    ) {
                        Icon(
                            Icons.Filled.Mic,
                            contentDescription = "Memo aufnehmen",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = "Memo aufnehmen",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Animierte Wellenform-Balken basierend auf Amplitude (0..1).
 * 5 Balken mit leicht versetzter Animations-Phase.
 */
@Composable
private fun WaveformBars(amplitude: Float, modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "waveform")
    val barCount = 5
    Row(
        modifier = modifier.height(32.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        repeat(barCount) { i ->
            val phase = i * 0.2f
            val animFactor by infiniteTransition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = 400,
                        delayMillis = (phase * 400).toInt(),
                        easing = LinearEasing
                    ),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "bar$i"
            )
            val barHeight = (amplitude * animFactor * 28).coerceAtLeast(4f)
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(barHeight.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                        shape = RoundedCornerShape(2.dp)
                    )
            )
        }
    }
}

package ch.etasystems.pirol.ui.analysis

import android.net.Uri
import ch.etasystems.pirol.audio.AudioPlayer
import ch.etasystems.pirol.data.repository.ReferenceEntry
import ch.etasystems.pirol.data.repository.SessionMetadata
import ch.etasystems.pirol.ml.DetectionResult
import ch.etasystems.pirol.ui.components.SpectrogramState
import java.io.File

/**
 * Zusammenfassung einer Session fuer die Listen-Ansicht.
 */
data class SessionSummary(
    val sessionDir: File,
    val metadata: SessionMetadata,
    val detectionCount: Int,
    val verifiedCount: Int,
    val recordingSizeBytes: Long = 0L  // T52: WAV-Dateigrösse (0 = nicht ermittelbar)
)

/** Was gerade abgespielt wird im Vergleichs-Modus */
enum class PlayingSource { DETECTION, REFERENCE }

/**
 * UI-State fuer den Analyse-Tab (Session-Browser + Detail + Vergleich).
 */
data class AnalysisUiState(
    // Session-Liste
    val sessions: List<SessionSummary> = emptyList(),
    val isLoading: Boolean = false,
    // T202: Artsuche ueber alle Sessions; sessionSpecies = wiss. Namen je Detektion pro sessionId (Cache bis Neuladen)
    val sessionSearch: String = "",
    val sessionSpecies: Map<String, List<String>> = emptyMap(),

    // T180: Mehrfachauswahl (Schluessel = sessionId); selectionMode = Auswahlmodus aktiv
    val selectionMode: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
    /** T180: laufende Stapel-Aktion (Loeschen/Verschieben); null = keine */
    val batchRunning: Boolean = false,
    val batchIsMove: Boolean = false,
    val batchProgress: ch.etasystems.pirol.data.repository.MoveProgress? = null,
    /** T180: Ergebnis der letzten Stapel-Aktion (einmalig als Snackbar) */
    val batchMessage: String? = null,

    // Ausgewaehlte Session (null = Liste anzeigen)
    val selectedSession: SessionSummary? = null,
    val detections: List<DetectionResult> = emptyList(),
    val recordingFile: File? = null,
    /** T131: true = Aufnahme vorhanden (File ODER SAF-URI) */
    val hasRecording: Boolean = false,
    // T62: SAF-URI-Fallback (z.B. content:// auf SD-Karte). Wird bevorzugt wenn nicht null.
    val recordingUri: Uri? = null,
    val recordingDurationSec: Float = 0f,

    // Sonogramm
    val spectrogramState: SpectrogramState = SpectrogramState(),
    // T107: Ladeanzeige / Fehlertext fuer das Session-Sonogramm (statt stillem Abbruch)
    val sonogramLoading: Boolean = false,
    val sonogramError: String? = null,

    // T116: Wiedergabe-Zoom — Sonogramm zeigt nur den Ausschnitt der abgespielten Detektion
    val zoomRange: ch.etasystems.pirol.audio.export.DetectionExcerpt.Range? = null,
    val zoomSpectrogramState: SpectrogramState = SpectrogramState(),
    /** T205: PCM des sichtbaren Ausschnitts (Basis fuer Spektrum und Oszillogramm), null = kein Ausschnitt */
    val zoomPcm: ch.etasystems.pirol.audio.export.ExcerptWav.Pcm? = null,
    // T116: Detektion teilen (Dialog)
    val shareRequest: ch.etasystems.pirol.data.export.DetectionShareRequest? = null,

    // Scroll-Signal (T39: nach Jump zum Chunk soll Sonogramm sichtbar sein)
    val scrollToTop: Boolean = false,

    // Audio-Playback
    val playbackState: AudioPlayer.PlaybackState = AudioPlayer.PlaybackState.IDLE,
    val playingChunkIndex: Int? = null,
    // T107: abgespielte Detektion (Hervorhebung) + Abspielposition in Sekunden (Zeiger im Sonogramm)
    val playingDetectionId: String? = null,
    /** T173: Schluessel der laufenden Wiedergabe (PlaybackController), null = keine */
    val playingKey: String? = null,
    val playbackPositionSec: Float? = null,
    /** T203: letzte bekannte Abspielposition (bleibt nach Stopp, fuer "+ Art") */
    val lastPlaybackPositionSec: Float? = null,
    /** T210: per Tippen gesetzte Startposition (Wiedergabe gestoppt); Play startet dort, null = Anfang */
    val pendingStartSec: Float? = null,
    // T167: abgespieltes Einzelvorkommen (Startsekunde in der Session), null = Hauptfenster/keines
    val playingOccurrenceStartSec: Float? = null,
    // T167: Wanduhr-Zeiten absorbierter Fenster je Detektions-ID (aus CLUSTER_MERGED)
    val mergeTimestamps: Map<String, List<DetectionOccurrences.MergeInfo>> = emptyMap(),
    // T264: Session-Memos (memos.jsonl) und Zeitraeume der Art-Memos (MEMO_RECORDED) fuer den Block "Memos"
    val sessionMemos: List<ch.etasystems.pirol.data.repository.SessionMemo> = emptyList(),
    val artMemoSpans: Map<String, ch.etasystems.pirol.audio.export.MemoExclusion.WallSpan> = emptyMap(),
    // T62: MediaPlayer-Fehler fuer Snackbar/Toast-Feedback (AC7).
    val playerError: String? = null,

    // Vergleichs-Modus (Ebene 3)
    val isCompareMode: Boolean = false,
    val compareDetection: DetectionResult? = null,
    // T237: Startsekunde des Detektionsfensters in der Session-Aufnahme (statt fester 3-s-Chunk-Nummer, Fenster sind modellabhaengig)
    val compareDetectionStartSec: Float? = null,
    val detectionSpectrogramState: SpectrogramState = SpectrogramState(),
    val referenceEntries: List<ReferenceEntry> = emptyList(),
    val selectedReference: ReferenceEntry? = null,
    val referenceSpectrogramState: SpectrogramState = SpectrogramState(),
    val playingSource: PlayingSource? = null,

    // Raven-Export Feedback
    val ravenExportMessage: String? = null,
    // T194: geparste Raven-Tabelle (null = Dialog zu)
    val ravenTable: RavenTable? = null,

    // IO-Fehler bei Verifikations-Persistierung (AC5)
    val saveErrorMessage: String? = null,
    // T237: Hinweis mit Undo-Aktion (z.B. nach "Alternative waehlen"); wird von der UI angezeigt und verbraucht
    val undoOffer: UndoOffer? = null,

    // T221: Rescan-Varianten der geoeffneten Session; activeVariant = angezeigte Variante (null = Original)
    val variants: List<ch.etasystems.pirol.rescan.RescanInfo> = emptyList(),
    val activeVariant: String? = null,
    /** T221: Fortschritt 0..1 des laufenden Rescans (null = keiner) und dessen Session */
    val rescanProgress: Float? = null,
    val rescanSessionDir: File? = null,
    /** T309: Stelle in der Import-Warteschlange (0-basiert) und Laenge */
    val rescanBatchIndex: Int = 0,
    val rescanBatchSize: Int = 1,
    // T255: Fremdaudio-Import: Dialog offen, Vorpruefung (null = laeuft), Rescan-Dialog nach dem Import anbieten
    val audioImportOpen: Boolean = false,
    val audioImportProbes: List<ch.etasystems.pirol.data.importer.AudioProbe>? = null,
    val rescanRequest: Boolean = false,
    /** T261: Fortschritt 0..1 des Mensch-Scans vor "Speichern unter" (null = keiner) */
    val humanScanProgress: Float? = null,
    /** T263: Export wartet auf den Entscheid "Modell ohne Mensch-Klasse" (null = keiner) */
    val pendingMaskChoice: PendingMaskExport? = null
)

/** T263: Welcher Export auf den Entscheid wartet (Dialog Scan mit BirdNET / ohne Ausblenden / Abbrechen) */
sealed class PendingMaskExport {
    data class Zip(val target: android.net.Uri) : PendingMaskExport()
    data class Folder(val treeUri: android.net.Uri) : PendingMaskExport()
    data object Upload : PendingMaskExport()
}

/** T237: Snackbar-Hinweis mit Rueckgaengig-Knopf (Analyse-Tab). */
data class UndoOffer(val message: String, val actionLabel: String, val onUndo: () -> Unit)

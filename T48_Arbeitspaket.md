# T48 — Analyse-Tab Timeline + Zeit-Offset-Navigation

## Voraussetzung

**T46 ist abgeschlossen und reviewed.** `audio/recording.wav` existiert pro neuer Session.
**T47 ist optional vorher** — beeinflusst T48 nicht (anderer Code-Bereich).

---

## Ziel

Der Analyse-Tab zeigt **eine durchgehende `recording.wav` mit klickbaren Detektions-Markern** statt der bisherigen Chunk-Liste. Jede Detektion in der Liste hat einen Play-Button, der die `recording.wav` ab `chunkStartSec` startet. Optional: eine schmale Waveform-/Marker-Leiste über dem Detektions-Listenkopf.

Der bisherige Jump-to-Chunk (T39) wird zu **Jump-to-Time-Offset**.

---

## Projekt-Kontext

- **Package:** `ch.etasystems.pirol`
- **Pfad:** `D:\80002\PIROL`
- **Build-Befehl:** `./gradlew compileDebugKotlin`

**Relevante Dateien:**

- `app/src/main/kotlin/ch/etasystems/pirol/ui/analysis/AnalysisScreen.kt`
- `app/src/main/kotlin/ch/etasystems/pirol/ui/analysis/AnalysisViewModel.kt`
- `app/src/main/kotlin/ch/etasystems/pirol/audio/AudioPlayer.kt` (oder analoger Player — bestehende Klasse, Name aus T46 verifizieren)
- `app/src/main/kotlin/ch/etasystems/pirol/data/repository/SessionManager.kt` (`getRecordingFile()`)
- `app/src/main/kotlin/ch/etasystems/pirol/ml/DetectionResult.kt` (`chunkStartSec`, `chunkEndSec`)

---

## Voraussetzungs-Check (vor Beginn lesen)

In T46 wurden die Play-Buttons im Analyse-Tab **deaktiviert oder abgespeckt**. T48 reaktiviert sie auf Basis der einzelnen `recording.wav`. Lies erst `Handover_T46.md` — dort steht der genaue Zustand.

`AudioPlayer` muss **Seek-Position in Sekunden** unterstützen. Falls nicht: vor T48 die Seek-API ergänzen (siehe Änderung 2).

---

## Änderung 1 — `AnalysisViewModel.kt`

### 1a. State-Erweiterung

Im Analyse-State neu:

```kotlin
val recordingFile: File?,        // null fuer alte Chunk-Sessions
val recordingDurationSec: Float  // 0f wenn nicht ladbar
```

In der Session-Lade-Logik:
```kotlin
val recording = sessionManager.getRecordingFile(sessionDir)
val durationSec = recording?.let { readWavDurationSec(it) } ?: 0f
```

`readWavDurationSec(file)` als private Helper: liest Bytes 24-27 (sampleRate) und 40-43 (dataSize) aus dem WAV-Header, berechnet `dataSize / (sampleRate * 2)` als Float.

### 1b. `playDetection(detection)`

```kotlin
fun playDetection(detection: DetectionResult) {
    val file = _state.value.recordingFile ?: return
    audioPlayer.playFromOffset(file, startSec = detection.chunkStartSec, endSec = detection.chunkEndSec)
}
```

Stop-Logik: nach `endSec - startSec` Millisekunden auto-stop, ODER beim Klick auf einen anderen Marker den vorherigen Player stoppen. Pragmatisch: bestehende `AudioPlayer.stop()`-Logik wiederverwenden.

### 1c. Alte Chunk-Logik entfernen

Wenn `AnalysisViewModel` noch Chunk-Index-Felder (`currentChunkIndex` o.ä.) hat → **entfernen**. Wenn der State eine Chunk-Liste exposed → durch `recordingFile` ersetzen.

---

## Änderung 2 — `AudioPlayer.kt` (falls nötig)

Falls `AudioPlayer` noch keine Offset-Wiedergabe unterstützt:

```kotlin
/**
 * Spielt einen Zeitabschnitt einer WAV-Datei ab.
 * @param startSec Startposition in Sekunden
 * @param endSec Endposition in Sekunden (oder null = bis Dateiende)
 */
fun playFromOffset(file: File, startSec: Float, endSec: Float? = null)
```

Implementierung: Bytes-Offset = `44 + (startSec * sampleRate * 2).toInt()`, dann Wiedergabe via `AudioTrack` mit den Samples ab Offset bis `endSec` oder Datei-Ende.

Falls `AudioPlayer` schon eine Offset-API hat (anderer Methodenname): den bestehenden Namen verwenden, **keine** Doppelung.

---

## Änderung 3 — `AnalysisScreen.kt`

### 3a. Detektions-Liste: Play-Buttons aktiv

Pro Detektions-Card einen Play-Button:
```kotlin
IconButton(onClick = { viewModel.playDetection(detection) }) {
    Icon(Icons.Default.PlayArrow, "Abspielen ab ${detection.chunkStartSec}s")
}
```

Disabled wenn `state.recordingFile == null` (alte Chunk-Sessions).

### 3b. Zeit-Display pro Detektion

Anstelle des bisherigen "Chunk N"-Labels:
```kotlin
Text(
    text = formatSecondsAsMinSec(detection.chunkStartSec),
    style = MaterialTheme.typography.labelSmall
)
```

Helper:
```kotlin
private fun formatSecondsAsMinSec(s: Float): String {
    val total = s.toInt()
    val mm = total / 60
    val ss = total % 60
    return String.format("%02d:%02d", mm, ss)
}
```

### 3c. (Optional, niedrige Priorität) Timeline-Marker-Leiste

Über der Detektionsliste eine `Box` mit Höhe ~24dp und Breite `fillMaxWidth`, die Detektionen als vertikale Striche an `chunkStartSec / recordingDurationSec` Position zeichnet. Klick auf einen Strich = `playDetection(detectionAtThatPosition)`.

**Wenn das die Komplexität sprengt: weglassen** und im Handover als T49-Kandidat notieren. Liste mit Play-Buttons reicht für AC.

---

## Änderung 4 — Hinweis-Banner für alte Chunk-Sessions

Wenn `state.recordingFile == null` und die Session **vor T46** angelegt wurde (erkennbar daran dass `audio/`-Ordner `chunk_*.wav`-Dateien enthält):

```kotlin
Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
    Text(
        text = "Diese Session wurde im alten Chunk-Format gespeichert. " +
               "Audio-Wiedergabe nicht verfuegbar — Detektions-Daten sind weiterhin sichtbar.",
        modifier = Modifier.padding(12.dp)
    )
}
```

---

## Scope-Ausschluss

- **Keine** Spektrogramm-Anzeige der ganzen Aufnahme (nur einfache Marker-Leiste oder ganz weglassen)
- **Keine** Migration alter Chunk-Sessions zu `recording.wav`
- **Kein** Scrubbing/Slider zum freien Suchen in der Aufnahme (nur Sprung zu Detektionen)
- **Keine** Loop-/Wiederholungs-Funktion
- **Kein** Runtime-Test

---

## Testanforderung

```bash
./gradlew compileDebugKotlin
```

→ **BUILD SUCCESSFUL**, null Compile-Errors.

---

## Acceptance Criteria

1. Neue Sessions (mit `recording.wav`): Klick auf Play-Button einer Detektion startet Wiedergabe ab `chunkStartSec`
2. Wiedergabe stoppt automatisch nach `chunkEndSec - chunkStartSec` Sekunden (oder spätestens bei Klick auf andere Detektion)
3. Zeit-Label pro Detektion zeigt `MM:SS` statt Chunk-Index
4. Alte Chunk-Sessions (ohne `recording.wav`): Banner sichtbar, Play-Buttons disabled, kein Crash
5. `compileDebugKotlin` BUILD SUCCESSFUL
6. Bei langem Aufnahme-Start-Offset (z.B. Detektion bei Sekunde 1234.567): Wiedergabe startet korrekt ohne Audio-Glitch am Anfang

---

## Handover

`Handover_T48.md` im Projekt-Root mit:
1. Zusammenfassung
2. Geänderte Dateien
3. Build-Status
4. Ob die Marker-Leiste (Änderung 3c) implementiert wurde oder als T49 notiert
5. Verhalten bei alten Chunk-Sessions (Banner-Text, Disabled-State)
6. Beobachtungen zu `AudioPlayer`-Seek-Genauigkeit (Bytes-Offset muss auf Sample-Boundary aligned sein — bei 16-bit Mono = 2 Bytes — sonst Knack-Geräusch)

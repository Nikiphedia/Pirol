# T49 — Session-relative Zeitstempel in Detektionen

## Voraussetzung

**T46, T47, T48 sind abgeschlossen und reviewed.** Build-Stand `main` ist grün.

---

## Ziel

`DetectionResult.chunkStartSec` und `chunkEndSec` enthalten **Sekunden seit Session-Start** (= Sekunden in `recording.wav`), nicht wie bisher Werte relativ zum 3s-Inference-Fenster (0.0–3.0).

Ohne diese Korrektur:
- T47 Raven-Export liefert alle Begin Times im Bereich 0–3 → unbrauchbar in Raven/Audacity
- T48 Play-Buttons springen immer auf Sekunde 0–3 von `recording.wav`, nicht zur echten Detektions-Stelle

---

## Projekt-Kontext

- **Package:** `ch.etasystems.pirol`
- **Pfad:** `D:\80002\PIROL`
- **Build-Befehl:** `./gradlew compileDebugKotlin`

**Relevante Dateien:**

- `app/src/main/kotlin/ch/etasystems/pirol/ml/DetectionResult.kt` (Feld-Kommentar)
- `app/src/main/kotlin/ch/etasystems/pirol/ml/InferenceWorker.kt` (Zeile ~137-138 — **nicht ändern**, bleibt Session-agnostisch)
- `app/src/main/kotlin/ch/etasystems/pirol/ui/live/LiveViewModel.kt` (Callback ab Zeile ~126, WAV-Write ab Zeile ~177)
- `app/src/main/kotlin/ch/etasystems/pirol/data/repository/SessionManager.kt` (Offset-Getter neu)
- `app/src/main/kotlin/ch/etasystems/pirol/data/repository/WavWriter.kt` (`StreamingWavWriter.sampleCount` ist bereits public — T46)

---

## Architektur-Entscheidung

**Der `InferenceWorker` bleibt Session-agnostisch** — er weiss nichts von `recording.wav` oder Session-Start. Er füllt `chunkStartSec`/`chunkEndSec` weiterhin mit den Classifier-Werten (0.0/3.0 aus BirdNET).

**Die Umrechnung auf Session-relative Zeiten passiert im `LiveViewModel`-Callback**, direkt bevor die Detektionen in `DetectionListState` und `detections.jsonl` fließen. Quelle der Wahrheit für den Offset: `StreamingWavWriter.sampleCount` — das ist die einzige Stelle die **exakt** weiss wie viele Samples bereits in `recording.wav` stehen (inkl. Preroll).

---

## Änderung 1 — `DetectionResult.kt`

Feld-Kommentare korrigieren:

```kotlin
val chunkStartSec: Float,    // Sekunden seit Session-Start (Position in recording.wav)
val chunkEndSec: Float,      // Sekunden seit Session-Start (Ende des Inference-Fensters)
```

**Kein** Feldname-Rename — wäre ein unnötig grosser Refactor. Der alte Name bleibt, nur die Semantik wird korrekt dokumentiert.

---

## Änderung 2 — `SessionManager.kt`

Neue public Methode:

```kotlin
/**
 * Aktueller Schreib-Offset in recording.wav in Sekunden.
 *
 * Ist gleich der Sekunden-Position ab der der naechste appendAudioSamples()-Block
 * in der WAV-Datei landen wird. Enthaelt bereits Preroll-Samples wenn diese
 * ueber appendAudioSamples() geschrieben wurden.
 *
 * @return Offset in Sekunden, oder 0f wenn keine Session aktiv
 */
fun getCurrentRecordingOffsetSec(): Float {
    val writer = recordingWriter ?: return 0f
    val sampleRate = activeMetadata?.sampleRate ?: return 0f
    return writer.sampleCount.toFloat() / sampleRate.toFloat()
}
```

**Nicht `suspend`** — reiner Read-Access auf ein Long-Feld, kein I/O.

**Thread-Safety:** `sampleCount` wird in `appendAudioSamples()` (auf `Dispatchers.IO`) inkrementiert. Der Getter wird aus dem Inference-Callback (non-IO) aufgerufen. Long-Read ist nicht atomar — aber da wir `toFloat()` und nachfolgende Berechnung machen, und ein Race um wenige Samples (< 1ms Fehler) irrelevant ist, reicht es. **Keine Synchronisation nötig.**

---

## Änderung 3 — `LiveViewModel.kt`

### 3a. Umrechnung im Callback

**Vor** `detectionListState.addDetections(geoDetections)` (ca. Zeile 159) einfügen:

```kotlin
// Session-relative Zeitstempel setzen (T49)
// chunkStartSec/chunkEndSec vom InferenceWorker sind relativ zum 3s-Fenster (0.0-3.0).
// Hier werden sie auf Sekunden seit Session-Start umgerechnet.
val sessionRelativeDetections = if (sessionManager.isActive && audioBlock != null) {
    val startSec = sessionManager.getCurrentRecordingOffsetSec()
    val durationSec = audioBlock.samples.size.toFloat() / audioBlock.sampleRate.toFloat()
    geoDetections.map { det ->
        det.copy(
            chunkStartSec = startSec,
            chunkEndSec = startSec + durationSec
        )
    }
} else {
    geoDetections
}
```

Danach alle nachfolgenden Verwendungen von `geoDetections` → `sessionRelativeDetections`:
- `detectionListState.addDetections(sessionRelativeDetections)`
- `for (detection in sessionRelativeDetections)` (Watchlist-Loop)
- `sessionManager.appendDetections(sessionRelativeDetections)`
- `topDetection = sessionRelativeDetections.maxByOrNull { ... }` im Embedding-Arm

### 3b. Reihenfolge beachten

Der Getter `getCurrentRecordingOffsetSec()` muss aufgerufen werden **bevor** der Audio-Block ge-appended wird. Im aktuellen Code (Zeile ~181) passiert das Append in einem `launch(Dispatchers.IO)` — der Callback-Code läuft erst nachdem `sessionRelativeDetections` berechnet ist, aber das Append könnte theoretisch durch einen vorherigen pendenten Coroutine-Job bereits weiter sein.

**Pragmatische Lösung:** Die Offset-Berechnung ist per Block konsistent, solange der InferenceWorker sequentiell ist (ein Block nach dem anderen) und die WAV-Writes in genau der gleichen Reihenfolge dispatched werden wie die Callbacks. Das ist aktuell der Fall (T46-Handover: Preroll sequentiell via `pendingPrerollSamples`). 

Im Zweifel: das Append auch synchron `runBlocking` oder in der gleichen Coroutine machen — aber das ist **Scope-Erweiterung** und soll nur als T50 gemacht werden falls in T49 nachweisbare Drifts auftreten.

---

## Änderung 4 — Sanity-Check Logging

Im Callback nach der Umrechnung ein einmaliges Log für den ersten detektierten Block pro Session:

```kotlin
if (Log.isLoggable(TAG, Log.DEBUG)) {
    Log.d(TAG, "Detection-Offset: chunkStartSec=${sessionRelativeDetections.firstOrNull()?.chunkStartSec}")
}
```

Nur Debug-Info — hilft bei der manuellen Verifikation am Device.

---

## Scope-Ausschluss

- **Kein** Rename der Felder `chunkStartSec`/`chunkEndSec` (Kommentar-Fix reicht)
- **Keine** Marker-Leiste (T50)
- **Kein** Cleanup von `compareDetectionChunkIndex` aus `AnalysisUiState` (T50)
- **Keine** Migration bestehender `detections.jsonl` (Felder bleiben wie sie waren — Daten sind für Analyse vor T49 sowieso chunk-relativ und im neuen Analyse-Tab nicht abspielbar)
- **Kein** Schutz gegen Race-Condition zwischen Offset-Read und Append (siehe 3b — pragmatisch akzeptabel)
- **Keine** Änderung an `InferenceWorker.kt`
- **Kein** Runtime-Test

---

## Testanforderung

```bash
./gradlew compileDebugKotlin
```

→ **BUILD SUCCESSFUL**, null Compile-Errors.

---

## Acceptance Criteria

1. Nach 2 Minuten Aufnahme: eine Detektion bei z.B. Sekunde 67 hat `chunkStartSec ≈ 67.0` in `detections.jsonl` (nicht 0.0–3.0)
2. Raven-Export (T47) einer neuen Session zeigt Begin Times über die gesamte Aufnahme-Dauer verteilt (nicht alle im Bereich 0–3)
3. Play-Button (T48) einer Detektion bei Sekunde 67 startet Wiedergabe von `recording.wav` ab Sekunde 67
4. **Preroll-Integration:** eine Detektion im ersten 3s-Inference-Fenster nach Aufnahmestart hat `chunkStartSec ≈ prerollDurationSec` (nicht 0), weil der Preroll bereits in `recording.wav` steht
5. `compileDebugKotlin` BUILD SUCCESSFUL
6. Keine Änderungen an `InferenceWorker.kt`
7. `getCurrentRecordingOffsetSec()` gibt 0f zurück wenn keine Session aktiv — kein Crash, kein NPE

---

## Handover

`Handover_T49.md` im Projekt-Root mit:
1. Zusammenfassung
2. Geänderte Dateien
3. Build-Status
4. Beispiel: Offset-Werte aus einem Debug-Log (aus `Log.d(TAG, "Detection-Offset: ...")`) — falls ein Device-Test möglich war; sonst "kein Device-Test durchgeführt"
5. Beobachtungen insbesondere zu:
   - Race-Condition Offset vs. Append (in 3b beschrieben) — ist sie in der Praxis aufgetaucht?
   - Alte `detections.jsonl` mit chunk-relativen Werten — kompilieren sie weiterhin im Analyse-Tab ohne Crash?

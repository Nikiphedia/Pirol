# T46 — Eine `recording.wav` pro Session (Merlin-Modell)

## Voraussetzung

**T45 ist abgeschlossen und reviewed.** Build-Stand `main` ist grün.

---

## Ziel

Pro Aufnahme-Session entsteht **genau eine** durchgehende WAV-Datei `audio/recording.wav`. Der Preroll-Buffer wird beim Start an den Anfang dieser Datei geschrieben — danach werden alle Inference-Audioblöcke kontinuierlich angehängt. Beim Stop wird der WAV-Header mit der finalen `dataSize` aktualisiert.

**Keine** `chunk_NNN.wav`-Dateien mehr.

---

## Projekt-Kontext

- **Package:** `ch.etasystems.pirol`
- **Pfad:** `D:\80002\PIROL`
- **Build-Befehl:** `./gradlew compileDebugKotlin`
- **Sprache:** Kotlin 2.1.0, 16-bit PCM Mono WAV

**Relevante Dateien — alle vor Änderung vollständig lesen:**

- `app/src/main/kotlin/ch/etasystems/pirol/data/repository/WavWriter.kt`
- `app/src/main/kotlin/ch/etasystems/pirol/data/repository/SessionManager.kt`
- `app/src/main/kotlin/ch/etasystems/pirol/ui/live/LiveViewModel.kt`
- `app/src/main/kotlin/ch/etasystems/pirol/data/repository/ReferenceRepository.kt` (nur lesen — wird angepasst)

---

## Änderung 1 — `WavWriter.kt` zu Streaming-Writer ausbauen

Bestehendes `object WavWriter { fun write(...) }` bleibt **erhalten** (für andere Aufrufer wie `ReferenceRepository.cropToReference`).

**Neue Klasse `StreamingWavWriter`** im selben File:

```kotlin
/**
 * Streaming-WAV-Writer: Header beim Open mit Platzhaltern, Samples per append(),
 * beim close() werden RIFF-fileSize und data-dataSize im Header aktualisiert.
 *
 * 16-bit PCM Mono. Nicht thread-safe — Aufrufer muss serialisieren.
 */
class StreamingWavWriter(
    private val file: File,
    private val sampleRate: Int
) {
    private var raf: RandomAccessFile? = null
    private var dataBytesWritten: Long = 0L
    private val numChannels = 1
    private val bitsPerSample = 16

    /** Oeffnet die Datei und schreibt einen 44-Byte-Header mit Platzhaltern. */
    fun open() { /* RandomAccessFile("rw"), Header mit dataSize=0, fileSize=36 */ }

    /** Haengt Samples an. open() muss vorher aufgerufen worden sein. */
    fun append(samples: ShortArray) { /* LE-Bytes anhaengen, dataBytesWritten += samples.size * 2 */ }

    /** Aktualisiert RIFF-fileSize (Offset 4) und data-dataSize (Offset 40), schliesst Datei. */
    fun close() { /* seek(4)/writeIntLE(36 + dataBytesWritten); seek(40)/writeIntLE(dataBytesWritten); raf.close() */ }

    /** Aktuell geschriebene Sample-Anzahl (fuer Logging). */
    val sampleCount: Long get() = dataBytesWritten / 2
}
```

**Hinweis Endianness:** WAV ist Little-Endian. `RandomAccessFile.writeInt` schreibt Big-Endian — manuell bytewise schreiben oder via `ByteBuffer.allocate(4).order(LITTLE_ENDIAN).putInt(v).array()` und `raf.write(...)`.

**Imports:** `java.io.RandomAccessFile`, `java.nio.ByteBuffer`, `java.nio.ByteOrder`.

---

## Änderung 2 — `SessionManager.kt`

### 2a. Felder

```kotlin
private var recordingWriter: StreamingWavWriter? = null
```

`chunkCounter` **entfernen** (samt allen Verwendungen). `totalChunks` aus `SessionMetadata` (siehe 2d) entfernen — oder neu als `totalRecordedSeconds: Float` belegen.

### 2b. Lifecycle

`startSession()` öffnet die Recording-Datei am Ende:

```kotlin
val recordingFile = File(audioDir, "recording.wav")
recordingWriter = StreamingWavWriter(recordingFile, sampleRate).also { it.open() }
```

`endSession()` schließt sie zuerst:

```kotlin
try { recordingWriter?.close() } catch (e: Exception) { Log.e(TAG, "WAV close failed", e) }
recordingWriter = null
```

### 2c. `writeAudioChunk()` → `appendAudioSamples()`

Methode umbenennen und intern ändern:

```kotlin
suspend fun appendAudioSamples(samples: ShortArray) = withContext(Dispatchers.IO) {
    recordingWriter?.append(samples)
}
```

Der `sampleRate`-Parameter entfällt — er ist über die Session festgelegt. Aufrufer in `LiveViewModel` entsprechend anpassen (Änderung 3).

### 2d. `SessionMetadata`

`totalChunks: Int` ersetzen durch `totalRecordedSamples: Long` (in `endSession()` aus `recordingWriter?.sampleCount` setzen, **bevor** geschlossen wird). Falls Felder anderswo genutzt werden (Analyse-Tab, Sessions-Liste): dort auf neues Feld umstellen oder `totalChunks` als deprecated `@Transient` Default 0 lassen — entscheide bei Build-Errors pragmatisch.

### 2e. `getAudioChunks(sessionDir)` → `getRecordingFile(sessionDir)`

```kotlin
fun getRecordingFile(sessionDir: File): File? {
    val f = File(sessionDir, "audio/recording.wav")
    return if (f.exists()) f else null
}
```

Alte `getAudioChunks()` **entfernen**. Aufrufer (`AnalysisViewModel`, `ReferenceRepository`) müssen angepasst werden — siehe Änderung 4 + 5.

---

## Änderung 3 — `LiveViewModel.kt`

**Zwei Aufrufstellen** von `sessionManager.writeAudioChunk(...)`:

### 3a. Zeile ~182 (laufende Inference-Blöcke)

```kotlin
viewModelScope.launch(Dispatchers.IO) {
    sessionManager.appendAudioSamples(shortSamples)
}
```

### 3b. Zeile ~348 (Preroll bei Aufnahmestart)

```kotlin
if (prerollSamples.isNotEmpty()) {
    viewModelScope.launch(Dispatchers.IO) {
        sessionManager.appendAudioSamples(prerollSamples)
    }
    // Sonogramm-Block bleibt unveraendert
    ...
}
```

**Wichtig — Reihenfolge:** `sessionManager.startSession(...)` muss vor dem ersten `appendAudioSamples(prerollSamples)` aufgerufen worden sein, damit `recordingWriter` offen ist. Falls die Reihenfolge im aktuellen Code anders ist, sicherstellen dass `startSession` vor dem Preroll-Append liegt (sonst werden Preroll-Samples verworfen).

---

## Änderung 4 — `AnalysisViewModel.kt`

`sessionManager.getAudioChunks(summary.sessionDir)` ersetzen durch `sessionManager.getRecordingFile(summary.sessionDir)`. Datentyp ändert sich von `List<File>` zu `File?`.

**Konsequenz Jump-to-Chunk (T39):** Die bestehende Chunk-Index-Navigation funktioniert nicht mehr. Für T46 reicht: **einfach die ganze `recording.wav` abspielen** (kein Sprung zu einzelner Detektion). Die echte Timeline-Navigation kommt in **T48**.

Konkret — wenn der Code z.B. `chunks[detection.chunkIndex]` aufruft:
- Stattdessen einmal `getRecordingFile(...)` lesen und in `AudioPlayer` laden
- Play-Buttons bei einzelnen Detektionen vorerst **deaktivieren** (oder versteckt — minimaler Eingriff)

`AnalysisScreen.kt` entsprechend anpassen (UI darf temporär weniger können — wird in T48 wieder voll).

---

## Änderung 5 — `ReferenceRepository.kt` (Zeile 64)

```kotlin
val sourceWav = File(sessionDir, "audio/chunk_${String.format("%03d", chunkIndex)}.wav")
```

ersetzen durch:

```kotlin
val sourceWav = File(sessionDir, "audio/recording.wav")
```

Falls `cropToReference()` aktuell den ganzen Chunk übernimmt: Die Detektion hat `chunkStartSec` (relative Position, derzeit 0) — für T46 reicht es, **die ganze Datei** als Referenz zu kopieren oder die Funktion vorerst **deaktiviert** zu lassen (return null). T48/T49 schneidet dann mit Zeit-Offset.

Wähle den minimalsten Eingriff der den Build grün hält. Im Handover dokumentieren was du gewählt hast.

---

## Scope-Ausschluss

- **Keine** Migration alter `chunk_NNN.wav`-Sessions (bleiben wie sie sind, werden im Analyse-Tab nicht mehr abspielbar — Hinweis im Handover)
- **Keine** Timeline-UI im Analyse-Tab (kommt in T48)
- **Kein** Raven-Exporter (kommt in T47)
- **Kein** Runtime-Test auf Device

---

## Testanforderung

```bash
./gradlew compileDebugKotlin
```

→ **BUILD SUCCESSFUL**, null Compile-Errors.

Zusätzlich prüfen:
- `chunkCounter`, `chunk_NNN`, `getAudioChunks` kommen in keiner `.kt`-Datei mehr vor (außer in History/Comments)
- `WavWriter.write(...)` als statische Methode bleibt erhalten (für `ReferenceRepository`)
- `SessionMetadata` ist rückwärtskompatibel deserialisierbar (alte session.json mit `totalChunks` darf nicht crashen — `ignoreUnknownKeys = true` ist bereits gesetzt)

---

## Acceptance Criteria

1. Neue Session → `audio/recording.wav` existiert und ist eine valide WAV-Datei (in Audacity öffnen ginge — nicht Teil des Tests, aber Datei-Header korrekt: `RIFF`, `WAVE`, `fmt `, `data`)
2. Preroll-Samples sind die ersten Samples in `recording.wav`
3. Beim `endSession()` enthält der WAV-Header die korrekte `dataSize` (= Bytes aller geschriebenen Samples)
4. `recording.wav` enthält Preroll + alle Inference-Blöcke in chronologischer Reihenfolge ohne Lücken oder Duplikate
5. Sessions-Ordner enthält **keine** `chunk_NNN.wav`-Dateien mehr (nur `recording.wav`)
6. `compileDebugKotlin` BUILD SUCCESSFUL
7. Kein Crash beim Start einer neuen Session, kein Crash beim End einer Session
8. Alte Sessions (mit Chunks) crashen den Sessions-Listing nicht — werden geladen, sind im Analyse-Tab evtl. ohne Audio (dokumentiert im Handover)

---

## Handover

`Handover_T46.md` im Projekt-Root mit:
1. Zusammenfassung was geändert wurde
2. Vollständige Liste geänderter Dateien
3. Build-Status
4. Was mit `ReferenceRepository.cropToReference()` und `AnalysisScreen` Play-Buttons gemacht wurde (deaktiviert / abgespeckt / wie genau)
5. Beobachtungen für T47 (Raven-Exporter braucht `recording.wav` + Detektions-Zeitoffsets) und T48 (Timeline-UI)

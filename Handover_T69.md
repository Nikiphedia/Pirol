# Handover T69 — Block C3: Sprach-Memo (WAV-Recorder + Player)

**Branch:** `T69-voice-memo`  
**Commit:** 67b57c0  
**Build-Tag nach Master-Merge:** `V0.0.6.T69`

---

## Erledigt

### Neue Dateien

**`audio/VoiceMemoRecorder.kt`**
- AudioRecord mit 16 kHz, Mono, 16-bit PCM, MediaRecorder.AudioSource.MIC
- Schreibt via bestehende `StreamingWavWriter` (File-Modus: `forFile()`, SAF-Modus: `forSaf()`)
- `StateFlow<Boolean> isRecording`, `StateFlow<Float> amplitude` (0..1, pro Puffer-Iteration)
- `SharedFlow<Unit> capReached` — emittiert wenn 60s-Cap greift
- `suspend fun startRecording(File)` + `startRecordingSaf(Context, Uri)` + `stopRecording()`
- Recording-Loop auf eigenem Daemon-Thread (blockiert RecordingService NICHT)
- `@Synchronized stopInternal()` — Thread-safe

**`ui/components/VoiceMemoSection.kt`**
- 3 UI-Zustände: Kein Memo / Aufnahme läuft / Memo vorhanden
- Animierte Wellenform-Balken (5 Bars, `infiniteRepeatable`, amplitude-basiert)
- Timer mm:ss während Aufnahme, LaunchedEffect auf `isRecording`
- Play/Pause via bestehendem `AudioPlayer`, Löschen inkl. File-Delete
- `onMemoChanged: (String?) -> Unit` — Dialog informiert bei Änderung
- `onSnackbar: (String) -> Unit` — für Fehler + 60s-Cap-Signal

**`test/audio/VoiceMemoRecorderTest.kt`**
- Testet `StreamingWavWriter` mit denselben Parametern wie VoiceMemoRecorder
- `wavHeader_16k_mono_16bit`: RIFF, WAVE, numChannels=1, sampleRate=16000, bitsPerSample=16, dataSize, RIFF-fileSize
- `wavHeader_emptyRecording`: 44-Byte-Header bei 0 Samples
- `byteRateCalculation`: byteRate=32000, blockAlign=2

### Geänderte Dateien

**`ml/DetectionResult.kt`**
- `val newValue: String? = null` zu `VerificationEvent` hinzugefügt (backward-compat, @Serializable)
- Wird für `ACTION_MEMO_RECORDED` genutzt (Pfad-Transport)

**`data/repository/SessionManager.kt`**
- `fun memoFile(sessionDir: File, detectionId: String): File` — erstellt memos/-Verzeichnis
- `fun memoDocFile(sessionDoc: DocumentFile, detectionId: String): DocumentFile?` — SAF-Variante
- `val currentSessionDir: File? get() = activeSessionDir` — public getter
- `val currentSessionDoc: DocumentFile? get() = activeSessionDoc` — public getter

**`ui/components/EditDetectionDialog.kt`**
- Neue Parameter: `recorder`, `memoAudioPlayer`, `memoTargetFile`, `memoTargetUri`, `onSnackbar`
- `onSave` erweitert auf `(count, atlasCode, note, voiceMemoFile: String?) -> Unit`
- `hasChanged` prüft jetzt auch `currentMemoRelPath != detection.voiceMemoFile`
- `VoiceMemoSection` unter Bemerkung eingebunden (mit `HorizontalDivider` + Label)

**`ui/components/SpeciesCard.kt`**
- `onEditDetection` Signatur: `+voiceMemoFile: String?`
- Neue optionale Parameter: `voiceMemoRecorder`, `memoAudioPlayer`, `onGetMemoTargetFile`, `onGetMemoTargetUri`, `onSnackbar`
- `remember { onGetMemoTargetFile?.invoke() }` — einmalige Berechnung beim Dialog-Open

**`ui/components/DetectionList.kt`**
- `onEditDetection` Signatur: `+voiceMemoFile: String?`
- Neue optionale Parameter wie SpeciesCard, Weitergabe an SpeciesCard mit `detection.id`-Binding

**`ui/live/LiveScreen.kt`**
- Alle 3 `DetectionList`-Aufrufe: `+voiceMemoRecorder`, `+memoAudioPlayer`, `+onGetMemoTargetFile`, `+onGetMemoTargetUri`
- `onEditDetection` Lambda: `+memoFile` Parameter

**`ui/analysis/AnalysisScreen.kt`**
- `SpeciesCard`-Aufruf: analog LiveScreen, `onGetMemoTargetFile/Uri` mit `detection.id` pre-bound

**`ui/live/LiveViewModel.kt`**
- `+context: android.content.Context` Konstruktor-Parameter
- `val voiceMemoRecorder = VoiceMemoRecorder()` — Feld, kein Koin-Singleton
- `val memoAudioPlayer by lazy { AudioPlayer(context) }` — separater Player
- `editDetection` +`voiceMemoFile: String?`: kopiert Feld, `MEMO_RECORDED`/`MEMO_DELETED` Events
- `fun getMemoTargetFile(detectionId)` + `getMemoTargetUri(detectionId)`
- `init { voiceMemoRecorder.capReached.collect { _snackbarChannel.trySend(SnackbarEvent("Memo auf 60s gekürzt")) } }`
- `onCleared()`: `voiceMemoRecorder.release()` + `memoAudioPlayer.release()`

**`ui/analysis/AnalysisViewModel.kt`**
- Analog zu LiveViewModel
- 60s-Cap → `_uiState.update { it.copy(saveErrorMessage = "Memo auf 60s gekürzt") }` (bestehender Kanal)
- `getMemoTargetFile(detectionId, sessionDir?: File? = selectedSession?.sessionDir): File?`
- `getMemoTargetUri()`: via `DocumentFile.fromFile(sessionDir)`

**`di/AppModule.kt`**
- `LiveViewModel`: 14→15 Parameter (+`androidContext()`)
- `AnalysisViewModel`: 5→6 Parameter (+`androidContext()`)

---

## Getestet

### Automatisch

```
./gradlew compileDebugKotlin  → BUILD SUCCESSFUL (13s)
./gradlew test                → BUILD SUCCESSFUL (45 tests, inkl. 3 neue VoiceMemoRecorderTest)
./gradlew assembleDebug       → BUILD SUCCESSFUL (55s)
```

### Manuell (Voraussetzung: pirol-deploy-worktree.ps1 -Branch T69-voice-memo)

Manuell durch Master nach QA-Gate:

1. Live-Tab → Detection → Listen-Icon → Dialog öffnet → Memo-Sektion sichtbar
2. "Memo aufnehmen" → Mic-Button → Aufnahme startet, Timer zählt, Bars animieren
3. Stop → WAV in `<sessionDir>/memos/<detectionId>.wav` vorhanden
4. Speichern → `detections.jsonl` enthält `voiceMemoFile: "memos/<id>.wav"`
5. `verifications.jsonl` enthält `MEMO_RECORDED`-Eintrag mit `newValue`
6. Play-Button → Audio hörbar, Pause funktioniert
7. App-Neustart → Analyse-Tab → selbe Session → Memo abspielbar
8. Löschen → WAV weg, MEMO_DELETED in verifications.jsonl
9. Parallel-Test: Live-Recording 60s + Memo aufnehmen → beide WAVs gültig
10. 60s-Cap: Aufnahme läuft durch → auto-stop + Snackbar "Memo auf 60s gekürzt"
11. SAF-Modus: Memo unter Downloads/PIROL/…/memos/

---

## Bewusst nicht gemacht

- **Memo-Export als m4a** — explizit nicht in Scope (Folge-AP nach Cluster C)
- **Speech-to-Text** — nicht im AP
- **Mehrere Memos pro Detektion** — nicht im AP (1 Memo, überschreibt)
- **Wellenform aus WAV-File** — nur live animiert (statisch bei vorhandenem Memo: Dateiname angezeigt)
- **`VoiceMemoRecorder` als Koin-Singleton** — AP erlaubt lokales Objekt im ViewModel; kein `AppModule`-Overhead

---

## Beobachtungen

### Parallel-Recording-Architektur
VoiceMemoRecorder nutzt einen separaten `AudioRecord`-Daemon-Thread, unabhängig von RecordingService/OboeAudioEngine. Android erlaubt mehrere gleichzeitige `AudioRecord`-Instanzen (sofern Hardware nicht exklusiv belegt). Tatsächliche Verträglichkeit ist hardware-abhängig — manueller Parallel-Test (AC7) zwingend auf Zielgerät.

### SAF-Modus AnalysisViewModel
`DocumentFile.fromFile(sessionDir)` im Analysis-Tab ist ein Workaround — für echte SAF-Schreibpfade wäre ein SAF-URI besser. Bei Analyse bestehender Sessions ist der File-Pfad aber immer verfügbar. Wenn zukünftig externe Sessions per SAF geöffnet werden, `getMemoTargetUri()` entsprechend erweitern.

### `memoAudioPlayer by lazy`
In `onCleared()` wird `memoAudioPlayer.release()` aufgerufen, was den Player bei Bedarf initialisiert. Das ist eine minimale AudioPlayer-Instanz die sofort wieder freigegeben wird — akzeptabler Overhead.

### AnimatedVisibility in VoiceMemoSection fehlt
`VoiceMemoSection` nutzt `when {}` ohne AnimatedVisibility. State-Wechsel (Kein Memo → Aufnahme → Memo) sind ohne Transition. Bei Bedarf `AnimatedContent` nachrüsten.

---

## Abhängigkeiten

### Für nachfolgende Tasks

- **`VerificationEvent.newValue: String?`** ist jetzt im Datenmodell (backward-compat). Zukünftige Verification-Events können das Feld für Nutzlast nutzen.
- **`SessionManager.memoFile()` + `memoDocFile()`** stehen für weitere Memo-Features bereit.
- **`SessionManager.currentSessionDir` + `currentSessionDoc`** sind öffentlich — weitere Features können auf die aktive Session zugreifen.
- **`EditDetectionDialog.onSave`-Signatur** hat sich geändert: alle Aufrufer müssen `voiceMemoFile: String?` entgegennehmen (bereits in T69 durchgezogen).

### Speicherplatz-Schätzung
60s @ 16kHz Mono 16-bit = 60 × 16000 × 2 Bytes = **1.92 MB** pro Memo (WAV, unkomprimiert).

### Empfehlung Folge-AP: "Memo-Export als m4a"
WAV-Memos sind Raven-kompatibel aber groß. Folge-AP könnte `MediaCodec` AAC-Encoding (M4A) anbieten: ~10× kleinere Dateien bei ähnlicher Qualität. VoiceMemoRecorder-API bleibt unverändert — Export wäre ein separater Konverter.

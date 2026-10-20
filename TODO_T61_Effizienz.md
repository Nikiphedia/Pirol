# T61: Effizienz-Review — Umgesetztes + TODOs

Stand: 2026-10-19, Branch `claude/exciting-faraday-sa64ox` (Basis: `master` / V0.0.6)

## 1. Zusammenfassung

Code-Review auf ineffiziente Stellen. Schwerpunkt: Live-Pipeline (DSP + Inference), die auf dem
Geraet dauerhaft laeuft. Alle DSP-Aenderungen sind **bitidentisch** zum alten Ergebnis
(per Vorher/Nachher-Vergleich verifiziert), nur schneller.

Nebenbei gefunden und behoben: **WAV-Header-Bug** in `StreamingWavWriter` (siehe 2.7) —
betrifft alle Aufnahmen seit T51 (V0.0.6).

## 2. Umgesetzt

Messungen: JVM-Desktop (gleicher Kotlin-Code), Vorher/Nachher im selben Lauf.
Auf ARM-Geraeten ist der relative Gewinn erfahrungsgemaess aehnlich oder groesser.

| # | Datei | Problem | Loesung | Messung |
|---|-------|---------|---------|---------|
| 2.1 | `audio/dsp/FFT.kt` | `cos()`/`sin()` pro Butterfly (~11'000 Trig-Aufrufe pro 2048-FFT) | Twiddle-Tabelle pro FFT-Groesse, gecacht | 1000x FFT-2048: 304 ms → 43 ms (**7x**) |
| 2.2 | `audio/dsp/MelSpectrogram.kt` | `process()` verschob nach **jedem** Frame den ganzen Rest-Buffer per `arraycopy` → O(n²/hop). Ursache des Preroll-ANR (T54-Fix-2) | Lese-Offset, einmaliges Kompaktieren am Ende | 30 s in einem Aufruf: 1142 ms → 171 ms (**6.7x**); Live-Chunk 100 ms: 2.8 ms → 0.45 ms |
| 2.3 | `ml/MfccExtractor.kt` | Dichte Filterbank 64 x 1025 (>95% Multiplikation mit 0), Allokation pro Frame | Sparse-Bereich pro Mel-Band, Buffer-Wiederverwendung | extractEnhanced 3 s: 107 ms → 16 ms (**6.7x**, inkl. FFT) |
| 2.4 | `audio/dsp/DynamicRangeMapper.kt` | Alle 250 ms: 60k Floats allozieren + voll sortieren; `computeStatic` sortiert ganze Session | Quickselect (exakt gleiche Perzentile), wiederverwendeter Puffer | 5 s Fenster: 4.1 ms → 0.76 ms (**5x**) |
| 2.5 | `audio/dsp/AudioResampler.kt` | `resample(ShortArray)` allozierte Zwischen-FloatArray (144k) pro Inference | Normalisierung im selben Durchgang (bitidentisch, Skalierung mit 2^-15 ist exakt) | 1 Allokation weniger pro 3-s-Block |
| 2.6 | `ml/BirdNetV3Classifier.kt` | `OrtSession.Result` in `runInference()` nie geschlossen → native Output-Tensoren (1024 + 11'560 Floats) leaken alle 3 s | `session.run(...).use { }` | Speicherleck behoben |
| 2.7 | `data/repository/WavWriter.kt` | **BUG:** `open()` schreibt Header mit absolutem `write(buf, 0)` — das verschiebt die Channel-Position nicht. Erstes `append()` schrieb ab Offset 0 und **ueberschrieb den WAV-Header** mit Audio. Danach patchte `close()` nur Offset 4 + 40 → "RIFF"/"WAVE"/"fmt"/Samplerate kaputt | `channel.position(44L)` nach dem Header | Roundtrip-Test: Header korrekt, Daten bitgenau, `javax.sound` liest Datei |
| 2.8 | `data/repository/WavWriter.kt` | `putShort()` pro Sample + neuer ByteBuffer pro Chunk | Bulk `asShortBuffer().put()`, wiederverwendeter Buffer | — |
| 2.9 | `ml/EmbeddingDatabase.kt` | Ungepufferte Data-Streams (Syscall pro kleinem write), Float-Schleifen | `BufferedInput/OutputStream`, Bulk `asFloatBuffer()` | Format unveraendert (BSED v1) |
| 2.10 | `ui/analysis/AnalysisViewModel.kt` | `readWavSamples()` liest Short fuer Short | Bulk `asShortBuffer().get()` | — |

### Nachtrag (Runde 2)

| # | Datei | Problem | Loesung |
|---|-------|---------|---------|
| 2.11 | `ml/AudioClassifier.kt`, `ml/BirdNetV3Classifier.kt`, `ml/InferenceWorker.kt`, `ml/AudioBlock.kt`, `ml/EmbeddingExtractor.kt`, `ui/live/LiveViewModel.kt` | Pro Detektion lief BirdNET **zweimal** (Klassifikation + separates `extractEmbedding()`) | Neues `classifyWithEmbedding()` liefert Treffer + Embedding aus EINEM Lauf; `AudioBlock.embedding` traegt es zum Embedding-Arm. `extract(..., precomputed)` nutzt es, Fallback bleibt erhalten |
| 2.12 | `ml/AudioBlock.kt`, `ml/InferenceWorker.kt`, `ui/live/LiveViewModel.kt` | LiveViewModel wandelte jeden 3-s-Block Float→Short zurueck fuers WAV, obwohl das Original-PCM vorlag | `AudioBlock.pcm` traegt das Original-ShortArray mit (Ergebnis identisch, Rueckkonvertierung war verlustfrei — nur Rechenzeit gespart) |
| 2.13 | `ml/EmbeddingDatabase.kt` | `LinkedHashMap` ohne Synchronisation, Zugriffe aus parallelen Coroutines | Lock um alle Map-Zugriffe; Suche/Speichern arbeiten auf Snapshots ausserhalb des Locks |
| 2.14 | `data/repository/WavWriter.kt`, `data/repository/SessionManager.kt`, `ui/analysis/AnalysisViewModel.kt` | Defekte V0.0.6-Aufnahmen (siehe 2.7) | `WavWriter.repairHeaderIfNeeded()` + `SessionManager.repairRecordingHeaders()`; wird beim Oeffnen einer Session im Analyse-Tab automatisch ausgefuehrt. Erkennung: fehlendes "RIFF"/"WAVE" → Header aus `session.json`-Samplerate neu schreiben. Gueltige Dateien werden nicht angefasst |

**Achtung 2.11 — Verhaltensaenderung:** Das Embedding stammt jetzt aus dem vorverarbeiteten
Inference-Input (32 kHz, Hochpass falls aktiv, Peak-Normalisierung) statt aus dem Roh-Audio.
Neue DB-Eintraege sind dadurch minimal anders als alte. Fuer saubere Aehnlichkeitssuche
ggf. Embedding-DB einmal leeren. → erledigt automatisch, siehe 2.18.

Verifizierung Runde 2 (JVM + Android-Stubs): Header-Reparatur (kaputte Datei → Header byte-identisch
mit Original, gueltige Datei unveraendert, javax.sound liest sie), InferenceWorker mit Fake-Classifier
(PCM unveraendert durchgereicht, Embedding im Block, 0 zusaetzliche extractEmbedding-Aufrufe),
EmbeddingDatabase mit 8 parallelen Coroutines x 2000 add + search → 16'000 Eintraege, keine Exception.
`LiveViewModel.kt`, `SessionManager.kt`, `AnalysisViewModel.kt` konnten nicht kompiliert werden
(zu viele Android-Abhaengigkeiten) — Aenderungen dort sind klein und manuell geprueft.

### Nachtrag (Runde 3 — nach Feedback)

| # | Datei | Aenderung |
|---|-------|-----------|
| 2.15 | `audio/dsp/SpectrogramOverview.kt` (neu), `ui/analysis/AnalysisViewModel.kt`, `ui/analysis/AnalysisScreen.kt`, `ui/components/SpectrogramCanvas.kt` | **Analyse-Tab zeigt die ganze Aufnahme als Uebersicht.** WAV wird gestreamt gelesen (kein `readBytes()`), per Max-Pooling auf max. 2048 Spalten verdichtet (kurze Rufe bleiben sichtbar). Bei langen Aufnahmen Hop bis fftSize (lueckenlos) → 10 min in 1.2 s statt 5.8 s (Desktop). Aufnahmen < ~22 s werden 1:1 wie bisher gezeigt. Neuer Canvas-Modus `fitAllFrames` skaliert alle Frames auf die Breite |
| 2.16 | `ui/analysis/AnalysisViewModel.kt` | **Vergleichsmodus zeigt jetzt den Ausschnitt der Detektion** (chunkStartSec..chunkEndSec, wie das Abspielen). Vorher: letzte ~22 s der Aufnahme, unabhaengig von der Detektion. Referenz-Sonogramm ebenfalls als Uebersicht |
| 2.17 | `ui/live/LiveViewModel.kt`, `ui/components/SpectrogramState.kt` | **Ganzer Preroll im Sonogramm** (statt 5 s), berechnet im Hintergrund; `prependFrames()` setzt ihn vor bereits eingetroffene Live-Frames. Generationszaehler verwirft verspaetete Ergebnisse nach `clear()`. Sichtbar max. ~22 s (Kapazitaet des Sonogramm-Buffers) |
| 2.18 | `ui/live/LiveViewModel.kt`, `data/AppPreferences.kt` | **Embedding-DB wird einmalig geleert** (neue Basis aus 2.11). Marker `pirol_embedding_db_version`; alte Datei bleibt als `embeddings.bsed.v1.bak` liegen |

Verifizierung Runde 3 (JVM): Uebersicht kurz == volle Frames (bitgleich), lang == Referenz-Max-Pooling
(bitgleich), ungerade Leseblockgroessen, vorzeitiges Dateiende, `readWavRange`, `prependFrames`
(Reihenfolge, Ueberlauf, verworfene Generation) — alle OK. `DspEffizienzTest` jetzt 8/8.

Neue Unit-Tests: `app/src/test/kotlin/ch/etasystems/pirol/audio/dsp/DspEffizienzTest.kt`
(FFT vs. DFT, Chunk-Unabhaengigkeit MelSpectrogram, Quickselect vs. Sortierung,
computeStatic, Resampler-Bitidentitaet, Twiddle-Cache).

## 3. Verifizierung

- **Android-Build NICHT gelaufen:** In der Cloud-Umgebung ist das Android-SDK nicht verfuegbar
  (dl.google.com durch Proxy gesperrt). → **Bitte lokal `./gradlew compileDebugKotlin` und
  `./gradlew testDebugUnitTest` laufen lassen.**
- Ersatz-Verifikation in einem separaten JVM-Gradle-Projekt:
  - Alte vs. neue DSP-Dateien nebeneinander: FFT, MelSpectrogram (4 Chunk-Groessen, 2 Configs,
    Edge-Case hop > fft), MFCC, Resampler, Perzentile → **alle bitidentisch**
  - `DspEffizienzTest`: 6/6 gruen
  - `WavWriter.kt`, `EmbeddingDatabase.kt`, `BirdNetV3Classifier.kt` gegen Android-Stubs +
    onnxruntime-JVM 1.19.0 kompiliert; Roundtrip-Tests WAV + Embedding-DB OK
- Kein Geraetetest (Live-Aufnahme, ONNX auf Geraet) — bitte im naechsten Feldtest pruefen.

## 4. TODOs (nicht umgesetzt — brauchen Entscheidung, Geraet oder Build)

### Hoch

- [x] **Kaputte Aufnahmen seit V0.0.6 reparieren** → 2.14 (automatisch beim Oeffnen im Analyse-Tab).
  Offen: Sessions, die nie im Analyse-Tab geoeffnet werden (z.B. direkt per USB kopiert), werden
  nicht repariert — ggf. zusaetzlich beim App-Start ueber alle Sessions laufen lassen.
- [x] **Doppelte BirdNET-Inference pro Detektion** → 2.11.
- [x] **Analyse-Tab** → 2.15/2.16 (Entscheidung: Uebersicht ueber ganze Aufnahme).
  Offen: Bei sehr langen Aufnahmen (1 h ≈ 7 s Desktop, auf dem Handy laenger) gibt es noch
  keinen Fortschrittsbalken; Sessions mit Rotation zeigen nur das erste Segment (wie Playback).

### Mittel

- [x] **Preroll-Anzeige** → 2.17.
- [ ] **ONNX Runtime beschleunigen:** `SessionOptions` ohne Execution-Provider/Threads.
  Testen: `addXnnpack(...)` oder `addNnapi()`, `setIntraOpNumThreads(2..4)`, FP16-Modell.
  Nur mit Geraete-Benchmark entscheidbar (NNAPI ist je nach Chip schneller oder langsamer).
- [ ] **Real-FFT-Trick:** Reelles Signal als N/2-komplexe FFT rechnen → nochmals ~2x
  auf FFT/MelSpectrogram/MFCC. Aendert Rundung minimal (nicht mehr bitidentisch).
- [ ] **SpectrogramCanvas zeichnet bei jedem Frame das ganze Bitmap neu**
  (Breite x 128 Pixel + LUT-Lookup). Inkrementell nur neue Spalten in einen Ring-Bitmap schreiben
  und mit 2x `drawImage` zusammensetzen.
- [x] **EmbeddingDatabase thread-safe** → 2.13. Offen: bei sehr grossen DBs Top-N per Heap statt Vollsortierung.

### Niedrig

- [ ] `InferenceWorker`: Block wird zweimal in Float konvertiert (AudioBlock mit /32767,
  Resampler mit /32768). Einheitlich machen spart eine Konvertierung (aendert Werte minimal).
  (Die Short-Rueckkonvertierung im LiveViewModel ist mit 2.12 weg.)
- [ ] `SessionManager.removeLastVerification()` liest und schreibt die ganze JSONL-Datei neu —
  ok fuer heutige Groessen, bei sehr langen Sessions beobachten.

### Dependency-Updates ("Software veraltet")

Konnte ich nicht umsetzen, weil ohne Android-SDK weder Build noch Test moeglich ist.
Stand im Repo vs. Vorschlag (vor dem Update jeweils aktuelle stabile Version pruefen):

| Komponente | Ist | Vorschlag |
|------------|-----|-----------|
| Kotlin | 2.1.0 | 2.2.x (Compose-Compiler-Plugin gleiche Version) |
| AGP | 8.7.3 | aktuelle 8.x (9.x nur mit Migrationsaufwand) |
| Gradle Wrapper | 8.11.1 | passend zu AGP (8.14.x) |
| Compose BOM | 2025.01.01 | aktuelle BOM |
| ONNX Runtime | 1.19.0 | aktuelle 1.2x (bessere ARM-Kernels, XNNPACK) |
| kotlinx-coroutines | 1.9.0 | 1.10.x |
| kotlinx-serialization | 1.7.3 | aktuelle 1.x (passend zu Kotlin) |
| Ktor | 3.0.3 | aktuelle 3.x |
| Koin | 4.0.2 | aktuelle 4.x |
| Target/Compile SDK | 35 | 36 (Play-Store-Vorgabe beachten) |

Empfehlung: Eigener Task, Schritt fuer Schritt (erst Gradle/AGP/Kotlin, dann Libraries),
nach jedem Schritt `assembleDebug` + Feldtest. CLAUDE.md Tech-Stack-Tabelle danach nachfuehren.

## 5. Geaenderte Dateien

- `app/src/main/kotlin/ch/etasystems/pirol/audio/dsp/FFT.kt`
- `app/src/main/kotlin/ch/etasystems/pirol/audio/dsp/MelSpectrogram.kt`
- `app/src/main/kotlin/ch/etasystems/pirol/audio/dsp/DynamicRangeMapper.kt`
- `app/src/main/kotlin/ch/etasystems/pirol/audio/dsp/AudioResampler.kt`
- `app/src/main/kotlin/ch/etasystems/pirol/ml/MfccExtractor.kt`
- `app/src/main/kotlin/ch/etasystems/pirol/ml/BirdNetV3Classifier.kt`
- `app/src/main/kotlin/ch/etasystems/pirol/ml/EmbeddingDatabase.kt`
- `app/src/main/kotlin/ch/etasystems/pirol/data/repository/WavWriter.kt`
- `app/src/main/kotlin/ch/etasystems/pirol/ui/analysis/AnalysisViewModel.kt`
- `app/src/main/kotlin/ch/etasystems/pirol/ml/AudioClassifier.kt` (Runde 2)
- `app/src/main/kotlin/ch/etasystems/pirol/ml/AudioBlock.kt` (Runde 2)
- `app/src/main/kotlin/ch/etasystems/pirol/ml/InferenceWorker.kt` (Runde 2)
- `app/src/main/kotlin/ch/etasystems/pirol/ml/EmbeddingExtractor.kt` (Runde 2)
- `app/src/main/kotlin/ch/etasystems/pirol/ui/live/LiveViewModel.kt` (Runde 2)
- `app/src/main/kotlin/ch/etasystems/pirol/data/repository/SessionManager.kt` (Runde 2)
- neu: `app/src/test/kotlin/ch/etasystems/pirol/audio/dsp/DspEffizienzTest.kt`
- neu: `TODO_T61_Effizienz.md` (diese Datei)

## 6. Naechster Schritt

1. Lokal bauen + Unit-Tests (`./gradlew compileDebugKotlin testDebugUnitTest`).
2. Kurzer Feldtest: Live-Aufnahme, danach `recording.wav` in Raven/Audacity oeffnen (2.7).
3. Eine V0.0.6-Session im Analyse-Tab oeffnen → Logcat "WAV-Header repariert", danach Playback/Raven pruefen.
4. Entscheidung zum Analyse-Tab-Fenster (welcher Ausschnitt soll gezeigt werden?).

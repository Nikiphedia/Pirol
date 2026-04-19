# PIROL — Projekt-Uebergabe (Stand T49)

**Projekt:** PIROL (Practical In-field Recognition of Organic Life) — Android-App zur akustischen Echtzeit-Artenerkennung
**Pfad:** `D:\80002\PIROL`
**Package:** `ch.etasystems.pirol`
**Schwester-Projekt:** AMSEL Desktop unter `D:\80002\AMSEL` (Compose Desktop, Kotlin 2.1)

---

## Tech-Stack

| Komponente | Version |
|------------|---------|
| Kotlin | 2.1.0 (JVM Target 17) |
| AGP | 8.7.3 |
| Gradle | 8.11.1 |
| Jetpack Compose BOM | 2025.01.01 (Material 3) |
| Koin DI | 4.0.2 |
| ONNX Runtime | 1.19.0 (onnxruntime-android) |
| Ktor Client | 3.0.3 (OkHttp-Engine) |
| play-services-location | 21.3.0 |
| kotlinx-serialization | 1.7.3 |
| kotlinx-coroutines | 1.9.0 |
| NDK | 27.0.12077973 (C++17, CMake 3.22.1) |
| Min SDK | 26 (Android 8.0) |
| Target/Compile SDK | 35 |
| ABI Filters | arm64-v8a, armeabi-v7a, x86_64 |

---

## Architektur

```
Mikrofon → Oboe C++ (48k/96k) → RingBuffer (30s, SPSC lock-free)
    → JNI → OboeAudioEngine.kt
    → RecordingService (Foreground, Binder, SharedFlow<AudioChunk> 100ms)
    → LiveViewModel:
        ├── [DSP] MelSpectrogram → SpectrogramState → SpectrogramCanvas (Rolling)
        ├── [ML]  ChunkAccumulator (3s) → Resampler (48→32k) → BirdNET V3.0 ONNX
        │         → InferenceConfig (Threshold/TopK) → RegionalSpeciesFilter (DACH)
        │         → DetectionResult + GPS + Session-relative Offsets (T49)
        │         → DetectionListState (Dedup + 10s Re-detection Rule T44)
        │         → LazyColumn<SpeciesCard>
        ├── [EMB] InferenceWorker Callback → EmbeddingExtractor (ONNX 1024-dim / MFCC 43-dim)
        │         → EmbeddingDatabase (Binary BSED, filesDir/embeddings/embeddings.bsed)
        │         → findSimilar() → SimilarityPanel
        └── [GPS] LocationProvider (FusedLocation, 10s) → StateFlow<LocationData?>
                  → LiveUiState (LocationBar) + DetectionResult.copy(lat, lon)
```

### Session-Persistenz (T46):

```
SessionManager: startSession() / endSession()
    → filesDir/sessions/{iso-date}_{uuid6}/
        ├── session.json                   (SessionMetadata inkl. totalRecordedSamples)
        ├── detections.jsonl               (DetectionResult pro Zeile, session-relative Zeiten)
        ├── verifications.jsonl            (VerificationEvent pro Zeile)
        └── audio/
            ├── recording.wav              (EINE durchgehende 16-bit PCM Mono WAV, inkl. Preroll)
            └── recording.selections.txt   (Raven Selection Table, on-demand via Export-Button T47)
```

**Streaming-Writer:** `StreamingWavWriter` öffnet die Datei in `startSession()`, hängt in `appendAudioSamples()` an (via `RandomAccessFile`), finalisiert RIFF- und data-Header in `endSession()`.

---

## Namenskonventionen (aus CLAUDE.md)

| Kontext | Konvention | Beispiel |
|---------|-----------|---------|
| Packages | lowercase | `ch.etasystems.pirol.ml` |
| Klassen | PascalCase | `DetectionListState` |
| Funktionen | camelCase | `classifyChunk()` |
| Konstanten | SCREAMING_SNAKE | `TARGET_SAMPLE_RATE` |
| Dateien (Kotlin) | PascalCase.kt | `SpeciesCard.kt` |
| Dateien (Assets) | snake_case | `species_master.json` |
| Code-Bezeichner | Englisch | — |
| Kommentare | Deutsch | — |

---

## Abgeschlossene Phasen

| Phase | Tasks | Status |
|-------|-------|--------|
| P1 Audio-Engine | T1 Oboe NDK + T2 RecordingService | ✅ |
| P2 DSP-Pipeline | T3 FFT/Mel + T4 SpectrogramCanvas + T5 LiveViewModel | ✅ |
| P3 Adaptive UI | T6 Layouts + Chips + FAB | ✅ |
| P4 ML-Klassifikation | T7 Resampler/BirdNET + T8 InferencePipeline + T9/T10 SpeciesCard/DualPipeline | ✅ |
| P5 Tuning | T11 Confidence/Filter/Regionen | ✅ |
| P6 Embedding-Engine | T12a Pipeline + T12b UI | ✅ |
| P7 GPS + Sessions | T13 GPS + T14 Sessions | ✅ |
| P8 Export + Verifikation | T15 KML + T16 Verifikation | ✅ |
| P9 Upload + Referenzen | T17 Upload + T18 Referenzbibliothek | ✅ |
| P10 Species-Alarm | T20 Watchlist + T21 Alarm-UI | ✅ |
| P11 Onboarding + Config | T22 Onboarding + Labels + Settings | ✅ |
| P12 Analyse-Fenster | T23 Browser + T24 Dual-Vergleich | ✅ |
| P14 Detektions-UX | T26 Mehrsprachig + T27 Dedup+Kandidaten | ✅ |
| P15 Modell-Management | T28 Classifier-IF + T29 Energiesparmodus | ✅ |
| P16 UX-Polish | T30 Settings-Umbau + T31 Karte | ✅ |
| P17 Feld-Bugfixes | T32 Artennamen+Rotation + T33 Detektionsliste | ✅ |
| P18 Audio+Storage | T34–T41 (HPF, Preroll, Loeschen, Modell, SD, Chunk, Cleanup, Migration) | ✅ |
| P19 Code-Scan+Fix | T42 Scan (8 Funde) + T43 6 Fixes | ✅ |
| **P20 Workflow V0.0.4** | **T44 UNCERTAIN+10s · T45 REPLACED+Alt · T46 recording.wav · T47 Raven · T48 Timeline · T49 Session-Offsets** | ✅ |

**Letzter erfolgreicher Build:** T49 — `compileDebugKotlin` BUILD SUCCESSFUL (8s)

---

## Aktueller Stand der Key-Files

### `ml/DetectionResult.kt`

```kotlin
@Serializable
enum class VerificationStatus {
    UNVERIFIED,   // Default
    CONFIRMED,    // Nutzer bestaetigt
    REJECTED,     // Nutzer lehnt ab
    CORRECTED,    // Art korrigiert
    UNCERTAIN,    // Unsicher (T44, orange Rand)
    REPLACED      // Durch Alternative ersetzt (T45, roter Rand + ausgegraut)
}

@Serializable
data class DetectionCandidate(
    val scientificName: String,
    val commonName: String,
    val confidence: Float
)

@Serializable
data class DetectionResult(
    val id: String,
    val scientificName: String,
    val commonName: String,
    val confidence: Float,
    val timestampMs: Long,
    val chunkStartSec: Float,    // T49: Sekunden seit Session-Start (Position in recording.wav)
    val chunkEndSec: Float,      // T49: Sekunden seit Session-Start
    val sampleRate: Int,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val verificationStatus: VerificationStatus = VerificationStatus.UNVERIFIED,
    val correctedSpecies: String? = null,
    val verifiedAtMs: Long? = null,
    val candidates: List<DetectionCandidate> = emptyList(),
    val detectionCount: Int = 1,
    val lastDetectedMs: Long = 0L
)
```

### `ml/DetectionListState.kt`
- Dedup pro Art; 10s Re-detection Rule (T44): Art nur bei ≥10s Pause an Index 0
- `updateVerification()`: CONFIRMED/REJECTED/CORRECTED/UNCERTAIN
- `selectAlternative()` (T45): Original → REPLACED + Alternative → neue Detektion an Index 0

### `ui/components/SpeciesCard.kt`
Buttons: ✓ Bestätigen · ? Unsicher · ✗ Ablehnen · ✎ Korrigieren
- Kandidaten-Zeilen klickbar (T45) → Alternative wählen
- REPLACED: roter Rand, `alpha=0.6`, Badge "Ersetzt → <Art>"
- UNCERTAIN: oranger Rand, Badge "Unsicher"
- Play-Button + MM:SS-Label (T48), disabled bei alten Chunk-Sessions ohne `recording.wav`

### `audio/AudioPlayer.kt` (T48)
- `playFromOffset(file, startSec, endSec)` — Frame-aligned AudioTrack MODE_STATIC mit Auto-Stop via `setNotificationMarkerPosition`

### `data/repository/WavWriter.kt` (T46)
- `object WavWriter { fun write(...) }` — statisch, für ReferenceRepository
- `class StreamingWavWriter(file, sampleRate)` — open/append/close für Session-Recording

### `data/repository/SessionManager.kt`
- `appendAudioSamples(samples)` — kein Chunk-Counter, kontinuierlicher Append
- `getRecordingFile(sessionDir): File?`
- `getCurrentRecordingOffsetSec(): Float` (T49) — liest `StreamingWavWriter.sampleCount / sampleRate`
- `exportRavenSelectionTable(sessionDir): File?` (T47)

### `data/export/RavenExporter.kt` (T47)
- Tab-getrennte Selection Table, UTF-8, Locale.US, 3 Dezimalstellen
- Header: `Selection · View · Channel · Begin/End Time (s) · Low/High Freq · Species · Common Name · Confidence · Status · Corrected Species`
- Enthält ALLE Detektionen inkl. REPLACED/REJECTED (Audit-Trail)

### Export
- `detections.jsonl` — Rohdaten, append-only, session-relative Zeiten ab T49
- `verifications.jsonl` — VerificationEvents
- `recording.wav` — eine Datei pro Session, inkl. Preroll
- `recording.selections.txt` — Raven Selection Table, on-demand via Button
- KML-Exporter — unverändert

---

## Nächste Tasks

### T50 — Marker-Leiste + Cleanup (optional, nach Feldtest)
- Timeline-Marker-Leiste im Analyse-Tab (`Canvas` mit Strichen an `chunkStartSec / recordingDurationSec`)
- Cleanup `compareDetectionChunkIndex` aus `AnalysisUiState` (laut T48-Handover nicht mehr genutzt)

### Feldtest-Pflicht vor T50
Acceptance Criteria von T49 sind **ohne Device-Test nicht verifiziert**. Master-Empfehlung:
1. Neue Session 3–5 Min aufnehmen
2. `detections.jsonl` prüfen: `chunkStartSec > 3.0` und plausibel verteilt?
3. Analyse-Tab Play-Button bei z.B. 02:30 → springt Player dorthin?
4. Raven-Export öffnen: Begin Times verteilt, nicht nur 0–3?
5. Mit Preroll 5s: erste Detektion `chunkStartSec ≥ 5`?

---

## Offene Punkte / Bekannte Limitierungen

1. **BirdNET-Modell** — `birdnet_v3.onnx` muss manuell in `assets/models/` (oder via Download/SAF) abgelegt werden
2. **Labels** — 25 Platzhalter-Arten (echte CSV mit 11'560 Arten noch nicht eingebunden)
3. **Alte Chunk-Sessions** — bleiben im Analyse-Tab ohne Audio-Wiedergabe (Banner, T48)
4. **findCommonName()** — `ReferenceScreen.kt` ~Zeile 124: toter Code — kuenftiger Cleanup
5. **BOM menuAnchor** — Compose BOM 2025.01.01 hat Deprecation-Warning — kuenftiger Upgrade-Task
6. **xenoCantoApiKey** — Kein Eingabefeld in Settings
7. **InferenceConfig** — Nicht persistiert (Reset bei Neustart)
8. **Feldtest T46–T49** — Runtime-Verifikation steht noch aus

---

## Build-Befehle

```bash
# Typ-Pruefung (schnell, kein Device noetig)
./gradlew compileDebugKotlin

# Vollstaendiger Debug-Build
./gradlew assembleDebug

# Dev-Lauf auf Geraet/Emulator
./gradlew installDebug
```

---

## Verbotene Patterns

- Kein `Thread.sleep()` — Coroutines verwenden
- Keine hardcodierten Pfade — `context.filesDir` / `context.assets`
- Kein `GlobalScope` — strukturierte Coroutines via ViewModel/Service Scope
- Keine Plattform-Threads fuer Audio — Oboe NDK + Coroutines

---

## Worker-Regeln

- Alle zu aendernden Dateien **zuerst vollstaendig lesen**
- Vollstaendige Dateien ausgeben (kein "Rest bleibt gleich")
- `./gradlew compileDebugKotlin` muss am Ende BUILD SUCCESSFUL sein
- Handover als `Handover_T{N}.md` im Projekt-Root abliefern
- Keine Aenderungen ausserhalb des Task-Scope

---

## Master-Verhalten

Der Pirol Master **plant, delegiert, reviewt** — **implementiert nicht selbst**. Arbeitspakete als `T{N}_Arbeitspaket.md` im Projekt-Root schreiben; Worker liefern `Handover_T{N}.md` zurück.

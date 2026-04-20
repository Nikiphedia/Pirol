# CLAUDE.md — PIROL Projektkonventionen

## Tech Stack

| Komponente | Version | Hinweis |
|------------|---------|---------|
| Kotlin | 2.1.0 | JVM Target 17 |
| AGP | 8.7.3 | |
| Gradle | 8.11.1 | |
| Jetpack Compose BOM | 2025.01.01 | Material 3 |
| Koin DI | 4.0.2 | koin-android + koin-androidx-compose |
| ONNX Runtime | 1.19.0 | onnxruntime-android |
| Ktor Client | 3.0.3 | OkHttp-Engine, Xeno-Canto API |
| play-services-location | 21.3.0 | FusedLocationProviderClient |
| kotlinx-serialization | 1.7.3 | JSON |
| kotlinx-coroutines | 1.9.0 | core + android |
| NDK | 27.0.12077973 | C++17, CMake 3.22.1 |
| Min SDK | 26 | Android 8.0 |
| Target/Compile SDK | 35 | |
| ABI Filters | arm64-v8a, armeabi-v7a, x86_64 | |

## Package

`ch.etasystems.pirol`

## Schwester-Projekt

AMSEL Desktop (`D:\80002\AMSEL`) — Compose Desktop, Kotlin 2.1. Gemeinsame Formate:
- `region_sets.json` (v3)
- `species_master.json` (v3, 11'560 Arten, 23 Sprachen)
- `birdnet_v3_labels.csv` (Semikolon-delimited)
- `birdnet_v3.onnx` (Float32, 32kHz Input)

## Namenskonventionen

| Kontext | Konvention | Beispiel |
|---------|-----------|---------|
| Packages | lowercase, kein Unterstrich | `ch.etasystems.pirol.ml` |
| Klassen | PascalCase | `InferenceWorker`, `LiveViewModel` |
| Funktionen | camelCase | `classifyChunk()`, `isPlausible()` |
| Konstanten | SCREAMING_SNAKE | `TARGET_SAMPLE_RATE`, `N_MELS` |
| Dateien (Kotlin) | PascalCase.kt | `RegionalSpeciesFilter.kt` |
| Dateien (Assets) | snake_case | `region_sets.json`, `species_master.json` |
| Artnamen (intern) | Unterstrich statt Leerzeichen | `Turdus_merula` |

## Sprache

- **Code-Bezeichner:** Englisch (Kotlin API, Android Framework)
- **Kommentare:** Deutsch
- **UI-Strings:** Deutsch (vorerst keine i18n)
- **Handover / Doku:** Deutsch, ASCII-kompatibel (ae/oe/ue statt Umlaute in Dateinamen)

## Datenformate

| Typ | Format | Beispiel |
|-----|--------|---------|
| Audio Sample Rate | 48000 Hz (Oboe), 32000 Hz (BirdNET) | |
| Chunk-Dauer | 3.0 Sekunden | 96'000 Samples @32kHz |
| FFT | N_FFT=2048, Hop=512 | |
| Mel-Filterbank | 64 Bänder, 40–15000 Hz | |
| MFCC | 13 Koeffizienten | |
| Konfidenz | Float 0.0–1.0 | 0.85 |
| Embedding-Vektoren | FloatArray, L2-normalisiert | |
| Embedding-DB | Binary BSED (AMSEL-kompatibel) | `embeddings.bsed` |
| Artenlisten | Unterstrich-Format | `Parus_major` |
| Zeitstempel | ISO 8601 mit **Offset** (Lokalzeit) | `2026-04-20T14:30:00+02:00` |
| Session-Metadaten | JSON (`@Serializable`) | `session.json` |
| Detektionen | JSONL (eine Zeile pro Detektion) | `detections.jsonl` |
| Verifikationen | JSONL (eine Zeile pro Aktion) | `verifications.jsonl` |
| Audio-Aufnahme | WAV 16-bit PCM Mono 48 kHz | `recording.wav` (eine Datei pro Session, inkl. Preroll) |
| Raven-Export | TSV (Tab-getrennt, UTF-8) | `recording.selections.txt` (auto, neben WAV) |
| GPS-Koordinaten | WGS84, Double | `47.3769, 8.5417` |

**Zeitzone-Regel:** Alle Timestamps in `session.json`, `detections.jsonl` und `verifications.jsonl` werden in **lokaler Zeit mit Zonen-Offset** geschrieben. Kein reines UTC (`Z`), kein nacktes Datetime ohne Offset. Grund: Feldornithologen lesen Raven-Exporte direkt, lokale Zeit muss ohne Konversion erkennbar sein.

## Session-Speicher (V0.0.6+)

| Ebene | Pfad | Hinweis |
|-------|------|---------|
| Default-Basis | `Downloads/PIROL/` | öffentlich, per USB/MTP sichtbar |
| Tages-Unterordner | `Downloads/PIROL/YYYY-MM-DD/` | an/aus in Settings, default an |
| Session-Ordner | `Downloads/PIROL/YYYY-MM-DD/{sessionId}/` | `{sessionId}` = `{iso-date}_{uuid6}` |
| Fallback | `context.filesDir/sessions/…` | wenn SAF-URI nicht zugreifbar |

Alle Session-Dateien liegen **direkt** im Session-Ordner, kein `audio/`-Unterordner: `session.json`, `detections.jsonl`, `verifications.jsonl`, `recording.wav`, `recording.selections.txt`.

## Export-Formate

| Status | Format | Hinweis |
|--------|--------|---------|
| **Aktiv** | Raven Selection Table (`recording.selections.txt`) | kanonisch, wird bei Session-Stop **automatisch** geschrieben, öffnet direkt in Cornell Raven / Audacity / Sonic Visualiser |
| **Obsolet ab V0.0.6** | KML (`.kml`) | wird aus Code und UI entfernt (Feldtest-Feedback: nicht mehr benötigt, da Raven + GPS in Raven-TSV integrierbar) |
| **Aktiv** | ZIP-Session-Paket | via `SessionUploadWorker` nach `Downloads/PIROL/` |

## UI-Konventionen

- **Minimum Tap-Target:** 48 dp (Material 3 Accessibility). Im Feld-Kontext bevorzugt 56 dp für Verifikations-Aktionen.
- **Feedback-Regel:** Jede zerstörbare oder semantik-ändernde Aktion (Verifikation, Lösche, Alternative wählen) zeigt Snackbar mit Undo (≥ 5 s).
- **Sprache auf UI:** Artnamen immer via `SpeciesNameResolver` in der in Settings gewählten Anzeigesprache. Wissenschaftlicher Name nur als Subtitle/Tooltip. Gilt auch für Top-N-Kandidaten.
- **Farbkodierung Verifikationsstatus** (nicht ändern ohne Abstimmung): CONFIRMED grün, UNCERTAIN orange, REJECTED ausgegraut (alpha 0.6), CORRECTED ersetzt Namen, REPLACED ausgegraut + rotem Rand.

## Verzeichnisstruktur

```
app/src/main/
├── kotlin/ch/etasystems/pirol/
│   ├── MainActivity.kt
│   ├── PirolApp.kt
│   ├── audio/              # Oboe-Engine, RecordingService, AudioPlayer, Permissions
│   │   └── dsp/            # FFT, Mel, Resampler, Spectrogram
│   ├── data/
│   │   ├── repository/     # SessionManager, ReferenceRepository, WavWriter, ShareHelper
│   │   ├── export/         # RavenExporter (auto on session stop)
│   │   └── sync/           # UploadManager, UploadTarget, SessionUploadWorker
│   ├── di/                 # Koin AppModule (9 ViewModel-Parameter)
│   ├── location/           # LocationProvider, LocationPermissionHandler
│   ├── ml/                 # BirdNET, Inference, Embedding, Filter, Verification
│   └── ui/
│       ├── live/           # LiveScreen, LiveViewModel, LiveUiState
│       ├── analysis/       # (Platzhalter — P12)
│       ├── reference/      # ReferenceScreen, ReferenceViewModel (T18)
│       ├── map/            # (Platzhalter — Ideenspeicher)
│       ├── settings/       # SettingsScreen (Export-Toggles, T17)
│       ├── components/     # SpectrogramCanvas, SpeciesCard, DetectionList, SimilarityPanel
│       ├── navigation/     # BottomNav
│       └── theme/          # Material 3 Theme
├── cpp/                    # Oboe NDK (RingBuffer, JNI)
├── assets/
│   ├── models/             # birdnet_v3_labels.txt (Platzhalter)
│   └── regions/            # region_sets.json
└── res/                    # Android Resources
```

## Build & Test

```bash
# Typ-Prüfung (schnell, kein Device nötig)
./gradlew compileDebugKotlin

# Vollständiger Debug-Build
./gradlew assembleDebug

# Dev-Lauf auf Gerät/Emulator
./gradlew installDebug
```

## Verbotene Patterns

- Kein `any` ohne Kommentar warum
- Keine `Thread.sleep()` — Coroutines verwenden
- Keine hardcodierten Pfade — `context.filesDir` / `context.assets`
- Keine Plattform-Threads für Audio — Oboe NDK + Coroutines
- Kein `GlobalScope` — strukturierte Coroutines via ViewModel/Service Scope

## Commit-Format

```
T{N}: Kurzbeschreibung

- Detail 1
- Detail 2
```

## Handover-Format

Jeder abgeschlossene Task liefert `Handover_T{N}.md` im Projekt-Root mit:
1. Zusammenfassung
2. Vollständige Dateiliste (neu/geändert/gelöscht)
3. Verifizierung (Build-Status, Tests)
4. Offene Punkte
5. Nächster Schritt

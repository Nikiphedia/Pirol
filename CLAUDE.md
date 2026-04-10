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
| AndroidX Security Crypto | 1.1.0-alpha06 | EncryptedSharedPreferences |
| osmdroid | 6.1.18 | Offline-Karten |

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
| Zeitstempel | ISO 8601 | `2026-04-04T14:30:00Z` |
| Session-Metadaten | JSON (`@Serializable`) | `session.json` |
| Detektionen | JSONL (eine Zeile pro Detektion) | `detections.jsonl` |
| Audio-Chunks | WAV 16-bit PCM Mono | `chunk_000.wav` (3s, 48kHz) |
| GPS-Koordinaten | WGS84, Double | `47.3769, 8.5417` |

## Verzeichnisstruktur

```
app/src/main/
├── kotlin/ch/etasystems/pirol/
│   ├── MainActivity.kt
│   ├── PirolApp.kt
│   ├── audio/              # Oboe-Engine, RecordingService, AudioPlayer, Permissions
│   │   └── dsp/            # FFT, Mel, Resampler, Spectrogram
│   ├── data/
│   │   ├── repository/     # SessionManager, ReferenceRepository, KmlExporter, WavWriter
│   │   ├── sync/           # UploadManager, UploadTarget, SessionUploadWorker
│   │   └── SecurePreferences.kt  # EncryptedSharedPreferences fuer API-Keys
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

## Sicherheit — API-Keys & Secrets

| Regel | Detail |
|-------|--------|
| **Storage** | API-Keys in `SecurePreferences` (EncryptedSharedPreferences), NICHT in `AppPreferences` |
| **Klasse** | `ch.etasystems.pirol.data.SecurePreferences` — eigene Klasse, getrennt von AppPreferences |
| **UI-Eingabe** | Maskiert (`PasswordVisualTransformation`), Toggle "Key anzeigen" |
| **Transport** | Nur HTTPS — Ktor OkHttp Default, keine HTTP-Fallbacks |
| **Source Code** | Keine Keys, Tokens oder Secrets in .kt, Assets, oder Ressourcen |
| **Logging** | Keys nie in Log.d/e/w/i — bei Debug nur `"key=***"` oder `key.take(4) + "..."` |
| **Git** | `.gitignore`: `*.keystore`, `local.properties`, `secrets/`, `*.jks` |
| **Koin** | `SecurePreferences` als Singleton, separat von AppPreferences |

### Betroffene Keys
- `xenoCantoApiKey` — Xeno-Canto API (optional, User-eingegeben)
- `swisstopoApiKey` — swisstopo WMTS (falls noetig, User-eingegeben)

## Verbotene Patterns

- Kein `any` ohne Kommentar warum
- Keine `Thread.sleep()` — Coroutines verwenden
- Keine hardcodierten Pfade — `context.filesDir` / `context.assets`
- Keine Plattform-Threads für Audio — Oboe NDK + Coroutines
- Kein `GlobalScope` — strukturierte Coroutines via ViewModel/Service Scope
- **Keine API-Keys in Plain-Text SharedPreferences** — `SecurePreferences` verwenden
- **Keine Secrets in Log-Statements** — maskieren oder weglassen

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

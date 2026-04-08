# PIROL — Projektuebersicht (Stand 2026-04-06)

## Was ist PIROL?

Android-App zur akustischen Echtzeit-Artenerkennung im Feld.
Schwester-Projekt zu AMSEL Desktop (Kotlin/Compose Desktop).

- **Package:** `ch.etasystems.pirol`
- **Pfad:** `D:\80002\PIROL`
- **Stack:** Kotlin 2.1, Jetpack Compose, Material 3, Koin DI, Oboe NDK, ONNX Runtime Mobile, WorkManager, osmdroid
- **Min SDK 26, Target 35**
- **Build:** `./gradlew assembleDebug` oder `compileDebugKotlin`

---

## Architektur

```
Mikrofon → Oboe C++ (48k/96k) → RingBuffer (30s, SPSC)
    → JNI → RecordingService (Foreground, SharedFlow<AudioChunk> 100ms)
    → LiveViewModel (13 Koin-Parameter):
        ├── [DSP]  MelSpectrogram → SpectrogramState → SpectrogramCanvas
        ├── [ML]   ChunkAccumulator (3s) → Resampler (48→32k) → AudioClassifier (ONNX)
        │          → InferenceConfig → RegionalSpeciesFilter → SpeciesNameResolver (23 Sprachen)
        │          → DetectionResult + GPS + Candidates → DetectionListState → SpeciesCard
        ├── [EMB]  EmbeddingExtractor (1024-dim ONNX / 43-dim MFCC) → EmbeddingDatabase (BSED)
        │          → findSimilar() → SimilarityPanel
        ├── [GPS]  LocationProvider (FusedLocation, 10s) → DetectionResult.lat/lon
        ├── [SES]  SessionManager → sessions/{id}/ (session.json + detections.jsonl + audio/*.wav)
        ├── [WL]   WatchlistManager → AlarmService (Notification + Vibration, 5min Cooldown)
        └── [UPL]  UploadManager → WorkManager → LocalExportTarget (ZIP → Downloads/PIROL/)
```

---

## 5 Tabs

| Tab | Status | Inhalt |
|-----|--------|--------|
| **Live** | ✅ voll | Sonogramm, Detektionsliste, FAB, GPS-Bar, Status-Zeile |
| **Analyse** | ✅ voll | Session-Browser, Chunk-Navigation, Sonogramm aus WAV, Verifikation, Dual-Vergleich (Detektion vs Referenz) |
| **Referenzen** | ✅ voll | Artenliste → Aufnahmen → AudioPlayer, "Als Referenz speichern" auf SpeciesCards |
| **Karte** | ✅ voll | osmdroid OSM-Karte, Detektions-Marker mit Popup |
| **Settings** | ✅ voll | Sonogramm-Config, Farbpalette, Confidence, Region, Presets, Energieprofil, Modell-Info, Artennamen-Sprache, Export-Toggles, Watchlist-Editor mit SAF + Autocomplete |

---

## Implementierte Features (T1–T31)

### Audio & DSP (P1-P2)
- Oboe NDK Low-Latency (48k/96k Hz)
- Foreground Service mit Notification
- MelSpectrogram (FFT 2048, Hop 512, 64 Mel-Baender)
- 3 Presets: BIRDS (125-8kHz), BATS (10k+), WIDEBAND
- 3 Paletten: MAGMA, VIRIDIS, GRAYSCALE (Default)

### ML-Klassifikation (P4-P5)
- BirdNET V3.0 via ONNX Runtime Mobile
- AudioClassifier Interface (erweiterbar fuer V2.4, Custom)
- 11'560 Arten Labels (CSV aus Zenodo)
- Modell aus filesDir/models/ (516 MB FP32 oder 259 MB FP16)
- Modell-Download von Zenodo im Onboarding ODER SAF-Import
- InferenceConfig: Threshold, TopK, Region, Intervall (3s-30s)
- RegionalSpeciesFilter (CH Breeding, Central Europe, All)
- Top-N Kandidaten aufklappbar auf SpeciesCard
- Dedup pro Art (hoechste Confidence behalten, Zaehler-Badge)

### Mehrsprachigkeit (P14)
- species_master.json (11'560 Arten, 23 Sprachen)
- SpeciesNameResolver mit Sprach-Auswahl in Settings
- 11 UI-Sprachen: DE, EN, FR, IT, ES, PT, NL, PL, RU, JA, ZH

### GPS & Georeferenzierung (P7)
- FusedLocationProvider (10s Intervall)
- Lat/Lon auf jedem DetectionResult
- LocationBar im LiveScreen
- Permission-Handling (analog Audio)

### Sessions & Persistenz (P7)
- SessionManager: Start/Stop mit Recording-Lifecycle
- Ordnerstruktur: filesDir/sessions/{iso-date}_{uuid6}/
- session.json (Metadaten), detections.jsonl, verifications.jsonl, audio/chunk_NNN.wav
- WAV: 16-bit PCM Mono, 48kHz, 3s pro Chunk

### Verifikation (P8)
- VerificationStatus: UNVERIFIED, CONFIRMED, REJECTED, CORRECTED
- Buttons auf SpeciesCard (✓ / ✗ / ✎ mit Korrektur-Dialog)
- Verifikation nachtraeglich in Analyse-Tab
- verifications.jsonl (separate Persistierung, Rohdaten unveraendert)

### Export (P8)
- KML-Export mit Placemarks pro Detektion (lon,lat,alt)
- FileProvider + Share Intent
- ZIP-Export via WorkManager (Session → Downloads/PIROL/)
- WLAN-only Constraint konfigurierbar

### Embedding & Aehnlichkeit (P6)
- EmbeddingExtractor: ONNX 1024-dim oder MFCC 43-dim Fallback
- EmbeddingDatabase: Binary BSED (AMSEL-kompatibel)
- findSimilar() mit Cosine Similarity
- SimilarityPanel im LiveScreen

### Referenzbibliothek (P9)
- Verifizierte Detektionen als Referenz speichern
- references/{species}/ref_NNN.wav
- ReferenceRepository mit index.json
- AudioPlayer (MediaPlayer-Wrapper, StateFlow)
- ReferenceScreen: Artenliste → Aufnahmen → Play/Stop

### Analyse (P12)
- Session-Browser (alle Sessions, sortiert, Metadaten)
- Session-Detail: Chunk-Navigation, Sonogramm aus WAV
- Audio-Playback pro Chunk
- Verifikation nachtraeglich
- Dual-Sonogramm-Vergleich (Detektion vs Referenz)
- Referenz-Auswahl per FilterChip

### Watchlist & Alarm (P10)
- watchlist.json Import (Downloads/PIROL/ Auto-Scan + SAF File-Picker)
- WatchlistEntry: scientificName, commonName, priority (high/normal/low)
- AlarmService: Notification Channel IMPORTANCE_HIGH + Vibration
- 5 Minuten Cooldown pro Art
- Watchlist-Editor in Settings (Autocomplete aus RegionalSpeciesFilter, Delete)
- Reaktiver StateFlow (WatchlistManager → LiveViewModel)

### Energiemanagement (P15)
- Inference-Intervall konfigurierbar (3s / 6s / 15s / 30s)
- 4 Power-Profile: Genau, Standard, Sparsam, Ultra-Sparsam
- Audio-Chunks werden auch bei uebersprungener Inference gespeichert

### Onboarding (P11)
- 3-Schritt-Dialog bei Erststart: Region → Modell → Permissions
- BirdNET-Modell Download (Zenodo FP16/FP32) oder SAF-Import
- SharedPreferences fuer alle Settings (Threshold, Region, Intervall, Sprache, Toggles)

### Karte (P16)
- osmdroid (OpenStreetMap, kein API-Key noetig)
- Detektions-Marker aus allen Sessions
- Popup: Artname, Konfidenz%, Session-ID
- Default-Center: Zuerich (47.38, 8.54)

### Dark Mode
- ✅ Implementiert (System-Setting + Dynamic Colors Android 12+)
- Default-Palette GRAYSCALE (Dark-Mode-tauglich)

---

## Packages

```
ch.etasystems.pirol/
├── audio/              # OboeAudioEngine, RecordingService, AudioPlayer, AudioPermissionHandler
│   └── dsp/            # FFT, MelFilterbank, MelSpectrogram, AudioResampler, SpectrogramConfig
├── data/
│   ├── AppPreferences  # SharedPreferences Wrapper
│   ├── repository/     # SessionManager, ReferenceRepository, KmlExporter, WavWriter, ShareHelper
│   └── sync/           # UploadManager, UploadTarget, LocalExportTarget, SessionUploadWorker
├── di/                 # AppModule (Koin, 13 ViewModel-Parameter)
├── location/           # LocationProvider, LocationPermissionHandler
├── ml/                 # AudioClassifier, BirdNetV3Classifier, InferenceWorker, InferenceConfig,
│                       # EmbeddingExtractor, EmbeddingDatabase, MfccExtractor, DetectionResult,
│                       # DetectionListState, RegionalSpeciesFilter, SpeciesNameResolver,
│                       # WatchlistManager, AlarmService, ModelManager
└── ui/
    ├── live/           # LiveScreen, LiveViewModel, LiveUiState (3 Layouts)
    ├── analysis/       # AnalysisScreen, AnalysisViewModel, AnalysisUiState (3-Ebenen-Nav)
    ├── reference/      # ReferenceScreen, ReferenceViewModel, ReferenceUiState
    ├── map/            # MapScreen, MapViewModel (osmdroid)
    ├── settings/       # SettingsScreen (scrollbar, 8+ Sektionen)
    ├── onboarding/     # OnboardingScreen (3-Schritt-Wizard)
    ├── components/     # SpectrogramCanvas, SpeciesCard, DetectionList, SimilarityPanel, SessionCard
    ├── navigation/     # PirolNavigation, Screen Enum (5 Tabs + Onboarding)
    └── theme/          # Theme (Light/Dark + Dynamic Colors)
```

---

## Datenformate

| Typ | Format | Beispiel |
|-----|--------|---------|
| Audio | WAV 16-bit PCM Mono 48kHz | chunk_000.wav (3s) |
| Detektionen | JSONL (@Serializable) | detections.jsonl |
| Verifikationen | JSONL | verifications.jsonl |
| Session-Meta | JSON (prettyPrint) | session.json |
| Embeddings | Binary BSED (AMSEL-kompatibel) | embeddings.bsed |
| Referenz-Index | JSON | index.json |
| Watchlist | JSON | watchlist.json |
| Export | KML (Placemarks) + ZIP | {sessionId}.kml |
| Labels | CSV (Semikolon, UTF-8 BOM) | birdnet_v3_labels.csv |
| Arten | JSON (23 Sprachen) | species_master.json |
| GPS | WGS84 Double | 47.3769, 8.5417 |
| Zeitstempel | ISO 8601 | 2026-04-05T14:30:00Z |
| Artennamen | Unterstrich-Format | Turdus_merula |

---

## Bekannte Pendenzen / Ideen

- xenoCantoApiKey Feld in Settings (fehlt, API wird noch nicht genutzt)
- Xeno-Canto Referenz-Download (Ktor Client bereit, API-Key noetig)
- BirdNET V2.4 TFLite Runtime (AudioClassifier Interface steht)
- Zenodo-Download URLs verifizieren (Redirects testen)
- viewModelScope in onCleared (Session-Save bei App-Kill)
- T27 Dedup-Verhalten klären (nur Top-1 vs Top-K separat listen)
- Koin Compose-Warning (KoinContext)
- menuAnchor() Deprecation (Material3 API Migration)
- Offline-Tiles fuer osmdroid
- Marker-Clustering bei vielen Detektionen
- Session-Loeschen
- Bat-Modus (Ultraschall >15kHz)
- Wear OS Companion
- KMP Code-Sharing AMSEL ↔ PIROL

---

## Build & Run

```powershell
# Typ-Check (schnell)
cd D:\80002\PIROL; .\gradlew.bat compileDebugKotlin

# Vollbuild
cd D:\80002\PIROL; .\gradlew.bat assembleDebug

# APK: app\build\outputs\apk\debug\app-debug.apk

# Modell (516 MB, nicht in APK — muss via SAF oder Download importiert werden):
# C:\Users\nleis\Documents\AMSEL\models\birdnet_v3.onnx
```

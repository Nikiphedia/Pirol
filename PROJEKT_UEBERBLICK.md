# PIROL — Projektuebersicht (Stand 2026-04-22 · **V0.0.6 Release-ready** inkl. T57 Block B · Feldtest-Abnahme erfolgt)

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
        ├── [SES]  SessionManager → Downloads/PIROL/YYYY-MM-DD/{id}/ (flat: session.json + detections.jsonl + verifications.jsonl + recording.wav + recording.selections.txt)
        ├── [WL]   WatchlistManager → AlarmService (Notification + Vibration, 5min Cooldown)
        └── [UPL]  UploadManager → WorkManager → LocalExportTarget (ZIP → Downloads/PIROL/)
```

---

## 5 Tabs

| Tab | Status | Inhalt |
|-----|--------|--------|
| **Live** | ✅ voll | Sonogramm, Detektionsliste (Dedup + 10s Re-detection), FAB, GPS-Bar, Status-Zeile, Alternativwahl |
| **Analyse** | ✅ voll | Session-Browser (Auto-Refresh via ON_RESUME, T51c), durchgehende `recording.wav` mit Zeit-Offset-Wiedergabe, MM:SS-Labels pro Detektion, Verifikation, Dual-Vergleich, Re-Export Raven-TSV |
| **Referenzen** | ✅ voll | Artenliste → Aufnahmen → AudioPlayer, "Als Referenz speichern" auf SpeciesCards |
| **Karte** | ✅ voll | osmdroid OSM-Karte, Detektions-Marker mit Popup |
| **Settings** | ✅ voll | Sonogramm-Config, Farbpalette, Confidence, Region, Presets, Energieprofil, Modell-Info, Artennamen-Sprache, **Speicherort-Sektion (SAF-Picker + Tages-Unterordner, T51)**, Watchlist-Editor mit SAF + Autocomplete |

---

## Implementierte Features (T1–T49, V0.0.6-WIP: T54 + T51 + T51b + T51c)

### Audio & DSP (P1-P2)
- Oboe NDK Low-Latency (48k/96k Hz)
- Foreground Service mit Notification
- MelSpectrogram (FFT 2048, Hop 512, **128** Mel-Baender)
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

### Sessions & Persistenz (P7, überarbeitet T46, V0.0.6 T51)
- SessionManager: Start/Stop mit Recording-Lifecycle
- **V0.0.6 Default-Pfad:** `Downloads/PIROL/YYYY-MM-DD/{iso-date}_{uuid6}/` (per SAF, User-waehlbar)
- **Flache Struktur:** alle Dateien direkt im Session-Ordner, kein `audio/`-Unterordner mehr
- session.json (Metadaten inkl. `totalRecordedSamples`), detections.jsonl, verifications.jsonl
- **Eine durchgehende `recording.wav`** pro Session (16-bit PCM Mono, inkl. Preroll, **Daueraufnahme** — Audio immer, nicht nur bei Detektionen, T51)
- **Automatischer Raven-TSV-Export** neben `recording.wav` beim Session-Stop (T51)
- StreamingWavWriter: FileChannel-basiert (T51), Header-Finalisierung beim Stop
- Fallback-Banner wenn SAF-Pfad nicht erreichbar → `getExternalFilesDir()/PIROL/`
- Migration bestehender Sessions via `SessionMigrationWorker` (CoroutineWorker)
- **Preroll-Crash (T54)** behoben: drei unabhaengige Bugs (MelSpectrogram-Race, IO-Dispatcher-Race, ANR bei 30s-Preroll)

### Verifikation (P8, erweitert P20)
- VerificationStatus: UNVERIFIED, CONFIRMED, REJECTED, CORRECTED, **UNCERTAIN** (T44), **REPLACED** (T45)
- Buttons auf SpeciesCard: ✓ / ? / ✗ / ✎ mit Korrektur-Dialog
- **Alternative wählen**: Klick auf Kandidaten-Zeile ersetzt Hauptart (Original als REPLACED ausgegraut, rotem Rand)
- **10s Re-detection Rule** (T44): Art rückt nur nach ≥10s Pause an Index 0 der Liste
- Verifikation nachtraeglich in Analyse-Tab
- verifications.jsonl (separate Persistierung, Rohdaten unveraendert)

### Export (P8, erweitert T47, **V0.0.6 T51: KML entfernt, Raven ist kanonisch**)
- **Raven Selection Table** (T47, T51-Auto) — `recording.selections.txt` **automatisch** beim Session-Stop neben `recording.wav`; TSV-Format, direkt in Cornell Raven / Audacity / Sonic Visualiser öffenbar
- Re-Export im Analyse-Tab fuer nachtraeglich verifizierte Sessions
- ~~KML-Export~~ **ab V0.0.6 entfernt** (Feldtest-Feedback: nicht benoetigt, Raven ersetzt es vollstaendig)
- ~~FileProvider + Share Intent~~ (nicht mehr noetig: Datei liegt direkt im oeffentlichen Ordner)
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

### Analyse (P12, erweitert T48, V0.0.6 T51/T51c)
- Session-Browser (alle Sessions, sortiert, Metadaten), **Auto-Refresh via ON_RESUME** (T51c)
- Session-Detail: durchgehende `recording.wav`, Zeit-Offset-Wiedergabe via `AudioPlayer.playFromOffset`
- Play-Button pro Detektion springt zur exakten Sekunde (T49 session-relative Offsets)
- MM:SS-Zeit-Label pro Detektion
- Banner für alte Chunk-Sessions (keine Audio-Wiedergabe)
- Rueckwaertskompatibles Laden von `filesDir/sessions/` + `audio/recording.wav`-Legacy
- Verifikation nachtraeglich
- Dual-Sonogramm-Vergleich (Detektion vs Referenz)
- Referenz-Auswahl per FilterChip
- Re-Export Raven-TSV (fuer nachtraegliche Verifikations-Updates)

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
│   ├── export/         # RavenExporter (T47, Auto-Export T51)
│   ├── repository/     # SessionManager, SessionMigrationWorker (T51), ReferenceRepository, WavWriter (FileChannel-basiert, T51)
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
| Audio | WAV 16-bit PCM Mono 48kHz | recording.wav (eine Datei pro Session, Daueraufnahme inkl. Preroll) |
| Detektionen | JSONL (@Serializable, session-relative Zeitoffsets) | detections.jsonl |
| Raven-Export | TSV (Tab-getrennt, UTF-8, **Auto beim Session-Stop** T51) | recording.selections.txt |
| Verifikationen | JSONL | verifications.jsonl |
| Session-Meta | JSON (prettyPrint) | session.json |
| Embeddings | Binary BSED (AMSEL-kompatibel) | embeddings.bsed |
| Referenz-Index | JSON | index.json |
| Watchlist | JSON | watchlist.json |
| Upload | ZIP | {sessionId}.zip |
| Labels | CSV (Semikolon, UTF-8 BOM) | birdnet_v3_labels.csv |
| Arten | JSON (23 Sprachen) | species_master.json |
| GPS | WGS84 Double | 47.3769, 8.5417 |
| Zeitstempel | ISO 8601 **mit Offset** (Lokalzeit, V0.0.6 T51) | 2026-04-20T14:30:00+02:00 |
| Artennamen | Unterstrich-Format | Turdus_merula |

**Speicher-Layout (V0.0.6 T51):**
```
Downloads/PIROL/                  (oder anderer per SAF gewaehlter Basis-Pfad)
  YYYY-MM-DD/                     (optional, Toggle "Tages-Unterordner")
    {iso-date}_{uuid6}/           (Session-Ordner, flache Struktur)
      session.json
      detections.jsonl
      verifications.jsonl
      recording.wav
      recording.selections.txt    (Raven-TSV, auto)
```

---

## V0.0.6-Status (Stand 2026-04-22, Release)

### Release-Inhalt (auf master, Tag `v0.0.6`)
- ✅ **T54** Preroll-Crash (3 Bugs: MelSpectrogram-Race, IO-Dispatcher-Race, ANR) — Samsung verifiziert
- ✅ **T51** Storage-Layout, SAF, Auto-Raven, Daueraufnahme, KML entfernt, Zeitzone mit Offset
- ✅ **T51b** Timestamp-Leser (AnalysisViewModel.openCompare, ReferenceRepository, WatchlistManager)
- ✅ **T51c** LiveViewModel OffsetDateTime-Fallback + T51-Completion-Gaps (Fallback-Banner, ON_RESUME-Refresh)
- ✅ **T52** Live-UX (Buttons 56dp, Snackbar+Undo, FAB 3-State, Analyse mm:ss+MB, Top-N in Anzeigesprache, parseInstantCompat) — 22 AC, 6 Commits
- ✅ **T56** Sonogramm-Auto-Kontrast (Rolling-Perzentil p2/p98, 5s-Window, IIR; manueller dB-Range als Fallback; Analyse-Tab Einmal-Perzentil)
- ✅ **T56b** Gamma-Kompression + Ceiling-dB (LUT-Cache kombiniert Gamma+Palette)
- ✅ **T53** GPS-Robustheit (Accuracy-Filter 50m, LastKnown-Fallback 2min, Median-5, Intervall 2/5/10/20/60s; gpsStats in session.json nullable; 12 Unit-Tests gruen)
- ✅ **T57 Block B** Session-Rotation (Slider 10-80min, Multi-WAV mit `recordingSegments`), WAV-Header periodisch geupdatet (Windows-kompatibel bei Prozess-Kill), Raven-TSV mit `Begin File` + `Begin Date Time` + Low=150 Hz, FAB-Farben korrigiert (PREROLL gruen, RECORDING rot)

### Offen, nicht-blockierend (nach V0.0.6)
- ⬜ **T54b** Fairphone-Preroll-Crash — auf Hold (User-Prio)
- ⬜ **T55** Recording-Start-Stabilitaet — durch T54 wahrscheinlich miterledigt, Feldtest-Verifikation
- ⬜ BDA V005 → V006

### V0.0.7-Ideenspeicher
- T58 Map V2 (Cluster, Session-Filter, Menue, Verifikation auf Karte, Live-Update)
- T59 Canvas-Skalierung Merlin-Stil
- T60 BirdNET Multi-Label pro Chunk (aktuell Top-1) + Per-Species-Frequenzen im Raven-Export
- Per-Species Low/High Freq aus BirdNET-Metadata (TODO-Marker in RavenExporter.kt)

---

## Bekannte Pendenzen / Ideen

- ~~**SessionCard.kt:47,52** `Instant.parse()`~~ ✅ gefixt in T52 via `util/DateTimeUtils.parseInstantCompat()`
- **AnalysisViewModel** liest WAV via `File` — SAF-URIs auf SD-Karte funktionieren nicht (Folgetask: `ContentResolver.openInputStream()`)
- Alte Chunk-Sessions bleiben im Analyse-Tab ohne Audio-Wiedergabe
- xenoCantoApiKey Feld in Settings (fehlt, API wird noch nicht genutzt)
- Xeno-Canto Referenz-Download (Ktor Client bereit, API-Key noetig)
- BirdNET V2.4 TFLite Runtime (AudioClassifier Interface steht)
- Zenodo-Download URLs verifizieren (Redirects testen)
- viewModelScope in onCleared (Session-Save bei App-Kill)
- Koin Compose-Warning (KoinContext)
- menuAnchor() Deprecation (Material3 API Migration)
- Offline-Tiles fuer osmdroid
- Marker-Clustering bei vielen Detektionen
- Bat-Modus (Ultraschall >15kHz)
- Wear OS Companion
- KMP Code-Sharing AMSEL ↔ PIROL
- SessionMigrationWorker auf Koin umstellen (laut T51-Handover nice-to-have)
- Koin ViewModel-Parameter-Anzahl (9 vs. 13 inkonsistent in Altdokumenten) — Code ist ground truth, in Folge-AP nochmal auditieren

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

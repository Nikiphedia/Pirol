# PIROL — Projektbeschrieb

## Was ist PIROL?

**PIROL** (Practical In-field Recognition of Organic Life) ist eine Android-App zur akustischen Bestimmung von Vogelarten und anderen Tiergruppen — im Feld, in Echtzeit, vollständig offline-fähig. Sie kombiniert Live-Aufnahme, On-Device-ML-Klassifizierung, Sonogramm-Visualisierung und Xeno-Canto-Referenzvergleich in einer einzigen, feldtauglichen Anwendung.

**Vision:** Ein besseres Merlin — mit professionellem Sonogramm, transparenter Klassifizierung, Offline-First-Architektur und der Signalverarbeitungs-Tiefe von AMSEL.

**Zielgruppe:** Ornithologen, Feldforschende, Biologie-Studierende, ambitionierte Birder.

---

## Abgrenzung zu Merlin & AMSEL

| Aspekt | Merlin | AMSEL (Desktop) | **PIROL (Mobile)** |
|--------|--------|-----------------|---------------------|
| Plattform | iOS / Android | Desktop (Win/Mac/Linux) | Android (Phone + Tablet) |
| Aufnahme | Ja, Live | Nein (Import) | **Ja, Live + Import** |
| Echtzeit-Erkennung | Ja (BirdNET-Lite) | Nein (Batch) | **Ja (On-Device ONNX)** |
| Sonogramm | Minimal (Rolling) | Professionell (Full) | **Professionell (Adaptive)** |
| Offline | Teilweise | Voll | **Voll (ML + Referenzen)** |
| Filterung/Signalkette | Keine | Umfangreich (8 Stufen) | **Wesentliche (3–4 Stufen)** |
| Referenzvergleich | Artenseiten | Xeno-Canto + Embedding | **Xeno-Canto + Embedding** |
| Transparenz | Blackbox | Embeddings sichtbar | **Embeddings + Confidence** |
| Export | Keine | PNG/WAV/MP3 | **KML + Raven Selection Table + JSONL + Share (Bild/Audio/Liste)** |
| Cloud-Sync | Keine | Keine | **Pluggable Upload (Drive/S3/SFTP/WebDAV) bei WLAN** |
| GPS-Logging | Nein | Nein | **Ja (Georef. + KML-Track)** |
| Verifikation | Nein | Nein | **Ja (Bestätigen · Unsicher · Ablehnen · Korrigieren · Alternative wählen)** |

---

## Tech-Stack-Empfehlung

| Komponente | Technologie | Begründung |
|------------|-------------|------------|
| **Sprache** | Kotlin 2.1+ | Identisch mit AMSEL → max. Code-Sharing |
| **UI** | Jetpack Compose + Material 3 | Adaptive Layouts (Phone/Tablet), state-of-the-art Android UI |
| **Architektur** | Kotlin Multiplatform (KMP) für Core | Shared Modules zwischen AMSEL Desktop & PIROL Mobile |
| **Audio-Aufnahme** | Oboe (C++/NDK) | Low-Latency, < 10 ms, Google-maintained |
| **Audio-Processing** | KMP-Modul (shared mit AMSEL) | FFT, Mel-Filterbank, Bandpass — Kotlin/Native oder expect/actual mit JTransforms |
| **On-Device ML** | ONNX Runtime Mobile / TensorFlow Lite | EfficientNet-Embeddings + BirdNET-Lite on-device |
| **Datenbank** | Room (Android) / SQLDelight (KMP) | SQLDelight = shared DB-Schema mit Desktop |
| **Netzwerk** | Ktor Client (KMP) | Xeno-Canto API, shared mit Desktop |
| **Cloud-Sync** | Google Drive API / WebDAV (Ktor) | Push-only, WLAN-getriggert |
| **DI** | Koin (Multiplatform) | Leichtgewichtig, KMP-kompatibel |
| **Build** | Gradle + Version Catalog | Konsistent mit AMSEL |
| **Min SDK** | API 26 (Android 8.0) | ~97% Abdeckung, Oboe-Support |

### Warum KMP statt rein nativ?

Die Core-Logik von AMSEL (FFT, Mel-Filterbank, Similarity Engine, Xeno-Canto Client, Projektmodell) ist reines Kotlin ohne Desktop-UI-Abhängigkeiten. Mit KMP lassen sich diese ~8'000 LOC in Shared Modules extrahieren:

```
amsel-shared/          ← KMP Library (shared)
├── core-audio/        ← FFT, Mel, Resampling, Filter
├── core-ml/           ← ONNX-Inference, Embedding-Vergleich
├── core-similarity/   ← MFCC, DTW, Cosinus, HNSW
├── core-model/        ← Annotation, Project, AuditEntry
├── data-api/          ← Xeno-Canto Client (Ktor)
└── data-cache/        ← Offline-Cache Logik

amsel-desktop/         ← Compose Desktop UI (bestehendes AMSEL)
pirol-android/         ← Jetpack Compose UI (neue App)
```

**Vorteil:** Bugfixes in der Similarity Engine oder neue ML-Modelle profitieren sofort beide Plattformen.

---

## Kernfunktionen

### 1. Live-Aufnahme + Echtzeit-Erkennung

```
Mikrofon → Oboe (Low-Latency) → Ring-Buffer (konfigurierbar: 10–120s)
                                      ↓
                              Chunked FFT (3s-Fenster, 1s-Overlap)
                                      ↓
                              ONNX Runtime: EfficientNet Embedding
                                      ↓
                              Top-K Klassifizierung (Confidence ≥ Threshold)
                                      ↓
                              UI: Rolling Artenliste + Live-Spektrogramm
```

- **Aufnahmeformat:** 48 kHz, 16-bit Mono (Oboe/AudioRecord)
- **Inference-Intervall:** Alle 2–3 Sekunden auf 3s-Chunk
- **Latenz-Budget:** < 500 ms (Aufnahme → Anzeige)
- **Energiesparmodus:** Reduzierte Sample-Rate (16 kHz), grösseres Intervall (5s)
- **Hintergrund-Aufnahme:** Foreground Service mit Notification

#### Preroll-Buffer

Der Ring-Buffer läuft permanent (Always-Listening). Beim manuellen Speichern werden die letzten X Sekunden rückwirkend mitgespeichert. Rein mechanisch, keine ML-Logik.

- **Dauer:** Konfigurierbar (10s / 30s / 60s / 120s), Default: 30s
- **Save:** User drückt "Speichern" → Preroll wird der Aufnahme vorangestellt
- **Markierung:** Preroll-Bereich wird in der Sonogramm-Ansicht visuell abgegrenzt (halbtransparenter Overlay)
- **Speicher:** Ring-Buffer als PCM im RAM, bei Save Flush auf Disk

Kein Ruf geht mehr verloren — solange die App läuft, sind die letzten Sekunden immer verfügbar.

#### Species Alerts (Watchlist)

Konfigurierbare Artenliste, die bei Detektion eine Benachrichtigung auslöst. Rein informativ — kein automatisches Speichern, kein Eingriff in die Aufnahme.

- **Watchlist:** Frei konfigurierbar, z.B. "Pirol, Eisvogel, Wendehals"
- **Alert-Typen:**
  - Vibration (kurz: beliebige Art erkannt, lang: Watchlist-Art erkannt)
  - Notification mit Artname + Confidence
  - Optional: Ton-Alert (wählbar, default: stumm um Vögel nicht zu stören)
- **Confidence-Schwelle:** Pro Art einstellbar (Default: 80%)
- **Cooldown:** Konfigurierbar (30s / 1min / 5min) — verhindert Dauervibrieren bei territorial rufenden Vögeln
- **Rucksack-Workflow:**
  ```
  Handy in Tasche → Rucksack-Modus (Display aus)
       ↓
  ML läuft im Hintergrund (Foreground Service)
       ↓
  Pirol detektiert (Confidence 87%)
       ↓
  Vibration + Notification: "🐦 Pirol · 87% · 08:47"
       ↓
  User entscheidet: Notification antippen → Sonogramm öffnen → manuell speichern
  ```

### 1b. GPS-Georeferenzierung

Jede Detektion wird automatisch mit der aktuellen GPS-Position verknüpft. Ermöglicht räumliche Auswertung, Kartendarstellung und KML-Export.

- **Provider:** `FusedLocationProviderClient` (Google Play Services) für optimierten Akkuverbrauch
- **Aktualisierungsintervall:** Adaptiv — 10s bei Bewegung, 60s bei Stillstand
- **Genauigkeit:** Best-effort (GPS + WLAN + Cell), Accuracy-Wert wird mitgespeichert
- **Datenmodell:** Jede `DetectionResult` erhält optionale GPS-Felder:
  ```kotlin
  data class GeoPosition(
      val latitude: Double,
      val longitude: Double,
      val altitude: Double?,      // Meter über NN
      val accuracy: Float,        // Meter (Radius)
      val timestampMs: Long
  )
  ```
- **Batterie:** GPS nur aktiv während Recording-Session, nicht im Standby
- **Permission:** `ACCESS_FINE_LOCATION` + `ACCESS_COARSE_LOCATION` (Runtime-Permission wie Audio)

### 1c. Session-Management & Aufnahme-Persistierung

Kontinuierliche Aufnahmen werden in strukturierten Sessions auf Disk gespeichert. Ermöglicht spätere Analyse, Export und Cloud-Sync.

- **Session-Struktur (ab T46 — Merlin-Modell: eine WAV pro Session):**
  ```
  /sessions/
  └── 2026-04-19T08-23-12_a3f9c1/
      ├── session.json                   ← Metadaten (Gerät, Einstellungen, Dauer, totalRecordedSamples)
      ├── detections.jsonl               ← Eine Zeile pro Detektion, session-relative Zeitoffsets
      ├── verifications.jsonl            ← Verifikations-Events (CONFIRMED/REJECTED/CORRECTED/UNCERTAIN/REPLACED)
      └── audio/
          ├── recording.wav              ← EINE durchgehende WAV (48 kHz, 16-bit Mono, inkl. Preroll)
          └── recording.selections.txt   ← Raven Selection Table (on-demand via Export)
  ```
- **`recording.wav`:** Streaming-geschrieben via `StreamingWavWriter`, Header wird beim `endSession()` mit finaler `dataSize` aktualisiert. Preroll steht am Dateianfang.
- **JSONL-Format:** Eine Zeile pro Detektion — einfacher als JSON-Array, streambar. `chunkStartSec`/`chunkEndSec` enthalten session-relative Sekunden (Position in `recording.wav`):
  ```json
  {"id":"uuid","scientificName":"Turdus merula","commonName":"Amsel","confidence":0.94,"timestampMs":1711959791000,"chunkStartSec":43.0,"chunkEndSec":46.0,"sampleRate":48000,"latitude":47.123,"longitude":8.456,"verificationStatus":"CONFIRMED"}
  ```
- **Session-Lifecycle:** Start → Recording → Pause → Resume → Stop → Finalize (KML schreiben)
- **Speicherort:** App-interner Speicher oder konfigurierbar (externer Speicher mit SAF)

### 2. Sonogramm-Ansicht (Adaptive)

| Modus | Phone | Tablet |
|-------|-------|--------|
| **Live** | Rolling Sonogramm (letzte 30s), Artenliste unten | Split: Sonogramm links, Artenliste rechts |
| **Analyse** | Vollbild-Sonogramm, Bottom-Sheet für Details | Side-by-Side: Sonogramm + Detail-Panel |
| **Vergleich** | Swipe zwischen Aufnahme & Referenz | Dual-Pane: Aufnahme links, Referenz rechts |

- Mel-Spektrogramm mit konfigurierbaren Presets (Vögel: 125–7500 Hz)
- Pinch-to-Zoom, horizontales Scrollen
- Farbschema: Magma (Default), Viridis, Graustufen
- Annotation-Overlay: Tap-to-Mark mit Artenzuweisung

### 3. Offline-Klassifizierung (On-Device ML)

- **Primärmodell:** BirdNET-Lite (TFLite) für breite Arterkennung (~3'000 Arten)
- **Embedding-Modell:** EfficientNet-B0 (ONNX, ~20 MB) für Ähnlichkeitssuche
- **Lokale Embedding-DB:** Vorberechnete Embeddings für CH/DACH-Arten (~500 Arten, ~50 MB)
- **Fallback-Kaskade:**
  1. BirdNET-Lite Klassifizierung (schnell, breite Abdeckung)
  2. Embedding-Ähnlichkeitssuche gegen lokale DB (genauer, schmalere Abdeckung)
  3. Online: Xeno-Canto-Suche (wenn verfügbar)

### 4. Xeno-Canto-Referenzvergleich

- **Offline-Cache:** Top-5 Referenzaufnahmen pro Art (CH/DACH) beim Erststart herunterladen (~2 GB)
- **Online-Suche:** Erweiterte Suche bei Netzwerk-Verfügbarkeit
- **Vergleichs-UI:** Side-by-Side Sonogramme (Aufnahme vs. Referenz)
- **Similarity-Score:** Embedding-Distanz als verständlicher Prozentwert

### 5. Cloud-Sync (Drive-Push)

PIROL arbeitet vollständig offline — alle Aufnahmen, Klassifizierungen und Annotationen werden lokal gespeichert. Sobald WLAN verfügbar ist, werden die Daten automatisch per **Push** auf ein konfiguriertes Google Drive (oder anderes WebDAV/Cloud-Ziel) hochgeladen.

**Sync-Format pro Aufnahme:**
```
pirol-sync/
├── 2026-03-31_0847_waldrand/
│   ├── recording.wav          ← Original-Audio (oder komprimiert als .opus)
│   ├── metadata.json          ← Session-Metadaten
│   └── results.json           ← Klassifizierungs-Ergebnisse
```

**metadata.json:**
```json
{
  "id": "uuid-v4",
  "timestamp": "2026-03-31T08:47:12+02:00",
  "gps": { "lat": 47.3902, "lon": 8.0456, "accuracy_m": 5 },
  "duration_s": 342,
  "device": "Pixel 8",
  "pirol_version": "1.0.0",
  "settings": { "sample_rate": 48000, "preset": "birds" }
}
```

**results.json:**
```json
{
  "session_id": "uuid-v4",
  "detections": [
    {
      "species": "Turdus merula",
      "common_name": "Amsel",
      "confidence": 0.94,
      "time_start_s": 12.3,
      "time_end_s": 15.1,
      "model": "birdnet-lite-v2.4",
      "embedding_match": { "xc_id": "XC234567", "similarity": 0.87 }
    }
  ],
  "annotations": [ ... ]
}
```

**Sync-Verhalten:**
- **Trigger:** Nur bei WLAN (konfigurierbar: auch mobiles Netz)
- **Push-only:** PIROL schreibt, AMSEL/Desktop liest — kein bidirektionaler Sync
- **Inkrementell:** Nur neue/geänderte Sessions werden hochgeladen
- **Retry-Queue:** Fehlgeschlagene Uploads werden bei nächster Verbindung nachgeholt
- **Kompression:** Audio optional als Opus (~10× kleiner als WAV)

**Workflow „Vorsondieren":**
```
Feld (offline)          Zug/Zuhause (WLAN)         Desktop (AMSEL)
─────────────           ──────────────────          ────────────────
Aufnahme starten  →     WLAN erkannt          →     Drive-Ordner öffnen
Live-Erkennung          Auto-Push to Drive          Audio in AMSEL importieren
Annotationen setzen     Status: ✓ hochgeladen       Detailanalyse mit voller
Session beenden                                     Signalkette (8 Filter)
                                                    Embedding-Vergleich verfeinern
```

Typischer Use Case: Morgens im Feld aufnehmen, im Zug die Ergebnisse auf dem Handy vorsichten und annotieren, abends zuhause die interessanten Funde in AMSEL mit der vollen Desktop-Signalkette nachbearbeiten — die Daten liegen bereits auf dem Drive.

### 6. Export-Formate (KML + Raven Selection Table)

PIROL exportiert Beobachtungsdaten in offenen, standardisierten Formaten die direkt in GIS-Tools (QGIS, Google Earth) und Bioakustik-Tools (Cornell Raven, Audacity, Sonic Visualiser) nutzbar sind.

**KML-Export:**
- **LineString:** Gesamter GPS-Track der Session als Linie
- **Placemarks:** Jede Detektion als Punkt mit Artname, Confidence, Zeitstempel, Link zur Audio-Datei + Offset
- **Styling:** Farbcodierung nach Confidence (grün ≥80%, gelb 50–79%, rot <50%)

**Raven Selection Table (T47):**
Tab-getrennte `.txt`-Datei direkt neben `recording.wav`. Bioakustik-Standard von Cornell Lab — öffnet 1:1 in Raven, Audacity (Label-Track-Import) und Sonic Visualiser.

```
Selection  View            Channel  Begin Time (s)  End Time (s)  Low Freq  High Freq  Species            Common Name  Confidence  Status      Corrected Species
1          Spectrogram 1   1        12.345          15.345        0         12000      Parus major        Kohlmeise    0.873       CONFIRMED
2          Spectrogram 1   1        45.900          48.900        0         12000      Erithacus rubecula Rotkehlchen  0.910       REPLACED    Sylvia atricapilla
```

Enthält **alle** Detektionen als Audit-Trail — auch REPLACED/REJECTED sind durch die Status-Spalte transparent erkennbar.

**Share Intent:**
- Sonogramm als PNG exportieren (Compose Screenshot)
- Audio-Clip exportieren (3s-Chunk als WAV)
- Detektionsliste als CSV oder JSONL
- KML-Datei der aktuellen Session

### 7. Manueller Verifikations-Flow (erweitert in P20)

Citizen-Science-Kernloop: KI schlägt vor, Mensch entscheidet, Datenbank wächst. **Fünf Aktionen** pro Detektion:

- **Bestätigen (✓):** Detektion ist korrekt → `CONFIRMED`
- **Unsicher (?):** Detektion eventuell korrekt, zur Nachprüfung → `UNCERTAIN` (orange Rand)
- **Ablehnen (✗):** Fehlalarm → `REJECTED` (ausgegraut)
- **Korrigieren (✎):** User wählt korrekte Art aus Dropdown → `CORRECTED`
- **Alternative wählen:** Klick auf eine Kandidaten-Zeile ersetzt die Hauptart — Original wird als `REPLACED` ausgegraut mit rotem Rand + Badge "Ersetzt → <Artname>", die gewählte Alternative rückt als neue Detektion an Index 0

Jede Aktion schreibt ein `VerificationEvent` in `verifications.jsonl` (separate Persistierung, Rohdaten unverändert). Im Raven-Export ist der Status pro Detektion sichtbar.

**10-Sekunden Re-detection Rule (T44):** Eine bereits gesehene Art rückt nur dann wieder an Index 0 der Liste, wenn sie seit ≥10 Sekunden nicht mehr detektiert wurde. Ständig rufende Vögel (territoriale Amseln etc.) scrollen die Liste dadurch nicht mehr nervös durch.

**Lokale Referenz-DB:** Bestätigte Aufnahmen fliessen in die lokale Referenzbibliothek — über Zeit entstehen regionsspezifische Vergleichsdaten.

### 8. Upload-Backend (modular)

Pluggable Upload-Architektur — kein Lock-in auf einen Cloud-Anbieter.

```kotlin
interface UploadBackend {
    suspend fun upload(session: SessionDir): Result<Unit>
    fun isConfigured(): Boolean
    fun displayName(): String
}
```

- **Unterstützte Backends:** Google Drive (primär), S3, SFTP, WebDAV, HTTP/REST
- **WorkManager:** Constraint-basiert (WLAN), automatischer Retry bei Fehler
- **Sync-Queue:** Fehlgeschlagene Uploads werden bei nächster Verbindung nachgeholt
- **Kompression:** Audio optional als Opus (~10× kleiner als WAV)

---

## Offline-First — Prinzip

PIROL ist konsequent offline-first. Alle Kernfunktionen arbeiten ohne Netzwerk:

| Funktion | Offline | Online (optional) |
|----------|---------|-------------------|
| Aufnahme | ✓ Immer | — |
| Klassifizierung | ✓ On-Device ML | Erweiterte Modelle |
| Sonogramm | ✓ Immer | — |
| Referenzvergleich | ✓ Lokaler Cache | Xeno-Canto Live-Suche |
| Annotationen | ✓ Lokal (JSON) | — |
| Cloud-Sync | Queued | ✓ Push bei WLAN |

Netzwerk ist ein **Bonus**, nie eine Voraussetzung.

---

## Adaptive UI — Konzept

### Navigation (Phone)
```
┌─────────────────────┐
│     Live-Aufnahme    │  ← Haupt-Screen
│  ┌─────────────────┐ │
│  │  Rolling Sono   │ │
│  │                 │ │
│  └─────────────────┘ │
│  ┌─────────────────┐ │
│  │ Erkannte Arten  │ │
│  │ 🐦 Amsel  94%  │ │
│  │ 🐦 Drossel 71% │ │
│  └─────────────────┘ │
│                       │
│ [⏺ Rec] [📁] [⚙️]  │
├───┬───┬───┬───┬──────┤
│ 🎤│ 📊│ 📖│ 🗺️│ ⚙️  │  ← Bottom Nav
│Live│Ana│Ref│Map│Set  │
└───┴───┴───┴───┴──────┘
```

### Navigation (Tablet)
```
┌────────┬──────────────────────────────┐
│        │                              │
│  Nav   │     Content Area             │
│  Rail  │  ┌────────────┬────────────┐ │
│        │  │  Sonogramm │  Details   │ │
│  🎤    │  │            │            │ │
│  📊    │  │            │  Art: ...  │ │
│  📖    │  │            │  Conf: ... │ │
│  🗺️    │  │            │  Refs: ... │ │
│  ⚙️    │  └────────────┴────────────┘ │
│        │                              │
└────────┴──────────────────────────────┘
```

### Window Size Classes (Material 3)
- **Compact** (< 600 dp): Phone-Layout, Bottom Navigation, Single-Pane, Bottom-Sheets für Details
- **Medium** (600–840 dp): Kleine Tablets, optionales Side-Panel, grössere Touch-Targets
- **Expanded** (> 840 dp): Grosse Tablets, Navigation Rail + Dual-Pane, Sonogramm nutzt volle Breite

### Phone-spezifische Optimierungen
- Bottom Navigation (Daumen-erreichbar)
- Sonogramm: volle Bildschirmbreite, vertikales Scrollen für Artenliste
- Swipe-Gesten: Links/Rechts für Aufnahme vs. Referenz
- Kompakte Artenkarten (eine Zeile pro Art)
- FAB für Aufnahme-Start/Stop

### Tablet-spezifische Optimierungen
- Navigation Rail (links, persistent)
- Dual-Pane: Sonogramm + Detail-Panel nebeneinander
- Breiteres Sonogramm mit mehr Zeitkontext (60s statt 30s sichtbar)
- Drag-and-Drop für Annotationen
- Keyboard-Shortcuts für externe Tastaturen

---

## Architektur

```
pirol-android/
├── app/                          ← Android Application
│   ├── ui/
│   │   ├── live/                 ← Live-Aufnahme Screen
│   │   │   ├── LiveScreen.kt
│   │   │   └── LiveViewModel.kt
│   │   ├── analysis/             ← Analyse Screen (importierte Dateien)
│   │   │   ├── AnalysisScreen.kt
│   │   │   └── AnalysisViewModel.kt
│   │   ├── reference/            ← Xeno-Canto Referenzen
│   │   ├── map/                  ← Karte mit Beobachtungen
│   │   ├── settings/             ← Einstellungen
│   │   ├── components/           ← Shared Composables
│   │   │   ├── SpectrogramCanvas.kt
│   │   │   ├── SpeciesCard.kt
│   │   │   └── AdaptiveLayout.kt
│   │   └── theme/                ← Material 3 Dynamic Color
│   ├── audio/
│   │   ├── RecordingService.kt        ← Foreground Service (Binder + SharedFlow)
│   │   ├── OboeAudioEngine.kt        ← NDK Bridge (C++ RingBuffer + JNI)
│   │   ├── AudioChunk.kt             ← Datenmodell (ShortArray + Metadaten)
│   │   ├── AudioPermissionHandler.kt  ← Runtime-Permission Composable
│   │   └── dsp/                       ← Signalverarbeitung
│   │       ├── FFT.kt                 ← Radix-2 FFT (reines Kotlin)
│   │       ├── MelFilterbank.kt       ← Mel-Frequenzskala
│   │       ├── MelSpectrogram.kt      ← FFT → Mel → dB Pipeline
│   │       ├── AudioResampler.kt      ← Lineare Interpolation (48→32 kHz)
│   │       ├── SpectrogramConfig.kt   ← Presets (BIRDS/BATS/WIDEBAND)
│   │       └── WindowFunction.kt      ← Hann-Fenster
│   ├── ml/
│   │   ├── BirdNetV3Classifier.kt     ← ONNX Runtime (statt TFLite)
│   │   ├── InferenceWorker.kt         ← Chunk→Resample→Classify Pipeline
│   │   ├── InferenceConfig.kt         ← Confidence/TopK/Region Settings
│   │   ├── RegionalSpeciesFilter.kt   ← CH/DACH Artenfilter
│   │   ├── DetectionListState.kt      ← Thread-safe Detektions-Speicher
│   │   ├── DetectionResult.kt         ← Datenmodell pro Detektion
│   │   ├── ChunkAccumulator.kt        ← 3s-Block-Akkumulator
│   │   └── OnnxEmbeddingEngine.kt     ← (T12: EfficientNet Embeddings)
│   ├── data/
│   │   ├── db/                   ← Room / SQLDelight
│   │   ├── repository/           ← Single Source of Truth
│   │   └── sync/                 ← Offline-Cache Sync
│   ├── sync/
│   │   ├── DriveSyncWorker.kt    ← WorkManager Job (WLAN-Constraint)
│   │   ├── SyncQueue.kt          ← Pending Uploads Queue
│   │   └── SessionExporter.kt    ← Audio + JSON Packaging
│   └── di/                       ← Koin Modules
│
amsel-shared/                     ← KMP Shared (siehe oben)
```

### State Management
```
RecordingService (Audio-Stream)
        ↓ Flow<AudioChunk>
LiveViewModel
        ↓ combine()
LiveUiState (immutable data class)
        ↓ collectAsState()
LiveScreen (Compose)
```

---

## Nicht-funktionale Anforderungen

| Anforderung | Zielwert |
|-------------|----------|
| Erster Start (Cold) | < 3s |
| Aufnahme → Erkennung | < 500 ms |
| Batterie (1h Daueraufnahme) | < 15% Verbrauch |
| App-Grösse (APK) | < 80 MB (ohne Offline-Cache) |
| Offline-Cache (CH/DACH) | ~2 GB (optional, inkrementell) |
| Min. Android Version | API 26 (8.0 Oreo) |
| Sonogramm-FPS (Live) | ≥ 30 FPS |
| RAM-Budget | < 300 MB |

---

## Entwicklungs-Roadmap

### Phase 1 — Foundation (T1–T6, ABGESCHLOSSEN ✓)
- [x] Oboe NDK Audio-Engine (C++ RingBuffer + JNI)
- [x] RecordingService (Foreground) + Runtime-Permissions
- [x] FFT + Mel-Filterbank (reines Kotlin)
- [x] SpectrogramCanvas (Rolling Bitmap + Paletten)
- [x] LiveViewModel + Audio→DSP→Canvas Pipeline
- [x] Adaptive UI (Compact/Medium/Expanded + FAB-States)

### Phase 2 — Klassifizierung (T7–T11, ABGESCHLOSSEN ✓)
- [x] AudioResampler + BirdNET V3.0 ONNX Classifier
- [x] Inference-Pipeline (ChunkAccumulator → Resample → Classify)
- [x] SpeciesCard UI + DetectionList (Material 3)
- [x] Dual-Pipeline (DSP + ML parallel im ViewModel)
- [x] Confidence-Tuning + Regionale Artenfilterung (CH/DACH)

### Phase 3 — Embedding & Referenzen (T12–T13)
- [ ] T12: EfficientNet ONNX Embedding-Engine + Aehnlichkeitssuche
- [ ] T13: GPS-Service + Detektion georeferenzieren (FusedLocationProvider)

### Phase 4 — Persistierung & Export (T14–T16)
- [ ] T14: Session-Management (Ordnerstruktur, 10min WAV-Chunks, JSONL-Metadaten)
- [ ] T15: KML-Export (GPS-Track + Detektions-Placemarks) + Share Intent
- [ ] T16: Manueller Verifikations-Flow (Bestaetigen/Ablehnen/Korrigieren → lokale Referenz-DB)

### Phase 5 — Cloud-Sync & Upload (T17–T18)
- [ ] T17: Upload-Backend Interface (Drive/S3/SFTP/WebDAV) + WorkManager (WLAN-Constraint)
- [ ] T18: Lokale Referenzbibliothek (verifizierte eigene Aufnahmen + Xeno-Canto-Cache)

### Phase 6 — Xeno-Canto & Vergleich (T19–T20)
- [ ] T19: Xeno-Canto API Client (Ktor, Offline-Cache)
- [ ] T20: Side-by-Side Sonogramm-Vergleich (Aufnahme vs. Referenz)

### Phase 7 — Polish & Release (T21+)
- [ ] Species Alerts / Watchlist (Vibration + Notification)
- [ ] Energiesparmodus (adaptive Sample-Rate, groesseres Inferenz-Intervall)
- [ ] Onboarding-Flow
- [ ] Performance-Optimierung (Profiling, RAM-Budget)
- [ ] Accessibility (TalkBack, Schriftgroessen)
- [ ] Google Play Store Release (Beta → Public)

**Geschaetzte Gesamtdauer:** ~40 Wochen (1 Entwickler, Teilzeit mit Claude Code)
**Aktueller Stand (April 2026, T49):** P1–P20 abgeschlossen. 5 Tabs voll funktional. Workflow V0.0.4 vollständig implementiert (UNCERTAIN/REPLACED, 10s Re-detection, eine WAV pro Session, Raven-Export, Timeline-Wiedergabe, session-relative Zeitstempel). Nächste Schritte: Feldtest T46–T49 + T50 (Marker-Leiste, Cleanup).

---

## Risiken & Mitigationen

| Risiko | Impact | Mitigation |
|--------|--------|------------|
| ONNX Runtime Mobile zu langsam | Echtzeit nicht möglich | Fallback auf TFLite; GPU-Delegate testen |
| Batterieverbrauch zu hoch | Felduntauglichkeit | Adaptive Sample-Rate; Inference nur bei Aktivität |
| Oboe-Integration komplex | Verzögerung Phase 1 | Fallback auf AudioRecord (höhere Latenz, aber funktional) |
| BirdNET-Lizenz unklar für Mobile | Rechtliches Risiko | Eigenes Modell trainieren oder BirdNET-Team kontaktieren |
| Offline-Cache zu gross | Nutzerakzeptanz | Inkrementeller Download, regionale Pakete (CH: ~200 MB) |

---

## Bezug zu AMSEL

PIROL ist keine Portierung, sondern ein eigenständiges Produkt für den **Feld-Einsatz**. AMSEL bleibt das Profi-Tool für **Studio-Analyse** am Desktop. Durch KMP teilen beide Projekte die Core-Algorithmen:

```
┌──────────────┐         ┌──────────────┐
│   AMSEL      │         │   PIROL      │
│   Desktop    │         │   Mobile     │
│              │         │              │
│  Studio-     │         │  Feld-       │
│  Analyse     │◄────────│  Erkennung   │
│              │  Drive  │              │
│              │  (JSON  │              │
│              │  +Audio)│              │
└──────┬───────┘         └──────┬───────┘
       │                        │
       └────────┬───────────────┘
                │
        ┌───────┴───────┐
        │ amsel-shared  │
        │    (KMP)      │
        │               │
        │ FFT, Mel, ML  │
        │ Similarity    │
        │ Xeno-Canto    │
        │ Modelle       │
        └───────────────┘
```

**Datenfluss Feld → Desktop:** PIROL pushed Audio + JSON auf Google Drive (bei WLAN). AMSEL importiert direkt aus dem Drive-Ordner — inklusive Klassifizierungsergebnisse, GPS-Koordinaten und Annotationen aus dem Feld.

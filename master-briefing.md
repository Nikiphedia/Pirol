# PIROL — Master-Briefing

**Projekt:** PIROL (Practical In-field Recognition of Organic Life)
**Typ:** Android-App zur akustischen Echtzeit-Artenerkennung
**Package:** `ch.etasystems.pirol`
**Pfad:** `D:\80002\PIROL`
**Schwester-Projekt:** AMSEL Desktop (`D:\80002\AMSEL`)

---

## Projektstand

| Phase | Tasks | Status |
|-------|-------|--------|
| P1 Audio-Engine | T1 Oboe NDK + T2 RecordingService | ✅ |
| P2 DSP-Pipeline | T3 FFT/Mel + T4 SpectrogramCanvas + T5 LiveViewModel | ✅ |
| P3 Adaptive UI | T6 Layouts + Chips + FAB | ✅ |
| P4 ML-Klassifikation | T7 Resampler/BirdNET + T8 InferencePipeline + T9/T10 SpeciesCard/DualPipeline | ✅ |
| P5 Tuning | T11 Confidence/Filter/Regionen | ✅ |
| P6 Embedding-Engine | T12a Pipeline-Integration ✅ · T12b Embedding-UI ✅ | ✅ |
| P7 GPS + Sessions | T13 GPS ✅ · T14 Sessions ✅ | ✅ |
| P8 Export + Verifikation | T15 KML ✅ · T16 Verifikation ✅ | ✅ |
| P9 Upload + Referenzen | T17 Upload ✅ · T18 Referenzbibliothek ✅ | ✅ |
| T19 Smoketest | Emulator-Integrationstest, 0 Crashes | ✅ |
| P10 Species-Alarm | T20 Watchlist ✅ · T21 Alarm-UI ✅ | ✅ |
| P11 Onboarding + Config | T22 Onboarding + Labels + Settings-Persistenz ✅ | ✅ |
| P12 Analyse-Fenster | T23 Browser ✅ · T24 Dual-Vergleich ✅ · T25a Smoketest ✅ | ✅ |
| P14 Detektions-UX | T26 Mehrsprachig ✅ · T27 Dedup+Kandidaten ✅ | ✅ |
| P15 Modell-Management | T28 Classifier-IF+Download ✅ · T29 Energiesparmodus ✅ | ✅ |
| P16 UX-Polish | T30 Settings-Umbau ✅ · T31 Karte ✅ | ✅ |
| P17 Feld-Bugfixes | T32 Artennamen+Rotation ✅ · T33 Detektionsliste ✅ | ✅ |
| P18 Audio+Storage | T34 HPF+Norm · T35 Preroll · T36 Loeschen · T37 Modell · T38 SD · T39 ChunkJump · T40 Cleanup · T41 Migration | ✅ |
| P19 Code-Scan+Fix | T42 Scan (8 Funde) · T43 6 Fixes (Build gruen) | ✅ |
| P20 Workflow-Logik | T44 UNCERTAIN+10s-Rule ✅ · T45 Alternative ersetzt Hauptart ✅ | ✅ |

**Letzter erfolgreicher Build:** T43 — `compileDebugKotlin` BUILD SUCCESSFUL

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
        │         → DetectionResult + GPS (lat/lon) → DetectionListState (Dedup 10s)
        │         → LazyColumn<SpeciesCard>
        └── [GPS] LocationProvider (FusedLocation, 10s) → StateFlow<LocationData?>
                  → LiveUiState (LocationBar) + DetectionResult.copy(lat, lon)
```

### Embedding-Arm (T12a/T12b):
```
        └── [EMB] InferenceWorker Callback → EmbeddingExtractor (ONNX 1024-dim / MFCC 43-dim)
                  → EmbeddingDatabase (Binary BSED, filesDir/embeddings/embeddings.bsed)
                  → findSimilar() → LiveUiState.similarMatches → SimilarityPanel
```

### Session-Persistenz (T14):
```
        └── [SES] SessionManager: startSession() / endSession()
                  → filesDir/sessions/{iso-date}_{uuid6}/
                      ├── session.json   (SessionMetadata, @Serializable)
                      ├── detections.jsonl (DetectionResult pro Zeile)
                      └── audio/chunk_NNN.wav (3s, 16-bit PCM Mono)
```

---

## UI-Layout

- **Phone Portrait:** Column — Config-Chips, InferenceConfig (Slider+Chips), Sonogramm 200dp, DetectionList, FAB
- **Phone Landscape:** Row 60/40 — Sonogramm links, DetectionList rechts
- **Tablet:** Row 60/40 + NavigationRail links
- 5 Tabs: Live ✅ | Analyse ⬜ | Referenzen ✅ | Karte ⬜ | Settings ✅

---

## Offene Punkte / Bekannte Limitierungen

1. **BirdNET-Modell** — `birdnet_v3.onnx` ist Platzhalter, muss manuell in `assets/models/` platziert werden
2. **Labels-Datei** — 25 Platzhalter-Arten statt 11'560 (echte CSV noch nicht eingebunden)
3. **species_master.json** — Noch nicht in PIROL
4. **InferenceConfig** — Nicht persistiert (Reset bei Neustart)
5. **Confidence-Kalibrierung** — Preset-Werte (0.3/0.5/0.7) sind Schätzwerte
6. **Graceful Degradation** — App läuft ohne Modell, zeigt "Modell nicht installiert" (T19 verifiziert)
7. **viewModelScope in onCleared** — Session/Embedding-Save könnte bei App-Kill abbrechen
8. **Analyse-Tab** — Platzhalter, kein Session-Replay oder Sonogramm-Vergleich
9. **Karte-Tab** — Platzhalter, keine Map-Integration
10. **Species-Alarm** — Kein Watchlist/Trigger-System fuer Zielarten
11. **Externer Datenaustausch** — Alles in `context.filesDir`, kein SD-Karten-Scan oder JSON-Import
12. **Koin Compose-Warning** — KoinContext nicht gesetzt (nicht kritisch, T19 Beobachtung)

---

## AMSEL-Referenz für T12

AMSEL implementiert ein vollständiges Embedding- und Similarity-System:

### Relevante AMSEL-Klassen:

| Klasse | Funktion | PIROL-Relevanz |
|--------|----------|----------------|
| `EmbeddingExtractor` | ONNX-Embedding + MFCC-Fallback (43-dim) | **Direkte Vorlage** |
| `EmbeddingDatabase` | Binary BSED Format, Brute-Force Cosine | **Direkte Vorlage** |
| `SimilarityEngine` | Offline-First, Xeno-Canto Fallback | Teilweise (kein Online-Fallback in T12) |
| `MfccExtractor` | 13 MFCC + Delta + Delta-Delta, CMVN | **Direkte Vorlage** |
| `CosineSimilarityMetric` | 26-dim MFCC Summary | Einfachste Variante |
| `DtwSimilarityMetric` | 78-dim + DTW 2-Phase | Zu aufwändig für Mobile |
| `OnnxBirdNetV3` | 32kHz Input, Embeddings + Predictions Output | Bereits in PIROL (BirdNetV3Classifier) |

### AMSEL Embedding-Parameter:
- ONNX: Input `[1, 1, 64, nFrames]`, Output = Logits-Vektor
- MFCC-Fallback: 39 Enhanced (13×3 Deltas) + 4 Spectral = 43 Dimensionen
- Alle Embeddings: L2-normalisiert
- Speicherformat: Binary "BSED" (Header 16 Bytes + Entries)
- Suche: Brute-Force Cosine (skaliert bis ~100k Einträge)

---

## Roadmap (ab T12)

| Task | Inhalt | Abhängigkeit |
|------|--------|-------------|
| T12a | ~~Embedding-Pipeline Integration + Build-Fix~~ ✅ | T7/T8 (BirdNET ONNX) |
| T12b | ~~Embedding-UI (findSimilar, MatchResult-Anzeige)~~ ✅ | T12a |
| T13 | ~~GPS-Service + Detektion georeferenzieren~~ ✅ | T8 |
| T14 | ~~Session-Management (Ordner, WAV-Chunks, JSONL)~~ ✅ | T13 |
| T15 | ~~KML-Export + Share Intent~~ ✅ | T14 |
| T16 | ~~Verifikations-Flow (Bestätigen/Ablehnen/Korrigieren)~~ ✅ | T12 + T14 |
| T17 | ~~Upload-Backend Interface + WorkManager~~ ✅ | T14 |
| T18 | ~~Lokale Referenzbibliothek (verifizierte Aufnahmen)~~ ✅ | T12 + T16 |

---

## Rotations-Log

| Datum | Grund | Was der nächste Master zuerst lesen soll |
|-------|-------|----------------------------------------|
| 2026-04-04 | Neuer Master, Briefing + CLAUDE.md erstellt | master-briefing.md + CLAUDE.md, dann T12 planen |
| 2026-04-04 | T12a abgeschlossen (Build-Fix + Embedding-Pipeline) | Handover_T12a.md lesen, T12b planen |
| 2026-04-04 | T12b abgeschlossen (findSimilar + SimilarityPanel) | P6 komplett, T13 (GPS) naechster Task |
| 2026-04-04 | T13 abgeschlossen (GPS + Georeferenzierung) | P7 halb, T14 (Sessions) naechster Task |
| 2026-04-04 | T14 abgeschlossen (Sessions + WAV + JSONL) | P7 komplett, T15 (KML-Export) naechster Task |
| 2026-04-05 | T15 abgeschlossen (KML-Export + FileProvider + Share Intent) | T16 (Verifikation) naechster Task |
| 2026-04-05 | T16 abgeschlossen (Verifikations-Flow) | P8 komplett, T17 (Upload-Backend) naechster Task |
| 2026-04-05 | T17 abgeschlossen (WorkManager + LocalExport + Settings-UI) | T18 (Referenzbibliothek) letzter Task der Roadmap |
| 2026-04-05 | T18 abgeschlossen (Referenzbibliothek + AudioPlayer) | ROADMAP P1–P9 KOMPLETT. Smoketest auf Geraet als naechstes. |
| 2026-04-05 | **T19 Smoketest BESTANDEN** — 0 Crashes, 0 Fixes, alle Quality Gates gruen | App ist feldtauglich. Naechstes: BirdNET-Modell einbinden fuer ML-Test. |
| 2026-04-05 | T20 abgeschlossen (Watchlist + AlarmService + Downloads/PIROL/ Scanner) | T21 (Alarm-Cooldown + Reload-Button) naechster Task |
| 2026-04-05 | T21 abgeschlossen (SAF + Cooldown + Editor + Flows) | P10 komplett. T22 Onboarding naechster. |
| 2026-04-05 | T22 abgeschlossen (Onboarding + 11'560 Labels + SharedPreferences) | P11 komplett. T23 Analyse-Tab naechster. xenoCantoApiKey fehlt — spaeterer Task. |
| 2026-04-05 | T23 abgeschlossen (Session-Browser + Sonogramm-Replay + Verifikation) | T24 naechster. |
| 2026-04-05 | T24 abgeschlossen (Dual-Sonogramm + Dual-Playback + Referenz-Auswahl) | T25 Finaler Smoketest — letzter Task vor Feldtest! |
| 2026-04-05 | T25a Smoketest Teil 1 bestanden (ADB-only, 0 Crashes) | Emulator-Limits — UI-Test manuell noetig |
| 2026-04-05 | T26–T31 abgeschlossen (Sprache, Dedup, Classifier-IF, Energie, Settings, Karte) | P14–P16 komplett |
| 2026-04-06 | Erster Feldtest — App laeuft, erkennt Arten | Sonogramm-Default auf GRAYSCALE gefixt |
| 2026-04-07 | ROTATION — Kontext voll. Feld-Feedback in `Pirol_Pull_V0.0.3.md` | Naechster Master: Pull-Datei lesen, 9 APs formulieren, offene Entscheidungen klaeren |
| 2026-04-08 | P18 komplett (T34–T41). Alle Reviews bestanden, Build gruen. | — |
| **2026-04-08** | **P19 komplett (T42–T43). Code-Scan 8 Funde, 6 gefixt, 2 akzeptiert. ROTATION.** | Naechster Master: Scan_T42.md lesen (Beobachtungen), findCommonName() toter Code offen. Offene Pendings: BOM-Upgrade (menuAnchor), Preroll 96kHz (Feldtest) |
| **2026-04-14** | **P20 geplant (T44–T45). Workflow-Dokument analysiert, Arbeitspakete formuliert.** | T44 (UNCERTAIN + 10s Re-detection) zuerst; T45 (Alternative ersetzt Hauptart + REPLACED) danach. Prompt_Thread_Uebergabe.md aktualisiert. |
| 2026-04-14 | T44 abgeschlossen + reviewed (UNCERTAIN + 10s Re-detection Rule, Build gruen). Worker fand+fixte zusaetzlich einen Bug: verificationStatus wurde bei Re-detection mit hoeherer Confidence ueberschrieben. | T45 (Alternative ersetzt Hauptart + REPLACED) naechster Task |
| 2026-04-14 | **T45 abgeschlossen + reviewed. P20 komplett — Workflow-Dokument V0.0.4 vollstaendig implementiert.** Build gruen. | Feldtest der neuen Interaktions-Logik (?-Button, Alternative-Auswahl, 10s-Regel). |
| 2026-04-22 | **V0.0.6-WIP: T54 + T51a/b/c + T52 + T56 + T56b + T53 committet auf master.** Build gruen, 12 Unit-Tests gruen, Feldtest-Abnahme abend offen. Rotations-Grund: neuer Master ("Pirolmaster 6") uebernimmt. `.gitignore` ergaenzt: T*_Arbeitspaket.md / Master_Uebergabe*.md / Prompt_Thread_Uebergabe.md nicht mehr auf GitHub. | PROJEKT_UEBERBLICK.md lesen. Offen: T54b Fairphone (User-Prio aus), T55 im Feldtest verifizieren (20x Start), BDA V006 nach Abnahme, dann Tag v0.0.6. V0.0.7-Ideenspeicher: T57/T58/T59. |
| 2026-04-22 | **V0.0.6 RELEASE.** T57 Block B nach Feldtest-OK mitgenommen: Session-Rotation (10-80min Slider, `recordingSegments` in session.json), WAV-Header periodisch geupdatet (Windows-kompatibel bei Prozess-Kill), Raven-TSV um `Begin File` + `Begin Date Time` + Low=150 Hz erweitert, FAB-Farben korrigiert (PREROLL gruen, RECORDING rot). Tag `v0.0.6` auf master, push nach origin. | Naechster Master: V0.0.7 planen. Ideenspeicher T58/T59/T60 (Map V2, Canvas-Merlin-Skalierung, BirdNET Multi-Label + Per-Species-Freq). T54b Fairphone weiter auf Hold. |

---

## Roadmap P10–P13 (Feature-Erweiterung)

| Phase | Tasks | Status |
|-------|-------|--------|
| **P10 Species-Alarm + Watchlist** | T20 Watchlist-Modell + JSON-Import, T21 Alarm-UI + Notification | **⏳ T20 NÄCHSTER** |
| **P11 Externer Datenaustausch** | T22 SD/Downloads Scanner, T23 AMSEL-kompatibles Config-Format | ⬜ |
| **P12 Analyse-Fenster** | T24 Session-Browser + Replay, T25 Audio-Playback + Sonogramm | ⬜ |
| **P13 Sonogramm-Vergleich** | T26 Dual-Sonogramm (AMSEL CompareScreen Referenz) | ⬜ |

### P10 — Species-Alarm + Watchlist
- `watchlist.json`: Liste von Zielarten die bei Detektion Alarm ausloesen
- Import via SD-Karte/Downloads: `/sdcard/PIROL/watchlist.json` oder `Downloads/PIROL/watchlist.json`
- Bei Detektion einer Watchlist-Art → Vibration + Notification + optionaler Sound
- Editierbar in AMSEL Desktop (JSON-Editor) → auf Handy uebertragen

### P11 — Externer Datenaustausch (SD-Karte + JSON-Import)
- App scannt beim Start `/sdcard/PIROL/` und `Downloads/PIROL/` nach Config-Dateien
- Import: `watchlist.json`, `config.json` (InferenceConfig, Region, etc.)
- Export: Sessions direkt auf SD-Karte statt nur `filesDir`
- AMSEL-kompatibles Austauschformat (JSON-basiert)
- User-Workflow: Am Rechner in AMSEL editieren → JSON auf SD → PIROL scannt und nutzt

### P12 — Analyse-Fenster (Session-Replay)
- Session-Browser: Liste aller gespeicherten Sessions (sortiert, mit Metadaten)
- Session oeffnen: Detektionen + Sonogramm aus WAV-Chunks rekonstruieren
- Timeline-Navigation: Scrollen durch 3s-Chunks
- Verifizierung nachtraeglich (gleiche Buttons wie LiveScreen)

### P13 — Sonogramm-Vergleich
- Dual-Sonogramm-View: Detektion oben, Referenz unten (wie AMSEL CompareScreen)
- Audio-Playback synchron (Detektion und Referenz nebeneinander hoeren)
- Frequenz/Zeit-Zoom
- AMSEL `CompareScreen.kt` als Architektur-Vorlage

### Roadmap Details

| Task | Inhalt | Abhaengigkeit |
|------|--------|---------------|
| T20 | ~~Watchlist-Datenmodell + JSON-Import aus Downloads/PIROL/~~ ✅ | T11 (InferenceConfig) |
| T21 | ~~Alarm-UI: SAF + Cooldown + Editor + Reaktive Flows~~ ✅ | T20 |
| T22 | ~~Onboarding + Modell-Download + Labels 11'560 + Settings-Persistenz~~ ✅ | T11 |
| T23 | ~~Session-Browser + Replay (AnalysisScreen)~~ ✅ | T14 (Sessions) |
| T24 | ~~Dual-Sonogramm-Vergleich (Detektion vs Referenz)~~ ✅ | T23 + T18 |
| **T25** | **Finaler Smoketest (Onboarding + ML E2E + Analyse + Vergleich)** | T24 |

---

## Roadmap P14–P16 (Feld-Feedback 2026-04-05)

| Phase | Tasks | Status |
|-------|-------|--------|
| P14 Detektions-UX | T26 Mehrsprachig ✅ · T27 Dedup+Kandidaten ✅ | ✅ |
| P15 Modell-Management | T28 Classifier-IF+Download ✅ · T29 Energiesparmodus ✅ | ✅ |
| P16 UX-Polish | T30 Settings-Umbau ✅ · T31 Karte ✅ | ✅ |
| P17 Feld-Bugfixes | T32 Artennamen+Rotation ✅ · T33 Detektionsliste ✅ | ✅ |

### Feld-Feedback Detail

| # | Feedback | Task |
|---|----------|------|
| 1 | Sprache der Arten nicht waehlbar (23 Sprachen in species_master.json) | T26 |
| 2 | Sonogramm nicht erkennbar im Dark Mode | ✅ gefixt (Default → GRAYSCALE) |
| 3 | Scan-Einstellungen gehoeren in Settings | T30 |
| 4 | Karte fehlt | T31 |
| 5 | Art mehrfach gelistet (Dedup zu kurz?) | T27 |
| 6 | Alternative Arten anzeigen (Top-N Kandidaten) | T27 |
| 7 | Energiesparsamkeit (FP16, Inference-Intervall) | T29 |
| 8 | Modell-Download im Onboarding (Zenodo) | T28 |

### Roadmap Details P14–P16

| Task | Inhalt | Abhaengigkeit |
|------|--------|---------------|
| T26 | ~~Mehrsprachige Artnamen~~ ✅ | — |
| T27 | ~~Dedup + Top-N Kandidaten~~ ✅ | T26 |
| T28 | ~~Classifier-Interface + Modell-Download~~ ✅ | — |
| T29 | ~~Energiesparmodus~~ ✅ | T28 |
| T30 | ~~Settings-Umbau~~ ✅ | — |
| T31 | ~~Karte-Tab (osmdroid)~~ ✅ | T13 |
| T32 | ~~Artennamen-Bug + Rotation + Dark Mode Landscape~~ ✅ | T26 |
| T33 | ~~Detektionsliste UX (Dedup, Highlight, Clear)~~ ✅ | T27 |
| T34 | ~~Audio-Pipeline: Peak-Norm + HPF (Toggle, 200Hz)~~ ✅ | — |
| T35 | ~~Preroll-Puffer konfigurierbar (5s/10s/30s)~~ ✅ | — |
| T36 | ~~Sessions + Referenzen loeschen~~ ✅ | — |
| T37 | ~~Modell-Management Fix + Settings-Umschaltung~~ ✅ | T28 |
| T38 | ~~Speicherort waehlbar (Intern + SD-Karte)~~ ✅ | — |
| T39 | ~~Analyse-Tab: Chunk-Navigation per Art~~ ✅ | T23 |
| T40 | ~~Cleanup: Deprecation + Konstante + getSpeciesCount Bug~~ ✅ | — |
| T41 | ~~Session-Migration bei Speicherort-Wechsel~~ ✅ | T38 |

---

## Ideenspeicher [Master only]

- **Xeno-Canto Integration:** Ktor API Client + ReferenceDownloader (AMSEL als Vorlage)
- KMP-Extraktion: Core-Module zwischen AMSEL und PIROL teilen
- Bat-Modus: Ultraschall-Erkennung (>15kHz), eigenes Embedding-Modell
- Wear OS Companion: Notifications auf Smartwatch
- WorkInfo-Observer: UploadManager Status-Tracking via WorkManager LiveData
- Autocomplete im Korrektur-Dialog (RegionalSpeciesFilter als Quelle)
- BirdNET V2.4 TFLite Runtime (Alternative zu ONNX-Konvertierung)
- xenoCantoApiKey Feld in Settings (ging bei T22 unter)

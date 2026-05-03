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
| **P21 V0.0.7 Block A — Bugs+Hold-Stack** | T58 Watchlist/Alarm ✅ · T59 Ordnerwechsel ✅ · T60 Auto-Loeschen<10s ✅ · T61 Verif-Status instant ✅ · T62 Analyse-Player ✅ · T63 T54b Fairphone ✅ · T64 T55 Recording-Start ✅ · T65 Crash-Recovery ✅ · T66 BDA V006 ✅ | ✅ |
| **P22 V0.0.7 Fehlerlog-Bugfixes** | T76 Lifecycle+Preroll+LongPress ✅ · T77 Button-Layout+Player-Symbol ✅ · T78 Raven-UX+Icon-Konsistenz ✅ | ✅ |
| **P23 V0.0.7 Cluster D — Karte V2** | T79 Session-Filter+Live-Anzeige+Position-Move (D1) ✅ | 🔄 |

**Letzter erfolgreicher Build:** T79 — `compileDebugKotlin` + `test` + `assembleDebug` BUILD SUCCESSFUL

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
| **2026-04-26** | **Pirolmaster 7 uebernimmt. Changerequest V0.0.7 vom User (40 Punkte) in Cluster A-I gegliedert. V0.0.7-Reihenfolge bestaetigt: Block A (Bugs+T54b+T55+BDA) → C (manuelle Erfassung) → D (Karte V2) → B (Detektions-Logik) → E (Player+Chunk-Workflow, vorgeschaltet Disk-Pipeline-Spike). Cluster H Xeno-Canto/Cloud → V0.0.8. Worker-Konventionen festgelegt: 60k-Token-Limit pro Worker, Test-Build-Naming `V{Release}.T{N}` (z.B. `V0.0.6.T58`), File-Indexer-Worker vor jedem AP-Cluster.** | T58 (Watchlist/Alarm-Audit + Fix) als kleinste isolierte Einheit zuerst. T59-T66 in Block A folgen. Plan-Datei: `C:\Users\nleis\.claude\plans\c-users-nleis-documents-claude-prompts-cozy-barto.md`. |
| **2026-05-03** | **Cluster D1 T79 abgeschlossen. Tag `V0.0.6.T79`.** MapViewModel+MapScreen Rewrite: 3× FilterChip (Alle/Nur Live/Letzte 3), Live-Arm via `snapshotFlow` auf `DetectionListState.version` (reaktiv, kein Polling), blaue Live-Marker + rote historische Marker (programmatisch via Canvas). Long-Press → `MarkerLongPressOverlay` (<50px Distanz) → `AlertDialog` mit Lat/Lon-Feldern → `SessionManager.recordPositionMoved()` schreibt `ACTION_POSITION_MOVED` in Session-`verifications.jsonl`. AppModule: `viewModel { MapViewModel(get(), get()) }`. **Worker-Beobachtungen:** `DetectionListState.detections` war privat — `snapshotFlow` auf `version: State<Int>` als Workaround (korrekt+reaktiv). `appendVerificationEvent()` nur für aktive Session — `recordPositionMoved()` nutzt direktes `File.appendText()` (SAF-Lücke dokumentiert). osmdroid-bonus-pack nicht im Classpath — eigener `MarkerLongPressOverlay`. `compileDebugKotlin` + `test` (45 Tasks) + `assembleDebug` gruen. | **Naechstes:** D2 (Marker-Symbol Kreis + Art-Farben-JSON). Offene Backlog-Items: Memo-Button onClick-Fix (F6b), T80 Persistenz-Haertung. |
| **2026-05-02** | **Fehlerlog-Bugfixes T76-T78 abgeschlossen. Tags `V0.0.6.T76` + `V0.0.6.T77` + `V0.0.6.T78`. Pirolmaster 8.** T76: `observeRecordingState()` mit `!sessionManager.isActive`-Guard (F1+F2 Tab-Wechsel-Bug + Detektionen erscheinen nicht); `initPrerollIfEnabled()` aus `onServiceConnected` entfernt, neuer Preroll-SmallFAB + `userStartPreroll()`/`userStopPreroll()` (F5); Stop-FAB auf `combinedClickable` Long-Press 2s umgestellt (F7). T77: +Art- und Memo-Buttons in Bottom-Bar neben FAB verschoben, nur bei Aufnahme sichtbar (F6); `onJumpToChunk`-Symbol aus AnalysisScreen entfernt, nur PlayArrow bleibt (F4). **T77-Beobachtung: Memo-Button hat `onClick = {}` — funktioniert nicht, Folge-AP noetig.** T78: Raven-Datei-Vorschau-Dialog (F3a); 10 neue AppPreferences `pirol_raven_field_*` + Settings-Toggle-Sektion + `RavenExporter`-Refactor mit `enabledFields`-Parameter + `SessionManager`-Update (F3c); Undo-Icon bei CONFIRMED SpeciesCard + Detail-Button auf 56dp (F3d). Build: `compileDebugKotlin` + `test` + `assembleDebug` gruen. **Worktree-Disziplin erneut gebrochen:** T76+T77 direkt auf master committed (kein eigener Branch). T78 als uncommitted Working-Tree-Aenderung geliefert — Master committed. | **Naechster Schritt: Cluster D (Karte V2) planen.** Vorab: Memo-Button-Followup (T79) als Mini-AP einplanen. Persistenz-Haertung-Backlog (a,b,c,g,h) als T80. Cluster-D-APs D1-D5 formulieren. Entscheidungen getroffen: RadiusMarkerClusterer, Swisstopo WMTS `wmts.geo.admin.ch`, Map-Verwaltung JSON-Asset. |
| **2026-04-29** | **Cluster B KOMPLETT (T73 Lock-Species). Tag `V0.0.6.T73`. Cluster A+C+B alle abgeschlossen.** T73: `DetectionListState.lockedSpecies` StateFlow + `isLocked()` + `rebuildLockedSpecies()` (Session-Reload), `getDetections()` sortiert Lock-First. LiveViewModel.toggleLock() mit Mutex+Rollback; Inference-Loop verwirft gesperrte Arten VOR `addOrCluster` (kein detections.jsonl-Eintrag, kein UI-Update bei Suppression). AnalysisViewModel.toggleLock() mit direktem JSONL-Write. SpeciesCard: combinedClickable Long-Press → DropdownMenu Sperren/Entsperren, Lock-Badge als 5. Element (Watchlist→Manuell→Saison→Selten→Lock), surfaceVariant-Background bei `lockSpecies`. ACTION_SPECIES_LOCKED in verifications.jsonl mit `newValue="true"/"false"`. 4 neue Tests. `compileDebugKotlin` + `test` + `assembleDebug` gruen. **Pirolmaster 7 schliesst ab + uebergibt.** Cluster A+C+B-Strecke (T58-T75 ohne Luecken) komplett gemerged, Tags V0.0.6.T58 bis V0.0.6.T75 gesetzt (18 Tags). | **Pirolmaster 8 startet:** `Pirolmaster_Uebergabe.md` + `master-briefing.md` + `Pirol_Changerequest_V0.0.7.md` + `CLAUDE.md` lesen, dann **Cluster D (Karte V2) planen**. Vor erstem D-AP Master-Entscheidungen: osmdroid `RadiusMarkerClusterer` als Cluster-Library bestaetigen, Swisstopo WMTS-URL + Lizenz-Konditionen pruefen. **B5 Simultanbeobachtung** (aus Cluster B verschoben) ist Teil von Cluster D. **Folge-APs nach Cluster B:** T76 Persistenz-Haertung, T77 UI-Polish, T78 Modell-Manifest-Update (User-Bugreport BirdNet-Download). |
| **2026-04-29** | **Cluster B grosser Wurf: T72 + T74 + T75 gemerged. Tags `V0.0.6.T72` + `V0.0.6.T74` + `V0.0.6.T75`.** T72 (B3 Manuell+Alternative): `AddManualDetectionDialog` mit Art-Autocomplete via `SpeciesNameResolver.searchSpecies`, GPS-Snapshot, `DetectionListState.addManualDetection` (Index 0, kein Cluster), ACTION_MANUAL_ADD + ACTION_ALTERNATIVE_CONFIRMED in beiden VMs, PanTool-Badge bei `manualEntry`, "+ Art"-Button in Live-Tab (3 Layouts) + Analyse-Tab; AnalysisVM-Konstruktor um SpeciesNameResolver erweitert (Position 6, AppModule angepasst); 7 Tests. T74 (B6 Saisonalitaet): `species_phenology.json` (Asset, leer), `PhenologyRepository` (Singleton, lazy, graceful), AppPreferences `phenologyEnabled` Default false, Saison-untypisch-Badge orange + Star-Icon bei rarity>0.7 in SpeciesCard, Settings-Sektion mit Toggle + Reload-Knopf; 7 Tests. T75 (B7 Aehnliche Arten): Audit-Befund Mehrfach-Nennungen aus EmbeddingDatabase (per recordingId-Key, kein Art-Dedup), Refactor mit DE-Namen via SpeciesNameResolver + Dedup pro Art + Settings-Slider 0..10 (0.5=Marquee-Mode mit letzten 5 Detektionen, 1+=Listen-Mode), Toggle + AppPreferences similarEnabled/similarLines, Header umbenannt "Aehnliche Aufnahmen" → "Aehnliche Arten"; 6 Tests. **Konflikte:** SpeciesCard.kt (T72 PanTool + T74 Phenology-Badges in selber Row) + AppPreferences.kt (T74+T75 beide am Ende neue Properties) — beide manuell als additive Block-Konkatenation aufgeloest. `compileDebugKotlin` + `test` + `assembleDebug` gruen (~85 Tests gesamt). | **T73 (Lock-Species, B4) als letzter Cluster-B-AP** — fasst LiveViewModel + SpeciesCard + DetectionList an, sequenziell nach T72. Vor Dispatch T73-AP mit T72/T74/T75-Lessons aktualisieren. **T74-Beobachtung:** Live-View Caller wurde nicht angepasst (DetectionList bekommt phenologyRepository=null Default) — Mini-Folge-Task fuer Live-Phenology-Wiring. **Naechste Master-Entscheidungen vor Cluster D:** osmdroid `RadiusMarkerClusterer`, Swisstopo WMTS-URL + Lizenz. |
| **2026-04-28** | **Block B2 (T71) gemerged. Tag `V0.0.6.T71`.** Worker-Befund: Multi-Label-Pipeline war seit T70 bereits funktional (`addOrCluster` pro Detection + Classifier returnt Liste). T71-Arbeit: `BirdNetV3Classifier.filterAndRank()` als testbare Companion-Funktion extrahiert (Verhalten unveraendert), `InferenceConfig.topKThreshold: Float = 0.3f` separat von `confidenceThreshold` (semantische Trennung: Detection vs. spaeterer Display-Filter), TopK-Default 5→3, AppPreferences mit `pirol_topk_threshold`, Settings-UI-Slider "Max. Arten pro Chunk", Konfidenz-Schwelle-Slider steuert jetzt `topKThreshold`; 5 neue Tests = 65 gesamt. `compileDebugKotlin` + `test` + `assembleDebug` gruen. **Master-Beobachtung:** Bestehende SharedPrefs-Installs behalten alten `topK=5`-Wert — Master-Hinweis fuer Release-Notes: einmalig "Standard"-Preset tippen. **Neuer User-Bugreport:** "BirdNet/Modelle direkt downloaden, .json-Manifest zur einfachen Aktualisierung, spaeter zentrale Update-Version fuer JSONs vom Ersteller" — eigener Folge-AP **T78 Modell-Manifest-Update** (Cluster B oder eigenes Mini-Cluster). | T72-T75 (Cluster B Rest) dispatch-bereit. T72 Manuell+Alternative, T73 Lock-Species, T74 Saisonalitaet, T75 Aehnliche-Arten. T72/T73 sequenziell (LiveVM-Ueberlappung), T74/T75 parallel. |
| **2026-04-28** | **Cluster C komplett (T69) + Cluster B1 (T70) gemerged. Tags `V0.0.6.T69` + `V0.0.6.T70`.** T69: VoiceMemoRecorder (separater AudioRecord-Daemon-Thread parallel zu RecordingService), VoiceMemoSection UI (3 States, Wellenform), 60s-Cap mit Snackbar, `<sessionDir>/memos/<id>.wav` Storage File+SAF, ACTION_MEMO_RECORDED/DELETED, neues `VerificationEvent.newValue: String?` Feld; 3 neue Tests = 45 gesamt. T70: `addOrCluster()` + Haversine-Distanzpruefung in DetectionListState, `firstSeenAtMs`/`lastSeenAtMs` (Long, Default = timestampMs) in DetectionResult, AppPreferences mit clusterEnabled/Radius/TimeWindow, Settings-UI-Sektion "Detektions-Cluster", LiveViewModel ruft addOrCluster pro Detection, ACTION_CLUSTER_MERGED in verifications.jsonl + replaceDetection fuer Anker; 9 neue Tests = ~55 gesamt. `compileDebugKotlin` + `test` + `assembleDebug` gruen. **Master-Beobachtung:** Beide Worker in eigenen Worktrees → saubere Per-AP-Commits (kein Bundle). Auto-Merge ohne Konflikt trotz beider DetectionResult/CLAUDE.md anfassen. T70-Beobachtung: Watchlist-Alarm triggert nicht mehr bei Cluster-Merges (nur bei `newDetections`) — semantisch sauber. | **T71-T75 dispatch-bereit** (T71 Multi-Label, T72 Manuell+Alternative, T73 Lock-Species, T74 Saisonalitaet, T75 Aehnliche-Arten). Reihenfolge: T71/T72/T73 sequenziell (alle fassen LiveViewModel/SpeciesCard an), T74/T75 jederzeit parallel. AP-Files mit T69/T70-Lessons updaten falls noetig vor Dispatch. |
| **2026-04-27** | **Feldtest 8h Dauer-Aufnahme bestanden (User-Bericht).** T64 Recording-Start-Stabilitaet ✅ (kein Drop in 8h, stillschweigend verifiziert). T65 Crash-Recovery-Code aktiv, aber im Praxiseinsatz **kein Crash-Trigger** — S1/S2/S3-Sim formal nicht durchgefuehrt, Recovery-Pfad bleibt ungetestet (akzeptiert, da Praxiseinsatz nichts ausloest). T63 Fairphone-Preroll bleibt vertagt (separater Bugreport). **Reihenfolge geaendert:** V0.0.7 nun **A → C → B → D → E** (Cluster B vor D, weil User Detektions-Logik wichtiger findet als Karte V2). | Cluster B auf 6 APs reduziert (B5 Simultanbeobachtung wandert zu Cluster D, da nur Karten-relevant). T70 (B1 Cluster + Settings-Slider Radius/Zeit + `firstSeenAt`/`lastSeenAt` Datenmodell + neuer ActionType `CLUSTER_MERGED`), T71 (B2 Multi-Label TopK=3 → mehrere parallele Detection-Cards pro Chunk), T72 (B3 Manuell + Alternative-bestaetigen), T73 (B4 Lock-Species + Long-Press-Kontext), T74 (B6 Saisonalitaet + `species_phenology.json`-Asset), T75 (B7 Aehnliche-Arten-Refactor + 0.5=Laufschrift). User-Entscheidungen: Radius/Zeit in Settings einstellbar, Saisonalitaet aktivierbar, Zeit von-bis loggen. T70 zwingend zuerst (alle anderen bauen darauf), dann T72/T73/T75 parallel, T71/T74 separat. |
| **2026-04-27** | **Cluster C T67+T68 gemerged in master. Tags `V0.0.6.T67` + `V0.0.6.T68`.** T67: `DetectionResult` um count(=1)/atlasCode/note/voiceMemoFile/manualEntry/lockSpecies erweitert + `VerificationEvent.actionType` mit 15 ACTION_*-Konstanten (6 alt, 5 Cluster-C aktiv, 4 Cluster-B/D vorbereitet) — Backward-kompat zu V0.0.6-JSONL ohne Migrations-Skript dank kotlinx.serialization-Defaults; 6 neue Unit-Tests, 40 gesamt gruen. T68: `assets/atlas_codes.json` (CH-Set 20 Codes AC1-AC19+AC99) + `AtlasCodeRepository` (lazy-cached) + `EditDetectionDialog` (Stueckzahl-Stepper, Atlascode-Dropdown, 500-Zeichen-Bemerkung) + Listen-Icon in `SpeciesCard` (faerbt sich primary bei `hasCustomData`) + `editDetection()` in beiden ViewModels (granulare ACTION_COUNT_CHANGED/ATLAS_SET/NOTE_ADDED-Events, Mutex+Rollback) + `SessionManager.replaceDetection` (atomar tmp+rename / SAF "wt"-Rewrite) + `DetectionListState.updateDetection`; 9 neue Unit-Tests, 48 gesamt gruen. `compileDebugKotlin` + `test` + `assembleDebug` gruen. **Master-Beobachtung:** Backlog (f) `saveErrorMessage`-Snackbar in AnalysisScreen war bereits in T-prev verdrahtet (T68-Beobachtung) — gestrichen. Neue Backlog-Punkte: (h) `removeLastVerification()` auf tmp+rename umstellen (T70), (i) `DetectionResult.atlasCode`-Kommentar zeigt alte Beispiele "A2"/"B4" statt "AC1"–"AC99" (T71). | **Naechster AP: T69 Sprach-Memo (WAV 16kHz mono 16-bit, `<sessionDir>/memos/<id>.wav`, MEMO_RECORDED/DELETED Action-Types).** T69-AP mit T67/T68-Lessons-Korrekturen aktualisiert. Folge-Ideen nach Cluster C: Settings-Dropdown Atlascode-Set CH/FR/DE, Memo-Export als m4a aus AnalysisPlayer. **Naechste Master-Entscheidungen vor Cluster D:** osmdroid `RadiusMarkerClusterer`-Bestaetigung, Swisstopo WMTS-URL + Lizenz, species_phenology.json fuer Cluster B Saisonalitaet. |
| **2026-04-27** | **Block A T64-T66 gemerged in master. Tags `V0.0.6.T64` (Merge 6dd4abc), `V0.0.6.T65` (Merge 9b1f7db), `V0.0.6.T66` (Merge ac171fa).** T64: SMOKETEST_START/SMOKETEST_FIRST_CHUNK Log-Markers in RecordingService + Python-Analyse-Script `tools/log-analyse-t64.py` — Geraete-Tests (80 Starts) durch User ausstehend. T65: `StreamingWavWriter.finalizeHeader(File)` + `SessionManager.recoverOrphanSessions()` (Call-Site `AnalysisViewModel.init`) — Crash-Sim S1/S2/S3 durch User ausstehend. T66: `BDA_PIROL_V006.md` (13 Abschnitte mit Diff T50-T65 + Changelog). `compileDebugKotlin` + `test` + `assembleDebug` gruen. **Master-Beobachtung:** Worktree-Disziplin erneut gebrochen — T65 im Hauptrepo statt eigenem Worktree, T66 ohne Branch (Files lagen untracked, retroaktiv Branch erstellt). T64 als einziger sauber im eigenen Worktree. **Block A komplett gemerged (T58-T66).** Folge-AP-Kandidaten aus Beobachtungen: (a) `force(false)`/`force(true)`-Verhalten bei Akku-Aus (T65), (b) Int-Overflow >2GB-WAV (T65), (c) `FileWriter.sync()` fehlt (T65), (d) Stereo-Downmix in `readWavSamplesRange` (T62) — bleibt offen wegen geplantem Stereo-Mic + Gyroscope-Feature (raeumliche Lokalisierung, Mono/Stereo-Umschalter in Settings, V0.0.8+), (e) doppelter `modifier` in `DetectionList.kt:112` (T61), (f) AnalysisScreen muss `saveErrorMessage` verdrahten (T61), (g) `undoLastVerification()` leerer if-Block (T53). | **User-Aufgaben (Geraete-Verifikation):** T63 Fairphone-Logs (vertagt — separater Bugreport), T64 80-Starts-Smoketest, T65 Crash-Sim S1/S2/S3. **Naechster Block: C — Manuelle Erfassung.** Vorab Master-Entscheidungen: (1) DetectionResult-Felder count/atlasCode/note/voiceMemoFile/manualEntry/lockSpecies, (2) verifications.jsonl actionTypes MANUAL_ADD/POSITION_MOVED/SPECIES_LOCKED/ALTERNATIVE_CONFIRMED, (3) Saisonalitaet-Quelle (region_sets vs. neue species_phenology.json fuer Cluster B), (4) osmdroid `RadiusMarkerClusterer` als Cluster-D-Library bestaetigen. |
| **2026-04-27** | **Block A T59-T63 gemerged in master. Tags `V0.0.6.T59` (44084c7) + `V0.0.6.T60..T63` (Bundle-Commit 33de8d0). Merge-Commit 945a384.** T59 SessionMigration via DocumentFile/ContentResolver, T60 Auto-Loeschen kurzer leerer Sessions (Toggle+Slider), T61 Verifikations-Mutex+Rollback (LiveVM+AnalysisVM), T62 Player-Refactor (Streaming-Sonogramm, SAF-Lesen, playerError-Toast), T63 Fairphone Audio-API-Fallback (AAudio→OpenSL ES) + Permission-Race + WavWriter-Position-Bugfix. `compileDebugKotlin` + `test` (37 gruen) + `assembleDebug` gruen. **Master-Beobachtung:** Worker T60-T63 haben **alle im selben Tree (T59-Branch) gestapelt** statt frische Worktrees von master zu nehmen — saubere Per-AP-Commits unmoeglich, daher Bundle. CLAUDE.md "Worktree-Disziplin" verschaerft. AP-Files T63-Beobachtungen: Stereo-Downmix in `readWavSamplesRange` fehlt, doppelter `modifier` in `DetectionList.kt:112`, AnalysisScreen muss `saveErrorMessage` verdrahten — Folge-AP. | T64 (20x-Smoketest Samsung+Fairphone, braucht T63 deployed) als naechstes. T65 (Crash-Recovery) + T66 (BDA V006) parallelisierbar. Geraete-Verifikation T63 (Fairphone) durch User noch ausstehend. |
| **2026-04-26** | **T58 abgeschlossen + reviewed + User-Geraete-Test bestanden. Tag `V0.0.6.T58` gesetzt auf `cdd311a`.** Watchlist/Alarm-Hauptursache D1 (un-normalisierte Eintraege im `watchedSpecies`-Set) gefixt; Cooldown-Slider 0-60min in Settings; `clearCooldowns()` bei `endSession()`; 5 Unit-Tests gruen; Build SUCCESSFUL. Master-Beobachtungen: (a) Worker hat direkt in master gemerged statt Worktree-Branch zu halten — neue Merge-Regel in CLAUDE.md eingetragen, ab T59 muss Master mergen. (b) Worker hat Scope-Creep in `SettingsScreen.kt` (Autocomplete-Polish, Slider-Render-Bug) — Scope-Disziplin in CLAUDE.md verschaerft. | T59 (SessionMigrationWorker Ordnerwechsel-Fix) als naechstes Block-A-AP. Workflow ab jetzt: Worker bleibt im Worktree, User buildet aus Worktree via neuem `pirol-deploy-worktree.ps1`, erst nach Freigabe merged Master in master. |

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

- **Stereo + Gyroscope (V0.0.8+):** Stereo-Mikrofon-Aufnahme (Channel-Count konfigurierbar in Settings, Default Mono), kombiniert mit Gyroscope/Accelerometer fuer raeumliche Lokalisierung der Rufe. Bedingt: WavWriter Channel-Parameter, Oboe `setChannelCount(Stereo)`, BirdNET-Pipeline-Downmix-Stage vor Inference, `readWavSamplesRange` Stereo-Lesung, Sonogramm zeigt L/R getrennt oder summiert, neue Detection-Felder `bearing: Float?` + `tilt: Float?`. Voraussetzung: Geraet hat Stereo-Mic (viele Smartphones nicht). UI-Konzept: Pfeil auf Detection-Karte zeigt Richtung relativ zur Geraetelage beim Ruf.
- **Xeno-Canto Integration:** Ktor API Client + ReferenceDownloader (AMSEL als Vorlage)
- KMP-Extraktion: Core-Module zwischen AMSEL und PIROL teilen
- Bat-Modus: Ultraschall-Erkennung (>15kHz), eigenes Embedding-Modell
- Wear OS Companion: Notifications auf Smartwatch
- WorkInfo-Observer: UploadManager Status-Tracking via WorkManager LiveData
- Autocomplete im Korrektur-Dialog (RegionalSpeciesFilter als Quelle)
- BirdNET V2.4 TFLite Runtime (Alternative zu ONNX-Konvertierung)
- xenoCantoApiKey Feld in Settings (ging bei T22 unter)

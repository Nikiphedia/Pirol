# PIROL — Code-Index

**Stand:** 2026-05-04
**Maintainer:** Pirolmaster (jeweils aktuell)
**Zweck:** Übersicht aller Quelldateien — Sprache, Erstelldatum, Zweck, Changelog.

**Hinweise zu Unklarheiten:**
- Dateien mit Erstelldatum `≤ 2026-04-08` wurden vor dem ersten dokumentierten Rotations-Log erstellt (T1–T31, kein vollständiger Kontext mehr vorhanden). Inhalte aus Code-Lesungen erschlossen.
- Changelog-Einträge vor T58 sind unvollständig — ggf. git log konsultieren.
- Zeilenzahlen sind Näherungswerte zum Erstellungszeitpunkt des Index.

---

## Legende

| Spalte | Inhalt |
|--------|--------|
| **Datei** | Pfad relativ zu `D:\80002\PIROL` |
| **Sprache** | Kotlin / C++ / XML / JSON / CSV / Kotlin DSL |
| **Erstellt** | Datum erstes git-commit (`git log --diff-filter=A`) |
| **Zweck** | Kurzbeschreibung |
| **Changelog** | Task-Nummern bei relevanten Änderungen |

---

## 1. App-Root

| Datei | Sprache | Erstellt | Zweck | Changelog |
|-------|---------|----------|-------|-----------|
| `app/src/main/AndroidManifest.xml` | XML | ≤ 2026-04-08 | Permissions, Activity, Service, FileProvider | — |
| `app/build.gradle.kts` | Kotlin DSL | ≤ 2026-04-08 | Build-Konfiguration, Dependencies | — |
| `app/src/main/kotlin/ch/etasystems/pirol/MainActivity.kt` | Kotlin | ≤ 2026-04-08 | Einstiegspunkt, setzt ComposeView | — |
| `app/src/main/kotlin/ch/etasystems/pirol/PirolApp.kt` | Kotlin | 2026-04-08 | Application-Klasse: Koin-Init, osmdroid-Config | T89: osmdroid Configuration.getInstance() vor startKoin() |

---

## 2. Audio

| Datei | Sprache | Erstellt | Zweck | Changelog |
|-------|---------|----------|-------|-----------|
| `audio/RecordingService.kt` | Kotlin | ≤ 2026-04-08 | Foreground-Service: Aufnahme-Loop, Preroll-Buffer, WAV-Schreiben, SharedFlow<AudioChunk> | T76: Lifecycle-Guard, Preroll-SmallFAB, Stop-LongPress 2s |
| `audio/OboeAudioEngine.kt` | Kotlin | ≤ 2026-04-08 | JNI-Wrapper zu Oboe C++ Engine | — |
| `audio/AudioPlayer.kt` | Kotlin | ≤ 2026-04-08 | WAV-Datei abspielen (SAF + File-Modus), Streaming-Sonogramm | T62: Player-Refactor, Streaming-Sonogramm |
| `audio/AlarmService.kt` | Kotlin | ≤ 2026-04-08 | Watchlist-Alarm: Benachrichtigung + Vibration bei erkannter Zielart | T58: Cooldown-Slider, Alarm-Fix |
| `audio/AudioChunk.kt` | Kotlin | ≤ 2026-04-08 | Data-Klasse: Audiodaten + Timestamp-Metadaten | — |
| `audio/AudioPermissionHandler.kt` | Kotlin | ≤ 2026-04-08 | Mikrofon-Permission-Flow | — |
| `audio/VoiceMemoRecorder.kt` | Kotlin | ≤ 2026-04-08 | Sprach-Memo aufnehmen: WAV 16kHz Mono 16-bit, 60s-Cap, parallel zu RecordingService | T69: neu |

### Audio DSP

| Datei | Sprache | Erstellt | Zweck | Changelog |
|-------|---------|----------|-------|-----------|
| `audio/dsp/FFT.kt` | Kotlin | ≤ 2026-04-08 | Cooley-Tukey FFT | — |
| `audio/dsp/MelFilterbank.kt` | Kotlin | ≤ 2026-04-08 | Mel-Filterbank (128 Bänder) | — |
| `audio/dsp/MelSpectrogram.kt` | Kotlin | ≤ 2026-04-08 | Mel-Spektrogramm: FFT → Filterbank → dB | — |
| `audio/dsp/AudioResampler.kt` | Kotlin | ≤ 2026-04-08 | Resampling 48kHz → 32kHz für BirdNET | — |
| `audio/dsp/AudioDspUtils.kt` | Kotlin | ≤ 2026-04-08 | HPF, Normalisierung, Hilfsfunktionen | — |
| `audio/dsp/DynamicRangeMapper.kt` | Kotlin | ≤ 2026-04-08 | Dynamik-Mapping für Sonogramm-Darstellung | — |
| `audio/dsp/SpectrogramConfig.kt` | Kotlin | ≤ 2026-04-08 | Konfigurationsparameter für Spektrogramm | — |
| `audio/dsp/WindowFunction.kt` | Kotlin | ≤ 2026-04-08 | Hann-Fensterfunktion | — |

---

## 3. Native C++ (Oboe Audio Engine)

| Datei | Sprache | Erstellt | Zweck | Changelog |
|-------|---------|----------|-------|-----------|
| `cpp/AudioEngine.cpp` | C++ | ≤ 2026-04-08 | Oboe-Aufnahme-Loop: AAudio → OpenSL ES Fallback, RingBuffer-Write | T63: OpenSL ES Fallback für Fairphone |
| `cpp/AudioEngine.h` | C++ | ≤ 2026-04-08 | AudioEngine-Interface, Start/Stop/Read | — |
| `cpp/jni_bridge.cpp` | C++ | ≤ 2026-04-08 | JNI-Brücke: Kotlin ↔ C++ AudioEngine | — |
| `cpp/RingBuffer.h` | C++ | ≤ 2026-04-08 | Lock-freier SPSC RingBuffer (30s, float) | — |
| `cpp/CMakeLists.txt` | CMake | ≤ 2026-04-08 | NDK Build-Konfiguration | — |

---

## 4. ML / Erkennung

| Datei | Sprache | Erstellt | Zweck | Changelog |
|-------|---------|----------|-------|-----------|
| `ml/DetectionResult.kt` | Kotlin | 2026-04-08 | Zentrale Datenklasse: Erkennung mit count, atlasCode, note, voiceMemoFile, manualEntry, lockSpecies, GPS, verificationStatus | T67: count/atlasCode/note/voiceMemoFile/manualEntry/lockSpecies/firstSeenAt/lastSeenAt; T84: ACTION_SIMULTANEOUS/PROBABLE/SAME_INDIVIDUAL; T86: atlasCode-Kommentar |
| `ml/DetectionListState.kt` | Kotlin | 2026-04-08 | Shared State: Live-Detektionsliste, addOrCluster(), lockedSpecies StateFlow, version-Counter | T70: addOrCluster() + Haversine; T73: lockedSpecies StateFlow |
| `ml/BirdNetV3Classifier.kt` | Kotlin | ≤ 2026-04-08 | ONNX-Runtime BirdNET V3: Inferenz, filterAndRank(), Top-K | T71: filterAndRank() extrahiert, TopK-Default 5→3, topKThreshold separat |
| `ml/InferenceWorker.kt` | Kotlin | ≤ 2026-04-08 | Koordiniert ChunkAccumulator → BirdNetV3Classifier → EmbeddingExtractor | — |
| `ml/ChunkAccumulator.kt` | Kotlin | ≤ 2026-04-08 | Sammelt AudioChunks zu 3s-Blöcken für Inferenz | — |
| `ml/AudioClassifier.kt` | Kotlin | ≤ 2026-04-08 | Interface für Klassifikatoren | — |
| `ml/EmbeddingExtractor.kt` | Kotlin | ≤ 2026-04-08 | ONNX-Embedding-Extraktion (1024-dim / MFCC 43-dim) | — |
| `ml/EmbeddingDatabase.kt` | Kotlin | ≤ 2026-04-08 | Binary-Embedding-DB (BSED), findSimilar() | T75: Dedup-Fix, DE-Namen |
| `ml/MfccExtractor.kt` | Kotlin | ≤ 2026-04-08 | MFCC-Features für Embedding-Fallback | — |
| `ml/ModelManager.kt` | Kotlin | ≤ 2026-04-08 | Modell-Laden/Entladen, Download-Logik | — |
| `ml/SpeciesNameResolver.kt` | Kotlin | ≤ 2026-04-08 | Wissenschaftl. ↔ Deutschen Artnamen, searchSpecies() | — |
| `ml/RegionalSpeciesFilter.kt` | Kotlin | ≤ 2026-04-08 | Filtert Erkennungen auf DACH-Arten (region_sets.json) | — |
| `ml/PhenologyRepository.kt` | Kotlin | ≤ 2026-04-08 | Lädt species_phenology.json, saison-Plausibilität | T74: neu |
| `ml/WatchlistManager.kt` | Kotlin | ≤ 2026-04-08 | Watchlist laden/speichern, Alarm-Trigger | T58: Cooldown-Fix, un-normalisierte Namen |
| `ml/WatchlistEntry.kt` | Kotlin | ≤ 2026-04-08 | Data-Klasse: Art + Alarm-Konfiguration | — |
| `ml/InferenceConfig.kt` | Kotlin | ≤ 2026-04-08 | Inferenz-Parameter: Threshold, TopK, TopKThreshold | T71: topKThreshold separat |
| `ml/AudioBlock.kt` | Kotlin | ≤ 2026-04-08 | Data-Klasse: Audio-Block für Verarbeitung | — |
| `ml/AtlasCode.kt` | Kotlin | ≤ 2026-04-08 | Typ-Alias / Konstante für Atlascode-Strings | — |

---

## 5. Data / Repository

| Datei | Sprache | Erstellt | Zweck | Changelog |
|-------|---------|----------|-------|-----------|
| `data/repository/SessionManager.kt` | Kotlin | 2026-04-08 | Kern-Repository: Session-CRUD, detections.jsonl, verifications.jsonl, WAV, SAF+File-Dual-Mode | T67: VerificationEvent + ActionTypes; T68: replaceDetection atomar; T70: addOrCluster; T79: recordPositionMoved; T84: recordSimultaneousLink; T85: loadSimultaneousLinkMap; T86: removeLastVerification tmp+rename |
| `data/repository/WavWriter.kt` | Kotlin | ≤ 2026-04-08 | WAV-Header schreiben/aktualisieren, periodisches Force, Streaming | T86: Int-Overflow-Cap minOf(0xFFFFFFFFL) |
| `data/repository/SessionMetadata.kt` | Kotlin | ≤ 2026-04-08 | @Serializable: Session-Metadaten (ID, Zeitraum, GPS-Bounds) | — |
| `data/repository/SessionMigrationWorker.kt` | Kotlin | ≤ 2026-04-08 | WorkManager: Session-Ordner migrieren bei Speicherpfad-Wechsel | T59: DocumentFile/ContentResolver |
| `data/repository/SpeciesColorRepository.kt` | Kotlin | 2026-05-03 | Lädt species_colors.json (filesDir), 12-Farben-Fallback via hashCode | T80: neu |
| `data/repository/AtlasCodeRepository.kt` | Kotlin | ≤ 2026-04-08 | Lädt atlas_codes.json aus assets, lazy-cached | T68: neu |
| `data/repository/ReferenceRepository.kt` | Kotlin | ≤ 2026-04-08 | Referenz-Sonogramme + Audiodateien aus filesDir/references/ | — |
| `data/repository/ReferenceEntry.kt` | Kotlin | ≤ 2026-04-08 | Data-Klasse: Referenz-Eintrag | — |
| `data/export/RavenExporter.kt` | Kotlin | 2026-04-19 | Exportiert Session als Raven-kompatibles TSV | T78: enabledFields-Parameter, 10 Toggle-Felder; T85: SimultaneousLinkSummary, FIELD_SIMULTAN/MOEGLICH/GLEICH, 6. Parameter linkMap |
| `data/AppPreferences.kt` | Kotlin | 2026-04-08 | SharedPreferences-Wrapper: alle App-Settings als typisierte Properties | T71–T85: je neue Properties; T85: ravenFieldSimultan/Moeglich/Gleich |
| `data/StorageManager.kt` | Kotlin | ≤ 2026-04-08 | Speicherpfad-Verwaltung: intern / SAF-extern | — |

### Data / Sync

| Datei | Sprache | Erstellt | Zweck | Changelog |
|-------|---------|----------|-------|-----------|
| `data/sync/SessionUploadWorker.kt` | Kotlin | ≤ 2026-04-08 | WorkManager: Session-Upload | — |
| `data/sync/UploadManager.kt` | Kotlin | ≤ 2026-04-08 | Upload-Koordination | — |
| `data/sync/LocalExportTarget.kt` | Kotlin | ≤ 2026-04-08 | Export-Ziel: lokaler Ordner | — |
| `data/sync/UploadTarget.kt` | Kotlin | ≤ 2026-04-08 | Interface für Upload-Ziele | — |
| `data/sync/UploadStatus.kt` | Kotlin | ≤ 2026-04-08 | Enum: Upload-Status | — |

---

## 6. Dependency Injection

| Datei | Sprache | Erstellt | Zweck | Changelog |
|-------|---------|----------|-------|-----------|
| `di/AppModule.kt` | Kotlin | 2026-04-08 | Koin-Module: alle Singletons und ViewModels | T80: SpeciesColorRepository; T82: AtlasCodeRepository; T83: MapViewModel 2→3 Parameter (LocationProvider) |

---

## 7. Location

| Datei | Sprache | Erstellt | Zweck | Changelog |
|-------|---------|----------|-------|-----------|
| `location/LocationProvider.kt` | Kotlin | ≤ 2026-04-08 | FusedLocationProviderClient, 10s-Intervall, StateFlow<LocationData?> | — |
| `location/LocationPermissionHandler.kt` | Kotlin | ≤ 2026-04-08 | GPS-Permission-Flow | — |

---

## 8. UI — Karte

| Datei | Sprache | Erstellt | Zweck | Changelog |
|-------|---------|----------|-------|-----------|
| `ui/map/MapScreen.kt` | Kotlin | 2026-04-08 | Karten-Composable: osmdroid MapView, Marker, Cluster, Dialoge, Long-press via GestureDetectorCompat, Center-Pin Move-Modus | T79–T85: kompletter Rewrite D1–D5b; T87: X/Y Fix + Cache; T88: GestureDetectorCompat via setOnTouchListener; T89: PirolApp-Config entfernt, Center-Pin Move-UX |
| `ui/map/MapViewModel.kt` | Kotlin | 2026-04-08 | MapUiState (15 Felder), FilterMode, LinkType, MapMarker, SimultaneousLink, MarkerCluster, alle Karten-Methoden | T79: Rewrite; T80: SpeciesColor; T81: Clustering; T82: EditDetection+Lock; T83: LongPressTarget+LocationProvider; T84: FocusMode+Simultanlinks |
| `ui/map/SwisstopoTileSource.kt` | Kotlin | 2026-05-03 | OnlineTileSourceBase BGDI WMTS: {z}/{y}/{x}.jpeg, Zoom 13–18 | T83: neu; T87: X/Y getauscht |

---

## 9. UI — Live-Screen

| Datei | Sprache | Erstellt | Zweck | Changelog |
|-------|---------|----------|-------|-----------|
| `ui/live/LiveScreen.kt` | Kotlin | ≤ 2026-04-08 | Haupt-Live-UI: 3 Layouts (Compact/Medium/Expanded), FABs, Sonogramm, DetectionList, Dialoge | T76: Lifecycle-Fix, Preroll-FAB; T77: +Art+Memo-Buttons; T86: Memo-Button Platzhalter-Dialog |
| `ui/live/LiveViewModel.kt` | Kotlin | ≤ 2026-04-08 | Live-Aufnahme-ViewModel: RecordingService-Binding, Inferenz-Loop, Session-Management | T70–T75: Cluster/Lock/Manuell/Alternative/Saisonalität |
| `ui/live/LiveUiState.kt` | Kotlin | ≤ 2026-04-08 | LiveUiState Data-Klasse | — |

---

## 10. UI — Analyse-Screen

| Datei | Sprache | Erstellt | Zweck | Changelog |
|-------|---------|----------|-------|-----------|
| `ui/analysis/AnalysisScreen.kt` | Kotlin | ≤ 2026-04-08 | Session-Browser + Sonogramm-Replay + Verifikation + Player | T62: Streaming-Sonogramm, SAF-Lesen |
| `ui/analysis/AnalysisViewModel.kt` | Kotlin | ≤ 2026-04-08 | Session laden, Verifikation, Lock, Edit, Memo | T68: editDetection; T73: toggleLock |
| `ui/analysis/AnalysisUiState.kt` | Kotlin | ≤ 2026-04-08 | AnalysisUiState Data-Klasse | — |

---

## 11. UI — Settings

| Datei | Sprache | Erstellt | Zweck | Changelog |
|-------|---------|----------|-------|-----------|
| `ui/settings/SettingsScreen.kt` | Kotlin | ≤ 2026-04-08 | Alle Einstellungen: Cluster, TopK, Watchlist, Raven-Felder-Toggle, Saisonalität, Ähnliche Arten, Simultanfelder | T78: Raven-Toggle-Sektion; T85: Simultan/Moeglich/Gleich-Switches |

---

## 12. UI — Komponenten

| Datei | Sprache | Erstellt | Zweck | Changelog |
|-------|---------|----------|-------|-----------|
| `ui/components/SpeciesCard.kt` | Kotlin | ≤ 2026-04-08 | Erkennungs-Karte: Art, Konfidenz, Badges (Phenology/Rarity/Lock/Manual), Long-Press-Menü | T73: Lock-Badge + Dropdown; T74: Saison-Badge |
| `ui/components/DetectionList.kt` | Kotlin | ≤ 2026-04-08 | LazyColumn aller Detektionen | — |
| `ui/components/EditDetectionDialog.kt` | Kotlin | ≤ 2026-04-08 | Dialog: count-Stepper, atlasCode-Dropdown, 500-Zeichen-Bemerkung | T68: neu |
| `ui/components/AddManualDetectionDialog.kt` | Kotlin | ≤ 2026-04-08 | Dialog: Art-Autocomplete + GPS-Snapshot für manuelle Erfassung | T72: neu |
| `ui/components/SpectrogramCanvas.kt` | Kotlin | ≤ 2026-04-08 | Rolling-Sonogramm-Canvas | T62: Streaming-Verbesserung |
| `ui/components/SpectrogramOverlay.kt` | Kotlin | ≤ 2026-04-08 | Overlays auf Sonogramm (Chunk-Marker, Zeitachse) | — |
| `ui/components/SpectrogramState.kt` | Kotlin | ≤ 2026-04-08 | State-Klasse für Sonogramm | — |
| `ui/components/VoiceMemoSection.kt` | Kotlin | ≤ 2026-04-08 | UI für Sprach-Memo: 3 States (idle/recording/done), Wellenform | T69: neu |
| `ui/components/SimilarityPanel.kt` | Kotlin | ≤ 2026-04-08 | „Ähnliche Arten"-Panel: Liste oder Marquee-Laufschrift | T75: Refactor, DE-Namen, Dedup |
| `ui/components/SessionCard.kt` | Kotlin | ≤ 2026-04-08 | Session-Liste im Analyse-Tab | — |
| `ui/components/ColorPalette.kt` | Kotlin | ≤ 2026-04-08 | 12 Material-Farben als Fallback für SpeciesColorRepository | T80: neu (unklar ob eigene Datei oder in SpeciesColorRepository) |

---

## 13. UI — Navigation, Onboarding, Referenzen, Theme

| Datei | Sprache | Erstellt | Zweck | Changelog |
|-------|---------|----------|-------|-----------|
| `ui/navigation/PirolNavigation.kt` | Kotlin | ≤ 2026-04-08 | NavHost: 5 Tabs (Live/Analyse/Referenzen/Karte/Settings) | — |
| `ui/navigation/Screen.kt` | Kotlin | ≤ 2026-04-08 | Screen-Enum / sealed class | — |
| `ui/onboarding/OnboardingScreen.kt` | Kotlin | ≤ 2026-04-08 | Onboarding-Flow: Permissions, Modell-Hinweis | T22: neu |
| `ui/reference/ReferenceScreen.kt` | Kotlin | ≤ 2026-04-08 | Referenz-Bibliothek: Sonogramme + Audiofiles | — |
| `ui/reference/ReferenceViewModel.kt` | Kotlin | ≤ 2026-04-08 | Referenz-Verwaltung | — |
| `ui/reference/ReferenceUiState.kt` | Kotlin | ≤ 2026-04-08 | ReferenceUiState Data-Klasse | — |
| `ui/theme/Theme.kt` | Kotlin | ≤ 2026-04-08 | Material3-Theme | — |
| `ui/theme/Type.kt` | Kotlin | ≤ 2026-04-08 | Typographie-Definition | — |
| `util/DateTimeUtils.kt` | Kotlin | ≤ 2026-04-08 | Datums-/Zeit-Hilfsfunktionen | — |

---

## 14. Assets / Ressourcen

| Datei | Sprache | Erstellt | Zweck | Changelog |
|-------|---------|----------|-------|-----------|
| `assets/species/species_master.json` | JSON | ≤ 2026-04-08 | 11'560 Vogelarten: wissenschaftl. Name, DE/EN/FR, Taxonomie (~417k Zeilen) | — |
| `assets/models/birdnet_v3_labels.csv` | CSV | ≤ 2026-04-08 | BirdNET V3 Label-Taxonomie (11'561 Zeilen) | — |
| `assets/models/README.md` | Markdown | ≤ 2026-04-08 | Platzhalter-Hinweis: birdnet_v3.onnx manuell in assets/models/ ablegen | — |
| `assets/regions/region_sets.json` | JSON | ≤ 2026-04-08 | DACH-Artenfilter nach Region (~755 Zeilen) | — |
| `assets/atlas_codes.json` | JSON | 2026-04-27 | Schweizer Atlascode-Set AC1–AC99 (26 Zeilen) | T68: neu |
| `assets/map_manifest.json` | JSON | 2026-05-03 | Karten-Konfiguration: OSM Zoom 1–12, Swisstopo Zoom 13–18 (Konfig-Doku) | T83: neu |
| `assets/species_phenology.json` | JSON | ≤ 2026-04-28 | Saisonalitäts-Daten (initial leer — User befüllt) | T74: neu |
| `res/xml/file_paths.xml` | XML | ≤ 2026-04-08 | FileProvider-Pfade | — |
| `res/values/strings.xml` | XML | ≤ 2026-04-08 | App-Name | — |
| `res/values/themes.xml` | XML | ≤ 2026-04-08 | Theme-Referenz | — |

---

## 15. Laufzeit-Dateien (filesDir / cacheDir, nicht im Repo)

Diese Dateien werden zur Laufzeit erzeugt und sind **nicht** im git-Repository.

| Pfad | Erstellt durch | Zweck |
|------|---------------|-------|
| `filesDir/sessions/{date}_{uuid}/session.json` | SessionManager | Session-Metadaten |
| `filesDir/sessions/{date}_{uuid}/detections.jsonl` | SessionManager | Detektionen (1 JSON pro Zeile) |
| `filesDir/sessions/{date}_{uuid}/verifications.jsonl` | SessionManager | Verifikations-Events (ActionTypes) |
| `filesDir/sessions/{date}_{uuid}/audio/chunk_NNN.wav` | SessionManager | 3s Audio-Chunks |
| `filesDir/sessions/{date}_{uuid}/memos/{id}.wav` | VoiceMemoRecorder | Sprach-Memos 16kHz |
| `filesDir/species_colors.json` | User / Import | Art → Farbe-Mapping (editierbar) |
| `filesDir/references/` | User | Referenz-Sonogramme + Audiofiles |
| `filesDir/embeddings/embeddings.bsed` | EmbeddingDatabase | Binäre Embedding-DB |
| `cacheDir/osmdroid_tiles/` | osmdroid | Kachel-Cache (T89) |

---

## Unklarheiten / Offene Punkte

1. **Erstelldaten vor T58** — git-History existiert, wurde für diesen Index nicht vollständig ausgewertet. Für genaue Daten: `git log --diff-filter=A --follow --format="%as %s" -- <datei>`
2. **ColorPalette.kt** — Unklar ob eigenständige Datei oder Teil von SpeciesColorRepository. Im Code-Scan als separate Datei erschienen.
3. **birdnet_v3.onnx** — Wird nicht versioniert (zu gross). Muss manuell in `assets/models/` abgelegt werden. Kein Eintrag im Index möglich.
4. **species_colors.json** — Laufzeit-Datei, kein Template im Repo. Kann via Einstellungen importiert werden (Cluster F offen).
5. **Changelog T1–T57** — Nicht rekonstruierbar ohne vollständigen git-Log-Kontext. Bei Bedarf: `git log --oneline` konsultieren.
6. **Unit-Tests** — Testdateien unter `app/src/test/` nicht indexiert. Ca. 45 Tests vorhanden (Stand T86).

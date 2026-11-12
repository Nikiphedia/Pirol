# PIROL — Code-Index

**Stand:** 2026-05-05 (aus Handover-Files T1–T89 rekonstruiert)
**Maintainer:** Pirolmaster (jeweils aktuell)
**Zweck:** Übersicht aller Quelldateien — Sprache, Erstelldatum, Zweck, Changelog.

**Rekonstruktions-Methode:** Alle Handover-Files T1–T89 (D:\80002\PIROL\Historisch\) und aktuelle Master-Briefings systematisch ausgewertet. Erstelldaten auf Basis des ersten Handovers der Datei; Changelogs vollständig aus Handover-Texten übernommen. Wenige verbleibende Unklarheiten sind am Ende dokumentiert.

---

## Legende

| Spalte | Inhalt |
|--------|--------|
| **Datei** | Pfad relativ zu `app/src/main/kotlin/ch/etasystems/pirol/` (bei Kotlin) |
| **Sprache** | Kotlin / C++ / XML / JSON / CSV / Kotlin DSL |
| **Erstellt** | Task bei erstem Erscheinen (aus Handover erschlossen) |
| **Zweck** | Kurzbeschreibung |
| **Changelog** | Task-Nummern mit wesentlichen Änderungen |

---

## 1. App-Root

| Datei | Sprache | Erstellt | Zweck | Changelog |
|-------|---------|----------|-------|-----------|
| `app/src/main/AndroidManifest.xml` | XML | T1 | Permissions, Activity, Service, FileProvider-Registrierung | T2: RecordingService; T13: LocationProvider; T15: FileProvider; T17: WorkManager; T20: VIBRATE-Permission; T32: configChanges auf MainActivity; T51: file_paths-Update |
| `app/build.gradle.kts` | Kotlin DSL | T1 | Build-Konfiguration, Dependency-Deklarationen | T1: NDK+Oboe; T7: onnxruntime-android; T17: work-runtime-ktx; T28: Ktor+OkHttp; T31: osmdroid; T51: documentfile |
| `app/src/main/kotlin/.../MainActivity.kt` | Kotlin | T1 | Compose-Einstiegspunkt, setzt WindowSizeClass | T6: WindowSizeClass an LiveScreen; T22: Onboarding-Startdestination |
| `app/src/main/kotlin/.../PirolApp.kt` | Kotlin | T2 | Application-Klasse: startKoin{} mit appModule | T89: osmdroid Configuration.getInstance() VOR startKoin() — einzige korrekte Init-Stelle |

---

## 2. Audio

| Datei | Sprache | Erstellt | Zweck | Changelog |
|-------|---------|----------|-------|-----------|
| `audio/RecordingService.kt` | Kotlin | T2 | Foreground-Service: Chunk-Emitter 100ms, SharedFlow<AudioChunk>, Notification mit Chronometer | T35: Preroll-Architektur (initPreroll, getPrerollSamples); T46: Single-WAV statt chunk_NNN; T52: RecordingPhase-Enum (IDLE/PREROLL_BUFFERING/RECORDING), StateFlow, prerollPhaseJob-Timer; T57: Session-Rotation (rotateRecordingSync); T76: Lifecycle-Guard, Stop-LongPress 2s |
| `audio/OboeAudioEngine.kt` | Kotlin | T1 | JNI-Wrapper zur Oboe C++ Engine: start/stop, getLatestChunk(durationMs), isRecording, actualSampleRate | — |
| `audio/AudioPlayer.kt` | Kotlin | T18 | WAV abspielen (MediaPlayer + AudioTrack), PlaybackState-Flow | T48: playFromOffset(file, startSec, endSec) via AudioTrack.MODE_STATIC; T62: Streaming-Sonogramm; T43: stop() statt release() in onCleared |
| `audio/AlarmService.kt` | Kotlin | T20 | Watchlist-Alarm: IMPORTANCE_HIGH-Notification, Vibration, PendingIntent | T21: Cooldown-Map 5 min pro Art; T58: Bugfix un-normalisierte Artnamen |
| `audio/AudioChunk.kt` | Kotlin | T2 | Data-Klasse: ShortArray-Kopie der Samples + sampleRate + timestampMs | — |
| `audio/AudioPermissionHandler.kt` | Kotlin | T2 | RECORD_AUDIO + POST_NOTIFICATIONS Permission-Flow (drei Zustände: Granted/Denied/PermanentlyDenied) | — |
| `audio/VoiceMemoRecorder.kt` | Kotlin | T69 | Sprach-Memo aufnehmen: WAV 16kHz Mono 16-bit, 60s-Cap, parallel zu RecordingService | T69: neu |

### Audio DSP

| Datei | Sprache | Erstellt | Zweck | Changelog |
|-------|---------|----------|-------|-----------|
| `audio/dsp/FFT.kt` | Kotlin | T3 | Radix-2 Cooley-Tukey FFT in-place, powerSpectrum(), powerSpectrumInto() (zero-alloc) | — |
| `audio/dsp/MelFilterbank.kt` | Kotlin | T3 | HTK-Mel-Skala, vorberechnete Dreieckfilter (sparse), apply() zero-alloc | — |
| `audio/dsp/MelSpectrogram.kt` | Kotlin | T3 | Zustandsbehaftete Pipeline: Overlap-Buffer → Hann → FFT → Mel → dB-Skalierung | T54: Bugfix concurrent access (ArrayIndexOutOfBoundsException bei Preroll+Pipeline gleichzeitig) |
| `audio/dsp/AudioResampler.kt` | Kotlin | T7 | Lineares Resampling (portiert von AMSEL): ShortArray @48kHz → FloatArray @32kHz | — |
| `audio/dsp/AudioDspUtils.kt` | Kotlin | T34 | HPF Butterworth 2. Ordnung (200Hz), peakNormalize, brickwallLimit | T34: neu |
| `audio/dsp/DynamicRangeMapper.kt` | Kotlin | T56 | Perzentil-basierter Auto-Kontrast (5./95. Pz., 5s Rolling-Window, IIR-geglättet); computeStatic() für Analyse-Tab | T56: neu |
| `audio/dsp/SpectrogramConfig.kt` | Kotlin | T3 | Presets BIRDS/BATS/WIDEBAND; fftSize, hopSize, numMelBins, fMin, fMax | — |
| `audio/dsp/WindowFunction.kt` | Kotlin | T3 | Hann-Fensterfunktion mit Cache | T43: HashMap → ConcurrentHashMap (Thread-Safety) |

---

## 3. Native C++ (Oboe Audio Engine)

| Datei | Sprache | Erstellt | Zweck | Changelog |
|-------|---------|----------|-------|-----------|
| `app/src/main/cpp/AudioEngine.cpp` | C++ | T1 | Oboe AudioStreamCallback, RingBuffer-Write, Auto-Restart bei Fehler; InputPreset::Unprocessed | T63: OpenSL ES Fallback für Fairphone-Kompatibilität |
| `app/src/main/cpp/AudioEngine.h` | C++ | T1 | AudioEngine-Interface: start/stop, read, sampleRate | — |
| `app/src/main/cpp/jni_bridge.cpp` | C++ | T1 | JNI-Brücke für ch.etasystems.pirol.audio.OboeAudioEngine | — |
| `app/src/main/cpp/RingBuffer.h` | C++ | T1 | SPSC Lock-freier RingBuffer (atomic, überschreibend, konfigurierbar) | — |
| `app/src/main/cpp/CMakeLists.txt` | CMake | T1 | NDK Build-Konfiguration; Oboe 1.9.0 via FetchContent | — |

---

## 4. ML / Erkennung

| Datei | Sprache | Erstellt | Zweck | Changelog |
|-------|---------|----------|-------|-----------|
| `ml/DetectionResult.kt` | Kotlin | T8 | Zentrale Datenklasse einer Erkennung | T13: lat/lon (nullable); T16: VerificationStatus, correctedSpecies, verifiedAtMs; T27: candidates: List<DetectionCandidate>, detectionCount; T33: lastDetectedMs; T44: VerificationStatus.UNCERTAIN; T45: VerificationStatus.REPLACED; T49: chunkStartSec/chunkEndSec = session-relativ (nicht chunk-relativ); T67: count, atlasCode, note, voiceMemoFile, manualEntry, lockSpecies, firstSeenAtMs, lastSeenAtMs; T84: ACTION_SIMULTANEOUS/PROBABLE/SAME_INDIVIDUAL; T86: atlasCode-Kommentar AC1–AC99 |
| `ml/DetectionListState.kt` | Kotlin | T8 | Thread-safe Compose State-Holder: addDetections(), getDetections(), version-Counter | T27: Dedup-Logik rein artbasiert (kein Zeitfenster mehr); T33: ID-Stabilität Bug fix; T44: 10s Re-detection Rule, verificationStatus beim Update beibehalten; T45: selectAlternative(); T70: addOrCluster() + Haversine; T73: lockedSpecies StateFlow |
| `ml/BirdNetV3Classifier.kt` | Kotlin | T7 | ONNX Runtime: Inferenz, Embedding-Extraktion, filterAndRank() | T22: CSV-Labels-Parser (BOM-tolerant), filesDir-Laden; T28: implementiert AudioClassifier-Interface; T37: ModelManager-Integration für Modellwechsel; T43: double-close OnnxTensor fix; T71: filterAndRank() extrahiert, topKThreshold separat |
| `ml/InferenceWorker.kt` | Kotlin | T8 | Orchestrierung: ChunkAccumulator → Resampling → BirdNetV3Classifier → Callback | T11: RegionalSpeciesFilter + InferenceConfig (topK/threshold); T29: inferenceIntervalMs (Energieprofil); T34: HPF + peakNormalize + brickwallLimit vor classify(); T51: Callback immer aufgerufen (audio nullable detections, kein Early-Return mehr) |
| `ml/ChunkAccumulator.kt` | Kotlin | T8 | Sammelt AudioChunks bis 3s-Fenster voll, gibt AccumulatedBlock frei | T40: CHUNK_DURATION_MS-Konstante |
| `ml/AudioClassifier.kt` | Kotlin | T28 | Interface für Klassifikatoren: classify(), extractEmbedding(), isModelAvailable() | T28: neu |
| `ml/EmbeddingExtractor.kt` | Kotlin | T12a | ONNX-Embedding-Extraktion (1024-dim) + MFCC-Fallback (43-dim) | T28: Constructor nimmt AudioClassifier statt BirdNetV3Classifier |
| `ml/EmbeddingDatabase.kt` | Kotlin | T12a | Binary-DB (BSED-Format, AMSEL-kompatibel), add(), search() Cosine-Similarity | T75: Dedup-Fix, DE-Namen |
| `ml/MfccExtractor.kt` | Kotlin | T12a | MFCC-Features als Embedding-Fallback | — |
| `ml/ModelManager.kt` | Kotlin | T22 | Modell-Verfügbarkeit prüfen, SAF-Import, Zenodo-Download (FP16/FP32), listInstalledModels() | T22: neu; T28: ModelInfo, Zenodo-URLs, FP16/FP32; T37: setActiveModel(), getActiveModelFile(), deleteModel() |
| `ml/SpeciesNameResolver.kt` | Kotlin | T26 | Wissenschaftl. → Gemeinnamen (11 Sprachen, Fallback-Kette), searchSpecies() | T26: neu |
| `ml/RegionalSpeciesFilter.kt` | Kotlin | T11 | Artenfilter: region_sets.json v3 (AMSEL-kompatibel); Unterstrich-Normalisierung | T11: komplett neu auf AMSEL-Format (vorher dach.json-basiert); dach.json gelöscht |
| `ml/PhenologyRepository.kt` | Kotlin | T74 | Lädt species_phenology.json, Saison-Plausibilität, Rarität-Schwelle 0.7 | T74: neu |
| `ml/WatchlistManager.kt` | Kotlin | T20 | Watchlist laden (extern Downloads/PIROL/ → intern filesDir/), Alarm-Trigger | T21: watchedSpeciesFlow + entriesFlow (reaktiv), importFromUri() (SAF); T51b: ZonedDateTime-Timestamps; T58: Cooldown-Bugfix |
| `ml/WatchlistEntry.kt` | Kotlin | T20 | @Serializable data class: scientificName, commonName, WatchlistPriority (high/normal/low) | — |
| `ml/InferenceConfig.kt` | Kotlin | T11 | Konfigurations-Datenklasse mit Presets (DEFAULT/SENSITIVE/STRICT/POWER_SAVE/ULTRA_SAVE) | T29: inferenceIntervalMs + Presets; T34: highpassEnabled; T40: const CHUNK_DURATION_MS = 3000L |
| `ml/AudioBlock.kt` | Kotlin | T8 | Data-Klasse: akkumulierter Audio-Block mit Samples + Metadaten | — |
| `ml/AtlasCode.kt` | Kotlin | T68 | Typen/Konstanten für Atlascodes (CH-Set AC1–AC99) | T68: neu |

---

## 5. Data / Repository

| Datei | Sprache | Erstellt | Zweck | Changelog |
|-------|---------|----------|-------|-----------|
| `data/repository/SessionManager.kt` | Kotlin | T14 | Kern-Repository: Session-CRUD, detections.jsonl, verifications.jsonl, WAV schreiben (SAF + File-Dual-Mode) | T23: loadMetadata/loadDetections/loadDetectionsWithVerifications/getAudioChunks; T36: deleteSession(); T38: konfigurierbarer Basispfad (AppPreferences); T41: getMigrationInfo() + migrateSessionsTo(); T46: appendAudioSamples() statt writeAudioChunk(), getRecordingFile(); T47: exportRavenSelectionTable(); T49: getCurrentRecordingOffsetSec(); T51: SAF + Dual-Mode, Tages-Unterordner, Auto-Raven in endSession(), sessionEnded SharedFlow; T52: removeLastVerification(); T53: endSession(gpsStats); T57: Session-Rotation (segmentFileName, rotateRecordingSync, totalSamplesCompleted); T67: VerificationEvent + alle ActionTypes; T84: recordSimultaneousLink(); T85: loadSimultaneousLinkMap(); T86: removeLastVerification tmp+rename |
| `data/repository/WavWriter.kt` | Kotlin | T14 | object WavWriter: 44-Byte RIFF-Header, 16-bit PCM Mono; StreamingWavWriter für kontinuierliches Schreiben | T46: StreamingWavWriter ergänzt (RandomAccessFile); T51: FileChannel (forFile/forSaf Factory); T57: periodisches WAV-Header-Update (alle 20 Appends); T86: Int-Overflow-Cap minOf(0xFFFFFFFFL) |
| `data/repository/SessionMetadata.kt` | Kotlin | T14 | @Serializable: SessionID, Zeitraum, GPS-Bounds, regionFilter, confidenceThreshold | T46: totalChunks → totalRecordedSamples; T53: GpsStats (fixCount, rejectedCount, medianAccuracy, intervalMs); T57: RecordingSegment + recordingSegments (Multi-WAV) |
| `data/repository/SessionMigrationWorker.kt` | Kotlin | T51 | WorkManager CoroutineWorker: Session-Ordner im Hintergrund migrieren | T51: neu; T59: DocumentFile/ContentResolver für SAF-Pfade |
| `data/repository/SpeciesColorRepository.kt` | Kotlin | T80 | Lädt species_colors.json (filesDir), Regex-Parser, 12-Farben-Fallback via hashCode | T80: neu |
| `data/repository/AtlasCodeRepository.kt` | Kotlin | T68 | Lädt atlas_codes.json aus assets, lazy-cached | T68: neu |
| `data/repository/ReferenceRepository.kt` | Kotlin | T18 | Referenz-WAVs + index.json in filesDir/references/, addFromDetection(), getBySpecies() | T36: deleteReference(); T43: AudioPlayer.stop() statt release() in onCleared; T46: Quellpfad auf audio/recording.wav umgestellt; T51b: ZonedDateTime-Timestamps |
| `data/repository/ReferenceEntry.kt` | Kotlin | T18 | @Serializable data class: Referenz-Metadaten (Art, WAV-Dateiname, Herkunft, GPS) | — |
| `data/export/RavenExporter.kt` | Kotlin | T47 | Exportiert Session als Raven Selection Table (TSV, UTF-8, Locale.US) | T47: neu; T57: neue Spalten Begin File + Begin Date Time, Multi-Segment-Support, LOW_FREQ_HZ 0→150; T78: enabledFields-Parameter, 10 Toggle-Felder; T85: SimultaneousLinkSummary, FIELD_SIMULTAN/MOEGLICH/GLEICH, 6. Parameter linkMap |
| `data/AppPreferences.kt` | Kotlin | T22 | SharedPreferences-Wrapper: alle App-Settings als typisierte Properties (pirol_*-Prefix) | T26: speciesLanguage; T29: inferenceIntervalMs; T34: highpassEnabled; T35: prerollEnabled, prerollDurationSec; T38: storagePath; T51: storageBaseUri, storageDailySubfolder; T53: gpsMaxAccuracyMeters, gpsSmoothingEnabled, gpsIntervalSeconds; T57: maxRecordingMinutes; T71: topKThreshold, showOnlyFilteredSpecies; T74: phenologyEnabled; T75: similarSpeciesEnabled, similarSpeciesCount, marqueeMode; T78: ravenEnabled + 10 Feld-Toggles; T85: ravenFieldSimultan/Moeglich/Gleich |
| `data/StorageManager.kt` | Kotlin | T38 | Speicherpfad-Verwaltung: intern / SD-Karte via getExternalFilesDirs | T38: neu |

### Data / Sync

| Datei | Sprache | Erstellt | Zweck | Changelog |
|-------|---------|----------|-------|-----------|
| `data/sync/SessionUploadWorker.kt` | Kotlin | T17 | WorkManager CoroutineWorker: Session packen als ZIP, LocalExportTarget aufrufen | T17: neu |
| `data/sync/UploadManager.kt` | Kotlin | T17 | Orchestrierung via WorkManager OneTimeWorkRequest (WLAN-Constraint), status StateFlow | T17: neu |
| `data/sync/LocalExportTarget.kt` | Kotlin | T17 | UploadTarget-Implementierung: kopiert ZIP nach Downloads/PIROL/ | T17: neu |
| `data/sync/UploadTarget.kt` | Kotlin | T17 | Interface: name, suspend fun upload(zipFile, metadata, onProgress) | T17: neu |
| `data/sync/UploadStatus.kt` | Kotlin | T17 | Sealed class: Idle / InProgress / Success / Failed | T17: neu |

---

## 6. Dependency Injection

| Datei | Sprache | Erstellt | Zweck | Changelog |
|-------|---------|----------|-------|-----------|
| `di/AppModule.kt` | Kotlin | T2 | Koin-Modul: alle singles + viewModels | T2: OboeAudioEngine; T5: LiveViewModel; T7: BirdNetV3Classifier; T8: DetectionListState; T11: RegionalSpeciesFilter; T13: LocationProvider; T14: SessionManager; T17: UploadManager; T18: ReferenceRepository, AudioPlayer; T20: WatchlistManager, AlarmService; T22: AppPreferences, ModelManager; T26: SpeciesNameResolver; T28: single<AudioClassifier>; T31: MapViewModel; T38: StorageManager; T53: LocationProvider 2 Parameter; T80: SpeciesColorRepository; T82: AtlasCodeRepository; T83: MapViewModel 3. Parameter (LocationProvider) |

---

## 7. Location

| Datei | Sprache | Erstellt | Zweck | Changelog |
|-------|---------|----------|-------|-----------|
| `location/LocationProvider.kt` | Kotlin | T13 | FusedLocationProviderClient 10s-Intervall, StateFlow<LocationData?>, start()/stop() | T53: Accuracy-Filter, LastKnown-Fallback, Median-Smoothing (5 Fixes), GPS-Intervall konfigurierbar, buildGpsStats() |
| `location/LocationPermissionHandler.kt` | Kotlin | T13 | GPS-Permission-Flow (Granted/Denied/PermanentlyDenied), Rationale-Dialog | — |

---

## 8. UI — Karte

| Datei | Sprache | Erstellt | Zweck | Changelog |
|-------|---------|----------|-------|-----------|
| `ui/map/MapScreen.kt` | Kotlin | T31 | Karten-Composable: osmdroid MapView via AndroidView; Long-press via GestureDetectorCompat (setOnTouchListener); Center-Pin Move-Modus | T31: neu (Platzhalter → osmdroid); T79–T85: kompletter Rewrite D1–D5b (Filter-Chips, Live-Marker, Clustering, EditDetection, LongPress-Dialog, FocusMode, Polylinien); T87: X/Y-Fix + Cache-Config; T88: GestureDetectorCompat via setOnTouchListener (Compose PointerInteropFilter-Problem); T89: remember-Config entfernt, Center-Pin Move-UX, inMoveMode-Guard |
| `ui/map/MapViewModel.kt` | Kotlin | T31 | MapUiState (15 Felder), FilterMode, MapMarker, MarkerCluster, SimultaneousLink | T31: neu; T79: FilterMode ALL/LIVE_ONLY/RECENT_3, recordPositionMoved; T80: SpeciesColor; T81: computeClusters() O(n²) greedy; T82: editTarget/editDetection, lockedSpecies; T83: LongPressTarget, LocationProvider, Home-FAB; T84: focusMode, focusSpecies, linkAnchor/Target, recordSimultaneousLink() |
| `ui/map/SwisstopoTileSource.kt` | Kotlin | T83 | OnlineTileSourceBase: BGDI WMTS wmts.geo.admin.ch, URL {z}/{y}/{x}.jpeg, Zoom 13–18 | T83: neu; T87: X/Y getauscht ({z}/{x}/{y} → {z}/{y}/{x}) |

---

## 9. UI — Live-Screen

| Datei | Sprache | Erstellt | Zweck | Changelog |
|-------|---------|----------|-------|-----------|
| `ui/live/LiveScreen.kt` | Kotlin | T2 | Haupt-Live-UI: 3 adaptive Layouts (Compact/Medium/Expanded), FAB State-Machine, Sonogramm, DetectionList, Dialoge | T4: SpectrogramCanvas eingefügt; T5: ViewModel-Injection, Palette-Button; T6: Drei Layouts; T9: DetectionList; T10: DualPipeline; T11: InferenceConfigRow; T12b: SimilarityPanel; T13: LocationBar; T15: Export-Button; T16: Verifikations-Callbacks; T17: Upload-Button; T18: onSaveAsReference; T20: watchlistSpecies; T21: SAF-Import; T27: Kandidaten; T30: ScanStatusRow (ConfigChipRow entfernt); T32: .background() Dark-Mode-Fix; T33: 5s-Highlight; T44: onMarkUncertain; T45: onSelectAlternative; T51: KML entfernt, StorageFallbackBanner; T51c: Banner-Composable; T52: FAB 3-State (blau/grün/rot), Snackbar-Undo, GPS-Bar-Erweiterung; T76: Lifecycle-Guard, Preroll-SmallFAB; T77: +Art+Memo-Buttons; T86: Memo-Button Platzhalter-Dialog |
| `ui/live/LiveViewModel.kt` | Kotlin | T5 | Live-Aufnahme-ViewModel: RecordingService-Binding, Inferenz-Loop, Session-Management, 13+ Constructor-Parameter | T6: setSpectrogramConfig() Buffer-Clear; T11: RegionalSpeciesFilter; T13: LocationProvider; T14: SessionManager; T15: exportLastSessionAsKml(); T16: verifyDetection(); T17: UploadManager; T18: saveAsReference(), ReferenceRepository; T19: erste Smoketest; T20: WatchlistManager, AlarmService; T22: AppPreferences, ModelManager; T23: saveReference Timestamp-Fix; T26: SpeciesNameResolver; T27: Kandidaten nur Top-1; T28: AudioClassifier statt BirdNetV3Classifier; T30: spectrogramConfig + palette aus Prefs; T32: runBlocking für SpeciesNameResolver (fixiert Reihenfolge); T33: clear() bei Recording-Stop; T35: initPrerollIfEnabled(); T37: AudioClassifier; T43: CoroutineScope für onCleared(); T49: chunkStartSec session-relativ; T51: KML entfernt, Fallback-Flag; T51c: Instant.parse-Fix; T52: onFabTap(), SnackbarEvent, UndoEntry; T53: gpsStats an endSession; T70–T75: Cluster/Lock/Manuell/Alternative/Saisonalität/Ähnlich; T76: Lifecycle-Fix |
| `ui/live/LiveUiState.kt` | Kotlin | T5 | LiveUiState Data-Klasse mit allen UI-Feldern | T6: RecordingFabState; T9: detectionListState, isModelAvailable; T11: inferenceConfig; T13: GPS-Felder; T15: lastSessionId; T17: uploadStatus; T20: watchlistSpecies; T22: onboardingCompleted; T51: storageUnavailableFallback; T52: PREROLL_BUFFERING, recordingPhase, showTopNCandidates |

---

## 10. UI — Analyse-Screen

| Datei | Sprache | Erstellt | Zweck | Changelog |
|-------|---------|----------|-------|-----------|
| `ui/analysis/AnalysisScreen.kt` | Kotlin | T23 | Session-Browser + Detail + Vergleichs-Modus; AnimatedContent 3 Ebenen | T23: neu (Platzhalter → funktional); T24: CompareView, 3-Ebenen-Navigation; T36: Delete-Dialog; T39: onJumpToChunk; T46: chunk-Navigation entfernt (T46 WAV-Umbau); T47: Raven-Export-Button, Toast-Feedback; T48: Play-Button pro Detektion; T51c: ON_RESUME loadSessions-Refresh; T62: SAF-Lese-Support |
| `ui/analysis/AnalysisViewModel.kt` | Kotlin | T23 | Session laden, Verifikation, Sonogramm-Rendering, Audio-Playback | T23: neu; T24: ReferenceRepository 3. Parameter, openCompare/closeCompare; T36: deleteSession(); T39: jumpToDetection(), consumeScrollToTop(); T43: AudioPlayer.stop() statt release(); T46: recordingFile statt audioChunks; T47: exportRavenTable(), consumeRavenExportMessage(); T48: playDetection(detection), readWavDurationSec(); T51: sessionEnded.collect; T52: loadSessions() bei ON_RESUME; T53: gpsStats; T57: Segment-aware; T62: Streaming-Sonogramm |
| `ui/analysis/AnalysisUiState.kt` | Kotlin | T23 | Data-Klassen: SessionSummary, AnalysisUiState | T24: PlayingSource-Enum, compareDetection, referenceEntries; T46: recordingFile statt audioChunks; T48: recordingDurationSec; T52: recordingSizeBytes |

---

## 11. UI — Settings

| Datei | Sprache | Erstellt | Zweck | Changelog |
|-------|---------|----------|-------|-----------|
| `ui/settings/SettingsScreen.kt` | Kotlin | T17 | Alle Einstellungen: Audio, Scan, Modell, Energie, Speicher, Artennamen, Export, Watchlist | T20: Watchlist-Sektion; T21: SAF-Import, Reload-Button, Watchlist-Editor, verticalScroll; T22: AppPreferences-Injection; T26: Sprachauswahl-Dropdown; T28: Modell-Sektion (Download, Auswahl); T29: Energieprofil-RadioButtons; T30: Scan-Sektion (Preset/Palette/Confidence/Region) + ScanStatusRow in LiveScreen; T34: HPF-Toggle; T35: Preroll-Config; T37: Multi-Modell-UI mit Progress; T38: Speicherort-Sektion; T40: @Suppress menuAnchor-Deprecation; T41: Migrations-Dialog; T52: showTopNCandidates-Switch; T78: Raven-Toggle-Sektion; T85: Simultan/Moeglich/Gleich-Switches |

---

## 12. UI — Komponenten

| Datei | Sprache | Erstellt | Zweck | Changelog |
|-------|---------|----------|-------|-----------|
| `ui/components/SpeciesCard.kt` | Kotlin | T9 | Erkennungs-Karte: Art, Konfidenz-Bar, Verifikations-Buttons, Kandidaten | T16: onConfirm/onReject/onCorrect-Buttons, farbkodierte Ränder, Status-Badge; T18: onSaveAsReference; T24: onCompare; T27: Zaehler-Badge, aufklappbare Kandidaten-Liste; T33: 5s-Highlight animateColorAsState; T39: onJumpToChunk; T44: UNCERTAIN-Rand orange, ?-Button; T45: onSelectAlternative, klickbare Kandidaten-Zeilen; T48: onPlay-Button, playTimeLabel; T52: FilledIconButton 56dp, Haptik, rememberSaveable, animateContentSize, Sci-Name-Subtitle; T73: Lock-Badge + Dropdown; T74: Saison-Badge |
| `ui/components/DetectionList.kt` | Kotlin | T9 | LazyColumn aller Detektionen mit stabilen Keys | T18: onSaveAsReference; T44: onMarkUncertain; T45: onSelectAlternative; T52: showTopNCandidates-Parameter |
| `ui/components/EditDetectionDialog.kt` | Kotlin | T68 | Dialog: count-Stepper, atlasCode-Dropdown (CH-Set AC1–AC99), 500-Zeichen-Bemerkung | T68: neu |
| `ui/components/AddManualDetectionDialog.kt` | Kotlin | T72 | Dialog: Art-Autocomplete + GPS-Snapshot für manuelle Erfassung, ACTION_MANUAL_ADD | T72: neu |
| `ui/components/SpectrogramCanvas.kt` | Kotlin | T4 | Rolling-Sonogramm: Off-Screen-Bitmap, setPixels()-Batch, Y-Achse invertiert | T56: DynamicRangeMapper-Integration (autoContrast, manualRangeOverride); T62: Streaming-Verbesserung |
| `ui/components/SpectrogramOverlay.kt` | Kotlin | T4 | Overlays auf Sonogramm: Frequenzachse (Mel-skaliert), Zeitachse | — |
| `ui/components/SpectrogramState.kt` | Kotlin | T4 | Ring-Buffer für Sonogramm-Frames, frameVersion mutableIntStateOf, @Synchronized | T56: dynamicRangeMapper-Feld, mapper.update() in appendFrames(), mapper.reset() in clear() |
| `ui/components/VoiceMemoSection.kt` | Kotlin | T69 | UI für Sprach-Memo: 3 Zustände (idle/recording/done), Wellenform-Anzeige | T69: neu |
| `ui/components/SimilarityPanel.kt` | Kotlin | T12b | „Ähnliche Arten"-Panel: Liste oder Marquee-Laufschrift (5 letzte Detektionen) | T75: Refactor, DE-Namen, Dedup-Fix |
| `ui/components/SessionCard.kt` | Kotlin | T23 | Session-Karte in Analyse-Tab: Datum, Dauer, GPS, Detektionszahl | T36: onDelete-Button; T52: parseInstantCompat(), Dauer als mm:ss, Grösse in MB |
| `ui/components/ColorPalette.kt` | Kotlin | T4 | 3 Farbpaletten (MAGMA/VIRIDIS/GRAYSCALE) als IntArray(256)-LUT | — |

---

## 13. UI — Navigation, Onboarding, Referenzen, Theme, Utils

| Datei | Sprache | Erstellt | Zweck | Changelog |
|-------|---------|----------|-------|-----------|
| `ui/navigation/PirolNavigation.kt` | Kotlin | T1 | NavHost: 5 Tabs (Live/Analyse/Referenzen/Karte/Settings), BottomNav/Rail | T6: WindowSizeClass an LiveScreen; T22: Onboarding-Route; T83: WindowSizeClass an MapScreen |
| `ui/navigation/Screen.kt` | Kotlin | T1 | Screen-Konstanten / sealed class | T22: ONBOARDING_ROUTE |
| `ui/onboarding/OnboardingScreen.kt` | Kotlin | T22 | Onboarding-Flow: Region, Modell-Import, Permissions (3 Schritte) | T22: neu; T28: Modell-Download (Zenodo FP16/FP32), RadioButtons |
| `ui/reference/ReferenceScreen.kt` | Kotlin | T18 | Referenz-Bibliothek: Artenliste → Aufnahmen (2 Ebenen, AnimatedContent) | T36: Delete-Dialog; T40: getSpeciesCount()-Fix; T43: getSpeciesCount() via ViewModel |
| `ui/reference/ReferenceViewModel.kt` | Kotlin | T18 | Referenz-Verwaltung: loadIndex, selectSpecies, playRecording | T36: deleteReference(); T43: getSpeciesCount(scientificName) neue public Methode, stop() statt release() |
| `ui/reference/ReferenceUiState.kt` | Kotlin | T18 | ReferenceUiState Data-Klasse | — |
| `ui/theme/Theme.kt` | Kotlin | T1 | Material3-Theme (Light/Dark) | — |
| `ui/theme/Type.kt` | Kotlin | T1 | Typographie-Definitionen | — |
| `util/DateTimeUtils.kt` | Kotlin | T52 | parseInstantCompat(): OffsetDateTime.parse + Instant.parse-Fallback | T52: neu |

---

## 14. Assets / Ressourcen

| Datei | Sprache | Erstellt | Zweck | Changelog |
|-------|---------|----------|-------|-----------|
| `assets/species/species_master.json` | JSON | T26 | 11'560 Vogelarten: wissenschaftl. Name + 23 Sprachen (AMSEL-Format v3, ~10 MB) | T26: neu (aus AMSEL übernommen) |
| `assets/models/birdnet_v3_labels.csv` | CSV | T22 | BirdNET V3 Label-Taxonomie (11'561 Zeilen, Semikolon, BOM-tolerant) | T22: neu (ersetzt birdnet_v3_labels.txt mit 25 Platzhalter-Arten) |
| `assets/models/README.md` | Markdown | T7 | Hinweis: birdnet_v3.onnx manuell ablegen (zu gross für Repo) | — |
| `assets/regions/region_sets.json` | JSON | T11 | AMSEL-kompatible Regionsdaten v3: ch_breeding (180), ch_all (237), central_europe (302), all | T11: neu (ersetzt dach.json) |
| `assets/atlas_codes.json` | JSON | T68 | Schweizer Atlascode-Set CH initial, AC1–AC99 | T68: neu |
| `assets/map_manifest.json` | JSON | T83 | Karten-Konfiguration: OSM Zoom 1–12, Swisstopo Zoom 13–18 (Konfig-Doku; tileSourceForZoom() hartcodiert) | T83: neu |
| `assets/species_phenology.json` | JSON | T74 | Saisonalitäts-Daten (initial leer — User befüllt nach) | T74: neu |
| `res/xml/file_paths.xml` | XML | T15 | FileProvider-Pfade für SessionManager + Export | T15: neu; T51: export/-Pfad entfernt (KmlExporter gelöscht) |
| `res/values/strings.xml` | XML | T1 | App-Name ("PIROL") | — |
| `res/values/themes.xml` | XML | T1 | Theme-Referenz | — |
| `gradle/libs.versions.toml` | TOML | T1 | Version-Catalog: alle Dependency-Versionen | T7: onnxruntime; T13: play-services-location; T17: work-runtime-ktx; T28: ktor+okhttp; T31: osmdroid; T51: documentfile |

---

## 15. Laufzeit-Dateien (filesDir / cacheDir, nicht im Repo)

Diese Dateien werden zur Laufzeit erzeugt und sind **nicht** im git-Repository.

| Pfad | Erstellt durch | Zweck |
|------|---------------|-------|
| `filesDir/sessions/{date}_{uuid}/session.json` | SessionManager | Session-Metadaten (JSON) |
| `filesDir/sessions/{date}_{uuid}/detections.jsonl` | SessionManager | Detektionen (1 JSON-Objekt pro Zeile) |
| `filesDir/sessions/{date}_{uuid}/verifications.jsonl` | SessionManager | Verifikations-Events (VerificationEvent, alle ActionTypes) |
| `filesDir/sessions/{date}_{uuid}/recording.wav` | SessionManager | Durchgehende WAV-Aufnahme (seit T46; davor chunk_NNN.wav) |
| `filesDir/sessions/{date}_{uuid}/recording-002.wav` usw. | SessionManager | Rotations-Segmente (seit T57, wenn > maxRecordingMinutes) |
| `filesDir/sessions/{date}_{uuid}/recording.selections.txt` | RavenExporter | Auto-Raven-Export (seit T51, bei jedem endSession()) |
| `filesDir/sessions/{date}_{uuid}/memos/{id}.wav` | VoiceMemoRecorder | Sprach-Memos 16kHz Mono 16-bit |
| `filesDir/species_colors.json` | User / Import | Art → Farbe-Mapping (editierbar) |
| `filesDir/references/{species}/ref_NNN_{species}.wav` | ReferenceRepository | Referenz-WAVs |
| `filesDir/references/index.json` | ReferenceRepository | Referenz-Index |
| `filesDir/embeddings/embeddings.bsed` | EmbeddingDatabase | Binäre Embedding-DB (AMSEL-kompatibel) |
| `filesDir/models/birdnet_v3_fp16.onnx` usw. | ModelManager | ONNX-Modell (manuell oder per Download) |
| `cacheDir/osmdroid_tiles/` | osmdroid | Kachel-Cache (seit T89) |
| `Downloads/PIROL/YYYY-MM-DD/{sessionId}/` | SessionManager | SAF-Zielordner (sichtbar per USB seit T51) |

---

## Gelöschte / umbenannte Dateien (historisch)

| Datei | Gelöscht in | Grund |
|-------|-------------|-------|
| `assets/regions/dach.json` | T11 | Ersetzt durch region_sets.json (AMSEL-Format) |
| `assets/models/birdnet_v3_labels.txt` | T22 | Ersetzt durch birdnet_v3_labels.csv (11'560 Arten) |
| `data/repository/KmlExporter.kt` | T51 | KML-Export nie genutzt, entfernt |
| `data/repository/ShareHelper.kt` | T51 | KML-Export nie genutzt, entfernt |
| `audio/dsp/SessionCard.kt` (Chunk-Navigation) | T46 | Chunk-Buttons entfernt (Single-WAV-Umbau) |

---

## Verbleibende Unklarheiten

1. **birdnet_v3.onnx / birdnet_v3_fp16.onnx** — Wird nicht versioniert (40–516 MB). Muss manuell in `filesDir/models/` abgelegt oder via Settings/Onboarding heruntergeladen werden.

2. **Unit-Tests** — Testdateien unter `app/src/test/` und `app/src/androidTest/` nicht indexiert. Ca. 90 Tests vorhanden (Stand T86). Erste Tests ab T53 (GPS-Accuracy, Median-Smoothing, LocationProvider).

3. **ColorPalette.kt Ablage** — Im Index als eigenständige Datei geführt (T4); könnte theoretisch in `SpectrogramCanvas.kt` eingebettet sein. Im aktuellen Code als `ui/components/ColorPalette.kt` vorhanden.

4. **species_colors.json** — Laufzeit-Datei, kein Template im Repo. Kann via Einstellungen importiert werden (Cluster F offen).

5. **EmbeddingExtractor / MfccExtractor / EmbeddingDatabase** — Erstellzeitpunkt aus T12a erschlossen; genaue Package-Pfade im Repo nicht nochmals verifiziert. Logik: `ch.etasystems.pirol.ml`.

6. **AudioBlock.kt** — Unklar ob das `AccumulatedBlock` aus T8 oder eine separate Data-Klasse ist. Im Code wird `AudioBlock` als Parameter des InferenceWorker-Callbacks referenziert (seit T51).

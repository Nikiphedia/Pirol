package ch.etasystems.pirol.di

import ch.etasystems.pirol.audio.AudioPlayer
import ch.etasystems.pirol.audio.OboeAudioEngine
import ch.etasystems.pirol.data.AppPreferences
import ch.etasystems.pirol.data.SpectrogramSettingsRepository
import ch.etasystems.pirol.data.StorageManager
import ch.etasystems.pirol.data.repository.AtlasCodeRepository
import ch.etasystems.pirol.data.repository.ReferenceRepository
import ch.etasystems.pirol.data.repository.SessionManager
import ch.etasystems.pirol.data.repository.SpeciesColorRepository
import ch.etasystems.pirol.data.sync.UploadManager
import ch.etasystems.pirol.location.LocationProvider
import ch.etasystems.pirol.ml.AudioClassifier
import ch.etasystems.pirol.ml.BirdNetV3Classifier
import ch.etasystems.pirol.ml.DetectionListState
import ch.etasystems.pirol.ml.EmbeddingDatabase
import ch.etasystems.pirol.ml.EmbeddingExtractor
import ch.etasystems.pirol.ml.MfccExtractor
import ch.etasystems.pirol.ml.ModelManager
import ch.etasystems.pirol.ml.PhenologyRepository
import ch.etasystems.pirol.ml.RegionalSpeciesFilter
import ch.etasystems.pirol.ml.SpeciesNameResolver
import ch.etasystems.pirol.ml.PirolFolder
import ch.etasystems.pirol.ml.WatchlistManager
import ch.etasystems.pirol.audio.AlarmService
import ch.etasystems.pirol.ui.analysis.AnalysisViewModel
import ch.etasystems.pirol.ui.live.LiveViewModel
import ch.etasystems.pirol.ui.map.MapViewModel
import ch.etasystems.pirol.ui.reference.ReferenceViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val appModule = module {
    // Audio Engine — Singleton fuer die gesamte App-Laufzeit
    single { OboeAudioEngine() }

    // BirdNET V3.0 Classifier — Singleton, lazy ONNX Session (T28: als AudioClassifier registriert)
    single<AudioClassifier> { BirdNetV3Classifier(get(), get(), get()) }  // T103a: + SpeciesNameResolver

    // Regionaler Artenfilter — Singleton, laedt DACH-Artenliste aus assets/regions/
    single { RegionalSpeciesFilter(get(), get(), get(), get()) }  // T218: + AppPreferences
    single { ch.etasystems.pirol.ml.GeoFilterController(androidContext(), get(), get()) }  // T218

    // MFCC-Feature-Extractor — Singleton, pure Kotlin (kein Context)
    single { MfccExtractor() }

    // Embedding-Extractor — Singleton, nutzt AudioClassifier + MFCC-Fallback
    single { EmbeddingExtractor(get(), get()) }

    // Embedding-Datenbank — Singleton, leere DB (wird per load() befuellt)
    single { EmbeddingDatabase() }

    // Detektions-State — Singleton, wird von InferenceWorker befuellt und UI gelesen
    single { DetectionListState() }

    // GPS-Location-Provider — Singleton, Context + AppPreferences via Koin (T53)
    single { LocationProvider(get(), get()) }

    // T191: Routenaufzeichnung aus dem Positions-Flow, schreibt track.jsonl der aktiven Session
    single {
        val sm = get<SessionManager>()
        ch.etasystems.pirol.location.TrackRecorder(get<LocationProvider>().location, { line -> sm.appendTrackLine(line) })
    }

    // StorageManager — Singleton, ermittelt verfuegbare Speicherorte (T38)
    single { StorageManager(get()) }

    // Session-Manager — Singleton, verwaltet Aufnahme-Sessions auf Disk
    single { SessionManager(get(), get()) }

    // Upload-Manager — Singleton, orchestriert Session-Uploads via WorkManager
    single { UploadManager(get()) }

    // Referenzbibliothek — Singleton, verwaltet verifizierte Aufnahmen
    single { ReferenceRepository(get()) }

    // Audio-Player — Singleton, WAV-Playback fuer Referenzen
    // T62: Context fuer SAF-URI-Support (ContentResolver / setDataSource(context, uri))
    single { AudioPlayer(androidContext()) }

    // T173: zentrale Wiedergabe (ein Player, Start/Stopp-Schluessel) fuer Live, Analyse, Referenz, Karte, Memo
    single { ch.etasystems.pirol.audio.PlaybackController(get<AudioPlayer>()) }

    // WatchlistManager — Singleton, laedt/verwaltet Watchlist (T20)
    single { PirolFolder(get(), get()) }  // T129: SAF-Ordnerfreigabe
    single { WatchlistManager(get(), get()) }

    // AlarmService — Singleton, Notification + Vibration fuer Watchlist-Matches (T20/T58)
    single { ch.etasystems.pirol.ml.VerificationService(get(), get()) }  // T137
    single { AlarmService(get(), get(), get(), get()) }

    // AppPreferences — Singleton, SharedPreferences-Wrapper (T22)
    single { AppPreferences(get()) }

    // T107: gemeinsame Sonogramm-Darstellungs-Einstellungen (Live, Analyse, Settings)
    single { SpectrogramSettingsRepository(get()) }

    // SpeciesNameResolver — Singleton, uebersetzt Artnamen in gewaehlte Sprache (T26)
    single { SpeciesNameResolver(get()).also { it.scientificNames = get<AppPreferences>().scientificNamesDisplay } }  // T220

    // ModelManager — Singleton, Modell-Verfuegbarkeit + SAF-Import + Download (T22/T28/T37)
    single { ModelManager(get(), get()) }

    // AtlasCodeRepository — Singleton, laedt CH-Atlascodes aus assets/atlas_codes.json (T68)
    single { AtlasCodeRepository(androidContext()) }

    // PhenologyRepository — Singleton, laedt species_phenology.json (T74)
    single { PhenologyRepository(androidContext()) }

    // SpeciesColorRepository — Singleton, laedt species_colors.json aus filesDir (T80)
    single { SpeciesColorRepository(androidContext()) }

    // DetectionExporter — Singleton, Detektions-Ausschnitt teilen (T116)
    single { ch.etasystems.pirol.data.export.DetectionExporter(androidContext(), get(), get(), get()) }

    // T94: Embedding-DB-Datei (Laden/Modellwechsel/Speichern) — vorher im LiveViewModel
    single { ch.etasystems.pirol.audio.EmbeddingDbStore(androidContext(), get(), get(), get()) }

    // T94: Session-Pipeline (Persistenz + Inference + Detektionen), gesteuert vom RecordingService
    single {
        ch.etasystems.pirol.audio.RecordingPipeline(
            classifier = get(),
            regionalFilter = get(),
            detectionListState = get(),
            session = ch.etasystems.pirol.audio.SessionManagerPipelineSession(get()),
            hooks = ch.etasystems.pirol.audio.AndroidPipelineHooks(
                get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()  // T191: + TrackRecorder; T218: + GeoFilterController
            )
        )
    }

    // LiveViewModel — ueberlebt Configuration Changes, haelt DSP + ML + GPS Pipeline
    // T69: +1 Param (androidContext) fuer VoiceMemoRecorder SAF + memoAudioPlayer (14→15 get())
    viewModel { LiveViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), androidContext(), get<AudioPlayer>(), get()) }  // T173: + AudioPlayer, PlaybackController; T94: EmbeddingExtractor -> RecordingPipeline, + EmbeddingDbStore

    // ReferenceViewModel — Referenzbibliothek-UI
    viewModel { ReferenceViewModel(get(), get(), get(), androidContext(), get()) }  // T173: + PlaybackController

    // AnalysisViewModel — Session-Browser + Replay (T23) + Vergleich (T24)
    // T72: +1 Param (SpeciesNameResolver) fuer AddManualDetectionDialog (6→7 get())
    // T221: Rescan einer Aufnahme (eigene ORT-Session, eigener Listenfilter)
    single {
        ch.etasystems.pirol.rescan.RescanEngine(
            androidContext(), get(), get(), get(), get(), get(), get(), get<ch.etasystems.pirol.ml.AudioClassifier>(), get()
        )
    }
    viewModel { AnalysisViewModel(get(), get(), get(), get(), get(), get(), androidContext(), get(), get(), get()) }  // T173: + PlaybackController, T194: + UploadManager
     // SessionManager + AudioPlayer + ReferenceRepository + AppPreferences + AtlasCodeRepository + SpeciesNameResolver + Context (T72)

    // MapViewModel — Detektionen auf OSM-Karte (T31)
    // T83: +1 Param (LocationProvider) fuer Home-FAB (2→3 get())
    viewModel { MapViewModel(get(), get(), get(), get<AudioPlayer>(), get(), get(), get()) }  // T173: + AudioPlayer, PlaybackController, AppPreferences; T191: + TrackRecorder
     // SessionManager, DetectionListState, LocationProvider
}

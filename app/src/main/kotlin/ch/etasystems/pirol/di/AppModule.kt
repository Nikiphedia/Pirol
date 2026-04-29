package ch.etasystems.pirol.di

import ch.etasystems.pirol.audio.AudioPlayer
import ch.etasystems.pirol.audio.OboeAudioEngine
import ch.etasystems.pirol.data.AppPreferences
import ch.etasystems.pirol.data.StorageManager
import ch.etasystems.pirol.data.repository.AtlasCodeRepository
import ch.etasystems.pirol.data.repository.ReferenceRepository
import ch.etasystems.pirol.data.repository.SessionManager
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
    single<AudioClassifier> { BirdNetV3Classifier(get(), get()) }

    // Regionaler Artenfilter — Singleton, laedt DACH-Artenliste aus assets/regions/
    single { RegionalSpeciesFilter(get()) }

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

    // WatchlistManager — Singleton, laedt/verwaltet Watchlist (T20)
    single { WatchlistManager(get()) }

    // AlarmService — Singleton, Notification + Vibration fuer Watchlist-Matches (T20/T58)
    single { AlarmService(get(), get()) }

    // AppPreferences — Singleton, SharedPreferences-Wrapper (T22)
    single { AppPreferences(get()) }

    // SpeciesNameResolver — Singleton, uebersetzt Artnamen in gewaehlte Sprache (T26)
    single { SpeciesNameResolver(get()) }

    // ModelManager — Singleton, Modell-Verfuegbarkeit + SAF-Import + Download (T22/T28/T37)
    single { ModelManager(get(), get()) }

    // AtlasCodeRepository — Singleton, laedt CH-Atlascodes aus assets/atlas_codes.json (T68)
    single { AtlasCodeRepository(androidContext()) }

    // PhenologyRepository — Singleton, laedt species_phenology.json (T74)
    single { PhenologyRepository(androidContext()) }

    // LiveViewModel — ueberlebt Configuration Changes, haelt DSP + ML + GPS Pipeline
    // T69: +1 Param (androidContext) fuer VoiceMemoRecorder SAF + memoAudioPlayer (14→15 get())
    viewModel { LiveViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), androidContext()) }

    // ReferenceViewModel — Referenzbibliothek-UI
    viewModel { ReferenceViewModel(get(), get()) }

    // AnalysisViewModel — Session-Browser + Replay (T23) + Vergleich (T24)
    // T69: +1 Param (androidContext) fuer VoiceMemoRecorder SAF + memoAudioPlayer (5→6 get())
    viewModel { AnalysisViewModel(get(), get(), get(), get(), get(), androidContext()) }  // SessionManager + AudioPlayer + ReferenceRepository + AppPreferences + AtlasCodeRepository + Context (T69)

    // MapViewModel — Detektionen auf OSM-Karte (T31)
    viewModel { MapViewModel(get()) }  // SessionManager
}

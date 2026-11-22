package ch.etasystems.pirol.data

import android.content.Context
import ch.etasystems.pirol.ml.CHUNK_DURATION_MS
import ch.etasystems.pirol.ml.InferenceConfig

/**
 * SharedPreferences-Wrapper fuer persistente App-Einstellungen.
 * Keys mit Prefix pirol_ um Kollisionen zu vermeiden.
 */
/** T152: GPS-Modus-Werte (Persistenz in pirol_gps_mode). */
const val GPS_MODE_CONTINUOUS = "continuous"
const val GPS_MODE_ON_DETECTION = "on_detection"

class AppPreferences(context: Context) {

    private val prefs = context.getSharedPreferences("pirol_prefs", Context.MODE_PRIVATE)

    // --- Onboarding ---
    var onboardingCompleted: Boolean
        get() = prefs.getBoolean("pirol_onboarding_completed", false)
        set(value) = prefs.edit().putBoolean("pirol_onboarding_completed", value).apply()

    /** T183: zuletzt gesehene Changelog-Version (versionCode); null = nie gespeichert. */
    var lastSeenChangelogVersionCode: Int?
        get() = if (prefs.contains("pirol_last_seen_changelog")) prefs.getInt("pirol_last_seen_changelog", 0) else null
        set(value) { if (value != null) prefs.edit().putInt("pirol_last_seen_changelog", value).apply() }

    /** T195: Vollbild (Systemleisten ausblenden); als Flow, damit die Activity live umschaltet. */
    private val _fullscreen = kotlinx.coroutines.flow.MutableStateFlow(prefs.getBoolean("pirol_fullscreen", false))
    val fullscreenFlow: kotlinx.coroutines.flow.StateFlow<Boolean> = _fullscreen
    var fullscreen: Boolean
        get() = _fullscreen.value
        set(value) {
            prefs.edit().putBoolean("pirol_fullscreen", value).apply()
            _fullscreen.value = value
        }

    // --- InferenceConfig ---
    var confidenceThreshold: Float
        get() = prefs.getFloat("pirol_confidence_threshold", 0.5f)
        set(value) = prefs.edit().putFloat("pirol_confidence_threshold", value).apply()

    var topK: Int
        get() = prefs.getInt("pirol_top_k", 3)
        set(value) = prefs.edit().putInt("pirol_top_k", value).apply()

    /** Mindest-Konfidenz fuer TopK-Erkennung (0.3 = mehr Arten, 0.5 = nur sichere) */
    var topKThreshold: Float
        get() = prefs.getFloat("pirol_topk_threshold", 0.3f)
        set(value) = prefs.edit().putFloat("pirol_topk_threshold", value).apply()

    var regionFilter: String?
        get() = prefs.getString("pirol_region_filter", "ch_breeding")
        set(value) = prefs.edit().putString("pirol_region_filter", value).apply()

    var showOnlyFiltered: Boolean
        get() = prefs.getBoolean("pirol_show_only_filtered", true)
        set(value) = prefs.edit().putBoolean("pirol_show_only_filtered", value).apply()

    // --- Inference-Intervall (T29: Energiesparmodus) ---
    var inferenceIntervalMs: Long
        get() = prefs.getLong("pirol_inference_interval_ms", CHUNK_DURATION_MS)
        set(value) = prefs.edit().putLong("pirol_inference_interval_ms", value).apply()

    // --- Hochpassfilter (T34: Wind/Trittschall) ---
    var highpassEnabled: Boolean
        get() = prefs.getBoolean("pirol_highpass_enabled", true)
        set(value) = prefs.edit().putBoolean("pirol_highpass_enabled", value).apply()

    // --- Preroll-Puffer (T35) ---
    var prerollEnabled: Boolean
        get() = prefs.getBoolean("pirol_preroll_enabled", true)
        set(value) = prefs.edit().putBoolean("pirol_preroll_enabled", value).apply()

    var prerollDurationSec: Int
        get() = prefs.getInt("pirol_preroll_duration_sec", 10)
        set(value) = prefs.edit().putInt("pirol_preroll_duration_sec", value).apply()

    // --- Artennamen-Sprache (T26) ---
    var speciesLanguage: String
        get() = prefs.getString("pirol_species_language", "de") ?: "de"
        set(value) = prefs.edit().putString("pirol_species_language", value).apply()

    /**
     * T120a: true = Artnamen-Sprache wurde vom User explizit gesetzt; sonst folgt sie der App-Sprache.
     * Migration: ein bereits gespeicherter Sprachwert gilt als explizit.
     */
    var speciesLanguageExplicit: Boolean
        get() = prefs.getBoolean("pirol_species_language_explicit", prefs.contains("pirol_species_language"))
        set(value) {
            prefs.edit().putBoolean("pirol_species_language_explicit", value).apply()
            if (!value) prefs.edit().remove("pirol_species_language").apply()
        }

    /** T120a: wirksame Artnamen-Sprache (explizit oder gekoppelt an die App-Sprache) */
    fun effectiveSpeciesLanguage(): String =
        ch.etasystems.pirol.ui.AppLanguage.effectiveSpeciesLanguage(
            speciesLanguageExplicit, speciesLanguage,
            ch.etasystems.pirol.ui.AppLanguage.currentTag(),
            ch.etasystems.pirol.ui.AppLanguage.systemLanguage()
        )

    // --- Top-N Kandidaten-Anzeige (T52) ---
    /** true = Alternativen in SpeciesCard aufklappbar; false = Expand-Bereich verborgen. */
    var showTopNCandidates: Boolean
        get() = prefs.getBoolean("pirol_show_top_n_candidates", true)
        set(value) = prefs.edit().putBoolean("pirol_show_top_n_candidates", value).apply()

    // --- Kompakte Artkarte (T192) ---
    /** true = Artkarte als Einzeiler mit [Bestaetigen][Ablehnen][+]; false = Vollkarte (Default). */
    var compactSpeciesCard: Boolean
        get() = prefs.getBoolean("pirol_compact_species_card", false)
        set(value) = prefs.edit().putBoolean("pirol_compact_species_card", value).apply()

    // --- GPS (T53) ---
    /** Maximale akzeptierte GPS-Ungenauigkeit in Metern. Fixes schlechter als dieser Wert werden verworfen. */
    var gpsMaxAccuracyMeters: Float
        get() = prefs.getFloat("pirol_gps_max_accuracy_m", 50f)
        set(value) = prefs.edit().putFloat("pirol_gps_max_accuracy_m", value).apply()

    /** Median-Smoothing ueber 5 akzeptierte Fixes — reduziert Ausreisser-Spruenge. */
    var gpsSmoothingEnabled: Boolean
        get() = prefs.getBoolean("pirol_gps_smoothing_enabled", true)
        set(value) = prefs.edit().putBoolean("pirol_gps_smoothing_enabled", value).apply()

    /** GPS-Abfrage-Intervall in Sekunden. Wirkt ab naechster Session. Default 10 s. */
    var gpsIntervalSeconds: Int
        get() = prefs.getInt("pirol_gps_interval_sec", 10)
        set(value) = prefs.edit().putInt("pirol_gps_interval_sec", value).apply()

    /** T152: GPS-Modus: "continuous" (Dauernd, Standard) oder "on_detection" (Nur bei Detektion). */
    var gpsMode: String
        get() = prefs.getString("pirol_gps_mode", GPS_MODE_CONTINUOUS) ?: GPS_MODE_CONTINUOUS
        set(value) = prefs.edit().putString("pirol_gps_mode", value).apply()

    val gpsOnDetectionOnly: Boolean get() = gpsMode == GPS_MODE_ON_DETECTION

    /** T191: GPS-Spur waehrend der Aufnahme aufzeichnen (track.jsonl + track.gpx). Default an. */
    var trackRecordingEnabled: Boolean
        get() = prefs.getBoolean("pirol_track_recording_enabled", true)
        set(value) = prefs.edit().putBoolean("pirol_track_recording_enabled", value).apply()

    // --- Sonogramm-Config (T30) ---
    var spectrogramConfigName: String
        get() = prefs.getString("pirol_spectrogram_config", "BIRDS") ?: "BIRDS"
        set(value) = prefs.edit().putString("pirol_spectrogram_config", value).apply()

    var paletteName: String
        get() = prefs.getString("pirol_palette", "GRAYSCALE") ?: "GRAYSCALE"
        set(value) = prefs.edit().putString("pirol_palette", value).apply()

    // --- Sonogramm-Dynamik (T56: Auto-Kontrast + manueller dB-Bereich) ---
    /** Auto-Kontrast aktiv → Perzentil-basiertes Mapping; AUS → fester manualMin/MaxDb-Bereich. */
    var spectrogramAutoContrast: Boolean
        get() = prefs.getBoolean("pirol_spectrogram_auto_contrast", true)
        set(value) = prefs.edit().putBoolean("pirol_spectrogram_auto_contrast", value).apply()

    /** Manuelle Untergrenze (nur aktiv wenn Auto-Kontrast AUS). Default -80 dB wie altes Mapping. */
    var spectrogramMinDb: Float
        get() = prefs.getFloat("pirol_spectrogram_min_db", -80f)
        set(value) = prefs.edit().putFloat("pirol_spectrogram_min_db", value).apply()

    /** Manuelle Obergrenze (nur aktiv wenn Auto-Kontrast AUS). Default 0 dB wie altes Mapping. */
    var spectrogramMaxDb: Float
        get() = prefs.getFloat("pirol_spectrogram_max_db", 0f)
        set(value) = prefs.edit().putFloat("pirol_spectrogram_max_db", value).apply()

    /** Gamma-Kompression fuer Sonogramm-Anzeige (T56b). < 1.0 = leise Anteile heller. Default 0.5. */
    var spectrogramGamma: Float
        get() = prefs.getFloat("pirol_spectrogram_gamma", 0.5f)
        set(value) = prefs.edit().putFloat("pirol_spectrogram_gamma", value).apply()

    /**
     * Lautstärke-Deckel (Ceiling) fuer Sonogramm-Anzeige (T56b).
     * Signale ueber diesem dB-Wert werden auf die hellste Palette-Farbe geclippt.
     * 0 dB = kein Effekt (Mel-Werte sind bereits <= 0 dBFS).
     * Negativer Wert (z.B. -10) = Lautes abschneiden, mehr Kontrast fuer leise Signale.
     */
    var spectrogramCeilingDb: Float
        get() = prefs.getFloat("pirol_spectrogram_ceiling_db", 0f)
        set(value) = prefs.edit().putFloat("pirol_spectrogram_ceiling_db", value).apply()

    // --- T107: Sonogramm-Darstellung (Frequenzbereich, Zeitfenster, Auto-Kontrast-Parameter) ---
    // T144: neue Schluessel (_t144), damit alte gespeicherte 8-kHz-Grenzen ignoriert werden
    /** Untere Anzeige-Grenzfrequenz in Hz (wird auf den Bereich der SpectrogramConfig begrenzt). */
    var spectrogramFreqMinHz: Float
        get() = prefs.getFloat("pirol_spectrogram_freq_min_hz_t144", 0f)
        set(value) = prefs.edit().putFloat("pirol_spectrogram_freq_min_hz_t144", value).apply()

    /** Obere Anzeige-Grenzfrequenz in Hz (wird auf den Bereich der SpectrogramConfig begrenzt). */
    var spectrogramFreqMaxHz: Float
        get() = prefs.getFloat("pirol_spectrogram_freq_max_hz_t144", 24000f)
        set(value) = prefs.edit().putFloat("pirol_spectrogram_freq_max_hz_t144", value).apply()

    /** T193: Live-Darstellung (Enum-Name von LiveDisplayMode). */
    var liveDisplayMode: String
        get() = prefs.getString("pirol_live_display_mode", "SONOGRAM") ?: "SONOGRAM"
        set(value) = prefs.edit().putString("pirol_live_display_mode", value).apply()

    /** Zeitfenster des Live-Sonogramms in Sekunden. */
    var spectrogramWindowSec: Float
        get() = prefs.getFloat("pirol_spectrogram_window_sec", 12f)
        set(value) = prefs.edit().putFloat("pirol_spectrogram_window_sec", value).apply()

    /** Auto-Kontrast: unteres Perzentil (0..1). */
    var spectrogramAutoLowPct: Float
        get() = prefs.getFloat("pirol_spectrogram_auto_low_pct", 0.02f)
        set(value) = prefs.edit().putFloat("pirol_spectrogram_auto_low_pct", value).apply()

    /** Auto-Kontrast: oberes Perzentil (0..1). */
    var spectrogramAutoHighPct: Float
        get() = prefs.getFloat("pirol_spectrogram_auto_high_pct", 0.98f)
        set(value) = prefs.edit().putFloat("pirol_spectrogram_auto_high_pct", value).apply()

    /** Auto-Kontrast: Mindestspanne in dB. */
    var spectrogramAutoMinSpanDb: Float
        get() = prefs.getFloat("pirol_spectrogram_auto_min_span_db", 20f)
        set(value) = prefs.edit().putFloat("pirol_spectrogram_auto_min_span_db", value).apply()

    // --- Aktives Modell (T37) ---
    var activeModelFileName: String
        get() = prefs.getString("pirol_active_model", "birdnet_v3.onnx") ?: "birdnet_v3.onnx"
        set(value) = prefs.edit().putString("pirol_active_model", value).apply()

    // --- Speicherort (T38, erweitert T51) ---
    /** SAF-URI als String fuer user-konfigurierten Speicherort (null = Fallback auf getExternalFilesDir). */
    var storageBaseUri: String?
        get() = prefs.getString("pirol_storage_base_uri", null)
        set(value) = prefs.edit().putString("pirol_storage_base_uri", value).apply()

    /** Tages-Unterordner YYYY-MM-DD aktivieren (default true). */
    var storageDailySubfolder: Boolean
        get() = prefs.getBoolean("pirol_storage_daily_subfolder", true)
        set(value) = prefs.edit().putBoolean("pirol_storage_daily_subfolder", value).apply()

    // --- Session-Rotation (T57-B1) ---
    /** Maximale Aufnahmelaenge pro WAV-Datei in Minuten. Default 60. */
    var maxRecordingMinutes: Int
        get() = prefs.getInt("pirol_max_recording_minutes", 60)
        set(value) = prefs.edit().putInt("pirol_max_recording_minutes", value).apply()

    // --- Detektions-Ausschnitt (T116) ---
    /** Maximale Laenge des Detektions-Ausschnitts (Wiedergabe/Zoom/Export) in Sekunden. Default 30. */
    var excerptMaxSec: Int
        get() = prefs.getInt("pirol_excerpt_max_sec", 30)
        set(value) = prefs.edit().putInt("pirol_excerpt_max_sec", value).apply()

    // --- Watchlist-Alarm-Cooldown (T58) ---
    /** Pause zwischen Alarmen derselben Art in Minuten. 0 = jeder Treffer alarmiert. Default 5. */
    var alarmCooldownMinutes: Int
        get() = prefs.getInt("pirol_alarm_cooldown_minutes", 5)
        set(value) = prefs.edit().putInt("pirol_alarm_cooldown_minutes", value).apply()

    // --- Detektions-Cluster (T70) ---
    /** Raeumlich/zeitliches Zusammenfassen von Detektionen derselben Art. Default an. */
    var clusterEnabled: Boolean
        get() = prefs.getBoolean("pirol_cluster_enabled", true)
        set(value) = prefs.edit().putBoolean("pirol_cluster_enabled", value).apply()

    /** Cluster-Radius in Metern. 0 = kein Radius-Check (nur Zeit-Dedup). Default 10. */
    var clusterRadiusM: Int
        get() = prefs.getInt("pirol_cluster_radius_m", 10)
        set(value) = prefs.edit().putInt("pirol_cluster_radius_m", value).apply()

    /** Cluster-Zeitfenster in Minuten. 0 = kein Zeit-Check. Default 5. */
    var clusterTimeWindowMin: Int
        get() = prefs.getInt("pirol_cluster_timewindow_min", 5)
        set(value) = prefs.edit().putInt("pirol_cluster_timewindow_min", value).apply()

    // --- Saisonalitaet & Seltenheit (T74) ---
    var phenologyEnabled: Boolean
        get() = prefs.getBoolean("pirol_phenology_enabled", false)
        set(value) = prefs.edit().putBoolean("pirol_phenology_enabled", value).apply()

    // --- Aehnliche Arten (T75) ---
    var similarEnabled: Boolean
        get() = prefs.getBoolean("pirol_similar_enabled", true)
        set(value) = prefs.edit().putBoolean("pirol_similar_enabled", value).apply()

    /** 0.0=aus, 0.5=Laufschrift letzte 5, 1.0–10.0=Liste mit n Zeilen */
    var similarLines: Float
        get() = prefs.getFloat("pirol_similar_lines", 3.0f)
        set(value) = prefs.edit().putFloat("pirol_similar_lines", value).apply()

    // --- Auto-Loeschen kurzer leerer Sessions (T60) ---
    var autoDeleteShortEmptySessions: Boolean
        get() = prefs.getBoolean("pirol_autodelete_short_empty", true)
        set(value) = prefs.edit().putBoolean("pirol_autodelete_short_empty", value).apply()

    var autoDeleteMinSeconds: Int
        get() = prefs.getInt("pirol_autodelete_min_seconds", 10)
        set(value) = prefs.edit().putInt("pirol_autodelete_min_seconds", value).apply()

    // --- Raven-Export Felder (T78) ---
    var ravenFieldBeginTime: Boolean
        get() = prefs.getBoolean("pirol_raven_field_begin_time", true)
        set(value) = prefs.edit().putBoolean("pirol_raven_field_begin_time", value).apply()

    var ravenFieldEndTime: Boolean
        get() = prefs.getBoolean("pirol_raven_field_end_time", true)
        set(value) = prefs.edit().putBoolean("pirol_raven_field_end_time", value).apply()

    var ravenFieldLowFreq: Boolean
        get() = prefs.getBoolean("pirol_raven_field_low_freq", true)
        set(value) = prefs.edit().putBoolean("pirol_raven_field_low_freq", value).apply()

    var ravenFieldHighFreq: Boolean
        get() = prefs.getBoolean("pirol_raven_field_high_freq", true)
        set(value) = prefs.edit().putBoolean("pirol_raven_field_high_freq", value).apply()

    var ravenFieldSpecies: Boolean
        get() = prefs.getBoolean("pirol_raven_field_species", true)
        set(value) = prefs.edit().putBoolean("pirol_raven_field_species", value).apply()

    var ravenFieldConfidence: Boolean
        get() = prefs.getBoolean("pirol_raven_field_confidence", true)
        set(value) = prefs.edit().putBoolean("pirol_raven_field_confidence", value).apply()

    var ravenFieldBeginFile: Boolean
        get() = prefs.getBoolean("pirol_raven_field_begin_file", true)
        set(value) = prefs.edit().putBoolean("pirol_raven_field_begin_file", value).apply()

    var ravenFieldBeginDateTime: Boolean
        get() = prefs.getBoolean("pirol_raven_field_begin_date_time", true)
        set(value) = prefs.edit().putBoolean("pirol_raven_field_begin_date_time", value).apply()

    var ravenFieldLat: Boolean
        get() = prefs.getBoolean("pirol_raven_field_lat", true)
        set(value) = prefs.edit().putBoolean("pirol_raven_field_lat", value).apply()

    var ravenFieldLon: Boolean
        get() = prefs.getBoolean("pirol_raven_field_lon", true)
        set(value) = prefs.edit().putBoolean("pirol_raven_field_lon", value).apply()

    var ravenFieldSimultan: Boolean
        get() = prefs.getBoolean("pirol_raven_field_simultan", true)
        set(v) = prefs.edit().putBoolean("pirol_raven_field_simultan", v).apply()

    var ravenFieldMoeglich: Boolean
        get() = prefs.getBoolean("pirol_raven_field_moeglich", true)
        set(v) = prefs.edit().putBoolean("pirol_raven_field_moeglich", v).apply()

    var ravenFieldGleich: Boolean
        get() = prefs.getBoolean("pirol_raven_field_gleich", true)
        set(v) = prefs.edit().putBoolean("pirol_raven_field_gleich", v).apply()

    // --- Upload ---
    var wifiOnly: Boolean
        get() = prefs.getBoolean("pirol_wifi_only", true)
        set(value) = prefs.edit().putBoolean("pirol_wifi_only", value).apply()

    var autoUpload: Boolean
        get() = prefs.getBoolean("pirol_auto_upload", false)
        set(value) = prefs.edit().putBoolean("pirol_auto_upload", value).apply()

    // --- Embedding-DB Format-Version (T61) ---
    /** Version der gespeicherten Embedding-Basis; 0 = vor T61 (Embeddings aus Roh-Audio) */
    /** T97: Obergrenze der Embedding-DB (Eintraege). Ein Eintrag belegt rund 4 KB. Default 5000. */
    var embeddingDbMaxEntries: Int
        get() = prefs.getInt("pirol_embedding_db_max_entries", 5000)
        set(value) = prefs.edit().putInt("pirol_embedding_db_max_entries", value).apply()

    var embeddingDbVersion: Int
        get() = prefs.getInt("pirol_embedding_db_version", 0)
        set(value) = prefs.edit().putInt("pirol_embedding_db_version", value).apply()

    /** Gespeicherte InferenceConfig laden */
    fun loadInferenceConfig(): InferenceConfig {
        return InferenceConfig(
            confidenceThreshold = confidenceThreshold,
            topK = topK,
            topKThreshold = topKThreshold,
            regionFilter = regionFilter,
            showOnlyFiltered = showOnlyFiltered,
            inferenceIntervalMs = inferenceIntervalMs,
            highpassEnabled = highpassEnabled
        )
    }

    /** InferenceConfig speichern */
    fun saveInferenceConfig(config: InferenceConfig) {
        confidenceThreshold = config.confidenceThreshold
        topK = config.topK
        topKThreshold = config.topKThreshold
        regionFilter = config.regionFilter
        showOnlyFiltered = config.showOnlyFiltered
        inferenceIntervalMs = config.inferenceIntervalMs
        highpassEnabled = config.highpassEnabled
    }

    /** T108: gewaehlte Kartenquelle (MapSourceMode.name), Default AUTO */
    var mapSourceMode: String
        get() = prefs.getString("pirol_map_source_mode", "AUTO") ?: "AUTO"
        set(value) = prefs.edit().putString("pirol_map_source_mode", value).apply()

    /** T109: zuletzt verwendete Session-Bemerkung (Vorbelegung beim naechsten Start) */
    var lastSessionNote: String
        get() = prefs.getString("pirol_last_session_note", "") ?: ""
        set(value) = prefs.edit().putString("pirol_last_session_note", value).apply()

    /** T110: Ueberlappung der Analysefenster in Prozent (0, 50, 67), Default 0 */
    var overlapPercent: Int
        get() = prefs.getInt("pirol_overlap_percent", 0)
        set(value) = prefs.edit().putInt("pirol_overlap_percent", value).apply()

    // --- T124: Watchlist-Alarm-Ausgaenge (alle Default an) ---
    /** Vibrationsalarm bei Watchlist-Treffer */
    var alarmVibrationEnabled: Boolean
        get() = prefs.getBoolean("pirol_alarm_vibration", true)
        set(value) = prefs.edit().putBoolean("pirol_alarm_vibration", value).apply()

    /** Heads-up-Benachrichtigung bei Watchlist-Treffer */
    var alarmNotificationEnabled: Boolean
        get() = prefs.getBoolean("pirol_alarm_notification", true)
        set(value) = prefs.edit().putBoolean("pirol_alarm_notification", value).apply()

    /** Grosser Hinweis in der App bei Watchlist-Treffer */
    var alarmBannerEnabled: Boolean
        get() = prefs.getBoolean("pirol_alarm_banner", true)
        set(value) = prefs.edit().putBoolean("pirol_alarm_banner", value).apply()

    // --- T125: Upload-Ziel (Passwort liegt NICHT hier, sondern im UploadCredentialStore) ---

    /** UploadTargetType.name, Default OFF (nichts wird automatisch hochgeladen) */
    var uploadTargetType: String
        get() = prefs.getString("pirol_upload_target_type", "OFF") ?: "OFF"
        set(value) = prefs.edit().putString("pirol_upload_target_type", value).apply()

    /** SAF-Baum-URI des gewaehlten Zielordners */
    var uploadFolderUri: String
        get() = prefs.getString("pirol_upload_folder_uri", "") ?: ""
        set(value) = prefs.edit().putString("pirol_upload_folder_uri", value).apply()

    var smbHost: String
        get() = prefs.getString("pirol_smb_host", "") ?: ""
        set(value) = prefs.edit().putString("pirol_smb_host", value).apply()

    var smbShare: String
        get() = prefs.getString("pirol_smb_share", "") ?: ""
        set(value) = prefs.edit().putString("pirol_smb_share", value).apply()

    var smbPath: String
        get() = prefs.getString("pirol_smb_path", "PIROL") ?: "PIROL"
        set(value) = prefs.edit().putString("pirol_smb_path", value).apply()

    var smbUser: String
        get() = prefs.getString("pirol_smb_user", "") ?: ""
        set(value) = prefs.edit().putString("pirol_smb_user", value).apply()

    /** Lokale ZIP-Kopie nach erfolgreichem Upload weglassen (Default false = Kopie in Downloads/PIROL bleibt) */
    var deleteLocalZipAfterUpload: Boolean
        get() = prefs.getBoolean("pirol_upload_delete_local_zip", false)
        set(value) = prefs.edit().putBoolean("pirol_upload_delete_local_zip", value).apply()

    /** Zeitpunkt des letzten Upload-Versuchs (ms, 0 = nie) */
    var lastUploadTimeMs: Long
        get() = prefs.getLong("pirol_last_upload_time", 0L)
        set(value) = prefs.edit().putLong("pirol_last_upload_time", value).apply()

    /** Ergebnistext des letzten Upload-Versuchs (Deutsch) */
    var lastUploadResult: String
        get() = prefs.getString("pirol_last_upload_result", "") ?: ""
        set(value) = prefs.edit().putString("pirol_last_upload_result", value).apply()

    /** T133: Resampler-Verfahren "POLYPHASE" (Default, Anti-Alias) | "LINEAR" (alt). Setzt auch AudioResampler.defaultMode. */
    var resamplerMode: String
        get() = prefs.getString("pirol_resampler_mode", "POLYPHASE") ?: "POLYPHASE"
        set(value) {
            prefs.edit().putString("pirol_resampler_mode", value).apply()
            ch.etasystems.pirol.audio.dsp.AudioResampler.defaultMode =
                ch.etasystems.pirol.audio.dsp.AudioResampler.Mode.parse(value)
        }

    /** T134: Auf-/Zuklapp-Zustand einer Einstellungs-Sektion */
    fun isSettingsSectionExpanded(key: String, default: Boolean): Boolean =
        prefs.getBoolean("pirol_settings_section_$key", default)

    fun setSettingsSectionExpanded(key: String, expanded: Boolean) {
        prefs.edit().putBoolean("pirol_settings_section_$key", expanded).apply()
    }

    /** T129: freigegebener PIROL-Ordner (SAF-Tree-URI, persistierbar); null = keine Freigabe */
    var pirolFolderUri: String?
        get() = prefs.getString("pirol_folder_uri", null)
        set(value) = prefs.edit().putString("pirol_folder_uri", value).apply()
}

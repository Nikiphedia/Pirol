package ch.etasystems.pirol.data

import android.content.Context
import ch.etasystems.pirol.ml.CHUNK_DURATION_MS
import ch.etasystems.pirol.ml.InferenceConfig

/**
 * SharedPreferences-Wrapper fuer persistente App-Einstellungen.
 * Keys mit Prefix pirol_ um Kollisionen zu vermeiden.
 */
class AppPreferences(context: Context) {

    private val prefs = context.getSharedPreferences("pirol_prefs", Context.MODE_PRIVATE)

    // --- Onboarding ---
    var onboardingCompleted: Boolean
        get() = prefs.getBoolean("pirol_onboarding_completed", false)
        set(value) = prefs.edit().putBoolean("pirol_onboarding_completed", value).apply()

    // --- InferenceConfig ---
    var confidenceThreshold: Float
        get() = prefs.getFloat("pirol_confidence_threshold", 0.5f)
        set(value) = prefs.edit().putFloat("pirol_confidence_threshold", value).apply()

    var topK: Int
        get() = prefs.getInt("pirol_top_k", 5)
        set(value) = prefs.edit().putInt("pirol_top_k", value).apply()

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

    // --- Top-N Kandidaten-Anzeige (T52) ---
    /** true = Alternativen in SpeciesCard aufklappbar; false = Expand-Bereich verborgen. */
    var showTopNCandidates: Boolean
        get() = prefs.getBoolean("pirol_show_top_n_candidates", true)
        set(value) = prefs.edit().putBoolean("pirol_show_top_n_candidates", value).apply()

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

    // --- Aktives Modell (T37) ---
    var activeModelFileName: String
        get() = prefs.getString("pirol_active_model", "birdnet_v3.onnx") ?: "birdnet_v3.onnx"
        set(value) = prefs.edit().putString("pirol_active_model", value).apply()

    // --- Speicherort (T38, erweitert T51) ---
    /** Veralteter Pfad-Key (pre-T51). Wird noch fuer einen Release als Fallback genutzt. */
    var storagePath: String?
        get() = prefs.getString("pirol_storage_path", null)
        set(value) = prefs.edit().putString("pirol_storage_path", value).apply()

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

    // --- Upload ---
    var wifiOnly: Boolean
        get() = prefs.getBoolean("pirol_wifi_only", true)
        set(value) = prefs.edit().putBoolean("pirol_wifi_only", value).apply()

    var autoUpload: Boolean
        get() = prefs.getBoolean("pirol_auto_upload", false)
        set(value) = prefs.edit().putBoolean("pirol_auto_upload", value).apply()

    /** Gespeicherte InferenceConfig laden */
    fun loadInferenceConfig(): InferenceConfig {
        return InferenceConfig(
            confidenceThreshold = confidenceThreshold,
            topK = topK,
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
        regionFilter = config.regionFilter
        showOnlyFiltered = config.showOnlyFiltered
        inferenceIntervalMs = config.inferenceIntervalMs
        highpassEnabled = config.highpassEnabled
    }
}

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

    // --- Sonogramm-Config (T30) ---
    var spectrogramConfigName: String
        get() = prefs.getString("pirol_spectrogram_config", "BIRDS") ?: "BIRDS"
        set(value) = prefs.edit().putString("pirol_spectrogram_config", value).apply()

    var paletteName: String
        get() = prefs.getString("pirol_palette", "GRAYSCALE") ?: "GRAYSCALE"
        set(value) = prefs.edit().putString("pirol_palette", value).apply()

    // --- Aktives Modell (T37) ---
    var activeModelFileName: String
        get() = prefs.getString("pirol_active_model", "birdnet_v3.onnx") ?: "birdnet_v3.onnx"
        set(value) = prefs.edit().putString("pirol_active_model", value).apply()

    // --- Speicherort (T38) ---
    var storagePath: String?
        get() = prefs.getString("pirol_storage_path", null)
        set(value) = prefs.edit().putString("pirol_storage_path", value).apply()

    // --- Karten-Quelle (T45) ---
    var mapTileSourceId: String
        get() = prefs.getString("pirol_map_tile_source", "osm") ?: "osm"
        set(value) = prefs.edit().putString("pirol_map_tile_source", value).apply()

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

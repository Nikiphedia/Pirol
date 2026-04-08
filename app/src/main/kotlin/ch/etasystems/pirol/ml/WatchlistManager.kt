package ch.etasystems.pirol.ml

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * Laedt, speichert und verwaltet die Species-Watchlist.
 *
 * Prioritaet beim Laden:
 * 1. Externe Datei: Downloads/PIROL/watchlist.json (vom Benutzer uebertragen)
 * 2. Interne Datei: filesDir/watchlist.json (Backup / Letztstand)
 *
 * Thread-safe: Alle IO-Operationen laufen auf Dispatchers.IO via suspend.
 * Exponiert watchedSpeciesFlow als StateFlow fuer reaktive UI-Updates.
 */
class WatchlistManager(private val context: Context) {

    companion object {
        private const val TAG = "WatchlistManager"
        private const val INTERNAL_FILE = "watchlist.json"
        private const val EXTERNAL_DIR = "PIROL"
        private const val EXTERNAL_FILE = "watchlist.json"
    }

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private var watchlist: Watchlist = Watchlist()

    // Reaktiver StateFlow fuer UI-Beobachtung (T21)
    private val _watchedSpeciesFlow = MutableStateFlow<Set<String>>(emptySet())
    val watchedSpeciesFlow: StateFlow<Set<String>> = _watchedSpeciesFlow.asStateFlow()

    // Reaktiver StateFlow fuer Watchlist-Eintraege (T21 — SettingsScreen)
    private val _entriesFlow = MutableStateFlow<List<WatchlistEntry>>(emptyList())
    val entriesFlow: StateFlow<List<WatchlistEntry>> = _entriesFlow.asStateFlow()

    /** Aktive Watchlist-Arten als Set (schneller Lookup) */
    val watchedSpecies: Set<String>
        get() = watchlist.species.map { it.scientificName }.toSet()

    /** Alle Eintraege */
    val entries: List<WatchlistEntry>
        get() = watchlist.species

    /** Name der Watchlist */
    val name: String
        get() = watchlist.name

    /** Ob eine Art auf der Watchlist steht */
    fun isWatched(scientificName: String): Boolean {
        val normalized = scientificName.replace(' ', '_')
        return watchedSpecies.contains(normalized)
    }

    /** Priority fuer eine Art (null wenn nicht auf Watchlist) */
    fun getPriority(scientificName: String): WatchlistPriority? {
        val normalized = scientificName.replace(' ', '_')
        return watchlist.species.find { it.scientificName == normalized }?.priority
    }

    /** Flows nach Aenderung aktualisieren */
    private fun emitFlows() {
        _watchedSpeciesFlow.value = watchedSpecies
        _entriesFlow.value = watchlist.species
    }

    /**
     * Watchlist laden. Prueft zuerst externe Quelle (Downloads/PIROL/),
     * dann interne Datei (filesDir/).
     *
     * Wenn externe Datei vorhanden → externe uebernehmen und intern kopieren.
     */
    suspend fun load() = withContext(Dispatchers.IO) {
        // 1. Externe Datei pruefen (Downloads/PIROL/watchlist.json)
        val externalFile = getExternalWatchlistFile()
        val internalFile = File(context.filesDir, INTERNAL_FILE)

        if (externalFile != null && externalFile.exists()) {
            try {
                val externalWatchlist = json.decodeFromString<Watchlist>(externalFile.readText())
                watchlist = externalWatchlist
                // Intern kopieren (als Backup / fuer naechsten Start ohne SD)
                internalFile.writeText(json.encodeToString(Watchlist.serializer(), watchlist))
                Log.i(TAG, "Externe Watchlist geladen: ${watchlist.species.size} Arten " +
                        "<- ${externalFile.absolutePath}")
                emitFlows()
                return@withContext
            } catch (e: Exception) {
                Log.e(TAG, "Externe Watchlist fehlerhaft", e)
            }
        }

        // 2. Interne Datei laden
        if (internalFile.exists()) {
            try {
                watchlist = json.decodeFromString<Watchlist>(internalFile.readText())
                Log.i(TAG, "Interne Watchlist geladen: ${watchlist.species.size} Arten")
            } catch (e: Exception) {
                Log.e(TAG, "Interne Watchlist fehlerhaft", e)
                watchlist = Watchlist()
            }
        } else {
            Log.i(TAG, "Keine Watchlist gefunden — leer")
        }
        emitFlows()
    }

    /** Speichert aktuelle Watchlist intern */
    suspend fun save() = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, INTERNAL_FILE)
        file.writeText(json.encodeToString(Watchlist.serializer(), watchlist))
        Log.i(TAG, "Watchlist gespeichert: ${watchlist.species.size} Arten")
    }

    /**
     * Laedt Watchlist aus einer URI (vom SAF File-Picker).
     * Kopiert den Inhalt nach filesDir/watchlist.json fuer kuenftige Starts.
     */
    suspend fun importFromUri(uri: Uri) = withContext(Dispatchers.IO) {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("URI nicht lesbar: $uri")
        val content = inputStream.bufferedReader().readText()
        inputStream.close()

        val imported = json.decodeFromString<Watchlist>(content)
        watchlist = imported
        save()
        emitFlows()
        Log.i(TAG, "Watchlist importiert via SAF: ${watchlist.species.size} Arten")
    }

    /** Art manuell hinzufuegen */
    suspend fun addSpecies(entry: WatchlistEntry) {
        val current = watchlist.species.toMutableList()
        // Duplikat-Check
        if (current.none { it.scientificName == entry.scientificName }) {
            current.add(entry)
            watchlist = watchlist.copy(
                species = current,
                updatedAt = java.time.Instant.now().toString()
            )
            save()
            emitFlows()
        }
    }

    /** Art entfernen */
    suspend fun removeSpecies(scientificName: String) {
        val current = watchlist.species.toMutableList()
        current.removeAll { it.scientificName == scientificName }
        watchlist = watchlist.copy(
            species = current,
            updatedAt = java.time.Instant.now().toString()
        )
        save()
        emitFlows()
    }

    /**
     * Sucht die externe Watchlist-Datei.
     * Prueft: Downloads/PIROL/watchlist.json
     */
    private fun getExternalWatchlistFile(): File? {
        val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS
        )
        val pirolDir = File(downloadsDir, EXTERNAL_DIR)
        val file = File(pirolDir, EXTERNAL_FILE)
        return if (file.exists()) file else null
    }
}

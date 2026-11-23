package ch.etasystems.pirol.ml

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
class WatchlistManager(private val context: Context, private val pirolFolder: PirolFolder? = null) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private var watchlist: Watchlist = Watchlist()

    // Reaktiver StateFlow fuer UI-Beobachtung (T21)
    private val _watchedSpeciesFlow = MutableStateFlow<Set<String>>(emptySet())
    val watchedSpeciesFlow: StateFlow<Set<String>> = _watchedSpeciesFlow.asStateFlow()

    // Reaktiver StateFlow fuer Watchlist-Eintraege (T21 — SettingsScreen)
    private val _entriesFlow = MutableStateFlow<List<WatchlistEntry>>(emptyList())
    val entriesFlow: StateFlow<List<WatchlistEntry>> = _entriesFlow.asStateFlow()

    /** Normalisiert Artnamen: Leerzeichen → Unterstrich (Turdus merula → Turdus_merula) */
    private fun normalizeName(name: String): String = name.replace(' ', '_')

    /** Aktive Watchlist-Arten als Set kanonischer Schluessel (T105: Synonyme aufgeloest, Kleinschreibung) */
    val watchedSpecies: Set<String>
        get() = entries.map { SpeciesKey.of(it.scientificName) }.toSet()

    /** Alle aktiven Eintraege (abgelaufene 24-h-Alarme fehlen, T244) */
    val entries: List<WatchlistEntry>
        get() = WatchlistMatcher.active(watchlist.species, System.currentTimeMillis())

    /** Name der Watchlist */
    val name: String
        get() = watchlist.name

    /** Ob eine Art auf der Watchlist steht */
    fun isWatched(scientificName: String): Boolean {
        return WatchlistMatcher.findEntry(watchlist.species, scientificName) != null
    }

    /** Priority fuer eine Art (null wenn nicht auf Watchlist) */
    fun getPriority(scientificName: String): WatchlistPriority? {
        return WatchlistMatcher.findEntry(watchlist.species, scientificName)?.priority
    }

    /**
     * Kombinierter Match+Cooldown-Check fuer AlarmService.
     * Testbar: Companion-Funktion enthaelt die reine Logik ohne Android-Abhaengigkeit.
     */
    fun matchAndCheckCooldown(
        scientificName: String,
        cooldownMs: Long,
        lastAlarmTimes: Map<String, Long>
    ): Boolean = matchAndCheckCooldown(
        normalizeName(scientificName), watchedSpecies, cooldownMs, lastAlarmTimes,
        System.currentTimeMillis()
    )

    companion object {
        private const val TAG = "WatchlistManager"
        private const val INTERNAL_FILE = "watchlist.json"
        private const val EXTERNAL_DIR = "PIROL"
        private const val EXTERNAL_FILE = "watchlist.json"
        private const val EXTERNAL_HASH_FILE = "watchlist.external.hash"

        /** Reine Cooldown-Logik — frei von Android-Abhaengigkeiten, direkt unit-testbar. */
        internal fun matchAndCheckCooldown(
            normalizedName: String,
            watchedSet: Set<String>,
            cooldownMs: Long,
            lastAlarmTimes: Map<String, Long>,
            now: Long
        ): Boolean {
            val k = WatchlistMatcher.key(normalizedName)
            if (watchedSet.none { WatchlistMatcher.key(it) == k }) return false
            return WatchlistMatcher.isCooldownOver(lastAlarmTimes[normalizedName], cooldownMs, now)
        }
    }

    /** Flows nach Aenderung aktualisieren */
    private fun emitFlows() {
        _watchedSpeciesFlow.value = watchedSpecies
        _entriesFlow.value = entries
        scheduleExpiry()
    }

    // T250/N6: alle Aenderungen (Ablauf-Timer, Alarm setzen/loeschen, Import) nacheinander
    private val mutex = Mutex()

    // T247: Abzeichen verschwindet beim Ablauf eines 24-h-Alarms sofort (ein Job fuer den naechsten Ablauf)
    private val expiryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var expiryJob: Job? = null

    private fun scheduleExpiry() {
        expiryJob?.cancel()
        val next = watchlist.species.mapNotNull { it.expiresAtMs }.minOrNull() ?: return
        val wait = (next - System.currentTimeMillis()).coerceAtLeast(0L) + 100L
        expiryJob = expiryScope.launch {
            delay(wait)
            refreshExpired()
        }
    }

    /** T244: abgelaufene Eintraege dauerhaft entfernen (beim Laden/Pruefen und per Ablauf-Timer, siehe scheduleExpiry) */
    private fun pruneExpired(): Boolean {
        val active = WatchlistMatcher.active(watchlist.species, System.currentTimeMillis())
        if (active.size == watchlist.species.size) return false
        watchlist = watchlist.copy(species = active)
        return true
    }

    /** T244: abgelaufene entfernen und Flows aktualisieren (z.B. beim Anzeigen) */
    suspend fun refreshExpired() {
        mutex.withLock {
        if (pruneExpired()) { save(); emitFlows() }
        }
    }

    /**
     * T244: Alarm fuer eine Art setzen (expiresAtMs null = dauerhaft).
     * @return Zustand vorher (fuer Undo ueber [restore])
     */
    suspend fun setAlarm(scientificName: String, commonName: String, expiresAtMs: Long?): Watchlist {
        return mutex.withLock {
        val previous = watchlist
        watchlist = watchlist.copy(
            species = WatchlistMatcher.withAlarm(watchlist.species, scientificName, commonName, expiresAtMs),
            updatedAt = nowIso()
        )
        save()
        emitFlows()
        previous
        }
    }

    /** T244: Alarm fuer eine Art entfernen. @return Zustand vorher (Undo) */
    suspend fun clearAlarm(scientificName: String): Watchlist {
        return mutex.withLock {
        val previous = watchlist
        watchlist = watchlist.copy(
            species = WatchlistMatcher.withoutAlarm(watchlist.species, scientificName),
            updatedAt = nowIso()
        )
        save()
        emitFlows()
        previous
        }
    }

    private fun nowIso(): String = java.time.ZonedDateTime.now(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    /**
     * Watchlist laden. Prueft zuerst externe Quelle (Downloads/PIROL/),
     * dann interne Datei (filesDir/).
     *
     * Wenn externe Datei vorhanden → externe uebernehmen und intern kopieren.
     */
    suspend fun load() = mutex.withLock { withContext(Dispatchers.IO) {
        SpeciesKey.load(context)
        // 0. T129: freigegebener Ordner (SAF) hat Vorrang, umgeht Scoped Storage
        val grantedText = try { pirolFolder?.readText(EXTERNAL_FILE) } catch (e: Exception) { null }
        // T244: externe Datei nur uebernehmen, wenn sie sich seit der letzten Uebernahme geaendert hat
        val hashFile = File(context.filesDir, EXTERNAL_HASH_FILE)
        val lastHash = try { hashFile.takeIf { it.exists() }?.readText() } catch (e: Exception) { null }
        if (grantedText != null && WatchlistMatcher.shouldAdoptExternal(grantedText, lastHash)) {
            try {
                watchlist = json.decodeFromString<Watchlist>(grantedText)
                pruneExpired()
                File(context.filesDir, INTERNAL_FILE).writeText(json.encodeToString(Watchlist.serializer(), watchlist))
                hashFile.writeText(grantedText.hashCode().toString())
                Log.i(TAG, "Watchlist aus freigegebenem Ordner geladen: ${watchlist.species.size} Arten")
                emitFlows()
                return@withContext
            } catch (e: Exception) {
                Log.e(TAG, "Watchlist im freigegebenen Ordner fehlerhaft", e)
            }
        }
        // 1. Externe Datei pruefen (Downloads/PIROL/watchlist.json)
        val externalFile = getExternalWatchlistFile()
        val internalFile = File(context.filesDir, INTERNAL_FILE)

        val externalText = if (grantedText == null && externalFile != null && externalFile.exists())
            try { externalFile.readText() } catch (e: Exception) { null } else null
        if (externalText != null && WatchlistMatcher.shouldAdoptExternal(externalText, lastHash)) {
            try {
                val externalWatchlist = json.decodeFromString<Watchlist>(externalText)
                watchlist = externalWatchlist
                pruneExpired()
                // Intern kopieren (als Backup / fuer naechsten Start ohne SD)
                internalFile.writeText(json.encodeToString(Watchlist.serializer(), watchlist))
                hashFile.writeText(externalText.hashCode().toString())
                Log.i(TAG, "Externe Watchlist geladen: ${watchlist.species.size} Arten " +
                        "<- ${externalFile?.absolutePath}")
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
                if (pruneExpired()) internalFile.writeText(json.encodeToString(Watchlist.serializer(), watchlist))
            } catch (e: Exception) {
                Log.e(TAG, "Interne Watchlist fehlerhaft", e)
                watchlist = Watchlist()
            }
        } else {
            Log.i(TAG, "Keine Watchlist gefunden — leer")
        }
        emitFlows()
    } }

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
        mutex.withLock {
            watchlist = imported
            save()
            emitFlows()
            Log.i(TAG, "Watchlist importiert via SAF: ${watchlist.species.size} Arten")
        }
    }

    /** Art manuell hinzufuegen */
    suspend fun addSpecies(entry: WatchlistEntry) {
        mutex.withLock {
        val current = watchlist.species.toMutableList()
        // Duplikat-Check
        if (current.none { it.scientificName == entry.scientificName }) {
            current.add(entry)
            watchlist = watchlist.copy(
                species = current,
                updatedAt = java.time.ZonedDateTime.now(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            )
            save()
            emitFlows()
        }
        }
    }

    /**
     * T190: Artenliste in die Watchlist uebernehmen (Zusammenfuehren oder Ersetzen).
     * @return Zustand vor der Uebernahme (fuer Undo ueber [restore])
     */
    suspend fun adoptFromList(
        list: CustomSpeciesList,
        merge: Boolean,
        commonName: (String) -> String
    ): Watchlist {
        return mutex.withLock {
        val previous = watchlist
        val now = java.time.ZonedDateTime.now(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        watchlist = WatchlistFromList.apply(previous, list, merge, commonName, now)
        save()
        emitFlows()
        previous
        }
    }

    /** T190: Watchlist auf einen frueheren Zustand zuruecksetzen (Undo) */
    suspend fun restore(previous: Watchlist) {
        mutex.withLock {
        watchlist = previous
        save()
        emitFlows()
        }
    }

    /** Art entfernen */
    suspend fun removeSpecies(scientificName: String) {
        mutex.withLock {
        val current = watchlist.species.toMutableList()
        current.removeAll { it.scientificName == scientificName }
        watchlist = watchlist.copy(
            species = current,
            updatedAt = java.time.ZonedDateTime.now(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        )
        save()
        emitFlows()
        }
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
        if (file.exists() && file.canRead()) return file
        // T124: ab Android 11 sieht die App fremde Dateien in Downloads/ nicht (Scoped Storage).
        // Fallback: app-eigener Ordner (Android/data/<pkg>/files/watchlist.json) oder SAF-Import.
        val appFile = context.getExternalFilesDir(null)?.let { File(it, EXTERNAL_FILE) }
        return if (appFile != null && appFile.exists() && appFile.canRead()) appFile else null
    }
}

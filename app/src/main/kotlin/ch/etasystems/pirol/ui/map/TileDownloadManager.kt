package ch.etasystems.pirol.ui.map

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.BoundingBox
import org.osmdroid.views.MapView
import java.io.File
import java.util.UUID

/**
 * Fortschritts-State fuer einen laufenden Tile-Download.
 */
data class DownloadProgress(
    val totalTiles: Int = 0,
    val downloadedTiles: Int = 0,
    val currentZoom: Int = 0,
    val isDownloading: Boolean = false,
    val error: String? = null
)

/**
 * Persistierbare Metadaten eines abgeschlossenen Downloads.
 */
@Serializable
data class TileDownloadRecord(
    val id: String,
    val source: String,
    val boundingBox: BoundingBoxRecord,
    val zoomMin: Int,
    val zoomMax: Int,
    val tileCount: Int,
    val downloadedAt: String,
    val label: String = ""
)

@Serializable
data class BoundingBoxRecord(
    val north: Double,
    val south: Double,
    val east: Double,
    val west: Double
)

/**
 * Verwaltet Offline-Tile-Downloads via osmdroid CacheManager.
 * Fortschritt wird als StateFlow exponiert fuer die UI.
 */
class TileDownloadManager(private val context: Context) {

    private val _progress = MutableStateFlow(DownloadProgress())
    val progress: StateFlow<DownloadProgress> = _progress.asStateFlow()

    private var cacheManager: CacheManager? = null

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /**
     * Schaetzt die Tile-Anzahl fuer eine Bounding Box und Zoom-Bereich.
     * Braucht eine MapView-Instanz fuer den CacheManager.
     */
    fun estimateTileCount(mapView: MapView, boundingBox: BoundingBox, zoomMin: Int, zoomMax: Int): Int {
        val cm = CacheManager(mapView)
        return cm.possibleTilesInArea(boundingBox, zoomMin, zoomMax)
    }

    /**
     * Startet den asynchronen Tile-Download.
     * Muss auf dem Main-Thread aufgerufen werden (CacheManager braucht MapView).
     */
    fun startDownload(
        mapView: MapView,
        tileSource: OnlineTileSourceBase,
        boundingBox: BoundingBox,
        zoomMin: Int,
        zoomMax: Int,
        sourceId: String,
        onComplete: () -> Unit
    ) {
        val totalEstimate = estimateTileCount(mapView, boundingBox, zoomMin, zoomMax)

        _progress.update {
            DownloadProgress(
                totalTiles = totalEstimate,
                downloadedTiles = 0,
                currentZoom = zoomMin,
                isDownloading = true,
                error = null
            )
        }

        cacheManager = CacheManager(mapView)

        cacheManager?.downloadAreaAsync(
            context,
            boundingBox,
            zoomMin,
            zoomMax,
            object : CacheManager.CacheManagerCallback {

                override fun onTaskComplete() {
                    _progress.update {
                        it.copy(
                            isDownloading = false,
                            downloadedTiles = it.totalTiles
                        )
                    }
                    // Metadaten speichern
                    saveDownloadRecord(
                        TileDownloadRecord(
                            id = UUID.randomUUID().toString(),
                            source = sourceId,
                            boundingBox = BoundingBoxRecord(
                                north = boundingBox.latNorth,
                                south = boundingBox.latSouth,
                                east = boundingBox.lonEast,
                                west = boundingBox.lonWest
                            ),
                            zoomMin = zoomMin,
                            zoomMax = zoomMax,
                            tileCount = totalEstimate,
                            downloadedAt = java.time.Instant.now().toString()
                        )
                    )
                    onComplete()
                }

                override fun onTaskFailed(errors: Int) {
                    _progress.update {
                        it.copy(
                            isDownloading = false,
                            error = "Download fehlgeschlagen ($errors Fehler)"
                        )
                    }
                }

                override fun updateProgress(
                    progress: Int,
                    currentZoomLevel: Int,
                    zoomMin: Int,
                    zoomMax: Int
                ) {
                    _progress.update {
                        it.copy(
                            downloadedTiles = progress,
                            currentZoom = currentZoomLevel
                        )
                    }
                }

                override fun downloadStarted() {
                    // Bereits in startDownload() gesetzt
                }

                override fun setPossibleTilesInArea(total: Int) {
                    _progress.update { it.copy(totalTiles = total) }
                }
            }
        )
    }

    /** Bricht den laufenden Download ab. Bereits geladene Tiles bleiben erhalten. */
    fun cancelDownload() {
        cacheManager?.cancelAllJobs()
        _progress.update {
            it.copy(isDownloading = false, error = null)
        }
    }

    /** Setzt den Fortschritts-State zurueck (z.B. nach Fehler-Quittierung). */
    fun resetProgress() {
        _progress.update { DownloadProgress() }
    }

    /** Alle gespeicherten Download-Records laden */
    fun loadDownloadRecords(): List<TileDownloadRecord> {
        return try {
            val cacheDir = Configuration.getInstance().osmdroidTileCache
            val file = File(cacheDir, "downloads.json")
            if (file.exists()) {
                json.decodeFromString<List<TileDownloadRecord>>(file.readText())
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            android.util.Log.e("TileDownloadManager", "Fehler beim Laden der Download-Records", e)
            emptyList()
        }
    }

    /** Einen Download-Record loeschen (nur Metadaten — Tiles bleiben im Cache) */
    fun deleteDownloadRecord(id: String) {
        try {
            val cacheDir = Configuration.getInstance().osmdroidTileCache
            val file = File(cacheDir, "downloads.json")
            if (!file.exists()) return
            val existing = json.decodeFromString<List<TileDownloadRecord>>(file.readText())
            val updated = existing.filter { it.id != id }
            file.writeText(json.encodeToString(updated))
        } catch (e: Exception) {
            android.util.Log.e("TileDownloadManager", "Fehler beim Loeschen des Download-Records", e)
        }
    }

    /** Gesamtgroesse des Tile-Cache-Verzeichnisses in Bytes */
    fun getCacheSizeBytes(): Long {
        val cacheDir = Configuration.getInstance().osmdroidTileCache
        return cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /** Gesamten Tile-Cache loeschen (alle Tiles + downloads.json) */
    fun clearCache() {
        try {
            val cacheDir = Configuration.getInstance().osmdroidTileCache
            cacheDir.deleteRecursively()
            cacheDir.mkdirs()
        } catch (e: Exception) {
            android.util.Log.e("TileDownloadManager", "Fehler beim Loeschen des Caches", e)
        }
    }

    /**
     * Speichert Download-Metadaten in downloads.json im Tile-Cache-Verzeichnis.
     */
    private fun saveDownloadRecord(record: TileDownloadRecord) {
        try {
            val cacheDir = Configuration.getInstance().osmdroidTileCache
            val file = File(cacheDir, "downloads.json")
            val existing = if (file.exists()) {
                json.decodeFromString<List<TileDownloadRecord>>(file.readText())
            } else {
                emptyList()
            }
            val updated = existing + record
            file.writeText(json.encodeToString(updated))
        } catch (e: Exception) {
            android.util.Log.e("TileDownloadManager", "Fehler beim Speichern der Download-Metadaten", e)
        }
    }
}

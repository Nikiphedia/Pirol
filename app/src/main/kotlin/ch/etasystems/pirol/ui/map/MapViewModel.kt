package ch.etasystems.pirol.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.etasystems.pirol.data.repository.SessionManager
import ch.etasystems.pirol.ml.VerificationStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.BoundingBox
import org.osmdroid.views.MapView

class MapViewModel(
    private val sessionManager: SessionManager,
    private val tileDownloadManager: TileDownloadManager
) : ViewModel() {

    data class MapMarker(
        val latitude: Double,
        val longitude: Double,
        val species: String,
        val confidence: Float,
        val timestampMs: Long,
        val sessionId: String,
        val verificationStatus: VerificationStatus
    )

    data class MapUiState(
        val markers: List<MapMarker> = emptyList(),
        val centerLat: Double = 47.38,   // Default: Zuerich
        val centerLon: Double = 8.54,
        val isLoading: Boolean = false,
        val showDownloadSheet: Boolean = false,
        val estimatedTiles: Int = 0,
        val downloadFinishedMessage: String? = null,
        val showManagementSheet: Boolean = false,
        val downloadRecords: List<TileDownloadRecord> = emptyList(),
        val cacheSizeMb: Double = 0.0
    )

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    /** Fortschritts-State vom TileDownloadManager */
    val downloadProgress: StateFlow<DownloadProgress> = tileDownloadManager.progress

    init {
        loadAllDetections()
    }

    /** Alle Detektionen aus allen Sessions laden */
    fun loadAllDetections() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoading = true) }
            val allMarkers = mutableListOf<MapMarker>()
            val sessions = sessionManager.listSessions()

            for (dir in sessions) {
                val meta = sessionManager.loadMetadata(dir) ?: continue
                val detections = sessionManager.loadDetectionsWithVerifications(dir)
                for (det in detections) {
                    if (det.latitude != null && det.longitude != null) {
                        allMarkers.add(
                            MapMarker(
                                latitude = det.latitude,
                                longitude = det.longitude,
                                species = det.commonName,
                                confidence = det.confidence,
                                timestampMs = det.timestampMs,
                                sessionId = meta.sessionId,
                                verificationStatus = det.verificationStatus
                            )
                        )
                    }
                }
            }

            // Kartenmitte auf neueste Detektion oder Default
            val center = allMarkers.lastOrNull()
            _uiState.update {
                it.copy(
                    markers = allMarkers,
                    centerLat = center?.latitude ?: 47.38,
                    centerLon = center?.longitude ?: 8.54,
                    isLoading = false
                )
            }
        }
    }

    // --- Download-Sheet ---

    fun showDownloadSheet() {
        _uiState.update { it.copy(showDownloadSheet = true) }
    }

    fun hideDownloadSheet() {
        _uiState.update { it.copy(showDownloadSheet = false) }
        tileDownloadManager.resetProgress()
    }

    fun dismissFinishedMessage() {
        _uiState.update { it.copy(downloadFinishedMessage = null) }
    }

    /** Tile-Anzahl fuer aktuelle Bounding Box schaetzen */
    fun estimateTiles(mapView: MapView, boundingBox: BoundingBox, zoomMin: Int, zoomMax: Int) {
        val count = tileDownloadManager.estimateTileCount(mapView, boundingBox, zoomMin, zoomMax)
        _uiState.update { it.copy(estimatedTiles = count) }
    }

    /** Download starten */
    fun startDownload(
        mapView: MapView,
        tileSource: OnlineTileSourceBase,
        boundingBox: BoundingBox,
        zoomMin: Int,
        zoomMax: Int,
        sourceId: String
    ) {
        tileDownloadManager.startDownload(
            mapView = mapView,
            tileSource = tileSource,
            boundingBox = boundingBox,
            zoomMin = zoomMin,
            zoomMax = zoomMax,
            sourceId = sourceId,
            onComplete = {
                _uiState.update {
                    it.copy(
                        showDownloadSheet = false,
                        downloadFinishedMessage = "Download abgeschlossen"
                    )
                }
            }
        )
    }

    /** Download abbrechen */
    fun cancelDownload() {
        tileDownloadManager.cancelDownload()
    }

    // --- Management-Sheet ---

    /** Records laden und Management-Sheet oeffnen */
    fun showManagementSheet() {
        viewModelScope.launch(Dispatchers.IO) {
            val records = tileDownloadManager.loadDownloadRecords()
            val sizeBytes = tileDownloadManager.getCacheSizeBytes()
            _uiState.update {
                it.copy(
                    showManagementSheet = true,
                    downloadRecords = records,
                    cacheSizeMb = sizeBytes / 1_048_576.0
                )
            }
        }
    }

    fun hideManagementSheet() {
        _uiState.update { it.copy(showManagementSheet = false) }
    }

    /** Einzelnen Download-Record entfernen (nur Metadaten) */
    fun deleteRecord(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            tileDownloadManager.deleteDownloadRecord(id)
            val records = tileDownloadManager.loadDownloadRecords()
            _uiState.update { it.copy(downloadRecords = records) }
        }
    }

    /** Gesamten Tile-Cache loeschen */
    fun clearAllTiles() {
        viewModelScope.launch(Dispatchers.IO) {
            tileDownloadManager.clearCache()
            _uiState.update {
                it.copy(
                    showManagementSheet = false,
                    downloadRecords = emptyList(),
                    cacheSizeMb = 0.0
                )
            }
        }
    }
}

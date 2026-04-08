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

class MapViewModel(
    private val sessionManager: SessionManager
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
        val isLoading: Boolean = false
    )

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

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
}

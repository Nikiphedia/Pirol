package ch.etasystems.pirol.ui.map

import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.etasystems.pirol.data.repository.SessionManager
import ch.etasystems.pirol.location.LocationData
import ch.etasystems.pirol.location.LocationProvider
import ch.etasystems.pirol.ml.DetectionListState
import ch.etasystems.pirol.ml.DetectionResult
import ch.etasystems.pirol.ml.VerificationStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class FilterMode { ALL, LIVE_ONLY, RECENT_3 }

class MapViewModel(
    private val sessionManager: SessionManager,
    private val detectionListState: DetectionListState,
    private val locationProvider: LocationProvider
) : ViewModel() {

    val currentLocation: StateFlow<LocationData?> = locationProvider.location

    data class MapMarker(
        val detectionId: String,
        val sessionId: String,
        val latitude: Double,
        val longitude: Double,
        val species: String,
        val confidence: Float,
        val timestampMs: Long,
        val verificationStatus: VerificationStatus,
        val isLive: Boolean = false,
        val count: Int = 1,
        val atlasCode: String? = null
    )

    data class MarkerCluster(
        val centerLat: Double,
        val centerLon: Double,
        val items: List<MapMarker>
    )

    data class MapUiState(
        val markers: List<MapMarker> = emptyList(),
        val liveMarkers: List<MapMarker> = emptyList(),
        val clusters: List<MarkerCluster> = emptyList(),
        val selectedCluster: MarkerCluster? = null,
        val centerLat: Double = 47.38,
        val centerLon: Double = 8.54,
        val isLoading: Boolean = false,
        val filterMode: FilterMode = FilterMode.ALL,
        val longPressTarget: MapMarker? = null,
        val positionMoveTarget: MapMarker? = null,
        val editTarget: MapMarker? = null,
        val editDetection: DetectionResult? = null,
        val lockedSpecies: Set<String> = emptySet()
    )

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    init {
        // Live-Arm: snapshotFlow auf version reagiert auf jede addDetections()-Aenderung
        viewModelScope.launch {
            snapshotFlow { detectionListState.version.value }.collect { _ ->
                val live = detectionListState.getDetections().mapNotNull { det ->
                    if (det.latitude != null && det.longitude != null) {
                        MapMarker(
                            detectionId = det.id,
                            sessionId = "",
                            latitude = det.latitude,
                            longitude = det.longitude,
                            species = det.commonName,
                            confidence = det.confidence,
                            timestampMs = det.timestampMs,
                            verificationStatus = det.verificationStatus,
                            isLive = true
                        )
                    } else null
                }
                _uiState.update { it.copy(liveMarkers = live) }
            }
        }
        // Lock-Arm: gesperrte Arten beobachten
        viewModelScope.launch {
            detectionListState.lockedSpecies.collect { locked ->
                _uiState.update { it.copy(lockedSpecies = locked) }
            }
        }
        loadHistoricalDetections(FilterMode.ALL)
    }

    /** Filter umschalten — LIVE_ONLY zeigt nur liveMarkers, sonst historische laden */
    fun setFilter(mode: FilterMode) {
        _uiState.update { it.copy(filterMode = mode) }
        if (mode == FilterMode.LIVE_ONLY) {
            _uiState.update { it.copy(markers = emptyList(), clusters = emptyList(), isLoading = false) }
        } else {
            loadHistoricalDetections(mode)
        }
    }

    /** Historische Detektionen aus Sessions laden (RECENT_3: nur die letzten 3 Sessions) */
    private fun loadHistoricalDetections(mode: FilterMode) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoading = true) }
            val allMarkers = mutableListOf<MapMarker>()
            val sessions = sessionManager.listSessions()
            val filtered = if (mode == FilterMode.RECENT_3) sessions.take(3) else sessions

            for (dir in filtered) {
                val meta = sessionManager.loadMetadata(dir) ?: continue
                val detections = sessionManager.loadDetectionsWithVerifications(dir)
                for (det in detections) {
                    if (det.latitude != null && det.longitude != null) {
                        allMarkers.add(
                            MapMarker(
                                detectionId = det.id,
                                sessionId = meta.sessionId,
                                latitude = det.latitude,
                                longitude = det.longitude,
                                species = det.commonName,
                                confidence = det.confidence,
                                timestampMs = det.timestampMs,
                                verificationStatus = det.verificationStatus,
                                isLive = false,
                                count = det.count,
                                atlasCode = det.atlasCode
                            )
                        )
                    }
                }
            }

            val center = allMarkers.lastOrNull()
            _uiState.update {
                it.copy(
                    markers = allMarkers,
                    clusters = computeClusters(allMarkers),
                    centerLat = center?.latitude ?: 47.38,
                    centerLon = center?.longitude ?: 8.54,
                    isLoading = false
                )
            }
        }
    }

    private fun computeClusters(markers: List<MapMarker>): List<MarkerCluster> {
        val radiusDeg = 0.003
        val processed = BooleanArray(markers.size)
        val result = mutableListOf<MarkerCluster>()
        for (i in markers.indices) {
            if (processed[i]) continue
            val group = mutableListOf(markers[i])
            processed[i] = true
            for (j in (i + 1) until markers.size) {
                if (processed[j]) continue
                val dLat = markers[j].latitude - markers[i].latitude
                val dLon = markers[j].longitude - markers[i].longitude
                if (dLat * dLat + dLon * dLon < radiusDeg * radiusDeg) {
                    group.add(markers[j])
                    processed[j] = true
                }
            }
            val centerLat = group.sumOf { it.latitude } / group.size
            val centerLon = group.sumOf { it.longitude } / group.size
            result.add(MarkerCluster(centerLat, centerLon, group))
        }
        return result
    }

    fun selectCluster(cluster: MarkerCluster) {
        _uiState.update { it.copy(selectedCluster = cluster) }
    }

    fun dismissCluster() {
        _uiState.update { it.copy(selectedCluster = null) }
    }

    /** Long-Press auf historischen Marker → Auswahl-Dialog oeffnen */
    fun onMarkerLongPress(marker: MapMarker) {
        if (marker.isLive) return
        _uiState.update { it.copy(longPressTarget = marker) }
    }

    fun dismissLongPressMenu() {
        _uiState.update { it.copy(longPressTarget = null) }
    }

    /** Position-Verschieben-Dialog oeffnen */
    fun selectMarkerForMove(marker: MapMarker) {
        if (marker.isLive) return  // Live-Marker haben keine sessionId → kein Position-Move
        _uiState.update { it.copy(positionMoveTarget = marker) }
    }

    /** Neue Position bestaetigen → in verifications.jsonl schreiben + Marker in-place aktualisieren */
    fun confirmPositionMove(newLat: Double, newLon: Double) {
        val target = _uiState.value.positionMoveTarget ?: return
        viewModelScope.launch(Dispatchers.IO) {
            sessionManager.recordPositionMoved(
                sessionId = target.sessionId,
                detectionId = target.detectionId,
                newLat = newLat,
                newLon = newLon
            )
            _uiState.update { state ->
                val updated = state.markers.map { m ->
                    if (m.detectionId == target.detectionId)
                        m.copy(latitude = newLat, longitude = newLon)
                    else m
                }
                state.copy(markers = updated, clusters = computeClusters(updated), positionMoveTarget = null)
            }
        }
    }

    /** Dialog schliessen ohne Aenderung */
    fun dismissPositionMove() {
        _uiState.update { it.copy(positionMoveTarget = null) }
    }

    /** Long-Press auf historischen Marker → volle DetectionResult laden, Edit-Dialog oeffnen */
    fun selectMarkerForEdit(marker: MapMarker) {
        if (marker.isLive) return
        viewModelScope.launch(Dispatchers.IO) {
            val dir = sessionManager.listSessions().firstOrNull {
                sessionManager.loadMetadata(it)?.sessionId == marker.sessionId
            } ?: return@launch
            val det = sessionManager.loadDetectionsWithVerifications(dir)
                .firstOrNull { it.id == marker.detectionId } ?: return@launch
            _uiState.update { it.copy(editTarget = marker, editDetection = det) }
        }
    }

    /** Speichern: Detection aktualisieren + Marker in-place updaten */
    fun saveDetectionEdit(count: Int, atlasCode: String?, note: String?) {
        val marker = _uiState.value.editTarget ?: return
        val det = _uiState.value.editDetection ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val updated = det.copy(count = count, atlasCode = atlasCode, note = note)
            sessionManager.replaceDetection(updated)
            _uiState.update { state ->
                val updatedMarkers = state.markers.map { m ->
                    if (m.detectionId == marker.detectionId)
                        m.copy(count = count, atlasCode = atlasCode)
                    else m
                }
                state.copy(
                    markers = updatedMarkers,
                    clusters = computeClusters(updatedMarkers),
                    editTarget = null,
                    editDetection = null
                )
            }
        }
    }

    fun dismissEdit() {
        _uiState.update { it.copy(editTarget = null, editDetection = null) }
    }
}

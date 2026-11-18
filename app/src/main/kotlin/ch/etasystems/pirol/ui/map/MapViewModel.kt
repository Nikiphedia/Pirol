package ch.etasystems.pirol.ui.map

import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.etasystems.pirol.data.repository.SessionManager
import ch.etasystems.pirol.location.LocationData
import ch.etasystems.pirol.location.LocationProvider
import ch.etasystems.pirol.ml.DetectionListState
import ch.etasystems.pirol.ml.DetectionResult
import ch.etasystems.pirol.ml.VerificationEvent
import ch.etasystems.pirol.ml.VerificationStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ch.etasystems.pirol.R
import ch.etasystems.pirol.ui.AppStrings

enum class FilterMode { ALL, LIVE_ONLY, RECENT_3 }

class MapViewModel(
    private val sessionManager: SessionManager,
    private val detectionListState: DetectionListState,
    private val locationProvider: LocationProvider
) : ViewModel() {

    val currentLocation: StateFlow<LocationData?> = locationProvider.location

    /** T112: letzte bekannte Position holen (startet kein Tracking), damit die Karte auf den Standort zoomen kann. */
    fun requestLastKnownLocation() = locationProvider.publishLastKnown()

    data class MapMarker(
        val detectionId: String,
        val sessionId: String,
        val latitude: Double,
        val longitude: Double,
        val species: String,
        val scientificName: String = "",  // T111: fuer SpeciesNameResolver im Cluster-Dialog
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

    enum class LinkType { SIMULTANEOUS, PROBABLE, SAME_INDIVIDUAL }

    data class SimultaneousLink(
        val lat1: Double, val lon1: Double,
        val lat2: Double, val lon2: Double,
        val type: LinkType,
        val detectionId1: String,
        val detectionId2: String
    )

    data class MapUiState(
        val markers: List<MapMarker> = emptyList(),
        val liveMarkers: List<MapMarker> = emptyList(),
        val selectedCluster: MarkerCluster? = null,
        val centerLat: Double = 47.38,
        val centerLon: Double = 8.54,
        val isLoading: Boolean = false,
        val filterMode: FilterMode = FilterMode.ALL,
        val longPressTarget: MapMarker? = null,
        val positionMoveTarget: MapMarker? = null,
        val editTarget: MapMarker? = null,
        val editDetection: DetectionResult? = null,
        val lockedSpecies: Set<String> = emptySet(),
        val focusMode: Boolean = false,
        val focusSpecies: String? = null,
        val linkAnchor: MapMarker? = null,
        val linkTarget: MapMarker? = null,
        val simultaneousLinks: List<SimultaneousLink> = emptyList()
    )

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    init {
        // Live-Arm: snapshotFlow auf version reagiert auf jede addDetections()-Aenderung
        viewModelScope.launch {
            snapshotFlow { detectionListState.version.value }.collect { _ ->
                val live = detectionListState.getDetections().mapNotNull { det ->
                    if (det.latitude != null && det.longitude != null && isVisibleOnMap(det.verificationStatus)) {
                        MapMarker(
                            detectionId = det.id,
                            sessionId = "",
                            latitude = det.latitude,
                            longitude = det.longitude,
                            species = det.commonName,
                            scientificName = det.scientificName,
                            confidence = det.confidence,
                            timestampMs = det.timestampMs,
                            verificationStatus = det.verificationStatus,
                            isLive = true,
                            count = det.count,
                            atlasCode = det.atlasCode
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
            _uiState.update { it.copy(markers = emptyList(), isLoading = false) }
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
                    if (det.latitude != null && det.longitude != null && isVisibleOnMap(det.verificationStatus)) {
                        allMarkers.add(
                            MapMarker(
                                detectionId = det.id,
                                sessionId = meta.sessionId,
                                latitude = det.latitude,
                                longitude = det.longitude,
                                species = det.commonName,
                                scientificName = det.scientificName,
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
                    centerLat = center?.latitude ?: 47.38,
                    centerLon = center?.longitude ?: 8.54,
                    isLoading = false
                )
            }
        }
    }

    fun selectCluster(cluster: MarkerCluster) {
        _uiState.update { it.copy(selectedCluster = cluster) }
    }

    fun dismissCluster() {
        _uiState.update { it.copy(selectedCluster = null) }
    }

    /** Long-Press auf historischen Marker → Auswahl-Dialog oeffnen */
    fun onMarkerLongPress(marker: MapMarker) {
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
                state.copy(markers = updated, positionMoveTarget = null)
            }
        }
    }

    /** Dialog schliessen ohne Aenderung */
    fun dismissPositionMove() {
        _uiState.update { it.copy(positionMoveTarget = null) }
    }

    /** Marker bearbeiten: historisch aus Session-Datei, live aus DetectionListState (T111) */
    fun selectMarkerForEdit(marker: MapMarker) {
        if (marker.isLive) {
            val det = detectionListState.getDetections().firstOrNull { it.id == marker.detectionId } ?: return
            _uiState.update { it.copy(editTarget = marker, editDetection = det) }
            return
        }
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
        val updated = det.copy(count = count, atlasCode = atlasCode, note = note)
        fun patch(m: MapMarker) =
            if (m.detectionId == marker.detectionId) m.copy(count = count, atlasCode = atlasCode) else m
        if (marker.isLive) {
            // Live: Liste aktualisieren (triggert liveMarkers-Neuaufbau), Datei nur bei laufender Session
            detectionListState.updateDetection(updated)
            _uiState.update { state ->
                state.copy(
                    selectedCluster = state.selectedCluster?.let { c -> c.copy(items = c.items.map(::patch)) },
                    editTarget = null,
                    editDetection = null
                )
            }
            if (!sessionManager.isActive) return
            viewModelScope.launch(Dispatchers.IO) {
                sessionManager.replaceDetection(updated)
                val now = System.currentTimeMillis()
                if (det.count != count) sessionManager.appendVerification(
                    VerificationEvent(det.id, det.verificationStatus, verifiedAtMs = now,
                        actionType = VerificationEvent.ACTION_COUNT_CHANGED))
                if (det.atlasCode != atlasCode) sessionManager.appendVerification(
                    VerificationEvent(det.id, det.verificationStatus, verifiedAtMs = now,
                        actionType = VerificationEvent.ACTION_ATLAS_SET))
                if (det.note != note) sessionManager.appendVerification(
                    VerificationEvent(det.id, det.verificationStatus, verifiedAtMs = now,
                        actionType = VerificationEvent.ACTION_NOTE_ADDED))
            }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            // Historisch: in die Datei der Marker-Session schreiben (replaceDetection trifft nur die aktive Session)
            val dir = sessionManager.listSessions().firstOrNull {
                sessionManager.loadMetadata(it)?.sessionId == marker.sessionId
            }
            if (dir != null) sessionManager.replaceDetectionIn(dir, updated)
            else sessionManager.replaceDetection(updated)
            _uiState.update { state ->
                state.copy(
                    markers = state.markers.map(::patch),
                    selectedCluster = state.selectedCluster?.let { c -> c.copy(items = c.items.map(::patch)) },
                    editTarget = null,
                    editDetection = null
                )
            }
        }
    }

    // ── T113: Verifikation auf der Karte ─────────────────────────────

    data class MapSnackbar(val message: String, val actionLabel: String? = null, val onAction: (() -> Unit)? = null)

    private val snackbarChannel = Channel<MapSnackbar>(Channel.BUFFERED)
    val snackbarEvents = snackbarChannel.receiveAsFlow()

    // Serialisiert Verifikations-IO (Schnell-Taps)
    private val verificationMutex = Mutex()
    private val verifyJson = Json { encodeDefaults = true }

    private suspend fun findSessionDir(sessionId: String) = sessionManager.listSessions().firstOrNull {
        sessionManager.loadMetadata(it)?.sessionId == sessionId
    }

    /** Aktualisiert Marker in Hauptliste + offenem Cluster; abgelehnte verschwinden (T108). */
    private fun applyMarkerStatus(marker: MapMarker, status: VerificationStatus) {
        val visible = MapVerification.visibleAfter(status)
        fun patch(list: List<MapMarker>) =
            if (visible) list.map { if (it.detectionId == marker.detectionId) MapVerification.withStatus(it, status) else it }
            else list.filter { it.detectionId != marker.detectionId }
        _uiState.update { state ->
            state.copy(
                markers = patch(state.markers),
                selectedCluster = state.selectedCluster?.let { c ->
                    val items = patch(c.items)
                    if (items.isEmpty()) null else c.copy(items = items)
                },
                longPressTarget = null
            )
        }
    }

    /** Bestaetigen / Unsicher / Ablehnen direkt auf der Karte, mit Snackbar-Undo. */
    fun verifyMarker(marker: MapMarker, status: VerificationStatus) {
        viewModelScope.launch {
            var prevCorrected: String? = null
            if (marker.isLive) {
                val det = detectionListState.getDetections().firstOrNull { it.id == marker.detectionId } ?: return@launch
                prevCorrected = det.correctedSpecies
                // Gleiche Logik wie LiveViewModel.verifyDetection: Liste (Live-Tab zieht nach) + Datei bei aktiver Session
                detectionListState.updateVerification(marker.detectionId, status, null)
                if (sessionManager.isActive) withContext(Dispatchers.IO) {
                    verificationMutex.withLock {
                        runCatching {
                            sessionManager.appendVerification(
                                MapVerification.eventFor(marker.detectionId, status, System.currentTimeMillis()))
                        }.onFailure {
                            detectionListState.updateVerification(marker.detectionId, marker.verificationStatus, prevCorrected)
                            snackbarChannel.trySend(MapSnackbar(AppStrings.get(R.string.common_save_failed)))
                        }
                    }
                }
            } else {
                val ok = withContext(Dispatchers.IO) {
                    verificationMutex.withLock {
                        val dir = findSessionDir(marker.sessionId) ?: return@withLock false
                        prevCorrected = sessionManager.loadDetectionsWithVerifications(dir)
                            .firstOrNull { it.id == marker.detectionId }?.correctedSpecies
                        runCatching {
                            sessionManager.appendVerificationLine(
                                dir,
                                verifyJson.encodeToString(
                                    MapVerification.eventFor(marker.detectionId, status, System.currentTimeMillis())
                                ) + "\n"
                            )
                        }.isSuccess
                    }
                }
                if (!ok) {
                    snackbarChannel.trySend(MapSnackbar(AppStrings.get(R.string.common_save_failed)))
                    return@launch
                }
            }
            applyMarkerStatus(marker, status)
            val record = MapVerification.UndoRecord(marker, marker.verificationStatus, prevCorrected)
            snackbarChannel.trySend(
                MapSnackbar(MapVerification.message(status, marker.species), AppStrings.get(R.string.common_undo)) {
                    undoVerification(record)
                }
            )
        }
    }

    private fun undoVerification(record: MapVerification.UndoRecord) {
        val m = record.marker
        viewModelScope.launch {
            if (m.isLive) {
                // Live-Marker bauen sich ueber die Liste neu auf
                detectionListState.updateVerification(m.detectionId, record.previousStatus, record.previousCorrected)
                if (sessionManager.isActive) withContext(Dispatchers.IO) {
                    verificationMutex.withLock { runCatching { sessionManager.removeLastVerification(m.detectionId) } }
                }
            } else {
                withContext(Dispatchers.IO) {
                    verificationMutex.withLock {
                        val dir = findSessionDir(m.sessionId) ?: return@withLock
                        runCatching {
                            sessionManager.appendVerificationLine(
                                dir,
                                verifyJson.encodeToString(MapVerification.undoEvent(record, System.currentTimeMillis())) + "\n"
                            )
                        }
                    }
                }
                // Marker wieder einblenden bzw. Status zuruecksetzen
                _uiState.update { state ->
                    val restored = MapVerification.withStatus(m, record.previousStatus)
                    val exists = state.markers.any { it.detectionId == m.detectionId }
                    state.copy(
                        markers = when {
                            exists -> state.markers.map { if (it.detectionId == m.detectionId) restored else it }
                            MapVerification.visibleAfter(record.previousStatus) -> state.markers + restored
                            else -> state.markers
                        }
                    )
                }
            }
        }
    }

    fun dismissEdit() {
        _uiState.update { it.copy(editTarget = null, editDetection = null) }
    }

    fun enterFocusMode(anchor: MapMarker) {
        if (anchor.isLive) return
        _uiState.update {
            it.copy(
                focusMode = true,
                focusSpecies = anchor.species,
                linkAnchor = anchor,
                longPressTarget = null
            )
        }
    }

    fun selectLinkTarget(marker: MapMarker) {
        if (!_uiState.value.focusMode) return
        if (marker.detectionId == _uiState.value.linkAnchor?.detectionId) return
        _uiState.update { it.copy(linkTarget = marker) }
    }

    fun confirmLink(type: LinkType) {
        val anchor = _uiState.value.linkAnchor ?: return
        val target = _uiState.value.linkTarget ?: return
        val actionType = when (type) {
            LinkType.SIMULTANEOUS    -> VerificationEvent.ACTION_SIMULTANEOUS_LINK
            LinkType.PROBABLE        -> VerificationEvent.ACTION_PROBABLE_LINK
            LinkType.SAME_INDIVIDUAL -> VerificationEvent.ACTION_SAME_INDIVIDUAL
        }
        viewModelScope.launch(Dispatchers.IO) {
            sessionManager.recordSimultaneousLink(
                sessionId = anchor.sessionId,
                detectionId1 = anchor.detectionId,
                detectionId2 = target.detectionId,
                actionType = actionType
            )
            val newLink = SimultaneousLink(
                lat1 = anchor.latitude, lon1 = anchor.longitude,
                lat2 = target.latitude, lon2 = target.longitude,
                type = type,
                detectionId1 = anchor.detectionId,
                detectionId2 = target.detectionId
            )
            _uiState.update {
                it.copy(
                    simultaneousLinks = it.simultaneousLinks + newLink,
                    linkTarget = null
                )
            }
        }
    }

    fun exitFocusMode() {
        _uiState.update {
            it.copy(
                focusMode = false,
                focusSpecies = null,
                linkAnchor = null,
                linkTarget = null
            )
        }
    }

    fun dismissLinkTarget() {
        _uiState.update { it.copy(linkTarget = null) }
    }
}

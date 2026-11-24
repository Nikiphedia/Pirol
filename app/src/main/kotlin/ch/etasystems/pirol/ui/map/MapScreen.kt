package ch.etasystems.pirol.ui.map

import ch.etasystems.pirol.ui.help.helpTopic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import org.osmdroid.views.overlay.CopyrightOverlay
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import ch.etasystems.pirol.audio.PlaybackKeys
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import ch.etasystems.pirol.data.repository.AtlasCodeRepository
import ch.etasystems.pirol.data.repository.SpeciesColorRepository
import ch.etasystems.pirol.ui.components.EditDetectionDialog
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.BoundingBox
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.modules.SqlTileWriter
import ch.etasystems.pirol.data.AppPreferences
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import ch.etasystems.pirol.ml.SpeciesNameResolver
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import android.view.GestureDetector
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.ui.input.pointer.pointerInput
import androidx.core.view.GestureDetectorCompat
import ch.etasystems.pirol.R
import androidx.compose.ui.res.stringResource
import ch.etasystems.pirol.ui.AppStrings

// ── Hilfsfunktionen: Kreis-Marker mit Kuerzel ────────────────────────────────

private fun speciesLabel(species: String): String {
    val words = species.trim().split(" ", "-")
    return when {
        words.size >= 2 -> words.take(3).joinToString("") { it.take(1).uppercase() }
        species.length >= 3 -> species.take(3).replaceFirstChar { it.uppercase() }
        else -> species.uppercase()
    }
}

/** T115: Icons gecacht (Main-Thread), damit ein Overlay-Neuaufbau keine Bitmaps neu zeichnet. */
private val markerIconCache = LruCacheMap<MarkerIconKey, Drawable>(300)

private fun createCircleMarkerIcon(
    resources: android.content.res.Resources,
    color: Int,
    label: String = "",
    locked: Boolean = false,
    size: Int = 64,
    atlasCode: String? = null,
    count: Int = 1,
    ringColor: Int? = null
): Drawable = markerIconCache.getOrPut(MarkerIconKey(color, label, locked, size, atlasCode, count, ringColor)) {
    drawCircleMarkerIcon(resources, color, label, locked, size, atlasCode, count, ringColor)
}

private fun drawCircleMarkerIcon(
    resources: android.content.res.Resources,
    color: Int,
    label: String,
    locked: Boolean,
    size: Int,
    atlasCode: String?,   // T111: kleines Etikett unten
    count: Int,           // T111: Badge oben rechts ab 2
    ringColor: Int?       // T111: farbiger Ring (Cluster mit Live-Detektionen)
): Drawable {
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val paintFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - 2f, paintFill)

    val paintBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - 2f, paintBorder)

    if (ringColor != null) {
        val paintRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = ringColor
            style = Paint.Style.STROKE
            strokeWidth = 6f
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 6f, paintRing)
    }

    if (label.isNotEmpty()) {
        val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = android.graphics.Color.WHITE
            textSize = when {
                label.length == 1 -> 26f
                label.length <= 3 -> 18f
                else -> 14f
            }
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val bounds = android.graphics.Rect()
        paintText.getTextBounds(label, 0, label.length, bounds)
        val yOffset = bounds.height() / 2f
        canvas.drawText(label, size / 2f, size / 2f + yOffset, paintText)
    }

    if (!atlasCode.isNullOrBlank()) {
        val paintTag = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = android.graphics.Color.WHITE
            textSize = size * 0.22f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val tw = paintTag.measureText(atlasCode)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = android.graphics.Color.parseColor("#CC263238") }
        val h = size * 0.26f
        val rect = android.graphics.RectF(size / 2f - tw / 2f - 4f, size - h - 1f, size / 2f + tw / 2f + 4f, size - 1f)
        canvas.drawRoundRect(rect, h / 2f, h / 2f, bg)
        canvas.drawText(atlasCode, size / 2f, size - 1f - h * 0.25f, paintTag)
    }

    if (count > 1) {
        val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = android.graphics.Color.WHITE }
        val r = size * 0.18f
        canvas.drawCircle(size - r - 1f, r + 1f, r, badge)
        val paintCount = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = android.graphics.Color.BLACK
            textSize = r * (if (count > 9) 1.1f else 1.4f)
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        canvas.drawText(count.coerceAtMost(99).toString(), size - r - 1f, r + 1f + r * 0.45f, paintCount)
    }

    if (locked) {
        val lockFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = android.graphics.Color.BLACK
            style = Paint.Style.FILL
        }
        canvas.drawCircle(8f, 8f, 6f, lockFill)
        val lockBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = android.graphics.Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        canvas.drawCircle(8f, 8f, 6f, lockBorder)
    }

    return BitmapDrawable(resources, bitmap)
}

/** Namen der swisstopo-Quellen im osmdroid-Cache (Spalte provider) */
private val SWISSTOPO_CACHE_NAMES = listOf(SwisstopoFarbeTileSource.name(), SwisstopoLuftbildTileSource.name())

/**
 * Belegter swisstopo-Kartenspeicher in Bytes (T128: nur swisstopo-Kacheln, OSM zaehlt nicht mit).
 * Summe der Kachelgroessen direkt aus der osmdroid-SQLite-Datei, da die Dateigroesse nach
 * dem Loeschen nicht schrumpft.
 */
private fun tileCacheBytes(): Long {
    val file = java.io.File(
        org.osmdroid.config.Configuration.getInstance().osmdroidTileCache,
        SqlTileWriter.DATABASE_FILENAME + ".sqlite"
    )
    if (!file.exists()) return 0L
    val db = android.database.sqlite.SQLiteDatabase.openDatabase(
        file.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
    )
    return try {
        val marks = SWISSTOPO_CACHE_NAMES.joinToString(",") { "?" }
        db.rawQuery(
            "SELECT COALESCE(SUM(LENGTH(tile)),0) FROM tiles WHERE provider IN ($marks)",
            SWISSTOPO_CACHE_NAMES.toTypedArray()
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
    } finally { db.close() }
}

/** T128: loescht nur swisstopo-Kacheln, OSM-Kacheln bleiben erhalten */
private fun purgeTileCache() {
    val writer = SqlTileWriter()
    try { SWISSTOPO_CACHE_NAMES.forEach { writer.purgeCache(it) } } finally { writer.onDetach() }
}

/** Dialoge rund um Kartenquelle und Offline-Download (T108) */
private sealed interface OfflineDialog {
    data class Confirm(val box: BoundingBox, val zoomMin: Int, val zoomMax: Int, val tiles: Int) : OfflineDialog
    data class Info(val text: String) : OfflineDialog
    data object ConfirmDelete : OfflineDialog
}

// ── T191: Route als Polyline ──────────────────────────────────────────────────

private const val TRACK_COLOR = "#8E24AA"       // historische Route (violett)
private const val TRACK_LIVE_COLOR = "#1976D2"  // laufende Aufnahme (Live-Blau wie Marker)
private const val TRACK_ALPHA = 170
private const val TRACK_WIDTH_PX = 6f

/**
 * Route unter die Marker legen. Spur nur aus Detektions-Fixes (Modus "Nur bei Detektion")
 * wird gestrichelt gezeichnet: die Linie verbindet nur wenige Fixes, nicht den echten Weg.
 */
private fun addTrackOverlay(mapView: MapView, track: ch.etasystems.pirol.location.SessionTrack, isLive: Boolean) {
    if (track.points.size < 2) return
    val line = org.osmdroid.views.overlay.Polyline(mapView)
    line.setPoints(track.points.map { GeoPoint(it.lat, it.lon) })
    line.outlinePaint.apply {
        color = android.graphics.Color.parseColor(if (isLive) TRACK_LIVE_COLOR else TRACK_COLOR)
        alpha = TRACK_ALPHA
        strokeWidth = TRACK_WIDTH_PX * mapView.resources.displayMetrics.density / 2f
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
        isAntiAlias = true
        if (track.sparse) pathEffect = android.graphics.DashPathEffect(floatArrayOf(18f, 14f), 0f)
    }
    // Tap auf die Route nicht abfangen (kein leeres Info-Fenster, Marker bleiben bedienbar)
    line.infoWindow = null
    line.setOnClickListener { _, _, _ -> false }
    mapView.overlays.add(line)
}

// ── Haupt-Composable ──────────────────────────────────────────────────────────

@OptIn(FlowPreview::class, ExperimentalFoundationApi::class)
@Composable
fun MapScreen(viewModel: MapViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val playingKey by viewModel.playingKey.collectAsState()  // T173
    val colorRepo: SpeciesColorRepository = koinInject()
    val atlasCodeRepo: AtlasCodeRepository = koinInject()
    val nameResolver: SpeciesNameResolver = koinInject()
    val watchlistManager: ch.etasystems.pirol.ml.WatchlistManager = koinInject()  // T204
    val watched by watchlistManager.watchedSpeciesFlow.collectAsState()
    // T204: Artfilter auf die Marker anwenden (Routen bleiben unberuehrt)
    val shownMarkers = remember(state.markers, state.hiddenSpecies, state.onlyAlarm, watched) {
        MapSpeciesFilter.filter(state.markers, state.hiddenSpecies, state.onlyAlarm, watched)
    }
    val shownLive = remember(state.liveMarkers, state.hiddenSpecies, state.onlyAlarm, watched) {
        MapSpeciesFilter.filter(state.liveMarkers, state.hiddenSpecies, state.onlyAlarm, watched)
    }

    val mapViewRef = remember { mutableStateOf<MapView?>(null) }
    val startCenter = remember { mutableStateOf<GeoPoint?>(null) }  // T112: Kartenmitte beim Start

    // T108: Kartenquelle + Offline-Download
    val prefs: AppPreferences = koinInject()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sourceMode by remember { mutableStateOf(MapSourceMode.fromName(prefs.mapSourceMode)) }
    val sourceModeRef = rememberUpdatedState(sourceMode)
    var panelOpen by remember { mutableStateOf(false) }  // T119: Seitenleiste
    var offlineDialog by remember { mutableStateOf<OfflineDialog?>(null) }
    var downloadTask by remember { mutableStateOf<CacheManager.CacheManagerTask?>(null) }
    var downloadDone by remember { mutableIntStateOf(0) }
    var downloadTotal by remember { mutableIntStateOf(0) }
    var cacheBytes by remember { mutableStateOf(0L) }
    LaunchedEffect(panelOpen, downloadTask, offlineDialog) {
        cacheBytes = withContext(Dispatchers.IO) { runCatching { tileCacheBytes() }.getOrDefault(0L) }
    }
    val locationState by viewModel.currentLocation.collectAsState()

    // T112: Beim Oeffnen auf den Standort zoomen (swisstopo-Standard). Kein erneuter Sprung,
    // sobald der Nutzer die Karte bewegt hat (Kartenmitte != Startmitte) oder nach 15 s ohne Fix.
    LaunchedEffect(Unit) {
        viewModel.requestLastKnownLocation()
        val loc = withTimeoutOrNull(15_000L) {
            snapshotFlow { locationState }.filterNotNull().first()
        } ?: return@LaunchedEffect
        val mv = mapViewRef.value ?: return@LaunchedEffect
        val start = startCenter.value ?: return@LaunchedEffect
        val untouched = abs(mv.mapCenter.latitude - start.latitude) < 1e-4 &&
            abs(mv.mapCenter.longitude - start.longitude) < 1e-4 &&
            mv.zoomLevelDouble == 14.0
        if (untouched && state.positionMoveTarget == null) {
            mv.controller.setZoom(15.0)
            mv.controller.setCenter(GeoPoint(loc.latitude, loc.longitude))
        }
    }

    // Marker-Referenz fuer GestureDetector (aktuell, thread-safe via MutableState)
    val positionMoveRef = remember { mutableStateOf<MapViewModel.MapMarker?>(null) }
    val longPressClustersRef = remember { mutableStateOf<List<MapViewModel.MarkerCluster>>(emptyList()) }

    // T111: Zoom -> Clustering. Roh-Zoom aus dem MapListener, entprellt (150 ms) fuer die Gruppierung.
    val rawZoom = remember { mutableDoubleStateOf(14.0) }
    var clusterZoom by remember { mutableDoubleStateOf(14.0) }
    LaunchedEffect(Unit) {
        snapshotFlow { rawZoom.doubleValue }.debounce(150L).collect { clusterZoom = it }
    }
    val thresholdPx = 48f * LocalDensity.current.density  // ~1 Icon-Durchmesser (48 dp)
    // T115: Clustering auf Dispatchers.Default (nicht im Main-Thread); veraltete Berechnung wird abgebrochen
    var clusters by remember { mutableStateOf<List<MapViewModel.MarkerCluster>>(emptyList()) }
    LaunchedEffect(shownMarkers, shownLive, state.filterMode, state.focusMode, state.focusKey, clusterZoom, thresholdPx) {
        val input = when {
            state.filterMode == FilterMode.LIVE_ONLY -> shownLive
            // Fokus-Modus: nur historische Marker der Fokus-Art (Live hat keine Session fuer Verknuepfungen)
            state.focusMode -> shownMarkers.filter { MapSpeciesFilter.keyOf(it) == state.focusKey }
            else -> shownMarkers + shownLive
        }
        clusters = withContext(Dispatchers.Default) { buildClusters(input, clusterZoom, thresholdPx.toDouble()) }
    }

    // T115: MapView-Lifecycle (Tile-Threads pausieren, wenn Screen/Tab nicht aktiv)
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> mapViewRef.value?.onResume()
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> mapViewRef.value?.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // Letzter Overlay-Stand: Neuaufbau nur bei echter Aenderung
    val lastOverlayKey = remember { arrayOfNulls<Any>(1) }

    // T122: clipToBounds — die MapView zeichnete nach kurzer Zeit ueber NavigationRail und Statusleiste
    Box(modifier = Modifier.fillMaxSize().clipToBounds()) {

        val inMoveMode = state.positionMoveTarget != null

        // Karte (ganzer Bildschirm)
        val allMarkers = when (state.filterMode) {
            FilterMode.LIVE_ONLY -> shownLive
            else -> shownMarkers + shownLive
        }

        AndroidView(
            factory = { ctx ->
                MapView(ctx).apply {
                    setMultiTouchControls(true)
                    // T245: eingebaute Zoom-Knoepfe aus, eigene (groessere) Compose-Knoepfe
                    zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
                    controller.setZoom(14.0)
                    rawZoom.doubleValue = 14.0
                    controller.setCenter(GeoPoint(state.centerLat, state.centerLon))
                    startCenter.value = GeoPoint(state.centerLat, state.centerLon)
                    setTileSource(tileSourceFor(sourceModeRef.value, 14, state.centerLat, state.centerLon))
                    addMapListener(object : org.osmdroid.events.MapListener {
                        override fun onZoom(event: org.osmdroid.events.ZoomEvent): Boolean {
                            // T106: nur bei Wechsel der Quelle setzen (setTileSource leert den Kachel-Cache)
                            rawZoom.doubleValue = event.zoomLevel
                            val c = mapCenter
                            val source = tileSourceFor(sourceModeRef.value, event.zoomLevel.toInt(), c.latitude, c.longitude)
                            if (tileProvider.tileSource != source) setTileSource(source)
                            return false
                        }
                        override fun onScroll(event: org.osmdroid.events.ScrollEvent): Boolean {
                            // T128: Automatik-Wechsel auch nach Verschieben (CH <-> Ausland)
                            if (sourceModeRef.value == MapSourceMode.AUTO) {
                                val c = mapCenter
                                val source = tileSourceFor(MapSourceMode.AUTO, zoomLevelDouble.toInt(), c.latitude, c.longitude)
                                if (tileProvider.tileSource != source) setTileSource(source)
                            }
                            return false
                        }
                    })
                }.also { mapView ->
                    mapViewRef.value = mapView

                    // GestureDetector direkt auf dem MapView — empfängt Events vor Compose
                    val gestureDetector = GestureDetectorCompat(
                        ctx,
                        object : GestureDetector.SimpleOnGestureListener() {
                            override fun onLongPress(e: MotionEvent) {
                                val clustersNow = longPressClustersRef.value
                                val proj = mapView.projection
                                val nearest = clustersNow.minByOrNull { c ->
                                    val px = proj.toPixels(GeoPoint(c.centerLat, c.centerLon), null)
                                    val dx = (px.x - e.x).toDouble()
                                    val dy = (px.y - e.y).toDouble()
                                    dx * dx + dy * dy
                                } ?: return
                                val nearPx = proj.toPixels(GeoPoint(nearest.centerLat, nearest.centerLon), null)
                                val dist = Math.hypot(
                                    (nearPx.x - e.x).toDouble(),
                                    (nearPx.y - e.y).toDouble()
                                )
                                // T165: Trefferradius dp-basiert (40 dp), unabhaengig von der Bildschirmdichte
                                if (dist < 40.0 * mapView.resources.displayMetrics.density) {
                                    if (nearest.items.size > 1) {
                                        viewModel.selectCluster(nearest)
                                    } else {
                                        val m = nearest.items.first()
                                        viewModel.onMarkerLongPress(m)
                                    }
                                }
                            }
                        }
                    )

                    @Suppress("ClickableViewAccessibility")
                    mapView.setOnTouchListener { _, event ->
                        gestureDetector.onTouchEvent(event)
                        false  // false = MapView erhält Event weiterhin (Panning/Zooming)
                    }
                }
            },
            update = { mapView ->
                // Marker-Referenz aktuell halten (für GestureDetector)
                longPressClustersRef.value = if (state.positionMoveTarget == null) clusters else emptyList()

                // animateTo nur beim Eintritt in Move-Modus (prevMoveTarget war null)
                val moveTarget = state.positionMoveTarget
                val prevMoveTarget = positionMoveRef.value
                positionMoveRef.value = moveTarget
                if (prevMoveTarget == null && moveTarget != null) {
                    mapView.controller.animateTo(GeoPoint(moveTarget.latitude, moveTarget.longitude))
                }

                // T108: Quelle wechseln (nur bei Aenderung, setTileSource leert den Kachel-Cache)
                val centre = mapView.mapCenter
                val wanted = tileSourceFor(sourceMode, mapView.zoomLevelDouble.toInt(), centre.latitude, centre.longitude)
                if (mapView.tileProvider.tileSource != wanted) mapView.setTileSource(wanted)

                // T191: Routen (Live-Session nur einmal)
                val visibleTracks = state.visibleTracks()

                // T115: Overlays nur neu aufbauen, wenn sich die Eingaben wirklich geaendert haben
                val overlayKey = listOf(
                    moveTarget, clusters, state.simultaneousLinks, state.linkAnchor?.detectionId,
                    state.lockedSpecies, state.focusMode, sourceMode, visibleTracks
                )
                if (lastOverlayKey[0] == overlayKey) return@AndroidView
                lastOverlayKey[0] = overlayKey
                val rebuildStart = android.os.SystemClock.elapsedRealtime()

                // Einfaches clear — GestureDetector läuft unabhängig von Overlays
                mapView.overlays.clear()
                // T106: Quellenangabe der aktiven Kachelquelle (swisstopo / OpenStreetMap)
                mapView.overlays.add(CopyrightOverlay(mapView.context))

                if (moveTarget != null) {
                    // Move-Modus: nur Ziel-Marker anzeigen (orange, gross)
                    val moveMarker = Marker(mapView)
                    moveMarker.position = GeoPoint(moveTarget.latitude, moveTarget.longitude)
                    moveMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    moveMarker.icon = createCircleMarkerIcon(
                        mapView.resources,
                        android.graphics.Color.parseColor("#FF8F00"),
                        speciesLabel(moveTarget.species),
                        size = 80
                    )
                    moveMarker.title = moveTarget.species
                    mapView.overlays.add(moveMarker)
                } else {
                    val blueColor = android.graphics.Color.parseColor("#1976D2")
                    val clusterColor = android.graphics.Color.parseColor("#607D8B")

                    // T191: Routen zuunterst (vor Verknuepfungen und Markern)
                    for (track in visibleTracks) addTrackOverlay(mapView, track, isLive = track === state.liveTrack)

                    // Polylinien vor Markern zeichnen
                    for (link in state.simultaneousLinks) {
                        val polyline = org.osmdroid.views.overlay.Polyline(mapView)
                        polyline.addPoint(GeoPoint(link.lat1, link.lon1))
                        polyline.addPoint(GeoPoint(link.lat2, link.lon2))
                        val lineColor = when (link.type) {
                            MapViewModel.LinkType.SIMULTANEOUS    -> android.graphics.Color.parseColor("#4CAF50")
                            MapViewModel.LinkType.PROBABLE        -> android.graphics.Color.parseColor("#F44336")
                            MapViewModel.LinkType.SAME_INDIVIDUAL -> android.graphics.Color.parseColor("#2196F3")
                        }
                        polyline.outlinePaint.apply {
                            color = lineColor
                            strokeWidth = 6f
                            isAntiAlias = true
                        }
                        mapView.overlays.add(polyline)
                    }

                    for (cluster in clusters) {
                        val marker = Marker(mapView)
                        marker.position = GeoPoint(cluster.centerLat, cluster.centerLon)
                        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        if (cluster.items.size == 1) {
                            val m = cluster.items.first()
                            if (m.isLive) {
                                marker.icon = createCircleMarkerIcon(
                                    mapView.resources, blueColor, speciesLabel(m.species),
                                    atlasCode = m.atlasCode, count = m.count
                                )
                                marker.title = AppStrings.get(R.string.map_live_2, m.species, (m.confidence * 100).toInt())
                                // T165: Tap oeffnet das Aktionsmenue (inkl. Position verschieben)
                                marker.setOnMarkerClickListener { _, _ ->
                                    viewModel.onMarkerLongPress(m)
                                    true
                                }
                            } else {
                                val isAnchor = m.detectionId == state.linkAnchor?.detectionId
                                val color = colorRepo.getColor(m.species)
                                val iconSize = if (isAnchor) 80 else 64
                                marker.icon = createCircleMarkerIcon(
                                    mapView.resources, color, speciesLabel(m.species),
                                    locked = state.lockedSpecies.contains(m.species), size = iconSize,
                                    atlasCode = m.atlasCode, count = m.count
                                )
                                marker.title = "${m.species} (${(m.confidence * 100).toInt()}%)"
                                marker.snippet = m.sessionId.take(16)
                                marker.setOnMarkerClickListener { _, _ ->
                                    // Fokus: Linkziel waehlen; sonst (T165) Tap oeffnet das Aktionsmenue
                                    if (state.focusMode) viewModel.selectLinkTarget(m)
                                    else viewModel.onMarkerLongPress(m)
                                    true
                                }
                            }
                        } else {
                            val hasLive = cluster.items.any { it.isLive }
                            marker.icon = createCircleMarkerIcon(
                                mapView.resources, clusterColor, "+${cluster.items.size}",
                                ringColor = if (hasLive) blueColor else null
                            )
                            marker.title = AppStrings.get(R.string.ui_detektionen, cluster.items.size)
                            marker.snippet = null
                            marker.setOnMarkerClickListener { _, _ ->
                                viewModel.selectCluster(cluster)
                                true
                            }
                        }
                        mapView.overlays.add(marker)
                    }
                }

                mapView.invalidate()
                android.util.Log.d(
                    "MapScreen",
                    "T115 overlays rebuilt n=${mapView.overlays.size} ms=${android.os.SystemClock.elapsedRealtime() - rebuildStart}"
                )
            },
            onRelease = { it.onPause() },
            modifier = Modifier.fillMaxSize().clipToBounds()
        )

        // T113: Snackbar mit Undo (Long = 10 s, also >= 5 s)
        val snackbarHostState = remember { SnackbarHostState() }
        LaunchedEffect(viewModel) {
            viewModel.snackbarEvents.collect { ev ->
                snackbarHostState.currentSnackbarData?.dismiss()
                val result = snackbarHostState.showSnackbar(
                    message = ev.message,
                    actionLabel = ev.actionLabel,
                    duration = SnackbarDuration.Long
                )
                if (result == SnackbarResult.ActionPerformed) ev.onAction?.invoke()
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp)
        )

        if (!inMoveMode) {
            // Cluster-Dialog
            val cluster = state.selectedCluster
            if (cluster != null) {
                AlertDialog(
                    onDismissRequest = { viewModel.dismissCluster() },
                    title = { Text(
                        if (state.focusMode) stringResource(R.string.map_cluster_linkziel_waehlen, cluster.items.size)
                        else stringResource(R.string.map_detektionen_tippen_oder_lange_drueck, cluster.items.size)
                    ) },
                    text = {
                        LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                            items(cluster.items) { m ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .combinedClickable(
                                            onClick = {
                                                // T129: im Fokusmodus Cluster-Eintrag als Verknuepfungsziel waehlen
                                                if (state.focusMode) {
                                                    viewModel.dismissCluster()
                                                    viewModel.selectLinkTarget(m)
                                                } else viewModel.selectMarkerForEdit(m)
                                            },
                                            onLongClick = {
                                                if (state.focusMode) {
                                                    viewModel.dismissCluster()
                                                    viewModel.selectLinkTarget(m)
                                                } else viewModel.selectMarkerForEdit(m)
                                            }
                                        )
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(12.dp)
                                            .background(
                                                color = Color(colorRepo.getColor(m.species)),
                                                shape = CircleShape
                                            )
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        nameResolver.language = prefs.effectiveSpeciesLanguage()
                                        val shownName = if (m.scientificName.isNotBlank())
                                            nameResolver.displayName(m.scientificName) else m.species
                                        Text(shownName, style = MaterialTheme.typography.bodyMedium)
                                        if (m.scientificName.isNotBlank()) {
                                            Text(
                                                m.scientificName.replace('_', ' '),
                                                style = MaterialTheme.typography.bodySmall,
                                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        val meta = buildString {
                                            if (m.isLive) append(stringResource(R.string.map_live))
                                            append("${(m.confidence * 100).toInt()}%")
                                            if (m.atlasCode != null) append("  •  ${m.atlasCode}")
                                            if (m.count > 1) append("  •  ${m.count}×")
                                            append("  •  ${m.verificationStatus.name}")
                                        }
                                        Text(
                                            meta,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                // T136: Verifikation auch im Fokusmodus (Auswahl als Linkziel bleibt)
                                MapVerifyButtons(
                                    status = m.verificationStatus,
                                    onVerify = { viewModel.verifyMarker(m, it) },
                                    modifier = Modifier.padding(bottom = 2.dp)
                                )
                                // T165: Einzelpunkt aus dem Cluster verschieben; T173: Ausschnitt abspielen/stoppen
                                if (!state.focusMode) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        MapPlayButton(
                                            playing = playingKey == PlaybackKeys.detection(m.detectionId),
                                            onClick = { viewModel.toggleMarkerPlayback(m) }
                                        )
                                        TextButton(
                                            onClick = { viewModel.selectMarkerForMove(m) },
                                            modifier = Modifier.heightIn(min = 48.dp)
                                        ) { Text(stringResource(R.string.map_position_verschieben)) }
                                    }
                                }
                                HorizontalDivider()
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { viewModel.dismissCluster() }) { Text(stringResource(R.string.common_close)) }
                    }
                )
            }

            // Edit-Detection-Dialog
            val editTarget = state.editTarget
            val editDetection = state.editDetection
            if (editTarget != null && editDetection != null) {
                EditDetectionDialog(
                    detection = editDetection,
                    atlasCodes = atlasCodeRepo.loadCodes(),
                    onDismiss = { viewModel.dismissEdit() },
                    onSave = { count, atlasCode, note, _ ->
                        viewModel.saveDetectionEdit(count, atlasCode, note)
                    }
                )
            }

            // Long-Press-Auswahl-Dialog
            val longPressTarget = state.longPressTarget
            if (longPressTarget != null) {
                AlertDialog(
                    onDismissRequest = { viewModel.dismissLongPressMenu() },
                    title = { Text(longPressTarget.species) },
                    text = {
                        MapVerifyButtons(
                            status = longPressTarget.verificationStatus,
                            onVerify = {
                                viewModel.dismissLongPressMenu()
                                viewModel.verifyMarker(longPressTarget, it)
                            }
                        )
                    },
                    confirmButton = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            // T173: Ausschnitt abspielen/stoppen (Dialog bleibt offen, Icon zeigt Stopp)
                            OutlinedButton(
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                onClick = { viewModel.toggleMarkerPlayback(longPressTarget) }
                            ) {
                                val playing = playingKey == PlaybackKeys.detection(longPressTarget.detectionId)
                                Icon(
                                    if (playing) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                                    contentDescription = null
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(if (playing) R.string.ui_wiedergabe_stoppen else R.string.common_play))
                            }
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    viewModel.dismissLongPressMenu()
                                    viewModel.selectMarkerForEdit(longPressTarget)
                                }
                            ) { Text(stringResource(R.string.common_edit)) }
                            // T165: Verschieben auch fuer Live-Punkte (ohne Session nur in der Liste -> Hinweis)
                            OutlinedButton(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    viewModel.dismissLongPressMenu()
                                    viewModel.selectMarkerForMove(longPressTarget)
                                }
                            ) { Text(stringResource(R.string.map_position_verschieben)) }
                            if (longPressTarget.isLive && !viewModel.liveMovePersisted) {
                                Text(
                                    stringResource(R.string.map_move_live_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (!longPressTarget.isLive) {
                                Button(
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = {
                                        viewModel.dismissLongPressMenu()
                                        viewModel.enterFocusMode(longPressTarget)
                                    }
                                ) { Text(stringResource(R.string.map_simultanbeobachtung)) }
                            }
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.dismissLongPressMenu() }) { Text(stringResource(R.string.common_cancel)) }
                    }
                )
            }

            // Fokus-beenden-Button
            if (state.focusMode) {
                Button(
                    onClick = { viewModel.exitFocusMode() },
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Text(stringResource(R.string.map_fokus_beenden, state.focusSpecies ?: ""))
                }
            }

            // Verbindungstyp-Dialog
            val linkTarget = state.linkTarget
            if (linkTarget != null) {
                AlertDialog(
                    onDismissRequest = { viewModel.dismissLinkTarget() },
                    title = { Text(stringResource(R.string.map_verbindungstyp)) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                                onClick = { viewModel.confirmLink(MapViewModel.LinkType.SIMULTANEOUS) }
                            ) { Text(stringResource(R.string.map_simultanbeobachtung_2)) }
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336)),
                                onClick = { viewModel.confirmLink(MapViewModel.LinkType.PROBABLE) }
                            ) { Text(stringResource(R.string.map_vermutete_simultanbeobachtung)) }
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3)),
                                onClick = { viewModel.confirmLink(MapViewModel.LinkType.SAME_INDIVIDUAL) }
                            ) { Text(stringResource(R.string.map_gleiches_tier)) }
                        }
                    },
                    confirmButton = {},
                    dismissButton = {
                        TextButton(onClick = { viewModel.dismissLinkTarget() }) { Text(stringResource(R.string.common_cancel)) }
                    }
                )
            }

            // Home-FAB
            FloatingActionButton(
                onClick = {
                    val loc = locationState
                    if (loc != null) {
                        val mv = mapViewRef.value
                        if (mv != null) {
                            // T175: sichtbare Kartenhoehe ca. 200 m
                            val zoom = LocateZoom.zoomForHeight(
                                loc.latitude, mv.height, mv.maxZoomLevel
                            )
                            mv.controller.animateTo(
                                GeoPoint(loc.latitude, loc.longitude),
                                zoom,
                                500L
                            )
                        }
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .helpTopic(R.string.help_map_my_location, "8")
                    .size(84.dp)  // T245: 56 dp * 1.5
            ) {
                Icon(Icons.Filled.MyLocation, contentDescription = stringResource(R.string.map_mein_standort), modifier = Modifier.size(36.dp))
            }

            // T245: Zoom +/- (eigene Knoepfe, 50 % groesser als der Standard), ueber dem Standort-Knopf
            Column(
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 116.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FloatingActionButton(
                    onClick = { mapViewRef.value?.controller?.zoomIn() },
                    modifier = Modifier.helpTopic(R.string.help_map_zoom, "8").size(84.dp)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.map_zoom_in), modifier = Modifier.size(36.dp))
                }
                FloatingActionButton(
                    onClick = { mapViewRef.value?.controller?.zoomOut() },
                    modifier = Modifier.helpTopic(R.string.help_map_zoom, "8").size(84.dp)
                ) {
                    Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.map_zoom_out), modifier = Modifier.size(36.dp))
                }
            }

            // T108: Download-Fortschritt mit Abbrechen
            if (downloadTask != null) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 16.dp, end = 88.dp, bottom = 16.dp)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), MaterialTheme.shapes.medium)
                        .padding(12.dp)
                ) {
                    Text(stringResource(R.string.map_karte_laden_kacheln, downloadDone, downloadTotal), style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(
                        progress = { if (downloadTotal > 0) downloadDone.toFloat() / downloadTotal else 0f },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    )
                    TextButton(onClick = {
                        downloadTask?.cancel(true)
                        downloadTask = null
                        offlineDialog = OfflineDialog.Info(AppStrings.get(R.string.map_download_abgebrochen_bereits_geladen))
                    }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.common_cancel)) }
                }
            }

            when (val d = offlineDialog) {
                is OfflineDialog.Confirm -> AlertDialog(
                    onDismissRequest = { offlineDialog = null },
                    title = { Text(stringResource(R.string.map_ausschnitt_herunterladen)) },
                    text = {
                        Text(stringResource(R.string.map_kacheln_zoom_ca_mb, d.tiles, d.zoomMin, d.zoomMax, OfflineTilePlan.estimatedMegabytes(d.tiles)))
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            offlineDialog = null
                            val mv = mapViewRef.value ?: return@TextButton
                            downloadDone = 0
                            downloadTotal = d.tiles
                            downloadTask = try {
                                CacheManager(mv).downloadAreaAsyncNoUI(
                                    context, d.box, d.zoomMin, d.zoomMax,
                                    object : CacheManager.CacheManagerCallback {
                                        override fun onTaskComplete() {
                                            downloadTask = null
                                            offlineDialog = OfflineDialog.Info(AppStrings.get(R.string.map_download_abgeschlossen_kacheln, d.tiles))
                                        }
                                        override fun onTaskFailed(errors: Int) {
                                            downloadTask = null
                                            offlineDialog = OfflineDialog.Info(
                                                AppStrings.get(R.string.map_download_beendet_kacheln_fehlgeschla, errors)
                                            )
                                        }
                                        override fun updateProgress(progress: Int, currentZoomLevel: Int, zoomMin: Int, zoomMax: Int) {
                                            downloadDone = progress
                                        }
                                        override fun downloadStarted() {}
                                        override fun setPossibleTilesInArea(total: Int) { downloadTotal = total }
                                    }
                                )
                            } catch (e: Exception) {
                                offlineDialog = OfflineDialog.Info(AppStrings.get(R.string.map_download_nicht_moeglich, e.message))
                                null
                            }
                        }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.map_herunterladen)) }
                    },
                    dismissButton = { TextButton(onClick = { offlineDialog = null }) { Text(stringResource(R.string.common_cancel)) } }
                )
                is OfflineDialog.Info -> AlertDialog(
                    onDismissRequest = { offlineDialog = null },
                    text = { Text(d.text) },
                    confirmButton = { TextButton(onClick = { offlineDialog = null }) { Text("OK") } }
                )
                OfflineDialog.ConfirmDelete -> AlertDialog(
                    onDismissRequest = { offlineDialog = null },
                    title = { Text(stringResource(R.string.map_offline_karten_loeschen)) },
                    text = { Text(stringResource(R.string.map_alle_gespeicherten_swisstopo_kacheln, cacheBytes / 1_000_000)) },
                    confirmButton = {
                        TextButton(onClick = {
                            offlineDialog = null
                            scope.launch {
                                withContext(Dispatchers.IO) { runCatching { purgeTileCache() } }
                                mapViewRef.value?.let { it.tileProvider.clearTileCache(); it.invalidate() }
                                cacheBytes = withContext(Dispatchers.IO) { runCatching { tileCacheBytes() }.getOrDefault(0L) }
                            }
                        }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.common_delete)) }
                    },
                    dismissButton = { TextButton(onClick = { offlineDialog = null }) { Text(stringResource(R.string.common_cancel)) } }
                )
                null -> {}
            }

            // T119: Menue-Karte oben links (solide Flaeche, auf jedem Kartenhintergrund lesbar)
            Surface(
                modifier = Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 12.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                shadowElevation = 6.dp
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { panelOpen = true }, modifier = Modifier.helpTopic(R.string.help_map_menu, "8").size(72.dp)) {
                        Icon(Icons.Filled.Menu, contentDescription = stringResource(R.string.map_kartenmenue_oeffnen), modifier = Modifier.size(36.dp))
                    }
                    Text(
                        when (state.filterMode) {
                            FilterMode.ALL -> AppStrings.get(R.string.common_all)
                            FilterMode.LIVE_ONLY -> AppStrings.get(R.string.map_nur_live)
                            FilterMode.RECENT_3 -> AppStrings.get(R.string.map_letzte_3)
                        },
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(end = 14.dp)
                    )
                }
            }

            // T119: Randzone fuer Wischen von links (schmal, damit Karten-Panning ungestoert bleibt)
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(top = 72.dp)
                    .fillMaxHeight()
                    .width(48.dp)  // T122: 24 dp war im Feld kaum zu treffen
                    .systemGestureExclusion()  // T122: Android-Zurueck-Geste hier nicht ausloesen
                    .pointerInput(Unit) {
                        val tracker = SideSwipeTracker(SideSwipeTracker.OPEN_THRESHOLD_DP * density)
                        detectHorizontalDragGestures(
                            onDragStart = { tracker.reset() },
                            onHorizontalDrag = { change, dx ->
                                change.consume()
                                if (tracker.onDrag(dx)) panelOpen = true
                            }
                        )
                    }
            )

            // Ladeindikator
            if (state.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }

            // Leerstate (kein Loading, keine Marker)
            if (!state.isLoading && allMarkers.isEmpty()) {
                Surface(
                    modifier = Modifier.align(Alignment.Center),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp,
                    shadowElevation = 6.dp
                ) {
                    Text(
                        text = stringResource(R.string.map_noch_keine_georeferenzierten_detekti),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
            }
        }

        // T119: Seitenleiste (nicht im Move-Modus)
        if (!inMoveMode) {
            BackHandler(enabled = panelOpen) { panelOpen = false }
            AnimatedVisibility(visible = panelOpen, enter = fadeIn(), exit = fadeOut()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.32f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { panelOpen = false }
                )
            }
            AnimatedVisibility(
                visible = panelOpen,
                enter = slideInHorizontally { -it },
                exit = slideOutHorizontally { -it },
                modifier = Modifier.align(Alignment.CenterStart)
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(320.dp)
                        .pointerInput(Unit) {
                            // Wischen nach links schliesst
                            val tracker = SideSwipeTracker(SideSwipeTracker.OPEN_THRESHOLD_DP * density)
                            detectHorizontalDragGestures(
                                onDragStart = { tracker.reset() },
                                onHorizontalDrag = { change, dx ->
                                    if (tracker.onDrag(-dx)) { change.consume(); panelOpen = false }
                                }
                            )
                        },
                    shape = RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 8.dp,
                    shadowElevation = 12.dp
                ) {
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.map_karte), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                            IconButton(onClick = { panelOpen = false }, modifier = Modifier.size(72.dp)) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.map_menue_schliessen), modifier = Modifier.size(36.dp))
                            }
                        }

                        Text(stringResource(R.string.map_anzeige), style = MaterialTheme.typography.titleSmall)
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.helpTopic(R.string.help_map_filter, "8")) {
                            FilterChip(
                                selected = state.filterMode == FilterMode.ALL,
                                onClick = { viewModel.setFilter(FilterMode.ALL) },
                                label = { Text(stringResource(R.string.common_all)) }
                            )
                            FilterChip(
                                selected = state.filterMode == FilterMode.LIVE_ONLY,
                                onClick = { viewModel.setFilter(FilterMode.LIVE_ONLY) },
                                label = { Text(stringResource(R.string.map_nur_live)) }
                            )
                            FilterChip(
                                selected = state.filterMode == FilterMode.RECENT_3,
                                onClick = { viewModel.setFilter(FilterMode.RECENT_3) },
                                label = { Text(stringResource(R.string.map_letzte_3)) }
                            )
                        }
                        HorizontalDivider()

                        Text(stringResource(R.string.map_kartenquelle), style = MaterialTheme.typography.titleSmall)
                        for (mode in MapSourceMode.entries) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .helpTopic(R.string.help_map_source, "8")
                                    .clickable {
                                        sourceMode = mode
                                        prefs.mapSourceMode = mode.name
                                    },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = mode == sourceMode, onClick = null)
                                Spacer(Modifier.width(12.dp))
                                Text(stringResource(mode.labelRes), style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                        HorizontalDivider()

                        Text(stringResource(R.string.map_offline_karten), style = MaterialTheme.typography.titleSmall)
                        Button(
                            modifier = Modifier.helpTopic(R.string.help_map_offline_download, "8").fillMaxWidth().heightIn(min = 48.dp),
                            enabled = downloadTask == null,
                            onClick = {
                                panelOpen = false
                                val mv = mapViewRef.value ?: return@Button
                                val source = mv.tileProvider.tileSource as? SwisstopoWmtsTileSource
                                if (source == null) {
                                    offlineDialog = OfflineDialog.Info(
                                        AppStrings.get(R.string.map_offline_download_gibt_es_nur, AUTO_SWISSTOPO_MIN_ZOOM)
                                    )
                                    return@Button
                                }
                                val zooms = OfflineTilePlan.zoomRange(mv.zoomLevelDouble.toInt(), source)
                                val box = mv.boundingBox
                                val tiles = try {
                                    CacheManager(mv).possibleTilesInArea(box, zooms.first, zooms.last)
                                } catch (e: Exception) { 0 }
                                offlineDialog = if (OfflineTilePlan.isAllowed(tiles)) {
                                    OfflineDialog.Confirm(box, zooms.first, zooms.last, tiles)
                                } else {
                                    OfflineDialog.Info(
                                        AppStrings.get(R.string.map_dieser_ausschnitt_braucht_kacheln_zo, tiles, zooms.first, zooms.last, OfflineTilePlan.MAX_TILES)
                                    )
                                }
                            }
                        ) { Text(stringResource(R.string.map_ausschnitt_herunterladen_2)) }
                        OutlinedButton(
                            modifier = Modifier.helpTopic(R.string.help_map_offline_delete, "8").fillMaxWidth().heightIn(min = 48.dp),
                            onClick = {
                                panelOpen = false
                                offlineDialog = OfflineDialog.ConfirmDelete
                            }
                        ) { Text(stringResource(R.string.map_offline_karten_mb_loeschen, cacheBytes / 1_000_000)) }

                        // Legende = Artfilter (T204b): alle Arten, Checkbox schaltet sie auf der Karte ein/aus
                        val legendEntries = remember(state.markers, state.liveMarkers, state.filterMode) {
                            MapSpeciesFilter.speciesEntries(
                                if (state.filterMode == FilterMode.LIVE_ONLY) state.liveMarkers else state.markers + state.liveMarkers
                            )
                        }
                        if (legendEntries.isNotEmpty()) {
                            HorizontalDivider()
                            Text(stringResource(R.string.map_legende), style = MaterialTheme.typography.titleSmall)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(14.dp).background(Color(0xFF1976D2), CircleShape))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.map_live_detektion), style = MaterialTheme.typography.bodyMedium)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .helpTopic(R.string.help_map_only_alarm, "8")
                                    .clickable { viewModel.setOnlyAlarm(!state.onlyAlarm) },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                androidx.compose.material3.Checkbox(checked = state.onlyAlarm, onCheckedChange = null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.map_nur_alarmarten), style = MaterialTheme.typography.bodyMedium)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.helpTopic(R.string.help_map_legend, "8")) {
                                TextButton(
                                    onClick = { viewModel.setSpeciesVisible(legendEntries.map { it.key }, true) },
                                    modifier = Modifier.heightIn(min = 48.dp)
                                ) { Text(stringResource(R.string.common_all)) }
                                TextButton(
                                    onClick = { viewModel.setSpeciesVisible(legendEntries.map { it.key }, false) },
                                    modifier = Modifier.heightIn(min = 48.dp)
                                ) { Text(stringResource(R.string.map_keine)) }
                            }
                            // Das Panel scrollt bereits, daher einfache Zeilen statt LazyColumn
                            for (e in legendEntries) {
                                val visible = e.key !in state.hiddenSpecies
                                val name = if (e.scientificName.isBlank()) e.fallbackName.replace('_', ' ')
                                    else nameResolver.displayName(e.scientificName)
                                Row(
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                        .alpha(if (visible) 1f else 0.5f)
                                        .helpTopic(R.string.help_map_legend, "8")
                                        .clickable { viewModel.toggleSpecies(e.key) },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    androidx.compose.material3.Checkbox(checked = visible, onCheckedChange = null)
                                    Spacer(Modifier.width(4.dp))
                                    Box(Modifier.size(14.dp).background(Color(colorRepo.getColor(e.fallbackName)), CircleShape))
                                    Spacer(Modifier.width(8.dp))
                                    Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                    Text("${e.count}", style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Move-Modus UI
        if (inMoveMode) {
            // Fadenkreuz in der Mitte
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = stringResource(R.string.map_zielposition),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(48.dp)
            )

            // T165: Hinweis-Banner oben
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                tonalElevation = 4.dp
            ) {
                Text(
                    text = stringResource(R.string.map_move_banner, state.positionMoveTarget!!.species),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            // T165: grosse Knoepfe unten (56 dp), gut mit dem Daumen erreichbar
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = { viewModel.dismissPositionMove() },
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surface)
                ) { Text(stringResource(R.string.common_cancel)) }
                Button(
                    onClick = {
                        mapViewRef.value?.let { mv ->
                            viewModel.confirmPositionMove(mv.mapCenter.latitude, mv.mapCenter.longitude)
                        }
                    },
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp)
                ) { Text(stringResource(R.string.map_move_apply)) }
            }
        }
    }
}

/** T111: Marker bei gegebenem Zoom zu Clustern gruppieren (Pixel-Schwelle, Web-Mercator). */
private fun buildClusters(
    markers: List<MapViewModel.MapMarker>,
    zoom: Double,
    thresholdPx: Double
): List<MapViewModel.MarkerCluster> {
    if (markers.isEmpty()) return emptyList()
    val lats = DoubleArray(markers.size) { markers[it].latitude }
    val lons = DoubleArray(markers.size) { markers[it].longitude }
    val groups = MarkerClustering.groupByZoom(lats, lons, zoom, thresholdPx, org.osmdroid.util.TileSystem.getTileSize())
    return groups.map { g ->
        val items = g.map { markers[it] }
        MapViewModel.MarkerCluster(items.sumOf { it.latitude } / items.size, items.sumOf { it.longitude } / items.size, items)
    }
}

/** T173: Play/Stopp-Knopf (72 dp, T245) fuer den Detektions-Ausschnitt auf der Karte. */
@Composable
private fun MapPlayButton(playing: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(72.dp)) {
        Icon(
            modifier = Modifier.size(36.dp),
            imageVector = if (playing) Icons.Filled.Stop else Icons.Filled.PlayArrow,
            contentDescription = stringResource(if (playing) R.string.ui_wiedergabe_stoppen else R.string.common_play),
            tint = MaterialTheme.colorScheme.primary
        )
    }
}

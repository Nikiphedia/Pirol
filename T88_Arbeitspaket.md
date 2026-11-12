# T88 — Bugfix Karte 2 (Long-press + Swisstopo Root-Cause)

**Branch:** `T88-kartenbugs2`
**Ziel-Commit-Tag:** `V0.0.6.T88`
**Geschätztes Token-Budget:** ~30 000

---

## Root-Cause-Analysen (nach T87-Feldtest)

### Bug 1 — Swisstopo lädt nicht (Config-Timing)

`LaunchedEffect(Unit)` ist eine Kotlin-Coroutine — sie läuft auf dem nächsten Frame,
**nach** der ersten Composition. Der `AndroidView.factory`-Block läuft während der
ersten Composition und erstellt den `MapView`. `setTileSource(...)` triggert sofort
erste Tile-Downloads.

Resultat: `osmdroidTileCache` zeigt noch auf den **Default-Pfad**
(`/sdcard/osmdroid/`), für den `WRITE_EXTERNAL_STORAGE`-Permission fehlt (nicht im
Manifest). osmdroid kann den Cache nicht schreiben → Downloads schlagen stil fehl →
blank tiles.

**Fix:** `Configuration.getInstance()` in einem synchronen `remember(context)`-Block
setzen (läuft während der ersten Composition, VOR `factory`).

### Bug 2 — Long-press öffnet nie das Menü (Compose PointerInteropFilter)

Compose's `PointerInteropFilter` leitet Events an embedded Android-Views weiter.
Wenn Compose eine Scroll-Geste erkennt (Karte pannen), sendet es `ACTION_CANCEL` an
den `MapView`. Das bricht osmdroid's internen `GestureDetector` ab, bevor die
500ms-Schwelle erreicht wird. Das Overlay-`onLongPress()` wird nie aufgerufen.

**Fix:** Long-press-Erkennung aus dem osmdroid-Overlay-System herausnehmen.
Stattdessen: `GestureDetectorCompat` mit `setOnTouchListener` direkt auf den
`MapView` setzen. `setOnTouchListener` empfängt Events **vor** der Compose-
Verarbeitung und wird nicht von `ACTION_CANCEL` unterbrochen. `return false`
aus dem Listener lässt osmdroid weiterhin pannen/zoomen.

---

## Zu ändernde Dateien

### `app/src/main/kotlin/ch/etasystems/pirol/ui/map/MapScreen.kt`

#### Schritt 1 — Neue Imports (nach vorhandenen Imports einfügen)

```kotlin
import android.view.GestureDetector
import androidx.core.view.GestureDetectorCompat
```

#### Schritt 2 — `MarkerLongPressOverlay`-Klasse **entfernen**

Die gesamte Klasse `private class MarkerLongPressOverlay(...)` (ca. 30 Zeilen) wird
entfernt. Sie wird durch `GestureDetectorCompat` ersetzt.

#### Schritt 3 — In `MapScreen()`: synchrone Config + neue State-Variable

```kotlin
@Composable
fun MapScreen(viewModel: MapViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val colorRepo: SpeciesColorRepository = koinInject()
    val atlasCodeRepo: AtlasCodeRepository = koinInject()

    // Synchron — muss VOR AndroidView.factory laufen
    remember(context) {
        Configuration.getInstance().apply {
            load(context, context.getSharedPreferences("osmdroid", 0))
            userAgentValue = context.packageName
            osmdroidTileCache = java.io.File(context.cacheDir, "osmdroid_tiles")
            tileDownloadThreads = 4.toShort()
            tileDownloadMaxQueueSize = 40.toShort()
        }
    }

    val mapViewRef = remember { mutableStateOf<MapView?>(null) }
    val locationState by viewModel.currentLocation.collectAsState()

    // Marker-Referenz fuer GestureDetector (aktuell, thread-safe via MutableState)
    val longPressMarkersRef = remember { mutableStateOf<List<MapViewModel.MapMarker>>(emptyList()) }
    
    // ENTFERNEN: longPressOverlayRef — wird nicht mehr gebraucht
    // ENTFERNEN: LaunchedEffect(Unit) { Configuration.getInstance()... }
```

#### Schritt 4 — `AndroidView.factory`: GestureDetector statt Overlay

```kotlin
factory = { ctx ->
    MapView(ctx).apply {
        setMultiTouchControls(true)
        controller.setZoom(14.0)
        controller.setCenter(GeoPoint(state.centerLat, state.centerLon))
        setTileSource(tileSourceForZoom(14))
        addMapListener(object : org.osmdroid.events.MapListener {
            override fun onZoom(event: org.osmdroid.events.ZoomEvent): Boolean {
                setTileSource(tileSourceForZoom(event.zoomLevel.toInt()))
                return false
            }
            override fun onScroll(event: org.osmdroid.events.ScrollEvent) = false
        })
    }.also { mapView ->
        mapViewRef.value = mapView

        // GestureDetector direkt auf dem MapView — empfängt Events vor Compose
        val gestureDetector = GestureDetectorCompat(
            ctx,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onLongPress(e: MotionEvent) {
                    val markers = longPressMarkersRef.value
                    val proj = mapView.projection
                    val historisch = markers.filter { !it.isLive }
                    val nearest = historisch.minByOrNull { m ->
                        val px = proj.toPixels(GeoPoint(m.latitude, m.longitude), null)
                        val dx = (px.x - e.x).toDouble()
                        val dy = (px.y - e.y).toDouble()
                        dx * dx + dy * dy
                    } ?: return
                    val nearPx = proj.toPixels(
                        GeoPoint(nearest.latitude, nearest.longitude), null
                    )
                    val dist = Math.hypot(
                        (nearPx.x - e.x).toDouble(),
                        (nearPx.y - e.y).toDouble()
                    )
                    if (dist < 120.0) {   // 120px ≈ 40dp — grosszügiger Tap-Target
                        viewModel.onMarkerLongPress(nearest)
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
```

#### Schritt 5 — `AndroidView.update`: zurück zu einfachem `overlays.clear()`

```kotlin
update = { mapView ->
    // Marker-Referenz aktuell halten (für GestureDetector)
    longPressMarkersRef.value = state.markers

    // Einfaches clear — GestureDetector läuft unabhängig von Overlays
    mapView.overlays.clear()

    // ... Rest unverändert: Polylinien, Cluster-Marker, Live-Marker ...
    // (alles wie vor T87, ohne den subList-Trick und ohne overlay-Update)
    
    mapView.invalidate()
},
```

**Entfernen aus `update`:**
- `if (mapView.overlays.size > 1) { mapView.overlays.subList(1, mapView.overlays.size).clear() }`
- `longPressOverlayRef.value?.updateMarkers(state.markers)`

**Ersetzen durch:**
- `longPressMarkersRef.value = state.markers` (erste Zeile im update-Block)
- `mapView.overlays.clear()` (wie ursprünglich)

---

## Was NICHT geändert wird

- `SwisstopoTileSource.kt` — bleibt wie T87 (`{z}/{y}/{x}` ist WMTS-korrekt)
- `MapViewModel.kt` — keine Änderungen
- `AndroidManifest.xml` — kein `WRITE_EXTERNAL_STORAGE` nötig (wir nutzen `cacheDir`)

---

## Acceptance Criteria

- **AC1:** Long-press auf Marker → Menü erscheint. Test: 5× auf verschiedene Marker drücken (auch bei laufender Aufnahme mit aktiven Live-Detektionen)
- **AC2:** Zoom auf ≥ 13 → Swisstopo-Kacheln erscheinen (keine blank-Tiles)
- **AC3:** `./gradlew compileDebugKotlin` → `BUILD SUCCESSFUL`
- **AC4:** `./gradlew assembleDebug` → `BUILD SUCCESSFUL`

---

## Abhängigkeiten

| Was | Details |
|-----|---------|
| `MapScreen.kt` | 4 Änderungen: remember(context) Config, MarkerLongPressOverlay entfernt, factory GestureDetector, update vereinfacht |
| Neue Imports | `GestureDetector`, `GestureDetectorCompat` |
| Neue Dateien | keine |
| Branch | `T88-kartenbugs2`, KEIN Self-Merge auf master |

# T87 — Bugfix: Karte (Longpress-Menü + Swisstopo + Performance)

**Branch:** `T87-kartenbugs`
**Ziel-Commit-Tag:** `V0.0.6.T87`
**Geschätztes Token-Budget:** ~35 000

---

## Kontext

Feldtest Cluster D hat drei Bugs aufgedeckt:

1. **Long-press auf Marker öffnet kein Menü** (kein Dialog erscheint)
2. **Swisstopo-Kacheln laden gar nicht** (blank bei Zoom ≥ 13)
3. **Karte generell langsam** (kein persistenter Tile-Cache)

Alle drei sind in zwei Dateien zu beheben. Kein neuer Code-Pfad, keine neuen Tests nötig.

---

## Root-Cause-Analysen

### Bug 1 — Longpress (MapScreen.kt)

`AndroidView.update` wird bei **jeder** State-Änderung aufgerufen — auch bei jedem eingehenden Live-Marker (snapshotFlow, ≈ jede Sekunde). Der Block beginnt mit:

```kotlin
mapView.overlays.clear()
mapView.overlays.add(MarkerLongPressOverlay(state.markers) { ... })
```

Ein Long-press braucht ~500 ms. In dieser Zeit feuert `overlays.clear()` mit hoher Wahrscheinlichkeit (Live-Marker-Update), das frisch erstellte `MarkerLongPressOverlay` wird entfernt, bevor osmdroid `onLongPress` darauf feuern kann. Das Event geht verloren.

**Fix:** `MarkerLongPressOverlay` darf nicht bei jedem `update` neu erstellt und gelöscht werden. Lösung:
- Die Klasse bekommt ein `fun updateMarkers(newMarkers: List<MapMarker>)` um die Liste mutable zu halten.
- Der Overlay wird **einmalig** im `factory`-Block erstellt und bleibt persistent im `mapView.overlays[0]`.
- Im `update`-Block wird **nicht** `overlays.clear()` verwendet, sondern nur `overlays.subList(1, overlays.size).clear()` (entfernt alle ausser Index 0 = LongPressOverlay). Dann `overlays[0].updateMarkers(state.markers)` zur Aktualisierung der Marker-Liste.

### Bug 2 — Swisstopo URL (SwisstopoTileSource.kt)

Aktuelle Implementierung:
```kotlin
return (baseUrl[0]
    + MapTileIndex.getZoom(pMapTileIndex) + "/"
    + MapTileIndex.getX(pMapTileIndex) + "/"   // ← FALSCH
    + MapTileIndex.getY(pMapTileIndex)          // ← FALSCH
    + ".jpeg")
// → Ergebnis: {z}/{x}/{y}   (→ 404)
```

Swisstopo-WMTS folgt dem OGC-Standard: URL-Pfad = `{TileMatrix}/{TileRow}/{TileCol}` = **`{z}/{y}/{x}`**.

In osmdroid-Begriffen: `TileRow = getY()`, `TileCol = getX()`.

Korrektur:
```kotlin
return (baseUrl[0]
    + MapTileIndex.getZoom(pMapTileIndex) + "/"
    + MapTileIndex.getY(pMapTileIndex) + "/"   // TileRow zuerst
    + MapTileIndex.getX(pMapTileIndex)          // TileCol zweiter
    + ".jpeg")
// → Ergebnis: {z}/{y}/{x}   (→ 200 OK)
```

### Bug 2b — Tile-Cache-Performance (MapScreen.kt)

`Configuration.getInstance().load(...)` wird in der Composable-Body aufgerufen (nicht in `LaunchedEffect`) → läuft bei jeder Rekomposition. Kein explizites Cache-Verzeichnis und kein User-Agent gesetzt → osmdroid nutzt ein internes Default-Verzeichnis (oft `/data/data/.../files/osmdroid/`), das nicht garantiert persistent ist, und keinen User-Agent-Wert.

**Fix:** Ersetze den `Configuration.getInstance().load(...)` Aufruf durch einen `LaunchedEffect(Unit)`-Block der nur einmal läuft:

```kotlin
LaunchedEffect(Unit) {
    Configuration.getInstance().apply {
        load(context, context.getSharedPreferences("osmdroid", 0))
        userAgentValue = context.packageName
        osmdroidTileCache = java.io.File(context.cacheDir, "osmdroid_tiles")
        tileDownloadThreads = 4.toShort()
        tileDownloadMaxQueueSize = 40.toShort()
    }
}
```

`java.io.File` ist bereits importiert via Android-Kotlin-Stdlib — kein neuer Import nötig. Alternativ `import java.io.File` hinzufügen falls noch nicht vorhanden (prüfen).

---

## Zu ändernde Dateien

### 1. `app/src/main/kotlin/ch/etasystems/pirol/ui/map/SwisstopoTileSource.kt`

Nur Zeilen 15–17 ändern (getX ↔ getY tauschen):

```kotlin
// VORHER:
    override fun getTileURLString(pMapTileIndex: Long): String {
        return (baseUrl[0]
            + MapTileIndex.getZoom(pMapTileIndex) + "/"
            + MapTileIndex.getX(pMapTileIndex) + "/"
            + MapTileIndex.getY(pMapTileIndex)
            + ".jpeg")
    }

// NACHHER:
    override fun getTileURLString(pMapTileIndex: Long): String {
        return (baseUrl[0]
            + MapTileIndex.getZoom(pMapTileIndex) + "/"
            + MapTileIndex.getY(pMapTileIndex) + "/"
            + MapTileIndex.getX(pMapTileIndex)
            + ".jpeg")
    }
```

### 2. `app/src/main/kotlin/ch/etasystems/pirol/ui/map/MapScreen.kt`

**2a. `MarkerLongPressOverlay`-Klasse erweitern** (Zeile ~142–163): `markers`-Feld mutable machen:

```kotlin
private class MarkerLongPressOverlay(
    markers: List<MapViewModel.MapMarker>,
    private val onLongPress: (MapViewModel.MapMarker) -> Unit
) : Overlay() {

    private var currentMarkers: List<MapViewModel.MapMarker> = markers

    fun updateMarkers(newMarkers: List<MapViewModel.MapMarker>) {
        currentMarkers = newMarkers
    }

    override fun onLongPress(e: MotionEvent, mapView: MapView): Boolean {
        val proj = mapView.projection
        val historisch = currentMarkers.filter { !it.isLive }
        val nearest = historisch.minByOrNull { m ->
            val px = proj.toPixels(GeoPoint(m.latitude, m.longitude), null)
            val dx = (px.x - e.x).toDouble()
            val dy = (px.y - e.y).toDouble()
            dx * dx + dy * dy
        } ?: return false
        val nearPx = proj.toPixels(GeoPoint(nearest.latitude, nearest.longitude), null)
        val dist = Math.hypot((nearPx.x - e.x).toDouble(), (nearPx.y - e.y).toDouble())
        if (dist < 50.0) {
            onLongPress(nearest)
            return true
        }
        return false
    }
}
```

**2b. Configuration-Block auf `LaunchedEffect(Unit)` umstellen** (Zeile ~174–175):

```kotlin
// ENTFERNEN:
// Configuration.getInstance().load(context, context.getSharedPreferences("osmdroid", 0))

// EINFÜGEN (nach den remember-Deklarationen, vor dem Box()):
LaunchedEffect(Unit) {
    Configuration.getInstance().apply {
        load(context, context.getSharedPreferences("osmdroid", 0))
        userAgentValue = context.packageName
        osmdroidTileCache = java.io.File(context.cacheDir, "osmdroid_tiles")
        tileDownloadThreads = 4.toShort()
        tileDownloadMaxQueueSize = 40.toShort()
    }
}
```

**2c. `AndroidView` umstrukturieren** (Zeile ~188+):

Im `factory`-Block: `MarkerLongPressOverlay` erstellen und dauerhaft hinzufügen.
Im `update`-Block: `overlays[0]` updaten statt komplett löschen.

```kotlin
// remember für den persistenten Overlay
val longPressOverlayRef = remember { mutableStateOf<MarkerLongPressOverlay?>(null) }

AndroidView(
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
            // Persistenter Long-Press-Overlay an Index 0
            val lpOverlay = MarkerLongPressOverlay(state.markers) { viewModel.onMarkerLongPress(it) }
            overlays.add(lpOverlay)
            longPressOverlayRef.value = lpOverlay
        }.also { mapViewRef.value = it }
    },
    update = { mapView ->
        // Nur dynamische Overlays (Index 1+) entfernen, Long-Press-Overlay bleibt
        if (mapView.overlays.size > 1) {
            mapView.overlays.subList(1, mapView.overlays.size).clear()
        }
        // Marker-Liste im persistenten Overlay aktualisieren
        longPressOverlayRef.value?.updateMarkers(state.markers)

        // ... Rest unverändert: Polylinien, Cluster-Marker, Live-Marker ...
        // (alles was bisher nach dem overlays.add(MarkerLongPressOverlay...) stand,
        //  bleibt UNVERÄNDERT, wird nur noch an mapView.overlays.add() direkt gehängt
        //  ohne den vorherigen overlays.clear()-Aufruf)

        mapView.invalidate()
    },
    modifier = Modifier.fillMaxSize()
)
```

**Wichtig:** Die `val longPressOverlayRef = remember { ... }` Deklaration muss **vor** dem `AndroidView`-Aufruf in der Composable-Funktion stehen (also auf Composable-Ebene, nicht innerhalb von `factory`/`update`).

---

## Acceptance Criteria

- **AC1:** Long-press auf historischen Marker → Menü erscheint zuverlässig, auch wenn gleichzeitig Live-Detektionen aktiv sind (mehrfach testen: ≥ 5 Versuche)
- **AC2:** Bei Zoom ≥ 13 werden Swisstopo-Kacheln geladen (keine leeren Tiles, Logcat zeigt keine 404)
- **AC3:** Nach erstem Ladevorgang sind Tiles beim nächsten App-Start ohne Netz-Download sichtbar (Cache-Verzeichnis `context.cacheDir/osmdroid_tiles` wird befüllt)
- **AC4:** `./gradlew compileDebugKotlin` → `BUILD SUCCESSFUL`
- **AC5:** `./gradlew assembleDebug` → `BUILD SUCCESSFUL`

---

## Nicht im Scope

- Bitmap-Caching für Marker-Icons (`createCircleMarkerIcon` wird bei jedem Update aufgerufen — separate Optimierung, nicht in T87)
- Cluster-Auswahl im Focus-Modus (T84-Beobachtung, separates Thema)
- Tile-Offline-Download (Cluster H / V0.0.8)

---

## Abhängigkeiten

| Was | Details |
|-----|---------|
| `SwisstopoTileSource.kt` | Einzige Änderung: X/Y tauschen |
| `MapScreen.kt` | 3 Stellen: Configuration→LaunchedEffect, MarkerLongPressOverlay +updateMarkers(), AndroidView factory/update Refactor |
| Neue Dateien | keine |
| Neue Abhängigkeiten | keine |
| Branch | `T87-kartenbugs`, KEIN Self-Merge auf master |

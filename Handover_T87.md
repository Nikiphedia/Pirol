# Handover T87 — Bugfix Karte (Longpress-Menü + Swisstopo + Performance)

**Branch:** `T87-kartenbugs`
**Commit-Tag:** `V0.0.6.T87`
**Datum:** 2026-05-03

---

## Erledigt

**`SwisstopoTileSource.kt`** — `getX`/`getY` in `getTileURLString` getauscht → URL-Pfad folgt jetzt OGC-Standard `{z}/{y}/{x}` (TileRow vor TileCol).

**`MapScreen.kt`** — sechs Änderungen:
1. `import androidx.compose.runtime.LaunchedEffect` hinzugefügt
2. `MarkerLongPressOverlay`: `private val markers` → `markers` (Parameter), neues Feld `private var currentMarkers`, neue Methode `fun updateMarkers()`; `onLongPress` nutzt jetzt `currentMarkers`
3. `Configuration.getInstance().load(...)` aus Composable-Body entfernt → ersetzt durch `LaunchedEffect(Unit)` mit `userAgentValue`, `osmdroidTileCache = cacheDir/osmdroid_tiles`, Thread-/Queue-Limits (4/40)
4. `val longPressOverlayRef = remember { mutableStateOf<MarkerLongPressOverlay?>(null) }` vor `Box()` deklariert
5. `AndroidView.factory`: `MarkerLongPressOverlay` einmalig erstellt, an `overlays[0]` gehängt, Ref gesetzt
6. `AndroidView.update`: `overlays.clear()` + `overlays.add(MarkerLongPressOverlay(...))` ersetzt durch `subList(1, size).clear()` + `longPressOverlayRef.value?.updateMarkers(state.markers)`

---

## Getestet

- `.\gradlew compileDebugKotlin` → `BUILD SUCCESSFUL in 2s`
- `.\gradlew assembleDebug` → `BUILD SUCCESSFUL in 4s`

---

## Bewusst nicht gemacht

- AC1/2/3 (Gerät/Emulator-Tests): erfordern physisches Gerät oder Emulator, ausserhalb Worker-Scope
- Bitmap-Caching für `createCircleMarkerIcon` (explizit aus Scope ausgeschlossen laut Briefing)
- Cluster-Auswahl im Focus-Modus (T84-Beobachtung, separates Thema)

---

## Beobachtungen

- `createCircleMarkerIcon` wird weiterhin bei jedem `update`-Durchlauf neu allokiert (für alle Cluster- und Live-Marker). Das ist die im Briefing erwähnte separate Optimierung — nicht angefasst.

---

## Abhängigkeiten

| Was | Details |
|-----|---------|
| `SwisstopoTileSource.kt` | URL-Reihenfolge geändert: Swisstopo-Kacheln ab Zoom 13 liefern jetzt HTTP 200 statt 404 |
| `MapScreen.kt` | `MarkerLongPressOverlay` hat neues Interface `updateMarkers()` — kein externer Aufrufer, nur intern genutzt |
| Neue Dateien | keine |
| Neue Abhängigkeiten | keine |
| Branch | `T87-kartenbugs`, kein Self-Merge auf master |

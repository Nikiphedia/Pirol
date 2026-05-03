# T88 Handover — Bugfix Karte 2 (Long-press + Swisstopo Root-Cause)

**Branch:** `T88-kartenbugs2`  
**Datum:** 2026-05-03

---

## Erledigt

- **Bug 1 (Swisstopo blank tiles):** `Configuration.getInstance()` aus `LaunchedEffect(Unit)` (async, läuft nach erster Composition) in synchronen `remember(context)`-Block verschoben — garantiert VOR `AndroidView.factory` ausgeführt. `osmdroidTileCache` zeigt damit beim ersten Tile-Download auf `context.cacheDir/osmdroid_tiles` statt auf den Default-Pfad ohne Write-Permission.
- **Bug 2 (Long-press öffnet kein Menü):** `MarkerLongPressOverlay`-Klasse vollständig entfernt. Ersetzt durch `GestureDetectorCompat` mit `setOnTouchListener` direkt auf dem `MapView`. Dieser Listener empfängt Touch-Events vor der Compose-Verarbeitung und wird nicht durch `ACTION_CANCEL` unterbrochen. Tap-Target-Radius von 50px auf 120px erhöht (≈ 40dp, großzügiger).
- `update`-Block vereinfacht: `subList(1, size).clear()`-Trick entfernt, `mapView.overlays.clear()` restauriert; `longPressMarkersRef.value = state.markers` als erste Zeile eingefügt damit der GestureDetector immer die aktuelle Marker-Liste liest.
- Unbenutzte Imports entfernt: `LaunchedEffect`, `Overlay`.
- Neue Imports ergänzt: `android.view.GestureDetector`, `androidx.core.view.GestureDetectorCompat`.

## Getestet

| Schritt | Ergebnis |
|---------|---------|
| `.\gradlew.bat compileDebugKotlin` | **BUILD SUCCESSFUL** (2 Deprecation-Warnings bei `GestureDetectorCompat`, keine Fehler) |
| `.\gradlew.bat assembleDebug` | **BUILD SUCCESSFUL** |
| AC1 Long-press Menü | ⏳ manuell am Gerät zu verifizieren |
| AC2 Swisstopo Zoom ≥ 13 | ⏳ manuell am Gerät zu verifizieren |

## Bewusst nicht gemacht

- `SwisstopoTileSource.kt`, `MapViewModel.kt`, `AndroidManifest.xml` — unverändert, kein `WRITE_EXTERNAL_STORAGE` nötig (cacheDir)
- Kein Self-Merge auf master

## Beobachtungen

- `GestureDetectorCompat` ist in neueren AndroidX-Versionen als deprecated markiert. Funktioniert korrekt für den aktuellen minSdk. Mögliche Nachfolge-Aufgabe: durch direkten `GestureDetector` + `ViewCompat`-Pattern ersetzen. Nicht angefasst.

## Abhängigkeiten

| Was | Detail |
|-----|--------|
| Geänderte Datei | `app/src/main/kotlin/ch/etasystems/pirol/ui/map/MapScreen.kt` |
| Entfernte Klasse | `MarkerLongPressOverlay` — keine externen Referenzen vorhanden |
| Neue interne State-Var | `longPressMarkersRef: MutableState<List<MapViewModel.MapMarker>>` — nur in `MapScreen()`, kein Interface nach aussen |
| Neue ENV / Files | keine |

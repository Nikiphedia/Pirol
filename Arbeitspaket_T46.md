# Arbeitspaket T46: Offline-Karten Download

## Ziel

User kann auf der Karte eine Region auswaehlen (Bounding Box), Zoom-Stufen bestimmen und Tiles fuer Offline-Nutzung herunterladen. Fortschritt wird angezeigt. Tiles werden lokal gespeichert und von osmdroid automatisch genutzt.

## Kontext

- **Abhaengigkeit:** T45 (Tile-Source-Abstraktion) — ist implementiert und committed
- **osmdroid:** Version 6.1.18 (in `gradle/libs.versions.toml`)
- **Tile-Quellen:** `TileSourceConfig.kt` — Enum `MapTileSource` (OSM, SWISSTOPO_MAP, SWISSTOPO_AERIAL), Factory `PirolTileSourceFactory`
- **Speicher:** `StorageManager.kt` — unterstuetzt Intern + SD-Karte (`getExternalFilesDirs()`)
- **Karte:** `MapScreen.kt` — AndroidView mit MapView, FilterChips fuer Tile-Source, Marker-Overlay
- **MapViewModel.kt** — Laedt Detektionen, State: markers, center, loading

### Relevante osmdroid APIs

osmdroid 6.1.18 bietet eingebautes Tile-Caching:
- `SqlTileWriter` — SQLite-basierter Cache (Default, automatisch aktiv)
- `CacheManager` — Klasse fuer Bulk-Downloads mit Callback-Interface
- `CacheManager(mapView)` oder `CacheManager(tileSource, tileWriter, minZoom, maxZoom)`
- `CacheManager.downloadAreaAsync(ctx, boundingBox, zoomMin, zoomMax, callback)`
- `CacheManager.CacheManagerCallback` — `onTaskComplete`, `onTaskFailed`, `updateProgress(progress, currentZoom, zoomMin, zoomMax)`
- `CacheManager.possibleTilesInArea(boundingBox, zoomMin, zoomMax)` — Tile-Count schaetzen

### Normung (aus CLAUDE.md)

- Packages: lowercase (`ch.etasystems.pirol.ui.map`)
- Klassen: PascalCase
- Funktionen: camelCase
- Konstanten: SCREAMING_SNAKE
- Kommentare: Deutsch
- UI-Strings: Deutsch
- Coroutines statt Threads
- Kein GlobalScope — ViewModel-Scope oder Service-Scope

---

## Anforderungen

### 1. Download-Trigger UI (in MapScreen)

- Button "Offline speichern" (oder FAB mit Download-Icon) auf der Karte
- Oeffnet ein Bottom Sheet oder Dialog mit:
  - **Aktuelle Karten-Ansicht** als Bounding Box (automatisch aus MapView)
  - **Zoom-Bereich:** Zwei Slider oder Dropdown (Min/Max Zoom, Default: aktuelle Zoom-Stufe bis +3)
  - **Tile-Anzahl Schaetzung:** Live-Anzeige basierend auf `CacheManager.possibleTilesInArea()`
  - **Speicherplatz Schaetzung:** ~15 KB/Tile fuer OSM, ~25 KB/Tile fuer swisstopo (Richtwerte)
  - **Aktive Tile-Source** wird automatisch uebernommen (aus `selectedSource` State)
  - **Start-Button:** Startet Download

### 2. Download-Logik

- `TileDownloadManager` Klasse in `ch.etasystems.pirol.ui.map/` (oder `data/` falls besser passend)
- Nutzt osmdroid `CacheManager` intern
- Download laeuft auf IO-Dispatcher (Coroutines)
- Fortschritt als `StateFlow<DownloadProgress>`:
  ```kotlin
  data class DownloadProgress(
      val totalTiles: Int,
      val downloadedTiles: Int,
      val currentZoom: Int,
      val isDownloading: Boolean,
      val error: String? = null
  )
  ```
- Cancel-Funktion (CacheManager unterstuetzt `cancelAllJobs()`)
- Bei Fehler: Retry-Option, bereits heruntergeladene Tiles bleiben erhalten

### 3. Fortschrittsanzeige

- Waehrend Download: `LinearProgressIndicator` mit Prozent-Anzeige
- Text: "Zoom {z}: {n}/{total} Tiles" 
- Cancel-Button
- Bei Abschluss: Toast oder Snackbar "Download abgeschlossen: {n} Tiles gespeichert"

### 4. Speicherort

- osmdroid nutzt standardmaessig `Configuration.getInstance().osmdroidTileCache`
- Diesen Pfad auf den vom User gewaehlten Speicherort setzen (via StorageManager):
  ```kotlin
  Configuration.getInstance().osmdroidTileCache = File(storageManager.getActiveStoragePath(), "tiles")
  ```
- In `MapScreen.kt` bei der osmdroid-Konfiguration setzen (vor MapView-Erstellung)

### 5. Download-Metadaten

Fuer das spaetere Management-UI (T47) muessen Metadaten gespeichert werden:
- `TileDownloadRecord` als `@Serializable` Data Class:
  ```kotlin
  data class TileDownloadRecord(
      val id: String,           // UUID
      val source: String,       // MapTileSource.id ("osm", "swisstopo_map", etc.)
      val boundingBox: BoundingBoxRecord,  // lat/lon Grenzen
      val zoomMin: Int,
      val zoomMax: Int,
      val tileCount: Int,
      val downloadedAt: String, // ISO 8601
      val label: String = ""    // User-Label (optional)
  )
  
  data class BoundingBoxRecord(
      val north: Double, val south: Double,
      val east: Double, val west: Double
  )
  ```
- Gespeichert als JSON-Array in `{tileCache}/downloads.json`

---

## Scope-Ausschluss

- **Kein Management-UI** — das ist T47 (Loeschen, Liste, Speicherplatz)
- **Kein Bounding-Box-Zeichnen** auf der Karte — aktuelle Ansicht reicht
- **Kein Background-Download** via WorkManager — Download nur waehrend App-Nutzung
- **Keine Tile-Expiry-Logik** — Tiles bleiben bis manuelles Loeschen (T47)

## Testanforderung

- `./gradlew compileDebugKotlin` — BUILD SUCCESSFUL
- Fortschritts-UI reagiert auf State-Changes (manueller Test)
- Download-Record wird in `downloads.json` geschrieben
- Nach Download: Karte funktioniert auch ohne Netz (manueller Test auf Geraet)

## Acceptance Criteria

1. Button/FAB auf MapScreen startet Download-Dialog
2. Tile-Anzahl wird vor Download angezeigt (Schaetzung)
3. Fortschrittsanzeige waehrend Download (Prozent, aktuelle Zoom-Stufe)
4. Cancel bricht Download ab, bereits geladene Tiles bleiben
5. Download-Metadaten in `downloads.json` gespeichert
6. osmdroid Tile-Cache-Pfad respektiert StorageManager-Einstellung
7. Build kompiliert fehlerfrei
8. Handover_T46.md mit Dateiliste

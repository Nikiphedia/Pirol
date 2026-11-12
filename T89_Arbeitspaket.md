# T89 — Karte: Position-Move per Drag + osmdroid-Init in Application

**Branch:** `T89-karte-move-touch`
**Ziel-Commit-Tag:** `V0.0.6.T89`
**Geschätztes Token-Budget:** ~35 000

---

## Zwei Aufgaben

### Aufgabe A — osmdroid-Init in PirolApp (Tiles laden endlich)

**Root-Cause:** osmdroid's `MapTileProvider` wird im `MapView`-Konstruktor
initialisiert — nicht erst beim `setTileSource()`-Aufruf. Das heisst: sobald
`MapView(ctx)` aufgerufen wird, sucht der Provider das Cache-Verzeichnis.
`remember(context)` in T88 läuft im Composable, also nach dem ersten Render-Frame
— zu spät für den MapView-Konstruktor.

**Korrekte Lösung (offizielle osmdroid-Empfehlung):** Initialisierung in
`Application.onCreate()`. Läuft zwingend vor jeder Activity, jedem Composable und
jedem MapView.

#### `PirolApp.kt`

```kotlin
import org.osmdroid.config.Configuration

class PirolApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // osmdroid — zwingend vor erstem MapView-Konstruktor
        Configuration.getInstance().apply {
            load(this@PirolApp, getSharedPreferences("osmdroid", 0))
            userAgentValue = packageName
            osmdroidTileCache = java.io.File(cacheDir, "osmdroid_tiles")
            tileDownloadThreads = 4.toShort()
            tileDownloadMaxQueueSize = 40.toShort()
        }

        startKoin {
            androidLogger()
            androidContext(this@PirolApp)
            modules(appModule)
        }
    }
}
```

#### `MapScreen.kt` — `remember(context)`-Config-Block entfernen

Den Block:
```kotlin
remember(context) {
    Configuration.getInstance().apply { ... }
}
```
vollständig löschen — Config ist jetzt in PirolApp. Unbenutzte Imports danach
entfernen (`import org.osmdroid.config.Configuration` falls nur dort verwendet).

---

### Aufgabe B — Position verschieben per Drag (Center-Pin UX)

#### UX-Flow (wie OsmAnd / Google Maps)

1. User long-press auf Marker → Menü → „Position verschieben"
2. Karte wird **vollständig** sichtbar — alle UI-Elemente ausgeblendet
   (FilterChips, Home-FAB, Fokus-Button, sonstige Overlays)
3. Karte springt zum aktuellen Marker: `controller.animateTo(GeoPoint(lat, lon))`
4. Zentriert auf dem Bildschirm: **Fadenkreuz-Icon** (Pin, fest, bewegt sich nicht)
5. Oben links: **Abbrechen-Button** (X-Icon oder OutlinedButton)
   Oben rechts: **Speichern-Button** (Checkmark-Icon oder Button)
6. User verschiebt die Karte → Fadenkreuz bleibt in Bildschirmmitte fixiert
7. Speichern → `viewModel.confirmPositionMove(mapView.mapCenter.latitude, mapView.mapCenter.longitude)`
8. Abbrechen → `viewModel.dismissPositionMove()`, alle UI wieder sichtbar

#### Warum Center-Pin statt Marker-Drag?

- Drag auf osmdroid-`Marker` erfordert custom `onTouch`-Overlay, Koordinaten-Transformation und Live-Update — komplex und fehleranfällig
- Center-Pin ist verbreitet (Google Maps, OsmAnd, Apple Maps beim Pin-Drop) und auf Touchscreens ergonomischer (Finger blockiert nicht den Zielbereich)

---

## Implementierung

### `MapScreen.kt`

#### 1. Koordinaten-Dialog **entfernen** (~45 Zeilen)

Den Block komplett löschen:
```kotlin
// Position-Verschieben-Dialog
val target = state.positionMoveTarget
if (target != null) {
    var latText by remember(target.detectionId) { ... }
    ...
    AlertDialog( ... )
}
```
Dazu ggf. Imports für `KeyboardOptions`, `KeyboardType` entfernen falls nach dem
Löschen unbenutzt.

#### 2. Karte zu Marker-Position navigieren wenn Move-Modus startet

Im `AndroidView.update`-Block, direkt nach `positionMoveRef.value = state.positionMoveTarget`
(bzw. setze dort auch mapViewRef):

```kotlin
// Karte zu Ziel-Marker navigieren wenn Move-Modus frisch startet
val moveTarget = state.positionMoveTarget
if (moveTarget != null) {
    mapView.controller.animateTo(GeoPoint(moveTarget.latitude, moveTarget.longitude))
}
```

**Achtung:** `animateTo` nur beim Eintritt in den Modus aufrufen, nicht bei jedem
Update. Lösung: nur aufrufen wenn `positionMoveRef.value` vorher null war und
jetzt gesetzt wird. Prüfung:

```kotlin
val prevMoveTarget = positionMoveRef.value
positionMoveRef.value = moveTarget
if (prevMoveTarget == null && moveTarget != null) {
    mapView.controller.animateTo(GeoPoint(moveTarget.latitude, moveTarget.longitude))
}
```

#### 3. `Box`-Composable: bedingte UI-Sichtbarkeit

Den `state.positionMoveTarget`-Zustand für die Anzeige nutzen:

```kotlin
val inMoveMode = state.positionMoveTarget != null

// Cluster/Marker/FAB/FilterChips nur anzeigen wenn NICHT im Move-Modus
if (!inMoveMode) {
    // Filter-Chips (Column oben)
    // Home-FAB
    // Fokus-beenden-Button
    // Cluster-Dialog
    // Edit-Detection-Dialog
    // Long-Press-Dialog
    // Link-Target-Dialog
}

// Move-Modus-UI (nur wenn inMoveMode)
if (inMoveMode) {
    // Fadenkreuz in der Mitte
    Icon(
        imageVector = Icons.Filled.Add,   // "+" als Fadenkreuz
        contentDescription = "Zielposition",
        tint = MaterialTheme.colorScheme.error,
        modifier = Modifier
            .align(Alignment.Center)
            .size(48.dp)
    )

    // Abbrechen — oben links
    IconButton(
        onClick = { viewModel.dismissPositionMove() },
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(8.dp)
    ) {
        Icon(Icons.Filled.Close, contentDescription = "Abbrechen",
            tint = MaterialTheme.colorScheme.onSurface)
    }

    // Speichern — oben rechts
    IconButton(
        onClick = {
            mapViewRef.value?.let { mv ->
                viewModel.confirmPositionMove(
                    mv.mapCenter.latitude,
                    mv.mapCenter.longitude
                )
            }
        },
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(8.dp)
    ) {
        Icon(Icons.Filled.Check, contentDescription = "Speichern",
            tint = MaterialTheme.colorScheme.primary)
    }

    // Art-Name als Kontext
    Text(
        text = state.positionMoveTarget!!.species,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 16.dp)
            .background(
                MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                shape = MaterialTheme.shapes.small
            )
            .padding(horizontal = 12.dp, vertical = 4.dp),
        style = MaterialTheme.typography.bodyMedium
    )
}
```

**Nötige Imports** (prüfen ob vorhanden, sonst ergänzen):
- `androidx.compose.material.icons.filled.Add`
- `androidx.compose.material.icons.filled.Close`
- `androidx.compose.material.icons.filled.Check`
- `androidx.compose.ui.graphics.toArgb` (falls nötig)
- `androidx.compose.foundation.background`
- `androidx.compose.ui.draw.alpha` (falls nötig)

**Hinweis:** `Icons.Filled.Add` als Fadenkreuz funktioniert — ist ein „+"-Zeichen.
Alternativ `Icons.Filled.GpsFixed` (Zielkreuz) falls im Material-Icons-Extended-Set
vorhanden. Extended Icons sind bereits im Classpath prüfen via Grep auf
`material-icons-extended` in `build.gradle`.

#### 4. Im Move-Modus: Overlays minimal halten

Im `AndroidView.update`-Block: wenn `inMoveMode`, nur den Ziel-Marker (orange, 80px)
rendern, keine anderen Cluster-Marker, keine Live-Marker, keine Polylinien.

```kotlin
if (state.positionMoveTarget != null) {
    // Nur Ziel-Marker anzeigen (orange, gross)
    val mv = state.positionMoveTarget!!
    val moveMarker = Marker(mapView)
    moveMarker.position = GeoPoint(mv.latitude, mv.longitude)
    moveMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
    moveMarker.icon = createCircleMarkerIcon(
        mapView.resources,
        android.graphics.Color.parseColor("#FF8F00"),
        speciesLabel(mv.species),
        size = 80
    )
    moveMarker.title = mv.species
    mapView.overlays.add(moveMarker)
} else {
    // Normaler Render-Pfad: Polylinien + Cluster + Live-Marker
    // ... (alles wie bisher) ...
}
```

---

## Was NICHT geändert wird

- `MapViewModel.kt` — keine Änderungen (`positionMoveTarget`, `confirmPositionMove`,
  `dismissPositionMove`, `selectMarkerForMove` bleiben unverändert)
- `SessionManager.kt` — `recordPositionMoved()` unverändert
- `SwisstopoTileSource.kt` — unverändert

---

## Acceptance Criteria

- **AC1:** OSM-Kacheln laden nach Kalt-Start (Regression-Check nach PirolApp-Fix)
- **AC2:** Swisstopo-Kacheln laden bei Zoom ≥ 13
- **AC3:** Long-press → „Position verschieben" → Karte springt zu Marker, Fadenkreuz sichtbar, alle anderen UI-Elemente ausgeblendet
- **AC4:** Karte verschieben → Fadenkreuz bleibt in Mitte fixiert
- **AC5:** Speichern → Marker an neuer Position, normaler UI-Zustand zurück
- **AC6:** Abbrechen → Marker unverändert, normaler UI-Zustand zurück
- **AC7:** `./gradlew compileDebugKotlin` → `BUILD SUCCESSFUL`
- **AC8:** `./gradlew assembleDebug` → `BUILD SUCCESSFUL`

---

## Abhängigkeiten

| Was | Details |
|-----|---------|
| `PirolApp.kt` | osmdroid-Config in onCreate() — 1 neuer Import |
| `MapScreen.kt` | remember-Config raus, Koordinaten-Dialog raus, Move-Modus UI (Fadenkreuz + 2 Buttons + Art-Name), bedingter Render-Pfad im update-Block |
| Neue Dateien | keine |
| Neue Permissions | keine |
| Branch | `T89-karte-move-touch`, KEIN Self-Merge auf master |

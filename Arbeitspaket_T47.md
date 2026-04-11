# Arbeitspaket T47: Download-Management-UI

## Ziel

User kann heruntergeladene Offline-Karten-Regionen verwalten: Liste aller Downloads mit Name/Groesse/Datum anzeigen, einzelne Regionen loeschen, Gesamtspeicherplatz sehen.

## Kontext

- **Abhaengigkeit:** T46 (Offline-Download) — implementiert und committed (`7946694`)
- **Projekt-Root:** `C:\eta_Data\80002\PIROL`
- **Konventionen:** `CLAUDE.md` im Projekt-Root
- **Build:** `./gradlew compileDebugKotlin` funktioniert (T55)

### Bestehende Infrastruktur

**TileDownloadManager** (`ui/map/TileDownloadManager.kt`):
- `TileDownloadRecord`: id, source, boundingBox (north/south/east/west), zoomMin, zoomMax, tileCount, downloadedAt (ISO 8601), label
- `BoundingBoxRecord`: north, south, east, west (Double)
- Downloads werden in `downloads.json` im Tile-Cache-Verzeichnis gespeichert (`Configuration.getInstance().osmdroidTileCache`)
- `saveDownloadRecord()` ist private — nur intern beim Download-Abschluss aufgerufen
- JSON-Format: `List<TileDownloadRecord>` als Array

**MapScreen** (`ui/map/MapScreen.kt`):
- Tile-Cache-Pfad: `AppPreferences.storagePath + "/tiles"` oder `filesDir/tiles`
- FAB "Offline speichern" → Bottom Sheet mit Download-Config
- SnackbarHost fuer Meldungen

**MapViewModel** (`ui/map/MapViewModel.kt`):
- Konstruktor: `SessionManager`, `TileDownloadManager`
- Download-Funktionen: `showDownloadSheet()`, `startDownload()`, `cancelDownload()`

### Normung (aus CLAUDE.md)

- Packages: lowercase (`ch.etasystems.pirol.ui.map`)
- Klassen: PascalCase
- Funktionen: camelCase
- Kommentare: Deutsch
- UI-Strings: Deutsch
- Coroutines statt Threads

---

## Anforderungen

### 1. TileDownloadManager erweitern

Neue oeffentliche Methoden:

```kotlin
/** Alle gespeicherten Download-Records laden */
fun loadDownloadRecords(): List<TileDownloadRecord>

/** Einen Download-Record loeschen (nur Metadaten — Tiles bleiben im Cache) */
fun deleteDownloadRecord(id: String)

/** Gesamtgroesse des Tile-Cache-Verzeichnisses in Bytes */
fun getCacheSizeBytes(): Long

/** Gesamten Tile-Cache loeschen (alle Tiles + downloads.json) */
fun clearCache()
```

**Wichtig:** osmdroid speichert Tiles in einer SQLite-DB (`SqlTileWriter`), nicht als einzelne Dateien pro Region. Daher kann man **nicht** Tiles einer einzelnen Region gezielt loeschen. Zwei Optionen:

- **Option A (empfohlen):** `deleteDownloadRecord()` loescht nur den Metadaten-Eintrag aus `downloads.json`. Die Tiles bleiben im Cache. `clearCache()` loescht den gesamten Cache (alle Regionen). In der UI klar kommunizieren: "Eintrag entfernen" vs. "Gesamten Cache loeschen".
- **Option B:** osmdroid Cache-DB oeffnen und Tiles per Bounding Box + Zoom manuell loeschen. Komplex und fragil.

→ **Nimm Option A.**

### 2. MapViewModel erweitern

Neuer State:
```kotlin
data class MapUiState(
    // ... bestehende Felder ...
    val showManagementSheet: Boolean = false,
    val downloadRecords: List<TileDownloadRecord> = emptyList(),
    val cacheSizeMb: Double = 0.0
)
```

Neue Methoden:
```kotlin
fun showManagementSheet()    // Records laden + Sheet oeffnen
fun hideManagementSheet()
fun deleteRecord(id: String) // Einzelnen Eintrag entfernen
fun clearAllTiles()           // Gesamten Cache loeschen
```

### 3. Management-UI (Bottom Sheet oder Dialog)

Neuer Einstiegspunkt auf MapScreen: zweiter FAB oder Icon-Button neben dem Download-FAB.
Alternativ: Long-Press auf Download-FAB oeffnet Management.
**Empfehlung:** Zweiter FAB (Icons.Default.Layers oder Icons.Default.Storage) links neben dem Download-FAB.

**Bottom Sheet / Dialog Inhalt:**

```
Offline-Karten verwalten
────────────────────────
Gesamt: 142 MB

┌──────────────────────────────────────┐
│ OpenStreetMap                        │
│ Zoom 10-14 · 2'340 Tiles            │
│ 11.04.2026, 14:30                   │
│                          [Entfernen] │
├──────────────────────────────────────┤
│ swisstopo Landeskarte                │
│ Zoom 12-16 · 890 Tiles              │
│ 11.04.2026, 15:45                   │
│                          [Entfernen] │
└──────────────────────────────────────┘

[Gesamten Cache loeschen]
```

**Details pro Eintrag:**
- Tile-Source Name (MapTileSource.fromId(record.source).displayName)
- Zoom-Bereich: `${record.zoomMin}-${record.zoomMax}`
- Tile-Anzahl: `record.tileCount` (mit Tausender-Trennzeichen)
- Datum: `record.downloadedAt` formatiert (Datum + Uhrzeit, kein ISO)
- Label falls vorhanden: `record.label`
- "Entfernen"-Button → Bestaetigung → `deleteRecord(id)`

**"Gesamten Cache loeschen":**
- Bestaetigugs-Dialog: "Alle offline Karten loeschen? Dies kann nicht rueckgaengig gemacht werden."
- Loescht Tile-DB + downloads.json
- Schliesst Sheet

**Leerer Zustand:**
- Text: "Keine offline Karten gespeichert"

### 4. Datum formatieren

`downloadedAt` ist ISO 8601 (`2026-04-11T14:30:00Z`). Fuer die UI formatieren:
```kotlin
val instant = Instant.parse(record.downloadedAt)
val formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm")
    .withZone(ZoneId.systemDefault())
formatter.format(instant)
```

### 5. Speicherplatz berechnen

Gesamtgroesse des Cache-Verzeichnisses rekursiv:
```kotlin
fun getCacheSizeBytes(): Long {
    val cacheDir = Configuration.getInstance().osmdroidTileCache
    return cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
```

In der UI als MB anzeigen: `"%.1f MB".format(bytes / 1_048_576.0)`

---

## Scope-Ausschluss

- **Kein per-Region Tile-Loeschen** — osmdroid SQLite-Cache erlaubt das nicht sauber
- **Kein Label-Editieren** — TileDownloadRecord.label bleibt leer (koennte spaeter ergaenzt werden)
- **Kein Region-auf-Karte-anzeigen** — Bounding Box der Downloads nicht visuell darstellen
- **Keine Download-Wiederholung** — kein "Erneut herunterladen" Button

## Testanforderung

- `./gradlew compileDebugKotlin` — BUILD SUCCESSFUL
- Leerer Zustand korrekt angezeigt (keine Downloads vorhanden)
- Nach Download (T46): Eintrag erscheint in der Liste
- "Entfernen" loescht Eintrag aus Liste (Tiles bleiben)
- "Gesamten Cache loeschen" leert Verzeichnis + JSON
- Speicherplatz-Anzeige aktualisiert sich nach Loeschen

## Acceptance Criteria

1. Management-UI erreichbar via FAB/Button auf MapScreen
2. Download-Records als Liste angezeigt (Source, Zoom, Tiles, Datum)
3. Einzelne Records entfernbar (Bestaetigung)
4. "Gesamten Cache loeschen" mit Bestaetigung
5. Speicherplatz-Anzeige (MB)
6. Leerer Zustand behandelt
7. Build kompiliert fehlerfrei
8. Handover_T47.md mit Dateiliste

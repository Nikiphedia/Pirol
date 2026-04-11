# Arbeitspaket T54: Integration T53 + T46 + T50

## Ziel

Die Outputs von drei parallel gelaufenen Workern (T53, T46, T50) auf einem sauberen master zusammenfuehren. Merge-Konflikte loesen, zwei Pflicht-Fixes einbauen, Build verifizieren.

## Kontext

- **Projekt-Root:** `C:\eta_Data\80002\PIROL`
- **Aktueller master-Commit:** `c87d55a` (T44+T48+T51, Welle 1)
- **Uncommitted auf master:** T45+T49 Aenderungen (6 Dateien) — muessen VOR dem Merge committed werden
- **Handovers:** `Handover_T53.md`, `Handover_T46.md`, `Handover_T50.md` im Projekt-Root
- **Konventionen:** `CLAUDE.md` im Projekt-Root
- **Kein Agent-Dispatch** — du arbeitest direkt auf master

### Ausgangslage

Auf master liegen uncommitted Aenderungen aus T45+T49 (Welle 2). Zusaetzlich haben drei Worker in separaten Kontexten (nicht in Worktrees) ihre Aenderungen als Code-Beschreibungen in Handover-Dateien dokumentiert. Die Dateien muessen manuell zusammengefuehrt werden.

---

## Schritt 1: Uncommitted T45+T49 committen

Zuerst den bestehenden uncommitted Code committen:

```bash
git add app/src/main/kotlin/ch/etasystems/pirol/data/AppPreferences.kt
git add app/src/main/kotlin/ch/etasystems/pirol/data/SecurePreferences.kt
git add app/src/main/kotlin/ch/etasystems/pirol/di/AppModule.kt
git add app/src/main/kotlin/ch/etasystems/pirol/ui/map/MapScreen.kt
git add app/src/main/kotlin/ch/etasystems/pirol/ui/map/TileSourceConfig.kt
git add app/src/main/kotlin/ch/etasystems/pirol/ui/settings/SettingsScreen.kt
```

Commit-Message:
```
T45+T49: Tile-Sources + Xeno-Canto API Client

- MapScreen: FilterChips fuer OSM/swisstopo Tile-Source-Auswahl (T45)
- TileSourceConfig: Enum MapTileSource + PirolTileSourceFactory (T45)
- AppPreferences: mapTileSourceId Persistierung (T45)
- SecurePreferences: swisstopoApiKey (T45)
- SettingsScreen: swisstopo API-Key Eingabe maskiert (T45)
- XenoCantoClient: Ktor API v3 Client (T49)
- XenoCantoModels: @Serializable Response/Recording (T49)
- AppModule: XenoCantoClient Koin-Singleton (T49)
```

`master-briefing.md` Aenderungen NICHT committen (werden spaeter aktualisiert).

---

## Schritt 2: T53 Aenderungen anwenden

Handover-Dateiliste (14 Dateien):

| Datei | T53-Aenderung | Konflikt mit T45/T49? |
|-------|--------------|----------------------|
| `ml/WatchlistEntry.kt` | Enum SCREAMING_SNAKE + @SerialName | Nein |
| `audio/AlarmService.kt` | Enum-Referenzen HIGH/NORMAL/LOW | Nein |
| `ui/settings/SettingsScreen.kt` | Enum-Referenzen HIGH/NORMAL/LOW | **Ja** — T45 hat swisstopo-Key hinzugefuegt |
| `ui/navigation/Screen.kt` | Label "Einstellungen" | Nein |
| `ui/reference/ReferenceScreen.kt` | Dead Code getSpeciesCount() entfernt | **Ja** — T50 fuegt XC-UI hinzu |
| `ml/ChunkAccumulator.kt` | overlapMs Parameter entfernt | Nein |
| `audio/AudioPlayer.kt` | release() entfernt | **Ja** — T50 fuegt playFromUrl() hinzu |
| `ml/InferenceWorker.kt` | scientificName Normalisierung | Nein |
| `data/api/XenoCantoModels.kt` | scientificName Normalisierung | Nein |
| `audio/dsp/AudioDspUtils.kt` | sampleRate Default entfernt | Nein |
| `di/AppModule.kt` | OboeAudioEngine Singleton entfernt, Kommentar StorageManager | **Ja** — T46+T50 aendern auch AppModule |
| `ui/live/LiveViewModel.kt` | runBlocking entfernt, withTimeout in onCleared | **Ja** — T50 renamed wavFileName |
| `PirolApp.kt` | SpeciesNameResolver async laden | Nein |
| `gradle/libs.versions.toml` | Kommentar bei serialization-json | Nein |

### Merge-Strategie fuer Konflikte

**SettingsScreen.kt:**
- T45 hat swisstopo-Key-UI unten angefuegt — bleibt
- T53 aendert WatchlistPriority-Referenzen (`.high/.normal/.low` → `.HIGH/.NORMAL/.LOW`) — beide Aenderungen sind in verschiedenen Code-Bereichen, kein echter Konflikt

**ReferenceScreen.kt:**
- T53 entfernt `getSpeciesCount()` Wrapper-Funktion — Aufrufer direkt auf `viewModel.getSpeciesCount()` umstellen
- T50 fuegt XC-Such-UI hinzu und aendert Konstruktor
- Strategie: Zuerst T53 (Dead Code weg), dann T50 (neuer Code oben drauf)

**AudioPlayer.kt:**
- T53 entfernt `release()`
- T50 fuegt `playFromUrl(url: String)` hinzu
- Strategie: Beide anwenden — release() weg, playFromUrl() rein

**AppModule.kt:**
- T53 entfernt OboeAudioEngine Singleton + Kommentar StorageManager
- T46 fuegt TileDownloadManager Singleton + MapViewModel 2 Params hinzu
- T50 fuegt ReferenceDownloader Singleton + ReferenceViewModel 4 Params hinzu
- Strategie: Alle drei zusammenfuehren in einer Datei

**LiveViewModel.kt:**
- T53 entfernt runBlocking (Zeile ~228), fuegt withTimeout in onCleared
- T50 renamed wavFileName → audioFileName
- Strategie: Beide anwenden — verschiedene Stellen, kein Inhaltlicher Konflikt

---

## Schritt 3: T46 Aenderungen anwenden

| Datei | T46-Aenderung | Konflikt? |
|-------|--------------|-----------|
| `ui/map/TileDownloadManager.kt` | **Neue Datei** | Nein |
| `ui/map/MapViewModel.kt` | Konstruktor + State + Methoden | Nein (nach T53) |
| `ui/map/MapScreen.kt` | FAB + BottomSheet + SnackbarHost | **Ja** — T45 uncommitted hat FilterChips |
| `di/AppModule.kt` | TileDownloadManager + MapViewModel(get(), get()) | Zusammenfuehren mit T53+T50 |

**MapScreen.kt:**
- T45 (committed in Schritt 1) hat FilterChips + TileSource-Wechsel
- T46 fuegt FAB, BottomSheet, SnackbarHost, Tile-Cache-Pfad hinzu
- Strategie: T46 baut auf T45 auf — Code oberhalb der MapView ist T45, FAB/BottomSheet von T46 dazu

---

## Schritt 4: T50 Aenderungen anwenden

| Datei | T50-Aenderung | Konflikt? |
|-------|--------------|-----------|
| `data/repository/ReferenceDownloader.kt` | **Neue Datei** | Nein |
| `data/repository/ReferenceEntry.kt` | Feld-Rename + neue Felder | Nein |
| `data/repository/ReferenceRepository.kt` | getAudioFile, addFromXenoCanto | Nein |
| `audio/AudioPlayer.kt` | playFromUrl | Zusammen mit T53 |
| `ui/reference/ReferenceUiState.kt` | XC-Felder | Nein |
| `ui/reference/ReferenceViewModel.kt` | XC-Methoden, 4 Params | Nein |
| `ui/reference/ReferenceScreen.kt` | XC-Such-UI | Zusammen mit T53 |
| `di/AppModule.kt` | ReferenceDownloader + ViewModel | Zusammen mit T53+T46 |
| `ui/analysis/AnalysisViewModel.kt` | getWavFile → getAudioFile | Nein |
| `ui/analysis/AnalysisScreen.kt` | wavFileName → audioFileName | Nein |
| `ui/live/LiveViewModel.kt` | wavFileName → audioFileName | Zusammen mit T53 |

---

## Schritt 5: Pflicht-Fixes

### Fix A — Deserialisierung ReferenceEntry (kritisch)

**Problem:** T50 renamed `wavFileName` → `audioFileName` ohne Default-Wert. Bestehende `index.json` hat `wavFileName` — Deserialisierung crasht.

**Fix:** In `ReferenceEntry.kt` einen Default-Wert setzen:
```kotlin
val audioFileName: String = ""
```

Und optional `@SerialName` oder eine Alternative-Key-Annotation fuer Rueckwaertskompatibilitaet. Oder: kotlinx-serialization mit `@SerialName("wavFileName")` als Alias auf `audioFileName` — **ACHTUNG:** `@SerialName` ist kein Alias, es ersetzt den Namen. Stattdessen:
```kotlin
// Rueckwaerts-kompatibel: Default "" fuer alte Eintraege ohne audioFileName
val audioFileName: String = "",
```

Zusaetzlich pruefen: Werden irgendwo Eintraege mit leerem `audioFileName` fehlerhaft verarbeitet? In `getAudioFile()` einen Guard einbauen:
```kotlin
fun getAudioFile(entry: ReferenceEntry): File? {
    if (entry.audioFileName.isBlank()) return null
    // ... bestehende Logik
}
```

### Fix B — removeSuffix in AnalysisScreen (minor)

**Problem:** `.removeSuffix(".wav")` zeigt `.mp3` fuer XC-Downloads an.

**Fix:** In `AnalysisScreen.kt`:
```kotlin
// Statt:
ref.audioFileName.removeSuffix(".wav")
// Neu:
ref.audioFileName.substringBeforeLast('.')
```

---

## Schritt 6: AppModule.kt Zusammenfuehrung

Die finale `AppModule.kt` muss enthalten:

```
// T53: OboeAudioEngine ENTFERNT (war Dead Code)
// T53: StorageManager Kommentar praezisiert
// T46: TileDownloadManager als Singleton
// T46: MapViewModel(get(), get()) — 2 Parameter
// T50: ReferenceDownloader als Singleton
// T50: ReferenceViewModel(get(), get(), get(), get()) — 4 Parameter
// T49: XenoCantoClient als Singleton (bereits auf master nach Schritt 1)
```

---

## Schritt 7: Build verifizieren

```bash
./gradlew compileDebugKotlin
```

Falls Android SDK fehlt: `local.properties` anpassen (siehe T55). Falls SDK nicht verfuegbar: alle Aenderungen committen und im Handover dokumentieren.

---

## Schritt 8: Commit

Commit-Message:
```
T53+T46+T50: Integration — Scan-Fixes, Offline-Karten, Xeno-Canto Download

- T53: 13 Scan-Fixes (Enum SCREAMING_SNAKE, runBlocking entfernt, OboeAudioEngine Koin Dead-Singleton, Dead Code, scientificName Normalisierung, withTimeout)
- T46: Offline-Tile-Download (TileDownloadManager, FAB, BottomSheet, Fortschritt, downloads.json)
- T50: Xeno-Canto Download (ReferenceDownloader, Such-UI, Streaming-Playback, ReferenceEntry erweitert)
- Integration: Merge-Konflikte in AppModule/AudioPlayer/ReferenceScreen/LiveViewModel/MapScreen geloest
- Fix: ReferenceEntry.audioFileName Default-Wert fuer Rueckwaertskompatibilitaet
- Fix: AnalysisScreen removeSuffix fuer MP3-Dateien
```

---

## Scope-Ausschluss

- Keine neuen Features ueber die drei Handovers hinaus
- Keine Architektur-Aenderungen
- Stale Worktrees NICHT aufraeumen (Master macht das separat)
- `master-briefing.md` NICHT aktualisieren (Master-Aufgabe)

## Testanforderung

- `./gradlew compileDebugKotlin` — BUILD SUCCESSFUL (oder dokumentieren warum nicht)
- Grep nach `wavFileName` — 0 Treffer
- Grep nach `runBlocking` im ViewModel-Code — 0 Treffer
- Grep nach `OboeAudioEngine` in `AppModule.kt` — 0 Treffer
- Grep nach `getSpeciesCount` als private fun in ReferenceScreen — 0 Treffer

## Acceptance Criteria

1. Alle Dateien aus T53, T46, T50 sind auf master zusammengefuehrt
2. Kein Kompilierfehler (Build gruen oder SDK-Problem dokumentiert)
3. Pflicht-Fix A (audioFileName Default) eingebaut
4. Pflicht-Fix B (removeSuffix MP3) eingebaut
5. AppModule.kt enthaelt alle Koin-Registrierungen aus T46+T50, OHNE OboeAudioEngine (T53)
6. Ein sauberer Commit mit allen Aenderungen
7. Handover_T54.md mit Dateiliste und Merge-Entscheidungen

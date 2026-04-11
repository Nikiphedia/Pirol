# Arbeitspaket T56: Smoketest P20–P24

## Ziel

Vollstaendiger Smoketest aller neuen Features seit dem letzten Feldtest (2026-04-06). Kein Code aendern — nur testen, dokumentieren, Fehler auflisten.

## Kontext

- **Projekt-Root:** `C:\eta_Data\80002\PIROL`
- **Letzter Smoketest:** T25a (2026-04-05, ADB-only)
- **Letzter Feldtest:** 2026-04-06 (P1–P16)
- **Seither hinzugekommen:** P17–P24 (T32–T55), ca. 30 Dateien geaendert/neu
- **Build:** `./gradlew compileDebugKotlin` BUILD SUCCESSFUL (T47)

---

## Teil 1: Build-Verifikation

```bash
# 1. Clean Build
./gradlew clean assembleDebug

# 2. APK-Groesse pruefen (sollte <50MB ohne Modell)
ls -la app/build/outputs/apk/debug/app-debug.apk
```

Ergebnis dokumentieren: Build-Zeit, APK-Groesse, Warnungen.

---

## Teil 2: Statische Analyse (ohne Geraet)

### 2.1 Import-Konsistenz

Alle `.kt`-Dateien pruefen — keine unaufgeloesten Imports:
```bash
./gradlew compileDebugKotlin 2>&1 | grep -i "error\|unresolved"
```

### 2.2 Koin-Graph

Manuell pruefen ob alle Koin-Registrierungen in `AppModule.kt` konsistent sind:
- Jedes `single {}` / `factory {}` hat korrekte Parameter
- Jedes `viewModel {}` hat korrekte Anzahl `get()` Aufrufe
- Keine Zirkulaeren Abhaengigkeiten

Aktuelle erwartete Koin-Singletons:
- BirdNetV3Classifier (als AudioClassifier)
- RegionalSpeciesFilter
- MfccExtractor
- EmbeddingExtractor
- EmbeddingDatabase
- DetectionListState
- LocationProvider
- StorageManager
- SessionManager
- UploadManager
- ReferenceRepository
- AudioPlayer
- WatchlistManager
- AlarmService
- AppPreferences
- SecurePreferences
- XenoCantoClient
- SpeciesNameResolver
- ModelManager
- TileDownloadManager
- ReferenceDownloader

ViewModels:
- LiveViewModel (13 Parameter)
- ReferenceViewModel (4 Parameter)
- AnalysisViewModel (3 Parameter)
- MapViewModel (2 Parameter)

### 2.3 T53 Scan-Fixes verifizieren

```bash
# runBlocking im ViewModel-Code — muss 0 sein
grep -rn "runBlocking" app/src/main/kotlin/ch/etasystems/pirol/ui/

# OboeAudioEngine in AppModule — muss 0 sein
grep -n "OboeAudioEngine" app/src/main/kotlin/ch/etasystems/pirol/di/AppModule.kt

# Alte Enum-Werte — muss 0 sein
grep -rn "WatchlistPriority\.high\|WatchlistPriority\.normal\|WatchlistPriority\.low" app/src/main/kotlin/

# wavFileName — muss 0 sein
grep -rn "wavFileName\|getWavFile" app/src/main/kotlin/

# Tab-Label "Settings" — muss 0 sein (nur "Einstellungen")
grep -n '"Settings"' app/src/main/kotlin/ch/etasystems/pirol/ui/navigation/Screen.kt
```

### 2.4 Neue Dateien vorhanden

Pruefen ob alle erwarteten neuen Dateien existieren:
```
app/src/main/kotlin/ch/etasystems/pirol/data/api/XenoCantoClient.kt
app/src/main/kotlin/ch/etasystems/pirol/data/api/XenoCantoModels.kt
app/src/main/kotlin/ch/etasystems/pirol/data/SecurePreferences.kt
app/src/main/kotlin/ch/etasystems/pirol/data/repository/ReferenceDownloader.kt
app/src/main/kotlin/ch/etasystems/pirol/ui/map/TileSourceConfig.kt
app/src/main/kotlin/ch/etasystems/pirol/ui/map/TileDownloadManager.kt
```

---

## Teil 3: Funktionale Pruefung (ADB / Emulator falls verfuegbar)

Falls ein Geraet oder Emulator angeschlossen ist (`adb devices`):

### 3.1 App-Start

```bash
./gradlew installDebug
adb shell am start -n ch.etasystems.pirol/.MainActivity
```

- App startet ohne Crash?
- Onboarding wird angezeigt (bei Erststart)?
- Kein ANR (runBlocking wurde entfernt)?

### 3.2 Tab-Navigation

Alle 5 Tabs antippen:
- Live → Sonogramm sichtbar?
- Analyse → Session-Browser laedt?
- Referenzen → Artenliste oder "Keine Referenzen"?
- Karte → Karte wird gerendert? FilterChips sichtbar?
- Einstellungen → Tab-Label korrekt "Einstellungen"?

### 3.3 Karten-Features (T45–T47)

- FilterChips: OSM / swisstopo Landeskarte / swisstopo Luftbild — Karte wechselt?
- Download-FAB: Bottom Sheet oeffnet? Zoom-Slider funktioniert? Tile-Schaetzung angezeigt?
- Management-FAB (Storage-Icon): Sheet oeffnet? "Keine offline Karten" bei leerem Cache?
- Falls Download gestartet: Fortschrittsanzeige? Cancel funktioniert?

### 3.4 Xeno-Canto (T49–T50)

- Referenzen-Tab → "Xeno-Canto durchsuchen" Button sichtbar? (Nur mit API-Key)
- Settings → Xeno-Canto API-Key eingeben → Speichern
- Settings → swisstopo API-Key Feld sichtbar? Maskiert?
- Referenzen → XC-Suche → Ergebnisse? Streaming-Playback? Download?

### 3.5 Detektions-UX V2 (T44)

- SpeciesCard: Fragezeichen-Button sichtbar?
- VerificationStatus UNCERTAIN/REPLACED vorhanden?

### 3.6 Autocomplete (T51)

- Korrektur-Dialog: Tippen → Dropdown mit Filterung?

### 3.7 Crash-Check

```bash
adb logcat -d | grep -i "FATAL\|crash\|ANR" | grep pirol
```

---

## Ergebnis-Format

Datei `Smoketest_T56.md` im Projekt-Root:

```
# Smoketest T56 — [Datum]

## Build
- Clean Build: [SUCCESSFUL/FAILED] ([Zeit])
- APK-Groesse: [X MB]
- Warnungen: [Anzahl]

## Statische Analyse
| Check | Ergebnis | Details |
|-------|----------|---------|
| Import-Konsistenz | OK/FAIL | ... |
| Koin-Graph | OK/FAIL | ... |
| T53 Grep-Checks | OK/FAIL | ... |
| Neue Dateien | OK/FAIL | ... |

## Funktionale Tests (falls Geraet vorhanden)
| Test | Ergebnis | Details |
|------|----------|---------|
| App-Start | OK/FAIL/SKIP | ... |
| Tab-Navigation | OK/FAIL/SKIP | ... |
| Karten-Features | OK/FAIL/SKIP | ... |
| Xeno-Canto | OK/FAIL/SKIP | ... |
| Detektions-UX V2 | OK/FAIL/SKIP | ... |
| Autocomplete | OK/FAIL/SKIP | ... |
| Crash-Check | OK/FAIL/SKIP | ... |

## Funde
| # | Schweregrad | Beschreibung | Betroffener Task |
|---|-------------|-------------|-----------------|
| 1 | ... | ... | ... |

## Fazit
[Zusammenfassung: Anzahl Tests, bestanden, fehlgeschlagen, uebersprungen]
```

---

## Scope-Ausschluss

- **Nichts fixen** — nur dokumentieren
- Kein ML-Test (BirdNET-Modell muss manuell platziert werden)
- Kein GPS-Test (braucht echtes Geraet im Freien)
- Kein Upload-Test (braucht Server)

## Acceptance Criteria

1. `./gradlew clean assembleDebug` BUILD SUCCESSFUL
2. Alle statischen Checks (Teil 2) durchgefuehrt und dokumentiert
3. Funktionale Tests durchgefuehrt oder begruendet uebersprungen (kein Geraet)
4. `Smoketest_T56.md` existiert im Projekt-Root
5. Kein Quellcode veraendert

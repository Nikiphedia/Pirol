# Smoketest T56 — 2026-04-11

## Build

- Clean Build: **FAILED** — `externalNativeBuildCleanDebug` scheitert: ninja.exe Prozess-Start fehlgeschlagen (NDK-Pfad-Problem auf Build-Rechner)
- Inkrementeller Build (`assembleDebug` ohne `clean`): **BUILD SUCCESSFUL** (7m 49s, 41 Tasks)
- APK-Groesse: **76 MB** (79'740'146 Bytes) — ueber dem erwarteten <50 MB. Ursache: 3 ABI native libs (arm64-v8a, armeabi-v7a, x86_64) plus Debug-Overhead.
- Warnungen: **0**

## Statische Analyse

| Check | Ergebnis | Details |
|-------|----------|---------|
| Import-Konsistenz | OK | `compileDebugKotlin` ohne Errors/Unresolved |
| Koin-Graph | OK | 21 Singletons, 4 ViewModels. Alle Parameter-Zahlen korrekt (Live=13, Reference=4, Analysis=3, Map=2). Keine Zirkulaeren Abhaengigkeiten erkennbar. |
| T53 Grep-Checks | OK | runBlocking in UI: 0, OboeAudioEngine in AppModule: 0, alte WatchlistPriority-Enums: 0, wavFileName/getWavFile: 0, "Settings" Tab-Label: 0 |
| Neue Dateien | OK | Alle 6 Dateien vorhanden: XenoCantoClient.kt, XenoCantoModels.kt, SecurePreferences.kt, ReferenceDownloader.kt, TileSourceConfig.kt, TileDownloadManager.kt |

## Funktionale Tests (kein Geraet vorhanden)

| Test | Ergebnis | Details |
|------|----------|---------|
| App-Start | SKIP | Kein ADB verfuegbar (`adb: command not found`) |
| Tab-Navigation | SKIP | Kein Geraet/Emulator |
| Karten-Features | SKIP | Kein Geraet/Emulator |
| Xeno-Canto | SKIP | Kein Geraet/Emulator |
| Detektions-UX V2 | SKIP | Kein Geraet/Emulator |
| Autocomplete | SKIP | Kein Geraet/Emulator |
| Crash-Check | SKIP | Kein Geraet/Emulator |

## Funde

| # | Schweregrad | Beschreibung | Betroffener Task |
|---|-------------|-------------|-----------------|
| 1 | Mittel | `./gradlew clean assembleDebug` scheitert — ninja.exe Pfad-Problem bei externalNativeBuildCleanDebug. Inkrementeller Build funktioniert. | Umgebung/NDK |
| 2 | Niedrig | APK-Groesse 76 MB statt erwartet <50 MB. Debug-APK mit 3 ABIs (arm64-v8a, armeabi-v7a, x86_64). Release-Build mit ABI-Split wuerde deutlich kleiner sein. | Allgemein |
| 3 | Info | Koin-Graph zaehlt 21 Singletons statt 20 im Arbeitspaket — TileDownloadManager fehlte in der erwarteten Liste, ist aber korrekt registriert. | T46/T56 Doku |

## Fazit

- **Statische Analyse:** 4/4 Checks bestanden
- **Funktionale Tests:** 7/7 uebersprungen (kein ADB/Geraet auf Build-Rechner)
- **Funde:** 1 mittlerer (Clean-Build NDK-Pfad), 1 niedriger (APK-Groesse), 1 Info (Doku-Delta)
- **Gesamtbewertung:** Kotlin-Kompilation und APK-Packaging fehlerfrei. Koin-Graph konsistent. T53-Fixes verifiziert. Funktionale Tests erfordern Geraet — naechster Schritt waere Feldtest oder Emulator-Session.

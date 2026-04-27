# Release-Prompt: PIROL V0.0.3 Build + GitHub Push

Du bist ein Worker. Fuehre folgende Schritte aus.

## Kontext

PIROL ist eine Android-App (Kotlin/Compose). Das Arbeitsverzeichnis ist `D:\80002\PIROL`.
Es enthaelt neben dem Source auch Task-Dateien, Handover-Dateien und Scan-Reports
die NICHT ins Repository gehoeren.

## Ziel

1. Git-Repository aufraemen (nur relevante Dateien committen)
2. Tag `v0.0.3` setzen
3. Auf GitHub pushen

## Schritt 1: Status pruefen

```powershell
cd D:\80002\PIROL
git status
```

## Schritt 2: .gitignore pruefen / ergaenzen

Stelle sicher dass `.gitignore` diese Eintraege enthaelt (falls nicht, ergaenzen):

```
# Task-/Handover-/Scan-Dateien (Master-Worker-Artefakte)
T*_task.md
T*_prompt.md
Handover_T*.md
Scan_T*.md

# IDE / Build
.idea/
.gradle/
build/
app/build/
local.properties
*.iml

# OS
Thumbs.db
.DS_Store
```

## Schritt 3: Relevante Dateien stagen

NUR Source, Konfiguration und Projekt-Dokumentation:

```powershell
git add app/src/
git add app/build.gradle.kts
git add build.gradle.kts
git add settings.gradle.kts
git add gradle/
git add gradlew
git add gradlew.bat
git add gradle.properties
git add .gitignore
git add CLAUDE.md
git add master-briefing.md
git add PROJEKT_UEBERBLICK.md
git add Pirol_Pull_V0.0.3.md
git add app/proguard-rules.pro
```

Falls `app/src/main/res/` oder `app/src/main/AndroidManifest.xml` existieren:
```powershell
git add app/src/main/res/
git add app/src/main/AndroidManifest.xml
```

## Schritt 4: Pruefen was gestagt ist

```powershell
git diff --cached --stat
```

Verifiziere: Keine T*_task.md, Handover_T*.md, Scan_T*.md, T*_prompt.md Dateien dabei.

## Schritt 5: Commit + Tag

```powershell
git commit -m "PIROL V0.0.3: P1-P19 komplett (T1-T43), Feldtest bestanden"
git tag -a v0.0.3 -m "V0.0.3 - Audio-Pipeline, Storage, Code-Scan, 43 Tasks abgeschlossen"
```

## Schritt 6: Push

```powershell
git push origin main
git push origin v0.0.3
```

Falls der Branch nicht `main` heisst: `git branch --show-current` pruefen.

## Schritt 7: Verify

```powershell
git log --oneline -3
git tag -l
```

## Kurzanleitung PIROL V0.0.3

### Klonen + Bauen

```powershell
git clone https://github.com/DEIN-USER/PIROL.git
cd PIROL
.\gradlew assembleDebug
```

APK liegt in: `app\build\outputs\apk\debug\app-debug.apk`

### Auf Device installieren

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

### Voraussetzungen

- Android Studio / JDK 21 (`JAVA_HOME` gesetzt)
- Android SDK (API 34+)
- ADB fuer Device-Deploy
- BirdNET-Modell: muss manuell in `assets/models/birdnet_v3.onnx` platziert oder ueber Onboarding heruntergeladen werden

### Was ist in V0.0.3

- Echtzeit-Artenerkennung (BirdNET V3 ONNX)
- 48kHz/96kHz Audio-Pipeline mit Hochpassfilter + Normalisierung
- Preroll-Puffer (5s/10s/30s)
- Session-Aufzeichnung (WAV + JSONL)
- Speicherort waehlbar (Intern/SD) mit Session-Migration
- KML-Export + Share
- Referenzbibliothek mit Embedding-Vergleich
- Analyse-Tab mit Session-Browser + Dual-Sonogramm
- Karte (osmdroid)
- Mehrsprachige Artnamen (23 Sprachen)
- Energiesparmodus (FP16, Inference-Intervall)
- Modell-Management (Download, Import, Umschaltung)
- Species-Alarm mit Watchlist
- Feldtest bestanden (2026-04-06)

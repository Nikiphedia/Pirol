# Arbeitspaket T55: Android SDK einrichten

## Ziel

Android SDK auf dieser Maschine installieren und konfigurieren, sodass `./gradlew compileDebugKotlin` im PIROL-Projekt erfolgreich durchlaeuft.

## Kontext

- **Maschine:** Windows 10 Pro 10.0.19045, User `Nikiphedia`
- **Projekt-Root:** `C:\eta_Data\80002\PIROL`
- **Java:** Temurin JDK 21.0.10 (via Scoop) — `C:\Users\Nikiphedia\scoop\apps\temurin21-jdk\current\bin\java.exe`
- **Paketmanager:** Scoop (installiert, funktionsfaehig)
- **Android Studio:** Installiert unter `C:\Program Files\Android\Android Studio\` (hat JBR, aber kein SDK)
- **Aktuelles Problem:** `local.properties` verweist auf `C:\Users\nleis\AppData\Local\Android\Sdk` — Pfad existiert nicht
- **ANDROID_HOME / ANDROID_SDK_ROOT:** Nicht gesetzt
- **Gradle:** 8.11.1 (Wrapper im Projekt)

### Benoetigte SDK-Komponenten (aus build.gradle.kts / CLAUDE.md)

| Komponente | Version | Hinweis |
|------------|---------|---------|
| Compile SDK | 35 | Android 15 |
| Min SDK | 26 | Android 8.0 |
| Target SDK | 35 | |
| Build Tools | passend zu AGP 8.7.3 | Wird automatisch gewaehlt |
| NDK | 27.0.12077973 | Fuer Oboe C++ JNI |
| CMake | 3.22.1 | Fuer NDK-Build |
| ABI Filters | arm64-v8a, armeabi-v7a, x86_64 | |
| Platform Tools | aktuell | adb, etc. |

---

## Option A: SDK via Android Studio (empfohlen)

Android Studio ist bereits installiert. SDK Manager oeffnen und installieren:

1. Android Studio starten
2. Tools → SDK Manager (oder Einstellungs-Icon auf Welcome Screen)
3. SDK Platforms Tab:
   - Android 15 (API 35) installieren
4. SDK Tools Tab:
   - Android SDK Build-Tools (aktuell)
   - NDK 27.0.12077973 (exakte Version!)
   - CMake 3.22.1
   - Android SDK Command-line Tools (latest)
   - Android SDK Platform-Tools
5. SDK-Pfad notieren (Standard: `C:\Users\Nikiphedia\AppData\Local\Android\Sdk`)

---

## Option B: SDK via Command Line (ohne Studio-GUI)

Falls Android Studio SDK Manager nicht gewuenscht:

### Schritt 1: Command-line Tools herunterladen

```powershell
# Zielverzeichnis erstellen
mkdir C:\Android\Sdk\cmdline-tools\latest -Force

# Download (neueste Version)
Invoke-WebRequest -Uri "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip" -OutFile "$env:TEMP\cmdline-tools.zip"

# Entpacken
Expand-Archive -Path "$env:TEMP\cmdline-tools.zip" -DestinationPath "$env:TEMP\cmdline-tools-tmp"

# In richtige Verzeichnisstruktur verschieben
Copy-Item -Path "$env:TEMP\cmdline-tools-tmp\cmdline-tools\*" -Destination "C:\Android\Sdk\cmdline-tools\latest" -Recurse
```

### Schritt 2: Umgebungsvariablen setzen

```powershell
[Environment]::SetEnvironmentVariable("ANDROID_HOME", "C:\Android\Sdk", "User")
$env:ANDROID_HOME = "C:\Android\Sdk"
$env:Path += ";C:\Android\Sdk\cmdline-tools\latest\bin;C:\Android\Sdk\platform-tools"
```

### Schritt 3: SDK-Komponenten installieren

```bash
sdkmanager --sdk_root=C:\Android\Sdk "platforms;android-35"
sdkmanager --sdk_root=C:\Android\Sdk "build-tools;35.0.0"
sdkmanager --sdk_root=C:\Android\Sdk "platform-tools"
sdkmanager --sdk_root=C:\Android\Sdk "ndk;27.0.12077973"
sdkmanager --sdk_root=C:\Android\Sdk "cmake;3.22.1"
```

Lizenzen akzeptieren:
```bash
sdkmanager --sdk_root=C:\Android\Sdk --licenses
```

---

## Schritt (beide Optionen): local.properties anpassen

```properties
sdk.dir=C\:\\Users\\Nikiphedia\\AppData\\Local\\Android\\Sdk
```

Oder falls Option B mit `C:\Android\Sdk`:
```properties
sdk.dir=C\:\\Android\\Sdk
```

**Wichtig:** Doppelte Backslashes + Escaped Doppelpunkt (`\:`) in Properties-Syntax.

---

## Schritt (beide Optionen): Build verifizieren

```bash
cd C:\eta_Data\80002\PIROL
./gradlew compileDebugKotlin
```

Erwartetes Ergebnis: `BUILD SUCCESSFUL`

Falls NDK-Fehler:
- Pruefen ob NDK-Version exakt `27.0.12077973` ist (nicht 27.1 oder 27.2)
- In `app/build.gradle.kts` steht `ndkVersion = "27.0.12077973"`

Falls CMake-Fehler:
- Pruefen ob CMake 3.22.1 installiert ist (Scoop hat CMake 4.2.3 — das ist das System-CMake, nicht das Android SDK CMake)
- Android SDK CMake muss ueber sdkmanager installiert sein

---

## Scope-Ausschluss

- Kein Emulator-Setup
- Kein Device-Deployment (adb)
- Keine Gradle-Aenderungen am Projekt
- Keine IDE-Konfiguration (Android Studio Projekt-Einstellungen)

## Testanforderung

- `./gradlew compileDebugKotlin` — BUILD SUCCESSFUL
- `sdkmanager --list_installed` zeigt alle benoetigten Komponenten

## Acceptance Criteria

1. Android SDK installiert mit API 35, NDK 27.0.12077973, CMake 3.22.1
2. `local.properties` zeigt auf korrekten SDK-Pfad
3. `ANDROID_HOME` Umgebungsvariable gesetzt (optional, aber empfohlen)
4. `./gradlew compileDebugKotlin` laeuft erfolgreich durch
5. Handover_T55.md mit installiertem Pfad und Versions-Uebersicht

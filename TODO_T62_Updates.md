# T62: Dependency-Updates — Build-Checkliste

Branch `claude/t62-dependency-updates` (Basis: `master`, unabhaengig von T61).
**Gebaut und getestet (Cloud, Android-SDK installiert):** `compileDebugKotlin`, `testDebugUnitTest`
(12/12 gruen) und `assembleDebug` erfolgreich. Nur 3 Kotlin-Warnungen (siehe unten). Kein Geraetetest.

## Was geaendert wurde

| Komponente | Alt | Neu |
|------------|-----|-----|
| Gradle Wrapper | 8.11.1 | 9.8.2 (AGP 9.4 braucht >= 9.6) |
| AGP | 8.7.3 | 9.4.0 |
| Kotlin (Compose-/Serialization-Plugin) | 2.1.0 | 2.4.21 |
| Compose BOM | 2025.01.01 | 2026.09.00 |
| kotlinx-coroutines | 1.9.0 | 1.11.0 |
| kotlinx-serialization-json | 1.7.3 | 1.12.0 |
| Ktor | 3.0.3 | 3.6.0 |
| Koin | 4.0.2 | 4.2.2 |
| ONNX Runtime Android | 1.19.0 | 1.31.0 |
| activity-compose | 1.10.0 | 1.13.0 |
| lifecycle | 2.8.7 | 2.11.0 |
| navigation-compose | 2.8.6 | 2.10.2 |
| core-ktx | 1.15.0 | 1.19.1 |
| window | 1.3.0 | 1.5.1 |
| work | 2.10.0 | 2.12.0 |
| documentfile | 1.0.1 | 1.1.0 |
| play-services-location | 21.3.0 | 21.4.0 |
| osmdroid | 6.1.18 | 6.1.20 |
| compileSdk | 35 | 37 (navigation 2.10 / Compose 1.12 verlangen >= 37; Plattform `android-37.0`) |

**AGP-9-Migration (Built-in Kotlin):** Plugin `org.jetbrains.kotlin.android` entfernt (Root, App,
Version-Catalog), `kotlinOptions { jvmTarget = "17" }` entfernt — jvmTarget folgt jetzt
`compileOptions.targetCompatibility` (17). Compose- und Serialization-Plugin bleiben.

## Bitte lokal pruefen

1. Android Studio: SDK Platform 37 installieren (SDK-Manager), dann Gradle-Sync
2. Optional Wrapper-Skripte aktualisieren: `./gradlew wrapper --gradle-version 9.8.2`
3. Auf dem Geraet: Live-Aufnahme + BirdNET (ONNX 1.31), GPS, Karte, Upload

## Build-Ergebnis (Cloud)

- Kotlin-Warnungen (nicht blockierend):
  - `ui/components/SpeciesCard.kt:150` — `rememberSaveable(key = ...)` deprecated (Compose warnt vor
    State-Bugs mit eigenem Key) → bei Gelegenheit `key` entfernen
  - `ui/components/SpeciesCard.kt:486` — redundanter `else`-Zweig in exhaustivem `when`
  - `ui/live/LiveScreen.kt:596` — ueberfluessiger Elvis-Operator
- Gradle meldet "Deprecated Gradle features ... incompatible with Gradle 10" (vermutlich aus Plugins,
  Details mit `--warning-mode all`)
- **APK-Groesse Debug: 131 MB (vorher 79 MB)** — Hauptursache `libonnxruntime.so` in 1.31
  (x86_64 41 MB, arm64 34 MB, armv7 24 MB). Im Play-Store-Bundle (AAB) laedt jedes Geraet nur seine ABI;
  fuer Direkt-APKs ggf. ABI-Splits oder x86_64 nur im Debug

## Moegliche Stolpersteine

- **Kotlin-Quellordner:** Code liegt in `src/main/kotlin`. Built-in Kotlin sollte das automatisch
  erkennen; falls "Unresolved reference" auf alles: in `android {}` ergaenzen
  `sourceSets["main"].kotlin.directories += "src/main/kotlin"` (bzw. laut AGP-Fehlermeldung).
- **Koin:** `org.koin.androidx.compose.koinViewModel` ist deprecated (Warnung). Ersatz:
  `org.koin.compose.viewmodel.koinViewModel`.
- **NDK:** bleibt fest auf 27.0.12077973 (AGP-9.4-Default waere 28.2.x). Wenn nicht installiert:
  per SDK-Manager nachinstallieren oder `ndkVersion` anheben.
- **Target SDK 36** bewusst NICHT angehoben (Verhaltensaenderungen: u.a. Edge-to-Edge ohne Opt-out,
  Orientierung/Resizability auf grossen Screens). Eigener Task inkl. UI-Test, Play-Store-Frist beachten.
- Neue Lint-/Deprecation-Warnungen sind erwartbar; nur Fehler sind blockierend.

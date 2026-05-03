# Handover T89 — Karte: Position-Move per Drag + osmdroid-Init in Application

**Branch:** `T89-karte-move-touch`
**Datum:** 2026-05-03

---

## Erledigt

**Aufgabe A — `PirolApp.kt`:**
- `import org.osmdroid.config.Configuration` hinzugefügt
- osmdroid-Init-Block in `onCreate()` vor `startKoin` eingefügt (Cache-Dir, UserAgent, Thread-Limits)

**Aufgabe B — `MapScreen.kt`:**
- `remember(context)`-Block (osmdroid-Config) entfernt — Init liegt jetzt in PirolApp
- `val positionMoveRef` als composable `remember` deklariert (Typ `MapViewModel.MapMarker?`)
- `val inMoveMode = state.positionMoveTarget != null` am Anfang der Box
- `AndroidView.update`: `animateTo` nur beim Eintritt in Move-Modus (`prevMoveTarget == null && moveTarget != null`); Overlay-Render in `if (moveTarget != null) / else` aufgeteilt — Move-Modus zeigt nur orangen Ziel-Marker (80 px)
- Alle UI-Elemente (Dialoge, FAB, Chips, Fokus-Button, Ladeindikator, Leerstate) in `if (!inMoveMode)` gewrappt
- Koordinaten-AlertDialog (45 Zeilen) ersetzt durch Center-Pin-UI:
  - Fadenkreuz `Icons.Filled.Add` (Center, 48 dp, error-Farbe)
  - Abbrechen-`IconButton` (TopStart)
  - Speichern-`IconButton` (TopEnd) → liest `mapView.mapCenter` und ruft `confirmPositionMove`
  - Art-Name `Text` (TopCenter, halbtransparenter Hintergrund)
- Imports bereinigt: `Configuration`, `KeyboardOptions`, `KeyboardType`, `OutlinedTextField`, `setValue`, `LocalContext` entfernt; `IconButton`, `Add`, `Check`, `Close` hinzugefügt

---

## Getestet

```
./gradlew compileDebugKotlin → BUILD SUCCESSFUL
./gradlew assembleDebug      → BUILD SUCCESSFUL
```

Zwei bestehende Deprecation-Warnings (`GestureDetectorCompat`) aus T88 — kein neuer Fehler.

---

## Bewusst nicht gemacht

- Kein `Icons.Filled.GpsFixed` statt `Add` — `Add` ist im Core-Set und zuverlässiger; GpsFixed wäre Alternative aus Extended
- Kein Gerätetest der ACs AC1–AC6 (erfordert physisches Gerät oder Emulator)

---

## Beobachtungen

- `GestureDetectorCompat` ist deprecated (Kotlin-Warning, bereits seit T88) — Kandidat für separaten Cleanup-Task, nicht T89-Scope
- `val context` war nach Entfernen des remember-Blocks vollständig unbenutzt → sauber mitentfernt

---

## Abhängigkeiten

| Was | Details |
|-----|---------|
| `PirolApp.kt` | osmdroid ist ab jetzt zwingend in `Application.onCreate()` initialisiert — nachfolgende Tasks können das voraussetzen |
| `MapScreen.kt` | `positionMoveTarget == null` = Normalmodus; `!= null` = Move-Modus mit eingeschränkter UI und nur Ziel-Marker auf der Karte |
| Neue Dateien | keine |
| Neue Dependencies | keine |
| Neue ENV-Variablen | keine |
| Branch | `T89-karte-move-touch`, KEIN Self-Merge auf master |

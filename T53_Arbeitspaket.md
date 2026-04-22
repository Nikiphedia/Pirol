# T53 — GPS-Robustheit

## Ziel

Koordinaten auf Detektionen sind stabil. Keine `null` auf den ersten Detektionen, keine Ausreisser-Spruenge (0/0 Afrika-Marker), kein Drift. Ungenaue Fixes werden verworfen.

Letzter V0.0.6-Task. Nicht-blockierend fuer den Feldtest am 2026-04-22 (App funktioniert auch ohne — aber wenn drin, bessere Karten-Qualitaet).

---

## Projekt-Kontext

**Branch:** `master`, nach T52-Merge.

Heute:
- `app/src/main/kotlin/ch/etasystems/pirol/location/LocationProvider.kt` — FusedLocation 10 s Intervall, kein Accuracy-Filter, kein Fallback.
- `LiveViewModel` konsumiert `StateFlow<LocationData?>` und schreibt lat/lon auf `DetectionResult`.
- CLAUDE.md: WGS84 Double, GPS nur bei aktiver Session, keine `GlobalScope`.

---

## Scope

### 1. Accuracy-Filter
- `location.accuracy > 50f` (Setting-konfigurierbar, Default 50 m) → Fix verworfen, `lastGoodFix` bleibt.
- Settings-Key `gpsMaxAccuracyMeters: Float`, Default `50f`.

### 2. LastKnownLocation-Fallback
- Bei Session-Start: `fusedLocationClient.lastLocation` holen. Wenn `≤ 2 Minuten alt` und `accuracy ≤ gpsMaxAccuracyMeters` → sofort als `currentLocation` publishen, damit erste Detektionen nicht `null` sind.

### 3. Median-Smoothing
- Rolling-Window **5 Fixes** (akzeptierte only), Median auf lat/lon separat.
- Settings-Toggle `"GPS-Smoothing"`, Key `gpsSmoothingEnabled: Boolean`, Default `true`.

### 4. GPS-Power-Profil
- `PRIORITY_HIGH_ACCURACY` nur waehrend aktiver Session.
- `PRIORITY_BALANCED_POWER_ACCURACY` im Idle.
- `removeLocationUpdates()` definitiv bei Session-Stop — auditieren, ggf. fix.

### 5. GPS-Intervall konfigurierbar
- Settings-Auswahl "GPS-Intervall": 2 s / 5 s / 10 s (default) / 20 s / 60 s.
- Wirkt auf `LocationRequest.setIntervalMillis()` + `setMinUpdateIntervalMillis()` beim naechsten Session-Start.
- Settings-Key `gpsIntervalSeconds: Int`, Default `10`.
- Hilfezeile: *"Kuerzere Intervalle = genauere Position, hoeherer Akkuverbrauch."*

### 6. Session-Metadata
- `SessionMetadata` um `gpsStats: GpsStats?` erweitern:
  ```kotlin
  @Serializable
  data class GpsStats(
      val fixCount: Int,
      val rejectedCount: Int,
      val medianAccuracy: Float,
      val intervalMs: Long
  )
  ```
- Befuellt vom `LocationProvider` / `LiveViewModel`, geschrieben beim `SessionManager.stop()`.
- `@Serializable`, Schema-erweiternd (Feld nullable → bestehende Sessions weiterhin lesbar).

---

## Scope-Ausschluss

- **Kein Kalman-Filter** (Over-Engineering).
- **Keine Offline-Geocoding / Karten-Features** (T58 V0.0.7).
- **Keine osmdroid / MapScreen-Aenderung** — nur Daten-Qualitaet am Entstehungsort.

---

## Acceptance Criteria

1. Erste Detektion hat **nie** `lat/lon == null`, wenn Permission granted und LastKnown-Fix ≤ 2 Min alt + accuracy ok.
2. Fixes `accuracy > 50 m` landen nicht auf `DetectionResult` (Unit-Test mit Fake-Location-Source).
3. Median-Smoothing-Toggle wirkt, verifizierbar via Mock-Location (Sprung → Median zeigt vor Sprung).
4. Nach Session-Stop: `LocationProvider` macht **keine** weiteren Updates (logcat oder Battery Historian).
5. GPS-Intervall 2/5/10/20/60 in Settings umschaltbar, naechste Session respektiert Wert, wird in `session.json.gpsStats.intervalMs` reflektiert.
6. `session.json` enthaelt `gpsStats` nach erfolgreicher Session.
7. Marker auf Karte: Keine 0/0-Marker mehr sichtbar. Bei `null`-lat/lon wird die Detektion im Map-Tab schlicht nicht gerendert (kein Crash, kein Afrika-Sprung).
8. `compileDebugKotlin` + `assembleDebug` gruen.
9. Bestehende Sessions (ohne `gpsStats`) oeffnen sich ohne Crash im Analyse-Tab.

---

## Testplan

### Unit
- `AccuracyFilterTest`: akzeptiert 10/30/50 m, verwirft 51/100 m.
- `MedianSmoothingTest`: 5 Werte inkl. Ausreisser → Median ignoriert Ausreisser.

### Integration (Emulator / Tablet)
- `adb emu geo fix 8.54 47.38` → erste Detektion hat diese Koordinaten.
- Sprung `adb emu geo fix 0 0` → kein Detection-Marker springt auf 0/0.
- 5-Min-Session → `session.json.gpsStats.fixCount > 0`, `rejectedCount` plausibel.
- Permission revoke → Live-Tab zeigt weiterhin ohne Crash, Detektionen haben `null`-Koordinaten, werden auf Karte nicht gerendert.

---

## Normung

- Kotlin 2.1, `callbackFlow` um FusedLocation, kein `GlobalScope`, `viewModelScope` / Service-Scope.
- Double fuer WGS84 (CLAUDE.md).
- Kommentare deutsch.

---

## Handover

`Handover_T53.md` im Projekt-Root mit Dateiliste, AC-Abgleich, Unit-Test-Ergebnis, Manual-Test-Protokoll, `session.json`-Beispiel mit `gpsStats`, offenen Punkten.

---

## Kritische Dateien

- `location/LocationProvider.kt` (Hauptaenderung)
- `ui/live/LiveViewModel.kt` (Konsum + lastGood-Halten + Counter)
- `data/repository/SessionManager.kt` (gpsStats ins session.json)
- `data/repository/SessionMetadata.kt` (neues Feld)
- `data/AppPreferences.kt` (4 neue Keys)
- `ui/settings/SettingsScreen.kt` (neue Sektion GPS)
- `ml/DetectionResult.kt` (nur lesen — keine Schema-Aenderung am Detection-Format)
- `ui/map/MapScreen.kt` / `MapViewModel.kt` (nur Null-Guard fuer Marker-Render)

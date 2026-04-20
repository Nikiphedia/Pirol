# T51b — Timestamp-Leser nachziehen (T51-Regress)

## Ziel

T51 hat das Timestamp-Schreib-Format von UTC-`Z` auf lokale Zeit mit Offset (`+02:00`) umgestellt, aber **drei Lesestellen** wurden nicht mitmigriert. Eine davon (`AnalysisViewModel.openCompare()`) crasht bei **jeder** neuen Session, sobald der User eine Detektion im Compare-Modus (Referenz-Vergleich) oeffnet. Die anderen beiden schreiben fuer Konsistenz noch im alten Format.

Nach T51b parsen alle Leser beide Formate (Offset + Z + nackt, Rueckwaertskompatibel), und alle Schreiber nutzen das neue Format.

**Dies ist ein Hotfix-Task** — minimal-invasiv, kein neuer Scope.

---

## Projekt-Kontext

**Package:** `ch.etasystems.pirol`
**Pfad:** `D:\80002\PIROL`
**Branch-Basis:** `master` (nach T51-Merge)
**Build:** `.\gradlew.bat compileDebugKotlin` / `.\gradlew.bat assembleDebug`

**Hintergrund:** Siehe `Handover_T51.md` §8 — Worker hat den Regress selbst markiert als HOCH-Prioritaet, konnte ihn im T51-Scope aber nicht mehr mitnehmen.

---

## Tablet-Debug-Freigabe

Der Worker darf das ueber USB angeschlossene Tablet nutzen:

```cmd
set PATH=%PATH%;C:\Users\nleis\AppData\Local\Android\Sdk\platform-tools
adb devices
adb logcat -c
adb logcat *:E AndroidRuntime:E AnalysisVM:V > crash.log
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

---

## Scope — drei konkrete Fixes

### Fix 1 — AnalysisViewModel.openCompare() (Crash)

`app/src/main/kotlin/ch/etasystems/pirol/ui/analysis/AnalysisViewModel.kt`, ca. Zeile 174:

```kotlin
val startMs = try {
    java.time.Instant.parse(session.metadata.startedAt).toEpochMilli()
} catch (_: Exception) { return }
```

**Problem:** `Instant.parse()` versteht nur UTC-`Z`-Format. Neue Sessions mit `2026-04-20T14:30:00+02:00` werfen `DateTimeParseException` und der Compare-Modus oeffnet einfach nicht — der User sieht keinen Crash, aber auch nichts passieren.

**Fix:** Beide Formate akzeptieren. Empfehlung:
```kotlin
val startMs = try {
    java.time.OffsetDateTime.parse(session.metadata.startedAt).toInstant().toEpochMilli()
} catch (_: Exception) {
    try {
        java.time.Instant.parse(session.metadata.startedAt).toEpochMilli()
    } catch (_: Exception) { return }
}
```

`OffsetDateTime.parse()` akzeptiert sowohl `+02:00` als auch `Z` (als Offset 0). Fallback fuer ganz alte Sessions ohne Offset.

### Fix 2 — ReferenceRepository Timestamp-Schreiben

`app/src/main/kotlin/ch/etasystems/pirol/data/repository/ReferenceRepository.kt` — alle `Instant.now().toString()` (gibt `...Z`-Format) durch das neue Muster ersetzen:

```kotlin
java.time.ZonedDateTime.now(java.time.ZoneId.systemDefault())
    .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
```

Gleiches Pattern wie in `SessionManager` (bereits von T51 umgesetzt — gerne dort copy-paste). Falls ein Helper in `SessionManager` schon als `companion object`-Funktion existiert, ueberall wiederverwenden statt duplizieren.

### Fix 3 — WatchlistManager Timestamp-Schreiben

Gleiche Behandlung wie Fix 2 fuer `WatchlistManager.kt` (Pfad vermutlich `app/src/main/kotlin/ch/etasystems/pirol/ml/WatchlistManager.kt` oder aehnlich — Worker findet per `grep -r "Instant.now" app/src/main/`).

### Audit

Nach den drei Fixes **einmal sauber auditieren**:

```cmd
grep -rn "Instant.parse\|Instant.now" app\src\main\kotlin\
```

Alle verbleibenden Treffer pruefen und dokumentieren: entweder umgestellt oder bewusst belassen (mit kurzer Begruendung im Handover).

---

## Acceptance Criteria

1. `compileDebugKotlin` + `assembleDebug` gruen.
2. Neue Session aufnehmen (mit mindestens einer Detektion — Playback eines Vogelrufs reicht) → im Analyse-Tab die Session oeffnen → Detektion antippen → Compare-Modus oeffnet sich **ohne Crash**, zeigt Referenzen der Art.
3. Alte Session (Pre-T51, mit `...Z`-Timestamp) im Compare-Modus funktioniert **weiterhin**.
4. Eine neue Referenz wird angelegt oder geaendert → das Timestamp-Feld hat `+02:00`-Offset (per Logcat oder JSON-Dump nachweisbar).
5. `grep -rn "Instant.parse" app\src\main\kotlin\` liefert keine unkommentierten Treffer mehr.
6. Keine neuen Dateien, keine Dependency-Aenderung, keine Schema-Aenderung.

---

## Scope-Ausschluss

- Keine weitere Refactorierung der Timestamp-Pipeline.
- Kein zentraler Timestamp-Helper bauen, wenn es nicht sowieso schon einen gibt (nur wiederverwenden, was da ist).
- Keine UI-Aenderungen.
- Kein Datei-Migrations-Lauf fuer Alt-Daten (Timestamps in alten Dateien bleiben im alten Format — nur der Leser muss es koennen).

---

## Normung

- Kotlin 2.1.0, Deutsch-Kommentare, Englisch-Bezeichner.
- Commit-Format: `T51b: Kurzbeschreibung` + Bullet-Details.
- Keine neuen Dependencies.

---

## Relevante Dateien

- `app/src/main/kotlin/ch/etasystems/pirol/ui/analysis/AnalysisViewModel.kt` (Fix 1)
- `app/src/main/kotlin/ch/etasystems/pirol/data/repository/ReferenceRepository.kt` (Fix 2)
- `app/src/main/kotlin/ch/etasystems/pirol/ml/WatchlistManager.kt` (Fix 3 — Pfad verifizieren)
- `app/src/main/kotlin/ch/etasystems/pirol/data/repository/SessionManager.kt` (Referenz-Pattern fuer Fix 2/3)

---

## Handover

`Handover_T51b.md` im Projekt-Root mit:

1. Was geaendert (Dateiliste, je 1–3 Zeilen).
2. Audit-Ergebnis: welche `Instant.*`-Treffer uebrig, warum.
3. Verifikation: Build gruen, Compare-Modus-Test auf Tablet bestanden.
4. Naechster Schritt: "T51b abgenommen, T56 folgt."

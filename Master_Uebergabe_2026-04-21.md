# Master-Uebergabe — PIROL V0.0.6-WIP

**Datum:** 2026-04-21
**Projekt:** `D:\80002\PIROL` (Branch `master`, letzter Commit `2d3ecfe`)
**Rotations-Grund:** Kontextfenster voll nach T54+T51+T51b+T51c+T56-Dispatch.

---

## 1. Zuerst lesen (in dieser Reihenfolge)

1. `C:\Users\nleis\Documents\Claude Prompts\master-verhalten-prompt.md` — dein Rollen-Prompt.
2. `D:\80002\PIROL\PROJEKT_UEBERBLICK.md` — Projekt-Briefing, Stand `V0.0.6-WIP`.
3. `D:\80002\PIROL\CLAUDE.md` — Konventionen (Tech Stack, Namen, Datenformate, verbotene Patterns).
4. `C:\Users\nleis\.claude\plans\joyful-mixing-starlight.md` — V0.0.6-Plan (T51–T56 + V0.0.7-Ausblick).
5. `D:\80002\PIROL\Handover_T51.md`, `Handover_T51b.md`, `Handover_T51c.md` — was schon gelaufen ist und welche Stellen offen blieben.

`master-briefing.md` existiert nicht — die Rolle erfuellt `PROJEKT_UEBERBLICK.md` (Alias-Entscheidung steht im Plan).

---

## 2. Stand V0.0.6

| Task | Status | Commit |
|------|--------|--------|
| T54 Preroll-Crash | abgenommen | `30ce09b` |
| T51 Storage-Layout + Daueraufnahme + Auto-Raven + KML weg | abgenommen | `a8d8395` `d61b4db` `7cad9f4` `c929f85` `240edce` `0999518` |
| T51b Timestamp-Leser (OffsetDateTime) | abgenommen | `55d7291` |
| T51c LiveViewModel-Fix + T51-Completion-Gaps | abgenommen | `91c2820` |
| **T56 Sonogramm-Dynamik** | **laeuft gerade beim Worker** | AP: `fcb8cc6` (`T56_Arbeitspaket.md`) |
| T52 Live-UX-Haertung | **AP noch NICHT geschrieben** — deine erste Aufgabe nach T56 | — |
| T55 Recording-Start-Stabilitaet | Pruefen ob durch T54 miterledigt | — |
| T53 GPS-Robustheit | letzter V0.0.6-Task | — |

Reihenfolge ist serialisiert (User testet jeden Task auf Tablet per USB). Parallel nichts.

---

## 3. Unmittelbar anstehend

### Schritt 1 — T56-Handover reviewen (sobald vom Worker zurueck)

Wenn `Handover_T56.md` im Projekt-Root liegt:
- Gegen Acceptance Criteria aus `T56_Arbeitspaket.md` pruefen.
- Build gruen? (`.\gradlew.bat compileDebugKotlin` + `assembleDebug`)
- User baut APK per `pirol-deploy.ps1` (siehe unten) und testet auf Tablet.
- Bei Gruen: Commit/Merge nach `master`, `PROJEKT_UEBERBLICK.md` Tabelle aktualisieren (T56 → abgenommen).
- Bei Rot: Nachbesserung im gleichen Worktree, nicht neuer Task.

### Schritt 2 — T52 AP schreiben

Datei: `D:\80002\PIROL\T52_Arbeitspaket.md` (Format analog `T56_Arbeitspaket.md`, `T51b_Arbeitspaket.md`).

**Muss enthalten** — User-UX-Wuensche vom 2026-04-20 (stehen auch in Handover_T51c §5):

1. **Preroll-FAB-Zustand:** FAB blau waehrend Preroll-Buffer sich fuellt, gruen sobald die eigentliche Aufnahme laeuft. Aktuell gibt es `RecordingFabState` mit `IDLE/READY/CONNECTING/RECORDING` in `LiveScreen.kt` (FAB-Code ab ~Zeile 720). Waehrend Buffer-Fuellung ist Status derzeit wahrscheinlich `CONNECTING` oder direkt `RECORDING` — Worker muss das sauber auseinanderziehen.
2. **Analyse-Liste Dauer in mm:ss** (bisher nur Minuten). Kritische Stelle: `ui/components/SessionCard.kt` Zeile ~58-60 (`Duration.between(...).toMinutes()`) + Zeile 102 (`"$durationMin Min"`).
3. **Analyse-Liste Groesse in MB.** Feld existiert aktuell nicht in `SessionSummary` — Worker muss WAV-Dateigroesse beim Laden ermitteln und anzeigen.

**Zusaetzlich Regress-Pendenz aus T51b-Handover §4 (MITTEL):**

4. `SessionCard.kt:47` und `:52`: `Instant.parse(meta.startedAt)` / `Instant.parse(it)` → `OffsetDateTime.parse()` mit `Instant.parse()`-Fallback (gleiches Muster wie T51b/T51c). Crasht nicht, zeigt aber "—" statt Dauer bei neuen `+02:00`-Sessions.

**Standard-T52-Scope aus dem Plan** (joyful-mixing-starlight.md Abschnitt T52):
- Verifikations-Buttons Tap-Target ≥48dp (Feld bevorzugt 56dp), Rand 1dp→2dp, Ripple+Haptik, Snackbar mit Undo (≥5s).
- Status-Zeile: Session-Laufzeit mm:ss live, GPS-Fix-Alter (rot wenn >30s oder null).
- FAB: Doppeltap-Debounce 500ms, State direkt aus `RecordingService.isRunning`-StateFlow.
- GPS-Bar: ±m Genauigkeit, Tap oeffnet Permission falls denied.
- Top-N-Expand: State persistent ueber Re-Sortierung (Key via `DetectionResult.id`), `animateContentSize`.
- **Top-N-Kandidaten in Anzeigesprache** via `SpeciesNameResolver` (aktuell lateinisch) — wissenschaftlicher Name als Subtitle.
- Settings-Toggle "Artvorschlaege anzeigen" (default an), JSONL bleibt vollstaendig.

### Schritt 3 — T52 dispatchen

- Worktree anlegen: `git worktree add .claude/worktrees/v006-t52 master`
- Worker-Prompt: `C:\Users\nleis\Documents\Claude Prompts\worker-verhalten-prompt.md` **nicht aendern** (User-Regel: Worker-Prompt ist heilig).
- Agent-Tool mit `T52_Arbeitspaket.md` + Worker-Prompt-Referenz dispatchen.

---

## 4. Deploy-Workflow (User macht den physischen Teil)

Script liegt im Projekt-Root: `pirol-deploy.ps1`. User ruft auf:

```powershell
cd D:\80002\PIROL
.\pirol-deploy.ps1
```

Macht: `.\gradlew.bat assembleDebug` + `adb install -r app\build\outputs\apk\debug\app-debug.apk`. Tablet haengt per USB, Entwicklermodus an.

**Pro-Task-Loop:**
1. Master dispatcht Worker.
2. Worker liefert Handover.
3. Master reviewt gegen AC.
4. User baut+installiert via Script, testet auf Tablet.
5. Bei Gruen: Commit, Briefing-Update. Bei Rot: Nachbesserung.
6. Naechster Task.

---

## 5. Offene Pendenzen V0.0.6 (aus Handovers)

| Punkt | Quelle | Wann |
|-------|--------|------|
| `SessionCard.kt:47,52` Instant.parse → OffsetDateTime-Fallback | Handover_T51b §4, Handover_T51c §4 | **in T52 einziehen** |
| SAF auf SD-Karte — schlaegt bei manchen Geraeten fehl, Fallback greift | Handover_T51 | Nach V0.0.6 pruefen (User-Feedback) |
| Migration bestehender Sessions (Pre-V0.0.6 filesDir) | Handover_T51 | Dialog existiert, User muss manuell ausloesen |

---

## 6. Nicht delegieren (Master-Hoheit)

- `CLAUDE.md` + `PROJEKT_UEBERBLICK.md` — du aenderst direkt, kein Worker.
- Zeitzone-Konvention (ISO 8601 mit Offset) ist gesetzt — nicht mehr verhandeln.
- Scope-Grenzen: T52 macht keine Sonogramm-Aenderungen (ist T56), keine GPS-Aenderungen (ist T53).
- `BDA_PIROL_V005.md` → `V006.md` **erst nach Abschluss T52** (UX-Aenderungen muessen dokumentiert sein). Vorher nichts anfassen.

---

## 7. Nach V0.0.6 abgeschlossen

- 30-Min-Integrations-Feldtest durch User.
- Tag `v0.0.6`, Push, Release-Note.
- Neue Planungs-Session fuer V0.0.7 (T57 Session-Rotation, T58 Map V2) — **separater Master-Lauf**, nicht in diesem Kontext.

---

## 8. Begruessungs-Satz (nach Lesen von Briefing + CLAUDE.md)

> "Ich habe PROJEKT_UEBERBLICK.md, CLAUDE.md, den Plan und die drei T51-Handovers gelesen. Stand: V0.0.6-WIP, T54+T51+T51b+T51c abgenommen, T56 laeuft beim Worker. Sobald Handover_T56.md da ist, reviewe ich gegen AC. Danach schreibe ich T52_Arbeitspaket.md mit den drei User-UX-Wuenschen (Preroll-FAB blau→gruen, Dauer mm:ss, Groesse MB) + Regress-Fix SessionCard.Instant.parse + Standard-T52-Scope. T55 pruefe ich danach, T53 als letztes."

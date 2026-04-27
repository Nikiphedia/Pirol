# PIROL V0.0.6 — Bedienungsanleitung

Akustische Echtzeit-Artenerkennung fuer Android.

*Version: V006 | Stand: 2026-04-26 (V0.0.6 + Block A, T50–T63)*

---

## Erster Start

1. App oeffnen → Onboarding fuehrt durch Berechtigungen (Mikrofon, GPS, Speicher)
2. BirdNET-Modell herunterladen (Zenodo, ~50 MB) oder manuell per SAF importieren
3. Modellvariante waehlen: FP32 (genauer) oder FP16 (schneller, weniger RAM)
4. Speicherort pruefen: Aufnahmen landen unter `Downloads/PIROL/YYYY-MM-DD/` (SAF-Zugriff, sichtbar per USB)

---

## Live-Erkennung

**Start:** FAB-Button auf dem Live-Tab druecken.

Das Sonogramm laeuft sofort. Erkannte Arten erscheinen als Karten unterhalb des Sonogramms mit:
- Artname (Sprache waehlbar in Settings), wissenschaftlicher Name
- Confidence in Prozent
- Zeitstempel + GPS-Koordinaten (falls verfuegbar)
- Top-N Kandidaten (aufklappbar, klickbar — siehe Verifikation)

**FAB-Zustaende (V0.0.6):**

| Farbe | Zustand | Bedeutung |
|-------|---------|-----------|
| Gruen (pulsierend) | PREROLL_BUFFERING | Preroll wird gepuffert |
| Rot (pulsierend) | RECORDING | Aufnahme laeuft |

**Status-Zeile:** Zeigt Laufzeit in mm:ss (1 Hz-Takt).

**GPS-Bar:** Zeigt aktuelle Genauigkeit in ±m. Wird rot wenn laenger als 30 s kein gueltiger Fix.

**Ruhige Liste:** Eine bereits gesehene Art rueckt nur dann wieder an Platz 1, wenn sie seit mindestens 10 Sekunden nicht mehr gehoert wurde.

**Preroll:** Wenn aktiviert (Settings → Erkennung), werden 5/10/30 Sekunden Audio VOR dem Erkennungszeitpunkt mitgespeichert. Der Preroll steht direkt am Anfang der Session-Audiodatei. Das Sonogramm zeigt davon max. 5 s an — die WAV-Datei enthaelt den vollstaendigen Preroll.

**Sonogramm Auto-Kontrast (V0.0.6):** DynamicRangeMapper berechnet rollierend p5/p95 ueber ein 5-s-Fenster, IIR-geglaettet (alpha 0.15), alle 250 ms neu. Ergebnis: Sonogramm passt sich automatisch an Umgebungslautstaerke an. Manueller dB-Range-Slider als Fallback in Settings → Sonogramm.

**Session-Rotation (V0.0.6):** Bei langen Aufnahmen wechselt PIROL automatisch nach max. 60 Minuten (konfigurierbar: 10–80 min) in ein neues Segment (`recording-002.wav` usw.). Die Aufnahme laeuft unterbrechungsfrei weiter.

**Hochpassfilter:** Default an (200 Hz). Filtert Wind- und Trittschall. Abschaltbar in Settings → Erkennung.

**Stopp:** Nochmals FAB druecken. Session und Raven-Export werden automatisch gespeichert.

---

## Verifikation (auf SpeciesCard)

Pro Detektion stehen fuenf Aktionen zur Verfuegung:

| Icon | Aktion | Status | Visualisierung |
|------|--------|--------|----------------|
| ✓ | Bestaetigen | CONFIRMED | gruener Rand |
| ? | Unsicher | UNCERTAIN | oranger Rand, Badge "Unsicher" |
| ✗ | Ablehnen | REJECTED | ausgegraut (alpha 0.6) |
| ✎ | Korrigieren | CORRECTED | Art durch Dropdown ersetzt |
| (Klick auf Alternative) | Alternative waehlen | REPLACED | Original ausgegraut, roter Rand, Badge "Ersetzt → <Art>" |

**Alternative waehlen:** Wenn die erkannte Art nicht stimmt, Kandidaten-Zeile aufklappen und die richtige Art antippen. Die Alternative erscheint als neue Detektion an Index 0, die urspruengliche Detektion bleibt sichtbar als REPLACED in der Liste (Audit-Trail).

**Instant-Update (V0.0.6):** Status-Aenderungen werden sofort in der UI sichtbar — kein manuelles Reload noetig.

Jede Verifikation wird in `verifications.jsonl` der Session gespeichert.

---

## Sessions

Jede Aufnahme erzeugt eine Session unter `Downloads/PIROL/YYYY-MM-DD/{sessionId}/`:

```
Downloads/PIROL/2026-04-26/a3f9c1/
├── session.json                   ← Metadaten (Dauer, Ort, GpsStats, recordingSegments)
├── detections.jsonl               ← Alle Detektionen (Zeitoffsets in Sekunden ab Aufnahme-Start)
├── verifications.jsonl            ← Alle Verifikations-Aktionen
├── recording.wav                  ← Eine durchgehende WAV-Datei (kurze Sessions)
│   oder
├── recording-001.wav              ← Segment 1 (bei Session-Rotation, T57)
├── recording-002.wav              ← Segment 2
│   ...
└── recording.selections.txt       ← Raven Selection Table (Auto-Export nach Session-Ende)
```

**Kein `audio/`-Unterordner mehr** (seit V0.0.6/T51): Alle Dateien liegen direkt im Session-Ordner.

**Session-Rotation:** Bei Aufnahmen laenger als das konfigurierte Maximum (Default 60 min) entstehen mehrere WAV-Segmente. `session.json` enthaelt die vollstaendige `recordingSegments`-Liste (nullable, rueckwaertskompatibel).

**Periodischer WAV-Header-Update:** Alle ~60 s wird der RIFF-Header (fileSize + dataSize) aktualisiert. Wird die App gekillt, bleibt die Datei trotzdem abspielbar.

**Auto-Delete (V0.0.6):** Kurze Sessions ohne Nachweis (keine Detektionen, konfigurierbare Mindestdauer) werden automatisch geloescht. Einstellbar in Settings → Speicher.

**Storage-Migration:** Beim Wechsel des Speicherorts (intern ↔ SD-Karte) koennen bestehende Sessions migriert werden. Der SessionMigrationWorker nutzt SAF-URIs und benoetigt keinen direkten Dateisystem-Zugriff.

**Loeschen:** Wisch-Geste oder Loeschen-Button auf der Session-Karte (mit Bestaetigung + Snackbar-Undo 5 s).

> **Hinweis zu aelteren Sessions:** Sessions die vor V0.0.3 aufgenommen wurden, liegen noch im alten Chunk-Format (`audio/chunk_NNN.wav`) vor. Sie werden im Analyse-Tab weiterhin angezeigt, die Audio-Wiedergabe ist aber nicht verfuegbar (Banner erscheint). Detektions-Daten bleiben sichtbar.

---

## Analyse-Tab

- **Session-Browser:** Alle gespeicherten Sessions, sortiert nach Datum. Session-Karte zeigt Dauer (mm:ss) und Groesse (MB).
- **Session oeffnen:** Sonogramm + Detektionsliste mit Zeit-Labels (MM:SS pro Detektion).
- **Play-Button pro Detektion:** Springt direkt an die Sekunde in `recording.wav` an der die Art detektiert wurde. Auto-Stop nach ~3 Sekunden.
- **Globaler Play/Stop:** Spielt die gesamte Aufnahme ab. Bei segmentierten Sessions werden die Segmente nahtlos aneinandergereiht.
- **AudioPlayer SAF-Unterstuetzung (V0.0.6):** Liest WAV-Dateien via SAF-URI; kein direkter Dateisystem-Zugriff benoetigt.
- **Streaming-Sonogramm (V0.0.6):** Lange Aufnahmen werden gestreamt — kein vollstaendiger RAM-Load.
- **Verifikation:** Jede Detektion kann nachtraeglich bestaetigt, abgelehnt, als unsicher markiert, korrigiert oder durch eine Alternative ersetzt werden. Status-Update sofort sichtbar.
- **Dual-Sonogramm:** Detektion vs. Referenz nebeneinander vergleichen + synchrone Wiedergabe.
- **Manueller Raven-Re-Export:** Raven Selection Table neu generieren (z.B. nach nachtraeglicher Verifikation).

---

## Export

### Raven Selection Table (einziger Export-Typ ab V0.0.6)

Wird **automatisch beim Session-Stop** als `recording.selections.txt` im Session-Ordner gespeichert. Manueller Re-Export im Analyse-Tab weiterhin moeglich.

Tab-getrenntes Textformat — oeffnet direkt in:
- Cornell Raven (bioakustische Profi-Software)
- Audacity (als Label-Track importieren)
- Sonic Visualiser

**Format V0.0.6 (14 Spalten):**

```
Selection	Begin File	Begin Time (s)	End Time (s)	Low Freq (Hz)	High Freq (Hz)	Begin Date Time	Species	Confidence	Status
1	recording.wav	12.345	15.345	150	10000	2026-04-26T08:23:24+02:00	Parus major	0.873	CONFIRMED
2	recording-002.wav	45.900	48.900	150	10000	2026-04-26T09:28:17+02:00	Erithacus rubecula	0.910	REPLACED
```

Neu gegenueber V005:
- Spalte `Begin File` (vor Begin Time): Dateiname des WAV-Segments — relevant bei Session-Rotation
- Spalte `Begin Date Time` (nach High Freq): Absoluter Zeitstempel inkl. Timezone-Offset
- `Low Freq` neu: 150 Hz (war 0 Hz)
- Multi-File-Support: Zeitoffsets relativ zu jeweiligem Segment-Start

Alle Detektionen werden exportiert — auch REJECTED/REPLACED (Audit-Trail).

> **KML-Export entfernt** (seit V0.0.6/T51): Der KML-Export ist nicht mehr verfuegbar.

---

## Referenzbibliothek

Verifizierte Aufnahmen werden als Referenzen gespeichert. Embedding-basierter Aehnlichkeitsvergleich (Cosine Similarity) gegen neue Detektionen.

- **Hinzufuegen:** Verifizierte Detektion → "Als Referenz speichern"
- **Vergleich:** Automatisch bei neuer Detektion oder manuell im Analyse-Tab
- **Loeschen:** Loeschen-Button auf der Referenz-Karte (mit Bestaetigung)

---

## Karte

GPS-georeferenzierte Detektionen auf osmdroid-Karte. Marker pro Art, farbcodiert nach Confidence.

---

## Species-Alarm

**Watchlist:** Liste mit Zielarten. Import aus `Downloads/PIROL/watchlist.json` oder per SAF. Editierbar in Settings → Alarm.

**Normalisierung (V0.0.6):** Gross-/Kleinschreibung und fuehrendes/nachfolgendes Leerzeichen werden bei Watchlist-Abgleich normalisiert — Tippfehler fuehren nicht mehr zu stillen Nicht-Treffern.

Bei Detektion einer Watchlist-Art: Vibration + Notification.

**Cooldown-Slider (V0.0.6):** Cooldown-Dauer stufenlos konfigurierbar in Settings → Alarm (verhindert Daueralarm).

---

## Energiesparmodus

Settings → Erkennung → Analyse-Intervall:

| Profil | Intervall | Beschreibung |
|--------|-----------|-------------|
| Genau | 3s | Jeder Chunk wird analysiert |
| Balanciert | 6s | Jeder 2. Chunk |
| Sparsam | 15s | Jeder 5. Chunk |
| Ultra-Sparsam | 30s | Jeder 10. Chunk |

FP16-Modell halbiert den RAM-Verbrauch und beschleunigt Inference.

---

## Modell-Management

Settings → Modell:
- **Installierte Modelle:** Liste mit RadioButton-Auswahl
- **Download:** Zenodo-Link, Progress-Anzeige
- **SAF-Import:** Lokale .onnx-Datei importieren
- **Umschaltung:** Sofort wirksam, naechste Inference nutzt neues Modell

---

## Einstellungen Uebersicht

| Sektion | Parameter |
|---------|-----------|
| Erkennung | Confidence-Schwelle, Analyse-Intervall, Hochpassfilter, Preroll (5/10/30 s) |
| GPS | Genauigkeits-Filter (Default 50 m), Intervall (2/5/10/20/60 s), Median-Smoothing |
| Sonogramm | Auto-Kontrast (DynamicRangeMapper an/aus), manueller dB-Range-Slider |
| Modell | Installiert, Download, Import, Auswahl (FP32/FP16) |
| Artnamen | Sprache (23 verfuegbar) |
| Region | Artfilter (z.B. CH Brutvoegel, DACH) |
| Alarm | Watchlist, Cooldown-Slider |
| Speicher | Intern / SD-Karte, Migration, Auto-Delete kurze Sessions, Max. Segment-Dauer (10–80 min) |

---

## Technische Hinweise

- **Samplerates:** 48 kHz (Standard) / 96 kHz (Fledermaus-Modus, kein Preroll)
- **Inference:** BirdNET V3 via ONNX Runtime, Resampling auf 32 kHz
- **DSP-Kette:** Hochpassfilter (200 Hz Butterworth) → Peak-Normalisierung → Brickwall-Limiter (nur auf Inference-Kopie, Original-Audio unangetastet)
- **Audio-Speicher:** Eine durchgehende `recording.wav` pro Session (16-bit PCM Mono). Bei Session-Rotation mehrere Segmente (`recording-001.wav`, `recording-002.wav`, …). Streaming-Writer mit RandomAccessFile; WAV-Header wird alle ~60 s an Offset 4 (fileSize) + Offset 40 (dataSize) aktualisiert → Datei bei Kill stets abspielbar.
- **Zeitstempel:** ISO 8601 mit Timezone-Offset (`+02:00`). `chunkStartSec` / `chunkEndSec` in `detections.jsonl` sind Sekunden ab Session-Start (Position in `recording.wav` bzw. jeweiligem Segment). `parseInstantCompat` verarbeitet beide Formate (Z und +HH:MM).
- **Speicherbedarf:** ~5.5 MB pro Minute Aufnahme (48 kHz, 16-bit, Mono)
- **GPS (V0.0.6):** FusedLocationProvider, PRIORITY_HIGH_ACCURACY. Genauigkeits-Filter 50 m (konfigurierbar), LastKnown-Fallback 2 min, Median-Smoothing ueber 5 akzeptierte Fixes. `GpsStats` (fixCount, rejectedCount, medianAccuracy, intervalMs) werden in `session.json` gespeichert. Intervall konfigurierbar: 2/5/10/20/60 s (Default 10 s).
- **Fairphone-Kompatibilitaet (V0.0.6):** Sample-Rate-Negotiation statt Fixed 48 kHz; AAudio→OpenSLES-Fallback bei Initialisierungsfehler; Preroll-Flush-Serialisierung verhindert Crash bei Preroll-Stop-Race.
- **Preroll-Sonogramm:** Darstellung auf max. 5 s Samples begrenzt (kein ANR); WAV-Datei enthaelt den vollstaendigen Preroll ungekuerzt.
- **Sonogramm Auto-Kontrast:** DynamicRangeMapper; Rolling-Window p5/p95 ueber 5 s; IIR-Glaettung alpha=0.15; Recompute-Intervall 250 ms. Im Analyse-Tab: einmaliges Einlesen der Perzentile ueber die gesamte Session (`computeStatic`).
- **Worker-Konventionen (V0.0.7+):** Max. 60k Tokens pro Worker-Session; Test-Build-Naming `V{Release}.T{N}` (z.B. `V0.0.6.T66`); Worktree-Disziplin (frische Branch von master, kein Task-Stapeln); Quality-Gates: jedes AP vor Dispatch und jedes Handover vor Master-Merge vom User freigegeben.

---

## Changelog — inkorporierte Tasks

| Task | Kurzbeschreibung |
|------|-----------------|
| T50 | Streaming-WAV-Writer: kontinuierliche WAV-Datei statt Chunk-Files *(kein Handover vorhanden; Quelle: master-briefing)* |
| T51 | SAF-Speicherlayout (`Downloads/PIROL/YYYY-MM-DD/`), Auto-Raven-Export, KML-Entfernung, Timezone-Fix (ZonedDateTime), Daueraufnahme-Fix (kein Early-Return in InferenceWorker) |
| T52 | Live-UX: FAB-3-State, Snackbar-Undo (≥ 5 s), GPS-Bar 1 Hz, Top-N stable Re-Sort, parseInstantCompat, Session-Info mm:ss + MB |
| T53 | GPS-Robustheit: Accuracy-Filter 50 m, LastKnown-Fallback, Median-Smoothing, GpsStats in session.json, 12 Unit-Tests |
| T54 | Preroll-Crash: 3 Race-Conditions behoben (prerollMel isoliert, sessionDispatcher serialisiert, Sonogramm auf 5 s gekappt) |
| T55 | Per-Species-Freq / BSED-Embeddings *(kein Handover vorhanden; Quelle: master-briefing)* |
| T56 | Sonogramm Auto-Kontrast: DynamicRangeMapper (p5/p95 Rolling, IIR), manueller dB-Range-Slider, Analyse-Tab computeStatic |
| T57 | Session-Rotation (RecordingSegment, Multi-WAV), WAV-Header Periodic Update, Raven-TSV: Begin File + Begin Date Time + Low Freq 150 Hz + Multi-File, FAB-Farben: Preroll=Gruen, Recording=Rot |
| T58 | Watchlist-Normalisierung (Gross-/Kleinschreibung, Whitespace), Cooldown-Slider, WatchlistManager Unit-Tests |
| T59 | SessionMigrationWorker SAF-URI-Fix: Storage-Wechsel ohne direkten Dateisystem-Zugriff |
| T60 | Auto-Delete kurzer Sessions ohne Nachweis: `shouldAutoDelete()`, konfigurierbare Mindestdauer |
| T61 | Verifikations-Status Instant-Update: Mutex-Serialisierung, IO-Fehlerbehandlung, Race-Condition-Tests |
| T62 | AudioPlayer SAF-Unterstuetzung + Streaming-Sonogramm fuer lange Dateien (kein RAM-Vollload) |
| T63 | Fairphone Preroll-Crash: Sample-Rate-Negotiation, AAudio→OpenSLES-Fallback, Preroll-Flush-Serialisierung, WavWriter-Bug-Fix |
| T64 | *(Handover nicht vorhanden zum Dispatch-Zeitpunkt 2026-04-26)* |
| T65 | *(Handover nicht vorhanden zum Dispatch-Zeitpunkt 2026-04-26)* |

---

*PIROL V0.0.6 — ETA Systems — Stand 2026-04-26 (V0.0.6 + Block A, T50–T63)*

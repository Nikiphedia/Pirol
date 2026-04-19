# PIROL V0.0.5 — Bedienungsanleitung

Akustische Echtzeit-Artenerkennung fuer Android.

---

## Erster Start

1. App oeffnen → Onboarding fuehrt durch Berechtigungen (Mikrofon, GPS, Speicher)
2. BirdNET-Modell herunterladen (Zenodo, ~50 MB) oder manuell per SAF importieren
3. Modellvariante waehlen: FP32 (genauer) oder FP16 (schneller, weniger RAM)

---

## Live-Erkennung

**Start:** Gruener FAB-Button auf dem Live-Tab.

Das Sonogramm laeuft sofort. Erkannte Arten erscheinen als Karten unterhalb des Sonogramms mit:
- Artname (Sprache waehlbar in Settings), wissenschaftlicher Name
- Confidence in Prozent
- Zeitstempel + GPS-Koordinaten (falls verfuegbar)
- Top-N Kandidaten (aufklappbar, klickbar — siehe Verifikation)

**Ruhige Liste:** Eine bereits gesehene Art rueckt nur dann wieder an Platz 1, wenn sie seit mindestens 10 Sekunden nicht mehr gehoert wurde. Permanent rufende Voegel scrollen die Liste also nicht nervoes durch.

**Preroll:** Wenn aktiviert (Settings → Erkennung), werden 5/10/30 Sekunden Audio VOR dem Erkennungszeitpunkt mitgespeichert. Der Preroll steht dabei direkt am Anfang der Session-Audiodatei.

**Hochpassfilter:** Default an (200 Hz). Filtert Wind- und Trittschall. Abschaltbar in Settings → Erkennung.

**Stopp:** Nochmals FAB druecken. Session wird automatisch gespeichert.

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

**Alternative waehlen:** Wenn die erkannte Art nicht stimmt, Kandidaten-Zeile aufklappen und die richtige Art antippen. Die Alternative erscheint als neue Detektion an Index 0, die ursprueng liche Detektion bleibt sichtbar als REPLACED in der Liste (Audit-Trail).

Jede Verifikation wird in `verifications.jsonl` der Session gespeichert.

---

## Sessions

Jede Aufnahme erzeugt eine Session unter `sessions/{datum}_{id}/`:

```
sessions/2026-04-19T08-23-12_a3f9c1/
├── session.json                   ← Metadaten (Dauer, Ort, Einstellungen)
├── detections.jsonl               ← Alle Detektionen (Zeitoffsets in Sekunden ab Aufnahme-Start)
├── verifications.jsonl            ← Alle Verifikations-Aktionen
└── audio/
    ├── recording.wav              ← Eine durchgehende WAV-Datei der gesamten Aufnahme (inkl. Preroll)
    └── recording.selections.txt   ← Raven Selection Table (nach Export)
```

**Ein File, eine Aufnahme** (Merlin-Modell): Pro Session gibt es genau eine `recording.wav`. Der Preroll steht direkt am Dateianfang — kein Jonglieren mit Chunk-Dateien.

**Loeschen:** Wisch-Geste oder Loeschen-Button auf der Session-Karte (mit Bestaetigung).

**Speicherort:** Settings → Speicher. Intern oder SD-Karte. Beim Wechsel koennen bestehende Sessions migriert werden.

> **Hinweis zu aelteren Sessions:** Sessions die vor V0.0.3 aufgenommen wurden, liegen noch im alten Chunk-Format (`audio/chunk_NNN.wav`) vor. Sie werden im Analyse-Tab weiterhin angezeigt, die Audio-Wiedergabe ist aber nicht verfuegbar (Banner erscheint). Detektions-Daten bleiben sichtbar.

---

## Analyse-Tab

- **Session-Browser:** Alle gespeicherten Sessions, sortiert nach Datum.
- **Session oeffnen:** Sonogramm + Detektionsliste mit Zeit-Labels (MM:SS pro Detektion).
- **Play-Button pro Detektion:** Springt direkt an die Sekunde in `recording.wav` an der die Art detektiert wurde. Auto-Stop nach ~3 Sekunden.
- **Globaler Play/Stop:** Spielt die gesamte `recording.wav` ab.
- **Verifikation:** Jede Detektion kann nachtraeglich bestaetigt, abgelehnt, als unsicher markiert, korrigiert oder durch eine Alternative ersetzt werden.
- **Dual-Sonogramm:** Detektion vs. Referenz nebeneinander vergleichen + synchrone Wiedergabe.
- **Export-Buttons:** KML (GPS + Detektionen) und Raven Selection Table (.txt).

---

## Export

### KML
Analyse-Tab → Session oeffnen → KML-Export. Erzeugt KML-Datei mit GPS-Track und Detektions-Placemarks. Share Intent zum Versenden per Mail, Messenger, Cloud. Oeffnet in QGIS und Google Earth.

### Raven Selection Table
Analyse-Tab → Session oeffnen → "Raven (.txt)". Erzeugt `recording.selections.txt` neben `recording.wav` im Session-Ordner. Tab-getrenntes Textformat — oeffnet direkt in:
- Cornell Raven (bioakustische Profi-Software)
- Audacity (als Label-Track importieren)
- Sonic Visualiser

Format-Auszug:

```
Selection	Begin Time (s)	End Time (s)	Species	Confidence	Status
1	12.345	15.345	Parus major	0.873	CONFIRMED
2	45.900	48.900	Erithacus rubecula	0.910	REPLACED
```

Alle Detektionen werden exportiert — auch REJECTED/REPLACED (Audit-Trail). Der Status steht in der entsprechenden Spalte.

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

**Watchlist:** JSON-Datei mit Zielarten. Import aus `Downloads/PIROL/watchlist.json` oder per SAF.

Bei Detektion einer Watchlist-Art: Vibration + Notification. Cooldown verhindert Daueralarm.

Watchlist editierbar in Settings → Alarm.

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
| Erkennung | Confidence-Schwelle, Analyse-Intervall, Hochpassfilter, Preroll |
| Modell | Installiert, Download, Import, Auswahl |
| Artnamen | Sprache (23 verfuegbar) |
| Region | Artfilter (z.B. CH Brutvoegel, DACH) |
| Alarm | Watchlist, Cooldown |
| Speicher | Intern / SD-Karte, Migration |
| Upload | Export-Pfad, WorkManager-Status |

---

## Technische Hinweise

- **Samplerates:** 48 kHz (Standard) / 96 kHz (Fledermaus-Modus, kein Preroll)
- **Inference:** BirdNET V3 via ONNX Runtime, Resampling auf 32 kHz
- **DSP-Kette:** Hochpassfilter (200 Hz Butterworth) → Peak-Normalisierung → Brickwall-Limiter (nur auf Inference-Kopie, Original-Audio unangetastet)
- **Audio-Speicher:** Eine durchgehende `recording.wav` pro Session (16-bit PCM Mono). Streaming-Writer mit RandomAccessFile-Header-Finalisierung beim Stop.
- **Zeitstempel:** `chunkStartSec` / `chunkEndSec` in `detections.jsonl` sind Sekunden ab Session-Start (Position in `recording.wav`).
- **Speicherbedarf:** ~5.5 MB pro Minute Aufnahme (48 kHz, 16-bit, Mono)
- **GPS:** FusedLocationProvider, 10s Intervall, nur bei aktiver Session

---

*PIROL V0.0.5 — ETA Systems — Stand 2026-04-19 (T49)*

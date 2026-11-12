# PIROL — Pull: Änderungen & Verbesserungen
Stand: 2026-04-07

## Was es ist
Android-App zur akustischen Echtzeit-Vogelartenerkennung (BirdNET ONNX). Läuft, aber UX im Feld unbrauchbar — zu viel Rauschen, falsche Sprache, Rotation kaputt.

## Stack
Kotlin 2.1, Jetpack Compose, Material 3, Koin DI, Oboe NDK, ONNX Runtime Mobile, osmdroid, Min SDK 26 / Target 35

---

## MVP-Änderungen

### 1. Artennamen in gewählter Sprache (Bug)
- Detektionsliste zeigt lateinische Namen statt gewählter Sprache
- SpeciesNameResolver wird nicht korrekt angesteuert bei Detektion
- **Zusatz:** Bei Rotation springt Sprache auf Deutsch (System-Default) zurück — nicht für alle Anzeigen konsistent

### 2. Detektionsliste komplett überarbeiten (UX)
- **Problem:** Chunk-basierte Erkennung erzeugt Spam (20x Turdus merula, 1x Parus major, 10x Turdus merula → Kohlmeise geht unter)
- **Neues Verhalten:**
  - Pro Art nur eine Zeile (Deduplizierung mit Zähler)
  - Neue Art erscheint zuoberst, bleibt mindestens 2s dort
  - Nachschieben: bestehende Einträge rutschen nach unten
  - Letzte erkannte Art leuchtet 5s nach
  - [OFFEN: Verschwindet eine Art nach Inaktivität oder bleibt sie in der Liste?]

### 3. Rotation / Orientierung (Bug)
- Alle 4 Orientierungen unterstützen: 0° / 90° / 180° / 270°
- Dark Mode funktioniert nicht im Landscape-Layout
- Sprach-Reset bei Activity-Recreate fixen (siehe Punkt 1)

### 4. Preroll-Puffer (Feature)
- Läuft heute dauerhaft → darf erst bei App-Öffnung starten
- Abschaltbar (Toggle in Settings)
- Länge einstellbar [OFFEN: Bereich? 5s/10s/30s oder frei?]
- Bei Aufnahmestart: Preroll + aufgenommenes Material → ein zusammenhängendes WAV-File
- Im Sonogramm darstellbar (nahtlos vor dem Live-Material)

### 5. Audio-Pipeline: Normalisierung & Filter (Feature)
- **Problem:** Audio wird zu leise aufgenommen → weniger Erkennungen
- **Lösung:** Peak-Normalisierung pro Chunk vor Inference (Original-WAV bleibt unangetastet)
- Brickwall-Limiter als Sicherheitsnetz
- Hochpassfilter (~200Hz Cutoff) gegen Wind-Rumpeln und Trittschall
- [OFFEN: Hochpass immer an oder abschaltbar?]
- Beides konfigurierbar in Settings

### 6. Modell-Management (Feature/Fix)
- Mehrere BirdNET-Varianten nutzbar (V3 FP32, V3 FP16, ev. V2.4 TFLite)
- In Settings umschaltbar
- Import-Prozess (Zenodo-Download + SAF) funktioniert heute nicht sauber → fixen

### 7. Sessions/Dateien löschen (Feature)
- Fehlt komplett
- Sessions und einzelne Aufnahmen löschbar machen

### 8. Speicherort wählbar (Feature)
- Im Onboarding: Speicherort für Dateien und Modelle festlegen
- [OFFEN: Interner Speicher vs. SD-Karte vs. SAF (beliebig)?]

### 9. Analyse-Tab: Chunk-Navigation (UX)
- Tippen auf eine Art springt zum entsprechenden Chunk
- Wiedergabe startet 5s vor der Detektion (Vorlauf)

---

## Offene Entscheidungen

| # | Frage | Kontext |
|---|-------|---------|
| 1 | Detektionsliste: Verschwindet Art nach Inaktivität? | Listenverhalten Punkt 2 |
| 2 | Preroll-Länge: Feste Stufen oder frei? | Preroll Punkt 4 |
| 3 | Speicherort: Welche Optionen genau? | Onboarding Punkt 8 |
| 4 | Hochpassfilter: Immer an oder abschaltbar? | Audio-Pipeline Punkt 5 |
| 5 | Priorisierung: Sprach-/Listen-Bugs zuerst? | Reihenfolge der Arbeitspakete |

---

## Nicht im Scope (diese Runde)

- Xeno-Canto Integration
- Bat-Modus (Ultraschall)
- Wear OS Companion
- KMP Code-Sharing AMSEL ↔ PIROL
- Offline-Tiles osmdroid
- Marker-Clustering Karte
- Adaptives Noise-Gating (zu aufwändig für V1)

---

## Ideenspeicher

- Wind-/Trittfilter als adaptives Gate (spätere Version)
- Marker-Clustering bei vielen Detektionen
- Offline-Kartenmaterial

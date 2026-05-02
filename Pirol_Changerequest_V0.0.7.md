**Pirol — Changerequest V0.0.7**

**Stand:** 2026-04-27 (Pirolmaster 7)
**Legende:**
- ✅ erledigt + Tag (`V0.0.6.T<N>`) — in master gemerged
- ⏳ Code fertig, Geräte-/Feldtest beim User ausstehend
- 🔄 in Arbeit / dispatched (Worker laeuft oder AP wartet auf Worker-Start)
- 📅 geplant für spaeteren Block (Cluster-Tag)
- ⬜ offen, noch nicht in einen Block eingeplant

**Cluster-Tags:** [A] Bugs · [B] Detektions-Logik · [C] Manuelle Erfassung · [D] Karte V2 · [E] Player+Chunk · [F] Listen/Default-JSON · [G] Settings UX · [H] Xeno-Canto/Cloud (V0.0.8) · [I] Raven-Export

---

* ✅ [V0.0.6.T70, Radius+Zeit in Settings, `firstSeenAtMs`/`lastSeenAtMs`, ActionType `CLUSTER_MERGED`, 9 Tests gruen] detektion einer art über mehrere chunks und innerhalb  10m radius zu einer meldung zusammenfassen, auch in protokoll.
* ✅ [V0.0.6.T71, TopK=3 default + topKThreshold=0.3 separat von confidenceThreshold, Settings-Slider, 5 Tests gruen — Pipeline war seit T70 schon multi-label-faehig, T71 hat nur Konfiguration sauber gemacht] multi erkennungen innerhalb von chunk
* 📅 [E] teilen von chunk, selektieren von vor und nachlauf. während recording, hinzufügen zu referenzfiles, kategorisieren und klassifizieren
* 📅 [E] art vorschlag und chunk, inkl. vor- und nachlauf, abhören über headphone, ohne aufnahme unterbrechen.
* 📅 [E] in vergleichsmodus und analysemodus ermöglichen von referenzaufnahmen anhören
* ✅ [V0.0.6.T68 (Text via `note`) + V0.0.6.T69 (Sprach-WAV 16kHz mono 16-bit, 60s-Cap)] memofunktion, sprach und text
* ✅ [V0.0.6.T68] manuell stückzahl eingeben, atlascode wahl, bemerkungen.
* ✅ [V0.0.6.T72, AddManualDetectionDialog mit Art-Autocomplete + GPS-Snapshot, ACTION_MANUAL_ADD, PanTool-Badge bei manualEntry] manuell art hinzufügen, auch eintrag in logfile.
* ✅ [V0.0.6.T72, selectAlternative in beiden VMs, ACTION_ALTERNATIVE_CONFIRMED] alternativvorschläge bestätigen können. auch eintrag in logfile
* ✅ [V0.0.6.T62] player hat probleme, sonogramanzeige unbrauchbar, stürzt bei langen files ab.
* 📅 [D1] auf karte müssen sich aufnahmen anwählen lassen welche angezeigt werden. auch nur live anzeigen. aufnahmen müssen manuell verschoben werden können. ( auch positionsänderung in logfiles )
* ✅ [V0.0.6.T65, 8h-Dauertest ohne Crash bestanden 2026-04-27 — kein Crash-Trigger fuer S1/S2/S3, Recovery-Code aktiv aber im Praxiseinsatz nicht ausgeloest] wie sicherstellen dass das bei programabsturz oder batterieausfall audiofile lesbar bleibt?
* ✅ [V0.0.6.T59] bei ordnerwechsel, dateien rüberkopieren funktioniert nicht
* ✅ [V0.0.6.T60] files ohne nachweis und unter 10 sec automatisch löschen
* 📅 [H, V0.0.8] integration von xeno- canto mit api key, und sonogramimport. anwahl von qualitätsniveau. eigene sonogramme und audiofiles
* 📅 [H, V0.0.8] auswählen von backupordner / cloudablage und export automatisch, nur bei w-lan oder push. googledrive integration?
* 📅 [E] bei chunkanlyse, schnellnormalisieren mittels bitshift.
* 📅 [Spike vor E] grundsätzliches vorgehen, datei wird auf datenträger geschrieben, und die chunks vom datenträger gelesen, so kann birdnet schneller arbeiten, und auch ergebnisse von preroll werden angezeigt.
* ✅ [V0.0.6.T74, Toggle in Settings + species_phenology.json (initial leer, User fuellt nach), Saison-untypisch-Badge orange + Star-Icon bei rarity>0.7. Live-View Caller noch nicht angepasst — Mini-Folge-Task] vorschläge sind zum teil aus dem ganzen 11k arten fundus. listen müssen sich an jahreszeit anpassen und bei niedriger wahrscheinlichkeit des antreffens oder seltene art. meldung hervorheben.
* ✅ [V0.0.6.T58] liste mit alarmarten funktioniert gar nicht
* 📅 [F] listeneditor und import von listen. format json. ablegen in /pirol ordner, auch referenzsonogramme dort ablegen. scanfunktion in einstellungen.
* 📅 [G] kapitel in einstellungen ausklapbar machen für bessere übersicht
* 📅 [D2] auf kartenansicht, den arten unterschiedliche farben zuweisen. abspeichern in json, so das konfiguriert werden kann
* 📅 [D3] kartenansicht, zeigen von bemerkungen, meldeungsstatus sowie atlascode. meldung auch hier editierbar, mit langem drücken. mehrere meldungen innerhalb kleiner fläche ( markierungen überschneiden sich), meldesymbol wird ein \+. drücken darauf lässt meldungen erscheinen. langes drücken auf meldungen lässt sie editieren. scrollfunktion bei vielem meldungen. kein Tropfensymbol, sondern kreis mit abkürzung Art, Atlascode und stückzahl. Kreis ist farbe von Art. — Library: osmdroid RadiusMarkerClusterer (bestätigt 2026-05-02)
* ✅ [V0.0.6.T73, Liste-Teil: Long-Press → Sperren/Entsperren-Menue, Lock-Badge, Suppression in Inference-Loop, Lock-First-Sortierung, ACTION_SPECIES_LOCKED. Karten-Teil offen fuer Cluster D] funktion für art sperren, d.h. es wird nur eine meldung gemacht, die art erscheint dann als kleines icon in der liste und bleibt sichtbar die ganze zeit. in kartenansicht abenfalls.
* 📅 [D4] map verwaltung: JSON-Manifest als App-Asset (statisch, Option A bestätigt 2026-05-02). Welche Karte in welcher Zoomstufe verfügbar ist, wird im Asset konfiguriert.
* 📅 [D4] swisstopo karten einbinden. — Entschied: WMTS via wmts.geo.admin.ch (freie BGDI-Tiles, bestätigt 2026-05-02)
* 📅 [D4] auf kartenansicht eine homefunktion implementieren
* 📅 [I] raven export, grundparameter sind fix, zusatzinfos anwählbar. submenue in einstellungen.
* 📅 [E] play, bei abspielen ist playbar in der mitte. mit ziehen lässt sich abspielposition verschieben. in einstellungen lässt sich preevent bei erkanntem ruf einstellen um kontext zu hören.
* 📅 [G] einzelne funktionen abwählbar um benutzerkomplexität möglichst zu veringern.
* 📅 [D5] eintragen simultanbeobachtung, mehrfachsichtung und vermutete Simultanbeobachtung. (verschoben aus B5, nur Karten-relevant)
* 📅 [E] presets für rauschfilterung bei abspielen audiofile, und speichern Sonogram.
* ✅ [V0.0.6.T61] aktualisieren von artbestätigungen ist nicht instant.
* ✅ [V0.0.6.T75, Audit-Befund: Mehrfach-Nennungen kamen aus EmbeddingDatabase (per recordingId, kein Art-Dedup); Refactor mit DE-Namen + Dedup + Settings-Slider 0..10 + Marquee-Mode bei 0.5 + Toggle, 6 Tests] "ähnliche Arten" abschnitt ist genauer sinn unbekannt. bezeichnungen sind der wissenschaftliche namen, und es erscheinen mehrfach nennungen.  Funktion muss abgewählt werden können, und anzeigbare anzahl zeilen eingestellt werden können. bei 0.5 Zeilen werden jeweils die letzten 5 erfassungen in laufschrift angezeigt.
* 📅 [F] am schluss wird ein default json definiert, das wird standardmässig im nutzerordner abgelegt und nicht überschrieben
* 📅 [G] Touchsperre und sperren bildschirmdrehen.

---

## Zusatzliche Aufgaben (nicht im Original-Changerequest, vom Master eingefuegt)

* ✅ [V0.0.6.T63] T54b Fairphone-Preroll-Crash (vom V0.0.6-Hold-Stack) — Geraete-Verifikation auf Fairphone vom User vertagt (separater Bugreport)
* ✅ [V0.0.6.T64, 8h-Dauertest 2026-04-27 ohne Probleme] T55 Recording-Start 20×-Smoketest Samsung+Fairphone — Recording-Start-Stabilitaet stillschweigend mit-verifiziert
* ✅ [V0.0.6.T66] BDA V005 → V006 Update
* ✅ [V0.0.6.T67] Datenmodell-Erweiterung `DetectionResult` + `verifications.jsonl` actionTypes (Grundlage Cluster B/C/D)

---

## Bugreport (Von User)
- 🔄 [B/F-Hybrid, geplant als T78 Modell-Manifest-Update] BirdNet und andere Modelle lassen sich nicht direkt runterladen. Ein .json wäre Hilfreich und einfach zu aktualisieren. Später Update Version für alle.json welche von Ersteller zur verfügung gestellt werden.

## Fehlerlog 2026-05-02 (Feld-Feedback, nach Cluster B)

| # | Beschreibung | Cluster | Prio |
|---|-------------|---------|------|
| F1 | Tab-Wechsel startet neue Aufnahme (Navigation-Lifecycle-Bug) | **[A] Kritischer Bug** | HOCH |
| F2 | Resultate und Eingaben werden nicht automatisch aktualisiert — neue Erfassungen erscheinen nicht in der Liste | **[A] Kritischer Bug** | HOCH |
| F3 | Raven-Datei: Klick auf Datei soll Vorschau zeigen. Speichern ist immer Auto — kein "Speichern unter"-Symbol in Dateiansicht; einzelne Felder anwählbar/abwählbar. Im Ribbon (Einzel-Beobachtung) sind zwei Haken — verwirrend. Alle Icons sollen gleich gross sein. | **[I] Raven-Export** | MITTEL |
| F4 | Target-Symbol und Play-Symbol im Player haben dieselbe Funktion — eines entfernen | **[E] Player** | MITTEL |
| F5 | App läuft immer im Hintergrund (Akkuverschleiss). App darf nur laden wenn angewählt. Preroll erst aktiv wenn explizit angewählt. | **[A] Architektur** | HOCH |
| F6 | +Art-Button und Memo-Button: unten direkt neben Play verschieben | **[G] Settings UX / LiveScreen-Layout** | NIEDRIG |
| F7 | Aufnahme Stoppen = Long-Press 2 Sekunden (kein einfacher Tap) | **[G] UX** | NIEDRIG |

**Hinweis AP-Groesse:** Alle APs max. 60'000 Tokens (Konvention CLAUDE.md). Master schneidet bei Bedarf.

## Folge-AP-Backlog (Master-Beobachtungen aus Block A)

| # | Issue | Buendel-Vorschlag |
|---|-------|-------------------|
| (a) | `WavWriter.force(true)` bei Akku-Aus verifizieren | T70 Persistenz-Haertung |
| (b) | Int-Overflow bei WAV >2GB | T70 |
| (c) | `FileWriter.sync()` fehlt fuer detections.jsonl | T70 |
| (d) | Stereo-Downmix in `readWavSamplesRange` (T62) — bleibt offen wegen geplantem Stereo-Mic + Gyroscope (V0.0.8+) | mit Stereo+Gyro-Feature |
| (e) | Doppelter `modifier` in `DetectionList.kt:112` | T71 UI-Polish |
| ~~(f)~~ | ~~`saveErrorMessage`-Snackbar in AnalysisScreen verdrahten~~ — ✅ bereits in T-prev verdrahtet (T68-Beobachtung) | erledigt |
| (h) | `removeLastVerification()` auf tmp+rename umstellen (analog T68 `replaceDetection`) | T70 |
| (i) | `DetectionResult.atlasCode`-Kommentar zeigt alte Beispiele "A2"/"B4" — auf "AC1"–"AC99" aktualisieren | T71 |
| (g) | `undoLastVerification()` leerer if-Block (T53) | T70 |
| Atlas | Settings-Dropdown Atlascode-Set CH/FR/DE (vorerst nur CH in T68) | nach Cluster C |
| Memo | Memo-Export als m4a aus AnalysisPlayer | nach Cluster C |

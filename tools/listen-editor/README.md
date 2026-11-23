# PIROL Listen-Editor

Eigenstaendige HTML-Seite zum Erstellen von Artenlisten im Format v4 (`tools/SPECIES_LIST_FORMAT.md`). Laeuft komplett offline im Browser (Tablet, Telefon, Desktop), keine Netzwerkzugriffe, keine Abhaengigkeiten.

## Dateien

| Datei | Zweck |
|-------|-------|
| `template.html` | Quelle der Seite (UI) |
| `core.js` | Kernlogik: Laden, Export, Validierung, Saison (ohne DOM, testbar) |
| `build.js` | baut `listen-editor.html` (Template + Kernlogik + eingebetteter Artenindex + Aliase) und kopiert sie nach `app/src/main/assets/editor/listen-editor.html` |
| `listen-editor.html` | fertige Seite (ca. 0,9 MB), die ausgeliefert wird |
| `test.js` / `test.html` | Selbsttest der Kernlogik (`node tools/listen-editor/test.js` oder `test.html` im Browser oeffnen) |

Nach Aenderungen an `template.html` oder `core.js`: `node tools/listen-editor/build.js`. Die Seite wird aus `species_master.json`, `birdnet_v3_labels.csv` und `species_aliases.json` der App-Assets erzeugt.

## Nutzung

1. `listen-editor.html` im Browser oeffnen (Doppelklick, `file://`, `content://` aus der App oder GitHub Pages).
2. Optional **species_master.json** laden (Knopf "species_master.json laden"): dann stehen alle 23 Sprachen fuer Artnamen zur Verfuegung. Ohne diese Datei gibt es Deutsch und Englisch aus dem eingebetteten Index (11'564 Arten).
3. Optional **Label-CSV** des Modells laden (Semikolon-CSV mit `sci_name`): Arten ohne Label werden beim Pruefen als "vom Modell nicht erkennbar" gemeldet.
4. Kopfdaten ausfuellen (ID = spaeterer Dateiname, nur `a-z 0-9 _`).
5. Arten suchen (Name in gewaehlter Sprache, wissenschaftlicher Name, Teilwort; Filter Tiergruppe) und hinzufuegen. Arten ohne Treffer (z.B. Nicht-Voegel ohne Eintrag) per `Genus_species` und optionalem Namen von Hand hinzufuegen.
6. Pro Art aufklappen: Saison (Monat, optional Tag; "von" nach "bis" ueber den Jahreswechsel, z.B. 11 bis 02), eigene Tiergruppe, Bemerkung, Name.
7. **Pruefen** zeigt Fehler (blockieren den Export) und Hinweise. **Als v4-JSON herunterladen** speichert `<id>.json`.

Bestehende Listen (v3 oder v4, Einzel-Set oder Sammeldatei) lassen sich laden und aendern. Unbekannte Felder bleiben erhalten (Round-Trip). Eine Sammeldatei wird beim Export zur Einzel-Set-Datei (das gewaehlte Set).

Die exportierte Datei kommt in den PIROL-Ordner `lists/` (siehe `SPECIES_LIST_FORMAT.md`, Abschnitt 7). Gespeichert werden nur wissenschaftliche Namen im Unterstrich-Format; Unterarten sind nicht erlaubt.

## Spaeter: GitHub Pages

Die Datei ist eigenstaendig; fuer GitHub Pages genuegt es, `listen-editor.html` im Listen-Repo unter `editor/index.html` abzulegen. Das Repo wird erst nach Freigabe angelegt. PIROL nimmt online die Pages-URL, ohne Netz die Kopie aus den App-Assets (`assets/editor/listen-editor.html`).

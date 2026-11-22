# Artenlisten-Format v4 (PIROL + AMSEL)

Gemeinsames Dateiformat fuer regionale Artenlisten. Gelesen und geschrieben von PIROL (Android), AMSEL Desktop und dem Browser-Listen-Editor (HTML, GitHub Pages). Listen werden im Editor erstellt, in PIROL/AMSEL importiert und ueber ein Git-Repo zwischen allen ausgetauscht. Vollstaendiges Beispiel: `examples/species_list_v4_example.json`.

v4 ist eine **additive** Erweiterung von `region_sets.json` v3. Jede v3-Datei ist eine gueltige v4-Eingabe; jeder v3-Leser sieht in einer v4-Datei die vollstaendige Artenliste (ohne Saison), also hoechstens zu viele Arten, nie zu wenige.

## 1. Zweck und Abgrenzung

- **Artenliste (dieses Format):** Welche Arten sind in einem Gebiet/Kontext plausibel, optional zu welcher Jahreszeit. Wirkt als Filter auf Modell-Detektionen (`RegionalSpeciesFilter`).
- **Watchlist (separates Format, unveraendert):** Welche Arten loesen einen Alarm aus (`watchlist.json`, `WatchlistEntry` mit `priority`). Abbildung Liste -> Watchlist siehe Abschnitt 8.
- Nicht Teil des Formats: Artnamen-Uebersetzungen (kommen aus `species_master.json` / `SpeciesNameResolver`), Modell-Labels, Phaenologie-Statistik.

## 2. Ist-Stand v3 (aus PIROL abgeleitet)

Asset `app/src/main/assets/regions/region_sets.json`:

```json
{
  "version": 3,
  "sets": [
    { "id": "ch_breeding", "name_de": "Schweizer Brutvoegel", "name_en": "Swiss Breeding Birds",
      "description_de": "Regelmaessige Brutvoegel der Schweiz (180 Arten)",
      "species": ["Accipiter_nisus", "Acrocephalus_arundinaceus", "..."] }
  ]
}
```

- Sets im Asset: `all` (leer = kein Filter), `ch_breeding` (180), `ch_all` (237), `central_europe` (302).
- Eigene Listen (T129, `CustomSpeciesLists.parse`): `<PIROL-Ordner>/lists/*.json`, entweder **ein Set** als Wurzelobjekt (ohne `version`) oder `{"sets":[...]}`. `id` fehlt -> Dateiname; `name_de` fehlt -> `id`; `name_en` fehlt -> `name_de`. Leere oder fehlende `species` = Fehler. Intern bekommt die ID das Praefix `list:`.
- Namen: getrimmt, Leerzeichen -> Unterstrich; Vergleich immer ueber `SpeciesKey.of(name)` (Kleinschreibung + Alias-Aufloesung `species_aliases.json`, kanonisch = BirdNET 3.0 final).
- Unbekannte Felder werden schon heute ignoriert (`description_de` wird von PIROL nicht gelesen).

## 3. Dateiaufbau v4

UTF-8 ohne BOM, JSON (keine Kommentare). Zwei Formen, wie in v3:

- **Einzel-Set:** Wurzelobjekt = Kopfdaten + ein Set (empfohlen fuer das Listen-Repo).
- **Sammeldatei:** Wurzelobjekt = Kopfdaten + `sets: [...]` (Asset `region_sets.json`, Beispieldatei).

Annotierte Einzel-Set-Datei (Kommentare nur zur Erklaerung, in echten Dateien verboten):

```json
{
  "version": 4,
  "author": "N. N.",
  "created": "2026-11-22",
  "updated": "2026-11-22T15:00:00+01:00",
  "description": "Brutvoegel Kanton Bern",
  "display_language": "de",                 // Sprache von common_name und description
  "taxonomy": { "aves": { "reference": "IOC", "version": "15.1" } },

  "id": "ch_be_breeding_aves",              // [a-z0-9_]+, = Dateiname ohne .json
  "name_de": "Brutvoegel Bern",
  "name_en": "Breeding birds Bern",
  "taxon_group": "aves",
  "species": ["Oriolus_oriolus", "Turdus_merula"],
  "species_info": {
    "Oriolus_oriolus": { "common_name": "Pirol", "season": [{ "from": "04-20", "to": "08-31" }] }
  }
}
```

### 3.1 Kopfdaten (Wurzelobjekt)

| Name | Typ | Pflicht | Default | Bedeutung |
|------|-----|---------|---------|-----------|
| `version` | Int | v4: ja | fehlt = 3 | Formatversion. Writer schreiben `4`. |
| `generator` | String | nein | - | Erzeugendes Programm + Version, z.B. `"pirol-list-editor 0.1"`, `"AMSEL 0.4"`. |
| `author` | String | nein | - | Autor/in der Liste (frei). |
| `created` | String | nein | - | Erstellung, `YYYY-MM-DD` oder ISO 8601 **mit Offset**. |
| `updated` | String | nein | - | Letzte Aenderung, Format wie `created`. Writer setzen es bei jeder Aenderung. |
| `description` | String | nein | - | Beschreibung der Datei, Sprache = `display_language`. |
| `display_language` | String | nein | `"de"` | BCP-47-Code (`de`, `en`, `fr`, `gsw-CH` ...) fuer `common_name` und `description`. |
| `taxonomy` | Objekt | v4: ja fuer `aves` | - | Taxonomie-Referenz je Tiergruppe: `{ "<taxon_group>": { "reference": String, "version": String } }`. Fuer Voegel `reference = "IOC"` (IOC World Bird List), `version` z.B. `"15.1"`. |
| `license` | String | nein | - | Lizenz der Liste beim Teilen, z.B. `"CC BY 4.0"`. |
| `sets` | Array | Sammeldatei | - | Liste von Set-Objekten (3.2). Fehlt `sets`, ist die Wurzel selbst das Set. |

Kopfdaten gelten fuer alle Sets der Datei. Ein Set darf `taxonomy` und `description` nicht ueberschreiben (Einfachheit); verschiedene Kopfdaten = verschiedene Dateien.

### 3.2 Set

| Name | Typ | Pflicht | Default | Bedeutung |
|------|-----|---------|---------|-----------|
| `id` | String | ja (Repo) | Dateiname ohne Endung, bei Sammeldatei `<datei>-<n>` | Stabile ID, `[a-z0-9_]+`. Apps speichern die gewaehlte ID; nicht umbenennen. |
| `name_de` | String | nein | `id` | Anzeigename Deutsch. |
| `name_en` | String | nein | `name_de` | Anzeigename Englisch. |
| `name_<lang>` | String | nein | `name_en` | Weitere Sprachen, z.B. `name_fr`, `name_it` (v4). |
| `description_de` | String | nein | - | Beschreibung Deutsch (v3). |
| `description_en` | String | nein | - | Beschreibung Englisch (v4). |
| `taxon_group` | String | nein | `"aves"` | Tiergruppe der Liste (Vokabular 3.4). Gilt fuer alle Arten ohne eigenes `taxon_group`. |
| `source` | String | nein | - | Quellenangabe (Atlas, Rote Liste, eigene Beobachtung ...). |
| `species` | Array\<String\> | ja | - | **Alle** Arten der Liste, Unterstrich-Format `Genus_species`. Nie leer (Ausnahme: Asset-Set `all` = kein Filter). |
| `species_info` | Objekt | nein | `{}` | Zusatzangaben pro Art, Schluessel = Eintrag aus `species` (3.3). |

**Warum `species_info` getrennt:** `species` bleibt ein reines String-Array wie in v3. Ein v3-Leser (alte PIROL-/AMSEL-Version) liest die Datei unveraendert und filtert ohne Saison. Objekte im `species`-Array wuerden alte Leser brechen bzw. Arten still verlieren.

### 3.3 Art-Eintrag in `species_info`

| Name | Typ | Pflicht | Default | Bedeutung |
|------|-----|---------|---------|-----------|
| `common_name` | String | nein | - | Anzeigename in `display_language`. Nur Rueckfall: Apps zeigen zuerst den Namen aus `SpeciesNameResolver` (App-Sprache). Wichtig fuer Nicht-Voegel ohne Eintrag in `species_master.json`. |
| `taxon_group` | String | nein | Set-`taxon_group` | Abweichende Tiergruppe dieser Art (gemischte Listen). |
| `season` | Array\<Zeitraum\> | nein | ganzjaehrig | Aktive Zeitraeume. Vereinigung aller Eintraege. Fehlt oder `[]` = ganzjaehrig. |
| `note` | String | nein | - | Freitext (z.B. "nur Rufaktivitaet"). |

Zeitraum: `{ "from": "MM" | "MM-DD", "to": "MM" | "MM-DD" }`, beide Grenzen **inklusiv**.

- `"MM"` als `from` = Tag 01 des Monats; `"MM"` als `to` = letzter Tag des Monats (Feb inkl. 29.).
- `MM` 01-12, `DD` 01 bis Monatslaenge (Februar bis 29). Immer zweistellig.
- Kein Jahr: Saison wiederholt sich jedes Jahr.

### 3.4 Tiergruppen-Vokabular (`taxon_group`)

Kleinbuchstaben, wissenschaftlicher Gruppenname. Unbekannte Werte sind erlaubt (Anzeige wie geschrieben, Warnung im Editor).

| Wert | Gruppe | Taxonomie-Referenz (Vorschlag) |
|------|--------|-------------------------------|
| `aves` | Voegel | IOC World Bird List (verbindlich) |
| `orthoptera` | Heuschrecken | Orthoptera Species File (`OSF`) - offen |
| `chiroptera` | Fledermaeuse | offen |
| `anura` | Froschlurche | offen |
| `mammalia` | uebrige Saeuger | offen |
| `insecta` | uebrige Insekten (Zikaden ...) | offen |
| `other` | Sonstiges | - |

## 4. Saison-Auswertung

Eine Art ist fuer einen Zeitpunkt **plausibel**, wenn

1. `SpeciesKey.of(art)` im kanonischen Set der aktiven Liste liegt, **und**
2. die Art keine `season` hat (oder `[]`), **oder** das Stichtag-Datum in mindestens einem Zeitraum liegt.

**Stichtag:** lokales Kalenderdatum (Monat, Tag) des Detektionszeitpunkts in der Zeitzone der Aufnahme. PIROL live: Geraete-Lokalzeit beim Chunk. Nachtraeglich (AMSEL, PIROL-Reanalyse): Session-Start aus `session.json` (ISO mit Offset) + `chunkStartSec`, im Offset der Session. Nicht UTC verwenden.

Vergleich als Tupel `(MM, DD)`; Jahr wird ignoriert:

```
inRange(d, from, to):
  if from <= to:  return from <= d && d <= to          // normal, z.B. 04-15..08-15
  else:           return d >= from || d <= to          // Jahreswechsel, z.B. 11-01..02-29
```

Randfaelle:

| Fall | Regel |
|------|-------|
| `from > to` (z.B. `"11"` bis `"02"`) | Ueber den Jahreswechsel: 1. Nov bis Ende Feb. |
| `from == to` | Genau ein Tag (`"05-01"`) bzw. ein Monat (`"05"`). |
| `{"from":"01","to":"12"}` | Ganzjaehrig (gleich wie fehlende Saison). |
| 29. Februar | Als Grenze erlaubt. Tupelvergleich: `to:"02-29"` schliesst im Nicht-Schaltjahr am 28.2.; `from:"02-29"` beginnt dann faktisch am 1.3. Keine Sonderbehandlung noetig. |
| Mehrere Zeitraeume | Vereinigung; Ueberlappung erlaubt. |
| Ungueltiger Zeitraum (`"13"`, `"04-31"`, fehlendes `to`, falscher Typ) | Eintrag ignorieren + Warnung. Bleibt kein gueltiger Eintrag: Art ganzjaehrig + Warnung (nie still ausfiltern). |
| Stichtag unbekannt (Datei ohne Zeitstempel) | Saison nicht auswerten (ganzjaehrig), Hinweis anzeigen. |
| Session ueber Mitternacht / Monatswechsel | Pro Detektion mit ihrem eigenen Datum auswerten. Caching pro Kalendertag erlaubt. |
| Art in `species_info`, aber nicht in `species` | Eintrag ignorieren + Warnung (`species` ist massgebend). |
| v3-Leser | Ignoriert `species_info` -> Art ganzjaehrig plausibel. Gewollte Degradation. |

## 5. Namensabgleich und Validierung

**Speicherform:** wissenschaftlicher Name im Unterstrich-Format (`Turdus_merula`), gross geschriebene Gattung, nach IOC-Version aus `taxonomy.aves.version` (Voegel) bzw. der Referenz der Tiergruppe.

**Leser (PIROL, AMSEL):**

1. Trim, Leerzeichen -> Unterstrich (wie v3). Leere Strings verwerfen.
2. Vergleich nur ueber `SpeciesKey.of(name)` (Kleinschreibung + `species_aliases.json`). Die Datei wird nie umgeschrieben.
3. Duplikate mit gleichem Schluessel: einmal zaehlen; fuer `species_info` gilt der erste Eintrag, Warnung.
4. Arten, die kein aktives Modell kennt (kein Label mit gleichem Schluessel): nur Hinweis ("x Arten vom Modell nicht erkennbar"), nicht entfernen.
5. Fehler (Set wird uebersprungen): kein JSON, `species` fehlt/kein Array/leer. Alle anderen Probleme sind Warnungen.

**Editor (beim Speichern):**

- Formatpruefung `^[A-Z][a-z]+_[a-z]+(-[a-z]+)?$` (Bindestrich-Epitheta wie `novae-hollandiae`); Abweichung = Warnung.
- Abgleich gegen die IOC-Namensliste der Kopf-Version: unbekannt = Warnung; bekanntes Synonym/alter Name = Vorschlag des aktuellen IOC-Namens.
- Abgleich gegen die Modell-Labels (BirdNET 3.0 final) ueber dieselbe Alias-Tabelle: Hinweis, wenn eine Art nicht erkannt werden kann.

**Wichtig (Konflikt IOC vs. BirdNET):** `SpeciesKey` ist kanonisch auf BirdNET 3.0 final. Wo IOC und BirdNET verschiedene Namen fuehren (heute schon fuer aeltere Namen geloest, z.B. `Accipiter_gentilis` -> `Astur_gentilis`; mit jeder neuen IOC-Version kommen Aenderungen dazu), braucht es einen Eintrag in `species_aliases.json`, sonst greift der Filter nicht. Die Alias-Tabelle muss deshalb die IOC-Namen der Kopf-Version abdecken (Erweiterung von `tools/build_species_aliases.py`, Folge-AP). Editor, PIROL und AMSEL muessen dieselbe Alias-Tabelle verwenden.

## 6. Versionierung und Kompatibilitaet

| `version` | Leser-Verhalten |
|-----------|-----------------|
| fehlt | v3 (eigene Listen T129 haben oft keine Version). |
| `3` | v3; alle v4-Felder fehlen -> Defaults. |
| `4` | v4. |
| `> 4` | Als v4 lesen, unbekannte Felder ignorieren, Hinweis "neuere Formatversion". |

- **Regel fuer alle Leser:** unbekannte Felder (Wurzel, Set, Art-Eintrag, Zeitraum) ignorieren, nie Fehler.
- **Regel fuer Schreiber:** beim Aendern einer bestehenden Datei unbekannte Felder **erhalten** (Round-Trip zwischen PIROL, AMSEL und Editor ohne Datenverlust). Immer `version: 4` schreiben.
- Kuenftige Versionen (5, 6 ...) duerfen nur Felder hinzufuegen. Ein inkompatibler Umbau braeuchte einen neuen Dateityp, nicht eine hoehere Nummer.
- Das Asset `region_sets.json` kann auf `version: 4` gehoben werden, sobald PIROL und AMSEL v4 lesen; bis dahin bleibt es v3.

## 7. Listen-Repo: Dateinamen und Struktur

```
<listen-repo>/
  README.md
  index.json                      # optional, generiert: id, Datei, name_de, taxon_group, Artenzahl, updated
  lists/
    ch/
      ch_breeding_aves.json
      ch_be_breeding_aves.json
      ch_plateau_orthoptera.json
    eu/
      central_europe_aves.json
  taxonomy/                       # Referenzlisten fuer den Editor (Namen, Synonyme)
    ioc_15.1.csv
  editor/                         # Browser-Editor (GitHub Pages), T189
```

- **Eine Liste pro Datei** (Einzel-Set-Form): saubere Diffs und Merges. Sammeldateien nur fuer App-Assets.
- **Dateiname = `<id>.json`**, `id` = `[a-z0-9_]+`, Aufbau `<region>_<inhalt>_<taxon_group>`; `<region>` = ISO-3166-Code klein (`ch`, `de`), optional Unterregion (`ch_be`), ueberregional `eu`, `world`.
- `id` repo-weit eindeutig und stabil (App-Einstellungen merken die ID). Umbenennen = neue Liste.
- **Formatierung fuer Git:** 2 Leerzeichen Einrueckung, LF, abschliessender Zeilenumbruch, `species` alphabetisch sortiert, `species_info`-Schluessel in derselben Reihenfolge. Writer formatieren immer gleich (kanonische Ausgabe), damit Diffs nur echte Aenderungen zeigen.
- **In PIROL:** Dateien nach `<PIROL-Ordner>/lists/` kopieren (bestehender T129-Mechanismus, Auswahl als `list:<id>`). **In AMSEL:** Ablageort offen (Frage 4).

## 8. Abbildung auf die Watchlist ("Watchlist aus Regionsliste uebernehmen")

Ziel: `watchlist.json` (`Watchlist` v1). Das Watchlist-Format bleibt unveraendert.

| Watchlist-Feld | Quelle |
|----------------|--------|
| `Watchlist.version` | `1` (unveraendert) |
| `Watchlist.name` | bestehender Name bleibt; bei neuer Watchlist `name_<App-Sprache>` des Sets, sonst `name_de` |
| `Watchlist.updatedAt` | Zeitpunkt der Uebernahme, ISO 8601 mit Offset |
| `WatchlistEntry.scientificName` | Eintrag aus `species`, Schreibweise der Liste (nicht der Schluessel) |
| `WatchlistEntry.commonName` | `SpeciesNameResolver` (App-Sprache), sonst `species_info.common_name`, sonst `""` |
| `WatchlistEntry.priority` | `normal` (oder im Dialog gewaehlter Standard) |
| `WatchlistEntry.notes` | `null` |

Nicht uebernommen: `season`, `taxon_group`, `note`, `source`, Kopfdaten.
Zusammenfuehren: Abgleich ueber `SpeciesKey.of`; bereits vorhandene Arten behalten `priority` und `notes`, neue werden angehaengt. Nichts wird entfernt.

## 9. Offene Fragen an den User

1. **Saison hart oder weich?** Art ausserhalb der Saison ausblenden (wie nicht in der Liste) oder anzeigen mit Markierung "ausserhalb Saison"? Schalter "Saison beachten" in den Settings?
2. **Taxonomie fuer Nicht-Voegel:** welche Referenz je Gruppe (Heuschrecken OSF oder info fauna/CSCF-Checkliste? Fledermaeuse, Amphibien)? Und: gibt es fuer diese Gruppen ueberhaupt Modelle (BirdNET 3.0 hat einzelne Insekten/Froesche)?
3. **IOC-Aliase:** Wer pflegt die Abbildung IOC -> BirdNET-Label? Vorschlag: `build_species_aliases.py` um IOC-Version erweitern, Tabelle in PIROL, AMSEL und Editor identisch.
4. **AMSEL:** Liest der AMSEL-v3-Parser strikt (z.B. Abbruch bei `version != 3`)? Wo legt AMSEL importierte Listen ab? Muss vor der Umstellung im AMSEL-Code geprueft werden (Quellcode lag hier nicht vor).
5. **Unterarten:** Trinomen (`Motacilla_flava_flava`) zulassen oder nur Arten?
6. **Repo:** Name, oeffentlich oder privat, Standardlizenz der Listen; `index.json` gewuenscht?
7. **Watchlist-Uebernahme:** nur zusammenfuehren (Vorschlag) oder auch "ersetzen"? Nur Arten, die heute in Saison sind? Saison als Text in `notes`?
8. **Schreibt PIROL Listen** (z.B. eigene Liste in der App bearbeiten/exportieren) oder nur lesen? Davon haengt ab, ob die Round-Trip-Regel in PIROL umgesetzt werden muss.
9. **Mehrsprachige Anzeigenamen:** reicht eine `display_language` pro Datei, oder braucht es `common_names: {"de":..., "fr":...}`?

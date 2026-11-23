# Listen-Generator (GBIF -> Artenlisten v4)

`generate.py` erzeugt Artenlisten im PIROL/AMSEL-Format v4 (`tools/SPECIES_LIST_FORMAT.md`) aus der
GBIF-Occurrence-API. Nur Python 3 (Standardbibliothek), nur lesende Abfragen ohne Schluessel
(`api.gbif.org`, fuer Regionsgeometrie `api3.geo.admin.ch`). Es werden keine persoenlichen Daten gesendet.

## Aufruf

```
python generate.py --labels <birdnet_v3_final_labels.csv> --regions ch --groups orthoptera
python generate.py --labels ... --regions ch,be,zh,jura --groups aves,anura
python generate.py --labels ... --regions alle --groups aves,orthoptera,anura,chiroptera
```

| Parameter | Default | Bedeutung |
|-----------|---------|-----------|
| `--labels` | (Pflicht) | Modell-Labels: 3.0 final (`filesDir/models/birdnet_v3_final_labels.csv`, Komma) oder Preview (`birdnet_v3_labels.csv`, Semikolon). Taxonomie der Liste = Taxonomie des Modells |
| `--regions` | `ch` | `ch`, Kantonskuerzel (`ag ar ai bl bs be fr ge gl gr ju lu ne nw ow sg sh sz so tg ti ur vs vd zg zh`), `jura mittelland alpennordflanke zentralalpen_west zentralalpen_ost alpensuedflanke`, oder `kantone`, `biogeo`, `alle` |
| `--groups` | `aves` | `aves orthoptera anura chiroptera mammalia` |
| `--min-records` | 20 | Mindestzahl Nachweise je Art und Liste (filtert Irrgaeste/Zufallsfunde) |
| `--years` | 20 | Zeitraum: letzte N Jahre |
| `--season-threshold` | 0.05 | Monat zaehlt zur Saison, wenn er mindestens diesen Anteil der Nachweise der Art hat |
| `--season-min-records` | 30 | Unter dieser Zahl Nachweise wird keine Saison abgeleitet (ganzjaehrig) |
| `--out` | `out/` | Ausgabe |
| `--cache` | `cache/` | Cache der API-Antworten |
| `--sleep` | 0.25 | Pause (s) vor jeder echten Netzabfrage |
| `--aliases` | Asset im Repo | `species_aliases.json` |

Ausgabe: `out/lists/ch/<id>.json` (Einzel-Set, kanonisch formatiert) und `out/sources.md`
(Datensaetze mit Lizenz und DOI, Listenuebersicht). `out/` und `cache/` sind gitignored.

ID-Schema: `ch_gbif_<gruppe>`, `ch_<kanton>_gbif_<gruppe>`, `ch_bg_<region>_gbif_<gruppe>`.

## Abfragelogik

- Filter: Region, `taxonKey` (Aves 212, Orthoptera 1458, Anura 952, Chiroptera 734, Mammalia 359), Jahre,
  `license=CC0_1_0` und `CC_BY_4_0` (kein CC BY-NC), `occurrenceStatus=PRESENT`, `basisOfRecord` =
  HUMAN_OBSERVATION, MACHINE_OBSERVATION, OBSERVATION, PRESERVED_SPECIMEN (ohne Fossilien und Gefangenschaft).
- Art-Liste: Facette `speciesKey` (ein Aufruf), Saison: dieselbe Facette je Monat 1-12 (12 Aufrufe),
  Datensaetze fuer `sources.md`: Facette `datasetKey`. Pro Liste also 14 Aufrufe plus Namensaufloesung.
- Regionen: Land `country=CH`; Kantone `gadmGid=CHE.<n>_1` (GADM, Nummern gegen GBIF geprueft);
  biogeografische Regionen per `geometry` (WKT), siehe unten.
- Kein Mehrfach-Abruf: jede Antwort landet im Cache (`cache/`). Nach einem Abbruch denselben Befehl
  nochmals starten, erledigte Abfragen kommen aus dem Cache. Bei HTTP 429/5xx wird mit Backoff wiederholt.
  Neue Abfragen sind 0.25 s verzoegert.

## Saison

Aus der Monatsverteilung der Nachweise einer Art: Monate mit mindestens 5 % der Nachweise sind aktiv,
zusammenhaengende Monate werden zu `{"from":"MM","to":"MM"}` (Jahreswechsel moeglich, z.B. `11` bis `02`).
Sind alle 12 Monate aktiv oder gibt es zu wenig Nachweise (< 30), steht keine Saison (ganzjaehrig,
"nie zu wenig").

**Verzerrung bei Voegeln:** Die groessten Quellen (Brutvogeldatenbank der Vogelwarte, ornitho/eBird) sind
auf die Brutzeit konzentriert, die Brutvogeldatenbank enthaelt sogar nur Brutzeit-Nachweise. Winter- und
Durchzugszeiten von Zug- und Wintergaesten sind deshalb unterrepraesentiert und Saisonfenster eher zu eng.
Gegenmassnahmen: niedrigere `--season-threshold`, oder die Saison im Listen-Editor nachbessern. Bei
Heuschrecken und Amphibien ist die Verteilung unproblematischer (Feldsaison), aber Erfassung im Hochsommer
bzw. zur Laichzeit ist ebenfalls ueberbetont.

## Namensabgleich auf das Modell

GBIF-Name (canonicalName der Art) wird in dieser Reihenfolge auf ein Modell-Label abgebildet:
1. direkt (Kleinschreibung, Unterstrich),
2. ueber `species_aliases.json` (wie `SpeciesKey.of`),
3. ueber die GBIF-Synonyme der Art (`/species/<key>/synonyms`, nur bei Bedarf abgefragt).

Bei Treffer steht der **Label-Name des Modells** in der Liste (damit der Filter ohne Aliastabelle greift) und
der deutsche Label-Name als `common_name`. Arten ohne Treffer bleiben mit GBIF-Namen in der Liste und tragen
in `species_info` das Zusatzfeld `"model_known": false`. Das Format erlaubt unbekannte Felder
(`SPECIES_LIST_FORMAT.md` Abschnitt 6: Leser ignorieren, Schreiber erhalten sie). So koennen andere Modelle
(z.B. Perch) die Art nutzen, PIROL zeigt "x Arten vom Modell nicht erkennbar". Die Zahl steht auch in
`description_de`.

## Biogeografische Regionen

Quelle: BAFU, Layer `ch.bafu.biogeographische_regionen` (opendata.swiss "Biogeographische Regionen der
Schweiz", Nutzungsbedingungen `terms_open`), abgerufen als GeoJSON ueber `api3.geo.admin.ch`. Die 12
Unterregionen werden zu den sechs Hauptregionen (Jura, Mittelland, Alpennordflanke, westliche und oestliche
Zentralalpen, Alpensuedflanke) zusammengefasst. Die GBIF-Suche nimmt die Geometrie als WKT in der URL (Limit rund 4000 Zeichen, sonst HTTP 400); die Geometrie wird
deshalb je Unterregion per Douglas-Peucker auf hoechstens 3200 Zeichen WKT vereinfacht (Toleranz ab 0.002 Grad).
Jede Unterregion wird einzeln abgefragt und die Zaehler werden addiert (ein gemeinsames Multipolygon benachbarter
Teile ergibt nach der Vereinfachung Selbstueberschneidungen, GBIF lehnt es ab). Folge: Grenzen sind auf wenige hundert Meter bis ca. 1-2 km ungenau, Nachweise am Rand koennen der
Nachbarregion zufallen. Fuer Artenlisten ist das unkritisch.

## Zitierung

Der Lizenzfilter gilt je Nachweis (`license` am Occurrence). Datensaetze mit Standardlizenz CC BY-NC (iNaturalist,
Birda) erscheinen deshalb in `sources.md` mit den wenigen Einzelnachweisen, die unter CC0/CC BY freigegeben sind.

`out/sources.md` listet die beteiligten GBIF-Datensaetze mit Lizenz und DOI. Fuer die Weitergabe der Listen
verlangt GBIF eine Zitierung; dafuer legt der User bei GBIF ein Derived Dataset mit DOI an (GBIF-Konto,
Liste der Quell-Datensaetze). Die Listen selbst stehen unter CC BY 4.0, weil nur CC0 und CC BY 4.0 Daten
eingehen.

## Tests

```
cd tools/listen-generator
python -m unittest test_generate
```

Ohne Netz, mit gespeicherten Beispielantworten (`testdata/sample.json`): Saisonableitung, Namensabgleich
(direkt, Alias, Synonym), Geometrie-Vereinfachung, v4-Ausgabe.

#!/usr/bin/env python3
"""T215: Listen-Generator. Erzeugt Artenlisten im PIROL/AMSEL-Format v4 aus der GBIF-Occurrence-API.

Nur Python-3-Standardbibliothek. Nur lesende, schluessellose Abfragen (api.gbif.org,
api3.geo.admin.ch). Es werden keine persoenlichen Daten gesendet.

Beispiel:
  python generate.py --labels birdnet_v3_final_labels.csv --regions ch --groups orthoptera
Siehe README.md.
"""
import argparse
import csv
import datetime
import hashlib
import json
import math
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
GENERATOR = "pirol-gbif-generator 1.0"
GBIF = "https://api.gbif.org/v1"
GEO_ADMIN = "https://api3.geo.admin.ch/rest/services/api/MapServer/find"
BIOGEO_LAYER = "ch.bafu.biogeographische_regionen"
LICENSES = ["CC0_1_0", "CC_BY_4_0"]
# Beobachtungen und Belege; ohne Fossilien und Zoo-/Gefangenschaftsbestaende
BASIS_OF_RECORD = ["HUMAN_OBSERVATION", "MACHINE_OBSERVATION", "OBSERVATION", "PRESERVED_SPECIMEN"]

# Tiergruppe -> (GBIF-taxonKey, deutscher Name, englischer Name)
GROUPS = {
    "aves": (212, "Voegel", "Birds"),
    "orthoptera": (1458, "Heuschrecken", "Grasshoppers and crickets"),
    "anura": (952, "Froschlurche", "Frogs and toads"),
    "chiroptera": (734, "Fledermaeuse", "Bats"),
    "mammalia": (359, "Saeuger", "Mammals"),
}

# Kantone: Kuerzel -> (GADM-Nummer, Name). GADM-Level-1 CHE.<n>_1, Nummern per GBIF-Stichprobe geprueft.
CANTONS = {
    "ag": (1, "Aargau"), "ar": (2, "Appenzell Ausserrhoden"), "ai": (3, "Appenzell Innerrhoden"),
    "bl": (4, "Basel-Landschaft"), "bs": (5, "Basel-Stadt"), "be": (6, "Bern"),
    "fr": (7, "Freiburg"), "ge": (8, "Genf"), "gl": (9, "Glarus"), "gr": (10, "Graubuenden"),
    "ju": (11, "Jura"), "lu": (12, "Luzern"), "ne": (13, "Neuenburg"), "nw": (14, "Nidwalden"),
    "ow": (15, "Obwalden"), "sg": (16, "St. Gallen"), "sh": (17, "Schaffhausen"), "sz": (18, "Schwyz"),
    "so": (19, "Solothurn"), "tg": (20, "Thurgau"), "ti": (21, "Tessin"), "ur": (22, "Uri"),
    "vs": (23, "Wallis"), "vd": (24, "Waadt"), "zg": (25, "Zug"), "zh": (26, "Zuerich"),
}

# Biogeografische Regionen (BAFU). Layer-Objektnummern 1-12 gehoeren zu 6 Hauptregionen.
BIOGEO = {
    "jura": ("Jura", [1]),
    "mittelland": ("Mittelland", [2, 3, 4, 5]),
    "alpennordflanke": ("Alpennordflanke", [6, 7]),
    "zentralalpen_west": ("Westliche Zentralalpen", [8]),
    "zentralalpen_ost": ("Oestliche Zentralalpen", [9, 10]),
    "alpensuedflanke": ("Alpensuedflanke", [11, 12]),
}


# ---------------------------------------------------------------- Saison

def months_to_ranges(active):
    """Menge aktiver Monate (1-12) -> Liste {from,to} mit 'MM'; Jahreswechsel moeglich ('11'..'02').
    Alle 12 Monate = ganzjaehrig = leere Liste."""
    act = set(active)
    if not act or len(act) == 12:
        return []
    # Start: ein aktiver Monat, dessen Vorgaenger inaktiv ist
    starts = [m for m in sorted(act) if ((m - 2) % 12) + 1 not in act]
    ranges = []
    for s in starts:
        e = s
        while (e % 12) + 1 in act:
            e = (e % 12) + 1
        ranges.append({"from": "%02d" % s, "to": "%02d" % e})
    return ranges


def derive_season(month_counts, threshold=0.05, min_records=30):
    """Saison aus Monatsverteilung {monat: nachweise}. Aktiv = Monat mit mindestens `threshold`
    Anteil der Nachweise. Unter `min_records` Nachweisen: ganzjaehrig (leere Liste, nie zu wenig)."""
    total = sum(month_counts.get(m, 0) for m in range(1, 13))
    if total < min_records:
        return []
    active = {m for m in range(1, 13) if month_counts.get(m, 0) >= threshold * total}
    if not active:
        active = {max(range(1, 13), key=lambda m: month_counts.get(m, 0))}
    return months_to_ranges(active)


# ---------------------------------------------------------------- Namensabgleich

def norm(name):
    return name.strip().replace(" ", "_").lower()


class Matcher:
    """Gleicht GBIF-Namen auf Modell-Labels ab: direkt, Alias, GBIF-Synonyme.
    Schluessel wie SpeciesKey.of(): Kleinschreibung + Alias-Aufloesung."""

    def __init__(self, labels, aliases):
        # labels: Liste (scientific_name mit Leerzeichen, common_name_de)
        self.aliases = {norm(k): norm(v) for k, v in aliases.items()}
        self.by_key = {}
        for sci, de in labels:
            self.by_key.setdefault(norm(sci), (sci.strip().replace(" ", "_"), de))

    def key(self, name):
        n = norm(name)
        return self.aliases.get(n, n)

    def match(self, name, synonyms=()):
        """-> (label_name_unterstrich, common_name_de, wie) oder None.
        `synonyms`: Iterable oder Callable, das GBIF-Synonymnamen liefert (nur bei Bedarf)."""
        k = norm(name)
        if k in self.by_key:
            return self.by_key[k] + ("direct",)
        k2 = self.aliases.get(k)
        if k2 in self.by_key:
            return self.by_key[k2] + ("alias",)
        syns = synonyms() if callable(synonyms) else synonyms
        for s in syns:
            ks = self.key(s)
            if ks in self.by_key:
                return self.by_key[ks] + ("synonym",)
        return None


def load_labels(path):
    rows = list(csv.DictReader(open(path, encoding="utf-8-sig"), delimiter=","))
    if rows and "scientific_name" in rows[0]:  # 3.0 final (Komma, scientific_name)
        return [(r["scientific_name"], (r.get("common_name_de") or "").strip()) for r in rows]
    # Preview-Format (Semikolon, sci_name)
    rows = list(csv.DictReader(open(path, encoding="utf-8-sig"), delimiter=";"))
    return [(r["sci_name"], "") for r in rows]


def load_aliases(path):
    return json.load(open(path, encoding="utf-8"))["aliases"]


# ---------------------------------------------------------------- Geometrie

def _dp(points, tol):
    """Douglas-Peucker (iterativ) fuer eine offene Punktliste."""
    n = len(points)
    if n < 3:
        return list(points)
    keep = [False] * n
    keep[0] = keep[-1] = True
    stack = [(0, n - 1)]
    while stack:
        a, b = stack.pop()
        ax, ay = points[a]
        bx, by = points[b]
        dx, dy = bx - ax, by - ay
        ln = math.hypot(dx, dy)
        best, idx = -1.0, -1
        for i in range(a + 1, b):
            px, py = points[i]
            d = math.hypot(px - ax, py - ay) if ln == 0 else abs(dx * (ay - py) - (ax - px) * dy) / ln
            if d > best:
                best, idx = d, i
        if best > tol:
            keep[idx] = True
            stack.append((a, idx))
            stack.append((idx, b))
    return [p for p, k in zip(points, keep) if k]


def _area(ring):
    return sum(ring[i][0] * ring[i + 1][1] - ring[i + 1][0] * ring[i][1] for i in range(len(ring) - 1)) / 2


def simplify_ring(ring, tol):
    """Geschlossener Ring (erster = letzter Punkt) vereinfachen; None, wenn degeneriert.
    Der Ring wird am ersten Punkt aufgetrennt und beide Haelften vereinfacht (Ring-Start bleibt)."""
    pts = [tuple(p[:2]) for p in (ring[:-1] if ring[0] == ring[-1] else ring)]
    half = len(pts) // 2
    a = _dp(pts[:half + 1], tol)
    b = _dp(pts[half:] + [pts[0]], tol)
    out = a[:-1] + b
    if len(out) < 4:
        return None
    return out


def simplify_polygons(polygons, max_vertices=250):
    """polygons: Liste von Polygonen (Liste von Ringen, GeoJSON-Koordinaten lon/lat).
    Toleranz wachsen lassen, bis die Gesamtzahl der Stuetzpunkte <= max_vertices.
    Aussenring gegen den Uhrzeigersinn, Loecher im Uhrzeigersinn (GBIF-Vorgabe)."""
    tol = 0.002
    while True:
        res = []
        for poly in polygons:
            rings = []
            ok = True
            for i, ring in enumerate(poly):
                s = simplify_ring(ring, tol)
                if s is None:
                    if i == 0:
                        ok = False
                        break
                    continue
                if (i == 0) != (_area(s) > 0):
                    s = s[::-1]
                rings.append(s)
            if ok:
                res.append(rings)
        if sum(len(r) for p in res for r in p) <= max_vertices or tol > 1:
            return res
        tol *= 1.6


def to_wkt(polygons):
    def ring(r):
        return "(" + ",".join("%.4f %.4f" % (x, y) for x, y in r) + ")"
    polys = ["(" + ",".join(ring(r) for r in p) + ")" for p in polygons]
    return "MULTIPOLYGON(" + ",".join(polys) + ")"


def geojson_polygons(geom):
    if geom["type"] == "Polygon":
        return [geom["coordinates"]]
    return list(geom["coordinates"])


# ---------------------------------------------------------------- HTTP + Cache

class Http:
    """GET-JSON mit Plattencache (Wiederaufnahme nach Abbruch) und Backoff bei 429/5xx."""

    def __init__(self, cache_dir, sleep=0.25, retries=5):
        self.cache_dir = cache_dir
        self.sleep = sleep
        self.retries = retries
        os.makedirs(cache_dir, exist_ok=True)
        self.calls = 0
        self.cached = 0

    def get_json(self, url):
        path = os.path.join(self.cache_dir, hashlib.sha1(url.encode()).hexdigest() + ".json")
        if os.path.exists(path):
            self.cached += 1
            with open(path, encoding="utf-8") as f:
                return json.load(f)
        delay = 2.0
        for attempt in range(self.retries):
            time.sleep(self.sleep)
            try:
                req = urllib.request.Request(url, headers={"User-Agent": "pirol-list-generator/1.0",
                                                           "Accept": "application/json"})
                with urllib.request.urlopen(req, timeout=60) as r:
                    data = json.load(r)
                self.calls += 1
                tmp = path + ".tmp"
                with open(tmp, "w", encoding="utf-8") as f:
                    json.dump(data, f)
                os.replace(tmp, path)
                return data
            except urllib.error.HTTPError as e:
                if e.code in (429, 500, 502, 503, 504) and attempt < self.retries - 1:
                    time.sleep(delay)
                    delay *= 2
                    continue
                raise
            except (urllib.error.URLError, TimeoutError):
                if attempt < self.retries - 1:
                    time.sleep(delay)
                    delay *= 2
                    continue
                raise


class Gbif:
    def __init__(self, http):
        self.http = http

    def facet(self, field, params, limit=5000):
        q = urllib.parse.urlencode(
            [("limit", 0), ("facet", field), ("facetLimit", limit), ("facetMincount", 1)] + params, doseq=True,
            quote_via=urllib.parse.quote, safe=",()")  # WKT kompakt: Komma/Klammern nicht kodieren (URL-Laenge)
        d = self.http.get_json(GBIF + "/occurrence/search?" + q)
        counts = d["facets"][0]["counts"] if d.get("facets") else []
        if len(counts) >= limit:
            print("WARNUNG: Facette %s hat das Limit %d erreicht" % (field, limit), file=sys.stderr)
        return {c["name"]: c["count"] for c in counts}, d.get("count", 0)

    def species(self, key):
        return self.http.get_json("%s/species/%s" % (GBIF, key))

    def synonyms(self, key):
        d = self.http.get_json("%s/species/%s/synonyms?limit=200" % (GBIF, key))
        return [r["canonicalName"] for r in d.get("results", []) if r.get("canonicalName")]

    def dataset(self, key):
        return self.http.get_json("%s/dataset/%s" % (GBIF, key))


# ---------------------------------------------------------------- Regionen

def region_defs(names):
    """-> Liste (id_teil, name_de, name_en, (art, wert))."""
    out = []
    for n in names:
        if n == "ch":
            out.append(("ch", "Schweiz", "Switzerland", ("country", "CH")))
        elif n in CANTONS:
            num, nm = CANTONS[n]
            out.append(("ch_" + n, "Kanton " + nm, "Canton " + nm, ("gadm", "CHE.%d_1" % num)))
        elif n in BIOGEO:
            nm = BIOGEO[n][0]
            out.append(("ch_bg_" + n, "Biogeografische Region " + nm, "Biogeographic region " + nm, ("biogeo", n)))
        else:
            sys.exit("Unbekannte Region: " + n)
    return out


def biogeo_wkts(http, key, max_chars=3200):
    """Eine WKT-Geometrie je Unterregion. Getrennt, weil benachbarte Teile nach der Vereinfachung
    als gemeinsames Multipolygon Selbstueberschneidungen ergeben koennen (GBIF: 400 Invalid geometry).
    Die Facettenzahlen der Teile werden addiert (Teile sind disjunkt)."""
    out = []
    for num in BIOGEO[key][1]:
        url = GEO_ADMIN + "?" + urllib.parse.urlencode({
            "layer": BIOGEO_LAYER, "searchText": num, "searchField": "objnummer", "contains": "false",
            "returnGeometry": "true", "geometryFormat": "geojson", "sr": 4326})
        polys = []
        for r in http.get_json(url)["results"]:
            polys += geojson_polygons(r["geometry"])
        # GBIF-URL darf rund 4000 Zeichen nicht ueberschreiten (sonst HTTP 400): WKT-Laenge begrenzen
        mv = 250
        while True:
            wkt = to_wkt(simplify_polygons(polys, mv))
            if len(wkt) <= max_chars or mv <= 20:
                break
            mv = int(mv * 0.8)
        out.append(wkt)
    return out


def area_params(http, kind, value):
    """-> Liste von Parameter-Listen (ein Eintrag je Teilgebiet; Ergebnisse werden addiert)."""
    if kind == "country":
        return [[("country", value)]]
    if kind == "gadm":
        return [[("gadmGid", value)]]
    return [[("geometry", w)] for w in biogeo_wkts(http, value)]


def facet_sum(gbif, field, pieces, base):
    """Facette ueber alle Teilgebiete, Zaehler addiert -> (dict, Gesamtzahl)."""
    total, n = {}, 0
    for piece in pieces:
        d, c = gbif.facet(field, piece + base)
        n += c
        for k, v in d.items():
            total[k] = total.get(k, 0) + v
    return total, n


# ---------------------------------------------------------------- Aufbau der Liste

def build_set(set_id, name_de, name_en, group, species, source, now_iso, today, years):
    """species: Liste dict(name, common_name, season, model_known). -> v4-Einzel-Set als dict."""
    species = sorted(species, key=lambda s: s["name"])
    gname = GROUPS[group]
    taxonomy = {group: {"reference": "GBIF Backbone, abgeglichen auf BirdNET 3.0 final", "version": today}}
    info = {}
    for s in species:
        e = {}
        if s.get("common_name"):
            e["common_name"] = s["common_name"]
        if s.get("season"):
            e["season"] = s["season"]
        if not s.get("model_known", True):
            e["model_known"] = False
        info[s["name"]] = e
    n_unknown = sum(1 for s in species if not s.get("model_known", True))
    return {
        "version": 4,
        "generator": GENERATOR,
        "author": "GBIF-Generator",
        "created": today,
        "updated": now_iso,
        "description": ("%s: beobachtete Arten laut GBIF (CC0 und CC BY 4.0), Jahre %s. "
                        "Saison aus der Monatsverteilung der Nachweise." % (name_de, years)),
        "display_language": "de",
        "license": "CC BY 4.0",
        "taxonomy": taxonomy,
        "id": set_id,
        "name_de": "%s: %s (GBIF)" % (name_de, gname[1]),
        "name_en": "%s: %s (GBIF)" % (name_en, gname[2]),
        "description_de": "%d Arten, davon %d vom BirdNET-Modell nicht erkennbar (model_known=false)"
                          % (len(species), n_unknown),
        "taxon_group": group,
        "source": source,
        "species": [s["name"] for s in species],
        "species_info": info,
    }


def process(gbif, matcher, region, group, a):
    rid, name_de, name_en, (kind, value) = region
    pieces = area_params(gbif.http, kind, value)
    taxon = GROUPS[group][0]
    year0 = a.year_to - a.years + 1
    base = [("taxonKey", taxon), ("year", "%d,%d" % (year0, a.year_to)),
                     ("occurrenceStatus", "PRESENT")]
    base += [("license", l) for l in LICENSES] + [("basisOfRecord", b) for b in BASIS_OF_RECORD]
    totals, n_records = facet_sum(gbif, "speciesKey", pieces, base)
    months = {}
    for m in range(1, 13):
        months[m], _ = facet_sum(gbif, "speciesKey", pieces, base + [("month", m)])
    datasets, _ = facet_sum(gbif, "datasetKey", pieces, base)

    merged = {}
    for key, cnt in totals.items():
        if cnt < a.min_records:
            continue
        sp = gbif.species(key)
        gname = sp.get("canonicalName") or sp.get("species")
        if not gname:
            continue
        hit = matcher.match(gname, lambda k=key: gbif.synonyms(k))
        name = hit[0] if hit else gname.replace(" ", "_")
        e = merged.setdefault(name, {"name": name, "common_name": hit[1] if hit else "",
                                     "model_known": bool(hit), "months": {m: 0 for m in range(1, 13)}})
        for m in range(1, 13):
            e["months"][m] += months[m].get(key, 0)
    species = []
    for e in merged.values():
        species.append({"name": e["name"], "common_name": e["common_name"], "model_known": e["model_known"],
                        "season": derive_season(e["months"], a.season_threshold, a.season_min_records)})
    n_unknown = sum(1 for s in species if not s["model_known"])
    today = datetime.date.today().isoformat()
    now_iso = datetime.datetime.now().astimezone().replace(microsecond=0).isoformat()
    sid = "%s_gbif_%s" % (rid, group)
    area_txt = {"country": "Land CH", "gadm": "GADM " + value,
                "biogeo": "BAFU biogeografische Region (vereinfachte Geometrie)"}[kind]
    src = ("GBIF Occurrence Search (api.gbif.org), %s, Taxon %s, Jahre %d-%d, nur CC0 und CC BY 4.0, "
           "mindestens %d Nachweise je Art, Abfrage %s" % (area_txt, taxon, year0, a.year_to, a.min_records, today))
    doc = build_set(sid, name_de, name_en, group, species, src, now_iso, today, "%d-%d" % (year0, a.year_to))
    out_dir = os.path.join(a.out, "lists", "ch")
    os.makedirs(out_dir, exist_ok=True)
    with open(os.path.join(out_dir, sid + ".json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(doc, f, ensure_ascii=False, indent=2)
        f.write("\n")
    return {"id": sid, "n": len(species), "unknown": n_unknown, "records": n_records, "datasets": datasets,
            "unknown_names": sorted(s["name"] for s in species if not s["model_known"])}


def write_sources(gbif, results, a):
    ds_total = {}
    for r in results:
        for k, c in r["datasets"].items():
            ds_total[k] = ds_total.get(k, 0) + c
    lines = ["# Quellen (generiert %s)" % datetime.date.today().isoformat(), "",
             "Daten: GBIF.org (api.gbif.org), Occurrence-Suche, nur Lizenzen CC0 und CC BY 4.0.",
             "Die Nachweiszahlen summieren die Abfragen ueber alle erzeugten Listen (Mehrfachzaehlung",
             "bei ueberlappenden Regionen). Fuer die Zitierung legt der User bei GBIF ein Derived Dataset an.",
             "Die Spalte Lizenz zeigt die Standardlizenz des Datensatzes. Gefiltert wird auf die Lizenz je",
             "Nachweis: Datensaetze mit Standard CC BY-NC (z.B. iNaturalist, Birda) tragen nur mit den",
             "Einzelnachweisen ein, die ihre Beobachter unter CC0 oder CC BY freigegeben haben.", "",
             "## Datensaetze", "", "| Nachweise | Datensatz | Lizenz | DOI | datasetKey |", "|---|---|---|---|---|"]
    for k, c in sorted(ds_total.items(), key=lambda kv: -kv[1]):
        d = gbif.dataset(k)
        lic = (d.get("license") or "").replace("http://creativecommons.org/", "").replace("https://creativecommons.org/", "")
        lines.append("| %d | %s | %s | %s | %s |" % (c, (d.get("title") or "").replace("|", "/"), lic, d.get("doi") or "", k))
    lines += ["", "## Listen", "", "| Liste | Arten | davon Modell unbekannt | Nachweise (gefiltert) |", "|---|---|---|---|"]
    for r in results:
        lines.append("| %s | %d | %d | %d |" % (r["id"], r["n"], r["unknown"], r["records"]))
    lines += ["", "## Geometrie", "",
              "Biogeografische Regionen: BAFU, via api3.geo.admin.ch (Layer %s), opendata.swiss-Nutzungsbedingungen "
              "terms_open (freie Nutzung), Quelle: Bundesamt fuer Umwelt BAFU. Fuer die Abfrage vereinfacht." % BIOGEO_LAYER,
              "Kantone: GADM-Level-1-Zuordnung durch GBIF (gadmGid)."]
    os.makedirs(a.out, exist_ok=True)
    with open(os.path.join(a.out, "sources.md"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--labels", required=True, help="Modell-Labels (birdnet_v3_final_labels.csv oder Preview-CSV)")
    p.add_argument("--aliases", default=os.path.join(HERE, "..", "..", "app", "src", "main", "assets", "species_aliases.json"))
    p.add_argument("--regions", default="ch",
                   help="Komma-Liste: ch, Kantonskuerzel (be,zh,...), %s, 'kantone', 'biogeo', 'alle'" % ",".join(BIOGEO))
    p.add_argument("--groups", default="aves", help="Komma-Liste: %s" % ",".join(GROUPS))
    p.add_argument("--min-records", type=int, default=20, help="Mindestzahl Nachweise je Art und Liste")
    p.add_argument("--years", type=int, default=20, help="Zeitraum: letzte N Jahre")
    p.add_argument("--year-to", type=int, default=datetime.date.today().year)
    p.add_argument("--season-threshold", type=float, default=0.05,
                   help="Anteil der Nachweise, ab dem ein Monat zur Saison zaehlt")
    p.add_argument("--season-min-records", type=int, default=30, help="darunter keine Saison (ganzjaehrig)")
    p.add_argument("--out", default=os.path.join(HERE, "out"))
    p.add_argument("--cache", default=os.path.join(HERE, "cache"))
    p.add_argument("--sleep", type=float, default=0.25, help="Pause zwischen Netzabfragen (s)")
    a = p.parse_args(argv)

    names = []
    for n in a.regions.split(","):
        n = n.strip().lower()
        names += {"kantone": list(CANTONS), "biogeo": list(BIOGEO),
                  "alle": ["ch"] + list(CANTONS) + list(BIOGEO)}.get(n, [n])
    groups = [g.strip().lower() for g in a.groups.split(",")]
    for g in groups:
        if g not in GROUPS:
            sys.exit("Unbekannte Tiergruppe: " + g)

    matcher = Matcher(load_labels(a.labels), load_aliases(a.aliases))
    gbif = Gbif(Http(a.cache, a.sleep))
    results = []
    for region in region_defs(names):
        for g in groups:
            r = process(gbif, matcher, region, g, a)
            results.append(r)
            print("%-34s %4d Arten, %3d ohne Modell, %d Nachweise" % (r["id"], r["n"], r["unknown"], r["records"]),
                  flush=True)
    write_sources(gbif, results, a)
    print("Netzabfragen: %d, aus Cache: %d. Ausgabe: %s" % (gbif.http.calls, gbif.http.cached, a.out))


if __name__ == "__main__":
    main()

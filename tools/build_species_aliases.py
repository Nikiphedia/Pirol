#!/usr/bin/env python3
"""T105: erzeugt app/src/main/assets/species_aliases.json (einmalig, Ergebnis ist eingecheckt).

Aufruf:  python tools/build_species_aliases.py <final_labels.csv>
  <final_labels.csv> = BirdNET+ V3.0 Global 12K Labels (Zenodo 24162518, Komma-CSV)

Kanonischer Schluessel = wissenschaftlicher Name aus BirdNET 3.0 final (Unterstrich-Format).
Quellen fuer alte Namen: Preview-Label-CSV (Asset), region_sets.json, species_master.json (Trivialnamen).
Zuordnung (nur wenn eindeutig):
  1. Name identisch -> kein Alias
  2. gleicher Trivialname (en, sonst de) wie genau ein Final-Eintrag
  3. Gattungswechsel: gleiches Artepitheton, genau ein Final-Eintrag mit diesem Epitheton
Danach wird MANUAL (Handpflege) ergaenzt; uebrig bleibende Regions-Arten werden ausgegeben.
"""
import csv, json, sys, os
from collections import defaultdict

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")

# Von Hand ergaenzt (alter Name -> Final-Name), mit Begruendung im Handover
MANUAL = {
    # Aeltere eBird-/BirdNET-2.4-Namen, die als Quelle fehlen (in 3.0 final aufgegangen/umbenannt)
    "Accipiter_gentilis": "Astur_gentilis",          # Habicht
    "Acanthis_cabaret": "Acanthis_flammea",          # Alpenbirkenzeisig (3.0 final fuehrt nur Birkenzeisig/Redpoll)
    # T215: GBIF-Schreibweisen/-Namen fuer Schweizer Brutvoegel (Bericht T214), gegen 3.0-final-Labels geprueft
    "Phylloscopus_sibillatrix": "Phylloscopus_sibilatrix",  # Waldlaubsaenger (GBIF-Tippfehler-Variante, doppeltes l)
    "Parus_montanus": "Poecile_montanus",            # Weidenmeise
    "Ixobrychus_minutus": "Botaurus_minutus",        # Zwergdommel
    # T218: GBIF-Name (Bericht T217); Geomodel und 3.0 final fuehren Ardea ibis
    "Bubulcus_ibis": "Ardea_ibis",                   # Kuhreiher
}

def stem(k):
    e = k.split("_", 1)[-1]
    return e[:-2] if len(e) > 4 else e  # grobe Stammform (Genus-Endungen -us/-a/-um)

def u(name): return name.strip().replace(" ", "_")

def main(final_csv):
    final = list(csv.DictReader(open(final_csv, encoding="utf-8-sig")))
    canon = {u(r["scientific_name"]) for r in final}
    by_en = defaultdict(set); by_de = defaultdict(set); by_epi = defaultdict(set)
    for r in final:
        k = u(r["scientific_name"])
        by_en[(r.get("common_name_en") or r["common_name"]).strip().lower()].add(k)
        de = (r.get("common_name_de") or "").strip().lower()
        if de: by_de[de].add(k)
        by_epi[k.split("_", 1)[1] if "_" in k else k].add(k)

    preview = list(csv.DictReader(open(os.path.join(ASSETS, "models", "birdnet_v3_labels.csv"),
                                       encoding="utf-8-sig"), delimiter=";"))
    prev_en = {u(r["sci_name"]): r["com_name"].strip().lower() for r in preview}
    master = json.load(open(os.path.join(ASSETS, "species", "species_master.json"), encoding="utf-8"))
    names = {t["scientific_name"]: t.get("common_names", {}) for t in master["taxa"]}
    regions = json.load(open(os.path.join(ASSETS, "regions", "region_sets.json"), encoding="utf-8"))
    region_species = {s for st in regions["sets"] for s in st["species"]}

    aliases, how = {}, {}
    def try_map(old, en, de):
        cand = set()
        # Trivialname muss eindeutig sein UND das Artepitheton muss (bis auf Endung) uebereinstimmen,
        # sonst entstehen Fehlzuordnungen zwischen Taxa mit gleichem Trivialnamen
        if en and len(by_en.get(en, ())) == 1: cand = by_en[en]; src = "name_en"
        elif de and len(by_de.get(de, ())) == 1: cand = by_de[de]; src = "name_de"
        if cand and stem(next(iter(cand))) != stem(old): cand = set()
        if cand:
            aliases[old] = next(iter(cand)); how[old] = src
            return True
        return False

    for old, en in prev_en.items():
        if old not in canon:
            try_map(old, en if en != old.replace("_", " ").lower() else "", "")
    for old in sorted(region_species):
        if old in canon or old in aliases: continue
        cn = names.get(old, {})
        try_map(old, (cn.get("en") or "").strip().lower(), (cn.get("de") or "").strip().lower())
    for old, new in MANUAL.items():
        assert new in canon, f"MANUAL-Ziel unbekannt: {new}"
        aliases[old] = new; how[old] = "manual"

    out = {"version": 1, "canonical": "BirdNET 3.0 final (scientific_name, underscore)",
           "aliases": dict(sorted(aliases.items()))}
    with open(os.path.join(ASSETS, "species_aliases.json"), "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=0)
    cnt = defaultdict(int)
    for h in how.values(): cnt[h] += 1
    print("Aliase:", len(aliases), dict(cnt))
    for sid in ("central_europe", "ch_breeding", "ch_all"):
        st = next(s for s in regions["sets"] if s["id"] == sid)["species"]
        before = [s for s in st if s not in canon]
        after = [s for s in before if s not in aliases]
        print(f"{sid}: {len(st)} Arten, unmapped vorher={len(before)} nachher={len(after)}")
        print("   Rest:", after)
    rest = sorted(s for s in region_species if s not in canon and s not in aliases)
    print("Region-Arten ohne Zuordnung (gesamt):", len(rest))

if __name__ == "__main__":
    main(sys.argv[1])

#!/usr/bin/env python3
"""T218: Kompakte Indexdatei fuer das BirdNET+ Geomodel V3.0.5.

Liest die Labels-CSV des Geomodels (Spalten ix, birdnet_id, scientific_name, ...) und schreibt
`geomodel_v305_index.tsv`: eine Zeile pro Modellausgang (Zeile n = Index n), Spalten
`birdnet_id<TAB>wissenschaftlicher Name`. Die App gleicht ueber SpeciesKey (Name + Alias) ab.

Aufruf:  python build_geo_index.py <Labels.csv> [<Ausgabe.tsv>]
"""
import csv
import hashlib
import sys


def main() -> None:
    src = sys.argv[1]
    dst = sys.argv[2] if len(sys.argv) > 2 else "geomodel_v305_index.tsv"
    rows = []
    with open(src, newline="", encoding="utf-8") as f:
        for i, r in enumerate(csv.DictReader(f)):
            if int(r["ix"]) != i:
                raise SystemExit(f"Index-Luecke bei Zeile {i}: ix={r['ix']}")
            rows.append((r["birdnet_id"].strip(), r["scientific_name"].strip()))
    with open(dst, "w", encoding="utf-8", newline="\n") as f:
        for bid, name in rows:
            f.write(f"{bid}\t{name}\n")
    data = open(dst, "rb").read()
    print(f"{len(rows)} Zeilen, {len(data)} Bytes, SHA-256 {hashlib.sha256(data).hexdigest()}")


if __name__ == "__main__":
    main()

"""Unit-Tests ohne Netz (unittest). Aufruf: python -I -m unittest test_generate  (im Ordner listen-generator)"""
import json
import os
import re
import tempfile
import types
import unittest

import generate as g

HERE = os.path.dirname(os.path.abspath(__file__))
SAMPLE = json.load(open(os.path.join(HERE, "testdata", "sample.json"), encoding="utf-8"))


class FakeGbif:
    """Ersetzt die Netzabfragen durch die gespeicherten Beispielantworten."""

    def __init__(self):
        self.http = None
        self.syn_calls = []

    def facet(self, field, params, limit=5000):
        if field == "datasetKey":
            return {"63f077c9-96a0-49de-b3ea-2aa565ee1098": 99}, 99
        month = next((v for k, v in params if k == "month"), None)
        if month is None:
            return SAMPLE["totals"], sum(SAMPLE["totals"].values())
        out = {k: v[str(month)] for k, v in SAMPLE["months"].items() if str(month) in v}
        return out, sum(out.values())

    def species(self, key):
        return SAMPLE["species"][key]

    def synonyms(self, key):
        self.syn_calls.append(key)
        return SAMPLE["synonyms"].get(key, [])


def args(out, **kw):
    d = dict(min_records=20, years=20, year_to=2026, season_threshold=0.05, season_min_records=30, out=out)
    d.update(kw)
    return types.SimpleNamespace(**d)


class SeasonTest(unittest.TestCase):
    def test_ranges_simple_and_wrap(self):
        self.assertEqual(g.months_to_ranges({5, 6, 7}), [{"from": "05", "to": "07"}])
        self.assertEqual(g.months_to_ranges({11, 12, 1, 2}), [{"from": "11", "to": "02"}])
        self.assertEqual(g.months_to_ranges({3, 4, 8}), [{"from": "03", "to": "04"}, {"from": "08", "to": "08"}])
        self.assertEqual(g.months_to_ranges(set(range(1, 13))), [])

    def test_pirol(self):
        mc = {int(k): v for k, v in SAMPLE["months"]["5231918"].items()}
        # Apr 254 = 0,4 %, Aug 453 = 0,8 %, Sep 24 -> nur Mai bis Juli ueber 5 %
        self.assertEqual(g.derive_season(mc), [{"from": "05", "to": "07"}])
        # niedrigere Schwelle nimmt April und August mit
        self.assertEqual(g.derive_season(mc, threshold=0.003), [{"from": "04", "to": "08"}])

    def test_winter_species(self):
        mc = {11: 20, 12: 10, 1: 5, 2: 5}
        self.assertEqual(g.derive_season(mc, threshold=0.05, min_records=30), [{"from": "11", "to": "02"}])

    def test_few_records_year_round(self):
        self.assertEqual(g.derive_season({6: 10}, min_records=30), [])

    def test_uniform_is_year_round(self):
        self.assertEqual(g.derive_season({m: 10 for m in range(1, 13)}), [])


class MatcherTest(unittest.TestCase):
    def setUp(self):
        self.m = g.Matcher(SAMPLE["labels"], SAMPLE["aliases"])

    def test_direct(self):
        self.assertEqual(self.m.match("Oriolus oriolus")[:3], ("Oriolus_oriolus", "Pirol", "direct"))

    def test_alias(self):
        self.assertEqual(self.m.match("Parus montanus")[0], "Poecile_montanus")
        self.assertEqual(self.m.match("Accipiter gentilis")[2], "alias")

    def test_synonym_lazy(self):
        called = []
        def syn():
            called.append(1)
            return ["Curruca curruca"]
        self.assertEqual(self.m.match("Sylvia curruca", syn)[0], "Curruca_curruca")
        self.assertEqual(len(called), 1)
        called.clear()
        self.m.match("Oriolus oriolus", syn)  # direkt: Synonyme nicht abgefragt
        self.assertEqual(called, [])

    def test_unknown(self):
        self.assertIsNone(self.m.match("Pholidoptera griseoaptera", []))


class GeometryTest(unittest.TestCase):
    def test_simplify_and_orientation(self):
        # Quadrat im Uhrzeigersinn mit vielen Punkten auf einer Kante
        ring = [(0, 0), (0, 1), (1, 1), (1, 0)]
        dense = [(0, 0)] + [(0, i / 100) for i in range(1, 100)] + [(0, 1), (1, 1), (1, 0), (0, 0)]
        res = g.simplify_polygons([[dense]], max_vertices=50)
        self.assertEqual(len(res), 1)
        r = res[0][0]
        self.assertLessEqual(len(r), 6)
        self.assertEqual(r[0], r[-1])
        self.assertGreater(g._area(r), 0, "Aussenring muss gegen den Uhrzeigersinn laufen")

    def test_wkt(self):
        wkt = g.to_wkt([[[(0, 0), (1, 0), (1, 1), (0, 0)]]])
        self.assertTrue(re.fullmatch(r"MULTIPOLYGON\(\(\(0\.0000 0\.0000,1\.0000 0\.0000,1\.0000 1\.0000,0\.0000 0\.0000\)\)\)", wkt))


class BiogeoTest(unittest.TestCase):
    def test_wkt_length_limit(self):
        import math
        ring = [(8 + math.cos(i / 4000 * 6.2832) * (1 + 0.01 * math.sin(i)), 46 + math.sin(i / 4000 * 6.2832))
                for i in range(4000)]
        ring.append(ring[0])
        geom = {"type": "Polygon", "coordinates": [ring]}

        class FakeHttp:
            def get_json(self, url):
                return {"results": [{"geometry": geom}]}
        wkts = g.biogeo_wkts(FakeHttp(), "jura")
        self.assertEqual(len(wkts), 1)
        self.assertLessEqual(len(wkts[0]), 3200)
        self.assertTrue(wkts[0].startswith("MULTIPOLYGON((("))

    def test_facet_sum(self):
        class F:
            def facet(self, field, params):
                v = dict(params)["geometry"]
                return ({"a": 1, "b": 2} if v == "x" else {"a": 10}), 3 if v == "x" else 10
        d, n = g.facet_sum(F(), "speciesKey", [[("geometry", "x")], [("geometry", "y")]], [])
        self.assertEqual((d, n), ({"a": 11, "b": 2}, 13))


class OutputTest(unittest.TestCase):
    def test_process_writes_v4(self):
        m = g.Matcher(SAMPLE["labels"], SAMPLE["aliases"])
        fake = FakeGbif()
        with tempfile.TemporaryDirectory() as tmp:
            res = g.process(fake, m, ("ch", "Schweiz", "Switzerland", ("country", "CH")), "aves", args(tmp))
            doc = json.load(open(os.path.join(tmp, "lists", "ch", "ch_gbif_aves.json"), encoding="utf-8"))
        self.assertEqual(doc["version"], 4)
        self.assertEqual(doc["id"], "ch_gbif_aves")
        self.assertEqual(doc["taxon_group"], "aves")
        self.assertRegex(doc["updated"], r"[+-]\d\d:\d\d$")  # Offset Pflicht
        # 7002 (15 Nachweise) < min_records ist raus; Anthus vaalensis (5) ebenfalls
        self.assertEqual(doc["species"], sorted(doc["species"]))
        self.assertEqual(doc["species"], ["Astur_gentilis", "Curruca_curruca", "Oriolus_oriolus", "Poecile_montanus"])
        self.assertEqual(list(doc["species_info"]), doc["species"])
        info = doc["species_info"]
        self.assertEqual(info["Oriolus_oriolus"], {"common_name": "Pirol", "season": [{"from": "05", "to": "07"}]})
        self.assertNotIn("season", info["Astur_gentilis"])  # ganzjaehrig
        self.assertNotIn("model_known", info["Oriolus_oriolus"])
        self.assertEqual(res["n"], 4)
        self.assertEqual(res["unknown"], 0)

    def test_unknown_species_kept_and_marked(self):
        m = g.Matcher(SAMPLE["labels"], SAMPLE["aliases"])
        sample_totals = dict(SAMPLE["totals"])
        SAMPLE["totals"]["7002"] = 60  # Pholidoptera: kein Label
        try:
            with tempfile.TemporaryDirectory() as tmp:
                res = g.process(FakeGbif(), m, ("ch", "Schweiz", "Switzerland", ("country", "CH")), "orthoptera", args(tmp))
                doc = json.load(open(os.path.join(tmp, "lists", "ch", "ch_gbif_orthoptera.json"), encoding="utf-8"))
        finally:
            SAMPLE["totals"] = sample_totals
        self.assertIn("Pholidoptera_griseoaptera", doc["species"])
        self.assertEqual(doc["species_info"]["Pholidoptera_griseoaptera"]["model_known"], False)
        self.assertEqual(res["unknown_names"], ["Pholidoptera_griseoaptera"])


if __name__ == "__main__":
    unittest.main()

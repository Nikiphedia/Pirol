"""Tests fuer evaluate.py (ohne Modell, ohne onnxruntime). Lauf: python -m unittest test_evaluate -v"""
import json
import struct
import tempfile
import unittest
from pathlib import Path

import numpy as np

import evaluate as ev


def write_wav(path, samples, sr=48000):
    data = np.asarray(samples, dtype="<i2").tobytes()
    hdr = b"RIFF" + struct.pack("<I", 36 + len(data)) + b"WAVEfmt " + struct.pack(
        "<IHHIIHH", 16, 1, 1, sr, sr * 2, 2, 16) + b"data" + struct.pack("<I", len(data))
    Path(path).write_bytes(hdr + data)


def jl(path, rows):
    Path(path).write_text("\n".join(json.dumps(r) for r in rows) + "\n", encoding="utf-8")


class StatusTest(unittest.TestCase):
    def test_last_status_undo_and_correction(self):
        dets = [{"id": "a"}, {"id": "b"}, {"id": "c"}, {"id": "d"}, {"id": "e"}]
        events = [
            {"detectionId": "a", "status": "CONFIRMED", "verifiedAtMs": 1},
            {"detectionId": "a", "status": "REJECTED", "verifiedAtMs": 2},
            {"detectionId": "a", "status": "CONFIRMED", "verifiedAtMs": 3, "actionType": "UNDO"},
            {"detectionId": "b", "status": "CORRECTED", "correctedSpecies": "Parus major", "verifiedAtMs": 1},
            {"detectionId": "c", "status": "UNVERIFIED", "verifiedAtMs": 1, "actionType": "CLUSTER_MERGED",
             "correctedSpecies": "merged:x|count:2|lastSeenAt:5"},
            {"detectionId": "d", "status": "REJECTED", "verifiedAtMs": 5},
            {"detectionId": "d", "status": "UNVERIFIED", "verifiedAtMs": 9, "actionType": "NOTE_ADDED"},
            {"detectionId": "d", "status": "UNCERTAIN", "verifiedAtMs": 7},
        ]
        st = ev.final_status(dets, events)
        self.assertEqual(st["a"][0], "CONFIRMED")
        self.assertEqual(st["b"], ("CORRECTED", "Parus major"))
        self.assertEqual(st["c"][0], "UNVERIFIED")
        # nach verifiedAtMs sortiert; Metadaten-Events zaehlen nicht
        self.assertEqual(st["d"][0], "UNCERTAIN")
        self.assertEqual(st["e"][0], "UNVERIFIED")

    def test_count(self):
        st = {"a": ("CONFIRMED", None), "b": ("CONFIRMED", None), "c": ("REJECTED", None),
              "d": ("CORRECTED", "x"), "e": ("UNCERTAIN", None), "f": ("UNVERIFIED", None)}
        c = ev.count_statuses(st)
        self.assertEqual((c["CONFIRMED"], c["REJECTED"], c["CORRECTED"], c["UNCERTAIN"], c["UNVERIFIED"]),
                         (2, 1, 1, 1, 1))

    def test_merge_windows(self):
        events = [
            {"detectionId": "a", "status": "UNVERIFIED", "actionType": "CLUSTER_MERGED", "verifiedAtMs": 1,
             "correctedSpecies": "merged:x|count:2|lastSeenAt:5|conf:0.8|start:12.5"},
            {"detectionId": "a", "status": "UNVERIFIED", "actionType": "CLUSTER_MERGED", "verifiedAtMs": 2,
             "correctedSpecies": "merged:y|count:3|lastSeenAt:6"},
        ]
        self.assertEqual(ev.merged_windows(events), {"a": [12.5]})


class WindowTest(unittest.TestCase):
    def make_session(self, d):
        sr = 48000
        seg1 = np.arange(0, 4 * sr) % 30000          # 4 s
        seg2 = (np.arange(0, 4 * sr) % 30000) + 1000  # 4 s
        write_wav(d / "recording.wav", seg1, sr)
        write_wav(d / "recording-002.wav", seg2, sr)
        meta = {"sampleRate": sr, "sessionRelativeOffsets": True,
                "recordingSegments": [{"fileName": "recording.wav", "startSec": 0.0, "durationSec": 4.0},
                                      {"fileName": "recording-002.wav", "startSec": 4.0, "durationSec": 4.0}]}
        (d / "session.json").write_text(json.dumps(meta), encoding="utf-8")
        return meta, seg1, seg2, sr

    def test_window_inside_and_across_segments(self):
        with tempfile.TemporaryDirectory() as t:
            d = Path(t)
            meta, s1, s2, sr = self.make_session(d)
            rd = ev.SessionAudio(d, meta)
            w = rd.window(0.5, 3 * sr)
            self.assertTrue(np.array_equal(w, s1[sr // 2: sr // 2 + 3 * sr].astype("float32") / 32768.0))
            w = rd.window(2.0, 3 * sr)  # 2 s aus Segment 1, 1 s aus Segment 2
            exp = np.concatenate([s1[2 * sr:], s2[:sr]]).astype("float32") / 32768.0
            self.assertTrue(np.array_equal(w, exp))
            self.assertEqual(len(w), 3 * sr)

    def test_window_beyond_end_is_none(self):
        with tempfile.TemporaryDirectory() as t:
            d = Path(t)
            meta, *_ = self.make_session(d)
            rd = ev.SessionAudio(d, meta)
            self.assertIsNone(rd.window(20.0, 3 * 48000))

    def test_stale_header_uses_file_size(self):
        with tempfile.TemporaryDirectory() as t:
            p = Path(t) / "x.wav"
            write_wav(p, np.arange(1000), 48000)
            b = bytearray(p.read_bytes())
            b[40:44] = struct.pack("<I", 100)  # veralteter data-Header
            p.write_bytes(bytes(b))
            sr, n, off = ev.wav_info(p)
            self.assertEqual((sr, n), (48000, 1000))


class MetricsTest(unittest.TestCase):
    def test_auc(self):
        self.assertAlmostEqual(ev.auc([0.9, 0.8, 0.7], [0.1, 0.2, 0.3]), 1.0)
        self.assertAlmostEqual(ev.auc([0.1, 0.2], [0.9, 0.8]), 0.0)
        self.assertAlmostEqual(ev.auc([0.5, 0.5], [0.5, 0.5]), 0.5)
        self.assertAlmostEqual(ev.auc([0.8, 0.4], [0.6, 0.2]), 0.75)
        self.assertIsNone(ev.auc([], [0.1]))

    def test_rates(self):
        pos = [0.9, 0.8, 0.7, 0.2, 0.1]   # richtige Detektionen, 2.4-Score
        neg = [0.05, 0.1, 0.6, 0.02]      # falsche
        hit, fa = ev.rates(pos, neg, 0.3)
        self.assertAlmostEqual(hit, 3 / 4)
        self.assertAlmostEqual(fa, 2 / 5)

    def test_decide_go_by_auc(self):
        rows = []
        # 3.0 trennt schlecht, 2.4 trennt perfekt
        for i in range(60):
            rows.append(ev.Row("s", f"p{i}", "x", 1, 0.5 + (i % 5) * 0.05, 0.9, True))
        for i in range(20):
            rows.append(ev.Row("s", f"n{i}", "x", 0, 0.5 + (i % 5) * 0.05, 0.02, True))
        res = ev.summarize(rows, min_pos=50, min_neg=15)
        self.assertGreaterEqual(res["auc_combined"] - res["auc_conf3"], 0.05)
        self.assertEqual(res["decision"], "GO")

    def test_decide_no_go_and_too_few(self):
        rows = [ev.Row("s", f"p{i}", "x", 1, 0.9, 0.9, True) for i in range(10)]
        rows += [ev.Row("s", f"n{i}", "x", 0, 0.3, 0.9, True) for i in range(3)]
        self.assertEqual(ev.summarize(rows)["decision"], "ZU WENIG DATEN")
        res = ev.summarize(rows, min_pos=5, min_neg=2)
        self.assertEqual(res["decision"], "NO-GO")

    def test_not_checkable_blocks_go(self):
        rows = [ev.Row("s", f"p{i}", "x", 1, 0.6, 0.9, True) for i in range(40)]
        rows += [ev.Row("s", f"n{i}", "x", 0, 0.6, 0.0, True) for i in range(20)]
        rows += [ev.Row("s", f"u{i}", "x", 1, 0.6, None, False) for i in range(20)]
        res = ev.summarize(rows, min_pos=10, min_neg=10)
        self.assertGreater(res["not_checkable_share"], 0.10)
        self.assertEqual(res["decision"], "NO-GO")


class AliasTest(unittest.TestCase):
    def test_species_key(self):
        al = {"accipiter_gentilis": "astur_gentilis"}
        self.assertEqual(ev.species_key("Accipiter gentilis", al), "astur_gentilis")
        self.assertEqual(ev.species_key("  Parus_major ", al), "parus_major")

    def test_label_index_and_lookup(self):
        al = {"accipiter_gentilis": "astur_gentilis"}
        labels = ["Accipiter gentilis", "Parus major"]
        idx = ev.label_index(labels, al)
        self.assertEqual(idx["astur_gentilis"], 0)
        self.assertEqual(idx.get(ev.species_key("Astur_gentilis", al)), 0)
        self.assertIsNone(idx.get(ev.species_key("Turdus merula", al)))

    def test_load_labels(self):
        with tempfile.TemporaryDirectory() as t:
            p = Path(t) / "l.csv"
            p.write_text("scientific_name,common_name\nParus major,Kohlmeise\nTurdus merula,Amsel\n", encoding="utf-8")
            self.assertEqual(ev.load_labels(p), ["Parus major", "Turdus merula"])
            q = Path(t) / "l.txt"
            q.write_text("Parus major_Great Tit\nTurdus merula_Eurasian Blackbird", encoding="utf-8")
            self.assertEqual(ev.load_labels(q), ["Parus major", "Turdus merula"])


class SessionCountTest(unittest.TestCase):
    def test_load_and_count(self):
        with tempfile.TemporaryDirectory() as t:
            d = Path(t) / "2026-01-01_abc"
            d.mkdir()
            (d / "session.json").write_text("{}", encoding="utf-8")
            jl(d / "detections.jsonl", [{"id": "a", "scientificName": "Parus major", "confidence": 0.9},
                                        {"id": "b", "scientificName": "Turdus merula", "confidence": 0.6}])
            jl(d / "verifications.jsonl", [{"detectionId": "a", "status": "CONFIRMED", "verifiedAtMs": 1}])
            s = ev.load_session(d)
            self.assertEqual(ev.count_statuses(s.status)["CONFIRMED"], 1)
            self.assertEqual(ev.count_statuses(s.status)["UNVERIFIED"], 1)
            self.assertEqual([p.name for p in ev.find_sessions([Path(t)])], ["2026-01-01_abc"])


class EvaluateFlowTest(unittest.TestCase):
    def test_evaluate_with_fake_model(self):
        class Fake:
            labels = ["Astur gentilis", "Parus major"]
            sample_rate, window_sec, n_samples = 48000, 3.0, 144000

            def scores(self, wave):
                return np.array([0.8, 0.1], dtype="float32")

        with tempfile.TemporaryDirectory() as t:
            d = Path(t)
            write_wav(d / "recording.wav", np.ones(5 * 48000) * 1000, 48000)
            (d / "session.json").write_text(json.dumps({"sampleRate": 48000, "sessionRelativeOffsets": True}),
                                            encoding="utf-8")
            jl(d / "detections.jsonl", [
                {"id": "a", "scientificName": "Accipiter gentilis", "confidence": 0.9, "chunkStartSec": 1.0},
                {"id": "b", "scientificName": "Turdus merula", "confidence": 0.8, "chunkStartSec": 0.0}])
            jl(d / "verifications.jsonl", [{"detectionId": "a", "status": "CONFIRMED", "verifiedAtMs": 1},
                                           {"detectionId": "b", "status": "REJECTED", "verifiedAtMs": 1}])
            rows = ev.evaluate_sessions([ev.load_session(d)], Fake(), {"accipiter_gentilis": "astur_gentilis"}, False)
            by = {r.det_id: r for r in rows}
            self.assertTrue(by["a"].checkable)
            self.assertAlmostEqual(by["a"].score2, 0.8, places=5)
            self.assertFalse(by["b"].checkable)


if __name__ == "__main__":
    unittest.main()

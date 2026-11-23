#!/usr/bin/env python3
"""T213: Offline-Auswertung Zweitmodell (BirdNET 2.4) auf verifizierten PIROL-Sessions.

Modi:
  --count      nur zaehlen (Status je Detektion); braucht weder Modell noch onnxruntime
  --evaluate   beste Fenster aus der WAV schneiden, Zweitmodell rechnen, Kennzahlen + Go/No-Go

Sessions werden nur gelesen. Konzept und Go-Kriterium: Bericht_T150_Zweitmodell.md.
Aufrufbeispiele: README.md.
"""
import argparse
import csv
import json
import math
import sys
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Optional, Tuple

DEFAULT_ALIASES = Path(__file__).resolve().parents[2] / "app/src/main/assets/species_aliases.json"

# Status-tragende Events: actionType null (Legacy) oder einer dieser Werte.
# Alles andere (CLUSTER_MERGED, COUNT_CHANGED, NOTE_ADDED, ...) sind Metadaten und aendern den Status nicht.
STATUS_ACTIONS = {None, "CONFIRMED", "REJECTED", "UNCERTAIN", "CORRECTED", "REPLACED", "UNDO"}
STATUS_ORDER = ["CONFIRMED", "REJECTED", "CORRECTED", "UNCERTAIN", "UNVERIFIED"]

THRESHOLDS = [0.05, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7]
PEAK_TARGET = 0.95
HIGHPASS = True  # wie App-Standard (highpassEnabled = true); --no-highpass schaltet ab


# ---------------------------------------------------------------- Sessions

def final_status(detections: List[dict], events: List[dict]) -> Dict[str, Tuple[str, Optional[str]]]:
    """Letzter Status je Detektion (nach verifiedAtMs; UNDO traegt den wiederhergestellten Status)."""
    result = {d["id"]: ("UNVERIFIED", None) for d in detections}
    for e in sorted(events, key=lambda x: x.get("verifiedAtMs", 0)):
        if e.get("actionType") not in STATUS_ACTIONS:
            continue
        did = e.get("detectionId")
        if did in result:
            result[did] = (e.get("status", "UNVERIFIED"), e.get("correctedSpecies"))
    return result


def merged_windows(events: List[dict]) -> Dict[str, List[float]]:
    """Startzeiten absorbierter Fenster je Cluster (CLUSTER_MERGED, Feld start: ab T181)."""
    out: Dict[str, List[float]] = {}
    for e in events:
        if e.get("actionType") != "CLUSTER_MERGED":
            continue
        for part in (e.get("correctedSpecies") or "").split("|"):
            if part.startswith("start:"):
                try:
                    out.setdefault(e["detectionId"], []).append(float(part[6:]))
                except ValueError:
                    pass
    return out


def count_statuses(status: Dict[str, Tuple[str, Optional[str]]]) -> Counter:
    c = Counter()
    for st, _ in status.values():
        c[st if st in STATUS_ORDER else "OTHER"] += 1
    return c


@dataclass
class Session:
    dir: Path
    meta: dict
    detections: List[dict]
    events: List[dict]
    status: Dict[str, Tuple[str, Optional[str]]] = field(default_factory=dict)


def _read_jsonl(path: Path) -> List[dict]:
    if not path.exists():
        return []
    rows = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line:
            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError:
                print(f"WARNUNG: defekte Zeile in {path}", file=sys.stderr)
    return rows


def load_session(d: Path) -> Session:
    meta = json.loads((d / "session.json").read_text(encoding="utf-8"))
    dets = _read_jsonl(d / "detections.jsonl")
    evs = _read_jsonl(d / "verifications.jsonl")
    return Session(d, meta, dets, evs, final_status(dets, evs))


def find_sessions(paths: List[Path]) -> List[Path]:
    found = []
    for p in paths:
        if (p / "session.json").exists():
            found.append(p)
        else:
            found.extend(sorted(x.parent for x in p.rglob("session.json")))
    return found


# ---------------------------------------------------------------- Audio

def wav_info(path: Path) -> Tuple[int, int, int]:
    """(Abtastrate, Anzahl Samples, Byte-Offset der Daten). Datenlaenge aus der Dateigroesse
    (RIFF-Header kann nach Prozess-Kill veraltet sein, T130). 16-bit PCM Mono."""
    import struct
    size = path.stat().st_size
    with open(path, "rb") as f:
        head = f.read(12)
        if head[:4] != b"RIFF" or head[8:12] != b"WAVE":
            raise ValueError(f"keine WAV-Datei: {path}")
        sr = bits = ch = None
        while True:
            ch_hdr = f.read(8)
            if len(ch_hdr) < 8:
                raise ValueError(f"kein data-Chunk: {path}")
            cid, csize = ch_hdr[:4], struct.unpack("<I", ch_hdr[4:])[0]
            if cid == b"fmt ":
                _fmt, ch, sr, _br, _ba, bits = struct.unpack("<HHIIHH", f.read(16))
                f.seek(csize - 16, 1)
            elif cid == b"data":
                off = f.tell()
                if bits != 16 or ch != 1:
                    raise ValueError(f"erwartet 16-bit Mono: {path}")
                return sr, (size - off) // 2, off
            else:
                f.seek(csize + (csize & 1), 1)


class SessionAudio:
    """Liest Fenster aus den Segmenten einer Session (session-relative Offsets)."""

    def __init__(self, d: Path, meta: dict):
        self.segments = []  # (startSample, nSamples, path, dataOffset)
        segs = meta.get("recordingSegments")
        if not segs:
            files = sorted(d.glob("recording*.wav"))
            segs = []
            pos = 0.0
            for f in files:
                sr_, n_, _ = wav_info(f)
                segs.append({"fileName": f.name, "startSec": pos})
                pos += n_ / sr_
        self.sr = 0
        for s in segs:
            p = d / s["fileName"]
            if not p.exists():
                continue
            sr, n, off = wav_info(p)
            self.sr = self.sr or sr
            self.segments.append((round(s["startSec"] * sr), n, p, off))

    def window(self, start_sec: float, n: int):
        """n Samples ab start_sec als float32 (-1..1), ueber Segmentgrenzen; None wenn nichts vorhanden."""
        import numpy as np
        s = round(start_sec * self.sr)
        out = np.zeros(n, dtype="float32")
        covered = 0
        for seg_start, seg_n, p, off in self.segments:
            a, b = max(s, seg_start), min(s + n, seg_start + seg_n)
            if b <= a:
                continue
            raw = np.fromfile(p, dtype="<i2", count=b - a, offset=off + 2 * (a - seg_start))
            out[a - s:a - s + len(raw)] = raw.astype("float32") / 32768.0
            covered += len(raw)
        return out if covered else None


# ---------------------------------------------------------------- Arten

def species_key(name: str, aliases: Dict[str, str]) -> str:
    """Wie SpeciesKey.of(): trimmen, Leerzeichen -> Unterstrich, klein, Alias aufloesen."""
    n = name.strip().replace(" ", "_").lower()
    return aliases.get(n, n)


def load_aliases(path: Path) -> Dict[str, str]:
    if not path.exists():
        print(f"WARNUNG: Alias-Datei fehlt: {path}", file=sys.stderr)
        return {}
    raw = json.loads(path.read_text(encoding="utf-8")).get("aliases", {})
    norm = lambda s: s.strip().replace(" ", "_").lower()
    return {norm(k): norm(v) for k, v in raw.items()}


def label_index(labels: List[str], aliases: Dict[str, str]) -> Dict[str, int]:
    return {species_key(n, aliases): i for i, n in enumerate(labels)}


def load_labels(path: Path, delimiter: Optional[str] = None, column=None) -> List[str]:
    """Wissenschaftliche Namen je Klasse. .txt = BirdNET 'Wiss_Common' (Trennung am ersten _),
    sonst CSV mit Kopfzeile (Spalte scientific_name o.ae., sonst Spalte 0)."""
    text = path.read_text(encoding="utf-8-sig")
    lines = [l for l in text.splitlines() if l.strip()]
    if path.suffix.lower() == ".txt":
        return [l.split("_", 1)[0].strip() for l in lines]
    if delimiter is None:
        first = lines[0]
        delimiter = max([",", ";", "\t"], key=first.count)
    rows = list(csv.reader(lines, delimiter=delimiter))
    header = rows[0]
    idx = 0
    has_header = False
    if isinstance(column, int):
        idx = column
    elif column and column in header:
        idx, has_header = header.index(column), True
    else:
        for i, h in enumerate(header):
            if "scientific" in h.lower():
                idx, has_header = i, True
                break
    return [r[idx].strip() for r in (rows[1:] if has_header else rows)]


# ---------------------------------------------------------------- Kennzahlen

@dataclass
class Row:
    session: str
    det_id: str
    species: str
    label: int                   # 1 = richtig (CONFIRMED), 0 = falsch (REJECTED/CORRECTED)
    conf3: float                 # 3.0-Konfidenz
    score2: Optional[float]      # Zweitmodell-Score der Art
    checkable: bool
    window_start: float = 0.0
    top1_species: str = ""
    top1_score: Optional[float] = None
    status: str = ""


def auc(pos: List[float], neg: List[float]) -> Optional[float]:
    """AUC (Mann-Whitney, Gleichstand = 0.5)."""
    if not pos or not neg:
        return None
    wins = 0.0
    for p in pos:
        for n in neg:
            wins += 1.0 if p > n else 0.5 if p == n else 0.0
    return wins / (len(pos) * len(neg))


def rates(pos_scores: List[float], neg_scores: List[float], thr: float) -> Tuple[Optional[float], Optional[float]]:
    """(Fehler-Trefferquote, Fehlalarmquote) bei 'nicht bestaetigt' = Score < thr."""
    hit = sum(s < thr for s in neg_scores) / len(neg_scores) if neg_scores else None
    fa = sum(s < thr for s in pos_scores) / len(pos_scores) if pos_scores else None
    return hit, fa


def _logit(p: float) -> float:
    p = min(max(p, 1e-6), 1 - 1e-6)
    return math.log(p / (1 - p))


def summarize(rows: List[Row], min_pos: int = 200, min_neg: int = 50) -> dict:
    labeled = [r for r in rows if r.label in (0, 1)]
    chk = [r for r in labeled if r.checkable and r.score2 is not None]
    res: dict = {"n_labeled": len(labeled), "n_checkable": len(chk),
                 "n_pos": sum(r.label == 1 for r in labeled), "n_neg": sum(r.label == 0 for r in labeled),
                 "not_checkable_share": (1 - len(chk) / len(labeled)) if labeled else 0.0}
    pos = [r for r in chk if r.label == 1]
    neg = [r for r in chk if r.label == 0]
    res["n_pos_checkable"], res["n_neg_checkable"] = len(pos), len(neg)
    res["auc_conf3"] = auc([r.conf3 for r in pos], [r.conf3 for r in neg])
    res["auc_score2"] = auc([r.score2 for r in pos], [r.score2 for r in neg])
    comb = lambda r: _logit(r.conf3) + _logit(r.score2)
    res["auc_combined"] = auc([comb(r) for r in pos], [comb(r) for r in neg])

    def table(subset_pos, subset_neg):
        return [(t, *rates([r.score2 for r in subset_pos], [r.score2 for r in subset_neg], t)) for t in THRESHOLDS]

    res["table_all"] = table(pos, neg)
    hi_pos = [r for r in pos if r.conf3 > 0.7]
    hi_neg = [r for r in neg if r.conf3 > 0.7]
    res["n_pos_hi"], res["n_neg_hi"] = len(hi_pos), len(hi_neg)
    res["table_hi"] = table(hi_pos, hi_neg)

    auc_ok = (res["auc_combined"] is not None and res["auc_conf3"] is not None
              and res["auc_combined"] - res["auc_conf3"] >= 0.05)
    quota_thr = [t for t, hit, fa in res["table_hi"] if hit is not None and fa is not None
                 and hit >= 1 / 3 and fa <= 0.10]
    res["crit_auc"], res["crit_quota"] = auc_ok, bool(quota_thr)
    res["quota_thresholds"] = quota_thr
    # Schwellenvorschlag: Youden (Trefferquote - Fehlalarmquote) ueber alle Fenster
    best = max(((hit - fa, t) for t, hit, fa in res["table_all"] if hit is not None and fa is not None),
               default=None)
    res["suggested_threshold"] = best[1] if best else None
    if res["n_pos"] < min_pos or res["n_neg"] < min_neg:
        res["decision"] = "ZU WENIG DATEN"
    elif (auc_ok or quota_thr) and res["not_checkable_share"] <= 0.10:
        res["decision"] = "GO"
    else:
        res["decision"] = "NO-GO"
    return res


def format_summary(res: dict, min_pos: int, min_neg: int) -> str:
    f = lambda v: "-" if v is None else f"{v:.3f}"
    p = lambda v: "-" if v is None else f"{100 * v:.0f} %"
    out = [
        f"Verifiziert: {res['n_pos']} richtig, {res['n_neg']} falsch (Soll mind. {min_pos} / {min_neg}); "
        f"pruefbar: {res['n_pos_checkable']} / {res['n_neg_checkable']}",
        f"Nicht pruefbar (Art fehlt im Zweitmodell): {100 * res['not_checkable_share']:.1f} % (Soll <= 10 %)",
        f"AUC 3.0-Konfidenz allein: {f(res['auc_conf3'])}",
        f"AUC Zweitmodell-Score:    {f(res['auc_score2'])}",
        f"AUC Kombination (Logit-Summe): {f(res['auc_combined'])}",
        "",
        "Schwelle | alle: Fehler-Treffer / Fehlalarm | 3.0-Konfidenz > 0.7: Fehler-Treffer / Fehlalarm",
    ]
    for (t, h, fa), (_, h2, fa2) in zip(res["table_all"], res["table_hi"]):
        out.append(f"  {t:<5}  |  {p(h):>6} / {p(fa):>6}  |  {p(h2):>6} / {p(fa2):>6}")
    out += [
        f"(Teilmenge > 0.7: {res['n_pos_hi']} richtig, {res['n_neg_hi']} falsch)",
        f"Schwellenvorschlag (Youden): {res['suggested_threshold']}",
        f"Kriterium AUC +0.05: {'ja' if res['crit_auc'] else 'nein'}; "
        f"Kriterium 1/3 der Fehler bei <= 10 % Fehlalarm: "
        f"{'ja, Schwellen ' + str(res['quota_thresholds']) if res['crit_quota'] else 'nein'}",
        f"ENTSCHEIDUNG: {res['decision']}",
    ]
    return "\n".join(out)


# ---------------------------------------------------------------- Modell

class SecondModel:
    def __init__(self, onnx_path: Path, labels: List[str], sample_rate=48000, window_sec=3.0,
                 transform="sigmoid", input_name=None, output_name=None):
        import onnxruntime as ort  # nur fuer --evaluate noetig
        self.sess = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
        self.labels = labels
        self.sample_rate, self.window_sec, self.transform = sample_rate, window_sec, transform
        self.input_name = input_name or self.sess.get_inputs()[0].name
        outs = [o.name for o in self.sess.get_outputs()]
        self.output_name = output_name if output_name in outs else outs[0]
        self.n_samples = round(sample_rate * window_sec)

    def scores(self, wave):
        import numpy as np
        x = wave.astype("float32")[None, :]
        y = np.asarray(self.sess.run([self.output_name], {self.input_name: x})[0]).reshape(-1)
        if self.transform == "sigmoid":
            y = 1.0 / (1.0 + np.exp(-y))
        elif self.transform == "softmax":
            e = np.exp(y - y.max())
            y = e / e.sum()
        if len(y) != len(self.labels):
            raise ValueError(f"Ausgang {len(y)} Klassen, Labels {len(self.labels)}")
        return y


def highpass(wave, sr: int, cutoff: float = 200.0):
    """Biquad-Hochpass wie AudioDspUtils.highpassFilter der App (Butterworth Q=0.7071, Direct Form II transposed)."""
    import numpy as np
    w0 = 2.0 * math.pi * cutoff / sr
    alpha = math.sin(w0) / (2.0 * 0.7071)
    c = math.cos(w0)
    a0 = 1 + alpha
    b0 = (1 + c) / 2 / a0
    b1 = -(1 + c) / a0
    a1 = -2 * c / a0
    a2 = (1 - alpha) / a0
    z1 = z2 = 0.0
    out = np.empty(len(wave), dtype="float32")
    for i, x in enumerate(wave.tolist()):
        y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b0 * x - a2 * y
        out[i] = y
    return out


def prepare_window(wave, src_sr: int, dst_sr: int, n_dst: int, use_highpass: bool = True):
    """Wie InferenceWorker der App: Resampling (linear, nur bei anderer Rate), Hochpass 200 Hz, Peak-Normalisierung
    auf 0.95 (nur anheben, nie absenken), Limiter."""
    import numpy as np
    if src_sr != dst_sr:
        n_src = len(wave)
        wave = np.interp(np.linspace(0, n_src - 1, round(n_src * dst_sr / src_sr)), np.arange(n_src), wave)
    wave = np.asarray(wave, dtype="float32")[:n_dst]
    if len(wave) < n_dst:
        wave = np.pad(wave, (0, n_dst - len(wave)))
    if use_highpass:
        wave = highpass(wave, dst_sr)
    peak = float(np.abs(wave).max())
    if peak >= 1e-6 and PEAK_TARGET / peak > 1.0:
        wave = wave * (PEAK_TARGET / peak)
    return np.clip(wave, -1.0, 1.0).astype("float32")


def load_model_from_args(args) -> SecondModel:
    sr, win, transform, in_name, out_name = 48000, 3.0, "sigmoid", None, None
    labels_path, delim, col = args.labels, None, None
    if args.profile:
        prof = json.loads(Path(args.profile).read_text(encoding="utf-8"))
        inp = prof.get("input", {})
        sr, win, in_name = inp.get("sampleRate", sr), inp.get("windowSec", win), inp.get("name")
        for o in prof.get("outputs", []):
            if o.get("role") == "scores":
                transform, out_name = o.get("transform", transform), o.get("name")
        spec = prof.get("labels", {}).get("species", {})
        if not labels_path and spec.get("file"):
            labels_path = str(Path(args.profile).parent / spec["file"])
            delim, col = spec.get("delimiter"), spec.get("scientificColumn")
    if not labels_path:
        sys.exit("FEHLER: --labels (oder --profile mit Label-Datei) fehlt")
    labels = load_labels(Path(labels_path), delim, col)
    return SecondModel(Path(args.model), labels, sr, win, transform, in_name, out_name)


# ---------------------------------------------------------------- Auswertung

def label_for(status: str) -> Optional[int]:
    return 1 if status == "CONFIRMED" else 0 if status in ("REJECTED", "CORRECTED") else None


def evaluate_sessions(sessions: List[Session], model, aliases: Dict[str, str], with_merged: bool) -> List[Row]:
    idx = label_index(model.labels, aliases)
    rows: List[Row] = []
    for s in sessions:
        # Flag steht nur bei rotierten Sessions; Einzeldatei: Offsets identisch zur Wanduhr
        if len(s.meta.get("recordingSegments") or []) > 1 and not s.meta.get("sessionRelativeOffsets"):
            print(f"WARNUNG: {s.dir.name}: alte Session ohne sessionRelativeOffsets, Offsets evtl. falsch",
                  file=sys.stderr)
        audio = SessionAudio(s.dir, s.meta)
        extra = merged_windows(s.events) if with_merged else {}
        for d in s.detections:
            st = s.status.get(d["id"], ("UNVERIFIED", None))[0]
            label = label_for(st)
            if label is None or d.get("manualEntry"):
                continue
            species = d.get("scientificName", "")
            conf3 = float(d.get("confidence", 0.0))
            col = idx.get(species_key(species, aliases))
            if col is None:
                rows.append(Row(s.dir.name, d["id"], species, label, conf3, None, False, status=st))
                continue
            first = d.get("bestStartSec")
            starts = [first if first is not None else d.get("chunkStartSec", 0.0)] + extra.get(d["id"], [])
            best = None  # (score, start, scores)
            for t in starts:
                w = audio.window(t, round(audio.sr * model.window_sec))
                if w is None:
                    continue
                sc = model.scores(prepare_window(w, audio.sr, model.sample_rate, model.n_samples, HIGHPASS))
                if best is None or sc[col] > best[0]:
                    best = (float(sc[col]), t, sc)
            if best is None:
                print(f"WARNUNG: kein Audio fuer {s.dir.name}/{d['id']}", file=sys.stderr)
                continue
            top = int(best[2].argmax())
            rows.append(Row(s.dir.name, d["id"], species, label, conf3, best[0], True, best[1],
                            model.labels[top], float(best[2][top]), st))
    return rows


def write_csv(rows: List[Row], path: Path):
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["session", "detection_id", "species", "status", "label", "conf3", "window_start_sec",
                    "checkable", "score2", "top1_species", "top1_score"])
        for r in rows:
            w.writerow([r.session, r.det_id, r.species, r.status, r.label, f"{r.conf3:.4f}", r.window_start,
                        int(r.checkable), "" if r.score2 is None else f"{r.score2:.4f}", r.top1_species,
                        "" if r.top1_score is None else f"{r.top1_score:.4f}"])


def run_count(sessions: List[Session]):
    total = Counter()
    print(f"{'Session':<34}" + "".join(f"{k[:6]:>8}" for k in STATUS_ORDER) + f"{'Summe':>8}")
    for s in sessions:
        c = count_statuses(s.status)
        total.update(c)
        print(f"{s.dir.name:<34}" + "".join(f"{c[k]:>8}" for k in STATUS_ORDER) + f"{sum(c.values()):>8}")
    print(f"{'SUMME (' + str(len(sessions)) + ' Sessions)':<34}"
          + "".join(f"{total[k]:>8}" for k in STATUS_ORDER) + f"{sum(total.values()):>8}")
    print(f"Wahrheit: richtig = {total['CONFIRMED']}, falsch = {total['REJECTED'] + total['CORRECTED']} "
          f"(Soll fuer eine Aussage ca. 200 / 50)")


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("sessions", nargs="+", type=Path, help="Session-Ordner oder Ordner, der Sessions enthaelt")
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--count", action="store_true", help="nur Status zaehlen (ohne Modell)")
    g.add_argument("--evaluate", action="store_true", help="Zweitmodell rechnen und auswerten")
    ap.add_argument("--model", help="Zweitmodell (ONNX)")
    ap.add_argument("--labels", help="Label-Datei des Zweitmodells (CSV oder BirdNET-TXT)")
    ap.add_argument("--profile", help="optional: pirol-model/1 Profil-JSON (Abtastrate, Fenster, Transform, Labels)")
    ap.add_argument("--aliases", type=Path, default=DEFAULT_ALIASES, help="species_aliases.json")
    ap.add_argument("--csv", type=Path, default=Path("zweitmodell_eval.csv"), help="Ausgabe je Detektion")
    ap.add_argument("--with-merged", action="store_true",
                    help="zusaetzlich absorbierte Cluster-Fenster (CLUSTER_MERGED start:) rechnen, Maximum zaehlt")
    ap.add_argument("--no-highpass", action="store_true", help="200-Hz-Hochpass der App-Vorverarbeitung auslassen")
    ap.add_argument("--min-pos", type=int, default=200)
    ap.add_argument("--min-neg", type=int, default=50)
    args = ap.parse_args(argv)
    global HIGHPASS
    HIGHPASS = not args.no_highpass

    dirs = find_sessions(args.sessions)
    if not dirs:
        sys.exit("FEHLER: keine Session (session.json) gefunden")
    sessions = [load_session(d) for d in dirs]
    if args.count:
        run_count(sessions)
        return
    if not args.model:
        sys.exit("FEHLER: --model fehlt")
    model = load_model_from_args(args)
    rows = evaluate_sessions(sessions, model, load_aliases(args.aliases), args.with_merged)
    write_csv(rows, args.csv)
    print(f"{len(rows)} Detektionen ausgewertet, Details: {args.csv}\n")
    print(format_summary(summarize(rows, args.min_pos, args.min_neg), args.min_pos, args.min_neg))


if __name__ == "__main__":
    main()

"""BSG-BAT v0.21 -> PIROL-Profilpaket (pirol-model/1) mit Frontend im ONNX (T291).

Eingang: Original-Checkpoint-ONNX von tphakala/BSG-BAT (Eingang `spectrogram [b,1,512,128]`,
Ausgang `logits [b,22]`). Ausgabe im --out-dir:
    <id>.onnx            Eingang `audio` [1, 393472] (384 kHz, 1.024667 s), Ausgang `scores` [1, 22] (Sigmoid)
    <id>.pirol.json      Profil pirol-model/1
    <id>_species.csv     22 Klassen (21 Arten + Background), Unterstrich-Format

Aufruf (Python mit -I, nur numpy + onnx):
    python -I tools/bats/build_bsgbat_package.py <bsgbat_v0.21_r1.onnx> --out-dir <dir> [--fp16] [--variant conv|stft]
"""
import argparse
import hashlib
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import onnx  # noqa: E402

import bsgbat_frontend as fe  # noqa: E402

# Reihenfolge = Modellausgang (bsgbat_labels.txt, README tphakala/BSG-BAT)
CLASSES = [
    ("Barbastella_barbastellus", "Mopsfledermaus"),
    ("Eptesicus_nilssonii", "Nordfledermaus"),
    ("Eptesicus_serotinus", "Breitflügelfledermaus"),
    ("Hypsugo_savii", "Alpenfledermaus"),
    ("Miniopterus_schreibersii", "Langflügelfledermaus"),
    ("Myotis_alcathoe", "Nymphenfledermaus"),
    ("Myotis_crypticus", "Kryptische Fransenfledermaus"),
    ("Myotis_daubentonii", "Wasserfledermaus"),
    ("Nyctalus_leisleri", "Kleiner Abendsegler"),
    ("Nyctalus_noctula", "Grosser Abendsegler"),
    ("Pipistrellus_kuhlii", "Weissrandfledermaus"),
    ("Pipistrellus_nathusii", "Rauhautfledermaus"),
    ("Pipistrellus_pipistrellus", "Zwergfledermaus"),
    ("Pipistrellus_pygmaeus", "Mückenfledermaus"),
    ("Plecotus_auritus", "Braunes Langohr"),
    ("Plecotus_austriacus", "Graues Langohr"),
    ("Rhinolophus_euryale", "Mittelmeer-Hufeisennase"),
    ("Rhinolophus_ferrumequinum", "Grosse Hufeisennase"),
    ("Rhinolophus_hipposideros", "Kleine Hufeisennase"),
    ("Tadarida_teniotis", "Bulldoggfledermaus"),
    ("Vespertilio_murinus", "Zweifarbfledermaus"),
    ("Background", "Hintergrund"),
]

LICENSE = ("CC BY 4.0 (BSG-BAT v0.21, bsg-bat team 2025, doi:10.5281/zenodo.15495676; "
           "ONNX-Export tphakala/BSG-BAT, Checkpoint r1; Frontend PIROL T291)")


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def check_labels(original_labels):
    """Vergleich mit bsgbat_labels.txt des Downloads (falls angegeben)."""
    with open(original_labels, encoding="utf-8") as f:
        names = [l.strip().replace(" ", "_") for l in f if l.strip()]
    if names != [c[0] for c in CLASSES]:
        sys.exit(f"Labelreihenfolge weicht ab: {names}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("classifier", help="bsgbat_v0.21_r<N>.onnx (Original, Spektrogramm-Eingang)")
    ap.add_argument("--out-dir", required=True)
    ap.add_argument("--checkpoint", default="r1", help="nur fuer Id/Name, z.B. r1")
    ap.add_argument("--variant", choices=["conv", "stft"], default="conv")
    ap.add_argument("--fp16", action="store_true", help="Gewichte des Klassifikators als Float16 speichern")
    ap.add_argument("--labels-txt", help="bsgbat_labels.txt zur Kontrolle der Reihenfolge")
    args = ap.parse_args()

    if args.labels_txt:
        check_labels(args.labels_txt)
    os.makedirs(args.out_dir, exist_ok=True)
    suffix = ("_fp16" if args.fp16 else "") + ("_stft" if args.variant == "stft" else "")
    model_id = f"bsgbat_v021_{args.checkpoint}{suffix}"
    onnx_name = model_id + ".onnx"
    csv_name = model_id + "_species.csv"

    m = fe.build(args.classifier, variant=args.variant, fp16=args.fp16)
    onnx_path = os.path.join(args.out_dir, onnx_name)
    onnx.save(m, onnx_path)

    csv_path = os.path.join(args.out_dir, csv_name)
    with open(csv_path, "w", encoding="utf-8", newline="\n") as f:
        f.write("scientific_name,common_name\n")
        for sci, com in CLASSES:
            f.write(f"{sci},{com}\n")

    window_sec = round(fe.WINDOW_SAMPLES / fe.SAMPLE_RATE, 9)
    assert round(fe.SAMPLE_RATE * window_sec) == fe.WINDOW_SAMPLES
    profile = {
        "schema": "pirol-model/1",
        "id": model_id,
        "name": f"BSG-BAT v0.21 {args.checkpoint}{' FP16' if args.fp16 else ''} (Fledermäuse Europa)",
        "family": "bsgbat",
        "runtime": "onnx",
        "modelFile": onnx_name,
        "modelSha256": sha256(onnx_path),
        "license": LICENSE,
        "input": {"name": "audio", "sampleRate": fe.SAMPLE_RATE, "windowSec": window_sec, "layout": "waveform"},
        "outputs": [{"name": "scores", "role": "scores", "transform": "none", "labels": "species"}],
        "labels": {
            "species": {
                "file": csv_name,
                "sha256": sha256(csv_path),
                "delimiter": ",",
                "header": True,
                "scientificColumn": "scientific_name",
                "commonColumn": "common_name",
            }
        },
    }
    with open(os.path.join(args.out_dir, model_id + ".pirol.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(profile, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print(f"{onnx_path}  {os.path.getsize(onnx_path) / 1e6:.1f} MB  sha256 {profile['modelSha256']}")
    print(f"windowSec {window_sec} -> {fe.WINDOW_SAMPLES} Samples @ {fe.SAMPLE_RATE} Hz")


if __name__ == "__main__":
    main()

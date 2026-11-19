#!/usr/bin/env python
"""Konvertiert ein TF-/Keras-/TFLite-Modell nach ONNX und erzeugt das PIROL-Profil
(`<id>.pirol.json`, Vertrag `pirol-model/1`, siehe Arbeitspaket_T151.md).

Ablauf: Konvertieren -> Profil schreiben -> Original und ONNX vergleichen -> Bericht.
Exit-Code 0 nur, wenn alle Pruefungen bestehen.

Beispiel: siehe tools/README_convert_model.md
"""
import argparse
import csv
import hashlib
import importlib.util
import json
import re
import shutil
import sys
import time
from pathlib import Path

import numpy as np

OPSET_DEFAULT = 17  # passend zu onnxruntime-android 1.22


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def fail(msg):
    print("FEHLER: " + msg, file=sys.stderr)
    sys.exit(2)


# ---------------------------------------------------------------- Originalmodell

class Original:
    """Kapselt das Originalmodell fuer den Vergleich: run(x [1,N]) -> Liste Arrays."""

    def __init__(self, path, preprocess_fn):
        import tensorflow as tf
        self.tf = tf
        self.path = path
        self.kind = None
        self.preprocess_fn = preprocess_fn
        p = Path(path)
        if p.is_dir():
            self.kind = "savedmodel"
            self.model = tf.saved_model.load(str(p))
            sigs = getattr(self.model, "signatures", {})
            if "serving_default" in sigs:
                self.fn = sigs["serving_default"]
            elif len(sigs) == 1:
                self.fn = list(sigs.values())[0]
            else:
                fail("SavedModel ohne Signatur 'serving_default'. Modell mit "
                     "tf.saved_model.save(..., signatures=...) neu speichern.")
            self.input_key = list(self.fn.structured_input_signature[1].keys())[0]
            spec = self.fn.structured_input_signature[1][self.input_key]
            self.input_name = spec.name or self.input_key
            self.input_shape = list(spec.shape)
            self.output_keys = list(self.fn.structured_outputs.keys())
        elif p.suffix in (".keras", ".h5"):
            self.kind = "keras"
            self.model = tf.keras.models.load_model(str(p), compile=False)
            self.input_name = self.model.inputs[0].name
            self.input_shape = list(self.model.inputs[0].shape)
            self.output_keys = [o.name for o in self.model.outputs]
        elif p.suffix == ".tflite":
            self.kind = "tflite"
            self.interp = tf.lite.Interpreter(model_path=str(p), num_threads=2)
            self.interp.allocate_tensors()
            d = self.interp.get_input_details()[0]
            self.input_name = d["name"]
            self.input_shape = list(d["shape"])
            self.output_keys = [o["name"] for o in self.interp.get_output_details()]
        else:
            fail("Unbekannte Eingabe (erwartet: SavedModel-Ordner, .keras, .h5, .tflite)")

    def run(self, x):
        tf = self.tf
        if self.preprocess_fn is not None:
            x = self.preprocess_fn(tf.constant(x)).numpy()
        if self.kind == "savedmodel":
            # Rang an die Signatur anpassen (z.B. [N] statt [1,N])
            xin = x if len(self.input_shape) == 2 else x.reshape(self.input_shape[-1:] if len(self.input_shape) == 1 else x.shape)
            out = self.fn(**{self.input_key: tf.constant(xin)})
            return [np.asarray(out[k]) for k in self.output_keys]
        if self.kind == "keras":
            out = self.model(x, training=False)
            out = out if isinstance(out, (list, tuple)) else [out]
            return [np.asarray(o) for o in out]
        d = self.interp.get_input_details()[0]
        self.interp.set_tensor(d["index"], x.astype(d["dtype"]))
        self.interp.invoke()
        return [self.interp.get_tensor(o["index"]) for o in self.interp.get_output_details()]


def load_preprocess(py_file):
    """Laedt `preprocess(x)` aus einer eigenen Python-Datei (TF-Operationen)."""
    spec = importlib.util.spec_from_file_location("pirol_preprocess", py_file)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    if not hasattr(mod, "preprocess"):
        fail("%s enthaelt keine Funktion preprocess(x)" % py_file)
    return mod.preprocess


# ---------------------------------------------------------------- Konvertierung

def convert(args, orig, out_onnx, n_samples):
    import tensorflow as tf
    import tf2onnx
    p = Path(args.model)
    spec_name = "inputs"
    # Eingangssignatur: Roh-Audio [1, N] (mit Vorverarbeitung) bzw. Originalform
    if args.preprocess:
        pre = orig.preprocess_fn
        spec = tf.TensorSpec([1, n_samples], tf.float32, name=spec_name)
        if orig.kind == "savedmodel":
            base = orig.fn
            key = orig.input_key
            in_rank = len(orig.input_shape)

            @tf.function(input_signature=[spec])
            def wrapped(x):
                y = pre(x)
                out = base(**{key: y})
                return {k: tf.identity(v, name=k) for k, v in out.items()}
        elif orig.kind == "keras":
            @tf.function(input_signature=[spec])
            def wrapped(x):
                out = orig.model(pre(x), training=False)
                out = out if isinstance(out, (list, tuple)) else [out]
                return {"out%d" % i: v for i, v in enumerate(out)}
        else:
            fail("--preprocess geht nur mit SavedModel oder Keras (TFLite: Vorverarbeitung ins Modell einbauen)")
        model_proto, _ = tf2onnx.convert.from_function(
            wrapped, input_signature=[spec], opset=args.opset, output_path=str(out_onnx))
        return
    if orig.kind == "savedmodel":
        tf2onnx.convert.from_saved_model if False else None  # nur zur Doku: CLI-aequivalent
        # Ueber die Signatur konvertieren; Eingabeform wie im Original, Batch fest 1
        shape = [1 if d is None else d for d in orig.input_shape]
        spec = tf.TensorSpec(shape, tf.float32, name=spec_name)

        @tf.function(input_signature=[spec])
        def wrapped(x):
            out = orig.fn(**{orig.input_key: x})
            return {k: tf.identity(v, name=k) for k, v in out.items()}
        tf2onnx.convert.from_function(
            wrapped, input_signature=[spec], opset=args.opset, output_path=str(out_onnx))
    elif orig.kind == "keras":
        shape = [1 if d is None else d for d in orig.input_shape]
        spec = tf.TensorSpec(shape, tf.float32, name=spec_name)
        tf2onnx.convert.from_keras(
            orig.model, input_signature=[spec], opset=args.opset, output_path=str(out_onnx))
    else:
        import subprocess
        r = subprocess.run([sys.executable, "-m", "tf2onnx.convert", "--tflite", str(p),
                            "--opset", str(args.opset), "--output", str(out_onnx)])
        if r.returncode != 0:
            fail("tf2onnx (TFLite) fehlgeschlagen")


def fix_rfft_real(path):
    """Workaround fuer tf2onnx 1.16.1: tf.signal.rfft wird nur mit ComplexAbs als Verbraucher umgesetzt.
    BirdNET 2.4 schickt das Ergebnis aber durch Cast auf float32 (= nur der Realteil); tf2onnx laesst dann
    einen ungueltigen 'RFFT'-Knoten im Graph. Ersetzt jeden solchen Knoten durch ONNX-DFT (opset 17,
    onesided) und nimmt den Realteil. Der Cast dahinter bleibt (float -> float)."""
    import onnx
    from onnx import helper, numpy_helper, TensorProto
    m = onnx.load(str(path))
    g = m.graph
    inits = {i.name: numpy_helper.to_array(i) for i in g.initializer}
    consts = {n.output[0]: numpy_helper.to_array(n.attribute[0].t) for n in g.node
              if n.op_type == "Constant" and n.attribute and n.attribute[0].name == "value"}
    consts.update(inits)
    new_nodes, n_fixed = [], 0
    for node in g.node:
        if node.op_type != "RFFT":
            new_nodes.append(node)
            continue
        length = consts.get(node.input[1])
        if length is None:
            fail("RFFT-Knoten '%s' mit nicht konstanter Laenge, Workaround nicht moeglich" % node.name)
        base = node.name or node.output[0]
        len_name, ax_name, idx_name = base + "__len", base + "__ax", base + "__re"
        g.initializer.append(numpy_helper.from_array(np.array(int(length.reshape(-1)[0]), dtype=np.int64), len_name))
        g.initializer.append(numpy_helper.from_array(np.array([-1], dtype=np.int64), ax_name))
        g.initializer.append(numpy_helper.from_array(np.array(0, dtype=np.int64), idx_name))
        x4, spec = base + "__x4", base + "__dft"
        new_nodes += [
            helper.make_node("Unsqueeze", [node.input[0], ax_name], [x4], name=base + "__unsq"),
            # Signalachse = vorletzte Achse (Rang 4: [Batch, Frames, Samples, 1]) -> axis 2
            helper.make_node("DFT", [x4, len_name], [spec], name=base + "__dft", axis=2, onesided=1, inverse=0),
            helper.make_node("Gather", [spec, idx_name], [node.output[0]], name=base + "__real", axis=3),
        ]
        n_fixed += 1
    if n_fixed:
        del g.node[:]
        g.node.extend(new_nodes)
        onnx.save(m, str(path))
        print("RFFT: %d Knoten durch DFT (nur Realteil) ersetzt" % n_fixed)


def fix_same_padding(path):
    """Workaround fuer tf2onnx 1.16.1: berechnet die SAME-Paddings von Convs/Pools mit Stride > 1
    aus statischen TF-Formen, und diese stimmen bei Modellen mit Vorverarbeitung (STFT) nicht
    immer mit den echten ueberein (Add-Fehler 'Incompatible dimensions' beim Laden).
    Explizite Pads bei Stride > 1 werden durch auto_pad=SAME_UPPER ersetzt (entspricht TF SAME;
    bei Stride 1 sind die Pads formunabhaengig und bleiben)."""
    import onnx
    from onnx import helper
    m = onnx.load(str(path))
    n_fixed = 0
    for node in m.graph.node:
        if node.op_type not in ("Conv", "MaxPool", "AveragePool"):
            continue
        attrs = {a.name: a for a in node.attribute}
        if "pads" not in attrs or "strides" not in attrs or max(attrs["strides"].ints) <= 1:
            continue
        if "auto_pad" in attrs and attrs["auto_pad"].s != b"NOTSET":
            continue
        kept = [a for a in node.attribute if a.name not in ("pads", "auto_pad")]
        del node.attribute[:]
        node.attribute.extend(kept)
        node.attribute.append(helper.make_attribute("auto_pad", "SAME_UPPER"))
        n_fixed += 1
    if n_fixed:
        onnx.save(m, str(path))
        print("SAME-Padding bei %d Knoten mit Stride > 1 auf auto_pad=SAME_UPPER gesetzt" % n_fixed)


def fix_resize_scales(path):
    """Workaround fuer tf2onnx 1.16.1: tf.image.resize wird mit Float32-'scales' statt Zielgroesse
    exportiert (z.B. 171/169 = 1.0118343); ORT rundet dann ab (floor(169*1.0118343) = 170 statt 171),
    das Spektrogramm hat eine Spalte zu wenig und alle Ausgaenge weichen ab. Ersetzt 'scales'
    durch 'sizes' = round(Eingang * scales), sofern die Eingangsform bekannt ist."""
    import onnx
    from onnx import numpy_helper, shape_inference
    m = onnx.load(str(path))
    mi = shape_inference.infer_shapes(m)
    shapes = {v.name: [d.dim_value if d.HasField("dim_value") else None
                       for d in v.type.tensor_type.shape.dim]
              for v in list(mi.graph.value_info) + list(mi.graph.input)}
    inits = {i.name: numpy_helper.to_array(i) for i in m.graph.initializer}
    n_fixed = 0
    for node in m.graph.node:
        if node.op_type != "Resize" or len(node.input) < 3 or not node.input[2] or                 (len(node.input) > 3 and node.input[3]):
            continue
        scales = inits.get(node.input[2])
        shape = shapes.get(node.input[0])
        if scales is None or shape is None or None in shape or len(shape) != len(scales):
            print("WARNUNG: Resize '%s' mit unbekannter Eingangsform, scales unveraendert" % node.name)
            continue
        sizes = np.rint(np.array(shape) * scales).astype(np.int64)
        name = node.name + "__sizes"
        m.graph.initializer.append(numpy_helper.from_array(sizes, name))
        node.input[:] = [node.input[0], node.input[1], "", name]
        n_fixed += 1
    if n_fixed:
        onnx.save(m, str(path))
        print("Resize: scales bei %d Knoten durch sizes ersetzt" % n_fixed)


def drop_unused_inputs(path):
    """tf2onnx hinterlaesst bei manchen Modellen ungenutzte Zusatz-Eingaenge (z.B. '40938:0');
    ORT verlangt sie sonst als Pflicht-Eingaben. Entfernt Eingaenge, die kein Knoten nutzt."""
    import onnx
    m = onnx.load(str(path))
    used = {i for n in m.graph.node for i in n.input}
    init = {i.name for i in m.graph.initializer}
    gone = [i for i in m.graph.input if i.name not in used and i.name not in init]
    if len(gone) == len(m.graph.input):
        return  # nichts Sinnvolles uebrig, nicht anfassen
    for i in gone:
        m.graph.input.remove(i)
    if gone:
        onnx.save(m, str(path))
        print("Ungenutzte Eingaenge entfernt: %s" % [i.name for i in gone])


def flatten_outputs(path):
    """Stellt sicher, dass alle Ausgaenge Rang 2 [1,N] haben (App liest nur das).
    Ausgaenge mit anderem Rang bekommen am Graph-Ende ein Reshape auf [1,-1]."""
    import onnx
    from onnx import helper, numpy_helper, TensorProto
    m = onnx.load(str(path))
    g = m.graph
    changed = []
    for out in list(g.output):
        dims = out.type.tensor_type.shape.dim
        if len(dims) == 2:
            continue
        raw = out.name + "__raw"
        for node in g.node:
            node.output[:] = [raw if o == out.name else o for o in node.output]
            node.input[:] = [raw if i == out.name else i for i in node.input]
        shape_name = out.name + "__shape"
        g.initializer.append(numpy_helper.from_array(np.array([1, -1], dtype=np.int64), shape_name))
        g.node.append(helper.make_node("Reshape", [raw, shape_name], [out.name], name=out.name + "__reshape"))
        new = helper.make_tensor_value_info(out.name, TensorProto.FLOAT, [1, None])
        out.CopyFrom(new)
        changed.append(out.name)
    if changed:
        onnx.checker.check_model(m)
        onnx.save(m, str(path))
        print("Ausgaenge auf [1,N] umgeformt: %s" % changed)


def to_fp16(path):
    import onnx
    from onnxconverter_common import float16
    m = onnx.load(str(path))
    m16 = float16.convert_float_to_float16(m, keep_io_types=True)  # Ein-/Ausgang bleibt Float32
    onnx.save(m16, str(path))


# ---------------------------------------------------------------- ONNX lesen

def onnx_io(path):
    import onnxruntime as ort
    so = ort.SessionOptions()
    so.intra_op_num_threads = 2
    sess = ort.InferenceSession(str(path), so, providers=["CPUExecutionProvider"])
    ins = [(i.name, i.shape, i.type) for i in sess.get_inputs()]
    outs = [(o.name, o.shape, o.type) for o in sess.get_outputs()]
    return sess, ins, outs


def last_dim(shape):
    d = shape[-1] if shape else None
    return int(d) if isinstance(d, (int, np.integer)) else None  # TFLite liefert numpy-Ganzzahlen


# ---------------------------------------------------------------- Labels

def read_labels(path, delimiter, header):
    with open(path, newline="", encoding="utf-8-sig") as f:
        rows = list(csv.reader(f, delimiter=delimiter))
    rows = [r for r in rows if r]
    if header:
        return rows[0], rows[1:]
    return None, rows


def sniff_delimiter(path):
    first = open(path, encoding="utf-8-sig").readline()
    return max([",", ";", "\t"], key=first.count)


def label_entry(role, src, args, out_dir, model_id, sci_col, common_col):
    """Kopiert die Labeldatei und liefert (Profil-Eintrag, Zeilenzahl)."""
    delim = sniff_delimiter(src)
    header = not args.no_header
    head, rows = read_labels(src, delim, header)
    dst = out_dir / ("%s_%s.csv" % (model_id, role))
    shutil.copyfile(src, dst)
    entry = {"file": dst.name, "sha256": sha256(dst), "delimiter": delim, "header": header}
    if sci_col is not None:
        if header and sci_col not in head:
            fail("Spalte '%s' nicht in Labeldatei %s (Spalten: %s)" % (sci_col, src, head))
        entry["scientificColumn"] = sci_col if header else int(sci_col)
    if common_col is not None:
        if header and common_col not in head:
            fail("Spalte '%s' nicht in Labeldatei %s (Spalten: %s)" % (common_col, src, head))
        entry["commonColumn"] = common_col if header else int(common_col)
    return entry, len(rows)


# ---------------------------------------------------------------- Test-Audio

def test_signal(path, sr, n):
    if path:
        import wave
        with wave.open(path, "rb") as w:
            ch, sw, fr, nf = w.getnchannels(), w.getsampwidth(), w.getframerate(), w.getnframes()
            raw = w.readframes(nf)
        if sw != 2:
            fail("Test-WAV: nur 16 bit PCM")
        a = np.frombuffer(raw, np.int16).astype(np.float32) / 32768.0
        a = a.reshape(-1, ch)[:, 0]
        if fr != sr:  # einfache lineare Umrechnung reicht fuer den Vergleich Original/ONNX
            t = np.arange(int(len(a) * sr / fr)) * fr / sr
            a = np.interp(t, np.arange(len(a)), a).astype(np.float32)
        if len(a) < n:
            a = np.pad(a, (0, n - len(a)))
        # Fenster mit der hoechsten Energie waehlen (statt Stille)
        hop = max(1, sr // 4)
        best = max(range(0, len(a) - n + 1, hop), key=lambda s: float(np.sum(a[s:s + n] ** 2)))
        return [a[best:best + n].reshape(1, n).astype(np.float32)], "WAV " + Path(path).name
    rng = np.random.default_rng(0)
    t = np.arange(n) / sr
    noise = (rng.standard_normal(n) * 0.1).astype(np.float32)
    sine = (0.5 * np.sin(2 * np.pi * 3000 * t)).astype(np.float32)
    return [noise.reshape(1, n), (noise + sine).reshape(1, n)], "Rauschen + Sinus 3 kHz"


# ---------------------------------------------------------------- Hauptprogramm

def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("model", help="SavedModel-Ordner, .keras, .h5 oder .tflite")
    ap.add_argument("--out-dir", required=True, help="Ausgabeverzeichnis")
    ap.add_argument("--id", required=True, help="Modell-ID [a-z0-9_]+")
    ap.add_argument("--name", required=True, help="Anzeigename")
    ap.add_argument("--family", required=True)
    ap.add_argument("--license", required=True)
    ap.add_argument("--sample-rate", type=int, required=True)
    ap.add_argument("--window-sec", type=float, required=True)
    ap.add_argument("--scores-output", required=True, help="Name des Score-Ausgangs (siehe Ausgabe 'Ausgaenge')")
    ap.add_argument("--transform", choices=["none", "sigmoid", "softmax"], required=True)
    ap.add_argument("--labels", required=True, help="CSV der Klassen des Score-Ausgangs")
    ap.add_argument("--labels-format", choices=["csv", "birdnet24"], default="csv",
                    help="birdnet24: Textdatei 'Wissenschaftlich_Common' pro Zeile ohne Kopf; wird in eine CSV "
                    "scientific_name,common_name umgewandelt (Spaltenoptionen entfallen)")
    ap.add_argument("--scientific-column", help="Spaltenname (oder Index ohne Kopfzeile)")
    ap.add_argument("--common-column")
    ap.add_argument("--no-header", action="store_true", help="Labeldateien ohne Kopfzeile")
    ap.add_argument("--aux-output", action="append", default=[], metavar="NAME:LABELS.csv:TRANSFORM",
                    help="Zusatzausgang (role aux), mehrfach moeglich")
    ap.add_argument("--embedding-output", help="Name des Embedding-Ausgangs")
    ap.add_argument("--fp16", action="store_true", help="Gewichte nach Float16 (Ein-/Ausgang bleibt Float32)")
    ap.add_argument("--opset", type=int, default=OPSET_DEFAULT)
    ap.add_argument("--preprocess", metavar="PY", help="Python-Datei mit preprocess(x) (TF-Ops): "
                    "Roh-Audio [1,N] -> Eingang des Modells; wird mit eingebaut")
    ap.add_argument("--test-audio", help="WAV 16 bit fuer den Vergleich (sonst Rauschen + Sinus)")
    ap.add_argument("--tol", type=float, default=1e-3, help="erlaubte max. Abweichung (Float32-Ausgaenge)")
    args = ap.parse_args()

    if not re.fullmatch(r"[a-z0-9_]+", args.id):
        fail("--id muss [a-z0-9_]+ sein")
    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    labels_tmp = None
    if args.labels_format == "birdnet24":
        # BirdNET-2.4-Labels: pro Zeile "Wissenschaftlicher Name_Common Name", Trennung am ersten "_"
        labels_tmp = out_dir / (args.id + "_labels_src.csv")
        with open(args.labels, encoding="utf-8-sig") as f, open(labels_tmp, "w", newline="", encoding="utf-8") as g:
            w = csv.writer(g)
            w.writerow(["scientific_name", "common_name"])
            for line in f:
                line = line.strip()
                if line:
                    sci, _, com = line.partition("_")
                    w.writerow([sci, com])
        args.labels, args.no_header = str(labels_tmp), False
        args.scientific_column, args.common_column = "scientific_name", "common_name"
        print("Labels (birdnet24) nach CSV umgewandelt")
    if not args.scientific_column:
        fail("--scientific-column fehlt")
    n_samples = round(args.sample_rate * args.window_sec)
    mid = args.id
    report, ok = [], True

    def check(label, passed, detail=""):
        nonlocal ok
        ok = ok and passed
        line = "- [%s] %s%s" % ("OK" if passed else "FEHLER", label, (": " + detail) if detail else "")
        report.append(line)
        print(line)

    # 1. Original laden und Eingang pruefen
    pre = load_preprocess(args.preprocess) if args.preprocess else None
    orig = Original(args.model, pre)
    print("Original: %s, Eingang '%s' %s, Ausgaenge %s" % (orig.kind, orig.input_name, orig.input_shape, orig.output_keys))
    raw_ok = (last_dim(orig.input_shape) == n_samples and len(orig.input_shape) <= 2)
    if not args.preprocess and not raw_ok:
        fail("Das Modell erwartet kein Roh-Audio der Laenge %d (Eingang %s). Vermutlich Spektrogramm-"
             "Eingang: die Vorverarbeitung muss mit ins ONNX. Mit --preprocess datei.py (Funktion "
             "preprocess(x) in TF-Ops: [1,N] -> Modelleingang) einbauen, oder das Modell mit "
             "Vorverarbeitung neu exportieren." % (n_samples, orig.input_shape))

    # 2. Konvertieren
    onnx_path = out_dir / (mid + ".onnx")
    t0 = time.time()
    convert(args, orig, onnx_path, n_samples)
    fix_rfft_real(onnx_path)
    fix_resize_scales(onnx_path)
    fix_same_padding(onnx_path)
    drop_unused_inputs(onnx_path)
    flatten_outputs(onnx_path)
    if args.fp16:
        to_fp16(onnx_path)
    print("Konvertiert in %.1f s -> %s" % (time.time() - t0, onnx_path))

    # 3. ONNX lesen
    sess, ins, outs = onnx_io(onnx_path)
    in_name, in_shape, in_type = ins[0]
    print("ONNX Eingang: %s %s %s" % (in_name, in_shape, in_type))
    print("ONNX Ausgaenge (Name, Form):")
    for o in outs:
        print("  %s %s" % (o[0], o[1]))
    out_names = [o[0] for o in outs]
    out_size = {o[0]: last_dim(o[1]) for o in outs}

    def find_out(name):
        # exakter Name, sonst Name ohne ":0"
        for n in out_names:
            if n == name or n.split(":")[0] == name:
                return n
        fail("Ausgang '%s' nicht im ONNX (vorhanden: %s)" % (name, out_names))

    # 4. Profil
    scores = find_out(args.scores_output)
    labels = {}
    outputs = []
    sci, common = args.scientific_column, args.common_column
    entry, n = label_entry("species", Path(args.labels), args, out_dir, mid, sci, common)
    labels["species"] = entry
    if labels_tmp:
        labels_tmp.unlink()
    label_counts = [("species", scores, n)]
    outputs.append({"name": scores, "role": "scores", "transform": args.transform, "labels": "species"})
    for spec in args.aux_output:
        parts = spec.split(":")
        if len(parts) < 3:
            fail("--aux-output erwartet NAME:LABELS.csv:TRANSFORM (Windows-Pfad mit Laufwerk: relative Pfade nutzen)")
        aname, atr = parts[0], parts[-1]
        apath = ":".join(parts[1:-1])
        an = find_out(aname)
        role = re.sub(r"[^a-z0-9_]", "_", aname.split(":")[0].lower())
        e, cnt = label_entry(role, Path(apath), args, out_dir, mid, None, None)
        e["scientificColumn"] = 0 if args.no_header else None
        if e["scientificColumn"] is None:
            # Aux-Labels: erste Spalte als Name verwenden
            head, _ = read_labels(Path(apath), e["delimiter"], True)
            e["scientificColumn"] = head[0]
        labels[role] = e
        label_counts.append((role, an, cnt))
        outputs.append({"name": an, "role": "aux", "transform": atr, "labels": role})
    if args.embedding_output:
        outputs.append({"name": find_out(args.embedding_output), "role": "embedding"})

    profile = {
        "schema": "pirol-model/1", "id": mid, "name": args.name, "family": args.family,
        "runtime": "onnx", "modelFile": onnx_path.name, "modelSha256": sha256(onnx_path),
        "license": args.license,
        "input": {"name": in_name, "sampleRate": args.sample_rate, "windowSec": args.window_sec,
                  "layout": "waveform"},
        "outputs": outputs, "labels": labels,
    }
    prof_path = out_dir / (mid + ".pirol.json")
    prof_path.write_text(json.dumps(profile, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    # 5. Pruefungen
    report.append("## Pruefungen")
    n_in = last_dim(in_shape)
    check("Eingangslaenge = round(sampleRate*windowSec)", n_in == n_samples,
          "ONNX %s, erwartet %d" % (n_in, n_samples))
    check("Eingangstyp Float32", in_type == "tensor(float)", in_type)
    check("Eingang Rang 1 oder 2", len(in_shape) in (1, 2) and (len(in_shape) == 1 or in_shape[0] in (1, "batch", None) or isinstance(in_shape[0], str)),
          str(in_shape))
    xs, src = test_signal(args.test_audio, args.sample_rate, n_samples)
    report.append("")
    report.append("Testsignal: %s (%d Fenster)" % (src, len(xs)))
    report.append("")
    # Ausgaenge der Profil-Eintraege muessen die Form [1,N] haben (App liest nur Rang 2)
    probe = sess.run(None, {in_name: xs[0].reshape([d if isinstance(d, int) else 1 for d in in_shape])})
    real = {n: tuple(np.asarray(a).shape) for n, a in zip(out_names, probe)}
    for o in outputs:
        sh = real[o["name"]]
        check("Ausgang '%s' hat Form [1,N]" % o["name"], len(sh) == 2 and sh[0] == 1, str(list(sh)))
    for role, oname, cnt in label_counts:
        n_out = int(np.prod(real[oname]))
        check("Labelanzahl '%s' = Ausgangsgroesse '%s'" % (role, oname), cnt == n_out,
              "%d Labels, Ausgang %d" % (cnt, n_out))
    worst = {}
    top5_ok = {}
    t_orig = t_onnx = 0.0
    for x in xs:
        t = time.time()
        ro = orig.run(x)
        t_orig += time.time() - t
        t = time.time()
        rn = sess.run(None, {in_name: x.reshape([d if isinstance(d, int) else 1 for d in in_shape])})
        t_onnx += time.time() - t
        if len(ro) != len(rn):
            check("Anzahl Ausgaenge", False, "Original %d, ONNX %d" % (len(ro), len(rn)))
            continue
        for i, name in enumerate(out_names):
            a, b = np.asarray(ro[i]).reshape(-1), np.asarray(rn[i]).reshape(-1)
            if a.shape != b.shape:
                check("Form Ausgang %s" % name, False, "Original %s, ONNX %s" % (a.shape, b.shape))
                continue
            if not (np.all(np.isfinite(a)) and np.all(np.isfinite(b))):
                check("Werte endlich Ausgang %s" % name, False, "NaN/Inf (Original %s, ONNX %s)" % (
                    not np.all(np.isfinite(a)), not np.all(np.isfinite(b))))
                continue
            worst[name] = max(worst.get(name, 0.0), float(np.max(np.abs(a - b))))
            if len(a) >= 5:
                k = 1 if args.fp16 else 5  # bei fp16 nur Top-1 (Reihenfolge knapper Plaetze kippt leicht)
                same = list(np.argsort(-a)[:k]) == list(np.argsort(-b)[:k])
                top5_ok[name] = top5_ok.get(name, True) and same
    tol = args.tol * (10 if args.fp16 else 1)
    table = ["| Ausgang | max. Abweichung | Top-5 identisch |", "|---|---|---|"]
    for name in out_names:
        if name in worst:
            t5 = {True: "ja", False: "nein"}.get(top5_ok.get(name), "-")
            table.append("| %s | %.3g | %s |" % (name, worst[name], t5))
            check("Abweichung %s <= %g" % (name, tol), worst[name] <= tol, "%.3g" % worst[name])
            if name in top5_ok:
                check("Top-%d %s identisch" % (1 if args.fp16 else 5, name), top5_ok[name])
    report[1:1] = table + [""]  # Tabelle direkt unter die Ueberschrift
    report.append("")
    report.append("Laufzeit pro Fenster (PC, 2 Threads ONNX): ONNX %.1f ms, Original %.1f ms" % (
        1000 * t_onnx / len(xs), 1000 * t_orig / len(xs)))

    # 6. Dateien und Bericht
    report.append("")
    report.append("## Dateien")
    for f in sorted(out_dir.glob(mid + "*")):
        if f.name.endswith("_bericht.md"):
            continue
        report.append("- %s  %s  %d Byte" % (f.name, sha256(f), f.stat().st_size))
    head = ["# Konvertierungsbericht %s" % mid, "", "Quelle: %s, opset %d%s" % (
        args.model, args.opset, ", fp16" if args.fp16 else ""), "",
        "Ergebnis: %s" % ("BESTANDEN" if ok else "FEHLER"), "", "## Ausgaenge"]
    for o in outs:
        head.append("- %s %s" % (o[0], o[1]))
    head.append("")
    (out_dir / (mid + "_bericht.md")).write_text("\n".join(head + report) + "\n", encoding="utf-8")
    print("\nErgebnis: %s (Bericht: %s)" % ("BESTANDEN" if ok else "FEHLER", out_dir / (mid + "_bericht.md")))
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()

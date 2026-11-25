"""Gleichheitstest BSG-BAT: ONNX mit Frontend vs. Referenz (librosa + Original-Klassifikator) (T291).

Referenz = eigene Nachbildung von `wav2spectrograms()` aus BSG-BAT data384.py (librosa.melspectrogram,
log10(S+1e-6), normalize, Segmente 512 Frames / Hop 250 inkl. Rest-Segment), danach das Original-ONNX
(`spectrogram` -> `logits`, Sigmoid). Der heruntergeladene Originalcode wird NICHT ausgefuehrt.

ONNX-Pfad = PIROL-Sicht: pro Segment ein Rohaudio-Fenster von 393'472 Samples aus dem
beidseitig mit 512 Nullen aufgefuellten Signal (entspricht librosa center=True, pad_mode constant).

Aufruf (Python mit -I; numpy, onnx, onnxruntime, librosa, soundfile):
    python -I tools/bats/verify_bsgbat.py --classifier <bsgbat_v0.21_r1.onnx> --model <a.onnx> [--model <b.onnx>]
        [--wav-dir <Ordner mit WAV/FLAC >= 250 kHz>] [--synthetic] [--json <ergebnis.json>]
"""
import argparse
import glob
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np  # noqa: E402
import onnx  # noqa: E402
import onnxruntime as ort  # noqa: E402

import bsgbat_frontend as fe  # noqa: E402

N_TIME = 512
N_HOP = 250
CLASSES = None  # wird aus build_bsgbat_package geladen


def sigmoid(x):
    return 1.0 / (1.0 + np.exp(-x))


# ---------------------------------------------------------------- Referenz (Nachbildung data384.py)
def ref_normalize(data):
    x = (data - np.mean(data)) / np.std(data)
    return np.clip(x - np.median(x, axis=0), 0.0, 6.0)


def ref_melspec(y):
    import librosa
    return librosa.feature.melspectrogram(y=y, sr=fe.SAMPLE_RATE, n_fft=fe.N_FFT, hop_length=fe.HOP,
                                          n_mels=fe.N_MELS, fmin=fe.F_MIN, fmax=fe.F_MAX).T


def ref_segments(y):
    """-> (Startframes, Daten [n,512,128]) wie wav2spectrograms()."""
    S = ref_melspec(y)
    n = int(np.max((np.ceil((len(S) - N_TIME) / N_HOP), 1)))
    data = np.ndarray((n, N_TIME, fe.N_MELS), dtype="float32")
    starts = []
    if len(S) < N_TIME:
        X = np.zeros((N_TIME, fe.N_MELS), dtype="float32")
        X[:len(S)] = S
        data[0] = ref_normalize(np.log10(X + 1e-6))
        starts.append(0)
    else:
        for i in range(n):
            s = i * N_HOP
            if s + N_TIME > len(S):
                s = len(S) - N_TIME
            data[i] = ref_normalize(np.log10(S[s:s + N_TIME] + 1e-6))
            starts.append(s)
    return starts, data


def onnx_windows(y, starts):
    pad = fe.N_FFT // 2
    yp = np.concatenate([np.zeros(pad, np.float32), y.astype(np.float32),
                         np.zeros(pad + fe.WINDOW_SAMPLES, np.float32)])
    return [yp[s * fe.HOP: s * fe.HOP + fe.WINDOW_SAMPLES][None, :] for s in starts]


# ---------------------------------------------------------------- Testsignale
def synth_signals(seed=291):
    """Fledermausaehnliche Rufe (keine echten Aufnahmen), 384 kHz, je 4 s."""
    rng = np.random.default_rng(seed)
    sr = fe.SAMPLE_RATE
    dur = 4.0
    t_all = np.arange(int(sr * dur)) / sr

    def calls(f_start, f_end, call_ms, rate_hz, kind="lin", amp=0.3, noise=0.003, harmonics=False):
        y = noise * rng.standard_normal(len(t_all))
        n = int(sr * call_ms / 1000)
        tc = np.arange(n) / sr
        T = call_ms / 1000
        if kind == "lin":
            ph = 2 * np.pi * (f_start * tc + (f_end - f_start) * tc ** 2 / (2 * T))
        elif kind == "hyp":  # hyperbolisch, steil am Anfang (FM-QCF, Pipistrellus-artig)
            k = (f_start / f_end - 1) / T
            ph = 2 * np.pi * f_start * np.log1p(k * tc) / k
        else:
            raise ValueError(kind)
        env = np.hanning(n)
        call = np.sin(ph) * env
        if harmonics:
            call += 0.3 * np.sin(2 * ph) * env
        period = 1.0 / rate_hz
        t0 = 0.05
        while t0 + T < dur:
            i0 = int((t0 + rng.uniform(-0.005, 0.005)) * sr)
            y[i0:i0 + n] += amp * rng.uniform(0.5, 1.0) * call
            t0 += period
        return y.astype(np.float32)

    def cf_calls(f_cf, call_ms, rate_hz, amp=0.3):
        # Hufeisennase: lange CF mit kurzem FM-Anfang/-Ende
        y = 0.003 * rng.standard_normal(len(t_all))
        n = int(sr * call_ms / 1000)
        tc = np.arange(n) / sr
        f = np.full(n, f_cf)
        m = int(0.003 * sr)
        f[:m] = np.linspace(f_cf - 3000, f_cf, m)
        f[-m:] = np.linspace(f_cf, f_cf - 15000, m)
        ph = 2 * np.pi * np.cumsum(f) / sr
        call = np.sin(ph) * np.hanning(n) ** 0.3
        t0 = 0.05
        while t0 + call_ms / 1000 < dur:
            i0 = int(t0 * sr)
            y[i0:i0 + n] += amp * call
            t0 += 1.0 / rate_hz
        return y.astype(np.float32)

    sig = {
        "syn_pip_pip_45k": calls(75000, 45000, 6, 11, "hyp"),
        "syn_pip_pyg_55k": calls(85000, 55000, 5, 12, "hyp"),
        "syn_pip_nat_40k": calls(70000, 39000, 7, 9, "hyp"),
        "syn_nyctalus_20k": calls(26000, 19000, 18, 4, "lin", amp=0.5),
        "syn_eptesicus_27k": calls(55000, 27000, 10, 7, "hyp"),
        "syn_myotis_fm": calls(90000, 30000, 3, 13, "lin", amp=0.2, harmonics=True),
        "syn_rhino_hip_110k": cf_calls(110000, 45, 8),
        "syn_rhino_fer_82k": cf_calls(82000, 55, 7),
        "noise_white": (0.05 * rng.standard_normal(len(t_all))).astype(np.float32),
        "short_0p6s_pip": calls(75000, 45000, 6, 11, "hyp")[: int(0.6 * sr)],
    }
    return sig


def load_wavs(wav_dir):
    import librosa
    out = {}
    for p in sorted(glob.glob(os.path.join(wav_dir, "*.wav")) + glob.glob(os.path.join(wav_dir, "*.flac"))):
        try:
            import soundfile as sf
            info = sf.info(p)
            native = info.samplerate
        except Exception:
            native = None
        try:
            y, _ = librosa.load(p, sr=fe.SAMPLE_RATE, mono=True, res_type="kaiser_fast")
            rt = "kaiser_fast"
        except Exception:  # resampy fehlt -> soxr
            y, _ = librosa.load(p, sr=fe.SAMPLE_RATE, mono=True, res_type="soxr_hq")
            rt = "soxr_hq"
        out[os.path.basename(p)] = (y.astype(np.float32), native, rt)
    return out


# ---------------------------------------------------------------- Pruefungen
def mel_check():
    import librosa
    ref = librosa.filters.mel(sr=fe.SAMPLE_RATE, n_fft=fe.N_FFT, n_mels=fe.N_MELS, fmin=fe.F_MIN, fmax=fe.F_MAX)
    return float(np.max(np.abs(ref - fe.mel_filterbank())))


def session(path, threads=0):
    so = ort.SessionOptions()
    if threads:
        so.intra_op_num_threads = threads
    return ort.InferenceSession(path, so, providers=["CPUExecutionProvider"])


def main():
    global CLASSES
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--classifier", required=True)
    ap.add_argument("--model", action="append", required=True, help="kombiniertes ONNX (mehrfach moeglich)")
    ap.add_argument("--wav-dir")
    ap.add_argument("--synthetic", action="store_true")
    ap.add_argument("--json")
    ap.add_argument("--timing-runs", type=int, default=20)
    args = ap.parse_args()
    from build_bsgbat_package import CLASSES as C
    CLASSES = [c[0] for c in C]

    res = {"onnxruntime": ort.__version__, "mel_filter_maxdiff_vs_librosa": mel_check()}
    print(f"onnxruntime {ort.__version__}; Mel-Filter max |diff| vs librosa = {res['mel_filter_maxdiff_vs_librosa']:.3g}")

    signals = {}
    if args.synthetic:
        for k, v in synth_signals().items():
            signals[k] = (v, fe.SAMPLE_RATE, "synth")
    if args.wav_dir:
        signals.update(load_wavs(args.wav_dir))
    if not signals:
        sys.exit("keine Signale (--synthetic und/oder --wav-dir)")

    ref_sess = session(args.classifier)
    # Debug-Modell (FP32, conv) mit Zwischenausgang zur Frontend-Pruefung
    import tempfile
    dbg = fe.build(args.classifier, variant="conv", fp16=False, debug_features=True)
    dbg_path = os.path.join(tempfile.mkdtemp(), "dbg.onnx")
    onnx.save(dbg, dbg_path)
    dbg_sess = session(dbg_path)
    sessions = {os.path.basename(p): session(p) for p in args.model}

    res["files"] = []
    for name, (y, native, how) in signals.items():
        starts, data = ref_segments(y)
        ref_logits = ref_sess.run(["logits"], {"spectrogram": data[:, None, :, :]})[0]
        ref_scores = sigmoid(ref_logits)
        wins = onnx_windows(y, starts)
        feat_diff = 0.0
        for i, w in enumerate(wins):
            f = dbg_sess.run(["spectrogram"], {"audio": w})[0][0, 0]
            feat_diff = max(feat_diff, float(np.max(np.abs(f - data[i]))))
        entry = {"name": name, "nativeRate": native, "load": how, "durationSec": round(len(y) / fe.SAMPLE_RATE, 3),
                 "segments": len(starts), "featureMaxAbsDiff": feat_diff, "models": {}}
        ref_top = [CLASSES[int(np.argmax(s))] for s in ref_scores]
        ref_top_nobg = [CLASSES[int(np.argmax(s[:21]))] for s in ref_scores]
        entry["refTop1"] = ref_top
        entry["refTop1Species"] = ref_top_nobg
        entry["refMaxScore"] = [round(float(np.max(s)), 4) for s in ref_scores]
        for mname, s in sessions.items():
            sc = np.concatenate([s.run(["scores"], {"audio": w})[0] for w in wins])
            top = [CLASSES[int(np.argmax(v))] for v in sc]
            top_nobg = [CLASSES[int(np.argmax(v[:21]))] for v in sc]
            entry["models"][mname] = {
                "scoreMaxAbsDiff": float(np.max(np.abs(sc - ref_scores))),
                "top1Equal": int(sum(a == b for a, b in zip(top, ref_top))),
                "top1SpeciesEqual": int(sum(a == b for a, b in zip(top_nobg, ref_top_nobg))),
                "aboveHalfEqual": bool(np.array_equal(sc > 0.5, ref_scores > 0.5)),
            }
        res["files"].append(entry)
        ms = "; ".join(f"{k}: dmax {v['scoreMaxAbsDiff']:.2e} top1 {v['top1Equal']}/{len(starts)}"
                       for k, v in entry["models"].items())
        best = max(range(len(starts)), key=lambda i: entry["refMaxScore"][i])
        print(f"{name}: {len(starts)} Seg, Feature dmax {feat_diff:.2e}, Ref-Top1 {ref_top[best]} "
              f"({entry['refMaxScore'][best]:.3f}), Art-Top1 {ref_top_nobg[best]} | {ms}")

    # Laufzeit je Fenster
    res["timing_ms"] = {}
    w = onnx_windows(next(iter(signals.values()))[0], [0])[0]
    for p in args.model:
        for th in (1, 4, 0):
            s = session(p, th)
            s.run(["scores"], {"audio": w})
            t0 = time.perf_counter()
            for _ in range(args.timing_runs):
                s.run(["scores"], {"audio": w})
            ms = (time.perf_counter() - t0) / args.timing_runs * 1000
            key = f"{os.path.basename(p)} threads={th or 'auto'}"
            res["timing_ms"][key] = round(ms, 1)
            print(f"Laufzeit {key}: {ms:.1f} ms/Fenster")
    # Ops
    res["ops"] = {}
    for p in args.model:
        ops = sorted({n.op_type for n in onnx.load(p).graph.node})
        res["ops"][os.path.basename(p)] = ops
        print(f"Ops {os.path.basename(p)}: {', '.join(ops)}")

    if args.json:
        with open(args.json, "w", encoding="utf-8") as f:
            json.dump(res, f, indent=1)


if __name__ == "__main__":
    main()

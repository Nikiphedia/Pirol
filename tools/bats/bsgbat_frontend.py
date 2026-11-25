"""BSG-BAT v0.21: Frontend (Mel-Spektrogramm + Normalisierung) als ONNX-Teilgraph (T291).

Bildet `wav2spectrograms()` aus dem BSG-BAT-Originalcode (data384.py) nach, aber fuer EIN
Fenster Rohaudio, wie PIROL es liefert:

    librosa.feature.melspectrogram(sr=384000, n_fft=1024, hop_length=768, n_mels=128,
                                   fmin=9000, fmax=150000)   # Hann, power 2, Slaney-Mel
    log10(S + 1e-6) -> (x - mean) / std -> clip(x - median_t(x), 0, 6)

Fenster: 512 Frames ohne Zentrierung = 1024 + 511*768 = 393'472 Samples (1.024667 s @ 384 kHz).
Das entspricht einem Ausschnitt aus dem zentrierten librosa-STFT einer langen Aufnahme
(Fensterbeginn = Frame-Start * 768 - 512 im Original-Signal, vorne/hinten mit Nullen).

Nur numpy + onnx (kein librosa noetig). Kommentare Deutsch, Bezeichner Englisch.
"""
import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

SAMPLE_RATE = 384_000
N_FFT = 1024
HOP = 768
N_MELS = 128
F_MIN = 9_000.0
F_MAX = 150_000.0
N_FRAMES = 512
WINDOW_SAMPLES = N_FFT + (N_FRAMES - 1) * HOP  # 393'472
LOG_EPS = 1e-6
STD_FLOOR = 1e-8  # Schutz gegen Division durch 0 bei digitaler Stille (Original: NaN)


# ---------------------------------------------------------------- Mel (Slaney, wie librosa htk=False, norm='slaney')
def _hz_to_mel(f):
    f = np.asarray(f, dtype=np.float64)
    f_sp = 200.0 / 3
    mels = f / f_sp
    min_log_hz = 1000.0
    min_log_mel = min_log_hz / f_sp
    logstep = np.log(6.4) / 27.0
    return np.where(f >= min_log_hz, min_log_mel + np.log(np.maximum(f, 1e-10) / min_log_hz) / logstep, mels)


def _mel_to_hz(m):
    m = np.asarray(m, dtype=np.float64)
    f_sp = 200.0 / 3
    freqs = f_sp * m
    min_log_hz = 1000.0
    min_log_mel = min_log_hz / f_sp
    logstep = np.log(6.4) / 27.0
    return np.where(m >= min_log_mel, min_log_hz * np.exp(logstep * (m - min_log_mel)), freqs)


def mel_filterbank(sr=SAMPLE_RATE, n_fft=N_FFT, n_mels=N_MELS, fmin=F_MIN, fmax=F_MAX):
    """Mel-Filterbank [n_mels, 1 + n_fft/2], identisch zu librosa.filters.mel (Default-Parameter)."""
    fftfreqs = np.linspace(0, sr / 2, 1 + n_fft // 2)
    mel_f = _mel_to_hz(np.linspace(_hz_to_mel(fmin), _hz_to_mel(fmax), n_mels + 2))
    fdiff = np.diff(mel_f)
    ramps = mel_f[:, None] - fftfreqs[None, :]
    weights = np.zeros((n_mels, len(fftfreqs)))
    for i in range(n_mels):
        lower = -ramps[i] / fdiff[i]
        upper = ramps[i + 2] / fdiff[i + 1]
        weights[i] = np.maximum(0, np.minimum(lower, upper))
    enorm = 2.0 / (mel_f[2:n_mels + 2] - mel_f[:n_mels])
    weights *= enorm[:, None]
    return weights.astype(np.float32)


def hann_periodic(n=N_FFT):
    return (0.5 - 0.5 * np.cos(2 * np.pi * np.arange(n) / n)).astype(np.float64)


def used_bins(mel):
    """Bereich der FFT-Bins, die in mindestens einem Mel-Filter vorkommen."""
    nz = np.nonzero(mel.sum(axis=0) > 0)[0]
    return int(nz[0]), int(nz[-1]) + 1


# ---------------------------------------------------------------- Graph-Bausteine
def _const(name, arr, dtype=None):
    arr = np.asarray(arr) if dtype is None else np.asarray(arr, dtype=dtype)
    return numpy_helper.from_array(arr, name)


def frontend_nodes(audio_name, out_name, variant="conv", prefix="fe_"):
    """Knoten + Initializer: audio [1, WINDOW_SAMPLES] -> out [1, 1, N_FRAMES, N_MELS].

    variant "conv": DFT als Conv1d (Kernel = Hann * cos/sin, nur benutzte Bins). Laeuft in jedem ORT-Build.
    variant "stft": ONNX-Op STFT (Opset 17).
    """
    p = prefix
    mel = mel_filterbank()
    nodes, inits = [], []

    if variant == "conv":
        k0, k1 = used_bins(mel)
        k = np.arange(k0, k1)
        n = np.arange(N_FFT)
        win = hann_periodic()
        ang = 2 * np.pi * np.outer(k, n) / N_FFT
        w = np.concatenate([np.cos(ang) * win, -np.sin(ang) * win], axis=0)  # [2K, N_FFT]
        inits.append(_const(p + "dft_w", w[:, None, :].astype(np.float32)))
        inits.append(_const(p + "shape3", [1, 1, WINDOW_SAMPLES], np.int64))
        inits.append(_const(p + "shape_ri", [1, 2, k1 - k0, N_FRAMES], np.int64))
        nodes += [
            helper.make_node("Reshape", [audio_name, p + "shape3"], [p + "x3"]),
            helper.make_node("Conv", [p + "x3", p + "dft_w"], [p + "ri"], strides=[HOP], kernel_shape=[N_FFT]),
            helper.make_node("Mul", [p + "ri", p + "ri"], [p + "ri2"]),
            helper.make_node("Reshape", [p + "ri2", p + "shape_ri"], [p + "ri2r"]),
            helper.make_node("ReduceSum", [p + "ri2r", p + "ax1"], [p + "pow_kt"], keepdims=0),   # [1, K, T]
            helper.make_node("Transpose", [p + "pow_kt"], [p + "pow"], perm=[0, 2, 1]),          # [1, T, K]
        ]
        inits.append(_const(p + "ax1", [1], np.int64))
        mel_t = mel[:, k0:k1].T  # [K, 128]
    elif variant == "stft":
        inits.append(_const(p + "shape3", [1, WINDOW_SAMPLES, 1], np.int64))
        inits.append(_const(p + "step", np.int64(HOP)))
        inits.append(_const(p + "win", hann_periodic().astype(np.float32)))
        inits.append(_const(p + "flen", np.int64(N_FFT)))
        inits.append(_const(p + "ax3", [3], np.int64))
        nodes += [
            helper.make_node("Reshape", [audio_name, p + "shape3"], [p + "x3"]),
            helper.make_node("STFT", [p + "x3", p + "step", p + "win", p + "flen"], [p + "spec"], onesided=1),  # [1,T,513,2]
            helper.make_node("Mul", [p + "spec", p + "spec"], [p + "spec2"]),
            helper.make_node("ReduceSum", [p + "spec2", p + "ax3"], [p + "pow"], keepdims=0),    # [1, T, 513]
        ]
        mel_t = mel.T  # [513, 128]
    else:
        raise ValueError(variant)

    inits += [
        _const(p + "mel_t", mel_t.astype(np.float32)),
        _const(p + "eps", np.float32(LOG_EPS)),
        _const(p + "inv_ln10", np.float32(1.0 / np.log(10.0))),
        _const(p + "std_floor", np.float32(STD_FLOOR)),
        _const(p + "k_med", [N_FRAMES // 2 + 1], np.int64),
        _const(p + "med_lo", [N_FRAMES // 2 - 1], np.int64),
        _const(p + "med_hi", [N_FRAMES // 2 + 1], np.int64),
        _const(p + "ax_t", [1], np.int64),
        _const(p + "zero", np.float32(0.0)),
        _const(p + "six", np.float32(6.0)),
        _const(p + "shape4", [1, 1, N_FRAMES, N_MELS], np.int64),
    ]
    nodes += [
        helper.make_node("MatMul", [p + "pow", p + "mel_t"], [p + "melspec"]),                 # [1, T, 128]
        helper.make_node("Add", [p + "melspec", p + "eps"], [p + "mel_eps"]),
        helper.make_node("Log", [p + "mel_eps"], [p + "ln"]),
        helper.make_node("Mul", [p + "ln", p + "inv_ln10"], [p + "lg"]),                        # log10
        # Globale Standardisierung (np.mean / np.std ueber das ganze Fenster)
        helper.make_node("ReduceMean", [p + "lg"], [p + "mean"], keepdims=1),
        helper.make_node("Sub", [p + "lg", p + "mean"], [p + "d"]),
        helper.make_node("Mul", [p + "d", p + "d"], [p + "d2"]),
        helper.make_node("ReduceMean", [p + "d2"], [p + "var"], keepdims=1),
        helper.make_node("Sqrt", [p + "var"], [p + "std0"]),
        helper.make_node("Max", [p + "std0", p + "std_floor"], [p + "std"]),
        helper.make_node("Div", [p + "d", p + "std"], [p + "z"]),
        # Median je Mel-Band ueber die Zeit (512 Werte: Mittel aus Rang 255 und 256)
        helper.make_node("TopK", [p + "z", p + "k_med"], [p + "low", p + "low_idx"], axis=1, largest=0, sorted=1),
        helper.make_node("Slice", [p + "low", p + "med_lo", p + "med_hi", p + "ax_t"], [p + "mid2"]),
        helper.make_node("ReduceMean", [p + "mid2"], [p + "med"], axes=[1], keepdims=1),  # Opset 17: axes als Attribut
        helper.make_node("Sub", [p + "z", p + "med"], [p + "zm"]),
        helper.make_node("Clip", [p + "zm", p + "zero", p + "six"], [p + "clipped"]),
        helper.make_node("Reshape", [p + "clipped", p + "shape4"], [out_name]),
    ]
    return nodes, inits


def weights_to_fp16(graph, skip_prefix="fe_", min_size=1024):
    """Grosse Float32-Gewichte (ausser Frontend) als Float16 speichern + Cast nach Float32.

    Rechnung bleibt Float32 (ORT faltet die Casts beim Laden), nur die Datei wird halb so gross.
    """
    new_inits, casts = [], []
    for t in graph.initializer:
        arr = numpy_helper.to_array(t)
        if t.data_type == TensorProto.FLOAT and arr.size >= min_size and not t.name.startswith(skip_prefix):
            h = t.name + "_fp16"
            new_inits.append(numpy_helper.from_array(arr.astype(np.float16), h))
            casts.append(helper.make_node("Cast", [h], [t.name], to=TensorProto.FLOAT, name="cast_" + t.name))
        else:
            new_inits.append(t)
    del graph.initializer[:]
    graph.initializer.extend(new_inits)
    nodes = list(graph.node)
    del graph.node[:]
    graph.node.extend(casts + nodes)
    return len(casts)


def build(classifier_path, variant="conv", fp16=False, debug_features=False,
          input_name="audio", output_name="scores"):
    """Original-Klassifikator (spectrogram -> logits) + Frontend + Sigmoid -> ein ONNX-Modell."""
    m = onnx.load(classifier_path)
    g = m.graph
    assert [i.name for i in g.input] == ["spectrogram"], [i.name for i in g.input]
    assert [o.name for o in g.output] == ["logits"], [o.name for o in g.output]
    if fp16:
        weights_to_fp16(g)

    fe_nodes, fe_inits = frontend_nodes(input_name, "spectrogram", variant)
    old_nodes = list(g.node)
    del g.node[:]
    g.node.extend(fe_nodes + old_nodes + [helper.make_node("Sigmoid", ["logits"], [output_name])])
    g.initializer.extend(fe_inits)

    del g.input[:]
    g.input.append(helper.make_tensor_value_info(input_name, TensorProto.FLOAT, [1, WINDOW_SAMPLES]))
    del g.output[:]
    g.output.append(helper.make_tensor_value_info(output_name, TensorProto.FLOAT, [1, 22]))
    if debug_features:
        g.output.append(helper.make_tensor_value_info("spectrogram", TensorProto.FLOAT, [1, 1, N_FRAMES, N_MELS]))
        g.output.append(helper.make_tensor_value_info("logits", TensorProto.FLOAT, [1, 22]))
    # Batch-Dimension war dynamisch; jetzt fest 1 -> Shape-Infos des Klassifikators bereinigen
    del g.value_info[:]
    g.name = "bsgbat_v021_frontend"
    for op in m.opset_import:
        if op.domain in ("", "ai.onnx") and op.version < 17:
            op.version = 17
    onnx.checker.check_model(m)
    return m

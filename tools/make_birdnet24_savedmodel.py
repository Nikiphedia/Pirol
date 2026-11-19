#!/usr/bin/env python
"""Fasst die beiden Signaturen des BirdNET-2.4-Protobuf-Modells ('basic' = Scores, 'embeddings' = 1024 Werte)
in ein SavedModel mit einer Signatur 'serving_default' zusammen, damit convert_model.py beide Ausgaenge
in einem ONNX bekommt (T147). Eingang: Roh-Audio [1,144000] @48 kHz.

Aufruf: python make_birdnet24_savedmodel.py <audio-model-Ordner> <Ausgabeordner>
"""
import sys

import tensorflow as tf

src, dst = sys.argv[1], sys.argv[2]
m = tf.saved_model.load(src)


class Combined(tf.Module):
    def __init__(self):
        super().__init__()
        self.m = m  # haelt die Variablen am Leben

    @tf.function(input_signature=[tf.TensorSpec([1, 144000], tf.float32, name="inputs")])
    def serve(self, inputs):
        return {"scores": m.signatures["basic"](inputs=inputs)["scores"],
                "embeddings": m.signatures["embeddings"](inputs=inputs)["embeddings"]}


c = Combined()
tf.saved_model.save(c, dst, signatures={"serving_default": c.serve})
print("gespeichert:", dst)

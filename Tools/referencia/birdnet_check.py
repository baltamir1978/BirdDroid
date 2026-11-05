"""Comprueba el modelo TFLite completo de BirdNET con audio de verdad: 48 kHz mono, 3 s, float32."""
import sys, av, numpy as np
from ai_edge_litert.interpreter import Interpreter
model, labels, audio = sys.argv[1:4]
c = av.open(audio); r = av.AudioResampler(format="flt", layout="mono", rate=48000)
pcm = np.concatenate([g.to_ndarray().reshape(-1) for f in c.decode(audio=0) for g in r.resample(f)]).astype(np.float32)
it = Interpreter(model_path=model); it.allocate_tensors()
inp = it.get_input_details()[0]; out = it.get_output_details()[0]
print("entrada", inp["shape"], inp["dtype"], "salida", out["shape"])
names = [l.strip() for l in open(labels, encoding="utf-8")]
for start in range(0, len(pcm) - 144000 + 1, 144000):
    w = pcm[start:start + 144000]
    it.set_tensor(inp["index"], w.reshape(1, 144000)); it.invoke()
    logits = it.get_tensor(out["index"])[0]
    p = 1 / (1 + np.exp(-1.3 * logits))           # sensibilidad 1,3 (la de iOS)
    top = np.argsort(-p)[:3]
    print(f"{start/48000:4.0f}s", [(names[i], round(float(p[i]), 2)) for i in top])

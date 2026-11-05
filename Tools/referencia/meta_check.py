"""La red de meta_weights.bin (como LocationFilter.swift) frente al modelo oficial MData de BirdNET."""
import sys, numpy as np
from ai_edge_litert.interpreter import Interpreter
weights, mdata, labels = sys.argv[1:4]
raw = open(weights, "rb").read(); assert raw[:4] == b"BMET"
f = np.frombuffer(raw[8:], dtype="<f4"); o = 48
def take(n):
    global o; a = f[o:o+n]; o += n; return a
W1 = take(256*144).reshape(256,144); b1 = take(256); W2 = take(512*256).reshape(512,256); b2 = take(512)
W3 = take(1024*512).reshape(1024,512); b3 = take(1024); W4 = take(6522*1024).reshape(6522,1024); b4 = take(6522)
def mine(lat, lon, week):
    n = [(lat+90)/180, (lon+180)/360, week/48]
    e = np.concatenate([np.sqrt(2)*np.sin(2*np.pi*x + np.arange(48)*np.pi/48) for x in n]).astype(np.float32)
    h = np.maximum(W1@e+b1, 0); h = np.maximum(W2@h+b2, 0); h = np.maximum(W3@h+b3, 0)
    return 1/(1+np.exp(-(W4@h+b4)))
it = Interpreter(model_path=mdata); it.allocate_tensors(); i = it.get_input_details()[0]; out = it.get_output_details()[0]
names = [l.strip() for l in open(labels, encoding="utf-8")]
for lat, lon, week in [(40.4168, -3.7038, 20), (43.42, -4.75, 4), (28.46, -16.25, 30)]:
    it.set_tensor(i["index"], np.array([[lat, lon, week]], dtype=np.float32)); it.invoke()
    ref = it.get_tensor(out["index"])[0]; m = mine(lat, lon, week)
    print(f"({lat},{lon}) semana {week}: correlación {np.corrcoef(ref, m)[0,1]:.5f}, máx dif {np.abs(ref-m).max():.4f}")
    for sp in ["Turdus merula", "Apus apus", "Ciconia ciconia"]:
        k = [j for j, n in enumerate(names) if n.startswith(sp + "_")][0]
        print(f"   {sp:16s} fila {k}: mía {m[k]:.5f}  oficial {ref[k]:.5f}")

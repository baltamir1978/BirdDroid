"""Comprueba Google AIY Birds V1: imagen 224×224 RGB uint8, salida 965 clases (con __background__)."""
import sys, zipfile, numpy as np, av
from ai_edge_litert.interpreter import Interpreter
model, image = sys.argv[1:3]
labels = zipfile.ZipFile(model).read("probability-labels-en.txt").decode().splitlines()
it = Interpreter(model_path=model); it.allocate_tensors()
inp = it.get_input_details()[0]; out = it.get_output_details()[0]
print("entrada", inp["shape"], inp["dtype"], inp["quantization"], "salida", out["shape"], out["dtype"], out["quantization"])
frame = next(av.open(image).decode(video=0))
w, h = frame.width, frame.height; s = min(w, h)
img = frame.reformat(width=w, height=h, format="rgb24").to_ndarray()
img = img[(h - s)//2:(h - s)//2 + s, (w - s)//2:(w - s)//2 + s]               # recorte central
f = av.VideoFrame.from_ndarray(np.ascontiguousarray(img), format="rgb24").reformat(width=224, height=224)
x = f.to_ndarray().reshape(1, 224, 224, 3).astype(inp["dtype"])
it.set_tensor(inp["index"], x); it.invoke()
y = it.get_tensor(out["index"])[0].astype(np.float32)
scale, zero = out["quantization"]
p = (y - zero) * scale if scale else y
for i in np.argsort(-p)[:3]: print(labels[i], round(float(p[i]), 3))

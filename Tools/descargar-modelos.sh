#!/usr/bin/env bash
# Baja los modelos a app/src/main/assets/models/ (no van en el repositorio: ~90 MB).
#   - BirdNET+ V3.0.1, Global 12K, versión recortada FP16 para móviles (una de las tres cabezas):
#     recibe el audio tal cual (32 kHz, 3 s) y da la probabilidad de 11.725 clases (aves, insectos,
#     anfibios, mamíferos y 16 sonidos). Licencia Apache 2.0. Desde la 0.6; antes, BirdNET V2.4.
#   - Sus etiquetas (CSV con 36 idiomas), de las que se sacan labels3*.txt («Científico_Común»).
#     Lo que quede en inglés se completa en app/src/main/assets/nombres/ (sí va en el repositorio).
#   - El Geomodel V3.0.5 (filtro por lugar y semana; LiteRT, 14.057 especies). El 3.0.4 de GitHub
#     no sirve: usa la operación Erf de TensorFlow, que el LiteRT normal no trae.
#   - Google AIY Birds V1 (fotos, 964 especies + fondo), de Kaggle.
#   - MediaPipe Magic Touch (recorta el pájaro a partir de un punto), de Google. Se usa con LiteRT, sin MediaPipe.
# Copia de todo en ~/Documentos/Software/ModelosBirdDroid por si una fuente desaparece.
set -euo pipefail
dest="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/assets/models"
mkdir -p "$dest"; cd "$dest"
# Lo de BirdNET V2.4 (hasta la 0.5): fuera, o acabaría dentro del APK.
rm -f birdnet.tflite meta_weights.bin labels.txt labels_es.txt labels_de.txt labels_fr.txt labels_it.txt labels_pt_PT.txt
get() { [ -s "$2" ] && { echo "ya está: $2"; return; }; echo "bajando $2"; curl -fSL --retry 3 -o "$2.part" "$1" && mv "$2.part" "$2"; }
check() { echo "$2  $1" | md5sum -c --quiet - || { echo "$1 no es el esperado: bórralo y vuelve a bajarlo" >&2; exit 1; }; }
ZENODO="https://zenodo.org/records/24362980/files"
get "$ZENODO/BirdNET+_V3.0_Global_12K_FP16_pruned.tflite?download=1" birdnet3.tflite
check birdnet3.tflite a3575f46173acbe089cabe076b85998b
if [ ! -s labels3.txt ]; then
  get "$ZENODO/BirdNET+_V3.0_Global_12K_Labels.csv?download=1" labels3.csv
  check labels3.csv bb25847af8a581e1382081382f01e762
  # Una fila por clase, en el orden de la salida del modelo. Idiomas: el de España y Portugal si lo hay.
  python3 - <<'PY'
import csv
rows = list(csv.DictReader(open("labels3.csv", encoding="utf-8")))
assert [int(r["ix"]) for r in rows] == list(range(len(rows)))
def write(name, cols):
    with open(name, "w", encoding="utf-8") as f:
        for r in rows:
            common = next((r[c] for c in cols if r.get(c)), r["common_name"])
            f.write(f'{r["scientific_name"]}_{common}\n')
write("labels3.txt", ["common_name_en"])
for lang, cols in {"es": ["common_name_es_ES", "common_name_es"], "de": ["common_name_de"], "fr": ["common_name_fr"],
                   "it": ["common_name_it"], "pt": ["common_name_pt_PT", "common_name_pt"]}.items():
    write(f"labels3_{lang}.txt", cols)
PY
  rm labels3.csv
fi
if [ ! -s geomodel.tflite ] || [ ! -s geomodel_labels.txt ]; then
  get "$ZENODO/BirdNET+_Geomodel_V3.0.5_Global_14K.zip?download=1" geomodel.zip
  check geomodel.zip d30b7f51ad301889ab193a384c72f1a1
  unzip -o -j -q geomodel.zip '*_FP16.tflite' '*_Labels.txt'
  mv BirdNET+_Geomodel_V3.0.5_Global_14K_FP16.tflite geomodel.tflite
  mv BirdNET+_Geomodel_V3.0.5_Global_14K_Labels.txt geomodel_labels.txt
  rm geomodel.zip
fi
get "https://storage.googleapis.com/mediapipe-models/interactive_segmenter/magic_touch/float32/1/magic_touch.tflite" magic_touch.tflite
if [ ! -s aiy_birds_v1.tflite ]; then
  echo "bajando aiy_birds_v1.tflite"
  curl -fSL --retry 3 -o aiy.tgz "https://www.kaggle.com/api/v1/models/google/aiy/tfLite/vision-classifier-birds-v1/3/download"
  tar xzf aiy.tgz && mv 3.tflite aiy_birds_v1.tflite && rm aiy.tgz
fi
ls -la "$dest"

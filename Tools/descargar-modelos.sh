#!/usr/bin/env bash
# Baja los modelos a app/src/main/assets/models/ (no van en el repositorio: ~70 MB).
#   - BirdNET GLOBAL 6K V2.4, modelo completo en TFLite (FP16): recibe el audio tal cual
#     (48 kHz, 3 s), sin espectrograma. Del repositorio oficial de BirdNET-Analyzer.
#   - Las etiquetas de BirdNET y sus nombres comunes por idioma, y meta_weights.bin (el filtro
#     por lugar y semana), del repositorio de BirdApp (la app de iOS), para dar lo mismo que iOS.
#   - Google AIY Birds V1 (fotos, 964 especies + fondo), de Kaggle.
# Licencia de BirdNET: CC BY-NC-SA 4.0 (uso personal, nada comercial).
set -euo pipefail
dest="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/assets/models"
mkdir -p "$dest"; cd "$dest"
get() { [ -s "$2" ] && { echo "ya está: $2"; return; }; echo "bajando $2"; curl -fSL --retry 3 -o "$2.part" "$1" && mv "$2.part" "$2"; }
get "https://github.com/birdnet-team/BirdNET-Analyzer/raw/v1.5.1/birdnet_analyzer/checkpoints/V2.4/BirdNET_GLOBAL_6K_V2.4_Model_FP16.tflite" birdnet.tflite
BIRDAPP="https://raw.githubusercontent.com/baltamir1978/BirdApp/main/BirdApp"
get "$BIRDAPP/meta_weights.bin" meta_weights.bin
get "$BIRDAPP/BirdNET_GLOBAL_6K_V2.4_Labels.txt" labels.txt
for l in es de fr it pt_PT; do get "$BIRDAPP/BirdNET_GLOBAL_6K_V2.4_Labels_$l.txt" "labels_$l.txt"; done
if [ ! -s aiy_birds_v1.tflite ]; then
  echo "bajando aiy_birds_v1.tflite"
  curl -fSL --retry 3 -o aiy.tgz "https://www.kaggle.com/api/v1/models/google/aiy/tfLite/vision-classifier-birds-v1/3/download"
  tar xzf aiy.tgz && mv 3.tflite aiy_birds_v1.tflite && rm aiy.tgz
fi
ls -la "$dest"

#!/usr/bin/env bash
# El icono de la app de iOS (PNG de 1024 px) como icono adaptable de Android, sin redibujarlo.
# Android recorta el icono con su forma (círculo, ardilla…) y deja ver sólo el 72/108 central:
# se amplía el lienzo rellenando con el color de los bordes, para que se vea el icono entero.
# Uso: Tools/icono-desde-ios.sh <AppIcon-1024.png> <carpeta res>
set -euo pipefail
src="$1"; res="$2"
mkdir -p "$res/drawable-nodpi" "$res/mipmap-anydpi-v26"
# Lienzo de 1894 px (el icono queda al 54 %, entero dentro del círculo), con los bordes estirados para rellenar.
convert "$src" -alpha off -virtual-pixel edge -set option:distort:viewport 1894x1894-435-435 \
  -distort SRT 0 +repage -resize 432x432 "$res/drawable-nodpi/ic_launcher_full.png"
convert "$src" -resize 256x256 "$res/drawable-nodpi/icon_ios.png"
cat > "$res/mipmap-anydpi-v26/ic_launcher.xml" <<XML
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_full" />
    <foreground android:drawable="@android:color/transparent" />
</adaptive-icon>
XML
echo "icono listo en $res"

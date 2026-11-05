#!/usr/bin/env bash
# Instala lo necesario para compilar las apps de Android en Linux, sin sudo y sin Android Studio:
#   - JDK 17 (Temurin)            → ~/Android/jdk
#   - Android SDK (cmdline-tools, platform-tools, android-35, build-tools 35.0.0) → ~/Android/Sdk
#   - Gradle 8.11.1 (sólo para generar el wrapper la primera vez) → ~/Android/gradle-8.11.1
# Y deja las variables en ~/.bashrc y el sdk.dir en local.properties.
#
# Se puede volver a ejecutar: lo que ya está instalado se salta.
# Uso: Tools/instalar-entorno.sh
set -euo pipefail

BASE="$HOME/Android"
JDK="$BASE/jdk"
SDK="$BASE/Sdk"
GRADLE_VERSION="8.11.1"
GRADLE="$BASE/gradle-$GRADLE_VERSION"
CMDTOOLS_ZIP="commandlinetools-linux-11076708_latest.zip"
REPO="$(cd "$(dirname "$0")/.." && pwd)"

need() { command -v "$1" >/dev/null || { echo "Falta «$1»: sudo apt install $2"; exit 1; }; }
need curl curl
need unzip unzip
need tar tar

mkdir -p "$BASE/dl"
cd "$BASE/dl"

if [ ! -x "$JDK/bin/java" ]; then
  echo "» JDK 17…"
  curl -fSL -o jdk.tar.gz "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
  mkdir -p "$JDK" && tar -xzf jdk.tar.gz -C "$JDK" --strip-components=1
fi

if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo "» Android command-line tools…"
  curl -fSL -o cmdtools.zip "https://dl.google.com/android/repository/$CMDTOOLS_ZIP"
  mkdir -p "$SDK/cmdline-tools"
  rm -rf "$SDK/cmdline-tools/latest" "$SDK/cmdline-tools/cmdline-tools"
  unzip -q -o cmdtools.zip -d "$SDK/cmdline-tools"
  mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"
fi

if [ ! -x "$GRADLE/bin/gradle" ]; then
  echo "» Gradle $GRADLE_VERSION…"
  curl -fSL -o gradle.zip "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
  unzip -q -o gradle.zip -d "$BASE"
fi

export JAVA_HOME="$JDK" ANDROID_HOME="$SDK"
SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"

echo "» Licencias y paquetes del SDK…"
yes | "$SDKMANAGER" --licenses >/dev/null || true
"$SDKMANAGER" "platform-tools" "platforms;android-35" "build-tools;35.0.0"

# Variables para las próximas terminales
if ! grep -q "# Android: entorno de compilación (JDK, SDK)" "$HOME/.bashrc" 2>/dev/null; then
  cat >> "$HOME/.bashrc" <<EOF

# Android: entorno de compilación (JDK, SDK)
export JAVA_HOME="$JDK"
export ANDROID_HOME="$SDK"
export PATH="\$JAVA_HOME/bin:\$ANDROID_HOME/platform-tools:\$PATH"
EOF
  echo "» Añadidas JAVA_HOME y ANDROID_HOME a ~/.bashrc"
fi

# Dónde está el SDK, para Gradle (local.properties está en .gitignore)
if [ -d "$REPO" ] && ! grep -q '^sdk.dir=' "$REPO/local.properties" 2>/dev/null; then
  echo "sdk.dir=$SDK" >> "$REPO/local.properties"
fi

# El wrapper de Gradle, si el repositorio aún no lo tiene
if [ -f "$REPO/settings.gradle.kts" ] && [ ! -f "$REPO/gradlew" ]; then
  (cd "$REPO" && "$GRADLE/bin/gradle" wrapper --gradle-version "$GRADLE_VERSION")
fi

echo
"$JDK/bin/java" -version 2>&1 | head -1
echo "SDK en $SDK"
echo "Listo. Abre una terminal nueva (o: source ~/.bashrc) y compila con: ./gradlew assembleDebug"
echo "Para instalar en el móvil por USB: adb install -r app/build/outputs/apk/debug/app-debug.apk"

# BirdDroid

Identifica aves **por su canto y por foto**, con todos los modelos ejecutándose en el móvil.

Versión para Android de [BirdApp](https://github.com/baltamir1978/BirdApp) (iOS), escrita desde
cero en Kotlin + Jetpack Compose. Funciona sin Google Play (GrapheneOS): LiteRT va dentro de la
app.

**Estado: 0.1** (5-nov-2026), sin probar todavía en el móvil. Ver `CLAUDE.md`.

## Qué hace

- **Por el canto** con BirdNET (GLOBAL 6K V2.4), en directo y también con la app minimizada.
- **Por foto** con Google AIY Birds V1, con las especies pasadas a la taxonomía de BirdNET.
- **Filtro por lugar y temporada**, con el sitio y la fecha **del EXIF** en las fotos.
- **Fusión**: al identificar una foto, se prefieren las especies oídas hace poco.
- **Desempate**: si el segundo candidato queda cerca del primero, pregunta.
- Historial, ficha de Wikipedia y canto de xeno-canto.

## Compilar

```sh
Tools/instalar-entorno.sh       # JDK 17, Android SDK y Gradle en ~/Android, sin sudo
Tools/descargar-modelos.sh      # ~70 MB de modelos (no están en el repositorio)
./gradlew testDebugUnitTest assembleRelease
```

## Licencias

BirdNET: CC BY-NC-SA 4.0 (Cornell Lab of Ornithology y TU Chemnitz), uso personal y no
comercial. Google AIY Birds V1: Apache 2.0. Wikipedia: CC BY-SA. xeno-canto: según cada grabación.

# BirdDroid

Identifica aves **por su canto y por foto**, con todos los modelos ejecutándose en el móvil.

Versión para Android de [BirdApp](https://github.com/baltamir1978/BirdApp) (iOS), escrita desde
cero en Kotlin + Jetpack Compose. Funciona sin Google Play (GrapheneOS): LiteRT va dentro de la
app.

**Estado: 0.6.0** (25-nov-2026): pasa a BirdNET+ V3; sin probar todavía en el móvil. Ver `CLAUDE.md`.

## Qué hace

- **Por el canto** con BirdNET+ V3 (11.725 clases: aves, también insectos, anfibios y mamíferos), en directo y también con la app minimizada. La pantalla dice cuánto tarda el modelo en cada ventana.
  Mientras escucha, un pájaro en el centro se llena de verde con el volumen, como en BirdApp. Si
  el micrófono o el modelo fallan, lo dice y deja de escuchar.
- **Por foto** con Google AIY Birds V1, con las especies pasadas a la taxonomía de BirdNET. La app busca el pájaro en la foto; con un toque se recorta por su silueta (MediaPipe Magic Touch) y también se puede arrastrar un recuadro.
- **Filtro por lugar y temporada** con el Geomodel de BirdNET+, con el sitio y la fecha **del EXIF** en las fotos.
- **Fusión**: al identificar una foto, se prefieren las especies oídas hace poco.
- **Desempate**: si el segundo candidato queda cerca del primero, pregunta.
- **Buscar** un ave por su nombre, también el popular («lavandera», «pajarita de las nieves»): foto, descripción y canto.
- Historial (se borra deslizando a la izquierda), ficha de Wikipedia y canto de xeno-canto.
- **Idiomas**: interfaz en español, inglés, alemán, francés, italiano y portugués, con los nombres
  de las especies y los sonidos (sirena, tren…) en cada idioma.

## Compilar

```sh
Tools/instalar-entorno.sh       # JDK 17, Android SDK y Gradle en ~/Android, sin sudo
Tools/descargar-modelos.sh      # ~90 MB de modelos (no están en el repositorio)
./gradlew testDebugUnitTest lintDebug assembleRelease
```

Hace falta clonar también [droid-common](https://github.com/baltamir1978/droid-common) **en la
carpeta de al lado** (`../droid-common`): el código que comparten las apps de Android, que Gradle
incluye desde ahí. En cada push, GitHub Actions pasa las pruebas y el lint
(`.github/workflows/android.yml`).

Los nombres que las etiquetas de BirdNET dejan en inglés se completan en
`app/src/main/assets/nombres/`, que sí está en el repositorio. Para regenerarlos desde Wikidata:
`python3 Tools/completar-nombres.py`.

## Licencias

BirdNET+ V3 y su Geomodel: Apache 2.0 (Cornell Lab of Ornithology y TU Chemnitz). Google AIY Birds V1: Apache 2.0. Wikipedia: CC BY-SA. Wikidata: CC0. xeno-canto: según cada grabación.

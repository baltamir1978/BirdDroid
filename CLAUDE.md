# BirdDroid — contexto del repositorio

Versión para Android de **BirdApp** (iOS), escrita **desde cero en Kotlin + Jetpack Compose**. Lo
común a las apps de Android (firma, `versionCode`, secretos, distribución) está en el `CLAUDE.md` de
la carpeta de proyectos; aquí va lo propio de esta.

## Estado (5-nov-2026)

**0.1: compila y pasan 8 pruebas; sin probar en el móvil** (micrófono y cámara). APK en
`~/Documentos/BirdDroid-0.1.apk` (66 MB, casi todo modelos; sólo arm64).

Comprobado aquí, en Python con LiteRT (`Tools/referencia/`):
- **BirdNET completo en TFLite** (FP16, del repo oficial de BirdNET-Analyzer) con audio tal cual,
  48 kHz y 3 s, sin espectrograma: un mirlo de Wikimedia sale «Turdus merula» al 100 %.
- **AIY Birds V1** (Kaggle, 224×224 RGB uint8; salida uint8 con escala 1/256): una foto de mirlo,
  97 %. **Su clase de fondo se llama `None`** (en la conversión de iOS era `__background__`).
- **El filtro por lugar y semana** con `meta_weights.bin` de iOS: correlación 0,9997 con el modelo
  oficial MData de BirdNET; la traducción a Kotlin da los mismos números (prueba).

Hecho, traducido de BirdApp: escucha en un servicio de tipo micrófono (ventanas de 3 s cada
0,8 s, puertas de saturación y de señal, paso alto, sigmoide con sensibilidad, filtro suave de
lugar al estilo whoBIRD, suavizado), candidatos y «pensando», historial sin repetir la misma
especie en 10 min, **desempate** cuando el segundo queda al 65 % o más, fotos (galería con
`ACCESS_MEDIA_LOCATION` y `setRequireOriginal`, cámara, compartir desde otra app), encuadre
arrastrando un recuadro, EXIF (sitio y fecha para el filtro), **fusión audio↔foto** (lo oído en
10 min sube y se renormaliza), ficha de Wikipedia, canto de xeno-canto (con clave), ajustes.

Falta: **segmentación con un toque** (MediaPipe Interactive Image Segmenter: ahora el encuadre es
a mano o el cuadrado central), el modelo ibérico de iOS, widgets, accesos directos, traducciones
(es y en ahora; iOS tiene más), y probar en el móvil (sobre todo si la ubicación del EXIF llega
con fotos compartidas desde Google Fotos).

**Los modelos no van en el repositorio** (decidido el 5-nov-2026; el plan era Git LFS, pero aquí
no había `git-lfs` y se prefirió no instalar binarios de fuera): `Tools/descargar-modelos.sh`
los baja de sus fuentes oficiales a `app/src/main/assets/models/` (en `.gitignore`), y la
compilación avisa si faltan.

## La especificación es el original

`../BirdApp` (`github.com/baltamir1978/BirdApp`): leer su `README.md` y su `REVISION.md`. Qué
hace, todo **en el dispositivo**:

- **Identificación por el canto** con BirdNET (GLOBAL_6K V2.4), en directo desde el micrófono y
  también en segundo plano (opcional, con auto-apagado).
- **Identificación por foto** con Google AIY Birds V1 (964 especies), con las especies traducidas a
  la taxonomía de BirdNET.
- **Encuadre manual del ave**: un toque sobre el pájaro lo recorta (segmentación de sujetos) o se
  arrastra un recuadro a mano.
- **Compartir desde la galería** (y otras apps) con el EXIF intacto.
- **Fusión audio↔foto**: al identificar una foto, se prefieren las especies oídas en los últimos
  minutos.
- **Filtro por ubicación y temporada**, con la ubicación y la fecha **del EXIF** en las fotos de la
  galería.
- **Desempate manual** cuando el segundo candidato queda a menos del 35 % del primero.
- Cantos de xeno-canto (clave en Ajustes), ficha con imagen de Wikipedia, historial sin duplicados,
  nombres en muchos idiomas, widgets y ajustes avanzados de detección.

## Cómo se hace en Android

| En iOS | Aquí |
|---|---|
| Core ML | **LiteRT** (antes TensorFlow Lite), **con el runtime empaquetado en la app**, no la versión de Google Play Services (GrapheneOS). BirdNET nació en TFLite: `../BirdApp/_modelos/` ya tiene `BirdNET_classifier_only.tflite` |
| `MelSpectrogramExtractor` + `filterbank1/2.bin` | Dos caminos: el modelo *classifier only* con el espectrograma calculado en Kotlin (como en iOS), o **el modelo completo oficial de BirdNET**, que recibe el audio tal cual (48 kHz, 3 s) y se ahorra el espectrograma. Probar primero el completo |
| `meta_weights.bin` (filtro por lugar y semana) | El modelo de metadatos oficial de BirdNET en TFLite |
| Modelo de fotos en Core ML | Google AIY Birds V1 existe en TFLite (Kaggle) |
| Segmentación de sujetos de Vision | **No ML Kit**: su segmentación de sujetos se descarga por Google Play Services, que GrapheneOS no tiene. El candidato es **MediaPipe Interactive Image Segmenter**, que recorta el objeto a partir del punto tocado (justo el gesto del original) y lleva el modelo dentro de la app. Comprobar que funciona sin Google Play |
| Extensión de compartir `BirdShare` | `intent-filter` con `ACTION_SEND` de `image/*`: mucho más sencillo que en iOS |
| Escucha en segundo plano | Servicio en primer plano de tipo `microphone`, con notificación fija. **Desde Android 14 sólo se puede arrancar con la app delante** |
| WidgetKit + notificaciones Darwin | Glance; el botón de escuchar habla con el servicio |
| App Shortcuts / Siri | Accesos directos de la app |

## Trampas conocidas

- **La ubicación del EXIF viene borrada** salvo que la app tenga `ACCESS_MEDIA_LOCATION` y pida el
  original (`MediaStore.setRequireOriginal`). Con fotos compartidas desde Google Fotos puede llegar
  sin ubicación igualmente: comprobarlo en el móvil, porque el filtro por lugar depende de ello.
- **Los modelos pesan** (~50 MB el de BirdNET): Git LFS desde el primer commit, como se recomendó en
  el original.
- **Licencia de BirdNET**: CC BY-NC-SA 4.0. Uso personal sin problema; no distribuir con fines
  comerciales.
- **La clave de xeno-canto** en `local.properties` para desarrollo, nunca en el repositorio.

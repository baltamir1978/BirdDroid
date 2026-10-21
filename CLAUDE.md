# BirdDroid — contexto del repositorio

Versión para Android de **BirdApp** (iOS), escrita **desde cero en Kotlin + Jetpack Compose**. Lo
común a las apps de Android (firma, `versionCode`, secretos, distribución) está en el `CLAUDE.md` de
la carpeta de proyectos; aquí va lo propio de esta.

## Estado (21-oct-2026)

**Sin empezar.** Necesita móvil de verdad (micrófono y cámara), que Bruno aún no tiene.

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
| Core ML | **LiteRT** (antes TensorFlow Lite). BirdNET nació en TFLite: `../BirdApp/_modelos/` ya tiene `BirdNET_classifier_only.tflite` |
| `MelSpectrogramExtractor` + `filterbank1/2.bin` | Dos caminos: el modelo *classifier only* con el espectrograma calculado en Kotlin (como en iOS), o **el modelo completo oficial de BirdNET**, que recibe el audio tal cual (48 kHz, 3 s) y se ahorra el espectrograma. Probar primero el completo |
| `meta_weights.bin` (filtro por lugar y semana) | El modelo de metadatos oficial de BirdNET en TFLite |
| Modelo de fotos en Core ML | Google AIY Birds V1 existe en TFLite (Kaggle) |
| Segmentación de sujetos de Vision | ML Kit, segmentación de sujetos |
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

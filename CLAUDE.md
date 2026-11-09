# BirdDroid — contexto del repositorio

Versión para Android de **BirdApp** (iOS), escrita **desde cero en Kotlin + Jetpack Compose**. Lo
común a las apps de Android (firma, `versionCode`, secretos, distribución) está en el `CLAUDE.md` de
la carpeta de proyectos; aquí va lo propio de esta.

## Estado (9-nov-2026)

**0.5.1 (9-nov-2026):** los títulos de las pestañas ya no se montan con la barra de estado. `AdaptiveNavigation` hacía `consumeWindowInsets(padding)` aplicando sólo el margen inferior: la parte de arriba quedaba consumida y el `windowInsetsPadding` siguiente daba 0. Sin probar en el móvil.

**0.5 (9-nov-2026): pestaña Buscar.** Se escribe el nombre con el que se conoce un ave y sale su foto, su descripción y su canto (`SearchScreen` y `SpeciesScreen` en `ui/Main.kt`, `SpeciesSearch` en `core/Core.kt`). Busca en local, sin acentos y con palabras a medias, en el idioma del móvil, español, inglés, latín y los nombres populares de `assets/nombres/es-espana.txt`, que sí va en el repositorio y sale de `Tools/nombres-populares.py` (Anexo:Aves de España de la Wikipedia, modelo de lugar de BirdNET, alias de Wikidata y redirecciones de la Wikipedia en español). Las especies de España salen antes. Ese fichero incluye las ~30 aves de España que BirdNET no tiene (quebrantahuesos, avutarda, alimoche…). Si en local no hay nada, mira qué artículos de aves de la Wikipedia citan ese nombre («pitita» → lavandera blanca). El canto prefiere una grabación de tipo «song». Sin probar en el móvil; el canto tampoco aquí, porque hace falta la clave de xeno-canto.

**0.4 (9-nov-2026): la foto, como en iOS.** Toda foto pasa por una pantalla de encuadre (`ui/Photo.kt`, copia de `PhotoFramingView`) con el pájaro ya seleccionado, y el resultado sale bajo una vista previa de 200 dp (antes quedaba por debajo de la foto, fuera de la pantalla). El recuadro salía negro por usar `BlendMode.Clear` sin capa propia: borraba la ventana. El pájaro se busca solo con AIY Birds en ventanas de 5 tamaños (lado corto reducido a 896 px, parando al llegar a 0,9) y luego **Magic Touch** (`magic_touch.tflite`, 6 MB, con LiteRT tal cual: sin MediaPipe ni Google Play) ciñe el recuadro; un toque recorta por la silueta. El recorte ahora es cuadrado y se reduce promediando (`core/Framing.kt`): con `createScaledBitmap` había aliasing. Comprobado en Python con el mismo flujo: 20 de 20 en pájaros de entre el 50 y el 12 % del encuadre pegados sobre un paisaje. **AIY no sabe si hay pájaro**: ve patos en un coche (0,94) y su clase `None` casi nunca gana, así que su nota sólo sirve para localizar; en una foto sin aves preselecciona algo y da una especie al azar (también en iOS). Sin probar en el móvil.

**0.3.3 (8-nov-2026):** las filas de «Últimas» y del historial muestran fecha y hora, no sólo la hora.

**0.3.2 (8-nov-2026): imágenes y traducciones.** Coil usa ya el cliente con User-Agent propio: `thumb.wikimedia.org` da 403 al de serie de OkHttp, y por eso no salía ninguna foto. Los 11 sonidos de BirdNET (`Labels.NON_SPECIES`: perro, motor, sirena…) se traducen en `res` y no tienen nombre científico, ficha ni canto. Lo que las etiquetas oficiales dejan en inglés (el italiano, casi todo) se completa desde Wikidata en `assets/nombres/<idioma>.txt`, que sí va en el repositorio y sale de `Tools/completar-nombres.py`; en español quedan 80 en inglés, casi todos grillos y ranas de Norteamérica sin nombre común. Los nombres del historial se rehacen al cargar. La interfaz está también en de, fr, it y pt. La barra de estado la reserva `AdaptiveNavigation`, y las listas se borran deslizando a la izquierda. Sin probar en el móvil.

**0.3.1 (6-nov-2026): modo escritorio** — en ventana ancha (modo escritorio, tableta) el contenido se centra a 720 dp con el margen como `contentPadding` (la rueda del ratón desplaza también sobre él), y la actividad no se recrea al redimensionar (`configChanges`). Piezas en `ui/Adaptive.kt`; sin probar en modo escritorio de verdad. En ventana ancha la barra inferior pasa a riel a la izquierda (`AdaptiveNavigation`). APK en `~/Documentos/BirdDroid-0.3.1.apk`.

**0.3: compila y pasan 9 pruebas.** APK en `~/Documentos/BirdDroid-0.3.apk` (66 MB, casi todo
modelos; sólo arm64). En el móvil, la 0.2 oía pájaros cerca pero se quedaba en «¿quizá…?». La 0.3:
- **La sensibilidad iba al revés** (también en iOS): se usaba como pendiente de la sigmoide, y con
  el umbral por debajo del 50 % una pendiente mayor exige más. Ahora es como en BirdNET-Analyzer:
  pendiente = 2 − sensibilidad (`Settings.slope`).
- **La ganancia no importa**: BirdNET normaliza cada ventana (mismo logit con el audio a 1/10.000,
  comprobado en Python). Subir el volumen no ayuda; lo que cuenta es la relación señal/ruido.
- La captura ya no espera al modelo (antes se analizaba dentro del bucle de lectura con un búfer
  de 1 s y se perdían muestras); el medidor va en dB; Ajustes comparte los últimos 15 s en WAV
  para analizarlos aquí con `Tools/referencia/birdnet_check.py`.

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

Falta: el modelo ibérico de iOS, widgets, accesos directos, y probar en el móvil (sobre todo si la ubicación del EXIF llega
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
| Segmentación de sujetos de Vision | **No ML Kit**: su segmentación de sujetos se descarga por Google Play Services, que GrapheneOS no tiene. Se usa **Magic Touch**, el modelo de MediaPipe Interactive Image Segmenter, directamente con LiteRT: RGB en 0–1 más un cuarto canal con un disco blanco donde se tocó |
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

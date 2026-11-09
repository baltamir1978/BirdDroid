package es.altamirano.bird.data

import android.content.Context
import es.altamirano.bird.BuildConfig
import es.altamirano.bird.R
import es.altamirano.bird.core.Candidate
import es.altamirano.bird.core.FUSION_WINDOW_S
import es.altamirano.bird.core.Framing
import es.altamirano.bird.core.Labels
import es.altamirano.bird.core.LocationFilter
import es.altamirano.bird.core.Settings
import es.altamirano.bird.core.SpeciesSearch
import es.altamirano.bird.core.TIEBREAK_RATIO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.Locale
import java.util.UUID

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

@Serializable
data class Alternative(val scientific: String, val name: String, val confidence: Double, val imageUrl: String? = null)

/** Un avistamiento (`BirdDetection`): por el canto o por foto, con los finalistas que estuvieron cerca. */
@Serializable
data class Detection(
    val id: String = UUID.randomUUID().toString(),
    val scientific: String,
    val name: String,
    val confidence: Double,
    val at: Long = System.currentTimeMillis(),
    val lat: Double? = null,
    val lon: Double? = null,
    val photo: Boolean = false,
    val imageUrl: String? = null,
    val alternatives: List<Alternative> = emptyList(),
) {
    val needsTiebreak get() = alternatives.isNotEmpty()

    /** Era otro: el que ganaba pasa a ser alternativa, así se puede deshacer. */
    fun choose(a: Alternative): Detection {
        if (a.scientific == scientific) return this
        val rest = alternatives.filter { it.scientific != a.scientific } + Alternative(scientific, name, confidence, imageUrl)
        return copy(scientific = a.scientific, name = a.name, confidence = a.confidence, imageUrl = a.imageUrl,
            alternatives = rest.sortedByDescending { it.confidence })
    }

    fun localized(l: Labels) = copy(name = l.nameOf(scientific) ?: name,
        alternatives = alternatives.map { it.copy(name = l.nameOf(it.scientific) ?: it.name) })
}

/** El ganador, con los que quedaron lo bastante cerca como para preguntar (`topCarryingCloseCalls`). */
fun List<Candidate>.toDetection(lat: Double?, lon: Double?, photo: Boolean): Detection? {
    val top = firstOrNull() ?: return null
    val close = drop(1).filter { it.confidence >= top.confidence * TIEBREAK_RATIO }.map { Alternative(it.scientific, it.name, it.confidence) }
    return Detection(scientific = top.scientific, name = top.name, confidence = top.confidence, lat = lat, lon = lon, photo = photo, alternatives = close)
}

/**
 * El historial. Mientras se sigue oyendo la misma especie, no se apunta una vez por ventana de
 * 0,8 s: una sola entrada cada 10 minutos, que guarda la confianza más alta.
 */
class History(context: Context, labels: () -> Labels) {
    private val file = File(context.filesDir, "historial.json")
    // Los nombres se guardan al detectar: se rehacen al cargar, por las traducciones nuevas o si cambia el idioma.
    private val _items = MutableStateFlow(runCatching { json.decodeFromString(ListSerializer(Detection.serializer()), file.readText()) }.getOrDefault(emptyList())
        .let { saved -> if (saved.isEmpty()) saved else labels().let { l -> saved.map { it.localized(l) } } })
    val items: StateFlow<List<Detection>> = _items.asStateFlow()

    fun record(d: Detection): Detection {
        var result = d
        _items.update { list ->
            val i = list.indexOfFirst { it.scientific == d.scientific && (d.at - it.at) / 1000 < FUSION_WINDOW_S }
            if (i >= 0) list.toMutableList().also { l ->
                if (d.confidence > l[i].confidence) l[i] = l[i].copy(confidence = d.confidence, alternatives = d.alternatives)
                result = l[i]
            } else listOf(d) + list
        }
        save(); return result
    }

    fun replace(d: Detection) { _items.update { l -> l.map { if (it.id == d.id) d else it } }; save() }
    fun remove(d: Detection) { _items.update { l -> l.filterNot { it.id == d.id } }; save() }
    fun clear() { _items.value = emptyList(); save() }

    /** Especies oídas en los últimos 10 min (científico en minúsculas → cuándo), para las fotos. */
    fun recentlyHeard(nowMs: Long = System.currentTimeMillis()): Map<String, Long> =
        _items.value.filter { !it.photo && (nowMs - it.at) / 1000 < FUSION_WINDOW_S }.associate { it.scientific.lowercase() to it.at / 1000 }

    private fun save() {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(ListSerializer(Detection.serializer()), _items.value)); tmp.renameTo(file)
    }
}

class Prefs(context: Context) {
    private val p = context.getSharedPreferences("ajustes", Context.MODE_PRIVATE)
    private val _s = MutableStateFlow(read())
    val settings: StateFlow<Settings> = _s.asStateFlow()

    private fun read() = Settings(
        threshold = p.getFloat("umbral", 0.35f), sensitivity = p.getFloat("sensibilidad", 1.3f),
        smoothing = p.getBoolean("suavizado", false), locationInfluence = p.getFloat("filtro_lugar", 0.7f),
        highPass = p.getBoolean("paso_alto", false), highPassCutoff = p.getFloat("paso_alto_hz", 200f),
        signalGate = p.getBoolean("puerta_senal", false), clipGate = p.getBoolean("puerta_saturacion", true),
        fusion = p.getBoolean("fusion", true),
    )

    fun update(f: (Settings) -> Settings) {
        val s = f(_s.value); _s.value = s
        p.edit().putFloat("umbral", s.threshold).putFloat("sensibilidad", s.sensitivity).putBoolean("suavizado", s.smoothing)
            .putFloat("filtro_lugar", s.locationInfluence).putBoolean("paso_alto", s.highPass).putFloat("paso_alto_hz", s.highPassCutoff)
            .putBoolean("puerta_senal", s.signalGate).putBoolean("puerta_saturacion", s.clipGate).putBoolean("fusion", s.fusion).apply()
    }

    var xenoCantoKey: String
        get() = p.getString("xc", null) ?: BuildConfig.XENO_CANTO_KEY
        set(v) { p.edit().putString("xc", v.trim()).apply() }

    var backgroundMinutes: Int
        get() = p.getInt("fondo_min", 30)
        set(v) { p.edit().putInt("fondo_min", v).apply() }
}

/** Los sonidos que no son especies: en las etiquetas de BirdNET vienen en inglés en casi todos los idiomas. */
private val SOUNDS = mapOf(
    "Dog" to R.string.sound_dog, "Engine" to R.string.sound_engine, "Environmental" to R.string.sound_environmental,
    "Fireworks" to R.string.sound_fireworks, "Gun" to R.string.sound_gun, "Human non-vocal" to R.string.sound_human,
    "Human vocal" to R.string.sound_voice, "Human whistle" to R.string.sound_whistle, "Noise" to R.string.sound_noise,
    "Power tools" to R.string.sound_tools, "Siren" to R.string.sound_siren,
)

/**
 * Los modelos, con LiteRT **empaquetado en la app** (no el de Google Play Services: GrapheneOS).
 * BirdNET completo recibe el audio tal cual (48 kHz, 3 s): no hace falta el espectrograma de iOS.
 */
class Models(private val context: Context) {
    private fun map(name: String): MappedByteBuffer = context.assets.openFd("models/$name").use { fd ->
        FileInputStream(fd.fileDescriptor).channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
    }

    val labels: Labels by lazy {
        val lang = Locale.getDefault().language
        val file = when (lang) { "es", "de", "fr", "it" -> "labels_$lang.txt"; "pt" -> "labels_pt_PT.txt"; else -> null }
        // Lo que las etiquetas oficiales dejan en inglés (Tools/completar-nombres.py) y los sonidos.
        val completed = if (file == null) emptyMap() else completedNames(lang)
        Labels(read("labels.txt"), file?.let { runCatching { read(it) }.getOrNull() }, completed + SOUNDS.mapValues { context.getString(it.value) })
    }
    private fun read(name: String) = context.assets.open("models/$name").bufferedReader().readLines()
    private fun completedNames(lang: String): Map<String, String> = runCatching {
        context.assets.open("nombres/$lang.txt").bufferedReader().readLines().filter { '_' in it }.associate { it.substringBefore('_') to it.substringAfter('_') }
    }.getOrDefault(emptyMap())

    /** Buscar: los nombres en español valen sea cual sea el idioma del móvil. */
    val search: SpeciesSearch by lazy {
        val spanish = read("labels_es.txt").filter { '_' in it }.associate { it.substringBefore('_') to it.substringAfter('_') } + completedNames("es")
        val spain = context.assets.open("nombres/es-espana.txt").bufferedReader().readLines().filter { it.isNotBlank() }
            .map { it.split('|') }.associate { it[0] to it.drop(1) }
        SpeciesSearch(labels, spanish, spain)
    }

    val filter: LocationFilter by lazy { LocationFilter(map("meta_weights.bin")) }

    private val birdnet: Interpreter by lazy { Interpreter(map("birdnet.tflite"), Interpreter.Options().setNumThreads(2)) }
    private val output = Array(1) { FloatArray(6522) }

    /** Los logits de las 6.522 especies para una ventana de 3 s. */
    @Synchronized
    fun birdnet(window: FloatArray): FloatArray {
        birdnet.run(arrayOf(window), output)
        return output[0].copyOf()
    }

    // --- Fotos: Google AIY Birds V1 (224×224 RGB, cuantizado) ------------------------------

    private val aiyBuffer: MappedByteBuffer by lazy { map("aiy_birds_v1.tflite") }
    private val aiy: Interpreter by lazy { Interpreter(aiyBuffer, Interpreter.Options().setNumThreads(4)) }

    /** Las 965 etiquetas (nombres científicos) van dentro del propio .tflite, en un zip al final. */
    val aiyLabels: List<String> by lazy {
        val bytes = ByteArray(aiyBuffer.capacity()).also { aiyBuffer.duplicate().get(it) }
        java.util.zip.ZipInputStream(bytes.inputStream().let { s ->
            // El zip empieza tras el flatbuffer: se busca su cabecera «PK\3\4».
            val start = (0 until bytes.size - 4).first { bytes[it] == 0x50.toByte() && bytes[it + 1] == 0x4B.toByte() && bytes[it + 2] == 3.toByte() && bytes[it + 3] == 4.toByte() }
            bytes.inputStream(start, bytes.size - start)
        }).use { zip ->
            var e = zip.nextEntry
            while (e != null && e.name != "probability-labels-en.txt") e = zip.nextEntry
            zip.readBytes().decodeToString().lines()
        }
    }

    /** La clase «no hay pájaro»: «None» en el .tflite de Kaggle, «__background__» en la conversión de iOS. */
    val aiyBackground: Int by lazy { aiyLabels.indexOfFirst { it == "None" || it == "__background__" } }

    private val aiyInput = ByteBuffer.allocateDirect(Framing.MODEL_SIDE * Framing.MODEL_SIDE * 3).order(ByteOrder.nativeOrder())
    private val aiyOutput = Array(1) { ByteArray(965) }

    /** Probabilidad (0–1) de cada clase para una imagen ARGB de 224×224. */
    @Synchronized
    fun classifyPhoto(px: IntArray): FloatArray {
        aiyInput.rewind()
        for (c in px) { aiyInput.put((c shr 16 and 0xFF).toByte()); aiyInput.put((c shr 8 and 0xFF).toByte()); aiyInput.put((c and 0xFF).toByte()) }
        aiyInput.rewind()
        aiy.run(aiyInput, aiyOutput)
        // Salida cuantizada: escala 1/256, sin desplazamiento.
        return FloatArray(965) { (aiyOutput[0][it].toInt() and 0xFF) / 256f }
    }

    // --- Recorte con un toque: MediaPipe Magic Touch, con LiteRT tal cual ------------------

    private val magicTouch: Interpreter by lazy { Interpreter(map("magic_touch.tflite"), Interpreter.Options().setNumThreads(4)) }
    private val magicInput = ByteBuffer.allocateDirect(Framing.MASK_SIDE * Framing.MASK_SIDE * 4 * 4).order(ByteOrder.nativeOrder())
    private val magicOutput = ByteBuffer.allocateDirect(Framing.MASK_SIDE * Framing.MASK_SIDE * 4).order(ByteOrder.nativeOrder())

    /**
     * La máscara (512×512, 0–1) del objeto en el punto (0–1) de una imagen ARGB de 512×512.
     * Entrada: RGB en 0–1 y, como cuarto canal, un disco blanco donde se tocó.
     */
    @Synchronized
    fun segment(px: IntArray, tapX: Float, tapY: Float): FloatArray {
        val side = Framing.MASK_SIDE
        val cx = tapX * side; val cy = tapY * side
        magicInput.rewind()
        for (y in 0 until side) for (x in 0 until side) {
            val c = px[y * side + x]
            magicInput.putFloat((c shr 16 and 0xFF) / 255f); magicInput.putFloat((c shr 8 and 0xFF) / 255f); magicInput.putFloat((c and 0xFF) / 255f)
            val dx = x - cx; val dy = y - cy
            magicInput.putFloat(if (dx * dx + dy * dy <= TAP_RADIUS * TAP_RADIUS) 1f else 0f)
        }
        magicInput.rewind(); magicOutput.rewind()
        magicTouch.run(magicInput, magicOutput)
        magicOutput.rewind()
        return FloatArray(side * side).also { magicOutput.asFloatBuffer().get(it) }
    }

    private companion object { const val TAP_RADIUS = 6f }
}

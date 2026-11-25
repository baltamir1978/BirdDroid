package es.altamirano.bird.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/** Las 11.725 clases de BirdNET+ V3: «Científico_Común» por fila; la fila es la clase del modelo. */
class Labels(lines: List<String>, localized: List<String>?, private val extra: Map<String, String> = emptyMap()) {
    val scientific: List<String>
    val common: List<String>
    /** Nombres en el idioma del móvil, sólo si el fichero casa fila a fila. */
    val localizedCommon: List<String>?
    private val index: Map<String, Int>

    init {
        val rows = lines.filter { it.isNotBlank() }
        scientific = rows.map { it.substringBefore('_') }
        common = rows.map { it.substringAfter('_', it) }
        localizedCommon = localized?.filter { it.isNotBlank() }?.map { it.substringAfter('_', it) }?.takeIf { it.size == rows.size }
        index = scientific.withIndex().associate { (i, s) -> s.lowercase() to i }
    }

    val size get() = scientific.size
    fun row(scientificName: String): Int? = index[scientificName.lowercase()] ?: index[binomial(scientificName).lowercase()]
    /**
     * El nombre en el idioma del móvil. Manda el de las etiquetas; lo completado ([extra]) sólo si
     * las etiquetas lo dejan en inglés (en los ficheros, lo que falta va con el nombre inglés).
     */
    fun displayName(i: Int): String = localizedCommon?.getOrNull(i)
        ?.takeIf { it.isNotBlank() && !it.equals(scientific[i], true) && it != common[i] }
        ?: extra[scientific[i]] ?: common[i]

    /** El nombre en el idioma actual, o null si no se conoce (especies de fotos que BirdNET no tiene). */
    fun nameOf(scientificName: String): String? = row(scientificName)?.let(::displayName) ?: extra[binomial(scientificName)]

    companion object {
        /**
         * Las clases de BirdNET que son sonidos y no especies: no tienen ficha, foto ni canto. Las de
         * BirdNET+ V3 (`taxon_group` Anthropogenic, Geophony y Music) y, por el historial, las de V2.4.
         * El ser humano también: su «canto» no está en xeno-canto.
         */
        val NON_SPECIES = setOf("Aircraft", "Boat", "Chainsaw", "Engine", "Explosion", "Fire", "Fireworks", "Gun", "Hammer",
            "Motor vehicle", "Music", "Power tool", "Sawing", "Siren", "Train", "Vehicle horn", "Homo sapiens",
            "Dog", "Environmental", "Human non-vocal", "Human vocal", "Human whistle", "Noise", "Power tools")

        fun isSpecies(scientificName: String) = scientificName !in NON_SPECIES

        /** «Anas platyrhynchos diazi» → «Anas platyrhynchos» (BirdNET no tiene subespecies). */
        fun binomial(s: String) = s.trim().split(Regex("\\s+")).take(2).joinToString(" ")
    }
}

/** Un resultado de Buscar. [alias]: el nombre por el que se ha encontrado, si no es el de la ficha. */
data class SpeciesHit(val scientific: String, val name: String, val alias: String? = null)

/**
 * Busca especies por cualquiera de sus nombres, sin distinguir mayúsculas ni acentos y aunque las
 * palabras estén a medias: en el idioma del móvil, en español, en inglés, en latín y por los
 * nombres populares. Lo exacto va primero, luego lo que vive en España.
 *
 * @param spanish científico → nombre en español
 * @param spain científico → otros nombres; las claves son las especies presentes en España, y
 * las que BirdNET no tiene (quebrantahuesos…) se llaman por el primero
 */
class SpeciesSearch(labels: Labels, spanish: Map<String, String>, spain: Map<String, List<String>>) {
    private class Name(val text: String, val normal: String) { val words = normal.split(' ') }
    private class Entry(val scientific: String, val name: String, val inSpain: Boolean, val names: List<Name>)

    private val entries = (0 until labels.size).filter { Labels.isSpecies(labels.scientific[it]) }.map { i ->
        val sci = labels.scientific[i]
        entry(sci, labels.displayName(i), sci in spain, listOfNotNull(spanish[sci], labels.common[i]) + spain[sci].orEmpty())
    } + spain.filter { (sci, names) -> labels.row(sci) == null && names.isNotEmpty() }.map { (sci, names) -> entry(sci, names[0], true, names.drop(1)) }
    private val byScientific = entries.associateBy { it.scientific.lowercase() }

    private fun entry(sci: String, name: String, inSpain: Boolean, others: List<String>) =
        Entry(sci, name, inSpain, (listOf(name, sci) + others).distinctBy(::normalize).map { Name(it, normalize(it)) })

    fun search(text: String, limit: Int = 40): List<SpeciesHit> {
        val query = normalize(text)
        if (!isEnough(text)) return emptyList()
        val tokens = query.split(' ')
        class Match(val entry: Entry, val name: Name, val quality: Int)
        return entries.mapNotNull { e ->
            // minBy se queda con el primero: a igualdad, el nombre de la ficha antes que un alias.
            e.names.filter { n -> tokens.all { t -> n.words.any { it.startsWith(t) } } }
                .minByOrNull { quality(it, query) }?.let { Match(e, it, quality(it, query)) }
        }.sortedWith(compareBy({ it.quality != EXACT }, { !it.entry.inSpain }, { it.quality }, { normalize(it.entry.name) }))
            .take(limit).map { m -> SpeciesHit(m.entry.scientific, m.entry.name, m.name.text.takeUnless { isOwnName(m.entry, m.name) }) }
    }

    /** La especie con ese nombre científico (o null si BirdNET no la tiene). */
    fun hit(scientific: String): SpeciesHit? = byScientific[scientific.lowercase()]?.let { SpeciesHit(it.scientific, it.name) }

    private fun quality(n: Name, query: String) = when { n.normal == query -> EXACT; n.normal.startsWith(query) -> 1; else -> 2 }
    private fun isOwnName(e: Entry, n: Name) = n.normal == normalize(e.name) || n.normal == normalize(e.scientific)

    companion object {
        private const val EXACT = 0
        private val MARKS = Regex("\\p{Mn}+")
        private val SEPARATORS = Regex("[\\s-]+")

        fun normalize(s: String) = MARKS.replace(java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD), "").replace(SEPARATORS, " ").trim()

        /** Con menos de dos letras saldría media lista. */
        fun isEnough(text: String) = normalize(text).length >= 2
    }
}

/**
 * El filtro por lugar y semana: el Geomodel de BirdNET+ V3 ([geo]: latitud, longitud y semana de
 * 1 a 48 → probabilidad de cada una de sus especies), llevado al orden del modelo de audio con
 * [toGeo] (fila de audio → fila del Geomodel, o −1 si no la tiene).
 *
 * El filtro suave es el de whoBIRD: la probabilidad se pasa a escalones (1 / 0,8 / 0,5 / 0) y se
 * mezcla al 50 % con el máximo del año (ayuda con las migratorias). Lo que el Geomodel no conoce
 * (unas 800 clases, casi todas de islas lejanas y del trópico) queda a medias.
 */
class LocationFilter(private val toGeo: IntArray, private val geo: (lat: Float, lon: Float, week: Int) -> FloatArray) {
    val numClasses get() = toGeo.size

    var metaScores: FloatArray = FloatArray(0); private set
    private var cacheKey: String? = null

    /** Recalcula sólo si cambian la posición redondeada o la semana. */
    @Synchronized
    fun update(lat: Double, lon: Double, date: LocalDate) {
        val week = weekOfYear(date)
        val key = "${Math.round(lat * 100)}_${Math.round(lon * 100)}_$week"
        if (key == cacheKey) return
        val current = probabilities(lat, lon, week)
        val annualMax = FloatArray(numClasses)
        for (w in 1..48) {
            val p = probabilities(lat, lon, w)
            for (i in 0 until numClasses) if (p[i] > annualMax[i]) annualMax[i] = p[i]
        }
        metaScores = FloatArray(numClasses) { if (toGeo[it] < 0) UNKNOWN else 0.5f * step(current[it]) + 0.5f * step(annualMax[it]) }
        cacheKey = key
    }

    /** Probabilidad de cada clase de audio (0 si el Geomodel no la tiene). */
    fun probabilities(lat: Double, lon: Double, week: Int): FloatArray {
        val g = geo(lat.toFloat(), lon.toFloat(), week)
        return FloatArray(numClasses) { toGeo[it].let { j -> if (j >= 0) g[j] else 0f } }
    }

    companion object {
        /** Lo que el Geomodel no tiene: ni se premia ni se descarta. */
        const val UNKNOWN = 0.5f

        /**
         * El Geomodel V3 es muy tajante (en Madrid, lo que vive allí sale por encima de 0,9 y lo de
         * otros continentes por debajo de 0,001), así que los escalones van más altos que con el de
         * V2.4 (0,01 / 0,008 / 0,001).
         */
        fun step(p: Float): Float = when { p >= 0.1f -> 1f; p >= 0.03f -> 0.8f; p >= 0.01f -> 0.5f; else -> 0f }

        /** Semana de 1 a 48, como en iOS: día del año entre días del año, por 48, hacia arriba. */
        fun weekOfYear(d: LocalDate): Int = Math.ceil(d.dayOfYear.toDouble() / d.lengthOfYear() * 48).toInt().coerceIn(1, 48)

        /** Fila de audio → fila del Geomodel, por el nombre científico (los dos usan la taxonomía de BirdNET+). */
        fun mapping(audio: List<String>, geo: List<String>): IntArray {
            val index = geo.withIndex().associate { (i, s) -> s.lowercase() to i }
            return IntArray(audio.size) { index[audio[it].lowercase()] ?: -1 }
        }
    }
}

// --- El canto: de lo que da el modelo a candidatos ---------------------------------------

data class Settings(
    val threshold: Float = 0.35f,            // confianza mínima
    val sensitivity: Float = 1.3f,           // 0,5 … 1,5: más alto = más dispuesto (ver `slope`)
    val smoothing: Boolean = false,          // consenso entre ventanas solapadas
    val locationInfluence: Float = 0.7f,     // 0 = sin filtro … 1 = filtro completo
    val highPass: Boolean = false,
    val highPassCutoff: Float = 200f,
    val signalGate: Boolean = false,
    val clipGate: Boolean = true,
    val fusion: Boolean = true,              // foto: preferir lo oído hace poco
)

/**
 * La pendiente de la sigmoide, como en BirdNET-Analyzer: `2 − sensibilidad`. Subir la sensibilidad
 * **aplana** la curva, y así los logits negativos (cantos flojos o lejanos) se acercan al 50 % y
 * pasan el umbral. Usarla tal cual como pendiente (como hacían iOS y la 0.2) hacía lo contrario de
 * lo que dice el ajuste: con el umbral por debajo del 50 %, una pendiente mayor exige más.
 */
val Settings.slope: Float get() = (2f - sensitivity).coerceIn(0.5f, 1.5f)

data class Candidate(val row: Int, val scientific: String, val name: String, val confidence: Double)

class Scorer(private val labels: Labels) {
    private val history = ArrayDeque<FloatArray>()

    /**
     * Sensibilidad (BirdNET-Analyzer), filtro suave de lugar (whoBIRD) y suavizado opcional.
     * Devuelve los candidatos por encima del umbral, o el mejor por debajo como «pensando».
     *
     * BirdNET+ V3 ya da probabilidades (V2.4 daba logits): se vuelven a logit para aplicar la
     * pendiente de la sensibilidad, así el ajuste sigue haciendo lo mismo.
     */
    fun score(probs: FloatArray, s: Settings, meta: FloatArray?): Pair<List<Candidate>, Candidate?> {
        val combined = FloatArray(probs.size) { i ->
            val q = probs[i].toDouble().coerceIn(1e-7, 1 - 1e-7)
            var p = (1 / (1 + exp(-s.slope * ln(q / (1 - q))))).toFloat()
            if (meta != null && s.locationInfluence > 0 && i < meta.size) p *= (1 - s.locationInfluence) + s.locationInfluence * meta[i]
            p
        }
        val scores = if (s.smoothing) {
            history.addLast(combined); while (history.size > 3) history.removeFirst()
            FloatArray(combined.size) { i -> history.sumOf { it[i].toDouble() }.toFloat() / history.size }
        } else { history.clear(); combined }
        val ranked = scores.indices.sortedByDescending { scores[it] }.take(5)
        fun cand(i: Int) = Candidate(i, labels.scientific[i], labels.displayName(i), scores[i].toDouble())
        val above = ranked.filter { scores[it] >= s.threshold }.map(::cand)
        val best = ranked.firstOrNull()?.takeIf { above.isEmpty() && scores[it] > 0.15f }?.let(::cand)
        return above to best
    }
}

object Signal {
    /** Lo que espera BirdNET+ V3 (V2.4 quería 48 kHz). */
    const val RATE = 32_000
    /** Lo que da el micrófono sin remuestrear (el nativo de los Pixel); se pasa a [RATE] con [Resampler]. */
    const val CAPTURE_RATE = 48_000
    const val WINDOW = 96_000       // 3 s
    const val HOP = 25_600          // 0,8 s: ~73 % de solape (como whoBIRD)

    /**
     * La ventana a un pico de 0,5, o null si es silencio digital. V2.4 normalizaba por dentro y le
     * daba igual el volumen; V3 no del todo: con el audio a 1/10.000 (lo flojo que llega UNPROCESSED,
     * sin control de ganancia) un mirlo bajaba de 0,87 a 0,31. Y con silencio puro el modelo recortado
     * se inventa algo al 0,25, así que eso ni se analiza.
     */
    fun normalized(x: FloatArray): FloatArray? {
        var peak = 0f
        for (v in x) { val a = abs(v); if (a > peak) peak = a }
        if (peak < 1e-5f) return null
        val k = 0.5f / peak
        return FloatArray(x.size) { x[it] * k }
    }

    /** Nivel en dBFS (0 = el máximo). BirdNET no depende del volumen: esto es sólo para verlo. */
    fun dbfs(rms: Float): Float = if (rms <= 1e-7f) -140f else 20 * kotlin.math.log10(rms)

    /** WAV mono de 16 bits, para compartir lo que oyó el micrófono. */
    fun wav(x: FloatArray, rate: Int = RATE): ByteArray {
        val b = ByteBuffer.allocate(44 + x.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + x.size * 2).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(x.size * 2)
        for (v in x) b.putShort((v.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        return b.array()
    }

    /** Más del 0,5 % de muestras saturadas. */
    fun isClipped(x: FloatArray): Boolean = x.count { abs(it) >= 0.99f }.toFloat() / x.size > 0.005f

    /** Hay energía en la banda de los pájaros (> 1,5 kHz), no sólo silencio o tráfico. */
    fun hasBirdSignal(x: FloatArray): Boolean {
        val rms = sqrt(x.sumOf { (it * it).toDouble() } / x.size)
        if (rms < 0.0005) return false
        val hp = highPass(x, 1500f)
        val hpRms = sqrt(hp.sumOf { (it * it).toDouble() } / hp.size)
        return hpRms / rms > 0.1
    }

    /** Paso alto de segundo orden (RBJ, Butterworth, Q = 0,707). */
    fun highPass(x: FloatArray, cutoff: Float = 200f, rate: Float = RATE.toFloat()): FloatArray {
        val w0 = 2 * PI.toFloat() * cutoff / rate
        val c = cos(w0); val alpha = sin(w0) / (2 * 0.7071f); val a0 = 1 + alpha
        val b0 = ((1 + c) / 2) / a0; val b1 = -(1 + c) / a0; val b2 = b0; val a1 = -2 * c / a0; val a2 = (1 - alpha) / a0
        val y = FloatArray(x.size); var x1 = 0f; var x2 = 0f; var y1 = 0f; var y2 = 0f
        for (n in x.indices) {
            val yn = b0 * x[n] + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            y[n] = yn; x2 = x1; x1 = x[n]; y2 = y1; y1 = yn
        }
        return y
    }
}

/**
 * De 48 a 32 kHz (2/3) por trozos, sin saltos entre uno y otro: cada muestra de salida es la
 * interpolación con un sinc enventanado (Blackman, 2×[HALF] puntos) de la entrada en t = n·1,5,
 * con un paso bajo a ~14,4 kHz (por debajo de los 16 kHz de Nyquist de la salida). Pedir 32 kHz a
 * `AudioRecord` también valdría, pero con la fuente UNPROCESSED no todos los móviles lo aceptan, y
 * entonces se caería al micrófono con supresión de ruido, que se come los cantos.
 */
class Resampler(private val inRate: Int = Signal.CAPTURE_RATE, private val outRate: Int = Signal.RATE) {
    private val ratio = inRate.toDouble() / outRate
    private val cutoff = 0.9 * outRate / (2.0 * inRate)  // 14,4 kHz en ciclos por muestra de entrada (0,3)
    private var buf = FloatArray(0)                     // entrada pendiente (con HALF de historia delante)
    private var t = HALF.toDouble()                     // posición de la siguiente salida dentro de buf
    private val kernels = HashMap<Long, DoubleArray>()

    init { buf = FloatArray(HALF) }                     // historia de ceros al empezar

    fun process(x: FloatArray): FloatArray {
        buf += x
        val out = ArrayList<Float>((x.size / ratio).toInt() + 2)
        while (t + HALF < buf.size) {
            val c = t.toInt(); val frac = t - c
            // De 48 a 32 kHz sólo hay dos desfases (0 y 0,5): los coeficientes se calculan una vez.
            val taps = kernels.getOrPut(Math.round(frac * 1e6)) { DoubleArray(2 * HALF) { kernel(it - HALF + 1 - frac) } }
            var acc = 0.0
            for (k in taps.indices) acc += buf[c - HALF + 1 + k] * taps[k]
            out += acc.toFloat()
            t += ratio
        }
        // Se queda con lo necesario para la siguiente vuelta.
        val keep = (t.toInt() - HALF).coerceAtLeast(0)
        buf = buf.copyOfRange(keep, buf.size); t -= keep
        return out.toFloatArray()
    }

    private fun kernel(d: Double): Double {
        if (abs(d) >= HALF) return 0.0
        val x = 2 * cutoff * d
        val sinc = if (abs(x) < 1e-9) 1.0 else sin(PI * x) / (PI * x)
        val w = 0.42 + 0.5 * cos(PI * d / HALF) + 0.08 * cos(2 * PI * d / HALF)
        return 2 * cutoff * sinc * w
    }

    private companion object { const val HALF = 24 }
}

// --- Desempate y fusión audio ↔ foto -------------------------------------------------------

/** Un segundo candidato a un 65 % del primero o más: se pregunta en vez de adivinar. */
const val TIEBREAK_RATIO = 0.65
/** Lo oído hace menos de 10 min respalda a esa especie en una foto (y el historial no la repite). */
const val FUSION_WINDOW_S = 600L

/**
 * Sube la nota de lo que el micrófono oyó hace poco (el doble si fue ahora mismo, nada a los
 * 10 min) y renormaliza: redistribuye la confianza entre candidatos, no la inventa.
 * Devuelve los que se subieron, para poder decirlo.
 */
fun applyAudioPrior(cands: MutableList<Candidate>, heard: Map<String, Long>, nowS: Long): Set<String> {
    if (heard.isEmpty() || cands.size < 2) return emptySet()
    val before = cands.sumOf { it.confidence }
    val boosted = mutableSetOf<String>()
    for (i in cands.indices) {
        val key = cands[i].scientific.lowercase()
        val at = heard[key] ?: continue
        val age = nowS - at
        if (age < 0 || age >= FUSION_WINDOW_S) continue
        cands[i] = cands[i].copy(confidence = cands[i].confidence * (1 + (1 - age.toDouble() / FUSION_WINDOW_S)))
        boosted += key
    }
    val after = cands.sumOf { it.confidence }
    if (boosted.isEmpty() || after <= 0) return emptySet()
    for (i in cands.indices) cands[i] = cands[i].copy(confidence = cands[i].confidence * before / after)
    return boosted
}

/**
 * El día de la foto, de `DateTimeOriginal` («2026:05:17 08:31:02», hora local de la cámara).
 * `ExifInterface.dateTimeOriginal` es de uso interno de la librería; la fecha va en el texto.
 */
internal fun exifDate(value: String?): java.time.LocalDate? {
    val day = value?.trim()?.take(10)?.split(':', '-') ?: return null
    if (day.size != 3) return null
    val (y, m, d) = day.map { it.toIntOrNull() ?: return null }
    return runCatching { java.time.LocalDate.of(y, m, d) }.getOrNull()?.takeIf { it.year > 1970 }
}

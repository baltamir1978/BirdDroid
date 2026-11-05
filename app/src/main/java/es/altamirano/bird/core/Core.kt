package es.altamirano.bird.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/** Las 6.522 especies de BirdNET: «Científico_Común» por fila; la fila es la clase del modelo. */
class Labels(lines: List<String>, localized: List<String>?) {
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
    fun displayName(i: Int): String = localizedCommon?.getOrNull(i)?.takeIf { it.isNotBlank() && !it.equals(scientific[i], true) } ?: common[i]

    companion object {
        /** «Anas platyrhynchos diazi» → «Anas platyrhynchos» (BirdNET no tiene subespecies). */
        fun binomial(s: String) = s.trim().split(Regex("\\s+")).take(2).joinToString(" ")
    }
}

/**
 * El filtro por lugar y semana de BirdNET v2.4, traducido de `LocationFilter.swift` con los mismos
 * pesos (`meta_weights.bin`): da los mismos números que la app de iOS.
 *
 *   [lat, lon, semana] → embebido de Fourier (144) → 256 → 512 → 1024 (ReLU) → 6522 → sigmoide
 *
 * El filtro suave es el de whoBIRD: la probabilidad se pasa a escalones (1 / 0,8 / 0,5 / 0) y se
 * mezcla al 50 % con el máximo del año (ayuda con las migratorias).
 */
class LocationFilter(bytes: ByteBuffer) {
    private val w1: FloatArray; private val b1: FloatArray
    private val w2: FloatArray; private val b2: FloatArray
    private val w3: FloatArray; private val b3: FloatArray
    private val w4: FloatArray; private val b4: FloatArray
    val numClasses = 6522

    var metaScores: FloatArray = FloatArray(0); private set
    private var cacheKey: String? = null

    init {
        val buf = bytes.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        require(buf.get(0) == 0x42.toByte() && buf.get(1) == 0x4D.toByte() && buf.get(2) == 0x45.toByte() && buf.get(3) == 0x54.toByte()) { "meta_weights.bin no es válido" }
        buf.position(8)
        val fb = buf.asFloatBuffer()
        fun read(n: Int) = FloatArray(n).also { fb.get(it) }
        read(48)                                    // bloque de frecuencias antiguo, sin uso
        w1 = read(256 * 144); b1 = read(256)
        w2 = read(512 * 256); b2 = read(512)
        w3 = read(1024 * 512); b3 = read(1024)
        w4 = read(6522 * 1024); b4 = read(6522)
    }

    /** Recalcula sólo si cambian la posición redondeada o la semana. */
    fun update(lat: Double, lon: Double, date: LocalDate) {
        val week = weekOfYear(date)
        val key = "${Math.round(lat * 100)}_${Math.round(lon * 100)}_$week"
        if (key == cacheKey) return
        cacheKey = key
        val current = probabilities(lat, lon, week)
        val annualMax = FloatArray(numClasses)
        for (w in 1..48) {
            val p = probabilities(lat, lon, w)
            for (i in 0 until numClasses) if (p[i] > annualMax[i]) annualMax[i] = p[i]
        }
        metaScores = FloatArray(numClasses) { 0.5f * step(current[it]) + 0.5f * step(annualMax[it]) }
    }

    fun probabilities(lat: Double, lon: Double, week: Int): FloatArray {
        val h1 = linear(w1, b1, 256, 144, embedding(lat, lon, week)).relu()
        val h2 = linear(w2, b2, 512, 256, h1).relu()
        val h3 = linear(w3, b3, 1024, 512, h2).relu()
        val logits = linear(w4, b4, numClasses, 1024, h3)
        for (i in logits.indices) logits[i] = (1 / (1 + exp(-logits[i].toDouble()))).toFloat()
        return logits
    }

    /** emb[i] = √2 · sin(2π·n + i·π/48) para latitud, longitud y semana normalizadas. */
    private fun embedding(lat: Double, lon: Double, week: Int): FloatArray {
        val latN = ((lat + 90) / 180).toFloat(); val lonN = ((lon + 180) / 360).toFloat(); val weekN = week / 48f
        val twoPi = 2 * PI.toFloat(); val step = PI.toFloat() / 48; val sqrt2 = sqrt(2f)
        val f = FloatArray(144)
        for (i in 0 until 48) {
            val phase = i * step
            f[i] = sqrt2 * sin(latN * twoPi + phase)
            f[i + 48] = sqrt2 * sin(lonN * twoPi + phase)
            f[i + 96] = sqrt2 * sin(weekN * twoPi + phase)
        }
        return f
    }

    private fun linear(w: FloatArray, b: FloatArray, rows: Int, cols: Int, x: FloatArray): FloatArray {
        val y = FloatArray(rows)
        for (r in 0 until rows) {
            var acc = 0f; val base = r * cols
            for (c in 0 until cols) acc += w[base + c] * x[c]
            y[r] = acc + b[r]
        }
        return y
    }

    private fun FloatArray.relu() = apply { for (i in indices) if (this[i] < 0) this[i] = 0f }

    companion object {
        fun step(p: Float): Float = when { p >= 0.01f -> 1f; p >= 0.008f -> 0.8f; p >= 0.001f -> 0.5f; else -> 0f }

        /** Semana de 1 a 48, como en iOS: día del año entre días del año, por 48, hacia arriba. */
        fun weekOfYear(d: LocalDate): Int = Math.ceil(d.dayOfYear.toDouble() / d.lengthOfYear() * 48).toInt().coerceIn(1, 48)
    }
}

// --- El canto: de los logits del modelo a candidatos ---------------------------------------

data class Settings(
    val threshold: Float = 0.35f,            // confianza mínima
    val sensitivity: Float = 1.3f,           // pendiente de la sigmoide (0,5 … 1,5)
    val smoothing: Boolean = false,          // consenso entre ventanas solapadas
    val locationInfluence: Float = 0.7f,     // 0 = sin filtro … 1 = filtro completo
    val highPass: Boolean = false,
    val highPassCutoff: Float = 200f,
    val signalGate: Boolean = false,
    val clipGate: Boolean = true,
    val fusion: Boolean = true,              // foto: preferir lo oído hace poco
)

data class Candidate(val row: Int, val scientific: String, val name: String, val confidence: Double)

class Scorer(private val labels: Labels) {
    private val history = ArrayDeque<FloatArray>()

    /**
     * Sensibilidad (BirdNET-Analyzer), filtro suave de lugar (whoBIRD) y suavizado opcional.
     * Devuelve los candidatos por encima del umbral, o el mejor por debajo como «pensando».
     */
    fun score(logits: FloatArray, s: Settings, meta: FloatArray?): Pair<List<Candidate>, Candidate?> {
        val combined = FloatArray(logits.size) { i ->
            var p = (1 / (1 + exp(-s.sensitivity * logits[i].toDouble()))).toFloat()
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
    const val RATE = 48_000
    const val WINDOW = 144_000      // 3 s
    const val HOP = 38_400          // 0,8 s: ~73 % de solape (como whoBIRD)

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

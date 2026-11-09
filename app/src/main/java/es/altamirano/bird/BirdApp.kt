package es.altamirano.bird

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import coil.ImageLoader
import coil.ImageLoaderFactory
import es.altamirano.bird.core.Box
import es.altamirano.bird.core.Candidate
import es.altamirano.bird.core.Component
import es.altamirano.bird.core.Framing
import es.altamirano.bird.core.Labels
import es.altamirano.bird.core.Square
import es.altamirano.bird.core.applyAudioPrior
import es.altamirano.bird.data.Detection
import es.altamirano.bird.data.History
import es.altamirano.bird.data.Models
import es.altamirano.bird.data.Prefs
import es.altamirano.bird.data.toDetection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class BirdApp : Application(), ImageLoaderFactory {
    lateinit var models: Models; private set
    lateinit var history: History; private set
    lateinit var prefs: Prefs; private set

    override fun onCreate() {
        super.onCreate()
        models = Models(this)
        history = History(this) { models.labels }
        prefs = Prefs(this)
    }

    /** Las fotos de Wikimedia, con nuestro User-Agent: al de serie de OkHttp le responden 403. */
    override fun newImageLoader() = ImageLoader.Builder(this).okHttpClient(http).crossfade(true).build()
}

val Context.app: BirdApp get() = applicationContext as BirdApp

// --- Fotos -------------------------------------------------------------------------------

data class PhotoInfo(val bitmap: Bitmap, val lat: Double?, val lon: Double?, val date: LocalDate?)

/** Lo que se encontró en la foto: el encuadre y la silueta que se pinta encima ([maskArea] es dónde va). */
class Subject(val box: Box, val mask: Bitmap? = null, val maskArea: Box = Box(0f, 0f, 1f, 1f))

sealed interface PhotoResult {
    val crop: Bitmap
    data class Found(val detection: Detection, val candidates: List<Candidate>, override val crop: Bitmap, val heardBoost: Boolean) : PhotoResult
    data class NoBird(override val crop: Bitmap) : PhotoResult
}

object Photos {
    /** Por debajo, la mejor ventana de la búsqueda no se toma por un pájaro. */
    private const val BIRD_SCORE = 0.5f
    /** Con esta nota se deja de bajar a ventanas más pequeñas: el pájaro ya está bien encuadrado. */
    private const val SURE_SCORE = 0.9f
    /** Si al ceñirse a la silueta la nota cae por debajo de esta parte, la silueta iba mal. */
    private const val KEEP_REFINED = 0.7f
    /** La silueta (0xAARRGGBB): el verde de la app, translúcido. */
    private const val MASK_COLOR = 0x59239E7B
    /** Por debajo no se dice nada, igual que en iOS. */
    private const val MINIMUM = 0.05

    /**
     * La foto con su orientación y, del EXIF, el sitio y la fecha: el filtro por lugar y
     * temporada usa los de la foto, no los de ahora. La ubicación del EXIF viene borrada salvo
     * con `ACCESS_MEDIA_LOCATION` y pidiendo el original.
     */
    suspend fun load(context: Context, uri: Uri): PhotoInfo? = withContext(Dispatchers.IO) {
        val original = runCatching { MediaStore.setRequireOriginal(uri) }.getOrDefault(uri)
        val exif = runCatching { context.contentResolver.openInputStream(original)?.use { ExifInterface(it) } }.getOrNull()
            ?: runCatching { context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) } }.getOrNull()
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        var sample = 1
        while (opts.outWidth / (sample * 2) >= 1600 && opts.outHeight / (sample * 2) >= 1600) sample *= 2
        val bmp = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return@withContext null
        val rotation = exif?.rotationDegrees ?: 0
        val rotated = if (rotation == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
        val latLon = exif?.latLong?.takeUnless { it[0] == 0.0 && it[1] == 0.0 }
        val date = exif?.dateTimeOriginal?.let { java.time.Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }
        PhotoInfo(rotated, latLon?.get(0), latLon?.get(1), date)
    }

    /** Reduce a la mitad mientras se pueda (así no hay aliasing) y ajusta el lado corto a [side]. */
    private fun shrink(bmp: Bitmap, side: Int): Bitmap {
        var b = bmp
        while (minOf(b.width, b.height) >= side * 2) b = Bitmap.createScaledBitmap(b, b.width / 2, b.height / 2, true)
        val k = side.toFloat() / minOf(b.width, b.height)
        return if (k >= 1f) b else Bitmap.createScaledBitmap(b, (b.width * k).roundToInt(), (b.height * k).roundToInt(), true)
    }

    private fun pixels(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    /** Lo que el modelo ve en ese cuadrado: la mejor especie, sin contar la clase de fondo. */
    private fun birdScore(models: Models, px: IntArray, w: Int, s: Square): Float {
        val n = Framing.MODEL_SIDE
        val p = models.classifyPhoto(Framing.resample(px, w, s.x, s.y, s.side, s.side, n, n))
        return p.indices.filter { it != models.aiyBackground }.maxOf { p[it] }
    }

    private fun maskBitmap(c: Component): Bitmap {
        val n = Framing.MASK_SIDE
        return Bitmap.createBitmap(IntArray(n * n) { if (c.pixels[it]) MASK_COLOR else 0 }, n, n, Bitmap.Config.ARGB_8888)
    }

    /**
     * Busca el pájaro solo, como hace Vision en iOS: AIY Birds pasa por ventanas cada vez más
     * pequeñas, y Magic Touch ciñe el recuadro a la silueta en la mejor.
     *
     * La nota sirve para localizar, no para saber si hay pájaro: AIY también ve patos en un coche.
     */
    suspend fun findBird(context: Context, info: PhotoInfo): Subject? = withContext(Dispatchers.Default) {
        val models = context.app.models
        val small = shrink(info.bitmap, Framing.SCAN_SIDE)
        val w = small.width; val h = small.height
        val px = pixels(small)
        var best: Square? = null
        var score = 0f
        for (scale in Framing.scales(w, h)) {
            for (s in scale) {
                ensureActive()
                val v = birdScore(models, px, w, s)
                if (v > score) { score = v; best = s }
            }
            if (score >= SURE_SCORE) break
        }
        val found = best?.takeIf { score >= BIRD_SCORE } ?: return@withContext null
        val window = Box(found.x / w.toFloat(), found.y / h.toFloat(), (found.x + found.side) / w.toFloat(), (found.y + found.side) / h.toFloat())

        // Se mira un poco más allá de la ventana, por si el pájaro no cabía entero.
        val side = minOf(found.side * 2, w, h)
        val x = (found.x + found.side / 2 - side / 2).coerceIn(0, w - side); val y = (found.y + found.side / 2 - side / 2).coerceIn(0, h - side)
        val n = Framing.MASK_SIDE
        val tapX = (found.x + found.side / 2f - x) / side; val tapY = (found.y + found.side / 2f - y) / side
        // Si la segmentación fallara, la ventana sigue siendo un buen encuadre.
        val mask = runCatching { models.segment(Framing.resample(px, w, x, y, side, side, n, n), tapX, tapY) }
            .onFailure { Log.w("BirdDroid", "Segmentación fallida", it) }.getOrNull() ?: return@withContext Subject(window)
        val component = Framing.component(mask, n, tapX, tapY) ?: return@withContext Subject(window)
        val area = Box(x / w.toFloat(), y / h.toFloat(), (x + side) / w.toFloat(), (y + side) / h.toFloat())
        val box = Framing.padded(Framing.unproject(component.box, area))
        if (birdScore(models, px, w, Framing.square(box, w, h)) < score * KEEP_REFINED) return@withContext Subject(window)
        Subject(box, maskBitmap(component), area)
    }

    /** Un toque sobre el pájaro (0–1): lo recorta por su silueta, o null si no hay nada aprovechable. */
    suspend fun subjectAt(context: Context, info: PhotoInfo, tapX: Float, tapY: Float): Subject? = withContext(Dispatchers.Default) {
        val small = shrink(info.bitmap, Framing.SCAN_SIDE)
        val n = Framing.MASK_SIDE
        val mask = context.app.models.segment(Framing.resample(pixels(small), small.width, 0, 0, small.width, small.height, n, n), tapX, tapY)
        val component = Framing.component(mask, n, tapX, tapY)?.takeIf { Framing.isUseful(it.box) } ?: return@withContext null
        Subject(Framing.padded(component.box), maskBitmap(component))
    }

    /**
     * Como `PhotoIdentifier.swift`: AIY Birds → taxonomía de BirdNET → filtro suave de lugar y
     * temporada (el de la foto o, si no lo trae, donde estamos) → lo oído hace poco → hasta 3
     * candidatos. El recorte es la zona elegida, lo que se encontró solo o, si no, el cuadrado central.
     */
    suspend fun identify(context: Context, info: PhotoInfo, region: Box?, found: Subject?): PhotoResult = withContext(Dispatchers.Default) {
        val app = context.app
        val s = Framing.square(region ?: found?.box ?: Box(0f, 0f, 1f, 1f), info.bitmap.width, info.bitmap.height)
        val crop = Bitmap.createBitmap(info.bitmap, s.x, s.y, s.side, s.side)
        val n = Framing.MODEL_SIDE
        val probs = app.models.classifyPhoto(Framing.resample(pixels(crop), crop.width, 0, 0, crop.width, crop.height, n, n))
        val labels = app.models.aiyLabels
        val ranked = probs.indices.sortedByDescending { probs[it] }.take(10)
        if (ranked.first() == app.models.aiyBackground) return@withContext PhotoResult.NoBird(crop)
        val birdnet: Labels = app.models.labels
        val settings = app.prefs.settings.value
        val here = if (info.lat != null && info.lon != null) info.lat to info.lon else context.lastLocation()
        val lat = here?.first; val lon = here?.second
        val meta = if (lat != null && lon != null && settings.locationInfluence > 0) {
            app.models.filter.update(lat, lon, info.date ?: LocalDate.now()); app.models.filter.metaScores
        } else null
        val cands = ranked.filter { it != app.models.aiyBackground }.mapNotNull { i ->
            val sci = labels.getOrNull(i)?.replace('_', ' ') ?: return@mapNotNull null
            val row = birdnet.row(sci)
            var conf = probs[i].toDouble()
            if (meta != null && row != null) conf *= (1 - settings.locationInfluence) + settings.locationInfluence * meta[row]
            Candidate(row ?: -1, if (row != null) Labels.binomial(sci) else sci, birdnet.nameOf(sci) ?: sci, conf)
        }.toMutableList()
        val boosted = if (settings.fusion) applyAudioPrior(cands, app.history.recentlyHeard(), System.currentTimeMillis() / 1000) else emptySet()
        cands.sortByDescending { it.confidence }
        val top = cands.firstOrNull()?.takeIf { it.confidence >= MINIMUM } ?: return@withContext PhotoResult.NoBird(crop)
        val shown = (listOf(top) + cands.drop(1).filter { it.confidence >= MINIMUM }).take(3)
        val d = shown.toDetection(lat, lon, photo = true)!!
        PhotoResult.Found(app.history.record(d), shown, crop, top.scientific.lowercase() in boosted)
    }
}

/** La última posición conocida, con `LocationManager` y no el de Google Play (GrapheneOS). */
@SuppressLint("MissingPermission")
fun Context.lastLocation(): Pair<Double, Double>? {
    if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
    val lm = getSystemService(LocationManager::class.java)
    return listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }?.let { it.latitude to it.longitude }
}

// --- Wikipedia y xeno-canto --------------------------------------------------------------

private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
    .addInterceptor { chain ->
        chain.proceed(chain.request().newBuilder().header("User-Agent", "BirdDroid/${BuildConfig.VERSION_NAME} (+https://github.com/baltamir1978/BirdDroid)").build())
    }.build()
private val json = Json { ignoreUnknownKeys = true }

private suspend fun getJson(url: String): JsonObject? = withContext(Dispatchers.IO) {
    runCatching {
        http.newCall(Request.Builder().url(url).build()).execute()
            .use { r -> if (r.isSuccessful) json.parseToJsonElement(r.body!!.string()) as? JsonObject else null }
    }.getOrNull()
}

data class WikiInfo(val title: String, val extract: String?, val image: String?, val url: String?)

object Wikipedia {
    private val cache = mutableMapOf<String, WikiInfo?>()

    /**
     * La ficha por el nombre científico, en el idioma del móvil. Lo que falte (sin artículo o sin
     * foto, frecuente fuera del inglés) se completa con la Wikipedia en inglés.
     */
    suspend fun summary(scientific: String): WikiInfo? {
        if (!Labels.isSpecies(scientific)) return null
        return cache.getOrPut(scientific) {
            val lang = Locale.getDefault().language.takeIf { it in setOf("es", "en", "de", "fr", "it", "pt") } ?: "en"
            val first = fetch(lang, scientific)
            if (lang == "en" || (first?.image != null && first.extract != null)) return@getOrPut first
            val english = fetch("en", scientific) ?: return@getOrPut first
            first?.copy(image = first.image ?: english.image, extract = first.extract ?: english.extract) ?: english
        }
    }

    /**
     * Los artículos de la Wikipedia en español que citan ese nombre tal cual: muchos nombres
     * populares («pitita», «pezpita») sólo están en el texto. Las aves se titulan por su nombre científico.
     */
    suspend fun mentioning(name: String): List<String> {
        val q = URLEncoder.encode("\"" + name.replace("\"", "").trim() + "\"", "UTF-8")
        val o = getJson("https://es.wikipedia.org/w/api.php?action=query&list=search&srnamespace=0&srlimit=20&format=json&srsearch=$q") ?: return emptyList()
        return ((o["query"] as? JsonObject)?.get("search") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.get("title")?.jsonPrimitive?.content }
    }

    private suspend fun fetch(lang: String, scientific: String): WikiInfo? {
        val o = getJson("https://$lang.wikipedia.org/api/rest_v1/page/summary/" + URLEncoder.encode(scientific.replace(' ', '_'), "UTF-8")) ?: return null
        val thumb = (o["thumbnail"] as? JsonObject)?.get("source")?.jsonPrimitive?.content
        val page = ((o["content_urls"] as? JsonObject)?.get("mobile") as? JsonObject)?.get("page")?.jsonPrimitive?.content
        return WikiInfo(o["title"]?.jsonPrimitive?.content ?: scientific, o["extract"]?.jsonPrimitive?.content, thumb, page)
    }
}

object XenoCanto {
    /** Una grabación de calidad A de la especie, de canto si la hay (la API v3 pide clave: en Ajustes). */
    suspend fun recording(scientific: String, key: String): String? {
        if (key.isBlank()) return null
        val q = URLEncoder.encode("sp:\"$scientific\" q:A", "UTF-8")
        val o = getJson("https://xeno-canto.org/api/3/recordings?query=$q&key=$key") ?: return null
        val all = (o["recordings"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        val best = all.firstOrNull { "song" in it["type"]?.jsonPrimitive?.content.orEmpty().lowercase() } ?: all.firstOrNull()
        return best?.get("file")?.jsonPrimitive?.content
    }
}


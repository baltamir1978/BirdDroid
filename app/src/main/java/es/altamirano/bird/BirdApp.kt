package es.altamirano.bird

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import es.altamirano.bird.core.Candidate
import es.altamirano.bird.core.Labels
import es.altamirano.bird.core.applyAudioPrior
import es.altamirano.bird.data.Detection
import es.altamirano.bird.data.History
import es.altamirano.bird.data.Models
import es.altamirano.bird.data.Prefs
import es.altamirano.bird.data.toDetection
import kotlinx.coroutines.Dispatchers
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

class BirdApp : Application() {
    lateinit var models: Models; private set
    lateinit var history: History; private set
    lateinit var prefs: Prefs; private set

    override fun onCreate() {
        super.onCreate()
        models = Models(this)
        history = History(this)
        prefs = Prefs(this)
    }
}

val Context.app: BirdApp get() = applicationContext as BirdApp

// --- Fotos -------------------------------------------------------------------------------

data class PhotoInfo(val bitmap: Bitmap, val lat: Double?, val lon: Double?, val date: LocalDate?)

sealed interface PhotoResult {
    data class Found(val detection: Detection, val candidates: List<Candidate>, val crop: Bitmap, val heardBoost: Boolean) : PhotoResult
    data class NoBird(val crop: Bitmap) : PhotoResult
}

object Photos {
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
        val latLon = exif?.latLong
        val date = exif?.dateTimeOriginal?.let { java.time.Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }
        PhotoInfo(rotated, latLon?.get(0), latLon?.get(1), date)
    }

    /** El recorte: la zona elegida (0–1) o, si no, el cuadrado central. */
    fun crop(bmp: Bitmap, region: RectF?): Bitmap {
        val r = region ?: run {
            val s = minOf(bmp.width, bmp.height).toFloat()
            RectF((bmp.width - s) / 2 / bmp.width, (bmp.height - s) / 2 / bmp.height, (bmp.width + s) / 2 / bmp.width, (bmp.height + s) / 2 / bmp.height)
        }
        val x = (r.left * bmp.width).toInt().coerceIn(0, bmp.width - 1); val y = (r.top * bmp.height).toInt().coerceIn(0, bmp.height - 1)
        val w = (r.width() * bmp.width).toInt().coerceIn(1, bmp.width - x); val h = (r.height() * bmp.height).toInt().coerceIn(1, bmp.height - y)
        return Bitmap.createBitmap(bmp, x, y, w, h)
    }

    /**
     * Como `PhotoIdentifier.swift`: AIY Birds → taxonomía de BirdNET → filtro suave de lugar y
     * temporada → lo oído hace poco → hasta 3 candidatos (por debajo del 5 % no se dice nada).
     */
    suspend fun identify(context: Context, info: PhotoInfo, region: RectF?, here: Pair<Double, Double>?): PhotoResult = withContext(Dispatchers.Default) {
        val app = context.app
        val crop = crop(info.bitmap, region)
        val probs = app.models.classifyPhoto(crop)
        val labels = app.models.aiyLabels
        val ranked = probs.indices.sortedByDescending { probs[it] }.take(10)
        // La clase «no hay pájaro»: «None» en el .tflite de Kaggle, «__background__» en la conversión de iOS.
        fun isBackground(l: String?) = l == "None" || l == "__background__"
        if (isBackground(labels.getOrNull(ranked.first())) || probs[ranked.first()] < 0.05f) return@withContext PhotoResult.NoBird(crop)
        val birdnet: Labels = app.models.labels
        val s = app.prefs.settings.value
        val lat = info.lat ?: here?.first; val lon = info.lon ?: here?.second
        val meta = if (lat != null && lon != null && s.locationInfluence > 0) {
            app.models.filter.update(lat, lon, info.date ?: LocalDate.now()); app.models.filter.metaScores
        } else null
        val cands = ranked.mapNotNull { i ->
            val sci = labels.getOrNull(i)?.takeIf { !isBackground(it) }?.replace('_', ' ') ?: return@mapNotNull null
            val row = birdnet.row(sci)
            var conf = probs[i].toDouble()
            if (meta != null && row != null) conf *= (1 - s.locationInfluence) + s.locationInfluence * meta[row]
            Candidate(row ?: -1, if (row != null) Labels.binomial(sci) else sci, row?.let { birdnet.displayName(it) } ?: sci, conf)
        }.toMutableList()
        val boosted = if (s.fusion) applyAudioPrior(cands, app.history.recentlyHeard(), System.currentTimeMillis() / 1000) else emptySet()
        cands.sortByDescending { it.confidence }
        val shown = (listOf(cands.first()) + cands.drop(1).filter { it.confidence >= 0.05 }).take(3)
        val d = shown.toDetection(lat, lon, photo = true)!!
        PhotoResult.Found(app.history.record(d), shown, crop, cands.first().scientific.lowercase() in boosted)
    }
}

// --- Wikipedia y xeno-canto --------------------------------------------------------------

private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
private val json = Json { ignoreUnknownKeys = true }

private suspend fun getJson(url: String): JsonObject? = withContext(Dispatchers.IO) {
    runCatching {
        http.newCall(Request.Builder().url(url).header("User-Agent", "BirdDroid/0.1 (+https://github.com/baltamir1978/BirdDroid)").build()).execute()
            .use { r -> if (r.isSuccessful) json.parseToJsonElement(r.body!!.string()) as? JsonObject else null }
    }.getOrNull()
}

data class WikiInfo(val title: String, val extract: String?, val image: String?, val url: String?)

object Wikipedia {
    private val cache = mutableMapOf<String, WikiInfo?>()

    /** La ficha por el nombre científico, en el idioma del móvil (y si no, en inglés). */
    suspend fun summary(scientific: String): WikiInfo? = cache.getOrPut(scientific) {
        val lang = Locale.getDefault().language.takeIf { it in setOf("es", "en", "de", "fr", "it", "pt") } ?: "en"
        for (l in listOf(lang, "en").distinct()) {
            val o = getJson("https://$l.wikipedia.org/api/rest_v1/page/summary/" + URLEncoder.encode(scientific.replace(' ', '_'), "UTF-8")) ?: continue
            val thumb = (o["thumbnail"] as? JsonObject)?.get("source")?.jsonPrimitive?.content
            val page = ((o["content_urls"] as? JsonObject)?.get("mobile") as? JsonObject)?.get("page")?.jsonPrimitive?.content
            return@getOrPut WikiInfo(o["title"]?.jsonPrimitive?.content ?: scientific, o["extract"]?.jsonPrimitive?.content, thumb, page)
        }
        null
    }
}

object XenoCanto {
    /** Una grabación de calidad A de la especie (la API v3 pide clave: en Ajustes). */
    suspend fun recording(scientific: String, key: String): String? {
        if (key.isBlank()) return null
        val q = URLEncoder.encode("sp:\"$scientific\" q:A", "UTF-8")
        val o = getJson("https://xeno-canto.org/api/3/recordings?query=$q&key=$key") ?: return null
        return ((o["recordings"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("file")?.jsonPrimitive?.content
    }
}


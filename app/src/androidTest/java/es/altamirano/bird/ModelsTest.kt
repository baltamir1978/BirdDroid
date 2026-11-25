package es.altamirano.bird

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import es.altamirano.bird.core.Resampler
import es.altamirano.bird.core.Scorer
import es.altamirano.bird.core.Settings
import es.altamirano.bird.core.Signal
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import es.altamirano.bird.data.Models
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.sin

/**
 * Los modelos con el LiteRT de la app (no el de Python, que es otra versión): que carguen y den
 * algo con sentido. Así se cazó que BirdNET+ V3 fallaba en el móvil y no en las pruebas de Python.
 */
@RunWith(AndroidJUnit4::class)
class ModelsTest {
    private val models = Models(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test fun birdnetDaUnaProbabilidadPorClase() {
        val tone = FloatArray(Signal.WINDOW) { (0.3 * sin(2 * PI * 3000 * it / Signal.RATE)).toFloat() }
        val p = models.birdnet(tone)
        assertEquals(models.labels.size, p.size)
        assertTrue(p.all { it.isFinite() && it in 0f..1f })
        android.util.Log.i("ModelsTest", "BirdNET: ${models.lastInferenceMs} ms por ventana")
    }

    @Test fun elGeomodelConoceElMirloEnMadrid() {
        models.filter.update(40.4, -3.7, LocalDate.of(2026, 5, 15))
        assertEquals(1f, models.filter.metaScores[models.labels.row("Turdus merula")!!])
        assertEquals(0f, models.filter.metaScores[models.labels.row("Cardinalis cardinalis")!!])
    }

    /**
     * Un petirrojo de verdad por el mismo camino que el micrófono: 48 kHz → [Resampler] → ventanas
     * → modelo → [Scorer]. El audio no va en el repositorio: se deja con
     * `adb push petirrojo.f32 /sdcard/Android/data/es.altamirano.bird/files/robin48.f32`
     * (mono, float32, 48 kHz; ver CLAUDE.md).
     */
    @Test fun unPetirrojoDeVerdad() {
        val f = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "robin48.f32")
        assumeTrue(f.exists())
        val raw = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().let { b -> FloatArray(b.remaining()).also { b.get(it) } }
        val x = Resampler().process(raw)
        val scorer = Scorer(models.labels)
        val tops = mutableListOf<String>(); val ms = mutableListOf<Long>()
        var start = 0
        while (start + Signal.WINDOW <= x.size) {
            val (above, thinking) = scorer.score(models.birdnet(x.copyOfRange(start, start + Signal.WINDOW)), Settings(locationInfluence = 0f), null)
            tops += (above.firstOrNull() ?: thinking)?.scientific ?: "-"; ms += models.lastInferenceMs
            start += Signal.HOP
        }
        android.util.Log.i("ModelsTest", "petirrojo: $tops; ms $ms")
        assertTrue(tops.count { it == "Erithacus rubecula" } > tops.size / 2)
    }
}

package es.altamirano.bird.core

import es.altamirano.bird.data.toDetection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.sin

private val models = File("src/main/assets/models")

class LocationFilterTest {
    /**
     * Mismos números que la referencia en Python (`Tools/`… ver CLAUDE.md), que a su vez casa al
     * 0,9997 con el modelo oficial de metadatos de BirdNET. Necesita los modelos bajados.
     */
    @Test fun matchesReference() {
        val f = File(models, "meta_weights.bin")
        assumeTrue(f.exists())
        val filter = LocationFilter(ByteBuffer.wrap(f.readBytes()))
        val p = filter.probabilities(40.4168, -3.7038, 20)
        assertEquals(0.99609, p[6253].toDouble(), 2e-4)   // Turdus merula
        assertEquals(0.94683, p[423].toDouble(), 2e-4)    // Apus apus: los vencejos llegan en mayo
        assertEquals(0.44519, p[1315].toDouble(), 2e-4)   // Ciconia ciconia
        // En Llanes, en enero, los vencejos están en África.
        assertTrue(filter.probabilities(43.42, -4.75, 4)[423] < 0.01f)
    }

    @Test fun weeksAndSteps() {
        assertEquals(1, LocationFilter.weekOfYear(LocalDate.of(2026, 1, 1)))
        assertEquals(48, LocationFilter.weekOfYear(LocalDate.of(2026, 12, 31)))
        assertEquals(18, LocationFilter.weekOfYear(LocalDate.of(2026, 5, 15)))   // 135/365 × 48 = 17,75 → 18
        assertEquals(1f, LocationFilter.step(0.02f)); assertEquals(0.8f, LocationFilter.step(0.009f))
        assertEquals(0.5f, LocationFilter.step(0.002f)); assertEquals(0f, LocationFilter.step(0.0001f))
    }
}

class ScoringTest {
    private val labels = Labels(listOf("Turdus merula_Eurasian Blackbird", "Turdus philomelos_Song Thrush", "Erithacus rubecula_European Robin"),
        listOf("Turdus merula_Mirlo común", "Turdus philomelos_Zorzal común", "Erithacus rubecula_Petirrojo europeo"))

    @Test fun labelsAndNames() {
        assertEquals("Mirlo común", labels.displayName(0))
        assertEquals(0, labels.row("Turdus merula"))
        assertEquals(1, labels.row("turdus philomelos philomelos"))   // subespecie → especie
        assertNull(labels.row("Passer domesticus"))
    }

    @Test fun sigmoidWithSensitivityThresholdAndLocation() {
        val scorer = Scorer(labels)
        val logits = floatArrayOf(4f, 0f, -4f)
        val (above, thinking) = scorer.score(logits, Settings(threshold = 0.35f, sensitivity = 1.3f, locationInfluence = 0f), null)
        assertEquals(listOf("Turdus merula", "Turdus philomelos"), above.map { it.scientific })   // 0,94 y 0,5
        assertNull(thinking)
        // Con el filtro de lugar al 70 %: una especie que no está aquí (meta 0) baja al 30 %.
        val (filtered, _) = scorer.score(logits, Settings(threshold = 0.35f, locationInfluence = 0.7f), floatArrayOf(0f, 1f, 1f))
        assertEquals(listOf("Turdus philomelos"), filtered.map { it.scientific })
    }

    @Test fun moreSensitivityLetsFaintSongThrough() {
        // Un canto flojo (logit −0,8): con sensibilidad 1,4 pasa del 35 %; con 0,6, no.
        val faint = floatArrayOf(-0.8f, -5f, -5f)
        val (eager, _) = Scorer(labels).score(faint, Settings(threshold = 0.35f, sensitivity = 1.4f, locationInfluence = 0f), null)
        val (strict, _) = Scorer(labels).score(faint, Settings(threshold = 0.35f, sensitivity = 0.6f, locationInfluence = 0f), null)
        assertEquals(listOf("Turdus merula"), eager.map { it.scientific })
        assertTrue(strict.isEmpty())
    }

    @Test fun thinkingWhenNothingCrossesTheThreshold() {
        val (above, thinking) = Scorer(labels).score(floatArrayOf(-1f, -3f, -3f), Settings(threshold = 0.5f), null)
        assertTrue(above.isEmpty())
        assertEquals("Turdus merula", thinking!!.scientific)
    }

    @Test fun closeCallsAskTheUser() {
        val d = listOf(Candidate(0, "Sitta europaea", "Trepador azul", 0.6), Candidate(1, "Sitta neumayer", "Trepador rupestre", 0.45),
            Candidate(2, "Certhia brachydactyla", "Agateador", 0.1)).toDetection(null, null, photo = true)!!
        assertEquals(listOf("Sitta neumayer"), d.alternatives.map { it.scientific })   // 0,45 ≥ 0,6 × 0,65
        val chosen = d.choose(d.alternatives[0])
        assertEquals("Sitta neumayer", chosen.scientific)
        assertEquals(listOf("Sitta europaea"), chosen.alternatives.map { it.scientific })   // se puede deshacer
    }

    @Test fun recentSongBoostsAPhotoWithoutInventingConfidence() {
        val c = mutableListOf(Candidate(0, "Sitta europaea", "a", 0.5), Candidate(1, "Sitta neumayer", "b", 0.4))
        val boosted = applyAudioPrior(c, mapOf("sitta neumayer" to 1000L), nowS = 1000L)
        assertEquals(setOf("sitta neumayer"), boosted)
        assertEquals(0.9, c.sumOf { it.confidence }, 1e-9)              // se reparte, no se crea
        assertTrue(c[1].confidence > c[0].confidence)                    // oído ahora mismo: el doble
        val stale = mutableListOf(Candidate(0, "A b", "a", 0.5), Candidate(1, "C d", "b", 0.4))
        assertTrue(applyAudioPrior(stale, mapOf("c d" to 0L), nowS = 601L).isEmpty())   // hace más de 10 min
    }
}

class SignalTest {
    private fun tone(hz: Double, amp: Float) = FloatArray(Signal.WINDOW) { (amp * sin(2 * PI * hz * it / Signal.RATE)).toFloat() }

    @Test fun gates() {
        assertTrue(Signal.hasBirdSignal(tone(3000.0, 0.1f)))      // canto agudo
        assertFalse(Signal.hasBirdSignal(tone(80.0, 0.1f)))       // zumbido de tráfico
        assertFalse(Signal.hasBirdSignal(FloatArray(Signal.WINDOW)))
        assertTrue(Signal.isClipped(FloatArray(Signal.WINDOW) { if (it % 100 == 0) 1f else 0f }))
        assertFalse(Signal.isClipped(tone(3000.0, 0.5f)))
    }
}


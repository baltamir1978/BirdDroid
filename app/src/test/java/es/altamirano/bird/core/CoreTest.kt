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

    @Test fun `lo completado y los sonidos mandan sobre la etiqueta en ingles`() {
        val en = listOf("Turdus merula_Common Blackbird", "Bubo lacteus_Verreaux's Eagle-Owl", "Dog_Dog")
        val es = listOf("Turdus merula_Mirlo común", "Bubo lacteus_Verreaux's Eagle-Owl", "Dog_Dog")
        val l = Labels(en, es, mapOf("Bubo lacteus" to "Búho lechoso", "Dog" to "Perro"))
        assertEquals("Mirlo común", l.nameOf("Turdus merula"))
        assertEquals("Búho lechoso", l.nameOf("Bubo lacteus"))
        assertEquals("Perro", l.nameOf("Dog"))
        assertEquals(null, l.nameOf("Pica pica"))
        // Especies de las fotos que BirdNET no tiene: sólo por lo completado.
        assertEquals("Cuchara común", Labels(en, es, mapOf("Anas clypeata" to "Cuchara común")).nameOf("Anas clypeata"))
    }

    @Test fun `los sonidos no son especies`() {
        assertTrue(Labels.isSpecies("Turdus merula"))
        assertFalse(Labels.isSpecies("Human vocal"))
        assertFalse(Labels.isSpecies("Fireworks"))
    }
}

class FramingTest {
    @Test fun aRectangleGrowsIntoASquareAroundItsCentre() {
        assertEquals(Square(300, 200, 400), Framing.square(Box(0.4f, 0.2f, 0.6f, 0.6f), 1000, 1000))
    }

    @Test fun theSquareStaysInsideThePhoto() {
        assertEquals(Square(0, 0, 200), Framing.square(Box(0f, 0f, 0.2f, 0.1f), 1000, 1000))
        assertEquals(Square(800, 800, 200), Framing.square(Box(0.9f, 0.8f, 1f, 1f), 1000, 1000))
    }

    @Test fun theWholePhotoBecomesTheCentralSquare() {
        assertEquals(Square(0, 500, 3000), Framing.square(Box(0f, 0f, 1f, 1f), 3000, 4000))
    }

    @Test fun windowsReachTheEdgesAndGoFromLargeToSmall() {
        val scales = Framing.scales(900, 1200)
        assertEquals(listOf(900, 594, 396, 270, 180), scales.map { it.first().side })
        for (scale in scales) {
            val side = scale.first().side
            assertTrue(scale.any { it.x == 900 - side && it.y == 1200 - side })
            assertTrue(scale.all { it.x >= 0 && it.y >= 0 && it.x + side <= 900 && it.y + side <= 1200 })
        }
    }

    @Test fun reducingAveragesInsteadOfSkippingPixels() {
        // Tablero blanco y negro de 1 px: el aliasing lo dejaría blanco o negro; bien reducido es gris.
        val w = 448
        val board = IntArray(w * w) { i -> if ((i % w + i / w) % 2 == 0) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }
        val out = Framing.resample(board, w, 0, 0, w, w, 224, 224)
        assertTrue(out.all { (it and 0xFF) in 126..128 })
    }

    @Test fun enlargingKeepsAFlatColour() {
        val c = 0xFF336699.toInt()
        assertTrue(Framing.resample(IntArray(100) { c }, 10, 2, 3, 5, 5, 224, 224).all { it == c })
    }

    @Test fun resampleReadsTheRequestedRegion() {
        val w = 4
        val red = 0xFFFF0000.toInt(); val blue = 0xFF0000FF.toInt()
        val px = IntArray(16) { if (it % w >= 2) red else blue }
        assertTrue(Framing.resample(px, w, 2, 0, 2, 4, 1, 1).all { it == red })
    }

    private fun maskOf(side: Int, on: (Int, Int) -> Boolean) = FloatArray(side * side) { if (on(it % side, it / side)) 1f else 0f }

    @Test fun theBoxFollowsTheTouchedBlobAndIgnoresStraySpots() {
        val mask = maskOf(100) { x, y -> x in 10..29 && y in 40..59 || x in 80..84 && y in 5..9 }
        val c = Framing.component(mask, 100, 0.2f, 0.5f)!!
        assertEquals(Box(0.1f, 0.4f, 0.3f, 0.6f), c.box)
        assertFalse(c.pixels[5 * 100 + 80])
    }

    @Test fun aTouchOutsideTakesTheLargestBlob() {
        val mask = maskOf(100) { x, y -> x in 10..29 && y in 40..59 || x in 80..84 && y in 5..9 }
        assertEquals(Box(0.1f, 0.4f, 0.3f, 0.6f), Framing.component(mask, 100, 0.9f, 0.9f)!!.box)
    }

    @Test fun anEmptyMaskFindsNothing() {
        assertNull(Framing.component(FloatArray(100), 10, 0.5f, 0.5f))
    }

    @Test fun paddingStopsAtTheEdges() {
        assertEquals(Box(0f, 0.38f, 0.56f, 0.62f), Framing.padded(Box(0f, 0.4f, 0.5f, 0.6f)).let { b ->
            Box(b.left, Math.round(b.top * 100) / 100f, Math.round(b.right * 100) / 100f, Math.round(b.bottom * 100) / 100f) })
    }
}

class SpeciesSearchTest {
    private val labels = Labels(
        listOf("Motacilla alba_White Wagtail", "Motacilla capensis_Cape Wagtail", "Motacilla flava_Western Yellow Wagtail",
            "Passer domesticus_House Sparrow", "Passer griseus_Northern Gray-headed Sparrow", "Dog_Dog"),
        listOf("Motacilla alba_Lavandera Blanca", "Motacilla capensis_Lavandera de El Cabo", "Motacilla flava_Lavandera Boyera",
            "Passer domesticus_Gorrión Común", "Passer griseus_Gorrión Gris", "Dog_Perro"))
    private val spanish = mapOf("Motacilla alba" to "Lavandera Blanca", "Passer domesticus" to "Gorrión Común")
    private val search = SpeciesSearch(labels, spanish, mapOf(
        "Motacilla alba" to listOf("Aguzanieves", "Pajarita de las nieves"), "Motacilla flava" to emptyList(), "Passer domesticus" to listOf("Gorrión"),
        "Gypaetus barbatus" to listOf("Quebrantahuesos", "Quebranta")))

    private fun names(q: String) = search.search(q).map { it.scientific }

    @Test fun `un nombre popular lleva a su especie y dice por cual se encontro`() {
        val hit = search.search("pajarita de las nieves").single()
        assertEquals("Motacilla alba", hit.scientific)
        assertEquals("Lavandera Blanca", hit.name)
        assertEquals("Pajarita de las nieves", hit.alias)
    }

    @Test fun `el nombre de la ficha no se repite como alias`() = assertEquals(null, search.search("lavandera blanca").single().alias)

    @Test fun `las de España van antes`() =
        assertEquals(listOf("Motacilla alba", "Motacilla flava", "Motacilla capensis"), names("lavandera"))

    @Test fun `lo exacto va antes que lo demas`() = assertEquals("Passer domesticus", names("gorrion").first())

    @Test fun `da igual acentos, mayusculas, orden y palabras a medias`() {
        assertEquals(listOf("Passer domesticus"), names("GORRIÓN com"))
        assertEquals(listOf("Motacilla alba"), names("nieves paj"))
        assertEquals(listOf("Motacilla alba"), names("motacilla alb"))
    }

    @Test fun `tambien estan las aves de España que BirdNET no conoce`() {
        val hit = search.search("quebrantahuesos").single()
        assertEquals(SpeciesHit("Gypaetus barbatus", "Quebrantahuesos"), hit)
        assertEquals("Quebrantahuesos", search.hit("Gypaetus barbatus")?.name)
    }

    @Test fun `los sonidos no salen y una letra no basta`() {
        assertEquals(emptyList<String>(), names("perro"))
        assertEquals(emptyList<String>(), names("l"))
    }

    @Test fun `busca por nombre cientifico exacto`() {
        assertEquals("Lavandera Blanca", search.hit("motacilla alba")?.name)
        assertEquals(null, search.hit("Dog"))
    }
}

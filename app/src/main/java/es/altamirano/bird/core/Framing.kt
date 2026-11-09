package es.altamirano.bird.core

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Una zona de la foto, normalizada (0–1), con el origen arriba a la izquierda. */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val area get() = width * height
}

/** Un cuadrado en píxeles: lo único que ve el clasificador. */
data class Square(val x: Int, val y: Int, val side: Int)

/** Una zona conexa de una máscara: los píxeles (lado × lado) y su caja. */
class Component(val pixels: BooleanArray, val box: Box)

/** La geometría del encuadre y el reescalado de imágenes, sin nada de Android. */
object Framing {
    /** Entrada de AIY Birds. */
    const val MODEL_SIDE = 224
    /** Entrada y salida de Magic Touch. */
    const val MASK_SIDE = 512
    /** Lado corto de la copia reducida donde se busca el pájaro. */
    const val SCAN_SIDE = 896

    /** Tamaños de la ventana de búsqueda, en fracción del lado corto: de la foto entera a un pájaro lejano. */
    private val SCALES = floatArrayOf(1f, 0.66f, 0.44f, 0.3f, 0.2f)

    /** Las ventanas de búsqueda por escala, de mayor a menor, solapadas a la mitad y llegando a los bordes. */
    fun scales(w: Int, h: Int): List<List<Square>> = SCALES.map { f ->
        val side = (min(w, h) * f).toInt().coerceAtLeast(1)
        val step = (side / 2).coerceAtLeast(1)
        val xs = ((0..w - side step step).toList() + (w - side)).distinct()
        val ys = ((0..h - side step step).toList() + (h - side)).distinct()
        ys.flatMap { y -> xs.map { x -> Square(x, y, side) } }
    }

    /** Algo de aire alrededor: la máscara va pegada a la silueta y el modelo aprendió con fotos, no con recortes. */
    fun padded(b: Box, amount: Float = 0.12f): Box {
        val dx = b.width * amount; val dy = b.height * amount
        return Box((b.left - dx).coerceAtLeast(0f), (b.top - dy).coerceAtLeast(0f), (b.right + dx).coerceAtMost(1f), (b.bottom + dy).coerceAtMost(1f))
    }

    /**
     * El cuadrado que contiene la zona, centrado en ella y metido en la foto. Si fuera rectangular,
     * al pasarlo a 224×224 el pájaro saldría deformado.
     */
    fun square(b: Box, w: Int, h: Int): Square {
        val bw = b.width * w; val bh = b.height * h
        val side = max(bw, bh).roundToInt().coerceIn(1, min(w, h))
        val cx = (b.left + b.right) / 2 * w; val cy = (b.top + b.bottom) / 2 * h
        return Square((cx - side / 2f).roundToInt().coerceIn(0, w - side), (cy - side / 2f).roundToInt().coerceIn(0, h - side), side)
    }

    /** Una zona de [area] (0–1) expresada dentro de la foto entera. */
    fun unproject(b: Box, area: Box) = Box(area.left + b.left * area.width, area.top + b.top * area.height,
        area.left + b.right * area.width, area.top + b.bottom * area.height)

    /** Ni una mota ni la foto entera: como en iOS, ninguna de las dos sirve de encuadre. */
    fun isUseful(b: Box) = b.area > 0.004f && b.area < 0.95f

    /**
     * Reescala la zona (x, y, rw, rh) de una imagen ARGB. Al reducir, promedia todos los píxeles de
     * origen: `Bitmap.createScaledBitmap` sólo mira 2×2 y, al reducir mucho, el aliasing confunde al
     * modelo. Al ampliar (un pájaro lejano), bilineal.
     */
    fun resample(px: IntArray, srcW: Int, x: Int, y: Int, rw: Int, rh: Int, dstW: Int, dstH: Int): IntArray {
        val out = IntArray(dstW * dstH)
        if (rw > dstW || rh > dstH) {
            for (dy in 0 until dstH) {
                val y0 = y + dy * rh / dstH; val y1 = max(y0 + 1, y + (dy + 1) * rh / dstH)
                for (dx in 0 until dstW) {
                    val x0 = x + dx * rw / dstW; val x1 = max(x0 + 1, x + (dx + 1) * rw / dstW)
                    var r = 0; var g = 0; var b = 0; var n = 0
                    for (sy in y0 until y1) for (sx in x0 until x1) {
                        val c = px[sy * srcW + sx]
                        r += c shr 16 and 0xFF; g += c shr 8 and 0xFF; b += c and 0xFF; n++
                    }
                    out[dy * dstW + dx] = (0xFF shl 24) or (r / n shl 16) or (g / n shl 8) or (b / n)
                }
            }
        } else {
            for (dy in 0 until dstH) {
                val sy = ((dy + 0.5f) * rh / dstH - 0.5f).coerceIn(0f, rh - 1f)
                val y0 = floor(sy).toInt(); val y1 = min(y0 + 1, rh - 1); val fy = sy - y0
                for (dx in 0 until dstW) {
                    val sx = ((dx + 0.5f) * rw / dstW - 0.5f).coerceIn(0f, rw - 1f)
                    val x0 = floor(sx).toInt(); val x1 = min(x0 + 1, rw - 1); val fx = sx - x0
                    val a = px[(y + y0) * srcW + x + x0]; val b = px[(y + y0) * srcW + x + x1]
                    val c = px[(y + y1) * srcW + x + x0]; val d = px[(y + y1) * srcW + x + x1]
                    fun mix(shift: Int): Int {
                        val top = (a shr shift and 0xFF) * (1 - fx) + (b shr shift and 0xFF) * fx
                        val bottom = (c shr shift and 0xFF) * (1 - fx) + (d shr shift and 0xFF) * fx
                        return (top * (1 - fy) + bottom * fy).roundToInt()
                    }
                    out[dy * dstW + dx] = (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
                }
            }
        }
        return out
    }

    /**
     * La zona de la máscara bajo el toque; si el toque cayó fuera, la mayor. Magic Touch a veces
     * marca además manchas sueltas lejos del sujeto, y estirarían el recuadro.
     */
    fun component(mask: FloatArray, side: Int, tapX: Float, tapY: Float): Component? {
        val on = BooleanArray(mask.size) { mask[it] > 0.5f }
        val seen = BooleanArray(mask.size)
        val stack = IntArray(mask.size)
        fun flood(start: Int): IntArray {
            var top = 0; var count = 0
            val members = IntArray(mask.size)
            stack[top++] = start; seen[start] = true
            while (top > 0) {
                val p = stack[--top]; members[count++] = p
                val x = p % side
                if (x > 0 && on[p - 1] && !seen[p - 1]) { seen[p - 1] = true; stack[top++] = p - 1 }
                if (x < side - 1 && on[p + 1] && !seen[p + 1]) { seen[p + 1] = true; stack[top++] = p + 1 }
                if (p >= side && on[p - side] && !seen[p - side]) { seen[p - side] = true; stack[top++] = p - side }
                if (p < mask.size - side && on[p + side] && !seen[p + side]) { seen[p + side] = true; stack[top++] = p + side }
            }
            return members.copyOf(count)
        }
        val tap = (tapY * side).toInt().coerceIn(0, side - 1) * side + (tapX * side).toInt().coerceIn(0, side - 1)
        val members = if (on[tap]) flood(tap) else {
            var best = IntArray(0)
            for (p in on.indices) if (on[p] && !seen[p]) flood(p).let { if (it.size > best.size) best = it }
            best
        }
        if (members.isEmpty()) return null
        val pixels = BooleanArray(mask.size)
        var minX = side; var minY = side; var maxX = -1; var maxY = -1
        for (p in members) {
            pixels[p] = true
            val px = p % side; val py = p / side
            if (px < minX) minX = px; if (px > maxX) maxX = px
            if (py < minY) minY = py; if (py > maxY) maxY = py
        }
        val s = side.toFloat()
        return Component(pixels, Box(minX / s, minY / s, (maxX + 1) / s, (maxY + 1) / s))
    }
}

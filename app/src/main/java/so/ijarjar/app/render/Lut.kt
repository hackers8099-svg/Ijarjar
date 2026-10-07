package so.ijarjar.app.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import java.util.concurrent.ConcurrentHashMap

/** A 3D colour lookup table read from an Adobe / DaVinci ".cube" file. */
class Lut(val size: Int, private val data: FloatArray) {

    private fun idx(r: Int, g: Int, b: Int) = ((b * size + g) * size + r) * 3

    /** Trilinear lookup; rgb in 0..1. */
    fun lookup(r: Float, g: Float, b: Float, out: FloatArray) {
        val n = size - 1
        val fr = (r.coerceIn(0f, 1f) * n); val fg = (g.coerceIn(0f, 1f) * n); val fb = (b.coerceIn(0f, 1f) * n)
        val r0 = fr.toInt().coerceAtMost(n - 1).coerceAtLeast(0); val g0 = fg.toInt().coerceAtMost(n - 1).coerceAtLeast(0); val b0 = fb.toInt().coerceAtMost(n - 1).coerceAtLeast(0)
        val dr = fr - r0; val dg = fg - g0; val db = fb - b0
        for (c in 0 until 3) {
            val c000 = data[idx(r0, g0, b0) + c]; val c100 = data[idx(r0 + 1, g0, b0) + c]
            val c010 = data[idx(r0, g0 + 1, b0) + c]; val c110 = data[idx(r0 + 1, g0 + 1, b0) + c]
            val c001 = data[idx(r0, g0, b0 + 1) + c]; val c101 = data[idx(r0 + 1, g0, b0 + 1) + c]
            val c011 = data[idx(r0, g0 + 1, b0 + 1) + c]; val c111 = data[idx(r0 + 1, g0 + 1, b0 + 1) + c]
            val c00 = c000 + (c100 - c000) * dr; val c10 = c010 + (c110 - c010) * dr
            val c01 = c001 + (c101 - c001) * dr; val c11 = c011 + (c111 - c011) * dr
            val c0 = c00 + (c10 - c00) * dg; val c1 = c01 + (c11 - c01) * dg
            out[c] = c0 + (c1 - c0) * db
        }
    }

    /** Applies the LUT to a bitmap on the CPU (preview / photos). */
    fun apply(src: Bitmap, strength: Float, dst: Bitmap? = null): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val o = FloatArray(3)
        val s = strength.coerceIn(0f, 1f)
        for (i in px.indices) {
            val c = px[i]
            val r = ((c shr 16) and 0xFF) / 255f; val g = ((c shr 8) and 0xFF) / 255f; val b = (c and 0xFF) / 255f
            lookup(r, g, b, o)
            val nr = ((r + (o[0] - r) * s) * 255f).toInt().coerceIn(0, 255)
            val ng = ((g + (o[1] - g) * s) * 255f).toInt().coerceIn(0, 255)
            val nb = ((b + (o[2] - b) * s) * 255f).toInt().coerceIn(0, 255)
            px[i] = (c and 0xFF000000.toInt()) or (nr shl 16) or (ng shl 8) or nb
        }
        val out = if (dst != null && dst.width == w && dst.height == h && dst.isMutable) dst else Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    /** Media3 cube [R][G][B] of ARGB ints, with strength baked in (max 33 points per side). */
    fun toCube(strength: Float): Array<Array<IntArray>> {
        val n = minOf(size, 33)
        val o = FloatArray(3)
        val s = strength.coerceIn(0f, 1f)
        return Array(n) { ri ->
            Array(n) { gi ->
                IntArray(n) { bi ->
                    val r = ri / (n - 1f); val g = gi / (n - 1f); val b = bi / (n - 1f)
                    lookup(r, g, b, o)
                    Color.rgb(((r + (o[0] - r) * s) * 255).toInt().coerceIn(0, 255),
                        ((g + (o[1] - g) * s) * 255).toInt().coerceIn(0, 255),
                        ((b + (o[2] - b) * s) * 255).toInt().coerceIn(0, 255))
                }
            }
        }
    }

    companion object {
        private val cache = ConcurrentHashMap<String, Lut>()

        fun load(context: Context, uri: String): Lut? {
            cache[uri]?.let { return it }
            return try {
                val text = context.contentResolver.openInputStream(Uri.parse(uri))?.bufferedReader()?.use { it.readText() } ?: return null
                parse(text)?.also { cache[uri] = it }
            } catch (e: Exception) {
                null
            }
        }

        fun parse(text: String): Lut? {
            var size = 0
            var min = floatArrayOf(0f, 0f, 0f); var max = floatArrayOf(1f, 1f, 1f)
            val values = ArrayList<Float>()
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val parts = line.split(Regex("\\s+"))
                when {
                    parts[0] == "LUT_3D_SIZE" -> size = parts[1].toInt()
                    parts[0] == "DOMAIN_MIN" -> min = floatArrayOf(parts[1].toFloat(), parts[2].toFloat(), parts[3].toFloat())
                    parts[0] == "DOMAIN_MAX" -> max = floatArrayOf(parts[1].toFloat(), parts[2].toFloat(), parts[3].toFloat())
                    parts[0].first().isLetter() -> {}
                    parts.size >= 3 -> {
                        val r = parts[0].toFloatOrNull(); val g = parts[1].toFloatOrNull(); val b = parts[2].toFloatOrNull()
                        if (r != null && g != null && b != null) {
                            values.add((r - min[0]) / (max[0] - min[0]))
                            values.add((g - min[1]) / (max[1] - min[1]))
                            values.add((b - min[2]) / (max[2] - min[2]))
                        }
                    }
                }
            }
            if (size < 2 || values.size < size * size * size * 3) return null
            return Lut(size, values.subList(0, size * size * size * 3).toFloatArray())
        }
    }
}

package so.ijarjar.app.render

import android.graphics.Bitmap
import android.graphics.Color
import android.util.LruCache
import so.ijarjar.app.model.Layer
import kotlin.math.sqrt

/**
 * Green-screen keying (After Effects Keylight style): screen colour, tolerance, softness,
 * matte choke, spill suppression and a matte view.
 */
object Chroma {

    private val cache = LruCache<String, Bitmap>(6)

    private fun cbcr(r: Int, g: Int, b: Int): FloatArray {
        val cb = -0.168736f * r - 0.331264f * g + 0.5f * b
        val cr = 0.5f * r - 0.418688f * g - 0.081312f * b
        return floatArrayOf(cb / 255f, cr / 255f)
    }

    /** Keys [src] into a new bitmap. Static pictures are cached with [cacheKey]. */
    fun key(src: Bitmap, l: Layer, cacheKey: String? = null): Bitmap {
        val ck = cacheKey?.let { "$it|${l.chromaColor}|${l.chromaTol}|${l.chromaSoft}|${l.chromaSpill}|${l.chromaChoke}|${l.chromaMatte}|${src.width}" }
        if (ck != null) cache.get(ck)?.let { return it }
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val kr = Color.red(l.chromaColor); val kg = Color.green(l.chromaColor); val kb = Color.blue(l.chromaColor)
        val k = cbcr(kr, kg, kb)
        // which channel is the screen colour (for spill)
        val dominant = when {
            kg >= kr && kg >= kb -> 1
            kb >= kr && kb >= kg -> 2
            else -> 0
        }
        val tol = (l.chromaTol * 0.5f + l.chromaChoke * 0.1f).coerceIn(0f, 1f)
        val soft = (l.chromaSoft * 0.4f).coerceAtLeast(0.002f)
        val spill = l.chromaSpill.coerceIn(0f, 1f)
        for (i in px.indices) {
            val c = px[i]
            val a0 = c ushr 24
            if (a0 == 0) continue
            var r = (c shr 16) and 0xFF
            var g = (c shr 8) and 0xFF
            var b = c and 0xFF
            val cb = -0.168736f * r - 0.331264f * g + 0.5f * b
            val cr = 0.5f * r - 0.418688f * g - 0.081312f * b
            val dx = cb / 255f - k[0]; val dy = cr / 255f - k[1]
            val d = sqrt(dx * dx + dy * dy)
            var a = ((d - tol) / soft).coerceIn(0f, 1f)
            a = a * a * (3 - 2 * a)
            if (spill > 0f) {
                when (dominant) {
                    1 -> { val lim = maxOf(r, b); if (g > lim) g = (g - (g - lim) * spill).toInt() }
                    2 -> { val lim = maxOf(r, g); if (b > lim) b = (b - (b - lim) * spill).toInt() }
                    else -> { val lim = maxOf(g, b); if (r > lim) r = (r - (r - lim) * spill).toInt() }
                }
            }
            val alpha = (a * a0).toInt().coerceIn(0, 255)
            px[i] = if (l.chromaMatte) {
                val v = alpha
                (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            } else (alpha shl 24) or (r shl 16) or (g shl 8) or b
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        if (ck != null) cache.put(ck, out)
        return out
    }
}

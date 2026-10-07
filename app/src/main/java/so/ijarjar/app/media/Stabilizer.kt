package so.ijarjar.app.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import kotlin.math.abs

/**
 * Auto stabilise: measures camera shake between frames (block matching on small grey frames),
 * smooths the camera path and returns the correction for every 100 ms of source time.
 */
object Stabilizer {

    private const val W = 160
    private const val STEP_MS = 100L

    private fun gray(b: Bitmap): Pair<IntArray, Int> {
        val h = (W * b.height / b.width.coerceAtLeast(1)).coerceAtLeast(16)
        val s = if (b.width != W) Bitmap.createScaledBitmap(b, W, h, true) else b
        val px = IntArray(W * h)
        s.getPixels(px, 0, W, 0, 0, W, h)
        for (i in px.indices) {
            val c = px[i]
            px[i] = (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8
        }
        return Pair(px, h)
    }

    /** Best (dx, dy) so that b(x+dx, y+dy) matches a(x, y). */
    private fun shift(a: IntArray, b: IntArray, h: Int): IntArray {
        val r = 10
        var best = Long.MAX_VALUE; var bx = 0; var by = 0
        val x0 = r + 8; val x1 = W - r - 8; val y0 = r + 8; val y1 = h - r - 8
        if (x1 <= x0 || y1 <= y0) return intArrayOf(0, 0)
        for (dy in -r..r) for (dx in -r..r) {
            var sad = 0L
            var y = y0
            while (y < y1) {
                var x = x0
                val row = y * W; val rowB = (y + dy) * W + dx
                while (x < x1) { sad += abs(a[row + x] - b[rowB + x]); x += 3 }
                y += 3
                if (sad >= best) break
            }
            if (sad < best) { best = sad; bx = dx; by = dy }
        }
        return intArrayOf(bx, by)
    }

    /** Returns (x, y) correction pairs as fractions of the frame, or null on failure. */
    fun analyse(context: Context, uri: Uri, startMs: Long, endMs: Long, progress: (Int) -> Unit): MutableList<Float>? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            val n = ((endMs - startMs) / STEP_MS).toInt() + 1
            val rawX = FloatArray(n); val rawY = FloatArray(n)
            var prev: IntArray? = null
            var h = 0
            var x = 0f; var y = 0f
            for (i in 0 until n) {
                // source time relative to the clip's untrimmed start
                val t = (startMs + i * STEP_MS) * 1000
                val f = if (Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST, W, W * 4)
                else r.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST)
                if (f != null) {
                    val (g, gh) = gray(f)
                    val p = prev
                    if (p != null && gh == h) {
                        val s = shift(p, g, gh)
                        // content moved by s => camera moved by -s
                        x += s[0]; y += s[1]
                    }
                    prev = g; h = gh
                }
                rawX[i] = x; rawY[i] = y
                if (i % 5 == 0) progress(i * 100 / n)
            }
            // smooth the camera path, correction = smooth - raw
            val win = 12
            val out = ArrayList<Float>(n * 2)
            for (i in 0 until n) {
                var sx = 0f; var sy = 0f; var c = 0
                for (k in (i - win).coerceAtLeast(0)..(i + win).coerceAtMost(n - 1)) { sx += rawX[k]; sy += rawY[k]; c++ }
                // content shifted by raw: move it back towards the smooth path
                out.add(-(rawX[i] - sx / c) / W)
                out.add(-(rawY[i] - sy / c) / h.coerceAtLeast(1))
            }
            out
        } catch (e: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }
}

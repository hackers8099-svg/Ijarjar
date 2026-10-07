package so.ijarjar.app.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import kotlin.math.abs

/**
 * Point tracking (auto track): follows a small patch of the video from frame to frame
 * with template matching. Positions are fractions (0..1) of the video frame.
 */
object Tracker {

    private const val W = 240

    private class Gray(val px: IntArray, val w: Int, val h: Int)

    private fun gray(b: Bitmap): Gray {
        val h = (W * b.height / b.width.coerceAtLeast(1)).coerceAtLeast(16)
        val s = if (b.width != W || b.height != h) Bitmap.createScaledBitmap(b, W, h, true) else b
        val px = IntArray(W * h)
        s.getPixels(px, 0, W, 0, 0, W, h)
        for (i in px.indices) {
            val c = px[i]
            px[i] = (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8
        }
        return Gray(px, W, h)
    }

    /**
     * Follows the point (u, v) starting at source time [srcTimes][0].
     * Returns one (u, v) pair per source time, or null if the video can't be read.
     */
    fun track(context: Context, uri: Uri, srcTimes: List<Long>, u0: Float, v0: Float, boxFrac: Float, progress: (Int) -> Unit): List<FloatArray>? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            val out = ArrayList<FloatArray>()
            var tmpl: FloatArray? = null
            val half = (W * boxFrac / 2f).toInt().coerceIn(6, 40)
            val search = half + 18
            var x = 0; var y = 0
            for ((i, t) in srcTimes.withIndex()) {
                val f = if (Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST, W, W * 4)
                else r.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                if (f == null) { out.add(out.lastOrNull() ?: floatArrayOf(u0, v0)); continue }
                val g = gray(f)
                if (tmpl == null) {
                    x = (u0 * g.w).toInt().coerceIn(half, g.w - half - 1)
                    y = (v0 * g.h).toInt().coerceIn(half, g.h - half - 1)
                    tmpl = patch(g, x, y, half)
                } else {
                    var best = Float.MAX_VALUE; var bx = x; var by = y
                    val t0 = tmpl
                    for (dy in -search + half..search - half step 1) {
                        val cy = y + dy
                        if (cy - half < 0 || cy + half >= g.h) continue
                        for (dx in -search + half..search - half step 1) {
                            val cx = x + dx
                            if (cx - half < 0 || cx + half >= g.w) continue
                            var sad = 0f
                            var k = 0
                            var yy = -half
                            while (yy <= half) {
                                var xx = -half
                                val row = (cy + yy) * g.w + cx
                                while (xx <= half) { sad += abs(g.px[row + xx] - t0[k]); k++; xx += 2 }
                                yy += 2
                                if (sad >= best) break
                            }
                            // prefer small moves when two spots look the same
                            sad += (abs(dx) + abs(dy)) * 2f
                            if (sad < best) { best = sad; bx = cx; by = cy }
                        }
                    }
                    x = bx; y = by
                    // adapt slowly to changes in lighting / angle
                    val np = patch(g, x, y, half)
                    for (k in t0.indices) t0[k] = t0[k] * 0.8f + np[k] * 0.2f
                }
                out.add(floatArrayOf(x.toFloat() / g.w, y.toFloat() / g.h))
                if (i % 3 == 0) progress(i * 100 / srcTimes.size)
            }
            out
        } catch (e: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    private fun patch(g: Gray, x: Int, y: Int, half: Int): FloatArray {
        val list = ArrayList<Float>()
        var yy = -half
        while (yy <= half) {
            var xx = -half
            while (xx <= half) {
                val px = (x + xx).coerceIn(0, g.w - 1); val py = (y + yy).coerceIn(0, g.h - 1)
                list.add(g.px[py * g.w + px].toFloat()); xx += 2
            }
            yy += 2
        }
        return list.toFloatArray()
    }
}

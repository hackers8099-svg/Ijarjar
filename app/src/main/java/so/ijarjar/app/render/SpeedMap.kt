package so.ijarjar.app.render

import so.ijarjar.app.model.Clip
import so.ijarjar.app.model.SpeedCurve

/**
 * Speed curves. The clip's source range is split into [SEGMENTS] equal parts; each part plays at a
 * constant speed taken from the curve. Preview and export use the same table, so timing matches.
 */
object SpeedMap {
    const val SEGMENTS = 24

    private fun points(c: Clip): FloatArray =
        if (c.curve == SpeedCurve.CUSTOM) c.curvePoints.map { it.coerceIn(0.1f, 10f) }.toFloatArray() else c.curve.points

    /** Speed of each segment. */
    fun speeds(c: Clip): FloatArray {
        val p = points(c)
        return FloatArray(SEGMENTS) { k ->
            val u = (k + 0.5f) / SEGMENTS * (p.size - 1)
            val i = u.toInt().coerceAtMost(p.size - 2)
            val f = u - i
            (p[i] + (p[i + 1] - p[i]) * f).coerceIn(0.1f, 10f)
        }
    }

    private fun segSrc(c: Clip) = c.trimmedMs.toDouble() / SEGMENTS

    fun outDuration(c: Clip): Long {
        val s = speeds(c); val seg = segSrc(c)
        var total = 0.0
        for (v in s) total += seg / v
        return total.toLong().coerceAtLeast(1)
    }

    /** Source time (ms from trim start) -> output time (ms from clip start). */
    fun srcToOut(c: Clip, src: Long): Long {
        if (!c.hasCurve) return (src / c.speed).toLong()
        val s = speeds(c); val seg = segSrc(c)
        var out = 0.0
        var left = src.toDouble().coerceIn(0.0, c.trimmedMs.toDouble())
        for (v in s) {
            val d = minOf(left, seg)
            out += d / v
            left -= d
            if (left <= 0) break
        }
        return out.toLong()
    }

    /** Output time (ms from clip start) -> source time (ms from trim start). */
    fun outToSrc(c: Clip, out: Long): Long {
        if (!c.hasCurve) return (out * c.speed).toLong()
        val s = speeds(c); val seg = segSrc(c)
        var src = 0.0
        var left = out.toDouble().coerceAtLeast(0.0)
        for (v in s) {
            val segOut = seg / v
            if (left <= segOut) { src += left * v; return src.toLong() }
            src += seg
            left -= segOut
        }
        return c.trimmedMs
    }

    fun speedAtSrc(c: Clip, src: Long): Float {
        if (!c.hasCurve) return c.speed
        val s = speeds(c)
        val k = (src.toDouble() / segSrc(c)).toInt().coerceIn(0, SEGMENTS - 1)
        return s[k]
    }

    /** Next segment boundary after [srcUs] (microseconds from trim start), or -1. */
    fun nextChangeUs(c: Clip, srcUs: Long): Long {
        val segUs = segSrc(c) * 1000.0
        val k = (srcUs / segUs).toInt() + 1
        return if (k >= SEGMENTS) -1 else (k * segUs).toLong()
    }
}

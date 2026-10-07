package so.ijarjar.app.render

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GaussianBlur
import androidx.media3.effect.RgbMatrix
import so.ijarjar.app.model.Adjust
import so.ijarjar.app.model.FilterPreset

/**
 * One colour pipeline shared by the preview (Android ColorMatrix) and the export (Media3 RgbMatrix),
 * so what the user sees is what gets exported.
 */
object Filters {

    private fun offsetMatrix(r: Float, g: Float, b: Float) = ColorMatrix(floatArrayOf(
        1f, 0f, 0f, 0f, r,
        0f, 1f, 0f, 0f, g,
        0f, 0f, 1f, 0f, b,
        0f, 0f, 0f, 1f, 0f))

    private fun gainMatrix(r: Float, g: Float, b: Float) = ColorMatrix(floatArrayOf(
        r, 0f, 0f, 0f, 0f,
        0f, g, 0f, 0f, 0f,
        0f, 0f, b, 0f, 0f,
        0f, 0f, 0f, 1f, 0f))

    fun colorMatrix(a: Adjust): ColorMatrix {
        val m = ColorMatrix()
        presetMatrix(a.preset)?.let { pm ->
            val k = a.presetAmount.coerceIn(0f, 1f)
            if (k >= 0.999f) m.postConcat(pm)
            else {
                // mix between "no filter" and the full filter
                val id = ColorMatrix().array; val f = pm.array
                m.postConcat(ColorMatrix(FloatArray(20) { id[it] + (f[it] - id[it]) * k }))
            }
        }
        if (a.temperature != 0f) {
            val t = a.temperature * 0.25f
            m.postConcat(gainMatrix(1f + t, 1f, 1f - t))
        }
        if (a.tint != 0f) {
            val t = a.tint * 0.2f
            m.postConcat(gainMatrix(1f + t * 0.5f, 1f - t, 1f + t * 0.5f))
        }
        if (a.saturation != 0f) {
            val s = ColorMatrix(); s.setSaturation(1f + a.saturation); m.postConcat(s)
        }
        if (a.contrast != 0f) {
            val c = 1f + a.contrast
            val t = 128f * (1f - c)
            m.postConcat(ColorMatrix(floatArrayOf(
                c, 0f, 0f, 0f, t,
                0f, c, 0f, 0f, t,
                0f, 0f, c, 0f, t,
                0f, 0f, 0f, 1f, 0f)))
        }
        if (a.brightness != 0f) {
            val b = a.brightness * 255f * 0.6f
            m.postConcat(offsetMatrix(b, b, b))
        }
        return m
    }

    fun colorFilter(a: Adjust): ColorMatrixColorFilter? =
        if (a.isColorIdentity()) null else ColorMatrixColorFilter(colorMatrix(a))

    /** A colour grade: saturation, contrast, brightness, R/G/B gain and R/G/B lift. */
    private fun grade(sat: Float, con: Float, bri: Float, rg: Float, gg: Float, bg: Float, ro: Float, go: Float, bo: Float): ColorMatrix {
        val m = ColorMatrix().apply { setSaturation(sat) }
        val t = 128f * (1f - con)
        m.postConcat(ColorMatrix(floatArrayOf(
            con * rg, 0f, 0f, 0f, t + bri + ro,
            0f, con * gg, 0f, 0f, t + bri + go,
            0f, 0f, con * bg, 0f, t + bri + bo,
            0f, 0f, 0f, 1f, 0f)))
        return m
    }

    private fun presetMatrix(p: FilterPreset): ColorMatrix? = when (p) {
        FilterPreset.NONE -> null
        FilterPreset.BW -> ColorMatrix().apply { setSaturation(0f) }
        FilterPreset.SEPIA -> ColorMatrix(floatArrayOf(
            0.393f, 0.769f, 0.189f, 0f, 0f,
            0.349f, 0.686f, 0.168f, 0f, 0f,
            0.272f, 0.534f, 0.131f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f))
        FilterPreset.VINTAGE -> ColorMatrix(floatArrayOf(
            0.9f, 0.15f, 0.05f, 0f, 18f,
            0.1f, 0.8f, 0.1f, 0f, 10f,
            0.05f, 0.15f, 0.6f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f))
        FilterPreset.COOL -> ColorMatrix(floatArrayOf(
            0.9f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 5f,
            0f, 0f, 1.15f, 0f, 15f,
            0f, 0f, 0f, 1f, 0f))
        FilterPreset.WARM -> ColorMatrix(floatArrayOf(
            1.15f, 0f, 0f, 0f, 12f,
            0f, 1.02f, 0f, 0f, 4f,
            0f, 0f, 0.85f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f))
        FilterPreset.VIVID -> ColorMatrix().apply { setSaturation(1.6f) }
        FilterPreset.FADE -> ColorMatrix(floatArrayOf(
            0.8f, 0.1f, 0.1f, 0f, 30f,
            0.1f, 0.8f, 0.1f, 0f, 30f,
            0.1f, 0.1f, 0.8f, 0f, 30f,
            0f, 0f, 0f, 1f, 0f))
        FilterPreset.NOIR -> ColorMatrix().apply {
            setSaturation(0f)
            postConcat(ColorMatrix(floatArrayOf(
                1.5f, 0f, 0f, 0f, -60f,
                0f, 1.5f, 0f, 0f, -60f,
                0f, 0f, 1.5f, 0f, -60f,
                0f, 0f, 0f, 1f, 0f)))
        }
        FilterPreset.GOLDEN -> ColorMatrix(floatArrayOf(
            1.2f, 0.1f, 0f, 0f, 10f,
            0.05f, 1.05f, 0f, 0f, 5f,
            0f, 0f, 0.7f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f))
        FilterPreset.TEAL_ORANGE -> ColorMatrix(floatArrayOf(
            1.15f, 0.05f, -0.1f, 0f, 5f,
            0f, 1.0f, 0.05f, 0f, 0f,
            -0.1f, 0.1f, 1.15f, 0f, 8f,
            0f, 0f, 0f, 1f, 0f)).apply { postConcat(ColorMatrix().apply { setSaturation(1.2f) }) }
        FilterPreset.PINK -> ColorMatrix(floatArrayOf(
            1.1f, 0f, 0.05f, 0f, 20f,
            0f, 0.9f, 0f, 0f, 0f,
            0.05f, 0f, 1.05f, 0f, 15f,
            0f, 0f, 0f, 1f, 0f))
        FilterPreset.MOODY -> ColorMatrix().apply {
            setSaturation(0.7f)
            postConcat(ColorMatrix(floatArrayOf(
                0.95f, 0f, 0f, 0f, -10f,
                0f, 1f, 0f, 0f, -5f,
                0f, 0f, 1.1f, 0f, 5f,
                0f, 0f, 0f, 1f, 0f)))
        }
        FilterPreset.PASTEL -> ColorMatrix().apply {
            setSaturation(0.6f)
            postConcat(ColorMatrix(floatArrayOf(
                0.85f, 0f, 0f, 0f, 40f,
                0f, 0.85f, 0f, 0f, 38f,
                0f, 0f, 0.85f, 0f, 45f,
                0f, 0f, 0f, 1f, 0f)))
        }
        FilterPreset.SUNSET -> ColorMatrix(floatArrayOf(
            1.2f, 0.05f, 0f, 0f, 15f,
            0f, 0.95f, 0f, 0f, 0f,
            0f, 0f, 0.8f, 0f, 10f,
            0f, 0f, 0f, 1f, 0f))
        FilterPreset.FOREST -> ColorMatrix(floatArrayOf(
            0.9f, 0f, 0f, 0f, 0f,
            0.05f, 1.1f, 0f, 0f, 5f,
            0f, 0.05f, 0.9f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f))
        FilterPreset.MATTE -> ColorMatrix(floatArrayOf(
            0.85f, 0f, 0f, 0f, 25f,
            0f, 0.85f, 0f, 0f, 25f,
            0f, 0f, 0.85f, 0f, 30f,
            0f, 0f, 0f, 1f, 0f)).apply { postConcat(ColorMatrix().apply { setSaturation(0.85f) }) }
        FilterPreset.CYBER -> ColorMatrix(floatArrayOf(
            1.1f, 0f, 0.2f, 0f, 10f,
            0f, 0.85f, 0.1f, 0f, 0f,
            0.1f, 0f, 1.3f, 0f, 20f,
            0f, 0f, 0f, 1f, 0f)).apply { postConcat(ColorMatrix().apply { setSaturation(1.3f) }) }
        FilterPreset.MONO_HI -> ColorMatrix().apply {
            setSaturation(0f)
            postConcat(ColorMatrix(floatArrayOf(
                1.8f, 0f, 0f, 0f, -100f,
                0f, 1.8f, 0f, 0f, -100f,
                0f, 0f, 1.8f, 0f, -100f,
                0f, 0f, 0f, 1f, 0f)))
        }
        FilterPreset.KODAK -> ColorMatrix(floatArrayOf(
            1.1f, 0.05f, 0f, 0f, 8f,
            0.02f, 1.02f, 0f, 0f, 4f,
            0f, 0.05f, 0.9f, 0f, -4f,
            0f, 0f, 0f, 1f, 0f))
        FilterPreset.CLEAR -> grade(1.05f, 1.08f, 6f, 1f, 1f, 1.03f, 0f, 0f, 0f)
        FilterPreset.FRESH -> grade(1.15f, 1.05f, 8f, 0.97f, 1.03f, 1.05f, 0f, 2f, 4f)
        FilterPreset.BRIGHT -> grade(1.05f, 0.95f, 22f, 1f, 1f, 1f, 0f, 0f, 0f)
        FilterPreset.SOFT -> grade(0.85f, 0.85f, 18f, 1.02f, 1f, 0.98f, 4f, 4f, 4f)
        FilterPreset.SUNNY -> grade(1.2f, 1.05f, 10f, 1.08f, 1.03f, 0.9f, 4f, 2f, -6f)
        FilterPreset.FILM_200 -> grade(0.9f, 1.05f, 2f, 1.05f, 1f, 0.92f, 8f, 4f, 0f)
        FilterPreset.FILM_400 -> grade(0.85f, 1.1f, 0f, 1f, 1.02f, 0.95f, 6f, 6f, 10f)
        FilterPreset.POLAROID -> grade(0.8f, 0.9f, 14f, 1.05f, 1.02f, 0.9f, 14f, 10f, 4f)
        FilterPreset.PORTRA -> grade(0.92f, 0.98f, 6f, 1.06f, 1.0f, 0.93f, 6f, 2f, 0f)
        FilterPreset.CHROME -> grade(1.25f, 1.15f, 0f, 1.02f, 1f, 1.02f, -4f, -4f, -2f)
        FilterPreset.RETRO -> grade(0.75f, 0.95f, 6f, 1.1f, 1.02f, 0.8f, 16f, 8f, 0f)
        FilterPreset.SEVENTIES -> grade(0.8f, 0.9f, 4f, 1.12f, 1f, 0.78f, 20f, 10f, -4f)
        FilterPreset.FADED_RED -> grade(0.7f, 0.85f, 10f, 1.12f, 0.95f, 0.92f, 20f, 6f, 8f)
        FilterPreset.OLD_PHOTO -> grade(0.35f, 0.8f, 10f, 1.1f, 1.02f, 0.85f, 24f, 14f, 2f)
        FilterPreset.HOLLYWOOD -> grade(0.95f, 1.15f, -2f, 1.05f, 0.98f, 1.02f, 0f, -2f, 6f)
        FilterPreset.BLOCKBUSTER -> grade(1.1f, 1.2f, -4f, 1.08f, 0.98f, 0.92f, -4f, 0f, 14f)
        FilterPreset.DUNE -> grade(0.85f, 1.1f, 2f, 1.15f, 1.02f, 0.78f, 6f, 0f, -10f)
        FilterPreset.ARCTIC -> grade(0.8f, 1.05f, 8f, 0.88f, 1f, 1.15f, -4f, 4f, 16f)
        FilterPreset.BLEACH -> grade(0.45f, 1.3f, -4f, 1f, 1f, 1f, 0f, 0f, 0f)
        FilterPreset.SILVER -> grade(0f, 1.1f, 8f, 1f, 1f, 1.02f, 0f, 0f, 4f)
        FilterPreset.CHARCOAL -> grade(0f, 1.35f, -14f, 1f, 1f, 1f, 0f, 0f, 0f)
        FilterPreset.SELENIUM -> grade(0f, 1.1f, 0f, 0.95f, 1f, 1.1f, 0f, 0f, 10f)
        FilterPreset.SKIN_GLOW -> grade(1.0f, 0.95f, 12f, 1.06f, 1.0f, 0.97f, 8f, 4f, 4f)
        FilterPreset.ROSY -> grade(1.05f, 1f, 8f, 1.08f, 0.97f, 1.0f, 10f, 0f, 6f)
        FilterPreset.BRONZE -> grade(1.1f, 1.05f, 2f, 1.12f, 1.0f, 0.82f, 10f, 2f, -8f)
        FilterPreset.LUSH -> grade(1.3f, 1.05f, 0f, 0.95f, 1.1f, 0.92f, 0f, 6f, 0f)
        FilterPreset.OCEAN -> grade(1.2f, 1.05f, 2f, 0.88f, 1.02f, 1.15f, 0f, 4f, 12f)
        FilterPreset.AUTUMN -> grade(1.15f, 1.05f, 0f, 1.15f, 0.98f, 0.8f, 8f, 0f, -8f)
        FilterPreset.TASTY -> grade(1.35f, 1.08f, 6f, 1.08f, 1.02f, 0.92f, 6f, 2f, -4f)
        FilterPreset.CREAMY -> grade(0.9f, 0.9f, 14f, 1.05f, 1.02f, 0.95f, 12f, 8f, 4f)
        FilterPreset.NEON_NIGHT -> grade(1.4f, 1.15f, -6f, 1.05f, 0.9f, 1.15f, 6f, -6f, 18f)
        FilterPreset.MIDNIGHT -> grade(0.8f, 1.1f, -16f, 0.9f, 0.95f, 1.12f, -6f, 0f, 14f)
        FilterPreset.INVERT -> ColorMatrix(floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f))
    }

    /** Converts the Android 4x5 colour matrix to a column-major 4x4 GL matrix (colours 0..1). */
    fun toGl(cm: ColorMatrix): FloatArray {
        val a = cm.array
        val gl = FloatArray(16)
        for (r in 0 until 3) {
            for (c in 0 until 3) gl[c * 4 + r] = a[r * 5 + c]
            gl[12 + r] = a[r * 5 + 4] / 255f
        }
        gl[15] = 1f
        return gl
    }

    private val lutCache = java.util.concurrent.ConcurrentHashMap<String, Lut>()

    fun lutKey(a: Adjust) = "${a.lutUri}|${a.lutStrength}|${a.highlights}|${a.shadows}|${a.vibrance}|${a.fade}"

    /** Shadows / highlights / vibrance / fade curve on one colour (0..1). */
    private fun tone(a: Adjust, rgb: FloatArray) {
        val l = 0.299f * rgb[0] + 0.587f * rgb[1] + 0.114f * rgb[2]
        val ws = (1f - l) * (1f - l); val wh = l * l
        val add = a.shadows * 0.35f * ws + a.highlights * 0.35f * wh
        for (i in 0..2) rgb[i] += add
        if (a.vibrance != 0f) {
            val mx = maxOf(rgb[0], rgb[1], rgb[2]); val mn = minOf(rgb[0], rgb[1], rgb[2])
            val sat = if (mx <= 0f) 0f else (mx - mn) / mx
            val k = 1f + a.vibrance * (1f - sat)
            val g = 0.299f * rgb[0] + 0.587f * rgb[1] + 0.114f * rgb[2]
            for (i in 0..2) rgb[i] = g + (rgb[i] - g) * k
        }
        if (a.fade > 0f) for (i in 0..2) rgb[i] = a.fade * 0.15f + rgb[i] * (1f - a.fade * 0.15f)
        for (i in 0..2) rgb[i] = rgb[i].coerceIn(0f, 1f)
    }

    /** One LUT with the tone curve + the imported .cube file (strength baked in), or null. */
    fun lutFor(context: android.content.Context, a: Adjust): Lut? {
        val file = a.lutUri?.let { Lut.load(context, it) }
        if (!a.hasTone() && (file == null || a.lutStrength >= 0.999f)) return file
        val key = lutKey(a)
        lutCache[key]?.let { return it }
        val n = 25
        val data = FloatArray(n * n * n * 3)
        val c = FloatArray(3); val o = FloatArray(3)
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
            c[0] = r / (n - 1f); c[1] = g / (n - 1f); c[2] = b / (n - 1f)
            if (a.hasTone()) tone(a, c)
            if (file != null) {
                file.lookup(c[0], c[1], c[2], o)
                val s = a.lutStrength.coerceIn(0f, 1f)
                for (i in 0..2) c[i] = c[i] + (o[i] - c[i]) * s
            }
            val idx = ((b * n + g) * n + r) * 3
            data[idx] = c[0]; data[idx + 1] = c[1]; data[idx + 2] = c[2]
        }
        return Lut(n, data).also { lutCache[key] = it }
    }

    /** Blur radius in preview pixels for a given 0..1 strength. */
    fun blurRadiusPx(strength: Float, viewWidth: Int): Float = strength * viewWidth * 0.03f

    @UnstableApi
    fun exportEffects(a: Adjust, outputWidth: Int): List<Effect> {
        val list = ArrayList<Effect>()
        if (!a.isColorIdentity()) {
            val gl = toGl(colorMatrix(a))
            list.add(object : RgbMatrix {
                override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray = gl
            })
        }
        if (a.blur > 0f) {
            // sigma roughly matches the preview blur radius (radius ~ 2 sigma)
            list.add(GaussianBlur((blurRadiusPx(a.blur, outputWidth) / 2f).coerceIn(0.5f, 60f)))
        }
        return list
    }
}

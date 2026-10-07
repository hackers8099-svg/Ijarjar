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
        presetMatrix(a.preset)?.let { m.postConcat(it) }
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

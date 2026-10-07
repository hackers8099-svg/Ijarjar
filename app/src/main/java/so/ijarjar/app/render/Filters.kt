package so.ijarjar.app.render

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.RgbMatrix
import so.ijarjar.app.model.Adjust
import so.ijarjar.app.model.FilterPreset

/**
 * One colour pipeline shared by the preview (Android ColorMatrix) and the export (Media3 RgbMatrix),
 * so what the user sees is what gets exported.
 */
object Filters {

    fun colorMatrix(a: Adjust): ColorMatrix {
        val m = ColorMatrix()
        // preset first
        val p = presetMatrix(a.preset)
        if (p != null) m.postConcat(p)
        // saturation
        if (a.saturation != 0f) {
            val s = ColorMatrix(); s.setSaturation(1f + a.saturation); m.postConcat(s)
        }
        // contrast around mid grey
        if (a.contrast != 0f) {
            val c = 1f + a.contrast
            val t = 128f * (1f - c)
            m.postConcat(ColorMatrix(floatArrayOf(
                c, 0f, 0f, 0f, t,
                0f, c, 0f, 0f, t,
                0f, 0f, c, 0f, t,
                0f, 0f, 0f, 1f, 0f)))
        }
        // brightness
        if (a.brightness != 0f) {
            val b = a.brightness * 255f * 0.6f
            m.postConcat(ColorMatrix(floatArrayOf(
                1f, 0f, 0f, 0f, b,
                0f, 1f, 0f, 0f, b,
                0f, 0f, 1f, 0f, b,
                0f, 0f, 0f, 1f, 0f)))
        }
        return m
    }

    fun colorFilter(a: Adjust): ColorMatrixColorFilter? =
        if (a.isIdentity()) null else ColorMatrixColorFilter(colorMatrix(a))

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
        FilterPreset.INVERT -> ColorMatrix(floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f))
    }

    /** Converts the Android 4x5 colour matrix to a column-major 4x4 GL matrix (colours 0..1). */
    private fun toGl(cm: ColorMatrix): FloatArray {
        val a = cm.array
        val gl = FloatArray(16)
        for (r in 0 until 3) {
            for (c in 0 until 3) gl[c * 4 + r] = a[r * 5 + c]
            gl[12 + r] = a[r * 5 + 4] / 255f
        }
        gl[15] = 1f
        return gl
    }

    @UnstableApi
    fun exportEffects(a: Adjust): List<Effect> {
        if (a.isIdentity()) return emptyList()
        val gl = toGl(colorMatrix(a))
        return listOf(object : RgbMatrix {
            override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray = gl
        })
    }
}

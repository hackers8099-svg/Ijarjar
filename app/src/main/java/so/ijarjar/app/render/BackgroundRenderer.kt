package so.ijarjar.app.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.util.LruCache
import so.ijarjar.app.media.MediaUtils
import so.ijarjar.app.model.Project

/** Background of a photo project (PixelLab style): colour, gradient and/or a picture. */
object BackgroundRenderer {

    private val blurCache = LruCache<String, Bitmap>(4)

    /** A cheap blur: shrink and scale back up with filtering. */
    private fun blurred(src: Bitmap, strength: Float, key: String): Bitmap {
        val k = "$key|$strength"
        blurCache.get(k)?.let { return it }
        val factor = (1f + strength * 30f)
        var b = src
        var w = src.width; var h = src.height
        val targetW = (src.width / factor).toInt().coerceAtLeast(4)
        // shrink in steps for smoother results
        while (w / 2 >= targetW) {
            w /= 2; h = (h / 2).coerceAtLeast(2)
            b = Bitmap.createScaledBitmap(b, w, h, true)
        }
        b = Bitmap.createScaledBitmap(b, targetW, (src.height * targetW / src.width).coerceAtLeast(2), true)
        blurCache.put(k, b)
        return b
    }

    fun draw(context: Context, canvas: Canvas, p: Project, w: Int, h: Int, maxImageDim: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (p.bgColor2 != 0) {
            paint.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), p.bgColor, p.bgColor2, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
            paint.shader = null
        } else canvas.drawColor(p.bgColor)

        val uri = p.bgImageUri ?: return
        var bmp = MediaUtils.loadBitmapCached(context, Uri.parse(uri), maxImageDim) ?: return
        if (p.bgAdjust.blur > 0f) bmp = blurred(bmp, p.bgAdjust.blur, "$uri@$maxImageDim")
        // "cover" fit, then the user transform
        val cover = maxOf(w.toFloat() / bmp.width, h.toFloat() / bmp.height)
        val m = Matrix()
        m.postTranslate(-bmp.width / 2f, -bmp.height / 2f)
        m.postScale(cover * p.bgScale * (if (p.bgMirror) -1f else 1f), cover * p.bgScale)
        m.postRotate(p.bgRot)
        m.postTranslate(w / 2f + p.bgX * w, h / 2f + p.bgY * h)
        Filters.colorFilter(p.bgAdjust)?.let { paint.colorFilter = it }
        canvas.drawBitmap(bmp, m, paint)
    }

    @Suppress("unused")
    private fun unused(f: ColorMatrixColorFilter) = f
}

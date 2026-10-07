package so.ijarjar.app.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.LruCache
import so.ijarjar.app.media.MediaUtils
import so.ijarjar.app.model.Layer
import so.ijarjar.app.model.LayerKind
import kotlin.math.ceil

/**
 * Draws layers. Used by both the live preview and the exporter so the result matches.
 * All positions are relative to the canvas size, so any resolution gives the same picture.
 */
object LayerRenderer {

    val FONTS = listOf("Caadi", "Serif", "Mono", "Far-qoraal", "Cidhiidhi")

    fun typeface(font: Int, bold: Boolean): Typeface {
        val style = if (bold) Typeface.BOLD else Typeface.NORMAL
        return when (font) {
            1 -> Typeface.create(Typeface.SERIF, style)
            2 -> Typeface.create(Typeface.MONOSPACE, style)
            3 -> Typeface.create("cursive", style)
            4 -> Typeface.create("sans-serif-condensed", style)
            else -> Typeface.create(Typeface.SANS_SERIF, style)
        }
    }

    private val textCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private fun textKey(l: Layer, canvasW: Int) =
        "${l.text}|${l.textColor}|${l.strokeColor}|${l.bgColor}|${l.textSizeFrac}|${l.bold}|${l.font}|${l.align}|$canvasW|${l.kind}"

    fun textBitmap(l: Layer, canvasW: Int): Bitmap {
        val key = textKey(l, canvasW)
        textCache.get(key)?.let { return it }
        val textPx = (l.textSizeFrac * canvasW).coerceAtLeast(6f)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = textPx
            typeface = typeface(l.font, l.bold)
            color = l.textColor
        }
        val text = l.text.ifEmpty { " " }
        val maxW = (canvasW * 0.95f).toInt().coerceAtLeast(10)
        var widest = 1f
        for (line in text.split("\n")) widest = maxOf(widest, paint.measureText(line))
        val layoutW = ceil(minOf(widest, maxW.toFloat())).toInt().coerceAtLeast(1)
        val alignment = when (l.align) {
            0 -> Layout.Alignment.ALIGN_NORMAL
            2 -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_CENTER
        }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, layoutW)
            .setAlignment(alignment)
            .setIncludePad(true)
            .build()
        val pad = (textPx * 0.3f).toInt()
        val bw = layoutW + pad * 2
        val bh = layout.height + pad * 2
        val bmp = Bitmap.createBitmap(bw.coerceAtLeast(1), bh.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (l.bgColor != 0) {
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = l.bgColor }
            c.drawRoundRect(RectF(0f, 0f, bw.toFloat(), bh.toFloat()), textPx * 0.25f, textPx * 0.25f, bg)
        }
        c.save()
        c.translate(pad.toFloat(), pad.toFloat())
        if (l.strokeColor != 0) {
            val sp = TextPaint(paint).apply {
                style = Paint.Style.STROKE
                strokeWidth = textPx * 0.12f
                strokeJoin = Paint.Join.ROUND
                color = l.strokeColor
            }
            StaticLayout.Builder.obtain(text, 0, text.length, sp, layoutW)
                .setAlignment(alignment).setIncludePad(true).build().draw(c)
        }
        layout.draw(c)
        c.restore()
        textCache.put(key, bmp)
        return bmp
    }

    /** Size of the layer content at scale 1, in canvas pixels. */
    fun contentSize(l: Layer, canvasW: Int): Pair<Float, Float> {
        return if (l.isTextLike()) {
            val b = textBitmap(l, canvasW)
            Pair(b.width.toFloat(), b.height.toFloat())
        } else {
            val w = l.baseW * canvasW
            Pair(w, w * l.contentAspect)
        }
    }

    /** Maps layer-local content coordinates (0..cw, 0..ch) to canvas pixels. */
    fun matrix(l: Layer, canvasW: Int, canvasH: Int): Matrix {
        val (cw, ch) = contentSize(l, canvasW)
        return Matrix().apply {
            postTranslate(-cw / 2f, -ch / 2f)
            postScale(if (l.flipH) -l.scale else l.scale, l.scale)
            postRotate(l.rotation)
            postTranslate(l.cx * canvasW, l.cy * canvasH)
        }
    }

    fun hitTest(l: Layer, canvasW: Int, canvasH: Int, x: Float, y: Float, slopPx: Float): Boolean {
        val inv = Matrix()
        if (!matrix(l, canvasW, canvasH).invert(inv)) return false
        val pt = floatArrayOf(x, y)
        inv.mapPoints(pt)
        val (cw, ch) = contentSize(l, canvasW)
        val s = slopPx / l.scale.coerceAtLeast(0.05f)
        return pt[0] >= -s && pt[0] <= cw + s && pt[1] >= -s && pt[1] <= ch + s
    }

    /** Corner points of the layer box in canvas pixels (for selection outlines). */
    fun corners(l: Layer, canvasW: Int, canvasH: Int): FloatArray {
        val (cw, ch) = contentSize(l, canvasW)
        val pts = floatArrayOf(0f, 0f, cw, 0f, cw, ch, 0f, ch)
        matrix(l, canvasW, canvasH).mapPoints(pts)
        return pts
    }

    /** Video layers are drawn first (they sit under images and text, same as in the preview). */
    fun drawOrder(layers: List<Layer>): List<Layer> =
        layers.filter { it.kind == LayerKind.VIDEO } + layers.filter { it.kind != LayerKind.VIDEO }

    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /**
     * Draws a single layer. [content] is the image or video frame (ignored for text).
     */
    fun draw(context: Context, canvas: Canvas, l: Layer, canvasW: Int, canvasH: Int, content: Bitmap?) {
        val bmp: Bitmap = when {
            l.isTextLike() -> textBitmap(l, canvasW)
            content != null -> content
            l.kind == LayerKind.IMAGE && l.uri != null ->
                MediaUtils.loadBitmapCached(context, Uri.parse(l.uri), 1600) ?: return
            else -> return
        }
        val (cw, ch) = contentSize(l, canvasW)
        val m = matrix(l, canvasW, canvasH)
        // scale bitmap pixels to content size first
        val pre = Matrix().apply { setScale(cw / bmp.width, ch / bmp.height) }
        pre.postConcat(m)
        bmpPaint.alpha = (l.opacity.coerceIn(0f, 1f) * 255).toInt()
        canvas.drawBitmap(bmp, pre, bmpPaint)
    }
}

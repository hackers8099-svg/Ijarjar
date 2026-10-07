package so.ijarjar.app.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import so.ijarjar.app.model.Layer
import so.ijarjar.app.model.MockupKind

/**
 * Device mockups (phone, tablet, laptop, browser, watch, TV, polaroid) drawn around a picture or
 * video layer. With 3D rotation (rotX / rotY) the body gets real thickness.
 */
object Mockups {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private fun darker(c: Int, f: Float) = Color.argb(Color.alpha(c), (Color.red(c) * f).toInt(), (Color.green(c) * f).toInt(), (Color.blue(c) * f).toInt())
    private fun lighter(c: Int, f: Float) = Color.argb(Color.alpha(c), (Color.red(c) + (255 - Color.red(c)) * f).toInt(),
        (Color.green(c) + (255 - Color.green(c)) * f).toInt(), (Color.blue(c) + (255 - Color.blue(c)) * f).toInt())

    /** Bezel sizes (left, top, right, bottom), body corner radius, screen corner radius. */
    private fun geometry(k: MockupKind, cw: Float, ch: Float): Pair<RectF, FloatArray> {
        val s = minOf(cw, ch)
        return when (k) {
            MockupKind.PHONE -> Pair(RectF(s * 0.045f, s * 0.045f, s * 0.045f, s * 0.045f), floatArrayOf(s * 0.16f, s * 0.12f))
            MockupKind.PHONE_ROUND -> Pair(RectF(s * 0.06f, s * 0.12f, s * 0.06f, s * 0.12f), floatArrayOf(s * 0.18f, s * 0.04f))
            MockupKind.PHONE_PRO -> Pair(RectF(s * 0.035f, s * 0.035f, s * 0.035f, s * 0.035f), floatArrayOf(s * 0.17f, s * 0.14f))
            MockupKind.PHONE_ULTRA -> Pair(RectF(s * 0.03f, s * 0.03f, s * 0.03f, s * 0.03f), floatArrayOf(s * 0.05f, s * 0.035f))
            MockupKind.PHONE_BAR -> Pair(RectF(s * 0.04f, s * 0.04f, s * 0.04f, s * 0.04f), floatArrayOf(s * 0.15f, s * 0.12f))
            MockupKind.TABLET -> Pair(RectF(s * 0.05f, s * 0.05f, s * 0.05f, s * 0.05f), floatArrayOf(s * 0.07f, s * 0.03f))
            MockupKind.LAPTOP -> Pair(RectF(s * 0.035f, s * 0.045f, s * 0.035f, s * 0.035f), floatArrayOf(s * 0.04f, s * 0.006f))
            MockupKind.BROWSER -> Pair(RectF(s * 0.006f, s * 0.09f, s * 0.006f, s * 0.006f), floatArrayOf(s * 0.03f, s * 0.006f))
            MockupKind.WATCH -> Pair(RectF(s * 0.08f, s * 0.08f, s * 0.08f, s * 0.08f), floatArrayOf(s * 0.3f, s * 0.22f))
            MockupKind.TV -> Pair(RectF(s * 0.02f, s * 0.02f, s * 0.02f, s * 0.02f), floatArrayOf(s * 0.015f, s * 0.004f))
            MockupKind.POLAROID -> Pair(RectF(s * 0.07f, s * 0.07f, s * 0.07f, s * 0.28f), floatArrayOf(s * 0.015f, 0f))
            MockupKind.NONE -> Pair(RectF(), floatArrayOf(0f, 0f))
        }
    }

    /** A camera lens: metal ring, dark glass, small reflection. */
    private fun lens(canvas: Canvas, x: Float, y: Float, r: Float, base: Int, alpha: Int) {
        paint.shader = null; paint.style = Paint.Style.FILL
        paint.color = lighter(darker(base, 0.8f), 0.3f); paint.alpha = alpha
        canvas.drawCircle(x, y, r, paint)
        paint.color = 0xFF0B0B10.toInt(); paint.alpha = alpha
        canvas.drawCircle(x, y, r * 0.8f, paint)
        paint.shader = android.graphics.RadialGradient(x - r * 0.2f, y - r * 0.2f, r * 0.6f, 0xFF3A4A7A.toInt(), 0xFF0B0B10.toInt(), Shader.TileMode.CLAMP)
        canvas.drawCircle(x, y, r * 0.55f, paint)
        paint.shader = null
        paint.color = Color.WHITE; paint.alpha = alpha * 140 / 255
        canvas.drawCircle(x - r * 0.25f, y - r * 0.25f, r * 0.12f, paint)
    }

    /** Back of a phone (seen when turned around): body + camera module in the chosen style. */
    private fun drawBack(canvas: Canvas, kind: MockupKind, body: RectF, bodyR: Float, base: Int, alpha: Int, cw: Float, ch: Float, s: Float) {
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(body.right, body.top, body.left, body.bottom, lighter(base, 0.18f), darker(base, 0.8f), Shader.TileMode.CLAMP)
        paint.color = base; paint.alpha = alpha
        canvas.drawRoundRect(body, bodyR, bodyR, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE; paint.strokeWidth = s * 0.008f; paint.color = lighter(base, 0.4f); paint.alpha = alpha
        canvas.drawRoundRect(body, bodyR, bodyR, paint)
        paint.style = Paint.Style.FILL
        // seen from behind the picture is mirrored, so "top-left of the back" is at the right in our coordinates
        val r = s * 0.075f
        when (kind) {
            MockupKind.PHONE_PRO -> {
                val box = RectF(cw - s * 0.47f, s * 0.04f, cw - s * 0.04f, s * 0.47f)
                paint.color = lighter(base, 0.12f); paint.alpha = alpha
                canvas.drawRoundRect(box, s * 0.1f, s * 0.1f, paint)
                lens(canvas, box.left + box.width() * 0.3f, box.top + box.height() * 0.28f, r, base, alpha)
                lens(canvas, box.left + box.width() * 0.3f, box.top + box.height() * 0.72f, r, base, alpha)
                lens(canvas, box.left + box.width() * 0.72f, box.top + box.height() * 0.5f, r, base, alpha)
                paint.color = 0xFFFFF4D6.toInt(); paint.alpha = alpha
                canvas.drawCircle(box.left + box.width() * 0.75f, box.top + box.height() * 0.18f, s * 0.025f, paint)
            }
            MockupKind.PHONE_ULTRA -> {
                val x = cw - s * 0.16f
                for (i in 0 until 3) lens(canvas, x, s * 0.14f + i * s * 0.2f, r * 1.05f, base, alpha)
                lens(canvas, x - s * 0.17f, s * 0.14f, r * 0.55f, base, alpha)
                lens(canvas, x - s * 0.17f, s * 0.3f, r * 0.55f, base, alpha)
                paint.color = 0xFFFFF4D6.toInt(); paint.alpha = alpha
                canvas.drawCircle(x - s * 0.17f, s * 0.44f, s * 0.022f, paint)
            }
            MockupKind.PHONE_BAR -> {
                val bar = RectF(body.left, ch * 0.12f, body.right, ch * 0.12f + s * 0.2f)
                paint.color = darker(base, 0.35f); paint.alpha = alpha
                canvas.drawRoundRect(bar, s * 0.1f, s * 0.1f, paint)
                val pill = RectF(cw - s * 0.58f, bar.top + s * 0.035f, cw - s * 0.1f, bar.bottom - s * 0.035f)
                paint.color = 0xFF111116.toInt(); paint.alpha = alpha
                canvas.drawRoundRect(pill, pill.height() / 2, pill.height() / 2, paint)
                lens(canvas, pill.right - pill.height() * 0.5f, pill.centerY(), pill.height() * 0.38f, base, alpha)
                lens(canvas, pill.right - pill.height() * 1.4f, pill.centerY(), pill.height() * 0.38f, base, alpha)
                lens(canvas, pill.left + pill.height() * 0.6f, pill.centerY(), pill.height() * 0.3f, base, alpha)
            }
            else -> {
                val box = RectF(cw - s * 0.36f, s * 0.04f, cw - s * 0.04f, s * 0.36f)
                paint.color = lighter(base, 0.1f); paint.alpha = alpha
                canvas.drawRoundRect(box, s * 0.08f, s * 0.08f, paint)
                lens(canvas, box.left + box.width() * 0.3f, box.top + box.height() * 0.3f, r * 0.9f, base, alpha)
                lens(canvas, box.left + box.width() * 0.7f, box.top + box.height() * 0.7f, r * 0.9f, base, alpha)
            }
        }
    }

    fun draw(canvas: Canvas, l: Layer, pose: Pose, canvasW: Int, canvasH: Int, content: Bitmap, alpha: Int, cw: Float, ch: Float) {
        val kind = l.mockup
        val (b, radii) = geometry(kind, cw, ch)
        val body = RectF(-b.left, -b.top, cw + b.right, ch + b.bottom)
        val bodyR = radii[0]; val screenR = radii[1]
        val base = if (kind == MockupKind.POLAROID) Color.WHITE else l.mockupColor
        val s = minOf(cw, ch)

        // thickness: copies of the body slightly behind, only visible when turned in 3D
        val phone = kind == MockupKind.PHONE || kind == MockupKind.PHONE_ROUND || kind == MockupKind.PHONE_PRO ||
            kind == MockupKind.PHONE_ULTRA || kind == MockupKind.PHONE_BAR
        val m = LayerRenderer.matrix(l, pose, canvasW, canvasH)
        // which side faces us? (mirrored mapping = we look at the back)
        val pts = floatArrayOf(0f, 0f, cw, 0f, 0f, ch)
        m.mapPoints(pts)
        val cross = (pts[2] - pts[0]) * (pts[5] - pts[1]) - (pts[3] - pts[1]) * (pts[4] - pts[0])
        val back = cross < 0f
        if (pose.rx != 0f || pose.ry != 0f) {
            // the body's thickness: many thin slices = a solid metal edge
            val depth = if (phone) 0.022f else 0.016f
            @Suppress("UNUSED_VARIABLE") val unused = 0
            val steps = 16
            // farthest slice first: from the back plate when we see the front, from the front when we see the back
            val order = if (back) (0 until steps).toList() else (steps downTo 1).toList()
            for (k in order) {
                val z = k * depth / steps
                val mk = LayerRenderer.matrix(l, pose, canvasW, canvasH, z)
                canvas.save(); canvas.concat(mk)
                paint.shader = null; paint.style = Paint.Style.FILL
                val f = k.toFloat() / steps
                paint.color = if (f in 0.35f..0.65f) lighter(darker(base, 0.9f), 0.25f) else darker(base, 0.55f + 0.25f * (1f - f))
                paint.alpha = alpha
                canvas.drawRoundRect(body, bodyR, bodyR, paint)
                canvas.restore()
            }
        }
        if (back && phone) {
            canvas.save(); canvas.concat(LayerRenderer.matrix(l, pose, canvasW, canvasH, if (pose.rx != 0f || pose.ry != 0f) 0.022f else 0f))
            drawBack(canvas, kind, body, bodyR, base, alpha, cw, ch, s)
            canvas.restore()
            return
        }
        canvas.save()
        canvas.concat(m)

        // extras behind the body
        paint.shader = null; paint.style = Paint.Style.FILL; paint.alpha = alpha
        when (kind) {
            MockupKind.WATCH -> {
                paint.color = darker(base, 0.8f); paint.alpha = alpha
                canvas.drawRoundRect(RectF(cw * 0.15f, -b.top - ch * 0.6f, cw * 0.85f, 0f), s * 0.05f, s * 0.05f, paint)
                canvas.drawRoundRect(RectF(cw * 0.15f, ch, cw * 0.85f, ch + b.bottom + ch * 0.6f), s * 0.05f, s * 0.05f, paint)
            }
            MockupKind.TV -> {
                paint.color = darker(base, 0.9f); paint.alpha = alpha
                val stand = Path().apply {
                    moveTo(cw * 0.42f, body.bottom); lineTo(cw * 0.58f, body.bottom)
                    lineTo(cw * 0.64f, body.bottom + ch * 0.12f); lineTo(cw * 0.36f, body.bottom + ch * 0.12f); close()
                }
                canvas.drawPath(stand, paint)
                canvas.drawRoundRect(RectF(cw * 0.25f, body.bottom + ch * 0.11f, cw * 0.75f, body.bottom + ch * 0.14f), s * 0.01f, s * 0.01f, paint)
            }
            MockupKind.PHONE, MockupKind.PHONE_ROUND, MockupKind.PHONE_PRO, MockupKind.PHONE_ULTRA, MockupKind.PHONE_BAR -> {
                paint.color = darker(base, 0.7f); paint.alpha = alpha
                canvas.drawRoundRect(RectF(body.right - s * 0.004f, ch * 0.18f, body.right + s * 0.012f, ch * 0.3f), s * 0.006f, s * 0.006f, paint)
                canvas.drawRoundRect(RectF(body.left - s * 0.012f, ch * 0.16f, body.left + s * 0.004f, ch * 0.21f), s * 0.006f, s * 0.006f, paint)
                canvas.drawRoundRect(RectF(body.left - s * 0.012f, ch * 0.24f, body.left + s * 0.004f, ch * 0.33f), s * 0.006f, s * 0.006f, paint)
            }
            else -> {}
        }

        // body with a soft light from the top-left
        paint.color = base; paint.alpha = alpha
        paint.shader = LinearGradient(body.left, body.top, body.right, body.bottom, lighter(base, 0.25f), darker(base, 0.85f), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(body, bodyR, bodyR, paint)
        paint.shader = null
        // rim
        paint.style = Paint.Style.STROKE; paint.strokeWidth = s * 0.006f; paint.color = lighter(base, 0.35f); paint.alpha = alpha
        canvas.drawRoundRect(body, bodyR, bodyR, paint)
        paint.style = Paint.Style.FILL

        // screen
        canvas.save()
        val screen = RectF(0f, 0f, cw, ch)
        canvas.clipPath(Path().apply { addRoundRect(screen, screenR, screenR, Path.Direction.CW) })
        paint.color = Color.BLACK; paint.alpha = alpha
        canvas.drawRect(screen, paint)
        bmpPaint.alpha = alpha
        canvas.drawBitmap(content, Matrix().apply { setScale(cw / content.width, ch / content.height) }, bmpPaint)
        // glass reflection
        paint.shader = LinearGradient(0f, 0f, cw, ch, Color.argb(40, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, cw * 0.6f, ch, paint)
        paint.shader = null
        canvas.restore()

        // details on top
        when (kind) {
            MockupKind.PHONE, MockupKind.PHONE_PRO -> { // pill cut-out
                paint.color = Color.BLACK; paint.alpha = alpha
                canvas.drawRoundRect(RectF(cw * 0.36f, ch * 0.015f, cw * 0.64f, ch * 0.015f + s * 0.07f), s * 0.035f, s * 0.035f, paint)
            }
            MockupKind.PHONE_ULTRA, MockupKind.PHONE_BAR -> { // punch-hole camera
                paint.color = Color.BLACK; paint.alpha = alpha
                canvas.drawCircle(cw / 2f, ch * 0.022f + s * 0.02f, s * 0.022f, paint)
            }
            MockupKind.PHONE_ROUND -> {
                paint.color = darker(base, 0.5f); paint.alpha = alpha
                canvas.drawRoundRect(RectF(cw * 0.4f, -b.top * 0.55f, cw * 0.6f, -b.top * 0.45f), s * 0.01f, s * 0.01f, paint)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = s * 0.008f
                canvas.drawCircle(cw / 2f, ch + b.bottom / 2f, b.bottom * 0.3f, paint)
                paint.style = Paint.Style.FILL
            }
            MockupKind.TABLET -> { paint.color = darker(base, 0.4f); paint.alpha = alpha; canvas.drawCircle(cw / 2f, -b.top / 2f, s * 0.008f, paint) }
            MockupKind.LAPTOP -> {
                paint.color = darker(base, 0.4f); paint.alpha = alpha
                canvas.drawCircle(cw / 2f, -b.top / 2f, s * 0.006f, paint)
                // keyboard deck
                val deck = Path().apply {
                    moveTo(body.left - cw * 0.08f, body.bottom); lineTo(body.right + cw * 0.08f, body.bottom)
                    lineTo(body.right + cw * 0.1f, body.bottom + ch * 0.05f); lineTo(body.left - cw * 0.1f, body.bottom + ch * 0.05f); close()
                }
                paint.shader = LinearGradient(0f, body.bottom, 0f, body.bottom + ch * 0.05f, lighter(Color.GRAY, 0.5f), Color.GRAY, Shader.TileMode.CLAMP)
                paint.color = Color.LTGRAY; paint.alpha = alpha
                canvas.drawPath(deck, paint)
                paint.shader = null
                paint.color = darker(Color.GRAY, 0.6f); paint.alpha = alpha
                canvas.drawRoundRect(RectF(cw * 0.42f, body.bottom, cw * 0.58f, body.bottom + ch * 0.012f), s * 0.01f, s * 0.01f, paint)
            }
            MockupKind.BROWSER -> {
                val cy = -b.top / 2f
                for ((i, c) in listOf(0xFFFF5F57.toInt(), 0xFFFEBC2E.toInt(), 0xFF28C840.toInt()).withIndex()) {
                    paint.color = c; paint.alpha = alpha
                    canvas.drawCircle(s * 0.04f + i * s * 0.035f, cy, s * 0.012f, paint)
                }
                paint.color = lighter(base, 0.2f); paint.alpha = alpha
                canvas.drawRoundRect(RectF(cw * 0.2f, cy - b.top * 0.28f, cw * 0.8f, cy + b.top * 0.28f), s * 0.02f, s * 0.02f, paint)
            }
            else -> {}
        }
        canvas.restore()
    }
}

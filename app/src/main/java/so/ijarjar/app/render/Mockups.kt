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
            MockupKind.TABLET -> Pair(RectF(s * 0.05f, s * 0.05f, s * 0.05f, s * 0.05f), floatArrayOf(s * 0.07f, s * 0.03f))
            MockupKind.LAPTOP -> Pair(RectF(s * 0.035f, s * 0.045f, s * 0.035f, s * 0.035f), floatArrayOf(s * 0.04f, s * 0.006f))
            MockupKind.BROWSER -> Pair(RectF(s * 0.006f, s * 0.09f, s * 0.006f, s * 0.006f), floatArrayOf(s * 0.03f, s * 0.006f))
            MockupKind.WATCH -> Pair(RectF(s * 0.08f, s * 0.08f, s * 0.08f, s * 0.08f), floatArrayOf(s * 0.3f, s * 0.22f))
            MockupKind.TV -> Pair(RectF(s * 0.02f, s * 0.02f, s * 0.02f, s * 0.02f), floatArrayOf(s * 0.015f, s * 0.004f))
            MockupKind.POLAROID -> Pair(RectF(s * 0.07f, s * 0.07f, s * 0.07f, s * 0.28f), floatArrayOf(s * 0.015f, 0f))
            MockupKind.NONE -> Pair(RectF(), floatArrayOf(0f, 0f))
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
        if (pose.rx != 0f || pose.ry != 0f) {
            val steps = 10
            for (k in steps downTo 1) {
                val m = LayerRenderer.matrix(l, pose, canvasW, canvasH, k * 0.0016f)
                canvas.save(); canvas.concat(m)
                paint.shader = null; paint.style = Paint.Style.FILL
                paint.color = darker(base, 0.55f + 0.03f * (steps - k)); paint.alpha = alpha
                canvas.drawRoundRect(body, bodyR, bodyR, paint)
                canvas.restore()
            }
        }
        val m = LayerRenderer.matrix(l, pose, canvasW, canvasH)
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
            MockupKind.PHONE, MockupKind.PHONE_ROUND -> {
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
            MockupKind.PHONE -> { // dynamic island
                paint.color = Color.BLACK; paint.alpha = alpha
                canvas.drawRoundRect(RectF(cw * 0.36f, ch * 0.015f, cw * 0.64f, ch * 0.015f + s * 0.07f), s * 0.035f, s * 0.035f, paint)
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

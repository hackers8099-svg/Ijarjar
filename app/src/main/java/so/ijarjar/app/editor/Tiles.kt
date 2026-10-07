package so.ijarjar.app.editor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import so.ijarjar.app.model.Clip
import so.ijarjar.app.model.EffectKind
import so.ijarjar.app.model.Easing
import so.ijarjar.app.model.FilterPreset
import so.ijarjar.app.model.Adjust
import so.ijarjar.app.model.Layer
import so.ijarjar.app.model.LayerAnim
import so.ijarjar.app.model.LayerKind
import so.ijarjar.app.model.LoopAnim
import so.ijarjar.app.model.MediaKind
import so.ijarjar.app.model.Project
import so.ijarjar.app.model.TextAnim
import so.ijarjar.app.model.TextLoop
import so.ijarjar.app.model.TransitionKind
import so.ijarjar.app.render.Ease
import so.ijarjar.app.render.EffectRenderer
import so.ijarjar.app.render.Filters
import so.ijarjar.app.render.LayerRenderer
import so.ijarjar.app.render.Motion

/**
 * Small looping previews (like GIFs) shown in the pickers, so people see an animation, effect,
 * transition or filter before choosing it.
 */
@SuppressLint("ViewConstructor")
abstract class LoopTile(context: Context, private val periodMs: Long) : View(context) {
    var selectedTile = false
        set(v) { field = v; invalidate() }
    protected val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Ui.ACCENT }
    private val start = SystemClock.uptimeMillis()

    protected fun now(): Long = (SystemClock.uptimeMillis() - start) % periodMs

    override fun onDraw(canvas: Canvas) {
        val r = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val rad = width * 0.14f
        val clip = Path().apply { addRoundRect(r, rad, rad, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(clip)
        bg.color = 0xFF2A2A33.toInt()
        canvas.drawRect(r, bg)
        drawContent(canvas, now())
        canvas.restore()
        if (selectedTile) {
            border.strokeWidth = width * 0.05f
            val i = border.strokeWidth / 2
            canvas.drawRoundRect(RectF(i, i, width - i, height - i), rad, rad, border)
        }
        if (animated()) postInvalidateOnAnimation()
    }

    protected open fun animated() = true
    abstract fun drawContent(canvas: Canvas, t: Long)
}

/** Text / layer animation preview. */
class AnimTile(context: Context, sample: String, private val setup: (Layer) -> Unit) : LoopTile(context, 2600) {
    private val layer = Layer(kind = LayerKind.TEXT, text = sample, textSizeFrac = 0.26f, startMs = 0, endMs = 2000,
        animInMs = 900, animOutMs = 700, textColor = Color.WHITE).also(setup)

    override fun drawContent(canvas: Canvas, t: Long) {
        if (layer.isActive(t)) LayerRenderer.draw(context, canvas, layer, t, width, height, null, 256)
    }
}

/** Video effect preview on a picture of the current frame. */
class EffectTile(context: Context, private val thumb: Bitmap?, kind: EffectKind) : LoopTile(context, 3000) {
    private val project = Project(aspect = "1:1").also { p ->
        p.clips.add(Clip(kind = MediaKind.IMAGE, trimStartMs = 0, trimEndMs = 3000, width = 1, height = 1))
        p.layers.add(Layer(kind = LayerKind.EFFECT, effect = kind, startMs = 0, endMs = 3000))
    }
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fade = Paint()

    override fun drawContent(canvas: Canvas, t: Long) {
        val w = width.toFloat(); val h = height.toFloat()
        val m = Motion.clipMotion(project, 0, t)
        canvas.save()
        canvas.translate(w / 2 + m.tx * w, h / 2 + m.ty * h)
        canvas.rotate(m.rotation)
        canvas.scale(m.scale * m.sx, m.scale)
        canvas.translate(-w / 2, -h / 2)
        val cm = EffectRenderer.colorMatrix(project, t)
        paint.colorFilter = cm?.let { ColorMatrixColorFilter(it) }
        drawThumb(canvas, thumb, w, h, paint)
        canvas.restore()
        if (m.fadeAlpha > 0f) { fade.color = m.fadeColor; fade.alpha = (m.fadeAlpha * 255).toInt(); canvas.drawRect(0f, 0f, w, h, fade) }
        LayerRenderer.draw(context, canvas, project.layers[0], t, width, height, null, 256)
    }
}

/** Transition preview between two pictures. */
class TransitionTile(context: Context, private val a: Bitmap?, private val b: Bitmap?, kind: TransitionKind) : LoopTile(context, 2400) {
    private val project = Project(aspect = "1:1").also { p ->
        p.clips.add(Clip(kind = MediaKind.IMAGE, trimStartMs = 0, trimEndMs = 1200, width = 1, height = 1))
        p.clips.add(Clip(kind = MediaKind.IMAGE, trimStartMs = 0, trimEndMs = 1200, width = 1, height = 1, transition = kind, transitionMs = 900))
    }
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fade = Paint()

    override fun drawContent(canvas: Canvas, t: Long) {
        val w = width.toFloat(); val h = height.toFloat()
        val idx = if (t < 1200) 0 else 1
        val m = Motion.clipMotion(project, idx, if (idx == 0) t else t - 1200)
        canvas.save()
        canvas.translate(w / 2 + m.tx * w, h / 2 + m.ty * h)
        canvas.rotate(m.rotation)
        canvas.scale(m.scale * m.sx, m.scale)
        canvas.translate(-w / 2, -h / 2)
        drawThumb(canvas, if (idx == 0) a else b, w, h, paint, if (idx == 0) 0xFF3A86FF.toInt() else 0xFFFF006E.toInt())
        canvas.restore()
        if (m.fadeAlpha > 0f) { fade.color = m.fadeColor; fade.alpha = (m.fadeAlpha * 255).toInt(); canvas.drawRect(0f, 0f, w, h, fade) }
    }
}

/** A filter applied to the current frame. */
class FilterTile(context: Context, private val thumb: Bitmap?, preset: FilterPreset) : LoopTile(context, 1000) {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = Filters.colorFilter(Adjust(preset = preset))
    }
    override fun animated() = false
    override fun drawContent(canvas: Canvas, t: Long) = drawThumb(canvas, thumb, width.toFloat(), height.toFloat(), paint)
}

/** Draws a keyframe curve. */
class CurveView(context: Context) : View(context) {
    var easing: Easing = Easing.EASE_IN_OUT
        set(v) { field = v; invalidate() }
    var b = floatArrayOf(0.42f, 0f, 0.58f, 1f)
        set(v) { field = v; invalidate() }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF; strokeWidth = 2f }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.ACCENT; strokeWidth = 5f; style = Paint.Style.STROKE }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFCC00.toInt() }
    private val start = SystemClock.uptimeMillis()

    override fun onDraw(canvas: Canvas) {
        val pad = width * 0.08f
        val w = width - pad * 2; val h = height - pad * 2
        canvas.drawColor(0xFF2A2A33.toInt())
        canvas.drawLine(pad, pad + h, pad + w, pad + h, grid)
        canvas.drawLine(pad, pad, pad, pad + h, grid)
        val path = Path()
        for (i in 0..60) {
            val x = i / 60f
            val y = Ease.apply(easing, x, b[0], b[1], b[2], b[3])
            val px = pad + x * w; val py = pad + h - y * h * 0.8f - h * 0.1f
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        canvas.drawPath(path, line)
        // moving dot shows the speed of the curve
        val tt = ((SystemClock.uptimeMillis() - start) % 1600) / 1200f
        val x = tt.coerceAtMost(1f)
        val y = Ease.apply(easing, x, b[0], b[1], b[2], b[3])
        canvas.drawCircle(pad + w + pad * 0.5f - pad, pad + h - y * h * 0.8f - h * 0.1f, width * 0.03f, dot)
        canvas.drawCircle(pad + x * w, pad + h - y * h * 0.8f - h * 0.1f, width * 0.025f, dot)
        postInvalidateOnAnimation()
    }
}

private fun drawThumb(canvas: Canvas, b: Bitmap?, w: Float, h: Float, paint: Paint, fallback: Int = 0xFF3A86FF.toInt()) {
    if (b == null) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = fallback
        canvas.drawRect(0f, 0f, w, h, p)
        p.color = 0x55FFFFFF
        canvas.drawCircle(w * 0.35f, h * 0.4f, w * 0.15f, p)
        canvas.drawRect(0f, h * 0.7f, w, h, p)
        return
    }
    val s = maxOf(w / b.width, h / b.height)
    val m = Matrix()
    m.postScale(s, s)
    m.postTranslate((w - b.width * s) / 2f, (h - b.height * s) / 2f)
    canvas.drawBitmap(b, m, paint)
}

/** A tile + label column for horizontal pickers. */
fun tileWithLabel(context: Context, tile: View, label: String, sizeDp: Float = 64f, onClick: () -> Unit): View {
    val box = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(Ui.dp(context, 4f), 0, Ui.dp(context, 4f), 0)
        setOnClickListener { onClick() }
    }
    box.addView(tile, LinearLayout.LayoutParams(Ui.dp(context, sizeDp), Ui.dp(context, sizeDp)))
    box.addView(Ui.text(context, label, 11f, Ui.TEXT2).apply {
        gravity = Gravity.CENTER; maxLines = 1
        maxWidth = Ui.dp(context, sizeDp + 8f)
        setPadding(0, Ui.dp(context, 4f), 0, 0)
    })
    return box
}

@Suppress("unused")
private fun unusedRefs(a: LayerAnim, b: TextAnim, c: TextLoop, d: LoopAnim) = listOf(a, b, c, d)

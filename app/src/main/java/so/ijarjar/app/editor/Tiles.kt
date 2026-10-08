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

/** Animation preview on a copy of a real layer (shapes, stickers, pictures). */
class LayerAnimTile(context: Context, base: Layer, private val setup: (Layer) -> Unit) : LoopTile(context, 2600) {
    private val layer = base.copy().also { s ->
        s.keyframes = mutableListOf(); s.cx = 0.5f; s.cy = 0.5f; s.scale = 1f; s.rotation = 0f; s.opacity = 1f
        s.startMs = 0; s.endMs = 2000; s.animInMs = 900; s.animOutMs = 700
        s.animIn = so.ijarjar.app.model.LayerAnim.NONE; s.animOut = so.ijarjar.app.model.LayerAnim.NONE; s.animLoop = so.ijarjar.app.model.LoopAnim.NONE
        s.baseW = 0.42f
    }.also(setup)

    override fun drawContent(canvas: Canvas, t: Long) {
        if (layer.isActive(t)) LayerRenderer.draw(context, canvas, layer, t, width, height, null, 256)
    }
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

/** A filter applied to the current frame (or a sample photo), with its name on a strip like CapCut. */
class FilterTile(context: Context, thumb: Bitmap?, private val preset: FilterPreset) : LoopTile(context, 1000) {
    private val pic = thumb ?: FilterSample.get()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = Filters.colorFilter(Adjust(preset = preset))
    }
    private val strip = Paint()
    private val name = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    override fun animated() = false
    override fun drawContent(canvas: Canvas, t: Long) {
        val w = width.toFloat(); val h = height.toFloat()
        if (preset == FilterPreset.NONE) {
            bg.color = 0xFF34343E.toInt(); canvas.drawRect(0f, 0f, w, h, bg)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = w * 0.045f }
            val r = w * 0.17f; val cy = h * 0.42f
            canvas.drawCircle(w / 2, cy, r, p)
            canvas.drawLine(w / 2 - r * 0.7f, cy + r * 0.7f, w / 2 + r * 0.7f, cy - r * 0.7f, p)
        } else drawThumb(canvas, pic, w, h, paint)
        val sh = h * 0.26f
        strip.color = if (selectedTile) Ui.ACCENT else (GROUP_COLORS[preset.group % GROUP_COLORS.size] and 0x00FFFFFF) or 0xD0000000.toInt()
        canvas.drawRect(0f, h - sh, w, h, strip)
        name.textSize = sh * 0.52f
        var label = preset.label
        while (label.length > 3 && name.measureText(label) > w * 0.92f) label = label.dropLast(1)
        if (label != preset.label) label = label.dropLast(1) + "…"
        canvas.drawText(label, w / 2, h - sh / 2 + name.textSize * 0.36f, name)
    }
    companion object {
        private val GROUP_COLORS = intArrayOf(0xFF3A3A44.toInt(), 0xFF2E7D6B.toInt(), 0xFF8A5A2B.toInt(), 0xFF7A4A3A.toInt(),
            0xFF2B4F7A.toInt(), 0xFF444444.toInt(), 0xFF8A3A5E.toInt(), 0xFF3F6E2A.toInt(), 0xFF9A5A1A.toInt(), 0xFF4A2B7A.toInt())
    }
}

/** A colourful built-in sample picture (sky, sun, hills, water, a person) to show filters when there is no media yet. */
object FilterSample {
    private var person: Bitmap? = null
    private var food: Bitmap? = null
    /** Portrait photo (NASA, public domain) – shows how a filter looks on skin, like CapCut's model photos. */
    fun person(c: Context): Bitmap? = person ?: (custom(c) ?: runCatching { android.graphics.BitmapFactory.decodeResource(c.resources, so.ijarjar.app.R.drawable.filter_sample_person) }.getOrNull()).also { person = it }

    /** Your own sample photo for the filter tiles (saved once, used every time). */
    private fun custom(c: Context): Bitmap? {
        val u = c.getSharedPreferences("ijarjar", Context.MODE_PRIVATE).getString("filterSample", null) ?: return null
        return runCatching { so.ijarjar.app.media.MediaUtils.loadBitmap(c, android.net.Uri.parse(u), 480) }.getOrNull()
    }

    fun setCustom(c: Context, uri: String?) {
        c.getSharedPreferences("ijarjar", Context.MODE_PRIVATE).edit().apply { if (uri == null) remove("filterSample") else putString("filterSample", uri) }.apply()
        person = null
    }
    /** Food photo (CC0). */
    fun food(c: Context): Bitmap? = food ?: runCatching { android.graphics.BitmapFactory.decodeResource(c.resources, so.ijarjar.app.R.drawable.filter_sample_food) }.getOrNull().also { food = it }
    /** The right sample for a filter group: food, landscape or a person. */
    fun forGroup(c: Context, group: Int): Bitmap? = when (group) { 8 -> food(c); 7 -> get(); else -> person(c) }
    private var cached: Bitmap? = null
    fun get(): Bitmap {
        cached?.let { return it }
        val w = 240; val h = 320
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = android.graphics.LinearGradient(0f, 0f, 0f, h * 0.6f, intArrayOf(0xFF2F6FD6.toInt(), 0xFF7FB7F0.toInt(), 0xFFFFC98A.toInt()), null, android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p); p.shader = null
        p.shader = android.graphics.RadialGradient(w * 0.68f, h * 0.42f, w * 0.22f, intArrayOf(0xFFFFF4C2.toInt(), 0xFFFFB347.toInt(), 0x00FFB347), floatArrayOf(0f, 0.45f, 1f), android.graphics.Shader.TileMode.CLAMP)
        c.drawCircle(w * 0.68f, h * 0.42f, w * 0.22f, p); p.shader = null
        p.color = 0xFFFFFFFF.toInt(); p.alpha = 200
        c.drawOval(RectF(w * 0.08f, h * 0.12f, w * 0.42f, h * 0.19f), p); c.drawOval(RectF(w * 0.2f, h * 0.09f, w * 0.5f, h * 0.17f), p)
        p.alpha = 255
        fun hill(y: Float, amp: Float, col: Int) {
            val path = Path(); path.moveTo(0f, h.toFloat()); path.lineTo(0f, y)
            var x = 0f
            while (x <= w) { path.lineTo(x, y - amp * kotlin.math.sin(x / w * 6.3f + amp).toFloat() - amp * 0.5f * kotlin.math.sin(x / w * 13f).toFloat()); x += 6f }
            path.lineTo(w.toFloat(), h.toFloat()); path.close(); p.color = col; c.drawPath(path, p)
        }
        hill(h * 0.52f, 22f, 0xFF5C7A9E.toInt())
        hill(h * 0.6f, 14f, 0xFF3E8C4F.toInt())
        p.shader = android.graphics.LinearGradient(0f, h * 0.66f, 0f, h.toFloat(), intArrayOf(0xFF2A9DB8.toInt(), 0xFF0E4F73.toInt()), null, android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, h * 0.66f, w.toFloat(), h.toFloat(), p); p.shader = null
        p.color = 0x66FFE3A0; c.drawRect(w * 0.6f, h * 0.7f, w * 0.76f, h * 0.715f, p); c.drawRect(w * 0.63f, h * 0.75f, w * 0.73f, h * 0.762f, p)
        // a person in a red top on the shore (skin tones show portrait filters)
        p.color = 0xFFE8B48A.toInt(); c.drawCircle(w * 0.28f, h * 0.6f, w * 0.06f, p)
        p.color = 0xFF2B1B14.toInt(); c.drawArc(RectF(w * 0.22f, h * 0.555f, w * 0.34f, h * 0.635f), 180f, 180f, true, p)
        p.color = 0xFFE0384F.toInt(); c.drawRoundRect(RectF(w * 0.2f, h * 0.645f, w * 0.36f, h * 0.84f), 14f, 14f, p)
        p.color = 0xFFF2E3C6.toInt(); c.drawRect(0f, h * 0.86f, w.toFloat(), h.toFloat(), p)
        cached = b
        return b
    }
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
    /** Called when the user drags a handle (the curve becomes custom). */
    var onChange: ((FloatArray) -> Unit)? = null
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val handleLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x88FFFFFF.toInt(); strokeWidth = 3f }
    private var dragging = -1

    private fun px(x: Float): Float { val pad = width * 0.08f; return pad + x * (width - pad * 2) }
    private fun py(y: Float): Float { val pad = width * 0.08f; val h = height - pad * 2; return pad + h - y * h * 0.8f - h * 0.1f }
    private fun vx(p: Float): Float { val pad = width * 0.08f; return ((p - pad) / (width - pad * 2)).coerceIn(0f, 1f) }
    private fun vy(p: Float): Float { val pad = width * 0.08f; val h = height - pad * 2; return ((pad + h - h * 0.1f - p) / (h * 0.8f)).coerceIn(-1f, 2f) }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        if (onChange == null) return false
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val d1 = kotlin.math.hypot(e.x - px(b[0]), e.y - py(b[1]))
                val d2 = kotlin.math.hypot(e.x - px(b[2]), e.y - py(b[3]))
                dragging = if (d1 < d2) 0 else 1
            }
            android.view.MotionEvent.ACTION_MOVE -> if (dragging >= 0) {
                val nb = b.copyOf()
                nb[dragging * 2] = vx(e.x); nb[dragging * 2 + 1] = vy(e.y)
                easing = Easing.CUSTOM
                b = nb
                onChange?.invoke(nb)
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> dragging = -1
        }
        return true
    }

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
        if (easing == Easing.CUSTOM || onChange != null) {
            canvas.drawLine(px(0f), py(0f), px(b[0]), py(b[1]), handleLine)
            canvas.drawLine(px(1f), py(1f), px(b[2]), py(b[3]), handleLine)
            canvas.drawCircle(px(b[0]), py(b[1]), width * 0.025f, handle)
            canvas.drawCircle(px(b[2]), py(b[3]), width * 0.025f, handle)
        }
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
    Ui.press(box)
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

/** Preview of an animated title template (several linked layers). */
class TitleTile(context: Context, tpl: so.ijarjar.app.data.TitleTemplate) : LoopTile(context, 3200) {
    private val layers = tpl.build(0, 2600)
    override fun drawContent(canvas: Canvas, t: Long) {
        val p = Paint(); p.color = 0xFF3A3F55.toInt()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
        for (l in LayerRenderer.drawOrder(layers)) if (l.isActive(t)) LayerRenderer.draw(context, canvas, l, t, width, height, null, 256)
    }
}

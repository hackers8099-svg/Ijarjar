package so.ijarjar.app.editor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import so.ijarjar.app.model.Easing
import so.ijarjar.app.model.Keyframe
import so.ijarjar.app.model.Layer
import so.ijarjar.app.render.Ease
import so.ijarjar.app.render.LayerRenderer
import so.ijarjar.app.render.Pose
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * An endless value dial (like After Effects' scrubby numbers): drag left / right to change the value
 * with no end. It stops for a moment at 0 (and the default) with a small vibration, double-tap resets.
 */
@SuppressLint("ViewConstructor")
class ScrubDial(
    context: Context,
    private val label: String,
    initial: Float,
    private val def: Float,
    /** value change per dp dragged */
    private val perDp: Float,
    private val fmt: (Float) -> String = { v -> if (abs(v) >= 100) v.roundToInt().toString() else "%.1f".format(v) },
    private val min: Float = -Float.MAX_VALUE,
    private val max: Float = Float.MAX_VALUE,
    private val onChange: (Float) -> Unit
) : View(context) {

    var value = initial
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private val labelP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.TEXT2; textSize = 12f * d }
    private val valueP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.ACCENT; textSize = 15f * d; isFakeBoldText = true; textAlign = Paint.Align.RIGHT }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FFFFFF; strokeWidth = 1.2f * d }
    private val tickBig = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xAAFFFFFF.toInt(); strokeWidth = 1.6f * d }
    private val zeroP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.ACCENT; strokeWidth = 2.5f * d }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.SURFACE2 }
    private val resetP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.TEXT2; textSize = 15f * d; textAlign = Paint.Align.CENTER }
    private var lastX = 0f
    private var downX = 0f; private var downY = 0f
    private var mine = false      // decided: this drag changes the value (horizontal), not a page scroll
    private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private var held = 0f        // drag distance spent waiting at a snap point
    private var resetRect = RectF()

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean { reset(); return true }
        override fun onDown(e: MotionEvent) = true
    })

    fun reset() {
        value = def; onChange(def)
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (52 * d).toInt())
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN && resetRect.contains(e.x, e.y)) { reset(); return true }
        gestures.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = e.x; downX = e.x; downY = e.y; held = 0f; mine = false }
            MotionEvent.ACTION_MOVE -> {
                if (!mine) {
                    val ax = abs(e.x - downX); val ay = abs(e.y - downY)
                    if (ax < slop && ay < slop) return true
                    // up / down = scroll the list; left / right = change the value
                    if (ay > ax) return false
                    mine = true; lastX = e.x
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                val dx = (e.x - lastX) / d
                lastX = e.x
                val slow = if (e.pointerCount > 1) 0.2f else 1f
                var nv = value + dx * perDp * slow
                // stick at 0 / default for a moment
                for (snap in listOf(0f, def)) {
                    if ((value < snap && nv >= snap) || (value > snap && nv <= snap)) {
                        nv = snap; held = 0f
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                }
                if ((value == 0f || value == def) && held < 14f) { held += abs(dx); if (held < 14f) nv = value }
                nv = nv.coerceIn(min, max)
                if (nv != value) { value = nv; onChange(nv) }
            }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        c.drawRoundRect(RectF(0f, 4 * d, w, h - 4 * d), 12 * d, 12 * d, bg)
        c.drawText(label, 12 * d, 22 * d, labelP)
        // reset button
        val rs = 30 * d
        resetRect.set(w - rs - 4 * d, (h - rs) / 2, w - 4 * d, (h + rs) / 2)
        c.drawText("↺", resetRect.centerX(), resetRect.centerY() + 5 * d, resetP)
        c.drawText(fmt(value), w - rs - 10 * d, 23 * d, valueP)
        // ruler: moves with the value so it feels endless
        val left = 12 * d; val right = w - rs - 10 * d
        val y = h - 14 * d
        val spacing = 8 * d
        val offset = ((value / perDp) * d) % spacing
        var x = left - offset
        var i = 0
        while (x < right) {
            if (x >= left) c.drawLine(x, y, x, y + (if (i % 5 == 0) 7 else 4) * d, if (i % 5 == 0) tickBig else tick)
            x += spacing; i++
        }
        // centre marker (the current value)
        val cx = (left + right) / 2
        c.drawLine(cx, y - 3 * d, cx, y + 9 * d, zeroP)
    }
}

/**
 * Graph editor (like After Effects' value graph): the curve of one property over time, the
 * keyframes on it, and two bezier handles for the selected keyframe's curve to the next one.
 */
@SuppressLint("ViewConstructor")
/**
 * Keyframe icons like After Effects: each half shows how the value moves on that side —
 * ◆ half = linear, ⧓ half (wide outside, point in the middle) = eased, ■ half = hold.
 */
object KeyIcon {
    const val LINEAR = 0; const val EASED = 1; const val HOLD = 2

    /** Bezier handles (x1, y1, x2, y2) of the segment that starts at [k]. */
    fun bez(k: Keyframe): FloatArray = when (k.ease) {
        Easing.CUSTOM -> floatArrayOf(k.bx1, k.by1, k.bx2, k.by2)
        Easing.LINEAR -> floatArrayOf(0.333f, 0.333f, 0.667f, 0.667f)
        Easing.EASE_IN -> floatArrayOf(0.333f, 0f, 1f, 1f)
        Easing.EASE_OUT -> floatArrayOf(0f, 0f, 0.667f, 1f)
        Easing.EASE_IN_OUT -> floatArrayOf(0.333f, 0f, 0.667f, 1f)
        else -> floatArrayOf(0.333f, 0.333f, 0.667f, 0.667f)
    }

    /** (incoming side, outgoing side) of [k] inside [l]. */
    fun kinds(l: Layer, k: Keyframe): Pair<Int, Int> {
        val ks = l.keyframes.sortedBy { it.t }
        val i = ks.indexOf(k)
        val prev = ks.getOrNull(i - 1)
        val inK = when {
            prev == null -> LINEAR
            prev.ease == Easing.HOLD -> HOLD
            prev.ease in listOf(Easing.BACK, Easing.BOUNCE, Easing.ELASTIC) -> LINEAR
            else -> bez(prev).let { if (it[3] >= 0.98f && it[2] < 0.97f) EASED else LINEAR }
        }
        val outK = when (k.ease) {
            Easing.HOLD -> HOLD
            Easing.BACK, Easing.BOUNCE, Easing.ELASTIC -> LINEAR
            else -> bez(k).let { if (it[1] <= 0.02f && it[0] > 0.03f) EASED else LINEAR }
        }
        return Pair(inK, outK)
    }

    private val path = Path()

    fun draw(c: Canvas, x: Float, y: Float, r: Float, inK: Int, outK: Int, fill: Paint, outline: Paint? = null) {
        path.reset()
        // left half
        when (inK) {
            EASED -> { path.moveTo(x, y); path.lineTo(x - r * 0.85f, y - r); path.lineTo(x - r * 0.85f, y + r); path.close() }
            HOLD -> path.addRect(x - r * 0.8f, y - r * 0.8f, x, y + r * 0.8f, Path.Direction.CW)
            else -> { path.moveTo(x, y - r); path.lineTo(x - r, y); path.lineTo(x, y + r); path.close() }
        }
        when (outK) {
            EASED -> { path.moveTo(x, y); path.lineTo(x + r * 0.85f, y - r); path.lineTo(x + r * 0.85f, y + r); path.close() }
            HOLD -> path.addRect(x, y - r * 0.8f, x + r * 0.8f, y + r * 0.8f, Path.Direction.CW)
            else -> { path.moveTo(x, y - r); path.lineTo(x + r, y); path.lineTo(x, y + r); path.close() }
        }
        if (outline != null) c.drawPath(path, outline)
        c.drawPath(path, fill)
    }

    fun draw(c: Canvas, l: Layer, k: Keyframe, x: Float, y: Float, r: Float, fill: Paint, outline: Paint? = null) {
        val (a, b) = kinds(l, k); draw(c, x, y, r, a, b, fill, outline)
    }
}

/** A small button with an AE keyframe icon and a name (Linear, Easy Ease, Ease In, Ease Out, Hold). */
@SuppressLint("ViewConstructor")
class EaseButton(context: Context, private val inK: Int, private val outK: Int, private val label: String, private val compact: Boolean = false) : View(context) {
    private val d = resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.SURFACE2 }
    private val ic = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFCC00.toInt() }
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.TEXT; textSize = 11f * d; textAlign = Paint.Align.CENTER }
    override fun onMeasure(w: Int, h: Int) = if (compact) setMeasuredDimension((66 * d).toInt(), (46 * d).toInt()) else setMeasuredDimension((74 * d).toInt(), (58 * d).toInt())
    override fun onDraw(c: Canvas) {
        c.drawRoundRect(RectF(2 * d, 2 * d, width - 2 * d, height - 2 * d), 12 * d, 12 * d, bg)
        KeyIcon.draw(c, width / 2f, (if (compact) 17 else 22) * d, (if (compact) 8 else 10) * d, inK, outK, ic)
        if (compact) tp.textSize = 10f * d
        c.drawText(label, width / 2f, height - (if (compact) 7 else 10) * d, tp)
    }
}

class GraphView(context: Context, private val layer: Layer, private val onEdit: () -> Unit, private val onSeek: (Long) -> Unit) : View(context) {

    /** 0 X, 1 Y, 2 scale, 3 rotation, 4 opacity, 5 rotate X, 6 rotate Y, 7 Z, 8 width, 9 height */
    var prop = 0
        set(v) { field = v; invalidate() }
    var selected: Keyframe? = null
        set(v) { field = v; invalidate() }
    var playheadMs: Long = 0
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22FFFFFF; strokeWidth = 1f * d }
    private val curveP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.ACCENT; strokeWidth = 2.5f * d; style = Paint.Style.STROKE }
    private val exprP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99FFCC00.toInt(); strokeWidth = 1.5f * d; style = Paint.Style.STROKE; pathEffect = DashPathEffect(floatArrayOf(6 * d, 4 * d), 0f) }
    private val keyP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFCC00.toInt() }
    private val keySel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val handleLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xAAFFFFFF.toInt(); strokeWidth = 1.5f * d }
    private val handleP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val playP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF3D7F.toInt(); strokeWidth = 1.5f * d }
    private val textP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.TEXT2; textSize = 10f * d }
    private val bg = Paint().apply { color = 0xFF202027.toInt() }
    private val rulerP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x88FFFFFF.toInt(); strokeWidth = 1f * d }
    private val bubbleText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 10f * d; isFakeBoldText = true }

    private var vMin = 0f; private var vMax = 1f
    /** Visible time range (zoom): ms inside the layer. */
    private var viewStart = 0L
    private var viewEnd = -1L
    private fun vEnd() = if (viewEnd <= viewStart) layer.durationMs.coerceAtLeast(1) else viewEnd
    private var pinchSpan = 0f

    /** Zoom in (> 1) or out (< 1) around a time. */
    fun zoom(f: Float, aroundMs: Long = (viewStart + vEnd()) / 2) {
        val dur = layer.durationMs.coerceAtLeast(1)
        val span = ((vEnd() - viewStart) / f).toLong().coerceIn(200L.coerceAtMost(dur), dur)
        val frac = ((aroundMs - viewStart).toFloat() / (vEnd() - viewStart).coerceAtLeast(1)).coerceIn(0f, 1f)
        var s0 = aroundMs - (span * frac).toLong()
        s0 = s0.coerceIn(0, dur - span)
        viewStart = s0; viewEnd = s0 + span
        invalidate()
    }
    fun fit() { viewStart = 0; viewEnd = -1; invalidate() }
    private var dragging = -1   // 0 = out handle, 1 = in handle, 2 = scrub, 3 = pinch
    private var lastMid = 0f
    private var gx = 0f; private var gy = 0f; private var decided = false
    private var keyStartT = 0L; private var moved = false
    private var panStart = 0L
    private val gslop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    private fun v(p: Pose): Float = when (prop) {
        0 -> p.cx * 100; 1 -> p.cy * 100; 2 -> p.scale * 100; 3 -> p.rotation; 4 -> p.opacity * 100
        5 -> p.rx; 6 -> p.ry; 7 -> p.z * 1000; 8 -> p.sx * 100; else -> p.sy * 100
    }
    private fun kv(k: Keyframe): Float = when (prop) {
        0 -> k.cx * 100; 1 -> k.cy * 100; 2 -> k.scale * 100; 3 -> k.rotation; 4 -> k.opacity * 100
        5 -> k.rx; 6 -> k.ry; 7 -> k.z * 1000; 8 -> k.sx * 100; else -> k.sy * 100
    }

    private val padL get() = 34 * d
    private val padR get() = 12 * d
    private val padT get() = 24 * d
    private val padB get() = 36 * d
    // overview strip at the bottom: the whole layer, all keyframes, and the part you see (drag it)
    private val navTop get() = height - 15 * d
    private val navBot get() = height - 3 * d
    private fun navX(t: Long) = padL + (width - padL - padR) * (t.toFloat() / layer.durationMs.coerceAtLeast(1))
    private fun navT(x: Float) = ((x - padL) / (width - padL - padR) * layer.durationMs).toLong()
    private fun zoomed() = vEnd() - viewStart < layer.durationMs - 1
    private fun panTo(startMs: Long) {
        val span = vEnd() - viewStart
        viewStart = startMs.coerceIn(0, (layer.durationMs - span).coerceAtLeast(0)); viewEnd = viewStart + span; invalidate()
    }
    private val navBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2C2C35.toInt() }
    private val navWin = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x5519D3C5; }
    private val navWinEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.ACCENT; style = Paint.Style.STROKE; strokeWidth = 1.5f * d }
    private val keyOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC000000.toInt(); style = Paint.Style.STROKE; strokeWidth = 2.5f * d; strokeJoin = Paint.Join.ROUND }
    private fun tx(t: Long) = padL + (width - padL - padR) * ((t - viewStart).toFloat() / (vEnd() - viewStart).coerceAtLeast(1))
    private fun ty(v: Float) = padT + (height - padT - padB) * (1f - (v - vMin) / (vMax - vMin).coerceAtLeast(1e-4f))
    private fun tOf(x: Float) = (viewStart + (x - padL) / (width - padL - padR) * (vEnd() - viewStart)).toLong().coerceIn(0, layer.durationMs)
    private fun vOf(y: Float) = vMin + (1f - (y - padT) / (height - padT - padB)) * (vMax - vMin)

    private fun sorted() = layer.keyframes.sortedBy { it.t }
    private fun nextOf(k: Keyframe): Keyframe? = sorted().firstOrNull { it.t > k.t }

    /** Graph positions of the two handles of the selected keyframe's segment. */
    private fun handles(): Pair<FloatArray, FloatArray>? {
        val a = selected ?: return null
        val b = nextOf(a) ?: return null
        val va = kv(a); var dv = kv(b) - va
        if (abs(dv) < (vMax - vMin) * 0.05f) dv = (vMax - vMin) * 0.3f
        val dt = (b.t - a.t).toFloat()
        val (x1, y1, x2, y2) = curvePts(a)
        return Pair(floatArrayOf(tx(a.t + (x1 * dt).toLong()), ty(va + y1 * dv)), floatArrayOf(tx(a.t + (x2 * dt).toLong()), ty(va + y2 * dv)))
    }

    private fun curvePts(a: Keyframe): FloatArray = when (a.ease) {
        Easing.CUSTOM -> floatArrayOf(a.bx1, a.by1, a.bx2, a.by2)
        Easing.LINEAR -> floatArrayOf(0.333f, 0.333f, 0.667f, 0.667f)
        Easing.EASE_IN -> floatArrayOf(0.333f, 0f, 1f, 1f)        // starts slow
        Easing.EASE_OUT -> floatArrayOf(0f, 0f, 0.667f, 1f)       // ends slow
        else -> floatArrayOf(0.333f, 0f, 0.667f, 1f)
    }

    private fun computeRange() {
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        val n = 80
        for (i in 0..n) {
            val t = layer.startMs + viewStart + (vEnd() - viewStart) * i / n
            val x = v(LayerRenderer.basePose(layer, t))
            lo = min(lo, x); hi = max(hi, x)
        }
        if (hi - lo < 1f) { lo -= 5f; hi += 5f }
        val m = (hi - lo) * 0.25f
        vMin = lo - m; vMax = hi + m
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        // two fingers: pinch = zoom time, move = pan
        if (e.pointerCount >= 2) {
            val sp = abs(e.getX(0) - e.getX(1)).coerceAtLeast(1f)
            val mid = (e.getX(0) + e.getX(1)) / 2
            when (e.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> { pinchSpan = sp; dragging = 3; lastMid = mid; parent?.requestDisallowInterceptTouchEvent(true) }
                MotionEvent.ACTION_MOVE -> if (dragging == 3 && pinchSpan > 0f) {
                    zoom(sp / pinchSpan, tOf(mid)); pinchSpan = sp
                    val dt = ((lastMid - mid) / (width - padL - padR) * (vEnd() - viewStart)).toLong()
                    val dur = layer.durationMs; val span = vEnd() - viewStart
                    viewStart = (viewStart + dt).coerceIn(0, (dur - span).coerceAtLeast(0)); viewEnd = viewStart + span
                    lastMid = mid; invalidate()
                }
            }
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gx = e.x; gy = e.y; decided = false
                panStart = viewStart
                // overview strip: drag the window to see other keyframes
                if (e.y > navTop - 6 * d) {
                    dragging = 5; decided = true; parent?.requestDisallowInterceptTouchEvent(true)
                    panTo(navT(e.x) - (vEnd() - viewStart) / 2); return true
                }
                // ruler on top, or the playhead line itself: move the playhead
                val px = tx(playheadMs - layer.startMs)
                if (e.y < padT || abs(e.x - px) < 16 * d) {
                    dragging = 2; decided = true; parent?.requestDisallowInterceptTouchEvent(true)
                    onSeek(layer.startMs + tOf(e.x)); return true
                }
                dragging = 6
                handles()?.let { (h1, h2) ->
                    if (hypot(e.x - h1[0], e.y - h1[1]) < 22 * d) dragging = 0
                    else if (hypot(e.x - h2[0], e.y - h2[1]) < 22 * d) dragging = 1
                }
                if (dragging == 6) {
                    val hit = layer.keyframes.minByOrNull { hypot(tx(it.t) - e.x, ty(kv(it)) - e.y) }
                    if (hit != null && hypot(tx(hit.t) - e.x, ty(kv(hit)) - e.y) < 20 * d) { selected = hit; dragging = 4; keyStartT = hit.t }
                }
                // handles and keyframes grab the finger at once; elsewhere we wait to see if it is a scroll
                if (dragging != 6) { parent?.requestDisallowInterceptTouchEvent(true); decided = true }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!decided) {
                    val ax = abs(e.x - gx); val ay = abs(e.y - gy)
                    if (ax < gslop && ay < gslop) return true
                    if (ay > ax) { dragging = -1; return false }      // up / down = scroll the panel
                    decided = true; parent?.requestDisallowInterceptTouchEvent(true)
                }
                val a = selected
                if (dragging == 3) return true
                if (dragging == 5) { panTo(navT(e.x) - (vEnd() - viewStart) / 2); return true }
                // empty graph: slide the view when zoomed in, otherwise move the playhead
                if (dragging == 6) dragging = if (zoomed()) 7 else 2
                if (dragging == 7) {
                    val dt = ((gx - e.x) / (width - padL - padR) * (vEnd() - viewStart)).toLong()
                    panTo(panStart + dt); return true
                }
                if (dragging == 4 && a != null) {
                    val ks = layer.keyframes.sortedBy { it.t }
                    val i = ks.indexOf(a)
                    val lo = (ks.getOrNull(i - 1)?.t ?: -1L) + 20
                    val hi = (ks.getOrNull(i + 1)?.t ?: (layer.durationMs + 1)) - 20
                    a.t = tOf(e.x).coerceIn(lo.coerceAtLeast(0), hi.coerceAtMost(layer.durationMs))
                    moved = true
                    onEdit(); onSeek(layer.startMs + a.t); invalidate()
                    return true
                }
                if (dragging == 2) onSeek(layer.startMs + tOf(e.x))
                else if (a != null && (dragging == 0 || dragging == 1)) {
                    val b = nextOf(a) ?: return true
                    val pts = curvePts(a)
                    val va = kv(a); var dv = kv(b) - va
                    if (abs(dv) < (vMax - vMin) * 0.05f) dv = (vMax - vMin) * 0.3f
                    val fx = ((tOf(e.x) - a.t).toFloat() / (b.t - a.t).coerceAtLeast(1)).coerceIn(0f, 1f)
                    val fy = ((vOf(e.y) - va) / dv).coerceIn(-1.5f, 2.5f)
                    if (dragging == 0) { pts[0] = fx; pts[1] = fy } else { pts[2] = fx; pts[3] = fy }
                    a.ease = Easing.CUSTOM; a.bx1 = pts[0]; a.by1 = pts[1]; a.bx2 = pts[2]; a.by2 = pts[3]
                    onEdit(); invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (dragging == 4 && !moved) selected?.let { onSeek(layer.startMs + it.t) }
                moved = false
                if (!decided && (dragging == 2 || dragging == 6)) {
                    // a tap: pick a keyframe or move the playhead
                    val hit = layer.keyframes.minByOrNull { abs(tx(it.t) - e.x) }
                    if (hit != null && abs(tx(hit.t) - e.x) < 18 * d) { selected = hit; onSeek(layer.startMs + hit.t) } else onSeek(layer.startMs + tOf(e.x))
                }
                dragging = -1
            }
            MotionEvent.ACTION_CANCEL -> dragging = -1
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        computeRange()
        c.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), 12 * d, 12 * d, bg)
        // grid + labels
        for (i in 0..4) {
            val y = padT + (height - padT - padB) * i / 4f
            c.drawLine(padL, y, width - padR, y, grid)
            val v = vMax - (vMax - vMin) * i / 4f
            c.drawText(if (abs(v) >= 100) v.roundToInt().toString() else "%.1f".format(v), 4 * d, y + 4 * d, textP)
        }
        // time labels at least ~64 dp apart, whatever the zoom
        val spanS = (vEnd() - viewStart) / 1000f
        val usable = (width - padL - padR).coerceAtLeast(1f)
        val steps = floatArrayOf(0.05f, 0.1f, 0.25f, 0.5f, 1f, 2f, 5f, 10f, 15f, 30f, 60f, 120f)
        val stepS = steps.firstOrNull { usable * it / spanS >= 64 * d } ?: 300f
        var s = (kotlin.math.floor(viewStart / 1000f / stepS) * stepS)
        c.save(); c.clipRect(padL, 0f, width - padR, height.toFloat())
        while (s <= vEnd() / 1000f + stepS) {
            val x = tx((s * 1000).toLong())
            c.drawLine(x, padT, x, height - padB, grid)
            val lab = if (stepS >= 1f) { val m = (s / 60).toInt(); val sec = (s % 60).toInt(); if (m > 0) "%d:%02d".format(m, sec) else "${sec}s" } else "%.2fs".format(s)
            c.drawText(lab, x + 2 * d, height - 4 * d, textP)
            // ruler on top: big tick + small ticks (like the AE time ruler)
            c.drawLine(x, padT - 10 * d, x, padT, rulerP)
            for (k in 1 until 5) { val xs = tx(((s + stepS * k / 5f) * 1000).toLong()); c.drawLine(xs, padT - 5 * d, xs, padT, rulerP) }
            s += stepS
        }
        // keyframed curve and (dashed) result with expressions
        val path = Path(); val ex = Path()
        val n = 140
        for (i in 0..n) {
            val t = layer.startMs + viewStart + (vEnd() - viewStart) * i / n
            val x = tx(t - layer.startMs)
            val y = ty(v(LayerRenderer.basePose(layer, t)))
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            if (layer.exprCode.isNotEmpty() || layer.expr != so.ijarjar.app.model.Expression.NONE) {
                val ey = ty(v(LayerRenderer.poseAt(layer, t)))
                if (i == 0) ex.moveTo(x, ey) else ex.lineTo(x, ey)
            }
        }
        c.drawPath(ex, exprP)
        c.drawPath(path, curveP)
        // keyframes
        for (k in layer.keyframes) {
            val x = tx(k.t); val y = ty(kv(k))
            KeyIcon.draw(c, layer, k, x, y, 7 * d, if (k === selected) keySel else keyP, keyOutline)
        }
        // handles of the selected segment
        handles()?.let { (h1, h2) ->
            val a = selected!!; val b = nextOf(a)!!
            c.drawLine(tx(a.t), ty(kv(a)), h1[0], h1[1], handleLine)
            c.drawLine(tx(b.t), ty(kv(b)), h2[0], h2[1], handleLine)
            c.drawCircle(h1[0], h1[1], 6 * d, handleP)
            c.drawCircle(h2[0], h2[1], 6 * d, handleP)
        }
        val px = tx(playheadMs - layer.startMs)
        c.drawLine(px, padT - 10 * d, px, height - padB, playP)
        c.restore()
        // where you are: time bubble on the playhead
        val local = (playheadMs - layer.startMs).coerceAtLeast(0)
        val lab = "%d:%02d.%02d".format(local / 60000, (local / 1000) % 60, (local % 1000) / 10)
        val tw = bubbleText.measureText(lab) + 12 * d
        val bx = (px - tw / 2).coerceIn(padL, width - padR - tw)
        c.drawRoundRect(RectF(bx, 2 * d, bx + tw, padT - 10 * d), 5 * d, 5 * d, playP)
        c.drawText(lab, bx + 6 * d, padT - 14 * d, bubbleText)
        // overview strip
        val nr = RectF(padL, navTop, width - padR, navBot)
        c.drawRoundRect(nr, 5 * d, 5 * d, navBg)
        val wr = RectF(navX(viewStart), navTop, navX(vEnd()).coerceAtLeast(navX(viewStart) + 6 * d), navBot)
        c.drawRoundRect(wr, 5 * d, 5 * d, navWin); c.drawRoundRect(wr, 5 * d, 5 * d, navWinEdge)
        for (k in layer.keyframes) KeyIcon.draw(c, layer, k, navX(k.t), (navTop + navBot) / 2, 4.5f * d, if (k === selected) keySel else keyP)
        val npx = navX((playheadMs - layer.startMs).coerceIn(0, layer.durationMs))
        c.drawLine(npx, navTop - 2 * d, npx, navBot + 2 * d, playP)
    }
}

/**
 * A slim time bar inside panels: drag it to move the playhead without closing the panel.
 * Shows the whole video, the selected layer's span and its keyframes (◆).
 */
@SuppressLint("ViewConstructor")
class MiniTimeline(context: Context, private val duration: () -> Long, private val layer: () -> Layer?,
                   private val time: () -> Long, private val onSeek: (Long) -> Unit) : View(context) {
    private val d = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2C2C35.toInt() }
    private val span = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x5519D3C5 }
    private val key = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFCC00.toInt() }
    private val head = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 2.5f * d }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.TEXT2; textSize = 10f * d }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (40 * d).toInt())

    private val l get() = 46 * d
    private val r get() = width - 10 * d
    // visible part (zoom): starts on the selected layer so its keyframes are easy to see
    private var vs = -1L
    private var ve = -1L
    private var lastLayerId: String? = null
    private fun range(): Pair<Long, Long> {
        val dur = duration().coerceAtLeast(1)
        val ly = layer()
        if (ly?.id != lastLayerId) {
            lastLayerId = ly?.id
            if (ly != null && ly.durationMs < dur * 0.6) {
                val pad = (ly.durationMs * 0.15).toLong().coerceAtLeast(300)
                vs = (ly.startMs - pad).coerceAtLeast(0); ve = (ly.endMs + pad).coerceAtMost(dur)
            } else { vs = 0; ve = dur }
        }
        if (vs < 0 || ve <= vs) { vs = 0; ve = dur }
        ve = ve.coerceAtMost(dur); if (ve - vs < 300) ve = (vs + 300).coerceAtMost(dur)
        return Pair(vs, ve)
    }
    private fun x(t: Long): Float { val (a, b) = range(); return l + (r - l) * ((t - a).toFloat() / (b - a).coerceAtLeast(1)) }
    private fun t(x: Float): Long { val (a, b) = range(); return (a + ((x - l) / (r - l)).coerceIn(0f, 1f) * (b - a)).toLong() }

    private var dragKey: so.ijarjar.app.model.Keyframe? = null
    var onKeyMoved: (() -> Unit)? = null
    private var pinch = 0f
    private var pinchMid = 0f
    private val keyOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC000000.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f * d; strokeJoin = Paint.Join.ROUND }
    private val gest = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean { vs = 0; ve = duration(); invalidate(); return true }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        gest.onTouchEvent(e)
        // two fingers: pinch = zoom, move = slide
        if (e.pointerCount >= 2) {
            val sp = abs(e.getX(0) - e.getX(1)).coerceAtLeast(1f)
            val mid = (e.getX(0) + e.getX(1)) / 2
            when (e.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> { pinch = sp; pinchMid = mid; dragKey = null }
                MotionEvent.ACTION_MOVE -> if (pinch > 0f) {
                    val (a, b) = range(); val dur = duration().coerceAtLeast(1)
                    val focus = t(mid)
                    val span = ((b - a) * pinch / sp).toLong().coerceIn(300L.coerceAtMost(dur), dur)
                    val frac = ((focus - a).toFloat() / (b - a).coerceAtLeast(1))
                    var ns = focus - (span * frac).toLong()
                    ns -= ((mid - pinchMid) / (r - l) * span).toLong()
                    ns = ns.coerceIn(0, dur - span)
                    vs = ns; ve = ns + span; pinch = sp; pinchMid = mid; invalidate()
                }
            }
            return true
        }
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(true)
            val ly = layer()
            dragKey = ly?.keyframes?.minByOrNull { abs(x(ly.startMs + it.t) - e.x) }?.takeIf { abs(x(ly.startMs + it.t) - e.x) < 11 * d && abs(e.y - height / 2f) < 16 * d }
        }
        if (e.actionMasked == MotionEvent.ACTION_POINTER_UP) return true
        val dk = dragKey
        val ly = layer()
        if (dk != null && ly != null && e.actionMasked == MotionEvent.ACTION_MOVE) {
            val ks = ly.keyframes.sortedBy { it.t }; val i = ks.indexOf(dk)
            val lo = (ks.getOrNull(i - 1)?.t ?: -1L) + 20; val hi = (ks.getOrNull(i + 1)?.t ?: (ly.durationMs + 1)) - 20
            dk.t = (t(e.x) - ly.startMs).coerceIn(lo.coerceAtLeast(0), hi.coerceAtMost(ly.durationMs))
            onSeek(ly.startMs + dk.t); onKeyMoved?.invoke(); invalidate()
            return true
        }
        if (e.actionMasked == MotionEvent.ACTION_UP) dragKey = null
        if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_MOVE) {
            var tt = t(e.x)
            // at the edges the view slides along, so you can scrub past what you see
            val (a, b) = range(); val dur = duration()
            if (e.x > r - 6 * d && b < dur) { val st = ((b - a) / 40).coerceAtLeast(16); vs = (a + st).coerceAtMost(dur - (b - a)); ve = vs + (b - a); tt = ve }
            else if (e.x < l + 6 * d && a > 0) { val st = ((b - a) / 40).coerceAtLeast(16); vs = (a - st).coerceAtLeast(0); ve = vs + (b - a); tt = vs }
            // snap to a keyframe when close
            layer()?.let { ly -> ly.keyframes.minByOrNull { abs(x(ly.startMs + it.t) - e.x) }?.let { k -> if (abs(x(ly.startMs + k.t) - e.x) < 10 * d) tt = ly.startMs + k.t } }
            onSeek(tt); invalidate()
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        val cy = height / 2f
        val now = time()
        // keep the playhead in view while playing
        val (a0, b0) = range()
        if (now < a0 || now > b0) { val span = b0 - a0; vs = (now - span / 4).coerceIn(0, (duration() - span).coerceAtLeast(0)); ve = vs + span }
        val (a, b) = range()
        c.save(); c.clipRect(l - 8 * d, 0f, r + 8 * d, height.toFloat())
        c.drawRoundRect(RectF(l, cy - 7 * d, r, cy + 7 * d), 7 * d, 7 * d, track)
        layer()?.let { ly ->
            c.drawRoundRect(RectF(x(ly.startMs).coerceAtLeast(l), cy - 7 * d, x(ly.endMs).coerceAtMost(r), cy + 7 * d), 7 * d, 7 * d, span)
            for (k in ly.keyframes) {
                val kx = x(ly.startMs + k.t)
                if (kx < l - 6 * d || kx > r + 6 * d) continue
                KeyIcon.draw(c, ly, k, kx, cy, 7 * d, key, keyOutline)
            }
        }
        val hx = x(now)
        c.drawLine(hx, cy - 13 * d, hx, cy + 13 * d, head)
        c.restore()
        c.drawText("%d:%02d.%d".format(now / 60000, (now / 1000) % 60, (now % 1000) / 100), 4 * d, cy + 4 * d, txt)
        // zoomed in: small marks show there is more on either side
        if (a > 0) c.drawText("‹", l - 7 * d, cy + 4 * d, txt)
        if (b < duration()) c.drawText("›", r + 2 * d, cy + 4 * d, txt)
    }
}

/**
 * After Effects style keyframe sheet: one row per property (Position, Scale, Rotate, Opacity, 3D)
 * with its keyframes, a time ruler and the playhead. Drag ◆ = move it in time, tap = pick it,
 * drag the ruler or the line = move the playhead, two fingers = zoom / slide.
 */
@SuppressLint("ViewConstructor")
class DopeSheetView(context: Context, private val layer: Layer, private val time: () -> Long,
                    private val onSeek: (Long) -> Unit, private val onEdit: () -> Unit) : View(context) {
    private val d = resources.displayMetrics.density
    var selected: Keyframe? = null
        set(v) { field = v; picked.clear(); if (v != null) picked.add(v); invalidate() }
    /** Keyframes picked for the next change (one, or a whole row). */
    val picked = LinkedHashSet<Keyframe>()
    var selectedRow = 0
        set(v) { field = v; invalidate() }
    var onSelect: ((Keyframe?) -> Unit)? = null
    var onRow: ((Int) -> Unit)? = null

    private class Row(val name: String, val graphProp: Int, val value: (Keyframe) -> FloatArray)
    private val rows: List<Row> get() {
        val r = arrayListOf(
            Row("Position", 0) { floatArrayOf(it.cx, it.cy) },
            Row("Scale", 2) { floatArrayOf(it.scale, it.sx, it.sy) },
            Row("Rotate", 3) { floatArrayOf(it.rotation) },
            Row("Opacity", 4) { floatArrayOf(it.opacity) })
        if (layer.kind == so.ijarjar.app.model.LayerKind.MODEL3D || layer.keyframes.any { it.rx != 0f || it.ry != 0f || it.z != 0f } || layer.rotX != 0f || layer.rotY != 0f)
            r.add(Row("3D X/Y/Z", 5) { floatArrayOf(it.rx, it.ry, it.z) })
        return r
    }

    private val labelW get() = 76 * d
    private val rulerH get() = 20 * d
    /** Big rows when the sheet fills the screen (Fit). */
    var big = false
        set(v) { field = v; requestLayout(); invalidate() }
    var onAddKey: ((Long) -> Unit)? = null
    private val rowH get() = (if (big) 44 else 26) * d
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (rulerH + rowH * rows.size + 4 * d).toInt())

    // visible time (ms inside the layer)
    private var vs = 0L
    private var ve = -1L
    private fun vEnd() = if (ve <= vs) layer.durationMs.coerceAtLeast(1) else ve
    private fun x(t: Long) = labelW + 8 * d + (width - labelW - 16 * d) * ((t - vs).toFloat() / (vEnd() - vs).coerceAtLeast(1))
    private fun t(x: Float) = (vs + (x - labelW - 8 * d) / (width - labelW - 16 * d) * (vEnd() - vs)).toLong().coerceIn(0, layer.durationMs)
    private fun zoomed() = vEnd() - vs < layer.durationMs - 1
    fun zoom(f: Float) {
        val dur = layer.durationMs.coerceAtLeast(1)
        val around = (time() - layer.startMs).coerceIn(0, dur)
        val span = ((vEnd() - vs) / f).toLong().coerceIn(200L.coerceAtMost(dur), dur)
        val frac = ((around - vs).toFloat() / (vEnd() - vs).coerceAtLeast(1)).coerceIn(0f, 1f)
        vs = (around - (span * frac).toLong()).coerceIn(0, dur - span); ve = vs + span; invalidate()
    }
    fun fit() { vs = 0; ve = -1; invalidate() }
    private fun panTo(s: Long) { val span = vEnd() - vs; vs = s.coerceIn(0, (layer.durationMs - span).coerceAtLeast(0)); ve = vs + span; invalidate() }

    /** Does this row's value change at keyframe [k]? (AE shows a key only where the property is keyed.) */
    private fun keyed(row: Row, k: Keyframe, ks: List<Keyframe>): Boolean {
        if (ks.size <= 1) return true
        val i = ks.indexOf(k); val v = row.value(k)
        fun diff(o: Keyframe?) = o != null && row.value(o).zip(v.toList()).any { (a, b) -> abs(a - b) > 1e-4f }
        return diff(ks.getOrNull(i - 1)) || diff(ks.getOrNull(i + 1))
    }

    private val bgA = Paint().apply { color = 0xFF202027.toInt() }
    private val bgB = Paint().apply { color = 0xFF24242C.toInt() }
    private val rowSel = Paint().apply { color = 0x2219D3C5 }
    private val labelP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFC9C9D2.toInt(); textSize = 11f * d }
    private val labelSelP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.ACCENT; textSize = 11f * d; isFakeBoldText = true }
    private val rulerT = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.TEXT2; textSize = 9f * d }
    private val tickP = Paint().apply { color = 0x66FFFFFF; strokeWidth = 1f * d }
    private val spanP = Paint().apply { color = 0x55FFCC00; strokeWidth = 2f * d }
    private val keyP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFCC00.toInt() }
    private val keySel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val keyOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC000000.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f * d; strokeJoin = Paint.Join.ROUND }
    private val headP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF3D7F.toInt(); strokeWidth = 2f * d }

    override fun onDraw(c: Canvas) {
        val rs = rows
        val ks = layer.keyframes.sortedBy { it.t }
        c.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), 12 * d, 12 * d, bgA)
        // ruler
        val spanS = (vEnd() - vs) / 1000f
        val usable = (width - labelW - 16 * d).coerceAtLeast(1f)
        val steps = floatArrayOf(0.05f, 0.1f, 0.25f, 0.5f, 1f, 2f, 5f, 10f, 15f, 30f, 60f)
        val stepS = steps.firstOrNull { usable * it / spanS >= 52 * d } ?: 120f
        var s = kotlin.math.floor(vs / 1000f / stepS) * stepS
        c.save(); c.clipRect(labelW, 0f, width.toFloat(), height.toFloat())
        while (s <= vEnd() / 1000f + stepS) {
            val xx = x((s * 1000).toLong())
            c.drawLine(xx, rulerH - 6 * d, xx, rulerH, tickP)
            c.drawText(if (stepS >= 1f) "${s.toInt()}s" else "%.2fs".format(s), xx + 2 * d, rulerH - 8 * d, rulerT)
            s += stepS
        }
        c.restore()
        // rows
        for ((i, row) in rs.withIndex()) {
            val top = rulerH + i * rowH
            c.drawRect(0f, top, width.toFloat(), top + rowH, if (i % 2 == 0) bgB else bgA)
            if (i == selectedRow) c.drawRect(0f, top, width.toFloat(), top + rowH, rowSel)
            c.drawText(row.name, 10 * d, top + rowH / 2 + 4 * d, if (i == selectedRow) labelSelP else labelP)
            val cy = top + rowH / 2
            c.save(); c.clipRect(labelW, top, width.toFloat(), top + rowH)
            val keyedHere = ks.filter { keyed(row, it, ks) }
            for (j in 0 until keyedHere.size - 1) c.drawLine(x(keyedHere[j].t), cy, x(keyedHere[j + 1].t), cy, spanP)
            for (k in keyedHere) KeyIcon.draw(c, layer, k, x(k.t), cy, 7 * d, if (k in picked) keySel else keyP, keyOutline)
            c.restore()
        }
        // playhead
        val px = x(time() - layer.startMs)
        if (px >= labelW) {
            c.drawLine(px, 2 * d, px, height.toFloat(), headP)
            c.drawCircle(px, 4 * d, 3.5f * d, headP)
        }
    }

    private var mode = 0       // 1 seek, 2 key, 3 pan, 4 pinch, 5 pending
    private var dragKey: Keyframe? = null
    private var downX = 0f; private var downY = 0f; private var panStart = 0L
    private var pinch = 0f; private var pinchMid = 0f
    private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    private fun keyAt(xx: Float, yy: Float): Keyframe? {
        val rs = rows; val ks = layer.keyframes.sortedBy { it.t }
        val ri = ((yy - rulerH) / rowH).toInt()
        val row = rs.getOrNull(ri)
        val cand = if (row != null) ks.filter { keyed(row, it, ks) } else ks
        return cand.minByOrNull { abs(x(it.t) - xx) }?.takeIf { abs(x(it.t) - xx) < 16 * d }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.pointerCount >= 2) {
            val sp = abs(e.getX(0) - e.getX(1)).coerceAtLeast(1f); val mid = (e.getX(0) + e.getX(1)) / 2
            when (e.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> { mode = 4; pinch = sp; pinchMid = mid; parent?.requestDisallowInterceptTouchEvent(true) }
                MotionEvent.ACTION_MOVE -> if (mode == 4) {
                    val dur = layer.durationMs.coerceAtLeast(1)
                    val focus = t(mid)
                    val span = ((vEnd() - vs) * pinch / sp).toLong().coerceIn(200L.coerceAtMost(dur), dur)
                    val frac = ((focus - vs).toFloat() / (vEnd() - vs).coerceAtLeast(1))
                    var ns = focus - (span * frac).toLong() - ((mid - pinchMid) / (width - labelW) * span).toLong()
                    ns = ns.coerceIn(0, dur - span); vs = ns; ve = ns + span
                    pinch = sp; pinchMid = mid; invalidate()
                }
            }
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; panStart = vs
                if (e.x < labelW && e.y > rulerH) {          // a row name: pick that property and ALL its keyframes
                    val ri = ((e.y - rulerH) / rowH).toInt().coerceIn(0, rows.size - 1)
                    val ks = layer.keyframes.sortedBy { it.t }
                    val inRow = ks.filter { keyed(rows[ri], it, ks) }
                    selectedRow = ri
                    selected = inRow.firstOrNull(); picked.addAll(inRow)
                    onSelect?.invoke(selected); onRow?.invoke(rows[ri].graphProp); mode = 0
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); invalidate(); return true
                }
                val px = x(time() - layer.startMs)
                val k = if (e.y > rulerH) keyAt(e.x, e.y) else null
                mode = when {
                    k != null -> { dragKey = k; selected = k; onSelect?.invoke(k); 2 }
                    e.y < rulerH || abs(e.x - px) < 14 * d -> 1
                    else -> 5
                }
                if (mode != 5) parent?.requestDisallowInterceptTouchEvent(true)
                if (mode == 1) onSeek(layer.startMs + t(e.x))
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == 5) {
                    val ax = abs(e.x - downX); val ay = abs(e.y - downY)
                    if (ax < slop && ay < slop) return true
                    if (ay > ax) { mode = 0; return false }        // vertical = scroll the panel
                    mode = if (zoomed()) 3 else 1; parent?.requestDisallowInterceptTouchEvent(true)
                }
                when (mode) {
                    1 -> onSeek(layer.startMs + t(e.x))
                    2 -> dragKey?.let { k ->
                        if (abs(e.x - downX) < slop) return true
                        // free: a keyframe can go anywhere, even past the others
                        var nt = t(e.x)
                        while (layer.keyframes.any { it !== k && abs(it.t - nt) < 10 }) nt += 10
                        k.t = nt.coerceIn(0, layer.durationMs)
                        onSeek(layer.startMs + k.t); onEdit(); invalidate()
                    }
                    3 -> panTo(panStart + ((downX - e.x) / (width - labelW - 16 * d) * (vEnd() - vs)).toLong())
                }
            }
            MotionEvent.ACTION_UP -> {
                if (mode == 5) {
                    // a tap on an empty spot of a row: a new keyframe there (like clicking the AE timeline with the stopwatch on)
                    val tt = layer.startMs + t(e.x)
                    if (e.y > rulerH && onAddKey != null) { selectedRow = ((e.y - rulerH) / rowH).toInt().coerceIn(0, rows.size - 1); onAddKey?.invoke(tt) }
                    else { selected = null; onSelect?.invoke(null); onSeek(tt) }
                } else if (mode == 2 && abs(e.x - downX) < slop) dragKey?.let { onSeek(layer.startMs + it.t) }
                mode = 0; dragKey = null
            }
            MotionEvent.ACTION_CANCEL -> { mode = 0; dragKey = null }
        }
        invalidate()
        return true
    }
}

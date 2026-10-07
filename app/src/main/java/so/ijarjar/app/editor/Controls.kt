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
    private val padB get() = 18 * d
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
                MotionEvent.ACTION_POINTER_DOWN -> { pinchSpan = sp; dragging = 3; lastMid = mid }
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
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = 2
                handles()?.let { (h1, h2) ->
                    if (hypot(e.x - h1[0], e.y - h1[1]) < 22 * d) dragging = 0
                    else if (hypot(e.x - h2[0], e.y - h2[1]) < 22 * d) dragging = 1
                }
                if (dragging == 2) {
                    // tap on a keyframe selects it
                    val hit = layer.keyframes.minByOrNull { abs(tx(it.t) - e.x) }
                    if (hit != null && abs(tx(hit.t) - e.x) < 18 * d) { selected = hit; onSeek(layer.startMs + hit.t); dragging = -1 }
                    else onSeek(layer.startMs + tOf(e.x))
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val a = selected
                if (dragging == 3) return true
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
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = -1
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
            val r = 6 * d
            val dia = Path().apply { moveTo(x, y - r); lineTo(x + r, y); lineTo(x, y + r); lineTo(x - r, y); close() }
            c.drawPath(dia, if (k === selected) keySel else keyP)
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
    }
}

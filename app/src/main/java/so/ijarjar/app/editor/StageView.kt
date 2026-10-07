package so.ijarjar.app.editor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import so.ijarjar.app.model.Layer
import so.ijarjar.app.model.LayerKind
import so.ijarjar.app.model.Project
import so.ijarjar.app.render.LayerRenderer
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The preview canvas. Holds the main video surface, an image view for photo clips,
 * texture views for overlay videos, and a drawing/gesture layer on top.
 */
class StageView(context: Context) : FrameLayout(context) {

    interface Listener {
        fun onLayerSelected(layer: Layer?)
        fun onLayerTransforming()
        fun onLayerTransformed()
    }

    var project: Project? = null
    var timeMs: Long = 0
    var selectedLayerId: String? = null
        set(v) { field = v; overlay.invalidate() }
    var listener: Listener? = null

    val mainTexture = TextureView(context)
    val imageView = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER; visibility = View.GONE }
    val videoLayerHost = FrameLayout(context)
    private val overlay = OverlayView(context)
    val videoLayerViews = HashMap<String, TextureView>()

    private var videoW = 16
    private var videoH = 9

    init {
        setBackgroundColor(Color.BLACK)
        clipChildren = true
        addView(mainTexture, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(imageView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(videoLayerHost, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        videoLayerHost.clipChildren = false
    }

    /** Fits the stage to the project aspect ratio inside whatever space the parent gives. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availW = MeasureSpec.getSize(widthMeasureSpec)
        val availH = MeasureSpec.getSize(heightMeasureSpec)
        val r = project?.aspectRatio() ?: (9f / 16f)
        var w = availW
        var h = (w / r).toInt()
        if (h > availH && availH > 0) { h = availH; w = (h * r).toInt() }
        super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        layoutMainTexture(w, h)
    }

    fun setVideoSize(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        videoW = w; videoH = h
        layoutMainTexture(width, height)
        mainTexture.requestLayout()
    }

    private fun layoutMainTexture(sw: Int, sh: Int) {
        if (sw <= 0 || sh <= 0) return
        val vr = videoW.toFloat() / videoH
        val sr = sw.toFloat() / sh
        val lp = mainTexture.layoutParams as LayoutParams
        val nw: Int; val nh: Int
        if (vr > sr) { nw = sw; nh = (sw / vr).toInt() } else { nh = sh; nw = (sh * vr).toInt() }
        if (lp.width == nw && lp.height == nh && lp.gravity == android.view.Gravity.CENTER) return
        lp.width = nw; lp.height = nh
        lp.gravity = android.view.Gravity.CENTER
        mainTexture.layoutParams = lp
    }

    /** Called every frame by the editor. */
    fun refresh() {
        val p = project ?: return
        val w = width; val h = height
        if (w == 0) return
        for (l in p.layers) {
            if (l.kind != LayerKind.VIDEO) continue
            val tv = videoLayerViews[l.id] ?: continue
            val (cw, ch) = LayerRenderer.contentSize(l, w)
            val lp = tv.layoutParams
            if (lp.width != cw.toInt() || lp.height != ch.toInt()) {
                lp.width = cw.toInt().coerceAtLeast(1); lp.height = ch.toInt().coerceAtLeast(1)
                tv.layoutParams = lp
            }
            tv.pivotX = cw / 2f; tv.pivotY = ch / 2f
            tv.translationX = l.cx * w - cw / 2f
            tv.translationY = l.cy * h - ch / 2f
            tv.scaleX = if (l.flipH) -l.scale else l.scale
            tv.scaleY = l.scale
            tv.rotation = l.rotation
            tv.alpha = l.opacity
            tv.visibility = if (l.isActive(timeMs)) View.VISIBLE else View.INVISIBLE
        }
        overlay.invalidate()
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    @SuppressLint("ViewConstructor")
    private inner class OverlayView(context: Context) : View(context) {
        private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = dp(2f); color = Color.WHITE
        }
        private val linkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = dp(1.5f); color = 0xFF19D3C5.toInt()
            pathEffect = DashPathEffect(floatArrayOf(dp(6f), dp(4f)), 0f)
        }
        private val guidePaint = Paint().apply { color = 0xFFFF3D7F.toInt(); strokeWidth = dp(1f) }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val linkLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF19D3C5.toInt(); strokeWidth = dp(1.5f) }

        private var showVGuide = false
        private var showHGuide = false

        override fun onDraw(canvas: Canvas) {
            val p = project ?: return
            val w = width; val h = height
            for (l in LayerRenderer.drawOrder(p.layers)) {
                if (l.kind == LayerKind.VIDEO || !l.isActive(timeMs)) continue
                LayerRenderer.draw(context, canvas, l, w, h, null)
            }
            val sel = p.layers.firstOrNull { it.id == selectedLayerId } ?: return
            val group = p.linkedWith(sel)
            for (g in group) {
                if (g.id == sel.id) continue
                drawBox(canvas, LayerRenderer.corners(g, w, h), linkPaint)
                // a line showing the link between the two layers
                canvas.drawLine(sel.cx * w, sel.cy * h, g.cx * w, g.cy * h, linkLinePaint)
            }
            drawBox(canvas, LayerRenderer.corners(sel, w, h), boxPaint)
            val c = LayerRenderer.corners(sel, w, h)
            for (i in 0 until 4) canvas.drawCircle(c[i * 2], c[i * 2 + 1], dp(5f), dotPaint)
            if (showVGuide) canvas.drawLine(w / 2f, 0f, w / 2f, h.toFloat(), guidePaint)
            if (showHGuide) canvas.drawLine(0f, h / 2f, w.toFloat(), h / 2f, guidePaint)
        }

        private fun drawBox(canvas: Canvas, c: FloatArray, paint: Paint) {
            val path = Path()
            path.moveTo(c[0], c[1]); path.lineTo(c[2], c[3]); path.lineTo(c[4], c[5]); path.lineTo(c[6], c[7]); path.close()
            canvas.drawPath(path, paint)
        }

        // ---- gestures ----
        private var active: Layer? = null
        private var lastX = 0f
        private var lastY = 0f
        private var lastSpan = 0f
        private var lastAngle = 0f
        private var pointerMode = 0
        private var moved = false
        private var downX = 0f
        private var downY = 0f
        private var accRotSnap = 0f
        private var snapOffX = 0f
        private var snapOffY = 0f
        private var snapOffR = 0f

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val p = project ?: return false
            val w = width; val h = height
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x; downY = e.y; moved = false
                    snapOffX = 0f; snapOffY = 0f; snapOffR = 0f
                    val hit = LayerRenderer.drawOrder(p.layers).reversed().firstOrNull {
                        it.isActive(timeMs) && LayerRenderer.hitTest(it, w, h, e.x, e.y, dp(12f))
                    }
                    // Prefer the already selected layer if the touch is on it
                    val cur = p.layers.firstOrNull { it.id == selectedLayerId }
                    val target = if (cur != null && cur.isActive(timeMs) && LayerRenderer.hitTest(cur, w, h, e.x, e.y, dp(16f))) cur else hit
                    active = target
                    if (target?.id != selectedLayerId) {
                        selectedLayerId = target?.id
                        listener?.onLayerSelected(target)
                    }
                    lastX = e.x; lastY = e.y; pointerMode = 1
                    return true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (e.pointerCount >= 2) {
                        pointerMode = 2
                        lastSpan = span(e); lastAngle = angle(e)
                        lastX = focusX(e); lastY = focusY(e)
                        accRotSnap = 0f
                        if (active == null) {
                            active = p.layers.firstOrNull { it.id == selectedLayerId && it.isActive(timeMs) }
                        }
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val a = active ?: return true
                    val group = p.linkedWith(a)
                    if (pointerMode == 2 && e.pointerCount >= 2) {
                        val fx = focusX(e); val fy = focusY(e)
                        val sp = span(e); val an = angle(e)
                        val s = if (lastSpan > 10f) sp / lastSpan else 1f
                        var dr = an - lastAngle
                        if (dr > 180) dr -= 360f
                        if (dr < -180) dr += 360f
                        applyTransform(group, a, fx - lastX, fy - lastY, s, dr, fx, fy, w, h)
                        lastX = fx; lastY = fy; lastSpan = sp; lastAngle = an
                        moved = true
                    } else if (pointerMode == 1) {
                        val dx = e.x - lastX; val dy = e.y - lastY
                        if (!moved && hypot(e.x - downX, e.y - downY) < dp(4f)) return true
                        applyTransform(group, a, dx, dy, 1f, 0f, e.x, e.y, w, h)
                        lastX = e.x; lastY = e.y
                        moved = true
                    }
                    listener?.onLayerTransforming()
                    refresh()
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    // fall back to single finger drag with the remaining pointer
                    val idx = if (e.actionIndex == 0) 1 else 0
                    lastX = e.getX(idx); lastY = e.getY(idx)
                    pointerMode = 1
                    snapOffR = 0f
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    showVGuide = false; showHGuide = false
                    if (moved && active != null) listener?.onLayerTransformed()
                    active = null; pointerMode = 0
                    invalidate()
                }
            }
            return true
        }

        /**
         * Moves / scales / rotates the touched layer AND every layer linked to it,
         * so linked layers behave like one object.
         */
        private fun applyTransform(group: List<Layer>, main: Layer, dx: Float, dy: Float, s: Float, dr: Float,
                                   px: Float, py: Float, w: Int, h: Int) {
            // undo the snap applied on the previous move so the raw finger position is kept
            if (snapOffX != 0f || snapOffY != 0f) {
                for (l in group) { l.cx -= snapOffX; l.cy -= snapOffY }
                snapOffX = 0f; snapOffY = 0f
            }
            if (snapOffR != 0f) {
                for (l in group) l.rotation = ((l.rotation - snapOffR) % 360f + 360f) % 360f
                snapOffR = 0f
            }
            val rad = Math.toRadians(dr.toDouble())
            val cosR = cos(rad).toFloat(); val sinR = sin(rad).toFloat()
            for (l in group) {
                var x = l.cx * w; var y = l.cy * h
                if (s != 1f || dr != 0f) {
                    val rx = (x - px) * s; val ry = (y - py) * s
                    x = px + rx * cosR - ry * sinR
                    y = py + rx * sinR + ry * cosR
                    l.scale = (l.scale * s).coerceIn(0.05f, 20f)
                    l.rotation = ((l.rotation + dr) % 360f + 360f) % 360f
                }
                x += dx; y += dy
                l.cx = (x / w).coerceIn(-0.5f, 1.5f)
                l.cy = (y / h).coerceIn(-0.5f, 1.5f)
            }
            // centre snapping (applied to the whole group)
            val snap = dp(8f)
            var sx = 0f; var sy = 0f
            showVGuide = abs(main.cx * w - w / 2f) < snap
            showHGuide = abs(main.cy * h - h / 2f) < snap
            if (showVGuide) sx = 0.5f - main.cx
            if (showHGuide) sy = 0.5f - main.cy
            if (sx != 0f || sy != 0f) for (l in group) { l.cx += sx; l.cy += sy }
            snapOffX = sx; snapOffY = sy
            // rotation snapping to 0/90/180/270
            if (dr != 0f || pointerMode == 2) {
                val r = main.rotation
                val nearest = (Math.round(r / 90f) * 90f) % 360f
                var diff = nearest - r
                if (diff > 180) diff -= 360f
                if (diff < -180) diff += 360f
                if (abs(diff) < 3f && abs(diff) > 0.001f) {
                    for (l in group) l.rotation = ((l.rotation + diff) % 360f + 360f) % 360f
                    snapOffR = diff
                }
            }
        }

        private fun span(e: MotionEvent) = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
        private fun angle(e: MotionEvent) = Math.toDegrees(atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())).toFloat()
        private fun focusX(e: MotionEvent) = (e.getX(0) + e.getX(1)) / 2f
        private fun focusY(e: MotionEvent) = (e.getY(0) + e.getY(1)) / 2f
    }
}

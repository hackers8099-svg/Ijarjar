package so.ijarjar.app.editor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RenderEffect
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import so.ijarjar.app.media.MediaUtils
import so.ijarjar.app.model.Layer
import so.ijarjar.app.model.LayerKind
import so.ijarjar.app.model.MaskKind
import so.ijarjar.app.model.MediaKind
import so.ijarjar.app.model.Project
import so.ijarjar.app.render.BackgroundRenderer
import so.ijarjar.app.render.EffectRenderer
import so.ijarjar.app.render.Filters
import so.ijarjar.app.render.LayerRenderer
import so.ijarjar.app.render.Motion
import so.ijarjar.app.render.Pose
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The preview canvas. Holds the main video surface, an image view for photo clips,
 * texture views for overlay videos, a fade view for transitions and a drawing/gesture layer on top.
 */
class StageView(context: Context) : FrameLayout(context) {

    interface Listener {
        fun onLayerSelected(layer: Layer?)
        fun onLayerTransforming()
        fun onLayerTransformed()
    }

    /** Something besides a layer that can be moved with fingers (a main clip or the photo background). */
    class CanvasTarget(
        val get: () -> FloatArray,           // x, y (fraction of canvas, 0 = centre), scale, rotation
        val set: (FloatArray) -> Unit
    )

    var project: Project? = null
    var timeMs: Long = 0
    var selectedLayerId: String? = null
        set(v) { field = v; overlay.invalidate() }
    var canvasTarget: CanvasTarget? = null
    var listener: Listener? = null

    val mainTexture = TextureView(context)
    val imageView = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER; visibility = View.GONE }
    val videoLayerHost = FrameLayout(context)
    private val fadeView = View(context)
    private val overlay = OverlayView(context)
    val videoLayerViews = HashMap<String, TextureView>()

    private var shownImageUri: String? = null
    private var colorKey = ""
    private var blurKey = -1f

    init {
        setBackgroundColor(Color.BLACK)
        clipChildren = true
        addView(mainTexture, LayoutParams(10, 10, Gravity.CENTER))
        addView(imageView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(videoLayerHost, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(fadeView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        fadeView.alpha = 0f
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
    }

    /** Size of a clip's picture when fitted in the stage. */
    private fun fitSize(cw: Int, ch: Int, sw: Int, sh: Int): Pair<Int, Int> {
        if (cw <= 0 || ch <= 0) return Pair(sw, sh)
        val vr = cw.toFloat() / ch
        val sr = sw.toFloat() / sh
        return if (vr > sr) Pair(sw, (sw / vr).toInt()) else Pair((sh * vr).toInt(), sh)
    }

    /** Called every frame by the editor. */
    fun refresh() {
        val p = project ?: return
        val w = width; val h = height
        if (w == 0) return
        if (p.isPhoto) {
            mainTexture.visibility = View.GONE
            imageView.visibility = View.GONE
            fadeView.alpha = 0f
            overlay.invalidate()
            return
        }
        refreshMainClip(p, w, h)
        for (l in p.layers) {
            if (l.kind != LayerKind.VIDEO) continue
            val tv = videoLayerViews[l.id] ?: continue
            val pose = LayerRenderer.poseAt(l, timeMs)
            val (cw, ch) = LayerRenderer.contentSize(l, w)
            val lp = tv.layoutParams
            if (lp.width != cw.toInt().coerceAtLeast(1) || lp.height != ch.toInt().coerceAtLeast(1)) {
                lp.width = cw.toInt().coerceAtLeast(1); lp.height = ch.toInt().coerceAtLeast(1)
                tv.layoutParams = lp
            }
            tv.pivotX = cw / 2f; tv.pivotY = ch / 2f
            tv.translationX = pose.cx * w - cw / 2f
            tv.translationY = pose.cy * h - ch / 2f
            tv.scaleX = if (l.flipH) -pose.scale else pose.scale
            tv.scaleY = pose.scale
            tv.rotation = pose.rotation
            // masked overlay videos are drawn by the overlay view instead
            tv.alpha = if (l.mask != MaskKind.NONE) 0.01f else pose.opacity
            tv.visibility = if (l.isActive(timeMs)) View.VISIBLE else View.INVISIBLE
        }
        overlay.invalidate()
    }

    private fun refreshMainClip(p: Project, w: Int, h: Int) {
        if (p.clips.isEmpty()) {
            mainTexture.visibility = View.INVISIBLE; imageView.visibility = View.GONE; fadeView.alpha = 0f
            return
        }
        val idx = p.clipIndexAt(timeMs).coerceAtLeast(0)
        val c = p.clips[idx]
        val local = timeMs - p.clipStartMs(idx)
        val m = Motion.clipMotion(p, idx, local)
        val target: View
        if (c.kind == MediaKind.IMAGE) {
            mainTexture.visibility = View.INVISIBLE
            imageView.visibility = View.VISIBLE
            if (shownImageUri != c.uri) {
                shownImageUri = c.uri
                imageView.setImageBitmap(MediaUtils.loadBitmapCached(context, Uri.parse(c.uri), 1600))
            }
            target = imageView
        } else {
            mainTexture.visibility = View.VISIBLE
            imageView.visibility = View.GONE
            val (fw, fh) = fitSize(c.width, c.height, w, h)
            val lp = mainTexture.layoutParams as LayoutParams
            if (lp.width != fw || lp.height != fh) { lp.width = fw; lp.height = fh; mainTexture.layoutParams = lp }
            target = mainTexture
        }
        target.pivotX = target.width / 2f
        target.pivotY = target.height / 2f
        target.scaleX = m.scale * (if (m.mirror) -1f else 1f)
        target.scaleY = m.scale
        target.rotation = m.rotation
        target.translationX = m.tx * w
        target.translationY = m.ty * h
        fadeView.setBackgroundColor(m.fadeColor)
        fadeView.alpha = m.fadeAlpha

        // colour: clip filter + colour effects
        val effect = EffectRenderer.colorMatrix(p, timeMs)
        val key = "${c.id}|${c.adjust.brightness}|${c.adjust.contrast}|${c.adjust.saturation}|${c.adjust.temperature}|" +
            "${c.adjust.tint}|${c.adjust.preset}|${effect?.array?.contentToString()}"
        if (key != colorKey) {
            colorKey = key
            val cm: ColorMatrix? = if (c.adjust.isColorIdentity() && effect == null) null else {
                val base = Filters.colorMatrix(c.adjust)
                if (effect != null) base.postConcat(effect)
                base
            }
            val filter = cm?.let { ColorMatrixColorFilter(it) }
            mainTexture.setLayerPaint(if (filter != null) Paint().apply { colorFilter = filter } else null)
            imageView.colorFilter = filter
        }
        if (Build.VERSION.SDK_INT >= 31) {
            val r = Filters.blurRadiusPx(c.adjust.blur, target.width.coerceAtLeast(1))
            if (r != blurKey) {
                blurKey = r
                val fx = if (r > 0.5f) RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP) else null
                mainTexture.setRenderEffect(fx)
                imageView.setRenderEffect(fx)
            }
        }
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    @SuppressLint("ViewConstructor")
    private inner class OverlayView(context: Context) : View(context) {
        private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = dp(2f); color = Color.WHITE
        }
        private val clipBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = dp(2f); color = 0xFFFF3D7F.toInt()
        }
        private val linkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = dp(1.5f); color = 0xFF19D3C5.toInt()
            pathEffect = DashPathEffect(floatArrayOf(dp(6f), dp(4f)), 0f)
        }
        private val guidePaint = Paint().apply { color = 0xFFFF3D7F.toInt(); strokeWidth = dp(1f) }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFCC00.toInt() }
        private val linkLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF19D3C5.toInt(); strokeWidth = dp(1.5f) }
        private var frameCache: Bitmap? = null

        private var showVGuide = false
        private var showHGuide = false

        override fun onDraw(canvas: Canvas) {
            val p = project ?: return
            val w = width; val h = height
            if (p.isPhoto) BackgroundRenderer.draw(context, canvas, p, w, h, 1600)
            for (l in LayerRenderer.drawOrder(p.layers)) {
                if (!l.isActive(timeMs)) continue
                if (l.kind == LayerKind.VIDEO) {
                    if (l.mask == MaskKind.NONE) continue
                    val tv = videoLayerViews[l.id] ?: continue
                    if (!tv.isAvailable) continue
                    val (cw, ch) = LayerRenderer.contentSize(l, w)
                    val bw = cw.toInt().coerceIn(16, 720); val bh = (bw * ch / cw).toInt().coerceAtLeast(16)
                    var fc = frameCache
                    if (fc == null || fc.width != bw || fc.height != bh) { fc = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888); frameCache = fc }
                    tv.getBitmap(fc!!)
                    LayerRenderer.draw(context, canvas, l, timeMs, w, h, fc)
                    continue
                }
                LayerRenderer.draw(context, canvas, l, timeMs, w, h, null)
            }
            // selected main clip / background outline
            if (selectedLayerId == null) canvasTarget?.let { drawTargetBox(canvas, it, w, h) }

            val sel = p.layers.firstOrNull { it.id == selectedLayerId } ?: return
            if (sel.isEffect()) return
            val group = p.linkedWith(sel)
            val selPose = LayerRenderer.basePose(sel, timeMs)
            for (g in group) {
                if (g.id == sel.id) continue
                val gp = LayerRenderer.basePose(g, timeMs)
                drawBox(canvas, LayerRenderer.corners(g, gp, w, h), linkPaint)
                canvas.drawLine(selPose.cx * w, selPose.cy * h, gp.cx * w, gp.cy * h, linkLinePaint)
            }
            val c = LayerRenderer.corners(sel, selPose, w, h)
            drawBox(canvas, c, boxPaint)
            for (i in 0 until 4) canvas.drawCircle(c[i * 2], c[i * 2 + 1], dp(5f), dotPaint)
            if (sel.keyframes.isNotEmpty()) {
                // diamond at the top: yellow when sitting on a keyframe
                val on = LayerRenderer.keyframeAt(sel, timeMs) != null
                val x = (c[0] + c[2]) / 2f; val y = (c[1] + c[3]) / 2f - dp(14f)
                val d = Path().apply { moveTo(x, y - dp(7f)); lineTo(x + dp(7f), y); lineTo(x, y + dp(7f)); lineTo(x - dp(7f), y); close() }
                keyPaint.style = if (on) Paint.Style.FILL else Paint.Style.STROKE
                keyPaint.strokeWidth = dp(2f)
                canvas.drawPath(d, keyPaint)
            }
            if (showVGuide) canvas.drawLine(w / 2f, 0f, w / 2f, h.toFloat(), guidePaint)
            if (showHGuide) canvas.drawLine(0f, h / 2f, w.toFloat(), h / 2f, guidePaint)
        }

        private fun drawTargetBox(canvas: Canvas, t: CanvasTarget, w: Int, h: Int) {
            val v = t.get()
            val p = project ?: return
            // approximate box: the clip picture size (or the full canvas for photo backgrounds)
            var bw = w.toFloat(); var bh = h.toFloat()
            if (!p.isPhoto && p.clips.isNotEmpty()) {
                val c = p.clips[p.clipIndexAt(timeMs).coerceAtLeast(0)]
                val (fw, fh) = fitSize(c.width, c.height, w, h)
                bw = fw.toFloat(); bh = fh.toFloat()
            }
            val m = android.graphics.Matrix()
            m.postTranslate(-bw / 2f, -bh / 2f)
            m.postScale(v[2], v[2])
            m.postRotate(v[3])
            m.postTranslate(w / 2f + v[0] * w, h / 2f + v[1] * h)
            val pts = floatArrayOf(0f, 0f, bw, 0f, bw, bh, 0f, bh)
            m.mapPoints(pts)
            drawBox(canvas, pts, clipBoxPaint)
        }

        private fun drawBox(canvas: Canvas, c: FloatArray, paint: Paint) {
            val path = Path()
            path.moveTo(c[0], c[1]); path.lineTo(c[2], c[3]); path.lineTo(c[4], c[5]); path.lineTo(c[6], c[7]); path.close()
            canvas.drawPath(path, paint)
        }

        // ---- gestures ----
        private var active: Layer? = null
        private var movingTarget = false
        private var lastX = 0f
        private var lastY = 0f
        private var lastSpan = 0f
        private var lastAngle = 0f
        private var pointerMode = 0
        private var moved = false
        private var downX = 0f
        private var downY = 0f
        private var snapOffX = 0f
        private var snapOffY = 0f
        private var snapOffR = 0f
        private val poses = HashMap<String, Pose>()

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val p = project ?: return false
            val w = width; val h = height
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x; downY = e.y; moved = false
                    snapOffX = 0f; snapOffY = 0f; snapOffR = 0f
                    poses.clear()
                    val hit = LayerRenderer.drawOrder(p.layers).reversed().firstOrNull {
                        it.isActive(timeMs) && LayerRenderer.hitTest(it, timeMs, w, h, e.x, e.y, dp(12f))
                    }
                    val cur = p.layers.firstOrNull { it.id == selectedLayerId }
                    val target = if (cur != null && cur.isActive(timeMs) && LayerRenderer.hitTest(cur, timeMs, w, h, e.x, e.y, dp(16f))) cur else hit
                    active = target
                    movingTarget = target == null && canvasTarget != null && selectedLayerId == null
                    if (target != null && target.id != selectedLayerId) {
                        selectedLayerId = target.id
                        listener?.onLayerSelected(target)
                    } else if (target == null && selectedLayerId != null) {
                        selectedLayerId = null
                        listener?.onLayerSelected(null)
                    }
                    target?.let { a -> for (g in p.linkedWith(a)) poses[g.id] = LayerRenderer.basePose(g, timeMs) }
                    lastX = e.x; lastY = e.y; pointerMode = 1
                    return true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (e.pointerCount >= 2) {
                        pointerMode = 2
                        lastSpan = span(e); lastAngle = angle(e)
                        lastX = focusX(e); lastY = focusY(e)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    var dx: Float; var dy: Float; var s = 1f; var dr = 0f; val fx: Float; val fy: Float
                    if (pointerMode == 2 && e.pointerCount >= 2) {
                        fx = focusX(e); fy = focusY(e)
                        val sp = span(e); val an = angle(e)
                        s = if (lastSpan > 10f) sp / lastSpan else 1f
                        dr = an - lastAngle
                        if (dr > 180) dr -= 360f
                        if (dr < -180) dr += 360f
                        dx = fx - lastX; dy = fy - lastY
                        lastSpan = sp; lastAngle = an
                    } else {
                        fx = e.x; fy = e.y
                        dx = e.x - lastX; dy = e.y - lastY
                        if (!moved && hypot(e.x - downX, e.y - downY) < dp(4f)) return true
                    }
                    lastX = fx; lastY = fy
                    moved = true
                    val a = active
                    if (a != null) {
                        applyTransform(p, p.linkedWith(a), a, dx, dy, s, dr, fx, fy, w, h)
                    } else if (movingTarget) {
                        val t = canvasTarget ?: return true
                        val v = t.get()
                        if (s != 1f || dr != 0f) {
                            // rotate/scale around the fingers
                            val cxp = w / 2f + v[0] * w; val cyp = h / 2f + v[1] * h
                            val rad = Math.toRadians(dr.toDouble())
                            val rx = (cxp - fx) * s; val ry = (cyp - fy) * s
                            val nx = fx + rx * cos(rad).toFloat() - ry * sin(rad).toFloat()
                            val ny = fy + rx * sin(rad).toFloat() + ry * cos(rad).toFloat()
                            v[0] = (nx - w / 2f) / w; v[1] = (ny - h / 2f) / h
                            v[2] = (v[2] * s).coerceIn(0.1f, 10f)
                            v[3] = ((v[3] + dr) % 360f + 360f) % 360f
                        }
                        v[0] += dx / w; v[1] += dy / h
                        t.set(v)
                    } else return true
                    listener?.onLayerTransforming()
                    refresh()
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    val idx = if (e.actionIndex == 0) 1 else 0
                    lastX = e.getX(idx); lastY = e.getY(idx)
                    pointerMode = 1
                    snapOffR = 0f
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    showVGuide = false; showHGuide = false
                    if (moved && (active != null || movingTarget)) listener?.onLayerTransformed()
                    active = null; movingTarget = false; pointerMode = 0
                    invalidate()
                }
            }
            return true
        }

        /**
         * Moves / scales / rotates the touched layer AND every layer linked to it,
         * so linked layers behave like one object. Keyframed layers get keyframes.
         */
        private fun applyTransform(p: Project, group: List<Layer>, main: Layer, dx: Float, dy: Float, s: Float, dr: Float,
                                   px: Float, py: Float, w: Int, h: Int) {
            for (l in group) if (!poses.containsKey(l.id)) poses[l.id] = LayerRenderer.basePose(l, timeMs)
            if (snapOffX != 0f || snapOffY != 0f) {
                for (l in group) poses[l.id]?.let { it.cx -= snapOffX; it.cy -= snapOffY }
                snapOffX = 0f; snapOffY = 0f
            }
            if (snapOffR != 0f) {
                for (l in group) poses[l.id]?.let { it.rotation = ((it.rotation - snapOffR) % 360f + 360f) % 360f }
                snapOffR = 0f
            }
            val rad = Math.toRadians(dr.toDouble())
            val cosR = cos(rad).toFloat(); val sinR = sin(rad).toFloat()
            for (l in group) {
                val q = poses[l.id] ?: continue
                var x = q.cx * w; var y = q.cy * h
                if (s != 1f || dr != 0f) {
                    val rx = (x - px) * s; val ry = (y - py) * s
                    x = px + rx * cosR - ry * sinR
                    y = py + rx * sinR + ry * cosR
                    q.scale = (q.scale * s).coerceIn(0.05f, 20f)
                    q.rotation = ((q.rotation + dr) % 360f + 360f) % 360f
                }
                x += dx; y += dy
                q.cx = (x / w).coerceIn(-0.5f, 1.5f)
                q.cy = (y / h).coerceIn(-0.5f, 1.5f)
            }
            val mp = poses[main.id]!!
            val snap = dp(8f)
            var sx = 0f; var sy = 0f
            showVGuide = abs(mp.cx * w - w / 2f) < snap
            showHGuide = abs(mp.cy * h - h / 2f) < snap
            if (showVGuide) sx = 0.5f - mp.cx
            if (showHGuide) sy = 0.5f - mp.cy
            if (sx != 0f || sy != 0f) for (l in group) poses[l.id]?.let { it.cx += sx; it.cy += sy }
            snapOffX = sx; snapOffY = sy
            if (pointerMode == 2) {
                val r = mp.rotation
                val nearest = (Math.round(r / 90f) * 90f) % 360f
                var diff = nearest - r
                if (diff > 180) diff -= 360f
                if (diff < -180) diff += 360f
                if (abs(diff) < 3f && abs(diff) > 0.001f) {
                    for (l in group) poses[l.id]?.let { it.rotation = ((it.rotation + diff) % 360f + 360f) % 360f }
                    snapOffR = diff
                }
            }
            for (l in group) poses[l.id]?.let { LayerRenderer.writePose(l, timeMs, it) }
        }

        private fun span(e: MotionEvent) = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
        private fun angle(e: MotionEvent) = Math.toDegrees(atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())).toFloat()
        private fun focusX(e: MotionEvent) = (e.getX(0) + e.getX(1)) / 2f
        private fun focusY(e: MotionEvent) = (e.getY(0) + e.getY(1)) / 2f
    }
}

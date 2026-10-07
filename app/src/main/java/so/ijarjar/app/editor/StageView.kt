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
import android.graphics.RectF
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
import so.ijarjar.app.model.Stroke
import so.ijarjar.app.render.BackgroundRenderer
import so.ijarjar.app.render.EffectRenderer
import so.ijarjar.app.render.Filters
import so.ijarjar.app.render.LayerRenderer
import so.ijarjar.app.render.Lut
import so.ijarjar.app.render.Motion
import so.ijarjar.app.render.Pose
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The preview canvas: main video surface, image view for photo clips, LUT view, overlay video
 * surfaces, a fade view for transitions and a drawing / gesture layer on top.
 */
class StageView(context: Context) : FrameLayout(context) {

    interface Listener {
        fun onLayerSelected(layer: Layer?)
        /** "Select" mode: the set of picked layers changed. */
        fun onMultiChanged() {}
        fun onLayerTransforming()
        fun onLayerTransformed()
    }

    /** Something besides a layer that can be moved with fingers (a main clip or the photo background). */
    class CanvasTarget(
        val get: () -> FloatArray,           // x, y (fraction of canvas, 0 = centre), scale, rotation
        val set: (FloatArray) -> Unit
    )

    /** Brush settings while drawing. */
    class Brush(var layerId: String, var color: Int, var width: Float, var eraser: Boolean = false, var type: Int = 0)

    var project: Project? = null
    var timeMs: Long = 0
    var selectedLayerId: String? = null
    /** "Select" mode: taps add / remove layers instead of switching to one. */
    var multiMode = false
        set(v) { field = v; overlay.invalidate() }
    var canvasTarget: CanvasTarget? = null
    var listener: Listener? = null
    var brush: Brush? = null
    /** Rule-of-thirds grid + centre lines + safe area. */
    var showGrid = false
        set(v) { field = v; overlay.invalidate() }
    /** When set, the next tap picks a colour from the selected layer's picture. */
    var colorPicker: ((Int) -> Unit)? = null
    /** On-screen editing of a layer's mask (drag = move, pinch = size) or crop (drag the edges). */
    var editMask: Layer? = null
        set(v) { field = v; overlay.invalidate() }
    var editCrop: Layer? = null
        set(v) { field = v; overlay.invalidate() }
    var onEditChanged: (() -> Unit)? = null

    val mainTexture = TextureView(context)
    val imageView = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER; visibility = View.GONE }
    private val lutView = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_XY; visibility = View.GONE }
    val videoLayerHost = FrameLayout(context)
    private val fadeView = View(context)
    private val overlay = OverlayView(context)
    val videoLayerViews = HashMap<String, TextureView>()

    private var shownImageUri: String? = null
    private var colorKey = ""
    private var blurKey = -1f
    private var lutFrame = 0
    private var lutBmp: Bitmap? = null
    private var lutGrab: Bitmap? = null

    init {
        setBackgroundColor(Color.BLACK)
        clipChildren = true
        addView(mainTexture, LayoutParams(10, 10, Gravity.CENTER))
        addView(imageView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(lutView, LayoutParams(10, 10, Gravity.CENTER))
        addView(videoLayerHost, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(fadeView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        fadeView.alpha = 0f
        videoLayerHost.clipChildren = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availW = MeasureSpec.getSize(widthMeasureSpec)
        val availH = MeasureSpec.getSize(heightMeasureSpec)
        val r = project?.aspectRatio() ?: (9f / 16f)
        var w = availW
        var h = (w / r).toInt()
        if (h > availH && availH > 0) { h = availH; w = (h * r).toInt() }
        super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }

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
            lutView.visibility = View.GONE
            fadeView.alpha = 0f
            overlay.invalidate()
            return
        }
        refreshMainClip(p, w, h)
        canvasVideos = videosOnCanvas(p)
        for (l in p.layers) {
            if (l.kind == LayerKind.MODEL3D) {
                // the video on a 3D model plays hidden; its frames go onto the model
                videoLayerViews[l.id]?.let { tv -> tv.alpha = 0.01f; tv.visibility = if (l.isActive(timeMs)) View.VISIBLE else View.INVISIBLE
                    val lp = tv.layoutParams; if (lp.width != 360) { lp.width = 360; lp.height = 640; tv.layoutParams = lp } }
                continue
            }
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
            tv.scaleX = (if (l.flipH) -pose.scale else pose.scale) * pose.sx * pose.flipX
            tv.scaleY = pose.scale * pose.sy
            tv.rotation = pose.rotation
            // keyed / masked / cropped overlay videos are drawn by the overlay view instead
            tv.alpha = if (drawnByOverlay(l) || l.id in canvasVideos) 0.01f else pose.opacity
            tv.visibility = if (l.isActive(timeMs)) View.VISIBLE else View.INVISIBLE
        }
        overlay.invalidate()
    }

    /** Overlay videos that sit above a picture / text must be drawn on the canvas to keep the order. */
    private var canvasVideos: Set<String> = emptySet()

    private fun videosOnCanvas(p: Project): Set<String> {
        val out = HashSet<String>()
        var canvasStarted = false
        for (l in p.layers) {
            if (l.isEffect()) continue
            if (l.kind == LayerKind.VIDEO) { if (canvasStarted || drawnByOverlay(l)) { out.add(l.id); canvasStarted = true } }
            else canvasStarted = true
        }
        return out
    }

    private fun drawnByOverlay(l: Layer) = l.mask != MaskKind.NONE || l.chroma || l.hasCrop() || l.outlineColor != 0 || l.shadow ||
        l.mockup != so.ijarjar.app.model.MockupKind.NONE || l.glowColor != 0 || l.rotX != 0f || l.rotY != 0f || l.posZ != 0f ||
        l.keyframes.any { it.rx != 0f || it.ry != 0f || it.z != 0f } || !l.adjust.isIdentity() || l.motionBlur

    private fun refreshMainClip(p: Project, w: Int, h: Int) {
        if (p.clips.isEmpty()) {
            mainTexture.visibility = View.INVISIBLE; imageView.visibility = View.GONE; lutView.visibility = View.GONE; fadeView.alpha = 0f
            return
        }
        val idx = p.clipIndexAt(timeMs).coerceAtLeast(0)
        val c = p.clips[idx]
        val local = timeMs - p.clipStartMs(idx)
        val m = Motion.clipMotion(p, idx, local)
        val target: View
        val (fw, fh) = fitSize(c.width, c.height, w, h)
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
            val lp = mainTexture.layoutParams as LayoutParams
            if (lp.width != fw || lp.height != fh) { lp.width = fw; lp.height = fh; mainTexture.layoutParams = lp }
            target = mainTexture
        }
        val views = listOf(target, lutView)
        for (v in views) {
            v.pivotX = (if (v === lutView) fw else v.width) / 2f
            v.pivotY = (if (v === lutView) fh else v.height) / 2f
            v.scaleX = m.scale * m.sx * (if (m.mirror) -1f else 1f)
            v.scaleY = m.scale
            v.rotation = m.rotation
            v.translationX = m.tx * w
            v.translationY = m.ty * h
        }
        fadeView.setBackgroundColor(m.fadeColor)
        fadeView.alpha = m.fadeAlpha

        // colour: clip filter + colour effects
        val effect = EffectRenderer.colorMatrix(p, timeMs)
        val a = c.adjust
        val key = "${c.id}|${a.brightness}|${a.contrast}|${a.saturation}|${a.temperature}|${a.tint}|${a.preset}|${effect?.array?.contentToString()}"
        if (key != colorKey) {
            colorKey = key
            val cm: ColorMatrix? = if (a.isColorIdentity() && effect == null) null else {
                val base = Filters.colorMatrix(a)
                if (effect != null) base.postConcat(effect)
                base
            }
            val filter = cm?.let { ColorMatrixColorFilter(it) }
            mainTexture.setLayerPaint(if (filter != null) Paint().apply { colorFilter = filter } else null)
            imageView.colorFilter = filter
            lutView.colorFilter = filter
        }
        if (Build.VERSION.SDK_INT >= 31) {
            val r = Filters.blurRadiusPx(a.blur, target.width.coerceAtLeast(1))
            if (r != blurKey) {
                blurKey = r
                val fx = if (r > 0.5f) RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP) else null
                mainTexture.setRenderEffect(fx)
                imageView.setRenderEffect(fx)
                lutView.setRenderEffect(fx)
            }
        }
        // LUT preview: the frame is graded on the CPU and shown on top of the video
        if (a.lutUri != null || a.hasTone()) {
            val lut = Filters.lutFor(context, a)
            if (lut != null) {
                val lp = lutView.layoutParams as LayoutParams
                if (lp.width != fw || lp.height != fh) { lp.width = fw; lp.height = fh; lutView.layoutParams = lp }
                lutView.visibility = View.VISIBLE
                if (lutFrame++ % 2 == 0) {
                    val src: Bitmap? = if (c.kind == MediaKind.IMAGE) MediaUtils.loadBitmapCached(context, Uri.parse(c.uri), 720) else {
                        val gw = 360; val gh = (360f * fh / fw.coerceAtLeast(1)).toInt().coerceAtLeast(16)
                        var g = lutGrab
                        if (g == null || g.width != gw || g.height != gh) { g = Bitmap.createBitmap(gw, gh, Bitmap.Config.ARGB_8888); lutGrab = g }
                        if (mainTexture.isAvailable) mainTexture.getBitmap(g!!) else null
                    }
                    if (src != null) {
                        lutBmp = lut.apply(src, 1f, if (c.kind == MediaKind.IMAGE) null else lutBmp)
                        lutView.setImageBitmap(lutBmp)
                        lutView.invalidate()
                    }
                }
                return
            }
        }
        lutView.visibility = View.GONE
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    @SuppressLint("ViewConstructor")
    private inner class OverlayView(context: Context) : View(context) {
        private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.5f); color = Color.WHITE }
        private val clipBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2f); color = 0xFFFF3D7F.toInt() }
        private val linkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = dp(1.5f); color = 0xFF19D3C5.toInt()
            pathEffect = DashPathEffect(floatArrayOf(dp(6f), dp(4f)), 0f)
        }
        private val guidePaint = Paint().apply { color = 0xFFFF3D7F.toInt(); strokeWidth = dp(1f) }
        private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF19D3C5.toInt(); style = Paint.Style.STROKE; strokeWidth = dp(1.5f) }
        private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFCC00.toInt() }
        private val linkLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF19D3C5.toInt(); strokeWidth = dp(1.5f) }
        private val frameCache = HashMap<String, Bitmap>()
        private val gridPaint = Paint().apply { color = 0x88FFFFFF.toInt() }
        private val safePaint = Paint().apply { color = 0x55FFFFFF; style = Paint.Style.STROKE; strokeWidth = 2f; pathEffect = DashPathEffect(floatArrayOf(12f, 10f), 0f) }

        private var showVGuide = false
        private var showHGuide = false

        private fun videoFrame(l: Layer, w: Int): Bitmap? {
            val tv = videoLayerViews[l.id] ?: return null
            if (!tv.isAvailable) return null
            val (cw, ch) = LayerRenderer.contentSize(l, w)
            val bw = cw.toInt().coerceIn(16, 640); val bh = (bw * ch / cw).toInt().coerceAtLeast(16)
            var fc = frameCache[l.id]
            if (fc == null || fc.width != bw || fc.height != bh) { fc = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888); frameCache[l.id] = fc }
            return tv.getBitmap(fc!!)
        }

        override fun onDraw(canvas: Canvas) {
            val p = project ?: return
            val w = width; val h = height
            if (p.isPhoto) BackgroundRenderer.draw(context, canvas, p, w, h, 1600)
            for (l in LayerRenderer.drawOrder(p.layers)) {
                if (!l.isActive(timeMs)) continue
                if (l.kind == LayerKind.VIDEO) {
                    if (!drawnByOverlay(l) && l.id !in canvasVideos) continue
                    val fr = videoFrame(l, w) ?: continue
                    LayerRenderer.draw(context, canvas, l, timeMs, w, h, fr)
                    continue
                }
                val live = if (l.kind == LayerKind.MODEL3D && l.videoSource() != null) videoFrame(l, w) else null
                LayerRenderer.draw(context, canvas, l, timeMs, w, h, live)
            }
            if (showGrid) {
                gridPaint.strokeWidth = dp(1f)
                for (k in 1..2) {
                    canvas.drawLine(w * k / 3f, 0f, w * k / 3f, h.toFloat(), gridPaint)
                    canvas.drawLine(0f, h * k / 3f, w.toFloat(), h * k / 3f, gridPaint)
                }
                canvas.drawLine(w / 2f, h / 2f - dp(10f), w / 2f, h / 2f + dp(10f), gridPaint)
                canvas.drawLine(w / 2f - dp(10f), h / 2f, w / 2f + dp(10f), h / 2f, gridPaint)
                // title-safe area (90 %)
                canvas.drawRect(w * 0.05f, h * 0.05f, w * 0.95f, h * 0.95f, safePaint)
            }
            if (editMask != null || editCrop != null) { drawEdit(canvas, w, h); return }
            if (selectedLayerId == null) canvasTarget?.let { drawTargetBox(canvas, it, w, h) }
            val sel = p.layers.firstOrNull { it.id == selectedLayerId } ?: return
            if (sel.isEffect() || brush != null) return
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
            if (sel.kind != LayerKind.DRAW) {
                val hs = handles(sel, w, h)
                val edgeW = hypot(c[2] - c[0], c[3] - c[1]); val edgeH = hypot(c[6] - c[0], c[7] - c[1])
                // rotate handle: a line from the top edge and a round accent knob
                val tx = (c[0] + c[2]) / 2f; val ty = (c[1] + c[3]) / 2f
                canvas.drawLine(tx, ty, hs[6][0], hs[6][1], boxPaint)
                handlePaint.color = 0xFF19D3C5.toInt()
                canvas.drawCircle(hs[6][0], hs[6][1], dp(9f), handlePaint)
                handlePaint.color = Color.WHITE
                rotArc.set(hs[6][0] - dp(4.5f), hs[6][1] - dp(4.5f), hs[6][0] + dp(4.5f), hs[6][1] + dp(4.5f))
                canvas.drawArc(rotArc, -60f, 280f, false, rotPaint)
                // corners (resize): small white dots
                for (i in 0 until 4) {
                    canvas.drawCircle(hs[i][0], hs[i][1], dp(6f), handlePaint)
                    canvas.drawCircle(hs[i][0], hs[i][1], dp(6f), handleRing)
                }
                // sides (stretch): short bars, only when the box is big enough to tell them apart
                val ang = Math.toDegrees(atan2((c[3] - c[1]).toDouble(), (c[2] - c[0]).toDouble())).toFloat()
                if (edgeH > dp(56f)) drawBar(canvas, hs[4][0], hs[4][1], ang + 90f)
                if (edgeW > dp(56f)) drawBar(canvas, hs[5][0], hs[5][1], ang)
            }
            if (sel.keyframes.isNotEmpty()) {
                val on = LayerRenderer.keyframeAt(sel, timeMs) != null
                val x = (c[0] + c[2]) / 2f; val y = (c[1] + c[3]) / 2f - dp(40f)
                val d = Path().apply { moveTo(x, y - dp(6f)); lineTo(x + dp(6f), y); lineTo(x, y + dp(6f)); lineTo(x - dp(6f), y); close() }
                keyPaint.style = if (on) Paint.Style.FILL else Paint.Style.STROKE
                keyPaint.strokeWidth = dp(2f)
                canvas.drawPath(d, keyPaint)
            }
            if (showVGuide) canvas.drawLine(w / 2f, 0f, w / 2f, h.toFloat(), guidePaint)
            if (showHGuide) canvas.drawLine(0f, h / 2f, w.toFloat(), h / 2f, guidePaint)
        }

        private val rotArc = RectF()
        private val rotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.8f); color = Color.WHITE; strokeCap = Paint.Cap.ROUND }
        private val barRect = RectF()

        private fun drawBar(canvas: Canvas, x: Float, y: Float, deg: Float) {
            canvas.save(); canvas.rotate(deg, x, y)
            barRect.set(x - dp(9f), y - dp(3.5f), x + dp(9f), y + dp(3.5f))
            canvas.drawRoundRect(barRect, dp(3.5f), dp(3.5f), handlePaint)
            canvas.drawRoundRect(barRect, dp(3.5f), dp(3.5f), handleRing)
            canvas.restore()
        }

        /** Handle positions: 0-3 corners (scale), 4 right side (width), 5 bottom side (height), 6 rotate. */
        private fun handles(l: Layer, w: Int, h: Int): List<FloatArray> {
            val c = LayerRenderer.corners(l, LayerRenderer.basePose(l, timeMs), w, h)
            val list = ArrayList<FloatArray>()
            for (i in 0 until 4) list.add(floatArrayOf(c[i * 2], c[i * 2 + 1]))
            list.add(floatArrayOf((c[2] + c[4]) / 2f, (c[3] + c[5]) / 2f))
            list.add(floatArrayOf((c[4] + c[6]) / 2f, (c[5] + c[7]) / 2f))
            // rotate handle sits above the top edge
            val tx = (c[0] + c[2]) / 2f; val ty = (c[1] + c[3]) / 2f
            val bx = (c[4] + c[6]) / 2f; val by = (c[5] + c[7]) / 2f
            val len = hypot(tx - bx, ty - by).coerceAtLeast(1f)
            list.add(floatArrayOf(tx + (tx - bx) / len * dp(24f), ty + (ty - by) / len * dp(24f)))
            return list
        }

        private fun drawTargetBox(canvas: Canvas, t: CanvasTarget, w: Int, h: Int) {
            val v = t.get()
            val p = project ?: return
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
        private var handle = -1
        private var multiTap: String? = null
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
        private var stroke: Stroke? = null

        private var editLast: FloatArray? = null
        private var editSpan = 0f
        private var cropEdge = -1

        private var maskHandle = 0      // 0 move, 1 height, 2 width, 3 round, 4 feather
        private var editAngle = 0f

        /** Mask handle positions in layer-content pixels: centre, ↕ top, ↔ right, ◜ round, ≈ feather. */
        private fun maskHandles(m: Layer, cw: Float, ch: Float): List<FloatArray> {
            val b = LayerRenderer.maskBox(m, cw, ch)
            val off = minOf(cw, ch) * 0.07f
            val rad = Math.toRadians(m.maskRot.toDouble())
            fun at(x: Float, y: Float): FloatArray {   // rotate around the mask centre
                val c = kotlin.math.cos(rad).toFloat(); val s = kotlin.math.sin(rad).toFloat()
                return floatArrayOf(b[0] + x * c - y * s, b[1] + x * s + y * c)
            }
            return listOf(at(0f, 0f), at(0f, -b[3] - off), at(b[2] + off, 0f), at(-b[2] - off * 0.7f, -b[3] - off * 0.7f), at(0f, b[3] + off))
        }

        private fun editTouch(e: MotionEvent, w: Int, h: Int) {
            val m = editMask
            val c = editCrop
            val l = m ?: c ?: return
            val (cw, ch) = LayerRenderer.contentSize(l, w)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    editLast = LayerRenderer.toLocal(l, timeMs, w, h, e.x, e.y)
                    if (m != null) {
                        val mat = LayerRenderer.matrix(m, LayerRenderer.basePose(m, timeMs), w, h)
                        val hs = maskHandles(m, cw, ch).map { p -> floatArrayOf(p[0], p[1]).also { mat.mapPoints(it) } }
                        maskHandle = 0
                        var best = dp(30f)
                        for (i in 1 until hs.size) {
                            if (i == 3 && m.mask != MaskKind.RECT) continue
                            val d = hypot(e.x - hs[i][0], e.y - hs[i][1]); if (d < best) { best = d; maskHandle = i }
                        }
                    }
                    if (c != null) {
                        val k = LayerRenderer.corners(l, LayerRenderer.basePose(l, timeMs), w, h)
                        val mids = listOf(floatArrayOf((k[0] + k[6]) / 2, (k[1] + k[7]) / 2), floatArrayOf((k[0] + k[2]) / 2, (k[1] + k[3]) / 2),
                            floatArrayOf((k[2] + k[4]) / 2, (k[3] + k[5]) / 2), floatArrayOf((k[4] + k[6]) / 2, (k[5] + k[7]) / 2))
                        cropEdge = mids.indices.minByOrNull { hypot(e.x - mids[it][0], e.y - mids[it][1]) } ?: -1
                    }
                }
                MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount >= 2) { editSpan = span(e); editAngle = angle(e) }
                MotionEvent.ACTION_MOVE -> {
                    if (m != null && e.pointerCount >= 2) {
                        // pinch = size, twist = rotate (like CapCut)
                        val sp = span(e); val an = angle(e)
                        if (editSpan > 0f) { m.maskSize = (m.maskSize * sp / editSpan).coerceIn(0.02f, 4f); m.maskRot += an - editAngle }
                        editSpan = sp; editAngle = an
                        onEditChanged?.invoke(); invalidate(); return
                    }
                    val cur = LayerRenderer.toLocal(l, timeMs, w, h, e.x, e.y) ?: return
                    val last = editLast ?: cur
                    if (m != null) {
                        val b = LayerRenderer.maskBox(m, cw, ch)
                        val rad = Math.toRadians(-m.maskRot.toDouble())
                        val px = cur[0] * cw - b[0]; val py = cur[1] * ch - b[1]
                        val rx = (px * kotlin.math.cos(rad) - py * kotlin.math.sin(rad)).toFloat()
                        val ry = (px * kotlin.math.sin(rad) + py * kotlin.math.cos(rad)).toFloat()
                        val minSide = minOf(cw, ch); val off = minSide * 0.07f
                        when (maskHandle) {
                            1 -> { val oldW = b[2]; val hh = (abs(ry) - off).coerceAtLeast(minSide * 0.01f); m.maskSize = (hh * 2 / minSide).coerceIn(0.02f, 4f); m.maskStretch = (oldW / hh).coerceIn(0.05f, 20f) }
                            2 -> { val hw = (abs(rx) - off).coerceAtLeast(minSide * 0.01f); m.maskStretch = (hw / b[3]).coerceIn(0.05f, 20f) }
                            3 -> { m.maskRound = ((rx + b[2]) / minOf(b[2], b[3])).coerceIn(0f, 1f) }
                            4 -> { m.maskFeather = ((ry - b[3]) / (minSide * 0.3f)).coerceIn(0f, 1f) }
                            else -> { m.maskX = (m.maskX + cur[0] - last[0]).coerceIn(-0.5f, 1.5f); m.maskY = (m.maskY + cur[1] - last[1]).coerceIn(-0.5f, 1.5f) }
                        }
                    } else if (c != null) {
                        val dx = cur[0] - last[0]; val dy = cur[1] - last[1]
                        val cwF = (1f - c.cropL - c.cropR).coerceAtLeast(0.05f); val chF = (1f - c.cropT - c.cropB).coerceAtLeast(0.05f)
                        when (cropEdge) {
                            0 -> c.cropL = (c.cropL + dx * cwF).coerceIn(0f, 0.9f - c.cropR)
                            1 -> c.cropT = (c.cropT + dy * chF).coerceIn(0f, 0.9f - c.cropB)
                            2 -> c.cropR = (c.cropR - dx * cwF).coerceIn(0f, 0.9f - c.cropL)
                            3 -> c.cropB = (c.cropB - dy * chF).coerceIn(0f, 0.9f - c.cropT)
                        }
                        val nw = (1f - c.cropL - c.cropR).coerceAtLeast(0.05f); val nh = (1f - c.cropT - c.cropB).coerceAtLeast(0.05f)
                        c.contentAspect = c.srcAspect * nh / nw
                    }
                    editLast = LayerRenderer.toLocal(l, timeMs, w, h, e.x, e.y)
                    onEditChanged?.invoke(); invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { editLast = null; editSpan = 0f; cropEdge = -1; maskHandle = 0 }
            }
        }

        private val editPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = 0xFFFFCC00.toInt(); strokeWidth = dp(2f)
        }
        private val editDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val editGlyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF202027.toInt(); textAlign = Paint.Align.CENTER; textSize = dp(13f); isFakeBoldText = true }
        private val editShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55000000 }

        private fun knob(canvas: Canvas, x: Float, y: Float, glyph: String) {
            canvas.drawCircle(x, y + dp(1f), dp(13f), editShadow)
            canvas.drawCircle(x, y, dp(12f), editDot)
            canvas.drawText(glyph, x, y + dp(4.5f), editGlyph)
        }

        private fun drawEdit(canvas: Canvas, w: Int, h: Int) {
            val m = editMask
            if (m != null && m.mask != MaskKind.NONE) {
                val (cw, ch) = LayerRenderer.contentSize(m, w)
                val mat = LayerRenderer.matrix(m, LayerRenderer.basePose(m, timeMs), w, h)
                val b = LayerRenderer.maskBox(m, cw, ch)
                canvas.save(); canvas.concat(mat)
                canvas.save(); canvas.rotate(m.maskRot, b[0], b[1])
                val rect = RectF(b[0] - b[2], b[1] - b[3], b[0] + b[2], b[1] + b[3])
                when (m.mask) {
                    MaskKind.RECT -> { val r = minOf(b[2], b[3]) * m.maskRound; canvas.drawRoundRect(rect, r, r, editPaint) }
                    MaskKind.LINEAR -> canvas.drawLine(b[0] - cw * 2, b[1], b[0] + cw * 2, b[1], editPaint)
                    MaskKind.MIRROR -> { canvas.drawLine(b[0] - cw * 2, b[1] - b[3], b[0] + cw * 2, b[1] - b[3], editPaint); canvas.drawLine(b[0] - cw * 2, b[1] + b[3], b[0] + cw * 2, b[1] + b[3], editPaint) }
                    else -> canvas.drawOval(rect, editPaint)
                }
                canvas.restore(); canvas.restore()
                // handles in screen space so they stay the same size
                val hs = maskHandles(m, cw, ch).map { p -> floatArrayOf(p[0], p[1]).also { mat.mapPoints(it) } }
                canvas.drawCircle(hs[0][0], hs[0][1], dp(7f), editPaint)
                knob(canvas, hs[1][0], hs[1][1], "↕")
                if (m.mask != MaskKind.LINEAR) knob(canvas, hs[2][0], hs[2][1], "↔")
                if (m.mask == MaskKind.RECT) knob(canvas, hs[3][0], hs[3][1], "◜")
                knob(canvas, hs[4][0], hs[4][1], "≈")
            }
            val c = editCrop
            if (c != null) {
                val k = LayerRenderer.corners(c, LayerRenderer.basePose(c, timeMs), w, h)
                val path = Path().apply { moveTo(k[0], k[1]); lineTo(k[2], k[3]); lineTo(k[4], k[5]); lineTo(k[6], k[7]); close() }
                canvas.drawPath(path, editPaint)
                val mids = listOf((k[0] + k[6]) / 2 to (k[1] + k[7]) / 2, (k[0] + k[2]) / 2 to (k[1] + k[3]) / 2,
                    (k[2] + k[4]) / 2 to (k[3] + k[5]) / 2, (k[4] + k[6]) / 2 to (k[5] + k[7]) / 2)
                for ((x, y) in mids) { canvas.drawCircle(x, y, dp(9f), editDot); canvas.drawCircle(x, y, dp(9f), handleRing) }
            }
        }

        private fun pickColor(p: Project, x: Float, y: Float): Boolean {
            val cb = colorPicker ?: return false
            val l = p.layers.firstOrNull { it.id == selectedLayerId } ?: return true
            val loc = LayerRenderer.toLocal(l, timeMs, width, height, x, y) ?: return true
            val src: Bitmap? = when (l.kind) {
                LayerKind.VIDEO -> videoFrame(l, width)
                LayerKind.IMAGE -> l.uri?.let { MediaUtils.loadBitmapCached(context, Uri.parse(it), 900) }
                else -> null
            }
            if (src != null) {
                val px = (loc[0] * src.width).toInt().coerceIn(0, src.width - 1)
                val py = (loc[1] * src.height).toInt().coerceIn(0, src.height - 1)
                cb(src.getPixel(px, py) or 0xFF000000.toInt())
            }
            colorPicker = null
            return true
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val p = project ?: return false
            val w = width; val h = height
            // brush drawing
            val b = brush
            if (b != null) {
                val l = p.layers.firstOrNull { it.id == b.layerId } ?: return true
                val loc = LayerRenderer.toLocal(l, timeMs, w, h, e.x, e.y) ?: return true
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        val s = Stroke(color = b.color, width = b.width, eraser = b.eraser, type = b.type)
                        s.points.add(loc[0]); s.points.add(loc[1])
                        l.strokes.add(s); stroke = s
                    }
                    MotionEvent.ACTION_MOVE -> stroke?.let { it.points.add(loc[0]); it.points.add(loc[1]) }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { stroke = null; listener?.onLayerTransformed() }
                }
                invalidate()
                return true
            }
            if (editMask != null || editCrop != null) { editTouch(e, w, h); return true }
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (colorPicker != null) { pickColor(p, e.x, e.y); return true }
                    downX = e.x; downY = e.y; moved = false; handle = -1
                    snapOffX = 0f; snapOffY = 0f; snapOffR = 0f
                    poses.clear()
                    val cur = p.layers.firstOrNull { it.id == selectedLayerId }
                    // handles of the selected layer first
                    if (cur != null && cur.isActive(timeMs) && !cur.isEffect() && cur.kind != LayerKind.DRAW) {
                        val hs = handles(cur, w, h)
                        for ((i, hp) in hs.withIndex()) if (hypot(e.x - hp[0], e.y - hp[1]) < dp(20f)) { handle = i; break }
                    }
                    val target: Layer? = if (handle >= 0) cur else {
                        val hit = LayerRenderer.drawOrder(p.layers).reversed().firstOrNull {
                            it.isActive(timeMs) && LayerRenderer.hitTest(it, timeMs, w, h, e.x, e.y, dp(12f))
                        }
                        if (cur != null && cur.isActive(timeMs) && LayerRenderer.hitBox(cur, timeMs, w, h, e.x, e.y, dp(16f))) cur else hit
                    }
                    if (multiMode) {
                        multiTap = null
                        if (target != null) {
                            if (target.id in so.ijarjar.app.model.MultiSelect.ids) { if (handle < 0) multiTap = target.id }
                            else so.ijarjar.app.model.MultiSelect.ids.add(target.id)
                            selectedLayerId = target.id
                            listener?.onMultiChanged()
                            for (g in p.linkedWith(target)) poses[g.id] = LayerRenderer.basePose(g, timeMs)
                        }
                        active = target; movingTarget = false
                        lastX = e.x; lastY = e.y; pointerMode = 1
                        invalidate()
                        return true
                    }
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
                        pointerMode = 2; handle = -1
                        lastSpan = span(e); lastAngle = angle(e)
                        lastX = focusX(e); lastY = focusY(e)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val a = active
                    if (a != null && handle >= 0 && pointerMode == 1) {
                        dragHandle(p, a, e.x, e.y, w, h)
                        lastX = e.x; lastY = e.y; moved = true
                        listener?.onLayerTransforming(); refresh()
                        return true
                    }
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
                    if (a != null) {
                        applyTransform(p.linkedWith(a), a, dx, dy, s, dr, fx, fy, w, h)
                    } else if (movingTarget) {
                        val t = canvasTarget ?: return true
                        val v = t.get()
                        if (s != 1f || dr != 0f) {
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
                    val tapped = multiTap
                    if (multiMode && !moved && tapped != null && e.actionMasked == MotionEvent.ACTION_UP) {
                        // tapping a picked layer again takes it out
                        so.ijarjar.app.model.MultiSelect.ids.remove(tapped)
                        if (selectedLayerId == tapped) selectedLayerId = so.ijarjar.app.model.MultiSelect.ids.lastOrNull()
                        listener?.onMultiChanged()
                    }
                    multiTap = null
                    active = null; movingTarget = false; pointerMode = 0; handle = -1
                    invalidate()
                }
            }
            return true
        }

        /** Corner = scale, side = stretch width / height, top knob = rotate. Linked layers follow. */
        private fun dragHandle(p: Project, a: Layer, x: Float, y: Float, w: Int, h: Int) {
            val group = p.linkedWith(a)
            for (l in group) if (!poses.containsKey(l.id)) poses[l.id] = LayerRenderer.basePose(l, timeMs)
            val mp = poses[a.id] ?: return
            val cx = mp.cx * w; val cy = mp.cy * h
            when (handle) {
                in 0..3 -> {
                    val d0 = hypot(lastX - cx, lastY - cy).coerceAtLeast(1f)
                    val d1 = hypot(x - cx, y - cy)
                    applyTransform(group, a, 0f, 0f, d1 / d0, 0f, cx, cy, w, h)
                    return
                }
                6 -> {
                    val a0 = Math.toDegrees(atan2((lastY - cy).toDouble(), (lastX - cx).toDouble())).toFloat()
                    val a1 = Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble())).toFloat()
                    var dr = a1 - a0
                    if (dr > 180) dr -= 360f
                    if (dr < -180) dr += 360f
                    applyTransform(group, a, 0f, 0f, 1f, dr, cx, cy, w, h)
                    return
                }
                else -> {
                    // distance along the layer's own axis
                    val rad = Math.toRadians(mp.rotation.toDouble())
                    val ax = if (handle == 4) cos(rad).toFloat() else -sin(rad).toFloat()
                    val ay = if (handle == 4) sin(rad).toFloat() else cos(rad).toFloat()
                    val d0 = ((lastX - cx) * ax + (lastY - cy) * ay).coerceAtLeast(4f)
                    val d1 = ((x - cx) * ax + (y - cy) * ay).coerceAtLeast(4f)
                    val f = d1 / d0
                    for (l in group) poses[l.id]?.let { q ->
                        if (handle == 4) q.sx = (q.sx * f).coerceIn(0.05f, 20f) else q.sy = (q.sy * f).coerceIn(0.05f, 20f)
                        LayerRenderer.writePose(l, timeMs, q)
                    }
                }
            }
        }

        private fun applyTransform(group: List<Layer>, main: Layer, dx: Float, dy: Float, s: Float, dr: Float,
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
            if (handle < 0) {
                val snap = dp(8f)
                var sx = 0f; var sy = 0f
                showVGuide = abs(mp.cx * w - w / 2f) < snap
                showHGuide = abs(mp.cy * h - h / 2f) < snap
                if (showVGuide) sx = 0.5f - mp.cx
                if (showHGuide) sy = 0.5f - mp.cy
                if (sx != 0f || sy != 0f) for (l in group) poses[l.id]?.let { it.cx += sx; it.cy += sy }
                snapOffX = sx; snapOffY = sy
            }
            if (pointerMode == 2 || handle == 6) {
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

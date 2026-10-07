package so.ijarjar.app.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.util.LruCache
import so.ijarjar.app.L
import so.ijarjar.app.media.MediaUtils
import so.ijarjar.app.model.Keyframe
import so.ijarjar.app.model.Layer
import so.ijarjar.app.model.LayerAnim
import so.ijarjar.app.model.LayerKind
import so.ijarjar.app.model.LoopAnim
import so.ijarjar.app.model.MaskKind
import so.ijarjar.app.model.ShapeKind
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sin

/** Where a layer is at one moment: keyframes + animations applied. */
class Pose(
    var cx: Float, var cy: Float, var scale: Float, var rotation: Float, var opacity: Float,
    var visibleChars: Int = -1
)

/**
 * Draws layers. Used by both the live preview and the exporters so the result matches.
 * All positions are relative to the canvas size, so any resolution gives the same picture.
 */
object LayerRenderer {

    val FONTS: List<String>
        get() = listOf(L.t("Caadi", "Default"), "Serif", "Mono", L.t("Far-qoraal", "Script"),
            L.t("Cidhiidhi", "Condensed"), L.t("Khafiif", "Light"), L.t("Culus", "Black"), L.t("Dhexe", "Medium"))

    fun typeface(font: Int, bold: Boolean): Typeface {
        val style = if (bold) Typeface.BOLD else Typeface.NORMAL
        return when (font) {
            1 -> Typeface.create(Typeface.SERIF, style)
            2 -> Typeface.create(Typeface.MONOSPACE, style)
            3 -> Typeface.create("cursive", style)
            4 -> Typeface.create("sans-serif-condensed", style)
            5 -> Typeface.create("sans-serif-light", style)
            6 -> Typeface.create("sans-serif-black", style)
            7 -> Typeface.create("sans-serif-medium", style)
            else -> Typeface.create(Typeface.SANS_SERIF, style)
        }
    }

    // ------------------------------------------------------------------ pose (keyframes + animation)

    private fun ease(x: Float): Float { val f = x.coerceIn(0f, 1f); return f * f * (3 - 2 * f) }
    private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f

    private fun lerpAngle(a: Float, b: Float, f: Float): Float {
        var d = b - a
        while (d > 180) d -= 360f
        while (d < -180) d += 360f
        return a + d * f
    }

    /** Base transform at time t from keyframes (or the static values). Used for editing. */
    fun basePose(l: Layer, t: Long): Pose {
        val ks = l.keyframes
        if (ks.isEmpty()) return Pose(l.cx, l.cy, l.scale, l.rotation, l.opacity)
        val rel = t - l.startMs
        val sorted = ks.sortedBy { it.t }
        val first = sorted.first(); val last = sorted.last()
        if (rel <= first.t) return poseOf(first)
        if (rel >= last.t) return poseOf(last)
        for (k in 0 until sorted.size - 1) {
            val a = sorted[k]; val b = sorted[k + 1]
            if (rel >= a.t && rel <= b.t) {
                val f = ease(if (b.t == a.t) 1f else (rel - a.t).toFloat() / (b.t - a.t))
                return Pose(lerp(a.cx, b.cx, f), lerp(a.cy, b.cy, f), lerp(a.scale, b.scale, f),
                    lerpAngle(a.rotation, b.rotation, f), lerp(a.opacity, b.opacity, f))
            }
        }
        return poseOf(last)
    }

    private fun poseOf(k: Keyframe) = Pose(k.cx, k.cy, k.scale, k.rotation, k.opacity)

    /** Full pose: keyframes + in/out + loop animations. Used for drawing. */
    fun poseAt(l: Layer, t: Long): Pose {
        val p = basePose(l, t)
        val dur = l.durationMs
        val inMs = minOf(l.animInMs, dur / 2).coerceAtLeast(1)
        val outMs = minOf(l.animOutMs, dur / 2).coerceAtLeast(1)
        val sinceStart = t - l.startMs
        val untilEnd = l.endMs - t
        if (l.animIn != LayerAnim.NONE && sinceStart < inMs) animate(p, l, l.animIn, sinceStart.toFloat() / inMs, true)
        if (l.animOut != LayerAnim.NONE && untilEnd < outMs) animate(p, l, l.animOut, untilEnd.toFloat() / outMs, false)
        if (l.animLoop != LoopAnim.NONE) loop(p, l.animLoop, sinceStart / 1000f)
        return p
    }

    private fun animate(p: Pose, l: Layer, a: LayerAnim, raw: Float, isIn: Boolean) {
        val f = ease(raw)
        val g = 1f - f
        when (a) {
            LayerAnim.NONE -> {}
            LayerAnim.FADE -> p.opacity *= f
            LayerAnim.SLIDE_UP -> { p.cy += g * 0.25f; p.opacity *= f }
            LayerAnim.SLIDE_DOWN -> { p.cy -= g * 0.25f; p.opacity *= f }
            LayerAnim.SLIDE_LEFT -> { p.cx += g * 0.4f; p.opacity *= f }
            LayerAnim.SLIDE_RIGHT -> { p.cx -= g * 0.4f; p.opacity *= f }
            LayerAnim.ZOOM -> { p.scale *= 0.2f + 0.8f * f; p.opacity *= f }
            LayerAnim.POP -> {
                val x = raw.coerceIn(0f, 1f)
                val s = if (x < 0.7f) x / 0.7f * 1.15f else 1.15f - (x - 0.7f) / 0.3f * 0.15f
                p.scale *= s.coerceAtLeast(0.01f)
                p.opacity *= minOf(1f, x * 3f)
            }
            LayerAnim.SPIN -> { p.rotation += g * (if (isIn) -360f else 360f); p.scale *= 0.3f + 0.7f * f; p.opacity *= f }
            LayerAnim.TYPEWRITER -> {
                if (l.isTextLike()) p.visibleChars = (l.text.length * raw.coerceIn(0f, 1f)).toInt()
                else p.opacity *= f
            }
        }
    }

    private fun loop(p: Pose, a: LoopAnim, s: Float) {
        val tau = 2f * PI.toFloat()
        when (a) {
            LoopAnim.NONE -> {}
            LoopAnim.PULSE -> p.scale *= 1f + 0.08f * sin(s * tau * 1.2f)
            LoopAnim.SWING -> p.rotation += 10f * sin(s * tau * 0.8f)
            LoopAnim.FLOAT -> p.cy += 0.015f * sin(s * tau * 0.6f)
            LoopAnim.BLINK -> p.opacity *= 0.25f + 0.75f * (0.5f + 0.5f * sin(s * tau * 1.5f))
            LoopAnim.ROTATE -> p.rotation += (s * 90f) % 360f
            LoopAnim.SHAKE -> { p.cx += 0.006f * sin(s * 47f); p.cy += 0.005f * sin(s * 61f) }
            LoopAnim.HEARTBEAT -> {
                val ph = s % 1f
                val beat = if (ph < 0.12f) sin(ph / 0.12f * PI.toFloat()) else if (ph in 0.2f..0.32f) 0.6f * sin((ph - 0.2f) / 0.12f * PI.toFloat()) else 0f
                p.scale *= 1f + 0.12f * beat
            }
        }
    }

    // ------------------------------------------------------------------ text

    private val textCache = object : LruCache<String, Bitmap>(28 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private fun hidden(raw: String, visibleChars: Int): CharSequence {
        if (visibleChars < 0 || visibleChars >= raw.length) return raw
        val s = SpannableString(raw)
        s.setSpan(ForegroundColorSpan(Color.TRANSPARENT), visibleChars, raw.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return s
    }

    fun textBitmap(l: Layer, canvasW: Int, visibleChars: Int = -1): Bitmap {
        val key = "${l.text}|${l.textColor}|${l.textColor2}|${l.strokeColor}|${l.strokeWidth}|${l.bgColor}|${l.textSizeFrac}|" +
            "${l.bold}|${l.font}|${l.align}|${l.shadow}|${l.depth}|${l.depthColor}|${l.letterSpacing}|$canvasW|${l.kind}|$visibleChars"
        textCache.get(key)?.let { return it }
        val textPx = (l.textSizeFrac * canvasW).coerceAtLeast(6f)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = textPx
            typeface = typeface(l.font, l.bold)
            color = l.textColor
            letterSpacing = l.letterSpacing
        }
        val raw = l.text.ifEmpty { " " }
        val maxW = (canvasW * 0.95f).toInt().coerceAtLeast(10)
        var widest = 1f
        for (line in raw.split("\n")) widest = maxOf(widest, paint.measureText(line))
        val layoutW = ceil(minOf(widest, maxW.toFloat())).toInt().coerceAtLeast(1)
        val alignment = when (l.align) {
            0 -> Layout.Alignment.ALIGN_NORMAL
            2 -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_CENTER
        }
        fun build(p: TextPaint) = StaticLayout.Builder.obtain(hidden(raw, visibleChars), 0, raw.length, p, layoutW)
            .setAlignment(alignment).setIncludePad(true).build()

        val layout = build(paint)
        val pad = (textPx * 0.3f).toInt()
        val depthPx = (l.depth * textPx * 0.25f).toInt()
        val bw = layoutW + pad * 2 + depthPx
        val bh = layout.height + pad * 2 + depthPx
        val bmp = Bitmap.createBitmap(bw.coerceAtLeast(1), bh.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (l.bgColor != 0) {
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = l.bgColor }
            c.drawRoundRect(RectF(0f, 0f, (bw - depthPx).toFloat(), (bh - depthPx).toFloat()), textPx * 0.25f, textPx * 0.25f, bg)
        }
        c.save()
        c.translate(pad.toFloat(), pad.toFloat())
        // 3D extrusion: the text repeated in a darker colour, stepping down-right
        if (depthPx > 0) {
            val dp = TextPaint(paint).apply { color = l.depthColor; shader = null }
            val dl = build(dp)
            for (i in depthPx downTo 1) {
                c.save(); c.translate(i.toFloat(), i.toFloat()); dl.draw(c); c.restore()
            }
        }
        if (l.shadow) {
            val sp = TextPaint(paint).apply {
                color = 0x99000000.toInt()
                maskFilter = BlurMaskFilter(textPx * 0.08f, BlurMaskFilter.Blur.NORMAL)
            }
            c.save(); c.translate(textPx * 0.05f, textPx * 0.07f); build(sp).draw(c); c.restore()
        }
        if (l.strokeColor != 0) {
            val sp = TextPaint(paint).apply {
                style = Paint.Style.STROKE
                strokeWidth = textPx * l.strokeWidth.coerceIn(0.01f, 0.5f)
                strokeJoin = Paint.Join.ROUND
                color = l.strokeColor
            }
            build(sp).draw(c)
        }
        if (l.textColor2 != 0) {
            paint.shader = LinearGradient(0f, 0f, 0f, layout.height.toFloat(), l.textColor, l.textColor2, Shader.TileMode.CLAMP)
            build(paint).draw(c)
        } else layout.draw(c)
        c.restore()
        textCache.put(key, bmp)
        return bmp
    }

    // ------------------------------------------------------------------ shapes

    fun shapeBitmap(l: Layer, canvasW: Int): Bitmap {
        val (cw, ch) = contentSize(l, canvasW)
        val scaleDown = (1024f / maxOf(cw, ch)).coerceAtMost(1f)
        val w = (cw * scaleDown).toInt().coerceAtLeast(4)
        val h = (ch * scaleDown).toInt().coerceAtLeast(4)
        val key = "shape|${l.shape}|${l.textColor}|${l.textColor2}|${l.strokeColor}|${l.strokeWidth}|${l.shadow}|$w|$h"
        textCache.get(key)?.let { return it }
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val sw = if (l.strokeColor != 0) minOf(w, h) * l.strokeWidth.coerceIn(0.01f, 0.3f) * 0.5f else 0f
        val inset = sw / 2f + 1f
        val r = RectF(inset, inset, w - inset, h - inset)
        val path = shapePath(l.shape, r)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = l.textColor
            if (l.textColor2 != 0) shader = LinearGradient(0f, 0f, 0f, h.toFloat(), l.textColor, l.textColor2, Shader.TileMode.CLAMP)
            if (l.shape == ShapeKind.LINE) { style = Paint.Style.STROKE; strokeWidth = h * 0.8f; strokeCap = Paint.Cap.ROUND }
        }
        c.drawPath(path, fill)
        if (sw > 0f && l.shape != ShapeKind.LINE) {
            c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = sw; color = l.strokeColor; strokeJoin = Paint.Join.ROUND
            })
        }
        textCache.put(key, b)
        return b
    }

    private fun shapePath(kind: ShapeKind, r: RectF): Path {
        val p = Path()
        val cx = r.centerX(); val cy = r.centerY()
        when (kind) {
            ShapeKind.RECT -> p.addRect(r, Path.Direction.CW)
            ShapeKind.ROUND_RECT -> { val rad = minOf(r.width(), r.height()) * 0.2f; p.addRoundRect(r, rad, rad, Path.Direction.CW) }
            ShapeKind.CIRCLE -> p.addOval(r, Path.Direction.CW)
            ShapeKind.TRIANGLE -> { p.moveTo(cx, r.top); p.lineTo(r.right, r.bottom); p.lineTo(r.left, r.bottom); p.close() }
            ShapeKind.STAR -> {
                val ro = minOf(r.width(), r.height()) / 2f; val ri = ro * 0.45f
                for (i in 0 until 10) {
                    val a = Math.toRadians((-90 + i * 36).toDouble())
                    val rr = if (i % 2 == 0) ro else ri
                    val x = cx + (rr * Math.cos(a)).toFloat(); val y = cy + (rr * Math.sin(a)).toFloat()
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                p.close()
            }
            ShapeKind.HEART -> {
                val w = r.width(); val h = r.height(); val top = r.top
                p.moveTo(cx, top + h * 0.28f)
                p.cubicTo(cx, top, r.left, top, r.left, top + h * 0.3f)
                p.cubicTo(r.left, top + h * 0.6f, cx - w * 0.1f, top + h * 0.8f, cx, r.bottom)
                p.cubicTo(cx + w * 0.1f, top + h * 0.8f, r.right, top + h * 0.6f, r.right, top + h * 0.3f)
                p.cubicTo(r.right, top, cx, top, cx, top + h * 0.28f)
                p.close()
            }
            ShapeKind.ARROW -> {
                val hh = r.height()
                p.moveTo(r.left, cy - hh * 0.18f)
                p.lineTo(r.right - hh * 0.5f, cy - hh * 0.18f)
                p.lineTo(r.right - hh * 0.5f, r.top)
                p.lineTo(r.right, cy)
                p.lineTo(r.right - hh * 0.5f, r.bottom)
                p.lineTo(r.right - hh * 0.5f, cy + hh * 0.18f)
                p.lineTo(r.left, cy + hh * 0.18f)
                p.close()
            }
            ShapeKind.LINE -> { p.moveTo(r.left + r.height(), cy); p.lineTo(r.right - r.height(), cy) }
            ShapeKind.BUBBLE -> {
                val body = RectF(r.left, r.top, r.right, r.top + r.height() * 0.78f)
                val rad = minOf(body.width(), body.height()) * 0.3f
                p.addRoundRect(body, rad, rad, Path.Direction.CW)
                val tail = Path()
                tail.moveTo(r.left + r.width() * 0.25f, body.bottom - 2f)
                tail.lineTo(r.left + r.width() * 0.18f, r.bottom)
                tail.lineTo(r.left + r.width() * 0.42f, body.bottom - 2f)
                tail.close()
                p.op(tail, Path.Op.UNION)
            }
        }
        return p
    }

    // ------------------------------------------------------------------ geometry

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
    fun matrix(l: Layer, p: Pose, canvasW: Int, canvasH: Int): Matrix {
        val (cw, ch) = contentSize(l, canvasW)
        return Matrix().apply {
            postTranslate(-cw / 2f, -ch / 2f)
            postScale(if (l.flipH) -p.scale else p.scale, p.scale)
            postRotate(p.rotation)
            postTranslate(p.cx * canvasW, p.cy * canvasH)
        }
    }

    fun hitTest(l: Layer, t: Long, canvasW: Int, canvasH: Int, x: Float, y: Float, slopPx: Float): Boolean {
        if (l.isEffect()) return false
        val p = basePose(l, t)
        val inv = Matrix()
        if (!matrix(l, p, canvasW, canvasH).invert(inv)) return false
        val pt = floatArrayOf(x, y)
        inv.mapPoints(pt)
        val (cw, ch) = contentSize(l, canvasW)
        val s = slopPx / abs(p.scale).coerceAtLeast(0.05f)
        return pt[0] >= -s && pt[0] <= cw + s && pt[1] >= -s && pt[1] <= ch + s
    }

    /** Corner points of the layer box in canvas pixels (for selection outlines). */
    fun corners(l: Layer, p: Pose, canvasW: Int, canvasH: Int): FloatArray {
        val (cw, ch) = contentSize(l, canvasW)
        val pts = floatArrayOf(0f, 0f, cw, 0f, cw, ch, 0f, ch)
        matrix(l, p, canvasW, canvasH).mapPoints(pts)
        return pts
    }

    /** Overlay videos first, then drawn effects, then everything else in list order. */
    fun drawOrder(layers: List<Layer>): List<Layer> =
        layers.filter { it.kind == LayerKind.VIDEO } +
            layers.filter { it.isEffect() } +
            layers.filter { it.kind != LayerKind.VIDEO && !it.isEffect() }

    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /** Draws a single layer at time [t]. [content] is the image or video frame (ignored for text). */
    fun draw(context: Context, canvas: Canvas, l: Layer, t: Long, canvasW: Int, canvasH: Int, content: Bitmap?) {
        if (l.isEffect()) {
            if (EffectRenderer.isDrawn(l)) EffectRenderer.draw(canvas, l, t, canvasW, canvasH)
            return
        }
        val pose = poseAt(l, t)
        if (pose.opacity <= 0.003f) return
        val bmp: Bitmap = when {
            l.isTextLike() -> textBitmap(l, canvasW, pose.visibleChars)
            l.kind == LayerKind.SHAPE -> shapeBitmap(l, canvasW)
            content != null -> content
            l.kind == LayerKind.IMAGE && l.uri != null ->
                MediaUtils.loadBitmapCached(context, Uri.parse(l.uri), 1600) ?: return
            else -> return
        }
        val (cw, ch) = contentSize(l, canvasW)
        val m = matrix(l, pose, canvasW, canvasH)
        val pre = Matrix().apply { setScale(cw / bmp.width, ch / bmp.height) }
        pre.postConcat(m)
        bmpPaint.alpha = (pose.opacity.coerceIn(0f, 1f) * 255).toInt()
        if (l.mask == MaskKind.NONE || l.isTextLike()) {
            canvas.drawBitmap(bmp, pre, bmpPaint)
            return
        }
        // masked: draw into a layer, then keep only the mask shape
        val bounds = RectF(0f, 0f, cw, ch)
        m.mapRect(bounds)
        bounds.inset(-2f, -2f)
        val save = canvas.saveLayer(bounds, null)
        canvas.drawBitmap(bmp, pre, bmpPaint)
        val mask = maskBitmap(l, cw, ch)
        val mpre = Matrix().apply { setScale(cw / mask.width, ch / mask.height) }
        mpre.postConcat(m)
        canvas.drawBitmap(mask, mpre, maskPaint)
        canvas.restoreToCount(save)
    }

    // ------------------------------------------------------------------ masks

    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }

    private val maskCache = LruCache<String, Bitmap>(24)

    /** White-on-transparent mask in content space (stretched to the content). */
    fun maskBitmap(l: Layer, cw: Float, ch: Float): Bitmap {
        val aspect = (ch / cw.coerceAtLeast(1f)).coerceIn(0.05f, 20f)
        val mw = if (aspect <= 1f) 384 else (384 / aspect).toInt().coerceAtLeast(8)
        val mh = (mw * aspect).toInt().coerceAtLeast(8)
        val key = "${l.mask}|${l.maskSize}|${l.maskFeather}|${l.maskInvert}|$mw|$mh"
        maskCache.get(key)?.let { return it }
        val b = Bitmap.createBitmap(mw, mh, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val size = l.maskSize.coerceIn(0.05f, 1.5f)
        val minSide = minOf(mw, mh).toFloat()
        val featherPx = l.maskFeather.coerceIn(0f, 1f) * minSide * 0.25f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            if (featherPx > 0.5f && l.mask != MaskKind.LINEAR && l.mask != MaskKind.MIRROR) {
                maskFilter = BlurMaskFilter(featherPx, BlurMaskFilter.Blur.NORMAL)
            }
        }
        if (l.maskInvert) {
            c.drawColor(Color.WHITE)
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        }
        val cx = mw / 2f; val cy = mh / 2f
        when (l.mask) {
            MaskKind.NONE -> c.drawColor(Color.WHITE)
            MaskKind.CIRCLE -> c.drawCircle(cx, cy, minSide * size / 2f, paint)
            MaskKind.RECT -> {
                val hw = mw * size / 2f; val hh = mh * size / 2f
                c.drawRoundRect(RectF(cx - hw, cy - hh, cx + hw, cy + hh), minSide * 0.06f, minSide * 0.06f, paint)
            }
            MaskKind.HEART -> c.drawPath(shapePath(ShapeKind.HEART,
                RectF(cx - minSide * size / 2f, cy - minSide * size * 0.45f, cx + minSide * size / 2f, cy + minSide * size * 0.45f)), paint)
            MaskKind.STAR -> c.drawPath(shapePath(ShapeKind.STAR,
                RectF(cx - minSide * size / 2f, cy - minSide * size / 2f, cx + minSide * size / 2f, cy + minSide * size / 2f)), paint)
            MaskKind.LINEAR -> {
                val edge = mh * (size / 1.5f).coerceIn(0f, 1f)
                val f = featherPx.coerceAtLeast(1f)
                paint.shader = LinearGradient(0f, edge - f, 0f, edge + f, Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, mw.toFloat(), mh.toFloat(), paint)
            }
            MaskKind.MIRROR -> {
                val half = mh * (size / 1.5f).coerceIn(0.01f, 1f) / 2f
                val f = featherPx.coerceAtLeast(1f)
                val total = 2 * half + 2 * f
                paint.shader = LinearGradient(0f, cy - half - f, 0f, cy + half + f,
                    intArrayOf(Color.TRANSPARENT, Color.WHITE, Color.WHITE, Color.TRANSPARENT),
                    floatArrayOf(0f, (2 * f / total).coerceIn(0f, 0.49f), (1f - 2 * f / total).coerceIn(0.51f, 1f), 1f),
                    Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, mw.toFloat(), mh.toFloat(), paint)
            }
        }
        maskCache.put(key, b)
        return b
    }

    /** Writes a new transform for time [t]: keyframed layers get a keyframe, others change directly. */
    fun writePose(l: Layer, t: Long, p: Pose) {
        if (l.keyframes.isEmpty()) {
            l.cx = p.cx; l.cy = p.cy; l.scale = p.scale; l.rotation = p.rotation; l.opacity = p.opacity
            return
        }
        val rel = (t - l.startMs).coerceIn(0, l.durationMs)
        val k = l.keyframes.firstOrNull { abs(it.t - rel) < 60 }
        if (k != null) { k.cx = p.cx; k.cy = p.cy; k.scale = p.scale; k.rotation = p.rotation; k.opacity = p.opacity }
        else l.keyframes.add(Keyframe(rel, p.cx, p.cy, p.scale, p.rotation, p.opacity))
    }

    fun keyframeAt(l: Layer, t: Long): Keyframe? {
        val rel = t - l.startMs
        return l.keyframes.firstOrNull { abs(it.t - rel) < 60 }
    }
}

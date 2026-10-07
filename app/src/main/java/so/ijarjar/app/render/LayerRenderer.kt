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
import android.graphics.PorterDuffColorFilter
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
import so.ijarjar.app.media.AnimatedSource
import so.ijarjar.app.media.MediaUtils
import so.ijarjar.app.model.Keyframe
import so.ijarjar.app.model.Layer
import so.ijarjar.app.model.LayerAnim
import so.ijarjar.app.model.LayerKind
import so.ijarjar.app.model.LoopAnim
import so.ijarjar.app.model.MaskKind
import so.ijarjar.app.model.ShapeKind
import so.ijarjar.app.model.TextAnim
import so.ijarjar.app.model.TextLoop
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/** Where a layer is at one moment: keyframes + animations applied. */
class Pose(
    var cx: Float, var cy: Float, var scale: Float, var rotation: Float, var opacity: Float,
    var visibleChars: Int = -1,
    var blur: Float = 0f,       // 0..1, whole-layer blur animation
    var flipX: Float = 1f,      // squash for the "flip" animation
    var sx: Float = 1f,         // width stretch
    var sy: Float = 1f          // height stretch
)

/** Everything needed to draw a text layer (shared by the bitmap and the letter-by-letter paths). */
class TextSpec(
    val paint: TextPaint,
    val layout: StaticLayout,
    val layoutW: Int,
    val pad: Int,
    val depthPx: Int,
    val bw: Int,
    val bh: Int,
    val textPx: Float,
    val raw: String,
    val alignment: Layout.Alignment
)

/**
 * Draws layers. Used by both the live preview and the exporters so the result matches.
 * All positions are relative to the canvas size, so any resolution gives the same picture.
 */
object LayerRenderer {

    /** Preview draws pictures smaller than export. */
    var previewMaxDim = 900

    val FONTS: List<String>
        get() = listOf(L.t("Caadi", "Default"), "Serif", "Mono", L.t("Far-qoraal", "Script"),
            L.t("Cidhiidhi", "Condensed"), L.t("Khafiif", "Light"), L.t("Culus", "Black"), L.t("Dhexe", "Medium"),
            L.t("Khafiif-cidhiidhi", "Thin"), "Casual")

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
            8 -> Typeface.create("sans-serif-thin", style)
            9 -> Typeface.create("casual", style)
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
        if (ks.isEmpty()) return Pose(l.cx, l.cy, l.scale, l.rotation, l.opacity, sx = l.stretchX, sy = l.stretchY)
        var rel = t - l.startMs
        val sorted = ks.sortedBy { it.t }
        val first = sorted.first(); val last = sorted.last()
        val span = last.t - first.t
        if (span > 0 && rel > last.t) {
            if (l.expr == so.ijarjar.app.model.Expression.LOOP_CYCLE) rel = first.t + (rel - first.t) % span
            else if (l.expr == so.ijarjar.app.model.Expression.LOOP_PINGPONG) {
                val k = (rel - first.t) / span; val r = (rel - first.t) % span
                rel = if (k % 2 == 0L) first.t + r else last.t - r
            }
        }
        if (rel <= first.t) return poseOf(first)
        if (rel >= last.t) return poseOf(last)
        for (k in 0 until sorted.size - 1) {
            val a = sorted[k]; val b = sorted[k + 1]
            if (rel >= a.t && rel <= b.t) {
                val x = if (b.t == a.t) 1f else (rel - a.t).toFloat() / (b.t - a.t)
                val f = Ease.apply(a.ease, x, a.bx1, a.by1, a.bx2, a.by2)
                return Pose(lerp(a.cx, b.cx, f), lerp(a.cy, b.cy, f), lerp(a.scale, b.scale, f),
                    lerpAngle(a.rotation, b.rotation, f), lerp(a.opacity, b.opacity, f).coerceIn(0f, 1f),
                    sx = lerp(a.sx, b.sx, f), sy = lerp(a.sy, b.sy, f))
            }
        }
        return poseOf(last)
    }

    private fun poseOf(k: Keyframe) = Pose(k.cx, k.cy, k.scale, k.rotation, k.opacity, sx = k.sx, sy = k.sy)

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
        if (l.expr != so.ijarjar.app.model.Expression.NONE) Expressions.apply(p, l, t)
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
            LayerAnim.ZOOM_OUT -> { p.scale *= 1f + 1.5f * g; p.opacity *= f }
            LayerAnim.POP -> {
                p.scale *= Ease.apply(so.ijarjar.app.model.Easing.BACK, raw).coerceAtLeast(0.01f)
                p.opacity *= minOf(1f, raw * 3f)
            }
            LayerAnim.SPIN -> { p.rotation += g * (if (isIn) -360f else 360f); p.scale *= 0.3f + 0.7f * f; p.opacity *= f }
            LayerAnim.FLIP -> { p.flipX *= cos(g * PI.toFloat() / 2f).coerceAtLeast(0.01f); p.opacity *= minOf(1f, raw * 2f) }
            LayerAnim.DROP -> { p.cy -= (1f - Ease.apply(so.ijarjar.app.model.Easing.BOUNCE, raw)) * 0.35f; p.opacity *= minOf(1f, raw * 4f) }
            LayerAnim.BLUR -> { p.blur = maxOf(p.blur, g); p.opacity *= f }
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
    private val specCache = LruCache<String, TextSpec>(40)

    private fun hidden(raw: String, visibleChars: Int): CharSequence {
        if (visibleChars < 0 || visibleChars >= raw.length) return raw
        val s = SpannableString(raw)
        s.setSpan(ForegroundColorSpan(Color.TRANSPARENT), visibleChars, raw.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return s
    }

    private fun textKey(l: Layer, canvasW: Int) =
        "${l.text}|${l.textColor}|${l.textColor2}|${l.strokeColor}|${l.strokeWidth}|${l.bgColor}|${l.textSizeFrac}|" +
            "${l.bold}|${l.font}|${l.align}|${l.shadow}|${l.depth}|${l.depthColor}|${l.letterSpacing}|$canvasW|${l.kind}"

    fun textSpec(l: Layer, canvasW: Int): TextSpec {
        val key = textKey(l, canvasW)
        specCache.get(key)?.let { return it }
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
        val layout = StaticLayout.Builder.obtain(raw, 0, raw.length, paint, layoutW)
            .setAlignment(alignment).setIncludePad(true).build()
        val pad = (textPx * 0.3f).toInt()
        val depthPx = (l.depth * textPx * 0.25f).toInt()
        val spec = TextSpec(paint, layout, layoutW, pad, depthPx, layoutW + pad * 2 + depthPx, layout.height + pad * 2 + depthPx, textPx, raw, alignment)
        specCache.put(key, spec)
        return spec
    }

    fun textBitmap(l: Layer, canvasW: Int, visibleChars: Int = -1): Bitmap {
        val key = textKey(l, canvasW) + "|$visibleChars"
        textCache.get(key)?.let { return it }
        val sp = textSpec(l, canvasW)
        val textPx = sp.textPx
        val raw = sp.raw
        fun build(p: TextPaint) = StaticLayout.Builder.obtain(hidden(raw, visibleChars), 0, raw.length, p, sp.layoutW)
            .setAlignment(sp.alignment).setIncludePad(true).build()
        val paint = TextPaint(sp.paint)
        val bmp = Bitmap.createBitmap(sp.bw.coerceAtLeast(1), sp.bh.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (l.bgColor != 0) {
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = l.bgColor }
            c.drawRoundRect(RectF(0f, 0f, (sp.bw - sp.depthPx).toFloat(), (sp.bh - sp.depthPx).toFloat()), textPx * 0.25f, textPx * 0.25f, bg)
        }
        c.save()
        c.translate(sp.pad.toFloat(), sp.pad.toFloat())
        if (sp.depthPx > 0) {
            val dl = build(TextPaint(paint).apply { color = l.depthColor; shader = null })
            for (i in sp.depthPx downTo 1) { c.save(); c.translate(i.toFloat(), i.toFloat()); dl.draw(c); c.restore() }
        }
        if (l.shadow) {
            val shp = TextPaint(paint).apply { color = 0x99000000.toInt(); maskFilter = BlurMaskFilter(textPx * 0.08f, BlurMaskFilter.Blur.NORMAL) }
            c.save(); c.translate(textPx * 0.05f, textPx * 0.07f); build(shp).draw(c); c.restore()
        }
        if (l.strokeColor != 0) {
            build(TextPaint(paint).apply {
                style = Paint.Style.STROKE; strokeWidth = textPx * l.strokeWidth.coerceIn(0.01f, 0.5f)
                strokeJoin = Paint.Join.ROUND; color = l.strokeColor
            }).draw(c)
        }
        if (l.textColor2 != 0) paint.shader = LinearGradient(0f, 0f, 0f, sp.layout.height.toFloat(), l.textColor, l.textColor2, Shader.TileMode.CLAMP)
        build(paint).draw(c)
        c.restore()
        textCache.put(key, bmp)
        return bmp
    }

    /** True when the text must be drawn letter by letter at this moment. */
    fun usesGlyphs(l: Layer, t: Long): Boolean {
        if (l.kind != LayerKind.TEXT) return false
        if (l.textLoop != TextLoop.NONE) return true
        val dur = l.durationMs
        if (l.textIn != TextAnim.NONE && t - l.startMs < minOf(l.animInMs, dur / 2)) return true
        if (l.textOut != TextAnim.NONE && l.endMs - t < minOf(l.animOutMs, dur / 2)) return true
        return false
    }

    // ------------------------------------------------------------------ shapes & drawings

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
            if (l.shape == ShapeKind.RING) { style = Paint.Style.STROKE; strokeWidth = minOf(w, h) * 0.12f }
        }
        if (l.shape == ShapeKind.RING) {
            val rr = RectF(r); rr.inset(fill.strokeWidth / 2, fill.strokeWidth / 2)
            c.drawOval(rr, fill)
        } else c.drawPath(path, fill)
        if (sw > 0f && l.shape != ShapeKind.LINE) {
            c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = sw; color = l.strokeColor; strokeJoin = Paint.Join.ROUND
            })
        }
        textCache.put(key, b)
        return b
    }

    fun shapePath(kind: ShapeKind, r: RectF): Path {
        val p = Path()
        val cx = r.centerX(); val cy = r.centerY()
        when (kind) {
            ShapeKind.RECT -> p.addRect(r, Path.Direction.CW)
            ShapeKind.ROUND_RECT -> { val rad = minOf(r.width(), r.height()) * 0.2f; p.addRoundRect(r, rad, rad, Path.Direction.CW) }
            ShapeKind.CIRCLE, ShapeKind.RING -> p.addOval(r, Path.Direction.CW)
            ShapeKind.TRIANGLE -> { p.moveTo(cx, r.top); p.lineTo(r.right, r.bottom); p.lineTo(r.left, r.bottom); p.close() }
            ShapeKind.HEXAGON -> {
                for (i in 0 until 6) {
                    val a = Math.toRadians((60 * i - 90).toDouble())
                    val x = cx + (r.width() / 2 * Math.cos(a)).toFloat(); val y = cy + (r.height() / 2 * Math.sin(a)).toFloat()
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                p.close()
            }
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

    private fun drawStrokes(canvas: Canvas, l: Layer, m: Matrix, cw: Float, ch: Float, alpha: Int) {
        val save = canvas.saveLayer(null, null)
        canvas.concat(m)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        for (s in l.strokes) {
            if (s.points.size < 2) continue
            p.strokeWidth = s.width * cw
            p.color = s.color
            p.alpha = if (s.eraser) 255 else (Color.alpha(s.color) * alpha / 255)
            p.xfermode = if (s.eraser) PorterDuffXfermode(PorterDuff.Mode.CLEAR) else null
            val path = Path()
            path.moveTo(s.points[0] * cw, s.points[1] * ch)
            var i = 2
            while (i + 1 < s.points.size) {
                val x = s.points[i] * cw; val y = s.points[i + 1] * ch
                val px = s.points[i - 2] * cw; val py = s.points[i - 1] * ch
                path.quadTo(px, py, (x + px) / 2f, (y + py) / 2f)
                i += 2
            }
            if (s.points.size == 2) path.lineTo(s.points[0] * cw + 0.1f, s.points[1] * ch)
            else path.lineTo(s.points[s.points.size - 2] * cw, s.points[s.points.size - 1] * ch)
            canvas.drawPath(path, p)
        }
        canvas.restoreToCount(save)
    }

    // ------------------------------------------------------------------ pictures (crop, keying, outline)

    private val picCache = object : LruCache<String, Bitmap>(40 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Source picture of an image / video / animated layer after crop and chroma key. */
    fun picture(context: Context, l: Layer, t: Long, content: Bitmap?, maxDim: Int): Bitmap? {
        val static = l.kind == LayerKind.IMAGE && l.linkedProject == null
        val key = "${l.uri}|${l.cropL}|${l.cropT}|${l.cropR}|${l.cropB}|$maxDim"
        var b: Bitmap = when {
            content != null -> content
            l.linkedProject != null -> so.ijarjar.app.data.DynamicLink.bitmap(context, l.linkedProject!!, maxDim) ?: return null
            l.kind == LayerKind.IMAGE && l.uri != null -> MediaUtils.loadBitmapCached(context, Uri.parse(l.uri), maxDim) ?: return null
            l.kind == LayerKind.ANIMATED -> AnimatedSource.frameAt(context, l, t - l.startMs, maxDim) ?: return null
            else -> return null
        }
        if (l.hasCrop()) {
            val ck = "crop|$key"
            val cached = if (static) picCache.get(ck) else null
            b = cached ?: run {
                val x = (b.width * l.cropL).toInt().coerceIn(0, b.width - 1)
                val y = (b.height * l.cropT).toInt().coerceIn(0, b.height - 1)
                val w = (b.width * (1f - l.cropL - l.cropR)).toInt().coerceIn(1, b.width - x)
                val h = (b.height * (1f - l.cropT - l.cropB)).toInt().coerceIn(1, b.height - y)
                Bitmap.createBitmap(b, x, y, w, h).also { if (static) picCache.put(ck, it) }
            }
        }
        l.adjust.lutUri?.let { u ->
            Lut.load(context, u)?.let { lut ->
                val lk = "lut|$key|$u|${l.adjust.lutStrength}"
                b = (if (static) picCache.get(lk) else null) ?: lut.apply(b, l.adjust.lutStrength).also { if (static) picCache.put(lk, it) }
            }
        }
        if (l.chroma) {
            // keep keying fast: work on a reduced copy for moving pictures
            val src = if (!static && maxOf(b.width, b.height) > 640) {
                val s = 640f / maxOf(b.width, b.height)
                Bitmap.createScaledBitmap(b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true)
            } else b
            b = Chroma.key(src, l, if (static) key else null)
        }
        return b
    }

    private val outlineCache = LruCache<String, Bitmap>(8)

    // ------------------------------------------------------------------ geometry

    /** Size of the layer content at scale 1, in canvas pixels. */
    fun contentSize(l: Layer, canvasW: Int): Pair<Float, Float> {
        return if (l.isTextLike()) {
            val s = textSpec(l, canvasW)
            Pair(s.bw.toFloat(), s.bh.toFloat())
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
            postScale((if (l.flipH) -p.scale else p.scale) * p.flipX * p.sx, p.scale * p.sy)
            postRotate(p.rotation)
            postTranslate(p.cx * canvasW, p.cy * canvasH)
        }
    }

    fun hitTest(l: Layer, t: Long, canvasW: Int, canvasH: Int, x: Float, y: Float, slopPx: Float): Boolean {
        if (l.isEffect() || l.kind == LayerKind.DRAW) return false
        return hitBox(l, t, canvasW, canvasH, x, y, slopPx)
    }

    fun hitBox(l: Layer, t: Long, canvasW: Int, canvasH: Int, x: Float, y: Float, slopPx: Float): Boolean {
        val p = basePose(l, t)
        val inv = Matrix()
        if (!matrix(l, p, canvasW, canvasH).invert(inv)) return false
        val pt = floatArrayOf(x, y)
        inv.mapPoints(pt)
        val (cw, ch) = contentSize(l, canvasW)
        val s = slopPx / (abs(p.scale) * minOf(abs(p.sx), abs(p.sy))).coerceAtLeast(0.05f)
        return pt[0] >= -s && pt[0] <= cw + s && pt[1] >= -s && pt[1] <= ch + s
    }

    /** Point in canvas pixels -> 0..1 position inside the layer box (or null when outside). */
    fun toLocal(l: Layer, t: Long, canvasW: Int, canvasH: Int, x: Float, y: Float): FloatArray? {
        val inv = Matrix()
        if (!matrix(l, basePose(l, t), canvasW, canvasH).invert(inv)) return null
        val pt = floatArrayOf(x, y)
        inv.mapPoints(pt)
        val (cw, ch) = contentSize(l, canvasW)
        return floatArrayOf(pt[0] / cw, pt[1] / ch)
    }

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

    /**
     * Draws a single layer at time [t]. [content] is the video frame for overlay videos.
     * [maxDim] limits picture decoding (smaller in preview).
     */
    fun draw(context: Context, canvas: Canvas, l: Layer, t: Long, canvasW: Int, canvasH: Int, content: Bitmap?, maxDim: Int = previewMaxDim) {
        if (l.motionBlur && !l.isEffect() && l.kind != LayerKind.VIDEO) {
            // motion blur: earlier moments drawn faintly behind (only when the layer moves)
            val now = poseAt(l, t); val before = poseAt(l, t - 40)
            val moving = abs(now.cx - before.cx) * canvasW + abs(now.cy - before.cy) * canvasH + abs(now.rotation - before.rotation) * 3f +
                abs(now.scale - before.scale) * canvasW * 0.3f
            if (moving > 2f) {
                val save = canvas.saveLayerAlpha(null, 70)
                for (k in 1..4) drawOnce(context, canvas, l, t - k * 12L, canvasW, canvasH, content, maxDim)
                canvas.restoreToCount(save)
            }
        }
        drawOnce(context, canvas, l, t, canvasW, canvasH, content, maxDim)
    }

    private fun drawOnce(context: Context, canvas: Canvas, l: Layer, t: Long, canvasW: Int, canvasH: Int, content: Bitmap?, maxDim: Int) {
        if (l.isEffect()) {
            if (EffectRenderer.isDrawn(l)) EffectRenderer.draw(canvas, l, t, canvasW, canvasH)
            return
        }
        val pose = poseAt(l, t)
        if (pose.opacity <= 0.003f) return
        val (cw, ch) = contentSize(l, canvasW)
        val m = matrix(l, pose, canvasW, canvasH)
        val alpha = (pose.opacity.coerceIn(0f, 1f) * 255).toInt()

        if (l.kind == LayerKind.DRAW) { drawStrokes(canvas, l, m, cw, ch, alpha); return }
        if (l.kind == LayerKind.TEXT && usesGlyphs(l, t)) {
            TextAnimator.draw(canvas, l, textSpec(l, canvasW), m, t, alpha)
            return
        }
        val bmp: Bitmap = when {
            l.isTextLike() -> textBitmap(l, canvasW, pose.visibleChars)
            l.kind == LayerKind.SHAPE -> shapeBitmap(l, canvasW)
            else -> picture(context, l, t, content, maxDim) ?: return
        }
        val pre = Matrix().apply { setScale(cw / bmp.width, ch / bmp.height) }
        pre.postConcat(m)
        bmpPaint.alpha = alpha
        bmpPaint.maskFilter = null
        bmpPaint.colorFilter = if (l.isPicture()) Filters.colorFilter(l.adjust) else null

        val blurPx = pose.blur * cw * 0.06f
        val needLayer = l.mask != MaskKind.NONE && !l.isTextLike()
        // picture shadow / outline (sticker look)
        if (l.isPicture() || l.kind == LayerKind.SHAPE) {
            if (l.shadow) {
                val sh = bmp.extractAlpha()
                val sp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                    color = Color.BLACK; this.alpha = alpha * 120 / 255
                    maskFilter = BlurMaskFilter((bmp.width * 0.02f).coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL)
                }
                val sm = Matrix(pre); sm.postTranslate(cw * 0.015f * pose.scale, ch * 0.02f * pose.scale)
                canvas.drawBitmap(sh, sm, sp)
            }
            if (l.outlineColor != 0 && l.outlineWidth > 0f) {
                val op = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                    colorFilter = PorterDuffColorFilter(l.outlineColor, PorterDuff.Mode.SRC_IN); this.alpha = alpha
                }
                val r = l.outlineWidth * bmp.width
                for (k in 0 until 16) {
                    val a = k * PI.toFloat() / 8f
                    val om = Matrix(); om.setTranslate(cos(a) * r, sin(a) * r); om.postConcat(pre)
                    canvas.drawBitmap(bmp, om, op)
                }
            }
        }
        if (!needLayer) {
            if (blurPx > 0.5f) {
                val bp = Paint(bmpPaint); bp.maskFilter = null
                // simple blur: draw a few offset copies
                bp.alpha = alpha / 5
                for (k in 0 until 5) {
                    val a = k * 2f * PI.toFloat() / 5f
                    val bm = Matrix(pre); bm.postTranslate(cos(a) * blurPx, sin(a) * blurPx)
                    canvas.drawBitmap(bmp, bm, bp)
                }
                bmpPaint.alpha = (alpha * (1f - pose.blur * 0.6f)).toInt()
            }
            canvas.drawBitmap(bmp, pre, bmpPaint)
            return
        }
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

    // ------------------------------------------------------------------ keyframe helpers

    fun writePose(l: Layer, t: Long, p: Pose) {
        if (l.keyframes.isEmpty()) {
            l.cx = p.cx; l.cy = p.cy; l.scale = p.scale; l.rotation = p.rotation; l.opacity = p.opacity
            l.stretchX = p.sx; l.stretchY = p.sy
            return
        }
        val rel = (t - l.startMs).coerceIn(0, l.durationMs)
        val k = l.keyframes.firstOrNull { abs(it.t - rel) < 60 }
        if (k != null) { k.cx = p.cx; k.cy = p.cy; k.scale = p.scale; k.rotation = p.rotation; k.opacity = p.opacity; k.sx = p.sx; k.sy = p.sy }
        else {
            // new keyframes copy the curve of the keyframe before them
            val prev = l.keyframes.filter { it.t < rel }.maxByOrNull { it.t }
            l.keyframes.add(Keyframe(rel, p.cx, p.cy, p.scale, p.rotation, p.opacity, p.sx, p.sy).also { nk ->
                if (prev != null) { nk.ease = prev.ease; nk.bx1 = prev.bx1; nk.by1 = prev.by1; nk.bx2 = prev.bx2; nk.by2 = prev.by2 }
            })
        }
    }

    fun keyframeAt(l: Layer, t: Long): Keyframe? {
        val rel = t - l.startMs
        return l.keyframes.firstOrNull { abs(it.t - rel) < 60 }
    }

    @Suppress("unused")
    private fun unusedOutline() = outlineCache
}

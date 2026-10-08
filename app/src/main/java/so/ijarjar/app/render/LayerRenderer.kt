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
    var sy: Float = 1f,         // height stretch
    var rx: Float = 0f,         // 3D tilt (degrees around X)
    var ry: Float = 0f,         // 3D turn (degrees around Y)
    var z: Float = 0f           // 3D depth (canvas widths)
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

    private val fileFonts = HashMap<String, Typeface?>()

    /** The layer's typeface: an imported font file, or one of the built-in ones. */
    fun fontFor(l: Layer): Typeface {
        val path = l.fontPath
        if (path != null) {
            val tf = synchronized(fileFonts) { fileFonts.getOrPut(path) { runCatching { Typeface.createFromFile(path) }.getOrNull() } }
            if (tf != null) return if (l.bold) Typeface.create(tf, Typeface.BOLD) else tf
        }
        return typeface(l.font, l.bold)
    }

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
        if (ks.isEmpty()) return Pose(l.cx, l.cy, l.scale, l.rotation, l.opacity, sx = l.stretchX, sy = l.stretchY, rx = l.rotX, ry = l.rotY, z = l.posZ)
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
                    sx = lerp(a.sx, b.sx, f), sy = lerp(a.sy, b.sy, f), rx = lerp(a.rx, b.rx, f), ry = lerp(a.ry, b.ry, f), z = lerp(a.z, b.z, f))
            }
        }
        return poseOf(last)
    }

    private fun poseOf(k: Keyframe) = Pose(k.cx, k.cy, k.scale, k.rotation, k.opacity, sx = k.sx, sy = k.sy, rx = k.rx, ry = k.ry, z = k.z)

    // ------------------------------------------------------------------ expression code (AE style)

    /** Property names that can have expression code. */
    val EXPR_PROPS = listOf("position", "scale", "rotation", "opacity", "rotX", "rotY", "posZ")

    fun propValue(p: Pose, k: String): DoubleArray {
        val w = ExprEngine.compW; val h = ExprEngine.compH
        return when (k) {
            "position" -> doubleArrayOf(p.cx * w, p.cy * h)
            "scale" -> doubleArrayOf(p.scale * p.sx * 100.0, p.scale * p.sy * 100.0)
            "rotation" -> doubleArrayOf(p.rotation.toDouble())
            "opacity" -> doubleArrayOf(p.opacity * 100.0)
            "rotX" -> doubleArrayOf(p.rx.toDouble())
            "rotY" -> doubleArrayOf(p.ry.toDouble())
            else -> doubleArrayOf(p.z * 1000.0)
        }
    }

    private fun setProp(p: Pose, k: String, r: DoubleArray) {
        if (r.isEmpty()) return
        val w = ExprEngine.compW; val h = ExprEngine.compH
        when (k) {
            "position" -> { p.cx = (r[0] / w).toFloat(); if (r.size > 1) p.cy = (r[1] / h).toFloat() }
            "scale" -> {
                val sc = if (abs(p.scale) < 1e-4f) 1f else p.scale
                p.sx = (r[0] / 100.0 / sc).toFloat(); p.sy = (r.getOrElse(1) { r[0] } / 100.0 / sc).toFloat()
            }
            "rotation" -> p.rotation = r[0].toFloat()
            "opacity" -> p.opacity = (r[0] / 100.0).toFloat().coerceIn(0f, 1f)
            "rotX" -> p.rx = r[0].toFloat()
            "rotY" -> p.ry = r[0].toFloat()
            else -> p.z = (r[0] / 1000.0).toFloat()
        }
    }

    /** Runs the layer's expression code on top of its keyframed values. */
    fun applyCode(l: Layer, t: Long, p: Pose) {
        if (l.exprCode.isEmpty()) return
        val time = (t - l.startMs) / 1000.0
        val keyTimes = l.keyframes.map { it.t / 1000.0 }.sorted()
        for ((k, code) in l.exprCode) {
            if (code.isBlank()) continue
            val ctx = ExprEngine.Ctx(time, propValue(p, k), { s -> propValue(basePose(l, l.startMs + (s * 1000).toLong()), k) },
                keyTimes, l.durationMs / 1000.0, ExprEngine.compW, ExprEngine.compH)
            val r = ExprEngine.eval(code, ctx) ?: continue
            setProp(p, k, r)
        }
    }

    /** Full pose: keyframes + in/out + loop animations. Used for drawing. */
    fun poseAt(l: Layer, t: Long): Pose {
        val p = basePose(l, t)
        applyCode(l, t, p)
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
            LayerAnim.POP_UP -> {
                p.scale *= backOut(raw, 2.6f).coerceAtLeast(0.01f)
                p.cy += (1f - ease(raw)) * 0.06f
                p.opacity *= minOf(1f, raw * 3f)
            }
            LayerAnim.BOUNCE_IN -> { p.scale *= Ease.apply(so.ijarjar.app.model.Easing.BOUNCE, raw).coerceAtLeast(0.01f); p.opacity *= minOf(1f, raw * 4f) }
            LayerAnim.ELASTIC -> { p.scale *= Ease.apply(so.ijarjar.app.model.Easing.ELASTIC, raw).coerceAtLeast(0.01f); p.opacity *= minOf(1f, raw * 4f) }
            LayerAnim.JELLY -> {
                val w = sin(raw * PI.toFloat() * 5f) * (1f - raw) * 0.45f
                p.sx *= 1f + w; p.sy *= 1f - w
                p.scale *= minOf(1f, 0.3f + raw * 2f); p.opacity *= minOf(1f, raw * 4f)
            }
            LayerAnim.SQUASH -> {
                val fall = (raw / 0.55f).coerceIn(0f, 1f)
                p.cy -= (1f - fall * fall) * 0.4f
                if (raw > 0.55f) {
                    val k = (raw - 0.55f) / 0.45f
                    val w = sin(k * PI.toFloat() * 2.5f) * (1f - k) * 0.35f
                    p.sx *= 1f + w; p.sy *= 1f - w
                }
                p.opacity *= minOf(1f, raw * 5f)
            }
            LayerAnim.RISE_BOUNCE -> { p.cy += (1f - Ease.apply(so.ijarjar.app.model.Easing.BOUNCE, raw)) * 0.3f; p.opacity *= minOf(1f, raw * 4f) }
            LayerAnim.STRETCH -> { p.sx *= backOut(raw, 1.8f).coerceAtLeast(0.01f); p.opacity *= minOf(1f, raw * 3f) }
            LayerAnim.SWING_IN -> {
                val d = (1f - raw)
                p.rotation += (if (isIn) 1f else -1f) * 50f * d * d * cos(raw * PI.toFloat() * 3.5f)
                p.opacity *= minOf(1f, raw * 3f)
            }
            LayerAnim.SHAKE_IN -> { p.cx += sin(raw * 55f) * (1f - raw) * 0.035f; p.opacity *= minOf(1f, raw * 3f) }
            LayerAnim.ROLL -> {
                p.cx -= g * 0.45f * (if (isIn) 1f else -1f)
                p.rotation -= g * 360f * (if (isIn) 1f else -1f)
                p.opacity *= f
            }
        }
    }

    /** Overshoot ease ("back out") with a chosen strength. */
    private fun backOut(x: Float, k: Float): Float {
        val t = x.coerceIn(0f, 1f) - 1f
        return t * t * ((k + 1f) * t + k) + 1f
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
            LoopAnim.BOUNCE -> {
                val ph = (s * 1.4f) % 1f
                val hgt = 4f * ph * (1f - ph)
                p.cy -= 0.04f * hgt
                if (ph < 0.12f || ph > 0.88f) { val q = 1f - hgt * 4f; p.sx *= 1f + 0.12f * q.coerceIn(0f, 1f); p.sy *= 1f - 0.12f * q.coerceIn(0f, 1f) }
            }
            LoopAnim.JELLY -> { val w = 0.07f * sin(s * tau * 1.6f); p.sx *= 1f + w; p.sy *= 1f - w }
            LoopAnim.WIGGLE -> {
                p.rotation += 4f * sin(s * 11f) + 3f * sin(s * 17.3f)
                p.cx += 0.006f * sin(s * 7.1f) + 0.004f * sin(s * 12.7f)
                p.cy += 0.006f * sin(s * 8.3f) + 0.004f * sin(s * 10.9f)
            }
            LoopAnim.BREATHE -> { val b = sin(s * tau * 0.45f); p.scale *= 1f + 0.05f * b; p.opacity *= 0.85f + 0.15f * (0.5f + 0.5f * b) }
            LoopAnim.FLIP_SPIN -> { val c = cos(s * tau * 0.5f); p.flipX *= if (abs(c) < 0.02f) (if (c < 0f) -0.02f else 0.02f) else c }
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
            "${l.bold}|${l.font}|${l.fontPath}|${l.align}|${l.shadow}|${l.depth}|${l.depthColor}|${l.letterSpacing}|$canvasW|${l.kind}"

    fun textSpec(l: Layer, canvasW: Int): TextSpec {
        val key = textKey(l, canvasW)
        specCache.get(key)?.let { return it }
        val textPx = (l.textSizeFrac * canvasW).coerceAtLeast(6f)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = textPx
            typeface = fontFor(l)
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

    /** The shape drawn at its stretched size, so round corners and outlines stay round when it is made wider or taller. */
    fun shapeBitmap(l: Layer, canvasW: Int, sx: Float = 1f, sy: Float = 1f): Bitmap {
        val (cw0, ch0) = contentSize(l, canvasW)
        val qx = (kotlin.math.round(abs(sx) * 50f) / 50f).coerceIn(0.02f, 50f)
        val qy = (kotlin.math.round(abs(sy) * 50f) / 50f).coerceIn(0.02f, 50f)
        val cw = cw0 * qx; val ch = ch0 * qy
        val scaleDown = (1024f / maxOf(cw, ch)).coerceAtMost(1f)
        val w = (cw * scaleDown).toInt().coerceAtLeast(4)
        val h = (ch * scaleDown).toInt().coerceAtLeast(4)
        val key = "shape|${l.shape}|${l.textColor}|${l.textColor2}|${l.strokeColor}|${l.strokeWidth}|${l.shadow}|${l.shapeRound}|$w|$h"
        textCache.get(key)?.let { return it }
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val sw = if (l.strokeColor != 0) minOf(w, h) * l.strokeWidth.coerceIn(0.01f, 0.3f) * 0.5f else 0f
        val inset = sw / 2f + 1f
        val r = RectF(inset, inset, w - inset, h - inset)
        val path = shapePath(l.shape, r, l.shapeRound)
        // rounded corners on pointy shapes (triangle, star, hexagon, arrow, bubble)
        val cornerFx = if (l.shapeRound > 0f && l.shape !in setOf(ShapeKind.RECT, ShapeKind.ROUND_RECT, ShapeKind.CIRCLE, ShapeKind.RING, ShapeKind.LINE, ShapeKind.HEART))
            android.graphics.CornerPathEffect(minOf(w, h) * 0.3f * l.shapeRound) else null
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            pathEffect = cornerFx
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
                style = Paint.Style.STROKE; strokeWidth = sw; color = l.strokeColor; strokeJoin = Paint.Join.ROUND; pathEffect = cornerFx
            })
        }
        textCache.put(key, b)
        return b
    }

    fun shapePath(kind: ShapeKind, r: RectF, round: Float = -1f): Path {
        val p = Path()
        val cx = r.centerX(); val cy = r.centerY()
        when (kind) {
            ShapeKind.RECT, ShapeKind.ROUND_RECT -> {
                val k = if (round >= 0f) round else if (kind == ShapeKind.ROUND_RECT) 0.4f else 0f
                val rad = minOf(r.width(), r.height()) * 0.5f * k.coerceIn(0f, 1f)
                if (rad <= 0.5f) p.addRect(r, Path.Direction.CW) else p.addRoundRect(r, rad, rad, Path.Direction.CW)
            }
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
            p.maskFilter = null; p.pathEffect = null; p.strokeCap = Paint.Cap.ROUND
            if (!s.eraser && s.type == 3) {
                // spray: dots scattered around the path
                val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = s.color; this.alpha = p.alpha }
                var i = 0
                while (i + 1 < s.points.size) {
                    val x = s.points[i] * cw; val y = s.points[i + 1] * ch
                    for (k in 0 until 10) {
                        val a = ((i * 31 + k * 97) % 360) * PI.toFloat() / 180f
                        val r = ((i * 17 + k * 53) % 100) / 100f * p.strokeWidth
                        canvas.drawCircle(x + cos(a) * r, y + sin(a) * r, maxOf(1f, p.strokeWidth * 0.06f), dot)
                    }
                    i += 2
                }
                continue
            }
            if (!s.eraser && s.type == 1) { p.alpha = p.alpha * 90 / 255; p.strokeCap = Paint.Cap.SQUARE; p.strokeWidth *= 2.2f }
            if (!s.eraser && s.type == 4) p.pathEffect = android.graphics.DashPathEffect(floatArrayOf(p.strokeWidth * 2f, p.strokeWidth * 1.5f), 0f)
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
            if (!s.eraser && s.type >= 5) {
                when (s.type) {
                    5 -> { // calligraphy: a flat, slanted nib
                        p.strokeCap = Paint.Cap.BUTT; val w = p.strokeWidth; p.strokeWidth = w * 0.25f
                        for (k in -4..4) { canvas.save(); canvas.translate(k * w * 0.09f, -k * w * 0.09f); canvas.drawPath(path, p); canvas.restore() }
                    }
                    6 -> { // pencil: thin grainy lines
                        val w = p.strokeWidth; p.strokeWidth = maxOf(1f, w * 0.35f); p.alpha = p.alpha * 170 / 255
                        for (k in 0 until 3) { canvas.save(); canvas.translate((k - 1) * w * 0.12f, ((k * 7) % 3 - 1) * w * 0.1f); canvas.drawPath(path, p); canvas.restore() }
                    }
                    7 -> { // airbrush: soft edges
                        p.maskFilter = BlurMaskFilter(p.strokeWidth * 0.6f, BlurMaskFilter.Blur.NORMAL); p.alpha = p.alpha * 200 / 255
                        canvas.drawPath(path, p)
                    }
                    8 -> { // dots
                        p.pathEffect = android.graphics.DashPathEffect(floatArrayOf(0.1f, p.strokeWidth * 1.8f), 0f)
                        canvas.drawPath(path, p)
                    }
                    9 -> { // rainbow
                        val b = RectF(); path.computeBounds(b, true)
                        p.shader = LinearGradient(b.left, b.top, b.right.coerceAtLeast(b.left + 1f), b.bottom, intArrayOf(0xFFFF3B30.toInt(), 0xFFFF9500.toInt(), 0xFFFFCC00.toInt(),
                            0xFF34C759.toInt(), 0xFF007AFF.toInt(), 0xFFAF52DE.toInt()), null, Shader.TileMode.MIRROR)
                        canvas.drawPath(path, p); p.shader = null
                    }
                    else -> { // outline: coloured edge, empty middle
                        canvas.drawPath(path, p)
                        val hole = Paint(p).apply { strokeWidth = p.strokeWidth * 0.55f; xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
                        canvas.drawPath(path, hole)
                    }
                }
                continue
            }
            if (!s.eraser && s.type == 2) {
                // neon: wide soft glow + white core
                val glow = Paint(p).apply { strokeWidth = p.strokeWidth * 2.5f; maskFilter = BlurMaskFilter(p.strokeWidth * 1.2f, BlurMaskFilter.Blur.NORMAL) }
                canvas.drawPath(path, glow)
                canvas.drawPath(path, p)
                val core = Paint(p).apply { color = Color.WHITE; this.alpha = p.alpha; strokeWidth = p.strokeWidth * 0.35f }
                canvas.drawPath(path, core)
                continue
            }
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
        if (l.adjust.lutUri != null || l.adjust.hasTone()) {
            Filters.lutFor(context, l.adjust)?.let { lut ->
                val lk = "lut|$key|${Filters.lutKey(l.adjust)}"
                b = (if (static) picCache.get(lk) else null) ?: lut.apply(b, 1f).also { if (static) picCache.put(lk, it) }
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
    fun matrix(l: Layer, p: Pose, canvasW: Int, canvasH: Int, extraZ: Float = 0f): Matrix {
        val (cw, ch) = contentSize(l, canvasW)
        return Matrix().apply {
            postTranslate(-cw / 2f, -ch / 2f)
            postScale((if (l.flipH) -p.scale else p.scale) * p.flipX * p.sx, p.scale * p.sy)
            val z = p.z + extraZ
            if (p.rx != 0f || p.ry != 0f || z != 0f) {
                // 3D: perspective that looks the same at any resolution
                val k = 1000f / canvasW.coerceAtLeast(1)
                postScale(k, k)
                val cam = android.graphics.Camera()
                cam.save()
                cam.translate(0f, 0f, z * 1000f)
                cam.rotateX(p.rx)
                cam.rotateY(p.ry)
                val m3 = Matrix()
                cam.getMatrix(m3)
                cam.restore()
                postConcat(m3)
                postScale(1f / k, 1f / k)
            }
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
        // effects first, then pictures / videos / text in the user's order (so a video can be above or below)
        layers.filter { it.isEffect() } + layers.filter { !it.isEffect() }

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
                abs(now.scale - before.scale) * canvasW * 0.3f + (abs(now.rx - before.rx) + abs(now.ry - before.ry)) * 3f +
                (if (l.kind == LayerKind.MODEL3D) abs(l.modelSpin) * 40f * 3f else 0f)
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
        if (l.kind == LayerKind.MODEL3D) { drawModel(context, canvas, l, pose, t, canvasW, canvasH, cw, ch, alpha, maxDim, content); return }
        if (l.kind == LayerKind.TEXT && usesGlyphs(l, t)) {
            TextAnimator.draw(canvas, l, textSpec(l, canvasW), m, t, alpha)
            return
        }
        val bmp0: Bitmap = when {
            l.isTextLike() -> textBitmap(l, canvasW, pose.visibleChars)
            l.kind == LayerKind.SHAPE -> shapeBitmap(l, canvasW, pose.sx, pose.sy)
            else -> picture(context, l, t, content, maxDim) ?: return
        }
        var pre = Matrix().apply { setScale(cw / bmp0.width, ch / bmp0.height) }
        pre.postConcat(m)
        // AE Fast Box Blur: the picture with room around it, shrunk and stretched back (smooth and cheap)
        var bmp = bmp0
        if (l.fxBlur > 0.01f) {
            val (b2, pad) = fastBlur(bmp0, l.fxBlur)
            val pm = Matrix(); pm.setTranslate(-pad, -pad); pm.postScale(cw / bmp0.width, ch / bmp0.height); pm.postConcat(m)
            bmp = b2; pre = pm
        }
        if (l.glowColor != 0) drawGlow(canvas, bmp, pre, l, alpha)
        if (l.mockup != so.ijarjar.app.model.MockupKind.NONE && l.isPicture()) {
            Mockups.draw(canvas, l, pose, canvasW, canvasH, bmp, alpha, cw, ch)
            return
        }
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

    private class ModelFrame(val key: String, val bmp: Bitmap)
    private val modelFrames = java.util.concurrent.ConcurrentHashMap<String, ModelFrame>()
    private val modelRequested = HashMap<String, String>()
    /** Called (on the main thread) when a 3D picture drawn in the background is ready. */
    var onAsyncFrame: (() -> Unit)? = null

    /** 3D model: the model turns in real 3D (rotX / rotY / spin); the layer box is flat. */
    private fun drawModel(context: Context, canvas: Canvas, l: Layer, pose: Pose, t: Long, canvasW: Int, canvasH: Int,
                          cw: Float, ch: Float, alpha: Int, maxDim: Int, content: Bitmap? = null) {
        val uri = l.uri ?: return
        // render a bit bigger than shown so edges stay smooth
        val onScreen = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
        val size = (cw * pose.scale * (if (onScreen) 1.15f else 1.4f)).toInt().coerceIn(96, minOf((maxDim * 1.4f).toInt(), if (onScreen) 1100 else 1800))
        val spin = l.modelSpin * (t - l.startMs) / 1000f * 360f
        val looks = ArrayList<Model3D.Look>()
        if (l.modelTexture != null || l.modelColor != 0) looks.add(Model3D.Look(l.modelMaterial ?: "", l.modelTexture, color = l.modelColor))
        var liveKey = ""
        // pictures on a built-in phone screen keep their shape (cut to fit, never stretched)
        val phoneAspect = l.phoneStyle?.let { st -> runCatching { PhoneGlb.screenAspect(PhoneGlb.Style.valueOf(st)) }.getOrNull() } ?: 0f
        for ((m, p) in l.parts) {
            val asp = if (m == "Screen") phoneAspect else 0f
            if (p.hidden) { looks.add(Model3D.Look(m, hidden = true)); continue }
            if (p.video) {
                if (content != null) { val k = "${(t - l.startMs) / 33}"; looks.add(Model3D.Look(m, bitmap = content, bitmapKey = k, color = p.color, flipV = p.flipV, flipH = p.flipH, aspect = asp)); liveKey += "$m@$k" }
            } else if (p.tex != null || p.color != 0) looks.add(Model3D.Look(m, p.tex, color = p.color, flipV = p.flipV, flipH = p.flipH, aspect = asp))
        }
        val light = Model3D.Light(l.lightPower, l.lightAmbient, l.lightAz, l.lightEl, l.lightSize)
        val key = "$uri|$size|${pose.rx}|${pose.ry + spin}|${l.lightPower},${l.lightAmbient},${l.lightAz},${l.lightEl},${l.lightSize}|" + looks.joinToString(";") { "${it.material}|${it.texUri}|${it.color}|${it.hidden}|${it.flipV}|${it.flipH}" } + "|$liveKey"
        // a few frames per layer are kept, so motion blur (earlier moments) doesn't re-render every time
        val cached = modelFrames["${l.id}|$key"] ?: modelFrames[l.id]
        val onMain = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
        val bmp = if (cached != null && cached.key == key) cached.bmp
        else if (onMain) {
            // preview: never wait for the 3D renderer on the screen thread — show the last picture,
            // draw the new one in the background and refresh when it is ready
            if (modelRequested[l.id] != key) {
                modelRequested[l.id] = key
                val id = l.id
                // the live video frame is reused by the screen, so hand the 3D thread its own copy
                val safeLooks = looks.map { lk -> if (lk.bitmap == null) lk else Model3D.Look(lk.material, lk.texUri, lk.bitmap.copy(Bitmap.Config.ARGB_8888, false), lk.bitmapKey, lk.color, lk.hidden, lk.flipV, lk.flipH, lk.aspect) }
                Model3D.renderAsync(context, id, uri, size, size, pose.rx, pose.ry + spin, safeLooks, light) { b ->
                    if (modelRequested[id] == key) modelRequested.remove(id)
                    if (b != null) {
                        modelFrames[id] = ModelFrame(key, b); modelFrames["$id|$key"] = ModelFrame(key, b)
                        if (modelFrames.size > 40) modelFrames.keys.filter { it.contains('|') }.take(20).forEach { modelFrames.remove(it) }
                        onAsyncFrame?.invoke()
                    }
                }
            }
            cached?.bmp ?: return
        } else {
            val b = Model3D.render(context, uri, size, size, pose.rx, pose.ry + spin, looks, null, light) ?: cached?.bmp ?: return
            modelFrames[l.id] = ModelFrame(key, b)
            modelFrames["${l.id}|$key"] = ModelFrame(key, b)
            if (modelFrames.size > 40) modelFrames.keys.filter { it.contains('|') }.take(20).forEach { modelFrames.remove(it) }
            b
        }
        val flat = Pose(pose.cx, pose.cy, pose.scale, pose.rotation, pose.opacity, sx = pose.sx, sy = pose.sy, z = pose.z)
        val m = matrix(l, flat, canvasW, canvasH)
        var pre = Matrix().apply { setScale(cw / bmp.width, ch / bmp.height) }
        pre.postConcat(m)
        var bmp2 = bmp
        if (l.fxBlur > 0.01f) {
            val (b2, pad) = fastBlur(bmp, l.fxBlur)
            val pm = Matrix(); pm.setTranslate(-pad, -pad); pm.postScale(cw / bmp.width, ch / bmp.height); pm.postConcat(m)
            bmp2 = b2; pre = pm
        }
        if (l.glowColor != 0) drawGlow(canvas, bmp2, pre, l, alpha)
        bmpPaint.alpha = alpha; bmpPaint.maskFilter = null; bmpPaint.colorFilter = null
        canvas.drawBitmap(bmp2, pre, bmpPaint)
    }

    private val blurCache = LruCache<String, Pair<Bitmap, Float>>(12)

    /** Blurred copy of [src] with a margin (so the blur can spread past the edges); returns it and the margin in px. */
    private fun fastBlur(src: Bitmap, amount: Float): Pair<Bitmap, Float> {
        val key = "${System.identityHashCode(src)}|${src.generationId}|${(amount * 100).toInt()}"
        blurCache.get(key)?.let { return it }
        val pad = (maxOf(src.width, src.height) * 0.18f * amount).coerceAtLeast(2f)
        val padded = Bitmap.createBitmap((src.width + pad * 2).toInt(), (src.height + pad * 2).toInt(), Bitmap.Config.ARGB_8888)
        Canvas(padded).drawBitmap(src, pad, pad, Paint(Paint.FILTER_BITMAP_FLAG))
        val k = 1f + amount * 18f
        var b = padded
        repeat(2) {
            val sw = (b.width / k).toInt().coerceAtLeast(2); val sh = (b.height / k).toInt().coerceAtLeast(2)
            val small = Bitmap.createScaledBitmap(b, sw, sh, true)
            b = Bitmap.createScaledBitmap(small, padded.width, padded.height, true)
        }
        val out = Pair(b, pad)
        blurCache.put(key, out)
        return out
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /** Soft coloured light behind the layer (After Effects "Glow"). */
    private fun drawGlow(canvas: Canvas, bmp: Bitmap, pre: Matrix, l: Layer, alpha: Int) {
        val a = bmp.extractAlpha()
        glowPaint.color = l.glowColor
        glowPaint.alpha = alpha
        val r = (l.glowSize.coerceIn(0.02f, 1f) * minOf(bmp.width, bmp.height) * 0.25f).coerceAtLeast(1f)
        glowPaint.maskFilter = BlurMaskFilter(r, BlurMaskFilter.Blur.NORMAL)
        canvas.drawBitmap(a, pre, glowPaint)
        glowPaint.maskFilter = BlurMaskFilter(r * 0.4f, BlurMaskFilter.Blur.NORMAL)
        canvas.drawBitmap(a, pre, glowPaint)
    }

    // ------------------------------------------------------------------ masks

    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }

    private val maskCache = LruCache<String, Bitmap>(24)

    /** Mask box in content pixels: centre, half width, half height (shape masks). */
    fun maskBox(l: Layer, cw: Float, ch: Float): FloatArray {
        val minSide = minOf(cw, ch)
        val hh = minSide * l.maskSize.coerceIn(0.02f, 4f) / 2f
        val hw = hh * l.maskStretch.coerceIn(0.05f, 20f)
        return floatArrayOf(cw * l.maskX, ch * l.maskY, hw, hh)
    }

    fun maskBitmap(l: Layer, cw: Float, ch: Float): Bitmap {
        val aspect = (ch / cw.coerceAtLeast(1f)).coerceIn(0.05f, 20f)
        val mw = if (aspect <= 1f) 384 else (384 / aspect).toInt().coerceAtLeast(8)
        val mh = (mw * aspect).toInt().coerceAtLeast(8)
        val key = "${l.mask}|${l.maskSize}|${l.maskFeather}|${l.maskInvert}|${l.maskX}|${l.maskY}|${l.maskStretch}|${l.maskRot}|${l.maskRound}|$mw|$mh"
        maskCache.get(key)?.let { return it }
        val b = Bitmap.createBitmap(mw, mh, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
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
        val box = maskBox(l, mw.toFloat(), mh.toFloat())
        val cx = box[0]; val cy = box[1]; val hw = box[2]; val hh = box[3]
        val big = (mw + mh) * 2f
        c.save()
        c.rotate(l.maskRot, cx, cy)
        when (l.mask) {
            MaskKind.NONE -> c.drawColor(Color.WHITE)
            MaskKind.CIRCLE -> c.drawOval(RectF(cx - hw, cy - hh, cx + hw, cy + hh), paint)
            MaskKind.RECT -> {
                val r = minOf(hw, hh) * l.maskRound.coerceIn(0f, 1f)
                c.drawRoundRect(RectF(cx - hw, cy - hh, cx + hw, cy + hh), r, r, paint)
            }
            MaskKind.HEART -> c.drawPath(shapePath(ShapeKind.HEART, RectF(cx - hw, cy - hh, cx + hw, cy + hh)), paint)
            MaskKind.STAR -> c.drawPath(shapePath(ShapeKind.STAR, RectF(cx - hw, cy - hh, cx + hw, cy + hh)), paint)
            MaskKind.LINEAR -> {
                // everything above the line shows (CapCut "linear"); rotate to tilt it
                val f = featherPx.coerceAtLeast(1f)
                paint.shader = LinearGradient(0f, cy - f, 0f, cy + f, Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP)
                c.drawRect(cx - big, cy - big, cx + big, cy + big, paint)
            }
            MaskKind.MIRROR -> {
                val f = featherPx.coerceAtLeast(1f)
                val total = 2 * hh + 2 * f
                paint.shader = LinearGradient(0f, cy - hh - f, 0f, cy + hh + f,
                    intArrayOf(Color.TRANSPARENT, Color.WHITE, Color.WHITE, Color.TRANSPARENT),
                    floatArrayOf(0f, (2 * f / total).coerceIn(0f, 0.49f), (1f - 2 * f / total).coerceIn(0.51f, 1f), 1f),
                    Shader.TileMode.CLAMP)
                c.drawRect(cx - big, cy - big, cx + big, cy + big, paint)
            }
        }
        c.restore()
        maskCache.put(key, b)
        return b
    }

    // ------------------------------------------------------------------ keyframe helpers

    /** How editing a keyframed layer works: 0 = new keyframe at the playhead (AE auto-key),
     *  1 = change the nearest keyframe, 2 = move the whole animation. */
    @Volatile var keyMode = 0

    fun writePose(l: Layer, t: Long, p: Pose) {
        if (l.keyframes.isEmpty()) {
            l.cx = p.cx; l.cy = p.cy; l.scale = p.scale; l.rotation = p.rotation; l.opacity = p.opacity
            l.stretchX = p.sx; l.stretchY = p.sy
            l.rotX = p.rx; l.rotY = p.ry; l.posZ = p.z
            return
        }
        val rel = (t - l.startMs).coerceIn(0, l.durationMs)
        if (keyMode == 2) {
            // move the whole animation: the change is added to every keyframe
            val b = basePose(l, t)
            val dS = if (abs(b.scale) < 1e-4f) 1f else p.scale / b.scale
            val dSx = if (abs(b.sx) < 1e-4f) 1f else p.sx / b.sx
            val dSy = if (abs(b.sy) < 1e-4f) 1f else p.sy / b.sy
            for (k in l.keyframes) {
                k.cx += p.cx - b.cx; k.cy += p.cy - b.cy; k.scale *= dS; k.rotation += p.rotation - b.rotation
                k.opacity = (k.opacity + p.opacity - b.opacity).coerceIn(0f, 1f); k.sx *= dSx; k.sy *= dSy
                k.rx += p.rx - b.rx; k.ry += p.ry - b.ry; k.z += p.z - b.z
            }
            return
        }
        val k = l.keyframes.firstOrNull { abs(it.t - rel) < 60 }
            ?: if (keyMode == 1) l.keyframes.minByOrNull { abs(it.t - rel) } else null
        if (k != null) { k.cx = p.cx; k.cy = p.cy; k.scale = p.scale; k.rotation = p.rotation; k.opacity = p.opacity; k.sx = p.sx; k.sy = p.sy; k.rx = p.rx; k.ry = p.ry; k.z = p.z }
        else {
            // new keyframes copy the curve of the keyframe before them
            val prev = l.keyframes.filter { it.t < rel }.maxByOrNull { it.t }
            l.keyframes.add(Keyframe(rel, p.cx, p.cy, p.scale, p.rotation, p.opacity, p.sx, p.sy, rx = p.rx, ry = p.ry, z = p.z).also { nk ->
                if (prev != null) { nk.ease = prev.ease; nk.bx1 = prev.bx1; nk.by1 = prev.by1; nk.bx2 = prev.bx2; nk.by2 = prev.by2 }
            })
        }
    }

    fun keyframeAt(l: Layer, t: Long): Keyframe? {
        val rel = t - l.startMs
        // only "on" a keyframe when really on it (half a frame): moving the playhead a little and tapping ◆ adds a new one
        return l.keyframes.firstOrNull { abs(it.t - rel) < 17 }
    }

    @Suppress("unused")
    private fun unusedOutline() = outlineCache
}

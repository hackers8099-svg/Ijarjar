package so.ijarjar.app.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import so.ijarjar.app.model.EffectKind
import so.ijarjar.app.model.Layer
import so.ijarjar.app.model.Project
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Video effects (CapCut "Effects"). An effect is a timeline layer of kind EFFECT;
 * its opacity is used as the strength. Shared by preview and export.
 */
object EffectRenderer {

    private fun active(p: Project, t: Long) = p.layers.filter { it.isEffect() && it.isActive(t) }

    /** Small envelope so effects fade in/out over 150 ms instead of popping. */
    private fun env(l: Layer, t: Long): Float {
        val a = ((t - l.startMs) / 150f).coerceIn(0f, 1f)
        val b = ((l.endMs - t) / 150f).coerceIn(0f, 1f)
        return minOf(a, b) * l.opacity.coerceIn(0f, 1f)
    }

    /** Motion & flash effects added on top of the clip motion. [t] is global time. */
    fun applyMotion(p: Project, t: Long, m: ClipMotion) {
        for (l in active(p, t)) {
            val k = env(l, t)
            val u = (t - l.startMs).toFloat()
            when (l.effect) {
                EffectKind.SHAKE -> {
                    m.tx += (sin(u * 0.07f) * 0.6f + sin(u * 0.19f) * 0.4f) * 0.03f * k
                    m.ty += (cos(u * 0.083f) * 0.6f + cos(u * 0.23f) * 0.4f) * 0.025f * k
                    m.scale *= 1f + 0.06f * k
                }
                EffectKind.ZOOM_PULSE -> m.scale *= 1f + (0.5f + 0.5f * sin(u / 1000f * 2f * PI.toFloat() * 1.5f)) * 0.12f * k
                EffectKind.SLOW_ZOOM -> m.scale *= 1f + (u / l.durationMs).coerceIn(0f, 1f) * 0.3f * k
                EffectKind.SWAY -> m.rotation += sin(u / 1000f * 2f * PI.toFloat() * 0.6f) * 4f * k
                EffectKind.BOUNCE -> {
                    val ph = (u / 600f) % 1f
                    m.ty -= abs(sin(ph * PI.toFloat())) * 0.04f * k
                    m.scale *= 1f + abs(sin(ph * PI.toFloat())) * 0.04f * k
                }
                EffectKind.GLITCH -> {
                    val f = (u / 70f).toInt()
                    if (rnd(f, 3) > 0.55f) {
                        m.tx += (rnd(f, 1) - 0.5f) * 0.08f * k
                        m.sx *= 1f + (rnd(f, 2) - 0.5f) * 0.12f * k
                        if (rnd(f, 4) > 0.8f) { m.fadeColor = Color.argb(255, 255, 0, 255); m.fadeAlpha = maxOf(m.fadeAlpha, 0.25f * k) }
                    }
                }
                EffectKind.EARTHQUAKE -> {
                    m.tx += (sin(u * 0.11f) + sin(u * 0.27f) * 0.6f) * 0.045f * k
                    m.ty += (cos(u * 0.13f) + cos(u * 0.31f) * 0.6f) * 0.04f * k
                    m.rotation += sin(u * 0.05f) * 2.5f * k
                    m.scale *= 1f + 0.1f * k
                }
                EffectKind.FADE_WHITE -> {
                    val f = (u / l.durationMs).coerceIn(0f, 1f)
                    m.fadeColor = Color.WHITE; m.fadeAlpha = maxOf(m.fadeAlpha, f * l.opacity)
                }
                EffectKind.FLASH -> {
                    val ph = (u % 800f) / 800f
                    val a = if (ph < 0.25f) 1f - ph / 0.25f else 0f
                    if (a * k > m.fadeAlpha || m.fadeColor == Color.WHITE) { m.fadeColor = Color.WHITE; m.fadeAlpha = maxOf(m.fadeAlpha, a * k * 0.8f) }
                }
                EffectKind.STROBE -> {
                    val on = ((u / 90f).toInt() % 2) == 0
                    if (on) { m.fadeColor = Color.WHITE; m.fadeAlpha = maxOf(m.fadeAlpha, 0.6f * k) }
                }
                EffectKind.FADE_BLACK -> {
                    val f = (u / l.durationMs).coerceIn(0f, 1f)
                    m.fadeColor = Color.BLACK; m.fadeAlpha = maxOf(m.fadeAlpha, f * l.opacity)
                }
                else -> {}
            }
        }
    }

    /** Colour effects active at global time [t], or null. */
    fun colorMatrix(p: Project, t: Long): ColorMatrix? {
        var out: ColorMatrix? = null
        for (l in active(p, t)) {
            val k = env(l, t)
            val cm: ColorMatrix = when (l.effect) {
                EffectKind.BW -> ColorMatrix().apply { setSaturation(1f - k) }
                EffectKind.OLD_FILM -> {
                    val flicker = 1f + sin((t - l.startMs) * 0.05f) * 0.04f * k
                    ColorMatrix(floatArrayOf(
                        lerp(1f, 0.393f, k) * flicker, lerp(0f, 0.769f, k), lerp(0f, 0.189f, k), 0f, 0f,
                        lerp(0f, 0.349f, k), lerp(1f, 0.686f, k) * flicker, lerp(0f, 0.168f, k), 0f, 0f,
                        lerp(0f, 0.272f, k), lerp(0f, 0.534f, k), lerp(1f, 0.131f, k) * flicker, 0f, 0f,
                        0f, 0f, 0f, 1f, 0f))
                }
                EffectKind.RAINBOW -> hueRotate((t - l.startMs) / 1000f * 120f * k)
                EffectKind.DREAMY -> ColorMatrix(floatArrayOf(
                    1f - 0.15f * k, 0f, 0f, 0f, 40f * k,
                    0f, 1f - 0.15f * k, 0f, 0f, 30f * k,
                    0f, 0f, 1f - 0.15f * k, 0f, 45f * k,
                    0f, 0f, 0f, 1f, 0f)).apply { postConcat(ColorMatrix().apply { setSaturation(1f + 0.25f * k) }) }
                EffectKind.BLOOM -> ColorMatrix(floatArrayOf(
                    1f + 0.35f * k, 0f, 0f, 0f, 10f * k,
                    0f, 1f + 0.35f * k, 0f, 0f, 10f * k,
                    0f, 0f, 1f + 0.3f * k, 0f, 12f * k,
                    0f, 0f, 0f, 1f, 0f))
                EffectKind.NEON -> ColorMatrix().apply {
                    setSaturation(1f + 1.2f * k)
                    postConcat(ColorMatrix(floatArrayOf(
                        1f + 0.4f * k, 0f, 0f, 0f, -40f * k,
                        0f, 1f + 0.4f * k, 0f, 0f, -40f * k,
                        0f, 0f, 1f + 0.4f * k, 0f, -40f * k,
                        0f, 0f, 0f, 1f, 0f)))
                    postConcat(hueRotate(sin((t - l.startMs) / 700f) * 40f * k))
                }
                EffectKind.NEGATIVE -> ColorMatrix(floatArrayOf(
                    1f - 2f * k, 0f, 0f, 0f, 255f * k,
                    0f, 1f - 2f * k, 0f, 0f, 255f * k,
                    0f, 0f, 1f - 2f * k, 0f, 255f * k,
                    0f, 0f, 0f, 1f, 0f))
                else -> continue
            }
            out = (out ?: ColorMatrix()).apply { postConcat(cm) }
        }
        return out
    }

    private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f

    private fun hueRotate(deg: Float): ColorMatrix {
        val r = deg / 180f * PI.toFloat()
        val c = cos(r); val s = sin(r)
        val lr = 0.213f; val lg = 0.715f; val lb = 0.072f
        return ColorMatrix(floatArrayOf(
            lr + c * (1 - lr) + s * (-lr), lg + c * (-lg) + s * (-lg), lb + c * (-lb) + s * (1 - lb), 0f, 0f,
            lr + c * (-lr) + s * 0.143f, lg + c * (1 - lg) + s * 0.140f, lb + c * (-lb) + s * (-0.283f), 0f, 0f,
            lr + c * (-lr) + s * (-(1 - lr)), lg + c * (-lg) + s * lg, lb + c * (1 - lb) + s * lb, 0f, 0f,
            0f, 0f, 0f, 1f, 0f))
    }

    fun isDrawn(l: Layer) = l.isEffect() && l.effect.group == 3

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun rnd(i: Int, salt: Int): Float {
        var x = i * 374761393 + salt * 668265263
        x = (x xor (x ushr 13)) * 1274126177
        x = x xor (x ushr 16)
        return (x and 0xFFFF) / 65535f
    }

    /** Draws overlay effects (vignette, letterbox, particles, grain). */
    fun draw(canvas: Canvas, l: Layer, t: Long, w: Int, h: Int) {
        val k = env(l, t)
        if (k <= 0.01f) return
        val u = (t - l.startMs).toFloat()
        val W = w.toFloat(); val H = h.toFloat()
        paint.shader = null; paint.style = Paint.Style.FILL; paint.alpha = 255
        when (l.effect) {
            EffectKind.VIGNETTE -> {
                paint.shader = RadialGradient(W / 2, H / 2, maxOf(W, H) * 0.72f,
                    intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb((230 * k).toInt(), 0, 0, 0)),
                    floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, W, H, paint)
                paint.shader = null
            }
            EffectKind.LETTERBOX -> {
                paint.color = Color.BLACK
                val bar = H * 0.12f * k
                canvas.drawRect(0f, 0f, W, bar, paint)
                canvas.drawRect(0f, H - bar, W, H, paint)
            }
            EffectKind.SNOW -> particles(canvas, W, H, u, 70, k, 0.06f) { x, y, s, _ ->
                paint.color = Color.argb((220 * k).toInt(), 255, 255, 255); canvas.drawCircle(x, y, s, paint)
            }
            EffectKind.RAIN -> {
                paint.strokeWidth = maxOf(1f, W * 0.003f)
                particles(canvas, W, H, u, 90, k, 0.6f) { x, y, s, _ ->
                    paint.color = Color.argb((150 * k).toInt(), 200, 220, 255)
                    canvas.drawLine(x, y, x - s * 0.8f, y + s * 6f, paint)
                }
            }
            EffectKind.HEARTS -> particles(canvas, W, H, u, 26, k, -0.08f) { x, y, s, i ->
                paint.color = Color.argb((230 * k).toInt(), 255, 60 + (rnd(i, 9) * 80).toInt(), 120)
                canvas.drawPath(heart(x, y, s * 3.2f), paint)
            }
            EffectKind.CONFETTI -> particles(canvas, W, H, u, 80, k, 0.14f) { x, y, s, i ->
                val colors = intArrayOf(0xFFFF3B30.toInt(), 0xFFFFCC00.toInt(), 0xFF34C759.toInt(), 0xFF007AFF.toInt(), 0xFFFF2D55.toInt(), 0xFF19D3C5.toInt())
                paint.color = colors[i % colors.size]; paint.alpha = (255 * k).toInt()
                canvas.save(); canvas.rotate(u * 0.3f * (rnd(i, 4) - 0.5f) + i * 37f, x, y)
                canvas.drawRect(x - s, y - s * 0.5f, x + s, y + s * 0.5f, paint)
                canvas.restore()
            }
            EffectKind.STARS -> {
                for (i in 0 until 40) {
                    val x = rnd(i, 1) * W; val y = rnd(i, 2) * H
                    val tw = (0.5f + 0.5f * sin(u / 1000f * (2f + rnd(i, 3) * 4f) + i)).coerceIn(0f, 1f)
                    val s = (W * 0.008f + rnd(i, 5) * W * 0.012f) * tw
                    paint.color = Color.argb((255 * k * tw).toInt(), 255, 245, 200)
                    canvas.drawPath(sparkle(x, y, s), paint)
                }
            }
            EffectKind.GRAIN -> {
                val frame = (u / 50f).toInt()
                paint.strokeWidth = maxOf(1f, W * 0.002f)
                for (i in 0 until 260) {
                    val x = rnd(i, frame) * W; val y = rnd(i, frame + 7777) * H
                    paint.color = if (i % 2 == 0) Color.argb((70 * k).toInt(), 255, 255, 255) else Color.argb((70 * k).toInt(), 0, 0, 0)
                    canvas.drawPoint(x, y, paint)
                }
                // vertical scratch
                if (rnd(frame, 3) > 0.6f) {
                    paint.color = Color.argb((60 * k).toInt(), 255, 255, 255)
                    val x = rnd(frame, 11) * W
                    canvas.drawLine(x, 0f, x, H, paint)
                }
            }
            EffectKind.BUBBLES -> {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = maxOf(1f, W * 0.003f)
                particles(canvas, W, H, u, 30, k, -0.1f) { x, y, s, _ ->
                    paint.color = Color.argb((180 * k).toInt(), 220, 240, 255); canvas.drawCircle(x, y, s * 2.5f, paint)
                }
                paint.style = Paint.Style.FILL
            }
            EffectKind.FIREWORKS -> {
                for (b in 0 until 4) {
                    val period = 1600f
                    val local = (u + b * 400f) % period
                    val cycle = ((u + b * 400f) / period).toInt()
                    val cx = (0.2f + rnd(cycle * 7 + b, 1) * 0.6f) * W
                    val cy = (0.15f + rnd(cycle * 7 + b, 2) * 0.4f) * H
                    val pr = local / period
                    val col = Color.HSVToColor(floatArrayOf(rnd(cycle * 7 + b, 3) * 360f, 0.7f, 1f))
                    paint.color = col; paint.alpha = ((1f - pr) * 255 * k).toInt()
                    for (j in 0 until 24) {
                        val a = j / 24f * 2f * PI.toFloat()
                        val r = pr * W * 0.25f
                        canvas.drawCircle(cx + cos(a) * r, cy + sin(a) * r + pr * pr * H * 0.05f, W * 0.006f, paint)
                    }
                }
            }
            EffectKind.LIGHT_LEAK -> {
                val ph = u / 3000f
                val x = (0.5f + 0.5f * sin(ph * 2f * PI.toFloat())) * W
                paint.shader = RadialGradient(x, H * 0.2f, maxOf(W, H) * 0.6f,
                    intArrayOf(Color.argb((150 * k).toInt(), 255, 140, 40), Color.argb((60 * k).toInt(), 255, 60, 90), Color.TRANSPARENT),
                    floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, W, H, paint)
                paint.shader = null
            }
            EffectKind.VHS -> {
                paint.color = Color.argb((40 * k).toInt(), 0, 0, 0)
                var y = 0f
                val step = maxOf(2f, H / 240f)
                while (y < H) { canvas.drawRect(0f, y, W, y + step / 2, paint); y += step }
                val band = (u / 4f) % (H * 1.3f) - H * 0.15f
                paint.color = Color.argb((50 * k).toInt(), 255, 255, 255)
                canvas.drawRect(0f, band, W, band + H * 0.03f, paint)
                paint.color = Color.argb((220 * k).toInt(), 255, 255, 255)
                paint.textSize = W * 0.05f
                canvas.drawText("PLAY ▶", W * 0.06f, H * 0.08f, paint)
            }
            EffectKind.REC -> {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = maxOf(2f, W * 0.006f)
                paint.color = Color.argb((230 * k).toInt(), 255, 255, 255)
                val m = W * 0.06f; val len = W * 0.08f
                canvas.drawLine(m, m, m + len, m, paint); canvas.drawLine(m, m, m, m + len, paint)
                canvas.drawLine(W - m, m, W - m - len, m, paint); canvas.drawLine(W - m, m, W - m, m + len, paint)
                canvas.drawLine(m, H - m, m + len, H - m, paint); canvas.drawLine(m, H - m, m, H - m - len, paint)
                canvas.drawLine(W - m, H - m, W - m - len, H - m, paint); canvas.drawLine(W - m, H - m, W - m, H - m - len, paint)
                paint.style = Paint.Style.FILL
                if ((u / 500).toInt() % 2 == 0) { paint.color = Color.argb((255 * k).toInt(), 255, 40, 40); canvas.drawCircle(m * 1.6f, m * 1.9f, W * 0.015f, paint) }
                paint.color = Color.argb((230 * k).toInt(), 255, 255, 255); paint.textSize = W * 0.04f
                canvas.drawText("REC", m * 1.6f + W * 0.03f, m * 1.9f + W * 0.014f, paint)
                val sec = (u / 1000).toInt()
                canvas.drawText("%02d:%02d".format(sec / 60, sec % 60), W - m - W * 0.16f, m * 1.9f + W * 0.014f, paint)
            }
            EffectKind.LENS_FLARE -> {
                val ph = u / 4000f
                val fx = (0.15f + 0.7f * ((sin(ph * PI.toFloat()) + 1f) / 2f)) * W
                val fy = H * 0.22f
                paint.shader = RadialGradient(fx, fy, W * 0.35f, intArrayOf(Color.argb((200 * k).toInt(), 255, 245, 220), Color.argb((60 * k).toInt(), 255, 200, 120), Color.TRANSPARENT),
                    floatArrayOf(0f, 0.25f, 1f), Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, W, H, paint); paint.shader = null
                // ghosts along the line through the centre
                val cx = W / 2; val cy = H / 2
                for (i in 1..5) {
                    val f = i * 0.45f
                    val gx = fx + (cx - fx) * f * 2f; val gy = fy + (cy - fy) * f * 2f
                    paint.color = Color.HSVToColor((40 * k).toInt(), floatArrayOf((i * 60f) % 360f, 0.5f, 1f))
                    canvas.drawCircle(gx, gy, W * (0.02f + 0.015f * i), paint)
                }
                paint.strokeWidth = maxOf(1f, W * 0.003f); paint.color = Color.argb((120 * k).toInt(), 255, 240, 200)
                canvas.drawLine(fx - W * 0.4f, fy, fx + W * 0.4f, fy, paint)
            }
            EffectKind.GLOW_EDGES -> {
                val hue = (u / 20f) % 360f
                val c = Color.HSVToColor(floatArrayOf(hue, 0.7f, 1f))
                paint.style = Paint.Style.STROKE
                for (i in 0 until 6) {
                    paint.strokeWidth = W * 0.012f * (6 - i)
                    paint.color = c; paint.alpha = (35 * k).toInt()
                    canvas.drawRect(0f, 0f, W, H, paint)
                }
                paint.style = Paint.Style.FILL
            }
            EffectKind.SPARKLE_GLOW -> {
                for (i in 0 until 60) {
                    val x = rnd(i, 11) * W; val y = rnd(i, 12) * H
                    val tw = (0.5f + 0.5f * sin(u / 1000f * (3f + rnd(i, 13) * 6f) + i)).coerceIn(0f, 1f)
                    val sz = (W * 0.004f + rnd(i, 14) * W * 0.01f) * tw
                    paint.color = Color.argb((80 * k * tw).toInt(), 255, 230, 160)
                    canvas.drawCircle(x, y, sz * 3f, paint)
                    paint.color = Color.argb((255 * k * tw).toInt(), 255, 255, 255)
                    canvas.drawPath(sparkle(x, y, sz), paint)
                }
            }
            EffectKind.BOKEH -> {
                for (i in 0 until 18) {
                    val x = (rnd(i, 21) * W + sin(u / 3000f + i) * W * 0.03f)
                    val y = (rnd(i, 22) * H + cos(u / 3500f + i) * H * 0.03f)
                    val r = W * (0.03f + rnd(i, 23) * 0.06f)
                    val c = Color.HSVToColor(floatArrayOf(20f + rnd(i, 24) * 40f, 0.5f, 1f))
                    paint.shader = RadialGradient(x, y, r, intArrayOf(Color.argb((110 * k).toInt(), Color.red(c), Color.green(c), Color.blue(c)), Color.argb((60 * k).toInt(), Color.red(c), Color.green(c), Color.blue(c)), Color.TRANSPARENT),
                        floatArrayOf(0f, 0.8f, 1f), Shader.TileMode.CLAMP)
                    canvas.drawCircle(x, y, r, paint)
                }
                paint.shader = null
            }
            EffectKind.SPOTLIGHT -> {
                val x = (0.5f + 0.25f * sin(u / 1500f)) * W
                paint.shader = RadialGradient(x, H * 0.45f, minOf(W, H) * 0.45f,
                    intArrayOf(Color.TRANSPARENT, Color.argb((210 * k).toInt(), 0, 0, 0)), floatArrayOf(0.6f, 1f), Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, W, H, paint)
                paint.shader = null
            }
            else -> {}
        }
    }

    private inline fun particles(canvas: Canvas, W: Float, H: Float, u: Float, n: Int, k: Float, speedFrac: Float,
                                 drawOne: (Float, Float, Float, Int) -> Unit) {
        for (i in 0 until (n * k.coerceIn(0.3f, 1f)).toInt()) {
            val size = W * (0.004f + rnd(i, 2) * 0.008f)
            val speed = H * abs(speedFrac) * (0.5f + rnd(i, 3)) / 1000f
            val drift = sin(u / 1000f * (0.5f + rnd(i, 6)) + i) * W * 0.02f
            val span = H + size * 12
            var y = (rnd(i, 1) * span + u * speed) % span
            if (speedFrac < 0) y = span - y
            y -= size * 6
            val x = rnd(i, 0) * W + drift
            drawOne(x, y, size, i)
        }
    }

    private fun heart(x: Float, y: Float, s: Float): Path {
        val p = Path()
        p.moveTo(x, y + s * 0.3f)
        p.cubicTo(x - s * 0.9f, y - s * 0.3f, x - s * 0.4f, y - s * 0.9f, x, y - s * 0.35f)
        p.cubicTo(x + s * 0.4f, y - s * 0.9f, x + s * 0.9f, y - s * 0.3f, x, y + s * 0.3f)
        p.close()
        return p
    }

    private fun sparkle(x: Float, y: Float, s: Float): Path {
        val p = Path()
        p.moveTo(x, y - s * 2)
        p.quadTo(x, y, x + s * 2, y)
        p.quadTo(x, y, x, y + s * 2)
        p.quadTo(x, y, x - s * 2, y)
        p.quadTo(x, y, x, y - s * 2)
        p.close()
        return p
    }
}

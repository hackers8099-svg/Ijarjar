package so.ijarjar.app.render

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextPaint
import java.text.Bidi
import java.text.BreakIterator
import so.ijarjar.app.model.Easing
import so.ijarjar.app.model.Layer
import so.ijarjar.app.model.TextAnim
import so.ijarjar.app.model.TextLoop
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Letter-by-letter and word-by-word text animation (Apple / After Effects / Premiere style),
 * plus caption styles like karaoke and word highlight. Draws straight onto the canvas.
 */
object TextAnimator {

    private class Unit(val start: Int, val end: Int, val x: Float, val w: Float, val baseline: Float, val top: Float, val bottom: Float) {
        val cx get() = x + w / 2f
        val cy get() = (top + bottom) / 2f
    }

    private class State {
        var alpha = 1f; var dx = 0f; var dy = 0f; var scale = 1f; var rot = 0f; var blur = 0f
        var color: Int? = null; var box: Int = 0
        fun reset() { alpha = 1f; dx = 0f; dy = 0f; scale = 1f; rot = 0f; blur = 0f; color = null; box = 0 }
    }

    private fun wordBased(a: TextAnim, l: Layer? = null) = (a == TextAnim.CUSTOM && l != null && l.taUnit == 1) || a == TextAnim.WORD_FADE_UP || a == TextAnim.WORD_FADE_DOWN ||
        a == TextAnim.WORD_SLIDE_LEFT || a == TextAnim.WORD_SLIDE_RIGHT || a == TextAnim.WORD_POP

    private fun wordLoop(a: TextLoop) = a == TextLoop.KARAOKE || a == TextLoop.WORD_HIGHLIGHT || a == TextLoop.WORD_POP

    private fun units(sp: TextSpec, words: Boolean, lines: Boolean = false): List<Unit> {
        val text = sp.raw
        val lay = sp.layout
        val list = ArrayList<Unit>()
        if (lines) {
            for (ln in 0 until lay.lineCount) {
                val s0 = lay.getLineStart(ln); val e0 = lay.getLineEnd(ln)
                if (text.substring(s0, e0).isBlank()) continue
                val x0 = lay.getLineLeft(ln); val x1 = lay.getLineRight(ln)
                list.add(Unit(s0, e0, x0, x1 - x0, lay.getLineBaseline(ln).toFloat(), lay.getLineTop(ln).toFloat(), lay.getLineBottom(ln).toFloat()))
            }
            return list
        }
        val rtl = Bidi.requiresBidi(text.toCharArray(), 0, text.length)
        val it = if (words || rtl) BreakIterator.getWordInstance() else BreakIterator.getCharacterInstance()
        it.setText(text)
        var s = it.first()
        var e = it.next()
        while (e != BreakIterator.DONE) {
            val piece = text.substring(s, e)
            if (piece.isNotBlank()) {
                val line = lay.getLineForOffset(s)
                val x0 = lay.getPrimaryHorizontal(s)
                val lineEnd = lay.getLineEnd(line)
                val x1 = if (e >= lineEnd) x0 + sp.paint.measureText(text, s, e) else lay.getPrimaryHorizontal(e)
                val left = minOf(x0, x1)
                list.add(Unit(s, e, left, abs(x1 - x0), lay.getLineBaseline(line).toFloat(), lay.getLineTop(line).toFloat(), lay.getLineBottom(line).toFloat()))
            }
            s = e
            e = it.next()
        }
        return list
    }

    /** Staggered progress of unit [i] of [n] for overall progress [p] (0..1). */
    private fun stagger(p: Float, i: Int, n: Int, window: Float = 0.35f): Float {
        if (n <= 1) return p.coerceIn(0f, 1f)
        val d = (1f - window) / (n - 1)
        return ((p - i * d) / window).coerceIn(0f, 1f)
    }

    private fun hash(i: Int, salt: Int): Float {
        var x = i * 374761393 + salt * 668265263
        x = (x xor (x ushr 13)) * 1274126177
        return ((x xor (x ushr 16)) and 0xFFFF) / 65535f
    }

    private fun mix(a: Int, b: Int, f: Float): Int {
        val t = f.coerceIn(0f, 1f)
        return Color.argb(
            (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t).toInt(),
            (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt())
    }

    /** Applies an in/out animation with unit progress u (0 = hidden, 1 = settled). */
    private fun applyAnim(st: State, a: TextAnim, u: Float, i: Int, n: Int, px: Float, l: Layer, t: Long) {
        val f = Ease.apply(Easing.EASE_OUT, u)
        val g = 1f - f
        when (a) {
            TextAnim.NONE -> {}
            TextAnim.APPLE -> { st.alpha *= f; st.blur = maxOf(st.blur, g); st.dy += g * px * 0.12f }
            TextAnim.CUSTOM -> {
                val e = Ease.apply(l.taEase, u)
                val h = 1f - e
                st.dx += h * l.taDx * px; st.dy += h * l.taDy * px
                st.scale *= l.taScale + (1f - l.taScale) * e
                st.rot += h * l.taRot
                st.alpha *= (l.taOpacity + (1f - l.taOpacity) * e).coerceIn(0f, 1f)
                st.blur = maxOf(st.blur, h * l.taBlur)
            }
            TextAnim.LETTER_FADE -> st.alpha *= f
            TextAnim.LETTER_RISE -> { st.dy += g * px * 0.7f; st.alpha *= f }
            TextAnim.LETTER_DROP -> {
                st.dy -= (1f - Ease.apply(Easing.BOUNCE, u)) * px * 1.2f; st.alpha *= minOf(1f, u * 4f)
            }
            TextAnim.LETTER_POP -> { st.scale *= Ease.apply(Easing.BACK, u).coerceAtLeast(0f); st.alpha *= minOf(1f, u * 3f) }
            TextAnim.LETTER_SPIN -> { st.rot += g * -200f; st.scale *= f; st.alpha *= f }
            TextAnim.LETTER_ZOOM -> { st.scale *= 1f + g * 2.5f; st.alpha *= f }
            TextAnim.TRACKING -> {}
            TextAnim.COLOR_IN -> { st.alpha *= f; st.color = mix(l.highlightColor, l.textColor, Ease.apply(Easing.EASE_IN_OUT, u)) }
            TextAnim.RANDOM -> { st.alpha *= f; st.blur = maxOf(st.blur, g * 0.6f) }
            TextAnim.WORD_FADE_UP -> { st.dy += g * px * 0.6f; st.alpha *= f }
            TextAnim.WORD_FADE_DOWN -> { st.dy -= g * px * 0.6f; st.alpha *= f }
            TextAnim.WORD_SLIDE_LEFT -> { st.dx -= g * px * 2f; st.alpha *= f }
            TextAnim.WORD_SLIDE_RIGHT -> { st.dx += g * px * 2f; st.alpha *= f }
            TextAnim.WORD_POP -> { st.scale *= Ease.apply(Easing.BACK, u).coerceAtLeast(0f); st.alpha *= minOf(1f, u * 3f) }
            TextAnim.TYPEWRITER -> st.alpha *= if (u > 0f) 1f else 0f
            TextAnim.GLITCH -> {
                st.alpha *= if (u > 0f) 1f else 0f
                if (u < 1f) {
                    val k = (t / 50).toInt()
                    st.dx += (hash(i, k) - 0.5f) * px * 0.6f * g
                    st.dy += (hash(i, k + 99) - 0.5f) * px * 0.3f * g
                    if (hash(i, k + 7) > 0.6f) st.color = if (hash(i, k + 3) > 0.5f) 0xFF00FFFF.toInt() else 0xFFFF00FF.toInt()
                }
            }
            TextAnim.BOUNCE_IN -> { st.scale *= Ease.apply(Easing.ELASTIC, u).coerceAtLeast(0f); st.alpha *= minOf(1f, u * 4f) }
        }
    }

    private fun applyLoop(st: State, a: TextLoop, s: Float, i: Int, n: Int, px: Float, l: Layer, u: Unit, layoutW: Float) {
        val tau = 2f * PI.toFloat()
        when (a) {
            TextLoop.NONE -> {}
            TextLoop.WAVE -> st.dy += sin(s * tau + i * 0.55f) * px * 0.15f
            TextLoop.BOUNCE -> st.dy -= abs(sin(s * PI.toFloat() * 1.6f + i * 0.45f)) * px * 0.25f
            TextLoop.KARAOKE, TextLoop.WORD_HIGHLIGHT, TextLoop.WORD_POP -> {}   // handled in draw() per word
            TextLoop.SHIMMER -> {
                val pos = u.cx / layoutW.coerceAtLeast(1f)
                val band = ((s * 0.8f) % 1.6f) - 0.3f
                val k = (1f - abs(pos - band) / 0.18f).coerceIn(0f, 1f)
                st.color = mix(l.textColor, Color.WHITE, k * 0.9f)
            }
            TextLoop.RAINBOW -> st.color = Color.HSVToColor(floatArrayOf(((i * 30f + s * 120f) % 360f), 0.8f, 1f))
            TextLoop.JITTER -> {
                val k = (s * 20).toInt()
                st.dx += (hash(i, k) - 0.5f) * px * 0.08f; st.dy += (hash(i, k + 5) - 0.5f) * px * 0.08f
            }
            TextLoop.FLICKER -> { val k = (s * 12).toInt(); if (hash(i, k) > 0.85f) st.alpha *= 0.15f }
        }
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun draw(canvas: Canvas, l: Layer, sp: TextSpec, m: Matrix, t: Long, layerAlpha: Int) {
        val dur = l.durationMs
        val inMs = minOf(l.animInMs, dur / 2).coerceAtLeast(1)
        val outMs = minOf(l.animOutMs, dur / 2).coerceAtLeast(1)
        val since = t - l.startMs
        val until = l.endMs - t
        val inActive = l.textIn != TextAnim.NONE && since < inMs
        val outActive = l.textOut != TextAnim.NONE && until < outMs
        // letters / words / lines: chosen by the user, else the animation's own way
        fun unitOf(a: TextAnim): Int = when {
            l.textUnit in 0..2 -> l.textUnit
            a == TextAnim.CUSTOM -> l.taUnit
            wordBased(a, l) -> 1
            else -> 0
        }
        val uIn = if (inActive) unitOf(l.textIn) else -1
        val uOut = if (outActive) unitOf(l.textOut) else -1
        val animWords = uIn == 1 || uOut == 1
        val letterAnim = uIn == 0 || uOut == 0
        val wl = wordLoop(l.textLoop)
        val words = animWords || (wl && !letterAnim)
        val lines = uIn == 2 || uOut == 2
        val list = units(sp, words, lines)
        // caption word timing: each word gets time by its length; the last word ends a little before the clip
        val wordList = if (wl) (if (words && !lines) list else units(sp, true)) else emptyList()
        var curWord = -1; var curFrac = 0f
        if (wl && wordList.isNotEmpty()) {
            val weights = wordList.map { (it.end - it.start) + 2f }
            val total = weights.sum()
            var pos = (since.toFloat() / (dur * 0.94f).coerceAtLeast(1f)).coerceIn(0f, 0.9999f) * total
            for ((k, w) in weights.withIndex()) { if (pos < w) { curWord = k; curFrac = pos / w; break }; pos -= w }
            if (curWord < 0) { curWord = wordList.size - 1; curFrac = 1f }
        }
        fun wordOf(u: Unit): Int = if (wordList.isEmpty()) -1 else wordList.indexOfFirst { u.start >= it.start && u.start < it.end }
        val n = list.size
        val px = sp.textPx
        val inP = if (inActive) since.toFloat() / inMs else 1f
        val outP = if (outActive) until.toFloat() / outMs else 1f
        val order = IntArray(n) { it }
        // order and overlap (Custom tab) shape every text animation, ready-made ones too
        if ((inActive && l.textIn == TextAnim.RANDOM) || (outActive && l.textOut == TextAnim.RANDOM) || l.taOrder == 3) {
            val sorted = (0 until n).sortedBy { hash(it, 42) }
            for ((rank, idx) in sorted.withIndex()) order[idx] = rank
        } else if (l.taOrder == 1) {
            for (i in 0 until n) order[i] = n - 1 - i
        } else if (l.taOrder == 2) {
            val c = (n - 1) / 2f
            val sorted = (0 until n).sortedBy { abs(it - c) }
            for ((rank, idx) in sorted.withIndex()) order[idx] = rank
        }
        val window = l.taOverlap.coerceIn(0.03f, 1f)

        canvas.save()
        canvas.concat(m)
        // background box fades with the text
        if (l.bgColor != 0) {
            bgPaint.color = l.bgColor
            bgPaint.alpha = (Color.alpha(l.bgColor) * layerAlpha / 255 * minOf(inP, outP).coerceIn(0f, 1f)).toInt()
            canvas.drawRoundRect(RectF(0f, 0f, (sp.bw - sp.depthPx).toFloat(), (sp.bh - sp.depthPx).toFloat()), px * l.bgRound, px * l.bgRound, bgPaint)
        }
        canvas.translate(sp.pad.toFloat(), sp.pad.toFloat())

        val fill = TextPaint(sp.paint)
        if (l.textColor2 != 0) fill.shader = LinearGradient(0f, 0f, 0f, sp.layout.height.toFloat(), l.textColor, l.textColor2, Shader.TileMode.CLAMP)
        val stroke = if (l.strokeColor != 0) TextPaint(sp.paint).apply {
            style = Paint.Style.STROKE; strokeWidth = px * l.strokeWidth.coerceIn(0.01f, 0.5f); strokeJoin = Paint.Join.ROUND
        } else null
        val depth = if (sp.depthPx > 0) TextPaint(sp.paint).apply { color = l.depthColor } else null
        val shadow = if (l.shadow) TextPaint(sp.paint).apply { color = l.shadowColor; val r = px * l.shadowBlur; if (r > 0.5f) maskFilter = BlurMaskFilter(r, BlurMaskFilter.Blur.NORMAL) } else null
        val glow = if (l.glowColor != 0) TextPaint(sp.paint).apply {
            color = l.glowColor; maskFilter = BlurMaskFilter(px * (0.1f + l.glowSize * 0.5f), BlurMaskFilter.Blur.NORMAL)
            style = Paint.Style.FILL_AND_STROKE; strokeWidth = px * 0.08f
        } else null
        val st = State()
        val s = since / 1000f
        val center = (n - 1) / 2f
        var lastVisible = -1
        val textAlpha = minOf(inP, outP).coerceIn(0f, 1f)
        // word highlight: one rounded box behind the word being said (eases in as the word starts)
        if (l.textLoop == TextLoop.WORD_HIGHLIGHT && curWord >= 0) {
            val fm = sp.paint.fontMetrics
            val padX = px * 0.14f
            fun box(w: Unit) = RectF(w.x - padX, w.baseline + fm.ascent * 0.92f - px * 0.04f, w.x + w.w + padX, w.baseline + fm.descent * 0.9f + px * 0.04f)
            val w = wordList[curWord]
            val r = box(w)
            val k = minOf(1f, curFrac * 5f)
            var alpha = Color.alpha(l.highlightColor) * layerAlpha / 255f * textAlpha
            var sc = 1f
            when (l.hlAnim) {
                0 -> sc = 0.85f + 0.15f * Ease.apply(Easing.BACK, k)
                1 -> if (curWord > 0) {
                    val p0 = box(wordList[curWord - 1])
                    val e = Ease.apply(Easing.EASE_IN_OUT, minOf(1f, curFrac * 4f))
                    if (p0.top == r.top || abs(p0.top - r.top) < px * 0.2f) {
                        r.set(p0.left + (r.left - p0.left) * e, r.top, p0.right + (r.right - p0.right) * e, r.bottom)
                    }
                }
                2 -> alpha *= k
                3 -> r.right = r.left + (r.right - r.left) * Ease.apply(Easing.EASE_OUT, minOf(1f, curFrac * 3f))
            }
            bgPaint.color = l.highlightColor
            bgPaint.alpha = alpha.toInt().coerceIn(0, 255)
            val rad = r.height() / 2f * l.hlRound.coerceIn(0f, 1f)
            canvas.save(); canvas.scale(sc, sc, r.centerX(), r.centerY())
            canvas.drawRoundRect(r, rad, rad, bgPaint)
            canvas.restore()
        }
        for ((i, u) in list.withIndex()) {
            st.reset()
            if (inActive) {
                if (l.textIn == TextAnim.TRACKING) {
                    val f = Ease.apply(Easing.EASE_OUT, inP)
                    st.dx += (i - center) * (1f - f) * px * 0.6f; st.alpha *= f; st.blur = maxOf(st.blur, (1f - f) * 0.5f)
                } else applyAnim(st, l.textIn, stagger(inP, order[i], n, window), i, n, px, l, t)
            }
            if (outActive) {
                if (l.textOut == TextAnim.TRACKING) {
                    val f = Ease.apply(Easing.EASE_OUT, outP)
                    st.dx += (i - center) * (1f - f) * px * 0.6f; st.alpha *= f
                } else applyAnim(st, l.textOut, stagger(outP, n - 1 - order[i], n, window), i, n, px, l, t)
            }
            applyLoop(st, l.textLoop, s, i, n, px, l, u, sp.layoutW.toFloat())
            if (st.alpha <= 0.004f) continue
            lastVisible = i
            canvas.save()
            val wi = if (wl) wordOf(u) else -1
            // word pop: the spoken word grows around its own centre (letters stay together)
            if (l.textLoop == TextLoop.WORD_POP && wi == curWord && wi >= 0) {
                val w = wordList[wi]
                val k = Ease.apply(Easing.BACK, minOf(1f, curFrac * 5f))
                val sc = 1f + 0.18f * k
                canvas.scale(sc, sc, w.cx, w.cy)
                canvas.translate(0f, -px * 0.06f * k)
                st.color = l.highlightColor
            }
            if (l.textLoop == TextLoop.KARAOKE && wi >= 0 && wi < curWord) st.color = l.highlightColor
            if (l.textLoop == TextLoop.WORD_HIGHLIGHT && wi >= 0 && wi == curWord && l.hlTextColor != 0) st.color = l.hlTextColor
            canvas.translate(u.cx + st.dx, u.cy + st.dy)
            if (st.rot != 0f) canvas.rotate(st.rot)
            if (st.scale != 1f) canvas.scale(st.scale, st.scale)
            canvas.translate(-u.cx, -u.cy)
            val a = (st.alpha.coerceIn(0f, 1f) * layerAlpha).toInt()
            val blur = if (st.blur > 0.02f) BlurMaskFilter(st.blur * px * 0.35f, BlurMaskFilter.Blur.NORMAL) else null
            if (depth != null) {
                depth.alpha = a; depth.maskFilter = blur
                for (k in sp.depthPx downTo 1) canvas.drawText(sp.raw, u.start, u.end, u.x + k, u.baseline + k, depth)
            }
            if (glow != null) {
                glow.alpha = a
                canvas.drawText(sp.raw, u.start, u.end, u.x, u.baseline, glow)
            }
            if (shadow != null) { shadow.alpha = a * android.graphics.Color.alpha(l.shadowColor) / 255; canvas.drawText(sp.raw, u.start, u.end, u.x + px * l.shadowDx, u.baseline + px * l.shadowDy, shadow) }
            if (stroke != null) {
                stroke.color = l.strokeColor; stroke.alpha = a; stroke.maskFilter = blur
                canvas.drawText(sp.raw, u.start, u.end, u.x, u.baseline, stroke)
            }
            val c = st.color
            if (c != null) { fill.shader = null; fill.color = c } else {
                fill.color = l.textColor
                if (l.textColor2 != 0) fill.shader = LinearGradient(0f, 0f, 0f, sp.layout.height.toFloat(), l.textColor, l.textColor2, Shader.TileMode.CLAMP)
            }
            fill.alpha = a
            fill.maskFilter = blur
            canvas.drawText(sp.raw, u.start, u.end, u.x, u.baseline, fill)
            // karaoke: the word being sung fills with the highlight colour from left to right
            if (l.textLoop == TextLoop.KARAOKE && wi >= 0 && wi == curWord) {
                val w = wordList[wi]
                val bx = w.x + w.w * curFrac
                if (bx > u.x) {
                    canvas.save()
                    canvas.clipRect(u.x - px, u.top - px, minOf(bx, u.x + u.w + px), u.bottom + px)
                    fill.shader = null; fill.color = l.highlightColor; fill.alpha = a
                    canvas.drawText(sp.raw, u.start, u.end, u.x, u.baseline, fill)
                    canvas.restore()
                }
            }
            canvas.restore()
        }
        // typewriter cursor
        if (inActive && l.textIn == TextAnim.TYPEWRITER && lastVisible >= 0 && lastVisible < n - 1 && (t / 400) % 2 == 0L) {
            val u = list[lastVisible]
            fill.shader = null; fill.color = l.textColor; fill.alpha = layerAlpha; fill.maskFilter = null
            canvas.drawRect(u.x + u.w + px * 0.05f, u.top + px * 0.15f, u.x + u.w + px * 0.12f, u.bottom - px * 0.15f, fill)
        }
        canvas.restore()
    }
}

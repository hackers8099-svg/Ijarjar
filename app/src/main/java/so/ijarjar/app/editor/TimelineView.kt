package so.ijarjar.app.editor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.content.ContextCompat
import so.ijarjar.app.L
import so.ijarjar.app.R
import so.ijarjar.app.media.MediaUtils
import so.ijarjar.app.model.AudioKind
import so.ijarjar.app.model.AudioTrack
import so.ijarjar.app.model.Clip
import so.ijarjar.app.model.Layer
import so.ijarjar.app.model.LayerKind
import so.ijarjar.app.model.MediaKind
import so.ijarjar.app.model.Project
import so.ijarjar.app.model.TransitionKind
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * CapCut style timeline: the playhead stays in the middle and the tracks scroll under it.
 * Rows: ruler, main track (clips + transition buttons), audio tracks, then one row per layer.
 */
class TimelineView(context: Context) : View(context) {

    sealed class Sel {
        data class ClipSel(val index: Int) : Sel()
        data class LayerSel(val id: String) : Sel()
        data class AudioSel(val id: String) : Sel()
    }

    interface Listener {
        fun onScrub(timeMs: Long)
        fun onSelect(sel: Sel?)
        fun onTransitionTap(clipIndex: Int)
        fun onKeyframeTap(timeMs: Long)
        fun onTimelineEditing()
        fun onTimelineEdited()
    }

    var project: Project? = null
        set(v) { field = v; invalidate() }
    var timeMs: Long = 0
        set(v) { field = v; invalidate() }
    var selection: Sel? = null
        set(v) { field = v; invalidate() }   // show the pick at once
    /** "Select" mode: ids of clips, audio and layers that are picked. */
    var multi: Set<String> = emptySet()
        set(v) { field = v; invalidate() }
    var listener: Listener? = null

    private var pxPerMs = dp(60f) / 1000f
    private var vScroll = 0f

    private val rulerH = dp(22f)
    private val clipH = dp(54f)
    private val audioH = dp(26f)
    private val layerH = dp(26f)
    private val gap = dp(6f)
    private val handleW = dp(14f)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9A9AA5.toInt(); textSize = dp(10f) }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = dp(11f) }

    private val thumbs = HashMap<String, Bitmap?>()
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val icons = HashMap<Int, Drawable?>()

    private val linkColors = intArrayOf(0xFF19D3C5.toInt(), 0xFFFFB020.toInt(), 0xFF8B7CFF.toInt(), 0xFF4CD964.toInt(), 0xFFFF6B6B.toInt())

    private fun dp(v: Float) = v * resources.displayMetrics.density

    private fun icon(res: Int): Drawable? = icons.getOrPut(res) { ContextCompat.getDrawable(context, res)?.mutate() }

    private fun drawIcon(canvas: Canvas, res: Int, x: Float, cy: Float, size: Float, color: Int = Color.WHITE) {
        val d = icon(res) ?: return
        d.setTint(color)
        d.setBounds(x.toInt(), (cy - size / 2).toInt(), (x + size).toInt(), (cy + size / 2).toInt())
        d.draw(canvas)
    }

    private fun xOf(t: Long) = width / 2f + (t - timeMs) * pxPerMs
    private fun tOf(x: Float) = timeMs + ((x - width / 2f) / pxPerMs).toLong()

    private fun clipTop() = rulerH + gap - vScroll
    private fun audioTop(i: Int) = clipTop() + clipH + gap + i * (audioH + gap * 0.6f)
    private fun layersTop(): Float {
        val n = max(1, project?.audios?.size ?: 0)
        return audioTop(n) + gap * 0.4f
    }
    private fun layerTop(i: Int) = layersTop() + i * (layerH + gap * 0.6f)

    private fun contentHeight(): Float {
        val n = project?.layers?.size ?: 0
        return layerTop(n) + vScroll + gap
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        val p = project ?: return
        canvas.drawColor(0xFF121216.toInt())
        drawClips(canvas, p)
        drawAudio(canvas, p)
        drawLayers(canvas, p)
        drawGutter(canvas, p)
        drawRuler(canvas)
        paint.color = Color.WHITE
        paint.strokeWidth = dp(2f)
        canvas.drawLine(width / 2f, 0f, width / 2f, height.toFloat(), paint)
        canvas.drawCircle(width / 2f, dp(4f), dp(4f), paint)
    }

    // ------------------------------------------------------------------ left column (CapCut): mute / eye

    private val gutterW get() = dp(44f)

    private fun gutterBtn(canvas: Canvas, cy: Float, res: Int, on: Boolean) {
        paint.color = if (on) 0xFF2A2A33.toInt() else 0xFF3A2228.toInt()
        val r = dp(13f)
        canvas.drawCircle(gutterW / 2f, cy, r, paint)
        drawIcon(canvas, res, gutterW / 2f - dp(8f), cy, dp(16f), if (on) Color.WHITE else 0xFFFF6B6B.toInt())
    }

    private fun drawGutter(canvas: Canvas, p: Project) {
        // fade so the rows slide under the buttons
        paint.shader = android.graphics.LinearGradient(0f, 0f, gutterW + dp(10f), 0f, 0xF2121216.toInt(), 0x00121216, android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRect(0f, rulerH, gutterW + dp(10f), height.toFloat(), paint)
        paint.shader = null
        if (p.clips.isNotEmpty()) {
            val muted = p.clips.all { it.volume == 0f }
            gutterBtn(canvas, clipTop() + clipH / 2, if (muted) R.drawable.ic_mute else R.drawable.ic_volume, !muted)
        }
        for ((i, a) in p.audios.withIndex()) {
            val cy = audioTop(i) + audioH / 2
            if (cy < rulerH || cy > height) continue
            gutterBtn(canvas, cy, if (a.volume == 0f) R.drawable.ic_mute else R.drawable.ic_volume, a.volume != 0f)
        }
        for ((i, l) in p.layers.withIndex()) {
            val cy = layerTop(i) + layerH / 2
            if (cy < rulerH || cy > height) continue
            gutterBtn(canvas, cy, if (l.hidden) R.drawable.ic_eye_off else R.drawable.ic_eye, !l.hidden)
        }
    }

    /** Tap on the left column: mute / unmute sound, hide / show a layer. */
    private fun gutterTap(p: Project, x: Float, y: Float): Boolean {
        if (x > gutterW) return false
        val ct = clipTop()
        if (p.clips.isNotEmpty() && y in ct..(ct + clipH)) {
            val muted = p.clips.all { it.volume == 0f }
            for (c in p.clips) {
                if (muted) { c.volume = if (c.muteVol > 0f) c.muteVol else 1f; c.muteVol = -1f }
                else if (c.volume != 0f) { c.muteVol = c.volume; c.volume = 0f }
            }
            invalidate(); listener?.onTimelineEdited(); return true
        }
        for ((i, a) in p.audios.withIndex()) {
            val top = audioTop(i)
            if (y in (top - dp(3f))..(top + audioH + dp(3f))) {
                if (a.volume == 0f) { a.volume = if (a.muteVol > 0f) a.muteVol else 1f; a.muteVol = -1f }
                else { a.muteVol = a.volume; a.volume = 0f }
                invalidate(); listener?.onTimelineEdited(); return true
            }
        }
        for ((i, l) in p.layers.withIndex()) {
            val top = layerTop(i)
            if (y in (top - dp(3f))..(top + layerH + dp(3f))) {
                l.hidden = !l.hidden
                invalidate(); listener?.onTimelineEdited(); return true
            }
        }
        return false
    }

    private fun drawRuler(canvas: Canvas) {
        paint.color = 0xFF121216.toInt()
        canvas.drawRect(0f, 0f, width.toFloat(), rulerH, paint)
        val stepMs = when {
            pxPerMs * 1000 > dp(120f) -> 500L
            pxPerMs * 1000 > dp(40f) -> 1000L
            pxPerMs * 1000 > dp(15f) -> 5000L
            else -> 10000L
        }
        val start = (tOf(0f) / stepMs - 1) * stepMs
        val end = tOf(width.toFloat()) + stepMs
        var t = max(0L, start)
        paint.color = 0xFF55555F.toInt(); paint.strokeWidth = dp(1f)
        while (t <= end) {
            val x = xOf(t)
            canvas.drawLine(x, rulerH - dp(5f), x, rulerH, paint)
            if (t % (stepMs * 2) == 0L) canvas.drawText(fmt(t), x + dp(2f), rulerH - dp(8f), textPaint)
            t += stepMs
        }
    }

    private fun drawClips(canvas: Canvas, p: Project) {
        var t = 0L
        val top = clipTop()
        for ((i, c) in p.clips.withIndex()) {
            val x0 = xOf(t); val x1 = xOf(t + c.outDurationMs)
            t += c.outDurationMs
            if (x1 < 0 || x0 > width) continue
            val r = RectF(x0 + dp(2f), top, x1 - dp(2f), top + clipH)
            canvas.save()
            canvas.clipRect(r)
            paint.color = 0xFF2A2A33.toInt()
            canvas.drawRect(r, paint)
            drawThumbs(canvas, c, r)
            canvas.restore()
            var bx = r.left + dp(4f)
            val by = r.bottom - dp(10f)
            if (c.hasCurve) {
                drawIcon(canvas, R.drawable.ic_speed, bx, by, dp(13f), 0xFFFFCC00.toInt()); bx += dp(14f)
                canvas.drawText(c.curve.label, bx, by + dp(4f), labelPaint); bx += labelPaint.measureText(c.curve.label) + dp(6f)
            } else if (c.kind == MediaKind.VIDEO && c.speed != 1f) {
                drawIcon(canvas, R.drawable.ic_speed, bx, by, dp(13f)); bx += dp(14f)
                canvas.drawText("${trim(c.speed)}x", bx, by + dp(4f), labelPaint); bx += dp(28f)
            }
            if (c.volume == 0f) { drawIcon(canvas, R.drawable.ic_mute, bx, by, dp(13f)); bx += dp(16f) }
            if (c.reversed) { drawIcon(canvas, R.drawable.ic_reverse, bx, by, dp(13f)); bx += dp(16f) }
            if (!c.adjust.isIdentity()) { drawIcon(canvas, R.drawable.ic_filter, bx, by, dp(13f)); bx += dp(16f) }
            if (c.tScale != 1f || c.tRot != 0f || c.tX != 0f || c.tY != 0f || c.mirror) drawIcon(canvas, R.drawable.ic_canvas, bx, by, dp(13f))
            if ((selection as? Sel.ClipSel)?.index == i || c.id in multi) drawSelection(canvas, r, if (c.id in multi) 0xFF19D3C5.toInt() else Color.WHITE)
        }
        // transition buttons between clips
        var acc = 0L
        for ((i, c) in p.clips.withIndex()) {
            if (i > 0) {
                val x = xOf(acc)
                if (x > -dp(20f) && x < width + dp(20f)) {
                    val r = RectF(x - dp(10f), top + clipH / 2 - dp(10f), x + dp(10f), top + clipH / 2 + dp(10f))
                    paint.color = if (c.transition == TransitionKind.NONE) Color.WHITE else 0xFF19D3C5.toInt()
                    canvas.drawRoundRect(r, dp(4f), dp(4f), paint)
                    drawIcon(canvas, R.drawable.ic_transition, r.left + dp(3f), r.centerY(), dp(14f), Color.BLACK)
                }
            }
            acc += c.outDurationMs
        }
        if (p.clips.isEmpty()) {
            canvas.drawText(L.t("Riix ＋ si aad muuqaal ugu darto", "Tap ＋ to add a video"), width / 2f + dp(10f), top + clipH / 2f, labelPaint)
        }
    }

    private fun drawThumbs(canvas: Canvas, c: Clip, r: RectF) {
        val tw = clipH * 0.75f
        val n = max(1, ((r.width()) / tw).toInt() + 1)
        val step = c.trimmedMs.toFloat() / n
        val firstVisible = max(0, ((0 - r.left) / tw).toInt())
        val lastVisible = min(n - 1, ((width - r.left) / tw).toInt())
        for (k in firstVisible..lastVisible) {
            val srcT = c.trimStartMs + (k * step).toLong()
            val bucket = if (c.kind == MediaKind.IMAGE) 0 else (srcT / 1000) * 1000
            val key = "${c.uri}#$bucket"
            val dst = RectF(r.left + k * tw, r.top, r.left + (k + 1) * tw, r.bottom)
            if (thumbs.containsKey(key)) {
                val b = thumbs[key]
                if (b != null) canvas.drawBitmap(b, centerCrop(b, dst), dst, null)
            } else {
                thumbs[key] = null
                val uri = Uri.parse(c.uri)
                val isVideo = c.kind == MediaKind.VIDEO
                executor.execute {
                    val b = MediaUtils.thumbnail(context, uri, isVideo, bucket, 160)
                    main.post { thumbs[key] = b; invalidate() }
                }
            }
        }
    }

    private fun centerCrop(b: Bitmap, dst: RectF): Rect {
        val br = b.width.toFloat() / b.height
        val dr = dst.width() / dst.height()
        return if (br > dr) {
            val w = (b.height * dr).toInt(); val x = (b.width - w) / 2
            Rect(x, 0, x + w, b.height)
        } else {
            val h = (b.width / dr).toInt(); val y = (b.height - h) / 2
            Rect(0, y, b.width, y + h)
        }
    }

    private fun audioIcon(a: AudioTrack) = when (a.kind) {
        AudioKind.MUSIC -> R.drawable.ic_music
        AudioKind.VOICE -> R.drawable.ic_mic
        AudioKind.EXTRACTED -> R.drawable.ic_waveform
        AudioKind.SOUND -> R.drawable.ic_sound
    }

    private val nameShadow by lazy { Paint(labelPaint).apply { color = 0xAA000000.toInt() } }
    private val smallLabel by lazy { Paint(labelPaint).apply { textSize = labelPaint.textSize * 0.8f; color = 0xDDFFFFFF.toInt() } }

    private fun drawAudio(canvas: Canvas, p: Project) {
        if (p.audios.isEmpty()) {
            val top = audioTop(0)
            drawIcon(canvas, R.drawable.ic_music, xOf(0) + dp(6f), top + audioH / 2, dp(14f), 0xFF9A9AA5.toInt())
            canvas.drawText(L.t("Muusik ma jiro", "No audio"), xOf(0) + dp(24f), top + audioH * 0.65f, textPaint)
            return
        }
        for ((i, a) in p.audios.withIndex()) {
            val top = audioTop(i)
            // a clear gap between pieces so every cut is easy to see
            val r = RectF(xOf(a.startMs) + dp(2f), top + dp(1f), xOf(a.endMs) - dp(2f), top + audioH - dp(1f))
            // CapCut look: dark teal block, one smooth filled cyan wave mirrored around the middle
            val (bg, bar) = when (a.kind) {
                AudioKind.MUSIC -> Pair(0xFF173A3E.toInt(), 0xFF35D0DC.toInt())
                AudioKind.VOICE -> Pair(0xFF3A2C17.toInt(), 0xFFFFB04D.toInt())
                AudioKind.EXTRACTED -> Pair(0xFF173A3E.toInt(), 0xFF35D0DC.toInt())
                AudioKind.SOUND -> Pair(0xFF2E1B3E.toInt(), 0xFFC98BFF.toInt())
            }
            paint.color = bg
            canvas.drawRoundRect(r, dp(4f), dp(4f), paint)
            val wave = so.ijarjar.app.media.Waveform.get(context, a.uri) { invalidate() }
            // samples sit on a fixed time grid (not on screen pixels), so the wave keeps its shape while it
            // scrolls — no flicker / "blur" while playing
            val step = dp(2f)
            val msPer = max(1L, (step / pxPerMs).toLong())
            val mid = r.centerY()
            val maxH = audioH * 0.44f
            val x0 = max(r.left, 0f); val x1 = min(r.right, width.toFloat())
            val tops = ArrayList<Float>()
            val xs = ArrayList<Float>()
            var k = floor((tOf(x0) - a.startMs).toDouble() / msPer).toLong()
            while (true) {
                val tt = a.startMs + k * msPer
                val x = xOf(tt)
                if (x > x1 + step) break
                k++
                if (x < x0 - step) continue
                val local = tt - a.startMs + a.trimStartMs
                val v = if (wave != null && wave.isNotEmpty()) {
                    val i0 = (local / so.ijarjar.app.media.Waveform.BUCKET_MS).toInt().coerceAtLeast(0)
                    val i1 = ((local + msPer) / so.ijarjar.app.media.Waveform.BUCKET_MS).toInt().coerceAtLeast(i0 + 1)
                    var m = 0f
                    for (q in i0 until minOf(i1, wave.size)) m = max(m, wave[q])
                    m * a.volume.coerceIn(0f, 2f)
                } else 0.06f
                tops.add((v.coerceIn(0f, 1f) * maxH).coerceAtLeast(dp(0.6f)))
                xs.add(x)
            }
            if (tops.isNotEmpty()) {
                val path = android.graphics.Path()
                path.moveTo(xs[0], mid)
                for ((i, hh) in tops.withIndex()) path.lineTo(xs[i], mid - hh)
                for (i in tops.indices.reversed()) path.lineTo(xs[i], mid + tops[i])
                path.close()
                canvas.save(); canvas.clipRect(r)
                paint.color = bar
                canvas.drawPath(path, paint)
                canvas.restore()
            }
            canvas.save(); canvas.clipRect(r)
            val lx = max(r.left, 0f) + dp(4f)
            // beat markers
            if (a.beats.isNotEmpty()) {
                paint.color = 0xFFFFCC00.toInt()
                for (bt in a.beats) {
                    val tt = a.startMs + bt - a.trimStartMs
                    if (tt < a.startMs || tt > a.endMs) continue
                    val bx = xOf(tt); if (bx < r.left || bx > r.right) continue
                    canvas.drawCircle(bx, r.bottom - dp(4f), dp(2.5f), paint)
                }
            }
            // name over the wave, like CapCut
            drawIcon(canvas, audioIcon(a), lx + dp(4f), r.centerY(), dp(12f))
            canvas.drawText(a.name, lx + dp(20f), r.centerY() + dp(4f), nameShadow)
            canvas.drawText(a.name, lx + dp(19f), r.centerY() + dp(3.5f), labelPaint)
            canvas.restore()
            if ((selection as? Sel.AudioSel)?.id == a.id || a.id in multi) drawSelection(canvas, r, if (a.id in multi) 0xFF19D3C5.toInt() else Color.WHITE)
        }
    }

    private fun layerColor(l: Layer) = when (l.kind) {
        LayerKind.TEXT -> if (l.isCaption) 0xFF4B6BD6.toInt() else 0xFF7A4BD6.toInt()
        LayerKind.STICKER -> 0xFFC9822B.toInt()
        LayerKind.IMAGE -> 0xFF2B7BC9.toInt()
        LayerKind.VIDEO -> 0xFFB8336A.toInt()
        LayerKind.EFFECT -> 0xFF8A6D1E.toInt()
        LayerKind.SHAPE -> 0xFF2E8B57.toInt()
        LayerKind.ANIMATED -> 0xFFB04BD6.toInt()
        LayerKind.DRAW -> 0xFFD6814B.toInt()
        LayerKind.MODEL3D -> 0xFF4B8BD6.toInt()
    }

    private fun layerIcon(l: Layer) = when (l.kind) {
        LayerKind.TEXT -> R.drawable.ic_text
        LayerKind.STICKER -> R.drawable.ic_sticker
        LayerKind.IMAGE -> R.drawable.ic_image_add
        LayerKind.VIDEO -> R.drawable.ic_overlay
        LayerKind.EFFECT -> R.drawable.ic_effects
        LayerKind.SHAPE -> R.drawable.ic_shape
        LayerKind.ANIMATED -> R.drawable.ic_animation
        LayerKind.DRAW -> R.drawable.ic_pencil
        LayerKind.MODEL3D -> R.drawable.ic_model3d
    }

    private fun drawLayers(canvas: Canvas, p: Project) {
        val groups = p.layers.mapNotNull { it.linkGroup }.distinct()
        val selLayer = (selection as? Sel.LayerSel)?.let { s -> p.layers.firstOrNull { it.id == s.id } }
        for ((i, l) in p.layers.withIndex()) {
            val top = layerTop(i)
            if (top > height || top + layerH < rulerH) continue
            val r = RectF(xOf(l.startMs), top, xOf(l.endMs), top + layerH)
            paint.color = layerColor(l)
            if (l.hidden) paint.alpha = 90
            canvas.drawRoundRect(r, dp(5f), dp(5f), paint)
            paint.alpha = 255
            val g = l.linkGroup
            var labelX = max(r.left, 0f) + dp(5f)
            canvas.save(); canvas.clipRect(r)
            if (g != null) {
                paint.color = linkColors[groups.indexOf(g) % linkColors.size]
                canvas.drawRect(r.left, r.top, r.left + dp(4f), r.bottom, paint)
                drawIcon(canvas, R.drawable.ic_link, labelX, r.centerY(), dp(13f), paint.color)
                labelX += dp(16f)
            }
            drawIcon(canvas, layerIcon(l), labelX, r.centerY(), dp(13f))
            labelX += dp(17f)
            canvas.drawText(layerLabel(l), labelX, top + layerH * 0.68f, labelPaint)
            // keyframe diamonds
            paint.color = 0xFFFFCC00.toInt()
            for (k in l.keyframes) {
                val x = xOf(l.startMs + k.t); val cy = r.bottom - dp(5f); val s = dp(4f)
                val d = Path().apply { moveTo(x, cy - s); lineTo(x + s, cy); lineTo(x, cy + s); lineTo(x - s, cy); close() }
                canvas.drawPath(d, paint)
            }
            canvas.restore()
            if (l.id in multi) drawSelection(canvas, r, 0xFF19D3C5.toInt())
            else if (selLayer?.id == l.id) drawSelection(canvas, r, Color.WHITE)
            else if (g != null && selLayer?.linkGroup == g) {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1.5f); paint.color = 0xFF19D3C5.toInt()
                canvas.drawRoundRect(r, dp(5f), dp(5f), paint)
                paint.style = Paint.Style.FILL
            }
        }
    }

    fun layerLabel(l: Layer): String = when (l.kind) {
        LayerKind.TEXT -> l.text.replace("\n", " ")
        LayerKind.STICKER -> l.text
        LayerKind.IMAGE -> l.name
        LayerKind.VIDEO -> l.name
        LayerKind.EFFECT -> l.effect.label
        LayerKind.SHAPE -> l.shape.label
        LayerKind.ANIMATED -> l.name
        LayerKind.DRAW -> L.t("Sawir gacmeed", "Drawing")
        LayerKind.MODEL3D -> l.name
    }

    private fun drawSelection(canvas: Canvas, r: RectF, color: Int, handles: Boolean = true) {
        // dark edge under the bright frame: visible on white / bright clips too
        paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(5f); paint.color = 0x99000000.toInt()
        canvas.drawRoundRect(r, dp(4f), dp(4f), paint)
        paint.strokeWidth = dp(2.5f); paint.color = color
        canvas.drawRoundRect(r, dp(4f), dp(4f), paint)
        paint.style = Paint.Style.FILL
        if (handles) {
            paint.color = 0x99000000.toInt()
            canvas.drawRoundRect(RectF(r.left - handleW - dp(1.5f), r.top - dp(1.5f), r.left, r.bottom + dp(1.5f)), dp(4f), dp(4f), paint)
            canvas.drawRoundRect(RectF(r.right, r.top - dp(1.5f), r.right + handleW + dp(1.5f), r.bottom + dp(1.5f)), dp(4f), dp(4f), paint)
            paint.color = color
            canvas.drawRoundRect(RectF(r.left - handleW, r.top, r.left, r.bottom), dp(3f), dp(3f), paint)
            canvas.drawRoundRect(RectF(r.right, r.top, r.right + handleW, r.bottom), dp(3f), dp(3f), paint)
            paint.color = Color.BLACK
            paint.strokeWidth = dp(1.5f)
            canvas.drawLine(r.left - handleW / 2, r.centerY() - dp(6f), r.left - handleW / 2, r.centerY() + dp(6f), paint)
            canvas.drawLine(r.right + handleW / 2, r.centerY() - dp(6f), r.right + handleW / 2, r.centerY() + dp(6f), paint)
        }
    }

    // ------------------------------------------------------------------ touch

    private enum class Drag { NONE, SCRUB, VSCROLL, CLIP_L, CLIP_R, LAYER_L, LAYER_R, LAYER_MOVE, AUDIO_L, AUDIO_R, AUDIO_MOVE, KEYFRAME }

    private var keyDrag: so.ijarjar.app.model.Keyframe? = null
    private var keyOrigT = 0L

    /** Keyframe diamond of the selected layer under the finger. */
    private fun keyframeAt(p: Project, x: Float, y: Float): so.ijarjar.app.model.Keyframe? {
        val s = selection as? Sel.LayerSel ?: return null
        val idx = p.layers.indexOfFirst { it.id == s.id }
        if (idx < 0) return null
        val l = p.layers[idx]
        val top = layerTop(idx)
        if (y < top - dp(8f) || y > top + layerH + dp(10f)) return null
        return l.keyframes.minByOrNull { abs(xOf(l.startMs + it.t) - x) }?.takeIf { abs(xOf(l.startMs + it.t) - x) < dp(12f) }
    }

    private var drag = Drag.NONE
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var decided = false
    private var pendingDrag = Drag.NONE
    private var origStart = 0L
    private var origEnd = 0L
    private var origTrim = 0L
    private var origTimes: List<Pair<Long, Long>> = emptyList()

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            pxPerMs = (pxPerMs * d.scaleFactor).coerceIn(dp(4f) / 1000f, dp(600f) / 1000f)
            drag = Drag.NONE
            invalidate()
            return true
        }
    })

    // ------------------------------------------------------------------ smooth fling (keeps gliding after you let go)
    private val scroller = android.widget.OverScroller(context, android.view.animation.DecelerateInterpolator(1.6f))
    private var velocity: android.view.VelocityTracker? = null
    private var flingKind = Drag.NONE
    private var pinching = false
    private var downTimeMs = 0L

    private fun startFling(p: Project) {
        val vt = velocity ?: return
        vt.computeCurrentVelocity(1000)
        val vx = vt.xVelocity; val vy = vt.yVelocity
        val minV = dp(260f)
        when (drag) {
            Drag.SCRUB -> if (abs(vx) > minV) {
                flingKind = Drag.SCRUB
                scroller.fling((timeMs * pxPerMs).toInt(), 0, (-vx).toInt(), 0, 0, (max(0L, p.durationMs - 1) * pxPerMs).toInt(), 0, 0)
                postInvalidateOnAnimation()
            }
            Drag.VSCROLL -> if (abs(vy) > minV) {
                flingKind = Drag.VSCROLL
                scroller.fling(0, vScroll.toInt(), 0, (-vy).toInt(), 0, 0, 0, max(0f, contentHeight() - height).toInt())
                postInvalidateOnAnimation()
            }
            else -> {}
        }
    }

    override fun computeScroll() {
        if (flingKind == Drag.NONE || !scroller.computeScrollOffset()) { flingKind = Drag.NONE; return }
        val p = project ?: return
        if (flingKind == Drag.SCRUB) {
            timeMs = (scroller.currX / pxPerMs).toLong().coerceIn(0, max(0, p.durationMs - 1))
            listener?.onScrub(timeMs)
        } else vScroll = scroller.currY.toFloat()
        postInvalidateOnAnimation()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val p = project ?: return false
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            scroller.forceFinished(true); flingKind = Drag.NONE
            pinching = false; downTimeMs = timeMs
            velocity?.recycle(); velocity = android.view.VelocityTracker.obtain()
        }
        velocity?.addMovement(e)
        scaleDetector.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            // a pinch zooms around the playhead: undo any scrub the first finger did, so the line stays where you put it
            if (!pinching && drag == Drag.SCRUB && timeMs != downTimeMs) { timeMs = downTimeMs; listener?.onScrub(timeMs) }
            pinching = true
        }
        if (pinching || e.pointerCount > 1) {
            drag = Drag.NONE
            if (e.actionMasked == MotionEvent.ACTION_UP) { pinching = false; velocity?.recycle(); velocity = null }
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y; decided = false
                pendingDrag = handleAt(p, e.x, e.y)
                drag = Drag.NONE
            }
            MotionEvent.ACTION_MOVE -> {
                if (!decided) {
                    val dx = abs(e.x - downX); val dy = abs(e.y - downY)
                    if (dx < dp(6f) && dy < dp(6f)) return true
                    decided = true
                    drag = when {
                        pendingDrag != Drag.NONE -> pendingDrag
                        dy > dx * 1.3f -> Drag.VSCROLL
                        else -> Drag.SCRUB
                    }
                    beginDrag(p)
                }
                val dx = e.x - lastX; val dy = e.y - lastY
                lastX = e.x; lastY = e.y
                when (drag) {
                    Drag.SCRUB -> {
                        timeMs = (timeMs - (dx / pxPerMs).toLong()).coerceIn(0, max(0, p.durationMs - 1))
                        listener?.onScrub(timeMs)
                    }
                    Drag.VSCROLL -> {
                        vScroll = (vScroll - dy).coerceIn(0f, max(0f, contentHeight() - height))
                        invalidate()
                    }
                    else -> { applyEdit(p, e.x - downX); listener?.onTimelineEditing(); invalidate() }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!decided) onTap(p, e.x, e.y)
                else if (drag != Drag.SCRUB && drag != Drag.VSCROLL && drag != Drag.NONE) listener?.onTimelineEdited()
                else startFling(p)
                drag = Drag.NONE
                velocity?.recycle(); velocity = null
            }
            MotionEvent.ACTION_CANCEL -> drag = Drag.NONE
        }
        return true
    }

    private fun selectedLayer(p: Project): Layer? = (selection as? Sel.LayerSel)?.let { s -> p.layers.firstOrNull { it.id == s.id } }
    private fun selectedAudio(p: Project): AudioTrack? = (selection as? Sel.AudioSel)?.let { s -> p.audios.firstOrNull { it.id == s.id } }

    private fun edgeDrag(x: Float, x0: Float, x1: Float, left: Drag, right: Drag, body: Drag): Drag {
        if (x in (x0 - handleW - dp(8f))..(x0 + dp(6f))) return left
        if (x in (x1 - dp(6f))..(x1 + handleW + dp(8f))) return right
        if (body != Drag.NONE && x in x0..x1) return body
        return Drag.NONE
    }

    private fun handleAt(p: Project, x: Float, y: Float): Drag {
        when (val s = selection) {
            is Sel.ClipSel -> {
                val c = p.clips.getOrNull(s.index) ?: return Drag.NONE
                val top = clipTop()
                if (y < top || y > top + clipH) return Drag.NONE
                val st = p.clipStartMs(s.index)
                return edgeDrag(x, xOf(st), xOf(st + c.outDurationMs), Drag.CLIP_L, Drag.CLIP_R, Drag.NONE)
            }
            is Sel.LayerSel -> {
                val idx = p.layers.indexOfFirst { it.id == s.id }
                if (idx < 0) return Drag.NONE
                val l = p.layers[idx]
                keyframeAt(p, x, y)?.let { k -> keyDrag = k; keyOrigT = k.t; return Drag.KEYFRAME }
                val top = layerTop(idx)
                if (y < top - dp(6f) || y > top + layerH + dp(6f)) return Drag.NONE
                return edgeDrag(x, xOf(l.startMs), xOf(l.endMs), Drag.LAYER_L, Drag.LAYER_R, Drag.LAYER_MOVE)
            }
            is Sel.AudioSel -> {
                val idx = p.audios.indexOfFirst { it.id == s.id }
                if (idx < 0) return Drag.NONE
                val a = p.audios[idx]
                val top = audioTop(idx)
                if (y < top - dp(6f) || y > top + audioH + dp(6f)) return Drag.NONE
                return edgeDrag(x, xOf(a.startMs), xOf(a.endMs), Drag.AUDIO_L, Drag.AUDIO_R, Drag.AUDIO_MOVE)
            }
            else -> {}
        }
        return Drag.NONE
    }

    private fun beginDrag(p: Project) {
        when (selection) {
            is Sel.ClipSel -> p.clips.getOrNull((selection as Sel.ClipSel).index)?.let { origStart = it.trimStartMs; origEnd = it.trimEndMs }
            is Sel.LayerSel -> selectedLayer(p)?.let { l ->
                origStart = l.startMs; origEnd = l.endMs
                origTimes = p.linkedWith(l).map { Pair(it.startMs, it.endMs) }
            }
            is Sel.AudioSel -> selectedAudio(p)?.let { a -> origStart = a.startMs; origEnd = a.endMs; origTrim = a.trimStartMs }
            else -> {}
        }
    }

    private fun applyEdit(p: Project, totalDx: Float) {
        val dMs = (totalDx / pxPerMs).toLong()
        when (drag) {
            Drag.CLIP_L, Drag.CLIP_R -> {
                val c = p.clips.getOrNull((selection as Sel.ClipSel).index) ?: return
                val srcD = if (c.kind == MediaKind.VIDEO) (dMs * c.speed).toLong() else dMs
                if (c.kind == MediaKind.IMAGE) {
                    val len = if (drag == Drag.CLIP_L) origEnd - origStart - srcD else origEnd - origStart + srcD
                    c.trimStartMs = 0
                    c.trimEndMs = len.coerceIn(200, 600_000)
                    c.sourceDurationMs = max(c.sourceDurationMs, c.trimEndMs)
                } else if (drag == Drag.CLIP_L) {
                    c.trimStartMs = (origStart + srcD).coerceIn(0, c.trimEndMs - 100)
                } else {
                    c.trimEndMs = (origEnd + srcD).coerceIn(c.trimStartMs + 100, c.sourceDurationMs)
                }
            }
            Drag.LAYER_L -> selectedLayer(p)?.let { l ->
                val ns = (origStart + dMs).coerceIn(0, l.endMs - 100)
                // keep keyframes in place on the timeline
                val shift = ns - l.startMs
                for (k in l.keyframes) k.t -= shift
                if (l.kind == LayerKind.VIDEO) l.trimStartMs = (l.trimStartMs + shift).coerceAtLeast(0)
                l.startMs = ns
            }
            Drag.LAYER_R -> selectedLayer(p)?.let { l ->
                var end = origEnd + dMs
                if (l.kind == LayerKind.VIDEO && l.sourceDurationMs > 0) end = min(end, l.startMs + l.sourceDurationMs - l.trimStartMs)
                l.endMs = max(l.startMs + 100, end)
            }
            Drag.LAYER_MOVE -> selectedLayer(p)?.let { l ->
                val group = p.linkedWith(l)
                val minStart = origTimes.minOf { it.first }
                val d = max(dMs, -minStart)
                for ((k, g) in group.withIndex()) {
                    val o = origTimes.getOrNull(k) ?: continue
                    g.startMs = o.first + d; g.endMs = o.second + d
                }
            }
            Drag.AUDIO_L -> selectedAudio(p)?.let { a ->
                val d = max(dMs, max(-origStart, -origTrim))
                val ns = min(origStart + d, origEnd - 200)
                a.trimStartMs = origTrim + (ns - origStart)
                a.startMs = ns
                a.durationMs = origEnd - ns
            }
            Drag.AUDIO_R -> selectedAudio(p)?.let { a ->
                var dur = origEnd + dMs - a.startMs
                if (a.sourceDurationMs > 0) dur = min(dur, a.sourceDurationMs - a.trimStartMs)
                a.durationMs = max(200, dur)
            }
            Drag.AUDIO_MOVE -> selectedAudio(p)?.let { a -> a.startMs = max(0, origStart + dMs) }
            Drag.KEYFRAME -> selectedLayer(p)?.let { l -> keyDrag?.let { k -> k.t = (keyOrigT + dMs).coerceIn(0, l.durationMs) } }
            else -> {}
        }
    }

    private fun onTap(p: Project, x: Float, y: Float) {
        if (gutterTap(p, x, y)) return
        val t = tOf(x)
        // tapping a keyframe jumps to it
        keyframeAt(p, x, y)?.let { k ->
            val l = selectedLayer(p) ?: return@let
            listener?.onKeyframeTap(l.startMs + k.t); return
        }
        val ct = clipTop()
        if (y in ct..(ct + clipH)) {
            // transition buttons
            var acc = 0L
            for (i in p.clips.indices) {
                if (i > 0 && abs(x - xOf(acc)) < dp(14f)) { listener?.onTransitionTap(i); return }
                acc += p.clips[i].outDurationMs
            }
            acc = 0L
            for ((i, c) in p.clips.withIndex()) {
                if (t >= acc && t < acc + c.outDurationMs) { select(Sel.ClipSel(i)); return }
                acc += c.outDurationMs
            }
        }
        for ((i, a) in p.audios.withIndex()) {
            val top = audioTop(i)
            if (y in top..(top + audioH) && t >= a.startMs && t <= a.endMs) { select(Sel.AudioSel(a.id)); return }
        }
        for ((i, l) in p.layers.withIndex()) {
            val top = layerTop(i)
            if (y in top..(top + layerH) && t >= l.startMs && t <= l.endMs) { select(Sel.LayerSel(l.id)); return }
        }
        select(null)
    }

    private fun select(s: Sel?) {
        if (s != null) performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        selection = s
        listener?.onSelect(s)
    }

    fun revealLayer(index: Int) {
        val top = layerTop(index) + vScroll
        if (top + layerH > vScroll + height) vScroll = (top + layerH - height + gap).coerceAtLeast(0f)
        invalidate()
    }

    companion object {
        fun fmt(ms: Long): String {
            val s = ms / 1000
            return "%02d:%02d".format(s / 60, s % 60)
        }

        fun fmtPrecise(ms: Long): String = "%02d:%02d.%d".format(ms / 60000, (ms / 1000) % 60, (ms % 1000) / 100)

        fun trim(f: Float): String = if (f == f.toInt().toFloat()) f.toInt().toString() else "%.2f".format(f).trimEnd('0').trimEnd('.')
    }
}

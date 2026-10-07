package so.ijarjar.app.editor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import so.ijarjar.app.media.MediaUtils
import so.ijarjar.app.model.Clip
import so.ijarjar.app.model.Layer
import so.ijarjar.app.model.LayerKind
import so.ijarjar.app.model.MediaKind
import so.ijarjar.app.model.Project
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * CapCut style timeline: the playhead stays in the middle and the tracks scroll under it.
 * Rows: ruler, main track (clips), music, and one row per layer.
 */
class TimelineView(context: Context) : View(context) {

    sealed class Sel {
        data class ClipSel(val index: Int) : Sel()
        data class LayerSel(val id: String) : Sel()
        object MusicSel : Sel()
    }

    interface Listener {
        fun onScrub(timeMs: Long)
        fun onSelect(sel: Sel?)
        fun onTimelineEditing()
        fun onTimelineEdited()
    }

    var project: Project? = null
        set(v) { field = v; invalidate() }
    var timeMs: Long = 0
        set(v) { field = v; invalidate() }
    var selection: Sel? = null
        set(v) { field = v; invalidate() }
    var listener: Listener? = null

    private var pxPerMs = dp(60f) / 1000f // 60dp per second
    private var vScroll = 0f

    private val rulerH = dp(22f)
    private val clipH = dp(56f)
    private val musicH = dp(26f)
    private val layerH = dp(26f)
    private val gap = dp(6f)
    private val handleW = dp(14f)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9A9AA5.toInt(); textSize = dp(10f) }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = dp(11f) }

    private val thumbs = HashMap<String, Bitmap?>()
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private val linkColors = intArrayOf(0xFF19D3C5.toInt(), 0xFFFFB020.toInt(), 0xFF8B7CFF.toInt(), 0xFF4CD964.toInt(), 0xFFFF6B6B.toInt())

    private fun dp(v: Float) = v * resources.displayMetrics.density

    private fun xOf(t: Long) = width / 2f + (t - timeMs) * pxPerMs
    private fun tOf(x: Float) = timeMs + ((x - width / 2f) / pxPerMs).toLong()

    private fun clipTop() = rulerH + gap - vScroll
    private fun musicTop() = clipTop() + clipH + gap
    private fun layersTop() = musicTop() + musicH + gap
    private fun layerTop(i: Int) = layersTop() + i * (layerH + gap * 0.6f)

    private fun contentHeight(): Float {
        val n = project?.layers?.size ?: 0
        return rulerH + gap + clipH + gap + musicH + gap + n * (layerH + gap * 0.6f) + gap
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        val p = project ?: return
        canvas.drawColor(0xFF121216.toInt())
        drawRuler(canvas, p)
        drawClips(canvas, p)
        drawMusic(canvas, p)
        drawLayers(canvas, p)
        // playhead
        paint.color = Color.WHITE
        paint.strokeWidth = dp(2f)
        canvas.drawLine(width / 2f, 0f, width / 2f, height.toFloat(), paint)
        canvas.drawCircle(width / 2f, dp(4f), dp(4f), paint)
    }

    private fun drawRuler(canvas: Canvas, p: Project) {
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
            val r = RectF(x0 + dp(1f), top, x1 - dp(1f), top + clipH)
            canvas.save()
            canvas.clipRect(r)
            paint.color = 0xFF2A2A33.toInt()
            canvas.drawRect(r, paint)
            drawThumbs(canvas, c, r)
            canvas.restore()
            // speed / volume badges
            val badges = buildString {
                if (c.kind == MediaKind.VIDEO && c.speed != 1f) append("${trim(c.speed)}x ")
                if (c.volume == 0f) append("🔇 ")
                if (!c.adjust.isIdentity()) append("🎨")
            }
            if (badges.isNotEmpty()) canvas.drawText(badges, r.left + dp(4f), r.bottom - dp(5f), labelPaint)
            val selected = (selection as? Sel.ClipSel)?.index == i
            if (selected) drawSelection(canvas, r, 0xFFFFFFFF.toInt())
        }
        if (p.clips.isEmpty()) {
            canvas.drawText("Riix ＋ si aad muuqaal ugu darto", width / 2f + dp(10f), top + clipH / 2f, labelPaint)
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

    private fun drawMusic(canvas: Canvas, p: Project) {
        val top = musicTop()
        val m = p.music
        if (m == null) {
            canvas.drawText("♪  Muusik ma jiro", xOf(0) + dp(6f), top + musicH * 0.65f, textPaint)
            return
        }
        val len = min(p.durationMs, (m.sourceDurationMs - m.trimStartMs).coerceAtLeast(0))
        val r = RectF(xOf(0), top, xOf(len), top + musicH)
        paint.color = 0xFF1F5E3A.toInt()
        canvas.drawRoundRect(r, dp(5f), dp(5f), paint)
        canvas.save(); canvas.clipRect(r)
        canvas.drawText("♪ " + m.name, max(r.left, 0f) + dp(6f), top + musicH * 0.68f, labelPaint)
        canvas.restore()
        if (selection is Sel.MusicSel) drawSelection(canvas, r, Color.WHITE, handles = false)
    }

    private fun layerColor(l: Layer) = when (l.kind) {
        LayerKind.TEXT -> 0xFF7A4BD6.toInt()
        LayerKind.STICKER -> 0xFFC9822B.toInt()
        LayerKind.IMAGE -> 0xFF2B7BC9.toInt()
        LayerKind.VIDEO -> 0xFFB8336A.toInt()
    }

    private fun drawLayers(canvas: Canvas, p: Project) {
        val groups = p.layers.mapNotNull { it.linkGroup }.distinct()
        for ((i, l) in p.layers.withIndex()) {
            val top = layerTop(i)
            if (top > height || top + layerH < rulerH) continue
            val r = RectF(xOf(l.startMs), top, xOf(l.endMs), top + layerH)
            paint.color = layerColor(l)
            canvas.drawRoundRect(r, dp(5f), dp(5f), paint)
            val g = l.linkGroup
            var labelX = max(r.left, 0f) + dp(6f)
            if (g != null) {
                paint.color = linkColors[groups.indexOf(g) % linkColors.size]
                canvas.drawRect(r.left, r.top, r.left + dp(4f), r.bottom, paint)
                canvas.save(); canvas.clipRect(r)
                canvas.drawText("🔗", labelX, top + layerH * 0.7f, labelPaint)
                canvas.restore()
                labelX += dp(18f)
            }
            canvas.save(); canvas.clipRect(r)
            canvas.drawText(layerLabel(l), labelX, top + layerH * 0.7f, labelPaint)
            canvas.restore()
            if ((selection as? Sel.LayerSel)?.id == l.id) drawSelection(canvas, r, Color.WHITE)
            else if (g != null && (selection as? Sel.LayerSel)?.let { s -> p.layers.firstOrNull { it.id == s.id }?.linkGroup } == g) {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1.5f); paint.color = 0xFF19D3C5.toInt()
                canvas.drawRoundRect(r, dp(5f), dp(5f), paint)
                paint.style = Paint.Style.FILL
            }
        }
    }

    fun layerLabel(l: Layer): String = when (l.kind) {
        LayerKind.TEXT -> "T  " + l.text.replace("\n", " ")
        LayerKind.STICKER -> l.text
        LayerKind.IMAGE -> "🖼 " + l.name
        LayerKind.VIDEO -> "🎬 " + l.name
    }

    private fun drawSelection(canvas: Canvas, r: RectF, color: Int, handles: Boolean = true) {
        paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(2f); paint.color = color
        canvas.drawRoundRect(r, dp(4f), dp(4f), paint)
        paint.style = Paint.Style.FILL
        if (handles) {
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

    private enum class Drag { NONE, SCRUB, VSCROLL, CLIP_L, CLIP_R, LAYER_L, LAYER_R, LAYER_MOVE }

    private var drag = Drag.NONE
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var decided = false
    private var downTime = 0L
    private var pendingDrag = Drag.NONE
    private var origStart = 0L
    private var origEnd = 0L
    private var origTimes: List<Pair<Long, Long>> = emptyList()

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            pxPerMs = (pxPerMs * d.scaleFactor).coerceIn(dp(4f) / 1000f, dp(600f) / 1000f)
            drag = Drag.NONE
            invalidate()
            return true
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val p = project ?: return false
        scaleDetector.onTouchEvent(e)
        if (e.pointerCount > 1) { drag = Drag.NONE; return true }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y; decided = false; downTime = e.eventTime
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
                drag = Drag.NONE
            }
            MotionEvent.ACTION_CANCEL -> drag = Drag.NONE
        }
        return true
    }

    private fun selectedLayer(p: Project): Layer? = (selection as? Sel.LayerSel)?.let { s -> p.layers.firstOrNull { it.id == s.id } }

    /** Which handle (if any) was touched on the selected item. */
    private fun handleAt(p: Project, x: Float, y: Float): Drag {
        when (val s = selection) {
            is Sel.ClipSel -> {
                val c = p.clips.getOrNull(s.index) ?: return Drag.NONE
                val top = clipTop()
                if (y < top || y > top + clipH) return Drag.NONE
                val x0 = xOf(p.clipStartMs(s.index)); val x1 = xOf(p.clipStartMs(s.index) + c.outDurationMs)
                if (x in (x0 - handleW - dp(8f))..(x0 + dp(6f))) return Drag.CLIP_L
                if (x in (x1 - dp(6f))..(x1 + handleW + dp(8f))) return Drag.CLIP_R
            }
            is Sel.LayerSel -> {
                val idx = p.layers.indexOfFirst { it.id == s.id }
                if (idx < 0) return Drag.NONE
                val l = p.layers[idx]
                val top = layerTop(idx)
                if (y < top - dp(6f) || y > top + layerH + dp(6f)) return Drag.NONE
                val x0 = xOf(l.startMs); val x1 = xOf(l.endMs)
                if (x in (x0 - handleW - dp(8f))..(x0 + dp(6f))) return Drag.LAYER_L
                if (x in (x1 - dp(6f))..(x1 + handleW + dp(8f))) return Drag.LAYER_R
                if (x in x0..x1) return Drag.LAYER_MOVE
            }
            else -> {}
        }
        return Drag.NONE
    }

    private fun beginDrag(p: Project) {
        when (val s = selection) {
            is Sel.ClipSel -> p.clips.getOrNull(s.index)?.let { origStart = it.trimStartMs; origEnd = it.trimEndMs }
            is Sel.LayerSel -> selectedLayer(p)?.let { l ->
                origStart = l.startMs; origEnd = l.endMs
                origTimes = p.linkedWith(l).map { Pair(it.startMs, it.endMs) }
            }
            else -> {}
        }
    }

    private fun applyEdit(p: Project, totalDx: Float) {
        val dMs = (totalDx / pxPerMs).toLong()
        when (drag) {
            Drag.CLIP_L, Drag.CLIP_R -> {
                val c = p.clips.getOrNull((selection as Sel.ClipSel).index) ?: return
                val srcD = if (c.kind == MediaKind.VIDEO) (dMs * c.speed).toLong() else dMs
                if (drag == Drag.CLIP_L) {
                    val minStart = if (c.kind == MediaKind.IMAGE) origStart else 0L
                    c.trimStartMs = (origStart + srcD).coerceIn(minStart, c.trimEndMs - 100)
                    if (c.kind == MediaKind.IMAGE) {
                        // images: left handle shortens/extends the still image
                        c.trimStartMs = 0
                        c.trimEndMs = (origEnd - origStart - srcD).coerceIn(200, 600_000)
                    }
                } else {
                    val maxEnd = if (c.kind == MediaKind.IMAGE) 600_000L else c.sourceDurationMs
                    c.trimEndMs = (origEnd + srcD).coerceIn(c.trimStartMs + 100, maxEnd)
                    if (c.kind == MediaKind.IMAGE) c.sourceDurationMs = max(c.sourceDurationMs, c.trimEndMs)
                }
            }
            Drag.LAYER_L -> selectedLayer(p)?.let { l -> l.startMs = (origStart + dMs).coerceIn(0, l.endMs - 100) }
            Drag.LAYER_R -> selectedLayer(p)?.let { l ->
                var end = origEnd + dMs
                if (l.kind == LayerKind.VIDEO && l.sourceDurationMs > 0) end = min(end, l.startMs + l.sourceDurationMs - l.trimStartMs)
                l.endMs = max(l.startMs + 100, end)
            }
            Drag.LAYER_MOVE -> selectedLayer(p)?.let { l ->
                // linked layers move in time together
                val group = p.linkedWith(l)
                val minStart = origTimes.minOf { it.first }
                val d = max(dMs, -minStart)
                for ((k, g) in group.withIndex()) {
                    val o = origTimes.getOrNull(k) ?: continue
                    g.startMs = o.first + d; g.endMs = o.second + d
                }
            }
            else -> {}
        }
    }

    private fun onTap(p: Project, x: Float, y: Float) {
        // seek is not changed by taps (CapCut behaviour); only selection
        val t = tOf(x)
        val ct = clipTop()
        if (y in ct..(ct + clipH)) {
            var acc = 0L
            for ((i, c) in p.clips.withIndex()) {
                if (t >= acc && t < acc + c.outDurationMs) { select(Sel.ClipSel(i)); return }
                acc += c.outDurationMs
            }
        }
        val mt = musicTop()
        if (y in mt..(mt + musicH) && p.music != null) { select(Sel.MusicSel); return }
        for ((i, l) in p.layers.withIndex()) {
            val top = layerTop(i)
            if (y in top..(top + layerH) && t >= l.startMs && t <= l.endMs) { select(Sel.LayerSel(l.id)); return }
        }
        select(null)
    }

    private fun select(s: Sel?) {
        selection = s
        listener?.onSelect(s)
    }

    /** Make sure a layer row is visible (after adding a layer). */
    fun revealLayer(index: Int) {
        val top = layerTop(index) + vScroll
        if (top + layerH > vScroll + height) vScroll = (top + layerH - height + gap).coerceAtLeast(0f)
        invalidate()
    }

    fun clearThumbs() { thumbs.clear() }

    companion object {
        fun fmt(ms: Long): String {
            val s = ms / 1000
            return "%02d:%02d".format(s / 60, s % 60)
        }

        fun trim(f: Float): String = if (f == f.toInt().toFloat()) f.toInt().toString() else "%.2f".format(f).trimEnd('0').trimEnd('.')
    }
}

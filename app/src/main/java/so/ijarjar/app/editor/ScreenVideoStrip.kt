package so.ijarjar.app.editor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import so.ijarjar.app.media.MediaUtils
import so.ijarjar.app.model.ScreenSeg
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * The video on a 3D screen as a strip of pictures (like CapCut's trim bar): kept pieces are bright,
 * cut-out parts dark. Drag the white line to a spot, drag a piece's edge to trim it, tap a piece to pick it.
 */
@SuppressLint("ViewConstructor")
class ScreenVideoStrip(context: Context, private val uri: String, val durationMs: Long, val segs: MutableList<ScreenSeg>) : View(context) {

    var cursorMs = 0L
        set(v) { field = v.coerceIn(0, maxOf(0, durationMs - 1)); invalidate() }
    var selected = 0
        set(v) { field = v; invalidate() }
    var onCursor: ((Long) -> Unit)? = null
    var onChanged: (() -> Unit)? = null

    private val thumbs = HashMap<Int, Bitmap?>()
    private val pool = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = dp(10f) }
    private fun dp(v: Float) = v * resources.displayMetrics.density

    private val pad get() = dp(14f)
    private val barTop get() = dp(18f)
    private val barH get() = dp(52f)
    private fun xOf(ms: Long) = pad + (width - 2 * pad) * (ms.toFloat() / durationMs.coerceAtLeast(1))
    private fun msOf(x: Float) = (((x - pad) / (width - 2 * pad)).coerceIn(0f, 1f) * durationMs).toLong()

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), dp(84f).toInt())

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val top = barTop; val bot = barTop + barH
        // pictures
        val tw = barH * 0.6f
        val n = ((w - 2 * pad) / tw).toInt().coerceAtLeast(1)
        for (i in 0 until n) {
            val dst = RectF(pad + i * (w - 2 * pad) / n, top, pad + (i + 1) * (w - 2 * pad) / n, bot)
            if (thumbs.containsKey(i)) thumbs[i]?.let { b ->
                val s = maxOf(dst.width() / b.width, dst.height() / b.height)
                val sw = dst.width() / s; val sh = dst.height() / s
                val src = android.graphics.Rect(((b.width - sw) / 2).toInt(), ((b.height - sh) / 2).toInt(), ((b.width + sw) / 2).toInt(), ((b.height + sh) / 2).toInt())
                c.drawBitmap(b, src, dst, paint)
            } else {
                thumbs[i] = null
                paint.color = 0xFF2A2A33.toInt(); c.drawRect(dst, paint)
                val t = (durationMs * (i + 0.5f) / n).toLong()
                pool.execute { val b = MediaUtils.thumbnail(context, Uri.parse(uri), true, t, 160); main.post { thumbs[i] = b; invalidate() } }
            }
        }
        // cut-out parts dark
        paint.color = 0xC0000000.toInt()
        var last = 0L
        for (sg in segs.sortedBy { it.start }) {
            if (sg.start > last) c.drawRect(xOf(last), top, xOf(sg.start), bot, paint)
            last = maxOf(last, sg.end)
        }
        if (last < durationMs) c.drawRect(xOf(last), top, xOf(durationMs), bot, paint)
        // pieces: outline, selected one with handles
        for ((i, sg) in segs.withIndex()) {
            val r = RectF(xOf(sg.start), top, xOf(sg.end), bot)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(if (i == selected) 3f else 1.5f)
            paint.color = if (i == selected) Ui.ACCENT else 0xAAFFFFFF.toInt()
            c.drawRoundRect(r, dp(4f), dp(4f), paint)
            paint.style = Paint.Style.FILL
            if (i == selected) {
                paint.color = Ui.ACCENT
                c.drawRoundRect(RectF(r.left - dp(7f), top, r.left + dp(1f), bot), dp(3f), dp(3f), paint)
                c.drawRoundRect(RectF(r.right - dp(1f), top, r.right + dp(7f), bot), dp(3f), dp(3f), paint)
            }
            text.color = Color.WHITE
            c.drawText("${i + 1}", r.left + dp(5f), bot - dp(5f), text)
        }
        // cursor
        val cx = xOf(cursorMs)
        paint.color = Color.WHITE
        c.drawRect(cx - dp(1.2f), top - dp(6f), cx + dp(1.2f), bot + dp(6f), paint)
        c.drawCircle(cx, top - dp(8f), dp(4f), paint)
        text.color = 0xFFB0B0BA.toInt()
        c.drawText(TimelineView.fmt(cursorMs), (cx - dp(16f)).coerceIn(0f, w - dp(40f)), dp(12f), text)
        c.drawText(TimelineView.fmt(durationMs), w - pad - dp(30f), bot + dp(18f), text)
    }

    private var dragEdge = 0          // -1 left edge, 1 right edge, 0 cursor
    private var dragSeg = -1

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragEdge = 0; dragSeg = -1
                val sel = segs.getOrNull(selected)
                if (sel != null) {
                    if (abs(e.x - xOf(sel.start)) < dp(18f)) { dragEdge = -1; dragSeg = selected }
                    else if (abs(e.x - xOf(sel.end)) < dp(18f)) { dragEdge = 1; dragSeg = selected }
                }
                if (dragEdge == 0) {
                    val t = msOf(e.x)
                    segs.indexOfFirst { t >= it.start && t < it.end }.takeIf { it >= 0 }?.let { if (it != selected) { selected = it; performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) } }
                    cursorMs = t; onCursor?.invoke(cursorMs)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val t = msOf(e.x)
                if (dragEdge != 0) {
                    val sg = segs[dragSeg]
                    val sorted = segs.sortedBy { it.start }
                    val k = sorted.indexOf(sg)
                    val lo = sorted.getOrNull(k - 1)?.end ?: 0L
                    val hi = sorted.getOrNull(k + 1)?.start ?: durationMs
                    if (dragEdge < 0) sg.start = t.coerceIn(lo, sg.end - 100) else sg.end = t.coerceIn(sg.start + 100, hi)
                    cursorMs = if (dragEdge < 0) sg.start else sg.end - 1
                    onCursor?.invoke(cursorMs); onChanged?.invoke()
                } else { cursorMs = t; onCursor?.invoke(cursorMs) }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { if (dragEdge != 0) onChanged?.invoke(); dragEdge = 0 }
        }
        return true
    }

    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); pool.shutdownNow() }
}

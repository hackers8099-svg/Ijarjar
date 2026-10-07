package so.ijarjar.app.render

import android.graphics.Color
import so.ijarjar.app.model.Project
import so.ijarjar.app.model.TransitionKind
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** How the main clip is placed on the canvas at one moment (user transform + transition). */
class ClipMotion(
    var scale: Float = 1f,
    var rotation: Float = 0f,   // degrees clockwise on screen
    var tx: Float = 0f,         // fraction of canvas width
    var ty: Float = 0f,         // fraction of canvas height
    var mirror: Boolean = false,
    var sx: Float = 1f,         // extra horizontal squeeze
    var fadeColor: Int = Color.BLACK,
    var fadeAlpha: Float = 0f
)

object Motion {

    private fun half(p: Project, i: Int): Long {
        val c = p.clips[i]
        if (i <= 0 || c.transition == TransitionKind.NONE) return 0
        val prev = p.clips[i - 1]
        return min(c.transitionMs / 2, min(prev.outDurationMs / 2, c.outDurationMs / 2)).coerceAtLeast(1)
    }

    /**
     * Motion of clip [i] at [localMs] (milliseconds from the start of the clip on the output timeline).
     * Shared by the preview and the exporter.
     */
    fun clipMotion(p: Project, i: Int, localMs: Long): ClipMotion {
        val c = p.clips[i]
        val m = ClipMotion(c.tScale, c.tRot, c.tX, c.tY, c.mirror)
        // incoming transition (this clip's)
        val hIn = half(p, i)
        if (hIn > 0 && localMs < hIn) {
            apply(m, c.transition, 1f - localMs.toFloat() / hIn, incoming = true, localMs)
        }
        // outgoing transition (next clip's)
        if (i + 1 < p.clips.size) {
            val hOut = half(p, i + 1)
            val out = c.outDurationMs
            if (hOut > 0 && localMs > out - hOut) {
                apply(m, p.clips[i + 1].transition, 1f - (out - localMs).toFloat() / hOut, incoming = false, localMs)
            }
        }
        EffectRenderer.applyMotion(p, p.clipStartMs(i) + localMs, m)
        return m
    }

    private fun apply(m: ClipMotion, kind: TransitionKind, fRaw: Float, incoming: Boolean, u: Long) {
        val f = fRaw.coerceIn(0f, 1f)
        val e = f * f * (3 - 2 * f) // smoothstep
        when (kind) {
            TransitionKind.NONE -> {}
            TransitionKind.FADE_BLACK -> { m.fadeColor = Color.BLACK; m.fadeAlpha = maxOf(m.fadeAlpha, e) }
            TransitionKind.FLASH -> { m.fadeColor = Color.WHITE; m.fadeAlpha = maxOf(m.fadeAlpha, e) }
            TransitionKind.ZOOM_IN -> {
                m.scale *= if (incoming) 1f + 0.6f * e else 1f + 1.0f * e
                m.fadeColor = Color.WHITE; m.fadeAlpha = maxOf(m.fadeAlpha, e * 0.35f)
            }
            TransitionKind.ZOOM_OUT -> {
                m.scale *= if (incoming) 1f + 0.4f * e else 1f - 0.5f * e
                m.fadeColor = Color.BLACK; m.fadeAlpha = maxOf(m.fadeAlpha, e * 0.6f)
            }
            TransitionKind.SPIN -> {
                m.rotation += if (incoming) -180f * e else 180f * e
                m.scale *= 1f - 0.4f * e
                m.fadeColor = Color.BLACK; m.fadeAlpha = maxOf(m.fadeAlpha, e * 0.5f)
            }
            TransitionKind.SLIDE_LEFT -> m.tx += if (incoming) e * 1.0f else -e * 1.0f
            TransitionKind.SLIDE_RIGHT -> m.tx += if (incoming) -e * 1.0f else e * 1.0f
            TransitionKind.SLIDE_UP -> m.ty += if (incoming) e * 1.0f else -e * 1.0f
            TransitionKind.SLIDE_DOWN -> m.ty += if (incoming) -e * 1.0f else e * 1.0f
            TransitionKind.WHIP -> {
                m.tx += if (incoming) e * 0.6f else -e * 0.6f
                m.sx *= 1f + 0.5f * e
                m.fadeColor = Color.WHITE; m.fadeAlpha = maxOf(m.fadeAlpha, e * 0.45f)
            }
            TransitionKind.SQUEEZE -> {
                m.sx *= (1f - e).coerceAtLeast(0.02f)
                m.fadeColor = Color.BLACK; m.fadeAlpha = maxOf(m.fadeAlpha, e * 0.3f)
            }
            TransitionKind.GLITCH -> {
                val k = (u / 60).toInt()
                val r = ((k * 1103515245 + 12345) ushr 8 and 0xFFFF) / 65535f
                m.tx += (r - 0.5f) * 0.12f * e
                m.sx *= 1f + (r - 0.5f) * 0.3f * e
                m.fadeColor = if (r > 0.5f) Color.argb(255, 255, 0, 255) else Color.argb(255, 0, 255, 255)
                m.fadeAlpha = maxOf(m.fadeAlpha, e * 0.35f * r)
            }
            TransitionKind.SHAKE -> {
                m.tx += sin(u * 0.09f) * 0.05f * e
                m.ty += cos(u * 0.11f) * 0.04f * e
                m.scale *= 1f + 0.1f * e
                m.fadeColor = Color.WHITE; m.fadeAlpha = maxOf(m.fadeAlpha, e * 0.25f)
            }
        }
    }
}

package so.ijarjar.app.render

import so.ijarjar.app.model.Expression
import so.ijarjar.app.model.Layer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * After Effects style expressions, applied after keyframes:
 * inertial bounce, wiggle, squash & stretch, orbit, pulse, rotation over time, focus pull.
 * (loopOut cycle / pingpong are handled when keyframes are read.)
 */
object Expressions {

    private fun noise(x: Float, seed: Float): Float =
        (sin(x * 1.0f + seed) * 0.5f + sin(x * 2.3f + seed * 1.7f) * 0.3f + sin(x * 4.1f + seed * 2.9f) * 0.2f)

    fun apply(p: Pose, l: Layer, t: Long) {
        val amp = l.exprAmp
        val freq = l.exprFreq.coerceAtLeast(0.05f)
        val s = (t - l.startMs) / 1000f
        val tau = 2f * PI.toFloat()
        when (l.expr) {
            Expression.NONE, Expression.LOOP_CYCLE, Expression.LOOP_PINGPONG -> {}
            Expression.BOUNCE -> bounce(p, l, t, amp, freq)
            Expression.WIGGLE -> {
                val x = s * freq
                p.cx += noise(x, 1.3f) * 0.03f * amp
                p.cy += noise(x, 5.1f) * 0.03f * amp
                p.rotation += noise(x, 9.7f) * 6f * amp
            }
            Expression.SQUASH -> {
                val w = sin(s * tau * freq) * 0.12f * amp
                p.sx *= 1f + w; p.sy *= 1f - w
            }
            Expression.ROTATE -> p.rotation += s * 90f * freq * amp
            Expression.PULSE -> p.scale *= 1f + 0.1f * amp * sin(s * tau * freq)
            Expression.ORBIT -> {
                p.cx += cos(s * tau * freq * 0.25f) * 0.1f * amp
                p.cy += sin(s * tau * freq * 0.25f) * 0.1f * amp
            }
            Expression.FOCUS -> p.blur = maxOf(p.blur, (0.5f + 0.5f * sin(s * tau * freq * 0.25f)) * amp.coerceIn(0f, 1f))
        }
    }

    /**
     * Classic inertial bounce: after each keyframe the value overshoots and settles, based on the
     * speed it arrived with. With no keyframes the layer bounces like a ball.
     */
    private fun bounce(p: Pose, l: Layer, t: Long, amp: Float, freq: Float) {
        val decay = l.exprDecay.coerceAtLeast(0.1f)
        val tau = 2f * PI.toFloat()
        val ks = l.keyframes.sortedBy { it.t }
        val rel = t - l.startMs
        if (ks.size < 2) {
            val s = rel / 1000f
            val h = abs(sin(s * PI.toFloat() * freq)) * exp(-decay * 0.15f * s)
            p.cy -= h * 0.12f * amp
            val squash = (1f - h).coerceIn(0f, 1f)
            if (squash > 0.9f) { p.sx *= 1f + (squash - 0.9f) * 1.5f * amp; p.sy *= 1f - (squash - 0.9f) * 1.5f * amp }
            return
        }
        val k = ks.lastOrNull { it.t <= rel && ks.indexOf(it) > 0 } ?: return
        val dt = (rel - k.t) / 1000f
        if (dt <= 0f) return
        // stop when the next keyframe begins moving again
        val next = ks.firstOrNull { it.t > k.t }
        if (next != null && rel >= next.t) return
        // average speed of the move into this keyframe (works with eased keyframes too, like AE's inertia)
        val prevK = ks[ks.indexOf(k) - 1]
        val segS = ((k.t - prevK.t) / 1000f).coerceAtLeast(0.001f)
        val vScale = (k.scale - prevK.scale) / segS
        val vx = (k.cx - prevK.cx) / segS; val vy = (k.cy - prevK.cy) / segS; val vr = (k.rotation - prevK.rotation) / segS
        val osc = amp * 0.08f * sin(freq * dt * tau) / exp(decay * dt)
        p.cx += vx * osc; p.cy += vy * osc; p.scale += vScale * osc; p.rotation += vr * osc
    }
}

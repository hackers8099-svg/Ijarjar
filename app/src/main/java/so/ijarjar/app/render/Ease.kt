package so.ijarjar.app.render

import so.ijarjar.app.model.Easing
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

/** Keyframe interpolation curves (After Effects style). */
object Ease {

    fun apply(e: Easing, x: Float, bx1: Float = 0.42f, by1: Float = 0f, bx2: Float = 0.58f, by2: Float = 1f): Float {
        val t = x.coerceIn(0f, 1f)
        return when (e) {
            Easing.LINEAR -> t
            Easing.EASE_IN -> t * t * t
            Easing.EASE_OUT -> 1f - (1f - t).pow(3)
            Easing.EASE_IN_OUT -> if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).pow(3) / 2f
            Easing.BACK -> { val c1 = 1.70158f; val c3 = c1 + 1f; 1f + c3 * (t - 1f).pow(3) + c1 * (t - 1f).pow(2) }
            Easing.BOUNCE -> bounceOut(t)
            Easing.ELASTIC -> if (t == 0f || t == 1f) t else
                (2.0.pow(-10.0 * t) * sin((t * 10 - 0.75) * (2 * PI / 3)) + 1).toFloat()
            Easing.HOLD -> if (t < 1f) 0f else 1f
            Easing.CUSTOM -> bezier(t, bx1, by1, bx2, by2)
        }
    }

    private fun bounceOut(x: Float): Float {
        val n1 = 7.5625f; val d1 = 2.75f
        var t = x
        return when {
            t < 1f / d1 -> n1 * t * t
            t < 2f / d1 -> { t -= 1.5f / d1; n1 * t * t + 0.75f }
            t < 2.5f / d1 -> { t -= 2.25f / d1; n1 * t * t + 0.9375f }
            else -> { t -= 2.625f / d1; n1 * t * t + 0.984375f }
        }
    }

    /** CSS-style cubic bezier: control points (x1,y1) (x2,y2), ends at (0,0) and (1,1). */
    fun bezier(x: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        fun bx(t: Float) = 3 * (1 - t) * (1 - t) * t * x1 + 3 * (1 - t) * t * t * x2 + t * t * t
        fun by(t: Float) = 3 * (1 - t) * (1 - t) * t * y1 + 3 * (1 - t) * t * t * y2 + t * t * t
        var lo = 0f; var hi = 1f; var t = x
        repeat(24) {
            val v = bx(t)
            if (abs(v - x) < 0.0005f) return by(t)
            if (v < x) lo = t else hi = t
            t = (lo + hi) / 2f
        }
        return by(t)
    }
}

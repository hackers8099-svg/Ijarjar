package so.ijarjar.app.data

import android.content.Context
import com.google.gson.reflect.TypeToken
import so.ijarjar.app.L
import so.ijarjar.app.model.Easing
import so.ijarjar.app.model.Expression
import so.ijarjar.app.model.Keyframe
import so.ijarjar.app.model.Layer
import so.ijarjar.app.model.LayerAnim
import so.ijarjar.app.model.LoopAnim
import so.ijarjar.app.model.TextAnim
import so.ijarjar.app.model.TextLoop
import so.ijarjar.app.render.LayerRenderer
import java.io.File

/** A saved animation (keyframes relative to the layer's own position + animations + expression). */
class AnimPreset(
    var name: String = "",
    var keys: MutableList<Keyframe> = mutableListOf(),   // cx/cy are offsets, scale/opacity are factors
    var animIn: LayerAnim = LayerAnim.NONE,
    var animOut: LayerAnim = LayerAnim.NONE,
    var animLoop: LoopAnim = LoopAnim.NONE,
    var textIn: TextAnim = TextAnim.NONE,
    var textOut: TextAnim = TextAnim.NONE,
    var textLoop: TextLoop = TextLoop.NONE,
    var animInMs: Long = 500,
    var animOutMs: Long = 500,
    var expr: Expression = Expression.NONE,
    var exprAmp: Float = 1f,
    var exprFreq: Float = 2f,
    var exprDecay: Float = 5f,
    var motionBlur: Boolean = false,
    var fromEnd: Boolean = false   // keyframes counted back from the layer end
)

/** User presets (saved on the phone) and built-in "Motion Tools" style keyframe presets. */
object Presets {

    private fun file(c: Context) = File(c.filesDir, "anim_presets.json")

    fun load(c: Context): MutableList<AnimPreset> = runCatching {
        val type = object : TypeToken<MutableList<AnimPreset>>() {}.type
        ProjectStore.gson.fromJson<MutableList<AnimPreset>>(file(c).readText(), type)
    }.getOrNull() ?: mutableListOf()

    fun save(c: Context, list: List<AnimPreset>) = file(c).writeText(ProjectStore.gson.toJson(list))

    /** Turns a layer's animation into a preset. */
    fun fromLayer(l: Layer, name: String): AnimPreset {
        val base = LayerRenderer.basePose(l, l.startMs)
        return AnimPreset(name = name,
            keys = l.keyframes.map { k -> Keyframe(k.t, k.cx - base.cx, k.cy - base.cy, k.scale / base.scale.coerceAtLeast(0.01f),
                k.rotation - base.rotation, k.opacity, k.sx, k.sy, k.ease, k.bx1, k.by1, k.bx2, k.by2) }.toMutableList(),
            animIn = l.animIn, animOut = l.animOut, animLoop = l.animLoop, textIn = l.textIn, textOut = l.textOut, textLoop = l.textLoop,
            animInMs = l.animInMs, animOutMs = l.animOutMs, expr = l.expr, exprAmp = l.exprAmp, exprFreq = l.exprFreq,
            exprDecay = l.exprDecay, motionBlur = l.motionBlur)
    }

    /** Applies a preset to a layer, keeping where the layer already is. */
    fun apply(p: AnimPreset, l: Layer) {
        val base = LayerRenderer.basePose(l, l.startMs)
        l.keyframes.clear()
        for (k in p.keys) {
            val t = if (p.fromEnd) (l.durationMs - k.t).coerceAtLeast(0) else k.t.coerceAtMost(l.durationMs)
            l.keyframes.add(Keyframe(t, base.cx + k.cx, base.cy + k.cy, base.scale * k.scale, base.rotation + k.rotation,
                (base.opacity * k.opacity).coerceIn(0f, 1f), base.sx * k.sx, base.sy * k.sy, k.ease, k.bx1, k.by1, k.bx2, k.by2))
        }
        l.animIn = p.animIn; l.animOut = p.animOut; l.animLoop = p.animLoop
        if (l.kind == so.ijarjar.app.model.LayerKind.TEXT) { l.textIn = p.textIn; l.textOut = p.textOut; l.textLoop = p.textLoop }
        l.animInMs = p.animInMs; l.animOutMs = p.animOutMs
        l.expr = p.expr; l.exprAmp = p.exprAmp; l.exprFreq = p.exprFreq; l.exprDecay = p.exprDecay; l.motionBlur = p.motionBlur
    }

    private fun k(t: Long, dx: Float = 0f, dy: Float = 0f, s: Float = 1f, r: Float = 0f, o: Float = 1f, e: Easing = Easing.EASE_OUT) =
        Keyframe(t, dx, dy, s, r, o, 1f, 1f, e)

    /** Ready-made keyframe animations (like the Motion Tools plugin). */
    val builtIn: List<AnimPreset>
        get() = listOf(
            AnimPreset(L.t("Bidix ka soo gal", "Slide in left"), mutableListOf(k(0, dx = -0.7f), k(600)), motionBlur = true),
            AnimPreset(L.t("Midig ka soo gal", "Slide in right"), mutableListOf(k(0, dx = 0.7f), k(600)), motionBlur = true),
            AnimPreset(L.t("Hoos ka soo kac", "Rise up"), mutableListOf(k(0, dy = 0.3f, o = 0f), k(700))),
            AnimPreset(L.t("Kor ka soo dhac", "Drop in"), mutableListOf(k(0, dy = -0.5f, e = Easing.BOUNCE), k(900))),
            AnimPreset(L.t("Bood", "Pop in"), mutableListOf(k(0, s = 0f, e = Easing.BACK), k(450))),
            AnimPreset(L.t("Weyneyn", "Zoom in"), mutableListOf(k(0, s = 3f, o = 0f), k(600)), motionBlur = true),
            AnimPreset(L.t("Wareeg gal", "Spin in"), mutableListOf(k(0, s = 0f, r = -270f), k(800))),
            AnimPreset(L.t("Laastig", "Elastic in"), mutableListOf(k(0, s = 0.2f, e = Easing.ELASTIC), k(1000))),
            AnimPreset(L.t("Bood (inertia)", "Overshoot"), mutableListOf(k(0, dx = -0.5f, e = Easing.EASE_IN), k(400, e = Easing.LINEAR)),
                expr = Expression.BOUNCE, exprAmp = 1.2f, exprFreq = 3f, exprDecay = 6f),
            AnimPreset(L.t("Gariir", "Shake"), (0..10).map { i -> k(i * 60L, dx = if (i == 10) 0f else if (i % 2 == 0) -0.02f else 0.02f, e = Easing.LINEAR) }.toMutableList()),
            AnimPreset(L.t("Luleey", "Pendulum"), mutableListOf(k(0, r = -15f, e = Easing.EASE_IN_OUT), k(900, r = 15f, e = Easing.EASE_IN_OUT)), expr = Expression.LOOP_PINGPONG),
            AnimPreset(L.t("Sabbeyn", "Drift"), mutableListOf(k(0, dx = -0.04f, e = Easing.LINEAR), k(4000, dx = 0.04f, e = Easing.LINEAR))),
            AnimPreset("Ken Burns", mutableListOf(k(0, e = Easing.LINEAR), k(5000, s = 1.3f, e = Easing.LINEAR))),
            AnimPreset(L.t("Bax kor", "Exit up"), mutableListOf(k(600, e = Easing.EASE_IN), k(0, dy = -0.4f, o = 0f)), fromEnd = true),
            AnimPreset(L.t("Bax yaraan", "Exit shrink"), mutableListOf(k(500, e = Easing.BACK), k(0, s = 0f)), fromEnd = true)
        )
}

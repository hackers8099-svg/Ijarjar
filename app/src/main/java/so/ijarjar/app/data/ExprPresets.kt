package so.ijarjar.app.data

import android.content.Context
import com.google.gson.reflect.TypeToken
import so.ijarjar.app.L
import java.io.File

/** A saved expression: which property it is for and its code. */
class ExprPreset(var name: String = "", var prop: String = "position", var code: String = "")

object ExprPresets {

    private fun file(c: Context) = File(c.filesDir, "expr_presets.json")

    fun load(c: Context): MutableList<ExprPreset> = runCatching {
        ProjectStore.gson.fromJson<MutableList<ExprPreset>>(file(c).readText(), object : TypeToken<MutableList<ExprPreset>>() {}.type)
    }.getOrNull() ?: mutableListOf()

    fun save(c: Context, list: List<ExprPreset>) = file(c).writeText(ProjectStore.gson.toJson(list))

    /** Ready-made expressions (After Effects classics). */
    val builtIn: List<ExprPreset>
        get() = listOf(
            ExprPreset(L.t("Bood (inertia)", "Inertial bounce"), "position", "bounce(0.06, 2.5, 5)"),
            ExprPreset(L.t("Bood cabbir", "Scale bounce"), "scale", "bounce(0.08, 3, 6)"),
            ExprPreset(L.t("Bood wareeg", "Rotation bounce"), "rotation", "bounce(0.08, 3, 6)"),
            ExprPreset("Wiggle", "position", "wiggle(2, 30)"),
            ExprPreset(L.t("Wiggle wareeg", "Wiggle rotation"), "rotation", "wiggle(3, 8)"),
            ExprPreset("loopOut cycle", "position", "loopOut(\"cycle\")"),
            ExprPreset("loopOut pingpong", "position", "loopOut(\"pingpong\")"),
            ExprPreset(L.t("Wareeg joogto", "Spin"), "rotation", "value + time * 90"),
            ExprPreset(L.t("Neef", "Pulse"), "scale", "value * (1 + 0.08 * sin(time * 6))"),
            ExprPreset(L.t("Sabbeyn", "Float"), "position", "value + [0, sin(time * 2) * 25]"),
            ExprPreset(L.t("Libdhi", "Blink"), "opacity", "time % 1 < 0.5 ? 100 : 0"),
            ExprPreset(L.t("Soo bax 1s", "Fade in 1s"), "opacity", "linear(time, 0, 1, 0, 100)"),
            ExprPreset(L.t("Kala go'go'", "Stop motion"), "position", "posterizeTime(8); wiggle(4, 15)"),
            ExprPreset(L.t("Wareeg 3D", "3D turn"), "rotY", "value + time * 60")
        )
}

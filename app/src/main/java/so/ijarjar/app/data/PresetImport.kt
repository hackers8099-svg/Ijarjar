package so.ijarjar.app.data

import android.content.Context
import android.net.Uri
import android.util.Xml
import com.google.gson.reflect.TypeToken
import org.xmlpull.v1.XmlPullParser
import so.ijarjar.app.model.Easing
import so.ijarjar.app.model.Keyframe
import java.io.StringReader

/**
 * Preset files from other apps:
 *  - Premiere Pro .prfpset (XML): Motion (Position, Scale, Rotation) and Opacity keyframes are read.
 *  - Ijar Jar .ijpreset (JSON): presets shared from another phone.
 * After Effects .ffx is a closed binary format and can't be read.
 */
object PresetImport {

    private const val TICKS = 254016000000.0   // Premiere ticks per second

    sealed class Result {
        class Ok(val presets: List<AnimPreset>) : Result()
        class Fail(val reason: String) : Result()
    }

    fun import(context: Context, uri: Uri): Result {
        val name = so.ijarjar.app.media.MediaUtils.displayName(context, uri)
        val text = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            ?: return Result.Fail("read")
        val lower = name.lowercase()
        if (lower.endsWith(".ffx") || (text.size > 4 && String(text, 0, 4, Charsets.ISO_8859_1) == "RIFX")) return Result.Fail("ffx")
        val s = String(text, Charsets.UTF_8).trimStart('﻿', ' ', '\n', '\r', '\t')
        return when {
            s.startsWith("[") || s.startsWith("{") -> fromJson(s)
            s.startsWith("<") -> fromPremiere(s, name.substringBeforeLast('.'))
            else -> Result.Fail("format")
        }
    }

    private fun fromJson(s: String): Result = runCatching {
        val list: List<AnimPreset> = if (s.startsWith("[")) {
            ProjectStore.gson.fromJson(s, object : TypeToken<List<AnimPreset>>() {}.type)
        } else listOf(ProjectStore.gson.fromJson(s, AnimPreset::class.java))
        val ok = list.filter { it.name.isNotBlank() || it.keys.isNotEmpty() }
        if (ok.isEmpty()) Result.Fail("empty") else Result.Ok(ok)
    }.getOrElse { Result.Fail("format") }

    fun toJson(list: List<AnimPreset>): String = ProjectStore.gson.toJson(list)

    private class Param(var name: String = "", var keys: String = "", var start: String = "")

    /** One keyframe track: (time ms, values). */
    private fun track(p: Param): List<Pair<Long, FloatArray>> {
        val out = ArrayList<Pair<Long, FloatArray>>()
        for (e in p.keys.split(';')) {
            val parts = e.trim().split(',')
            if (parts.size < 2) continue
            val tick = parts[0].trim().toDoubleOrNull() ?: continue
            if (tick < -1e15) continue
            val v = parts[1].trim().split(':').mapNotNull { it.trim().toFloatOrNull() }
            if (v.isEmpty()) continue
            out.add(Pair((tick / TICKS * 1000).toLong(), v.toFloatArray()))
        }
        return out.sortedBy { it.first }
    }

    private fun valueAt(tr: List<Pair<Long, FloatArray>>, t: Long, i: Int): Float? {
        if (tr.isEmpty()) return null
        fun g(a: FloatArray) = a.getOrElse(i) { a[0] }
        if (t <= tr.first().first) return g(tr.first().second)
        if (t >= tr.last().first) return g(tr.last().second)
        for (k in 1 until tr.size) {
            val (t1, v1) = tr[k]
            if (t <= t1) {
                val (t0, v0) = tr[k - 1]
                val f = if (t1 == t0) 1f else (t - t0).toFloat() / (t1 - t0)
                return g(v0) + (g(v1) - g(v0)) * f
            }
        }
        return g(tr.last().second)
    }

    private fun fromPremiere(xml: String, fallbackName: String): Result {
        val params = ArrayList<Param>()
        val names = ArrayList<String>()
        try {
            val xp = Xml.newPullParser()
            xp.setInput(StringReader(xml))
            val stack = ArrayList<String>()
            var cur: Param? = null
            var depth = 0
            while (true) {
                when (xp.next()) {
                    XmlPullParser.START_TAG -> {
                        stack.add(xp.name)
                        if (xp.name.endsWith("Param") && xp.name != "Param" && cur == null) { cur = Param(); depth = stack.size }
                    }
                    XmlPullParser.TEXT -> {
                        val tag = stack.lastOrNull()
                        val txt = xp.text ?: ""
                        if (tag == "PresetName" && txt.isNotBlank()) names.add(txt.trim())
                        cur?.let { p ->
                            if (stack.size == depth + 1) when (tag) {
                                "Name" -> p.name = txt.trim()
                                "Keyframes" -> p.keys += txt
                                "StartKeyframe" -> p.start += txt
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (cur != null && stack.size == depth) { params.add(cur); cur = null }
                        if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                    }
                    XmlPullParser.END_DOCUMENT -> break
                }
            }
        } catch (e: Exception) {
            if (params.isEmpty()) return Result.Fail("format")
        }
        fun find(vararg n: String) = params.firstOrNull { p -> n.any { it.equals(p.name, true) } && track(p).size >= 2 }?.let { track(it) } ?: emptyList()
        val pos = find("Position")
        val scale = find("Scale", "Uniform Scale")
        val rot = find("Rotation")
        val op = find("Opacity")
        val all = listOf(pos, scale, rot, op).filter { it.isNotEmpty() }
        if (all.isEmpty()) return Result.Fail("nokeys")
        val times = all.flatMap { tr -> tr.map { it.first } }.toSortedSet().toList()
        val t0 = times.first()
        val px0 = valueAt(pos, t0, 0) ?: 0.5f; val py0 = valueAt(pos, t0, 1) ?: 0.5f
        val keys = times.map { t ->
            Keyframe(t - t0,
                (valueAt(pos, t, 0) ?: px0) - px0,
                (valueAt(pos, t, 1) ?: py0) - py0,
                ((valueAt(scale, t, 0) ?: 100f) / 100f).coerceAtLeast(0f),
                valueAt(rot, t, 0) ?: 0f,
                ((valueAt(op, t, 0) ?: 100f) / 100f).coerceIn(0f, 1f),
                1f, 1f, Easing.EASE_IN_OUT)
        }.toMutableList()
        return Result.Ok(listOf(AnimPreset(name = names.firstOrNull() ?: fallbackName, keys = keys)))
    }
}

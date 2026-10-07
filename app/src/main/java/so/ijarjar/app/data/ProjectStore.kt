package so.ijarjar.app.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import so.ijarjar.app.model.AudioKind
import so.ijarjar.app.model.AudioTrack
import so.ijarjar.app.model.Project
import java.io.File

object ProjectStore {
    val gson: Gson = GsonBuilder().create()

    private fun dir(context: Context): File = File(context.filesDir, "projects").apply { mkdirs() }

    fun save(context: Context, project: Project) {
        project.updatedAt = System.currentTimeMillis()
        val f = File(dir(context), project.id + ".json")
        val tmp = File(dir(context), project.id + ".tmp")
        tmp.writeText(gson.toJson(project))
        tmp.renameTo(f)
    }

    fun load(context: Context, id: String): Project? {
        val f = File(dir(context), "$id.json")
        if (!f.exists()) return null
        return try {
            fromJson(f.readText())
        } catch (e: Exception) {
            null
        }
    }

    fun list(context: Context): List<Project> =
        dir(context).listFiles { f -> f.name.endsWith(".json") }
            ?.mapNotNull { runCatching { fromJson(it.readText()) }.getOrNull() }
            ?.sortedByDescending { it.updatedAt } ?: emptyList()

    fun delete(context: Context, id: String) {
        File(dir(context), "$id.json").delete()
    }

    fun toJson(p: Project): String = gson.toJson(p)

    fun fromJson(s: String): Project {
        val p = gson.fromJson(s, Project::class.java)
        // Gson can leave nulls for missing fields; repair them.
        if (p.clips == null) p.clips = mutableListOf()
        if (p.layers == null) p.layers = mutableListOf()
        if (p.audios == null) p.audios = mutableListOf()
        for (l in p.layers) {
            if (l.keyframes == null) l.keyframes = mutableListOf()
            if (l.frames == null) l.frames = mutableListOf()
            if (l.strokes == null) l.strokes = mutableListOf()
            if (l.adjust == null) l.adjust = so.ijarjar.app.model.Adjust()
        }
        for (c in p.clips) {
            if (c.curvePoints == null || c.curvePoints.size != 5) c.curvePoints = mutableListOf(1f, 1f, 1f, 1f, 1f)
            if (c.adjust == null) c.adjust = so.ijarjar.app.model.Adjust()
        }
        if (p.bgAdjust == null) p.bgAdjust = so.ijarjar.app.model.Adjust()
        // older projects had a single "music" field
        p.music?.let { m ->
            if (m.uri.isNotEmpty()) {
                val len = if (m.sourceDurationMs > 0) (m.sourceDurationMs - m.trimStartMs).coerceAtLeast(500) else p.durationMs
                p.audios.add(AudioTrack(uri = m.uri, name = m.name, kind = AudioKind.MUSIC, startMs = 0,
                    trimStartMs = m.trimStartMs, durationMs = minOf(len, p.durationMs.coerceAtLeast(500)),
                    sourceDurationMs = m.sourceDurationMs, volume = m.volume))
            }
            p.music = null
        }
        return p
    }
}

/** Simple snapshot based undo / redo. */
class History(private val limit: Int = 60) {
    private val undo = ArrayDeque<String>()
    private val redo = ArrayDeque<String>()

    fun push(state: String) {
        if (undo.lastOrNull() == state) return
        undo.addLast(state)
        if (undo.size > limit) undo.removeFirst()
        redo.clear()
    }

    fun canUndo() = undo.size > 1
    fun canRedo() = redo.isNotEmpty()

    fun undo(): String? {
        if (undo.size <= 1) return null
        redo.addLast(undo.removeLast())
        return undo.last()
    }

    fun redo(): String? {
        val s = redo.removeLastOrNull() ?: return null
        undo.addLast(s)
        return s
    }
}

package so.ijarjar.app.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
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

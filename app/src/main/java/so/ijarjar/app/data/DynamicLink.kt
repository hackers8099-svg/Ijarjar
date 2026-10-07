package so.ijarjar.app.data

import android.content.Context
import android.graphics.Bitmap
import androidx.media3.common.util.UnstableApi
import so.ijarjar.app.export.PhotoExporter
import java.io.File

/**
 * Dynamic link (like Premiere Pro <-> After Effects): a photo project used inside a video.
 * Whenever the photo project is saved, the video shows the new version.
 */
@UnstableApi
object DynamicLink {
    private class Entry(val stamp: Long, val bmp: Bitmap)

    private val cache = HashMap<String, Entry>()
    private val lastCheck = HashMap<String, Long>()

    fun file(context: Context, id: String) = File(File(context.filesDir, "projects"), "$id.json")

    @Synchronized
    fun bitmap(context: Context, id: String, maxDim: Int): Bitmap? {
        val key = "$id@$maxDim"
        val now = System.currentTimeMillis()
        val have = cache[key]
        // look at the file at most twice a second
        if (have != null && now - (lastCheck[key] ?: 0) < 500) return have.bmp
        lastCheck[key] = now
        val f = file(context, id)
        if (!f.exists()) return have?.bmp
        val stamp = f.lastModified()
        if (have != null && have.stamp == stamp) return have.bmp
        val p = ProjectStore.load(context, id) ?: return have?.bmp
        val r = p.aspectRatio()
        val shortSide = if (r >= 1f) (maxDim / r).toInt() else (maxDim * r).toInt()
        val bmp = runCatching { PhotoExporter.render(context, p, shortSide.coerceIn(64, 2160)) }.getOrNull() ?: return have?.bmp
        cache[key] = Entry(stamp, bmp)
        return bmp
    }
}

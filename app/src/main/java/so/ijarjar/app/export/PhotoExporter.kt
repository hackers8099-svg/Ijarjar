package so.ijarjar.app.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import so.ijarjar.app.model.Project
import so.ijarjar.app.render.BackgroundRenderer
import so.ijarjar.app.render.LayerRenderer
import java.io.File
import java.io.FileOutputStream

/** Renders a photo project (PixelLab mode) to PNG or JPG and saves it to Pictures/IjarJar. */
@UnstableApi
object PhotoExporter {

    fun render(context: Context, p: Project, shortSide: Int): Bitmap {
        val (w, h) = p.outputSize(shortSide)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        BackgroundRenderer.draw(context, canvas, p, w, h, maxOf(w, h))
        for (l in LayerRenderer.drawOrder(p.layers)) {
            if (!l.isActive(0)) continue
            LayerRenderer.draw(context, canvas, l, 0, w, h, null, maxOf(w, h))
        }
        return bmp
    }

    /** Returns the gallery uri (or null) and the local file. */
    fun export(context: Context, p: Project, shortSide: Int, png: Boolean): Pair<Uri?, File> {
        val bmp = render(context, p, shortSide)
        val ext = if (png) "png" else "jpg"
        val file = File(context.cacheDir, "ijarjar_photo.$ext")
        FileOutputStream(file).use { bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 95, it) }
        bmp.recycle()
        val uri = Exporter.saveMedia(context, file, "IjarJar_" + System.currentTimeMillis() + "." + ext,
            if (png) "image/png" else "image/jpeg", false)
        return Pair(uri, file)
    }
}

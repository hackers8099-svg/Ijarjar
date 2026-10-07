package so.ijarjar.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Movie
import android.net.Uri
import so.ijarjar.app.model.Layer
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import com.airbnb.lottie.TextDelegate

/**
 * Transparent motion graphics: PNG sequences (After Effects "PNG sequence" export) and GIFs.
 * Frames loop for as long as the layer is on the timeline.
 */
@Suppress("DEPRECATION")
object AnimatedSource {

    private class Gif(val movie: Movie, val bmp: Bitmap)

    private val gifs = HashMap<String, Gif?>()

    @Synchronized
    private fun gif(context: Context, uri: String, maxDim: Int): Gif? {
        val key = "$uri@$maxDim"
        if (gifs.containsKey(key)) return gifs[key]
        val g = try {
            val bytes = context.contentResolver.openInputStream(Uri.parse(uri))?.use { it.readBytes() }
            val m = bytes?.let { Movie.decodeByteArray(it, 0, it.size) }
            if (m == null || m.width() <= 0) null else {
                val s = minOf(1f, maxDim.toFloat() / maxOf(m.width(), m.height()))
                Gif(m, Bitmap.createBitmap((m.width() * s).toInt().coerceAtLeast(1), (m.height() * s).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888))
            }
        } catch (e: Exception) { null }
        gifs[key] = g
        return g
    }

    // ---------------- Lottie (After Effects -> Bodymovin JSON) ----------------

    private class Lot(val drawable: LottieDrawable, val comp: LottieComposition, val bmp: Bitmap, var text: String)

    private val lotties = HashMap<String, Lot?>()

    private fun loadComposition(context: Context, uri: String): LottieComposition? = try {
        val name = MediaUtils.displayName(context, Uri.parse(uri)).lowercase()
        context.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
            if (name.endsWith(".lottie") || name.endsWith(".zip"))
                LottieCompositionFactory.fromZipStreamSync(java.util.zip.ZipInputStream(input), uri).value
            else LottieCompositionFactory.fromJsonInputStreamSync(input, uri).value
        }
    } catch (e: Exception) { null }

    @Synchronized
    private fun lottie(context: Context, l: Layer, maxDim: Int): Lot? {
        val uri = l.uri ?: return null
        val key = "$uri@$maxDim"
        val have = lotties[key]
        if (have != null || lotties.containsKey(key)) {
            if (have != null && have.text != l.lottieText) applyText(have, l.lottieText)
            return have
        }
        val comp = loadComposition(context, uri)
        val lot = if (comp == null) null else {
            val d = LottieDrawable()
            d.composition = comp
            val bw = comp.bounds.width().coerceAtLeast(1); val bh = comp.bounds.height().coerceAtLeast(1)
            val s = minOf(1f, maxDim.toFloat() / maxOf(bw, bh))
            val bmp = Bitmap.createBitmap((bw * s).toInt().coerceAtLeast(1), (bh * s).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, bmp.width, bmp.height)
            Lot(d, comp, bmp, "").also { applyText(it, l.lottieText) }
        }
        lotties[key] = lot
        return lot
    }

    private fun applyText(lot: Lot, text: String) {
        lot.text = text
        // replace every text layer in the animation with the user's words (blank = keep original)
        val td = object : TextDelegate(lot.drawable) {
            override fun getText(layerName: String?, input: String?): String = if (text.isBlank()) (input ?: "") else text
        }
        lot.drawable.setTextDelegate(td)
    }

    fun isLottieName(name: String) = name.lowercase().let { it.endsWith(".json") || it.endsWith(".lottie") }

    /** Size (w, h) of the animation, or null. */
    fun size(context: Context, l: Layer): Pair<Int, Int>? {
        if (l.isLottie) {
            val comp = loadComposition(context, l.uri ?: return null) ?: return null
            return Pair(comp.bounds.width(), comp.bounds.height())
        }
        if (l.frames.isNotEmpty()) {
            val b = MediaUtils.loadBitmapCached(context, Uri.parse(l.frames[0]), 512) ?: return null
            return Pair(b.width, b.height)
        }
        val g = gif(context, l.uri ?: return null, 1024) ?: return null
        return Pair(g.movie.width(), g.movie.height())
    }

    fun lottieDurationMs(context: Context, uri: String): Long =
        loadComposition(context, uri)?.duration?.toLong()?.coerceAtLeast(100) ?: 0

    fun gifDurationMs(context: Context, uri: String): Long {
        val g = gif(context, uri, 1024) ?: return 0
        return g.movie.duration().toLong().coerceAtLeast(100)
    }

    /** The frame to show [localMs] after the layer starts. */
    @Synchronized
    fun frameAt(context: Context, l: Layer, localMs: Long, maxDim: Int): Bitmap? {
        if (l.isLottie) {
            val lot = lottie(context, l, maxDim) ?: return null
            val dur = lot.comp.duration.coerceAtLeast(1f)
            lot.drawable.progress = ((localMs.coerceAtLeast(0) % dur.toLong()) / dur).coerceIn(0f, 1f)
            lot.bmp.eraseColor(Color.TRANSPARENT)
            lot.drawable.draw(Canvas(lot.bmp))
            return lot.bmp
        }
        if (l.frames.isNotEmpty()) {
            val n = l.frames.size
            val i = ((localMs.coerceAtLeast(0) * l.fps / 1000f).toLong() % n).toInt()
            return MediaUtils.loadBitmapCached(context, Uri.parse(l.frames[i]), maxDim)
        }
        val uri = l.uri ?: return null
        val g = gif(context, uri, maxDim) ?: return null
        val dur = g.movie.duration().coerceAtLeast(100)
        g.movie.setTime((localMs.coerceAtLeast(0) % dur).toInt())
        g.bmp.eraseColor(Color.TRANSPARENT)
        val c = Canvas(g.bmp)
        c.scale(g.bmp.width.toFloat() / g.movie.width(), g.bmp.height.toFloat() / g.movie.height())
        g.movie.draw(c, 0f, 0f)
        return g.bmp
    }
}

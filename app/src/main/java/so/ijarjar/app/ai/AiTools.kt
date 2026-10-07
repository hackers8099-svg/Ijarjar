package so.ijarjar.app.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import so.ijarjar.app.media.MediaUtils
import so.ijarjar.app.model.Adjust
import java.io.File
import java.io.FileOutputStream
import kotlin.math.sqrt

/** On-device AI photo tools (Google ML Kit): background removal and auto enhance. */
object AiTools {

    /** Cuts the main subject out of a picture. Calls back on the main thread with a PNG file or null. */
    fun removeBackground(context: Context, uri: Uri, done: (File?, String?) -> Unit) {
        val src = MediaUtils.loadBitmap(context, uri, 2048)
        if (src == null) { done(null, "load"); return }
        val image = InputImage.fromBitmap(src, 0)
        val options = SubjectSegmenterOptions.Builder().enableForegroundBitmap().build()
        SubjectSegmentation.getClient(options).process(image)
            .addOnSuccessListener { result ->
                val fg = result.foregroundBitmap
                if (fg != null) done(save(context, fg), null) else selfie(context, src, done)
            }
            .addOnFailureListener { selfie(context, src, done) }
    }

    /** Fallback: the people-only selfie model (bundled in the app, works offline). */
    private fun selfie(context: Context, src: Bitmap, done: (File?, String?) -> Unit) {
        val opts = SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).build()
        Segmentation.getClient(opts).process(InputImage.fromBitmap(src, 0))
            .addOnSuccessListener { mask ->
                val w = mask.width; val h = mask.height
                val buf = mask.buffer
                buf.rewind()
                val scaled = Bitmap.createScaledBitmap(src, w, h, true)
                val px = IntArray(w * h)
                scaled.getPixels(px, 0, w, 0, 0, w, h)
                for (i in 0 until w * h) {
                    val a = (buf.float * 255f).toInt().coerceIn(0, 255)
                    px[i] = (a shl 24) or (px[i] and 0x00FFFFFF)
                }
                val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                out.setPixels(px, 0, w, 0, 0, w, h)
                done(save(context, out), null)
            }
            .addOnFailureListener { done(null, it.message) }
    }

    private fun save(context: Context, b: Bitmap): File {
        val dir = File(context.filesDir, "cutouts").apply { mkdirs() }
        val f = File(dir, "cut_${System.currentTimeMillis()}.png")
        FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }

    /** Looks at the picture and suggests brightness / contrast / saturation (one-tap enhance). */
    fun autoEnhance(context: Context, uri: Uri, a: Adjust) {
        val b = MediaUtils.loadBitmap(context, uri, 256) ?: return
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        var sum = 0.0; var sum2 = 0.0; var sat = 0.0
        val hsv = FloatArray(3)
        for (c in px) {
            val l = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0
            sum += l; sum2 += l * l
            Color.colorToHSV(c, hsv); sat += hsv[1]
        }
        val n = px.size.toDouble()
        val mean = sum / n
        val std = sqrt((sum2 / n - mean * mean).coerceAtLeast(0.0))
        val meanSat = sat / n
        a.brightness = ((0.5 - mean) * 0.8).toFloat().coerceIn(-0.4f, 0.4f)
        a.contrast = ((0.22 - std) * 2.0).toFloat().coerceIn(-0.2f, 0.5f)
        a.saturation = ((0.45 - meanSat) * 0.8).toFloat().coerceIn(-0.1f, 0.4f)
    }
}

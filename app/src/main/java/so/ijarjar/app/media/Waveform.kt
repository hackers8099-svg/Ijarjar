package so.ijarjar.app.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Real audio waveforms (like CapCut): the sound is decoded once in the background and its loudness
 * stored per 20 ms; the timeline draws the bars from that.
 */
object Waveform {

    const val BUCKET_MS = 20L

    private val mem = HashMap<String, FloatArray>()
    private val pending = HashSet<String>()
    private val worker = Executors.newSingleThreadExecutor()

    /** Peaks (0..1, one per [BUCKET_MS]) or null while it is still being read; [onReady] is called when done. */
    fun get(context: Context, uri: String, onReady: () -> Unit): FloatArray? {
        synchronized(mem) {
            mem[uri]?.let { return it }
            if (!pending.add(uri)) return null
        }
        val app = context.applicationContext
        worker.execute {
            val data = runCatching { load(app, uri) }.getOrNull() ?: FloatArray(0)
            synchronized(mem) { mem[uri] = data; pending.remove(uri) }
            android.os.Handler(android.os.Looper.getMainLooper()).post(onReady)
        }
        return null
    }

    private fun cacheFile(c: Context, uri: String) = File(File(c.cacheDir, "waves").apply { mkdirs() }, Integer.toHexString(uri.hashCode()) + ".w")

    private fun load(c: Context, uri: String): FloatArray {
        val f = cacheFile(c, uri)
        if (f.exists()) {
            val b = f.readBytes()
            return FloatArray(b.size) { (b[it].toInt() and 0xFF) / 255f }
        }
        val peaks = decode(c, Uri.parse(uri)) ?: return FloatArray(0)
        // normalise so quiet files still show
        val top = (peaks.maxOrNull() ?: 1f).coerceAtLeast(0.05f)
        val out = FloatArray(peaks.size) { (peaks[it] / top).coerceIn(0f, 1f) }
        runCatching { f.writeBytes(ByteArray(out.size) { (out[it] * 255).toInt().toByte() }) }
        return out
    }

    private fun decode(c: Context, uri: Uri): FloatArray? {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(c, uri, null)
            var track = -1
            for (i in 0 until ex.trackCount) if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; break }
            if (track < 0) return null
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: return null
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(fmt, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            val out = ArrayList<Float>()
            var bucketFrames = (rate * BUCKET_MS / 1000).toInt().coerceAtLeast(1)
            var acc = 0.0; var peak = 0f; var n = 0
            var inputDone = false; var outputDone = false
            val start = System.currentTimeMillis()
            while (!outputDone && System.currentTimeMillis() - start < 60_000) {
                if (!inputDone) {
                    val ii = codec.dequeueInputBuffer(5000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { codec.queueInputBuffer(ii, 0, size, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 5000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec.outputFormat
                    rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                    bucketFrames = (rate * BUCKET_MS / 1000).toInt().coerceAtLeast(1)
                } else if (oi >= 0) {
                    val ob = codec.getOutputBuffer(oi)
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset); ob.limit(info.offset + info.size)
                        val sb = ob.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        while (sb.remaining() >= ch) {
                            var m = 0f
                            for (k in 0 until ch) m = max(m, abs(sb.get() / 32768f))
                            acc += m * m; peak = max(peak, m); n++
                            if (n >= bucketFrames) {
                                // mix of loudness (rms) and peaks looks like CapCut's bars
                                out.add((sqrt(acc / n).toFloat() * 0.6f + peak * 0.4f))
                                acc = 0.0; peak = 0f; n = 0
                            }
                        }
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
            if (n > 0) out.add(sqrt(acc / n).toFloat())
            codec.stop(); codec.release()
            return out.toFloatArray()
        } catch (e: Exception) {
            return null
        } finally {
            ex.release()
        }
    }
}

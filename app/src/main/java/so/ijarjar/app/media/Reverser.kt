package so.ijarjar.app.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

/**
 * Makes a reversed copy of part of a video: frames are grabbed, written in reverse order and
 * re-encoded; the sound is decoded, reversed and mixed back in.
 */
@UnstableApi
class Reverser(private val context: Context, private val uri: Uri, private val startMs: Long, private val endMs: Long) {

    interface Callback {
        fun onProgress(percent: Int)
        fun onDone(file: File, durationMs: Long)
        fun onError(message: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var cancelled = false
    private var transformer: Transformer? = null

    fun cancel() {
        cancelled = true
        main.post { transformer?.cancel() }
    }

    fun start(cb: Callback) {
        io.execute {
            try {
                val work = File(context.cacheDir, "reverse").apply { deleteRecursively(); mkdirs() }
                val frames = grabFrames(work) { p -> main.post { cb.onProgress(p * 50 / 100) } }
                if (cancelled) return@execute
                if (frames.isEmpty()) { main.post { cb.onError("no frames") }; return@execute }
                val wav = runCatching { reversedAudio(work) }.getOrNull()
                main.post { encode(frames, wav, cb) }
            } catch (e: Exception) {
                main.post { cb.onError(e.message ?: e.toString()) }
            }
        }
    }

    private var fps = 30f

    private fun grabFrames(dir: File, progress: (Int) -> Unit): List<File> {
        val r = MediaMetadataRetriever()
        r.setDataSource(context, uri)
        val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        val count = if (Build.VERSION.SDK_INT >= 28) r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull() ?: 0 else 0
        val srcFps = if (count > 0 && dur > 0) count * 1000f / dur else 30f
        fps = srcFps.coerceIn(10f, 30f)
        val step = 1000f / fps
        val total = ((endMs - startMs) / step).toInt().coerceAtLeast(1)
        val out = ArrayList<File>()
        var i = 0
        while (i < total && !cancelled) {
            val tMs = startMs + (i * step).toLong()
            val bmp: Bitmap? = if (Build.VERSION.SDK_INT >= 28 && count > 0) {
                val idx = (tMs * srcFps / 1000f).toInt().coerceIn(0, count - 1)
                runCatching { r.getFrameAtIndex(idx) }.getOrNull()
            } else r.getFrameAtTime(tMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
            if (bmp != null) {
                val s = minOf(1f, 1280f / maxOf(bmp.width, bmp.height))
                val scaled = if (s < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt() / 2 * 2, (bmp.height * s).toInt() / 2 * 2, true) else bmp
                val f = File(dir, "f%05d.jpg".format(i))
                FileOutputStream(f).use { scaled.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                if (scaled !== bmp) scaled.recycle()
                bmp.recycle()
                out.add(f)
            }
            i++
            if (i % 5 == 0) progress(i * 100 / total)
        }
        r.release()
        return out.reversed()
    }

    /** Decodes the audio of the range, reverses it and writes a WAV file. */
    private fun reversedAudio(dir: File): File? {
        val ex = MediaExtractor()
        ex.setDataSource(context, uri, null)
        var track = -1
        var fmt: MediaFormat? = null
        for (t in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(t)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = t; fmt = f; break }
        }
        if (track < 0 || fmt == null) { ex.release(); return null }
        ex.selectTrack(track)
        ex.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val pcm = java.io.ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var inDone = false; var outDone = false
        while (!outDone && !cancelled) {
            if (!inDone) {
                val ii = codec.dequeueInputBuffer(10000)
                if (ii >= 0) {
                    val buf = codec.getInputBuffer(ii)!!
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0 || ex.sampleTime > endMs * 1000) {
                        codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true
                    } else { codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance() }
                }
            }
            val oi = codec.dequeueOutputBuffer(info, 10000)
            if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val of = codec.outputFormat
                rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else if (oi >= 0) {
                if (info.presentationTimeUs >= startMs * 1000 && info.size > 0) {
                    val ob = codec.getOutputBuffer(oi)!!
                    val bytes = ByteArray(info.size)
                    ob.position(info.offset); ob.get(bytes)
                    pcm.write(bytes)
                }
                codec.releaseOutputBuffer(oi, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
            }
        }
        codec.stop(); codec.release(); ex.release()
        val data = pcm.toByteArray()
        val frameBytes = 2 * channels
        val frames = data.size / frameBytes
        val rev = ByteArray(frames * frameBytes)
        for (f in 0 until frames) System.arraycopy(data, f * frameBytes, rev, (frames - 1 - f) * frameBytes, frameBytes)
        val wav = File(dir, "audio.wav")
        FileOutputStream(wav).use { out ->
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()); h.putInt(36 + rev.size); h.put("WAVE".toByteArray())
            h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(channels.toShort())
            h.putInt(rate); h.putInt(rate * frameBytes); h.putShort(frameBytes.toShort()); h.putShort(16)
            h.put("data".toByteArray()); h.putInt(rev.size)
            out.write(h.array()); out.write(rev)
        }
        return wav
    }

    private fun encode(frames: List<File>, wav: File?, cb: Callback) {
        if (cancelled) return
        val frameMs = (1000f / fps).toLong().coerceAtLeast(1)
        val items = frames.map { f ->
            EditedMediaItem.Builder(MediaItem.Builder().setUri(Uri.fromFile(f)).setImageDurationMs(frameMs).build())
                .setDurationUs(frameMs * 1000).setFrameRate(fps.toInt()).build()
        }
        val seqs = mutableListOf(EditedMediaItemSequence(items))
        if (wav != null) seqs.add(EditedMediaItemSequence(listOf(EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(wav))).build())))
        val composition = Composition.Builder(seqs).experimentalSetForceAudioTrack(true).build()
        val dir = File(context.filesDir, "reversed").apply { mkdirs() }
        val out = File(dir, "rev_${System.currentTimeMillis()}.mp4")
        val t = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    main.removeCallbacksAndMessages(null)
                    cb.onDone(out, frames.size * frameMs)
                }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    main.removeCallbacksAndMessages(null)
                    cb.onError(exportException.errorCodeName)
                }
            }).build()
        transformer = t
        t.start(composition, out.absolutePath)
        val holder = ProgressHolder()
        main.postDelayed(object : Runnable {
            override fun run() {
                val tr = transformer ?: return
                if (tr.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) cb.onProgress(50 + holder.progress / 2)
                main.postDelayed(this, 400)
            }
        }, 400)
    }
}

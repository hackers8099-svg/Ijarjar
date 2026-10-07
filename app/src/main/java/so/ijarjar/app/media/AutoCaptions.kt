package so.ijarjar.app.media

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import so.ijarjar.app.model.MediaKind
import so.ijarjar.app.model.Project
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Automatic captions: the video's sound is cut into short pieces at pauses and each piece is
 * sent to the phone's speech recogniser (Android 13+). Language: Somali, English, Arabic or auto.
 */
class AutoCaptions(private val context: Context) {

    class Piece(val startMs: Long, val endMs: Long, val text: String)

    interface Callback {
        fun onProgress(done: Int, total: Int)
        fun onDone(pieces: List<Piece>)
        fun onError(message: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var cancelled = false
    private var recognizer: SpeechRecognizer? = null

    fun cancel() {
        cancelled = true
        main.post { recognizer?.destroy(); recognizer = null }
    }

    companion object {
        const val RATE = 16000
        fun supported(context: Context) = Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isRecognitionAvailable(context)
    }

    /** language: "auto", "so-SO", "en-US", "ar-SA" … */
    fun start(project: Project, language: String, cb: Callback) {
        io.execute {
            try {
                val pcm = timelineAudio(project)
                if (cancelled) return@execute
                val pieces = split(pcm)
                if (pieces.isEmpty()) { main.post { cb.onError("no speech") }; return@execute }
                main.post { recogniseAll(pcm, pieces, language, cb) }
            } catch (e: Exception) {
                main.post { cb.onError(e.message ?: e.toString()) }
            }
        }
    }

    /** Mono 16 kHz PCM of the whole main track, following the timeline. */
    private fun timelineAudio(p: Project): ShortArray {
        val total = (p.durationMs * RATE / 1000).toInt()
        val out = ShortArray(total)
        for ((i, c) in p.clips.withIndex()) {
            if (c.kind != MediaKind.VIDEO) continue
            val pcm = decode(Uri.parse(c.uri), c.trimStartMs, c.trimEndMs) ?: continue
            val start = (p.clipStartMs(i) * RATE / 1000).toInt()
            val speed = (c.trimmedMs.toFloat() / c.outDurationMs).coerceAtLeast(0.1f)
            val outLen = (c.outDurationMs * RATE / 1000).toInt()
            for (k in 0 until outLen) {
                val src = (k * speed).toInt()
                if (src >= pcm.size || start + k >= total) break
                out[start + k] = pcm[src]
            }
        }
        return out
    }

    /** Decodes a range of a file's audio to mono 16 kHz. */
    private fun decode(uri: Uri, startMs: Long, endMs: Long): ShortArray? {
        val ex = MediaExtractor()
        ex.setDataSource(context, uri, null)
        var track = -1; var fmt: MediaFormat? = null
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
        var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val raw = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var inDone = false; var outDone = false
        while (!outDone && !cancelled) {
            if (!inDone) {
                val ii = codec.dequeueInputBuffer(10000)
                if (ii >= 0) {
                    val buf = codec.getInputBuffer(ii)!!
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0 || ex.sampleTime > endMs * 1000) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                    else { codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance() }
                }
            }
            val oi = codec.dequeueOutputBuffer(info, 10000)
            if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                rate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else if (oi >= 0) {
                if (info.presentationTimeUs >= startMs * 1000 && info.size > 0) {
                    val ob = codec.getOutputBuffer(oi)!!
                    val bytes = ByteArray(info.size); ob.position(info.offset); ob.get(bytes); raw.write(bytes)
                }
                codec.releaseOutputBuffer(oi, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
            }
        }
        codec.stop(); codec.release(); ex.release()
        val b = raw.toByteArray()
        val frames = b.size / (2 * ch)
        val outLen = (frames.toLong() * RATE / rate).toInt()
        val out = ShortArray(outLen)
        for (k in 0 until outLen) {
            val f = (k.toLong() * rate / RATE).toInt().coerceAtMost(frames - 1)
            var sum = 0
            for (c in 0 until ch) {
                val o = (f * ch + c) * 2
                sum += ((b[o + 1].toInt() shl 8) or (b[o].toInt() and 0xFF)).toShort().toInt()
            }
            out[k] = (sum / ch).toShort()
        }
        return out
    }

    /** Cuts the sound at quiet moments into 1.5–6 s pieces that contain speech. */
    private fun split(pcm: ShortArray): List<LongArray> {
        val win = RATE / 50 // 20 ms
        val n = pcm.size / win
        val energy = FloatArray(n) { w ->
            var s = 0L
            for (k in w * win until (w + 1) * win) s += abs(pcm[k].toInt())
            s.toFloat() / win
        }
        val sorted = energy.sorted()
        val noise = if (sorted.isEmpty()) 0f else sorted[(sorted.size * 0.2f).toInt()]
        val thr = maxOf(noise * 2.5f, 300f)
        val pieces = ArrayList<LongArray>()
        var start = -1; var lastLoud = -1
        for (w in 0 until n) {
            val loud = energy[w] > thr
            if (loud) { if (start < 0) start = w; lastLoud = w }
            val len = if (start >= 0) w - start else 0
            val silentFor = if (lastLoud >= 0) w - lastLoud else 0
            if (start >= 0 && ((silentFor > 15 && len > 50) || len > 300 || w == n - 1)) {
                val s = (start - 5).coerceAtLeast(0); val e = (lastLoud + 8).coerceAtMost(n)
                if (e - s > 15) pieces.add(longArrayOf(s * 20L, e * 20L))
                start = -1; lastLoud = -1
            }
        }
        return pieces
    }

    private fun recogniseAll(pcm: ShortArray, pieces: List<LongArray>, language: String, cb: Callback) {
        val results = ArrayList<Piece>()
        fun next(i: Int) {
            if (cancelled) return
            if (i >= pieces.size) { recognizer?.destroy(); recognizer = null; cb.onDone(results); return }
            cb.onProgress(i, pieces.size)
            val p = pieces[i]
            recognise(pcm, p[0], p[1], language) { text ->
                if (!text.isNullOrBlank()) results.add(Piece(p[0], p[1], text.trim()))
                main.post { next(i + 1) }
            }
        }
        next(0)
    }

    private fun recognise(pcm: ShortArray, startMs: Long, endMs: Long, language: String, done: (String?) -> Unit) {
        if (Build.VERSION.SDK_INT < 33) { done(null); return }
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        val pipe = ParcelFileDescriptor.createPipe()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, RATE)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            if (language == "auto") {
                if (Build.VERSION.SDK_INT >= 34) {
                    putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, arrayListOf("so-SO", "en-US", "ar-SA"))
                }
            } else {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
            }
        }
        var finished = false
        fun finish(t: String?) { if (finished) return; finished = true; runCatching { pipe[0].close() }; done(t) }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) = finish(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
            override fun onError(error: Int) = finish(null)
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(intent)
        // feed the piece (plus a little silence) through the pipe
        io.execute {
            runCatching {
                FileOutputStream(pipe[1].fileDescriptor).use { out ->
                    val s = (startMs * RATE / 1000).toInt().coerceIn(0, pcm.size)
                    val e = (endMs * RATE / 1000).toInt().coerceIn(s, pcm.size)
                    val bytes = ByteArray((e - s) * 2 + RATE)
                    for (k in s until e) { val v = pcm[k].toInt(); bytes[(k - s) * 2] = (v and 0xFF).toByte(); bytes[(k - s) * 2 + 1] = (v shr 8).toByte() }
                    out.write(bytes)
                }
            }
            runCatching { pipe[1].close() }
        }
        // safety timeout
        main.postDelayed({ if (!finished) { runCatching { r.stopListening() }; main.postDelayed({ finish(null) }, 3000) } }, (endMs - startMs) + 8000)
    }
}

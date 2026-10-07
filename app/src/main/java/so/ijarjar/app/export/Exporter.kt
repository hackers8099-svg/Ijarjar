package so.ijarjar.app.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.SingleColorLut
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import so.ijarjar.app.model.AudioTrack
import so.ijarjar.app.media.AudioFx
import so.ijarjar.app.model.Clip
import so.ijarjar.app.model.Layer
import so.ijarjar.app.model.LayerKind
import so.ijarjar.app.model.MediaKind
import so.ijarjar.app.model.Project
import so.ijarjar.app.render.EffectRenderer
import so.ijarjar.app.render.Filters
import so.ijarjar.app.render.LayerRenderer
import so.ijarjar.app.render.Motion
import so.ijarjar.app.render.Lut
import so.ijarjar.app.render.SpeedMap
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Renders the project to an MP4 with Media3 Transformer.
 * Main track clips -> one sequence (trim, speed, volume, filters, canvas transform, transitions,
 * effects). Layers -> drawn per frame with the same renderer used by the preview.
 * Each audio track -> its own audio-only sequence (with silence in front) mixed in.
 */
@UnstableApi
class Exporter(
    private val context: Context,
    private val project: Project,
    private val shortSide: Int,
    private val callback: Callback,
    /** true = QuickTime .mov file, false = .mp4 */
    private val mov: Boolean = false
) {
    interface Callback {
        fun onProgress(percent: Int)
        fun onDone(uri: Uri?, file: File)
        fun onError(message: String)
    }

    private var transformer: Transformer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val output = File(context.cacheDir, if (mov) "ijarjar_export.mov" else "ijarjar_export.mp4")
    private val frameSource = FrameSource()

    fun start() {
        val (w, h) = project.outputSize(shortSide)
        project.outputSize(1080).let { so.ijarjar.app.render.ExprEngine.compW = it.first.toDouble(); so.ijarjar.app.render.ExprEngine.compH = it.second.toDouble() }
        output.delete()

        val items = project.clips.mapIndexed { i, c -> buildItem(c, i, w, h) }
        val sequences = mutableListOf(EditedMediaItemSequence(items))
        val total = project.durationMs
        for (a in project.audios) {
            if (a.startMs >= total || a.durationMs <= 0) continue
            sequences.add(audioSequence(a, total))
        }
        // the sound of overlay videos (picture-in-picture)
        for (l in project.layers) {
            if (l.kind != LayerKind.VIDEO || l.uri == null || l.volume <= 0f || l.startMs >= total) continue
            val info = so.ijarjar.app.media.MediaUtils.probe(context, Uri.parse(l.uri)) ?: continue
            if (!info.hasAudio) continue
            val len = minOf(l.durationMs, (info.durationMs - l.trimStartMs).coerceAtLeast(100))
            sequences.add(audioSequence(AudioTrack(uri = l.uri!!, startMs = l.startMs, trimStartMs = l.trimStartMs, durationMs = len,
                sourceDurationMs = info.durationMs, volume = l.volume), total))
        }

        val composition = Composition.Builder(sequences)
            .experimentalSetForceAudioTrack(true)
            .build()

        val t = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    handler.removeCallbacksAndMessages(null)
                    frameSource.release()
                    val uri = saveToGallery()
                    callback.onDone(uri, output)
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    handler.removeCallbacksAndMessages(null)
                    frameSource.release()
                    callback.onError(exportException.errorCodeName + ": " + (exportException.cause?.message ?: exportException.message ?: ""))
                }
            })
            .build()
        transformer = t
        t.start(composition, output.absolutePath)
        pollProgress()
    }

    fun cancel() {
        handler.removeCallbacksAndMessages(null)
        transformer?.cancel()
        frameSource.release()
    }

    private fun pollProgress() {
        val holder = ProgressHolder()
        handler.postDelayed(object : Runnable {
            override fun run() {
                val tr = transformer ?: return
                if (tr.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) callback.onProgress(holder.progress)
                handler.postDelayed(this, 300)
            }
        }, 300)
    }

    private fun volumeProcessor(volume: Float): AudioProcessor {
        val p = ChannelMixingAudioProcessor()
        p.putChannelMixingMatrix(ChannelMixingMatrix.create(1, 1).scaleBy(volume))
        p.putChannelMixingMatrix(ChannelMixingMatrix.create(2, 2).scaleBy(volume))
        return p
    }

    // ------------------------------------------------------------------ audio

    private fun audioSequence(a: AudioTrack, total: Long): EditedMediaItemSequence {
        val list = ArrayList<EditedMediaItem>()
        if (a.startMs > 0) {
            val silence = silenceFile(a.startMs)
            list.add(EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(silence))).build())
        }
        val len = minOf(a.durationMs, total - a.startMs).coerceAtLeast(100)
        val item = MediaItem.Builder().setUri(Uri.parse(a.uri))
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(a.trimStartMs)
                    .setEndPositionMs(a.trimStartMs + len)
                    .build()
            ).build()
        val audio = mutableListOf<AudioProcessor>()
        if (a.volume != 1f) audio.add(volumeProcessor(a.volume))
        if (a.voice.pitch != 1f) audio.add(androidx.media3.common.audio.SonicAudioProcessor().apply { setPitch(a.voice.pitch) })
        if (a.denoise > 0f || a.enhanceVoice || a.voice.mode != 0 || a.sfx.on) { val d = a.denoise; val e = a.enhanceVoice; val v = a.voice; val r = a.sfx; audio.add(AudioFx({ Pair(d, e) }, { v }, { r })) }
        list.add(EditedMediaItem.Builder(item).setRemoveVideo(true).setEffects(Effects(audio, emptyList())).build())
        return EditedMediaItemSequence(list)
    }

    /** A stereo 44.1 kHz WAV file of silence, used to start an audio track later in the video. */
    private fun silenceFile(ms: Long): File {
        val f = File(context.cacheDir, "silence_$ms.wav")
        if (f.exists()) return f
        val rate = 44100; val ch = 2
        val frames = (rate * ms / 1000).toInt()
        val dataLen = frames * ch * 2
        FileOutputStream(f).use { out ->
            val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()); header.putInt(36 + dataLen); header.put("WAVE".toByteArray())
            header.put("fmt ".toByteArray()); header.putInt(16); header.putShort(1); header.putShort(ch.toShort())
            header.putInt(rate); header.putInt(rate * ch * 2); header.putShort((ch * 2).toShort()); header.putShort(16)
            header.put("data".toByteArray()); header.putInt(dataLen)
            out.write(header.array())
            val zeros = ByteArray(64 * 1024)
            var left = dataLen
            while (left > 0) { val n = minOf(left, zeros.size); out.write(zeros, 0, n); left -= n }
        }
        return f
    }

    // ------------------------------------------------------------------ video

    private fun buildItem(c: Clip, index: Int, w: Int, h: Int): EditedMediaItem {
        val clipStartMs = project.clipStartMs(index)
        val mb = MediaItem.Builder().setUri(Uri.parse(c.uri))
        if (c.kind == MediaKind.IMAGE) {
            mb.setImageDurationMs(c.trimmedMs)
        } else {
            mb.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(c.trimStartMs)
                    .setEndPositionMs(c.trimEndMs)
                    .build()
            )
        }
        val audio = mutableListOf<AudioProcessor>()
        val video = mutableListOf<Effect>()
        if (c.kind == MediaKind.VIDEO && (c.speed != 1f || c.hasCurve)) {
            val speed = c.speed
            val curve = c.hasCurve
            val pair = Effects.createExperimentalSpeedChangingEffect(object : SpeedProvider {
                override fun getSpeed(timeUs: Long): Float = if (curve) SpeedMap.speedAtSrc(c, timeUs / 1000) else speed
                override fun getNextSpeedChangeTimeUs(timeUs: Long): Long {
                    if (!curve) return C.TIME_UNSET
                    val n = SpeedMap.nextChangeUs(c, timeUs)
                    return if (n < 0) C.TIME_UNSET else n
                }
            })
            audio.add(pair.first)
            video.add(pair.second)
        }
        if (c.volume != 1f) audio.add(volumeProcessor(c.volume))
        if (c.voice.pitch != 1f) audio.add(androidx.media3.common.audio.SonicAudioProcessor().apply { setPitch(c.voice.pitch) })
        if (c.denoise > 0f || c.enhanceVoice || c.voice.mode != 0 || c.sfx.on) { val d = c.denoise; val e = c.enhanceVoice; val v = c.voice; val r = c.sfx; audio.add(AudioFx({ Pair(d, e) }, { v }, { r })) }
        video.addAll(Filters.exportEffects(c.adjust, c.width.coerceAtLeast(16)))
        if (c.adjust.lutUri != null || c.adjust.hasTone()) Filters.lutFor(context, c.adjust)?.let { lut -> video.add(SingleColorLut.createFromCube(lut.toCube(1f))) }
        if (project.layers.any { it.isEffect() && it.effect.group == 2 }) video.add(EffectColor(clipStartMs))
        video.add(Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT))
        video.add(ClipTransform(index, w.toFloat() / h))
        video.add(FadeColor(index))
        if (project.layers.isNotEmpty()) {
            video.add(OverlayEffect(ImmutableList.of<TextureOverlay>(CanvasOverlay(clipStartMs, w, h))))
        }
        val b = EditedMediaItem.Builder(mb.build()).setEffects(Effects(audio, video))
        if (c.kind == MediaKind.IMAGE) b.setDurationUs(c.trimmedMs * 1000).setFrameRate(30)
        return b.build()
    }

    /** Remembers the first frame time so an effect knows where it is inside its clip. */
    private class LocalClock {
        private var first = Long.MIN_VALUE
        fun localMs(ptsUs: Long): Long {
            if (first == Long.MIN_VALUE) first = ptsUs
            return ((ptsUs - first) / 1000).coerceAtLeast(0)
        }
    }

    /** Canvas transform of the clip + transition motion, in normalised device coordinates. */
    private inner class ClipTransform(private val index: Int, private val aspect: Float) : MatrixTransformation {
        private val clock = LocalClock()
        override fun getMatrix(presentationTimeUs: Long): Matrix {
            val m = Motion.clipMotion(project, index, clock.localMs(presentationTimeUs))
            return Matrix().apply {
                postScale(aspect, 1f)
                postScale(m.scale * m.sx * (if (m.mirror) -1f else 1f), m.scale)
                postRotate(-m.rotation)
                postScale(1f / aspect, 1f)
                postTranslate(2f * m.tx, -2f * m.ty)
            }
        }
    }

    /** Fade-to-colour part of transitions and flash effects. */
    private inner class FadeColor(private val index: Int) : RgbMatrix {
        private val clock = LocalClock()
        private val gl = FloatArray(16)
        override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
            val m = Motion.clipMotion(project, index, clock.localMs(presentationTimeUs))
            val a = m.fadeAlpha.coerceIn(0f, 1f)
            java.util.Arrays.fill(gl, 0f)
            gl[0] = 1 - a; gl[5] = 1 - a; gl[10] = 1 - a; gl[15] = 1f
            gl[12] = Color.red(m.fadeColor) / 255f * a
            gl[13] = Color.green(m.fadeColor) / 255f * a
            gl[14] = Color.blue(m.fadeColor) / 255f * a
            return gl
        }
    }

    /** Colour effects on the timeline (black & white, old film, rainbow…). */
    private inner class EffectColor(private val clipStartMs: Long) : RgbMatrix {
        private val clock = LocalClock()
        private val identity = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }
        override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
            val cm = EffectRenderer.colorMatrix(project, clipStartMs + clock.localMs(presentationTimeUs)) ?: return identity
            return Filters.toGl(cm)
        }
    }

    /** Draws every layer that is visible at the frame time into one full-frame bitmap. */
    private inner class CanvasOverlay(private val clipStartMs: Long, private val w: Int, private val h: Int) : BitmapOverlay() {
        private val clock = LocalClock()
        private var lastKey = ""
        private val settings = OverlaySettings.Builder().build()

        override fun getBitmap(presentationTimeUs: Long): Bitmap {
            val t = clipStartMs + clock.localMs(presentationTimeUs)
            val bmp = frameSource.canvasBitmap(w, h)
            val active = project.layers.filter { it.isActive(t) }
            // anything that moves needs a redraw every frame
            val animated = active.any {
                !(it.kind == LayerKind.TEXT || it.kind == LayerKind.STICKER || it.kind == LayerKind.IMAGE || it.kind == LayerKind.SHAPE || it.kind == LayerKind.DRAW) ||
                    it.keyframes.isNotEmpty() || it.motionBlur ||
                    it.animIn != so.ijarjar.app.model.LayerAnim.NONE || it.animOut != so.ijarjar.app.model.LayerAnim.NONE ||
                    it.animLoop != so.ijarjar.app.model.LoopAnim.NONE || it.expr != so.ijarjar.app.model.Expression.NONE ||
                    it.textIn != so.ijarjar.app.model.TextAnim.NONE || it.textOut != so.ijarjar.app.model.TextAnim.NONE ||
                    it.textLoop != so.ijarjar.app.model.TextLoop.NONE
            }
            val key = active.joinToString(",") { it.id }
            if (!animated && key == lastKey && frameSource.lastOwner === this) return bmp
            lastKey = key
            frameSource.lastOwner = this
            bmp.eraseColor(Color.TRANSPARENT)
            val canvas = Canvas(bmp)
            for (l in LayerRenderer.drawOrder(active)) {
                val content = if (l.kind == LayerKind.VIDEO) frameSource.videoFrame(l, t, w) else null
                if (l.kind == LayerKind.VIDEO && content == null) continue
                LayerRenderer.draw(context, canvas, l, t, w, h, content, 1920)
            }
            return bmp
        }

        override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = settings
    }

    /** Shared bitmaps + frame grabbers for overlay videos. */
    private inner class FrameSource {
        private var canvasBmp: Bitmap? = null
        var lastOwner: Any? = null
        private val retrievers = HashMap<String, MediaMetadataRetriever>()
        private val lastFrames = HashMap<String, Pair<Long, Bitmap>>()

        fun canvasBitmap(w: Int, h: Int): Bitmap {
            val b = canvasBmp
            if (b != null && b.width == w && b.height == h) return b
            return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { canvasBmp = it; lastOwner = null }
        }

        fun videoFrame(l: Layer, t: Long, canvasW: Int): Bitmap? {
            val local = l.trimStartMs + (t - l.startMs)
            val bucket = local / 33
            lastFrames[l.id]?.let { if (it.first == bucket) return it.second }
            val r = retrievers.getOrPut(l.id) {
                MediaMetadataRetriever().apply { setDataSource(context, Uri.parse(l.uri)) }
            }
            val (cw, ch) = LayerRenderer.contentSize(l, canvasW)
            val tw = (cw * l.scale).toInt().coerceIn(16, 1920)
            val th = (ch * l.scale).toInt().coerceIn(16, 1920)
            val frame = try {
                if (Build.VERSION.SDK_INT >= 27)
                    r.getScaledFrameAtTime(local * 1000, MediaMetadataRetriever.OPTION_CLOSEST, tw, th)
                else r.getFrameAtTime(local * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
            } catch (e: Exception) {
                null
            } ?: return lastFrames[l.id]?.second
            lastFrames[l.id] = Pair(bucket, frame)
            return frame
        }

        fun release() {
            retrievers.values.forEach { runCatching { it.release() } }
            retrievers.clear()
            lastFrames.clear()
        }
    }

    /** Copies the finished file into the phone gallery (Movies/IjarJar). */
    private fun saveToGallery(): Uri? {
        if (mov) markAsQuickTime(output)
        return saveMedia(context, output, "IjarJar_" + System.currentTimeMillis() + (if (mov) ".mov" else ".mp4"),
            if (mov) "video/quicktime" else "video/mp4", true)
    }

    /**
     * MP4 and MOV share the same structure; setting the file's brand to QuickTime ("qt  ")
     * makes it a proper .mov that QuickTime, Premiere and After Effects open as MOV.
     */
    private fun markAsQuickTime(f: File) {
        runCatching {
            RandomAccessFile(f, "rw").use { raf ->
                val head = ByteArray(8)
                raf.readFully(head)
                if (String(head, 4, 4, Charsets.US_ASCII) == "ftyp") {
                    raf.seek(8)
                    raf.write("qt  ".toByteArray(Charsets.US_ASCII))
                    raf.write(byteArrayOf(0x20, 0x05, 0x03, 0x00))
                }
            }
        }
    }

    companion object {
        /** Saves a file to Movies/IjarJar (video) or Pictures/IjarJar (image). */
        fun saveMedia(context: Context, file: File, name: String, mime: String, video: Boolean): Uri? {
            val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val dirName = if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
            return try {
                if (Build.VERSION.SDK_INT >= 29) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(MediaStore.MediaColumns.MIME_TYPE, mime)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "$dirName/IjarJar")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val resolver = context.contentResolver
                    val uri = resolver.insert(collection, values) ?: return null
                    resolver.openOutputStream(uri)?.use { out -> FileInputStream(file).use { it.copyTo(out) } }
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    uri
                } else {
                    @Suppress("DEPRECATION")
                    val dir = File(Environment.getExternalStoragePublicDirectory(dirName), "IjarJar")
                    dir.mkdirs()
                    val f = File(dir, name)
                    file.copyTo(f, overwrite = true)
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(MediaStore.MediaColumns.MIME_TYPE, mime)
                        @Suppress("DEPRECATION")
                        put(MediaStore.MediaColumns.DATA, f.absolutePath)
                    }
                    context.contentResolver.insert(collection, values)
                }
            } catch (e: Exception) {
                null
            }
        }

    }
}

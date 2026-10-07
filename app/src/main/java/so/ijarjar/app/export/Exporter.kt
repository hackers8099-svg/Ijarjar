package so.ijarjar.app.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
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
import so.ijarjar.app.model.Clip
import so.ijarjar.app.model.Layer
import so.ijarjar.app.model.LayerKind
import so.ijarjar.app.model.MediaKind
import so.ijarjar.app.model.Project
import so.ijarjar.app.render.Filters
import so.ijarjar.app.render.LayerRenderer
import java.io.File
import java.io.FileInputStream

/**
 * Renders the project to an MP4 with Media3 Transformer.
 * Main track clips -> one sequence (trim, speed, volume, filters, fit to canvas).
 * Layers (text, stickers, images, overlay videos) -> drawn per frame with the same
 * LayerRenderer used by the preview.
 * Music -> second, audio-only sequence mixed in.
 */
@UnstableApi
class Exporter(
    private val context: Context,
    private val project: Project,
    private val shortSide: Int,
    private val callback: Callback
) {
    interface Callback {
        fun onProgress(percent: Int)
        fun onDone(uri: Uri?, file: File)
        fun onError(message: String)
    }

    private var transformer: Transformer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val output = File(context.cacheDir, "ijarjar_export.mp4")
    private val frameSource = FrameSource()

    fun start() {
        val (w, h) = project.outputSize(shortSide)
        output.delete()

        val items = project.clips.mapIndexed { i, c -> buildItem(c, project.clipStartMs(i), w, h) }
        val sequences = mutableListOf(EditedMediaItemSequence(items))
        project.music?.let { m ->
            val end = m.trimStartMs + project.durationMs
            val clipping = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(m.trimStartMs)
            if (m.sourceDurationMs <= 0 || end < m.sourceDurationMs) clipping.setEndPositionMs(end)
            val item = MediaItem.Builder().setUri(Uri.parse(m.uri)).setClippingConfiguration(clipping.build()).build()
            val audio = mutableListOf<AudioProcessor>()
            if (m.volume != 1f) audio.add(volumeProcessor(m.volume))
            sequences.add(EditedMediaItemSequence(listOf(
                EditedMediaItem.Builder(item).setRemoveVideo(true).setEffects(Effects(audio, emptyList())).build()
            )))
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

    private fun buildItem(c: Clip, clipStartMs: Long, w: Int, h: Int): EditedMediaItem {
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
        if (c.kind == MediaKind.VIDEO && c.speed != 1f) {
            val speed = c.speed
            val pair = Effects.createExperimentalSpeedChangingEffect(object : SpeedProvider {
                override fun getSpeed(timeUs: Long): Float = speed
                override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
            })
            audio.add(pair.first)
            video.add(pair.second)
        }
        if (c.volume != 1f) audio.add(volumeProcessor(c.volume))
        video.addAll(Filters.exportEffects(c.adjust))
        video.add(Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT))
        if (project.layers.isNotEmpty()) {
            video.add(OverlayEffect(ImmutableList.of<TextureOverlay>(CanvasOverlay(clipStartMs, w, h))))
        }
        val b = EditedMediaItem.Builder(mb.build()).setEffects(Effects(audio, video))
        if (c.kind == MediaKind.IMAGE) {
            b.setDurationUs(c.trimmedMs * 1000).setFrameRate(30)
        }
        return b.build()
    }

    /** Draws every layer that is visible at the frame time into one full-frame bitmap. */
    private inner class CanvasOverlay(private val clipStartMs: Long, private val w: Int, private val h: Int) : BitmapOverlay() {
        private var firstTs = Long.MIN_VALUE
        private var lastKey = ""
        private val settings = OverlaySettings.Builder().build()

        override fun getBitmap(presentationTimeUs: Long): Bitmap {
            if (firstTs == Long.MIN_VALUE) firstTs = presentationTimeUs
            val t = clipStartMs + (presentationTimeUs - firstTs) / 1000
            val bmp = frameSource.canvasBitmap(w, h)
            val active = project.layers.filter { it.isActive(t) }
            val hasVideo = active.any { it.kind == LayerKind.VIDEO }
            val key = active.joinToString(",") { it.id }
            if (!hasVideo && key == lastKey && frameSource.lastOwner === this) return bmp
            lastKey = key
            frameSource.lastOwner = this
            bmp.eraseColor(Color.TRANSPARENT)
            val canvas = Canvas(bmp)
            for (l in LayerRenderer.drawOrder(active)) {
                val content = if (l.kind == LayerKind.VIDEO) frameSource.videoFrame(l, t, w) else null
                if (l.kind == LayerKind.VIDEO && content == null) continue
                LayerRenderer.draw(context, canvas, l, w, h, content)
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
        val name = "IjarJar_" + System.currentTimeMillis() + ".mp4"
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/IjarJar")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
                resolver.openOutputStream(uri)?.use { out -> FileInputStream(output).use { it.copyTo(out) } }
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                uri
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "IjarJar")
                dir.mkdirs()
                val f = File(dir, name)
                output.copyTo(f, overwrite = true)
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    @Suppress("DEPRECATION")
                    put(MediaStore.Video.Media.DATA, f.absolutePath)
                }
                context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            }
        } catch (e: Exception) {
            null
        }
    }
}

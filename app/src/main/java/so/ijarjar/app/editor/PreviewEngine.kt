package so.ijarjar.app.editor

import android.content.Context
import android.graphics.Paint
import android.net.Uri
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import so.ijarjar.app.media.MediaUtils
import so.ijarjar.app.model.Clip
import so.ijarjar.app.model.LayerKind
import so.ijarjar.app.model.MediaKind
import so.ijarjar.app.model.Project
import so.ijarjar.app.render.Filters
import kotlin.math.abs

/**
 * Plays the project: the main track in one ExoPlayer, music in another and one muted
 * ExoPlayer per overlay video layer, all following one global timeline.
 */
class PreviewEngine(private val context: Context, private val stage: StageView) {

    var project: Project = Project()
        private set

    private val main: ExoPlayer = ExoPlayer.Builder(context).build()
    private var music: ExoPlayer? = null
    private val overlayPlayers = HashMap<String, ExoPlayer>()
    private val overlaySignature = HashMap<String, String>()
    private var musicSignature: String? = null
    private var playlistSignature: String = ""

    var isPlaying = false
        private set
    var onEnded: (() -> Unit)? = null

    private var currentIndex = -1
    private var lastOverlaySeek = 0L

    init {
        main.setVideoTextureView(stage.mainTexture)
        main.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    val w = (videoSize.width * videoSize.pixelWidthHeightRatio).toInt()
                    stage.setVideoSize(w, videoSize.height)
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                applyClipState()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && isPlaying) {
                    pause()
                    onEnded?.invoke()
                }
            }
        })
    }

    private fun clipItem(c: Clip): MediaItem {
        val b = MediaItem.Builder().setUri(Uri.parse(c.uri)).setMediaId(c.id)
        if (c.kind == MediaKind.IMAGE) {
            b.setImageDurationMs(c.trimmedMs)
        } else {
            b.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(c.trimStartMs)
                    .setEndPositionMs(c.trimEndMs)
                    .build()
            )
        }
        return b.build()
    }

    /** Re-reads the project after an edit. Keeps the current time. */
    fun load(p: Project, timeMs: Long) {
        project = p
        stage.project = p
        val sig = p.clips.joinToString("|") { "${it.uri}:${it.trimStartMs}:${it.trimEndMs}:${it.kind}" }
        if (sig != playlistSignature) {
            playlistSignature = sig
            main.setMediaItems(p.clips.map { clipItem(it) })
            main.prepare()
        }
        syncOverlayPlayers()
        syncMusic()
        seekTo(timeMs.coerceIn(0, (p.durationMs - 1).coerceAtLeast(0)))
        applyClipState()
        stage.requestLayout()
    }

    private fun syncMusic() {
        val m = project.music
        val sig = m?.let { "${it.uri}" }
        if (sig == musicSignature) { music?.volume = m?.volume ?: 1f; return }
        musicSignature = sig
        music?.release(); music = null
        if (m != null) {
            music = ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(Uri.parse(m.uri)))
                volume = m.volume
                prepare()
            }
        }
    }

    private fun syncOverlayPlayers() {
        val videoLayers = project.layers.filter { it.kind == LayerKind.VIDEO && it.uri != null }
        val ids = videoLayers.map { it.id }.toSet()
        // remove old
        for (id in overlayPlayers.keys.toList()) {
            if (id !in ids) {
                overlayPlayers.remove(id)?.release()
                overlaySignature.remove(id)
                stage.videoLayerViews.remove(id)?.let { stage.videoLayerHost.removeView(it) }
            }
        }
        for (l in videoLayers) {
            val sig = l.uri!!
            if (overlaySignature[l.id] == sig) continue
            overlayPlayers.remove(l.id)?.release()
            val tv = stage.videoLayerViews[l.id] ?: TextureView(context).also {
                stage.videoLayerViews[l.id] = it
                stage.videoLayerHost.addView(it, FrameLayout.LayoutParams(10, 10))
            }
            val pl = ExoPlayer.Builder(context).build()
            pl.volume = 0f
            pl.setVideoTextureView(tv)
            pl.setMediaItem(MediaItem.fromUri(Uri.parse(l.uri)))
            pl.prepare()
            overlayPlayers[l.id] = pl
            overlaySignature[l.id] = sig
        }
        // keep z-order same as layer order
        for (l in videoLayers) stage.videoLayerViews[l.id]?.bringToFront()
    }

    /** Applies speed, volume and colour filter of the clip that is playing now. */
    private fun applyClipState() {
        val idx = main.currentMediaItemIndex
        val c = project.clips.getOrNull(idx) ?: return
        currentIndex = idx
        main.playbackParameters = PlaybackParameters(if (c.kind == MediaKind.VIDEO) c.speed else 1f)
        main.volume = c.volume.coerceIn(0f, 1f)
        val filter = Filters.colorFilter(c.adjust)
        if (filter != null) {
            stage.mainTexture.setLayerType(View.LAYER_TYPE_HARDWARE, Paint().apply { colorFilter = filter })
        } else {
            stage.mainTexture.setLayerType(View.LAYER_TYPE_NONE, null)
        }
        if (c.kind == MediaKind.IMAGE) {
            val bmp = MediaUtils.loadBitmapCached(context, Uri.parse(c.uri), 1600)
            stage.imageView.setImageBitmap(bmp)
            stage.imageView.colorFilter = filter
            stage.imageView.visibility = View.VISIBLE
        } else {
            stage.imageView.visibility = View.GONE
            stage.imageView.setImageDrawable(null)
        }
    }

    /** Global timeline position in output milliseconds. */
    fun currentTimeMs(): Long {
        val idx = main.currentMediaItemIndex
        val c = project.clips.getOrNull(idx) ?: return 0
        val pos = main.currentPosition.coerceAtLeast(0)
        val local = if (c.kind == MediaKind.VIDEO) (pos / c.speed).toLong() else pos
        return project.clipStartMs(idx) + local.coerceAtMost(c.outDurationMs)
    }

    fun seekTo(t: Long) {
        val p = project
        if (p.clips.isEmpty()) return
        var acc = 0L
        var idx = p.clips.size - 1
        var local = 0L
        for ((i, c) in p.clips.withIndex()) {
            if (t < acc + c.outDurationMs) { idx = i; local = t - acc; break }
            acc += c.outDurationMs
            if (i == p.clips.size - 1) { idx = i; local = c.outDurationMs - 1 }
        }
        val c = p.clips[idx]
        val pos = if (c.kind == MediaKind.VIDEO) (local * c.speed).toLong() else local
        main.seekTo(idx, pos.coerceAtLeast(0))
        if (idx != currentIndex) applyClipState()
        syncSecondary(t, force = true)
    }

    fun play() {
        if (project.clips.isEmpty()) return
        if (currentTimeMs() >= project.durationMs - 50) seekTo(0)
        isPlaying = true
        main.play()
        syncSecondary(currentTimeMs(), force = true)
    }

    fun pause() {
        isPlaying = false
        main.pause()
        music?.pause()
        overlayPlayers.values.forEach { it.pause() }
    }

    /** Called every frame while the editor is visible. */
    fun tick(): Long {
        val t = currentTimeMs()
        syncSecondary(t, force = false)
        return t
    }

    private fun syncSecondary(t: Long, force: Boolean) {
        // music
        val m = project.music
        val mp = music
        if (m != null && mp != null) {
            val expect = m.trimStartMs + t
            val dur = mp.duration
            if (dur != C.TIME_UNSET && expect >= dur) {
                mp.pause()
            } else if (isPlaying) {
                if (force || abs(mp.currentPosition - expect) > 300) mp.seekTo(expect)
                if (!mp.isPlaying) mp.play()
            } else {
                if (force) mp.seekTo(expect)
                mp.pause()
            }
        }
        // overlay videos
        val now = System.currentTimeMillis()
        for (l in project.layers) {
            if (l.kind != LayerKind.VIDEO) continue
            val pl = overlayPlayers[l.id] ?: continue
            if (!l.isActive(t)) {
                if (pl.isPlaying) pl.pause()
                continue
            }
            val expect = l.trimStartMs + (t - l.startMs)
            if (isPlaying) {
                if (force || abs(pl.currentPosition - expect) > 300) pl.seekTo(expect)
                if (!pl.isPlaying) pl.play()
            } else {
                if (pl.isPlaying) pl.pause()
                if (force || now - lastOverlaySeek > 120) {
                    if (abs(pl.currentPosition - expect) > 40) pl.seekTo(expect)
                }
            }
        }
        if (!isPlaying && now - lastOverlaySeek > 120) lastOverlaySeek = now
    }

    fun release() {
        main.release()
        music?.release()
        overlayPlayers.values.forEach { it.release() }
        overlayPlayers.clear()
    }
}

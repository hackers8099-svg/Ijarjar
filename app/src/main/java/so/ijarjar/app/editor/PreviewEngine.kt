package so.ijarjar.app.editor

import android.content.Context
import android.net.Uri
import android.view.TextureView
import android.widget.FrameLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.common.audio.AudioProcessor
import so.ijarjar.app.media.AudioFx
import so.ijarjar.app.model.Clip
import so.ijarjar.app.model.LayerKind
import so.ijarjar.app.model.MediaKind
import so.ijarjar.app.model.Project
import so.ijarjar.app.render.SpeedMap
import kotlin.math.abs

/**
 * Plays the project: the main track in one ExoPlayer, one ExoPlayer per audio track and one muted
 * ExoPlayer per overlay video layer, all following one global timeline. Pictures, transitions,
 * filters and effects are drawn by [StageView].
 */
class PreviewEngine(private val context: Context, private val stage: StageView) {

    var project: Project = Project()
        private set

    /** A player whose sound goes through the voice clean-up processor. */
    private fun playerWithFx(settings: () -> Pair<Float, Boolean>, voice: () -> so.ijarjar.app.model.VoiceFx,
                             room: () -> so.ijarjar.app.model.SoundFx? = { null }): ExoPlayer {
        val rf = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context).setAudioProcessors(arrayOf<AudioProcessor>(AudioFx(settings, voice, room))).build()
        }
        return ExoPlayer.Builder(context, rf).build()
    }

    private val main: ExoPlayer = playerWithFx({
        val c = project.clips.getOrNull(currentIndexSafe)
        if (c == null) Pair(0f, false) else Pair(c.denoise, c.enhanceVoice)
    }, { project.clips.getOrNull(currentIndexSafe)?.voice ?: so.ijarjar.app.model.VoiceFx.NONE },
        { project.clips.getOrNull(currentIndexSafe)?.sfx })
    @Volatile private var currentIndexSafe = 0
    private val audioPlayers = HashMap<String, ExoPlayer>()
    private val audioSignature = HashMap<String, String>()
    private val overlayPlayers = HashMap<String, ExoPlayer>()
    private val overlaySignature = HashMap<String, String>()
    private var playlistSignature: String = ""

    var isPlaying = false
        private set
    var onEnded: (() -> Unit)? = null
    var muteMain = false

    private var currentIndex = -1
    private var lastOverlaySeek = 0L

    init {
        main.setVideoTextureView(stage.mainTexture)
        main.addListener(object : Player.Listener {
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
        syncAudioPlayers()
        seekTo(timeMs.coerceIn(0, (p.durationMs - 1).coerceAtLeast(0)))
        applyClipState()
        stage.requestLayout()
    }

    private fun syncAudioPlayers() {
        val ids = project.audios.map { it.id }.toSet()
        for (id in audioPlayers.keys.toList()) if (id !in ids) { audioPlayers.remove(id)?.release(); audioSignature.remove(id) }
        for (a in project.audios) {
            val sig = a.uri
            val existing = audioPlayers[a.id]
            if (existing != null && audioSignature[a.id] == sig) {
                existing.volume = a.volume.coerceIn(0f, 1f)
                if (existing.playbackParameters.pitch != a.voice.pitch) existing.playbackParameters = PlaybackParameters(1f, a.voice.pitch)
                continue
            }
            existing?.release()
            val id = a.id
            val pl = playerWithFx({
                val t = project.audios.firstOrNull { it.id == id }
                if (t == null) Pair(0f, false) else Pair(t.denoise, t.enhanceVoice)
            }, { project.audios.firstOrNull { it.id == id }?.voice ?: so.ijarjar.app.model.VoiceFx.NONE },
                { project.audios.firstOrNull { it.id == id }?.sfx })
            pl.setMediaItem(MediaItem.fromUri(Uri.parse(a.uri)))
            pl.volume = a.volume.coerceIn(0f, 1f)
            pl.playbackParameters = PlaybackParameters(1f, a.voice.pitch)
            pl.prepare()
            audioPlayers[a.id] = pl
            audioSignature[a.id] = sig
        }
    }

    private fun syncOverlayPlayers() {
        val videoLayers = project.layers.filter { it.videoSource() != null }
        val ids = videoLayers.map { it.id }.toSet()
        for (id in overlayPlayers.keys.toList()) {
            if (id !in ids) {
                overlayPlayers.remove(id)?.release()
                overlaySignature.remove(id)
                stage.videoLayerViews.remove(id)?.let { stage.videoLayerHost.removeView(it) }
            }
        }
        for (l in videoLayers) {
            val sig = l.videoSource()!!
            if (overlaySignature[l.id] == sig) { overlayPlayers[l.id]?.volume = l.volume.coerceIn(0f, 1f); continue }
            overlayPlayers.remove(l.id)?.release()
            val tv = stage.videoLayerViews[l.id] ?: TextureView(context).also {
                stage.videoLayerViews[l.id] = it
                stage.videoLayerHost.addView(it, FrameLayout.LayoutParams(10, 10))
            }
            val lid = l.id
            val pl = playerWithFx({ Pair(0f, false) }, { project.layers.firstOrNull { it.id == lid }?.voice ?: so.ijarjar.app.model.VoiceFx.NONE },
                { project.layers.firstOrNull { it.id == lid }?.sfx })
            pl.volume = l.volume.coerceIn(0f, 1f)
            pl.playbackParameters = PlaybackParameters(1f, l.voice.pitch)
            pl.setVideoTextureView(tv)
            pl.setMediaItem(MediaItem.fromUri(Uri.parse(sig)))
            if (l.kind == LayerKind.MODEL3D) pl.repeatMode = Player.REPEAT_MODE_ONE
            pl.prepare()
            overlayPlayers[l.id] = pl
            overlaySignature[l.id] = sig
        }
        for (l in videoLayers) stage.videoLayerViews[l.id]?.bringToFront()
    }

    /** Applies speed and volume of the clip that is playing now. */
    private fun applyClipState() {
        val idx = main.currentMediaItemIndex
        val c = project.clips.getOrNull(idx) ?: return
        currentIndex = idx
        currentIndexSafe = idx
        lastSpeed = if (c.kind == MediaKind.VIDEO) SpeedMap.speedAtSrc(c, main.currentPosition.coerceAtLeast(0)) else 1f
        main.playbackParameters = PlaybackParameters(lastSpeed, c.voice.pitch)
        main.volume = if (muteMain) 0f else c.volume.coerceIn(0f, 1f)
    }

    fun refreshVolumes() {
        applyClipState()
        for (l in project.layers) if (l.kind == LayerKind.VIDEO) overlayPlayers[l.id]?.let {
            it.volume = l.volume.coerceIn(0f, 1f)
            if (it.playbackParameters.pitch != l.voice.pitch) it.playbackParameters = PlaybackParameters(1f, l.voice.pitch)
        }
        for (a in project.audios) audioPlayers[a.id]?.let {
            it.volume = a.volume.coerceIn(0f, 1f)
            if (it.playbackParameters.pitch != a.voice.pitch) it.playbackParameters = PlaybackParameters(1f, a.voice.pitch)
        }
    }

    /** Global timeline position in output milliseconds. */
    fun currentTimeMs(): Long {
        val idx = main.currentMediaItemIndex
        val c = project.clips.getOrNull(idx) ?: return 0
        val pos = main.currentPosition.coerceAtLeast(0)
        val local = if (c.kind == MediaKind.VIDEO) SpeedMap.srcToOut(c, pos) else pos
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
        val pos = if (c.kind == MediaKind.VIDEO) SpeedMap.outToSrc(c, local) else local
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
        audioPlayers.values.forEach { it.pause() }
        overlayPlayers.values.forEach { it.pause() }
    }

    /** Called every frame while the editor is visible. */
    private var lastSpeed = 1f

    fun tick(): Long {
        // speed curves: follow the curve while playing
        val c = project.clips.getOrNull(main.currentMediaItemIndex)
        if (c != null && c.hasCurve) {
            val sp = SpeedMap.speedAtSrc(c, main.currentPosition.coerceAtLeast(0))
            if (kotlin.math.abs(sp - lastSpeed) > 0.01f) { lastSpeed = sp; main.playbackParameters = PlaybackParameters(sp, c.voice.pitch) }
        }
        val t = currentTimeMs()
        syncSecondary(t, force = false)
        return t
    }

    private fun syncSecondary(t: Long, force: Boolean) {
        for (a in project.audios) {
            val pl = audioPlayers[a.id] ?: continue
            if (!a.isActive(t)) { if (pl.isPlaying) pl.pause(); continue }
            val expect = a.trimStartMs + (t - a.startMs)
            if (isPlaying) {
                if (force || abs(pl.currentPosition - expect) > 300) pl.seekTo(expect)
                if (!pl.isPlaying) pl.play()
            } else {
                if (pl.isPlaying) pl.pause()
                if (force) pl.seekTo(expect)
            }
        }
        val now = System.currentTimeMillis()
        for (l in project.layers) {
            if (l.videoSource() == null) continue
            val pl = overlayPlayers[l.id] ?: continue
            if (!l.isActive(t)) { if (pl.isPlaying) pl.pause(); continue }
            val raw = l.trimStartMs + (t - l.startMs)
            // a video on a 3D model loops
            val expect = if (l.kind == LayerKind.MODEL3D && pl.duration > 0) raw % pl.duration else raw
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
        audioPlayers.values.forEach { it.release() }
        overlayPlayers.values.forEach { it.release() }
        audioPlayers.clear(); overlayPlayers.clear()
    }
}

package so.ijarjar.app.editor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.media3.common.util.UnstableApi
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.RangeSlider
import so.ijarjar.app.L
import so.ijarjar.app.R
import so.ijarjar.app.data.History
import so.ijarjar.app.data.ProjectStore
import so.ijarjar.app.export.Exporter
import so.ijarjar.app.export.PhotoExporter
import so.ijarjar.app.media.MediaUtils
import so.ijarjar.app.model.AudioKind
import so.ijarjar.app.model.AudioTrack
import so.ijarjar.app.model.Clip
import so.ijarjar.app.model.EffectKind
import so.ijarjar.app.model.FilterPreset
import so.ijarjar.app.model.Adjust
import so.ijarjar.app.model.Layer
import so.ijarjar.app.model.LayerAnim
import so.ijarjar.app.model.LayerKind
import so.ijarjar.app.model.LoopAnim
import so.ijarjar.app.model.MaskKind
import so.ijarjar.app.model.MediaKind
import so.ijarjar.app.model.Project
import so.ijarjar.app.model.ShapeKind
import so.ijarjar.app.model.TransitionKind
import so.ijarjar.app.model.newId
import so.ijarjar.app.render.LayerRenderer
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

@UnstableApi
class EditorActivity : AppCompatActivity(), StageView.Listener, TimelineView.Listener {

    companion object {
        const val PHOTO_END = 3_600_000L
    }

    private fun tr(so: String, en: String) = L.t(so, en)

    private lateinit var project: Project
    private val history = History()
    private lateinit var stage: StageView
    private lateinit var engine: PreviewEngine
    private lateinit var timeline: TimelineView
    private lateinit var timeLabel: TextView
    private lateinit var playBtn: ImageView
    private lateinit var keyBtn: ImageView
    private lateinit var fullBtn: ImageView
    private lateinit var playRow: View
    private lateinit var tlBox: View
    private lateinit var toolRow: LinearLayout
    private lateinit var toolScroll: HorizontalScrollView
    private lateinit var undoBtn: View
    private lateinit var redoBtn: View
    private lateinit var aspectBtn: TextView

    private var timeMs = 0L
    private var running = false
    private var fullscreen = false
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var insertAfter = -1
    private var replaceIndex = -1
    private var addAudioKind = AudioKind.MUSIC
    private var recorder: MediaRecorder? = null

    private val photo get() = project.isPhoto

    private fun dp(v: Float) = Ui.dp(this, v)

    // ------------------------------------------------------------------ pickers

    private val pickClips = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) addClips(uris)
    }
    private val pickOverlay = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) addMediaLayer(uri)
    }
    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) addAudio(uri, addAudioKind)
    }
    private val pickReplace = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) replaceClip(uri)
    }
    private val pickBackground = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) setBackgroundImage(uri)
    }
    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) showVoiceover() else toast(tr("Ogolaanshaha makarafoonka waa loo baahan yahay", "Microphone permission is needed"))
    }

    private fun keep(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        L.init(this)
        val id = intent.getStringExtra("id")
        project = (id?.let { ProjectStore.load(this, it) }) ?: Project()
        buildUi()
        engine = PreviewEngine(this, stage)
        engine.onEnded = { updatePlayButton() }
        history.push(ProjectStore.toJson(project))
        reload()
        if (!photo && project.clips.isEmpty()) main.postDelayed({ pickClips.launch(arrayOf("video/*", "image/*")) }, 300)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (fullscreen) { toggleFullscreen(); return }
                save(); finish()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        running = true
        Choreographer.getInstance().postFrameCallback(frame)
    }

    override fun onPause() {
        super.onPause()
        running = false
        stopRecording(cancel = true)
        engine.pause()
        updatePlayButton()
        save()
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.release()
        io.shutdown()
    }

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            if (engine.isPlaying) {
                timeMs = engine.tick()
                timeline.timeMs = timeMs
            }
            stage.timeMs = if (photo) 0 else timeMs
            stage.refresh()
            if (!photo) {
                timeLabel.text = TimelineView.fmt(timeMs) + " / " + TimelineView.fmt(project.durationMs)
                updateKeyButton()
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    // ------------------------------------------------------------------ UI

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4f), dp(4f), dp(8f), dp(4f))
        }
        top.addView(Ui.iconButton(this, R.drawable.ic_close) { save(); finish() })
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        undoBtn = Ui.iconButton(this, R.drawable.ic_undo) { undo() }
        redoBtn = Ui.iconButton(this, R.drawable.ic_redo) { redo() }
        top.addView(undoBtn); top.addView(redoBtn)
        aspectBtn = Ui.text(this, project.aspect, 13f).apply {
            background = Ui.roundBg(Ui.SURFACE2, dp(14f).toFloat())
            setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
            setOnClickListener { showAspect() }
        }
        top.addView(aspectBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6f); marginEnd = dp(8f) })
        top.addView(Ui.button(this, tr("Dhoofi", "Export")) { if (photo) showPhotoExport() else showExport() }.apply {
            setIconResource(R.drawable.ic_export)
            iconTint = android.content.res.ColorStateList.valueOf(0xFF00201E.toInt())
        })
        root.addView(top)

        val stageBox = FrameLayout(this).apply { setBackgroundColor(Ui.BG) }
        stage = StageView(this)
        stage.project = project
        stage.listener = this
        stageBox.addView(stage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        stageBox.setPadding(dp(12f), dp(4f), dp(12f), dp(4f))
        root.addView(stageBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val pr = FrameLayout(this).apply { setPadding(dp(14f), 0, dp(6f), 0) }
        timeLabel = Ui.text(this, "00:00 / 00:00", 12f, Ui.TEXT2)
        pr.addView(timeLabel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL))
        playBtn = Ui.iconButton(this, R.drawable.ic_play, 28f) { togglePlay() }
        pr.addView(playBtn, FrameLayout.LayoutParams(dp(48f), dp(44f), Gravity.CENTER))
        val right = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        keyBtn = Ui.iconButton(this, R.drawable.ic_keyframe, 22f) { toggleKeyframe() }
        fullBtn = Ui.iconButton(this, R.drawable.ic_fullscreen, 22f) { toggleFullscreen() }
        right.addView(keyBtn); right.addView(fullBtn)
        pr.addView(right, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL))
        playRow = pr
        root.addView(pr)

        val tb = FrameLayout(this)
        timeline = TimelineView(this)
        timeline.listener = this
        tb.addView(timeline, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val add = ImageView(this).apply {
            setImageResource(R.drawable.ic_add)
            imageTintList = android.content.res.ColorStateList.valueOf(0xFF00201E.toInt())
            background = Ui.roundBg(Ui.TEXT, dp(8f).toFloat())
            setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
            setOnClickListener {
                insertAfter = -1
                pickClips.launch(arrayOf("video/*", "image/*"))
            }
        }
        tb.addView(add, FrameLayout.LayoutParams(dp(40f), dp(40f), Gravity.END or Gravity.TOP).apply { topMargin = dp(35f); marginEnd = dp(8f) })
        tlBox = tb
        root.addView(tb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(230f)))

        toolScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(Ui.SURFACE)
        }
        toolRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(4f), dp(4f), dp(4f), dp(8f)) }
        toolScroll.addView(toolRow)
        root.addView(toolScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        if (project.isPhoto) {
            playRow.visibility = View.GONE
            tlBox.visibility = View.GONE
        }
        setContentView(root)
    }

    private fun updatePlayButton() {
        playBtn.setImageResource(if (engine.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun updateKeyButton() {
        val l = selectedLayer()
        if (l == null || l.isEffect()) { keyBtn.visibility = View.INVISIBLE; return }
        keyBtn.visibility = View.VISIBLE
        val on = LayerRenderer.keyframeAt(l, timeMs) != null
        keyBtn.setImageResource(if (on) R.drawable.ic_keyframe_on else R.drawable.ic_keyframe)
        keyBtn.imageTintList = android.content.res.ColorStateList.valueOf(if (on || l.keyframes.isNotEmpty()) 0xFFFFCC00.toInt() else Ui.TEXT)
    }

    private fun togglePlay() {
        if (engine.isPlaying) engine.pause() else { engine.seekTo(timeMs); engine.play() }
        updatePlayButton()
    }

    private fun toggleFullscreen() {
        fullscreen = !fullscreen
        tlBox.visibility = if (fullscreen) View.GONE else View.VISIBLE
        toolScroll.visibility = if (fullscreen) View.GONE else View.VISIBLE
        fullBtn.setImageResource(if (fullscreen) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen)
        stage.requestLayout()
    }

    private fun updateUndo() {
        undoBtn.alpha = if (history.canUndo()) 1f else 0.35f
        redoBtn.alpha = if (history.canRedo()) 1f else 0.35f
    }

    // ------------------------------------------------------------------ selection & tools

    private var selection: TimelineView.Sel? = null

    private fun setSelection(s: TimelineView.Sel?) {
        selection = s
        timeline.selection = s
        stage.selectedLayerId = (s as? TimelineView.Sel.LayerSel)?.id
        stage.canvasTarget = canvasTargetFor(s)
        buildTools()
    }

    /** What the fingers move when no layer is touched: the selected clip, or the photo background. */
    private fun canvasTargetFor(s: TimelineView.Sel?): StageView.CanvasTarget? {
        if (photo) {
            if (project.bgImageUri == null || s != null) return null
            return StageView.CanvasTarget(
                get = { floatArrayOf(project.bgX, project.bgY, project.bgScale, project.bgRot) },
                set = { v -> project.bgX = v[0]; project.bgY = v[1]; project.bgScale = v[2]; project.bgRot = v[3] })
        }
        val idx = (s as? TimelineView.Sel.ClipSel)?.index ?: return null
        val c = project.clips.getOrNull(idx) ?: return null
        return StageView.CanvasTarget(
            get = { floatArrayOf(c.tX, c.tY, c.tScale, c.tRot) },
            set = { v -> c.tX = v[0]; c.tY = v[1]; c.tScale = v[2]; c.tRot = v[3] })
    }

    private fun selectedClipIndex(): Int = (selection as? TimelineView.Sel.ClipSel)?.index ?: -1
    private fun selectedLayer(): Layer? = (selection as? TimelineView.Sel.LayerSel)?.let { s -> project.layers.firstOrNull { it.id == s.id } }
    private fun selectedAudio(): AudioTrack? = (selection as? TimelineView.Sel.AudioSel)?.let { s -> project.audios.firstOrNull { it.id == s.id } }

    private fun buildTools() {
        toolRow.removeAllViews()
        fun t(icon: Int, label: String, active: Boolean = false, f: () -> Unit) = toolRow.addView(Ui.tool(this, icon, label, active, f))
        val s = selection
        val back = { t(R.drawable.ic_back, tr("Dib", "Back")) { setSelection(null) } }
        when {
            s is TimelineView.Sel.ClipSel && project.clips.getOrNull(s.index) != null -> {
                val c = project.clips[s.index]
                back()
                t(R.drawable.ic_split, tr("Kala jar", "Split")) { splitClip(s.index) }
                if (c.kind == MediaKind.VIDEO) t(R.drawable.ic_speed, tr("Xawaare", "Speed")) { showSpeed(c) }
                t(R.drawable.ic_volume, tr("Cod", "Volume")) { showVolume(c) }
                t(R.drawable.ic_filter, tr("Filter", "Filters")) { showAdjust(c.adjust) { for (o in project.clips) o.adjust = c.adjust.copy() } }
                t(R.drawable.ic_trim, tr("Gooy", "Trim")) { showTrim(c) }
                t(R.drawable.ic_canvas, tr("Shaashad", "Canvas")) { showCanvas(c) }
                if (s.index > 0) t(R.drawable.ic_transition, tr("Isbeddel", "Transition")) { showTransition(s.index) }
                if (c.kind == MediaKind.VIDEO) {
                    t(R.drawable.ic_freeze, tr("Qaboojí", "Freeze")) { freezeFrame(s.index) }
                    t(R.drawable.ic_waveform, tr("Codka soo saar", "Extract audio")) { extractAudio(s.index) }
                }
                t(R.drawable.ic_replace, tr("Beddel", "Replace")) { replaceIndex = s.index; pickReplace.launch(arrayOf("video/*", "image/*")) }
                t(R.drawable.ic_copy, tr("Nuqul", "Duplicate")) { duplicateClip(s.index) }
                t(R.drawable.ic_left, tr("Bidix", "Move left")) { moveClip(s.index, -1) }
                t(R.drawable.ic_right, tr("Midig", "Move right")) { moveClip(s.index, 1) }
                t(R.drawable.ic_add, tr("Ku dar", "Add")) { insertAfter = s.index; pickClips.launch(arrayOf("video/*", "image/*")) }
                t(R.drawable.ic_delete, tr("Tirtir", "Delete")) { deleteClip(s.index) }
            }
            s is TimelineView.Sel.LayerSel && selectedLayer() != null -> {
                val l = selectedLayer()!!
                back()
                if (l.isEffect()) {
                    t(R.drawable.ic_effects, tr("Beddel", "Change")) { showEffects(l) }
                    t(R.drawable.ic_opacity, tr("Xoog", "Strength")) { showOpacity(l, tr("Xoogga saameynta", "Effect strength")) }
                } else {
                    t(R.drawable.ic_link, tr("Isku xir", "Link"), l.linkGroup != null) { showLink(l) }
                    if (l.linkGroup != null) t(R.drawable.ic_unlink, tr("Kala fur", "Unlink")) { unlink(l) }
                    if (l.isTextLike()) t(R.drawable.ic_pencil, tr("Wax ka beddel", "Edit")) { showTextEditor(l) }
                    if (l.kind == LayerKind.SHAPE) t(R.drawable.ic_pencil, tr("Qaabka", "Style")) { showShapeEditor(l) }
                    if (!photo) t(R.drawable.ic_animation, tr("Dhaqdhaqaaq", "Animation")) { showAnimation(l) }
                    if (l.kind == LayerKind.IMAGE || l.kind == LayerKind.VIDEO) t(R.drawable.ic_mask, tr("Maaskaro", "Mask"), l.mask != MaskKind.NONE) { showMask(l) }
                    if (l.kind == LayerKind.IMAGE) t(R.drawable.ic_replace, tr("Beddel", "Replace")) { replaceLayerImage(l) }
                    if (!photo) t(R.drawable.ic_keyframe, "Keyframe", l.keyframes.isNotEmpty()) { toggleKeyframe() }
                    t(R.drawable.ic_opacity, tr("Daahsoon", "Opacity")) { showOpacity(l, tr("Daahsoonaan", "Opacity")) }
                    t(R.drawable.ic_flip, tr("Rog", "Flip")) { for (g in project.linkedWith(l)) g.flipH = !g.flipH; commit() }
                }
                if (!photo) {
                    t(R.drawable.ic_split, tr("Kala jar", "Split")) { splitLayer(l) }
                    t(R.drawable.ic_start_here, tr("Bilow halkan", "Start here")) { moveLayerStart(l) }
                    t(R.drawable.ic_end_here, tr("Dhamee halkan", "End here")) { if (timeMs > l.startMs) { l.endMs = timeMs; commit() } }
                }
                if (!l.isEffect()) {
                    t(R.drawable.ic_bring_forward, tr("Kor", "Forward")) { reorderLayer(l, 1) }
                    t(R.drawable.ic_send_backward, tr("Hoos", "Backward")) { reorderLayer(l, -1) }
                    t(R.drawable.ic_reset, tr("Dib u celi", "Reset")) { l.scale = 1f; l.rotation = 0f; l.cx = 0.5f; l.cy = 0.5f; l.keyframes.clear(); commit() }
                }
                t(R.drawable.ic_copy, tr("Nuqul", "Duplicate")) { duplicateLayer(l) }
                t(R.drawable.ic_delete, tr("Tirtir", "Delete")) { deleteLayer(l) }
            }
            s is TimelineView.Sel.AudioSel && selectedAudio() != null -> {
                val a = selectedAudio()!!
                back()
                t(R.drawable.ic_volume, tr("Cod", "Volume")) { showAudioVolume(a) }
                t(R.drawable.ic_split, tr("Kala jar", "Split")) { splitAudio(a) }
                t(R.drawable.ic_start_here, tr("Bilow halkan", "Start here")) { a.startMs = timeMs; commit() }
                t(R.drawable.ic_copy, tr("Nuqul", "Duplicate")) { val b = a.copy(); b.startMs = a.endMs; project.audios.add(b); commit() }
                t(R.drawable.ic_delete, tr("Tirtir", "Delete")) { project.audios.remove(a); setSelection(null); commit() }
            }
            photo -> {
                t(R.drawable.ic_background, tr("Gadaal", "Background")) { showBackground() }
                t(R.drawable.ic_text, tr("Qoraal", "Text")) { addText() }
                t(R.drawable.ic_sticker, "Sticker") { showStickers() }
                t(R.drawable.ic_image_add, tr("Sawir", "Image")) { pickOverlay.launch(arrayOf("image/*")) }
                t(R.drawable.ic_shape, tr("Qaabab", "Shapes")) { showShapes() }
                t(R.drawable.ic_link, tr("Isku xir", "Link")) { showLink(null) }
                t(R.drawable.ic_layers, tr("Layer-ada", "Layers")) { showLayers() }
                t(R.drawable.ic_ratio, tr("Cabbir", "Size")) { showAspect() }
            }
            else -> {
                t(R.drawable.ic_edit, tr("Wax ka beddel", "Edit")) {
                    if (project.clips.isNotEmpty()) setSelection(TimelineView.Sel.ClipSel(project.clipIndexAt(timeMs)))
                }
                t(R.drawable.ic_audio, tr("Cod", "Audio")) { showAudioMenu() }
                t(R.drawable.ic_text, tr("Qoraal", "Text")) { addText() }
                t(R.drawable.ic_caption, tr("Qoraal-hoosaad", "Captions")) { showCaptions() }
                t(R.drawable.ic_sticker, "Sticker") { showStickers() }
                t(R.drawable.ic_overlay, "Overlay") { pickOverlay.launch(arrayOf("video/*", "image/*")) }
                t(R.drawable.ic_effects, tr("Saameyn", "Effects")) { showEffects(null) }
                t(R.drawable.ic_shape, tr("Qaabab", "Shapes")) { showShapes() }
                t(R.drawable.ic_filter, tr("Filter", "Filters")) {
                    if (project.clips.isNotEmpty()) {
                        val c = project.clips[project.clipIndexAt(timeMs)]
                        showAdjust(c.adjust) { for (o in project.clips) o.adjust = c.adjust.copy() }
                    }
                }
                t(R.drawable.ic_link, tr("Isku xir", "Link")) { showLink(null) }
                t(R.drawable.ic_layers, tr("Layer-ada", "Layers")) { showLayers() }
                t(R.drawable.ic_ratio, tr("Saami", "Ratio")) { showAspect() }
            }
        }
        toolScroll.scrollTo(0, 0)
    }

    // ------------------------------------------------------------------ commit / undo

    private fun reload() {
        stage.project = project
        timeline.project = project
        timeMs = if (photo) 0 else timeMs.coerceIn(0, (project.durationMs - 1).coerceAtLeast(0))
        timeline.timeMs = timeMs
        engine.load(project, timeMs)
        aspectBtn.text = project.aspect
        if (selection is TimelineView.Sel.ClipSel && selectedClipIndex() >= project.clips.size) selection = null
        if (selection is TimelineView.Sel.LayerSel && selectedLayer() == null) selection = null
        if (selection is TimelineView.Sel.AudioSel && selectedAudio() == null) selection = null
        setSelection(selection)
        updateUndo()
        updatePlayButton()
        stage.requestLayout()
    }

    private fun commit() {
        history.push(ProjectStore.toJson(project))
        reload()
        scheduleSave()
    }

    private val saveRunnable = Runnable { save() }
    private fun scheduleSave() { main.removeCallbacks(saveRunnable); main.postDelayed(saveRunnable, 1500) }

    private fun save() {
        val copy = ProjectStore.fromJson(ProjectStore.toJson(project))
        io.execute { ProjectStore.save(applicationContext, copy) }
    }

    private fun undo() {
        val s = history.undo() ?: return
        project = ProjectStore.fromJson(s); reload(); scheduleSave()
    }

    private fun redo() {
        val s = history.redo() ?: return
        project = ProjectStore.fromJson(s); reload(); scheduleSave()
    }

    /** Re-apply the project to the players without adding an undo step (live previews). */
    private fun live() {
        engine.refreshVolumes()
        stage.refresh()
        timeline.invalidate()
    }

    // ------------------------------------------------------------------ listeners

    override fun onLayerSelected(layer: Layer?) {
        setSelection(layer?.let { TimelineView.Sel.LayerSel(it.id) })
    }

    override fun onLayerTransforming() {}

    override fun onLayerTransformed() { commit() }

    override fun onScrub(timeMs: Long) {
        if (engine.isPlaying) { engine.pause(); updatePlayButton() }
        this.timeMs = timeMs
        engine.seekTo(timeMs)
    }

    override fun onSelect(sel: TimelineView.Sel?) {
        setSelection(sel)
        if (sel is TimelineView.Sel.LayerSel) {
            val l = selectedLayer() ?: return
            if (!l.isActive(timeMs)) { timeMs = l.startMs; timeline.timeMs = timeMs; engine.seekTo(timeMs) }
        }
    }

    override fun onTransitionTap(clipIndex: Int) { showTransition(clipIndex) }

    override fun onTimelineEditing() { stage.refresh() }

    override fun onTimelineEdited() { commit() }

    // ------------------------------------------------------------------ clips

    private fun addClips(uris: List<Uri>) {
        toast(tr("Waa la soo gelinayaa…", "Importing…"))
        io.execute {
            val clips = uris.mapNotNull { uri ->
                keep(uri)
                val info = MediaUtils.probe(this, uri) ?: return@mapNotNull null
                if (info.isVideo) Clip(uri = uri.toString(), kind = MediaKind.VIDEO, sourceDurationMs = info.durationMs,
                    trimStartMs = 0, trimEndMs = info.durationMs, width = info.width, height = info.height)
                else Clip(uri = uri.toString(), kind = MediaKind.IMAGE, sourceDurationMs = 3000, trimStartMs = 0,
                    trimEndMs = 3000, width = info.width, height = info.height)
            }
            main.post {
                if (clips.isEmpty()) { toast(tr("Faylka lama furi karo", "Could not open the file")); return@post }
                val firstProject = project.clips.isEmpty()
                val at = if (insertAfter in project.clips.indices) insertAfter + 1 else project.clips.size
                project.clips.addAll(at, clips)
                if (firstProject) {
                    val c = clips[0]
                    project.aspect = when {
                        c.width > c.height * 1.2f -> "16:9"
                        c.height > c.width * 1.2f -> "9:16"
                        else -> "1:1"
                    }
                }
                insertAfter = -1
                commit()
            }
        }
    }

    private fun replaceClip(uri: Uri) {
        val i = replaceIndex
        val c = project.clips.getOrNull(i) ?: return
        keep(uri)
        io.execute {
            val info = MediaUtils.probe(this, uri)
            main.post {
                if (info == null) { toast(tr("Faylka lama furi karo", "Could not open the file")); return@post }
                val len = c.trimmedMs
                c.uri = uri.toString(); c.width = info.width; c.height = info.height
                if (info.isVideo) {
                    c.kind = MediaKind.VIDEO; c.sourceDurationMs = info.durationMs
                    c.trimStartMs = 0; c.trimEndMs = minOf(info.durationMs, len)
                } else {
                    c.kind = MediaKind.IMAGE; c.trimStartMs = 0; c.trimEndMs = len; c.sourceDurationMs = len
                }
                commit()
            }
        }
    }

    private fun splitClip(i: Int) {
        val c = project.clips[i]
        val local = timeMs - project.clipStartMs(i)
        if (local < 100 || local > c.outDurationMs - 100) { toast(tr("Dhig xariiqda dhexda muuqaalka", "Move the playhead inside the clip")); return }
        val b = c.copy()
        b.transition = TransitionKind.NONE
        if (c.kind == MediaKind.IMAGE) {
            val total = c.trimmedMs
            c.trimStartMs = 0; c.trimEndMs = local
            b.trimStartMs = 0; b.trimEndMs = total - local
        } else {
            val srcSplit = c.trimStartMs + (local * c.speed).toLong()
            b.trimStartMs = srcSplit
            c.trimEndMs = srcSplit
        }
        project.clips.add(i + 1, b)
        setSelection(TimelineView.Sel.ClipSel(i + 1))
        commit()
    }

    private fun duplicateClip(i: Int) {
        project.clips.add(i + 1, project.clips[i].copy())
        commit()
    }

    private fun moveClip(i: Int, d: Int) {
        val j = i + d
        if (j !in project.clips.indices) return
        val c = project.clips.removeAt(i)
        project.clips.add(j, c)
        setSelection(TimelineView.Sel.ClipSel(j))
        commit()
    }

    private fun deleteClip(i: Int) {
        project.clips.removeAt(i)
        setSelection(null)
        commit()
    }

    private fun freezeFrame(i: Int) {
        val c = project.clips[i]
        val local = (timeMs - project.clipStartMs(i)).coerceIn(0, c.outDurationMs - 1)
        val srcT = c.trimStartMs + (local * c.speed).toLong()
        toast(tr("Sawirka waa la qaadayaa…", "Grabbing frame…"))
        io.execute {
            val r = MediaMetadataRetriever()
            val bmp: Bitmap? = try {
                r.setDataSource(this, Uri.parse(c.uri))
                r.getFrameAtTime(srcT * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
            } catch (e: Exception) { null } finally { runCatching { r.release() } }
            if (bmp == null) { main.post { toast(tr("Lama qaadi karo", "Could not grab the frame")) }; return@execute }
            val dir = File(filesDir, "frames").apply { mkdirs() }
            val f = File(dir, "freeze_${newId()}.jpg")
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            main.post {
                // split at the playhead and put a 3 second still in between
                val still = Clip(uri = Uri.fromFile(f).toString(), kind = MediaKind.IMAGE, sourceDurationMs = 3000,
                    trimStartMs = 0, trimEndMs = 3000, width = bmp.width, height = bmp.height, adjust = c.adjust.copy(),
                    tScale = c.tScale, tRot = c.tRot, tX = c.tX, tY = c.tY, mirror = c.mirror)
                if (local in 100..(c.outDurationMs - 100)) {
                    val b = c.copy(); b.transition = TransitionKind.NONE
                    b.trimStartMs = srcT; c.trimEndMs = srcT
                    project.clips.add(i + 1, still)
                    project.clips.add(i + 2, b)
                } else project.clips.add(i + 1, still)
                setSelection(TimelineView.Sel.ClipSel(i + 1))
                commit()
            }
        }
    }

    private fun extractAudio(i: Int) {
        val c = project.clips[i]
        project.audios.add(AudioTrack(uri = c.uri, name = tr("Codka muuqaalka", "Clip audio"), kind = AudioKind.EXTRACTED,
            startMs = project.clipStartMs(i), trimStartMs = c.trimStartMs, durationMs = c.trimmedMs,
            sourceDurationMs = c.sourceDurationMs, volume = c.volume.coerceAtLeast(0.01f), fromVideo = true))
        c.volume = 0f
        commit()
        toast(tr("Codka waa la soo saaray — hadda waa track gooni ah", "Audio extracted to its own track"))
    }

    private fun showSpeed(c: Clip) {
        val (d, root) = Ui.sheet(this, tr("Xawaaraha", "Speed")) { commit() }
        val lbl = Ui.label(this, "${TimelineView.trim(c.speed)}x")
        root.addView(lbl)
        root.addView(Ui.slider(this, 0.25f, 4f, c.speed, 0.25f) { v -> c.speed = v; lbl.text = "${TimelineView.trim(v)}x" })
        root.addView(Ui.choiceRow(this, listOf("0.5x", "1x", "1.5x", "2x", "3x"), -1) { k ->
            c.speed = floatArrayOf(0.5f, 1f, 1.5f, 2f, 3f)[k]; d.dismiss()
        })
        d.show()
    }

    private fun showVolume(c: Clip) {
        val (d, root) = Ui.sheet(this, tr("Codka", "Volume")) { commit() }
        val lbl = Ui.label(this, "${(c.volume * 100).toInt()}%")
        root.addView(lbl)
        root.addView(Ui.slider(this, 0f, 2f, c.volume, 0.05f) { v -> c.volume = v; lbl.text = "${(v * 100).toInt()}%"; live() })
        root.addView(Ui.button(this, if (c.volume == 0f) tr("Fur codka", "Unmute") else tr("Aamusi", "Mute"), false) {
            c.volume = if (c.volume == 0f) 1f else 0f; d.dismiss()
        })
        root.addView(Ui.button(this, tr("Ku dabaq dhammaan", "Apply to all"), false) {
            for (o in project.clips) o.volume = c.volume
            d.dismiss()
        })
        d.show()
    }

    /** Filters + adjustments for a clip or the photo background. */
    private fun showAdjust(a: Adjust, applyAll: (() -> Unit)?) {
        val (d, root) = Ui.sheet(this, tr("Filter & Hagaajin", "Filters & Adjust")) { commit() }
        val presets = FilterPreset.entries
        root.addView(Ui.choiceRow(this, presets.map { it.label }, presets.indexOf(a.preset)) { k -> a.preset = presets[k]; live() })
        fun row(so: String, en: String, v: Float, from: Float = -1f, set: (Float) -> Unit) {
            root.addView(Ui.label(this, tr(so, en)))
            root.addView(Ui.slider(this, from, 1f, v) { set(it); live() })
        }
        row("Iftiin", "Brightness", a.brightness) { a.brightness = it }
        row("Kala duwanaan", "Contrast", a.contrast) { a.contrast = it }
        row("Midab", "Saturation", a.saturation) { a.saturation = it }
        row("Diirimaad", "Temperature", a.temperature) { a.temperature = it }
        row("Midab-dhexe", "Tint", a.tint) { a.tint = it }
        row("Qariin (blur)", "Blur", a.blur, 0f) { a.blur = it }
        if (applyAll != null) root.addView(Ui.button(this, tr("Ku dabaq dhammaan muuqaalada", "Apply to all clips"), false) { applyAll(); d.dismiss() })
        root.addView(Ui.button(this, tr("Dib u celi", "Reset"), false) {
            a.brightness = 0f; a.contrast = 0f; a.saturation = 0f; a.temperature = 0f; a.tint = 0f; a.blur = 0f; a.preset = FilterPreset.NONE
            d.dismiss()
        })
        d.show()
    }

    private fun showTrim(c: Clip) {
        val (d, root) = Ui.sheet(this, tr("Gooy (trim)", "Trim")) { commit() }
        val lbl = Ui.label(this, "")
        fun upd() { lbl.text = TimelineView.fmt(c.trimStartMs) + " → " + TimelineView.fmt(c.trimEndMs) + "   (" + "%.1f".format(c.trimmedMs / 1000f) + "s)" }
        upd()
        root.addView(lbl)
        if (c.kind == MediaKind.IMAGE) {
            root.addView(Ui.label(this, tr("Mudada sawirka", "Photo duration")))
            root.addView(Ui.slider(this, 0.5f, 30f, c.trimmedMs / 1000f, 0.5f) { v -> c.trimStartMs = 0; c.trimEndMs = (v * 1000).toLong(); c.sourceDurationMs = c.trimEndMs; upd() })
        } else {
            val maxMs = c.sourceDurationMs.toFloat().coerceAtLeast(200f)
            val rs = RangeSlider(this).apply {
                valueFrom = 0f; valueTo = maxMs
                values = listOf(c.trimStartMs.toFloat().coerceIn(0f, maxMs), c.trimEndMs.toFloat().coerceIn(0f, maxMs))
                minSeparation = 100f
                addOnChangeListener { s, _, fromUser ->
                    if (fromUser) { c.trimStartMs = s.values[0].toLong(); c.trimEndMs = s.values[1].toLong(); upd() }
                }
            }
            root.addView(rs)
        }
        d.show()
    }

    /** CapCut "canvas": rotate, mirror, fit/fill. The clip can also be pinched on the preview. */
    private fun showCanvas(c: Clip) {
        val (d, root) = Ui.sheet(this, tr("Shaashadda", "Canvas")) { commit() }
        root.addView(Ui.label(this, tr("Farta ku dhaqaaji, ku weyneey ama ku wareeji muuqaalka sawirka korka.",
            "Drag, pinch or twist the video on the preview to move, zoom or rotate it.")))
        root.addView(Ui.label(this, tr("Weyneyn", "Zoom")))
        root.addView(Ui.slider(this, 0.2f, 4f, c.tScale) { c.tScale = it; live() })
        root.addView(Ui.label(this, tr("Wareejin", "Rotation")))
        root.addView(Ui.slider(this, 0f, 359f, c.tRot) { c.tRot = it; live() })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun b(s: String, f: () -> Unit) = row.addView(Ui.button(this, s, false, f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6f) })
        b(tr("Wareeji 90°", "Rotate 90°")) { c.tRot = (c.tRot + 90f) % 360f; d.dismiss() }
        b(tr("Muraayad", "Mirror")) { c.mirror = !c.mirror; d.dismiss() }
        root.addView(row)
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun b2(s: String, f: () -> Unit) = row2.addView(Ui.button(this, s, false, f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6f) })
        b2(tr("Buuxi", "Fill")) {
            val ca = c.width.toFloat().coerceAtLeast(1f) / c.height.coerceAtLeast(1)
            val r = project.aspectRatio()
            c.tScale = maxOf(ca / r, r / ca); c.tX = 0f; c.tY = 0f; c.tRot = 0f; d.dismiss()
        }
        b2(tr("Ku habee", "Fit")) { c.tScale = 1f; c.tX = 0f; c.tY = 0f; c.tRot = 0f; c.mirror = false; d.dismiss() }
        root.addView(row2)
        root.addView(Ui.button(this, tr("Ku dabaq dhammaan", "Apply to all"), false) {
            for (o in project.clips) { o.tScale = c.tScale; o.tRot = c.tRot; o.tX = c.tX; o.tY = c.tY; o.mirror = c.mirror }
            d.dismiss()
        })
        d.show()
    }

    private fun showTransition(index: Int) {
        val c = project.clips.getOrNull(index) ?: return
        val (d, root) = Ui.sheet(this, tr("Isbeddelka (transition)", "Transition")) { commit() }
        val kinds = TransitionKind.entries
        val grid = GridLayout(this).apply { columnCount = 4 }
        for (k in kinds) {
            val v = Ui.iconChip(this, if (k == TransitionKind.NONE) R.drawable.ic_close else R.drawable.ic_transition, k.label) {
                c.transition = k; live(); d.dismiss()
            }
            if (k == c.transition) v.background = Ui.roundBg(Ui.SURFACE2, dp(12f).toFloat(), dp(2f), Ui.ACCENT)
            grid.addView(v, GridLayout.LayoutParams().apply { width = dp(76f); setMargins(dp(4f), dp(4f), dp(4f), dp(4f)) })
        }
        root.addView(grid)
        root.addView(Ui.label(this, tr("Mudada", "Duration")))
        root.addView(Ui.slider(this, 0.2f, 2f, c.transitionMs / 1000f, 0.1f) { c.transitionMs = (it * 1000).toLong(); live() })
        root.addView(Ui.button(this, tr("Ku dabaq dhammaan", "Apply to all"), false) {
            for ((i, o) in project.clips.withIndex()) if (i > 0) { o.transition = c.transition; o.transitionMs = c.transitionMs }
            d.dismiss()
        })
        d.show()
    }

    // ------------------------------------------------------------------ layers

    private fun newLayerTimes(l: Layer, length: Long) {
        if (photo) { l.startMs = 0; l.endMs = PHOTO_END; return }
        val total = project.durationMs.coerceAtLeast(3000)
        l.startMs = timeMs.coerceAtMost((total - 500).coerceAtLeast(0))
        l.endMs = (l.startMs + length).coerceAtMost(maxOf(total, l.startMs + 500))
    }

    private fun addLayer(l: Layer) {
        project.layers.add(l)
        setSelection(TimelineView.Sel.LayerSel(l.id))
        commit()
        timeline.revealLayer(project.layers.size - 1)
    }

    private fun addText() {
        val l = Layer(kind = LayerKind.TEXT, text = tr("Qoraal", "Text"))
        newLayerTimes(l, 3000)
        addLayer(l)
        showTextEditor(l)
    }

    private fun showStickers() {
        val emojis = listOf("😀", "😂", "😍", "🥰", "😎", "🤩", "😭", "😡", "👍", "👏", "🙏", "💪", "🔥", "✨", "💯", "❤️",
            "💔", "⭐", "🎉", "🎁", "🎵", "📌", "✅", "❌", "⚡", "🌙", "☀️", "🌸", "🇸🇴", "🕌", "📿", "🤲", "👀", "💥", "🚀", "🏆",
            "🎂", "🌹", "💎", "👑", "📢", "💡", "📍", "🎬", "📸", "🎤", "⚽", "🌍")
        val (d, root) = Ui.sheet(this, "Sticker")
        val grid = GridLayout(this).apply { columnCount = 6 }
        for (e in emojis) {
            grid.addView(Ui.text(this, e, 30f).apply {
                gravity = Gravity.CENTER
                setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
                setOnClickListener {
                    d.dismiss()
                    val l = Layer(kind = LayerKind.STICKER, text = e, textSizeFrac = 0.18f, bold = false)
                    newLayerTimes(l, 3000)
                    addLayer(l)
                }
            })
        }
        root.addView(grid)
        d.show()
    }

    private fun showShapes() {
        val (d, root) = Ui.sheet(this, tr("Qaabab", "Shapes"))
        val grid = GridLayout(this).apply { columnCount = 3 }
        for (k in ShapeKind.entries) {
            grid.addView(Ui.iconChip(this, R.drawable.ic_shape, k.label) {
                d.dismiss()
                val l = Layer(kind = LayerKind.SHAPE, shape = k, textColor = Ui.ACCENT, baseW = 0.4f)
                l.contentAspect = when (k) {
                    ShapeKind.LINE -> 0.05f
                    ShapeKind.ARROW -> 0.45f
                    ShapeKind.RECT, ShapeKind.ROUND_RECT -> 0.65f
                    ShapeKind.BUBBLE -> 0.8f
                    else -> 1f
                }
                if (k == ShapeKind.LINE) l.textColor = 0xFFFFFFFF.toInt()
                newLayerTimes(l, 3000)
                addLayer(l)
            }, GridLayout.LayoutParams().apply { width = dp(100f); setMargins(dp(4f), dp(4f), dp(4f), dp(4f)) })
        }
        root.addView(grid)
        d.show()
    }

    private fun addMediaLayer(uri: Uri) {
        keep(uri)
        io.execute {
            val info = MediaUtils.probe(this, uri)
            val name = MediaUtils.displayName(this, uri)
            main.post {
                if (info == null) { toast(tr("Faylka lama furi karo", "Could not open the file")); return@post }
                val l = Layer(kind = if (info.isVideo) LayerKind.VIDEO else LayerKind.IMAGE, uri = uri.toString(), name = name)
                l.contentAspect = info.height.toFloat() / info.width
                val r = project.aspectRatio()
                l.baseW = if (l.contentAspect * 0.6f * r > 0.6f) 0.6f / (l.contentAspect * r) else 0.6f
                if (info.isVideo) {
                    l.sourceDurationMs = info.durationMs
                    newLayerTimes(l, info.durationMs)
                    l.endMs = l.startMs + info.durationMs
                } else newLayerTimes(l, 3000)
                addLayer(l)
            }
        }
    }

    private var replaceLayerTarget: Layer? = null
    private val pickLayerImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val l = replaceLayerTarget ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        keep(uri)
        io.execute {
            val info = MediaUtils.probe(this, uri)
            main.post {
                if (info == null) return@post
                l.uri = uri.toString(); l.name = MediaUtils.displayName(this, uri)
                l.contentAspect = info.height.toFloat() / info.width
                commit()
            }
        }
    }

    private fun replaceLayerImage(l: Layer) {
        replaceLayerTarget = l
        pickLayerImage.launch(arrayOf("image/*"))
    }

    private class TextPreset(val so: String, val en: String, val fn: (Layer) -> Unit)

    private val textPresets = listOf(
        TextPreset("Caadi", "Classic") { it.textColor = 0xFFFFFFFF.toInt(); it.textColor2 = 0; it.strokeColor = 0; it.bgColor = 0; it.shadow = false; it.depth = 0f },
        TextPreset("Xariiq", "Outline") { it.textColor = 0xFFFFFFFF.toInt(); it.textColor2 = 0; it.strokeColor = 0xFF000000.toInt(); it.strokeWidth = 0.14f; it.bgColor = 0; it.depth = 0f },
        TextPreset("Hoosaad", "Subtitle") { it.textColor = 0xFFFFE600.toInt(); it.textColor2 = 0; it.strokeColor = 0xFF000000.toInt(); it.strokeWidth = 0.16f; it.bgColor = 0; it.bold = true; it.depth = 0f },
        TextPreset("Sanduuq", "Box") { it.textColor = 0xFFFFFFFF.toInt(); it.textColor2 = 0; it.strokeColor = 0; it.bgColor = 0xCC000000.toInt(); it.depth = 0f },
        TextPreset("Hadal", "Bubble") { it.textColor = 0xFF111111.toInt(); it.textColor2 = 0; it.strokeColor = 0; it.bgColor = 0xFFFFFFFF.toInt(); it.depth = 0f },
        TextPreset("Neon", "Neon") { it.textColor = 0xFF7DF9FF.toInt(); it.textColor2 = 0; it.strokeColor = 0xFFFF2D95.toInt(); it.strokeWidth = 0.08f; it.shadow = true; it.bgColor = 0; it.depth = 0f },
        TextPreset("Dahab 3D", "Gold 3D") { it.textColor = 0xFFFFE27A.toInt(); it.textColor2 = 0xFFE09B12.toInt(); it.strokeColor = 0xFF5A3A00.toInt(); it.strokeWidth = 0.06f; it.depth = 0.6f; it.depthColor = 0xFF6B4300.toInt(); it.bgColor = 0 },
        TextPreset("Dab", "Fire") { it.textColor = 0xFFFFF176.toInt(); it.textColor2 = 0xFFFF3D00.toInt(); it.strokeColor = 0xFF3E0000.toInt(); it.strokeWidth = 0.1f; it.depth = 0f; it.bgColor = 0 },
        TextPreset("Retro", "Retro") { it.textColor = 0xFFFF6EC7.toInt(); it.textColor2 = 0; it.strokeColor = 0xFFFFFFFF.toInt(); it.strokeWidth = 0.06f; it.depth = 0.5f; it.depthColor = 0xFF5B2A86.toInt(); it.bgColor = 0 },
        TextPreset("Hadh", "Shadow") { it.textColor = 0xFFFFFFFF.toInt(); it.textColor2 = 0; it.strokeColor = 0; it.shadow = true; it.bgColor = 0; it.depth = 0f },
        TextPreset("Barafle", "Ice") { it.textColor = 0xFFFFFFFF.toInt(); it.textColor2 = 0xFF8FD3FF.toInt(); it.strokeColor = 0xFF1565C0.toInt(); it.strokeWidth = 0.08f; it.depth = 0.3f; it.depthColor = 0xFF0D47A1.toInt(); it.bgColor = 0 }
    )

    private fun copyTextStyle(from: Layer, to: Layer) {
        to.textColor = from.textColor; to.textColor2 = from.textColor2; to.strokeColor = from.strokeColor
        to.strokeWidth = from.strokeWidth; to.bgColor = from.bgColor; to.textSizeFrac = from.textSizeFrac
        to.bold = from.bold; to.font = from.font; to.align = from.align; to.shadow = from.shadow
        to.depth = from.depth; to.depthColor = from.depthColor; to.letterSpacing = from.letterSpacing
        to.cx = from.cx; to.cy = from.cy; to.scale = from.scale; to.animIn = from.animIn; to.animOut = from.animOut
    }

    private fun showTextEditor(l: Layer) {
        val (d, root) = Ui.sheet(this, if (l.kind == LayerKind.STICKER) "Sticker" else tr("Qoraal", "Text")) { commit() }
        val edit = EditText(this).apply {
            setText(l.text)
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.TEXT2)
            hint = tr("Qor halkan…", "Type here…")
            background = Ui.roundBg(Ui.SURFACE2, dp(10f).toFloat())
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            setSelection(text.length)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { l.text = s?.toString() ?: ""; live() }
            })
        }
        root.addView(edit)
        root.addView(Ui.label(this, tr("Cabbirka", "Size")))
        root.addView(Ui.slider(this, 0.02f, 0.3f, l.textSizeFrac) { l.textSizeFrac = it; live() })
        if (l.kind == LayerKind.TEXT) {
            root.addView(Ui.label(this, tr("Qaababka diyaarsan", "Styles")))
            root.addView(Ui.choiceRow(this, textPresets.map { tr(it.so, it.en) }, -1) { k -> textPresets[k].fn(l); live() })
            root.addView(Ui.label(this, tr("Midabka qoraalka", "Text colour")))
            root.addView(Ui.colorRow(this, l.textColor, false) { l.textColor = it; live() })
            root.addView(Ui.label(this, tr("Midab labaad (gradient)", "Second colour (gradient)")))
            root.addView(Ui.colorRow(this, l.textColor2, true) { l.textColor2 = it; live() })
            root.addView(Ui.label(this, tr("Xariiq (outline)", "Outline")))
            root.addView(Ui.colorRow(this, l.strokeColor, true) { l.strokeColor = it; live() })
            root.addView(Ui.slider(this, 0.02f, 0.4f, l.strokeWidth) { l.strokeWidth = it; live() })
            root.addView(Ui.label(this, tr("Gadaal (background)", "Background")))
            root.addView(Ui.colorRow(this, l.bgColor, true) { l.bgColor = it; live() })
            root.addView(Ui.label(this, tr("3D qoto-dheer", "3D depth")))
            root.addView(Ui.slider(this, 0f, 1f, l.depth) { l.depth = it; live() })
            root.addView(Ui.colorRow(this, l.depthColor, false) { l.depthColor = it; live() })
            root.addView(Ui.label(this, tr("Kala fogaanta xarfaha", "Letter spacing")))
            root.addView(Ui.slider(this, -0.1f, 0.5f, l.letterSpacing) { l.letterSpacing = it; live() })
            root.addView(Ui.label(this, tr("Farta", "Font")))
            root.addView(Ui.choiceRow(this, LayerRenderer.FONTS, l.font) { l.font = it; live() })
            root.addView(Ui.label(this, tr("Qaab", "Format")))
            root.addView(Ui.choiceRow(this, listOf(tr("Adag", "Bold"), tr("Caadi", "Regular"), tr("Hadh: Haa", "Shadow: on"), tr("Hadh: Maya", "Shadow: off")),
                if (l.bold) 0 else 1) { k ->
                when (k) { 0 -> l.bold = true; 1 -> l.bold = false; 2 -> l.shadow = true; else -> l.shadow = false }
                live()
            })
            root.addView(Ui.choiceRow(this, listOf(tr("Bidix", "Left"), tr("Dhexe", "Center"), tr("Midig", "Right")), l.align) { l.align = it; live() })
            if (l.isCaption) {
                root.addView(Ui.button(this, tr("Qaabkan u dabaq dhammaan qoraal-hoosaadyada", "Apply this style to all captions"), false) {
                    for (o in project.layers) if (o.isCaption && o.id != l.id) copyTextStyle(l, o)
                    d.dismiss()
                })
            }
        }
        d.show()
    }

    private fun showShapeEditor(l: Layer) {
        val (d, root) = Ui.sheet(this, l.shape.label) { commit() }
        root.addView(Ui.choiceRow(this, ShapeKind.entries.map { it.label }, ShapeKind.entries.indexOf(l.shape)) { l.shape = ShapeKind.entries[it]; live() })
        root.addView(Ui.label(this, tr("Midabka", "Fill")))
        root.addView(Ui.colorRow(this, l.textColor, false) { l.textColor = it; live() })
        root.addView(Ui.label(this, tr("Midab labaad (gradient)", "Second colour (gradient)")))
        root.addView(Ui.colorRow(this, l.textColor2, true) { l.textColor2 = it; live() })
        root.addView(Ui.label(this, tr("Xariiq", "Outline")))
        root.addView(Ui.colorRow(this, l.strokeColor, true) { l.strokeColor = it; live() })
        root.addView(Ui.slider(this, 0.02f, 0.3f, l.strokeWidth) { l.strokeWidth = it; live() })
        root.addView(Ui.label(this, tr("Ballac", "Width")))
        root.addView(Ui.slider(this, 0.05f, 1.2f, l.baseW) { l.baseW = it; live() })
        root.addView(Ui.label(this, tr("Dherer", "Height")))
        root.addView(Ui.slider(this, 0.02f, 3f, l.contentAspect) { l.contentAspect = it; live() })
        d.show()
    }

    private fun showOpacity(l: Layer, title: String) {
        val (d, root) = Ui.sheet(this, title) { commit() }
        root.addView(Ui.slider(this, 0f, 1f, LayerRenderer.basePose(l, timeMs).opacity) {
            val p = LayerRenderer.basePose(l, timeMs); p.opacity = it
            LayerRenderer.writePose(l, timeMs, p); live()
        })
        d.show()
    }

    private fun showAnimation(l: Layer) {
        val (d, root) = Ui.sheet(this, tr("Dhaqdhaqaaq", "Animation")) { commit() }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun show(tab: Int) {
            body.removeAllViews()
            when (tab) {
                0, 1 -> {
                    val anims = LayerAnim.entries
                    val cur = if (tab == 0) l.animIn else l.animOut
                    val grid = GridLayout(this).apply { columnCount = 4 }
                    for (a in anims) {
                        val v = Ui.iconChip(this, if (a == LayerAnim.NONE) R.drawable.ic_close else R.drawable.ic_animation, a.label) {
                            if (tab == 0) l.animIn = a else l.animOut = a
                            // preview the animation
                            timeMs = if (tab == 0) l.startMs else (l.endMs - minOf(l.animOutMs, l.durationMs / 2) - 50).coerceAtLeast(l.startMs)
                            timeline.timeMs = timeMs; engine.seekTo(timeMs); engine.play(); updatePlayButton()
                            main.postDelayed({ if (engine.isPlaying) { engine.pause(); updatePlayButton() } }, (if (tab == 0) l.animInMs else l.animOutMs) + 400)
                            show(tab)
                        }
                        if (a == cur) v.background = Ui.roundBg(Ui.SURFACE2, dp(12f).toFloat(), dp(2f), Ui.ACCENT)
                        grid.addView(v, GridLayout.LayoutParams().apply { width = dp(76f); setMargins(dp(4f), dp(4f), dp(4f), dp(4f)) })
                    }
                    body.addView(grid)
                    body.addView(Ui.label(this, tr("Mudada", "Duration")))
                    body.addView(Ui.slider(this, 0.1f, 3f, (if (tab == 0) l.animInMs else l.animOutMs) / 1000f, 0.1f) {
                        if (tab == 0) l.animInMs = (it * 1000).toLong() else l.animOutMs = (it * 1000).toLong()
                    })
                }
                else -> {
                    val grid = GridLayout(this).apply { columnCount = 4 }
                    for (a in LoopAnim.entries) {
                        val v = Ui.iconChip(this, if (a == LoopAnim.NONE) R.drawable.ic_close else R.drawable.ic_animation, a.label) {
                            l.animLoop = a; live(); show(tab)
                        }
                        if (a == l.animLoop) v.background = Ui.roundBg(Ui.SURFACE2, dp(12f).toFloat(), dp(2f), Ui.ACCENT)
                        grid.addView(v, GridLayout.LayoutParams().apply { width = dp(76f); setMargins(dp(4f), dp(4f), dp(4f), dp(4f)) })
                    }
                    body.addView(grid)
                }
            }
        }
        root.addView(Ui.choiceRow(this, listOf(tr("Gal", "In"), tr("Bax", "Out"), tr("Wareeg", "Loop")), 0) { show(it) })
        root.addView(body)
        show(0)
        d.show()
    }

    private fun showMask(l: Layer) {
        val (d, root) = Ui.sheet(this, tr("Maaskaro", "Mask")) { commit() }
        val grid = GridLayout(this).apply { columnCount = 4 }
        val chips = ArrayList<View>()
        for (m in MaskKind.entries) {
            val v = Ui.iconChip(this, when (m) {
                MaskKind.NONE -> R.drawable.ic_close
                MaskKind.CIRCLE -> R.drawable.ic_circle
                else -> R.drawable.ic_mask
            }, m.label) {
                l.mask = m; live()
                chips.forEachIndexed { i, c -> c.background = Ui.roundBg(Ui.SURFACE2, dp(12f).toFloat(), if (MaskKind.entries[i] == m) dp(2f) else 0, Ui.ACCENT) }
            }
            if (m == l.mask) v.background = Ui.roundBg(Ui.SURFACE2, dp(12f).toFloat(), dp(2f), Ui.ACCENT)
            chips.add(v)
            grid.addView(v, GridLayout.LayoutParams().apply { width = dp(76f); setMargins(dp(4f), dp(4f), dp(4f), dp(4f)) })
        }
        root.addView(grid)
        root.addView(Ui.label(this, tr("Cabbirka", "Size")))
        root.addView(Ui.slider(this, 0.1f, 1.5f, l.maskSize) { l.maskSize = it; live() })
        root.addView(Ui.label(this, tr("Cidhif jilicsan (feather)", "Feather")))
        root.addView(Ui.slider(this, 0f, 1f, l.maskFeather) { l.maskFeather = it; live() })
        root.addView(Ui.choiceRow(this, listOf(tr("Caadi", "Normal"), tr("Rogan (invert)", "Invert")), if (l.maskInvert) 1 else 0) { l.maskInvert = it == 1; live() })
        d.show()
    }

    private fun toggleKeyframe() {
        val l = selectedLayer() ?: return
        if (l.isEffect()) return
        if (!l.isActive(timeMs)) { toast(tr("Dhig xariiqda layer-ka dhexdiisa", "Move the playhead over the layer")); return }
        val k = LayerRenderer.keyframeAt(l, timeMs)
        if (k != null) {
            l.keyframes.remove(k)
            toast(tr("Keyframe waa la tirtiray", "Keyframe removed"))
        } else {
            val p = LayerRenderer.basePose(l, timeMs)
            l.keyframes.add(so.ijarjar.app.model.Keyframe(timeMs - l.startMs, p.cx, p.cy, p.scale, p.rotation, p.opacity))
            if (l.keyframes.size == 1) toast(tr("Keyframe waa la daray. U dhaqaaji waqti kale oo layer-ka beddel — keyframe cusub ayaa samaysmaya.",
                "Keyframe added. Move to another time and change the layer — a new keyframe is made automatically."))
        }
        commit()
    }

    private fun splitLayer(l: Layer) {
        if (timeMs <= l.startMs + 100 || timeMs >= l.endMs - 100) { toast(tr("Dhig xariiqda dhexda layer-ka", "Move the playhead inside the layer")); return }
        val b = l.copy()
        val cut = timeMs - l.startMs
        b.startMs = timeMs
        b.keyframes = l.keyframes.map { it.copy().also { k -> k.t -= cut } }.toMutableList()
        if (l.kind == LayerKind.VIDEO) b.trimStartMs = l.trimStartMs + cut
        b.linkGroup = l.linkGroup
        l.endMs = timeMs
        project.layers.add(project.layers.indexOf(l) + 1, b)
        setSelection(TimelineView.Sel.LayerSel(b.id))
        commit()
    }

    private fun moveLayerStart(l: Layer) {
        val d = timeMs - l.startMs
        for (g in project.linkedWith(l)) { g.startMs += d; g.endMs += d }
        commit()
    }

    private fun reorderLayer(l: Layer, d: Int) {
        val i = project.layers.indexOf(l)
        val j = i + d
        if (j !in project.layers.indices) return
        project.layers.removeAt(i)
        project.layers.add(j, l)
        commit()
    }

    private fun duplicateLayer(l: Layer) {
        val b = l.copy()
        b.linkGroup = null
        if (!l.isEffect()) {
            b.cx = l.cx + 0.04f; b.cy = l.cy + 0.04f
            for (k in b.keyframes) { k.cx += 0.04f; k.cy += 0.04f }
        } else { b.startMs = l.endMs; b.endMs = l.endMs + l.durationMs }
        project.layers.add(project.layers.indexOf(l) + 1, b)
        setSelection(TimelineView.Sel.LayerSel(b.id))
        commit()
    }

    private fun deleteLayer(l: Layer) {
        project.layers.remove(l)
        cleanupGroups()
        setSelection(null)
        commit()
    }

    private fun cleanupGroups() {
        val counts = project.layers.mapNotNull { it.linkGroup }.groupingBy { it }.eachCount()
        for (x in project.layers) if (x.linkGroup != null && (counts[x.linkGroup] ?: 0) < 2) x.linkGroup = null
    }

    private fun layerTitle(l: Layer) = timeline.layerLabel(l).take(28)

    /** A list of all layers: tap to select. */
    private fun showLayers() {
        if (project.layers.isEmpty()) { toast(tr("Layer ma jiro weli", "No layers yet")); return }
        val (d, root) = Ui.sheet(this, tr("Layer-ada", "Layers"))
        for (l in project.layers.reversed()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = Ui.roundBg(Ui.SURFACE2, dp(10f).toFloat())
                setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
                setOnClickListener { d.dismiss(); onSelect(TimelineView.Sel.LayerSel(l.id)) }
            }
            row.addView(Ui.text(this, layerTitle(l), 14f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (l.linkGroup != null) row.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_link); imageTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT)
            }, LinearLayout.LayoutParams(dp(20f), dp(20f)))
            root.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6f) })
        }
        d.show()
    }

    /** Link layers so they move, scale, rotate and shift in time together. */
    private fun showLink(base: Layer?) {
        val layers = project.layers.filter { !it.isEffect() }
        if (layers.size < 2) { toast(tr("Ugu yaraan laba layer ku dar", "Add at least two layers first")); return }
        val candidates = if (base == null) layers else layers.filter { it.id != base.id }
        val names = candidates.map { layerTitle(it) }.toTypedArray()
        val checked = BooleanArray(candidates.size) { i -> base != null && base.linkGroup != null && candidates[i].linkGroup == base.linkGroup }
        MaterialAlertDialogBuilder(this)
            .setTitle(if (base == null) tr("Dooro layer-ada la isku xirayo", "Choose layers to link") else tr("Ku xir \"${layerTitle(base)}\" kuwan:", "Link \"${layerTitle(base)}\" with:"))
            .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setNegativeButton(tr("Jooji", "Cancel"), null)
            .setPositiveButton(tr("Isku xir", "Link")) { _, _ ->
                val chosen = candidates.filterIndexed { i, _ -> checked[i] }.toMutableList()
                if (base != null) chosen.add(0, base)
                if (chosen.size < 2) {
                    if (base != null && base.linkGroup != null) {
                        val g = base.linkGroup
                        for (x in project.layers) if (x.linkGroup == g) x.linkGroup = null
                        commit()
                    } else toast(tr("Dooro ugu yaraan laba", "Choose at least two"))
                    return@setPositiveButton
                }
                val group = base?.linkGroup ?: chosen.firstNotNullOfOrNull { it.linkGroup } ?: newId()
                for (x in project.layers) if (x.linkGroup == group && x !in chosen) x.linkGroup = null
                for (x in chosen) x.linkGroup = group
                cleanupGroups()
                commit()
                toast(tr("${chosen.size} layer waa la isku xiray — hal mar ayay wada dhaqaaqayaan", "${chosen.size} layers linked — they now move together"))
            }
            .show()
    }

    private fun unlink(l: Layer) {
        l.linkGroup = null
        cleanupGroups()
        commit()
    }

    // ------------------------------------------------------------------ effects & captions

    private fun showEffects(replace: Layer?) {
        val (d, root) = Ui.sheet(this, tr("Saameyn (Effects)", "Effects"))
        val groups = listOf(tr("Dhaqdhaqaaq", "Motion"), tr("Iftiin", "Light"), tr("Midab", "Colour"), tr("Qurxin", "Overlay"))
        for ((gi, gname) in groups.withIndex()) {
            root.addView(Ui.label(this, gname))
            val grid = GridLayout(this).apply { columnCount = 4 }
            for (e in EffectKind.entries.filter { it.group == gi }) {
                grid.addView(Ui.iconChip(this, R.drawable.ic_effects, e.label) {
                    d.dismiss()
                    if (replace != null) { replace.effect = e; commit(); return@iconChip }
                    val l = Layer(kind = LayerKind.EFFECT, effect = e, name = e.en)
                    newLayerTimes(l, 3000)
                    addLayer(l)
                }, GridLayout.LayoutParams().apply { width = dp(76f); setMargins(dp(4f), dp(4f), dp(4f), dp(4f)) })
            }
            root.addView(grid)
        }
        d.show()
    }

    private fun captionLayer(text: String, start: Long, end: Long): Layer {
        val template = project.layers.firstOrNull { it.isCaption }
        val l = Layer(kind = LayerKind.TEXT, text = text, isCaption = true, textSizeFrac = 0.055f, cy = 0.82f,
            textColor = 0xFFFFFFFF.toInt(), strokeColor = 0xFF000000.toInt(), strokeWidth = 0.14f)
        if (template != null) copyTextStyle(template, l)
        l.startMs = start; l.endMs = end
        return l
    }

    private fun showCaptions() {
        val (d, root) = Ui.sheet(this, tr("Qoraal-hoosaad (Captions)", "Captions"))
        root.addView(Ui.label(this, tr("Qor qoraalka. Sadar kasta wuxuu noqonayaa hal qoraal-hoosaad.",
            "Type your captions. Each line becomes one caption.")))
        val edit = EditText(this).apply {
            setTextColor(Ui.TEXT); setHintTextColor(Ui.TEXT2)
            hint = tr("Sadarka 1aad\nSadarka 2aad…", "First line\nSecond line…")
            minLines = 4
            gravity = Gravity.TOP
            background = Ui.roundBg(Ui.SURFACE2, dp(10f).toFloat())
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
        }
        root.addView(edit)
        root.addView(Ui.label(this, tr("Mudada qoraal kasta (ilbiriqsi)", "Seconds per caption")))
        var per = 2.5f
        val perLbl = Ui.label(this, "2.5 s")
        root.addView(perLbl)
        root.addView(Ui.slider(this, 0.5f, 8f, per, 0.5f) { per = it; perLbl.text = "$it s" })
        root.addView(Ui.button(this, tr("Ku dar laga bilaabo xariiqda", "Add from the playhead")) {
            val lines = edit.text.toString().split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) return@button
            var t = timeMs
            val step = (per * 1000).toLong()
            for (line in lines) { project.layers.add(captionLayer(line, t, t + step)); t += step }
            d.dismiss(); commit()
            toast(tr("${lines.size} qoraal-hoosaad waa la daray", "${lines.size} captions added"))
        })
        root.addView(Ui.button(this, tr("U qaybi muuqaalka oo dhan", "Spread over the whole video"), false) {
            val lines = edit.text.toString().split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty() || project.durationMs <= 0) return@button
            val step = project.durationMs / lines.size
            for ((i, line) in lines.withIndex()) project.layers.add(captionLayer(line, i * step, (i + 1) * step))
            d.dismiss(); commit()
        })
        val existing = project.layers.filter { it.isCaption }
        if (existing.isNotEmpty()) {
            root.addView(Ui.button(this, tr("Wax ka beddel qaabka qoraal-hoosaadyada", "Edit caption style"), false) {
                d.dismiss()
                setSelection(TimelineView.Sel.LayerSel(existing[0].id))
                showTextEditor(existing[0])
            })
            root.addView(Ui.button(this, tr("Tirtir dhammaan qoraal-hoosaadyada", "Delete all captions"), false) {
                project.layers.removeAll { it.isCaption }; d.dismiss(); setSelection(null); commit()
            })
        }
        d.show()
    }

    // ------------------------------------------------------------------ audio

    private fun showAudioMenu() {
        val (d, root) = Ui.sheet(this, tr("Cod", "Audio"))
        val grid = GridLayout(this).apply { columnCount = 4 }
        fun item(icon: Int, label: String, f: () -> Unit) =
            grid.addView(Ui.iconChip(this, icon, label) { d.dismiss(); f() }, GridLayout.LayoutParams().apply { width = dp(76f); setMargins(dp(4f), dp(4f), dp(4f), dp(4f)) })
        item(R.drawable.ic_music, tr("Muusik", "Music")) { addAudioKind = AudioKind.MUSIC; pickAudio.launch(arrayOf("audio/*")) }
        item(R.drawable.ic_mic, tr("Cod-duub", "Voiceover")) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) showVoiceover()
            else askMic.launch(Manifest.permission.RECORD_AUDIO)
        }
        item(R.drawable.ic_waveform, tr("Ka soo saar", "Extract")) { addAudioKind = AudioKind.EXTRACTED; pickAudio.launch(arrayOf("video/*")) }
        item(R.drawable.ic_sound, tr("Dhawaaq", "Sound FX")) { addAudioKind = AudioKind.SOUND; pickAudio.launch(arrayOf("audio/*")) }
        root.addView(grid)
        d.show()
    }

    private fun addAudio(uri: Uri, kind: AudioKind) {
        keep(uri)
        io.execute {
            val info = MediaUtils.probe(this, uri)
            val name = MediaUtils.displayName(this, uri)
            main.post {
                val src = info?.durationMs ?: 0L
                val start = if (kind == AudioKind.MUSIC && project.audios.none { it.kind == AudioKind.MUSIC }) 0L else timeMs
                val room = (project.durationMs - start).coerceAtLeast(1000)
                val a = AudioTrack(uri = uri.toString(), name = name, kind = kind, startMs = start, trimStartMs = 0,
                    durationMs = if (src > 0) minOf(src, room) else room, sourceDurationMs = src, fromVideo = kind == AudioKind.EXTRACTED)
                project.audios.add(a)
                setSelection(TimelineView.Sel.AudioSel(a.id))
                commit()
            }
        }
    }

    private fun showAudioVolume(a: AudioTrack) {
        val (d, root) = Ui.sheet(this, tr("Codka", "Volume")) { commit() }
        val lbl = Ui.label(this, "${(a.volume * 100).toInt()}%")
        root.addView(lbl)
        root.addView(Ui.slider(this, 0f, 2f, a.volume, 0.05f) { a.volume = it; lbl.text = "${(it * 100).toInt()}%"; live() })
        d.show()
    }

    private fun splitAudio(a: AudioTrack) {
        if (timeMs <= a.startMs + 100 || timeMs >= a.endMs - 100) { toast(tr("Dhig xariiqda dhexda codka", "Move the playhead inside the audio")); return }
        val b = a.copy()
        val cut = timeMs - a.startMs
        b.startMs = timeMs; b.trimStartMs = a.trimStartMs + cut; b.durationMs = a.durationMs - cut
        a.durationMs = cut
        project.audios.add(project.audios.indexOf(a) + 1, b)
        setSelection(TimelineView.Sel.AudioSel(b.id))
        commit()
    }

    private var recordFile: File? = null
    private var recordStartT = 0L
    private var recordStartClock = 0L

    private fun showVoiceover() {
        val (d, root) = Ui.sheet(this, tr("Cod-duub (voiceover)", "Voiceover")) { stopRecording(cancel = false) }
        root.addView(Ui.label(this, tr("Riix badhanka si aad u duubto. Muuqaalku wuu socon doonaa adigoo hadlaya.",
            "Tap the button to record. The video plays while you talk.")))
        val status = Ui.text(this, "00:00", 28f, Ui.TEXT, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(12f), 0, dp(12f)) }
        root.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val btn = ImageView(this).apply {
            setImageResource(R.drawable.ic_record)
            imageTintList = android.content.res.ColorStateList.valueOf(0xFFFF3B30.toInt())
        }
        root.addView(btn, LinearLayout.LayoutParams(dp(84f), dp(84f)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        val ticker = object : Runnable {
            override fun run() {
                if (recorder == null) return
                status.text = TimelineView.fmt(SystemClock.elapsedRealtime() - recordStartClock)
                main.postDelayed(this, 250)
            }
        }
        btn.setOnClickListener {
            if (recorder == null) {
                if (startRecording()) {
                    btn.setImageResource(R.drawable.ic_stop)
                    main.post(ticker)
                }
            } else {
                stopRecording(cancel = false)
                d.dismiss()
            }
        }
        d.show()
    }

    private fun startRecording(): Boolean {
        val dir = File(filesDir, "voice").apply { mkdirs() }
        val f = File(dir, "voice_${newId()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(44100)
            r.setAudioEncodingBitRate(128000)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            recordFile = f
            recordStartT = timeMs
            recordStartClock = SystemClock.elapsedRealtime()
            engine.muteMain = true
            engine.seekTo(timeMs); engine.play(); updatePlayButton()
            true
        } catch (e: Exception) {
            runCatching { r.release() }
            toast(tr("Duubista lama bilaabi karo", "Could not start recording"))
            false
        }
    }

    private fun stopRecording(cancel: Boolean) {
        val r = recorder ?: return
        recorder = null
        val len = SystemClock.elapsedRealtime() - recordStartClock
        runCatching { r.stop() }
        runCatching { r.release() }
        engine.pause(); engine.muteMain = false; engine.refreshVolumes(); updatePlayButton()
        val f = recordFile ?: return
        if (cancel || len < 300) { f.delete(); return }
        project.audios.add(AudioTrack(uri = Uri.fromFile(f).toString(), name = tr("Cod-duub", "Voiceover"), kind = AudioKind.VOICE,
            startMs = recordStartT, durationMs = len, sourceDurationMs = len))
        commit()
    }

    // ------------------------------------------------------------------ photo background

    private fun setBackgroundImage(uri: Uri) {
        keep(uri)
        io.execute {
            val info = MediaUtils.probe(this, uri)
            main.post {
                project.bgImageUri = uri.toString()
                project.bgScale = 1f; project.bgRot = 0f; project.bgX = 0f; project.bgY = 0f
                if (project.layers.isEmpty() && info != null) {
                    // match the canvas to the picture the first time
                    project.aspect = closestAspect(info.width.toFloat() / info.height)
                }
                commit()
            }
        }
    }

    private val aspects = listOf("9:16", "16:9", "1:1", "4:5", "4:3", "3:4", "2:3", "3:2", "21:9")

    private fun closestAspect(r: Float): String = aspects.minByOrNull {
        val p = it.split(":"); kotlin.math.abs(p[0].toFloat() / p[1].toFloat() - r)
    } ?: "1:1"

    private fun showBackground() {
        val (d, root) = Ui.sheet(this, tr("Gadaal", "Background")) { commit() }
        root.addView(Ui.label(this, tr("Midab", "Colour")))
        root.addView(Ui.colorRow(this, project.bgColor, false) { project.bgColor = it; live() })
        root.addView(Ui.label(this, tr("Midab labaad (gradient)", "Second colour (gradient)")))
        root.addView(Ui.colorRow(this, project.bgColor2, true) { project.bgColor2 = it; live() })
        root.addView(Ui.button(this, tr("Dooro sawir", "Choose picture")) { d.dismiss(); pickBackground.launch(arrayOf("image/*")) })
        if (project.bgImageUri != null) {
            root.addView(Ui.label(this, tr("Farta ku dhaqaaji ama ku weyneey sawirka korka.", "Drag or pinch the picture on the canvas.")))
            root.addView(Ui.button(this, tr("Filter & hagaajin sawirka", "Picture filters & adjust"), false) {
                d.dismiss(); showAdjust(project.bgAdjust, null)
            })
            root.addView(Ui.button(this, tr("Muraayad", "Mirror"), false) { project.bgMirror = !project.bgMirror; live() })
            root.addView(Ui.button(this, tr("Ka saar sawirka", "Remove picture"), false) { project.bgImageUri = null; d.dismiss() })
        }
        d.show()
    }

    // ------------------------------------------------------------------ aspect & export

    private fun showAspect() {
        val labels = listOf("9:16  TikTok", "16:9  YouTube", "1:1  Square", "4:5  Instagram", "4:3", "3:4", "2:3", "3:2", "21:9")
        val (d, root) = Ui.sheet(this, tr("Saamiga shaashadda", "Aspect ratio"))
        root.addView(Ui.choiceRow(this, labels, aspects.indexOf(project.aspect)) { k ->
            project.aspect = aspects[k]
            stage.requestLayout()
            commit()
            d.dismiss()
        })
        d.show()
    }

    private fun showExport() {
        if (project.clips.isEmpty()) { toast(tr("Marka hore muuqaal ku dar", "Add a video first")); return }
        engine.pause(); updatePlayButton()
        val (d, root) = Ui.sheet(this, tr("Dhoofi muuqaalka", "Export video"))
        var res = 1080
        root.addView(Ui.label(this, tr("Tayada", "Resolution")))
        root.addView(Ui.choiceRow(this, listOf("480p", "720p", "1080p"), 2) { k -> res = intArrayOf(480, 720, 1080)[k] })
        root.addView(Ui.label(this, tr("Mudada: ", "Duration: ") + TimelineView.fmt(project.durationMs)))
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }
        root.addView(bar)
        val status = Ui.label(this, "")
        root.addView(status)
        var exporter: Exporter? = null
        val startBtn = Ui.button(this, tr("Bilow dhoofinta", "Start export")) {}
        startBtn.setOnClickListener {
            startBtn.isEnabled = false
            bar.visibility = View.VISIBLE
            status.text = tr("Waa la samaynayaa… fadlan sug", "Rendering… please wait")
            save()
            exporter = Exporter(this, ProjectStore.fromJson(ProjectStore.toJson(project)), res, object : Exporter.Callback {
                override fun onProgress(percent: Int) { bar.progress = percent; status.text = tr("Waa la samaynayaa… ", "Rendering… ") + "$percent%" }
                override fun onDone(uri: Uri?, file: File) {
                    bar.progress = 100
                    status.text = if (uri != null) tr("Waa la keydiyay: Gallery → Movies/IjarJar", "Saved: Gallery → Movies/IjarJar") else tr("Diyaar", "Done")
                    startBtn.visibility = View.GONE
                    root.addView(Ui.button(this@EditorActivity, tr("Fur", "Open")) { openMedia(uri, file, "video/mp4") })
                    root.addView(Ui.button(this@EditorActivity, tr("Wadaag", "Share"), false) { shareMedia(uri, file, "video/mp4") })
                    exporter = null
                }
                override fun onError(message: String) {
                    status.text = tr("Khalad: ", "Error: ") + message
                    startBtn.isEnabled = true
                    exporter = null
                }
            })
            exporter?.start()
        }
        root.addView(startBtn)
        d.setOnDismissListener { exporter?.cancel() }
        d.show()
    }

    private fun showPhotoExport() {
        val (d, root) = Ui.sheet(this, tr("Keydi sawirka", "Save image"))
        var res = 1080
        var png = true
        root.addView(Ui.label(this, tr("Tayada", "Quality")))
        root.addView(Ui.choiceRow(this, listOf("720", "1080", "1440", "2160 (4K)"), 1) { k -> res = intArrayOf(720, 1080, 1440, 2160)[k] })
        root.addView(Ui.label(this, tr("Nooca", "Format")))
        root.addView(Ui.choiceRow(this, listOf("PNG", "JPG"), 0) { k -> png = k == 0 })
        val status = Ui.label(this, "")
        root.addView(status)
        val btn = Ui.button(this, tr("Keydi", "Save")) {}
        btn.setOnClickListener {
            btn.isEnabled = false
            status.text = tr("Waa la keydinayaa…", "Saving…")
            save()
            val copy = ProjectStore.fromJson(ProjectStore.toJson(project))
            io.execute {
                val result = runCatching { PhotoExporter.export(this, copy, res, png) }
                main.post {
                    result.onSuccess { (uri, file) ->
                        val mime = if (png) "image/png" else "image/jpeg"
                        status.text = tr("Waa la keydiyay: Gallery → Pictures/IjarJar", "Saved: Gallery → Pictures/IjarJar")
                        btn.visibility = View.GONE
                        root.addView(Ui.button(this, tr("Fur", "Open")) { openMedia(uri, file, mime) })
                        root.addView(Ui.button(this, tr("Wadaag", "Share"), false) { shareMedia(uri, file, mime) })
                    }.onFailure {
                        status.text = tr("Khalad: ", "Error: ") + (it.message ?: "")
                        btn.isEnabled = true
                    }
                }
            }
        }
        root.addView(btn)
        d.show()
    }

    private fun contentUri(uri: Uri?, file: File): Uri =
        uri ?: FileProvider.getUriForFile(this, "$packageName.files", file)

    private fun openMedia(uri: Uri?, file: File, mime: String) {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(contentUri(uri, file), mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(i) }.onFailure { toast(tr("App lagu furo lama helin", "No app found to open it")) }
    }

    private fun shareMedia(uri: Uri?, file: File, mime: String) {
        val i = Intent(Intent.ACTION_SEND).setType(mime)
            .putExtra(Intent.EXTRA_STREAM, contentUri(uri, file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(i, tr("Wadaag", "Share")))
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}

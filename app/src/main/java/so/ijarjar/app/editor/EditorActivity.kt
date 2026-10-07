package so.ijarjar.app.editor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
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
import so.ijarjar.app.ai.AiTools
import so.ijarjar.app.data.History
import so.ijarjar.app.data.ProjectStore
import so.ijarjar.app.export.Exporter
import so.ijarjar.app.export.PhotoExporter
import so.ijarjar.app.media.AnimatedSource
import so.ijarjar.app.media.MediaUtils
import so.ijarjar.app.media.Reverser
import so.ijarjar.app.media.Sfx
import so.ijarjar.app.model.Adjust
import so.ijarjar.app.model.AudioKind
import so.ijarjar.app.model.AudioTrack
import so.ijarjar.app.model.Clip
import so.ijarjar.app.model.Easing
import so.ijarjar.app.model.EffectKind
import so.ijarjar.app.model.Expression
import so.ijarjar.app.model.FilterPreset
import so.ijarjar.app.model.Keyframe
import so.ijarjar.app.model.Layer
import so.ijarjar.app.model.LayerAnim
import so.ijarjar.app.model.LayerKind
import so.ijarjar.app.model.LoopAnim
import so.ijarjar.app.model.MaskKind
import so.ijarjar.app.model.MediaKind
import so.ijarjar.app.model.Project
import so.ijarjar.app.model.ShapeKind
import so.ijarjar.app.model.SpeedCurve
import so.ijarjar.app.model.TextAnim
import so.ijarjar.app.model.TextLoop
import so.ijarjar.app.model.TransitionKind
import so.ijarjar.app.model.newId
import so.ijarjar.app.render.LayerRenderer
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

@UnstableApi
class EditorActivity : AppCompatActivity(), StageView.Listener, TimelineView.Listener, PanelHost {

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
    private lateinit var panelBox: FrameLayout
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
    private var panel: Panel? = null
    private var sfxPlayer: MediaPlayer? = null

    private val photo get() = project.isPhoto

    private fun dp(v: Float) = Ui.dp(this, v)

    // ------------------------------------------------------------------ pickers

    private val pickClips = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) addClips(uris)
    }
    private val pickOverlay = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) addOverlay(uris)
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
    private var lutTarget: Adjust? = null
    private val pickLut = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val a = lutTarget ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        keep(uri)
        io.execute {
            val lut = so.ijarjar.app.render.Lut.load(this, uri.toString())
            main.post {
                if (lut == null) { toast(tr("Faylka .cube lama akhrin karo", "Could not read the .cube file")); return@post }
                a.lutUri = uri.toString(); a.lutName = MediaUtils.displayName(this, uri); a.lutStrength = 1f
                commit()
                toast("LUT: ${a.lutName}")
            }
        }
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
                if (panel != null) { panel?.dismiss(); return }
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
        sfxPlayer?.release()
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
            iconTint = ColorStateList.valueOf(0xFF00201E.toInt())
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
        val gridBtn = Ui.iconButton(this, R.drawable.ic_grid, 22f, Ui.TEXT2) {}
        gridBtn.setOnClickListener {
            stage.showGrid = !stage.showGrid
            gridBtn.imageTintList = ColorStateList.valueOf(if (stage.showGrid) Ui.ACCENT else Ui.TEXT2)
        }
        right.addView(gridBtn)
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
            imageTintList = ColorStateList.valueOf(0xFF00201E.toInt())
            background = Ui.roundBg(Ui.TEXT, dp(8f).toFloat())
            setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
            setOnClickListener {
                insertAfter = -1
                pickClips.launch(arrayOf("video/*", "image/*"))
            }
        }
        tb.addView(add, FrameLayout.LayoutParams(dp(40f), dp(40f), Gravity.END or Gravity.TOP).apply { topMargin = dp(35f); marginEnd = dp(8f) })
        tlBox = tb
        root.addView(tb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220f)))

        // panels open here, in place of the timeline + tools (the preview stays visible)
        panelBox = FrameLayout(this).apply { visibility = View.GONE }
        root.addView(panelBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

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

    // ------------------------------------------------------------------ panels

    override fun attachPanel(panel: Panel) {
        this.panel?.let { if (it !== panel) it.dismiss() }
        this.panel = panel
        panelBox.removeAllViews()
        val maxH = (resources.displayMetrics.heightPixels * 0.42f).toInt()
        panelBox.addView(panel.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        panel.view.post {
            if (panel.view.height > maxH) panel.view.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxH)
        }
        panelBox.visibility = View.VISIBLE
        tlBox.visibility = View.GONE
        toolScroll.visibility = View.GONE
    }

    override fun detachPanel(panel: Panel) {
        if (this.panel !== panel) return
        this.panel = null
        panelBox.removeAllViews()
        panelBox.visibility = View.GONE
        if (!fullscreen) {
            if (!photo) tlBox.visibility = View.VISIBLE
            toolScroll.visibility = View.VISIBLE
        }
        stage.brush = null
        stage.colorPicker = null
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
        keyBtn.imageTintList = ColorStateList.valueOf(if (on || l.keyframes.isNotEmpty()) 0xFFFFCC00.toInt() else Ui.TEXT)
    }

    private fun togglePlay() {
        if (engine.isPlaying) engine.pause() else { engine.seekTo(timeMs); engine.play() }
        updatePlayButton()
    }

    private fun toggleFullscreen() {
        fullscreen = !fullscreen
        panel?.dismiss()
        tlBox.visibility = if (fullscreen) View.GONE else View.VISIBLE
        toolScroll.visibility = if (fullscreen) View.GONE else View.VISIBLE
        fullBtn.setImageResource(if (fullscreen) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen)
        stage.requestLayout()
    }

    private fun updateUndo() {
        undoBtn.alpha = if (history.canUndo()) 1f else 0.35f
        redoBtn.alpha = if (history.canRedo()) 1f else 0.35f
    }

    /** A picture of the current frame for previews in pickers. */
    private fun currentThumb(): Bitmap? {
        if (photo) return project.bgImageUri?.let { MediaUtils.loadBitmapCached(this, Uri.parse(it), 256) }
        val c = project.clips.getOrNull(project.clipIndexAt(timeMs)) ?: return null
        return if (c.kind == MediaKind.IMAGE) MediaUtils.loadBitmapCached(this, Uri.parse(c.uri), 256)
        else if (stage.mainTexture.isAvailable) stage.mainTexture.getBitmap(200, (200f * c.height / c.width.coerceAtLeast(1)).toInt().coerceAtLeast(16)) else null
    }

    private fun clipThumb(i: Int): Bitmap? {
        val c = project.clips.getOrNull(i) ?: return null
        return if (c.kind == MediaKind.IMAGE) MediaUtils.loadBitmapCached(this, Uri.parse(c.uri), 256)
        else MediaUtils.thumbnail(this, Uri.parse(c.uri), true, c.trimStartMs + c.trimmedMs / 2, 200)
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
                if (c.kind == MediaKind.VIDEO) t(R.drawable.ic_speed, tr("Xawaare", "Speed"), c.hasCurve || c.speed != 1f) { showSpeed(c) }
                t(R.drawable.ic_volume, tr("Cod", "Volume")) { showVolume(c) }
                t(R.drawable.ic_filter, tr("Filter", "Filters"), !c.adjust.isIdentity()) { showFilters(c.adjust) { for (o in project.clips) o.adjust = c.adjust.copy() } }
                t(R.drawable.ic_trim, tr("Gooy", "Trim")) { showTrim(c) }
                t(R.drawable.ic_canvas, tr("Shaashad", "Canvas")) { showCanvas(c) }
                if (s.index > 0) t(R.drawable.ic_transition, tr("Isbeddel", "Transition"), c.transition != TransitionKind.NONE) { showTransition(s.index) }
                if (c.kind == MediaKind.VIDEO) {
                    t(R.drawable.ic_reverse, tr("Dib u celi", "Reverse"), c.reversed) { reverseClip(s.index) }
                    t(R.drawable.ic_stabilize, tr("Deji gariirka", "Stabilize"), c.stab) { showStabilize(s.index) }
                    t(R.drawable.ic_voice, tr("Codka hagaaji", "Voice"), c.denoise > 0f || c.enhanceVoice) { showVoiceFx(c.denoise, c.enhanceVoice) { d, e -> c.denoise = d; c.enhanceVoice = e } }
                    t(R.drawable.ic_freeze, tr("Qabooji", "Freeze")) { freezeFrame(s.index) }
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
                    if (l.kind == LayerKind.TEXT) t(R.drawable.ic_pencil, tr("Qoraal", "Edit text")) { showTextEditor(l) }
                    if (l.kind == LayerKind.STICKER) t(R.drawable.ic_pencil, tr("Wax ka beddel", "Edit")) { showTextEditor(l) }
                    if (l.kind == LayerKind.SHAPE) t(R.drawable.ic_pencil, tr("Qaabka", "Style")) { showShapeEditor(l) }
                    if (l.kind == LayerKind.DRAW) t(R.drawable.ic_brush, tr("Sawir", "Draw")) { showDraw(l) }
                    if (l.isLottie) t(R.drawable.ic_text, tr("Qoraalka", "Text")) { showLottieText(l) }
                    t(R.drawable.ic_link, tr("Isku xir", "Link"), l.linkGroup != null) { showLink(l) }
                    if (l.linkGroup != null) t(R.drawable.ic_unlink, tr("Kala fur", "Unlink")) { unlink(l) }
                    if (!photo) t(R.drawable.ic_animation, tr("Dhaqdhaqaaq", "Animation"),
                        l.animIn != LayerAnim.NONE || l.animOut != LayerAnim.NONE || l.textIn != TextAnim.NONE || l.textOut != TextAnim.NONE ||
                            l.animLoop != LoopAnim.NONE || l.textLoop != TextLoop.NONE) { showAnimation(l) }
                    if (!photo) t(R.drawable.ic_keyframe, "Keyframe", l.keyframes.isNotEmpty()) { showKeyframes(l) }
                    if (!photo) t(R.drawable.ic_preset, "Presets") { showPresets(l) }
                    if (!photo && l.keyframes.isNotEmpty()) t(R.drawable.ic_curve, tr("Qalooc", "Curve")) { showCurve(l) }
                    if (!photo) t(R.drawable.ic_expression, tr("Expression", "Expression"), l.expr != Expression.NONE || l.motionBlur) { showExpression(l) }
                    if (l.isPicture()) {
                        t(R.drawable.ic_filter, tr("Filter", "Filters"), !l.adjust.isIdentity()) { showFilters(l.adjust, null) }
                        t(R.drawable.ic_chroma, tr("Shaashad cagaar", "Chroma key"), l.chroma) { showChroma(l) }
                        t(R.drawable.ic_mask, tr("Maaskaro", "Mask"), l.mask != MaskKind.NONE) { showMask(l) }
                    }
                    if (l.kind == LayerKind.IMAGE) {
                        t(R.drawable.ic_ai, "AI") { showAiForLayer(l) }
                        t(R.drawable.ic_crop, tr("Jar", "Crop"), l.hasCrop()) { showCrop(l) }
                        t(R.drawable.ic_replace, tr("Beddel", "Replace")) { replaceLayerImage(l) }
                    }
                    if (l.isPicture() || l.kind == LayerKind.SHAPE) t(R.drawable.ic_outline, tr("Xariiq & hadh", "Outline"), l.outlineColor != 0 || l.shadow) { showOutline(l) }
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
                    t(R.drawable.ic_reset, tr("Dib u celi", "Reset")) {
                        l.scale = 1f; l.rotation = 0f; l.cx = 0.5f; l.cy = 0.5f; l.stretchX = 1f; l.stretchY = 1f; l.keyframes.clear(); commit()
                    }
                }
                t(R.drawable.ic_copy, tr("Nuqul", "Duplicate")) { duplicateLayer(l) }
                t(R.drawable.ic_delete, tr("Tirtir", "Delete")) { deleteLayer(l) }
            }
            s is TimelineView.Sel.AudioSel && selectedAudio() != null -> {
                val a = selectedAudio()!!
                back()
                t(R.drawable.ic_volume, tr("Cod", "Volume")) { showAudioVolume(a) }
                t(R.drawable.ic_voice, tr("Codka hagaaji", "Voice"), a.denoise > 0f || a.enhanceVoice) { showVoiceFx(a.denoise, a.enhanceVoice) { d, e -> a.denoise = d; a.enhanceVoice = e } }
                t(R.drawable.ic_split, tr("Kala jar", "Split")) { splitAudio(a) }
                t(R.drawable.ic_start_here, tr("Bilow halkan", "Start here")) { a.startMs = timeMs; commit() }
                t(R.drawable.ic_copy, tr("Nuqul", "Duplicate")) { val b = a.copy(); b.startMs = a.endMs; project.audios.add(b); commit() }
                t(R.drawable.ic_delete, tr("Tirtir", "Delete")) { project.audios.remove(a); setSelection(null); commit() }
            }
            photo -> {
                t(R.drawable.ic_background, tr("Gadaal", "Background")) { showBackground() }
                t(R.drawable.ic_ai, "AI") { showAiForBackground() }
                t(R.drawable.ic_filter, tr("Filter", "Filters"), !project.bgAdjust.isIdentity()) { showFilters(project.bgAdjust, null) }
                t(R.drawable.ic_text, tr("Qoraal", "Text")) { addText() }
                t(R.drawable.ic_sticker, "Sticker") { showStickers() }
                t(R.drawable.ic_image_add, tr("Sawir", "Image")) { pickOverlay.launch(arrayOf("image/*", "application/json", "application/octet-stream")) }
                t(R.drawable.ic_shape, tr("Qaabab", "Shapes")) { showShapes() }
                t(R.drawable.ic_brush, tr("Sawir gacmeed", "Draw")) { startDrawing() }
                t(R.drawable.ic_link, tr("Isku xir", "Link")) { showLink(null) }
                t(R.drawable.ic_layers, tr("Layer-ada", "Layers")) { showLayers() }
                t(R.drawable.ic_ratio, tr("Cabbir", "Size")) { showAspect() }
                t(R.drawable.ic_grid, tr("Shabag", "Grid"), stage.showGrid) { stage.showGrid = !stage.showGrid; buildTools() }
                t(R.drawable.ic_send, tr("U dir muuqaal", "Send to video")) { sendToVideo() }
            }
            else -> {
                t(R.drawable.ic_edit, tr("Wax ka beddel", "Edit")) {
                    if (project.clips.isNotEmpty()) setSelection(TimelineView.Sel.ClipSel(project.clipIndexAt(timeMs)))
                }
                t(R.drawable.ic_audio, tr("Cod", "Audio")) { showAudioMenu() }
                t(R.drawable.ic_sfx, tr("Dhawaaqyo", "Sound FX")) { showSfx() }
                t(R.drawable.ic_text, tr("Qoraal", "Text")) { addText() }
                t(R.drawable.ic_caption, tr("Qoraal-hoosaad", "Captions")) { showCaptions() }
                t(R.drawable.ic_sticker, "Sticker") { showStickers() }
                t(R.drawable.ic_overlay, "Overlay") { showOverlayMenu() }
                t(R.drawable.ic_effects, tr("Saameyn", "Effects")) { showEffects(null) }
                t(R.drawable.ic_shape, tr("Qaabab", "Shapes")) { showShapes() }
                t(R.drawable.ic_brush, tr("Sawir gacmeed", "Draw")) { startDrawing() }
                t(R.drawable.ic_filter, tr("Filter", "Filters")) {
                    if (project.clips.isNotEmpty()) {
                        val c = project.clips[project.clipIndexAt(timeMs)]
                        showFilters(c.adjust) { for (o in project.clips) o.adjust = c.adjust.copy() }
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
        panel?.dismiss()
        val s = history.undo() ?: return
        project = ProjectStore.fromJson(s); reload(); scheduleSave()
    }

    private fun redo() {
        panel?.dismiss()
        val s = history.redo() ?: return
        project = ProjectStore.fromJson(s); reload(); scheduleSave()
    }

    /** Re-apply the project to the preview without adding an undo step. */
    private fun live() {
        engine.refreshVolumes()
        stage.refresh()
        timeline.invalidate()
    }

    // ------------------------------------------------------------------ listeners

    override fun onLayerSelected(layer: Layer?) {
        if (stage.brush != null) return
        setSelection(layer?.let { TimelineView.Sel.LayerSel(it.id) })
    }

    override fun onLayerTransforming() {}

    override fun onLayerTransformed() { if (panel == null) commit() else { history.push(ProjectStore.toJson(project)); updateUndo(); scheduleSave() } }

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

    override fun onKeyframeTap(timeMs: Long) {
        if (engine.isPlaying) { engine.pause(); updatePlayButton() }
        this.timeMs = timeMs
        timeline.timeMs = timeMs
        engine.seekTo(timeMs)
    }

    override fun onTimelineEditing() { stage.refresh() }

    override fun onTimelineEdited() { commit() }

    // ------------------------------------------------------------------ helpers for panels

    /** Adds a horizontal row of looping preview tiles. */
    private fun <T> tileRow(root: LinearLayout, items: List<T>, isSel: (T) -> Boolean, label: (T) -> String,
                            tile: (T) -> LoopTile, size: Float = 64f, pick: (T) -> Unit) {
        val (sv, row) = Ui.hrow(this)
        val tiles = ArrayList<LoopTile>()
        for (it in items) {
            val tv = tile(it)
            tv.selectedTile = isSel(it)
            tiles.add(tv)
            row.addView(tileWithLabel(this, tv, label(it), size) {
                pick(it)
                for ((i, x) in tiles.withIndex()) x.selectedTile = isSel(items[i])
            })
        }
        root.addView(sv)
    }

    private fun chipRow(root: LinearLayout, items: List<Pair<Int, String>>, pick: (Int) -> Unit) {
        val (sv, row) = Ui.hrow(this)
        for ((i, it) in items.withIndex()) {
            row.addView(Ui.iconChip(this, it.first, it.second) { pick(i) },
                LinearLayout.LayoutParams(dp(78f), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6f) })
        }
        root.addView(sv)
    }

    private fun buttonRow(root: LinearLayout, vararg buttons: Pair<String, () -> Unit>) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((i, b) in buttons.withIndex()) {
            row.addView(Ui.button(this, b.first, i == 0 && buttons.size == 1, b.second),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i < buttons.size - 1) marginEnd = dp(6f) })
        }
        root.addView(row)
    }

    private fun sampleText(l: Layer?): String {
        val t = l?.text?.replace("\n", " ")?.trim().orEmpty()
        return if (l?.kind == LayerKind.TEXT && t.isNotEmpty()) t.take(8) else "Abc"
    }

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
        val c = project.clips.getOrNull(replaceIndex) ?: return
        keep(uri)
        io.execute {
            val info = MediaUtils.probe(this, uri)
            main.post {
                if (info == null) { toast(tr("Faylka lama furi karo", "Could not open the file")); return@post }
                val len = c.trimmedMs
                c.uri = uri.toString(); c.width = info.width; c.height = info.height; c.reversed = false; c.originalUri = null
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
            val srcSplit = c.trimStartMs + so.ijarjar.app.render.SpeedMap.outToSrc(c, local)
            b.trimStartMs = srcSplit
            c.trimEndMs = srcSplit
            if (c.hasCurve) { c.curve = SpeedCurve.NONE; b.curve = SpeedCurve.NONE }
        }
        project.clips.add(i + 1, b)
        setSelection(TimelineView.Sel.ClipSel(i + 1))
        commit()
    }

    private fun duplicateClip(i: Int) { project.clips.add(i + 1, project.clips[i].copy()); commit() }

    private fun moveClip(i: Int, d: Int) {
        val j = i + d
        if (j !in project.clips.indices) return
        val c = project.clips.removeAt(i)
        project.clips.add(j, c)
        setSelection(TimelineView.Sel.ClipSel(j))
        commit()
    }

    private fun deleteClip(i: Int) { project.clips.removeAt(i); setSelection(null); commit() }

    private fun reverseClip(i: Int) {
        val c = project.clips[i]
        if (c.reversed) {
            // back to the original
            val parts = c.originalUri?.split("|")
            if (parts != null && parts.size >= 4) {
                c.uri = parts[0]; c.trimStartMs = parts[1].toLong(); c.trimEndMs = parts[2].toLong(); c.sourceDurationMs = parts[3].toLong()
            }
            c.reversed = false; c.originalUri = null
            commit(); return
        }
        engine.pause(); updatePlayButton()
        val (d, root) = Ui.sheet(this, tr("Dib u celin (reverse)", "Reverse"))
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        root.addView(Ui.label(this, tr("Muuqaalka waa la rogayaa… tani waxay qaadan kartaa daqiiqad.", "Reversing the clip… this can take a minute.")))
        root.addView(bar)
        val rev = Reverser(this, Uri.parse(c.uri), c.trimStartMs, c.trimEndMs)
        d.setOnDismissListener { rev.cancel() }
        d.show()
        rev.start(object : Reverser.Callback {
            override fun onProgress(percent: Int) { bar.progress = percent }
            override fun onDone(file: File, durationMs: Long) {
                c.originalUri = "${c.uri}|${c.trimStartMs}|${c.trimEndMs}|${c.sourceDurationMs}"
                c.uri = Uri.fromFile(file).toString()
                c.trimStartMs = 0; c.trimEndMs = durationMs; c.sourceDurationMs = durationMs
                c.reversed = true
                d.setOnDismissListener { }
                d.dismiss()
                commit()
                toast(tr("Waa la rogay", "Reversed"))
            }
            override fun onError(message: String) { d.setOnDismissListener { }; d.dismiss(); toast(tr("Khalad: ", "Error: ") + message) }
        })
    }

    private fun freezeFrame(i: Int) {
        val c = project.clips[i]
        val local = (timeMs - project.clipStartMs(i)).coerceIn(0, c.outDurationMs - 1)
        val srcT = c.trimStartMs + so.ijarjar.app.render.SpeedMap.outToSrc(c, local)
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
        Ui.tabs(this, root, listOf(
            tr("Caadi", "Normal") to { body: LinearLayout ->
                val lbl = Ui.label(this, "${TimelineView.trim(c.speed)}x")
                body.addView(lbl)
                body.addView(Ui.slider(this, 0.25f, 4f, c.speed, 0.25f) { v -> c.speed = v; c.curve = SpeedCurve.NONE; lbl.text = "${TimelineView.trim(v)}x" })
                body.addView(Ui.choiceRow(this, listOf("0.5x", "1x", "1.5x", "2x", "3x"), -1) { k ->
                    c.speed = floatArrayOf(0.5f, 1f, 1.5f, 2f, 3f)[k]; c.curve = SpeedCurve.NONE; lbl.text = "${TimelineView.trim(c.speed)}x"
                })
            },
            tr("Qalooc", "Curve") to { body: LinearLayout ->
                val custom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                fun showCustom() {
                    custom.removeAllViews()
                    if (c.curve != SpeedCurve.CUSTOM) return
                    for (k in 0 until 5) custom.addView(Ui.sliderRow(this, tr("Barta ${k + 1}", "Point ${k + 1}"), 0.1f, 10f, c.curvePoints[k]) { v -> c.curvePoints[k] = v })
                }
                chipRow(body, SpeedCurve.entries.map { (if (it == SpeedCurve.NONE) R.drawable.ic_close else R.drawable.ic_curve) to it.label }) { k ->
                    c.curve = SpeedCurve.entries[k]
                    if (c.curve != SpeedCurve.NONE && c.curve != SpeedCurve.CUSTOM) for (j in 0 until 5) c.curvePoints[j] = c.curve.points[j]
                    showCustom()
                    toast(c.curve.label)
                }
                body.addView(custom)
                showCustom()
            }
        ), if (c.hasCurve) 1 else 0)
        d.show()
    }

    private fun showVolume(c: Clip) {
        val (d, root) = Ui.sheet(this, tr("Codka", "Volume")) { commit() }
        val lbl = Ui.label(this, "${(c.volume * 100).toInt()}%")
        root.addView(lbl)
        root.addView(Ui.slider(this, 0f, 2f, c.volume, 0.05f) { v -> c.volume = v; lbl.text = "${(v * 100).toInt()}%"; live() })
        buttonRow(root,
            (if (c.volume == 0f) tr("Fur codka", "Unmute") else tr("Aamusi", "Mute")) to { c.volume = if (c.volume == 0f) 1f else 0f; d.dismiss() },
            tr("Dhammaan", "Apply to all") to { for (o in project.clips) o.volume = c.volume; d.dismiss() })
        d.show()
    }

    /** Filters (preview tiles), adjustments and LUT import for a clip, picture layer or photo background. */
    private fun showFilters(a: Adjust, applyAll: (() -> Unit)?) {
        val (d, root) = Ui.sheet(this, tr("Filter", "Filters")) { commit() }
        val thumb = currentThumb()
        Ui.tabs(this, root, listOf(
            tr("Filter", "Filters") to { body: LinearLayout ->
                tileRow(body, FilterPreset.entries, { it == a.preset }, { it.label }, { FilterTile(this, thumb, it) }, 60f) { p -> a.preset = p; live() }
                if (applyAll != null) buttonRow(body, tr("Ku dabaq dhammaan", "Apply to all") to { applyAll(); d.dismiss() })
            },
            tr("Hagaaji", "Adjust") to { body: LinearLayout ->
                body.addView(Ui.sliderRow(this, tr("Iftiin", "Brightness"), -1f, 1f, a.brightness) { a.brightness = it; live() })
                body.addView(Ui.sliderRow(this, tr("Kala duwanaan", "Contrast"), -1f, 1f, a.contrast) { a.contrast = it; live() })
                body.addView(Ui.sliderRow(this, tr("Midab", "Saturation"), -1f, 1f, a.saturation) { a.saturation = it; live() })
                body.addView(Ui.sliderRow(this, tr("Diirimaad", "Temperature"), -1f, 1f, a.temperature) { a.temperature = it; live() })
                body.addView(Ui.sliderRow(this, tr("Midab-dhexe", "Tint"), -1f, 1f, a.tint) { a.tint = it; live() })
                body.addView(Ui.sliderRow(this, tr("Qariin", "Blur"), 0f, 1f, a.blur) { a.blur = it; live() })
                buttonRow(body, tr("Dib u celi", "Reset") to {
                    a.brightness = 0f; a.contrast = 0f; a.saturation = 0f; a.temperature = 0f; a.tint = 0f; a.blur = 0f; a.preset = FilterPreset.NONE; d.dismiss()
                })
            },
            "LUT" to { body: LinearLayout ->
                body.addView(Ui.label(this, if (a.lutUri != null) "LUT: ${a.lutName}" else tr("Ku soo dar fayl .cube (LUT) ah", "Import a .cube LUT file")))
                buttonRow(body, tr("Soo gali .cube", "Import .cube") to { lutTarget = a; d.dismiss(); pickLut.launch(arrayOf("*/*")) })
                if (a.lutUri != null) {
                    body.addView(Ui.sliderRow(this, tr("Xoog", "Strength"), 0f, 1f, a.lutStrength) { a.lutStrength = it; live() })
                    buttonRow(body, tr("Ka saar LUT", "Remove LUT") to { a.lutUri = null; a.lutName = ""; d.dismiss() })
                }
            }
        ))
        d.show()
    }

    private fun showTrim(c: Clip) {
        val (d, root) = Ui.sheet(this, tr("Gooy (trim)", "Trim")) { commit() }
        val lbl = Ui.label(this, "")
        fun upd() { lbl.text = TimelineView.fmt(c.trimStartMs) + " → " + TimelineView.fmt(c.trimEndMs) + "   (" + "%.1f".format(c.trimmedMs / 1000f) + "s)" }
        upd()
        root.addView(lbl)
        if (c.kind == MediaKind.IMAGE) {
            root.addView(Ui.slider(this, 0.5f, 30f, (c.trimmedMs / 1000f).coerceIn(0.5f, 30f), 0.5f) { v -> c.trimStartMs = 0; c.trimEndMs = (v * 1000).toLong(); c.sourceDurationMs = c.trimEndMs; upd() })
        } else {
            val maxMs = c.sourceDurationMs.toFloat().coerceAtLeast(200f)
            root.addView(RangeSlider(this).apply {
                valueFrom = 0f; valueTo = maxMs
                values = listOf(c.trimStartMs.toFloat().coerceIn(0f, maxMs), c.trimEndMs.toFloat().coerceIn(0f, maxMs))
                minSeparation = 100f
                addOnChangeListener { s, _, fromUser ->
                    if (fromUser) { c.trimStartMs = s.values[0].toLong(); c.trimEndMs = s.values[1].toLong(); upd() }
                }
            })
        }
        d.show()
    }

    private fun showCanvas(c: Clip) {
        val (d, root) = Ui.sheet(this, tr("Shaashadda", "Canvas")) { commit() }
        root.addView(Ui.label(this, tr("Farta ku dhaqaaji, ku weyneey ama ku wareeji muuqaalka korka.", "Drag, pinch or twist the video on the preview.")))
        root.addView(Ui.sliderRow(this, tr("Weyneyn", "Zoom"), 0.2f, 4f, c.tScale.coerceIn(0.2f, 4f)) { c.tScale = it; live() })
        root.addView(Ui.sliderRow(this, tr("Wareejin", "Rotation"), 0f, 359f, c.tRot.coerceIn(0f, 359f)) { c.tRot = it; live() })
        buttonRow(root, tr("90°", "90°") to { c.tRot = (c.tRot + 90f) % 360f; d.dismiss() }, tr("Muraayad", "Mirror") to { c.mirror = !c.mirror; d.dismiss() },
            tr("Buuxi", "Fill") to {
                val ca = c.width.toFloat().coerceAtLeast(1f) / c.height.coerceAtLeast(1)
                val r = project.aspectRatio()
                c.tScale = maxOf(ca / r, r / ca); c.tX = 0f; c.tY = 0f; c.tRot = 0f; d.dismiss()
            },
            tr("Ku habee", "Fit") to { c.tScale = 1f; c.tX = 0f; c.tY = 0f; c.tRot = 0f; c.mirror = false; d.dismiss() })
        buttonRow(root, tr("Ku dabaq dhammaan", "Apply to all") to {
            for (o in project.clips) { o.tScale = c.tScale; o.tRot = c.tRot; o.tX = c.tX; o.tY = c.tY; o.mirror = c.mirror }
            d.dismiss()
        })
        d.show()
    }

    private fun showTransition(index: Int) {
        val c = project.clips.getOrNull(index) ?: return
        val (d, root) = Ui.sheet(this, tr("Isbeddelka (transition)", "Transition")) { commit() }
        val a = clipThumb(index - 1); val b = clipThumb(index)
        tileRow(root, TransitionKind.entries, { it == c.transition }, { it.label }, { TransitionTile(this, a, b, it) }) { k -> c.transition = k; live() }
        root.addView(Ui.sliderRow(this, tr("Mudada", "Duration"), 0.2f, 2f, (c.transitionMs / 1000f).coerceIn(0.2f, 2f), 0.1f) { c.transitionMs = (it * 1000).toLong(); live() })
        buttonRow(root, tr("Ku dabaq dhammaan", "Apply to all") to {
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
        val emojis = listOf("😀", "😂", "😍", "🥰", "😎", "🤩", "😭", "😡", "🫨", "👍", "👏", "🙏", "💪", "🔥", "✨", "💯", "❤️",
            "💔", "⭐", "🎉", "🎁", "🎵", "📌", "✅", "❌", "⚡", "🌙", "☀️", "🌸", "🇸🇴", "🕌", "📿", "🤲", "👀", "💥", "🚀", "🏆",
            "🎂", "🌹", "💎", "👑", "📢", "💡", "📍", "🎬", "📸", "🎤", "⚽", "🌍", "🤯", "🥳", "😴", "🤔", "👋", "🙌", "💫", "🌈")
        val (d, root) = Ui.sheet(this, "Sticker")
        val sv = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val grid = GridLayout(this).apply { rowCount = 3; orientation = GridLayout.VERTICAL }
        for (e in emojis) {
            grid.addView(Ui.text(this, e, 30f).apply {
                gravity = Gravity.CENTER
                setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
                setOnClickListener {
                    val l = Layer(kind = LayerKind.STICKER, text = e, textSizeFrac = 0.18f, bold = false)
                    newLayerTimes(l, 3000)
                    addLayer(l)
                }
            })
        }
        sv.addView(grid)
        root.addView(sv)
        d.show()
    }

    private fun showShapes() {
        val (d, root) = Ui.sheet(this, tr("Qaabab", "Shapes"))
        chipRow(root, ShapeKind.entries.map { R.drawable.ic_shape to it.label }) { k ->
            val kind = ShapeKind.entries[k]
            val l = Layer(kind = LayerKind.SHAPE, shape = kind, textColor = Ui.ACCENT, baseW = 0.4f)
            l.contentAspect = when (kind) {
                ShapeKind.LINE -> 0.05f
                ShapeKind.ARROW -> 0.45f
                ShapeKind.RECT, ShapeKind.ROUND_RECT -> 0.65f
                ShapeKind.BUBBLE -> 0.8f
                else -> 1f
            }
            if (kind == ShapeKind.LINE) l.textColor = 0xFFFFFFFF.toInt()
            newLayerTimes(l, 3000)
            d.dismiss()
            addLayer(l)
        }
        root.addView(Ui.label(this, tr("Dhinacyada sanduuqa ku jiid si aad u beddesho dhererka iyo ballaca.", "Drag the side handles to change width and height.")))
        d.show()
    }

    /** Overlay import: video, picture, GIF, PNG sequence (several PNGs) or Lottie (.json / .lottie). */
    private fun addOverlay(uris: List<Uri>) {
        uris.forEach { keep(it) }
        io.execute {
            val names = uris.map { MediaUtils.displayName(this, it) }
            main.post {
                val lower = names.map { it.lowercase() }
                when {
                    lower.any { it.endsWith(".mogrt") || it.endsWith(".aep") || it.endsWith(".prproj") } -> MaterialAlertDialogBuilder(this)
                        .setTitle(tr("Faylkan si toos ah uma shaqeeyo", "This file can't be opened directly"))
                        .setMessage(tr("Faylasha .mogrt iyo .aep waxay u baahan yihiin Adobe. After Effects ka dhoofi sidan:\n\n• Lottie (.json) adigoo isticmaalaya Bodymovin — qoraalka waad beddeli kartaa\n• PNG sequence (transparent)\n• GIF\n\nKadib halkan ku soo gali.",
                            ".mogrt and .aep files need Adobe software. From After Effects export as:\n\n• Lottie (.json) with Bodymovin — the text stays editable\n• PNG sequence (transparent)\n• GIF\n\nThen import it here."))
                        .setPositiveButton("OK", null).show()
                    uris.size > 1 && lower.all { it.endsWith(".png") || it.endsWith(".webp") } -> addSequence(uris.sortedBy { MediaUtils.displayName(this, it) }, names.first())
                    uris.size > 1 -> uris.forEach { addMediaLayer(it) }
                    AnimatedSource.isLottieName(lower[0]) || lower[0].endsWith(".zip") -> addLottie(uris[0], names[0])
                    lower[0].endsWith(".gif") -> addGif(uris[0], names[0])
                    else -> {
                        if (lower[0].endsWith(".mov")) toast(tr("MOV: hufnaanta (alpha) Android kuma shaqeyso — isticmaal PNG sequence ama Lottie.",
                            "MOV: Android can't play transparency — use a PNG sequence or Lottie."))
                        addMediaLayer(uris[0])
                    }
                }
            }
        }
    }

    private fun fitBase(l: Layer, w: Int, h: Int) {
        l.contentAspect = h.toFloat().coerceAtLeast(1f) / w.coerceAtLeast(1)
        l.srcAspect = l.contentAspect
        val r = project.aspectRatio()
        l.baseW = if (l.contentAspect * 0.6f * r > 0.6f) 0.6f / (l.contentAspect * r) else 0.6f
    }

    private fun addSequence(uris: List<Uri>, name: String) {
        val l = Layer(kind = LayerKind.ANIMATED, name = tr("PNG taxane", "PNG sequence") + " · " + name, frames = uris.map { it.toString() }.toMutableList(), fps = 25f)
        io.execute {
            val sz = AnimatedSource.size(this, l)
            main.post {
                if (sz != null) fitBase(l, sz.first, sz.second)
                newLayerTimes(l, (uris.size * 1000L / 25).coerceAtLeast(500))
                addLayer(l)
                toast(tr("${uris.size} sawir ayaa la isku xiray (25 fps)", "${uris.size} frames joined (25 fps)"))
            }
        }
    }

    private fun addGif(uri: Uri, name: String) {
        val l = Layer(kind = LayerKind.ANIMATED, name = name, uri = uri.toString())
        io.execute {
            val sz = AnimatedSource.size(this, l)
            val dur = AnimatedSource.gifDurationMs(this, uri.toString())
            main.post {
                if (sz == null) { addMediaLayer(uri); return@post }
                fitBase(l, sz.first, sz.second)
                newLayerTimes(l, maxOf(dur, 2000))
                addLayer(l)
            }
        }
    }

    private fun addLottie(uri: Uri, name: String) {
        val l = Layer(kind = LayerKind.ANIMATED, name = name, uri = uri.toString(), isLottie = true)
        io.execute {
            val sz = AnimatedSource.size(this, l)
            val dur = AnimatedSource.lottieDurationMs(this, uri.toString())
            main.post {
                if (sz == null) { toast(tr("Faylka Lottie lama akhrin karo", "Could not read the Lottie file")); return@post }
                fitBase(l, sz.first, sz.second)
                l.baseW = 0.8f
                newLayerTimes(l, maxOf(dur, 1000))
                addLayer(l)
            }
        }
    }

    private fun showLottieText(l: Layer) {
        val (d, root) = Ui.sheet(this, tr("Qoraalka Lottie", "Lottie text")) { commit() }
        root.addView(Ui.label(this, tr("Qoraalka cusub wuxuu beddelayaa qoraalka animation-ka (bannaan = kii asalka).", "New text replaces the animation's text (empty = original).")))
        root.addView(editText(l.lottieText, tr("Qor halkan…", "Type here…")) { l.lottieText = it; live() })
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
                fitBase(l, info.width, info.height)
                if (info.isVideo) {
                    l.sourceDurationMs = info.durationMs
                    newLayerTimes(l, info.durationMs)
                    if (!photo) l.endMs = l.startMs + info.durationMs
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
                l.contentAspect = info.height.toFloat() / info.width; l.srcAspect = l.contentAspect
                l.cropL = 0f; l.cropT = 0f; l.cropR = 0f; l.cropB = 0f
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
        TextPreset("Barafle", "Ice") { it.textColor = 0xFFFFFFFF.toInt(); it.textColor2 = 0xFF8FD3FF.toInt(); it.strokeColor = 0xFF1565C0.toInt(); it.strokeWidth = 0.08f; it.depth = 0.3f; it.depthColor = 0xFF0D47A1.toInt(); it.bgColor = 0 },
        TextPreset("Apple", "Apple") { it.textColor = 0xFFFFFFFF.toInt(); it.textColor2 = 0; it.strokeColor = 0; it.bgColor = 0; it.shadow = false; it.depth = 0f; it.font = 7; it.bold = true; it.textIn = TextAnim.APPLE; it.textOut = TextAnim.APPLE }
    )

    private fun copyTextStyle(from: Layer, to: Layer) {
        to.textColor = from.textColor; to.textColor2 = from.textColor2; to.strokeColor = from.strokeColor
        to.strokeWidth = from.strokeWidth; to.bgColor = from.bgColor; to.textSizeFrac = from.textSizeFrac
        to.bold = from.bold; to.font = from.font; to.align = from.align; to.shadow = from.shadow
        to.depth = from.depth; to.depthColor = from.depthColor; to.letterSpacing = from.letterSpacing
        to.cx = from.cx; to.cy = from.cy; to.scale = from.scale; to.animIn = from.animIn; to.animOut = from.animOut
        to.textIn = from.textIn; to.textOut = from.textOut; to.textLoop = from.textLoop; to.highlightColor = from.highlightColor
        to.animInMs = from.animInMs; to.animOutMs = from.animOutMs
    }

    private fun editText(value: String, hint: String, onChange: (String) -> Unit): EditText = EditText(this).apply {
        setText(value)
        setTextColor(Ui.TEXT)
        setHintTextColor(Ui.TEXT2)
        this.hint = hint
        background = Ui.roundBg(Ui.SURFACE2, dp(10f).toFloat())
        setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
        setSelection(text.length)
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { onChange(s?.toString() ?: "") }
        })
    }

    private fun showTextEditor(l: Layer) {
        val (d, root) = Ui.sheet(this, if (l.kind == LayerKind.STICKER) "Sticker" else tr("Qoraal", "Text")) { commit() }
        root.addView(editText(l.text, tr("Qor halkan…", "Type here…")) { l.text = it; live() })
        if (l.kind == LayerKind.STICKER) {
            root.addView(Ui.sliderRow(this, tr("Cabbirka", "Size"), 0.02f, 0.4f, l.textSizeFrac.coerceIn(0.02f, 0.4f)) { l.textSizeFrac = it; live() })
            d.show(); return
        }
        Ui.tabs(this, root, listOf(
            tr("Qaab", "Styles") to { body: LinearLayout ->
                val (sv, row) = Ui.hrow(this)
                for (p in textPresets) {
                    val sample = Layer(kind = LayerKind.TEXT, text = "Aa", textSizeFrac = 0.34f).also { p.fn(it); it.textIn = TextAnim.NONE; it.textOut = TextAnim.NONE }
                    val tile = object : LoopTile(this, 1000) {
                        override fun animated() = false
                        override fun drawContent(canvas: android.graphics.Canvas, t: Long) {
                            sample.startMs = 0; sample.endMs = 1000
                            LayerRenderer.draw(context, canvas, sample, 500, width, height, null, 256)
                        }
                    }
                    row.addView(tileWithLabel(this, tile, tr(p.so, p.en), 60f) { p.fn(l); live() })
                }
                body.addView(sv)
                body.addView(Ui.sliderRow(this, tr("Cabbirka", "Size"), 0.02f, 0.3f, l.textSizeFrac.coerceIn(0.02f, 0.3f)) { l.textSizeFrac = it; live() })
            },
            tr("Farta", "Font") to { body: LinearLayout ->
                body.addView(Ui.choiceRow(this, LayerRenderer.FONTS, l.font) { l.font = it; live() })
                body.addView(Ui.choiceRow(this, listOf(tr("Adag", "Bold"), tr("Caadi", "Regular")), if (l.bold) 0 else 1) { l.bold = it == 0; live() })
                body.addView(Ui.choiceRow(this, listOf(tr("Bidix", "Left"), tr("Dhexe", "Center"), tr("Midig", "Right")), l.align) { l.align = it; live() })
                body.addView(Ui.sliderRow(this, tr("Kala fogaan", "Spacing"), -0.1f, 0.5f, l.letterSpacing.coerceIn(-0.1f, 0.5f)) { l.letterSpacing = it; live() })
            },
            tr("Midab", "Colour") to { body: LinearLayout ->
                body.addView(Ui.colorRow(this, l.textColor, false) { l.textColor = it; live() })
                body.addView(Ui.label(this, tr("Midab labaad (gradient)", "Second colour (gradient)")))
                body.addView(Ui.colorRow(this, l.textColor2, true) { l.textColor2 = it; live() })
                body.addView(Ui.label(this, tr("Midabka iftiinka (karaoke)", "Highlight colour (karaoke)")))
                body.addView(Ui.colorRow(this, l.highlightColor, false) { l.highlightColor = it; live() })
            },
            tr("Xariiq", "Outline") to { body: LinearLayout ->
                body.addView(Ui.colorRow(this, l.strokeColor, true) { l.strokeColor = it; live() })
                body.addView(Ui.sliderRow(this, tr("Ballac", "Width"), 0.02f, 0.4f, l.strokeWidth.coerceIn(0.02f, 0.4f)) { l.strokeWidth = it; live() })
                body.addView(Ui.label(this, tr("Gadaal", "Background")))
                body.addView(Ui.colorRow(this, l.bgColor, true) { l.bgColor = it; live() })
            },
            "3D" to { body: LinearLayout ->
                body.addView(Ui.sliderRow(this, tr("Qoto-dheer", "Depth"), 0f, 1f, l.depth) { l.depth = it; live() })
                body.addView(Ui.colorRow(this, l.depthColor, false) { l.depthColor = it; live() })
                body.addView(Ui.choiceRow(this, listOf(tr("Hadh: Haa", "Shadow on"), tr("Hadh: Maya", "Shadow off")), if (l.shadow) 0 else 1) { l.shadow = it == 0; live() })
            },
            tr("Dhaqdhaqaaq", "Animate") to { body: LinearLayout ->
                body.addView(Ui.label(this, tr("Gal (letter / word)", "In (letters / words)")))
                tileRow(body, TextAnim.entries, { it == l.textIn }, { it.label }, { k -> AnimTile(this, sampleText(l)) { it.textIn = k; it.textOut = TextAnim.NONE } }, 60f) { k ->
                    l.textIn = k; if (k != TextAnim.NONE) l.animIn = LayerAnim.NONE; previewAnim(l, true)
                }
                body.addView(Ui.label(this, tr("Wareeg", "Loop")))
                tileRow(body, TextLoop.entries, { it == l.textLoop }, { it.label }, { k -> AnimTile(this, sampleText(l)) { it.textLoop = k; it.endMs = 2600 } }, 60f) { k ->
                    l.textLoop = k; live()
                }
            }
        ))
        if (l.isCaption) buttonRow(root, tr("U dabaq dhammaan qoraal-hoosaadyada", "Apply to all captions") to {
            for (o in project.layers) if (o.isCaption && o.id != l.id) copyTextStyle(l, o)
            d.dismiss()
        })
        d.show()
    }

    private fun showShapeEditor(l: Layer) {
        val (d, root) = Ui.sheet(this, l.shape.label) { commit() }
        Ui.tabs(this, root, listOf(
            tr("Qaab", "Shape") to { body: LinearLayout ->
                body.addView(Ui.choiceRow(this, ShapeKind.entries.map { it.label }, ShapeKind.entries.indexOf(l.shape)) { l.shape = ShapeKind.entries[it]; live() })
                body.addView(Ui.sliderRow(this, tr("Ballac", "Width"), 0.05f, 3f, l.stretchX.coerceIn(0.05f, 3f)) { v ->
                    val p = LayerRenderer.basePose(l, timeMs); p.sx = v; LayerRenderer.writePose(l, timeMs, p); live()
                })
                body.addView(Ui.sliderRow(this, tr("Dherer", "Height"), 0.05f, 3f, l.stretchY.coerceIn(0.05f, 3f)) { v ->
                    val p = LayerRenderer.basePose(l, timeMs); p.sy = v; LayerRenderer.writePose(l, timeMs, p); live()
                })
            },
            tr("Midab", "Fill") to { body: LinearLayout ->
                body.addView(Ui.colorRow(this, l.textColor, false) { l.textColor = it; live() })
                body.addView(Ui.label(this, tr("Midab labaad (gradient)", "Second colour (gradient)")))
                body.addView(Ui.colorRow(this, l.textColor2, true) { l.textColor2 = it; live() })
            },
            tr("Xariiq", "Outline") to { body: LinearLayout ->
                body.addView(Ui.colorRow(this, l.strokeColor, true) { l.strokeColor = it; live() })
                body.addView(Ui.sliderRow(this, tr("Ballac", "Width"), 0.02f, 0.3f, l.strokeWidth.coerceIn(0.02f, 0.3f)) { l.strokeWidth = it; live() })
            }
        ))
        d.show()
    }

    private fun showOpacity(l: Layer, title: String) {
        val (d, root) = Ui.sheet(this, title) { commit() }
        root.addView(Ui.slider(this, 0f, 1f, LayerRenderer.basePose(l, timeMs).opacity.coerceIn(0f, 1f)) {
            val p = LayerRenderer.basePose(l, timeMs); p.opacity = it
            LayerRenderer.writePose(l, timeMs, p); live()
        })
        d.show()
    }

    /** Plays the start (or end) of a layer so the chosen animation is seen right away. */
    private fun previewAnim(l: Layer, isIn: Boolean) {
        timeMs = if (isIn) l.startMs else (l.endMs - minOf(l.animOutMs, l.durationMs / 2) - 50).coerceAtLeast(l.startMs)
        timeline.timeMs = timeMs
        engine.seekTo(timeMs)
        if (project.clips.isNotEmpty()) {
            engine.play(); updatePlayButton()
            main.postDelayed({ if (engine.isPlaying) { engine.pause(); updatePlayButton() } }, (if (isIn) l.animInMs else l.animOutMs) + 500)
        }
    }

    private fun showAnimation(l: Layer) {
        val (d, root) = Ui.sheet(this, tr("Dhaqdhaqaaq", "Animation")) { commit() }
        val isText = l.kind == LayerKind.TEXT
        val sample = sampleText(l)
        fun layerTile(setup: (Layer) -> Unit): LoopTile = AnimTile(this, if (isText) sample else "★") { setup(it) }
        Ui.tabs(this, root, listOf(
            tr("Gal", "In") to { body: LinearLayout ->
                if (isText) {
                    body.addView(Ui.label(this, tr("Xarfaha / erayada", "Letters / words")))
                    tileRow(body, TextAnim.entries, { it == l.textIn }, { it.label }, { k -> AnimTile(this, sample) { it.textIn = k } }) { k ->
                        l.textIn = k; if (k != TextAnim.NONE) l.animIn = LayerAnim.NONE; previewAnim(l, true)
                    }
                }
                body.addView(Ui.label(this, tr("Dhammaan layer-ka", "Whole layer")))
                tileRow(body, LayerAnim.entries, { it == l.animIn }, { it.label }, { k -> layerTile { it.animIn = k } }) { k ->
                    l.animIn = k; if (k != LayerAnim.NONE) l.textIn = TextAnim.NONE; previewAnim(l, true)
                }
                body.addView(Ui.sliderRow(this, tr("Mudada", "Duration"), 0.1f, 3f, (l.animInMs / 1000f).coerceIn(0.1f, 3f), 0.1f) { l.animInMs = (it * 1000).toLong() })
            },
            tr("Bax", "Out") to { body: LinearLayout ->
                if (isText) {
                    body.addView(Ui.label(this, tr("Xarfaha / erayada", "Letters / words")))
                    tileRow(body, TextAnim.entries, { it == l.textOut }, { it.label }, { k -> AnimTile(this, sample) { it.textOut = k; it.textIn = TextAnim.NONE } }) { k ->
                        l.textOut = k; if (k != TextAnim.NONE) l.animOut = LayerAnim.NONE; previewAnim(l, false)
                    }
                }
                body.addView(Ui.label(this, tr("Dhammaan layer-ka", "Whole layer")))
                tileRow(body, LayerAnim.entries, { it == l.animOut }, { it.label }, { k -> layerTile { it.animOut = k } }) { k ->
                    l.animOut = k; if (k != LayerAnim.NONE) l.textOut = TextAnim.NONE; previewAnim(l, false)
                }
                body.addView(Ui.sliderRow(this, tr("Mudada", "Duration"), 0.1f, 3f, (l.animOutMs / 1000f).coerceIn(0.1f, 3f), 0.1f) { l.animOutMs = (it * 1000).toLong() })
            },
            tr("Wareeg", "Loop") to { body: LinearLayout ->
                if (isText) {
                    body.addView(Ui.label(this, tr("Xarfaha / erayada", "Letters / words")))
                    tileRow(body, TextLoop.entries, { it == l.textLoop }, { it.label }, { k -> AnimTile(this, sample) { it.textLoop = k; it.endMs = 2600 } }) { k -> l.textLoop = k; live() }
                }
                body.addView(Ui.label(this, tr("Dhammaan layer-ka", "Whole layer")))
                tileRow(body, LoopAnim.entries, { it == l.animLoop }, { it.label }, { k -> layerTile { it.animLoop = k; it.endMs = 2600 } }) { k -> l.animLoop = k; live() }
            }
        ))
        d.show()
    }

    private fun showExpression(l: Layer) {
        val (d, root) = Ui.sheet(this, "Expression") { commit() }
        tileRow(root, Expression.entries, { it == l.expr }, { it.label }, { k ->
            AnimTile(this, "●") {
                it.kind = LayerKind.SHAPE; it.shape = ShapeKind.CIRCLE; it.textColor = Ui.ACCENT; it.baseW = 0.32f; it.contentAspect = 1f
                it.expr = k; it.endMs = 2600
                if (k == Expression.BOUNCE || k == Expression.LOOP_CYCLE || k == Expression.LOOP_PINGPONG) {
                    it.keyframes.add(Keyframe(0, 0.25f, 0.5f)); it.keyframes.add(Keyframe(500, 0.75f, 0.5f).also { kf -> kf.ease = Easing.LINEAR })
                    it.keyframes[0].ease = Easing.EASE_IN
                }
            }
        }) { k -> l.expr = k; live() }
        root.addView(Ui.sliderRow(this, tr("Xoog", "Amount"), 0f, 3f, l.exprAmp.coerceIn(0f, 3f)) { l.exprAmp = it; live() })
        root.addView(Ui.sliderRow(this, tr("Inta jeer", "Frequency"), 0.1f, 8f, l.exprFreq.coerceIn(0.1f, 8f)) { l.exprFreq = it; live() })
        root.addView(Ui.sliderRow(this, tr("Dejin", "Decay"), 0.5f, 15f, l.exprDecay.coerceIn(0.5f, 15f)) { l.exprDecay = it; live() })
        root.addView(Ui.choiceRow(this, listOf("Motion blur: " + tr("Maya", "Off"), "Motion blur: " + tr("Haa", "On")), if (l.motionBlur) 1 else 0) { l.motionBlur = it == 1; live() })
        if (l.expr == Expression.BOUNCE && l.keyframes.size < 2) root.addView(Ui.label(this, tr("Talo: laba keyframe sameey si uu u booddo marka uu istaago.", "Tip: add two keyframes — it bounces when it stops.")))
        d.show()
    }

    /** Keyframe curves (After Effects graph presets + custom bezier). */
    private fun showCurve(l: Layer) {
        val k = LayerRenderer.keyframeAt(l, timeMs) ?: l.keyframes.filter { it.t <= timeMs - l.startMs }.maxByOrNull { it.t } ?: l.keyframes.minByOrNull { it.t } ?: return
        val (d, root) = Ui.sheet(this, tr("Qalooca keyframe-ka", "Keyframe curve")) { commit() }
        val curve = CurveView(this).apply { easing = k.ease; b = floatArrayOf(k.bx1, k.by1, k.bx2, k.by2) }
        curve.onChange = { nb -> k.ease = Easing.CUSTOM; k.bx1 = nb[0]; k.by1 = nb[1]; k.bx2 = nb[2]; k.by2 = nb[3]; live() }
        root.addView(Ui.label(this, tr("Jiid barahaas cad si aad u samayso qalooc gaar ah.", "Drag the white points to make your own curve.")))
        root.addView(curve, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(110f)))
        val custom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun showCustom() {
            custom.removeAllViews()
            if (k.ease != Easing.CUSTOM) return
            fun upd() { curve.b = floatArrayOf(k.bx1, k.by1, k.bx2, k.by2); live() }
            custom.addView(Ui.sliderRow(this, "X1", 0f, 1f, k.bx1.coerceIn(0f, 1f)) { k.bx1 = it; upd() })
            custom.addView(Ui.sliderRow(this, "Y1", -1f, 2f, k.by1.coerceIn(-1f, 2f)) { k.by1 = it; upd() })
            custom.addView(Ui.sliderRow(this, "X2", 0f, 1f, k.bx2.coerceIn(0f, 1f)) { k.bx2 = it; upd() })
            custom.addView(Ui.sliderRow(this, "Y2", -1f, 2f, k.by2.coerceIn(-1f, 2f)) { k.by2 = it; upd() })
        }
        root.addView(Ui.choiceRow(this, Easing.entries.map { it.label }, Easing.entries.indexOf(k.ease)) { i ->
            k.ease = Easing.entries[i]; curve.easing = k.ease; showCustom(); live()
        })
        root.addView(custom)
        showCustom()
        buttonRow(root, tr("Dhammaan keyframe-yada", "All keyframes") to {
            for (o in l.keyframes) { o.ease = k.ease; o.bx1 = k.bx1; o.by1 = k.by1; o.bx2 = k.bx2; o.by2 = k.by2 }
            d.dismiss()
        })
        d.show()
    }

    private fun showMask(l: Layer) {
        val (d, root) = Ui.sheet(this, tr("Maaskaro", "Mask")) { commit() }
        chipRow(root, MaskKind.entries.map { (when (it) { MaskKind.NONE -> R.drawable.ic_close; MaskKind.CIRCLE -> R.drawable.ic_circle; else -> R.drawable.ic_mask }) to it.label }) { i ->
            l.mask = MaskKind.entries[i]; live()
        }
        root.addView(Ui.sliderRow(this, tr("Cabbirka", "Size"), 0.1f, 1.5f, l.maskSize.coerceIn(0.1f, 1.5f)) { l.maskSize = it; live() })
        root.addView(Ui.sliderRow(this, "Feather", 0f, 1f, l.maskFeather) { l.maskFeather = it; live() })
        root.addView(Ui.choiceRow(this, listOf(tr("Caadi", "Normal"), tr("Rogan", "Invert")), if (l.maskInvert) 1 else 0) { l.maskInvert = it == 1; live() })
        d.show()
    }

    /** Green / blue screen keying, After Effects Keylight style. */
    private fun showChroma(l: Layer) {
        val (d, root) = Ui.sheet(this, tr("Shaashad cagaar (chroma key)", "Chroma key")) { commit() }
        root.addView(Ui.choiceRow(this, listOf(tr("Dami", "Off"), tr("Shid", "On")), if (l.chroma) 1 else 0) { l.chroma = it == 1; live() })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val swatch = View(this).apply { background = Ui.roundBg(l.chromaColor, dp(14f).toFloat(), dp(2f), 0x55FFFFFF) }
        row.addView(swatch, LinearLayout.LayoutParams(dp(30f), dp(30f)).apply { marginEnd = dp(8f) })
        row.addView(Ui.button(this, tr("Ka dooro sawirka", "Pick from video"), false) {
            l.chroma = true
            toast(tr("Taabo midabka gadaasha ee sawirka korka", "Tap the background colour on the preview"))
            stage.colorPicker = { c -> l.chromaColor = c; swatch.background = Ui.roundBg(c, dp(14f).toFloat(), dp(2f), 0x55FFFFFF); live() }
        }.apply { setIconResource(R.drawable.ic_eyedropper) })
        row.addView(Ui.button(this, tr("Cagaar", "Green"), false) { l.chromaColor = 0xFF00FF00.toInt(); l.chroma = true; swatch.background = Ui.roundBg(l.chromaColor, dp(14f).toFloat()); live() })
        row.addView(Ui.button(this, tr("Buluug", "Blue"), false) { l.chromaColor = 0xFF0047FF.toInt(); l.chroma = true; swatch.background = Ui.roundBg(l.chromaColor, dp(14f).toFloat()); live() })
        val (sv, r) = Ui.hrow(this); r.addView(row); root.addView(sv)
        root.addView(Ui.sliderRow(this, tr("Xoog", "Strength"), 0f, 1f, l.chromaTol) { l.chromaTol = it; live() })
        root.addView(Ui.sliderRow(this, tr("Jilicsanaan", "Softness"), 0f, 1f, l.chromaSoft) { l.chromaSoft = it; live() })
        root.addView(Ui.sliderRow(this, tr("Ka saar dheecaan", "Spill"), 0f, 1f, l.chromaSpill) { l.chromaSpill = it; live() })
        root.addView(Ui.sliderRow(this, "Choke", -1f, 1f, l.chromaChoke.coerceIn(-1f, 1f)) { l.chromaChoke = it; live() })
        root.addView(Ui.choiceRow(this, listOf(tr("Muuqaal", "Result"), tr("Matte (madow/cad)", "Matte view")), if (l.chromaMatte) 1 else 0) { l.chromaMatte = it == 1; live() })
        d.show()
    }

    private fun showCrop(l: Layer) {
        if (!l.hasCrop()) l.srcAspect = l.contentAspect
        val (d, root) = Ui.sheet(this, tr("Jar sawirka", "Crop")) { commit() }
        fun apply() {
            val w = (1f - l.cropL - l.cropR).coerceAtLeast(0.05f); val h = (1f - l.cropT - l.cropB).coerceAtLeast(0.05f)
            l.contentAspect = l.srcAspect * h / w
            live()
        }
        root.addView(Ui.choiceRow(this, listOf(tr("Xor", "Free"), "1:1", "4:5", "16:9", "9:16", tr("Asal", "Reset")), -1) { k ->
            if (k == 5) { l.cropL = 0f; l.cropT = 0f; l.cropR = 0f; l.cropB = 0f; apply(); return@choiceRow }
            if (k == 0) return@choiceRow
            val target = floatArrayOf(1f, 4f / 5f, 16f / 9f, 9f / 16f)[k - 1]   // width / height
            val src = 1f / l.srcAspect
            if (src > target) { val keep = target / src; l.cropL = (1 - keep) / 2; l.cropR = (1 - keep) / 2; l.cropT = 0f; l.cropB = 0f }
            else { val keep = src / target; l.cropT = (1 - keep) / 2; l.cropB = (1 - keep) / 2; l.cropL = 0f; l.cropR = 0f }
            apply()
        })
        root.addView(Ui.sliderRow(this, tr("Bidix", "Left"), 0f, 0.45f, l.cropL.coerceIn(0f, 0.45f)) { l.cropL = it; apply() })
        root.addView(Ui.sliderRow(this, tr("Midig", "Right"), 0f, 0.45f, l.cropR.coerceIn(0f, 0.45f)) { l.cropR = it; apply() })
        root.addView(Ui.sliderRow(this, tr("Kor", "Top"), 0f, 0.45f, l.cropT.coerceIn(0f, 0.45f)) { l.cropT = it; apply() })
        root.addView(Ui.sliderRow(this, tr("Hoos", "Bottom"), 0f, 0.45f, l.cropB.coerceIn(0f, 0.45f)) { l.cropB = it; apply() })
        d.show()
    }

    private fun showOutline(l: Layer) {
        val (d, root) = Ui.sheet(this, tr("Xariiq & hadh", "Outline & shadow")) { commit() }
        root.addView(Ui.label(this, tr("Xariiqda (sticker)", "Outline (sticker look)")))
        root.addView(Ui.colorRow(this, l.outlineColor, true) { l.outlineColor = it; live() })
        root.addView(Ui.sliderRow(this, tr("Ballac", "Width"), 0.005f, 0.08f, l.outlineWidth.coerceIn(0.005f, 0.08f)) { l.outlineWidth = it; live() })
        root.addView(Ui.choiceRow(this, listOf(tr("Hadh: Maya", "Shadow off"), tr("Hadh: Haa", "Shadow on")), if (l.shadow) 1 else 0) { l.shadow = it == 1; live() })
        d.show()
    }

    // ------------------------------------------------------------------ AI (photo tools)

    private fun aiBusy(title: String): Panel {
        val (d, root) = Ui.sheet(this, title)
        root.addView(ProgressBar(this).apply { isIndeterminate = true })
        root.addView(Ui.label(this, tr("AI ayaa shaqeynaya… (marka ugu horreysa model-ka ayaa la soo dejiyaa)", "AI is working… (the model downloads the first time)")))
        d.show()
        return d
    }

    private fun showAiForLayer(l: Layer) {
        val uri = l.uri ?: return
        val (d, root) = Ui.sheet(this, "AI")
        chipRow(root, listOf(R.drawable.ic_cutout to tr("Ka saar gadaasha", "Remove background"), R.drawable.ic_enhance to tr("Hagaaji si toos ah", "Auto enhance"))) { k ->
            d.dismiss()
            if (k == 0) {
                val busy = aiBusy(tr("Ka saarista gadaasha", "Removing background"))
                AiTools.removeBackground(this, Uri.parse(uri)) { file, err ->
                    main.post {
                        busy.dismiss()
                        if (file == null) { toast(tr("Lama samayn karo: ", "Could not do it: ") + (err ?: "")); return@post }
                        l.uri = Uri.fromFile(file).toString(); l.cropL = 0f; l.cropT = 0f; l.cropR = 0f; l.cropB = 0f
                        commit(); toast(tr("Gadaasha waa laga saaray ✓", "Background removed ✓"))
                    }
                }
            } else {
                io.execute { AiTools.autoEnhance(this, Uri.parse(uri), l.adjust); main.post { commit(); toast(tr("Waa la hagaajiyay ✓", "Enhanced ✓")) } }
            }
        }
        d.show()
    }

    private fun showAiForBackground() {
        val uri = project.bgImageUri
        if (uri == null) { toast(tr("Marka hore sawir gadaal ah dooro", "Choose a background picture first")); showBackground(); return }
        val (d, root) = Ui.sheet(this, "AI")
        chipRow(root, listOf(
            R.drawable.ic_cutout to tr("Ka saar gadaasha", "Remove background"),
            R.drawable.ic_blur to tr("Qari gadaasha", "Blur background"),
            R.drawable.ic_enhance to tr("Hagaaji", "Auto enhance"))) { k ->
            d.dismiss()
            if (k == 2) { io.execute { AiTools.autoEnhance(this, Uri.parse(uri), project.bgAdjust); main.post { commit() } }; return@chipRow }
            val busy = aiBusy(if (k == 0) tr("Ka saarista gadaasha", "Removing background") else tr("Qarinta gadaasha", "Blurring background"))
            AiTools.removeBackground(this, Uri.parse(uri)) { file, err ->
                main.post {
                    busy.dismiss()
                    if (file == null) { toast(tr("Lama samayn karo: ", "Could not do it: ") + (err ?: "")); return@post }
                    // the cut-out subject becomes a layer placed exactly over the picture
                    val info = MediaUtils.probe(this, Uri.parse(uri))
                    val ia = (info?.width ?: 1).toFloat() / (info?.height ?: 1).coerceAtLeast(1)
                    val r = project.aspectRatio()
                    val l = Layer(kind = LayerKind.IMAGE, uri = Uri.fromFile(file).toString(), name = tr("Qofka", "Subject"),
                        baseW = if (ia > r) ia / r else 1f, contentAspect = 1f / ia, startMs = 0, endMs = PHOTO_END)
                    l.srcAspect = l.contentAspect
                    project.bgScale = 1f; project.bgRot = 0f; project.bgX = 0f; project.bgY = 0f; project.bgMirror = false
                    if (k == 0) { project.bgImageUri = null; project.bgColor = 0xFFFFFFFF.toInt() } else project.bgAdjust.blur = 0.5f
                    project.layers.add(0, l)
                    commit()
                }
            }
        }
        d.show()
    }

    // ------------------------------------------------------------------ drawing

    private fun startDrawing() {
        val sel = selectedLayer()
        val l = if (sel != null && sel.kind == LayerKind.DRAW) sel else {
            Layer(kind = LayerKind.DRAW, baseW = 1f, contentAspect = 1f / project.aspectRatio(), name = tr("Sawir gacmeed", "Drawing")).also {
                newLayerTimes(it, 3000); project.layers.add(it); commit(); setSelection(TimelineView.Sel.LayerSel(it.id))
            }
        }
        showDraw(l)
    }

    private fun showDraw(l: Layer) {
        val brush = StageView.Brush(l.id, 0xFFFFFFFF.toInt(), 0.012f)
        val (d, root) = Ui.sheet(this, tr("Sawir gacmeed", "Draw")) { stage.brush = null; commit() }
        root.addView(Ui.label(this, tr("Farta ku sawir sawirka korka.", "Draw with your finger on the preview.")))
        root.addView(Ui.colorRow(this, brush.color, false) { brush.color = it; brush.eraser = false })
        root.addView(Ui.sliderRow(this, tr("Ballac", "Size"), 0.002f, 0.06f, brush.width) { brush.width = it })
        buttonRow(root,
            tr("Burush", "Brush") to { brush.eraser = false },
            tr("Tirtire", "Eraser") to { brush.eraser = true },
            tr("Dib u celi", "Undo") to { if (l.strokes.isNotEmpty()) { l.strokes.removeAt(l.strokes.size - 1); live() } },
            tr("Nadiifi", "Clear") to { l.strokes.clear(); live() })
        d.show()
        stage.brush = brush
    }

    // ------------------------------------------------------------------ keyframes, split, order

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
            l.keyframes.add(Keyframe(timeMs - l.startMs, p.cx, p.cy, p.scale, p.rotation, p.opacity, p.sx, p.sy))
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

    private fun showLayers() {
        if (project.layers.isEmpty()) { toast(tr("Layer ma jiro weli", "No layers yet")); return }
        val (d, root) = Ui.sheet(this, tr("Layer-ada", "Layers"))
        val (sv, row) = Ui.hrow(this)
        for (l in project.layers.reversed()) {
            val chip = Ui.text(this, (if (l.linkGroup != null) "🔗 " else "") + layerTitle(l), 13f).apply {
                background = Ui.roundBg(Ui.SURFACE2, dp(10f).toFloat(), if (l.id == selectedLayer()?.id) dp(2f) else 0, Ui.ACCENT)
                setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
                maxLines = 1
                setOnClickListener { d.dismiss(); onSelect(TimelineView.Sel.LayerSel(l.id)) }
            }
            row.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6f) })
        }
        root.addView(sv)
        d.show()
    }

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

    private fun unlink(l: Layer) { l.linkGroup = null; cleanupGroups(); commit() }

    // ------------------------------------------------------------------ keyframes (Motion Tools style)

    private fun showKeyframes(l: Layer) {
        val (d, root) = Ui.sheet(this, "Keyframes") { commit() }
        // navigation + add / remove
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun jump(next: Boolean) {
            val rel = timeMs - l.startMs
            val k = if (next) l.keyframes.filter { it.t > rel + 30 }.minByOrNull { it.t } else l.keyframes.filter { it.t < rel - 30 }.maxByOrNull { it.t }
            if (k != null) onKeyframeTap(l.startMs + k.t)
        }
        nav.addView(Ui.iconButton(this, R.drawable.ic_prev) { jump(false) })
        nav.addView(Ui.button(this, tr("◆ Ku dar / tirtir", "◆ Add / remove"), false) { toggleKeyframe(); d.dismiss(); showKeyframes(selectedLayer() ?: return@button) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        nav.addView(Ui.iconButton(this, R.drawable.ic_next) { jump(true) })
        root.addView(nav)
        root.addView(Ui.label(this, tr("Tilmaan: keyframe-yada timeline-ka ku jiid si aad u dhaqaajiso; taabo si aad ugu boodo.",
            "Tip: drag the diamonds on the timeline to move keyframes; tap one to jump to it.")))
        Ui.tabs(this, root, listOf(
            tr("Diyaar", "Presets") to { body: LinearLayout ->
                val sample = if (l.kind == LayerKind.TEXT) sampleText(l) else "★"
                tileRow(body, so.ijarjar.app.data.Presets.builtIn, { false }, { it.name }, { pr ->
                    AnimTile(this, sample) { it.endMs = 2600; it.textSizeFrac = 0.24f; so.ijarjar.app.data.Presets.apply(pr, it) }
                }) { pr -> so.ijarjar.app.data.Presets.apply(pr, l); previewAnim(l, !pr.fromEnd); live() }
            },
            tr("Qiimaha", "Values") to { body: LinearLayout ->
                val p0 = LayerRenderer.basePose(l, timeMs)
                fun upd(f: (so.ijarjar.app.render.Pose) -> Unit) { val p = LayerRenderer.basePose(l, timeMs); f(p); LayerRenderer.writePose(l, timeMs, p); live() }
                body.addView(Ui.sliderRow(this, "X", -0.5f, 1.5f, p0.cx.coerceIn(-0.5f, 1.5f)) { v -> upd { it.cx = v } })
                body.addView(Ui.sliderRow(this, "Y", -0.5f, 1.5f, p0.cy.coerceIn(-0.5f, 1.5f)) { v -> upd { it.cy = v } })
                body.addView(Ui.sliderRow(this, tr("Cabbir", "Scale"), 0.05f, 5f, p0.scale.coerceIn(0.05f, 5f)) { v -> upd { it.scale = v } })
                body.addView(Ui.sliderRow(this, tr("Wareeg", "Rotation"), -360f, 360f, p0.rotation.let { if (it > 180) it - 360 else it }.coerceIn(-360f, 360f)) { v -> upd { it.rotation = v } })
                body.addView(Ui.sliderRow(this, tr("Daahsoon", "Opacity"), 0f, 1f, p0.opacity.coerceIn(0f, 1f)) { v -> upd { it.opacity = v } })
                body.addView(Ui.sliderRow(this, tr("Ballac", "Width"), 0.05f, 4f, p0.sx.coerceIn(0.05f, 4f)) { v -> upd { it.sx = v } })
                body.addView(Ui.sliderRow(this, tr("Dherer", "Height"), 0.05f, 4f, p0.sy.coerceIn(0.05f, 4f)) { v -> upd { it.sy = v } })
            },
            tr("Qalooc", "Easing") to { body: LinearLayout ->
                if (l.keyframes.isEmpty()) { body.addView(Ui.label(this, tr("Marka hore keyframe ku dar.", "Add keyframes first."))); return@to }
                val k = LayerRenderer.keyframeAt(l, timeMs) ?: l.keyframes.filter { it.t <= timeMs - l.startMs }.maxByOrNull { it.t } ?: l.keyframes.minByOrNull { it.t }!!
                val curve = CurveView(this).apply { easing = k.ease; b = floatArrayOf(k.bx1, k.by1, k.bx2, k.by2) }
                curve.onChange = { nb -> k.ease = Easing.CUSTOM; k.bx1 = nb[0]; k.by1 = nb[1]; k.bx2 = nb[2]; k.by2 = nb[3]; live() }
                body.addView(curve, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(100f)))
                body.addView(Ui.choiceRow(this, Easing.entries.map { it.label }, Easing.entries.indexOf(k.ease)) { i -> k.ease = Easing.entries[i]; curve.easing = k.ease; live() })
                buttonRow(body, tr("Dhammaan keyframe-yada", "All keyframes") to {
                    for (o in l.keyframes) { o.ease = k.ease; o.bx1 = k.bx1; o.by1 = k.by1; o.bx2 = k.bx2; o.by2 = k.by2 }
                    toast("✓")
                })
            }
        ))
        d.show()
    }

    /** Save a layer's animation and apply it to other layers (works like Premiere / After Effects presets). */
    private fun showPresets(l: Layer) {
        val (d, root) = Ui.sheet(this, "Presets") { commit() }
        val mine = so.ijarjar.app.data.Presets.load(this)
        root.addView(Ui.label(this, tr("Presets-kaaga", "Your presets")))
        if (mine.isEmpty()) root.addView(Ui.label(this, tr("Weli ma jiraan. Animation samee kadib \"Keydi\" riix.", "None yet. Animate a layer, then tap \"Save\".")))
        else {
            val sample = if (l.kind == LayerKind.TEXT) sampleText(l) else "★"
            tileRow(root, mine, { false }, { it.name }, { pr -> AnimTile(this, sample) { it.endMs = 2600; so.ijarjar.app.data.Presets.apply(pr, it) } }) { pr ->
                so.ijarjar.app.data.Presets.apply(pr, l); previewAnim(l, true); live()
            }
        }
        root.addView(Ui.label(this, tr("Diyaar ah", "Built-in")))
        val sample2 = if (l.kind == LayerKind.TEXT) sampleText(l) else "★"
        tileRow(root, so.ijarjar.app.data.Presets.builtIn, { false }, { it.name }, { pr -> AnimTile(this, sample2) { it.endMs = 2600; so.ijarjar.app.data.Presets.apply(pr, it) } }) { pr ->
            so.ijarjar.app.data.Presets.apply(pr, l); previewAnim(l, !pr.fromEnd); live()
        }
        val name = editText("", tr("Magaca preset-ka", "Preset name")) {}
        root.addView(name)
        buttonRow(root,
            tr("Keydi", "Save") to {
                val n = name.text.toString().ifBlank { "Preset ${mine.size + 1}" }
                mine.add(so.ijarjar.app.data.Presets.fromLayer(l, n)); so.ijarjar.app.data.Presets.save(this, mine)
                toast(tr("Waa la keydiyay: ", "Saved: ") + n); d.dismiss()
            },
            tr("Ku dabaq kuwa la xiray", "Apply to linked") to {
                val pr = so.ijarjar.app.data.Presets.fromLayer(l, "tmp")
                for (o in project.linkedWith(l)) if (o.id != l.id) so.ijarjar.app.data.Presets.apply(pr, o)
                d.dismiss()
            })
        if (mine.isNotEmpty()) buttonRow(root, tr("Tirtir presets-kayga", "Delete my presets") to { so.ijarjar.app.data.Presets.save(this, emptyList()); d.dismiss() })
        root.addView(Ui.label(this, tr("Fiiro: faylasha .ffx / .prfpset ee Adobe Android kuma furmaan. Looks-ka Lumetri (.cube) waxaad ku soo gelin kartaa Filter → LUT.",
            "Note: Adobe .ffx / .prfpset files can't be read on Android. Lumetri looks (.cube) can be imported in Filters → LUT.")))
        d.show()
    }

    // ------------------------------------------------------------------ stabilise & voice

    private fun showStabilize(i: Int) {
        val c = project.clips[i]
        val (d, root) = Ui.sheet(this, tr("Deji gariirka (stabilize)", "Stabilize")) { commit() }
        if (c.stab && c.stabPath.isNotEmpty()) {
            root.addView(Ui.label(this, tr("Waa la dejiyay ✓", "Stabilized ✓")))
            root.addView(Ui.sliderRow(this, tr("Weyneyn", "Crop zoom"), 1f, 1.4f, c.stabZoom.coerceIn(1f, 1.4f)) { c.stabZoom = it; live() })
            buttonRow(root, tr("Dami", "Turn off") to { c.stab = false; c.stabPath.clear(); d.dismiss() })
            d.show(); return
        }
        root.addView(Ui.label(this, tr("Muuqaalka waa la falanqeynayaa si gariirka kamarada loo dejiyo…", "Analysing the clip to smooth out camera shake…")))
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        root.addView(bar)
        d.show()
        var cancelled = false
        d.setOnDismissListener { cancelled = true; commit() }
        io.execute {
            val path = so.ijarjar.app.media.Stabilizer.analyse(this, Uri.parse(c.uri), c.trimStartMs, c.trimEndMs) { pr -> main.post { bar.progress = pr } }
            main.post {
                if (cancelled) return@post
                if (path == null || path.size < 4) { toast(tr("Lama dejin karo", "Could not stabilize")); d.dismiss(); return@post }
                c.stabPath = path; c.stab = true; c.stabZoom = 1.1f
                d.dismiss()
                toast(tr("Waa la dejiyay ✓", "Stabilized ✓"))
            }
        }
    }

    /** Noise reduction + voice enhance for a clip or an audio track. */
    private fun showVoiceFx(denoise: Float, enhance: Boolean, set: (Float, Boolean) -> Unit) {
        var dn = denoise; var en = enhance
        val (d, root) = Ui.sheet(this, tr("Hagaaji codka", "Voice clean-up")) { commit() }
        root.addView(Ui.sliderRow(this, tr("Ka saar buuqa", "Noise removal"), 0f, 1f, dn) { dn = it; set(dn, en); live() })
        root.addView(Ui.choiceRow(this, listOf(tr("Kor u qaad: Maya", "Enhance: off"), tr("Kor u qaad: Haa", "Enhance: on")), if (en) 1 else 0) { en = it == 1; set(dn, en); live() })
        root.addView(Ui.label(this, tr("Buuqa gadaasha (dabaylo, mishiin, shanqar) waa la yareynayaa; \"Kor u qaad\" codka ayuu cadeynayaa oo xoojinayaa.",
            "Reduces background noise (wind, hum, hiss); \"Enhance\" makes the voice clearer and louder.")))
        buttonRow(root, tr("Codka ugu fiican", "Best voice") to { dn = 0.6f; en = true; set(dn, en); d.dismiss() })
        d.show()
    }

    // ------------------------------------------------------------------ overlays & dynamic link

    private fun showOverlayMenu() {
        val (d, root) = Ui.sheet(this, "Overlay")
        chipRow(root, listOf(
            R.drawable.ic_overlay to tr("Fayl", "Media file"),
            R.drawable.ic_dlink to tr("Mashruuc sawir", "Photo project"))) { k ->
            d.dismiss()
            if (k == 0) pickOverlay.launch(arrayOf("video/*", "image/*", "application/json", "application/zip", "application/octet-stream"))
            else linkPhotoProject()
        }
        root.addView(Ui.label(this, tr("Muuqaal, sawir, GIF, PNG sequence, Lottie (.json) — ama mashruuc sawir ah oo si toos ah u cusboonaada (dynamic link).",
            "Video, picture, GIF, PNG sequence, Lottie (.json) — or a photo project that updates live (dynamic link).")))
        d.show()
    }

    private fun addLinkedPhoto(target: Project, photo: Project, start: Long, end: Long) {
        val r = target.aspectRatio()
        val pa = photo.aspectRatio()
        val l = Layer(kind = LayerKind.IMAGE, linkedProject = photo.id, name = "🔗 " + photo.name, startMs = start, endMs = end)
        l.contentAspect = 1f / pa; l.srcAspect = l.contentAspect
        l.baseW = if (pa > r) 1f else pa / r
        target.layers.add(l)
    }

    private fun linkPhotoProject() {
        val photos = ProjectStore.list(this).filter { it.isPhoto && it.id != project.id }
        if (photos.isEmpty()) { toast(tr("Mashruuc sawir ah ma jiro weli", "No photo projects yet")); return }
        MaterialAlertDialogBuilder(this)
            .setTitle(tr("Dooro mashruuc sawir", "Choose a photo project"))
            .setItems(photos.map { it.name }.toTypedArray()) { _, which ->
                val ph = photos[which]
                val total = project.durationMs.coerceAtLeast(3000)
                val st = timeMs.coerceAtMost(total - 500)
                addLinkedPhoto(project, ph, st, (st + 3000).coerceAtMost(total))
                commit()
                project.layers.lastOrNull()?.let { setSelection(TimelineView.Sel.LayerSel(it.id)) }
                toast(tr("Waa la xiray — marka aad wax ka beddesho sawirka, muuqaalkuna wuu cusboonaanayaa", "Linked — edit the photo and the video updates too"))
            }.show()
    }

    /** Photo editor -> video editor (dynamic link). */
    private fun sendToVideo() {
        save()
        val videos = ProjectStore.list(this).filter { !it.isPhoto }
        val names = arrayOf(tr("+ Mashruuc muuqaal cusub", "+ New video project")) + videos.map { it.name }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(tr("U dir muuqaal (dynamic link)", "Send to video (dynamic link)"))
            .setItems(names) { _, which ->
                val photoCopy = ProjectStore.fromJson(ProjectStore.toJson(project))
                io.execute {
                    ProjectStore.save(applicationContext, photoCopy)
                    val target: Project = if (which == 0) {
                        // a new video: a 5 s blank clip as the base, the photo on top
                        val black = File(filesDir, "blank.png")
                        if (!black.exists()) {
                            val b = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888); b.eraseColor(0xFF000000.toInt())
                            FileOutputStream(black).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        }
                        Project(name = project.name + " · video", aspect = project.aspect).also {
                            it.clips.add(Clip(uri = Uri.fromFile(black).toString(), kind = MediaKind.IMAGE, sourceDurationMs = 5000, trimStartMs = 0, trimEndMs = 5000, width = 16, height = 16))
                        }
                    } else videos[which - 1]
                    addLinkedPhoto(target, photoCopy, 0, target.durationMs.coerceAtLeast(3000))
                    ProjectStore.save(applicationContext, target)
                    main.post {
                        MaterialAlertDialogBuilder(this)
                            .setMessage(tr("Sawirka waa la dhigay \"${target.name}\". Wixii aad halkan ka beddesho waxay ka muuqan doonaan muuqaalka.",
                                "The photo was placed in \"${target.name}\". Changes you make here will show in the video."))
                            .setNegativeButton(tr("Halkan joog", "Stay here"), null)
                            .setPositiveButton(tr("Fur muuqaalka", "Open video")) { _, _ ->
                                startActivity(Intent(this, EditorActivity::class.java).putExtra("id", target.id))
                            }.show()
                    }
                }
            }.show()
    }

    // ------------------------------------------------------------------ effects & captions

    private fun showEffects(replace: Layer?) {
        val (d, root) = Ui.sheet(this, tr("Saameyn (Effects)", "Effects"))
        val thumb = currentThumb()
        val groups = listOf(tr("Dhaqdhaqaaq", "Motion"), tr("Iftiin", "Light"), tr("Midab", "Colour"), tr("Qurxin", "Overlay"))
        Ui.tabs(this, root, groups.mapIndexed { gi, name ->
            name to { body: LinearLayout ->
                tileRow(body, EffectKind.entries.filter { it.group == gi }, { it == replace?.effect }, { it.label }, { EffectTile(this, thumb, it) }, 70f) { e ->
                    d.dismiss()
                    if (replace != null) { replace.effect = e; commit() }
                    else {
                        val l = Layer(kind = LayerKind.EFFECT, effect = e, name = e.en)
                        newLayerTimes(l, 3000)
                        addLayer(l)
                    }
                }
            }
        }, replace?.effect?.group ?: 0)
        d.show()
    }

    private fun captionLayer(text: String, start: Long, end: Long): Layer {
        val template = project.layers.firstOrNull { it.isCaption }
        val l = Layer(kind = LayerKind.TEXT, text = text, isCaption = true, textSizeFrac = 0.055f, cy = 0.82f,
            textColor = 0xFFFFFFFF.toInt(), strokeColor = 0xFF000000.toInt(), strokeWidth = 0.14f, textIn = TextAnim.WORD_POP, animInMs = 400, animOutMs = 250)
        if (template != null) copyTextStyle(template, l)
        l.startMs = start; l.endMs = end
        return l
    }

    /** Caption templates (Brevidy style): font, colours, box, highlight and animation in one tap. */
    private class CapTemplate(val name: String, val fn: (Layer) -> Unit)

    private val capTemplates: List<CapTemplate> by lazy {
        fun base(l: Layer) { l.textColor2 = 0; l.bgColor = 0; l.depth = 0f; l.shadow = false; l.letterSpacing = 0f; l.strokeColor = 0; l.textLoop = TextLoop.NONE; l.textOut = TextAnim.NONE }
        listOf(
            CapTemplate("Hormozi") { base(it); it.font = 6; it.bold = true; it.textColor = 0xFFFFFFFF.toInt(); it.strokeColor = 0xFF000000.toInt(); it.strokeWidth = 0.18f; it.textLoop = TextLoop.WORD_POP; it.highlightColor = 0xFFFFE600.toInt(); it.textIn = TextAnim.WORD_POP },
            CapTemplate("Beast") { base(it); it.font = 6; it.textColor = 0xFFFFE600.toInt(); it.strokeColor = 0xFF000000.toInt(); it.strokeWidth = 0.22f; it.depth = 0.35f; it.depthColor = 0xFF000000.toInt(); it.textIn = TextAnim.LETTER_POP },
            CapTemplate("Karaoke") { base(it); it.font = 7; it.textColor = 0xFFFFFFFF.toInt(); it.strokeColor = 0xFF000000.toInt(); it.strokeWidth = 0.12f; it.textLoop = TextLoop.KARAOKE; it.highlightColor = 0xFF39FF14.toInt(); it.textIn = TextAnim.WORD_FADE_UP },
            CapTemplate("Box") { base(it); it.font = 7; it.textColor = 0xFFFFFFFF.toInt(); it.bgColor = 0xE6000000.toInt(); it.textLoop = TextLoop.WORD_HIGHLIGHT; it.highlightColor = 0xFFFF2D55.toInt(); it.textIn = TextAnim.WORD_FADE_UP },
            CapTemplate("Minimal") { base(it); it.font = 5; it.bold = false; it.textColor = 0xFFFFFFFF.toInt(); it.shadow = true; it.textIn = TextAnim.APPLE; it.textOut = TextAnim.APPLE },
            CapTemplate("Neon") { base(it); it.textColor = 0xFF7DF9FF.toInt(); it.strokeColor = 0xFFFF2D95.toInt(); it.strokeWidth = 0.08f; it.shadow = true; it.textLoop = TextLoop.WAVE; it.textIn = TextAnim.LETTER_FADE },
            CapTemplate("Typewriter") { base(it); it.font = 2; it.textColor = 0xFFFFFFFF.toInt(); it.bgColor = 0xCC000000.toInt(); it.textIn = TextAnim.TYPEWRITER },
            CapTemplate("Bubble") { base(it); it.font = 9; it.textColor = 0xFF111111.toInt(); it.bgColor = 0xFFFFFFFF.toInt(); it.textIn = TextAnim.BOUNCE_IN },
            CapTemplate("Glitch") { base(it); it.font = 2; it.textColor = 0xFFFFFFFF.toInt(); it.strokeColor = 0xFFFF00FF.toInt(); it.strokeWidth = 0.05f; it.textIn = TextAnim.GLITCH; it.textLoop = TextLoop.JITTER },
            CapTemplate("Gold") { base(it); it.font = 6; it.textColor = 0xFFFFE27A.toInt(); it.textColor2 = 0xFFE09B12.toInt(); it.depth = 0.5f; it.depthColor = 0xFF6B4300.toInt(); it.textIn = TextAnim.BOUNCE_IN },
            CapTemplate("Cinema") { base(it); it.font = 1; it.bold = false; it.textColor = 0xFFF5F5F5.toInt(); it.letterSpacing = 0.15f; it.textIn = TextAnim.TRACKING; it.textOut = TextAnim.LETTER_FADE },
            CapTemplate("Comic") { base(it); it.font = 9; it.textColor = 0xFFFFFFFF.toInt(); it.strokeColor = 0xFF000000.toInt(); it.strokeWidth = 0.2f; it.textLoop = TextLoop.BOUNCE; it.textIn = TextAnim.LETTER_DROP },
            CapTemplate("Soomaali") { base(it); it.font = 6; it.textColor = 0xFFFFFFFF.toInt(); it.strokeColor = 0xFF4189DD.toInt(); it.strokeWidth = 0.16f; it.textLoop = TextLoop.KARAOKE; it.highlightColor = 0xFF4189DD.toInt(); it.textIn = TextAnim.WORD_POP },
            CapTemplate("Fire") { base(it); it.font = 6; it.textColor = 0xFFFFF176.toInt(); it.textColor2 = 0xFFFF3D00.toInt(); it.strokeColor = 0xFF3E0000.toInt(); it.strokeWidth = 0.1f; it.textIn = TextAnim.LETTER_RISE },
            CapTemplate("Pastel") { base(it); it.font = 3; it.bold = false; it.textColor = 0xFFFFC2D1.toInt(); it.strokeColor = 0xFF3A0CA3.toInt(); it.strokeWidth = 0.08f; it.textLoop = TextLoop.WAVE; it.textIn = TextAnim.COLOR_IN; it.highlightColor = 0xFFB5179E.toInt() },
            CapTemplate("Rainbow") { base(it); it.font = 6; it.textColor = 0xFFFFFFFF.toInt(); it.strokeColor = 0xFF000000.toInt(); it.strokeWidth = 0.14f; it.textLoop = TextLoop.RAINBOW; it.textIn = TextAnim.RANDOM }
        )
    }

    private var capWords = 4
    private var capUpper = false

    private fun captionLayer(text: String, start: Long, end: Long): Layer {
        val template = project.layers.firstOrNull { it.isCaption }
        val l = Layer(kind = LayerKind.TEXT, text = if (capUpper) text.uppercase() else text, isCaption = true, textSizeFrac = 0.06f, cy = 0.78f,
            textColor = 0xFFFFFFFF.toInt(), strokeColor = 0xFF000000.toInt(), strokeWidth = 0.14f, textIn = TextAnim.WORD_POP, animInMs = 400, animOutMs = 250)
        if (template != null) copyTextStyle(template, l) else capTemplates[0].fn(l)
        l.startMs = start; l.endMs = end
        return l
    }

    /** Splits text over [start,end) into captions of [capWords] words, timed by word length. */
    private fun addCaptionText(text: String, start: Long, end: Long) {
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return
        val chunks = words.chunked(capWords.coerceAtLeast(1))
        val totalChars = words.sumOf { it.length + 1 }.coerceAtLeast(1)
        var t = start
        for ((i, ch) in chunks.withIndex()) {
            val chars = ch.sumOf { it.length + 1 }
            val len = if (i == chunks.size - 1) end - t else ((end - start) * chars / totalChars)
            project.layers.add(captionLayer(ch.joinToString(" "), t, (t + len).coerceAtLeast(t + 200)))
            t += len
        }
    }

    /** Re-cuts all captions into chunks of [capWords] words. */
    private fun rechunkCaptions() {
        val caps = project.layers.filter { it.isCaption }.sortedBy { it.startMs }
        if (caps.isEmpty()) return
        val style = caps[0]
        project.layers.removeAll { it.isCaption }
        val keep = Layer().also { copyTextStyle(style, it); it.isCaption = true; it.kind = LayerKind.TEXT }
        project.layers.add(keep)
        for (c in caps) addCaptionText(c.text, c.startMs, c.endMs)
        project.layers.remove(keep)
    }

    private val askMicForCaptions = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) runAutoCaptions(pendingCaptionLang) else toast(tr("Ogolaanshaha makarafoonka waa loo baahan yahay", "Microphone permission is needed"))
    }
    private var pendingCaptionLang = "auto"

    private fun showCaptions() {
        val (d, root) = Ui.sheet(this, tr("Qoraal-hoosaad (Captions)", "Captions"))
        Ui.tabs(this, root, listOf(
            tr("Auto", "Auto") to { body: LinearLayout ->
                body.addView(Ui.label(this, tr("Codka muuqaalka ayaa qoraal loo beddelayaa. Luqadda dooro:", "The video's speech becomes captions. Language:")))
                val langs = listOf("auto" to tr("Toos (auto)", "Auto detect"), "so-SO" to "Soomaali", "en-US" to "English", "ar-SA" to "العربية")
                var lang = "auto"
                body.addView(Ui.choiceRow(this, langs.map { it.second }, 0) { lang = langs[it].first })
                buttonRow(body, tr("Samee captions", "Generate captions") to {
                    pendingCaptionLang = lang
                    d.dismiss()
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) runAutoCaptions(lang)
                    else askMicForCaptions.launch(Manifest.permission.RECORD_AUDIO)
                })
            },
            tr("Qor", "Type") to { body: LinearLayout ->
                val edit = editText("", tr("Sadar kasta = hal qoraal-hoosaad", "Each line = one caption")) {}.apply { minLines = 2; gravity = Gravity.TOP }
                body.addView(edit)
                var per = 2.5f
                body.addView(Ui.sliderRow(this, tr("Ilbiriqsi", "Seconds"), 0.5f, 8f, per, 0.5f) { per = it })
                buttonRow(body,
                    tr("Xariiqda", "At playhead") to {
                        val lines = edit.text.toString().split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                        var t = timeMs
                        val step = (per * 1000).toLong()
                        for (line in lines) { addCaptionText(line, t, t + step); t += step }
                        if (lines.isNotEmpty()) { d.dismiss(); commit() }
                    },
                    tr("Dhammaan", "Whole video") to {
                        val lines = edit.text.toString().split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                        if (lines.isNotEmpty() && project.durationMs > 0) {
                            val step = project.durationMs / lines.size
                            for ((i, line) in lines.withIndex()) addCaptionText(line, i * step, (i + 1) * step)
                            d.dismiss(); commit()
                        }
                    })
            },
            tr("Template", "Templates") to { body: LinearLayout ->
                tileRow(body, capTemplates, { false }, { it.name }, { tpl ->
                    AnimTile(this, tr("Salaan wanaagsan", "Hello there")) { tpl.fn(it); it.textSizeFrac = 0.17f; it.endMs = 2600 }
                }, 66f) { tpl ->
                    val caps = project.layers.filter { it.isCaption }
                    if (caps.isEmpty()) toast(tr("Marka hore captions samee", "Make some captions first"))
                    for (c in caps) tpl.fn(c)
                    live()
                }
                buttonRow(body, tr("Keydi", "Save") to { commit() })
            },
            tr("Qaab", "Layout") to { body: LinearLayout ->
                body.addView(Ui.sliderRow(this, tr("Erayo", "Words / caption"), 1f, 8f, capWords.toFloat(), 1f) { capWords = it.toInt() })
                body.addView(Ui.choiceRow(this, listOf(tr("Kor", "Top"), tr("Dhexe", "Middle"), tr("Hoos", "Bottom")), -1) { k ->
                    val y = floatArrayOf(0.18f, 0.5f, 0.8f)[k]
                    for (c in project.layers) if (c.isCaption) { c.cy = y; c.keyframes.clear() }
                    live()
                })
                body.addView(Ui.choiceRow(this, listOf("Aa", "AA"), if (capUpper) 1 else 0) { k ->
                    capUpper = k == 1
                    if (capUpper) for (c in project.layers) if (c.isCaption) c.text = c.text.uppercase()
                    live()
                })
                body.addView(Ui.sliderRow(this, tr("Cabbirka", "Size"), 0.03f, 0.15f, project.layers.firstOrNull { it.isCaption }?.textSizeFrac?.coerceIn(0.03f, 0.15f) ?: 0.06f) { v ->
                    for (c in project.layers) if (c.isCaption) c.textSizeFrac = v
                    live()
                })
                buttonRow(body,
                    tr("Kala jar mar kale", "Re-split") to { rechunkCaptions(); d.dismiss(); commit() },
                    tr("Qaabka", "Style") to {
                        val first = project.layers.firstOrNull { it.isCaption }
                        if (first != null) { d.dismiss(); setSelection(TimelineView.Sel.LayerSel(first.id)); showTextEditor(first) }
                    },
                    tr("Tirtir", "Delete") to { project.layers.removeAll { it.isCaption }; d.dismiss(); setSelection(null); commit() })
            }
        ))
        d.show()
    }

    private fun runAutoCaptions(lang: String) {
        if (project.clips.none { it.kind == MediaKind.VIDEO }) { toast(tr("Muuqaal cod leh ku dar", "Add a video with speech")); return }
        if (!so.ijarjar.app.media.AutoCaptions.supported(this)) {
            MaterialAlertDialogBuilder(this)
                .setMessage(tr("Auto captions waxay u baahan yihiin Android 13+ iyo adeegga codka Google. Qoraalka gacanta ku qor qeybta \"Qor\".",
                    "Auto captions need Android 13+ and Google speech services. You can type captions in the \"Type\" tab."))
                .setPositiveButton("OK", null).show()
            return
        }
        engine.pause(); updatePlayButton()
        val (d, root) = Ui.sheet(this, tr("Auto captions", "Auto captions"))
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val status = Ui.label(this, tr("Codka waa la akhrinayaa…", "Reading the sound…"))
        root.addView(status); root.addView(bar)
        val ac = so.ijarjar.app.media.AutoCaptions(this)
        d.setOnDismissListener { ac.cancel() }
        d.show()
        ac.start(ProjectStore.fromJson(ProjectStore.toJson(project)), lang, object : so.ijarjar.app.media.AutoCaptions.Callback {
            override fun onProgress(done: Int, total: Int) {
                bar.progress = done * 100 / total.coerceAtLeast(1)
                status.text = tr("Qoraal-hoosaad ", "Caption ") + "${done + 1} / $total"
            }
            override fun onDone(pieces: List<so.ijarjar.app.media.AutoCaptions.Piece>) {
                d.setOnDismissListener { }; d.dismiss()
                if (pieces.isEmpty()) { toast(tr("Hadal lama helin (ama luqadda telefoonku ma taageerto)", "No speech found (or the phone does not support this language)")); return }
                for (p in pieces) addCaptionText(p.text, p.startMs, p.endMs)
                commit()
                toast(tr("${pieces.size} qoraal-hoosaad ayaa la sameeyay", "${pieces.size} captions made"))
                showCaptions()
            }
            override fun onError(message: String) { d.setOnDismissListener { }; d.dismiss(); toast(tr("Khalad: ", "Error: ") + message) }
        })
    }

    // ------------------------------------------------------------------ audio

    private fun showAudioMenu() {
        val (d, root) = Ui.sheet(this, tr("Cod", "Audio"))
        chipRow(root, listOf(
            R.drawable.ic_music to tr("Muusik", "Music"),
            R.drawable.ic_sfx to tr("Dhawaaqyo", "Sound FX"),
            R.drawable.ic_mic to tr("Cod-duub", "Voiceover"),
            R.drawable.ic_waveform to tr("Ka soo saar", "Extract"),
            R.drawable.ic_sound to tr("Fayl cod", "Audio file"))) { k ->
            d.dismiss()
            when (k) {
                0 -> { addAudioKind = AudioKind.MUSIC; pickAudio.launch(arrayOf("audio/*")) }
                1 -> showSfx()
                2 -> if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) showVoiceover()
                     else askMic.launch(Manifest.permission.RECORD_AUDIO)
                3 -> { addAudioKind = AudioKind.EXTRACTED; pickAudio.launch(arrayOf("video/*")) }
                else -> { addAudioKind = AudioKind.SOUND; pickAudio.launch(arrayOf("audio/*")) }
            }
        }
        d.show()
    }

    /** Built-in sound effects: tap to hear it and add it at the playhead. */
    private fun showSfx() {
        val (d, root) = Ui.sheet(this, tr("Dhawaaqyo (sound effects)", "Sound effects"))
        root.addView(Ui.label(this, tr("Taabo si aad u maqasho oo u darto meesha xariiqdu taagan tahay.", "Tap to hear it and add it at the playhead.")))
        val sv = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val grid = GridLayout(this).apply { rowCount = 2; orientation = GridLayout.VERTICAL }
        for (s in Sfx.all) {
            grid.addView(Ui.iconChip(this, R.drawable.ic_sfx, s.label) {
                io.execute {
                    val f = Sfx.file(this, s)
                    main.post {
                        sfxPlayer?.release()
                        sfxPlayer = runCatching { MediaPlayer.create(this, Uri.fromFile(f))?.also { it.start() } }.getOrNull()
                        val len = Sfx.durationMs(f)
                        project.audios.add(AudioTrack(uri = Uri.fromFile(f).toString(), name = s.label, kind = AudioKind.SOUND,
                            startMs = timeMs, durationMs = len, sourceDurationMs = len))
                        commit()
                        toast("+ ${s.label}")
                    }
                }
            }, GridLayout.LayoutParams().apply { width = dp(84f); setMargins(dp(3f), dp(3f), dp(3f), dp(3f)) })
        }
        sv.addView(grid)
        root.addView(sv)
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
        val status = Ui.text(this, "00:00", 24f, Ui.TEXT, true).apply { gravity = Gravity.CENTER }
        val btn = ImageView(this).apply {
            setImageResource(R.drawable.ic_record)
            imageTintList = ColorStateList.valueOf(0xFFFF3B30.toInt())
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        row.addView(btn, LinearLayout.LayoutParams(dp(64f), dp(64f)))
        row.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(16f) })
        root.addView(Ui.label(this, tr("Riix si aad u duubto — muuqaalku wuu socon doonaa.", "Tap to record — the video plays while you talk.")))
        root.addView(row)
        val ticker = object : Runnable {
            override fun run() {
                if (recorder == null) return
                status.text = TimelineView.fmt(SystemClock.elapsedRealtime() - recordStartClock)
                main.postDelayed(this, 250)
            }
        }
        btn.setOnClickListener {
            if (recorder == null) {
                if (startRecording()) { btn.setImageResource(R.drawable.ic_stop); main.post(ticker) }
            } else d.dismiss()
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
                if (project.layers.isEmpty() && info != null) project.aspect = closestAspect(info.width.toFloat() / info.height)
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
        root.addView(Ui.colorRow(this, project.bgColor, false) { project.bgColor = it; live() })
        root.addView(Ui.label(this, tr("Midab labaad (gradient)", "Second colour (gradient)")))
        root.addView(Ui.colorRow(this, project.bgColor2, true) { project.bgColor2 = it; live() })
        if (project.bgImageUri == null) buttonRow(root, tr("Dooro sawir", "Choose picture") to { d.dismiss(); pickBackground.launch(arrayOf("image/*")) })
        else buttonRow(root,
            tr("Beddel sawirka", "Change") to { d.dismiss(); pickBackground.launch(arrayOf("image/*")) },
            tr("Muraayad", "Mirror") to { project.bgMirror = !project.bgMirror; live() },
            tr("Ka saar", "Remove") to { project.bgImageUri = null; d.dismiss() })
        d.show()
    }

    // ------------------------------------------------------------------ aspect & export

    private fun showAspect() {
        val labels = listOf("9:16  TikTok", "16:9  YouTube", "1:1", "4:5  Instagram", "4:3", "3:4", "2:3", "3:2", "21:9")
        val (d, root) = Ui.sheet(this, tr("Saamiga shaashadda", "Aspect ratio"))
        root.addView(Ui.choiceRow(this, labels, aspects.indexOf(project.aspect)) { k ->
            project.aspect = aspects[k]
            stage.requestLayout()
            commit()
        })
        d.show()
    }

    private fun showExport() {
        if (project.clips.isEmpty()) { toast(tr("Marka hore muuqaal ku dar", "Add a video first")); return }
        engine.pause(); updatePlayButton()
        val (d, root) = Ui.sheet(this, tr("Dhoofi muuqaalka", "Export video"))
        var res = 1080
        var mov = false
        root.addView(Ui.choiceRow(this, listOf("480p", "720p", "1080p", "2K", "4K"), 2) { k -> res = intArrayOf(480, 720, 1080, 1440, 2160)[k] })
        root.addView(Ui.choiceRow(this, listOf("MP4", "MOV"), 0) { k -> mov = k == 1 })
        root.addView(Ui.label(this, tr("Mudada: ", "Duration: ") + TimelineView.fmt(project.durationMs) +
            tr("   ·  2K/4K waxay u baahan yihiin telefoon awood leh", "   ·  2K/4K need a strong phone")))
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
            val mime = if (mov) "video/quicktime" else "video/mp4"
            exporter = Exporter(this, ProjectStore.fromJson(ProjectStore.toJson(project)), res, mov = mov, callback = object : Exporter.Callback {
                override fun onProgress(percent: Int) { bar.progress = percent; status.text = tr("Waa la samaynayaa… ", "Rendering… ") + "$percent%" }
                override fun onDone(uri: Uri?, file: File) {
                    bar.progress = 100
                    status.text = if (uri != null) tr("Waa la keydiyay: Gallery → Movies/IjarJar", "Saved: Gallery → Movies/IjarJar") else tr("Diyaar", "Done")
                    startBtn.visibility = View.GONE
                    buttonRow(root, tr("Fur", "Open") to { openMedia(uri, file, mime) }, tr("Wadaag", "Share") to { shareMedia(uri, file, mime) })
                    exporter = null
                }
                override fun onError(message: String) {
                    status.text = tr("Khalad: ", "Error: ") + message + if (res > 1080) tr(" — isku day 1080p", " — try 1080p") else ""
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
        root.addView(Ui.choiceRow(this, listOf("720", "1080", "2K", "4K"), 1) { k -> res = intArrayOf(720, 1080, 1440, 2160)[k] })
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
                        buttonRow(root, tr("Fur", "Open") to { openMedia(uri, file, mime) }, tr("Wadaag", "Share") to { shareMedia(uri, file, mime) })
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

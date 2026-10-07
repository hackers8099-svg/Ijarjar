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
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
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
import so.ijarjar.app.model.MultiSelect
import so.ijarjar.app.model.VoiceFx
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
    private lateinit var toolBox: LinearLayout
    private lateinit var catHolder: FrameLayout
    private lateinit var toolBack: View
    private lateinit var notice: TextView
    private val toolTab = HashMap<String, Int>()
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
    // gallery pickers (open the phone's photos & videos straight away)
    private fun media() = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
    private fun imagesOnly() = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
    private val pickClipsG = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isNotEmpty()) addClips(uris)
    }
    private val pickOverlayG = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
        if (uris.isNotEmpty()) addOverlay(uris) else mockupNext = false
    }
    private val pickReplaceG = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) replaceClip(uri)
    }
    /** Takes just the sound out of a video from the gallery. */
    private val pickVideoAudio = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) addAudio(uri, AudioKind.EXTRACTED)
    }
    private val pickBackgroundG = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) setBackgroundImage(uri)
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
        if (!photo && project.clips.isEmpty()) main.postDelayed({ pickClipsG.launch(media()) }, 300)

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
        tts?.shutdown()
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
            activeGraph?.let { g -> if (g.isAttachedToWindow) { if (g.playheadMs != timeMs) g.playheadMs = timeMs } else activeGraph = null }
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
        // short messages appear on top of the preview (they never cover the panel)
        notice = Ui.text(this, "", 13f, Ui.TEXT).apply {
            background = Ui.roundBg(0xE6202027.toInt(), dp(18f).toFloat(), dp(1f), 0x33FFFFFF)
            setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
            gravity = Gravity.CENTER; maxLines = 3; visibility = View.GONE; elevation = dp(6f).toFloat()
        }
        stageBox.addView(notice, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(10f); leftMargin = dp(24f); rightMargin = dp(24f) })
        root.addView(stageBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // play row: time + keyframe on the left, play in the middle, undo / redo / grid / full screen on the right
        val pr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12f), 0, dp(4f), 0) }
        val left = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        timeLabel = Ui.text(this, "00:00 / 00:00", 12f, Ui.TEXT2)
        left.addView(timeLabel)
        keyBtn = Ui.iconButton(this, R.drawable.ic_keyframe, 20f) { selectedLayer()?.let { showKeyframes(it) } }
        keyBtn.setOnLongClickListener { toggleKeyframe(); true }
        left.addView(keyBtn)
        pr.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        playBtn = Ui.iconButton(this, R.drawable.ic_play, 28f) { togglePlay() }
        pr.addView(playBtn, LinearLayout.LayoutParams(dp(48f), dp(44f)))
        val right = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL or Gravity.END }
        undoBtn = Ui.iconButton(this, R.drawable.ic_undo, 20f) { undo() }
        redoBtn = Ui.iconButton(this, R.drawable.ic_redo, 20f) { redo() }
        right.addView(undoBtn); right.addView(redoBtn)
        val gridBtn = Ui.iconButton(this, R.drawable.ic_grid, 20f, Ui.TEXT2) {}
        gridBtn.setOnClickListener {
            stage.showGrid = !stage.showGrid
            gridBtn.imageTintList = ColorStateList.valueOf(if (stage.showGrid) Ui.ACCENT else Ui.TEXT2)
        }
        right.addView(gridBtn)
        fullBtn = Ui.iconButton(this, R.drawable.ic_fullscreen, 20f) { toggleFullscreen() }
        right.addView(fullBtn)
        pr.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
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
                pickClipsG.launch(media())
            }
        }
        tb.addView(add, FrameLayout.LayoutParams(dp(40f), dp(40f), Gravity.END or Gravity.TOP).apply { topMargin = dp(35f); marginEnd = dp(8f) })
        tlBox = tb
        root.addView(tb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220f)))

        // panels open here, in place of the timeline + tools (the preview stays visible)
        panelBox = object : FrameLayout(this) {
            // the panel never takes more than ~38% of the screen, so the preview stays big
            override fun onMeasure(w: Int, h: Int) {
                val maxH = (resources.displayMetrics.heightPixels * 0.38f).toInt()
                val mode = MeasureSpec.getMode(h)
                val limit = if (mode == MeasureSpec.UNSPECIFIED) maxH else minOf(maxH, MeasureSpec.getSize(h))
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST))
            }
        }.apply { visibility = View.GONE }
        root.addView(panelBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // bottom tool bar: category tabs on top, the tools of that category below
        toolBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.SURFACE) }
        catHolder = FrameLayout(this).apply { setPadding(dp(4f), dp(2f), dp(4f), 0) }
        toolBox.addView(catHolder)
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        toolBack = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(Ui.tool(this@EditorActivity, R.drawable.ic_back, tr("Dib", "Back")) { if (multiMode) endMulti() else setSelection(null) })
            addView(View(this@EditorActivity).apply { setBackgroundColor(0x22FFFFFF) }, LinearLayout.LayoutParams(dp(1f), dp(36f)))
        }
        line.addView(toolBack)
        toolScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        toolRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(4f), dp(2f), dp(4f), dp(8f)) }
        toolScroll.addView(toolRow)
        line.addView(toolScroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        toolBox.addView(line)
        root.addView(toolBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // keyboard open → the panel keeps only its text box, so the preview stays visible
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            panel?.onKeyboard(insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()))
            insets
        }

        if (project.isPhoto) {
            playRow.visibility = View.GONE
            tlBox.visibility = View.GONE
        }
        setContentView(root)
    }

    // ------------------------------------------------------------------ panels

    override fun attachPanel(panel: Panel) {
        this.panel?.let { if (it !== panel) it.dismiss() }
        if (panel.snapshot == null) panel.snapshot = ProjectStore.toJson(project)
        this.panel = panel
        panelBox.removeAllViews()
        val maxH = (resources.displayMetrics.heightPixels * 0.40f).toInt()
        panelBox.addView(panel.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        panelBox.visibility = View.VISIBLE
        tlBox.visibility = View.GONE
        toolBox.visibility = View.GONE
    }

    override fun cancelPanel(panel: Panel) {
        val snap = panel.snapshot ?: return
        if (snap == ProjectStore.toJson(project)) return
        project = ProjectStore.fromJson(snap)
        commit()
        toast(tr("Isbeddelka waa la tuuray", "Changes discarded"))
    }

    override fun detachPanel(panel: Panel) {
        if (this.panel !== panel) return
        this.panel = null
        panelBox.removeAllViews()
        panelBox.visibility = View.GONE
        if (!fullscreen) {
            if (!photo) tlBox.visibility = View.VISIBLE
            toolBox.visibility = View.VISIBLE
        }
        stage.brush = null
        stage.colorPicker = null
        stage.editMask = null
        stage.editCrop = null
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
        toolBox.visibility = if (fullscreen) View.GONE else View.VISIBLE
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

    private class ToolSpec(val icon: Int, val label: String, val active: Boolean, val f: () -> Unit)

    /** Tools grouped in categories, so the bar stays short and easy to read. */
    private fun buildTools() {
        val groups = ArrayList<Pair<String, ArrayList<ToolSpec>>>()
        fun group(name: String) { groups.add(Pair(name, ArrayList())) }
        fun t(icon: Int, label: String, active: Boolean = false, f: () -> Unit) { groups.last().second.add(ToolSpec(icon, label, active, f)) }
        val s = selection
        var key = "none"
        when {
            multiMode -> {
                key = "multi"
                val picked = project.layers.filter { it.id in MultiSelect.ids }
                val pickedAudio = project.audios.filter { it.id in MultiSelect.ids }
                val pickedClips = project.clips.filter { it.id in MultiSelect.ids }
                val total = picked.size + pickedAudio.size + pickedClips.size
                group(tr("La doortay: $total", "Selected: $total"))
                t(R.drawable.ic_layers, tr("Dooro dhammaan", "Select all")) {
                    MultiSelect.ids.clear()
                    project.layers.forEach { MultiSelect.ids.add(it.id) }
                    project.audios.forEach { MultiSelect.ids.add(it.id) }
                    project.clips.forEach { MultiSelect.ids.add(it.id) }
                    stage.selectedLayerId = project.layers.lastOrNull { !it.isEffect() }?.id; onMultiChanged()
                }
                t(R.drawable.ic_close, tr("Ka saar dhammaan", "Clear")) { MultiSelect.ids.clear(); stage.selectedLayerId = null; onMultiChanged() }
                if (picked.size > 1) {
                    t(R.drawable.ic_link, tr("Isku xir", "Link")) {
                        val g = java.util.UUID.randomUUID().toString().take(8)
                        for (l in picked) l.linkGroup = g
                        cleanupGroups(); commit(); toast(tr("${picked.size} layer waa la isku xiray", "${picked.size} layers linked"))
                    }
                    t(R.drawable.ic_align_h, tr("Dhexdhexaad ←→", "Center ←→")) { centerPicked(picked, true) }
                    t(R.drawable.ic_align_v, tr("Dhexdhexaad ↑↓", "Center ↑↓")) { centerPicked(picked, false) }
                    if (!photo) t(R.drawable.ic_animation, tr("Animation isku mid ah", "Same animation")) {
                        val src = project.layers.firstOrNull { it.id == stage.selectedLayerId } ?: picked.first()
                        val pr = so.ijarjar.app.data.Presets.fromLayer(src, "tmp")
                        for (o in picked) if (o.id != src.id) so.ijarjar.app.data.Presets.apply(pr, o)
                        commit(); toast(tr("Animation-ka “${layerTitle(src)}” waa lagu dabaqay", "Animation of “${layerTitle(src)}” applied"))
                    }
                }
                if (!photo && (picked.isNotEmpty() || pickedAudio.isNotEmpty())) t(R.drawable.ic_start_here, tr("Bilow halkan", "Start here")) {
                    val first = (picked.map { it.startMs } + pickedAudio.map { it.startMs }).minOrNull() ?: 0L
                    val d = timeMs - first
                    for (l in picked) { l.startMs += d; l.endMs += d }
                    for (a in pickedAudio) a.startMs = (a.startMs + d).coerceAtLeast(0)
                    commit()
                }
                if (total > 0) {
                    if (picked.isNotEmpty() || pickedAudio.isNotEmpty()) t(R.drawable.ic_copy, tr("Nuqul", "Duplicate")) {
                        val copies = picked.map { l -> l.copy().also { b -> b.linkGroup = null } }
                        val aCopies = pickedAudio.map { a -> a.copy().also { b -> b.startMs = a.endMs } }
                        project.layers.addAll(copies); project.audios.addAll(aCopies)
                        MultiSelect.ids.clear(); copies.forEach { MultiSelect.ids.add(it.id) }; aCopies.forEach { MultiSelect.ids.add(it.id) }
                        stage.selectedLayerId = copies.lastOrNull()?.id
                        commit()
                    }
                    t(R.drawable.ic_delete, tr("Tirtir", "Delete")) {
                        project.layers.removeAll { it.id in MultiSelect.ids }
                        project.audios.removeAll { it.id in MultiSelect.ids }
                        project.clips.removeAll { it.id in MultiSelect.ids }
                        MultiSelect.ids.clear(); stage.selectedLayerId = null
                        cleanupGroups(); commit()
                    }
                }
            }
            s is TimelineView.Sel.ClipSel && project.clips.getOrNull(s.index) != null -> {
                key = "clip"
                val c = project.clips[s.index]
                group(tr("Wax ka beddel", "Edit"))
                t(R.drawable.ic_split, tr("Kala jar", "Split")) { splitClip(s.index) }
                t(R.drawable.ic_trim, tr("Gooy", "Trim")) { showTrim(c) }
                if (c.kind == MediaKind.VIDEO) t(R.drawable.ic_speed, tr("Xawaare", "Speed"), c.hasCurve || c.speed != 1f) { showSpeed(c) }
                t(R.drawable.ic_volume, tr("Cod", "Volume")) { showVolume(c) }
                if (c.kind == MediaKind.VIDEO) t(R.drawable.ic_waveform, tr("Codka soo saar", "Extract audio")) { extractAudio(s.index) }
                t(R.drawable.ic_replace, tr("Beddel", "Replace")) { replaceIndex = s.index; pickReplaceG.launch(media()) }
                t(R.drawable.ic_copy, tr("Nuqul", "Duplicate")) { duplicateClip(s.index) }
                t(R.drawable.ic_delete, tr("Tirtir", "Delete")) { deleteClip(s.index) }
                group(tr("Muuqaal", "Look"))
                t(R.drawable.ic_filter, tr("Filter", "Filters"), !c.adjust.isIdentity()) { showFilters(c.adjust) { for (o in project.clips) o.adjust = c.adjust.copy() } }
                t(R.drawable.ic_canvas, tr("Shaashad", "Canvas")) { showCanvas(c) }
                if (s.index > 0) t(R.drawable.ic_transition, tr("Isbeddel", "Transition"), c.transition != TransitionKind.NONE) { showTransition(s.index) }
                if (c.kind == MediaKind.VIDEO) t(R.drawable.ic_stabilize, tr("Deji gariirka", "Stabilize"), c.stab) { showStabilize(s.index) }
                if (c.kind == MediaKind.VIDEO) {
                    group(tr("Dheeraad", "More"))
                    t(R.drawable.ic_reverse, tr("Dib u celi", "Reverse"), c.reversed) { reverseClip(s.index) }
                    t(R.drawable.ic_freeze, tr("Qabooji", "Freeze")) { freezeFrame(s.index) }
                    t(R.drawable.ic_voice_change, tr("Beddel codka", "Voice changer"), c.voice != VoiceFx.NONE || c.sfx.on) { showVoiceChanger(c.voice, project.clipStartMs(s.index), c.sfx) { c.voice = it } }
                    t(R.drawable.ic_voice, tr("Nadiifi codka", "Clean voice"), c.denoise > 0f || c.enhanceVoice) { showVoiceFx(c.denoise, c.enhanceVoice) { d, e -> c.denoise = d; c.enhanceVoice = e } }
                }
                group(tr("Habee", "Arrange"))
                t(R.drawable.ic_left, tr("Bidix u dhaqaaji", "Move left")) { moveClip(s.index, -1) }
                t(R.drawable.ic_right, tr("Midig u dhaqaaji", "Move right")) { moveClip(s.index, 1) }
                t(R.drawable.ic_add, tr("Ku dar ka dib", "Add after")) { insertAfter = s.index; pickClipsG.launch(media()) }
                t(R.drawable.ic_send_backward, tr("Ka dhig layer", "To overlay layer")) { clipToLayer(s.index) }
            }
            s is TimelineView.Sel.LayerSel && selectedLayer() != null -> {
                val l = selectedLayer()!!
                key = if (l.isEffect()) "effect" else "layer"
                group(tr("Wax ka beddel", "Edit"))
                if (l.isEffect()) {
                    t(R.drawable.ic_effects, tr("Beddel", "Change")) { showEffects(l) }
                    t(R.drawable.ic_opacity, tr("Xoog", "Strength")) { showOpacity(l, tr("Xoogga saameynta", "Effect strength")) }
                } else {
                    if (l.kind == LayerKind.TEXT) t(R.drawable.ic_pencil, tr("Qoraal", "Edit text")) { showTextEditor(l) }
                    if (l.kind == LayerKind.STICKER) t(R.drawable.ic_pencil, tr("Wax ka beddel", "Edit")) { showTextEditor(l) }
                    if (l.kind == LayerKind.SHAPE) t(R.drawable.ic_pencil, tr("Qaabka", "Style")) { showShapeEditor(l) }
                    if (l.kind == LayerKind.DRAW) t(R.drawable.ic_brush, tr("Sawir", "Draw")) { showDraw(l) }
                    if (l.isLottie) t(R.drawable.ic_text, tr("Qoraalka", "Text")) { showLottieText(l) }
                    if (l.linkGroup != null && project.linkedWith(l).size > 1) t(R.drawable.ic_preset, tr("Wax ka beddel template", "Edit template")) { showTemplateEditor(l) }
                    if (l.kind == LayerKind.VIDEO) {
                        t(R.drawable.ic_volume, tr("Cod", "Volume"), l.volume != 1f) { showLayerVolume(l) }
                        t(R.drawable.ic_waveform, tr("Codka soo saar", "Extract audio")) { extractLayerAudio(l) }
                        t(R.drawable.ic_trim, tr("Gooy", "Trim")) { showLayerTrim(l) }
                        t(R.drawable.ic_voice_change, tr("Beddel codka", "Voice changer"), l.voice != VoiceFx.NONE || l.sfx.on) { showVoiceChanger(l.voice, l.startMs, l.sfx) { l.voice = it } }
                        t(R.drawable.ic_replace, tr("Beddel", "Replace")) { replaceLayerTarget = l; pickLayerVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }
                    }
                    if (l.kind == LayerKind.IMAGE) {
                        t(R.drawable.ic_crop, tr("Jar", "Crop"), l.hasCrop()) { showCrop(l) }
                        t(R.drawable.ic_replace, tr("Beddel", "Replace")) { replaceLayerImage(l) }
                    }
                }
                if (!photo) {
                    t(R.drawable.ic_split, tr("Kala jar", "Split")) { splitLayer(l) }
                    t(R.drawable.ic_start_here, tr("Bilow halkan", "Start here")) { moveLayerStart(l) }
                    t(R.drawable.ic_end_here, tr("Dhamee halkan", "End here")) { if (timeMs > l.startMs) { l.endMs = timeMs; commit() } }
                }
                t(R.drawable.ic_copy, tr("Nuqul", "Duplicate")) { duplicateLayer(l) }
                t(R.drawable.ic_delete, tr("Tirtir", "Delete")) { deleteLayer(l) }
                if (!l.isEffect()) {
                    if (!photo) {
                        group(tr("Dhaqdhaqaaq", "Motion"))
                        t(R.drawable.ic_animation, tr("Animation", "Animation"),
                            l.animIn != LayerAnim.NONE || l.animOut != LayerAnim.NONE || l.textIn != TextAnim.NONE || l.textOut != TextAnim.NONE ||
                                l.animLoop != LoopAnim.NONE || l.textLoop != TextLoop.NONE) { showAnimation(l) }
                        t(R.drawable.ic_keyframe, tr("Keyframe & Presets", "Keyframes & presets"), l.keyframes.isNotEmpty() || l.exprCode.isNotEmpty()) { showKeyframes(l) }
                        t(R.drawable.ic_expression, "Expression", l.expr != Expression.NONE || l.motionBlur || l.exprCode.isNotEmpty()) { showExpression(l) }
                        if (project.clips.any { it.kind == MediaKind.VIDEO }) t(R.drawable.ic_track, tr("Raac (track)", "Track"), l.keyframes.size > 8) { showTrack(l) }
                    }
                    group(tr("Muuqaal", "Look"))
                    if (l.isPicture()) t(R.drawable.ic_filter, tr("Filter", "Filters"), !l.adjust.isIdentity()) { showFilters(l.adjust, null) }
                    t(R.drawable.ic_glow, tr("Qaab (opacity, xariiq, glow)", "Style"), l.outlineColor != 0 || l.shadow || l.glowColor != 0) { showStyle(l) }
                    if (l.isPicture()) {
                        t(R.drawable.ic_mask, tr("Maaskaro", "Mask"), l.mask != MaskKind.NONE) { showMask(l) }
                        t(R.drawable.ic_chroma, tr("Shaashad cagaar", "Chroma key"), l.chroma) { showChroma(l) }
                    }
                    if (l.kind == LayerKind.IMAGE) t(R.drawable.ic_ai, "AI") { showAiForLayer(l) }
                    group("3D")
                    t(R.drawable.ic_cube, "3D", l.rotX != 0f || l.rotY != 0f || l.posZ != 0f) { show3D(l) }
                    if (l.kind == LayerKind.MODEL3D) {
                        t(R.drawable.ic_layers, tr("Qaybaha", "Parts"), l.parts.isNotEmpty()) { showModelParts(l) }
                        if (l.phoneStyle != null) {
                            t(R.drawable.ic_image_add, tr("Beddel shaashadda", "Replace screen")) { partTarget = l to "Screen"; pickPartMedia.launch(media()) }
                            t(R.drawable.ic_mockup, tr("Beddel taleefanka", "Replace phone")) { showPhoneStyle(l) }
                        }
                        t(R.drawable.ic_rotate, tr("Wareeg joogto", "Auto spin"), l.modelSpin != 0f) { showSpin(l) }
                    }
                    if (l.isPicture()) t(R.drawable.ic_mockup, tr("Taleefan 3D", "3D phone")) { putInPhone(l) }
                    group(tr("Habee", "Arrange"))
                    t(R.drawable.ic_select, tr("Dooro badan", "Select")) { startMulti(l) }
                    if (l.kind == LayerKind.VIDEO || l.kind == LayerKind.IMAGE) t(R.drawable.ic_bring_forward, tr("U dir track-ga weyn", "To main track")) { layerToClip(l) }
                    t(R.drawable.ic_link, tr("Isku xir", "Link"), l.linkGroup != null) { showLink(l) }
                    if (l.linkGroup != null) t(R.drawable.ic_unlink, tr("Kala fur", "Unlink")) { unlink(l) }
                    t(R.drawable.ic_layers, tr("Kor / hoos", "Order")) { showLayers() }
                    t(R.drawable.ic_flip, tr("Rog", "Flip")) { for (g in project.linkedWith(l)) g.flipH = !g.flipH; commit() }
                    t(R.drawable.ic_reset, tr("Dib u deji", "Reset")) {
                        l.scale = 1f; l.rotation = 0f; l.cx = 0.5f; l.cy = 0.5f; l.stretchX = 1f; l.stretchY = 1f; l.keyframes.clear(); commit()
                    }
                }
            }
            s is TimelineView.Sel.AudioSel && selectedAudio() != null -> {
                key = "audio"
                val a = selectedAudio()!!
                group(tr("Cod", "Audio"))
                t(R.drawable.ic_volume, tr("Cod", "Volume")) { showAudioVolume(a) }
                t(R.drawable.ic_opacity, "Fade", a.fadeInMs > 0 || a.fadeOutMs > 0) { showAudioFade(a) }
                t(R.drawable.ic_waveform, tr("Garaaca (beat)", "Beats"), a.beats.isNotEmpty()) { detectBeats(a) }
                t(R.drawable.ic_voice_change, tr("Beddel codka", "Voice changer"), a.voice != VoiceFx.NONE || a.sfx.on) { showVoiceChanger(a.voice, a.startMs, a.sfx) { a.voice = it } }
                t(R.drawable.ic_voice, tr("Nadiifi codka", "Clean voice"), a.denoise > 0f || a.enhanceVoice) { showVoiceFx(a.denoise, a.enhanceVoice) { d, e -> a.denoise = d; a.enhanceVoice = e } }
                t(R.drawable.ic_split, tr("Kala jar", "Split")) { splitAudio(a) }
                t(R.drawable.ic_start_here, tr("Bilow halkan", "Start here")) { a.startMs = timeMs; commit() }
                t(R.drawable.ic_copy, tr("Nuqul", "Duplicate")) { val b = a.copy(); b.startMs = a.endMs; project.audios.add(b); commit() }
                t(R.drawable.ic_delete, tr("Tirtir", "Delete")) { project.audios.remove(a); setSelection(null); commit() }
            }
            photo -> {
                key = "photo"
                group(tr("Sawirka", "Photo"))
                t(R.drawable.ic_background, tr("Gadaal", "Background")) { showBackground() }
                t(R.drawable.ic_filter, tr("Filter", "Filters"), !project.bgAdjust.isIdentity()) { showFilters(project.bgAdjust, null) }
                t(R.drawable.ic_ai, "AI") { showAiForBackground() }
                t(R.drawable.ic_ratio, tr("Cabbir", "Size")) { showAspect() }
                group(tr("Ku dar", "Add"))
                t(R.drawable.ic_text, tr("Qoraal", "Text")) { addText() }
                t(R.drawable.ic_image_add, tr("Sawir", "Image")) { pickOverlayG.launch(imagesOnly()) }
                t(R.drawable.ic_model3d, "3D") { pickModel.launch(arrayOf("model/gltf-binary", "model/*", "application/octet-stream")) }
                t(R.drawable.ic_sticker, tr("Walxo", "Elements")) { showElements() }
                t(R.drawable.ic_brush, tr("Sawir gacmeed", "Draw")) { startDrawing() }
                group(tr("Habee", "Arrange"))
                t(R.drawable.ic_select, tr("Dooro badan", "Select")) { startMulti(null) }
                t(R.drawable.ic_layers, tr("Layer-ada", "Layers")) { showLayers() }
                t(R.drawable.ic_link, tr("Isku xir", "Link")) { showLink(null) }
                t(R.drawable.ic_grid, tr("Shabag", "Grid"), stage.showGrid) { stage.showGrid = !stage.showGrid; buildTools() }
                t(R.drawable.ic_send, tr("U dir muuqaal", "Send to video")) { sendToVideo() }
            }
            else -> {
                group(tr("Ku dar", "Add"))
                t(R.drawable.ic_text, tr("Qoraal", "Text")) { addText() }
                t(R.drawable.ic_caption, tr("Qoraal-hoosaad", "Captions")) { showCaptions() }
                t(R.drawable.ic_overlay, tr("Media & 3D", "Media & 3D")) { showOverlayMenu() }
                t(R.drawable.ic_sticker, tr("Walxo", "Elements")) { showElements() }
                t(R.drawable.ic_effects, tr("Saameyn", "Effects")) { showEffects(null) }
                t(R.drawable.ic_brush, tr("Sawir gacmeed", "Draw")) { startDrawing() }
                group(tr("Cod", "Audio"))
                t(R.drawable.ic_music, tr("Muusik", "Music")) { addAudioKind = AudioKind.MUSIC; pickAudio.launch(arrayOf("audio/*")) }
                t(R.drawable.ic_sfx, tr("Dhawaaqyo", "Sound FX")) { showSfx() }
                t(R.drawable.ic_mic, tr("Cod-duub", "Voiceover")) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) showVoiceover()
                    else askMic.launch(Manifest.permission.RECORD_AUDIO)
                }
                t(R.drawable.ic_waveform, tr("Codka video", "Audio from video")) { pickVideoAudio.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }
                t(R.drawable.ic_audio, tr("Kale", "More")) { showAudioMenu() }
                group(tr("Mashruuc", "Project"))
                t(R.drawable.ic_edit, tr("Wax ka beddel", "Edit clip")) {
                    if (project.clips.isNotEmpty()) setSelection(TimelineView.Sel.ClipSel(project.clipIndexAt(timeMs)))
                }
                t(R.drawable.ic_filter, tr("Filter", "Filters")) {
                    if (project.clips.isNotEmpty()) {
                        val c = project.clips[project.clipIndexAt(timeMs)]
                        showFilters(c.adjust) { for (o in project.clips) o.adjust = c.adjust.copy() }
                    }
                }
                t(R.drawable.ic_ratio, tr("Saami", "Ratio")) { showAspect() }
                t(R.drawable.ic_background, tr("Gadaal", "Background")) { showVideoBackground() }
                t(R.drawable.ic_layers, tr("Layer-ada", "Layers")) { showLayers() }
                t(R.drawable.ic_select, tr("Dooro badan", "Select")) { startMulti(null) }
            }
        }
        toolBack.visibility = if (multiMode || (s != null && !(photo && key == "photo"))) View.VISIBLE else View.GONE
        val nonEmpty = groups.filter { it.second.isNotEmpty() }
        val sel = (toolTab[key] ?: 0).coerceIn(0, (nonEmpty.size - 1).coerceAtLeast(0))
        fun fill(i: Int) {
            toolTab[key] = i
            toolRow.removeAllViews()
            for (ts in nonEmpty.getOrNull(i)?.second.orEmpty()) toolRow.addView(Ui.tool(this, ts.icon, ts.label, ts.active, ts.f))
            toolScroll.scrollTo(0, 0)
        }
        catHolder.removeAllViews()
        if (nonEmpty.size > 1) catHolder.addView(Ui.tabBar(this, nonEmpty.map { it.first }, sel) { fill(it) })
        fill(sel)
    }

    // ------------------------------------------------------------------ commit / undo

    private fun reload() {
        project.outputSize(1080).let { so.ijarjar.app.render.ExprEngine.compW = it.first.toDouble(); so.ijarjar.app.render.ExprEngine.compH = it.second.toDouble() }
        stage.project = project
        timeline.project = project
        timeMs = if (photo) 0 else timeMs.coerceIn(0, (project.durationMs - 1).coerceAtLeast(0))
        timeline.timeMs = timeMs
        engine.load(project, timeMs)
        aspectBtn.text = project.aspect
        if (selection is TimelineView.Sel.ClipSel && selectedClipIndex() >= project.clips.size) selection = null
        if (selection is TimelineView.Sel.LayerSel && selectedLayer() == null) selection = null
        if (selection is TimelineView.Sel.AudioSel && selectedAudio() == null) selection = null
        if (multiMode) {
            MultiSelect.ids.retainAll((project.layers.map { it.id } + project.audios.map { it.id } + project.clips.map { it.id }).toSet())
            timeline.multi = MultiSelect.ids.toSet()
            if (stage.selectedLayerId !in MultiSelect.ids) stage.selectedLayerId = MultiSelect.ids.lastOrNull()
            buildTools()
        } else setSelection(selection)
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

    /** Undo / redo keep an open panel open: the old state is copied into the same objects. */
    private fun restore(json: String) {
        val p = panel
        if (p != null) {
            so.ijarjar.app.data.InPlace.merge(project, ProjectStore.fromJson(json))
            p.snapshot = null
            live(); stage.requestLayout(); engine.load(project, timeMs); updateUndo(); timeline.invalidate()
        } else {
            project = ProjectStore.fromJson(json); reload()
        }
        scheduleSave()
    }

    private fun undo() {
        // changes made inside the open panel are saved as a step first, so undo removes them
        if (panel != null) { val cur = ProjectStore.toJson(project); if (cur != history.current()) history.push(cur) }
        val s = history.undo() ?: return
        restore(s)
    }

    private fun redo() {
        val s = history.redo() ?: return
        restore(s)
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
        if (multiMode) {
            val id = when (sel) {
                is TimelineView.Sel.LayerSel -> sel.id
                is TimelineView.Sel.AudioSel -> sel.id
                is TimelineView.Sel.ClipSel -> project.clips.getOrNull(sel.index)?.id
                null -> null
            } ?: return
            if (!MultiSelect.ids.remove(id)) MultiSelect.ids.add(id)
            if (sel is TimelineView.Sel.LayerSel) stage.selectedLayerId = if (id in MultiSelect.ids) id else project.layers.lastOrNull { it.id in MultiSelect.ids }?.id
            onMultiChanged()
            return
        }
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
    /** Tiles in a grid of 4 columns (CapCut style), filling the panel width. */
    private fun <T> tileGrid(root: LinearLayout, items: List<T>, isSel: (T) -> Boolean, label: (T) -> String,
                             tile: (T) -> LoopTile, pick: (T) -> Unit) {
        val cols = 4
        val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density - 28f
        val size = (widthDp / cols - 10f).coerceIn(56f, 110f)
        val grid = GridLayout(this).apply { columnCount = cols }
        val tiles = ArrayList<LoopTile>()
        for (it in items) {
            val tv = tile(it)
            tv.selectedTile = isSel(it)
            tiles.add(tv)
            grid.addView(tileWithLabel(this, tv, label(it), size) {
                pick(it)
                for ((i, x) in tiles.withIndex()) x.selectedTile = isSel(items[i])
            }, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply { width = 0 })
        }
        root.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

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
                tileGrid(body, FilterPreset.entries, { it == a.preset }, { it.label }, { FilterTile(this, thumb, it) }) { p -> a.preset = p; live() }
                if (applyAll != null) buttonRow(body, tr("Ku dabaq dhammaan", "Apply to all") to { applyAll(); d.dismiss() })
            },
            tr("Hagaaji", "Adjust") to { body: LinearLayout ->
                body.addView(Ui.sliderRow(this, tr("Iftiin", "Brightness"), -1f, 1f, a.brightness) { a.brightness = it; live() })
                body.addView(Ui.sliderRow(this, tr("Kala duwanaan", "Contrast"), -1f, 1f, a.contrast) { a.contrast = it; live() })
                body.addView(Ui.sliderRow(this, tr("Midab", "Saturation"), -1f, 1f, a.saturation) { a.saturation = it; live() })
                body.addView(Ui.sliderRow(this, tr("Diirimaad", "Temperature"), -1f, 1f, a.temperature) { a.temperature = it; live() })
                body.addView(Ui.sliderRow(this, tr("Midab-dhexe", "Tint"), -1f, 1f, a.tint) { a.tint = it; live() })
                body.addView(Ui.sliderRow(this, "Highlights", -1f, 1f, a.highlights) { a.highlights = it; live() })
                body.addView(Ui.sliderRow(this, tr("Hadhka", "Shadows"), -1f, 1f, a.shadows) { a.shadows = it; live() })
                body.addView(Ui.sliderRow(this, "Vibrance", -1f, 1f, a.vibrance) { a.vibrance = it; live() })
                body.addView(Ui.sliderRow(this, "Fade", 0f, 1f, a.fade) { a.fade = it; live() })
                body.addView(Ui.sliderRow(this, tr("Qariin", "Blur"), 0f, 1f, a.blur) { a.blur = it; live() })
                buttonRow(body, tr("Dib u celi", "Reset") to {
                    a.brightness = 0f; a.contrast = 0f; a.saturation = 0f; a.temperature = 0f; a.tint = 0f; a.blur = 0f; a.preset = FilterPreset.NONE
                    a.highlights = 0f; a.shadows = 0f; a.vibrance = 0f; a.fade = 0f; d.dismiss()
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
        if (photo) { addPlainText(); return }
        val (d, root) = Ui.sheet(this, tr("Qoraal", "Text"))
        buttonRow(root, tr("+ Qoraal cusub", "+ Add text") to { d.dismiss(); addPlainText() })
        Ui.tabs(this, root, so.ijarjar.app.data.TitleTemplates.groups.mapIndexed { gi, name ->
            name to { body: LinearLayout ->
                tileRow(body, so.ijarjar.app.data.TitleTemplates.all.filter { it.group == gi }, { false }, { it.label }, { tpl -> TitleTile(this, tpl) }, 76f) { tpl ->
                    d.dismiss()
                    val total = project.durationMs.coerceAtLeast(3000)
                    val st = timeMs.coerceAtMost((total - 500).coerceAtLeast(0))
                    val layers = tpl.build(st, (st + 4000).coerceAtMost(maxOf(total, st + 1000)))
                    project.layers.addAll(layers)
                    setSelection(TimelineView.Sel.LayerSel(layers.first { it.kind == LayerKind.TEXT }.id))
                    commit()
                    previewAnim(layers.first(), true)
                    showTemplateEditor(layers.first { it.kind == LayerKind.TEXT })
                }
            }
        })
        d.show()
    }

    private fun addPlainText() {
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
                        .setMessage(tr("Faylasha .mogrt iyo .aep waxay u baahan yihiin Adobe. After Effects ka dhoofi sidan:\n\n• Lottie (.json) adigoo isticmaalaya Bodymovin — qoraalka waad beddeli kartaa\n• MOV (Animation / PNG codec, RGB + Alpha)\n• PNG sequence (transparent)\n• GIF\n\nKadib halkan ku soo gali.",
                            ".mogrt and .aep files need Adobe software. From After Effects export as:\n\n• Lottie (.json) with Bodymovin — the text stays editable\n• MOV (Animation / PNG codec, RGB + Alpha)\n• PNG sequence (transparent)\n• GIF\n\nThen import it here."))
                        .setPositiveButton("OK", null).show()
                    uris.size > 1 && lower.all { it.endsWith(".png") || it.endsWith(".webp") } -> addSequence(uris.sortedBy { MediaUtils.displayName(this, it) }, names.first())
                    uris.size > 1 -> uris.forEach { addMediaLayer(it) }
                    AnimatedSource.isLottieName(lower[0]) || lower[0].endsWith(".zip") -> addLottie(uris[0], names[0])
                    lower[0].endsWith(".gif") -> addGif(uris[0], names[0])
                    lower[0].endsWith(".mov") -> addMovAlpha(uris[0], names[0])
                    else -> addMediaLayer(uris[0])
                }
            }
        }
    }

    private var mockupNext = false
    private val pickModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        keep(uri)
        val name = MediaUtils.displayName(this, uri)
        if (!name.lowercase().endsWith(".glb")) toast(tr("Fiiro: faylasha .glb ayaa ugu fiican (.gltf leh faylal kale ma shaqeeyo)", "Tip: .glb files work best (.gltf with extra files won't load)"))
        val l = Layer(kind = LayerKind.MODEL3D, uri = uri.toString(), name = name, baseW = 0.7f, contentAspect = 1f, modelSpin = 0.25f)
        newLayerTimes(l, 4000)
        addLayer(l)
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

    /** Transparent MOV (After Effects "Animation" or "PNG" codec, RGB + Alpha) → frames with alpha. */
    private fun addMovAlpha(uri: Uri, name: String) {
        val (d, root) = Ui.sheet(this, tr("MOV hufan (alpha)", "Transparent MOV (alpha)"))
        root.addView(Ui.label(this, tr("Fiimkan waa la furayaa si hufnaanta loo ilaaliyo…", "Reading the frames and keeping the transparency…")))
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        root.addView(bar)
        d.show()
        var cancelled = false
        d.setOnDismissListener { cancelled = true }
        io.execute {
            val res = so.ijarjar.app.media.MovAlpha.extract(this, uri) { pr -> main.post { bar.progress = pr } }
            main.post {
                if (cancelled && res !is so.ijarjar.app.media.MovAlpha.Result.Ok) return@post
                d.setOnDismissListener {}; d.dismiss()
                when (res) {
                    is so.ijarjar.app.media.MovAlpha.Result.Ok -> {
                        val l = Layer(kind = LayerKind.ANIMATED, name = "MOV · $name", frames = res.frames.toMutableList(), fps = res.fps)
                        fitBase(l, res.w, res.h)
                        newLayerTimes(l, (res.frames.size * 1000L / res.fps).toLong().coerceAtLeast(300))
                        addLayer(l)
                        toast(tr("MOV hufan waa la soo geliyay ✓ (${res.frames.size} frame)", "Transparent MOV added ✓ (${res.frames.size} frames)"))
                    }
                    is so.ijarjar.app.media.MovAlpha.Result.Other -> {
                        if (res.hasAlpha) MaterialAlertDialogBuilder(this)
                            .setTitle(tr("Codec-kan (${res.codec}) lama taageero", "This codec (${res.codec}) isn't supported"))
                            .setMessage(tr("ProRes 4444 / HEVC alpha ma furmaan Android. After Effects / Media Encoder ka dhoofi sidan:\n\n• Format: QuickTime\n• Video Codec: Animation  ama  PNG\n• Channels: RGB + Alpha\n\nKadib halkan ku soo gali — hufnaantu waa shaqeyneysaa.",
                                "ProRes 4444 / HEVC alpha can't be decoded on Android. Export from After Effects / Media Encoder as:\n\n• Format: QuickTime\n• Video Codec: Animation  or  PNG\n• Channels: RGB + Alpha\n\nThen import it here — the transparency will work."))
                            .setPositiveButton(tr("Sidaas ku dar (alpha la'aan)", "Add without alpha")) { _, _ -> addMediaLayer(uri) }
                            .setNegativeButton(tr("Jooji", "Cancel"), null).show()
                        else addMediaLayer(uri)
                    }
                    else -> addMediaLayer(uri)
                }
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
                if (mockupNext) {
                    mockupNext = false
                    l.mockup = so.ijarjar.app.model.MockupKind.PHONE_PRO; l.mockupColor = 0xFF8A8F98.toInt()
                    l.baseW = (0.55f / (l.contentAspect * project.aspectRatio())).coerceAtMost(0.5f)
                    l.rotY = -20f; l.rotX = 6f
                }
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
    private val pickLayerImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val l = replaceLayerTarget ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        keep(uri)
        io.execute {
            val info = MediaUtils.probe(this, uri)
            main.post {
                if (info == null) return@post
                l.uri = uri.toString(); l.name = MediaUtils.displayName(this, uri)
                // stay in the same place and inside the same box
                val old = l.contentAspect.coerceAtLeast(0.01f)
                val nw = info.height.toFloat() / info.width.coerceAtLeast(1)
                l.baseW = minOf(l.baseW, l.baseW * old / nw.coerceAtLeast(0.01f))
                l.contentAspect = nw; l.srcAspect = l.contentAspect
                l.cropL = 0f; l.cropT = 0f; l.cropR = 0f; l.cropB = 0f
                commit()
            }
        }
    }

    private fun replaceLayerImage(l: Layer) {
        replaceLayerTarget = l
        pickLayerImage.launch(imagesOnly())
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
        to.hlRound = from.hlRound; to.hlAnim = from.hlAnim; to.hlTextColor = from.hlTextColor; to.glowColor = from.glowColor; to.glowSize = from.glowSize
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

    private var fontTarget: Layer? = null
    private val pickFont = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val l = fontTarget ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        io.execute {
            val name = MediaUtils.displayName(this, uri).replace(Regex("[^A-Za-z0-9._ -]"), "_")
            val ext = name.substringAfterLast('.', "").lowercase()
            val dir = File(filesDir, "fonts").apply { mkdirs() }
            val f = File(dir, if (ext == "ttf" || ext == "otf") name else "$name.ttf")
            val ok = runCatching { contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { i.copyTo(it) } }; android.graphics.Typeface.createFromFile(f) }.isSuccess
            main.post {
                if (!ok) { f.delete(); toast(tr("Font-kan lama furi karo", "This font can't be opened")); return@post }
                l.fontPath = f.absolutePath; commit()
                toast(tr("Font-ka waa la soo geliyay ✓", "Font imported ✓"))
                showTextEditor(l)
            }
        }
    }

    /** Every part of a title template in one place: its texts and the colours of its shapes. */
    private fun showTemplateEditor(l: Layer) {
        val group = project.layers.filter { it.linkGroup != null && it.linkGroup == l.linkGroup }
        val (d, root) = Ui.sheet(this, tr("Template", "Template")) { commit() }
        val texts = group.filter { it.kind == LayerKind.TEXT }
        val shapes = group.filter { it.kind == LayerKind.SHAPE }
        for ((i, t) in texts.withIndex()) {
            root.addView(Ui.label(this, tr("Qoraal ${i + 1}", "Text ${i + 1}")))
            root.addView(editText(t.text, tr("Qor halkan…", "Type here…")) { t.text = it; live() })
            root.addView(Ui.colorRow(this, t.textColor, false) { t.textColor = it; live() })
        }
        for ((i, sh) in shapes.withIndex()) {
            root.addView(Ui.label(this, tr("Midabka qaabka ${i + 1} (${sh.shape.label})", "Shape ${i + 1} colour (${sh.shape.label})")))
            root.addView(Ui.colorRow(this, sh.textColor, false) { sh.textColor = it; sh.textColor2 = 0; live() })
        }
        if (shapes.isNotEmpty()) {
            root.addView(Ui.label(this, tr("Hal midab dhammaan qaababka", "One colour for all shapes")))
            root.addView(Ui.colorRow(this, shapes.first().textColor, false) { c -> for (sh in shapes) { sh.textColor = c; sh.textColor2 = 0 }; live() })
        }
        root.addView(Ui.label(this, tr("Wax kale (font, animation, keyframe): layer-ka gaarka ah taabo, ama Layer-ada ka dooro.",
            "Anything else (font, animation, keyframes): tap that layer, or pick it in Layers.")))
        d.show()
    }

    private fun showTextEditor(l: Layer) {
        val (d, root) = Ui.sheet(this, if (l.kind == LayerKind.STICKER) "Sticker" else tr("Qoraal", "Text")) { commit() }
        d.top.addView(editText(l.text, tr("Qor halkan…", "Type here…")) { l.text = it; live() }.apply { maxLines = 3 },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4f) })
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
                body.addView(Ui.choiceRow(this, LayerRenderer.FONTS, if (l.fontPath == null) l.font else -1) { l.font = it; l.fontPath = null; live() })
                val mine = File(filesDir, "fonts").listFiles()?.filter { it.extension.lowercase() in setOf("ttf", "otf") }?.sortedBy { it.name }.orEmpty()
                if (mine.isNotEmpty()) {
                    body.addView(Ui.label(this, tr("Fonts-kaaga", "Your fonts")))
                    body.addView(Ui.choiceRow(this, mine.map { it.nameWithoutExtension.take(18) }, mine.indexOfFirst { it.absolutePath == l.fontPath }) { i -> l.fontPath = mine[i].absolutePath; live() })
                }
                buttonRow(body, tr("+ Soo geli font (.ttf / .otf)", "+ Import font (.ttf / .otf)") to { fontTarget = l; pickFont.launch(arrayOf("font/*", "application/x-font-ttf", "application/x-font-otf", "application/octet-stream", "*/*")) })
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
                val hlBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                fun hlOptions() {
                    hlBox.removeAllViews()
                    if (l.textLoop != TextLoop.WORD_HIGHLIGHT && l.textLoop != TextLoop.KARAOKE && l.textLoop != TextLoop.WORD_POP) return
                    hlBox.addView(Ui.label(this, tr("Midabka erayga hadda la akhrinayo", "Colour of the current word")))
                    hlBox.addView(Ui.colorRow(this, l.highlightColor, false) { l.highlightColor = it; live() })
                    if (l.textLoop == TextLoop.WORD_HIGHLIGHT) {
                        hlBox.addView(Ui.label(this, tr("Midabka qoraalka ku dul jira", "Text colour on the box")))
                        hlBox.addView(Ui.colorRow(this, l.hlTextColor, true) { l.hlTextColor = it; live() })
                        hlBox.addView(Ui.sliderRow(this, tr("Wareegsanaan", "Roundness"), 0f, 1f, l.hlRound) { l.hlRound = it; live() })
                        hlBox.addView(Ui.label(this, tr("Sida sanduuqu u dhaqaaqo", "How the box moves")))
                        hlBox.addView(Ui.choiceRow(this, listOf(tr("Bood", "Pop"), tr("Simbiriirix", "Slide"), tr("Soo bax", "Fade"), tr("Bidix ka buuxi", "Wipe"), tr("Midna", "None")), l.hlAnim.coerceIn(0, 4)) {
                            l.hlAnim = it; previewAnim(l, true)
                        })
                    }
                }
                tileRow(body, TextLoop.entries, { it == l.textLoop }, { it.label }, { k -> AnimTile(this, sampleText(l)) { it.textLoop = k; it.endMs = 2600 } }, 60f) { k ->
                    l.textLoop = k; live(); hlOptions()
                }
                body.addView(hlBox)
                hlOptions()
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
                val defRound = if (l.shape == ShapeKind.ROUND_RECT) 0.4f else 0f
                body.addView(Ui.sliderRow(this, tr("Geesaha wareeg", "Corner round"), 0f, 1f, (if (l.shapeRound < 0f) defRound else l.shapeRound).coerceIn(0f, 1f)) { l.shapeRound = it; live() })
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

    private val pickLayerVideo = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val l = replaceLayerTarget ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        keep(uri)
        io.execute {
            val info = MediaUtils.probe(this, uri)
            main.post {
                if (info == null) return@post
                l.uri = uri.toString(); l.name = MediaUtils.displayName(this, uri); l.trimStartMs = 0
                val old = l.contentAspect.coerceAtLeast(0.01f)
                val nw = info.height.toFloat() / info.width.coerceAtLeast(1)
                l.baseW = minOf(l.baseW, l.baseW * old / nw.coerceAtLeast(0.01f))
                l.contentAspect = nw; l.srcAspect = nw
                commit()
            }
        }
    }

    /** Which part of the overlay video plays: start point inside the video and length. */
    private fun showLayerTrim(l: Layer) {
        val uri = l.uri ?: return
        io.execute {
            val src = MediaUtils.probe(this, Uri.parse(uri))?.durationMs ?: return@execute
            main.post {
                val (d, root) = Ui.sheet(this, tr("Gooy muuqaalka", "Trim video")) { commit() }
                root.addView(Ui.sliderRow(this, tr("Bilow (s)", "Start (s)"), 0f, (src / 1000f).coerceAtLeast(0.1f), (l.trimStartMs / 1000f).coerceIn(0f, src / 1000f)) {
                    l.trimStartMs = (it * 1000).toLong().coerceIn(0, (src - 100).coerceAtLeast(0))
                    l.endMs = minOf(l.endMs, l.startMs + (src - l.trimStartMs)); live(); engine.seekTo(timeMs)
                })
                root.addView(Ui.sliderRow(this, tr("Dherer (s)", "Length (s)"), 0.2f, ((src - l.trimStartMs) / 1000f).coerceAtLeast(0.3f), (l.durationMs / 1000f).coerceIn(0.2f, ((src - l.trimStartMs) / 1000f).coerceAtLeast(0.3f))) {
                    l.endMs = l.startMs + (it * 1000).toLong(); live()
                })
                root.addView(Ui.label(this, tr("Timeline-ka dhinacyada layer-ka ku jiid sidoo kale.", "You can also drag the layer's ends on the timeline.")))
                d.show()
            }
        }
    }

    /** Main-track clip → overlay layer (like CapCut "switch to overlay"); a black gap keeps the timing. */
    private fun clipToLayer(i: Int) {
        val c = project.clips.getOrNull(i) ?: return
        val start = project.clipStartMs(i)
        val len = c.outDurationMs
        io.execute {
            val black = File(File(filesDir, "backgrounds").apply { mkdirs() }, "black.png")
            if (!black.exists()) FileOutputStream(black).use { Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF000000.toInt()) }.compress(Bitmap.CompressFormat.PNG, 100, it) }
            main.post {
                val l = Layer(kind = if (c.kind == MediaKind.VIDEO) LayerKind.VIDEO else LayerKind.IMAGE, uri = c.uri, name = MediaUtils.displayName(this, Uri.parse(c.uri)))
                l.startMs = start; l.endMs = start + len; l.trimStartMs = c.trimStartMs; l.volume = c.volume
                l.contentAspect = c.height.toFloat().coerceAtLeast(1f) / c.width.coerceAtLeast(1); l.srcAspect = l.contentAspect
                l.baseW = minOf(1f, 1f / (l.contentAspect * project.aspectRatio()))
                l.adjust = c.adjust.copy()
                project.clips[i] = Clip(uri = Uri.fromFile(black).toString(), kind = MediaKind.IMAGE, sourceDurationMs = len, trimStartMs = 0, trimEndMs = len, width = 64, height = 64)
                project.layers.add(l)
                setSelection(TimelineView.Sel.LayerSel(l.id)); commit()
                toast(tr("Waa layer hadda — kor ama hoos u dhaqaaji (Habee → Layer-ada)", "It's a layer now — move it up or down (Arrange → Layers)"))
            }
        }
    }

    /** Overlay video / picture → back onto the main track at the playhead. */
    private fun layerToClip(l: Layer) {
        val uri = l.uri ?: return
        io.execute {
            val info = MediaUtils.probe(this, Uri.parse(uri))
            main.post {
                if (info == null) return@post
                val len = l.durationMs
                val c = if (l.kind == LayerKind.VIDEO) Clip(uri = uri, kind = MediaKind.VIDEO, sourceDurationMs = info.durationMs, trimStartMs = l.trimStartMs,
                    trimEndMs = (l.trimStartMs + len).coerceAtMost(info.durationMs), width = info.width, height = info.height).also { it.volume = l.volume }
                else Clip(uri = uri, kind = MediaKind.IMAGE, sourceDurationMs = len, trimStartMs = 0, trimEndMs = len, width = info.width, height = info.height)
                val at = if (project.clips.isEmpty()) 0 else (project.clipIndexAt(timeMs) + 1).coerceAtMost(project.clips.size)
                project.clips.add(at, c)
                project.layers.remove(l); cleanupGroups()
                setSelection(TimelineView.Sel.ClipSel(at)); commit()
            }
        }
    }

    private fun showLayerVolume(l: Layer) {
        val (d, root) = Ui.sheet(this, tr("Codka layer-ka", "Layer volume")) { commit() }
        root.addView(Ui.sliderRow(this, tr("Cod", "Volume"), 0f, 1f, l.volume.coerceIn(0f, 1f)) { l.volume = it; live() })
        buttonRow(root, tr("Aamusi", "Mute") to { l.volume = 0f; live(); d.dismiss() }, "100%" to { l.volume = 1f; live(); d.dismiss() })
        d.show()
    }

    /** Puts the overlay video's sound on its own audio track (and mutes the layer). */
    private fun extractLayerAudio(l: Layer) {
        val uri = l.uri ?: return
        io.execute {
            val info = MediaUtils.probe(this, Uri.parse(uri))
            main.post {
                if (info == null || !info.hasAudio) { toast(tr("Muuqaalkan cod ma leh", "This video has no sound")); return@post }
                val len = minOf(l.durationMs, (info.durationMs - l.trimStartMs).coerceAtLeast(100))
                val a = AudioTrack(uri = uri, name = l.name, kind = AudioKind.EXTRACTED, startMs = l.startMs, trimStartMs = l.trimStartMs,
                    durationMs = len, sourceDurationMs = info.durationMs, volume = l.volume.coerceIn(0f, 1f), fromVideo = true)
                project.audios.add(a); l.volume = 0f
                setSelection(TimelineView.Sel.AudioSel(a.id)); commit()
                toast(tr("Codka waa la soo saaray ✓", "Audio extracted ✓"))
            }
        }
    }

    /** Opacity, outline / shadow and glow together. */
    private fun showStyle(l: Layer) {
        val (d, root) = Ui.sheet(this, tr("Qaab", "Style")) { commit() }
        Ui.tabs(this, root, listOf(
            tr("Daahsoon", "Opacity") to { body: LinearLayout ->
                body.addView(Ui.sliderRow(this, tr("Daahsoon", "Opacity"), 0f, 1f, LayerRenderer.basePose(l, timeMs).opacity.coerceIn(0f, 1f)) {
                    val p = LayerRenderer.basePose(l, timeMs); p.opacity = it; LayerRenderer.writePose(l, timeMs, p); live()
                })
            },
            tr("Xariiq & hadh", "Outline & shadow") to { body: LinearLayout ->
                body.addView(Ui.colorRow(this, l.outlineColor, true) { l.outlineColor = it; live() })
                body.addView(Ui.sliderRow(this, tr("Ballac", "Width"), 0.005f, 0.08f, l.outlineWidth.coerceIn(0.005f, 0.08f)) { l.outlineWidth = it; live() })
                body.addView(Ui.choiceRow(this, listOf(tr("Hadh: Maya", "Shadow off"), tr("Hadh: Haa", "Shadow on")), if (l.shadow) 1 else 0) { l.shadow = it == 1; live() })
            },
            "Glow" to { body: LinearLayout ->
                body.addView(Ui.colorRow(this, l.glowColor, true) { l.glowColor = it; live() })
                body.addView(Ui.sliderRow(this, tr("Cabbir", "Size"), 0.02f, 1f, l.glowSize.coerceIn(0.02f, 1f)) { l.glowSize = it; live() })
            }
        ))
        d.show()
    }

    /** Stickers and shapes in one place. */
    private fun showElements() {
        val (d, root) = Ui.sheet(this, tr("Walxo", "Elements"))
        Ui.tabs(this, root, listOf(
            "Sticker" to { body: LinearLayout ->
                val emojis = listOf("😀", "😂", "😍", "🥰", "😎", "🤩", "😭", "😡", "🫨", "👍", "👏", "🙏", "💪", "🔥", "✨", "💯", "❤️",
                    "💔", "⭐", "🎉", "🎁", "🎵", "📌", "✅", "❌", "⚡", "🌙", "☀️", "🌸", "🇸🇴", "🕌", "📿", "🤲", "👀", "💥", "🚀", "🏆",
                    "🎂", "🌹", "💎", "👑", "📢", "💡", "📍", "🎬", "📸", "🎤", "⚽", "🌍", "🤯", "🥳", "😴", "🤔", "👋", "🙌", "💫", "🌈")
                val sv = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
                val grid = GridLayout(this).apply { rowCount = 3; orientation = GridLayout.VERTICAL }
                for (e in emojis) grid.addView(Ui.text(this, e, 30f).apply {
                    gravity = Gravity.CENTER; setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
                    setOnClickListener { val l = Layer(kind = LayerKind.STICKER, text = e, textSizeFrac = 0.18f, bold = false); newLayerTimes(l, 3000); addLayer(l) }
                })
                sv.addView(grid); body.addView(sv)
            },
            tr("Qaabab", "Shapes") to { body: LinearLayout ->
                chipRow(body, ShapeKind.entries.map { R.drawable.ic_shape to it.label }) { k ->
                    val kind = ShapeKind.entries[k]
                    val l = Layer(kind = LayerKind.SHAPE, shape = kind, textColor = Ui.ACCENT, baseW = 0.4f)
                    l.contentAspect = when (kind) { ShapeKind.LINE -> 0.05f; ShapeKind.ARROW -> 0.45f; ShapeKind.RECT, ShapeKind.ROUND_RECT -> 0.65f; ShapeKind.BUBBLE -> 0.8f; else -> 1f }
                    if (kind == ShapeKind.LINE) l.textColor = 0xFFFFFFFF.toInt()
                    newLayerTimes(l, 3000); d.dismiss(); addLayer(l)
                }
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
            tr("Gaar ah", "Custom") to { body: LinearLayout ->
                if (!isText) { body.addView(Ui.label(this, tr("Qeybtan waxay u shaqeysaa qoraalka.", "This is for text layers."))); return@to }
                body.addView(Ui.choiceRow(this, listOf(tr("Xaraf", "Letters"), tr("Eray", "Words"), tr("Sadar", "Lines")), l.taUnit) { l.taUnit = it; previewAnim(l, true) })
                body.addView(Ui.choiceRow(this, listOf(tr("Hore", "Forward"), tr("Gadaal", "Backward"), tr("Dhexda", "From centre"), tr("Kala firdhi", "Random")), l.taOrder) { l.taOrder = it; previewAnim(l, true) })
                body.addView(Ui.sliderRow(this, tr("Isku dhafan", "Overlap"), 0.03f, 1f, l.taOverlap.coerceIn(0.03f, 1f)) { l.taOverlap = it })
                body.addView(Ui.sliderRow(this, "X", -3f, 3f, l.taDx.coerceIn(-3f, 3f)) { l.taDx = it })
                body.addView(Ui.sliderRow(this, "Y", -3f, 3f, l.taDy.coerceIn(-3f, 3f)) { l.taDy = it })
                body.addView(Ui.sliderRow(this, tr("Cabbir", "Scale"), 0f, 4f, l.taScale.coerceIn(0f, 4f)) { l.taScale = it })
                body.addView(Ui.sliderRow(this, tr("Wareeg", "Rotation"), -360f, 360f, l.taRot.coerceIn(-360f, 360f)) { l.taRot = it })
                body.addView(Ui.sliderRow(this, tr("Daahsoon", "Opacity"), 0f, 1f, l.taOpacity.coerceIn(0f, 1f)) { l.taOpacity = it })
                body.addView(Ui.sliderRow(this, tr("Qariin", "Blur"), 0f, 1f, l.taBlur.coerceIn(0f, 1f)) { l.taBlur = it })
                body.addView(Ui.choiceRow(this, Easing.entries.filter { it != Easing.CUSTOM && it != Easing.HOLD }.map { it.label },
                    Easing.entries.filter { it != Easing.CUSTOM && it != Easing.HOLD }.indexOf(l.taEase)) { i ->
                    l.taEase = Easing.entries.filter { it != Easing.CUSTOM && it != Easing.HOLD }[i]
                })
                buttonRow(body,
                    tr("U isticmaal Gal", "Use as In") to { l.textIn = TextAnim.CUSTOM; l.animIn = LayerAnim.NONE; previewAnim(l, true) },
                    tr("U isticmaal Bax", "Use as Out") to { l.textOut = TextAnim.CUSTOM; l.animOut = LayerAnim.NONE; previewAnim(l, false) })
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
        Ui.tabs(this, root, listOf(
            tr("Code (sida AE)", "Code (like AE)") to { body: LinearLayout -> expressionEditor(body, l) },
            tr("Diyaar", "Quick") to { body: LinearLayout ->
                body.addView(Ui.label(this, tr("Kuwaan waxay raacaan keyframe-yada (tusaale Bounce wuxuu ka booddaa keyframe kasta).", "These follow your keyframes (e.g. Bounce overshoots after every keyframe).")))
                tileRow(body, Expression.entries, { it == l.expr }, { it.label }, { k ->
                    AnimTile(this, "●") {
                        it.kind = LayerKind.SHAPE; it.shape = ShapeKind.CIRCLE; it.textColor = Ui.ACCENT; it.baseW = 0.32f; it.contentAspect = 1f
                        it.expr = k; it.endMs = 2600
                        if (k == Expression.BOUNCE || k == Expression.LOOP_CYCLE || k == Expression.LOOP_PINGPONG) {
                            it.keyframes.add(Keyframe(0, 0.25f, 0.5f)); it.keyframes.add(Keyframe(500, 0.75f, 0.5f).also { kf -> kf.ease = Easing.LINEAR })
                            it.keyframes[0].ease = Easing.EASE_IN
                        }
                    }
                }) { k -> l.expr = k; live() }
                body.addView(Ui.sliderRow(this, tr("Xoog", "Amount"), 0f, 3f, l.exprAmp.coerceIn(0f, 3f)) { l.exprAmp = it; live() })
                body.addView(Ui.sliderRow(this, tr("Inta jeer", "Frequency"), 0.1f, 8f, l.exprFreq.coerceIn(0.1f, 8f)) { l.exprFreq = it; live() })
                body.addView(Ui.sliderRow(this, tr("Dejin", "Decay"), 0.5f, 15f, l.exprDecay.coerceIn(0.5f, 15f)) { l.exprDecay = it; live() })
                body.addView(Ui.choiceRow(this, listOf("Motion blur: " + tr("Maya", "Off"), "Motion blur: " + tr("Haa", "On")), if (l.motionBlur) 1 else 0) { l.motionBlur = it == 1; live() })
            }
        ))
        d.show()
    }

    /** Keyframe curves (After Effects graph presets + custom bezier). */
    private fun showCurve(l: Layer) = showKeyframes(l, 1)

    /** Mask like CapCut: shapes, then position / size / rotate / feather / round corner — on screen or with dials. */
    private fun showMask(l: Layer) {
        val (d, root) = Ui.sheet(this, tr("Maaskaro", "Mask")) { commit() }
        if (l.mask == MaskKind.NONE) l.mask = MaskKind.RECT
        val shapes = MaskKind.entries
        d.top.addView(Ui.choiceRow(this, shapes.map { it.label }, shapes.indexOf(l.mask)) { i -> l.mask = shapes[i]; live(); stage.invalidate() })
        fun dial(body: LinearLayout, label: String, v: Float, def: Float, perDp: Float, fmt: (Float) -> String, min: Float = -Float.MAX_VALUE, max: Float = Float.MAX_VALUE, set: (Float) -> Unit) =
            body.addView(ScrubDial(this, label, v, def, perDp, fmt, min, max) { set(it); live(); stage.invalidate() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4f) })
        val pct = { v: Float -> "%.0f".format(v) }
        Ui.tabs(this, root, listOf(
            tr("Boos", "Position") to { body: LinearLayout ->
                dial(body, tr("Dhidibka X", "X axis"), (l.maskX - 0.5f) * 100, 0f, 0.25f, pct) { l.maskX = 0.5f + it / 100f }
                dial(body, tr("Dhidibka Y", "Y axis"), (l.maskY - 0.5f) * 100, 0f, 0.25f, pct) { l.maskY = 0.5f + it / 100f }
            },
            tr("Cabbir", "Size") to { body: LinearLayout ->
                dial(body, tr("Dherer", "Height"), l.maskSize * 100, 80f, 0.4f, pct, 2f) { v -> val w = l.maskSize * l.maskStretch; l.maskSize = v / 100f; l.maskStretch = (w / l.maskSize).coerceIn(0.05f, 20f) }
                dial(body, tr("Ballac", "Width"), l.maskSize * l.maskStretch * 100, 80f, 0.4f, pct, 2f) { v -> l.maskStretch = (v / 100f / l.maskSize).coerceIn(0.05f, 20f) }
            },
            tr("Wareeg", "Rotate") to { body: LinearLayout ->
                dial(body, tr("Wareeg", "Rotate"), l.maskRot, 0f, 0.6f, { "%.0f°".format(it) }) { l.maskRot = it }
            },
            "Feather" to { body: LinearLayout ->
                dial(body, "Feather", l.maskFeather * 100, 0f, 0.4f, pct, 0f, 100f) { l.maskFeather = it / 100f }
            },
            tr("Geesaha", "Round corner") to { body: LinearLayout ->
                if (l.mask != MaskKind.RECT) body.addView(Ui.label(this, tr("Kaliya afargeeska (Rectangle).", "Only for Rectangle.")))
                dial(body, tr("Wareegsanaan", "Round corner"), l.maskRound * 100, 0f, 0.4f, pct, 0f, 100f) { l.maskRound = it / 100f }
            }
        ))
        root.addView(Ui.label(this, tr("Shaashadda: dhexda jiid = dhaqaaji · ↕ dherer · ↔ ballac · ◜ geesaha · ≈ feather · laba farood = weyneyn & wareeji",
            "On screen: drag inside = move · ↕ height · ↔ width · ◜ round corner · ≈ feather · two fingers = size & rotate")))
        buttonRow(root,
            tr("Rog (invert)", "Invert") to { l.maskInvert = !l.maskInvert; live() },
            tr("Dib u deji", "Reset") to { l.maskX = 0.5f; l.maskY = 0.5f; l.maskSize = 0.8f; l.maskStretch = 1f; l.maskRot = 0f; l.maskFeather = 0.1f; l.maskRound = 0f; d.dismiss(); showMask(l) })
        stage.editMask = l
        stage.onEditChanged = { live() }
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
        root.addView(Ui.label(this, tr("Shaashadda: jiid barahaas cad ee dhinacyada si aad u jarto.", "On screen: drag the white dots on the sides to crop.")))
        stage.editCrop = l
        stage.onEditChanged = { live() }
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
        root.addView(Ui.choiceRow(this, listOf(tr("Qalin", "Pen"), "Highlighter", "Neon", "Spray", tr("Xariiq go'an", "Dashed"),
            tr("Khad (calligraphy)", "Calligraphy"), tr("Qalin rasaas", "Pencil"), "Airbrush", tr("Dhibco", "Dots"), "Rainbow", tr("Xariiq laba", "Outline")), 0) { brush.type = it; brush.eraser = false })
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

    // ------------------------------------------------------------------ select several layers

    private var multiMode = false

    private fun startMulti(first: Layer?) {
        panel?.dismiss()
        multiMode = true
        MultiSelect.ids.clear()
        first?.let { MultiSelect.ids.add(it.id) }
        stage.multiMode = true
        stage.selectedLayerId = first?.id
        timeline.selection = null
        timeline.multi = MultiSelect.ids.toSet()
        buildTools()
        toast(tr("Taabo wax kasta oo aad rabto — layer, cod, muuqaal (shaashadda ama timeline-ka). Mar kale taabo si aad uga saarto.",
            "Tap anything you want — layers, audio, clips (on screen or in the timeline). Tap again to remove it."))
    }

    private fun endMulti() {
        multiMode = false
        MultiSelect.ids.clear()
        timeline.multi = emptySet()
        stage.multiMode = false
        setSelection(null)
    }

    override fun onMultiChanged() {
        timeline.multi = MultiSelect.ids.toSet()
        timeline.selection = null
        timeline.invalidate()
        buildTools()
    }

    /** Moves the picked layers so the group sits in the middle (keeps their spacing). */
    private fun centerPicked(picked: List<Layer>, horizontal: Boolean) {
        val poses = picked.map { LayerRenderer.basePose(it, timeMs) }
        val mid = if (horizontal) (poses.minOf { it.cx } + poses.maxOf { it.cx }) / 2f else (poses.minOf { it.cy } + poses.maxOf { it.cy }) / 2f
        val d = 0.5f - mid
        for ((i, l) in picked.withIndex()) {
            val p = poses[i]
            if (horizontal) p.cx += d else p.cy += d
            LayerRenderer.writePose(l, timeMs, p)
        }
        commit()
    }

    private fun toggleKeyframe(save: Boolean = true) {
        val l = selectedLayer() ?: return
        if (l.isEffect()) return
        if (!l.isActive(timeMs)) { toast(tr("Dhig xariiqda layer-ka dhexdiisa", "Move the playhead over the layer")); return }
        val k = LayerRenderer.keyframeAt(l, timeMs)
        if (k != null) {
            l.keyframes.remove(k)
            if (l.keyframes.isEmpty()) { l.cx = k.cx; l.cy = k.cy; l.scale = k.scale; l.rotation = k.rotation; l.opacity = k.opacity; l.stretchX = k.sx; l.stretchY = k.sy; l.rotX = k.rx; l.rotY = k.ry; l.posZ = k.z }
            toast(tr("Keyframe waa la tirtiray", "Keyframe removed"))
        } else {
            val p = LayerRenderer.basePose(l, timeMs)
            val prev = l.keyframes.filter { it.t < timeMs - l.startMs }.maxByOrNull { it.t }
            l.keyframes.add(Keyframe(timeMs - l.startMs, p.cx, p.cy, p.scale, p.rotation, p.opacity, p.sx, p.sy, rx = p.rx, ry = p.ry, z = p.z).also { nk ->
                if (prev != null) { nk.ease = prev.ease; nk.bx1 = prev.bx1; nk.by1 = prev.by1; nk.bx2 = prev.bx2; nk.by2 = prev.by2 }
            })
            if (l.keyframes.size == 1) toast(tr("Keyframe waa la daray. U dhaqaaji waqti kale oo layer-ka beddel — keyframe cusub ayaa samaysmaya.",
                "Keyframe added. Move to another time and change the layer — a new keyframe is made automatically."))
        }
        if (save) commit() else live()
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
        // the copy sits exactly on top of the original (same place, same size)
        if (l.isEffect()) { b.startMs = l.endMs; b.endMs = l.endMs + l.durationMs }
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

    /** All layers in a list (top = in front). ▲ / ▼ move a layer up or down, tap a name to select it. */
    private fun showLayers() {
        if (project.layers.isEmpty()) { toast(tr("Layer ma jiro weli", "No layers yet")); return }
        val (d, root) = Ui.sheet(this, tr("Layer-ada", "Layers")) { commit() }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun rebuild() {
            list.removeAllViews()
            val order = project.layers.filter { !it.isEffect() }.reversed()
            for ((pos, l) in order.withIndex()) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    val sel = l.id == selectedLayer()?.id
                    background = Ui.roundBg(if (sel) 0x2219D3C5 else Ui.SURFACE2, dp(12f).toFloat(), if (sel) dp(1.5f) else 0, Ui.ACCENT)
                    setPadding(dp(12f), dp(2f), dp(4f), dp(2f))
                }
                row.addView(Ui.text(this, "${pos + 1}", 12f, Ui.TEXT2), LinearLayout.LayoutParams(dp(22f), ViewGroup.LayoutParams.WRAP_CONTENT))
                row.addView(Ui.text(this, (if (l.linkGroup != null) "🔗 " else "") + layerTitle(l), 14f).apply {
                    maxLines = 1; setPadding(0, dp(10f), 0, dp(10f))
                    setOnClickListener { setSelection(TimelineView.Sel.LayerSel(l.id)); stage.selectedLayerId = l.id; rebuild() }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                // up = towards the front (later in the list)
                row.addView(Ui.iconButton(this, R.drawable.ic_bring_forward, 20f, if (pos > 0) Ui.TEXT else 0x33FFFFFF) {
                    moveLayerInStack(l, 1); rebuild()
                })
                row.addView(Ui.iconButton(this, R.drawable.ic_send_backward, 20f, if (pos < order.size - 1) Ui.TEXT else 0x33FFFFFF) {
                    moveLayerInStack(l, -1); rebuild()
                })
                list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6f) })
            }
        }
        root.addView(Ui.label(this, tr("Kan ugu sarreeya ayaa hore u muuqda. ▲ kor u qaad · ▼ hoos u dhig", "The top one shows in front. ▲ bring up · ▼ send down")))
        root.addView(list)
        rebuild()
        if (project.layers.any { it.kind == LayerKind.VIDEO } && project.layers.any { it.kind != LayerKind.VIDEO && !it.isEffect() })
            root.addView(Ui.label(this, tr("Fiiro: muuqaallada (video overlay) had iyo jeer waxay ku jiraan qoraalka iyo sawirada gadaashooda.", "Note: overlay videos always sit behind text and pictures.")))
        d.show()
    }

    /** Moves a layer one step up (+1, towards the front) or down (-1), skipping effect layers. */
    private fun moveLayerInStack(l: Layer, d: Int) {
        val pics = project.layers.filter { !it.isEffect() }
        val i = pics.indexOf(l); val j = i + d
        if (i < 0 || j !in pics.indices) return
        val other = pics[j]
        val a = project.layers.indexOf(l); val b = project.layers.indexOf(other)
        project.layers[a] = other; project.layers[b] = l
        live()
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

    // ------------------------------------------------------------------ glow, 3D, mockups, tracking

    private fun showGlow(l: Layer) {
        val (d, root) = Ui.sheet(this, "Glow") { commit() }
        root.addView(Ui.colorRow(this, l.glowColor, true) { l.glowColor = it; live() })
        root.addView(Ui.sliderRow(this, tr("Cabbir", "Size"), 0.02f, 1f, l.glowSize.coerceIn(0.02f, 1f)) { l.glowSize = it; live() })
        d.show()
    }

    private var textureTarget: Layer? = null
    private val pickTexture = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val l = textureTarget ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        keep(uri)
        io.execute {
            val mats = so.ijarjar.app.render.Model3D.materials(this, l.uri ?: "")
            main.post {
                fun use(mat: String?) { l.modelTexture = uri.toString(); l.modelMaterial = mat; commit(); toast(tr("Sawirka waa la saaray ✓", "Picture applied ✓")) }
                if (mats.size <= 1) use(null)
                else MaterialAlertDialogBuilder(this).setTitle(tr("Qaybtee sawirka la saarayaa?", "Which part gets the picture?"))
                    .setItems((listOf(tr("Dhammaan", "All parts")) + mats).toTypedArray()) { _, i -> use(if (i == 0) null else mats[i - 1]) }.show()
            }
        }
    }

    // ------------------------------------------------------------------ real 3D phones + model parts

    private var partTarget: Pair<Layer, String>? = null
    private val pickPartMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val (l, mat) = partTarget ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        keep(uri)
        val isVideo = contentResolver.getType(uri)?.startsWith("video") == true
        // only one video at a time on a model
        if (isVideo) for (p in l.parts.values) if (p.video) { p.video = false; p.tex = null }
        val part = l.parts.getOrPut(mat) { so.ijarjar.app.model.ModelPart(name = mat) }
        part.tex = uri.toString(); part.video = isVideo
        if (isVideo) io.execute {
            val info = MediaUtils.probe(this, uri)
            main.post { if (info != null && info.durationMs > l.durationMs && !photo) l.endMs = l.startMs + info.durationMs; commit() }
        } else commit()
        toast(if (isVideo) tr("Video-ga waa lagu xiray qaybta ✓", "Video linked to the part ✓") else tr("Sawirka waa lagu xiray qaybta ✓", "Picture linked to the part ✓"))
    }

    /** Pick one of the built-in 3D phones, then a screenshot or video for its screen. */
    private fun showPhonePicker() {
        val (d, root) = Ui.sheet(this, tr("Taleefan 3D", "3D phone"))
        root.addView(Ui.label(this, tr("Taleefan 3D ah oo dhab ah — wareeji XYZ, shaashaddana sawir ama video geli.", "A real 3D phone — turn it in XYZ and put a screenshot or video on its screen.")))
        chipRow(root, so.ijarjar.app.render.PhoneGlb.Style.entries.map { R.drawable.ic_mockup to it.label }) { i ->
            d.dismiss()
            val style = so.ijarjar.app.render.PhoneGlb.Style.entries[i]
            io.execute {
                val f = so.ijarjar.app.render.PhoneGlb.file(this, style)
                main.post {
                    val l = Layer(kind = LayerKind.MODEL3D, uri = Uri.fromFile(f).toString(), name = tr("Taleefan 3D", "3D phone") + " · " + style.label,
                        baseW = 0.85f, contentAspect = 1f)
                    l.phoneStyle = style.name; l.rotY = -22f; l.rotX = 8f
                    newLayerTimes(l, 5000)
                    addLayer(l)
                    partTarget = l to "Screen"
                    toast(tr("Hadda dooro sawir ama video shaashadda", "Now pick a picture or video for the screen"))
                    pickPartMedia.launch(media())
                }
            }
        }
        d.show()
    }

    /** Swap the phone model (keeps the screen, position and animation). */
    private fun showPhoneStyle(l: Layer) {
        val (d, root) = Ui.sheet(this, tr("Beddel taleefanka", "Replace phone")) { commit() }
        chipRow(root, so.ijarjar.app.render.PhoneGlb.Style.entries.map { R.drawable.ic_mockup to it.label }) { i ->
            val style = so.ijarjar.app.render.PhoneGlb.Style.entries[i]
            io.execute {
                val f = so.ijarjar.app.render.PhoneGlb.file(this, style)
                main.post { l.uri = Uri.fromFile(f).toString(); l.phoneStyle = style.name; l.name = tr("Taleefan 3D", "3D phone") + " · " + style.label; live() }
            }
        }
        root.addView(Ui.label(this, tr("Midabka jirka", "Body colour")))
        root.addView(Ui.colorRow(this, l.parts["Body"]?.color ?: 0, true) { c ->
            for (m in listOf("Body", "Island", "Frame")) l.parts.getOrPut(m) { so.ijarjar.app.model.ModelPart(name = m) }.color = c
            live()
        })
        buttonRow(root, tr("Beddel shaashadda", "Replace screen") to { d.dismiss(); partTarget = l to "Screen"; pickPartMedia.launch(media()) })
        d.show()
    }

    /** Puts an existing picture / video layer onto the screen of a 3D phone. */
    private fun putInPhone(src: Layer) {
        val uri = src.uri ?: return
        io.execute {
            val f = so.ijarjar.app.render.PhoneGlb.file(this, so.ijarjar.app.render.PhoneGlb.Style.PRO)
            main.post {
                val l = Layer(kind = LayerKind.MODEL3D, uri = Uri.fromFile(f).toString(), name = tr("Taleefan 3D", "3D phone"), baseW = 0.85f, contentAspect = 1f)
                l.phoneStyle = so.ijarjar.app.render.PhoneGlb.Style.PRO.name; l.rotY = -22f; l.rotX = 8f
                l.startMs = src.startMs; l.endMs = src.endMs; l.cx = src.cx; l.cy = src.cy
                l.parts["Screen"] = so.ijarjar.app.model.ModelPart("Screen", uri, src.kind == LayerKind.VIDEO)
                val i = project.layers.indexOf(src)
                project.layers[i] = l
                setSelection(TimelineView.Sel.LayerSel(l.id)); commit()
                toast(tr("Waxaa la geliyay taleefan 3D ah ✓", "Now inside a 3D phone ✓"))
            }
        }
    }

    /** Every part of a 3D model with a small picture showing where it is; rename it, put a picture, video or colour on it. */
    private fun showModelParts(l: Layer) {
        val uri = l.uri ?: return
        val (d, root) = Ui.sheet(this, tr("Qaybaha moodelka", "Model parts")) { commit() }
        val status = Ui.label(this, tr("Qaybaha waa la akhrinayaa…", "Reading the parts…"))
        root.addView(status)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        d.show()
        io.execute {
            // parts whose name sounds like a screen come first
            val screenWord = Regex("screen|display|lcd|wallpaper|monitor|panel|oled|ui", RegexOption.IGNORE_CASE)
            val names = so.ijarjar.app.render.Model3D.materials(this, uri).sortedByDescending { screenWord.containsMatchIn(it) }
            main.post {
                status.text = if (names.isEmpty()) tr("Qaybo lama helin.", "No parts found.")
                    else tr("Qaybta casaanka ah ee sawir kasta waa qaybtaas — raadi midka shaashadda (hore) ka casaan ah, kadib 🖼 riix. Magaca taabo si aad u beddesho.",
                        "The pink area in each picture is that part — find the one where the front screen turns pink, then tap 🖼. Tap a name to rename it.")
                val thumbs = HashMap<String, ImageView>()
                for (m in names) {
                    val part = l.parts[m]
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                        background = Ui.roundBg(Ui.SURFACE2, dp(12f).toFloat()); setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
                    }
                    val iv = ImageView(this).apply { background = Ui.roundBg(0xFF15151A.toInt(), dp(8f).toFloat()) }
                    thumbs[m] = iv
                    row.addView(iv, LinearLayout.LayoutParams(dp(84f), dp(84f)))
                    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10f), 0, 0, 0) }
                    val title = Ui.text(this, (part?.name?.takeIf { it.isNotBlank() && it != m } ?: m) + if (screenWord.containsMatchIn(m)) "  📱" else "", 14f, Ui.TEXT, true)
                    title.setOnClickListener {
                        val input = editText(title.text.toString(), tr("Magac", "Name")) {}
                        MaterialAlertDialogBuilder(this).setTitle(tr("Magaca qaybta", "Part name")).setView(input)
                            .setPositiveButton("OK") { _, _ ->
                                val n = input.text.toString().trim()
                                l.parts.getOrPut(m) { so.ijarjar.app.model.ModelPart() }.name = n
                                title.text = n.ifBlank { m }
                            }.setNegativeButton(tr("Jooji", "Cancel"), null).show()
                    }
                    col.addView(title)
                    val what = when { part?.video == true -> "🎬 video"; part?.tex != null -> "🖼 " + tr("sawir", "picture"); part?.color != null && part.color != 0 -> "● " + tr("midab", "colour"); else -> tr("asal", "original") }
                    col.addView(Ui.text(this, what, 11f, Ui.TEXT2))
                    val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    fun small(icon: Int, f: () -> Unit) = btns.addView(Ui.iconButton(this, icon, 18f, Ui.ACCENT) { f() })
                    small(R.drawable.ic_image_add) { partTarget = l to m; d.dismiss(); pickPartMedia.launch(media()) }
                    small(R.drawable.ic_filter) {
                        pickColor(this, l.parts[m]?.color?.takeIf { it != 0 } ?: 0xFF8A8F98.toInt()) { c -> l.parts.getOrPut(m) { so.ijarjar.app.model.ModelPart(name = m) }.color = c; live() }
                    }
                    small(R.drawable.ic_reset) { l.parts[m]?.let { it.tex = null; it.video = false; it.color = 0 }; live() }
                    col.addView(btns)
                    row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6f) })
                }
                // previews one by one
                io.execute {
                    for (m in names) {
                        val b = so.ijarjar.app.render.Model3D.partPreviews(this, uri, listOf(m), 160)[m] ?: continue
                        main.post { thumbs[m]?.setImageBitmap(b) }
                    }
                    main.post { live() }
                }
            }
        }
    }

    private fun showModelColor(l: Layer) {
        val (d, root) = Ui.sheet(this, tr("Midabka moodelka", "Model colour")) { commit() }
        root.addView(Ui.label(this, tr("Midab ku dar (0 = midabkii asalka)", "Tint the model (⦸ = original)")))
        root.addView(Ui.colorRow(this, l.modelColor, true) { l.modelColor = it; live() })
        buttonRow(root,
            tr("Beddel sawirka", "Replace texture") to { textureTarget = l; pickTexture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            tr("Ka saar sawirka", "Remove texture") to { l.modelTexture = null; l.modelMaterial = null; live() })
        root.addView(Ui.label(this, tr("Fiiro: qaybaha moodelka ee aan sawir lahayn, midabka kaliya ayaa beddelma.", "Note: parts of the model without a picture only change colour.")))
        d.show()
    }

    private fun show3D(l: Layer) {
        val (d, root) = Ui.sheet(this, "3D") { commit() }
        val p0 = LayerRenderer.basePose(l, timeMs)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun build() {
            box.removeAllViews()
            val p = LayerRenderer.basePose(l, timeMs)
            fun upd(f: (so.ijarjar.app.render.Pose) -> Unit) { val q = LayerRenderer.basePose(l, timeMs); f(q); LayerRenderer.writePose(l, timeMs, q); live() }
            val deg = { v: Float -> "%.1f°".format(v) }
            fun dial(label: String, v: Float, def: Float, perDp: Float, fmt: (Float) -> String, min: Float = -Float.MAX_VALUE, set: (Float) -> Unit) =
                box.addView(ScrubDial(this, label, v, def, perDp, fmt, min, Float.MAX_VALUE, set), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4f) })
            dial(tr("Wareeg X", "Rotate X"), p.rx, 0f, 0.6f, deg) { v -> upd { it.rx = v } }
            dial(tr("Wareeg Y", "Rotate Y"), p.ry, 0f, 0.6f, deg) { v -> upd { it.ry = v } }
            dial(tr("Wareeg Z", "Rotate Z"), p.rotation, 0f, 0.6f, deg) { v -> upd { it.rotation = v } }
            dial(tr("Booska X", "Position X"), p.cx * 100, 50f, 0.25f, { "%.1f%%".format(it) }) { v -> upd { it.cx = v / 100f } }
            dial(tr("Booska Y", "Position Y"), p.cy * 100, 50f, 0.25f, { "%.1f%%".format(it) }) { v -> upd { it.cy = v / 100f } }
            dial(tr("Booska Z (fog)", "Position Z"), p.z * 1000, 0f, 3f, { "%.0f".format(it) }, -800f) { v -> upd { it.z = v / 1000f } }
            dial(tr("Cabbir", "Scale"), p.scale * 100, 100f, 0.5f, { "%.1f%%".format(it) }, 0f) { v -> upd { it.scale = v / 100f } }
        }
        root.addView(Ui.label(this, tr("Farta ku jiid bidix/midig — dhammaad ma laha. Laba jeer taabo ama ↺ si aad 0 ugu celiso; 0 wuu ku istaagaa.",
            "Drag left / right — there's no end. Double-tap or ↺ resets; it stops at 0 for a moment.")))
        root.addView(box)
        build()
        buttonRow(root,
            tr("Dib u deji 3D", "Reset 3D") to { val q = LayerRenderer.basePose(l, timeMs); q.rx = 0f; q.ry = 0f; q.z = 0f; LayerRenderer.writePose(l, timeMs, q); live(); build() },
            tr("Wareeg 360°", "Spin 360°") to {
                val b = LayerRenderer.basePose(l, l.startMs)
                l.keyframes.clear()
                l.keyframes.add(Keyframe(0, b.cx, b.cy, b.scale, b.rotation, b.opacity, b.sx, b.sy, Easing.EASE_IN_OUT, rx = b.rx, ry = 0f, z = b.z))
                l.keyframes.add(Keyframe(l.durationMs, b.cx, b.cy, b.scale, b.rotation, b.opacity, b.sx, b.sy, Easing.EASE_IN_OUT, rx = b.rx, ry = 360f, z = b.z))
                live(); build()
            },
            "◆ Keyframe" to { d.dismiss(); showKeyframes(l) })
        if (l.kind == LayerKind.MODEL3D) buttonRow(root, tr("Qaybaha (sawir, video, midab)", "Parts (picture, video, colour)") to { d.dismiss(); showModelParts(l) })
        if (p0.rx == 0f && p0.ry == 0f && l.keyframes.isEmpty()) root.addView(Ui.label(this, tr("Talo: ◆ Keyframe ku dar si 3D-gu u dhaqaaqo.", "Tip: add ◆ keyframes to animate in 3D.")))
        d.show()
    }

    private fun showSpin(l: Layer) {
        val (d, root) = Ui.sheet(this, tr("Wareeg joogto", "Auto spin")) { commit() }
        root.addView(Ui.sliderRow(this, tr("Xawaare", "Turns / sec"), -1f, 1f, l.modelSpin.coerceIn(-1f, 1f)) { l.modelSpin = it; live() })
        buttonRow(root, tr("Jooji", "Stop") to { l.modelSpin = 0f; d.dismiss() })
        d.show()
    }

    private fun showMockup(l: Layer) {
        val (d, root) = Ui.sheet(this, tr("Mockup (3D)", "Mockup (3D)")) { commit() }
        chipRow(root, so.ijarjar.app.model.MockupKind.entries.map { (if (it == so.ijarjar.app.model.MockupKind.NONE) R.drawable.ic_close else R.drawable.ic_mockup) to it.label }) { i ->
            val k = so.ijarjar.app.model.MockupKind.entries[i]
            l.mockup = k
            if (k != so.ijarjar.app.model.MockupKind.NONE && l.rotY == 0f && l.keyframes.isEmpty()) { l.rotY = -18f; l.rotX = 6f; l.scale = 0.8f }
            live()
        }
        root.addView(Ui.label(this, tr("Midabka", "Colour")))
        root.addView(Ui.colorRow(this, l.mockupColor, false) { l.mockupColor = it; live() })
        buttonRow(root,
            tr("Toos", "Flat") to { l.rotX = 0f; l.rotY = 0f; live() },
            tr("Janjeer", "Tilted") to { l.rotX = 8f; l.rotY = -22f; live() },
            tr("Gadaal", "Back") to { l.rotX = 0f; l.rotY = 180f; l.keyframes.clear(); live() },
            tr("Wareeg 360°", "Spin 360°") to {
                l.keyframes.clear()
                l.keyframes.add(Keyframe(0, l.cx, l.cy, l.scale, l.rotation, l.opacity, ease = Easing.EASE_IN_OUT, ry = 0f, rx = 6f))
                l.keyframes.add(Keyframe(l.durationMs, l.cx, l.cy, l.scale, l.rotation, l.opacity, ease = Easing.EASE_IN_OUT, ry = 360f, rx = 6f))
                live()
            })
        buttonRow(root, "XYZ (3D)" to { d.dismiss(); show3D(l) })
        root.addView(Ui.label(this, tr("Midabyo: titanium, madow, cad, buluug…", "Colours: titanium, black, white, blue…")))
        root.addView(Ui.choiceRow(this, listOf("Titanium", tr("Madow", "Black"), tr("Cad", "White"), tr("Buluug", "Blue"), tr("Dahab", "Gold"), tr("Casaan", "Red")), -1) { i ->
            l.mockupColor = intArrayOf(0xFF8A8F98.toInt(), 0xFF1C1C1E.toInt(), 0xFFE8E8EA.toInt(), 0xFF2D3E5C.toInt(), 0xFFC9B48A.toInt(), 0xFF8E1F2B.toInt())[i]; live()
        })
        root.addView(Ui.label(this, tr("Muuqaalkaaga ama sawirkaaga ayaa shaashadda ka ciyaaraya. Y-ga ka wareeji 90° si aad u aragto gadaashiisa iyo kamaradaha.",
            "Your video or picture plays on the screen. Turn Y past 90° to see the back and cameras.")))
        d.show()
    }

    /** Auto track: the layer follows a moving object in the video. */
    private fun showTrack(l: Layer) {
        val (d, root) = Ui.sheet(this, tr("Raac shay (auto track)", "Auto track")) { commit() }
        root.addView(Ui.label(this, tr("1) Layer-ka dhig shayga aad rabto inuu raaco (bilowga layer-ka).\n2) Riix Bilow.",
            "1) Put the layer on the object at the layer's start.\n2) Tap Start.")))
        var box = 0.12f
        root.addView(Ui.sliderRow(this, tr("Cabbirka sanduuqa", "Box size"), 0.05f, 0.3f, box) { box = it })
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }
        root.addView(bar)
        var cancelled = false
        d.setOnDismissListener { cancelled = true; commit() }
        buttonRow(root, tr("Bilow", "Start") to {
            val ci = project.clipIndexAt(l.startMs)
            val c = project.clips.getOrNull(ci)
            if (c == null || c.kind != MediaKind.VIDEO) { toast(tr("Layer-ku waa inuu ku jiraa muuqaal", "The layer must be over a video clip")); return@to }
            val clipStart = project.clipStartMs(ci)
            val end = minOf(l.endMs, clipStart + c.outDurationMs)
            val p0 = LayerRenderer.basePose(l, l.startMs)
            // canvas position -> video frame position (fit + canvas transform, no rotation)
            val ca = c.width.toFloat().coerceAtLeast(1f) / c.height.coerceAtLeast(1)
            val r = project.aspectRatio()
            val fw = (if (ca > r) 1f else ca / r) * c.tScale
            val fh = (if (ca > r) r / ca else 1f) * c.tScale
            val u0 = ((p0.cx - 0.5f - c.tX) / fw + 0.5f).coerceIn(0f, 1f)
            val v0 = ((p0.cy - 0.5f - c.tY) / fh + 0.5f).coerceIn(0f, 1f)
            val outTimes = ArrayList<Long>(); var t = l.startMs
            while (t < end) { outTimes.add(t); t += 100 }
            val srcTimes = outTimes.map { c.trimStartMs + so.ijarjar.app.render.SpeedMap.outToSrc(c, it - clipStart) }
            bar.visibility = View.VISIBLE
            io.execute {
                val path = so.ijarjar.app.media.Tracker.track(this, Uri.parse(c.uri), srcTimes, u0, v0, box) { pr -> main.post { bar.progress = pr } }
                main.post {
                    if (cancelled) return@post
                    if (path == null || path.isEmpty()) { toast(tr("Lama raaci karo", "Could not track")); return@post }
                    l.keyframes.clear()
                    for ((i, uv) in path.withIndex()) {
                        val cx = 0.5f + c.tX + (uv[0] - 0.5f) * fw
                        val cy = 0.5f + c.tY + (uv[1] - 0.5f) * fh
                        l.keyframes.add(Keyframe(outTimes[i] - l.startMs, cx + (p0.cx - (0.5f + c.tX + (u0 - 0.5f) * fw)), cy + (p0.cy - (0.5f + c.tY + (v0 - 0.5f) * fh)),
                            p0.scale, p0.rotation, p0.opacity, p0.sx, p0.sy, Easing.LINEAR))
                    }
                    d.dismiss()
                    toast(tr("Waa la raacay ✓ (${path.size} keyframe)", "Tracked ✓ (${path.size} keyframes)"))
                }
            }
        })
        d.show()
    }

    // ------------------------------------------------------------------ keyframes (Motion Tools style)

    private var activeGraph: GraphView? = null

    /** Adds keyframes from the playhead: each step is (ms after the playhead, change to the pose). */
    /**
     * Which keyframe curves an ease changes. Like AE, easing a keyframe changes the move INTO it and
     * the move OUT of it (so it also works on the last keyframe). No keyframe at the playhead = all.
     */
    private fun easeTargets(l: Layer, k: Keyframe?): List<Keyframe> {
        val ks = l.keyframes.sortedBy { it.t }
        if (ks.size < 2) return emptyList()
        if (k == null) return ks.dropLast(1)
        val i = ks.indexOf(k)
        return listOfNotNull(ks.getOrNull(i - 1), if (i < ks.size - 1) k else null)
    }

    private fun addMotion(l: Layer, steps: List<Pair<Long, (so.ijarjar.app.render.Pose) -> Unit>>, ease: FloatArray = floatArrayOf(0.333f, 0f, 0.667f, 1f)) {
        val base = LayerRenderer.basePose(l, timeMs)
        val start = (timeMs - l.startMs).coerceIn(0, l.durationMs)
        if (l.keyframes.isEmpty()) LayerRenderer.writePose(l, timeMs, base)   // keep the current values outside the move
        for ((dt, f) in steps) {
            val t = (start + dt).coerceAtMost(l.durationMs)
            val p = so.ijarjar.app.render.Pose(base.cx, base.cy, base.scale, base.rotation, base.opacity, sx = base.sx, sy = base.sy, rx = base.rx, ry = base.ry, z = base.z)
            f(p)
            l.keyframes.removeAll { kotlin.math.abs(it.t - t) < 40 }
            l.keyframes.add(Keyframe(t, p.cx, p.cy, p.scale, p.rotation, p.opacity, p.sx, p.sy, Easing.CUSTOM, ease[0], ease[1], ease[2], ease[3], p.rx, p.ry, p.z))
        }
        live()
    }

    /** Quick tools like the Motion Tools plugin for After Effects. */
    private fun motionTools(body: LinearLayout, l: Layer, after: () -> Unit) {
        body.addView(Ui.label(this, tr("Qalooc (taabo: keyframe-ka hadda, ama dhammaan haddii midna aan la dooran)", "Ease (tap: the current keyframe, or all if none is under the playhead)")))
        val eases = listOf("Linear" to floatArrayOf(0.333f, 0.333f, 0.667f, 0.667f), "Easy Ease" to floatArrayOf(0.333f, 0f, 0.667f, 1f),
            "Ease In" to floatArrayOf(0.333f, 0f, 1f, 1f), "Ease Out" to floatArrayOf(0f, 0f, 0.667f, 1f), "Strong" to floatArrayOf(0.7f, 0f, 0.3f, 1f),
            "Expo" to floatArrayOf(0.9f, 0f, 0.1f, 1f), "Smooth" to floatArrayOf(0.45f, 0f, 0.55f, 1f), "Overshoot" to floatArrayOf(0.3f, 0f, 0.3f, 1.35f),
            "Anticipate" to floatArrayOf(0.4f, -0.35f, 0.6f, 1f))
        val (sv, row) = Ui.hrow(this)
        for ((name, b) in eases) {
            val cv = CurveView(this).apply { easing = Easing.CUSTOM; this.b = b }
            row.addView(tileWithLabel(this, cv, name, 64f) {
                val targets = easeTargets(l, LayerRenderer.keyframeAt(l, timeMs))
                if (targets.isEmpty()) { toast(tr("Marka hore samee ugu yaraan 2 keyframe", "Make at least 2 keyframes first")); return@tileWithLabel }
                for (o in targets) { o.ease = Easing.CUSTOM; o.bx1 = b[0]; o.by1 = b[1]; o.bx2 = b[2]; o.by2 = b[3] }
                live(); toast(name + " ✓ (" + targets.size + ")"); after()
            })
        }
        body.addView(sv)
        body.addView(Ui.label(this, tr("Dhaqdhaqaaq diyaar ah (wuxuu ka bilaabmaa xariiqda)", "Ready moves (start at the playhead)")))
        val moves = listOf<Pair<String, () -> Unit>>(
            "Zoom in" to { addMotion(l, listOf(0L to { _ -> }, 1000L to { p -> p.scale *= 1.2f })) },
            "Zoom out" to { addMotion(l, listOf(0L to { p -> p.scale *= 1.2f }, 1000L to { _ -> })) },
            tr("Weyn-yar (punch)", "Punch zoom") to { addMotion(l, listOf(0L to { _ -> }, 120L to { p -> p.scale *= 1.25f }, 400L to { _ -> }), floatArrayOf(0.2f, 0f, 0.3f, 1f)) },
            "Pop" to { addMotion(l, listOf(0L to { p -> p.scale = 0.01f; p.opacity = 0f }, 300L to { p -> p.scale *= 1.12f }, 500L to { _ -> }), floatArrayOf(0.2f, 0f, 0.3f, 1f)) },
            tr("Bidix ka soo gal", "Slide in ←") to { addMotion(l, listOf(0L to { p -> p.cx -= 1f }, 600L to { _ -> }), floatArrayOf(0f, 0f, 0.2f, 1f)) },
            tr("Midig ka soo gal", "Slide in →") to { addMotion(l, listOf(0L to { p -> p.cx += 1f }, 600L to { _ -> }), floatArrayOf(0f, 0f, 0.2f, 1f)) },
            tr("Hoos ka soo kac", "Rise up") to { addMotion(l, listOf(0L to { p -> p.cy += 0.3f; p.opacity = 0f }, 600L to { _ -> }), floatArrayOf(0f, 0f, 0.2f, 1f)) },
            tr("Soo bax", "Fade in") to { addMotion(l, listOf(0L to { p -> p.opacity = 0f }, 500L to { _ -> })) },
            tr("Libdh", "Fade out") to { addMotion(l, listOf(0L to { _ -> }, 500L to { p -> p.opacity = 0f })) },
            tr("Wareeg 360", "Spin 360") to { addMotion(l, listOf(0L to { _ -> }, 1000L to { p -> p.rotation += 360f })) },
            tr("Wareeg 3D", "Flip 3D") to { addMotion(l, listOf(0L to { p -> p.ry -= 90f; p.opacity = 0f }, 700L to { _ -> })) },
            tr("Gariir", "Shake") to { addMotion(l, (0..8).map { i -> (i * 60L) to { p: so.ijarjar.app.render.Pose -> if (i in 1..7) p.cx += (if (i % 2 == 0) 0.02f else -0.02f) * (8 - i) / 7f } }, floatArrayOf(0.333f, 0.333f, 0.667f, 0.667f)) })
        val (sv2, row2) = Ui.hrow(this)
        for ((name, f) in moves) row2.addView(Ui.button(this, name, false) { f(); after() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6f) })
        body.addView(sv2)
        body.addView(Ui.label(this, tr("Qalab", "Tools")))
        val tools = listOf<Pair<String, () -> Unit>>(
            "Excite" to { l.exprCode["position"] = "bounce(0.06, 2.5, 5)"; l.exprCode["scale"] = "bounce(0.08, 3, 6)"; live(); toast(tr("Keyframe kasta kadib wuu booddaa", "Overshoots after every keyframe")) },
            tr("Rog keyframe-yada", "Reverse keys") to {
                val ks = l.keyframes.sortedBy { it.t }
                if (ks.size > 1) { val a = ks.first().t; val b = ks.last().t; for (k in ks) k.t = a + b - k.t }
                live()
            },
            tr("Keys → xariiqda", "Keys to playhead") to {
                val first = l.keyframes.minOfOrNull { it.t } ?: 0L
                val d = (timeMs - l.startMs) - first
                for (k in l.keyframes) k.t = (k.t + d).coerceIn(0, l.durationMs)
                live()
            },
            tr("Dheereey ×2", "Slower ×2") to { val a = l.keyframes.minOfOrNull { it.t } ?: 0L; for (k in l.keyframes) k.t = (a + (k.t - a) * 2).coerceAtMost(l.durationMs); live() },
            tr("Dedeji ×½", "Faster ×½") to { val a = l.keyframes.minOfOrNull { it.t } ?: 0L; for (k in l.keyframes) k.t = a + (k.t - a) / 2; live() })
        val (sv3, row3) = Ui.hrow(this)
        for ((name, f) in tools) row3.addView(Ui.button(this, name, false) { f(); after() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6f) })
        body.addView(sv3)
    }

    private val KEY_PROPS get() = listOf(tr("Booska X", "Position X"), tr("Booska Y", "Position Y"), tr("Cabbir", "Scale"), tr("Wareeg", "Rotation"),
        tr("Daahsoon", "Opacity"), tr("Wareeg X", "Rotate X"), tr("Wareeg Y", "Rotate Y"), tr("Fog Z", "Position Z"), tr("Ballac", "Width"), tr("Dherer", "Height"))

    /** One endless dial per property; changing it keyframes like After Effects. */
    private fun keyDials(body: LinearLayout, l: Layer, onAny: () -> Unit = {}) {
        val p0 = LayerRenderer.basePose(l, timeMs)
        fun upd(f: (so.ijarjar.app.render.Pose) -> Unit) { val p = LayerRenderer.basePose(l, timeMs); f(p); LayerRenderer.writePose(l, timeMs, p); live(); onAny() }
        val deg = { v: Float -> "%.1f°".format(v) }
        val pct = { v: Float -> "%.1f%%".format(v) }
        fun dial(label: String, v: Float, def: Float, perDp: Float, fmt: (Float) -> String, min: Float = -Float.MAX_VALUE, max: Float = Float.MAX_VALUE, set: (Float) -> Unit) =
            body.addView(ScrubDial(this, label, v, def, perDp, fmt, min, max, set), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4f) })
        dial(KEY_PROPS[0], p0.cx * 100, 50f, 0.25f, pct) { v -> upd { it.cx = v / 100f } }
        dial(KEY_PROPS[1], p0.cy * 100, 50f, 0.25f, pct) { v -> upd { it.cy = v / 100f } }
        dial(KEY_PROPS[2], p0.scale * 100, 100f, 0.5f, pct, 0f) { v -> upd { it.scale = v / 100f } }
        dial(KEY_PROPS[3], p0.rotation, 0f, 0.6f, deg) { v -> upd { it.rotation = v } }
        dial(KEY_PROPS[4], p0.opacity * 100, 100f, 0.4f, pct, 0f, 100f) { v -> upd { it.opacity = v / 100f } }
        dial(KEY_PROPS[5], p0.rx, 0f, 0.6f, deg) { v -> upd { it.rx = v } }
        dial(KEY_PROPS[6], p0.ry, 0f, 0.6f, deg) { v -> upd { it.ry = v } }
        dial(KEY_PROPS[7], p0.z * 1000, 0f, 3f, { v -> "%.0f".format(v) }, -800f) { v -> upd { it.z = v / 1000f } }
        dial(KEY_PROPS[8], p0.sx * 100, 100f, 0.5f, pct, 1f) { v -> upd { it.sx = v / 100f } }
        dial(KEY_PROPS[9], p0.sy * 100, 100f, 0.5f, pct, 1f) { v -> upd { it.sy = v / 100f } }
    }

    /** Keyframes like After Effects: values, graph editor, presets and expressions in one place. */
    private fun showKeyframes(l: Layer, startTab: Int = 0) {
        val (d, root) = Ui.sheet(this, "Keyframes") { commit() }
        // ---- fixed header: ◀ ◆ ▶ + where we are
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val info = Ui.text(this, "", 12f, Ui.TEXT2).apply { gravity = Gravity.CENTER }
        val diamond = Ui.iconButton(this, R.drawable.ic_keyframe, 26f) {}
        var refreshBody: () -> Unit = {}
        fun refreshNav() {
            val ks = l.keyframes.sortedBy { it.t }
            val on = LayerRenderer.keyframeAt(l, timeMs)
            diamond.setImageResource(if (on != null) R.drawable.ic_keyframe_on else R.drawable.ic_keyframe)
            diamond.imageTintList = ColorStateList.valueOf(if (on != null) 0xFFFFCC00.toInt() else Ui.TEXT)
            info.text = if (ks.isEmpty()) tr("Keyframe ma jiro — ◆ riix", "No keyframes — tap ◆")
            else (if (on != null) "◆ ${ks.indexOf(on) + 1}/${ks.size}" else "${ks.size} keyframe") + " · " + TimelineView.fmt(timeMs - l.startMs)
        }
        fun jump(next: Boolean) {
            val rel = timeMs - l.startMs
            val k = if (next) l.keyframes.filter { it.t > rel + 30 }.minByOrNull { it.t } else l.keyframes.filter { it.t < rel - 30 }.maxByOrNull { it.t }
            if (k != null) { onKeyframeTap(l.startMs + k.t); refreshNav(); refreshBody() }
        }
        diamond.setOnClickListener { toggleKeyframe(false); refreshNav(); refreshBody() }
        nav.addView(Ui.iconButton(this, R.drawable.ic_prev) { jump(false) })
        nav.addView(diamond)
        nav.addView(Ui.iconButton(this, R.drawable.ic_next) { jump(true) })
        nav.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        nav.addView(Ui.iconButton(this, R.drawable.ic_delete, 22f, Ui.TEXT2) {
            if (l.keyframes.isEmpty()) return@iconButton
            MaterialAlertDialogBuilder(this).setMessage(tr("Tirtir dhammaan keyframe-yada?", "Remove all keyframes?"))
                .setPositiveButton(tr("Tirtir", "Remove")) { _, _ ->
                    val p = LayerRenderer.basePose(l, timeMs); l.keyframes.clear(); LayerRenderer.writePose(l, timeMs, p); live(); refreshNav(); refreshBody()
                }.setNegativeButton(tr("Maya", "No"), null).show()
        })
        d.top.addView(nav)
        d.top.addView(Ui.choiceRow(this, listOf(tr("◆ Keyframe cusub", "◆ New keyframe"), tr("Beddel kan u dhow", "Edit nearest"), tr("Dhaqaaji dhammaan", "Move all")), LayerRenderer.keyMode) {
            LayerRenderer.keyMode = it
            toast(when (it) {
                0 -> tr("Marka aad layer-ka beddesho, keyframe cusub ayaa la samaynayaa (sida AE).", "Changing the layer adds a keyframe at the playhead (like AE).")
                1 -> tr("Isbeddelku wuxuu galayaa keyframe-ka ugu dhow — mid cusub lama samaynayo.", "Changes go into the nearest keyframe — no new one is made.")
                else -> tr("Animation-ka oo dhan ayaa dhaqaaqaya — keyframe-yada lama tirtirayo.", "The whole animation moves — no keyframe is removed.")
            })
        })
        refreshNav()

        Ui.tabs(this, root, listOf(
            tr("Qiimaha", "Values") to { body: LinearLayout ->
                refreshBody = { body.removeAllViews(); keyDials(body, l) { refreshNav() } }
                keyDials(body, l) { refreshNav() }
            },
            tr("Garaaf", "Graph") to { body: LinearLayout ->
                val graph = GraphView(this, l, { live() }) { t -> onKeyframeTap(t); refreshNav() }
                activeGraph = graph
                // no keyframe is picked until you tap one, so scrolling never bends a curve by accident
                graph.selected = null
                graph.playheadMs = timeMs
                refreshBody = { graph.playheadMs = timeMs; graph.invalidate() }
                body.addView(Ui.choiceRow(this, KEY_PROPS, 0) { graph.prop = it })
                val zr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                zr.addView(Ui.text(this, tr("Zoom (laba farood ama):", "Zoom (two fingers, or):"), 12f, Ui.TEXT2), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                zr.addView(Ui.button(this, "−", false) { graph.zoom(0.5f, timeMs - l.startMs) })
                zr.addView(Ui.button(this, "+", false) { graph.zoom(2f, timeMs - l.startMs) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6f) })
                zr.addView(Ui.button(this, tr("Dhammaan", "Fit"), false) { graph.fit() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6f) })
                body.addView(zr)
                body.addView(graph, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(200f)).apply { topMargin = dp(6f) })
                body.addView(Ui.label(this, tr("Taabo keyframe ◆, kadib jiid labada bar ee cad si aad u beddesho xawaaraha (sida AE Graph Editor).",
                    "Tap a keyframe ◆, then drag the two white handles to shape its speed (like the AE Graph Editor).")))
                fun setEase(e: Easing, b: FloatArray?, all: Boolean) {
                    val targets = if (all) l.keyframes else easeTargets(l, graph.selected ?: LayerRenderer.keyframeAt(l, timeMs)).ifEmpty { l.keyframes }
                    for (k in targets) { k.ease = e; if (b != null) { k.bx1 = b[0]; k.by1 = b[1]; k.bx2 = b[2]; k.by2 = b[3] } }
                    live(); graph.invalidate()
                }
                var all = false
                body.addView(Ui.choiceRow(this, listOf(tr("Keyframe-kan", "This keyframe"), tr("Dhammaan", "All keyframes")), 0) { all = it == 1 })
                body.addView(Ui.choiceRow(this, listOf("Easy Ease", "Ease In", "Ease Out", "Linear", "Hold", "Back", "Bounce", "Elastic"), -1) { i ->
                    when (i) {
                        0 -> setEase(Easing.CUSTOM, floatArrayOf(0.333f, 0f, 0.667f, 1f), all)
                        1 -> setEase(Easing.CUSTOM, floatArrayOf(0.333f, 0f, 1f, 1f), all)
                        2 -> setEase(Easing.CUSTOM, floatArrayOf(0f, 0f, 0.667f, 1f), all)
                        3 -> setEase(Easing.LINEAR, null, all)
                        4 -> setEase(Easing.HOLD, null, all)
                        5 -> setEase(Easing.BACK, null, all)
                        6 -> setEase(Easing.BOUNCE, null, all)
                        else -> setEase(Easing.ELASTIC, null, all)
                    }
                })
                val k0 = graph.selected ?: LayerRenderer.keyframeAt(l, timeMs) ?: l.keyframes.minByOrNull { it.t }
                if (k0 != null) {
                    body.addView(Ui.sliderRow(this, tr("Saameyn bax %", "Influence out %"), 0f, 100f, (if (k0.ease == Easing.CUSTOM) k0.bx1 else 0.333f) * 100) { v ->
                        (graph.selected ?: LayerRenderer.keyframeAt(l, timeMs) ?: k0).let { k -> if (k.ease != Easing.CUSTOM) { k.ease = Easing.CUSTOM; k.bx1 = 0.333f; k.by1 = 0f; k.bx2 = 0.667f; k.by2 = 1f }; k.bx1 = v / 100f; live(); graph.invalidate() }
                    })
                    body.addView(Ui.sliderRow(this, tr("Saameyn gal %", "Influence in %"), 0f, 100f, (1f - (if (k0.ease == Easing.CUSTOM) k0.bx2 else 0.667f)) * 100) { v ->
                        (graph.selected ?: LayerRenderer.keyframeAt(l, timeMs) ?: k0).let { k -> if (k.ease != Easing.CUSTOM) { k.ease = Easing.CUSTOM; k.bx1 = 0.333f; k.by1 = 0f; k.bx2 = 0.667f; k.by2 = 1f }; k.bx2 = 1f - v / 100f; live(); graph.invalidate() }
                    })
                }
            },
            tr("Diyaar", "Presets") to { body: LinearLayout ->
                body.addView(Ui.label(this, tr("Preset-ku wuxuu galiyaa keyframe-yo dhab ah — kadib waad beddeli kartaa.", "A preset adds real keyframes — you can edit them after.")))
                val sample = if (l.kind == LayerKind.TEXT) sampleText(l) else "★"
                tileRow(body, so.ijarjar.app.data.Presets.builtIn, { false }, { it.name }, { pr ->
                    AnimTile(this, sample) { it.endMs = 2600; it.textSizeFrac = 0.24f; so.ijarjar.app.data.Presets.apply(pr, it) }
                }) { pr -> so.ijarjar.app.data.Presets.apply(pr, l); previewAnim(l, !pr.fromEnd); live(); refreshNav() }
                val mine = so.ijarjar.app.data.Presets.load(this)
                if (mine.isNotEmpty()) {
                    body.addView(Ui.label(this, tr("Kuwaaga", "Yours")))
                    tileRow(body, mine, { false }, { it.name }, { pr -> AnimTile(this, sample) { it.endMs = 2600; so.ijarjar.app.data.Presets.apply(pr, it) } }) { pr ->
                        so.ijarjar.app.data.Presets.apply(pr, l); previewAnim(l, true); live(); refreshNav()
                    }
                }
                buttonRow(body, tr("Keydi animation-kan", "Save this animation") to { d.dismiss(); showPresets(l) })
            },
            "Motion Tools" to { body: LinearLayout -> motionTools(body, l) { refreshNav() } },
            "Expression" to { body: LinearLayout -> expressionEditor(body, l) }
        ), startTab)
        d.show()
    }

    /** Expression code per property, like After Effects (Alt-click the stopwatch). */
    private fun expressionEditor(body: LinearLayout, l: Layer) {
        val props = LayerRenderer.EXPR_PROPS
        val names = listOf(tr("Booska", "Position"), tr("Cabbir", "Scale"), tr("Wareeg", "Rotation"), tr("Daahsoon", "Opacity"),
            tr("Wareeg X", "Rotate X"), tr("Wareeg Y", "Rotate Y"), tr("Fog Z", "Position Z"))
        var prop = props.firstOrNull { !l.exprCode[it].isNullOrBlank() } ?: "position"
        val status = Ui.text(this, "", 12f, Ui.TEXT2)
        val code = EditText(this).apply {
            setTextColor(0xFFE6E6E6.toInt()); setHintTextColor(Ui.TEXT2)
            typeface = android.graphics.Typeface.MONOSPACE; textSize = 14f
            hint = "wiggle(2, 30)"
            background = Ui.roundBg(0xFF15151A.toInt(), dp(10f).toFloat(), dp(1f), 0x33FFFFFF)
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            minLines = 3; gravity = Gravity.TOP or Gravity.START
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setHorizontallyScrolling(false)
        }
        fun check() {
            val src = code.text.toString()
            if (src.isBlank()) { status.text = tr("Expression ma jiro", "No expression"); status.setTextColor(Ui.TEXT2); return }
            val ctx = so.ijarjar.app.render.ExprEngine.Ctx(1.0, LayerRenderer.propValue(LayerRenderer.basePose(l, timeMs), prop),
                { LayerRenderer.propValue(LayerRenderer.basePose(l, l.startMs + (it * 1000).toLong()), prop) }, l.keyframes.map { it.t / 1000.0 }.sorted(), l.durationMs / 1000.0,
                so.ijarjar.app.render.ExprEngine.compW, so.ijarjar.app.render.ExprEngine.compH)
            val err = so.ijarjar.app.render.ExprEngine.test(src, ctx)
            if (err == null) { status.text = tr("✓ Wuu shaqeynayaa", "✓ Works"); status.setTextColor(0xFF34C759.toInt()) }
            else { status.text = "⚠ $err"; status.setTextColor(0xFFFF6B6B.toInt()) }
        }
        var loading = false
        fun load() { loading = true; code.setText(l.exprCode[prop] ?: ""); loading = false; check() }
        code.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: Editable?) {
                if (loading) return
                val src = e?.toString().orEmpty()
                if (src.isBlank()) l.exprCode.remove(prop) else l.exprCode[prop] = src
                check(); live()
            }
        })
        body.addView(Ui.choiceRow(this, names, props.indexOf(prop)) { prop = props[it]; load() })
        body.addView(code, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6f) })
        body.addView(status)
        // quick inserts
        val snippets = listOf("wiggle(2, 30)", "bounce(0.06, 2.5, 5)", "loopOut(\"cycle\")", "loopOut(\"pingpong\")", "value + time * 90",
            "value + [0, sin(time*2)*25]", "linear(time, 0, 1, 0, 100)", "posterizeTime(8)", "time", "value", "random(0, 100)")
        body.addView(Ui.choiceRow(this, snippets, -1) { i ->
            val sn = snippets[i]
            val st = code.selectionStart.coerceAtLeast(0)
            code.text.insert(st, sn)
        })
        body.addView(Ui.label(this, tr("Diyaar — taabo si aad u isticmaasho", "Ready-made — tap to use")))
        val all = so.ijarjar.app.data.ExprPresets.builtIn + so.ijarjar.app.data.ExprPresets.load(this)
        body.addView(Ui.choiceRow(this, all.map { it.name }, -1) { i ->
            val pr = all[i]
            prop = pr.prop; l.exprCode[pr.prop] = pr.code
            body.removeAllViews(); expressionEditor(body, l)
            live()
            if (pr.code.contains("bounce") && l.keyframes.size < 2) toast(tr("Bounce-ku wuxuu raacaa keyframe-yada: samee ugu yaraan 2 keyframe.", "Bounce follows your keyframes: make at least 2 keyframes."))
        })
        val nameBox = editText("", tr("Magaca expression-ka", "Expression name")) {}
        body.addView(nameBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) })
        buttonRow(body,
            tr("Keydi", "Save") to {
                val src = code.text.toString()
                if (src.isBlank()) toast(tr("Marka hore code qor", "Write some code first")) else {
                    val list = so.ijarjar.app.data.ExprPresets.load(this)
                    list.add(so.ijarjar.app.data.ExprPreset(nameBox.text.toString().ifBlank { src.take(18) }, prop, src))
                    so.ijarjar.app.data.ExprPresets.save(this, list)
                    toast(tr("Expression waa la keydiyay ✓", "Expression saved ✓"))
                }
            },
            tr("Ka saar", "Remove") to { l.exprCode.remove(prop); load(); live() })
        body.addView(Ui.label(this, tr("Waxa la heli karo: time, value, wiggle(), loopOut(), bounce(), linear(), ease(), valueAtTime(), random(), sin/cos, posterizeTime(), [x, y]. Booska waa pixel, cabbirka iyo daahsoonaanta %.",
            "Available: time, value, wiggle(), loopOut(), bounce(), linear(), ease(), valueAtTime(), random(), sin/cos, posterizeTime(), [x, y]. Position is in pixels, scale and opacity in %.")))
        load()
    }

    private var presetTarget: Layer? = null
    private val pickPreset = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val l = presetTarget ?: return@registerForActivityResult
        if (uri == null) { showPresets(l); return@registerForActivityResult }
        io.execute {
            val res = so.ijarjar.app.data.PresetImport.import(this, uri)
            main.post {
                when (res) {
                    is so.ijarjar.app.data.PresetImport.Result.Ok -> {
                        val mine = so.ijarjar.app.data.Presets.load(this)
                        mine.addAll(res.presets); so.ijarjar.app.data.Presets.save(this, mine)
                        so.ijarjar.app.data.Presets.apply(res.presets.first(), l); commit()
                        toast(tr("${res.presets.size} preset ayaa la soo geliyay ✓", "${res.presets.size} preset(s) imported ✓"))
                        previewAnim(l, true)
                    }
                    is so.ijarjar.app.data.PresetImport.Result.Fail -> MaterialAlertDialogBuilder(this)
                        .setTitle(tr("Preset-ka lama akhrin", "Couldn't read the preset"))
                        .setMessage(when (res.reason) {
                            "ffx" -> tr("After Effects .ffx waa fayl xiran oo Adobe kaliya akhrin karto. Beddelkeeda: animation-ka AE ka dhoofi Lottie (.json, Bodymovin) ama ku samee halkan oo keydi preset ahaan.",
                                "After Effects .ffx is a closed format only Adobe can read. Instead export the animation from AE as Lottie (.json, Bodymovin), or build it here and save it as a preset.")
                            "nokeys" -> tr("Preset-kan kuma jiraan keyframes Position/Scale/Rotation/Opacity ah (tusaale Lumetri ama effect kale). Midabka: isticmaal .cube LUT.",
                                "This preset has no Position/Scale/Rotation/Opacity keyframes (e.g. Lumetri or another effect). For colour use a .cube LUT.")
                            else -> tr("Faylkan ma aha .prfpset ama .ijpreset.", "This isn't a .prfpset or .ijpreset file.")
                        })
                        .setPositiveButton("OK", null).show()
                }
            }
        }
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
        buttonRow(root,
            tr("Soo geli (.prfpset / .ijpreset)", "Import (.prfpset / .ijpreset)") to { presetTarget = l; d.dismiss(); pickPreset.launch(arrayOf("*/*")) },
            tr("Wadaag presets-kayga", "Share my presets") to {
                if (mine.isEmpty()) toast(tr("Weli preset ma lihid", "No presets yet")) else {
                    val f = File(cacheDir, "IjarJar-presets.ijpreset")
                    f.writeText(so.ijarjar.app.data.PresetImport.toJson(mine))
                    shareMedia(null, f, "application/octet-stream")
                }
            })
        root.addView(Ui.label(this, tr("Premiere Pro .prfpset: Position, Scale, Rotation iyo Opacity keyframes ayaa la akhriyaa. After Effects .ffx waa fayl xiran (binary) — Android kuma furmo. Looks-ka Lumetri (.cube) → Filter → LUT.",
            "Premiere Pro .prfpset: Position, Scale, Rotation and Opacity keyframes are read. After Effects .ffx is a closed binary format and can't be opened. Lumetri looks (.cube) → Filters → LUT.")))
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
    private val stopPreview = Runnable { if (engine.isPlaying) { engine.pause(); updatePlayButton() } }

    /** Plays a few seconds so a change can be heard right away. */
    private fun previewSound(from: Long) {
        main.removeCallbacks(stopPreview)
        timeMs = from.coerceIn(0, (project.durationMs - 1).coerceAtLeast(0)); timeline.timeMs = timeMs
        engine.seekTo(timeMs); engine.play(); updatePlayButton()
        main.postDelayed(stopPreview, 3500)
    }

    /** Voice changer: chipmunk, deep, robot, echo, radio… (tap = hear it). */
    private fun showVoiceChanger(current: VoiceFx, startMs: Long, sfx: so.ijarjar.app.model.SoundFx, set: (VoiceFx) -> Unit) {
        val (d, root) = Ui.sheet(this, tr("Beddel codka", "Voice changer")) { main.removeCallbacks(stopPreview); commit() }
        root.addView(Ui.label(this, tr("Taabo cod si aad isla markiiba u maqasho.", "Tap a voice to hear it straight away.")))
        val from = if (timeMs >= startMs) timeMs else startMs
        val (sv, row) = Ui.hrow(this)
        val chips = ArrayList<View>()
        fun paint(sel: VoiceFx) = VoiceFx.entries.forEachIndexed { i, v ->
            chips[i].background = if (v == sel) Ui.roundBg(0x2219D3C5, dp(14f).toFloat(), dp(2f), Ui.ACCENT) else Ui.roundBg(Ui.SURFACE2, dp(14f).toFloat())
        }
        for (v in VoiceFx.entries) {
            val chip = Ui.iconChip(this, if (v == VoiceFx.NONE) R.drawable.ic_mic else R.drawable.ic_voice_change, v.label) {}
            chip.setOnClickListener { set(v); paint(v); live(); previewSound(from) }
            chips.add(chip)
            row.addView(chip, LinearLayout.LayoutParams(dp(84f), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6f) })
        }
        paint(current)
        root.addView(sv)
        // custom echo + room
        root.addView(Ui.label(this, tr("Echo (adiga dooro)", "Echo (custom)")))
        root.addView(Ui.sliderRow(this, tr("Xoog", "Mix"), 0f, 1f, sfx.echoMix) { sfx.echoMix = it; live() })
        root.addView(Ui.sliderRow(this, tr("Daahitaan ms", "Delay ms"), 30f, 1200f, sfx.echoMs.coerceIn(30f, 1200f)) { sfx.echoMs = it; live() })
        root.addView(Ui.sliderRow(this, tr("Soo noqosho", "Feedback"), 0f, 0.9f, sfx.echoFb.coerceIn(0f, 0.9f)) { sfx.echoFb = it; live() })
        root.addView(Ui.label(this, tr("Qol (reverb)", "Room (reverb)")))
        root.addView(Ui.sliderRow(this, tr("Xoog", "Mix"), 0f, 1f, sfx.roomMix) { sfx.roomMix = it; live() })
        root.addView(Ui.sliderRow(this, tr("Cabbirka qolka", "Room size"), 0f, 1f, sfx.roomSize) { sfx.roomSize = it; live() })
        root.addView(Ui.choiceRow(this, listOf(tr("Qol yar", "Small room"), tr("Hool", "Hall"), tr("Masjid", "Mosque"), tr("Echo buur", "Mountain echo"), tr("Dami", "Off")), -1) { i ->
            when (i) {
                0 -> { sfx.roomMix = 0.35f; sfx.roomSize = 0.25f; sfx.echoMix = 0f }
                1 -> { sfx.roomMix = 0.55f; sfx.roomSize = 0.75f; sfx.echoMix = 0f }
                2 -> { sfx.roomMix = 0.7f; sfx.roomSize = 1f; sfx.echoMix = 0.15f; sfx.echoMs = 420f; sfx.echoFb = 0.3f }
                3 -> { sfx.echoMix = 0.55f; sfx.echoMs = 650f; sfx.echoFb = 0.45f; sfx.roomMix = 0.1f }
                else -> { sfx.echoMix = 0f; sfx.roomMix = 0f }
            }
            d.dismiss(); showVoiceChanger(current, startMs, sfx, set); previewSound(from)
        })
        buttonRow(root, tr("▶ Dhageyso", "▶ Listen") to { previewSound(from) })
        d.show()
    }

    // ------------------------------------------------------------------ text to speech

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private fun showTts() {
        val (d, root) = Ui.sheet(this, tr("Qoraal → cod (TTS)", "Text to speech")) { tts?.stop() }
        val input = editText("", tr("Qor waxa la akhrinayo…", "Type what should be spoken…")) {}.apply { maxLines = 3 }
        d.top.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4f) })
        val status = Ui.label(this, tr("Codadka telefoonka waa la soo rarayaa…", "Loading the phone's voices…"))
        val langs = listOf("so" to "Soomaali", "en" to "English", "ar" to "العربية", "sw" to "Kiswahili", "fr" to "Français", "tr" to "Türkçe")
        var locale = Locale("so")
        var rate = 1f; var pitch = 1f
        var voice: Voice? = null
        var asCaption = true
        val voiceBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun refreshVoices() {
            voiceBox.removeAllViews(); voice = null
            val e = tts ?: return
            if (!ttsReady) return
            val ok = e.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE
            status.text = if (ok) tr("Diyaar ✓ — dooro cod, kadib \"Dhageyso\".", "Ready ✓ — pick a voice, then \"Listen\".")
            else tr("Telefoonkan kuma jiro cod luqaddan ah. Ku rakib app TTS ah oo leh luqaddan (Settings → Text-to-speech), ama isticmaal Cod-duub.",
                "This phone has no voice for this language. Install a text-to-speech app that has it (Settings → Text-to-speech), or use Voiceover.")
            val vs = runCatching { e.voices?.filter { it.locale.language == locale.language && !it.isNetworkConnectionRequired }
                ?.sortedByDescending { it.quality }?.take(8) }.getOrNull().orEmpty()
            if (vs.size > 1) {
                voiceBox.addView(Ui.label(this, tr("Codka", "Voice")))
                voiceBox.addView(Ui.choiceRow(this, vs.mapIndexed { i, v -> tr("Cod ${i + 1}", "Voice ${i + 1}") + if (v.name.contains("female", true)) " ♀" else if (v.name.contains("male", true)) " ♂" else "" }, 0) { voice = vs[it] })
                voice = vs[0]
            }
        }
        fun prepare(): TextToSpeech? {
            val e = tts ?: return null
            e.language = locale
            voice?.let { runCatching { e.voice = it } }
            e.setSpeechRate(rate); e.setPitch(pitch)
            return e
        }
        root.addView(status)
        root.addView(Ui.label(this, tr("Luqadda", "Language")))
        root.addView(Ui.choiceRow(this, langs.map { it.second }, 0) { locale = Locale(langs[it].first); refreshVoices() })
        root.addView(voiceBox)
        root.addView(Ui.sliderRow(this, tr("Xawaare", "Speed"), 0.5f, 2f, 1f) { rate = it })
        root.addView(Ui.sliderRow(this, tr("Heerka codka", "Pitch"), 0.5f, 2f, 1f) { pitch = it })
        root.addView(Ui.choiceRow(this, listOf(tr("Qoraal-hoosaad: Haa", "Caption: on"), tr("Qoraal-hoosaad: Maya", "Caption: off")), 0) { asCaption = it == 0 })
        buttonRow(root,
            tr("▶ Dhageyso", "▶ Listen") to {
                val txt = input.text.toString().trim()
                if (txt.isEmpty()) toast(tr("Marka hore wax qor", "Type something first"))
                else prepare()?.speak(txt, TextToSpeech.QUEUE_FLUSH, null, "preview")
            },
            tr("+ Ku dar", "+ Add") to {
                val txt = input.text.toString().trim()
                val e = prepare()
                if (txt.isEmpty()) toast(tr("Marka hore wax qor", "Type something first"))
                else if (e != null) {
                    val dir = File(filesDir, "tts").apply { mkdirs() }
                    val f = File(dir, "tts_${System.currentTimeMillis()}.wav")
                    val id = "tts" + System.nanoTime()
                    val at = timeMs
                    val cap = asCaption
                    e.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        @Deprecated("old api") override fun onError(utteranceId: String?) { main.post { toast(tr("Codka lama samayn karin", "Couldn't make the voice")) } }
                        override fun onDone(utteranceId: String?) {
                            if (utteranceId != id) return
                            val len = runCatching {
                                val r = android.media.MediaMetadataRetriever(); r.setDataSource(f.absolutePath)
                                val v = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong(); r.release(); v
                            }.getOrNull() ?: 2000L
                            main.post {
                                project.audios.add(AudioTrack(uri = Uri.fromFile(f).toString(), name = "TTS · " + txt.take(20), kind = AudioKind.VOICE,
                                    startMs = at, durationMs = len, sourceDurationMs = len))
                                if (cap && !photo) addCaptionText(txt, at, at + len)
                                commit()
                                toast(tr("Codka waa lagu daray ✓", "Voice added ✓"))
                            }
                        }
                    })
                    e.synthesizeToFile(txt, Bundle(), f, id)
                    toast(tr("Codka waa la samaynayaa…", "Making the voice…"))
                }
            })
        root.addView(Ui.label(this, tr("Fiiro: voice clone (codkaaga oo la koobiyeeyo) wuxuu u baahan yahay AI weyn oo server ah — telefoonka dhexdiisa si fiican uguma shaqeeyo, sidaas darteed lama darin.",
            "Note: voice cloning needs a large AI model on a server — it can't run well inside the phone, so it isn't included.")))
        d.show()
        if (tts == null) tts = TextToSpeech(this) { st -> main.post { ttsReady = st == TextToSpeech.SUCCESS
            if (!ttsReady) status.text = tr("Telefoonkan ma laha TTS engine.", "This phone has no text-to-speech engine.") else refreshVoices() } }
        else refreshVoices()
    }

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
            R.drawable.ic_image_add to tr("Gallery", "Gallery"),
            R.drawable.ic_overlay to tr("Fayl (MOV, GIF…)", "File (MOV, GIF…)"),
            R.drawable.ic_model3d to tr("Model 3D", "3D model"),
            R.drawable.ic_mockup to tr("Taleefan 3D", "3D phone"),
            R.drawable.ic_dlink to tr("Mashruuc sawir", "Photo project"))) { k ->
            d.dismiss()
            when (k) {
                0 -> pickOverlayG.launch(media())
                1 -> pickOverlay.launch(arrayOf("video/*", "image/*", "application/json", "application/zip", "application/octet-stream", "*/*"))
                2 -> pickModel.launch(arrayOf("model/gltf-binary", "model/*", "application/octet-stream"))
                3 -> showPhonePicker()
                else -> linkPhotoProject()
            }
        }
        root.addView(Ui.label(this, tr("Gallery: sawir iyo muuqaal. Fayl: MOV hufan (alpha), GIF, PNG sequence, Lottie (.json).",
            "Gallery: photos and videos. File: transparent MOV (alpha), GIF, PNG sequence, Lottie (.json).")))
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
                tileGrid(body, EffectKind.entries.filter { it.group == gi }, { it == replace?.effect }, { it.label }, { EffectTile(this, thumb, it) }) { e ->
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
            R.drawable.ic_waveform to tr("Codka video", "Audio from video"),
            R.drawable.ic_sound to tr("Fayl cod", "Audio file"))) { k ->
            d.dismiss()
            when (k) {
                0 -> { addAudioKind = AudioKind.MUSIC; pickAudio.launch(arrayOf("audio/*")) }
                1 -> showSfx()
                2 -> if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) showVoiceover()
                     else askMic.launch(Manifest.permission.RECORD_AUDIO)
                3 -> pickVideoAudio.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                else -> { addAudioKind = AudioKind.SOUND; pickAudio.launch(arrayOf("audio/*")) }
            }
        }
        d.show()
    }

    /** Built-in sound effects: tap one to hear it, then "Add" puts it at the playhead. */
    private fun showSfx() {
        val (d, root) = Ui.sheet(this, tr("Dhawaaqyo", "Sound effects")) { sfxPlayer?.release(); sfxPlayer = null }
        var chosen: Sfx.Sound? = null
        val addBtn = Ui.button(this, tr("Dooro dhawaaq si aad u maqasho", "Pick a sound to hear it")) {}.apply { isEnabled = false; alpha = 0.5f }
        addBtn.setOnClickListener {
            val s = chosen ?: return@setOnClickListener
            io.execute {
                val f = Sfx.file(this, s)
                main.post {
                    val len = Sfx.durationMs(f)
                    project.audios.add(AudioTrack(uri = Uri.fromFile(f).toString(), name = s.label, kind = AudioKind.SOUND,
                        startMs = timeMs, durationMs = len, sourceDurationMs = len))
                    commit()
                    toast(tr("“${s.label}” waa lagu daray ", "“${s.label}” added at ") + TimelineView.fmt(timeMs))
                }
            }
        }
        d.top.addView(addBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val sv = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipToPadding = false }
        val grid = GridLayout(this).apply { rowCount = 2; orientation = GridLayout.VERTICAL }
        val chips = ArrayList<View>()
        for (s in Sfx.all) {
            val chip = Ui.iconChip(this, R.drawable.ic_play, s.label) {}
            chip.setOnClickListener {
                chosen = s
                for (c in chips) c.background = Ui.roundBg(Ui.SURFACE2, dp(14f).toFloat())
                chip.background = Ui.roundBg(0x2219D3C5, dp(14f).toFloat(), dp(2f), Ui.ACCENT)
                addBtn.isEnabled = true; addBtn.alpha = 1f
                addBtn.text = tr("+ Ku dar “${s.label}”", "+ Add “${s.label}”")
                io.execute {
                    val f = Sfx.file(this, s)
                    main.post {
                        sfxPlayer?.release()
                        sfxPlayer = runCatching { MediaPlayer.create(this, Uri.fromFile(f))?.also { it.start() } }.getOrNull()
                    }
                }
            }
            chips.add(chip)
            grid.addView(chip, GridLayout.LayoutParams().apply { width = dp(84f); setMargins(dp(3f), dp(3f), dp(3f), dp(3f)) })
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
                val room = (project.durationMs - start).coerceAtLeast(if (project.clips.isEmpty()) maxOf(src, 1000L) else 1000L)
                val a = AudioTrack(uri = uri.toString(), name = name, kind = kind, startMs = start, trimStartMs = 0,
                    durationMs = if (src > 0) minOf(src, room) else room, sourceDurationMs = src, fromVideo = kind == AudioKind.EXTRACTED)
                project.audios.add(a)
                setSelection(TimelineView.Sel.AudioSel(a.id))
                commit()
            }
        }
    }

    private fun showAudioFade(a: AudioTrack) {
        val (d, root) = Ui.sheet(this, "Fade") { commit() }
        val maxS = (a.durationMs / 2000f).coerceIn(0.2f, 10f)
        root.addView(Ui.sliderRow(this, tr("Soo gal (s)", "Fade in (s)"), 0f, maxS, (a.fadeInMs / 1000f).coerceIn(0f, maxS)) { a.fadeInMs = (it * 1000).toLong(); live() })
        root.addView(Ui.sliderRow(this, tr("Ka bax (s)", "Fade out (s)"), 0f, maxS, (a.fadeOutMs / 1000f).coerceIn(0f, maxS)) { a.fadeOutMs = (it * 1000).toLong(); live() })
        d.show()
    }

    /** Finds the beats of a song and marks them on its track (to cut clips on the beat). */
    private fun detectBeats(a: AudioTrack) {
        if (a.beats.isNotEmpty()) {
            MaterialAlertDialogBuilder(this).setMessage(tr("${a.beats.size} garaac ayaa calaamadsan. Ka saar?", "${a.beats.size} beats are marked. Remove them?"))
                .setPositiveButton(tr("Ka saar", "Remove")) { _, _ -> a.beats.clear(); commit() }.setNegativeButton(tr("Maya", "No"), null).show()
            return
        }
        toast(tr("Garaacyada waa la raadinayaa…", "Finding the beats…"))
        fun run() {
            val b = so.ijarjar.app.media.Waveform.beats(this, a.uri) { run() } ?: return
            a.beats.clear(); a.beats.addAll(b); commit()
            toast(tr("${b.size} garaac ayaa la helay — dhibcaha jaalaha ah ee codka ku jira", "${b.size} beats found — the yellow dots on the track"))
        }
        run()
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

    private fun gcd(a: Int, b: Int): Int = if (b == 0) a.coerceAtLeast(1) else gcd(b, a % b)

    private val aspects = listOf("9:16", "16:9", "1:1", "4:5", "4:3", "3:4", "2:3", "3:2", "21:9")

    private fun closestAspect(r: Float): String = aspects.minByOrNull {
        val p = it.split(":"); kotlin.math.abs(p[0].toFloat() / p[1].toFloat() - r)
    } ?: "1:1"

    private fun showBackground() {
        val (d, root) = Ui.sheet(this, tr("Gadaal", "Background")) { commit() }
        root.addView(Ui.colorRow(this, project.bgColor, false) { project.bgColor = it; live() })
        root.addView(Ui.label(this, tr("Midab labaad (gradient)", "Second colour (gradient)")))
        root.addView(Ui.colorRow(this, project.bgColor2, true) { project.bgColor2 = it; live() })
        if (project.bgImageUri == null) buttonRow(root, tr("Dooro sawir", "Choose picture") to { d.dismiss(); pickBackgroundG.launch(imagesOnly()) })
        else buttonRow(root,
            tr("Beddel sawirka", "Change") to { d.dismiss(); pickBackgroundG.launch(imagesOnly()) },
            tr("Muraayad", "Mirror") to { project.bgMirror = !project.bgMirror; live() },
            tr("Ka saar", "Remove") to { project.bgImageUri = null; d.dismiss() })
        d.show()
    }

    // ------------------------------------------------------------------ aspect & export

    /** A colour (or picture) background for videos, so you can start without any footage. */
    private fun showVideoBackground() {
        val (d, root) = Ui.sheet(this, tr("Gadaal (background)", "Background"))
        var color = 0xFF101014.toInt(); var color2 = 0
        var secs = 5f
        root.addView(Ui.label(this, tr("Midab", "Colour")))
        root.addView(Ui.colorRow(this, color, false) { color = it })
        root.addView(Ui.label(this, tr("Midab labaad (gradient)", "Second colour (gradient)")))
        root.addView(Ui.colorRow(this, color2, true) { color2 = it })
        root.addView(Ui.sliderRow(this, tr("Dherer (s)", "Length (s)"), 1f, 60f, secs, 1f) { secs = it })
        buttonRow(root,
            tr("+ Ku dar midab", "+ Add colour") to {
                d.dismiss()
                io.execute {
                    val (w, h) = project.outputSize(720)
                    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val c = android.graphics.Canvas(b)
                    if (color2 != 0) c.drawPaint(android.graphics.Paint().apply { shader = android.graphics.LinearGradient(0f, 0f, 0f, h.toFloat(), color, color2, android.graphics.Shader.TileMode.CLAMP) })
                    else c.drawColor(color)
                    val f = File(File(filesDir, "backgrounds").apply { mkdirs() }, "bg_${System.currentTimeMillis()}.png")
                    FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    val ms = (secs * 1000).toLong()
                    main.post {
                        val clip = Clip(uri = Uri.fromFile(f).toString(), kind = MediaKind.IMAGE, sourceDurationMs = ms, trimStartMs = 0, trimEndMs = ms, width = w, height = h)
                        val at = (selectedClipIndex().takeIf { it >= 0 }?.plus(1)) ?: project.clips.size
                        project.clips.add(at.coerceIn(0, project.clips.size), clip)
                        commit(); toast(tr("Gadaal midab ah waa lagu daray", "Colour background added"))
                    }
                }
            },
            tr("+ Sawir", "+ Picture") to { d.dismiss(); insertAfter = selectedClipIndex(); pickClipsG.launch(imagesOnly()) })
        root.addView(Ui.label(this, tr("Kadib qoraal, sticker, shapes iyo 3D ku dar korkiisa.", "Then add text, stickers, shapes or 3D on top.")))
        d.show()
    }

    private fun showAspect() {
        val labels = listOf("9:16  TikTok", "16:9  YouTube", "1:1", "4:5  Instagram", "4:3", "3:4", "2:3", "3:2", "21:9")
        val (d, root) = Ui.sheet(this, tr("Saamiga shaashadda", "Aspect ratio"))
        root.addView(Ui.choiceRow(this, labels, aspects.indexOf(project.aspect)) { k ->
            project.aspect = aspects[k]
            stage.requestLayout()
            commit()
        })
        // custom ratio / size (e.g. 1080 × 1350 or 5 : 7)
        root.addView(Ui.label(this, tr("Cabbir gaar ah (ballac × dherer)", "Custom (width × height)")))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val cur = project.aspect.split(":")
        val wIn = editText(cur.getOrElse(0) { "9" }, "W") {}.apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val hIn = editText(cur.getOrElse(1) { "16" }, "H") {}.apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        row.addView(wIn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Ui.text(this, "  ×  ", 16f, Ui.TEXT2))
        row.addView(hIn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Ui.button(this, "OK") {
            val w = wIn.text.toString().toIntOrNull() ?: 0; val h = hIn.text.toString().toIntOrNull() ?: 0
            if (w <= 0 || h <= 0 || w.toFloat() / h > 8f || h.toFloat() / w > 8f) { toast(tr("Lambar sax ah geli", "Enter valid numbers")); return@button }
            val g = gcd(w, h)
            project.aspect = "${w / g}:${h / g}"
            stage.requestLayout(); commit(); d.dismiss()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8f) })
        root.addView(row)
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

    private val hideNotice = Runnable { notice.animate().alpha(0f).setDuration(200).withEndAction { notice.visibility = View.GONE }.start() }

    /** A short message on top of the preview. */
    private fun toast(s: String) {
        main.removeCallbacks(hideNotice)
        notice.text = s
        notice.animate().cancel()
        notice.alpha = 0f; notice.visibility = View.VISIBLE
        notice.animate().alpha(1f).setDuration(150).start()
        main.postDelayed(hideNotice, (1800L + s.length * 35L).coerceAtMost(5000L))
    }
}

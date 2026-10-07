package so.ijarjar.app.editor

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.media3.common.util.UnstableApi
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.RangeSlider
import so.ijarjar.app.data.History
import so.ijarjar.app.data.ProjectStore
import so.ijarjar.app.export.Exporter
import so.ijarjar.app.media.MediaUtils
import so.ijarjar.app.model.Clip
import so.ijarjar.app.model.FilterPreset
import so.ijarjar.app.model.Layer
import so.ijarjar.app.model.LayerKind
import so.ijarjar.app.model.MediaKind
import so.ijarjar.app.model.Music
import so.ijarjar.app.model.Project
import so.ijarjar.app.model.newId
import so.ijarjar.app.render.LayerRenderer
import java.io.File
import java.util.concurrent.Executors

@UnstableApi
class EditorActivity : AppCompatActivity(), StageView.Listener, TimelineView.Listener {

    private lateinit var project: Project
    private val history = History()
    private lateinit var stage: StageView
    private lateinit var engine: PreviewEngine
    private lateinit var timeline: TimelineView
    private lateinit var timeLabel: TextView
    private lateinit var playBtn: TextView
    private lateinit var toolRow: LinearLayout
    private lateinit var toolScroll: HorizontalScrollView
    private lateinit var undoBtn: TextView
    private lateinit var redoBtn: TextView
    private lateinit var aspectBtn: TextView

    private var timeMs = 0L
    private var running = false
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var insertAfter = -1
    private var replaceLayerPick: ((Uri) -> Unit)? = null

    private fun dp(v: Float) = Ui.dp(this, v)

    // ------------------------------------------------------------------ pickers

    private val pickClips = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) addClips(uris)
    }
    private val pickOverlay = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) addMediaLayer(uri)
    }
    private val pickMusic = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) setMusic(uri)
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
        val id = intent.getStringExtra("id")
        project = (id?.let { ProjectStore.load(this, it) }) ?: Project()
        buildUi()
        engine = PreviewEngine(this, stage)
        engine.onEnded = { updatePlayButton() }
        history.push(ProjectStore.toJson(project))
        reload()
        if (project.clips.isEmpty()) main.postDelayed({ pickClips.launch(arrayOf("video/*", "image/*")) }, 300)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
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
            stage.timeMs = timeMs
            stage.refresh()
            timeLabel.text = TimelineView.fmt(timeMs) + " / " + TimelineView.fmt(project.durationMs)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    // ------------------------------------------------------------------ UI

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
        }

        // top bar
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
        }
        top.addView(iconBtn("✕") { save(); finish() })
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        undoBtn = iconBtn("↶") { undo() }
        redoBtn = iconBtn("↷") { redo() }
        top.addView(undoBtn); top.addView(redoBtn)
        aspectBtn = Ui.text(this, project.aspect, 13f).apply {
            background = Ui.roundBg(Ui.SURFACE2, dp(14f).toFloat())
            setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
            setOnClickListener { showAspect() }
        }
        top.addView(aspectBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8f); marginEnd = dp(8f) })
        top.addView(Ui.button(this, "Dhoofi ⬆") { showExport() })
        root.addView(top)

        // stage
        val stageBox = FrameLayout(this).apply { setBackgroundColor(Ui.BG) }
        stage = StageView(this)
        stage.project = project
        stage.listener = this
        stageBox.addView(stage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        stageBox.setPadding(dp(12f), dp(4f), dp(12f), dp(4f))
        root.addView(stageBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // play row
        val playRow = FrameLayout(this).apply { setPadding(dp(14f), dp(2f), dp(14f), dp(2f)) }
        timeLabel = Ui.text(this, "00:00 / 00:00", 12f, Ui.TEXT2)
        playRow.addView(timeLabel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL))
        playBtn = Ui.text(this, "▶", 24f).apply {
            gravity = Gravity.CENTER
            setOnClickListener { togglePlay() }
        }
        playRow.addView(playBtn, FrameLayout.LayoutParams(dp(48f), dp(40f), Gravity.CENTER))
        root.addView(playRow)

        // timeline with an add button
        val tlBox = FrameLayout(this)
        timeline = TimelineView(this)
        timeline.listener = this
        tlBox.addView(timeline, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val add = Ui.text(this, "＋", 22f, 0xFF00201E.toInt(), true).apply {
            gravity = Gravity.CENTER
            background = Ui.roundBg(Ui.TEXT, dp(8f).toFloat())
            setOnClickListener {
                insertAfter = -1
                pickClips.launch(arrayOf("video/*", "image/*"))
            }
        }
        tlBox.addView(add, FrameLayout.LayoutParams(dp(40f), dp(40f), Gravity.END or Gravity.TOP).apply { topMargin = dp(36f); marginEnd = dp(8f) })
        root.addView(tlBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220f)))

        // tools
        toolScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(Ui.SURFACE)
        }
        toolRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(4f), dp(4f), dp(4f), dp(8f)) }
        toolScroll.addView(toolRow)
        root.addView(toolScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        setContentView(root)
    }

    private fun iconBtn(s: String, onClick: () -> Unit) = Ui.text(this, s, 22f).apply {
        gravity = Gravity.CENTER
        setPadding(dp(10f), dp(2f), dp(10f), dp(2f))
        setOnClickListener { onClick() }
    }

    private fun updatePlayButton() {
        playBtn.text = if (engine.isPlaying) "❚❚" else "▶"
    }

    private fun togglePlay() {
        if (engine.isPlaying) engine.pause() else { engine.seekTo(timeMs); engine.play() }
        updatePlayButton()
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
        buildTools()
    }

    private fun selectedClipIndex(): Int = (selection as? TimelineView.Sel.ClipSel)?.index ?: -1
    private fun selectedLayer(): Layer? = (selection as? TimelineView.Sel.LayerSel)?.let { s -> project.layers.firstOrNull { it.id == s.id } }

    private fun clipAt(t: Long): Int {
        var acc = 0L
        for ((i, c) in project.clips.withIndex()) {
            if (t < acc + c.outDurationMs) return i
            acc += c.outDurationMs
        }
        return project.clips.size - 1
    }

    private fun buildTools() {
        toolRow.removeAllViews()
        fun t(icon: String, label: String, f: () -> Unit) = toolRow.addView(Ui.tool(this, icon, label, f))
        val s = selection
        when {
            s is TimelineView.Sel.ClipSel && project.clips.getOrNull(s.index) != null -> {
                val c = project.clips[s.index]
                t("◀", "Dib") { setSelection(null) }
                t("✂️", "Kala jar") { splitClip(s.index) }
                if (c.kind == MediaKind.VIDEO) t("⏱", "Xawaare") { showSpeed(c) }
                t("🔊", "Cod") { showVolume(c) }
                t("🎨", "Filter") { showAdjust(c) }
                t("📏", "Gooy") { showTrim(c) }
                t("⧉", "Nuqul") { duplicateClip(s.index) }
                t("⬅️", "Bidix") { moveClip(s.index, -1) }
                t("➡️", "Midig") { moveClip(s.index, 1) }
                t("➕", "Ku dar") { insertAfter = s.index; pickClips.launch(arrayOf("video/*", "image/*")) }
                t("🗑", "Tirtir") { deleteClip(s.index) }
            }
            s is TimelineView.Sel.LayerSel && selectedLayer() != null -> {
                val l = selectedLayer()!!
                t("◀", "Dib") { setSelection(null) }
                t("🔗", "Isku xir") { showLink(l) }
                if (l.linkGroup != null) t("⛓", "Kala fur") { unlink(l) }
                if (l.isTextLike()) t("✏️", "Wax ka beddel") { showTextEditor(l) }
                t("✂️", "Kala jar") { splitLayer(l) }
                t("🌫", "Daahsoon") { showOpacity(l) }
                t("↔️", "Rog") { for (g in project.linkedWith(l)) g.flipH = !g.flipH; commit() }
                t("⏮", "Bilow halkan") { moveLayerStart(l) }
                t("⏭", "Dhamee halkan") { if (timeMs > l.startMs) { l.endMs = timeMs; commit() } }
                t("⬆️", "Kor") { reorderLayer(l, 1) }
                t("⬇️", "Hoos") { reorderLayer(l, -1) }
                t("⟲", "Dib u celi") { l.scale = 1f; l.rotation = 0f; l.cx = 0.5f; l.cy = 0.5f; commit() }
                t("⧉", "Nuqul") { duplicateLayer(l) }
                t("🗑", "Tirtir") { deleteLayer(l) }
            }
            s is TimelineView.Sel.MusicSel && project.music != null -> {
                val m = project.music!!
                t("◀", "Dib") { setSelection(null) }
                t("🔊", "Cod") { showMusicVolume(m) }
                t("⏩", "Bilow") { showMusicOffset(m) }
                t("🔁", "Beddel") { pickMusic.launch(arrayOf("audio/*")) }
                t("🗑", "Tirtir") { project.music = null; setSelection(null); commit() }
            }
            else -> {
                t("✂️", "Wax ka beddel") {
                    if (project.clips.isNotEmpty()) setSelection(TimelineView.Sel.ClipSel(clipAt(timeMs)))
                }
                t("🎵", "Muusik") { pickMusic.launch(arrayOf("audio/*")) }
                t("🔤", "Qoraal") { addText() }
                t("😀", "Sticker") { showStickers() }
                t("🖼", "Overlay") { pickOverlay.launch(arrayOf("video/*", "image/*")) }
                t("🔗", "Isku xir") { showLink(null) }
                t("🎨", "Filter") { if (project.clips.isNotEmpty()) showAdjust(project.clips[clipAt(timeMs)]) }
                t("📐", "Saami") { showAspect() }
            }
        }
        toolScroll.scrollTo(0, 0)
    }

    // ------------------------------------------------------------------ commit / undo

    private fun reload() {
        stage.project = project
        timeline.project = project
        timeMs = timeMs.coerceIn(0, (project.durationMs - 1).coerceAtLeast(0))
        timeline.timeMs = timeMs
        engine.load(project, timeMs)
        aspectBtn.text = project.aspect
        if (selection is TimelineView.Sel.ClipSel && selectedClipIndex() >= project.clips.size) selection = null
        if (selection is TimelineView.Sel.LayerSel && selectedLayer() == null) selection = null
        if (selection is TimelineView.Sel.MusicSel && project.music == null) selection = null
        setSelection(selection)
        updateUndo()
        updatePlayButton()
    }

    private fun commit() {
        history.push(ProjectStore.toJson(project))
        reload()
        scheduleSave()
    }

    private val saveRunnable = Runnable { save() }
    private fun scheduleSave() { main.removeCallbacks(saveRunnable); main.postDelayed(saveRunnable, 1500) }

    private fun save() {
        val json = ProjectStore.toJson(project)
        val copy = ProjectStore.fromJson(json)
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

    override fun onTimelineEditing() {
        stage.refresh()
    }

    override fun onTimelineEdited() { commit() }

    // ------------------------------------------------------------------ clips

    private fun addClips(uris: List<Uri>) {
        Toast.makeText(this, "Waa la soo gelinayaa…", Toast.LENGTH_SHORT).show()
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
                if (clips.isEmpty()) { Toast.makeText(this, "Faylka lama furi karo", Toast.LENGTH_LONG).show(); return@post }
                val firstProject = project.clips.isEmpty()
                val at = if (insertAfter in project.clips.indices) insertAfter + 1 else project.clips.size
                project.clips.addAll(at, clips)
                if (firstProject) {
                    // choose the canvas shape from the first clip
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

    private fun splitClip(i: Int) {
        val c = project.clips[i]
        val start = project.clipStartMs(i)
        val local = timeMs - start
        if (local < 100 || local > c.outDurationMs - 100) { toast("Dhig xariiqda dhexda muuqaalka"); return }
        val srcSplit = c.trimStartMs + if (c.kind == MediaKind.VIDEO) (local * c.speed).toLong() else local
        val b = c.copy()
        if (c.kind == MediaKind.IMAGE) {
            // a still image: first part keeps `local` ms, second part gets the rest
            val total = c.trimmedMs
            c.trimStartMs = 0; c.trimEndMs = local
            b.trimStartMs = 0; b.trimEndMs = total - local
        } else {
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

    private fun showSpeed(c: Clip) {
        val (d, root) = Ui.sheet(this, "Xawaaraha") { commit() }
        val lbl = Ui.label(this, "${TimelineView.trim(c.speed)}x")
        root.addView(lbl)
        root.addView(Ui.slider(this, 0.25f, 4f, c.speed, 0.25f) { v -> c.speed = v; lbl.text = "${TimelineView.trim(v)}x" })
        root.addView(Ui.choiceRow(this, listOf("0.5x", "1x", "1.5x", "2x", "3x"), -1) { k ->
            c.speed = floatArrayOf(0.5f, 1f, 1.5f, 2f, 3f)[k]; d.dismiss()
        })
        d.show()
    }

    private fun showVolume(c: Clip) {
        val (d, root) = Ui.sheet(this, "Codka") { commit() }
        val lbl = Ui.label(this, "${(c.volume * 100).toInt()}%")
        root.addView(lbl)
        root.addView(Ui.slider(this, 0f, 2f, c.volume, 0.05f) { v -> c.volume = v; lbl.text = "${(v * 100).toInt()}%" })
        root.addView(Ui.button(this, if (c.volume == 0f) "🔊 Fur codka" else "🔇 Aamusi", false) {
            c.volume = if (c.volume == 0f) 1f else 0f; d.dismiss()
        })
        root.addView(Ui.button(this, "Ku dabaq dhammaan", false) {
            for (o in project.clips) o.volume = c.volume
            d.dismiss()
        })
        d.show()
    }

    private fun showAdjust(c: Clip) {
        val (d, root) = Ui.sheet(this, "Filter & Hagaajin") { commit() }
        val presets = FilterPreset.entries
        root.addView(Ui.choiceRow(this, presets.map { it.label }, presets.indexOf(c.adjust.preset)) { k ->
            c.adjust.preset = presets[k]; reloadLive()
        })
        root.addView(Ui.label(this, "Iftiin"))
        root.addView(Ui.slider(this, -1f, 1f, c.adjust.brightness) { c.adjust.brightness = it; reloadLive() })
        root.addView(Ui.label(this, "Kala duwanaan (contrast)"))
        root.addView(Ui.slider(this, -1f, 1f, c.adjust.contrast) { c.adjust.contrast = it; reloadLive() })
        root.addView(Ui.label(this, "Midab (saturation)"))
        root.addView(Ui.slider(this, -1f, 1f, c.adjust.saturation) { c.adjust.saturation = it; reloadLive() })
        root.addView(Ui.button(this, "Ku dabaq dhammaan muuqaalada", false) {
            for (o in project.clips) o.adjust = c.adjust.copy()
            d.dismiss()
        })
        d.show()
    }

    /** Re-apply the project to the player without adding an undo step (live previews). */
    private fun reloadLive() {
        engine.load(project, timeMs)
        stage.refresh()
        timeline.invalidate()
    }

    private fun showTrim(c: Clip) {
        val (d, root) = Ui.sheet(this, "Gooy (trim)") { commit() }
        val maxMs = if (c.kind == MediaKind.IMAGE) 30_000f else c.sourceDurationMs.toFloat()
        val lbl = Ui.label(this, "")
        fun upd() { lbl.text = TimelineView.fmt(c.trimStartMs) + " → " + TimelineView.fmt(c.trimEndMs) + "   (" + "%.1f".format(c.trimmedMs / 1000f) + "s)" }
        upd()
        root.addView(lbl)
        if (c.kind == MediaKind.IMAGE) {
            root.addView(Ui.label(this, "Mudada sawirka"))
            root.addView(Ui.slider(this, 0.5f, 30f, c.trimmedMs / 1000f, 0.5f) { v -> c.trimStartMs = 0; c.trimEndMs = (v * 1000).toLong(); c.sourceDurationMs = c.trimEndMs; upd() })
        } else {
            val rs = RangeSlider(this).apply {
                valueFrom = 0f; valueTo = maxMs.coerceAtLeast(200f)
                values = listOf(c.trimStartMs.toFloat().coerceIn(0f, valueTo), c.trimEndMs.toFloat().coerceIn(0f, valueTo))
                minSeparation = 100f
                addOnChangeListener { s, _, fromUser ->
                    if (fromUser) { c.trimStartMs = s.values[0].toLong(); c.trimEndMs = s.values[1].toLong(); upd() }
                }
            }
            root.addView(rs)
        }
        d.show()
    }

    // ------------------------------------------------------------------ layers

    private fun newLayerTimes(l: Layer, length: Long) {
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
        val l = Layer(kind = LayerKind.TEXT, text = "Qoraal")
        newLayerTimes(l, 3000)
        addLayer(l)
        showTextEditor(l)
    }

    private fun showStickers() {
        val emojis = listOf("😀", "😂", "😍", "🥰", "😎", "🤩", "😭", "😡", "👍", "👏", "🙏", "💪", "🔥", "✨", "💯", "❤️",
            "💔", "⭐", "🎉", "🎁", "🎵", "📌", "✅", "❌", "⚡", "🌙", "☀️", "🌸", "🇸🇴", "🕌", "📿", "🤲", "👀", "💥", "🚀", "🏆")
        val (d, root) = Ui.sheet(this, "Sticker-ro")
        val grid = android.widget.GridLayout(this).apply { columnCount = 6 }
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

    private fun addMediaLayer(uri: Uri) {
        keep(uri)
        io.execute {
            val info = MediaUtils.probe(this, uri)
            val name = MediaUtils.displayName(this, uri)
            main.post {
                if (info == null) { toast("Faylka lama furi karo"); return@post }
                val l = Layer(kind = if (info.isVideo) LayerKind.VIDEO else LayerKind.IMAGE, uri = uri.toString(), name = name)
                l.contentAspect = info.height.toFloat() / info.width
                // fit nicely inside the canvas
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

    private fun showTextEditor(l: Layer) {
        val (d, root) = Ui.sheet(this, if (l.kind == LayerKind.STICKER) "Sticker" else "Qoraal") { commit() }
        val edit = EditText(this).apply {
            setText(l.text)
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.TEXT2)
            hint = "Qor halkan…"
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
        root.addView(Ui.label(this, "Cabbirka"))
        root.addView(Ui.slider(this, 0.02f, 0.3f, l.textSizeFrac) { l.textSizeFrac = it; live() })
        if (l.kind == LayerKind.TEXT) {
            root.addView(Ui.label(this, "Midabka qoraalka"))
            root.addView(Ui.colorRow(this, l.textColor, false) { l.textColor = it; live() })
            root.addView(Ui.label(this, "Xariiq (outline)"))
            root.addView(Ui.colorRow(this, l.strokeColor, true) { l.strokeColor = it; live() })
            root.addView(Ui.label(this, "Gadaal (background)"))
            root.addView(Ui.colorRow(this, l.bgColor, true) { l.bgColor = it; live() })
            root.addView(Ui.label(this, "Farta"))
            root.addView(Ui.choiceRow(this, LayerRenderer.FONTS, l.font) { l.font = it; live() })
            root.addView(Ui.label(this, "Qaab"))
            root.addView(Ui.choiceRow(this, listOf("B  Adag", "Caadi"), if (l.bold) 0 else 1) { l.bold = it == 0; live() })
            root.addView(Ui.choiceRow(this, listOf("⟸ Bidix", "≡ Dhexe", "Midig ⟹"), l.align) { l.align = it; live() })
        }
        d.show()
    }

    private fun live() {
        stage.refresh()
        timeline.invalidate()
    }

    private fun showOpacity(l: Layer) {
        val (d, root) = Ui.sheet(this, "Daahsoonaan") { commit() }
        root.addView(Ui.slider(this, 0f, 1f, l.opacity) { l.opacity = it; live() })
        d.show()
    }

    private fun splitLayer(l: Layer) {
        if (timeMs <= l.startMs + 100 || timeMs >= l.endMs - 100) { toast("Dhig xariiqda dhexda layer-ka"); return }
        val b = l.copy()
        b.startMs = timeMs
        if (l.kind == LayerKind.VIDEO) b.trimStartMs = l.trimStartMs + (timeMs - l.startMs)
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
        b.cx = (l.cx + 0.04f); b.cy = (l.cy + 0.04f)
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

    /** A link group with only one layer left is removed. */
    private fun cleanupGroups() {
        val counts = project.layers.mapNotNull { it.linkGroup }.groupingBy { it }.eachCount()
        for (x in project.layers) if (x.linkGroup != null && (counts[x.linkGroup] ?: 0) < 2) x.linkGroup = null
    }

    private fun layerTitle(l: Layer) = timeline.layerLabel(l).take(28)

    /**
     * Link layers so they move, scale, rotate and shift in time together.
     * [base] null = choose any layers.
     */
    private fun showLink(base: Layer?) {
        val layers = project.layers
        if (layers.size < 2) { toast("Ugu yaraan laba layer ku dar (qoraal, sawir, sticker ama muuqaal)"); return }
        val candidates = if (base == null) layers.toList() else layers.filter { it.id != base.id }
        val names = candidates.map { layerTitle(it) }.toTypedArray()
        val checked = BooleanArray(candidates.size) { i -> base != null && base.linkGroup != null && candidates[i].linkGroup == base.linkGroup }
        MaterialAlertDialogBuilder(this)
            .setTitle(if (base == null) "Dooro layer-ada la isku xirayo" else "Ku xir \"${layerTitle(base)}\" kuwan:")
            .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setNegativeButton("Jooji", null)
            .setPositiveButton("Isku xir") { _, _ ->
                val chosen = candidates.filterIndexed { i, _ -> checked[i] }.toMutableList()
                if (base != null) chosen.add(0, base)
                if (chosen.size < 2) {
                    if (base != null && base.linkGroup != null) {
                        // everything unticked -> unlink
                        val g = base.linkGroup
                        for (x in layers) if (x.linkGroup == g) x.linkGroup = null
                        commit()
                    } else toast("Dooro ugu yaraan laba")
                    return@setPositiveButton
                }
                val group = base?.linkGroup ?: chosen.firstNotNullOfOrNull { it.linkGroup } ?: newId()
                // remove members that were unticked
                for (x in layers) if (x.linkGroup == group && x !in chosen) x.linkGroup = null
                for (x in chosen) x.linkGroup = group
                cleanupGroups()
                commit()
                toast("🔗 ${chosen.size} layer waa la isku xiray — hal mar ayay wada dhaqaaqayaan")
            }
            .show()
    }

    private fun unlink(l: Layer) {
        val g = l.linkGroup ?: return
        l.linkGroup = null
        cleanupGroups()
        commit()
        if (project.layers.none { it.linkGroup == g }) toast("Xiriirka waa la furay")
    }

    // ------------------------------------------------------------------ music

    private fun setMusic(uri: Uri) {
        keep(uri)
        io.execute {
            val info = MediaUtils.probe(this, uri)
            val name = MediaUtils.displayName(this, uri)
            main.post {
                project.music = Music(uri = uri.toString(), name = name, sourceDurationMs = info?.durationMs ?: 0)
                setSelection(TimelineView.Sel.MusicSel)
                commit()
            }
        }
    }

    private fun showMusicVolume(m: Music) {
        val (d, root) = Ui.sheet(this, "Codka muusikada") { commit() }
        val lbl = Ui.label(this, "${(m.volume * 100).toInt()}%")
        root.addView(lbl)
        root.addView(Ui.slider(this, 0f, 2f, m.volume, 0.05f) { m.volume = it; lbl.text = "${(it * 100).toInt()}%" })
        d.show()
    }

    private fun showMusicOffset(m: Music) {
        val (d, root) = Ui.sheet(this, "Meesha muusikadu ka bilaabanayso") { commit() }
        val maxS = ((m.sourceDurationMs - 1000) / 1000f).coerceAtLeast(1f)
        val lbl = Ui.label(this, TimelineView.fmt(m.trimStartMs))
        root.addView(lbl)
        root.addView(Ui.slider(this, 0f, maxS, (m.trimStartMs / 1000f).coerceAtMost(maxS)) {
            m.trimStartMs = (it * 1000).toLong(); lbl.text = TimelineView.fmt(m.trimStartMs)
        })
        d.show()
    }

    // ------------------------------------------------------------------ aspect & export

    private fun showAspect() {
        val options = listOf("9:16", "16:9", "1:1", "4:5", "4:3", "3:4")
        val labels = listOf("9:16  TikTok", "16:9  YouTube", "1:1  Square", "4:5  Instagram", "4:3", "3:4")
        val (d, root) = Ui.sheet(this, "Saamiga shaashadda")
        root.addView(Ui.choiceRow(this, labels, options.indexOf(project.aspect)) { k ->
            project.aspect = options[k]
            stage.requestLayout()
            commit()
            d.dismiss()
        })
        d.show()
    }

    private fun showExport() {
        if (project.clips.isEmpty()) { toast("Marka hore muuqaal ku dar"); return }
        engine.pause(); updatePlayButton()
        val (d, root) = Ui.sheet(this, "Dhoofi muuqaalka")
        var res = 1080
        root.addView(Ui.label(this, "Tayada"))
        root.addView(Ui.choiceRow(this, listOf("720p", "1080p"), 1) { k -> res = if (k == 0) 720 else 1080 })
        val info = Ui.label(this, "Mudada: " + TimelineView.fmt(project.durationMs))
        root.addView(info)
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }
        root.addView(bar)
        val status = Ui.label(this, "")
        root.addView(status)
        var exporter: Exporter? = null
        val startBtn = Ui.button(this, "Bilow dhoofinta") {}
        startBtn.setOnClickListener {
            startBtn.isEnabled = false
            bar.visibility = View.VISIBLE
            status.text = "Waa la samaynayaa… fadlan sug"
            save()
            exporter = Exporter(this, ProjectStore.fromJson(ProjectStore.toJson(project)), res, object : Exporter.Callback {
                override fun onProgress(percent: Int) { bar.progress = percent; status.text = "Waa la samaynayaa… $percent%" }
                override fun onDone(uri: Uri?, file: File) {
                    bar.progress = 100
                    status.text = if (uri != null) "✅ Waa la keydiyay: Gallery → Movies/IjarJar" else "✅ Diyaar"
                    startBtn.visibility = View.GONE
                    root.addView(Ui.button(this@EditorActivity, "▶ Fur") { openVideo(uri, file) })
                    root.addView(Ui.button(this@EditorActivity, "📤 Wadaag", false) { shareVideo(uri, file) })
                    exporter = null
                }
                override fun onError(message: String) {
                    status.text = "❌ Khalad: $message"
                    startBtn.isEnabled = true
                    exporter = null
                }
            })
            exporter?.start()
        }
        root.addView(startBtn)
        d.setOnDismissListener { exporter?.cancel() }
        d.setCancelable(true)
        d.show()
    }

    private fun contentUri(uri: Uri?, file: File): Uri =
        uri ?: FileProvider.getUriForFile(this, "$packageName.files", file)

    private fun openVideo(uri: Uri?, file: File) {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(contentUri(uri, file), "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(i) }.onFailure { toast("App muuqaal lagu furo lama helin") }
    }

    private fun shareVideo(uri: Uri?, file: File) {
        val i = Intent(Intent.ACTION_SEND).setType("video/mp4")
            .putExtra(Intent.EXTRA_STREAM, contentUri(uri, file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(i, "Wadaag"))
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}

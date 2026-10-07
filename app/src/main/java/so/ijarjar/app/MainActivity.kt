package so.ijarjar.app

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.util.UnstableApi
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import so.ijarjar.app.data.ProjectStore
import so.ijarjar.app.editor.EditorActivity
import so.ijarjar.app.editor.TimelineView
import so.ijarjar.app.editor.Ui
import so.ijarjar.app.media.MediaUtils
import so.ijarjar.app.model.MediaKind
import so.ijarjar.app.model.Project
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

@UnstableApi
class MainActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private val io = Executors.newSingleThreadExecutor()

    private fun dp(v: Float) = Ui.dp(this, v)
    private fun tr(so: String, en: String) = L.t(so, en)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        L.init(this)
        build()
    }

    private fun build() {
        val scroll = ScrollView(this).apply { setBackgroundColor(Ui.BG) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18f), dp(22f), dp(18f), dp(22f))
        }
        scroll.addView(root)

        val title = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        title.addView(ImageView(this).apply { setImageResource(R.mipmap.ic_launcher) }, LinearLayout.LayoutParams(dp(48f), dp(48f)))
        title.addView(Ui.text(this, "Ijar Jar", 28f, Ui.TEXT, true).apply { setPadding(dp(10f), 0, 0, 0) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // language switch
        val lang = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.roundBg(Ui.SURFACE2, dp(16f).toFloat())
            setPadding(dp(10f), dp(6f), dp(12f), dp(6f))
            setOnClickListener {
                L.setEnglish(this@MainActivity, !L.english)
                build(); refresh()
            }
        }
        lang.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_language); imageTintList = ColorStateList.valueOf(Ui.TEXT)
        }, LinearLayout.LayoutParams(dp(18f), dp(18f)))
        lang.addView(Ui.text(this, if (L.english) "English" else "Soomaali", 13f).apply { setPadding(dp(6f), 0, 0, 0) })
        title.addView(lang)
        root.addView(title)
        root.addView(Ui.text(this, tr("Muuqaal iyo sawir — layer-ada isku xir oo hal mar wada dhaqaaji",
            "Video & photo editor — link layers and move them together"), 13f, Ui.TEXT2).apply { setPadding(0, dp(4f), 0, dp(18f)) })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(bigButton(R.drawable.ic_video, tr("Muuqaal cusub", "New video"), tr("Sida CapCut", "Like CapCut"), Ui.ACCENT) { newProject(false) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6f) })
        row.addView(bigButton(R.drawable.ic_photo, tr("Sawir cusub", "New photo"), tr("Sida PixelLab", "Like PixelLab"), Ui.ACCENT2) { newProject(true) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6f) })
        root.addView(row)

        root.addView(Ui.text(this, tr("Mashaariicdaada", "Your projects"), 16f, Ui.TEXT, true).apply { setPadding(0, dp(24f), 0, dp(10f)) })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        setContentView(scroll)
    }

    private fun bigButton(icon: Int, title: String, sub: String, color: Int, onClick: () -> Unit): View {
        val b = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = Ui.roundBg(color, dp(20f).toFloat())
            setPadding(dp(12f), dp(22f), dp(12f), dp(18f))
            setOnClickListener { onClick() }
        }
        b.addView(ImageView(this).apply { setImageResource(icon); imageTintList = ColorStateList.valueOf(0xFF101014.toInt()) },
            LinearLayout.LayoutParams(dp(34f), dp(34f)))
        b.addView(Ui.text(this, title, 16f, 0xFF101014.toInt(), true).apply { gravity = Gravity.CENTER; setPadding(0, dp(8f), 0, 0) })
        b.addView(Ui.text(this, sub, 12f, 0xAA101014.toInt()).apply { gravity = Gravity.CENTER })
        return b
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun newProject(photo: Boolean) {
        val stamp = SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date())
        val p = Project(name = (if (photo) tr("Sawir ", "Photo ") else tr("Muuqaal ", "Video ")) + stamp, isPhoto = photo)
        if (photo) { p.aspect = "1:1"; p.bgColor = 0xFFFFFFFF.toInt() }
        ProjectStore.save(this, p)
        open(p.id)
    }

    private fun open(id: String) {
        startActivity(Intent(this, EditorActivity::class.java).putExtra("id", id))
    }

    private fun refresh() {
        if (!::list.isInitialized) return
        list.removeAllViews()
        val projects = ProjectStore.list(this)
        if (projects.isEmpty()) {
            list.addView(Ui.text(this, tr("Weli mashruuc ma jiro.", "No projects yet."), 14f, Ui.TEXT2))
            return
        }
        for (p in projects) list.addView(row(p))
    }

    private fun row(p: Project): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.roundBg(Ui.SURFACE, dp(14f).toFloat())
            setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
            setOnClickListener { open(p.id) }
            setOnLongClickListener { menu(p); true }
        }
        val thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = Ui.roundBg(Ui.SURFACE2, dp(10f).toFloat())
            clipToOutline = true
        }
        r.addView(thumb, LinearLayout.LayoutParams(dp(64f), dp(64f)))
        val firstUri = p.clips.firstOrNull()?.uri ?: p.bgImageUri
        if (firstUri != null) {
            val isVideo = p.clips.firstOrNull()?.kind == MediaKind.VIDEO
            val at = p.clips.firstOrNull()?.trimStartMs ?: 0L
            io.execute {
                val b = MediaUtils.thumbnail(this, Uri.parse(firstUri), isVideo, at, 160)
                runOnUiThread { if (b != null) thumb.setImageBitmap(b) }
            }
        } else {
            thumb.setImageResource(if (p.isPhoto) R.drawable.ic_photo else R.drawable.ic_video)
            thumb.scaleType = ImageView.ScaleType.CENTER_INSIDE
            thumb.imageTintList = ColorStateList.valueOf(Ui.TEXT2)
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12f), 0, 0, 0) }
        texts.addView(Ui.text(this, p.name, 15f, Ui.TEXT, true))
        val info = if (p.isPhoto) tr("Sawir", "Photo") + "  •  " + p.aspect + "  •  " + p.layers.size + " layer"
        else TimelineView.fmt(p.durationMs) + "  •  " + p.clips.size + tr(" muuqaal", " clips") + "  •  " + p.layers.size + " layer"
        texts.addView(Ui.text(this, info, 12f, Ui.TEXT2))
        r.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(Ui.iconButton(this, R.drawable.ic_more, 22f, Ui.TEXT2) { menu(p) })
        val wrap = LinearLayout(this).apply { setPadding(0, 0, 0, dp(10f)) }
        wrap.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return wrap
    }

    private fun menu(p: Project) {
        MaterialAlertDialogBuilder(this)
            .setTitle(p.name)
            .setItems(arrayOf(tr("Fur", "Open"), tr("Magac beddel", "Rename"), tr("Nuqul samee", "Duplicate"), tr("Tirtir", "Delete"))) { _, which ->
                when (which) {
                    0 -> open(p.id)
                    1 -> rename(p)
                    2 -> {
                        val c = ProjectStore.fromJson(ProjectStore.toJson(p))
                        c.id = so.ijarjar.app.model.newId(); c.name = p.name + " (2)"
                        ProjectStore.save(this, c); refresh()
                    }
                    3 -> MaterialAlertDialogBuilder(this)
                        .setMessage(tr("Ma hubtaa inaad tirtirto \"${p.name}\"?", "Delete \"${p.name}\"?"))
                        .setNegativeButton(tr("Maya", "No"), null)
                        .setPositiveButton(tr("Haa, tirtir", "Yes, delete")) { _, _ -> ProjectStore.delete(this, p.id); refresh() }
                        .show()
                }
            }
            .show()
    }

    private fun rename(p: Project) {
        val e = EditText(this).apply { setText(p.name); setSelection(text.length) }
        val box = LinearLayout(this).apply { setPadding(dp(20f), dp(8f), dp(20f), 0); addView(e, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        MaterialAlertDialogBuilder(this)
            .setTitle(tr("Magaca mashruuca", "Project name"))
            .setView(box)
            .setNegativeButton(tr("Jooji", "Cancel"), null)
            .setPositiveButton(tr("Keydi", "Save")) { _, _ -> p.name = e.text.toString().ifBlank { p.name }; ProjectStore.save(this, p); refresh() }
            .show()
    }
}

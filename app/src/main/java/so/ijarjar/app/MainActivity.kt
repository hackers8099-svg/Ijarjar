package so.ijarjar.app

import android.content.Intent
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply { setBackgroundColor(Ui.BG) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18f), dp(22f), dp(18f), dp(22f))
        }
        scroll.addView(root)

        val title = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        title.addView(ImageView(this).apply { setImageResource(R.mipmap.ic_launcher) }, LinearLayout.LayoutParams(dp(48f), dp(48f)))
        title.addView(Ui.text(this, "Ijar Jar", 28f, Ui.TEXT, true).apply { setPadding(dp(10f), 0, 0, 0) })
        root.addView(title)
        root.addView(Ui.text(this, "Video editor — layer-ada isku xir oo hal mar wada dhaqaaji", 13f, Ui.TEXT2).apply { setPadding(0, dp(4f), 0, dp(18f)) })

        val newBtn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = Ui.roundBg(Ui.ACCENT, dp(20f).toFloat())
            setPadding(dp(16f), dp(26f), dp(16f), dp(26f))
            setOnClickListener { newProject() }
        }
        newBtn.addView(Ui.text(this, "＋", 34f, 0xFF00201E.toInt(), true).apply { gravity = Gravity.CENTER })
        newBtn.addView(Ui.text(this, "Mashruuc cusub", 17f, 0xFF00201E.toInt(), true).apply { gravity = Gravity.CENTER })
        root.addView(newBtn)

        root.addView(Ui.text(this, "Mashaariicdaada", 16f, Ui.TEXT, true).apply { setPadding(0, dp(24f), 0, dp(10f)) })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun newProject() {
        val p = Project(name = "Mashruuc " + SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date()))
        ProjectStore.save(this, p)
        open(p.id)
    }

    private fun open(id: String) {
        startActivity(Intent(this, EditorActivity::class.java).putExtra("id", id))
    }

    private fun refresh() {
        list.removeAllViews()
        val projects = ProjectStore.list(this)
        if (projects.isEmpty()) {
            list.addView(Ui.text(this, "Weli mashruuc ma jiro. Riix \"Mashruuc cusub\".", 14f, Ui.TEXT2))
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
        p.clips.firstOrNull()?.let { c ->
            io.execute {
                val b = MediaUtils.thumbnail(this, Uri.parse(c.uri), c.kind == MediaKind.VIDEO, c.trimStartMs, 160)
                runOnUiThread { if (b != null) thumb.setImageBitmap(b) }
            }
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12f), 0, 0, 0) }
        texts.addView(Ui.text(this, p.name, 15f, Ui.TEXT, true))
        texts.addView(Ui.text(this, TimelineView.fmt(p.durationMs) + "  •  " + p.clips.size + " muuqaal  •  " + p.layers.size + " layer", 12f, Ui.TEXT2))
        r.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(Ui.text(this, "⋮", 22f, Ui.TEXT2).apply {
            setPadding(dp(10f), dp(4f), dp(4f), dp(4f))
            setOnClickListener { menu(p) }
        })
        val wrap = LinearLayout(this).apply { setPadding(0, 0, 0, dp(10f)) }
        wrap.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return wrap
    }

    private fun menu(p: Project) {
        MaterialAlertDialogBuilder(this)
            .setTitle(p.name)
            .setItems(arrayOf("Fur", "Magac beddel", "Nuqul samee", "Tirtir")) { _, which ->
                when (which) {
                    0 -> open(p.id)
                    1 -> rename(p)
                    2 -> {
                        val c = ProjectStore.fromJson(ProjectStore.toJson(p))
                        c.id = so.ijarjar.app.model.newId(); c.name = p.name + " (2)"
                        ProjectStore.save(this, c); refresh()
                    }
                    3 -> MaterialAlertDialogBuilder(this)
                        .setMessage("Ma hubtaa inaad tirtirto \"${p.name}\"?")
                        .setNegativeButton("Maya", null)
                        .setPositiveButton("Haa, tirtir") { _, _ -> ProjectStore.delete(this, p.id); refresh() }
                        .show()
                }
            }
            .show()
    }

    private fun rename(p: Project) {
        val e = EditText(this).apply { setText(p.name); setSelection(text.length) }
        val box = LinearLayout(this).apply { setPadding(dp(20f), dp(8f), dp(20f), 0); addView(e, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        MaterialAlertDialogBuilder(this)
            .setTitle("Magaca mashruuca")
            .setView(box)
            .setNegativeButton("Jooji", null)
            .setPositiveButton("Keydi") { _, _ -> p.name = e.text.toString().ifBlank { p.name }; ProjectStore.save(this, p); refresh() }
            .show()
    }
}

package so.ijarjar.app.editor

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider

/** Small helpers to build the UI in code. */
object Ui {
    const val BG = 0xFF0E0E10.toInt()
    const val SURFACE = 0xFF1A1A1F.toInt()
    const val SURFACE2 = 0xFF26262D.toInt()
    const val ACCENT = 0xFF19D3C5.toInt()
    const val ACCENT2 = 0xFFFF3D7F.toInt()
    const val TEXT = 0xFFF2F2F5.toInt()
    const val TEXT2 = 0xFF9A9AA5.toInt()

    val PALETTE = intArrayOf(
        0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFF3B30.toInt(), 0xFFFF9500.toInt(), 0xFFFFCC00.toInt(),
        0xFF34C759.toInt(), 0xFF19D3C5.toInt(), 0xFF007AFF.toInt(), 0xFF5856D6.toInt(), 0xFFFF2D55.toInt(),
        0xFFA2845E.toInt(), 0xFF8E8E93.toInt()
    )

    fun dp(c: Context, v: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.resources.displayMetrics).toInt()

    fun text(c: Context, s: String, size: Float = 14f, color: Int = TEXT, bold: Boolean = false): TextView =
        TextView(c).apply {
            text = s; textSize = size; setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    fun roundBg(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius
        if (stroke > 0) setStroke(stroke, strokeColor)
    }

    /** CapCut style bottom tool: flat icon above a label. */
    fun tool(c: Context, icon: Int, label: String, active: Boolean = false, onClick: () -> Unit): View {
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(c, 6f), dp(c, 8f), dp(c, 6f), dp(c, 6f))
            minimumWidth = dp(c, 66f)
            isClickable = true
            val tv = TypedValue()
            c.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
            setBackgroundResource(tv.resourceId)
            setOnClickListener { onClick() }
        }
        box.addView(ImageView(c).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(if (active) ACCENT else TEXT)
        }, LinearLayout.LayoutParams(dp(c, 24f), dp(c, 24f)))
        box.addView(text(c, label, 11f, if (active) ACCENT else TEXT2).apply {
            gravity = Gravity.CENTER; maxLines = 1; setPadding(0, dp(c, 4f), 0, 0)
        })
        return box
    }

    /** A plain flat icon button. */
    fun iconButton(c: Context, icon: Int, sizeDp: Float = 24f, tint: Int = TEXT, onClick: () -> Unit): ImageView =
        ImageView(c).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(tint)
            val pad = dp(c, 8f)
            setPadding(pad, pad, pad, pad)
            val tv = TypedValue()
            c.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
            setBackgroundResource(tv.resourceId)
            layoutParams = LinearLayout.LayoutParams(dp(c, sizeDp + 16f), dp(c, sizeDp + 16f))
            setOnClickListener { onClick() }
        }

    /** A row with an icon and a label, used in pickers. */
    fun iconChip(c: Context, icon: Int, label: String, onClick: () -> Unit): View {
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = roundBg(SURFACE2, dp(c, 14f).toFloat())
            setPadding(dp(c, 8f), dp(c, 12f), dp(c, 8f), dp(c, 10f))
            setOnClickListener { onClick() }
        }
        box.addView(ImageView(c).apply { setImageResource(icon); imageTintList = ColorStateList.valueOf(ACCENT) },
            LinearLayout.LayoutParams(dp(c, 26f), dp(c, 26f)))
        box.addView(text(c, label, 11f, TEXT).apply { gravity = Gravity.CENTER; maxLines = 1; setPadding(0, dp(c, 4f), 0, 0) })
        return box
    }

    /**
     * Opens an editing panel at the bottom of the editor (like CapCut): the preview stays visible
     * above it and the panel replaces the timeline while it is open.
     */
    fun sheet(c: Context, title: String, onDismiss: (() -> Unit)? = null): Pair<Panel, LinearLayout> {
        val p = Panel(c, title)
        if (onDismiss != null) p.setOnDismissListener(onDismiss)
        return Pair(p, p.root)
    }

    /** Label + slider on one line (keeps panels short). */
    fun sliderRow(c: Context, label: String, from: Float, to: Float, value: Float, step: Float = 0f, onChange: (Float) -> Unit): View {
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(text(c, label, 12f, TEXT2).apply { maxLines = 1 }, LinearLayout.LayoutParams(dp(c, 92f), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(slider(c, from, to, value, step, onChange), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    /** A horizontally scrolling row to put tiles in (left to right, like CapCut). */
    fun hrow(c: Context): Pair<HorizontalScrollView, LinearLayout> {
        val sv = HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(c, 4f), 0, dp(c, 4f)) }
        sv.addView(row)
        return Pair(sv, row)
    }

    /** Tabs on top of a panel (underlined text, like CapCut); each tab fills the body when chosen. */
    fun tabs(c: Context, root: LinearLayout, tabs: List<Pair<String, (LinearLayout) -> Unit>>, selected: Int = 0) {
        val body = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(c, 6f), 0, 0) }
        fun show(i: Int) { body.removeAllViews(); tabs[i].second(body) }
        root.addView(tabBar(c, tabs.map { it.first }, selected) { show(it) })
        root.addView(body)
        show(selected)
    }

    /** Text tabs with an accent underline under the chosen one. */
    fun tabBar(c: Context, names: List<String>, selected: Int, onPick: (Int) -> Unit): HorizontalScrollView {
        val sv = HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        sv.addView(row)
        val views = ArrayList<TextView>()
        fun paint(sel: Int) {
            views.forEachIndexed { i, v ->
                v.setTextColor(if (i == sel) TEXT else TEXT2)
                v.setTypeface(null, if (i == sel) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                v.background = if (i == sel) underline(c) else null
            }
        }
        names.forEachIndexed { i, n ->
            val v = text(c, n, 14f).apply {
                setPadding(dp(c, 12f), dp(c, 8f), dp(c, 12f), dp(c, 10f))
                setOnClickListener { paint(i); onPick(i) }
            }
            row.addView(v)
            views.add(v)
        }
        paint(selected)
        return sv
    }

    private fun underline(c: Context): android.graphics.drawable.Drawable {
        val line = GradientDrawable().apply { setColor(ACCENT); cornerRadius = dp(c, 2f).toFloat() }
        return android.graphics.drawable.LayerDrawable(arrayOf(line)).apply {
            setLayerGravity(0, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            setLayerSize(0, dp(c, 18f), dp(c, 3f))
        }
    }

    fun label(c: Context, s: String) = text(c, s, 12f, TEXT2).apply { setPadding(dp(c, 2f), dp(c, 10f), 0, dp(c, 4f)) }

    fun slider(c: Context, from: Float, to: Float, value: Float, step: Float = 0f, onChange: (Float) -> Unit): Slider =
        Slider(c).apply {
            valueFrom = from; valueTo = to
            if (step > 0f) stepSize = step
            this.value = if (step > 0f) (Math.round((value.coerceIn(from, to) - from) / step) * step + from).coerceIn(from, to) else value.coerceIn(from, to)
            addOnChangeListener { _, v, fromUser -> if (fromUser) onChange(v) }
        }

    fun button(c: Context, s: String, filled: Boolean = true, onClick: () -> Unit): MaterialButton =
        MaterialButton(c).apply {
            text = s
            isAllCaps = false
            if (filled) {
                backgroundTintList = ColorStateList.valueOf(ACCENT)
                setTextColor(0xFF00201E.toInt())
            } else {
                backgroundTintList = ColorStateList.valueOf(SURFACE2)
                setTextColor(TEXT)
            }
            setOnClickListener { onClick() }
        }

    /** A horizontal row of selectable chips. */
    fun choiceRow(c: Context, options: List<String>, selected: Int, onPick: (Int) -> Unit): HorizontalScrollView {
        val sv = HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        sv.addView(row)
        val views = ArrayList<TextView>()
        fun paint(sel: Int) {
            views.forEachIndexed { i, v ->
                v.background = roundBg(if (i == sel) 0x2219D3C5 else SURFACE2, dp(c, 10f).toFloat(),
                    dp(c, 1.5f), if (i == sel) ACCENT else SURFACE2)
                v.setTextColor(if (i == sel) ACCENT else TEXT)
            }
        }
        options.forEachIndexed { i, o ->
            val v = text(c, o, 13f).apply {
                setPadding(dp(c, 14f), dp(c, 7f), dp(c, 14f), dp(c, 7f))
                setOnClickListener { paint(i); onPick(i) }
            }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = dp(c, 6f); lp.topMargin = dp(c, 4f); lp.bottomMargin = dp(c, 4f)
            row.addView(v, lp)
            views.add(v)
        }
        paint(selected)
        return sv
    }

    /** Colour swatches. [allowNone] adds a "none" swatch that returns 0. */
    fun colorRow(c: Context, selected: Int, allowNone: Boolean, onPick: (Int) -> Unit): HorizontalScrollView {
        val sv = HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(c, 4f), 0, dp(c, 4f)) }
        sv.addView(row)
        val colors = (if (allowNone) intArrayOf(0) else intArrayOf()) + PALETTE
        val views = ArrayList<View>()
        fun paint(sel: Int) {
            views.forEachIndexed { i, v ->
                val col = colors[i]
                v.background = roundBg(if (col == 0) SURFACE2 else col, dp(c, 16f).toFloat(),
                    dp(c, if (col == sel) 3f else 1f), if (col == sel) ACCENT else 0x44FFFFFF)
            }
        }
        for (col in colors) {
            val v = TextView(c).apply {
                gravity = Gravity.CENTER
                if (col == 0) { text = "⦸"; setTextColor(Color.WHITE) }
                setOnClickListener { paint(col); onPick(col) }
            }
            val s = dp(c, 32f)
            val lp = LinearLayout.LayoutParams(s, s)
            lp.marginEnd = dp(c, 8f)
            row.addView(v, lp)
            views.add(v)
        }
        paint(selected)
        return sv
    }
}

/** Implemented by the screen that shows panels. */
interface PanelHost {
    fun attachPanel(panel: Panel)
    fun detachPanel(panel: Panel)
    /** Puts the project back the way it was when the panel opened. */
    fun cancelPanel(panel: Panel)
}

/**
 * An inline bottom panel: [X] cancels (undoes everything changed in it), the title, [✓] keeps the changes.
 * Views added to [top] stay visible while the keyboard is open (the rest hides, so the preview stays big).
 */
class Panel(val context: Context, title: String) {
    val view: LinearLayout
    val top: LinearLayout
    val root: LinearLayout
    private val scroll: NestedScrollView
    private var onDismiss: (() -> Unit)? = null
    private var dismissed = false
    /** Project as JSON when the panel opened (set by the host). */
    var snapshot: String? = null

    init {
        view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Ui.SURFACE)
                val r = Ui.dp(context, 18f).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
        }
        // grab bar
        view.addView(View(context).apply { background = Ui.roundBg(0x55FFFFFF, Ui.dp(context, 2f).toFloat()) },
            LinearLayout.LayoutParams(Ui.dp(context, 36f), Ui.dp(context, 4f)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = Ui.dp(context, 6f) })
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(context, 4f), 0, Ui.dp(context, 4f), 0)
        }
        head.addView(Ui.iconButton(context, so.ijarjar.app.R.drawable.ic_close, 22f, Ui.TEXT2) { cancel() })
        head.addView(Ui.text(context, title, 15f, Ui.TEXT, true).apply { gravity = Gravity.CENTER; maxLines = 1 },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(Ui.iconButton(context, so.ijarjar.app.R.drawable.ic_check, 24f, Ui.ACCENT) { dismiss() })
        view.addView(head)
        top = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 14f), 0, Ui.dp(context, 14f), 0)
        }
        view.addView(top)
        scroll = NestedScrollView(context)
        root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 14f), 0, Ui.dp(context, 14f), Ui.dp(context, 12f))
        }
        scroll.addView(root)
        view.addView(scroll)
    }

    fun setOnDismissListener(f: () -> Unit) { onDismiss = f }

    fun show() { (context as? PanelHost)?.attachPanel(this) }

    /** Keyboard open: only the header and [top] stay, so the preview isn't squeezed. */
    fun onKeyboard(open: Boolean) {
        if (top.childCount > 0) scroll.visibility = if (open) View.GONE else View.VISIBLE
    }

    /** Closes and keeps the changes (runs the dismiss action once). */
    fun dismiss() {
        if (dismissed) return
        dismissed = true
        (context as? PanelHost)?.detachPanel(this)
        onDismiss?.invoke()
    }

    /** Closes and throws away what was changed while the panel was open. */
    fun cancel() {
        if (dismissed) return
        dismiss()
        (context as? PanelHost)?.cancelPanel(this)
    }
}

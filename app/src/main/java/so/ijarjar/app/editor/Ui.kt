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
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
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
            background = roundBg(SURFACE2, dp(c, 12f).toFloat())
            setPadding(dp(c, 8f), dp(c, 10f), dp(c, 8f), dp(c, 8f))
            setOnClickListener { onClick() }
        }
        box.addView(ImageView(c).apply { setImageResource(icon); imageTintList = ColorStateList.valueOf(TEXT) },
            LinearLayout.LayoutParams(dp(c, 26f), dp(c, 26f)))
        box.addView(text(c, label, 11f, TEXT).apply { gravity = Gravity.CENTER; maxLines = 1; setPadding(0, dp(c, 4f), 0, 0) })
        return box
    }

    fun sheet(c: Context, title: String, onDismiss: (() -> Unit)? = null): Pair<BottomSheetDialog, LinearLayout> {
        val d = BottomSheetDialog(c)
        val scroll = NestedScrollView(c).apply {
            background = roundBg(SURFACE, dp(c, 18f).toFloat())
        }
        val root = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(c, 18f), dp(c, 14f), dp(c, 18f), dp(c, 22f))
        }
        scroll.addView(root)
        root.addView(text(c, title, 17f, TEXT, true).apply { setPadding(0, 0, 0, dp(c, 10f)) })
        d.setContentView(scroll)
        d.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        d.behavior.skipCollapsed = true
        if (onDismiss != null) d.setOnDismissListener { onDismiss() }
        return Pair(d, root)
    }

    fun label(c: Context, s: String) = text(c, s, 13f, TEXT2).apply { setPadding(0, dp(c, 10f), 0, dp(c, 2f)) }

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
                v.background = roundBg(if (i == sel) ACCENT else SURFACE2, dp(c, 16f).toFloat())
                v.setTextColor(if (i == sel) 0xFF00201E.toInt() else TEXT)
            }
        }
        options.forEachIndexed { i, o ->
            val v = text(c, o, 13f).apply {
                setPadding(dp(c, 14f), dp(c, 8f), dp(c, 14f), dp(c, 8f))
                setOnClickListener { paint(i); onPick(i) }
            }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = dp(c, 8f)
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

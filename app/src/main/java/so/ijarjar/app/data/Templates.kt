package so.ijarjar.app.data

import so.ijarjar.app.L
import so.ijarjar.app.model.EffectKind
import so.ijarjar.app.model.FilterPreset
import so.ijarjar.app.model.Layer
import so.ijarjar.app.model.LayerAnim
import so.ijarjar.app.model.LayerKind
import so.ijarjar.app.model.LoopAnim
import so.ijarjar.app.model.MediaKind
import so.ijarjar.app.model.Project
import so.ijarjar.app.model.ShapeKind
import so.ijarjar.app.model.TextAnim
import so.ijarjar.app.model.TextLoop
import so.ijarjar.app.model.TransitionKind

/**
 * Ready-made templates (CapCut / PixelLab style). Video templates take the user's clips and add
 * timing, transitions, effects, filters and animated titles. Photo templates build a poster.
 */
class Template(
    val id: String,
    val so: String,
    val en: String,
    val photo: Boolean,
    val aspect: String,
    /** Pictures/videos the user should pick (0 = none needed). */
    val media: Int,
    val color1: Int,
    val color2: Int,
    val build: (Project) -> Unit
) {
    val label: String get() = L.t(so, en)
}

object Templates {

    private const val END = 3_600_000L

    private fun title(text: String, start: Long, end: Long, cy: Float = 0.5f, size: Float = 0.1f, color: Int = 0xFFFFFFFF.toInt(),
                      textIn: TextAnim = TextAnim.APPLE, textOut: TextAnim = TextAnim.LETTER_FADE, font: Int = 6): Layer =
        Layer(kind = LayerKind.TEXT, text = text, startMs = start, endMs = end, cy = cy, textSizeFrac = size, textColor = color,
            textIn = textIn, textOut = textOut, font = font, animInMs = 900, animOutMs = 600, shadow = true)

    private fun timing(p: Project, slotMs: Long, transition: TransitionKind, filter: FilterPreset = FilterPreset.NONE) {
        for ((i, c) in p.clips.withIndex()) {
            if (c.kind == MediaKind.IMAGE) { c.trimStartMs = 0; c.trimEndMs = slotMs; c.sourceDurationMs = slotMs }
            else c.trimEndMs = minOf(c.sourceDurationMs, c.trimStartMs + slotMs)
            if (i > 0) { c.transition = transition; c.transitionMs = minOf(700L, slotMs / 2) }
            c.adjust.preset = filter
            // fill the frame like most templates do
            val ca = c.width.toFloat().coerceAtLeast(1f) / c.height.coerceAtLeast(1)
            val r = p.aspectRatio()
            c.tScale = maxOf(ca / r, r / ca)
        }
    }

    private fun effect(p: Project, kind: EffectKind, start: Long = 0, end: Long = p.durationMs, strength: Float = 1f) {
        p.layers.add(Layer(kind = LayerKind.EFFECT, effect = kind, name = kind.en, startMs = start, endMs = end, opacity = strength))
    }

    val all: List<Template> = listOf(
        // ---------------- video ----------------
        Template("slideshow", "Bandhig sawiro", "Slideshow", false, "9:16", 5, 0xFF2B2D42.toInt(), 0xFFEF8354.toInt()) { p ->
            timing(p, 2500, TransitionKind.FADE_BLACK, FilterPreset.WARM)
            p.layers.add(title(L.t("Xusuusteyda", "Memories"), 0, minOf(3000, p.durationMs), 0.45f))
            effect(p, EffectKind.SLOW_ZOOM, 0, p.durationMs, 0.6f)
        },
        Template("beat", "Garaac", "Beat sync", false, "9:16", 6, 0xFF111111.toInt(), 0xFF19D3C5.toInt()) { p ->
            timing(p, 800, TransitionKind.FLASH)
            effect(p, EffectKind.ZOOM_PULSE)
            p.layers.add(title("VIBES", 0, p.durationMs, 0.5f, 0.16f, textIn = TextAnim.LETTER_POP).also { it.textLoop = TextLoop.BOUNCE })
        },
        Template("travel", "Safar", "Travel", false, "9:16", 5, 0xFF0B3954.toInt(), 0xFF087E8B.toInt()) { p ->
            timing(p, 2000, TransitionKind.WHIP, FilterPreset.TEAL_ORANGE)
            effect(p, EffectKind.LETTERBOX)
            p.layers.add(title(L.t("SAFAR", "TRAVEL"), 0, minOf(3000, p.durationMs), 0.5f, 0.14f, textIn = TextAnim.TRACKING))
        },
        Template("birthday", "Dhalasho", "Birthday", false, "9:16", 4, 0xFFFF6F91.toInt(), 0xFFFFC75F.toInt()) { p ->
            timing(p, 2000, TransitionKind.ZOOM_IN)
            effect(p, EffectKind.CONFETTI)
            p.layers.add(title(L.t("Dhalasho\nwanaagsan!", "Happy\nBirthday!"), 0, p.durationMs, 0.3f, 0.11f, 0xFFFFE27A.toInt(), TextAnim.BOUNCE_IN).also {
                it.textColor2 = 0xFFE09B12.toInt(); it.depth = 0.5f; it.depthColor = 0xFF6B4300.toInt(); it.textLoop = TextLoop.WAVE
            })
        },
        Template("vlog", "Maalintayda", "Daily vlog", false, "9:16", 5, 0xFFF4F1DE.toInt(), 0xFFE07A5F.toInt()) { p ->
            timing(p, 3000, TransitionKind.SLIDE_LEFT)
            effect(p, EffectKind.REC)
            p.layers.add(title(L.t("Maalintayda", "My day"), 0, minOf(3500, p.durationMs), 0.8f, 0.07f, 0xFF111111.toInt(), TextAnim.WORD_FADE_UP, font = 7).also {
                it.bgColor = 0xEEFFFFFF.toInt(); it.shadow = false
            })
        },
        Template("retro", "Qadiim", "Retro VHS", false, "9:16", 5, 0xFF3D348B.toInt(), 0xFFF35B04.toInt()) { p ->
            timing(p, 2000, TransitionKind.GLITCH, FilterPreset.VINTAGE)
            effect(p, EffectKind.VHS)
            effect(p, EffectKind.GRAIN, 0, p.durationMs, 0.7f)
            p.layers.add(title("RETRO", 0, minOf(3000, p.durationMs), 0.5f, 0.15f, 0xFFFF6EC7.toInt(), TextAnim.GLITCH).also {
                it.depth = 0.5f; it.depthColor = 0xFF5B2A86.toInt()
            })
        },
        Template("love", "Jacayl", "Love", false, "9:16", 4, 0xFFFFAFCC.toInt(), 0xFFCDB4DB.toInt()) { p ->
            timing(p, 2500, TransitionKind.FADE_BLACK, FilterPreset.PINK)
            effect(p, EffectKind.HEARTS)
            p.layers.add(title(L.t("Jacayl", "Love"), 0, p.durationMs, 0.25f, 0.14f, 0xFFFFFFFF.toInt(), TextAnim.APPLE, font = 3).also {
                it.animLoop = LoopAnim.HEARTBEAT; it.bold = false
            })
        },
        Template("promo", "Iibin", "Promo", false, "9:16", 3, 0xFFD00000.toInt(), 0xFFFFBA08.toInt()) { p ->
            timing(p, 1500, TransitionKind.SPIN)
            p.layers.add(Layer(kind = LayerKind.SHAPE, shape = ShapeKind.ROUND_RECT, textColor = 0xFFD00000.toInt(), baseW = 0.8f,
                contentAspect = 0.3f, cy = 0.78f, startMs = 0, endMs = p.durationMs, animIn = LayerAnim.ZOOM))
            p.layers.add(title(L.t("DHIMIS 50%", "SALE 50%"), 0, p.durationMs, 0.78f, 0.09f, 0xFFFFBA08.toInt(), TextAnim.LETTER_ZOOM).also {
                it.textLoop = TextLoop.SHIMMER; it.shadow = false
            })
        },
        Template("quran", "Aayad", "Verse", false, "9:16", 2, 0xFF0B3D2E.toInt(), 0xFFC9A227.toInt()) { p ->
            timing(p, 4000, TransitionKind.FADE_BLACK, FilterPreset.MOODY)
            effect(p, EffectKind.VIGNETTE)
            p.layers.add(title(L.t("Qor aayadda halkan", "Write the verse here"), 0, p.durationMs, 0.5f, 0.06f, 0xFFFFFFFF.toInt(), TextAnim.APPLE, font = 1).also {
                it.bold = false; it.textLoop = TextLoop.KARAOKE; it.highlightColor = 0xFFE6C15A.toInt()
            })
        },

        // ---------------- photo (PixelLab) ----------------
        Template("quote", "Odhaah", "Quote", true, "1:1", 0, 0xFF7F00FF.toInt(), 0xFFE100FF.toInt()) { p ->
            p.bgColor = 0xFF7F00FF.toInt(); p.bgColor2 = 0xFFE100FF.toInt()
            p.layers.add(title(L.t("\"Ku qor odhaahda\nhalkan\"", "\"Write your\nquote here\""), 0, END, 0.45f, 0.08f, font = 1, textIn = TextAnim.NONE, textOut = TextAnim.NONE))
            p.layers.add(title("— " + L.t("Magaca", "Name"), 0, END, 0.68f, 0.045f, 0xCCFFFFFF.toInt(), TextAnim.NONE, TextAnim.NONE, 0).also { it.bold = false; it.shadow = false })
        },
        Template("jumca", "Jimco", "Jumu'ah", true, "4:5", 0, 0xFF0B3D2E.toInt(), 0xFF1B5E20.toInt()) { p ->
            p.bgColor = 0xFF06281D.toInt(); p.bgColor2 = 0xFF1B5E20.toInt()
            p.layers.add(title(L.t("Jimco\nMubaarak", "Jumu'ah\nMubarak"), 0, END, 0.45f, 0.12f, 0xFFFFE27A.toInt(), TextAnim.NONE, TextAnim.NONE, 1).also {
                it.textColor2 = 0xFFC9A227.toInt(); it.depth = 0.4f; it.depthColor = 0xFF3E2C00.toInt()
            })
            for ((x, y) in listOf(0.15f to 0.12f, 0.85f to 0.15f, 0.2f to 0.85f, 0.82f to 0.82f))
                p.layers.add(Layer(kind = LayerKind.SHAPE, shape = ShapeKind.STAR, textColor = 0xFFE6C15A.toInt(), baseW = 0.08f, contentAspect = 1f, cx = x, cy = y, startMs = 0, endMs = END))
        },
        Template("sale", "Dhimis", "Sale poster", true, "4:5", 0, 0xFFD00000.toInt(), 0xFFFFBA08.toInt()) { p ->
            p.bgColor = 0xFFD00000.toInt(); p.bgColor2 = 0xFF6A040F.toInt()
            p.layers.add(title(L.t("DHIMIS", "SALE"), 0, END, 0.38f, 0.22f, 0xFFFFE600.toInt(), TextAnim.NONE, TextAnim.NONE).also {
                it.depth = 0.7f; it.depthColor = 0xFF6A040F.toInt(); it.strokeColor = 0xFF000000.toInt(); it.strokeWidth = 0.05f
            })
            p.layers.add(Layer(kind = LayerKind.SHAPE, shape = ShapeKind.ROUND_RECT, textColor = 0xFFFFFFFF.toInt(), baseW = 0.7f, contentAspect = 0.22f, cy = 0.64f, startMs = 0, endMs = END))
            p.layers.add(title("50% OFF", 0, END, 0.64f, 0.09f, 0xFFD00000.toInt(), TextAnim.NONE, TextAnim.NONE).also { it.shadow = false })
        },
        Template("bday_card", "Kaarka dhalashada", "Birthday card", true, "1:1", 0, 0xFF4CC9F0.toInt(), 0xFF7209B7.toInt()) { p ->
            p.bgColor = 0xFF4CC9F0.toInt(); p.bgColor2 = 0xFF7209B7.toInt()
            p.layers.add(title(L.t("Dhalasho\nwanaagsan", "Happy\nBirthday"), 0, END, 0.42f, 0.13f, 0xFFFFF176.toInt(), TextAnim.NONE, TextAnim.NONE).also {
                it.textColor2 = 0xFFFF3D00.toInt(); it.strokeColor = 0xFF3E0000.toInt(); it.strokeWidth = 0.08f
            })
            for ((x, y) in listOf(0.15f to 0.15f, 0.85f to 0.2f, 0.5f to 0.85f))
                p.layers.add(Layer(kind = LayerKind.SHAPE, shape = ShapeKind.HEART, textColor = 0xFFFF4D6D.toInt(), baseW = 0.12f, contentAspect = 0.9f, cx = x, cy = y, startMs = 0, endMs = END))
        },
        Template("thumb", "YouTube thumbnail", "YouTube thumbnail", true, "16:9", 1, 0xFFFF0000.toInt(), 0xFF282828.toInt()) { p ->
            p.layers.add(title(L.t("CINWAAN\nWEYN!", "BIG\nTITLE!"), 0, END, 0.5f, 0.11f, 0xFFFFE600.toInt(), TextAnim.NONE, TextAnim.NONE).also {
                it.cx = 0.32f; it.strokeColor = 0xFF000000.toInt(); it.strokeWidth = 0.18f; it.align = 0
            })
            p.layers.add(Layer(kind = LayerKind.SHAPE, shape = ShapeKind.ARROW, textColor = 0xFFFF0000.toInt(), strokeColor = 0xFFFFFFFF.toInt(),
                strokeWidth = 0.06f, baseW = 0.25f, contentAspect = 0.45f, cx = 0.75f, cy = 0.7f, rotation = -30f, startMs = 0, endMs = END))
        },
        Template("insta", "Instagram", "Instagram post", true, "4:5", 0, 0xFFFFE5EC.toInt(), 0xFFFFC2D1.toInt()) { p ->
            p.bgColor = 0xFFFFE5EC.toInt(); p.bgColor2 = 0xFFFFC2D1.toInt()
            p.layers.add(title(L.t("Qoraalkaaga", "Your words"), 0, END, 0.46f, 0.1f, 0xFF3A0CA3.toInt(), TextAnim.NONE, TextAnim.NONE, 3).also { it.shadow = false; it.bold = false })
            p.layers.add(Layer(kind = LayerKind.SHAPE, shape = ShapeKind.LINE, textColor = 0xFF3A0CA3.toInt(), baseW = 0.4f, contentAspect = 0.04f, cy = 0.58f, startMs = 0, endMs = END))
        }
    )
}

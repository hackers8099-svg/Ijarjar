package so.ijarjar.app.data

import so.ijarjar.app.L
import so.ijarjar.app.model.Easing
import so.ijarjar.app.model.Keyframe
import so.ijarjar.app.model.Layer
import so.ijarjar.app.model.LayerAnim
import so.ijarjar.app.model.LayerKind
import so.ijarjar.app.model.LoopAnim
import so.ijarjar.app.model.ShapeKind
import so.ijarjar.app.model.TextAnim
import so.ijarjar.app.model.TextLoop
import so.ijarjar.app.model.newId

/**
 * Animated text templates (like After Effects / Premiere motion graphics): lower thirds,
 * callouts, quotes, titles, subscribe buttons … Each template is a few layers linked together.
 */
class TitleTemplate(val so: String, val en: String, val group: Int, val build: (start: Long, end: Long) -> List<Layer>) {
    val label: String get() = L.t(so, en)
}

object TitleTemplates {

    val groups: List<String> get() = listOf(L.t("Hoose (lower third)", "Lower thirds"), "Callouts", L.t("Odhaah", "Quotes"), L.t("Cinwaan", "Titles"), "Social")

    private fun text(t: String, cx: Float, cy: Float, size: Float, color: Int = 0xFFFFFFFF.toInt(), font: Int = 7, bold: Boolean = true,
                     tin: TextAnim = TextAnim.APPLE, tout: TextAnim = TextAnim.LETTER_FADE, align: Int = 1) =
        Layer(kind = LayerKind.TEXT, text = t, cx = cx, cy = cy, textSizeFrac = size, textColor = color, font = font, bold = bold,
            textIn = tin, textOut = tout, align = align, animInMs = 800, animOutMs = 500)

    private fun shape(k: ShapeKind, cx: Float, cy: Float, w: Float, aspect: Float, color: Int, ain: LayerAnim = LayerAnim.NONE) =
        Layer(kind = LayerKind.SHAPE, shape = k, cx = cx, cy = cy, baseW = w, contentAspect = aspect, textColor = color,
            animIn = ain, animOut = LayerAnim.FADE, animInMs = 600, animOutMs = 400)

    /** Slide-in keyframes for a layer (from dx to its place). */
    private fun slideIn(l: Layer, dx: Float, dy: Float = 0f, ms: Long = 600) {
        l.keyframes.add(Keyframe(0, l.cx + dx, l.cy + dy, 1f, 0f, 1f, ease = Easing.EASE_OUT))
        l.keyframes.add(Keyframe(ms, l.cx, l.cy, 1f, 0f, 1f))
        l.motionBlur = true
    }

    private fun group(start: Long, end: Long, vararg ls: Layer): List<Layer> {
        val g = newId()
        for (l in ls) { l.startMs = start; l.endMs = end; l.linkGroup = g }
        return ls.toList()
    }

    val all: List<TitleTemplate> = listOf(
        // ---- lower thirds
        TitleTemplate("Magac + shaqo", "Name + role", 0) { s, e ->
            val bar = shape(ShapeKind.RECT, 0.3f, 0.8f, 0.5f, 0.2f, 0xFF19D3C5.toInt()).also { slideIn(it, -0.7f) }
            val name = text(L.t("Magacaaga", "Your Name"), 0.3f, 0.785f, 0.055f, 0xFF00201E.toInt(), 6, tin = TextAnim.WORD_SLIDE_LEFT)
            val role = text(L.t("Shaqada", "Job title"), 0.3f, 0.845f, 0.035f, 0xFFFFFFFF.toInt(), 7, false, TextAnim.LETTER_FADE)
            group(s, e, bar, name, role)
        },
        TitleTemplate("Xariiq", "Accent line", 0) { s, e ->
            val line = shape(ShapeKind.RECT, 0.08f, 0.8f, 0.012f, 9f, 0xFFFF3D7F.toInt(), LayerAnim.SLIDE_UP)
            val name = text(L.t("Magacaaga", "Your Name"), 0.32f, 0.78f, 0.06f, align = 0, tin = TextAnim.LETTER_RISE)
            val role = text(L.t("Faahfaahin", "Description"), 0.32f, 0.835f, 0.035f, 0xCCFFFFFF.toInt(), 5, false, TextAnim.WORD_FADE_UP, align = 0)
            group(s, e, line, name, role)
        },
        TitleTemplate("Sanduuq madow", "Dark box", 0) { s, e ->
            val box = shape(ShapeKind.ROUND_RECT, 0.5f, 0.82f, 0.8f, 0.2f, 0xCC000000.toInt()).also { slideIn(it, 0f, 0.3f) }
            val t = text(L.t("Warka ugu dambeeyay", "Latest news"), 0.5f, 0.82f, 0.05f, tin = TextAnim.TRACKING)
            group(s, e, box, t)
        },
        TitleTemplate("Warka degdegga", "Breaking news", 0) { s, e ->
            val red = shape(ShapeKind.RECT, 0.2f, 0.82f, 0.38f, 0.22f, 0xFFD00000.toInt()).also { slideIn(it, -0.6f, ms = 450) }
            val white = shape(ShapeKind.RECT, 0.69f, 0.82f, 0.62f, 0.13f, 0xFFFFFFFF.toInt()).also { slideIn(it, 0.9f, ms = 600) }
            val t1 = text(L.t("DEGDEG", "BREAKING"), 0.2f, 0.82f, 0.045f, 0xFFFFFFFF.toInt(), 6, tin = TextAnim.LETTER_POP)
            val t2 = text(L.t("Cinwaanka warka halkan", "Headline goes here"), 0.69f, 0.82f, 0.035f, 0xFF111111.toInt(), 7, tin = TextAnim.WORD_SLIDE_RIGHT)
            group(s, e, red, white, t1, t2)
        },
        // ---- callouts
        TitleTemplate("Tilmaan", "Pointer callout", 1) { s, e ->
            val dot = shape(ShapeKind.CIRCLE, 0.35f, 0.55f, 0.04f, 1f, 0xFFFFFFFF.toInt(), LayerAnim.POP)
            val ring = shape(ShapeKind.RING, 0.35f, 0.55f, 0.09f, 1f, 0xFFFFFFFF.toInt(), LayerAnim.ZOOM).also { it.animLoop = LoopAnim.PULSE }
            val line = shape(ShapeKind.LINE, 0.5f, 0.45f, 0.3f, 0.05f, 0xFFFFFFFF.toInt(), LayerAnim.FADE).also { it.rotation = -33f }
            val t = text(L.t("Tilmaan", "Callout"), 0.75f, 0.36f, 0.05f, tin = TextAnim.WORD_FADE_UP)
            group(s, e, ring, dot, line, t)
        },
        TitleTemplate("Hadal", "Speech bubble", 1) { s, e ->
            val b = shape(ShapeKind.BUBBLE, 0.5f, 0.4f, 0.6f, 0.55f, 0xFFFFFFFF.toInt(), LayerAnim.POP)
            val t = text(L.t("Salaan!", "Hello!"), 0.5f, 0.37f, 0.07f, 0xFF111111.toInt(), 9, tin = TextAnim.LETTER_POP)
            group(s, e, b, t)
        },
        TitleTemplate("Fallaar", "Arrow label", 1) { s, e ->
            val a = shape(ShapeKind.ARROW, 0.35f, 0.5f, 0.3f, 0.45f, 0xFFFFCC00.toInt()).also { slideIn(it, -0.4f) ; it.expr = so.ijarjar.app.model.Expression.BOUNCE }
            val t = text(L.t("EEG", "LOOK"), 0.7f, 0.5f, 0.08f, 0xFFFFCC00.toInt(), 6, tin = TextAnim.LETTER_DROP)
            group(s, e, a, t)
        },
        TitleTemplate("Meel", "Location pin", 1) { s, e ->
            val c = shape(ShapeKind.CIRCLE, 0.25f, 0.2f, 0.07f, 1f, 0xFFFF3B30.toInt(), LayerAnim.DROP)
            val t = text(L.t("Muqdisho, Soomaaliya", "Mogadishu, Somalia"), 0.6f, 0.2f, 0.045f, align = 0, tin = TextAnim.TYPEWRITER)
            group(s, e, c, t)
        },
        // ---- quotes
        TitleTemplate("Odhaah", "Quote", 2) { s, e ->
            val q = text("“", 0.5f, 0.3f, 0.25f, 0xFF19D3C5.toInt(), 1, tin = TextAnim.LETTER_ZOOM)
            val t = text(L.t("Ku qor odhaahda\nhalkan", "Write the quote\nhere"), 0.5f, 0.48f, 0.065f, font = 1, bold = false, tin = TextAnim.APPLE)
            val a = text("— " + L.t("Magaca", "Author"), 0.5f, 0.63f, 0.04f, 0xCCFFFFFF.toInt(), 5, false, TextAnim.WORD_FADE_UP)
            group(s, e, q, t, a)
        },
        TitleTemplate("Odhaah sanduuq", "Quote card", 2) { s, e ->
            val card = shape(ShapeKind.ROUND_RECT, 0.5f, 0.5f, 0.82f, 0.7f, 0xEEFFFFFF.toInt(), LayerAnim.ZOOM)
            val t = text(L.t("\"Odhaah qurux badan\"", "\"A beautiful quote\""), 0.5f, 0.47f, 0.06f, 0xFF111111.toInt(), 1, false, TextAnim.WORD_FADE_UP)
            val a = text("— " + L.t("Magaca", "Author"), 0.5f, 0.6f, 0.035f, 0xFF555555.toInt(), 7, false, TextAnim.LETTER_FADE)
            group(s, e, card, t, a)
        },
        // ---- titles
        TitleTemplate("Cinwaan weyn", "Big title", 3) { s, e ->
            group(s, e, text(L.t("CINWAAN", "TITLE"), 0.5f, 0.5f, 0.16f, font = 6, tin = TextAnim.TRACKING, tout = TextAnim.APPLE).also { it.letterSpacing = 0.1f })
        },
        TitleTemplate("Cutub", "Chapter", 3) { s, e ->
            val n = text("01", 0.5f, 0.42f, 0.14f, 0xFF19D3C5.toInt(), 6, tin = TextAnim.LETTER_DROP)
            val t = text(L.t("Cutubka koowaad", "Chapter one"), 0.5f, 0.55f, 0.05f, tin = TextAnim.APPLE)
            val l = shape(ShapeKind.LINE, 0.5f, 0.6f, 0.3f, 0.04f, 0xFFFFFFFF.toInt(), LayerAnim.ZOOM)
            group(s, e, n, t, l)
        },
        TitleTemplate("Neon", "Neon sign", 3) { s, e ->
            group(s, e, text("NEON", 0.5f, 0.5f, 0.15f, 0xFFFFFFFF.toInt(), 9, tin = TextAnim.GLITCH).also {
                it.glowColor = 0xFFFF2D95.toInt(); it.glowSize = 0.6f; it.textLoop = TextLoop.FLICKER
            })
        },
        TitleTemplate("Dahab 3D", "Gold 3D", 3) { s, e ->
            group(s, e, text(L.t("GUUL", "WIN"), 0.5f, 0.5f, 0.18f, 0xFFFFE27A.toInt(), 6, tin = TextAnim.BOUNCE_IN).also {
                it.textColor2 = 0xFFE09B12.toInt(); it.depth = 0.6f; it.depthColor = 0xFF6B4300.toInt(); it.glowColor = 0x88FFCC00.toInt()
                it.keyframes.add(Keyframe(0, 0.5f, 0.5f, 1f, 0f, 1f, ry = -35f)); it.keyframes.add(Keyframe(1500, 0.5f, 0.5f, 1f, 0f, 1f, ry = 0f))
            })
        },
        TitleTemplate("Cinema", "Cinematic", 3) { s, e ->
            val t = text(L.t("SHEEKO", "THE STORY"), 0.5f, 0.48f, 0.08f, 0xFFF5F5F5.toInt(), 1, false, TextAnim.TRACKING).also { it.letterSpacing = 0.3f }
            val sub = text(L.t("Filim cusub", "A new film"), 0.5f, 0.56f, 0.03f, 0xAAFFFFFF.toInt(), 5, false, TextAnim.LETTER_FADE).also { it.letterSpacing = 0.4f }
            group(s, e, t, sub)
        },
        // ---- social
        TitleTemplate("Subscribe", "Subscribe", 4) { s, e ->
            val btn = shape(ShapeKind.ROUND_RECT, 0.5f, 0.8f, 0.42f, 0.28f, 0xFFFF0000.toInt(), LayerAnim.POP)
            val t = text("SUBSCRIBE", 0.5f, 0.8f, 0.045f, 0xFFFFFFFF.toInt(), 6, tin = TextAnim.NONE).also { it.animIn = LayerAnim.POP }
            group(s, e, btn, t)
        },
        TitleTemplate("Magaca bogga", "Social handle", 4) { s, e ->
            val pill = shape(ShapeKind.ROUND_RECT, 0.5f, 0.88f, 0.55f, 0.18f, 0xE6FFFFFF.toInt()).also { slideIn(it, 0f, 0.2f) }
            val t = text("@ijarjar", 0.5f, 0.88f, 0.045f, 0xFF111111.toInt(), 6, tin = TextAnim.TYPEWRITER)
            group(s, e, pill, t)
        },
        TitleTemplate("Like", "Like + heart", 4) { s, e ->
            val h = shape(ShapeKind.HEART, 0.5f, 0.45f, 0.18f, 0.9f, 0xFFFF2D55.toInt(), LayerAnim.POP).also { it.animLoop = LoopAnim.HEARTBEAT }
            val t = text(L.t("Like riix", "Tap like"), 0.5f, 0.6f, 0.05f, tin = TextAnim.WORD_POP)
            group(s, e, h, t)
        },
        TitleTemplate("Hashtag", "Hashtag", 4) { s, e ->
            group(s, e, text("#IjarJar", 0.5f, 0.5f, 0.09f, 0xFF19D3C5.toInt(), 6, tin = TextAnim.RANDOM).also { it.textLoop = TextLoop.SHIMMER })
        }
    )
}

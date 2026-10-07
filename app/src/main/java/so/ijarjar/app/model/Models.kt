package so.ijarjar.app.model

import so.ijarjar.app.L
import java.util.UUID

enum class MediaKind { VIDEO, IMAGE }

enum class LayerKind { VIDEO, IMAGE, TEXT, STICKER, EFFECT, SHAPE }

enum class AudioKind { MUSIC, VOICE, EXTRACTED, SOUND }

enum class FilterPreset(val so: String, val en: String) {
    NONE("Caadi", "None"),
    BW("Madow-Cadaan", "B&W"),
    SEPIA("Sepia", "Sepia"),
    VINTAGE("Qadiim", "Vintage"),
    COOL("Qabow", "Cool"),
    WARM("Kulul", "Warm"),
    VIVID("Midab badan", "Vivid"),
    FADE("Daciif", "Faded"),
    NOIR("Mugdi", "Noir"),
    GOLDEN("Dahab", "Golden"),
    TEAL_ORANGE("Cinema", "Cinematic"),
    PINK("Casaan", "Pink"),
    INVERT("Rogan", "Invert");

    val label: String get() = L.t(so, en)
}

/** In / out animations for layers. */
enum class LayerAnim(val so: String, val en: String) {
    NONE("Midna", "None"),
    FADE("Iftiimi", "Fade"),
    SLIDE_UP("Kor u soo bax", "Slide up"),
    SLIDE_DOWN("Hoos u soo dhac", "Slide down"),
    SLIDE_LEFT("Bidix", "Slide left"),
    SLIDE_RIGHT("Midig", "Slide right"),
    ZOOM("Weyneyn", "Zoom"),
    POP("Bood", "Pop"),
    SPIN("Wareeg", "Spin"),
    TYPEWRITER("Qoraal-qor", "Typewriter");

    val label: String get() = L.t(so, en)
}

/** Transition that plays at the START of a clip (between the previous clip and this one). */
enum class TransitionKind(val so: String, val en: String) {
    NONE("Midna", "None"),
    FADE_BLACK("Madow", "Fade black"),
    FLASH("Iftiin cad", "Flash"),
    ZOOM_IN("Gal", "Zoom in"),
    ZOOM_OUT("Ka bax", "Zoom out"),
    SPIN("Wareeg", "Spin"),
    SHAKE("Gariir", "Shake");

    val label: String get() = L.t(so, en)
}

/** Continuous (looping) layer animation, played the whole time the layer is visible. */
enum class LoopAnim(val so: String, val en: String) {
    NONE("Midna", "None"),
    PULSE("Neef", "Pulse"),
    SWING("Lulo", "Swing"),
    FLOAT("Sabbeyn", "Float"),
    BLINK("Libdhi", "Blink"),
    ROTATE("Wareeg", "Rotate"),
    SHAKE("Gariir", "Shake"),
    HEARTBEAT("Garaac", "Heartbeat");

    val label: String get() = L.t(so, en)
}

/** Video effects placed on the timeline like a layer. */
enum class EffectKind(val so: String, val en: String, val group: Int) {
    SHAKE("Gariir", "Shake", 0),
    ZOOM_PULSE("Weyn-yar", "Zoom pulse", 0),
    SLOW_ZOOM("Soo dhowaan", "Slow zoom", 0),
    SWAY("Lulo", "Sway", 0),
    FLASH("Iftiin", "Flash", 1),
    STROBE("Libiqsi", "Strobe", 1),
    FADE_BLACK("Madoobaan", "Fade to black", 1),
    BW("Madow-Cadaan", "Black & white", 2),
    OLD_FILM("Filim qadiim", "Old film", 2),
    RAINBOW("Qaanso-roobaad", "Rainbow", 2),
    NEGATIVE("Rogan", "Negative", 2),
    VIGNETTE("Geeso madow", "Vignette", 3),
    LETTERBOX("Cinema", "Letterbox", 3),
    SNOW("Baraf", "Snow", 3),
    HEARTS("Qalbiyo", "Hearts", 3),
    CONFETTI("Dabaaldeg", "Confetti", 3),
    STARS("Xiddigo", "Sparkles", 3),
    RAIN("Roob", "Rain", 3),
    GRAIN("Bus", "Film grain", 3);

    val label: String get() = L.t(so, en)
}

/** Shapes (PixelLab style). */
enum class ShapeKind(val so: String, val en: String) {
    RECT("Afargees", "Rectangle"),
    ROUND_RECT("Afargees jilicsan", "Rounded"),
    CIRCLE("Wareeg", "Circle"),
    TRIANGLE("Saddex-geesle", "Triangle"),
    STAR("Xiddig", "Star"),
    HEART("Qalbi", "Heart"),
    ARROW("Fallaar", "Arrow"),
    LINE("Xariiq", "Line"),
    BUBBLE("Hadal", "Speech bubble");

    val label: String get() = L.t(so, en)
}

/** Mask shapes for overlay images / videos. */
enum class MaskKind(val so: String, val en: String) {
    NONE("Midna", "None"),
    CIRCLE("Wareeg", "Circle"),
    RECT("Afargees", "Rectangle"),
    HEART("Qalbi", "Heart"),
    STAR("Xiddig", "Star"),
    LINEAR("Toos", "Linear"),
    MIRROR("Muraayad", "Mirror");

    val label: String get() = L.t(so, en)
}

fun newId(): String = UUID.randomUUID().toString().substring(0, 8)

class Adjust(
    var brightness: Float = 0f,   // -1..1
    var contrast: Float = 0f,     // -1..1
    var saturation: Float = 0f,   // -1..1
    var temperature: Float = 0f,  // -1..1
    var tint: Float = 0f,         // -1..1
    var blur: Float = 0f,         // 0..1
    var preset: FilterPreset = FilterPreset.NONE
) {
    fun isColorIdentity() = brightness == 0f && contrast == 0f && saturation == 0f &&
        temperature == 0f && tint == 0f && (preset == FilterPreset.NONE)
    fun isIdentity() = isColorIdentity() && blur == 0f
    fun copy() = Adjust(brightness, contrast, saturation, temperature, tint, blur, preset)
}

/** A clip on the main track. */
class Clip(
    var id: String = newId(),
    var uri: String = "",
    var kind: MediaKind = MediaKind.VIDEO,
    var sourceDurationMs: Long = 3000,
    var trimStartMs: Long = 0,
    var trimEndMs: Long = 3000,
    var speed: Float = 1f,
    var volume: Float = 1f,
    var adjust: Adjust = Adjust(),
    var width: Int = 0,
    var height: Int = 0,
    // position of the clip on the canvas (CapCut "canvas" transform)
    var tScale: Float = 1f,
    var tRot: Float = 0f,
    var tX: Float = 0f,   // fraction of canvas width
    var tY: Float = 0f,   // fraction of canvas height
    var mirror: Boolean = false,
    var transition: TransitionKind = TransitionKind.NONE,
    var transitionMs: Long = 600
) {
    val trimmedMs: Long get() = (trimEndMs - trimStartMs).coerceAtLeast(1)
    val outDurationMs: Long get() = if (kind == MediaKind.IMAGE) trimmedMs else (trimmedMs / speed).toLong().coerceAtLeast(1)

    fun copy(): Clip = Clip(newId(), uri, kind, sourceDurationMs, trimStartMs, trimEndMs, speed, volume, adjust.copy(),
        width, height, tScale, tRot, tX, tY, mirror, transition, transitionMs)
}

/** Snapshot of a layer's transform at a moment in time (relative to the layer start). */
class Keyframe(
    var t: Long = 0,
    var cx: Float = 0.5f,
    var cy: Float = 0.5f,
    var scale: Float = 1f,
    var rotation: Float = 0f,
    var opacity: Float = 1f
) {
    fun copy() = Keyframe(t, cx, cy, scale, rotation, opacity)
}

/** A free layer on top of the main track (overlay video, image, text, sticker). */
class Layer(
    var id: String = newId(),
    var kind: LayerKind = LayerKind.TEXT,
    var name: String = "",
    var uri: String? = null,
    var text: String = "",
    var textColor: Int = 0xFFFFFFFF.toInt(),
    var strokeColor: Int = 0,
    var bgColor: Int = 0,
    var textSizeFrac: Float = 0.07f,
    var bold: Boolean = true,
    var font: Int = 0,
    var align: Int = 1, // 0 left, 1 center, 2 right
    var cx: Float = 0.5f,
    var cy: Float = 0.5f,
    var baseW: Float = 0.5f,       // width as a fraction of canvas width (image/video)
    var contentAspect: Float = 1f, // height / width of content
    var scale: Float = 1f,
    var rotation: Float = 0f,
    var opacity: Float = 1f,
    var flipH: Boolean = false,
    var startMs: Long = 0,
    var endMs: Long = 3000,
    var linkGroup: String? = null,
    var sourceDurationMs: Long = 0,
    var trimStartMs: Long = 0,
    var keyframes: MutableList<Keyframe> = mutableListOf(),
    var animIn: LayerAnim = LayerAnim.NONE,
    var animOut: LayerAnim = LayerAnim.NONE,
    var animInMs: Long = 500,
    var animOutMs: Long = 500,
    var shadow: Boolean = false,
    var mask: MaskKind = MaskKind.NONE,
    var maskSize: Float = 0.8f,
    var maskFeather: Float = 0.1f,
    var maskInvert: Boolean = false,
    var animLoop: LoopAnim = LoopAnim.NONE,
    var effect: EffectKind = EffectKind.SHAKE,
    var isCaption: Boolean = false,
    var shape: ShapeKind = ShapeKind.RECT,
    var textColor2: Int = 0,          // second colour = gradient fill (text & shapes)
    var depth: Float = 0f,            // 3D extrusion 0..1
    var depthColor: Int = 0xFF333333.toInt(),
    var letterSpacing: Float = 0f,    // em
    var strokeWidth: Float = 0.12f    // relative to text size / shape size
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(1)
    fun isActive(t: Long) = t >= startMs && t < endMs
    fun isTextLike() = kind == LayerKind.TEXT || kind == LayerKind.STICKER
    fun isEffect() = kind == LayerKind.EFFECT

    fun copy(): Layer = Layer(
        newId(), kind, name, uri, text, textColor, strokeColor, bgColor, textSizeFrac, bold, font, align,
        cx, cy, baseW, contentAspect, scale, rotation, opacity, flipH, startMs, endMs, linkGroup,
        sourceDurationMs, trimStartMs, keyframes.map { it.copy() }.toMutableList(), animIn, animOut,
        animInMs, animOutMs, shadow, mask, maskSize, maskFeather, maskInvert, animLoop, effect, isCaption, shape, textColor2, depth, depthColor, letterSpacing, strokeWidth
    )
}

/** Any extra audio: music, voice-over, audio taken from a clip, sound effects. */
class AudioTrack(
    var id: String = newId(),
    var uri: String = "",
    var name: String = "",
    var kind: AudioKind = AudioKind.MUSIC,
    var startMs: Long = 0,        // position on the timeline
    var trimStartMs: Long = 0,    // where in the file it starts
    var durationMs: Long = 0,     // how long it plays
    var sourceDurationMs: Long = 0,
    var volume: Float = 1f,
    var fromVideo: Boolean = false
) {
    val endMs: Long get() = startMs + durationMs
    fun isActive(t: Long) = t >= startMs && t < endMs
    fun copy() = AudioTrack(newId(), uri, name, kind, startMs, trimStartMs, durationMs, sourceDurationMs, volume, fromVideo)
}

/** Old single music track (kept so older projects still load). */
class Music(
    var uri: String = "",
    var name: String = "",
    var sourceDurationMs: Long = 0,
    var trimStartMs: Long = 0,
    var volume: Float = 1f
)

class Project(
    var id: String = newId(),
    var name: String = "Mashruuc",
    var aspect: String = "9:16",
    var clips: MutableList<Clip> = mutableListOf(),
    var layers: MutableList<Layer> = mutableListOf(),
    var audios: MutableList<AudioTrack> = mutableListOf(),
    var music: Music? = null,
    var bgColor: Int = 0xFF000000.toInt(),
    var bgColor2: Int = 0,           // gradient background (photo mode)
    var bgImageUri: String? = null,  // background picture (photo mode)
    var bgAdjust: Adjust = Adjust(),
    var bgScale: Float = 1f,
    var bgRot: Float = 0f,
    var bgX: Float = 0f,
    var bgY: Float = 0f,
    var bgMirror: Boolean = false,
    var isPhoto: Boolean = false,
    var updatedAt: Long = System.currentTimeMillis()
) {
    val durationMs: Long get() = clips.sumOf { it.outDurationMs }

    fun clipStartMs(index: Int): Long {
        var t = 0L
        for (i in 0 until index.coerceAtMost(clips.size)) t += clips[i].outDurationMs
        return t
    }

    fun clipIndexAt(t: Long): Int {
        var acc = 0L
        for ((i, c) in clips.withIndex()) {
            if (t < acc + c.outDurationMs) return i
            acc += c.outDurationMs
        }
        return clips.size - 1
    }

    fun aspectRatio(): Float {
        val p = aspect.split(":")
        return p[0].toFloat() / p[1].toFloat() // width / height
    }

    /** Output size for export, short side = [shortSide]. Always even numbers. */
    fun outputSize(shortSide: Int): Pair<Int, Int> {
        val r = aspectRatio()
        val (w, h) = if (r >= 1f) Pair((shortSide * r).toInt(), shortSide) else Pair(shortSide, (shortSide / r).toInt())
        return Pair(w / 2 * 2, h / 2 * 2)
    }

    fun linkedWith(layer: Layer): List<Layer> {
        val g = layer.linkGroup ?: return listOf(layer)
        return layers.filter { it.linkGroup == g }
    }
}

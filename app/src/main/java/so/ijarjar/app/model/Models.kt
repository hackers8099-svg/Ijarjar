package so.ijarjar.app.model

import com.google.gson.Gson
import so.ijarjar.app.L
import java.util.UUID

enum class MediaKind { VIDEO, IMAGE }

/** ANIMATED = GIF or PNG sequence (transparent motion graphics). DRAW = brush drawing. */
enum class LayerKind { VIDEO, IMAGE, TEXT, STICKER, EFFECT, SHAPE, ANIMATED, DRAW, MODEL3D }

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
    MOODY("Murugo", "Moody"),
    PASTEL("Jilicsan", "Pastel"),
    SUNSET("Qorrax-dhac", "Sunset"),
    FOREST("Kayn", "Forest"),
    MATTE("Matte", "Matte"),
    CYBER("Cyber", "Cyberpunk"),
    MONO_HI("Madow adag", "Hi-con B&W"),
    KODAK("Filim", "Film"),
    INVERT("Rogan", "Invert");

    val label: String get() = L.t(so, en)
}

/** In / out animations for a whole layer. */
enum class LayerAnim(val so: String, val en: String) {
    NONE("Midna", "None"),
    FADE("Iftiimi", "Fade"),
    SLIDE_UP("Kor u soo bax", "Slide up"),
    SLIDE_DOWN("Hoos u soo dhac", "Slide down"),
    SLIDE_LEFT("Bidix", "Slide left"),
    SLIDE_RIGHT("Midig", "Slide right"),
    ZOOM("Weyneyn", "Zoom"),
    ZOOM_OUT("Yaraan", "Zoom out"),
    POP("Bood", "Pop"),
    SPIN("Wareeg", "Spin"),
    FLIP("Rog", "Flip"),
    DROP("Dhac", "Drop"),
    BLUR("Qariin", "Blur"),
    TYPEWRITER("Qoraal-qor", "Typewriter");

    val label: String get() = L.t(so, en)
}

/** Letter / word animations for text (Apple, After Effects, Premiere style). */
enum class TextAnim(val so: String, val en: String) {
    NONE("Midna", "None"),
    APPLE("Apple", "Apple blur"),
    CUSTOM("Gaar ah", "Custom"),
    LETTER_FADE("Xaraf-xaraf", "Letter fade"),
    LETTER_RISE("Xaraf kor", "Letter rise"),
    LETTER_DROP("Xaraf dhac", "Letter drop"),
    LETTER_POP("Xaraf bood", "Letter pop"),
    LETTER_SPIN("Xaraf wareeg", "Letter spin"),
    LETTER_ZOOM("Xaraf weyn", "Letter zoom"),
    TRACKING("Kala fidin", "Tracking"),
    COLOR_IN("Midab", "With colors"),
    RANDOM("Kala firdhi", "Random letters"),
    WORD_FADE_UP("Eray kor", "Word fade up"),
    WORD_FADE_DOWN("Eray hoos", "Word fade down"),
    WORD_SLIDE_LEFT("Eray bidix", "From left"),
    WORD_SLIDE_RIGHT("Eray midig", "From right"),
    WORD_POP("Eray bood", "Word pop"),
    TYPEWRITER("Qoraal-qor", "Typewriter"),
    GLITCH("Glitch", "Glitch"),
    BOUNCE_IN("Booddo", "Bounce");

    val label: String get() = L.t(so, en)
}

/** Looping letter / word animations (captions, titles). */
enum class TextLoop(val so: String, val en: String) {
    NONE("Midna", "None"),
    WAVE("Mowjad", "Wave"),
    BOUNCE("Bood", "Bounce"),
    KARAOKE("Karaoke", "Karaoke"),
    WORD_HIGHLIGHT("Eray iftiin", "Word highlight"),
    WORD_POP("Eray weyn", "Word pop"),
    SHIMMER("Dhalaal", "Shimmer"),
    RAINBOW("Qaanso", "Rainbow"),
    JITTER("Gariir", "Jitter"),
    FLICKER("Libdhi", "Flicker");

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
    SHAKE("Gariir", "Shake"),
    SLIDE_LEFT("Bidix", "Slide left"),
    SLIDE_RIGHT("Midig", "Slide right"),
    SLIDE_UP("Kor", "Slide up"),
    SLIDE_DOWN("Hoos", "Slide down"),
    WHIP("Whip", "Whip pan"),
    SQUEEZE("Cadaadi", "Squeeze"),
    GLITCH("Glitch", "Glitch");

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
    BOUNCE("Booddo", "Bounce", 0),
    GLITCH("Glitch", "Glitch", 0),
    EARTHQUAKE("Dhulgariir", "Earthquake", 0),
    FLASH("Iftiin", "Flash", 1),
    STROBE("Libiqsi", "Strobe", 1),
    FADE_BLACK("Madoobaan", "Fade to black", 1),
    FADE_WHITE("Caddaan", "Fade to white", 1),
    BW("Madow-Cadaan", "Black & white", 2),
    OLD_FILM("Filim qadiim", "Old film", 2),
    RAINBOW("Qaanso-roobaad", "Rainbow", 2),
    NEGATIVE("Rogan", "Negative", 2),
    NEON("Neon", "Neon", 2),
    VIGNETTE("Geeso madow", "Vignette", 3),
    LETTERBOX("Cinema", "Letterbox", 3),
    SNOW("Baraf", "Snow", 3),
    HEARTS("Qalbiyo", "Hearts", 3),
    CONFETTI("Dabaaldeg", "Confetti", 3),
    STARS("Xiddigo", "Sparkles", 3),
    RAIN("Roob", "Rain", 3),
    GRAIN("Bus", "Film grain", 3),
    BUBBLES("Xumbo", "Bubbles", 3),
    FIREWORKS("Rashaash", "Fireworks", 3),
    LIGHT_LEAK("Iftiin daadan", "Light leak", 3),
    VHS("VHS", "VHS", 3),
    REC("Kamarad", "Camera REC", 3),
    SPOTLIGHT("Iftiin dhexe", "Spotlight", 3),
    DREAMY("Riyo", "Dreamy glow", 2),
    BLOOM("Iftiin badan", "Bloom", 2),
    LENS_FLARE("Lens flare", "Lens flare", 3),
    GLOW_EDGES("Cidhif iftiin", "Glow frame", 3),
    SPARKLE_GLOW("Dhalaal", "Glitter", 3),
    BOKEH("Bokeh", "Bokeh", 3);

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
    BUBBLE("Hadal", "Speech bubble"),
    HEXAGON("Lix-geesle", "Hexagon"),
    RING("Giraan", "Ring");

    val label: String get() = L.t(so, en)
}

/** Device frames for 3D mockups. */
enum class MockupKind(val so: String, val en: String) {
    NONE("Midna", "None"),
    PHONE("Taleefan", "Phone"),
    PHONE_ROUND("Taleefan 2", "Phone 2"),
    PHONE_PRO("Pro · 3 kamarad", "Pro · 3 cameras"),
    PHONE_ULTRA("Ultra · 5 lens", "Ultra · 5 lenses"),
    PHONE_BAR("Bar kamarad", "Camera bar"),
    TABLET("Tablet", "Tablet"),
    LAPTOP("Laptop", "Laptop"),
    BROWSER("Browser", "Browser"),
    WATCH("Saacad", "Watch"),
    TV("TV", "TV"),
    POLAROID("Polaroid", "Polaroid");

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

/** After Effects style expressions that add motion on top of keyframes. */
enum class Expression(val so: String, val en: String) {
    NONE("Midna", "None"),
    BOUNCE("Bood (inertia)", "Bounce"),
    WIGGLE("Ruxid", "Wiggle"),
    LOOP_CYCLE("Ku celi", "loopOut cycle"),
    LOOP_PINGPONG("Tag-iyo-kaalay", "loopOut pingpong"),
    SQUASH("Cadaadis", "Squash & stretch"),
    ROTATE("Wareeg joogto", "time * rotate"),
    PULSE("Neef", "Pulse"),
    ORBIT("Meeraha", "Orbit"),
    FOCUS("Diirad", "Focus pull");

    val label: String get() = L.t(so, en)
}

/** Keyframe interpolation curves (After Effects style). */
enum class Easing(val so: String, val en: String) {
    LINEAR("Toos", "Linear"),
    EASE_IN("Tartiib bilow", "Ease in"),
    EASE_OUT("Tartiib dhammee", "Ease out"),
    EASE_IN_OUT("Tartiib labada", "Ease in-out"),
    BACK("Dib-u-bood", "Back"),
    BOUNCE("Booddo", "Bounce"),
    ELASTIC("Laastig", "Elastic"),
    HOLD("Joogso", "Hold"),
    CUSTOM("Gaar ah", "Custom");

    val label: String get() = L.t(so, en)
}

/** Speed curves (CapCut style). Values are speed multipliers at 5 evenly spaced points. */
enum class SpeedCurve(val so: String, val en: String, val points: FloatArray) {
    NONE("Midna", "None", floatArrayOf(1f, 1f, 1f, 1f, 1f)),
    MONTAGE("Montage", "Montage", floatArrayOf(1f, 3f, 0.6f, 3f, 1f)),
    HERO("Geesi", "Hero", floatArrayOf(2f, 2f, 0.3f, 2f, 2f)),
    BULLET("Xabbad", "Bullet", floatArrayOf(3f, 0.3f, 0.3f, 0.3f, 3f)),
    JUMP_CUT("Bood-goyn", "Jump cut", floatArrayOf(1f, 5f, 1f, 5f, 1f)),
    FLASH_IN("Degdeg gal", "Flash in", floatArrayOf(5f, 3f, 1f, 1f, 1f)),
    FLASH_OUT("Degdeg bax", "Flash out", floatArrayOf(1f, 1f, 1f, 3f, 5f)),
    CUSTOM("Gaar ah", "Custom", floatArrayOf(1f, 1f, 1f, 1f, 1f));

    val label: String get() = L.t(so, en)
}

fun newId(): String = UUID.randomUUID().toString().substring(0, 8)

private val cloner = Gson()

/** Deep copy through JSON (keeps every field, new ones included). */
fun <T> gsonCopy(x: T, c: Class<T>): T = cloner.fromJson(cloner.toJson(x), c)

class Adjust(
    var brightness: Float = 0f,   // -1..1
    var contrast: Float = 0f,     // -1..1
    var saturation: Float = 0f,   // -1..1
    var temperature: Float = 0f,  // -1..1
    var tint: Float = 0f,         // -1..1
    var blur: Float = 0f,         // 0..1
    var preset: FilterPreset = FilterPreset.NONE,
    var lutUri: String? = null,   // .cube colour lookup table
    var lutName: String = "",
    var lutStrength: Float = 1f,
    var highlights: Float = 0f,   // -1..1
    var shadows: Float = 0f,      // -1..1
    var vibrance: Float = 0f,     // -1..1
    var fade: Float = 0f          // 0..1 (lifted blacks)
) {
    fun isColorIdentity() = brightness == 0f && contrast == 0f && saturation == 0f &&
        temperature == 0f && tint == 0f && (preset == FilterPreset.NONE)
    fun hasTone() = highlights != 0f || shadows != 0f || vibrance != 0f || fade != 0f
    fun isIdentity() = isColorIdentity() && blur == 0f && lutUri == null && !hasTone()
    fun copy(): Adjust = gsonCopy(this, Adjust::class.java)
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
    var tScale: Float = 1f,
    var tRot: Float = 0f,
    var tX: Float = 0f,
    var tY: Float = 0f,
    var mirror: Boolean = false,
    var transition: TransitionKind = TransitionKind.NONE,
    var transitionMs: Long = 600,
    var curve: SpeedCurve = SpeedCurve.NONE,
    var curvePoints: MutableList<Float> = mutableListOf(1f, 1f, 1f, 1f, 1f),
    var reversed: Boolean = false,
    var originalUri: String? = null,
    // voice clean-up
    var denoise: Float = 0f,
    var enhanceVoice: Boolean = false,
    var voice: VoiceFx = VoiceFx.NONE,
    var sfx: SoundFx = SoundFx(),
    // stabilisation: correction (x, y) per 100 ms of source, as a fraction of the frame
    var stab: Boolean = false,
    var stabPath: MutableList<Float> = mutableListOf(),
    var stabZoom: Float = 1.1f
) {
    val trimmedMs: Long get() = (trimEndMs - trimStartMs).coerceAtLeast(1)
    val hasCurve: Boolean get() = kind == MediaKind.VIDEO && curve != SpeedCurve.NONE
    val outDurationMs: Long
        get() = when {
            kind == MediaKind.IMAGE -> trimmedMs
            hasCurve -> so.ijarjar.app.render.SpeedMap.outDuration(this)
            else -> (trimmedMs / speed).toLong().coerceAtLeast(1)
        }

    fun copy(): Clip = gsonCopy(this, Clip::class.java).also { it.id = newId() }
}

/** Snapshot of a layer's transform at a moment in time (relative to the layer start). */
class Keyframe(
    var t: Long = 0,
    var cx: Float = 0.5f,
    var cy: Float = 0.5f,
    var scale: Float = 1f,
    var rotation: Float = 0f,
    var opacity: Float = 1f,
    var sx: Float = 1f,
    var sy: Float = 1f,
    var ease: Easing = Easing.EASE_IN_OUT,  // curve towards the NEXT keyframe
    var bx1: Float = 0.42f,
    var by1: Float = 0f,
    var bx2: Float = 0.58f,
    var by2: Float = 1f,
    var rx: Float = 0f,    // 3D tilt around X
    var ry: Float = 0f,    // 3D turn around Y
    var z: Float = 0f      // depth (positive = further away)
) {
    fun copy(): Keyframe = gsonCopy(this, Keyframe::class.java)
}

/** One brush stroke; points are x,y pairs in 0..1 of the layer box. */
class Stroke(
    var color: Int = 0xFFFFFFFF.toInt(),
    var width: Float = 0.01f,
    var points: MutableList<Float> = mutableListOf(),
    var eraser: Boolean = false,
    var type: Int = 0   // 0 pen, 1 marker (highlighter), 2 neon, 3 spray, 4 dashed
)

/** A free layer on top of the main track. */
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
    var fontPath: String? = null,
    var volume: Float = 1f,              // sound of an overlay video layer
    var voice: VoiceFx = VoiceFx.NONE,
    var sfx: SoundFx = SoundFx(),        // imported .ttf / .otf (overrides [font])
    var align: Int = 1,
    var cx: Float = 0.5f,
    var cy: Float = 0.5f,
    var baseW: Float = 0.5f,
    var contentAspect: Float = 1f,
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
    var maskX: Float = 0.5f,            // mask centre inside the layer (0..1)
    var maskY: Float = 0.5f,
    var maskStretch: Float = 1f,        // mask width / height factor
    var animLoop: LoopAnim = LoopAnim.NONE,
    var effect: EffectKind = EffectKind.SHAKE,
    var isCaption: Boolean = false,
    var shape: ShapeKind = ShapeKind.RECT,
    var textColor2: Int = 0,
    var depth: Float = 0f,
    var depthColor: Int = 0xFF333333.toInt(),
    var letterSpacing: Float = 0f,
    var strokeWidth: Float = 0.12f,
    // letter / word animations
    var textIn: TextAnim = TextAnim.NONE,
    var textOut: TextAnim = TextAnim.NONE,
    var textLoop: TextLoop = TextLoop.NONE,
    var highlightColor: Int = 0xFFFFE600.toInt(),
    var exprCode: MutableMap<String, String> = mutableMapOf(),   // AE-style expression code per property
    var shapeRound: Float = -1f,         // shape corner roundness 0..1 (-1 = shape's default)
    var hlRound: Float = 0.5f,          // word highlight box: 0 = square … 1 = pill
    var hlAnim: Int = 0,                // 0 pop, 1 slide from word to word, 2 fade, 3 grow from left, 4 none
    var hlTextColor: Int = 0,           // text colour on the highlighted word (0 = keep)
    // chroma key (green screen), After Effects Keylight style
    var chroma: Boolean = false,
    var chromaColor: Int = 0xFF00FF00.toInt(),
    var chromaTol: Float = 0.3f,
    var chromaSoft: Float = 0.1f,
    var chromaSpill: Float = 0.5f,
    var chromaChoke: Float = 0f,
    var chromaMatte: Boolean = false,
    // crop (fractions of the source picture)
    var cropL: Float = 0f,
    var cropT: Float = 0f,
    var cropR: Float = 0f,
    var cropB: Float = 0f,
    var srcAspect: Float = 1f,
    // picture outline / drop shadow (sticker look)
    var outlineColor: Int = 0,
    var outlineWidth: Float = 0.02f,
    // animated overlays: PNG sequence frames or a single GIF in uri
    var frames: MutableList<String> = mutableListOf(),
    var fps: Float = 25f,
    // brush drawing
    var strokes: MutableList<Stroke> = mutableListOf(),
    // width / height stretch (keyframable)
    var stretchX: Float = 1f,
    var stretchY: Float = 1f,
    // expressions & motion blur
    var expr: Expression = Expression.NONE,
    var exprAmp: Float = 1f,
    var exprFreq: Float = 2f,
    var exprDecay: Float = 5f,
    var motionBlur: Boolean = false,
    // colour for pictures, and Lottie (After Effects / Bodymovin) animations
    var adjust: Adjust = Adjust(),
    var isLottie: Boolean = false,
    var lottieText: String = "",
    // dynamic link: a photo project shown live as this layer
    var linkedProject: String? = null,
    // 3D transform (keyframable)
    var rotX: Float = 0f,
    var rotY: Float = 0f,
    var posZ: Float = 0f,
    // glow around text / shapes / pictures
    var glowColor: Int = 0,
    var glowSize: Float = 0.3f,
    // device mockup around a picture or video
    var mockup: MockupKind = MockupKind.NONE,
    var mockupColor: Int = 0xFF1C1C1E.toInt(),
    // custom text animator (letters / words)
    var taUnit: Int = 0,          // 0 letters, 1 words, 2 lines
    var taDx: Float = 0f,         // offsets in text heights
    var taDy: Float = 0.8f,
    var taScale: Float = 1f,
    var taRot: Float = 0f,
    var taOpacity: Float = 0f,
    var taBlur: Float = 0.5f,
    var taOverlap: Float = 0.35f, // 0.05 = one by one, 1 = all together
    var taOrder: Int = 0,         // 0 forward, 1 backward, 2 from centre, 3 random
    var taEase: Easing = Easing.EASE_OUT,
    // 3D model (.glb)
    var modelSpin: Float = 0f,
    var modelTexture: String? = null,     // picture put on the 3D model
    var modelMaterial: String? = null,    // which material gets it (null = all)
    var modelColor: Int = 0,
    var parts: MutableMap<String, ModelPart> = mutableMapOf(),   // per material of a 3D model
    var phoneStyle: String? = null       // generated 3D phone (PhoneGlb.Style name)
) {
    /** Where this layer's moving picture comes from (overlay video, or a video on a 3D model). */
    fun videoSource(): String? = when (kind) {
        LayerKind.VIDEO -> uri
        LayerKind.MODEL3D -> parts.values.firstOrNull { it.video && it.tex != null }?.tex
        else -> null
    }

    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(1)
    fun isActive(t: Long) = t >= startMs && t < endMs
    fun isTextLike() = kind == LayerKind.TEXT || kind == LayerKind.STICKER
    fun isEffect() = kind == LayerKind.EFFECT
    fun isPicture() = kind == LayerKind.IMAGE || kind == LayerKind.VIDEO || kind == LayerKind.ANIMATED
    fun hasCrop() = cropL > 0f || cropT > 0f || cropR > 0f || cropB > 0f

    fun copy(): Layer = gsonCopy(this, Layer::class.java).also { it.id = newId() }
}

/** Any extra audio: music, voice-over, audio taken from a clip, sound effects. */
class AudioTrack(
    var id: String = newId(),
    var uri: String = "",
    var name: String = "",
    var kind: AudioKind = AudioKind.MUSIC,
    var startMs: Long = 0,
    var trimStartMs: Long = 0,
    var durationMs: Long = 0,
    var sourceDurationMs: Long = 0,
    var volume: Float = 1f,
    var fromVideo: Boolean = false,
    var denoise: Float = 0f,
    var enhanceVoice: Boolean = false,
    var voice: VoiceFx = VoiceFx.NONE,
    var sfx: SoundFx = SoundFx()
) {
    val endMs: Long get() = startMs + durationMs
    fun isActive(t: Long) = t >= startMs && t < endMs
    fun copy(): AudioTrack = gsonCopy(this, AudioTrack::class.java).also { it.id = newId() }
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
    var bgColor2: Int = 0,
    var bgImageUri: String? = null,
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
        return p[0].toFloat() / p[1].toFloat()
    }

    /** Output size for export, short side = [shortSide]. Always even numbers. */
    fun outputSize(shortSide: Int): Pair<Int, Int> {
        val r = aspectRatio()
        val (w, h) = if (r >= 1f) Pair((shortSide * r).toInt(), shortSide) else Pair(shortSide, (shortSide / r).toInt())
        return Pair(w / 2 * 2, h / 2 * 2)
    }

    fun linkedWith(layer: Layer): List<Layer> {
        val sel = MultiSelect.ids
        if (sel.size > 1 && layer.id in sel) {
            val groups = layers.filter { it.id in sel }.mapNotNull { it.linkGroup }.toSet()
            return layers.filter { it.id in sel || (it.linkGroup != null && it.linkGroup in groups) }
        }
        val g = layer.linkGroup ?: return listOf(layer)
        return layers.filter { it.linkGroup == g }
    }
}

/** Layers picked together with "Select" (not saved): they move, scale and rotate as one. */
object MultiSelect {
    val ids = LinkedHashSet<String>()
}

/** Voice changer: [pitch] is applied by the player / Sonic, [mode] by AudioFx. */
enum class VoiceFx(val so: String, val en: String, val pitch: Float, val mode: Int) {
    NONE("Caadi", "Original", 1f, 0),
    CHIPMUNK("Carruur", "Chipmunk", 1.65f, 0),
    HELIUM("Helium", "Helium", 1.3f, 0),
    FEMALE("Dumar", "Higher", 1.18f, 0),
    MALE("Rag", "Lower", 0.85f, 0),
    DEEP("Qoto-dheer", "Deep", 0.72f, 0),
    MONSTER("Bahal", "Monster", 0.55f, 6),
    ROBOT("Robot", "Robot", 1f, 1),
    ALIEN("Shisheeye", "Alien", 1.15f, 7),
    ECHO("Dhawaaq celin", "Echo", 1f, 2),
    CAVE("God (reverb)", "Cave", 1f, 3),
    RADIO("Raadiyo", "Radio", 1f, 4),
    PHONE("Telefoon", "Telephone", 1f, 5),
    MEGAPHONE("Sameecad", "Megaphone", 1f, 8);
    val label: String get() = L.t(so, en)
}

/** Custom echo and room (reverb) settings for a clip or audio track. */
class SoundFx(
    var echoMix: Float = 0f,      // 0 = off
    var echoMs: Float = 280f,
    var echoFb: Float = 0.4f,
    var roomMix: Float = 0f,      // 0 = off
    var roomSize: Float = 0.5f
) {
    val on: Boolean get() = echoMix > 0.001f || roomMix > 0.001f
}

/** One part (material) of a 3D model: a friendly name, a picture or video on it, a colour. */
class ModelPart(var name: String = "", var tex: String? = null, var video: Boolean = false, var color: Int = 0)

package so.ijarjar.app.model

import java.util.UUID

enum class MediaKind { VIDEO, IMAGE }

enum class LayerKind { VIDEO, IMAGE, TEXT, STICKER }

enum class FilterPreset(val label: String) {
    NONE("Caadi"),
    BW("Madow-Cadaan"),
    SEPIA("Sepia"),
    VINTAGE("Qadiim"),
    COOL("Qabow"),
    WARM("Kulul"),
    VIVID("Midab badan"),
    FADE("Daciif"),
    INVERT("Rogan")
}

fun newId(): String = UUID.randomUUID().toString().substring(0, 8)

class Adjust(
    var brightness: Float = 0f,   // -1..1
    var contrast: Float = 0f,     // -1..1
    var saturation: Float = 0f,   // -1..1
    var preset: FilterPreset = FilterPreset.NONE
) {
    fun isIdentity() = brightness == 0f && contrast == 0f && saturation == 0f && preset == FilterPreset.NONE
    fun copy() = Adjust(brightness, contrast, saturation, preset)
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
    var height: Int = 0
) {
    val trimmedMs: Long get() = (trimEndMs - trimStartMs).coerceAtLeast(1)
    val outDurationMs: Long get() = if (kind == MediaKind.IMAGE) trimmedMs else (trimmedMs / speed).toLong().coerceAtLeast(1)

    fun copy(): Clip = Clip(newId(), uri, kind, sourceDurationMs, trimStartMs, trimEndMs, speed, volume, adjust.copy(), width, height)
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
    var trimStartMs: Long = 0
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(1)
    fun isActive(t: Long) = t >= startMs && t < endMs
    fun isTextLike() = kind == LayerKind.TEXT || kind == LayerKind.STICKER

    fun copy(): Layer = Layer(
        newId(), kind, name, uri, text, textColor, strokeColor, bgColor, textSizeFrac, bold, font, align,
        cx, cy, baseW, contentAspect, scale, rotation, opacity, flipH, startMs, endMs, linkGroup,
        sourceDurationMs, trimStartMs
    )
}

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
    var music: Music? = null,
    var updatedAt: Long = System.currentTimeMillis()
) {
    val durationMs: Long get() = clips.sumOf { it.outDurationMs }

    fun clipStartMs(index: Int): Long {
        var t = 0L
        for (i in 0 until index.coerceAtMost(clips.size)) t += clips[i].outDurationMs
        return t
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

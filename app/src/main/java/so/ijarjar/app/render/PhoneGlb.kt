package so.ijarjar.app.render

import android.content.Context
import so.ijarjar.app.L
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Builds real 3D phone models (glTF binary) in code: a metal frame, glass front, a screen that can
 * show any picture or video, a back with a camera module and lenses, and side buttons.
 * The screen material is called "Screen"; body parts "Body", "Frame", "Island".
 */
object PhoneGlb {

    enum class Style(val so: String, val en: String) {
        PRO("Pro · 3 kamarad", "Pro · 3 cameras"),
        ULTRA("Ultra · geesaha toosan", "Ultra · square corners"),
        BAR("Bar kamarad", "Camera bar"),
        CLASSIC("Caadi · 2 kamarad", "Classic · 2 cameras");
        val label: String get() = L.t(so, en)
    }

    /** Width / height of the screen of a style (pictures are fitted to it, not stretched). */
    fun screenAspect(style: Style): Float {
        val w = 0.74f; val h = 1.56f; val bev = 0.012f
        val inset = if (style == Style.ULTRA) 0.022f else 0.026f
        return (w - 2 * (bev + inset)) / (h - 2 * (bev + inset))
    }

    /** The .glb file for a style (made once, then reused). */
    fun file(context: Context, style: Style): File {
        val f = File(File(context.filesDir, "phones").apply { mkdirs() }, "phone_${style.name.lowercase()}_v4.glb")
        if (!f.exists()) f.writeBytes(build(style))
        return f
    }

    // ------------------------------------------------------------------ mesh building

    private class Prim(val material: Int) {
        val pos = ArrayList<Float>(); val nor = ArrayList<Float>(); val uv = ArrayList<Float>(); val idx = ArrayList<Int>()
        fun v(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, u: Float = 0f, w: Float = 0f): Int {
            pos += x; pos += y; pos += z; nor += nx; nor += ny; nor += nz; uv += u; uv += w
            return pos.size / 3 - 1
        }
        fun tri(a: Int, b: Int, c: Int) { idx += a; idx += b; idx += c }
    }

    /** Rounded rectangle outline (counter-clockwise) centred at (cx, cy). */
    private fun outline(cx: Float, cy: Float, w: Float, h: Float, r: Float, seg: Int = 10): List<FloatArray> {
        val out = ArrayList<FloatArray>()
        val rr = r.coerceAtMost(minOf(w, h) / 2f)
        val corners = listOf(
            floatArrayOf(cx + w / 2 - rr, cy - h / 2 + rr, -90f), floatArrayOf(cx + w / 2 - rr, cy + h / 2 - rr, 0f),
            floatArrayOf(cx - w / 2 + rr, cy + h / 2 - rr, 90f), floatArrayOf(cx - w / 2 + rr, cy - h / 2 + rr, 180f))
        for (c in corners) for (i in 0..seg) {
            val a = Math.toRadians((c[2] + 90.0 * i / seg)).toFloat()
            // point + outward normal
            out.add(floatArrayOf(c[0] + cos(a) * rr, c[1] + sin(a) * rr, cos(a), sin(a)))
        }
        return out
    }

    private fun circle(cx: Float, cy: Float, r: Float, seg: Int = 28): List<FloatArray> =
        (0 until seg).map { val a = (2 * PI * it / seg).toFloat(); floatArrayOf(cx + cos(a) * r, cy + sin(a) * r, cos(a), sin(a)) }

    /** Flat cap (fan) facing +z (front = true) or -z. */
    private fun cap(p: Prim, o: List<FloatArray>, z: Float, front: Boolean, uvBox: FloatArray? = null) {
        val cx = o.map { it[0] }.average().toFloat(); val cy = o.map { it[1] }.average().toFloat()
        val nz = if (front) 1f else -1f
        fun uvOf(x: Float, y: Float): Pair<Float, Float> = if (uvBox == null) Pair(0f, 0f)
            else Pair((uvBox[2] - x) / (uvBox[2] - uvBox[0]), (uvBox[3] - y) / (uvBox[3] - uvBox[1]))
        val (cu, cv) = uvOf(cx, cy)
        val c = p.v(cx, cy, z, 0f, 0f, nz, cu, cv)
        val first = p.pos.size / 3
        for (pt in o) { val (u, w) = uvOf(pt[0], pt[1]); p.v(pt[0], pt[1], z, 0f, 0f, nz, u, w) }
        for (i in o.indices) {
            val a = first + i; val b = first + (i + 1) % o.size
            if (front) p.tri(c, a, b) else p.tri(c, b, a)
        }
    }

    /** Side wall between z0 (back) and z1 (front). */
    private fun wall(p: Prim, o: List<FloatArray>, z0: Float, z1: Float) {
        val first = p.pos.size / 3
        for (pt in o) { p.v(pt[0], pt[1], z0, pt[2], pt[3], 0f); p.v(pt[0], pt[1], z1, pt[2], pt[3], 0f) }
        for (i in o.indices) {
            val a = first + i * 2; val b = first + ((i + 1) % o.size) * 2
            p.tri(a, b, a + 1); p.tri(b, b + 1, a + 1)
        }
    }

    /** Rounded edge (quarter round) from the wall to a cap: makes the body look solid, not flat. */
    private fun bevel(p: Prim, o: List<FloatArray>, zEdge: Float, depth: Float, inset: Float, front: Boolean, steps: Int = 4) {
        val first = p.pos.size / 3
        for (s in 0..steps) {
            val a = (PI / 2 * s / steps).toFloat()
            val inn = inset * (1 - cos(a)); val dz = depth * sin(a) * (if (front) 1 else -1)
            val nOut = cos(a); val nZ = sin(a) * (if (front) 1 else -1)
            for (pt in o) p.v(pt[0] - pt[2] * inn, pt[1] - pt[3] * inn, zEdge + dz, pt[2] * nOut, pt[3] * nOut, nZ)
        }
        val n = o.size
        for (s in 0 until steps) for (i in 0 until n) {
            val a = first + s * n + i; val b = first + s * n + (i + 1) % n
            val c = a + n; val d = b + n
            if (front) { p.tri(a, b, c); p.tri(b, d, c) } else { p.tri(a, c, b); p.tri(b, c, d) }
        }
    }

    private fun shrink(o: List<FloatArray>, d: Float) = o.map { floatArrayOf(it[0] - it[2] * d, it[1] - it[3] * d, it[2], it[3]) }

    fun build(style: Style): ByteArray {
        // materials: 0 Body, 1 Frame, 2 Glass, 3 Screen, 4 Island, 5 Lens, 6 LensRing, 7 Flash
        val prims = Array(8) { Prim(it) }
        val w = 0.74f; val h = 1.56f; val t = 0.082f
        val r = if (style == Style.ULTRA) 0.05f else 0.115f
        val bev = 0.012f
        val o = outline(0f, 0f, w, h, r, 12)
        val zf = t / 2 - bev; val zb = -t / 2 + bev
        // frame: wall + rounded edges front and back
        wall(prims[1], o, zb, zf)
        bevel(prims[1], o, zf, bev, bev, true)
        bevel(prims[1], o, zb, bev, bev, false)
        val inner = shrink(o, bev)
        // front glass and screen
        cap(prims[2], inner, t / 2, true)
        val sInset = if (style == Style.ULTRA) 0.022f else 0.026f
        val so = outline(0f, 0f, w - 2 * (bev + sInset), h - 2 * (bev + sInset), (r - bev - sInset * 0.6f).coerceAtLeast(0.02f), 12)
        val minX = so.minOf { it[0] }; val maxX = so.maxOf { it[0] }; val minY = so.minOf { it[1] }; val maxY = so.maxOf { it[1] }
        cap(prims[3], so, t / 2 + 0.0012f, true, floatArrayOf(minX, minY, maxX, maxY))
        // front camera: a pill-shaped island on the Pro (iPhone style), a punch hole on the others
        if (style == Style.PRO) cap(prims[5], outline(0f, maxY - 0.055f, 0.2f, 0.058f, 0.029f, 8), t / 2 + 0.0018f, true)
        else cap(prims[5], circle(0f, maxY - 0.04f, 0.016f, 20), t / 2 + 0.0018f, true)
        // back
        cap(prims[0], inner, -t / 2, false)
        val zBack = -t / 2
        fun lens(x: Float, y: Float, rad: Float, base: Float) {
            val ring = circle(x, y, rad, 32)
            wall(prims[6], ring, base - 0.016f, base)
            cap(prims[6], ring, base - 0.016f, false)
            cap(prims[5], circle(x, y, rad * 0.78f, 32), base - 0.0168f, false)
        }
        fun island(cx: Float, cy: Float, iw: Float, ih: Float, ir: Float, height: Float): Float {
            val io = outline(cx, cy, iw, ih, ir, 10)
            wall(prims[4], io, zBack - height, zBack)
            cap(prims[4], io, zBack - height, false)
            return zBack - height
        }
        // seen from the back the camera sits top-left = +x here
        when (style) {
            Style.PRO -> {
                val ix = w / 2 - 0.2f; val iy = h / 2 - 0.2f
                val base = island(ix, iy, 0.34f, 0.34f, 0.08f, 0.01f)
                lens(ix + 0.075f, iy + 0.075f, 0.06f, base); lens(ix + 0.075f, iy - 0.075f, 0.06f, base); lens(ix - 0.075f, iy, 0.06f, base)
                cap(prims[7], circle(ix - 0.075f, iy + 0.1f, 0.018f), base - 0.0005f, false)
            }
            Style.ULTRA -> {
                val x = w / 2 - 0.13f
                for (i in 0 until 3) lens(x, h / 2 - 0.15f - i * 0.15f, 0.058f, zBack)
                lens(x - 0.13f, h / 2 - 0.15f, 0.03f, zBack)
                cap(prims[7], circle(x - 0.13f, h / 2 - 0.26f, 0.018f), zBack - 0.0005f, false)
            }
            Style.BAR -> {
                val base = island(0f, h / 2 - 0.27f, w - 0.02f, 0.15f, 0.07f, 0.02f)
                lens(0.17f, h / 2 - 0.27f, 0.045f, base); lens(0.05f, h / 2 - 0.27f, 0.045f, base)
                cap(prims[7], circle(-0.08f, h / 2 - 0.27f, 0.016f), base - 0.0005f, false)
            }
            Style.CLASSIC -> {
                val ix = w / 2 - 0.15f; val iy = h / 2 - 0.2f
                val base = island(ix, iy, 0.2f, 0.32f, 0.08f, 0.008f)
                lens(ix, iy + 0.07f, 0.055f, base); lens(ix, iy - 0.07f, 0.055f, base)
            }
        }
        // side buttons on the right edge
        fun button(y: Float, len: Float) {
            val x0 = w / 2 - 0.006f; val x1 = w / 2 + 0.008f
            val bo = listOf(floatArrayOf(x0, y - len / 2, 0f, -1f), floatArrayOf(x1, y - len / 2, 1f, 0f),
                floatArrayOf(x1, y + len / 2, 1f, 0f), floatArrayOf(x0, y + len / 2, 0f, 1f))
            wall(prims[1], bo, -0.012f, 0.012f)
            cap(prims[1], bo, 0.012f, true)
            cap(prims[1], bo, -0.012f, false)
        }
        button(0.3f, 0.2f)
        button(0.05f, 0.12f)
        return write(prims.toList())
    }

    // ------------------------------------------------------------------ glTF binary writer

    /** 4×4 dark PNG shown until a picture is put on the screen. */
    private fun placeholderPng(): ByteArray = java.util.Base64.getDecoder()
        .decode("iVBORw0KGgoAAAANSUhEUgAAAAQAAAAECAIAAAAmkwkpAAAAEElEQVR4nGMQEZODIwbiOAB0lASBlgScggAAAABJRU5ErkJggg==")

    private fun write(prims: List<Prim>): ByteArray {
        val bin = ByteArrayOutputStream()
        val views = ArrayList<String>(); val accessors = ArrayList<String>(); val primJson = ArrayList<String>()
        fun pad() { while (bin.size() % 4 != 0) bin.write(0) }
        fun addFloats(list: List<Float>, comps: Int, type: String, target: Int, minMax: Boolean): Int {
            pad()
            val off = bin.size()
            val bb = ByteBuffer.allocate(list.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (f in list) bb.putFloat(f)
            bin.write(bb.array())
            views.add("""{"buffer":0,"byteOffset":$off,"byteLength":${list.size * 4},"target":$target}""")
            var mm = ""
            if (minMax) {
                val mins = (0 until comps).map { c -> (c until list.size step comps).minOf { list[it] } }
                val maxs = (0 until comps).map { c -> (c until list.size step comps).maxOf { list[it] } }
                mm = ""","min":${mins.joinToString(",", "[", "]")},"max":${maxs.joinToString(",", "[", "]")}"""
            }
            accessors.add("""{"bufferView":${views.size - 1},"componentType":5126,"count":${list.size / comps},"type":"$type"$mm}""")
            return accessors.size - 1
        }
        fun addIndices(list: List<Int>): Int {
            pad()
            val off = bin.size()
            val bb = ByteBuffer.allocate(list.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (i in list) bb.putInt(i)
            bin.write(bb.array())
            views.add("""{"buffer":0,"byteOffset":$off,"byteLength":${list.size * 4},"target":34963}""")
            accessors.add("""{"bufferView":${views.size - 1},"componentType":5125,"count":${list.size},"type":"SCALAR"}""")
            return accessors.size - 1
        }
        for (p in prims) {
            if (p.idx.isEmpty()) continue
            val pa = addFloats(p.pos, 3, "VEC3", 34962, true)
            val na = addFloats(p.nor, 3, "VEC3", 34962, false)
            val ua = addFloats(p.uv, 2, "VEC2", 34962, false)
            val ia = addIndices(p.idx)
            primJson.add("""{"attributes":{"POSITION":$pa,"NORMAL":$na,"TEXCOORD_0":$ua},"indices":$ia,"material":${p.material}}""")
        }
        // screen placeholder image
        pad()
        val png = placeholderPng()
        val imgOff = bin.size(); bin.write(png)
        views.add("""{"buffer":0,"byteOffset":$imgOff,"byteLength":${png.size}}""")
        val imgView = views.size - 1
        pad()
        val mats = listOf(
            """{"name":"Body","pbrMetallicRoughness":{"baseColorFactor":[0.55,0.57,0.6,1],"metallicFactor":0.15,"roughnessFactor":0.45}}""",
            """{"name":"Frame","pbrMetallicRoughness":{"baseColorFactor":[0.72,0.73,0.76,1],"metallicFactor":0.85,"roughnessFactor":0.28}}""",
            """{"name":"Glass","pbrMetallicRoughness":{"baseColorFactor":[0.01,0.01,0.012,1],"metallicFactor":0.0,"roughnessFactor":0.08}}""",
            """{"name":"Screen","pbrMetallicRoughness":{"baseColorTexture":{"index":0},"baseColorFactor":[1,1,1,1],"metallicFactor":0,"roughnessFactor":1},"extensions":{"KHR_materials_unlit":{}}}""",
            """{"name":"Island","pbrMetallicRoughness":{"baseColorFactor":[0.6,0.62,0.66,1],"metallicFactor":0.25,"roughnessFactor":0.3}}""",
            """{"name":"Lens","pbrMetallicRoughness":{"baseColorFactor":[0.02,0.025,0.05,1],"metallicFactor":0.2,"roughnessFactor":0.05}}""",
            """{"name":"LensRing","pbrMetallicRoughness":{"baseColorFactor":[0.78,0.79,0.82,1],"metallicFactor":0.9,"roughnessFactor":0.22}}""",
            """{"name":"Flash","pbrMetallicRoughness":{"baseColorFactor":[1,0.95,0.8,1],"metallicFactor":0,"roughnessFactor":0.4},"emissiveFactor":[0.6,0.55,0.4]}""")
        val json = """{"asset":{"version":"2.0","generator":"ijarjar"},"extensionsUsed":["KHR_materials_unlit"],"scene":0,"scenes":[{"nodes":[0]}],""" +
            """"nodes":[{"mesh":0,"name":"Phone"}],"meshes":[{"name":"Phone","primitives":[${primJson.joinToString(",")}]}],""" +
            """"materials":[${mats.joinToString(",")}],"textures":[{"source":0,"sampler":0}],"samplers":[{"magFilter":9729,"minFilter":9729,"wrapS":33071,"wrapT":33071}],""" +
            """"images":[{"bufferView":$imgView,"mimeType":"image/png"}],"accessors":[${accessors.joinToString(",")}],"bufferViews":[${views.joinToString(",")}],""" +
            """"buffers":[{"byteLength":${bin.size()}}]}"""
        var jb = json.toByteArray(Charsets.UTF_8)
        if (jb.size % 4 != 0) jb += ByteArray(4 - jb.size % 4) { 0x20 }
        val binBytes = bin.toByteArray()
        val total = 12 + 8 + jb.size + 8 + binBytes.size
        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(0x46546C67); out.putInt(2); out.putInt(total)
        out.putInt(jb.size); out.putInt(0x4E4F534A); out.put(jb)
        out.putInt(binBytes.size); out.putInt(0x004E4942); out.put(binBytes)
        return out.array()
    }
}

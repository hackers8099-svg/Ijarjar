package so.ijarjar.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Looks inside a .glb file: its pictures (textures), meshes, materials and nodes, and can swap a
 * picture for another one. Swapping writes a new .glb, so it works with any model and any shader.
 */
object GlbEdit {

    class Glb(val json: JSONObject, val bin: ByteArray)

    class Image(val index: Int, val name: String, val mime: String, val width: Int, val height: Int, val bytes: Int, val thumb: Bitmap?, val usedBy: List<String>)

    class Info(val meshes: List<String>, val nodes: List<String>, val materials: List<String>, val images: Int, val triangles: Long, val animations: Int)

    fun read(bytes: ByteArray): Glb? {
        if (bytes.size < 20) return null
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bb.int != 0x46546C67) return null
        bb.int; bb.int
        var json: JSONObject? = null
        var bin = ByteArray(0)
        while (bb.remaining() >= 8) {
            val len = bb.int; val type = bb.int
            if (len < 0 || len > bb.remaining()) break
            val chunk = ByteArray(len); bb.get(chunk)
            if (type == 0x4E4F534A) json = JSONObject(String(chunk, Charsets.UTF_8).trimEnd(' ', '\u0000'))
            else if (type == 0x004E4942) bin = chunk
        }
        return json?.let { Glb(it, bin) }
    }

    fun write(g: Glb): ByteArray {
        var jb = g.json.toString().toByteArray(Charsets.UTF_8)
        if (jb.size % 4 != 0) jb += ByteArray(4 - jb.size % 4) { 0x20 }
        var bin = g.bin
        if (bin.size % 4 != 0) bin += ByteArray(4 - bin.size % 4)
        val total = 12 + 8 + jb.size + (if (bin.isNotEmpty()) 8 + bin.size else 0)
        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(0x46546C67); out.putInt(2); out.putInt(total)
        out.putInt(jb.size); out.putInt(0x4E4F534A); out.put(jb)
        if (bin.isNotEmpty()) { out.putInt(bin.size); out.putInt(0x004E4942); out.put(bin) }
        return out.array()
    }

    private fun load(context: Context, uri: String): Glb? =
        runCatching { context.contentResolver.openInputStream(Uri.parse(uri))?.use { it.readBytes() } }.getOrNull()?.let { read(it) }

    private fun imageBytes(g: Glb, img: JSONObject): ByteArray? {
        if (img.has("bufferView")) {
            val v = g.json.getJSONArray("bufferViews").getJSONObject(img.getInt("bufferView"))
            val off = v.optInt("byteOffset", 0); val len = v.getInt("byteLength")
            if (off + len > g.bin.size) return null
            return g.bin.copyOfRange(off, off + len)
        }
        val u = img.optString("uri")
        if (u.startsWith("data:")) return android.util.Base64.decode(u.substringAfter(","), android.util.Base64.DEFAULT)
        return null
    }

    /** Which materials use each image (so you can tell the screen picture from the others). */
    private fun usage(g: Glb): Map<Int, List<String>> {
        val tex = g.json.optJSONArray("textures") ?: JSONArray()
        val mats = g.json.optJSONArray("materials") ?: JSONArray()
        val out = HashMap<Int, MutableList<String>>()
        fun texImage(ti: Int): Int? = tex.optJSONObject(ti)?.let { t -> if (t.has("source")) t.getInt("source") else t.optJSONObject("extensions")?.let { e ->
            e.keys().asSequence().mapNotNull { k -> e.optJSONObject(k)?.optInt("source", -1)?.takeIf { it >= 0 } }.firstOrNull() } }
        for (i in 0 until mats.length()) {
            val m = mats.getJSONObject(i); val name = m.optString("name", "Material $i")
            val slots = ArrayList<Pair<String, Int>>()
            m.optJSONObject("pbrMetallicRoughness")?.let { p ->
                p.optJSONObject("baseColorTexture")?.let { slots.add("colour" to it.getInt("index")) }
                p.optJSONObject("metallicRoughnessTexture")?.let { slots.add("metal" to it.getInt("index")) }
            }
            m.optJSONObject("emissiveTexture")?.let { slots.add("glow" to it.getInt("index")) }
            m.optJSONObject("normalTexture")?.let { slots.add("normal" to it.getInt("index")) }
            m.optJSONObject("occlusionTexture")?.let { slots.add("shadow" to it.getInt("index")) }
            m.optJSONObject("extensions")?.optJSONObject("KHR_materials_pbrSpecularGlossiness")?.optJSONObject("diffuseTexture")?.let { slots.add("colour" to it.getInt("index")) }
            for ((slot, ti) in slots) texImage(ti)?.let { out.getOrPut(it) { ArrayList() }.add("$name ($slot)") }
        }
        return out
    }

    fun images(context: Context, uri: String, thumb: Int = 220): List<Image> {
        val g = load(context, uri) ?: return emptyList()
        val imgs = g.json.optJSONArray("images") ?: return emptyList()
        val use = usage(g)
        val out = ArrayList<Image>()
        for (i in 0 until imgs.length()) {
            val img = imgs.getJSONObject(i)
            val bytes = imageBytes(g, img)
            var w = 0; var h = 0; var bmp: Bitmap? = null
            if (bytes != null) {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                w = o.outWidth; h = o.outHeight
                var s = 1; while (maxOf(w, h) / (s * 2) >= thumb) s *= 2
                bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
            }
            out.add(Image(i, img.optString("name", "").ifBlank { "Image ${i + 1}" }, img.optString("mimeType", "image/png"), w, h, bytes?.size ?: 0, bmp, use[i].orEmpty()))
        }
        return out
    }

    fun info(context: Context, uri: String): Info? {
        val g = load(context, uri) ?: return null
        fun names(key: String, def: String) = (g.json.optJSONArray(key) ?: JSONArray()).let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("name", "").ifBlank { "$def ${it + 1}" } } }
        var tris = 0L
        val acc = g.json.optJSONArray("accessors") ?: JSONArray()
        val meshes = g.json.optJSONArray("meshes") ?: JSONArray()
        for (i in 0 until meshes.length()) {
            val prims = meshes.getJSONObject(i).optJSONArray("primitives") ?: continue
            for (p in 0 until prims.length()) {
                val pr = prims.getJSONObject(p)
                val c = if (pr.has("indices")) acc.optJSONObject(pr.getInt("indices"))?.optLong("count", 0) ?: 0
                else acc.optJSONObject(pr.getJSONObject("attributes").optInt("POSITION", 0))?.optLong("count", 0) ?: 0
                tris += c / 3
            }
        }
        return Info(names("meshes", "Mesh"), names("nodes", "Node"), names("materials", "Material"),
            (g.json.optJSONArray("images") ?: JSONArray()).length(), tris, (g.json.optJSONArray("animations") ?: JSONArray()).length())
    }

    /**
     * Builds a copy of [srcUri] with some pictures swapped ([replacements]: image index → picture uri).
     * The new picture is resized to the old one's size so it lines up the same way on the model.
     */
    fun build(context: Context, srcUri: String, replacements: Map<Int, String>, outFile: File): Boolean {
        val g = load(context, srcUri) ?: return false
        val imgs = g.json.optJSONArray("images") ?: return false
        val views = g.json.optJSONArray("bufferViews") ?: JSONArray()
        val newData = HashMap<Int, ByteArray>()   // bufferView index -> new bytes
        for ((idx, picUri) in replacements) {
            val img = imgs.optJSONObject(idx) ?: continue
            val old = imageBytes(g, img)
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            if (old != null) BitmapFactory.decodeByteArray(old, 0, old.size, o)
            val tw = if (o.outWidth > 0) o.outWidth else 1024; val th = if (o.outHeight > 0) o.outHeight else 1024
            val src = MediaUtils.loadBitmap(context, Uri.parse(picUri), maxOf(tw, th)) ?: continue
            val scaled = Bitmap.createScaledBitmap(src, tw, th, true)
            val jpeg = img.optString("mimeType").contains("jpeg") || img.optString("mimeType").contains("jpg")
            val bos = ByteArrayOutputStream()
            scaled.compress(if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, 92, bos)
            val data = bos.toByteArray()
            img.put("mimeType", if (jpeg) "image/jpeg" else "image/png")
            if (img.has("bufferView")) newData[img.getInt("bufferView")] = data
            else { img.put("uri", "data:${if (jpeg) "image/jpeg" else "image/png"};base64," + android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)) }
        }
        // re-pack the binary chunk with the new picture bytes
        val bin = ByteArrayOutputStream()
        for (i in 0 until views.length()) {
            val v = views.getJSONObject(i)
            if (v.optInt("buffer", 0) != 0) continue
            while (bin.size() % 4 != 0) bin.write(0)
            val off = v.optInt("byteOffset", 0); val len = v.getInt("byteLength")
            val data = newData[i] ?: g.bin.copyOfRange(off.coerceAtMost(g.bin.size), (off + len).coerceAtMost(g.bin.size))
            v.put("byteOffset", bin.size()); v.put("byteLength", data.size)
            bin.write(data)
        }
        while (bin.size() % 4 != 0) bin.write(0)
        g.json.optJSONArray("buffers")?.optJSONObject(0)?.put("byteLength", bin.size())
        outFile.parentFile?.mkdirs()
        outFile.writeBytes(write(Glb(g.json, bin.toByteArray())))
        return true
    }
}

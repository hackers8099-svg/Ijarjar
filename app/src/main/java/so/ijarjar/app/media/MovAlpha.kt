package so.ijarjar.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Transparent QuickTime (.mov) files from After Effects / Premiere.
 * Android's own decoders drop the alpha channel, so the two codecs After Effects uses for
 * "RGB + Alpha" that can be decoded in software are read here:
 *  - Animation (QuickTime RLE, 32-bit)
 *  - PNG
 * Every frame is turned into a transparent PNG, and the layer plays them like a PNG sequence.
 */
object MovAlpha {

    sealed class Result {
        class Ok(val frames: List<String>, val fps: Float, val w: Int, val h: Int) : Result()
        /** A codec this decoder can't read; [hasAlpha] = the file probably has transparency (ProRes 4444 etc.). */
        class Other(val codec: String, val hasAlpha: Boolean) : Result()
        object Failed : Result()
    }

    private class Track(var codec: String = "", var depth: Int = 0, var w: Int = 0, var h: Int = 0, var timescale: Long = 0,
                        val chunkOffsets: ArrayList<Long> = ArrayList(), val stsc: ArrayList<IntArray> = ArrayList(),
                        var uniformSize: Int = 0, val sizes: ArrayList<Int> = ArrayList(),
                        var sampleCount: Int = 0, var totalDuration: Long = 0, var isVideo: Boolean = false)

    private val containers = setOf("moov", "trak", "mdia", "minf", "stbl", "edts")

    private class Reader(val ch: FileChannel) {
        fun read(pos: Long, n: Int): ByteBuffer {
            val b = ByteBuffer.allocate(n)
            var p = pos
            while (b.hasRemaining()) { val r = ch.read(b, p); if (r <= 0) break; p += r }
            b.flip(); return b
        }
    }

    private fun fourcc(b: ByteBuffer): String { val a = ByteArray(4); b.get(a); return String(a, Charsets.ISO_8859_1) }

    private fun walk(r: Reader, start: Long, end: Long, tracks: ArrayList<Track>, cur: Track?) {
        var pos = start
        var track = cur
        while (pos + 8 <= end) {
            val h = r.read(pos, 16)
            if (h.remaining() < 8) return
            var size = h.int.toLong() and 0xFFFFFFFFL
            val type = fourcc(h)
            var hdr = 8
            if (size == 1L) { if (h.remaining() < 8) return; size = h.long; hdr = 16 } else if (size == 0L) size = end - pos
            if (size < hdr) return
            val body = pos + hdr; val bodyEnd = minOf(pos + size, end)
            when {
                type == "trak" -> { val t = Track(); tracks.add(t); walk(r, body, bodyEnd, tracks, t) }
                type in containers -> walk(r, body, bodyEnd, tracks, track)
                track != null -> parseLeaf(r, type, body, (bodyEnd - body).toInt().coerceAtMost(64 * 1024 * 1024), track)
            }
            pos += size
        }
    }

    private fun parseLeaf(r: Reader, type: String, pos: Long, len: Int, t: Track) {
        when (type) {
            "hdlr" -> { val b = r.read(pos, 12); b.position(8); if (fourcc(b) == "vide") t.isVideo = true }
            "mdhd" -> {
                val b = r.read(pos, 32); val v = b.get().toInt(); b.position(4)
                if (v == 1) { b.long; b.long; t.timescale = b.int.toLong() and 0xFFFFFFFFL } else { b.int; b.int; t.timescale = b.int.toLong() and 0xFFFFFFFFL }
            }
            "stsd" -> {
                val b = r.read(pos, minOf(len, 200)); b.position(8)
                if (b.remaining() < 8 + 78) return
                b.int; t.codec = fourcc(b)
                b.position(b.position() + 6 + 2 + 16)
                t.w = b.short.toInt() and 0xFFFF; t.h = b.short.toInt() and 0xFFFF
                b.position(b.position() + 4 + 4 + 4 + 2 + 32)
                t.depth = b.short.toInt()
            }
            "stts" -> {
                val b = r.read(pos, len); b.position(4); val n = b.int
                var cnt = 0; var dur = 0L
                repeat(n) { if (b.remaining() >= 8) { val c = b.int; val d = b.int.toLong() and 0xFFFFFFFFL; cnt += c; dur += c * d } }
                t.sampleCount = cnt; t.totalDuration = dur
            }
            "stsc" -> {
                val b = r.read(pos, len); b.position(4); val n = b.int
                repeat(n) { if (b.remaining() >= 12) t.stsc.add(intArrayOf(b.int, b.int, b.int)) }
            }
            "stsz" -> {
                val b = r.read(pos, len); b.position(4); t.uniformSize = b.int; val n = b.int
                if (t.uniformSize == 0) repeat(n) { if (b.remaining() >= 4) t.sizes.add(b.int) }
                else t.sampleCount = maxOf(t.sampleCount, n)
            }
            "stco" -> { val b = r.read(pos, len); b.position(4); val n = b.int; repeat(n) { if (b.remaining() >= 4) t.chunkOffsets.add(b.int.toLong() and 0xFFFFFFFFL) } }
            "co64" -> { val b = r.read(pos, len); b.position(4); val n = b.int; repeat(n) { if (b.remaining() >= 8) t.chunkOffsets.add(b.long) } }
        }
    }

    /** (offset, size) of every sample. */
    private fun samples(t: Track): List<Pair<Long, Int>> {
        val n = if (t.uniformSize == 0) t.sizes.size else t.sampleCount
        val out = ArrayList<Pair<Long, Int>>(n)
        var s = 0
        for (ci in t.chunkOffsets.indices) {
            val chunk = ci + 1
            var per = 1
            for (e in t.stsc) if (e[0] <= chunk) per = e[1] else break
            var off = t.chunkOffsets[ci]
            repeat(per) {
                if (s >= n) return out
                val sz = if (t.uniformSize == 0) t.sizes[s] else t.uniformSize
                out.add(Pair(off, sz)); off += sz; s++
            }
        }
        return out
    }

    private val alphaCodecs = setOf("ap4h", "ap4x", "hvc1", "hev1", "rle ", "png ", "raw ", "tga ", "BGRA", "b64a", "dxa3", "Hap5", "HapA", "HapY")

    /** Decodes a transparent MOV into PNG frames (in app storage). */
    fun extract(context: Context, uri: Uri, maxDim: Int = 1280, maxFrames: Int = 3000, progress: (Int) -> Unit): Result {
        val pfd = runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull() ?: return Result.Failed
        pfd.use {
            FileInputStream(it.fileDescriptor).use { fis ->
                val ch = fis.channel
                val r = Reader(ch)
                val tracks = ArrayList<Track>()
                runCatching { walk(r, 0, ch.size(), tracks, null) }
                val t = tracks.firstOrNull { tr -> tr.isVideo && tr.chunkOffsets.isNotEmpty() } ?: return Result.Failed
                val codec = t.codec
                val decodable = codec == "png " || (codec == "rle " && t.depth == 32)
                if (!decodable) return Result.Other(codec.trim(), codec in alphaCodecs && !(codec == "rle " && t.depth != 32))
                val list = samples(t)
                if (list.isEmpty() || t.w <= 0 || t.h <= 0) return Result.Failed
                val fps = if (t.totalDuration > 0 && t.timescale > 0) (t.sampleCount * t.timescale.toFloat() / t.totalDuration).coerceIn(1f, 120f) else 25f

                val dir = File(context.filesDir, "movalpha/" + Integer.toHexString((uri.toString() + ch.size()).hashCode()))
                val done = File(dir, "done.txt")
                val count = minOf(list.size, maxFrames)
                if (done.exists()) {
                    val files = (0 until count).map { i -> File(dir, "f%05d.png".format(i)) }
                    if (files.all { f -> f.exists() }) return Result.Ok(files.map { f -> Uri.fromFile(f).toString() }, fps, t.w, t.h)
                }
                dir.deleteRecursively(); dir.mkdirs()
                val s = minOf(1f, maxDim.toFloat() / maxOf(t.w, t.h))
                val ow = (t.w * s).toInt().coerceAtLeast(1); val oh = (t.h * s).toInt().coerceAtLeast(1)
                val canvas = IntArray(t.w * t.h)
                val frame = Bitmap.createBitmap(t.w, t.h, Bitmap.Config.ARGB_8888)
                val out = ArrayList<String>(count)
                for (i in 0 until count) {
                    val (off, sz) = list[i]
                    val data = r.read(off, sz).array()
                    val bmp: Bitmap? = if (codec == "png ") {
                        BitmapFactory.decodeByteArray(data, 0, data.size)
                    } else {
                        decodeRle32(data, canvas, t.w, t.h)
                        frame.setPixels(canvas, 0, t.w, 0, 0, t.w, t.h); frame
                    }
                    val f = File(dir, "f%05d.png".format(i))
                    if (bmp != null) {
                        val o = if (bmp.width != ow || bmp.height != oh) Bitmap.createScaledBitmap(bmp, ow, oh, true) else bmp
                        FileOutputStream(f).use { os -> o.compress(Bitmap.CompressFormat.PNG, 100, os) }
                        if (o !== bmp && o !== frame) o.recycle()
                        if (bmp !== frame) bmp.recycle()
                    } else if (i > 0) {
                        File(dir, "f%05d.png".format(i - 1)).copyTo(f, true)
                    } else return Result.Failed
                    out.add(Uri.fromFile(f).toString())
                    if (i % 4 == 0) progress(i * 100 / count)
                }
                done.writeText("$count")
                return Result.Ok(out, fps, t.w, t.h)
            }
        }
    }

    /** QuickTime Animation (RLE) 32-bit ARGB; frames only store the lines / pixels that changed. */
    private fun decodeRle32(d: ByteArray, px: IntArray, w: Int, h: Int) {
        if (d.size < 8) return
        var p = 4
        fun u8(): Int = if (p < d.size) d[p++].toInt() and 0xFF else { p++; 0xFF }
        fun be16(): Int = (u8() shl 8) or u8()
        val header = be16()
        var line = 0; var lines = h
        if (header and 0x0008 != 0) {
            line = be16(); p += 2; lines = be16(); p += 2
            if (line >= h) return
            lines = minOf(lines, h - line)
        }
        while (lines-- > 0 && p < d.size) {
            val rowStart = line * w
            var x = u8() - 1
            while (p < d.size) {
                val code = d[p++].toInt()
                if (code == -1) break
                if (code == 0) { x += u8() - 1 }
                else if (code < 0) {
                    val argb = (u8() shl 24) or (u8() shl 16) or (u8() shl 8) or u8()
                    repeat(-code) { if (x in 0 until w) px[rowStart + x] = argb; x++ }
                } else {
                    repeat(code) {
                        val argb = (u8() shl 24) or (u8() shl 16) or (u8() shl 8) or u8()
                        if (x in 0 until w) px[rowStart + x] = argb; x++
                    }
                }
            }
            line++
            if (line >= h) break
        }
    }
}

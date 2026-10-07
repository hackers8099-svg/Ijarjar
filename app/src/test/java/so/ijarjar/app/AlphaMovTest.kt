package so.ijarjar.app

import org.junit.Test
import so.ijarjar.app.media.AlphaMovWriter
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

/** Writes a small transparent MOV so CI can check it with ffprobe / ffmpeg. */
class AlphaMovTest {

    /** Tiny RGBA PNG encoder (no Android / AWT in unit tests). */
    private fun png(w: Int, h: Int, px: (Int, Int) -> Int): ByteArray {
        val raw = ByteArrayOutputStream()
        for (y in 0 until h) {
            raw.write(0)
            for (x in 0 until w) { val c = px(x, y); raw.write(c shr 16 and 255); raw.write(c shr 8 and 255); raw.write(c and 255); raw.write(c ushr 24) }
        }
        val z = ByteArrayOutputStream(); DeflaterOutputStream(z).use { it.write(raw.toByteArray()) }
        val out = ByteArrayOutputStream(); val d = DataOutputStream(out)
        d.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        fun chunk(type: String, data: ByteArray) {
            d.writeInt(data.size); val t = type.toByteArray(); d.write(t); d.write(data)
            val crc = CRC32(); crc.update(t); crc.update(data); d.writeInt(crc.value.toInt())
        }
        val ihdr = ByteArrayOutputStream(); DataOutputStream(ihdr).apply { writeInt(w); writeInt(h); write(8); write(6); write(0); write(0); write(0) }
        chunk("IHDR", ihdr.toByteArray()); chunk("IDAT", z.toByteArray()); chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }

    @Test
    fun writeAlphaMov() {
        val dir = File(System.getProperty("user.dir"), "build/alpha").apply { mkdirs() }
        val w = AlphaMovWriter(File(dir, "test.mov"), 160, 120, 30)
        for (i in 0 until 30) {
            if (i % 10 == 5) { w.repeatFrame(); continue }
            w.addFrame(png(160, 120) { x, y -> val dx = x - (20 + i * 4); val dy = y - 60; if (dx * dx + dy * dy < 400) 0xA0FFC800.toInt() else 0 })
        }
        w.close()
    }
}

package so.ijarjar.app.media

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * Writes a transparent QuickTime movie (.mov, "PNG" codec, RGB + alpha) — the format After Effects,
 * Premiere, Final Cut and CapCut desktop read as a video with see-through background.
 * Frames go straight to the file; identical frames point to the same data, so still parts cost nothing.
 * Plain JVM code (no Android) so it can be tested on the computer.
 */
class AlphaMovWriter(private val file: File, private val width: Int, private val height: Int, private val fps: Int = 30) {

    private val raf = RandomAccessFile(file, "rw")
    private val offsets = ArrayList<Long>()
    private val sizes = ArrayList<Int>()
    private val mdatStart: Long

    init {
        raf.setLength(0)
        // ftyp: QuickTime
        raf.write(atom("ftyp", bytes { writeAscii("qt  "); writeInt(0x20050300); writeAscii("qt  ") }))
        // mdat with a 64-bit size, filled in at the end
        mdatStart = raf.filePointer
        raf.writeInt(1); raf.write("mdat".toByteArray(Charsets.US_ASCII)); raf.writeLong(0)
    }

    val frameCount get() = offsets.size

    /** Adds one frame (PNG bytes). */
    fun addFrame(png: ByteArray) {
        offsets.add(raf.filePointer); sizes.add(png.size)
        raf.write(png)
    }

    /** Shows the previous frame again (no new data). */
    fun repeatFrame() {
        if (offsets.isEmpty()) return
        offsets.add(offsets.last()); sizes.add(sizes.last())
    }

    fun close() {
        val end = raf.filePointer
        raf.seek(mdatStart + 8); raf.writeLong(end - mdatStart)
        raf.seek(end)
        raf.write(moov())
        raf.close()
    }

    /** Stops and deletes the file. */
    fun abort() { runCatching { raf.close() }; file.delete() }

    // ------------------------------------------------------------------ atoms

    private val timescale = fps * 100
    private val frameDur = 100
    private fun duration() = offsets.size.toLong() * frameDur

    private fun moov(): ByteArray = atom("moov", mvhd() + trak())

    private fun matrix(o: DataOutputStream) {
        o.writeInt(0x00010000); o.writeInt(0); o.writeInt(0)
        o.writeInt(0); o.writeInt(0x00010000); o.writeInt(0)
        o.writeInt(0); o.writeInt(0); o.writeInt(0x40000000)
    }

    private fun mvhd() = atom("mvhd", bytes {
        writeInt(0)                    // version + flags
        writeInt(0); writeInt(0)       // created, modified
        writeInt(timescale); writeInt(duration().toInt())
        writeInt(0x00010000)           // rate 1.0
        writeShort(0x0100)             // volume 1.0
        write(ByteArray(10))
        matrix(this)
        repeat(6) { writeInt(0) }      // preview, poster, selection, current time
        writeInt(2)                    // next track id
    })

    private fun trak() = atom("trak", tkhd() + atom("mdia", mdhd() + hdlr("mhlr", "vide", "VideoHandler") + minf()))

    private fun tkhd() = atom("tkhd", bytes {
        writeInt(0x0000000F)           // enabled, in movie, in preview, in poster
        writeInt(0); writeInt(0)
        writeInt(1); writeInt(0)       // track id, reserved
        writeInt(duration().toInt())
        writeInt(0); writeInt(0)
        writeShort(0); writeShort(0)   // layer, alternate group
        writeShort(0); writeShort(0)   // volume, reserved
        matrix(this)
        writeInt(width shl 16); writeInt(height shl 16)
    })

    private fun mdhd() = atom("mdhd", bytes {
        writeInt(0); writeInt(0); writeInt(0)
        writeInt(timescale); writeInt(duration().toInt())
        writeShort(0); writeShort(0)   // language, quality
    })

    private fun hdlr(type: String, sub: String, name: String) = atom("hdlr", bytes {
        writeInt(0)
        writeAscii(type); writeAscii(sub)
        writeInt(0); writeInt(0); writeInt(0)   // manufacturer, flags, mask
        writeByte(name.length); writeAscii(name)  // Pascal string
    })

    private fun minf() = atom("minf",
        atom("vmhd", bytes { writeInt(0x00000001); writeShort(0x0040); writeShort(0x8000); writeShort(0x8000); writeShort(0x8000) }) +
            hdlr("dhlr", "alis", "DataHandler") +
            atom("dinf", atom("dref", bytes { writeInt(0); writeInt(1); write(atom("alis", bytes { writeInt(1) })) })) +
            stbl())

    private fun stbl(): ByteArray {
        val stsd = atom("stsd", bytes {
            writeInt(0); writeInt(1)
            write(atom("png ", bytes {
                write(ByteArray(6)); writeShort(1)        // reserved, data reference index
                writeShort(0); writeShort(0)              // version, revision
                writeAscii("appl")
                writeInt(0); writeInt(0x200)              // temporal / spatial quality
                writeShort(width); writeShort(height)
                writeInt(72 shl 16); writeInt(72 shl 16)  // resolution
                writeInt(0); writeShort(1)                // data size, frames per sample
                val name = "PNG"
                writeByte(name.length); writeAscii(name); write(ByteArray(31 - name.length))
                writeShort(32)                            // depth: 32 = millions of colours + alpha
                writeShort(-1)                            // no colour table
            }))
        })
        val stts = atom("stts", bytes { writeInt(0); writeInt(1); writeInt(offsets.size); writeInt(frameDur) })
        val stsc = atom("stsc", bytes { writeInt(0); writeInt(1); writeInt(1); writeInt(1); writeInt(1) })
        val stsz = atom("stsz", bytes { writeInt(0); writeInt(0); writeInt(sizes.size); sizes.forEach { writeInt(it) } })
        val co64 = atom("co64", bytes { writeInt(0); writeInt(offsets.size); offsets.forEach { writeLong(it) } })
        return atom("stbl", stsd + stts + stsc + stsz + co64)
    }

    private fun atom(type: String, body: ByteArray): ByteArray = bytes { writeInt(body.size + 8); writeAscii(type); write(body) }

    private inline fun bytes(f: DataOutputStream.() -> Unit): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).f()
        return bos.toByteArray()
    }

    private fun DataOutputStream.writeAscii(s: String) = write(s.toByteArray(Charsets.US_ASCII))
}

package so.ijarjar.app

import org.junit.Test
import so.ijarjar.app.media.AlphaMovWriter
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/** Writes a small transparent MOV so CI can check it with ffprobe / ffmpeg. */
class AlphaMovTest {
    @Test
    fun writeAlphaMov() {
        val dir = File(System.getProperty("user.dir"), "build/alpha").apply { mkdirs() }
        val w = AlphaMovWriter(File(dir, "test.mov"), 320, 240, 30)
        for (i in 0 until 30) {
            if (i % 10 == 5) { w.repeatFrame(); continue }
            val img = BufferedImage(320, 240, BufferedImage.TYPE_INT_ARGB)
            val g = img.createGraphics()
            g.color = Color(255, 200, 0, 160)
            g.fillOval(10 + i * 8, 60, 80, 80)
            g.dispose()
            val bos = ByteArrayOutputStream()
            ImageIO.write(img, "png", bos)
            w.addFrame(bos.toByteArray())
        }
        w.close()
    }
}

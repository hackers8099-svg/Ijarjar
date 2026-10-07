package so.ijarjar.app

import org.junit.Test
import so.ijarjar.app.render.PhoneGlb
import java.io.File

/** Writes the generated phones so CI can check them with the official glTF validator. */
class PhoneGlbTest {
    @Test
    fun writePhones() {
        val dir = File(System.getProperty("user.dir"), "build/phones").apply { mkdirs() }
        for (s in PhoneGlb.Style.entries) File(dir, "${s.name}.glb").writeBytes(PhoneGlb.build(s))
    }
}

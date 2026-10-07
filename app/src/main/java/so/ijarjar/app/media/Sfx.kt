package so.ijarjar.app.media

import android.content.Context
import so.ijarjar.app.L
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** Built-in sound effects, synthesised on the phone the first time they are used. */
object Sfx {

    class Sound(val id: String, val so: String, val en: String, val gen: () -> FloatArray) {
        val label: String get() = L.t(so, en)
    }

    private const val RATE = 44100
    private fun n(sec: Double) = (RATE * sec).toInt()
    private fun env(i: Int, len: Int, attack: Double = 0.005, decay: Double = 8.0): Double {
        val t = i.toDouble() / RATE
        val a = if (t < attack) t / attack else 1.0
        return a * exp(-decay * t * (1.0 / (len.toDouble() / RATE)).coerceAtMost(10.0) / 4.0)
    }

    private fun tone(sec: Double, f0: Double, f1: Double = f0, decay: Double = 6.0, wave: Int = 0, vol: Double = 0.6): FloatArray {
        val len = n(sec)
        var ph = 0.0
        return FloatArray(len) { i ->
            val p = i.toDouble() / len
            val f = f0 * (f1 / f0).pow(p)
            ph += 2 * PI * f / RATE
            val s = when (wave) {
                1 -> if (sin(ph) >= 0) 1.0 else -1.0
                2 -> 2.0 * ((ph / (2 * PI)) % 1.0) - 1.0
                else -> sin(ph)
            }
            (s * vol * exp(-decay * p) * (if (i < 200) i / 200.0 else 1.0)).toFloat()
        }
    }

    private fun noise(sec: Double, decay: Double = 6.0, vol: Double = 0.5, lowpass: Double = 1.0, seed: Int = 1): FloatArray {
        val len = n(sec)
        val r = Random(seed)
        var y = 0.0
        return FloatArray(len) { i ->
            val p = i.toDouble() / len
            y += (r.nextDouble() * 2 - 1 - y) * lowpass
            (y * vol * exp(-decay * p)).toFloat()
        }
    }

    private fun mix(vararg parts: Pair<Double, FloatArray>): FloatArray {
        val len = parts.maxOf { n(it.first) + it.second.size }
        val out = FloatArray(len)
        for ((at, a) in parts) { val o = n(at); for (i in a.indices) out[o + i] += a[i] }
        return out
    }

    private fun swell(sec: Double, f0: Double, f1: Double): FloatArray {
        val len = n(sec); val r = Random(7); var ph = 0.0; var y = 0.0
        return FloatArray(len) { i ->
            val p = i.toDouble() / len
            val f = f0 * (f1 / f0).pow(p)
            ph += 2 * PI * f / RATE
            y += (r.nextDouble() * 2 - 1 - y) * (0.05 + 0.4 * p)
            ((sin(ph) * 0.3 + y * 0.6) * p * p * (if (p > 0.95) (1 - p) * 20 else 1.0)).toFloat()
        }
    }

    val all: List<Sound> = listOf(
        Sound("pop", "Bood", "Pop") { tone(0.12, 900.0, 200.0, 12.0) },
        Sound("click", "Guji", "Click") { noise(0.03, 20.0, 0.8, 0.9) },
        Sound("ding", "Ding", "Ding") { mix(0.0 to tone(1.2, 1318.0, 1318.0, 4.0), 0.0 to tone(1.2, 2637.0, 2637.0, 6.0, vol = 0.2)) },
        Sound("bell", "Gambaleel", "Bell") { mix(0.0 to tone(2.0, 880.0, 880.0, 3.0), 0.0 to tone(2.0, 1760.0 * 1.19, 1760.0 * 1.19, 4.0, vol = 0.25)) },
        Sound("whoosh", "Wuush", "Whoosh") { noise(0.6, 0.0, 0.7, 0.15).also { a -> for (i in a.indices) { val p = i.toDouble() / a.size; a[i] = (a[i] * sin(PI * p)).toFloat() } } },
        Sound("swoosh", "Fuud", "Swoosh") { swell(0.45, 300.0, 2400.0) },
        Sound("boom", "Qarax", "Boom") { mix(0.0 to tone(1.4, 80.0, 30.0, 3.5, vol = 0.9), 0.0 to noise(1.2, 4.0, 0.6, 0.05)) },
        Sound("kick", "Durbaan", "Kick") { tone(0.35, 150.0, 45.0, 7.0, vol = 0.9) },
        Sound("snare", "Snare", "Snare") { mix(0.0 to noise(0.25, 12.0, 0.6, 0.7), 0.0 to tone(0.15, 220.0, 180.0, 14.0, vol = 0.4)) },
        Sound("clap", "Sacab", "Clap") { mix(0.0 to noise(0.04, 30.0, 0.6, 0.6, 2), 0.012 to noise(0.04, 30.0, 0.6, 0.6, 3), 0.024 to noise(0.2, 10.0, 0.6, 0.6, 4)) },
        Sound("hihat", "Hi-hat", "Hi-hat") { noise(0.08, 25.0, 0.4, 1.0) },
        Sound("beep", "Biib", "Beep") { tone(0.25, 1000.0, 1000.0, 0.5, 1, 0.3) },
        Sound("notify", "Ogeysiis", "Notification") { mix(0.0 to tone(0.18, 988.0, 988.0, 5.0), 0.12 to tone(0.4, 1319.0, 1319.0, 5.0)) },
        Sound("coin", "Lacag", "Coin") { mix(0.0 to tone(0.08, 988.0, 988.0, 1.0, 1, 0.25), 0.08 to tone(0.45, 1319.0, 1319.0, 5.0, 1, 0.25)) },
        Sound("laser", "Leysar", "Laser") { tone(0.3, 2400.0, 200.0, 4.0, 2, 0.4) },
        Sound("jump", "Bood kor", "Jump") { tone(0.25, 300.0, 900.0, 3.0, 1, 0.3) },
        Sound("drop", "Dhac", "Drop") { tone(0.5, 1200.0, 120.0, 3.0) },
        Sound("riser", "Kor u kac", "Riser") { swell(2.0, 200.0, 1600.0) },
        Sound("glitch", "Glitch", "Glitch") {
            val len = n(0.5); val r = Random(5); var hold = 0.0; var k = 0
            FloatArray(len) { if (k-- <= 0) { hold = r.nextDouble() * 2 - 1; k = r.nextInt(20, 400) }; (hold * 0.5).toFloat() }
        },
        Sound("typing", "Qorid", "Typing") { mix(*(0 until 8).map { (it * 0.09 + (it % 3) * 0.01) to noise(0.03, 25.0, 0.6, 0.9, it + 10) }.toTypedArray()) },
        Sound("shutter", "Kamarad", "Camera") { mix(0.0 to noise(0.05, 20.0, 0.7, 0.8, 21), 0.08 to noise(0.07, 15.0, 0.6, 0.6, 22)) },
        Sound("heartbeat", "Garaac", "Heartbeat") { mix(0.0 to tone(0.18, 60.0, 45.0, 6.0, vol = 1.0), 0.22 to tone(0.18, 55.0, 40.0, 6.0, vol = 0.8)) },
        Sound("magic", "Sixir", "Magic") { mix(*(0 until 6).map { (it * 0.07) to tone(0.6, 1046.0 * 2.0.pow(it / 4.0), 1046.0 * 2.0.pow(it / 4.0), 5.0, vol = 0.25) }.toTypedArray()) },
        Sound("success", "Guul", "Success") { mix(0.0 to tone(0.3, 523.0, 523.0, 3.0), 0.12 to tone(0.3, 659.0, 659.0, 3.0), 0.24 to tone(0.6, 784.0, 784.0, 3.0)) },
        Sound("fail", "Guuldarro", "Fail") { mix(0.0 to tone(0.3, 392.0, 392.0, 2.0, 2, 0.3), 0.3 to tone(0.3, 370.0, 370.0, 2.0, 2, 0.3), 0.6 to tone(0.8, 349.0, 330.0, 2.0, 2, 0.3)) },
        Sound("tick", "Tik-tak", "Tick tock") { mix(*(0 until 4).map { (it * 0.5) to tone(0.05, if (it % 2 == 0) 2000.0 else 1500.0, 1000.0, 20.0, vol = 0.5) }.toTypedArray()) },
        Sound("buzzer", "Dhawaq", "Buzzer") { tone(0.6, 110.0, 110.0, 0.5, 2, 0.4) },
        Sound("drumroll", "Durbaan dheer", "Drum roll") { mix(*(0 until 30).map { (it * 0.05) to noise(0.06, 15.0, 0.3 + it * 0.015, 0.6, it + 40) }.toTypedArray()) },
        Sound("vinyl", "Joog", "Record scratch") { tone(0.4, 600.0, 120.0, 2.0, 2, 0.35) },
        Sound("bubble", "Xumbo", "Bubble") { tone(0.1, 400.0, 1400.0, 6.0) }
    )

    /** Returns (generating if needed) the WAV file for a sound. */
    fun file(context: Context, s: Sound): File {
        val dir = File(context.filesDir, "sfx").apply { mkdirs() }
        val f = File(dir, "${s.id}.wav")
        if (f.exists() && f.length() > 44) return f
        val data = s.gen()
        // normalise softly
        val peak = data.maxOfOrNull { kotlin.math.abs(it) }?.coerceAtLeast(0.001f) ?: 1f
        val g = if (peak > 0.95f) 0.95f / peak else 1f
        val bytes = ByteBuffer.allocate(44 + data.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()); bytes.putInt(36 + data.size * 2); bytes.put("WAVE".toByteArray())
        bytes.put("fmt ".toByteArray()); bytes.putInt(16); bytes.putShort(1); bytes.putShort(1)
        bytes.putInt(RATE); bytes.putInt(RATE * 2); bytes.putShort(2); bytes.putShort(16)
        bytes.put("data".toByteArray()); bytes.putInt(data.size * 2)
        for (v in data) bytes.putShort((v * g * 32767f).toInt().coerceIn(-32768, 32767).toShort())
        FileOutputStream(f).use { it.write(bytes.array()) }
        return f
    }

    fun durationMs(s: File): Long = ((s.length() - 44) / 2 * 1000 / RATE)

    @Suppress("unused")
    private fun unused() = env(0, 1)
}

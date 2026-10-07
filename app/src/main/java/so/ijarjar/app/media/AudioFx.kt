package so.ijarjar.app.media

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Voice clean-up: background-noise reduction (rumble/hiss filters + noise gate) and
 * "enhance" (presence boost + compressor + loudness). Used in export and in the preview.
 * [settings] returns (noise reduction 0..1, enhance on/off) and is read for every buffer.
 */
@UnstableApi
class AudioFx(private val settings: () -> Pair<Float, Boolean>) : BaseAudioProcessor() {

    private class Biquad {
        var b0 = 1.0; var b1 = 0.0; var b2 = 0.0; var a1 = 0.0; var a2 = 0.0
        private val z1 = DoubleArray(8); private val z2 = DoubleArray(8)
        fun run(x: Double, ch: Int): Double {
            val y = b0 * x + z1[ch]
            z1[ch] = b1 * x - a1 * y + z2[ch]
            z2[ch] = b2 * x - a2 * y
            return y
        }
        fun reset() { z1.fill(0.0); z2.fill(0.0) }

        fun highPass(fs: Double, f: Double, q: Double = 0.707) {
            val w = 2 * PI * f / fs; val al = sin(w) / (2 * q); val c = cos(w); val a0 = 1 + al
            b0 = (1 + c) / 2 / a0; b1 = -(1 + c) / a0; b2 = (1 + c) / 2 / a0; a1 = -2 * c / a0; a2 = (1 - al) / a0
        }
        fun lowPass(fs: Double, f: Double, q: Double = 0.707) {
            val w = 2 * PI * f / fs; val al = sin(w) / (2 * q); val c = cos(w); val a0 = 1 + al
            b0 = (1 - c) / 2 / a0; b1 = (1 - c) / a0; b2 = (1 - c) / 2 / a0; a1 = -2 * c / a0; a2 = (1 - al) / a0
        }
        fun peak(fs: Double, f: Double, gainDb: Double, q: Double = 1.0) {
            val a = 10.0.pow(gainDb / 40); val w = 2 * PI * f / fs; val al = sin(w) / (2 * q); val c = cos(w); val a0 = 1 + al / a
            b0 = (1 + al * a) / a0; b1 = -2 * c / a0; b2 = (1 - al * a) / a0; a1 = -2 * c / a0; a2 = (1 - al / a) / a0
        }
    }

    private val hp = Biquad(); private val lp = Biquad(); private val presence = Biquad()
    private var gateEnv = 0.0
    private var gateGain = 1.0
    private var compEnv = 0.0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // only 16-bit PCM is processed; anything else passes straight through
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET
        val fs = inputAudioFormat.sampleRate.toDouble()
        hp.highPass(fs, 90.0)
        lp.lowPass(fs, minOf(9000.0, fs * 0.45))
        presence.peak(fs, 3000.0, 4.0)
        return inputAudioFormat
    }

    override fun isActive(): Boolean = super.isActive()

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)
        val (noise, enhance) = settings()
        val ch = inputAudioFormat.channelCount.coerceAtLeast(1)
        val fs = inputAudioFormat.sampleRate.toDouble().coerceAtLeast(8000.0)
        val src = inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        out.order(ByteOrder.LITTLE_ENDIAN)
        if (noise <= 0f && !enhance) {
            out.put(src)
            out.flip()
            return
        }
        val frames = size / (2 * ch)
        val attack = 1.0 - kotlin.math.exp(-1.0 / (0.005 * fs))
        val release = 1.0 - kotlin.math.exp(-1.0 / (0.12 * fs))
        val threshold = 0.004 + noise * 0.04          // gate threshold (full scale = 1)
        val floor = (1.0 - noise * 0.9).coerceIn(0.05, 1.0)
        val sample = DoubleArray(ch)
        for (f in 0 until frames) {
            var peak = 0.0
            for (c in 0 until ch) {
                var x = src.getShort().toDouble() / 32768.0
                x = hp.run(x, c)
                if (noise > 0.3f) x = lp.run(x, c)
                if (enhance) x = presence.run(x, c)
                sample[c] = x
                peak = maxOf(peak, abs(x))
            }
            // noise gate / downward expander
            gateEnv += (peak - gateEnv) * (if (peak > gateEnv) attack else release)
            val target = if (noise <= 0f || gateEnv > threshold) 1.0 else floor + (1 - floor) * (gateEnv / threshold).pow(2)
            gateGain += (target - gateGain) * (if (target > gateGain) 0.02 else 0.002)
            // compressor + make-up gain for a clearer, louder voice
            var g = gateGain
            if (enhance) {
                compEnv += (peak - compEnv) * (if (peak > compEnv) attack else release)
                val thr = 0.2
                val comp = if (compEnv > thr) (thr + (compEnv - thr) / 3.0) / compEnv else 1.0
                g *= comp * 1.7
            }
            for (c in 0 until ch) {
                var y = sample[c] * g
                if (y > 0.98) y = 0.98 + (y - 0.98) * 0.1 else if (y < -0.98) y = -0.98 + (y + 0.98) * 0.1
                out.putShort((y.coerceIn(-1.0, 1.0) * 32767).toInt().toShort())
            }
        }
        // any odd leftover bytes
        while (src.hasRemaining()) out.put(src.get())
        out.flip()
    }

    override fun onFlush() { hp.reset(); lp.reset(); presence.reset(); gateEnv = 0.0; gateGain = 1.0; compEnv = 0.0 }

    @Suppress("unused")
    private fun rms(a: DoubleArray) = sqrt(a.sumOf { it * it } / a.size.coerceAtLeast(1))
}

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
class AudioFx(private val settings: () -> Pair<Float, Boolean>,
              private val voice: () -> so.ijarjar.app.model.VoiceFx = { so.ijarjar.app.model.VoiceFx.NONE },
              private val room: () -> so.ijarjar.app.model.SoundFx? = { null }) : BaseAudioProcessor() {
    // custom echo + room
    private var echoBuf = Array(2) { FloatArray(1) }
    private var ePos = 0
    private val combs = Array(2) { Array(4) { FloatArray(1) } }
    private val combPos = IntArray(4)
    private val combLen = IntArray(4)
    private val curLen = IntArray(4) { 8 }
    private val combLp = Array(2) { FloatArray(4) }

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
    // voice changer
    private val vHp = Biquad(); private val vLp = Biquad(); private val vMid = Biquad()
    private var delay = Array(2) { FloatArray(1) }
    private var dPos = 0
    private var phase = 0.0
    private var vMode = -1
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
        delay = Array(inputAudioFormat.channelCount.coerceIn(1, 8)) { FloatArray((fs * 0.6).toInt()) }
        val chs = inputAudioFormat.channelCount.coerceIn(1, 8)
        echoBuf = Array(chs) { FloatArray((fs * 1.3).toInt()) }; ePos = 0
        val base = intArrayOf(1116, 1188, 1277, 1356)   // Freeverb comb lengths at 44.1 kHz
        for (i in 0 until 4) combLen[i] = (base[i] * fs / 44100.0 * 2.2).toInt()
        for (c in combs.indices) combs[c] = Array(4) { FloatArray(combLen[it] + 1) }
        combPos.fill(0)
        dPos = 0; vMode = -1
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
        val mode = voice().mode
        val rm = room()?.takeIf { it.on }
        if (rm != null) for (k in 0 until 4) {
            curLen[k] = (combLen[k] * (0.45 + 0.55 * rm.roomSize.coerceIn(0f, 1f))).toInt().coerceIn(8, combLen[k])
            if (combPos[k] >= curLen[k]) combPos[k] = 0
        }
        if (mode != vMode) setupVoice(mode, fs)
        if (noise <= 0f && !enhance && mode == 0 && rm == null) {
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
            if (mode != 0) {
                for (c in 0 until ch) sample[c] = voiceSample(mode, sample[c], c, fs)
                dPos++; if (dPos >= delay[0].size) dPos = 0
                phase += 1.0 / fs
            }
            if (rm != null) {
                for (c in 0 until ch) sample[c] = roomSample(rm, sample[c], c, fs)
                ePos++; if (ePos >= echoBuf[0].size) ePos = 0
                for (k in 0 until 4) { combPos[k]++; if (combPos[k] >= curLen[k]) combPos[k] = 0 }
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

    /** Custom echo (delay + feedback) and a small room reverb (4 damped combs). */
    private fun roomSample(r: so.ijarjar.app.model.SoundFx, x: Double, c: Int, fs: Double): Double {
        var y = x
        if (r.echoMix > 0.001f) {
            val b = echoBuf[c.coerceAtMost(echoBuf.size - 1)]
            val dl = (r.echoMs.coerceIn(30f, 1200f) / 1000.0 * fs).toInt().coerceIn(1, b.size - 1)
            var i = ePos - dl; if (i < 0) i += b.size
            val d = b[i].toDouble()
            b[ePos] = (x + d * r.echoFb.coerceIn(0f, 0.92f)).toFloat()
            y += d * r.echoMix
        }
        if (r.roomMix > 0.001f) {
            val cc = c.coerceAtMost(1)
            val fb = 0.7 + 0.27 * r.roomSize.coerceIn(0f, 1f)
            var wet = 0.0
            for (k in 0 until 4) {
                val buf = combs[cc][k]
                val i = combPos[k].coerceIn(0, buf.size - 1)
                val out = buf[i].toDouble()
                combLp[cc][k] = (out * 0.7 + combLp[cc][k] * 0.3).toFloat()
                buf[i] = (x + combLp[cc][k] * fb).toFloat()
                wet += out
            }
            y = y * (1 - r.roomMix * 0.4) + wet * 0.25 * r.roomMix
        }
        return y
    }

    private fun setupVoice(mode: Int, fs: Double) {
        vMode = mode
        vHp.reset(); vLp.reset(); vMid.reset()
        for (d in delay) d.fill(0f)
        when (mode) {
            4 -> { vHp.highPass(fs, 350.0); vLp.lowPass(fs, 3800.0); vMid.peak(fs, 1500.0, 5.0) }   // radio
            5 -> { vHp.highPass(fs, 400.0); vLp.lowPass(fs, 3000.0); vMid.peak(fs, 1800.0, 6.0) }   // telephone
            8 -> { vHp.highPass(fs, 600.0); vLp.lowPass(fs, 4500.0); vMid.peak(fs, 2200.0, 8.0) }   // megaphone
            6 -> { vHp.highPass(fs, 40.0); vLp.lowPass(fs, 2500.0); vMid.peak(fs, 180.0, 6.0) }     // monster
            else -> { vHp.highPass(fs, 20.0); vLp.lowPass(fs, minOf(16000.0, fs * 0.45)); vMid.peak(fs, 1000.0, 0.0) }
        }
    }

    private fun tap(c: Int, sec: Double, fs: Double): Double {
        val d = delay[c.coerceAtMost(delay.size - 1)]
        var i = dPos - (sec * fs).toInt()
        while (i < 0) i += d.size
        return d[i % d.size].toDouble()
    }

    private fun voiceSample(mode: Int, x: Double, c: Int, fs: Double): Double {
        val d = delay[c.coerceAtMost(delay.size - 1)]
        val y: Double = when (mode) {
            1 -> { // robot: ring modulation + short metallic comb
                val r = x * sin(2 * PI * 55.0 * phase) * 1.6
                val o = r + tap(c, 0.012, fs) * 0.45
                d[dPos] = o.toFloat(); o
            }
            7 -> { // alien: fast ring modulation mixed with the voice
                val o = x * 0.55 + x * sin(2 * PI * 420.0 * phase) * 0.6
                d[dPos] = x.toFloat(); o
            }
            2 -> { // echo
                val o = x + tap(c, 0.28, fs) * 0.45
                d[dPos] = o.toFloat(); o
            }
            3 -> { // cave: a few feedback taps = simple reverb
                val o = x + (tap(c, 0.037, fs) * 0.32 + tap(c, 0.071, fs) * 0.27 + tap(c, 0.113, fs) * 0.22 + tap(c, 0.161, fs) * 0.18)
                d[dPos] = (o * 0.8).toFloat(); x * 0.7 + o * 0.5
            }
            4, 5, 8 -> { // band-limited + drive
                var o = vMid.run(vLp.run(vHp.run(x, c), c), c)
                val drive = if (mode == 8) 4.0 else if (mode == 5) 3.0 else 2.0
                o = kotlin.math.tanh(o * drive) / kotlin.math.tanh(drive) * 0.8
                d[dPos] = 0f; o
            }
            6 -> { // monster: dark + growl
                var o = vMid.run(vLp.run(x, c), c)
                o = kotlin.math.tanh(o * 2.2) * 0.75 + x * sin(2 * PI * 28.0 * phase) * 0.25
                d[dPos] = 0f; o
            }
            else -> x
        }
        return y
    }

    override fun onFlush() { hp.reset(); lp.reset(); presence.reset(); gateEnv = 0.0; gateGain = 1.0; compEnv = 0.0 }

    @Suppress("unused")
    private fun rms(a: DoubleArray) = sqrt(a.sumOf { it * it } / a.size.coerceAtLeast(1))
}

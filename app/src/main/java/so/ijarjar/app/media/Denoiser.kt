package so.ijarjar.app.media

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Spectral noise reduction (one channel): the steady background sound (fan, wind, hum, hiss) is
 * learnt from the quiet moments and removed from every frequency, while speech stays.
 * Short-time FFT with 50 % overlap; delay is half a frame (~11 ms).
 */
class Denoiser(sampleRate: Int) {
    private val n = if (sampleRate > 32000) 1024 else 512
    private val hop = n / 2
    private val latency = n - hop
    private val win = FloatArray(n) { sqrt(0.5f - 0.5f * cos(2 * PI * it / n).toFloat()) }   // sqrt-Hann (analysis + synthesis)
    private val inFifo = FloatArray(n)
    private val outFifo = FloatArray(n)
    private val accum = FloatArray(n * 2)
    private var rover = latency
    private val re = FloatArray(n); private val im = FloatArray(n)
    private val bins = n / 2 + 1
    private val smooth = FloatArray(bins)
    private val noise = FloatArray(bins)
    private val gainPrev = FloatArray(bins) { 1f }
    private var frames = 0

    /** amount 0..1 */
    fun process(x: Float, amount: Float): Float {
        inFifo[rover] = x
        val y = outFifo[rover - latency]
        rover++
        if (rover >= n) {
            rover = latency
            frame(amount)
        }
        return y
    }

    private fun frame(amount: Float) {
        for (i in 0 until n) { re[i] = inFifo[i] * win[i]; im[i] = 0f }
        fft(re, im, false)
        val over = 1.2f + 3.3f * amount          // how hard noise is subtracted
        val floor = 0.22f - 0.17f * amount       // keep a little so it never sounds "underwater"
        for (k in 0 until bins) {
            val p = re[k] * re[k] + im[k] * im[k]
            smooth[k] = if (frames == 0) p else 0.75f * smooth[k] + 0.25f * p
            // noise floor: follows quiet moments fast, rises slowly (minimum tracking)
            noise[k] = when {
                frames < 8 -> if (frames == 0) smooth[k] else min(noise[k], smooth[k]) * 0.6f + smooth[k] * 0.4f
                smooth[k] < noise[k] -> smooth[k]
                else -> noise[k] * 1.004f + 1e-12f
            }
            var g = 1f - over * noise[k] / max(p, 1e-12f)
            g = max(g, floor)
            // smooth over time against "musical" noise
            g = if (g < gainPrev[k]) 0.55f * g + 0.45f * gainPrev[k] else 0.8f * g + 0.2f * gainPrev[k]
            gainPrev[k] = g
            re[k] *= g; im[k] *= g
            if (k in 1 until n / 2) { re[n - k] = re[k]; im[n - k] = -im[k] }
        }
        frames++
        fft(re, im, true)
        for (i in 0 until n) accum[i] += re[i] * win[i]
        for (i in 0 until hop) outFifo[i] = accum[i]
        System.arraycopy(accum, hop, accum, 0, n)
        for (i in n until n + hop) accum[i] = 0f
        System.arraycopy(inFifo, hop, inFifo, 0, latency)
    }

    private fun fft(re: FloatArray, im: FloatArray, inverse: Boolean) {
        val size = re.size
        var j = 0
        for (i in 1 until size) {
            var bit = size shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= size) {
            val ang = 2 * PI / len * (if (inverse) 1 else -1)
            val wr = cos(ang).toFloat(); val wi = sin(ang).toFloat()
            var i = 0
            while (i < size) {
                var cr = 1f; var ci = 0f
                for (k in 0 until len / 2) {
                    val ar = re[i + k]; val ai = im[i + k]
                    val br = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val bi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ar + br; im[i + k] = ai + bi
                    re[i + k + len / 2] = ar - br; im[i + k + len / 2] = ai - bi
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) { val inv = 1f / size; for (i in 0 until size) { re[i] = re[i] * inv; im[i] = im[i] * inv } }
    }
}

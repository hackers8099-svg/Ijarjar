package so.ijarjar.app.media

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Fade in / fade out over a piece of audio (used when exporting). */
@UnstableApi
class FadeProcessor(private val inMs: Long, private val outMs: Long, private val lengthMs: Long) : BaseAudioProcessor() {
    private var frames = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size).order(ByteOrder.LITTLE_ENDIAN)
        val src = inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val ch = inputAudioFormat.channelCount.coerceAtLeast(1)
        val rate = inputAudioFormat.sampleRate.coerceAtLeast(1)
        while (src.remaining() >= 2 * ch) {
            val ms = frames * 1000 / rate
            var g = 1f
            if (inMs > 0) g = minOf(g, (ms.toFloat() / inMs).coerceIn(0f, 1f))
            if (outMs > 0) g = minOf(g, ((lengthMs - ms).toFloat() / outMs).coerceIn(0f, 1f))
            for (c in 0 until ch) out.putShort((src.getShort() * g).toInt().toShort())
            frames++
        }
        while (src.hasRemaining()) out.put(src.get())
        out.flip()
    }

    override fun onFlush() { frames = 0 }
}

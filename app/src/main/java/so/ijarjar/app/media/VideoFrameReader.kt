package so.ijarjar.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri

/**
 * Reads frames of a video one after another, fast (for export). Asking a frame retriever for each
 * frame decodes from the last key frame every time — with phone screen recordings that can take
 * seconds per frame and makes export look stuck. Here the decoder just keeps going forward.
 */
class VideoFrameReader(context: Context, uri: Uri, private val maxSide: Int) {

    private val ex = MediaExtractor()
    private val codec: MediaCodec
    private val rotation: Int
    private var lastPtsUs = -1L
    private var last: Bitmap? = null
    private var inputDone = false
    private var outputDone = false
    val durationUs: Long

    init {
        ex.setDataSource(context, uri, null)
        var track = -1
        for (i in 0 until ex.trackCount) if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) { track = i; break }
        require(track >= 0) { "no video track" }
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        durationUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
        rotation = runCatching {
            val r = MediaMetadataRetriever(); try { r.setDataSource(context, uri); r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0 } finally { r.release() }
        }.getOrDefault(0)
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()
    }

    /** The frame showing at [ms] (from the start of the file). */
    fun frameAt(ms: Long): Bitmap? {
        val target = ms * 1000
        // going back, or far ahead: jump to the key frame before it
        if (target < lastPtsUs - 1000 || target > lastPtsUs + 3_000_000 || lastPtsUs < 0 && target > 0) {
            ex.seekTo(target, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            codec.flush(); inputDone = false; outputDone = false
        } else if (target <= lastPtsUs && last != null) return last
        val info = MediaCodec.BufferInfo()
        var guard = 0
        while (!outputDone && guard++ < 4000) {
            if (!inputDone) {
                val ii = codec.dequeueInputBuffer(2000)
                if (ii >= 0) {
                    val buf = codec.getInputBuffer(ii)!!
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                    else { codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance() }
                }
            }
            val oi = codec.dequeueOutputBuffer(info, 2000)
            if (oi >= 0) {
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                val pts = info.presentationTimeUs
                // convert only the frame we need (or the last one before the end)
                val need = pts + 17_000 >= target || outputDone
                if (need && info.size > 0) {
                    val img = codec.getOutputImage(oi)
                    if (img != null) { last = toBitmap(img) ?: last; img.close() }
                }
                codec.releaseOutputBuffer(oi, false)
                lastPtsUs = pts
                if (need) return last
            }
        }
        return last
    }

    /** YUV → picture, skipping pixels so it is no bigger than [maxSide]. */
    private fun toBitmap(img: android.media.Image): Bitmap? {
        val w = img.width; val h = img.height
        val step = maxOf(1, (maxOf(w, h) + maxSide - 1) / maxSide)
        val ow = w / step; val oh = h / step
        if (ow < 2 || oh < 2) return null
        val yP = img.planes[0]; val uP = img.planes[1]; val vP = img.planes[2]
        val yB = yP.buffer; val uB = uP.buffer; val vB = vP.buffer
        val yRow = yP.rowStride; val yPix = yP.pixelStride
        val uRow = uP.rowStride; val uPix = uP.pixelStride
        val vRow = vP.rowStride; val vPix = vP.pixelStride
        val px = IntArray(ow * oh)
        var o = 0
        for (oy in 0 until oh) {
            val y = oy * step
            for (ox in 0 until ow) {
                val x = ox * step
                val yy = (yB.get(y * yRow + x * yPix).toInt() and 255) - 16
                val cy = y / 2; val cx = x / 2
                val u = (uB.get(cy * uRow + cx * uPix).toInt() and 255) - 128
                val v = (vB.get(cy * vRow + cx * vPix).toInt() and 255) - 128
                val c = 1192 * yy.coerceAtLeast(0)
                val r = ((c + 1634 * v) shr 10).coerceIn(0, 255)
                val g = ((c - 833 * v - 400 * u) shr 10).coerceIn(0, 255)
                val b = ((c + 2066 * u) shr 10).coerceIn(0, 255)
                px[o++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        val bmp = Bitmap.createBitmap(px, ow, oh, Bitmap.Config.ARGB_8888)
        // the crop area of the decoder (some phones pad the picture)
        val crop = img.cropRect
        val cut = if (crop != null && (crop.width() < w || crop.height() < h))
            Bitmap.createBitmap(bmp, (crop.left / step).coerceIn(0, ow - 1), (crop.top / step).coerceIn(0, oh - 1),
                (crop.width() / step).coerceIn(1, ow - crop.left / step), (crop.height() / step).coerceIn(1, oh - crop.top / step)) else bmp
        return if (rotation % 360 != 0) Bitmap.createBitmap(cut, 0, 0, cut.width, cut.height, Matrix().apply { postRotate(rotation.toFloat()) }, true) else cut
    }

    fun release() {
        runCatching { codec.stop() }; runCatching { codec.release() }; runCatching { ex.release() }
    }
}

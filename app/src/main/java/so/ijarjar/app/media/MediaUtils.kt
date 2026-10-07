package so.ijarjar.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.LruCache

class MediaInfo(
    val isVideo: Boolean,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val hasAudio: Boolean
)

object MediaUtils {

    fun probe(context: Context, uri: Uri): MediaInfo? {
        val type = context.contentResolver.getType(uri) ?: ""
        val isImage = type.startsWith("image")
        return if (isImage) {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            var w = opts.outWidth
            var h = opts.outHeight
            if (Build.VERSION.SDK_INT >= 28) {
                // ImageDecoder applies EXIF rotation, so take the shape from a small decode.
                loadBitmap(context, uri, 256)?.let { small ->
                    val portraitDecoded = small.height > small.width
                    val portraitRaw = h > w
                    if (portraitDecoded != portraitRaw) { val t = w; w = h; h = t }
                }
            }
            MediaInfo(false, 3000, w.coerceAtLeast(1), h.coerceAtLeast(1), false)
        } else {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(context, uri)
                val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
                val hasVideo = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
                val hasAudio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
                MediaInfo(hasVideo, dur, w.coerceAtLeast(1), h.coerceAtLeast(1), hasAudio)
            } catch (e: Exception) {
                null
            } finally {
                runCatching { r.release() }
            }
        }
    }

    fun displayName(context: Context, uri: Uri): String {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: "Fayl"
    }

    private val bitmapCache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun loadBitmapCached(context: Context, uri: Uri, maxDim: Int): Bitmap? {
        val key = "$uri@$maxDim"
        bitmapCache.get(key)?.let { return it }
        val b = loadBitmap(context, uri, maxDim) ?: return null
        bitmapCache.put(key, b)
        return b
    }

    fun loadBitmap(context: Context, uri: Uri, maxDim: Int): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= 28) {
                val src = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                    val w = info.size.width
                    val h = info.size.height
                    val s = maxOf(w, h)
                    if (s > maxDim) {
                        val f = maxDim.toFloat() / s
                        decoder.setTargetSize((w * f).toInt().coerceAtLeast(1), (h * f).toInt().coerceAtLeast(1))
                    }
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                var sample = 1
                while (maxOf(opts.outWidth, opts.outHeight) / (sample * 2) >= maxDim) sample *= 2
                val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) }
            }
        } catch (e: Exception) {
            null
        }
    }

    private val thumbCache = LruCache<String, Bitmap>(120)

    /** Video frame thumbnail; for images returns a scaled bitmap. Call off the main thread. */
    fun thumbnail(context: Context, uri: Uri, isVideo: Boolean, timeMs: Long, size: Int): Bitmap? {
        val key = "$uri#${timeMs / 500}#$size"
        thumbCache.get(key)?.let { return it }
        val bmp = if (!isVideo) loadBitmap(context, uri, size) else {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(context, uri)
                val frame = if (Build.VERSION.SDK_INT >= 27)
                    r.getScaledFrameAtTime(timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, size, size)
                else r.getFrameAtTime(timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                frame
            } catch (e: Exception) {
                null
            } finally {
                runCatching { r.release() }
            }
        }
        if (bmp != null) thumbCache.put(key, bmp)
        return bmp
    }
}

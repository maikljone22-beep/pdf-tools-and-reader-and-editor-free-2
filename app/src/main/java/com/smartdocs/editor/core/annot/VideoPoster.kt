package com.smartdocs.editor.core.annot

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import java.io.ByteArrayOutputStream
import java.io.File

/** Poster frame and size of a video file (MediaMetadataRetriever). Call off the main thread. */
object VideoPoster {

    /** Display size (rotation applied) and duration of a video; null when the file cannot be read. */
    data class Meta(val width: Int, val height: Int, val durationMs: Long)

    fun meta(file: File): Meta? = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (rot % 180 != 0) Meta(h, w, d) else Meta(w, h, d)
        } finally { runCatching { r.release() } }
    }.getOrNull()

    /**
     * The first frame that is not (almost) black, scaled to at most [maxPx] on its long side, as JPEG bytes;
     * null when no frame can be decoded (unsupported codec, corrupt file).
     */
    fun makeJpeg(file: File, maxPx: Int = 640): ByteArray? = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val times = listOf(0L, 500L, 1_000L, 2_000L, 4_000L, dur / 10, dur / 4).filter { dur <= 0 || it <= dur }.distinct()
            var fallback: Bitmap? = null
            var chosen: Bitmap? = null
            for (t in times) {
                val f = frame(r, t * 1000, maxPx) ?: continue
                if (!isDark(f)) { chosen = f; break }
                if (fallback == null) fallback = f else f.recycle()
            }
            val bmp = chosen ?: fallback ?: return@runCatching null
            if (chosen != null && fallback != null) fallback.recycle()
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
            bmp.recycle()
            out.toByteArray()
        } finally { runCatching { r.release() } }
    }.getOrNull()

    private fun frame(r: MediaMetadataRetriever, us: Long, maxPx: Int): Bitmap? {
        val f = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, maxPx, maxPx)
            else r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }.getOrNull() ?: return null
        val long = maxOf(f.width, f.height)
        if (long <= maxPx) return opaque(f)
        val k = maxPx.toFloat() / long
        val s = Bitmap.createScaledBitmap(f, (f.width * k).toInt().coerceAtLeast(1), (f.height * k).toInt().coerceAtLeast(1), true)
        if (s !== f) f.recycle()
        return opaque(s)
    }

    /** JPEG has no alpha: make sure the bitmap is ARGB_8888 / RGB_565 so compress works on every device. */
    private fun opaque(b: Bitmap): Bitmap = if (b.config == Bitmap.Config.ARGB_8888 || b.config == Bitmap.Config.RGB_565) b
        else b.copy(Bitmap.Config.ARGB_8888, false).also { if (it !== b) b.recycle() }

    /** Average luminance of a sparse sample below ~6 %: a black / fade-in frame. */
    private fun isDark(b: Bitmap): Boolean {
        var sum = 0L; var n = 0
        val sx = (b.width / 24).coerceAtLeast(1); val sy = (b.height / 24).coerceAtLeast(1)
        var y = 0
        while (y < b.height) {
            var x = 0
            while (x < b.width) {
                val c = b.getPixel(x, y)
                sum += (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
                n++; x += sx
            }
            y += sy
        }
        return n == 0 || sum / n < 16
    }
}

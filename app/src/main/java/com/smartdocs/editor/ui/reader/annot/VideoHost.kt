package com.smartdocs.editor.ui.reader.annot

import android.graphics.RectF
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.smartdocs.editor.R
import com.smartdocs.editor.core.annot.VideoPdf
import com.smartdocs.editor.core.annot.VideoPoster
import com.smartdocs.editor.core.pdf.Annot
import com.smartdocs.editor.core.pdf.AnnotKind
import com.smartdocs.editor.core.pdf.AnnotationWriter
import com.smartdocs.editor.ui.reader.AnnotTarget
import com.smartdocs.editor.ui.reader.PageGeometry
import com.smartdocs.editor.ui.reader.ReaderState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * One video shown on a page: a pending VIDEO annotation ([pendingId], [file]) or one stored in the PDF ([stored]).
 * [rect] is in PDF space (top = larger y).
 */
data class VideoItem(
    val key: String,
    val page: Int,
    val rect: RectF,
    val name: String,
    val mime: String,
    val options: VideoPdf.Options,
    val supported: Boolean,
    /** The page bitmap already shows a picture for it (our poster appearance, or a foreign /AP). */
    val drawnByPage: Boolean,
    val pendingId: Long? = null,
    val file: File? = null,
    val posterPath: String? = null,
    val stored: AnnotationWriter.Existing? = null,
)

/** The video properties dialog's values: player options, title (/T), locked, and a picked poster image (JPEG). */
data class VideoProps(val options: VideoPdf.Options, val title: String, val locked: Boolean, val posterPath: String?)

/** Playback rules shared by the in-page player and the tests. */
object VideoPlayback {
    /** How many times a video plays before it stops (looping: no limit). */
    fun plays(o: VideoPdf.Options): Int = if (o.loop) Int.MAX_VALUE else o.repeat.coerceIn(1, VideoPdf.MAX_REPEAT)
}

/** A picked video waiting for its box on the page (Video tool). [aspect] = width / height. */
data class PickedVideo(val file: File, val name: String, val mime: String, val aspect: Float, val posterPath: String?)

/** A picked video and the tap / rectangle it goes to, waiting for the user's choice: in-page player or icon. */
data class VideoPlacement(val video: PickedVideo, val page: Int, val rect: RectF?, val x: Float, val y: Float)

/**
 * The in-page video player of the reader (`state.video`): which video plays, the single ExoPlayer, extraction of
 * stored videos into the cache, the Video tool's picked file, and the dialogs' state. Main thread only.
 *
 * Only one video plays at a time; [deactivate] releases the player (decoders are freed), [release] also empties
 * the cache (reader closed).
 */
class VideoHost(private val s: ReaderState) {
    var activeKey by mutableStateOf<String?>(null); private set
    var active by mutableStateOf<VideoItem?>(null); private set
    var player by mutableStateOf<ExoPlayer?>(null); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var isPlaying by mutableStateOf(false); private set
    var muted by mutableStateOf(false); private set
    var loop by mutableStateOf(false); private set
    /** Width / height of the video picture (0 = not known yet) and the rotation the TextureView must apply. */
    var aspect by mutableFloatStateOf(0f); private set
    var rotation by mutableIntStateOf(0); private set
    var fullscreen by mutableStateOf(false)
    var controlsVisible by mutableStateOf(true)

    /** Video tool: picked file waiting for a box, a picked file that needs the large-file confirmation. */
    var picked by mutableStateOf<PickedVideo?>(null)
    var largeWarning by mutableStateOf<Pair<Uri, Long>?>(null)
    var importing by mutableStateOf(false)
    /** The "player box or icon?" question after a video was picked and its place tapped. */
    var placement by mutableStateOf<VideoPlacement?>(null)
    /** Options dialog / save launcher of the selected video. */
    var optionsOpen by mutableStateOf(false)
    var launchSave: (name: String, file: File) -> Unit = { _, _ -> }

    private val autoplayed = HashSet<String>()
    /** Times the active video has played (repeat count). */
    private var plays = 1
    private var job: Job? = null

    val cacheDir: File get() = File(s.context.cacheDir, "video_play").apply { mkdirs() }

    init { runCatching { File(s.context.cacheDir, "video_play").listFiles()?.forEach { it.delete() } } }

    private fun str(id: Int, vararg a: Any) = s.context.getString(id, *a)

    // =============== items of a page ===============

    /** Every video of [page]: pending ones (drawn on top) and stored ones not taken out for editing. */
    fun items(page: Int): List<VideoItem> {
        val out = ArrayList<VideoItem>()
        s.existing.forEach { e ->
            val v = e.video ?: return@forEach
            if (e.page != page) return@forEach
            out += VideoItem(
                key = "s:${e.page}:${e.index}:${e.subtype}", page = e.page, rect = PageGeometry.normPdf(e.rect),
                name = v.name, mime = v.mime, options = v.options, supported = v.supported, drawnByPage = v.hasAppearance, stored = e,
            )
        }
        s.annots.forEach { a ->
            if (a.page != page || a.kind != AnnotKind.VIDEO) return@forEach
            val r = a.rects.firstOrNull() ?: return@forEach
            out += VideoItem(
                key = "p:${a.id}", page = a.page, rect = PageGeometry.normPdf(r), name = a.text, mime = a.mime,
                options = a.video ?: VideoPdf.Options(), supported = true, drawnByPage = false,
                pendingId = a.id, file = a.filePath?.let(::File), posterPath = a.posterPath,
            )
        }
        return out
    }

    // =============== playback ===============

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
        override fun onPlayerError(e: PlaybackException) {
            error = str(R.string.vid_error) + " (" + e.errorCodeName + ")"
            isPlaying = false
        }
        override fun onVideoSizeChanged(v: VideoSize) {
            if (v.width <= 0 || v.height <= 0) return
            val rot = @Suppress("DEPRECATION") v.unappliedRotationDegrees
            val a = v.width * v.pixelWidthHeightRatio / v.height
            rotation = rot
            aspect = if (rot % 180 != 0) 1f / a else a
        }
        override fun onPlaybackStateChanged(state: Int) {
            if (state != Player.STATE_ENDED) return
            // FINAL_CHECK_2 #15: play N times, then keep the player open or show the poster again
            val o = active?.options
            if (o != null && !loop && plays < VideoPlayback.plays(o)) { plays++; player?.seekTo(0); player?.play(); return }
            if (o != null && !o.keepOpen) { s.scope.launch { deactivate() }; return }
            controlsVisible = true
        }
    }

    /** Start [item] in its box (stops any other video first). [auto]: started by "autoplay when visible". */
    fun activate(item: VideoItem, auto: Boolean = false) {
        if (!item.supported) { s.annot2.message = str(if (item.mime.contains("flash")) R.string.vid_unsupported_flash else R.string.vid_no_file); return }
        if (activeKey == item.key && player != null) { player?.play(); return }
        deactivate()
        if (auto) autoplayed += item.key
        activeKey = item.key; active = item
        error = null; loading = true; aspect = 0f; rotation = 0
        muted = item.options.muted; loop = item.options.loop; plays = 1
        controlsVisible = true
        // an icon has no box to play in: it opens the pop-up (full-screen) player
        if (item.options.fullscreen || item.options.icon) fullscreen = true
        val key = item.key
        job = s.scope.launch {
            try {
                // renditions: the first clip whose criteria this device meets (main clip, then the alternates)
                val idx = chooseClip(item)
                val local = if (idx == 0) item.file else item.options.alternates.getOrNull(idx - 1)?.path?.takeIf { it.isNotBlank() }?.let(::File)
                val f = local?.takeIf { it.isFile } ?: extract(item, idx)
                    ?: throw java.io.FileNotFoundException(str(R.string.vid_no_file))
                if (activeKey != key) return@launch
                val p = withContext(Dispatchers.Main) {
                    ExoPlayer.Builder(s.context.applicationContext).build().apply {
                        addListener(listener)
                        setMediaItem(MediaItem.fromUri(Uri.fromFile(f)))
                        repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                        volume = item.options.gain
                        playWhenReady = true
                        prepare()
                    }
                }
                if (activeKey != key) { p.release(); return@launch }
                player = p
            } catch (e: CancellationException) { throw e
            } catch (e: Throwable) {
                if (activeKey == key) error = e.message ?: str(R.string.vid_error)
            } finally { if (activeKey == key) loading = false }
        }
    }

    /** Stop the playing video and free the player (the poster shows again). */
    fun deactivate() {
        job?.cancel(); job = null
        player?.let { p -> runCatching { p.removeListener(listener); p.release() } }
        player = null
        activeKey = null; active = null
        loading = false; error = null; isPlaying = false; fullscreen = false
    }

    fun isActive(item: VideoItem) = activeKey == item.key

    /** Leave the pop-up player: an icon video stops (it has no box to play in), a box video goes on in its box. */
    fun exitFullscreen() { if (active?.options?.icon == true) deactivate() else fullscreen = false }

    fun message(id: Int) { s.annot2.message = str(id) }

    fun togglePlay() {
        val p = player ?: return
        if (p.isPlaying) p.pause()
        else { if (p.playbackState == Player.STATE_ENDED) p.seekTo(0); p.play() }
    }

    fun seekTo(ms: Long) { player?.seekTo(ms.coerceAtLeast(0)) }

    fun toggleMute() { muted = !muted; player?.volume = if (muted) 0f else (active?.options?.copy(muted = false)?.gain ?: 1f) }

    fun toggleLoop() { loop = !loop; player?.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF }

    /** App went to the background. */
    fun pause() { player?.pause() }

    /** Reader closed for good: free the player and the extracted copies. */
    fun release() {
        deactivate(); autoplayed.clear()
        runCatching { File(s.context.cacheDir, "video_play").listFiles()?.forEach { it.delete() } }
    }

    /**
     * Page visibility of [item] changed: autoplay it once per appearance (option "autoplay"), stop it when it leaves
     * the screen (not while it is in full screen).
     */
    fun onVisibility(item: VideoItem, visible: Boolean) {
        if (!visible) {
            autoplayed -= item.key
            if (activeKey == item.key && !fullscreen) deactivate()
            return
        }
        if (item.options.autoplay && item.supported && activeKey == null && item.key !in autoplayed && s.tool == null && s.edit.target == null)
            activate(item, auto = true)
    }

    // =============== stored videos ===============

    /** Stream the stored video of [item] out of the PDF into the cache (reused while the file is unchanged). */
    /** Index of the clip to play: 0 the main clip, k the k-th alternate ([VideoPdf.select] on this device). */
    private fun chooseClip(item: VideoItem): Int {
        val o = item.options
        if (o.alternates.isEmpty()) return 0
        val dm = s.context.resources.displayMetrics
        val bandwidth = runCatching {
            val cm = s.context.getSystemService(android.net.ConnectivityManager::class.java)
            cm.getNetworkCapabilities(cm.activeNetwork)?.linkDownstreamBandwidthKbps?.toLong()?.times(1000)
        }.getOrNull() ?: Long.MAX_VALUE
        return VideoPdf.select(listOf(o.criteria) + o.alternates.map { it.criteria }, bandwidth, java.util.Locale.getDefault().toLanguageTag(), dm.widthPixels, dm.heightPixels)
    }

    private suspend fun extract(item: VideoItem, clip: Int = 0): File? {
        val e = item.stored ?: return null
        val src = s.file ?: return null
        val pw = s.password
        return withContext(Dispatchers.IO) {
            val clipName = if (clip == 0) item.name else item.options.alternates.getOrNull(clip - 1)?.name.orEmpty()
            val ext = clipName.substringAfterLast('.', "").takeIf { it.length in 2..5 } ?: "mp4"
            val id = sha1("${src.absolutePath}|${src.length()}|${src.lastModified()}|${e.page}|${e.index}|${e.subtype}|$clip")
            val out = File(cacheDir, "$id.$ext")
            if (out.isFile && out.length() > 0) return@withContext out
            val part = File(cacheDir, "$id.part")
            try {
                com.tom_roush.pdfbox.pdmodel.PDDocument.load(src, pw ?: "", com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly()).use { d ->
                    if (e.page !in 0 until d.numberOfPages) return@withContext null
                    val page = d.getPage(e.page)
                    val r = PageGeometry.normPdf(e.rect)
                    val i = VideoPdf.findIndex(page, e.index, e.subtype, floatArrayOf(r.left, r.bottom, r.right, r.top))
                    val an = VideoPdf.annotDicts(page).getOrNull(i) ?: return@withContext null
                    val n = part.outputStream().buffered(1 shl 16).use { VideoPdf.copyVideo(an, it, clip) }
                    if (n <= 0) { part.delete(); return@withContext null }
                }
                if (!part.renameTo(out)) { part.copyTo(out, overwrite = true); part.delete() }
                // keep the cache small: only the newest few extracted videos
                cacheDir.listFiles()?.filter { it != out && it.extension != "part" }?.sortedByDescending { it.lastModified() }?.drop(2)?.forEach { it.delete() }
                out
            } catch (t: Throwable) { part.delete(); throw t }
        }
    }

    private fun sha1(s: String): String = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    // =============== selection actions ===============

    /** The selected annotation as a video item (edit bar actions). */
    fun selectedItem(): VideoItem? {
        val ed = s.edit
        return when (val t = ed.target) {
            is AnnotTarget.Pending -> items(t.page).firstOrNull { it.pendingId == t.id }
            is AnnotTarget.Stored -> items(t.page).firstOrNull { it.stored == t.e }
            null -> null
        }
    }

    /** Edit bar "play": play the selected video in its box. */
    fun playSelected() {
        val item = selectedItem() ?: return
        s.edit.clear()
        activate(item)
    }

    /** The video file of [item] (pending file, or extracted from the PDF) for share / save. */
    suspend fun fileOf(item: VideoItem): File? = item.file?.takeIf { it.isFile } ?: extract(item)?.let { f ->
        // a readable name for the receiving app
        val named = File(File(s.context.cacheDir, "video_share").apply { mkdirs() }, item.name.replace(Regex("""[\\/:*?"<>|]"""), "_").ifBlank { "video.mp4" })
        withContext(Dispatchers.IO) { f.copyTo(named, overwrite = true) }
    }

    fun shareSelected() {
        val item = selectedItem() ?: return
        s.scope.launch {
            val f = runCatching { fileOf(item) }.getOrNull() ?: run { s.annot2.message = str(R.string.vid_no_file); return@launch }
            MediaIntents.share(s.context, s.app, f, item.mime.ifBlank { "video/*" })
        }
    }

    fun saveSelected() {
        val item = selectedItem() ?: return
        s.scope.launch {
            val f = runCatching { fileOf(item) }.getOrNull() ?: run { s.annot2.message = str(R.string.vid_no_file); return@launch }
            launchSave(item.name.ifBlank { f.name }, f)
        }
    }

    /**
     * Properties dialog result: one undoable edit of the selected video (a stored one becomes pending): options,
     * title (/T), locked, and the poster ([posterPath] a picked image; the automatic frame when the mode is auto).
     */
    fun applyOptions(p: VideoProps) {
        optionsOpen = false
        s.edit.edit { a ->
            val poster = when (p.options.poster) { VideoPdf.POSTER_FILE -> p.posterPath ?: a.posterPath; VideoPdf.POSTER_NONE -> a.posterPath; else -> if (a.video?.poster == VideoPdf.POSTER_FILE) null else a.posterPath }
            val wasIcon = a.video?.icon == true
            val rects = when {
                p.options.icon && !wasIcon -> a.rects.firstOrNull()?.let { r -> val n = PageGeometry.normPdf(r); val (pw, ph) = s.pageSizes[a.page] ?: (595f to 842f); listOf(iconBox(n.left, n.top, pw, ph)) } ?: a.rects
                !p.options.icon && wasIcon -> a.rects.firstOrNull()?.let { r ->
                    val n = PageGeometry.normPdf(r); val (pw, ph) = s.pageSizes[a.page] ?: (595f to 842f)
                    val w = pw * 0.4f; val h = w * 9f / 16f
                    val l = n.left.coerceIn(0f, (pw - w).coerceAtLeast(0f)); val t = n.top.coerceIn(h, ph)
                    listOf(RectF(l, t, l + w, t - h))
                } ?: a.rects
                else -> a.rects
            }
            a.copy(video = p.options, author = p.title, locked = p.locked, posterPath = poster, rects = rects)
        }
    }

    /** The selected video's properties for the dialog. */
    fun selectedProps(): VideoProps {
        val a = s.edit.current()
        return VideoProps(a?.video ?: selectedItem()?.options ?: VideoPdf.Options(), a?.author ?: "", a?.locked ?: false, a?.posterPath)
    }

    // =============== Video tool: picked file → box ===============

    /** A picked video was copied to [file]: read its size / poster (IO), then wait for the box (or use [tap]). */
    fun onPicked(file: File, name: String, tap: Tap2?) {
        importing = true
        s.scope.launch {
            try {
                val (meta, posterPath) = withContext(Dispatchers.IO) {
                    val m = VideoPoster.meta(file)
                    val jp = VideoPoster.makeJpeg(file)
                    val p = jp?.let { b -> File(file.parentFile, file.nameWithoutExtension + "_poster.jpg").also { it.writeBytes(b) }.absolutePath }
                    m to p
                }
                val aspect = if (meta != null && meta.width > 0 && meta.height > 0) meta.width.toFloat() / meta.height else 16f / 9f
                val mime = VideoPdf.videoMime(name).ifBlank { com.smartdocs.editor.core.annot.MediaAnnots.mimeOf(name) }
                val pv = PickedVideo(file, name, mime, aspect, posterPath)
                if (meta == null) s.annot2.message = str(R.string.vid_maybe_unsupported)
                if (tap != null) ask(pv, tap.page, tap.rect, tap.x, tap.y) else { picked = pv; s.annot2.message = str(R.string.vid_tool_hint) }
            } finally { importing = false }
        }
    }

    /** Ask how [pv] goes on the page (in-page player box, or an icon that pops up the player); [place] follows. */
    fun ask(pv: PickedVideo, page: Int, rect: RectF?, x: Float, y: Float) { placement = VideoPlacement(pv, page, rect, x, y) }

    /** The placement dialog's answer. */
    fun placeChosen(icon: Boolean) {
        val p = placement ?: return
        placement = null
        place(p.video, p.page, p.rect, p.x, p.y, icon)
    }

    /**
     * Put [pv] on [page]: in the dragged [rect] (PDF), else a box of 40 % of the page width centred on (x, y).
     * [icon]: a small video icon at (x, y) instead (tap → pop-up player); a dragged rectangle is ignored.
     */
    fun place(pv: PickedVideo, page: Int, rect: RectF?, x: Float, y: Float, icon: Boolean = false) {
        val (pw, ph) = s.pageSizes[page] ?: (595f to 842f)
        val box = if (icon) iconBox(x, y, pw, ph) else rect?.let { PageGeometry.normPdf(it) } ?: run {
            var w = pw * 0.4f; var h = w / pv.aspect.coerceIn(0.2f, 5f)
            if (h > ph * 0.6f) { h = ph * 0.6f; w = h * pv.aspect }
            val l = (x - w / 2).coerceIn(0f, (pw - w).coerceAtLeast(0f)); val t = (y + h / 2).coerceIn(h, ph)
            RectF(l, t, l + w, t - h)
        }
        val a: Annot = s.newAnnot(AnnotKind.VIDEO, page, rects = listOf(box), text = pv.name, color = if (icon) 0x1E6FD9 else 0x000000)
            .copy(filePath = pv.file.absolutePath, mime = pv.mime, video = VideoPdf.Options(icon = icon), posterPath = pv.posterPath, opacity = 1f)
        picked = null
        s.addAnnot(a)
    }

    /** The icon form's square (PDF, top = larger y) centred on (x, y), kept inside the page. */
    private fun iconBox(x: Float, y: Float, pw: Float, ph: Float): RectF {
        val sz = VideoPdf.ICON_SIZE
        val l = (x - sz / 2).coerceIn(0f, (pw - sz).coerceAtLeast(0f)); val t = (y + sz / 2).coerceIn(sz, ph)
        return RectF(l, t, l + sz, t - sz)
    }
}

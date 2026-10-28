package com.smartdocs.editor.core.edit5

import com.smartdocs.editor.core.annot.SoundPdf
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSBoolean
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File

/**
 * fix/links-bookmarks (Foxit §15 "play media / sound" actions for links and bookmarks).
 *
 * Written forms:
 * - Sound action (ISO 32000 §12.6.4.8): `/S /Sound /Sound <stream>` with the WAV samples as PDF sound data
 *   (`/R` rate, `/C` channels, `/B` bits, `/E /Signed` for 16-bit big-endian, `/Raw` for 8-bit unsigned).
 * - Rendition action (§12.6.4.14): `/S /Rendition /R <media rendition>` with the clip embedded (media clip data →
 *   file specification with `/EF`) and a `/JS` that plays it in a floating window. No screen annotation is needed
 *   (`/OP` is left out, so `/AN` is not required).
 *
 * Plain PdfBox (no android.*), unit tested on the JVM.
 */
object NavMedia {
    val SOUND: COSName = COSName.getPDFName("Sound")
    private val R = COSName.getPDFName("R")
    private val C = COSName.getPDFName("C")
    private val B = COSName.getPDFName("B")
    private val E = COSName.getPDFName("E")
    private val D = COSName.getPDFName("D")
    private val P = COSName.getPDFName("P")
    private val CT = COSName.getPDFName("CT")
    private val EF = COSName.getPDFName("EF")
    private val BE = COSName.getPDFName("BE")
    private val SP = COSName.getPDFName("SP")
    private val TF = COSName.getPDFName("TF")
    private val W = COSName.getPDFName("W")

    /** The script of a written Rendition action: play the action's clip in a floating window. */
    const val PLAY_SCRIPT = "app.media.openPlayer({rendition: event.action.rendition, settings: {windowType: app.media.windowType.floating}});"

    private fun deref(b: COSBase?): COSBase? = if (b is COSObject) b.getObject() else b

    /** PCM samples of a WAV file: rate, channels, bits (8 or 16) and the data as stored (little-endian for 16-bit). */
    class Wav(val rate: Int, val channels: Int, val bits: Int, val data: ByteArray)

    /** Parse a PCM WAV file (RIFF / WAVE, format 1, 8 or 16 bits). Null when it is not one. */
    fun parseWav(b: ByteArray): Wav? {
        fun u16(o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        fun u32(o: Int) = u16(o) or (u16(o + 2) shl 16)
        if (b.size < 44 || String(b, 0, 4, Charsets.ISO_8859_1) != "RIFF" || String(b, 8, 4, Charsets.ISO_8859_1) != "WAVE") return null
        var o = 12
        var fmt: IntArray? = null
        while (o + 8 <= b.size) {
            val id = String(b, o, 4, Charsets.ISO_8859_1); val len = u32(o + 4)
            if (len < 0) return null
            val body = o + 8
            when (id) {
                "fmt " -> if (body + 16 <= b.size) fmt = intArrayOf(u16(body), u16(body + 2), u32(body + 4), u16(body + 14))
                "data" -> {
                    val f = fmt ?: return null
                    if (f[0] != 1 || (f[3] != 8 && f[3] != 16) || f[1] !in 1..2 || f[2] <= 0) return null
                    val end = minOf(b.size, body + len)
                    return Wav(f[2], f[1], f[3], b.copyOfRange(body, end))
                }
            }
            o = body + len + (len and 1)
        }
        return null
    }

    /** PDF sound data of [w]: 16-bit samples become big-endian signed, 8-bit stay unsigned (/Raw). */
    fun pdfSamples(w: Wav): ByteArray = if (w.bits == 16) ByteArray(w.data.size and 1.inv()).also { out ->
        var i = 0
        while (i + 1 < w.data.size) { out[i] = w.data[i + 1]; out[i + 1] = w.data[i]; i += 2 }
    } else w.data.copyOf()

    /** Build a Sound or Rendition action dictionary embedding [file] into [doc]. Throws when a sound file is not PCM WAV. */
    fun build(doc: PDDocument, file: File, sound: Boolean, mime: String): COSDictionary {
        val act = COSDictionary().apply { setItem(COSName.TYPE, COSName.getPDFName("Action")) }
        val bytes = file.readBytes()
        if (sound) {
            val w = parseWav(bytes) ?: throw IllegalArgumentException("not a PCM WAV file")
            val st = doc.document.createCOSStream()
            st.setItem(COSName.TYPE, SOUND)
            st.setItem(R, COSFloat(w.rate.toFloat())); st.setInt(C, w.channels); st.setInt(B, w.bits)
            st.setName(E, if (w.bits == 16) "Signed" else "Raw")
            st.createOutputStream().use { it.write(pdfSamples(w)) }
            act.setName(COSName.S, "Sound"); act.setItem(SOUND, st)
            return act
        }
        val ef = doc.document.createCOSStream()
        ef.setItem(COSName.TYPE, COSName.getPDFName("EmbeddedFile"))
        if (mime.isNotBlank()) ef.setName(COSName.SUBTYPE, mime)
        ef.createOutputStream().use { it.write(bytes) }
        ef.setItem(COSName.PARAMS, COSDictionary().apply { setInt(COSName.SIZE, bytes.size) })
        val fs = COSDictionary().apply {
            setItem(COSName.TYPE, COSName.FILESPEC); setString(COSName.F, file.name); setString(COSName.UF, file.name)
            setItem(EF, COSDictionary().apply { setItem(COSName.F, ef); setItem(COSName.UF, ef) })
        }
        val clip = COSDictionary().apply {
            setItem(COSName.TYPE, COSName.getPDFName("MediaClip")); setName(COSName.S, "MCD"); setString(COSName.N, file.name)
            if (mime.isNotBlank()) setString(CT, mime)
            setItem(D, fs)
            setItem(P, COSDictionary().apply { setItem(COSName.TYPE, COSName.getPDFName("MediaPermissions")); setString(TF, "TEMPACCESS") })
        }
        val rendition = COSDictionary().apply {
            setItem(COSName.TYPE, COSName.getPDFName("Rendition")); setName(COSName.S, "MR"); setString(COSName.N, file.name)
            setItem(C, clip)
            setItem(P, COSDictionary().apply { setItem(COSName.TYPE, COSName.getPDFName("MediaPlayParams")); setItem(BE, COSDictionary().apply { setItem(C, COSBoolean.TRUE) }) })
            // play in a floating window (no screen annotation on a page)
            setItem(SP, COSDictionary().apply { setItem(COSName.TYPE, COSName.getPDFName("MediaScreenParams")); setItem(BE, COSDictionary().apply { setItem(W, COSInteger.ZERO) }) })
        }
        act.setName(COSName.S, "Rendition"); act.setItem(R, rendition); act.setString(COSName.JS, PLAY_SCRIPT)
        return act
    }

    /** The media clip data of a Rendition action (a selector rendition: its first choice). */
    private fun clipOf(a: COSDictionary): COSDictionary? {
        var r = deref(a.getDictionaryObject(R)) as? COSDictionary ?: return null
        if (r.getNameAsString(COSName.S) == "SR") r = deref((deref(r.getDictionaryObject(R)) as? COSArray)?.getObject(0)) as? COSDictionary ?: return null
        return deref(r.getDictionaryObject(C)) as? COSDictionary
    }

    /** (name, mime) of the clip of a Rendition action, or null when it has none. */
    fun clipInfo(a: COSDictionary): Pair<String, String>? {
        val clip = clipOf(a) ?: return null
        val fs = deref(clip.getDictionaryObject(D))
        val name = when (fs) {
            is COSDictionary -> fs.getString(COSName.UF) ?: fs.getString(COSName.F)
            is COSString -> fs.string
            else -> null
        } ?: clip.getString(COSName.N) ?: "clip"
        return name to (clip.getString(CT) ?: "")
    }

    /**
     * The playable bytes of a Sound or Rendition action: (file name, mime, bytes). Sound data is decoded to WAV; a
     * clip must be embedded (a clip that only names an outside file or URL gives null).
     */
    fun extract(a: COSDictionary): Triple<String, String, ByteArray>? = when (a.getNameAsString(COSName.S)) {
        "Sound" -> (deref(a.getDictionaryObject(SOUND)) as? COSStream)?.let { Triple("sound.wav", "audio/wav", SoundPdf.soundToWav(it)) }
        "Rendition" -> run {
            val clip = clipOf(a) ?: return@run null
            val fs = deref(clip.getDictionaryObject(D)) as? COSDictionary ?: return@run null
            val ef = deref(fs.getDictionaryObject(EF)) as? COSDictionary ?: return@run null
            val st = deref(ef.getDictionaryObject(COSName.UF) ?: ef.getDictionaryObject(COSName.F)) as? COSStream ?: return@run null
            val (name, mime) = clipInfo(a) ?: return@run null
            Triple(name, mime, st.createInputStream().use { it.readBytes() })
        }
        else -> null
    }
}

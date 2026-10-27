package com.smartdocs.editor.core.annot

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Audio of other PDFs (Foxit 19.x "play audio from other PDFs"): native /Sound annotations (ISO 32000 13.3,
 * sampled data with /R rate, /C channels, /B bits, /E Raw / Signed / muLaw / ALaw) are decoded to a 16-bit PCM
 * WAV the existing player can play; Screen + Rendition annotations with an embedded audio clip are extracted as is.
 */
object SoundPdf {
    private val SOUND = COSName.getPDFName("Sound")
    private val R = COSName.getPDFName("R")
    private val C = COSName.getPDFName("C")
    private val B = COSName.getPDFName("B")
    private val E = COSName.getPDFName("E")
    private val CT = COSName.getPDFName("CT")
    private val D = COSName.getPDFName("D")
    private val EF = COSName.getPDFName("EF")
    private val AA = COSName.getPDFName("AA")

    private fun muLaw(u: Int): Int {
        val v = u.inv() and 0xFF
        val sign = v and 0x80; val exp = (v shr 4) and 7; val man = v and 0x0F
        var s = ((man shl 3) + 0x84) shl exp
        s -= 0x84
        return if (sign != 0) -s else s
    }

    private fun aLaw(a: Int): Int {
        val v = a xor 0x55
        val sign = v and 0x80; val exp = (v shr 4) and 7; val man = v and 0x0F
        var s = if (exp == 0) (man shl 4) + 8 else ((man shl 4) + 0x108) shl (exp - 1)
        if (sign == 0) s = -s
        return s
    }

    /** Decode PDF sound samples to 16-bit little-endian PCM. */
    fun toPcm16(data: ByteArray, bits: Int, encoding: String): ShortArray {
        return when {
            encoding.equals("muLaw", true) -> ShortArray(data.size) { muLaw(data[it].toInt() and 0xFF).coerceIn(-32768, 32767).toShort() }
            encoding.equals("ALaw", true) -> ShortArray(data.size) { aLaw(data[it].toInt() and 0xFF).coerceIn(-32768, 32767).toShort() }
            bits <= 8 -> ShortArray(data.size) { i ->
                val b = data[i].toInt()
                (if (encoding.equals("Signed", true)) b shl 8 else ((b and 0xFF) - 128) shl 8).toShort()
            }
            else -> ShortArray(data.size / 2) { i ->
                // 16-bit PDF samples are big-endian; Raw = unsigned, Signed = two's complement
                val v = ((data[i * 2].toInt() and 0xFF) shl 8) or (data[i * 2 + 1].toInt() and 0xFF)
                (if (encoding.equals("Signed", true)) v.toShort().toInt() else v - 32768).toShort()
            }
        }
    }

    fun wav(pcm: ShortArray, rate: Int, channels: Int): ByteArray {
        val out = ByteArrayOutputStream(44 + pcm.size * 2)
        fun i32(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF); out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF) }
        fun i16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        val dataLen = pcm.size * 2
        out.write("RIFF".toByteArray()); i32(36 + dataLen); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); i32(16); i16(1); i16(channels); i32(rate); i32(rate * channels * 2); i16(channels * 2); i16(16)
        out.write("data".toByteArray()); i32(dataLen)
        pcm.forEach { i16(it.toInt()) }
        return out.toByteArray()
    }

    /** WAV bytes of a /Sound stream. */
    fun soundToWav(s: COSStream): ByteArray {
        val rate = s.getFloat(R, 8000f).toInt().coerceIn(1000, 192000)
        val ch = s.getInt(C, 1).coerceIn(1, 2)
        val bits = s.getInt(B, 8)
        val enc = s.getNameAsString(E) ?: "Raw"
        val data = s.createInputStream().use { it.readBytes() }
        return wav(toPcm16(data, bits, enc), rate, ch)
    }

    private fun dict(b: COSBase?): COSDictionary? = when (b) { is COSDictionary -> b; is com.tom_roush.pdfbox.cos.COSObject -> b.`object` as? COSDictionary; else -> null }

    /** Embedded audio clip (stream, mime, name) of a Screen + Rendition annotation, or null. */
    fun audioClip(cos: COSDictionary): Triple<COSStream, String, String>? {
        val actions = ArrayList<COSDictionary>()
        dict(cos.getDictionaryObject(COSName.A))?.let { actions += it }
        dict(cos.getDictionaryObject(AA))?.let { aa -> aa.keySet().forEach { k -> dict(aa.getDictionaryObject(k))?.let { actions += it } } }
        for (a in actions) {
            if (a.getNameAsString(COSName.S) != "Rendition") continue
            var r = dict(a.getDictionaryObject(R)) ?: continue
            if (r.getNameAsString(COSName.S) == "SR") r = dict((r.getDictionaryObject(R) as? COSArray)?.getObject(0)) ?: continue
            val clip = dict(r.getDictionaryObject(C)) ?: continue
            val mime = clip.getString(CT) ?: ""
            val fs = dict(clip.getDictionaryObject(D)) ?: continue
            val ef = dict(fs.getDictionaryObject(EF)) ?: continue
            val stream = (ef.getDictionaryObject(COSName.UF) ?: ef.getDictionaryObject(COSName.F)) as? COSStream ?: continue
            val name = fs.getString(COSName.UF) ?: fs.getString(COSName.F) ?: "clip"
            if (mime.startsWith("audio/") || Regex("""\.(mp3|wav|m4a|aac|ogg|aif|aiff|au|wma|flac)$""", RegexOption.IGNORE_CASE).containsMatchIn(name))
                return Triple(stream, mime.ifBlank { MediaAnnots.mimeOf(name) }, name)
        }
        return null
    }

    /** Whether annotation [cos] has audio this module can play. */
    fun isAudio(cos: COSDictionary): Boolean = when (cos.getNameAsString(COSName.SUBTYPE)) {
        "Sound" -> cos.getDictionaryObject(SOUND) is COSStream
        "Screen" -> audioClip(cos) != null
        else -> false
    }

    /** Write the audio of annotation [index] on [page] to [dir]; (file, mime) or null. */
    suspend fun extractAt(src: File, pw: String?, page: Int, index: Int, dir: File): Pair<File, String>? = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val doc = if (pw.isNullOrEmpty()) PDDocument.load(src) else PDDocument.load(src, pw)
        doc.use { d ->
            if (page !in 0 until d.numberOfPages) return@use null
            val cos = d.getPage(page).annotations.getOrNull(index)?.cosObject ?: return@use null
            when (cos.getNameAsString(COSName.SUBTYPE)) {
                "Sound" -> {
                    val s = cos.getDictionaryObject(SOUND) as? COSStream ?: return@use null
                    val out = File(dir, "sound_${page}_$index.wav")
                    out.writeBytes(soundToWav(s)); out to "audio/wav"
                }
                "Screen" -> {
                    val (s, mime, name) = audioClip(cos) ?: return@use null
                    val out = File(dir, name.replace(Regex("""[\\/:*?"<>|]"""), "_"))
                    out.outputStream().use { o -> s.createInputStream().use { it.copyTo(o) } }
                    out to mime
                }
                else -> null
            }
        }
    }
}

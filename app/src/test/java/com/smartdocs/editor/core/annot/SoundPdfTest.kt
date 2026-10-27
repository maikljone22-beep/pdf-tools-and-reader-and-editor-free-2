package com.smartdocs.editor.core.annot

import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Native /Sound annotations decode to WAV; Screen + Rendition audio clips are found (Foxit 19.x). */
class SoundPdfTest {
    @Test fun rawAndSignedEightBitToWav() {
        PDDocument().use { d ->
            val s = d.document.createCOSStream()
            s.createOutputStream().use { it.write(byteArrayOf(0, 127, -128, -1)) }
            s.setItem(COSName.getPDFName("R"), COSFloat(11025f)); s.setInt(COSName.getPDFName("B"), 8); s.setName(COSName.getPDFName("E"), "Raw")
            val wav = SoundPdf.soundToWav(s)
            assertEquals(44 + 8, wav.size)
            assertEquals("RIFF", String(wav, 0, 4)); assertEquals("WAVE", String(wav, 8, 4))
            assertEquals(11025, (wav[24].toInt() and 0xFF) or ((wav[25].toInt() and 0xFF) shl 8))
            // raw 0 -> -32768, raw 255 -> 32512
            assertEquals(-32768, SoundPdf.toPcm16(byteArrayOf(0), 8, "Raw")[0].toInt())
            assertEquals(0, SoundPdf.toPcm16(byteArrayOf(0), 8, "Signed")[0].toInt())
            assertEquals(0, SoundPdf.toPcm16(byteArrayOf(-1), 8, "muLaw")[0].toInt())
            assertEquals(256, SoundPdf.toPcm16(byteArrayOf(0, 1), 16, "Signed")[0].toInt() + 255)
        }
    }

    @Test fun screenRenditionAudioClip() {
        PDDocument().use { d ->
            val clipStream = d.document.createCOSStream(); clipStream.createOutputStream().use { it.write(ByteArray(10)) }
            val fs = COSDictionary().apply { setString(COSName.UF, "voice.mp3"); setItem(COSName.getPDFName("EF"), COSDictionary().apply { setItem(COSName.F, clipStream) }) }
            val clip = COSDictionary().apply { setName(COSName.S, "MCD"); setString(COSName.getPDFName("CT"), "audio/mpeg"); setItem(COSName.getPDFName("D"), fs) }
            val r = COSDictionary().apply { setName(COSName.S, "MR"); setItem(COSName.getPDFName("C"), clip) }
            val a = COSDictionary().apply { setName(COSName.S, "Rendition"); setItem(COSName.getPDFName("R"), r) }
            val screen = COSDictionary().apply { setName(COSName.SUBTYPE, "Screen"); setItem(COSName.A, a) }
            val found = SoundPdf.audioClip(screen)
            assertNotNull(found)
            assertEquals("audio/mpeg", found!!.second); assertEquals("voice.mp3", found.third)
            assertTrue(SoundPdf.isAudio(screen))
        }
    }
}

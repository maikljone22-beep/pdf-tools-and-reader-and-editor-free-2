package com.smartdocs.editor.core.annot

import com.smartdocs.editor.core.pdf.AnnotationWriter
import com.smartdocs.editor.core.pdf.DigitalSignature
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationFileAttachment
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.SignatureInterface
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions
import kotlinx.coroutines.runBlocking
import org.bouncycastle.cert.jcajce.JcaCertStore
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Calendar
import kotlin.random.Random

/** In-page video: Screen + Rendition writing, and reading back every supported video form. */
class VideoPdfTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun fakeVideo(size: Int = 300_000): File = tmp.newFile("clip_${size}.mp4").apply { writeBytes(Random(7).nextBytes(size)) }

    // baseline JPEGs made with ffmpeg (no java.awt in Android unit tests): 16x9 test pattern and 1x30 red
    private val jpeg16x9 = "/9j/4AAQSkZJRgABAgAAAQABAAD//gAQTGF2YzYyLjI4LjEwMgD/2wBDAAgQEBMQExYWFhYWFhoYGhsbGxoaGhobGxsdHR0iIiIdHR0bGx0dICAiIiUmJSMjIiMmJigoKDAwLi44ODpFRVP/xAB9AAEBAQAAAAAAAAAAAAAAAAAGBQcBAQEBAQAAAAAAAAAAAAAAAAUGBAcQAAECAgYLAQAAAAAAAAAAAAIBAwARBRIiQhMhBIFzsrFRUlM1NBWzEQABAwMDAgMJAQAAAAAAAAABAgMEERIABSExIjI0gbSzsoN0w3ITUUM1/8AAEQgACQAQAwESAAISAAMSAP/aAAwDAQACEQMRAD8ALM0dpHzRRG7Rkt8c0rIo3pdUHWfQLV+kb9J1GAxIX+RwBwm0Gxwm23ioSaCvP7pjeheIf+H7q8RmyEw2FsyCEFLTaWxbcQsyHFOgKSCaFAaJqbdhTeuUjv8AjDz9vm2LSDLdKCuJIBrYtku3Icqs1tcuEXWvLDsz3QjnenaZPiNFTjViFDq62zWhJ4CicE0Ttc+z6gyJ0+O1PXNeQgOLJjqjL7TslSXKVpQUO4VsdjyBhX95HzUX0krP/9k="
    private val jpeg1x30 = "/9j/4AAQSkZJRgABAgAAAQABAAD//gAQTGF2YzYyLjI4LjEwMgD/2wBDAAgEBAQEBAUFBQUFBQYGBgYGBgYGBgYGBgYHBwcICAgHBwcGBgcHCAgICAkJCQgICAgJCQoKCgwMCwsODg4RERT/xABNAAEBAAAAAAAAAAAAAAAAAAAABgEBAQEAAAAAAAAAAAAAAAAAAAYHEAEAAAAAAAAAAAAAAAAAAAAAEQEAAAAAAAAAAAAAAAAAAAAA/8AAEQgAHgABAwEiAAIRAAMRAP/aAAwDAQACEQMRAD8AiwEm38AB/9k="

    private fun jpeg(tall: Boolean = false): ByteArray = java.util.Base64.getDecoder().decode(if (tall) jpeg1x30 else jpeg16x9)

    /** Annotations through plain COS (PDAnnotation.createAnnotation logs via android.util.Log for /Screen). */
    private fun annots(f: File, page: Int = 0): Pair<PDDocument, List<Ann>> {
        val d = PDDocument.load(f); return d to VideoPdf.annotDicts(d.getPage(page)).map { Ann(it!!) }
    }

    class Ann(val cosObject: COSDictionary) { val subtype: String? get() = cosObject.getNameAsString(COSName.SUBTYPE) }

    private fun VideoPdf.detect(a: Ann) = detectCos(a.cosObject)

    private fun addCos(page: PDPage, annot: COSDictionary) {
        val arr = page.cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray ?: COSArray().also { page.cosObject.setItem(COSName.ANNOTS, it) }
        arr.add(annot)
    }

    private fun d(b: Any?) = b as COSDictionary

    @Test fun screenRenditionRoundTrip() {
        val video = fakeVideo()
        val jp = jpeg()
        val out = tmp.newFile("screen.pdf")
        PDDocument().use { doc ->
            doc.addPage(PDPage(PDRectangle.A4))
            VideoPdf.writeScreen(doc, doc.getPage(0), 100f, 400f, 340f, 535f, video, "clip.mp4", "video/mp4",
                VideoPdf.Options(autoplay = true, loop = true, muted = true, controls = false), VideoPdf.Poster(jp, 16, 9), title = "me")
            doc.save(out)
        }
        val (doc, list) = annots(out)
        doc.use {
            assertEquals(1, list.size)
            val an = list[0]
            assertEquals("Screen", an.subtype)
            val info = VideoPdf.detect(an)!!
            assertEquals(VideoPdf.Form.SCREEN, info.form)
            assertEquals(100f, info.llx, 0.01f); assertEquals(400f, info.lly, 0.01f); assertEquals(340f, info.urx, 0.01f); assertEquals(535f, info.ury, 0.01f)
            assertEquals("video/mp4", info.mime); assertEquals("clip.mp4", info.name)
            assertEquals(VideoPdf.Options(autoplay = true, loop = true, muted = true, controls = false), info.options)
            assertTrue(info.supported); assertTrue(info.ours); assertTrue(info.hasAppearance)
            assertEquals(video.length(), info.size)
            // embedded bytes identical
            val bytes = ByteArrayOutputStream().also { VideoPdf.copyVideo(an.cosObject, it) }.toByteArray()
            assertArrayEquals(video.readBytes(), bytes)
            assertArrayEquals(jp, VideoPdf.posterJpeg(an.cosObject))
            // standard structure
            val cos = an.cosObject
            assertEquals("Video", cos.getNameAsString(VideoPdf.SD_MEDIA))
            val a = d(cos.getDictionaryObject(COSName.A))
            assertEquals("Rendition", a.getNameAsString(COSName.S))
            assertEquals(0, a.getInt(COSName.getPDFName("OP")))
            assertEquals(cos.getString(COSName.NM) ?: "", d(a.getDictionaryObject(COSName.getPDFName("AN"))).getString(COSName.NM) ?: "")
            assertEquals("Screen", d(a.getDictionaryObject(COSName.getPDFName("AN"))).getNameAsString(COSName.SUBTYPE))
            val r = d(a.getDictionaryObject(COSName.getPDFName("R")))
            assertEquals("MR", r.getNameAsString(COSName.S))
            val clip = d(r.getDictionaryObject(COSName.C))
            assertEquals("MCD", clip.getNameAsString(COSName.S))
            assertEquals("video/mp4", clip.getString(COSName.getPDFName("CT")))
            assertEquals("TEMPACCESS", d(clip.getDictionaryObject(COSName.P)).getString(COSName.getPDFName("TF")))
            val spec = d(clip.getDictionaryObject(COSName.getPDFName("D")))
            assertEquals("Filespec", spec.getNameAsString(COSName.TYPE))
            val ef = d(spec.getDictionaryObject(COSName.getPDFName("EF"))).getDictionaryObject(COSName.F) as COSStream
            assertEquals("video/mp4", ef.getNameAsString(COSName.SUBTYPE))
            val be = d(d(r.getDictionaryObject(COSName.P)).getDictionaryObject(COSName.getPDFName("BE")))
            assertEquals(0, be.getInt(COSName.getPDFName("RC"))); assertEquals(0, be.getInt(COSName.getPDFName("V")))
            assertNotNull("autoplay: page-visible action", d(cos.getDictionaryObject(COSName.getPDFName("AA"))).getDictionaryObject(COSName.getPDFName("PV")))
            // appearance: poster image + content stream
            val n = d(cos.getDictionaryObject(COSName.AP)).getDictionaryObject(COSName.N) as COSStream
            val content = n.createInputStream().use { s -> s.readBytes().toString(Charsets.US_ASCII) }
            assertTrue(content.contains("/Im0 Do"))
        }
    }

    @Test fun defaultOptionsHaveNoAutoplayAction() {
        val out = tmp.newFile("plain.pdf")
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            VideoPdf.writeScreen(doc, doc.getPage(0), 10f, 10f, 170f, 100f, fakeVideo(1000), "a.webm", "", VideoPdf.Options(), poster = null)
            doc.save(out)
        }
        val (doc, list) = annots(out)
        doc.use {
            val info = VideoPdf.detect(list[0])!!
            assertEquals(VideoPdf.Options(), info.options)
            assertEquals("video/webm", info.mime)
            // no play action on page open / visible; the default stop actions on page hidden / closed (OP 1)
            val aa = d(list[0].cosObject.getDictionaryObject(COSName.getPDFName("AA")))
            assertNull(aa.getDictionaryObject(COSName.getPDFName("PV"))); assertNull(aa.getDictionaryObject(COSName.getPDFName("PO")))
            assertEquals(1, d(aa.getDictionaryObject(COSName.getPDFName("PI"))).getInt(COSName.getPDFName("OP")))
            assertNull(VideoPdf.posterJpeg(list[0].cosObject))
        }
    }

    /** FINAL_CHECK_2 #15: volume, repeat, keep open, full screen, start trigger, stop actions, poster mode, alt text. */
    @Test fun playbackSettingsAndMediaActionsRoundTrip() {
        val out = tmp.newFile("props.pdf")
        val o = VideoPdf.Options(autoplay = true, volume = 40, repeat = 3, keepOpen = false, fullscreen = true, startOn = VideoPdf.START_OPEN,
            stopWhenHidden = true, poster = VideoPdf.POSTER_NONE, alt = "A lab demo")
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            VideoPdf.writeScreen(doc, doc.getPage(0), 10f, 10f, 170f, 100f, fakeVideo(1000), "demo.mp4", "video/mp4", o, poster = null)
            doc.save(out)
        }
        val (doc, list) = annots(out)
        doc.use {
            val cos = list[0].cosObject
            assertEquals(o, VideoPdf.detect(list[0])!!.options)
            assertEquals("A lab demo", cos.getString(COSName.CONTENTS))
            val aa = d(cos.getDictionaryObject(COSName.getPDFName("AA")))
            assertNotNull(aa.getDictionaryObject(COSName.getPDFName("PO"))); assertNull(aa.getDictionaryObject(COSName.getPDFName("PV")))
            assertEquals(1, d(aa.getDictionaryObject(COSName.getPDFName("PC"))).getInt(COSName.getPDFName("OP")))
            val r = d(d(cos.getDictionaryObject(COSName.A)).getDictionaryObject(COSName.getPDFName("R")))
            val be = d(d(r.getDictionaryObject(COSName.P)).getDictionaryObject(COSName.getPDFName("BE")))
            assertEquals(40, be.getInt(COSName.getPDFName("V"))); assertEquals(3, be.getInt(COSName.getPDFName("RC")))
            assertEquals(1, d(d(r.getDictionaryObject(COSName.getPDFName("SP"))).getDictionaryObject(COSName.getPDFName("BE"))).getInt(COSName.getPDFName("W")))
            val alt = d(r.getDictionaryObject(COSName.C)).getDictionaryObject(COSName.getPDFName("Alt")) as COSArray
            assertEquals("A lab demo", (alt.getObject(1) as COSString).string)
            // without our private /SDVideo keys (another viewer's file) the same settings come from the standard ones
            cos.removeItem(VideoPdf.SD_VIDEO)
            val std = VideoPdf.detect(list[0])!!.options
            assertEquals(40, std.volume); assertEquals(3, std.repeat); assertTrue(std.fullscreen)
            assertEquals(VideoPdf.START_OPEN, std.startOn); assertTrue(std.stopWhenHidden); assertEquals("A lab demo", std.alt)
        }
        assertEquals(0.4f, o.gain, 1e-4f); assertEquals(0f, o.copy(muted = true).gain, 1e-4f)
    }

    @Test fun oldFileAttachmentFormIsAVideo() {
        val video = fakeVideo(5000)
        val out = tmp.newFile("old.pdf")
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            val spec = PDComplexFileSpecification().apply { file = "old.mp4"; fileUnicode = "old.mp4" }
            val ef = video.inputStream().use { PDEmbeddedFile(doc, it) }.apply { subtype = "video/mp4"; size = video.length().toInt() }
            spec.embeddedFile = ef; spec.embeddedFileUnicode = ef
            val an = PDAnnotationFileAttachment().apply { file = spec; rectangle = PDRectangle(50f, 50f, 22f, 22f) }
            an.cosObject.setName(VideoPdf.SD_MEDIA, "Video")
            doc.getPage(0).annotations.add(an)
            // a plain (non-video) attachment is not detected
            val spec2 = PDComplexFileSpecification().apply { file = "a.txt" }
            spec2.embeddedFile = PDEmbeddedFile(doc, "hello".byteInputStream()).apply { subtype = "text/plain" }
            doc.getPage(0).annotations.add(PDAnnotationFileAttachment().apply { file = spec2; rectangle = PDRectangle(100f, 50f, 22f, 22f) })
            doc.save(out)
        }
        val (doc, list) = annots(out)
        doc.use {
            val info = VideoPdf.detect(list[0])!!
            assertEquals(VideoPdf.Form.ATTACHMENT, info.form)
            assertTrue(info.ours); assertTrue(info.supported)
            assertEquals("video/mp4", info.mime)
            assertArrayEquals(video.readBytes(), ByteArrayOutputStream().also { VideoPdf.copyVideo(list[0].cosObject, it) }.toByteArray())
            assertNull(VideoPdf.detect(list[1]))
        }
    }

    private fun fileSpec(doc: PDDocument, name: String, bytes: ByteArray, mime: String): COSDictionary {
        val ef = doc.document.createCOSStream()
        ef.setName(COSName.TYPE, "EmbeddedFile"); ef.setName(COSName.SUBTYPE, mime)
        ef.createOutputStream().use { it.write(bytes) }
        return COSDictionary().apply {
            setName(COSName.TYPE, "Filespec"); setString(COSName.F, name); setString(COSName.getPDFName("UF"), name)
            setItem(COSName.getPDFName("EF"), COSDictionary().apply { setItem(COSName.F, ef) })
        }
    }

    private fun richMedia(doc: PDDocument, assets: List<Pair<String, COSDictionary>>): COSDictionary {
        val names = COSArray().apply { assets.forEach { (n, fs) -> add(COSString(n)); add(fs) } }
        return COSDictionary().apply {
            setName(COSName.TYPE, "Annot"); setName(COSName.SUBTYPE, "RichMedia")
            setItem(COSName.RECT, PDRectangle(20f, 300f, 320f, 180f).cosArray)
            setItem(COSName.getPDFName("RichMediaContent"), COSDictionary().apply {
                setItem(COSName.getPDFName("Assets"), COSDictionary().apply { setItem(COSName.getPDFName("Names"), names) })
            })
            setItem(COSName.getPDFName("RichMediaSettings"), COSDictionary().apply {
                setItem(COSName.getPDFName("Activation"), COSDictionary().apply { setName(COSName.getPDFName("Condition"), "PV") })
            })
        }
    }

    @Test fun handBuiltRichMediaIsDetected() {
        val bytes = Random(3).nextBytes(4096)
        val out = tmp.newFile("rich.pdf")
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            val swf = fileSpec(doc, "player.swf", byteArrayOf(1, 2, 3), "application/x-shockwave-flash")
            val mp4 = fileSpec(doc, "movie.mp4", bytes, "video/mp4")
            addCos(doc.getPage(0), richMedia(doc, listOf("player.swf" to swf, "movie.mp4" to mp4)))
            addCos(doc.getPage(0), richMedia(doc, listOf("only.swf" to fileSpec(doc, "only.swf", byteArrayOf(9), "application/x-shockwave-flash"))))
            doc.save(out)
        }
        val (doc, list) = annots(out)
        doc.use {
            val v = VideoPdf.detect(list[0])!!
            assertEquals(VideoPdf.Form.RICHMEDIA, v.form)
            assertTrue(v.supported); assertFalse(v.ours)
            assertEquals("movie.mp4", v.name); assertEquals("video/mp4", v.mime)
            assertTrue("activation on page visible = autoplay", v.options.autoplay)
            assertEquals(20f, v.llx, 0.01f); assertEquals(480f, v.ury, 0.01f)
            assertArrayEquals(bytes, ByteArrayOutputStream().also { VideoPdf.copyVideo(list[0].cosObject, it) }.toByteArray())
            val flash = VideoPdf.detect(list[1])!!
            assertFalse("Flash only: shown as unsupported", flash.supported)
            assertEquals("application/x-shockwave-flash", flash.mime)
        }
    }

    @Test fun foreignScreenRenditionWithSelectorAndPlayParams() {
        val bytes = Random(5).nextBytes(2048)
        val out = tmp.newFile("foreign.pdf")
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            val annot = COSDictionary().apply {
                setName(COSName.TYPE, "Annot"); setName(COSName.SUBTYPE, "Screen")
                setItem(COSName.RECT, PDRectangle(0f, 0f, 200f, 100f).cosArray)
            }
            val mr = COSDictionary().apply {
                setName(COSName.TYPE, "Rendition"); setName(COSName.S, "MR")
                setItem(COSName.C, COSDictionary().apply {
                    setName(COSName.S, "MCD"); setString(COSName.getPDFName("CT"), "video/quicktime")
                    setItem(COSName.getPDFName("D"), fileSpec(doc, "x.mov", bytes, "video/quicktime"))
                })
                setItem(COSName.P, COSDictionary().apply {
                    setItem(COSName.getPDFName("MH"), COSDictionary().apply { setInt(COSName.getPDFName("RC"), 0); setInt(COSName.getPDFName("V"), 0) })
                })
            }
            val sr = COSDictionary().apply { setName(COSName.S, "SR"); setItem(COSName.getPDFName("R"), COSArray().apply { add(mr) }) }
            val action = COSDictionary().apply { setName(COSName.S, "Rendition"); setInt(COSName.getPDFName("OP"), 0); setItem(COSName.getPDFName("R"), sr); setItem(COSName.getPDFName("AN"), annot) }
            annot.setItem(COSName.getPDFName("AA"), COSDictionary().apply { setItem(COSName.getPDFName("PO"), action) })
            addCos(doc.getPage(0), annot)
            // an audio-only Screen annotation is not a video
            val audio = COSDictionary().apply {
                setName(COSName.TYPE, "Annot"); setName(COSName.SUBTYPE, "Screen")
                setItem(COSName.RECT, PDRectangle(0f, 200f, 20f, 20f).cosArray)
                setItem(COSName.A, COSDictionary().apply {
                    setName(COSName.S, "Rendition")
                    setItem(COSName.getPDFName("R"), COSDictionary().apply {
                        setName(COSName.S, "MR")
                        setItem(COSName.C, COSDictionary().apply { setName(COSName.S, "MCD"); setString(COSName.getPDFName("CT"), "audio/mpeg"); setItem(COSName.getPDFName("D"), fileSpec(doc, "s.mp3", byteArrayOf(1), "audio/mpeg")) })
                    })
                })
            }
            addCos(doc.getPage(0), audio)
            doc.save(out)
        }
        val (doc, list) = annots(out)
        doc.use {
            val v = VideoPdf.detect(list[0])!!
            assertEquals(VideoPdf.Form.SCREEN, v.form); assertFalse(v.ours); assertFalse(v.hasAppearance)
            assertEquals("video/quicktime", v.mime); assertEquals("x.mov", v.name)
            assertEquals(VideoPdf.Options(autoplay = true, loop = true, muted = true, controls = true, startOn = VideoPdf.START_OPEN, stopWhenHidden = false), v.options)
            assertArrayEquals(bytes, ByteArrayOutputStream().also { VideoPdf.copyVideo(list[0].cosObject, it) }.toByteArray())
            assertNull(VideoPdf.detect(list[1]))
            assertEquals(0, VideoPdf.findIndex(it.getPage(0), 5, "Screen", floatArrayOf(0f, 0f, 200f, 100f)))
            assertEquals(1, VideoPdf.findIndex(it.getPage(0), 1, "Screen", null))
        }
    }

    @Test fun jpegSizeReadsSof() {
        assertEquals(16 to 9, VideoPdf.jpegSize(jpeg()))
        assertEquals(1 to 30, VideoPdf.jpegSize(jpeg(tall = true)))
        assertNull(VideoPdf.jpegSize(byteArrayOf(1, 2, 3, 4)))
    }

    @Test fun videoOnSignedPdfIsAppendedIncrementally() {
        val plain = tmp.newFile("p.pdf")
        PDDocument().use { it.addPage(PDPage()); it.save(plain) }
        val id = runBlocking { DigitalSignature.generateSelfSigned(tmp.newFile("id.p12"), "Test Signer", "", "", "pw".toCharArray()) }
        val signer = SignatureInterface { content ->
            val gen = CMSSignedDataGenerator()
            gen.addSignerInfoGenerator(JcaSignerInfoGeneratorBuilder(JcaDigestCalculatorProviderBuilder().build())
                .build(JcaContentSignerBuilder("SHA256withRSA").build(id.privateKey), id.chain.first()))
            gen.addCertificates(JcaCertStore(id.chain))
            gen.generate(CMSProcessableByteArray(content.readBytes()), false).encoded
        }
        val signed = tmp.newFile("signed.pdf")
        PDDocument.load(plain).use { d ->
            val sig = PDSignature().apply { setFilter(PDSignature.FILTER_ADOBE_PPKLITE); setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED); name = "T"; signDate = Calendar.getInstance() }
            SignatureOptions().use { o -> d.addSignature(sig, signer, o); signed.outputStream().use { d.saveIncremental(it) } }
        }
        val video = fakeVideo(20_000)
        val out = File(tmp.root, "signed_video.pdf")
        AnnotationWriter.saveChanged(signed, out, null) { d ->
            VideoPdf.writeScreen(d, d.getPage(0), 50f, 50f, 250f, 160f, video, "v.mp4", "video/mp4", VideoPdf.Options(), VideoPdf.Poster(jpeg(), 16, 9))
            Unit to listOf(0)
        }
        val orig = signed.readBytes()
        assertArrayEquals("signed revision untouched", orig, out.readBytes().copyOf(orig.size))
        assertTrue(runBlocking { DigitalSignature.verify(out) }[0].signatureValid)
        PDDocument.load(out).use { d ->
            val an = VideoPdf.annotDicts(d.getPage(0)).filterNotNull().first { it.getNameAsString(COSName.SUBTYPE) == "Screen" }.let(::Ann)
            assertArrayEquals(video.readBytes(), ByteArrayOutputStream().also { VideoPdf.copyVideo(an.cosObject, it) }.toByteArray())
        }
    }

    /** Writes a sample PDF for external validation (pypdfium2) when -Dvideo.sample=<path> is set. */
    @Test fun sampleForExternalValidation() {
        val path = System.getProperty("video.sample") ?: System.getenv("VIDEO_SAMPLE_PDF") ?: return
        val src = (System.getProperty("video.sample.src") ?: System.getenv("VIDEO_SAMPLE_SRC"))?.let(::File)?.takeIf { it.isFile } ?: fakeVideo(50_000)
        val poster = (System.getProperty("video.sample.poster") ?: System.getenv("VIDEO_SAMPLE_POSTER"))?.let(::File)?.takeIf { it.isFile }?.readBytes() ?: jpeg()
        val (w, h) = VideoPdf.jpegSize(poster)!!
        PDDocument().use { doc ->
            doc.addPage(PDPage(PDRectangle.A4))
            VideoPdf.writeScreen(doc, doc.getPage(0), 72f, 450f, 72f + 320f, 450f + 180f, src, src.name, "video/mp4",
                VideoPdf.Options(loop = true), VideoPdf.Poster(poster, w, h), title = "SmartDocs")
            doc.save(File(path))
        }
    }
}

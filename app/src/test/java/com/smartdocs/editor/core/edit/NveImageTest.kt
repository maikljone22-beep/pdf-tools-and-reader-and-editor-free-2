package com.smartdocs.editor.core.edit

import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** NVE-img: round trips of the page-object image operations (crop, opacity, move, z-order, duplicate, pixels, layers). */
class NveImageTest {

    private fun tmp() = File.createTempFile("nve_img", ".pdf").also { it.deleteOnExit() }

    /** One A4 page with [n] gray images of 200×100 px, the k-th placed at (10 + 120k, 20) size 100×50 pt. */
    private fun doc(n: Int = 1): File {
        val f = tmp()
        PDDocument().use { d ->
            val p = PDPage(PDRectangle.A4); d.addPage(p)
            PDPageContentStream(d, p).use { cs ->
                repeat(n) { k ->
                    val img = ImageEdit5.grayImage(d, 200, 100, ByteArray(200 * 100) { (it % 251).toByte() })
                    cs.drawImage(img, 10f + 120f * k, 20f, 100f, 50f)
                }
            }
            d.save(f)
        }
        return f
    }

    private fun <T> reload(f: File, block: (PDDocument) -> T): T = PDDocument.load(f).use(block)

    private fun save(f: File, block: (PDDocument) -> Unit) {
        val out = tmp()
        PDDocument.load(f).use { d -> block(d); d.save(out) }
        out.copyTo(f, overwrite = true)
    }

    private fun imageOps(d: PDDocument): List<Int> {
        val p = d.getPage(0); val s = ImageEdit5.scan(p)
        return s.ops.indices.filter { ImageEdit5.isImageOp(p, s.ops[it]) }
    }

    private fun ctmOf(d: PDDocument, op: Int) = Affine.values(ImageEdit5.scan(d.getPage(0)).ops[op].ctm)

    @Test fun cropAndOpacityTravelWithTheImage() {
        val f = doc()
        save(f) { d -> ImageEdit5.apply(d, 0, listOf(ImageEdit5.Change(imageOps(d)[0], crop = floatArrayOf(0.1f, 0.2f, 0.5f, 0.6f), opacity = 0.5f))) }
        reload(f) { d ->
            val op = imageOps(d).single()
            val g = ImageEdit5.groupOf(ImageEdit5.scan(d.getPage(0)).ops, op)
            assertEquals(0.5f, g.crop!![2], 1e-4f); assertEquals(0.5f, g.opacity!!, 1e-4f)
            val v = ctmOf(d, op)
            assertEquals(100f, v[0], 1e-3f); assertEquals(50f, v[3], 1e-3f); assertEquals(10f, v[4], 1e-3f); assertEquals(20f, v[5], 1e-3f)
        }
        // move by (5, 7): the clip and opacity stay with it, no second group is nested
        save(f) { d -> ImageEdit5.apply(d, 0, listOf(ImageEdit5.Change(imageOps(d)[0], display = floatArrayOf(1f, 0f, 0f, 1f, 5f, 7f)))) }
        reload(f) { d ->
            val op = imageOps(d).single()
            val ops = ImageEdit5.scan(d.getPage(0)).ops
            val g = ImageEdit5.groupOf(ops, op)
            assertNotNull(g.crop); assertEquals(0.5f, g.opacity!!, 1e-4f)
            assertEquals(1, ops.count { it.name == "BDC" })
            val v = ctmOf(d, op)
            assertEquals(15f, v[4], 1e-3f); assertEquals(27f, v[5], 1e-3f)
            val info = ImageEdit5.info(d, 0, op)!!
            assertTrue(info.cropped); assertEquals(144f, info.ppiX, 0.5f); assertEquals("DeviceGray", info.colorSpace)
        }
        // blend mode joins the same ExtGState; opacity is kept
        save(f) { d -> ImageEdit5.apply(d, 0, listOf(ImageEdit5.Change(imageOps(d)[0], blend = "Multiply"))) }
        reload(f) { d ->
            val l = ImageEdit5.look(d, 0, imageOps(d).single())
            assertEquals("Multiply", l.blend); assertEquals(0.5f, l.opacity, 1e-4f)
            val gs = d.getPage(0).resources.extGStateNames.map { d.getPage(0).resources.getExtGState(it) }
            assertTrue(gs.any { it.cosObject.getCOSName(COSName.getPDFName("BM"))?.name == "Multiply" && it.nonStrokingAlphaConstant == 0.5f })
        }
        // remove the crop, make it opaque again
        save(f) { d -> ImageEdit5.apply(d, 0, listOf(ImageEdit5.Change(imageOps(d)[0], resetCrop = true, opacity = 1f, blend = "Normal"))) }
        reload(f) { d -> val g = ImageEdit5.groupOf(ImageEdit5.scan(d.getPage(0)).ops, imageOps(d).single()); assertEquals(null, g.crop); assertEquals(null, g.opacity) }
    }

    @Test fun rotateAndFlipAroundCentre() {
        val f = doc()
        // flip horizontally around the image centre (x = 60)
        save(f) { d -> ImageEdit5.apply(d, 0, listOf(ImageEdit5.Change(imageOps(d)[0], display = floatArrayOf(-1f, 0f, 0f, 1f, 120f, 0f)))) }
        reload(f) { d ->
            val v = ctmOf(d, imageOps(d).single())
            assertEquals(-100f, v[0], 1e-3f); assertEquals(110f, v[4], 1e-3f)
        }
    }

    private fun doOrder(d: PDDocument): List<Float> = imageOps(d).map { ctmOf(d, it)[4] }

    @Test fun zOrder() {
        val f = doc(3)
        reload(f) { d -> assertEquals(listOf(10f, 130f, 250f), doOrder(d)) }
        save(f) { d -> assertTrue(ImageEdit5.arrange(d, 0, imageOps(d)[0], ImageEdit5.Arrange.FRONT)) }
        reload(f) { d -> assertEquals(listOf(130f, 250f, 10f), doOrder(d)) }
        save(f) { d -> assertTrue(ImageEdit5.arrange(d, 0, imageOps(d)[2], ImageEdit5.Arrange.BACK)) }
        reload(f) { d -> assertEquals(listOf(10f, 130f, 250f), doOrder(d)) }
        save(f) { d -> assertTrue(ImageEdit5.arrange(d, 0, imageOps(d)[0], ImageEdit5.Arrange.FORWARD)) }
        reload(f) { d -> assertEquals(listOf(130f, 10f, 250f), doOrder(d)) }
        save(f) { d -> assertTrue(ImageEdit5.arrange(d, 0, imageOps(d)[2], ImageEdit5.Arrange.BACKWARD)) }
        reload(f) { d -> assertEquals(listOf(130f, 250f, 10f), doOrder(d)) }
        reload(f) { d -> assertFalse(ImageEdit5.arrange(d, 0, imageOps(d)[2], ImageEdit5.Arrange.FRONT)) }
    }

    @Test fun duplicateAndReplacePixelsKeepPlacement() {
        val f = doc()
        save(f) { d -> ImageEdit5.apply(d, 0, listOf(ImageEdit5.Change(imageOps(d)[0], crop = floatArrayOf(0f, 0f, 0.5f, 1f)))) }
        save(f) { d -> ImageEdit5.duplicate(d, 0, imageOps(d)[0], 12f, -12f) }
        reload(f) { d ->
            val ops = imageOps(d); assertEquals(2, ops.size)
            assertEquals(22f, ctmOf(d, ops[1])[4], 1e-3f); assertEquals(8f, ctmOf(d, ops[1])[5], 1e-3f)
            assertNotNull(ImageEdit5.groupOf(ImageEdit5.scan(d.getPage(0)).ops, ops[1]).crop)
        }
        save(f) { d -> ImageEdit5.replacePixels(d, 0, imageOps(d)[0]) { doc -> ImageEdit5.bwImage(doc, 40, 20, BooleanArray(800) { it % 3 == 0 }) } }
        reload(f) { d ->
            val op = imageOps(d)[0]
            val v = ctmOf(d, op); assertEquals(100f, v[0], 1e-3f); assertEquals(10f, v[4], 1e-3f)
            val info = ImageEdit5.info(d, 0, op)!!
            assertEquals(40, info.pixelW); assertEquals(1, info.bitsPerComponent); assertTrue(info.cropped)
        }
        save(f) { d -> ImageEdit5.replacePixels(d, 0, imageOps(d)[1]) { doc -> ImageEdit5.cmykImage(doc, 2, 1, intArrayOf(0xFF0000, 0x000000)) } }
        reload(f) { d -> assertEquals("DeviceCMYK", ImageEdit5.info(d, 0, imageOps(d)[1])!!.colorSpace) }
    }

    @Test fun mergeLayers() {
        val f = tmp()
        PDDocument().use { d ->
            val p = PDPage(PDRectangle.A4); d.addPage(p)
            d.documentCatalog.cosObject.setItem(COSName.getPDFName("OCProperties"), COSDictionary())
            PDPageContentStream(d, p).use { cs ->
                cs.beginMarkedContent(COSName.getPDFName("OC"), PDPropertyList.create(COSDictionary()))
                cs.addRect(10f, 10f, 50f, 50f); cs.fill()
                cs.endMarkedContent()
            }
            d.save(f)
        }
        reload(f) { d -> assertTrue(ImageEdit5.hasLayers(d)) }
        save(f) { d -> ImageEdit5.mergeLayers(d) }
        reload(f) { d ->
            assertFalse(ImageEdit5.hasLayers(d))
            val tokens = com.tom_roush.pdfbox.pdfparser.PDFStreamParser(d.getPage(0)).also { it.parse() }.tokens
            assertFalse(ImageEdit5.hasOcMarks(tokens))
            assertTrue(tokens.any { it is com.tom_roush.pdfbox.contentstream.operator.Operator && it.name == "BMC" })
        }
    }

    /** A page with a red filled rectangle (50,60 — 150,100) and, on top, a clipped image. */
    private fun shapeDoc(pages: Int = 1): File {
        val f = tmp()
        PDDocument().use { d ->
            repeat(pages) { d.addPage(PDPage(PDRectangle.A4)) }
            val p = d.getPage(0)
            PDPageContentStream(d, p).use { cs ->
                cs.setNonStrokingColor(255, 0, 0)
                cs.addRect(50f, 60f, 100f, 40f); cs.fill()
                cs.saveGraphicsState()
                cs.addRect(200f, 200f, 50f, 50f); cs.clip()
                cs.drawImage(ImageEdit5.grayImage(d, 4, 4, ByteArray(16)), 200f, 200f, 100f, 100f)
                cs.restoreGraphicsState()
            }
            d.save(f)
        }
        return f
    }

    private fun shapes(d: PDDocument) = ImageEdit5.objects(d.getPage(0)).filter { it.kind == ImageEdit5.Kind.SHAPE }

    @Test fun shapesAreObjects() {
        val f = shapeDoc()
        reload(f) { d ->
            val objs = ImageEdit5.objects(d.getPage(0))
            val sh = objs.single { it.kind == ImageEdit5.Kind.SHAPE }
            assertEquals(50f, sh.box[0], 1e-3f); assertEquals(100f, sh.box[3], 1e-3f)
            assertEquals(1, objs.count { it.kind == ImageEdit5.Kind.IMAGE })
        }
        // move + recolour + ellipse clip on the shape: one group, the path stays inside it
        save(f) { d -> ImageEdit5.apply(d, 0, listOf(ImageEdit5.Change(shapes(d).single().op, display = floatArrayOf(1f, 0f, 0f, 1f, 10f, 0f),
            fill = 0x0000FF, crop = floatArrayOf(0f, 0f, 1f, 1f), ellipse = true, lineWidth = 2f))) }
        reload(f) { d ->
            val sh = shapes(d).single()
            assertEquals(60f, sh.box[0], 1e-3f)
            val ops = ImageEdit5.scan(d.getPage(0)).ops
            val g = ImageEdit5.groupOf(ops, sh.op)
            assertTrue(g.ellipse); assertTrue(g.inner.any { (it.last() as com.tom_roush.pdfbox.contentstream.operator.Operator).name == "rg" })
        }
        // z-order works across kinds: the shape goes in front of the image
        save(f) { d -> assertTrue(ImageEdit5.arrange(d, 0, shapes(d).single().op, ImageEdit5.Arrange.FRONT)) }
        reload(f) { d ->
            val objs = ImageEdit5.objects(d.getPage(0))
            assertEquals(ImageEdit5.Kind.SHAPE, objs.last().kind)
            assertEquals(60f, objs.last().box[0], 1e-3f)
        }
        save(f) { d -> ImageEdit5.deleteObject(d, 0, shapes(d).single().op) }
        reload(f) { d -> assertTrue(shapes(d).isEmpty()) }
    }

    @Test fun outerClipCanBeCleared() {
        val f = shapeDoc()
        reload(f) { d -> assertTrue(ImageEdit5.clearOuterClip(d, 0, imageOps(d).single(), dryRun = true)) }
        save(f) { d -> assertTrue(ImageEdit5.clearOuterClip(d, 0, imageOps(d).single())) }
        reload(f) { d ->
            val ops = ImageEdit5.scan(d.getPage(0)).ops
            assertFalse(ops.any { it.name == "W" })
            assertFalse(ImageEdit5.clearOuterClip(d, 0, imageOps(d).single(), dryRun = true))
        }
    }

    @Test fun placeOnOtherPagesAndPasteAcrossDocuments() {
        val f = shapeDoc(3)
        save(f) { d -> ImageEdit5.placeOnPages(d, 0, imageOps(d).single(), listOf(1, 2)) }
        reload(f) { d ->
            for (i in 1..2) {
                val p = d.getPage(i); val s = ImageEdit5.scan(p)
                val op = s.ops.indices.single { ImageEdit5.isImageOp(p, s.ops[it]) }
                assertEquals(200f, Affine.values(s.ops[op].ctm)[4], 1e-3f)
            }
        }
        val clip = reload(f) { d -> ImageEdit5.copyVector(d, 0, shapes(d).single().op) }!!
        val other = doc()
        save(other) { d -> ImageEdit5.pasteVector(d, 0, clip, 5f, 5f) }
        reload(other) { d ->
            val sh = shapes(d).single()
            assertEquals(55f, sh.box[0], 1e-3f); assertEquals(65f, sh.box[1], 1e-3f)
        }
    }

    @Test fun imageAnnotationProperties() {
        val f = tmp()
        PDDocument().use { d ->
            val p = PDPage(PDRectangle.A4); d.addPage(p)
            val stamp = com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationRubberStamp()
            stamp.rectangle = PDRectangle(100f, 100f, 200f, 100f)
            val form = com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject(d)
            form.bBox = PDRectangle(0f, 0f, 200f, 100f); form.resources = com.tom_roush.pdfbox.pdmodel.PDResources()
            PDPageContentStream(d, com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream(form.cosObject)).use { cs ->
                cs.drawImage(ImageEdit5.grayImage(d, 40, 40, ByteArray(1600)), 0f, 0f, 200f, 100f)
            }
            stamp.appearance = com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary().apply {
                setNormalAppearance(com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream(form.cosObject))
            }
            p.annotations.add(stamp)
            d.save(f)
        }
        val P = com.smartdocs.editor.core.annot.ImageAnnotProps5
        // 40 px at 96 dpi = 30 pt; proportional "always" in 200 x 100 -> 100 x 100 centred
        val fit = P.fit(40f, 40f, 200f, 100f, P.Props())
        assertEquals(50f, fit[0], 1e-3f); assertEquals(100f, fit[2], 1e-3f)
        assertEquals(30f, P.fit(40f, 40f, 200f, 100f, P.Props(scaleWhen = P.ScaleWhen.BIGGER))[2], 1e-3f)
        assertEquals(200f, P.fit(40f, 40f, 200f, 100f, P.Props(proportional = false))[2], 1e-3f)
        save(f) { d -> assertTrue(P.apply(d, 0, 0, P.Props(hidden = true, locked = true, border = 2f, scaleWhen = P.ScaleWhen.NEVER, posX = 0f, posY = 1f))) }
        reload(f) { d ->
            val r = P.read(d, 0, 0)!!
            assertTrue(r.hidden); assertTrue(r.locked); assertEquals(2f, r.border, 1e-3f)
            assertEquals(P.ScaleWhen.NEVER, r.scaleWhen); assertEquals(1f, r.posY, 1e-3f)
            assertNotNull(P.image(d, 0, 0))
        }
    }
}

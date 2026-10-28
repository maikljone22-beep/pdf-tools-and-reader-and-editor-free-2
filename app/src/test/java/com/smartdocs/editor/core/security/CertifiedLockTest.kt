package com.smartdocs.editor.core.security

import com.smartdocs.editor.core.pdf.SignedGuard
import com.smartdocs.editor.core.pdf.SignedSave
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tab audit 4, D8: tools a certification / FieldMDP lock forbids are blocked when the document opens. */
class CertifiedLockTest {

    @Test fun noLockBlocksNothing() {
        for (a in DocAction.values()) {
            assertFalse(CertifiedLock.blocks(null, a))
            assertFalse(CertifiedLock.blocks(SignedGuard.Info.NONE, a))
            // signed but not certified: annotations are appended incrementally
            assertFalse(CertifiedLock.blocks(SignedGuard.Info(signed = true, lockP = null), a))
        }
        assertFalse(CertifiedLock.showBanner(SignedGuard.Info(true, null)))
    }

    @Test fun p1BlocksAnnotationsAndForms() {
        val i = SignedGuard.Info(signed = true, lockP = 1)
        assertTrue(CertifiedLock.blocks(i, DocAction.ANNOTATE))
        assertTrue(CertifiedLock.blocks(i, DocAction.FILL_FORMS))
        assertFalse(CertifiedLock.blocks(i, DocAction.PRINT))
        assertFalse(CertifiedLock.blocks(i, DocAction.COPY))
        assertTrue(CertifiedLock.showBanner(i))
    }

    @Test fun p2BlocksAnnotationsOnly() {
        val i = SignedGuard.Info(signed = true, lockP = 2)
        assertTrue(CertifiedLock.blocks(i, DocAction.ANNOTATE))
        assertFalse(CertifiedLock.blocks(i, DocAction.FILL_FORMS))
        assertTrue(CertifiedLock.showBanner(i))
    }

    @Test fun p3AllowsAnnotations() {
        val i = SignedGuard.Info(signed = true, lockP = 3)
        assertFalse(CertifiedLock.blocks(i, DocAction.ANNOTATE))
        assertFalse(CertifiedLock.blocks(i, DocAction.FILL_FORMS))
        assertFalse(CertifiedLock.showBanner(i))
    }

    @Test fun certifyLevelIsReadFromTheCatalog() {
        PDDocument().use { d ->
            d.addPage(PDPage())
            assertNull(SignedSave.certifyLevel(d))
            val tp = COSDictionary().apply { setInt(COSName.P, 2) }
            val ref = COSDictionary().apply { setItem(COSName.TRANSFORM_METHOD, COSName.DOCMDP); setItem(COSName.TRANSFORM_PARAMS, tp) }
            val sig = COSDictionary().apply { setItem(COSName.REFERENCE, COSArray().apply { add(ref) }) }
            d.documentCatalog.cosObject.setItem(COSName.PERMS, COSDictionary().apply { setItem(COSName.DOCMDP, sig) })
            assertEquals(2, SignedSave.certifyLevel(d))
            // (SignedGuard.inspect also reads the signature fields: the AcroForm fixup loads fonts, not on the JVM)
            val info = SignedGuard.Info(signed = false, lockP = SignedSave.certifyLevel(d))
            assertTrue(CertifiedLock.blocks(info, DocAction.ANNOTATE))
            assertFalse(CertifiedLock.blocks(info, DocAction.FILL_FORMS))
        }
    }
}

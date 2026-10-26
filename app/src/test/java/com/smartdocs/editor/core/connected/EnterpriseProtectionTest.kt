package com.smartdocs.editor.core.connected

import com.smartdocs.editor.core.connected.EnterpriseProtection.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnterpriseProtectionTest {
    private fun b(s: String) = s.toByteArray(Charsets.ISO_8859_1)
    private fun u16(s: String) = s.flatMap { listOf(it, '\u0000') }.joinToString("")
    private val none = ByteArray(0)

    @Test fun plainPdfIsNotProtected() {
        assertNull(EnterpriseProtection.detect(b("%PDF-1.7\n1 0 obj<</Type/Catalog>>endobj"), none, "a.pdf"))
    }

    @Test fun wrapperExtensions() {
        assertEquals(Kind.WRAPPER, EnterpriseProtection.detect(b("xx"), none, "report.pdf.pfile")!!.kind)
        assertTrue(EnterpriseProtection.detect(b("xx"), none, "report.ppdf")!!.blocking)
    }

    @Test fun irmSecurityHandler() {
        val i = EnterpriseProtection.detect(b("%PDF-1.6\n"), b("5 0 obj<</Filter /MicrosoftIRMServices/V 2>>endobj trailer<</Encrypt 5 0 R>>"), "x.pdf")!!
        assertEquals(Kind.IRM_PDF, i.kind); assertTrue(i.blocking)
    }

    @Test fun otherDrmHandler() {
        assertEquals(Kind.OTHER_DRM, EnterpriseProtection.detect(b("%PDF-1.4 <</Filter/FOPN_foweb>>"), none, "x.pdf")!!.kind)
    }

    @Test fun wrapperPdfEmbeddingProtectedOriginal() {
        val pdf = "%PDF-1.7 <</Type/EmbeddedFile>> <</F (contract.pdf.pfile)>>"
        assertEquals(Kind.WRAPPER, EnterpriseProtection.detect(b(pdf), none, "contract.pdf")!!.kind)
    }

    @Test fun officeDataSpaces() {
        val ole = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
        val i = EnterpriseProtection.detect(ole + b("....." + u16("\u0006DataSpaces") + "...."), none, "a.docx")!!
        assertEquals(Kind.OFFICE_IRM, i.kind)
        assertNull(EnterpriseProtection.detect(ole + b("plain old doc"), none, "a.doc"))
    }

    @Test fun labelOnlyIsNotBlocking() {
        val pdf = "%PDF-1.7 <</MSIP_Label_0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0_Enabled (true)/MSIP_Label_0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0_Name (Confidential)>>"
        val i = EnterpriseProtection.detect(b(pdf), none, "x.pdf")!!
        assertEquals(Kind.LABEL_ONLY, i.kind); assertFalse(i.blocking); assertEquals("Confidential", i.labelName)
    }

    @Test fun irmWinsOverLabel() {
        val pdf = "%PDF-1.7 <</MSIP_Label_0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0_Name (Secret)>> <</Filter /MicrosoftIRMServices>>"
        val i = EnterpriseProtection.detect(b(pdf), none, "x.pdf")!!
        assertEquals(Kind.IRM_PDF, i.kind); assertEquals("Secret", i.labelName)
    }
}

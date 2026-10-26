package com.smartdocs.editor.core.connected

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ConnMarkerTest {
    private val id = "ab12cd34ef56"

    @Test fun trailerNameWins() {
        val pdf = "%PDF-1.7\n1 0 obj<</SDConnId (zzzzzzzzzzzz)>>endobj\ntrailer\n<</Size 5/Root 1 0 R/SDConnId /$id/SDConnP /1>>\n%%EOF"
        val m = ConnMarker.find(pdf)!!
        assertEquals(id, m.docId); assertTrue(m.protected); assertEquals("trailer", m.where)
    }

    @Test fun encryptMeansProtected() {
        val m = ConnMarker.find("trailer<</Encrypt 9 0 R/SDConnId /$id>>")!!
        assertTrue(m.protected)
        assertFalse(ConnMarker.find("trailer<</SDConnId /$id>>")!!.protected)
    }

    @Test fun infoAndXmpFallbacks() {
        assertEquals("info", ConnMarker.find("<</Producer (x)/SDConnId ($id)>>")!!.where)
        val x = ConnMarker.find("<rdf:Description><sdc:DocumentId> $id </sdc:DocumentId></rdf:Description>")!!
        assertEquals(id, x.docId); assertEquals("xmp", x.where)
        assertEquals(id, ConnMarker.find("<rdf:Description sdc:DocumentId=\"$id\"/>")!!.docId)
    }

    @Test fun rejectsMalformedIds() {
        assertNull(ConnMarker.find("trailer<</SDConnId /ABC>>"))
        assertNull(ConnMarker.find("/SDConnId (short)"))
        assertFalse(ConnMarker.isId("AB12CD34EF56"))
        assertTrue(ConnMarker.isId(id))
    }

    @Test fun fileScanFindsMarkerOutsideTail() {
        val f = File.createTempFile("conn", ".pdf")
        try {
            val head = "%PDF-1.7\n<</SDConnId ($id)>>\n".toByteArray(Charsets.ISO_8859_1)
            f.outputStream().use { o -> o.write(head); o.write(ByteArray(400 * 1024) { ' '.code.toByte() }); o.write("trailer<</Size 1>>\n%%EOF".toByteArray()) }
            val m = ConnMarker.find(f)
            assertNotNull(m); assertEquals(id, m!!.docId)
        } finally { f.delete() }
    }
}

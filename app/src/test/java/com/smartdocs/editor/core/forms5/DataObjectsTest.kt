package com.smartdocs.editor.core.forms5

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentNameDictionary
import com.tom_roush.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** fix/pdf39-forms-2: exportDataObject finds embedded files (8.84). */
class DataObjectsTest {
    @Test fun findsByKeyThenByFileName() {
        PDDocument().use { d ->
            d.addPage(PDPage())
            val spec = PDComplexFileSpecification().apply {
                file = "terms.txt"; setFileUnicode("terms.txt")
                embeddedFile = PDEmbeddedFile(d, "hello".toByteArray().inputStream())
            }
            val tree = PDEmbeddedFilesNameTreeNode().apply { names = mapOf("Terms" to spec) }
            d.documentCatalog.names = PDDocumentNameDictionary(d.documentCatalog).apply { embeddedFiles = tree }
            assertArrayEquals("hello".toByteArray(), DataObjects.find(d, "Terms")!!.bytes)
            assertEquals("Terms", DataObjects.find(d, "terms.txt")!!.key)
            assertEquals("terms.txt", DataObjects.find(d, "TERMS")!!.fileName)
            assertNull(DataObjects.find(d, "other"))
        }
        assertEquals("a_b.txt", DataObjects.safeName("../x/a:b.txt"))
    }
}

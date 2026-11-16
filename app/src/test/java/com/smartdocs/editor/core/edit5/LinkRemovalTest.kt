package com.smartdocs.editor.core.edit5

import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LinkRemovalTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun source(password: String? = null): File = tmp.newFile().also { file ->
        PDDocument().use { doc ->
            repeat(3) {
                val page = PDPage()
                doc.addPage(page)
                page.annotations = mutableListOf(
                    PDAnnotationText().apply { contents = "Keep this comment" },
                    PDAnnotationLink().apply {
                        rectangle = PDRectangle(10f, 10f, 80f, 20f)
                        action = PDActionURI().apply { uri = "https://example.com" }
                    },
                    PDAnnotationLink().apply {
                        rectangle = PDRectangle(10f, 40f, 80f, 20f)
                        cosObject.setItem(COSName.DEST, com.tom_roush.pdfbox.cos.COSString("chapter"))
                    }
                )
            }
            if (password != null) doc.protect(StandardProtectionPolicy("owner-secret", password, AccessPermission()).apply {
                encryptionKeyLength = 128
                setPreferAES(true)
            })
            doc.save(file)
        }
    }

    private fun counts(file: File, password: String? = null): List<Int> = PDDocument.load(file, password ?: "").use { doc ->
        doc.pages.map { page -> page.annotations.count { it is PDAnnotationLink } }
    }

    @Test fun webDeletionOnlyChangesSelectedPagesAndKeepsOtherAnnotations() = runBlocking {
        val src = source(); val out = tmp.newFile()
        assertEquals(2, LinkOps.removeAll(src, out, null, webOnly = true, pages = setOf(0, 2)))
        assertEquals(listOf(1, 2, 1), counts(out))
        assertEquals(listOf(2, 2, 2), counts(src))
        PDDocument.load(out).use { doc ->
            for (page in doc.pages) assertEquals(1, page.annotations.count { it is PDAnnotationText })
            assertNull(doc.getPage(0).annotations.filterIsInstance<PDAnnotationLink>().single().action)
        }
    }

    @Test fun allLinkTypesCanBeDeletedOnJustTheCurrentPage() = runBlocking {
        val src = source(); val out = tmp.newFile()
        assertEquals(2, LinkOps.removeAll(src, out, null, webOnly = false, pages = setOf(1)))
        assertEquals(listOf(2, 0, 2), counts(out))
    }

    @Test fun emptyScopeNeverFallsBackToWholeDocument() = runBlocking {
        val src = source(); val out = tmp.newFile()
        assertEquals(0, LinkOps.removeAll(src, out, null, webOnly = false, pages = emptySet()))
        assertEquals(listOf(2, 2, 2), counts(out))
    }

    @Test fun wholeDocumentDeletionPersistsAndRepeatingItReturnsZero() = runBlocking {
        val src = source(); val out = tmp.newFile(); val again = tmp.newFile()
        assertEquals(6, LinkOps.removeAll(src, out, null, webOnly = false))
        assertEquals(listOf(0, 0, 0), counts(out))
        assertEquals(0, LinkOps.removeAll(out, again, null, webOnly = false))
    }

    @Test fun passwordProtectedOutputRemainsReadableAndEncrypted() = runBlocking {
        val src = source("secret"); val out = tmp.newFile()
        assertEquals(2, LinkOps.removeAll(src, out, "secret", webOnly = false, pages = setOf(1)))
        assertEquals(listOf(2, 0, 2), counts(out, "secret"))
        PDDocument.load(out, "secret").use { assertTrue(it.isEncrypted) }
    }

    @Test fun selectedDeletionUsesAnnotationIndexesNotLinkListIndexes() = runBlocking {
        val src = source(); val out = tmp.newFile()
        LinkOps.delete(src, out, null, mapOf(1 to setOf(1)))
        assertEquals(listOf(2, 1, 2), counts(out))
        PDDocument.load(out).use { doc ->
            assertEquals("Keep this comment", doc.getPage(1).annotations.first().contents)
        }
    }
    @Test fun replacingTargetsChangesOnlySelectedAnnotationsAndPersists() = runBlocking {
        val src = source(); val out = tmp.newFile()
        assertEquals(2, LinkOps.replaceTargets(src, out, null, mapOf(1 to setOf(1, 2)), "https://replacement.example"))
        PDDocument.load(out).use { doc ->
            val links = doc.getPage(1).annotations.filterIsInstance<PDAnnotationLink>()
            assertEquals(2, links.size)
            assertTrue(links.all { (it.action as PDActionURI).uri == "https://replacement.example" })
            assertTrue(links.all { it.destination == null })
            assertEquals("Keep this comment", doc.getPage(1).annotations.first().contents)
            assertEquals("https://example.com", (doc.getPage(0).annotations.filterIsInstance<PDAnnotationLink>().first().action as PDActionURI).uri)
        }
        assertEquals(listOf(2, 2, 2), counts(src))
    }

    @Test fun pageFilterIntersectsRangeAndLinkContent() = runBlocking {
        val src = source()
        val context = android.content.ContextWrapper(null)
        val spec = BulkLinkSpec(pages = setOf(0, 2), allPages = false, linkContains = "example.com")
        assertEquals(setOf(0, 2), BulkLinkOps.selectedPages(context, src, null, spec))
        assertTrue(BulkLinkOps.selectedPages(context, src, null, spec.copy(linkContains = "missing.example")).isEmpty())
        assertEquals(setOf(0, 1, 2), BulkLinkOps.selectedPages(context, src, null, spec.copy(allPages = true)))
    }

    @Test fun bulkReplacementUsesThePageFilterAndProducesAReadableResult() = runBlocking {
        val src = source(); val out = tmp.newFile()
        val context = android.content.ContextWrapper(null)
        val spec = BulkLinkSpec(pages = setOf(2), allPages = false, target = "https://new.example", linkContains = "example.com")
        assertEquals(2, BulkLinkOps.apply(context, src, out, null, spec))
        PDDocument.load(out).use { doc ->
            assertTrue(doc.getPage(2).annotations.filterIsInstance<PDAnnotationLink>().all { (it.action as PDActionURI).uri == "https://new.example" })
            assertEquals("https://example.com", (doc.getPage(0).annotations.filterIsInstance<PDAnnotationLink>().first().action as PDActionURI).uri)
        }
    }

}

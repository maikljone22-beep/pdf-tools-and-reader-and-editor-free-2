package com.smartdocs.editor.core.gaps6

import com.smartdocs.editor.core.files.FileCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class Gaps6LogicTest {

    @Test fun tagLimit() {
        assertEquals(TagLimit.Decision.ATTACH, TagLimit.toggle(listOf(1L, 2L), 3L))
        assertEquals(TagLimit.Decision.DETACH, TagLimit.toggle(listOf(1L, 2L), 2L))
        val five = listOf(1L, 2L, 3L, 4L, 5L)
        assertEquals(TagLimit.Decision.LIMIT_REACHED, TagLimit.toggle(five, 6L))
        assertEquals(TagLimit.Decision.DETACH, TagLimit.toggle(five, 5L))
        assertTrue(TagLimit.canAdd(4)); assertFalse(TagLimit.canAdd(5))
        assertEquals(0, TagLimit.remaining(7)); assertEquals(5, TagLimit.remaining(0))
    }

    @Test fun referralCode() {
        val a = Referral.codeFor("install-a")
        assertEquals(8, a.length)
        assertEquals(a, Referral.codeFor("install-a"))              // stable
        assertNotEquals(a, Referral.codeFor("install-b"))
        assertTrue(Referral.isValid(a))
        assertTrue(a.none { it in "ILOU" })
        assertEquals("https://smartdocs.app/invite?ref=$a", Referral.link(a))
        assertEquals(a, Referral.parse(Referral.link(a)))
        assertEquals(a, Referral.parse(" ${a.lowercase()} "))
        assertNull(Referral.parse("https://smartdocs.app/invite?ref=XX"))
        assertEquals("01V00000", Referral.normalize("oiu0-0000"))
    }

    @Test fun referralRedeem() {
        val own = Referral.codeFor("me"); val friend = Referral.codeFor("friend")
        assertTrue(Referral.canRedeem(own, friend, null))
        assertFalse(Referral.canRedeem(own, own, null))
        assertFalse(Referral.canRedeem(own, friend, friend))
        assertFalse(Referral.canRedeem(own, "nope", null))
    }

    @Test fun foldLayout() {
        val phone = FoldLayout.Input(widthDp = 400)
        assertEquals(FoldLayout.Mode.SINGLE, FoldLayout.listDetail(phone))
        assertEquals(FoldLayout.Mode.TWO_PANE, FoldLayout.listDetail(FoldLayout.Input(widthDp = 900)))
        val book = FoldLayout.Input(widthDp = 700, hasFold = true, vertical = true, halfOpened = true, separating = true)
        assertEquals(FoldLayout.Mode.BOOK, FoldLayout.reader(book))
        assertEquals(FoldLayout.Mode.TWO_PANE, FoldLayout.listDetail(book))
        val tabletop = FoldLayout.Input(widthDp = 700, hasFold = true, vertical = false, halfOpened = true, separating = true)
        assertEquals(FoldLayout.Mode.TABLETOP, FoldLayout.reader(tabletop))
        val flatDual = FoldLayout.Input(widthDp = 700, hasFold = true, vertical = true, halfOpened = false, separating = true)
        assertEquals(FoldLayout.Mode.BOOK, FoldLayout.reader(flatDual))
        val flatSeamless = FoldLayout.Input(widthDp = 700, hasFold = true, vertical = true, halfOpened = false, separating = false)
        assertEquals(FoldLayout.Mode.SINGLE, FoldLayout.reader(flatSeamless))
        assertEquals(500 to 20, FoldLayout.splitAtHinge(0, 1020, 500, 520))
        assertEquals(400 to 20, FoldLayout.splitAtHinge(100, 1020, 500, 520))
        assertNull(FoldLayout.splitAtHinge(0, 1000, 50, 60))
    }

    @Test fun fileCopyNames() {
        val dir = Files.createTempDirectory("g6copy").toFile()
        try {
            File(dir, "a.pdf").writeText("x")
            assertEquals("a (2).pdf", FileCopy.freeName(dir, "a.pdf", false).name)
            File(dir, "a (2).pdf").writeText("x")
            assertEquals("a (3).pdf", FileCopy.freeName(dir, "a.pdf", false).name)
            File(dir, "folder").mkdirs()
            assertEquals("folder (2)", FileCopy.freeName(dir, "folder", true).name)
            assertEquals("new.txt", FileCopy.freeName(dir, "new.txt", false).name)

            val src = File(dir, "folder"); File(src, "inner.txt").writeText("hello")
            val dest = File(dir, "dest").apply { mkdirs() }
            val r = FileCopy.copyInto(listOf(src, File(dir, "a.pdf")), dest)
            assertEquals(2, r.copied.size)
            assertEquals("hello", File(dest, "folder/inner.txt").readText())
            assertTrue(File(src, "inner.txt").exists())                // original kept
            val again = FileCopy.copyInto(listOf(File(dir, "a.pdf")), dest)
            assertEquals("a (2).pdf", again.copied.single().name)
            val self = FileCopy.copyInto(listOf(src), File(src, "sub"))
            assertEquals(1, self.skipped.size)
            assertTrue(FileCopy.isInside(src, File(src, "x/y")))
            assertFalse(FileCopy.isInside(src, File(dir, "folder2")))
            val zip = File(dir, "pack.zip").apply { writeText("z") }
            assertEquals("pack", FileCopy.unzipTarget(zip).name)
            File(dir, "pack").mkdirs()
            assertEquals("pack (2)", FileCopy.unzipTarget(zip).name)
        } finally { dir.deleteRecursively() }
    }
}

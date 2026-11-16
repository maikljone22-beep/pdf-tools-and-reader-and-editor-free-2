package com.smartdocs.editor.core.files

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ThumbnailChoicesTest {
    @get:Rule val tmp = TemporaryFolder()
    @Test fun choiceSurvivesRecreationWithoutChangingDocument() {
        val root = tmp.newFolder("choices"); val src = tmp.newFile("doc.pdf").apply { writeText("original") }
        val image = File(root, "selected.jpg").apply { writeText("image") }
        ThumbnailChoices(root).replace(src, image)
        assertEquals(image.absolutePath, ThumbnailChoices(root).choice(src))
        assertEquals("original", src.readText())
    }
    @Test fun sameNamesInDifferentFoldersKeepSeparateChoices() {
        val root = tmp.newFolder("choices"); val store = ThumbnailChoices(root)
        val a = File(tmp.newFolder("a"), "same.pdf"); val b = File(tmp.newFolder("b"), "same.pdf")
        store.hide(a)
        assertEquals("", store.choice(a)); assertNull(store.choice(b))
    }
    @Test fun hidingAndRestoringAutomaticPreviewRemoveOnlyCustomImage() {
        val root = tmp.newFolder("choices"); val src = tmp.newFile("doc.pdf").apply { writeText("original") }
        val image = File(root, "selected.jpg").apply { writeText("image") }; val store = ThumbnailChoices(root)
        store.replace(src, image); store.hide(src)
        assertEquals("", store.choice(src)); assertFalse(image.exists()); assertTrue(src.exists())
        store.reset(src); assertNull(store.choice(src)); assertTrue(src.exists())
    }
    @Test fun customImageFollowsRenameAndReplacementCleansOldImage() {
        val root = tmp.newFolder("choices"); val store = ThumbnailChoices(root)
        val a = tmp.newFile("before.pdf"); val b = File(tmp.root, "after.pdf")
        val old = File(root, "old.jpg").apply { writeText("old") }; val image = File(root, "new.jpg").apply { writeText("new") }
        store.replace(a, old); store.relocate(a, b)
        assertNull(store.choice(a)); assertEquals(old.absolutePath, store.choice(b))
        store.replace(b, image); assertFalse(old.exists()); assertEquals(image.absolutePath, store.choice(b))
    }
}

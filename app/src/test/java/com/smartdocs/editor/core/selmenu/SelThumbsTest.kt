package com.smartdocs.editor.core.selmenu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** fix/thumbs-panel: the thumbnail long-press menu of the registry (Organize and the reader's thumbnails panel). */
class SelThumbsTest {
    private fun env(vararg blocked: Pair<Need, Why>, signed: Boolean = false) = SelEnv(SelTab.PAGES, SelCtx.THUMB, blocked = blocked.toMap(), signed = signed)
    private fun actions(items: List<SelItem>) = items.map { it.action }

    @Test fun actsOnTheSelectionOnlyWhenThePressedPageIsInIt() {
        assertEquals(listOf(1, 3, 5), SelThumbs.pages(3, setOf(5, 1, 3)))
        assertEquals(listOf(2), SelThumbs.pages(2, setOf(5, 1, 3)))
        assertEquals(listOf(0), SelThumbs.pages(0, emptySet()))
    }

    @Test fun offersEveryPageOperation() {
        val a = actions(SelThumbs.items(env(), 0, emptySet(), 5))
        for (x in listOf(SelAction.THUMB_OPEN, SelAction.THUMB_SELECT, SelAction.THUMB_ROTATE_LEFT, SelAction.THUMB_ROTATE_RIGHT, SelAction.THUMB_DUPLICATE,
                SelAction.THUMB_DELETE, SelAction.THUMB_EXTRACT, SelAction.THUMB_INSERT_BLANK, SelAction.THUMB_INSERT_FILE, SelAction.THUMB_REPLACE, SelAction.THUMB_PRINT))
            assertTrue(x.name, x in a)
        assertFalse(SelAction.THUMB_DESELECT in a)
        // only thumbnail items in this context, none of the text / page-area ones
        assertTrue(a.all { it.group == SelGroup.THUMB })
    }

    @Test fun selectBecomesDeselectAndDeleteNeverRemovesEveryPage() {
        val a = actions(SelThumbs.items(env(), 1, setOf(0, 1), 2))
        assertTrue(SelAction.THUMB_DESELECT in a); assertFalse(SelAction.THUMB_SELECT in a)
        assertFalse(SelAction.THUMB_DELETE in a)
        assertTrue(SelAction.THUMB_DELETE in actions(SelThumbs.items(env(), 1, setOf(0, 1), 3)))
    }

    @Test fun permissionsAndSignatureDisableWithReason() {
        val items = SelThumbs.items(env(Need.MODIFY to Why.AUTHOR, Need.COPY to Why.PROTECTED_VIEW), 0, emptySet(), 3)
        val by = items.associateBy { it.action }
        assertEquals(Why.AUTHOR, by[SelAction.THUMB_ROTATE_RIGHT]!!.why)
        assertEquals(Why.PROTECTED_VIEW, by[SelAction.THUMB_EXTRACT]!!.why)
        assertTrue(by[SelAction.THUMB_OPEN]!!.enabled); assertTrue(by[SelAction.THUMB_PRINT]!!.enabled)
        val signed = SelThumbs.items(env(signed = true), 0, emptySet(), 3).associateBy { it.action }
        assertEquals(Why.SIGNED, signed[SelAction.THUMB_DELETE]!!.why)
        assertTrue(signed[SelAction.THUMB_EXTRACT]!!.enabled)
    }

    @Test fun textContextsNeverShowThumbnailItems() {
        val m = SelMenus.build(SelEnv(SelTab.VIEW, SelCtx.TEXT, "words"))
        assertTrue(m.all.none { it.action.group == SelGroup.THUMB })
    }
}

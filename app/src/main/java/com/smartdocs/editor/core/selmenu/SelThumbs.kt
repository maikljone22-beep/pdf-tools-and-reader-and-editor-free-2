package com.smartdocs.editor.core.selmenu

/**
 * fix/thumbs-panel: the long-press menu of a page thumbnail, shared by the Organize grid and the reader's thumbnails
 * panel. It is the registry's [SelCtx.THUMB] context ([SelMenus.build]) plus the few rules that depend on the pages:
 * Select or Deselect for the pressed page, and no Delete when it would remove every page.
 */
object SelThumbs {
    /** The pages a menu item acts on: the whole selection when the pressed page is part of it, else the pressed page. */
    fun pages(pressed: Int, selected: Set<Int>): List<Int> = if (pressed in selected) selected.sorted() else listOf(pressed)

    /** Every item of the menu in order (disabled ones keep their reason). */
    fun items(env: SelEnv, pressed: Int, selected: Set<Int>, pageCount: Int): List<SelItem> {
        val acting = pages(pressed, selected)
        return SelMenus.build(env.copy(ctx = SelCtx.THUMB), SelMenus.BAR_TABLET).all.filter {
            when (it.action) {
                SelAction.THUMB_SELECT -> pressed !in selected
                SelAction.THUMB_DESELECT -> pressed in selected
                SelAction.THUMB_DELETE -> acting.size < pageCount
                else -> true
            }
        }
    }
}

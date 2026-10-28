package com.smartdocs.editor.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** tab-view-core E-11: each Edit tool asks for the permission it really needs. */
class EditToolPermissionsTest {
    @Test fun pageAssemblyTools() {
        listOf("e5_organize", "e5_page_ops", "e5_insert_blank", "e5_split_page", "e5_replace_pages", "e5_copy_pages", "e5_interleave", "e5_mix", "e5_impose")
            .forEach { assertEquals(it, DocAction.ASSEMBLE, ReaderRestrictions.actionForTool(it)) }
    }

    @Test fun viewersNeedNothing() {
        listOf("e5_xref", "e5_destinations", "e5_doc_props").forEach { assertNull(it, ReaderRestrictions.actionForTool(it)) }
    }

    @Test fun contentToolsModify() {
        listOf("e5_watermark", "e5_header_footer", "e5_bates", "e5_background", "content_edit").forEach { assertEquals(it, DocAction.MODIFY, ReaderRestrictions.actionForTool(it)) }
    }
}

package com.smartdocs.editor.core.tools5

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Scanner audit 3 (gaps/SCANNER_AUDIT_3.md): project manifests are not trusted blindly. */
class ScanAudit3Test {
    @Test fun manifestRejectsPathsAndBadCorners() {
        val text = "name=x\n" +
            "0|../../evil.jpg|AUTO|0.0|1.0|0||false||false|\n" +
            "0|sub/p.jpg|AUTO|0.0|1.0|0|false||false|\n" +
            "0|p0_0.jpg|AUTO|0.0|1.0|0|false||false|NaN,0,1,0,1,1,0,1\n" +
            "0|p0_1.jpg|AUTO|0.0|1.0|0|false||false|0.1,0.1,0.9,0.1,0.9,0.9,0.1,0.9\n"
        val (_, groups) = ScanProjects.decode(text)
        assertEquals(listOf("p0_0.jpg", "p0_1.jpg"), groups.single().map { it.first })
        assertNull(groups.single()[0].second.crop)
        assertEquals(8, groups.single()[1].second.crop!!.size)
    }
}

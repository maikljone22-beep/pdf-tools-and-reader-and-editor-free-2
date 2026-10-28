package com.smartdocs.editor.ui.edit

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** tab-view-core E-07: a linked box is found again after its text got longer, in both directions. */
class LinkBoxMatchTest {
    @Test fun ltrBoxKeepsItsLeftEdge() = assertTrue(LinkBoxMatch.same(100f, 700f, 300f, 101f, 702f, 360f))
    @Test fun rtlBoxKeepsItsRightEdge() = assertTrue(LinkBoxMatch.same(200f, 700f, 500f, 140f, 701f, 500f))
    @Test fun differentLine() = assertFalse(LinkBoxMatch.same(100f, 700f, 300f, 100f, 680f, 300f))
    @Test fun otherBox() = assertFalse(LinkBoxMatch.same(100f, 700f, 300f, 320f, 700f, 500f))
}

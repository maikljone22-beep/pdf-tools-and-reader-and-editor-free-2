package com.smartdocs.editor.core.selmenu

import com.smartdocs.editor.core.fillsign2.FsGeom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** fix/selmenu-rest: annotation / form-field / page-image menus of the registry, and "sign / image here" placement. */
class SelObjectsTest {

    private fun build(ctx: SelCtx, annot: AnnotFacts? = null, field: FieldFacts? = null, image: ImageFacts? = null,
                      vararg blocked: Pair<Need, Why>, signed: Boolean = false, clip: Boolean = false, tab: SelTab = SelTab.VIEW) =
        SelMenus.build(SelEnv(tab, ctx, "", blocked.toMap(), signed, true, clip, false, annot = annot, field = field, image = image), Int.MAX_VALUE)

    private fun actions(m: SelMenuModel) = m.all.map { it.action }.toSet()

    // ---------------------------------------------------------------- annotation

    @Test fun annotationMenuKeepsEveryActionOfTheOldBar() {
        // a stored free-text comment: colour, opacity, edit text, text format, copy, delete + the More items
        val a = actions(build(SelCtx.ANNOT, AnnotFacts(editText = true, textFormat = true, stored = true, canPaste = true)))
        for (x in listOf(SelAction.A_COLOR, SelAction.A_OPACITY, SelAction.A_EDIT_TEXT, SelAction.A_TEXT_FORMAT, SelAction.A_COPY, SelAction.A_DELETE,
            SelAction.A_PROPS, SelAction.A_LOCK, SelAction.A_CUT, SelAction.A_CLIP_COPY, SelAction.A_PASTE_IN_PLACE, SelAction.A_ROTATE_LEFT,
            SelAction.A_ROTATE_RIGHT, SelAction.A_ROTATE_CUSTOM, SelAction.A_PLACE_PAGES, SelAction.A_FLATTEN, SelAction.A_EXPORT_XFDF,
            SelAction.A_EXPORT_FDF, SelAction.A_EMAIL_FDF, SelAction.A_REPLY)) assertTrue("$x missing", x in a)
        assertFalse(SelAction.A_UNLOCK in a); assertFalse(SelAction.A_THICKNESS in a); assertFalse(SelAction.A_VIDEO_PLAY in a)
    }

    @Test fun annotationKindsAddTheirOwnItems() {
        val stamp = actions(build(SelCtx.ANNOT, AnnotFacts(color = false, stamp = true, sigDefaultSize = true, imageProps = true)))
        assertTrue(stamp.containsAll(listOf(SelAction.A_STAMP_CUT, SelAction.A_STAMP_COPY, SelAction.A_STAMP_DEFAULT_SIZE, SelAction.A_STAMP_PAGES, SelAction.A_IMAGE_PROPS)))
        assertFalse(SelAction.A_COLOR in stamp)
        val onePage = actions(build(SelCtx.ANNOT, AnnotFacts(stamp = true, multiPage = false)))
        assertFalse(SelAction.A_STAMP_PAGES in onePage)
        val video = actions(build(SelCtx.ANNOT, AnnotFacts(color = false, opacity = false, video = true)))
        assertTrue(video.containsAll(listOf(SelAction.A_VIDEO_PLAY, SelAction.A_VIDEO_OPTIONS, SelAction.A_VIDEO_SHARE, SelAction.A_VIDEO_SAVE)))
        assertFalse(SelAction.A_OPACITY in video)
        val ink = actions(build(SelCtx.ANNOT, AnnotFacts(stroke = true, ink = true, locked = true)))
        assertTrue(SelAction.A_RECOGNIZE in ink && SelAction.A_THICKNESS in ink && SelAction.A_UNLOCK in ink); assertFalse(SelAction.A_LOCK in ink)
        val markup = actions(build(SelCtx.ANNOT, AnnotFacts(markup = true)))
        assertTrue(markup.containsAll(listOf(SelAction.A_RANGE_START_LESS, SelAction.A_RANGE_START_MORE, SelAction.A_RANGE_END_LESS, SelAction.A_RANGE_END_MORE)))
        assertTrue(SelAction.A_OPEN_FILE in actions(build(SelCtx.ANNOT, AnnotFacts(attachment = true))))
        assertTrue(SelAction.A_PLAY in actions(build(SelCtx.ANNOT, AnnotFacts(playable = true))))
        assertFalse(SelAction.A_REPLY in actions(build(SelCtx.ANNOT, AnnotFacts(stored = false))))
        assertTrue(SelAction.A_FLOAT_NOTE in actions(build(SelCtx.ANNOT, AnnotFacts(editText = true, floatNote = true))))
        assertFalse(SelAction.A_FLOAT_NOTE in actions(build(SelCtx.ANNOT, AnnotFacts(editText = true, textFormat = true))))
    }

    @Test fun annotationRowAndMoreAreSplitAsBefore() {
        val m = build(SelCtx.ANNOT, AnnotFacts(stored = true))
        m.bar.forEach { assertFalse("${it.action} belongs to More", it.action in SelObjects.ANNOT_MORE) }
        assertEquals(SelAction.A_COLOR, m.bar.first().action)
        assertTrue(m.all.count { it.action in SelObjects.ANNOT_MORE } >= 13)
    }

    @Test fun protectedAnnotationsAreGreyedWithReasonCopyStays() {
        val m = build(SelCtx.ANNOT, AnnotFacts(), null, null, Need.ANNOTATE to Why.AUTHOR)
        assertEquals(Why.AUTHOR, m.item(SelAction.A_DELETE)!!.why)
        assertFalse(m.item(SelAction.A_COLOR)!!.enabled)
        assertTrue(m.item(SelAction.A_COPY)!!.enabled)
        assertEquals(Why.SIGNED, build(SelCtx.ANNOT, AnnotFacts(), signed = true).item(SelAction.A_FLATTEN)!!.why)
        assertTrue(build(SelCtx.ANNOT, AnnotFacts(), signed = true).item(SelAction.A_COLOR)!!.enabled)
    }

    // ---------------------------------------------------------------- form field

    @Test fun filledTextFieldMenu() {
        val m = build(SelCtx.FIELD, field = FieldFacts(FieldKind.TEXT, hasValue = true, changed = true, hasCalculations = true), clip = true)
        assertEquals(listOf(SelAction.F_COPY_VALUE, SelAction.F_PASTE, SelAction.F_CLEAR, SelAction.F_RESET), m.bar.take(4).map { it.action })
        val a = actions(m)
        assertTrue(a.containsAll(listOf(SelAction.F_SPELL, SelAction.F_CALCULATE, SelAction.F_PROPS, SelAction.F_DESIGNER, SelAction.F_DUPLICATE, SelAction.F_DELETE, SelAction.F_TAB_ORDER)))
        assertFalse(SelAction.F_SIGN in a); assertFalse(SelAction.F_PICK_IMAGE in a)
    }

    @Test fun fieldRulesByKindAndState() {
        val empty = actions(build(SelCtx.FIELD, field = FieldFacts(FieldKind.TEXT)))
        assertFalse(SelAction.F_COPY_VALUE in empty); assertFalse(SelAction.F_CLEAR in empty); assertFalse(SelAction.F_RESET in empty); assertFalse(SelAction.F_SPELL in empty)
        assertEquals(Why.EMPTY_CLIPBOARD, build(SelCtx.FIELD, field = FieldFacts(FieldKind.TEXT)).item(SelAction.F_PASTE)!!.why)
        val ro = actions(build(SelCtx.FIELD, field = FieldFacts(FieldKind.TEXT, readOnly = true, hasValue = true)))
        assertTrue(SelAction.F_COPY_VALUE in ro); assertFalse(SelAction.F_PASTE in ro); assertFalse(SelAction.F_CLEAR in ro)
        val calc = actions(build(SelCtx.FIELD, field = FieldFacts(FieldKind.TEXT, calculated = true, hasValue = true)))
        assertFalse(SelAction.F_CLEAR in calc); assertTrue(SelAction.F_COPY_VALUE in calc)
        val check = actions(build(SelCtx.FIELD, field = FieldFacts(FieldKind.CHECK, hasValue = true)))
        assertTrue(SelAction.F_CLEAR in check); assertFalse(SelAction.F_PASTE in check); assertFalse(SelAction.F_SPELL in check)
        val img = actions(build(SelCtx.FIELD, field = FieldFacts(FieldKind.IMAGE)))
        assertTrue(SelAction.F_PICK_IMAGE in img); assertFalse(SelAction.F_COPY_VALUE in img)
        val sig = actions(build(SelCtx.FIELD, field = FieldFacts(FieldKind.SIGNATURE, hasValue = true)))
        assertTrue(SelAction.F_SIGN in sig); assertFalse(SelAction.F_CLEAR in sig); assertFalse(SelAction.F_DELETE in sig)
        assertFalse(SelAction.F_CALCULATE in actions(build(SelCtx.FIELD, field = FieldFacts(FieldKind.TEXT))))
    }

    @Test fun fieldPermissions() {
        val noFill = build(SelCtx.FIELD, null, FieldFacts(FieldKind.TEXT, hasValue = true, changed = true), null, Need.FILL to Why.AUTHOR, clip = true)
        assertFalse(noFill.item(SelAction.F_CLEAR)!!.enabled); assertEquals(Why.AUTHOR, noFill.item(SelAction.F_PASTE)!!.why)
        assertEquals(SelAction.F_COPY_VALUE, noFill.bar.first().action)
        val signed = build(SelCtx.FIELD, field = FieldFacts(FieldKind.TEXT, hasValue = true), signed = true)
        assertEquals(Why.SIGNED, signed.item(SelAction.F_DESIGNER)!!.why)
        assertTrue(signed.item(SelAction.F_CLEAR)!!.enabled)     // filling stays an incremental change
    }

    // ---------------------------------------------------------------- page image (content edit)

    @Test fun pictureAndShapeMenus() {
        val pic = build(SelCtx.IMAGE, image = ImageFacts(), tab = SelTab.EDIT)
        assertEquals(SelAction.I_COPY, pic.bar.first().action)
        assertTrue(actions(pic).containsAll(listOf(SelAction.I_SAVE, SelAction.I_SHARE, SelAction.I_REPLACE, SelAction.I_CROP, SelAction.I_ROTATE_LEFT,
            SelAction.I_ROTATE_RIGHT, SelAction.I_FLIP_H, SelAction.I_FLIP_V, SelAction.I_EDITOR, SelAction.I_PROPS, SelAction.I_DELETE, SelAction.I_FRONT,
            SelAction.I_BACK, SelAction.I_DUPLICATE, SelAction.I_CUT)))
        val shape = actions(build(SelCtx.IMAGE, image = ImageFacts(vector = true)))
        assertFalse(SelAction.I_SAVE in shape); assertFalse(SelAction.I_REPLACE in shape); assertTrue(SelAction.I_COPY in shape)
        val shading = actions(build(SelCtx.IMAGE, image = ImageFacts(vector = true, shading = true)))
        assertFalse(SelAction.I_COPY in shading); assertFalse(SelAction.I_CUT in shading); assertTrue(SelAction.I_DELETE in shading)
        val signed = build(SelCtx.IMAGE, image = ImageFacts(), signed = true)
        assertEquals(Why.SIGNED, signed.item(SelAction.I_DELETE)!!.why); assertTrue(signed.item(SelAction.I_COPY)!!.enabled)
        assertNull(signed.bar.firstOrNull { it.action == SelAction.I_DELETE })
    }

    // ---------------------------------------------------------------- sign / insert image at the pressed point

    @Test fun stampBoxInsideIsCentredOnThePoint() {
        val b = FsGeom.PageBox.plain(600f, 800f)
        val box = FsGeom.stampBoxInside(b, 300f, 400f, 100f, 50f)
        assertEquals(250f, box.left, 0.01f); assertEquals(350f, box.right, 0.01f); assertEquals(375f, box.bottom, 0.01f); assertEquals(425f, box.top, 0.01f)
    }

    @Test fun stampBoxInsideStaysOnThePageNearAnEdge() {
        val b = FsGeom.PageBox.plain(600f, 800f)
        val box = FsGeom.stampBoxInside(b, 590f, 5f, 100f, 50f)
        assertEquals(500f, box.left, 0.01f); assertEquals(600f, box.right, 0.01f); assertEquals(0f, box.bottom, 0.01f); assertEquals(50f, box.top, 0.01f)
    }

    @Test fun stampBoxInsideOnARotatedCroppedPage() {
        // /Rotate 90, crop box at (10, 20) 600 x 800: displayed 800 wide, 600 high; the upright 100 x 50 image turns with it
        val b = FsGeom.PageBox(90, 10f, 20f, 600f, 800f)
        val (ux, uy) = FsGeom.fromDisplay(b, 400f, 300f)
        val box = FsGeom.stampBoxInside(b, ux, uy, 100f, 50f)
        assertEquals(50f, box.right - box.left, 0.01f); assertEquals(100f, box.top - box.bottom, 0.01f)
        val r = FsGeom.userToDisplayRect(b, box)
        assertEquals(350f, r[0], 0.01f); assertEquals(275f, r[1], 0.01f); assertEquals(450f, r[2], 0.01f); assertEquals(325f, r[3], 0.01f)
        // pressed at the very corner: kept inside the visible crop box
        val (cx, cy) = FsGeom.fromDisplay(b, 799f, 599f)
        val c = FsGeom.userToDisplayRect(b, FsGeom.stampBoxInside(b, cx, cy, 100f, 50f))
        assertTrue(c[2] <= 800.01f && c[3] <= 600.01f && c[0] >= -0.01f && c[1] >= -0.01f)
        assertNotNull(c)
    }
}

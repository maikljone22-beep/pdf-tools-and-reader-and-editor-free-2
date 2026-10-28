package com.smartdocs.editor.core.forms5

import org.junit.Assert.assertEquals
import org.junit.Test

/** fix/pdf39-forms-2: fill-time named actions (8.69). */
class NamedActionsTest {
    @Test fun namesResolve() {
        assertEquals(NamedOp.NEXT, NamedActions.resolve("NextPage"))
        assertEquals(NamedOp.FIND, NamedActions.resolve("AcroSrch:Query"))
        assertEquals(NamedOp.ZOOM_IN, NamedActions.resolve("zoomviewin"))
        assertEquals(NamedOp.FIT_PAGE, NamedActions.resolve("FitPage"))
        assertEquals(NamedOp.FIT_WIDTH, NamedActions.resolve("FitWidth"))
        assertEquals(NamedOp.FULL_SCREEN, NamedActions.resolve("FullScreen"))
        assertEquals(NamedOp.TWO_COLUMNS, NamedActions.resolve("TwoColumns"))
        assertEquals(NamedOp.UNSUPPORTED, NamedActions.resolve("GeneralInfo"))
    }

    @Test fun zoomSteps() {
        assertEquals(1.25f, NamedActions.zoomStep(1f, true, 0.25f, 32f), 0.0001f)
        assertEquals(0.8f, NamedActions.zoomStep(1f, false, 0.25f, 32f), 0.0001f)
        assertEquals(32f, NamedActions.zoomStep(30f, true, 0.25f, 32f), 0f)
    }

    @Test fun scrollPointToFraction() {
        assertEquals(0f, NamedActions.scrollFraction(792f, 792f), 0f)        // top of the page
        assertEquals(0.75f, NamedActions.scrollFraction(198f, 792f), 0.0001f)
        assertEquals(1f, NamedActions.scrollFraction(-5f, 792f), 0f)
        assertEquals(0f, NamedActions.scrollFraction(10f, 0f), 0f)
    }
}

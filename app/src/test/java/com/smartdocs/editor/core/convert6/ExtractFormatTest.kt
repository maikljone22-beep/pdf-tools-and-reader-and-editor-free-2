package com.smartdocs.editor.core.convert6

import org.junit.Assert.assertEquals
import org.junit.Test

/** fix/pdf39-convert-2: Extract Images output format (s6). */
class ExtractFormatTest {
    @Test fun targetExtension() {
        assertEquals("jp2", ImageExtract6.targetExt(null, "jp2"))
        assertEquals("png", ImageExtract6.targetExt(ImgFmt.PNG, "jpg"))
        assertEquals("webp", ImageExtract6.targetExt(ImgFmt.WEBP, "tif"))
        assertEquals("jpg", ImageExtract6.targetExt(ImgFmt.JPG, "png"))
    }
}

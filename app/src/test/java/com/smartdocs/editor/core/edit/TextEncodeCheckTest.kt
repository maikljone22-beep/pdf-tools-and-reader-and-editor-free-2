package com.smartdocs.editor.core.edit

import com.tom_roush.pdfbox.pdmodel.font.NveTestFont
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** audit 5 C-7: characters no font can draw are reported before Find & replace writes (they used to vanish). */
class TextEncodeCheckTest {

    private val font: PDFont = NveTestFont()

    /** A "font set" that draws ASCII letters / digits / punctuation and Arabic, nothing else. */
    private val fontFor: (Char) -> PDFont? = { c -> if (c.code in 0x21..0x7E || c in '؀'..'ۿ') font else null }

    @Test fun encodableTextHasNoMissingCharacters() {
        assertTrue(unencodableChars("Hello, world 123", fontFor).isEmpty())
        assertTrue(unencodableChars("مرحبا بالعالم", fontFor).isEmpty())
    }

    @Test fun cjkAndEmojiAreReportedOnce() {
        val m = unencodableChars("abc 漢字漢 😀", fontFor)
        assertTrue('漢' in m); assertTrue('字' in m)
        // the emoji is a surrogate pair: both halves are unencodable
        assertEquals(4, m.size)
    }

    @Test fun whitespaceAndControlsAreIgnored() {
        assertTrue(unencodableChars(" \t\n\u0000", fontFor).isEmpty())
    }
}

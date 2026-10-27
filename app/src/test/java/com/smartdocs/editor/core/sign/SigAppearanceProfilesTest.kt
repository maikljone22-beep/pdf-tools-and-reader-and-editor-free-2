package com.smartdocs.editor.core.sign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** nitro-quicksign: appearance font cap encoding and named appearance profiles. */
class SigAppearanceProfilesTest {
    private val a = SigAppearanceSpec(showDn = true, logoPath = "/data/logo|x.png", layout = SigAppearanceSpec.Layout.LOGO_TOP, maxFontSize = 24)
    private val b = SigAppearanceSpec(showLabels = false)

    @Test fun specRoundTripWithFontCap() {
        assertEquals(a, SigAppearanceSpec.decode(a.encode()))
        assertEquals(b, SigAppearanceSpec.decode(b.encode()))
    }

    @Test fun oldSpecWithoutFontDecodesAsAuto() {
        val old = "111101|LOGO_RIGHT|/p.png"
        val d = SigAppearanceSpec.decode(old)
        assertEquals(SigAppearanceSpec.AUTO_FONT, d.maxFontSize)
        assertEquals(SigAppearanceSpec.Layout.LOGO_RIGHT, d.layout)
        assertEquals("/p.png", d.logoPath)
    }

    @Test fun fontCapLimitsSizes() {
        assertEquals(SigAppearanceSpec.NAME_SIZE, SigAppearanceSpec().fontSize(0), 0f)
        assertEquals(24f, a.fontSize(0), 0f)
        assertEquals(24f, a.fontSize(3), 0f)
        assertTrue(a.lineHeight(3) < SigAppearanceSpec().lineHeight(3))
    }

    @Test fun profilesRoundTrip() {
        val list = SigAppearanceProfiles.save(SigAppearanceProfiles.save(emptyList(), "Work", a), "Plain \"quoted\"", b)
        assertEquals(list, SigAppearanceProfiles.decode(SigAppearanceProfiles.encode(list)))
        assertEquals(emptyList<SigAppearanceProfiles.Profile>(), SigAppearanceProfiles.decode("not json"))
        assertEquals(emptyList<SigAppearanceProfiles.Profile>(), SigAppearanceProfiles.decode(null))
    }

    @Test fun saveReplacesSameNameIgnoringCase() {
        val list = SigAppearanceProfiles.save(SigAppearanceProfiles.save(emptyList(), "Work", a), "work", b)
        assertEquals(1, list.size)
        assertEquals(b, list[0].spec)
    }

    @Test fun renameDuplicateDelete() {
        var list = SigAppearanceProfiles.save(SigAppearanceProfiles.save(emptyList(), "Work", a), "Home", b)
        assertEquals(list, SigAppearanceProfiles.rename(list, "Work", "home"))   // name taken
        list = SigAppearanceProfiles.rename(list, "Work", "Office")
        assertEquals(listOf("Office", "Home"), list.map { it.name })
        list = SigAppearanceProfiles.duplicate(list, "Office", "copy")
        list = SigAppearanceProfiles.duplicate(list, "Office", "copy")
        assertEquals(listOf("Office", "Office (copy 2)", "Office (copy)", "Home"), list.map { it.name })
        assertEquals(a, list[1].spec)
        list = SigAppearanceProfiles.delete(list, "office (COPY)")
        assertEquals(listOf("Office", "Office (copy 2)", "Home"), list.map { it.name })
    }
}

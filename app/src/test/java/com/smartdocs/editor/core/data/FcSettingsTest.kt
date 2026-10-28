package com.smartdocs.editor.core.data

import com.smartdocs.editor.core.convert6.Cv6Defaults
import com.smartdocs.editor.core.convert6.Cv6Opts
import com.smartdocs.editor.core.convert6.Fit6
import com.smartdocs.editor.core.convert6.ImgFmt
import com.smartdocs.editor.core.convert6.Orient6
import com.smartdocs.editor.core.convert6.PageSetup
import com.smartdocs.editor.core.convert6.PageSize6
import com.smartdocs.editor.core.gaps6.Referral
import com.smartdocs.editor.core.util.DebugLog
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** fc-settings: app language, conversion defaults, merged recent size, invite links, debug-log trimming. */
class FcSettingsTest {

    @Test fun languageNormalizeAndMigrate() {
        assertEquals("ar", AppLang.normalize("ar"))
        assertEquals("en", AppLang.normalize("en"))
        assertEquals("system", AppLang.normalize(null))
        assertEquals("system", AppLang.normalize("fr"))
        // the old key defaulted to "ar" and was written with every change: only an explicit "en" is a real choice
        assertEquals("system", AppLang.migrate("ar"))
        assertEquals("system", AppLang.migrate(null))
        assertEquals("en", AppLang.migrate("en"))
    }

    @Test fun languageResolve() {
        assertEquals("ar", AppLang.resolve("system", Locale("ar", "SA")))
        assertEquals("en", AppLang.resolve("system", Locale.GERMANY))
        assertEquals("en", AppLang.resolve("en", Locale("ar")))
        assertEquals("ar", AppLang.resolve("ar", Locale.US))
    }

    @Test fun convDefaultsRoundTrip() {
        val o = Cv6Opts(range = "1-3", url = "https://x", text = "t", autoOcr = false, keepLayout = false, imgFormat = null, dpi = 300, quality = 70,
            zip = false, gray = true, setup = PageSetup(PageSize6.LETTER, Orient6.LANDSCAPE, Fit6.COVER, 36f), merge = false, nUp = 6,
            ocrLangs = "ara", pdfaStrict = true, attachSource = true, bw = true, perTable = false)
        val back = Cv6Defaults.decode(JSONObject(Cv6Defaults.encode(o).toString()), Cv6Opts())
        // per-run values are never saved
        assertEquals(o.copy(range = "", url = "", text = ""), back)
    }

    @Test fun convDefaultsKeepBaseForMissingOrBadKeys() {
        val base = Cv6Opts(imgFormat = ImgFmt.PNG, quality = 90, keepLayout = true)
        val j = JSONObject().put("dpi", 72).put("size", "NOPE").put("ocrLangs", "auto")
        val r = Cv6Defaults.decode(j, base)
        assertEquals(72, r.dpi)
        assertEquals(ImgFmt.PNG, r.imgFormat)
        assertEquals(90, r.quality)
        assertEquals(true, r.keepLayout)
        assertEquals(base.setup.size, r.setup.size)
        assertNull(r.ocrLangs)
        assertEquals(ImgFmt.WEBP, Cv6Defaults.decode(JSONObject().put("imgFormat", "WEBP"), base).imgFormat)
        assertNull(Cv6Defaults.decode(JSONObject().put("keepLayout", "auto"), base).keepLayout)
    }

    @Test fun recentSizeMerge() {
        assertEquals(0, NpfPrefs.mergedRecentLimit(50))
        assertEquals(20, NpfPrefs.mergedRecentLimit(20))
        assertEquals(5, NpfPrefs.mergedRecentLimit(5))
        assertTrue(0 in NpfPrefs.RECENT_CHOICES)
    }

    @Test fun inviteLinks() {
        assertTrue(Referral.isInviteLink("https", "smartdocs.app", "/invite"))
        assertTrue(Referral.isInviteLink("https", "SmartDocs.app", "/invite/"))
        assertFalse(Referral.isInviteLink("https", "evil.app", "/invite"))
        assertFalse(Referral.isInviteLink("https", "smartdocs.app", "/invited"))
        assertFalse(Referral.isInviteLink("content", "smartdocs.app", "/invite"))
    }

    @Test fun debugLogTrim() {
        val text = (1..100).joinToString("\n") { "line $it" } + "\n"
        val t = DebugLog.trimmed(text, 200)
        assertTrue(t.length <= 100)
        assertTrue(t.startsWith("line "))
        assertTrue(t.endsWith("line 100\n"))
        assertEquals("short", DebugLog.trimmed("short", 200))
        assertTrue(DebugLog.line(0L, "tag", "a\r\nb").contains("[tag] a\nb"))
    }
}

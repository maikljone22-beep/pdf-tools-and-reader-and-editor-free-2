package com.smartdocs.editor.core.selmenu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** feature/selection-menus: registry rules per tab / context, guard / signed / AI-off, entity detection, Arabic copy. */
class SelMenuTest {

    private fun env(tab: SelTab = SelTab.VIEW, ctx: SelCtx = SelCtx.TEXT, text: String = "some selected words", vararg blocked: Pair<Need, Why>,
                    signed: Boolean = false, ai: Boolean = true, clip: Boolean = false, stamp: Boolean = false, web: Boolean = true) =
        SelEnv(tab, ctx, text, blocked.toMap(), signed, ai, clip, stamp, linkIsWeb = web)

    private fun bar(e: SelEnv, size: Int = SelMenus.BAR_PHONE) = SelMenus.build(e, size).bar.map { it.action }

    // ---------------------------------------------------------------- per tab

    @Test fun viewTabLeadsWithCopyAndHighlight() {
        val b = bar(env())
        assertEquals(SelAction.COPY, b[0]); assertEquals(SelAction.HIGHLIGHT, b[1]); assertEquals(4, b.size)
    }

    @Test fun commentAndCustomTabsLeadWithMarkup() {
        for (t in listOf(SelTab.COMMENT, SelTab.CUSTOM)) assertEquals(listOf(SelAction.HIGHLIGHT, SelAction.UNDERLINE, SelAction.STRIKEOUT, SelAction.SQUIGGLY), bar(env(t)))
        assertEquals(SelGroup.MARKUP, SelMenus.build(env(SelTab.COMMENT)).more[0].group)
    }

    @Test fun editTabLeadsWithEditText() {
        val b = bar(env(SelTab.EDIT), SelMenus.BAR_TABLET)
        assertEquals(SelAction.EDIT_TEXT, b[0]); assertTrue(SelAction.REDACT in b); assertTrue(SelAction.CREATE_LINK in b)
    }

    @Test fun toolsTabOffersRedactAndWordCount() {
        val b = bar(env(SelTab.TOOLS), SelMenus.BAR_TABLET)
        assertTrue(SelAction.REDACT in b); assertTrue(SelAction.WORD_COUNT in b)
    }

    @Test fun everyTabAndContextHasAConsistentMenu() {
        for (t in SelTab.entries) for (c in SelCtx.entries) for (size in listOf(SelMenus.BAR_PHONE, SelMenus.BAR_TABLET)) {
            val m = SelMenus.build(env(t, c, clip = true, stamp = true), size)
            assertTrue("$t/$c bar empty", m.bar.isNotEmpty())
            assertTrue("$t/$c bar too long", m.bar.size <= size)
            m.bar.forEach { assertTrue("$t/$c ${it.action} disabled in bar", it.enabled); assertNotNull("$t/$c ${it.action} not in More", m.item(it.action)) }
            m.all.forEach { assertTrue("$t/$c ${it.action} wrong context", c in it.action.contexts) }
            assertEquals("$t/$c duplicate", m.all.size, m.all.map { it.action }.toSet().size)
        }
    }

    @Test fun tabletBarIsLonger() {
        assertEquals(SelMenus.BAR_TABLET, bar(env(), SelMenus.BAR_TABLET).size)
    }

    // ---------------------------------------------------------------- guard / signed / AI switch

    @Test fun copyRestrictedByAuthorIsShownDisabledWithReasonAndLeavesTheBar() {
        val m = SelMenus.build(env(SelTab.VIEW, SelCtx.TEXT, "words here", Need.COPY to Why.AUTHOR))
        val copy = m.item(SelAction.COPY)!!
        assertFalse(copy.enabled); assertEquals(Why.AUTHOR, copy.why)
        assertFalse(SelAction.COPY in m.bar.map { it.action })
        assertEquals(SelAction.HIGHLIGHT, m.bar[0].action)
        // everything that sends the text out follows the copy permission
        for (a in listOf(SelAction.SHARE, SelAction.WEB_SEARCH, SelAction.AI_TRANSLATE, SelAction.EXPORT_TXT, SelAction.OTHER_APPS)) assertFalse(a.name, m.item(a)!!.enabled)
        assertTrue(m.item(SelAction.READ_ALOUD)!!.enabled)
    }

    @Test fun protectedViewDisablesMarkup() {
        val m = SelMenus.build(env(SelTab.COMMENT, SelCtx.TEXT, "abc def", Need.ANNOTATE to Why.PROTECTED_VIEW, Need.MODIFY to Why.PROTECTED_VIEW))
        assertEquals(Why.PROTECTED_VIEW, m.item(SelAction.HIGHLIGHT)!!.why)
        assertFalse(m.bar.any { it.action.group == SelGroup.MARKUP })
        assertEquals(SelAction.COPY, m.bar[0].action)
    }

    @Test fun signedDocumentRefusesContentChangesButKeepsComments() {
        val m = SelMenus.build(env(SelTab.EDIT, signed = true), SelMenus.BAR_TABLET)
        for (a in listOf(SelAction.EDIT_TEXT, SelAction.REDACT, SelAction.CREATE_LINK, SelAction.PDF_BOOKMARK)) assertEquals(a.name, Why.SIGNED, m.item(a)!!.why)
        assertTrue(m.item(SelAction.HIGHLIGHT)!!.enabled)
        assertFalse(SelAction.EDIT_TEXT in m.bar.map { it.action })
    }

    @Test fun aiOffHidesEveryAiItem() {
        for (t in SelTab.entries) {
            val m = SelMenus.build(env(t, ai = false), SelMenus.BAR_TABLET)
            assertTrue(m.all.none { it.action.ai }); assertTrue(m.more.none { it.group == SelGroup.AI })
        }
        // the device's own translate apps stay available
        assertNotNull(SelMenus.build(env(ai = false)).item(SelAction.OTHER_APPS))
    }

    // ---------------------------------------------------------------- contexts

    @Test fun wordContextOffersSentence() {
        assertNotNull(SelMenus.build(env(ctx = SelCtx.WORD, text = "word")).item(SelAction.SELECT_SENTENCE))
        assertNull(SelMenus.build(env(ctx = SelCtx.TEXT)).item(SelAction.SELECT_SENTENCE))
    }

    @Test fun emptyAreaMenu() {
        val m = SelMenus.build(env(SelTab.COMMENT, SelCtx.EMPTY, ""))
        assertNull(m.item(SelAction.COPY)); assertNull(m.item(SelAction.PASTE_STAMP))
        assertEquals(Why.EMPTY_CLIPBOARD, m.item(SelAction.PASTE)!!.why)
        assertEquals(SelAction.NOTE_HERE, m.bar[0].action)
        assertNotNull(SelMenus.build(env(SelTab.COMMENT, SelCtx.EMPTY, "", stamp = true)).item(SelAction.PASTE_STAMP))
        assertEquals(SelAction.SIGN_HERE, bar(env(SelTab.SIGN, SelCtx.EMPTY, ""))[0])
        assertNull(SelMenus.build(env(SelTab.VIEW, SelCtx.EMPTY, "")).item(SelAction.SIGN_HERE))
        assertEquals(Why.AUTHOR, SelMenus.build(env(SelTab.VIEW, SelCtx.EMPTY, "", Need.PRINT to Why.AUTHOR)).item(SelAction.PRINT)!!.why)
    }

    @Test fun linkMenu() {
        assertEquals(listOf(SelAction.LINK_OPEN, SelAction.LINK_COPY, SelAction.LINK_SHARE), bar(env(ctx = SelCtx.LINK, text = "https://a.com")))
        assertEquals(listOf(SelAction.LINK_OPEN), bar(env(ctx = SelCtx.LINK, text = "", web = false)))
    }

    @Test fun phoneSelectionPutsCallFirst() {
        val m = SelMenus.build(env(text = "اتصل على ٧٧١٢٣٤٥٦٧"))
        assertEquals(SelAction.CALL, m.bar[0].action)
        assertEquals("771234567", m.bar[0].entity!!.value)
        assertNull(m.item(SelAction.WHATSAPP))       // no country code: wa.me cannot open it
        assertEquals(SelGroup.SMART, m.more[0].group)
        val intl = SelMenus.build(env(text = "+٩٦٧ ٧٧١ ٢٣٤ ٥٦٧"))
        assertNotNull(intl.item(SelAction.WHATSAPP))
        assertEquals("https://wa.me/967771234567", SelEntities.whatsAppUri(intl.item(SelAction.WHATSAPP)!!.entity!!))
    }

    @Test fun smartActionsFollowTheCopyPermission() {
        val m = SelMenus.build(env(SelTab.VIEW, SelCtx.TEXT, "call 0771234567", Need.COPY to Why.AUTHOR))
        assertFalse(m.item(SelAction.CALL)!!.enabled)
        assertFalse(m.bar.any { it.action.group == SelGroup.SMART })
    }

    @Test fun arabicCopyVariantsOnlyWhenUseful() {
        val withHarakat = SelMenus.build(env(text = "مُحَمَّدٌ رَسُولُ"))
        assertNotNull(withHarakat.item(SelAction.COPY_NO_DIACRITICS))
        val latin = SelMenus.build(env(text = "plain english 123"))
        assertNull(latin.item(SelAction.COPY_NO_DIACRITICS)); assertNull(latin.item(SelAction.COPY_NORMALIZED))
        assertNull(latin.item(SelAction.COPY_LATIN_DIGITS)); assertNull(latin.item(SelAction.COPY_ARABIC_DIGITS))
        assertNotNull(SelMenus.build(env(text = "الصفحة ١٢")).item(SelAction.COPY_LATIN_DIGITS))
        assertNotNull(SelMenus.build(env(text = "الصفحة 12")).item(SelAction.COPY_ARABIC_DIGITS))
        assertNotNull(SelMenus.build(env(text = "أحمد وإبراهيم")).item(SelAction.COPY_NORMALIZED))
    }

    // ---------------------------------------------------------------- entities

    private fun one(text: String): SelEntity = SelEntities.detect(text).single()

    @Test fun phones() {
        assertEquals("771234567", one("هاتف: ٧٧١٢٣٤٥٦٧").value)
        assertEquals("0771234567", one("۰۷۷۱۲۳۴۵۶۷").value)                       // Persian digits
        assertEquals("+967771234567", one("00967771234567").value)
        val e = one("+967 (1) 234-567")
        assertEquals(EntityKind.PHONE, e.kind); assertEquals("+9671234567", e.value); assertTrue(e.international)
        assertEquals("+967 (1) 234-567", e.text)
        assertTrue(SelEntities.detect("رقم ١٢٣").none { it.kind == EntityKind.PHONE })
    }

    @Test fun emailsAndUrls() {
        assertEquals("info@example.com", one("راسلنا info@example.com").value)
        assertEquals("https://www.example.com", one("زوروا www.example.com").value)
        assertEquals("https://moe.gov.ye", one("الموقع moe.gov.ye").value)
        assertEquals(EntityKind.URL, one("https://a.org/x?y=1").kind)
    }

    @Test fun dates() {
        assertEquals("2024-05-12", one("بتاريخ ١٢/٠٥/٢٠٢٤").value)
        assertEquals("2024-05-12", one("2024-05-12").value)
        assertEquals(EntityKind.DATE, one("12-05-2024").kind)               // a date, not a phone number
        assertEquals("2025-03-15", one("في 15 مارس 2025").value)
        assertEquals("2025-02-01", one("١ شباط ٢٠٢٥").value)
        assertEquals("2024-03-03", one("March 3, 2024").value)
        assertEquals("2024-12-25", one("12/25/2024").value)                 // month-first when the day cannot be a month
        assertTrue(SelEntities.detect("31/02/2024").none { it.kind == EntityKind.DATE })
    }

    @Test fun addressesAndNumbers() {
        assertEquals(EntityKind.ADDRESS, one("شارع الزبيري، صنعاء").kind)
        assertEquals("12 Main Street, Springfield", one("12 Main Street\nSpringfield").value)
        assertEquals("123.5", one("١٢٣٫٥").value)
        assertEquals("1234", one("1,234").value)
        assertTrue(SelEntities.detect("نص عادي بدون أي شيء").isEmpty())
        assertTrue(SelEntities.detect("x".repeat(SelEntities.MAX_SCAN + 1)).isEmpty())
    }

    // ---------------------------------------------------------------- Arabic text helpers

    @Test fun arabicHelpers() {
        assertEquals("محمد", SelText.stripDiacritics("مُحَمَّـدٌ"))
        assertEquals("احمد وابراهيم", SelText.normalize("أَحمد  وإبراهيم‏"))
        assertEquals("123.5, 2", SelText.latinDigits("١٢٣٫٥, ۲"))
        assertEquals("ص ١٢", SelText.arabicDigits("ص 12"))
        assertTrue(SelText.hasDiacritics("كَتَب")); assertFalse(SelText.hasDiacritics("كتب"))
    }

    @Test fun joinLinesAndSentence() {
        assertEquals("an example line\n\nnext para", SelText.joinLines("an exam-\nple\nline\n\nnext\npara"))
        val t = "First one. Second sentence here! Third؟ Last"
        val r = SelText.sentenceAt(t, t.indexOf("sentence"))
        assertEquals("Second sentence here!", t.substring(r.first, r.last + 1))
        val ar = "هذه جملة. وهذه جملة ثانية؟ نهاية"
        val r2 = SelText.sentenceAt(ar, ar.indexOf("ثانية"))
        assertEquals("وهذه جملة ثانية؟", ar.substring(r2.first, r2.last + 1))
    }
}

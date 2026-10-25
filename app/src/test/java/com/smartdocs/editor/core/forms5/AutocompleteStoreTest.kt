package com.smartdocs.editor.core.forms5

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutocompleteStoreTest {

    @Test fun keyNormalisation() {
        assertEquals("firstname", AutocompleteStore.normKey("form1.Page1.FirstName_2[0]"))
        assertEquals("city", AutocompleteStore.normKey("City"))
        assertEquals("123", AutocompleteStore.normKey("123"))
    }

    @Test fun prefixFirstThenWordsRankedByUse() {
        val s = AutocompleteStore()
        s.record("name", "Ahmed Ali", 1); s.record("name", "Ali Hassan", 2); s.record("name", "Ali Hassan", 3); s.record("name", "Sara", 4)
        assertEquals(listOf("Ali Hassan", "Ahmed Ali"), s.suggestions("name", "al"))
        assertEquals(listOf("Ali Hassan", "Sara", "Ahmed Ali"), s.suggestions("Name_1", ""))
    }

    @Test fun exactValueNotOfferedAndGlobalFallback() {
        val s = AutocompleteStore()
        s.record("city", "Riyadh", 1)
        s.record("other", "Riyadh Road", 2)
        assertEquals(listOf("Riyadh Road"), s.suggestions("city", "riyadh"))
        assertEquals(listOf("Riyadh Road", "Riyadh"), s.suggestions("town", "ri"))
    }

    @Test fun arabicFolding() {
        val s = AutocompleteStore()
        s.record("name", "أحمد", 1)            // ahmad with hamza on alef
        assertEquals(listOf("أحمد"), s.suggestions("name", "اح"))   // typed with bare alef
        s.record("phone", "٠٥١٢", 2)            // Arabic-Indic 0512
        assertEquals(listOf("٠٥١٢"), s.suggestions("phone", "05"))
    }

    @Test fun capAndJsonRoundTrip() {
        val s = AutocompleteStore(maxPerKey = 3)
        for (i in 1..5) s.record("k", "v$i", i.toLong())
        s.record("k", "v5", 10)
        val back = AutocompleteStore.fromJson(s.toJson(), maxPerKey = 3)
        val all = back.suggestions("k", "", 10)
        assertEquals(3, all.size)
        assertEquals("v5", all.first())
        assertTrue(AutocompleteStore.fromJson("not json").suggestions("k", "").isEmpty())
    }

    @Test fun removeAndIgnoreBlank() {
        val s = AutocompleteStore()
        s.record("k", "  "); s.record("k", "keep"); s.record("k", "drop")
        s.remove("k", "drop")
        assertEquals(listOf("keep"), s.suggestions("k", ""))
    }
}

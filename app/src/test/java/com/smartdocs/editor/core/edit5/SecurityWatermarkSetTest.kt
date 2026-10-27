package com.smartdocs.editor.core.edit5

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Row 10: a security-watermark profile with several watermarks, through the profile list's JSON export / import. */
class SecurityWatermarkSetTest {
    @Test fun severalWatermarksRoundTrip() {
        val a = DecorSpec.security("Ali", "TRACE1")
        val b = a.copy(text = "CONFIDENTIAL {date}", tile = false, rotation = 0f, opacity = 0.4f, vAlign = 1)
        val json = SecurityWatermarkSet.encode(listOf(a, b))
        assertTrue(JSONObject(json).has(SecurityWatermarkSet.KEY))
        val back = SecurityWatermarkSet.decode(json)
        assertEquals(2, back.size)
        assertEquals(a.text, back[0].text); assertEquals(b.text, back[1].text)
        assertEquals(0.4f, back[1].opacity, 0.0001f); assertEquals(1, back[1].vAlign)
        assertTrue(back.all { it.type == DecorType.SECURITY })
        // export / import of the profile file keeps the set
        val list = E5ProfileList.put(emptyList(), "Two marks", json)
        val text = E5ProfileList.export("sec", list)
        val imported = E5ProfileList.import("sec", emptyList(), text)
        assertEquals(2, SecurityWatermarkSet.decode(imported.single().json).size)
    }

    @Test fun singleSpecProfilesStillLoad() {
        val a = DecorSpec.security("Ali", "T2")
        assertEquals(a.toJson(), SecurityWatermarkSet.encode(listOf(a)))
        val back = SecurityWatermarkSet.decode(a.toJson())
        assertEquals(1, back.size); assertEquals(a.text, back[0].text)
        assertEquals(0, SecurityWatermarkSet.decode("not json").size)
        assertEquals(0, SecurityWatermarkSet.decode(null).size)
    }
}

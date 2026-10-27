package com.smartdocs.editor.core.esign

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrandFormTest {
    @Test fun colourNormalisation() {
        assertEquals("#1e3a8a", EsignBrand.normalizeColor(" #1E3A8A "))
        assertEquals("#aabbcc", EsignBrand.normalizeColor("abc"))
        assertEquals("#123456", EsignBrand.normalizeColor("123456"))
        assertEquals("", EsignBrand.normalizeColor("red"))
        assertEquals("", EsignBrand.normalizeColor("#12345"))
        assertEquals(0xFFFF0000L, EsignBrand.argb("#ff0000"))
        assertNull(EsignBrand.argb("nope"))
    }

    @Test fun readableTextColour() {
        assertEquals("#ffffff", EsignBrand.onColor("#1e3a8a"))
        assertEquals("#111111", EsignBrand.onColor("#ffff00"))
        assertEquals("#111111", EsignBrand.onColor("#ffffff"))
        assertEquals("#ffffff", EsignBrand.onColor("#000000"))
    }

    @Test fun logoMagicBytes() {
        assertEquals("image/png", EsignBrand.imageType(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 0)))
        assertEquals("image/jpeg", EsignBrand.imageType(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertEquals("image/webp", EsignBrand.imageType("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1)))
        assertNull(EsignBrand.imageType("GIF89a......".toByteArray()))
    }

    @Test fun brandJsonRoundTrip() {
        val b = EsignBrand("شركة المثال", "#ABC", "iVBORw0K")
        val o = b.toJson()
        assertEquals("#aabbcc", o.getString("color"))
        assertEquals("iVBORw0K", o.getString("logo"))
        val server = JSONObject().put("name", "شركة المثال").put("color", "#aabbcc").put("logo", true)
        val back = EsignBrand.from(server)!!
        assertTrue(back.hasLogo); assertEquals("", back.logoBase64)
        assertTrue(EsignBrand().isEmpty); assertFalse(back.isEmpty)
        val req = EsignRequest.from(JSONObject().put("id", "x").put("brand", server))
        assertEquals("#aabbcc", req.brand?.color)
        assertNull(EsignRequest.from(JSONObject().put("id", "x")).brand)
    }

    @Test fun formParsingAndCopyAsRequest() {
        val o = JSONObject("""{"id":"abcdefabcdef","title":"Consent","sender":"Clinic","created":10,"status":"active","state":"full","count":3,
            "maxResponses":3,"requireEmail":true,"link":"https://w/f/abcdefabcdef","originalSha":"${"a".repeat(64)}",
            "fields":[{"id":"s1","signer":4,"page":0,"x":0.1,"y":0.2,"w":0.3,"h":0.1,"type":"signature"}],"brand":{"color":"#0f766e","logo":false}}""")
        val f = EsignForm.from(o)
        assertEquals(FormState.FULL, f.state)
        assertEquals(0, f.fields.single().signer)
        assertEquals("3/3", FormCopies.counter(f))
        assertEquals("#0f766e", f.brand?.color)
        val s = FormSubmission.from(JSONObject("""{"sid":"0000abcdefghij123456","name":"نور","email":"n@x.io","time":99,
            "values":{"s1":"BASE64"},"event":{"time":99,"ip":"1.2.3.4","ua":"UA","device":"Pixel","channel":"web"}}"""))
        val (req, values) = FormCopies.asRequest(f, s)
        assertEquals("abcdefabcdef-0000abcdefghij123456", req.id)
        assertEquals(RequestStatus.COMPLETED, req.status)
        assertEquals("نور", req.signers.single().name)
        assertEquals(SignerStatus.SIGNED, req.signers.single().status)
        assertEquals(listOf("created", "signed"), req.audit.map { it.kind })
        assertEquals("1.2.3.4", req.audit[1].ip)
        assertEquals(mapOf("s1" to "BASE64"), values.single().values)
        assertEquals(0, values.single().signer)
    }
}

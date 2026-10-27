package com.smartdocs.editor.core.ai6

import com.smartdocs.editor.core.ai.AiClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiMediaTest {
    private fun obj(s: String) = AiClient.json.parseToJsonElement(s).jsonObject

    @Test fun visionBodyUsesContentPartsOnlyForImageMessages() {
        val body = AiMedia.visionBody("m", listOf(MediaMessage("system", "sys"), MediaMessage("user", "what is this?", listOf(AiImage(byteArrayOf(1, 2, 3), "image/png")))))
        val msgs = body["messages"] as JsonArray
        assertEquals("sys", (msgs[0].jsonObject["content"] as JsonPrimitive).content)
        val parts = msgs[1].jsonObject["content"] as JsonArray
        assertEquals(2, parts.size)
        val url = ((parts[1].jsonObject["image_url"] as JsonObject)["url"] as JsonPrimitive).content
        assertEquals("data:image/png;base64,AQID", url)
    }

    @Test fun parsesImageB64AndUrl() {
        val (bytes, url) = AiMedia.parseImage(obj("{\"data\":[{\"b64_json\":\"AQID\"}]}"))
        assertArrayEquals(byteArrayOf(1, 2, 3), bytes)
        assertEquals(null, url)
        val (b2, u2) = AiMedia.parseImage(obj("{\"data\":[{\"url\":\"https://x/y.png\"}]}"))
        assertEquals(null, b2); assertEquals("https://x/y.png", u2)
    }

    @Test fun imageBodyOnlyAsksB64ForDalle() {
        assertTrue(AiMedia.imageBody("dall-e-3", "cat", "1024x1024").containsKey("response_format"))
        assertTrue(!AiMedia.imageBody("gpt-image-1", "cat", "1024x1024").containsKey("response_format"))
    }

    @Test fun embeddingsAreReturnedInIndexOrder() {
        val v = AiMedia.parseEmbeddings(obj("{\"data\":[{\"index\":1,\"embedding\":[0,1]},{\"index\":0,\"embedding\":[1,0]}]}"))
        assertEquals(1f, v[0][0]); assertEquals(1f, v[1][1])
        assertEquals(1.0, AiMedia.cosine(floatArrayOf(1f, 0f), floatArrayOf(2f, 0f)), 1e-9)
        assertEquals(0.0, AiMedia.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 3f)), 1e-9)
        assertEquals(0.0, AiMedia.cosine(floatArrayOf(), floatArrayOf()), 1e-9)
    }

    @Test fun webResultsFromProxyAndDuckDuckGo() {
        val r = AiMedia.parseWebResults(obj("{\"results\":[{\"title\":\"T\",\"url\":\"https://a\",\"snippet\":\"S\"},{\"title\":\"no url\"}]}"))
        assertEquals(1, r.size); assertEquals("S", r[0].snippet)
        val d = AiMedia.parseDuckDuckGo(obj("{\"Heading\":\"Cairo\",\"AbstractText\":\"Capital of Egypt\",\"AbstractURL\":\"https://w/Cairo\",\"RelatedTopics\":[{\"Text\":\"Giza - a city\",\"FirstURL\":\"https://d/Giza\"},{\"Topics\":[{\"Text\":\"Nile - river\",\"FirstURL\":\"https://d/Nile\"}]}]}"))
        assertEquals(listOf("Cairo", "Giza", "Nile"), d.map { it.title })
        assertTrue(AiMedia.webBlock(d).startsWith("[W1] Cairo"))
    }

    @Test fun capabilitiesUsageAndErrors() {
        val caps = AiMedia.parseCapabilities(obj("{\"vision\":true,\"images\":false,\"web_search\":true}"))
        assertEquals(setOf(AiFeature.VISION, AiFeature.WEB_SEARCH), caps)
        val u = AiMedia.parseUsage(obj("{\"chat_used\":5,\"chat_limit\":60,\"tts_used\":2,\"tts_limit\":400}"))
        assertEquals(5, u.chatUsed); assertEquals(60, u.chatLimit); assertTrue(u.fromServer)
        assertTrue(AiMedia.mapError(AiFeature.IMAGES, 501, "{\"error\":\"not configured\"}") is AiNotConfiguredException)
        assertTrue(AiMedia.mapError(AiFeature.IMAGES, 404, "") is AiNotConfiguredException)
        assertTrue(AiMedia.mapError(AiFeature.IMAGES, 500, "boom") !is AiNotConfiguredException)
        assertEquals("audio/mp4", AiMedia.audioMime("a.M4A"))
    }
}

package com.smartdocs.editor.core.updates

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UpdateManifestTest {
    private val packageName = "com.smartdocs.editor.test"
    private fun asset(abi: String? = "arm64-v8a", url: String = "https://example.com/app.apk") = JSONObject()
        .put("url", url).put("sha256", "a".repeat(64)).put("size", 123)
        .put("abis", JSONArray(if (abi == null) emptyList<String>() else listOf(abi)))
    private fun feed() = JSONObject().put("schemaVersion", 1).put("applicationId", packageName).put("versionCode", 6)
        .put("versionName", "0.5.1-test").put("minSdk", 26).put("notes", "Changes")
        .put("artifacts", JSONArray().put(asset()).put(asset("armeabi-v7a", "https://example.com/arm32.apk")).put(asset(null, "https://example.com/universal.apk")))
    private fun parse(o: JSONObject, version: Long = 5, sdk: Int = 33, abis: List<String> = listOf("arm64-v8a", "armeabi-v7a")) = UpdateManifest.parse(o.toString(), packageName, version, sdk, abis)
    private fun rejected(problem: UpdateProblem, block: () -> Unit) {
        try { block(); fail("Expected $problem") } catch (e: UpdateException) { assertEquals(problem, e.problem) }
    }
    @Test fun choosesPreferredDeviceAbiOverFallback() { assertEquals("https://example.com/app.apk", parse(feed())!!.artifact.url) }
    @Test fun fallsBackToSupportedSecondaryAbi() { assertEquals("https://example.com/arm32.apk", parse(feed(), abis = listOf("x86_64", "armeabi-v7a"))!!.artifact.url) }
    @Test fun universalIsUsedWhenNoSpecificAbiMatches() { assertEquals("https://example.com/universal.apk", parse(feed(), abis = listOf("x86_64"))!!.artifact.url) }
    @Test fun sameOrOlderVersionIsNotOffered() { assertNull(parse(feed(), 6)); assertNull(parse(feed(), 7)) }
    @Test fun rejectsProductionFeedForTestApp() { rejected(UpdateProblem.WRONG_PACKAGE) { parse(feed().put("applicationId", "com.smartdocs.editor")) } }
    @Test fun rejectsUnsupportedAndroidAndAbi() {
        rejected(UpdateProblem.INCOMPATIBLE) { parse(feed(), sdk = 25) }
        rejected(UpdateProblem.INCOMPATIBLE) { parse(feed().put("artifacts", JSONArray().put(asset())), abis = listOf("x86")) }
    }
    @Test fun rejectsUnknownSchemaAndFractionalVersions() {
        rejected(UpdateProblem.INVALID_FEED) { parse(feed().put("schemaVersion", 2)) }
        rejected(UpdateProblem.INVALID_FEED) { parse(feed().put("versionCode", 6.5)) }
    }
    @Test fun rejectsBadHashesAndSizes() {
        for (a in listOf(asset().put("sha256", "missing"), asset().put("size", 0), asset().put("size", UpdateManifest.MAX_APK_BYTES + 1)))
            rejected(UpdateProblem.INVALID_FEED) { parse(feed().put("artifacts", JSONArray().put(a))) }
    }
    @Test fun rejectsPlainHttpAndEmbeddedCredentialsInAssets() {
        for (url in listOf("http://example.com/app.apk", "https://user:password@example.com/app.apk", "file:///private/app.apk"))
            rejected(UpdateProblem.UNSAFE_URL) { parse(feed().put("artifacts", JSONArray().put(asset(url = url)))) }
    }
    @Test fun malformedJsonIsAControlledError() { rejected(UpdateProblem.INVALID_FEED) { UpdateManifest.parse("not JSON", packageName, 5, 33, listOf("arm64-v8a")) } }
    @Test fun sourceUrlRequiresAnUnambiguousHttpsAddress() {
        assertEquals("https://example.com/channel.json", UpdateUrls.requireHttps("https://example.com/channel.json"))
        assertEquals("HTTPS://example.com/channel.json", UpdateUrls.requireHttps("HTTPS://example.com/channel.json"))
        for (url in listOf("https://", "https://example.com/a#fragment", "ftp://example.com/a", "https://u@example.com/a"))
            rejected(UpdateProblem.UNSAFE_URL) { UpdateUrls.requireHttps(url) }
    }
}

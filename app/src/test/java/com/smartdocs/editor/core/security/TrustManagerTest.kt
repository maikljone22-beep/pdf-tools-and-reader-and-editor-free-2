package com.smartdocs.editor.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustManagerTest {
    private val app = listOf("/data/user/0/com.smartdocs.editor/files", "/storage/emulated/0/Android/data/com.smartdocs.editor/files")
    private val own = listOf("com.smartdocs.editor.fileprovider")
    private fun classify(local: String, source: String?) = DocOrigins.classify(local, source, app, own)
    private val imported = "/storage/emulated/0/Android/data/com.smartdocs.editor/files/Documents/a.pdf"

    @Test fun originsOfDocuments() {
        assertEquals(OriginKind.APP_STORAGE, classify(imported, null).kind)
        assertEquals(OriginKind.LOCAL, classify("/storage/emulated/0/Documents/a.pdf", null).kind)
        assertEquals(OriginKind.DOWNLOADS, classify("/storage/emulated/0/Download/a.pdf", null).kind)
        assertEquals(OriginKind.EMAIL, classify(imported, "content://com.google.android.gm.sapi/acc/message_attachment_external/r1/a1").kind)
        assertEquals(OriginKind.EMAIL, classify(imported, "content://com.microsoft.office.outlook.fileprovider/x/a.pdf").kind)
        assertEquals(OriginKind.DOWNLOADS, classify(imported, "content://com.android.providers.downloads.documents/document/msf%3A42").kind)
        assertEquals(OriginKind.DOWNLOADS, classify(imported, "content://com.android.externalstorage.documents/document/primary%3ADownload%2Fa.pdf").kind)
        assertEquals(OriginKind.LOCAL, classify(imported, "content://com.android.externalstorage.documents/document/primary%3ADocuments%2Fa.pdf").kind)
        assertEquals(OriginKind.OTHER_APP, classify(imported, "content://org.telegram.messenger.provider/media/a.pdf").kind)
        assertEquals(OriginKind.APP_STORAGE, classify(imported, "content://com.smartdocs.editor.fileprovider/docs/a.pdf").kind)
        assertEquals(OriginKind.WEB, classify(imported, "https://example.com/a.pdf").kind)
        assertEquals(OriginKind.APP_STORAGE, classify(imported, "file://$imported").kind)
    }

    @Test fun pathsOfSourceUris() {
        assertEquals("/storage/emulated/0/Documents/Work/a.pdf", DocOrigins.pathOf("content://com.android.externalstorage.documents/document/primary%3ADocuments%2FWork%2Fa.pdf"))
        assertEquals("/storage/emulated/0/Documents", DocOrigins.pathOf("content://com.android.externalstorage.documents/tree/primary%3ADocuments"))
        assertEquals("/storage/1A2B-3C4D/x", DocOrigins.pathOf("content://com.android.externalstorage.documents/tree/1A2B-3C4D%3Ax"))
        assertEquals("/sdcard/a b.pdf", DocOrigins.pathOf("file:///sdcard/a%20b.pdf"))
        assertNull(DocOrigins.pathOf("content://org.telegram.messenger.provider/media/a.pdf"))
        assertTrue(DocOrigins.under("/a/b/c.pdf", "/a/b/")); assertFalse(DocOrigins.under("/a/bc/c.pdf", "/a/b"))
    }

    @Test fun privilegedLocations() {
        val priv = Privileged(folders = listOf("/storage/emulated/0/Documents/Work"), hosts = listOf("intranet.example.com"), appStorage = true)
        assertTrue(priv.covers(classify(imported, null)))
        assertFalse(priv.copy(appStorage = false).covers(classify(imported, null)))
        assertTrue(priv.covers(classify(imported, "content://com.android.externalstorage.documents/document/primary%3ADocuments%2FWork%2Fa.pdf")))
        assertFalse(priv.covers(classify(imported, "content://com.android.externalstorage.documents/document/primary%3ADocuments%2Fa.pdf")))
        // an imported e-mail attachment is not privileged by the app storage it was copied into
        assertFalse(priv.covers(classify(imported, "content://com.google.android.gm.sapi/a")))
        assertTrue(priv.covers(classify(imported, "https://docs.intranet.example.com/a.pdf")))
        assertFalse(priv.covers(classify(imported, "https://evil.com/a.pdf")))
        assertEquals("/storage/emulated/0/Documents", Privileged.normalizeFolder("content://com.android.externalstorage.documents/tree/primary%3ADocuments"))
        assertNull(Privileged.normalizeFolder("relative/path"))
        assertNull(Privileged.normalizeFolder("/a/../b"))
    }

    @Test fun safeReadingBlocksWhatLeavesTheDocument() {
        val priv = Privileged(hosts = listOf("ok.org"))
        assertEquals(NetDecision.BLOCK, SafeReading.web(true, false, priv, NetMode.ALLOW_ALL, emptyList(), "https://a.com"))
        assertEquals(NetDecision.ALLOW, SafeReading.web(true, false, priv, NetMode.ALLOW_ALL, emptyList(), "https://www.ok.org/x"))
        assertEquals(NetDecision.ASK, SafeReading.web(true, false, priv, NetMode.ASK, emptyList(), "https://ok.org"))
        assertEquals(NetDecision.ALLOW, SafeReading.web(true, false, priv, NetMode.BLOCK_ALL, emptyList(), "mailto:a@b.c"))
        assertEquals(NetDecision.BLOCK, SafeReading.web(false, true, priv, NetMode.ALLOW_ALL, emptyList(), "https://a.com"))
        assertEquals(NetDecision.ALLOW, SafeReading.web(false, false, priv, NetMode.ALLOW_ALL, emptyList(), "https://a.com"))
        assertEquals(LaunchVerdict.BLOCKED_SAFE_MODE, SafeReading.launch(true, false, "report.docx", false))
        assertEquals(LaunchVerdict.BLOCKED_EXECUTABLE, SafeReading.launch(true, false, "x.exe", false))
        assertEquals(LaunchVerdict.ALLOWED_WITH_WARNING, SafeReading.launch(false, false, "report.docx", false))
        assertTrue(SafeReading.active(true, false)); assertFalse(SafeReading.active(true, true)); assertFalse(SafeReading.active(false, false))
    }

    @Test fun protectedViewDecision() {
        val mail = classify(imported, "content://com.google.android.gm/a")
        val local = classify("/storage/emulated/0/Documents/a.pdf", null)
        assertTrue(ProtectedView.applies(PvMode.UNSAFE, mail, privileged = false, editingEnabled = false))
        assertFalse(ProtectedView.applies(PvMode.UNSAFE, local, false, false))
        assertTrue(ProtectedView.applies(PvMode.ALL, local, false, false))
        assertFalse(ProtectedView.applies(PvMode.ALL, local, privileged = true, editingEnabled = false))
        assertFalse(ProtectedView.applies(PvMode.UNSAFE, mail, false, editingEnabled = true))
        assertFalse(ProtectedView.applies(PvMode.OFF, mail, false, false))
    }

    @Test fun javaScriptRunLevels() {
        assertFalse(JsRun.allowed(JsLevel.OFF, false, true, JsAnswer.ALLOW))
        assertFalse(JsRun.allowed(JsLevel.ON, true, true, null))                 // safe reading mode
        assertTrue(JsRun.allowed(JsLevel.ON, false, false, null))
        assertFalse(JsRun.allowed(JsLevel.ON, false, false, JsAnswer.BLOCK))     // "Keep blocked"
        assertFalse(JsRun.allowed(JsLevel.TRUSTED, false, false, null))
        assertTrue(JsRun.allowed(JsLevel.TRUSTED, false, true, null))
        assertFalse(JsRun.allowed(JsLevel.ASK, false, false, null))
        assertTrue(JsRun.allowed(JsLevel.ASK, false, false, JsAnswer.ALLOW))
        assertTrue(JsRun.canAllow(JsLevel.ASK, false)); assertFalse(JsRun.canAllow(JsLevel.TRUSTED, false)); assertFalse(JsRun.canAllow(JsLevel.ON, true))
        val js = ActionFindings(mapOf(ActionFinding(ActionKind.JAVASCRIPT, ActionPlace.FIELDS) to 2))
        val links = ActionFindings(mapOf(ActionFinding(ActionKind.URI, ActionPlace.LINKS) to 5))
        val openUri = ActionFindings(mapOf(ActionFinding(ActionKind.URI, ActionPlace.OPEN_ACTION) to 1))
        assertTrue(JsRun.showBanner(js, trusted = false, answer = null))
        assertFalse(JsRun.showBanner(js, trusted = true, answer = null))
        assertFalse(JsRun.showBanner(js, false, JsAnswer.BLOCK))
        assertFalse(JsRun.showBanner(links, false, null))                         // plain links: no banner
        assertTrue(JsRun.showBanner(openUri, false, null))
        assertFalse(JsRun.showBanner(null, false, null))
    }

    @Test fun attachmentPolicy() {
        val d = AttachmentPolicy.defaults()
        assertEquals(AttachRule.BLOCK, AttachmentPolicy.decide("setup.EXE", d))
        assertEquals(AttachRule.BLOCK, AttachmentPolicy.decide("macro.xlsm", d))
        assertEquals(AttachRule.BLOCK, AttachmentPolicy.decide("evil.bat. ", d))
        assertEquals(AttachRule.ALLOW, AttachmentPolicy.decide("photo.JPG", d))
        assertEquals(AttachRule.ALLOW, AttachmentPolicy.decide("report.pdf", d))
        assertEquals(AttachRule.ASK, AttachmentPolicy.decide("archive.zip", d))
        assertEquals(AttachRule.ASK, AttachmentPolicy.decide("README", d))
        val rules = d + ("zip" to AttachRule.ALLOW) - "pdf"
        assertEquals(rules, AttachmentPolicy.decode(AttachmentPolicy.encode(rules)))
        assertEquals(AttachRule.ASK, AttachmentPolicy.decide("a.pdf", rules))
        assertEquals("exe", AttachmentPolicy.normalizeExt(" *.EXE "))
        assertNull(AttachmentPolicy.normalizeExt("a b"))
        assertNull(AttachmentPolicy.decode(null))
    }
}

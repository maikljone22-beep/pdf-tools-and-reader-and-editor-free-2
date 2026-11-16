package com.smartdocs.editor.core.edit5

import com.smartdocs.editor.core.redact.ProtectAdvanced
import com.smartdocs.editor.core.redact.ProtectOptions
import com.smartdocs.editor.core.security.ReaderRestrictions
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProtectionSourceTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun encrypted(): File = tmp.newFile().also { file ->
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            doc.protect(StandardProtectionPolicy("current-owner", "current-user", AccessPermission().apply {
                setCanModify(false); setCanExtractContent(false)
            }).apply { encryptionKeyLength = 128; setPreferAES(true) })
            doc.save(file)
        }
    }

    @Test fun missingOrStaleCurrentPasswordCannotProduceReadyInput() = runBlocking {
        val src = encrypted()
        for (password in listOf(null, "stale-password", "new-output-password")) {
            try {
                ProtectAdvanced.inspectSource(src, password)
                fail("An unverified current password must not enable protection")
            } catch (_: InvalidPasswordException) { }
        }
    }

    @Test fun userAndOwnerPasswordsHaveDifferentChangePermissions() = runBlocking {
        val src = encrypted()
        assertTrue(ReaderRestrictions.restricted(ProtectAdvanced.inspectSource(src, "current-user").security))
        assertFalse(ReaderRestrictions.restricted(ProtectAdvanced.inspectSource(src, "current-owner").security))
    }

    @Test fun validatedCurrentPasswordCanApplyANewOutputPassword() = runBlocking {
        val src = encrypted(); val out = tmp.newFile()
        ProtectAdvanced.inspectSource(src, "current-owner")
        ProtectAdvanced.protect(src, out, "current-owner", ProtectOptions("new-output-password", "new-owner-password"))
        PDDocument.load(out, "new-output-password").use { assertEquals(1, it.numberOfPages) }
        PDDocument.load(src, "current-user").use { assertEquals(1, it.numberOfPages) }
    }
}

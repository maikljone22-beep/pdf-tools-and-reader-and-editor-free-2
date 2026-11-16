package com.smartdocs.editor.core.updates

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

class UpdateFilesTest {
    @get:Rule val tmp = TemporaryFolder()
    @Test fun checksumDetectsTamperingAndTruncation() {
        val file = tmp.newFile().apply { writeText("verified APK bytes") }
        val artifact = UpdateArtifact("https://example.com/a.apk", UpdateFiles.sha256(file), file.length(), emptyList())
        UpdateFiles.verifyHash(file, artifact)
        file.writeText("tampered APK bytes")
        try { UpdateFiles.verifyHash(file, artifact); fail("Tampering must fail") } catch (e: UpdateException) { assertEquals(UpdateProblem.CHECKSUM, e.problem) }
        file.writeBytes(byteArrayOf())
        try { UpdateFiles.verifyHash(file, artifact); fail("Truncation must fail") } catch (e: UpdateException) { assertEquals(UpdateProblem.CHECKSUM, e.problem) }
    }
    @Test fun readsSmallFeedsAndRejectsOversizedBodies() {
        assertEquals("{}", UpdateFiles.readFeed(ByteArrayInputStream("{}".toByteArray())))
        try { UpdateFiles.readFeed(ByteArrayInputStream(ByteArray(64 * 1024 + 1))); fail("Oversized feeds must fail") }
        catch (e: UpdateException) { assertEquals(UpdateProblem.INVALID_FEED, e.problem) }
    }
}

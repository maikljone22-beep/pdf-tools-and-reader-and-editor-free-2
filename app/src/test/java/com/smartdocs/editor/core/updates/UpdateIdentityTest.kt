package com.smartdocs.editor.core.updates

import org.junit.Assert.*
import org.junit.Test

class UpdateIdentityTest {
    private val installed = ApkIdentity("com.smartdocs.editor.test", 5, 26, setOf("original-key"))
    private val candidate = installed.copy(versionCode = 6)
    private val offer = UpdateOffer(installed.applicationId, 6, "0.5.1-test", 26, "", UpdateArtifact("https://example.com/a.apk", "a".repeat(64), 1, emptyList()))
    private fun rejected(reason: UpdateProblem, value: ApkIdentity, release: UpdateOffer = offer) {
        try { UpdateIdentity.verify(installed, value, release, 33); fail("Expected $reason") }
        catch (e: UpdateException) { assertEquals(reason, e.problem) }
    }
    @Test fun permitsANewerBuildWithTheSameSigner() { UpdateIdentity.verify(installed, candidate, offer, 33) }
    @Test fun rejectsAResignedDebugApk() { rejected(UpdateProblem.SIGNATURE, candidate.copy(signers = setOf("different-key"))) }
    @Test fun permitsPlatformValidatedSingleSignerRotation() { UpdateIdentity.verify(installed, candidate.copy(signers = setOf("rotated-key"), signerHistory = setOf("original-key", "rotated-key")), offer, 33) }
    @Test fun rejectsUnsignedArchives() { rejected(UpdateProblem.SIGNATURE, candidate.copy(signers = emptySet())) }
    @Test fun rejectsPartialMultisignerMatches() { rejected(UpdateProblem.SIGNATURE, candidate.copy(signers = setOf("original-key", "other-key"))) }
    @Test fun rejectsWrongApplicationId() { rejected(UpdateProblem.WRONG_PACKAGE, candidate.copy(applicationId = "com.other.app")) }
    @Test fun rejectsDowngradesAndFeedVersionMismatch() {
        rejected(UpdateProblem.NOT_NEWER, candidate.copy(versionCode = 5))
        rejected(UpdateProblem.NOT_NEWER, candidate.copy(versionCode = 7))
    }
    @Test fun rejectsAndroidVersionIncompatibility() { rejected(UpdateProblem.INCOMPATIBLE, candidate.copy(minSdk = 34)) }
}

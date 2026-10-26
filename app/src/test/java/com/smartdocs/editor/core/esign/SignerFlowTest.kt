package com.smartdocs.editor.core.esign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignerFlowTest {
    private fun three() = listOf(Signer("A"), Signer("B"), Signer("C"))

    @Test fun sequentialOnlyFirstOpenSignerIsActive() {
        val s = SignerFlow.normalize(SignOrder.SEQUENTIAL, three())
        assertEquals(setOf(0), SignerFlow.active(SignOrder.SEQUENTIAL, s))
        assertEquals(SignerStatus.PENDING, s[0].status)
        assertEquals(SignerStatus.WAITING, s[1].status)
        val after = SignerFlow.sign(SignOrder.SEQUENTIAL, s, 0, 100L)
        assertEquals(SignerStatus.SIGNED, after[0].status)
        assertEquals(100L, after[0].signedAt)
        assertEquals(SignerStatus.PENDING, after[1].status)
        assertEquals(SignerStatus.WAITING, after[2].status)
        assertEquals(setOf(1), SignerFlow.active(SignOrder.SEQUENTIAL, after))
    }

    @Test(expected = SignerFlow.NotYourTurn::class)
    fun sequentialRejectsOutOfOrder() {
        SignerFlow.sign(SignOrder.SEQUENTIAL, SignerFlow.normalize(SignOrder.SEQUENTIAL, three()), 2, 1L)
    }

    @Test fun parallelEveryoneActiveAndCompletes() {
        var s = SignerFlow.normalize(SignOrder.PARALLEL, three())
        assertEquals(setOf(0, 1, 2), SignerFlow.active(SignOrder.PARALLEL, s))
        s = SignerFlow.sign(SignOrder.PARALLEL, s, 2, 1L)
        s = SignerFlow.sign(SignOrder.PARALLEL, s, 0, 2L)
        assertEquals(RequestStatus.OUT, SignerFlow.overall(s))
        s = SignerFlow.sign(SignOrder.PARALLEL, s, 1, 3L)
        assertEquals(RequestStatus.COMPLETED, SignerFlow.overall(s))
        assertTrue(SignerFlow.active(SignOrder.PARALLEL, s).isEmpty())
    }

    @Test fun declineEndsRequest() {
        var s = SignerFlow.normalize(SignOrder.PARALLEL, three())
        s = SignerFlow.decline(SignOrder.PARALLEL, s, 1, "wrong amount", 5L)
        assertEquals(RequestStatus.DECLINED, SignerFlow.overall(s))
        assertTrue(SignerFlow.active(SignOrder.PARALLEL, s).isEmpty())
        assertEquals("wrong amount", s[1].declineReason)
    }

    @Test fun viewedStatusKeptWhenActive() {
        val s = listOf(Signer("A", status = SignerStatus.VIEWED), Signer("B"))
        assertEquals(SignerStatus.VIEWED, SignerFlow.normalize(SignOrder.SEQUENTIAL, s)[0].status)
    }

    @Test fun missingRequiredFields() {
        val f = listOf(EsignField("a", 0, 0, 0f, 0f, .1f, .1f), EsignField("b", 0, 0, 0f, 0f, .1f, .1f, FieldType.TEXT, required = false),
            EsignField("c", 1, 0, 0f, 0f, .1f, .1f))
        assertEquals(listOf("a"), SignerFlow.missing(f, 0, mapOf("b" to "x")).map { it.id })
        assertTrue(SignerFlow.missing(f, 0, mapOf("a" to "sig")).isEmpty())
    }

    @Test fun requestJsonRoundTrip() {
        val o = org.json.JSONObject().put("id", "r1").put("order", "parallel").put("status", "completed")
            .put("signers", org.json.JSONArray().put(Signer("A", "a@x", SignerStatus.SIGNED, signedAt = 9L).toJson()))
            .put("fields", org.json.JSONArray().put(EsignField("f", 0, 1, .1f, .2f, .3f, .05f, FieldType.DATE).toJson()))
        val r = EsignRequest.from(o)
        assertEquals(SignOrder.PARALLEL, r.order)
        assertEquals(RequestStatus.COMPLETED, r.status)
        assertEquals(SignerStatus.SIGNED, r.signers[0].status)
        assertEquals(FieldType.DATE, r.fields[0].type)
        assertEquals(0.2f, r.fields[0].y, 1e-6f)
        assertFalse(r.signers.isEmpty())
    }
}

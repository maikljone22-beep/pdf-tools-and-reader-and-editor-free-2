package com.smartdocs.editor.core.ai

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class AiConsentTest {
    private class Mem(var v: Boolean = false) : AiConsent.Store { override fun get() = v; override fun set(value: Boolean) { v = value } }
    private lateinit var store: Mem

    @Before fun setUp() { store = Mem(); AiConsent.install(store, "declined") }

    @Test fun defaultIsNotGiven() { assertFalse(AiConsent.isGranted()); assertFalse(AiConsent.granted.value) }

    @Test fun withoutHostRequestIsBlocked() = runBlocking {
        try { AiConsent.ensure("api.openai.com"); fail("must throw") } catch (e: AiConsentRequired) { assertEquals("declined", e.message) }
        assertFalse(store.v)
    }

    @Test fun grantedPassesWithoutAsking() = runBlocking {
        AiConsent.set(true)
        AiConsent.ensure("x")   // no host, but already granted: no throw
        assertTrue(store.v); assertEquals(null, AiConsent.pending.value)
    }

    @Test fun allowPersistsAndUnblocks() = runBlocking {
        AiConsent.hosts = 1
        val call = async { AiConsent.ensure("api.groq.com") }
        val ask = AiConsent.pending.filterNotNull().first()
        assertEquals("api.groq.com", ask.provider)
        AiConsent.answer(ask, true)
        call.await()
        assertTrue(store.v); assertTrue(AiConsent.granted.value); assertEquals(null, AiConsent.pending.value)
    }

    @Test fun cancelBlocksAndDoesNotPersist() = runBlocking {
        AiConsent.hosts = 1
        val call = async { runCatching { AiConsent.ensure("p") } }
        AiConsent.answer(AiConsent.pending.filterNotNull().first(), false)
        assertTrue(call.await().exceptionOrNull() is AiConsentRequired)
        assertFalse(store.v); assertEquals(null, AiConsent.pending.value)
    }

    @Test fun withdrawAsksAgain() = runBlocking {
        AiConsent.set(true); AiConsent.set(false)
        assertFalse(store.v)
        try { AiConsent.ensure("p"); fail() } catch (_: AiConsentRequired) {}
    }

    @Test fun consentErrorIsAnAiException() { assertTrue(AiConsentRequired("m") is AiException) }

    @Test fun providerNameIsHost() {
        assertEquals("api.openai.com", AiConsent.providerName("https://api.openai.com/v1"))
        assertEquals("localhost", AiConsent.providerName("http://localhost:11434/v1"))
        assertEquals("weird", AiConsent.providerName("weird"))
    }
}

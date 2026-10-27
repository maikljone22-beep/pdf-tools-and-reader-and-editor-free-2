package com.smartdocs.editor.core.sign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** nitro-quicksign: saved time-stamp server list (built-ins kept, add / edit / delete, JSON). */
class TsaServersTest {
    @Test fun emptyStoreHasAllBuiltIns() {
        val l = TsaServers.decode(null)
        assertEquals(TsaClient.presets, l.map { it.url })
        assertTrue(l.all { it.builtIn })
    }

    @Test fun addEditDeleteRoundTrip() {
        var l = TsaServers.decode(null)
        val mine = TsaServer("u:1", "Company", "https://tsa.example.com/tsr", username = "alice", requireTrusted = true)
        l = TsaServers.upsert(l, mine)
        val back = TsaServers.decode(TsaServers.encode(l))
        assertEquals(l, back)
        assertEquals(mine, TsaServers.find(back, "HTTPS://tsa.example.com/tsr/"))
        l = TsaServers.upsert(l, mine.copy(name = "Corp", username = ""))
        assertEquals("Corp", l.first { it.id == "u:1" }.name)
        l = TsaServers.delete(l, "u:1")
        assertNull(TsaServers.find(l, mine.url))
    }

    @Test fun builtInsCannotBeDeletedOrMoved() {
        var l = TsaServers.decode(null)
        val b = l.first()
        l = TsaServers.delete(l, b.id)
        assertNotNull(l.firstOrNull { it.id == b.id })
        l = TsaServers.upsert(l, b.copy(url = "http://evil.example", username = "bob", requireTrusted = true))
        val e = l.first { it.id == b.id }
        assertEquals(b.url, e.url)
        assertEquals("bob", e.username)
        assertTrue(e.requireTrusted && e.builtIn)
        // the options of a built-in survive a save
        assertEquals(e, TsaServers.decode(TsaServers.encode(l)).first { it.id == b.id })
    }

    @Test fun badJsonFallsBackToBuiltIns() {
        assertEquals(TsaClient.presets.size, TsaServers.decode("{oops").size)
    }
}

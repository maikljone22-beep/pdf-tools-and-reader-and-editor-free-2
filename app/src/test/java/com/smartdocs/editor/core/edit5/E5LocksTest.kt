package com.smartdocs.editor.core.edit5

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.LocalTime

/** Password handling of the multi-file tools and the pure parts of the live watermark script. */
class E5LocksTest {

    private fun tmp(name: String) = File.createTempFile("e5_$name", ".pdf").also { it.deleteOnExit() }

    private fun locked(userPw: String, pages: Int = 2): File {
        val f = tmp("locked")
        PDDocument().use { d ->
            repeat(pages) { d.addPage(PDPage(PDRectangle.A4)) }
            val ap = AccessPermission().apply { setCanPrint(false) }
            d.protect(StandardProtectionPolicy("owner", userPw, ap).apply { encryptionKeyLength = 128; setPreferAES(true) })
            d.save(f)
        }
        return f
    }

    private fun plain(pages: Int = 1): File {
        val f = tmp("plain")
        PDDocument().use { d -> repeat(pages) { d.addPage(PDPage(PDRectangle.A4)) }; d.save(f) }
        return f
    }

    @Test fun passwordBookAsksOncePerFile() {
        val a = File("/x/a.pdf"); val b = File("/x/b.pdf"); val c = File("/x/c.pdf")
        val book = PasswordBook()
        assertEquals(listOf(a, b, c), book.pending(listOf(a, b, a, c)))
        book.put(a, "pa"); book.put(b, null); book.put(c, "")
        assertTrue(book.pending(listOf(a, b, c)).isEmpty())
        assertEquals("pa", book.get(a)); assertNull(book.get(b)); assertNull(book.get(c))
        assertEquals("pa", book.lookup()(File("/x/a.pdf")))
        book.retain(listOf(b))
        assertEquals(listOf(a), book.pending(listOf(a, b)))
        book.forget(b)
        assertFalse(book.has(b))
    }

    @Test fun passwordErrors() {
        val real = try { PDDocument.load(locked("pw")).close(); null } catch (e: InvalidPasswordException) { e }
        assertTrue(E5Locks.isPasswordError(real))
        assertTrue(E5Locks.isPasswordError(IOException("wrapped", real)))
        assertTrue(E5Locks.isPasswordError(RuntimeException("password required")))
        assertFalse(E5Locks.isPasswordError(IOException("broken xref")))
        assertFalse(E5Locks.isPasswordError(null))
    }

    @Test fun protectionPlan() {
        assertNull(E5Locks.plan(false, "pw", OutProtection.KEEP))
        assertNull(E5Locks.plan(true, "pw", OutProtection.REMOVE))
        assertEquals("pw" to "pw", E5Locks.plan(true, "pw", OutProtection.KEEP))
        assertEquals("" to "", E5Locks.plan(true, null, OutProtection.KEEP))
        // opened with the user password: permissions kept under a new owner password
        assertEquals("pw" to "o", E5Locks.plan(true, "pw", OutProtection.KEEP, openedAsOwner = false, ownerFallback = "o"))
        assertEquals("pw" to "pw", E5Locks.plan(true, "pw", OutProtection.KEEP, openedAsOwner = true, ownerFallback = "o"))
    }

    @Test fun probeStates() {
        val f = locked("u1")
        assertEquals(LockState.NEEDS_PASSWORD, E5Locks.probeNow(f, null))
        assertEquals(LockState.WRONG_PASSWORD, E5Locks.probeNow(f, "bad"))
        assertEquals(LockState.UNLOCKED, E5Locks.probeNow(f, "u1"))
        assertEquals(LockState.OPEN, E5Locks.probeNow(plain(), null))
        // owner-password-only files open without asking
        assertEquals(LockState.UNLOCKED, E5Locks.probeNow(locked(""), null))
    }

    @Test fun newOutputProtectedLikeSource() {
        val src = locked("u2")
        val kept = tmp("kept"); val open = tmp("open")
        PDDocument.load(src, "u2").use { s ->
            PDDocument().use { nd -> nd.addPage(PDPage(PDRectangle.A5)); E5Locks.protectLike(nd, s, "u2", OutProtection.KEEP); nd.save(kept) }
            PDDocument().use { nd -> nd.addPage(PDPage(PDRectangle.A5)); E5Locks.protectLike(nd, s, "u2", OutProtection.REMOVE); nd.save(open) }
        }
        assertEquals(LockState.NEEDS_PASSWORD, E5Locks.probeNow(kept, null))
        PDDocument.load(kept, "u2").use { d ->
            assertTrue(d.isEncrypted)
            assertFalse(d.currentAccessPermission.canPrint())        // permissions copied from the source
        }
        // audit 5 E-3: opened with the USER password, "remove protection" is not allowed: the output stays protected
        assertEquals(LockState.NEEDS_PASSWORD, E5Locks.probeNow(open, null))
        // with the owner password it is
        val open2 = tmp("open2")
        PDDocument.load(src, "owner").use { s ->
            PDDocument().use { nd -> nd.addPage(PDPage(PDRectangle.A5)); E5Locks.protectLike(nd, s, "owner", OutProtection.REMOVE); nd.save(open2) }
        }
        assertEquals(LockState.OPEN, E5Locks.probeNow(open2, null))
    }

    @Test fun keepProtectionOfEditedSource() {
        val src = locked("u4")
        val out = tmp("keep")
        PDDocument.load(src, "u4").use { d ->
            d.protect(StandardProtectionPolicy("u4", "u4", d.currentAccessPermission))   // as PdfOps.load
            E5Locks.applyTo(d, OutProtection.KEEP, "u4")
            d.save(out)
        }
        assertEquals(LockState.NEEDS_PASSWORD, E5Locks.probeNow(out, null))
        PDDocument.load(out, "u4").use { assertFalse(it.currentAccessPermission.canPrint()) }
    }

    @Test fun removeProtectionFromEditedSource() {
        val src = locked("u3")
        val out = tmp("out")
        PDDocument.load(src, "owner").use { d ->
            // what PdfOps.load does for an encrypted file, then the REMOVE choice (owner access: allowed)
            d.protect(StandardProtectionPolicy("owner", "u3", d.currentAccessPermission))
            E5Locks.applyTo(d, OutProtection.REMOVE)
            d.save(out)
        }
        assertEquals(LockState.OPEN, E5Locks.probeNow(out, null))
        // audit 5 E-3: opened with the user password, REMOVE keeps the protection
        val out2 = tmp("out2")
        PDDocument.load(src, "u3").use { d ->
            d.protect(StandardProtectionPolicy("u3", "u3", d.currentAccessPermission))
            E5Locks.applyTo(d, OutProtection.REMOVE, "u3")
            d.save(out2)
        }
        assertEquals(LockState.NEEDS_PASSWORD, E5Locks.probeNow(out2, null))
    }

    // ---------------- live watermark script (pure) ----------------

    @Test fun printdPatterns() {
        assertEquals("yyyy/mm/dd", LiveWmScript.printdPattern("yyyy/MM/dd"))
        assertEquals("HH:MM", LiveWmScript.printdPattern("HH:mm"))
        assertEquals("dddd d mmmm yyyy h:MM tt", LiveWmScript.printdPattern("EEEE d MMMM yyyy h:mm a"))
        assertEquals("dd.mm.yy HH:MM:ss", LiveWmScript.printdPattern("dd.MM.yy HH:mm:ss"))
        assertEquals("yyyy a\\t", LiveWmScript.printdPattern("yyyy 'at'"))
    }

    @Test fun scriptTemplateKeepsLiveTokens() {
        val ctx = TokenContext(page = 1, pages = 9, date = LocalDate.of(2026, 1, 2), time = LocalTime.of(3, 4), title = "Plan", user = "Ali", file = "f.pdf")
        val t = LiveWmScript.scriptTemplate("{title}: {user} {file} {page}/{pages} {date} {time:HH:mm} {datetime}", ctx)
        assertEquals("Plan: \u0001U\u0001 \u0001F\u0001 \u0001P\u0001/\u0001N\u0001 \u0001Dyyyy/mm/dd\u0001 \u0001DHH:MM\u0001 \u0001Dyyyy/mm/dd HH:MM\u0001", t)
        assertTrue(LiveWmScript.hasLiveTokens("x {date}"))
        assertFalse(LiveWmScript.hasLiveTokens("{title} {hijri}"))
    }

    @Test fun jsStringEscapes() {
        assertEquals("\"a\\\"b\\\\c\\n\"", LiveWmScript.jsString("a\"b\\c\n"))
        assertEquals("\"\\u0633\\u0631\\u064a \\u003c/\\u003e\"", LiveWmScript.jsString("سري </>"))
        val js = LiveWmScript.script("سري {user} {page}", TokenContext(), "علي", true)
        assertTrue(js.all { it.code in 9..126 })            // pure ASCII script
        assertTrue(js.contains(",A=true,"))
        assertTrue(js.contains("\\u0639\\u0644\\u064a"))
    }

    @Test fun colourRotationFlags() {
        assertEquals(0xFFFFFF, LiveWmScript.fadedColor(0x000000, 0f))
        assertEquals(0x000000, LiveWmScript.fadedColor(0x000000, 1f))
        assertEquals(0x7F7F7F, LiveWmScript.fadedColor(0x000000, 0.5f))
        assertEquals(0, LiveWmScript.snapRotation(30f)); assertEquals(90, LiveWmScript.snapRotation(45f)); assertEquals(270, LiveWmScript.snapRotation(-80f)); assertEquals(180, LiveWmScript.snapRotation(190f))
        assertEquals(1 or (1 shl 22) or (1 shl 23), LiveWmScript.fieldFlags(1))
        assertEquals(4096, LiveWmScript.fieldFlags(2) and 4096)
        assertEquals(4 or 64 or 128, LiveWmScript.annotFlags(onScreen = true, onPrint = true))
        assertEquals(32 or 64 or 128, LiveWmScript.annotFlags(onScreen = false, onPrint = false))
    }
}

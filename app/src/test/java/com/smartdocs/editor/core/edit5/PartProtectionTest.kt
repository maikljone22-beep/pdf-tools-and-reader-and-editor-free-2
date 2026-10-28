package com.smartdocs.editor.core.edit5

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** audit 5 E-3 / E-8: split / extract / smart split / mix outputs keep the source's protection and never overwrite. */
class PartProtectionTest {
    @get:Rule val tmp = TemporaryFolder()

    private val owner = "owner-secret"
    private val user = "user"

    private fun restricted() = AccessPermission().apply { setCanPrint(false); setCanExtractContent(false); setCanModify(false) }

    /** AES-128, no print / copy / modify, three pages. */
    private fun encrypted(): File {
        val f = tmp.newFile("enc.pdf")
        PDDocument().use { d ->
            repeat(3) { d.addPage(PDPage()) }
            d.protect(StandardProtectionPolicy(owner, user, restricted()).apply { encryptionKeyLength = 128; setPreferAES(true) })
            d.save(f)
        }
        return f
    }

    private fun plain(): File {
        val f = tmp.newFile("plain.pdf")
        PDDocument().use { d -> repeat(2) { d.addPage(PDPage()) }; d.save(f) }
        return f
    }

    private data class Prot(val length: Int, val perms: Int, val cfm: String?)

    private fun protOf(f: File, pw: String): Prot = PDDocument.load(f, pw).use {
        assertTrue("${f.name} must stay encrypted", it.isEncrypted)
        Prot(it.encryption.length, it.encryption.permissions, it.encryption.stdCryptFilterDictionary?.cryptFilterMethod?.name)
    }

    private fun assertLikeSource(src: File, out: File) {
        val a = protOf(src, user); val b = protOf(out, user)
        assertEquals("key length", a.length, b.length)
        assertEquals("cipher", a.cfm, b.cfm)
        val pa = AccessPermission(a.perms); val pb = AccessPermission(b.perms)
        assertEquals(pa.canPrint(), pb.canPrint()); assertEquals(pa.canExtractContent(), pb.canExtractContent())
        assertEquals(pa.canModify(), pb.canModify()); assertEquals(pa.canModifyAnnotations(), pb.canModifyAnnotations())
        assertEquals(pa.canFillInForm(), pb.canFillInForm()); assertEquals(pa.canAssembleDocument(), pb.canAssembleDocument())
        PDDocument.load(out, user).use { assertFalse("the user password must not grant owner rights", it.currentAccessPermission.isOwnerPermission) }
    }

    private fun dir() = File(tmp.root, "parts_${System.nanoTime()}").apply { mkdirs() }

    @Test fun splitPartsOpenedWithUserPasswordStayProtected() = runBlocking<Unit> {
        val src = encrypted()
        val parts = PageOrganize.writeParts(src, user, listOf(listOf(0), listOf(1, 2)), dir(), "p", PartName.NUMBER)
        assertEquals(2, parts.size)
        parts.forEach { assertLikeSource(src, it) }
        PDDocument.load(parts[1], user).use { assertEquals(2, it.numberOfPages) }
    }

    @Test fun smartSplitPartsStayProtectedAndNeverOverwrite() = runBlocking<Unit> {
        val src = encrypted()
        val d = dir()
        val first = PageOps5.splitAt(src, user, listOf(1), dropSeparators = true, outDir = d, base = "s")
        assertEquals(2, first.size)
        first.forEach { assertLikeSource(src, it) }
        // audit 5 E-8: a second run gets new names
        val second = PageOps5.splitAt(src, user, listOf(1), dropSeparators = true, outDir = d, base = "s")
        assertEquals(2, second.size)
        second.forEach { assertTrue(it !in first) }
        assertEquals(4, d.listFiles { f -> f.extension == "pdf" }!!.size)
        assertNotEquals(first[0].name, second[0].name)
    }

    @Test fun mixIsProtectedLikeTheFirstEncryptedInput() = runBlocking<Unit> {
        val src = encrypted()
        val out = File(tmp.root, "mix.pdf")
        PageOrganize.mix(listOf(plain() to null, src to user), listOf(false, false), out)
        assertLikeSource(src, out)
        PDDocument.load(out, user).use { assertEquals(5, it.numberOfPages) }
    }

    @Test fun ownerCanStillLiftTheProtectionOfTheParts() = runBlocking<Unit> {
        val src = encrypted()
        val parts = PageOrganize.writeParts(src, owner, listOf(listOf(0, 1)), dir(), "o", PartName.NUMBER)
        val part = parts.single()
        val p = protOf(part, owner)
        assertEquals(128, p.length)
        assertFalse(AccessPermission(p.perms).canPrint())
        // the user password still opens it with the restrictions
        PDDocument.load(part, user).use { assertFalse(it.currentAccessPermission.isOwnerPermission); assertFalse(it.currentAccessPermission.canPrint()) }
        // the owner password still lifts them
        val lifted = File(tmp.root, "lifted.pdf")
        PDDocument.load(part, owner).use { d ->
            assertTrue(d.currentAccessPermission.isOwnerPermission)
            assertTrue(E5Locks.canRemove(d.isEncrypted, d.currentAccessPermission.isOwnerPermission))
            d.isAllSecurityToBeRemoved = true; d.save(lifted)
        }
        PDDocument.load(lifted).use { assertFalse(it.isEncrypted) }
    }

    @Test fun extractOfAPlainFileStaysPlain() = runBlocking<Unit> {
        val parts = PageOrganize.writeParts(plain(), null, listOf(listOf(1)), dir(), "x", PartName.NUMBER)
        PDDocument.load(parts.single()).use { assertFalse(it.isEncrypted); assertEquals(1, it.numberOfPages) }
    }
}

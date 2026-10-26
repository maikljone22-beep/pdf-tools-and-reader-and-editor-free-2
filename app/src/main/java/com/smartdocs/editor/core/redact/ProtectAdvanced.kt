package com.smartdocs.editor.core.redact

import com.smartdocs.editor.core.pdf.PdfOps
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.SecureRandom

enum class EncAlgo(val keyLength: Int, val aes: Boolean) { AES_256(256, true), AES_128(128, true), RC4_128(128, false) }

enum class PrintLevel { NONE, LOW, HIGH }

/** Acrobat-style "changes allowed" presets. */
enum class ChangeLevel { NONE, ASSEMBLY, FORMS, ANNOTATE_FORMS, ALL_BUT_EXTRACT }

/** The eight PDF permission bits a viewer honours. */
data class Permissions(
    val print: Boolean = true,
    val printHigh: Boolean = true,
    val modify: Boolean = true,
    val assemble: Boolean = true,
    val copy: Boolean = true,
    val accessibility: Boolean = true,
    val annotate: Boolean = true,
    val fillForms: Boolean = true,
) {
    fun withPrint(l: PrintLevel) = copy(print = l != PrintLevel.NONE, printHigh = l == PrintLevel.HIGH)
    fun withChanges(l: ChangeLevel) = when (l) {
        ChangeLevel.NONE -> copy(modify = false, assemble = false, annotate = false, fillForms = false)
        ChangeLevel.ASSEMBLY -> copy(modify = false, assemble = true, annotate = false, fillForms = false)
        ChangeLevel.FORMS -> copy(modify = false, assemble = false, annotate = false, fillForms = true)
        ChangeLevel.ANNOTATE_FORMS -> copy(modify = false, assemble = false, annotate = true, fillForms = true)
        ChangeLevel.ALL_BUT_EXTRACT -> copy(modify = true, assemble = true, annotate = true, fillForms = true)
    }
    val printLevel get() = when { !print -> PrintLevel.NONE; printHigh -> PrintLevel.HIGH; else -> PrintLevel.LOW }
    val changeLevel: ChangeLevel? get() = ChangeLevel.values().firstOrNull { withChanges(it) == this }
}

data class ProtectOptions(
    val openPassword: String = "",
    val ownerPassword: String = "",
    val algo: EncAlgo = EncAlgo.AES_256,
    val permissions: Permissions = Permissions(),
    /** What is encrypted; [EncScope.ALL_BUT_METADATA] and [EncScope.ATTACHMENTS_ONLY] need an AES algorithm. */
    val scope: EncScope = EncScope.ALL,
)

/**
 * Detailed encryption (J6): AES-256 / AES-128 / RC4-128 (PdfBox-Android 2.0.27 StandardProtectionPolicy), the
 * eight permission bits with print and change presets. Without an owner password a random one is used, so the
 * permissions cannot be lifted by re-entering the open password. [ProtectOptions.scope] also offers "everything
 * except metadata" and "attachments only" ([ScopedSecurityHandler]).
 */
object ProtectAdvanced {

    fun accessPermission(p: Permissions): AccessPermission = AccessPermission().apply {
        setCanPrint(p.print)
        setCanPrintFaithful(p.print && p.printHigh)
        setCanModify(p.modify)
        setCanAssembleDocument(p.assemble)
        setCanExtractContent(p.copy)
        setCanExtractForAccessibility(p.accessibility)
        setCanModifyAnnotations(p.annotate)
        setCanFillInForm(p.fillForms)
    }

    fun randomPassword(len: Int = 24): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#%*-_"
        val r = SecureRandom()
        return (1..len).map { chars[r.nextInt(chars.length)] }.joinToString("")
    }

    /** Encrypts [src] into [out]. [currentPassword] opens an already protected file. Returns the owner password used. */
    suspend fun protect(src: File, out: File, currentPassword: String?, o: ProtectOptions): String = withContext(Dispatchers.IO) {
        require(out.canonicalPath != src.canonicalPath) { "out must differ from src" }
        require(o.openPassword.isNotEmpty() || o.permissions != Permissions()) { "nothing to protect" }
        require(o.scope == EncScope.ALL || o.algo.aes) { "this scope needs AES" }
        require(o.scope != EncScope.ATTACHMENTS_ONLY || o.openPassword.isNotEmpty()) { "attachments need an open password" }
        val owner = o.ownerPassword.ifEmpty { randomPassword() }
        PdfOps.load(src, currentPassword).use { doc ->
            val policy = StandardProtectionPolicy(owner, o.openPassword, accessPermission(o.permissions))
            policy.encryptionKeyLength = o.algo.keyLength
            policy.setPreferAES(o.algo.aes)
            if (o.scope == EncScope.ALL) doc.protect(policy)
            else { ScopedSecurityHandler.register(); doc.protect(ScopedProtectionPolicy(policy, o.scope)) }
            doc.save(out)
        }
        owner
    }
}

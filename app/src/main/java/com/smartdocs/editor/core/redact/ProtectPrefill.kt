package com.smartdocs.editor.core.redact

import com.smartdocs.editor.core.security.Perm
import com.smartdocs.editor.core.security.SecMethod
import com.smartdocs.editor.core.security.SecurityInfo

/** The current protection of a file as the "protect" form shows it ("Change Settings", row 3). Passwords stay empty. */
data class ProtectPrefill(val algo: EncAlgo, val permissions: Permissions, val restrict: Boolean, val scope: EncScope)

object ProtectPrefills {
    /** Null when [info] is not password security (nothing to pre-fill). */
    fun from(info: SecurityInfo?): ProtectPrefill? {
        if (info == null || info.method != SecMethod.PASSWORD) return null
        val algo = when {
            info.algorithm == "AES" && info.keyLength >= 256 -> EncAlgo.AES_256
            info.algorithm == "AES" -> EncAlgo.AES_128
            info.keyLength >= 128 -> EncAlgo.RC4_128
            else -> EncAlgo.RC4_40
        }
        val a = info.allowed
        val perms = Permissions(
            print = a.getValue(Perm.PRINT), printHigh = a.getValue(Perm.PRINT_HIGH), modify = a.getValue(Perm.MODIFY),
            assemble = a.getValue(Perm.ASSEMBLE), copy = a.getValue(Perm.COPY), accessibility = a.getValue(Perm.ACCESSIBILITY),
            annotate = a.getValue(Perm.ANNOTATE), fillForms = a.getValue(Perm.FILL_FORMS),
        )
        val scope = if (!info.encryptMetadata && algo.aes) EncScope.ALL_BUT_METADATA else EncScope.ALL
        return ProtectPrefill(algo, perms, perms != Permissions(), scope)
    }
}

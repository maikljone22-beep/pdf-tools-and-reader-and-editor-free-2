package com.smartdocs.editor.core.edit5

import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File

/** audit 5 E-3: whether a file opened with a password grants owner access (needed to offer "remove protection"). */
object E5Owner {
    /** True for an unencrypted file or one that [password] opens with owner rights; false otherwise (and on errors). */
    fun ownerAccess(file: File, password: String?): Boolean = runCatching {
        PDDocument.load(file, password.orEmpty()).use { d -> E5Locks.canRemove(d.isEncrypted, d.currentAccessPermission.isOwnerPermission) }
    }.getOrDefault(false)
}

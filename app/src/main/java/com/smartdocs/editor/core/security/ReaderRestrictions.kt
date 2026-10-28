package com.smartdocs.editor.core.security

/** How a document is protected. */
enum class SecMethod { NONE, PASSWORD, CERTIFICATE, OTHER }

/** The eight permissions of the PDF permission flags (/P), in the order the document properties list them. */
enum class Perm { PRINT, PRINT_HIGH, MODIFY, COPY, ACCESSIBILITY, ANNOTATE, FILL_FORMS, ASSEMBLE }

/**
 * Security properties of an open document (nitro-protect2). [permissions] is /P for password security and the
 * recipient's permission bytes for certificate security; [ownerAccess] is true when the document was opened with
 * the owner (permissions) password, or the opener has every permission anyway (PdfBox `isOwnerPermission`).
 */
data class SecurityInfo(
    val method: SecMethod,
    val filter: String = "",
    val subFilter: String = "",
    /** "RC4", "AES" or "" when the document is not encrypted. */
    val algorithm: String = "",
    val keyLength: Int = 0,
    val version: Int = 0,
    val revision: Int = 0,
    val encryptMetadata: Boolean = true,
    val permissions: Int = -1,
    val ownerAccess: Boolean = true,
) {
    val encrypted: Boolean get() = method != SecMethod.NONE

    /** Allowed / denied state of every [Perm] (see [PermissionBits.allowed]). */
    val allowed: Map<Perm, Boolean> get() = PermissionBits.allowed(permissions, revision)

    companion object { val NONE = SecurityInfo(SecMethod.NONE) }
}

/**
 * The PDF permission flags (ISO 32000-1 table 22; bit 1 is the lowest). Revision 2 knows only bits 3–6: assembling
 * follows "modify", form filling follows "annotate", high-resolution printing follows "print" and accessibility
 * follows "copy". From revision 3 on, bit 9 allows filling forms and bit 11 assembling even when bits 6 / 4 are
 * clear, and bit 12 is needed for high-resolution (faithful) printing. Accessibility is allowed by bit 10 or by
 * the copy bit (copying everything includes the screen-reader text).
 */
object PermissionBits {
    fun bit(p: Int, n: Int): Boolean = p and (1 shl (n - 1)) != 0

    fun allowed(p: Int, revision: Int): Map<Perm, Boolean> {
        val r2 = revision in 1..2
        val print = bit(p, 3); val modify = bit(p, 4); val copy = bit(p, 5); val annotate = bit(p, 6)
        val m = LinkedHashMap<Perm, Boolean>()
        m[Perm.PRINT] = print
        m[Perm.PRINT_HIGH] = if (r2) print else print && bit(p, 12)
        m[Perm.MODIFY] = modify
        m[Perm.COPY] = copy
        m[Perm.ACCESSIBILITY] = if (r2) copy else copy || bit(p, 10)
        m[Perm.ANNOTATE] = annotate
        m[Perm.FILL_FORMS] = if (r2) annotate else annotate || bit(p, 9)
        m[Perm.ASSEMBLE] = if (r2) modify else modify || bit(p, 11)
        return m
    }
}

/** Things a reader user can do that a document's restrictions (or Protected View) may forbid. */
enum class DocAction { PRINT, COPY, ANNOTATE, MODIFY, FILL_FORMS, ASSEMBLE, SAVE_UNPROTECTED, CHANGE_SECURITY }

/**
 * Why an action is refused: the author's permissions, the file is shown in Protected View, or (tab audit D8) a
 * certification / FieldMDP lock of a signature forbids it ([CertifiedLock]).
 */
enum class Block { AUTHOR, PROTECTED_VIEW, CERTIFIED }

/**
 * Decision table of the reader's restrictions (rows 2 and 6). A document opened with its user password (or an
 * owner-only file opened without a password) honours its permission flags; opening it with the owner password lifts
 * them. Making an unprotected copy or changing the security always needs owner access. Protected View refuses every
 * change to the document but leaves reading, printing and copying to the permissions.
 */
object ReaderRestrictions {
    private val EDITING = setOf(DocAction.ANNOTATE, DocAction.MODIFY, DocAction.FILL_FORMS, DocAction.ASSEMBLE, DocAction.CHANGE_SECURITY)

    fun check(info: SecurityInfo?, protectedView: Boolean, action: DocAction): Block? {
        if (protectedView && action in EDITING) return Block.PROTECTED_VIEW
        if (info == null || !info.encrypted || info.ownerAccess) return null
        val a = info.allowed
        val ok = when (action) {
            DocAction.PRINT -> a.getValue(Perm.PRINT)
            DocAction.COPY -> a.getValue(Perm.COPY)
            DocAction.ANNOTATE -> a.getValue(Perm.ANNOTATE)
            DocAction.MODIFY -> a.getValue(Perm.MODIFY)
            DocAction.FILL_FORMS -> a.getValue(Perm.FILL_FORMS)
            DocAction.ASSEMBLE -> a.getValue(Perm.ASSEMBLE)
            DocAction.SAVE_UNPROTECTED, DocAction.CHANGE_SECURITY -> false
        }
        return if (ok) null else Block.AUTHOR
    }

    /** True when any action is refused by the author's permissions (the reader shows a "restricted" note). */
    fun restricted(info: SecurityInfo?): Boolean = DocAction.values().any { check(info, false, it) == Block.AUTHOR }

    /** The permission a reader tool screen (Routes.tool id) needs on the open document; null = none (read only). */
    fun actionForTool(id: String): DocAction? = when (id) {
        "organize", "merge", "split" -> DocAction.ASSEMBLE
        "forms", "digital_sign" -> DocAction.FILL_FORMS
        "search_highlight" -> DocAction.ANNOTATE
        "protect_advanced", "protect", "cert_protect" -> DocAction.CHANGE_SECURITY
        "unlock" -> DocAction.SAVE_UNPROTECTED
        "ai", "compare", "pdf_to_images", "pdf_to_text", "convert_hub", "extract_images", "podcast" -> DocAction.COPY
        "info", "properties", "signatures" -> null
        else -> DocAction.MODIFY
    }

    /** The permission a one-step reader action (QuickAction name) needs; null = none. */
    fun actionForQuick(name: String): DocAction? = when (name) {
        "PROTECT" -> DocAction.CHANGE_SECURITY
        "UNLOCK" -> DocAction.SAVE_UNPROTECTED
        "ROTATE_ALL", "ADD_BLANK", "DELETE_PAGE", "EXTRACT" -> DocAction.ASSEMBLE
        "PRINT" -> DocAction.PRINT
        "PROPERTIES", "SHARE" -> null
        else -> DocAction.MODIFY
    }
}

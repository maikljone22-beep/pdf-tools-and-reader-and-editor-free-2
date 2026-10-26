package com.smartdocs.editor.core.sign

/**
 * Chain building + trust decision, generic over the certificate type so it can be unit-tested without real
 * certificates. With X509Certificate: subject = subjectX500Principal.name, issuer = issuerX500Principal.name,
 * signedBy(child, parent) = runCatching { child.verify(parent.publicKey) }.isSuccess.
 */
object TrustPath {

    enum class Status { TRUSTED, UNTRUSTED }

    data class Result<T>(val status: Status, val chain: List<T>, val anchor: T?)

    class Ops<T>(
        val subject: (T) -> String,
        val issuer: (T) -> String,
        val fingerprint: (T) -> String,
        val signedBy: (child: T, parent: T) -> Boolean,
    )

    /** Leaf first, following issuer → subject links through [pool] (signature-checked), stopping at a self-issued cert. */
    fun <T> build(leaf: T, pool: List<T>, ops: Ops<T>, maxDepth: Int = 10): List<T> {
        val chain = arrayListOf(leaf)
        var cur = leaf
        repeat(maxDepth) {
            if (ops.subject(cur) == ops.issuer(cur)) return chain
            val parent = pool.firstOrNull { p ->
                ops.subject(p) == ops.issuer(cur) && chain.none { ops.fingerprint(it) == ops.fingerprint(p) } && ops.signedBy(cur, p)
            } ?: return chain
            chain += parent; cur = parent
        }
        return chain
    }

    /**
     * Trusted when a certificate of the chain is in [trusted] (same fingerprint), or the last certificate of the chain
     * was issued and signed by a trusted certificate.
     */
    fun <T> evaluate(leaf: T, embedded: List<T>, trusted: List<T>, ops: Ops<T>): Result<T> {
        val chain = build(leaf, embedded + trusted, ops)
        val fps = trusted.map(ops.fingerprint).toSet()
        chain.firstOrNull { ops.fingerprint(it) in fps }?.let { return Result(Status.TRUSTED, chain, it) }
        val last = chain.last()
        val anchor = trusted.firstOrNull { t -> ops.subject(t) == ops.issuer(last) && ops.signedBy(last, t) }
        return if (anchor != null) Result(Status.TRUSTED, chain + anchor, anchor) else Result(Status.UNTRUSTED, chain, null)
    }
}

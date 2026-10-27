package com.smartdocs.editor.core.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Per-signature metadata kept beside the Room table (nitro-quicksign), in `filesDir/signature_meta.json` keyed by the
 * signature's image path, so no Room migration is needed: the password lock and the last time it was placed
 * (Recently used). A missing entry = unlocked, never used.
 */
data class SignatureMeta(
    val lastUsed: Long = 0L,
    /** PBKDF2-HMAC-SHA256 of the PIN / password; all three set or all null. */
    val salt: String? = null,
    val hash: String? = null,
    val iterations: Int = 0,
    // nitro-fillsign2
    /** User label shown in Manage Signatures (empty = "Signature" / "Initials"). */
    val name: String = "",
    /** Position in the palette (Manage Signatures move up / down); -1 = not ordered yet (newest first). */
    val order: Int = -1,
    /** Default placement width in points ("Set current size as default" / "Keep original size"); 0 = automatic. */
    val widthPt: Float = 0f,
    /** Temporary signature: deleted on the next app start, never synced or exported. */
    val temporary: Boolean = false,
    /** Bumped when the image is replaced or recoloured in place (UI cache key). */
    val rev: Int = 0,
) {
    val locked: Boolean get() = salt != null && hash != null && iterations > 0
}

object SignatureMetaStore {
    private val mutex = Mutex()
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val state = MutableStateFlow<Map<String, SignatureMeta>>(emptyMap())
    @Volatile private var loaded = false

    private fun file(c: Context) = File(c.applicationContext.filesDir, "signature_meta.json")

    // ---------- pure ----------

    fun encode(map: Map<String, SignatureMeta>): String = JSONObject().apply {
        map.toSortedMap().forEach { (path, m) ->
            val o = JSONObject()
            if (m.lastUsed > 0) o.put("used", m.lastUsed)
            if (m.locked) o.put("salt", m.salt).put("hash", m.hash).put("iter", m.iterations)
            if (m.name.isNotEmpty()) o.put("name", m.name)
            if (m.order >= 0) o.put("order", m.order)
            if (m.widthPt > 0f) o.put("width", m.widthPt.toDouble())
            if (m.temporary) o.put("temp", true)
            if (m.rev > 0) o.put("rev", m.rev)
            if (o.length() > 0) put(path, o)
        }
    }.toString()

    fun decode(s: String?): Map<String, SignatureMeta> {
        val o = s?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return emptyMap()
        return o.keys().asSequence().mapNotNull { k ->
            val e = o.optJSONObject(k) ?: return@mapNotNull null
            val salt = e.optString("salt").ifBlank { null }; val hash = e.optString("hash").ifBlank { null }; val iter = e.optInt("iter")
            val lock = salt != null && hash != null && iter > 0
            k to SignatureMeta(e.optLong("used"), if (lock) salt else null, if (lock) hash else null, if (lock) iter else 0,
                name = e.optString("name"), order = e.optInt("order", -1), widthPt = e.optDouble("width", 0.0).toFloat().takeIf { it.isFinite() && it > 0f } ?: 0f,
                temporary = e.optBoolean("temp"), rev = e.optInt("rev"))
        }.toMap()
    }

    /**
     * Palette order (nitro-fillsign2): signatures with a stored [SignatureMeta.order] first (ascending), then the
     * others in the given order ([paths] comes newest first from Room).
     */
    fun ordered(map: Map<String, SignatureMeta>, paths: List<String>): List<String> {
        val (withOrder, rest) = paths.partition { (map[it]?.order ?: -1) >= 0 }
        return withOrder.sortedBy { map[it]!!.order } + rest
    }

    /** [paths] (already in palette order) with the entry at [index] moved by [delta] (-1 up, +1 down); unchanged at the ends. */
    fun move(paths: List<String>, index: Int, delta: Int): List<String> {
        val to = index + delta
        if (index !in paths.indices || to !in paths.indices) return paths
        return paths.toMutableList().apply { add(to, removeAt(index)) }
    }

    /** Paths sorted by most recent use (only used ones), limited to [max]. */
    fun recent(map: Map<String, SignatureMeta>, paths: Collection<String>, max: Int = 5): List<String> =
        paths.filter { (map[it]?.lastUsed ?: 0L) > 0L }.sortedByDescending { map[it]!!.lastUsed }.take(max)

    // ---------- storage ----------

    /** Observable map; loads the file on first call. */
    fun flow(context: Context): StateFlow<Map<String, SignatureMeta>> {
        ensureLoaded(context)
        return state.asStateFlow()
    }

    fun get(context: Context, path: String): SignatureMeta = ensureLoaded(context)[path] ?: SignatureMeta()

    private fun ensureLoaded(context: Context): Map<String, SignatureMeta> {
        if (!loaded) synchronized(this) {
            if (!loaded) { state.value = decode(runCatching { file(context).takeIf { it.exists() }?.readText() }.getOrNull()); loaded = true }
        }
        return state.value
    }

    private fun update(context: Context, f: (Map<String, SignatureMeta>) -> Map<String, SignatureMeta>) {
        ensureLoaded(context)
        synchronized(this) { state.value = f(state.value) }
        val target = file(context)
        io.launch {
            mutex.withLock {
                runCatching {
                    val tmp = File(target.parentFile, target.name + ".tmp")
                    tmp.writeText(encode(state.value))   // always the latest map, so writes can never go back in time
                    if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
                }
            }
        }
    }

    /** Records that the signature at [path] was placed now (Recently used). */
    fun markUsed(context: Context, path: String, now: Long = System.currentTimeMillis()) =
        update(context) { m -> m + (path to (m[path] ?: SignatureMeta()).copy(lastUsed = now)) }

    internal fun setLock(context: Context, path: String, salt: String?, hash: String?, iterations: Int) =
        update(context) { m -> m + (path to (m[path] ?: SignatureMeta()).copy(salt = salt, hash = hash, iterations = iterations)) }

    /** An imported locked signature: its lock record (and name) from the data bundle. */
    fun setLockRecord(context: Context, path: String, lock: SignatureMeta) =
        update(context) { m -> m + (path to (m[path] ?: SignatureMeta()).copy(salt = lock.salt, hash = lock.hash, iterations = lock.iterations, name = lock.name)) }

    /** A copy of a signature (e.g. its black-and-white version) inherits the lock (and the temporary flag) of the original. */
    fun copyLock(context: Context, from: String, to: String) {
        val src = get(context, from)
        if (src.locked) setLock(context, to, src.salt, src.hash, src.iterations)
        if (src.temporary) setTemporary(context, to, true)
    }

    /** Forget a deleted signature. */
    fun remove(context: Context, path: String) = update(context) { it - path }

    // ---------- nitro-fillsign2 ----------

    private fun edit(context: Context, path: String, f: (SignatureMeta) -> SignatureMeta) =
        update(context) { m -> m + (path to f(m[path] ?: SignatureMeta())) }

    fun setName(context: Context, path: String, name: String) = edit(context, path) { it.copy(name = name.trim().take(60)) }

    /** Default placement width in points (0 = automatic). */
    fun setWidth(context: Context, path: String, widthPt: Float) = edit(context, path) { it.copy(widthPt = widthPt.coerceAtLeast(0f)) }

    fun setTemporary(context: Context, path: String, temporary: Boolean) = edit(context, path) { it.copy(temporary = temporary) }

    /** The image file at [path] was rewritten (recolour / replace). */
    fun bumpRev(context: Context, path: String) = edit(context, path) { it.copy(rev = it.rev + 1) }

    /** Stores the palette order of [paths] (all signatures, first = top). */
    fun setOrder(context: Context, paths: List<String>) = update(context) { m ->
        m + paths.mapIndexed { i, p -> p to (m[p] ?: SignatureMeta()).copy(order = i) }
    }

    /** Paths of temporary signatures (deleted at the next start by [TemporarySignatures.cleanUp]). */
    fun temporaryPaths(context: Context): List<String> = ensureLoaded(context).filterValues { it.temporary }.keys.toList()
}

/**
 * Password lock of a saved QuickSign signature (nitro-quicksign). The PIN / password is never stored: only a random
 * 16-byte salt and PBKDF2-HMAC-SHA256 (120 000 iterations). UI gate: `rememberSignatureGate()` in ui/signature.
 * Wrong attempts are throttled (5 tries, then 30 s) per process.
 */
object SignatureLock {
    const val ITERATIONS = 120_000
    const val MIN_LENGTH = 4
    private const val MAX_TRIES = 5
    private const val LOCKOUT_MS = 30_000L
    private var failures = 0
    private var blockedUntil = 0L

    fun hash(secret: CharArray, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray {
        val spec = PBEKeySpec(secret, salt, iterations, 256)
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** New lock record for [secret]: (salt hex, hash hex, iterations). */
    fun create(secret: CharArray, iterations: Int = ITERATIONS): Triple<String, String, Int> {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return Triple(hex(salt), hex(hash(secret, salt, iterations)), iterations)
    }

    fun matches(meta: SignatureMeta, secret: CharArray): Boolean {
        if (!meta.locked) return true
        val h = hash(secret, unhex(meta.salt!!), meta.iterations)
        return MessageDigest.isEqual(h, unhex(meta.hash!!))
    }

    fun isLocked(context: Context, path: String): Boolean = SignatureMetaStore.get(context, path).locked

    /** Milliseconds until the next attempt is allowed (0 = now). */
    fun waitMs(now: Long = System.currentTimeMillis()): Long = (blockedUntil - now).coerceAtLeast(0L)

    /**
     * Checks [secret] against the lock of [path]. True when the signature is unlocked or the secret matches. Call from a
     * coroutine: PBKDF2 runs on Dispatchers.Default. Throttled after repeated failures (returns false while blocked).
     */
    suspend fun unlock(context: Context, path: String, secret: CharArray): Boolean {
        val meta = SignatureMetaStore.get(context, path)
        if (!meta.locked) return true
        if (waitMs() > 0) return false
        val ok = withContext(Dispatchers.Default) { matches(meta, secret) }
        synchronized(this) {
            if (ok) failures = 0 else if (++failures >= MAX_TRIES) { failures = 0; blockedUntil = System.currentTimeMillis() + LOCKOUT_MS }
        }
        // nitro-fillsign2: decrypt an imported locked signature / refresh its encrypted copy for the data bundle
        if (ok) withContext(Dispatchers.IO) { runCatching { LockedSignatureFiles.sync(File(path), secret) } }
        return ok
    }

    /** Locks [path] with [secret] (replacing any previous one). */
    suspend fun lock(context: Context, path: String, secret: CharArray) {
        val (salt, hash, iter) = withContext(Dispatchers.Default) { create(secret) }
        SignatureMetaStore.setLock(context, path, salt, hash, iter)
        // nitro-fillsign2: encrypted copy under the same PIN, the only form a locked signature leaves the device in
        withContext(Dispatchers.IO) { runCatching { LockedSignatureFiles.write(File(path), secret) } }
    }

    /** Removes the lock; the caller must have unlocked first. */
    fun clear(context: Context, path: String) {
        SignatureMetaStore.setLock(context, path, null, null, 0)
        LockedSignatureFiles.blobOf(File(path)).delete()
    }
}

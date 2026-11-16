package com.smartdocs.editor.core.updates

import org.json.JSONObject
import java.io.IOException
import java.net.URI

enum class UpdateProblem { INVALID_FEED, UNSAFE_URL, WRONG_PACKAGE, INCOMPATIBLE, DOWNLOAD, CHECKSUM, SIGNATURE, NOT_NEWER, INSTALL }
class UpdateException(val problem: UpdateProblem) : IOException(problem.name)
data class UpdateArtifact(val url: String, val sha256: String, val size: Long, val abis: List<String>)
data class UpdateOffer(val applicationId: String, val versionCode: Long, val versionName: String, val minSdk: Int, val notes: String, val artifact: UpdateArtifact)

object UpdateUrls {
    fun requireHttps(value: String): String {
        val uri = try { URI(value) } catch (_: Exception) { throw UpdateException(UpdateProblem.UNSAFE_URL) }
        if (value.length > 4096 || !uri.scheme.equals("https", true) || uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.fragment != null)
            throw UpdateException(UpdateProblem.UNSAFE_URL)
        return value
    }
}

/** Deliberately small, versioned feed. No executable instructions or silent downgrade paths. */
object UpdateManifest {
    const val MAX_APK_BYTES = 512L * 1024 * 1024
    fun parse(json: String, applicationId: String, installedVersion: Long, sdk: Int, supportedAbis: List<String>): UpdateOffer? {
        try {
            val root = JSONObject(json)
            if (root.getInt("schemaVersion") != 1) throw UpdateException(UpdateProblem.INVALID_FEED)
            if (root.getString("applicationId") != applicationId) throw UpdateException(UpdateProblem.WRONG_PACKAGE)
            val version = root.get("versionCode").toString().toLongOrNull()?.takeIf { it > 0 } ?: throw UpdateException(UpdateProblem.INVALID_FEED)
            if (version <= installedVersion) return null
            val minSdk = root.getInt("minSdk")
            if (minSdk < 1) throw UpdateException(UpdateProblem.INVALID_FEED)
            if (minSdk > sdk) throw UpdateException(UpdateProblem.INCOMPATIBLE)
            val name = root.getString("versionName").trim().takeIf { it.isNotEmpty() && it.length <= 100 } ?: throw UpdateException(UpdateProblem.INVALID_FEED)
            val raw = root.getJSONArray("artifacts")
            if (raw.length() !in 1..16) throw UpdateException(UpdateProblem.INVALID_FEED)
            val artifacts = (0 until raw.length()).map { i ->
                val item = raw.getJSONObject(i)
                val size = item.get("size").toString().toLongOrNull() ?: throw UpdateException(UpdateProblem.INVALID_FEED)
                val hash = item.getString("sha256").lowercase()
                if (size !in 1..MAX_APK_BYTES || !hash.matches(Regex("[0-9a-f]{64}"))) throw UpdateException(UpdateProblem.INVALID_FEED)
                val abis = item.getJSONArray("abis")
                if (abis.length() > 16) throw UpdateException(UpdateProblem.INVALID_FEED)
                UpdateArtifact(UpdateUrls.requireHttps(item.getString("url")), hash, size, (0 until abis.length()).map { abis.getString(it) })
            }
            val artifact = supportedAbis.firstNotNullOfOrNull { abi -> artifacts.firstOrNull { abi in it.abis } }
                ?: artifacts.firstOrNull { it.abis.isEmpty() } ?: throw UpdateException(UpdateProblem.INCOMPATIBLE)
            return UpdateOffer(applicationId, version, name, minSdk, root.optString("notes").take(8000), artifact)
        } catch (e: UpdateException) { throw e }
        catch (_: Exception) { throw UpdateException(UpdateProblem.INVALID_FEED) }
    }
}

data class ApkIdentity(val applicationId: String, val versionCode: Long, val minSdk: Int, val signers: Set<String>, val signerHistory: Set<String> = emptySet())
object UpdateIdentity {
    fun verify(installed: ApkIdentity, candidate: ApkIdentity, offer: UpdateOffer, sdk: Int) {
        if (candidate.applicationId != installed.applicationId || candidate.applicationId != offer.applicationId) throw UpdateException(UpdateProblem.WRONG_PACKAGE)
        if (candidate.versionCode != offer.versionCode || candidate.versionCode <= installed.versionCode) throw UpdateException(UpdateProblem.NOT_NEWER)
        if (candidate.minSdk > sdk) throw UpdateException(UpdateProblem.INCOMPATIBLE)
        val same = installed.signers.isNotEmpty() && installed.signers == candidate.signers
        val rotated = installed.signers.size == 1 && candidate.signers.size == 1 && candidate.signerHistory.containsAll(installed.signers)
        if (!same && !rotated) throw UpdateException(UpdateProblem.SIGNATURE)
    }
}

package com.smartdocs.editor.core.sign

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.cert.X509Certificate

/**
 * Foxit §28 "save settings as a new policy" for certificate protection: named sets of recipients (their public
 * certificates, never a private key), each with its permissions, plus the cipher and the metadata option.
 * JSON in `filesDir/cert_policies.json`; the certificates are stored DER / base64.
 */
object CertPolicies {

    data class Member(val certificate: X509Certificate, val perms: CertEncryption.Perms)

    data class Policy(val name: String, val members: List<Member>, val aes256: Boolean = true, val encryptMetadata: Boolean = true)

    private fun permsJson(p: CertEncryption.Perms) = JSONObject().apply {
        put("print", p.print); put("fill", p.fillForms); put("annotate", p.annotate); put("assemble", p.assemble)
        put("modify", p.modify); put("access", p.copyAccessibility); put("extract", p.extract)
    }

    private fun permsOf(o: JSONObject?) = CertEncryption.Perms(
        print = o?.optBoolean("print", true) ?: true, fillForms = o?.optBoolean("fill", true) ?: true,
        annotate = o?.optBoolean("annotate", true) ?: true, assemble = o?.optBoolean("assemble", true) ?: true,
        modify = o?.optBoolean("modify", true) ?: true, copyAccessibility = o?.optBoolean("access", true) ?: true,
        extract = o?.optBoolean("extract", true) ?: true,
    )

    fun toJson(list: List<Policy>): String = JSONArray().apply {
        list.forEach { p ->
            put(JSONObject().apply {
                put("name", p.name); put("aes256", p.aes256); put("meta", p.encryptMetadata)
                put("members", JSONArray().apply {
                    p.members.forEach { m -> put(JSONObject().apply { put("cert", java.util.Base64.getEncoder().encodeToString(m.certificate.encoded)); put("perms", permsJson(m.perms)) }) }
                })
            })
        }
    }.toString()

    fun fromJson(text: String): List<Policy> = runCatching {
        val a = JSONArray(text)
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val ms = o.optJSONArray("members") ?: JSONArray()
            val members = (0 until ms.length()).mapNotNull { j ->
                val m = ms.optJSONObject(j) ?: return@mapNotNull null
                val der = runCatching { java.util.Base64.getDecoder().decode(m.optString("cert")) }.getOrNull() ?: return@mapNotNull null
                val c = TrustedCerts.parse(der).firstOrNull() ?: return@mapNotNull null
                Member(c, permsOf(m.optJSONObject("perms")))
            }
            Policy(name, members, o.optBoolean("aes256", true), o.optBoolean("meta", true))
        }
    }.getOrDefault(emptyList())

    /** Adds or replaces (same name, case-insensitive), sorted by name. */
    fun upsert(list: List<Policy>, p: Policy): List<Policy> = (list.filterNot { it.name.equals(p.name, true) } + p).sortedBy { it.name.lowercase() }

    fun delete(list: List<Policy>, name: String): List<Policy> = list.filterNot { it.name.equals(name, true) }

    fun file(context: Context) = File(context.filesDir, "cert_policies.json")

    fun load(context: Context): List<Policy> = file(context).takeIf { it.exists() }?.let { fromJson(it.readText()) }.orEmpty()

    fun save(context: Context, list: List<Policy>) {
        val f = file(context); val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(toJson(list))
        if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() }
    }
}

package com.smartdocs.editor.core.gaps6

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Stable random id of this installation (not a hardware id). */
object InstallId {
    private const val PREFS = "g6_install"
    fun get(context: Context): String {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.getString("id", null)?.let { return it }
        val id = UUID.randomUUID().toString()
        p.edit().putString("id", id).apply()
        return id
    }

    /** "Samsung SM-F946B" style device name. */
    fun deviceName(): String {
        val m = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
        val model = Build.MODEL.orEmpty()
        return if (model.startsWith(m, ignoreCase = true)) model else "$m $model".trim()
    }
}

/**
 * Devices activated with an activation code. There is no licence server in core/billing (codes are verified
 * offline), so the list is kept locally next to the activation record: every successful activation on this
 * install adds/updates this device. A server-side list can replace [load] later (see MODULE_gaps6_r5.md).
 */
object ActivatedDevices {
    private const val PREFS = "g6_devices"

    data class Device(val id: String, val name: String, val codeTail: String, val plan: String, val activatedAt: Long, val thisDevice: Boolean)

    fun load(context: Context): List<Device> {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val me = InstallId.get(context)
        val arr = runCatching { JSONArray(p.getString("list", "[]")) }.getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            Device(id, o.optString("name"), o.optString("code"), o.optString("plan"), o.optLong("at"), id == me)
        }.sortedByDescending { it.activatedAt }
    }

    private fun save(context: Context, list: List<Device>) {
        val arr = JSONArray()
        list.forEach { d -> arr.put(JSONObject().put("id", d.id).put("name", d.name).put("code", d.codeTail).put("plan", d.plan).put("at", d.activatedAt)) }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("list", arr.toString()).apply()
    }

    /** Call after a successful `ActivationCodes.activate`. Only the last 4 characters of the code are kept. */
    fun recordActivation(context: Context, code: String, plan: String) {
        val me = InstallId.get(context)
        val tail = code.filter { it.isLetterOrDigit() }.takeLast(4).uppercase()
        val list = load(context).filter { it.id != me } + Device(me, InstallId.deviceName(), tail, plan, System.currentTimeMillis(), true)
        save(context, list)
    }

    fun remove(context: Context, id: String) = save(context, load(context).filter { it.id != id })
}

/** Local referral state (invites sent, friend's code redeemed). */
object ReferralStore {
    private const val PREFS = "g6_referral"
    private fun p(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun ownCode(c: Context): String = Referral.codeFor(InstallId.get(c))
    fun invitesSent(c: Context): Int = p(c).getInt("sent", 0)
    fun recordInvite(c: Context) = p(c).edit().putInt("sent", invitesSent(c) + 1).apply()
    fun redeemed(c: Context): String? = p(c).getString("redeemed", null)
    /** Stores a friend's code (from an invite link or typed); false when invalid / own / already redeemed. */
    fun redeem(c: Context, input: String): Boolean {
        val own = ownCode(c)
        if (!Referral.canRedeem(own, input, redeemed(c))) return false
        p(c).edit().putString("redeemed", Referral.parse(input)).putLong("redeemed_at", System.currentTimeMillis()).apply()
        return true
    }
}

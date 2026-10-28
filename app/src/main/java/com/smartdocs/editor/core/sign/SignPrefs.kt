package com.smartdocs.editor.core.sign

import android.content.Context

/** Signature validation / signing preferences that are not part of the general settings store. */
object SignPrefs {
    private const val FILE = "sign_prefs"
    private fun p(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Trust the Android system CA store when validating signatures (default on, like the Windows store in Nitro). */
    fun trustSystemRoots(c: Context): Boolean = p(c).getBoolean("trust_system", true)
    fun setTrustSystemRoots(c: Context, on: Boolean) = p(c).edit().putBoolean("trust_system", on).apply()

    /** Check OCSP/CRL revocation during validation (needs internet; embedded DSS data is always used). */
    fun checkRevocation(c: Context): Boolean = p(c).getBoolean("check_revocation", true)
    fun setCheckRevocation(c: Context, on: Boolean) = p(c).edit().putBoolean("check_revocation", on).apply()

    /** Show signature status badges on signed fields in the reader (Nitro "hide valid signature icons" when off). */
    fun showSignatureIcons(c: Context): Boolean = p(c).getBoolean("sig_icons", true)
    fun setShowSignatureIcons(c: Context, on: Boolean) = p(c).edit().putBoolean("sig_icons", on).apply()

    /** Foxit "use expired timestamps": a time-stamp whose TSA certificate has expired still counts (default on). */
    fun acceptExpiredTimestamps(c: Context): Boolean = p(c).getBoolean("expired_ts", true)
    fun setAcceptExpiredTimestamps(c: Context, on: Boolean) = p(c).edit().putBoolean("expired_ts", on).apply()

    /** Foxit "ignore document validation info" when off: use the document's embedded DSS for revocation (default on). */
    fun useDocumentValidationInfo(c: Context): Boolean = p(c).getBoolean("use_dss", true)
    fun setUseDocumentValidationInfo(c: Context, on: Boolean) = p(c).edit().putBoolean("use_dss", on).apply()

    /** Offer to trust the signer of a valid but untrusted signature when a signed PDF opens (default on). */
    fun promptTrust(c: Context): Boolean = p(c).getBoolean("prompt_trust", true)
    fun setPromptTrust(c: Context, on: Boolean) = p(c).edit().putBoolean("prompt_trust", on).apply()

    /** Foxit "ask if verification info is too big": KB limit for the embedded DSS (0 = never ask; default 512 KB). */
    fun ltvAskKb(c: Context): Int = p(c).getInt("ltv_ask_kb", 512)
    fun setLtvAskKb(c: Context, kb: Int) = p(c).edit().putInt("ltv_ask_kb", kb.coerceAtLeast(0)).apply()

    /** Ask for the output file name after signing / certifying (Nitro "ask for a file name"); off = automatic name. */
    fun askFileName(c: Context): Boolean = p(c).getBoolean("ask_name", false)
    fun setAskFileName(c: Context, on: Boolean) = p(c).edit().putBoolean("ask_name", on).apply()
}

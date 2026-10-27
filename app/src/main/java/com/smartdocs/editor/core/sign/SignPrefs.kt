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

    /** Ask for the output file name after signing / certifying (Nitro "ask for a file name"); off = automatic name. */
    fun askFileName(c: Context): Boolean = p(c).getBoolean("ask_name", false)
    fun setAskFileName(c: Context, on: Boolean) = p(c).edit().putBoolean("ask_name", on).apply()
}

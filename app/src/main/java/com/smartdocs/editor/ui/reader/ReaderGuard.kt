package com.smartdocs.editor.ui.reader

import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.smartdocs.editor.R
import com.smartdocs.editor.core.pdf.PdfOps
import com.smartdocs.editor.core.security.ActionFindings
import com.smartdocs.editor.core.security.ActionInspector
import com.smartdocs.editor.core.security.Block
import com.smartdocs.editor.core.security.DocAction
import com.smartdocs.editor.core.security.DocContext
import com.smartdocs.editor.core.security.DocOrigin
import com.smartdocs.editor.core.security.DocOrigins
import com.smartdocs.editor.core.security.JsAnswer
import com.smartdocs.editor.core.security.JsLevel
import com.smartdocs.editor.core.security.JsRun
import com.smartdocs.editor.core.security.ProtectedView
import com.smartdocs.editor.core.security.ReaderRestrictions
import com.smartdocs.editor.core.security.SafeReading
import com.smartdocs.editor.core.security.SecurityInfo
import com.smartdocs.editor.core.security.SecurityInfoReader
import com.smartdocs.editor.core.security.TrustPrefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Per-document security state of the reader (nitro-protect2): the security properties and the author's
 * restrictions (honoured when opened without the owner password), Protected View, Safe Reading Mode with privileged
 * locations, and the action inspector / JavaScript run level. Every editing, printing and copying entry point of
 * the reader asks [check] first.
 */
class ReaderGuard(private val s: ReaderState) {
    /** Security properties of the open document; null until known (or not encrypted and not yet scanned). */
    var info by mutableStateOf<SecurityInfo?>(null); private set
    var origin by mutableStateOf<DocOrigin?>(null); private set
    var privileged by mutableStateOf(false); private set
    var protectedView by mutableStateOf(false); private set
    /** reader-prefs (§37 / PREF-5): the file claims PDF/A and is shown in PDF/A view mode (read-only until enabled). */
    var pdfaView by mutableStateOf(false)
    var safeActive by mutableStateOf(false); private set
    var findings by mutableStateOf<ActionFindings?>(null); private set
    var banner by mutableStateOf(false); private set
    var jsLevel by mutableStateOf(JsLevel.ON); private set
    /** "Keep blocked" for this document: links, launches and submissions are refused as in Safe Reading Mode. */
    var actionsBlocked by mutableStateOf(false); private set
    private var jsAnswer: JsAnswer? = null
    private var trusted = false
    private var hash: String? = null
    private var path: String = ""
    private val scanned = CompletableDeferred<Unit>()
    /**
     * Tab audit D8: signature / certification state read on open ([refreshSigned]); a DocMDP or FieldMDP lock with
     * P < 3 blocks the annotation tools from the first tap (and P = 1 form filling), with a banner.
     */
    var signedInfo by mutableStateOf<com.smartdocs.editor.core.pdf.SignedGuard.Info?>(null); private set

    /**
     * Called by [ReaderState.open] before the first page shows: origin and Protected View, and for an encrypted file
     * its security properties (so restrictions apply from the first tap). The action scan runs afterwards.
     */
    suspend fun prepare(f: File, password: String?) {
        val ctx = s.context
        path = f.absolutePath
        val docs = s.app.documents
        val source = runCatching { docs.originOf(f)?.toString() }.getOrNull() ?: s.uriString.takeIf { !it.startsWith("file:") }
        val appDirs = listOfNotNull(ctx.filesDir, ctx.cacheDir, ctx.getExternalFilesDir(null), runCatching { docs.docsDir }.getOrNull()).map { it.absolutePath }
        val o = DocOrigins.classify(f.absolutePath, source, appDirs, listOf(ctx.packageName + ".fileprovider", ctx.packageName + ".provider", ctx.packageName))
        val priv = TrustPrefs.privileged(ctx).covers(o)
        origin = o; privileged = priv
        safeActive = SafeReading.active(TrustPrefs.safeMode(ctx), priv)
        protectedView = ProtectedView.applies(TrustPrefs.pvMode(ctx), o, priv, TrustPrefs.editingEnabled(ctx, f.absolutePath))
        jsLevel = TrustPrefs.jsLevel(ctx)
        publish()
        if (withContext(Dispatchers.IO) { SecurityInfoReader.mentionsEncrypt(f) }) info = withContext(Dispatchers.IO) {
            runCatching { PdfOps.loadRaw(f, password).use { SecurityInfoReader.of(it) } }.getOrNull()
        } else info = SecurityInfo.NONE
        s.scope.launch { scan(f, password) }
        s.scope.launch { refreshSigned(f, password) }
    }

    /** Tab audit D8: (re)reads the certification and FieldMDP locks of [f] (on open and after the file was replaced). */
    suspend fun refreshSigned(f: File, password: String?) {
        signedInfo = withContext(Dispatchers.IO) { runCatching { com.smartdocs.editor.core.pdf.SignedGuard.inspect(f, password) }.getOrNull() }
    }

    private suspend fun scan(f: File, password: String?) {
        try {
            val ctx = s.context
            val found = withContext(Dispatchers.IO) { runCatching { PdfOps.loadRaw(f, password).use { ActionInspector.scan(it) } }.getOrNull() } ?: ActionFindings.EMPTY
            findings = found
            if (found.total > 0) {
                val h = withContext(Dispatchers.IO) { runCatching { TrustPrefs.hashOf(f) }.getOrNull() }
                hash = h
                jsAnswer = h?.let { TrustPrefs.answer(ctx, it) }
                trusted = privileged || (h != null && TrustPrefs.isTrusted(ctx, h))
                actionsBlocked = jsAnswer == JsAnswer.BLOCK
                banner = JsRun.showBanner(found, trusted, jsAnswer)
                publish()
            }
        } finally { scanned.complete(Unit) }
    }

    /** Whether this document's scripts may run (waits briefly for the action scan). [legacyOn]: the old switch. */
    suspend fun jsAllowed(legacyOn: Boolean): Boolean {
        withTimeoutOrNull(10_000) { scanned.await() }
        val level = TrustPrefs.jsLevel(s.context, legacyOn).also { jsLevel = it }
        if (findings?.hasJavaScript != true) return level != JsLevel.OFF && !safeActive
        return JsRun.allowed(level, safeActive, trusted, jsAnswer)
    }

    val canAllowScripts: Boolean get() = JsRun.canAllow(jsLevel, safeActive)

    /** Banner answer: Allow trusts the document (by hash) and runs its scripts; Keep blocked stops them and its actions. */
    fun answer(allow: Boolean) {
        val a = if (allow) JsAnswer.ALLOW else JsAnswer.BLOCK
        jsAnswer = a; banner = false
        if (allow) trusted = true
        actionsBlocked = !allow
        hash?.let { TrustPrefs.setAnswer(s.context, it, a) }
        publish()
        s.scope.launch { runCatching { s.forms5.ensureLoaded() } }   // reload with the new JavaScript decision
    }

    fun dismissBanner() { banner = false }

    /** After the app saved a trusted document its content hash changes: the trust follows it. */
    fun afterSave(f: File) {
        val old = hash ?: return
        if (!trusted) return
        s.scope.launch {
            val h = withContext(Dispatchers.IO) { runCatching { TrustPrefs.hashOf(f) }.getOrNull() } ?: return@launch
            TrustPrefs.carryTrust(s.context, old, h); hash = h
        }
    }

    /** Makes this document the one link / launch decisions refer to (on open and when the reader comes back). */
    fun publish() {
        if (path.isNotEmpty()) DocContext.current = DocContext.Current(path, privileged, actionsBlocked)
    }

    fun release() { if (path.isNotEmpty()) DocContext.clear(path) }

    // ---------------------------------------------------------------- restrictions

    /**
     * Why [a] is refused, or null. [pdfaAs] is what PDF/A view mode weighs instead (reader-prefs: Fill & Sign counts as
     * form filling there, see [com.smartdocs.editor.core.rpf.PdfaViewRule]).
     */
    fun blockOf(a: DocAction, pdfaAs: DocAction = a): Block? = ReaderRestrictions.check(info, protectedView, a)
        ?: Block.PROTECTED_VIEW.takeIf { com.smartdocs.editor.core.rpf.PdfaViewRule.blocks(pdfaView, pdfaAs) }
        ?: Block.CERTIFIED.takeIf { com.smartdocs.editor.core.security.CertifiedLock.blocks(signedInfo, a) }     // tab audit D8

    fun allows(a: DocAction): Boolean = blockOf(a) == null

    /** reader-prefs: picking reader tool [t] (Fill & Sign tools are form filling for PDF/A view mode). */
    fun checkTool(t: ReaderTool): Boolean {
        val a = actionFor(t) ?: return true
        return check(a, com.smartdocs.editor.core.rpf.PdfaViewRule.toolAction(t.name, a))
    }

    /** reader-prefs: a pending-annotation change; on the Fill & Sign tab / with a Fill & Sign tool it is form filling for PDF/A view mode. */
    fun checkAnnot(): Boolean = check(DocAction.ANNOTATE, com.smartdocs.editor.core.rpf.PdfaViewRule.annotAction(s.tab == ReaderTab.FILL_SIGN, s.tool?.name))

    /** True when [a] is allowed; otherwise tells the user why ("restricted by the author" / Protected View). */
    fun check(a: DocAction, pdfaAs: DocAction = a): Boolean {
        val b = blockOf(a, pdfaAs) ?: return true
        Toast.makeText(s.context, message(b), Toast.LENGTH_SHORT).show()
        return false
    }

    fun message(b: Block): String = s.context.getString(when (b) {
        Block.AUTHOR -> R.string.np2_restricted_author
        Block.PROTECTED_VIEW -> if (pdfaView && !protectedView) R.string.rpf_pdfa_blocked else R.string.np2_pv_blocked
        Block.CERTIFIED -> R.string.tfr_certified_blocked
    })

    /** Thrown by in-place operations refused by [check] (callers show its message like any failure). */
    class Refused(message: String) : IllegalStateException(message)

    fun require(a: DocAction) { blockOf(a)?.let { throw Refused(message(it)) } }

    /** Protected View → "Enable editing" for this file (remembered). */
    fun enableEditing() {
        if (path.isNotEmpty()) TrustPrefs.enableEditing(s.context, path)
        protectedView = false
    }

    /** True when the author's restrictions are in force (the reader shows a small note). */
    val restricted: Boolean get() = ReaderRestrictions.restricted(info)

    companion object {
        /** What a reader tool needs: snapshots copy content, links change the document, measuring changes nothing. */
        fun actionFor(t: ReaderTool): DocAction? = when (t) {
            ReaderTool.MEASURE -> null
            ReaderTool.SNAPSHOT -> DocAction.COPY
            ReaderTool.LINK -> DocAction.MODIFY
            else -> DocAction.ANNOTATE
        }
    }
}

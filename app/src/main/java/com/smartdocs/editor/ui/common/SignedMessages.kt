package com.smartdocs.editor.ui.common

import android.content.Context
import com.smartdocs.editor.R
import com.smartdocs.editor.core.pdf.SignedDocumentException

/** tab-view-core E-01: the localized "signed — save a copy" text of [e], or its plain message. */
fun signedOrMessage(ctx: Context, e: Throwable?): String = when ((e as? SignedDocumentException)?.reason) {
    SignedDocumentException.Reason.CERTIFIED_NO_CHANGES -> ctx.getString(R.string.signed_doc_certified_no_changes)
    SignedDocumentException.Reason.INCREMENTAL_FAILED -> ctx.getString(R.string.signed_doc_save_as_copy)
    null -> e?.message ?: e?.toString() ?: ""
}

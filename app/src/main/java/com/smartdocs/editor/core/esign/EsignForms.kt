package com.smartdocs.editor.core.esign

import org.json.JSONArray
import org.json.JSONObject

/*
 * Online form ("mass signing by link"): one reusable public link (`/f/<id>`) where anyone enters a name, fills the fields
 * and signs their own copy. Server: ai-proxy/esign/worker.js `formRoutes` / `formPublicRoutes`. All fields belong to signer 0.
 */

enum class FormState { DRAFT, ACTIVE, DISABLED, FULL;
    companion object { fun of(s: String?) = entries.firstOrNull { it.name.equals(s, true) } ?: ACTIVE }
}

data class EsignForm(
    val id: String, val title: String, val sender: String, val message: String, val fields: List<EsignField>,
    val state: FormState, val created: Long, val originalSha: String, val count: Int, val maxResponses: Int,
    val requireEmail: Boolean, val notify: Boolean, val link: String, val brand: EsignBrand? = null, val pageCount: Int = 0,
) {
    companion object {
        fun from(o: JSONObject): EsignForm {
            val f = o.optJSONArray("fields") ?: JSONArray()
            return EsignForm(o.optString("id"), o.optString("title"), o.optString("sender"), o.optString("message"),
                (0 until f.length()).map { EsignField.from(f.getJSONObject(it)).copy(signer = 0) },
                FormState.of(o.optString("state", o.optString("status"))), o.optLong("created"), o.optString("originalSha"),
                o.optInt("count"), o.optInt("maxResponses"), o.optBoolean("requireEmail"), o.optBoolean("notify"), o.optString("link"),
                EsignBrand.from(o.optJSONObject("brand")), o.optInt("pageCount"))
        }
    }
}

/** One response. [values] and [event] are only filled by the detail call (`GET /forms/:id/submissions/:sid`). */
data class FormSubmission(
    val sid: String, val name: String, val email: String, val time: Long, val finalSha: String = "",
    val values: Map<String, String> = emptyMap(), val event: SignerEvent? = null,
) {
    companion object {
        fun from(o: JSONObject): FormSubmission {
            val v = o.optJSONObject("values") ?: JSONObject()
            return FormSubmission(o.optString("sid"), o.optString("name"), o.optString("email"), o.optLong("time"), o.optString("finalSha"),
                v.keys().asSequence().associateWith { v.optString(it) },
                o.optJSONObject("event")?.let { SignerEvent(it.optLong("time"), it.optString("ip"), it.optString("ua"), it.optString("device"), it.optString("channel")) })
        }
    }
}

object FormCopies {
    /**
     * A response as a completed single-signer request, so [EsignComposer] draws the values and the certificate exactly as for
     * a normal request. The request id is "<form id>-<sid>" (what `/verify` reports for that copy).
     */
    fun asRequest(form: EsignForm, s: FormSubmission): Pair<EsignRequest, List<SubmittedValues>> {
        val signer = Signer(s.name, s.email, SignerStatus.SIGNED, s.time, s.time, event = s.event)
        val audit = listOf(
            AuditEntry(form.created, "created", form.sender),
            AuditEntry(s.time, "signed", s.name, s.event?.ip.orEmpty(), s.event?.device?.ifBlank { null } ?: s.event?.channel.orEmpty()),
        )
        val req = EsignRequest("${form.id}-${s.sid}", form.title, form.sender, form.message, SignOrder.SEQUENTIAL, listOf(signer), form.fields,
            RequestStatus.COMPLETED, form.created, form.originalSha, s.finalSha, audit, form.pageCount, brand = form.brand)
        return req to listOf(SubmittedValues(0, s.values, s.event))
    }

    /** "12 / 1000" style counter. */
    fun counter(f: EsignForm) = "${f.count}/${f.maxResponses}"
}

package com.smartdocs.editor.core.sign

/**
 * Visible digital-signature appearance (the reference's "appearance designer"): which lines are shown, labels,
 * logo and layout. Pure model + text lines; drawing is in ui/signature/SigAppearanceRenderer.kt.
 */
data class SigAppearanceSpec(
    val showName: Boolean = true,
    val showReason: Boolean = true,
    val showLocation: Boolean = true,
    val showDate: Boolean = true,
    val showDn: Boolean = false,
    val showLabels: Boolean = true,
    val logoPath: String? = null,
    val layout: Layout = Layout.LOGO_LEFT,
) {
    enum class Layout { LOGO_LEFT, LOGO_RIGHT, LOGO_TOP }

    /** Localised labels ("Digitally signed by", "Reason", …). */
    data class Labels(val signedBy: String, val reason: String, val location: String, val date: String, val dn: String)

    /** Text lines in display order; empty values are skipped. The name line is the big first line when shown. */
    fun lines(name: String, reason: String, location: String, date: String, dn: String, labels: Labels): List<String> {
        fun line(show: Boolean, label: String, value: String): String? =
            if (!show || value.isBlank()) null else if (showLabels) "$label: ${value.trim()}" else value.trim()
        return listOfNotNull(
            if (showName && name.isNotBlank()) (if (showLabels) "${labels.signedBy} ${name.trim()}" else name.trim()) else null,
            line(showReason, labels.reason, reason),
            line(showLocation, labels.location, location),
            line(showDate, labels.date, date),
            line(showDn, labels.dn, dn),
        )
    }

    fun encode(): String = listOf(showName, showReason, showLocation, showDate, showDn, showLabels).joinToString("") { if (it) "1" else "0" } +
        "|" + layout.name + "|" + (logoPath ?: "")

    companion object {
        fun decode(s: String?): SigAppearanceSpec {
            if (s.isNullOrBlank()) return SigAppearanceSpec()
            val parts = s.split('|', limit = 3)
            val b = parts[0]
            if (parts.size != 3 || b.length != 6 || b.any { it != '0' && it != '1' }) return SigAppearanceSpec()
            fun bit(i: Int, d: Boolean) = b.getOrNull(i)?.let { it == '1' } ?: d
            val d = SigAppearanceSpec()
            return SigAppearanceSpec(
                bit(0, d.showName), bit(1, d.showReason), bit(2, d.showLocation), bit(3, d.showDate), bit(4, d.showDn), bit(5, d.showLabels),
                logoPath = parts.getOrNull(2)?.ifBlank { null },
                layout = parts.getOrNull(1)?.let { n -> Layout.entries.firstOrNull { it.name == n } } ?: d.layout,
            )
        }
    }
}

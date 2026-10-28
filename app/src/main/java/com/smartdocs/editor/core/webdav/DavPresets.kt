package com.smartdocs.editor.core.webdav

/**
 * fc-sync (Foxit §32 / §42 Egnyte): ready-made WebDAV addresses for services that offer WebDAV, so "Add place" does
 * not need the URL typed by hand. Egnyte is reached through its WebDAV endpoint (`https://<domain>.egnyte.com/webdav`,
 * available when the Egnyte plan / admin enables WebDAV access); the native Egnyte API would need an Egnyte API key
 * registered by the owner. Pure, unit-tested in DavPresetsTest.
 */
object DavPresets {
    /** [template] contains "{domain}" / "{user}" where the user's value goes. */
    data class Preset(val key: String, val label: String, val template: String) {
        val needsDomain get() = template.contains("{domain}")
        val needsUser get() = template.contains("{user}")
    }

    val ALL = listOf(
        Preset("egnyte", "Egnyte", "https://{domain}.egnyte.com/webdav/"),
        Preset("nextcloud", "Nextcloud", "https://{domain}/remote.php/dav/files/{user}/"),
        Preset("owncloud", "ownCloud", "https://{domain}/remote.php/webdav/"),
        Preset("pcloud", "pCloud", "https://webdav.pcloud.com/"),
        Preset("koofr", "Koofr", "https://app.koofr.net/dav/Koofr/"),
        Preset("yandex", "Yandex Disk", "https://webdav.yandex.com/"),
    )

    /**
     * The address for [p] with [domain] / [user] filled in, or null while a needed value is missing or invalid.
     * An Egnyte domain may be typed as "acme", "acme.egnyte.com" or a full URL; other domains as a host name.
     */
    fun url(p: Preset, domain: String, user: String): String? {
        var d = domain.trim().removePrefix("https://").removePrefix("http://").substringBefore('/').trim().lowercase()
        if (p.key == "egnyte") d = d.removeSuffix(".egnyte.com")
        if (p.needsDomain && !Regex("^[a-z0-9]([a-z0-9.-]*[a-z0-9])?(:[0-9]{1,5})?$").matches(d)) return null
        val u = user.trim()
        if (p.needsUser && u.isEmpty()) return null
        return p.template.replace("{domain}", d).replace("{user}", DavXml.encodeSegment(u))
    }
}

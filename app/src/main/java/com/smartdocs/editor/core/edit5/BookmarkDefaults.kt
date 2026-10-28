package com.smartdocs.editor.core.edit5

import android.content.Context

/**
 * fix/links-bookmarks (Foxit §16): defaults for bookmarks the user creates by hand: the destination view
 * ("default zoom for new bookmarks") and the appearance ("use current appearance as new default").
 */
object BookmarkDefaults {
    /** The saved defaults: view (fit + zoom; position comes from the new bookmark) and colour / bold / italic. */
    data class Defaults(val view: DestView = DestView(DestFit.XYZ), val color: Int? = null, val bold: Boolean = false, val italic: Boolean = false)

    /** [b] with [d] applied: its style, the default fit and zoom; a top / left the bookmark has is kept when the fit uses it. Pure. */
    fun applyTo(b: E5Bookmark, d: Defaults): E5Bookmark {
        val v = d.view
        val view = when (v.fit) {
            DestFit.XYZ -> DestView(DestFit.XYZ, b.left, b.top, v.zoom)
            DestFit.FITH, DestFit.FITBH -> DestView(v.fit, top = b.top)
            DestFit.FITV -> DestView(v.fit, left = b.left)
            else -> DestView(v.fit, rect = v.rect)
        }
        return b.copy(color = d.color, bold = d.bold, italic = d.italic).withView(view)
    }

    private fun p(c: Context) = c.getSharedPreferences("lbm_bookmark_defaults", Context.MODE_PRIVATE)

    fun load(c: Context): Defaults {
        val sp = p(c)
        return Defaults(
            view = DestView(runCatching { DestFit.valueOf(sp.getString("fit", null)!!) }.getOrDefault(DestFit.XYZ), zoom = sp.getFloat("zoom", 0f)),
            color = if (sp.contains("color")) sp.getInt("color", 0) else null, bold = sp.getBoolean("bold", false), italic = sp.getBoolean("italic", false),
        )
    }

    fun saveView(c: Context, v: DestView) = p(c).edit().putString("fit", (if (v.fit == DestFit.FITR) DestFit.FIT else v.fit).name).putFloat("zoom", v.zoom).apply()

    fun saveStyle(c: Context, color: Int?, bold: Boolean, italic: Boolean) = p(c).edit().apply {
        if (color == null) remove("color") else putInt("color", color)
        putBoolean("bold", bold); putBoolean("italic", italic)
    }.apply()

    /**
     * Foxit §16 "Set destination" to the current view: [b] goes to [page] with [view]. A bookmark with an action list
     * gets its first go-to replaced (or one added in front); the other actions stay. Pure.
     */
    fun retarget(b: E5Bookmark, page: Int, view: DestView): E5Bookmark {
        if (b.actions.isEmpty() || b.actions.all { it is NavAction.GoTo }) return b.copy(page = page, actions = emptyList()).withView(view)
        val i = b.actions.indexOfFirst { it is NavAction.GoTo }
        val go = NavAction.GoTo(page, view)
        return b.copy(page = page, actions = if (i >= 0) b.actions.toMutableList().also { it[i] = go } else listOf(go) + b.actions)
    }

    /** A new bookmark [b] with the saved defaults. */
    fun apply(c: Context, b: E5Bookmark): E5Bookmark = applyTo(b, load(c))
}

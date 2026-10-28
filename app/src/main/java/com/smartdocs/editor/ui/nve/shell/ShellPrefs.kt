package com.smartdocs.editor.ui.nve.shell

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.mutableIntStateOf
import com.smartdocs.editor.core.nve.shell.Favorites
import com.smartdocs.editor.core.nve.shell.ToolbarModel
import java.io.File

/** Shell preferences of the nitro-view-edit work (prefs "nve_shell"). */
object NveShellPrefs {
    const val PREFS = "nve_shell"
    /** Bumped on every change so composables re-read. */
    val version = mutableIntStateOf(0)

    fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun bump() { version.intValue++ }

    // ---- favourite tools ----
    fun favorites(ctx: Context): List<String> = Favorites.parse(prefs(ctx).getString("favs", null))
    fun setFavorites(ctx: Context, list: List<String>) { prefs(ctx).edit().putString("favs", Favorites.format(list)).apply(); bump() }
    fun toggleFavorite(ctx: Context, id: String) = setFavorites(ctx, Favorites.toggle(favorites(ctx), id))
    fun favoritesTitle(ctx: Context): String? = prefs(ctx).getString("favs_title", null)?.takeIf { it.isNotBlank() }
    fun setFavoritesTitle(ctx: Context, t: String) { prefs(ctx).edit().putString("favs_title", t.trim()).apply(); bump() }

    // ---- reader Custom tab ----
    /** User name of the reader's Custom tab (null = default label). */
    fun customTabName(ctx: Context): String? = prefs(ctx).getString("custom_tab", null)?.takeIf { it.isNotBlank() }
    fun setCustomTabName(ctx: Context, t: String) { prefs(ctx).edit().putString("custom_tab", t.trim()).apply(); bump() }

    // ---- auto recovery ----
    // tab-view-core V-12: ONE recovery interval (Settings → more preferences and the shell settings edit the same value)
    fun recoveryMinutes(@Suppress("UNUSED_PARAMETER") ctx: Context): Int = com.smartdocs.editor.core.data.NpfPrefs.recoveryMinutes
    fun setRecoveryMinutes(@Suppress("UNUSED_PARAMETER") ctx: Context, m: Int) { com.smartdocs.editor.core.data.NpfPrefs.setInt(com.smartdocs.editor.core.data.NpfPrefs.K.RECOVERY_MIN, m); bump() }
}

/** SHL-3: reader toolbar customisation read by the reader (tabs order / hidden tabs / hidden View chips). */
object NveToolbarPrefs {
    fun tabOrder(ctx: Context): List<String> = ToolbarModel.order(ToolbarModel.parse(NveShellPrefs.prefs(ctx).getString("tab_order", null)))
    fun hiddenTabs(ctx: Context): Set<String> = ToolbarModel.parse(NveShellPrefs.prefs(ctx).getString("tab_hidden", null)).toSet()
    fun hiddenChips(ctx: Context): Set<String> = ToolbarModel.parse(NveShellPrefs.prefs(ctx).getString("chip_hidden", null)).toSet()
    /** Visible tabs in their order. */
    fun visibleTabs(ctx: Context): List<String> = ToolbarModel.visible(tabOrder(ctx), hiddenTabs(ctx))

    fun save(ctx: Context, order: List<String>, hiddenTabs: Set<String>, hiddenChips: Set<String>) {
        NveShellPrefs.prefs(ctx).edit().putString("tab_order", order.joinToString(",")).putString("tab_hidden", hiddenTabs.joinToString(","))
            .putString("chip_hidden", hiddenChips.joinToString(",")).apply()
        NveShellPrefs.version.intValue++
    }

    fun reset(ctx: Context) = save(ctx, ToolbarModel.DEFAULT_TABS, emptySet(), emptySet())

    /** View-tab chips the user can hide (ids used by the parent's ViewToolbar hook). */
    val VIEW_CHIPS = listOf("view_mode", "fit", "night", "page_color", "reflow", "auto_flip", "rotate", "crop", "magnifier", "search", "panels",
        "bookmarks", "thumbnails", "attachments", "read_aloud", "ai", "share", "print", "doc_info",
        // tab-view-core V-26: the View5 and NVE chips of the same row can be hidden too
        "v5_tell_me", "v5_split", "v5_compare", "v5_present", "v5_auto_scroll", "v5_rulers", "v5_grid", "v5_reading_ruler", "v5_overlay", "v5_tts_options", "nv_zoom", "nv_marquee", "nv_fit_height", "nv_actual", "nv_cont_facing", "nv_reverse", "nv_word_count", "nv_text_viewer", "nv_quad", "nv_transitions", "nv_page_snap", "nv_area_snap", "nv_paste", "nv_select_all", "nv_signatures", "nv_spell")
}

/** Shell "Convert to Word / Excel / PowerPoint / Text" targets: the conversion the hub opens with. */
object NveConvertTargets {
    @Volatile private var pending: String? = null
    fun set(id: String) { pending = id }
    /** The pending conversion id (consumed). */
    fun take(): String? = pending.also { pending = null }

    /** Activity-alias class name suffix -> Cv6Catalog conversion id. */
    val ALIASES = mapOf("ConvertToWord" to "pdf_docx", "ConvertToExcel" to "pdf_xlsx", "ConvertToPowerPoint" to "pdf_pptx", "ConvertToText" to "pdf_txt")

    fun forComponent(className: String?): String? = className?.substringAfterLast('.')?.let { ALIASES[it] }
}

/** A second reader window (Android multi-window): see NveWindowActivity. */
object NveWindows {
    fun openInNewWindow(context: Context, file: File) {
        val i = Intent(context, com.smartdocs.editor.NveWindowActivity::class.java)
            .setAction(Intent.ACTION_VIEW).setData(Uri.fromFile(file))
            .addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
        runCatching { context.startActivity(i) }
    }
}

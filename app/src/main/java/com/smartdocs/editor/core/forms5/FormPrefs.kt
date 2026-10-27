package com.smartdocs.editor.core.forms5

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Form-filling preferences (Settings -> Forms): automatic calculation, autocomplete, field highlight and
 * required-field colours, and the "this form is fillable" banner. Kept in SharedPreferences and observable
 * from Compose; [load] once with any context before reading (reads before that use the defaults).
 */
object FormPrefs {
    private const val FILE = "forms_run_prefs"
    const val DEFAULT_HIGHLIGHT = 0x3D7C9CFF
    const val DEFAULT_REQUIRED = 0xCCE53935.toInt()

    /** Calculate fields as values change (false: only on "Calculate now"). */
    var autoCalculate by mutableStateOf(true); private set
    /** Remember typed values and suggest them (Acrobat "Auto-Complete"). */
    var autocomplete by mutableStateOf(true); private set
    /** ARGB of the field highlight. */
    var highlightColor by mutableIntStateOf(DEFAULT_HIGHLIGHT); private set
    /** ARGB of the required-field outline. */
    var requiredColor by mutableIntStateOf(DEFAULT_REQUIRED); private set
    /** Show the "this document has fillable fields" banner in the reader. */
    var fillableBanner by mutableStateOf(true); private set

    @Volatile private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        autoCalculate = p.getBoolean("auto_calc", true)
        autocomplete = p.getBoolean("autocomplete", true)
        highlightColor = p.getInt("hl_color", DEFAULT_HIGHLIGHT)
        requiredColor = p.getInt("req_color", DEFAULT_REQUIRED)
        fillableBanner = p.getBoolean("fill_banner", true)
        loaded = true
    }

    private fun edit(context: Context, block: android.content.SharedPreferences.Editor.() -> Unit) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().apply(block).apply()

    fun setAutoCalculate(context: Context, v: Boolean) { autoCalculate = v; edit(context) { putBoolean("auto_calc", v) } }
    fun setAutocomplete(context: Context, v: Boolean) { autocomplete = v; edit(context) { putBoolean("autocomplete", v) } }
    fun setHighlightColor(context: Context, v: Int) { highlightColor = v; edit(context) { putInt("hl_color", v) } }
    fun setRequiredColor(context: Context, v: Int) { requiredColor = v; edit(context) { putInt("req_color", v) } }
    fun setFillableBanner(context: Context, v: Boolean) { fillableBanner = v; edit(context) { putBoolean("fill_banner", v) } }

    /** The colour choices offered in Settings (ARGB, highlight alpha applied by the caller). */
    val PALETTE = listOf(0x7C9CFF, 0xFFEB3B, 0x8BC34A, 0x26C6DA, 0xFF9800, 0xE53935, 0xAB47BC, 0x9E9E9E)

    /** Autocomplete memory file shared by the reader and Settings. */
    fun autocompleteFile(context: Context) = java.io.File(context.filesDir, "forms5_autocomplete.json")
}

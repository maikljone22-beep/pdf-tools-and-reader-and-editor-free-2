package com.smartdocs.editor.ui.formsx

import android.content.Context
import com.smartdocs.editor.core.formsx.FieldSpec
import com.smartdocs.editor.core.formsx.FxMerge
import com.smartdocs.editor.core.formsx.FxType

/** Designer preferences: per-type defaults for new fields (Foxit 26.30 / Nitro 8.79) and the saved gallery look (8.20). */
object FxPrefs {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("formsx", Context.MODE_PRIVATE)

    fun defaults(ctx: Context, type: FxType): FieldSpec? = prefs(ctx).getString("def_${type.name}", null)?.let { FxMerge.decodeLook(type, it) }

    fun saveDefaults(ctx: Context, s: FieldSpec) { prefs(ctx).edit().putString("def_${s.type.name}", FxMerge.encodeLook(s)).apply() }

    fun galleryLook(ctx: Context, type: FxType): FieldSpec? = prefs(ctx).getString("gal", null)?.let { FxMerge.decodeLook(type, it) }

    fun saveGalleryLook(ctx: Context, s: FieldSpec) { prefs(ctx).edit().putString("gal", FxMerge.encodeLook(s)).apply() }
}

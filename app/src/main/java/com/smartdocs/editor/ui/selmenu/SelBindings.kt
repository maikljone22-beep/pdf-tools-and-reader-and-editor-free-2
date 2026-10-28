package com.smartdocs.editor.ui.selmenu

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector
import com.smartdocs.editor.R
import com.smartdocs.editor.core.selmenu.SelAction
import com.smartdocs.editor.core.selmenu.SelGroup
import com.smartdocs.editor.core.selmenu.Why

/** Label and icon of every registry action (core/selmenu/SelMenu.kt); strings in values (ar) and values-en. */
object SelBindings {
    fun label(a: SelAction): Int = when (a) {
        SelAction.CALL -> R.string.sm_call
        SelAction.WHATSAPP -> R.string.sm_whatsapp
        SelAction.SMS -> R.string.sm_sms
        SelAction.ADD_CONTACT -> R.string.sm_add_contact
        SelAction.EMAIL -> R.string.sm_email
        SelAction.OPEN_URL -> R.string.sm_open_link
        SelAction.COPY_URL -> R.string.sm_copy_link
        SelAction.MAP -> R.string.sm_map
        SelAction.CALENDAR -> R.string.sm_calendar
        SelAction.COPY_NUMBER -> R.string.sm_copy_number
        SelAction.COPY -> R.string.copy_text
        SelAction.COPY_FORMATTED -> R.string.nv_copy_formatting
        SelAction.COPY_PLAIN -> R.string.sm_copy_plain
        SelAction.COPY_TABLE -> R.string.nv_copy_table
        SelAction.COPY_NO_DIACRITICS -> R.string.sm_copy_no_diacritics
        SelAction.COPY_NORMALIZED -> R.string.sm_copy_normalized
        SelAction.COPY_LATIN_DIGITS -> R.string.sm_copy_latin_digits
        SelAction.COPY_ARABIC_DIGITS -> R.string.sm_copy_arabic_digits
        SelAction.SELECT_SENTENCE -> R.string.sm_select_sentence
        SelAction.SELECT_ALL -> R.string.nv_select_all
        SelAction.COLUMN_SELECT -> R.string.nv_column_select
        SelAction.HIGHLIGHT -> R.string.annot_highlight
        SelAction.UNDERLINE -> R.string.annot_underline
        SelAction.STRIKEOUT -> R.string.annot_strikeout
        SelAction.SQUIGGLY -> R.string.annot_squiggly
        SelAction.REPLACE_TEXT -> R.string.an_replace_title
        SelAction.INSERT_TEXT -> R.string.an_insert_text_title
        SelAction.ADD_NOTE -> R.string.annot_note
        SelAction.READING_BOOKMARK -> R.string.add_bookmark
        SelAction.PDF_BOOKMARK -> R.string.nbm_bm_from_selection
        SelAction.SEARCH_DOC -> R.string.sm_search_doc
        SelAction.WEB_SEARCH -> R.string.rd_web_search
        SelAction.READ_ALOUD -> R.string.tts_from_here
        SelAction.WORD_COUNT -> R.string.nt_wc_title
        SelAction.OTHER_APPS -> R.string.sm_other_apps
        SelAction.AI_TRANSLATE -> R.string.ai_translate
        SelAction.AI_EXPLAIN -> R.string.ai_explain
        SelAction.AI_DEFINE -> R.string.sm_ai_define
        SelAction.AI_SUMMARIZE -> R.string.sm_ai_summarize
        SelAction.AI_REWRITE -> R.string.ai6r_rewrite_chip
        SelAction.EDIT_TEXT -> R.string.nv_edit_text
        SelAction.CREATE_LINK -> R.string.annot_link
        SelAction.REDACT -> R.string.s5_rd_chip
        SelAction.SHARE -> R.string.share_text
        SelAction.EXPORT_TXT -> R.string.nv_export_selection
        SelAction.LINK_OPEN -> R.string.sm_open_link
        SelAction.LINK_COPY -> R.string.sm_copy_link
        SelAction.LINK_SHARE -> R.string.sm_share_link
        SelAction.PASTE -> R.string.nv_paste
        SelAction.PASTE_STAMP -> R.string.nf2_paste_here
        SelAction.NOTE_HERE -> R.string.sm_note_here
        SelAction.TEXT_HERE -> R.string.sm_text_here
        SelAction.FILL_TEXT_HERE -> R.string.sm_fill_text_here
        SelAction.IMAGE_HERE -> R.string.sm_image_here
        SelAction.SIGN_HERE -> R.string.sm_sign_here
        SelAction.SNAPSHOT -> R.string.nv_page_snapshot
        SelAction.READ_PAGE -> R.string.sm_read_page
        SelAction.SEARCH -> R.string.sm_search
        SelAction.MARQUEE_ZOOM -> R.string.nv_marquee
        SelAction.ZOOM -> R.string.nv_zoom
        SelAction.GO_TO_PAGE -> R.string.go_to_page
        SelAction.ROTATE_VIEW -> R.string.rotate_view
        SelAction.PRINT -> R.string.print
        SelAction.DOC_INFO -> R.string.tool_doc_info
        // fix/selmenu-rest: annotation (labels of the former AnnotActionBar / SelectionMoreButton)
        SelAction.A_COLOR -> R.string.ae_color
        SelAction.A_OPACITY -> R.string.ae_opacity
        SelAction.A_THICKNESS -> R.string.ae_thickness
        SelAction.A_EDIT_TEXT -> R.string.ae_edit_text
        SelAction.A_TEXT_FORMAT -> R.string.cx_text_format
        SelAction.A_COPY -> R.string.ae_copy
        SelAction.A_PLAY, SelAction.A_OPEN_FILE -> R.string.an_open
        SelAction.A_STAMP_CUT -> R.string.nf2_cut
        SelAction.A_STAMP_COPY -> R.string.nf2_copy_clip
        SelAction.A_STAMP_DEFAULT_SIZE -> R.string.nf2_set_default_size
        SelAction.A_STAMP_PAGES -> R.string.nf2_multi_pages
        SelAction.A_VIDEO_PLAY -> R.string.vid_play
        SelAction.A_VIDEO_OPTIONS -> R.string.vid_options
        SelAction.A_VIDEO_SHARE -> R.string.vid_share
        SelAction.A_VIDEO_SAVE -> R.string.vid_save
        SelAction.A_RECOGNIZE -> R.string.an_recognize
        SelAction.A_IMAGE_PROPS -> R.string.ni_ia_title
        SelAction.A_DELETE -> R.string.ae_delete
        SelAction.A_RANGE_START_LESS -> R.string.smr_range_start_less
        SelAction.A_RANGE_START_MORE -> R.string.smr_range_start_more
        SelAction.A_RANGE_END_LESS -> R.string.smr_range_end_less
        SelAction.A_RANGE_END_MORE -> R.string.smr_range_end_more
        SelAction.A_REPLY -> R.string.smr_reply
        SelAction.A_PROPS -> R.string.cx_props
        SelAction.A_LOCK -> R.string.cx_lock
        SelAction.A_UNLOCK -> R.string.cx_unlock
        SelAction.A_CUT -> R.string.cx_cut
        SelAction.A_CLIP_COPY -> R.string.cx_copy
        SelAction.A_PASTE_IN_PLACE -> R.string.cx_paste_in_place
        SelAction.A_ROTATE_LEFT -> R.string.cx_rotate_left
        SelAction.A_ROTATE_RIGHT -> R.string.cx_rotate_right
        SelAction.A_ROTATE_CUSTOM -> R.string.cx_rotate_custom
        SelAction.A_PLACE_PAGES -> R.string.cx_place_pages
        SelAction.A_FLATTEN -> R.string.cx_flatten
        SelAction.A_EXPORT_XFDF -> R.string.cx_export_sel_xfdf
        SelAction.A_EXPORT_FDF -> R.string.cx_export_sel_fdf
        SelAction.A_EMAIL_FDF -> R.string.cx_email_fdf
        // form field
        SelAction.F_COPY_VALUE -> R.string.smr_f_copy_value
        SelAction.F_PASTE -> R.string.smr_f_paste
        SelAction.F_CLEAR -> R.string.smr_f_clear
        SelAction.F_RESET -> R.string.smr_f_reset
        SelAction.F_PICK_IMAGE -> R.string.smr_f_pick_image
        SelAction.F_SIGN -> R.string.smr_f_sign
        SelAction.F_SPELL -> R.string.smr_f_spell
        SelAction.F_CALCULATE -> R.string.smr_f_calculate
        SelAction.F_PROPS -> R.string.smr_f_props
        SelAction.F_DESIGNER -> R.string.smr_f_designer
        SelAction.F_DUPLICATE -> R.string.smr_f_duplicate
        SelAction.F_DELETE -> R.string.smr_f_delete
        SelAction.F_TAB_ORDER -> R.string.smr_f_tab_order
        // page-content picture (labels of the content-edit image bars)
        SelAction.I_COPY -> R.string.ni_copy
        SelAction.I_CUT -> R.string.ni_cut
        SelAction.I_SAVE -> R.string.ed_img_export
        SelAction.I_SHARE -> R.string.ed_img_share
        SelAction.I_REPLACE -> R.string.ed_img_replace
        SelAction.I_CROP -> R.string.ni_crop
        SelAction.I_ROTATE_LEFT -> R.string.ed_img_rotate_left
        SelAction.I_ROTATE_RIGHT -> R.string.ed_img_rotate_right
        SelAction.I_FLIP_H -> R.string.ni_flip_h
        SelAction.I_FLIP_V -> R.string.ni_flip_v
        SelAction.I_EDITOR -> R.string.ni_image_editor
        SelAction.I_PROPS -> R.string.ni_props
        SelAction.I_MORE_TOOLS -> R.string.ni_more
        SelAction.I_DELETE -> R.string.delete
        SelAction.I_FRONT -> R.string.ni_front
        SelAction.I_BACK -> R.string.ni_back
        SelAction.I_DUPLICATE -> R.string.ni_duplicate
    }

    fun icon(a: SelAction): ImageVector = when (a) {
        SelAction.CALL -> Icons.Default.Call
        SelAction.WHATSAPP -> Icons.Default.Chat
        SelAction.SMS -> Icons.Default.Sms
        SelAction.ADD_CONTACT -> Icons.Default.PersonAdd
        SelAction.EMAIL -> Icons.Default.Email
        SelAction.OPEN_URL, SelAction.LINK_OPEN -> Icons.Default.OpenInNew
        SelAction.COPY_URL, SelAction.LINK_COPY -> Icons.Default.Link
        SelAction.MAP -> Icons.Default.Place
        SelAction.CALENDAR -> Icons.Default.Event
        SelAction.COPY_NUMBER -> Icons.Default.Numbers
        SelAction.COPY -> Icons.Default.ContentCopy
        SelAction.COPY_FORMATTED -> Icons.Default.FormatPaint
        SelAction.COPY_PLAIN -> Icons.Default.Notes
        SelAction.COPY_TABLE -> Icons.Default.TableChart
        SelAction.COPY_NO_DIACRITICS -> Icons.Default.FormatClear
        SelAction.COPY_NORMALIZED -> Icons.Default.Spellcheck
        SelAction.COPY_LATIN_DIGITS, SelAction.COPY_ARABIC_DIGITS -> Icons.Default.Dialpad
        SelAction.SELECT_SENTENCE -> Icons.Default.FormatQuote
        SelAction.SELECT_ALL -> Icons.Default.SelectAll
        SelAction.COLUMN_SELECT -> Icons.Default.ViewColumn
        SelAction.HIGHLIGHT -> Icons.Default.Highlight
        SelAction.UNDERLINE -> Icons.Default.FormatUnderlined
        SelAction.STRIKEOUT -> Icons.Default.FormatStrikethrough
        SelAction.SQUIGGLY -> Icons.Default.Waves
        SelAction.REPLACE_TEXT -> Icons.Default.FindReplace
        SelAction.INSERT_TEXT -> Icons.Default.PostAdd
        SelAction.ADD_NOTE, SelAction.NOTE_HERE -> Icons.Default.AddComment
        SelAction.READING_BOOKMARK -> Icons.Default.Bookmark
        SelAction.PDF_BOOKMARK -> Icons.Default.BookmarkAdd
        SelAction.SEARCH_DOC -> Icons.Default.FindInPage
        SelAction.WEB_SEARCH -> Icons.Default.TravelExplore
        SelAction.READ_ALOUD -> Icons.AutoMirrored.Filled.VolumeUp
        SelAction.WORD_COUNT -> Icons.Default.Numbers
        SelAction.OTHER_APPS -> Icons.Default.Apps
        SelAction.AI_TRANSLATE -> Icons.Default.Translate
        SelAction.AI_EXPLAIN -> Icons.Default.AutoAwesome
        SelAction.AI_DEFINE -> Icons.Default.Book
        SelAction.AI_SUMMARIZE -> Icons.Default.Summarize
        SelAction.AI_REWRITE -> Icons.Default.AutoFixHigh
        SelAction.EDIT_TEXT -> Icons.Default.EditNote
        SelAction.CREATE_LINK -> Icons.Default.AddLink
        SelAction.REDACT -> Icons.Default.Block
        SelAction.SHARE, SelAction.LINK_SHARE -> Icons.Default.Share
        SelAction.EXPORT_TXT -> Icons.Default.Output
        SelAction.PASTE -> Icons.Default.ContentPaste
        SelAction.PASTE_STAMP -> Icons.Default.ContentPasteGo
        SelAction.TEXT_HERE -> Icons.Default.TextFields
        SelAction.FILL_TEXT_HERE -> Icons.Default.Title
        SelAction.IMAGE_HERE -> Icons.Default.AddPhotoAlternate
        SelAction.SIGN_HERE -> Icons.Default.Draw
        SelAction.SNAPSHOT -> Icons.Default.PhotoCamera
        SelAction.READ_PAGE -> Icons.Default.RecordVoiceOver
        SelAction.SEARCH -> Icons.Default.Search
        SelAction.MARQUEE_ZOOM -> Icons.Default.ZoomIn
        SelAction.ZOOM -> Icons.Default.ZoomOutMap
        SelAction.GO_TO_PAGE -> Icons.Default.Tag
        SelAction.ROTATE_VIEW -> Icons.Default.RotateRight
        SelAction.PRINT -> Icons.Default.Print
        SelAction.DOC_INFO -> Icons.Default.Info
        // fix/selmenu-rest: annotation
        SelAction.A_COLOR -> Icons.Default.Palette
        SelAction.A_OPACITY -> Icons.Default.Opacity
        SelAction.A_THICKNESS -> Icons.Default.LineWeight
        SelAction.A_EDIT_TEXT -> Icons.Default.Edit
        SelAction.A_TEXT_FORMAT -> Icons.Default.FormatSize
        SelAction.A_COPY, SelAction.A_CLIP_COPY -> Icons.Default.ContentCopy
        SelAction.A_PLAY, SelAction.A_VIDEO_PLAY -> Icons.Default.PlayArrow
        SelAction.A_STAMP_CUT, SelAction.A_CUT -> Icons.Default.ContentCut
        SelAction.A_STAMP_COPY -> Icons.Default.CopyAll
        SelAction.A_STAMP_DEFAULT_SIZE -> Icons.Default.AspectRatio
        SelAction.A_STAMP_PAGES, SelAction.A_PLACE_PAGES -> Icons.Default.FileCopy
        SelAction.A_OPEN_FILE -> Icons.Default.OpenInNew
        SelAction.A_VIDEO_OPTIONS, SelAction.A_IMAGE_PROPS -> Icons.Default.Tune
        SelAction.A_VIDEO_SHARE -> Icons.Default.Share
        SelAction.A_VIDEO_SAVE -> Icons.Default.SaveAlt
        SelAction.A_RECOGNIZE -> Icons.Default.Gesture
        SelAction.A_DELETE -> Icons.Default.Delete
        SelAction.A_RANGE_START_LESS, SelAction.A_RANGE_END_LESS -> Icons.Default.KeyboardDoubleArrowLeft
        SelAction.A_RANGE_START_MORE, SelAction.A_RANGE_END_MORE -> Icons.Default.KeyboardDoubleArrowRight
        SelAction.A_REPLY -> Icons.AutoMirrored.Filled.Reply
        SelAction.A_PROPS -> Icons.Default.Info
        SelAction.A_LOCK -> Icons.Default.Lock
        SelAction.A_UNLOCK -> Icons.Default.LockOpen
        SelAction.A_PASTE_IN_PLACE -> Icons.Default.ContentPaste
        SelAction.A_ROTATE_LEFT -> Icons.AutoMirrored.Filled.RotateLeft
        SelAction.A_ROTATE_RIGHT -> Icons.AutoMirrored.Filled.RotateRight
        SelAction.A_ROTATE_CUSTOM -> Icons.Default.Rotate90DegreesCcw
        SelAction.A_FLATTEN -> Icons.Default.Layers
        SelAction.A_EXPORT_XFDF, SelAction.A_EXPORT_FDF -> Icons.Default.Upload
        SelAction.A_EMAIL_FDF -> Icons.Default.Email
        // form field
        SelAction.F_COPY_VALUE -> Icons.Default.ContentCopy
        SelAction.F_PASTE -> Icons.Default.ContentPaste
        SelAction.F_CLEAR -> Icons.AutoMirrored.Filled.Backspace
        SelAction.F_RESET -> Icons.Default.RestartAlt
        SelAction.F_PICK_IMAGE -> Icons.Default.AddPhotoAlternate
        SelAction.F_SIGN -> Icons.Default.Draw
        SelAction.F_SPELL -> Icons.Default.Spellcheck
        SelAction.F_CALCULATE -> Icons.Default.Calculate
        SelAction.F_PROPS -> Icons.Default.Info
        SelAction.F_DESIGNER -> Icons.Default.DesignServices
        SelAction.F_DUPLICATE -> Icons.Default.LibraryAdd
        SelAction.F_DELETE -> Icons.Default.Delete
        SelAction.F_TAB_ORDER -> Icons.Default.FormatListNumbered
        // page-content picture
        SelAction.I_COPY -> Icons.Default.ContentCopy
        SelAction.I_CUT -> Icons.Default.ContentCut
        SelAction.I_SAVE -> Icons.Default.SaveAlt
        SelAction.I_SHARE -> Icons.Default.Share
        SelAction.I_REPLACE -> Icons.Default.FindReplace
        SelAction.I_CROP -> Icons.Default.Crop
        SelAction.I_ROTATE_LEFT -> Icons.AutoMirrored.Filled.RotateLeft
        SelAction.I_ROTATE_RIGHT -> Icons.AutoMirrored.Filled.RotateRight
        SelAction.I_FLIP_H -> Icons.Default.FlipToBack
        SelAction.I_FLIP_V -> Icons.Default.Flip
        SelAction.I_EDITOR -> Icons.Default.Brush
        SelAction.I_PROPS -> Icons.Default.Info
        SelAction.I_MORE_TOOLS -> Icons.Default.Tune
        SelAction.I_DELETE -> Icons.Default.Delete
        SelAction.I_FRONT -> Icons.Default.FlipToFront
        SelAction.I_BACK -> Icons.Default.FlipToBack
        SelAction.I_DUPLICATE -> Icons.Default.LibraryAdd
    }

    fun group(g: SelGroup): Int = when (g) {
        SelGroup.SMART -> R.string.sm_g_smart
        SelGroup.CLIPBOARD -> R.string.sm_g_clipboard
        SelGroup.ARABIC -> R.string.sm_g_arabic
        SelGroup.SELECT -> R.string.sm_g_select
        SelGroup.MARKUP -> R.string.sm_g_markup
        SelGroup.COMMENT -> R.string.sm_g_comment
        SelGroup.LOOKUP -> R.string.sm_g_lookup
        SelGroup.AI -> R.string.sm_g_ai
        SelGroup.EDIT -> R.string.sm_g_edit
        SelGroup.SHARE -> R.string.sm_g_share
        SelGroup.LINK -> R.string.sm_g_link
        SelGroup.INSERT -> R.string.sm_g_insert
        SelGroup.PAGE -> R.string.sm_g_page
        SelGroup.ANNOT_STYLE -> R.string.smr_g_annot_style
        SelGroup.ANNOT_ACTION -> R.string.smr_g_annot_action
        SelGroup.ANNOT_ARRANGE -> R.string.smr_g_annot_arrange
        SelGroup.ANNOT_EXPORT -> R.string.smr_g_annot_export
        SelGroup.FIELD_VALUE -> R.string.smr_g_field_value
        SelGroup.FIELD_FORM -> R.string.smr_g_field_form
        SelGroup.IMAGE_OUT -> R.string.smr_g_image_out
        SelGroup.IMAGE_EDIT -> R.string.smr_g_image_edit
        SelGroup.IMAGE_ARRANGE -> R.string.smr_g_image_arrange
    }

    fun why(w: Why): Int = when (w) {
        Why.AUTHOR -> R.string.np2_restricted_author
        Why.PROTECTED_VIEW -> R.string.np2_pv_blocked
        Why.SIGNED -> R.string.sm_why_signed
        Why.EMPTY_CLIPBOARD -> R.string.nv_clipboard_empty
    }
}

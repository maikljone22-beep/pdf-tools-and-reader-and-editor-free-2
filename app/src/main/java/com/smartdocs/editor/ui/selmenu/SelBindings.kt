package com.smartdocs.editor.ui.selmenu

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector
import com.smartdocs.editor.R
import com.smartdocs.editor.core.selmenu.SelAction
import com.smartdocs.editor.core.selmenu.SelGroup
import com.smartdocs.editor.core.selmenu.Why

/** Label and icon of every registry action (core/selmenu/SelMenu.kt); strings in values (ar) and values-en. */
object SelBindings {
    fun label(a: SelAction): Int = when (a) {
        // fix/thumbs-panel: page thumbnail menu (strings_thumbs_panel.xml)
        SelAction.THUMB_OPEN -> R.string.tpn_go_to_page
        SelAction.THUMB_SELECT -> R.string.tpn_select
        SelAction.THUMB_DESELECT -> R.string.tpn_deselect
        SelAction.THUMB_ROTATE_LEFT -> R.string.tpn_rotate_left
        SelAction.THUMB_ROTATE_RIGHT -> R.string.tpn_rotate_right
        SelAction.THUMB_DUPLICATE -> R.string.tpn_duplicate
        SelAction.THUMB_DELETE -> R.string.tpn_delete
        SelAction.THUMB_EXTRACT -> R.string.tpn_extract
        SelAction.THUMB_INSERT_BLANK -> R.string.tpn_insert_blank
        SelAction.THUMB_INSERT_FILE -> R.string.tpn_insert_file
        SelAction.THUMB_REPLACE -> R.string.tpn_replace
        SelAction.THUMB_PRINT -> R.string.tpn_print
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
    }

    fun icon(a: SelAction): ImageVector = when (a) {
        SelAction.THUMB_OPEN -> Icons.Default.Pageview
        SelAction.THUMB_SELECT -> Icons.Default.CheckCircle
        SelAction.THUMB_DESELECT -> Icons.Default.RadioButtonUnchecked
        SelAction.THUMB_ROTATE_LEFT -> Icons.Default.RotateLeft
        SelAction.THUMB_ROTATE_RIGHT -> Icons.Default.RotateRight
        SelAction.THUMB_DUPLICATE -> Icons.Default.FileCopy
        SelAction.THUMB_DELETE -> Icons.Default.Delete
        SelAction.THUMB_EXTRACT -> Icons.Default.ContentCut
        SelAction.THUMB_INSERT_BLANK -> Icons.Default.NoteAdd
        SelAction.THUMB_INSERT_FILE -> Icons.Default.FileOpen
        SelAction.THUMB_REPLACE -> Icons.Default.FindReplace
        SelAction.THUMB_PRINT -> Icons.Default.Print
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
    }

    fun group(g: SelGroup): Int = when (g) {
        SelGroup.THUMB -> R.string.tpn_g_pages
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
    }

    fun why(w: Why): Int = when (w) {
        Why.AUTHOR -> R.string.np2_restricted_author
        Why.PROTECTED_VIEW -> R.string.np2_pv_blocked
        Why.SIGNED -> R.string.sm_why_signed
        Why.EMPTY_CLIPBOARD -> R.string.nv_clipboard_empty
    }
}

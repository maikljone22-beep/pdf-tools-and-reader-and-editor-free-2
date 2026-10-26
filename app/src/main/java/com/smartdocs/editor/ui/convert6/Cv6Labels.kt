package com.smartdocs.editor.ui.convert6

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.vector.ImageVector
import com.smartdocs.editor.R
import com.smartdocs.editor.core.convert6.Conv
import com.smartdocs.editor.core.convert6.Cv6Group
import com.smartdocs.editor.core.convert6.Fmt
import com.smartdocs.editor.core.theme.Brand
import com.smartdocs.editor.core.theme.TileColors

/** String resource of a card format label. */
fun Fmt.labelRes(): Int = when (this) {
    Fmt.PDF -> R.string.cv6_f_pdf; Fmt.WORD -> R.string.cv6_f_word; Fmt.EXCEL -> R.string.cv6_f_excel; Fmt.PPT -> R.string.cv6_f_ppt
    Fmt.IMAGE -> R.string.cv6_f_image; Fmt.JPG -> R.string.cv6_f_jpg; Fmt.PNG -> R.string.cv6_f_png; Fmt.WEBP -> R.string.cv6_f_webp
    Fmt.HEIC -> R.string.cv6_f_heic; Fmt.TIFF -> R.string.cv6_f_tiff; Fmt.ZIP -> R.string.cv6_f_zip; Fmt.LONG_IMAGE -> R.string.cv6_f_long
    Fmt.TXT -> R.string.cv6_f_txt; Fmt.HTML -> R.string.cv6_f_html; Fmt.RTF -> R.string.cv6_f_rtf; Fmt.MD -> R.string.cv6_f_md
    Fmt.EPUB -> R.string.cv6_f_epub; Fmt.CSV -> R.string.cv6_f_csv; Fmt.ODT -> R.string.cv6_f_odt; Fmt.ODS -> R.string.cv6_f_ods
    Fmt.ODP -> R.string.cv6_f_odp; Fmt.SVG -> R.string.cv6_f_svg; Fmt.PDFA -> R.string.cv6_f_pdfa; Fmt.GRAY -> R.string.cv6_f_gray
    Fmt.SMALL -> R.string.cv6_f_small; Fmt.SEARCHABLE -> R.string.cv6_f_searchable; Fmt.FLAT -> R.string.cv6_f_flat; Fmt.WEB -> R.string.cv6_f_web
    Fmt.EMAIL -> R.string.cv6_f_email; Fmt.CAMERA -> R.string.cv6_f_camera; Fmt.CLIPBOARD -> R.string.cv6_f_clipboard; Fmt.ANY -> R.string.cv6_f_any
    Fmt.OCR -> R.string.cv6_f_scanned; Fmt.HANDOUT -> R.string.cv6_f_handout; Fmt.RESIZE -> R.string.cv6_f_resize; Fmt.IMAGES_OUT -> R.string.cv6_f_images
}

fun Fmt.icon(): ImageVector = when (this) {
    Fmt.PDF, Fmt.PDFA -> Icons.Default.PictureAsPdf
    Fmt.WORD, Fmt.ODT, Fmt.RTF -> Icons.Default.Description
    Fmt.EXCEL, Fmt.ODS, Fmt.CSV -> Icons.Default.TableChart
    Fmt.PPT, Fmt.ODP, Fmt.HANDOUT -> Icons.Default.Slideshow
    Fmt.IMAGE, Fmt.JPG, Fmt.PNG, Fmt.WEBP, Fmt.HEIC, Fmt.TIFF -> Icons.Default.Image
    Fmt.IMAGES_OUT -> Icons.Default.Collections
    Fmt.ZIP -> Icons.Default.FolderZip
    Fmt.LONG_IMAGE -> Icons.Default.ViewDay
    Fmt.TXT -> Icons.Default.TextSnippet
    Fmt.HTML, Fmt.WEB -> Icons.Default.Language
    Fmt.MD -> Icons.Default.Code
    Fmt.EPUB -> Icons.Default.MenuBook
    Fmt.SVG -> Icons.Default.Polyline
    Fmt.GRAY -> Icons.Default.Contrast
    Fmt.SMALL -> Icons.Default.Compress
    Fmt.SEARCHABLE, Fmt.OCR -> Icons.Default.DocumentScanner
    Fmt.FLAT -> Icons.Default.Layers
    Fmt.EMAIL -> Icons.Default.Email
    Fmt.CAMERA -> Icons.Default.PhotoCamera
    Fmt.CLIPBOARD -> Icons.Default.ContentPaste
    Fmt.ANY -> Icons.Default.AutoAwesomeMosaic
    Fmt.RESIZE -> Icons.Default.PhotoSizeSelectLarge
}

@Composable
@ReadOnlyComposable
fun Fmt.tile(): TileColors = with(Brand.tiles) {
    when (this@tile) {
        Fmt.PDF, Fmt.PDFA, Fmt.FLAT, Fmt.SMALL, Fmt.GRAY -> rose
        Fmt.WORD, Fmt.ODT, Fmt.RTF, Fmt.TXT, Fmt.MD -> blue
        Fmt.EXCEL, Fmt.ODS, Fmt.CSV -> green
        Fmt.PPT, Fmt.ODP, Fmt.HANDOUT -> amber
        Fmt.IMAGE, Fmt.JPG, Fmt.PNG, Fmt.WEBP, Fmt.HEIC, Fmt.TIFF, Fmt.IMAGES_OUT, Fmt.LONG_IMAGE, Fmt.SVG, Fmt.RESIZE, Fmt.ZIP -> purple
        else -> teal
    }
}

fun Cv6Group.titleRes(): Int = when (this) {
    Cv6Group.FROM_PDF -> R.string.cv6_g_from_pdf; Cv6Group.TO_PDF -> R.string.cv6_g_to_pdf; Cv6Group.IMAGES -> R.string.cv6_g_images
    Cv6Group.OFFICE -> R.string.cv6_g_office; Cv6Group.TEXT_BOOKS -> R.string.cv6_g_text; Cv6Group.OCR -> R.string.cv6_g_ocr; Cv6Group.OTHER -> R.string.cv6_g_other
}

fun Cv6Group.icon(): ImageVector = when (this) {
    Cv6Group.FROM_PDF -> Icons.Default.PictureAsPdf; Cv6Group.TO_PDF -> Icons.Default.NoteAdd; Cv6Group.IMAGES -> Icons.Default.Image
    Cv6Group.OFFICE -> Icons.Default.BusinessCenter; Cv6Group.TEXT_BOOKS -> Icons.Default.MenuBook; Cv6Group.OCR -> Icons.Default.DocumentScanner; Cv6Group.OTHER -> Icons.Default.Widgets
}

/** Card title: a dedicated string for a few cards, else "<from> to <to>". */
fun Conv.title(ctx: Context): String = when (id) {
    "extract_images" -> ctx.getString(R.string.cv6_t_extract_images)
    "pdf_long_image" -> ctx.getString(R.string.cv6_t_long_image)
    "pdf_pdfa", "pdf_gray", "pdf_compress", "pdf_searchable", "pdf_flatten" -> ctx.getString(R.string.cv6_conv_title, ctx.getString(from.labelRes()), ctx.getString(to.labelRes()))
    "img_convert" -> ctx.getString(R.string.cv6_t_img_convert)
    "img_resize" -> ctx.getString(R.string.cv6_t_img_resize)
    "pptx_handouts" -> ctx.getString(R.string.cv6_t_handouts)
    "scan_pdf" -> ctx.getString(R.string.cv6_t_scan)
    "clipboard_pdf" -> ctx.getString(R.string.cv6_t_clipboard)
    "any_pdf" -> ctx.getString(R.string.cv6_t_any)
    else -> ctx.getString(R.string.cv6_conv_title, ctx.getString(from.labelRes()), ctx.getString(to.labelRes()))
}

/** One-line hint under a card title (empty = none). */
fun Conv.hint(ctx: Context): String = when (id) {
    "pdf_docx" -> ctx.getString(R.string.cv6_h_pdf_docx)
    "pdf_images" -> ctx.getString(R.string.cv6_h_pdf_images)
    "pdf_epub" -> ctx.getString(R.string.cv6_h_pdf_epub)
    "pdf_svg" -> ctx.getString(R.string.cv6_h_pdf_svg)
    "pdf_odp", "pptx_odp" -> ctx.getString(R.string.cv6_h_picture_slides)
    "word_pdf" -> ctx.getString(R.string.cv6_h_word_pdf)
    "excel_pdf" -> ctx.getString(R.string.cv6_h_excel_pdf)
    "images_pdf" -> ctx.getString(R.string.cv6_h_images_pdf)
    "any_pdf" -> ctx.getString(R.string.cv6_h_any)
    "extract_images" -> ctx.getString(R.string.cv6_h_extract)
    "ocr_pdf_docx" -> ctx.getString(R.string.cv6_h_ocr_docx)
    "pdf_searchable" -> ctx.getString(R.string.cv6_h_searchable)
    "pdf_gray" -> ctx.getString(R.string.cv6_h_gray)
    "pdf_pdfa" -> ctx.getString(R.string.cv6_h_pdfa)
    "pptx_handouts" -> ctx.getString(R.string.cv6_h_handouts)
    "heic_jpg" -> ctx.getString(R.string.cv6_h_heic)
    else -> ""
}

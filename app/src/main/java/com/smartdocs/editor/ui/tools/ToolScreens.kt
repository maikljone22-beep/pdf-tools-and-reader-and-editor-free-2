package com.smartdocs.editor.ui.tools

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController
import com.smartdocs.editor.ui.ai.AiScreen
import com.smartdocs.editor.ui.ai.AiSettingsScreen
import com.smartdocs.editor.ui.forms.FormsScreen
import com.smartdocs.editor.ui.ocr.OcrScreen
import com.smartdocs.editor.ui.pages.OrganizeScreen
import com.smartdocs.editor.ui.scanner.ScannerScreen
import com.smartdocs.editor.ui.security.RedactScreen
import com.smartdocs.editor.ui.signature.DigitalSignScreen
import com.smartdocs.editor.ui.signature.FillProfileScreen
import com.smartdocs.editor.ui.signature.SignaturesScreen
import com.smartdocs.editor.ui.stamps.StampsScreen

/**
 * Routes a tool id (see [allTools] and `Routes.tool`) to its screen.
 * [uri] is an optional `file://` URI of the document to preselect.
 */
@Composable
fun ToolScreens(nav: NavHostController, id: String, uri: String?) {
    val source = remember(uri) { uri?.let { runCatching { java.io.File(android.net.Uri.parse(it).path!!) }.getOrNull()?.takeIf { f -> f.exists() } } }
    androidx.compose.runtime.CompositionLocalProvider(com.smartdocs.editor.ui.common.LocalToolSource provides source) { ToolScreensInner(nav, id, uri) }
}

@Composable
private fun ToolScreensInner(nav: NavHostController, id: String, uri: String?) {
    when (id) {
        // --- screens owned by the tools module ---
        "blank" -> BlankPdfScreen(nav)
        "images_to_pdf" -> ImagesToPdfScreen(nav)
        "merge" -> MergeScreen(nav, uri)
        "split" -> SplitScreen(nav, uri)
        "compress" -> CompressScreen(nav, uri)
        "page_numbers" -> PageNumbersScreen(nav, uri)
        "watermark" -> WatermarkScreen(nav, uri)
        "doc_info" -> DocInfoScreen(nav, uri)
        "protect" -> ProtectScreen(nav, uri)
        "unlock" -> UnlockScreen(nav, uri)
        "flatten" -> FlattenScreen(nav, uri)
        "pdf_to_images" -> PdfToImagesScreen(nav, uri)
        "pdf_to_text" -> PdfToTextScreen(nav, uri)
        "convert" -> ConvertScreen(nav, uri)
        "compare" -> CompareScreen(nav, uri)
        "print" -> PrintScreen(nav, uri)
        "read_aloud" -> ReadAloudScreen(nav, uri)
        "wifi" -> com.smartdocs.editor.ui.cloud.WifiScreen(nav)
        "help" -> com.smartdocs.editor.ui.help.HelpScreen(nav)

        // --- screens owned by other modules ---
        "organize", "rotate", "extract" -> OrganizeScreen(nav, uri)
        "scan" -> ScannerScreen(nav)
        "ocr" -> OcrScreen(nav, uri)
        "redact" -> RedactScreen(nav, uri)
        "search_all" -> com.smartdocs.editor.ui.search.GlobalSearchScreen(nav)
        "search_highlight" -> com.smartdocs.editor.ui.search.SearchMarkScreen(nav, uri)
        "find_replace" -> com.smartdocs.editor.ui.search.FindReplaceScreen(nav, uri)
        "sanitize" -> com.smartdocs.editor.ui.security.SanitizeScreen(nav, uri)
        "protect_advanced" -> com.smartdocs.editor.ui.security.ProtectAdvancedScreen(nav, uri)
        "forms" -> FormsScreen(nav, uri)
        "attachments" -> AttachmentsTool(nav, uri)
        "templates" -> com.smartdocs.editor.ui.templates.TemplatesScreen(nav)
        "cert_protect" -> com.smartdocs.editor.ui.tools.CertificateProtectScreen(nav, uri)
        "podcast" -> com.smartdocs.editor.ui.voice.PodcastScreen(nav, uri)
        "outline" -> com.smartdocs.editor.ui.edit.OutlineEditorScreen(nav, uri)
        "form_builder" -> com.smartdocs.editor.ui.forms.FormBuilderScreen(nav, uri)
        "signatures", "sign" -> SignaturesScreen(nav)
        "digital_sign" -> DigitalSignScreen(nav, uri)
        "fill_profile" -> FillProfileScreen(nav)
        "stamps" -> StampsScreen(nav)
        "ai" -> AiScreen(nav, uri)
        "ai_settings" -> AiSettingsScreen(nav)
        "ai_spaces" -> com.smartdocs.editor.ui.ai5.SpacesScreen(nav)

        else -> if (id in com.smartdocs.editor.ui.edit5.EDIT5_TOOL_IDS) com.smartdocs.editor.ui.edit5.Edit5Screens(nav, id, uri) else ComingSoonScreen(nav, id)
    }
}

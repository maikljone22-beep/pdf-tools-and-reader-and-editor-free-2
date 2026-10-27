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
        // share6: signature requests, connected documents
        "esign" -> com.smartdocs.editor.ui.esign.EsignDashboardScreen(nav)
        "esign_create" -> com.smartdocs.editor.ui.esign.EsignCreateScreen(nav, uri)
        "connected" -> com.smartdocs.editor.ui.connected.ConnectedHomeScreen(nav, uri)
        // office5: Office start screen (open / new Word / new Excel)
        "office" -> com.smartdocs.editor.ui.office.OfficeStartScreen(nav)
        "split" -> SplitScreen(nav, uri)
        "compress" -> CompressScreen(nav, uri)
        "page_numbers" -> PageNumbersScreen(nav, uri)
        "watermark" -> com.smartdocs.editor.ui.edit5.Edit5Screens(nav, "e5_watermark", uri)   // NVE: the edit5 watermark (old screen: "watermark_classic")
        "watermark_classic" -> WatermarkScreen(nav, uri)
        "doc_info" -> DocInfoScreen(nav, uri)
        "protect" -> ProtectScreen(nav, uri)
        "unlock" -> UnlockScreen(nav, uri)
        "flatten" -> FlattenScreen(nav, uri)
        "pdf_to_images" -> PdfToImagesScreen(nav, uri)
        "pdf_to_text" -> PdfToTextScreen(nav, uri)
        // convert6: the Convert hub replaces the old two-tab screen everywhere "convert" is opened (reader, home, panes)
        "convert", "convert_hub" -> com.smartdocs.editor.ui.convert6.ConvertHubScreen(nav, uri)
        "convert_classic" -> ConvertScreen(nav, uri)
        "extract_images" -> com.smartdocs.editor.ui.convert6.ExtractImagesScreen(nav, uri)
        "compare" -> com.smartdocs.editor.ui.tools5.Compare2Screen(nav, uri)
        "compare_basic" -> CompareScreen(nav, uri)
        "scan_studio" -> com.smartdocs.editor.ui.tools5.ScanStudioScreen(nav, uri)
        "to_pdf_plus" -> com.smartdocs.editor.ui.tools5.ToPdfPlusScreen(nav, uri)
        "share_to_pdf" -> com.smartdocs.editor.ui.tools5.ShareToPdfScreen(nav)
        "pdfa" -> com.smartdocs.editor.ui.tools5.PdfAScreen(nav, uri)
        "optimize" -> com.smartdocs.editor.ui.tools5.OptimizeScreen(nav, uri)
        "export_plus" -> com.smartdocs.editor.ui.tools5.ExportPlusScreen(nav, uri)
        "mail_merge" -> com.smartdocs.editor.ui.tools5.MailMergeScreen(nav, uri)
        "portfolio" -> com.smartdocs.editor.ui.tools5.PortfolioScreen(nav, uri)
        "ocr_plus" -> com.smartdocs.editor.ui.tools5.OcrBatchScreen(nav, uri)
        "recipes" -> com.smartdocs.editor.ui.tools5.RecipesScreen(nav, uri)
        "batch" -> com.smartdocs.editor.ui.tools5.BatchScreen(nav)
        "compare_plus" -> com.smartdocs.editor.ui.tools5.Compare2Screen(nav, uri)
        "accessibility" -> com.smartdocs.editor.ui.tools5.AccessibilityScreen(nav, uri)
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
        "outline" -> com.smartdocs.editor.ui.edit5.Edit5Screens(nav, "e5_bookmarks", uri)   // NVE: the edit5 bookmarks editor (old screen: "outline_classic")
        "outline_classic" -> com.smartdocs.editor.ui.edit.OutlineEditorScreen(nav, uri)
        "print_batch" -> com.smartdocs.editor.ui.nve.file.BatchPrintScreen(nav)   // NVE: print many files
        "form_builder" -> com.smartdocs.editor.ui.forms.FormBuilderScreen(nav, uri)
        "signatures", "sign" -> SignaturesScreen(nav)
        "digital_sign" -> DigitalSignScreen(nav, uri)
        "fill_profile" -> FillProfileScreen(nav)
        "stamps" -> StampsScreen(nav)
        "ai" -> AiScreen(nav, uri)
        "ai_settings" -> AiSettingsScreen(nav)
        "ai_spaces" -> com.smartdocs.editor.ui.ai5.SpacesScreen(nav)
        "ai_side_by_side" -> com.smartdocs.editor.ui.ai6.SideBySideScreen(nav, uri)   // ai6 (B12)
        "ai_image" -> com.smartdocs.editor.ui.ai6.ImageGenScreen(nav, uri)            // ai6 (AI-8)
        "ai_transcribe" -> com.smartdocs.editor.ui.ai6.TranscribeScreen(nav, uri)     // ai6 (audio transcription)
        "ai_chats" -> com.smartdocs.editor.ui.ai6.ConversationsScreen(nav)   // ai6 (AI-5)
        "ai_skills" -> com.smartdocs.editor.ui.ai6.SkillsScreen(nav)          // ai6 (AI-6)
        "ai_help" -> com.smartdocs.editor.ui.ai6.HelpChatScreen(nav)          // ai6 (§41 support chatbot)

        else -> if (id in com.smartdocs.editor.ui.edit5.EDIT5_TOOL_IDS) com.smartdocs.editor.ui.edit5.Edit5Screens(nav, id, uri) else ComingSoonScreen(nav, id)
    }
}

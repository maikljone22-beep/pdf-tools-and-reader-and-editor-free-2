package com.smartdocs.editor.ui.ai

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController

/**
 * Route `tool/ai`. Since MODULE_ai5 the full-screen assistant is the AI hub ([com.smartdocs.editor.ui.ai5.AiHubScreen]):
 * cited chat over the whole document, summary, study tools, document tools and the agent. [uri] may be a
 * `file://` URI or `space:<id>`, optionally with `#tab=chat|summary|study|tools|agent`.
 */
@Composable
fun AiScreen(nav: NavHostController, uri: String?) = com.smartdocs.editor.ui.ai5.AiHubScreen(nav, uri)

package com.smartdocs.editor.ui.forms

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController

/**
 * The "form_builder" tool. It used to create new fields only (drawn in the current session); it is now the full
 * form designer (ui/formsx): existing fields can be selected, moved, resized, edited and deleted as well.
 */
@Composable
fun FormBuilderScreen(nav: NavHostController, uri: String?) = com.smartdocs.editor.ui.formsx.FormDesignerScreen(nav, uri)

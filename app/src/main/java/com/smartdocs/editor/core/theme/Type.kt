package com.smartdocs.editor.core.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.smartdocs.editor.R

/** Noto Sans Arabic (bundled, res/font). Latin glyphs fall back to the system sans font. */
val AppFontFamily = FontFamily(
    Font(R.font.noto_sans_arabic_regular, FontWeight.Normal),
    Font(R.font.noto_sans_arabic_medium, FontWeight.Medium),
    Font(R.font.noto_sans_arabic_bold, FontWeight.SemiBold),
    Font(R.font.noto_sans_arabic_bold, FontWeight.Bold),
)

private val base = Typography()

private fun TextStyle.app(weight: FontWeight? = null, size: Int? = null, line: Int? = null) = copy(
    fontFamily = AppFontFamily,
    fontWeight = weight ?: fontWeight,
    fontSize = size?.sp ?: fontSize,
    lineHeight = line?.sp ?: lineHeight,
    letterSpacing = 0.sp, // tracking breaks Arabic joining
)

/** Arabic needs a little more line height than the Latin defaults. */
val AppTypography = Typography(
    displayLarge = base.displayLarge.app(FontWeight.Bold),
    displayMedium = base.displayMedium.app(FontWeight.Bold),
    displaySmall = base.displaySmall.app(FontWeight.Bold),
    headlineLarge = base.headlineLarge.app(FontWeight.Bold, line = 42),
    headlineMedium = base.headlineMedium.app(FontWeight.Bold, line = 38),
    headlineSmall = base.headlineSmall.app(FontWeight.Bold, line = 34),
    titleLarge = base.titleLarge.app(FontWeight.Bold, line = 30),
    titleMedium = base.titleMedium.app(FontWeight.SemiBold, line = 26),
    titleSmall = base.titleSmall.app(FontWeight.Medium, line = 22),
    bodyLarge = base.bodyLarge.app(line = 26),
    bodyMedium = base.bodyMedium.app(line = 22),
    bodySmall = base.bodySmall.app(line = 18),
    labelLarge = base.labelLarge.app(FontWeight.Medium, line = 22),
    labelMedium = base.labelMedium.app(FontWeight.Medium, line = 18),
    labelSmall = base.labelSmall.app(FontWeight.Medium, line = 16),
)

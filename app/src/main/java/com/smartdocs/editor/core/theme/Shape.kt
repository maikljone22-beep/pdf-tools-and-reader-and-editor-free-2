package com.smartdocs.editor.core.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Pill shape for search fields, chips and primary buttons. */
val PillShape = RoundedCornerShape(50)

/** Card radius used across lists / grouped settings. */
val CardShape = RoundedCornerShape(20.dp)

/** Spacing scale (4dp grid). */
object Spacing {
    val xxs = 2.dp
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    /** Horizontal screen gutter. */
    val gutter = 16.dp
}

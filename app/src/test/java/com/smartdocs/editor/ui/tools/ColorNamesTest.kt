package com.smartdocs.editor.ui.tools

import org.junit.Assert.assertEquals
import org.junit.Test

/** audit 5 C-22: spoken colour names of the swatches (search marks, watermark colours). */
class ColorNamesTest {

    @Test fun markColours() {
        assertEquals(ColorKind.YELLOW, colorKind(0xFFEB3B))
        assertEquals(ColorKind.GREEN, colorKind(0x8BC34A))
        assertEquals(ColorKind.CYAN, colorKind(0x4FC3F7))
        assertEquals(ColorKind.PINK, colorKind(0xF48FB1))
        assertEquals(ColorKind.ORANGE, colorKind(0xFF9800))
        assertEquals(ColorKind.RED, colorKind(0xE53935))
        assertEquals(ColorKind.BLACK, colorKind(0x000000))
        assertEquals(ColorKind.WHITE, colorKind(0xFFFFFF))
        assertEquals(ColorKind.GRAY, colorKind(0x9E9E9E))
        assertEquals(ColorKind.BLUE, colorKind(0x3F51B5))
    }

    @Test fun watermarkColours() {
        assertEquals(ColorKind.BLUE, colorKind(0x1E88E5))
        assertEquals(ColorKind.GREEN, colorKind(0x43A047))
        assertEquals(ColorKind.ORANGE, colorKind(0xFB8C00))
        assertEquals(ColorKind.BLACK, colorKind(0x212121))
        assertEquals(ColorKind.PURPLE, colorKind(0x8E24AA))
    }
}

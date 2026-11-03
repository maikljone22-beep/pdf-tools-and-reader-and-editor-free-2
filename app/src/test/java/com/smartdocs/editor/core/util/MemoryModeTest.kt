package com.smartdocs.editor.core.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryModeTest {
    @Test fun bigFilesAndSmallHeapsSaveMemory() {
        val mb = 1024L * 1024
        assertFalse(MemoryMode.lowFor(50 * mb, 512 * mb))
        assertTrue(MemoryMode.lowFor(400 * mb, 512 * mb))     // the 400 MB book
        assertTrue(MemoryMode.lowFor(5 * mb, 128 * mb))       // weak phone
    }
}

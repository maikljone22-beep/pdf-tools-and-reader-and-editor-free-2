package com.smartdocs.editor.core.edit5

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Foxit §23b Bates options: increment (also negative), 15 digits, output names with the Bates range, log. */
class FoxitBatesTest {

    @Test fun incrementAndCountDown() {
        val files = listOf(3 to listOf(0, 1, 2), 2 to listOf(0, 1))
        val up = Bates.plan(BatesSpec(start = 10, increment = 5), files)
        assertEquals(listOf(10L, 15L, 20L), up[0].values.toList()); assertEquals(listOf(25L, 30L), up[1].values.toList())
        val down = Bates.plan(BatesSpec(start = 100, increment = -1), files)
        assertEquals(listOf(100L, 99L, 98L), down[0].values.toList()); assertEquals(listOf(97L, 96L), down[1].values.toList())
        // a zero step is treated as 1
        assertEquals(listOf(1L, 2L, 3L), Bates.plan(BatesSpec(increment = 0), listOf(3 to listOf(0, 1, 2)))[0].values.toList())
    }

    @Test fun fifteenDigits() {
        assertEquals("X000000000000042", Bates.format(BatesSpec(prefix = "X", digits = 15), 42))
        assertEquals("-007", Bates.format(BatesSpec(digits = 3), -7))
    }

    @Test fun outputNames() {
        assertEquals("contract_bates", Bates.outputName("contract", "A001", "A010", BatesNaming.KEEP))
        assertEquals("contract_A001-A010", Bates.outputName("contract", "A001", "A010", BatesNaming.ADD_RANGE))
        assertEquals("A001-A010", Bates.outputName("contract", "A001", "A010", BatesNaming.REPLACE_WITH_RANGE))
        assertEquals("X_1-X_2", Bates.outputName("f", "X/1", "X:2", BatesNaming.REPLACE_WITH_RANGE))   // no path characters
    }

    @Test fun logLists() {
        val log = Bates.log(BatesSpec(prefix = "P", start = 5, increment = 2), listOf(Triple("a.pdf", "P000005", "P000009")))
        assertTrue(log.contains("increment=2")); assertTrue(log.contains("a.pdf\tP000005 – P000009"))
    }
}

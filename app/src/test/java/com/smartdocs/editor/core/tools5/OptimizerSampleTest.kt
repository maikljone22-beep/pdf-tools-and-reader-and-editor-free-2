package com.smartdocs.editor.core.tools5

import org.junit.Assert.assertEquals
import org.junit.Test

class OptimizerSampleTest {
    @Test fun subsamplesBigPhotosButNeverBelowTheTarget() {
        assertEquals(2, Optimizer.sampleFor(4000, 1400))     // 4000 → 2000 (>= 1400), not 1000
        assertEquals(1, Optimizer.sampleFor(1500, 1400))
        assertEquals(4, Optimizer.sampleFor(6000, 1400))     // 6000 → 1500
        assertEquals(1, Optimizer.sampleFor(4000, Int.MAX_VALUE))   // no downscale asked: full size
    }
}

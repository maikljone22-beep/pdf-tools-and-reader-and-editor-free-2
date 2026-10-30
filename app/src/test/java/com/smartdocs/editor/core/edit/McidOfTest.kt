package com.smartdocs.editor.core.edit

import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.contentstream.operator.Operator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class McidOfTest {
    private fun mc(id: Int) = COSDictionary().apply { setInt(COSName.getPDFName("MCID"), id) }

    @Test fun innermostMarkedContentWins() {
        val tokens = listOf<Any>(
            COSName.getPDFName("P"), mc(0), Operator.getOperator("BDC"),
            COSName.getPDFName("Im1"), Operator.getOperator("Do"),          // op 1 → mcid 0
            Operator.getOperator("EMC"),
            COSName.getPDFName("Artifact"), Operator.getOperator("BMC"),
            COSName.getPDFName("Im2"), Operator.getOperator("Do"),          // op 4 → none (artifact)
            Operator.getOperator("EMC"),
            COSName.getPDFName("Figure"), mc(3), Operator.getOperator("BDC"),
            COSName.getPDFName("Span"), Operator.getOperator("BMC"),
            COSName.getPDFName("Im3"), Operator.getOperator("Do"),          // op 8 → mcid 3 (through the unmarked BMC)
        )
        val ops = ImageEdit5.scanTokens(tokens).ops
        assertEquals(0, ImageEdit5.mcidOf(ops, 1))
        assertNull(ImageEdit5.mcidOf(ops, 4))
        assertEquals(3, ImageEdit5.mcidOf(ops, 8))
    }
}

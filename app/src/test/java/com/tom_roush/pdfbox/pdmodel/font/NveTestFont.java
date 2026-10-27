package com.tom_roush.pdfbox.pdmodel.font;

import com.tom_roush.fontbox.util.BoundingBox;
import com.tom_roush.pdfbox.cos.COSName;
import com.tom_roush.pdfbox.util.Matrix;

import java.io.IOException;
import java.io.InputStream;

/**
 * Test-only font for JVM unit tests (NveTextTest): pdfbox-android's standard-14 and TrueType fonts need Android assets
 * (AFM metrics, predefined CMaps) and android.util.Log, which plain JVM tests do not have. Single-byte codes, every
 * glyph 500 units wide; U+2022 maps to 0x95 (WinAnsi bullet); anything above U+00FF cannot be encoded.
 * Lives in PDFBox's package because PDFont's no-argument constructor is package-private.
 */
public class NveTestFont extends PDFont {
    public NveTestFont() {
        super();
        getCOSObject().setItem(COSName.SUBTYPE, COSName.getPDFName("Type1"));
        getCOSObject().setName(COSName.BASE_FONT, "NveTest");
    }

    @Override protected float getStandard14Width(int code) { return 500f; }

    @Override protected byte[] encode(int unicode) {
        if (unicode == 0x2022) return new byte[] { (byte) 0x95 };
        if (unicode > 255) throw new IllegalArgumentException("no glyph for U+" + Integer.toHexString(unicode));
        return new byte[] { (byte) unicode };
    }

    @Override public int readCode(InputStream in) throws IOException { return in.read(); }
    @Override public boolean isVertical() { return false; }
    @Override public void addToSubset(int codePoint) { }
    @Override public void subset() { }
    @Override public boolean willBeSubset() { return false; }
    @Override public String getName() { return "NveTest"; }
    @Override public Matrix getFontMatrix() { return new Matrix(0.001f, 0f, 0f, 0.001f, 0f, 0f); }
    @Override public BoundingBox getBoundingBox() { return new BoundingBox(0f, -200f, 1000f, 800f); }
    @Override public float getHeight(int code) { return 700f; }
    @Override public float getWidth(int code) { return 500f; }
    @Override public boolean hasExplicitWidth(int code) { return true; }
    @Override public float getWidthFromFont(int code) { return 500f; }
    @Override public boolean isEmbedded() { return false; }
    @Override public boolean isDamaged() { return false; }
    @Override public float getAverageFontWidth() { return 500f; }
}

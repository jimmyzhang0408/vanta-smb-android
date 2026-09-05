package com.vanta.smb;

import org.junit.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;

public class ScanTextParserTest {
    @Test public void decodesActualGbQrPayloadEvenIfDecoderGuessedAnotherCharset() {
        String original = "材料有限公司|样品一号|热轧";
        assertEquals(original, ScanTextParser.decodeQrText("乱码|乱码|乱码",
                java.util.Collections.singletonList(original.getBytes(Charset.forName("GB18030")))));
    }

    @Test public void utf8PayloadPreservesChineseFilename() {
        String original = "材料有限公司|样品一号|热轧";
        assertEquals(original, ScanTextParser.decodeQrText(original,
                java.util.Collections.singletonList(original.getBytes(StandardCharsets.UTF_8))));
    }

    @Test public void ignoresPartialByteSegmentsInMixedModeQrCode() {
        String original = "材料公司|SUP001|123456";
        assertEquals(original, ScanTextParser.decodeQrText(original,
                java.util.Collections.singletonList("材料公司".getBytes(StandardCharsets.UTF_8))));
    }

    @Test public void doesNotDropTrailingNumericModeSegment() {
        String original = "材料公司|SUP001|123456";
        assertEquals(original, ScanTextParser.decodeQrText(original,
                java.util.Collections.singletonList("材料公司|SUP001|".getBytes(StandardCharsets.UTF_8))));
    }

    @Test(expected = IllegalArgumentException.class) public void rejectsIrreversiblyDamagedFilename() {
        ScanTextParser.secondFieldAsFileStem("公司|样品\uFFFD|订单");
    }
    @Test public void usesSecondPipeSeparatedField() {
        String raw = "XX材料有限公司|SUP001|PO2024001|MAT001|11|316|热轧|ASTM|1200*600*20|H2024001";
        assertEquals("SUP001", ScanTextParser.secondFieldAsFileStem(raw));
    }

    @Test public void preservesValidChineseUtf8() {
        String raw = "材料公司|样品一号|订单";
        assertEquals(raw, ScanTextParser.normalize(raw));
        assertEquals("样品一号", ScanTextParser.secondFieldAsFileStem(raw));
    }

    @Test public void repairsGb18030BytesDecodedAsLatin1() {
        String original = "XX材料有限公司|SUP001|热轧";
        String mojibake = new String(original.getBytes(Charset.forName("GB18030")), StandardCharsets.ISO_8859_1);
        assertEquals(original, ScanTextParser.normalize(mojibake));
        assertEquals("SUP001", ScanTextParser.secondFieldAsFileStem(mojibake));
    }

    @Test public void repairsProvidedScannerExample() {
        String raw = "XX²ÄÁÏÓÐÏÞ¹«Ë¾|SUP001|PO2024001|MAT001|11|316|ÈÈÔþ|ASTM|1200*600*20|H2024001";
        assertEquals("XX材料有限公司|SUP001|PO2024001|MAT001|11|316|热轧|ASTM|1200*600*20|H2024001",
                ScanTextParser.normalize(raw));
        assertEquals("SUP001", ScanTextParser.secondFieldAsFileStem(raw));
    }

    @Test public void sanitizesUnsafeFilenameCharacters() {
        assertEquals("SUP_001", ScanTextParser.secondFieldAsFileStem("company|SUP/001|order"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMissingSeparator() {
        ScanTextParser.secondFieldAsFileStem("SUP001");
    }
}

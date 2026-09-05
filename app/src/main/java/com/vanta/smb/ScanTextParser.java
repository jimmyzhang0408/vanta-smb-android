package com.vanta.smb;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class ScanTextParser {
    private static final Charset GB18030 = Charset.forName("GB18030");

    private ScanTextParser() {}

    /** Repairs the two most common QR mojibake paths while leaving valid Unicode unchanged. */
    public static String normalize(String raw) {
        if (raw == null) return "";
        String cleaned = raw.replace("\u0000", "").trim();
        List<String> candidates = new ArrayList<>();
        candidates.add(cleaned);
        addLatin1Candidate(candidates, cleaned, StandardCharsets.UTF_8);
        addLatin1Candidate(candidates, cleaned, GB18030);

        String best = cleaned;
        int bestScore = qualityScore(cleaned);
        for (String candidate : candidates) {
            int score = qualityScore(candidate);
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    public static String secondFieldAsFileStem(String raw) {
        String normalized = normalize(raw);
        String[] parts = normalized.split("\\|", -1);
        if (parts.length < 2) {
            throw new IllegalArgumentException("二维码内容中没有找到 | 分隔符");
        }
        String stem = parts[1].trim();
        if (stem.toLowerCase(java.util.Locale.ROOT).endsWith(".json")) {
            stem = stem.substring(0, stem.length() - 5);
        }
        stem = stem.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_")
                   .replaceAll("[. ]+$", "")
                   .trim();
        if (stem.isEmpty() || ".".equals(stem) || "..".equals(stem)) {
            throw new IllegalArgumentException("二维码第二段为空或不能作为文件名");
        }
        if (stem.length() > 180) stem = stem.substring(0, 180);
        return stem;
    }

    private static void addLatin1Candidate(List<String> result, String text, Charset target) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 255) return;
        }
        try {
            byte[] bytes = text.getBytes(StandardCharsets.ISO_8859_1);
            CharBuffer decoded = target.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            result.add(decoded.toString());
        } catch (CharacterCodingException ignored) {
        }
    }

    private static int qualityScore(String text) {
        int score = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) score += 4;
            else if (Character.isLetterOrDigit(c)) score += 1;
            else if (c == '\uFFFD' || c == '\u00C2' || c == '\u00C3') score -= 8;
            else if (Character.isISOControl(c) && !Character.isWhitespace(c)) score -= 10;
        }
        return score;
    }
}

package com.nexusapi.server.modules.auth.support;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

@Component
public class CaptchaSvgRenderer {
    private static final int WIDTH = 164;
    private static final int HEIGHT = 52;
    private static final Map<Character, String[]> GLYPHS = Map.ofEntries(
            Map.entry('2', rows("11110", "00001", "00001", "11110", "10000", "10000", "11111")),
            Map.entry('3', rows("11110", "00001", "00001", "01110", "00001", "00001", "11110")),
            Map.entry('4', rows("10010", "10010", "10010", "11111", "00010", "00010", "00010")),
            Map.entry('5', rows("11111", "10000", "10000", "11110", "00001", "00001", "11110")),
            Map.entry('6', rows("01111", "10000", "10000", "11110", "10001", "10001", "01110")),
            Map.entry('7', rows("11111", "00001", "00010", "00100", "01000", "01000", "01000")),
            Map.entry('8', rows("01110", "10001", "10001", "01110", "10001", "10001", "01110")),
            Map.entry('9', rows("01110", "10001", "10001", "01111", "00001", "00001", "11110")),
            Map.entry('A', rows("01110", "10001", "10001", "11111", "10001", "10001", "10001")),
            Map.entry('C', rows("01111", "10000", "10000", "10000", "10000", "10000", "01111")),
            Map.entry('E', rows("11111", "10000", "10000", "11110", "10000", "10000", "11111")),
            Map.entry('F', rows("11111", "10000", "10000", "11110", "10000", "10000", "10000")),
            Map.entry('H', rows("10001", "10001", "10001", "11111", "10001", "10001", "10001")),
            Map.entry('J', rows("00111", "00010", "00010", "00010", "00010", "10010", "01100")),
            Map.entry('K', rows("10001", "10010", "10100", "11000", "10100", "10010", "10001")),
            Map.entry('L', rows("10000", "10000", "10000", "10000", "10000", "10000", "11111")),
            Map.entry('M', rows("10001", "11011", "10101", "10101", "10001", "10001", "10001")),
            Map.entry('N', rows("10001", "11001", "10101", "10011", "10001", "10001", "10001")),
            Map.entry('P', rows("11110", "10001", "10001", "11110", "10000", "10000", "10000")),
            Map.entry('R', rows("11110", "10001", "10001", "11110", "10100", "10010", "10001")),
            Map.entry('T', rows("11111", "00100", "00100", "00100", "00100", "00100", "00100")),
            Map.entry('U', rows("10001", "10001", "10001", "10001", "10001", "10001", "01110")),
            Map.entry('V', rows("10001", "10001", "10001", "10001", "10001", "01010", "00100")),
            Map.entry('W', rows("10001", "10001", "10001", "10101", "10101", "10101", "01010")),
            Map.entry('X', rows("10001", "10001", "01010", "00100", "01010", "10001", "10001")),
            Map.entry('Y', rows("10001", "10001", "01010", "00100", "00100", "00100", "00100")),
            Map.entry('Z', rows("11111", "00001", "00010", "00100", "01000", "10000", "11111"))
    );

    private final SecureRandom random = new SecureRandom();

    public String renderDataUri(String code) {
        StringBuilder svg = new StringBuilder(4096);
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(WIDTH)
                .append("\" height=\"").append(HEIGHT).append("\" viewBox=\"0 0 ")
                .append(WIDTH).append(' ').append(HEIGHT).append("\">")
                .append("<rect width=\"100%\" height=\"100%\" rx=\"8\" fill=\"#eef2ff\"/>");

        for (int i = 0; i < 8; i++) {
            svg.append("<path d=\"M").append(random.nextInt(WIDTH)).append(' ').append(random.nextInt(HEIGHT))
                    .append(" Q").append(random.nextInt(WIDTH)).append(' ').append(random.nextInt(HEIGHT))
                    .append(' ').append(random.nextInt(WIDTH)).append(' ').append(random.nextInt(HEIGHT))
                    .append("\" fill=\"none\" stroke=\"#a5b4fc\" stroke-opacity=\".45\" stroke-width=\"1\"/>");
        }

        for (int index = 0; index < code.length(); index++) {
            appendGlyph(svg, code.charAt(index), 13 + index * 38, 8 + random.nextInt(4), random.nextInt(9) - 4);
        }

        for (int i = 0; i < 24; i++) {
            svg.append("<circle cx=\"").append(random.nextInt(WIDTH)).append("\" cy=\"")
                    .append(random.nextInt(HEIGHT)).append("\" r=\"").append(1 + random.nextInt(2))
                    .append("\" fill=\"#6366f1\" fill-opacity=\".35\"/>");
        }
        svg.append("</svg>");
        return "data:image/svg+xml;base64," + Base64.getEncoder()
                .encodeToString(svg.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void appendGlyph(StringBuilder svg, char character, int x, int y, int rotation) {
        String[] rows = GLYPHS.get(character);
        if (rows == null) {
            throw new IllegalArgumentException("Unsupported captcha glyph");
        }
        svg.append("<g transform=\"rotate(").append(rotation).append(' ').append(x + 10).append(' ').append(y + 14).append(")\">");
        for (int row = 0; row < rows.length; row++) {
            for (int column = 0; column < rows[row].length(); column++) {
                if (rows[row].charAt(column) == '1') {
                    svg.append("<rect x=\"").append(x + column * 4).append("\" y=\"").append(y + row * 5)
                            .append("\" width=\"4.4\" height=\"5.4\" rx=\"1\" fill=\"#312e81\"/>");
                }
            }
        }
        svg.append("</g>");
    }

    private static String[] rows(String... rows) {
        return rows;
    }
}

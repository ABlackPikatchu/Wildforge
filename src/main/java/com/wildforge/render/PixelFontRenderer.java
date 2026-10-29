package com.wildforge.render;

import java.util.List;
import static com.wildforge.render.UiPrimitives.rect;
import static com.wildforge.render.InventoryLayout.SLOT_SIZE;

/** Pixel font and inventory stack digits, preserving existing glyph geometry. */
final class PixelFontRenderer {
    private PixelFontRenderer() { }

    static void menuText(List<Float> data, String value, float centerX,
                          float y, float size, float r, float g, float b,
                          float alpha) {
        drawText(data, value, centerX + size * 0.52f, y + size * 0.6f,
                size, 0.015f, 0.018f, 0.024f, alpha * 0.8f);
        drawText(data, value, centerX, y,
                size, r, g, b, alpha);
    }


    static void drawText(
            List<Float> data,
            String text,
            float centerX,
            float topY,
            float scale,
            float red,
            float green,
            float blue,
            float alpha) {

        float textWidth = text.length() * 6.0f * scale - scale;
        float startX = centerX - textWidth / 2.0f;

        for (int index = 0; index < text.length(); index++) {
            String[] rows = glyph(Character.toUpperCase(text.charAt(index)));
            float charX = startX + index * 6.0f * scale;

            for (int row = 0; row < rows.length; row++) {
                for (int column = 0; column < rows[row].length(); column++) {
                    if (rows[row].charAt(column) == '1') {
                        rect(data,
                                charX + column * scale,
                                topY + row * scale,
                                scale, scale,
                                red, green, blue, alpha);
                    }
                }
            }
        }
    }

    private static String[] glyph(char character) {
        return switch (character) {
            case 'A' -> new String[] {"01110", "10001", "10001", "11111", "10001", "10001", "10001"};
            case 'B' -> new String[] {"11110", "10001", "10001", "11110", "10001", "10001", "11110"};
            case 'C' -> new String[] {"01111", "10000", "10000", "10000", "10000", "10000", "01111"};
            case 'D' -> new String[] {"11110", "10001", "10001", "10001", "10001", "10001", "11110"};
            case 'E' -> new String[] {"11111", "10000", "10000", "11110", "10000", "10000", "11111"};
            case 'F' -> new String[] {"11111", "10000", "10000", "11110", "10000", "10000", "10000"};
            case 'G' -> new String[] {"01111", "10000", "10000", "10111", "10001", "10001", "01110"};
            case 'H' -> new String[] {"10001", "10001", "10001", "11111", "10001", "10001", "10001"};
            case 'I' -> new String[] {"11111", "00100", "00100", "00100", "00100", "00100", "11111"};
            case 'J' -> new String[] {"00111", "00010", "00010", "00010", "10010", "10010", "01100"};
            case 'K' -> new String[] {"10001", "10010", "10100", "11000", "10100", "10010", "10001"};
            case 'L' -> new String[] {"10000", "10000", "10000", "10000", "10000", "10000", "11111"};
            case 'M' -> new String[] {"10001", "11011", "10101", "10101", "10001", "10001", "10001"};
            case 'N' -> new String[] {"10001", "11001", "11001", "10101", "10011", "10011", "10001"};
            case 'O' -> new String[] {"01110", "10001", "10001", "10001", "10001", "10001", "01110"};
            case 'P' -> new String[] {"11110", "10001", "10001", "11110", "10000", "10000", "10000"};
            case 'Q' -> new String[] {"01110", "10001", "10001", "10001", "10101", "10010", "01101"};
            case 'R' -> new String[] {"11110", "10001", "10001", "11110", "10100", "10010", "10001"};
            case 'S' -> new String[] {"01111", "10000", "10000", "01110", "00001", "00001", "11110"};
            case 'T' -> new String[] {"11111", "00100", "00100", "00100", "00100", "00100", "00100"};
            case 'U' -> new String[] {"10001", "10001", "10001", "10001", "10001", "10001", "01110"};
            case 'V' -> new String[] {"10001", "10001", "10001", "10001", "10001", "01010", "00100"};
            case 'W' -> new String[] {"10001", "10001", "10001", "10101", "10101", "10101", "01010"};
            case 'X' -> new String[] {"10001", "10001", "01010", "00100", "01010", "10001", "10001"};
            case 'Y' -> new String[] {"10001", "10001", "01010", "00100", "00100", "00100", "00100"};
            case 'Z' -> new String[] {"11111", "00001", "00010", "00100", "01000", "10000", "11111"};
            case '0' -> new String[] {"01110", "10001", "10011", "10101", "11001", "10001", "01110"};
            case '1' -> new String[] {"00100", "01100", "00100", "00100", "00100", "00100", "01110"};
            case '2' -> new String[] {"01110", "10001", "00001", "00010", "00100", "01000", "11111"};
            case '3' -> new String[] {"11110", "00001", "00001", "01110", "00001", "00001", "11110"};
            case '4' -> new String[] {"00010", "00110", "01010", "10010", "11111", "00010", "00010"};
            case '5' -> new String[] {"11111", "10000", "10000", "11110", "00001", "00001", "11110"};
            case '6' -> new String[] {"01110", "10000", "10000", "11110", "10001", "10001", "01110"};
            case '7' -> new String[] {"11111", "00001", "00010", "00100", "01000", "01000", "01000"};
            case '8' -> new String[] {"01110", "10001", "10001", "01110", "10001", "10001", "01110"};
            case '9' -> new String[] {"01110", "10001", "10001", "01111", "00001", "00001", "01110"};
            case '/' -> new String[] {"00001","00001","00010","00100","01000","10000","10000"};
            case ':' -> new String[] {"00000","00100","00100","00000","00100","00100","00000"};
            case '.' -> new String[] {"00000","00000","00000","00000","00000","00110","00110"};
            case ',' -> new String[] {"00000","00000","00000","00000","00100","00100","01000"};
            case '-' -> new String[] {"00000","00000","00000","11111","00000","00000","00000"};
            case '_' -> new String[] {"00000","00000","00000","00000","00000","00000","11111"};
            case '[' -> new String[] {"01110","01000","01000","01000","01000","01000","01110"};
            case ']' -> new String[] {"01110","00010","00010","00010","00010","00010","01110"};
            case '+' -> new String[] {"00000","00100","00100","11111","00100","00100","00000"};
            case '?' -> new String[] {"01110","10001","00001","00010","00100","00000","00100"};
            case '#' -> new String[] {"01010","11111","01010","01010","11111","01010","00000"};
            case '=' -> new String[] {"00000","11111","00000","00000","11111","00000","00000"};
            case '>' -> new String[] {"10000","01000","00100","00010","00100","01000","10000"};
            case '<' -> new String[] {"00001","00010","00100","01000","00100","00010","00001"};
            case '!' -> new String[] {"00100","00100","00100","00100","00100","00000","00100"};
            case '(' -> new String[] {"00010","00100","01000","01000","01000","00100","00010"};
            case ')' -> new String[] {"01000","00100","00010","00010","00010","00100","01000"};
            default -> new String[] {"00000", "00000", "00000", "00000", "00000", "00000", "00000"};
        };
    }


    private static final String[][] COUNT_DIGITS = {
                {"111", "101", "101", "101", "111"}, // 0
                {"010", "110", "010", "010", "111"}, // 1
                {"111", "001", "111", "100", "111"}, // 2
                {"111", "001", "111", "001", "111"}, // 3
                {"101", "101", "111", "001", "001"}, // 4
                {"111", "100", "111", "001", "111"}, // 5
                {"111", "100", "111", "101", "111"}, // 6
                {"111", "001", "010", "010", "010"}, // 7
                {"111", "101", "111", "101", "111"}, // 8
                {"111", "101", "111", "001", "111"}  // 9
        };

    /** Compact pixel-font stack counts, anchored inside the bottom-right slot inset. */
    static void drawNumber(List<Float> data, float slotX, float slotY, int number) {
        if (number <= 0) {
            return;
        }

        String text = Integer.toString(number);
        final float pixel = 2f;
        final float digitWidth = 3f * pixel;
        final float gap = 2f;
        final float width = text.length() * digitWidth + (text.length() - 1) * gap;
        final float height = 5f * pixel;

        // Leave four screen pixels between the count and the slot's right/bottom edge.
        float x = slotX + SLOT_SIZE - 4f - width;
        float y = slotY + SLOT_SIZE - 4f - height;

        // Translucent inset keeps light digits legible even over the item texture.
        rect(data, x - 2f, y - 2f, width + 4f, height + 4f,
                0.035f, 0.045f, 0.052f, 0.76f);

        for (int i = 0; i < text.length(); i++) {
            int digit = text.charAt(i) - '0';
            float digitX = x + i * (digitWidth + gap);
            drawCountDigit(data, digitX + 1f, y + 1f, digit, pixel,
                    0.015f, 0.018f, 0.020f);
            drawCountDigit(data, digitX, y, digit, pixel,
                    0.96f, 0.95f, 0.87f);
        }
    }

    /** 3×5 pixel digits: smaller and more consistent than seven-segment numbers. */
    private static void drawCountDigit(
            List<Float> data, float x, float y, int digit, float pixel,
            float r, float g, float b) {
        if (digit < 0 || digit >= COUNT_DIGITS.length) {
            return;
        }
        String[] rows = COUNT_DIGITS[digit];
        for (int row = 0; row < rows.length; row++) {
            for (int col = 0; col < rows[row].length(); col++) {
                if (rows[row].charAt(col) == '1') {
                    rect(data, x + col * pixel, y + row * pixel,
                            pixel, pixel, r, g, b, 1f);
                }
            }
        }
    }

}

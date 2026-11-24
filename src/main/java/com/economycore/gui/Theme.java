package com.economycore.gui;

import java.util.HashMap;
import java.util.Map;

/** Modern colour system: maps the classic &-codes to a hand-picked hex palette and supports &#RRGGBB. */
public final class Theme {
    private Theme() {}

    public static boolean enabled = true;

    private static final Map<Character, String> PALETTE = new HashMap<>();
    static {
        PALETTE.put('0', "1F2430"); PALETTE.put('1', "3B5BDB"); PALETTE.put('2', "2F9E6B"); PALETTE.put('3', "1C9BB0");
        PALETTE.put('4', "D64545"); PALETTE.put('5', "8E5CF0"); PALETTE.put('6', "FFB020"); PALETTE.put('7', "A3ABBD");
        PALETTE.put('8', "636B7F"); PALETTE.put('9', "5B9DFF"); PALETTE.put('a', "4ADE80"); PALETTE.put('b', "4FE3D0");
        PALETTE.put('c', "FF6B6B"); PALETTE.put('d', "F472B6"); PALETTE.put('e', "FFD54A"); PALETTE.put('f', "F4F6FB");
    }

    public static String hex(String rrggbb) {
        StringBuilder sb = new StringBuilder("§x");
        for (char c : rrggbb.toCharArray()) sb.append('§').append(Character.toLowerCase(c));
        return sb.toString();
    }

    private static boolean isHex(String s, int from) {
        if (from + 6 > s.length()) return false;
        for (int i = from; i < from + 6; i++) if (Character.digit(s.charAt(i), 16) < 0) return false;
        return true;
    }

    /** Converts &-codes (and &#RRGGBB) to section codes, using the palette for the 16 classic colours. */
    public static String paint(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&' && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                if (n == '#' && isHex(s, i + 2)) { out.append(hex(s.substring(i + 2, i + 8))); i += 7; continue; }
                char l = Character.toLowerCase(n);
                if (PALETTE.containsKey(l)) {
                    if (enabled) out.append(hex(PALETTE.get(l))); else out.append('§').append(l);
                    i++;
                    continue;
                }
                if ("klmnor".indexOf(l) >= 0) { out.append('§').append(l); i++; continue; }
            }
            out.append(c);
        }
        return out.toString();
    }

    /** Horizontal gradient between two hex colours (no #), one colour per character. */
    public static String gradient(String text, String from, String to, boolean bold) {
        int n = Math.max(1, text.length() - 1);
        int r1 = Integer.parseInt(from.substring(0, 2), 16), g1 = Integer.parseInt(from.substring(2, 4), 16), b1 = Integer.parseInt(from.substring(4, 6), 16);
        int r2 = Integer.parseInt(to.substring(0, 2), 16), g2 = Integer.parseInt(to.substring(2, 4), 16), b2 = Integer.parseInt(to.substring(4, 6), 16);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            double t = (double) i / n;
            sb.append(hex(String.format("%02X%02X%02X", (int) (r1 + (r2 - r1) * t), (int) (g1 + (g2 - g1) * t), (int) (b1 + (b2 - b1) * t))));
            if (bold) sb.append("§l");
            sb.append(text.charAt(i));
        }
        return sb.toString();
    }

    /** Progress bar made of thin blocks: ▰▰▰▱▱ */
    public static String bar(int value, int max, int len) {
        int f = max <= 0 ? 0 : (int) Math.round((double) Math.min(value, max) * len / max);
        return "&6" + "▰".repeat(f) + "&8" + "▱".repeat(len - f);
    }
}

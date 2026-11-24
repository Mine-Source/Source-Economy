package com.economycore.gui;

import java.util.HashMap;
import java.util.Map;

/** Modern colour system: maps the classic &-codes to a hand-picked hex palette and supports &#RRGGBB. */
public final class Theme {
    private Theme() {}

    public static boolean enabled = true;

    private static final Map<Character, String> PALETTE = new HashMap<>();
    private static final Map<Character, String> TITLE = new HashMap<>();
    static {
        // Texto sobre fondo oscuro (items, chat). Roles: 6=dinero, e=acción, a=positivo, c=negativo, b=acento, 9=info, d/5=premium, 7=texto, 8=tenue, f=títulos.
        String[][] p = {
                {"0", "0F172A"}, {"1", "3B82F6"}, {"2", "22C55E"}, {"3", "06B6D4"}, {"4", "EF4444"}, {"5", "A78BFA"}, {"6", "FBBF24"}, {"7", "94A3B8"},
                {"8", "64748B"}, {"9", "60A5FA"}, {"a", "4ADE80"}, {"b", "2DD4BF"}, {"c", "F87171"}, {"d", "F0ABFC"}, {"e", "FDE047"}, {"f", "F8FAFC"}};
        // Texto sobre el fondo gris claro del título del inventario: versiones oscuras con buen contraste.
        String[][] t = {
                {"0", "0F172A"}, {"1", "1E3A8A"}, {"2", "166534"}, {"3", "0E7490"}, {"4", "991B1B"}, {"5", "5B21B6"}, {"6", "92400E"}, {"7", "475569"},
                {"8", "334155"}, {"9", "1D4ED8"}, {"a", "15803D"}, {"b", "0F766E"}, {"c", "B91C1C"}, {"d", "A21CAF"}, {"e", "A16207"}, {"f", "1E293B"}};
        for (String[] x : p) PALETTE.put(x[0].charAt(0), x[1]);
        for (String[] x : t) TITLE.put(x[0].charAt(0), x[1]);
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
    public static String paint(String s) { return paint(s, PALETTE); }

    /** Same as paint but with dark colours that stay readable on the light-grey inventory title bar. */
    public static String paintTitle(String s) { return paint(s, TITLE); }

    private static String paint(String s, Map<Character, String> pal) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&' && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                if (n == '#' && isHex(s, i + 2)) { out.append(hex(s.substring(i + 2, i + 8))); i += 7; continue; }
                char l = Character.toLowerCase(n);
                if (pal.containsKey(l)) {
                    if (enabled) out.append(hex(pal.get(l))); else out.append('§').append(l);
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

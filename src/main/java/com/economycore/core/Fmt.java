package com.economycore.core;

import java.text.SimpleDateFormat;
import java.util.Date;

/** Small display helpers. */
public final class Fmt {
    private Fmt() {}

    public static String date(long ts) { return new SimpleDateFormat("dd/MM HH:mm").format(new Date(ts)); }

    public static String dateFull(long ts) { return new SimpleDateFormat("dd/MM/yyyy HH:mm:ss").format(new Date(ts)); }

    public static String duration(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        long d = s / 86400, h = (s % 86400) / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (d > 0) return d + "d " + h + "h";
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m " + sec + "s";
        return sec + "s";
    }

    /** Unicode bar, value 0..1. */
    public static String bar(double v, int len) {
        v = Math.max(0, Math.min(1, v));
        int n = (int) Math.round(v * len);
        return com.economycore.gui.Theme.paint("&a") + "█".repeat(n) + com.economycore.gui.Theme.paint("&8") + "█".repeat(len - n);
    }

    public static String spark(double[] vals) {
        if (vals.length == 0) return "";
        String chars = "▁▂▃▄▅▆▇█";
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (double v : vals) { min = Math.min(min, v); max = Math.max(max, v); }
        StringBuilder sb = new StringBuilder(com.economycore.gui.Theme.paint("&b"));
        for (double v : vals) {
            int i = max == min ? 3 : (int) Math.round((v - min) / (max - min) * 7);
            sb.append(chars.charAt(i));
        }
        return sb.toString();
    }
}

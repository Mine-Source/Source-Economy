package com.economycore.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Formatting, parsing and scaling of monetary values. */
public final class Money {
    private Money() {}

    public static BigDecimal scale(String cur, BigDecimal v) {
        Currency c = EconomyCore.get().currencies().getOrDefault(cur);
        return v.setScale(c.decimals(), RoundingMode.HALF_UP);
    }

    public static String fmt(String cur, BigDecimal v) {
        Currency c = EconomyCore.get().currencies().getOrDefault(cur);
        if (v == null) v = BigDecimal.ZERO;
        StringBuilder p = new StringBuilder("#,##0");
        if (c.decimals() > 0) p.append('.').append("0".repeat(c.decimals()));
        DecimalFormat df = new DecimalFormat(p.toString(), DecimalFormatSymbols.getInstance(Locale.US));
        df.setRoundingMode(RoundingMode.HALF_UP);
        return Msg.color(c.color()) + c.symbol() + df.format(v) + "§r";
    }

    public static String plain(String cur, BigDecimal v) {
        Currency c = EconomyCore.get().currencies().getOrDefault(cur);
        return c.symbol() + v.setScale(c.decimals(), RoundingMode.HALF_UP).toPlainString();
    }

    public static String compact(BigDecimal v) {
        double d = v.doubleValue();
        double a = Math.abs(d);
        if (a >= 1e12) return String.format(Locale.US, "%.2fT", d / 1e12);
        if (a >= 1e9) return String.format(Locale.US, "%.2fB", d / 1e9);
        if (a >= 1e6) return String.format(Locale.US, "%.2fM", d / 1e6);
        if (a >= 1e3) return String.format(Locale.US, "%.2fK", d / 1e3);
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** Parses "1500", "1.5k", "2m", "3b". Returns null when invalid. */
    public static BigDecimal parse(String s) {
        if (s == null) return null;
        s = s.trim().toLowerCase(Locale.ROOT).replace(",", "");
        if (s.isEmpty()) return null;
        BigDecimal mult = BigDecimal.ONE;
        char last = s.charAt(s.length() - 1);
        if (last == 'k') mult = BigDecimal.valueOf(1_000L);
        else if (last == 'm') mult = BigDecimal.valueOf(1_000_000L);
        else if (last == 'b') mult = BigDecimal.valueOf(1_000_000_000L);
        else if (last == 't') mult = BigDecimal.valueOf(1_000_000_000_000L);
        if (mult.compareTo(BigDecimal.ONE) != 0) s = s.substring(0, s.length() - 1);
        try {
            BigDecimal v = new BigDecimal(s).multiply(mult);
            if (v.scale() > 8) v = v.setScale(8, RoundingMode.HALF_UP);
            if (v.precision() - v.scale() > 24) return null;
            return v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static BigDecimal pct(BigDecimal amount, BigDecimal pct) {
        return amount.multiply(pct).divide(BigDecimal.valueOf(100), 12, RoundingMode.HALF_UP);
    }

    public static BigDecimal dec(String s, BigDecimal def) {
        if (s == null || s.isEmpty()) return def;
        try { return new BigDecimal(s); } catch (NumberFormatException e) { return def; }
    }
}

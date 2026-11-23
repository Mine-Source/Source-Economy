package com.economycore.gui;

import com.economycore.core.EcoException;
import com.economycore.core.Money;
import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** Ready-made validated column setters for EditMenu fields. Table/column names are code constants, never user input. */
public final class Setters {
    private Setters() {}

    private static boolean none(String s) { return s.equals("-") || s.equalsIgnoreCase("none") || s.equalsIgnoreCase("ninguno"); }

    public static Function<String, CompletableFuture<?>> money(String table, String idCol, Object id, String col, boolean nullable) {
        return s -> {
            s = s.trim();
            if (nullable && none(s)) return EditMenu.update("UPDATE " + table + " SET " + col + "=NULL WHERE " + idCol + "=?", id);
            BigDecimal v = Money.parse(s);
            if (v == null || v.signum() < 0) return CompletableFuture.failedFuture(new EcoException("invalid-amount"));
            return EditMenu.update("UPDATE " + table + " SET " + col + "=? WHERE " + idCol + "=?", v, id);
        };
    }

    public static Function<String, CompletableFuture<?>> pct(String table, String idCol, Object id, String col) {
        return s -> {
            BigDecimal v = Money.parse(s);
            if (v == null || v.signum() < 0 || v.compareTo(BigDecimal.valueOf(100)) > 0) return CompletableFuture.failedFuture(new EcoException("invalid-number"));
            return EditMenu.update("UPDATE " + table + " SET " + col + "=? WHERE " + idCol + "=?", v, id);
        };
    }

    public static Function<String, CompletableFuture<?>> integer(String table, String idCol, Object id, String col, int min, int max) {
        return s -> {
            try {
                int v = Integer.parseInt(s.trim());
                if (v < min || v > max) return CompletableFuture.failedFuture(new EcoException("invalid-number"));
                return EditMenu.update("UPDATE " + table + " SET " + col + "=? WHERE " + idCol + "=?", v, id);
            } catch (NumberFormatException e) {
                return CompletableFuture.failedFuture(new EcoException("invalid-number"));
            }
        };
    }

    public static Function<String, CompletableFuture<?>> text(String table, String idCol, Object id, String col, boolean nullable) {
        return s -> {
            s = s.trim();
            if (nullable && none(s)) return EditMenu.update("UPDATE " + table + " SET " + col + "=NULL WHERE " + idCol + "=?", id);
            if (s.isEmpty()) return CompletableFuture.failedFuture(new EcoException("invalid-number"));
            return EditMenu.update("UPDATE " + table + " SET " + col + "=? WHERE " + idCol + "=?", s, id);
        };
    }

    public static CompletableFuture<?> flip(String table, String idCol, Object id, String col) {
        return EditMenu.update("UPDATE " + table + " SET " + col + "=1-" + col + " WHERE " + idCol + "=?", id);
    }
}

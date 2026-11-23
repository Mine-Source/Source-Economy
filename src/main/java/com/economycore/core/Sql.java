package com.economycore.core;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Tiny JDBC helpers. Always used from inside a Tx (DB thread). */
public final class Sql {
    private Sql() {}

    @FunctionalInterface
    public interface RowMap<T> { T map(ResultSet rs) throws SQLException; }

    static void bind(PreparedStatement ps, Object[] p) throws SQLException {
        for (int i = 0; i < p.length; i++) {
            Object o = p[i];
            int idx = i + 1;
            if (o == null) ps.setObject(idx, null);
            else if (o instanceof BigDecimal b) ps.setString(idx, b.toPlainString());
            else if (o instanceof Boolean b) ps.setInt(idx, b ? 1 : 0);
            else if (o instanceof Integer n) ps.setLong(idx, n.longValue());
            else if (o instanceof Long n) ps.setLong(idx, n);
            else if (o instanceof UUID u) ps.setString(idx, u.toString());
            else if (o instanceof Enum<?> e) ps.setString(idx, e.name());
            else ps.setString(idx, String.valueOf(o));
        }
    }

    public static <T> List<T> list(Tx tx, String sql, RowMap<T> m, Object... p) throws SQLException {
        try (PreparedStatement ps = tx.c().prepareStatement(sql)) {
            bind(ps, p);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> out = new ArrayList<>();
                while (rs.next()) out.add(m.map(rs));
                return out;
            }
        }
    }

    public static <T> T one(Tx tx, String sql, RowMap<T> m, Object... p) throws SQLException {
        try (PreparedStatement ps = tx.c().prepareStatement(sql)) {
            bind(ps, p);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? m.map(rs) : null;
            }
        }
    }

    public static int exec(Tx tx, String sql, Object... p) throws SQLException {
        try (PreparedStatement ps = tx.c().prepareStatement(sql)) {
            bind(ps, p);
            return ps.executeUpdate();
        }
    }

    /** Executes an INSERT and returns the generated rowid. */
    public static long insert(Tx tx, String sql, Object... p) throws SQLException {
        exec(tx, sql, p);
        try (Statement st = tx.c().createStatement(); ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) {
            return rs.next() ? rs.getLong(1) : -1L;
        }
    }

    public static long count(Tx tx, String sql, Object... p) throws SQLException {
        Long v = one(tx, sql, rs -> rs.getLong(1), p);
        return v == null ? 0L : v;
    }

    public static BigDecimal dec(ResultSet rs, String col) throws SQLException {
        String s = rs.getString(col);
        if (s == null || s.isEmpty()) return null;
        try { return new BigDecimal(s); } catch (NumberFormatException e) { return null; }
    }

    public static BigDecimal decOr0(ResultSet rs, String col) throws SQLException {
        BigDecimal d = dec(rs, col);
        return d == null ? BigDecimal.ZERO : d;
    }
}

package com.economycore.core;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.bukkit.entity.Player;

/** Progressive (marginal bracket) taxes with exemptions. Kinds: SALES, INCOME, TRANSFER, WEALTH. */
public final class Taxes {
    private Taxes() {}

    public static final String[] KINDS = {"SALES", "INCOME", "TRANSFER", "WEALTH"};

    public record Bracket(long id, String kind, String currency, BigDecimal min, BigDecimal ratePct) {}

    public static List<Bracket> brackets(Tx tx, String kind, String cur) throws SQLException {
        List<Bracket> l = Sql.list(tx, "SELECT * FROM tax_brackets WHERE kind=? AND currency IN (?, '*') ORDER BY CAST(min_amount AS REAL)",
                rs -> new Bracket(rs.getLong("id"), rs.getString("kind"), rs.getString("currency"), Money.dec(rs.getString("min_amount"), BigDecimal.ZERO), Money.dec(rs.getString("rate"), BigDecimal.ZERO)), kind, cur);
        l.sort((a, b) -> a.min().compareTo(b.min()));
        return l;
    }

    public static boolean exempt(Tx tx, UUID subject, String kind) throws SQLException {
        return Sql.count(tx, "SELECT COUNT(*) FROM tax_exemptions WHERE subject IN (?, '*') AND kind IN (?, '*')", subject.toString(), kind) > 0;
    }

    public static BigDecimal compute(Tx tx, String kind, String cur, BigDecimal amount, UUID subject) throws SQLException {
        if (subject == null || amount.signum() <= 0) return BigDecimal.ZERO;
        List<Bracket> br = brackets(tx, kind, cur);
        if (br.isEmpty() || exempt(tx, subject, kind)) return BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        for (int i = 0; i < br.size(); i++) {
            BigDecimal lower = br.get(i).min();
            if (amount.compareTo(lower) <= 0) break;
            BigDecimal upper = i + 1 < br.size() ? br.get(i + 1).min() : amount;
            BigDecimal slice = amount.min(upper).subtract(lower);
            if (slice.signum() > 0) tax = tax.add(Money.pct(slice, br.get(i).ratePct()));
        }
        return tax;
    }

    /** Main-thread helper: null when the player is permission-exempt, else the uuid to tax. */
    public static UUID subject(Player p) {
        return p.hasPermission("economycore.tax.exempt") ? null : p.getUniqueId();
    }

    public static UUID subject(UUID u) {
        Player p = org.bukkit.Bukkit.getPlayer(u);
        return p != null && p.hasPermission("economycore.tax.exempt") ? null : u;
    }
}

package com.economycore.company;

import com.economycore.core.Money;
import com.economycore.core.Sql;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public final class CompanyModels {
    private CompanyModels() {}

    public static final String[] FLAGS = {"HIRE", "FIRE", "SALARY", "PRODUCTS", "FINANCE", "DIVIDENDS", "ACCOUNTING", "SELL"};

    public record Company(String id, String name, String ticker, String industry, String description, String kind, String ceo, String currency, int totalShares,
                          BigDecimal price, BigDecimal prevPrice, boolean listed, int ipoRemaining, BigDecimal ipoPrice, BigDecimal autoDivPct, long created, String icon, boolean active) {
        public boolean isDefault() { return kind.equals("DEFAULT"); }
    }

    public record Member(String company, String uuid, String name, String role, BigDecimal salary, String flags, long hired) {
        public Set<String> flagSet() {
            Set<String> s = new HashSet<>();
            if (role.equals("CEO")) s.addAll(Arrays.asList(FLAGS));
            else for (String f : flags.split(",")) if (!f.isBlank()) s.add(f.trim());
            return s;
        }

        public boolean has(String f) { return flagSet().contains(f); }
    }

    public record Product(long id, String company, String kind, String name, String itemData, String material, BigDecimal price, int stock, String description,
                          String command, int produceAmount, int produceInterval, int produceCap, long lastProduce, boolean active) {}

    public static String defaultFlags(String role) {
        return switch (role) {
            case "DIRECTOR" -> "HIRE,FIRE,SALARY,PRODUCTS,ACCOUNTING,SELL";
            case "CEO" -> String.join(",", FLAGS);
            default -> "SELL";
        };
    }

    public static Company company(ResultSet rs) throws SQLException {
        return new Company(rs.getString("id"), rs.getString("name"), rs.getString("ticker"), rs.getString("industry"), rs.getString("description"), rs.getString("kind"),
                rs.getString("ceo") == null ? "" : rs.getString("ceo"), rs.getString("currency"), rs.getInt("total_shares"), Money.dec(rs.getString("price"), BigDecimal.ONE),
                Money.dec(rs.getString("prev_price"), Money.dec(rs.getString("price"), BigDecimal.ONE)), rs.getInt("listed") == 1, rs.getInt("ipo_remaining"),
                Money.dec(rs.getString("ipo_price"), Money.dec(rs.getString("price"), BigDecimal.ONE)), Money.dec(rs.getString("auto_div_pct"), BigDecimal.ZERO),
                rs.getLong("created"), rs.getString("icon"), rs.getInt("active") == 1);
    }

    public static Member member(ResultSet rs) throws SQLException {
        return new Member(rs.getString("company"), rs.getString("uuid"), rs.getString("name"), rs.getString("role"), Money.dec(rs.getString("salary"), BigDecimal.ZERO),
                rs.getString("flags") == null ? "" : rs.getString("flags"), rs.getLong("hired"));
    }

    public static Product product(ResultSet rs) throws SQLException {
        return new Product(rs.getLong("id"), rs.getString("company"), rs.getString("kind"), rs.getString("name"), rs.getString("item_data"), rs.getString("material"),
                Sql.decOr0(rs, "price"), rs.getInt("stock"), rs.getString("description"), rs.getString("command"), rs.getInt("produce_amount"),
                rs.getInt("produce_interval_min"), rs.getInt("produce_cap"), rs.getLong("last_produce"), rs.getInt("active") == 1);
    }
}

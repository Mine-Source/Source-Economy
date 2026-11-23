package com.economycore.market;

import com.economycore.core.Money;
import com.economycore.core.Sql;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;

public final class MarketModels {
    private MarketModels() {}

    public record Market(String id, String name, String icon, String currency, BigDecimal taxPct, BigDecimal commissionPct, String permission,
                         int openMin, int closeMin, String days, boolean rotEnabled, int rotSize, int rotIntervalMin, long lastRot,
                         boolean enabled, BigDecimal discountPct, boolean isShop) {}

    public record Category(String market, String id, String name, String icon, int sort) {}

    public record Product(long id, String market, String category, String material, String itemData, String name,
                          BigDecimal buy, BigDecimal sell, int stock, int maxStock, int restock, long lastRestock,
                          BigDecimal discount, int limit, int limitHours, String permission, boolean active, boolean inRotation) {
        public BigDecimal effectiveBuy(BigDecimal marketDiscount) {
            if (buy == null) return null;
            BigDecimal d = discount.add(marketDiscount);
            if (d.signum() <= 0) return buy;
            if (d.compareTo(BigDecimal.valueOf(100)) > 0) d = BigDecimal.valueOf(100);
            return buy.subtract(Money.pct(buy, d)).max(BigDecimal.ZERO);
        }
    }

    public static Market market(ResultSet rs) throws SQLException {
        return new Market(rs.getString("id"), rs.getString("name"), rs.getString("icon"), rs.getString("currency"),
                Money.dec(rs.getString("tax_pct"), BigDecimal.ZERO), Money.dec(rs.getString("commission_pct"), BigDecimal.ZERO), rs.getString("permission"),
                rs.getInt("open_min"), rs.getInt("close_min"), rs.getString("days") == null ? "" : rs.getString("days"),
                rs.getInt("rot_enabled") == 1, rs.getInt("rot_size"), rs.getInt("rot_interval_min"), rs.getLong("last_rot"),
                rs.getInt("enabled") == 1, Money.dec(rs.getString("discount_pct"), BigDecimal.ZERO), rs.getInt("is_shop") == 1);
    }

    public static Category category(ResultSet rs) throws SQLException {
        return new Category(rs.getString("market"), rs.getString("id"), rs.getString("name"), rs.getString("icon"), rs.getInt("sort"));
    }

    public static Product product(ResultSet rs) throws SQLException {
        return new Product(rs.getLong("id"), rs.getString("market"), rs.getString("category"), rs.getString("material"), rs.getString("item_data"), rs.getString("name"),
                Sql.dec(rs, "buy_price"), Sql.dec(rs, "sell_price"), rs.getInt("stock"), rs.getInt("max_stock"), rs.getInt("restock_per_hour"), rs.getLong("last_restock"),
                Money.dec(rs.getString("discount_pct"), BigDecimal.ZERO), rs.getInt("limit_per_player"), rs.getInt("limit_hours"), rs.getString("permission"),
                rs.getInt("active") == 1, rs.getInt("in_rotation") == 1);
    }
}

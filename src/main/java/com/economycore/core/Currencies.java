package com.economycore.core;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import org.bukkit.configuration.ConfigurationSection;

/** In-memory registry of currencies, persisted in SQLite. Unlimited currencies. */
public final class Currencies {
    private final EconomyCore plugin;
    private final Map<String, Currency> map = new ConcurrentHashMap<>();
    private volatile String defaultId = "coins";

    public Currencies(EconomyCore plugin) { this.plugin = plugin; }

    /** Startup only (blocking). Seeds from config when the table is empty. */
    public void load(Connection c) throws Exception {
        boolean empty;
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM currencies")) {
            empty = rs.next() && rs.getInt(1) == 0;
        }
        if (empty) {
            String def = plugin.getConfig().getString("economy.default-currency", "coins");
            List<?> list = plugin.getConfig().getMapList("economy.currencies");
            boolean any = false;
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) continue;
                String id = String.valueOf(m.get("id")).toLowerCase();
                String name = m.get("name") == null ? id : String.valueOf(m.get("name"));
                String sym = m.get("symbol") == null ? "$" : String.valueOf(m.get("symbol"));
                int dec = m.get("decimals") == null ? 2 : Integer.parseInt(String.valueOf(m.get("decimals")));
                String sb = m.get("start-balance") == null ? "0" : String.valueOf(m.get("start-balance"));
                boolean tr = m.get("transferable") == null || Boolean.parseBoolean(String.valueOf(m.get("transferable")));
                insert(c, new Currency(id, name, sym, dec, new BigDecimal(sb), tr, id.equals(def), "&e"));
                any = true;
            }
            if (!any) insert(c, new Currency("coins", "Monedas", "$", 2, new BigDecimal("100"), true, true, "&e"));
        }
        map.clear();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT * FROM currencies")) {
            while (rs.next()) {
                Currency cur = read(rs);
                map.put(cur.id(), cur);
                if (cur.isDefault()) defaultId = cur.id();
            }
        }
        if (!map.containsKey(defaultId) && !map.isEmpty()) defaultId = map.keySet().iterator().next();
    }

    private static Currency read(ResultSet rs) throws java.sql.SQLException {
        return new Currency(rs.getString("id"), rs.getString("name"), rs.getString("symbol"), rs.getInt("decimals"),
                new BigDecimal(rs.getString("start_balance") == null ? "0" : rs.getString("start_balance")),
                rs.getInt("transferable") == 1, rs.getInt("is_default") == 1, rs.getString("color"));
    }

    private static void insert(Connection c, Currency cur) throws java.sql.SQLException {
        try (java.sql.PreparedStatement ps = c.prepareStatement("INSERT OR REPLACE INTO currencies(id,name,symbol,decimals,start_balance,transferable,is_default,color) VALUES(?,?,?,?,?,?,?,?)")) {
            ps.setString(1, cur.id());
            ps.setString(2, cur.name());
            ps.setString(3, cur.symbol());
            ps.setInt(4, cur.decimals());
            ps.setString(5, cur.startBalance().toPlainString());
            ps.setInt(6, cur.transferable() ? 1 : 0);
            ps.setInt(7, cur.isDefault() ? 1 : 0);
            ps.setString(8, cur.color());
            ps.executeUpdate();
        }
    }

    public Currency get(String id) { return id == null ? null : map.get(id.toLowerCase()); }

    public Currency getOrDefault(String id) {
        Currency c = get(id);
        return c != null ? c : def();
    }

    public Currency def() {
        Currency c = map.get(defaultId);
        if (c == null) c = new Currency("coins", "Monedas", "$", 2, BigDecimal.ZERO, true, true, "&e");
        return c;
    }

    public String defId() { return def().id(); }

    public Collection<Currency> all() { return new ArrayList<>(map.values()); }

    public boolean exists(String id) { return get(id) != null; }

    /** Create or update a currency (unlimited count). */
    public CompletableFuture<Void> save(Currency cur, String actor) {
        return plugin.db().tx(tx -> {
            if (cur.isDefault()) Sql.exec(tx, "UPDATE currencies SET is_default=0");
            insert(tx.c(), cur);
            Audit.log(tx, actor, "currency.save", cur.id(), cur.name() + " " + cur.symbol() + " dec=" + cur.decimals());
            tx.afterCommit(() -> {});
            return null;
        }).thenAccept(v -> {
            if (cur.isDefault()) {
                for (Currency o : new ArrayList<>(map.values())) {
                    if (!o.id().equals(cur.id()) && o.isDefault()) map.put(o.id(), new Currency(o.id(), o.name(), o.symbol(), o.decimals(), o.startBalance(), o.transferable(), false, o.color()));
                }
                defaultId = cur.id();
            }
            map.put(cur.id(), cur);
        });
    }

    public CompletableFuture<Void> delete(String id, String actor) {
        return plugin.db().tx(tx -> {
            if (id.equalsIgnoreCase(defaultId)) throw new EcoException("currency-default-delete");
            long used = Sql.count(tx, "SELECT COUNT(*) FROM balances WHERE currency=? AND amount<>'0' AND amount<>'0.00'", id);
            if (used > 0) throw new EcoException("currency-in-use");
            Sql.exec(tx, "DELETE FROM currencies WHERE id=?", id);
            Sql.exec(tx, "DELETE FROM balances WHERE currency=?", id);
            Audit.log(tx, actor, "currency.delete", id, "");
            return null;
        }).thenAccept(v -> map.remove(id));
    }
}

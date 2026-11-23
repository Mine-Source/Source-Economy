package com.economycore.market;

import com.economycore.core.Acct;
import com.economycore.core.Audit;
import com.economycore.core.EcoException;
import com.economycore.core.EconomyCore;
import com.economycore.core.Inv;
import com.economycore.core.Ledger;
import com.economycore.core.Mailbox;
import com.economycore.core.Money;
import com.economycore.core.Sql;
import com.economycore.core.Taxes;
import com.economycore.core.Tx;
import com.economycore.market.MarketModels.Category;
import com.economycore.market.MarketModels.Market;
import com.economycore.market.MarketModels.Product;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public final class MarketService {
    private final EconomyCore pl;

    public MarketService(EconomyCore pl) { this.pl = pl; }

    public record BuyResult(BigDecimal unit, BigDecimal total, BigDecimal tax, int qty, String currency) {}
    public record SellResult(BigDecimal gross, BigDecimal net, BigDecimal fee, int qty, String currency) {}
    public record SellLine(long productId, int qty) {}
    public record SellAllResult(BigDecimal net, int sold, int returned, String currency) {}

    // ---------- schedule ----------
    public ZoneId zone() {
        try { return ZoneId.of(pl.getConfig().getString("markets.timezone", ZoneId.systemDefault().getId())); } catch (Exception e) { return ZoneId.systemDefault(); }
    }

    public boolean isOpen(Market m) {
        LocalDateTime now = LocalDateTime.now(zone());
        if (!m.days().isEmpty()) {
            String d = now.getDayOfWeek().name().substring(0, 3);
            boolean ok = false;
            for (String t : m.days().toUpperCase().split("[, ]+")) if (t.startsWith(d)) ok = true;
            if (!ok) return false;
        }
        if (m.openMin() >= 0 && m.closeMin() >= 0) {
            int cur = now.getHour() * 60 + now.getMinute();
            if (m.openMin() <= m.closeMin()) return cur >= m.openMin() && cur < m.closeMin();
            return cur >= m.openMin() || cur < m.closeMin();
        }
        return true;
    }

    public static String minToStr(int m) { return m < 0 ? "-" : String.format("%02d:%02d", m / 60, m % 60); }

    public static int strToMin(String s) {
        s = s.trim();
        if (s.equals("-") || s.isEmpty() || s.equalsIgnoreCase("none")) return -1;
        String[] p = s.split(":");
        int h = Integer.parseInt(p[0]);
        int m = p.length > 1 ? Integer.parseInt(p[1]) : 0;
        if (h < 0 || h > 23 || m < 0 || m > 59) throw new NumberFormatException();
        return h * 60 + m;
    }

    // ---------- queries ----------
    public CompletableFuture<List<Market>> markets() {
        return pl.db().tx(tx -> Sql.list(tx, "SELECT * FROM markets ORDER BY is_shop DESC, name", MarketModels::market));
    }

    public CompletableFuture<Market> market(String id) {
        return pl.db().tx(tx -> Sql.one(tx, "SELECT * FROM markets WHERE id=?", MarketModels::market, id));
    }

    public CompletableFuture<Market> shop() {
        return pl.db().tx(tx -> Sql.one(tx, "SELECT * FROM markets WHERE is_shop=1 LIMIT 1", MarketModels::market));
    }

    public CompletableFuture<List<Category>> categories(String market) {
        return pl.db().tx(tx -> Sql.list(tx, "SELECT * FROM market_categories WHERE market=? ORDER BY sort, name", MarketModels::category, market));
    }

    public CompletableFuture<Product> product(long id) {
        return pl.db().tx(tx -> {
            Product p = Sql.one(tx, "SELECT * FROM market_products WHERE id=?", MarketModels::product, id);
            return p == null ? null : applyRestock(tx, p);
        });
    }

    /** category null = every category. hidden = include inactive / out of rotation (admin). */
    public CompletableFuture<List<Product>> products(String market, String category, boolean hidden) {
        return pl.db().tx(tx -> {
            String sql = "SELECT * FROM market_products WHERE market=?" + (category != null ? " AND category=?" : "") + (hidden ? "" : " AND active=1 AND in_rotation=1") + " ORDER BY material";
            List<Product> raw = category != null ? Sql.list(tx, sql, MarketModels::product, market, category) : Sql.list(tx, sql, MarketModels::product, market);
            List<Product> out = new ArrayList<>();
            for (Product p : raw) out.add(applyRestock(tx, p));
            return out;
        });
    }

    private Product applyRestock(Tx tx, Product p) throws SQLException {
        if (p.restock() > 0 && p.maxStock() > 0 && p.stock() >= 0) {
            long hours = (System.currentTimeMillis() - p.lastRestock()) / 3_600_000L;
            if (hours > 0) {
                int ns = (int) Math.min(p.maxStock(), p.stock() + hours * p.restock());
                Sql.exec(tx, "UPDATE market_products SET stock=?, last_restock=last_restock+? WHERE id=?", ns, hours * 3_600_000L, p.id());
                return Sql.one(tx, "SELECT * FROM market_products WHERE id=?", MarketModels::product, p.id());
            }
        }
        return p;
    }

    private Market loadMarket(Tx tx, String id, boolean bypass) throws SQLException {
        Market m = Sql.one(tx, "SELECT * FROM markets WHERE id=?", MarketModels::market, id);
        if (m == null || !m.enabled()) throw new EcoException("market-closed");
        if (!bypass && !isOpen(m)) throw new EcoException("market-closed-hours", minToStr(m.openMin()), minToStr(m.closeMin()));
        return m;
    }

    // ---------- trading ----------
    public CompletableFuture<BuyResult> buy(Player p, long productId, int qty) {
        UUID id = p.getUniqueId();
        boolean bypass = p.hasPermission("economycore.market.admin");
        UUID taxSub = Taxes.subject(p);
        return pl.db().tx(tx -> {
            if (qty <= 0 || qty > 100_000) throw new EcoException("invalid-amount");
            Product pr = Sql.one(tx, "SELECT * FROM market_products WHERE id=?", MarketModels::product, productId);
            if (pr == null) throw new EcoException("product-gone");
            Market m = loadMarket(tx, pr.market(), bypass);
            if (!pr.active() || (!pr.inRotation() && !bypass)) throw new EcoException("product-unavailable");
            if (pr.buy() == null) throw new EcoException("not-for-sale");
            pr = applyRestock(tx, pr);
            if (pr.stock() >= 0 && pr.stock() < qty) throw new EcoException("out-of-stock");
            if (pr.limit() > 0) {
                long now = System.currentTimeMillis();
                long win = Math.max(1, pr.limitHours()) * 3_600_000L;
                long[] row = Sql.one(tx, "SELECT window_start,count FROM market_limits WHERE product_id=? AND player=?", rs -> new long[]{rs.getLong(1), rs.getLong(2)}, pr.id(), id);
                long ws = now, cnt = 0;
                if (row != null && now - row[0] < win) { ws = row[0]; cnt = row[1]; }
                if (cnt + qty > pr.limit()) throw new EcoException("limit-reached", pr.limit(), pr.limitHours());
                Sql.exec(tx, "INSERT INTO market_limits(product_id,player,window_start,count) VALUES(?,?,?,?) ON CONFLICT(product_id,player) DO UPDATE SET window_start=excluded.window_start,count=excluded.count", pr.id(), id, ws, cnt + qty);
            }
            String cur = m.currency();
            BigDecimal unit = pr.effectiveBuy(m.discountPct());
            BigDecimal total = Money.scale(cur, unit.multiply(BigDecimal.valueOf(qty)));
            BigDecimal tax = Money.scale(cur, Money.pct(total, m.taxPct()).add(Taxes.compute(tx, "SALES", cur, total, taxSub)));
            Ledger.add(tx, Acct.player(id), cur, total.add(tax).negate());
            if (total.signum() > 0) Ledger.add(tx, Acct.SYSTEM, cur, total);
            if (tax.signum() > 0) Ledger.add(tx, Acct.TREASURY, cur, tax);
            Ledger.record(tx, "MARKET_BUY", Acct.player(id), Acct.SYSTEM, cur, total, BigDecimal.ZERO, tax, m.id() + ":" + pr.material() + "x" + qty);
            if (pr.stock() >= 0) Sql.exec(tx, "UPDATE market_products SET stock=stock-? WHERE id=?", qty, pr.id());
            Mailbox.add(tx, id, Inv.decodeOr(pr.itemData(), pr.material()), qty, "market:" + m.name());
            return new BuyResult(unit, total, tax, qty, cur);
        });
    }

    private SellResult sellTx(Tx tx, UUID id, UUID taxSub, boolean bypass, Product given, int qty) throws SQLException {
        Product pr = Sql.one(tx, "SELECT * FROM market_products WHERE id=?", MarketModels::product, given.id());
        if (pr == null) throw new EcoException("product-gone");
        Market m = loadMarket(tx, pr.market(), bypass);
        if (!pr.active()) throw new EcoException("product-unavailable");
        if (pr.sell() == null) throw new EcoException("not-buyable");
        pr = applyRestock(tx, pr);
        if (pr.maxStock() > 0 && pr.stock() >= 0 && pr.stock() + qty > pr.maxStock()) throw new EcoException("market-full");
        BigDecimal gross = Money.scale(m.currency(), pr.sell().multiply(BigDecimal.valueOf(qty)));
        Ledger.Settlement st = Ledger.settle(tx, Acct.SYSTEM, Acct.player(id), m.currency(), gross, m.commissionPct(), "INCOME", taxSub, "MARKET_SELL", m.id() + ":" + pr.material() + "x" + qty);
        if (pr.stock() >= 0) Sql.exec(tx, "UPDATE market_products SET stock=stock+? WHERE id=?", qty, pr.id());
        return new SellResult(gross, st.net(), st.commission().add(st.tax()), qty, m.currency());
    }

    public CompletableFuture<SellResult> sell(Player p, Product prod, int qty) {
        UUID id = p.getUniqueId();
        ItemStack tmpl = Inv.decodeOr(prod.itemData(), prod.material());
        if (qty <= 0 || !Inv.take(p, tmpl, qty)) return CompletableFuture.failedFuture(new EcoException("not-enough-items"));
        boolean bypass = p.hasPermission("economycore.market.admin");
        UUID taxSub = Taxes.subject(p);
        CompletableFuture<SellResult> f = pl.db().tx(tx -> sellTx(tx, id, taxSub, bypass, prod, qty));
        f.whenComplete((r, t) -> { if (t != null) refund(id, tmpl, qty); });
        return f;
    }

    public void refund(UUID id, ItemStack tmpl, int qty) {
        pl.db().tx(tx -> { Mailbox.add(tx, id, tmpl, qty, "refund"); return null; });
    }

    /** Sells plain items of the shop in bulk. Items are removed first (main thread); unsold lines are returned via mailbox atomically. */
    public CompletableFuture<SellAllResult> sellAll(Player p, List<Product> prods) {
        UUID id = p.getUniqueId();
        boolean bypass = p.hasPermission("economycore.market.admin");
        UUID taxSub = Taxes.subject(p);
        List<Product> taken = new ArrayList<>();
        List<Integer> qtys = new ArrayList<>();
        for (Product pr : prods) {
            if (pr.sell() == null || pr.itemData() != null) continue;
            Material mat = Material.matchMaterial(pr.material());
            if (mat == null) continue;
            ItemStack t = new ItemStack(mat);
            int c = Inv.count(p, t);
            if (c > 0 && Inv.take(p, t, c)) { taken.add(pr); qtys.add(c); }
        }
        if (taken.isEmpty()) return CompletableFuture.failedFuture(new EcoException("nothing-to-sell"));
        return pl.db().tx(tx -> {
            BigDecimal net = BigDecimal.ZERO;
            int sold = 0, returned = 0;
            String cur = pl.currencies().defId();
            for (int i = 0; i < taken.size(); i++) {
                Product pr = taken.get(i);
                int q = qtys.get(i);
                try {
                    SellResult r = sellTx(tx, id, taxSub, bypass, pr, q);
                    net = net.add(r.net());
                    cur = r.currency();
                    sold += q;
                } catch (EcoException e) {
                    Mailbox.add(tx, id, new ItemStack(Material.matchMaterial(pr.material())), q, "sellall-return");
                    returned += q;
                }
            }
            return new SellAllResult(net, sold, returned, cur);
        }).whenComplete((r, t) -> {
            if (t != null) for (int i = 0; i < taken.size(); i++) refund(id, new ItemStack(Material.matchMaterial(taken.get(i).material())), qtys.get(i));
        });
    }

    // ---------- admin ----------
    public CompletableFuture<Void> createMarket(String actor, String id, String name, String currency) {
        return pl.db().tx(tx -> {
            if (Sql.count(tx, "SELECT COUNT(*) FROM markets WHERE id=?", id) > 0) throw new EcoException("market-exists");
            Sql.exec(tx, "INSERT INTO markets(id,name,icon,currency) VALUES(?,?,?,?)", id, name, "CHEST", currency);
            Audit.log(tx, actor, "market.create", id, name);
            return null;
        });
    }

    public CompletableFuture<Void> deleteMarket(String actor, String id) {
        return pl.db().tx(tx -> {
            Market m = Sql.one(tx, "SELECT * FROM markets WHERE id=?", MarketModels::market, id);
            if (m == null) return null;
            if (m.isShop()) throw new EcoException("shop-undeletable");
            Sql.exec(tx, "DELETE FROM market_limits WHERE product_id IN (SELECT id FROM market_products WHERE market=?)", id);
            Sql.exec(tx, "DELETE FROM market_products WHERE market=?", id);
            Sql.exec(tx, "DELETE FROM market_categories WHERE market=?", id);
            Sql.exec(tx, "DELETE FROM markets WHERE id=?", id);
            Audit.log(tx, actor, "market.delete", id, "");
            return null;
        });
    }

    public CompletableFuture<Void> createCategory(String market, String id, String name, String icon) {
        return pl.db().tx(tx -> {
            if (Sql.count(tx, "SELECT COUNT(*) FROM market_categories WHERE market=? AND id=?", market, id) > 0) throw new EcoException("category-exists");
            Sql.exec(tx, "INSERT INTO market_categories(market,id,name,icon,sort) VALUES(?,?,?,?,?)", market, id, name, icon, (int) Sql.count(tx, "SELECT COUNT(*) FROM market_categories WHERE market=?", market));
            return null;
        });
    }

    public CompletableFuture<Void> deleteCategory(String market, String id) {
        return pl.db().tx(tx -> {
            Sql.exec(tx, "UPDATE market_products SET category='' WHERE market=? AND category=?", market, id);
            Sql.exec(tx, "DELETE FROM market_categories WHERE market=? AND id=?", market, id);
            return null;
        });
    }

    public CompletableFuture<Long> addProduct(String actor, String market, String category, ItemStack item, BigDecimal buy, BigDecimal sell) {
        boolean plain = !item.hasItemMeta();
        String data = plain ? null : Inv.encode(item);
        return pl.db().tx(tx -> {
            long id = Sql.insert(tx, "INSERT INTO market_products(market,category,material,item_data,buy_price,sell_price,last_restock) VALUES(?,?,?,?,?,?,?)",
                    market, category == null ? "" : category, item.getType().name(), data, buy, sell, System.currentTimeMillis());
            Audit.log(tx, actor, "market.product.add", market, item.getType().name());
            return id;
        });
    }

    public CompletableFuture<Void> deleteProduct(String actor, long id) {
        return pl.db().tx(tx -> {
            Sql.exec(tx, "DELETE FROM market_limits WHERE product_id=?", id);
            Sql.exec(tx, "DELETE FROM market_products WHERE id=?", id);
            Audit.log(tx, actor, "market.product.delete", String.valueOf(id), "");
            return null;
        });
    }

    /** Applies rotations whose interval elapsed. Runs periodically. */
    public void rotate() {
        pl.db().tx(tx -> {
            List<Market> ms = Sql.list(tx, "SELECT * FROM markets WHERE rot_enabled=1", MarketModels::market);
            long now = System.currentTimeMillis();
            for (Market m : ms) {
                if (now - m.lastRot() < m.rotIntervalMin() * 60_000L) continue;
                List<Long> ids = Sql.list(tx, "SELECT id FROM market_products WHERE market=? AND active=1", rs -> rs.getLong(1), m.id());
                Collections.shuffle(ids);
                Sql.exec(tx, "UPDATE market_products SET in_rotation=0 WHERE market=?", m.id());
                for (int i = 0; i < Math.min(m.rotSize(), ids.size()); i++) Sql.exec(tx, "UPDATE market_products SET in_rotation=1 WHERE id=?", ids.get(i));
                Sql.exec(tx, "UPDATE markets SET last_rot=? WHERE id=?", now, m.id());
            }
            return null;
        });
    }

    /** Seeds the default shop on first run. */
    public void seedShop() throws Exception {
        pl.db().tx(tx -> {
            if (Sql.count(tx, "SELECT COUNT(*) FROM markets WHERE is_shop=1") > 0) return null;
            String cur = pl.currencies().defId();
            Sql.exec(tx, "INSERT INTO markets(id,name,icon,currency,is_shop) VALUES('shop','Tienda','EMERALD',?,1)", cur);
            String[][] cats = {{"blocks", "Bloques", "GRASS_BLOCK"}, {"ores", "Minerales", "DIAMOND"}, {"food", "Comida", "BREAD"}, {"tools", "Herramientas", "IRON_PICKAXE"}, {"redstone", "Redstone", "REDSTONE"}, {"misc", "Varios", "ENDER_PEARL"}};
            int s = 0;
            for (String[] c : cats) Sql.exec(tx, "INSERT INTO market_categories(market,id,name,icon,sort) VALUES('shop',?,?,?,?)", c[0], c[1], c[2], s++);
            Object[][] items = {
                {"blocks", "COBBLESTONE", 6, 1}, {"blocks", "STONE", 8, 2}, {"blocks", "OAK_LOG", 12, 3}, {"blocks", "SAND", 8, 2}, {"blocks", "GLASS", 10, 3}, {"blocks", "DIRT", 3, 0.5},
                {"blocks", "BRICKS", 40, 8}, {"blocks", "OBSIDIAN", 120, 25}, {"blocks", "NETHERRACK", 4, 0.5},
                {"ores", "COAL", 15, 5}, {"ores", "IRON_INGOT", 40, 15}, {"ores", "GOLD_INGOT", 80, 30}, {"ores", "DIAMOND", 400, 150}, {"ores", "EMERALD", 300, 100},
                {"ores", "LAPIS_LAZULI", 20, 6}, {"ores", "COPPER_INGOT", 12, 4}, {"ores", "NETHERITE_INGOT", 4000, 1500},
                {"food", "BREAD", 12, 3}, {"food", "COOKED_BEEF", 20, 6}, {"food", "APPLE", 8, 2}, {"food", "CARROT", 5, 1}, {"food", "WHEAT", 4, 1}, {"food", "GOLDEN_CARROT", 40, 10},
                {"tools", "IRON_PICKAXE", 200, 50}, {"tools", "IRON_SWORD", 160, 40}, {"tools", "DIAMOND_PICKAXE", 900, 200}, {"tools", "BOW", 90, 20}, {"tools", "ARROW", 4, 1}, {"tools", "SHIELD", 110, 25},
                {"redstone", "REDSTONE", 10, 3}, {"redstone", "PISTON", 60, 15}, {"redstone", "HOPPER", 100, 25}, {"redstone", "REPEATER", 25, 6}, {"redstone", "OBSERVER", 45, 10},
                {"misc", "ENDER_PEARL", 60, 15}, {"misc", "BLAZE_ROD", 50, 12}, {"misc", "SLIME_BALL", 25, 6}, {"misc", "BONE", 8, 2}, {"misc", "STRING", 6, 1.5}, {"misc", "GUNPOWDER", 20, 5}
            };
            long now = System.currentTimeMillis();
            for (Object[] it : items) {
                if (Material.matchMaterial((String) it[1]) == null) continue;
                Sql.exec(tx, "INSERT INTO market_products(market,category,material,buy_price,sell_price,last_restock) VALUES('shop',?,?,?,?,?)",
                        it[0], it[1], new BigDecimal(String.valueOf(it[2])), new BigDecimal(String.valueOf(it[3])), now);
            }
            return null;
        }).get();
    }
}

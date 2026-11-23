package com.economycore.bazaar;

import com.economycore.core.Acct;
import com.economycore.core.Audit;
import com.economycore.core.EcoException;
import com.economycore.core.EconomyCore;
import com.economycore.core.Inv;
import com.economycore.core.Mailbox;
import com.economycore.core.Money;
import com.economycore.core.Msg;
import com.economycore.core.OrderBook;
import com.economycore.core.OrderBook.Level;
import com.economycore.core.OrderBook.Order;
import com.economycore.core.OrderBook.PlaceResult;
import com.economycore.core.Sql;
import com.economycore.core.Tx;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public final class BazaarService {
    private final EconomyCore pl;
    private final Map<String, String> currencyOf = new ConcurrentHashMap<>();
    private final OrderBook book;

    public record Product(String id, String name, String material, String category, String currency, boolean active) {}
    public record Info(Product product, BigDecimal bestAsk, BigDecimal bestBid, BigDecimal last) {}
    public record Book(List<Level> asks, List<Level> bids, BigDecimal last) {}

    public BazaarService(EconomyCore pl) {
        this.pl = pl;
        this.book = new OrderBook("bazaar_orders", Acct.escrow("BAZAAR"), new OrderBook.Assets() {
            @Override public void reserveSeller(Tx tx, UUID owner, String product, int qty) {}

            @Override public void deliver(Tx tx, UUID buyer, String product, int qty, BigDecimal price) throws SQLException {
                Mailbox.add(tx, buyer, new ItemStack(mat(product)), qty, "bazaar");
            }

            @Override public void refund(Tx tx, UUID owner, String product, int qty) throws SQLException {
                Mailbox.add(tx, owner, new ItemStack(mat(product)), qty, "bazaar-return");
            }

            @Override public void onFill(Tx tx, String product, UUID buyer, UUID seller, BigDecimal price, int qty) throws SQLException {
                Sql.exec(tx, "INSERT INTO bazaar_trades(ts,product,buyer,seller,price,qty) VALUES(?,?,?,?,?,?)", System.currentTimeMillis(), product, buyer, seller, price, qty);
                String cur = cur(product);
                tx.afterCommit(() -> {
                    Player b = Bukkit.getPlayer(buyer);
                    Player s = Bukkit.getPlayer(seller);
                    if (b != null) Msg.send(b, "bazaar-fill-buy", qty, Inv.pretty(mat(product)), Money.fmt(cur, price));
                    if (s != null) Msg.send(s, "bazaar-fill-sell", qty, Inv.pretty(mat(product)), Money.fmt(cur, price));
                });
            }
        }, this::cur, () -> Money.dec(pl.getConfig().getString("bazaar.commission-percent", "1.25"), BigDecimal.ZERO),
                () -> pl.getConfig().getInt("bazaar.max-orders", 28), "BAZAAR");
    }

    private static Material mat(String product) {
        Material m = Material.matchMaterial(product);
        return m == null ? Material.BARRIER : m;
    }

    private String cur(String product) {
        String c = currencyOf.get(product);
        return c == null ? pl.currencies().defId() : c;
    }

    public void loadCache() throws Exception {
        for (Product p : products().get()) currencyOf.put(p.id(), p.currency());
    }

    public OrderBook book() { return book; }

    public CompletableFuture<List<Product>> products() {
        return pl.db().tx(tx -> Sql.list(tx, "SELECT * FROM bazaar_products WHERE active=1 ORDER BY name",
                rs -> new Product(rs.getString("id"), rs.getString("name"), rs.getString("material"), rs.getString("category"), rs.getString("currency"), rs.getInt("active") == 1)));
    }

    public CompletableFuture<List<Info>> infos() {
        return pl.db().tx(tx -> {
            List<Info> out = new ArrayList<>();
            List<Product> ps = Sql.list(tx, "SELECT * FROM bazaar_products WHERE active=1 ORDER BY name",
                    rs -> new Product(rs.getString("id"), rs.getString("name"), rs.getString("material"), rs.getString("category"), rs.getString("currency"), true));
            for (Product p : ps) {
                List<Level> a = book.levels(tx, p.id(), "SELL", 1);
                List<Level> b = book.levels(tx, p.id(), "BUY", 1);
                out.add(new Info(p, a.isEmpty() ? null : a.get(0).price(), b.isEmpty() ? null : b.get(0).price(), last(tx, p.id())));
            }
            return out;
        });
    }

    private BigDecimal last(Tx tx, String product) throws SQLException {
        return Sql.one(tx, "SELECT price FROM bazaar_trades WHERE product=? ORDER BY id DESC LIMIT 1", rs -> new BigDecimal(rs.getString(1)), product);
    }

    public CompletableFuture<Book> bookOf(String product) {
        return pl.db().tx(tx -> new Book(book.levels(tx, product, "SELL", 4), book.levels(tx, product, "BUY", 4), last(tx, product)));
    }

    public CompletableFuture<Void> addProduct(String actor, Material m, String currency) {
        String id = m.name();
        return pl.db().tx(tx -> {
            if (Sql.count(tx, "SELECT COUNT(*) FROM bazaar_products WHERE id=?", id) > 0) {
                Sql.exec(tx, "UPDATE bazaar_products SET active=1, currency=? WHERE id=?", currency, id);
            } else {
                Sql.exec(tx, "INSERT INTO bazaar_products(id,name,material,category,currency,active) VALUES(?,?,?,?,?,1)", id, Inv.pretty(m), id, "misc", currency);
            }
            Audit.log(tx, actor, "bazaar.product.add", id, currency);
            return null;
        }).thenAccept(v -> currencyOf.put(id, currency));
    }

    public CompletableFuture<Void> removeProduct(String actor, String id) {
        return pl.db().tx(tx -> {
            if (Sql.count(tx, "SELECT COUNT(*) FROM bazaar_orders WHERE product=? AND state='OPEN'", id) > 0) throw new EcoException("bazaar-open-orders");
            Sql.exec(tx, "UPDATE bazaar_products SET active=0 WHERE id=?", id);
            Audit.log(tx, actor, "bazaar.product.remove", id, "");
            return null;
        });
    }

    private void checkProduct(Tx tx, String product) throws SQLException {
        if (Sql.count(tx, "SELECT COUNT(*) FROM bazaar_products WHERE id=? AND active=1", product) == 0) throw new EcoException("bazaar-no-product");
    }

    /** side BUY: limit buy order; SELL: items are removed from the player first and returned if anything fails. */
    public CompletableFuture<PlaceResult> place(Player p, String product, String side, BigDecimal price, int amount) {
        UUID id = p.getUniqueId();
        String name = p.getName();
        boolean sell = side.equals("SELL");
        ItemStack tmpl = new ItemStack(mat(product));
        if (sell && (amount <= 0 || !Inv.take(p, tmpl, amount))) return CompletableFuture.failedFuture(new EcoException("not-enough-items"));
        CompletableFuture<PlaceResult> f = pl.db().tx(tx -> {
            checkProduct(tx, product);
            return book.place(tx, id, name, product, side, price, amount, price == null);
        });
        if (sell) f.whenComplete((r, t) -> { if (t != null) pl.db().tx(tx -> { Mailbox.add(tx, id, tmpl, amount, "bazaar-return"); return null; }); });
        return f;
    }

    public CompletableFuture<Void> cancel(Player p, long orderId, boolean admin) {
        UUID id = p.getUniqueId();
        return pl.db().tx(tx -> { book.cancel(tx, orderId, id, admin); return null; });
    }

    public CompletableFuture<List<Order>> myOrders(UUID id) {
        return pl.db().tx(tx -> book.orders(tx, id));
    }

    public String currencyOf(String product) { return cur(product); }

    /** Seeds a few common products on first run. */
    public void seed() throws Exception {
        pl.db().tx(tx -> {
            if (Sql.count(tx, "SELECT COUNT(*) FROM bazaar_products") > 0) return null;
            String cur = pl.currencies().defId();
            for (String n : new String[]{"COBBLESTONE", "COAL", "IRON_INGOT", "GOLD_INGOT", "DIAMOND", "EMERALD", "REDSTONE", "LAPIS_LAZULI", "OAK_LOG", "WHEAT", "CARROT", "POTATO", "BONE", "STRING", "GUNPOWDER", "ENDER_PEARL", "BLAZE_ROD", "COPPER_INGOT", "QUARTZ", "SUGAR_CANE"}) {
                Material m = Material.matchMaterial(n);
                if (m == null) continue;
                Sql.exec(tx, "INSERT INTO bazaar_products(id,name,material,category,currency,active) VALUES(?,?,?,?,?,1)", m.name(), Inv.pretty(m), m.name(), "misc", cur);
            }
            return null;
        }).get();
    }
}

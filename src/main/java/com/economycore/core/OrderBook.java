package com.economycore.core;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Generic price-time priority order book used by the Bazaar (items) and the stock exchange (shares).
 * Buy orders reserve money in escrow; sell orders own their assets. Matching executes at the resting order's price,
 * supports partial fills and runs entirely inside one database transaction.
 */
public final class OrderBook {
    public interface Assets {
        /** Called before a SELL is matched/placed: move the seller's assets into the order (may throw). */
        void reserveSeller(Tx tx, UUID owner, String product, int qty) throws SQLException;

        void deliver(Tx tx, UUID buyer, String product, int qty, BigDecimal price) throws SQLException;

        void refund(Tx tx, UUID owner, String product, int qty) throws SQLException;

        void onFill(Tx tx, String product, UUID buyer, UUID seller, BigDecimal price, int qty) throws SQLException;
    }

    public record Order(long id, String product, String owner, String ownerName, String side, BigDecimal price, int amount, int filled, String state, long created) {
        public int remaining() { return amount - filled; }
    }

    public record Level(BigDecimal price, int qty) {}

    public record PlaceResult(long orderId, int filled, int remaining, BigDecimal money) {}

    private final String table;
    private final Acct escrow;
    private final Assets assets;
    private final Function<String, String> currency;
    private final Supplier<BigDecimal> commission;
    private final Supplier<Integer> maxOrders;
    private final String type;

    public OrderBook(String table, Acct escrow, Assets assets, Function<String, String> currency, Supplier<BigDecimal> commission, Supplier<Integer> maxOrders, String type) {
        this.table = table;
        this.escrow = escrow;
        this.assets = assets;
        this.currency = currency;
        this.commission = commission;
        this.maxOrders = maxOrders;
        this.type = type;
    }

    public static Order map(ResultSet rs) throws SQLException {
        return new Order(rs.getLong("id"), rs.getString("product"), rs.getString("owner"), rs.getString("owner_name"), rs.getString("side"),
                Sql.decOr0(rs, "price"), rs.getInt("amount"), rs.getInt("filled"), rs.getString("state"), rs.getLong("created"));
    }

    /** price == null means a market order (immediate-or-cancel at any price). */
    public PlaceResult place(Tx tx, UUID owner, String ownerName, String product, String side, BigDecimal price, int amount, boolean ioc) throws SQLException {
        if (amount <= 0 || amount > 10_000_000) throw new EcoException("invalid-amount");
        String cur = currency.apply(product);
        boolean buy = side.equals("BUY");
        if (price != null) {
            price = Money.scale(cur, price);
            if (price.signum() <= 0) throw new EcoException("invalid-amount");
        } else {
            ioc = true;
        }
        if (!buy) assets.reserveSeller(tx, owner, product, amount);
        int remaining = amount;
        BigDecimal money = BigDecimal.ZERO;
        BigDecimal comm = commission.get();
        long now = System.currentTimeMillis();
        List<Order> book = Sql.list(tx, "SELECT * FROM " + table + " WHERE product=? AND state='OPEN' AND side=? ORDER BY pnum " + (buy ? "ASC" : "DESC") + ", id ASC LIMIT 500",
                OrderBook::map, product, buy ? "SELL" : "BUY");
        for (Order r : book) {
            if (remaining <= 0) break;
            if (r.owner().equals(owner.toString())) continue;
            if (price != null) {
                int c = r.price().compareTo(price);
                if (buy ? c > 0 : c < 0) break;
            }
            int qty = Math.min(remaining, r.remaining());
            BigDecimal cost = Money.scale(cur, r.price().multiply(BigDecimal.valueOf(qty)));
            UUID resting = UUID.fromString(r.owner());
            if (buy) {
                Ledger.settle(tx, Acct.player(owner), Acct.player(resting), cur, cost, comm, "INCOME", resting, type, product + " x" + qty);
                assets.deliver(tx, owner, product, qty, r.price());
                money = money.add(cost);
                assets.onFill(tx, product, owner, resting, r.price(), qty);
            } else {
                Ledger.Settlement st = Ledger.settle(tx, escrow, Acct.player(owner), cur, cost, comm, "INCOME", owner, type, product + " x" + qty);
                assets.deliver(tx, resting, product, qty, r.price());
                money = money.add(st.net());
                assets.onFill(tx, product, resting, owner, r.price(), qty);
            }
            int nf = r.filled() + qty;
            Sql.exec(tx, "UPDATE " + table + " SET filled=?, state=?, updated=? WHERE id=?", nf, nf >= r.amount() ? "DONE" : "OPEN", now, r.id());
            remaining -= qty;
        }
        long id = 0;
        if (remaining > 0) {
            if (ioc) {
                if (!buy) assets.refund(tx, owner, product, remaining);
            } else {
                long open = Sql.count(tx, "SELECT COUNT(*) FROM " + table + " WHERE owner=? AND state='OPEN'", owner.toString());
                if (open >= maxOrders.get()) throw new EcoException("order-limit", maxOrders.get());
                if (buy) Ledger.transfer(tx, Acct.player(owner), escrow, cur, price.multiply(BigDecimal.valueOf(remaining)), "ORDER_RESERVE", product);
                id = Sql.insert(tx, "INSERT INTO " + table + "(product,owner,owner_name,side,price,pnum,amount,filled,state,created,updated) VALUES(?,?,?,?,?,?,?,?,'OPEN',?,?)",
                        product, owner, ownerName, side, price, price.doubleValue(), amount, amount - remaining, now, now);
            }
        }
        return new PlaceResult(id, amount - remaining, remaining, money);
    }

    public void cancel(Tx tx, long id, UUID actor, boolean admin) throws SQLException {
        Order o = Sql.one(tx, "SELECT * FROM " + table + " WHERE id=?", OrderBook::map, id);
        if (o == null || !o.state().equals("OPEN")) throw new EcoException("order-gone");
        if (!admin && !o.owner().equals(actor.toString())) throw new EcoException("no-permission");
        UUID owner = UUID.fromString(o.owner());
        int rem = o.remaining();
        if (o.side().equals("BUY")) {
            String cur = currency.apply(o.product());
            Ledger.transfer(tx, escrow, Acct.player(owner), cur, o.price().multiply(BigDecimal.valueOf(rem)), "ORDER_REFUND", o.product());
        } else {
            assets.refund(tx, owner, o.product(), rem);
        }
        Sql.exec(tx, "UPDATE " + table + " SET state='CANCELLED', updated=? WHERE id=?", System.currentTimeMillis(), id);
    }

    public List<Level> levels(Tx tx, String product, String side, int n) throws SQLException {
        return Sql.list(tx, "SELECT price, SUM(amount-filled) AS q FROM " + table + " WHERE product=? AND state='OPEN' AND side=? GROUP BY price ORDER BY pnum " + (side.equals("SELL") ? "ASC" : "DESC") + " LIMIT ?",
                rs -> new Level(Sql.decOr0(rs, "price"), rs.getInt("q")), product, side, n);
    }

    public List<Order> orders(Tx tx, UUID owner) throws SQLException {
        return Sql.list(tx, "SELECT * FROM " + table + " WHERE owner=? AND state='OPEN' ORDER BY id DESC", OrderBook::map, owner.toString());
    }

    public List<Order> allOpen(Tx tx, String product) throws SQLException {
        return Sql.list(tx, "SELECT * FROM " + table + " WHERE product=? AND state='OPEN' ORDER BY id DESC LIMIT 500", OrderBook::map, product);
    }
}

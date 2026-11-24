package com.economycore.stock;

import com.economycore.company.CompanyModels;
import com.economycore.company.CompanyModels.Company;
import com.economycore.company.CompanyModels.Member;
import com.economycore.company.CompanyService;
import com.economycore.core.Acct;
import com.economycore.core.Audit;
import com.economycore.core.EcoException;
import com.economycore.core.EconomyCore;
import com.economycore.core.Ledger;
import com.economycore.core.Money;
import com.economycore.core.Notify;
import com.economycore.core.Msg;
import com.economycore.core.OrderBook;
import com.economycore.core.OrderBook.Level;
import com.economycore.core.OrderBook.Order;
import com.economycore.core.OrderBook.PlaceResult;
import com.economycore.core.Sql;
import com.economycore.core.Tx;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public final class StockService {
    private final EconomyCore pl;
    private final CompanyService companies;
    private final OrderBook book;

    public record Row(Company c, BigDecimal changePct, long volume24, BigDecimal bid, BigDecimal ask) {}
    public record Detail(Company c, List<BigDecimal> history, List<Level> asks, List<Level> bids, long volume24, BigDecimal changePct, BigDecimal marketCap) {}
    public record Holding(String company, String name, String ticker, int shares, BigDecimal avgCost, BigDecimal price, String currency) {}
    public record IndexInfo(BigDecimal value, BigDecimal changePct, long volume24, List<BigDecimal> history) {}

    public StockService(EconomyCore pl, CompanyService companies) {
        this.pl = pl;
        this.companies = companies;
        this.book = new OrderBook("stock_orders", Acct.escrow("STOCKS"), new OrderBook.Assets() {
            @Override public void reserveSeller(Tx tx, UUID owner, String product, int qty) throws SQLException {
                int n = Sql.exec(tx, "UPDATE holdings SET shares=shares-? WHERE owner=? AND company=? AND shares>=?", qty, owner, product, qty);
                if (n == 0) throw new EcoException("not-enough-shares");
            }

            @Override public void deliver(Tx tx, UUID buyer, String product, int qty, BigDecimal price) throws SQLException { addHolding(tx, buyer.toString(), product, qty, price); }

            @Override public void refund(Tx tx, UUID owner, String product, int qty) throws SQLException { addHolding(tx, owner.toString(), product, qty, null); }

            @Override public void onFill(Tx tx, String product, UUID buyer, UUID seller, BigDecimal price, int qty) throws SQLException {
                Sql.exec(tx, "UPDATE companies SET prev_price=price, price=? WHERE id=?", price, product);
                Sql.exec(tx, "INSERT INTO stock_trades(ts,company,buyer,seller,price,qty) VALUES(?,?,?,?,?,?)", System.currentTimeMillis(), product, buyer, seller, price, qty);
                String cur = companies.cur(product);
                Notify.to(tx, buyer, "stock-fill-buy", qty, product.toUpperCase(), Money.fmt(cur, price));
                Notify.to(tx, seller, "stock-fill-sell", qty, product.toUpperCase(), Money.fmt(cur, price));
            }
        }, companies::cur, () -> Money.dec(pl.getConfig().getString("stocks.commission-percent", "0.5"), BigDecimal.ZERO), () -> pl.getConfig().getInt("stocks.max-orders", 20), "STOCK");
    }

    public OrderBook book() { return book; }

    public String currencyFor(String company) { return companies.cur(company); }

    static void addHolding(Tx tx, String owner, String company, int qty, BigDecimal price) throws SQLException {
        String[] cur = Sql.one(tx, "SELECT shares, avg_cost FROM holdings WHERE owner=? AND company=?", rs -> new String[]{rs.getString(1), rs.getString(2)}, owner, company);
        if (cur == null) {
            Sql.exec(tx, "INSERT INTO holdings(owner,company,shares,avg_cost) VALUES(?,?,?,?)", owner, company, qty, price == null ? BigDecimal.ZERO : price);
            return;
        }
        int sh = Integer.parseInt(cur[0]);
        BigDecimal avg = Money.dec(cur[1], BigDecimal.ZERO);
        BigDecimal nAvg = avg;
        if (price != null && sh + qty > 0) nAvg = avg.multiply(BigDecimal.valueOf(sh)).add(price.multiply(BigDecimal.valueOf(qty))).divide(BigDecimal.valueOf(sh + qty), 8, RoundingMode.HALF_UP);
        Sql.exec(tx, "UPDATE holdings SET shares=shares+?, avg_cost=? WHERE owner=? AND company=?", qty, nAvg, owner, company);
    }

    // ---------- reads ----------
    private BigDecimal change24(Tx tx, Company c) throws SQLException {
        BigDecimal old = Sql.one(tx, "SELECT price FROM stock_history WHERE company=? AND ts<=? ORDER BY ts DESC LIMIT 1", rs -> new BigDecimal(rs.getString(1)), c.id(), System.currentTimeMillis() - 86_400_000L);
        if (old == null) old = c.prevPrice();
        if (old.signum() <= 0) return BigDecimal.ZERO;
        return c.price().subtract(old).multiply(BigDecimal.valueOf(100)).divide(old, 2, RoundingMode.HALF_UP);
    }

    private long volume24(Tx tx, String company) throws SQLException {
        Long v = Sql.one(tx, "SELECT SUM(qty) FROM stock_trades WHERE company=? AND ts>?", rs -> rs.getLong(1), company, System.currentTimeMillis() - 86_400_000L);
        return v == null ? 0 : v;
    }

    public CompletableFuture<List<Row>> listed() {
        return pl.db().tx(tx -> {
            List<Row> out = new ArrayList<>();
            for (Company c : Sql.list(tx, "SELECT * FROM companies WHERE listed=1 AND active=1 ORDER BY ticker", CompanyModels::company)) {
                List<Level> a = book.levels(tx, c.id(), "SELL", 1), b = book.levels(tx, c.id(), "BUY", 1);
                out.add(new Row(c, change24(tx, c), volume24(tx, c.id()), b.isEmpty() ? null : b.get(0).price(), a.isEmpty() ? null : a.get(0).price()));
            }
            return out;
        });
    }

    public CompletableFuture<Detail> detail(String id) {
        return pl.db().tx(tx -> {
            Company c = Sql.one(tx, "SELECT * FROM companies WHERE id=?", CompanyModels::company, id);
            if (c == null) throw new EcoException("company-gone");
            List<BigDecimal> h = Sql.list(tx, "SELECT price FROM stock_history WHERE company=? ORDER BY ts DESC LIMIT 24", rs -> new BigDecimal(rs.getString(1)), id);
            Collections.reverse(h);
            h.add(c.price());
            return new Detail(c, h, book.levels(tx, id, "SELL", 4), book.levels(tx, id, "BUY", 4), volume24(tx, id), change24(tx, c), c.price().multiply(BigDecimal.valueOf(c.totalShares())));
        });
    }

    public CompletableFuture<List<Holding>> portfolio(UUID u) {
        return pl.db().tx(tx -> Sql.list(tx, "SELECT h.company, c.name, c.ticker, h.shares, h.avg_cost, c.price, c.currency FROM holdings h JOIN companies c ON c.id=h.company WHERE h.owner=? AND h.shares>0 ORDER BY c.ticker",
                rs -> new Holding(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), Money.dec(rs.getString(5), BigDecimal.ZERO), Money.dec(rs.getString(6), BigDecimal.ZERO), rs.getString(7)), u.toString()));
    }

    public CompletableFuture<List<Order>> myOrders(UUID u) { return pl.db().tx(tx -> book.orders(tx, u)); }

    public CompletableFuture<Void> cancel(Player p, long id, boolean admin) {
        UUID u = p.getUniqueId();
        return pl.db().tx(tx -> { book.cancel(tx, id, u, admin); return null; });
    }

    public CompletableFuture<PlaceResult> place(Player p, String company, String side, BigDecimal price, int qty) {
        UUID u = p.getUniqueId();
        String name = p.getName();
        return pl.db().tx(tx -> {
            Company c = Sql.one(tx, "SELECT * FROM companies WHERE id=?", CompanyModels::company, company);
            if (c == null || !c.active() || !c.listed()) throw new EcoException("stock-not-listed");
            return book.place(tx, u, name, company, side, price, qty, price == null);
        });
    }

    // ---------- IPO ----------
    public CompletableFuture<BigDecimal> buyIpo(Player p, String company, int qty) {
        UUID u = p.getUniqueId();
        return pl.db().tx(tx -> {
            Company c = Sql.one(tx, "SELECT * FROM companies WHERE id=?", CompanyModels::company, company);
            if (c == null || !c.listed() || c.ipoRemaining() < qty || qty <= 0) throw new EcoException("ipo-unavailable");
            BigDecimal cost = Money.scale(c.currency(), c.ipoPrice().multiply(BigDecimal.valueOf(qty)));
            Ledger.transfer(tx, Acct.player(u), Acct.company(company), c.currency(), cost, "IPO", c.ticker() + " x" + qty);
            addHolding(tx, u.toString(), company, qty, c.ipoPrice());
            Sql.exec(tx, "UPDATE companies SET ipo_remaining=ipo_remaining-? WHERE id=?", qty, company);
            Sql.exec(tx, "INSERT INTO stock_trades(ts,company,buyer,seller,price,qty) VALUES(?,?,?,?,?,?)", System.currentTimeMillis(), company, u, "IPO", c.ipoPrice(), qty);
            Sql.exec(tx, "INSERT INTO company_ledger(company,ts,kind,amount,note) VALUES(?,?,'INVEST',?,?)", company, System.currentTimeMillis(), cost, "IPO " + qty + " acciones");
            return cost;
        });
    }

    public CompletableFuture<Void> setIpo(Player p, String company, int shares, BigDecimal price, boolean admin) {
        UUID u = p.getUniqueId();
        return pl.db().tx(tx -> {
            Company c = Sql.one(tx, "SELECT * FROM companies WHERE id=?", CompanyModels::company, company);
            if (c == null || !c.active()) throw new EcoException("company-gone");
            Member m = Sql.one(tx, "SELECT * FROM company_members WHERE company=? AND uuid=?", CompanyModels::member, company, u.toString());
            boolean ceo = m != null && m.role().equals("CEO");
            if (!ceo && !admin) throw new EcoException("no-permission");
            if (shares <= 0 || price.signum() <= 0) throw new EcoException("invalid-amount");
            if (ceo && !c.ceo().isEmpty()) {
                int n = Sql.exec(tx, "UPDATE holdings SET shares=shares-? WHERE owner=? AND company=? AND shares>=?", shares, u, company, shares);
                if (n == 0) throw new EcoException("not-enough-shares");
            } else {
                Sql.exec(tx, "UPDATE companies SET total_shares=total_shares+? WHERE id=?", shares, company);
            }
            Sql.exec(tx, "UPDATE companies SET ipo_remaining=ipo_remaining+?, ipo_price=?, listed=1 WHERE id=?", shares, Money.scale(c.currency(), price), company);
            if (!admin) return null;
            Audit.log(tx, p.getName(), "stock.ipo", company, shares + "@" + price);
            return null;
        });
    }

    public CompletableFuture<Void> setListed(Player p, String company, boolean listed, boolean admin) {
        UUID u = p.getUniqueId();
        return pl.db().tx(tx -> {
            Company c = Sql.one(tx, "SELECT * FROM companies WHERE id=?", CompanyModels::company, company);
            if (c == null) throw new EcoException("company-gone");
            if (!admin && !c.ceo().equals(u.toString())) throw new EcoException("no-permission");
            Sql.exec(tx, "UPDATE companies SET listed=? WHERE id=?", listed, company);
            return null;
        });
    }

    // ---------- periodic ----------
    /** Supply/demand price discovery from the open order book. */
    public void tick() {
        pl.db().tx(tx -> {
            BigDecimal sens = Money.dec(pl.getConfig().getString("stocks.sensitivity", "0.02"), new BigDecimal("0.02"));
            BigDecimal maxStep = Money.dec(pl.getConfig().getString("stocks.max-step", "0.05"), new BigDecimal("0.05"));
            BigDecimal maxMult = Money.dec(pl.getConfig().getString("stocks.max-price-multiplier", "100"), BigDecimal.valueOf(100));
            for (Company c : Sql.list(tx, "SELECT * FROM companies WHERE listed=1 AND active=1", CompanyModels::company)) {
                Long d = Sql.one(tx, "SELECT SUM(amount-filled) FROM stock_orders WHERE product=? AND state='OPEN' AND side='BUY'", rs -> rs.getLong(1), c.id());
                Long s = Sql.one(tx, "SELECT SUM(amount-filled) FROM stock_orders WHERE product=? AND state='OPEN' AND side='SELL'", rs -> rs.getLong(1), c.id());
                double demand = d == null ? 0 : d, supply = s == null ? 0 : s;
                if (demand + supply <= 0) continue;
                double imb = (demand - supply) / (demand + supply + Math.max(1, c.totalShares() * 0.01));
                double drift = Math.max(-maxStep.doubleValue(), Math.min(maxStep.doubleValue(), imb * sens.doubleValue()));
                BigDecimal np = Money.scale(c.currency(), c.price().multiply(BigDecimal.valueOf(1 + drift)));
                List<Level> asks = book.levels(tx, c.id(), "SELL", 1), bids = book.levels(tx, c.id(), "BUY", 1);
                if (drift > 0 && !asks.isEmpty() && np.compareTo(asks.get(0).price()) > 0) np = asks.get(0).price();
                if (drift < 0 && !bids.isEmpty() && np.compareTo(bids.get(0).price()) < 0) np = bids.get(0).price();
                BigDecimal unit = BigDecimal.ONE.movePointLeft(pl.currencies().getOrDefault(c.currency()).decimals());
                if (np.compareTo(unit) < 0) np = unit;
                if (np.compareTo(c.ipoPrice().multiply(maxMult)) > 0) np = c.ipoPrice().multiply(maxMult);
                if (np.compareTo(c.price()) != 0) Sql.exec(tx, "UPDATE companies SET prev_price=price, price=? WHERE id=?", np, c.id());
            }
            return null;
        });
    }

    private static String meta(Tx tx, String k) throws SQLException {
        return Sql.one(tx, "SELECT value FROM meta WHERE key=?", rs -> rs.getString(1), k);
    }

    private static void setMeta(Tx tx, String k, String v) throws SQLException {
        Sql.exec(tx, "INSERT OR REPLACE INTO meta(key,value) VALUES(?,?)", k, v);
    }

    /** Stores a history point per company and recomputes the market index (cap-weighted, continuous when the listing set changes). */
    public void snapshot() {
        pl.db().tx(tx -> {
            long now = System.currentTimeMillis();
            long window = Math.max(1, pl.getConfig().getLong("stocks.snapshot-minutes", 10)) * 60_000L;
            BigDecimal cap = BigDecimal.ZERO;
            long vol = 0;
            List<String> ids = new ArrayList<>();
            for (Company c : Sql.list(tx, "SELECT * FROM companies WHERE listed=1 AND active=1 ORDER BY id", CompanyModels::company)) {
                Long v = Sql.one(tx, "SELECT SUM(qty) FROM stock_trades WHERE company=? AND ts>?", rs -> rs.getLong(1), c.id(), now - window);
                long cv = v == null ? 0 : v;
                Sql.exec(tx, "INSERT OR REPLACE INTO stock_history(company,ts,price,volume) VALUES(?,?,?,?)", c.id(), now, c.price(), cv);
                cap = cap.add(c.price().multiply(BigDecimal.valueOf(c.totalShares())));
                vol += cv;
                ids.add(c.id());
            }
            Sql.exec(tx, "DELETE FROM stock_history WHERE ts<?", now - 30L * 86_400_000L);
            if (ids.isEmpty() || cap.signum() <= 0) return null;
            String sig = String.join(",", ids);
            String divS = meta(tx, "stock_index_div"), lastS = meta(tx, "stock_index_last"), sigS = meta(tx, "stock_index_sig");
            BigDecimal last = Money.dec(lastS, BigDecimal.valueOf(1000));
            BigDecimal div = divS == null ? cap.divide(BigDecimal.valueOf(1000), 12, RoundingMode.HALF_UP) : Money.dec(divS, BigDecimal.ONE);
            if (divS != null && !sig.equals(sigS)) div = cap.divide(last, 12, RoundingMode.HALF_UP);
            BigDecimal idx = cap.divide(div, 4, RoundingMode.HALF_UP);
            setMeta(tx, "stock_index_div", div.toPlainString());
            setMeta(tx, "stock_index_sig", sig);
            setMeta(tx, "stock_index_last", idx.toPlainString());
            Sql.exec(tx, "INSERT OR REPLACE INTO stock_index(ts,value,volume) VALUES(?,?,?)", now, idx, vol);
            Sql.exec(tx, "DELETE FROM stock_index WHERE ts<?", now - 30L * 86_400_000L);
            return null;
        });
    }

    public CompletableFuture<IndexInfo> index() {
        return pl.db().tx(tx -> {
            List<BigDecimal> h = Sql.list(tx, "SELECT value FROM stock_index ORDER BY ts DESC LIMIT 24", rs -> new BigDecimal(rs.getString(1)));
            Collections.reverse(h);
            BigDecimal cur = Money.dec(meta(tx, "stock_index_last"), BigDecimal.valueOf(1000));
            BigDecimal old = Sql.one(tx, "SELECT value FROM stock_index WHERE ts<=? ORDER BY ts DESC LIMIT 1", rs -> new BigDecimal(rs.getString(1)), System.currentTimeMillis() - 86_400_000L);
            if (old == null && !h.isEmpty()) old = h.get(0);
            BigDecimal ch = old == null || old.signum() == 0 ? BigDecimal.ZERO : cur.subtract(old).multiply(BigDecimal.valueOf(100)).divide(old, 2, RoundingMode.HALF_UP);
            Long v = Sql.one(tx, "SELECT SUM(qty) FROM stock_trades WHERE ts>?", rs -> rs.getLong(1), System.currentTimeMillis() - 86_400_000L);
            return new IndexInfo(cur, ch, v == null ? 0 : v, h);
        });
    }
}

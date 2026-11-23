package com.economycore.company;

import com.economycore.company.CompanyModels.Company;
import com.economycore.company.CompanyModels.Member;
import com.economycore.company.CompanyModels.Product;
import com.economycore.core.Acct;
import com.economycore.core.Audit;
import com.economycore.core.EcoException;
import com.economycore.core.EconomyCore;
import com.economycore.core.Inv;
import com.economycore.core.Ledger;
import com.economycore.core.Mailbox;
import com.economycore.core.Money;
import com.economycore.core.Msg;
import com.economycore.core.Players;
import com.economycore.core.Sql;
import com.economycore.core.Tx;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public final class CompanyService {
    private final EconomyCore pl;
    public final Map<String, String> currencyOf = new ConcurrentHashMap<>();

    public CompanyService(EconomyCore pl) { this.pl = pl; }

    public String cur(String companyId) {
        String c = currencyOf.get(companyId);
        return c == null ? pl.currencies().defId() : c;
    }

    // ---------- queries ----------
    public CompletableFuture<List<Company>> list() {
        return pl.db().tx(tx -> Sql.list(tx, "SELECT * FROM companies WHERE active=1 ORDER BY name", CompanyModels::company));
    }

    public CompletableFuture<List<Company>> mine(UUID u) {
        return pl.db().tx(tx -> Sql.list(tx, "SELECT c.* FROM companies c JOIN company_members m ON m.company=c.id WHERE m.uuid=? AND c.active=1 ORDER BY c.name", CompanyModels::company, u.toString()));
    }

    public CompletableFuture<Company> get(String id) {
        return pl.db().tx(tx -> Sql.one(tx, "SELECT * FROM companies WHERE id=?", CompanyModels::company, id));
    }

    public CompletableFuture<List<Member>> members(String id) {
        return pl.db().tx(tx -> Sql.list(tx, "SELECT * FROM company_members WHERE company=? ORDER BY CASE role WHEN 'CEO' THEN 0 WHEN 'DIRECTOR' THEN 1 ELSE 2 END, name", CompanyModels::member, id));
    }

    public CompletableFuture<Member> member(String id, UUID u) {
        return pl.db().tx(tx -> Sql.one(tx, "SELECT * FROM company_members WHERE company=? AND uuid=?", CompanyModels::member, id, u.toString()));
    }

    public CompletableFuture<List<Product>> products(String id, boolean all) {
        return pl.db().tx(tx -> Sql.list(tx, "SELECT * FROM company_products WHERE company=?" + (all ? "" : " AND active=1") + " ORDER BY kind, name", CompanyModels::product, id));
    }

    public CompletableFuture<Product> product(long id) {
        return pl.db().tx(tx -> Sql.one(tx, "SELECT * FROM company_products WHERE id=?", CompanyModels::product, id));
    }

    public CompletableFuture<BigDecimal> balance(String id) {
        return pl.db().tx(tx -> Ledger.bal(tx, Acct.company(id), cur(id)));
    }

    public CompletableFuture<Map<String, BigDecimal>> report(String id, long since) {
        return pl.db().tx(tx -> {
            Map<String, BigDecimal> m = new LinkedHashMap<>();
            for (String[] r : Sql.list(tx, "SELECT kind, amount FROM company_ledger WHERE company=? AND ts>=?", rs -> new String[]{rs.getString(1), rs.getString(2)}, id, since)) {
                m.merge(r[0], new BigDecimal(r[1]), BigDecimal::add);
            }
            return m;
        });
    }

    public record LedgerRow(long ts, String kind, BigDecimal amount, String note) {}

    public CompletableFuture<List<LedgerRow>> ledger(String id) {
        return pl.db().tx(tx -> Sql.list(tx, "SELECT ts,kind,amount,note FROM company_ledger WHERE company=? ORDER BY id DESC LIMIT 300",
                rs -> new LedgerRow(rs.getLong(1), rs.getString(2), new BigDecimal(rs.getString(3)), rs.getString(4)), id));
    }

    static void book(Tx tx, String company, String kind, BigDecimal amount, String note) throws SQLException {
        Sql.exec(tx, "INSERT INTO company_ledger(company,ts,kind,amount,note) VALUES(?,?,?,?,?)", company, System.currentTimeMillis(), kind, amount, note);
    }

    // ---------- permissions ----------
    private Member need(Tx tx, String company, UUID actor, String flag, boolean admin) throws SQLException {
        Member m = Sql.one(tx, "SELECT * FROM company_members WHERE company=? AND uuid=?", CompanyModels::member, company, actor.toString());
        if (m == null) {
            if (admin) return new Member(company, actor.toString(), "admin", "CEO", BigDecimal.ZERO, "", 0);
            throw new EcoException("no-permission");
        }
        if (flag != null && !m.has(flag) && !admin) throw new EcoException("no-permission");
        return m;
    }

    private Company load(Tx tx, String id) throws SQLException {
        Company c = Sql.one(tx, "SELECT * FROM companies WHERE id=?", CompanyModels::company, id);
        if (c == null || !c.active()) throw new EcoException("company-gone");
        return c;
    }

    // ---------- lifecycle ----------
    public CompletableFuture<String> create(Player p, String name, String ticker) {
        UUID id = p.getUniqueId();
        String pname = p.getName();
        String t = ticker.toUpperCase();
        BigDecimal cost = Money.dec(pl.getConfig().getString("companies.creation-cost", "5000"), BigDecimal.ZERO);
        int shares = pl.getConfig().getInt("companies.initial-shares", 1000);
        BigDecimal price = Money.dec(pl.getConfig().getString("companies.initial-share-price", "10"), BigDecimal.TEN);
        int max = pl.getConfig().getInt("companies.max-per-player", 3);
        return pl.db().tx(tx -> {
            if (!t.matches("[A-Z]{2,5}")) throw new EcoException("company-bad-ticker");
            if (name.length() < 3 || name.length() > 32) throw new EcoException("company-bad-name");
            if (Sql.count(tx, "SELECT COUNT(*) FROM companies WHERE ceo=? AND active=1", id.toString()) >= max) throw new EcoException("company-limit", max);
            if (Sql.count(tx, "SELECT COUNT(*) FROM companies WHERE ticker=? OR name=? COLLATE NOCASE", t, name) > 0) throw new EcoException("company-exists");
            String cur = pl.currencies().defId();
            if (cost.signum() > 0) Ledger.transfer(tx, Acct.player(id), Acct.TREASURY, cur, cost, "COMPANY_CREATE", name);
            String cid = t.toLowerCase();
            Sql.exec(tx, "INSERT INTO companies(id,name,ticker,industry,description,kind,ceo,currency,total_shares,price,prev_price,created,icon) VALUES(?,?,?,?,?,'PLAYER',?,?,?,?,?,?,?)",
                    cid, name, t, "General", "", id, cur, shares, price, price, System.currentTimeMillis(), "GOLD_BLOCK");
            Sql.exec(tx, "INSERT INTO company_members(company,uuid,name,role,flags,hired) VALUES(?,?,?,'CEO',?,?)", cid, id, pname, CompanyModels.defaultFlags("CEO"), System.currentTimeMillis());
            Sql.exec(tx, "INSERT INTO holdings(owner,company,shares,avg_cost) VALUES(?,?,?,?)", id, cid, shares, price);
            tx.afterCommit(() -> currencyOf.put(cid, cur));
            return cid;
        });
    }

    /** Default (server-owned) companies from config, created once. Startup only. */
    public void seedDefaults() throws Exception {
        List<Map<?, ?>> defs = pl.getConfig().getMapList("companies.defaults");
        pl.db().tx(tx -> {
            for (Map<?, ?> d : defs) {
                String id = String.valueOf(d.get("id")).toLowerCase();
                if (Sql.count(tx, "SELECT COUNT(*) FROM companies WHERE id=?", id) > 0) continue;
                String cur = pl.currencies().defId();
                int shares = d.get("shares") == null ? 1000 : Integer.parseInt(String.valueOf(d.get("shares")));
                BigDecimal price = new BigDecimal(String.valueOf(d.get("price") == null ? "10" : d.get("price")));
                BigDecimal capital = new BigDecimal(String.valueOf(d.get("capital") == null ? "10000" : d.get("capital")));
                BigDecimal div = new BigDecimal(String.valueOf(d.get("auto-dividend-percent") == null ? "0" : d.get("auto-dividend-percent")));
                String icon = d.get("icon") == null ? "GOLD_BLOCK" : String.valueOf(d.get("icon"));
                Sql.exec(tx, "INSERT INTO companies(id,name,ticker,industry,description,kind,ceo,currency,total_shares,price,prev_price,listed,ipo_remaining,ipo_price,auto_div_pct,created,icon) VALUES(?,?,?,?,?,'DEFAULT','',?,?,?,?,1,?,?,?,?,?)",
                        id, String.valueOf(d.get("name")), String.valueOf(d.get("ticker")).toUpperCase(), String.valueOf(d.get("industry") == null ? "General" : d.get("industry")),
                        String.valueOf(d.get("description") == null ? "" : d.get("description")), cur, shares, price, price, shares, price, div, System.currentTimeMillis(), icon);
                if (capital.signum() > 0) {
                    Ledger.transfer(tx, Acct.SYSTEM, Acct.company(id), cur, capital, "COMPANY_CAPITAL", id);
                    book(tx, id, "CAPITAL", capital, "capital inicial");
                }
                Object prods = d.get("products");
                if (prods instanceof List<?> pl2) {
                    for (Object o : pl2) {
                        if (!(o instanceof Map<?, ?> pm)) continue;
                        Material m = Material.matchMaterial(String.valueOf(pm.get("material")));
                        if (m == null) continue;
                        int pa = pm.get("produce-amount") == null ? 0 : Integer.parseInt(String.valueOf(pm.get("produce-amount")));
                        int pi = pm.get("produce-interval-min") == null ? 0 : Integer.parseInt(String.valueOf(pm.get("produce-interval-min")));
                        int cap = pm.get("produce-cap") == null ? 0 : Integer.parseInt(String.valueOf(pm.get("produce-cap")));
                        int stock = pm.get("stock") == null ? 0 : Integer.parseInt(String.valueOf(pm.get("stock")));
                        Sql.exec(tx, "INSERT INTO company_products(company,kind,name,material,price,stock,description,produce_amount,produce_interval_min,produce_cap,last_produce) VALUES(?,'PRODUCT',?,?,?,?,?,?,?,?,?)",
                                id, Inv.pretty(m), m.name(), new BigDecimal(String.valueOf(pm.get("price"))), stock, "", pa, pi, cap, System.currentTimeMillis());
                    }
                }
            }
            return null;
        }).get();
        for (Company c : list().get()) currencyOf.put(c.id(), c.currency());
    }

    public CompletableFuture<Void> disband(Player p, String id, boolean admin) {
        UUID u = p.getUniqueId();
        return pl.db().tx(tx -> {
            Company c = load(tx, id);
            if (!admin && !c.ceo().equals(u.toString())) throw new EcoException("no-permission");
            if (c.isDefault() && !admin) throw new EcoException("no-permission");
            long outside = Sql.count(tx, "SELECT COUNT(*) FROM holdings WHERE company=? AND shares>0 AND owner<>?", id, c.ceo());
            long orders = Sql.count(tx, "SELECT COUNT(*) FROM stock_orders WHERE product=? AND state='OPEN'", id);
            if (!admin && (outside > 0 || orders > 0 || c.ipoRemaining() > 0 && c.listed() && c.ipoRemaining() < c.totalShares())) throw new EcoException("company-has-shareholders");
            BigDecimal bal = Ledger.bal(tx, Acct.company(id), c.currency());
            if (bal.signum() > 0) {
                Acct dest = c.ceo().isEmpty() ? Acct.TREASURY : Acct.player(c.ceo());
                Ledger.transfer(tx, Acct.company(id), dest, c.currency(), bal, "COMPANY_CLOSE", c.name());
            }
            Sql.exec(tx, "UPDATE companies SET active=0, listed=0 WHERE id=?", id);
            Sql.exec(tx, "DELETE FROM company_members WHERE company=?", id);
            Sql.exec(tx, "UPDATE company_products SET active=0 WHERE company=?", id);
            Audit.log(tx, p.getName(), "company.disband", id, c.name());
            return null;
        });
    }

    // ---------- money ----------
    public CompletableFuture<Void> deposit(Player p, String id, BigDecimal amt, boolean admin) {
        UUID u = p.getUniqueId();
        return pl.db().tx(tx -> {
            Company c = load(tx, id);
            need(tx, id, u, null, admin);
            Ledger.transfer(tx, Acct.player(u), Acct.company(id), c.currency(), amt, "COMPANY_DEPOSIT", c.name());
            book(tx, id, "DEPOSIT", Money.scale(c.currency(), amt), p.getName());
            return null;
        });
    }

    public CompletableFuture<Void> withdraw(Player p, String id, BigDecimal amt, boolean admin) {
        UUID u = p.getUniqueId();
        return pl.db().tx(tx -> {
            Company c = load(tx, id);
            need(tx, id, u, "FINANCE", admin);
            Ledger.transfer(tx, Acct.company(id), Acct.player(u), c.currency(), amt, "COMPANY_WITHDRAW", c.name());
            book(tx, id, "WITHDRAW", Money.scale(c.currency(), amt).negate(), p.getName());
            return null;
        });
    }

    // ---------- staff ----------
    public CompletableFuture<Void> hire(Player p, String id, String target, String role, BigDecimal salary, boolean admin) {
        UUID u = p.getUniqueId();
        return pl.db().tx(tx -> {
            Company c = load(tx, id);
            Member me = need(tx, id, u, "HIRE", admin);
            if (role.equals("DIRECTOR") && !me.role().equals("CEO") && !admin) throw new EcoException("no-permission");
            Players.Ref r = Players.require(tx, target);
            if (Sql.count(tx, "SELECT COUNT(*) FROM company_members WHERE company=? AND uuid=?", id, r.uuid().toString()) > 0) throw new EcoException("company-already-member");
            if (Sql.count(tx, "SELECT COUNT(*) FROM company_members WHERE company=?", id) >= pl.getConfig().getInt("companies.max-members", 50)) throw new EcoException("company-full");
            Sql.exec(tx, "INSERT INTO company_members(company,uuid,name,role,salary,flags,hired) VALUES(?,?,?,?,?,?,?)", id, r.uuid(), r.name(), role, Money.scale(c.currency(), salary), CompanyModels.defaultFlags(role), System.currentTimeMillis());
            tx.afterCommit(() -> { Player tp = Bukkit.getPlayer(r.uuid()); if (tp != null) Msg.send(tp, "company-hired", c.name(), role); });
            return null;
        });
    }

    public CompletableFuture<Void> fire(Player p, String id, String memberUuid, boolean admin) {
        UUID u = p.getUniqueId();
        return pl.db().tx(tx -> {
            Member me = need(tx, id, u, "FIRE", admin);
            Member t = Sql.one(tx, "SELECT * FROM company_members WHERE company=? AND uuid=?", CompanyModels::member, id, memberUuid);
            if (t == null) return null;
            if (t.role().equals("CEO")) throw new EcoException("company-ceo-fixed");
            if (t.role().equals("DIRECTOR") && !me.role().equals("CEO") && !admin) throw new EcoException("no-permission");
            Sql.exec(tx, "DELETE FROM company_members WHERE company=? AND uuid=?", id, memberUuid);
            return null;
        });
    }

    public CompletableFuture<Void> setSalary(Player p, String id, String memberUuid, BigDecimal salary, boolean admin) {
        UUID u = p.getUniqueId();
        return pl.db().tx(tx -> {
            Company c = load(tx, id);
            need(tx, id, u, "SALARY", admin);
            if (salary.signum() < 0) throw new EcoException("invalid-amount");
            Sql.exec(tx, "UPDATE company_members SET salary=? WHERE company=? AND uuid=?", Money.scale(c.currency(), salary), id, memberUuid);
            return null;
        });
    }

    public CompletableFuture<Void> setRole(Player p, String id, String memberUuid, String role, boolean admin) {
        UUID u = p.getUniqueId();
        return pl.db().tx(tx -> {
            Member me = need(tx, id, u, null, admin);
            if (!me.role().equals("CEO") && !admin) throw new EcoException("no-permission");
            Company c = load(tx, id);
            if (memberUuid.equals(c.ceo())) throw new EcoException("company-ceo-fixed");
            if (role.equals("CEO")) {
                if (!c.ceo().isEmpty()) {
                    Sql.exec(tx, "UPDATE company_members SET role='DIRECTOR', flags=? WHERE company=? AND uuid=?", CompanyModels.defaultFlags("DIRECTOR"), id, c.ceo());
                }
                Sql.exec(tx, "UPDATE company_members SET role='CEO', flags=? WHERE company=? AND uuid=?", CompanyModels.defaultFlags("CEO"), id, memberUuid);
                Sql.exec(tx, "UPDATE companies SET ceo=? WHERE id=?", memberUuid, id);
            } else {
                Sql.exec(tx, "UPDATE company_members SET role=?, flags=? WHERE company=? AND uuid=?", role, CompanyModels.defaultFlags(role), id, memberUuid);
            }
            return null;
        });
    }

    public CompletableFuture<Void> toggleFlag(Player p, String id, String memberUuid, String flag, boolean admin) {
        UUID u = p.getUniqueId();
        return pl.db().tx(tx -> {
            Member me = need(tx, id, u, null, admin);
            if (!me.role().equals("CEO") && !admin) throw new EcoException("no-permission");
            Member t = Sql.one(tx, "SELECT * FROM company_members WHERE company=? AND uuid=?", CompanyModels::member, id, memberUuid);
            if (t == null || t.role().equals("CEO")) return null;
            List<String> fl = new ArrayList<>();
            for (String f : t.flags().split(",")) if (!f.isBlank()) fl.add(f.trim());
            if (!fl.remove(flag)) fl.add(flag);
            Sql.exec(tx, "UPDATE company_members SET flags=? WHERE company=? AND uuid=?", String.join(",", fl), id, memberUuid);
            return null;
        });
    }

    // ---------- products ----------
    public CompletableFuture<Long> addProduct(Player p, String id, String kind, String name, ItemStack tmpl, int stock, BigDecimal price, String command, boolean admin) {
        UUID u = p.getUniqueId();
        String data = tmpl == null ? null : Inv.encode(tmpl);
        String mat = tmpl == null ? null : tmpl.getType().name();
        CompletableFuture<Long> f = pl.db().tx(tx -> {
            Company c = load(tx, id);
            need(tx, id, u, "PRODUCTS", admin);
            if (price.signum() <= 0) throw new EcoException("invalid-amount");
            if (command != null && !command.isEmpty() && !p.hasPermission("economycore.admin")) throw new EcoException("no-permission");
            return Sql.insert(tx, "INSERT INTO company_products(company,kind,name,item_data,material,price,stock,description,command,last_produce) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    id, kind, name, data, mat, Money.scale(c.currency(), price), kind.equals("SERVICE") ? -1 : stock, "", command, System.currentTimeMillis());
        });
        if (tmpl != null && stock > 0) f.whenComplete((r, t) -> { if (t != null) pl.db().tx(tx -> { Mailbox.add(tx, u, tmpl, stock, "company-return"); return null; }); });
        return f;
    }

    /** Items already removed from the inventory; stock increases, items return via mailbox on failure. */
    public CompletableFuture<Void> addStock(Player p, long productId, int qty, boolean admin) {
        UUID u = p.getUniqueId();
        return pl.db().tx(tx -> {
            Product pr = Sql.one(tx, "SELECT * FROM company_products WHERE id=?", CompanyModels::product, productId);
            if (pr == null) throw new EcoException("product-gone");
            need(tx, pr.company(), u, "PRODUCTS", admin);
            Sql.exec(tx, "UPDATE company_products SET stock=stock+? WHERE id=?", qty, productId);
            return null;
        });
    }

    public record BuyResult(BigDecimal total, String currency, String name) {}

    public CompletableFuture<BuyResult> buy(Player p, long productId, int qty) {
        UUID id = p.getUniqueId();
        String pname = p.getName();
        return pl.db().tx(tx -> {
            if (qty <= 0 || qty > 100000) throw new EcoException("invalid-amount");
            Product pr = Sql.one(tx, "SELECT * FROM company_products WHERE id=?", CompanyModels::product, productId);
            if (pr == null || !pr.active()) throw new EcoException("product-gone");
            Company c = load(tx, pr.company());
            boolean item = pr.kind().equals("PRODUCT");
            if (item && pr.stock() < qty) throw new EcoException("out-of-stock");
            BigDecimal total = Money.scale(c.currency(), pr.price().multiply(BigDecimal.valueOf(qty)));
            BigDecimal corp = Money.dec(pl.getConfig().getString("companies.corporate-tax-percent", "5"), BigDecimal.ZERO);
            Ledger.Settlement st = Ledger.settle(tx, Acct.player(id), Acct.company(c.id()), c.currency(), total, corp, null, null, "COMPANY_SALE", c.name() + ": " + pr.name() + " x" + qty);
            book(tx, c.id(), "SALE", st.net(), pr.name() + " x" + qty + " (" + pname + ")");
            if (st.tax().add(st.commission()).signum() > 0) book(tx, c.id(), "TAX", st.commission().add(st.tax()).negate(), "impuesto corporativo");
            if (item) {
                Sql.exec(tx, "UPDATE company_products SET stock=stock-? WHERE id=?", qty, pr.id());
                Mailbox.add(tx, id, Inv.decodeOr(pr.itemData(), pr.material()), qty, "company:" + c.name());
            } else if (pr.command() != null && !pr.command().isEmpty()) {
                tx.afterCommit(() -> {
                    for (int i = 0; i < qty; i++) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), pr.command().replace("%player%", pname));
                });
            }
            if (!c.ceo().isEmpty()) {
                UUID ceo = UUID.fromString(c.ceo());
                tx.afterCommit(() -> { Player cp = Bukkit.getPlayer(ceo); if (cp != null) Msg.send(cp, "company-sale", c.name(), pr.name(), qty, Money.fmt(c.currency(), st.net())); });
            }
            return new BuyResult(total, c.currency(), pr.name());
        });
    }

    // ---------- periodic jobs ----------
    public void produce() {
        pl.db().tx(tx -> {
            long now = System.currentTimeMillis();
            for (Product pr : Sql.list(tx, "SELECT * FROM company_products WHERE active=1 AND produce_interval_min>0 AND produce_amount>0", CompanyModels::product)) {
                long due = pr.produceInterval() * 60_000L;
                long cycles = (now - pr.lastProduce()) / due;
                if (cycles <= 0) continue;
                long ns = pr.stock() + cycles * pr.produceAmount();
                if (pr.produceCap() > 0) ns = Math.min(ns, Math.max(pr.produceCap(), pr.stock()));
                Sql.exec(tx, "UPDATE company_products SET stock=?, last_produce=last_produce+? WHERE id=?", (int) Math.min(ns, Integer.MAX_VALUE), cycles * due, pr.id());
            }
            return null;
        });
    }

    public void payroll() {
        pl.db().tx(tx -> {
            for (Company c : Sql.list(tx, "SELECT * FROM companies WHERE active=1", CompanyModels::company)) {
                List<Member> ms = Sql.list(tx, "SELECT * FROM company_members WHERE company=? AND CAST(salary AS REAL)>0", CompanyModels::member, c.id());
                if (ms.isEmpty()) continue;
                BigDecimal paid = BigDecimal.ZERO, unpaid = BigDecimal.ZERO;
                for (Member m : ms) {
                    if (Ledger.bal(tx, Acct.company(c.id()), c.currency()).compareTo(m.salary()) >= 0) {
                        UUID mu = UUID.fromString(m.uuid());
                        Ledger.settle(tx, Acct.company(c.id()), Acct.player(mu), c.currency(), m.salary(), BigDecimal.ZERO, "INCOME", mu, "SALARY", c.name());
                        book(tx, c.id(), "SALARY", m.salary().negate(), m.name());
                        paid = paid.add(m.salary());
                        tx.afterCommit(() -> { Player pp = Bukkit.getPlayer(mu); if (pp != null) Msg.send(pp, "company-salary", Money.fmt(c.currency(), m.salary()), c.name()); });
                    } else unpaid = unpaid.add(m.salary());
                }
                Sql.exec(tx, "INSERT INTO payroll_log(company,ts,paid,unpaid,employees) VALUES(?,?,?,?,?)", c.id(), System.currentTimeMillis(), paid, unpaid, ms.size());
                if (unpaid.signum() > 0 && !c.ceo().isEmpty()) {
                    UUID ceo = UUID.fromString(c.ceo());
                    tx.afterCommit(() -> { Player cp = Bukkit.getPlayer(ceo); if (cp != null) Msg.send(cp, "company-payroll-short", c.name()); });
                }
            }
            return null;
        });
    }

    public CompletableFuture<BigDecimal> dividend(Player p, String id, BigDecimal perShare, boolean admin) {
        UUID u = p.getUniqueId();
        return pl.db().tx(tx -> {
            Company c = load(tx, id);
            need(tx, id, u, "DIVIDENDS", admin);
            return dividendTx(tx, c, perShare);
        });
    }

    private BigDecimal dividendTx(Tx tx, Company c, BigDecimal perShare) throws SQLException {
        perShare = perShare.setScale(8, RoundingMode.DOWN);
        List<String[]> holders = Sql.list(tx, "SELECT owner, shares FROM holdings WHERE company=? AND shares>0", rs -> new String[]{rs.getString(1), rs.getString(2)}, c.id());
        BigDecimal total = BigDecimal.ZERO;
        for (String[] h : holders) total = total.add(Money.scale(c.currency(), perShare.multiply(new BigDecimal(h[1]))));
        if (total.signum() <= 0) throw new EcoException("dividend-zero");
        if (Ledger.bal(tx, Acct.company(c.id()), c.currency()).compareTo(total) < 0) throw new EcoException("company-insufficient");
        int n = 0;
        for (String[] h : holders) {
            BigDecimal amt = Money.scale(c.currency(), perShare.multiply(new BigDecimal(h[1])));
            if (amt.signum() <= 0) continue;
            UUID hu = UUID.fromString(h[0]);
            Ledger.settle(tx, Acct.company(c.id()), Acct.player(hu), c.currency(), amt, BigDecimal.ZERO, "INCOME", hu, "DIVIDEND", c.ticker());
            n++;
            tx.afterCommit(() -> { Player pp = Bukkit.getPlayer(hu); if (pp != null) Msg.send(pp, "dividend-received", Money.fmt(c.currency(), amt), c.ticker()); });
        }
        book(tx, c.id(), "DIVIDEND", total.negate(), "dividendo " + perShare.stripTrailingZeros().toPlainString() + "/acción");
        Sql.exec(tx, "INSERT INTO dividends(company,ts,per_share,total,holders) VALUES(?,?,?,?,?)", c.id(), System.currentTimeMillis(), perShare, total, n);
        return total;
    }

    public void autoDividends() {
        pl.db().tx(tx -> {
            for (Company c : Sql.list(tx, "SELECT * FROM companies WHERE active=1 AND CAST(auto_div_pct AS REAL)>0", CompanyModels::company)) {
                Long held = Sql.one(tx, "SELECT SUM(shares) FROM holdings WHERE company=?", rs -> rs.getLong(1), c.id());
                if (held == null || held <= 0) continue;
                BigDecimal pool = Money.pct(Ledger.bal(tx, Acct.company(c.id()), c.currency()), c.autoDivPct());
                BigDecimal per = pool.divide(BigDecimal.valueOf(held), pl.currencies().getOrDefault(c.currency()).decimals(), RoundingMode.DOWN);
                if (per.signum() <= 0) continue;
                try { dividendTx(tx, c, per); } catch (EcoException ignored) {}
            }
            return null;
        });
    }

    // ---------- extra management ----------
    public CompletableFuture<Void> setAutoDiv(Player p, String id, BigDecimal pct, boolean admin) {
        UUID u = p.getUniqueId();
        return pl.db().tx(tx -> {
            load(tx, id);
            need(tx, id, u, "DIVIDENDS", admin);
            if (pct.signum() < 0 || pct.compareTo(BigDecimal.valueOf(100)) > 0) throw new EcoException("invalid-amount");
            Sql.exec(tx, "UPDATE companies SET auto_div_pct=? WHERE id=?", pct, id);
            return null;
        });
    }

    public CompletableFuture<Void> setProductPrice(Player p, long productId, BigDecimal price, boolean admin) {
        UUID u = p.getUniqueId();
        return pl.db().tx(tx -> {
            Product pr = Sql.one(tx, "SELECT * FROM company_products WHERE id=?", CompanyModels::product, productId);
            if (pr == null) throw new EcoException("product-gone");
            Company c = load(tx, pr.company());
            need(tx, pr.company(), u, "PRODUCTS", admin);
            if (price.signum() <= 0) throw new EcoException("invalid-amount");
            Sql.exec(tx, "UPDATE company_products SET price=? WHERE id=?", Money.scale(c.currency(), price), productId);
            return null;
        });
    }

    public CompletableFuture<Void> removeProduct(Player p, long productId, boolean admin) {
        UUID u = p.getUniqueId();
        return pl.db().tx(tx -> {
            Product pr = Sql.one(tx, "SELECT * FROM company_products WHERE id=?", CompanyModels::product, productId);
            if (pr == null) throw new EcoException("product-gone");
            need(tx, pr.company(), u, "PRODUCTS", admin);
            if (pr.kind().equals("PRODUCT") && pr.stock() > 0) Mailbox.add(tx, u, Inv.decodeOr(pr.itemData(), pr.material()), pr.stock(), "company-return");
            Sql.exec(tx, "UPDATE company_products SET active=0, stock=0 WHERE id=?", productId);
            return null;
        });
    }

    /** Admin: creates a server-owned (default) company with initial capital from the system account. */
    public CompletableFuture<String> adminCreate(Player p, String name, String ticker, String industry, int shares, BigDecimal price, BigDecimal capital) {
        String t = ticker.toUpperCase();
        return pl.db().tx(tx -> {
            if (!t.matches("[A-Z]{2,5}")) throw new EcoException("company-bad-ticker");
            if (name.length() < 3 || name.length() > 32) throw new EcoException("company-bad-name");
            if (shares <= 0 || price.signum() <= 0 || capital.signum() < 0) throw new EcoException("invalid-amount");
            if (Sql.count(tx, "SELECT COUNT(*) FROM companies WHERE ticker=? OR name=? COLLATE NOCASE", t, name) > 0) throw new EcoException("company-exists");
            String cur = pl.currencies().defId();
            String cid = t.toLowerCase();
            Sql.exec(tx, "INSERT INTO companies(id,name,ticker,industry,description,kind,ceo,currency,total_shares,price,prev_price,listed,ipo_remaining,ipo_price,created,icon) VALUES(?,?,?,?,?,'DEFAULT','',?,?,?,?,1,?,?,?,?)",
                    cid, name, t, industry, "", cur, shares, price, price, shares, price, System.currentTimeMillis(), "GOLD_BLOCK");
            if (capital.signum() > 0) {
                Ledger.transfer(tx, Acct.SYSTEM, Acct.company(cid), cur, capital, "COMPANY_CAPITAL", cid);
                book(tx, cid, "CAPITAL", capital, "capital inicial");
            }
            Audit.log(tx, p.getName(), "company.admin-create", cid, name);
            tx.afterCommit(() -> currencyOf.put(cid, cur));
            return cid;
        });
    }

    /** Admin: assign the CEO of any company (the previous CEO becomes director). */
    public CompletableFuture<Void> setCeo(Player p, String id, String targetName) {
        return pl.db().tx(tx -> {
            Company c = load(tx, id);
            Players.Ref r = Players.require(tx, targetName);
            String nu = r.uuid().toString();
            if (!c.ceo().isEmpty() && !c.ceo().equals(nu)) Sql.exec(tx, "UPDATE company_members SET role='DIRECTOR', flags=? WHERE company=? AND uuid=?", CompanyModels.defaultFlags("DIRECTOR"), id, c.ceo());
            if (Sql.count(tx, "SELECT COUNT(*) FROM company_members WHERE company=? AND uuid=?", id, nu) == 0)
                Sql.exec(tx, "INSERT INTO company_members(company,uuid,name,role,flags,hired) VALUES(?,?,?,'CEO',?,?)", id, nu, r.name(), CompanyModels.defaultFlags("CEO"), System.currentTimeMillis());
            else Sql.exec(tx, "UPDATE company_members SET role='CEO', flags=? WHERE company=? AND uuid=?", CompanyModels.defaultFlags("CEO"), id, nu);
            Sql.exec(tx, "UPDATE companies SET ceo=? WHERE id=?", nu, id);
            Audit.log(tx, p.getName(), "company.set-ceo", id, r.name());
            return null;
        });
    }
}

package com.economycore.pshop;

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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public final class PShopService {
    private final EconomyCore pl;

    public PShopService(EconomyCore pl) { this.pl = pl; }

    public record Shop(long id, String owner, String ownerName, String name, String description, String icon, boolean open, String currency, long created, int items) {}
    public record Item(long id, long shopId, String itemData, String material, String itemName, BigDecimal price, int stock, int sold) {}

    static final String SHOP_SQL = "SELECT s.*, (SELECT COUNT(*) FROM pshop_items i WHERE i.shop_id=s.id AND i.stock>0) AS cnt FROM pshops s";

    static Shop shop(ResultSet rs) throws SQLException {
        return new Shop(rs.getLong("id"), rs.getString("owner"), rs.getString("owner_name"), rs.getString("name"), rs.getString("description"), rs.getString("icon"),
                rs.getInt("open") == 1, rs.getString("currency"), rs.getLong("created"), rs.getInt("cnt"));
    }

    static Item item(ResultSet rs) throws SQLException {
        return new Item(rs.getLong("id"), rs.getLong("shop_id"), rs.getString("item_data"), rs.getString("material"), rs.getString("item_name"), Sql.decOr0(rs, "price"), rs.getInt("stock"), rs.getInt("sold"));
    }

    public CompletableFuture<List<Shop>> openShops() {
        return pl.db().tx(tx -> Sql.list(tx, SHOP_SQL + " WHERE s.open=1 ORDER BY cnt DESC, s.name", PShopService::shop));
    }

    public CompletableFuture<List<Shop>> mine(UUID id) {
        return pl.db().tx(tx -> Sql.list(tx, SHOP_SQL + " WHERE s.owner=? OR s.id IN (SELECT shop_id FROM pshop_managers WHERE uuid=?) ORDER BY s.name", PShopService::shop, id.toString(), id.toString()));
    }

    public CompletableFuture<Shop> get(long id) {
        return pl.db().tx(tx -> Sql.one(tx, SHOP_SQL + " WHERE s.id=?", PShopService::shop, id));
    }

    public CompletableFuture<List<Item>> items(long shopId, boolean all) {
        return pl.db().tx(tx -> Sql.list(tx, "SELECT * FROM pshop_items WHERE shop_id=?" + (all ? "" : " AND stock>0") + " ORDER BY material", PShopService::item, shopId));
    }

    public CompletableFuture<Item> itemById(long id) {
        return pl.db().tx(tx -> Sql.one(tx, "SELECT * FROM pshop_items WHERE id=?", PShopService::item, id));
    }

    public CompletableFuture<List<String[]>> managers(long shopId) {
        return pl.db().tx(tx -> Sql.list(tx, "SELECT uuid,name FROM pshop_managers WHERE shop_id=?", rs -> new String[]{rs.getString(1), rs.getString(2)}, shopId));
    }

    public int maxShops(Player p) {
        int best = pl.getConfig().getInt("playershops.max-shops", 3);
        for (var pe : p.getEffectivePermissions()) {
            String n = pe.getPermission();
            if (pe.getValue() && n.startsWith("economycore.pshop.limit.")) {
                try { best = Math.max(best, Integer.parseInt(n.substring("economycore.pshop.limit.".length()))); } catch (NumberFormatException ignored) {}
            }
        }
        return best;
    }

    private boolean canManage(Tx tx, Shop s, UUID u) throws SQLException {
        return s.owner().equals(u.toString()) || Sql.count(tx, "SELECT COUNT(*) FROM pshop_managers WHERE shop_id=? AND uuid=?", s.id(), u.toString()) > 0;
    }

    private Shop load(Tx tx, long id) throws SQLException {
        Shop s = Sql.one(tx, SHOP_SQL + " WHERE s.id=?", PShopService::shop, id);
        if (s == null) throw new EcoException("pshop-gone");
        return s;
    }

    public CompletableFuture<Long> create(Player p, String name) {
        UUID id = p.getUniqueId();
        String owner = p.getName();
        int max = maxShops(p);
        BigDecimal cost = Money.dec(pl.getConfig().getString("playershops.creation-cost", "0"), BigDecimal.ZERO);
        return pl.db().tx(tx -> {
            if (Sql.count(tx, "SELECT COUNT(*) FROM pshops WHERE owner=?", id.toString()) >= max) throw new EcoException("pshop-limit", max);
            String cur = pl.currencies().defId();
            if (cost.signum() > 0) Ledger.transfer(tx, Acct.player(id), Acct.TREASURY, cur, cost, "PSHOP_CREATE", name);
            return Sql.insert(tx, "INSERT INTO pshops(owner,owner_name,name,description,icon,open,currency,created) VALUES(?,?,?,?,?,1,?,?)", id, owner, name, "", "CHEST", cur, System.currentTimeMillis());
        });
    }

    public CompletableFuture<Void> delete(Player p, long shopId, boolean admin) {
        UUID id = p.getUniqueId();
        return pl.db().tx(tx -> {
            Shop s = load(tx, shopId);
            if (!s.owner().equals(id.toString()) && !admin) throw new EcoException("no-permission");
            for (Item i : Sql.list(tx, "SELECT * FROM pshop_items WHERE shop_id=?", PShopService::item, shopId)) {
                if (i.stock() > 0) Mailbox.add(tx, UUID.fromString(s.owner()), Inv.decodeOr(i.itemData(), i.material()), i.stock(), "pshop-closed");
            }
            Sql.exec(tx, "DELETE FROM pshop_items WHERE shop_id=?", shopId);
            Sql.exec(tx, "DELETE FROM pshop_managers WHERE shop_id=?", shopId);
            Sql.exec(tx, "DELETE FROM pshops WHERE id=?", shopId);
            if (!s.owner().equals(id.toString())) Audit.log(tx, p.getName(), "pshop.delete", s.name(), s.ownerName());
            return null;
        });
    }

    /** Items must already be removed from the player's inventory; returned through the mailbox on failure. */
    public CompletableFuture<Void> addStock(Player p, long shopId, ItemStack tmpl, int qty, BigDecimal price) {
        UUID id = p.getUniqueId();
        String data = Inv.encode(tmpl);
        CompletableFuture<Void> f = pl.db().tx(tx -> {
            Shop s = load(tx, shopId);
            if (!canManage(tx, s, id)) throw new EcoException("no-permission");
            Long existing = Sql.one(tx, "SELECT id FROM pshop_items WHERE shop_id=? AND item_data=? AND price=?", rs -> rs.getLong(1), shopId, data, Money.scale(s.currency(), price));
            if (existing != null) Sql.exec(tx, "UPDATE pshop_items SET stock=stock+? WHERE id=?", qty, existing);
            else {
                if (Sql.count(tx, "SELECT COUNT(*) FROM pshop_items WHERE shop_id=?", shopId) >= pl.getConfig().getInt("playershops.max-items", 45)) throw new EcoException("pshop-full");
                Sql.exec(tx, "INSERT INTO pshop_items(shop_id,item_data,material,item_name,price,stock) VALUES(?,?,?,?,?,?)", shopId, data, tmpl.getType().name(), Inv.name(tmpl), Money.scale(s.currency(), price), qty);
            }
            return null;
        });
        f.whenComplete((r, t) -> { if (t != null) pl.db().tx(tx -> { Mailbox.add(tx, id, tmpl, qty, "pshop-return"); return null; }); });
        return f;
    }

    public CompletableFuture<Void> withdrawStock(Player p, long itemId, int qty, boolean removeEntry) {
        UUID id = p.getUniqueId();
        return pl.db().tx(tx -> {
            Item i = Sql.one(tx, "SELECT * FROM pshop_items WHERE id=?", PShopService::item, itemId);
            if (i == null) throw new EcoException("pshop-gone");
            Shop s = load(tx, i.shopId());
            if (!canManage(tx, s, id)) throw new EcoException("no-permission");
            int q = removeEntry ? i.stock() : qty;
            if (q < 0 || q > i.stock()) throw new EcoException("out-of-stock");
            if (q > 0) Mailbox.add(tx, id, Inv.decodeOr(i.itemData(), i.material()), q, "pshop-withdraw");
            if (removeEntry) Sql.exec(tx, "DELETE FROM pshop_items WHERE id=?", itemId);
            else Sql.exec(tx, "UPDATE pshop_items SET stock=stock-? WHERE id=?", q, itemId);
            return null;
        });
    }

    public CompletableFuture<Void> setPrice(Player p, long itemId, BigDecimal price) {
        UUID id = p.getUniqueId();
        return pl.db().tx(tx -> {
            Item i = Sql.one(tx, "SELECT * FROM pshop_items WHERE id=?", PShopService::item, itemId);
            if (i == null) throw new EcoException("pshop-gone");
            Shop s = load(tx, i.shopId());
            if (!canManage(tx, s, id)) throw new EcoException("no-permission");
            if (price.signum() <= 0) throw new EcoException("invalid-amount");
            Sql.exec(tx, "UPDATE pshop_items SET price=? WHERE id=?", Money.scale(s.currency(), price), itemId);
            return null;
        });
    }

    public record BuyResult(BigDecimal total, String currency, String itemName) {}

    public CompletableFuture<BuyResult> buy(Player p, long itemId, int qty) {
        UUID id = p.getUniqueId();
        String name = p.getName();
        return pl.db().tx(tx -> {
            if (qty <= 0) throw new EcoException("invalid-amount");
            Item i = Sql.one(tx, "SELECT * FROM pshop_items WHERE id=?", PShopService::item, itemId);
            if (i == null) throw new EcoException("pshop-gone");
            Shop s = load(tx, i.shopId());
            if (!s.open()) throw new EcoException("pshop-closed");
            UUID owner = UUID.fromString(s.owner());
            if (owner.equals(id)) throw new EcoException("pshop-own");
            if (i.stock() < qty) throw new EcoException("out-of-stock");
            BigDecimal total = Money.scale(s.currency(), i.price().multiply(BigDecimal.valueOf(qty)));
            BigDecimal comm = Money.dec(pl.getConfig().getString("playershops.commission-percent", "2"), BigDecimal.ZERO);
            Ledger.Settlement st = Ledger.settle(tx, Acct.player(id), Acct.player(owner), s.currency(), total, comm, "INCOME", owner, "PSHOP_SALE", s.name() + ": " + i.itemName() + " x" + qty);
            Sql.exec(tx, "UPDATE pshop_items SET stock=stock-?, sold=sold+? WHERE id=?", qty, qty, itemId);
            Mailbox.add(tx, id, Inv.decodeOr(i.itemData(), i.material()), qty, "pshop:" + s.name());
            tx.afterCommit(() -> {
                Player op = Bukkit.getPlayer(owner);
                if (op != null) Msg.send(op, "pshop-sale", qty, i.itemName(), Money.fmt(s.currency(), st.net()), name);
            });
            return new BuyResult(total, s.currency(), i.itemName());
        });
    }

    public CompletableFuture<Void> addManager(Player p, long shopId, String name) {
        UUID id = p.getUniqueId();
        return pl.db().tx(tx -> {
            Shop s = load(tx, shopId);
            if (!s.owner().equals(id.toString())) throw new EcoException("no-permission");
            Players.Ref r = Players.require(tx, name);
            Sql.exec(tx, "INSERT OR REPLACE INTO pshop_managers(shop_id,uuid,name) VALUES(?,?,?)", shopId, r.uuid(), r.name());
            return null;
        });
    }
}

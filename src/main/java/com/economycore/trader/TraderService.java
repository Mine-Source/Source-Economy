package com.economycore.trader;

import com.economycore.core.Acct;
import com.economycore.core.Audit;
import com.economycore.core.EcoException;
import com.economycore.core.EconomyCore;
import com.economycore.core.Inv;
import com.economycore.core.Ledger;
import com.economycore.core.Mailbox;
import com.economycore.core.Money;
import com.economycore.core.Sql;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public final class TraderService {
    private final EconomyCore pl;
    /** npc key -> trader id (in-memory copy of trader_bindings for fast event lookups). */
    public final Map<String, String> bindings = new ConcurrentHashMap<>();

    public TraderService(EconomyCore pl) { this.pl = pl; }

    public record Trader(String id, String name, String permission, boolean enabled, String icon, String ui) {}
    public record Offer(long id, String trader, String costItem, int costItemAmt, String costItem2, int costItem2Amt, BigDecimal costMoney, String costCur,
                        String resItem, int resItemAmt, BigDecimal resMoney, String resCur, int maxUses, int globalStock, boolean enabled, int sort,
                        int cooldownSec, String permission, String command) {
        /** True when the vanilla villager window can represent this offer (items for items, nothing else). */
        public boolean nativeOk() {
            if (costItem == null || costItemAmt <= 0 || resItem == null || resItemAmt <= 0) return false;
            if (costMoney != null && costMoney.signum() > 0) return false;
            if (resMoney != null && resMoney.signum() > 0) return false;
            if (command != null && !command.isBlank()) return false;
            return costItemAmt <= 64 && resItemAmt <= 64 && costItem2Amt <= 64;
        }
    }

    static Trader trader(ResultSet rs) throws SQLException {
        String ui = rs.getString("ui");
        return new Trader(rs.getString("id"), rs.getString("name"), rs.getString("permission"), rs.getInt("enabled") == 1, rs.getString("icon"), ui == null ? "AUTO" : ui);
    }

    static Offer offer(ResultSet rs) throws SQLException {
        return new Offer(rs.getLong("id"), rs.getString("trader"), rs.getString("cost_item"), rs.getInt("cost_item_amt"), rs.getString("cost_item2"), rs.getInt("cost_item2_amt"),
                Sql.dec(rs, "cost_money"), rs.getString("cost_currency"), rs.getString("res_item"), rs.getInt("res_item_amt"), Sql.dec(rs, "res_money"), rs.getString("res_currency"),
                rs.getInt("max_uses"), rs.getInt("global_stock"), rs.getInt("enabled") == 1, rs.getInt("sort"), rs.getInt("cooldown_sec"), rs.getString("permission"), rs.getString("command"));
    }

    public void loadBindings() throws Exception {
        bindings.clear();
        for (String[] b : pl.db().tx(tx -> Sql.list(tx, "SELECT npc_key, trader FROM trader_bindings", rs -> new String[]{rs.getString(1), rs.getString(2)})).get()) bindings.put(b[0], b[1]);
    }

    public CompletableFuture<List<Trader>> traders() {
        return pl.db().tx(tx -> Sql.list(tx, "SELECT * FROM traders ORDER BY name", TraderService::trader));
    }

    public CompletableFuture<Trader> get(String id) {
        return pl.db().tx(tx -> Sql.one(tx, "SELECT * FROM traders WHERE id=?", TraderService::trader, id));
    }

    public CompletableFuture<List<Offer>> offers(String trader) {
        return pl.db().tx(tx -> Sql.list(tx, "SELECT * FROM trader_offers WHERE trader=? ORDER BY sort, id", TraderService::offer, trader));
    }

    public CompletableFuture<Offer> offer(long id) {
        return pl.db().tx(tx -> Sql.one(tx, "SELECT * FROM trader_offers WHERE id=?", TraderService::offer, id));
    }

    public CompletableFuture<Map<Long, Integer>> uses(UUID p, String trader) {
        return pl.db().tx(tx -> {
            Map<Long, Integer> m = new ConcurrentHashMap<>();
            for (long[] r : Sql.list(tx, "SELECT u.offer_id,u.uses FROM trader_uses u JOIN trader_offers o ON o.id=u.offer_id WHERE o.trader=? AND u.player=?", rs -> new long[]{rs.getLong(1), rs.getLong(2)}, trader, p.toString())) m.put(r[0], (int) r[1]);
            return m;
        });
    }

    /** offer id -> last use timestamp for this player (for cooldown display). */
    public CompletableFuture<Map<Long, Long>> lastUses(UUID p, String trader) {
        return pl.db().tx(tx -> {
            Map<Long, Long> m = new ConcurrentHashMap<>();
            for (long[] r : Sql.list(tx, "SELECT u.offer_id,u.last_use FROM trader_uses u JOIN trader_offers o ON o.id=u.offer_id WHERE o.trader=? AND u.player=?", rs -> new long[]{rs.getLong(1), rs.getLong(2)}, trader, p.toString())) m.put(r[0], r[1]);
            return m;
        });
    }

    public CompletableFuture<Long> duplicate(long offerId) {
        return pl.db().tx(tx -> Sql.insert(tx, "INSERT INTO trader_offers(trader,cost_item,cost_item_amt,cost_item2,cost_item2_amt,cost_money,cost_currency,res_item,res_item_amt,res_money,res_currency,max_uses,global_stock,enabled,sort,cooldown_sec,permission,command) "
                + "SELECT trader,cost_item,cost_item_amt,cost_item2,cost_item2_amt,cost_money,cost_currency,res_item,res_item_amt,res_money,res_currency,max_uses,global_stock,enabled,"
                + "(SELECT COALESCE(MAX(sort),0)+1 FROM trader_offers t2 WHERE t2.trader=o.trader),cooldown_sec,permission,command FROM trader_offers o WHERE o.id=?", offerId));
    }

    public CompletableFuture<Void> move(String trader, long offerId, int delta) {
        return pl.db().tx(tx -> {
            List<Long> ids = new java.util.ArrayList<>(Sql.list(tx, "SELECT id FROM trader_offers WHERE trader=? ORDER BY sort, id", rs -> rs.getLong(1), trader));
            int i = ids.indexOf(offerId);
            int j = i + delta;
            if (i < 0 || j < 0 || j >= ids.size()) return null;
            java.util.Collections.swap(ids, i, j);
            for (int k = 0; k < ids.size(); k++) Sql.exec(tx, "UPDATE trader_offers SET sort=? WHERE id=?", k, ids.get(k));
            return null;
        });
    }

    public CompletableFuture<Void> create(String actor, String id, String name) {
        return pl.db().tx(tx -> {
            if (Sql.count(tx, "SELECT COUNT(*) FROM traders WHERE id=?", id) > 0) throw new EcoException("trader-exists");
            Sql.exec(tx, "INSERT INTO traders(id,name,icon,ui) VALUES(?,?,'VILLAGER_SPAWN_EGG','AUTO')", id, name);
            Audit.log(tx, actor, "trader.create", id, name);
            return null;
        });
    }

    public CompletableFuture<Void> delete(String actor, String id) {
        return pl.db().tx(tx -> {
            Sql.exec(tx, "DELETE FROM trader_uses WHERE offer_id IN (SELECT id FROM trader_offers WHERE trader=?)", id);
            Sql.exec(tx, "DELETE FROM trader_offers WHERE trader=?", id);
            Sql.exec(tx, "DELETE FROM trader_bindings WHERE trader=?", id);
            Sql.exec(tx, "DELETE FROM traders WHERE id=?", id);
            Audit.log(tx, actor, "trader.delete", id, "");
            return null;
        }).thenAccept(v -> bindings.values().removeIf(t -> t.equals(id)));
    }

    public CompletableFuture<Long> newOffer(String trader) {
        String cur = pl.currencies().defId();
        return pl.db().tx(tx -> Sql.insert(tx, "INSERT INTO trader_offers(trader,cost_currency,res_currency,sort) VALUES(?,?,?,?)", trader, cur, cur,
                (int) Sql.count(tx, "SELECT COUNT(*) FROM trader_offers WHERE trader=?", trader)));
    }

    public CompletableFuture<Void> bind(String actor, String key, String trader) {
        return pl.db().tx(tx -> {
            Sql.exec(tx, "INSERT OR REPLACE INTO trader_bindings(npc_key,trader) VALUES(?,?)", key, trader);
            Audit.log(tx, actor, "trader.bind", trader, key);
            return null;
        }).thenAccept(v -> bindings.put(key, trader));
    }

    public CompletableFuture<Void> unbind(String key) {
        return pl.db().tx(tx -> { Sql.exec(tx, "DELETE FROM trader_bindings WHERE npc_key=?", key); return null; }).thenAccept(v -> bindings.remove(key));
    }

    private static boolean has(String item, int amt) { return item != null && amt > 0; }

    /** Executes one trade. Cost items are removed first (main thread) and returned if the transaction fails. */
    public CompletableFuture<Void> execute(Player p, long offerId) {
        UUID id = p.getUniqueId();
        String name = p.getName();
        return offer(offerId).thenCompose(o -> {
            if (o == null || !o.enabled()) return CompletableFuture.failedFuture(new EcoException("trade-gone"));
            CompletableFuture<Void> gate = new CompletableFuture<>();
            com.economycore.core.Tasks.sync(() -> {
                if (o.permission() != null && !o.permission().isBlank() && !p.hasPermission(o.permission()) && !p.hasPermission("economycore.trader.admin")) {
                    gate.completeExceptionally(new EcoException("no-permission"));
                    return;
                }
                ItemStack t1 = has(o.costItem(), o.costItemAmt()) ? Inv.decode(o.costItem()) : null;
                ItemStack t2 = has(o.costItem2(), o.costItem2Amt()) ? Inv.decode(o.costItem2()) : null;
                if (t1 != null && t2 != null && t1.isSimilar(t2)) {
                    if (Inv.count(p, t1) < o.costItemAmt() + o.costItem2Amt()) { gate.completeExceptionally(new EcoException("not-enough-items")); return; }
                } else {
                    if (t1 != null && Inv.count(p, t1) < o.costItemAmt()) { gate.completeExceptionally(new EcoException("not-enough-items")); return; }
                    if (t2 != null && Inv.count(p, t2) < o.costItem2Amt()) { gate.completeExceptionally(new EcoException("not-enough-items")); return; }
                }
                boolean took1 = t1 != null && Inv.take(p, t1, o.costItemAmt());
                if (t1 != null && !took1) { gate.completeExceptionally(new EcoException("not-enough-items")); return; }
                if (t2 != null && !Inv.take(p, t2, o.costItem2Amt())) {
                    if (took1) Inv.give(p, t1, o.costItemAmt());
                    gate.completeExceptionally(new EcoException("not-enough-items"));
                    return;
                }
                run(id, name, o).whenComplete((r, t) -> {
                    if (t != null) {
                        pl.db().tx(tx -> {
                            if (t1 != null) Mailbox.add(tx, id, t1, o.costItemAmt(), "trader-refund");
                            if (t2 != null) Mailbox.add(tx, id, t2, o.costItem2Amt(), "trader-refund");
                            return null;
                        });
                        gate.completeExceptionally(Db_unwrap(t));
                    } else gate.complete(null);
                });
            });
            return gate;
        });
    }

    private static Throwable Db_unwrap(Throwable t) { return com.economycore.core.Db.unwrap(t); }

    private CompletableFuture<Void> run(UUID id, String playerName, Offer pre) {
        return pl.db().tx(tx -> {
            Offer o = Sql.one(tx, "SELECT * FROM trader_offers WHERE id=?", TraderService::offer, pre.id());
            if (o == null || !o.enabled()) throw new EcoException("trade-gone");
            Trader t = Sql.one(tx, "SELECT * FROM traders WHERE id=?", TraderService::trader, o.trader());
            if (t == null || !t.enabled()) throw new EcoException("trade-gone");
            long now = System.currentTimeMillis();
            long[] use = Sql.one(tx, "SELECT uses,last_use FROM trader_uses WHERE offer_id=? AND player=?", rs -> new long[]{rs.getLong(1), rs.getLong(2)}, o.id(), id.toString());
            if (o.maxUses() > 0 && use != null && use[0] >= o.maxUses()) throw new EcoException("trade-limit");
            if (o.cooldownSec() > 0 && use != null && use[1] > 0 && now - use[1] < o.cooldownSec() * 1000L) {
                throw new EcoException("trade-cooldown", com.economycore.core.Fmt.duration(o.cooldownSec() * 1000L - (now - use[1])));
            }
            if (o.globalStock() == 0) throw new EcoException("out-of-stock");
            if (o.costMoney() != null && o.costMoney().signum() > 0) Ledger.transfer(tx, Acct.player(id), Acct.SYSTEM, o.costCur(), o.costMoney(), "TRADER_COST", "offer " + o.id());
            if (o.resMoney() != null && o.resMoney().signum() > 0) Ledger.transfer(tx, Acct.SYSTEM, Acct.player(id), o.resCur(), o.resMoney(), "TRADER_REWARD", "offer " + o.id());
            if (o.resItem() != null && o.resItemAmt() > 0) Mailbox.add(tx, id, o.resItem(), o.resItemAmt(), "trader:" + t.name());
            Sql.exec(tx, "INSERT INTO trader_uses(offer_id,player,uses,last_use) VALUES(?,?,1,?) ON CONFLICT(offer_id,player) DO UPDATE SET uses=uses+1, last_use=excluded.last_use", o.id(), id.toString(), now);
            if (o.globalStock() > 0) Sql.exec(tx, "UPDATE trader_offers SET global_stock=global_stock-1 WHERE id=?", o.id());
            if (o.command() != null && !o.command().isBlank()) {
                final String[] cmds = o.command().split("\\s*\\|\\|\\s*");
                tx.afterCommit(() -> {
                    for (String c : cmds) {
                        String line = c.trim().replace("%player%", playerName).replace("{player}", playerName);
                        if (line.startsWith("/")) line = line.substring(1);
                        if (!line.isEmpty()) org.bukkit.Bukkit.dispatchCommand(org.bukkit.Bukkit.getConsoleSender(), line);
                    }
                });
            }
            Audit.log(tx, playerName, "trader.trade", o.trader(), "offer " + o.id());
            return null;
        });
    }

    public String describeCost(Offer o) {
        StringBuilder sb = new StringBuilder();
        if (o.costItem() != null && o.costItemAmt() > 0) {
            ItemStack it = Inv.decode(o.costItem());
            sb.append(o.costItemAmt()).append("x ").append(it == null ? "?" : Inv.name(it));
        }
        if (o.costItem2() != null && o.costItem2Amt() > 0) {
            ItemStack it2 = Inv.decode(o.costItem2());
            if (sb.length() > 0) sb.append(" + ");
            sb.append(o.costItem2Amt()).append("x ").append(it2 == null ? "?" : Inv.name(it2));
        }
        if (o.costMoney() != null && o.costMoney().signum() > 0) {
            if (sb.length() > 0) sb.append(" + ");
            sb.append(Money.fmt(o.costCur(), o.costMoney()));
        }
        return sb.length() == 0 ? "Gratis" : sb.toString();
    }

    public String describeResult(Offer o) {
        StringBuilder sb = new StringBuilder();
        if (o.resItem() != null && o.resItemAmt() > 0) {
            ItemStack it = Inv.decode(o.resItem());
            sb.append(o.resItemAmt()).append("x ").append(it == null ? "?" : Inv.name(it));
        }
        if (o.resMoney() != null && o.resMoney().signum() > 0) {
            if (sb.length() > 0) sb.append(" + ");
            sb.append(Money.fmt(o.resCur(), o.resMoney()));
        }
        if (o.command() != null && !o.command().isBlank()) {
            if (sb.length() > 0) sb.append(" + ");
            sb.append("Recompensa especial");
        }
        return sb.length() == 0 ? "Nada" : sb.toString();
    }
}

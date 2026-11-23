package com.economycore.core;

import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Durable item delivery. Every module stores items a player is owed here inside the same transaction that moved the money,
 * so a crash can never lose or duplicate items. Delivery claims rows atomically before giving anything.
 */
public final class Mailbox {
    private Mailbox() {}

    public record Row(long id, String itemData, int amount, String source, long ts) {}

    private static final Set<UUID> BUSY = ConcurrentHashMap.newKeySet();

    public static void add(Tx tx, UUID owner, String itemData, int amount, String source) throws SQLException {
        if (amount <= 0) return;
        Sql.exec(tx, "INSERT INTO pending_items(owner,item_data,amount,source,ts) VALUES(?,?,?,?,?)", owner, itemData, amount, source, System.currentTimeMillis());
        tx.afterCommit(() -> {
            Player p = Bukkit.getPlayer(owner);
            if (p != null) deliver(p);
        });
    }

    public static void add(Tx tx, UUID owner, ItemStack tmpl, int amount, String source) throws SQLException {
        add(tx, owner, Inv.encode(tmpl), amount, source);
    }

    public static List<Row> list(Tx tx, UUID owner, int limit) throws SQLException {
        return Sql.list(tx, "SELECT id,item_data,amount,source,ts FROM pending_items WHERE owner=? ORDER BY id LIMIT ?",
                rs -> new Row(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getString(4), rs.getLong(5)), owner, limit);
    }

    public static void deliver(Player p) {
        EconomyCore pl = EconomyCore.get();
        UUID id = p.getUniqueId();
        if (!BUSY.add(id)) return;
        pl.db().tx(tx -> list(tx, id, 200)).whenComplete((rows, t) -> Tasks.sync(() -> {
            if (t != null || rows == null || rows.isEmpty()) { BUSY.remove(id); return; }
            step(p, rows, 0, 0);
        }));
    }

    private static void step(Player p, List<Row> rows, int i, int given) {
        UUID id = p.getUniqueId();
        if (i >= rows.size() || !p.isOnline()) { finish(p, given); return; }
        Row r = rows.get(i);
        ItemStack tmpl = Inv.decodeOr(r.itemData(), null);
        int fit = Inv.space(p, tmpl);
        if (fit <= 0) { finish(p, given); Msg.send(p, "mailbox-full"); return; }
        int take = Math.min(fit, r.amount());
        EconomyCore.get().db().tx(tx -> {
            int n = Sql.exec(tx, "UPDATE pending_items SET amount=amount-? WHERE id=? AND amount>=?", take, r.id(), take);
            if (n == 0) return false;
            Sql.exec(tx, "DELETE FROM pending_items WHERE id=? AND amount<=0", r.id());
            return true;
        }).whenComplete((ok, t) -> Tasks.sync(() -> {
            int g = given;
            if (t == null && Boolean.TRUE.equals(ok)) {
                int left = Inv.give(p, tmpl, take);
                g += take - left;
                if (left > 0) {
                    final int back = left;
                    EconomyCore.get().db().tx(tx -> { Mailbox.add(tx, id, r.itemData(), back, r.source()); return null; });
                }
            }
            if (take < r.amount()) { finish(p, g); Msg.send(p, "mailbox-full"); }
            else step(p, rows, i + 1, g);
        }));
    }

    private static void finish(Player p, int given) {
        BUSY.remove(p.getUniqueId());
        if (given > 0) Msg.send(p, "mailbox-delivered", given);
    }
}

package com.economycore.core;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Durable player notifications. The row is written in the same transaction as the event that caused it;
 * online players get it right after the commit, offline players receive it when they join.
 */
public final class Notify {
    private Notify() {}

    private record Row(String key, String[] args) {}

    /** Queues a notification inside a transaction. Args are plain strings (already formatted). */
    public static void to(Tx tx, UUID target, String key, Object... args) throws java.sql.SQLException {
        EconomyCore pl = EconomyCore.get();
        if (!pl.getConfig().getBoolean("notifications.enabled", true) || target == null) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) { if (i > 0) sb.append('\u0001'); sb.append(args[i]); }
        Sql.exec(tx, "INSERT INTO notifications(uuid,ts,key,args) VALUES(?,?,?,?)", target.toString(), System.currentTimeMillis(), key, sb.toString());
        Sql.exec(tx, "DELETE FROM notifications WHERE uuid=? AND id NOT IN (SELECT id FROM notifications WHERE uuid=? ORDER BY id DESC LIMIT 50)", target.toString(), target.toString());
        tx.afterCommit(() -> { Player p = Bukkit.getPlayer(target); if (p != null) flush(p); });
    }

    /** Delivers and removes every pending notification of the player. Main thread. */
    public static void flush(Player p) {
        EconomyCore pl = EconomyCore.get();
        UUID id = p.getUniqueId();
        pl.db().tx(tx -> {
            List<Row> rows = Sql.list(tx, "SELECT key,args FROM notifications WHERE uuid=? ORDER BY id", rs -> new Row(rs.getString(1), rs.getString(2) == null ? new String[0] : rs.getString(2).split("\u0001", -1)), id.toString());
            if (!rows.isEmpty()) Sql.exec(tx, "DELETE FROM notifications WHERE uuid=?", id.toString());
            return new ArrayList<>(rows);
        }).whenComplete((rows, t) -> Tasks.sync(() -> {
            if (t != null || rows == null || rows.isEmpty() || !p.isOnline()) return;
            for (Row r : rows) Msg.send(p, r.key(), (Object[]) r.args());
            if (pl.getConfig().getBoolean("notifications.sound", true)) {
                try { p.playSound(p.getLocation(), "entity.experience_orb.pickup", 0.8f, 1.3f); } catch (Throwable ignored) {}
            }
        }));
    }
}
